package io.github.hellices.agentframework.engine.internal.interception;

import io.github.hellices.agentframework.api.tool.ToolResult;
import io.github.hellices.agentframework.spi.interception.ToolInvocation;
import io.github.hellices.agentframework.spi.interception.ToolInvocationChain;
import io.github.hellices.agentframework.spi.interception.ToolInvocationInterceptor;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

final class ToolInterceptorChain {

  private final ComposedToolChain chain;

  ToolInterceptorChain(List<? extends ToolInvocationInterceptor> interceptors) {
    List<ToolInvocationInterceptor> snapshot =
        List.copyOf(
            Objects.requireNonNull(interceptors, "toolInvocationInterceptors must not be null"));
    ComposedToolChain composed =
        (invocation, terminal) -> requireResultStage(terminal.proceed(invocation));
    for (int index = snapshot.size() - 1; index >= 0; index--) {
      ToolInvocationInterceptor interceptor = snapshot.get(index);
      ComposedToolChain next = composed;
      composed =
          (invocation, terminal) ->
              requireResultStage(
                  interceptor.intercept(
                      invocation, nextInvocation -> next.proceed(nextInvocation, terminal)));
    }
    this.chain = composed;
  }

  CompletionStage<ToolResult> intercept(ToolInvocation invocation, ToolInvocationChain terminal) {
    Objects.requireNonNull(invocation, "invocation must not be null");
    Objects.requireNonNull(terminal, "terminal must not be null");
    return chain.proceed(invocation, terminal);
  }

  private static CompletionStage<ToolResult> requireResultStage(CompletionStage<ToolResult> stage) {
    return Objects.requireNonNull(stage, "tool result stage must not be null")
        .thenApply(result -> Objects.requireNonNull(result, "tool result must not be null"));
  }

  @FunctionalInterface
  private interface ComposedToolChain {
    CompletionStage<ToolResult> proceed(ToolInvocation invocation, ToolInvocationChain terminal);
  }
}
