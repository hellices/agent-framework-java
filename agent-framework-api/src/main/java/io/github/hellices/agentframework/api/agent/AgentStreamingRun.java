package io.github.hellices.agentframework.api.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

public final class AgentStreamingRun<T> {

  private final Flow.Publisher<T> updates;
  private final CompletionStage<AgentResponse> response;
  private final CancellationSignal cancellationSignal;
  private final Runnable cancellationAction;
  private final Predicate<Throwable> failureAction;

  public AgentStreamingRun(
      Flow.Publisher<T> updates,
      CompletionStage<AgentResponse> response,
      CancellationSignal cancellationSignal) {
    this.updates = Objects.requireNonNull(updates, "updates must not be null");
    this.response = Objects.requireNonNull(response, "response must not be null");
    this.cancellationSignal =
        cancellationSignal == null ? new CancellationSignal() : cancellationSignal;
    this.cancellationAction = this.cancellationSignal::cancel;
    this.failureAction = ignored -> false;
  }

  private AgentStreamingRun(
      Flow.Publisher<T> updates,
      CompletionStage<AgentResponse> response,
      CancellationSignal cancellationSignal,
      Runnable cancellationAction,
      Predicate<Throwable> failureAction) {
    this.updates = Objects.requireNonNull(updates, "updates must not be null");
    this.response = Objects.requireNonNull(response, "response must not be null");
    this.cancellationSignal =
        cancellationSignal == null ? new CancellationSignal() : cancellationSignal;
    this.cancellationAction =
        Objects.requireNonNull(cancellationAction, "cancellationAction must not be null");
    this.failureAction = Objects.requireNonNull(failureAction, "failureAction must not be null");
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
        new FinalizingPublisher(new SingleValuePublisher<>(update), response, signal, null);
    return new AgentStreamingRun<>(
        publisher,
        response.minimalCompletionStage(),
        signal,
        () -> {
          signal.cancel();
          publisher.cancel(false);
        },
        publisher::fail);
  }

  public static AgentStreamingRun<AgentResponseUpdate> fromUpdates(
      Flow.Publisher<AgentResponseUpdate> updates, CancellationSignal cancellationSignal) {
    Objects.requireNonNull(updates, "updates must not be null");
    CancellationSignal signal =
        cancellationSignal == null ? new CancellationSignal() : cancellationSignal;
    CompletableFuture<AgentResponse> response = new CompletableFuture<>();
    FinalizingPublisher publisher = new FinalizingPublisher(updates, response, signal, null);
    return new AgentStreamingRun<>(
        publisher,
        response.minimalCompletionStage(),
        signal,
        () -> {
          signal.cancel();
          publisher.cancel(false);
        },
        publisher::fail);
  }

  public static AgentStreamingRun<AgentResponseUpdate> fromUpdates(
      Flow.Publisher<AgentResponseUpdate> updates,
      CancellationSignal cancellationSignal,
      Supplier<AgentResponse> emptyResponseSupplier) {
    Objects.requireNonNull(updates, "updates must not be null");
    CancellationSignal signal =
        cancellationSignal == null ? new CancellationSignal() : cancellationSignal;
    CompletableFuture<AgentResponse> response = new CompletableFuture<>();
    FinalizingPublisher publisher =
        new FinalizingPublisher(
            updates,
            response,
            signal,
            Objects.requireNonNull(
                emptyResponseSupplier, "emptyResponseSupplier must not be null"));
    return new AgentStreamingRun<>(
        publisher,
        response.minimalCompletionStage(),
        signal,
        () -> {
          signal.cancel();
          publisher.cancel(false);
        },
        publisher::fail);
  }

  public Flow.Publisher<T> updates() {
    return updates;
  }

  /**
   * Returns the run's authoritative outcome. It completes only after every lifecycle step of the
   * run finished, so it can still fail after {@link #updates()} completed normally: the update
   * stream signals model transport completion, while post-stream work (for example a context
   * provider's {@code afterRun} hook) is reported here.
   */
  public CompletionStage<AgentResponse> response() {
    return response;
  }

  public void cancel() {
    cancellationAction.run();
  }

  public <R> AgentStreamingRun<R> mapUpdates(Function<? super T, ? extends R> mapper) {
    Objects.requireNonNull(mapper, "mapper must not be null");
    return new AgentStreamingRun<>(
        new MappingPublisher<>(updates, mapper, failureAction),
        response,
        cancellationSignal,
        cancellationAction,
        failureAction);
  }

  /**
   * Package-private copy used by {@link Agent} to observe run completion without losing the
   * original cancellation wiring. The returned run's exposed response stage is derived via {@link
   * CompletionStage#whenComplete(BiConsumer)}, so joining it can only return once {@code
   * completion} has finished running: any exception {@code completion} throws (including {@code
   * SessionContext} lifecycle violations such as a pre-filled or double-completed response slot)
   * propagates through the returned run's response stage instead of being swallowed. The original
   * {@code cancellationAction} and {@code failureAction} are preserved unchanged, so {@link
   * #cancel()} and update-mapping failure handling keep working exactly as before.
   */
  AgentStreamingRun<T> withCompletion(
      BiConsumer<? super AgentResponse, ? super Throwable> completion) {
    Objects.requireNonNull(completion, "completion must not be null");
    return withResponse(response.whenComplete(completion));
  }

  /**
   * Package-private copy used by {@link Agent} to expose a response stage it derived from this
   * run's own stage (session context completion followed by the {@code afterRun} lifecycle seam),
   * while keeping the original update publisher, cancellation wiring, and failure action. The
   * updates publisher is untouched, so update delivery keeps its existing ordering; only the
   * caller-visible response stage waits for the lifecycle steps.
   */
  AgentStreamingRun<T> withResponse(CompletionStage<AgentResponse> derivedResponse) {
    return new AgentStreamingRun<>(
        updates,
        Objects.requireNonNull(derivedResponse, "response must not be null"),
        cancellationSignal,
        cancellationAction,
        failureAction);
  }

  private static final class FinalizingPublisher implements Flow.Publisher<AgentResponseUpdate> {
    private final Flow.Publisher<AgentResponseUpdate> source;
    private final CompletableFuture<AgentResponse> response;
    private final CancellationSignal cancellationSignal;
    private final Supplier<AgentResponse> emptyResponseSupplier;
    private final AtomicBoolean subscribed = new AtomicBoolean();
    private final AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
    private final AtomicReference<StreamState> state = new AtomicReference<>(StreamState.ACTIVE);
    private final AtomicReference<Flow.Subscriber<? super AgentResponseUpdate>> downstream =
        new AtomicReference<>();
    private final AtomicReference<CancellationException> cancellationFailure =
        new AtomicReference<>();
    private final AtomicBoolean downstreamSubscribed = new AtomicBoolean();
    private final AtomicBoolean cancellationNotificationRequested = new AtomicBoolean();
    private final AtomicBoolean cancellationTerminalSent = new AtomicBoolean();
    private final ConcurrentLinkedQueue<StreamSignal> signals = new ConcurrentLinkedQueue<>();
    private final AtomicInteger drainWork = new AtomicInteger();
    private final ReentrantLock signalLock = new ReentrantLock();
    private final AtomicReference<Runnable> removeCancellationListener =
        new AtomicReference<>(() -> {});

    private FinalizingPublisher(
        Flow.Publisher<AgentResponseUpdate> source,
        CompletableFuture<AgentResponse> response,
        CancellationSignal cancellationSignal,
        Supplier<AgentResponse> emptyResponseSupplier) {
      this.source = source;
      this.response = response;
      this.cancellationSignal = cancellationSignal;
      this.emptyResponseSupplier = emptyResponseSupplier;
      this.removeCancellationListener.set(cancellationSignal.onCancel(() -> cancel(true)));
    }

    private boolean cancel(boolean notifyDownstream) {
      CancellationException failure;
      signalLock.lock();
      try {
        if (!state.compareAndSet(StreamState.ACTIVE, StreamState.CANCELLED)) {
          return false;
        }
        failure = new CancellationException("run was cancelled");
        cancellationFailure.set(failure);
        if (notifyDownstream) {
          cancellationNotificationRequested.set(true);
          enqueueCancellationIfReady();
        }
      } finally {
        signalLock.unlock();
      }
      unregisterCancellationListener();
      response.completeExceptionally(failure);
      Flow.Subscription activeSubscription = subscription.get();
      if (activeSubscription != null) {
        try {
          activeSubscription.cancel();
        } catch (RuntimeException cleanupFailure) {
          failure.addSuppressed(cleanupFailure);
        }
      }
      drainSignals();
      return true;
    }

    private void notifyCancellation() {
      signalLock.lock();
      try {
        enqueueCancellationIfReady();
      } finally {
        signalLock.unlock();
      }
      drainSignals();
    }

    private void enqueueCancellationIfReady() {
      CancellationException failure = cancellationFailure.get();
      if (downstream.get() != null
          && failure != null
          && downstreamSubscribed.get()
          && cancellationNotificationRequested.get()
          && cancellationTerminalSent.compareAndSet(false, true)) {
        signals.add(new ErrorSignal(failure));
      }
    }

    private void recordCancellationFailure(RuntimeException failure) {
      CancellationException cancellation = cancellationFailure.get();
      if (cancellation != null) {
        cancellation.addSuppressed(failure);
      }
    }

    private boolean fail(Throwable failure) {
      Throwable value = Objects.requireNonNull(failure, "failure must not be null");
      if (!state.compareAndSet(StreamState.ACTIVE, StreamState.TERMINATED)) {
        return false;
      }
      unregisterCancellationListener();
      response.completeExceptionally(value);
      try {
        cancellationSignal.cancel();
      } catch (RuntimeException cleanupFailure) {
        value.addSuppressed(cleanupFailure);
      }
      Flow.Subscription activeSubscription = subscription.get();
      if (activeSubscription != null) {
        try {
          activeSubscription.cancel();
        } catch (RuntimeException cleanupFailure) {
          value.addSuppressed(cleanupFailure);
        }
      }
      return true;
    }

    private void emitNext(AgentResponseUpdate item, List<AgentResponseUpdate> bufferedUpdates) {
      signalLock.lock();
      try {
        if (state.get() != StreamState.ACTIVE) {
          return;
        }
        AgentResponseUpdate value = Objects.requireNonNull(item, "updates must not contain null");
        bufferedUpdates.add(value);
        signals.add(new NextSignal(value));
      } finally {
        signalLock.unlock();
      }
      drainSignals();
    }

    private void emitError(Throwable failure) {
      signalLock.lock();
      try {
        if (!state.compareAndSet(StreamState.ACTIVE, StreamState.TERMINATED)) {
          return;
        }
        signals.add(new ErrorSignal(failure));
      } finally {
        signalLock.unlock();
      }
      unregisterCancellationListener();
      response.completeExceptionally(failure);
      drainSignals();
    }

    private void emitComplete(List<AgentResponseUpdate> bufferedUpdates) {
      signalLock.lock();
      try {
        if (!state.compareAndSet(StreamState.ACTIVE, StreamState.TERMINATED)) {
          return;
        }
      } finally {
        signalLock.unlock();
      }
      unregisterCancellationListener();
      StreamSignal terminalSignal;
      try {
        AgentResponse assembledResponse =
            bufferedUpdates.isEmpty() && emptyResponseSupplier != null
                ? Objects.requireNonNull(
                    emptyResponseSupplier.get(), "emptyResponseSupplier must not return null")
                : AgentResponse.fromUpdates(bufferedUpdates);
        response.complete(assembledResponse);
        terminalSignal = CompleteSignal.INSTANCE;
      } catch (RuntimeException failure) {
        response.completeExceptionally(failure);
        terminalSignal = new ErrorSignal(failure);
      }
      signalLock.lock();
      try {
        signals.add(terminalSignal);
      } finally {
        signalLock.unlock();
      }
      drainSignals();
    }

    private void unregisterCancellationListener() {
      removeCancellationListener.getAndSet(() -> {}).run();
    }

    private void drainSignals() {
      if (drainWork.getAndIncrement() != 0) {
        return;
      }
      int missed = 1;
      try {
        while (true) {
          StreamSignal signal;
          while ((signal = signals.poll()) != null) {
            Flow.Subscriber<? super AgentResponseUpdate> subscriber = downstream.get();
            if (subscriber == null) {
              continue;
            }
            if (signal instanceof NextSignal next) {
              subscriber.onNext(next.update());
            } else if (signal instanceof ErrorSignal error) {
              subscriber.onError(error.failure());
              signals.clear();
              return;
            } else {
              subscriber.onComplete();
              signals.clear();
              return;
            }
          }
          missed = drainWork.addAndGet(-missed);
          if (missed == 0) {
            return;
          }
        }
      } catch (RuntimeException failure) {
        signals.clear();
        drainWork.set(0);
        throw failure;
      }
    }

    @Override
    public void subscribe(Flow.Subscriber<? super AgentResponseUpdate> subscriber) {
      Objects.requireNonNull(subscriber, "subscriber must not be null");
      if (!subscribed.compareAndSet(false, true)) {
        subscriber.onSubscribe(EmptySubscription.INSTANCE);
        subscriber.onError(new IllegalStateException("updates can only be consumed once"));
        return;
      }
      downstream.set(subscriber);
      if (cancellationSignal.isCancelled()) {
        subscriber.onSubscribe(EmptySubscription.INSTANCE);
        downstreamSubscribed.set(true);
        notifyCancellation();
        return;
      }

      List<AgentResponseUpdate> bufferedUpdates = new ArrayList<>();
      source.subscribe(
          new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription upstreamSubscription) {
              subscription.set(upstreamSubscription);
              if (state.get() != StreamState.ACTIVE) {
                try {
                  upstreamSubscription.cancel();
                } catch (RuntimeException cleanupFailure) {
                  recordCancellationFailure(cleanupFailure);
                }
                subscriber.onSubscribe(EmptySubscription.INSTANCE);
                downstreamSubscribed.set(true);
                notifyCancellation();
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
                      if (FinalizingPublisher.this.cancel(false)) {
                        try {
                          cancellationSignal.cancel();
                        } catch (RuntimeException cleanupFailure) {
                          recordCancellationFailure(cleanupFailure);
                        }
                      }
                    }
                  });
              downstreamSubscribed.set(true);
              if (state.get() == StreamState.CANCELLED) {
                notifyCancellation();
              }
            }

            @Override
            public void onNext(AgentResponseUpdate item) {
              emitNext(item, bufferedUpdates);
            }

            @Override
            public void onError(Throwable throwable) {
              emitError(throwable);
            }

            @Override
            public void onComplete() {
              emitComplete(bufferedUpdates);
            }
          });
    }

    private interface StreamSignal {}

    private record NextSignal(AgentResponseUpdate update) implements StreamSignal {}

    private record ErrorSignal(Throwable failure) implements StreamSignal {}

    private enum CompleteSignal implements StreamSignal {
      INSTANCE
    }
  }

  private static final class MappingPublisher<T, R> implements Flow.Publisher<R> {
    private final Flow.Publisher<T> source;
    private final Function<? super T, ? extends R> mapper;
    private final Predicate<Throwable> failureAction;

    private MappingPublisher(
        Flow.Publisher<T> source,
        Function<? super T, ? extends R> mapper,
        Predicate<Throwable> failureAction) {
      this.source = source;
      this.mapper = mapper;
      this.failureAction = failureAction;
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
                if (!failureAction.test(exception)) {
                  subscription.cancel();
                }
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
