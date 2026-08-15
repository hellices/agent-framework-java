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
        Objects.requireNonNull(cancellationSignal, "cancellationSignal must not be null");
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

  public Map<String, Object> metadata() {
    return metadata;
  }

  public CancellationSignal cancellationSignal() {
    return cancellationSignal;
  }

  public synchronized Optional<AgentResponse> response() {
    return Optional.ofNullable(response);
  }

  public synchronized void complete(AgentResponse value) {
    if (response != null) {
      throw new IllegalStateException("session context response is already complete");
    }
    response = Objects.requireNonNull(value, "response must not be null");
  }
}
