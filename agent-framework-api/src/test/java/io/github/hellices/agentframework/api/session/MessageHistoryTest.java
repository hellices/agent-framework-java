package io.github.hellices.agentframework.api.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.Role;
import io.github.hellices.agentframework.api.message.TextContent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class MessageHistoryTest {

  @Test
  void anEmptyHistoryCarriesNoMessages() {
    assertThat(MessageHistory.empty().messages()).isEmpty();
  }

  @Test
  void aHistoryKeepsItsMessagesInOrder() {
    MessageHistory history = MessageHistory.of(List.of(message("first"), message("second")));

    assertThat(history.messages()).extracting(Message::text).containsExactly("first", "second");
  }

  @Test
  void aHistoryDoesNotAliasTheListItWasBuiltFrom() {
    List<Message> source = new ArrayList<>(List.of(message("first")));

    MessageHistory history = MessageHistory.of(source);
    source.add(message("late"));

    assertThat(history.messages()).extracting(Message::text).containsExactly("first");
  }

  @Test
  void aHistoryIsUnmodifiable() {
    MessageHistory history = MessageHistory.of(List.of(message("first")));

    assertThatThrownBy(() -> history.messages().add(message("second")))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void appendReturnsANewHistoryAndLeavesTheOriginalUntouched() {
    MessageHistory history = MessageHistory.of(List.of(message("first")));

    MessageHistory appended = history.append(List.of(message("second")));

    assertThat(history.messages()).extracting(Message::text).containsExactly("first");
    assertThat(appended.messages()).extracting(Message::text).containsExactly("first", "second");
  }

  @Test
  void aNullMessageListIsRejected() {
    assertThatThrownBy(() -> new MessageHistory(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("messages must not be null");
  }

  @Test
  void appendRejectsNullAdditionalMessagesClearly() {
    assertThatThrownBy(() -> assertThat(MessageHistory.empty().append(null)).isNotNull())
        .isInstanceOf(NullPointerException.class)
        .hasMessage("additional messages must not be null");
  }

  @Test
  void aNullMessageEntryIsRejected() {
    List<Message> messages = Arrays.asList(message("first"), null);

    assertThatThrownBy(() -> new MessageHistory(messages))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("messages must not contain null entries");
  }

  @Test
  void aNonMessageEntrySmuggledThroughARawListIsRejected() {
    List<Object> raw = new ArrayList<>();
    raw.add(message("first"));
    raw.add("not-a-message");

    @SuppressWarnings({"unchecked", "rawtypes"})
    List<Message> messages = (List) raw;

    assertThatThrownBy(() -> new MessageHistory(messages))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("messages must only contain Message entries");
  }

  @Test
  void twoHistoriesOverTheSameMessagesAreEqual() {
    Message message = message("first");

    assertThat(MessageHistory.of(List.of(message)))
        .isEqualTo(MessageHistory.of(List.of(message)))
        .hasSameHashCodeAs(MessageHistory.of(List.of(message)));
  }

  private static Message message(String text) {
    return new Message(Role.USER, List.of(new TextContent(text)));
  }
}
