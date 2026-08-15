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
    if (!Objects.equals(session, sessionContext.session())) {
      throw new IllegalArgumentException("session must match sessionContext.session()");
    }
  }

  /**
   * Binary/source-compatibility constructor preserved for callers compiled against the earlier
   * 3-argument signature (before {@code sessionContext} became a record component). It delegates to
   * the canonical 4-argument constructor with a fresh {@link SessionContext} built from {@code
   * session} and {@code attributes}, so the session-consistency invariant enforced above still
   * applies.
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
   * @deprecated Use the 4-argument constructor instead. Retained only for binary/source
   *     compatibility with code compiled against the earlier 3-argument signature.
   */
  @Deprecated(forRemoval = true)
  public AgentRunContext(Agent agent, AgentSession session, Map<String, Object> attributes) {
    this(agent, session, attributes, new SessionContext(session, List.of(), attributes, null));
  }
}
