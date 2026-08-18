package io.github.hellices.agentframework.api.tool;

import io.github.hellices.agentframework.api.message.ToolCallContent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * The approval configuration one agent runs under: the standing approvals the caller granted, the
 * heuristic policy consulted for everything else, the upper bound on automatically approved calls
 * in a single run, and the seam that names the host boundary a call would run on.
 *
 * <p>Evaluation order is fixed (TOOL-021): a matching standing approval approves the call and the
 * policy is not consulted at all, so a policy can never revoke an approval the caller already
 * granted. Only when no standing approval matches does the policy decide, and only a policy
 * approval counts against {@link #maxAutomaticApprovals()}. When that bound is exhausted the call
 * is surfaced for approval instead, identically for an ordinary and a streaming run.
 *
 * <p>The default configuration requires approval for every tool call. An agent that configures no
 * settings at all is not subject to approval and runs its tools directly.
 */
public final class ToolApprovalSettings {

  /** The default upper bound on automatically approved calls within one run. */
  public static final int DEFAULT_MAX_AUTOMATIC_APPROVALS = 10;

  private static final ToolApprovalPolicy REQUIRE_APPROVAL =
      context -> ToolApprovalDecision.REQUIRE_APPROVAL;

  private final List<ToolApprovalRule> standingApprovals;
  private final ToolApprovalPolicy policy;
  private final int maxAutomaticApprovals;
  private final Function<ToolCallContent, String> hostBoundaryResolver;

  private ToolApprovalSettings(Builder builder) {
    this.standingApprovals = List.copyOf(builder.standingApprovals);
    this.policy = builder.policy == null ? REQUIRE_APPROVAL : builder.policy;
    this.maxAutomaticApprovals = builder.maxAutomaticApprovals;
    this.hostBoundaryResolver =
        builder.hostBoundaryResolver == null ? call -> null : builder.hostBoundaryResolver;
  }

  public static Builder builder() {
    return new Builder();
  }

  public List<ToolApprovalRule> standingApprovals() {
    return standingApprovals;
  }

  public ToolApprovalPolicy policy() {
    return policy;
  }

  /**
   * The upper bound on automatically approved calls this configuration allows within a single run.
   *
   * <p>This is a per-run allowance, not a lifetime one: the count it bounds is kept by the run's
   * own {@code ToolApprovalCoordinator} and starts fresh at zero every time an agent starts a run,
   * including a caller resuming a run that previously stopped to ask for approval. A caller cannot
   * exhaust this allowance across separate runs, and resuming a paused run does not inherit
   * whatever count the run had already spent before it stopped to wait.
   */
  public int maxAutomaticApprovals() {
    return maxAutomaticApprovals;
  }

  /** The host boundary {@code call} would run on, such as an MCP server label, or {@code null}. */
  public String hostBoundary(ToolCallContent call) {
    return hostBoundaryResolver.apply(Objects.requireNonNull(call, "call must not be null"));
  }

  /** Whether a standing approval covers the pending call {@code context} describes. */
  public boolean matchesStandingApproval(ToolApprovalContext context) {
    Objects.requireNonNull(context, "context must not be null");
    for (ToolApprovalRule rule : standingApprovals) {
      if (rule.matches(context)) {
        return true;
      }
    }
    return false;
  }

  /**
   * The decision for the pending call {@code context} describes, applying standing approvals before
   * the heuristic policy is consulted.
   */
  public ToolApprovalDecision evaluate(ToolApprovalContext context) {
    return evaluateWithOrigin(context).decision();
  }

  /**
   * The decision for the pending call {@code context} describes, together with which rule produced
   * it (TOOL-021).
   *
   * <p>This is the one place that implements the fixed precedence order: a matching standing
   * approval decides {@code context} and the policy is never consulted, otherwise the policy
   * decides. {@link #evaluate(ToolApprovalContext)} is defined in terms of this method rather than
   * duplicating the ordering, and a caller that also needs to tell a standing approval apart from a
   * policy approval — such as one enforcing {@link #maxAutomaticApprovals()} only against policy
   * approvals — can read {@link Evaluation#origin()} from a single evaluation instead of scanning
   * {@link #matchesStandingApproval(ToolApprovalContext)} a second time to reclassify a decision it
   * already obtained.
   */
  public Evaluation evaluateWithOrigin(ToolApprovalContext context) {
    Objects.requireNonNull(context, "context must not be null");
    if (matchesStandingApproval(context)) {
      return new Evaluation(ToolApprovalDecision.APPROVE, Evaluation.Origin.STANDING_APPROVAL);
    }
    ToolApprovalDecision decision = policy.evaluate(context);
    decision = decision == null ? ToolApprovalDecision.REQUIRE_APPROVAL : decision;
    return new Evaluation(decision, Evaluation.Origin.POLICY);
  }

  /**
   * A decision from {@link #evaluateWithOrigin(ToolApprovalContext)} together with which rule
   * produced it.
   */
  public static final class Evaluation {

    private final ToolApprovalDecision decision;
    private final Origin origin;

    private Evaluation(ToolApprovalDecision decision, Origin origin) {
      this.decision = Objects.requireNonNull(decision, "decision must not be null");
      this.origin = Objects.requireNonNull(origin, "origin must not be null");
    }

    /** The decision {@link #evaluate(ToolApprovalContext)} would also return. */
    public ToolApprovalDecision decision() {
      return decision;
    }

    /** Which rule produced {@link #decision()}. */
    public Origin origin() {
      return origin;
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Evaluation that && decision == that.decision && origin == that.origin;
    }

    @Override
    public int hashCode() {
      return Objects.hash(decision, origin);
    }

    @Override
    public String toString() {
      return "Evaluation[decision=" + decision + ", origin=" + origin + "]";
    }

    /** Which rule in the fixed TOOL-021 precedence order produced an {@link Evaluation}. */
    public enum Origin {
      /** A matching standing approval decided the call; the policy was not consulted. */
      STANDING_APPROVAL,
      /** No standing approval matched, so the heuristic policy decided the call. */
      POLICY
    }
  }

  public static final class Builder {

    private final List<ToolApprovalRule> standingApprovals = new ArrayList<>();
    private ToolApprovalPolicy policy;
    private int maxAutomaticApprovals = DEFAULT_MAX_AUTOMATIC_APPROVALS;
    private Function<ToolCallContent, String> hostBoundaryResolver;

    private Builder() {}

    public Builder standingApproval(ToolApprovalRule rule) {
      standingApprovals.add(Objects.requireNonNull(rule, "rule must not be null"));
      return this;
    }

    public Builder standingApprovals(List<ToolApprovalRule> rules) {
      standingApprovals.clear();
      for (ToolApprovalRule rule : Objects.requireNonNull(rules, "rules must not be null")) {
        standingApproval(rule);
      }
      return this;
    }

    public Builder policy(ToolApprovalPolicy policy) {
      this.policy = policy;
      return this;
    }

    public Builder maxAutomaticApprovals(int maxAutomaticApprovals) {
      if (maxAutomaticApprovals < 0) {
        throw new IllegalArgumentException("maxAutomaticApprovals must not be negative");
      }
      this.maxAutomaticApprovals = maxAutomaticApprovals;
      return this;
    }

    public Builder hostBoundaryResolver(Function<ToolCallContent, String> hostBoundaryResolver) {
      this.hostBoundaryResolver = hostBoundaryResolver;
      return this;
    }

    public ToolApprovalSettings build() {
      return new ToolApprovalSettings(this);
    }
  }
}
