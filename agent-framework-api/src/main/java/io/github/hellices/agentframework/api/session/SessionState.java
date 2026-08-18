package io.github.hellices.agentframework.api.session;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class SessionState {

  private static final SessionState EMPTY = new SessionState(Map.of());

  private final Map<String, Entry<?>> entries;

  private SessionState(Map<String, Entry<?>> entries) {
    this.entries = entries;
  }

  public static SessionState empty() {
    return EMPTY;
  }

  public <T> Optional<T> get(SessionStateKey<T> key) {
    Objects.requireNonNull(key, "key must not be null");
    Entry<?> entry = entries.get(key.id());
    if (entry == null) {
      return Optional.empty();
    }
    ensureMatchingKey(key, entry.key);
    return Optional.of(key.type().cast(entry.value));
  }

  public <T> SessionState with(SessionStateKey<T> key, T value) {
    Objects.requireNonNull(key, "key must not be null");
    return withEntry(entry(key, value));
  }

  public SessionState without(SessionStateKey<?> key) {
    Objects.requireNonNull(key, "key must not be null");
    Entry<?> entry = entries.get(key.id());
    if (entry == null) {
      return this;
    }
    ensureMatchingKey(key, entry.key);
    return withoutId(key.id());
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof SessionState that)) {
      return false;
    }
    return entries.equals(that.entries);
  }

  @Override
  public int hashCode() {
    return entries.hashCode();
  }

  @Override
  public String toString() {
    return "SessionState" + entries.values();
  }

  Entry<?> entry(String id) {
    return entries.get(id);
  }

  SessionState withoutId(String id) {
    Entry<?> entry = entries.get(id);
    if (entry == null) {
      return this;
    }
    if (entries.size() == 1) {
      return empty();
    }
    LinkedHashMap<String, Entry<?>> updated = new LinkedHashMap<>(entries);
    updated.remove(id);
    return new SessionState(immutableEntries(updated));
  }

  SessionState withEntry(Entry<?> entry) {
    Objects.requireNonNull(entry, "entry must not be null");
    Entry<?> existing = entries.get(entry.key.id());
    if (existing != null) {
      ensureMatchingKey(entry.key, existing.key);
      if (existing.equals(entry)) {
        return this;
      }
    }
    LinkedHashMap<String, Entry<?>> updated = new LinkedHashMap<>(entries);
    updated.put(entry.key.id(), entry);
    return new SessionState(immutableEntries(updated));
  }

  static <T> Entry<T> entry(SessionStateKey<T> key, T value) {
    return new Entry<>(key, SessionStateValues.normalizeValue(key, value));
  }

  @SuppressWarnings("unchecked")
  static <T> SessionStateKey<T> dynamicKey(String id, Object value) {
    if (SessionStateValues.isJsonValueShape(value)) {
      return (SessionStateKey<T>)
          SessionStateKey.of(id, io.github.hellices.agentframework.api.value.JsonValue.class);
    }
    return (SessionStateKey<T>) SessionStateKey.of(id, value.getClass());
  }

  private static void ensureMatchingKey(SessionStateKey<?> requested, SessionStateKey<?> stored) {
    if (!stored.type().equals(requested.type())) {
      throw new IllegalArgumentException(
          "session state key collision for "
              + requested.id()
              + ": "
              + stored.type().getName()
              + " vs "
              + requested.type().getName());
    }
  }

  private static Map<String, Entry<?>> immutableEntries(Map<String, Entry<?>> entries) {
    return Collections.unmodifiableMap(new LinkedHashMap<>(entries));
  }

  static final class Entry<T> {
    private final SessionStateKey<T> key;
    private final T value;

    private Entry(SessionStateKey<T> key, T value) {
      this.key = key;
      this.value = value;
    }

    SessionStateKey<T> key() {
      return key;
    }

    T value() {
      return value;
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      if (!(other instanceof Entry<?> that)) {
        return false;
      }
      return key.equals(that.key) && value.equals(that.value);
    }

    @Override
    public int hashCode() {
      return Objects.hash(key, value);
    }

    @Override
    public String toString() {
      return key + "=" + value;
    }
  }
}
