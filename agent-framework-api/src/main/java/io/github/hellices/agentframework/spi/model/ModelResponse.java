package io.github.hellices.agentframework.spi.model;

import io.github.hellices.agentframework.api.message.FinishReason;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.Usage;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ModelResponse(
    List<Message> messages,
    Usage usage,
    FinishReason finishReason,
    Map<String, Object> metadata,
    Object rawRepresentation) {

  public ModelResponse {
    messages = messages == null ? List.of() : List.copyOf(messages);
    metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    Objects.requireNonNull(finishReason, "finishReason must not be null");
  }
}
