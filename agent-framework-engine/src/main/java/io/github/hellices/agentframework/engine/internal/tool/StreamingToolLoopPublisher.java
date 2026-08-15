package io.github.hellices.agentframework.engine.internal.tool;

import io.github.hellices.agentframework.api.agent.AgentResponseUpdate;
import io.github.hellices.agentframework.api.agent.AgentRunRequest;
import io.github.hellices.agentframework.api.message.Content;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.ToolCallContent;
import io.github.hellices.agentframework.engine.internal.model.ResponseIdentity;
import io.github.hellices.agentframework.engine.internal.model.StreamingModelResponseAccumulator;
import io.github.hellices.agentframework.spi.model.ModelRequest;
import io.github.hellices.agentframework.spi.model.ModelResponse;
import io.github.hellices.agentframework.spi.model.ModelResponseUpdate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Publishes the updates of a streaming run that executes function tools (TOOL-015).
 *
 * <p>A streaming run with tools is a loop over several model calls, and this publisher is that loop
 * expressed as a single subscription: the subscriber sees one continuous stream of updates in which
 * every model call's own updates are forwarded as they arrive, each round of tool results appears
 * as updates the engine synthesised, and the next model call happens only once the results before
 * it were delivered. Because every update carries the same {@link ResponseIdentity}, {@link
 * io.github.hellices.agentframework.api.agent.AgentResponse#fromUpdates} assembles the whole loop
 * into one response.
 *
 * <h2>What the assembled response has in common with an ordinary run</h2>
 *
 * <p>The assembled response reports the same roles in the same order, the same content in the same
 * order within them, the same tool results, the summed usage, the terminal call's finish reason,
 * continuation token and metadata, and the run's own identity — which is what makes a streamed run
 * and an ordinary run of the same script interchangeable to a caller that reads the response.
 *
 * <p>What it does not promise is the message objects themselves. A streamed response is
 * reconstructed by {@code fromUpdates}, which coalesces consecutive same-role content into one
 * message while no update reports a message id, and no model port in this repository reports one.
 * Two assistant chunks an ordinary client returned as two messages therefore arrive as one
 * reconstructed message. Inventing an id per update to force the boundaries apart would claim an
 * identity the provider never gave, so the parity this loop maintains is of content and order, not
 * of message boundaries.
 *
 * <h2>State machine</h2>
 *
 * <p>One iteration moves through three phases:
 *
 * <ol>
 *   <li><b>Model</b> — a model publisher is subscribed and its updates are mapped, recorded in the
 *       iteration's {@link StreamingModelResponseAccumulator} and forwarded. Downstream demand is
 *       passed straight through to it, so a slow subscriber slows the model down instead of being
 *       buffered around.
 *   <li><b>Tools</b> — when the model call completes, its accumulated response decides the run:
 *       without tool calls the loop is finished, the call's metadata is queued as the stream's last
 *       update and the stream completes; with tool calls the shared {@link ToolLoopPolicy}
 *       validates the budget and executes them, exactly as an ordinary run does.
 *   <li><b>Results</b> — each tool result is queued as its own update and emitted under downstream
 *       demand, in call order. The next iteration starts only after the last of them was delivered,
 *       so the model never sees a request whose results the subscriber has not seen yet.
 * </ol>
 *
 * <h2>Reactive Streams behaviour</h2>
 *
 * <p>The subscription is single-use and single-subscriber. Demand is one counter, held under a lock
 * together with the currently active model subscription so that credit granted while no model call
 * is running is handed to the next one when it subscribes, and never handed out twice. Emission
 * runs in a work-in-progress trampoline, so a synchronous publisher that emits from inside {@code
 * request} cannot re-enter {@code onNext}, and so the next iteration is started by whichever thread
 * drained the last queued result. Exactly one terminal signal is delivered: the first failure wins,
 * completion is delivered only once the queue is empty, and a cancelled subscription emits neither
 * — the run's own terminal handling reports cancellation to the caller.
 *
 * <p>Cancellation is honoured from both sides — the subscriber cancelling and the run's {@link
 * io.github.hellices.agentframework.api.agent.CancellationSignal} — and stops the loop wherever it
 * is: the active model subscription is cancelled, queued results are dropped, and a tool stage that
 * completes afterwards neither emits its results nor starts another iteration. The state is read
 * again immediately before each model call is made, so no iteration starts once cancellation is
 * observable there; a cancellation that becomes observable while a client is producing its
 * publisher cannot unmake that call, but that publisher is cancelled instead of read and delivers
 * no signal.
 *
 * <p>No executor, thread or {@link java.util.concurrent.SubmissionPublisher} is created: every
 * signal runs on the thread that caused it.
 */
public final class StreamingToolLoopPublisher implements Flow.Publisher<AgentResponseUpdate> {

  private final Supplier<Flow.Publisher<ModelResponseUpdate>> firstStream;
  private final Supplier<ModelRequest> firstRequest;
  private final Function<ModelRequest, Flow.Publisher<ModelResponseUpdate>> nextStream;
  private final ToolLoopPolicy policy;
  private final AgentRunRequest request;
  private final ResponseIdentity identity;
  private final AtomicBoolean subscribed = new AtomicBoolean();

  /**
   * @param firstStream the first model call, already decided by the engine so that a run without an
   *     asynchronous gate keeps failing synchronously when its client does
   * @param firstRequest the request the first model call was made with, readable once that call was
   *     made
   * @param nextStream how every later model call is made
   * @param policy the shared tool budget rules this run's ordinary counterpart would follow
   * @param request the run being streamed, carrying its cancellation signal and attributes
   * @param identity the response every update of this run belongs to
   */
  public StreamingToolLoopPublisher(
      Supplier<Flow.Publisher<ModelResponseUpdate>> firstStream,
      Supplier<ModelRequest> firstRequest,
      Function<ModelRequest, Flow.Publisher<ModelResponseUpdate>> nextStream,
      ToolLoopPolicy policy,
      AgentRunRequest request,
      ResponseIdentity identity) {
    this.firstStream = Objects.requireNonNull(firstStream, "firstStream must not be null");
    this.firstRequest = Objects.requireNonNull(firstRequest, "firstRequest must not be null");
    this.nextStream = Objects.requireNonNull(nextStream, "nextStream must not be null");
    this.policy = Objects.requireNonNull(policy, "policy must not be null");
    this.request = Objects.requireNonNull(request, "request must not be null");
    this.identity = Objects.requireNonNull(identity, "identity must not be null");
  }

  @Override
  public void subscribe(Flow.Subscriber<? super AgentResponseUpdate> subscriber) {
    Objects.requireNonNull(subscriber, "subscriber must not be null");
    if (!subscribed.compareAndSet(false, true)) {
      subscriber.onSubscribe(new RejectedSubscription());
      subscriber.onError(new IllegalStateException("model updates can only be consumed once"));
      return;
    }
    LoopSubscription subscription = new LoopSubscription(subscriber);
    subscriber.onSubscribe(subscription);
    subscription.start();
  }

  /** Unwraps the failure a {@link CompletionStage} reports, so callers see what actually failed. */
  private static Throwable unwrap(Throwable throwable) {
    return throwable instanceof CompletionException && throwable.getCause() != null
        ? throwable.getCause()
        : throwable;
  }

  /** The iteration a finished round of tool calls left ready to start. */
  private record PendingIteration(int index, ModelRequest request) {}

  private final class LoopSubscription implements Flow.Subscription {

    private final Flow.Subscriber<? super AgentResponseUpdate> downstream;
    private final Queue<AgentResponseUpdate> queue = new ConcurrentLinkedQueue<>();
    private final AtomicReference<PendingIteration> pending = new AtomicReference<>();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final AtomicInteger wip = new AtomicInteger();
    private final AtomicBoolean terminated = new AtomicBoolean();
    private final Object lock = new Object();
    private volatile boolean cancelled;
    private volatile boolean finished;
    private volatile Runnable removeCancellationListener = () -> {};
    private long demand;
    private Flow.Subscription upstream;

    private LoopSubscription(Flow.Subscriber<? super AgentResponseUpdate> downstream) {
      this.downstream = downstream;
    }

    private void start() {
      if (cancelled || terminated.get()) {
        return;
      }
      Runnable removeListener = request.cancellationSignal().onCancel(this::cancel);
      removeCancellationListener = removeListener;
      if (cancelled || terminated.get()) {
        removeListener.run();
        return;
      }
      beginIteration(0, null, firstStream);
    }

    @Override
    public void request(long n) {
      if (n <= 0) {
        fail(new IllegalArgumentException("demand must be greater than zero"));
        return;
      }
      Flow.Subscription active;
      synchronized (lock) {
        demand = demand + n < 0 ? Long.MAX_VALUE : demand + n;
        active = upstream;
      }
      if (active != null) {
        active.request(n);
      }
      drain();
    }

    @Override
    public void cancel() {
      Flow.Subscription active;
      synchronized (lock) {
        if (cancelled) {
          return;
        }
        cancelled = true;
        active = upstream;
        upstream = null;
      }
      removeCancellationListener.run();
      queue.clear();
      pending.set(null);
      if (active != null) {
        active.cancel();
      }
    }

    /**
     * Subscribes the model call of {@code index}. The upstream slot is cleared first, so demand
     * granted between two iterations is handed to the new subscription when it arrives rather than
     * to the finished one.
     *
     * <p>The cancellation state is read once more immediately before the model client is asked for
     * its publisher, because the decision to start this iteration was taken earlier — when a
     * finished tool round was armed, or when the last queued result was delivered — and the run may
     * have been cancelled since. The check is deliberately not held under {@code lock}: obtaining
     * and subscribing a publisher calls foreign code, and holding the demand monitor across it
     * would let a client that emits synchronously deadlock the subscription. What the check
     * guarantees is therefore stated in terms of what it observes: once cancellation is observable
     * here, this iteration does not start. A cancellation that becomes observable in the instant
     * after it — while the client is producing its publisher — cannot unmake that call, but no
     * signal of it reaches the subscriber either, because {@link ModelSubscriber#onSubscribe}
     * cancels the new subscription instead of requesting from it.
     *
     * <p>Producing the publisher and subscribing to it are guarded together, because both are the
     * client's code and a client that rejects a subscription fails the run for the same reason one
     * that cannot produce a publisher does. Letting the throw escape instead would unwind through
     * whichever thread happened to start the iteration — the subscriber's own {@code request} call
     * for the first iteration, a tool stage's completion for a later one — and leave the run with
     * no terminal signal at all.
     */
    private void beginIteration(
        int index,
        ModelRequest iterationRequest,
        Supplier<Flow.Publisher<ModelResponseUpdate>> source) {
      synchronized (lock) {
        upstream = null;
      }
      if (cancelled || terminated.get() || request.cancellationSignal().isCancelled()) {
        return;
      }
      try {
        Flow.Publisher<ModelResponseUpdate> publisher =
            Objects.requireNonNull(source.get(), "model client update publisher must not be null");
        publisher.subscribe(new ModelSubscriber(index, iterationRequest));
      } catch (RuntimeException startFailure) {
        fail(startFailure);
      }
    }

    /**
     * Decides what a finished model call means for the run: end it, or execute the tools it asked
     * for. This is the point at which a streaming iteration and an ordinary one are made to agree —
     * both read the same reassembled response and both ask the same {@link ToolLoopPolicy}.
     *
     * <p>The call that ends the run is also the one whose metadata the run reports. Its metadata is
     * queued as the last update of the stream, because that is the only place a metadata-unioning
     * assembler can be told which model call's metadata the response has: no update before it
     * carried any, so the assembled map is exactly this call's, down to being empty when the call
     * reported nothing.
     */
    private void completeIteration(
        int index, ModelRequest iterationRequest, StreamingModelResponseAccumulator accumulator) {
      if (cancelled || terminated.get()) {
        return;
      }
      CompletionStage<List<Content>> results;
      ModelResponse response;
      ModelRequest current;
      List<ToolCallContent> calls;
      try {
        response = accumulator.toModelResponse();
        policy.validateContinuation(response);
        calls = ToolLoopPolicy.toolCalls(response);
        if (calls.isEmpty()) {
          if (!response.metadata().isEmpty()) {
            queue.add(identity.metadataUpdate(response.metadata()));
          }
          finished = true;
          drain();
          return;
        }
        if (request.cancellationSignal().isCancelled()) {
          throw new CancellationException("run was cancelled");
        }
        policy.requireIterationBudget(index);
        current =
            iterationRequest == null
                ? Objects.requireNonNull(firstRequest.get(), "model request must not be null")
                : iterationRequest;
        results = policy.executeToolCalls(calls, request);
      } catch (RuntimeException iterationFailure) {
        fail(iterationFailure);
        return;
      }
      results.whenComplete(
          (values, toolFailure) ->
              completeTools(index, current, response, calls, values, toolFailure));
    }

    /**
     * Queues one update per tool result, in call order, and arms the next iteration. A run
     * cancelled while the tools were pending stops here: its results are neither reported nor
     * answered by another model call.
     */
    private void completeTools(
        int index,
        ModelRequest current,
        ModelResponse response,
        List<ToolCallContent> calls,
        List<Content> results,
        Throwable toolFailure) {
      if (toolFailure != null) {
        fail(toolFailure);
        return;
      }
      if (cancelled || terminated.get() || request.cancellationSignal().isCancelled()) {
        return;
      }
      try {
        Message toolResults = ToolLoopPolicy.toolResultMessage(results);
        List<AgentResponseUpdate> updates = new ArrayList<>();
        for (Content result : results) {
          updates.add(
              identity.messageUpdate(List.of(ToolLoopPolicy.toolResultMessage(List.of(result)))));
        }
        ModelRequest next =
            policy.nextRequest(current, response.messages(), calls, toolResults, index);
        queue.addAll(updates);
        pending.set(new PendingIteration(index + 1, next));
      } catch (RuntimeException resultFailure) {
        fail(resultFailure);
        return;
      }
      drain();
    }

    /** Records the first failure of the run and lets the drain deliver it. */
    private void fail(Throwable throwable) {
      if (failure.compareAndSet(null, unwrap(throwable))) {
        drain();
      }
    }

    /**
     * Runs the emission trampoline. A signal a downstream callback throws out of is not this
     * subscription's failure to record — the subscriber violated its own contract — but the
     * work-in-progress counter is reset before the throw leaves, because leaving it raised would
     * make every later drain a no-op and strand the run without a terminal signal.
     */
    private void drain() {
      if (wip.getAndIncrement() != 0) {
        return;
      }
      int missed = 1;
      try {
        while (emit()) {
          missed = wip.addAndGet(-missed);
          if (missed == 0) {
            return;
          }
        }
      } catch (RuntimeException drainFailure) {
        wip.set(0);
        throw drainFailure;
      }
    }

    /**
     * Emits what demand allows and then either terminates the stream or starts the iteration a
     * finished tool round left ready.
     *
     * @return {@code false} once no further draining can change anything
     */
    private boolean emit() {
      while (true) {
        if (cancelled) {
          queue.clear();
          return false;
        }
        Throwable pendingFailure = failure.get();
        if (pendingFailure != null) {
          terminate(pendingFailure);
          return false;
        }
        AgentResponseUpdate update;
        synchronized (lock) {
          update = demand > 0 ? queue.poll() : null;
          if (update != null && demand != Long.MAX_VALUE) {
            demand--;
          }
        }
        if (update == null) {
          break;
        }
        downstream.onNext(update);
      }
      if (finished && queue.isEmpty()) {
        terminate(null);
        return false;
      }
      startPendingIteration();
      return true;
    }

    private void startPendingIteration() {
      if (cancelled || finished || failure.get() != null || !queue.isEmpty()) {
        return;
      }
      PendingIteration next = pending.getAndSet(null);
      if (next == null) {
        return;
      }
      beginIteration(next.index(), next.request(), () -> nextStream.apply(next.request()));
    }

    /** Delivers the one terminal signal this subscription is allowed to deliver. */
    private void terminate(Throwable throwable) {
      if (!terminated.compareAndSet(false, true)) {
        return;
      }
      removeCancellationListener.run();
      Flow.Subscription active;
      synchronized (lock) {
        active = upstream;
        upstream = null;
      }
      if (throwable == null) {
        downstream.onComplete();
        return;
      }
      queue.clear();
      pending.set(null);
      if (active != null) {
        try {
          active.cancel();
        } catch (RuntimeException cancelFailure) {
          throwable.addSuppressed(cancelFailure);
        }
      }
      downstream.onError(throwable);
    }

    /** Consumes one model call. A new instance per iteration keeps the iterations' state apart. */
    private final class ModelSubscriber implements Flow.Subscriber<ModelResponseUpdate> {

      private final int index;
      private final ModelRequest iterationRequest;
      private final StreamingModelResponseAccumulator accumulator =
          new StreamingModelResponseAccumulator(identity);
      private volatile boolean done;

      private ModelSubscriber(int index, ModelRequest iterationRequest) {
        this.index = index;
        this.iterationRequest = iterationRequest;
      }

      @Override
      public void onSubscribe(Flow.Subscription subscription) {
        Objects.requireNonNull(subscription, "model subscription must not be null");
        long initial;
        synchronized (lock) {
          if (cancelled || terminated.get()) {
            initial = -1;
          } else {
            upstream = subscription;
            initial = demand;
          }
        }
        if (initial < 0) {
          subscription.cancel();
          return;
        }
        if (initial > 0) {
          subscription.request(initial);
        }
      }

      @Override
      public void onNext(ModelResponseUpdate update) {
        if (done || cancelled || terminated.get()) {
          return;
        }
        AgentResponseUpdate mapped;
        try {
          mapped = accumulator.record(update);
        } catch (RuntimeException recordFailure) {
          done = true;
          fail(recordFailure);
          return;
        }
        queue.add(mapped);
        drain();
      }

      @Override
      public void onError(Throwable throwable) {
        if (done) {
          return;
        }
        done = true;
        fail(throwable);
      }

      @Override
      public void onComplete() {
        if (done) {
          return;
        }
        done = true;
        completeIteration(index, iterationRequest, accumulator);
      }
    }
  }

  /** The subscription handed to a second subscriber, which is rejected before it can request. */
  private static final class RejectedSubscription implements Flow.Subscription {

    @Override
    public void request(long n) {
      // The subscriber is failed immediately after this subscription is handed out.
    }

    @Override
    public void cancel() {
      // Nothing to cancel.
    }
  }
}
