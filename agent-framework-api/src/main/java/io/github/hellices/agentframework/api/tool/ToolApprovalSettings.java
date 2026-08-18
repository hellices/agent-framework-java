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
    if (matchesStandingApproval(context)) {
      return ToolApprovalDecision.APPROVE;
    }
    ToolApprovalDecision decision = policy.evaluate(context);
    return decision == null ? ToolApprovalDecision.REQUIRE_APPROVAL : decision;
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
