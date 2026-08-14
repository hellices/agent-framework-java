package io.github.hellices.agentframework.api.agent;

import java.util.Objects;
import java.util.UUID;

public abstract class Agent {

  private final String id;
  private final String name;
  private final String description;

  protected Agent() {
    this(UUID.randomUUID().toString(), "agent", "");
  }

  protected Agent(String id, String name, String description) {
    this.id = id == null || id.isBlank() ? UUID.randomUUID().toString() : id;
    this.name = name == null || name.isBlank() ? "agent" : name;
    this.description = description == null ? "" : description;
  }

  public final String id() {
    return id;
  }

  public final String name() {
    return name;
  }

  public final String description() {
    return description;
  }

  public AgentRun run(String input) {
    Objects.requireNonNull(input, "input must not be null");
    return run(AgentRunRequest.of(input));
  }

  public abstract AgentRun run(AgentRunRequest request);

  public AgentStreamingRun runStreaming(String input) {
    Objects.requireNonNull(input, "input must not be null");
    return runStreaming(AgentRunRequest.of(input));
  }

  public abstract AgentStreamingRun runStreaming(AgentRunRequest request);
}
