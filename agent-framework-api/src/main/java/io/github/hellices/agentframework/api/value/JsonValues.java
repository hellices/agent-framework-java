package io.github.hellices.agentframework.api.value;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public final class JsonValues {

  private static final int MAX_NESTING_DEPTH = 64;

  private JsonValues() {}

  public static JsonValue fromJava(Object value) {
    return fromJava(value, 0);
  }

  static void requireDepthLimit(JsonValue value) {
    validateDepth(value, 0);
  }

  private static JsonValue fromJava(Object value, int depth) {
    if (depth > MAX_NESTING_DEPTH) {
      throw new IllegalArgumentException("state payload exceeds the nesting depth limit");
    }
    if (value == null) {
      return JsonNull.instance();
    }
    if (value instanceof JsonValue jsonValue) {
      requireDepthLimit(jsonValue);
      return jsonValue;
    }
    if (value instanceof Boolean booleanValue) {
      return JsonBoolean.of(booleanValue);
    }
    if (value instanceof String stringValue) {
      return JsonString.of(stringValue);
    }
    if (value instanceof Number number) {
      return JsonNumber.of(number);
    }
    if (value instanceof Map<?, ?> map) {
      JsonObject.Builder builder = JsonObject.builder();
      map.entrySet().stream()
          .sorted(Comparator.comparing(entry -> stringifyKey(entry.getKey())))
          .forEach(
              entry ->
                  builder.put(stringifyKey(entry.getKey()), fromJava(entry.getValue(), depth + 1)));
      return builder.build();
    }
    if (value instanceof List<?> list) {
      List<JsonValue> copy = new ArrayList<>(list.size());
      for (Object item : list) {
        copy.add(fromJava(item, depth + 1));
      }
      return JsonArray.of(copy);
    }
    throw new IllegalArgumentException(
        "state payload contains unsupported type: " + value.getClass().getName());
  }

  private static String stringifyKey(Object key) {
    if (key instanceof String stringKey) {
      return stringKey;
    }
    throw new IllegalArgumentException("state payload map keys must be strings");
  }

  private static void validateDepth(JsonValue value, int depth) {
    if (depth > MAX_NESTING_DEPTH) {
      throw new IllegalArgumentException("state payload exceeds the nesting depth limit");
    }
    if (value instanceof JsonArray array) {
      for (JsonValue item : array.values()) {
        validateDepth(item, depth + 1);
      }
      return;
    }
    if (value instanceof JsonObject object) {
      for (JsonValue item : object.values().values()) {
        validateDepth(item, depth + 1);
      }
    }
  }
}
