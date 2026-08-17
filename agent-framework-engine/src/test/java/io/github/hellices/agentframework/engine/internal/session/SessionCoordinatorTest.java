package io.github.hellices.agentframework.engine.internal.session;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hellices.agentframework.api.agent.Agent;
import io.github.hellices.agentframework.api.agent.AgentBuilder;
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
import io.github.hellices.agentframework.api.session.MessageHistory;
import io.github.hellices.agentframework.api.session.SessionContext;
import io.github.hellices.agentframework.api.session.SessionSnapshot;
import io.github.hellices.agentframework.api.session.SessionState;
import io.github.hellices.agentframework.api.session.SessionStateEntry;
import io.github.hellices.agentframework.api.session.SessionStateKey;
import io.github.hellices.agentframework.api.session.SessionStateValues;
import io.github.hellices.agentframework.api.value.JsonValue;
import io.github.hellices.agentframework.api.value.JsonValues;
import io.github.hellices.agentframework.engine.AgentEngine;
import io.github.hellices.agentframework.engine.EngineModels;
import io.github.hellices.agentframework.engine.session.InMemoryHistoryProvider;
import io.github.hellices.agentframework.spi.model.ModelClient;
import io.github.hellices.agentframework.spi.model.ModelRequest;
import io.github.hellices.agentframework.spi.model.ModelResponse;
import io.github.hellices.agentframework.spi.model.ModelResponseUpdate;
import io.github.hellices.agentframework.spi.session.ContextProvider;
import io.github.hellices.agentframework.spi.session.HistoryPolicy;
import io.github.hellices.agentframework.spi.session.ProviderSessionState;
import io.github.hellices.agentframework.spi.session.SessionStateDecodingException;
import io.github.hellices.agentframework.spi.session.SessionStore;
import io.github.hellices.agentframework.spi.session.StateCodec;
import io.github.hellices.agentframework.spi.session.StateCodecRegistry;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
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

  private static AgentBuilder boundBuilder(ModelClient client) {
    return AgentEngine.builder().build().factory().builderWithClient(client);
  }

  private static final Instant SEEDED_CREATED_AT = Instant.parse("2026-01-01T00:00:00Z");

  private final StateCodecRegistry registry = StateCodecRegistry.builder().build();

  // --- SES-014 default in-memory history injection conditions -----------------------------------

  @Test
  void aRunWithASessionAndNoHistoryProviderResolvesTheDefaultInMemoryHistory() {
    ProbeProvider probe = new ProbeProvider("probe");
    Agent engine = boundBuilder(fixedClient("hello")).contextProviders(probe).build();

    run(engine, session("session-1", null, Map.of()), "hi");

    assertThat(probe.storedHistory(InMemoryHistoryProvider.DEFAULT_SOURCE_ID))
        .extracting(Message::text)
        .containsExactly("hi", "hello");
  }

  @Test
  void aSessionlessRunResolvesNoDefaultInMemoryHistory() {
    ProbeProvider probe = new ProbeProvider("probe");
    Agent engine = boundBuilder(fixedClient("hello")).contextProviders(probe).build();

    engine.run("hi").response().toCompletableFuture().join();

    assertThat(probe.observedState(InMemoryHistoryProvider.DEFAULT_SOURCE_ID)).isEmpty();
  }

  @Test
  void aServiceManagedConversationResolvesNoDefaultInMemoryHistory() {
    ProbeProvider probe = new ProbeProvider("probe");
    Agent engine = boundBuilder(fixedClient("hello")).contextProviders(probe).build();

    run(engine, session("session-1", "service-1", Map.of()), "hi");

    assertThat(probe.observedState(InMemoryHistoryProvider.DEFAULT_SOURCE_ID)).isEmpty();
  }

  @Test
  void aConfiguredLoadEnabledHistoryProviderSuppressesTheDefaultInMemoryHistory() {
    ProbeProvider probe = new ProbeProvider("probe");
    Agent engine =
        boundBuilder(fixedClient("hello"))
            .contextProviders(
                new InMemoryHistoryProvider("primary", HistoryPolicy.defaults()), probe)
            .build();

    run(engine, session("session-1", null, Map.of()), "hi");

    assertThat(probe.observedState(InMemoryHistoryProvider.DEFAULT_SOURCE_ID)).isEmpty();
    assertThat(probe.storedHistory("primary"))
        .extracting(Message::text)
        .containsExactly("hi", "hello");
  }

  @Test
  void aLoadDisabledHistoryProviderDoesNotSuppressTheDefaultInMemoryHistory() {
    ProbeProvider probe = new ProbeProvider("probe");
    Agent engine =
        boundBuilder(fixedClient("hello"))
            .contextProviders(
                new InMemoryHistoryProvider(
                    "audit", HistoryPolicy.builder().loadMessages(false).build()),
                probe)
            .build();

    run(engine, session("session-1", null, Map.of()), "hi");

    assertThat(probe.storedHistory(InMemoryHistoryProvider.DEFAULT_SOURCE_ID))
        .extracting(Message::text)
        .containsExactly("hi", "hello");
    assertThat(probe.storedHistory("audit"))
        .extracting(Message::text)
        .containsExactly("hi", "hello");
  }

  @Test
  void aConfiguredProviderOwningTheDefaultHistoryNamespaceFailsAnEligibleSessionRun() {
    RecordingStore store = new RecordingStore();
    AtomicReference<ModelRequest> captured = new AtomicReference<>();
    Agent engine =
        engineWithStore(
            store,
            capturingClient(captured, "hello"),
            new InMemoryHistoryProvider(
                InMemoryHistoryProvider.DEFAULT_SOURCE_ID,
                HistoryPolicy.builder().loadMessages(false).storeOutputs(false).build()));

    assertThatThrownBy(() -> run(engine, session("session-1", null, Map.of()), "hi"))
        .hasRootCauseInstanceOf(IllegalStateException.class)
        .hasRootCauseMessage(
            "context provider sourceId '"
                + InMemoryHistoryProvider.DEFAULT_SOURCE_ID
                + "' is reserved for the default in-memory chat history of a session run; "
                + "configure a load-enabled HistoryProvider or a different sourceId");
    assertThat(captured.get()).isNull();
    assertThat(store.log).containsExactly("load:session-1");
  }

  @Test
  void aConfiguredProviderOwningTheDefaultHistoryNamespaceLeavesOtherRunsUntouched() {
    RecordingStore store = new RecordingStore();
    Agent engine =
        engineWithStore(
            store,
            fixedClient("hello"),
            new InMemoryHistoryProvider(
                InMemoryHistoryProvider.DEFAULT_SOURCE_ID,
                HistoryPolicy.builder().loadMessages(false).storeOutputs(false).build()));

    engine.run("hi").response().toCompletableFuture().join();
    run(engine, session("session-1", "service-1", Map.of()), "hi");

    assertThat(store.log).containsExactly("load:session-1", "save:session-1");
  }

  @Test
  void aLoadEnabledHistoryProviderOnTheDefaultNamespaceSuppressesTheDefaultNormally() {
    ProbeProvider probe = new ProbeProvider("probe");
    Agent engine =
        boundBuilder(fixedClient("hello"))
            .contextProviders(
                new InMemoryHistoryProvider(
                    InMemoryHistoryProvider.DEFAULT_SOURCE_ID, HistoryPolicy.defaults()),
                probe)
            .build();

    run(engine, session("session-1", null, Map.of()), "hi");

    assertThat(probe.storedHistory(InMemoryHistoryProvider.DEFAULT_SOURCE_ID))
        .extracting(Message::text)
        .containsExactly("hi", "hello");
  }

  @Test
  void theDefaultHistoryNamespaceIsStableAcrossConfigurationChanges() {
    RecordingStore store = new RecordingStore();
    run(
        engineWithStore(store, fixedClient("hello")),
        session("session-1", null, Map.of()),
        "first");

    AtomicReference<ModelRequest> captured = new AtomicReference<>();
    Agent reconfigured =
        engineWithStore(store, capturingClient(captured, "answer"), new ProbeProvider("probe"));
    run(reconfigured, session("session-1", null, Map.of()), "second");

    assertThat(captured.get().messages())
        .extracting(Message::text)
        .containsExactly("first", "hello", "second");
  }

  @Test
  void theDefaultInMemoryHistoryIsDecidedFromTheStoredSessionForBothHookDirections() {
    RecordingStore store = new RecordingStore();
    store.seed(
        registry.snapshot(session("session-1", "service-1", Map.of()), 3, SEEDED_CREATED_AT));
    ProbeProvider probe = new ProbeProvider("probe");
    Agent engine = engineWithStore(store, fixedClient("hello"), probe);

    run(engine, session("session-1", null, Map.of()), "hi");

    assertThat(probe.beforeRunHistory(InMemoryHistoryProvider.DEFAULT_SOURCE_ID)).isEmpty();
    assertThat(probe.afterRunHistory(InMemoryHistoryProvider.DEFAULT_SOURCE_ID)).isEmpty();
    SessionSnapshot saved = store.saved("session-1");
    assertThat(saved.serviceSessionId()).isEqualTo("service-1");
    assertThat(saved.revision()).isEqualTo(4);
    assertThat(saved.state()).containsOnlyKeys("probe");
  }

  // --- SES-001/SES-002: the caller's service conversation handle is never silently replaced
  // -------

  @Test
  void aRequestedServiceHandleTheStoredSessionDoesNotCarryFailsTheRunBeforeTheModelCall() {
    RecordingStore store = new RecordingStore();
    store.seed(registry.snapshot(session("session-1", null, Map.of()), 0, SEEDED_CREATED_AT));
    AtomicReference<ModelRequest> captured = new AtomicReference<>();
    Agent engine = engineWithStore(store, capturingClient(captured, "hello"));

    assertThatThrownBy(() -> run(engine, session("session-1", "service-9", Map.of()), "hi"))
        .hasRootCauseInstanceOf(IllegalArgumentException.class)
        .hasRootCauseMessage("hydrated service session id must be service-9");
    assertThat(captured.get()).isNull();
    assertThat(store.log).containsExactly("load:session-1");
    assertThat(store.saved("session-1").serviceSessionId()).isNull();
    assertThat(store.saved("session-1").revision()).isZero();
  }

  @Test
  void aRequestedServiceHandleThatDisagreesWithTheStoredOneFailsTheRun() {
    RecordingStore store = new RecordingStore();
    store.seed(
        registry.snapshot(session("session-1", "service-old", Map.of()), 0, SEEDED_CREATED_AT));
    Agent engine = engineWithStore(store, fixedClient("hello"));

    assertThatThrownBy(() -> run(engine, session("session-1", "service-new", Map.of()), "hi"))
        .hasRootCauseInstanceOf(IllegalArgumentException.class)
        .hasRootCauseMessage("hydrated service session id must be service-new");
    assertThat(store.log).containsExactly("load:session-1");
    assertThat(store.saved("session-1").serviceSessionId()).isEqualTo("service-old");
  }

  @Test
  void aMatchingServiceHandleRunsWithTheStoredSessionAndNoLocalHistory() {
    RecordingStore store = new RecordingStore();
    store.seed(
        registry.snapshot(session("session-1", "service-1", Map.of()), 2, SEEDED_CREATED_AT));
    Agent engine = engineWithStore(store, fixedClient("hello"));

    run(engine, session("session-1", "service-1", Map.of()), "hi");

    SessionSnapshot saved = store.saved("session-1");
    assertThat(saved.serviceSessionId()).isEqualTo("service-1");
    assertThat(saved.revision()).isEqualTo(3);
    assertThat(saved.state()).isEmpty();
  }

  @Test
  void anAbsentSnapshotKeepsTheRequestedServiceHandleAndInjectsNoLocalHistory() {
    RecordingStore store = new RecordingStore();
    Agent engine = engineWithStore(store, fixedClient("hello"));

    run(engine, session("session-1", "service-9", Map.of()), "hi");

    SessionSnapshot saved = store.saved("session-1");
    assertThat(saved.serviceSessionId()).isEqualTo("service-9");
    assertThat(saved.revision()).isZero();
    assertThat(saved.state()).isEmpty();
  }

  @Test
  void aServiceManagedSessionIsStillSnapshottedSoItsHandleAndStateSurvive() {
    RecordingStore store = new RecordingStore();
    Agent engine = engineWithStore(store, fixedClient("hello"));
    AgentSession session =
        session(
            "session-1", "service-9", Map.of("audit", MessageHistory.of(List.of(user("kept")))));

    run(engine, session, "hi");
    run(engine, session("session-1", null, Map.of()), "again");

    assertThat(store.log)
        .containsExactly("load:session-1", "save:session-1", "load:session-1", "save:session-1");
    SessionSnapshot saved = store.saved("session-1");
    assertThat(saved.serviceSessionId()).isEqualTo("service-9");
    assertThat(saved.revision()).isEqualTo(1);
    assertThat(saved.state()).containsOnlyKeys("audit");
    assertThat(historyOf(saved, "audit")).extracting(Message::text).containsExactly("kept");
  }

  @Test
  void aStoredHistoryIsReadBeforeTheModelCallAndExtendedAfterIt() {
    RecordingStore store = new RecordingStore();
    AtomicReference<ModelRequest> captured = new AtomicReference<>();
    Agent engine = engineWithStore(store, capturingClient(captured, "answer"));

    run(engine, session("session-1", null, Map.of()), "first");
    run(engine, session("session-1", null, Map.of()), "second");

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
    Agent engine =
        boundBuilder(fixedClient("hello"))
            .contextProviders(new SiblingWritingProvider("writer", "ghost"), probe)
            .build();

    run(engine, session("session-1", null, Map.of()), "hi");

    assertThat(probe.updatedState()).containsKey("writer").doesNotContainKey("ghost");
  }

  @Test
  void bothHookDirectionsSeeTheSameBindingsAndTheWriteBackAllowListStaysExact() {
    RecordingStore store = new RecordingStore();
    store.seed(
        registry.snapshot(
            session("session-1", null, Map.of("legacy", MessageHistory.of(List.of(user("kept"))))),
            0,
            SEEDED_CREATED_AT));
    ProbeProvider probe = new ProbeProvider("probe");
    Agent engine =
        engineWithStore(
            store, fixedClient("hello"), probe, new SiblingWritingProvider("writer", "ghost"));

    run(engine, session("session-1", null, Map.of()), "hi");

    assertThat(probe.beforeRunState.get()).isSameAs(probe.afterRunState.get());
    assertThat(probe.beforeRunDefaultHistoryState.get())
        .isSameAs(probe.afterRunDefaultHistoryState.get());
    assertThat(store.saved("session-1").state())
        .containsOnlyKeys("legacy", "probe", "writer", InMemoryHistoryProvider.DEFAULT_SOURCE_ID);
  }

  @Test
  void onlyResolvedProviderSourcesReachTheStoreAndOtherStoredStateIsPreserved() {
    RecordingStore store = new RecordingStore();
    store.seed(
        registry.snapshot(
            session("session-1", null, Map.of("legacy", MessageHistory.of(List.of(user("kept"))))),
            0,
            SEEDED_CREATED_AT));
    Agent engine =
        engineWithStore(store, fixedClient("hello"), new SiblingWritingProvider("writer", "ghost"));

    run(engine, session("session-1", null, Map.of()), "hi");

    SessionSnapshot saved = store.saved("session-1");
    assertThat(saved.state())
        .containsOnlyKeys("legacy", "writer", InMemoryHistoryProvider.DEFAULT_SOURCE_ID);
    assertThat(historyOf(saved, "legacy")).extracting(Message::text).containsExactly("kept");
  }

  // --- SES-003 load -> restore -> run -> snapshot -> save ---------------------------------------

  @Test
  void aRunWithoutAStoredSnapshotSavesRevisionZero() {
    RecordingStore store = new RecordingStore();
    Agent engine = engineWithStore(store, fixedClient("hello"));

    run(engine, session("session-1", null, Map.of()), "hi");

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
    store.seed(registry.snapshot(session("session-1", null, Map.of()), 7, SEEDED_CREATED_AT));
    Agent engine = engineWithStore(store, fixedClient("hello"));

    run(engine, session("session-1", null, Map.of()), "hi");
    run(engine, session("session-1", null, Map.of()), "again");

    SessionSnapshot saved = store.saved("session-1");
    assertThat(saved.revision()).isEqualTo(9);
    assertThat(saved.createdAt()).isEqualTo(SEEDED_CREATED_AT);
  }

  @Test
  void aFirstSaveStampsItsOwnCreatedAtAndLaterSavesKeepIt() {
    RecordingStore store = new RecordingStore();
    Agent engine = engineWithStore(store, fixedClient("hello"));

    run(engine, session("session-1", null, Map.of()), "hi");
    Instant firstCreatedAt = store.saved("session-1").createdAt();
    run(engine, session("session-1", null, Map.of()), "again");

    assertThat(store.saved("session-1").createdAt()).isEqualTo(firstCreatedAt);
    assertThat(store.saved("session-1").revision()).isEqualTo(1);
  }

  @Test
  void aRestartLoadsTheHistoryStoredByTheEarlierRun() {
    RecordingStore store = new RecordingStore();
    run(
        engineWithStore(store, fixedClient("hello")),
        session("session-1", null, Map.of()),
        "first");

    AtomicReference<ModelRequest> captured = new AtomicReference<>();
    Agent restarted = engineWithStore(store, capturingClient(captured, "answer"));
    run(restarted, session("session-1", null, Map.of()), "second");

    assertThat(captured.get().messages())
        .extracting(Message::text)
        .containsExactly("first", "hello", "second");
  }

  @Test
  void theStoredSessionStateBeatsTheRequestSessionState() {
    RecordingStore store = new RecordingStore();
    store.seed(
        registry.snapshot(
            session(
                "session-1",
                null,
                Map.of(
                    InMemoryHistoryProvider.DEFAULT_SOURCE_ID,
                    MessageHistory.of(List.of(user("stored"))))),
            0,
            SEEDED_CREATED_AT));
    AtomicReference<ModelRequest> captured = new AtomicReference<>();
    Agent engine = engineWithStore(store, capturingClient(captured, "answer"));

    run(
        engine,
        session(
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
    Agent engine = engineWithStore(store, fixedClient("hello"));

    engine.run("hi").response().toCompletableFuture().join();

    assertThat(store.log).isEmpty();
  }

  @Test
  void theSnapshotIsSavedOnlyAfterEveryAfterRunHook() {
    List<String> log = new ArrayList<>();
    RecordingStore store = new RecordingStore(log);
    Agent engine =
        engineWithStore(
            store,
            loggingClient(log, "hello"),
            new LoggingProvider("first", log),
            new LoggingProvider("second", log));

    run(engine, session("session-1", null, Map.of()), "hi");

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
    Agent engine = engineWithStore(store, fixedClient("hello"));

    assertThatThrownBy(() -> run(engine, session("session-1", null, Map.of()), "hi"))
        .hasRootCauseInstanceOf(IllegalStateException.class)
        .hasRootCauseMessage("store offline");
    assertThat(store.log).containsExactly("load:session-1");
  }

  @Test
  void aStoredSnapshotForAnotherSessionFailsTheRunAndSavesNothing() {
    RecordingStore store = new RecordingStore();
    store.seed(
        "session-1",
        registry.snapshot(session("other-session", null, Map.of()), 0, SEEDED_CREATED_AT));
    Agent engine = engineWithStore(store, fixedClient("hello"));

    assertThatThrownBy(() -> run(engine, session("session-1", null, Map.of()), "hi"))
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
    Agent engine = engineWithStore(store, fixedClient("hello"));

    assertThatThrownBy(() -> run(engine, session("session-1", null, Map.of()), "hi"))
        .hasCauseInstanceOf(SessionStateDecodingException.class)
        .hasRootCauseMessage("message payload must be an object");
    assertThat(store.log).containsExactly("load:session-1");
  }

  @Test
  void aModelFailureSavesNothing() {
    RecordingStore store = new RecordingStore();
    Agent engine =
        engineWithStore(
            store, request -> EngineModels.failed(new IllegalStateException("model offline")));

    assertThatThrownBy(() -> run(engine, session("session-1", null, Map.of()), "hi"))
        .hasRootCauseMessage("model offline");
    assertThat(store.log).containsExactly("load:session-1");
  }

  @Test
  void aBeforeRunHookFailureSavesNothing() {
    RecordingStore store = new RecordingStore();
    Agent engine =
        engineWithStore(store, fixedClient("hello"), new FailingProvider("broken", true));

    assertThatThrownBy(() -> run(engine, session("session-1", null, Map.of()), "hi"))
        .hasRootCauseMessage("before-run failed");
    assertThat(store.log).containsExactly("load:session-1");
  }

  @Test
  void anAfterRunHookFailureSavesNothing() {
    RecordingStore store = new RecordingStore();
    Agent engine =
        engineWithStore(store, fixedClient("hello"), new FailingProvider("broken", false));

    assertThatThrownBy(() -> run(engine, session("session-1", null, Map.of()), "hi"))
        .hasRootCauseMessage("after-run failed");
    assertThat(store.log).containsExactly("load:session-1");
  }

  @Test
  void aCancelledRunSavesNothing() {
    RecordingStore store = new RecordingStore();
    CompletableFuture<ModelResponse> pending = new CompletableFuture<>();
    CancellationSignal signal = new CancellationSignal();
    Agent engine = engineWithStore(store, request -> EngineModels.fromStage(pending));

    AgentRun agentRun =
        engine.run(
            request(
                Message.normalize("hi"),
                session("session-1", null, Map.of()),
                new AgentRunOptions(),
                signal,
                ContextAttributes.empty()));
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
    Agent engine = engineWithStore(store, fixedClient("hello"));

    assertThatThrownBy(() -> run(engine, session("session-1", null, Map.of()), "hi"))
        .hasRootCauseMessage("store read only");
    assertThat(store.log).containsExactly("load:session-1", "save:session-1");
  }

  @Test
  void aRunWhoseStateTypeIsNotRegisteredFailsTheSaveInsteadOfWritingIt() {
    RecordingStore store = new RecordingStore();
    Agent engine = engineWithStore(store, fixedClient("hello"), new CounterProvider("counter"));

    assertThatThrownBy(() -> run(engine, session("session-1", null, Map.of()), "hi"))
        .hasRootCauseInstanceOf(IllegalArgumentException.class);
    assertThat(store.log).containsExactly("load:session-1");
  }

  // --- streaming
  // ----------------------------------------------------------------------------------

  @Test
  void aStreamingRunLoadsBeforeTheModelCallAndSavesAfterTheRun() {
    List<String> log = new ArrayList<>();
    RecordingStore store = new RecordingStore(log);
    Agent engine = engineWithStore(store, new LoggingStreamingClient(log, "hello"));

    runStreaming(engine, session("session-1", null, Map.of()), "hi");

    assertThat(log).containsExactly("load:session-1", "model", "save:session-1");
    assertThat(historyOf(store.saved("session-1"), InMemoryHistoryProvider.DEFAULT_SOURCE_ID))
        .extracting(Message::text)
        .containsExactly("hi", "hello");
  }

  @Test
  void aStreamingRestartLoadsTheHistoryStoredByTheEarlierRun() {
    RecordingStore store = new RecordingStore();
    LoggingStreamingClient client = new LoggingStreamingClient(new ArrayList<>(), "hello");
    runStreaming(engineWithStore(store, client), session("session-1", null, Map.of()), "hi");

    runStreaming(engineWithStore(store, client), session("session-1", null, Map.of()), "again");

    assertThat(client.lastRequest.get().messages())
        .extracting(Message::text)
        .containsExactly("hi", "hello", "again");
  }

  @Test
  void aStreamingRunWithAFailingLoadSavesNothing() {
    RecordingStore store = new RecordingStore();
    store.failLoadWith(new IllegalStateException("store offline"));
    Agent engine = engineWithStore(store, new LoggingStreamingClient(new ArrayList<>(), "hello"));

    assertThatThrownBy(() -> runStreaming(engine, session("session-1", null, Map.of()), "hi"))
        .hasRootCauseMessage("store offline");
    assertThat(store.log).containsExactly("load:session-1");
  }

  @Test
  void aStreamingRunWithoutASessionPerformsNoStoreIo() {
    RecordingStore store = new RecordingStore();
    Agent engine = engineWithStore(store, new LoggingStreamingClient(new ArrayList<>(), "hello"));

    consume(engine.runStreaming("hi"));

    assertThat(store.log).isEmpty();
  }

  // --- concurrency: the store, not the coordinator, resolves overlapping runs --------------------

  @Test
  void twoOverlappingRunsOnOneSessionResolveAsLastWriterWins() {
    RecordingStore store = new RecordingStore();
    CompletableFuture<ModelResponse> firstModel = new CompletableFuture<>();
    CompletableFuture<ModelResponse> secondModel = new CompletableFuture<>();
    AtomicReference<CompletableFuture<ModelResponse>> next = new AtomicReference<>(firstModel);
    Agent engine =
        engineWithStore(store, request -> EngineModels.fromStage(next.getAndSet(secondModel)));

    AgentRun first = start(engine, session("session-1", null, Map.of()), "a");
    AgentRun second = start(engine, session("session-1", null, Map.of()), "b");
    firstModel.complete(modelResponse("A"));
    first.response().toCompletableFuture().join();
    secondModel.complete(modelResponse("B"));
    second.response().toCompletableFuture().join();

    assertThat(store.log)
        .containsExactly("load:session-1", "load:session-1", "save:session-1", "save:session-1");
    SessionSnapshot saved = store.saved("session-1");
    assertThat(saved.revision()).isZero();
    assertThat(historyOf(saved, InMemoryHistoryProvider.DEFAULT_SOURCE_ID))
        .extracting(Message::text)
        .containsExactly("b", "B");
  }

  // --- I2: the caller-visible failure shape of a client that throws -----------------------------

  @Test
  void aSessionlessRunWithNoProvidersFailsSynchronouslyWhenTheClientThrows() {
    Agent engine =
        boundBuilder(
                request -> {
                  throw new IllegalStateException("boom");
                })
            .build();

    assertThatThrownBy(() -> engine.run("hi"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("boom");
  }

  @Test
  void aSessionRunReportsAClientThatThrowsOnTheResponseAndSessionStages() {
    Agent engine =
        boundBuilder(
                request -> {
                  throw new IllegalStateException("boom");
                })
            .build();

    AgentRun agentRun = start(engine, session("session-1", null, Map.of()), "hi");

    assertThatThrownBy(() -> agentRun.response().toCompletableFuture().join())
        .hasRootCauseInstanceOf(IllegalStateException.class)
        .hasRootCauseMessage("boom");
    assertThatThrownBy(() -> agentRun.session().toCompletableFuture().join())
        .hasRootCauseInstanceOf(IllegalStateException.class)
        .hasRootCauseMessage("boom");
  }

  // --- I3: the updated session is observable without a configured store -------------------------

  @Test
  void aStoreLessSessionRunPublishesTheUpdatedSessionWithItsDefaultHistory() {
    Agent engine = boundBuilder(fixedClient("hello")).build();

    AgentRun agentRun = start(engine, session("session-1", null, Map.of()), "hi");
    agentRun.response().toCompletableFuture().join();

    AgentSession updated = agentRun.session().toCompletableFuture().join().orElseThrow();
    assertThat(updated.sessionId()).isEqualTo("session-1");
    assertThat(historyIn(updated, InMemoryHistoryProvider.DEFAULT_SOURCE_ID))
        .extracting(Message::text)
        .containsExactly("hi", "hello");
  }

  @Test
  void aStoreLessSecondTurnReplaysTheHistoryOfThePublishedSession() {
    AtomicReference<ModelRequest> captured = new AtomicReference<>();
    Agent engine = boundBuilder(capturingClient(captured, "answer")).build();

    AgentRun first = start(engine, session("session-1", null, Map.of()), "first");
    first.response().toCompletableFuture().join();
    AgentSession carried = first.session().toCompletableFuture().join().orElseThrow();
    AgentRun second = start(engine, carried, "second");
    second.response().toCompletableFuture().join();

    assertThat(captured.get().messages())
        .extracting(Message::text)
        .containsExactly("first", "answer", "second");
  }

  @Test
  void aStoreLessStreamingSessionRunPublishesTheUpdatedSession() {
    Agent engine = boundBuilder(new LoggingStreamingClient(new ArrayList<>(), "hello")).build();

    AgentStreamingRun<AgentResponseUpdate> streamingRun =
        engine.runStreaming(
            request(
                Message.normalize("hi"),
                session("session-1", null, Map.of()),
                new AgentRunOptions(),
                new CancellationSignal(),
                ContextAttributes.empty()));
    consume(streamingRun);

    AgentSession updated = streamingRun.session().toCompletableFuture().join().orElseThrow();
    assertThat(historyIn(updated, InMemoryHistoryProvider.DEFAULT_SOURCE_ID))
        .extracting(Message::text)
        .containsExactly("hi", "hello");
  }

  @Test
  void aSessionRunThatChangesNothingPublishesTheEffectiveSession() {
    Agent engine = boundBuilder(fixedClient("hello")).build();
    AgentSession requested =
        session(
            "session-1", "service-9", Map.of("audit", MessageHistory.of(List.of(user("kept")))));

    AgentRun agentRun = start(engine, requested, "hi");
    agentRun.response().toCompletableFuture().join();

    assertThat(agentRun.session().toCompletableFuture().join()).contains(requested);
  }

  @Test
  void aStoredRunPublishesTheStoredSessionRatherThanTheRequestedOne() {
    RecordingStore store = new RecordingStore();
    store.seed(
        registry.snapshot(
            session(
                "session-1", null, Map.of("legacy", MessageHistory.of(List.of(user("stored"))))),
            0,
            SEEDED_CREATED_AT));
    Agent engine = engineWithStore(store, fixedClient("hello"));

    AgentRun agentRun =
        start(
            engine,
            session(
                "session-1", null, Map.of("legacy", MessageHistory.of(List.of(user("request"))))),
            "hi");
    AgentSession updated = agentRun.session().toCompletableFuture().join().orElseThrow();

    assertThat(historyIn(updated, "legacy")).extracting(Message::text).containsExactly("stored");
    assertThat(historyIn(updated, InMemoryHistoryProvider.DEFAULT_SOURCE_ID))
        .extracting(Message::text)
        .containsExactly("hi", "hello");
  }

  @Test
  void aStoredRunPublishesTheUpdatedSessionOnlyAfterTheSaveSucceeded() {
    List<String> log = new ArrayList<>();
    RecordingStore store = new RecordingStore(log);
    Agent engine = engineWithStore(store, loggingClient(log, "hello"));

    AgentRun agentRun = start(engine, session("session-1", null, Map.of()), "hi");
    AgentSession updated = agentRun.session().toCompletableFuture().join().orElseThrow();

    assertThat(log).containsExactly("load:session-1", "model", "save:session-1");
    assertThat(historyIn(updated, InMemoryHistoryProvider.DEFAULT_SOURCE_ID))
        .extracting(Message::text)
        .containsExactly("hi", "hello");
  }

  @Test
  void aFailingSaveFailsTheSessionStageInsteadOfPublishingUnpersistedState() {
    RecordingStore store = new RecordingStore();
    store.failSaveWith(new IllegalStateException("store read only"));
    Agent engine = engineWithStore(store, fixedClient("hello"));

    AgentRun agentRun = start(engine, session("session-1", null, Map.of()), "hi");

    assertThatThrownBy(() -> agentRun.session().toCompletableFuture().join())
        .hasRootCauseMessage("store read only");
  }

  @Test
  void aCancelledRunFailsTheSessionStage() {
    RecordingStore store = new RecordingStore();
    CompletableFuture<ModelResponse> pending = new CompletableFuture<>();
    CancellationSignal signal = new CancellationSignal();
    Agent engine = engineWithStore(store, request -> EngineModels.fromStage(pending));

    AgentRun agentRun =
        engine.run(
            request(
                Message.normalize("hi"),
                session("session-1", null, Map.of()),
                new AgentRunOptions(),
                signal,
                ContextAttributes.empty()));
    signal.cancel();
    pending.complete(modelResponse("hello"));

    assertThatThrownBy(() -> agentRun.session().toCompletableFuture().join())
        .hasRootCauseInstanceOf(CancellationException.class);
    assertThat(store.log).containsExactly("load:session-1");
  }

  @Test
  void aSessionlessRunPublishesNoUpdatedSession() {
    Agent engine = boundBuilder(fixedClient("hello")).build();

    AgentRun agentRun = engine.run("hi");
    agentRun.response().toCompletableFuture().join();

    assertThat(agentRun.session().toCompletableFuture().join()).isEmpty();
  }

  // --- builder configuration
  // -----------------------------------------------------------------------

  @Test
  void aStateCodecRegistryWithoutASessionStoreIsRejected() {
    assertThatThrownBy(() -> AgentEngine.builder().stateCodecRegistry(registry).build())
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
    Agent engine =
        AgentEngine.builder()
            .sessionStore(store)
            .stateCodecRegistry(configured)
            .build()
            .factory()
            .builderWithClient(fixedClient("hello"))
            .contextProviders(new CounterProvider("counter"))
            .build();

    run(engine, session("session-1", null, Map.of()), "hi");
    run(engine, session("session-1", null, Map.of()), "again");

    assertThat(
            configured
                .restore(store.saved("session-1"))
                .state()
                .get(SessionStateKey.of("counter", Counter.class)))
        .contains(new Counter(2));
  }

  // --- helpers ---------------------------------------------------------------------------------

  private static Agent engineWithStore(
      SessionStore store, ModelClient client, ContextProvider... providers) {
    return AgentEngine.builder()
        .sessionStore(store)
        .build()
        .factory()
        .builderWithClient(client)
        .contextProviders(providers)
        .build();
  }

  private static void run(Agent engine, AgentSession session, String input) {
    start(engine, session, input).response().toCompletableFuture().join();
  }

  private static AgentRun start(Agent engine, AgentSession session, String input) {
    return engine.run(
        request(
            Message.normalize(input),
            session,
            new AgentRunOptions(),
            new CancellationSignal(),
            ContextAttributes.empty()));
  }

  private static void runStreaming(Agent engine, AgentSession session, String input) {
    consume(
        engine.runStreaming(
            request(
                Message.normalize(input),
                session,
                new AgentRunOptions(),
                new CancellationSignal(),
                ContextAttributes.empty())));
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

  private List<Message> historyOf(SessionSnapshot snapshot, String namespace) {
    return registry
        .restore(snapshot)
        .state()
        .get(SessionStateKey.of(namespace, MessageHistory.class))
        .orElseThrow()
        .messages();
  }

  private static List<Message> historyIn(AgentSession session, String namespace) {
    return session
        .state()
        .get(SessionStateKey.of(namespace, MessageHistory.class))
        .orElseThrow()
        .messages();
  }

  private static ModelClient fixedClient(String text) {
    return request -> EngineModels.of(modelResponse(text));
  }

  private static ModelClient capturingClient(AtomicReference<ModelRequest> captured, String text) {
    return request -> {
      captured.set(request);
      return EngineModels.of(modelResponse(text));
    };
  }

  private static ModelClient loggingClient(List<String> log, String text) {
    return request -> {
      log.add("model");
      return EngineModels.of(modelResponse(text));
    };
  }

  private static ModelResponse modelResponse(String text) {
    return ModelResponse.builder()
        .messages(List.of(new Message(Role.ASSISTANT, List.of(new TextContent(text)))))
        .finishReason(FinishReason.STOP)
        .build();
  }

  private static Map<String, Object> stateAsMap(SessionState state) {
    Map<String, Object> values = new LinkedHashMap<>();
    rawEntries(state)
        .forEach(
            (key, value) ->
                values.put(
                    key.id(),
                    value instanceof JsonValue jsonValue ? JsonValues.toJava(jsonValue) : value));
    return values;
  }

  @SuppressWarnings("unchecked")
  private static Map<SessionStateKey<?>, Object> rawEntries(SessionState state) {
    try {
      Field entriesField = SessionState.class.getDeclaredField("entries");
      entriesField.setAccessible(true);
      Map<String, ?> entries = (Map<String, ?>) entriesField.get(state);
      Class<?> entryType = Class.forName(SessionState.class.getName() + "$Entry");
      Method keyMethod = entryType.getDeclaredMethod("key");
      Method valueMethod = entryType.getDeclaredMethod("value");
      keyMethod.setAccessible(true);
      valueMethod.setAccessible(true);
      Map<SessionStateKey<?>, Object> values = new LinkedHashMap<>();
      for (Object entry : entries.values()) {
        values.put((SessionStateKey<?>) keyMethod.invoke(entry), valueMethod.invoke(entry));
      }
      return values;
    } catch (ReflectiveOperationException failure) {
      throw new IllegalStateException("failed to inspect session state", failure);
    }
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
    private final AtomicReference<ProviderSessionState> beforeRunState = new AtomicReference<>();
    private final AtomicReference<ProviderSessionState> afterRunState = new AtomicReference<>();
    private final AtomicReference<ProviderSessionState> beforeRunDefaultHistoryState =
        new AtomicReference<>();
    private final AtomicReference<ProviderSessionState> afterRunDefaultHistoryState =
        new AtomicReference<>();
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
      beforeRunState.set(state);
      beforeRunDefaultHistoryState.set(
          runContext.providerState(InMemoryHistoryProvider.DEFAULT_SOURCE_ID));
      beforeRunDefaultHistory.set(historyIn(runContext, InMemoryHistoryProvider.DEFAULT_SOURCE_ID));
      return completedFuture(null);
    }

    @Override
    public CompletionStage<Void> afterRun(SessionContext runContext, ProviderSessionState state) {
      afterRunState.set(state);
      afterRunDefaultHistoryState.set(
          runContext.providerState(InMemoryHistoryProvider.DEFAULT_SOURCE_ID));
      afterRunDefaultHistory.set(historyIn(runContext, InMemoryHistoryProvider.DEFAULT_SOURCE_ID));
      state.set(MessageHistory.of(runContext.inputMessages()));
      updatedState.set(
          runContext
              .updatedSession()
              .<Map<String, Object>>map(session -> stateAsMap(session.state()))
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
  private static final class LoggingStreamingClient implements ModelClient {

    private final List<String> log;
    private final String text;
    private final AtomicReference<ModelRequest> lastRequest = new AtomicReference<>();

    private LoggingStreamingClient(List<String> log, String text) {
      this.log = log;
      this.text = text;
    }

    @Override
    public Flow.Publisher<ModelResponseUpdate> execute(ModelRequest request) {
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
                      ModelResponseUpdate.builder()
                          .messages(
                              List.of(new Message(Role.ASSISTANT, List.of(new TextContent(text)))))
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
