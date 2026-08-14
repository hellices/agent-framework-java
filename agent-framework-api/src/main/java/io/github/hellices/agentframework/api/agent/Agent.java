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

  public final AgentRun run(String input) {
    Objects.requireNonNull(input, "input must not be null");
    return run(AgentRunRequest.of(input));
  }

  public final AgentRun run(AgentRunRequest request) {
    AgentRunRequest normalizedRequest = Objects.requireNonNull(request, "request must not be null");
    AgentSession session = normalizedRequest.session();
    if (session != null) {
      validateSessionCompatibility(session);
    }
    return runInternal(new AgentRunContext(this, session, normalizedRequest.attributes()), normalizedRequest);
  }

  public final AgentStreamingRun runStreaming(String input) {
    Objects.requireNonNull(input, "input must not be null");
    return runStreaming(AgentRunRequest.of(input));
  }

  public final AgentStreamingRun runStreaming(AgentRunRequest request) {
    AgentRunRequest normalizedRequest = Objects.requireNonNull(request, "request must not be null");
    AgentSession session = normalizedRequest.session();
    if (session != null) {
      validateSessionCompatibility(session);
    }
    return runStreamingInternal(
        new AgentRunContext(this, session, normalizedRequest.attributes()), normalizedRequest);
  }

  protected void validateSessionCompatibility(AgentSession session) {
    Objects.requireNonNull(session, "session must not be null");
  }

  protected abstract AgentRun runInternal(AgentRunContext context, AgentRunRequest request);

  protected abstract AgentStreamingRun runStreamingInternal(
      AgentRunContext context, AgentRunRequest request);
}
