package io.github.hellices.agentframework.engine;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hellices.agentframework.api.agent.Agent;
import io.github.hellices.agentframework.api.agent.AgentBuilder;
import io.github.hellices.agentframework.api.agent.AgentResponse;
import io.github.hellices.agentframework.api.agent.AgentResponseUpdate;
import io.github.hellices.agentframework.api.agent.AgentRun;
import io.github.hellices.agentframework.api.agent.AgentRunOptions;
import io.github.hellices.agentframework.api.agent.AgentRunRequest;
import io.github.hellices.agentframework.api.agent.AgentSession;
import io.github.hellices.agentframework.api.agent.AgentStreamingRun;
import io.github.hellices.agentframework.api.agent.CancellationSignal;
import io.github.hellices.agentframework.api.agent.RunContribution;
import io.github.hellices.agentframework.api.context.ContextAttributes;
import io.github.hellices.agentframework.api.message.FinishReason;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.MessageAttribution;
import io.github.hellices.agentframework.api.message.Role;
import io.github.hellices.agentframework.api.message.TextContent;
import io.github.hellices.agentframework.api.message.ToolCallContent;
import io.github.hellices.agentframework.api.message.ToolResultContent;
import io.github.hellices.agentframework.api.session.SessionContext;
import io.github.hellices.agentframework.api.session.SessionState;
import io.github.hellices.agentframework.api.session.SessionStateKey;
import io.github.hellices.agentframework.api.session.SessionStateValues;
import io.github.hellices.agentframework.api.tool.FunctionTool;
import io.github.hellices.agentframework.api.tool.ToolDefinition;
import io.github.hellices.agentframework.api.tool.ToolResult;
import io.github.hellices.agentframework.api.value.JsonNumber;
import io.github.hellices.agentframework.api.value.JsonObject;
import io.github.hellices.agentframework.api.value.JsonValue;
import io.github.hellices.agentframework.api.value.JsonValues;
import io.github.hellices.agentframework.spi.model.ModelClient;
import io.github.hellices.agentframework.spi.model.ModelProviderOption;
import io.github.hellices.agentframework.spi.model.ModelRequest;
import io.github.hellices.agentframework.spi.model.ModelRequestOptions;
import io.github.hellices.agentframework.spi.model.ModelResponse;
import io.github.hellices.agentframework.spi.model.ModelResponseUpdate;
import io.github.hellices.agentframework.spi.session.ContextProvider;
import io.github.hellices.agentframework.spi.session.ProviderSessionState;
import io.github.hellices.agentframework.spi.session.StatefulContextProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AgentEngineSessionContextTest {

  private static AgentBuilder boundBuilder(ModelClient client) {
    return AgentEngine.builder().build().factory().builderWithClient(client);
  }

  @Test
  void builderRejectsNullContextProviderEntries() {
    assertThatThrownBy(
            () ->
                boundBuilder(fixedClient("unused"))
                    .contextProviders(new RecordingProvider("memory", new ArrayList<>()), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("contextProviders must not contain null entries");
  }

  @Test
  void builderRejectsABlankContextProviderSourceId() {
    assertThatThrownBy(
            () ->
                boundBuilder(fixedClient("unused"))
                    .contextProviders(new RecordingProvider("  ", new ArrayList<>()))
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("id must not be blank");
  }

  @Test
  void builderRejectsDuplicateContextProviderSourceIds() {
    List<String> log = new ArrayList<>();

    assertThatThrownBy(
            () ->
                boundBuilder(fixedClient("unused"))
                    .contextProviders(
                        new RecordingProvider("memory", log), new RecordingProvider("memory", log))
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("duplicate context provider stateKey id: memory");
  }

  @Test
  void ordinaryRunComposesBeforeRunForwardAndAfterRunInReverseAroundTheModelCall() {
    List<String> log = new ArrayList<>();
    ModelClient client =
        request -> {
          log.add("model");
          return EngineModels.of(response("hello"));
        };
    Agent engine =
        boundBuilder(client)
            .contextProviders(
                new RecordingProvider("first", log),
                new RecordingProvider("second", log),
                new RecordingProvider("third", log))
            .build();

    engine.run("hi").response().toCompletableFuture().join();

    assertThat(log)
        .containsExactly(
            "before:first",
            "before:second",
            "before:third",
            "model",
            "after:third",
            "after:second",
            "after:first");
  }

  @Test
  void providerContextMessagesPrecedeCallerInputAndPreserveProviderOrder() {
    AtomicReference<ModelRequest> capturedRequest = new AtomicReference<>();
    ModelClient client =
        request -> {
          capturedRequest.set(request);
          return EngineModels.of(response("hello"));
        };
    List<String> log = new ArrayList<>();
    Agent engine =
        boundBuilder(client)
            .contextProviders(
                new RecordingProvider("first", log), new RecordingProvider("second", log))
            .build();

    engine.run("hi").response().toCompletableFuture().join();

    assertThat(capturedRequest.get().messages())
        .extracting(Message::text)
        .containsExactly("context:first", "context:second", "hi");
    assertThat(capturedRequest.get().messages())
        .extracting(Message::attribution)
        .containsExactly(
            new MessageAttribution("AIContextProvider", "first", null),
            new MessageAttribution("AIContextProvider", "second", null),
            null);
  }

  @Test
  void afterRunObservesTheFinalResponseThroughTheSharedSessionContext() {
    List<String> log = new ArrayList<>();
    RecordingProvider provider = new RecordingProvider("memory", log);
    Agent engine = boundBuilder(fixedClient("hello")).contextProviders(provider).build();

    AgentResponse response = engine.run("hi").response().toCompletableFuture().join();

    assertThat(provider.responseDuringBeforeRun).isEmpty();
    assertThat(provider.responseDuringAfterRun).containsSame(response);
    assertThat(provider.afterRunContexts).containsExactly(provider.beforeRunContexts.get(0));
  }

  @Test
  void oneProviderInstanceKeepsTwoSessionsStateSlotsSeparate() {
    List<String> log = new ArrayList<>();
    RecordingProvider provider = new RecordingProvider("memory", log);
    Agent engine = boundBuilder(fixedClient("hello")).contextProviders(provider).build();

    runWithSession(engine, session("session-1", null, Map.of()));
    runWithSession(engine, session("session-2", null, Map.of("memory", 7)));

    assertThat(provider.observedStateValues).containsExactly(0, 7);
    assertThat(provider.updatedSessions)
        .extracting(
            session ->
                session.state().get(SessionStateKey.of("memory", JsonValue.class)).orElse(null))
        .containsExactly(JsonNumber.of(1), JsonNumber.of(8));
  }

  @Test
  void distinctSourceIdsDoNotShareTheSameStateSlot() {
    List<String> log = new ArrayList<>();
    RecordingProvider first = new RecordingProvider("first", log);
    RecordingProvider second = new RecordingProvider("second", log);
    Agent engine = boundBuilder(fixedClient("hello")).contextProviders(first, second).build();

    runWithSession(engine, session("session-1", null, Map.of("first", 3)));

    assertThat(first.observedStateValues).containsExactly(3);
    assertThat(second.observedStateValues).containsExactly(0);
    assertThat(
            first.updatedSessions.get(0).state().get(SessionStateKey.of("first", JsonValue.class)))
        .contains(JsonNumber.of(4));
    assertThat(
            first.updatedSessions.get(0).state().get(SessionStateKey.of("second", JsonValue.class)))
        .contains(JsonNumber.of(1));
  }

  @Test
  void streamingRunComposesBeforeRunForwardAndAfterRunInReverseAroundTheModelCall() {
    List<String> log = new ArrayList<>();
    Agent engine =
        boundBuilder(new StreamingFakeClient(log))
            .contextProviders(
                new RecordingProvider("first", log), new RecordingProvider("second", log))
            .build();

    AgentStreamingRun<AgentResponseUpdate> run = engine.runStreaming("hi");
    List<AgentResponseUpdate> updates = consume(run.updates());
    AgentResponse response = run.response().toCompletableFuture().join();

    assertThat(updates).extracting(AgentResponseUpdate::text).containsExactly("hello");
    assertThat(response.text()).isEqualTo("hello");
    assertThat(log)
        .containsExactly("before:first", "before:second", "model", "after:second", "after:first");
  }

  @Test
  void streamingProviderContextMessagesPrecedeCallerInput() {
    List<String> log = new ArrayList<>();
    StreamingFakeClient client = new StreamingFakeClient(log);
    Agent engine =
        boundBuilder(client).contextProviders(new RecordingProvider("first", log)).build();

    AgentStreamingRun<AgentResponseUpdate> run = engine.runStreaming("hi");
    consume(run.updates());
    run.response().toCompletableFuture().join();

    assertThat(client.capturedRequest.get().messages())
        .extracting(Message::text)
        .containsExactly("context:first", "hi");
  }

  @Test
  void beforeRunFailureFailsTheRunWithoutCallingTheModelOrAfterRun() {
    List<String> log = new ArrayList<>();
    ModelClient client =
        request -> {
          log.add("model");
          return EngineModels.of(response("hello"));
        };
    RecordingProvider first = new RecordingProvider("first", log);
    Agent engine =
        boundBuilder(client)
            .contextProviders(first, new FailingBeforeRunProvider("second", log))
            .build();

    assertThatThrownBy(() -> engine.run("hi").response().toCompletableFuture().join())
        .hasRootCauseInstanceOf(IllegalStateException.class)
        .hasRootCauseMessage("before-run failure");
    assertThat(log).containsExactly("before:first", "before:second");
    assertThat(first.afterRunContexts).isEmpty();
  }

  @Test
  void beforeRunNullStageFailsTheRun() {
    List<String> log = new ArrayList<>();
    CountingClient client = new CountingClient("hello");
    RecordingProvider later = new RecordingProvider("later", log);
    Agent engine = boundBuilder(client).contextProviders(new NullStageProvider(), later).build();

    assertThatThrownBy(() -> engine.run("hi").response().toCompletableFuture().join())
        .hasRootCauseInstanceOf(NullPointerException.class)
        .hasRootCauseMessage("context provider prepare stage must not be null");
    // A null before-run stage short-circuits the run, so the model is never called and no later
    // provider hook - neither the following before-run hook nor any after-run hook - is reached.
    assertThat(client.invocations.get()).isZero();
    assertThat(later.beforeRunContexts).isEmpty();
    assertThat(later.afterRunContexts).isEmpty();
    assertThat(log).isEmpty();
  }

  @Test
  void afterRunFailureFailsTheRunAndStopsEarlierProviderHooks() {
    List<String> log = new ArrayList<>();
    RecordingProvider first = new RecordingProvider("first", log);
    Agent engine =
        boundBuilder(fixedClient("hello"))
            .contextProviders(first, new FailingAfterRunProvider("second", log))
            .build();

    assertThatThrownBy(() -> engine.run("hi").response().toCompletableFuture().join())
        .hasRootCauseInstanceOf(IllegalStateException.class)
        .hasRootCauseMessage("after-run failure");
    assertThat(log).containsExactly("before:first", "before:second", "after:second");
    assertThat(first.afterRunContexts).isEmpty();
  }

  @Test
  void modelFailureSkipsAfterRunAndLeavesTheResponseSlotEmpty() {
    List<String> log = new ArrayList<>();
    RecordingProvider provider = new RecordingProvider("memory", log);
    ModelClient client =
        request -> {
          CompletableFuture<ModelResponse> failed = new CompletableFuture<>();
          failed.completeExceptionally(new IllegalStateException("model failure"));
          return EngineModels.fromStage(failed);
        };
    Agent engine = boundBuilder(client).contextProviders(provider).build();

    assertThatThrownBy(() -> engine.run("hi").response().toCompletableFuture().join())
        .hasRootCauseInstanceOf(IllegalStateException.class)
        .hasRootCauseMessage("model failure");
    assertThat(log).containsExactly("before:memory");
    assertThat(provider.responseDuringBeforeRun).isEmpty();
    assertThat(provider.afterRunContexts).isEmpty();
  }

  @Test
  void anOrdinaryRunWithoutContextProvidersReportsAModelClientFailureSynchronously() {
    BrokenClient client = BrokenClient.throwing();
    Agent engine = boundBuilder(client).build();

    // With no provider hook to wait for there is nothing to defer, so a run that cannot start
    // still fails from the call that started it, exactly as it did before the provider pipeline.
    assertThatThrownBy(() -> engine.run("hi"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("model client failure");
    assertThat(client.runCalled.get()).isTrue();
  }

  @Test
  void anOrdinaryRunWithoutContextProvidersRejectsANullModelResponseStageSynchronously() {
    Agent engine = boundBuilder(BrokenClient.returningNull()).build();

    assertThatThrownBy(() -> engine.run("hi"))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("model client update publisher must not be null");
  }

  @Test
  void anOrdinaryRunWithContextProvidersDefersTheModelCallUntilBeforeRunHooksComplete() {
    List<String> log = new ArrayList<>();
    GatedBeforeRunProvider gated = new GatedBeforeRunProvider("slow", log);
    BrokenClient client = BrokenClient.throwing();
    Agent engine = boundBuilder(client).contextProviders(gated).build();

    AgentRun run = engine.run("hi");

    // The model is not called while the hook is still pending, so the run has not failed yet
    // either; the same client that fails synchronously with no providers must not be reached here.
    assertThat(client.runCalled.get()).isFalse();
    assertThat(run.response().toCompletableFuture()).isNotDone();

    gated.releaseBeforeRun();

    // Once the hook completes the deferred model call happens, and its failure surfaces on the
    // response stage instead of synchronously from run().
    assertThatThrownBy(() -> run.response().toCompletableFuture().join())
        .hasRootCauseInstanceOf(IllegalStateException.class)
        .hasRootCauseMessage("model client failure");
    assertThat(client.runCalled.get()).isTrue();
    assertThat(log).containsExactly("before:slow");
  }

  @Test
  void cancellationBeforeTheRunSkipsEveryProviderHook() {
    List<String> log = new ArrayList<>();
    RecordingProvider provider = new RecordingProvider("memory", log);
    CancellationSignal signal = new CancellationSignal();
    signal.cancel();
    Agent engine = boundBuilder(fixedClient("hello")).contextProviders(provider).build();
    AgentRunRequest request =
        request(
            Message.normalize("hi"),
            null,
            new AgentRunOptions(),
            signal,
            ContextAttributes.empty());

    assertThatThrownBy(() -> engine.run(request).response().toCompletableFuture().join())
        .hasCauseInstanceOf(CancellationException.class);
    assertThat(log).isEmpty();
  }

  @Test
  void cancellingWhileABeforeRunHookIsPendingSkipsTheModelAndEveryAfterRunHook() {
    List<String> log = new ArrayList<>();
    ModelClient client =
        request -> {
          log.add("model");
          return EngineModels.of(response("hello"));
        };
    GatedBeforeRunProvider gated = new GatedBeforeRunProvider("slow", log);
    Agent engine = boundBuilder(client).contextProviders(gated).build();

    AgentRun run = engine.run("hi");
    run.cancel();

    assertThat(log).containsExactly("before:slow");
    assertThatThrownBy(() -> run.response().toCompletableFuture().join())
        .hasCauseInstanceOf(CancellationException.class);

    gated.releaseBeforeRun();

    assertThat(log).containsExactly("before:slow");
    assertThatThrownBy(() -> run.response().toCompletableFuture().join())
        .hasCauseInstanceOf(CancellationException.class);
  }

  @Test
  void everyProviderHookReceivesOnlyTheStateViewBoundToItsOwnSourceId() {
    List<String> log = new ArrayList<>();
    RecordingProvider first = new RecordingProvider("first", log);
    RecordingProvider second = new RecordingProvider("second", log);
    Agent engine = boundBuilder(fixedClient("hello")).contextProviders(first, second).build();

    runWithSession(engine, session("session-1", null, Map.of("first", 3, "second", 5)));

    assertThat(first.observedStateSourceIds).containsExactly("first", "first");
    assertThat(second.observedStateSourceIds).containsExactly("second", "second");
    assertThat(first.observedStateValues).containsExactly(3);
    assertThat(second.observedStateValues).containsExactly(5);
  }

  @Test
  void aProviderUsingOnlyItsBoundViewCarriesStateIntoTheNextRunOfTheSameSession() {
    List<String> log = new ArrayList<>();
    RecordingProvider provider = new RecordingProvider("memory", log);
    Agent engine = boundBuilder(fixedClient("hello")).contextProviders(provider).build();

    runWithSession(engine, session("session-1", null, Map.of()));
    runWithSession(engine, provider.updatedSessions.get(0));

    assertThat(provider.observedStateValues).containsExactly(0, 1);
    assertThat(provider.updatedSessions)
        .extracting(
            session ->
                session.state().get(SessionStateKey.of("memory", JsonValue.class)).orElse(null))
        .containsExactly(JsonNumber.of(1), JsonNumber.of(2));
  }

  @Test
  void streamingBeforeRunFailureFailsTheStreamAndTheResponseWithoutCallingTheModel() {
    List<String> log = new ArrayList<>();
    StreamingFakeClient client = new StreamingFakeClient(log);
    RecordingProvider first = new RecordingProvider("first", log);
    Agent engine =
        boundBuilder(client)
            .contextProviders(first, new FailingBeforeRunProvider("second", log))
            .build();

    AgentStreamingRun<AgentResponseUpdate> run = engine.runStreaming("hi");

    assertThatThrownBy(() -> consume(run.updates()))
        .hasRootCauseInstanceOf(IllegalStateException.class)
        .hasRootCauseMessage("before-run failure");
    assertThatThrownBy(() -> run.response().toCompletableFuture().join())
        .hasRootCauseInstanceOf(IllegalStateException.class)
        .hasRootCauseMessage("before-run failure");
    assertThat(log).containsExactly("before:first", "before:second");
    assertThat(client.capturedRequest.get()).isNull();
    assertThat(first.afterRunContexts).isEmpty();
  }

  @Test
  void streamingAfterRunObservesTheFinalResponseThroughTheSharedSessionContext() {
    List<String> log = new ArrayList<>();
    RecordingProvider provider = new RecordingProvider("memory", log);
    Agent engine = boundBuilder(new StreamingFakeClient(log)).contextProviders(provider).build();

    AgentStreamingRun<AgentResponseUpdate> run = engine.runStreaming("hi");
    consume(run.updates());
    AgentResponse response = run.response().toCompletableFuture().join();

    assertThat(provider.responseDuringBeforeRun).isEmpty();
    assertThat(provider.responseDuringAfterRun).containsSame(response);
    assertThat(provider.afterRunContexts).containsExactly(provider.beforeRunContexts.get(0));
  }

  @Test
  void oneProviderInstanceKeepsTwoStreamingSessionsStateSlotsSeparate() {
    List<String> log = new ArrayList<>();
    RecordingProvider provider = new RecordingProvider("memory", log);
    Agent engine = boundBuilder(new StreamingFakeClient(log)).contextProviders(provider).build();

    runStreamingWithSession(engine, session("session-1", null, Map.of()));
    runStreamingWithSession(engine, session("session-2", null, Map.of("memory", 7)));

    assertThat(provider.observedStateValues).containsExactly(0, 7);
    assertThat(provider.updatedSessions)
        .extracting(
            session ->
                session.state().get(SessionStateKey.of("memory", JsonValue.class)).orElse(null))
        .containsExactly(JsonNumber.of(1), JsonNumber.of(8));
  }

  @Test
  void cancellingAStreamingRunWhileABeforeRunHookIsPendingTerminatesTheUpdateSubscriber() {
    List<String> log = new ArrayList<>();
    StreamingFakeClient client = new StreamingFakeClient(log);
    GatedBeforeRunProvider gated = new GatedBeforeRunProvider("slow", log);
    Agent engine = boundBuilder(client).contextProviders(gated).build();

    AgentStreamingRun<AgentResponseUpdate> run = engine.runStreaming("hi");
    RecordingSubscriber<AgentResponseUpdate> subscriber = subscribe(run.updates());

    run.cancel();

    assertThat(subscriber.completion).isCompletedExceptionally();
    assertThat(subscriber.terminalFailure.get())
        .isInstanceOf(CancellationException.class)
        .hasMessage("run was cancelled");
    assertThat(subscriber.values).isEmpty();
    assertThatThrownBy(() -> run.response().toCompletableFuture().join())
        .hasCauseInstanceOf(CancellationException.class);

    gated.releaseBeforeRun();

    assertThat(log).containsExactly("before:slow");
    assertThat(client.capturedRequest.get()).isNull();
  }

  @Test
  void aStreamingRunWithoutContextProvidersReportsAModelClientFailureSynchronously() {
    BrokenStreamingClient client = BrokenStreamingClient.throwing();
    Agent engine = boundBuilder(client).build();

    // With no provider hook to wait for there is nothing to defer, so a run that cannot start
    // still fails from the call that started it, exactly as it did before the provider pipeline.
    assertThatThrownBy(() -> engine.runStreaming("hi"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("streaming client failure");
    assertThat(client.streamingCalled.get()).isTrue();
  }

  @Test
  void aStreamingRunWithoutContextProvidersRejectsANullUpdatePublisherSynchronously() {
    Agent engine = boundBuilder(BrokenStreamingClient.returningNull()).build();

    assertThatThrownBy(() -> engine.runStreaming("hi"))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("model client update publisher must not be null");
  }

  @Test
  void aStreamingRunWithContextProvidersDeliversAModelClientFailureThroughTheStream() {
    List<String> log = new ArrayList<>();
    RecordingProvider provider = new RecordingProvider("memory", log);
    Agent engine =
        boundBuilder(BrokenStreamingClient.throwing()).contextProviders(provider).build();

    // A configured provider makes the model call happen after an asynchronous hook, so the same
    // failure can no longer be raised by runStreaming; it is delivered as a terminal onError.
    AgentStreamingRun<AgentResponseUpdate> run = engine.runStreaming("hi");

    assertThatThrownBy(() -> consume(run.updates()))
        .hasRootCauseInstanceOf(IllegalStateException.class)
        .hasRootCauseMessage("streaming client failure");
    assertThatThrownBy(() -> run.response().toCompletableFuture().join())
        .hasRootCauseInstanceOf(IllegalStateException.class)
        .hasRootCauseMessage("streaming client failure");
    assertThat(log).containsExactly("before:memory");
    assertThat(provider.afterRunContexts).isEmpty();
  }

  @Test
  void aStreamingRunWithContextProvidersDeliversANullUpdatePublisherThroughTheStream() {
    List<String> log = new ArrayList<>();
    Agent engine =
        boundBuilder(BrokenStreamingClient.returningNull())
            .contextProviders(new RecordingProvider("memory", log))
            .build();

    AgentStreamingRun<AgentResponseUpdate> run = engine.runStreaming("hi");

    assertThatThrownBy(() -> consume(run.updates()))
        .hasRootCauseInstanceOf(NullPointerException.class)
        .hasRootCauseMessage("model client update publisher must not be null");
    assertThatThrownBy(() -> run.response().toCompletableFuture().join())
        .hasRootCauseInstanceOf(NullPointerException.class)
        .hasRootCauseMessage("model client update publisher must not be null");
  }

  @Test
  void aStreamingAfterRunFailureFailsTheUpdateStreamAndTheResponse() {
    List<String> log = new ArrayList<>();
    Agent engine =
        boundBuilder(new StreamingFakeClient(log))
            .contextProviders(new FailingAfterRunProvider("memory", log))
            .build();

    AgentStreamingRun<AgentResponseUpdate> run = engine.runStreaming("hi");
    RecordingSubscriber<AgentResponseUpdate> subscriber = subscribe(run.updates());

    // The engine now owns the post-run lifecycle, so a provider-completion failure fails the update
    // stream with onError before any onComplete, after every model update was already delivered.
    assertThat(subscriber.values).extracting(AgentResponseUpdate::text).containsExactly("hello");
    assertThatThrownBy(subscriber.completion::join)
        .hasRootCauseInstanceOf(IllegalStateException.class)
        .hasRootCauseMessage("after-run failure");
    assertThat(subscriber.terminalFailure.get())
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("after-run failure");
    // The response stage carries the same after-run failure.
    assertThatThrownBy(() -> run.response().toCompletableFuture().join())
        .hasRootCauseInstanceOf(IllegalStateException.class)
        .hasRootCauseMessage("after-run failure");
    assertThat(log).containsExactly("before:memory", "model", "after:memory");
  }

  @Test
  void definitionAndProviderInstructionsBecomeLeadingSystemMessagesInRegistrationOrder() {
    AtomicReference<ModelRequest> captured = new AtomicReference<>();
    ModelClient client =
        request -> {
          captured.set(request);
          return EngineModels.of(response("ok"));
        };
    Agent engine =
        boundBuilder(client)
            .instructions("be terse")
            .contextProviders(
                new ContributingProvider(
                    "first",
                    new ArrayList<>(),
                    RunContribution.builder().addInstructionAddition("from-first").build()),
                new ContributingProvider(
                    "second",
                    new ArrayList<>(),
                    RunContribution.builder().addInstructionAddition("from-second").build()))
            .build();

    engine.run("hi").response().toCompletableFuture().join();

    assertThat(captured.get().messages())
        .extracting(message -> message.role().value() + ":" + message.text())
        .containsExactly("system:be terse", "system:from-first", "system:from-second", "user:hi");
  }

  @Test
  void aLeadingInstructionDuplicatedByAProviderIsNotInsertedTwice() {
    AtomicReference<ModelRequest> captured = new AtomicReference<>();
    ModelClient client =
        request -> {
          captured.set(request);
          return EngineModels.of(response("ok"));
        };
    Agent engine =
        boundBuilder(client)
            .instructions("shared")
            .contextProviders(
                new ContributingProvider(
                    "dup",
                    new ArrayList<>(),
                    RunContribution.builder().addInstructionAddition("shared").build()))
            .build();

    engine.run("hi").response().toCompletableFuture().join();

    assertThat(captured.get().messages())
        .extracting(message -> message.role().value() + ":" + message.text())
        .containsExactly("system:shared", "user:hi");
  }

  @Test
  void aContributedToolIsOfferedToTheModelButNotExecutedLocally() {
    AtomicInteger calls = new AtomicInteger();
    AtomicReference<ModelRequest> captured = new AtomicReference<>();
    ModelClient client =
        request -> {
          calls.incrementAndGet();
          captured.set(request);
          return EngineModels.of(toolCallResponse("call-1", "lookup"));
        };
    ToolDefinition lookup = ToolDefinition.builder().name("lookup").description("d").build();
    Agent engine =
        boundBuilder(client)
            .contextProviders(
                new ContributingProvider(
                    "tools", new ArrayList<>(), RunContribution.builder().addTool(lookup).build()))
            .build();

    AgentResponse response = engine.run("hi").response().toCompletableFuture().join();

    assertThat(captured.get().tools()).extracting(ToolDefinition::name).containsExactly("lookup");
    // A contributed tool is declaration-only: the model is offered it, but the call it makes ends
    // the run rather than being executed locally, so there is exactly one model call and no result.
    assertThat(calls.get()).isEqualTo(1);
    assertThat(response.messages())
        .allSatisfy(
            message ->
                assertThat(message.content())
                    .noneMatch(content -> content instanceof ToolResultContent));
  }

  @Test
  void aContributedToolNameDuplicatingADeclaredToolFailsBeforeTheModelIsCalled() {
    AtomicInteger calls = new AtomicInteger();
    ModelClient client =
        request -> {
          calls.incrementAndGet();
          return EngineModels.of(response("ok"));
        };
    ToolDefinition duplicate = ToolDefinition.builder().name("weather").description("d").build();
    Agent engine =
        boundBuilder(client)
            .tools(weatherTool())
            .contextProviders(
                new ContributingProvider(
                    "dup", new ArrayList<>(), RunContribution.builder().addTool(duplicate).build()))
            .build();

    assertThatThrownBy(() -> engine.run("hi").response().toCompletableFuture().join())
        .hasRootCauseInstanceOf(IllegalStateException.class)
        .hasRootCauseMessage("duplicate contributed tool name: weather");
    assertThat(calls.get()).isZero();
  }

  @Test
  void contributedModelOptionsMergeInProviderOrderWithLaterProvidersOverridingEarlier() {
    AtomicReference<ModelRequest> captured = new AtomicReference<>();
    ModelClient client =
        request -> {
          captured.set(request);
          return EngineModels.of(response("ok"));
        };
    TestProviderOption first = new TestProviderOption("first");
    TestProviderOption second = new TestProviderOption("second");
    Agent engine =
        boundBuilder(client)
            .contextProviders(
                new ContributingProvider(
                    "first",
                    new ArrayList<>(),
                    RunContribution.builder()
                        .modelOptions(
                            ModelRequestOptions.builder()
                                .temperature(0.2)
                                .maxOutputTokens(100)
                                .providerOption(first)
                                .build())
                        .build()),
                new ContributingProvider(
                    "second",
                    new ArrayList<>(),
                    RunContribution.builder()
                        .modelOptions(
                            ModelRequestOptions.builder()
                                .temperature(0.9)
                                .providerOption(second)
                                .build())
                        .build()))
            .build();

    engine.run("hi").response().toCompletableFuture().join();

    ModelRequestOptions options = captured.get().options();
    assertThat(options.temperature()).contains(0.9);
    assertThat(options.maxOutputTokens()).hasValue(100);
    assertThat(options.providerOption(TestProviderOption.class)).contains(second);
  }

  @Test
  void statelessProvidersReserveNoStateAndContributeUnattributedContextMessages() {
    AtomicReference<ModelRequest> captured = new AtomicReference<>();
    ModelClient client =
        request -> {
          captured.set(request);
          return EngineModels.of(response("ok"));
        };
    Agent engine =
        boundBuilder(client)
            .contextProviders(
                new ContributingProvider(
                    "a",
                    new ArrayList<>(),
                    RunContribution.builder()
                        .messages(
                            List.of(new Message(Role.USER, List.of(new TextContent("ctx-a")))))
                        .build()),
                new ContributingProvider(
                    "b",
                    new ArrayList<>(),
                    RunContribution.builder()
                        .messages(
                            List.of(new Message(Role.USER, List.of(new TextContent("ctx-b")))))
                        .build()))
            .build();

    engine.run("hi").response().toCompletableFuture().join();

    assertThat(captured.get().messages())
        .extracting(Message::text)
        .containsExactly("ctx-a", "ctx-b", "hi");
    assertThat(captured.get().messages())
        .extracting(Message::attribution)
        .containsExactly(null, null, null);
  }

  @Test
  void effectiveInstructionsToolsAndOptionsAreRetainedOnEveryToolLoopIteration() {
    List<ModelRequest> requests = new ArrayList<>();
    AtomicInteger calls = new AtomicInteger();
    ModelClient client =
        request -> {
          requests.add(request);
          return calls.getAndIncrement() == 0
              ? EngineModels.of(toolCallResponse("call-1", "weather"))
              : EngineModels.of(response("done"));
        };
    Agent engine =
        boundBuilder(client)
            .instructions("sys")
            .tools(weatherTool())
            .contextProviders(
                new ContributingProvider(
                    "opt",
                    new ArrayList<>(),
                    RunContribution.builder()
                        .modelOptions(ModelRequestOptions.builder().temperature(0.5).build())
                        .build()))
            .build();

    engine.run("hi").response().toCompletableFuture().join();

    assertThat(requests).hasSize(2);
    assertThat(requests)
        .allSatisfy(
            request -> {
              assertThat(request.messages().get(0).role()).isEqualTo(Role.SYSTEM);
              assertThat(request.messages().get(0).text()).isEqualTo("sys");
              assertThat(request.options().temperature()).contains(0.5);
              assertThat(request.tools()).extracting(ToolDefinition::name).contains("weather");
            });
  }

  @Test
  void contributionsProduceTheSameEffectiveRequestForOrdinaryAndStreamingRuns() {
    AtomicReference<ModelRequest> ordinaryRequest = new AtomicReference<>();
    ModelClient ordinaryClient =
        request -> {
          ordinaryRequest.set(request);
          return EngineModels.of(response("ok"));
        };
    StreamingFakeClient streamingClient = new StreamingFakeClient(new ArrayList<>());
    Agent ordinaryAgent =
        boundBuilder(ordinaryClient)
            .instructions("def")
            .contextProviders(
                new ContributingProvider("p", new ArrayList<>(), sharedContribution()))
            .build();
    Agent streamingAgent =
        boundBuilder(streamingClient)
            .instructions("def")
            .contextProviders(
                new ContributingProvider("p", new ArrayList<>(), sharedContribution()))
            .build();

    ordinaryAgent.run("hi").response().toCompletableFuture().join();
    AgentStreamingRun<AgentResponseUpdate> run = streamingAgent.runStreaming("hi");
    consume(run.updates());
    run.response().toCompletableFuture().join();

    ModelRequest ordinary = ordinaryRequest.get();
    ModelRequest streaming = streamingClient.capturedRequest.get();
    assertThat(streaming.messages())
        .extracting(message -> message.role().value() + ":" + message.text())
        .isEqualTo(
            ordinary.messages().stream()
                .map(message -> message.role().value() + ":" + message.text())
                .toList());
    assertThat(streaming.tools())
        .extracting(ToolDefinition::name)
        .isEqualTo(ordinary.tools().stream().map(ToolDefinition::name).toList());
    assertThat(streaming.options()).isEqualTo(ordinary.options());
  }

  private static RunContribution sharedContribution() {
    return RunContribution.builder()
        .addInstructionAddition("sys")
        .addTool(ToolDefinition.builder().name("lookup").description("d").build())
        .modelOptions(ModelRequestOptions.builder().temperature(0.3).build())
        .messages(List.of(new Message(Role.USER, List.of(new TextContent("ctx")))))
        .build();
  }

  private static void runStreamingWithSession(Agent engine, AgentSession session) {
    AgentStreamingRun<AgentResponseUpdate> run =
        engine.runStreaming(
            request(
                Message.normalize("hi"),
                session,
                new AgentRunOptions(),
                new CancellationSignal(),
                ContextAttributes.empty()));
    consume(run.updates());
    run.response().toCompletableFuture().join();
  }

  private static void runWithSession(Agent engine, AgentSession session) {
    engine
        .run(
            request(
                Message.normalize("hi"),
                session,
                new AgentRunOptions(),
                new CancellationSignal(),
                ContextAttributes.empty()))
        .response()
        .toCompletableFuture()
        .join();
  }

  private static AgentRunRequest request(
      List<? extends Message> messages,
      AgentSession session,
      AgentRunOptions options,
      CancellationSignal cancellationSignal,
      ContextAttributes attributes) {
    return AgentRunRequest.builder()
        .messages(messages)
        .session(session)
        .options(options)
        .cancellationSignal(cancellationSignal)
        .attributes(attributes)
        .build();
  }

  private static ModelClient fixedClient(String text) {
    return request -> EngineModels.of(response(text));
  }

  /** Counts model invocations so a test can prove a run never reached the model client. */
  private static final class CountingClient implements ModelClient {
    private final String text;
    private final AtomicInteger invocations = new AtomicInteger();

    private CountingClient(String text) {
      this.text = text;
    }

    @Override
    public Flow.Publisher<ModelResponseUpdate> execute(ModelRequest request) {
      invocations.incrementAndGet();
      return EngineModels.of(response(text));
    }
  }

  private static ModelResponse response(String text) {
    return ModelResponse.builder()
        .messages(List.of(new Message(Role.ASSISTANT, List.of(new TextContent(text)))))
        .finishReason(FinishReason.STOP)
        .build();
  }

  private static ModelResponse toolCallResponse(String callId, String name) {
    return ModelResponse.builder()
        .messages(
            List.of(
                new Message(
                    Role.ASSISTANT,
                    List.of(new ToolCallContent(callId, name, JsonObject.empty())))))
        .finishReason(FinishReason.TOOL_CALLS)
        .build();
  }

  private static FunctionTool weatherTool() {
    return FunctionTool.create(
        "weather",
        "weather",
        JsonObject.empty(),
        (arguments, context) -> completedFuture(ToolResult.success(new TextContent("sunny"))));
  }

  /**
   * A stateless {@link ContextProvider} that returns a fixed {@link RunContribution}, so a test can
   * drive contributed instructions, tools, and options through the engine's merge pipeline.
   */
  private static final class ContributingProvider implements ContextProvider {
    private final String id;
    private final List<String> log;
    private final RunContribution contribution;

    private ContributingProvider(String id, List<String> log, RunContribution contribution) {
      this.id = id;
      this.log = log;
      this.contribution = contribution;
    }

    @Override
    public CompletionStage<RunContribution> prepare(SessionContext context) {
      log.add("before:" + id);
      return completedFuture(contribution);
    }

    @Override
    public CompletionStage<Void> complete(SessionContext context) {
      log.add("after:" + id);
      return completedFuture(null);
    }
  }

  private record TestProviderOption(String tag) implements ModelProviderOption {
    @Override
    public String providerId() {
      return "test";
    }
  }

  private static <T> List<T> consume(Flow.Publisher<T> publisher) {
    RecordingSubscriber<T> subscriber = subscribe(publisher);
    subscriber.completion.join();
    return subscriber.values;
  }

  /**
   * Subscribes without waiting for a terminal signal, so a test can assert what a subscriber has
   * (or has not) been told at a chosen point of a run rather than blocking on it.
   */
  private static <T> RecordingSubscriber<T> subscribe(Flow.Publisher<T> publisher) {
    RecordingSubscriber<T> subscriber = new RecordingSubscriber<>();
    publisher.subscribe(subscriber);
    return subscriber;
  }

  private static AgentSession session(
      String sessionId, String serviceSessionId, Map<String, ?> state) {
    AgentSession.Builder builder =
        AgentSession.builder().sessionId(sessionId).state(sessionState(state));
    if (serviceSessionId != null) {
      builder.serviceSessionId(serviceSessionId);
    }
    return builder.build();
  }

  private static SessionState sessionState(Map<String, ?> state) {
    SessionState sessionState = SessionState.empty();
    for (Map.Entry<String, ?> entry : state.entrySet()) {
      sessionState = put(sessionState, entry.getKey(), entry.getValue());
    }
    return sessionState;
  }

  @SuppressWarnings("unchecked")
  private static SessionState put(SessionState state, String key, Object value) {
    if (SessionStateValues.isJsonValueShape(value)) {
      return state.with(SessionStateKey.of(key, JsonValue.class), JsonValues.fromJava(value));
    }
    return state.with((SessionStateKey<Object>) SessionStateKey.of(key, value.getClass()), value);
  }

  private static int asInt(JsonValue value) {
    return ((JsonNumber) value).value().intValueExact();
  }

  private static final class RecordingSubscriber<T> implements Flow.Subscriber<T> {
    private final List<T> values = new ArrayList<>();
    private final CompletableFuture<Void> completion = new CompletableFuture<>();
    private final AtomicReference<Throwable> terminalFailure = new AtomicReference<>();

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
      subscription.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(T item) {
      values.add(item);
    }

    @Override
    public void onError(Throwable throwable) {
      terminalFailure.set(throwable);
      completion.completeExceptionally(throwable);
    }

    @Override
    public void onComplete() {
      completion.complete(null);
    }
  }

  private static class RecordingProvider implements StatefulContextProvider<JsonValue> {
    private final String sourceId;
    private final SessionStateKey<JsonValue> stateKey;
    private final List<String> log;
    private final List<Integer> observedStateValues = new ArrayList<>();
    private final List<String> observedStateSourceIds = new ArrayList<>();
    private final List<AgentSession> updatedSessions = new ArrayList<>();
    private final List<SessionContext> beforeRunContexts = new ArrayList<>();
    private final List<SessionContext> afterRunContexts = new ArrayList<>();
    private Optional<AgentResponse> responseDuringBeforeRun = Optional.empty();
    private Optional<AgentResponse> responseDuringAfterRun = Optional.empty();

    private RecordingProvider(String sourceId, List<String> log) {
      this.sourceId = sourceId;
      this.stateKey = SessionStateKey.of(sourceId, JsonValue.class);
      this.log = log;
    }

    @Override
    public SessionStateKey<JsonValue> stateKey() {
      return stateKey;
    }

    @Override
    public CompletionStage<RunContribution> prepare(
        SessionContext context, ProviderSessionState<JsonValue> state) {
      log.add("before:" + sourceId);
      beforeRunContexts.add(context);
      responseDuringBeforeRun = context.response();
      observedStateSourceIds.add(state.key().id());
      int seen = state.value().map(AgentEngineSessionContextTest::asInt).orElse(0);
      observedStateValues.add(seen);
      state.set(JsonValues.fromJava(seen + 1));
      return completedFuture(
          RunContribution.builder()
              .messages(
                  List.of(new Message(Role.USER, List.of(new TextContent("context:" + sourceId)))))
              .build());
    }

    @Override
    public CompletionStage<Void> complete(
        SessionContext context, ProviderSessionState<JsonValue> state) {
      log.add("after:" + sourceId);
      afterRunContexts.add(context);
      responseDuringAfterRun = context.response();
      observedStateSourceIds.add(state.key().id());
      context.updatedSession().ifPresent(updatedSessions::add);
      return completedFuture(null);
    }
  }

  /**
   * A provider whose {@code prepare} stays pending until the test releases it, so a run can be
   * cancelled while the framework is inside the asynchronous window Task 2 opened between the hooks
   * and the model call.
   */
  private static final class GatedBeforeRunProvider extends RecordingProvider {
    private final CompletableFuture<Void> gate = new CompletableFuture<>();

    private GatedBeforeRunProvider(String sourceId, List<String> log) {
      super(sourceId, log);
    }

    @Override
    public CompletionStage<RunContribution> prepare(
        SessionContext context, ProviderSessionState<JsonValue> state) {
      CompletionStage<RunContribution> contribution = super.prepare(context, state);
      return gate.minimalCompletionStage().thenCompose(ignored -> contribution);
    }

    private void releaseBeforeRun() {
      gate.complete(null);
    }
  }

  private static final class FailingBeforeRunProvider extends RecordingProvider {
    private FailingBeforeRunProvider(String sourceId, List<String> log) {
      super(sourceId, log);
    }

    @Override
    public CompletionStage<RunContribution> prepare(
        SessionContext context, ProviderSessionState<JsonValue> state) {
      super.prepare(context, state);
      CompletableFuture<RunContribution> failed = new CompletableFuture<>();
      failed.completeExceptionally(new IllegalStateException("before-run failure"));
      return failed;
    }
  }

  private static final class FailingAfterRunProvider extends RecordingProvider {
    private FailingAfterRunProvider(String sourceId, List<String> log) {
      super(sourceId, log);
    }

    @Override
    public CompletionStage<Void> complete(
        SessionContext context, ProviderSessionState<JsonValue> state) {
      super.complete(context, state);
      CompletableFuture<Void> failed = new CompletableFuture<>();
      failed.completeExceptionally(new IllegalStateException("after-run failure"));
      return failed;
    }
  }

  private static final class NullStageProvider implements ContextProvider {
    private NullStageProvider() {}

    @Override
    public CompletionStage<RunContribution> prepare(SessionContext context) {
      return null;
    }

    @Override
    public CompletionStage<Void> complete(SessionContext context) {
      return completedFuture(null);
    }
  }

  /**
   * A non-streaming client that never returns a usable response stage, so a test can observe where
   * the failure of an ordinary run that cannot even start is reported.
   */
  private static final class BrokenClient implements ModelClient {
    private final boolean returnNull;
    private final AtomicBoolean runCalled = new AtomicBoolean();

    private BrokenClient(boolean returnNull) {
      this.returnNull = returnNull;
    }

    private static BrokenClient throwing() {
      return new BrokenClient(false);
    }

    private static BrokenClient returningNull() {
      return new BrokenClient(true);
    }

    @Override
    public Flow.Publisher<ModelResponseUpdate> execute(ModelRequest request) {
      runCalled.set(true);
      if (returnNull) {
        return null;
      }
      throw new IllegalStateException("model client failure");
    }
  }

  /**
   * A streaming client that never returns a usable update publisher, so a test can observe where
   * the failure of a run that cannot even start is reported.
   */
  private static final class BrokenStreamingClient implements ModelClient {
    private final boolean returnNull;
    private final AtomicBoolean streamingCalled = new AtomicBoolean();

    private BrokenStreamingClient(boolean returnNull) {
      this.returnNull = returnNull;
    }

    private static BrokenStreamingClient throwing() {
      return new BrokenStreamingClient(false);
    }

    private static BrokenStreamingClient returningNull() {
      return new BrokenStreamingClient(true);
    }

    @Override
    public Flow.Publisher<ModelResponseUpdate> execute(ModelRequest request) {
      streamingCalled.set(true);
      if (returnNull) {
        return null;
      }
      throw new IllegalStateException("streaming client failure");
    }
  }

  private static final class StreamingFakeClient implements ModelClient {
    private final List<String> log;
    private final AtomicReference<ModelRequest> capturedRequest = new AtomicReference<>();

    private StreamingFakeClient(List<String> log) {
      this.log = log;
    }

    @Override
    public Flow.Publisher<ModelResponseUpdate> execute(ModelRequest request) {
      log.add("model");
      capturedRequest.set(request);
      return subscriber ->
          subscriber.onSubscribe(
              new Flow.Subscription() {
                private boolean completed;

                @Override
                public void request(long n) {
                  if (completed || n <= 0) {
                    return;
                  }
                  completed = true;
                  subscriber.onNext(
                      ModelResponseUpdate.builder()
                          .messages(
                              List.of(
                                  new Message(Role.ASSISTANT, List.of(new TextContent("hello")))))
                          .finishReason(FinishReason.STOP)
                          .build());
                  subscriber.onComplete();
                }

                @Override
                public void cancel() {
                  completed = true;
                }
              });
    }
  }
}
