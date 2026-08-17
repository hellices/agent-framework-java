package io.github.hellices.agentframework.api.agent;

import io.github.hellices.agentframework.api.context.ContextAttributes;
import io.github.hellices.agentframework.api.session.SessionContext;
import java.util.List;
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

  /**
   * Convenience constructor that delegates to the canonical 4-argument constructor with a fresh
   * {@link SessionContext} built from {@code session} and {@code attributes}, so the
   * session-consistency invariant enforced above still applies.
   *
   * <p>Limitations: because the legacy signature carries no message list, the resulting {@code
   * SessionContext} always has empty {@link SessionContext#inputMessages()} and empty {@link
   * SessionContext#contextMessages()}; {@code sessionContext.metadata()} mirrors the normalized
   * {@code attributes}; and the cancellation signal is a brand-new {@link CancellationSignal},
   * using {@code SessionContext}'s fresh-null normalization (see the canonical {@code
   * SessionContext} constructor) rather than sharing one with any other run. Prefer the canonical
   * 4-argument constructor, which lets {@link Agent} share a single {@code SessionContext} between
   * the run context and its completion callback.
   *
   * @deprecated Use the 4-argument constructor instead.
   */
  @Deprecated(forRemoval = true)
  public AgentRunContext(Agent agent, AgentSession session, ContextAttributes attributes) {
    this(agent, session, attributes, new SessionContext(session, List.of(), attributes, null));
  }
}
