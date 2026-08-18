package io.github.hellices.agentframework.engine.internal.model;

import io.github.hellices.agentframework.api.agent.AgentResponseUpdate;
import io.github.hellices.agentframework.api.message.Content;
import io.github.hellices.agentframework.api.message.FinishReason;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.ToolApprovalRequestContent;
import io.github.hellices.agentframework.api.value.JsonObject;
import io.github.hellices.agentframework.spi.model.ModelResponseUpdate;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The one agent response every update of a single run belongs to.
 *
 * <p>A streaming run reports several model calls and its own tool results through one update
 * stream, and {@link io.github.hellices.agentframework.api.agent.AgentResponse#fromUpdates} only
 * assembles updates that agree on agent and response id. Resolving the identity once, when the run
 * starts, and building every update through it is what keeps a multi-iteration streaming run a
 * single response rather than one response per model call.
 *
 * @param agentId the running agent's id
 * @param responseId the id of the response this run produces
 * @param authorName the running agent's name, or {@code null} when it has none
 * @param createdAt when the run started
 */
public record ResponseIdentity(
    String agentId, String responseId, String authorName, Instant createdAt) {

  public ResponseIdentity {
    Objects.requireNonNull(agentId, "agentId must not be null");
    Objects.requireNonNull(responseId, "responseId must not be null");
  }

  /** Maps a model update onto this response. */
  public AgentResponseUpdate map(ModelResponseUpdate update) {
    return ModelResponseMapper.toAgentResponseUpdate(
        agentId, responseId, authorName, createdAt, update);
  }

  /**
   * An update this run produced itself, carrying messages only.
   *
   * <p>It deliberately reports no finish reason, no continuation token, no usage and no metadata:
   * those describe what a model returned, and an update the engine synthesises must not change the
   * assembled response's model-reported outcome.
   */
  public AgentResponseUpdate messageUpdate(List<Message> messages) {
    return AgentResponseUpdate.builder()
        .agentId(agentId)
        .responseId(responseId)
        .authorName(authorName)
        .createdAt(createdAt)
        .messages(messages)
        .additionalProperties(JsonObject.empty())
        .build();
  }

  /**
   * The update that surfaces an approval request the run is waiting on, ending the run's assembled
   * response at {@link io.github.hellices.agentframework.api.message.FinishReason#STOP}.
   *
   * <p>This is the one update the engine synthesises that does report a finish reason. A run that
   * stops to ask for approval has not ended because the model finished a turn — the last model call
   * it made asked for tool calls — so leaving the model's own {@code TOOL_CALLS} as the assembled
   * outcome would tell a caller that tool calls are still in flight when the run has in fact
   * stopped and is waiting for them. Reporting {@code STOP} here is also what makes an ordinary and
   * a streaming run agree, since the ordinary view builds its own terminal response the same way.
   *
   * <p>It also carries a stable {@code messageId} derived from the approval request's own {@link
   * ToolApprovalRequestContent#requestId()} (I-1): {@link
   * io.github.hellices.agentframework.api.agent.AgentResponse#fromUpdates} coalesces consecutive
   * same-role updates that both leave {@code messageId} unset, which would otherwise merge this
   * engine-synthesised message into the model's own preceding assistant tool-call message on the
   * streaming path only — a boundary an ordinary run's directly-built response never collapses.
   * Minting the id from the request's own domain identity, rather than a random value, keeps this
   * update deterministic and reproducible without depending on any provider-supplied identity.
   */
  public AgentResponseUpdate approvalRequestUpdate(Message message) {
    return AgentResponseUpdate.builder()
        .agentId(agentId)
        .responseId(responseId)
        .authorName(authorName)
        .createdAt(createdAt)
        .messageId(approvalMessageId(message))
        .messages(List.of(message))
        .finishReason(FinishReason.STOP)
        .additionalProperties(JsonObject.empty())
        .build();
  }

  /**
   * The {@code messageId} an assembled response's terminal message carries, derived the same way
   * regardless of whether that response was assembled from updates (a streaming run, or an ordinary
   * run reconstructed from a transformed update stream) or read directly from a run's accumulated
   * ordinary messages (an ordinary pass-through run) (IM-1).
   *
   * <p>This is the single place that derivation happens; callers on either path pass their
   * response's own terminal messages here rather than each re-deriving or special-casing an
   * approval message themselves; {@link
   * io.github.hellices.agentframework.api.agent.AgentResponse#fromUpdates} in particular stays
   * generic and never inspects message content for this, since a streaming run's messageId already
   * reaches it through {@link #approvalRequestUpdate}'s update.
   *
   * <p>Today the only message either path ever mints an id for is the approval-wait message, and a
   * run ends the instant it queues one, so at most one message in the whole list ever carries one;
   * every other terminal message yields {@code null} here, exactly as it already does through
   * {@code fromUpdates}.
   *
   * @param messages the response's own messages, in order
   * @return the derived id, or {@code null} when none of {@code messages} is an approval request
   */
  public static String terminalMessageId(List<Message> messages) {
    for (Message message : messages) {
      String id = approvalMessageId(message);
      if (id != null) {
        return id;
      }
    }
    return null;
  }

  private static String approvalMessageId(Message message) {
    for (Content content : message.content()) {
      if (content instanceof ToolApprovalRequestContent request) {
        return "tool-approval:" + request.requestId();
      }
    }
    return null;
  }

  /**
   * The update that reports a run's model metadata, carrying nothing else.
   *
   * <p>{@link io.github.hellices.agentframework.api.agent.AgentResponse#fromUpdates} unions the
   * metadata of every update it assembles, so a multi-call run whose updates each carried their own
   * call's metadata would end with the union of all its model calls — where an ordinary run reports
   * the terminal call's metadata alone. Reporting metadata once, in this update, is what lets the
   * assembled map be exactly that terminal metadata.
   */
  public AgentResponseUpdate metadataUpdate(JsonObject metadata) {
    return AgentResponseUpdate.builder()
        .agentId(agentId)
        .responseId(responseId)
        .authorName(authorName)
        .createdAt(createdAt)
        .messages(List.of())
        .additionalProperties(metadata == null ? JsonObject.empty() : metadata)
        .build();
  }
}
