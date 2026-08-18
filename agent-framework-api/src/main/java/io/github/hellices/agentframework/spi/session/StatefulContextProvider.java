package io.github.hellices.agentframework.spi.session;

import io.github.hellices.agentframework.api.agent.RunContribution;
import io.github.hellices.agentframework.api.session.SessionContext;
import io.github.hellices.agentframework.api.session.SessionStateKey;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * A {@link ContextProvider} that keeps durable per-session state under one typed {@link
 * SessionStateKey} (SES-012).
 *
 * <p>Unlike the stateless {@link ContextProvider}, this provider reserves a session-state
 * namespace: its {@link #stateKey()} names the slot the engine reads once at bind time to reject a
 * blank or duplicated key across configured stateful providers, before any run can mix two
 * providers' state. The engine creates a {@link ProviderSessionState} view for that key per run and
 * passes it to the typed hooks, so a provider observes exactly the state persisted for it and two
 * runs of the same provider instance over two sessions can never share one view.
 *
 * <p>The stateless {@link #prepare(SessionContext)} and {@link #complete(SessionContext)} the
 * engine invokes are implemented once here: they resolve this provider's typed view from the {@link
 * SessionContext} and delegate to the typed hooks. The engine therefore drives one ordered list of
 * {@link ContextProvider}s and never has to branch on whether a provider is stateful, while a
 * stateful provider still receives its bound state view.
 *
 * @param <S> the declared type of this provider's session state
 */
public interface StatefulContextProvider<S> extends ContextProvider {

  /**
   * Returns the fixed, typed session-state key this provider owns. The value is read once when the
   * agent is built; a blank or duplicated key across configured stateful providers is rejected
   * there.
   */
  SessionStateKey<S> stateKey();

  /**
   * Contributes context to the run before any model call, with this provider's bound state view.
   *
   * @param context the per-run session context, shared by every hook of this run
   * @param state this provider's key-bound session state view
   * @return a stage yielding this provider's contribution; neither the stage nor its value may be
   *     {@code null}
   */
  CompletionStage<RunContribution> prepare(SessionContext context, ProviderSessionState<S> state);

  /**
   * Observes the completed run and persists provider state, with this provider's bound state view.
   *
   * @param context the same per-run session context {@link #prepare} received
   * @param state this provider's key-bound session state view
   * @return a stage completing when the hook is done; must not be {@code null}
   */
  CompletionStage<Void> complete(SessionContext context, ProviderSessionState<S> state);

  /**
   * Resolves this provider's typed state view from {@code context} and delegates to {@link
   * #prepare(SessionContext, ProviderSessionState)}. The engine invokes only this stateless form,
   * so it retains one ordered provider list; the view is obtained here rather than dropped, so
   * state is never silently lost.
   */
  @Override
  default CompletionStage<RunContribution> prepare(SessionContext context) {
    Objects.requireNonNull(context, "context must not be null");
    return prepare(context, context.providerState(stateKey()));
  }

  /**
   * Resolves this provider's typed state view from {@code context} and delegates to {@link
   * #complete(SessionContext, ProviderSessionState)}.
   */
  @Override
  default CompletionStage<Void> complete(SessionContext context) {
    Objects.requireNonNull(context, "context must not be null");
    return complete(context, context.providerState(stateKey()));
  }
}
