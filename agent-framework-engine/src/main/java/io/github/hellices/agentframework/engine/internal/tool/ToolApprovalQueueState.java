package io.github.hellices.agentframework.engine.internal.tool;

import io.github.hellices.agentframework.api.message.ToolApprovalRequestContent;
import io.github.hellices.agentframework.api.session.SessionStateKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The durable, session-scoped queue of not-yet-resolved {@link ToolApprovalRequestContent} the tool
 * loop has surfaced for one session (TOOL-020).
 *
 * <p>This is a data-only value: it holds pending requests in the order they were surfaced (its head
 * is the request the caller sees next) and offers only immutable append/advance operations.
 * Deciding when to append, when to advance past the head, and how that interacts with the model and
 * tool loop is the run pipeline's responsibility, not this type's — this class only fixes what is
 * persisted and how.
 *
 * <p>It is stored under {@link #STATE_KEY}, a state key reserved by the engine so that every
 * session's approval queue lives at the same well-known location regardless of which agent or
 * context providers are configured.
 */
public final class ToolApprovalQueueState {

  /** The reserved session-state id the approval queue is persisted under. */
  public static final String STATE_ID = "engine.tool_approval_queue";

  /** The reserved, typed session-state key the approval queue is persisted under. */
  public static final SessionStateKey<ToolApprovalQueueState> STATE_KEY =
      SessionStateKey.of(STATE_ID, ToolApprovalQueueState.class);

  private static final ToolApprovalQueueState EMPTY = new ToolApprovalQueueState(List.of());

  private final List<ToolApprovalRequestContent> pending;

  public ToolApprovalQueueState(List<ToolApprovalRequestContent> pending) {
    Objects.requireNonNull(pending, "pending must not be null");
    List<ToolApprovalRequestContent> normalized = new ArrayList<>(pending.size());
    for (ToolApprovalRequestContent request : pending) {
      normalized.add(Objects.requireNonNull(request, "pending must not contain null entries"));
    }
    this.pending = List.copyOf(normalized);
  }

  /** Returns the shared empty queue. */
  public static ToolApprovalQueueState empty() {
    return EMPTY;
  }

  /** Returns a queue holding an ordered copy of {@code pending}. */
  public static ToolApprovalQueueState of(List<ToolApprovalRequestContent> pending) {
    Objects.requireNonNull(pending, "pending must not be null");
    return new ToolApprovalQueueState(pending);
  }

  /** The pending requests, in the order they were surfaced. */
  public List<ToolApprovalRequestContent> pending() {
    return pending;
  }

  /** The request the caller is expected to resolve next, or empty when the queue is empty. */
  public Optional<ToolApprovalRequestContent> head() {
    return pending.isEmpty() ? Optional.empty() : Optional.of(pending.get(0));
  }

  /** Returns a new queue with {@code request} appended after this queue's pending requests. */
  public ToolApprovalQueueState append(ToolApprovalRequestContent request) {
    Objects.requireNonNull(request, "request must not be null");
    List<ToolApprovalRequestContent> next = new ArrayList<>(pending.size() + 1);
    next.addAll(pending);
    next.add(request);
    return new ToolApprovalQueueState(next);
  }

  /**
   * Returns a new queue with the head request removed, once it has been resolved.
   *
   * @throws IllegalStateException if this queue is empty
   */
  public ToolApprovalQueueState resolveHead() {
    if (pending.isEmpty()) {
      throw new IllegalStateException("approval queue is empty");
    }
    return new ToolApprovalQueueState(pending.subList(1, pending.size()));
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof ToolApprovalQueueState that && pending.equals(that.pending);
  }

  @Override
  public int hashCode() {
    return pending.hashCode();
  }

  @Override
  public String toString() {
    return "ToolApprovalQueueState[pending=" + pending + "]";
  }
}
