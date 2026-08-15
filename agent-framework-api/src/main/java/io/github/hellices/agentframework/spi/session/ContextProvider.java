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
   * <p>On a streaming run this hook runs after the update stream already reached its terminal
   * signal, because the run's response is only known once the model finished streaming. A hook
   * failure therefore fails the run's response stage while the update subscriber has already seen
   * {@code onComplete}: the update stream reports model transport completion, and the authoritative
   * run outcome is {@code AgentStreamingRun.response()}. This split is deliberate — turning a
   * post-stream failure into a stream {@code onError} would emit a terminal signal after a terminal
   * signal, which Reactive Streams forbids. A caller that must not act on a run whose providers
   * failed waits for the response stage, not for {@code onComplete}.
   *
   * @param context the same per-run session context {@link #beforeRun} received
   * @param state this provider's source-bound session state view
   * @return a stage completing when the hook is done; must not be {@code null}
   */
  CompletionStage<Void> afterRun(SessionContext context, ProviderSessionState state);
}
