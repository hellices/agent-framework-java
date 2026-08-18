package io.github.hellices.agentframework.spi.interception;

import io.github.hellices.agentframework.api.agent.AgentResponseUpdate;
import io.github.hellices.agentframework.api.agent.AgentStreamingRun;

/**
 * Intercepts one agent execution around its updates, response, session, and cancellation handle.
 */
public interface AgentExecutionInterceptor {

  AgentStreamingRun<AgentResponseUpdate> intercept(
      AgentInvocation invocation, AgentInvocationChain next);
}
