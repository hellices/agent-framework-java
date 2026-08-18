package io.github.hellices.agentframework.spi.interception;

import java.util.concurrent.CompletionStage;

/** Intercepts one session load or save operation around its completion stage. */
public interface SessionOperationInterceptor {

  CompletionStage<SessionOperationResult> intercept(
      SessionInvocation invocation, SessionInvocationChain next);
}
