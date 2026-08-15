package io.github.hellices.agentframework.api.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hellices.agentframework.api.agent.AgentResponse;
import io.github.hellices.agentframework.api.agent.CancellationSignal;
import io.github.hellices.agentframework.api.message.FinishReason;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.Role;
import io.github.hellices.agentframework.api.message.TextContent;
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
