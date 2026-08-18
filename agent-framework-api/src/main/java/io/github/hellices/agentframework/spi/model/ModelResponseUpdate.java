package io.github.hellices.agentframework.spi.model;

import io.github.hellices.agentframework.api.message.FinishReason;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.Usage;
import io.github.hellices.agentframework.api.value.JsonObject;
import java.util.List;
import java.util.Objects;

public final class ModelResponseUpdate {

  private final List<Message> messages;
  private final Usage usage;
  private final FinishReason finishReason;
  private final String continuationToken;
  private final JsonObject metadata;
  private final transient Object rawRepresentation;

  private ModelResponseUpdate(Builder builder) {
    this.messages = builder.messages == null ? List.of() : List.copyOf(builder.messages);
    this.usage = builder.usage;
    this.finishReason =
        Objects.requireNonNull(builder.finishReason, "finishReason must not be null");
    this.continuationToken = builder.continuationToken;
    this.metadata = builder.metadata == null ? JsonObject.empty() : builder.metadata;
    this.rawRepresentation = builder.rawRepresentation;
  }

  public static Builder builder() {
    return new Builder();
  }

  public List<Message> messages() {
    return List.copyOf(messages);
  }

  public Usage usage() {
    return usage;
  }

  public FinishReason finishReason() {
    return finishReason;
  }

  public String continuationToken() {
    return continuationToken;
  }

  public JsonObject metadata() {
    return metadata;
  }

  public Object rawRepresentation() {
    return rawRepresentation;
  }

  public Builder toBuilder() {
    return new Builder()
        .messages(messages)
        .usage(usage)
        .finishReason(finishReason)
        .continuationToken(continuationToken)
        .metadata(metadata)
        .rawRepresentation(rawRepresentation);
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof ModelResponseUpdate that)) {
      return false;
    }
    return messages.equals(that.messages)
        && Objects.equals(usage, that.usage)
        && finishReason == that.finishReason
        && Objects.equals(continuationToken, that.continuationToken)
        && metadata.equals(that.metadata)
        && Objects.equals(rawRepresentation, that.rawRepresentation);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        messages, usage, finishReason, continuationToken, metadata, rawRepresentation);
  }

  public static final class Builder {
    private List<? extends Message> messages = List.of();
    private Usage usage;
    private FinishReason finishReason;
    private String continuationToken;
    private JsonObject metadata = JsonObject.empty();
    private Object rawRepresentation;

    private Builder() {}

    public Builder messages(List<? extends Message> messages) {
      this.messages = messages == null ? List.of() : messages;
      return this;
    }

    public Builder usage(Usage usage) {
      this.usage = usage;
      return this;
    }

    public Builder finishReason(FinishReason finishReason) {
      this.finishReason = finishReason;
      return this;
    }

    public Builder continuationToken(String continuationToken) {
      this.continuationToken = continuationToken;
      return this;
    }

    public Builder metadata(JsonObject metadata) {
      this.metadata = metadata == null ? JsonObject.empty() : metadata;
      return this;
    }

    public Builder rawRepresentation(Object rawRepresentation) {
      this.rawRepresentation = rawRepresentation;
      return this;
    }

    public ModelResponseUpdate build() {
      return new ModelResponseUpdate(this);
    }
  }
}
