package io.github.hellices.agentframework.api.tool;

import io.github.hellices.agentframework.api.message.Content;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record ToolResult(List<Content> content, boolean error) {

  public ToolResult {
    List<Content> normalized = new ArrayList<>();
    if (content != null) {
      for (Content item : content) {
        normalized.add(Objects.requireNonNull(item, "content must not contain null entries"));
      }
    }
    content = List.copyOf(normalized);
  }

  public static ToolResult success(Content content) {
    return new ToolResult(
        List.of(Objects.requireNonNull(content, "content must not be null")), false);
  }

  public static ToolResult failure(Content content) {
    return new ToolResult(
        List.of(Objects.requireNonNull(content, "content must not be null")), true);
  }
}
