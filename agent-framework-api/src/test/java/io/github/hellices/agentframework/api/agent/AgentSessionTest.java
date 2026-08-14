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
}
