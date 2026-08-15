package io.github.hellices.agentframework.api.tool;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

public final class FunctionTool {

  private final ToolDefinition definition;
  private final ToolHandler handler;

  private FunctionTool(ToolDefinition definition, ToolHandler handler) {
    this.definition = definition;
    this.handler = handler;
  }

  public static FunctionTool create(
      String name, String description, Map<String, Object> inputSchema, ToolHandler handler) {
    return new FunctionTool(
        new ToolDefinition(name, description, inputSchema),
        Objects.requireNonNull(handler, "handler must not be null"));
  }

  public ToolDefinition definition() {
    return definition;
  }

  public CompletionStage<ToolResult> execute(ToolArguments arguments, ToolContext context) {
    return Objects.requireNonNull(
        handler.invoke(
            Objects.requireNonNull(arguments, "arguments must not be null"),
            Objects.requireNonNull(context, "context must not be null")),
        "tool handler must not return null");
  }
}
