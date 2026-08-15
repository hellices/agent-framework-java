package io.github.hellices.agentframework.api.agent;

import io.github.hellices.agentframework.api.session.SessionContext;
import java.util.Map;
import java.util.Objects;

public record AgentRunContext(
    Agent agent,
    AgentSession session,
    Map<String, Object> attributes,
    SessionContext sessionContext) {

  public AgentRunContext {
    Objects.requireNonNull(agent, "agent must not be null");
    attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    Objects.requireNonNull(sessionContext, "sessionContext must not be null");
    if (!Objects.equals(session, sessionContext.session())) {
      throw new IllegalArgumentException("session must match sessionContext.session()");
    }
  }
}
