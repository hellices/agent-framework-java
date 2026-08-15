package io.github.hellices.agentframework.api.agent;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Builds the response stage a run exposes to callers, keeping it cancellable for exactly as long as
 * it is pending.
 *
 * <p>A run's caller-visible response stage is not always its underlying source stage: {@link
 * Agent#run(AgentRunRequest)} and {@link Agent#runStreaming(AgentRunRequest)} derive a stage that
 * additionally waits for session context completion and the {@link Agent#afterRun} seam. Without
 * this wrapping there would be a window in which the source stage already completed successfully
 * while the derived stage is still pending, and a {@link CancellationSignal#cancel()} in that
 * window would leave the caller waiting for lifecycle work it just asked to abandon.
 *
 * <p>The wrapper therefore fails the exposed stage with a {@link CancellationException} on
 * cancellation, and removes its listener as soon as the source completes so a finished run neither
 * retains a listener on the signal nor reacts to a later cancellation. Because the exposed future
 * accepts only its first terminal result, a source completing after cancellation cannot overwrite
 * the cancellation outcome, and a cancellation after completion cannot overwrite a published
 * result.
 */
final class CancellationAwareResponse {

  private CancellationAwareResponse() {}

  static CompletionStage<AgentResponse> wrap(
      CompletionStage<AgentResponse> source, CancellationSignal cancellationSignal) {
    CompletionStage<AgentResponse> value =
        Objects.requireNonNull(source, "response must not be null");
    CancellationSignal signal =
        Objects.requireNonNull(cancellationSignal, "cancellationSignal must not be null");
    CompletableFuture<AgentResponse> result = new CompletableFuture<>();
    Runnable removeCancellationListener =
        signal.onCancel(
            () -> result.completeExceptionally(new CancellationException("run was cancelled")));
    value.whenComplete(
        (response, failure) -> {
          removeCancellationListener.run();
          if (failure == null) {
            result.complete(response);
          } else {
            result.completeExceptionally(failure);
          }
        });
    return result.minimalCompletionStage();
  }
}
