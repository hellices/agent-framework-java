package io.github.hellices.agentframework.spi.model;

import io.github.hellices.agentframework.api.message.FinishReason;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Minimal Reactive-Streams-correct {@link ModelClient} stubs for API tests, which cannot depend on
 * the testkit's {@code ModelPublishers}.
 */
public final class StubModelClients {

  private StubModelClients() {}

  /** A client whose every call emits one update and then completes. */
  public static ModelClient stub() {
    return request -> completing();
  }

  /** A publisher that emits one {@code STOP} update and then completes. */
  public static Flow.Publisher<ModelResponseUpdate> completing() {
    ModelResponseUpdate update =
        ModelResponseUpdate.builder().finishReason(FinishReason.STOP).build();
    return subscriber -> {
      if (subscriber == null) {
        throw new NullPointerException("subscriber must not be null");
      }
      subscriber.onSubscribe(
          new Flow.Subscription() {
            private final AtomicBoolean terminated = new AtomicBoolean(false);

            @Override
            public void request(long n) {
              if (terminated.get()) {
                return;
              }
              if (n <= 0) {
                if (terminated.compareAndSet(false, true)) {
                  subscriber.onError(
                      new IllegalArgumentException("request must be positive, was " + n));
                }
                return;
              }
              if (terminated.compareAndSet(false, true)) {
                subscriber.onNext(update);
                subscriber.onComplete();
              }
            }

            @Override
            public void cancel() {
              terminated.set(true);
            }
          });
    };
  }
}
