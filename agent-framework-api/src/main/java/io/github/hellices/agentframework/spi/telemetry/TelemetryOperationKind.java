package io.github.hellices.agentframework.spi.telemetry;

/**
 * The semantic category of a {@link TelemetryOperation}.
 *
 * <p>A sink maps each kind to its backend's span-type or metric dimension. The engine never imports
 * backend-specific conventions.
 */
public enum TelemetryOperationKind {
  /** A complete agent run: model calls, tool calls, and session activity combined. */
  AGENT_RUN,

  /** A single model invocation within a run. */
  MODEL_CALL,

  /** A single tool execution within a run. */
  TOOL_CALL,

  /** A session store read or write. */
  SESSION_OPERATION,
}
