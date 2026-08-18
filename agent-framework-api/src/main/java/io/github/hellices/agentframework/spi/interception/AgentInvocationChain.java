package io.github.hellices.agentframework.spi.interception;

import io.github.hellices.agentframework.api.agent.AgentResponse;
import java.util.concurrent.CompletionStage;

/** Continues an agent execution chain with an explicit invocation snapshot. */
public interface AgentInvocationChain {

  CompletionStage<AgentResponse> proceed(AgentInvocation invocation);
}
