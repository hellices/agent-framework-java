package io.github.hellices.agentframework.engine;

import io.github.hellices.agentframework.spi.model.ModelClient;
import io.github.hellices.agentframework.spi.model.ModelRequest;
import io.github.hellices.agentframework.spi.model.ModelResponse;
import io.github.hellices.agentframework.spi.model.ModelResponseUpdate;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reactive-Streams-correct publishers that let engine tests express a model invocation as the
 * unified {@link ModelClient#execute(ModelRequest) execute} result without depending on the
 * testkit.
 */
public final class EngineModels {

  private EngineModels() {}

  /** A publisher that emits the given response as one update and then completes. */
  public static Flow.Publisher<ModelResponseUpdate> of(ModelResponse response) {
    return ofUpdate(toUpdate(response));
  }

  /** A publisher that emits one update and then completes. */
  public static Flow.Publisher<ModelResponseUpdate> ofUpdate(ModelResponseUpdate update) {
    return subscriber -> {
      if (subscriber == null) {
        throw new NullPointerException("subscriber must not be null");
      }
      subscriber.onSubscribe(new SingleSubscription(subscriber, update, null));
    };
  }

  /** A publisher that terminates with the given failure without emitting an update. */
  public static Flow.Publisher<ModelResponseUpdate> failed(Throwable failure) {
    return subscriber -> {
      if (subscriber == null) {
        throw new NullPointerException("subscriber must not be null");
      }
      subscriber.onSubscribe(new SingleSubscription(subscriber, null, failure));
    };
  }

  /** A publisher that mirrors a stage's response outcome once the stage completes. */
  public static Flow.Publisher<ModelResponseUpdate> fromStage(
      CompletionStage<ModelResponse> stage) {
    return subscriber -> {
      if (subscriber == null) {
        throw new NullPointerException("subscriber must not be null");
      }
      StageSubscription subscription = new StageSubscription(subscriber);
      subscriber.onSubscribe(subscription);
      subscription.attach(stage);
    };
  }

  public static ModelResponseUpdate toUpdate(ModelResponse response) {
    return ModelResponseUpdate.builder()
        .messages(response.messages())
        .usage(response.usage())
        .finishReason(response.finishReason())
        .continuationToken(response.continuationToken())
        .metadata(response.metadata())
        .rawRepresentation(response.rawRepresentation())
        .build();
  }

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

  private static final class StageSubscription implements Flow.Subscription {
    private final Flow.Subscriber<? super ModelResponseUpdate> subscriber;
    private final AtomicReference<Outcome> outcome = new AtomicReference<>();
    private final AtomicBoolean requested = new AtomicBoolean(false);
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicBoolean terminated = new AtomicBoolean(false);

    private StageSubscription(Flow.Subscriber<? super ModelResponseUpdate> subscriber) {
      this.subscriber = subscriber;
    }

    private void attach(CompletionStage<ModelResponse> stage) {
      stage.whenComplete(
          (value, failure) -> {
            outcome.set(new Outcome(value == null ? null : toUpdate(value), failure));
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
        subscriber.onError(result.failure());
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
    }
  }

  private record Outcome(ModelResponseUpdate value, Throwable failure) {}
}
