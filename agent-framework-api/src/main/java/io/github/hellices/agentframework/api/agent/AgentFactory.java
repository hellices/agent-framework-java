package io.github.hellices.agentframework.api.agent;

import io.github.hellices.agentframework.spi.model.ModelClient;

/**
 * Produces agents over a shared, model-independent engine.
 *
 * <p>A factory selects the model client each builder runs with — the catalog's default, a named
 * catalog entry, or an explicit client — and can also bind a fully specified {@link
 * AgentDefinition} and {@link AgentRuntime} directly through {@link #bind(AgentDefinition,
 * AgentRuntime)}.
 *
 * <p>{@link AgentBuilder#buildDefinition()} projects the declarative half of an agent on its own,
 * while {@link AgentBuilder#build()} and {@link #bind(AgentDefinition, AgentRuntime)} both produce
 * a runnable {@link Agent} bound to the shared engine.
 */
public interface AgentFactory {

  AgentBuilder builder();

  AgentBuilder builder(String modelName);

  AgentBuilder builderWithClient(ModelClient modelClient);

  Agent bind(AgentDefinition definition, AgentRuntime runtime);
}
