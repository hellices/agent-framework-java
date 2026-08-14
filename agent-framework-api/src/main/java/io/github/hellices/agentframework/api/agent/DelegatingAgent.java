package io.github.hellices.agentframework.api.agent;

import java.util.Objects;

public abstract class DelegatingAgent extends Agent {

  private final Agent delegate;

  protected DelegatingAgent(Agent delegate) {
    super(
        Objects.requireNonNull(delegate, "delegate must not be null").id(),
        delegate.name(),
        delegate.description());
    this.delegate = delegate;
  }

  protected Agent delegate() {
    return delegate;
  }

  @Override
  protected void validateSessionCompatibility(AgentSession session) {
    delegate.validateSessionCompatibility(session);
  }

  @Override
  protected AgentRun runInternal(AgentRunContext context, AgentRunRequest request) {
    return delegate.runInternal(context, request);
  }

  @Override
  protected AgentStreamingRun runStreamingInternal(
      AgentRunContext context, AgentRunRequest request) {
    return delegate.runStreamingInternal(context, request);
  }
}
