package io.github.hellices.agentframework.spi.interception;

import io.github.hellices.agentframework.api.agent.AgentSession;
import io.github.hellices.agentframework.api.session.SessionSnapshot;
import java.util.Objects;
import java.util.Optional;

/** Immutable session-operation result returned by the session interceptor seam. */
public final class SessionOperationResult {

  private final SessionOperation operation;
  private final AgentSession session;
  private final SessionSnapshot snapshot;

  private SessionOperationResult(Builder builder) {
    this.operation = Objects.requireNonNull(builder.operation, "operation must not be null");
    this.session = Objects.requireNonNull(builder.session, "session must not be null");
    this.snapshot = builder.snapshot;
    if (operation == SessionOperation.SAVE && snapshot == null) {
      throw new IllegalStateException("snapshot must not be null for SAVE");
    }
    if (snapshot != null && !session.sessionId().equals(snapshot.sessionId())) {
      throw new IllegalArgumentException("snapshot sessionId must be " + session.sessionId());
    }
  }

  public static Builder builder() {
    return new Builder();
  }

  public SessionOperation operation() {
    return operation;
  }

  public AgentSession session() {
    return session;
  }

  public Optional<SessionSnapshot> snapshot() {
    return Optional.ofNullable(snapshot);
  }

  public Builder toBuilder() {
    return new Builder().operation(operation).session(session).snapshot(snapshot);
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof SessionOperationResult that)) {
      return false;
    }
    return operation == that.operation
        && session.equals(that.session)
        && Objects.equals(snapshot, that.snapshot);
  }

  @Override
  public int hashCode() {
    return Objects.hash(operation, session, snapshot);
  }

  @Override
  public String toString() {
    return "SessionOperationResult[operation="
        + operation
        + ", session="
        + session
        + ", snapshot="
        + snapshot
        + "]";
  }

  public static final class Builder {
    private SessionOperation operation;
    private AgentSession session;
    private SessionSnapshot snapshot;

    private Builder() {}

    public Builder operation(SessionOperation operation) {
      this.operation = Objects.requireNonNull(operation, "operation must not be null");
      return this;
    }

    public Builder session(AgentSession session) {
      this.session = Objects.requireNonNull(session, "session must not be null");
      return this;
    }

    public Builder snapshot(SessionSnapshot snapshot) {
      this.snapshot = snapshot;
      return this;
    }

    public SessionOperationResult build() {
      return new SessionOperationResult(this);
    }
  }
}
