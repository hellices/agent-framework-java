package io.github.hellices.agentframework.api.agent;

import io.github.hellices.agentframework.api.tool.FunctionTool;
import io.github.hellices.agentframework.spi.session.ContextProvider;

/**
 * Configures a single agent's declarative identity and runtime wiring, then either returns the
 * declaration alone or binds a runnable agent.
 *
 * <p>A builder is handed out by an {@link AgentFactory} already carrying the model client the agent
 * runs with, so callers configure identity, instructions, tools, context providers, and the tool
 * iteration budget without ever naming an engine. {@link #buildDefinition()} returns the
 * declarative {@link AgentDefinition} only — the identity, instructions, and tool declarations —
 * while {@link #build()} additionally derives the {@link AgentRuntime} bindings and binds them,
 * producing a runnable {@link Agent}.
 */
public interface AgentBuilder {

  AgentBuilder id(String id);

  AgentBuilder name(String name);

  AgentBuilder description(String description);

  AgentBuilder instructions(String instructions);

  AgentBuilder tools(FunctionTool... tools);

  AgentBuilder contextProviders(ContextProvider... providers);

  AgentBuilder maxIterations(int maxIterations);

  AgentDefinition buildDefinition();

  Agent build();
}
