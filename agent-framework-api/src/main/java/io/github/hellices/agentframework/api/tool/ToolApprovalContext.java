package io.github.hellices.agentframework.api.tool;

import io.github.hellices.agentframework.api.value.JsonObject;
import java.util.Objects;
import java.util.Optional;

/**
 * The exact-match subject a {@link ToolApprovalPolicy} evaluates a pending tool call against
 * (TOOL-019): the tool name, its exact call arguments, and an optional host-boundary identifier
 * such as an MCP server label.
 *
 * <p>Two calls with the same tool name are different approval subjects when their arguments differ
 * or when they run on different host boundaries, so a policy must not widen a match by ignoring
 * either field.
 */
public final class ToolApprovalContext {

  private final String toolName;
  private final JsonObject arguments;
  private final String hostBoundary;

  public ToolApprovalContext(String toolName, JsonObject arguments, String hostBoundary) {
    if (toolName == null || toolName.isBlank()) {
      throw new IllegalArgumentException("tool name must not be blank");
    }
    this.toolName = toolName;
    this.arguments = arguments == null ? JsonObject.empty() : arguments;
    this.hostBoundary = hostBoundary;
  }

  public String toolName() {
    return toolName;
  }

  public JsonObject arguments() {
    return arguments;
  }

  /** The host boundary this call would run on, such as an MCP server label, when known. */
  public Optional<String> hostBoundary() {
    return Optional.ofNullable(hostBoundary);
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof ToolApprovalContext that)) {
      return false;
    }
    return toolName.equals(that.toolName)
        && arguments.equals(that.arguments)
        && Objects.equals(hostBoundary, that.hostBoundary);
  }

  @Override
  public int hashCode() {
    return Objects.hash(toolName, arguments, hostBoundary);
  }

  @Override
  public String toString() {
    return "ToolApprovalContext[toolName="
        + toolName
        + ", arguments="
        + arguments
        + ", hostBoundary="
        + hostBoundary
        + "]";
  }
}
