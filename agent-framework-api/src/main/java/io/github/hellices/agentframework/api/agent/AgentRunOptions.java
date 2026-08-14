package io.github.hellices.agentframework.api.agent;

import java.util.Map;

public final class AgentRunOptions {

  private final Map<String, Object> attributes;

  public AgentRunOptions() {
    this(Map.of());
  }

  public AgentRunOptions(Map<String, Object> attributes) {
    this.attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
  }

  public Map<String, Object> attributes() {
    return attributes;
  }
}
