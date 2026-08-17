package io.github.hellices.agentframework.spi.model;

import io.github.hellices.agentframework.api.agent.CancellationSignal;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.tool.ToolDefinition;
import io.github.hellices.agentframework.api.value.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class ModelRequest {

  private final List<Message> messages;
  private final ModelRequestOptions options;
  private final String continuationToken;
  private final CancellationSignal cancellationSignal;
  private final List<ToolDefinition> tools;
  private final JsonObject metadata;

  private ModelRequest(Builder builder) {
    this.messages = immutableMessages(builder.messages);
    this.options = builder.options == null ? ModelRequestOptions.empty() : builder.options;
    this.continuationToken = builder.continuationToken;
    this.cancellationSignal =
        builder.cancellationSignal == null ? new CancellationSignal() : builder.cancellationSignal;
    this.tools = immutableTools(builder.tools);
    this.metadata = builder.metadata == null ? JsonObject.empty() : builder.metadata;
  }

  public static Builder builder() {
    return new Builder();
  }

  public List<Message> messages() {
    return List.copyOf(messages);
  }

  public ModelRequestOptions options() {
    return options;
  }

  public String continuationToken() {
    return continuationToken;
  }

  public CancellationSignal cancellationSignal() {
    return cancellationSignal;
  }

  public List<ToolDefinition> tools() {
    return List.copyOf(tools);
  }

  public JsonObject metadata() {
    return metadata;
  }

  public Builder toBuilder() {
    return new Builder()
        .messages(messages)
        .options(options)
        .continuationToken(continuationToken)
        .cancellationSignal(cancellationSignal)
        .tools(tools)
        .metadata(metadata);
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
        && Objects.equals(continuationToken, that.continuationToken)
        && tools.equals(that.tools)
        && metadata.equals(that.metadata);
  }

  @Override
  public int hashCode() {
    return Objects.hash(messages, options, continuationToken, tools, metadata);
  }

  public static final class Builder {
    private List<? extends Message> messages = List.of();
    private ModelRequestOptions options = ModelRequestOptions.empty();
    private String continuationToken;
    private CancellationSignal cancellationSignal = new CancellationSignal();
    private List<? extends ToolDefinition> tools = List.of();
    private JsonObject metadata = JsonObject.empty();

    private Builder() {}

    public Builder messages(List<? extends Message> messages) {
      this.messages = messages == null ? List.of() : messages;
      return this;
    }

    public Builder options(ModelRequestOptions options) {
      this.options = options == null ? ModelRequestOptions.empty() : options;
      return this;
    }

    public Builder continuationToken(String continuationToken) {
      this.continuationToken = continuationToken;
      return this;
    }

    public Builder cancellationSignal(CancellationSignal cancellationSignal) {
      this.cancellationSignal =
          cancellationSignal == null ? new CancellationSignal() : cancellationSignal;
      return this;
    }

    public Builder tools(List<? extends ToolDefinition> tools) {
      this.tools = tools == null ? List.of() : tools;
      return this;
    }

    public Builder metadata(JsonObject metadata) {
      this.metadata = metadata == null ? JsonObject.empty() : metadata;
      return this;
    }

    public ModelRequest build() {
      return new ModelRequest(this);
    }
  }

  private static List<Message> immutableMessages(List<? extends Message> source) {
    if (source == null) {
      return List.of();
    }
    List<Message> normalized = new ArrayList<>(source.size());
    for (Message message : source) {
      normalized.add(Objects.requireNonNull(message, "messages must not contain null entries"));
    }
    return List.copyOf(normalized);
  }

  private static List<ToolDefinition> immutableTools(List<? extends ToolDefinition> source) {
    if (source == null) {
      return List.of();
    }
    List<ToolDefinition> normalized = new ArrayList<>(source.size());
    for (ToolDefinition tool : source) {
      normalized.add(Objects.requireNonNull(tool, "tools must not contain null entries"));
    }
    return List.copyOf(normalized);
  }
}
