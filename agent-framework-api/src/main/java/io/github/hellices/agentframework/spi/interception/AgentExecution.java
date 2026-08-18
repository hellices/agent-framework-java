package io.github.hellices.agentframework.spi.interception;

import io.github.hellices.agentframework.api.agent.AgentResponseUpdate;
import io.github.hellices.agentframework.api.agent.CancellationSignal;
import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Pre-finalization agent execution returned by the agent interceptor seam.
 *
 * <p>This value carries only the canonical update stream and the execution's cancellation signal.
 * The engine owns final-response derivation, session persistence, and every post-stream lifecycle
 * step after the returned updates finish, so those stages are deliberately not exposed here.
 */
public final class AgentExecution {

  private final Flow.Publisher<AgentResponseUpdate> updates;
  private final CancellationSignal cancellationSignal;

  private AgentExecution(
      Flow.Publisher<AgentResponseUpdate> updates, CancellationSignal cancellationSignal) {
    this.updates = singleSubscriber(updates);
    this.cancellationSignal =
        cancellationSignal == null ? new CancellationSignal() : cancellationSignal;
  }

  /**
   * Creates a pre-finalization execution from one update stream.
   *
   * <p>The returned execution does not expose a final response or session stage. The engine derives
   * those exactly once from the consumed updates later in the run pipeline.
   */
  public static AgentExecution fromUpdates(
      Flow.Publisher<AgentResponseUpdate> updates, CancellationSignal cancellationSignal) {
    return new AgentExecution(
        Objects.requireNonNull(updates, "updates must not be null"), cancellationSignal);
  }

  /**
   * Creates a pre-finalization execution from one update value.
   *
   * <p>The engine still owns final-response derivation and later lifecycle completion.
   */
  public static AgentExecution fromUpdate(
      AgentResponseUpdate update, CancellationSignal cancellationSignal) {
    return new AgentExecution(
        new SingleValuePublisher<>(Objects.requireNonNull(update, "update must not be null")),
        cancellationSignal);
  }

  public Flow.Publisher<AgentResponseUpdate> updates() {
    return updates;
  }

  public CancellationSignal cancellationSignal() {
    return cancellationSignal;
  }

  public AgentExecution mapUpdates(
      Function<? super AgentResponseUpdate, ? extends AgentResponseUpdate> mapper) {
    Objects.requireNonNull(mapper, "mapper must not be null");
    return new AgentExecution(new MappingPublisher<>(updates, mapper), cancellationSignal);
  }

  @SuppressWarnings("unchecked")
  private static Flow.Publisher<AgentResponseUpdate> singleSubscriber(
      Flow.Publisher<AgentResponseUpdate> updates) {
    Flow.Publisher<AgentResponseUpdate> source =
        Objects.requireNonNull(updates, "updates must not be null");
    if (source instanceof SingleSubscriberPublisher<?> singleSubscriberPublisher) {
      return (Flow.Publisher<AgentResponseUpdate>) singleSubscriberPublisher;
    }
    return new SingleSubscriberPublisher<>(source);
  }

  private static final class MappingPublisher<T, R> implements Flow.Publisher<R> {
    private final Flow.Publisher<T> source;
    private final Function<? super T, ? extends R> mapper;

    private MappingPublisher(Flow.Publisher<T> source, Function<? super T, ? extends R> mapper) {
      this.source = source;
      this.mapper = mapper;
    }

    @Override
    public void subscribe(Flow.Subscriber<? super R> subscriber) {
      Objects.requireNonNull(subscriber, "subscriber must not be null");
      source.subscribe(
          new Flow.Subscriber<>() {
            private Flow.Subscription subscription;
            private boolean terminated;

            @Override
            public void onSubscribe(Flow.Subscription subscription) {
              this.subscription = subscription;
              subscriber.onSubscribe(subscription);
            }

            @Override
            public void onNext(T item) {
              if (terminated) {
                return;
              }
              R mapped;
              try {
                mapped = Objects.requireNonNull(mapper.apply(item), "mapper must not return null");
              } catch (RuntimeException exception) {
                terminated = true;
                subscription.cancel();
                subscriber.onError(exception);
                return;
              }
              subscriber.onNext(mapped);
            }

            @Override
            public void onError(Throwable throwable) {
              if (!terminated) {
                terminated = true;
                subscriber.onError(throwable);
              }
            }

            @Override
            public void onComplete() {
              if (!terminated) {
                terminated = true;
                subscriber.onComplete();
              }
            }
          });
    }
  }

  private static final class SingleValuePublisher<T> implements Flow.Publisher<T> {
    private final T value;

    private SingleValuePublisher(T value) {
      this.value = value;
    }

    @Override
    public void subscribe(Flow.Subscriber<? super T> subscriber) {
      Objects.requireNonNull(subscriber, "subscriber must not be null");
      subscriber.onSubscribe(
          new Flow.Subscription() {
            private boolean completed;

            @Override
            public void request(long n) {
              if (completed) {
                return;
              }
              if (n <= 0) {
                completed = true;
                subscriber.onError(
                    new IllegalArgumentException("demand must be greater than zero"));
                return;
              }
              completed = true;
              subscriber.onNext(value);
              subscriber.onComplete();
            }

            @Override
            public void cancel() {
              completed = true;
            }
          });
    }
  }

  private static final class SingleSubscriberPublisher<T> implements Flow.Publisher<T> {
    private final Flow.Publisher<T> source;
    private final AtomicBoolean subscribed = new AtomicBoolean();

    private SingleSubscriberPublisher(Flow.Publisher<T> source) {
      this.source = source;
    }

    @Override
    public void subscribe(Flow.Subscriber<? super T> subscriber) {
      Objects.requireNonNull(subscriber, "subscriber must not be null");
      if (!subscribed.compareAndSet(false, true)) {
        subscriber.onSubscribe(EmptySubscription.INSTANCE);
        subscriber.onError(new IllegalStateException("updates can only be consumed once"));
        return;
      }
      source.subscribe(subscriber);
    }
  }

  private enum EmptySubscription implements Flow.Subscription {
    INSTANCE;

    @Override
    public void request(long n) {}

    @Override
    public void cancel() {}
  }
}
