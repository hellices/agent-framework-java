package io.github.hellices.agentframework.engine.internal.model;

import io.github.hellices.agentframework.api.agent.AgentResponse;
import io.github.hellices.agentframework.api.agent.AgentResponseUpdate;
import io.github.hellices.agentframework.api.value.JsonObject;
import io.github.hellices.agentframework.api.value.JsonValues;
import io.github.hellices.agentframework.spi.model.ModelResponse;
import io.github.hellices.agentframework.spi.model.ModelResponseUpdate;
import java.time.Instant;
import java.util.Map;
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
        metadata(value.metadata()),
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
        metadata(value.metadata()),
        value.rawRepresentation());
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
