package io.github.hellices.agentframework.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hellices.agentframework.api.message.FinishReason;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.Role;
import io.github.hellices.agentframework.api.message.TextContent;
import io.github.hellices.agentframework.api.value.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AgentStreamingRunTest {

  @Test
  void consumingUpdatesCompletesTheReconstructedResponse() {
    AgentStreamingRun<AgentResponseUpdate> run =
        AgentStreamingRun.fromUpdates(
            new IterablePublisher<>(List.of(update("message-1", "hel"), update("message-1", "lo"))),
            new CancellationSignal());

    assertThat(consume(run.updates())).hasSize(2);
    assertThat(run.response().toCompletableFuture().join().text()).isEqualTo("hello");
  }

  @Test
  void mappedUpdatesKeepTheOriginalFinalResponse() {
    CancellationSignal signal = new CancellationSignal();
    AgentStreamingRun<AgentResponseUpdate> run =
        AgentStreamingRun.fromUpdates(
            new IterablePublisher<>(List.of(update("message-1", "hel"), update("message-1", "lo"))),
            signal);

    AgentStreamingRun<String> mapped = run.mapUpdates(AgentResponseUpdate::text);

    assertThat(consume(mapped.updates())).containsExactly("hel", "lo");
    assertThat(mapped.response().toCompletableFuture().join().text()).isEqualTo("hello");
    mapped.cancel();
    assertThat(signal.isCancelled()).isTrue();
  }

  @Test
  void cancellingTheUpdateSubscriptionCancelsTheRunWithoutFinalizing() {
    CancellationSignal signal = new CancellationSignal();
    AgentStreamingRun<AgentResponseUpdate> run =
        AgentStreamingRun.fromUpdates(
            new IterablePublisher<>(List.of(update("message-1", "partial"))), signal);

    run.updates()
        .subscribe(
            new Flow.Subscriber<>() {
              @Override
              public void onSubscribe(Flow.Subscription subscription) {
                subscription.cancel();
              }

              @Override
              public void onNext(AgentResponseUpdate item) {}

              @Override
              public void onError(Throwable throwable) {}

              @Override
              public void onComplete() {}
            });

    assertThat(signal.isCancelled()).isTrue();
    assertThat(run.response().toCompletableFuture()).isCompletedExceptionally();
  }

  @Test
  void cancellingTheRunPreventsFinalization() {
    CancellationSignal signal = new CancellationSignal();
    AgentStreamingRun<AgentResponseUpdate> run =
        AgentStreamingRun.fromUpdate(update("message-1", "partial"), signal);

    run.cancel();
    List<String> signals = new ArrayList<>();
    run.updates()
        .subscribe(
            new Flow.Subscriber<>() {
              @Override
              public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
              }

              @Override
              public void onNext(AgentResponseUpdate item) {
                signals.add("onNext");
              }

              @Override
              public void onError(Throwable throwable) {
                signals.add("onError");
              }

              @Override
              public void onComplete() {
                signals.add("onComplete");
              }
            });

    assertThat(signal.isCancelled()).isTrue();
    assertThat(signals).containsExactly("onError");
    assertThat(run.response().toCompletableFuture()).isCompletedExceptionally();
  }

  @Test
  void mapperFailureCancelsTheSourceAndSignalsTheError() {
    CancellationSignal signal = new CancellationSignal();
    AgentStreamingRun<AgentResponseUpdate> run =
        AgentStreamingRun.fromUpdates(
            new IterablePublisher<>(List.of(update("message-1", "value"))), signal);
    AgentStreamingRun<String> mapped =
        run.mapUpdates(
            ignored -> {
              throw new IllegalStateException("mapping failed");
            });

    assertThatThrownBy(() -> consume(mapped.updates()))
        .isInstanceOf(CompletionException.class)
        .hasCauseInstanceOf(IllegalStateException.class)
        .hasMessageContaining("mapping failed");
    assertThat(signal.isCancelled()).isTrue();
    assertThatThrownBy(() -> run.response().toCompletableFuture().join())
        .isInstanceOf(CompletionException.class)
        .hasCauseInstanceOf(IllegalStateException.class)
        .hasMessageContaining("mapping failed");
  }

  @Test
  void subscriberFailureAfterCompletionDoesNotReceiveAnotherTerminalSignal() {
    AgentStreamingRun<AgentResponseUpdate> run =
        AgentStreamingRun.fromUpdates(
            new IterablePublisher<>(List.of(update("message-1", "complete"))),
            new CancellationSignal());
    List<String> signals = new ArrayList<>();

    assertThatThrownBy(
            () ->
                run.updates()
                    .subscribe(
                        new Flow.Subscriber<>() {
                          @Override
                          public void onSubscribe(Flow.Subscription subscription) {
                            subscription.request(Long.MAX_VALUE);
                          }

                          @Override
                          public void onNext(AgentResponseUpdate item) {
                            signals.add("onNext");
                          }

                          @Override
                          public void onError(Throwable throwable) {
                            signals.add("onError");
                          }

                          @Override
                          public void onComplete() {
                            signals.add("onComplete");
                            throw new IllegalStateException("subscriber failed");
                          }
                        }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("subscriber failed");
    assertThat(signals).containsExactly("onNext", "onComplete");
    assertThat(run.response().toCompletableFuture().join().text()).isEqualTo("complete");
  }

  @Test
  void singleUpdateFactoryKeepsSubscriptionAndCancellationContract() {
    CancellationSignal signal = new CancellationSignal();
    AgentStreamingRun<AgentResponseUpdate> run =
        AgentStreamingRun.fromUpdate(update("message-1", "value"), signal);
    List<Throwable> secondSubscriptionErrors = new ArrayList<>();

    run.updates()
        .subscribe(
            new Flow.Subscriber<>() {
              @Override
              public void onSubscribe(Flow.Subscription subscription) {
                subscription.cancel();
              }

              @Override
              public void onNext(AgentResponseUpdate item) {}

              @Override
              public void onError(Throwable throwable) {}

              @Override
              public void onComplete() {}
            });
    run.updates()
        .subscribe(
            new Flow.Subscriber<>() {
              @Override
              public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
              }

              @Override
              public void onNext(AgentResponseUpdate item) {}

              @Override
              public void onError(Throwable throwable) {
                secondSubscriptionErrors.add(throwable);
              }

              @Override
              public void onComplete() {}
            });

    assertThat(signal.isCancelled()).isTrue();
    assertThat(secondSubscriptionErrors).hasSize(1);
    assertThat(secondSubscriptionErrors.get(0))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("updates can only be consumed once");
  }

  @Test
  void singleUpdatePublisherRejectsNonPositiveDemand() {
    AgentStreamingRun<AgentResponseUpdate> run =
        AgentStreamingRun.fromUpdate(update("message-1", "value"));
    List<Throwable> errors = new ArrayList<>();

    run.updates()
        .subscribe(
            new Flow.Subscriber<>() {
              @Override
              public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(0);
              }

              @Override
              public void onNext(AgentResponseUpdate item) {}

              @Override
              public void onError(Throwable throwable) {
                errors.add(throwable);
              }

              @Override
              public void onComplete() {}
            });

    assertThat(errors).hasSize(1);
    assertThat(errors.get(0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("demand must be greater than zero");
    assertThat(run.response().toCompletableFuture()).isCompletedExceptionally();
  }

  @Test
  void signalsArrivingAfterCancellationAreNotForwardedOrFinalized() {
    CancellationSignal signal = new CancellationSignal();
    AgentStreamingRun<AgentResponseUpdate> run =
        AgentStreamingRun.fromUpdates(
            subscriber ->
                subscriber.onSubscribe(
                    new Flow.Subscription() {
                      @Override
                      public void request(long n) {
                        subscriber.onNext(update("message-1", "partial"));
                        subscriber.onNext(update("message-1", " ignored"));
                        subscriber.onComplete();
                      }

                      @Override
                      public void cancel() {}
                    }),
            signal);
    List<String> downstreamSignals = new ArrayList<>();

    run.updates()
        .subscribe(
            new Flow.Subscriber<>() {
              private Flow.Subscription subscription;

              @Override
              public void onSubscribe(Flow.Subscription subscription) {
                this.subscription = subscription;
                subscription.request(Long.MAX_VALUE);
              }

              @Override
              public void onNext(AgentResponseUpdate item) {
                downstreamSignals.add(item.text());
                subscription.cancel();
              }

              @Override
              public void onError(Throwable throwable) {
                downstreamSignals.add("onError");
              }

              @Override
              public void onComplete() {
                downstreamSignals.add("onComplete");
              }
            });

    assertThat(signal.isCancelled()).isTrue();
    assertThat(downstreamSignals).containsExactly("partial");
    assertThat(run.response().toCompletableFuture()).isCompletedExceptionally();
  }

  @Test
  void cancellationDoesNotWaitForABlockedDownstreamCallback() throws Exception {
    AtomicReference<Flow.Subscriber<? super AgentResponseUpdate>> sourceSubscriber =
        new AtomicReference<>();
    AgentStreamingRun<AgentResponseUpdate> run =
        AgentStreamingRun.fromUpdates(sourceSubscriber::set, new CancellationSignal());
    CountDownLatch callbackStarted = new CountDownLatch(1);
    CountDownLatch releaseCallback = new CountDownLatch(1);
    CountDownLatch cancellationReturned = new CountDownLatch(1);
    AtomicBoolean inOnNext = new AtomicBoolean();
    AtomicBoolean terminalWasConcurrent = new AtomicBoolean();
    CountDownLatch terminalReceived = new CountDownLatch(1);
    run.updates()
        .subscribe(
            new Flow.Subscriber<>() {
              @Override
              public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
              }

              @Override
              public void onNext(AgentResponseUpdate item) {
                inOnNext.set(true);
                callbackStarted.countDown();
                try {
                  releaseCallback.await();
                } catch (InterruptedException exception) {
                  Thread.currentThread().interrupt();
                  throw new IllegalStateException(exception);
                } finally {
                  inOnNext.set(false);
                }
              }

              @Override
              public void onError(Throwable throwable) {
                terminalWasConcurrent.set(inOnNext.get());
                terminalReceived.countDown();
              }

              @Override
              public void onComplete() {}
            });
    Flow.Subscriber<? super AgentResponseUpdate> subscriber = sourceSubscriber.get();
    subscriber.onSubscribe(
        new Flow.Subscription() {
          @Override
          public void request(long n) {}

          @Override
          public void cancel() {}
        });
    CompletableFuture<Void> producer =
        CompletableFuture.runAsync(() -> subscriber.onNext(update("message-1", "value")));
    assertThat(callbackStarted.await(1, TimeUnit.SECONDS)).isTrue();

    CompletableFuture.runAsync(
        () -> {
          run.cancel();
          cancellationReturned.countDown();
        });
    try {
      assertThat(cancellationReturned.await(1, TimeUnit.SECONDS)).isTrue();
    } finally {
      releaseCallback.countDown();
      producer.join();
    }
    assertThat(terminalReceived.await(1, TimeUnit.SECONDS)).isTrue();
    assertThat(terminalWasConcurrent).isFalse();
  }

  @Test
  void mappedDownstreamFailureDoesNotCancelOrReceiveAnotherSignal() {
    CancellationSignal signal = new CancellationSignal();
    AgentStreamingRun<String> mapped =
        AgentStreamingRun.fromUpdates(
                new IterablePublisher<>(List.of(update("message-1", "value"))), signal)
            .mapUpdates(AgentResponseUpdate::text);
    List<String> signals = new ArrayList<>();

    assertThatThrownBy(
            () ->
                mapped
                    .updates()
                    .subscribe(
                        new Flow.Subscriber<>() {
                          @Override
                          public void onSubscribe(Flow.Subscription subscription) {
                            subscription.request(Long.MAX_VALUE);
                          }

                          @Override
                          public void onNext(String item) {
                            signals.add("onNext");
                            throw new IllegalStateException("downstream failed");
                          }

                          @Override
                          public void onError(Throwable throwable) {
                            signals.add("onError");
                          }

                          @Override
                          public void onComplete() {
                            signals.add("onComplete");
                          }
                        }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("downstream failed");
    assertThat(signal.isCancelled()).isFalse();
    assertThat(signals).containsExactly("onNext");
  }

  @Test
  void externalResponseFutureCannotPreemptStreamFinalization() {
    AgentStreamingRun<AgentResponseUpdate> run =
        AgentStreamingRun.fromUpdates(
            new IterablePublisher<>(List.of(update("message-1", "final"))),
            new CancellationSignal());

    run.response()
        .toCompletableFuture()
        .completeExceptionally(new IllegalStateException("external"));

    consume(run.updates());
    assertThat(run.response().toCompletableFuture().join().text()).isEqualTo("final");
  }

  @Test
  void subscriptionCancellationAfterCompletionIsANoOp() {
    CancellationSignal signal = new CancellationSignal();
    AgentStreamingRun<AgentResponseUpdate> run =
        AgentStreamingRun.fromUpdates(
            new IterablePublisher<>(List.of(update("message-1", "done"))), signal);
    AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();

    run.updates()
        .subscribe(
            new Flow.Subscriber<>() {
              @Override
              public void onSubscribe(Flow.Subscription value) {
                subscription.set(value);
                value.request(Long.MAX_VALUE);
              }

              @Override
              public void onNext(AgentResponseUpdate item) {}

              @Override
              public void onError(Throwable throwable) {}

              @Override
              public void onComplete() {}
            });
    subscription.get().cancel();

    assertThat(signal.isCancelled()).isFalse();
    assertThat(run.response().toCompletableFuture().join().text()).isEqualTo("done");
  }

  @Test
  void externalSignalCancellationCompletesTheResponseExceptionally() {
    CancellationSignal signal = new CancellationSignal();
    AtomicBoolean upstreamCancelled = new AtomicBoolean();
    List<Throwable> downstreamErrors = new ArrayList<>();
    AgentStreamingRun<AgentResponseUpdate> run =
        AgentStreamingRun.fromUpdates(
            subscriber ->
                subscriber.onSubscribe(
                    new Flow.Subscription() {
                      @Override
                      public void request(long n) {}

                      @Override
                      public void cancel() {
                        upstreamCancelled.set(true);
                      }
                    }),
            signal);
    run.updates()
        .subscribe(
            new Flow.Subscriber<>() {
              @Override
              public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
              }

              @Override
              public void onNext(AgentResponseUpdate item) {}

              @Override
              public void onError(Throwable throwable) {
                downstreamErrors.add(throwable);
              }

              @Override
              public void onComplete() {}
            });

    signal.cancel();

    assertThat(upstreamCancelled).isTrue();
    assertThat(downstreamErrors).singleElement().isInstanceOf(CancellationException.class);
    assertThat(run.response().toCompletableFuture()).isCompletedExceptionally();
  }

  @Test
  void subscriptionCancellationDoesNotThrowWhenUpstreamCancellationFails() {
    AgentStreamingRun<AgentResponseUpdate> run =
        AgentStreamingRun.fromUpdates(
            subscriber ->
                subscriber.onSubscribe(
                    new Flow.Subscription() {
                      @Override
                      public void request(long n) {}

                      @Override
                      public void cancel() {
                        throw new IllegalStateException("upstream cancel failed");
                      }
                    }),
            new CancellationSignal());
    AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
    run.updates()
        .subscribe(
            new Flow.Subscriber<>() {
              @Override
              public void onSubscribe(Flow.Subscription value) {
                subscription.set(value);
              }

              @Override
              public void onNext(AgentResponseUpdate item) {}

              @Override
              public void onError(Throwable throwable) {}

              @Override
              public void onComplete() {}
            });

    assertThatCode(() -> subscription.get().cancel()).doesNotThrowAnyException();
    assertThat(run.response().toCompletableFuture()).isCompletedExceptionally();
  }

  @Test
  void cancellationBeforeSubscriptionDoesNotTouchTheSource() {
    int[] subscriptions = {0};
    AgentStreamingRun<AgentResponseUpdate> run =
        AgentStreamingRun.fromUpdates(subscriber -> subscriptions[0]++, new CancellationSignal());
    List<String> downstreamSignals = new ArrayList<>();

    run.cancel();
    run.updates()
        .subscribe(
            new Flow.Subscriber<>() {
              @Override
              public void onSubscribe(Flow.Subscription subscription) {
                downstreamSignals.add("onSubscribe");
              }

              @Override
              public void onNext(AgentResponseUpdate item) {
                downstreamSignals.add("onNext");
              }

              @Override
              public void onError(Throwable throwable) {
                downstreamSignals.add("onError");
              }

              @Override
              public void onComplete() {
                downstreamSignals.add("onComplete");
              }
            });

    assertThat(subscriptions[0]).isZero();
    assertThat(downstreamSignals).containsExactly("onSubscribe", "onError");
    assertThat(run.response().toCompletableFuture()).isCompletedExceptionally();
  }

  @Test
  void factoryAcceptsAnAlreadyCancelledSignal() {
    CancellationSignal signal = new CancellationSignal();
    signal.cancel();

    AgentStreamingRun<AgentResponseUpdate> run =
        AgentStreamingRun.fromUpdate(update("message-1", "unused"), signal);
    List<String> downstreamSignals = new ArrayList<>();
    run.updates()
        .subscribe(
            new Flow.Subscriber<>() {
              @Override
              public void onSubscribe(Flow.Subscription subscription) {
                downstreamSignals.add("onSubscribe");
              }

              @Override
              public void onNext(AgentResponseUpdate item) {
                downstreamSignals.add("onNext");
              }

              @Override
              public void onError(Throwable throwable) {
                downstreamSignals.add("onError");
              }

              @Override
              public void onComplete() {
                downstreamSignals.add("onComplete");
              }
            });

    assertThat(downstreamSignals).containsExactly("onSubscribe", "onError");
    assertThat(run.response().toCompletableFuture()).isCompletedExceptionally();
  }

  @Test
  void responseContinuationDoesNotBlockCancellation() throws Exception {
    AgentStreamingRun<AgentResponseUpdate> run =
        AgentStreamingRun.fromUpdate(update("message-1", "done"));
    CountDownLatch continuationStarted = new CountDownLatch(1);
    CountDownLatch releaseContinuation = new CountDownLatch(1);
    CountDownLatch cancellationReturned = new CountDownLatch(1);
    run.response()
        .thenRun(
            () -> {
              continuationStarted.countDown();
              try {
                releaseContinuation.await();
              } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
              }
            });
    CompletableFuture<Void> producer = CompletableFuture.runAsync(() -> consume(run.updates()));
    assertThat(continuationStarted.await(1, TimeUnit.SECONDS)).isTrue();

    CompletableFuture.runAsync(
        () -> {
          run.cancel();
          cancellationReturned.countDown();
        });
    try {
      assertThat(cancellationReturned.await(1, TimeUnit.SECONDS)).isTrue();
    } finally {
      releaseContinuation.countDown();
      producer.join();
    }
  }

  private static AgentResponseUpdate update(String messageId, String text) {
    return AgentResponseUpdate.builder()
        .agentId("agent-1")
        .responseId("response-1")
        .messageId(messageId)
        .authorName("assistant")
        .finishReason(FinishReason.STOP)
        .messages(List.of(new Message(Role.ASSISTANT, List.of(new TextContent(text)))))
        .additionalProperties(JsonObject.empty())
        .build();
  }

  private static <T> List<T> consume(Flow.Publisher<T> publisher) {
    List<T> values = new ArrayList<>();
    CompletableFuture<Void> completion = new CompletableFuture<>();
    publisher.subscribe(
        new Flow.Subscriber<>() {
          @Override
          public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
          }

          @Override
          public void onNext(T item) {
            values.add(item);
          }

          @Override
          public void onError(Throwable throwable) {
            completion.completeExceptionally(throwable);
          }

          @Override
          public void onComplete() {
            completion.complete(null);
          }
        });
    completion.join();
    return values;
  }

  private static final class IterablePublisher<T> implements Flow.Publisher<T> {
    private final List<T> values;

    private IterablePublisher(List<T> values) {
      this.values = List.copyOf(values);
    }

    @Override
    public void subscribe(Flow.Subscriber<? super T> subscriber) {
      subscriber.onSubscribe(
          new Flow.Subscription() {
            private boolean cancelled;
            private boolean emitted;

            @Override
            public void request(long n) {
              if (cancelled || emitted || n <= 0) {
                return;
              }
              emitted = true;
              for (T value : values) {
                if (cancelled) {
                  return;
                }
                subscriber.onNext(value);
              }
              if (!cancelled) {
                subscriber.onComplete();
              }
            }

            @Override
            public void cancel() {
              cancelled = true;
            }
          });
    }
  }
}
