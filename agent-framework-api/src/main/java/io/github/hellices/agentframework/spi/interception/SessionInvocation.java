package io.github.hellices.agentframework.spi.interception;

import io.github.hellices.agentframework.api.agent.AgentSession;
import io.github.hellices.agentframework.api.agent.CancellationSignal;
import io.github.hellices.agentframework.api.context.ContextAttributes;
import io.github.hellices.agentframework.api.session.SessionSnapshot;
import java.util.Objects;
import java.util.Optional;

/** Immutable session-operation invocation data passed through the interceptor seam. */
public final class SessionInvocation {

  private final SessionOperation operation;
  private final AgentSession session;
  private final SessionSnapshot snapshot;
  private final ContextAttributes attributes;
  private final CancellationSignal cancellationSignal;

  private SessionInvocation(Builder builder) {
    this.operation = Objects.requireNonNull(builder.operation, "operation must not be null");
    this.session = Objects.requireNonNull(builder.session, "session must not be null");
    this.snapshot = builder.snapshot;
    this.attributes = builder.attributes == null ? ContextAttributes.empty() : builder.attributes;
    this.cancellationSignal =
        builder.cancellationSignal == null ? new CancellationSignal() : builder.cancellationSignal;
    if (operation == SessionOperation.LOAD && snapshot != null) {
      throw new IllegalStateException("snapshot must be null for LOAD");
    }
    if (operation == SessionOperation.SAVE && snapshot == null) {
      throw new IllegalStateException("snapshot must not be null for SAVE");
    }
    validateSnapshotSession(snapshot, session.sessionId(), "snapshot");
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

  public ContextAttributes attributes() {
    return attributes;
  }

  public CancellationSignal cancellationSignal() {
    return cancellationSignal;
  }

  public Builder toBuilder() {
    return new Builder()
        .operation(operation)
        .session(session)
        .snapshot(snapshot)
        .attributes(attributes)
        .cancellationSignal(cancellationSignal);
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof SessionInvocation that)) {
      return false;
    }
    return operation == that.operation
        && session.equals(that.session)
        && Objects.equals(snapshot, that.snapshot)
        && attributes.equals(that.attributes);
  }

  @Override
  public int hashCode() {
    return Objects.hash(operation, session, snapshot, attributes);
  }

  @Override
  public String toString() {
    return "SessionInvocation[operation="
        + operation
        + ", session="
        + session
        + ", snapshot="
        + snapshot
        + ", attributes="
        + attributes
        + "]";
  }

  public static final class Builder {
    private SessionOperation operation;
    private AgentSession session;
    private SessionSnapshot snapshot;
    private ContextAttributes attributes = ContextAttributes.empty();
    private CancellationSignal cancellationSignal;

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

    public Builder attributes(ContextAttributes attributes) {
      this.attributes = Objects.requireNonNull(attributes, "attributes must not be null");
      return this;
    }

    public Builder cancellationSignal(CancellationSignal cancellationSignal) {
      this.cancellationSignal =
          Objects.requireNonNull(cancellationSignal, "cancellationSignal must not be null");
      return this;
    }

    public SessionInvocation build() {
      return new SessionInvocation(this);
    }
  }

  private static void validateSnapshotSession(
      SessionSnapshot snapshot, String expectedSessionId, String label) {
    if (snapshot != null && !expectedSessionId.equals(snapshot.sessionId())) {
      throw new IllegalArgumentException(label + " sessionId must be " + expectedSessionId);
    }
  }
}
