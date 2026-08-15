package io.github.hellices.agentframework.api.agent;

import io.github.hellices.agentframework.api.session.SessionContext;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

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
    SessionContext sessionContext = newSessionContext(normalizedRequest);
    AgentRun run =
        runInternal(
            new AgentRunContext(this, session, normalizedRequest.attributes(), sessionContext),
            normalizedRequest);
    registerCompletion(sessionContext, run.response());
    return run;
  }

  public final AgentStreamingRun<AgentResponseUpdate> runStreaming(String input) {
    Objects.requireNonNull(input, "input must not be null");
    return runStreaming(AgentRunRequest.of(input));
  }

  public final AgentStreamingRun<AgentResponseUpdate> runStreaming(AgentRunRequest request) {
    AgentRunRequest normalizedRequest = Objects.requireNonNull(request, "request must not be null");
    AgentSession session = normalizedRequest.session();
    if (session != null) {
      validateSessionCompatibility(session);
    }
    SessionContext sessionContext = newSessionContext(normalizedRequest);
    AgentStreamingRun<AgentResponseUpdate> run =
        runStreamingInternal(
            new AgentRunContext(this, session, normalizedRequest.attributes(), sessionContext),
            normalizedRequest);
    registerCompletion(sessionContext, run.response());
    return run;
  }

  protected void validateSessionCompatibility(AgentSession session) {
    Objects.requireNonNull(session, "session must not be null");
  }

  protected abstract AgentRun runInternal(AgentRunContext context, AgentRunRequest request);

  protected abstract AgentStreamingRun<AgentResponseUpdate> runStreamingInternal(
      AgentRunContext context, AgentRunRequest request);

  private static SessionContext newSessionContext(AgentRunRequest request) {
    return new SessionContext(
        request.session(), request.messages(), request.attributes(), request.cancellationSignal());
  }

  /**
   * Fills the run's {@link SessionContext} response slot only when the run completes successfully.
   * Failed or cancelled runs never populate the response slot, so callers can rely on {@code
   * sessionContext.response()} being present exactly when the run's terminal response stage
   * completed without error. Centralizing this here ensures every {@code Agent} subclass, including
   * custom ones, gets the same completion semantics regardless of how {@code runInternal}/{@code
   * runStreamingInternal} are implemented.
   */
  private static void registerCompletion(
      SessionContext sessionContext, CompletionStage<AgentResponse> response) {
    response.whenComplete(
        (value, failure) -> {
          if (failure == null) {
            sessionContext.complete(value);
          }
        });
  }
}
