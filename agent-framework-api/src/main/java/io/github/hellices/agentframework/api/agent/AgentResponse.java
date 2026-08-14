package io.github.hellices.agentframework.api.agent;

import io.github.hellices.agentframework.api.message.FinishReason;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.Usage;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record AgentResponse(
    String agentId,
    String responseId,
    String messageId,
    String authorName,
    Instant createdAt,
    FinishReason finishReason,
    List<Message> messages,
    Usage usage,
    Map<String, Object> additionalProperties,
    Object rawRepresentation) {

  public AgentResponse {
    Objects.requireNonNull(agentId, "agentId must not be null");
    Objects.requireNonNull(responseId, "responseId must not be null");
    Objects.requireNonNull(finishReason, "finishReason must not be null");
    messages = messages == null ? List.of() : List.copyOf(messages);
    additionalProperties = additionalProperties == null ? Map.of() : Collections.unmodifiableMap(new java.util.HashMap<>(additionalProperties));
  }

  public String text() {
    StringBuilder builder = new StringBuilder();
    for (Message message : messages) {
      builder.append(message.text());
    }
    return builder.toString();
  }
}
