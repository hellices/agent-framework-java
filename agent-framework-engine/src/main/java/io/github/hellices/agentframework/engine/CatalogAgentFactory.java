package io.github.hellices.agentframework.engine;

import io.github.hellices.agentframework.api.agent.Agent;
import io.github.hellices.agentframework.api.agent.AgentBuilder;
import io.github.hellices.agentframework.api.agent.AgentDefinition;
import io.github.hellices.agentframework.api.agent.AgentFactory;
import io.github.hellices.agentframework.api.agent.AgentRuntime;
import io.github.hellices.agentframework.spi.model.ModelCatalog;
import io.github.hellices.agentframework.spi.model.ModelClient;
import java.util.Objects;

/**
 * The {@link AgentFactory} an {@link AgentEngine} composes with a {@link ModelCatalog}: it selects
 * the model client each builder runs with and binds every agent it produces to the shared engine.
 */
final class CatalogAgentFactory implements AgentFactory {

  private final AgentEngine engine;
  private final ModelCatalog catalog;

  CatalogAgentFactory(AgentEngine engine, ModelCatalog catalog) {
    this.engine = Objects.requireNonNull(engine, "engine must not be null");
    this.catalog = Objects.requireNonNull(catalog, "catalog must not be null");
  }

  @Override
  public AgentBuilder builder() {
    return builderWithClient(catalog.defaultClient());
  }

  @Override
  public AgentBuilder builder(String modelName) {
    return builderWithClient(catalog.resolve(modelName));
  }

  @Override
  public AgentBuilder builderWithClient(ModelClient modelClient) {
    return new DefaultAgentBuilder(
        engine, Objects.requireNonNull(modelClient, "modelClient must not be null"));
  }

  @Override
  public Agent bind(AgentDefinition definition, AgentRuntime runtime) {
    return engine.bind(definition, runtime);
  }
}
