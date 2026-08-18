package io.github.hellices.agentframework.spi.telemetry;

/** No-op {@link TelemetrySink} that discards all telemetry. */
enum NoOpTelemetrySink implements TelemetrySink {
  INSTANCE;

  @Override
  public TelemetryOperation start(TelemetryStart start) {
    return NoOpTelemetryOperation.INSTANCE;
  }

  /** No-op {@link TelemetryOperation}. */
  private enum NoOpTelemetryOperation implements TelemetryOperation {
    INSTANCE;

    @Override
    public TelemetryOperation startChild(TelemetryStart start) {
      return INSTANCE;
    }

    @Override
    public void event(TelemetryEvent event) {}

    @Override
    public void fail(Throwable failure) {}

    @Override
    public void close() {}
  }
}
