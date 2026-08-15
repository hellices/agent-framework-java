package io.github.hellices.agentframework.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hellices.agentframework.api.message.FinishReason;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.Role;
import io.github.hellices.agentframework.api.message.TextContent;
import io.github.hellices.agentframework.api.session.SessionContext;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AgentLifecycleTest {

  @Test
  void cancellationSignalTracksState() {
    CancellationSignal signal = new CancellationSignal();
    assertThat(signal.isCancelled()).isFalse();
    signal.cancel();
    assertThat(signal.isCancelled()).isTrue();
  }

  @Test
  void requestNormalizesInputAndKeepsDefaults() {
    AgentRunRequest request = AgentRunRequest.of("hello");

    assertThat(request.messages()).hasSize(1);
    assertThat(request.messages().get(0).role()).isEqualTo(Role.USER);
    assertThat(request.cancellationSignal()).isNotNull();
    assertThat(request.options()).isNotNull();
  }

  @Test
  void agentExposesStableIdAndRunEntryPoints() {
    TestAgent agent = new TestAgent("test-agent");

    assertThat(agent.id()).isEqualTo("test-agent");
    assertThat(agent.run("hi").response()).isNotNull();
    assertThat(agent.runStreaming("hi").updates()).isNotNull();
  }

  @Test
  void runFailsBeforeExecutionWhenSessionIsIncompatible() {
    CompatibilityCheckingAgent agent = new CompatibilityCheckingAgent("test-agent");

    AgentRunRequest request =
        new AgentRunRequest(
            Message.normalize("hi"),
            new AgentSession("session-1", "service-1", Map.of()),
            new AgentRunOptions(),
            new CancellationSignal(),
            Map.of());

    assertThatThrownBy(() -> agent.run(request))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("session session-1 is not compatible with agent test-agent");
    assertThat(agent.executedRun).isFalse();
    assertThat(agent.executedStreamingRun).isFalse();
  }

  @Test
  void streamingRunFailsBeforeExecutionWhenSessionIsIncompatible() {
    CompatibilityCheckingAgent agent = new CompatibilityCheckingAgent("test-agent");

    AgentRunRequest request =
        new AgentRunRequest(
            Message.normalize("hi"),
            new AgentSession("session-1", "service-1", Map.of()),
            new AgentRunOptions(),
            new CancellationSignal(),
            Map.of());

    assertThatThrownBy(() -> agent.runStreaming(request))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("session session-1 is not compatible with agent test-agent");
    assertThat(agent.executedRun).isFalse();
    assertThat(agent.executedStreamingRun).isFalse();
  }

  @Test
  void delegatingAgentPreservesIdentityAndDelegatesCalls() {
    TestAgent delegate = new TestAgent("delegate-id");
    Agent agent = new DelegatingAgent(delegate) {};

    AgentRun run = agent.run("hello");
    AgentStreamingRun<AgentResponseUpdate> streamingRun = agent.runStreaming("hello");

    assertThat(agent.id()).isEqualTo("delegate-id");
    assertThat(agent.name()).isEqualTo("TestAgent");
    assertThat(run.response()).isNotNull();
    assertThat(streamingRun.updates()).isNotNull();
  }

  @Test
  void stackingWrappersKeepsRunResultStable() throws Exception {
    TestAgent baseAgent = new TestAgent("base-agent");
    Agent wrapped = new DelegatingAgent(new DelegatingAgent(baseAgent) {}) {};

    assertThat(wrapped.run("hello").response().toCompletableFuture().get().text()).isEqualTo("ok");
    AgentStreamingRun<AgentResponseUpdate> streamingRun = wrapped.runStreaming("hello");
    consume(streamingRun.updates());
    assertThat(streamingRun.response().toCompletableFuture().get().text()).isEqualTo("ok");
  }

  @Test
  void runBuildsExplicitRunContextWithoutGlobalState() {
    ContextCapturingAgent agent = new ContextCapturingAgent("ctx-agent");
    AgentSession session = new AgentSession("session-42", "service-42", Map.of());
    AgentRunRequest request =
        new AgentRunRequest(
            Message.normalize("hello"),
            session,
            new AgentRunOptions(),
            new CancellationSignal(),
            Map.of("traceId", "trace-42"));

    agent.run(request);

    assertThat(agent.lastContext).isNotNull();
    assertThat(agent.lastContext.agent()).isEqualTo(agent);
    assertThat(agent.lastContext.session()).isEqualTo(session);
    assertThat(agent.lastContext.attributes()).containsEntry("traceId", "trace-42");
  }

  @Test
  void convenienceRunMethodsAreFinalToKeepInvariantChecks() throws Exception {
    assertThat(Modifier.isFinal(Agent.class.getMethod("run", String.class).getModifiers()))
        .isTrue();
    assertThat(Modifier.isFinal(Agent.class.getMethod("runStreaming", String.class).getModifiers()))
        .isTrue();
  }

  @Test
  void runContextIsPreservedWhenRunExecutesOnAnotherThread() {
    ContextCapturingAgent agent = new ContextCapturingAgent("ctx-agent");
    AgentSession session = new AgentSession("session-42", "service-42", Map.of());
    AgentRunRequest request =
        new AgentRunRequest(
            Message.normalize("hello"),
            session,
            new AgentRunOptions(),
            new CancellationSignal(),
            Map.of("traceId", "trace-42"));

    CompletableFuture.runAsync(() -> agent.run(request)).join();

    assertThat(agent.lastContext).isNotNull();
    assertThat(agent.lastContext.agent()).isEqualTo(agent);
    assertThat(agent.lastContext.session()).isEqualTo(session);
    assertThat(agent.lastContext.attributes()).containsEntry("traceId", "trace-42");
  }

  @Test
  void streamingRunHandleCancelsTheRequestSignal() {
    TestAgent agent = new TestAgent("test-agent");
    CancellationSignal signal = new CancellationSignal();
    AgentRunRequest request =
        new AgentRunRequest(
            Message.normalize("hello"), null, new AgentRunOptions(), signal, Map.of());

    agent.runStreaming(request).cancel();

    assertThat(signal.isCancelled()).isTrue();
  }

  @Test
  void runHandleCancelsTheRequestSignalAfterCompletion() {
    TestAgent agent = new TestAgent("test-agent");
    CancellationSignal signal = new CancellationSignal();
    AgentRunRequest request =
        new AgentRunRequest(
            Message.normalize("hello"), null, new AgentRunOptions(), signal, Map.of());

    agent.run(request).cancel();

    assertThat(signal.isCancelled()).isTrue();
  }

  @Test
  void eachRunReceivesADistinctSessionContextAndFillsItOnSuccess() {
    SessionContextCapturingAgent agent = new SessionContextCapturingAgent("ctx-agent");

    AgentRun first = agent.run(AgentRunRequest.of("one"));
    AgentRun second = agent.run(AgentRunRequest.of("two"));

    assertThat(agent.contexts).hasSize(2);
    assertThat(agent.contexts.get(0)).isNotSameAs(agent.contexts.get(1));
    assertThat(first.response().toCompletableFuture().join())
        .isSameAs(agent.contexts.get(0).response().orElseThrow());
    assertThat(second.response().toCompletableFuture().join())
        .isSameAs(agent.contexts.get(1).response().orElseThrow());
  }

  @Test
  void sessionContextCopiesInputMessagesSessionAndMetadata() {
    SessionContextCapturingAgent agent = new SessionContextCapturingAgent("ctx-agent");
    AgentSession session = new AgentSession("session-42", "service-42", Map.of());
    AgentRunRequest request =
        new AgentRunRequest(
            Message.normalize("hello"),
            session,
            new AgentRunOptions(),
            new CancellationSignal(),
            Map.of("traceId", "trace-42"));

    agent.run(request);

    SessionContext sessionContext = agent.contexts.get(0);
    assertThat(sessionContext.session()).isEqualTo(session);
    assertThat(sessionContext.inputMessages()).isEqualTo(request.messages());
    assertThat(sessionContext.metadata()).containsEntry("traceId", "trace-42");
    assertThat(sessionContext.contextMessages()).isEmpty();
    assertThat(sessionContext.cancellationSignal()).isEqualTo(request.cancellationSignal());
  }

  @Test
  void streamingRunFillsSessionContextResponseSlotOnlyAfterTerminalCompletion() {
    SessionContextCapturingAgent agent = new SessionContextCapturingAgent("ctx-agent");

    AgentStreamingRun<AgentResponseUpdate> streamingRun = agent.runStreaming("hello");
    SessionContext sessionContext = agent.contexts.get(0);
    assertThat(sessionContext.response()).isEmpty();

    consume(streamingRun.updates());

    assertThat(sessionContext.response()).isPresent();
    assertThat(streamingRun.response().toCompletableFuture().join())
        .isSameAs(sessionContext.response().orElseThrow());
  }

  @Test
  void runFailsWhenSessionContextResponseIsAlreadyCompleteBeforeRunInternalReturns() {
    PreCompletingSessionContextAgent agent =
        new PreCompletingSessionContextAgent("pre-completed-agent");

    AgentRun run = agent.run("hello");

    // The exposed run's response stage is derived (via AgentRun.withCompletion /
    // Agent#completionAction) from the framework calling sessionContext.complete(value) after
    // runInternal returns. If the SessionContext was already completed beforehand, that call
    // throws IllegalStateException, and this test guards that the exception genuinely propagates
    // through join() instead of being lost or masked by a differently-worded failure.
    assertThatThrownBy(() -> run.response().toCompletableFuture().join())
        .hasCauseInstanceOf(IllegalStateException.class)
        .cause()
        .hasMessage("session context response is already complete");
    assertThat(agent.contexts).hasSize(1);
  }

  @Test
  void runDoesNotFillSessionContextResponseSlotOnFailure() {
    FailingAgent agent = new FailingAgent("failing-agent");

    AgentRun run = agent.run("hello");

    assertThatThrownBy(() -> run.response().toCompletableFuture().join())
        .hasCauseInstanceOf(IllegalStateException.class);
    assertThat(agent.contexts).hasSize(1);
    assertThat(agent.contexts.get(0).response()).isEmpty();
  }

  @Test
  void runDoesNotFillSessionContextResponseSlotWhenCancelledBeforeCompletion() {
    CompletableFuture<AgentResponse> manualSource = new CompletableFuture<>();
    ManualCompletionAgent agent = new ManualCompletionAgent("manual-agent", manualSource);

    AgentRun run = agent.run("hello");
    SessionContext sessionContext = agent.contexts.get(0);
    run.cancel();

    assertThatThrownBy(() -> run.response().toCompletableFuture().join())
        .hasCauseInstanceOf(CancellationException.class);
    assertThat(sessionContext.response()).isEmpty();

    // Completing the underlying source after cancellation must not retroactively fill the slot.
    manualSource.complete(sampleResponse("manual-agent"));
    assertThat(sessionContext.response()).isEmpty();
  }

  @Test
  void streamingRunDoesNotFillSessionContextResponseSlotWhenCancelledBeforeCompletion() {
    SessionContextCapturingAgent agent = new SessionContextCapturingAgent("manual-streaming-agent");

    AgentStreamingRun<AgentResponseUpdate> run = agent.runStreaming("hello");
    SessionContext sessionContext = agent.contexts.get(0);
    run.cancel();

    assertThatThrownBy(() -> run.response().toCompletableFuture().join())
        .hasCauseInstanceOf(CancellationException.class);
    assertThat(sessionContext.response()).isEmpty();
  }

  @Test
  void responseJoinCannotReturnBeforeSessionContextResponseIsPopulated() {
    CompletableFuture<AgentResponse> manualSource = new CompletableFuture<>();
    ManualCompletionAgent agent = new ManualCompletionAgent("manual-agent", manualSource);

    AgentRun run = agent.run("hello");
    SessionContext sessionContext = agent.contexts.get(0);
    AgentResponse expected = sampleResponse("manual-agent");

    // A dependent stage chained off the exposed response stage can only run once that stage has
    // completed, so capturing sessionContext.response() from inside it proves the session context
    // slot is filled no later than the exposed response stage completes.
    AtomicReference<AgentResponse> sessionResponseAtStageCompletion = new AtomicReference<>();
    CompletionStage<AgentResponse> observedCompletion =
        run.response()
            .whenComplete(
                (value, failure) ->
                    sessionResponseAtStageCompletion.set(sessionContext.response().orElse(null)));

    manualSource.complete(expected);

    // Not just "populated eventually" but the very same instance handed to the dependent stage,
    // proving the exposed response stage is genuinely downstream of the session context
    // completion rather than a coincidentally-ordered sibling callback.
    assertThat(observedCompletion.toCompletableFuture().join()).isSameAs(expected);
    assertThat(sessionResponseAtStageCompletion.get()).isSameAs(expected);
  }

  @Test
  void withCompletionResponseCannotCompleteBeforeCompletionActionFinishes() throws Exception {
    CompletableFuture<AgentResponse> source = new CompletableFuture<>();
    AgentRun run = new AgentRun(source, new CancellationSignal());
    CountDownLatch actionStarted = new CountDownLatch(1);
    CountDownLatch releaseAction = new CountDownLatch(1);
    AtomicBoolean actionFinished = new AtomicBoolean();
    AgentRun observed =
        run.withCompletion(
            (value, failure) -> {
              actionStarted.countDown();
              // Bounded wait used only as hang protection; the test releases this latch itself.
              awaitLatch(releaseAction);
              actionFinished.set(true);
            });

    CompletableFuture.runAsync(() -> source.complete(sampleResponse("manual-agent")));

    assertThat(actionStarted.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(observed.response().toCompletableFuture().isDone()).isFalse();

    releaseAction.countDown();
    observed.response().toCompletableFuture().join();

    assertThat(actionFinished.get()).isTrue();
  }

  @Test
  void streamingWithCompletionResponseCannotCompleteBeforeCompletionActionFinishes()
      throws Exception {
    AgentResponseUpdate update =
        new AgentResponseUpdate(
            "manual-agent",
            "response-1",
            "message-1",
            "manual-agent",
            null,
            FinishReason.STOP,
            List.of(new Message(Role.ASSISTANT, List.of(new TextContent("ok")))),
            null,
            Map.of(),
            null);
    AgentStreamingRun<AgentResponseUpdate> run = AgentStreamingRun.fromUpdate(update);
    CountDownLatch actionStarted = new CountDownLatch(1);
    CountDownLatch releaseAction = new CountDownLatch(1);
    AtomicBoolean actionFinished = new AtomicBoolean();
    AgentStreamingRun<AgentResponseUpdate> observed =
        run.withCompletion(
            (value, failure) -> {
              actionStarted.countDown();
              // Bounded wait used only as hang protection; the test releases this latch itself.
              awaitLatch(releaseAction);
              actionFinished.set(true);
            });

    CompletableFuture<Void> consumption =
        CompletableFuture.runAsync(() -> consume(observed.updates()));

    assertThat(actionStarted.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(observed.response().toCompletableFuture().isDone()).isFalse();

    releaseAction.countDown();
    consumption.join();
    observed.response().toCompletableFuture().join();

    assertThat(actionFinished.get()).isTrue();
  }

  @Test
  void afterRunSeamObservesTheFilledResponseSlotBeforeTheRunResponseCompletes() {
    AfterRunSeamAgent agent = new AfterRunSeamAgent("seam-agent");

    AgentResponse response = agent.run("hello").response().toCompletableFuture().join();

    assertThat(agent.afterRunInvocations).isEqualTo(1);
    assertThat(agent.afterRunSawResponse).isSameAs(response);
    assertThat(agent.contexts.get(0).response()).contains(response);
  }

  @Test
  void streamingAfterRunSeamRunsBeforeTheStreamingResponseCompletes() {
    AfterRunSeamAgent agent = new AfterRunSeamAgent("seam-agent");

    AgentStreamingRun<AgentResponseUpdate> run = agent.runStreaming("hello");
    assertThat(agent.afterRunInvocations).isZero();
    consume(run.updates());
    AgentResponse response = run.response().toCompletableFuture().join();

    assertThat(agent.afterRunInvocations).isEqualTo(1);
    assertThat(agent.afterRunSawResponse).isSameAs(response);
  }

  @Test
  void afterRunSeamIsNotInvokedWhenTheRunFails() {
    FailingAfterRunSeamAgent agent = new FailingAfterRunSeamAgent("seam-agent", false);

    assertThatThrownBy(() -> agent.run("hello").response().toCompletableFuture().join())
        .hasCauseInstanceOf(IllegalStateException.class)
        .cause()
        .hasMessage("model failure");
    assertThat(agent.afterRunInvocations).isZero();
  }

  @Test
  void afterRunSeamIsNotInvokedWhenTheRunIsCancelled() {
    CompletableFuture<AgentResponse> manualSource = new CompletableFuture<>();
    ManualCompletionAfterRunSeamAgent agent =
        new ManualCompletionAfterRunSeamAgent("seam-agent", manualSource);

    AgentRun run = agent.run("hello");
    run.cancel();

    assertThatThrownBy(() -> run.response().toCompletableFuture().join())
        .hasCauseInstanceOf(CancellationException.class);
    assertThat(agent.afterRunInvocations).isZero();
  }

  @Test
  void cancellingWhileTheAfterRunSeamIsPendingFailsTheRunResponse() {
    CompletableFuture<Void> afterRunGate = new CompletableFuture<>();
    GatedAfterRunSeamAgent agent = new GatedAfterRunSeamAgent("seam-agent", afterRunGate);

    AgentRun run = agent.run("hello");
    CompletableFuture<AgentResponse> response = run.response().toCompletableFuture();

    // The run's own response stage already completed successfully and filled the response slot,
    // so the caller-visible stage is now waiting only on the held afterRun seam.
    assertThat(agent.afterRunInvocations).isEqualTo(1);
    assertThat(agent.contexts.get(0).response()).isPresent();
    assertThat(response).isNotDone();

    run.cancel();

    assertThat(response)
        .failsWithin(Duration.ofSeconds(5))
        .withThrowableOfType(ExecutionException.class)
        .withCauseInstanceOf(CancellationException.class);

    afterRunGate.complete(null);

    // Releasing the seam afterwards must not overwrite the cancellation outcome.
    assertThat(run.response().toCompletableFuture())
        .failsWithin(Duration.ofSeconds(5))
        .withThrowableOfType(ExecutionException.class)
        .withCauseInstanceOf(CancellationException.class);
  }

  @Test
  void streamingCancellingWhileTheAfterRunSeamIsPendingFailsTheRunResponse() {
    CompletableFuture<Void> afterRunGate = new CompletableFuture<>();
    GatedAfterRunSeamAgent agent = new GatedAfterRunSeamAgent("seam-agent", afterRunGate);

    AgentStreamingRun<AgentResponseUpdate> run = agent.runStreaming("hello");
    TerminalCountingSubscriber<AgentResponseUpdate> subscriber = new TerminalCountingSubscriber<>();
    run.updates().subscribe(subscriber);
    subscriber.completion.join();
    CompletableFuture<AgentResponse> response = run.response().toCompletableFuture();

    // Model transport finished, so only the held afterRun seam keeps the response stage pending.
    assertThat(agent.afterRunInvocations).isEqualTo(1);
    assertThat(response).isNotDone();

    run.cancel();

    assertThat(response)
        .failsWithin(Duration.ofSeconds(5))
        .withThrowableOfType(ExecutionException.class)
        .withCauseInstanceOf(CancellationException.class);
    // Cancelling after the stream terminated must not emit a second terminal update signal.
    assertThat(subscriber.completions).isEqualTo(1);
    assertThat(subscriber.errors).isZero();

    afterRunGate.complete(null);

    assertThat(run.response().toCompletableFuture())
        .failsWithin(Duration.ofSeconds(5))
        .withThrowableOfType(ExecutionException.class)
        .withCauseInstanceOf(CancellationException.class);
    assertThat(subscriber.completions).isEqualTo(1);
    assertThat(subscriber.errors).isZero();
  }

  @Test
  void cancellingAfterTheRunResponseCompletedLeavesTheSuccessfulOutcome() {
    AfterRunSeamAgent agent = new AfterRunSeamAgent("seam-agent");

    AgentRun run = agent.run("hello");
    AgentResponse response = run.response().toCompletableFuture().join();

    // The derived stage drops its cancellation listener once it completes, so a late cancel is
    // inert instead of rewriting an already published run outcome.
    run.cancel();

    assertThat(run.response().toCompletableFuture().join()).isSameAs(response);
  }

  @Test
  void streamingCancellingAfterTheRunResponseCompletedLeavesTheSuccessfulOutcome() {
    AfterRunSeamAgent agent = new AfterRunSeamAgent("seam-agent");

    AgentStreamingRun<AgentResponseUpdate> run = agent.runStreaming("hello");
    consume(run.updates());
    AgentResponse response = run.response().toCompletableFuture().join();

    run.cancel();

    assertThat(run.response().toCompletableFuture().join()).isSameAs(response);
  }

  @Test
  void afterRunSeamFailurePropagatesThroughTheRunResponse() {
    FailingAfterRunSeamAgent agent = new FailingAfterRunSeamAgent("seam-agent", true);

    AgentRun run = agent.run("hello");

    assertThatThrownBy(() -> run.response().toCompletableFuture().join())
        .hasCauseInstanceOf(IllegalStateException.class)
        .cause()
        .hasMessage("after-run failure");
    // The response slot is still filled: the run itself succeeded and only the lifecycle
    // continuation failed, so the failure must be surfaced rather than swallowed.
    assertThat(agent.contexts.get(0).response()).isPresent();
  }

  @Test
  void afterRunSeamNullStageFailsTheRun() {
    NullAfterRunSeamAgent agent = new NullAfterRunSeamAgent("seam-agent");

    assertThatThrownBy(() -> agent.run("hello").response().toCompletableFuture().join())
        .hasCauseInstanceOf(NullPointerException.class)
        .cause()
        .hasMessage("afterRun stage must not be null");
  }

  @Test
  void delegatingAgentDelegatesTheAfterRunSeamToItsDelegate() {
    AfterRunSeamAgent delegate = new AfterRunSeamAgent("seam-agent");
    Agent wrapper = new DelegatingAgent(delegate) {};

    AgentResponse response = wrapper.run("hello").response().toCompletableFuture().join();

    assertThat(delegate.afterRunInvocations).isEqualTo(1);
    assertThat(delegate.afterRunSawResponse).isSameAs(response);
  }

  @Test
  void aRunBuiltDirectlyPublishesNoUpdatedSession() {
    AgentRun run = new AgentRun(sampleResponse("agent-1"));
    AgentStreamingRun<AgentResponseUpdate> streamingRun =
        AgentStreamingRun.fromUpdate(
            new AgentResponseUpdate(
                "agent-1",
                "response-1",
                "message-1",
                "agent-1",
                null,
                FinishReason.STOP,
                List.of(new Message(Role.ASSISTANT, List.of(new TextContent("ok")))),
                null,
                Map.of(),
                null));

    assertThat(run.session().toCompletableFuture().join()).isEmpty();
    assertThat(streamingRun.session().toCompletableFuture().join()).isEmpty();
  }

  @Test
  void aSessionlessRunPublishesNoUpdatedSession() {
    TestAgent agent = new TestAgent("agent-1");

    AgentRun run = agent.run("hello");
    run.response().toCompletableFuture().join();

    assertThat(run.session().toCompletableFuture().join()).isEmpty();
  }

  @Test
  void aRunWithASessionPublishesTheUpdatedSessionAfterTheAfterRunSeam() {
    SessionMutatingAfterRunSeamAgent agent = new SessionMutatingAfterRunSeamAgent("agent-1");

    AgentRun run =
        agent.run(
            new AgentRunRequest(
                Message.normalize("hello"),
                new AgentSession("session-1", null, Map.of()),
                new AgentRunOptions(),
                new CancellationSignal(),
                Map.of()));
    AgentSession updated = run.session().toCompletableFuture().join().orElseThrow();

    assertThat(agent.afterRunInvocations).isEqualTo(1);
    assertThat(updated.sessionId()).isEqualTo("session-1");
    assertThat(updated.state()).containsEntry("seam", "written");
  }

  @Test
  void aStreamingRunWithASessionPublishesTheUpdatedSessionAfterTheAfterRunSeam() {
    SessionMutatingAfterRunSeamAgent agent = new SessionMutatingAfterRunSeamAgent("agent-1");

    AgentStreamingRun<AgentResponseUpdate> run =
        agent.runStreaming(
            new AgentRunRequest(
                Message.normalize("hello"),
                new AgentSession("session-1", null, Map.of()),
                new AgentRunOptions(),
                new CancellationSignal(),
                Map.of()));
    assertThat(run.session().toCompletableFuture().isDone()).isFalse();
    consume(run.updates());
    AgentSession updated = run.session().toCompletableFuture().join().orElseThrow();

    assertThat(agent.afterRunInvocations).isEqualTo(1);
    assertThat(updated.state()).containsEntry("seam", "written");
  }

  @Test
  void afterRunSeamFailureFailsTheSessionStageToo() {
    FailingAfterRunSeamAgent agent = new FailingAfterRunSeamAgent("seam-agent", true);

    AgentRun run =
        agent.run(
            new AgentRunRequest(
                Message.normalize("hello"),
                new AgentSession("session-1", null, Map.of()),
                new AgentRunOptions(),
                new CancellationSignal(),
                Map.of()));

    assertThatThrownBy(() -> run.session().toCompletableFuture().join())
        .hasCauseInstanceOf(IllegalStateException.class)
        .cause()
        .hasMessage("after-run failure");
  }

  @Test
  void aCancelledRunFailsTheSessionStage() {
    CompletableFuture<AgentResponse> manualSource = new CompletableFuture<>();
    ManualCompletionAgent agent = new ManualCompletionAgent("agent-1", manualSource);

    AgentRun run =
        agent.run(
            new AgentRunRequest(
                Message.normalize("hello"),
                new AgentSession("session-1", null, Map.of()),
                new AgentRunOptions(),
                new CancellationSignal(),
                Map.of()));
    run.cancel();

    assertThatThrownBy(() -> run.session().toCompletableFuture().join())
        .hasCauseInstanceOf(CancellationException.class);
  }

  @Test
  void mappingUpdatesKeepsTheSessionStage() {
    SessionMutatingAfterRunSeamAgent agent = new SessionMutatingAfterRunSeamAgent("agent-1");

    AgentStreamingRun<AgentResponseUpdate> run =
        agent.runStreaming(
            new AgentRunRequest(
                Message.normalize("hello"),
                new AgentSession("session-1", null, Map.of()),
                new AgentRunOptions(),
                new CancellationSignal(),
                Map.of()));
    AgentStreamingRun<String> mapped = run.mapUpdates(update -> "mapped");
    consume(mapped.updates());

    assertThat(mapped.session().toCompletableFuture().join().orElseThrow().state())
        .containsEntry("seam", "written");
  }

  private static void awaitLatch(CountDownLatch latch) {
    try {
      assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError("unexpected interruption", interrupted);
    }
  }

  private static AgentResponse sampleResponse(String agentId) {
    return new AgentResponse(
        agentId,
        "response-1",
        "message-1",
        agentId,
        null,
        FinishReason.STOP,
        List.of(new Message(Role.ASSISTANT, List.of(new TextContent("ok")))),
        null,
        Map.of(),
        null);
  }

  @Test
  void cancellationListenerCanBeRemoved() {
    CancellationSignal signal = new CancellationSignal();
    boolean[] called = {false};
    Runnable remove = signal.onCancel(() -> called[0] = true);

    remove.run();
    signal.cancel();

    assertThat(called[0]).isFalse();
  }

  @Test
  void cancellationDrainsRemainingListenersAfterAListenerFails() {
    CancellationSignal signal = new CancellationSignal();
    boolean[] secondCalled = {false};
    signal.onCancel(
        () -> {
          throw new IllegalStateException("first failed");
        });
    signal.onCancel(() -> secondCalled[0] = true);

    assertThatThrownBy(signal::cancel)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("first failed");
    assertThat(secondCalled[0]).isTrue();
  }

  private static class TestAgent extends Agent {
    private TestAgent(String id) {
      super(id, "TestAgent", "agent test");
    }

    @Override
    protected AgentRun runInternal(AgentRunContext context, AgentRunRequest request) {
      return new AgentRun(
          CompletableFuture.completedFuture(
              new AgentResponse(
                  id(),
                  "response-1",
                  "message-1",
                  name(),
                  null,
                  io.github.hellices.agentframework.api.message.FinishReason.STOP,
                  List.of(new Message(Role.ASSISTANT, List.of(new TextContent("ok")))),
                  null,
                  Map.of(),
                  null)),
          request.cancellationSignal());
    }

    @Override
    protected AgentStreamingRun<AgentResponseUpdate> runStreamingInternal(
        AgentRunContext context, AgentRunRequest request) {
      return AgentStreamingRun.fromUpdate(
          new AgentResponseUpdate(
              id(),
              "response-1",
              "message-1",
              name(),
              null,
              io.github.hellices.agentframework.api.message.FinishReason.STOP,
              List.of(new Message(Role.ASSISTANT, List.of(new TextContent("ok")))),
              null,
              Map.of(),
              null),
          request.cancellationSignal());
    }
  }

  private static final class CompatibilityCheckingAgent extends TestAgent {
    private boolean executedRun;
    private boolean executedStreamingRun;

    private CompatibilityCheckingAgent(String id) {
      super(id);
    }

    @Override
    protected void validateSessionCompatibility(AgentSession session) {
      throw new IllegalArgumentException(
          "session " + session.sessionId() + " is not compatible with agent " + id());
    }

    @Override
    protected AgentRun runInternal(AgentRunContext context, AgentRunRequest request) {
      executedRun = true;
      return super.runInternal(context, request);
    }

    @Override
    protected AgentStreamingRun<AgentResponseUpdate> runStreamingInternal(
        AgentRunContext context, AgentRunRequest request) {
      executedStreamingRun = true;
      return super.runStreamingInternal(context, request);
    }
  }

  private static final class SessionContextCapturingAgent extends TestAgent {
    private final List<SessionContext> contexts = new ArrayList<>();

    private SessionContextCapturingAgent(String id) {
      super(id);
    }

    @Override
    protected AgentRun runInternal(AgentRunContext context, AgentRunRequest request) {
      contexts.add(context.sessionContext());
      return super.runInternal(context, request);
    }

    @Override
    protected AgentStreamingRun<AgentResponseUpdate> runStreamingInternal(
        AgentRunContext context, AgentRunRequest request) {
      contexts.add(context.sessionContext());
      return super.runStreamingInternal(context, request);
    }
  }

  private static final class PreCompletingSessionContextAgent extends TestAgent {
    private final List<SessionContext> contexts = new ArrayList<>();

    private PreCompletingSessionContextAgent(String id) {
      super(id);
    }

    @Override
    protected AgentRun runInternal(AgentRunContext context, AgentRunRequest request) {
      // Simulates a derived-stage propagation hazard: some code path (e.g. a provider or a
      // wrapping agent) fills the captured SessionContext's response slot directly, before the
      // framework's own completionAction runs. The framework must still surface the resulting
      // "already complete" failure through the exposed run's response stage rather than
      // swallowing it.
      context.sessionContext().complete(sampleResponse(id()));
      contexts.add(context.sessionContext());
      return super.runInternal(context, request);
    }
  }

  private static final class FailingAgent extends TestAgent {
    private final List<SessionContext> contexts = new ArrayList<>();

    private FailingAgent(String id) {
      super(id);
    }

    @Override
    protected AgentRun runInternal(AgentRunContext context, AgentRunRequest request) {
      contexts.add(context.sessionContext());
      CompletableFuture<AgentResponse> failed = new CompletableFuture<>();
      failed.completeExceptionally(new IllegalStateException("model failure"));
      return new AgentRun(failed, request.cancellationSignal());
    }
  }

  private static final class ManualCompletionAgent extends TestAgent {
    private final List<SessionContext> contexts = new ArrayList<>();
    private final CompletableFuture<AgentResponse> manualSource;

    private ManualCompletionAgent(String id, CompletableFuture<AgentResponse> manualSource) {
      super(id);
      this.manualSource = manualSource;
    }

    @Override
    protected AgentRun runInternal(AgentRunContext context, AgentRunRequest request) {
      contexts.add(context.sessionContext());
      return new AgentRun(manualSource, request.cancellationSignal());
    }
  }

  private static final class ContextCapturingAgent extends TestAgent {
    private AgentRunContext lastContext;

    private ContextCapturingAgent(String id) {
      super(id);
    }

    @Override
    protected AgentRun runInternal(AgentRunContext context, AgentRunRequest request) {
      lastContext = context;
      return super.runInternal(context, request);
    }
  }

  private static class AfterRunSeamAgent extends TestAgent {
    final List<SessionContext> contexts = new ArrayList<>();
    int afterRunInvocations;
    AgentResponse afterRunSawResponse;

    private AfterRunSeamAgent(String id) {
      super(id);
    }

    @Override
    protected AgentRun runInternal(AgentRunContext context, AgentRunRequest request) {
      contexts.add(context.sessionContext());
      return super.runInternal(context, request);
    }

    @Override
    protected AgentStreamingRun<AgentResponseUpdate> runStreamingInternal(
        AgentRunContext context, AgentRunRequest request) {
      contexts.add(context.sessionContext());
      return super.runStreamingInternal(context, request);
    }

    @Override
    protected CompletionStage<Void> afterRun(SessionContext sessionContext) {
      afterRunInvocations++;
      afterRunSawResponse = sessionContext.response().orElse(null);
      return CompletableFuture.completedFuture(null);
    }
  }

  private static final class FailingAfterRunSeamAgent extends AfterRunSeamAgent {
    private final boolean runSucceeds;

    private FailingAfterRunSeamAgent(String id, boolean runSucceeds) {
      super(id);
      this.runSucceeds = runSucceeds;
    }

    @Override
    protected AgentRun runInternal(AgentRunContext context, AgentRunRequest request) {
      if (runSucceeds) {
        return super.runInternal(context, request);
      }
      contexts.add(context.sessionContext());
      CompletableFuture<AgentResponse> failed = new CompletableFuture<>();
      failed.completeExceptionally(new IllegalStateException("model failure"));
      return new AgentRun(failed, request.cancellationSignal());
    }

    @Override
    protected CompletionStage<Void> afterRun(SessionContext sessionContext) {
      super.afterRun(sessionContext);
      CompletableFuture<Void> failed = new CompletableFuture<>();
      failed.completeExceptionally(new IllegalStateException("after-run failure"));
      return failed;
    }
  }

  private static final class SessionMutatingAfterRunSeamAgent extends AfterRunSeamAgent {
    private SessionMutatingAfterRunSeamAgent(String id) {
      super(id);
    }

    @Override
    protected CompletionStage<Void> afterRun(SessionContext sessionContext) {
      sessionContext.providerState("seam").set("written");
      return super.afterRun(sessionContext);
    }
  }

  private static final class NullAfterRunSeamAgent extends AfterRunSeamAgent {
    private NullAfterRunSeamAgent(String id) {
      super(id);
    }

    @Override
    protected CompletionStage<Void> afterRun(SessionContext sessionContext) {
      super.afterRun(sessionContext);
      return null;
    }
  }

  private static final class ManualCompletionAfterRunSeamAgent extends AfterRunSeamAgent {
    private final CompletableFuture<AgentResponse> manualSource;

    private ManualCompletionAfterRunSeamAgent(
        String id, CompletableFuture<AgentResponse> manualSource) {
      super(id);
      this.manualSource = manualSource;
    }

    @Override
    protected AgentRun runInternal(AgentRunContext context, AgentRunRequest request) {
      contexts.add(context.sessionContext());
      return new AgentRun(manualSource, request.cancellationSignal());
    }
  }

  /**
   * Holds the {@code afterRun} seam open on a caller-completed gate, so a test can observe the
   * window in which the run's own response stage already succeeded while the caller-visible
   * response stage is still pending.
   */
  private static final class GatedAfterRunSeamAgent extends AfterRunSeamAgent {
    private final CompletableFuture<Void> gate;

    private GatedAfterRunSeamAgent(String id, CompletableFuture<Void> gate) {
      super(id);
      this.gate = gate;
    }

    @Override
    protected CompletionStage<Void> afterRun(SessionContext sessionContext) {
      super.afterRun(sessionContext);
      return gate;
    }
  }

  /** Counts terminal update signals so a test can prove none is emitted twice. */
  private static final class TerminalCountingSubscriber<T> implements Flow.Subscriber<T> {
    private final CompletableFuture<Void> completion = new CompletableFuture<>();
    private int completions;
    private int errors;

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
      subscription.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(T item) {}

    @Override
    public void onError(Throwable throwable) {
      errors++;
      completion.completeExceptionally(throwable);
    }

    @Override
    public void onComplete() {
      completions++;
      completion.complete(null);
    }
  }

  private static <T> void consume(Flow.Publisher<T> publisher) {
    CompletableFuture<Void> completion = new CompletableFuture<>();
    publisher.subscribe(
        new Flow.Subscriber<>() {
          @Override
          public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
          }

          @Override
          public void onNext(T item) {}

          @Override
          public void onError(Throwable throwable) {
            completion.completeExceptionally(throwable);
          }

          @Override
          public void onComplete() {
            completion.complete(null);
          }
        });
    completion.join();
  }
}
