package io.github.hellices.agentframework.testkit.model;

import io.github.hellices.agentframework.api.agent.CancellationSignal;
import io.github.hellices.agentframework.spi.model.ModelClient;
import io.github.hellices.agentframework.spi.model.ModelRequest;
import io.github.hellices.agentframework.spi.model.ModelResponseUpdate;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reactive-Streams-correct {@link Flow.Publisher} factories a test or a provider adapter can hand
 * to the engine as the result of a {@link ModelClient#execute(ModelRequest) model invocation}.
 *
 * <p>Every publisher these factories return is cold and reusable: each {@link
 * Flow.Publisher#subscribe(Flow.Subscriber) subscribe} attaches a fresh subscription that replays
 * the same one-shot outcome, which is what lets one instance stand in for repeated model calls in a
 * test without a subscriber exhausting it for the next. That matches how the engine uses a model
 * client — it subscribes once per invocation — while keeping the helpers safe to subscribe to more
 * than once.
 *
 * <p>Each subscription obeys the same rules {@link
 * io.github.hellices.agentframework.spi.model.ModelClient} documents: {@code onSubscribe} is
 * signalled before any other signal, an update is emitted only after a positive {@link
 * Flow.Subscription#request(long) request}, a non-positive request terminates the subscription with
 * a single {@link IllegalArgumentException} delivered through {@code onError}, exactly one of
 * {@code onComplete} or {@code onError} terminates the subscription, and {@link
 * Flow.Subscription#cancel()} stops further signals. Nothing here blocks a thread or schedules work
 * on an executor: a synchronous outcome is delivered inline on the requesting thread, and an
 * asynchronous one on whatever thread completed the stage.
 */
public final class ModelPublishers {

  private ModelPublishers() {}

  /**
   * A publisher that emits one update and then completes.
   *
   * @param update the single update to emit, never {@code null}
   * @return a cold publisher, never {@code null}
   */
  public static Flow.Publisher<ModelResponseUpdate> just(ModelResponseUpdate update) {
    Objects.requireNonNull(update, "update must not be null");
    return subscriber -> {
      Objects.requireNonNull(subscriber, "subscriber must not be null");
      subscriber.onSubscribe(new SingleSubscription(subscriber, update, null));
    };
  }

  /**
   * A publisher that terminates with a failure without emitting any update.
   *
   * @param failure the terminal failure, never {@code null}
   * @return a cold publisher, never {@code null}
   */
  public static Flow.Publisher<ModelResponseUpdate> failed(Throwable failure) {
    Objects.requireNonNull(failure, "failure must not be null");
    return subscriber -> {
      Objects.requireNonNull(subscriber, "subscriber must not be null");
      subscriber.onSubscribe(new SingleSubscription(subscriber, null, failure));
    };
  }

  /**
   * A publisher that mirrors the outcome of a {@link CompletionStage}: the stage's value becomes
   * one update followed by completion, and the stage's failure becomes {@code onError}.
   *
   * <p>{@code onSubscribe} is always signalled before the outcome is observed — even when the stage
   * has already failed synchronously — so a subscriber that fails to subscribe cannot be confused
   * with one whose stage failed. Cancelling the subscription cancels the supplied {@link
   * CancellationSignal}, so a provider call the stage represents can observe that no one is waiting
   * any more.
   *
   * @param stage the stage whose single outcome the publisher mirrors, never {@code null}
   * @param cancellation the signal cancelled when the subscription is cancelled, never {@code null}
   * @return a cold publisher, never {@code null}
   */
  public static Flow.Publisher<ModelResponseUpdate> fromStage(
      CompletionStage<ModelResponseUpdate> stage, CancellationSignal cancellation) {
    Objects.requireNonNull(stage, "stage must not be null");
    Objects.requireNonNull(cancellation, "cancellation must not be null");
    return subscriber -> {
      Objects.requireNonNull(subscriber, "subscriber must not be null");
      StageSubscription subscription = new StageSubscription(subscriber, cancellation);
      subscriber.onSubscribe(subscription);
      subscription.attach(stage);
    };
  }

  private static Throwable unwrap(Throwable failure) {
    boolean wrapped =
        failure instanceof CompletionException || failure instanceof ExecutionException;
    return wrapped && failure.getCause() != null ? failure.getCause() : failure;
  }

  /** A subscription whose one-shot outcome is known synchronously at subscribe time. */
  private static final class SingleSubscription implements Flow.Subscription {

    private final Flow.Subscriber<? super ModelResponseUpdate> subscriber;
    private final ModelResponseUpdate update;
    private final Throwable failure;
    private final AtomicBoolean terminated = new AtomicBoolean(false);

    private SingleSubscription(
        Flow.Subscriber<? super ModelResponseUpdate> subscriber,
        ModelResponseUpdate update,
        Throwable failure) {
      this.subscriber = subscriber;
      this.update = update;
      this.failure = failure;
    }

    @Override
    public void request(long n) {
      if (terminated.get()) {
        return;
      }
      if (n <= 0) {
        if (terminated.compareAndSet(false, true)) {
          subscriber.onError(new IllegalArgumentException("request must be positive, was " + n));
        }
        return;
      }
      if (terminated.compareAndSet(false, true)) {
        if (failure != null) {
          subscriber.onError(failure);
        } else {
          subscriber.onNext(update);
          subscriber.onComplete();
        }
      }
    }

    @Override
    public void cancel() {
      terminated.set(true);
    }
  }

  /** A subscription whose one-shot outcome arrives from a {@link CompletionStage}. */
  private static final class StageSubscription implements Flow.Subscription {

    private final Flow.Subscriber<? super ModelResponseUpdate> subscriber;
    private final CancellationSignal cancellation;
    private final AtomicReference<Outcome> outcome = new AtomicReference<>();
    private final AtomicBoolean requested = new AtomicBoolean(false);
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicBoolean terminated = new AtomicBoolean(false);

    private StageSubscription(
        Flow.Subscriber<? super ModelResponseUpdate> subscriber, CancellationSignal cancellation) {
      this.subscriber = subscriber;
      this.cancellation = cancellation;
    }

    private void attach(CompletionStage<ModelResponseUpdate> stage) {
      stage.whenComplete(
          (value, failure) -> {
            outcome.set(new Outcome(value, failure));
            deliver();
          });
    }

    @Override
    public void request(long n) {
      if (terminated.get() || cancelled.get()) {
        return;
      }
      if (n <= 0) {
        if (terminated.compareAndSet(false, true)) {
          subscriber.onError(new IllegalArgumentException("request must be positive, was " + n));
        }
        return;
      }
      requested.set(true);
      deliver();
    }

    private void deliver() {
      if (!requested.get() || cancelled.get()) {
        return;
      }
      Outcome result = outcome.get();
      if (result == null) {
        return;
      }
      if (!terminated.compareAndSet(false, true)) {
        return;
      }
      if (result.failure != null) {
        subscriber.onError(unwrap(result.failure));
      } else if (result.value != null) {
        subscriber.onNext(result.value);
        subscriber.onComplete();
      } else {
        subscriber.onError(new IllegalStateException("stage completed without an update"));
      }
    }

    @Override
    public void cancel() {
      cancelled.set(true);
      terminated.compareAndSet(false, true);
      cancellation.cancel();
    }
  }

  private record Outcome(ModelResponseUpdate value, Throwable failure) {}
}
