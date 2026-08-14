package io.github.hellices.agentframework.api.agent;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record AgentSession(String sessionId, String serviceSessionId, Map<String, Object> state) {

  public AgentSession {
    String normalizedSessionId = sessionId;
    if (normalizedSessionId == null || normalizedSessionId.isBlank()) {
      normalizedSessionId = UUID.randomUUID().toString();
    }
    sessionId = normalizedSessionId;
    state = state == null ? Map.of() : Map.copyOf(state);
  }

  public static AgentSession create() {
    return new AgentSession(null, null, Map.of());
  }

  public AgentSession withState(Map<String, Object> updatedState) {
    return new AgentSession(sessionId, serviceSessionId, Objects.requireNonNull(updatedState));
  }
}
