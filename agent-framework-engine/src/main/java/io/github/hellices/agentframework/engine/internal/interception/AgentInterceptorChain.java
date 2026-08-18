package io.github.hellices.agentframework.engine.internal.interception;

import io.github.hellices.agentframework.spi.interception.AgentExecution;
import io.github.hellices.agentframework.spi.interception.AgentExecutionInterceptor;
import io.github.hellices.agentframework.spi.interception.AgentInvocation;
import io.github.hellices.agentframework.spi.interception.AgentInvocationChain;
import java.util.List;
import java.util.Objects;

final class AgentInterceptorChain {

  private final ComposedAgentChain chain;

  AgentInterceptorChain(List<? extends AgentExecutionInterceptor> interceptors) {
    List<AgentExecutionInterceptor> snapshot =
        List.copyOf(
            Objects.requireNonNull(interceptors, "agentExecutionInterceptors must not be null"));
    ComposedAgentChain composed =
        (invocation, terminal) ->
            Objects.requireNonNull(
                terminal.proceed(invocation), "agent execution must not be null");
    for (int index = snapshot.size() - 1; index >= 0; index--) {
      AgentExecutionInterceptor interceptor = snapshot.get(index);
      ComposedAgentChain next = composed;
      composed =
          (invocation, terminal) ->
              Objects.requireNonNull(
                  interceptor.intercept(
                      invocation, nextInvocation -> next.proceed(nextInvocation, terminal)),
                  "agent execution must not be null");
    }
    this.chain = composed;
  }

  AgentExecution intercept(AgentInvocation invocation, AgentInvocationChain terminal) {
    Objects.requireNonNull(invocation, "invocation must not be null");
    Objects.requireNonNull(terminal, "terminal must not be null");
    return chain.proceed(invocation, terminal);
  }

  @FunctionalInterface
  private interface ComposedAgentChain {
    AgentExecution proceed(AgentInvocation invocation, AgentInvocationChain terminal);
  }
}
