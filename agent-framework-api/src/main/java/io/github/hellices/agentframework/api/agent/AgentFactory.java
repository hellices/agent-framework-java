package io.github.hellices.agentframework.api.agent;

import io.github.hellices.agentframework.spi.model.ModelClient;

/**
 * Produces agents over a shared, model-independent {@link AgentEngine}.
 *
 * <p>A factory selects the model client each builder runs with — the catalog's default, a named
 * catalog entry, or an explicit client — and can also bind a fully specified {@link
 * AgentDefinition} and {@link AgentRuntime} directly.
 */
public interface AgentFactory {

  AgentBuilder builder();

  AgentBuilder builder(String modelName);

  AgentBuilder builderWithClient(ModelClient modelClient);

  Agent bind(AgentDefinition definition, AgentRuntime runtime);
}
