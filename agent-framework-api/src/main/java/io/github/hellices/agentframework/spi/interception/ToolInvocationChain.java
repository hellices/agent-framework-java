package io.github.hellices.agentframework.spi.interception;

import io.github.hellices.agentframework.api.tool.ToolResult;
import java.util.concurrent.CompletionStage;

/** Continues a tool invocation chain with an explicit invocation snapshot. */
public interface ToolInvocationChain {

  CompletionStage<ToolResult> proceed(ToolInvocation invocation);
}
