package io.github.hellices.agentframework.api.tool;

import io.github.hellices.agentframework.api.message.Content;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class ToolResult {

  private final List<Content> content;
  private final boolean error;

  public ToolResult(List<Content> content, boolean error) {
    List<Content> normalized = new ArrayList<>();
    if (content != null) {
      for (Content item : content) {
        normalized.add(Objects.requireNonNull(item, "content must not contain null entries"));
      }
    }
    this.content = List.copyOf(normalized);
    this.error = error;
  }

  public static ToolResult success(Content content) {
    return new ToolResult(
        List.of(Objects.requireNonNull(content, "content must not be null")), false);
  }

  public static ToolResult failure(Content content) {
    return new ToolResult(
        List.of(Objects.requireNonNull(content, "content must not be null")), true);
  }

  public List<Content> content() {
    return content;
  }

  public boolean error() {
    return error;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof ToolResult that && error == that.error && content.equals(that.content);
  }

  @Override
  public int hashCode() {
    return Objects.hash(content, error);
  }

  @Override
  public String toString() {
    return "ToolResult[content=" + content + ", error=" + error + "]";
  }
}
