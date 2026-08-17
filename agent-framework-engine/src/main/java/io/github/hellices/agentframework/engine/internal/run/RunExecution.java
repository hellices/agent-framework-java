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

  void fail(Throwable failure) {
    stateMachine.fail(failure);
  }

  void cancel() {
    stateMachine.cancel();
  }

  public RunState state() {
    return stateMachine.state();
  }
}
