package io.github.hellices.agentframework.engine.internal.run;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;

/** Explicit transition and terminal-outcome owner for one run. */
public final class RunStateMachine {

  private static final Map<RunPhase, EnumSet<RunPhase>> ALLOWED_TRANSITIONS = allowedTransitions();

  private final RunState state;

  public RunStateMachine() {
    this.state = new RunState();
  }

  public RunPhase phase() {
    return state.phase();
  }

  public RunState state() {
    return state;
  }

  public void transitionTo(RunPhase next) {
    Objects.requireNonNull(next, "next must not be null");
    state.transitionTo(next, current -> ALLOWED_TRANSITIONS.get(current).contains(next));
  }

  public void fail(Throwable failure) {
    state.terminalize(RunState.Outcome.FAILURE, failure);
  }

  public void cancel() {
    state.terminalize(RunState.Outcome.CANCELLED, new CancellationException("run was cancelled"));
  }

  private static Map<RunPhase, EnumSet<RunPhase>> allowedTransitions() {
    Map<RunPhase, EnumSet<RunPhase>> allowed = new EnumMap<>(RunPhase.class);
    allowed.put(RunPhase.VALIDATE, EnumSet.of(RunPhase.LOAD_SESSION));
    allowed.put(RunPhase.LOAD_SESSION, EnumSet.of(RunPhase.PREPARE_CONTEXT));
    allowed.put(RunPhase.PREPARE_CONTEXT, EnumSet.of(RunPhase.PREPARE_MODEL_REQUEST));
    allowed.put(RunPhase.PREPARE_MODEL_REQUEST, EnumSet.of(RunPhase.CALL_MODEL));
    allowed.put(RunPhase.CALL_MODEL, EnumSet.of(RunPhase.ACCUMULATE_MODEL_UPDATES));
    allowed.put(RunPhase.ACCUMULATE_MODEL_UPDATES, EnumSet.of(RunPhase.PLAN_TOOL_ACTION));
    allowed.put(
        RunPhase.PLAN_TOOL_ACTION,
        EnumSet.of(RunPhase.EXECUTE_TOOL_BATCH, RunPhase.FINALIZE_RESPONSE));
    allowed.put(RunPhase.EXECUTE_TOOL_BATCH, EnumSet.of(RunPhase.PREPARE_MODEL_REQUEST));
    allowed.put(RunPhase.FINALIZE_RESPONSE, EnumSet.of(RunPhase.COMPLETE_CONTEXT));
    allowed.put(RunPhase.COMPLETE_CONTEXT, EnumSet.of(RunPhase.PERSIST_SESSION));
    allowed.put(RunPhase.PERSIST_SESSION, EnumSet.of(RunPhase.TERMINATED));
    allowed.put(RunPhase.TERMINATED, EnumSet.noneOf(RunPhase.class));
    return allowed;
  }
}
