package io.github.hellices.agentframework.spi.model;

import io.github.hellices.agentframework.api.agent.CancellationSignal;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.tool.ToolDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ModelRequest(
    List<Message> messages,
    ModelRequestOptions options,
    CancellationSignal cancellationSignal,
    List<ToolDefinition> tools,
    Map<String, Object> metadata) {

  public ModelRequest {
    messages = messages == null ? List.of() : List.copyOf(messages);
    options = options == null ? ModelRequestOptions.empty() : options;
    cancellationSignal = cancellationSignal == null ? new CancellationSignal() : cancellationSignal;
    List<ToolDefinition> normalizedTools = new ArrayList<>();
    if (tools != null) {
      for (ToolDefinition tool : tools) {
        normalizedTools.add(Objects.requireNonNull(tool, "tools must not contain null entries"));
      }
    }
    tools = List.copyOf(normalizedTools);
    metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    for (Message message : messages) {
      Objects.requireNonNull(message, "messages must not contain null entries");
    }
  }

  public ModelRequest(
      List<Message> messages, ModelRequestOptions options, Map<String, Object> metadata) {
    this(messages, options, new CancellationSignal(), List.of(), metadata);
  }

  public ModelRequest(
      List<Message> messages,
      ModelRequestOptions options,
      CancellationSignal cancellationSignal,
      Map<String, Object> metadata) {
    this(messages, options, cancellationSignal, List.of(), metadata);
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
        && tools.equals(that.tools)
        && metadata.equals(that.metadata);
  }

  @Override
  public int hashCode() {
    return Objects.hash(messages, options, tools, metadata);
  }
}
