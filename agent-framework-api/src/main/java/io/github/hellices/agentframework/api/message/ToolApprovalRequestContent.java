package io.github.hellices.agentframework.api.message;

import io.github.hellices.agentframework.api.value.JsonObject;
import java.util.Objects;
import java.util.Optional;

/**
 * A core-owned request to approve a pending tool call before it runs (TOOL-016).
 *
 * <p>This carries the exact tool call the Java core is asking about — its call id, tool name, and
 * arguments, unchanged from the {@link ToolCallContent} that produced it — plus an optional
 * host-boundary identifier (an MCP server label or equivalent) so the same tool name on two
 * different hosts is never treated as the same approval subject (TOOL-019). A request surfaces as a
 * user-input-request until a matching {@link ToolApprovalResponseContent} resolves it.
 *
 * <p>Once resolved, a request and its matching response are replayed into the next model request as
 * ordinary conversation history, the same as any other message content this core owns — the engine
 * does not strip them out, because whether a wire format has an equivalent concept for "the core
 * asked the caller, and the caller answered" is provider-specific, not something the core-neutral
 * public contract can assume one way or the other. A provider adapter whose wire format has no
 * equivalent is the one responsible for filtering, reordering, or re-encoding this content when it
 * maps history onto that format; the core's job ends at replaying it unchanged.
 */
public final class ToolApprovalRequestContent extends Content {

  private final String requestId;
  private final String toolCallId;
  private final String toolName;
  private final JsonObject arguments;
  private final String hostBoundary;

  public ToolApprovalRequestContent(
      String requestId,
      String toolCallId,
      String toolName,
      JsonObject arguments,
      String hostBoundary) {
    this(requestId, toolCallId, toolName, arguments, hostBoundary, JsonObject.empty(), null);
  }

  public ToolApprovalRequestContent(
      String requestId,
      String toolCallId,
      String toolName,
      JsonObject arguments,
      String hostBoundary,
      JsonObject additionalProperties,
      Object rawRepresentation) {
    super(additionalProperties, rawRepresentation);
    this.requestId = requireText(requestId, "requestId");
    this.toolCallId = requireText(toolCallId, "toolCallId");
    this.toolName = requireText(toolName, "tool name");
    this.arguments = arguments == null ? JsonObject.empty() : arguments;
    this.hostBoundary = hostBoundary;
  }

  public String requestId() {
    return requestId;
  }

  public String toolCallId() {
    return toolCallId;
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
  public String type() {
    return "tool_approval_request";
  }

  /**
   * Whether {@code response} is the response the Java core binds to this request (TOOL-017).
   *
   * <p>Binding is by request id alone: {@link ToolApprovalResponseContent} carries no tool name or
   * arguments of its own, so there is nothing in a response payload that could substitute a
   * different tool call at resolution time. A response naming a different request id is rejected
   * here rather than forwarded to tool execution.
   */
  public boolean isResponseTo(ToolApprovalResponseContent response) {
    Objects.requireNonNull(response, "response must not be null");
    return requestId.equals(response.requestId());
  }

  private static String requireText(String value, String label) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(label + " must not be blank");
    }
    return value;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof ToolApprovalRequestContent that)) {
      return false;
    }
    return requestId.equals(that.requestId)
        && toolCallId.equals(that.toolCallId)
        && toolName.equals(that.toolName)
        && arguments.equals(that.arguments)
        && Objects.equals(hostBoundary, that.hostBoundary)
        && baseEquals(that);
  }

  @Override
  public int hashCode() {
    return Objects.hash(requestId, toolCallId, toolName, arguments, hostBoundary, baseHashCode());
  }

  @Override
  public String toString() {
    return "ToolApprovalRequestContent[requestId="
        + requestId
        + ", toolCallId="
        + toolCallId
        + ", toolName="
        + toolName
        + ", arguments="
        + arguments
        + ", hostBoundary="
        + hostBoundary
        + "]";
  }
}
