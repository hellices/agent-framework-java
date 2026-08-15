package io.github.hellices.agentframework.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hellices.agentframework.api.message.FinishReason;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.MessageAttribution;
import io.github.hellices.agentframework.api.message.Role;
import io.github.hellices.agentframework.api.message.TextContent;
import io.github.hellices.agentframework.api.message.Usage;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentResponseAssemblyTest {

  @Test
  void updatesWithTheSameMessageIdFormOneMessage() {
    AgentResponse response =
        AgentResponse.fromUpdates(
            List.of(
                update("message-1", Role.ASSISTANT, "hel"),
                update("message-1", Role.ASSISTANT, "lo")));

    assertThat(response.messages()).hasSize(1);
    assertThat(response.text()).isEqualTo("hello");
  }

  @Test
  void roleChangeStartsANewMessageWhenMessageIdIsAbsent() {
    AgentResponse response =
        AgentResponse.fromUpdates(
            List.of(update(null, Role.ASSISTANT, "answer"), update(null, Role.USER, "follow-up")));

    assertThat(response.messages())
        .extracting(Message::role)
        .containsExactly(Role.ASSISTANT, Role.USER);
  }

  @Test
  void usageFragmentsAreSummed() {
    AgentResponse response =
        AgentResponse.fromUpdates(
            List.of(
                update("message-1", "a", new Usage(2, 1, 3, Map.of("cachedTokens", 1L))),
                update("message-1", "b", new Usage(4, 3, 7, Map.of("cachedTokens", 2L)))));

    assertThat(response.usage()).isEqualTo(new Usage(6, 4, 10, Map.of("cachedTokens", 3L)));
  }

  @Test
  void responseMetadataIsPreservedFromUpdates() {
    AgentResponseUpdate update =
        new AgentResponseUpdate(
            "agent-1",
            "response-1",
            "message-1",
            "custom-author",
            null,
            FinishReason.STOP,
            "continuation-1",
            List.of(new Message(Role.of("critic"), List.of(new TextContent("review")))),
            null,
            Map.of("provider", "test"),
            "raw");

    AgentResponse response = AgentResponse.fromUpdates(List.of(update));

    assertThat(response.agentId()).isEqualTo("agent-1");
    assertThat(response.responseId()).isEqualTo("response-1");
    assertThat(response.authorName()).isEqualTo("custom-author");
    assertThat(response.messages()).extracting(Message::role).containsExactly(Role.of("critic"));
    assertThat(response.finishReason()).isEqualTo(FinishReason.STOP);
    assertThat(response.continuationToken()).isEqualTo("continuation-1");
    assertThat(response.additionalProperties()).containsEntry("provider", "test");
    assertThat(response.rawRepresentation()).isEqualTo("raw");
  }

  @Test
  void metadataIsNotLostWhenTheLastUpdateOmitsIt() {
    AgentResponseUpdate first =
        new AgentResponseUpdate(
            "agent-1",
            "response-1",
            "message-1",
            "custom-author",
            null,
            FinishReason.STOP,
            "continuation-1",
            List.of(new Message(Role.ASSISTANT, List.of(new TextContent("hel")))),
            null,
            Map.of("first", 1),
            "raw");
    AgentResponseUpdate last =
        new AgentResponseUpdate(
            "agent-1",
            "response-1",
            "message-1",
            null,
            null,
            null,
            null,
            List.of(new Message(Role.ASSISTANT, List.of(new TextContent("lo")))),
            null,
            Map.of("last", 2),
            null);

    AgentResponse response = AgentResponse.fromUpdates(List.of(first, last));

    assertThat(response.authorName()).isEqualTo("custom-author");
    assertThat(response.finishReason()).isEqualTo(FinishReason.STOP);
    assertThat(response.continuationToken()).isEqualTo("continuation-1");
    assertThat(response.additionalProperties())
        .containsExactlyInAnyOrderEntriesOf(Map.of("first", 1, "last", 2));
    assertThat(response.rawRepresentation()).isEqualTo("raw");
  }

  @Test
  void updatesFromDifferentResponsesCannotBeCombined() {
    AgentResponseUpdate first = update("message-1", Role.ASSISTANT, "first");
    AgentResponseUpdate differentResponse =
        new AgentResponseUpdate(
            "agent-1",
            "response-2",
            "message-2",
            "assistant",
            null,
            FinishReason.STOP,
            List.of(new Message(Role.ASSISTANT, List.of(new TextContent("second")))),
            null,
            Map.of(),
            null);

    assertThatThrownBy(() -> AgentResponse.fromUpdates(List.of(first, differentResponse)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("updates must belong to the same agent response");
  }

  @Test
  void distinctMessagesWithinOneUpdateRemainDistinct() {
    AgentResponseUpdate update =
        new AgentResponseUpdate(
            "agent-1",
            "response-1",
            "message-1",
            "assistant",
            null,
            FinishReason.STOP,
            List.of(
                new Message(Role.ASSISTANT, List.of(new TextContent("answer"))),
                new Message(Role.TOOL, List.of(new TextContent("tool result")))),
            null,
            Map.of(),
            null);

    AgentResponse response = AgentResponse.fromUpdates(List.of(update));

    assertThat(response.messages())
        .extracting(Message::role)
        .containsExactly(Role.ASSISTANT, Role.TOOL);
    assertThat(response.messages())
        .extracting(Message::text)
        .containsExactly("answer", "tool result");
  }

  @Test
  void matchingMessageIdDoesNotMergeAcrossRoles() {
    AgentResponseUpdate first =
        new AgentResponseUpdate(
            "agent-1",
            "response-1",
            "message-1",
            "assistant",
            null,
            null,
            List.of(
                new Message(Role.ASSISTANT, List.of(new TextContent("answer"))),
                new Message(Role.TOOL, List.of(new TextContent("tool result")))),
            null,
            Map.of(),
            null);
    AgentResponseUpdate continuation =
        new AgentResponseUpdate(
            "agent-1",
            "response-1",
            "message-1",
            "assistant",
            null,
            FinishReason.STOP,
            List.of(new Message(Role.ASSISTANT, List.of(new TextContent("continued")))),
            null,
            Map.of(),
            null);

    AgentResponse response = AgentResponse.fromUpdates(List.of(first, continuation));

    assertThat(response.messages())
        .extracting(Message::role)
        .containsExactly(Role.ASSISTANT, Role.TOOL, Role.ASSISTANT);
    assertThat(response.messages())
        .extracting(Message::text)
        .containsExactly("answer", "tool result", "continued");
  }

  @Test
  void mergedMessageFragmentsPreserveMetadata() {
    MessageAttribution firstAttribution = new MessageAttribution("source", "first", null);
    MessageAttribution lastAttribution = new MessageAttribution("source", "last", null);
    AgentResponseUpdate first =
        update(
            new Message(
                Role.ASSISTANT,
                List.of(new TextContent("hel")),
                firstAttribution,
                Map.of("first", 1, "shared", "old"),
                "raw-1"));
    AgentResponseUpdate last =
        update(
            new Message(
                Role.ASSISTANT,
                List.of(new TextContent("lo")),
                lastAttribution,
                Map.of("last", 2, "shared", "new"),
                "raw-2"));

    Message message = AgentResponse.fromUpdates(List.of(first, last)).messages().get(0);

    assertThat(message.text()).isEqualTo("hello");
    assertThat(message.attribution()).isEqualTo(lastAttribution);
    assertThat(message.additionalProperties())
        .containsExactlyInAnyOrderEntriesOf(Map.of("first", 1, "last", 2, "shared", "new"));
    assertThat(message.rawRepresentation()).isEqualTo("raw-2");
  }

  @Test
  void absentMessageIdContinuesTheCurrentRole() {
    AgentResponse response =
        AgentResponse.fromUpdates(
            List.of(
                update("message-1", Role.ASSISTANT, "Hel"),
                update(null, Role.ASSISTANT, "lo"),
                update(null, Role.ASSISTANT, "!")));

    assertThat(response.messages()).singleElement().extracting(Message::text).isEqualTo("Hello!");
  }

  @Test
  void metadataOnlyUpdateAdvancesTheMessageBoundary() {
    AgentResponseUpdate boundary =
        new AgentResponseUpdate(
            "agent-1",
            "response-1",
            "message-2",
            "assistant",
            null,
            null,
            List.of(),
            null,
            Map.of(),
            null);

    AgentResponse response =
        AgentResponse.fromUpdates(
            List.of(
                update("message-1", Role.ASSISTANT, "first"),
                boundary,
                update(null, Role.ASSISTANT, "second")));

    assertThat(response.messages()).extracting(Message::text).containsExactly("first", "second");
  }

  private static AgentResponseUpdate update(String messageId, Role role, String text) {
    return update(messageId, role, text, null);
  }

  private static AgentResponseUpdate update(String messageId, String text, Usage usage) {
    return update(messageId, Role.ASSISTANT, text, usage);
  }

  private static AgentResponseUpdate update(String messageId, Role role, String text, Usage usage) {
    return update(messageId, new Message(role, List.of(new TextContent(text))), usage);
  }

  private static AgentResponseUpdate update(Message message) {
    return update("message-1", message, null);
  }

  private static AgentResponseUpdate update(String messageId, Message message, Usage usage) {
    return new AgentResponseUpdate(
        "agent-1",
        "response-1",
        messageId,
        "assistant",
        null,
        FinishReason.STOP,
        List.of(message),
        usage,
        Map.of(),
        null);
  }
}
