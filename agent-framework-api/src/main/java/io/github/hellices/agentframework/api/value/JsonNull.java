package io.github.hellices.agentframework.api.value;

public final class JsonNull implements JsonValue {

  private static final JsonNull INSTANCE = new JsonNull();

  private JsonNull() {}

  public static JsonNull instance() {
    return INSTANCE;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof JsonNull;
  }

  @Override
  public int hashCode() {
    return JsonNull.class.hashCode();
  }

  @Override
  public String toString() {
    return "null";
  }
}
