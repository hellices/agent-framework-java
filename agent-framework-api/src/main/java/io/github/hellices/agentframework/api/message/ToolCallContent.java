package io.github.hellices.agentframework.api.message;

import io.github.hellices.agentframework.api.value.JsonObject;
import java.util.Objects;

public final class ToolCallContent extends Content {

  private final String callId;
  private final String name;
  private final JsonObject arguments;

  public ToolCallContent(String callId, String name, JsonObject arguments) {
    this(callId, name, arguments, JsonObject.empty(), null);
  }

  public ToolCallContent(
      String callId,
      String name,
      JsonObject arguments,
      JsonObject additionalProperties,
      Object rawRepresentation) {
    super(additionalProperties, rawRepresentation);
    this.callId = requireText(callId, "callId");
    this.name = requireText(name, "tool name");
    this.arguments = arguments == null ? JsonObject.empty() : arguments;
  }

  public String callId() {
    return callId;
  }

  public String name() {
    return name;
  }

  public JsonObject arguments() {
    return arguments;
  }

  @Override
  public String type() {
    return "tool_call";
  }

  private static String requireText(String value, String label) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(label + " must not be blank");
    }
    return value;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof ToolCallContent that)) {
      return false;
    }
    return callId.equals(that.callId)
        && name.equals(that.name)
        && arguments.equals(that.arguments)
        && baseEquals(that);
  }

  @Override
  public int hashCode() {
    return Objects.hash(callId, name, arguments, baseHashCode());
  }
}
