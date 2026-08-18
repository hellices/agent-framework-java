package io.github.hellices.agentframework.spi.telemetry;

import java.util.Objects;

/**
 * Immutable descriptor passed to {@link TelemetrySink#start} to open a new operation.
 *
 * <p>The attributes carry framework-neutral key/value metadata. The engine populates only stable,
 * non-sensitive keys defined in {@link TelemetryAttributes}. Prompt bodies, model output, tool
 * arguments, tool results, credentials, and personal traces are never populated by default.
 */
public final class TelemetryStart {

  private final TelemetryOperationKind kind;
  private final String operationName;
  private final TelemetryAttributeMap attributes;

  private TelemetryStart(Builder builder) {
    this.kind = Objects.requireNonNull(builder.kind, "kind must not be null");
    this.operationName =
        Objects.requireNonNull(builder.operationName, "operationName must not be null");
    this.attributes = builder.attributeBuilder.build();
  }

  /** Returns the semantic category of the operation. */
  public TelemetryOperationKind kind() {
    return kind;
  }

  /** Returns the stable, human-readable operation name. */
  public String operationName() {
    return operationName;
  }

  /**
   * Returns a typed, read-only map of framework-neutral metadata attributes. Sensitive data is
   * never present.
   */
  public TelemetryAttributeMap attributes() {
    return attributes;
  }

  /** Returns a new builder for the given kind and operation name. */
  public static Builder builder(TelemetryOperationKind kind, String operationName) {
    return new Builder(kind, operationName);
  }

  public static final class Builder {
    private final TelemetryOperationKind kind;
    private final String operationName;
    private final TelemetryAttributeMap.Builder attributeBuilder = TelemetryAttributeMap.builder();

    private Builder(TelemetryOperationKind kind, String operationName) {
      this.kind = kind;
      this.operationName = operationName;
    }

    /** Adds a string attribute. */
    public Builder attribute(String key, String value) {
      attributeBuilder.put(key, value);
      return this;
    }

    /** Adds a long attribute. */
    public Builder attribute(String key, long value) {
      attributeBuilder.put(key, value);
      return this;
    }

    public TelemetryStart build() {
      return new TelemetryStart(this);
    }
  }
}
