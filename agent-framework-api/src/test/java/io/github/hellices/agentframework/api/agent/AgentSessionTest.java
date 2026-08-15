package io.github.hellices.agentframework.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentSessionTest {

  @Test
  void createsSessionIdWhenMissing() {
    AgentSession session = new AgentSession(null, "service-1", Map.of());

    assertThat(session.sessionId()).isNotBlank();
  }

  @Test
  void rejectsBlankSessionId() {
    assertThatThrownBy(() -> new AgentSession("   ", "service-1", Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("sessionId must not be blank");
  }

  @Test
  void rejectsEmptyServiceSessionId() {
    assertThatThrownBy(() -> new AgentSession("session-1", "", Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("serviceSessionId must not be blank");
  }

  @Test
  void rejectsWhitespaceServiceSessionId() {
    assertThatThrownBy(() -> new AgentSession("session-1", "   ", Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("serviceSessionId must not be blank");
  }

  @Test
  void acceptsValidServiceSessionId() {
    AgentSession session = new AgentSession("session-1", "service-1", Map.of());

    assertThat(session.serviceSessionId()).isEqualTo("service-1");
  }

  @Test
  void acceptsNullServiceSessionId() {
    AgentSession session = new AgentSession("session-1", null, Map.of());

    assertThat(session.serviceSessionId()).isNull();
  }
}
