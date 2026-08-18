package io.github.hellices.agentframework.api.tool;

/**
 * Decides whether a pending tool call must be surfaced to the caller for approval, is automatically
 * approved, or is automatically denied (TOOL-019, TOOL-021).
 *
 * <p>A policy is a pure function of {@link ToolApprovalContext}: it carries no run state of its
 * own, so the same instance can serve every concurrent run. Standing approval rules, the mandatory
 * precedence over heuristic auto-approval, and the auto-approval-chain upper bound are composition
 * concerns for whoever assembles a policy, not obligations this interface enforces.
 */
public interface ToolApprovalPolicy {

  ToolApprovalDecision evaluate(ToolApprovalContext context);
}
