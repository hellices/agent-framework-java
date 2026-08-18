package io.github.hellices.agentframework.spi.interception;

import io.github.hellices.agentframework.api.agent.CancellationSignal;
import io.github.hellices.agentframework.spi.model.ModelRequest;
import java.util.Objects;
import java.util.Optional;

/** Immutable model-call invocation data passed through the interceptor seam. */
public final class ModelInvocation {

  private final String agentId;
  private final String sessionId;
  private final ModelRequest request;
  private final CancellationSignal cancellationSignal;

  private ModelInvocation(Builder builder) {
    this.agentId =
        requireText(Objects.requireNonNull(builder.agentId, "agentId must not be null"), "agentId");
    this.sessionId = normalizeOptional(builder.sessionId, "sessionId");
    this.request = Objects.requireNonNull(builder.request, "request must not be null");
    this.cancellationSignal =
        builder.cancellationSignal == null
            ? request.cancellationSignal()
            : builder.cancellationSignal;
  }

  public static Builder builder() {
    return new Builder();
  }

  public String agentId() {
    return agentId;
  }

  public Optional<String> sessionId() {
    return Optional.ofNullable(sessionId);
  }

  public ModelRequest request() {
    return request;
  }

  public CancellationSignal cancellationSignal() {
    return cancellationSignal;
  }

  public Builder toBuilder() {
    return new Builder()
        .agentId(agentId)
        .sessionId(sessionId)
        .request(request)
        .cancellationSignal(cancellationSignal);
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof ModelInvocation that)) {
      return false;
    }
    return agentId.equals(that.agentId)
        && Objects.equals(sessionId, that.sessionId)
        && request.equals(that.request);
  }

  @Override
  public int hashCode() {
    return Objects.hash(agentId, sessionId, request);
  }

  @Override
  public String toString() {
    return "ModelInvocation[agentId="
        + agentId
        + ", sessionId="
        + sessionId
        + ", request="
        + request
        + "]";
  }

  public static final class Builder {
    private String agentId;
    private String sessionId;
    private ModelRequest request;
    private CancellationSignal cancellationSignal;

    private Builder() {}

    public Builder agentId(String agentId) {
      this.agentId = Objects.requireNonNull(agentId, "agentId must not be null");
      return this;
    }

    public Builder sessionId(String sessionId) {
      this.sessionId = normalizeOptional(sessionId, "sessionId");
      return this;
    }

    public Builder request(ModelRequest request) {
      this.request = Objects.requireNonNull(request, "request must not be null");
      return this;
    }

    public Builder cancellationSignal(CancellationSignal cancellationSignal) {
      this.cancellationSignal =
          Objects.requireNonNull(cancellationSignal, "cancellationSignal must not be null");
      return this;
    }

    public ModelInvocation build() {
      return new ModelInvocation(this);
    }
  }

  private static String normalizeOptional(String value, String label) {
    if (value == null) {
      return null;
    }
    return requireText(value, label);
  }

  private static String requireText(String value, String label) {
    if (value.isBlank()) {
      throw new IllegalArgumentException(label + " must not be blank");
    }
    return value;
  }
}
