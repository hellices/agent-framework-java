package io.github.hellices.agentframework.spi.model;

import io.github.hellices.agentframework.api.agent.CancellationSignal;
import io.github.hellices.agentframework.api.message.Message;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ModelRequest(
    List<Message> messages,
    ModelRequestOptions options,
    CancellationSignal cancellationSignal,
    Map<String, Object> metadata) {

  public ModelRequest {
    messages = messages == null ? List.of() : List.copyOf(messages);
    options = options == null ? ModelRequestOptions.empty() : options;
    cancellationSignal = cancellationSignal == null ? new CancellationSignal() : cancellationSignal;
    metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    for (Message message : messages) {
      Objects.requireNonNull(message, "messages must not contain null entries");
    }
  }

  public ModelRequest(
      List<Message> messages, ModelRequestOptions options, Map<String, Object> metadata) {
    this(messages, options, new CancellationSignal(), metadata);
  }

  public static ModelRequest fromLegacyOptions(
      List<Message> messages, Map<String, Object> legacyOptions, Map<String, Object> metadata) {
    return new ModelRequest(
        messages,
        ModelRequestOptions.fromLegacyOptions(legacyOptions),
        new CancellationSignal(),
        metadata);
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof ModelRequest that)) {
      return false;
    }
    return messages.equals(that.messages)
        && options.equals(that.options)
        && metadata.equals(that.metadata);
  }

  @Override
  public int hashCode() {
    return Objects.hash(messages, options, metadata);
  }
}
