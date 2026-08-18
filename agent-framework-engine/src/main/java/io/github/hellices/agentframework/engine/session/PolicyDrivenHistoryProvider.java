package io.github.hellices.agentframework.engine.session;

import io.github.hellices.agentframework.api.agent.RunContribution;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.MessageAttribution;
import io.github.hellices.agentframework.api.session.ContextMessageContribution;
import io.github.hellices.agentframework.api.session.SessionContext;
import io.github.hellices.agentframework.api.session.SessionStateKey;
import io.github.hellices.agentframework.spi.session.HistoryPolicy;
import io.github.hellices.agentframework.spi.session.HistoryProvider;
import io.github.hellices.agentframework.spi.session.ProviderSessionState;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * The engine convenience base that turns a storage-only {@link HistoryProvider} into a fully
 * policy-driven one (SES-013). It is offered so an implementor never has to re-derive the load and
 * store semantics, but it is not required: {@code HistoryProvider} is an open interface, and an
 * implementor that needs different lifecycle behaviour implements it directly without inheriting
 * from this class.
 *
 * <p>History lives in one typed session-state key. The durable slot a provider owns declares its
 * type up front: {@link #stateKey()} resolves the state codec by exact class, with no dynamic type
 * inference. A subclass implements storage only — {@link #load} and {@link #append} — and inherits
 * the policy-driven hooks. The same subclass is therefore a primary history when it loads and
 * stores, an audit sink when it only stores inputs, an evaluation sink when it only stores outputs,
 * and a context recorder when it stores what other providers contributed.
 *
 * <p><strong>Loading.</strong> {@link #prepare} returns loaded messages only when {@link
 * HistoryPolicy#loadMessages()} is enabled, in the order storage returned them, stamped as {@value
 * HistoryProvider#HISTORY_SOURCE_TYPE} attribution carrying this provider's state-key id, so
 * history stays distinguishable from the context a memory or retrieval provider contributed.
 * Stamping replaces the stored source type and source id; only an {@code originSessionId} the
 * storage already held is preserved, so a reloaded message still names the session that produced
 * it, and only a message carrying none gets this run's session id. The engine folds the returned
 * contribution into the run under this provider's state-key id.
 *
 * <p><strong>Storing.</strong> {@link #complete} builds exactly one ordered batch and hands it to
 * {@link #append} once. The batch is the run in conversation order: the selected context messages
 * first (they preceded the caller's input in the model request), then the caller's input, then the
 * run response's messages. Categories the policy disables are left out, and a run that selects
 * nothing does not call {@link #append} at all.
 *
 * <p><strong>Context selection.</strong> Selection reads {@link
 * SessionContext#contextContributions()}, so it keys off the provider that actually contributed a
 * message rather than the attribution the message carries — attribution may be preserved from
 * another session or set to any source id by a sibling provider. With {@link
 * HistoryPolicy#storeContextFrom()} absent ({@code null}), every context message except this
 * provider's own contributions is stored — re-storing its own loaded history would duplicate the
 * whole conversation on every run. With source ids configured, exactly those contributing sources
 * are stored, including this provider's own if it is named; context added without a contributing
 * provider ({@link SessionContext#addContextMessages(java.util.List)}) is external and can only be
 * selected by the absent-filter form. The selection is read only when {@link
 * HistoryPolicy#storeContextMessages()} is enabled.
 *
 * <p><strong>Duplication across providers.</strong> Two history providers that both store context
 * with no source filter each re-store the other's loaded prefix on every run, which grows the
 * conversation quadratically. Configure the secondary sink with {@link
 * HistoryPolicy#storeContextFrom()} — or with context storage disabled — when a primary history and
 * an audit sink observe the same run.
 *
 * @param <S> the declared type of this provider's session state
 */
public abstract class PolicyDrivenHistoryProvider<S> implements HistoryProvider<S> {

  private final SessionStateKey<S> stateKey;
  private final HistoryPolicy policy;

  /**
   * @param sourceId the fixed session-state namespace and attribution source id; must not be blank
   * @param stateType the exact class of the session state this provider owns; must not be {@code
   *     null}
   * @param policy the immutable load/store policy; must not be {@code null}
   * @throws IllegalArgumentException if {@code sourceId} is blank
   * @throws NullPointerException if {@code sourceId}, {@code stateType}, or {@code policy} is
   *     {@code null}
   */
  protected PolicyDrivenHistoryProvider(String sourceId, Class<S> stateType, HistoryPolicy policy) {
    this(new Binding<>(sourceId, stateType, policy));
  }

  private PolicyDrivenHistoryProvider(Binding<S> binding) {
    this.stateKey = SessionStateKey.of(binding.sourceId(), binding.stateType());
    this.policy = binding.policy();
  }

  /**
   * Validates the constructor arguments before {@code PolicyDrivenHistoryProvider} itself starts
   * constructing, so a rejected argument cannot leave a partially initialised subclass instance
   * behind.
   */
  private record Binding<S>(String sourceId, Class<S> stateType, HistoryPolicy policy) {

    private Binding {
      Objects.requireNonNull(sourceId, "sourceId must not be null");
      if (sourceId.isBlank()) {
        throw new IllegalArgumentException("sourceId must not be blank");
      }
      Objects.requireNonNull(stateType, "stateType must not be null");
      Objects.requireNonNull(policy, "policy must not be null");
    }
  }

  @Override
  public final SessionStateKey<S> stateKey() {
    return stateKey;
  }

  /** Returns the immutable policy this provider was configured with. */
  @Override
  public final HistoryPolicy policy() {
    return policy;
  }

  /**
   * Loads stored history into the run when {@link HistoryPolicy#loadMessages()} is enabled, and
   * contributes nothing otherwise. Override to control loading beyond the policy; the override owns
   * attribution and ordering in that case.
   */
  @Override
  public CompletionStage<RunContribution> prepare(
      SessionContext context, ProviderSessionState<S> state) {
    Objects.requireNonNull(context, "context must not be null");
    Objects.requireNonNull(state, "state must not be null");
    if (!policy.loadMessages()) {
      return CompletableFuture.completedFuture(RunContribution.empty());
    }
    return Objects.requireNonNull(
            load(context, state), "history provider load stage must not be null")
        .thenApply(
            messages -> RunContribution.builder().messages(asHistory(context, messages)).build());
  }

  /**
   * Stores the batch the policy selects from the completed run. Override to control storing beyond
   * the policy; the override owns batch composition in that case.
   *
   * <p>The framework calls this only after the run succeeded and the response slot was filled. When
   * {@link HistoryPolicy#storeOutputs()} is enabled but no response was recorded — which happens
   * only outside that framework path — the remaining selected categories are still stored, so a
   * caller driving the hooks directly never loses the input it already accepted.
   */
  @Override
  public CompletionStage<Void> complete(SessionContext context, ProviderSessionState<S> state) {
    Objects.requireNonNull(context, "context must not be null");
    Objects.requireNonNull(state, "state must not be null");
    List<Message> batch = messagesToStore(context);
    if (batch.isEmpty()) {
      return CompletableFuture.completedFuture(null);
    }
    return Objects.requireNonNull(
        append(context, state, batch), "history provider append stage must not be null");
  }

  private List<Message> asHistory(SessionContext context, List<Message> messages) {
    Objects.requireNonNull(messages, "history provider messages must not be null");
    String currentSessionId = context.session() == null ? null : context.session().sessionId();
    List<Message> history = new ArrayList<>();
    for (Message message : messages) {
      Objects.requireNonNull(message, "history provider messages must not contain null entries");
      MessageAttribution stored = message.attribution();
      String originSessionId =
          stored == null || stored.originSessionId() == null
              ? currentSessionId
              : stored.originSessionId();
      history.add(
          message.withAttribution(
              new MessageAttribution(
                  HistoryProvider.HISTORY_SOURCE_TYPE, stateKey.id(), originSessionId)));
    }
    return List.copyOf(history);
  }

  private List<Message> messagesToStore(SessionContext context) {
    List<Message> batch = new ArrayList<>(contextMessagesToStore(context));
    if (policy.storeInputs()) {
      batch.addAll(context.inputMessages());
    }
    if (policy.storeOutputs()) {
      context.response().ifPresent(response -> batch.addAll(response.messages()));
    }
    return List.copyOf(batch);
  }

  private List<Message> contextMessagesToStore(SessionContext context) {
    if (!policy.storeContextMessages()) {
      return List.of();
    }
    Set<String> selectedSources = policy.storeContextFrom();
    List<Message> selected = new ArrayList<>();
    for (ContextMessageContribution contribution : context.contextContributions()) {
      String contributor = contribution.sourceId();
      boolean store =
          selectedSources == null
              ? !stateKey.id().equals(contributor)
              : contributor != null && selectedSources.contains(contributor);
      if (store) {
        selected.add(contribution.message());
      }
    }
    return selected;
  }
}
