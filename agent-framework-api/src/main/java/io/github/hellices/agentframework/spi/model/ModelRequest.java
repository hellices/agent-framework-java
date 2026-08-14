package io.github.hellices.agentframework.spi.model;

import io.github.hellices.agentframework.api.message.Message;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ModelRequest(List<Message> messages, Map<String, Object> options, Map<String, Object> metadata) {

  public ModelRequest {
    messages = messages == null ? List.of() : List.copyOf(messages);
    options = options == null ? Map.of() : Collections.unmodifiableMap(new java.util.HashMap<>(options));
    metadata = metadata == null ? Map.of() : Collections.unmodifiableMap(new java.util.HashMap<>(metadata));
    for (Message message : messages) {
      Objects.requireNonNull(message, "messages must not contain null entries");
    }
  }
}
