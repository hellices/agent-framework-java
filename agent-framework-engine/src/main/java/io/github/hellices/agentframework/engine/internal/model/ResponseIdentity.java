package io.github.hellices.agentframework.engine.internal.model;

import io.github.hellices.agentframework.api.agent.AgentResponseUpdate;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.value.JsonObject;
import io.github.hellices.agentframework.api.value.JsonValues;
import io.github.hellices.agentframework.spi.model.ModelResponseUpdate;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
    return new AgentResponseUpdate(
        agentId,
        responseId,
        null,
        authorName,
        createdAt,
        null,
        null,
        messages,
        null,
        Map.of(),
        null);
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
    return new AgentResponseUpdate(
        agentId,
        responseId,
        null,
        authorName,
        createdAt,
        null,
        null,
        List.of(),
        null,
        metadata(metadata),
        null);
  }

  private static Map<String, Object> metadata(JsonObject metadata) {
    if (metadata == null || metadata.isEmpty()) {
      return Map.of();
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> converted = (Map<String, Object>) JsonValues.toJava(metadata);
    return converted;
  }
}
