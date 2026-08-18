package io.github.hellices.agentframework.api.value;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class JsonObject implements JsonValue {

  private static final JsonObject EMPTY = new JsonObject(Map.of());

  private final Map<String, JsonValue> values;

  private JsonObject(Map<String, JsonValue> values) {
    this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    JsonValues.requireDepthLimit(this);
  }

  public static Builder builder() {
    return new Builder();
  }

  public static JsonObject empty() {
    return EMPTY;
  }

  public Map<String, JsonValue> values() {
    return values;
  }

  public boolean isEmpty() {
    return values.isEmpty();
  }

  public Optional<JsonValue> get(String name) {
    return Optional.ofNullable(values.get(name));
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof JsonObject jsonObject && values.equals(jsonObject.values);
  }

  @Override
  public int hashCode() {
    return values.hashCode();
  }

  @Override
  public String toString() {
    return values.toString();
  }

  public static final class Builder {

    private final LinkedHashMap<String, JsonValue> values = new LinkedHashMap<>();

    private Builder() {}

    public Builder put(String name, JsonValue value) {
      values.put(
          Objects.requireNonNull(name, "name must not be null"),
          Objects.requireNonNull(value, "value must not be null"));
      return this;
    }

    public JsonObject build() {
      return new JsonObject(values);
    }
  }
}
