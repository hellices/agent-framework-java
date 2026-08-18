package io.github.hellices.agentframework.api.tool;

import io.github.hellices.agentframework.api.value.JsonObject;
import io.github.hellices.agentframework.api.value.JsonString;
import io.github.hellices.agentframework.api.value.JsonValue;
import java.util.Optional;

public final class ToolArguments {

  private static final ToolArguments EMPTY = new ToolArguments(JsonObject.builder().build());

  private final JsonObject values;

  private ToolArguments(JsonObject values) {
    this.values = values;
  }

  public static ToolArguments empty() {
    return EMPTY;
  }

  public static ToolArguments of(JsonObject values) {
    if (values == null || values.values().isEmpty()) {
      return empty();
    }
    return new ToolArguments(values);
  }

  public JsonValue get(String name) {
    return values.values().get(name);
  }

  public Optional<String> string(String name) {
    JsonValue value = get(name);
    if (value == null) {
      return Optional.empty();
    }
    if (!(value instanceof JsonString jsonString)) {
      throw new IllegalStateException("tool argument '" + name + "' is not a string");
    }
    return Optional.of(jsonString.value());
  }

  public JsonObject values() {
    return values;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof ToolArguments that)) {
      return false;
    }
    return values.equals(that.values);
  }

  @Override
  public int hashCode() {
    return values.hashCode();
  }

  @Override
  public String toString() {
    return "ToolArguments" + values;
  }
}
