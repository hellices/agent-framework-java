package io.github.hellices.agentframework.spi.session;

import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.MessageAttribution;
import io.github.hellices.agentframework.api.session.SessionContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * The single history contract of SES-013: one {@link ContextProvider} whose load and store
 * behaviour is decided by an immutable {@link HistoryPolicy} rather than by a family of subtypes.
 *
 * <p>A subclass implements storage only — {@link #getMessages} and {@link #saveMessages} — and
 * inherits the policy-driven hooks. The same implementation is therefore a primary history when it
 * loads and stores, an audit sink when it only stores inputs, an evaluation sink when it only
 * stores outputs, and a context recorder when it stores what other providers contributed.
 *
 * <p><strong>Loading.</strong> {@link #beforeRun} runs only when {@link
 * HistoryPolicy#loadMessages()} is enabled. Loaded messages are injected in the order the storage
 * returned them and are stamped as {@value #HISTORY_SOURCE_TYPE} attribution carrying this
 * provider's {@link #sourceId()}, so history stays distinguishable from the context a memory or
 * retrieval provider contributed. The stamping preserves an {@code originSessionId} the storage
 * already held, so provenance of content produced by another session survives a reload; only when a
 * message carries none is this run's session id filled in.
 *
 * <p><strong>Storing.</strong> {@link #afterRun} builds exactly one ordered batch and hands it to
 * {@link #saveMessages} once. The batch is the run in conversation order: the selected context
 * messages first (they preceded the caller's input in the model request), then the caller's input,
 * then the run response's messages. Categories the policy disables are left out, and a run that
 * selects nothing does not call {@link #saveMessages} at all.
 *
 * <p><strong>Context selection.</strong> With {@link HistoryPolicy#storeContextFrom()} empty, every
 * context message except this provider's own loaded history is stored — re-storing its own history
 * would duplicate the whole conversation on every run. With source ids configured, exactly those
 * sources are stored. The selection is read only when {@link HistoryPolicy#storeContextMessages()}
 * is enabled.
 *
 * <p><strong>Security.</strong> Loaded history is not validated or sanitized by the framework, and
 * neither is context contributed by another provider. A storage backend that can be tampered with
 * can therefore change roles or inject adversarial content into a run.
 */
public abstract class HistoryProvider implements ContextProvider {

  /**
   * The attribution source type stamped onto loaded history, matching the pinned upstream chat
   * history source type so a message's provenance reads the same in Java as it does upstream.
   */
  public static final String HISTORY_SOURCE_TYPE = "ChatHistory";

  private final String sourceId;
  private final HistoryPolicy policy;

  /**
   * @param sourceId the fixed session-state namespace and attribution source id; must not be blank
   * @param policy the immutable load/store policy; must not be {@code null}
   * @throws IllegalArgumentException if {@code sourceId} is blank
   * @throws NullPointerException if {@code sourceId} or {@code policy} is {@code null}
   */
  protected HistoryProvider(String sourceId, HistoryPolicy policy) {
    this(new Binding(sourceId, policy));
  }

  private HistoryProvider(Binding binding) {
    this.sourceId = binding.sourceId();
    this.policy = binding.policy();
  }

  /**
   * Validates the constructor arguments before {@code HistoryProvider} itself starts constructing,
   * so a rejected argument cannot leave a partially initialised subclass instance behind.
   */
  private record Binding(String sourceId, HistoryPolicy policy) {

    private Binding {
      Objects.requireNonNull(sourceId, "sourceId must not be null");
      if (sourceId.isBlank()) {
        throw new IllegalArgumentException("sourceId must not be blank");
      }
      Objects.requireNonNull(policy, "policy must not be null");
    }
  }

  @Override
  public final String sourceId() {
    return sourceId;
  }

  /** Returns the immutable policy this provider was configured with. */
  public final HistoryPolicy policy() {
    return policy;
  }

  /**
   * Reads this session's stored history, oldest message first.
   *
   * @param context the per-run context, for a storage backend that keys history by session id
   * @param state this provider's source-bound session state view
   * @return a stage carrying the stored messages; neither the stage, the list, nor an entry may be
   *     {@code null}
   */
  protected abstract CompletionStage<List<Message>> getMessages(
      SessionContext context, ProviderSessionState state);

  /**
   * Persists one ordered batch for this session, appending it after what is already stored.
   *
   * @param context the per-run context, for a storage backend that keys history by session id
   * @param state this provider's source-bound session state view
   * @param messages the non-empty ordered batch selected by the policy
   * @return a stage completing when the batch is persisted; must not be {@code null}
   */
  protected abstract CompletionStage<Void> saveMessages(
      SessionContext context, ProviderSessionState state, List<Message> messages);

  /**
   * Loads stored history into the run when {@link HistoryPolicy#loadMessages()} is enabled, and
   * does nothing otherwise. Override to control loading beyond the policy; the override owns
   * attribution and ordering in that case.
   */
  @Override
  public CompletionStage<Void> beforeRun(SessionContext context, ProviderSessionState state) {
    Objects.requireNonNull(context, "context must not be null");
    Objects.requireNonNull(state, "state must not be null");
    if (!policy.loadMessages()) {
      return CompletableFuture.completedFuture(null);
    }
    return Objects.requireNonNull(
            getMessages(context, state), "history provider get-messages stage must not be null")
        .thenAccept(messages -> context.addContextMessages(sourceId, asHistory(context, messages)));
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
  public CompletionStage<Void> afterRun(SessionContext context, ProviderSessionState state) {
    Objects.requireNonNull(context, "context must not be null");
    Objects.requireNonNull(state, "state must not be null");
    List<Message> batch = messagesToStore(context);
    if (batch.isEmpty()) {
      return CompletableFuture.completedFuture(null);
    }
    return Objects.requireNonNull(
        saveMessages(context, state, batch),
        "history provider save-messages stage must not be null");
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
              new MessageAttribution(HISTORY_SOURCE_TYPE, sourceId, originSessionId)));
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
    for (Message message : context.contextMessages()) {
      MessageAttribution attribution = message.attribution();
      String messageSourceId = attribution == null ? null : attribution.sourceId();
      boolean store =
          selectedSources.isEmpty()
              ? !sourceId.equals(messageSourceId)
              : messageSourceId != null && selectedSources.contains(messageSourceId);
      if (store) {
        selected.add(message);
      }
    }
    return selected;
  }
}
