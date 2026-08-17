package io.github.hellices.agentframework.engine;

import io.github.hellices.agentframework.api.agent.Agent;
import io.github.hellices.agentframework.api.agent.AgentDefinition;
import io.github.hellices.agentframework.api.agent.AgentResponseUpdate;
import io.github.hellices.agentframework.api.agent.AgentRun;
import io.github.hellices.agentframework.api.agent.AgentRunContext;
import io.github.hellices.agentframework.api.agent.AgentRunRequest;
import io.github.hellices.agentframework.api.agent.AgentRuntime;
import io.github.hellices.agentframework.api.agent.AgentStreamingRun;
import io.github.hellices.agentframework.api.session.SessionContext;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * The {@link Agent} facade over an {@link AgentEngine}: it carries the per-agent {@link
 * AgentBinding} produced from an {@link AgentDefinition} and an {@link AgentRuntime}, and delegates
 * every execution hook to the shared engine, which owns only session coordination.
 *
 * <p>Separating the facade from the engine keeps the engine model-independent and thread-safe — one
 * engine binds many agents — while a bound agent stays an ordinary {@code Agent} so the whole
 * lifecycle ({@code run}, {@code runStreaming}, session save, the {@code afterRun} seam) is the
 * same for a bound agent as for any custom one.
 */
final class BoundAgent extends Agent {

  private final AgentBinding binding;
  private final AgentEngine engine;

  BoundAgent(AgentDefinition definition, AgentRuntime runtime, AgentEngine engine) {
    super(definition.id(), definition.name(), definition.description());
    this.binding = AgentBinding.create(definition, runtime);
    this.engine = Objects.requireNonNull(engine, "engine must not be null");
  }

  @Override
  protected AgentRun runInternal(AgentRunContext context, AgentRunRequest request) {
    return engine.runInternal(binding, context, request);
  }

  @Override
  protected AgentStreamingRun<AgentResponseUpdate> runStreamingInternal(
      AgentRunContext context, AgentRunRequest request) {
    return engine.runStreamingInternal(binding, context, request);
  }

  @Override
  protected CompletionStage<Void> afterRun(SessionContext sessionContext) {
    return engine.afterRun(binding, sessionContext);
  }
}
