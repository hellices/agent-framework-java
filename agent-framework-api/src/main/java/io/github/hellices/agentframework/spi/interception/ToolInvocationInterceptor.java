package io.github.hellices.agentframework.spi.interception;

import io.github.hellices.agentframework.api.tool.ToolResult;
import java.util.concurrent.CompletionStage;

/** Intercepts one tool invocation around its result stage. */
public interface ToolInvocationInterceptor {

  CompletionStage<ToolResult> intercept(ToolInvocation invocation, ToolInvocationChain next);
}
