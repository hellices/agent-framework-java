package io.github.hellices.agentframework.api.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

public final class AgentStreamingRun<T> {

  private final Flow.Publisher<T> updates;
  private final CompletionStage<AgentResponse> response;
  private final CancellationSignal cancellationSignal;
  private final Runnable cancellationAction;

  public AgentStreamingRun(
      Flow.Publisher<T> updates,
      CompletionStage<AgentResponse> response,
      CancellationSignal cancellationSignal) {
    this.updates = Objects.requireNonNull(updates, "updates must not be null");
    this.response = Objects.requireNonNull(response, "response must not be null");
    this.cancellationSignal =
        cancellationSignal == null ? new CancellationSignal() : cancellationSignal;
    this.cancellationAction = this.cancellationSignal::cancel;
  }

  private AgentStreamingRun(
      Flow.Publisher<T> updates,
      CompletionStage<AgentResponse> response,
      CancellationSignal cancellationSignal,
      Runnable cancellationAction) {
    this.updates = Objects.requireNonNull(updates, "updates must not be null");
    this.response = Objects.requireNonNull(response, "response must not be null");
    this.cancellationSignal =
        cancellationSignal == null ? new CancellationSignal() : cancellationSignal;
    this.cancellationAction =
        Objects.requireNonNull(cancellationAction, "cancellationAction must not be null");
  }

  public static AgentStreamingRun<AgentResponseUpdate> fromUpdate(AgentResponseUpdate update) {
    return fromUpdate(update, new CancellationSignal());
  }

  public static AgentStreamingRun<AgentResponseUpdate> fromUpdate(
      AgentResponseUpdate update, CancellationSignal cancellationSignal) {
    Objects.requireNonNull(update, "update must not be null");
    CancellationSignal signal =
        cancellationSignal == null ? new CancellationSignal() : cancellationSignal;
    CompletableFuture<AgentResponse> response = new CompletableFuture<>();
    FinalizingPublisher publisher =
        new FinalizingPublisher(new SingleValuePublisher<>(update), response, signal);
    return new AgentStreamingRun<>(
        publisher,
        response.minimalCompletionStage(),
        signal,
        () -> {
          signal.cancel();
          publisher.cancel();
        });
  }

  public static AgentStreamingRun<AgentResponseUpdate> fromUpdates(
      Flow.Publisher<AgentResponseUpdate> updates, CancellationSignal cancellationSignal) {
    Objects.requireNonNull(updates, "updates must not be null");
    CancellationSignal signal =
        cancellationSignal == null ? new CancellationSignal() : cancellationSignal;
    CompletableFuture<AgentResponse> response = new CompletableFuture<>();
    FinalizingPublisher publisher = new FinalizingPublisher(updates, response, signal);
    return new AgentStreamingRun<>(
        publisher,
        response.minimalCompletionStage(),
        signal,
        () -> {
          signal.cancel();
          publisher.cancel();
        });
  }

  public Flow.Publisher<T> updates() {
    return updates;
  }

  public CompletionStage<AgentResponse> response() {
    return response;
  }

  public void cancel() {
    cancellationAction.run();
  }

  public <R> AgentStreamingRun<R> mapUpdates(Function<? super T, ? extends R> mapper) {
    Objects.requireNonNull(mapper, "mapper must not be null");
    return new AgentStreamingRun<>(
        new MappingPublisher<>(updates, mapper), response, cancellationSignal, cancellationAction);
  }

  private static final class FinalizingPublisher implements Flow.Publisher<AgentResponseUpdate> {
    private final Flow.Publisher<AgentResponseUpdate> source;
    private final CompletableFuture<AgentResponse> response;
    private final CancellationSignal cancellationSignal;
    private final AtomicBoolean subscribed = new AtomicBoolean();
    private final AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
    private final AtomicReference<StreamState> state = new AtomicReference<>(StreamState.ACTIVE);

    private FinalizingPublisher(
        Flow.Publisher<AgentResponseUpdate> source,
        CompletableFuture<AgentResponse> response,
        CancellationSignal cancellationSignal) {
      this.source = source;
      this.response = response;
      this.cancellationSignal = cancellationSignal;
    }

    private boolean cancel() {
      if (state.compareAndSet(StreamState.ACTIVE, StreamState.CANCELLED)) {
        Flow.Subscription activeSubscription = subscription.get();
        if (activeSubscription != null) {
          activeSubscription.cancel();
        }
        return true;
      }
      return false;
    }

    @Override
    public void subscribe(Flow.Subscriber<? super AgentResponseUpdate> subscriber) {
      Objects.requireNonNull(subscriber, "subscriber must not be null");
      if (!subscribed.compareAndSet(false, true)) {
        subscriber.onSubscribe(EmptySubscription.INSTANCE);
        subscriber.onError(new IllegalStateException("updates can only be consumed once"));
        return;
      }
      if (cancellationSignal.isCancelled()) {
        subscriber.onSubscribe(EmptySubscription.INSTANCE);
        return;
      }

      List<AgentResponseUpdate> bufferedUpdates = new ArrayList<>();
      source.subscribe(
          new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription upstreamSubscription) {
              subscription.set(upstreamSubscription);
              if (state.get() != StreamState.ACTIVE) {
                upstreamSubscription.cancel();
                subscriber.onSubscribe(EmptySubscription.INSTANCE);
                subscriber.onComplete();
                return;
              }
              subscriber.onSubscribe(
                  new Flow.Subscription() {
                    @Override
                    public void request(long n) {
                      upstreamSubscription.request(n);
                    }

                    @Override
                    public void cancel() {
                      if (FinalizingPublisher.this.cancel()) {
                        cancellationSignal.cancel();
                      }
                    }
                  });
            }

            @Override
            public void onNext(AgentResponseUpdate item) {
              if (state.get() != StreamState.ACTIVE) {
                return;
              }
              bufferedUpdates.add(Objects.requireNonNull(item, "updates must not contain null"));
              if (state.get() != StreamState.ACTIVE) {
                bufferedUpdates.remove(bufferedUpdates.size() - 1);
                return;
              }
              subscriber.onNext(item);
            }

            @Override
            public void onError(Throwable throwable) {
              if (!state.compareAndSet(StreamState.ACTIVE, StreamState.TERMINATED)) {
                return;
              }
              response.completeExceptionally(throwable);
              subscriber.onError(throwable);
            }

            @Override
            public void onComplete() {
              if (!state.compareAndSet(StreamState.ACTIVE, StreamState.TERMINATED)) {
                return;
              }
              AgentResponse assembledResponse;
              try {
                assembledResponse = AgentResponse.fromUpdates(bufferedUpdates);
              } catch (RuntimeException exception) {
                response.completeExceptionally(exception);
                subscriber.onError(exception);
                return;
              }
              response.complete(assembledResponse);
              subscriber.onComplete();
            }
          });
    }
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

  private enum EmptySubscription implements Flow.Subscription {
    INSTANCE;

    @Override
    public void request(long n) {}

    @Override
    public void cancel() {}
  }

  private enum StreamState {
    ACTIVE,
    CANCELLED,
    TERMINATED
  }
}
