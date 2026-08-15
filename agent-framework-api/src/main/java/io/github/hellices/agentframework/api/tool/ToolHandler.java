package io.github.hellices.agentframework.api.tool;

import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface ToolHandler {

  CompletionStage<ToolResult> invoke(ToolArguments arguments, ToolContext context);
}
