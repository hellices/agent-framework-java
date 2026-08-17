package io.github.hellices.agentframework.api.agent;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

public final class AgentRun {

  private final CompletionStage<AgentResponse> response;
  private final CompletionStage<Optional<AgentSession>> session;
  private final CancellationSignal cancellationSignal;
  private final Runnable cancellationAction;
  private final boolean engineManaged;

  public AgentRun(AgentResponse response) {
    this(
        CompletableFuture.completedFuture(
            Objects.requireNonNull(response, "response must not be null")),
        new CancellationSignal());
  }

  public AgentRun(CompletionStage<AgentResponse> response, CancellationSignal cancellationSignal) {
    CompletionStage<AgentResponse> source =
        Objects.requireNonNull(response, "response must not be null");
    this.cancellationSignal =
        cancellationSignal == null ? new CancellationSignal() : cancellationSignal;
    this.response = CancellationAwareResponse.wrap(source, this.cancellationSignal);
    this.session = noSession();
    this.cancellationAction = this.cancellationSignal::cancel;
    this.engineManaged = false;
  }

  private AgentRun(
      CompletionStage<AgentResponse> response,
      CompletionStage<Optional<AgentSession>> session,
      CancellationSignal cancellationSignal,
      Runnable cancellationAction,
      boolean engineManaged) {
    this.response = Objects.requireNonNull(response, "response must not be null");
    this.session = Objects.requireNonNull(session, "session must not be null");
    this.cancellationSignal =
        Objects.requireNonNull(cancellationSignal, "cancellationSignal must not be null");
    this.cancellationAction =
        Objects.requireNonNull(cancellationAction, "cancellationAction must not be null");
    this.engineManaged = engineManaged;
  }

  /**
   * Builds a run whose whole post-run lifecycle — filling the {@link SessionContext} response slot,
   * running context providers' {@code afterRun} hooks, and persisting the session — is already
   * owned by the engine that produced {@code response}. The {@link Agent} facade must not re-run
   * its own completion and {@code afterRun} seam on such a run (that would complete the response
   * slot twice); it returns the run unchanged. The {@link #session()} stage is read from {@code
   * response}, so it reports exactly the session the engine durably wrote and fails identically
   * when the engine's lifecycle failed.
   */
  public static AgentRun engineManaged(
      CompletionStage<AgentResponse> response,
      CancellationSignal cancellationSignal,
      Supplier<Optional<AgentSession>> updatedSession) {
    CompletionStage<AgentResponse> source =
        Objects.requireNonNull(response, "response must not be null");
    Supplier<Optional<AgentSession>> session =
        Objects.requireNonNull(updatedSession, "updatedSession must not be null");
    CancellationSignal signal =
        cancellationSignal == null ? new CancellationSignal() : cancellationSignal;
    CompletionStage<AgentResponse> wrapped = CancellationAwareResponse.wrap(source, signal);
    return new AgentRun(
        wrapped, wrapped.thenApply(ignored -> session.get()), signal, signal::cancel, true);
  }

  boolean isEngineManaged() {
    return engineManaged;
  }

  public CompletionStage<AgentResponse> response() {
    return response;
  }

  /**
   * Returns the session this run produced, or empty when the run carried no session.
   *
   * <p>{@link AgentSession} is immutable, so a run that changes session state — a context provider
   * appending this turn to the conversation history, for example — cannot report it through the
   * session object the caller passed in. This stage is that report: passing its value to the next
   * run is what replays the conversation when no {@code SessionStore} is configured, and it is the
   * effective session (the stored one, when a run loaded one) even for a run that changed nothing.
   *
   * <p>It resolves from the same authoritative outcome {@link #response()} carries, so it completes
   * only after the run's post-run lifecycle succeeded — including the session save of an agent with
   * a configured store. A run that failed, was cancelled, or could not be persisted therefore fails
   * this stage too, rather than publishing state that was never durably written.
   */
  public CompletionStage<Optional<AgentSession>> session() {
    return session;
  }

  public void cancel() {
    cancellationAction.run();
  }

  /**
   * Package-private copy used by {@link Agent} to observe run completion without losing the
   * original cancellation wiring. The returned run's exposed response stage is derived via {@link
   * CompletionStage#whenComplete(BiConsumer)}, so joining it can only return once {@code
   * completion} has finished running: any exception {@code completion} throws (including {@code
   * SessionContext} lifecycle violations such as a pre-filled or double-completed response slot)
   * propagates through the returned run's response stage instead of being swallowed. The original
   * {@code cancellationAction} is preserved unchanged, so {@link #cancel()} keeps cancelling the
   * same underlying signal.
   */
  AgentRun withCompletion(BiConsumer<? super AgentResponse, ? super Throwable> completion) {
    Objects.requireNonNull(completion, "completion must not be null");
    return withResponse(response.whenComplete(completion));
  }

  /**
   * Package-private copy used by {@link Agent} to expose a response stage it derived from this
   * run's own stage (session context completion followed by the {@code afterRun} lifecycle seam),
   * while keeping the original cancellation wiring. Because the derived stage is the one callers
   * observe, every lifecycle step composed into it has finished before a caller's join returns, and
   * any failure it carries is surfaced instead of swallowed.
   *
   * <p>The derived stage is re-wrapped with the same cancellation handling the public constructor
   * applies, so {@link #cancel()} stays effective for the whole window in which callers are still
   * waiting - including after this run's own stage completed successfully while a lifecycle step
   * such as {@code afterRun} is still pending. The original {@code cancellationAction} and signal
   * are preserved, so cancellation keeps reaching the same underlying run.
   */
  AgentRun withResponse(CompletionStage<AgentResponse> derivedResponse) {
    return copy(derivedResponse, null);
  }

  /**
   * Package-private copy used by {@link Agent} for the final run it hands back, adding the
   * authoritative {@link #session()} stage on top of {@link #withResponse(CompletionStage)}.
   *
   * <p>{@code updatedSession} is read from the caller-visible response stage rather than from the
   * run's own stage, so the published session is exactly the one that existed when the run's whole
   * lifecycle — including an agent's post-run persistence — succeeded, and a lifecycle failure
   * fails both stages identically instead of publishing state the run never committed.
   */
  AgentRun withResponse(
      CompletionStage<AgentResponse> derivedResponse,
      Supplier<Optional<AgentSession>> updatedSession) {
    return copy(
        derivedResponse, Objects.requireNonNull(updatedSession, "updatedSession must not be null"));
  }

  private AgentRun copy(
      CompletionStage<AgentResponse> derivedResponse,
      Supplier<Optional<AgentSession>> updatedSession) {
    CompletionStage<AgentResponse> wrapped =
        CancellationAwareResponse.wrap(
            Objects.requireNonNull(derivedResponse, "response must not be null"),
            cancellationSignal);
    return new AgentRun(
        wrapped,
        updatedSession == null ? session : wrapped.thenApply(ignored -> updatedSession.get()),
        cancellationSignal,
        cancellationAction,
        engineManaged);
  }

  static CompletionStage<Optional<AgentSession>> noSession() {
    return CompletableFuture.<Optional<AgentSession>>completedFuture(Optional.empty())
        .minimalCompletionStage();
  }
}
