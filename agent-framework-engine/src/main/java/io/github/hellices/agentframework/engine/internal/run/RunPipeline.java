package io.github.hellices.agentframework.engine.internal.run;

import io.github.hellices.agentframework.api.agent.AgentResponse;
import io.github.hellices.agentframework.api.agent.AgentResponseUpdate;
import io.github.hellices.agentframework.api.agent.AgentRunRequest;
import io.github.hellices.agentframework.api.message.Content;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.ToolCallContent;
import io.github.hellices.agentframework.api.message.Usage;
import io.github.hellices.agentframework.api.value.JsonNumber;
import io.github.hellices.agentframework.api.value.JsonObject;
import io.github.hellices.agentframework.api.value.JsonValue;
import io.github.hellices.agentframework.engine.internal.model.ResponseIdentity;
import io.github.hellices.agentframework.engine.internal.model.StreamingModelResponseAccumulator;
import io.github.hellices.agentframework.engine.internal.tool.ToolLoopPolicy;
import io.github.hellices.agentframework.spi.model.ModelRequest;
import io.github.hellices.agentframework.spi.model.ModelResponse;
import io.github.hellices.agentframework.spi.model.ModelResponseUpdate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The one update-oriented pipeline every run of an agent executes, observed two ways.
 *
 * <p>A run is a loop over one or more model calls that may execute function tools between them.
 * This publisher is that loop expressed as a single subscription: every model call's updates are
 * forwarded as they arrive, each round of tool results is reported as updates the engine
 * synthesised, and the next model call happens only once the results before it were delivered.
 * Because every update carries the same {@link ResponseIdentity}, {@link AgentResponse#fromUpdates}
 * assembles the whole loop into one response.
 *
 * <h2>Two consumers, one execution</h2>
 *
 * <p>A streaming run subscribes the caller to this publisher and sees the cold, unicast update
 * stream directly. An ordinary run installs an internal draining subscriber and reads {@link
 * #terminalResponse()} instead: the same loop runs, but the caller observes only the single {@link
 * ModelResponse} it assembled — with the message boundaries an ordinary client returned preserved,
 * because that response is built from each model call's own updates rather than reconstructed by
 * {@code fromUpdates}. The two consumers therefore agree on the roles, content, order, tool
 * results, usage, finish reason, continuation token and terminal metadata of a run, and differ only
 * where they must: a streamed response coalesces consecutive same-role chunks a stream never gave a
 * message id, and an empty model stream is an empty streamed response but an ordinary failure.
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
 *       validates the budget and executes them, exactly as before.
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
 * request} cannot re-enter {@code onNext}. Exactly one terminal signal is delivered: the first
 * failure wins, completion is delivered only once the queue is empty, and a cancelled subscription
 * emits neither.
 *
 * <p>Cancellation is honoured from both sides — the subscriber cancelling and the run's {@link
 * io.github.hellices.agentframework.api.agent.CancellationSignal} — and stops the loop wherever it
 * is: the active model subscription is cancelled, queued results are dropped, and a tool stage that
 * completes afterwards neither emits its results nor starts another iteration.
 *
 * <p>No executor, thread or {@link java.util.concurrent.SubmissionPublisher} is created: every
 * signal runs on the thread that caused it.
 */
public final class RunPipeline implements Flow.Publisher<AgentResponseUpdate> {

  private final Supplier<Flow.Publisher<ModelResponseUpdate>> firstStream;
  private final Supplier<ModelRequest> firstRequest;
  private final Function<ModelRequest, Flow.Publisher<ModelResponseUpdate>> nextStream;
  private final ToolLoopPolicy policy;
  private final AgentRunRequest request;
  private final ResponseIdentity identity;
  private final RunExecution execution;
  private final CompletableFuture<ModelResponse> ordinaryTerminal = new CompletableFuture<>();
  private final AtomicBoolean subscribed = new AtomicBoolean();

  /**
   * @param firstStream the first model call, already decided by the engine so that a run without an
   *     asynchronous gate keeps failing synchronously when its client does
   * @param firstRequest the request the first model call was made with, readable once that call was
   *     made
   * @param nextStream how every later model call is made
   * @param policy the shared tool budget rules this run follows
   * @param request the run being executed, carrying its cancellation signal and attributes
   * @param identity the response every update of this run belongs to
   * @param execution the run's explicit state machine, driven through {@code FINALIZE_RESPONSE}
   */
  public RunPipeline(
      Supplier<Flow.Publisher<ModelResponseUpdate>> firstStream,
      Supplier<ModelRequest> firstRequest,
      Function<ModelRequest, Flow.Publisher<ModelResponseUpdate>> nextStream,
      ToolLoopPolicy policy,
      AgentRunRequest request,
      ResponseIdentity identity,
      RunExecution execution) {
    this.firstStream = Objects.requireNonNull(firstStream, "firstStream must not be null");
    this.firstRequest = Objects.requireNonNull(firstRequest, "firstRequest must not be null");
    this.nextStream = Objects.requireNonNull(nextStream, "nextStream must not be null");
    this.policy = Objects.requireNonNull(policy, "policy must not be null");
    this.request = Objects.requireNonNull(request, "request must not be null");
    this.identity = Objects.requireNonNull(identity, "identity must not be null");
    this.execution = Objects.requireNonNull(execution, "execution must not be null");
  }

  /**
   * The single response an ordinary run observes, completed when the loop finished. It preserves
   * the message boundaries each model call returned and fails with the same error a streamed run's
   * subscriber would see, except that an empty model stream fails this stage where it completes a
   * streamed run empty.
   */
  public CompletionStage<ModelResponse> terminalResponse() {
    return ordinaryTerminal.minimalCompletionStage();
  }

  /** The run's explicit state machine, so the engine can drive the post-finalise lifecycle. */
  public RunExecution execution() {
    return execution;
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
    private final List<Message> ordinaryMessages = new ArrayList<>();
    private volatile boolean cancelled;
    private volatile boolean finished;
    private volatile Runnable removeCancellationListener = () -> {};
    private Usage ordinaryUsage;
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
      advance(RunPhase.LOAD_SESSION);
      advance(RunPhase.PREPARE_CONTEXT);
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
      // A pure downstream cancel (the subscriber dropping the stream without the run's cancellation
      // signal firing) must still terminalise the run's explicit state machine so its terminal
      // listeners run and the cancellation listener detaches, while staying silent downstream per
      // the Reactive Streams contract. A signal-driven cancel already terminalised the machine, so
      // this is a no-op then.
      execution.cancel();
      ordinaryTerminal.completeExceptionally(new CancellationException("run was cancelled"));
      if (active != null) {
        active.cancel();
      }
    }

    /**
     * Subscribes the model call of {@code index}. The upstream slot is cleared first, so demand
     * granted between two iterations is handed to the new subscription when it arrives rather than
     * to the finished one. The cancellation state is read once more immediately before the model
     * client is asked for its publisher, because the decision to start this iteration was taken
     * earlier and the run may have been cancelled since.
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
      advance(RunPhase.PREPARE_MODEL_REQUEST);
      try {
        Flow.Publisher<ModelResponseUpdate> publisher =
            Objects.requireNonNull(source.get(), "model client update publisher must not be null");
        advance(RunPhase.CALL_MODEL);
        publisher.subscribe(new ModelSubscriber(index, iterationRequest));
      } catch (RuntimeException startFailure) {
        fail(startFailure);
      }
    }

    /**
     * Decides what a finished model call means for the run: end it, or execute the tools it asked
     * for. This is the point at which the streaming and ordinary views of the same run are made to
     * agree — both read the same reassembled response and both ask the same {@link ToolLoopPolicy}.
     *
     * <p>An empty model call is the one place the two views diverge on lifecycle. With tools it is
     * always a failure: the loop has no response to inspect. Without tools it is the empty response
     * a streamed run completes with, so the stream finishes while the ordinary terminal fails with
     * the same error the ordinary path always reported.
     */
    private void completeIteration(
        int index,
        ModelRequest iterationRequest,
        StreamingModelResponseAccumulator accumulator,
        List<ModelResponseUpdate> rawUpdates) {
      if (cancelled || terminated.get()) {
        return;
      }
      if (accumulator.isEmpty()) {
        // An empty model stream is a failure for every run, ordinary or streaming: there is no
        // update to reassemble a response from, so both views report the same error rather than one
        // completing empty.
        fail(new IllegalStateException("model stream completed without any update"));
        return;
      }
      advance(RunPhase.ACCUMULATE_MODEL_UPDATES);
      CompletionStage<List<Content>> results;
      ModelResponse response;
      ModelRequest current;
      List<ToolCallContent> calls;
      try {
        response = accumulator.toModelResponse();
        policy.validateContinuation(response);
        ModelResponse ordinaryResponse = assembleResponse(rawUpdates);
        recordOrdinaryIteration(ordinaryResponse);
        calls = ToolLoopPolicy.toolCalls(response);
        advance(RunPhase.PLAN_TOOL_ACTION);
        if (calls.isEmpty()) {
          completeRun(response, ordinaryResponse);
          return;
        }
        if (request.cancellationSignal().isCancelled()) {
          throw new CancellationException("run was cancelled");
        }
        policy.requireIterationBudget(index);
        if (!policy.canExecuteAll(calls) && policy.declaresAll(calls)) {
          // A declaration-only tool was invoked: it is declared and offered to the model but has
          // no local body, so the run ends with the reassembled response — whose tool-call updates
          // were already emitted — instead of fabricating results the Java core cannot produce
          // (TOOL-006). A call to an undeclared tool is not handled here: it reaches execution and
          // fails with the existing safe error.
          completeRun(response, ordinaryResponse);
          return;
        }
        current =
            iterationRequest == null
                ? Objects.requireNonNull(firstRequest.get(), "model request must not be null")
                : iterationRequest;
        advance(RunPhase.EXECUTE_TOOL_BATCH);
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
     * Ends the run with the reassembled model response. Its metadata is queued as the stream's last
     * update — the only place a metadata-unioning assembler can be told which model call's metadata
     * the response has — and the ordinary terminal is completed with the boundary-preserving
     * response the ordinary path assembled.
     */
    private void completeRun(ModelResponse decision, ModelResponse ordinaryResponse) {
      if (!decision.metadata().isEmpty()) {
        queue.add(identity.metadataUpdate(decision.metadata()));
      }
      // Reach FINALIZE_RESPONSE before completing the ordinary terminal: the engine's post-run
      // lifecycle is composed onto that stage and runs inline from COMPLETE_CONTEXT onward, so the
      // state machine must already be at FINALIZE_RESPONSE when it does.
      advance(RunPhase.FINALIZE_RESPONSE);
      completeOrdinaryTerminal(ordinaryResponse);
      finished = true;
      drain();
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
        ordinaryMessages.add(toolResults);
        queue.addAll(updates);
        pending.set(new PendingIteration(index + 1, next));
      } catch (RuntimeException resultFailure) {
        fail(resultFailure);
        return;
      }
      drain();
    }

    /** Accumulates one model call's boundary-preserving response into the ordinary terminal. */
    private void recordOrdinaryIteration(ModelResponse ordinaryResponse) {
      ordinaryMessages.addAll(ordinaryResponse.messages());
      ordinaryUsage = combineUsage(ordinaryUsage, ordinaryResponse.usage());
    }

    /** Completes the ordinary terminal with the run's accumulated messages and terminal outcome. */
    private void completeOrdinaryTerminal(ModelResponse terminalIteration) {
      ordinaryTerminal.complete(
          ModelResponse.builder()
              .messages(List.copyOf(ordinaryMessages))
              .usage(ordinaryUsage)
              .finishReason(terminalIteration.finishReason())
              .continuationToken(terminalIteration.continuationToken())
              .metadata(terminalIteration.metadata())
              .rawRepresentation(terminalIteration.rawRepresentation())
              .build());
    }

    /** Records the first failure of the run and lets the drain deliver it. */
    private void fail(Throwable throwable) {
      Throwable cause = unwrap(throwable);
      if (failure.compareAndSet(null, cause)) {
        ordinaryTerminal.completeExceptionally(cause);
        execution.fail(cause);
        drain();
      }
    }

    /**
     * Drives the run's explicit state machine, swallowing only the transition a concurrent
     * cancellation already made terminal. A transition that is illegal for any other reason is a
     * pipeline defect and is allowed to surface.
     */
    private void advance(RunPhase next) {
      try {
        if (!execution.state().isTerminal()) {
          execution.transitionTo(next);
        }
      } catch (IllegalStateException raced) {
        if (!execution.state().isTerminal()) {
          throw raced;
        }
      }
    }

    /**
     * Runs the emission trampoline. A signal a downstream callback throws out of is not this
     * subscription's failure to record, but the work-in-progress counter is reset before the throw
     * leaves, because leaving it raised would strand the run without a terminal signal.
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
      private final List<ModelResponseUpdate> rawUpdates = new ArrayList<>();
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
          rawUpdates.add(update);
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
        completeIteration(index, iterationRequest, accumulator, rawUpdates);
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

  /**
   * Reassembles one model call's updates into the single {@link ModelResponse} an ordinary run
   * observes for that call. A call an ordinary client returned as one update keeps its message
   * boundaries; a call a stream delivered in pieces is coalesced by {@link
   * AgentResponse#fromUpdates}, exactly as the streamed view coalesces it.
   */
  private static ModelResponse assembleResponse(List<ModelResponseUpdate> updates) {
    if (updates.isEmpty()) {
      throw new IllegalStateException("model stream completed without any update");
    }
    if (updates.size() == 1) {
      ModelResponseUpdate update = updates.get(0);
      return ModelResponse.builder()
          .messages(update.messages())
          .usage(update.usage())
          .finishReason(update.finishReason())
          .continuationToken(update.continuationToken())
          .metadata(update.metadata())
          .rawRepresentation(update.rawRepresentation())
          .build();
    }
    StreamingModelResponseAccumulator accumulator =
        new StreamingModelResponseAccumulator(
            new ResponseIdentity(
                "model", java.util.UUID.randomUUID().toString(), null, java.time.Instant.now()));
    for (ModelResponseUpdate update : updates) {
      accumulator.record(update);
    }
    return accumulator.toModelResponse();
  }

  private static Usage combineUsage(Usage accumulated, Usage update) {
    if (accumulated == null) {
      return update;
    }
    if (update == null) {
      return accumulated;
    }
    return new Usage(
        Math.addExact(accumulated.inputTokens(), update.inputTokens()),
        Math.addExact(accumulated.outputTokens(), update.outputTokens()),
        Math.addExact(accumulated.totalTokens(), update.totalTokens()),
        mergeAdditionalProperties(
            accumulated.additionalProperties(),
            update.additionalProperties(),
            RunPipeline::combineUsageProperty));
  }

  private static JsonValue combineUsageProperty(JsonValue accumulated, JsonValue update) {
    if (accumulated instanceof JsonNumber left && update instanceof JsonNumber right) {
      return JsonNumber.of(left.value().add(right.value()));
    }
    return update;
  }

  private static JsonObject mergeAdditionalProperties(
      JsonObject accumulated,
      JsonObject update,
      BiFunction<JsonValue, JsonValue, JsonValue> merger) {
    if ((accumulated == null || accumulated.isEmpty()) && (update == null || update.isEmpty())) {
      return JsonObject.empty();
    }
    if (accumulated == null || accumulated.isEmpty()) {
      return update == null ? JsonObject.empty() : update;
    }
    if (update == null || update.isEmpty()) {
      return accumulated;
    }
    Map<String, JsonValue> merged = new LinkedHashMap<>(accumulated.values());
    update
        .values()
        .forEach(
            (key, value) ->
                merged.merge(
                    key,
                    value,
                    (left, right) ->
                        Objects.requireNonNull(
                            merger.apply(left, right), "merged value must not be null")));
    JsonObject.Builder builder = JsonObject.builder();
    merged.forEach(builder::put);
    return builder.build();
  }
}
