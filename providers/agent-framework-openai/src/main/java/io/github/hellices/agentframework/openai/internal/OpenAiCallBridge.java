package io.github.hellices.agentframework.openai.internal;

import io.github.hellices.agentframework.api.agent.CancellationSignal;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

/**
 * Bridges a framework cancellation signal and a provider call.
 *
 * <p>Cancellation stops the framework from waiting. It does not abort the HTTP request: the future
 * the SDK returns is derived from its transport future with {@code thenApply}, and a JDK {@code
 * CompletableFuture} never completes its antecedent. The per-request timeout is what bounds the
 * work nobody is waiting for any more.
 *
 * <p>The bridge neither retries nor logs. A provider failure reaches the caller as the instance the
 * SDK threw, because that instance carries the status code, the request id, and the retry advice a
 * caller needs, and a second attempt this adapter never announced would double a billed call.
 *
 * <p>Cancellation completes the returned stage with a {@link CancellationException}, which callers
 * should recognise by type rather than by message: a derived stage reports it as a {@link
 * CompletionException} cause, and {@code CompletableFuture.join} rethrows the instance itself up to
 * JDK 22 but a fresh {@code CancellationException} carrying it as the cause from JDK 23 on.
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
   *     method
   */
  public static <T> CompletableFuture<T> guard(
      CancellationSignal signal, Supplier<CompletableFuture<T>> dispatch) {
    return guard(
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

  static <T> CompletableFuture<T> guard(
      Cancellation cancellation, Supplier<CompletableFuture<T>> dispatch) {
    if (cancellation.isCancelled()) {
      return CompletableFuture.failedFuture(
          new CancellationException("model call was cancelled before it was dispatched"));
    }
    CompletableFuture<T> result = new CompletableFuture<>();
    Runnable deregistration =
        cancellation.onCancel(
            () ->
                result.completeExceptionally(
                    new CancellationException("model call was cancelled")));
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
      // Request mapping runs inside the supplier, and ModelClient.run must never throw
      // synchronously: the engine calls it from inside and outside a completion stage, so a throw
      // would surface in two different places depending on the tool loop iteration.
      result.completeExceptionally(failure);
      return result;
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
