package io.github.hellices.agentframework.spi.telemetry;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * An immutable, typed map of telemetry attributes.
 *
 * <p>Attribute values are restricted to {@code String} and {@code Long} — the types that map
 * cleanly to every observability backend without further conversion.
 *
 * <p>This class does not expose {@code Map<String, Object>} in its public contract. Consumers read
 * typed values through {@link #getString} and {@link #getLong}. Builders use {@link #builder()} and
 * typed {@code put} overloads.
 */
public final class TelemetryAttributeMap {

  private static final TelemetryAttributeMap EMPTY = new TelemetryAttributeMap(Map.of());

  private final Map<String, Object> entries;

  private TelemetryAttributeMap(Map<String, Object> entries) {
    this.entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
  }

  /** Returns an empty attribute map. */
  public static TelemetryAttributeMap empty() {
    return EMPTY;
  }

  /** Returns a new builder. */
  public static Builder builder() {
    return new Builder();
  }

  /** Returns the string value for the given key, or {@code null} if absent or not a string. */
  public String getString(String key) {
    Object v = entries.get(key);
    return v instanceof String s ? s : null;
  }

  /** Returns the long value for the given key, or {@code null} if absent or not a long. */
  public Long getLong(String key) {
    Object v = entries.get(key);
    return v instanceof Long l ? l : null;
  }

  /** Returns {@code true} if this map contains no entries. */
  public boolean isEmpty() {
    return entries.isEmpty();
  }

  /** Returns the set of attribute keys. */
  public Set<String> keySet() {
    return entries.keySet();
  }

  /**
   * Iterates all entries, invoking the visitor for each key and its typed value.
   *
   * @param visitor receives each key and one of: {@code String}, {@code Long}, or {@code Boolean}
   */
  public void forEach(EntryVisitor visitor) {
    Objects.requireNonNull(visitor, "visitor must not be null");
    entries.forEach((k, v) -> visitor.accept(k, v));
  }

  /** Callback interface for iterating typed attribute entries without exposing raw maps. */
  @FunctionalInterface
  public interface EntryVisitor {
    /**
     * Called for each attribute entry.
     *
     * @param key the attribute key
     * @param value one of: {@code String}, {@code Long}, or {@code Boolean}
     */
    void accept(String key, Object value);
  }

  /** Builder for {@link TelemetryAttributeMap}. */
  public static final class Builder {

    private final Map<String, Object> entries = new LinkedHashMap<>();

    private Builder() {}

    /** Adds a string attribute. */
    public Builder put(String key, String value) {
      entries.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(value, "value"));
      return this;
    }

    /** Adds a long attribute. */
    public Builder put(String key, long value) {
      entries.put(Objects.requireNonNull(key, "key"), value);
      return this;
    }

    /** Returns the built map. */
    public TelemetryAttributeMap build() {
      return new TelemetryAttributeMap(entries);
    }
  }
}
