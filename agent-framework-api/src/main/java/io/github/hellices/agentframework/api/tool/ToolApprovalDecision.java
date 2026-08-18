package io.github.hellices.agentframework.api.tool;

/**
 * The three outcomes a {@link ToolApprovalPolicy} may return for a pending tool call.
 *
 * <p>{@code REQUIRE_APPROVAL} surfaces the call as a {@link
 * io.github.hellices.agentframework.api.message.ToolApprovalRequestContent} user-input-request
 * instead of running it. {@code APPROVE} and {@code DENY} resolve the call automatically — for
 * example from a standing approval rule or a heuristic auto-approval callback — without a caller
 * round trip, subject to the mandatory approval-chain and iteration limits the policy cannot
 * disable (TOOL-021).
 */
public enum ToolApprovalDecision {
  REQUIRE_APPROVAL,
  APPROVE,
  DENY
}
