package io.github.hellices.agentframework.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hellices.agentframework.api.session.SessionState;
import io.github.hellices.agentframework.api.session.SessionStateKey;
import io.github.hellices.agentframework.api.value.JsonNumber;
import io.github.hellices.agentframework.api.value.JsonObject;
import io.github.hellices.agentframework.api.value.JsonValue;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class AgentSessionTest {

  @Test
  void createsSessionIdWhenMissing() {
    AgentSession session = AgentSession.builder().serviceSessionId("service-1").build();

    assertThat(session.sessionId()).isNotBlank();
  }

  @Test
  void rejectsBlankSessionId() {
    assertThatThrownBy(
            () -> AgentSession.builder().sessionId("   ").serviceSessionId("service-1").build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("sessionId must not be blank");
  }

  @Test
  void rejectsEmptyServiceSessionId() {
    assertThatThrownBy(
            () -> AgentSession.builder().sessionId("session-1").serviceSessionId("").build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("serviceSessionId must not be blank");
  }

  @Test
  void rejectsWhitespaceServiceSessionId() {
    assertThatThrownBy(
            () -> AgentSession.builder().sessionId("session-1").serviceSessionId("   ").build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("serviceSessionId must not be blank");
  }

  @Test
  void acceptsValidServiceSessionId() {
    AgentSession session =
        AgentSession.builder().sessionId("session-1").serviceSessionId("service-1").build();

    assertThat(session.serviceSessionId()).contains("service-1");
  }

  @Test
  void acceptsNullServiceSessionId() {
    AgentSession session = AgentSession.builder().sessionId("session-1").build();

    assertThat(session.serviceSessionId()).isEmpty();
  }

  @Test
  void roundTripsThroughToBuilderWithTypedState() {
    SessionStateKey<JsonValue> turn = SessionStateKey.of("turn", JsonValue.class);
    AgentSession session =
        AgentSession.builder()
            .sessionId("session-1")
            .state(SessionState.empty().with(turn, JsonNumber.of(1)))
            .build();

    assertThat(session.toBuilder().build()).isEqualTo(session);
    assertThat(session.state().get(turn)).contains(JsonNumber.of(1));
  }

  @Test
  void typedStateRejectsConflictingTypesForTheSameStableId() {
    SessionStateKey<JsonValue> turn = SessionStateKey.of("turn", JsonValue.class);
    SessionState state = SessionState.empty().with(turn, JsonNumber.of(1));

    assertThatThrownBy(() -> state.get(SessionStateKey.of("turn", String.class)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("turn");
  }

  @Test
  void sessionStatePublicSurfaceStaysTypedOnly() {
    assertThat(Arrays.stream(SessionState.class.getMethods()).filter(this::declaredOnSessionState))
        .extracting(Method::getName)
        .containsExactlyInAnyOrder(
            "empty", "get", "with", "without", "equals", "hashCode", "toString");
  }

  @Test
  void typedStateRequiresJsonValuesToUseTheJsonValueKeyType() {
    assertThatThrownBy(
            () ->
                SessionState.empty()
                    .with(
                        SessionStateKey.of("memory", JsonObject.class),
                        JsonObject.builder().build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("JsonValue");
  }

  private boolean declaredOnSessionState(Method method) {
    return method.getDeclaringClass().equals(SessionState.class);
  }
}
