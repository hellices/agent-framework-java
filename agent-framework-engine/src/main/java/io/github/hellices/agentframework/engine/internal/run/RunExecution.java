package io.github.hellices.agentframework.engine.internal.run;

import io.github.hellices.agentframework.api.agent.AgentDefinition;
import io.github.hellices.agentframework.api.agent.AgentRunRequest;
import io.github.hellices.agentframework.api.agent.AgentRuntime;
import io.github.hellices.agentframework.api.agent.CancellationSignal;
import java.util.Objects;
import java.util.UUID;

/** One run's immutable collaborators and mutable execution state. */
public final class RunExecution {

  private final String id;
  private final AgentRunRequest input;
  private final AgentDefinition definition;
  private final AgentRuntime runtime;
  private final CancellationSignal cancellationSignal;
  private final RunStateMachine stateMachine;
  private final Runnable detachCancellationListener;

  public static RunExecution create(
      AgentRunRequest input, AgentDefinition definition, AgentRuntime runtime) {
    return new RunExecution(UUID.randomUUID().toString(), input, definition, runtime);
  }

  public RunExecution(
      String id, AgentRunRequest input, AgentDefinition definition, AgentRuntime runtime) {
    if (Objects.requireNonNull(id, "id must not be null").isBlank()) {
      throw new IllegalArgumentException("id must not be blank");
    }
    this.id = id;
    this.input = Objects.requireNonNull(input, "input must not be null");
    this.definition = Objects.requireNonNull(definition, "definition must not be null");
    this.runtime = Objects.requireNonNull(runtime, "runtime must not be null");
    this.cancellationSignal =
        Objects.requireNonNull(input.cancellationSignal(), "cancellationSignal must not be null");
    this.stateMachine = new RunStateMachine();
    this.detachCancellationListener = this.cancellationSignal.onCancel(stateMachine::cancel);
    this.stateMachine.onTerminal(detachCancellationListener);
  }

  public String id() {
    return id;
  }

  public AgentRunRequest input() {
    return input;
  }

  public AgentDefinition definition() {
    return definition;
  }

  public AgentRuntime runtime() {
    return runtime;
  }

  public CancellationSignal cancellationSignal() {
    return cancellationSignal;
  }

  RunPhase phase() {
    return stateMachine.phase();
  }

  void transitionTo(RunPhase next) {
    stateMachine.transitionTo(next);
  }

  /**
   * Advances a finalised run into context completion. Called by the engine once the pipeline has
   * reached {@link RunPhase#FINALIZE_RESPONSE}, just before the run's {@code SessionContext}
   * response slot is filled. A transition a concurrent cancellation already made terminal is
   * swallowed, mirroring the pipeline's own {@code advance} guard.
   */
  public void enterContextCompletion() {
    advance(RunPhase.COMPLETE_CONTEXT);
  }

  /**
   * Advances a run into session persistence, after every context provider's {@code afterRun} hook
   * completed. A transition a concurrent cancellation already made terminal is swallowed.
   */
  public void enterSessionPersistence() {
    advance(RunPhase.PERSIST_SESSION);
  }

  /**
   * Terminalises the run successfully, which notifies terminal listeners so the run's cancellation
   * listener detaches. A run a concurrent cancellation already terminalised stays cancelled.
   */
  public void terminateSuccessfully() {
    advance(RunPhase.TERMINATED);
  }

  /**
   * Terminalises the run with the failure that ended its post-run lifecycle, detaching the
   * cancellation listener. A run already terminal keeps its first outcome.
   */
  public void terminateExceptionally(Throwable failure) {
    stateMachine.fail(failure);
  }

  public void cancel() {
    stateMachine.cancel();
  }

  private void advance(RunPhase next) {
    try {
      if (!stateMachine.state().isTerminal()) {
        stateMachine.transitionTo(next);
      }
    } catch (IllegalStateException raced) {
      if (!stateMachine.state().isTerminal()) {
        throw raced;
      }
    }
  }

  void fail(Throwable failure) {
    stateMachine.fail(failure);
  }

  public RunState state() {
    return stateMachine.state();
  }
}
