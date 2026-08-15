package io.github.hellices.agentframework.api.agent;

import io.github.hellices.agentframework.api.session.SessionContext;
import java.util.List;
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
  }

  /**
   * Compatibility constructor that builds a fresh, empty {@link SessionContext} for callers that do
   * not yet construct one explicitly. Prefer the 4-argument constructor, which lets {@link Agent}
   * share a single {@code SessionContext} between the run context and its completion callback.
   */
  public AgentRunContext(Agent agent, AgentSession session, Map<String, Object> attributes) {
    this(
        agent,
        session,
        attributes,
        new SessionContext(session, List.of(), attributes, new CancellationSignal()));
  }
}
