package io.github.hellices.agentframework.api.session;

import java.util.Objects;

public final class SessionStateKey<T> {

  private final String id;
  private final Class<T> type;

  private SessionStateKey(String id, Class<T> type) {
    this.id = normalize(id);
    this.type = Objects.requireNonNull(type, "type must not be null");
  }

  public static <T> SessionStateKey<T> of(String id, Class<T> type) {
    return new SessionStateKey<>(id, type);
  }

  public String id() {
    return id;
  }

  public Class<T> type() {
    return type;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof SessionStateKey<?> that)) {
      return false;
    }
    return id.equals(that.id) && type.equals(that.type);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id, type);
  }

  @Override
  public String toString() {
    return "SessionStateKey[" + id + ":" + type.getName() + "]";
  }

  private static String normalize(String id) {
    Objects.requireNonNull(id, "id must not be null");
    if (id.isBlank()) {
      throw new IllegalArgumentException("id must not be blank");
    }
    return id;
  }
}
