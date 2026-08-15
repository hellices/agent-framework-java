package io.github.hellices.agentframework.api.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hellices.agentframework.api.agent.AgentResponse;
import io.github.hellices.agentframework.api.agent.AgentSession;
import io.github.hellices.agentframework.api.agent.CancellationSignal;
import io.github.hellices.agentframework.api.message.FinishReason;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.MessageAttribution;
import io.github.hellices.agentframework.api.message.Role;
import io.github.hellices.agentframework.api.message.TextContent;
import io.github.hellices.agentframework.spi.session.ProviderSessionState;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SessionContextTest {

  @Test
  void responseIsEmptyUntilCompleted() {
    SessionContext sessionContext =
        new SessionContext(null, List.of(), Map.of(), new CancellationSignal());

    assertThat(sessionContext.response()).isEmpty();
  }

  @Test
  void completeFillsTheResponseSlotExactlyOnce() {
    SessionContext sessionContext =
        new SessionContext(null, List.of(), Map.of(), new CancellationSignal());
    AgentResponse response = sampleResponse();

    sessionContext.complete(response);

    assertThat(sessionContext.response()).contains(response);
  }

  @Test
  void completeRejectsASecondCallEvenWithTheSameValue() {
    SessionContext sessionContext =
        new SessionContext(null, List.of(), Map.of(), new CancellationSignal());
    AgentResponse response = sampleResponse();
    sessionContext.complete(response);

    assertThatThrownBy(() -> sessionContext.complete(response))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("session context response is already complete");

    // The set-once slot keeps holding the first value; the rejected second call must not
    // overwrite it or clear it.
    assertThat(sessionContext.response()).contains(response);
  }

  @Test
  void completeRejectsASecondCallWithADifferentValue() {
    SessionContext sessionContext =
        new SessionContext(null, List.of(), Map.of(), new CancellationSignal());
    AgentResponse first = sampleResponse();
    AgentResponse second = sampleResponse();
    sessionContext.complete(first);

    assertThatThrownBy(() -> sessionContext.complete(second))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("session context response is already complete");
    assertThat(sessionContext.response()).contains(first);
  }

  @Test
  void completeRejectsANullResponse() {
    SessionContext sessionContext =
        new SessionContext(null, List.of(), Map.of(), new CancellationSignal());

    assertThatThrownBy(() -> sessionContext.complete(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("response must not be null");
    assertThat(sessionContext.response()).isEmpty();
  }

  @Test
  void normalizesANullCancellationSignalToAFreshOne() {
    SessionContext sessionContext = new SessionContext(null, List.of(), Map.of(), null);

    assertThat(sessionContext.cancellationSignal()).isNotNull();
    assertThat(sessionContext.cancellationSignal().isCancelled()).isFalse();
  }

  @Test
  void normalizedCancellationSignalIsFreshPerInstance() {
    SessionContext first = new SessionContext(null, List.of(), Map.of(), null);
    SessionContext second = new SessionContext(null, List.of(), Map.of(), null);

    assertThat(first.cancellationSignal()).isNotSameAs(second.cancellationSignal());
  }

  @Test
  void attributesAliasesRequestMetadata() {
    Map<String, Object> attributes = Map.of("tenant", "test");
    SessionContext sessionContext =
        new SessionContext(null, List.of(), attributes, new CancellationSignal());

    assertThat(sessionContext.attributes()).isEqualTo(attributes);
    assertThat(sessionContext.attributes()).isSameAs(sessionContext.metadata());
  }

  @Test
  void addContextMessagesAppendsInOrderAcrossMultipleCalls() {
    SessionContext sessionContext =
        new SessionContext(null, List.of(), Map.of(), new CancellationSignal());
    Message first = sampleMessage("first");
    Message second = sampleMessage("second");
    Message third = sampleMessage("third");

    sessionContext.addContextMessages(List.of(first, second));
    sessionContext.addContextMessages(List.of(third));

    assertThat(sessionContext.contextMessages()).containsExactly(first, second, third);
  }

  @Test
  void addContextMessagesCopiesTheCallerListSoLaterMutationsDoNotLeak() {
    SessionContext sessionContext =
        new SessionContext(null, List.of(), Map.of(), new CancellationSignal());
    Message first = sampleMessage("first");
    List<Message> callerList = new ArrayList<>(List.of(first));

    sessionContext.addContextMessages(callerList);
    callerList.add(sampleMessage("second"));

    assertThat(sessionContext.contextMessages()).containsExactly(first);
  }

  @Test
  void contextMessagesReturnsAnImmutableSnapshotThatDoesNotExposeTheBackingList() {
    SessionContext sessionContext =
        new SessionContext(null, List.of(), Map.of(), new CancellationSignal());
    Message first = sampleMessage("first");
    sessionContext.addContextMessages(List.of(first));

    List<Message> snapshot = sessionContext.contextMessages();

    assertThatThrownBy(() -> snapshot.add(sampleMessage("second")))
        .isInstanceOf(UnsupportedOperationException.class);
    // Mutating the previously-returned snapshot must not affect state observed afterwards.
    assertThat(sessionContext.contextMessages()).containsExactly(first);
  }

  @Test
  void addContextMessagesTreatsANullCollectionAsANoOp() {
    SessionContext sessionContext =
        new SessionContext(null, List.of(), Map.of(), new CancellationSignal());
    Message first = sampleMessage("first");
    sessionContext.addContextMessages(List.of(first));

    sessionContext.addContextMessages(null);

    assertThat(sessionContext.contextMessages()).containsExactly(first);
  }

  @Test
  void addContextMessagesRejectsNullEntriesAndLeavesStateUnchanged() {
    SessionContext sessionContext =
        new SessionContext(null, List.of(), Map.of(), new CancellationSignal());
    Message first = sampleMessage("first");
    sessionContext.addContextMessages(List.of(first));
    List<Message> withNullEntry = new ArrayList<>();
    withNullEntry.add(sampleMessage("second"));
    withNullEntry.add(null);

    assertThatThrownBy(() -> sessionContext.addContextMessages(withNullEntry))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("contextMessages must not contain null entries");
    // The rejected call must not partially append entries before the null was found.
    assertThat(sessionContext.contextMessages()).containsExactly(first);
  }

  @Test
  void providerStateStartsFromTheSessionStateNamespaceOfItsOwnSourceId() {
    AgentSession session =
        new AgentSession("session-1", null, Map.of("memory", 7, "history", List.of("kept")));
    SessionContext sessionContext =
        new SessionContext(session, List.of(), Map.of(), new CancellationSignal());

    ProviderSessionState memory = sessionContext.providerState("memory");

    assertThat(memory.sourceId()).isEqualTo("memory");
    assertThat(memory.value()).contains(7);
    assertThat(memory.value(Integer.class)).contains(7);
  }

  @Test
  void providerStateIsTheSameViewForTheSameSourceIdAcrossHookPhases() {
    AgentSession session = new AgentSession("session-1", null, Map.of());
    SessionContext sessionContext =
        new SessionContext(session, List.of(), Map.of(), new CancellationSignal());

    assertThat(sessionContext.providerState("memory"))
        .isSameAs(sessionContext.providerState("memory"));
  }

  @Test
  void providerStateSlotsAreIsolatedBetweenSourceIds() {
    AgentSession session = new AgentSession("session-1", null, Map.of("memory", 7));
    SessionContext sessionContext =
        new SessionContext(session, List.of(), Map.of(), new CancellationSignal());

    ProviderSessionState memory = sessionContext.providerState("memory");
    ProviderSessionState history = sessionContext.providerState("history");
    memory.set(9);

    assertThat(history.value()).isEmpty();
    assertThat(memory.value()).contains(9);
  }

  @Test
  void providerStateRejectsABlankSourceId() {
    SessionContext sessionContext =
        new SessionContext(null, List.of(), Map.of(), new CancellationSignal());

    assertThatThrownBy(() -> sessionContext.providerState("  "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("sourceId must not be blank");
  }

  @Test
  void typedProviderStateValueRejectsAMismatchedType() {
    AgentSession session = new AgentSession("session-1", null, Map.of("memory", "text"));
    SessionContext sessionContext =
        new SessionContext(session, List.of(), Map.of(), new CancellationSignal());

    assertThatThrownBy(() -> sessionContext.providerState("memory").value(Integer.class))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("provider state for source 'memory' is not a java.lang.Integer");
  }

  @Test
  void providerStateStartsEmptyForASessionlessRun() {
    SessionContext sessionContext =
        new SessionContext(null, List.of(), Map.of(), new CancellationSignal());

    assertThat(sessionContext.providerState("memory").value()).isEmpty();
    assertThat(sessionContext.updatedSession()).isEmpty();
  }

  @Test
  void updatedSessionReturnsTheOriginalWhenNoProviderStateWasViewed() {
    AgentSession session = new AgentSession("session-1", null, Map.of("stored", "value"));
    SessionContext sessionContext =
        new SessionContext(session, List.of(), Map.of(), new CancellationSignal());

    assertThat(sessionContext.updatedSession()).containsSame(session);
  }

  @Test
  void updatedSessionReturnsTheOriginalWhenNoViewedSourceCanBePersisted() {
    AgentSession session = new AgentSession("session-1", null, Map.of("stored", "value"));
    SessionContext sessionContext =
        new SessionContext(session, List.of(), Map.of(), new CancellationSignal());
    sessionContext.restrictPersistedSources(List.of());
    sessionContext.providerState("unbound").set("dropped");

    assertThat(sessionContext.updatedSession()).containsSame(session);
  }

  @Test
  void updatedSessionCarriesProviderStateWithoutMutatingTheOriginalSession() {
    AgentSession session =
        new AgentSession("session-1", "service-1", Map.of("memory", 1, "keep", "as-is"));
    SessionContext sessionContext =
        new SessionContext(session, List.of(), Map.of(), new CancellationSignal());

    sessionContext.providerState("memory").set(2);
    AgentSession updated = sessionContext.updatedSession().orElseThrow();

    assertThat(updated.sessionId()).isEqualTo("session-1");
    assertThat(updated.serviceSessionId()).isEqualTo("service-1");
    assertThat(updated.state()).containsEntry("memory", 2).containsEntry("keep", "as-is");
    assertThat(session.state()).containsEntry("memory", 1);
  }

  @Test
  void updatedSessionDropsAClearedProviderNamespace() {
    AgentSession session =
        new AgentSession("session-1", null, Map.of("memory", 1, "keep", "as-is"));
    SessionContext sessionContext =
        new SessionContext(session, List.of(), Map.of(), new CancellationSignal());

    sessionContext.providerState("memory").clear();

    assertThat(sessionContext.updatedSession().orElseThrow().state())
        .doesNotContainKey("memory")
        .containsEntry("keep", "as-is");
  }

  @Test
  void providerStateSetRejectsANullValue() {
    SessionContext sessionContext =
        new SessionContext(null, List.of(), Map.of(), new CancellationSignal());
    ProviderSessionState state = sessionContext.providerState("memory");

    assertThatThrownBy(() -> state.set(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("value must not be null");
  }

  @Test
  void sourceBoundContextMessagesAreStampedWithTheProviderAttribution() {
    AgentSession session = new AgentSession("session-1", null, Map.of());
    SessionContext sessionContext =
        new SessionContext(session, List.of(), Map.of(), new CancellationSignal());

    sessionContext.addContextMessages("memory", List.of(sampleMessage("remembered")));

    assertThat(sessionContext.contextMessages())
        .extracting(Message::attribution)
        .containsExactly(new MessageAttribution("AIContextProvider", "memory", "session-1"));
  }

  @Test
  void sourceBoundContextMessagesUseThePinnedProviderSourceTypeName() {
    SessionContext sessionContext =
        new SessionContext(null, List.of(), Map.of(), new CancellationSignal());

    sessionContext.addContextMessages("memory", List.of(sampleMessage("remembered")));

    // The pinned snapshot's known source types are External, AIContextProvider, and ChatHistory;
    // a provider contribution must be readable as the upstream name, not a Java-only alias.
    assertThat(sessionContext.contextMessages())
        .extracting(message -> message.attribution().sourceType())
        .containsExactly("AIContextProvider");
  }

  @Test
  void sourceBoundContextMessagesTreatABlankExistingSourceIdAsAbsent() {
    AgentSession session = new AgentSession("session-1", null, Map.of());
    SessionContext sessionContext =
        new SessionContext(session, List.of(), Map.of(), new CancellationSignal());
    Message blankSource =
        new Message(
            Role.USER,
            List.of(new TextContent("remembered")),
            new MessageAttribution("Memory", "   ", "origin-9"),
            Map.of(),
            null);

    sessionContext.addContextMessages("memory", List.of(blankSource));

    assertThat(sessionContext.contextMessages())
        .extracting(Message::attribution)
        .containsExactly(new MessageAttribution("Memory", "memory", "origin-9"));
  }

  @Test
  void sourceBoundContextMessagesKeepAnExplicitProviderAttribution() {
    SessionContext sessionContext =
        new SessionContext(null, List.of(), Map.of(), new CancellationSignal());
    Message attributed =
        new Message(
            Role.USER,
            List.of(new TextContent("remembered")),
            new MessageAttribution("Memory", "other-session-source", "origin-9"),
            Map.of(),
            null);

    sessionContext.addContextMessages("memory", List.of(attributed));

    assertThat(sessionContext.contextMessages()).containsExactly(attributed);
  }

  @Test
  void sourceBoundContextMessagesKeepAPreExistingOriginSessionId() {
    AgentSession session = new AgentSession("session-1", null, Map.of());
    SessionContext sessionContext =
        new SessionContext(session, List.of(), Map.of(), new CancellationSignal());
    Message retrievedElsewhere =
        new Message(
            Role.USER,
            List.of(new TextContent("remembered")),
            new MessageAttribution("Memory", null, "origin-9"),
            Map.of(),
            null);

    sessionContext.addContextMessages("memory", List.of(retrievedElsewhere));

    assertThat(sessionContext.contextMessages())
        .extracting(Message::attribution)
        .containsExactly(new MessageAttribution("Memory", "memory", "origin-9"));
  }

  @Test
  void sourceBoundContextMessagesUseThisRunsSessionIdOnlyWhenNoOriginIsCarried() {
    AgentSession session = new AgentSession("session-1", null, Map.of());
    SessionContext sessionContext =
        new SessionContext(session, List.of(), Map.of(), new CancellationSignal());
    Message withoutOrigin =
        new Message(
            Role.USER,
            List.of(new TextContent("remembered")),
            new MessageAttribution("Memory", null, null),
            Map.of(),
            null);

    sessionContext.addContextMessages("memory", List.of(withoutOrigin));

    assertThat(sessionContext.contextMessages())
        .extracting(Message::attribution)
        .containsExactly(new MessageAttribution("Memory", "memory", "session-1"));
  }

  @Test
  void sourceBoundContextMessagesKeepAPreExistingOriginSessionIdOnASessionlessRun() {
    SessionContext sessionContext =
        new SessionContext(null, List.of(), Map.of(), new CancellationSignal());
    Message retrievedElsewhere =
        new Message(
            Role.USER,
            List.of(new TextContent("remembered")),
            new MessageAttribution("Memory", null, "origin-9"),
            Map.of(),
            null);

    sessionContext.addContextMessages("memory", List.of(retrievedElsewhere));

    assertThat(sessionContext.contextMessages())
        .extracting(Message::attribution)
        .containsExactly(new MessageAttribution("Memory", "memory", "origin-9"));
  }

  @Test
  void sourceBoundContextMessagesRejectABlankSourceId() {
    SessionContext sessionContext =
        new SessionContext(null, List.of(), Map.of(), new CancellationSignal());
    List<Message> messages = List.of(sampleMessage("remembered"));

    assertThatThrownBy(() -> sessionContext.addContextMessages("", messages))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("sourceId must not be blank");
  }

  @Test
  void contextContributionsRecordTheContributingProviderInGlobalOrder() {
    SessionContext sessionContext =
        new SessionContext(null, List.of(), Map.of(), new CancellationSignal());

    sessionContext.addContextMessages("rag", List.of(sampleMessage("retrieved")));
    sessionContext.addContextMessages("notes", List.of(sampleMessage("noted")));
    sessionContext.addContextMessages("rag", List.of(sampleMessage("retrieved-again")));

    assertThat(sessionContext.contextContributions())
        .extracting(ContextMessageContribution::sourceId)
        .containsExactly("rag", "notes", "rag");
    assertThat(sessionContext.contextContributions())
        .extracting(contribution -> contribution.message().text())
        .containsExactly("retrieved", "noted", "retrieved-again");
    assertThat(sessionContext.contextMessages())
        .containsExactlyElementsOf(
            sessionContext.contextContributions().stream()
                .map(ContextMessageContribution::message)
                .toList());
  }

  @Test
  void aContributionKeepsItsContributorEvenWhenTheMessageCarriesAnotherAttribution() {
    SessionContext sessionContext =
        new SessionContext(null, List.of(), Map.of(), new CancellationSignal());
    Message preAttributed =
        new Message(
            Role.USER,
            List.of(new TextContent("remembered")),
            new MessageAttribution("AIContextProvider", "vector-store", "other-session"),
            Map.of(),
            null);

    sessionContext.addContextMessages("rag", List.of(preAttributed));

    // Attribution is content provenance and may be preserved across sessions; the contribution
    // records which provider of this run actually handed the message over.
    assertThat(sessionContext.contextContributions())
        .singleElement()
        .satisfies(
            contribution -> {
              assertThat(contribution.sourceId()).isEqualTo("rag");
              assertThat(contribution.message().attribution().sourceId()).isEqualTo("vector-store");
            });
  }

  @Test
  void theUnattributedHookContributesWithoutAContributingProvider() {
    SessionContext sessionContext =
        new SessionContext(null, List.of(), Map.of(), new CancellationSignal());

    sessionContext.addContextMessages(List.of(sampleMessage("external")));

    assertThat(sessionContext.contextContributions())
        .singleElement()
        .extracting(ContextMessageContribution::sourceId)
        .isNull();
  }

  @Test
  void contextContributionsReturnAnImmutableSnapshot() {
    SessionContext sessionContext =
        new SessionContext(null, List.of(), Map.of(), new CancellationSignal());
    sessionContext.addContextMessages("rag", List.of(sampleMessage("retrieved")));

    List<ContextMessageContribution> snapshot = sessionContext.contextContributions();

    assertThatThrownBy(
            () -> snapshot.add(new ContextMessageContribution("rag", sampleMessage("other"))))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThat(sessionContext.contextContributions()).hasSize(1);
  }

  @Test
  void aContributionRejectsANullMessageAndABlankSource() {
    Message message = sampleMessage("retrieved");

    assertThatThrownBy(() -> new ContextMessageContribution("rag", null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("message must not be null");
    assertThatThrownBy(() -> new ContextMessageContribution("  ", message))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("sourceId must not be blank");
  }

  @Test
  void hydrateReplacesTheSessionAndRecordsThePersistenceMetadata() {
    AgentSession requested =
        new AgentSession("session-1", null, Map.of("history", List.of("request")));
    SessionContext sessionContext =
        new SessionContext(requested, List.of(), Map.of(), new CancellationSignal());
    AgentSession stored =
        new AgentSession("session-1", "service-1", Map.of("history", List.of("stored")));

    sessionContext.hydrate(
        stored, new SessionSnapshotMetadata(7, Instant.parse("2026-01-01T00:00:00Z")));

    assertThat(sessionContext.session()).isSameAs(stored);
    assertThat(sessionContext.snapshotMetadata())
        .contains(new SessionSnapshotMetadata(7, Instant.parse("2026-01-01T00:00:00Z")));
    assertThat(sessionContext.providerState("history").value()).contains(List.of("stored"));
  }

  @Test
  void hydrateAcceptsAStoredServiceHandleWhenTheRequestDeclaredNone() {
    SessionContext sessionContext =
        new SessionContext(
            new AgentSession("session-1", null, Map.of()),
            List.of(),
            Map.of(),
            new CancellationSignal());
    AgentSession stored = new AgentSession("session-1", "service-1", Map.of());

    sessionContext.hydrate(
        stored, new SessionSnapshotMetadata(0, Instant.parse("2026-01-01T00:00:00Z")));

    assertThat(sessionContext.session().serviceSessionId()).isEqualTo("service-1");
  }

  @Test
  void hydrateAcceptsAStoredServiceHandleThatMatchesTheRequestedOne() {
    SessionContext sessionContext =
        new SessionContext(
            new AgentSession("session-1", "service-1", Map.of()),
            List.of(),
            Map.of(),
            new CancellationSignal());
    AgentSession stored = new AgentSession("session-1", "service-1", Map.of("history", "stored"));

    sessionContext.hydrate(
        stored, new SessionSnapshotMetadata(0, Instant.parse("2026-01-01T00:00:00Z")));

    assertThat(sessionContext.session()).isSameAs(stored);
  }

  @Test
  void hydrateRejectsAStoredSessionThatDropsTheRequestedServiceHandle() {
    SessionContext sessionContext =
        new SessionContext(
            new AgentSession("session-1", "service-9", Map.of()),
            List.of(),
            Map.of(),
            new CancellationSignal());
    AgentSession stored = new AgentSession("session-1", null, Map.of());
    SessionSnapshotMetadata metadata =
        new SessionSnapshotMetadata(0, Instant.parse("2026-01-01T00:00:00Z"));

    assertThatThrownBy(() -> sessionContext.hydrate(stored, metadata))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("hydrated service session id must be service-9");
    assertThat(sessionContext.session().serviceSessionId()).isEqualTo("service-9");
    assertThat(sessionContext.snapshotMetadata()).isEmpty();
  }

  @Test
  void hydrateRejectsAStoredSessionWithADifferentServiceHandle() {
    SessionContext sessionContext =
        new SessionContext(
            new AgentSession("session-1", "service-a", Map.of()),
            List.of(),
            Map.of(),
            new CancellationSignal());
    AgentSession stored = new AgentSession("session-1", "service-b", Map.of());
    SessionSnapshotMetadata metadata =
        new SessionSnapshotMetadata(0, Instant.parse("2026-01-01T00:00:00Z"));

    assertThatThrownBy(() -> sessionContext.hydrate(stored, metadata))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("hydrated service session id must be service-a");
    assertThat(sessionContext.session().serviceSessionId()).isEqualTo("service-a");
    assertThat(sessionContext.snapshotMetadata()).isEmpty();
  }

  @Test
  void snapshotMetadataIsEmptyUntilHydrated() {
    SessionContext sessionContext =
        new SessionContext(
            new AgentSession("session-1", null, Map.of()),
            List.of(),
            Map.of(),
            new CancellationSignal());

    assertThat(sessionContext.snapshotMetadata()).isEmpty();
  }

  @Test
  void hydrateRejectsASessionWithADifferentIdentity() {
    SessionContext sessionContext =
        new SessionContext(
            new AgentSession("session-1", null, Map.of()),
            List.of(),
            Map.of(),
            new CancellationSignal());
    AgentSession other = new AgentSession("session-2", null, Map.of());
    SessionSnapshotMetadata metadata =
        new SessionSnapshotMetadata(0, Instant.parse("2026-01-01T00:00:00Z"));

    assertThatThrownBy(() -> sessionContext.hydrate(other, metadata))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("hydrated session id must be session-1");
  }

  @Test
  void hydrateHappensAtMostOncePerRun() {
    SessionContext sessionContext =
        new SessionContext(
            new AgentSession("session-1", null, Map.of()),
            List.of(),
            Map.of(),
            new CancellationSignal());
    AgentSession stored = new AgentSession("session-1", null, Map.of());
    SessionSnapshotMetadata metadata =
        new SessionSnapshotMetadata(0, Instant.parse("2026-01-01T00:00:00Z"));
    sessionContext.hydrate(stored, metadata);

    assertThatThrownBy(() -> sessionContext.hydrate(stored, metadata))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("session context is already hydrated");
  }

  @Test
  void hydrateIsRejectedOnceAProviderStateViewExists() {
    SessionContext sessionContext =
        new SessionContext(
            new AgentSession("session-1", null, Map.of()),
            List.of(),
            Map.of(),
            new CancellationSignal());
    sessionContext.providerState("history");
    AgentSession stored = new AgentSession("session-1", null, Map.of());
    SessionSnapshotMetadata metadata =
        new SessionSnapshotMetadata(0, Instant.parse("2026-01-01T00:00:00Z"));

    assertThatThrownBy(() -> sessionContext.hydrate(stored, metadata))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("session context state views already exist");
  }

  @Test
  void hydrateRejectsANullSessionAndNullMetadata() {
    SessionContext sessionContext =
        new SessionContext(
            new AgentSession("session-1", null, Map.of()),
            List.of(),
            Map.of(),
            new CancellationSignal());
    AgentSession stored = new AgentSession("session-1", null, Map.of());
    SessionSnapshotMetadata metadata =
        new SessionSnapshotMetadata(0, Instant.parse("2026-01-01T00:00:00Z"));

    assertThatThrownBy(() -> sessionContext.hydrate(null, metadata))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("session must not be null");
    assertThatThrownBy(() -> sessionContext.hydrate(stored, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("metadata must not be null");
  }

  @Test
  void hydrateIsRejectedForASessionlessRun() {
    SessionContext sessionContext =
        new SessionContext(null, List.of(), Map.of(), new CancellationSignal());
    AgentSession stored = new AgentSession("session-1", null, Map.of());
    SessionSnapshotMetadata metadata =
        new SessionSnapshotMetadata(0, Instant.parse("2026-01-01T00:00:00Z"));

    assertThatThrownBy(() -> sessionContext.hydrate(stored, metadata))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("a sessionless run cannot be hydrated");
  }

  @Test
  void anUnrestrictedContextWritesBackEveryTouchedNamespace() {
    SessionContext sessionContext =
        new SessionContext(
            new AgentSession("session-1", null, Map.of()),
            List.of(),
            Map.of(),
            new CancellationSignal());

    sessionContext.providerState("bound").set("kept");
    sessionContext.providerState("sibling").set("kept too");

    assertThat(sessionContext.updatedSession().orElseThrow().state())
        .containsOnlyKeys("bound", "sibling");
  }

  @Test
  void restrictPersistedSourcesDropsWritesToUnboundNamespaces() {
    SessionContext sessionContext =
        new SessionContext(
            new AgentSession("session-1", null, Map.of("stored", "untouched")),
            List.of(),
            Map.of(),
            new CancellationSignal());
    sessionContext.restrictPersistedSources(List.of("bound"));

    sessionContext.providerState("bound").set("kept");
    sessionContext.providerState("sibling").set("dropped");

    assertThat(sessionContext.updatedSession().orElseThrow().state())
        .containsExactlyInAnyOrderEntriesOf(Map.of("stored", "untouched", "bound", "kept"));
  }

  @Test
  void restrictPersistedSourcesAlsoDropsClearsOfUnboundNamespaces() {
    SessionContext sessionContext =
        new SessionContext(
            new AgentSession("session-1", null, Map.of("sibling", "untouched")),
            List.of(),
            Map.of(),
            new CancellationSignal());
    sessionContext.restrictPersistedSources(List.of("bound"));

    sessionContext.providerState("sibling").clear();

    assertThat(sessionContext.updatedSession().orElseThrow().state())
        .containsExactlyInAnyOrderEntriesOf(Map.of("sibling", "untouched"));
  }

  @Test
  void anEmptyRestrictionPersistsNoProviderWrites() {
    SessionContext sessionContext =
        new SessionContext(
            new AgentSession("session-1", null, Map.of()),
            List.of(),
            Map.of(),
            new CancellationSignal());
    sessionContext.restrictPersistedSources(List.of());

    sessionContext.providerState("bound").set("dropped");

    assertThat(sessionContext.updatedSession().orElseThrow().state()).isEmpty();
  }

  @Test
  void restrictPersistedSourcesHappensAtMostOnceAndBeforeAnyStateView() {
    SessionContext sessionContext =
        new SessionContext(
            new AgentSession("session-1", null, Map.of()),
            List.of(),
            Map.of(),
            new CancellationSignal());
    sessionContext.restrictPersistedSources(List.of("bound"));

    assertThatThrownBy(() -> sessionContext.restrictPersistedSources(List.of("other")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("session context persisted sources are already restricted");

    SessionContext viewed =
        new SessionContext(
            new AgentSession("session-1", null, Map.of()),
            List.of(),
            Map.of(),
            new CancellationSignal());
    viewed.providerState("bound");

    assertThatThrownBy(() -> viewed.restrictPersistedSources(List.of("bound")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("session context state views already exist");
  }

  @Test
  void restrictPersistedSourcesRejectsNullAndBlankSourceIds() {
    SessionContext sessionContext =
        new SessionContext(
            new AgentSession("session-1", null, Map.of()),
            List.of(),
            Map.of(),
            new CancellationSignal());
    List<String> blank = List.of("  ");

    assertThatThrownBy(() -> sessionContext.restrictPersistedSources(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("sourceIds must not be null");
    assertThatThrownBy(() -> sessionContext.restrictPersistedSources(blank))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("sourceId must not be blank");
  }

  private static Message sampleMessage(String text) {
    return new Message(Role.ASSISTANT, List.of(new TextContent(text)));
  }

  private static AgentResponse sampleResponse() {
    return new AgentResponse(
        "agent-1",
        "response-1",
        "message-1",
        "agent",
        null,
        FinishReason.STOP,
        List.of(new Message(Role.ASSISTANT, List.of(new TextContent("ok")))),
        null,
        Map.of(),
        null);
  }
}
