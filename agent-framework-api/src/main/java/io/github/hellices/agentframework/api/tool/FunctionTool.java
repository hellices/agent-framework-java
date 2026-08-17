package io.github.hellices.agentframework.api.tool;

import io.github.hellices.agentframework.api.value.JsonObject;
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
      String name, String description, JsonObject inputSchema, ToolHandler handler) {
    return new FunctionTool(
        ToolDefinition.builder()
            .name(name)
            .description(description)
            .inputSchema(inputSchema)
            .build(),
        Objects.requireNonNull(handler, "handler must not be null"));
  }

  /**
   * Reconstructs an executable tool from a declaration and a handler, so an engine binding an
   * {@code AgentDefinition}'s declared tools to an {@code AgentRuntime}'s bound handlers can
   * recover the callable pair the loop needs.
   */
  public static FunctionTool bind(ToolDefinition definition, ToolHandler handler) {
    return new FunctionTool(
        Objects.requireNonNull(definition, "definition must not be null"),
        Objects.requireNonNull(handler, "handler must not be null"));
  }

  public ToolDefinition definition() {
    return definition;
  }

  public CompletionStage<ToolResult> execute(ToolArguments arguments, ToolContext context) {
    CompletionStage<ToolResult> result =
        handler.invoke(
            Objects.requireNonNull(arguments, "arguments must not be null"),
            Objects.requireNonNull(context, "context must not be null"));
    if (result == null) {
      throw new IllegalStateException("tool handler must not return null");
    }
    return result.thenApply(
        value -> {
          if (value == null) {
            throw new IllegalStateException("tool handler result must not be null");
          }
          return value;
        });
  }
}
