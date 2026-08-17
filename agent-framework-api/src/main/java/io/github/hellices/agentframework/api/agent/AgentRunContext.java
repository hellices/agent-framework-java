package io.github.hellices.agentframework.api.agent;

import io.github.hellices.agentframework.api.context.ContextAttributes;
import io.github.hellices.agentframework.api.session.SessionContext;
import java.util.Objects;

/**
 * The per-call context handed to an {@code Agent} implementation's internal run methods.
 *
 * <p>{@link #session()} is the session the caller passed on the request. A run that loads a stored
 * session replaces the session on {@link #sessionContext()} instead, because {@link SessionContext}
 * is the single per-run object every hook and every state view already shares. The consistency
 * invariant below is therefore a construction-time check, not a lifetime one: after a run hydrated
 * its context, {@code sessionContext().session()} is the run's effective session and this record's
 * {@code session} component still reports what the request asked for.
 */
public record AgentRunContext(
    Agent agent,
    AgentSession session,
    ContextAttributes attributes,
    SessionContext sessionContext) {

  public AgentRunContext {
    Objects.requireNonNull(agent, "agent must not be null");
    attributes = attributes == null ? ContextAttributes.empty() : attributes;
    Objects.requireNonNull(sessionContext, "sessionContext must not be null");
    if (!Objects.equals(session, sessionContext.session())) {
      throw new IllegalArgumentException("session must match sessionContext.session()");
    }
  }
}
