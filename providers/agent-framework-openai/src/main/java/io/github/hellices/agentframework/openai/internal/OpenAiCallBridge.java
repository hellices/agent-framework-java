package io.github.hellices.agentframework.openai.internal;

import io.github.hellices.agentframework.api.agent.CancellationSignal;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

/**
 * Bridges a framework cancellation signal and a provider call.
 *
 * <p>Cancellation stops the framework from waiting. It does not abort the HTTP request: the future
 * the SDK returns is derived from its transport future with {@code thenApply}, and a JDK {@code
 * CompletableFuture} never completes its antecedent. Cancelling the future taken from the returned
 * stage does not abort it either - that future is a copy, so it abandons one caller's wait and
 * nothing else. Only a timeout reclaims the work nobody is waiting for any more.
 *
 * <p>The bridge neither retries nor logs. It dispatches one call once, and a provider failure
 * reaches the caller as the instance the SDK threw, because that instance carries the status code,
 * the request id, and the retry advice a caller needs, and a second attempt this adapter never
 * announced would double a billed call. The SDK client the host injected may still retry inside
 * that single dispatch: {@code maxRetries} is the host's setting on its own client builder and
 * defaults to two, so an abandoned call can outlive the run by roughly the per-request timeout
 * times the number of attempts, plus the backoff the SDK waits between them. The adapter's
 * per-request timeout bounds one attempt, not the whole call.
 *
 * <p>Null arguments are a call-site programming error and are rejected where the call was made. A
 * signal that registers a listener and hands back no removal handle is the same kind of error: it
 * is rejected at the registration, before anything is dispatched, rather than left to fail inside a
 * completion action whose derived stage this bridge drops - there the failure would be reported
 * nowhere, the listener would stay registered, and the request would already be in flight.
 * Everything the dispatch itself does - a mapping failure, no stage at all, a provider failure -
 * arrives through the returned stage instead, so a caller handles one failure path rather than two.
 * A {@link Error} is the exception to that rule: it is rethrown as thrown, after this bridge has
 * settled the listener and the result it owns.
 *
 * <p>The returned stage carries no completion authority. Cancellation completes it with a {@link
 * CancellationException}, which a caller should recognise by type rather than by message, and which
 * a reader always sees behind a {@link CompletionException} because the exposed stage relays this
 * call's outcome: {@link #unwrap(Throwable)} peels that wrapper.
 */
public final class OpenAiCallBridge {

  private OpenAiCallBridge() {}

  /**
   * The part of a cancellation signal this bridge uses.
   *
   * <p>A {@code CancellationSignal} cannot report whether a listener was removed, so a test that
   * asserted cleanup through it would assert nothing. This seam makes the removal observable.
   */
  interface Cancellation {

    boolean isCancelled();

    /**
     * Registers a cancellation listener.
     *
     * @param listener the listener to run on cancellation
     * @return the handle that removes {@code listener}, never {@code null}: without it the bridge
     *     cannot remove the listener it registered
     */
    Runnable onCancel(Runnable listener);
  }

  /**
   * Runs a provider call under a cancellation signal.
   *
   * @param signal the run's cancellation signal, never {@code null}
   * @param dispatch produces the provider call; never invoked when the signal is already cancelled
   * @param <T> the value the provider call produces
   * @return a stage that fails with {@link CancellationException} on cancellation and otherwise
   *     mirrors the provider call, with the original failure preserved and never thrown from this
   *     method; completing or cancelling the future taken from it settles that future alone
   * @throws NullPointerException if {@code signal} or {@code dispatch} is {@code null}, or if the
   *     signal registers the cancellation listener without returning a removal handle
   */
  public static <T> CompletionStage<T> guard(
      CancellationSignal signal, Supplier<CompletableFuture<T>> dispatch) {
    Objects.requireNonNull(signal, "signal must not be null");
    Objects.requireNonNull(dispatch, "dispatch must not be null");
    return guardStage(
        new Cancellation() {
          @Override
          public boolean isCancelled() {
            return signal.isCancelled();
          }

          @Override
          public Runnable onCancel(Runnable listener) {
            return signal.onCancel(listener);
          }
        },
        dispatch);
  }

  static <T> CompletionStage<T> guardStage(
      Cancellation cancellation, Supplier<CompletableFuture<T>> dispatch) {
    // A minimal stage, not the call's own future: a caller that completed or cancelled the future
    // it was handed would otherwise decide this call's outcome, hide the provider's own answer, and
    // remove a cancellation listener while the request it belongs to is still in flight.
    return guard(cancellation, dispatch).minimalCompletionStage();
  }

  static <T> CompletableFuture<T> guard(
      Cancellation cancellation, Supplier<CompletableFuture<T>> dispatch) {
    Objects.requireNonNull(cancellation, "cancellation must not be null");
    Objects.requireNonNull(dispatch, "dispatch must not be null");
    if (cancellation.isCancelled()) {
      return CompletableFuture.failedFuture(
          new CancellationException("model call was cancelled before it was dispatched"));
    }
    CompletableFuture<T> result = new CompletableFuture<>();
    Runnable deregistration =
        Objects.requireNonNull(
            cancellation.onCancel(
                () ->
                    result.completeExceptionally(
                        new CancellationException("model call was cancelled"))),
            "onCancel must return a deregistration handle");
    result.whenComplete((value, failure) -> deregistration.run());
    if (result.isDone()) {
      // Cancelled between the check and the registration. The listener already ran, so the request
      // must not be dispatched at all.
      return result;
    }
    CompletableFuture<T> dispatched;
    try {
      dispatched = dispatch.get();
    } catch (RuntimeException failure) {
      // Request mapping runs inside the supplier, and ModelClient.execute must never throw
      // synchronously: the engine calls it from inside and outside a completion stage, so a throw
      // would surface in two different places depending on the tool loop iteration.
      result.completeExceptionally(failure);
      return result;
    } catch (Error fatal) {
      // A fatal error is not a request failure. Turning it into a failed stage would offer it to a
      // caller to map or retry as if the provider had answered, so the listener and the result this
      // bridge owns are settled and the same instance is rethrown.
      result.completeExceptionally(fatal);
      throw fatal;
    }
    if (dispatched == null) {
      // A port that answers with no stage would otherwise be a null pointer thrown from inside the
      // bridge, which is both the synchronous throw above and a cancellation listener that is never
      // removed because the result never completes.
      result.completeExceptionally(
          new IllegalStateException("the OpenAI chat completion call returned no stage"));
      return result;
    }
    dispatched.whenComplete(
        (value, failure) -> {
          if (failure == null) {
            result.complete(value);
          } else {
            result.completeExceptionally(unwrap(failure));
          }
        });
    return result;
  }

  /**
   * Returns the provider failure behind an asynchronous wrapper.
   *
   * @param failure the failure a completion stage reported, never {@code null}
   * @return the original provider exception instance where there is one
   */
  public static Throwable unwrap(Throwable failure) {
    boolean wrapped =
        failure instanceof CompletionException || failure instanceof ExecutionException;
    return wrapped && failure.getCause() != null ? failure.getCause() : failure;
  }
}
