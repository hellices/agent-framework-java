package io.github.hellices.agentframework.engine;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hellices.agentframework.api.agent.Agent;
import io.github.hellices.agentframework.api.agent.AgentDefinition;
import io.github.hellices.agentframework.api.agent.AgentResponse;
import io.github.hellices.agentframework.api.agent.AgentResponseUpdate;
import io.github.hellices.agentframework.api.agent.AgentRunOptions;
import io.github.hellices.agentframework.api.agent.AgentRunRequest;
import io.github.hellices.agentframework.api.agent.AgentRuntime;
import io.github.hellices.agentframework.api.agent.AgentSession;
import io.github.hellices.agentframework.api.agent.AgentStreamingRun;
import io.github.hellices.agentframework.api.agent.CancellationSignal;
import io.github.hellices.agentframework.api.agent.RunContribution;
import io.github.hellices.agentframework.api.context.ContextAttributes;
import io.github.hellices.agentframework.api.message.Content;
import io.github.hellices.agentframework.api.message.FinishReason;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.Role;
import io.github.hellices.agentframework.api.message.TextContent;
import io.github.hellices.agentframework.api.message.ToolApprovalRequestContent;
import io.github.hellices.agentframework.api.message.ToolApprovalResponseContent;
import io.github.hellices.agentframework.api.message.ToolCallContent;
import io.github.hellices.agentframework.api.message.ToolResultContent;
import io.github.hellices.agentframework.api.session.SessionContext;
import io.github.hellices.agentframework.api.session.SessionSnapshot;
import io.github.hellices.agentframework.api.session.SessionStateEntry;
import io.github.hellices.agentframework.api.session.SessionStateKey;
import io.github.hellices.agentframework.api.tool.FunctionTool;
import io.github.hellices.agentframework.api.tool.ToolApprovalDecision;
import io.github.hellices.agentframework.api.tool.ToolApprovalPolicy;
import io.github.hellices.agentframework.api.tool.ToolApprovalRule;
import io.github.hellices.agentframework.api.tool.ToolApprovalSettings;
import io.github.hellices.agentframework.api.tool.ToolBinding;
import io.github.hellices.agentframework.api.tool.ToolResult;
import io.github.hellices.agentframework.api.value.JsonObject;
import io.github.hellices.agentframework.api.value.JsonValues;
import io.github.hellices.agentframework.engine.internal.tool.ToolApprovalQueueState;
import io.github.hellices.agentframework.spi.model.ModelClient;
import io.github.hellices.agentframework.spi.model.ModelResponse;
import io.github.hellices.agentframework.spi.session.ProviderSessionState;
import io.github.hellices.agentframework.spi.session.SessionStore;
import io.github.hellices.agentframework.spi.session.StatefulContextProvider;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The tool approval state machine of the one ordinary/streaming run pipeline (TOOL-016 through
 * TOOL-021), plus the Task 4 persisted-state contract it builds on: the approval queue is a
 * versioned, engine-registered session state type that survives a save/load round trip under its
 * reserved state key.
 */
class AgentEngineToolApprovalTest {

  @Test
  void approvalQueueStateRoundTripsThroughTheEngineDefaultRegistryWithoutACustomRegistry() {
    RecordingSessionStore store = new RecordingSessionStore();
    ToolApprovalRequestContent first =
        new ToolApprovalRequestContent(
            "req-1", "call-1", "weather", jsonObject(Map.of("city", "Seoul")), "mcp-server-a");
    ToolApprovalRequestContent second =
        new ToolApprovalRequestContent(
            "req-2", "call-2", "search", jsonObject(Map.of("query", "news")), null);
    ApprovalQueueProvider writer =
        new ApprovalQueueProvider(
            new ToolApprovalQueueState(List.of(first, second)), /* write= */ true);
    Agent writerAgent = agentWithStore(store, fixedClient("hello"), writer);

    runWithSession(writerAgent, "session-1");

    SessionSnapshot savedSnapshot = store.saved.get("session-1");
    assertThat(savedSnapshot).isNotNull();
    SessionStateEntry entry = savedSnapshot.state().get(ToolApprovalQueueState.STATE_ID);
    assertThat(entry).isNotNull();
    assertThat(entry.typeId()).isEqualTo("engine.tool_approval_queue");
    assertThat(entry.codecVersion()).isEqualTo(1);

    ApprovalQueueProvider reader = new ApprovalQueueProvider(null, /* write= */ false);
    Agent readerAgent = agentWithStore(store, fixedClient("hello"), reader);

    runWithSession(readerAgent, "session-1");

    assertThat(reader.observed).isPresent();
    assertThat(reader.observed.get().pending()).containsExactly(first, second);
  }

  @Test
  void approvalQueueStatePreservesFifoOrderAndExactArgumentsAcrossARoundTrip() {
    RecordingSessionStore store = new RecordingSessionStore();
    ToolApprovalRequestContent nested =
        new ToolApprovalRequestContent(
            "req-1",
            "call-1",
            "weather",
            jsonObject(Map.of("city", "Seoul", "days", 3)),
            "mcp-server-a");
    ApprovalQueueProvider writer =
        new ApprovalQueueProvider(new ToolApprovalQueueState(List.of(nested)), true);
    runWithSession(agentWithStore(store, fixedClient("hello"), writer), "session-2");

    ApprovalQueueProvider reader = new ApprovalQueueProvider(null, false);
    runWithSession(agentWithStore(store, fixedClient("hello"), reader), "session-2");

    assertThat(reader.observed).isPresent();
    ToolApprovalRequestContent restored = reader.observed.get().pending().get(0);
    assertThat(restored.requestId()).isEqualTo("req-1");
    assertThat(restored.toolCallId()).isEqualTo("call-1");
    assertThat(restored.toolName()).isEqualTo("weather");
    assertThat(restored.arguments()).isEqualTo(jsonObject(Map.of("city", "Seoul", "days", 3L)));
    assertThat(restored.hostBoundary()).contains("mcp-server-a");
  }

  @Test
  void approvalQueueStateRoundTripsAnAbsentHostBoundaryAsEmpty() {
    RecordingSessionStore store = new RecordingSessionStore();
    ToolApprovalRequestContent withoutHostBoundary =
        new ToolApprovalRequestContent("req-1", "call-1", "search", JsonObject.empty(), null);
    ApprovalQueueProvider writer =
        new ApprovalQueueProvider(new ToolApprovalQueueState(List.of(withoutHostBoundary)), true);
    runWithSession(agentWithStore(store, fixedClient("hello"), writer), "session-3");

    ApprovalQueueProvider reader = new ApprovalQueueProvider(null, false);
    runWithSession(agentWithStore(store, fixedClient("hello"), reader), "session-3");

    assertThat(reader.observed).isPresent();
    assertThat(reader.observed.get().pending().get(0).hostBoundary()).isEmpty();
  }

  @Test
  void emptyApprovalQueueStateRoundTripsToAnEmptyQueue() {
    RecordingSessionStore store = new RecordingSessionStore();
    ApprovalQueueProvider writer = new ApprovalQueueProvider(ToolApprovalQueueState.empty(), true);
    runWithSession(agentWithStore(store, fixedClient("hello"), writer), "session-4");

    ApprovalQueueProvider reader = new ApprovalQueueProvider(null, false);
    runWithSession(agentWithStore(store, fixedClient("hello"), reader), "session-4");

    assertThat(reader.observed).isPresent();
    assertThat(reader.observed.get().pending()).isEmpty();
  }

  @Test
  void aCallRequiringApprovalSurfacesTheRequestWithoutExecutingTheTool() {
    ApprovalFixture fixture =
        new ApprovalFixture(
            requireApproval(), List.of(weatherCall(), textResponse("It is sunny")), "weather");

    AgentResponse response = fixture.run("session-approval-1", userText("hi"));

    ToolApprovalRequestContent request = onlyApprovalRequest(response);
    assertThat(request.toolCallId()).isEqualTo("call-1");
    assertThat(request.toolName()).isEqualTo("weather");
    assertThat(request.arguments()).isEqualTo(jsonObject(Map.of("city", "Seoul")));
    assertThat(fixture.invocations).isEmpty();
    assertThat(fixture.modelCalls).hasValue(1);
    assertThat(toolResults(response)).isEmpty();
  }

  @Test
  void anApprovedResponseExecutesThePersistedCallAndResumesTheLoop() {
    ApprovalFixture fixture =
        new ApprovalFixture(
            requireApproval(), List.of(weatherCall(), textResponse("It is sunny")), "weather");
    String requestId =
        onlyApprovalRequest(fixture.run("session-approval-2", userText("hi"))).requestId();

    AgentResponse resumed = fixture.run("session-approval-2", approvalResponse(requestId, true));

    assertThat(fixture.invocations).containsExactly(invocation("weather", Map.of("city", "Seoul")));
    assertThat(toolResults(resumed)).containsExactly("call-1:weather:false:[weather-ran]");
    assertThat(resumed.text()).isEqualTo("It is sunny");
    assertThat(fixture.modelCalls).hasValue(2);
    assertThat(approvalRequests(resumed)).isEmpty();
  }

  @Test
  void anApprovalResponseForAnUnknownRequestResolvesNothingAndResurfacesTheSameRequest() {
    ApprovalFixture fixture =
        new ApprovalFixture(
            requireApproval(), List.of(weatherCall(), textResponse("It is sunny")), "weather");
    String requestId =
        onlyApprovalRequest(fixture.run("session-approval-3", userText("hi"))).requestId();

    AgentResponse resumed = fixture.run("session-approval-3", approvalResponse("forged", true));

    assertThat(onlyApprovalRequest(resumed).requestId()).isEqualTo(requestId);
    assertThat(fixture.invocations).isEmpty();
    assertThat(fixture.modelCalls).hasValue(1);
  }

  @Test
  void anApprovalResponseRebindsToThePersistedRequestRatherThanCallerSuppliedContent() {
    ApprovalFixture fixture =
        new ApprovalFixture(
            requireApproval(), List.of(weatherCall(), textResponse("It is sunny")), "weather");
    String requestId =
        onlyApprovalRequest(fixture.run("session-approval-4", userText("hi"))).requestId();

    AgentResponse resumed =
        fixture.run(
            "session-approval-4",
            new Message(
                Role.USER,
                List.of(
                    ToolApprovalResponseContent.approve(requestId),
                    new ToolCallContent(
                        "call-1", "danger", jsonObject(Map.of("city", "Pyongyang"))))));

    assertThat(fixture.invocations).containsExactly(invocation("weather", Map.of("city", "Seoul")));
    assertThat(toolResults(resumed)).containsExactly("call-1:weather:false:[weather-ran]");
  }

  @Test
  void aDenialAppendsTheStableSyntheticErrorWithoutInvokingTheTool() {
    ApprovalFixture fixture =
        new ApprovalFixture(
            requireApproval(), List.of(weatherCall(), textResponse("understood")), "weather");
    String requestId =
        onlyApprovalRequest(fixture.run("session-approval-5", userText("hi"))).requestId();

    AgentResponse resumed = fixture.run("session-approval-5", approvalResponse(requestId, false));

    assertThat(fixture.invocations).isEmpty();
    assertThat(toolResults(resumed))
        .containsExactly("call-1:weather:true:[Error: Tool call invocation was rejected by user.]");
    assertThat(resumed.text()).isEqualTo("understood");
    assertThat(fixture.modelCalls).hasValue(2);
  }

  @Test
  void queuedApprovalsSurfaceOneAtATimeAndNoToolRunsUntilTheLastIsResolved() {
    ApprovalFixture fixture =
        new ApprovalFixture(
            requireApproval(),
            List.of(
                modelResponse(
                    new Message(
                        Role.ASSISTANT,
                        List.of(
                            new ToolCallContent(
                                "call-1", "weather", jsonObject(Map.of("city", "Seoul"))),
                            new ToolCallContent(
                                "call-2", "search", jsonObject(Map.of("query", "news"))))),
                    FinishReason.TOOL_CALLS),
                textResponse("all done")),
            "weather",
            "search");

    ToolApprovalRequestContent first =
        onlyApprovalRequest(fixture.run("session-queue", userText("hi")));
    assertThat(first.toolCallId()).isEqualTo("call-1");

    ToolApprovalRequestContent second =
        onlyApprovalRequest(
            fixture.run("session-queue", approvalResponse(first.requestId(), true)));
    assertThat(second.toolCallId()).isEqualTo("call-2");
    assertThat(fixture.invocations).isEmpty();
    assertThat(fixture.modelCalls).hasValue(1);

    AgentResponse resumed =
        fixture.run("session-queue", approvalResponse(second.requestId(), true));

    assertThat(fixture.invocations)
        .containsExactly(
            invocation("weather", Map.of("city", "Seoul")),
            invocation("search", Map.of("query", "news")));
    assertThat(toolResults(resumed))
        .containsExactly("call-1:weather:false:[weather-ran]", "call-2:search:false:[search-ran]");
    assertThat(fixture.modelCalls).hasValue(2);
  }

  @Test
  void aBatchMixingAnAutoApprovedAndAnUnresolvedCallExecutesBothOnlyOnceTheLastIsResolved() {
    ApprovalFixture fixture =
        new ApprovalFixture(
            ToolApprovalSettings.builder()
                .standingApproval(ToolApprovalRule.forTool("weather"))
                .build(),
            List.of(
                modelResponse(
                    new Message(
                        Role.ASSISTANT,
                        List.of(
                            new ToolCallContent(
                                "call-1", "weather", jsonObject(Map.of("city", "Seoul"))),
                            new ToolCallContent(
                                "call-2", "search", jsonObject(Map.of("query", "news"))))),
                    FinishReason.TOOL_CALLS),
                textResponse("all done")),
            "weather",
            "search");

    ToolApprovalRequestContent request =
        onlyApprovalRequest(fixture.run("session-mixed", userText("hi")));

    assertThat(request.toolCallId()).isEqualTo("call-2");
    assertThat(fixture.invocations).isEmpty();

    AgentResponse resumed =
        fixture.run("session-mixed", approvalResponse(request.requestId(), true));

    assertThat(fixture.invocations)
        .containsExactly(
            invocation("weather", Map.of("city", "Seoul")),
            invocation("search", Map.of("query", "news")));
    assertThat(toolResults(resumed))
        .containsExactly("call-1:weather:false:[weather-ran]", "call-2:search:false:[search-ran]");
  }

  @Test
  void aBatchMixingADeniedAndAnApprovedCallKeepsBothDecisionsAcrossTheResumingRun() {
    ApprovalFixture fixture =
        new ApprovalFixture(
            requireApproval(),
            List.of(
                modelResponse(
                    new Message(
                        Role.ASSISTANT,
                        List.of(
                            new ToolCallContent(
                                "call-1", "weather", jsonObject(Map.of("city", "Seoul"))),
                            new ToolCallContent(
                                "call-2", "search", jsonObject(Map.of("query", "news"))))),
                    FinishReason.TOOL_CALLS),
                textResponse("all done")),
            "weather",
            "search");

    ToolApprovalRequestContent first =
        onlyApprovalRequest(fixture.run("session-mixed-deny", userText("hi")));
    ToolApprovalRequestContent second =
        onlyApprovalRequest(
            fixture.run("session-mixed-deny", approvalResponse(first.requestId(), false)));
    AgentResponse resumed =
        fixture.run("session-mixed-deny", approvalResponse(second.requestId(), true));

    assertThat(fixture.invocations).containsExactly(invocation("search", Map.of("query", "news")));
    assertThat(toolResults(resumed))
        .containsExactly(
            "call-1:weather:true:[Error: Tool call invocation was rejected by user.]",
            "call-2:search:false:[search-ran]");
  }

  @Test
  void aStandingRuleOnExactArgumentsAutoApprovesWithoutACallerRoundTrip() {
    ApprovalFixture fixture =
        new ApprovalFixture(
            ToolApprovalSettings.builder()
                .standingApproval(
                    ToolApprovalRule.forArguments("weather", jsonObject(Map.of("city", "Seoul"))))
                .build(),
            List.of(weatherCall(), textResponse("It is sunny")),
            "weather");

    AgentResponse response = fixture.run("session-standing-1", userText("hi"));

    assertThat(approvalRequests(response)).isEmpty();
    assertThat(fixture.invocations).containsExactly(invocation("weather", Map.of("city", "Seoul")));
    assertThat(response.text()).isEqualTo("It is sunny");
  }

  @Test
  void aStandingRuleOnOtherArgumentsDoesNotAutoApprove() {
    ApprovalFixture fixture =
        new ApprovalFixture(
            ToolApprovalSettings.builder()
                .standingApproval(
                    ToolApprovalRule.forArguments("weather", jsonObject(Map.of("city", "Busan"))))
                .build(),
            List.of(weatherCall(), textResponse("It is sunny")),
            "weather");

    AgentResponse response = fixture.run("session-standing-2", userText("hi"));

    assertThat(onlyApprovalRequest(response).toolName()).isEqualTo("weather");
    assertThat(fixture.invocations).isEmpty();
  }

  @Test
  void aToolWideStandingRuleAutoApprovesAnyArgumentsWhileAnEmptyArgumentRuleDoesNot() {
    ApprovalFixture toolWide =
        new ApprovalFixture(
            ToolApprovalSettings.builder()
                .standingApproval(ToolApprovalRule.forTool("weather"))
                .build(),
            List.of(weatherCall(), textResponse("It is sunny")),
            "weather");

    assertThat(approvalRequests(toolWide.run("session-standing-3", userText("hi")))).isEmpty();
    assertThat(toolWide.invocations)
        .containsExactly(invocation("weather", Map.of("city", "Seoul")));

    ApprovalFixture noArguments =
        new ApprovalFixture(
            ToolApprovalSettings.builder()
                .standingApproval(ToolApprovalRule.forArguments("weather", JsonObject.empty()))
                .build(),
            List.of(weatherCall(), textResponse("It is sunny")),
            "weather");

    assertThat(onlyApprovalRequest(noArguments.run("session-standing-4", userText("hi"))))
        .isNotNull();
    assertThat(noArguments.invocations).isEmpty();
  }

  @Test
  void aStandingRuleDoesNotApplyAcrossHostBoundaries() {
    ApprovalFixture otherHost =
        new ApprovalFixture(
            ToolApprovalSettings.builder()
                .hostBoundaryResolver(call -> "mcp-a")
                .standingApproval(
                    ToolApprovalRule.forArguments(
                        "weather", jsonObject(Map.of("city", "Seoul")), "mcp-b"))
                .build(),
            List.of(weatherCall(), textResponse("It is sunny")),
            "weather");

    ToolApprovalRequestContent request =
        onlyApprovalRequest(otherHost.run("session-host-1", userText("hi")));
    assertThat(request.hostBoundary()).contains("mcp-a");
    assertThat(otherHost.invocations).isEmpty();

    ApprovalFixture sameHost =
        new ApprovalFixture(
            ToolApprovalSettings.builder()
                .hostBoundaryResolver(call -> "mcp-a")
                .standingApproval(
                    ToolApprovalRule.forArguments(
                        "weather", jsonObject(Map.of("city", "Seoul")), "mcp-a"))
                .build(),
            List.of(weatherCall(), textResponse("It is sunny")),
            "weather");

    assertThat(approvalRequests(sameHost.run("session-host-2", userText("hi")))).isEmpty();
    assertThat(sameHost.invocations)
        .containsExactly(invocation("weather", Map.of("city", "Seoul")));
  }

  @Test
  void aMatchingStandingRuleIsAppliedBeforeTheHeuristicPolicyIsEvaluated() {
    AtomicInteger evaluations = new AtomicInteger();
    ToolApprovalPolicy heuristic =
        context -> {
          evaluations.incrementAndGet();
          return ToolApprovalDecision.DENY;
        };
    ApprovalFixture fixture =
        new ApprovalFixture(
            ToolApprovalSettings.builder()
                .standingApproval(ToolApprovalRule.forTool("weather"))
                .policy(heuristic)
                .build(),
            List.of(weatherCall(), textResponse("It is sunny")),
            "weather");

    fixture.run("session-precedence", userText("hi"));

    assertThat(evaluations).hasValue(0);
    assertThat(fixture.invocations).containsExactly(invocation("weather", Map.of("city", "Seoul")));
  }

  @Test
  void automaticApprovalsStopAtTheConfiguredUpperBound() {
    ApprovalFixture fixture =
        new ApprovalFixture(
            ToolApprovalSettings.builder()
                .policy(context -> ToolApprovalDecision.APPROVE)
                .maxAutomaticApprovals(1)
                .build(),
            List.of(
                weatherCall(),
                modelResponse(
                    new Message(
                        Role.ASSISTANT,
                        List.of(
                            new ToolCallContent(
                                "call-2", "weather", jsonObject(Map.of("city", "Busan"))))),
                    FinishReason.TOOL_CALLS),
                textResponse("unreachable")),
            "weather");

    AgentResponse response = fixture.run("session-cap", userText("hi"));

    assertThat(fixture.invocations).containsExactly(invocation("weather", Map.of("city", "Seoul")));
    ToolApprovalRequestContent request = onlyApprovalRequest(response);
    assertThat(request.toolCallId()).isEqualTo("call-2");
    assertThat(fixture.modelCalls).hasValue(2);
  }

  @Test
  void configuredApprovalWithoutASessionFailsImmediatelyForBothRunShapes() {
    ApprovalFixture fixture =
        new ApprovalFixture(
            requireApproval(), List.of(weatherCall(), textResponse("It is sunny")), "weather");

    assertThatThrownBy(() -> fixture.agent.run("hi"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("tool approval requires a session");
    assertThatThrownBy(() -> fixture.agent.runStreaming("hi"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("tool approval requires a session");
    assertThat(fixture.modelCalls).hasValue(0);
  }

  @Test
  void theUnresolvedQueueIsPersistedUnderItsReservedKeyAndClearedOnceResolved() {
    ApprovalFixture fixture =
        new ApprovalFixture(
            requireApproval(), List.of(weatherCall(), textResponse("It is sunny")), "weather");

    String requestId =
        onlyApprovalRequest(fixture.run("session-persist", userText("hi"))).requestId();

    SessionStateEntry pending =
        fixture.store.saved.get("session-persist").state().get(ToolApprovalQueueState.STATE_ID);
    assertThat(pending).isNotNull();
    assertThat(pending.typeId()).isEqualTo("engine.tool_approval_queue");

    fixture.run("session-persist", approvalResponse(requestId, true));

    assertThat(
            fixture.store.saved.get("session-persist").state().get(ToolApprovalQueueState.STATE_ID))
        .isNull();
  }

  @Test
  void aCancelledResumeRunNeitherExecutesTheToolNorConsumesTheQueue() {
    ApprovalFixture fixture =
        new ApprovalFixture(
            requireApproval(), List.of(weatherCall(), textResponse("It is sunny")), "weather");
    String requestId =
        onlyApprovalRequest(fixture.run("session-cancel", userText("hi"))).requestId();
    CancellationSignal cancelled = new CancellationSignal();
    cancelled.cancel();

    assertThatThrownBy(
            () ->
                fixture
                    .agent
                    .run(
                        AgentRunRequest.builder()
                            .messages(List.of(approvalResponse(requestId, true)))
                            .session(AgentSession.builder().sessionId("session-cancel").build())
                            .options(new AgentRunOptions())
                            .cancellationSignal(cancelled)
                            .attributes(ContextAttributes.empty())
                            .build())
                    .response()
                    .toCompletableFuture()
                    .join())
        .rootCause()
        .isInstanceOf(java.util.concurrent.CancellationException.class);
    assertThat(fixture.invocations).isEmpty();

    AgentResponse resumed = fixture.run("session-cancel", approvalResponse(requestId, true));

    assertThat(fixture.invocations).containsExactly(invocation("weather", Map.of("city", "Seoul")));
    assertThat(toolResults(resumed)).containsExactly("call-1:weather:false:[weather-ran]");
  }

  @Test
  void aToolFailureWhileResumingAnApprovedCallFailsTheRun() {
    ApprovalFixture fixture =
        new ApprovalFixture(
            requireApproval(),
            List.of(failingCall(), textResponse("unreachable")),
            List.of("boom"),
            true);
    String requestId =
        onlyApprovalRequest(fixture.run("session-tool-failure", userText("hi"))).requestId();

    assertThatThrownBy(() -> fixture.run("session-tool-failure", approvalResponse(requestId, true)))
        .rootCause()
        .hasMessage("tool exploded");
  }

  @Test
  void anApprovalRequestSurfacesIdenticallyForOrdinaryAndStreamingRuns() {
    ApprovalFixture ordinary =
        new ApprovalFixture(
            requireApproval(), List.of(weatherCall(), textResponse("It is sunny")), "weather");
    ApprovalFixture streaming =
        new ApprovalFixture(
            requireApproval(), List.of(weatherCall(), textResponse("It is sunny")), "weather");

    AgentResponse ordinaryResponse = ordinary.run("session-parity-1", userText("hi"));
    AgentResponse streamingResponse = streaming.stream("session-parity-1", userText("hi"));

    assertThat(describe(streamingResponse)).isEqualTo(describe(ordinaryResponse));
    assertThat(streamingResponse.finishReason()).isEqualTo(ordinaryResponse.finishReason());
    assertThat(streaming.invocations).isEqualTo(ordinary.invocations);
  }

  @Test
  void anApprovalResumeRunsIdenticallyForOrdinaryAndStreamingRuns() {
    ApprovalFixture ordinary =
        new ApprovalFixture(
            requireApproval(), List.of(weatherCall(), textResponse("It is sunny")), "weather");
    ApprovalFixture streaming =
        new ApprovalFixture(
            requireApproval(), List.of(weatherCall(), textResponse("It is sunny")), "weather");
    String ordinaryRequest =
        onlyApprovalRequest(ordinary.run("session-parity-2", userText("hi"))).requestId();
    String streamingRequest =
        onlyApprovalRequest(streaming.stream("session-parity-2", userText("hi"))).requestId();

    AgentResponse ordinaryResponse =
        ordinary.run("session-parity-2", approvalResponse(ordinaryRequest, true));
    AgentResponse streamingResponse =
        streaming.stream("session-parity-2", approvalResponse(streamingRequest, true));

    assertThat(describe(streamingResponse)).isEqualTo(describe(ordinaryResponse));
    assertThat(streamingResponse.finishReason()).isEqualTo(ordinaryResponse.finishReason());
    assertThat(streaming.invocations).isEqualTo(ordinary.invocations);
    assertThat(streaming.modelCalls.get()).isEqualTo(ordinary.modelCalls.get());
  }

  private static ToolApprovalSettings requireApproval() {
    return ToolApprovalSettings.builder().build();
  }

  private static Message userText(String text) {
    return new Message(Role.USER, List.of(new TextContent(text)));
  }

  private static String invocation(String toolName, Map<String, ?> arguments) {
    return toolName + ":" + jsonObject(arguments);
  }

  private static Message approvalResponse(String requestId, boolean approved) {
    return new Message(
        Role.USER,
        List.of(
            approved
                ? ToolApprovalResponseContent.approve(requestId)
                : ToolApprovalResponseContent.deny(requestId)));
  }

  private static ModelResponse weatherCall() {
    return modelResponse(
        new Message(
            Role.ASSISTANT,
            List.of(new ToolCallContent("call-1", "weather", jsonObject(Map.of("city", "Seoul"))))),
        FinishReason.TOOL_CALLS);
  }

  private static ModelResponse failingCall() {
    return modelResponse(
        new Message(
            Role.ASSISTANT, List.of(new ToolCallContent("call-1", "boom", JsonObject.empty()))),
        FinishReason.TOOL_CALLS);
  }

  private static ModelResponse textResponse(String text) {
    return modelResponse(
        new Message(Role.ASSISTANT, List.of(new TextContent(text))), FinishReason.STOP);
  }

  private static ModelResponse modelResponse(Message message, FinishReason finishReason) {
    return ModelResponse.builder().messages(List.of(message)).finishReason(finishReason).build();
  }

  private static List<ToolApprovalRequestContent> approvalRequests(AgentResponse response) {
    List<ToolApprovalRequestContent> requests = new ArrayList<>();
    for (Message message : response.messages()) {
      for (Content content : message.content()) {
        if (content instanceof ToolApprovalRequestContent request) {
          requests.add(request);
        }
      }
    }
    return requests;
  }

  private static ToolApprovalRequestContent onlyApprovalRequest(AgentResponse response) {
    List<ToolApprovalRequestContent> requests = approvalRequests(response);
    assertThat(requests).hasSize(1);
    return requests.get(0);
  }

  private static List<String> toolResults(AgentResponse response) {
    List<String> results = new ArrayList<>();
    for (Message message : response.messages()) {
      for (Content content : message.content()) {
        if (content instanceof ToolResultContent result) {
          results.add(
              result.callId()
                  + ":"
                  + result.name()
                  + ":"
                  + result.error()
                  + ":"
                  + result.content().stream().map(Content::text).toList());
        }
      }
    }
    return results;
  }

  /**
   * Renders a response as the flat, role-tagged sequence of content it carries.
   *
   * <p>Message boundaries are deliberately not compared. A streamed response coalesces consecutive
   * same-role content a stream never gave a message id — the pre-existing rule the pipeline
   * documents — and an approval request is assistant content that follows the assistant message
   * whose tool call it is about, so the two views group it differently while carrying exactly the
   * same content in exactly the same order. The random request id each run mints for its own
   * approval request is left out for the same reason: it identifies the run, not the outcome.
   */
  private static List<String> describe(AgentResponse response) {
    List<String> described = new ArrayList<>();
    for (Message message : response.messages()) {
      String role = message.role().value();
      for (Content content : message.content()) {
        if (content instanceof ToolCallContent call) {
          described.add(
              role + "|call:" + call.callId() + ':' + call.name() + ':' + call.arguments());
        } else if (content instanceof ToolResultContent result) {
          described.add(
              role
                  + "|result:"
                  + result.callId()
                  + ':'
                  + result.name()
                  + ':'
                  + result.error()
                  + ':'
                  + result.content().stream().map(Content::text).toList());
        } else if (content instanceof ToolApprovalRequestContent approvalRequest) {
          described.add(
              role
                  + "|approval:"
                  + approvalRequest.toolCallId()
                  + ':'
                  + approvalRequest.toolName()
                  + ':'
                  + approvalRequest.arguments()
                  + ':'
                  + approvalRequest.hostBoundary().orElse(null));
        } else {
          described.add(role + "|text:" + content.text());
        }
      }
    }
    return described;
  }

  private static CompletableFuture<Void> drain(Flow.Publisher<AgentResponseUpdate> updates) {
    CompletableFuture<Void> completion = new CompletableFuture<>();
    updates.subscribe(
        new Flow.Subscriber<>() {
          @Override
          public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
          }

          @Override
          public void onNext(AgentResponseUpdate item) {
            // The assembled response is what this test compares.
          }

          @Override
          public void onError(Throwable throwable) {
            completion.completeExceptionally(throwable);
          }

          @Override
          public void onComplete() {
            completion.complete(null);
          }
        });
    return completion;
  }

  /**
   * One agent bound to a store-backed engine with tool approval configured, plus the model script
   * it answers with and the tool invocations it actually performed. Runs share the agent and the
   * store, so a later run resumes exactly the approval state an earlier run persisted.
   */
  private static final class ApprovalFixture {

    private final AtomicInteger modelCalls = new AtomicInteger();
    private final List<String> invocations = new ArrayList<>();
    private final RecordingSessionStore store = new RecordingSessionStore();
    private final Agent agent;

    private ApprovalFixture(
        ToolApprovalSettings settings, List<ModelResponse> script, String... toolNames) {
      this(settings, script, List.of(toolNames), false);
    }

    private ApprovalFixture(
        ToolApprovalSettings settings,
        List<ModelResponse> script,
        List<String> toolNames,
        boolean failing) {
      AgentDefinition.Builder definition =
          AgentDefinition.builder().id("agent-1").name("assistant");
      AgentRuntime.Builder runtime =
          AgentRuntime.builder().modelClient(scripted(modelCalls, script)).toolApproval(settings);
      for (String toolName : toolNames) {
        FunctionTool tool =
            FunctionTool.create(
                toolName,
                toolName,
                JsonObject.empty(),
                (arguments, context) -> {
                  invocations.add(toolName + ":" + arguments.values());
                  if (failing) {
                    throw new IllegalStateException("tool exploded");
                  }
                  return completedFuture(ToolResult.success(new TextContent(toolName + "-ran")));
                });
        definition.tool(tool.definition());
        runtime.toolBinding(ToolBinding.of(toolName, tool::execute));
      }
      this.agent =
          AgentEngine.builder()
              .sessionStore(store)
              .build()
              .bind(definition.build(), runtime.build());
    }

    private AgentResponse run(String sessionId, Message... input) {
      return agent.run(requestFor(sessionId, input)).response().toCompletableFuture().join();
    }

    private AgentResponse stream(String sessionId, Message... input) {
      AgentStreamingRun<AgentResponseUpdate> run = agent.runStreaming(requestFor(sessionId, input));
      drain(run.updates()).join();
      return run.response().toCompletableFuture().join();
    }

    private static AgentRunRequest requestFor(String sessionId, Message... input) {
      return AgentRunRequest.builder()
          .messages(List.of(input))
          .session(AgentSession.builder().sessionId(sessionId).build())
          .options(new AgentRunOptions())
          .cancellationSignal(new CancellationSignal())
          .attributes(ContextAttributes.empty())
          .build();
    }
  }

  private static ModelClient scripted(AtomicInteger calls, List<ModelResponse> script) {
    return request -> {
      int index = calls.getAndIncrement();
      if (index >= script.size()) {
        throw new IllegalStateException("unexpected model call: " + index);
      }
      return EngineModels.of(script.get(index));
    };
  }

  private static Agent agentWithStore(
      SessionStore store, ModelClient client, StatefulContextProvider<?> provider) {
    return AgentEngine.builder()
        .sessionStore(store)
        .build()
        .factory()
        .builderWithClient(client)
        .contextProviders(provider)
        .build();
  }

  private static void runWithSession(Agent agent, String sessionId) {
    agent
        .run(
            AgentRunRequest.builder()
                .messages(Message.normalize("hi"))
                .session(AgentSession.builder().sessionId(sessionId).build())
                .options(new AgentRunOptions())
                .cancellationSignal(new CancellationSignal())
                .attributes(ContextAttributes.empty())
                .build())
        .response()
        .toCompletableFuture()
        .join();
  }

  private static ModelClient fixedClient(String text) {
    return request ->
        EngineModels.of(
            ModelResponse.builder()
                .messages(List.of(new Message(Role.ASSISTANT, List.of(new TextContent(text)))))
                .finishReason(FinishReason.STOP)
                .build());
  }

  private static JsonObject jsonObject(Map<String, ?> values) {
    return values.isEmpty() ? JsonObject.empty() : (JsonObject) JsonValues.fromJava(values);
  }

  /**
   * A stateful provider bound to the reserved approval-queue key so the test can drive a save (when
   * {@code write} is true, writing {@code initial} during {@code complete}) or a load (when {@code
   * write} is false, capturing whatever the coordinator restored into {@code observed} during
   * {@code prepare}), without depending on any tool-loop wiring.
   */
  private static final class ApprovalQueueProvider
      implements StatefulContextProvider<ToolApprovalQueueState> {
    private final ToolApprovalQueueState initial;
    private final boolean write;
    private volatile Optional<ToolApprovalQueueState> observed = Optional.empty();

    private ApprovalQueueProvider(ToolApprovalQueueState initial, boolean write) {
      this.initial = initial;
      this.write = write;
    }

    @Override
    public SessionStateKey<ToolApprovalQueueState> stateKey() {
      return ToolApprovalQueueState.STATE_KEY;
    }

    @Override
    public CompletionStage<RunContribution> prepare(
        SessionContext context, ProviderSessionState<ToolApprovalQueueState> state) {
      observed = state.value();
      return completedFuture(RunContribution.empty());
    }

    @Override
    public CompletionStage<Void> complete(
        SessionContext context, ProviderSessionState<ToolApprovalQueueState> state) {
      if (write) {
        state.set(initial);
      }
      return completedFuture(null);
    }
  }

  private static final class RecordingSessionStore implements SessionStore {
    private final Map<String, SessionSnapshot> saved = new HashMap<>();

    @Override
    public CompletionStage<Optional<SessionSnapshot>> load(String sessionId) {
      return completedFuture(Optional.ofNullable(saved.get(sessionId)));
    }

    @Override
    public CompletionStage<Void> save(SessionSnapshot snapshot) {
      saved.put(snapshot.sessionId(), snapshot);
      return completedFuture(null);
    }

    @Override
    public CompletionStage<Void> delete(String sessionId) {
      saved.remove(sessionId);
      return completedFuture(null);
    }
  }
}
