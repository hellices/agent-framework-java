package io.github.hellices.agentframework.api.agent;

import io.github.hellices.agentframework.api.session.SessionState;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class AgentSession {

  private final String sessionId;
  private final String serviceSessionId;
  private final SessionState state;

  private AgentSession(Builder builder) {
    String normalizedSessionId = builder.sessionId;
    if (normalizedSessionId == null) {
      normalizedSessionId = UUID.randomUUID().toString();
    } else if (normalizedSessionId.isBlank()) {
      throw new IllegalArgumentException("sessionId must not be blank");
    }
    if (builder.serviceSessionId != null && builder.serviceSessionId.isBlank()) {
      throw new IllegalArgumentException("serviceSessionId must not be blank");
    }
    this.sessionId = normalizedSessionId;
    this.serviceSessionId = builder.serviceSessionId;
    this.state = builder.state == null ? SessionState.empty() : builder.state;
  }

  public static Builder builder() {
    return new Builder();
  }

  public String sessionId() {
    return sessionId;
  }

  public Optional<String> serviceSessionId() {
    return Optional.ofNullable(serviceSessionId);
  }

  public SessionState state() {
    return state;
  }

  public Builder toBuilder() {
    return new Builder().sessionId(sessionId).serviceSessionId(serviceSessionId).state(state);
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof AgentSession that)) {
      return false;
    }
    return sessionId.equals(that.sessionId)
        && Objects.equals(serviceSessionId, that.serviceSessionId)
        && state.equals(that.state);
  }

  @Override
  public int hashCode() {
    return Objects.hash(sessionId, serviceSessionId, state);
  }

  @Override
  public String toString() {
    return "AgentSession[sessionId="
        + sessionId
        + ", serviceSessionId="
        + serviceSessionId
        + ", state="
        + state
        + "]";
  }

  public static final class Builder {
    private String sessionId;
    private String serviceSessionId;
    private SessionState state = SessionState.empty();

    private Builder() {}

    public Builder sessionId(String sessionId) {
      this.sessionId = sessionId;
      return this;
    }

    public Builder serviceSessionId(String serviceSessionId) {
      this.serviceSessionId = serviceSessionId;
      return this;
    }

    public Builder state(SessionState state) {
      this.state = state == null ? SessionState.empty() : state;
      return this;
    }

    public AgentSession build() {
      return new AgentSession(this);
    }
  }
}
