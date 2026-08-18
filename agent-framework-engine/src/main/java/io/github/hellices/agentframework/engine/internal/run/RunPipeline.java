package io.github.hellices.agentframework.engine.internal.run;

import io.github.hellices.agentframework.api.agent.AgentResponse;
import io.github.hellices.agentframework.api.agent.AgentResponseUpdate;
import io.github.hellices.agentframework.api.agent.AgentRunRequest;
import io.github.hellices.agentframework.api.message.Content;
import io.github.hellices.agentframework.api.message.FinishReason;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.Role;
import io.github.hellices.agentframework.api.message.ToolApprovalRequestContent;
import io.github.hellices.agentframework.api.message.ToolCallContent;
import io.github.hellices.agentframework.api.message.Usage;
import io.github.hellices.agentframework.api.value.JsonNumber;
import io.github.hellices.agentframework.api.value.JsonObject;
import io.github.hellices.agentframework.api.value.JsonValue;
import io.github.hellices.agentframework.engine.internal.model.ResponseIdentity;
import io.github.hellices.agentframework.engine.internal.model.StreamingModelResponseAccumulator;
import io.github.hellices.agentframework.engine.internal.tool.ToolApprovalCoordinator;
import io.github.hellices.agentframework.engine.internal.tool.ToolLoopPolicy;
import io.github.hellices.agentframework.spi.model.ModelRequest;
import io.github.hellices.agentframework.spi.model.ModelResponse;
import io.github.hellices.agentframework.spi.model.ModelResponseUpdate;
import io.github.hellices.agentframework.spi.telemetry.TelemetryAttributes;
import io.github.hellices.agentframework.spi.telemetry.TelemetryOperation;
import io.github.hellices.agentframework.spi.telemetry.TelemetryOperationKind;
import io.github.hellices.agentframework.spi.telemetry.TelemetrySink;
import io.github.hellices.agentframework.spi.telemetry.TelemetryStart;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
 * <p>When the run's agent has tool approval configured, resolving a queue the run inherited comes
 * first: {@link RunPhase#RESOLVE_APPROVAL} matches the caller's approval responses against the
 * queue's head, in order, before any model call is made. A fully resolved queue's decided calls
 * execute and their results start the run's first model call exactly as an uninterrupted run's
 * would; an unresolved head instead moves the run to {@link RunPhase#WAIT_APPROVAL} and ends it at
 * {@code STOP} with that request surfaced, without a model call being made at all.
 *
 * <p>Once a model call is made, one iteration moves through three phases:
 *
 * <ol>
 *   <li><b>Model</b> — a model publisher is subscribed and its updates are mapped, recorded in the
 *       iteration's {@link StreamingModelResponseAccumulator} and forwarded. Downstream demand is
 *       passed straight through to it, so a slow subscriber slows the model down instead of being
 *       buffered around.
 *   <li><b>Tools</b> — when the model call completes, its accumulated response decides the run:
 *       without tool calls the loop is finished, the call's metadata is queued as the stream's last
 *       update and the stream completes; with tool calls the shared {@link ToolLoopPolicy} first
 *       validates the budget and requires every call to name a declared tool, failing the whole
 *       batch before anything in it is touched when one does not (CR-1) — a call to an undeclared
 *       tool is never handed to the approval coordinator and never reaches execution, regardless of
 *       configuration or of where in the batch it sits, so an executable sibling can never run
 *       ahead of it. Once every call is confirmed declared, a batch where every call is also
 *       executable plans against the approval coordinator instead of executing directly when
 *       approvals are configured — moving the run to {@link RunPhase#WAIT_APPROVAL} exactly as
 *       above when a call in the batch is unresolved.
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

  private final Function<List<Message>, Flow.Publisher<ModelResponseUpdate>> firstStream;
  private final Supplier<ModelRequest> firstRequest;
  private final Function<ModelRequest, Flow.Publisher<ModelResponseUpdate>> nextStream;
  private final Supplier<ToolLoopPolicy> policySupplier;
  private final ToolLoopPolicy.BoundToolInvoker toolInvoker;
  private final AgentRunRequest request;
  private final ResponseIdentity identity;
  private final RunExecution execution;
  private final ToolApprovalCoordinator approvals;
  private final CompletionStage<Void> approvalGate;
  private final TelemetrySink telemetrySink;
  private final CompletableFuture<ModelResponse> ordinaryTerminal = new CompletableFuture<>();
  private final AtomicBoolean subscribed = new AtomicBoolean();

  /**
   * @param firstStream the first model call, already decided by the engine so that a run without an
   *     asynchronous gate keeps failing synchronously when its client does; it is given the
   *     messages a resolved approval queue appended before the first model call, which is empty for
   *     every run that is not resuming one
   * @param firstRequest the request the first model call was made with, readable once that call was
   *     made
   * @param nextStream how every later model call is made
   * @param policy the shared tool budget rules this run follows
   * @param toolInvoker how each executed bound tool call is run, so the engine can route exactly
   *     the calls this run executes through the tool interceptor seam
   * @param request the run being executed, carrying its cancellation signal and attributes
   * @param identity the response every update of this run belongs to
   * @param execution the run's explicit state machine, driven through {@code FINALIZE_RESPONSE}
   * @param approvals the run's approval state machine, or {@code null} when the agent configured no
   *     approval settings and no tool call of this run is subject to approval
   * @param approvalGate the stage the approval queue is readable after, so the queue is resolved
   *     against a session that has already been loaded
   */
  public RunPipeline(
      Function<List<Message>, Flow.Publisher<ModelResponseUpdate>> firstStream,
      Supplier<ModelRequest> firstRequest,
      Function<ModelRequest, Flow.Publisher<ModelResponseUpdate>> nextStream,
      Supplier<ToolLoopPolicy> policySupplier,
      ToolLoopPolicy.BoundToolInvoker toolInvoker,
      AgentRunRequest request,
      ResponseIdentity identity,
      RunExecution execution,
      ToolApprovalCoordinator approvals,
      CompletionStage<Void> approvalGate,
      TelemetrySink telemetrySink) {
    this.firstStream = Objects.requireNonNull(firstStream, "firstStream must not be null");
    this.firstRequest = Objects.requireNonNull(firstRequest, "firstRequest must not be null");
    this.nextStream = Objects.requireNonNull(nextStream, "nextStream must not be null");
    this.policySupplier = Objects.requireNonNull(policySupplier, "policySupplier must not be null");
    this.toolInvoker = Objects.requireNonNull(toolInvoker, "toolInvoker must not be null");
    this.request = Objects.requireNonNull(request, "request must not be null");
    this.identity = Objects.requireNonNull(identity, "identity must not be null");
    this.execution = Objects.requireNonNull(execution, "execution must not be null");
    this.approvals = approvals;
    this.approvalGate = approvalGate;
    this.telemetrySink = Objects.requireNonNull(telemetrySink, "telemetrySink must not be null");
    if (approvals != null && approvalGate == null) {
      throw new IllegalArgumentException(
          "approvalGate must not be null when approvals are enabled");
    }
    if (approvals == null && approvalGate != null) {
      throw new IllegalArgumentException(
          "approvalGate must be null when approvals are not configured");
    }
  }

  private ToolLoopPolicy policy() {
    return Objects.requireNonNull(
        policySupplier.get(), "effective tool loop policy must not be null");
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

  /**
   * The iteration a finished round of tool calls left ready to start, together with how its model
   * call is made — a resumed approval starts iteration 0 through the run's first model call, while
   * every later iteration continues through {@code nextStream}.
   */
  private record PendingIteration(
      int index, ModelRequest request, Supplier<Flow.Publisher<ModelResponseUpdate>> source) {}

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
    private TelemetryOperation agentRunOp;

    private LoopSubscription(Flow.Subscriber<? super AgentResponseUpdate> downstream) {
      this.downstream = downstream;
    }

    private void start() {
      if (cancelled || terminated.get()) {
        return;
      }
      // Open the agent-run telemetry operation. The operation mirrors the ordinary terminal:
      // closed on success and failed with the unwrapped cause on failure or cancellation.
      agentRunOp =
          telemetrySink.start(
              TelemetryStart.builder(TelemetryOperationKind.AGENT_RUN, "agent.run")
                  .attribute(TelemetryAttributes.AGENT_ID, identity.agentId())
                  .attribute(
                      TelemetryAttributes.AGENT_NAME,
                      identity.authorName() != null ? identity.authorName() : "")
                  .build());
      ordinaryTerminal.whenComplete(
          (response, err) -> {
            if (err != null) {
              agentRunOp.fail(unwrap(err));
            } else {
              agentRunOp.close();
            }
          });
      Runnable removeListener = request.cancellationSignal().onCancel(this::cancel);
      removeCancellationListener = removeListener;
      if (cancelled || terminated.get()) {
        removeListener.run();
        return;
      }
      advance(RunPhase.LOAD_SESSION);
      advance(RunPhase.PREPARE_CONTEXT);
      if (approvals == null) {
        beginIteration(0, null, () -> firstStream.apply(List.of()));
        return;
      }
      approvalGate.whenComplete((ignored, gateFailure) -> resolvePending(gateFailure));
    }

    /**
     * Resolves the approval queue this run inherited before its first model call, so a call the
     * caller has just approved runs against the model request this run builds rather than being
     * re-planned by another model call it never made.
     */
    private void resolvePending(Throwable gateFailure) {
      if (gateFailure != null) {
        fail(gateFailure);
        return;
      }
      if (cancelled || terminated.get() || request.cancellationSignal().isCancelled()) {
        return;
      }
      ToolApprovalCoordinator.Plan plan;
      try {
        advance(RunPhase.RESOLVE_APPROVAL);
        plan = approvals.resolvePending();
      } catch (RuntimeException resolveFailure) {
        fail(resolveFailure);
        return;
      }
      Optional<ToolApprovalRequestContent> waitingFor = plan.waitingFor();
      if (waitingFor.isPresent()) {
        waitForApproval(waitingFor.get(), null, null);
        return;
      }
      Optional<List<ToolLoopPolicy.DecidedCall>> decided = plan.decided();
      if (decided.isEmpty()) {
        beginIteration(0, null, () -> firstStream.apply(List.of()));
        return;
      }
      executeResolvedQueue(decided.get());
    }

    /**
     * Executes the calls a fully resolved queue decided and starts the run's first model call with
     * their results appended, so a resumed run is the same single loop as an uninterrupted one
     * rather than a second loop of its own.
     */
    private void executeResolvedQueue(List<ToolLoopPolicy.DecidedCall> decided) {
      CompletionStage<List<Content>> results;
      try {
        advance(RunPhase.EXECUTE_TOOL_BATCH);
        results =
            policy()
                .executeDecidedToolCalls(decided, request, telemetryWrappedInvoker(decided.size()));
      } catch (RuntimeException executionFailure) {
        fail(executionFailure);
        return;
      }
      results.whenComplete((values, toolFailure) -> completeResolvedQueue(values, toolFailure));
    }

    private void completeResolvedQueue(List<Content> results, Throwable toolFailure) {
      if (toolFailure != null) {
        fail(toolFailure);
        return;
      }
      if (cancelled || terminated.get() || request.cancellationSignal().isCancelled()) {
        return;
      }
      try {
        Message toolResults = ToolLoopPolicy.toolResultMessage(results);
        for (Content result : results) {
          queue.add(
              identity.messageUpdate(List.of(ToolLoopPolicy.toolResultMessage(List.of(result)))));
        }
        ordinaryMessages.add(toolResults);
        pending.set(new PendingIteration(0, null, () -> firstStream.apply(List.of(toolResults))));
      } catch (RuntimeException resultFailure) {
        fail(resultFailure);
        return;
      }
      drain();
    }

    /**
     * Ends the run at the approval request the caller has to resolve next.
     *
     * <p>Only the head of the queue is surfaced, so the caller answers one request at a time and
     * every answer is unambiguous. The request is reported through the same update stream and the
     * same accumulated ordinary messages as any other content, which is what makes an ordinary and
     * a streaming run report an identical pending approval.
     */
    private void waitForApproval(
        ToolApprovalRequestContent approvalRequest,
        ModelResponse decision,
        ModelResponse ordinaryResponse) {
      advance(RunPhase.WAIT_APPROVAL);
      Message message = new Message(Role.ASSISTANT, List.of(approvalRequest));
      queue.add(identity.approvalRequestUpdate(message));
      ordinaryMessages.add(message);
      if (decision != null && !decision.metadata().isEmpty()) {
        queue.add(identity.metadataUpdate(decision.metadata()));
      }
      advance(RunPhase.FINALIZE_RESPONSE);
      completeOrdinaryTerminal(approvalTerminal(ordinaryResponse));
      finished = true;
      drain();
    }

    /**
     * The terminal outcome a run that stopped for approval reports: the last model call's
     * continuation, metadata and raw representation where there was one, but always {@code STOP},
     * because the run has stopped and is waiting rather than still calling tools.
     */
    private ModelResponse approvalTerminal(ModelResponse ordinaryResponse) {
      ModelResponse.Builder builder = ModelResponse.builder().messages(List.of());
      if (ordinaryResponse != null) {
        builder
            .continuationToken(ordinaryResponse.continuationToken())
            .metadata(ordinaryResponse.metadata())
            .rawRepresentation(ordinaryResponse.rawRepresentation());
      }
      return builder.finishReason(FinishReason.STOP).build();
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
      ModelSubscriber subscriber = new ModelSubscriber(index, iterationRequest);
      try {
        Flow.Publisher<ModelResponseUpdate> publisher =
            Objects.requireNonNull(source.get(), "model client update publisher must not be null");
        advance(RunPhase.CALL_MODEL);
        publisher.subscribe(subscriber);
      } catch (RuntimeException startFailure) {
        subscriber.failOperation(unwrap(startFailure));
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
        ToolLoopPolicy policy = policy();
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
        // A call to a tool this agent never declared is a broken model response regardless of what
        // else the batch contains: it must fail the whole batch here, before a declaration-only
        // check, an approval decision, or any tool body — not after an executable sibling has
        // already run through executeToolCalls' sequential, in-order execution (CR-1).
        policy.requireAllDeclared(calls);
        if (!policy.canExecuteAll(calls) && policy.declaresAll(calls)) {
          // A declaration-only tool was invoked: it is declared and offered to the model but has
          // no local body, so the run ends with the reassembled response — whose tool-call updates
          // were already emitted — instead of fabricating results the Java core cannot produce
          // (TOOL-006).
          completeRun(response, ordinaryResponse);
          return;
        }
        current =
            iterationRequest == null
                ? Objects.requireNonNull(firstRequest.get(), "model request must not be null")
                : iterationRequest;
        if (approvals != null && policy.canExecuteAll(calls)) {
          ToolApprovalCoordinator.Plan plan = approvals.planBatch(calls);
          Optional<ToolApprovalRequestContent> waitingFor = plan.waitingFor();
          if (waitingFor.isPresent()) {
            waitForApproval(waitingFor.get(), response, ordinaryResponse);
            return;
          }
          advance(RunPhase.EXECUTE_TOOL_BATCH);
          results =
              policy.executeDecidedToolCalls(
                  plan.decided().orElseThrow(), request, telemetryWrappedInvoker(calls.size()));
        } else {
          // Approvals are not configured for this run: every call in the batch was already
          // confirmed declared and executable-or-declaration-only above, so this is the ordinary,
          // approval-free execution path (I-2's fail-closed guarantee is enforced earlier by
          // requireAllDeclared, not by this branch).
          advance(RunPhase.EXECUTE_TOOL_BATCH);
          results = policy.executeToolCalls(calls, request, telemetryWrappedInvoker(calls.size()));
        }
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
            policy().nextRequest(current, response.messages(), calls, toolResults, index);
        ordinaryMessages.add(toolResults);
        queue.addAll(updates);
        pending.set(new PendingIteration(index + 1, next, () -> nextStream.apply(next)));
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
     * Wraps the shared tool invoker with a telemetry operation per call. Tools within a batch run
     * sequentially in call order, so the counter increments safely without additional locking.
     */
    private ToolLoopPolicy.BoundToolInvoker telemetryWrappedInvoker(int batchSize) {
      AtomicInteger callIndex = new AtomicInteger(0);
      return (tool, call, ctx) -> {
        int idx = callIndex.getAndIncrement();
        TelemetryOperation toolOp =
            telemetrySink.start(
                TelemetryStart.builder(TelemetryOperationKind.TOOL_CALL, "tool.call")
                    .attribute(TelemetryAttributes.TOOL_NAME, call.name())
                    .attribute(TelemetryAttributes.TOOL_CALL_COUNT, (long) batchSize)
                    .attribute(TelemetryAttributes.TOOL_CALL_INDEX, (long) idx)
                    .build());
        return toolInvoker
            .invoke(tool, call, ctx)
            .whenComplete(
                (result, err) -> {
                  if (err != null) {
                    toolOp.fail(unwrap(err));
                  } else {
                    toolOp.close();
                  }
                });
      };
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
      beginIteration(next.index(), next.request(), next.source());
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
      private final TelemetryOperation modelCallOp;
      private final StreamingModelResponseAccumulator accumulator =
          new StreamingModelResponseAccumulator(identity);
      private final List<ModelResponseUpdate> rawUpdates = new ArrayList<>();
      private volatile boolean done;

      private ModelSubscriber(int index, ModelRequest iterationRequest) {
        this.index = index;
        this.iterationRequest = iterationRequest;
        // Open the model-call telemetry operation here rather than as a local variable in
        // beginIteration, so its lifecycle is owned by this subscriber instance.
        this.modelCallOp =
            telemetrySink.start(
                TelemetryStart.builder(TelemetryOperationKind.MODEL_CALL, "model.call")
                    .attribute(TelemetryAttributes.MODEL_ITERATION, (long) index)
                    .build());
      }

      /** Fails the model call operation; called from beginIteration when source.get() throws. */
      void failOperation(Throwable cause) {
        modelCallOp.fail(cause);
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
          modelCallOp.fail(unwrap(recordFailure));
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
        modelCallOp.fail(unwrap(throwable));
        fail(throwable);
      }

      @Override
      public void onComplete() {
        if (done) {
          return;
        }
        done = true;
        modelCallOp.close();
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
