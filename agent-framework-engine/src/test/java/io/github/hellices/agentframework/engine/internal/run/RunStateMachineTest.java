package io.github.hellices.agentframework.engine.internal.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hellices.agentframework.api.agent.AgentDefinition;
import io.github.hellices.agentframework.api.agent.AgentRunRequest;
import io.github.hellices.agentframework.api.agent.AgentRuntime;
import io.github.hellices.agentframework.api.agent.CancellationSignal;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class RunStateMachineTest {

  private static final Map<RunPhase, EnumSet<RunPhase>> ALLOWED_SUCCESSORS = allowedSuccessors();

  @Test
  void everyPhaseOnlyAllowsTheDeclaredSuccessors() {
    for (RunPhase phase : RunPhase.values()) {
      for (RunPhase next : RunPhase.values()) {
        RunStateMachine machine = machineAt(phase);
        if (ALLOWED_SUCCESSORS.get(phase).contains(next)) {
          machine.transitionTo(next);
          assertThat(machine.phase()).isEqualTo(next);
        } else {
          assertThatThrownBy(() -> machine.transitionTo(next))
              .isInstanceOf(IllegalStateException.class);
          assertThat(machine.phase()).isEqualTo(phase);
        }
      }
    }
  }

  @Test
  void successPathRecordsAnImmutableHistoryAndASuccessOutcome() {
    RunStateMachine machine = new RunStateMachine();

    machine.transitionTo(RunPhase.LOAD_SESSION);
    machine.transitionTo(RunPhase.PREPARE_CONTEXT);
    machine.transitionTo(RunPhase.PREPARE_MODEL_REQUEST);
    machine.transitionTo(RunPhase.CALL_MODEL);
    machine.transitionTo(RunPhase.ACCUMULATE_MODEL_UPDATES);
    machine.transitionTo(RunPhase.PLAN_TOOL_ACTION);
    machine.transitionTo(RunPhase.FINALIZE_RESPONSE);
    machine.transitionTo(RunPhase.COMPLETE_CONTEXT);
    machine.transitionTo(RunPhase.PERSIST_SESSION);
    machine.transitionTo(RunPhase.TERMINATED);

    assertThat(machine.state().phaseHistory())
        .containsExactly(
            RunPhase.VALIDATE,
            RunPhase.LOAD_SESSION,
            RunPhase.PREPARE_CONTEXT,
            RunPhase.PREPARE_MODEL_REQUEST,
            RunPhase.CALL_MODEL,
            RunPhase.ACCUMULATE_MODEL_UPDATES,
            RunPhase.PLAN_TOOL_ACTION,
            RunPhase.FINALIZE_RESPONSE,
            RunPhase.COMPLETE_CONTEXT,
            RunPhase.PERSIST_SESSION,
            RunPhase.TERMINATED);
    assertThat(machine.state().outcome())
        .hasValueSatisfying(
            outcome -> {
              assertThat(outcome).isEqualTo(RunState.Outcome.SUCCESS);
            });
    assertThatThrownBy(() -> machine.state().phaseHistory().add(RunPhase.TERMINATED))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void toolLoopBackEdgeReturnsToPreparingTheNextModelRequest() {
    RunStateMachine machine = machineAt(RunPhase.PLAN_TOOL_ACTION);

    machine.transitionTo(RunPhase.EXECUTE_TOOL_BATCH);
    machine.transitionTo(RunPhase.PREPARE_MODEL_REQUEST);

    assertThat(machine.phase()).isEqualTo(RunPhase.PREPARE_MODEL_REQUEST);
    assertThat(machine.state().phaseHistory())
        .containsExactly(
            RunPhase.VALIDATE,
            RunPhase.LOAD_SESSION,
            RunPhase.PREPARE_CONTEXT,
            RunPhase.PREPARE_MODEL_REQUEST,
            RunPhase.CALL_MODEL,
            RunPhase.ACCUMULATE_MODEL_UPDATES,
            RunPhase.PLAN_TOOL_ACTION,
            RunPhase.EXECUTE_TOOL_BATCH,
            RunPhase.PREPARE_MODEL_REQUEST);
  }

  @Test
  void failTerminalizesFromEveryNonterminalPhaseAndPreservesTheExactCause() {
    for (RunPhase phase : nonterminalPhases()) {
      RunStateMachine machine = machineAt(phase);
      IllegalStateException failure = new IllegalStateException("boom-" + phase.name());

      machine.fail(failure);

      assertThat(machine.phase()).isEqualTo(RunPhase.TERMINATED);
      assertThat(machine.state().outcome())
          .hasValueSatisfying(
              outcome -> {
                assertThat(outcome).isEqualTo(RunState.Outcome.FAILURE);
              });
      assertThat(machine.state().mapTerminalCause(cause -> sameReference(cause, failure)))
          .hasValue(true);
    }
  }

  @Test
  void cancelTerminalizesFromEveryNonterminalPhaseWithADistinctCancellationOutcome() {
    for (RunPhase phase : nonterminalPhases()) {
      RunStateMachine machine = machineAt(phase);

      machine.cancel();

      assertThat(machine.phase()).isEqualTo(RunPhase.TERMINATED);
      assertThat(machine.state().outcome())
          .hasValueSatisfying(
              outcome -> {
                assertThat(outcome).isEqualTo(RunState.Outcome.CANCELLED);
              });
      assertThat(machine.state().mapTerminalCause(Function.identity()))
          .hasValueSatisfying(
              cause ->
                  assertThat(cause)
                      .isInstanceOf(CancellationException.class)
                      .hasMessage("run was cancelled"));
    }
  }

  @Test
  void repeatedFailureAndCancellationCannotReplaceTheFirstTerminalOutcome() {
    RunStateMachine failedFirst = machineAt(RunPhase.CALL_MODEL);
    IllegalStateException firstFailure = new IllegalStateException("first");

    failedFirst.fail(firstFailure);
    failedFirst.cancel();
    failedFirst.fail(new IllegalStateException("replacement"));

    assertThat(failedFirst.state().outcome())
        .hasValueSatisfying(
            outcome -> {
              assertThat(outcome).isEqualTo(RunState.Outcome.FAILURE);
            });
    assertThat(failedFirst.state().mapTerminalCause(cause -> sameReference(cause, firstFailure)))
        .hasValue(true);

    RunStateMachine cancelledFirst = machineAt(RunPhase.CALL_MODEL);

    cancelledFirst.cancel();
    cancelledFirst.fail(new IllegalStateException("late failure"));
    cancelledFirst.cancel();

    assertThat(cancelledFirst.state().outcome())
        .hasValueSatisfying(
            outcome -> {
              assertThat(outcome).isEqualTo(RunState.Outcome.CANCELLED);
            });
    assertThat(cancelledFirst.state().mapTerminalCause(Function.identity()))
        .hasValueSatisfying(cause -> assertThat(cause).isInstanceOf(CancellationException.class));
  }

  @Test
  void concurrentTerminalCallsLeaveOneStableOutcome() throws Exception {
    for (int iteration = 0; iteration < 200; iteration++) {
      RunStateMachine machine = machineAt(RunPhase.CALL_MODEL);
      IllegalStateException firstFailure = new IllegalStateException("first-" + iteration);
      IllegalArgumentException secondFailure = new IllegalArgumentException("second-" + iteration);
      CountDownLatch ready = new CountDownLatch(3);
      CountDownLatch start = new CountDownLatch(1);
      ExecutorService executor = Executors.newFixedThreadPool(3);
      try {
        List<Future<?>> futures =
            List.of(
                executor.submit(
                    () -> {
                      await(ready, start);
                      machine.fail(firstFailure);
                    }),
                executor.submit(
                    () -> {
                      await(ready, start);
                      machine.fail(secondFailure);
                    }),
                executor.submit(
                    () -> {
                      await(ready, start);
                      machine.cancel();
                    }));

        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        for (Future<?> future : futures) {
          future.get(5, TimeUnit.SECONDS);
        }
      } finally {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
      }

      RunState.Outcome stableOutcome = machine.state().outcome().orElseThrow();
      assertThat(machine.phase()).isEqualTo(RunPhase.TERMINATED);
      assertThat(stableOutcome).isIn(RunState.Outcome.FAILURE, RunState.Outcome.CANCELLED);
      if (stableOutcome == RunState.Outcome.FAILURE) {
        assertThat(
                machine
                        .state()
                        .mapTerminalCause(cause -> sameReference(cause, firstFailure))
                        .orElse(false)
                    || machine
                        .state()
                        .mapTerminalCause(cause -> sameReference(cause, secondFailure))
                        .orElse(false))
            .isTrue();
      } else {
        assertThat(machine.state().mapTerminalCause(Function.identity()))
            .hasValueSatisfying(
                cause -> assertThat(cause).isInstanceOf(CancellationException.class));
      }

      machine.fail(new RuntimeException("late"));
      machine.cancel();

      assertThat(machine.state().outcome()).containsSame(stableOutcome);
    }
  }

  @Test
  void terminalListenersRunOnSuccessAndRemovedListenersStayDetached() {
    RunStateMachine machine = machineAt(RunPhase.PERSIST_SESSION);
    AtomicInteger invoked = new AtomicInteger();
    AtomicInteger removedBeforeTerminal = new AtomicInteger();

    Runnable remover = machine.onTerminal(removedBeforeTerminal::incrementAndGet);
    machine.onTerminal(invoked::incrementAndGet);

    remover.run();
    machine.transitionTo(RunPhase.TERMINATED);

    assertThat(invoked).hasValue(1);
    assertThat(removedBeforeTerminal).hasValue(0);
  }

  @Test
  void terminalListenersRunOnceOnFailureAndLateRegistrationsRunImmediately() {
    RunStateMachine machine = machineAt(RunPhase.CALL_MODEL);
    AtomicInteger invoked = new AtomicInteger();
    AtomicInteger lateInvoked = new AtomicInteger();

    machine.onTerminal(invoked::incrementAndGet);

    machine.fail(new IllegalStateException("boom"));
    machine.fail(new IllegalStateException("replacement"));
    machine.cancel();

    assertThat(invoked).hasValue(1);

    machine.onTerminal(lateInvoked::incrementAndGet);

    assertThat(lateInvoked).hasValue(1);
  }

  @Test
  void terminalListenersRunOnCancellation() {
    RunStateMachine machine = machineAt(RunPhase.CALL_MODEL);
    AtomicInteger invoked = new AtomicInteger();

    machine.onTerminal(invoked::incrementAndGet);
    machine.cancel();

    assertThat(invoked).hasValue(1);
  }

  @Test
  void lateRegistrationsRunImmediatelyOnceTheStateIsAlreadyTerminal() {
    RunStateMachine machine = machineAt(RunPhase.CALL_MODEL);
    AtomicInteger invoked = new AtomicInteger();

    assertThat(
            machine
                .state()
                .terminalize(RunState.Outcome.FAILURE, new IllegalStateException("boom")))
        .isTrue();

    Runnable remover = machine.onTerminal(invoked::incrementAndGet);
    remover.run();

    assertThat(invoked).hasValue(1);
  }

  @Test
  void runExecutionOwnsRunIdentityInputsAndCancellationDrivenState() {
    CancellationSignal cancellation = new CancellationSignal();
    AgentRunRequest request = AgentRunRequest.builder().cancellationSignal(cancellation).build();
    AgentDefinition definition = AgentDefinition.builder().name("assistant").build();
    AgentRuntime runtime = AgentRuntime.builder().modelClient(ignored -> subscriber -> {}).build();

    RunExecution execution = new RunExecution("run-1", request, definition, runtime);

    assertThat(execution.id()).isEqualTo("run-1");
    assertThat(execution.input()).isSameAs(request);
    assertThat(execution.definition()).isSameAs(definition);
    assertThat(execution.runtime()).isSameAs(runtime);
    assertThat(execution.cancellationSignal()).isSameAs(cancellation);
    assertThat(execution.phase()).isEqualTo(RunPhase.VALIDATE);

    cancellation.cancel();

    assertThat(execution.state().outcome())
        .hasValueSatisfying(
            outcome -> {
              assertThat(outcome).isEqualTo(RunState.Outcome.CANCELLED);
            });
    assertThat(execution.state().mapTerminalCause(Function.identity()))
        .hasValueSatisfying(cause -> assertThat(cause).isInstanceOf(CancellationException.class));
  }

  @Test
  void runExecutionDetachesCancellationListenersAfterSuccessFailureAndCancellation() {
    CancellationSignal sharedSignal = new CancellationSignal();
    AgentRunRequest request = AgentRunRequest.builder().cancellationSignal(sharedSignal).build();
    AgentDefinition definition = AgentDefinition.builder().name("assistant").build();
    AgentRuntime runtime = AgentRuntime.builder().modelClient(ignored -> subscriber -> {}).build();

    RunExecution successful = new RunExecution("run-success", request, definition, runtime);

    assertThat(listenerCount(sharedSignal)).isEqualTo(1);

    successful.transitionTo(RunPhase.LOAD_SESSION);
    successful.transitionTo(RunPhase.PREPARE_CONTEXT);
    successful.transitionTo(RunPhase.PREPARE_MODEL_REQUEST);
    successful.transitionTo(RunPhase.CALL_MODEL);
    successful.transitionTo(RunPhase.ACCUMULATE_MODEL_UPDATES);
    successful.transitionTo(RunPhase.PLAN_TOOL_ACTION);
    successful.transitionTo(RunPhase.FINALIZE_RESPONSE);
    successful.transitionTo(RunPhase.COMPLETE_CONTEXT);
    successful.transitionTo(RunPhase.PERSIST_SESSION);
    successful.transitionTo(RunPhase.TERMINATED);

    assertThat(listenerCount(sharedSignal)).isZero();

    RunExecution failed = new RunExecution("run-failure", request, definition, runtime);

    assertThat(listenerCount(sharedSignal)).isEqualTo(1);

    failed.fail(new IllegalStateException("boom"));

    assertThat(listenerCount(sharedSignal)).isZero();

    RunExecution cancelled = new RunExecution("run-cancel", request, definition, runtime);

    assertThat(listenerCount(sharedSignal)).isEqualTo(1);

    sharedSignal.cancel();

    assertThat(listenerCount(sharedSignal)).isZero();
    assertThat(cancelled.state().outcome()).contains(RunState.Outcome.CANCELLED);
  }

  @Test
  void runExecutionDoesNotRetainCancellationListenerWhenSignalIsAlreadyCancelled() {
    CancellationSignal alreadyCancelled = new CancellationSignal();
    alreadyCancelled.cancel();

    AgentRunRequest request =
        AgentRunRequest.builder().cancellationSignal(alreadyCancelled).build();
    AgentDefinition definition = AgentDefinition.builder().name("assistant").build();
    AgentRuntime runtime = AgentRuntime.builder().modelClient(ignored -> subscriber -> {}).build();

    RunExecution execution = new RunExecution("run-cancelled", request, definition, runtime);

    assertThat(execution.state().outcome()).contains(RunState.Outcome.CANCELLED);
    assertThat(listenerCount(alreadyCancelled)).isZero();
  }

  private static void await(CountDownLatch ready, CountDownLatch start) {
    ready.countDown();
    try {
      assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }

  private static Iterable<RunPhase> nonterminalPhases() {
    return () -> EnumSet.complementOf(EnumSet.of(RunPhase.TERMINATED)).iterator();
  }

  private static boolean sameReference(Throwable left, Throwable right) {
    return Objects.equals(left, right);
  }

  @SuppressWarnings("unchecked")
  private static int listenerCount(CancellationSignal signal) {
    try {
      var field = CancellationSignal.class.getDeclaredField("listeners");
      field.setAccessible(true);
      return ((Queue<Runnable>) field.get(signal)).size();
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }

  private static RunStateMachine machineAt(RunPhase target) {
    RunStateMachine machine = new RunStateMachine();
    for (RunPhase next : pathTo(target)) {
      machine.transitionTo(next);
    }
    return machine;
  }

  private static List<RunPhase> pathTo(RunPhase target) {
    return switch (target) {
      case VALIDATE -> List.of();
      case LOAD_SESSION -> List.of(RunPhase.LOAD_SESSION);
      case PREPARE_CONTEXT -> List.of(RunPhase.LOAD_SESSION, RunPhase.PREPARE_CONTEXT);
      case PREPARE_MODEL_REQUEST ->
          List.of(RunPhase.LOAD_SESSION, RunPhase.PREPARE_CONTEXT, RunPhase.PREPARE_MODEL_REQUEST);
      case CALL_MODEL ->
          List.of(
              RunPhase.LOAD_SESSION,
              RunPhase.PREPARE_CONTEXT,
              RunPhase.PREPARE_MODEL_REQUEST,
              RunPhase.CALL_MODEL);
      case ACCUMULATE_MODEL_UPDATES ->
          List.of(
              RunPhase.LOAD_SESSION,
              RunPhase.PREPARE_CONTEXT,
              RunPhase.PREPARE_MODEL_REQUEST,
              RunPhase.CALL_MODEL,
              RunPhase.ACCUMULATE_MODEL_UPDATES);
      case PLAN_TOOL_ACTION ->
          List.of(
              RunPhase.LOAD_SESSION,
              RunPhase.PREPARE_CONTEXT,
              RunPhase.PREPARE_MODEL_REQUEST,
              RunPhase.CALL_MODEL,
              RunPhase.ACCUMULATE_MODEL_UPDATES,
              RunPhase.PLAN_TOOL_ACTION);
      case EXECUTE_TOOL_BATCH ->
          List.of(
              RunPhase.LOAD_SESSION,
              RunPhase.PREPARE_CONTEXT,
              RunPhase.PREPARE_MODEL_REQUEST,
              RunPhase.CALL_MODEL,
              RunPhase.ACCUMULATE_MODEL_UPDATES,
              RunPhase.PLAN_TOOL_ACTION,
              RunPhase.EXECUTE_TOOL_BATCH);
      case FINALIZE_RESPONSE ->
          List.of(
              RunPhase.LOAD_SESSION,
              RunPhase.PREPARE_CONTEXT,
              RunPhase.PREPARE_MODEL_REQUEST,
              RunPhase.CALL_MODEL,
              RunPhase.ACCUMULATE_MODEL_UPDATES,
              RunPhase.PLAN_TOOL_ACTION,
              RunPhase.FINALIZE_RESPONSE);
      case COMPLETE_CONTEXT ->
          List.of(
              RunPhase.LOAD_SESSION,
              RunPhase.PREPARE_CONTEXT,
              RunPhase.PREPARE_MODEL_REQUEST,
              RunPhase.CALL_MODEL,
              RunPhase.ACCUMULATE_MODEL_UPDATES,
              RunPhase.PLAN_TOOL_ACTION,
              RunPhase.FINALIZE_RESPONSE,
              RunPhase.COMPLETE_CONTEXT);
      case PERSIST_SESSION ->
          List.of(
              RunPhase.LOAD_SESSION,
              RunPhase.PREPARE_CONTEXT,
              RunPhase.PREPARE_MODEL_REQUEST,
              RunPhase.CALL_MODEL,
              RunPhase.ACCUMULATE_MODEL_UPDATES,
              RunPhase.PLAN_TOOL_ACTION,
              RunPhase.FINALIZE_RESPONSE,
              RunPhase.COMPLETE_CONTEXT,
              RunPhase.PERSIST_SESSION);
      case TERMINATED ->
          List.of(
              RunPhase.LOAD_SESSION,
              RunPhase.PREPARE_CONTEXT,
              RunPhase.PREPARE_MODEL_REQUEST,
              RunPhase.CALL_MODEL,
              RunPhase.ACCUMULATE_MODEL_UPDATES,
              RunPhase.PLAN_TOOL_ACTION,
              RunPhase.FINALIZE_RESPONSE,
              RunPhase.COMPLETE_CONTEXT,
              RunPhase.PERSIST_SESSION,
              RunPhase.TERMINATED);
    };
  }

  private static Map<RunPhase, EnumSet<RunPhase>> allowedSuccessors() {
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
