package io.github.hellices.agentframework.engine;

import io.github.hellices.agentframework.api.agent.AgentBuilder;
import io.github.hellices.agentframework.api.agent.AgentFactory;
import io.github.hellices.agentframework.spi.model.ModelCatalog;
import io.github.hellices.agentframework.spi.model.ModelClient;
import java.util.Objects;

final class CatalogAgentFactory implements AgentFactory {

  private final ModelCatalog catalog;

  CatalogAgentFactory(ModelCatalog catalog) {
    this.catalog = Objects.requireNonNull(catalog, "catalog must not be null");
  }

  @Override
  public AgentBuilder builder() {
    return builder(catalog.defaultClient());
  }

  @Override
  public AgentBuilder builder(String modelName) {
    return builder(catalog.resolve(modelName));
  }

  @Override
  public AgentBuilder builder(ModelClient modelClient) {
    return AgentEngine.builder()
        .modelClient(Objects.requireNonNull(modelClient, "modelClient must not be null"));
  }
}
