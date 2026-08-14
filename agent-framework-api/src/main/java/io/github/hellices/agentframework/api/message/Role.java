package io.github.hellices.agentframework.api.message;

import java.util.List;
import java.util.Objects;

public final class Role {

  public static final Role SYSTEM = new Role("system");
  public static final Role USER = new Role("user");
  public static final Role ASSISTANT = new Role("assistant");
  public static final Role TOOL = new Role("tool");

  private static final List<String> KNOWN_VALUES = List.of("system", "user", "assistant", "tool");

  private final String value;

  private Role(String value) {
    this.value = value;
  }

  public static Role of(String value) {
    Objects.requireNonNull(value, "value must not be null");
    String normalized = value.trim();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException("value must not be blank");
    }
    return new Role(normalized);
  }

  public String value() {
    return value;
  }

  public static List<String> knownValues() {
    return KNOWN_VALUES;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof Role role)) {
      return false;
    }
    return value.equals(role.value);
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
