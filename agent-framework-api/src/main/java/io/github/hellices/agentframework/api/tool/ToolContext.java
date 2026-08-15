package io.github.hellices.agentframework.api.tool;

import io.github.hellices.agentframework.api.agent.CancellationSignal;
import java.util.Map;

public record ToolContext(CancellationSignal cancellationSignal, Map<String, Object> attributes) {

  public ToolContext {
    cancellationSignal = cancellationSignal == null ? new CancellationSignal() : cancellationSignal;
    attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
  }
}
