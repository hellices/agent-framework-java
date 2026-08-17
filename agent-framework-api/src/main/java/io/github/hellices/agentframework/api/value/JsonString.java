package io.github.hellices.agentframework.api.value;

import java.util.Objects;

public final class JsonString implements JsonValue {

  private final String value;

  private JsonString(String value) {
    this.value = value;
  }

  public static JsonString of(String value) {
    return new JsonString(Objects.requireNonNull(value, "value must not be null"));
  }

  public String value() {
    return value;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof JsonString jsonString && value.equals(jsonString.value);
  }

  @Override
  public int hashCode() {
    return value.hashCode();
  }

  @Override
  public String toString() {
    return value;
  }
}
