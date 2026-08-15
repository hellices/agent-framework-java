package io.github.hellices.agentframework.api.agent;

import io.github.hellices.agentframework.api.tool.FunctionTool;

public interface AgentBuilder {

  AgentBuilder id(String id);

  AgentBuilder name(String name);

  AgentBuilder description(String description);

  AgentBuilder tools(FunctionTool... tools);

  AgentBuilder maxIterations(int maxIterations);

  Agent build();
}
