package io.github.hellices.agentframework.api.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hellices.agentframework.api.agent.AgentResponse;
import io.github.hellices.agentframework.api.agent.AgentResponseUpdate;
import io.github.hellices.agentframework.api.value.JsonNumber;
import io.github.hellices.agentframework.api.value.JsonObject;
import io.github.hellices.agentframework.api.value.JsonString;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MessageTest {

  @Test
  void roleSupportsKnownAndCustomValues() {
    assertThat(Role.SYSTEM.value()).isEqualTo("system");
    assertThat(Role.of("custom").value()).isEqualTo("custom");
    assertThat(Role.knownValues()).containsExactly("system", "user", "assistant", "tool");
  }

  @Test
  void normalizePreservesNullSafety() {
    assertThatThrownBy(() -> Message.normalize(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("input");

    assertThat(Message.normalize("hello")).hasSize(1);
    assertThat(Message.normalize(List.of(new Message(Role.USER, List.of(new TextContent("hi"))))))
        .hasSize(1);
  }

  @Test
  void textProjectionUsesOnlyTextFragments() {
    Message message =
        new Message(Role.USER, List.of(new TextContent("hello "), new TextContent("world")));

    assertThat(message.text()).isEqualTo("hello world");
  }

  @Test
  void withAttributionCopiesEveryOtherPartOfTheMessage() {
    Object rawRepresentation = new Object();
    JsonObject metadata = JsonObject.builder().put("key", JsonString.of("value")).build();
    Message message =
        new Message(
            Role.ASSISTANT,
            List.of(new TextContent("hello")),
            new MessageAttribution("External", "caller", "origin-session"),
            metadata,
            rawRepresentation);
    MessageAttribution attribution =
        new MessageAttribution("ChatHistory", "in_memory", "origin-session");

    Message stamped = message.withAttribution(attribution);

    assertThat(stamped).isNotSameAs(message);
    assertThat(stamped.attribution()).isEqualTo(attribution);
    assertThat(stamped.role()).isEqualTo(Role.ASSISTANT);
    assertThat(stamped.content()).isEqualTo(message.content());
    assertThat(stamped.additionalProperties()).isEqualTo(metadata);
    assertThat(stamped.rawRepresentation()).isSameAs(rawRepresentation);
    assertThat(message.attribution())
        .isEqualTo(new MessageAttribution("External", "caller", "origin-session"));
  }

  @Test
  void withAttributionRejectsANullAttribution() {
    Message message = new Message(Role.USER, List.of(new TextContent("hi")));
    List<Message> copies = new ArrayList<>();

    assertThatThrownBy(() -> copies.add(message.withAttribution(null)))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("attribution must not be null");
    assertThat(copies).isEmpty();
  }

  @Test
  void messageAndContentUseStructuralEqualityWithTypedMetadata() {
    Object rawRepresentation = new Object();
    JsonObject messageMetadata =
        JsonObject.builder().put("source", JsonString.of("history")).build();
    JsonObject contentMetadata = JsonObject.builder().put("offset", JsonNumber.of(1)).build();
    Message first =
        new Message(
            Role.ASSISTANT,
            List.of(new TextContent("hello", contentMetadata, rawRepresentation)),
            new MessageAttribution("External", "caller", "origin-session"),
            messageMetadata,
            rawRepresentation);
    Message second =
        new Message(
            Role.ASSISTANT,
            List.of(new TextContent("hello", contentMetadata, rawRepresentation)),
            new MessageAttribution("External", "caller", "origin-session"),
            messageMetadata,
            rawRepresentation);

    assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
  }

  @Test
  void usageAndResponseMetadataArePreserved() {
    JsonObject usageMetadata = JsonObject.builder().put("cachedTokens", JsonNumber.of(5L)).build();
    Usage usage = new Usage(10L, 20L, 30L, usageMetadata);
    AgentResponse response =
        AgentResponse.builder()
            .agentId("agent-1")
            .responseId("response-1")
            .messageId("message-1")
            .authorName("assistant")
            .finishReason(FinishReason.STOP)
            .messages(List.of(new Message(Role.ASSISTANT, List.of(new TextContent("hello")))))
            .usage(usage)
            .additionalProperties(JsonObject.empty())
            .build();

    assertThat(response.usage()).isEqualTo(usage);
    assertThat(response.text()).isEqualTo("hello");

    AgentResponseUpdate update =
        AgentResponseUpdate.builder()
            .agentId("agent-1")
            .responseId("response-1")
            .messageId("message-1")
            .authorName("assistant")
            .finishReason(FinishReason.STOP)
            .messages(List.of(new Message(Role.ASSISTANT, List.of(new TextContent("hello")))))
            .usage(usage)
            .additionalProperties(JsonObject.empty())
            .build();

    assertThat(update.text()).isEqualTo("hello");
    assertThat(update.usage()).isEqualTo(new Usage(10L, 20L, 30L, usageMetadata));
  }
}
