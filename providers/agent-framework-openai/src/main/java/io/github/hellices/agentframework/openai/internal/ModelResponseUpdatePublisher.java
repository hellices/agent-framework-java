package io.github.hellices.agentframework.openai.internal;

import io.github.hellices.agentframework.api.agent.CancellationSignal;
import io.github.hellices.agentframework.spi.model.ModelResponseUpdate;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Adapts the one-shot {@link CompletionStage} an OpenAI chat call produces into the {@link
 * Flow.Publisher} the unified {@link io.github.hellices.agentframework.spi.model.ModelClient}
 * contract requires, without blocking a thread or scheduling work on an executor.
 *
 * <p>The publisher is cold: each subscription mirrors the same stage outcome — the stage's value as
 * one update followed by completion, or the stage's failure as {@code onError}. It obeys the
 * Reactive Streams rules the contract documents: {@code onSubscribe} is signalled before any other
 * signal (even when the stage has already failed), an update is emitted only after a positive
 * request, a non-positive request terminates with a single {@link IllegalArgumentException}, and
 * exactly one terminal signal is delivered. Cancelling the subscription cancels the request's
 * {@link CancellationSignal}, so the OpenAI call the stage represents observes that no one is
 * waiting any more.
 */
public final class ModelResponseUpdatePublisher implements Flow.Publisher<ModelResponseUpdate> {

  private final CompletionStage<ModelResponseUpdate> stage;
  private final CancellationSignal cancellation;

  public ModelResponseUpdatePublisher(
      CompletionStage<ModelResponseUpdate> stage, CancellationSignal cancellation) {
    this.stage = Objects.requireNonNull(stage, "stage must not be null");
    this.cancellation = Objects.requireNonNull(cancellation, "cancellation must not be null");
  }

  @Override
  public void subscribe(Flow.Subscriber<? super ModelResponseUpdate> subscriber) {
    Objects.requireNonNull(subscriber, "subscriber must not be null");
    StageSubscription subscription = new StageSubscription(subscriber, cancellation);
    subscriber.onSubscribe(subscription);
    subscription.attach(stage);
  }

  private static Throwable unwrap(Throwable failure) {
    boolean wrapped =
        failure instanceof CompletionException || failure instanceof ExecutionException;
    return wrapped && failure.getCause() != null ? failure.getCause() : failure;
  }

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
      if (result.failure() != null) {
        subscriber.onError(unwrap(result.failure()));
      } else if (result.value() != null) {
        subscriber.onNext(result.value());
        subscriber.onComplete();
      } else {
        subscriber.onError(new IllegalStateException("stage completed without a response"));
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
