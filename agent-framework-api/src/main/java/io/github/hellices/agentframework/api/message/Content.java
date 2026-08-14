package io.github.hellices.agentframework.api.message;

import java.util.Map;

public abstract sealed class Content permits TextContent {

  private final Map<String, Object> additionalProperties;
  private final Object rawRepresentation;

  protected Content(Map<String, Object> additionalProperties, Object rawRepresentation) {
    this.additionalProperties =
        additionalProperties == null ? Map.of() : Map.copyOf(additionalProperties);
    this.rawRepresentation = rawRepresentation;
  }

  public Map<String, Object> additionalProperties() {
    return additionalProperties;
  }

  public Object rawRepresentation() {
    return rawRepresentation;
  }

  public abstract String type();

  public String text() {
    return "";
  }
}
