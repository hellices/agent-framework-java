package io.github.hellices.agentframework.spi.interception;

import io.github.hellices.agentframework.api.agent.AgentResponse;
import java.util.concurrent.CompletionStage;

/** Intercepts one agent execution around its final response stage. */
public interface AgentExecutionInterceptor {

  CompletionStage<AgentResponse> intercept(AgentInvocation invocation, AgentInvocationChain next);
}
