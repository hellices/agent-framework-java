package io.github.hellices.agentframework.api.agent;

import io.github.hellices.agentframework.spi.model.ModelClient;

public interface AgentFactory {

  AgentBuilder builder();

  AgentBuilder builder(String modelName);

  AgentBuilder builderWithClient(ModelClient modelClient);
}
