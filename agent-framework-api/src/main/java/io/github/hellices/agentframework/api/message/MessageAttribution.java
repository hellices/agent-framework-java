package io.github.hellices.agentframework.api.message;

public record MessageAttribution(String sourceType, String sourceId, String originSessionId) {

  public MessageAttribution {
    if (sourceType == null || sourceType.isBlank()) {
      sourceType = "External";
    }
  }
}
