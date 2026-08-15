package io.github.hellices.agentframework.api.agent;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;

public final class AgentRun {

  private final CompletionStage<AgentResponse> response;
  private final CancellationSignal cancellationSignal;
  private final Runnable cancellationAction;

  public AgentRun(AgentResponse response) {
    this(
        CompletableFuture.completedFuture(
            Objects.requireNonNull(response, "response must not be null")),
        new CancellationSignal());
  }

  public AgentRun(CompletionStage<AgentResponse> response, CancellationSignal cancellationSignal) {
    CompletionStage<AgentResponse> source =
        Objects.requireNonNull(response, "response must not be null");
    this.cancellationSignal =
        cancellationSignal == null ? new CancellationSignal() : cancellationSignal;
    CompletableFuture<AgentResponse> result = new CompletableFuture<>();
    Runnable removeCancellationListener =
        this.cancellationSignal.onCancel(
            () -> result.completeExceptionally(new CancellationException("run was cancelled")));
    source.whenComplete(
        (value, failure) -> {
          removeCancellationListener.run();
          if (failure == null) {
            result.complete(value);
          } else {
            result.completeExceptionally(failure);
          }
        });
    this.response = result.minimalCompletionStage();
    this.cancellationAction = this.cancellationSignal::cancel;
  }

  private AgentRun(
      CompletionStage<AgentResponse> response,
      CancellationSignal cancellationSignal,
      Runnable cancellationAction) {
    this.response = Objects.requireNonNull(response, "response must not be null");
    this.cancellationSignal =
        Objects.requireNonNull(cancellationSignal, "cancellationSignal must not be null");
    this.cancellationAction =
        Objects.requireNonNull(cancellationAction, "cancellationAction must not be null");
  }

  public CompletionStage<AgentResponse> response() {
    return response;
  }

  public void cancel() {
    cancellationAction.run();
  }

  /**
   * Package-private copy used by {@link Agent} to observe run completion without losing the
   * original cancellation wiring. The returned run's exposed response stage is derived via {@link
   * CompletionStage#whenComplete(BiConsumer)}, so joining it can only return once {@code
   * completion} has finished running: any exception {@code completion} throws (including {@code
   * SessionContext} lifecycle violations such as a pre-filled or double-completed response slot)
   * propagates through the returned run's response stage instead of being swallowed. The original
   * {@code cancellationAction} is preserved unchanged, so {@link #cancel()} keeps cancelling the
   * same underlying signal.
   */
  AgentRun withCompletion(BiConsumer<? super AgentResponse, ? super Throwable> completion) {
    Objects.requireNonNull(completion, "completion must not be null");
    return withResponse(response.whenComplete(completion));
  }

  /**
   * Package-private copy used by {@link Agent} to expose a response stage it derived from this
   * run's own stage (session context completion followed by the {@code afterRun} lifecycle seam),
   * while keeping the original cancellation wiring. Because the derived stage is the one callers
   * observe, every lifecycle step composed into it has finished before a caller's join returns, and
   * any failure it carries is surfaced instead of swallowed.
   */
  AgentRun withResponse(CompletionStage<AgentResponse> derivedResponse) {
    return new AgentRun(
        Objects.requireNonNull(derivedResponse, "response must not be null"),
        cancellationSignal,
        cancellationAction);
  }
}
