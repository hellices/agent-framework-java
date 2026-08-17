package io.github.hellices.agentframework.api.agent;

import io.github.hellices.agentframework.api.context.ContextAttributes;
import io.github.hellices.agentframework.api.session.SessionContext;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;

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
    ContextAttributes effectiveAttributes = effectiveAttributes(normalizedRequest);
    SessionContext sessionContext = newSessionContext(normalizedRequest, effectiveAttributes);
    AgentRun run =
        runInternal(
            new AgentRunContext(this, session, effectiveAttributes, sessionContext),
            normalizedRequest);
    AgentRun completed = run.withCompletion(completionAction(sessionContext));
    return completed.withResponse(
        afterRunStage(sessionContext, completed.response()), sessionContext::updatedSession);
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
    ContextAttributes effectiveAttributes = effectiveAttributes(normalizedRequest);
    SessionContext sessionContext = newSessionContext(normalizedRequest, effectiveAttributes);
    AgentStreamingRun<AgentResponseUpdate> run =
        runStreamingInternal(
            new AgentRunContext(this, session, effectiveAttributes, sessionContext),
            normalizedRequest);
    AgentStreamingRun<AgentResponseUpdate> completed =
        run.withCompletion(completionAction(sessionContext));
    return completed.withResponse(
        afterRunStage(sessionContext, completed.response()), sessionContext::updatedSession);
  }

  protected void validateSessionCompatibility(AgentSession session) {
    Objects.requireNonNull(session, "session must not be null");
  }

  protected abstract AgentRun runInternal(AgentRunContext context, AgentRunRequest request);

  protected abstract AgentStreamingRun<AgentResponseUpdate> runStreamingInternal(
      AgentRunContext context, AgentRunRequest request);

  /**
   * Framework lifecycle seam invoked exactly once per run, only after the run's terminal response
   * stage completed successfully and the run's {@link SessionContext} response slot was filled, and
   * always before the caller-visible response stage of the returned {@link AgentRun} / {@link
   * AgentStreamingRun} completes.
   *
   * <p>It exists so an implementation (for example the engine's context provider pipeline) can
   * observe the finished run through the same per-run {@code SessionContext} the run started with,
   * without the run's response slot being completed twice and without the ordering caller code sees
   * being changed. A failed or cancelled run never reaches this seam, so no success-only work is
   * performed for it. A stage returned here that fails, or a {@code null} stage, fails the run
   * rather than being swallowed.
   *
   * <p>Because this seam is the last lifecycle step, it is also the fence for {@link
   * AgentRun#session()}: the session that stage publishes is read only after this seam succeeded,
   * so an implementation that persists the session here (the engine's session save) publishes only
   * what it durably wrote, and a failure here fails the session stage rather than reporting state
   * the run never committed.
   *
   * <p>On a streaming run the seam necessarily runs after the update stream reached its terminal
   * signal, since the response it observes only exists once streaming finished. A failure here
   * therefore fails {@link AgentStreamingRun#response()} while the update subscriber has already
   * seen {@code onComplete}. That is the deliberate contract: the update stream signals model
   * transport completion, and the authoritative run outcome is the response stage. The alternative
   * would require emitting a second terminal signal on an already-terminated stream, which Reactive
   * Streams forbids.
   *
   * @param sessionContext the completed per-run session context
   * @return a stage completing when the post-run work is done; must not be {@code null}
   */
  protected CompletionStage<Void> afterRun(SessionContext sessionContext) {
    return CompletableFuture.completedFuture(null);
  }

  private static SessionContext newSessionContext(
      AgentRunRequest request, ContextAttributes effectiveAttributes) {
    return new SessionContext(
        request.session(), request.messages(), effectiveAttributes, request.cancellationSignal());
  }

  private static ContextAttributes effectiveAttributes(AgentRunRequest request) {
    return request.options().attributes().merge(request.attributes());
  }

  /**
   * Extends a run's already-completing response stage (the one that fills the {@link
   * SessionContext} response slot) with the {@link #afterRun(SessionContext)} lifecycle seam,
   * republishing the same {@link AgentResponse} once the seam's stage completed. Because the seam
   * is composed onto the stage callers actually observe, joining a run cannot return before the
   * session context was filled and the seam finished, and neither a completion failure nor a seam
   * failure can be lost. A failed or cancelled run short-circuits the composition, so the seam is
   * skipped and the original failure reaches the caller unchanged.
   */
  private CompletionStage<AgentResponse> afterRunStage(
      SessionContext sessionContext, CompletionStage<AgentResponse> completedResponse) {
    return completedResponse.thenCompose(
        value ->
            Objects.requireNonNull(afterRun(sessionContext), "afterRun stage must not be null")
                .thenApply(ignored -> value));
  }

  /**
   * Builds the success-only completion action that fills the run's {@link SessionContext} response
   * slot. Failed or cancelled runs never populate the response slot, so callers can rely on {@code
   * sessionContext.response()} being present exactly when the run's terminal response stage
   * completed without error. This action is attached via {@link AgentRun#withCompletion} / {@link
   * AgentStreamingRun#withCompletion}, which expose a response stage derived from it, so every
   * {@code Agent} subclass (including custom ones) gets the same completion semantics regardless of
   * how {@code runInternal}/{@code runStreamingInternal} are implemented, and any exception this
   * action throws (for example a pre-filled or already-completed response slot) propagates through
   * the returned run's response stage instead of being swallowed, skipping {@link
   * #afterRun(SessionContext)}.
   */
  private static BiConsumer<AgentResponse, Throwable> completionAction(
      SessionContext sessionContext) {
    return (value, failure) -> {
      if (failure == null) {
        sessionContext.complete(value);
      }
    };
  }
}
