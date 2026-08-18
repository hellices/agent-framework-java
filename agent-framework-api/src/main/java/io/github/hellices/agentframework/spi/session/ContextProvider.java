package io.github.hellices.agentframework.spi.session;

import io.github.hellices.agentframework.api.agent.RunContribution;
import io.github.hellices.agentframework.api.session.SessionContext;
import java.util.concurrent.CompletionStage;

/**
 * A context provider participates in a run through two hooks only (SES-012): {@link #prepare} runs
 * before any model call and returns the {@link RunContribution} it adds to the run, and {@link
 * #complete} runs after the run's response is known.
 *
 * <p>Hook ordering follows the upstream forward-before / response-then-reverse-after model: the
 * engine composes {@code prepare} in provider declaration order, folds each returned {@link
 * RunContribution} into the model request, performs the model call, fills the run's {@link
 * SessionContext} response slot, and then composes {@code complete} in reverse declaration order,
 * so a provider always closes over the context its neighbours opened.
 *
 * <p>This stateless form owns no session-state namespace: it reserves nothing, so any number of
 * stateless providers can be configured on one agent without a namespace collision. A provider that
 * needs durable per-session state implements {@link StatefulContextProvider} instead, which binds a
 * typed {@link io.github.hellices.agentframework.api.session.SessionStateKey} and receives a {@link
 * ProviderSessionState} view. A provider instance is shared across sessions, so state kept in
 * provider fields would leak between sessions; durable state belongs only in that view.
 *
 * <p>Hooks are asynchronous and must never block: return a stage that completes when the work is
 * done. {@code prepare} must return a non-null stage that yields a non-null {@link RunContribution}
 * — a provider that contributes nothing returns {@link RunContribution#empty()}. A hook that fails
 * (returned stage completes exceptionally, or the hook throws) fails the run without running any
 * later phase.
 */
public interface ContextProvider {

  /**
   * Contributes context to the run before any model call. The returned {@link RunContribution} is
   * folded into the model request in provider declaration order, so its messages precede the
   * caller's input.
   *
   * @param context the per-run session context, shared by every hook of this run
   * @return a stage yielding this provider's contribution; neither the stage nor its value may be
   *     {@code null}
   */
  CompletionStage<RunContribution> prepare(SessionContext context);

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
   * emits {@code onComplete} only after every {@code complete} hook and the session save succeeded.
   * The authoritative run outcome is still {@code AgentStreamingRun.response()}.
   *
   * @param context the same per-run session context {@link #prepare} received
   * @return a stage completing when the hook is done; must not be {@code null}
   */
  CompletionStage<Void> complete(SessionContext context);
}
