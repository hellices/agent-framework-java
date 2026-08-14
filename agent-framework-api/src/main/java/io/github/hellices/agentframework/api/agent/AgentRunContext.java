package io.github.hellices.agentframework.api.agent;

import java.util.Map;
import java.util.Objects;

public record AgentRunContext(Agent agent, AgentSession session, Map<String, Object> attributes) {

  public AgentRunContext {
    Objects.requireNonNull(agent, "agent must not be null");
    attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
  }
}
