package io.github.hellices.agentframework.engine;

import io.github.hellices.agentframework.api.tool.FunctionTool;
import io.github.hellices.agentframework.spi.model.ModelClient;
import java.util.ArrayList;
import java.util.List;

public final class AgentEngineBuilder {

  private String id;
  private String name;
  private String description;
  private ModelClient modelClient;
  private final List<FunctionTool> tools = new ArrayList<>();
  private int maxIterations = 5;

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

  public AgentEngineBuilder tools(FunctionTool... tools) {
    if (tools != null) {
      this.tools.addAll(List.of(tools));
    }
    return this;
  }

  public AgentEngineBuilder maxIterations(int maxIterations) {
    if (maxIterations < 1) {
      throw new IllegalArgumentException("maxIterations must be greater than 0");
    }
    this.maxIterations = maxIterations;
    return this;
  }

  public AgentEngine build() {
    if (modelClient == null) {
      throw new IllegalStateException("modelClient must be configured");
    }
    return new AgentEngine(id, name, description, modelClient, tools, maxIterations);
  }
}
