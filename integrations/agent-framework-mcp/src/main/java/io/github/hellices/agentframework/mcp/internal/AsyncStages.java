package io.github.hellices.agentframework.mcp.internal;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

/** Bridges between the SDK's reactive types and the framework's {@link CompletionStage} results. */
final class AsyncStages {

  private AsyncStages() {}

  /**
   * Subscribes to a {@link Mono} and mirrors its outcome into a future.
   *
   * <p>Subscription happens on the calling thread and completion happens on the thread the SDK
   * signals on, so no executor is created and none is required. Completing the returned future,
   * including by cancelling it, disposes the subscription so the SDK can release the pending
   * request.
   *
   * @param mono the source, never {@code null}
   * @param <T> the value type
   * @return a future mirroring the source, never {@code null}
   */
  static <T> CompletableFuture<T> fromMono(Mono<T> mono) {
    CompletableFuture<T> future = new CompletableFuture<>();
    Disposable subscription =
        mono.subscribe(
            future::complete, future::completeExceptionally, () -> future.complete(null));
    future.whenComplete((value, failure) -> subscription.dispose());
    return future;
  }

  /**
   * Returns a stage that mirrors a source and cancels the work behind it when it is cancelled.
   *
   * <p>A caller that cancels the stage the adapter returned expects the in flight request to stop,
   * yet {@link CompletableFuture} neither forwards cancellation to the futures a stage was derived
   * from nor lets a caller reach them. The returned stage carries the cancellation action into
   * every stage derived from it, so cancellation still arrives even after the framework has chained
   * its own mapping onto the result.
   *
   * @param source the stage to mirror, never {@code null}
   * @param onCancel the work to stop, never {@code null}
   * @param <T> the value type
   * @return the cancellation aware stage, never {@code null}
   */
  static <T> CompletableFuture<T> cancellable(CompletableFuture<T> source, Runnable onCancel) {
    CancellationPropagatingFuture<T> stage = new CancellationPropagatingFuture<>(onCancel);
    source.whenComplete(
        (value, failure) -> {
          if (failure == null) {
            stage.complete(value);
          } else {
            stage.completeExceptionally(failure);
          }
        });
    return stage;
  }

  /**
   * Turns a stage the port returned into a future, rejecting a missing one.
   *
   * <p>A port implementation that returns {@code null} would otherwise surface as a null pointer
   * failure from inside the adapter rather than as a statement about the operation that failed.
   *
   * @param stage the stage the port returned, may be {@code null}
   * @param operation the protocol operation, never {@code null}
   * @param <T> the value type
   * @return the future, never {@code null}
   * @throws IllegalStateException if the stage is {@code null}
   */
  static <T> CompletableFuture<T> requireStage(CompletionStage<T> stage, String operation) {
    if (stage == null) {
      throw new IllegalStateException("MCP " + operation + " returned no stage");
    }
    return stage.toCompletableFuture();
  }

  /**
   * Runs work that may throw and turns a synchronous failure into a failed stage.
   *
   * <p>Request building, result mapping, and the metadata callback all run on the invocation path.
   * A caller that received a stage expects failures through that stage rather than as an exception
   * thrown from the method that returned it, so both failure modes are unified at this boundary.
   *
   * @param work the work to run, never {@code null}
   * @param <T> the result type
   * @return a stage with the result or the failure, never {@code null}
   */
  static <T> CompletableFuture<T> callSafely(Supplier<CompletableFuture<T>> work) {
    try {
      return work.get();
    } catch (RuntimeException failure) {
      return failed(failure);
    }
  }

  /**
   * Returns a stage that has already failed.
   *
   * @param failure the failure, never {@code null}
   * @param <T> the result type
   * @return the failed stage, never {@code null}
   */
  static <T> CompletableFuture<T> failed(Throwable failure) {
    CompletableFuture<T> future = new CompletableFuture<>();
    future.completeExceptionally(failure);
    return future;
  }

  /**
   * Unwraps the completion wrapper a composed stage adds, so a caller sees the original failure.
   *
   * @param failure the observed failure, never {@code null}
   * @return the failure to report, never {@code null}
   */
  static Throwable unwrap(Throwable failure) {
    if (failure instanceof CompletionException && failure.getCause() != null) {
      return failure.getCause();
    }
    return failure;
  }

  private static final class CancellationPropagatingFuture<T> extends CompletableFuture<T> {

    private final Runnable onCancel;

    private CancellationPropagatingFuture(Runnable onCancel) {
      this.onCancel = onCancel;
    }

    @Override
    public <U> CompletableFuture<U> newIncompleteFuture() {
      return new CancellationPropagatingFuture<>(onCancel);
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
      boolean cancelled = super.cancel(mayInterruptIfRunning);
      onCancel.run();
      return cancelled;
    }
  }
}
