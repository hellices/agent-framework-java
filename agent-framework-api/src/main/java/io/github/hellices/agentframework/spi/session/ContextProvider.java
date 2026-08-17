package io.github.hellices.agentframework.spi.session;

import io.github.hellices.agentframework.api.session.SessionContext;
import java.util.concurrent.CompletionStage;

/**
 * A context provider participates in a run through two hooks only (SES-012): {@link #beforeRun}
 * runs before any model call, and {@link #afterRun} runs after the run's response is known.
 *
 * <p>Hook ordering follows the upstream forward-before / response-then-reverse-after model: the
 * engine composes {@code beforeRun} in provider declaration order, performs the model call, fills
 * the run's {@link SessionContext} response slot, and then composes {@code afterRun} in reverse
 * declaration order, so a provider always closes over the context its neighbours opened.
 *
 * <p>All persistent per-session state belongs in the {@link ProviderSessionState} view the engine
 * passes in, which is bound to this provider's {@link #sourceId()}. A provider instance is shared
 * across sessions, so state kept in provider fields would leak between sessions. Confining session
 * state access to that view is part of this contract: reaching another namespace through the shared
 * {@link SessionContext} is framework plumbing, not provider behaviour.
 *
 * <p>Hooks are asynchronous and must never block: return a stage that completes when the work is
 * done. A hook that fails (returned stage completes exceptionally, or the hook throws) fails the
 * run without running any later phase.
 */
public interface ContextProvider {

  /**
   * Returns the fixed, non-blank session-state namespace key for this provider. The value is read
   * once when the agent is built; duplicate ids across configured providers are rejected there.
   */
  String sourceId();

  /**
   * Contributes context to the run before any model call. Providers add context messages through
   * {@link SessionContext#addContextMessages(String, java.util.List)} using {@link
   * ProviderSessionState#sourceId()}; those messages precede the caller's input in the model
   * request.
   *
   * @param context the per-run session context, shared by every hook of this run
   * @param state this provider's source-bound session state view
   * @return a stage completing when the hook is done; must not be {@code null}
   */
  CompletionStage<Void> beforeRun(SessionContext context, ProviderSessionState state);

  /**
   * Observes the completed run and persists provider state. It runs only when the run completed
   * successfully, after {@code context.response()} is filled, and before the caller-visible run
   * response stage completes.
   *
   * <p>On a streaming run this hook runs after every model update was already delivered, because
   * the run's response is only known once the model finished streaming. The engine owns the
   * streaming run's terminal lifecycle, so a hook failure fails the run's response stage
   * <em>and</em> the update stream: the subscriber sees {@code onError} rather than {@code
   * onComplete}, and never a terminal signal for a run whose providers failed. A successful run
   * emits {@code onComplete} only after every {@code afterRun} hook and the session save succeeded.
   * The authoritative run outcome is still {@code AgentStreamingRun.response()}.
   *
   * @param context the same per-run session context {@link #beforeRun} received
   * @param state this provider's source-bound session state view
   * @return a stage completing when the hook is done; must not be {@code null}
   */
  CompletionStage<Void> afterRun(SessionContext context, ProviderSessionState state);
}
