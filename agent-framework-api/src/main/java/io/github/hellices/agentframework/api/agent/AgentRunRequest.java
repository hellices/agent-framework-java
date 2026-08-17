package io.github.hellices.agentframework.api.agent;

import io.github.hellices.agentframework.api.context.ContextAttributes;
import io.github.hellices.agentframework.api.message.Message;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class AgentRunRequest {

  private final List<Message> messages;
  private final AgentSession session;
  private final AgentRunOptions options;
  private final CancellationSignal cancellationSignal;
  private final ContextAttributes attributes;

  private AgentRunRequest(Builder builder) {
    this.messages = immutableMessages(builder.messages);
    this.session = builder.session;
    this.options = builder.options == null ? new AgentRunOptions() : builder.options;
    if (!messages.isEmpty() && this.options.continuationToken().isPresent()) {
      throw new IllegalArgumentException(
          "continuationToken cannot be combined with input messages");
    }
    this.cancellationSignal =
        builder.cancellationSignal == null ? new CancellationSignal() : builder.cancellationSignal;
    this.attributes = builder.attributes == null ? ContextAttributes.empty() : builder.attributes;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static AgentRunRequest empty() {
    return builder().build();
  }

  public static AgentRunRequest of(String input) {
    Objects.requireNonNull(input, "input must not be null");
    return builder().messages(Message.normalize(input)).build();
  }

  public static AgentRunRequest of(List<? extends Message> messages) {
    return builder().messages(messages).build();
  }

  public List<Message> messages() {
    return List.copyOf(messages);
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

  public ContextAttributes attributes() {
    return attributes;
  }

  public Builder toBuilder() {
    return new Builder()
        .messages(messages)
        .session(session)
        .options(options)
        .cancellationSignal(cancellationSignal)
        .attributes(attributes);
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof AgentRunRequest that)) {
      return false;
    }
    return messages.equals(that.messages)
        && Objects.equals(session, that.session)
        && Objects.equals(options, that.options)
        && Objects.equals(attributes, that.attributes);
  }

  @Override
  public int hashCode() {
    return Objects.hash(messages, session, options, attributes);
  }

  public static final class Builder {
    private List<? extends Message> messages = List.of();
    private AgentSession session;
    private AgentRunOptions options = new AgentRunOptions();
    private CancellationSignal cancellationSignal = new CancellationSignal();
    private ContextAttributes attributes = ContextAttributes.empty();

    private Builder() {}

    public Builder messages(List<? extends Message> messages) {
      this.messages = messages == null ? List.of() : messages;
      return this;
    }

    public Builder session(AgentSession session) {
      this.session = session;
      return this;
    }

    public Builder options(AgentRunOptions options) {
      this.options = options == null ? new AgentRunOptions() : options;
      return this;
    }

    public Builder cancellationSignal(CancellationSignal cancellationSignal) {
      this.cancellationSignal =
          cancellationSignal == null ? new CancellationSignal() : cancellationSignal;
      return this;
    }

    public Builder attributes(ContextAttributes attributes) {
      this.attributes = attributes == null ? ContextAttributes.empty() : attributes;
      return this;
    }

    public AgentRunRequest build() {
      return new AgentRunRequest(this);
    }
  }

  private static List<Message> immutableMessages(List<? extends Message> source) {
    List<Message> normalizedMessages = new ArrayList<>();
    if (source != null) {
      for (Message message : source) {
        normalizedMessages.add(
            Objects.requireNonNull(message, "messages must not contain null entries"));
      }
    }
    return List.copyOf(normalizedMessages);
  }
}
