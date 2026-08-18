package io.github.hellices.agentframework.spi.interception;

/**
 * Intercepts one agent execution before finalization.
 *
 * <p>The returned {@link AgentExecution} carries only the update stream and cancellation signal.
 * The engine derives the final {@code AgentResponse}, runs post-stream lifecycle work, and persists
 * the session exactly once after the interceptor chain returns.
 */
public interface AgentExecutionInterceptor {

  AgentExecution intercept(AgentInvocation invocation, AgentInvocationChain next);
}
