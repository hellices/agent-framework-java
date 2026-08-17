package io.github.hellices.agentframework.api.value;

public final class JsonBoolean implements JsonValue {

  private static final JsonBoolean TRUE = new JsonBoolean(true);
  private static final JsonBoolean FALSE = new JsonBoolean(false);

  private final boolean value;

  private JsonBoolean(boolean value) {
    this.value = value;
  }

  public static JsonBoolean of(boolean value) {
    return value ? TRUE : FALSE;
  }

  public boolean value() {
    return value;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof JsonBoolean jsonBoolean && value == jsonBoolean.value;
  }

  @Override
  public int hashCode() {
    return Boolean.hashCode(value);
  }

  @Override
  public String toString() {
    return Boolean.toString(value);
  }
}
