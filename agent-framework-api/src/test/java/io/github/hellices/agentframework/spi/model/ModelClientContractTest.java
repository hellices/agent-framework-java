package io.github.hellices.agentframework.spi.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hellices.agentframework.api.message.FinishReason;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * The Reactive Streams contract every {@link ModelClient#execute(ModelRequest)} publisher must
 * honour, expressed against a minimal in-test provider so a real provider adapter can be checked
 * against the same expectations.
 */
class ModelClientContractTest {

  private static final ModelResponseUpdate UPDATE =
      ModelResponseUpdate.builder().finishReason(FinishReason.STOP).build();

  @Test
  void nullSubscriberIsRejected() {
    Flow.Publisher<ModelResponseUpdate> publisher = oneShot(UPDATE, null).execute(request());

    assertThatThrownBy(() -> publisher.subscribe(nullSubscriber()))
        .isInstanceOf(NullPointerException.class);
  }

  private static Flow.Subscriber<ModelResponseUpdate> nullSubscriber() {
    return null;
  }

  @Test
  void oneShotUpdateArrivesInOrderAfterDemand() {
    RecordingSubscriber subscriber = new RecordingSubscriber(1);

    oneShot(UPDATE, null).execute(request()).subscribe(subscriber);

    assertThat(subscriber.signals())
        .containsExactly("onSubscribe", "onNext:" + UPDATE, "onComplete");
  }

  @Test
  void noUpdateIsEmittedBeforePositiveDemand() {
    RecordingSubscriber subscriber = new RecordingSubscriber(0);

    oneShot(UPDATE, null).execute(request()).subscribe(subscriber);

    assertThat(subscriber.signals()).containsExactly("onSubscribe");

    subscriber.subscription().request(1);
    assertThat(subscriber.signals())
        .containsExactly("onSubscribe", "onNext:" + UPDATE, "onComplete");
  }

  @Test
  void failureIsDeliveredAfterSubscribeWithNoUpdate() {
    RuntimeException failure = new IllegalStateException("model failed");
    RecordingSubscriber subscriber = new RecordingSubscriber(1);

    oneShot(null, failure).execute(request()).subscribe(subscriber);

    assertThat(subscriber.signals()).containsExactly("onSubscribe", "onError:" + failure);
  }

  @Test
  void nonPositiveDemandSignalsOneIllegalArgumentException() {
    RecordingSubscriber subscriber = new RecordingSubscriber(0);
    oneShot(UPDATE, null).execute(request()).subscribe(subscriber);

    subscriber.subscription().request(0);
    subscriber.subscription().request(-5);

    assertThat(subscriber.errors()).hasSize(1).allMatch(IllegalArgumentException.class::isInstance);
    assertThat(subscriber.terminalCount()).isEqualTo(1);
  }

  @Test
  void cancelPreventsFurtherSignals() {
    RecordingSubscriber subscriber = new RecordingSubscriber(0);
    oneShot(UPDATE, null).execute(request()).subscribe(subscriber);

    subscriber.subscription().cancel();
    subscriber.subscription().request(1);

    assertThat(subscriber.signals()).containsExactly("onSubscribe");
    assertThat(subscriber.terminalCount()).isZero();
  }

  @Test
  void exactlyOneTerminalSignalOnRepeatedDemand() {
    RecordingSubscriber subscriber = new RecordingSubscriber(0);
    oneShot(UPDATE, null).execute(request()).subscribe(subscriber);

    subscriber.subscription().request(1);
    subscriber.subscription().request(1);

    assertThat(subscriber.terminalCount()).isEqualTo(1);
  }

  private static ModelRequest request() {
    return ModelRequest.builder().build();
  }

  /** A minimal compliant provider that answers one call with a single update or a failure. */
  private static ModelClient oneShot(ModelResponseUpdate update, Throwable failure) {
    return req ->
        subscriber -> {
          if (subscriber == null) {
            throw new NullPointerException("subscriber must not be null");
          }
          subscriber.onSubscribe(new OneShotSubscription(subscriber, update, failure));
        };
  }

  private static final class OneShotSubscription implements Flow.Subscription {
    private final Flow.Subscriber<? super ModelResponseUpdate> subscriber;
    private final ModelResponseUpdate update;
    private final Throwable failure;
    private final AtomicBoolean terminated = new AtomicBoolean(false);

    private OneShotSubscription(
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

  private static final class RecordingSubscriber implements Flow.Subscriber<ModelResponseUpdate> {
    private final List<String> signals = new ArrayList<>();
    private final List<Throwable> errors = new ArrayList<>();
    private final long initialDemand;
    private Flow.Subscription subscription;
    private int terminalCount;

    private RecordingSubscriber(long initialDemand) {
      this.initialDemand = initialDemand;
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
      this.subscription = subscription;
      signals.add("onSubscribe");
      if (initialDemand > 0) {
        subscription.request(initialDemand);
      }
    }

    @Override
    public void onNext(ModelResponseUpdate item) {
      signals.add("onNext:" + item);
    }

    @Override
    public void onError(Throwable throwable) {
      signals.add("onError:" + throwable);
      errors.add(throwable);
      terminalCount++;
    }

    @Override
    public void onComplete() {
      signals.add("onComplete");
      terminalCount++;
    }

    private List<String> signals() {
      return signals;
    }

    private List<Throwable> errors() {
      return errors;
    }

    private Flow.Subscription subscription() {
      return subscription;
    }

    private int terminalCount() {
      return terminalCount;
    }
  }
}
