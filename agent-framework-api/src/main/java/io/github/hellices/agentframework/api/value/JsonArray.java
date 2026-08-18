package io.github.hellices.agentframework.api.value;

import java.util.List;
import java.util.Objects;

public final class JsonArray implements JsonValue {

  private final List<JsonValue> values;

  private JsonArray(List<JsonValue> values) {
    this.values = List.copyOf(values);
    JsonValues.requireDepthLimit(this);
  }

  public static JsonArray of(List<? extends JsonValue> values) {
    Objects.requireNonNull(values, "values must not be null");
    return new JsonArray(List.copyOf(values));
  }

  public List<JsonValue> values() {
    return values;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof JsonArray jsonArray && values.equals(jsonArray.values);
  }

  @Override
  public int hashCode() {
    return values.hashCode();
  }

  @Override
  public String toString() {
    return values.toString();
  }
}
