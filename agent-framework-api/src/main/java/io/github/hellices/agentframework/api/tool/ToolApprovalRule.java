package io.github.hellices.agentframework.api.tool;

import io.github.hellices.agentframework.api.value.JsonObject;
import java.util.Objects;
import java.util.Optional;

/**
 * A standing approval a caller granted ahead of time, so a matching tool call runs without another
 * caller round trip (TOOL-019).
 *
 * <p>A rule is scoped three ways. The tool name always participates. The arguments are either
 * {@code null}, meaning the rule covers every call of that tool, or an exact {@link JsonObject},
 * meaning the rule covers only a call whose arguments are equal to it — an empty object is
 * therefore a rule about no-argument calls, not a wildcard. The host boundary participates
 * strictly: a rule without one matches only calls without one, so the same tool name exposed by two
 * MCP servers is never approved by a rule granted for the other.
 */
public final class ToolApprovalRule {

  private final String toolName;
  private final JsonObject arguments;
  private final String hostBoundary;

  private ToolApprovalRule(String toolName, JsonObject arguments, String hostBoundary) {
    if (toolName == null || toolName.isBlank()) {
      throw new IllegalArgumentException("tool name must not be blank");
    }
    this.toolName = toolName;
    this.arguments = arguments;
    this.hostBoundary = hostBoundary;
  }

  /** A rule covering every call of {@code toolName} that carries no host boundary. */
  public static ToolApprovalRule forTool(String toolName) {
    return new ToolApprovalRule(toolName, null, null);
  }

  /** A rule covering every call of {@code toolName} on {@code hostBoundary}. */
  public static ToolApprovalRule forTool(String toolName, String hostBoundary) {
    return new ToolApprovalRule(toolName, null, hostBoundary);
  }

  /** A rule covering only calls of {@code toolName} whose arguments equal {@code arguments}. */
  public static ToolApprovalRule forArguments(String toolName, JsonObject arguments) {
    return forArguments(toolName, arguments, null);
  }

  /**
   * A rule covering only calls of {@code toolName} on {@code hostBoundary} whose arguments equal
   * {@code arguments}.
   */
  public static ToolApprovalRule forArguments(
      String toolName, JsonObject arguments, String hostBoundary) {
    return new ToolApprovalRule(
        toolName,
        Objects.requireNonNull(arguments, "arguments must not be null for an exact-argument rule"),
        hostBoundary);
  }

  public String toolName() {
    return toolName;
  }

  /** The exact arguments this rule is scoped to, or empty when it covers the whole tool. */
  public Optional<JsonObject> arguments() {
    return Optional.ofNullable(arguments);
  }

  /** The host boundary this rule is scoped to, when it is scoped to one. */
  public Optional<String> hostBoundary() {
    return Optional.ofNullable(hostBoundary);
  }

  /** Whether this rule approves the pending call {@code context} describes. */
  public boolean matches(ToolApprovalContext context) {
    Objects.requireNonNull(context, "context must not be null");
    return toolName.equals(context.toolName())
        && Objects.equals(hostBoundary, context.hostBoundary().orElse(null))
        && (arguments == null || arguments.equals(context.arguments()));
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof ToolApprovalRule that)) {
      return false;
    }
    return toolName.equals(that.toolName)
        && Objects.equals(arguments, that.arguments)
        && Objects.equals(hostBoundary, that.hostBoundary);
  }

  @Override
  public int hashCode() {
    return Objects.hash(toolName, arguments, hostBoundary);
  }

  @Override
  public String toString() {
    return "ToolApprovalRule[toolName="
        + toolName
        + ", arguments="
        + arguments
        + ", hostBoundary="
        + hostBoundary
        + "]";
  }
}
