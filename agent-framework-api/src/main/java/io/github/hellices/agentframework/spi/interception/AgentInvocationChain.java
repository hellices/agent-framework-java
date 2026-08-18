package io.github.hellices.agentframework.spi.interception;

import io.github.hellices.agentframework.api.agent.AgentResponseUpdate;
import io.github.hellices.agentframework.api.agent.AgentStreamingRun;

/** Continues an agent execution chain with an explicit invocation snapshot. */
public interface AgentInvocationChain {

  AgentStreamingRun<AgentResponseUpdate> proceed(AgentInvocation invocation);
}
