package io.github.hellices.agentframework.spi.interception;

import io.github.hellices.agentframework.spi.model.ModelResponseUpdate;
import java.util.concurrent.Flow;

/** Intercepts one model invocation around its response-update publisher. */
public interface ModelInvocationInterceptor {

  Flow.Publisher<ModelResponseUpdate> intercept(
      ModelInvocation invocation, ModelInvocationChain next);
}
