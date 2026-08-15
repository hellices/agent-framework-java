package io.github.hellices.agentframework.api.agent;

import io.github.hellices.agentframework.api.message.Content;
import io.github.hellices.agentframework.api.message.FinishReason;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.Usage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
    String continuationToken,
    List<Message> messages,
    Usage usage,
    Map<String, Object> additionalProperties,
    Object rawRepresentation) {

  public AgentResponse {
    Objects.requireNonNull(agentId, "agentId must not be null");
    Objects.requireNonNull(responseId, "responseId must not be null");
    messages = messages == null ? List.of() : List.copyOf(messages);
    additionalProperties =
        additionalProperties == null ? Map.of() : Map.copyOf(additionalProperties);
  }

  public AgentResponse(
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
    this(
        agentId,
        responseId,
        messageId,
        authorName,
        createdAt,
        finishReason,
        null,
        messages,
        usage,
        additionalProperties,
        rawRepresentation);
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
    String messageId = null;
    String authorName = null;
    Instant createdAt = null;
    FinishReason finishReason = null;
    String continuationToken = null;
    Usage usage = null;
    Map<String, Object> additionalProperties = new LinkedHashMap<>();
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
      additionalProperties.putAll(update.additionalProperties());
      if (update.rawRepresentation() != null) {
        rawRepresentation = update.rawRepresentation();
      }
      boolean firstMessageInUpdate = true;
      for (Message message : update.messages()) {
        boolean continuesCurrentMessage =
            firstMessageInUpdate
                && !messages.isEmpty()
                && messages.get(messages.size() - 1).role().equals(message.role())
                && (update.messageId() == null || update.messageId().equals(currentMessageId));
        if (continuesCurrentMessage) {
          Message current = messages.remove(messages.size() - 1);
          messages.add(combineMessages(current, message));
        } else {
          messages.add(message);
        }
        if (update.messageId() != null) {
          currentMessageId = update.messageId();
        }
        firstMessageInUpdate = false;
      }
    }
    return new AgentResponse(
        first.agentId(),
        first.responseId(),
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

  private static Message combineMessages(Message accumulated, Message update) {
    List<Content> content = new ArrayList<>(accumulated.content());
    content.addAll(update.content());
    Map<String, Object> additionalProperties =
        new LinkedHashMap<>(accumulated.additionalProperties());
    additionalProperties.putAll(update.additionalProperties());
    return new Message(
        accumulated.role(),
        content,
        update.attribution() == null ? accumulated.attribution() : update.attribution(),
        additionalProperties,
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

    Map<String, Object> additionalProperties =
        new LinkedHashMap<>(accumulated.additionalProperties());
    update
        .additionalProperties()
        .forEach(
            (key, value) ->
                additionalProperties.merge(key, value, AgentResponse::combineUsageProperty));
    return new Usage(
        Math.addExact(accumulated.inputTokens(), update.inputTokens()),
        Math.addExact(accumulated.outputTokens(), update.outputTokens()),
        Math.addExact(accumulated.totalTokens(), update.totalTokens()),
        additionalProperties);
  }

  private static Object combineUsageProperty(Object accumulated, Object update) {
    if (isIntegralNumber(accumulated) && isIntegralNumber(update)) {
      return Math.addExact(((Number) accumulated).longValue(), ((Number) update).longValue());
    }
    if (accumulated instanceof Number left && update instanceof Number right) {
      return left.doubleValue() + right.doubleValue();
    }
    return update;
  }

  private static boolean isIntegralNumber(Object value) {
    return value instanceof Byte
        || value instanceof Short
        || value instanceof Integer
        || value instanceof Long;
  }
}
