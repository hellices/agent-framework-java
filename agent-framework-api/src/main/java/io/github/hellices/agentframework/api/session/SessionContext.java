package io.github.hellices.agentframework.api.session;

import io.github.hellices.agentframework.api.agent.AgentResponse;
import io.github.hellices.agentframework.api.agent.AgentSession;
import io.github.hellices.agentframework.api.agent.CancellationSignal;
import io.github.hellices.agentframework.api.message.Message;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The independent per-run execution context described by SES-011. A fresh instance is created for
 * every ordinary and streaming {@code Agent} run; it carries the session identity, the caller's
 * input messages, an ordered list of provider-contributed context messages, run metadata, the
 * request's cancellation signal, and a set-once response slot that is only filled on successful
 * terminal completion.
 */
public final class SessionContext {

  private final AgentSession session;
  private final List<Message> inputMessages;
  private final List<Message> contextMessages = new ArrayList<>();
  private final Map<String, Object> metadata;
  private final CancellationSignal cancellationSignal;
  private AgentResponse response;

  public SessionContext(
      AgentSession session,
      List<? extends Message> inputMessages,
      Map<String, Object> metadata,
      CancellationSignal cancellationSignal) {
    this.session = session;
    List<Message> normalizedInputMessages = new ArrayList<>();
    if (inputMessages != null) {
      for (Message message : inputMessages) {
        normalizedInputMessages.add(
            Objects.requireNonNull(message, "inputMessages must not contain null entries"));
      }
    }
    this.inputMessages = List.copyOf(normalizedInputMessages);
    this.metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    this.cancellationSignal =
        cancellationSignal == null ? new CancellationSignal() : cancellationSignal;
  }

  public AgentSession session() {
    return session;
  }

  public List<Message> inputMessages() {
    return inputMessages;
  }

  public synchronized List<Message> contextMessages() {
    return List.copyOf(contextMessages);
  }

  /**
   * Appends an ordered, immutable-safe copy of {@code messages} to this context's accumulated
   * context messages. This is a plain accumulation hook for engines/providers to make {@link
   * #contextMessages()} usable; it does not attach any provider source attribution to the messages
   * it appends (source-bound attribution is owned by a later context-provider slice).
   *
   * <p>Null handling mirrors the constructor's {@code inputMessages} normalization: a {@code null}
   * collection is treated as "nothing to add" and is a no-op, while a non-null collection must not
   * contain {@code null} entries. A collection with a {@code null} entry is rejected in full (no
   * partial append) before any of its messages are appended.
   *
   * @param messages the messages to append, in order; may be {@code null}
   * @throws NullPointerException if {@code messages} is non-null and contains a {@code null} entry
   */
  public synchronized void addContextMessages(List<? extends Message> messages) {
    if (messages == null) {
      return;
    }
    List<Message> normalized = new ArrayList<>();
    for (Message message : messages) {
      normalized.add(
          Objects.requireNonNull(message, "contextMessages must not contain null entries"));
    }
    contextMessages.addAll(normalized);
  }

  public Map<String, Object> metadata() {
    return metadata;
  }

  public CancellationSignal cancellationSignal() {
    return cancellationSignal;
  }

  public synchronized Optional<AgentResponse> response() {
    return Optional.ofNullable(response);
  }

  /**
   * Fills the set-once response slot on successful terminal completion of the run this context was
   * created for. This is framework lifecycle completion invoked by {@code Agent}'s internal run
   * plumbing (see {@code Agent#completionAction}); it is not intended to be called by provider or
   * application code.
   *
   * <p>The slot can only be filled once: any second invocation, whether with the same or a
   * different value, is rejected because the response is set-once for the lifetime of this context.
   *
   * @param value the terminal response to store; must not be {@code null}
   * @throws NullPointerException if {@code value} is {@code null}
   * @throws IllegalStateException if the response slot has already been filled
   */
  public synchronized void complete(AgentResponse value) {
    if (response != null) {
      throw new IllegalStateException("session context response is already complete");
    }
    response = Objects.requireNonNull(value, "response must not be null");
  }
}
