package io.github.hellices.agentframework.spi.telemetry;

import java.util.Objects;

/**
 * An immutable, named event emitted on an open {@link TelemetryOperation}.
 *
 * <p>Events carry a name and optional framework-neutral attributes. Sensitive data (prompt bodies,
 * model output, tool arguments, tool results) is never populated by the engine by default.
 */
public final class TelemetryEvent {

  private final String name;
  private final TelemetryAttributeMap attributes;

  private TelemetryEvent(String name, TelemetryAttributeMap attributes) {
    this.name = Objects.requireNonNull(name, "name must not be null");
    this.attributes = Objects.requireNonNull(attributes, "attributes must not be null");
  }

  /** Returns the event name. */
  public String name() {
    return name;
  }

  /** Returns a typed, read-only map of event attributes. */
  public TelemetryAttributeMap attributes() {
    return attributes;
  }

  /** Creates an event with only a name and no attributes. */
  public static TelemetryEvent of(String name) {
    return new TelemetryEvent(name, TelemetryAttributeMap.empty());
  }

  /** Creates an event with the given name and pre-built attribute map. */
  public static TelemetryEvent of(String name, TelemetryAttributeMap attributes) {
    return new TelemetryEvent(name, attributes);
  }
}
