package io.github.hellices.agentframework.engine.internal.tool;

import io.github.hellices.agentframework.api.agent.AgentRunRequest;
import io.github.hellices.agentframework.api.message.Content;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.ToolApprovalRequestContent;
import io.github.hellices.agentframework.api.message.ToolApprovalResponseContent;
import io.github.hellices.agentframework.api.message.ToolCallContent;
import io.github.hellices.agentframework.api.session.SessionContext;
import io.github.hellices.agentframework.api.tool.ToolApprovalContext;
import io.github.hellices.agentframework.api.tool.ToolApprovalDecision;
import io.github.hellices.agentframework.api.tool.ToolApprovalSettings;
import io.github.hellices.agentframework.spi.session.ProviderSessionState;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The approval state machine of one run: it decides which pending tool calls may execute, which
 * must be surfaced to the caller, and which the caller denied (TOOL-016 through TOOL-021).
 *
 * <p>The queue this reads and writes is session state, so a run that stops to ask for approval and
 * the later run that answers are two views of one decision. Two rules make that safe. A response is
 * bound to the persisted request by request id alone, so a response naming an unknown request
 * resolves nothing and never reaches execution; and execution always rebinds to the call recorded
 * in the persisted request rather than to anything the resuming caller sent alongside its response,
 * so no caller payload can substitute a different tool call for an approved one.
 *
 * <p>Nothing executes while any queued request is unresolved: TOOL-020 requires the whole batch to
 * wait for its last decision, which is why a resolved entry keeps its decision in the queue instead
 * of being dropped as it is answered.
 */
public final class ToolApprovalCoordinator {

  private final ToolApprovalSettings settings;
  private final SessionContext sessionContext;
  private final AgentRunRequest request;

  /**
   * How many policy-driven automatic approvals this run has spent against {@link
   * ToolApprovalSettings#maxAutomaticApprovals()}.
   *
   * <p>One coordinator instance is created per run and is never shared across runs, but a single
   * run's iterations can each resume on a different callback thread as {@link #planBatch} is
   * invoked from successive {@code whenComplete} continuations. {@link AtomicInteger} makes the
   * check against the cap and the following increment one atomic step, so two concurrently resuming
   * iterations can never both observe room under the cap and both spend it — the field itself also
   * carries the same cross-thread visibility a plain {@code volatile} would, without the
   * read-then-write race a bare increment on a volatile field would leave open.
   */
  private final AtomicInteger automaticApprovals = new AtomicInteger();

  public ToolApprovalCoordinator(
      ToolApprovalSettings settings, SessionContext sessionContext, AgentRunRequest request) {
    this.settings = Objects.requireNonNull(settings, "settings must not be null");
    this.sessionContext = Objects.requireNonNull(sessionContext, "sessionContext must not be null");
    this.request = Objects.requireNonNull(request, "request must not be null");
  }

  /**
   * Resolves the queue this run inherited against the approval responses the caller sent, before
   * any model call is made.
   *
   * <p>Responses are matched against the head of the queue and only the head, in queue order: the
   * caller is shown one request at a time, so a response can only ever be about the request it was
   * shown. That is also what keeps a forged or stale response inert — it matches no head, resolves
   * nothing, and the same request is surfaced again.
   */
  public Plan resolvePending() {
    ProviderSessionState<ToolApprovalQueueState> state = queueState();
    ToolApprovalQueueState queue = state.value().orElseGet(ToolApprovalQueueState::empty);
    if (queue.isEmpty()) {
      return Plan.nothingPending();
    }
    for (ToolApprovalResponseContent response : approvalResponses(request.messages())) {
      Optional<ToolApprovalRequestContent> head = queue.head();
      if (head.isPresent() && head.get().isResponseTo(response)) {
        queue = queue.resolveHead(response.approved());
      }
    }
    Optional<ToolApprovalRequestContent> unresolved = queue.head();
    if (unresolved.isPresent()) {
      state.set(queue);
      return Plan.waiting(unresolved.get());
    }
    List<ToolLoopPolicy.DecidedCall> decided = new ArrayList<>(queue.entries().size());
    for (ToolApprovalQueueState.Entry entry : queue.entries()) {
      decided.add(
          new ToolLoopPolicy.DecidedCall(
              toolCall(entry.request()),
              entry
                  .decision()
                  .orElseThrow(
                      () ->
                          new IllegalStateException(
                              "approval queue entry reached resolution without a decision: "
                                  + entry.request().requestId()))));
    }
    state.clear();
    return Plan.resolved(decided);
  }

  /**
   * Decides a batch of tool calls the model just asked for, and surfaces the first call that
   * neither a standing approval nor the policy resolved.
   *
   * <p>The whole batch is decided before any of it runs, and a single unresolved call holds the
   * whole batch: a partially executed batch would let a tool run whose sibling the caller was still
   * being asked about. Deciding is a first pass over {@code calls} alone; the persisted queue is
   * only read, built and written in a second pass, and only when at least one call is unresolved,
   * so the common case — every call decided immediately — never allocates a queue entry's random
   * request id or touches session state at all. When the batch has to wait, every call of it is
   * queued — the ones already decided carrying their decision — so the decisions survive the runs
   * that resolve the rest and the batch still executes in the order the model asked for.
   */
  public Plan planBatch(List<ToolCallContent> calls) {
    List<ToolLoopPolicy.DecidedCall> decided = new ArrayList<>(calls.size());
    List<PendingCall> pending = new ArrayList<>(calls.size());
    boolean anyUnresolved = false;
    for (ToolCallContent call : calls) {
      String hostBoundary = settings.hostBoundary(call);
      ToolApprovalDecision decision =
          decide(new ToolApprovalContext(call.name(), call.arguments(), hostBoundary));
      Boolean resolved =
          switch (decision) {
            case APPROVE -> Boolean.TRUE;
            case DENY -> Boolean.FALSE;
            case REQUIRE_APPROVAL -> null;
          };
      pending.add(new PendingCall(call, hostBoundary, resolved));
      if (resolved == null) {
        anyUnresolved = true;
      } else {
        decided.add(new ToolLoopPolicy.DecidedCall(call, resolved));
      }
    }
    if (!anyUnresolved) {
      return Plan.resolved(decided);
    }
    ProviderSessionState<ToolApprovalQueueState> state = queueState();
    ToolApprovalQueueState queue = state.value().orElseGet(ToolApprovalQueueState::empty);
    for (PendingCall call : pending) {
      queue =
          queue.append(
              new ToolApprovalRequestContent(
                  UUID.randomUUID().toString(),
                  call.call().callId(),
                  call.call().name(),
                  call.call().arguments(),
                  call.hostBoundary()),
              call.resolved());
    }
    state.set(queue);
    return Plan.waiting(
        queue.head().orElseThrow(() -> new IllegalStateException("approval queue lost its head")));
  }

  /** One call's decision, held until the second pass decides whether the queue needs it. */
  private record PendingCall(ToolCallContent call, String hostBoundary, Boolean resolved) {}

  /**
   * Applies the configured evaluation order and the automatic-approval bound.
   *
   * <p>{@link ToolApprovalSettings#evaluateWithOrigin(ToolApprovalContext)} alone owns TOOL-021's
   * fixed precedence — a matching standing approval always wins and the policy is never consulted
   * when one matches — so this coordinator asks it once rather than re-deriving that precedence
   * itself, or scanning the standing rules a second time to reclassify a decision it already has. A
   * standing approval is the caller's own decision and is neither counted nor capped, so the
   * evaluation's {@link ToolApprovalSettings.Evaluation.Origin#STANDING_APPROVAL} tells this
   * coordinator to approve without spending the automatic-approval budget; only an {@link
   * ToolApprovalSettings.Evaluation.Origin#POLICY} approval is automatic in the sense TOOL-021
   * bounds, so once this run has spent its allowance the next such call is surfaced instead — which
   * is what stops an approve-everything policy from driving an unbounded internal re-invocation
   * chain.
   */
  private ToolApprovalDecision decide(ToolApprovalContext context) {
    ToolApprovalSettings.Evaluation evaluation = settings.evaluateWithOrigin(context);
    if (evaluation.decision() != ToolApprovalDecision.APPROVE) {
      return evaluation.decision();
    }
    if (evaluation.origin() == ToolApprovalSettings.Evaluation.Origin.STANDING_APPROVAL) {
      return ToolApprovalDecision.APPROVE;
    }
    return tryConsumeAutomaticApproval()
        ? ToolApprovalDecision.APPROVE
        : ToolApprovalDecision.REQUIRE_APPROVAL;
  }

  /**
   * Atomically checks the automatic-approval cap and, if room remains, spends one slot against it
   * in the same step — so two concurrently resuming iterations can never both observe room under
   * the cap and both spend it.
   */
  private boolean tryConsumeAutomaticApproval() {
    int max = settings.maxAutomaticApprovals();
    int before = automaticApprovals.getAndUpdate(count -> count < max ? count + 1 : count);
    return before < max;
  }

  private ProviderSessionState<ToolApprovalQueueState> queueState() {
    return sessionContext.providerState(ToolApprovalQueueState.STATE_KEY);
  }

  private static ToolCallContent toolCall(ToolApprovalRequestContent approvalRequest) {
    return new ToolCallContent(
        approvalRequest.toolCallId(), approvalRequest.toolName(), approvalRequest.arguments());
  }

  private static List<ToolApprovalResponseContent> approvalResponses(List<Message> messages) {
    List<ToolApprovalResponseContent> responses = new ArrayList<>();
    for (Message message : messages) {
      for (Content content : message.content()) {
        if (content instanceof ToolApprovalResponseContent response) {
          responses.add(response);
        }
      }
    }
    return responses;
  }

  /**
   * What the coordinator decided: nothing is pending, the run must stop and surface {@code
   * request}, or {@code decided} may be executed now.
   */
  public static final class Plan {

    private static final Plan NOTHING_PENDING = new Plan(null, null);

    private final ToolApprovalRequestContent waitingFor;
    private final List<ToolLoopPolicy.DecidedCall> decided;

    private Plan(ToolApprovalRequestContent waitingFor, List<ToolLoopPolicy.DecidedCall> decided) {
      this.waitingFor = waitingFor;
      this.decided = decided;
    }

    static Plan nothingPending() {
      return NOTHING_PENDING;
    }

    static Plan waiting(ToolApprovalRequestContent waitingFor) {
      return new Plan(Objects.requireNonNull(waitingFor, "waitingFor must not be null"), null);
    }

    static Plan resolved(List<ToolLoopPolicy.DecidedCall> decided) {
      return new Plan(null, List.copyOf(decided));
    }

    /** The request the caller must resolve before the run can continue, when there is one. */
    public Optional<ToolApprovalRequestContent> waitingFor() {
      return Optional.ofNullable(waitingFor);
    }

    /** The calls that may execute now, when the queue was fully resolved. */
    public Optional<List<ToolLoopPolicy.DecidedCall>> decided() {
      return Optional.ofNullable(decided);
    }
  }
}
