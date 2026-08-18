package io.github.hellices.agentframework.engine.internal.interception;

import io.github.hellices.agentframework.spi.interception.ModelInvocation;
import io.github.hellices.agentframework.spi.interception.ModelInvocationChain;
import io.github.hellices.agentframework.spi.interception.ModelInvocationInterceptor;
import io.github.hellices.agentframework.spi.model.ModelResponseUpdate;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Flow;

final class ModelInterceptorChain {

  private final ComposedModelChain chain;

  ModelInterceptorChain(List<? extends ModelInvocationInterceptor> interceptors) {
    List<ModelInvocationInterceptor> snapshot =
        List.copyOf(
            Objects.requireNonNull(interceptors, "modelInvocationInterceptors must not be null"));
    ComposedModelChain composed =
        (invocation, terminal) ->
            Objects.requireNonNull(
                terminal.proceed(invocation), "model response publisher must not be null");
    for (int index = snapshot.size() - 1; index >= 0; index--) {
      ModelInvocationInterceptor interceptor = snapshot.get(index);
      ComposedModelChain next = composed;
      composed =
          (invocation, terminal) ->
              Objects.requireNonNull(
                  interceptor.intercept(
                      invocation, nextInvocation -> next.proceed(nextInvocation, terminal)),
                  "model response publisher must not be null");
    }
    this.chain = composed;
  }

  Flow.Publisher<ModelResponseUpdate> intercept(
      ModelInvocation invocation, ModelInvocationChain terminal) {
    Objects.requireNonNull(invocation, "invocation must not be null");
    Objects.requireNonNull(terminal, "terminal must not be null");
    return chain.proceed(invocation, terminal);
  }

  @FunctionalInterface
  private interface ComposedModelChain {
    Flow.Publisher<ModelResponseUpdate> proceed(
        ModelInvocation invocation, ModelInvocationChain terminal);
  }
}
