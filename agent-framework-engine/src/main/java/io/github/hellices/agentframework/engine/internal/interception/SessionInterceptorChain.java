package io.github.hellices.agentframework.engine.internal.interception;

import io.github.hellices.agentframework.spi.interception.SessionInvocation;
import io.github.hellices.agentframework.spi.interception.SessionInvocationChain;
import io.github.hellices.agentframework.spi.interception.SessionOperationInterceptor;
import io.github.hellices.agentframework.spi.interception.SessionOperationResult;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

final class SessionInterceptorChain {

  private final ComposedSessionChain chain;

  SessionInterceptorChain(List<? extends SessionOperationInterceptor> interceptors) {
    List<SessionOperationInterceptor> snapshot =
        List.copyOf(
            Objects.requireNonNull(interceptors, "sessionOperationInterceptors must not be null"));
    ComposedSessionChain composed =
        (invocation, terminal) -> requireResultStage(terminal.proceed(invocation));
    for (int index = snapshot.size() - 1; index >= 0; index--) {
      SessionOperationInterceptor interceptor = snapshot.get(index);
      ComposedSessionChain next = composed;
      composed =
          (invocation, terminal) ->
              requireResultStage(
                  interceptor.intercept(
                      invocation, nextInvocation -> next.proceed(nextInvocation, terminal)));
    }
    this.chain = composed;
  }

  CompletionStage<SessionOperationResult> intercept(
      SessionInvocation invocation, SessionInvocationChain terminal) {
    Objects.requireNonNull(invocation, "invocation must not be null");
    Objects.requireNonNull(terminal, "terminal must not be null");
    return chain.proceed(invocation, terminal);
  }

  private static CompletionStage<SessionOperationResult> requireResultStage(
      CompletionStage<SessionOperationResult> stage) {
    return Objects.requireNonNull(stage, "session result stage must not be null")
        .thenApply(
            result -> Objects.requireNonNull(result, "session operation result must not be null"));
  }

  @FunctionalInterface
  private interface ComposedSessionChain {
    CompletionStage<SessionOperationResult> proceed(
        SessionInvocation invocation, SessionInvocationChain terminal);
  }
}
