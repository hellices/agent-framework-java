package io.github.hellices.agentframework.api.agent;

import io.github.hellices.agentframework.api.message.Content;
import io.github.hellices.agentframework.api.message.FinishReason;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.Usage;
import io.github.hellices.agentframework.api.value.JsonNumber;
import io.github.hellices.agentframework.api.value.JsonObject;
import io.github.hellices.agentframework.api.value.JsonValue;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class AgentResponse {

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

  private AgentResponse(Builder builder) {
    this.agentId = Objects.requireNonNull(builder.agentId, "agentId must not be null");
    this.responseId = Objects.requireNonNull(builder.responseId, "responseId must not be null");
    this.messageId = builder.messageId;
    this.authorName = builder.authorName;
    this.createdAt = builder.createdAt;
    this.finishReason = builder.finishReason;
    this.continuationToken = builder.continuationToken;
    this.messages = immutableMessages(builder.messages);
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

  public static AgentResponse fromUpdates(List<AgentResponseUpdate> updates) {
    Objects.requireNonNull(updates, "updates must not be null");
    if (updates.isEmpty()) {
      throw new IllegalArgumentException("updates must not be empty");
    }

    AgentResponseUpdate first =
        Objects.requireNonNull(updates.get(0), "updates must not contain null entries");
    List<Message> messages = new ArrayList<>();
    String currentMessageId = null;
    String lastMessageId = null;
    String messageId = null;
    String authorName = null;
    Instant createdAt = null;
    FinishReason finishReason = null;
    String continuationToken = null;
    Usage usage = null;
    JsonObject additionalProperties = JsonObject.empty();
    Object rawRepresentation = null;
    for (AgentResponseUpdate update : updates) {
      Objects.requireNonNull(update, "updates must not contain null entries");
      if (!first.agentId().equals(update.agentId())
          || !first.responseId().equals(update.responseId())) {
        throw new IllegalArgumentException("updates must belong to the same agent response");
      }
      if (update.messageId() != null) {
        messageId = update.messageId();
      }
      if (update.authorName() != null) {
        authorName = update.authorName();
      }
      if (createdAt == null && update.createdAt() != null) {
        createdAt = update.createdAt();
      }
      if (update.finishReason() != null) {
        finishReason = update.finishReason();
      }
      if (update.continuationToken() != null) {
        continuationToken = update.continuationToken();
      }
      usage = combineUsage(usage, update.usage());
      additionalProperties =
          mergeAdditionalProperties(additionalProperties, update.additionalProperties());
      if (update.rawRepresentation() != null) {
        rawRepresentation = update.rawRepresentation();
      }
      boolean firstMessageInUpdate = true;
      for (Message message : update.messages()) {
        boolean continuesCurrentMessage =
            firstMessageInUpdate
                && !messages.isEmpty()
                && messages.get(messages.size() - 1).role().equals(message.role())
                && (update.messageId() != null
                    ? update.messageId().equals(currentMessageId)
                    : Objects.equals(currentMessageId, lastMessageId));
        if (continuesCurrentMessage) {
          Message current = messages.remove(messages.size() - 1);
          messages.add(combineMessages(current, message));
        } else {
          messages.add(message);
        }
        lastMessageId = update.messageId() == null ? currentMessageId : update.messageId();
        firstMessageInUpdate = false;
      }
      if (update.messageId() != null) {
        currentMessageId = update.messageId();
      }
    }
    return builder()
        .agentId(first.agentId())
        .responseId(first.responseId())
        .messageId(messageId)
        .authorName(authorName)
        .createdAt(createdAt)
        .finishReason(finishReason)
        .continuationToken(continuationToken)
        .messages(messages)
        .usage(usage)
        .additionalProperties(additionalProperties)
        .rawRepresentation(rawRepresentation)
        .build();
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof AgentResponse that)) {
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

    public AgentResponse build() {
      return new AgentResponse(this);
    }
  }

  private static Message combineMessages(Message accumulated, Message update) {
    List<Content> content = new ArrayList<>(accumulated.content());
    content.addAll(update.content());
    return new Message(
        accumulated.role(),
        content,
        update.attribution() == null ? accumulated.attribution() : update.attribution(),
        mergeAdditionalProperties(
            accumulated.additionalProperties(), update.additionalProperties()),
        update.rawRepresentation() == null
            ? accumulated.rawRepresentation()
            : update.rawRepresentation());
  }

  private static Usage combineUsage(Usage accumulated, Usage update) {
    if (accumulated == null) {
      return update;
    }
    if (update == null) {
      return accumulated;
    }
    return new Usage(
        Math.addExact(accumulated.inputTokens(), update.inputTokens()),
        Math.addExact(accumulated.outputTokens(), update.outputTokens()),
        Math.addExact(accumulated.totalTokens(), update.totalTokens()),
        mergeAdditionalProperties(
            accumulated.additionalProperties(),
            update.additionalProperties(),
            AgentResponse::combineUsageProperty));
  }

  private static JsonValue combineUsageProperty(JsonValue accumulated, JsonValue update) {
    if (accumulated instanceof JsonNumber left && update instanceof JsonNumber right) {
      return JsonNumber.of(left.value().add(right.value()));
    }
    return update;
  }

  private static JsonObject mergeAdditionalProperties(JsonObject accumulated, JsonObject update) {
    return mergeAdditionalProperties(accumulated, update, (left, right) -> right);
  }

  private static JsonObject mergeAdditionalProperties(
      JsonObject accumulated,
      JsonObject update,
      java.util.function.BiFunction<JsonValue, JsonValue, JsonValue> merger) {
    if ((accumulated == null || accumulated.isEmpty()) && (update == null || update.isEmpty())) {
      return JsonObject.empty();
    }
    if (accumulated == null || accumulated.isEmpty()) {
      return update == null ? JsonObject.empty() : update;
    }
    if (update == null || update.isEmpty()) {
      return accumulated;
    }
    Map<String, JsonValue> merged = new LinkedHashMap<>(accumulated.values());
    update
        .values()
        .forEach(
            (key, value) ->
                merged.merge(
                    key,
                    value,
                    (left, right) ->
                        Objects.requireNonNull(
                            merger.apply(left, right), "merged value must not be null")));
    return jsonObject(merged);
  }

  private static JsonObject jsonObject(Map<String, JsonValue> values) {
    if (values.isEmpty()) {
      return JsonObject.empty();
    }
    JsonObject.Builder builder = JsonObject.builder();
    values.forEach(builder::put);
    return builder.build();
  }

  private static List<Message> immutableMessages(List<? extends Message> source) {
    return source == null ? List.of() : List.copyOf(source);
  }
}
