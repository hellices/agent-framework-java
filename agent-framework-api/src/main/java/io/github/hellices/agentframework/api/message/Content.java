package io.github.hellices.agentframework.api.message;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

public abstract sealed class Content permits TextContent {

  private final Map<String, Object> additionalProperties;
  private final Object rawRepresentation;

  protected Content(Map<String, Object> additionalProperties, Object rawRepresentation) {
    this.additionalProperties = additionalProperties == null ? Map.of() : Collections.unmodifiableMap(new java.util.HashMap<>(additionalProperties));
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
