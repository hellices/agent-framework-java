package io.github.hellices.agentframework.openai.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hellices.agentframework.api.agent.CancellationSignal;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
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

    CompletableFuture<String> result =
        OpenAiCallBridge.guard(
            signal,
            () -> {
              dispatches.incrementAndGet();
              return CompletableFuture.completedFuture("never");
            });

    assertThat(dispatches).hasValue(0);
    assertThat(result).isCompletedExceptionally();
    // Asserted on the failure the stage carries rather than on the one join reports: JDK 23 and
    // later rethrow a cancellation as a fresh CancellationException("join") that carries the
    // original as its cause, and the stage's own failure is what the engine composes onto anyway.
    assertThat(failureOf(result))
        .isInstanceOf(CancellationException.class)
        .hasMessage("model call was cancelled before it was dispatched");
  }

  @Test
  void failsPromptlyWhenCancellationArrivesAfterDispatch() {
    CancellationSignal signal = new CancellationSignal();
    CompletableFuture<String> inFlight = new CompletableFuture<>();

    CompletableFuture<String> result = OpenAiCallBridge.guard(signal, () -> inFlight);
    assertThat(result).isNotDone();
    signal.cancel();

    // Asserted on the stage rather than through join: the stage is what the engine composes onto,
    // and a bridge that never completed it would hang this test rather than fail it.
    assertThat(result).isCompletedExceptionally();
    assertThat(failureOf(result))
        .isInstanceOf(CancellationException.class)
        .hasMessage("model call was cancelled");
    // The in-flight call is deliberately left alone. The SDK derives its future with thenApply, so
    // nothing here can abort the HTTP request; the per-request timeout is what reclaims it.
    assertThat(inFlight).isNotDone();
  }

  @Test
  void reportsCancellationAsACompletionCauseToAChainedStage() {
    // What the engine sees: it composes onto the stage rather than joining it, and the derived
    // stage re-wraps the cancellation. Cancellation has to stay recognisable by type through it.
    CancellationSignal signal = new CancellationSignal();
    CompletableFuture<String> chained =
        OpenAiCallBridge.guard(signal, CompletableFuture<String>::new).thenApply(value -> value);
    signal.cancel();

    assertThat(chained).isCompletedExceptionally();
    assertThatThrownBy(chained::join)
        .isInstanceOf(CompletionException.class)
        .hasCauseInstanceOf(CancellationException.class);
    assertThat(OpenAiCallBridge.unwrap(failureOf(chained)))
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
  void mirrorsTheValueTheProviderCallProduced() {
    CancellationSignal signal = new CancellationSignal();
    CompletableFuture<String> dispatch = new CompletableFuture<>();

    CompletableFuture<String> result = OpenAiCallBridge.guard(signal, () -> dispatch);
    assertThat(result).isNotDone();
    dispatch.complete("answer");

    assertThat(result).isCompletedWithValue("answer");
  }

  @Test
  void preservesTheOriginalFailureInstance() {
    CancellationSignal signal = new CancellationSignal();
    IllegalStateException failure = new IllegalStateException("upstream");

    CompletableFuture<String> result =
        OpenAiCallBridge.guard(signal, () -> CompletableFuture.failedFuture(failure));

    assertThatThrownBy(result::join)
        .isInstanceOf(CompletionException.class)
        .satisfies(thrown -> assertThat(thrown.getCause()).isSameAs(failure));
  }

  @Test
  void preservesTheOriginalFailureInstanceBehindAnAsynchronousWrapper() {
    // The SDK composes its own stages, so the failure it reports is usually already wrapped. An
    // OpenAI exception carries the status code, the request id, and the retry advice a caller acts
    // on, so the wrapper is peeled rather than passed on with the real failure one level down.
    CancellationSignal signal = new CancellationSignal();
    IllegalStateException failure = new IllegalStateException("upstream");

    CompletableFuture<String> result =
        OpenAiCallBridge.guard(
            signal, () -> CompletableFuture.failedFuture(new CompletionException(failure)));

    assertThat(failureOf(result)).isSameAs(failure);
  }

  @Test
  void dispatchesOnceAndDoesNotRetryAFailedCall() {
    // A retry this adapter never announced would bill a second call and could repeat a tool call
    // the caller already saw. Recovery is the caller's decision, on the original exception.
    CancellationSignal signal = new CancellationSignal();
    AtomicInteger dispatches = new AtomicInteger();

    CompletableFuture<String> result =
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

    CompletableFuture<String> result =
        OpenAiCallBridge.guard(
            signal,
            () -> {
              throw failure;
            });

    assertThatThrownBy(result::join)
        .isInstanceOf(CompletionException.class)
        .satisfies(thrown -> assertThat(thrown.getCause()).isSameAs(failure));
  }

  @Test
  void deliversAMissingProviderCallThroughTheStage() {
    // A port that answers with no stage at all would otherwise surface as a null pointer thrown out
    // of ModelClient.run, which is the one shape this bridge exists to rule out.
    CancellationSignal signal = new CancellationSignal();

    CompletableFuture<String> result = OpenAiCallBridge.guard(signal, () -> null);

    assertThatThrownBy(result::join)
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

  /** A completion wrapper with no cause, which the JDK only builds through a subclass. */
  private static final class CauselessCompletionException extends CompletionException {

    private static final long serialVersionUID = 1L;
  }

  /** An execution wrapper with no cause, which the JDK only builds through a subclass. */
  private static final class CauselessExecutionException extends ExecutionException {

    private static final long serialVersionUID = 1L;
  }
}
