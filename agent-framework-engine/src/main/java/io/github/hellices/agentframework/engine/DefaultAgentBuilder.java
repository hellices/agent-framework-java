package io.github.hellices.agentframework.engine;

import io.github.hellices.agentframework.api.agent.Agent;
import io.github.hellices.agentframework.api.agent.AgentBuilder;
import io.github.hellices.agentframework.api.agent.AgentDefinition;
import io.github.hellices.agentframework.api.agent.AgentRunOptions;
import io.github.hellices.agentframework.api.agent.AgentRuntime;
import io.github.hellices.agentframework.api.tool.FunctionTool;
import io.github.hellices.agentframework.api.tool.ToolBinding;
import io.github.hellices.agentframework.spi.model.ModelClient;
import io.github.hellices.agentframework.spi.session.ContextProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The {@link AgentBuilder} an {@link io.github.hellices.agentframework.api.agent.AgentFactory}
 * hands out: it owns a single agent's per-agent configuration — identity, instructions, the {@link
 * FunctionTool}s it can call, context providers, the tool iteration budget, and the model client
 * the factory selected — and turns that into a runnable agent by binding a declaration and runtime
 * to the shared {@link AgentEngine}.
 *
 * <p>{@link #buildDefinition()} projects only the declarative half — identity, instructions, and
 * tool declarations — so a caller can inspect or persist the declaration without wiring a runtime.
 * {@link #build()} additionally derives the {@link AgentRuntime}: every configured {@link
 * FunctionTool} contributes both a declaration and an executable {@link ToolBinding}, so a tool
 * added here is always callable, and the context providers and model client become the runtime the
 * engine binds.
 */
final class DefaultAgentBuilder implements AgentBuilder {

  private final AgentEngine engine;
  private final ModelClient modelClient;
  private String id;
  private String name;
  private String description;
  private String instructions;
  private final List<FunctionTool> tools = new ArrayList<>();
  private final List<ContextProvider> contextProviders = new ArrayList<>();
  private int maxIterations = AgentRunOptions.DEFAULT_MAX_TOOL_ITERATIONS;

  DefaultAgentBuilder(AgentEngine engine, ModelClient modelClient) {
    this.engine = Objects.requireNonNull(engine, "engine must not be null");
    this.modelClient = Objects.requireNonNull(modelClient, "modelClient must not be null");
  }

  @Override
  public AgentBuilder id(String id) {
    this.id = id;
    return this;
  }

  @Override
  public AgentBuilder name(String name) {
    this.name = name;
    return this;
  }

  @Override
  public AgentBuilder description(String description) {
    this.description = description;
    return this;
  }

  @Override
  public AgentBuilder instructions(String instructions) {
    this.instructions = instructions;
    return this;
  }

  @Override
  public AgentBuilder tools(FunctionTool... tools) {
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
   * <p>Each provider's {@link ContextProvider#sourceId()} is read once when the agent is bound and
   * fixes the session state namespace it owns for the agent's lifetime. A blank source id or a
   * source id shared by two providers is rejected at bind time, because both would let one provider
   * silently read or overwrite another provider's state.
   *
   * @param providers the providers to add, in order; may be {@code null}
   * @throws IllegalArgumentException if {@code providers} contains a {@code null} entry
   */
  @Override
  public AgentBuilder contextProviders(ContextProvider... providers) {
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
  public AgentBuilder maxIterations(int maxIterations) {
    if (maxIterations < 1) {
      throw new IllegalArgumentException("maxIterations must be greater than 0");
    }
    this.maxIterations = maxIterations;
    return this;
  }

  @Override
  public AgentDefinition buildDefinition() {
    AgentDefinition.Builder definition =
        AgentDefinition.builder()
            .defaultRunOptions(AgentRunOptions.builder().maxToolIterations(maxIterations).build());
    if (id != null && !id.isBlank()) {
      definition.id(id);
    }
    if (name != null && !name.isBlank()) {
      definition.name(name);
    }
    if (description != null) {
      definition.description(description);
    }
    if (instructions != null) {
      definition.instructions(instructions);
    }
    for (FunctionTool tool : tools) {
      definition.tool(tool.definition());
    }
    return definition.build();
  }

  @Override
  public Agent build() {
    return engine.bind(buildDefinition(), buildRuntime());
  }

  private AgentRuntime buildRuntime() {
    AgentRuntime.Builder runtime = AgentRuntime.builder().modelClient(modelClient);
    for (FunctionTool tool : tools) {
      runtime.toolBinding(ToolBinding.of(tool.definition().name(), tool::execute));
    }
    for (ContextProvider contextProvider : contextProviders) {
      runtime.contextProvider(contextProvider);
    }
    return runtime.build();
  }
}
