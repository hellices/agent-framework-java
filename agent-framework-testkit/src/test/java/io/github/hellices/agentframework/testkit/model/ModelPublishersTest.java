package io.github.hellices.agentframework.testkit.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hellices.agentframework.api.agent.CancellationSignal;
import io.github.hellices.agentframework.api.message.FinishReason;
import io.github.hellices.agentframework.spi.model.ModelResponseUpdate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;

class ModelPublishersTest {

  private static final ModelResponseUpdate UPDATE =
      ModelResponseUpdate.builder().finishReason(FinishReason.STOP).build();

  @Test
  void justRejectsNullSubscriber() {
    assertThatThrownBy(() -> ModelPublishers.just(UPDATE).subscribe(nullSubscriber()))
        .isInstanceOf(NullPointerException.class);
  }

  private static Flow.Subscriber<ModelResponseUpdate> nullSubscriber() {
    return null;
  }

  @Test
  void justEmitsOneUpdateAfterDemandThenCompletes() {
    RecordingSubscriber subscriber = new RecordingSubscriber(1);

    ModelPublishers.just(UPDATE).subscribe(subscriber);

    assertThat(subscriber.signals())
        .containsExactly("onSubscribe", "onNext:" + UPDATE, "onComplete");
  }

  @Test
  void justEmitsNothingBeforePositiveDemand() {
    RecordingSubscriber subscriber = new RecordingSubscriber(0);

    ModelPublishers.just(UPDATE).subscribe(subscriber);

    assertThat(subscriber.signals()).containsExactly("onSubscribe");
  }

  @Test
  void justIsColdAndReplaysPerSubscriber() {
    Flow.Publisher<ModelResponseUpdate> publisher = ModelPublishers.just(UPDATE);
    RecordingSubscriber first = new RecordingSubscriber(1);
    RecordingSubscriber second = new RecordingSubscriber(1);

    publisher.subscribe(first);
    publisher.subscribe(second);

    assertThat(first.signals()).containsExactly("onSubscribe", "onNext:" + UPDATE, "onComplete");
    assertThat(second.signals()).containsExactly("onSubscribe", "onNext:" + UPDATE, "onComplete");
  }

  @Test
  void justSignalsOneIllegalArgumentExceptionForNonPositiveDemand() {
    RecordingSubscriber subscriber = new RecordingSubscriber(0);
    ModelPublishers.just(UPDATE).subscribe(subscriber);

    subscriber.subscription().request(0);
    subscriber.subscription().request(-1);

    assertThat(subscriber.errors()).hasSize(1).allMatch(IllegalArgumentException.class::isInstance);
    assertThat(subscriber.terminalCount()).isEqualTo(1);
  }

  @Test
  void justCancelPreventsSignals() {
    RecordingSubscriber subscriber = new RecordingSubscriber(0);
    ModelPublishers.just(UPDATE).subscribe(subscriber);

    subscriber.subscription().cancel();
    subscriber.subscription().request(1);

    assertThat(subscriber.signals()).containsExactly("onSubscribe");
  }

  @Test
  void failedDeliversErrorAfterSubscribeWithNoUpdate() {
    RuntimeException failure = new IllegalStateException("boom");
    RecordingSubscriber subscriber = new RecordingSubscriber(1);

    ModelPublishers.failed(failure).subscribe(subscriber);

    assertThat(subscriber.signals()).containsExactly("onSubscribe", "onError:" + failure);
    assertThat(subscriber.terminalCount()).isEqualTo(1);
  }

  @Test
  void fromStageEmitsStageValueAfterDemand() {
    RecordingSubscriber subscriber = new RecordingSubscriber(1);

    ModelPublishers.fromStage(CompletableFuture.completedFuture(UPDATE), new CancellationSignal())
        .subscribe(subscriber);

    assertThat(subscriber.signals())
        .containsExactly("onSubscribe", "onNext:" + UPDATE, "onComplete");
  }

  @Test
  void fromStageSignalsSubscribeBeforeSynchronousStageFailure() {
    RuntimeException failure = new IllegalStateException("stage failed");
    RecordingSubscriber subscriber = new RecordingSubscriber(1);

    ModelPublishers.fromStage(CompletableFuture.failedFuture(failure), new CancellationSignal())
        .subscribe(subscriber);

    assertThat(subscriber.signals()).containsExactly("onSubscribe", "onError:" + failure);
  }

  @Test
  void fromStageWaitsForDemandBeforeDeliveringFailure() {
    RuntimeException failure = new IllegalStateException("stage failed");
    RecordingSubscriber subscriber = new RecordingSubscriber(0);

    ModelPublishers.fromStage(CompletableFuture.failedFuture(failure), new CancellationSignal())
        .subscribe(subscriber);

    assertThat(subscriber.signals()).containsExactly("onSubscribe");

    subscriber.subscription().request(1);
    assertThat(subscriber.signals()).containsExactly("onSubscribe", "onError:" + failure);
  }

  @Test
  void fromStageUnwrapsCompletionException() {
    RuntimeException cause = new IllegalStateException("cause");
    CompletableFuture<ModelResponseUpdate> stage = new CompletableFuture<>();
    stage.completeExceptionally(new CompletionException(cause));
    RecordingSubscriber subscriber = new RecordingSubscriber(1);

    ModelPublishers.fromStage(stage, new CancellationSignal()).subscribe(subscriber);

    assertThat(subscriber.errors()).containsExactly(cause);
  }

  @Test
  void fromStageUnwrapsExecutionException() {
    RuntimeException cause = new IllegalStateException("cause");
    CompletableFuture<ModelResponseUpdate> stage = new CompletableFuture<>();
    stage.completeExceptionally(new java.util.concurrent.ExecutionException(cause));
    RecordingSubscriber subscriber = new RecordingSubscriber(1);

    ModelPublishers.fromStage(stage, new CancellationSignal()).subscribe(subscriber);

    assertThat(subscriber.errors()).containsExactly(cause);
  }

  @Test
  void fromStageWaitsForBothCompletionAndDemand() {
    CompletableFuture<ModelResponseUpdate> stage = new CompletableFuture<>();
    RecordingSubscriber subscriber = new RecordingSubscriber(0);

    ModelPublishers.fromStage(stage, new CancellationSignal()).subscribe(subscriber);
    assertThat(subscriber.signals()).containsExactly("onSubscribe");

    stage.complete(UPDATE);
    assertThat(subscriber.signals()).containsExactly("onSubscribe");

    subscriber.subscription().request(1);
    assertThat(subscriber.signals())
        .containsExactly("onSubscribe", "onNext:" + UPDATE, "onComplete");
  }

  @Test
  void fromStageCancelPropagatesCancellationSignalAndPreventsSignals() {
    CompletableFuture<ModelResponseUpdate> stage = new CompletableFuture<>();
    CancellationSignal cancellation = new CancellationSignal();
    RecordingSubscriber subscriber = new RecordingSubscriber(1);

    ModelPublishers.fromStage(stage, cancellation).subscribe(subscriber);
    subscriber.subscription().cancel();

    assertThat(cancellation.isCancelled()).isTrue();

    stage.complete(UPDATE);
    assertThat(subscriber.signals()).containsExactly("onSubscribe");
  }

  @Test
  void fromStageNonPositiveDemandSignalsOneIllegalArgumentException() {
    CompletableFuture<ModelResponseUpdate> stage = new CompletableFuture<>();
    RecordingSubscriber subscriber = new RecordingSubscriber(0);

    ModelPublishers.fromStage(stage, new CancellationSignal()).subscribe(subscriber);
    subscriber.subscription().request(-1);

    assertThat(subscriber.errors()).hasSize(1).allMatch(IllegalArgumentException.class::isInstance);
    assertThat(subscriber.terminalCount()).isEqualTo(1);
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
