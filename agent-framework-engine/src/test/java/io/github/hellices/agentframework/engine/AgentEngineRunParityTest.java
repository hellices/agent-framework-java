package io.github.hellices.agentframework.engine;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hellices.agentframework.api.agent.Agent;
import io.github.hellices.agentframework.api.agent.AgentBuilder;
import io.github.hellices.agentframework.api.agent.AgentDefinition;
import io.github.hellices.agentframework.api.agent.AgentResponse;
import io.github.hellices.agentframework.api.agent.AgentResponseUpdate;
import io.github.hellices.agentframework.api.agent.AgentRunRequest;
import io.github.hellices.agentframework.api.agent.AgentRuntime;
import io.github.hellices.agentframework.api.agent.AgentSession;
import io.github.hellices.agentframework.api.agent.AgentStreamingRun;
import io.github.hellices.agentframework.api.message.Content;
import io.github.hellices.agentframework.api.message.FinishReason;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.Role;
import io.github.hellices.agentframework.api.message.TextContent;
import io.github.hellices.agentframework.api.message.ToolApprovalRequestContent;
import io.github.hellices.agentframework.api.message.ToolApprovalResponseContent;
import io.github.hellices.agentframework.api.message.ToolCallContent;
import io.github.hellices.agentframework.api.message.ToolResultContent;
import io.github.hellices.agentframework.api.message.Usage;
import io.github.hellices.agentframework.api.session.SessionSnapshot;
import io.github.hellices.agentframework.api.tool.FunctionTool;
import io.github.hellices.agentframework.api.tool.ToolApprovalSettings;
import io.github.hellices.agentframework.api.tool.ToolBinding;
import io.github.hellices.agentframework.api.tool.ToolDefinition;
import io.github.hellices.agentframework.api.tool.ToolHandler;
import io.github.hellices.agentframework.api.tool.ToolResult;
import io.github.hellices.agentframework.api.value.JsonObject;
import io.github.hellices.agentframework.api.value.JsonValues;
import io.github.hellices.agentframework.spi.model.ModelClient;
import io.github.hellices.agentframework.spi.model.ModelResponse;
import io.github.hellices.agentframework.spi.model.ModelResponseUpdate;
import io.github.hellices.agentframework.spi.session.SessionStore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Ordinary and streaming runs of the same script are one pipeline observed two ways, so a caller
 * that reads the assembled response cannot tell which shape produced it. This test drives that
 * equivalence directly: for each script it runs an ordinary run and a streaming run and asserts
 * they agree on the content and order of their messages, their roles, tool results, usage, finish
 * reason, continuation token and — where a script fails — the run's root error.
 *
 * <p>What it does not assert is message-boundary parity for a call a stream delivered in pieces: a
 * streamed response is reconstructed by {@code AgentResponse.fromUpdates}, which coalesces
 * consecutive same-role chunks while no model port reports message ids, so a call an ordinary
 * client returned as two messages arrives as one reconstructed message. The split-call scenario
 * therefore asserts role and text parity, not exact message equality — the same parity the
 * streaming tool loop has always promised.
 */
class AgentEngineRunParityTest {

  private static final Map<String, Object> SEOUL = Map.of("city", "Seoul");

  @Test
  void aPlainRunHasAnIdenticalResponse() {
    AgentResponse ordinary =
        ordinary(
            request ->
                EngineModels.of(
                    modelResponse(
                        List.of(assistant("hello")),
                        new Usage(1, 2, 3, JsonObject.empty()),
                        FinishReason.STOP,
                        Map.of())));
    AgentResponse streaming =
        streaming(
            scripted(
                List.of(
                    List.of(update(assistant("hello"), new Usage(1, 2, 3), FinishReason.STOP)))));

    assertParity(ordinary, streaming);
  }

  @Test
  void aSingleToolCallRunHasAnIdenticalResponse() {
    AgentResponse ordinary =
        ordinary(
            twoIterations(
                modelResponse(
                    List.of(toolCall("call-1", "weather", SEOUL)),
                    new Usage(1, 2, 3, JsonObject.empty()),
                    FinishReason.TOOL_CALLS,
                    Map.of()),
                modelResponse(
                    List.of(assistant("It is sunny")),
                    new Usage(4, 5, 9, JsonObject.empty()),
                    FinishReason.STOP,
                    Map.of())),
            weatherTool());
    AgentResponse streaming =
        streaming(
            scripted(
                List.of(
                    List.of(
                        update(
                            toolCall("call-1", "weather", SEOUL),
                            new Usage(1, 2, 3),
                            FinishReason.TOOL_CALLS)),
                    List.of(
                        update(assistant("It is sunny"), new Usage(4, 5, 9), FinishReason.STOP)))),
            weatherTool());

    assertParity(ordinary, streaming);
    assertThat(toolResults(streaming)).isEqualTo(toolResults(ordinary));
  }

  @Test
  void aMultiToolRunHasAnIdenticalResponse() {
    FunctionTool first = echoTool("first");
    FunctionTool second = echoTool("second");
    AgentResponse ordinary =
        ordinary(
            twoIterations(
                modelResponse(
                    List.of(
                        new Message(
                            Role.ASSISTANT,
                            List.of(
                                new ToolCallContent("call-1", "first", JsonObject.empty()),
                                new ToolCallContent("call-2", "second", JsonObject.empty())))),
                    null,
                    FinishReason.TOOL_CALLS,
                    Map.of()),
                modelResponse(List.of(assistant("both done")), null, FinishReason.STOP, Map.of())),
            first,
            second);
    AgentResponse streaming =
        streaming(
            scripted(
                List.of(
                    List.of(
                        update(
                            new Message(
                                Role.ASSISTANT,
                                List.of(
                                    new ToolCallContent("call-1", "first", JsonObject.empty()),
                                    new ToolCallContent("call-2", "second", JsonObject.empty()))),
                            null,
                            FinishReason.TOOL_CALLS)),
                    List.of(update(assistant("both done"), null, FinishReason.STOP)))),
            first,
            second);

    assertParity(ordinary, streaming);
    assertThat(toolResults(streaming)).isEqualTo(toolResults(ordinary));
  }

  @Test
  void aSplitStreamedToolCallHasContentAndOrderParity() {
    AgentResponse ordinary =
        ordinary(
            twoIterations(
                modelResponse(
                    List.of(toolCall("call-1", "weather", SEOUL)),
                    null,
                    FinishReason.TOOL_CALLS,
                    Map.of()),
                modelResponse(
                    List.of(assistant("It is sunny")), null, FinishReason.STOP, Map.of())),
            weatherTool());
    AgentResponse streaming =
        streaming(
            scripted(
                List.of(
                    List.of(
                        update(
                            new Message(
                                Role.ASSISTANT,
                                List.of(
                                    new ToolCallContent("call-1", "weather", JsonObject.empty()))),
                            null,
                            FinishReason.TOOL_CALLS),
                        update(
                            new Message(
                                Role.ASSISTANT,
                                List.of(
                                    new ToolCallContent("call-1", "weather", jsonObject(SEOUL)))),
                            null,
                            FinishReason.TOOL_CALLS)),
                    List.of(
                        update(assistant("It is "), null, FinishReason.STOP),
                        update(assistant("sunny"), null, FinishReason.STOP)))),
            weatherTool());

    assertThat(roles(streaming)).isEqualTo(roles(ordinary));
    assertThat(streaming.text()).isEqualTo(ordinary.text());
    assertThat(streaming.finishReason()).isEqualTo(ordinary.finishReason());
    assertThat(toolResults(streaming)).isEqualTo(toolResults(ordinary));
  }

  @Test
  void aRecoveredToolFailureHasAnIdenticalResponse() {
    FunctionTool broken =
        tool(
            "broken",
            (arguments, context) ->
                completedFuture(ToolResult.failure(new TextContent("tool said no"))));
    AgentResponse ordinary =
        ordinary(
            twoIterations(
                modelResponse(
                    List.of(toolCall("call-1", "broken", Map.of())),
                    null,
                    FinishReason.TOOL_CALLS,
                    Map.of()),
                modelResponse(List.of(assistant("recovered")), null, FinishReason.STOP, Map.of())),
            broken);
    AgentResponse streaming =
        streaming(
            scripted(
                List.of(
                    List.of(
                        update(
                            toolCall("call-1", "broken", Map.of()), null, FinishReason.TOOL_CALLS)),
                    List.of(update(assistant("recovered"), null, FinishReason.STOP)))),
            broken);

    assertParity(ordinary, streaming);
    assertThat(toolResults(streaming)).isEqualTo(toolResults(ordinary));
  }

  @Test
  void aDeclarationOnlyToolCallEndsBothRunsWithTheCallsIntact() {
    ToolDefinition forecast = ToolDefinition.builder().name("forecast").build();
    ModelResponse response =
        modelResponse(
            List.of(toolCall("call-1", "forecast", SEOUL)),
            null,
            FinishReason.TOOL_CALLS,
            Map.of());
    AgentResponse ordinary = ordinaryDeclared(request -> EngineModels.of(response), forecast);
    AgentResponse streaming =
        streamingDeclared(
            scripted(
                List.of(
                    List.of(
                        update(
                            toolCall("call-1", "forecast", SEOUL),
                            null,
                            FinishReason.TOOL_CALLS)))),
            forecast);

    assertThat(roles(streaming)).isEqualTo(roles(ordinary));
    assertThat(streaming.finishReason()).isEqualTo(ordinary.finishReason());
    assertThat(toolResults(streaming)).isEqualTo(toolResults(ordinary));
    assertThat(toolResults(streaming)).isEmpty();
  }

  @Test
  void aMixedDeclarationAndExecutableCallEndsBothRunsWithoutExecuting() {
    ToolDefinition forecast = ToolDefinition.builder().name("forecast").build();
    FunctionTool weather = weatherTool();
    ModelResponse response =
        modelResponse(
            List.of(
                new Message(
                    Role.ASSISTANT,
                    List.of(
                        new ToolCallContent("call-1", "weather", jsonObject(SEOUL)),
                        new ToolCallContent("call-2", "forecast", jsonObject(SEOUL))))),
            null,
            FinishReason.TOOL_CALLS,
            Map.of());
    AgentResponse ordinary = ordinaryMixed(request -> EngineModels.of(response), forecast, weather);
    AgentResponse streaming =
        streamingMixed(
            scripted(
                List.of(
                    List.of(
                        update(
                            new Message(
                                Role.ASSISTANT,
                                List.of(
                                    new ToolCallContent("call-1", "weather", jsonObject(SEOUL)),
                                    new ToolCallContent("call-2", "forecast", jsonObject(SEOUL)))),
                            null,
                            FinishReason.TOOL_CALLS)))),
            forecast,
            weather);

    assertThat(roles(streaming)).isEqualTo(roles(ordinary));
    assertThat(streaming.finishReason()).isEqualTo(ordinary.finishReason());
    assertThat(toolResults(streaming)).isEqualTo(toolResults(ordinary));
    assertThat(toolResults(streaming)).isEmpty();
  }

  @Test
  void usageAccumulatesIdenticallyAcrossIterations() {
    AgentResponse ordinary =
        ordinary(
            twoIterations(
                modelResponse(
                    List.of(toolCall("call-1", "weather", SEOUL)),
                    new Usage(1, 2, 3, jsonObject(Map.of("cachedTokens", 1L))),
                    FinishReason.TOOL_CALLS,
                    Map.of()),
                modelResponse(
                    List.of(assistant("It is sunny")),
                    new Usage(4, 5, 9, jsonObject(Map.of("cachedTokens", 2L))),
                    FinishReason.STOP,
                    Map.of())),
            weatherTool());
    AgentResponse streaming =
        streaming(
            scripted(
                List.of(
                    List.of(
                        update(
                            toolCall("call-1", "weather", SEOUL),
                            new Usage(1, 2, 3, jsonObject(Map.of("cachedTokens", 1L))),
                            FinishReason.TOOL_CALLS)),
                    List.of(
                        update(
                            assistant("It is sunny"),
                            new Usage(4, 5, 9, jsonObject(Map.of("cachedTokens", 2L))),
                            FinishReason.STOP)))),
            weatherTool());

    assertThat(streaming.usage()).isEqualTo(ordinary.usage());
    assertThat(ordinary.usage().totalTokens()).isEqualTo(12);
  }

  @Test
  void aContinuationTokenReachesBothResponses() {
    AgentResponse ordinary =
        ordinary(
            request ->
                EngineModels.of(
                    ModelResponse.builder()
                        .messages(List.of(assistant("paused")))
                        .finishReason(FinishReason.STOP)
                        .continuationToken("resume-here")
                        .metadata(JsonObject.empty())
                        .build()));
    AgentResponse streaming =
        streaming(
            scripted(
                List.of(
                    List.of(
                        ModelResponseUpdate.builder()
                            .messages(List.of(assistant("paused")))
                            .finishReason(FinishReason.STOP)
                            .continuationToken("resume-here")
                            .metadata(JsonObject.empty())
                            .build()))));

    assertThat(streaming.continuationToken()).isEqualTo(ordinary.continuationToken());
    assertThat(streaming.continuationToken()).isEqualTo("resume-here");
  }

  @Test
  void anApprovalWaitAndItsResumeAreIdenticalForOrdinaryAndStreamingRuns() {
    ModelResponse waitingCall =
        modelResponse(
            List.of(toolCall("call-1", "weather", SEOUL)), null, FinishReason.TOOL_CALLS, Map.of());
    ModelResponse finished =
        modelResponse(List.of(assistant("sunny")), null, FinishReason.STOP, Map.of());

    Agent ordinaryAgent =
        approvalAgent(new RecordingApprovalSessionStore(), twoIterations(waitingCall, finished));
    Agent streamingAgent =
        approvalAgent(new RecordingApprovalSessionStore(), twoIterations(waitingCall, finished));

    AgentRunRequest firstTurn = sessionRequest("session-approval-parity", "hi");
    AgentResponse ordinaryWaiting =
        ordinaryAgent.run(firstTurn).response().toCompletableFuture().join();
    AgentResponse streamingWaiting = streamResponse(streamingAgent, firstTurn);

    // Response boundary and typed waiting surface (C-1, I-1) agree between both run kinds.
    assertThat(streamingWaiting.finishReason()).isEqualTo(ordinaryWaiting.finishReason());
    assertThat(streamingWaiting.messages()).hasSameSizeAs(ordinaryWaiting.messages());
    assertThat(describe(streamingWaiting.messages()))
        .isEqualTo(describe(ordinaryWaiting.messages()));
    assertThat(streamingWaiting.userInputRequests()).hasSize(1);
    assertThat(ordinaryWaiting.userInputRequests()).hasSize(1);
    assertThat(streamingWaiting.userInputRequests().get(0).toolName())
        .isEqualTo(ordinaryWaiting.userInputRequests().get(0).toolName());
    // IM-1 (re-review): the two views must also agree on how the response's own messageId is
    // derived, not just on messages and the typed waiting surface — a null-vs-derived-id
    // divergence here would be invisible to every other assertion above. Ordinary and streaming
    // are two independent runs here (separate agents, separate stores), so each mints its own
    // random approval requestId and the two ids are not literally the same string; what must be
    // equal is the *derivation*, so each view's messageId is asserted against its own
    // userInputRequests() id rather than against the other view's.
    assertThat(ordinaryWaiting.messageId())
        .isEqualTo("tool-approval:" + ordinaryWaiting.userInputRequests().get(0).requestId());
    assertThat(streamingWaiting.messageId())
        .isEqualTo("tool-approval:" + streamingWaiting.userInputRequests().get(0).requestId());

    AgentRunRequest ordinaryResume =
        approvalResumeRequest(
            "session-approval-parity", ordinaryWaiting.userInputRequests().get(0).requestId());
    AgentRunRequest streamingResume =
        approvalResumeRequest(
            "session-approval-parity", streamingWaiting.userInputRequests().get(0).requestId());

    AgentResponse ordinaryResumed =
        ordinaryAgent.run(ordinaryResume).response().toCompletableFuture().join();
    AgentResponse streamingResumed = streamResponse(streamingAgent, streamingResume);

    assertParity(ordinaryResumed, streamingResumed);
  }

  @Test
  void anUnknownToolCallFailsBothRunsWithTheSameRootError() {
    ModelResponse response =
        modelResponse(
            List.of(toolCall("call-1", "missing", Map.of())),
            null,
            FinishReason.TOOL_CALLS,
            Map.of());

    assertThatThrownBy(
            () ->
                agent(request -> EngineModels.of(response))
                    .run("hi")
                    .response()
                    .toCompletableFuture()
                    .join())
        .hasRootCauseInstanceOf(IllegalStateException.class)
        .hasRootCauseMessage("unknown tool call: missing");

    assertThatThrownBy(() -> streamResponse(agent(request -> EngineModels.of(response))))
        .hasRootCauseInstanceOf(IllegalStateException.class)
        .hasRootCauseMessage("unknown tool call: missing");
  }

  @Test
  void cancellationFailsBothRuns() {
    ModelClient stalling =
        request ->
            subscriber ->
                subscriber.onSubscribe(
                    new Flow.Subscription() {
                      @Override
                      public void request(long n) {
                        // A model that never emits keeps both runs pending until they are
                        // cancelled.
                      }

                      @Override
                      public void cancel() {}
                    });

    var ordinaryRun = agent(stalling).run("hi");
    ordinaryRun.cancel();
    assertThatThrownBy(() -> ordinaryRun.response().toCompletableFuture().join())
        .hasRootCauseInstanceOf(CancellationException.class);

    AgentStreamingRun<AgentResponseUpdate> streamingRun = agent(stalling).runStreaming("hi");
    streamingRun.updates().subscribe(new NoOpSubscriber());
    streamingRun.cancel();
    assertThatThrownBy(() -> streamingRun.response().toCompletableFuture().join())
        .hasRootCauseInstanceOf(CancellationException.class);
  }

  private static void assertParity(AgentResponse ordinary, AgentResponse streaming) {
    assertThat(describe(streaming.messages())).isEqualTo(describe(ordinary.messages()));
    assertThat(streaming.text()).isEqualTo(ordinary.text());
    assertThat(streaming.finishReason()).isEqualTo(ordinary.finishReason());
    assertThat(streaming.usage()).isEqualTo(ordinary.usage());
    assertThat(streaming.continuationToken()).isEqualTo(ordinary.continuationToken());
    assertThat(streaming.messageId()).isEqualTo(ordinary.messageId());
  }

  private AgentResponse ordinary(ModelClient client, FunctionTool... tools) {
    return agent(client, tools).run("hi").response().toCompletableFuture().join();
  }

  private AgentResponse ordinaryDeclared(ModelClient client, ToolDefinition declared) {
    return declaredAgent(client, declared).run("hi").response().toCompletableFuture().join();
  }

  private AgentResponse ordinaryMixed(
      ModelClient client, ToolDefinition declared, FunctionTool executable) {
    return mixedAgent(client, declared, executable)
        .run("hi")
        .response()
        .toCompletableFuture()
        .join();
  }

  private AgentResponse streaming(ModelClient client, FunctionTool... tools) {
    return streamResponse(agent(client, tools));
  }

  private AgentResponse streamingDeclared(ModelClient client, ToolDefinition declared) {
    return streamResponse(declaredAgent(client, declared));
  }

  private AgentResponse streamingMixed(
      ModelClient client, ToolDefinition declared, FunctionTool executable) {
    return streamResponse(mixedAgent(client, declared, executable));
  }

  private static AgentResponse streamResponse(Agent agent) {
    AgentStreamingRun<AgentResponseUpdate> run = agent.runStreaming("hi");
    subscribe(run.updates()).join();
    return run.response().toCompletableFuture().join();
  }

  private static AgentResponse streamResponse(Agent agent, AgentRunRequest request) {
    AgentStreamingRun<AgentResponseUpdate> run = agent.runStreaming(request);
    subscribe(run.updates()).join();
    return run.response().toCompletableFuture().join();
  }

  private static Agent agent(ModelClient client, FunctionTool... tools) {
    return boundBuilder(client).id("agent-1").name("assistant").tools(tools).build();
  }

  private static Agent declaredAgent(ModelClient client, ToolDefinition declared) {
    AgentDefinition definition =
        AgentDefinition.builder().id("agent-1").name("assistant").tool(declared).build();
    AgentRuntime runtime = AgentRuntime.builder().modelClient(client).build();
    return AgentEngine.builder().build().bind(definition, runtime);
  }

  private static Agent mixedAgent(
      ModelClient client, ToolDefinition declared, FunctionTool executable) {
    AgentDefinition definition =
        AgentDefinition.builder()
            .id("agent-1")
            .name("assistant")
            .tool(declared)
            .tool(executable.definition())
            .build();
    AgentRuntime runtime =
        AgentRuntime.builder()
            .modelClient(client)
            .toolBinding(
                ToolBinding.of(
                    executable.definition().name(),
                    (arguments, context) ->
                        completedFuture(ToolResult.success(new TextContent("unused")))))
            .build();
    return AgentEngine.builder().build().bind(definition, runtime);
  }

  /** An agent bound to {@code store} with tool approval required for every call to "weather". */
  private static Agent approvalAgent(SessionStore store, ModelClient client) {
    FunctionTool weather = weatherTool();
    AgentDefinition definition =
        AgentDefinition.builder()
            .id("agent-1")
            .name("assistant")
            .tool(weather.definition())
            .build();
    AgentRuntime runtime =
        AgentRuntime.builder()
            .modelClient(client)
            .toolApproval(ToolApprovalSettings.builder().build())
            .toolBinding(ToolBinding.of("weather", weather::execute))
            .build();
    return AgentEngine.builder().sessionStore(store).build().bind(definition, runtime);
  }

  private static AgentRunRequest sessionRequest(String sessionId, String text) {
    return AgentRunRequest.builder()
        .messages(Message.normalize(text))
        .session(AgentSession.builder().sessionId(sessionId).build())
        .build();
  }

  private static AgentRunRequest approvalResumeRequest(String sessionId, String requestId) {
    return AgentRunRequest.builder()
        .messages(
            List.of(
                new Message(Role.USER, List.of(ToolApprovalResponseContent.approve(requestId)))))
        .session(AgentSession.builder().sessionId(sessionId).build())
        .build();
  }

  private static AgentBuilder boundBuilder(ModelClient client) {
    return AgentEngine.builder().build().factory().builderWithClient(client);
  }

  private static ModelClient twoIterations(ModelResponse first, ModelResponse second) {
    AtomicInteger calls = new AtomicInteger();
    return request ->
        calls.getAndIncrement() == 0 ? EngineModels.of(first) : EngineModels.of(second);
  }

  private static ModelClient scripted(List<List<ModelResponseUpdate>> iterations) {
    AtomicInteger index = new AtomicInteger();
    return request -> {
      int iteration = index.getAndIncrement();
      if (iteration >= iterations.size()) {
        throw new IllegalStateException("unexpected model call: " + iteration);
      }
      return publisher(iterations.get(iteration));
    };
  }

  private static Flow.Publisher<ModelResponseUpdate> publisher(List<ModelResponseUpdate> updates) {
    return subscriber ->
        subscriber.onSubscribe(
            new Flow.Subscription() {
              private int index;
              private boolean done;
              private boolean emitting;
              private long demand;

              @Override
              public void request(long n) {
                if (done) {
                  return;
                }
                if (n <= 0) {
                  done = true;
                  subscriber.onError(
                      new IllegalArgumentException("demand must be greater than zero"));
                  return;
                }
                demand += n;
                if (emitting) {
                  return;
                }
                emitting = true;
                while (demand > 0 && index < updates.size()) {
                  demand--;
                  subscriber.onNext(updates.get(index++));
                }
                emitting = false;
                if (index == updates.size() && !done) {
                  done = true;
                  subscriber.onComplete();
                }
              }

              @Override
              public void cancel() {
                done = true;
              }
            });
  }

  private static CompletableFuture<Void> subscribe(Flow.Publisher<AgentResponseUpdate> publisher) {
    CompletableFuture<Void> completion = new CompletableFuture<>();
    publisher.subscribe(
        new Flow.Subscriber<>() {
          @Override
          public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
          }

          @Override
          public void onNext(AgentResponseUpdate item) {}

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

  private static final class NoOpSubscriber implements Flow.Subscriber<AgentResponseUpdate> {
    @Override
    public void onSubscribe(Flow.Subscription subscription) {
      subscription.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(AgentResponseUpdate item) {}

    @Override
    public void onError(Throwable throwable) {}

    @Override
    public void onComplete() {}
  }

  private static List<String> roles(AgentResponse response) {
    return response.messages().stream().map(message -> message.role().value()).toList();
  }

  private static List<String> toolResults(AgentResponse response) {
    List<String> results = new ArrayList<>();
    for (Message message : response.messages()) {
      for (Content content : message.content()) {
        if (content instanceof ToolResultContent result) {
          results.add(result.callId() + ":" + result.name() + ":" + result.error());
        }
      }
    }
    return results;
  }

  private static List<String> describe(List<Message> messages) {
    List<String> described = new ArrayList<>();
    for (Message message : messages) {
      StringBuilder builder = new StringBuilder(message.role().value()).append('|');
      builder.append(message.text());
      for (Content content : message.content()) {
        if (content instanceof ToolCallContent call) {
          builder
              .append("|call:")
              .append(call.callId())
              .append(':')
              .append(call.name())
              .append(':')
              .append(call.arguments());
        } else if (content instanceof ToolResultContent result) {
          builder
              .append("|result:")
              .append(result.callId())
              .append(':')
              .append(result.name())
              .append(':')
              .append(result.error())
              .append(':')
              .append(result.content().stream().map(Content::text).toList());
        } else if (content instanceof ToolApprovalRequestContent request) {
          // The random request id each run mints for its own approval request identifies the run,
          // not the outcome, so it is left out here — the same reason AgentEngineToolApprovalTest's
          // own describe() omits it.
          builder
              .append("|approval-request:")
              .append(request.toolCallId())
              .append(':')
              .append(request.toolName())
              .append(':')
              .append(request.arguments());
        } else if (content instanceof ToolApprovalResponseContent response) {
          builder.append("|approval-response:").append(response.approved());
        }
      }
      described.add(builder.toString());
    }
    return described;
  }

  private static Message assistant(String text) {
    return new Message(Role.ASSISTANT, List.of(new TextContent(text)));
  }

  private static Message toolCall(String callId, String name, Map<String, Object> arguments) {
    return new Message(
        Role.ASSISTANT, List.of(new ToolCallContent(callId, name, jsonObject(arguments))));
  }

  private static ModelResponseUpdate update(Message message, Usage usage, FinishReason reason) {
    return ModelResponseUpdate.builder()
        .messages(List.of(message))
        .usage(usage)
        .finishReason(reason)
        .metadata(JsonObject.empty())
        .build();
  }

  private static ModelResponse modelResponse(
      List<Message> messages,
      Usage usage,
      FinishReason finishReason,
      Map<String, Object> metadata) {
    return ModelResponse.builder()
        .messages(messages)
        .usage(usage)
        .finishReason(finishReason)
        .metadata(jsonObject(metadata))
        .build();
  }

  private static FunctionTool weatherTool() {
    return tool(
        "weather",
        (arguments, context) ->
            completedFuture(ToolResult.success(new TextContent("sunny:" + arguments.get("city")))));
  }

  private static FunctionTool echoTool(String name) {
    return tool(
        name, (arguments, context) -> completedFuture(ToolResult.success(new TextContent(name))));
  }

  private static FunctionTool tool(String name, ToolHandler handler) {
    return FunctionTool.create(name, name, (JsonObject) JsonValues.fromJava(Map.of()), handler);
  }

  private static JsonObject jsonObject(Map<String, Object> values) {
    return values.isEmpty() ? JsonObject.empty() : (JsonObject) JsonValues.fromJava(values);
  }

  /** A minimal in-memory {@link SessionStore}, scoped to one approval-parity test at a time. */
  private static final class RecordingApprovalSessionStore implements SessionStore {
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
