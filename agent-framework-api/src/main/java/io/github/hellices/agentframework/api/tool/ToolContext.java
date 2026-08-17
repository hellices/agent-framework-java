package io.github.hellices.agentframework.api.tool;

import io.github.hellices.agentframework.api.agent.CancellationSignal;
import io.github.hellices.agentframework.api.context.ContextAttributes;

public record ToolContext(CancellationSignal cancellationSignal, ContextAttributes attributes) {

  public ToolContext {
    cancellationSignal = cancellationSignal == null ? new CancellationSignal() : cancellationSignal;
    attributes = attributes == null ? ContextAttributes.empty() : attributes;
  }
}
