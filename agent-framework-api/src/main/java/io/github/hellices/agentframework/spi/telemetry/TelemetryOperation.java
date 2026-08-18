package io.github.hellices.agentframework.spi.telemetry;

/**
 * A handle to a live telemetry operation opened by {@link TelemetrySink#start}.
 *
 * <p>The operation spans exactly one logical unit of work (agent run, model call, tool call, or
 * session operation). Callers must call exactly one of {@link #close()} or {@link #fail} to
 * terminate the operation.
 *
 * <p>Lifecycle contract:
 *
 * <ol>
 *   <li>Open: {@link TelemetrySink#start} returns a new operation.
 *   <li>Events: zero or more {@link #event} calls while the operation is open.
 *   <li>Close: exactly one call to {@link #close()} (success) or {@link #fail} (failure), then no
 *       further calls. Calling close or fail a second time on the same operation is a no-op; the
 *       first outcome wins.
 * </ol>
 *
 * <p>Implementations must be thread-safe with respect to the exactly-once close guarantee.
 */
public interface TelemetryOperation extends AutoCloseable {

  /**
   * Records a named event on this operation. The engine calls this for interesting lifecycle points
   * that do not themselves warrant a child operation.
   *
   * @param event the event to record; must not be null
   */
  void event(TelemetryEvent event);

  /**
   * Records the failure that terminated this operation and marks it closed. Has no effect if the
   * operation was already closed.
   *
   * <p>The provided throwable is recorded as the root cause. Wrapping exceptions (e.g. {@code
   * CompletionException}) are unwrapped by the engine before this call so that the identity of the
   * failure seen here is stable and testable.
   *
   * @param failure the exception that caused this operation to fail; must not be null
   */
  void fail(Throwable failure);

  /**
   * Marks this operation as successfully closed. Has no effect if the operation was already closed.
   */
  @Override
  void close();
}
