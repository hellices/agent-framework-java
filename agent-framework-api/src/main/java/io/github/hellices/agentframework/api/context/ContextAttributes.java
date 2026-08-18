package io.github.hellices.agentframework.api.context;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable execution-only attribute bag keyed by {@link ContextKey}.
 *
 * <p>The bag's shape is immutable once built. Stored values are retained by reference, so callers
 * must supply values with stable immutable semantics for the lifetime of the attributes instance.
 */
public final class ContextAttributes {

  private static final ContextAttributes EMPTY = new ContextAttributes(Map.of());

  private final Map<KeyIdentity, Entry<?>> entries;

  private ContextAttributes(Map<KeyIdentity, Entry<?>> entries) {
    this.entries = entries;
  }

  public static ContextAttributes empty() {
    return EMPTY;
  }

  public static Builder builder() {
    return new Builder();
  }

  public <T> Optional<T> get(ContextKey<T> key) {
    Objects.requireNonNull(key, "key must not be null");
    Entry<?> entry = entries.get(KeyIdentity.of(key));
    if (entry == null || !entry.key.equals(key)) {
      return Optional.empty();
    }
    return Optional.of(key.type().cast(entry.value));
  }

  public Builder toBuilder() {
    Builder builder = new Builder();
    builder.entries.putAll(entries);
    return builder;
  }

  public ContextAttributes merge(ContextAttributes override) {
    Objects.requireNonNull(override, "override must not be null");
    if (override.entries.isEmpty()) {
      return this;
    }
    Builder builder = toBuilder();
    for (Entry<?> entry : override.entries.values()) {
      builder.putEntry(entry);
    }
    return builder.build();
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof ContextAttributes that)) {
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
    return "ContextAttributes" + entries.values();
  }

  public static final class Builder {
    private final Map<KeyIdentity, Entry<?>> entries = new LinkedHashMap<>();

    private Builder() {}

    /**
     * Stores an execution attribute.
     *
     * <p>Values are retained by reference, so callers must pass immutable values or otherwise
     * ensure the value is not mutated after insertion.
     */
    public <T> Builder put(ContextKey<T> key, T value) {
      Objects.requireNonNull(key, "key must not be null");
      Objects.requireNonNull(value, "value must not be null");
      if (!key.type().isInstance(value)) {
        throw new IllegalArgumentException(
            "value for "
                + key.namespace()
                + "/"
                + key.name()
                + " must be an instance of "
                + key.type().getName());
      }
      KeyIdentity identity = KeyIdentity.of(key);
      Entry<?> existing = entries.get(identity);
      if (existing != null && !existing.key.type().equals(key.type())) {
        throw new IllegalArgumentException(
            "context attribute key collision for "
                + key.namespace()
                + "/"
                + key.name()
                + ": "
                + existing.key.type().getName()
                + " vs "
                + key.type().getName());
      }
      entries.put(identity, new Entry<>(key, value));
      return this;
    }

    public ContextAttributes build() {
      if (entries.isEmpty()) {
        return empty();
      }
      return new ContextAttributes(Map.copyOf(entries));
    }

    private <T> void putEntry(Entry<T> entry) {
      put(entry.key, entry.value);
    }
  }

  private record KeyIdentity(String namespace, String name) {
    private static KeyIdentity of(ContextKey<?> key) {
      return new KeyIdentity(key.namespace(), key.name());
    }
  }

  private static final class Entry<T> {
    private final ContextKey<T> key;
    private final T value;

    private Entry(ContextKey<T> key, T value) {
      this.key = key;
      this.value = value;
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
