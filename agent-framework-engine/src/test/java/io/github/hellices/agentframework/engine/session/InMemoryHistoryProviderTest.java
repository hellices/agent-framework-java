package io.github.hellices.agentframework.engine.session;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hellices.agentframework.api.agent.AgentResponse;
import io.github.hellices.agentframework.api.agent.AgentRunOptions;
import io.github.hellices.agentframework.api.agent.AgentRunRequest;
import io.github.hellices.agentframework.api.agent.AgentSession;
import io.github.hellices.agentframework.api.agent.CancellationSignal;
import io.github.hellices.agentframework.api.context.ContextAttributes;
import io.github.hellices.agentframework.api.message.FinishReason;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.MessageAttribution;
import io.github.hellices.agentframework.api.message.Role;
import io.github.hellices.agentframework.api.message.TextContent;
import io.github.hellices.agentframework.api.session.MessageHistory;
import io.github.hellices.agentframework.api.session.SessionContext;
import io.github.hellices.agentframework.api.session.SessionState;
import io.github.hellices.agentframework.api.session.SessionStateKey;
import io.github.hellices.agentframework.api.session.SessionStateValues;
import io.github.hellices.agentframework.api.value.JsonObject;
import io.github.hellices.agentframework.api.value.JsonValue;
import io.github.hellices.agentframework.api.value.JsonValues;
import io.github.hellices.agentframework.engine.AgentEngine;
import io.github.hellices.agentframework.spi.model.ModelClient;
import io.github.hellices.agentframework.spi.model.ModelRequest;
import io.github.hellices.agentframework.spi.model.ModelResponse;
import io.github.hellices.agentframework.spi.session.ContextProvider;
import io.github.hellices.agentframework.spi.session.HistoryPolicy;
import io.github.hellices.agentframework.spi.session.HistoryProvider;
import io.github.hellices.agentframework.spi.session.ProviderSessionState;
import io.github.hellices.agentframework.spi.session.StateCodecRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class InMemoryHistoryProviderTest {

  @Test
  void loadInjectsStoredHistoryInOrderStampedAsChatHistory() {
    SessionContext context =
        contextWith(
            sessionWithHistory("session-1", List.of(user("one"), assistant("two"))),
            List.of(user("three")));
    InMemoryHistoryProvider provider = new InMemoryHistoryProvider();

    beforeRun(provider, context);

    assertThat(context.contextMessages()).extracting(Message::text).containsExactly("one", "two");
    assertThat(context.contextMessages())
        .extracting(Message::attribution)
        .containsExactly(
            new MessageAttribution("ChatHistory", "in_memory", "session-1"),
            new MessageAttribution("ChatHistory", "in_memory", "session-1"));
  }

  @Test
  void loadKeepsTheOriginSessionIdProvenanceStoredWithAMessage() {
    Message crossSession =
        new Message(
            Role.USER,
            List.of(new TextContent("remembered")),
            new MessageAttribution("AIContextProvider", "rag", "other-session"),
            JsonObject.empty(),
            null);
    SessionContext context =
        contextWith(sessionWithHistory("session-1", List.of(crossSession)), List.of(user("hi")));

    beforeRun(new InMemoryHistoryProvider(), context);

    // The loaded message becomes chat history contributed by this provider, but the session that
    // originally produced it stays readable.
    assertThat(context.contextMessages())
        .singleElement()
        .extracting(Message::attribution)
        .isEqualTo(new MessageAttribution("ChatHistory", "in_memory", "other-session"));
  }

  @Test
  void loadCopiesTheStoredMessageInsteadOfMutatingIt() {
    Message stored = user("one");
    SessionContext context =
        contextWith(sessionWithHistory("session-1", List.of(stored)), List.of(user("hi")));

    beforeRun(new InMemoryHistoryProvider(), context);

    assertThat(stored.attribution()).isNull();
    assertThat(context.contextMessages()).singleElement().isNotSameAs(stored);
    assertThat(context.contextMessages())
        .singleElement()
        .extracting(Message::role)
        .isEqualTo(Role.USER);
  }

  @Test
  void loadDisabledPolicyInjectsNothingEvenWithStoredHistory() {
    SessionContext context =
        contextWith(sessionWithHistory("session-1", List.of(user("one"))), List.of(user("hi")));
    InMemoryHistoryProvider provider =
        new InMemoryHistoryProvider(HistoryPolicy.builder().loadMessages(false).build());

    beforeRun(provider, context);

    assertThat(context.contextMessages()).isEmpty();
  }

  @Test
  void loadOnAnEmptyNamespaceInjectsNothing() {
    SessionContext context = contextWith(session("session-1", null, Map.of()), List.of(user("hi")));

    beforeRun(new InMemoryHistoryProvider(), context);

    assertThat(context.contextMessages()).isEmpty();
  }

  @Test
  void defaultPolicyStoresInputsAndOutputsButNotContext() {
    SessionContext context = contextWith(session("session-1", null, Map.of()), List.of(user("hi")));
    context.addContextMessages("rag", List.of(user("retrieved")));
    context.complete(response("hello"));
    InMemoryHistoryProvider provider = new InMemoryHistoryProvider();

    beforeRun(provider, context);
    afterRun(provider, context);

    assertThat(storedHistory(context, "in_memory"))
        .extracting(Message::text)
        .containsExactly("hi", "hello");
  }

  @Test
  void inputOnlyPolicyStoresOnlyTheCallerInput() {
    SessionContext context = contextWith(session("session-1", null, Map.of()), List.of(user("hi")));
    context.complete(response("hello"));
    InMemoryHistoryProvider provider =
        new InMemoryHistoryProvider(
            HistoryPolicy.builder().storeInputs(true).storeOutputs(false).build());

    afterRun(provider, context);

    assertThat(storedHistory(context, "in_memory")).extracting(Message::text).containsExactly("hi");
  }

  @Test
  void outputOnlyPolicyStoresOnlyTheResponseMessages() {
    SessionContext context = contextWith(session("session-1", null, Map.of()), List.of(user("hi")));
    context.complete(response("hello"));
    InMemoryHistoryProvider provider =
        new InMemoryHistoryProvider(
            HistoryPolicy.builder().storeInputs(false).storeOutputs(true).build());

    afterRun(provider, context);

    assertThat(storedHistory(context, "in_memory"))
        .extracting(Message::text)
        .containsExactly("hello");
  }

  @Test
  void contextStoragePolicyStoresEveryOtherSourceButNotItsOwnLoadedHistory() {
    SessionContext context =
        contextWith(sessionWithHistory("session-1", List.of(user("stored"))), List.of(user("hi")));
    InMemoryHistoryProvider provider =
        new InMemoryHistoryProvider(HistoryPolicy.builder().storeContextMessages(true).build());
    beforeRun(provider, context);
    context.addContextMessages("rag", List.of(user("retrieved")));
    context.addContextMessages("notes", List.of(user("noted")));
    context.complete(response("hello"));

    afterRun(provider, context);

    // "stored" was loaded by this provider and is already history; re-storing it would duplicate
    // the whole conversation on every run.
    assertThat(storedHistory(context, "in_memory"))
        .extracting(Message::text)
        .containsExactly("stored", "retrieved", "noted", "hi", "hello");
  }

  @Test
  void storeContextFromKeepsOnlyTheNamedSource() {
    SessionContext context = contextWith(session("session-1", null, Map.of()), List.of(user("hi")));
    context.addContextMessages("rag", List.of(user("retrieved")));
    context.addContextMessages("notes", List.of(user("noted")));
    context.complete(response("hello"));
    InMemoryHistoryProvider provider =
        new InMemoryHistoryProvider(
            HistoryPolicy.builder()
                .storeContextMessages(true)
                .storeContextFrom("rag")
                .storeInputs(false)
                .storeOutputs(false)
                .build());

    afterRun(provider, context);

    assertThat(storedHistory(context, "in_memory"))
        .extracting(Message::text)
        .containsExactly("retrieved");
  }

  @Test
  void storeContextFromKeepsAMessageItsContributorPreAttributedElsewhere() {
    SessionContext context = contextWith(session("session-1", null, Map.of()), List.of(user("hi")));
    Message fromVectorStore =
        new Message(
            Role.USER,
            List.of(new TextContent("remembered")),
            new MessageAttribution("AIContextProvider", "vector-store", "other-session"),
            JsonObject.empty(),
            null);
    context.addContextMessages("rag", List.of(fromVectorStore));
    InMemoryHistoryProvider provider =
        new InMemoryHistoryProvider(
            HistoryPolicy.builder()
                .storeContextMessages(true)
                .storeContextFrom("rag")
                .storeInputs(false)
                .storeOutputs(false)
                .build());

    afterRun(provider, context);

    // "rag" contributed the message; the cross-session attribution it preserved is content
    // provenance, not the contributing provider, so the configured filter still selects it.
    assertThat(storedHistory(context, "in_memory"))
        .extracting(Message::text)
        .containsExactly("remembered");
  }

  @Test
  void aContextMessageAttributedToThisProviderButContributedByAnotherSourceIsStillStored() {
    SessionContext context = contextWith(session("session-1", null, Map.of()), List.of(user("hi")));
    Message spoofed =
        new Message(
            Role.USER,
            List.of(new TextContent("spoofed")),
            new MessageAttribution("ChatHistory", "in_memory", "other-session"),
            JsonObject.empty(),
            null);
    context.addContextMessages("rag", List.of(spoofed));
    InMemoryHistoryProvider provider =
        new InMemoryHistoryProvider(
            HistoryPolicy.builder()
                .storeContextMessages(true)
                .storeInputs(false)
                .storeOutputs(false)
                .build());

    afterRun(provider, context);

    // Self-exclusion protects against re-storing history this provider loaded. It must key off the
    // contributing provider, otherwise any sibling provider can suppress storage by attribution.
    assertThat(storedHistory(context, "in_memory"))
        .extracting(Message::text)
        .containsExactly("spoofed");
  }

  @Test
  void theProvidersOwnContributedHistoryIsExcludedWithoutASourceFilter() {
    SessionContext context =
        contextWith(sessionWithHistory("session-1", List.of(user("stored"))), List.of(user("hi")));
    InMemoryHistoryProvider provider =
        new InMemoryHistoryProvider(
            HistoryPolicy.builder()
                .storeContextMessages(true)
                .storeInputs(false)
                .storeOutputs(false)
                .build());

    beforeRun(provider, context);
    afterRun(provider, context);

    assertThat(storedHistory(context, "in_memory"))
        .extracting(Message::text)
        .containsExactly("stored");
  }

  @Test
  void aSourceFilterNamingThisProviderStoresItsOwnLoadedHistory() {
    SessionContext context =
        contextWith(sessionWithHistory("session-1", List.of(user("stored"))), List.of(user("hi")));
    InMemoryHistoryProvider provider =
        new InMemoryHistoryProvider(
            HistoryPolicy.builder()
                .storeContextMessages(true)
                .storeContextFrom("in_memory")
                .storeInputs(false)
                .storeOutputs(false)
                .build());

    beforeRun(provider, context);
    afterRun(provider, context);

    // Self-exclusion is the no-filter default, not an override of an explicit selection: a
    // configuration that names this provider gets exactly what it asked for.
    assertThat(storedHistory(context, "in_memory"))
        .extracting(Message::text)
        .containsExactly("stored", "stored");
  }

  @Test
  void interleavedContextSourcesKeepTheirGlobalOrderInTheStoredBatch() {
    SessionContext context = contextWith(session("session-1", null, Map.of()), List.of(user("hi")));
    context.addContextMessages("rag", List.of(user("rag-one")));
    context.addContextMessages("notes", List.of(user("notes-one")));
    context.addContextMessages("rag", List.of(user("rag-two")));
    InMemoryHistoryProvider provider =
        new InMemoryHistoryProvider(
            HistoryPolicy.builder()
                .storeContextMessages(true)
                .storeContextFrom("rag", "notes")
                .storeInputs(false)
                .storeOutputs(false)
                .build());

    afterRun(provider, context);

    assertThat(storedHistory(context, "in_memory"))
        .extracting(Message::text)
        .containsExactly("rag-one", "notes-one", "rag-two");
  }

  @Test
  void contextAddedWithoutAContributingProviderIsStoredOnlyWithoutASourceFilter() {
    SessionContext unfiltered =
        contextWith(session("session-1", null, Map.of()), List.of(user("hi")));
    unfiltered.addContextMessages(List.of(user("external")));
    afterRun(
        new InMemoryHistoryProvider(
            HistoryPolicy.builder()
                .storeContextMessages(true)
                .storeInputs(false)
                .storeOutputs(false)
                .build()),
        unfiltered);

    SessionContext filtered =
        contextWith(session("session-2", null, Map.of()), List.of(user("hi")));
    filtered.addContextMessages(List.of(user("external")));
    afterRun(
        new InMemoryHistoryProvider(
            HistoryPolicy.builder()
                .storeContextMessages(true)
                .storeContextFrom("rag")
                .storeInputs(false)
                .storeOutputs(false)
                .build()),
        filtered);

    assertThat(storedHistory(unfiltered, "in_memory"))
        .extracting(Message::text)
        .containsExactly("external");
    assertThat(filtered.updatedSession().orElseThrow().state()).isEqualTo(SessionState.empty());
  }

  @Test
  void storedHistoryIsReadableAndWritableThroughThePublicStorageOperations() {
    SessionContext context =
        contextWith(sessionWithHistory("session-1", List.of(user("one"))), List.of(user("hi")));
    InMemoryHistoryProvider provider = new InMemoryHistoryProvider();
    ProviderSessionState state = context.providerState(provider.sourceId());

    List<Message> loaded = provider.getMessages(context, state).toCompletableFuture().join();
    provider.saveMessages(context, state, List.of(user("two"))).toCompletableFuture().join();

    assertThat(loaded).extracting(Message::text).containsExactly("one");
    assertThat(provider.getMessages(context, state).toCompletableFuture().join())
        .extracting(Message::text)
        .containsExactly("one", "two");
  }

  @Test
  void aHistoryNamespaceRoundTripsThroughTheDefaultStateCodecRegistry() {
    SessionContext context = contextWith(session("session-1", null, Map.of()), List.of(user("hi")));
    context.complete(response("hello"));
    afterRun(new InMemoryHistoryProvider(), context);
    AgentSession stored = context.updatedSession().orElseThrow();
    StateCodecRegistry registry = StateCodecRegistry.builder().build();

    AgentSession restored = registry.restore(registry.snapshot(stored, 0, Instant.EPOCH));

    SessionContext restoredContext = contextWith(restored, List.of(user("again")));
    assertThat(
            new InMemoryHistoryProvider()
                .getMessages(restoredContext, restoredContext.providerState("in_memory"))
                .toCompletableFuture()
                .join())
        .extracting(Message::text)
        .containsExactly("hi", "hello");
  }

  @Test
  void storeContextFromIsIgnoredWhenContextStorageIsDisabled() {
    SessionContext context = contextWith(session("session-1", null, Map.of()), List.of(user("hi")));
    context.addContextMessages("rag", List.of(user("retrieved")));
    context.complete(response("hello"));
    InMemoryHistoryProvider provider =
        new InMemoryHistoryProvider(
            HistoryPolicy.builder()
                .storeContextMessages(false)
                .storeContextFrom("rag")
                .storeOutputs(false)
                .build());

    afterRun(provider, context);

    assertThat(storedHistory(context, "in_memory")).extracting(Message::text).containsExactly("hi");
  }

  @Test
  void theStoredBatchKeepsContextThenInputThenOutputOrder() {
    SessionContext context =
        contextWith(
            session("session-1", null, Map.of()), List.of(user("input-one"), user("input-two")));
    context.addContextMessages("rag", List.of(user("context-one"), user("context-two")));
    context.complete(response(List.of(assistant("output-one"), assistant("output-two"))));
    InMemoryHistoryProvider provider =
        new InMemoryHistoryProvider(HistoryPolicy.builder().storeContextMessages(true).build());

    afterRun(provider, context);

    assertThat(storedHistory(context, "in_memory"))
        .extracting(Message::text)
        .containsExactly(
            "context-one", "context-two", "input-one", "input-two", "output-one", "output-two");
  }

  @Test
  void loadOnlyPolicyWritesNothingBack() {
    AgentSession session = sessionWithHistory("session-1", List.of(user("one")));
    SessionContext context = contextWith(session, List.of(user("hi")));
    context.complete(response("hello"));
    InMemoryHistoryProvider provider =
        new InMemoryHistoryProvider(
            HistoryPolicy.builder()
                .loadMessages(true)
                .storeInputs(false)
                .storeContextMessages(false)
                .storeOutputs(false)
                .build());

    beforeRun(provider, context);
    afterRun(provider, context);

    assertThat(storedHistory(context, "in_memory"))
        .extracting(Message::text)
        .containsExactly("one");
    assertThat(
            context
                .updatedSession()
                .orElseThrow()
                .state()
                .get(SessionStateKey.of("in_memory", MessageHistory.class))
                .orElseThrow())
        .isSameAs(
            session
                .state()
                .get(SessionStateKey.of("in_memory", MessageHistory.class))
                .orElseThrow());
  }

  @Test
  void storeOutputsWithoutACompletedResponseStoresTheRestOfTheBatch() {
    SessionContext context = contextWith(session("session-1", null, Map.of()), List.of(user("hi")));
    InMemoryHistoryProvider provider = new InMemoryHistoryProvider();

    afterRun(provider, context);

    assertThat(storedHistory(context, "in_memory")).extracting(Message::text).containsExactly("hi");
  }

  @Test
  void storingAppendsToTheHistoryTheSessionAlreadyHeld() {
    SessionContext context =
        contextWith(
            sessionWithHistory("session-1", List.of(user("one"), assistant("two"))),
            List.of(user("three")));
    context.complete(response("four"));
    InMemoryHistoryProvider provider = new InMemoryHistoryProvider();

    beforeRun(provider, context);
    afterRun(provider, context);

    assertThat(storedHistory(context, "in_memory"))
        .extracting(Message::text)
        .containsExactly("one", "two", "three", "four");
  }

  @Test
  void storedHistoryIsImmutableAndNeverMutatesTheSessionItStartedFrom() {
    AgentSession session = sessionWithHistory("session-1", List.of(user("one")));
    SessionContext context = contextWith(session, List.of(user("two")));
    context.complete(response("three"));

    afterRun(new InMemoryHistoryProvider(), context);

    MessageHistory storedBefore =
        session.state().get(SessionStateKey.of("in_memory", MessageHistory.class)).orElseThrow();
    assertThat(storedBefore.messages()).hasSize(1);
    List<Message> storedAfter = storedHistory(context, "in_memory");
    assertThat(storedAfter).hasSize(3);
    assertThatThrownBy(storedAfter::clear).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void twoBranchesOfOneSessionDoNotSeeEachOthersTurns() {
    AgentSession shared = sessionWithHistory("session-1", List.of(user("one")));
    InMemoryHistoryProvider provider = new InMemoryHistoryProvider();

    SessionContext first = contextWith(shared, List.of(user("branch-a")));
    first.complete(response("answer-a"));
    beforeRun(provider, first);
    afterRun(provider, first);

    SessionContext second = contextWith(shared, List.of(user("branch-b")));
    second.complete(response("answer-b"));
    beforeRun(provider, second);
    afterRun(provider, second);

    assertThat(storedHistory(first, "in_memory"))
        .extracting(Message::text)
        .containsExactly("one", "branch-a", "answer-a");
    assertThat(storedHistory(second, "in_memory"))
        .extracting(Message::text)
        .containsExactly("one", "branch-b", "answer-b");
  }

  @Test
  void oneProviderInstanceKeepsTwoSessionsHistoriesSeparate() {
    InMemoryHistoryProvider provider = new InMemoryHistoryProvider();
    AtomicReference<ModelRequest> capturedRequest = new AtomicReference<>();
    AtomicReference<SessionContext> capturedContext = new AtomicReference<>();
    ModelClient client =
        request -> {
          capturedRequest.set(request);
          return completedFuture(modelResponse("hello"));
        };
    AgentEngine engine =
        AgentEngine.builder()
            .modelClient(client)
            .contextProviders(provider, new ContextCapturingProvider(capturedContext))
            .build();

    run(engine, session("session-1", null, Map.of()), "first");
    SessionContext firstContext = capturedContext.get();
    run(engine, session("session-2", null, Map.of()), "second");
    SessionContext secondContext = capturedContext.get();

    assertThat(storedHistory(firstContext, "in_memory"))
        .extracting(Message::text)
        .containsExactly("first", "hello");
    assertThat(storedHistory(secondContext, "in_memory"))
        .extracting(Message::text)
        .containsExactly("second", "hello");
  }

  @Test
  void aRunLoadsHistoryBeforeTheCallerInputAndStoresTheCompletedTurn() {
    InMemoryHistoryProvider provider = new InMemoryHistoryProvider();
    AtomicReference<ModelRequest> capturedRequest = new AtomicReference<>();
    AtomicReference<SessionContext> capturedContext = new AtomicReference<>();
    ModelClient client =
        request -> {
          capturedRequest.set(request);
          return completedFuture(modelResponse("hello"));
        };
    AgentEngine engine =
        AgentEngine.builder()
            .modelClient(client)
            .contextProviders(provider, new ContextCapturingProvider(capturedContext))
            .build();

    run(engine, session("session-1", null, Map.of()), "first");
    AgentSession afterFirstTurn = capturedContext.get().updatedSession().orElseThrow();
    run(engine, afterFirstTurn, "second");

    assertThat(capturedRequest.get().messages())
        .extracting(Message::text)
        .containsExactly("first", "hello", "second");
    assertThat(capturedRequest.get().messages().get(0).attribution())
        .isEqualTo(new MessageAttribution("ChatHistory", "in_memory", "session-1"));
    assertThat(storedHistory(capturedContext.get(), "in_memory"))
        .extracting(Message::text)
        .containsExactly("first", "hello", "second", "hello");
  }

  @Test
  void constructionRejectsABlankSourceId() {
    assertThatThrownBy(() -> new InMemoryHistoryProvider("  ", HistoryPolicy.defaults()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("sourceId must not be blank");
  }

  @Test
  void constructionRejectsANullSourceId() {
    assertThatThrownBy(() -> new InMemoryHistoryProvider(null, HistoryPolicy.defaults()))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("sourceId must not be null");
  }

  @Test
  void constructionRejectsANullPolicy() {
    assertThatThrownBy(() -> new InMemoryHistoryProvider("history", null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("policy must not be null");
  }

  @Test
  void aCustomSourceIdBindsHistoryToItsOwnNamespace() {
    SessionContext context = contextWith(session("session-1", null, Map.of()), List.of(user("hi")));
    context.complete(response("hello"));
    InMemoryHistoryProvider provider =
        new InMemoryHistoryProvider("audit", HistoryPolicy.builder().loadMessages(false).build());

    afterRun(provider, context);

    assertThat(
            context
                .updatedSession()
                .orElseThrow()
                .state()
                .get(SessionStateKey.of("audit", MessageHistory.class)))
        .isPresent();
    assertThat(
            context
                .updatedSession()
                .orElseThrow()
                .state()
                .get(SessionStateKey.of("in_memory", MessageHistory.class)))
        .isEmpty();
    assertThat(storedHistory(context, "audit"))
        .extracting(Message::text)
        .containsExactly("hi", "hello");
  }

  @Test
  void aHistoryNamespaceHoldingSomethingOtherThanAMessageHistoryFails() {
    SessionContext context =
        contextWith(
            session("session-1", null, Map.of("in_memory", "corrupted")), List.of(user("hi")));

    assertThatThrownBy(() -> beforeRun(new InMemoryHistoryProvider(), context))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(
            "history state for source 'in_memory' is not a"
                + " io.github.hellices.agentframework.api.session.MessageHistory");
  }

  @Test
  void aPlainMessageListLeftInTheHistoryNamespaceFails() {
    SessionContext context =
        contextWith(
            session("session-1", null, Map.of("in_memory", List.of(user("one")))),
            List.of(user("hi")));

    assertThatThrownBy(() -> beforeRun(new InMemoryHistoryProvider(), context))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(
            "history state for source 'in_memory' is not a"
                + " io.github.hellices.agentframework.api.session.MessageHistory");
  }

  @Test
  void hooksRejectANullSessionContext() {
    InMemoryHistoryProvider provider = new InMemoryHistoryProvider();
    SessionContext context = contextWith(session("session-1", null, Map.of()), List.of(user("hi")));
    ProviderSessionState state = context.providerState("in_memory");

    assertThatThrownBy(() -> provider.beforeRun(null, state))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("context must not be null");
    assertThatThrownBy(() -> provider.afterRun(null, state))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("context must not be null");
  }

  @Test
  void hooksRejectANullProviderState() {
    InMemoryHistoryProvider provider = new InMemoryHistoryProvider();
    SessionContext context = contextWith(session("session-1", null, Map.of()), List.of(user("hi")));

    assertThatThrownBy(() -> provider.beforeRun(context, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("state must not be null");
    assertThatThrownBy(() -> provider.afterRun(context, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("state must not be null");
  }

  @Test
  void aProviderReturningANullLoadStageFails() {
    SessionContext context = contextWith(session("session-1", null, Map.of()), List.of(user("hi")));
    HistoryProvider provider = new NullStageHistoryProvider();

    assertThatThrownBy(() -> beforeRun(provider, context))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("history provider get-messages stage must not be null");
  }

  @Test
  void aProviderReturningANullSaveStageFails() {
    SessionContext context = contextWith(session("session-1", null, Map.of()), List.of(user("hi")));
    context.complete(response("hello"));
    HistoryProvider provider = new NullStageHistoryProvider();

    assertThatThrownBy(() -> afterRun(provider, context))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("history provider save-messages stage must not be null");
  }

  @Test
  void aProviderLoadingANullMessageListFails() {
    SessionContext context = contextWith(session("session-1", null, Map.of()), List.of(user("hi")));
    HistoryProvider provider = new NullMessagesHistoryProvider(null);

    assertThatThrownBy(() -> beforeRun(provider, context))
        .hasRootCauseInstanceOf(NullPointerException.class)
        .hasRootCauseMessage("history provider messages must not be null");
  }

  @Test
  void aProviderLoadingAListWithANullMessageFails() {
    SessionContext context = contextWith(session("session-1", null, Map.of()), List.of(user("hi")));
    List<Message> messages = new ArrayList<>();
    messages.add(null);
    HistoryProvider provider = new NullMessagesHistoryProvider(messages);

    assertThatThrownBy(() -> beforeRun(provider, context))
        .hasRootCauseInstanceOf(NullPointerException.class)
        .hasRootCauseMessage("history provider messages must not contain null entries");
  }

  @Test
  void theProviderKeepsNoHistoryInItsOwnFields() {
    InMemoryHistoryProvider provider = new InMemoryHistoryProvider();
    SessionContext first = contextWith(session("session-1", null, Map.of()), List.of(user("one")));
    first.complete(response("two"));
    afterRun(provider, first);

    SessionContext second =
        contextWith(session("session-2", null, Map.of()), List.of(user("three")));

    beforeRun(provider, second);

    assertThat(second.contextMessages()).isEmpty();
  }

  private static void beforeRun(HistoryProvider provider, SessionContext context) {
    provider
        .beforeRun(context, context.providerState(provider.sourceId()))
        .toCompletableFuture()
        .join();
  }

  private static void afterRun(HistoryProvider provider, SessionContext context) {
    provider
        .afterRun(context, context.providerState(provider.sourceId()))
        .toCompletableFuture()
        .join();
  }

  private static void run(AgentEngine engine, AgentSession session, String input) {
    engine
        .run(
            request(
                Message.normalize(input),
                session,
                new AgentRunOptions(),
                new CancellationSignal(),
                ContextAttributes.empty()))
        .response()
        .toCompletableFuture()
        .join();
  }

  private static SessionContext contextWith(AgentSession session, List<Message> inputMessages) {
    return new SessionContext(
        session, inputMessages, ContextAttributes.empty(), new CancellationSignal());
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

  private static AgentSession sessionWithHistory(String sessionId, List<Message> history) {
    return session(sessionId, null, Map.of("in_memory", MessageHistory.of(history)));
  }

  private static List<Message> storedHistory(SessionContext context, String sourceId) {
    MessageHistory value =
        context
            .updatedSession()
            .orElseThrow()
            .state()
            .get(SessionStateKey.of(sourceId, MessageHistory.class))
            .orElseThrow();
    return value.messages();
  }

  private static Message user(String text) {
    return new Message(Role.USER, List.of(new TextContent(text)));
  }

  private static Message assistant(String text) {
    return new Message(Role.ASSISTANT, List.of(new TextContent(text)));
  }

  private static AgentResponse response(String text) {
    return response(List.of(assistant(text)));
  }

  private static AgentResponse response(List<? extends Message> messages) {
    return AgentResponse.builder()
        .agentId("agent-1")
        .responseId("response-1")
        .createdAt(Instant.EPOCH)
        .finishReason(FinishReason.STOP)
        .messages(messages)
        .additionalProperties(JsonObject.empty())
        .build();
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

  private static ModelResponse modelResponse(String text) {
    return ModelResponse.builder()
        .messages(List.of(assistant(text)))
        .finishReason(FinishReason.STOP)
        .build();
  }

  /** Captures the run's session context so a test can read the state the run produced. */
  private static final class ContextCapturingProvider implements ContextProvider {

    private final AtomicReference<SessionContext> captured;

    private ContextCapturingProvider(AtomicReference<SessionContext> captured) {
      this.captured = captured;
    }

    @Override
    public String sourceId() {
      return "capture";
    }

    @Override
    public CompletionStage<Void> beforeRun(SessionContext context, ProviderSessionState state) {
      captured.set(context);
      return completedFuture(null);
    }

    @Override
    public CompletionStage<Void> afterRun(SessionContext context, ProviderSessionState state) {
      return completedFuture(null);
    }
  }

  /** A history provider whose storage hooks return no stage at all. */
  private static final class NullStageHistoryProvider extends HistoryProvider {

    private NullStageHistoryProvider() {
      super("broken", HistoryPolicy.defaults());
    }

    @Override
    public CompletionStage<List<Message>> getMessages(
        SessionContext context, ProviderSessionState state) {
      return null;
    }

    @Override
    public CompletionStage<Void> saveMessages(
        SessionContext context, ProviderSessionState state, List<Message> messages) {
      return null;
    }
  }

  /** A history provider that loads a message list the base contract must reject. */
  private static final class NullMessagesHistoryProvider extends HistoryProvider {

    private final List<Message> messages;

    private NullMessagesHistoryProvider(List<Message> messages) {
      super("broken", HistoryPolicy.defaults());
      this.messages = messages;
    }

    @Override
    public CompletionStage<List<Message>> getMessages(
        SessionContext context, ProviderSessionState state) {
      return completedFuture(messages);
    }

    @Override
    public CompletionStage<Void> saveMessages(
        SessionContext context, ProviderSessionState state, List<Message> stored) {
      return completedFuture(null);
    }
  }
}
