package io.github.hellices.agentframework.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hellices.agentframework.api.message.FinishReason;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.MessageAttribution;
import io.github.hellices.agentframework.api.message.Role;
import io.github.hellices.agentframework.api.message.TextContent;
import io.github.hellices.agentframework.api.message.Usage;
import io.github.hellices.agentframework.api.value.JsonNumber;
import io.github.hellices.agentframework.api.value.JsonObject;
import io.github.hellices.agentframework.api.value.JsonString;
import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
  void responseAndUpdateBuildersRoundTripAndReassembleTypedMetadata() {
    JsonObject responseMetadata =
        JsonObject.builder().put("provider", JsonString.of("test")).build();
    JsonObject usageMetadata = JsonObject.builder().put("cachedTokens", JsonNumber.of(3L)).build();
    Instant createdAt = Instant.parse("2026-08-18T00:00:00Z");
    AgentResponse response =
        AgentResponse.builder()
            .agentId("agent-1")
            .responseId("response-1")
            .messageId("message-1")
            .authorName("assistant")
            .createdAt(createdAt)
            .finishReason(FinishReason.STOP)
            .continuationToken("continuation-1")
            .messages(
                List.of(
                    new Message(
                        Role.ASSISTANT,
                        List.of(new TextContent("do"), new TextContent("ne")),
                        new MessageAttribution("source", "assistant", null),
                        JsonObject.empty(),
                        "raw-message")))
            .usage(new Usage(2L, 1L, 3L, usageMetadata))
            .additionalProperties(responseMetadata)
            .rawRepresentation("raw-response")
            .build();
    AgentResponseUpdate update =
        AgentResponseUpdate.builder()
            .agentId("agent-1")
            .responseId("response-1")
            .messageId("message-1")
            .authorName("assistant")
            .createdAt(createdAt)
            .finishReason(FinishReason.STOP)
            .continuationToken("continuation-1")
            .messages(response.messages())
            .usage(response.usage())
            .additionalProperties(responseMetadata)
            .rawRepresentation("raw-response")
            .build();

    assertThat(response.toBuilder().build()).isEqualTo(response).hasSameHashCodeAs(response);
    assertThat(update.toBuilder().build()).isEqualTo(update).hasSameHashCodeAs(update);
    assertThat(AgentResponse.fromUpdates(split(response))).isEqualTo(response);
  }

  @Test
  void usageFragmentsAreSummed() {
    AgentResponse response =
        AgentResponse.fromUpdates(
            List.of(
                update(
                    "message-1",
                    "a",
                    new Usage(
                        2,
                        1,
                        3,
                        JsonObject.builder()
                            .put("cachedTokens", JsonNumber.of(1L))
                            .put("provider", JsonString.of("first"))
                            .build())),
                update(
                    "message-1",
                    "b",
                    new Usage(
                        4,
                        3,
                        7,
                        JsonObject.builder()
                            .put("cachedTokens", JsonNumber.of(new BigDecimal("2.5")))
                            .put("provider", JsonString.of("second"))
                            .build()))));

    assertThat(response.usage())
        .isEqualTo(
            new Usage(
                6,
                4,
                10,
                JsonObject.builder()
                    .put("cachedTokens", JsonNumber.of(new BigDecimal("3.5")))
                    .put("provider", JsonString.of("second"))
                    .build()));
  }

  @Test
  void responseMetadataIsPreservedFromUpdates() {
    AgentResponseUpdate update =
        AgentResponseUpdate.builder()
            .agentId("agent-1")
            .responseId("response-1")
            .messageId("message-1")
            .authorName("custom-author")
            .finishReason(FinishReason.STOP)
            .continuationToken("continuation-1")
            .messages(List.of(new Message(Role.of("critic"), List.of(new TextContent("review")))))
            .additionalProperties(
                JsonObject.builder().put("provider", JsonString.of("test")).build())
            .rawRepresentation("raw")
            .build();

    AgentResponse response = AgentResponse.fromUpdates(List.of(update));

    assertThat(response.agentId()).isEqualTo("agent-1");
    assertThat(response.responseId()).isEqualTo("response-1");
    assertThat(response.authorName()).isEqualTo("custom-author");
    assertThat(response.messages()).extracting(Message::role).containsExactly(Role.of("critic"));
    assertThat(response.finishReason()).isEqualTo(FinishReason.STOP);
    assertThat(response.continuationToken()).isEqualTo("continuation-1");
    assertThat(response.additionalProperties())
        .isEqualTo(JsonObject.builder().put("provider", JsonString.of("test")).build());
    assertThat(response.rawRepresentation()).isEqualTo("raw");
  }

  @Test
  void metadataIsNotLostWhenTheLastUpdateOmitsIt() {
    AgentResponseUpdate first =
        AgentResponseUpdate.builder()
            .agentId("agent-1")
            .responseId("response-1")
            .messageId("message-1")
            .authorName("custom-author")
            .finishReason(FinishReason.STOP)
            .continuationToken("continuation-1")
            .messages(List.of(new Message(Role.ASSISTANT, List.of(new TextContent("hel")))))
            .additionalProperties(JsonObject.builder().put("first", JsonNumber.of(1)).build())
            .rawRepresentation("raw")
            .build();
    AgentResponseUpdate last =
        AgentResponseUpdate.builder()
            .agentId("agent-1")
            .responseId("response-1")
            .messageId("message-1")
            .messages(List.of(new Message(Role.ASSISTANT, List.of(new TextContent("lo")))))
            .additionalProperties(JsonObject.builder().put("last", JsonNumber.of(2)).build())
            .build();

    AgentResponse response = AgentResponse.fromUpdates(List.of(first, last));

    assertThat(response.authorName()).isEqualTo("custom-author");
    assertThat(response.finishReason()).isEqualTo(FinishReason.STOP);
    assertThat(response.continuationToken()).isEqualTo("continuation-1");
    assertThat(response.additionalProperties())
        .isEqualTo(
            JsonObject.builder()
                .put("first", JsonNumber.of(1))
                .put("last", JsonNumber.of(2))
                .build());
    assertThat(response.rawRepresentation()).isEqualTo("raw");
  }

  @Test
  void updatesFromDifferentResponsesCannotBeCombined() {
    AgentResponseUpdate first = update("message-1", Role.ASSISTANT, "first");
    AgentResponseUpdate differentResponse =
        AgentResponseUpdate.builder()
            .agentId("agent-1")
            .responseId("response-2")
            .messageId("message-2")
            .authorName("assistant")
            .finishReason(FinishReason.STOP)
            .messages(List.of(new Message(Role.ASSISTANT, List.of(new TextContent("second")))))
            .additionalProperties(JsonObject.empty())
            .build();

    assertThatThrownBy(() -> AgentResponse.fromUpdates(List.of(first, differentResponse)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("updates must belong to the same agent response");
  }

  @Test
  void distinctMessagesWithinOneUpdateRemainDistinct() {
    AgentResponseUpdate update =
        AgentResponseUpdate.builder()
            .agentId("agent-1")
            .responseId("response-1")
            .messageId("message-1")
            .authorName("assistant")
            .finishReason(FinishReason.STOP)
            .messages(
                List.of(
                    new Message(Role.ASSISTANT, List.of(new TextContent("answer"))),
                    new Message(Role.TOOL, List.of(new TextContent("tool result")))))
            .additionalProperties(JsonObject.empty())
            .build();

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
        AgentResponseUpdate.builder()
            .agentId("agent-1")
            .responseId("response-1")
            .messageId("message-1")
            .authorName("assistant")
            .messages(
                List.of(
                    new Message(Role.ASSISTANT, List.of(new TextContent("answer"))),
                    new Message(Role.TOOL, List.of(new TextContent("tool result")))))
            .additionalProperties(JsonObject.empty())
            .build();
    AgentResponseUpdate continuation =
        AgentResponseUpdate.builder()
            .agentId("agent-1")
            .responseId("response-1")
            .messageId("message-1")
            .authorName("assistant")
            .finishReason(FinishReason.STOP)
            .messages(List.of(new Message(Role.ASSISTANT, List.of(new TextContent("continued")))))
            .additionalProperties(JsonObject.empty())
            .build();

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
                JsonObject.builder()
                    .put("first", JsonNumber.of(1))
                    .put("shared", JsonString.of("old"))
                    .build(),
                "raw-1"));
    AgentResponseUpdate last =
        update(
            new Message(
                Role.ASSISTANT,
                List.of(new TextContent("lo")),
                lastAttribution,
                JsonObject.builder()
                    .put("last", JsonNumber.of(2))
                    .put("shared", JsonString.of("new"))
                    .build(),
                "raw-2"));

    Message message = AgentResponse.fromUpdates(List.of(first, last)).messages().get(0);

    assertThat(message.text()).isEqualTo("hello");
    assertThat(message.attribution()).isEqualTo(lastAttribution);
    assertThat(message.additionalProperties())
        .isEqualTo(
            JsonObject.builder()
                .put("first", JsonNumber.of(1))
                .put("last", JsonNumber.of(2))
                .put("shared", JsonString.of("new"))
                .build());
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
        AgentResponseUpdate.builder()
            .agentId("agent-1")
            .responseId("response-1")
            .messageId("message-2")
            .authorName("assistant")
            .messages(List.of())
            .additionalProperties(JsonObject.empty())
            .build();

    AgentResponse response =
        AgentResponse.fromUpdates(
            List.of(
                update("message-1", Role.ASSISTANT, "first"),
                boundary,
                update(null, Role.ASSISTANT, "second")));

    assertThat(response.messages()).extracting(Message::text).containsExactly("first", "second");
  }

  @Test
  void legacyConstructorsAreRemoved() {
    assertThat(
            findConstructor(
                AgentResponse.class,
                String.class,
                String.class,
                String.class,
                String.class,
                Instant.class,
                FinishReason.class,
                String.class,
                List.class,
                Usage.class,
                java.util.Map.class,
                Object.class))
        .isEmpty();
    assertThat(
            findConstructor(
                AgentResponseUpdate.class,
                String.class,
                String.class,
                String.class,
                String.class,
                Instant.class,
                FinishReason.class,
                String.class,
                List.class,
                Usage.class,
                java.util.Map.class,
                Object.class))
        .isEmpty();
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
    return AgentResponseUpdate.builder()
        .agentId("agent-1")
        .responseId("response-1")
        .messageId(messageId)
        .authorName("assistant")
        .finishReason(FinishReason.STOP)
        .messages(List.of(message))
        .usage(usage)
        .additionalProperties(JsonObject.empty())
        .build();
  }

  private static List<AgentResponseUpdate> split(AgentResponse response) {
    List<AgentResponseUpdate> updates = new ArrayList<>();
    for (Message message : response.messages()) {
      updates.add(
          AgentResponseUpdate.builder()
              .agentId(response.agentId())
              .responseId(response.responseId())
              .messageId(response.messageId())
              .authorName(response.authorName())
              .createdAt(response.createdAt())
              .finishReason(response.finishReason())
              .continuationToken(response.continuationToken())
              .messages(List.of(copy(message)))
              .usage(copy(response.usage()))
              .additionalProperties(response.additionalProperties())
              .rawRepresentation(response.rawRepresentation())
              .build());
    }
    return List.copyOf(updates);
  }

  private static Message copy(Message message) {
    List<io.github.hellices.agentframework.api.message.Content> content = new ArrayList<>();
    for (io.github.hellices.agentframework.api.message.Content item : message.content()) {
      if (item instanceof TextContent text) {
        content.add(
            new TextContent(text.value(), item.additionalProperties(), item.rawRepresentation()));
      } else {
        throw new IllegalArgumentException("unsupported content type in test copy: " + item.type());
      }
    }
    return new Message(
        message.role(),
        content,
        message.attribution(),
        message.additionalProperties(),
        message.rawRepresentation());
  }

  private static Usage copy(Usage usage) {
    if (usage == null) {
      return null;
    }
    return new Usage(
        usage.inputTokens(),
        usage.outputTokens(),
        usage.totalTokens(),
        usage.additionalProperties());
  }

  private static java.util.Optional<Constructor<?>> findConstructor(
      Class<?> type, Class<?>... parameterTypes) {
    try {
      return java.util.Optional.of(type.getDeclaredConstructor(parameterTypes));
    } catch (NoSuchMethodException missing) {
      return java.util.Optional.empty();
    }
  }
}
