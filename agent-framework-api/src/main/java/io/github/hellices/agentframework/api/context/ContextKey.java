package io.github.hellices.agentframework.api.context;

import java.util.Objects;

public final class ContextKey<T> {

  private final String namespace;
  private final String name;
  private final Class<T> type;

  private ContextKey(String namespace, String name, Class<T> type) {
    this.namespace = normalize(namespace, "namespace");
    this.name = normalize(name, "name");
    this.type = Objects.requireNonNull(type, "type must not be null");
  }

  public static <T> ContextKey<T> of(String namespace, String name, Class<T> type) {
    return new ContextKey<>(namespace, name, type);
  }

  public String namespace() {
    return namespace;
  }

  public String name() {
    return name;
  }

  public Class<T> type() {
    return type;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof ContextKey<?> that)) {
      return false;
    }
    return namespace.equals(that.namespace) && name.equals(that.name) && type.equals(that.type);
  }

  @Override
  public int hashCode() {
    return Objects.hash(namespace, name, type);
  }

  @Override
  public String toString() {
    return "ContextKey[" + namespace + "/" + name + ":" + type.getName() + "]";
  }

  private static String normalize(String value, String label) {
    Objects.requireNonNull(value, label + " must not be null");
    if (value.isBlank()) {
      throw new IllegalArgumentException(label + " must not be blank");
    }
    return value;
  }
}
