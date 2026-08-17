package io.github.hellices.agentframework.engine;

import io.github.hellices.agentframework.api.agent.Agent;
import io.github.hellices.agentframework.api.agent.AgentBuilder;
import io.github.hellices.agentframework.api.tool.FunctionTool;
import io.github.hellices.agentframework.spi.model.ModelClient;
import java.util.Objects;

/**
 * The {@link AgentBuilder} the {@link AgentFactory} hands out: it adapts the neutral builder
 * contract onto an {@link AgentEngineBuilder} already bound to the factory's chosen model client,
 * so a caller configures identity, tools, and the iteration budget without ever naming an engine.
 *
 * <p>It is the smallest bridge the factory needs while the split {@code AgentDefinition}/{@code
 * AgentRuntime}/{@code AgentEngine} model settles; a later plan refines how a factory produces
 * agents.
 */
final class BoundAgentBuilder implements AgentBuilder {

  private final AgentEngineBuilder delegate;

  BoundAgentBuilder(ModelClient modelClient) {
    this.delegate =
        AgentEngine.builder()
            .modelClient(Objects.requireNonNull(modelClient, "modelClient must not be null"));
  }

  @Override
  public AgentBuilder id(String id) {
    delegate.id(id);
    return this;
  }

  @Override
  public AgentBuilder name(String name) {
    delegate.name(name);
    return this;
  }

  @Override
  public AgentBuilder description(String description) {
    delegate.description(description);
    return this;
  }

  @Override
  public AgentBuilder tools(FunctionTool... tools) {
    delegate.tools(tools);
    return this;
  }

  @Override
  public AgentBuilder maxIterations(int maxIterations) {
    delegate.maxIterations(maxIterations);
    return this;
  }

  @Override
  public Agent build() {
    return delegate.build();
  }
}
