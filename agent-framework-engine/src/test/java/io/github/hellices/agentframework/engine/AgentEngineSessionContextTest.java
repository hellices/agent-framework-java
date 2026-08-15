package io.github.hellices.agentframework.engine;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hellices.agentframework.api.agent.AgentResponse;
import io.github.hellices.agentframework.api.agent.AgentResponseUpdate;
import io.github.hellices.agentframework.api.agent.AgentRun;
import io.github.hellices.agentframework.api.agent.AgentRunOptions;
import io.github.hellices.agentframework.api.agent.AgentRunRequest;
import io.github.hellices.agentframework.api.agent.AgentSession;
import io.github.hellices.agentframework.api.agent.AgentStreamingRun;
import io.github.hellices.agentframework.api.agent.CancellationSignal;
import io.github.hellices.agentframework.api.message.FinishReason;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.MessageAttribution;
import io.github.hellices.agentframework.api.message.Role;
import io.github.hellices.agentframework.api.message.TextContent;
import io.github.hellices.agentframework.api.session.SessionContext;
import io.github.hellices.agentframework.spi.model.ModelClient;
import io.github.hellices.agentframework.spi.model.ModelRequest;
import io.github.hellices.agentframework.spi.model.ModelResponse;
import io.github.hellices.agentframework.spi.model.ModelResponseUpdate;
import io.github.hellices.agentframework.spi.model.StreamingModelClient;
import io.github.hellices.agentframework.spi.session.ContextProvider;
import io.github.hellices.agentframework.spi.session.ProviderSessionState;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AgentEngineSessionContextTest {

  @Test
  void builderRejectsNullContextProviderEntries() {
    assertThatThrownBy(
            () ->
                AgentEngine.builder()
                    .modelClient(fixedClient("unused"))
                    .contextProviders(new RecordingProvider("memory", new ArrayList<>()), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("contextProviders must not contain null entries");
  }

  @Test
  void builderRejectsABlankContextProviderSourceId() {
    assertThatThrownBy(
            () ->
                AgentEngine.builder()
                    .modelClient(fixedClient("unused"))
                    .contextProviders(new RecordingProvider("  ", new ArrayList<>()))
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("context provider sourceId must not be blank");
  }

  @Test
  void builderRejectsDuplicateContextProviderSourceIds() {
    List<String> log = new ArrayList<>();

    assertThatThrownBy(
            () ->
                AgentEngine.builder()
                    .modelClient(fixedClient("unused"))
                    .contextProviders(
                        new RecordingProvider("memory", log), new RecordingProvider("memory", log))
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("duplicate context provider sourceId: memory");
  }

  @Test
  void ordinaryRunComposesBeforeRunForwardAndAfterRunInReverseAroundTheModelCall() {
    List<String> log = new ArrayList<>();
    ModelClient client =
        request -> {
          log.add("model");
          return completedFuture(response("hello"));
        };
    AgentEngine engine =
        AgentEngine.builder()
            .modelClient(client)
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
          return completedFuture(response("hello"));
        };
    List<String> log = new ArrayList<>();
    AgentEngine engine =
        AgentEngine.builder()
            .modelClient(client)
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
            new MessageAttribution("Context", "first", null),
            new MessageAttribution("Context", "second", null),
            null);
  }

  @Test
  void afterRunObservesTheFinalResponseThroughTheSharedSessionContext() {
    List<String> log = new ArrayList<>();
    RecordingProvider provider = new RecordingProvider("memory", log);
    AgentEngine engine =
        AgentEngine.builder().modelClient(fixedClient("hello")).contextProviders(provider).build();

    AgentResponse response = engine.run("hi").response().toCompletableFuture().join();

    assertThat(provider.responseDuringBeforeRun).isEmpty();
    assertThat(provider.responseDuringAfterRun).containsSame(response);
    assertThat(provider.afterRunContexts).containsExactly(provider.beforeRunContexts.get(0));
  }

  @Test
  void oneProviderInstanceKeepsTwoSessionsStateSlotsSeparate() {
    List<String> log = new ArrayList<>();
    RecordingProvider provider = new RecordingProvider("memory", log);
    AgentEngine engine =
        AgentEngine.builder().modelClient(fixedClient("hello")).contextProviders(provider).build();

    runWithSession(engine, new AgentSession("session-1", null, Map.of()));
    runWithSession(engine, new AgentSession("session-2", null, Map.of("memory", 7)));

    assertThat(provider.observedStateValues).containsExactly(0, 7);
    assertThat(provider.updatedSessions)
        .extracting(session -> session.state().get("memory"))
        .containsExactly(1, 8);
  }

  @Test
  void distinctSourceIdsDoNotShareTheSameStateSlot() {
    List<String> log = new ArrayList<>();
    RecordingProvider first = new RecordingProvider("first", log);
    RecordingProvider second = new RecordingProvider("second", log);
    AgentEngine engine =
        AgentEngine.builder()
            .modelClient(fixedClient("hello"))
            .contextProviders(first, second)
            .build();

    runWithSession(engine, new AgentSession("session-1", null, Map.of("first", 3)));

    assertThat(first.observedStateValues).containsExactly(3);
    assertThat(second.observedStateValues).containsExactly(0);
    assertThat(first.updatedSessions.get(0).state())
        .containsEntry("first", 4)
        .containsEntry("second", 1);
  }

  @Test
  void streamingRunComposesBeforeRunForwardAndAfterRunInReverseAroundTheModelCall() {
    List<String> log = new ArrayList<>();
    AgentEngine engine =
        AgentEngine.builder()
            .modelClient(new StreamingFakeClient(log))
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
    AgentEngine engine =
        AgentEngine.builder()
            .modelClient(client)
            .contextProviders(new RecordingProvider("first", log))
            .build();

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
          return completedFuture(response("hello"));
        };
    RecordingProvider first = new RecordingProvider("first", log);
    AgentEngine engine =
        AgentEngine.builder()
            .modelClient(client)
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
    AgentEngine engine =
        AgentEngine.builder()
            .modelClient(fixedClient("hello"))
            .contextProviders(new NullStageProvider("memory"))
            .build();

    assertThatThrownBy(() -> engine.run("hi").response().toCompletableFuture().join())
        .hasRootCauseInstanceOf(NullPointerException.class)
        .hasRootCauseMessage("context provider before-run stage must not be null");
    assertThat(log).isEmpty();
  }

  @Test
  void afterRunFailureFailsTheRunAndStopsEarlierProviderHooks() {
    List<String> log = new ArrayList<>();
    RecordingProvider first = new RecordingProvider("first", log);
    AgentEngine engine =
        AgentEngine.builder()
            .modelClient(fixedClient("hello"))
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
          return failed;
        };
    AgentEngine engine =
        AgentEngine.builder().modelClient(client).contextProviders(provider).build();

    assertThatThrownBy(() -> engine.run("hi").response().toCompletableFuture().join())
        .hasRootCauseInstanceOf(IllegalStateException.class)
        .hasRootCauseMessage("model failure");
    assertThat(log).containsExactly("before:memory");
    assertThat(provider.responseDuringBeforeRun).isEmpty();
    assertThat(provider.afterRunContexts).isEmpty();
  }

  @Test
  void cancellationBeforeTheRunSkipsEveryProviderHook() {
    List<String> log = new ArrayList<>();
    RecordingProvider provider = new RecordingProvider("memory", log);
    CancellationSignal signal = new CancellationSignal();
    signal.cancel();
    AgentEngine engine =
        AgentEngine.builder().modelClient(fixedClient("hello")).contextProviders(provider).build();
    AgentRunRequest request =
        new AgentRunRequest(Message.normalize("hi"), null, new AgentRunOptions(), signal, Map.of());

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
          return completedFuture(response("hello"));
        };
    GatedBeforeRunProvider gated = new GatedBeforeRunProvider("slow", log);
    AgentEngine engine = AgentEngine.builder().modelClient(client).contextProviders(gated).build();

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
    AgentEngine engine =
        AgentEngine.builder()
            .modelClient(fixedClient("hello"))
            .contextProviders(first, second)
            .build();

    runWithSession(engine, new AgentSession("session-1", null, Map.of("first", 3, "second", 5)));

    assertThat(first.observedStateSourceIds).containsExactly("first", "first");
    assertThat(second.observedStateSourceIds).containsExactly("second", "second");
    assertThat(first.observedStateValues).containsExactly(3);
    assertThat(second.observedStateValues).containsExactly(5);
  }

  @Test
  void aProviderUsingOnlyItsBoundViewCarriesStateIntoTheNextRunOfTheSameSession() {
    List<String> log = new ArrayList<>();
    RecordingProvider provider = new RecordingProvider("memory", log);
    AgentEngine engine =
        AgentEngine.builder().modelClient(fixedClient("hello")).contextProviders(provider).build();

    runWithSession(engine, new AgentSession("session-1", null, Map.of()));
    runWithSession(engine, provider.updatedSessions.get(0));

    assertThat(provider.observedStateValues).containsExactly(0, 1);
    assertThat(provider.updatedSessions)
        .extracting(session -> session.state().get("memory"))
        .containsExactly(1, 2);
  }

  @Test
  void streamingBeforeRunFailureFailsTheStreamAndTheResponseWithoutCallingTheModel() {
    List<String> log = new ArrayList<>();
    StreamingFakeClient client = new StreamingFakeClient(log);
    RecordingProvider first = new RecordingProvider("first", log);
    AgentEngine engine =
        AgentEngine.builder()
            .modelClient(client)
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
    AgentEngine engine =
        AgentEngine.builder()
            .modelClient(new StreamingFakeClient(log))
            .contextProviders(provider)
            .build();

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
    AgentEngine engine =
        AgentEngine.builder()
            .modelClient(new StreamingFakeClient(log))
            .contextProviders(provider)
            .build();

    runStreamingWithSession(engine, new AgentSession("session-1", null, Map.of()));
    runStreamingWithSession(engine, new AgentSession("session-2", null, Map.of("memory", 7)));

    assertThat(provider.observedStateValues).containsExactly(0, 7);
    assertThat(provider.updatedSessions)
        .extracting(session -> session.state().get("memory"))
        .containsExactly(1, 8);
  }

  @Test
  void cancellingAStreamingRunWhileABeforeRunHookIsPendingTerminatesTheUpdateSubscriber() {
    List<String> log = new ArrayList<>();
    StreamingFakeClient client = new StreamingFakeClient(log);
    GatedBeforeRunProvider gated = new GatedBeforeRunProvider("slow", log);
    AgentEngine engine = AgentEngine.builder().modelClient(client).contextProviders(gated).build();

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

  private static void runStreamingWithSession(AgentEngine engine, AgentSession session) {
    AgentStreamingRun<AgentResponseUpdate> run =
        engine.runStreaming(
            new AgentRunRequest(
                Message.normalize("hi"),
                session,
                new AgentRunOptions(),
                new CancellationSignal(),
                Map.of()));
    consume(run.updates());
    run.response().toCompletableFuture().join();
  }

  private static void runWithSession(AgentEngine engine, AgentSession session) {
    engine
        .run(
            new AgentRunRequest(
                Message.normalize("hi"),
                session,
                new AgentRunOptions(),
                new CancellationSignal(),
                Map.of()))
        .response()
        .toCompletableFuture()
        .join();
  }

  private static ModelClient fixedClient(String text) {
    return request -> completedFuture(response(text));
  }

  private static ModelResponse response(String text) {
    return new ModelResponse(
        List.of(new Message(Role.ASSISTANT, List.of(new TextContent(text)))),
        null,
        FinishReason.STOP,
        Map.of(),
        null);
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

  private static class RecordingProvider implements ContextProvider {
    private final String sourceId;
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
      this.log = log;
    }

    @Override
    public String sourceId() {
      return sourceId;
    }

    @Override
    public CompletionStage<Void> beforeRun(SessionContext context, ProviderSessionState state) {
      log.add("before:" + sourceId);
      beforeRunContexts.add(context);
      responseDuringBeforeRun = context.response();
      observedStateSourceIds.add(state.sourceId());
      int seen = state.value(Integer.class).orElse(0);
      observedStateValues.add(seen);
      state.set(seen + 1);
      context.addContextMessages(
          state.sourceId(),
          List.of(new Message(Role.USER, List.of(new TextContent("context:" + sourceId)))));
      return completedFuture(null);
    }

    @Override
    public CompletionStage<Void> afterRun(SessionContext context, ProviderSessionState state) {
      log.add("after:" + sourceId);
      afterRunContexts.add(context);
      responseDuringAfterRun = context.response();
      observedStateSourceIds.add(state.sourceId());
      context.updatedSession().ifPresent(updatedSessions::add);
      return completedFuture(null);
    }
  }

  /**
   * A provider whose {@code beforeRun} stays pending until the test releases it, so a run can be
   * cancelled while the framework is inside the asynchronous window Task 2 opened between the hooks
   * and the model call.
   */
  private static final class GatedBeforeRunProvider extends RecordingProvider {
    private final CompletableFuture<Void> gate = new CompletableFuture<>();

    private GatedBeforeRunProvider(String sourceId, List<String> log) {
      super(sourceId, log);
    }

    @Override
    public CompletionStage<Void> beforeRun(SessionContext context, ProviderSessionState state) {
      super.beforeRun(context, state);
      return gate.minimalCompletionStage();
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
    public CompletionStage<Void> beforeRun(SessionContext context, ProviderSessionState state) {
      super.beforeRun(context, state);
      CompletableFuture<Void> failed = new CompletableFuture<>();
      failed.completeExceptionally(new IllegalStateException("before-run failure"));
      return failed;
    }
  }

  private static final class FailingAfterRunProvider extends RecordingProvider {
    private FailingAfterRunProvider(String sourceId, List<String> log) {
      super(sourceId, log);
    }

    @Override
    public CompletionStage<Void> afterRun(SessionContext context, ProviderSessionState state) {
      super.afterRun(context, state);
      CompletableFuture<Void> failed = new CompletableFuture<>();
      failed.completeExceptionally(new IllegalStateException("after-run failure"));
      return failed;
    }
  }

  private static final class NullStageProvider implements ContextProvider {
    private final String sourceId;

    private NullStageProvider(String sourceId) {
      this.sourceId = sourceId;
    }

    @Override
    public String sourceId() {
      return sourceId;
    }

    @Override
    public CompletionStage<Void> beforeRun(SessionContext context, ProviderSessionState state) {
      return null;
    }

    @Override
    public CompletionStage<Void> afterRun(SessionContext context, ProviderSessionState state) {
      return completedFuture(null);
    }
  }

  private static final class StreamingFakeClient implements StreamingModelClient {
    private final List<String> log;
    private final AtomicReference<ModelRequest> capturedRequest = new AtomicReference<>();

    private StreamingFakeClient(List<String> log) {
      this.log = log;
    }

    @Override
    public CompletionStage<ModelResponse> run(ModelRequest request) {
      return completedFuture(response("hello"));
    }

    @Override
    public Flow.Publisher<ModelResponseUpdate> runStreaming(ModelRequest request) {
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
                      new ModelResponseUpdate(
                          List.of(new Message(Role.ASSISTANT, List.of(new TextContent("hello")))),
                          null,
                          FinishReason.STOP,
                          Map.of(),
                          null));
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
