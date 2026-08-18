package io.github.hellices.agentframework.spi.telemetry;

/**
 * Outbound SPI port for framework-neutral observability.
 *
 * <p>The engine emits one {@link TelemetryOperation} per semantic unit of work (agent run, model
 * call, tool call, session operation). Operations are nested: a model-call operation is opened
 * inside an agent-run operation and closed before the agent-run operation closes.
 *
 * <p>The default engine configuration uses {@link NoOpTelemetrySink}. Replace it via {@link
 * io.github.hellices.agentframework.engine.AgentEngineBuilder#telemetrySink} to route events to any
 * observability backend. The optional {@code agent-framework-otel} integration maps neutral values
 * to OpenTelemetry GenAI semantic conventions.
 *
 * <p>Sensitive data contract: the engine never passes prompt bodies, model output, tool arguments,
 * tool results, credentials, or personal traces to any sink method. Attribute keys are taken only
 * from {@link TelemetryAttributes}.
 *
 * <p>Implementations must be thread-safe. A single sink may be shared across all concurrent agent
 * runs.
 */
public interface TelemetrySink {

  /**
   * Opens a new operation and returns a handle the caller must close.
   *
   * @param start descriptor for the operation; must not be null
   * @return a live operation handle; never null
   */
  TelemetryOperation start(TelemetryStart start);

  /** Returns the default no-op sink that discards all telemetry. */
  static TelemetrySink noOp() {
    return NoOpTelemetrySink.INSTANCE;
  }
}
