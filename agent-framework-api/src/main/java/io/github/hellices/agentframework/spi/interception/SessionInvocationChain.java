package io.github.hellices.agentframework.spi.interception;

import java.util.concurrent.CompletionStage;

/** Continues a session-operation chain with an explicit invocation snapshot. */
public interface SessionInvocationChain {

  CompletionStage<SessionOperationResult> proceed(SessionInvocation invocation);
}
