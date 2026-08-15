package io.github.hellices.agentframework.engine.internal.session;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hellices.agentframework.api.agent.AgentResponseUpdate;
import io.github.hellices.agentframework.api.agent.AgentRun;
import io.github.hellices.agentframework.api.agent.AgentRunOptions;
import io.github.hellices.agentframework.api.agent.AgentRunRequest;
import io.github.hellices.agentframework.api.agent.AgentSession;
import io.github.hellices.agentframework.api.agent.AgentStreamingRun;
import io.github.hellices.agentframework.api.agent.CancellationSignal;
import io.github.hellices.agentframework.api.message.FinishReason;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.Role;
import io.github.hellices.agentframework.api.message.TextContent;
import io.github.hellices.agentframework.api.session.MessageHistory;
import io.github.hellices.agentframework.api.session.SessionContext;
import io.github.hellices.agentframework.api.session.SessionSnapshot;
import io.github.hellices.agentframework.api.session.SessionStateEntry;
import io.github.hellices.agentframework.engine.AgentEngine;
import io.github.hellices.agentframework.engine.session.InMemoryHistoryProvider;
import io.github.hellices.agentframework.spi.model.ModelClient;
import io.github.hellices.agentframework.spi.model.ModelRequest;
import io.github.hellices.agentframework.spi.model.ModelResponse;
import io.github.hellices.agentframework.spi.model.ModelResponseUpdate;
import io.github.hellices.agentframework.spi.model.StreamingModelClient;
import io.github.hellices.agentframework.spi.session.ContextProvider;
import io.github.hellices.agentframework.spi.session.HistoryPolicy;
import io.github.hellices.agentframework.spi.session.ProviderSessionState;
import io.github.hellices.agentframework.spi.session.SessionStateDecodingException;
import io.github.hellices.agentframework.spi.session.SessionStore;
import io.github.hellices.agentframework.spi.session.StateCodec;
import io.github.hellices.agentframework.spi.session.StateCodecRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SessionCoordinatorTest {

  private static final Instant SEEDED_CREATED_AT = Instant.parse("2026-01-01T00:00:00Z");

  private final StateCodecRegistry registry = StateCodecRegistry.builder().build();

  // --- SES-014 default in-memory history injection conditions -----------------------------------

  @Test
  void aRunWithASessionAndNoHistoryProviderResolvesTheDefaultInMemoryHistory() {
    ProbeProvider probe = new ProbeProvider("probe");
    AgentEngine engine =
        AgentEngine.builder().modelClient(fixedClient("hello")).contextProviders(probe).build();

    run(engine, new AgentSession("session-1", null, Map.of()), "hi");

    assertThat(probe.storedHistory(InMemoryHistoryProvider.DEFAULT_SOURCE_ID))
        .extracting(Message::text)
        .containsExactly("hi", "hello");
  }

  @Test
  void aSessionlessRunResolvesNoDefaultInMemoryHistory() {
    ProbeProvider probe = new ProbeProvider("probe");
    AgentEngine engine =
        AgentEngine.builder().modelClient(fixedClient("hello")).contextProviders(probe).build();

    engine.run("hi").response().toCompletableFuture().join();

    assertThat(probe.observedState(InMemoryHistoryProvider.DEFAULT_SOURCE_ID)).isEmpty();
  }

  @Test
  void aServiceManagedConversationResolvesNoDefaultInMemoryHistory() {
    ProbeProvider probe = new ProbeProvider("probe");
    AgentEngine engine =
        AgentEngine.builder().modelClient(fixedClient("hello")).contextProviders(probe).build();

    run(engine, new AgentSession("session-1", "service-1", Map.of()), "hi");

    assertThat(probe.observedState(InMemoryHistoryProvider.DEFAULT_SOURCE_ID)).isEmpty();
  }

  @Test
  void aConfiguredLoadEnabledHistoryProviderSuppressesTheDefaultInMemoryHistory() {
    ProbeProvider probe = new ProbeProvider("probe");
    AgentEngine engine =
        AgentEngine.builder()
            .modelClient(fixedClient("hello"))
            .contextProviders(
                new InMemoryHistoryProvider("primary", HistoryPolicy.defaults()), probe)
            .build();

    run(engine, new AgentSession("session-1", null, Map.of()), "hi");

    assertThat(probe.observedState(InMemoryHistoryProvider.DEFAULT_SOURCE_ID)).isEmpty();
    assertThat(probe.storedHistory("primary"))
        .extracting(Message::text)
        .containsExactly("hi", "hello");
  }

  @Test
  void aLoadDisabledHistoryProviderDoesNotSuppressTheDefaultInMemoryHistory() {
    ProbeProvider probe = new ProbeProvider("probe");
    AgentEngine engine =
        AgentEngine.builder()
            .modelClient(fixedClient("hello"))
            .contextProviders(
                new InMemoryHistoryProvider(
                    "audit", HistoryPolicy.builder().loadMessages(false).build()),
                probe)
            .build();

    run(engine, new AgentSession("session-1", null, Map.of()), "hi");

    assertThat(probe.storedHistory(InMemoryHistoryProvider.DEFAULT_SOURCE_ID))
        .extracting(Message::text)
        .containsExactly("hi", "hello");
    assertThat(probe.storedHistory("audit"))
        .extracting(Message::text)
        .containsExactly("hi", "hello");
  }

  @Test
  void theDefaultInMemoryHistoryAvoidsAConfiguredSourceIdCollisionDeterministically() {
    ProbeProvider probe = new ProbeProvider("probe");
    AgentEngine engine =
        AgentEngine.builder()
            .modelClient(fixedClient("hello"))
            .contextProviders(
                new InMemoryHistoryProvider(
                    InMemoryHistoryProvider.DEFAULT_SOURCE_ID,
                    HistoryPolicy.builder().loadMessages(false).storeOutputs(false).build()),
                probe)
            .build();

    run(engine, new AgentSession("session-1", null, Map.of()), "hi");

    assertThat(probe.storedHistory(InMemoryHistoryProvider.DEFAULT_SOURCE_ID))
        .extracting(Message::text)
        .containsExactly("hi");
    assertThat(probe.storedHistory(InMemoryHistoryProvider.DEFAULT_SOURCE_ID + "-2"))
        .extracting(Message::text)
        .containsExactly("hi", "hello");
  }

  @Test
  void theDefaultInMemoryHistoryIsDecidedFromTheStoredSessionForBothHookDirections() {
    RecordingStore store = new RecordingStore();
    store.seed(
        registry.snapshot(
            new AgentSession("session-1", "service-1", Map.of()), 3, SEEDED_CREATED_AT));
    ProbeProvider probe = new ProbeProvider("probe");
    AgentEngine engine = engineWithStore(store, fixedClient("hello"), probe);

    run(engine, new AgentSession("session-1", null, Map.of()), "hi");

    assertThat(probe.beforeRunHistory(InMemoryHistoryProvider.DEFAULT_SOURCE_ID)).isEmpty();
    assertThat(probe.afterRunHistory(InMemoryHistoryProvider.DEFAULT_SOURCE_ID)).isEmpty();
    SessionSnapshot saved = store.saved("session-1");
    assertThat(saved.serviceSessionId()).isEqualTo("service-1");
    assertThat(saved.revision()).isEqualTo(4);
    assertThat(saved.state()).containsOnlyKeys("probe");
  }

  @Test
  void aStoredHistoryIsReadBeforeTheModelCallAndExtendedAfterIt() {
    RecordingStore store = new RecordingStore();
    AtomicReference<ModelRequest> captured = new AtomicReference<>();
    AgentEngine engine = engineWithStore(store, capturingClient(captured, "answer"));

    run(engine, new AgentSession("session-1", null, Map.of()), "first");
    run(engine, new AgentSession("session-1", null, Map.of()), "second");

    assertThat(captured.get().messages())
        .extracting(Message::text)
        .containsExactly("first", "answer", "second");
    assertThat(historyOf(store.saved("session-1"), InMemoryHistoryProvider.DEFAULT_SOURCE_ID))
        .extracting(Message::text)
        .containsExactly("first", "answer", "second", "answer");
  }

  // --- Task 2 F1: state write-back is limited to the run's resolved provider sources ------------

  @Test
  void aProviderWritingASiblingNamespaceDoesNotPersistThatSlot() {
    ProbeProvider probe = new ProbeProvider("probe");
    AgentEngine engine =
        AgentEngine.builder()
            .modelClient(fixedClient("hello"))
            .contextProviders(new SiblingWritingProvider("writer", "ghost"), probe)
            .build();

    run(engine, new AgentSession("session-1", null, Map.of()), "hi");

    assertThat(probe.updatedState()).containsKey("writer").doesNotContainKey("ghost");
  }

  @Test
  void onlyResolvedProviderSourcesReachTheStoreAndOtherStoredStateIsPreserved() {
    RecordingStore store = new RecordingStore();
    store.seed(
        registry.snapshot(
            new AgentSession(
                "session-1", null, Map.of("legacy", MessageHistory.of(List.of(user("kept"))))),
            0,
            SEEDED_CREATED_AT));
    AgentEngine engine =
        engineWithStore(store, fixedClient("hello"), new SiblingWritingProvider("writer", "ghost"));

    run(engine, new AgentSession("session-1", null, Map.of()), "hi");

    SessionSnapshot saved = store.saved("session-1");
    assertThat(saved.state())
        .containsOnlyKeys("legacy", "writer", InMemoryHistoryProvider.DEFAULT_SOURCE_ID);
    assertThat(historyOf(saved, "legacy")).extracting(Message::text).containsExactly("kept");
  }

  // --- SES-003 load -> restore -> run -> snapshot -> save ---------------------------------------

  @Test
  void aRunWithoutAStoredSnapshotSavesRevisionZero() {
    RecordingStore store = new RecordingStore();
    AgentEngine engine = engineWithStore(store, fixedClient("hello"));

    run(engine, new AgentSession("session-1", null, Map.of()), "hi");

    assertThat(store.log).containsExactly("load:session-1", "save:session-1");
    SessionSnapshot saved = store.saved("session-1");
    assertThat(saved.revision()).isZero();
    assertThat(saved.type()).isEqualTo("session");
    assertThat(saved.version()).isEqualTo("1.0");
    assertThat(historyOf(saved, InMemoryHistoryProvider.DEFAULT_SOURCE_ID))
        .extracting(Message::text)
        .containsExactly("hi", "hello");
  }

  @Test
  void aStoredSnapshotIsSavedAtThePreviousRevisionPlusOneAndKeepsItsCreatedAt() {
    RecordingStore store = new RecordingStore();
    store.seed(
        registry.snapshot(new AgentSession("session-1", null, Map.of()), 7, SEEDED_CREATED_AT));
    AgentEngine engine = engineWithStore(store, fixedClient("hello"));

    run(engine, new AgentSession("session-1", null, Map.of()), "hi");
    run(engine, new AgentSession("session-1", null, Map.of()), "again");

    SessionSnapshot saved = store.saved("session-1");
    assertThat(saved.revision()).isEqualTo(9);
    assertThat(saved.createdAt()).isEqualTo(SEEDED_CREATED_AT);
  }

  @Test
  void aFirstSaveStampsItsOwnCreatedAtAndLaterSavesKeepIt() {
    RecordingStore store = new RecordingStore();
    AgentEngine engine = engineWithStore(store, fixedClient("hello"));

    run(engine, new AgentSession("session-1", null, Map.of()), "hi");
    Instant firstCreatedAt = store.saved("session-1").createdAt();
    run(engine, new AgentSession("session-1", null, Map.of()), "again");

    assertThat(store.saved("session-1").createdAt()).isEqualTo(firstCreatedAt);
    assertThat(store.saved("session-1").revision()).isEqualTo(1);
  }

  @Test
  void aRestartLoadsTheHistoryStoredByTheEarlierRun() {
    RecordingStore store = new RecordingStore();
    run(
        engineWithStore(store, fixedClient("hello")),
        new AgentSession("session-1", null, Map.of()),
        "first");

    AtomicReference<ModelRequest> captured = new AtomicReference<>();
    AgentEngine restarted = engineWithStore(store, capturingClient(captured, "answer"));
    run(restarted, new AgentSession("session-1", null, Map.of()), "second");

    assertThat(captured.get().messages())
        .extracting(Message::text)
        .containsExactly("first", "hello", "second");
  }

  @Test
  void theStoredSessionStateBeatsTheRequestSessionState() {
    RecordingStore store = new RecordingStore();
    store.seed(
        registry.snapshot(
            new AgentSession(
                "session-1",
                null,
                Map.of(
                    InMemoryHistoryProvider.DEFAULT_SOURCE_ID,
                    MessageHistory.of(List.of(user("stored"))))),
            0,
            SEEDED_CREATED_AT));
    AtomicReference<ModelRequest> captured = new AtomicReference<>();
    AgentEngine engine = engineWithStore(store, capturingClient(captured, "answer"));

    run(
        engine,
        new AgentSession(
            "session-1",
            null,
            Map.of(
                InMemoryHistoryProvider.DEFAULT_SOURCE_ID,
                MessageHistory.of(List.of(user("request-only"))))),
        "hi");

    assertThat(captured.get().messages()).extracting(Message::text).containsExactly("stored", "hi");
  }

  @Test
  void aSessionlessRunPerformsNoStoreIo() {
    RecordingStore store = new RecordingStore();
    AgentEngine engine = engineWithStore(store, fixedClient("hello"));

    engine.run("hi").response().toCompletableFuture().join();

    assertThat(store.log).isEmpty();
  }

  @Test
  void theSnapshotIsSavedOnlyAfterEveryAfterRunHook() {
    List<String> log = new ArrayList<>();
    RecordingStore store = new RecordingStore(log);
    AgentEngine engine =
        engineWithStore(
            store,
            loggingClient(log, "hello"),
            new LoggingProvider("first", log),
            new LoggingProvider("second", log));

    run(engine, new AgentSession("session-1", null, Map.of()), "hi");

    assertThat(log)
        .containsExactly(
            "load:session-1",
            "before:first",
            "before:second",
            "model",
            "after:second",
            "after:first",
            "save:session-1");
  }

  // --- failure paths never save ------------------------------------------------------------------

  @Test
  void aFailingLoadFailsTheRunAndSavesNothing() {
    RecordingStore store = new RecordingStore();
    store.failLoadWith(new IllegalStateException("store offline"));
    AgentEngine engine = engineWithStore(store, fixedClient("hello"));

    assertThatThrownBy(() -> run(engine, new AgentSession("session-1", null, Map.of()), "hi"))
        .hasRootCauseInstanceOf(IllegalStateException.class)
        .hasRootCauseMessage("store offline");
    assertThat(store.log).containsExactly("load:session-1");
  }

  @Test
  void aStoredSnapshotForAnotherSessionFailsTheRunAndSavesNothing() {
    RecordingStore store = new RecordingStore();
    store.seed(
        "session-1",
        registry.snapshot(new AgentSession("other-session", null, Map.of()), 0, SEEDED_CREATED_AT));
    AgentEngine engine = engineWithStore(store, fixedClient("hello"));

    assertThatThrownBy(() -> run(engine, new AgentSession("session-1", null, Map.of()), "hi"))
        .hasRootCauseInstanceOf(IllegalArgumentException.class)
        .hasRootCauseMessage("hydrated session id must be session-1");
    assertThat(store.log).containsExactly("load:session-1");
  }

  @Test
  void aMalformedStoredSnapshotFailsTheRunAndSavesNothing() {
    RecordingStore store = new RecordingStore();
    store.seed(
        new SessionSnapshot(
            "session",
            "1.0",
            "session-1",
            null,
            2,
            SEEDED_CREATED_AT,
            Map.of(
                InMemoryHistoryProvider.DEFAULT_SOURCE_ID,
                new SessionStateEntry("core.message_history", 1, List.of("not-a-message")))));
    AgentEngine engine = engineWithStore(store, fixedClient("hello"));

    assertThatThrownBy(() -> run(engine, new AgentSession("session-1", null, Map.of()), "hi"))
        .hasCauseInstanceOf(SessionStateDecodingException.class)
        .hasRootCauseMessage("message payload must be an object");
    assertThat(store.log).containsExactly("load:session-1");
  }

  @Test
  void aModelFailureSavesNothing() {
    RecordingStore store = new RecordingStore();
    AgentEngine engine =
        engineWithStore(
            store,
            request -> CompletableFuture.failedFuture(new IllegalStateException("model offline")));

    assertThatThrownBy(() -> run(engine, new AgentSession("session-1", null, Map.of()), "hi"))
        .hasRootCauseMessage("model offline");
    assertThat(store.log).containsExactly("load:session-1");
  }

  @Test
  void aBeforeRunHookFailureSavesNothing() {
    RecordingStore store = new RecordingStore();
    AgentEngine engine =
        engineWithStore(store, fixedClient("hello"), new FailingProvider("broken", true));

    assertThatThrownBy(() -> run(engine, new AgentSession("session-1", null, Map.of()), "hi"))
        .hasRootCauseMessage("before-run failed");
    assertThat(store.log).containsExactly("load:session-1");
  }

  @Test
  void anAfterRunHookFailureSavesNothing() {
    RecordingStore store = new RecordingStore();
    AgentEngine engine =
        engineWithStore(store, fixedClient("hello"), new FailingProvider("broken", false));

    assertThatThrownBy(() -> run(engine, new AgentSession("session-1", null, Map.of()), "hi"))
        .hasRootCauseMessage("after-run failed");
    assertThat(store.log).containsExactly("load:session-1");
  }

  @Test
  void aCancelledRunSavesNothing() {
    RecordingStore store = new RecordingStore();
    CompletableFuture<ModelResponse> pending = new CompletableFuture<>();
    CancellationSignal signal = new CancellationSignal();
    AgentEngine engine = engineWithStore(store, request -> pending);

    AgentRun agentRun =
        engine.run(
            new AgentRunRequest(
                Message.normalize("hi"),
                new AgentSession("session-1", null, Map.of()),
                new AgentRunOptions(),
                signal,
                Map.of()));
    signal.cancel();
    pending.complete(modelResponse("hello"));

    assertThatThrownBy(() -> agentRun.response().toCompletableFuture().join())
        .hasRootCauseInstanceOf(CancellationException.class);
    assertThat(store.log).containsExactly("load:session-1");
  }

  @Test
  void aFailingSaveFailsTheRun() {
    RecordingStore store = new RecordingStore();
    store.failSaveWith(new IllegalStateException("store read only"));
    AgentEngine engine = engineWithStore(store, fixedClient("hello"));

    assertThatThrownBy(() -> run(engine, new AgentSession("session-1", null, Map.of()), "hi"))
        .hasRootCauseMessage("store read only");
    assertThat(store.log).containsExactly("load:session-1", "save:session-1");
  }

  @Test
  void aRunWhoseStateTypeIsNotRegisteredFailsTheSaveInsteadOfWritingIt() {
    RecordingStore store = new RecordingStore();
    AgentEngine engine =
        engineWithStore(store, fixedClient("hello"), new CounterProvider("counter"));

    assertThatThrownBy(() -> run(engine, new AgentSession("session-1", null, Map.of()), "hi"))
        .hasRootCauseInstanceOf(IllegalArgumentException.class);
    assertThat(store.log).containsExactly("load:session-1");
  }

  // --- streaming
  // ----------------------------------------------------------------------------------

  @Test
  void aStreamingRunLoadsBeforeTheModelCallAndSavesAfterTheRun() {
    List<String> log = new ArrayList<>();
    RecordingStore store = new RecordingStore(log);
    AgentEngine engine = engineWithStore(store, new LoggingStreamingClient(log, "hello"));

    runStreaming(engine, new AgentSession("session-1", null, Map.of()), "hi");

    assertThat(log).containsExactly("load:session-1", "model", "save:session-1");
    assertThat(historyOf(store.saved("session-1"), InMemoryHistoryProvider.DEFAULT_SOURCE_ID))
        .extracting(Message::text)
        .containsExactly("hi", "hello");
  }

  @Test
  void aStreamingRestartLoadsTheHistoryStoredByTheEarlierRun() {
    RecordingStore store = new RecordingStore();
    LoggingStreamingClient client = new LoggingStreamingClient(new ArrayList<>(), "hello");
    runStreaming(
        engineWithStore(store, client), new AgentSession("session-1", null, Map.of()), "hi");

    runStreaming(
        engineWithStore(store, client), new AgentSession("session-1", null, Map.of()), "again");

    assertThat(client.lastRequest.get().messages())
        .extracting(Message::text)
        .containsExactly("hi", "hello", "again");
  }

  @Test
  void aStreamingRunWithAFailingLoadSavesNothing() {
    RecordingStore store = new RecordingStore();
    store.failLoadWith(new IllegalStateException("store offline"));
    AgentEngine engine =
        engineWithStore(store, new LoggingStreamingClient(new ArrayList<>(), "hello"));

    assertThatThrownBy(
            () -> runStreaming(engine, new AgentSession("session-1", null, Map.of()), "hi"))
        .hasRootCauseMessage("store offline");
    assertThat(store.log).containsExactly("load:session-1");
  }

  @Test
  void aStreamingRunWithoutASessionPerformsNoStoreIo() {
    RecordingStore store = new RecordingStore();
    AgentEngine engine =
        engineWithStore(store, new LoggingStreamingClient(new ArrayList<>(), "hello"));

    consume(engine.runStreaming("hi"));

    assertThat(store.log).isEmpty();
  }

  // --- builder configuration
  // -----------------------------------------------------------------------

  @Test
  void aStateCodecRegistryWithoutASessionStoreIsRejected() {
    assertThatThrownBy(
            () ->
                AgentEngine.builder()
                    .modelClient(fixedClient("hello"))
                    .stateCodecRegistry(registry)
                    .build())
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("stateCodecRegistry requires a configured sessionStore");
  }

  @Test
  void aNullSessionStoreIsRejected() {
    assertThatThrownBy(() -> AgentEngine.builder().sessionStore(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("sessionStore must not be null");
  }

  @Test
  void aNullStateCodecRegistryIsRejected() {
    assertThatThrownBy(() -> AgentEngine.builder().stateCodecRegistry(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("stateCodecRegistry must not be null");
  }

  @Test
  void aConfiguredRegistryOwnsTheStateTypesTheRunMayPersist() {
    RecordingStore store = new RecordingStore();
    StateCodecRegistry configured =
        StateCodecRegistry.builder().register(new CounterCodec()).build();
    AgentEngine engine =
        AgentEngine.builder()
            .modelClient(fixedClient("hello"))
            .sessionStore(store)
            .stateCodecRegistry(configured)
            .contextProviders(new CounterProvider("counter"))
            .build();

    run(engine, new AgentSession("session-1", null, Map.of()), "hi");
    run(engine, new AgentSession("session-1", null, Map.of()), "again");

    assertThat(configured.restore(store.saved("session-1")).state())
        .containsEntry("counter", new Counter(2));
  }

  // --- helpers ---------------------------------------------------------------------------------

  private static AgentEngine engineWithStore(
      SessionStore store, ModelClient client, ContextProvider... providers) {
    return AgentEngine.builder()
        .modelClient(client)
        .sessionStore(store)
        .contextProviders(providers)
        .build();
  }

  private static void run(AgentEngine engine, AgentSession session, String input) {
    engine
        .run(
            new AgentRunRequest(
                Message.normalize(input),
                session,
                new AgentRunOptions(),
                new CancellationSignal(),
                Map.of()))
        .response()
        .toCompletableFuture()
        .join();
  }

  private static void runStreaming(AgentEngine engine, AgentSession session, String input) {
    consume(
        engine.runStreaming(
            new AgentRunRequest(
                Message.normalize(input),
                session,
                new AgentRunOptions(),
                new CancellationSignal(),
                Map.of())));
  }

  private static void consume(AgentStreamingRun<AgentResponseUpdate> streamingRun) {
    streamingRun
        .updates()
        .subscribe(
            new Flow.Subscriber<AgentResponseUpdate>() {
              @Override
              public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
              }

              @Override
              public void onNext(AgentResponseUpdate item) {
                // The run outcome is asserted through the response stage, not the update stream.
              }

              @Override
              public void onError(Throwable throwable) {
                // The run outcome is asserted through the response stage, not the update stream.
              }

              @Override
              public void onComplete() {
                // The run outcome is asserted through the response stage, not the update stream.
              }
            });
    streamingRun.response().toCompletableFuture().join();
  }

  private List<Message> historyOf(SessionSnapshot snapshot, String namespace) {
    return ((MessageHistory) registry.restore(snapshot).state().get(namespace)).messages();
  }

  private static ModelClient fixedClient(String text) {
    return request -> completedFuture(modelResponse(text));
  }

  private static ModelClient capturingClient(AtomicReference<ModelRequest> captured, String text) {
    return request -> {
      captured.set(request);
      return completedFuture(modelResponse(text));
    };
  }

  private static ModelClient loggingClient(List<String> log, String text) {
    return request -> {
      log.add("model");
      return completedFuture(modelResponse(text));
    };
  }

  private static ModelResponse modelResponse(String text) {
    return new ModelResponse(
        List.of(new Message(Role.ASSISTANT, List.of(new TextContent(text)))),
        null,
        FinishReason.STOP,
        Map.of(),
        null);
  }

  private static Message user(String text) {
    return new Message(Role.USER, List.of(new TextContent(text)));
  }

  /** A session store that records every call, so a test can prove which I/O a run performed. */
  private static final class RecordingStore implements SessionStore {

    private final Map<String, SessionSnapshot> snapshots = new LinkedHashMap<>();
    private final List<String> log;
    private RuntimeException loadFailure;
    private RuntimeException saveFailure;

    private RecordingStore() {
      this(new ArrayList<>());
    }

    private RecordingStore(List<String> log) {
      this.log = log;
    }

    private void seed(SessionSnapshot snapshot) {
      snapshots.put(snapshot.sessionId(), snapshot);
    }

    private void seed(String sessionId, SessionSnapshot snapshot) {
      snapshots.put(sessionId, snapshot);
    }

    private void failLoadWith(RuntimeException failure) {
      loadFailure = failure;
    }

    private void failSaveWith(RuntimeException failure) {
      saveFailure = failure;
    }

    private SessionSnapshot saved(String sessionId) {
      return snapshots.get(sessionId);
    }

    @Override
    public CompletionStage<Optional<SessionSnapshot>> load(String sessionId) {
      log.add("load:" + sessionId);
      if (loadFailure != null) {
        return CompletableFuture.failedFuture(loadFailure);
      }
      return completedFuture(Optional.ofNullable(snapshots.get(sessionId)));
    }

    @Override
    public CompletionStage<Void> save(SessionSnapshot snapshot) {
      log.add("save:" + snapshot.sessionId());
      if (saveFailure != null) {
        return CompletableFuture.failedFuture(saveFailure);
      }
      snapshots.put(snapshot.sessionId(), snapshot);
      return completedFuture(null);
    }

    @Override
    public CompletionStage<Void> delete(String sessionId) {
      log.add("delete:" + sessionId);
      snapshots.remove(sessionId);
      return completedFuture(null);
    }
  }

  /**
   * Observes the run from a configured provider hook. Because {@code afterRun} runs in reverse
   * declaration order, a probe declared before the engine's own default history provider sees the
   * state that provider produced for the finished run.
   */
  private static final class ProbeProvider implements ContextProvider {

    private final String sourceId;
    private final AtomicReference<SessionContext> context = new AtomicReference<>();
    private final AtomicReference<List<Message>> beforeRunDefaultHistory = new AtomicReference<>();
    private final AtomicReference<List<Message>> afterRunDefaultHistory = new AtomicReference<>();
    private final AtomicReference<Map<String, Object>> updatedState = new AtomicReference<>();

    private ProbeProvider(String sourceId) {
      this.sourceId = sourceId;
    }

    @Override
    public String sourceId() {
      return sourceId;
    }

    @Override
    public CompletionStage<Void> beforeRun(SessionContext runContext, ProviderSessionState state) {
      context.set(runContext);
      beforeRunDefaultHistory.set(historyIn(runContext, InMemoryHistoryProvider.DEFAULT_SOURCE_ID));
      return completedFuture(null);
    }

    @Override
    public CompletionStage<Void> afterRun(SessionContext runContext, ProviderSessionState state) {
      afterRunDefaultHistory.set(historyIn(runContext, InMemoryHistoryProvider.DEFAULT_SOURCE_ID));
      state.set(MessageHistory.of(runContext.inputMessages()));
      updatedState.set(
          runContext
              .updatedSession()
              .<Map<String, Object>>map(session -> new LinkedHashMap<>(session.state()))
              .orElse(Map.of()));
      return completedFuture(null);
    }

    private static List<Message> historyIn(SessionContext runContext, String namespace) {
      return runContext
          .providerState(namespace)
          .value(MessageHistory.class)
          .map(MessageHistory::messages)
          .orElseGet(List::of);
    }

    private Optional<Object> observedState(String namespace) {
      return context.get().providerState(namespace).value();
    }

    private List<Message> storedHistory(String namespace) {
      return ((MessageHistory) observedState(namespace).orElseThrow()).messages();
    }

    private List<Message> beforeRunHistory(String namespace) {
      assertThat(namespace).isEqualTo(InMemoryHistoryProvider.DEFAULT_SOURCE_ID);
      return beforeRunDefaultHistory.get();
    }

    private List<Message> afterRunHistory(String namespace) {
      assertThat(namespace).isEqualTo(InMemoryHistoryProvider.DEFAULT_SOURCE_ID);
      return afterRunDefaultHistory.get();
    }

    private Map<String, Object> updatedState() {
      return updatedState.get();
    }
  }

  /** A provider that writes a namespace it was never bound to, which must never be persisted. */
  private static final class SiblingWritingProvider implements ContextProvider {

    private final String sourceId;
    private final String siblingSourceId;

    private SiblingWritingProvider(String sourceId, String siblingSourceId) {
      this.sourceId = sourceId;
      this.siblingSourceId = siblingSourceId;
    }

    @Override
    public String sourceId() {
      return sourceId;
    }

    @Override
    public CompletionStage<Void> beforeRun(SessionContext runContext, ProviderSessionState state) {
      state.set(MessageHistory.of(List.of(user("own"))));
      runContext.providerState(siblingSourceId).set(MessageHistory.of(List.of(user("leaked"))));
      return completedFuture(null);
    }

    @Override
    public CompletionStage<Void> afterRun(SessionContext runContext, ProviderSessionState state) {
      return completedFuture(null);
    }
  }

  /** Records hook ordering, so a test can prove where the snapshot save happens. */
  private static final class LoggingProvider implements ContextProvider {

    private final String sourceId;
    private final List<String> log;

    private LoggingProvider(String sourceId, List<String> log) {
      this.sourceId = sourceId;
      this.log = log;
    }

    @Override
    public String sourceId() {
      return sourceId;
    }

    @Override
    public CompletionStage<Void> beforeRun(SessionContext runContext, ProviderSessionState state) {
      log.add("before:" + sourceId);
      return completedFuture(null);
    }

    @Override
    public CompletionStage<Void> afterRun(SessionContext runContext, ProviderSessionState state) {
      log.add("after:" + sourceId);
      return completedFuture(null);
    }
  }

  /** Fails exactly one of the two hooks, so a test can prove a failed run never saves. */
  private static final class FailingProvider implements ContextProvider {

    private final String sourceId;
    private final boolean failBefore;

    private FailingProvider(String sourceId, boolean failBefore) {
      this.sourceId = sourceId;
      this.failBefore = failBefore;
    }

    @Override
    public String sourceId() {
      return sourceId;
    }

    @Override
    public CompletionStage<Void> beforeRun(SessionContext runContext, ProviderSessionState state) {
      return failBefore
          ? CompletableFuture.failedFuture(new IllegalStateException("before-run failed"))
          : completedFuture(null);
    }

    @Override
    public CompletionStage<Void> afterRun(SessionContext runContext, ProviderSessionState state) {
      return failBefore
          ? completedFuture(null)
          : CompletableFuture.failedFuture(new IllegalStateException("after-run failed"));
    }
  }

  /** Stores a custom state type, so a test can prove which registry the engine actually uses. */
  private static final class CounterProvider implements ContextProvider {

    private final String sourceId;

    private CounterProvider(String sourceId) {
      this.sourceId = sourceId;
    }

    @Override
    public String sourceId() {
      return sourceId;
    }

    @Override
    public CompletionStage<Void> beforeRun(SessionContext runContext, ProviderSessionState state) {
      return completedFuture(null);
    }

    @Override
    public CompletionStage<Void> afterRun(SessionContext runContext, ProviderSessionState state) {
      state.set(new Counter(state.value(Counter.class).map(Counter::runs).orElse(0) + 1));
      return completedFuture(null);
    }
  }

  private record Counter(int runs) {}

  private static final class CounterCodec implements StateCodec<Counter> {

    @Override
    public String typeId() {
      return "test.counter";
    }

    @Override
    public int version() {
      return 1;
    }

    @Override
    public Class<Counter> javaType() {
      return Counter.class;
    }

    @Override
    public Object encode(Counter value) {
      return Map.of("runs", value.runs());
    }

    @Override
    public Counter decode(Object payload) {
      return new Counter(((Number) ((Map<?, ?>) payload).get("runs")).intValue());
    }
  }

  /** A streaming client that records the request it was given and the order it was called in. */
  private static final class LoggingStreamingClient implements StreamingModelClient {

    private final List<String> log;
    private final String text;
    private final AtomicReference<ModelRequest> lastRequest = new AtomicReference<>();

    private LoggingStreamingClient(List<String> log, String text) {
      this.log = log;
      this.text = text;
    }

    @Override
    public CompletionStage<ModelResponse> run(ModelRequest request) {
      return completedFuture(modelResponse(text));
    }

    @Override
    public Flow.Publisher<ModelResponseUpdate> runStreaming(ModelRequest request) {
      log.add("model");
      lastRequest.set(request);
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
                          List.of(new Message(Role.ASSISTANT, List.of(new TextContent(text)))),
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
