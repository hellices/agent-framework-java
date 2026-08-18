package io.github.hellices.agentframework.spi.interception;

import io.github.hellices.agentframework.api.agent.AgentDefinition;
import io.github.hellices.agentframework.api.agent.AgentRunRequest;
import io.github.hellices.agentframework.api.agent.CancellationSignal;
import io.github.hellices.agentframework.api.context.ContextAttributes;
import java.util.Objects;

/** Immutable agent-execution invocation data passed through the interceptor seam. */
public final class AgentInvocation {

  private final AgentDefinition agentDefinition;
  private final AgentRunRequest request;
  private final ContextAttributes effectiveAttributes;
  private final CancellationSignal cancellationSignal;

  private AgentInvocation(Builder builder) {
    this.agentDefinition =
        Objects.requireNonNull(builder.agentDefinition, "agentDefinition must not be null");
    this.request = Objects.requireNonNull(builder.request, "request must not be null");
    this.effectiveAttributes =
        builder.effectiveAttributes == null
            ? request.options().attributes().merge(request.attributes())
            : builder.effectiveAttributes;
    this.cancellationSignal =
        builder.cancellationSignal == null
            ? request.cancellationSignal()
            : builder.cancellationSignal;
  }

  public static Builder builder() {
    return new Builder();
  }

  public AgentDefinition agentDefinition() {
    return agentDefinition;
  }

  public AgentRunRequest request() {
    return request;
  }

  public ContextAttributes effectiveAttributes() {
    return effectiveAttributes;
  }

  public CancellationSignal cancellationSignal() {
    return cancellationSignal;
  }

  public Builder toBuilder() {
    return new Builder()
        .agentDefinition(agentDefinition)
        .request(request)
        .effectiveAttributes(effectiveAttributes)
        .cancellationSignal(cancellationSignal);
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof AgentInvocation that)) {
      return false;
    }
    return agentDefinition.equals(that.agentDefinition)
        && request.equals(that.request)
        && effectiveAttributes.equals(that.effectiveAttributes);
  }

  @Override
  public int hashCode() {
    return Objects.hash(agentDefinition, request, effectiveAttributes);
  }

  @Override
  public String toString() {
    return "AgentInvocation[agentDefinition="
        + agentDefinition
        + ", request="
        + request
        + ", effectiveAttributes="
        + effectiveAttributes
        + "]";
  }

  public static final class Builder {
    private AgentDefinition agentDefinition;
    private AgentRunRequest request;
    private ContextAttributes effectiveAttributes;
    private CancellationSignal cancellationSignal;

    private Builder() {}

    public Builder agentDefinition(AgentDefinition agentDefinition) {
      this.agentDefinition =
          Objects.requireNonNull(agentDefinition, "agentDefinition must not be null");
      return this;
    }

    public Builder request(AgentRunRequest request) {
      this.request = Objects.requireNonNull(request, "request must not be null");
      return this;
    }

    public Builder effectiveAttributes(ContextAttributes effectiveAttributes) {
      this.effectiveAttributes =
          Objects.requireNonNull(effectiveAttributes, "effectiveAttributes must not be null");
      return this;
    }

    public Builder cancellationSignal(CancellationSignal cancellationSignal) {
      this.cancellationSignal =
          Objects.requireNonNull(cancellationSignal, "cancellationSignal must not be null");
      return this;
    }

    public AgentInvocation build() {
      return new AgentInvocation(this);
    }
  }
}
