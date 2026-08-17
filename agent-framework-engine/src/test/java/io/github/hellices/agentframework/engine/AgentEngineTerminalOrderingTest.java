package io.github.hellices.agentframework.engine;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hellices.agentframework.api.agent.Agent;
import io.github.hellices.agentframework.api.agent.AgentResponse;
import io.github.hellices.agentframework.api.agent.AgentResponseUpdate;
import io.github.hellices.agentframework.api.agent.AgentRun;
import io.github.hellices.agentframework.api.agent.AgentRunOptions;
import io.github.hellices.agentframework.api.agent.AgentRunRequest;
import io.github.hellices.agentframework.api.agent.AgentSession;
import io.github.hellices.agentframework.api.agent.AgentStreamingRun;
import io.github.hellices.agentframework.api.agent.CancellationSignal;
import io.github.hellices.agentframework.api.context.ContextAttributes;
import io.github.hellices.agentframework.api.message.FinishReason;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.Role;
import io.github.hellices.agentframework.api.message.TextContent;
import io.github.hellices.agentframework.api.session.SessionContext;
import io.github.hellices.agentframework.api.session.SessionSnapshot;
import io.github.hellices.agentframework.spi.model.ModelClient;
import io.github.hellices.agentframework.spi.model.ModelResponse;
import io.github.hellices.agentframework.spi.session.ContextProvider;
import io.github.hellices.agentframework.spi.session.ProviderSessionState;
import io.github.hellices.agentframework.spi.session.SessionStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Task 5 terminal lifecycle contract for engine (bound-agent) runs. Uses controllable model,
 * context-completion and session-save stages to pin the terminal order the pipeline owns:
 *
 * <pre>final update -> response reconstructed -> session context completed exactly once
 *   -> context providers complete in reverse -> session save -> response/session stages
 *   -> update publisher onComplete -> RunState TERMINATED.</pre>
 */
class AgentEngineTerminalOrderingTest {

  @Test
  void streamingUpdatePublisherDoesNotCompleteBeforeSessionSave() {
    List<String> log = new ArrayList<>();
    GatedSessionStore store = new GatedSessionStore(log);
    RecordingProvider provider = new RecordingProvider("memory", log, completedFuture(null));
    Agent agent = agentWithStore(store, fixedClient("hello"), provider);

    AgentStreamingRun<AgentResponseUpdate> run = runStreamingWithSession(agent);
    RecordingSubscriber<AgentResponseUpdate> subscriber = subscribe(run.updates());

    assertThat(subscriber.values).extracting(AgentResponseUpdate::text).containsExactly("hello");
    // Model transport is done and the save was invoked, but the save stage is still pending, so
    // the update publisher must not have completed yet.
    assertThat(store.saveCalled).isTrue();
    assertThat(subscriber.completion).isNotDone();
    assertThat(run.response().toCompletableFuture()).isNotDone();

    store.releaseSave();

    subscriber.completion.join();
    assertThat(subscriber.terminalFailure.get()).isNull();
    assertThat(run.response().toCompletableFuture().join().text()).isEqualTo("hello");
    assertThat(log).containsExactly("before:memory", "after:memory", "save");
  }

  @Test
  void streamingSessionSaveFailureProducesOnErrorAndNoOnComplete() {
    List<String> log = new ArrayList<>();
    IllegalStateException saveFailure = new IllegalStateException("save failure");
    GatedSessionStore store = new GatedSessionStore();
    RecordingProvider provider = new RecordingProvider("memory", log, completedFuture(null));
    Agent agent = agentWithStore(store, fixedClient("hello"), provider);

    AgentStreamingRun<AgentResponseUpdate> run = runStreamingWithSession(agent);
    RecordingSubscriber<AgentResponseUpdate> subscriber = subscribe(run.updates());

    store.failSave(saveFailure);

    assertThatThrownBy(subscriber.completion::join).hasRootCause(saveFailure);
    assertThat(subscriber.completedNormally).isFalse();
    assertThat(subscriber.terminalFailure.get()).isSameAs(saveFailure);
    assertThat(causeOf(run.response())).isSameAs(saveFailure);
    assertThat(causeOf(run.session())).isSameAs(saveFailure);
  }

  @Test
  void streamingProviderCompletionFailureProducesOnErrorBeforeAnyOnComplete() {
    List<String> log = new ArrayList<>();
    IllegalStateException afterRunFailure = new IllegalStateException("after-run failure");
    GatedSessionStore store = new GatedSessionStore();
    store.releaseSave();
    RecordingProvider provider = new RecordingProvider("memory", log, failedStage(afterRunFailure));
    Agent agent = agentWithStore(store, fixedClient("hello"), provider);

    AgentStreamingRun<AgentResponseUpdate> run = runStreamingWithSession(agent);
    RecordingSubscriber<AgentResponseUpdate> subscriber = subscribe(run.updates());

    assertThatThrownBy(subscriber.completion::join).hasRootCause(afterRunFailure);
    assertThat(subscriber.completedNormally).isFalse();
    assertThat(subscriber.terminalFailure.get()).isSameAs(afterRunFailure);
    // A provider-completion failure means the session is never written.
    assertThat(store.saveCalled).isFalse();
    assertThat(causeOf(run.response())).isSameAs(afterRunFailure);
    assertThat(causeOf(run.session())).isSameAs(afterRunFailure);
  }

  @Test
  void ordinaryAndStreamingShareTheExactSaveFailureCauseOnResponseAndSession() {
    IllegalStateException saveFailure = new IllegalStateException("shared save failure");

    GatedSessionStore ordinaryStore = new GatedSessionStore();
    ordinaryStore.failSave(saveFailure);
    Agent ordinaryAgent =
        agentWithStore(
            ordinaryStore,
            fixedClient("hello"),
            new RecordingProvider("memory", new ArrayList<>(), completedFuture(null)));
    AgentRun ordinaryRun = runWithSession(ordinaryAgent);
    assertThat(causeOf(ordinaryRun.response())).isSameAs(saveFailure);
    assertThat(causeOf(ordinaryRun.session())).isSameAs(saveFailure);

    GatedSessionStore streamingStore = new GatedSessionStore();
    streamingStore.failSave(saveFailure);
    Agent streamingAgent =
        agentWithStore(
            streamingStore,
            fixedClient("hello"),
            new RecordingProvider("memory", new ArrayList<>(), completedFuture(null)));
    AgentStreamingRun<AgentResponseUpdate> streamingRun = runStreamingWithSession(streamingAgent);
    consumeQuietly(streamingRun.updates());
    assertThat(causeOf(streamingRun.response())).isSameAs(saveFailure);
    assertThat(causeOf(streamingRun.session())).isSameAs(saveFailure);
  }

  @Test
  void sessionContextResponseIsCompletedExactlyOnceBeforeProvidersRunInReverse() {
    List<String> log = new ArrayList<>();
    GatedSessionStore store = new GatedSessionStore(log);
    store.releaseSave();
    RecordingProvider first = new RecordingProvider("first", log, completedFuture(null));
    RecordingProvider second = new RecordingProvider("second", log, completedFuture(null));
    Agent agent = agentWithStore(store, fixedClient("hello"), first, second);

    AgentResponse response = runWithSession(agent).response().toCompletableFuture().join();

    // The response slot was filled before the providers' afterRun observed it, and the providers
    // ran in reverse declaration order, then the save.
    assertThat(first.responseDuringAfterRun).containsSame(response);
    assertThat(second.responseDuringAfterRun).containsSame(response);
    assertThat(log)
        .containsExactly("before:first", "before:second", "after:second", "after:first", "save");
  }

  @Test
  void anEmptyModelStreamFailsBothOrdinaryAndStreamingIdentically() {
    Agent ordinaryAgent = agentWithoutStore(emptyClient());
    assertThatThrownBy(() -> ordinaryAgent.run("hi").response().toCompletableFuture().join())
        .hasRootCauseInstanceOf(IllegalStateException.class)
        .hasRootCauseMessage("model stream completed without any update");

    Agent streamingAgent = agentWithoutStore(emptyClient());
    AgentStreamingRun<AgentResponseUpdate> run = streamingAgent.runStreaming("hi");
    RecordingSubscriber<AgentResponseUpdate> subscriber = subscribe(run.updates());

    assertThat(subscriber.completedNormally).isFalse();
    assertThat(subscriber.terminalFailure.get())
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("model stream completed without any update");
    assertThatThrownBy(() -> run.response().toCompletableFuture().join())
        .hasRootCauseInstanceOf(IllegalStateException.class)
        .hasRootCauseMessage("model stream completed without any update");
  }

  private static Agent agentWithStore(
      SessionStore store, ModelClient client, ContextProvider... providers) {
    return AgentEngine.builder()
        .sessionStore(store)
        .build()
        .factory()
        .builderWithClient(client)
        .contextProviders(providers)
        .build();
  }

  private static Agent agentWithoutStore(ModelClient client) {
    return AgentEngine.builder().build().factory().builderWithClient(client).build();
  }

  private static AgentRun runWithSession(Agent agent) {
    return agent.run(sessionRequest());
  }

  private static AgentStreamingRun<AgentResponseUpdate> runStreamingWithSession(Agent agent) {
    return agent.runStreaming(sessionRequest());
  }

  private static AgentRunRequest sessionRequest() {
    return AgentRunRequest.builder()
        .messages(Message.normalize("hi"))
        .session(AgentSession.builder().sessionId("session-1").build())
        .options(new AgentRunOptions())
        .cancellationSignal(new CancellationSignal())
        .attributes(ContextAttributes.empty())
        .build();
  }

  private static ModelClient fixedClient(String text) {
    return request ->
        EngineModels.of(
            ModelResponse.builder()
                .messages(List.of(new Message(Role.ASSISTANT, List.of(new TextContent(text)))))
                .finishReason(FinishReason.STOP)
                .build());
  }

  private static ModelClient emptyClient() {
    return request ->
        subscriber ->
            subscriber.onSubscribe(
                new Flow.Subscription() {
                  @Override
                  public void request(long n) {
                    subscriber.onComplete();
                  }

                  @Override
                  public void cancel() {}
                });
  }

  private static CompletionStage<Void> failedStage(Throwable failure) {
    CompletableFuture<Void> failed = new CompletableFuture<>();
    failed.completeExceptionally(failure);
    return failed;
  }

  private static Throwable causeOf(CompletionStage<?> stage) {
    try {
      stage.toCompletableFuture().join();
      return null;
    } catch (CompletionException failure) {
      return failure.getCause();
    }
  }

  private static <T> RecordingSubscriber<T> subscribe(Flow.Publisher<T> publisher) {
    RecordingSubscriber<T> subscriber = new RecordingSubscriber<>();
    publisher.subscribe(subscriber);
    return subscriber;
  }

  private static void consumeQuietly(Flow.Publisher<?> publisher) {
    RecordingSubscriber<Object> subscriber = new RecordingSubscriber<>();
    publisher.subscribe(subscriber);
    try {
      subscriber.completion.join();
    } catch (RuntimeException ignored) {
      // The test asserts the outcome through the response and session stages instead.
    }
  }

  private static final class RecordingSubscriber<T> implements Flow.Subscriber<T> {
    private final List<T> values = new ArrayList<>();
    private final CompletableFuture<Void> completion = new CompletableFuture<>();
    private final AtomicReference<Throwable> terminalFailure = new AtomicReference<>();
    private volatile boolean completedNormally;

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
      completedNormally = true;
      completion.complete(null);
    }
  }

  private static final class RecordingProvider implements ContextProvider {
    private final String sourceId;
    private final List<String> log;
    private final CompletionStage<Void> afterRunStage;
    private Optional<AgentResponse> responseDuringAfterRun = Optional.empty();

    private RecordingProvider(
        String sourceId, List<String> log, CompletionStage<Void> afterRunStage) {
      this.sourceId = sourceId;
      this.log = log;
      this.afterRunStage = afterRunStage;
    }

    @Override
    public String sourceId() {
      return sourceId;
    }

    @Override
    public CompletionStage<Void> beforeRun(SessionContext context, ProviderSessionState state) {
      log.add("before:" + sourceId);
      return completedFuture(null);
    }

    @Override
    public CompletionStage<Void> afterRun(SessionContext context, ProviderSessionState state) {
      log.add("after:" + sourceId);
      responseDuringAfterRun = context.response();
      return afterRunStage;
    }
  }

  private static final class GatedSessionStore implements SessionStore {
    private final CompletableFuture<Void> saveGate = new CompletableFuture<>();
    private final List<String> log;
    private volatile boolean saveCalled;

    private GatedSessionStore() {
      this(new ArrayList<>());
    }

    private GatedSessionStore(List<String> log) {
      this.log = log;
    }

    private void releaseSave() {
      saveGate.complete(null);
    }

    private void failSave(Throwable failure) {
      saveGate.completeExceptionally(failure);
    }

    @Override
    public CompletionStage<Optional<SessionSnapshot>> load(String sessionId) {
      return completedFuture(Optional.empty());
    }

    @Override
    public CompletionStage<Void> save(SessionSnapshot snapshot) {
      saveCalled = true;
      log.add("save");
      return saveGate.minimalCompletionStage();
    }

    @Override
    public CompletionStage<Void> delete(String sessionId) {
      return completedFuture(null);
    }
  }
}
