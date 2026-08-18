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
 * its context, {@code sessionContext().session()} is the run's effective session and this value's
 * {@code session} field still reports what the request asked for.
 */
public final class AgentRunContext {

  private final Agent agent;
  private final AgentSession session;
  private final ContextAttributes attributes;
  private final SessionContext sessionContext;

  private AgentRunContext(Builder builder) {
    this.agent = Objects.requireNonNull(builder.agent, "agent must not be null");
    this.session = builder.session;
    this.attributes = builder.attributes == null ? ContextAttributes.empty() : builder.attributes;
    this.sessionContext =
        Objects.requireNonNull(builder.sessionContext, "sessionContext must not be null");
    if (!Objects.equals(session, sessionContext.session())) {
      throw new IllegalArgumentException("session must match sessionContext.session()");
    }
  }

  public static Builder builder() {
    return new Builder();
  }

  public Agent agent() {
    return agent;
  }

  public AgentSession session() {
    return session;
  }

  public ContextAttributes attributes() {
    return attributes;
  }

  public SessionContext sessionContext() {
    return sessionContext;
  }

  public Builder toBuilder() {
    return new Builder()
        .agent(agent)
        .session(session)
        .attributes(attributes)
        .sessionContext(sessionContext);
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof AgentRunContext that)) {
      return false;
    }
    return Objects.equals(agent, that.agent)
        && Objects.equals(session, that.session)
        && attributes.equals(that.attributes)
        && Objects.equals(sessionContext, that.sessionContext);
  }

  @Override
  public int hashCode() {
    return Objects.hash(agent, session, attributes, sessionContext);
  }

  public static final class Builder {
    private Agent agent;
    private AgentSession session;
    private ContextAttributes attributes = ContextAttributes.empty();
    private SessionContext sessionContext;

    private Builder() {}

    public Builder agent(Agent agent) {
      this.agent = agent;
      return this;
    }

    public Builder session(AgentSession session) {
      this.session = session;
      return this;
    }

    public Builder attributes(ContextAttributes attributes) {
      this.attributes = attributes == null ? ContextAttributes.empty() : attributes;
      return this;
    }

    public Builder sessionContext(SessionContext sessionContext) {
      this.sessionContext = sessionContext;
      return this;
    }

    public AgentRunContext build() {
      return new AgentRunContext(this);
    }
  }
}
