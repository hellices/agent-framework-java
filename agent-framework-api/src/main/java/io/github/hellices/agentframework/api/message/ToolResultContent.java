package io.github.hellices.agentframework.api.message;

import io.github.hellices.agentframework.api.value.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class ToolResultContent extends Content {

  private final String callId;
  private final String name;
  private final List<Content> content;
  private final boolean error;

  public ToolResultContent(
      String callId, String name, List<? extends Content> content, boolean error) {
    this(callId, name, content, error, JsonObject.empty(), null);
  }

  public ToolResultContent(
      String callId,
      String name,
      List<? extends Content> content,
      boolean error,
      JsonObject additionalProperties,
      Object rawRepresentation) {
    super(additionalProperties, rawRepresentation);
    if (callId == null || callId.isBlank()) {
      throw new IllegalArgumentException("callId must not be blank");
    }
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("tool name must not be blank");
    }
    this.callId = callId;
    this.name = name;
    List<Content> normalizedContent = new ArrayList<>();
    if (content != null) {
      for (Content item : content) {
        normalizedContent.add(
            java.util.Objects.requireNonNull(item, "content must not contain null entries"));
      }
    }
    this.content = List.copyOf(normalizedContent);
    this.error = error;
  }

  public String callId() {
    return callId;
  }

  public String name() {
    return name;
  }

  public List<Content> content() {
    return content;
  }

  public boolean error() {
    return error;
  }

  @Override
  public String type() {
    return "tool_result";
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof ToolResultContent that)) {
      return false;
    }
    return error == that.error
        && callId.equals(that.callId)
        && name.equals(that.name)
        && content.equals(that.content)
        && baseEquals(that);
  }

  @Override
  public int hashCode() {
    return Objects.hash(callId, name, content, error, baseHashCode());
  }
}
