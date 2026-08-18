package io.github.hellices.agentframework.engine.internal.tool;

import io.github.hellices.agentframework.api.message.ToolApprovalRequestContent;
import io.github.hellices.agentframework.api.session.SessionStateKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The durable, session-scoped queue of approval requests the tool loop has surfaced for one session
 * (TOOL-020).
 *
 * <p>This is a data-only value: it holds requests in the order they were surfaced (its head is the
 * request the caller sees next) and offers only immutable append/resolve operations. A resolved
 * entry keeps its request and records the caller's decision rather than dropping out of the queue,
 * because TOOL-020 requires the whole batch to stay unexecuted until its last approval is resolved
 * — the decisions have to survive the runs that resolve the entries before them. Deciding when to
 * append, when to resolve, and how that interacts with the model and tool loop is the run
 * pipeline's responsibility, not this type's — this class only fixes what is persisted and how.
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

  private final List<Entry> entries;

  public ToolApprovalQueueState(List<ToolApprovalRequestContent> pending) {
    this(toEntries(pending));
  }

  private ToolApprovalQueueState(Entry[] entries) {
    List<Entry> normalized = new ArrayList<>(entries.length);
    for (Entry entry : entries) {
      normalized.add(Objects.requireNonNull(entry, "entries must not contain null entries"));
    }
    this.entries = List.copyOf(normalized);
  }

  /** Returns the shared empty queue. */
  public static ToolApprovalQueueState empty() {
    return EMPTY;
  }

  /** Returns a queue holding an ordered copy of {@code pending}, none of it resolved yet. */
  public static ToolApprovalQueueState of(List<ToolApprovalRequestContent> pending) {
    Objects.requireNonNull(pending, "pending must not be null");
    return new ToolApprovalQueueState(pending);
  }

  /** Returns a queue holding an ordered copy of {@code entries}, decisions included. */
  public static ToolApprovalQueueState ofEntries(List<Entry> entries) {
    Objects.requireNonNull(entries, "entries must not be null");
    return new ToolApprovalQueueState(entries.toArray(new Entry[0]));
  }

  /** Every queued entry, resolved or not, in the order the requests were surfaced. */
  public List<Entry> entries() {
    return entries;
  }

  /** The requests still awaiting a decision, in the order they were surfaced. */
  public List<ToolApprovalRequestContent> pending() {
    List<ToolApprovalRequestContent> unresolved = new ArrayList<>(entries.size());
    for (Entry entry : entries) {
      if (entry.decision().isEmpty()) {
        unresolved.add(entry.request());
      }
    }
    return List.copyOf(unresolved);
  }

  /** The request the caller is expected to resolve next, or empty when nothing is awaiting one. */
  public Optional<ToolApprovalRequestContent> head() {
    for (Entry entry : entries) {
      if (entry.decision().isEmpty()) {
        return Optional.of(entry.request());
      }
    }
    return Optional.empty();
  }

  /** Whether every queued request has been decided, so the batch may execute. */
  public boolean fullyResolved() {
    return head().isEmpty();
  }

  /** Whether this queue holds no entry at all. */
  public boolean isEmpty() {
    return entries.isEmpty();
  }

  /** Returns a new queue with {@code request} appended, awaiting a decision. */
  public ToolApprovalQueueState append(ToolApprovalRequestContent request) {
    return append(request, null);
  }

  /**
   * Returns a new queue with {@code request} appended, already carrying {@code decision} when the
   * call was resolved without asking the caller.
   *
   * <p>An already decided call still has to be queued whenever any sibling of its batch is
   * unresolved, because TOOL-020 holds the whole batch until its last decision and the decision
   * would otherwise be lost with the run that made it.
   */
  public ToolApprovalQueueState append(ToolApprovalRequestContent request, Boolean decision) {
    Objects.requireNonNull(request, "request must not be null");
    List<Entry> next = new ArrayList<>(entries.size() + 1);
    next.addAll(entries);
    next.add(new Entry(request, decision));
    return ofEntries(next);
  }

  /**
   * Returns a new queue with the head request recorded as approved or denied, keeping it in place
   * so the decision is still known once the entries after it are resolved.
   *
   * @throws IllegalStateException if nothing in this queue is awaiting a decision
   */
  public ToolApprovalQueueState resolveHead(boolean approved) {
    List<Entry> next = new ArrayList<>(entries.size());
    boolean resolved = false;
    for (Entry entry : entries) {
      if (!resolved && entry.decision().isEmpty()) {
        next.add(new Entry(entry.request(), approved));
        resolved = true;
      } else {
        next.add(entry);
      }
    }
    if (!resolved) {
      throw new IllegalStateException("approval queue has no request awaiting a decision");
    }
    return ofEntries(next);
  }

  private static Entry[] toEntries(List<ToolApprovalRequestContent> pending) {
    Objects.requireNonNull(pending, "pending must not be null");
    Entry[] normalized = new Entry[pending.size()];
    for (int index = 0; index < pending.size(); index++) {
      normalized[index] =
          new Entry(
              Objects.requireNonNull(pending.get(index), "pending must not contain null entries"),
              null);
    }
    return normalized;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof ToolApprovalQueueState that && entries.equals(that.entries);
  }

  @Override
  public int hashCode() {
    return entries.hashCode();
  }

  @Override
  public String toString() {
    return "ToolApprovalQueueState[entries=" + entries + "]";
  }

  /** One queued approval request together with the caller decision it has received, if any. */
  public static final class Entry {

    private final ToolApprovalRequestContent request;
    private final Boolean decision;

    public Entry(ToolApprovalRequestContent request, Boolean decision) {
      this.request = immutableCopy(Objects.requireNonNull(request, "request must not be null"));
      this.decision = decision;
    }

    // Copies at both construction and every read, matching the established repo-wide convention
    // for a Content value that carries an unconstrained rawRepresentation (see, for example,
    // ToolInvocation and ToolLoopPolicy.DecidedCall): the copy is what defends this field against
    // SpotBugs' EI_EXPOSE_REP / EI_EXPOSE_REP2 without a blanket suppression, since the content
    // itself has no exposed mutable state to defend beyond that field.
    private static ToolApprovalRequestContent immutableCopy(ToolApprovalRequestContent request) {
      return new ToolApprovalRequestContent(
          request.requestId(),
          request.toolCallId(),
          request.toolName(),
          request.arguments(),
          request.hostBoundary().orElse(null),
          request.additionalProperties(),
          request.rawRepresentation());
    }

    public ToolApprovalRequestContent request() {
      return immutableCopy(request);
    }

    /** The caller decision, or empty while the request is still awaiting one. */
    public Optional<Boolean> decision() {
      return Optional.ofNullable(decision);
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Entry that
          && request.equals(that.request)
          && Objects.equals(decision, that.decision);
    }

    @Override
    public int hashCode() {
      return Objects.hash(request, decision);
    }

    @Override
    public String toString() {
      return "Entry[request=" + request + ", decision=" + decision + "]";
    }
  }
}
