package io.github.hellices.agentframework.openai.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hellices.agentframework.api.agent.CancellationSignal;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class OpenAiCallBridgeTest {

  @Test
  void doesNotDispatchWhenTheSignalIsAlreadyCancelled() {
    CancellationSignal signal = new CancellationSignal();
    signal.cancel();
    AtomicInteger dispatches = new AtomicInteger();

    CompletionStage<String> result =
        OpenAiCallBridge.guard(
            signal,
            () -> {
              dispatches.incrementAndGet();
              return CompletableFuture.completedFuture("never");
            });

    assertThat(dispatches).hasValue(0);
    assertThat(result).isCompletedExceptionally();
    // Read through unwrap rather than off join: the exposed stage relays this call's failure, so
    // every reader sees a CompletionException around the cancellation, and JDK 23 and later would
    // add a second CancellationException on top of a bare one anyway.
    assertThat(OpenAiCallBridge.unwrap(exposedFailureOf(result)))
        .isInstanceOf(CancellationException.class)
        .hasMessage("model call was cancelled before it was dispatched");
  }

  @Test
  void rejectsANullSignalOrANullDispatch() {
    // A call site that passes neither is a programming error rather than a request failure, so it
    // fails where it was made, by name, instead of arriving as a failed stage a caller might map.
    assertThatThrownBy(
            () -> OpenAiCallBridge.guard((CancellationSignal) null, CompletableFuture<String>::new))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("signal must not be null");
    assertThatThrownBy(() -> OpenAiCallBridge.guard(new CancellationSignal(), null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("dispatch must not be null");
  }

  @Test
  void rejectsANullCancellationOrANullDispatchOnTheSeam() {
    assertThatThrownBy(
            () ->
                OpenAiCallBridge.guard(
                    (OpenAiCallBridge.Cancellation) null, CompletableFuture<String>::new))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("cancellation must not be null");
    assertThatThrownBy(() -> OpenAiCallBridge.guard(new RecordingCancellation(), null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("dispatch must not be null");
  }

  @Test
  void rejectsARegistrationThatReturnsNoDeregistrationHandleBeforeDispatching() {
    // A signal that hands back no handle breaks the contract this bridge relies on to remove its
    // listener. Checked where the registration happens, so it fails the caller's own call: deferred
    // to the whenComplete action instead, the null pointer would be delivered to a derived stage
    // this method drops, so nothing would report it, the listener would stay registered, and the
    // request would already have been dispatched by then.
    NoHandleRegistration cancellation = new NoHandleRegistration();
    AtomicInteger dispatches = new AtomicInteger();

    assertThatThrownBy(
            () ->
                OpenAiCallBridge.<String>guard(
                    cancellation,
                    () -> {
                      dispatches.incrementAndGet();
                      return CompletableFuture.completedFuture("never");
                    }))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("onCancel must return a deregistration handle");

    assertThat(dispatches).hasValue(0);
    assertThat(cancellation.registered()).isTrue();
  }

  @Test
  void rejectsARegistrationThatReturnsNoDeregistrationHandleOnTheExposedStage() {
    // The same failure on the entry point the adapter actually calls: it arrives as a throw out of
    // the guard rather than as a stage that never completes.
    NoHandleRegistration cancellation = new NoHandleRegistration();
    AtomicInteger dispatches = new AtomicInteger();

    assertThatThrownBy(
            () ->
                OpenAiCallBridge.<String>guardStage(
                    cancellation,
                    () -> {
                      dispatches.incrementAndGet();
                      return CompletableFuture.completedFuture("never");
                    }))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("onCancel must return a deregistration handle");

    assertThat(dispatches).hasValue(0);
  }

  @Test
  void failsPromptlyWhenCancellationArrivesAfterDispatch() {
    CancellationSignal signal = new CancellationSignal();
    CompletableFuture<String> inFlight = new CompletableFuture<>();

    CompletionStage<String> result = OpenAiCallBridge.guard(signal, () -> inFlight);
    assertThat(result).isNotDone();
    signal.cancel();

    // Asserted on the stage rather than through join: the stage is what the engine composes onto,
    // and a bridge that never completed it would hang this test rather than fail it.
    assertThat(result).isCompletedExceptionally();
    assertThat(OpenAiCallBridge.unwrap(exposedFailureOf(result)))
        .isInstanceOf(CancellationException.class)
        .hasMessage("model call was cancelled");
    // The in-flight call is deliberately left alone. The SDK derives its future with thenApply, so
    // nothing here can abort the HTTP request; the per-attempt timeout is what reclaims it.
    assertThat(inFlight).isNotDone();
  }

  @Test
  void doesNotLetTheCallerCancelTheRunThroughTheStageItReturns() {
    // The stage handed out is a view, not the call. A caller that cancels its own future abandons
    // its own wait: it neither settles this call nor aborts the HTTP request, which is exactly what
    // cancelling the run's signal cannot do either.
    CancellationSignal signal = new CancellationSignal();
    CompletableFuture<String> inFlight = new CompletableFuture<>();

    CompletionStage<String> result = OpenAiCallBridge.guard(signal, () -> inFlight);
    assertThat(result.toCompletableFuture().cancel(true)).isTrue();

    assertThat(inFlight).isNotDone();
    assertThat(result).isNotDone();

    inFlight.complete("answer");

    assertThat(result).isCompletedWithValue("answer");
  }

  @Test
  void reportsCancellationAsACompletionCauseToAChainedStage() {
    // What the engine sees: it composes onto the stage rather than joining it, and the derived
    // stage re-wraps the cancellation. Cancellation has to stay recognisable by type through it.
    CancellationSignal signal = new CancellationSignal();
    CompletionStage<String> chained =
        OpenAiCallBridge.guard(signal, CompletableFuture<String>::new).thenApply(value -> value);
    signal.cancel();

    assertThat(chained).isCompletedExceptionally();
    assertThatThrownBy(chained.toCompletableFuture()::join)
        .isInstanceOf(CompletionException.class)
        .hasCauseInstanceOf(CancellationException.class);
    assertThat(OpenAiCallBridge.unwrap(exposedFailureOf(chained)))
        .isInstanceOf(CancellationException.class);
  }

  @Test
  void removesTheCancellationListenerOnEveryCompletionPath() {
    assertThat(deregistrationAfter(dispatch -> dispatch.complete("done"))).isTrue();
    assertThat(
            deregistrationAfter(
                dispatch -> dispatch.completeExceptionally(new IllegalStateException("no"))))
        .isTrue();
  }

  @Test
  void removesTheCancellationListenerWhenTheCallIsCancelled() {
    RecordingCancellation cancellation = new RecordingCancellation();
    CompletableFuture<String> dispatch = new CompletableFuture<>();

    CompletableFuture<String> result = OpenAiCallBridge.guard(cancellation, () -> dispatch);
    cancellation.cancel();

    assertThat(result).isCompletedExceptionally();
    assertThat(cancellation.deregistered()).isTrue();
  }

  @Test
  void removesTheCancellationListenerWhenTheDispatchThrows() {
    // The listener is registered before the request is built, so a failure that never reaches the
    // wire has to remove it too. One signal spans a whole run, and a listener leaked per failed
    // call would accumulate for that run's lifetime.
    RecordingCancellation cancellation = new RecordingCancellation();

    CompletableFuture<String> result =
        OpenAiCallBridge.guard(
            cancellation,
            () -> {
              throw new IllegalArgumentException("cannot map");
            });

    assertThat(result).isCompletedExceptionally();
    assertThat(cancellation.deregistered()).isTrue();
  }

  @Test
  void rethrowsAFatalDispatchErrorAfterRemovingTheCancellationListener() {
    // An Error is not a provider failure. Completing the stage with it and returning would hand a
    // caller a fatal condition to map, retry, or log as a bad request, so the same instance is
    // rethrown - but the listener and the result this bridge owns are still settled first.
    RecordingCancellation cancellation = new RecordingCancellation();
    StackOverflowError fatal = new StackOverflowError("dispatch exhausted the stack");

    assertThatThrownBy(
            () ->
                OpenAiCallBridge.<String>guard(
                    cancellation,
                    () -> {
                      throw fatal;
                    }))
        .isSameAs(fatal);

    assertThat(cancellation.deregistered()).isTrue();
  }

  @Test
  void doesNotDispatchWhenCancellationArrivesDuringRegistration() {
    // The check and the registration are two steps, and a run cancelled between them would
    // otherwise send a request nobody is waiting for.
    CancellingRegistration cancellation = new CancellingRegistration();
    AtomicInteger dispatches = new AtomicInteger();

    CompletableFuture<String> result =
        OpenAiCallBridge.guard(
            cancellation,
            () -> {
              dispatches.incrementAndGet();
              return CompletableFuture.completedFuture("never");
            });

    assertThat(dispatches).hasValue(0);
    assertThat(result).isCompletedExceptionally();
    assertThat(failureOf(result)).isInstanceOf(CancellationException.class);
    assertThat(cancellation.deregistered()).isTrue();
  }

  @Test
  void keepsTheCancellationListenerUntilTheProviderCallItselfCompletes() {
    // Cancelling the caller's own future settles nothing here: the provider call is still running,
    // so the listener that would fail this call on a real cancellation has to stay registered, and
    // it is removed when the call finishes rather than when a caller stops waiting for it.
    RecordingCancellation cancellation = new RecordingCancellation();
    CompletableFuture<String> inFlight = new CompletableFuture<>();

    CompletionStage<String> exposed = OpenAiCallBridge.guardStage(cancellation, () -> inFlight);
    assertThat(exposed.toCompletableFuture().cancel(true)).isTrue();

    assertThat(inFlight).isNotDone();
    assertThat(exposed).isNotDone();
    assertThat(cancellation.deregistered()).isFalse();

    inFlight.complete("answer");

    assertThat(exposed).isCompletedWithValue("answer");
    assertThat(cancellation.deregistered()).isTrue();
  }

  @Test
  void handsOutNoCompletionAuthorityOverTheProviderCall() {
    // A forged value on the caller's own future is the same kind of mistake as a forged
    // cancellation: it must not become this call's answer, and it must not settle the provider
    // call, whose real answer still arrives on the stage everyone else reads.
    RecordingCancellation cancellation = new RecordingCancellation();
    CompletableFuture<String> inFlight = new CompletableFuture<>();

    CompletionStage<String> exposed = OpenAiCallBridge.guardStage(cancellation, () -> inFlight);
    CompletableFuture<String> caller = exposed.toCompletableFuture();
    assertThat(caller.complete("forged")).isTrue();

    assertThat(inFlight).isNotDone();
    assertThat(exposed).isNotDone();

    inFlight.complete("answer");

    assertThat(exposed).isCompletedWithValue("answer");
    assertThat(caller).isCompletedWithValue("forged");
  }

  @Test
  void mirrorsTheValueTheProviderCallProduced() {
    CancellationSignal signal = new CancellationSignal();
    CompletableFuture<String> dispatch = new CompletableFuture<>();

    CompletionStage<String> result = OpenAiCallBridge.guard(signal, () -> dispatch);
    assertThat(result).isNotDone();
    dispatch.complete("answer");

    assertThat(result).isCompletedWithValue("answer");
  }

  @Test
  void preservesTheOriginalFailureInstance() {
    CancellationSignal signal = new CancellationSignal();
    IllegalStateException failure = new IllegalStateException("upstream");

    CompletionStage<String> result =
        OpenAiCallBridge.guard(signal, () -> CompletableFuture.failedFuture(failure));

    assertThatThrownBy(result.toCompletableFuture()::join)
        .isInstanceOf(CompletionException.class)
        .satisfies(thrown -> assertThat(thrown.getCause()).isSameAs(failure));
  }

  @Test
  void preservesTheOriginalFailureInstanceBehindAnAsynchronousWrapper() {
    // The SDK composes its own stages, so the failure it reports is usually already wrapped. An
    // OpenAI exception carries the status code, the request id, and the retry advice a caller acts
    // on, so the wrapper is peeled rather than passed on with the real failure one level down.
    // Asserted on the seam's raw stage: the exposed stage relays its failure, and a relay cannot
    // tell a wrapper this bridge failed to peel from the one the relay added itself.
    RecordingCancellation cancellation = new RecordingCancellation();
    IllegalStateException failure = new IllegalStateException("upstream");

    CompletableFuture<String> result =
        OpenAiCallBridge.guard(
            cancellation, () -> CompletableFuture.failedFuture(new CompletionException(failure)));

    assertThat(failureOf(result)).isSameAs(failure);
  }

  @Test
  void dispatchesOnceAndDoesNotRetryAFailedCall() {
    // A retry this adapter never announced would bill a second call and could repeat a tool call
    // the caller already saw. Recovery is the caller's decision, on the original exception, and
    // the retries the SDK client the host injected performs are the host's own configuration.
    CancellationSignal signal = new CancellationSignal();
    AtomicInteger dispatches = new AtomicInteger();

    CompletionStage<String> result =
        OpenAiCallBridge.guard(
            signal,
            () -> {
              dispatches.incrementAndGet();
              return CompletableFuture.failedFuture(new IllegalStateException("upstream"));
            });

    assertThat(result).isCompletedExceptionally();
    assertThat(dispatches).hasValue(1);
  }

  @Test
  void deliversASynchronousDispatchFailureThroughTheStage() {
    // Mapping happens inside the dispatch supplier, so an unmappable request must arrive as a
    // failed stage rather than as a throw out of ModelClient.run.
    CancellationSignal signal = new CancellationSignal();
    IllegalArgumentException failure = new IllegalArgumentException("cannot map");

    CompletionStage<String> result =
        OpenAiCallBridge.guard(
            signal,
            () -> {
              throw failure;
            });

    assertThatThrownBy(result.toCompletableFuture()::join)
        .isInstanceOf(CompletionException.class)
        .satisfies(thrown -> assertThat(thrown.getCause()).isSameAs(failure));
  }

  @Test
  void deliversAMissingProviderCallThroughTheStage() {
    // A port that answers with no stage at all would otherwise surface as a null pointer thrown out
    // of ModelClient.run, which is the one shape this bridge exists to rule out.
    CancellationSignal signal = new CancellationSignal();

    CompletionStage<String> result = OpenAiCallBridge.guard(signal, () -> null);

    assertThatThrownBy(result.toCompletableFuture()::join)
        .isInstanceOf(CompletionException.class)
        .hasCauseInstanceOf(IllegalStateException.class)
        .hasMessageContaining("returned no stage");
  }

  @Test
  void unwrapsOnlyTheAsynchronousWrappers() {
    IllegalStateException cause = new IllegalStateException("cause");

    assertThat(OpenAiCallBridge.unwrap(new CompletionException(cause))).isSameAs(cause);
    assertThat(OpenAiCallBridge.unwrap(new ExecutionException(cause))).isSameAs(cause);
    assertThat(OpenAiCallBridge.unwrap(cause)).isSameAs(cause);
  }

  @Test
  void keepsAWrapperThatCarriesNoCause() {
    // The JDK's causeless wrapper constructors are protected, and passing an explicit null cause to
    // the public ones is a SpotBugs NP_NONNULL_PARAM_VIOLATION, so the shape is built by subclass.
    CompletionException completion = new CauselessCompletionException();
    ExecutionException execution = new CauselessExecutionException();

    assertThat(OpenAiCallBridge.unwrap(completion)).isSameAs(completion);
    assertThat(OpenAiCallBridge.unwrap(execution)).isSameAs(execution);
  }

  private static Throwable failureOf(CompletableFuture<?> future) {
    return future.handle((value, failure) -> failure).join();
  }

  private static Throwable exposedFailureOf(CompletionStage<?> stage) {
    return stage.handle((value, failure) -> failure).toCompletableFuture().join();
  }

  private static boolean deregistrationAfter(Consumer<CompletableFuture<String>> completion) {
    RecordingCancellation cancellation = new RecordingCancellation();
    CompletableFuture<String> dispatch = new CompletableFuture<>();
    OpenAiCallBridge.guard(cancellation, () -> dispatch);
    completion.accept(dispatch);
    return cancellation.deregistered();
  }

  /** Records registration and removal, which a CancellationSignal cannot report on its own. */
  private static final class RecordingCancellation implements OpenAiCallBridge.Cancellation {

    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean deregistered = new AtomicBoolean();
    private Runnable listener;

    @Override
    public boolean isCancelled() {
      return cancelled.get();
    }

    @Override
    public Runnable onCancel(Runnable listener) {
      this.listener = listener;
      return () -> deregistered.set(true);
    }

    void cancel() {
      cancelled.set(true);
      listener.run();
    }

    boolean deregistered() {
      return deregistered.get();
    }
  }

  /** Reports "not cancelled" and then cancels while the listener is being registered. */
  private static final class CancellingRegistration implements OpenAiCallBridge.Cancellation {

    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean deregistered = new AtomicBoolean();

    @Override
    public boolean isCancelled() {
      return cancelled.get();
    }

    @Override
    public Runnable onCancel(Runnable listener) {
      cancelled.set(true);
      listener.run();
      return () -> deregistered.set(true);
    }

    boolean deregistered() {
      return deregistered.get();
    }
  }

  /** Registers the listener and breaks the contract by returning no deregistration handle. */
  private static final class NoHandleRegistration implements OpenAiCallBridge.Cancellation {

    private final AtomicBoolean registered = new AtomicBoolean();

    @Override
    public boolean isCancelled() {
      return false;
    }

    @Override
    public Runnable onCancel(Runnable listener) {
      registered.set(true);
      return null;
    }

    boolean registered() {
      return registered.get();
    }
  }

  /** A completion wrapper with no cause, which the JDK only builds through a subclass. */
  private static final class CauselessCompletionException extends CompletionException {

    private static final long serialVersionUID = 1L;
  }

  /** An execution wrapper with no cause, which the JDK only builds through a subclass. */
  private static final class CauselessExecutionException extends ExecutionException {

    private static final long serialVersionUID = 1L;
  }
}
