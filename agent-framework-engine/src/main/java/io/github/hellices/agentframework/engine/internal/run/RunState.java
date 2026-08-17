package io.github.hellices.agentframework.engine.internal.run;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

/** Mutable per-run data with snapshot-only readers. */
public final class RunState {

  private final Object lock = new Object();
  private RunPhase phase = RunPhase.VALIDATE;
  private Outcome outcome;
  private Object terminalCause;
  private final List<RunPhase> phaseHistory = new ArrayList<>(List.of(RunPhase.VALIDATE));

  public RunPhase phase() {
    synchronized (lock) {
      return phase;
    }
  }

  public boolean isTerminal() {
    synchronized (lock) {
      return phase.isTerminal();
    }
  }

  public java.util.Optional<Outcome> outcome() {
    synchronized (lock) {
      return java.util.Optional.ofNullable(outcome);
    }
  }

  public <T> java.util.Optional<T> mapTerminalCause(
      Function<? super Throwable, ? extends T> mapper) {
    Objects.requireNonNull(mapper, "mapper must not be null");
    synchronized (lock) {
      if (terminalCause == null) {
        return java.util.Optional.empty();
      }
      Throwable cause = (Throwable) terminalCause;
      return java.util.Optional.ofNullable(mapper.apply(cause));
    }
  }

  public List<RunPhase> phaseHistory() {
    synchronized (lock) {
      return List.copyOf(phaseHistory);
    }
  }

  void transitionTo(RunPhase next, Predicate<RunPhase> allowedTransition) {
    Objects.requireNonNull(next, "next must not be null");
    Objects.requireNonNull(allowedTransition, "allowedTransition must not be null");
    synchronized (lock) {
      RunPhase current = phase;
      if (current.isTerminal()) {
        throw illegalTransition(current, next);
      }
      if (!allowedTransition.test(current)) {
        throw illegalTransition(current, next);
      }
      phase = next;
      phaseHistory.add(next);
      if (next.isTerminal()) {
        outcome = Outcome.SUCCESS;
        terminalCause = null;
      }
    }
  }

  boolean terminalize(Outcome terminalOutcome, Throwable cause) {
    Objects.requireNonNull(terminalOutcome, "terminalOutcome must not be null");
    if (terminalOutcome == Outcome.SUCCESS && cause != null) {
      throw new IllegalArgumentException("success terminalization must not have a cause");
    }
    if (terminalOutcome != Outcome.SUCCESS && cause == null) {
      throw new IllegalArgumentException(terminalOutcome + " terminalization must have a cause");
    }
    synchronized (lock) {
      if (phase.isTerminal()) {
        return false;
      }
      phase = RunPhase.TERMINATED;
      phaseHistory.add(RunPhase.TERMINATED);
      outcome = terminalOutcome;
      terminalCause = cause;
      return true;
    }
  }

  private static IllegalStateException illegalTransition(RunPhase current, RunPhase next) {
    return new IllegalStateException("cannot transition from " + current + " to " + next);
  }

  public enum Outcome {
    SUCCESS,
    FAILURE,
    CANCELLED
  }
}
