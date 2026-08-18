package io.github.hellices.agentframework.spi.telemetry;

/**
 * Framework-neutral attribute key constants emitted by the engine.
 *
 * <p>These names are stable across releases. The OTel adapter maps them to GenAI semantic
 * convention attribute names without the engine importing the OTel SDK.
 *
 * <p>The engine populates only these keys and never populates sensitive data (prompt bodies, model
 * output, tool arguments, tool results, credentials, or personal traces).
 */
public final class TelemetryAttributes {

  private TelemetryAttributes() {}

  // ── Agent run ──────────────────────────────────────────────────────────────

  /** Stable identifier of the agent whose run is being observed. */
  public static final String AGENT_ID = "agent.id";

  /** Display name of the agent. */
  public static final String AGENT_NAME = "agent.name";

  // ── Model call ─────────────────────────────────────────────────────────────

  /** Identifier of the model client used for a model call. */
  public static final String MODEL_ID = "model.id";

  /** Number of input tokens consumed by a model call (long). */
  public static final String MODEL_INPUT_TOKENS = "model.input_tokens";

  /** Number of output tokens produced by a model call (long). */
  public static final String MODEL_OUTPUT_TOKENS = "model.output_tokens";

  /** Iteration index of the model call within the enclosing agent run (long, 0-based). */
  public static final String MODEL_ITERATION = "model.iteration";

  // ── Tool call ──────────────────────────────────────────────────────────────

  /** Name of the tool being executed. */
  public static final String TOOL_NAME = "tool.name";

  /** Total number of tool calls in the batch that triggered this execution (long). */
  public static final String TOOL_CALL_COUNT = "tool.call_count";

  /** 0-based index of this tool call within the batch (long). */
  public static final String TOOL_CALL_INDEX = "tool.call_index";

  // ── Session operation ──────────────────────────────────────────────────────

  /** The session operation type: {@code load} or {@code save}. */
  public static final String SESSION_OPERATION = "session.operation";

  /** Identifier of the session being accessed. */
  public static final String SESSION_ID = "session.id";
}
