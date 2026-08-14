package io.github.hellices.agentframework.api.message;

import java.util.Map;

public record Usage(
    long inputTokens,
    long outputTokens,
    long totalTokens,
    Map<String, Object> additionalProperties) {

  public Usage(long inputTokens, long outputTokens, long totalTokens) {
    this(inputTokens, outputTokens, totalTokens, Map.of());
  }

  public Usage {
    additionalProperties =
        additionalProperties == null ? Map.of() : Map.copyOf(additionalProperties);
  }
}
