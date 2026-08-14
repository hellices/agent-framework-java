package io.github.hellices.agentframework.api.agent;

import io.github.hellices.agentframework.api.message.Message;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class AgentRunRequest {

  private final List<Message> messages;
  private final String sessionId;
  private final AgentRunOptions options;
  private final CancellationSignal cancellationSignal;
  private final Map<String, Object> attributes;

  public AgentRunRequest(
      List<? extends Message> messages,
      String sessionId,
      AgentRunOptions options,
      CancellationSignal cancellationSignal,
      Map<String, Object> attributes) {
    List<Message> normalizedMessages = new ArrayList<>();
    if (messages != null) {
      for (Message message : messages) {
        normalizedMessages.add(Objects.requireNonNull(message, "messages must not contain null entries"));
      }
    }
    this.messages = Collections.unmodifiableList(normalizedMessages);
    this.sessionId = sessionId;
    this.options = options == null ? new AgentRunOptions() : options;
    this.cancellationSignal = cancellationSignal == null ? new CancellationSignal() : cancellationSignal;
    this.attributes = attributes == null ? Map.of() : Collections.unmodifiableMap(new HashMap<>(attributes));
  }

  public static AgentRunRequest empty() {
    return new AgentRunRequest(List.of(), null, new AgentRunOptions(), new CancellationSignal(), Map.of());
  }

  public static AgentRunRequest of(String input) {
    Objects.requireNonNull(input, "input must not be null");
    return new AgentRunRequest(Message.normalize(input), null, new AgentRunOptions(), new CancellationSignal(), Map.of());
  }

  public static AgentRunRequest of(List<? extends Message> messages) {
    return new AgentRunRequest(messages, null, new AgentRunOptions(), new CancellationSignal(), Map.of());
  }

  public List<Message> messages() {
    return messages;
  }

  public String sessionId() {
    return sessionId;
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
