package io.github.hellices.agentframework.api.tool;

import java.util.Objects;

public final class ToolBinding {

  private final String toolName;
  private final ToolHandler handler;

  private ToolBinding(String toolName, ToolHandler handler) {
    if (toolName == null || toolName.isBlank()) {
      throw new IllegalArgumentException("tool binding name must not be blank");
    }
    this.toolName = toolName;
    this.handler = Objects.requireNonNull(handler, "handler must not be null");
  }

  public static ToolBinding of(String toolName, ToolHandler handler) {
    return new ToolBinding(toolName, handler);
  }

  public String toolName() {
    return toolName;
  }

  public ToolHandler handler() {
    return handler;
  }
}
