package io.github.hellices.agentframework.spi.model;

import io.github.hellices.agentframework.api.message.Message;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ModelRequest(
    List<Message> messages, ModelRequestOptions options, Map<String, Object> metadata) {

  public ModelRequest {
    messages = messages == null ? List.of() : List.copyOf(messages);
    options = options == null ? ModelRequestOptions.empty() : options;
    metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    for (Message message : messages) {
      Objects.requireNonNull(message, "messages must not contain null entries");
    }
  }

  public static ModelRequest fromLegacyOptions(
      List<Message> messages, Map<String, Object> legacyOptions, Map<String, Object> metadata) {
    return new ModelRequest(messages, ModelRequestOptions.fromLegacyOptions(legacyOptions), metadata);
  }
}
