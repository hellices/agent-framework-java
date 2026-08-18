package io.github.hellices.agentframework.engine.internal.run;

public enum RunPhase {
  VALIDATE,
  LOAD_SESSION,
  PREPARE_CONTEXT,
  RESOLVE_APPROVAL,
  PREPARE_MODEL_REQUEST,
  CALL_MODEL,
  ACCUMULATE_MODEL_UPDATES,
  PLAN_TOOL_ACTION,
  EXECUTE_TOOL_BATCH,
  WAIT_APPROVAL,
  FINALIZE_RESPONSE,
  COMPLETE_CONTEXT,
  PERSIST_SESSION,
  TERMINATED;

  public boolean isTerminal() {
    return this == TERMINATED;
  }
}
