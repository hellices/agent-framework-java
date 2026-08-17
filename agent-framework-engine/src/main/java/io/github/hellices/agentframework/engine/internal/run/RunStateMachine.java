package io.github.hellices.agentframework.engine.internal.run;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;

/** Explicit transition and terminal-outcome owner for one run. */
public final class RunStateMachine {

  private static final Map<RunPhase, EnumSet<RunPhase>> ALLOWED_TRANSITIONS = allowedTransitions();

  private final RunState state;
  private final Object terminalListenersMonitor = new Object();
  private List<Runnable> terminalListeners = new ArrayList<>();
  private boolean terminalListenersClosed;

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
    if (next.isTerminal()) {
      notifyTerminalListeners();
    }
  }

  public Runnable onTerminal(Runnable listener) {
    Runnable value = Objects.requireNonNull(listener, "listener must not be null");
    if (state.isTerminal()) {
      invokeTerminalListener(value);
      return () -> {};
    }
    boolean invokeNow = false;
    synchronized (terminalListenersMonitor) {
      if (terminalListenersClosed) {
        invokeNow = true;
      } else {
        terminalListeners.add(value);
      }
    }
    if (invokeNow) {
      invokeTerminalListener(value);
      return () -> {};
    }
    return () -> removeTerminalListener(value);
  }

  public void fail(Throwable failure) {
    if (state.terminalize(RunState.Outcome.FAILURE, failure)) {
      notifyTerminalListeners();
    }
  }

  public void cancel() {
    if (state.terminalize(
        RunState.Outcome.CANCELLED, new CancellationException("run was cancelled"))) {
      notifyTerminalListeners();
    }
  }

  private void removeTerminalListener(Runnable listener) {
    synchronized (terminalListenersMonitor) {
      if (terminalListenersClosed) {
        return;
      }
      terminalListeners.remove(listener);
    }
  }

  private void notifyTerminalListeners() {
    List<Runnable> listeners;
    synchronized (terminalListenersMonitor) {
      if (terminalListenersClosed) {
        return;
      }
      listeners = List.copyOf(terminalListeners);
      terminalListeners = List.of();
      terminalListenersClosed = true;
    }
    RuntimeException firstFailure = null;
    for (Runnable listener : listeners) {
      try {
        invokeTerminalListener(listener);
      } catch (RuntimeException failure) {
        if (firstFailure == null) {
          firstFailure = failure;
        } else {
          firstFailure.addSuppressed(failure);
        }
      }
    }
    if (firstFailure != null) {
      throw firstFailure;
    }
  }

  private static void invokeTerminalListener(Runnable listener) {
    listener.run();
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
