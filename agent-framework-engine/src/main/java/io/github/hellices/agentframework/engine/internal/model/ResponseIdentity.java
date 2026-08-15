package io.github.hellices.agentframework.engine.internal.model;

import io.github.hellices.agentframework.api.agent.AgentResponseUpdate;
import io.github.hellices.agentframework.api.message.Message;
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
}
