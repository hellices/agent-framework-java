package io.github.hellices.agentframework.api.agent;

import io.github.hellices.agentframework.api.message.FinishReason;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.Usage;
import io.github.hellices.agentframework.api.value.JsonObject;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public final class AgentResponseUpdate {

  private final String agentId;
  private final String responseId;
  private final String messageId;
  private final String authorName;
  private final Instant createdAt;
  private final FinishReason finishReason;
  private final String continuationToken;
  private final List<Message> messages;
  private final Usage usage;
  private final JsonObject additionalProperties;
  private final transient Object rawRepresentation;

  private AgentResponseUpdate(Builder builder) {
    this.agentId = Objects.requireNonNull(builder.agentId, "agentId must not be null");
    this.responseId = Objects.requireNonNull(builder.responseId, "responseId must not be null");
    this.messageId = builder.messageId;
    this.authorName = builder.authorName;
    this.createdAt = builder.createdAt;
    this.finishReason = builder.finishReason;
    this.continuationToken = builder.continuationToken;
    this.messages = builder.messages == null ? List.of() : List.copyOf(builder.messages);
    this.usage = builder.usage;
    this.additionalProperties =
        builder.additionalProperties == null ? JsonObject.empty() : builder.additionalProperties;
    this.rawRepresentation = builder.rawRepresentation;
  }

  public static Builder builder() {
    return new Builder();
  }

  public String agentId() {
    return agentId;
  }

  public String responseId() {
    return responseId;
  }

  public String messageId() {
    return messageId;
  }

  public String authorName() {
    return authorName;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public FinishReason finishReason() {
    return finishReason;
  }

  public String continuationToken() {
    return continuationToken;
  }

  public List<Message> messages() {
    return List.copyOf(messages);
  }

  public Usage usage() {
    return usage;
  }

  public JsonObject additionalProperties() {
    return additionalProperties;
  }

  public Object rawRepresentation() {
    return rawRepresentation;
  }

  public Builder toBuilder() {
    return new Builder()
        .agentId(agentId)
        .responseId(responseId)
        .messageId(messageId)
        .authorName(authorName)
        .createdAt(createdAt)
        .finishReason(finishReason)
        .continuationToken(continuationToken)
        .messages(messages)
        .usage(usage)
        .additionalProperties(additionalProperties)
        .rawRepresentation(rawRepresentation);
  }

  public String text() {
    StringBuilder builder = new StringBuilder();
    for (Message message : messages) {
      builder.append(message.text());
    }
    return builder.toString();
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof AgentResponseUpdate that)) {
      return false;
    }
    return agentId.equals(that.agentId)
        && responseId.equals(that.responseId)
        && Objects.equals(messageId, that.messageId)
        && Objects.equals(authorName, that.authorName)
        && Objects.equals(createdAt, that.createdAt)
        && finishReason == that.finishReason
        && Objects.equals(continuationToken, that.continuationToken)
        && messages.equals(that.messages)
        && Objects.equals(usage, that.usage)
        && additionalProperties.equals(that.additionalProperties)
        && Objects.equals(rawRepresentation, that.rawRepresentation);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        agentId,
        responseId,
        messageId,
        authorName,
        createdAt,
        finishReason,
        continuationToken,
        messages,
        usage,
        additionalProperties,
        rawRepresentation);
  }

  public static final class Builder {
    private String agentId;
    private String responseId;
    private String messageId;
    private String authorName;
    private Instant createdAt;
    private FinishReason finishReason;
    private String continuationToken;
    private List<? extends Message> messages = List.of();
    private Usage usage;
    private JsonObject additionalProperties = JsonObject.empty();
    private Object rawRepresentation;

    private Builder() {}

    public Builder agentId(String agentId) {
      this.agentId = agentId;
      return this;
    }

    public Builder responseId(String responseId) {
      this.responseId = responseId;
      return this;
    }

    public Builder messageId(String messageId) {
      this.messageId = messageId;
      return this;
    }

    public Builder authorName(String authorName) {
      this.authorName = authorName;
      return this;
    }

    public Builder createdAt(Instant createdAt) {
      this.createdAt = createdAt;
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

    public Builder messages(List<? extends Message> messages) {
      this.messages = messages == null ? List.of() : messages;
      return this;
    }

    public Builder usage(Usage usage) {
      this.usage = usage;
      return this;
    }

    public Builder additionalProperties(JsonObject additionalProperties) {
      this.additionalProperties =
          additionalProperties == null ? JsonObject.empty() : additionalProperties;
      return this;
    }

    public Builder rawRepresentation(Object rawRepresentation) {
      this.rawRepresentation = rawRepresentation;
      return this;
    }

    public AgentResponseUpdate build() {
      return new AgentResponseUpdate(this);
    }
  }
}
