package io.github.hellices.agentframework.api.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hellices.agentframework.api.agent.AgentSession;
import io.github.hellices.agentframework.api.session.SessionState;
import io.github.hellices.agentframework.api.session.SessionStateKey;
import io.github.hellices.agentframework.api.session.SessionStateValues;
import io.github.hellices.agentframework.api.value.JsonValue;
import io.github.hellices.agentframework.api.value.JsonValues;
import io.github.hellices.agentframework.spi.session.StateCodecRegistry;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pins the extension point of the sealed content hierarchy in the module that owns it.
 *
 * <p>Widening a sealed type is the compatibility sensitive part of the content model: it decides
 * whether code that reasons over {@link Content} stays exhaustive, whether an adapter can carry a
 * payload the framework has no type for, and what a caller sees when such a payload reaches session
 * persistence. Those answers are asserted here rather than in an adapter module, because they hold
 * for every adapter and must not depend on one being on the classpath.
 */
class ExtensionContentTest {

  @Test
  void carriesTheSubclassDiscriminatorAndNoText() {
    Content content = new ProbeContent(Map.of("mimeType", "image/png"), null);

    assertThat(content.type()).isEqualTo("probe");
    assertThat(content.text()).isEmpty();
    assertThat(new Message(Role.ASSISTANT, List.of(new TextContent("done"), content)).text())
        .isEqualTo("done");
  }

  @Test
  void copiesAdditionalPropertiesOnBothSidesOfTheBoundary() {
    Map<String, Object> mutable = new LinkedHashMap<>();
    mutable.put("mimeType", "image/png");
    Content content = new ProbeContent(mutable, null);

    mutable.put("smuggled", "value");

    assertThat(content.additionalProperties()).containsExactly(Map.entry("mimeType", "image/png"));
    assertThatThrownBy(() -> content.additionalProperties().put("smuggled", "value"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void defaultsAbsentPropertiesAndKeepsTheRawRepresentationHandle() {
    Object sdkValue = new Object();
    Content content = new ProbeContent(null, sdkValue);

    assertThat(content.additionalProperties()).isEmpty();
    assertThat(content.rawRepresentation()).isSameAs(sdkValue);
    assertThat(new ProbeContent(null, null).rawRepresentation()).isNull();
  }

  @Test
  void isTheOnlyOpenPermitOfTheSealedContentHierarchy() {
    assertThat(Content.class.isSealed()).isTrue();
    assertThat(Content.class.getPermittedSubclasses())
        .containsExactlyInAnyOrder(
            TextContent.class,
            ToolCallContent.class,
            ToolResultContent.class,
            ExtensionContent.class);
    assertThat(ExtensionContent.class.isSealed()).isFalse();
    // `isSealed()` alone cannot tell a reopened permit from a closed one, because a final class and
    // a non-sealed class report the same thing. The framework owned kinds are pinned as final, so
    // widening one of them is a visible change rather than a silent one.
    assertThat(List.of(TextContent.class, ToolCallContent.class, ToolResultContent.class))
        .allSatisfy(
            permit -> {
              assertThat(Modifier.isFinal(permit.getModifiers())).isTrue();
              assertThat(permit.getPermittedSubclasses()).isNull();
            });
  }

  @Test
  void isRejectedBySessionPersistenceWithAnActionableMessage() {
    StateCodecRegistry registry = StateCodecRegistry.builder().build();
    AgentSession session =
        session(
            "session-1",
            null,
            Map.of(
                "message",
                new Message(Role.ASSISTANT, List.of(new ProbeContent(Map.of(), new Object())))));

    assertThatThrownBy(() -> registry.snapshot(session, 0, Instant.EPOCH))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(ProbeContent.class.getName())
        .hasMessageContaining("registered content codec");
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

  /** Stand-in for an adapter owned content kind, so no adapter module is needed to pin this. */
  private static final class ProbeContent extends ExtensionContent {

    private ProbeContent(Map<String, Object> additionalProperties, Object rawRepresentation) {
      super(additionalProperties, rawRepresentation);
    }

    @Override
    public String type() {
      return "probe";
    }
  }
}
