package io.github.hellices.agentframework.api.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hellices.agentframework.api.agent.AgentResponse;
import io.github.hellices.agentframework.api.agent.AgentResponseUpdate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    Message message =
        new Message(
            Role.ASSISTANT,
            List.of(new TextContent("hello")),
            new MessageAttribution("External", "caller", "origin-session"),
            Map.of("key", "value"),
            rawRepresentation);
    MessageAttribution attribution =
        new MessageAttribution("ChatHistory", "in_memory", "origin-session");

    Message stamped = message.withAttribution(attribution);

    assertThat(stamped).isNotSameAs(message);
    assertThat(stamped.attribution()).isEqualTo(attribution);
    assertThat(stamped.role()).isEqualTo(Role.ASSISTANT);
    assertThat(stamped.content()).isEqualTo(message.content());
    assertThat(stamped.additionalProperties()).isEqualTo(Map.of("key", "value"));
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
  void usageAndResponseMetadataArePreserved() {
    Usage usage = new Usage(10L, 20L, 30L);
    AgentResponse response =
        new AgentResponse(
            "agent-1",
            "response-1",
            "message-1",
            "assistant",
            null,
            FinishReason.STOP,
            List.of(new Message(Role.ASSISTANT, List.of(new TextContent("hello")))),
            usage,
            null,
            null);

    assertThat(response.usage()).isEqualTo(usage);
    assertThat(response.text()).isEqualTo("hello");

    AgentResponseUpdate update =
        new AgentResponseUpdate(
            "agent-1",
            "response-1",
            "message-1",
            "assistant",
            null,
            FinishReason.STOP,
            List.of(new Message(Role.ASSISTANT, List.of(new TextContent("hello")))),
            usage,
            null,
            null);

    assertThat(update.text()).isEqualTo("hello");
  }
}
