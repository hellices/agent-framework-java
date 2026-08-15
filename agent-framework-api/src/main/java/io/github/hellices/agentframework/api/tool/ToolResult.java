package io.github.hellices.agentframework.api.tool;

import io.github.hellices.agentframework.api.message.Content;
import java.util.List;
import java.util.Objects;

public record ToolResult(List<Content> content, boolean error) {

  public ToolResult {
    content = content == null ? List.of() : List.copyOf(content);
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
