package io.github.hellices.agentframework.api.message;

import io.github.hellices.agentframework.api.value.JsonObject;

public record Usage(
    long inputTokens, long outputTokens, long totalTokens, JsonObject additionalProperties) {

  public Usage(long inputTokens, long outputTokens, long totalTokens) {
    this(inputTokens, outputTokens, totalTokens, JsonObject.empty());
  }

  public Usage {
    additionalProperties = additionalProperties == null ? JsonObject.empty() : additionalProperties;
  }
}
