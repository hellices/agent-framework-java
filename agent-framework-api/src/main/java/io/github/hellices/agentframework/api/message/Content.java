package io.github.hellices.agentframework.api.message;

import io.github.hellices.agentframework.api.value.JsonObject;
import java.util.Objects;

public abstract sealed class Content
    permits TextContent, ToolCallContent, ToolResultContent, ExtensionContent {

  private final JsonObject additionalProperties;
  private final transient Object rawRepresentation;

  protected Content(JsonObject additionalProperties, Object rawRepresentation) {
    this.additionalProperties =
        additionalProperties == null ? JsonObject.empty() : additionalProperties;
    this.rawRepresentation = rawRepresentation;
  }

  public JsonObject additionalProperties() {
    return additionalProperties;
  }

  public Object rawRepresentation() {
    return rawRepresentation;
  }

  public abstract String type();

  public String text() {
    return "";
  }

  protected final boolean baseEquals(Content other) {
    return additionalProperties.equals(other.additionalProperties)
        && Objects.equals(rawRepresentation, other.rawRepresentation);
  }

  protected final int baseHashCode() {
    return Objects.hash(additionalProperties, rawRepresentation);
  }
}
