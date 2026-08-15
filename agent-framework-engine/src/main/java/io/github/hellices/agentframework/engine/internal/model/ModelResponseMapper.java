package io.github.hellices.agentframework.engine.internal.model;

import io.github.hellices.agentframework.api.agent.AgentResponse;
import io.github.hellices.agentframework.api.agent.AgentResponseUpdate;
import io.github.hellices.agentframework.spi.model.ModelResponse;
import io.github.hellices.agentframework.spi.model.ModelResponseUpdate;
import java.time.Instant;
import java.util.Objects;

public final class ModelResponseMapper {

  private ModelResponseMapper() {}

  public static AgentResponse toAgentResponse(
      String agentId,
      String responseId,
      String authorName,
      Instant createdAt,
      ModelResponse response) {
    ModelResponse value = Objects.requireNonNull(response, "model response must not be null");
    return new AgentResponse(
        agentId,
        responseId,
        null,
        authorName,
        createdAt,
        value.finishReason(),
        value.continuationToken(),
        value.messages(),
        value.usage(),
        value.metadata(),
        value.rawRepresentation());
  }

  public static AgentResponseUpdate toAgentResponseUpdate(
      String agentId,
      String responseId,
      String authorName,
      Instant createdAt,
      ModelResponseUpdate update) {
    ModelResponseUpdate value =
        Objects.requireNonNull(update, "model response update must not be null");
    return new AgentResponseUpdate(
        agentId,
        responseId,
        null,
        authorName,
        createdAt,
        value.finishReason(),
        value.continuationToken(),
        value.messages(),
        value.usage(),
        value.metadata(),
        value.rawRepresentation());
  }
}
