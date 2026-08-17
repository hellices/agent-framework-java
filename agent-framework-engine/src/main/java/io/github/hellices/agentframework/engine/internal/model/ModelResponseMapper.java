package io.github.hellices.agentframework.engine.internal.model;

import io.github.hellices.agentframework.api.agent.AgentResponse;
import io.github.hellices.agentframework.api.agent.AgentResponseUpdate;
import io.github.hellices.agentframework.api.value.JsonObject;
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
    return AgentResponse.builder()
        .agentId(agentId)
        .responseId(responseId)
        .authorName(authorName)
        .createdAt(createdAt)
        .finishReason(value.finishReason())
        .continuationToken(value.continuationToken())
        .messages(value.messages())
        .usage(value.usage())
        .additionalProperties(metadata(value.metadata()))
        .rawRepresentation(value.rawRepresentation())
        .build();
  }

  public static AgentResponseUpdate toAgentResponseUpdate(
      String agentId,
      String responseId,
      String authorName,
      Instant createdAt,
      ModelResponseUpdate update) {
    ModelResponseUpdate value =
        Objects.requireNonNull(update, "model response update must not be null");
    return AgentResponseUpdate.builder()
        .agentId(agentId)
        .responseId(responseId)
        .authorName(authorName)
        .createdAt(createdAt)
        .finishReason(value.finishReason())
        .continuationToken(value.continuationToken())
        .messages(value.messages())
        .usage(value.usage())
        .additionalProperties(metadata(value.metadata()))
        .rawRepresentation(value.rawRepresentation())
        .build();
  }

  private static JsonObject metadata(JsonObject metadata) {
    return metadata == null ? JsonObject.empty() : metadata;
  }
}
