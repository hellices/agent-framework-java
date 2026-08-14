package io.github.hellices.agentframework.api.agent;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class AgentRunOptions {

  private final Map<String, Object> attributes;

  public AgentRunOptions() {
    this(Map.of());
  }

  public AgentRunOptions(Map<String, Object> attributes) {
    this.attributes = attributes == null ? Map.of() : Collections.unmodifiableMap(new HashMap<>(attributes));
  }

  public Map<String, Object> attributes() {
    return attributes;
  }
}
