package io.github.hellices.agentframework.engine;

import io.github.hellices.agentframework.spi.model.ModelClient;

public final class AgentEngineBuilder {

  private String id;
  private String name;
  private String description;
  private ModelClient modelClient;

  AgentEngineBuilder() {}

  public AgentEngineBuilder id(String id) {
    this.id = id;
    return this;
  }

  public AgentEngineBuilder name(String name) {
    this.name = name;
    return this;
  }

  public AgentEngineBuilder description(String description) {
    this.description = description;
    return this;
  }

  public AgentEngineBuilder modelClient(ModelClient modelClient) {
    this.modelClient = modelClient;
    return this;
  }

  public AgentEngine build() {
    if (modelClient == null) {
      throw new IllegalStateException("modelClient must be configured");
    }
    return new AgentEngine(id, name, description, modelClient);
  }
}
