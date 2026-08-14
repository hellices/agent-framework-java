package io.github.hellices.agentframework.api.agent;

import io.github.hellices.agentframework.api.message.Message;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class AgentRunRequest {

  private final List<Message> messages;
  private final AgentSession session;
  private final AgentRunOptions options;
  private final CancellationSignal cancellationSignal;
  private final Map<String, Object> attributes;

  public AgentRunRequest(
      List<? extends Message> messages,
      AgentSession session,
      AgentRunOptions options,
      CancellationSignal cancellationSignal,
      Map<String, Object> attributes) {
    List<Message> normalizedMessages = new ArrayList<>();
    if (messages != null) {
      for (Message message : messages) {
        normalizedMessages.add(
            Objects.requireNonNull(message, "messages must not contain null entries"));
      }
    }
    this.messages = List.copyOf(normalizedMessages);
    this.session = session;
    this.options = options == null ? new AgentRunOptions() : options;
    this.cancellationSignal =
        cancellationSignal == null ? new CancellationSignal() : cancellationSignal;
    this.attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
  }

  public AgentRunRequest(
      List<? extends Message> messages,
      String sessionId,
      AgentRunOptions options,
      CancellationSignal cancellationSignal,
      Map<String, Object> attributes) {
    this(
        messages,
        sessionId == null ? null : new AgentSession(sessionId, null, Map.of()),
        options,
        cancellationSignal,
        attributes);
  }

  public static AgentRunRequest empty() {
    return new AgentRunRequest(
        List.of(), (AgentSession) null, new AgentRunOptions(), new CancellationSignal(), Map.of());
  }

  public static AgentRunRequest of(String input) {
    Objects.requireNonNull(input, "input must not be null");
    return new AgentRunRequest(
        Message.normalize(input),
        (AgentSession) null,
        new AgentRunOptions(),
        new CancellationSignal(),
        Map.of());
  }

  public static AgentRunRequest of(List<? extends Message> messages) {
    return new AgentRunRequest(
        messages, (AgentSession) null, new AgentRunOptions(), new CancellationSignal(), Map.of());
  }

  public List<Message> messages() {
    return messages;
  }

  public AgentSession session() {
    return session;
  }

  public String sessionId() {
    return session == null ? null : session.sessionId();
  }

  public AgentRunOptions options() {
    return options;
  }

  public CancellationSignal cancellationSignal() {
    return cancellationSignal;
  }

  public Map<String, Object> attributes() {
    return attributes;
  }
}
