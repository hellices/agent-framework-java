package io.github.hellices.agentframework.api.agent;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record AgentSession(String sessionId, String serviceSessionId, Map<String, Object> state) {

  public AgentSession {
    String normalizedSessionId = sessionId;
    if (normalizedSessionId == null) {
      normalizedSessionId = UUID.randomUUID().toString();
    } else if (normalizedSessionId.isBlank()) {
      throw new IllegalArgumentException("sessionId must not be blank");
    }
    sessionId = normalizedSessionId;
    if (serviceSessionId != null && serviceSessionId.isBlank()) {
      throw new IllegalArgumentException("serviceSessionId must not be blank");
    }
    state = state == null ? Map.of() : Map.copyOf(state);
  }

  public static AgentSession create() {
    return new AgentSession(null, null, Map.of());
  }

  public AgentSession withState(Map<String, Object> updatedState) {
    return new AgentSession(sessionId, serviceSessionId, Objects.requireNonNull(updatedState));
  }
}
