package io.github.hellices.agentframework.engine;

import io.github.hellices.agentframework.api.agent.AgentBuilder;
import io.github.hellices.agentframework.api.tool.FunctionTool;
import io.github.hellices.agentframework.spi.model.ModelClient;
import io.github.hellices.agentframework.spi.session.ContextProvider;
import java.util.ArrayList;
import java.util.List;

public final class AgentEngineBuilder implements AgentBuilder {

  private String id;
  private String name;
  private String description;
  private ModelClient modelClient;
  private final List<FunctionTool> tools = new ArrayList<>();
  private final List<ContextProvider> contextProviders = new ArrayList<>();
  private int maxIterations = 5;

  AgentEngineBuilder() {}

  @Override
  public AgentEngineBuilder id(String id) {
    this.id = id;
    return this;
  }

  @Override
  public AgentEngineBuilder name(String name) {
    this.name = name;
    return this;
  }

  @Override
  public AgentEngineBuilder description(String description) {
    this.description = description;
    return this;
  }

  public AgentEngineBuilder modelClient(ModelClient modelClient) {
    this.modelClient = modelClient;
    return this;
  }

  @Override
  public AgentEngineBuilder tools(FunctionTool... tools) {
    if (tools != null) {
      for (FunctionTool tool : tools) {
        if (tool == null) {
          throw new IllegalArgumentException("tools must not contain null entries");
        }
        this.tools.add(tool);
      }
    }
    return this;
  }

  /**
   * Configures the context providers that participate in every run of the built agent, in
   * declaration order: {@code beforeRun} hooks run in this order before the first model call, and
   * {@code afterRun} hooks run in reverse order after a successful run.
   *
   * <p>Each provider's {@link ContextProvider#sourceId()} is read once when the agent is built and
   * fixes the session state namespace it owns for the agent's lifetime. A blank source id or a
   * source id shared by two providers is rejected at build time, because both would let one
   * provider silently read or overwrite another provider's state.
   *
   * @param providers the providers to add, in order; may be {@code null}
   * @throws IllegalArgumentException if {@code providers} contains a {@code null} entry
   */
  public AgentEngineBuilder contextProviders(ContextProvider... providers) {
    if (providers != null) {
      for (ContextProvider provider : providers) {
        if (provider == null) {
          throw new IllegalArgumentException("contextProviders must not contain null entries");
        }
        this.contextProviders.add(provider);
      }
    }
    return this;
  }

  @Override
  public AgentEngineBuilder maxIterations(int maxIterations) {
    if (maxIterations < 1) {
      throw new IllegalArgumentException("maxIterations must be greater than 0");
    }
    this.maxIterations = maxIterations;
    return this;
  }

  @Override
  public AgentEngine build() {
    if (modelClient == null) {
      throw new IllegalStateException("modelClient must be configured");
    }
    return new AgentEngine(
        id, name, description, modelClient, tools, contextProviders, maxIterations);
  }
}
