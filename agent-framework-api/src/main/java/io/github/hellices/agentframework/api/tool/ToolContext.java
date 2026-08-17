package io.github.hellices.agentframework.api.tool;

import io.github.hellices.agentframework.api.agent.CancellationSignal;
import io.github.hellices.agentframework.api.context.ContextAttributes;
import java.util.Objects;

public final class ToolContext {

  private final CancellationSignal cancellationSignal;
  private final ContextAttributes attributes;

  public ToolContext(CancellationSignal cancellationSignal, ContextAttributes attributes) {
    this.cancellationSignal =
        cancellationSignal == null ? new CancellationSignal() : cancellationSignal;
    this.attributes = attributes == null ? ContextAttributes.empty() : attributes;
  }

  public CancellationSignal cancellationSignal() {
    return cancellationSignal;
  }

  public ContextAttributes attributes() {
    return attributes;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof ToolContext that)) {
      return false;
    }
    return Objects.equals(cancellationSignal, that.cancellationSignal)
        && attributes.equals(that.attributes);
  }

  @Override
  public int hashCode() {
    return Objects.hash(cancellationSignal, attributes);
  }

  @Override
  public String toString() {
    return "ToolContext[cancellationSignal="
        + cancellationSignal
        + ", attributes="
        + attributes
        + "]";
  }
}
