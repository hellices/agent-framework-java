package io.github.hellices.agentframework.spi.interception;

/**
 * Continues an agent execution chain with an explicit pre-finalization invocation snapshot.
 *
 * <p>The returned {@link AgentExecution} remains pre-finalization: the engine owns final-response
 * derivation and session persistence after the chain finishes.
 */
public interface AgentInvocationChain {

  AgentExecution proceed(AgentInvocation invocation);
}
