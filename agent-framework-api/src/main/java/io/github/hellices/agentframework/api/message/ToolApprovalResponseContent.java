package io.github.hellices.agentframework.api.message;

import io.github.hellices.agentframework.api.value.JsonObject;
import java.util.Objects;

/**
 * A core-owned decision on a previously surfaced {@link ToolApprovalRequestContent} (TOOL-016).
 *
 * <p>This carries only the request id it answers and the approve/deny decision — never a tool name
 * or arguments of its own — so an approval response can never smuggle a substituted tool call into
 * execution (TOOL-017): whatever the caller sends back is resolved solely against the matching
 * request's original {@link ToolCallContent}.
 */
public final class ToolApprovalResponseContent extends Content {

  private final String requestId;
  private final boolean approved;

  public ToolApprovalResponseContent(String requestId, boolean approved) {
    this(requestId, approved, JsonObject.empty(), null);
  }

  public ToolApprovalResponseContent(
      String requestId,
      boolean approved,
      JsonObject additionalProperties,
      Object rawRepresentation) {
    super(additionalProperties, rawRepresentation);
    this.requestId = requireText(requestId, "requestId");
    this.approved = approved;
  }

  /** An approval response for {@code requestId} (TOOL-018 denial normalization's counterpart). */
  public static ToolApprovalResponseContent approve(String requestId) {
    return new ToolApprovalResponseContent(requestId, true);
  }

  /**
   * A denial response for {@code requestId}, normalized to a stable {@code approved=false} value
   * regardless of how the caller expressed the denial (TOOL-018).
   */
  public static ToolApprovalResponseContent deny(String requestId) {
    return new ToolApprovalResponseContent(requestId, false);
  }

  public String requestId() {
    return requestId;
  }

  public boolean approved() {
    return approved;
  }

  @Override
  public String type() {
    return "tool_approval_response";
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
    if (!(other instanceof ToolApprovalResponseContent that)) {
      return false;
    }
    return approved == that.approved && requestId.equals(that.requestId) && baseEquals(that);
  }

  @Override
  public int hashCode() {
    return Objects.hash(requestId, approved, baseHashCode());
  }

  @Override
  public String toString() {
    return "ToolApprovalResponseContent[requestId=" + requestId + ", approved=" + approved + "]";
  }
}
