package io.github.hellices.agentframework.api.agent;

import io.github.hellices.agentframework.api.session.SessionContext;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

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
  protected AgentStreamingRun<AgentResponseUpdate> runStreamingInternal(
      AgentRunContext context, AgentRunRequest request) {
    return delegate.runStreamingInternal(context, request);
  }

  /**
   * Delegates the post-run lifecycle seam so a wrapped agent's own post-run work (for example the
   * engine's context provider {@code afterRun} pipeline) still runs when the wrapper owns the run.
   */
  @Override
  protected CompletionStage<Void> afterRun(SessionContext sessionContext) {
    return delegate.afterRun(sessionContext);
  }
}
