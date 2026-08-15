package io.github.hellices.agentframework.api.session;

import io.github.hellices.agentframework.api.agent.AgentResponse;
import io.github.hellices.agentframework.api.agent.AgentSession;
import io.github.hellices.agentframework.api.agent.CancellationSignal;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.MessageAttribution;
import io.github.hellices.agentframework.spi.session.ProviderSessionState;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The independent per-run execution context described by SES-011. A fresh instance is created for
 * every ordinary and streaming {@code Agent} run; it carries the session identity, the caller's
 * input messages, an ordered list of provider-contributed context messages, run metadata, the
 * request's cancellation signal, and a set-once response slot that is only filled on successful
 * terminal completion.
 *
 * <p>It also owns the source-bound {@link ProviderSessionState} views used by the context provider
 * pipeline (SES-012). Views are created lazily per {@code sourceId} from {@link
 * AgentSession#state()} and memoized for the lifetime of the run, so a provider's {@code beforeRun}
 * and {@code afterRun} hooks observe one state view, and two runs of the same provider instance
 * over two sessions can never share one.
 *
 * <p>Every context message is recorded together with the provider that contributed it, so {@link
 * #contextContributions()} can answer "who added this?" for a consumer that selects context by
 * source, while {@link #contextMessages()} stays the plain ordered message list the model request
 * is built from.
 *
 * <p>A run that loads a stored session has its session replaced once through {@link
 * #hydrate(AgentSession, SessionSnapshotMetadata)} before any provider is bound, and the set of
 * namespaces a later save may write back is fixed once through {@link
 * #restrictPersistedSources(java.util.Collection)}. Both are framework lifecycle plumbing, both are
 * set-once, and both are refused once a provider state view exists.
 */
public final class SessionContext {

  /**
   * Source type stamped onto provider-contributed context messages that carry no attribution. The
   * value is the pinned upstream known source type for context-provider contributions, so a
   * message's provenance reads the same here as it does in the pinned snapshot ({@code External},
   * {@code AIContextProvider}, {@code ChatHistory}).
   */
  private static final String PROVIDER_SOURCE_TYPE = "AIContextProvider";

  private final List<Message> inputMessages;
  private final List<ContextMessageContribution> contextContributions = new ArrayList<>();
  private final Map<String, ProviderState> providerStates = new LinkedHashMap<>();
  private final Map<String, Object> metadata;
  private final CancellationSignal cancellationSignal;
  private AgentSession session;
  private SessionSnapshotMetadata snapshotMetadata;
  private Set<String> persistedSourceIds;
  private AgentResponse response;

  public SessionContext(
      AgentSession session,
      List<? extends Message> inputMessages,
      Map<String, Object> metadata,
      CancellationSignal cancellationSignal) {
    this.session = session;
    List<Message> normalizedInputMessages = new ArrayList<>();
    if (inputMessages != null) {
      for (Message message : inputMessages) {
        normalizedInputMessages.add(
            Objects.requireNonNull(message, "inputMessages must not contain null entries"));
      }
    }
    this.inputMessages = List.copyOf(normalizedInputMessages);
    this.metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    this.cancellationSignal =
        cancellationSignal == null ? new CancellationSignal() : cancellationSignal;
  }

  public synchronized AgentSession session() {
    return session;
  }

  /**
   * Replaces this run's session with the one restored from durable storage, and records the
   * persistence metadata of the snapshot it came from (SES-003, SES-014).
   *
   * <p>This is framework lifecycle plumbing invoked once by the engine's session coordinator,
   * before any context provider is bound. It exists because a run's {@code SessionContext} is
   * created by {@code Agent} before the engine can start the asynchronous load, so the restored
   * session has to reach the context that already exists rather than a second one: two contexts per
   * run would break the one-context-per-run guarantee of SES-011 and split the provider state
   * views.
   *
   * <p>The seam is deliberately narrow and fails closed. The session identity is immutable, so a
   * snapshot for a different session is rejected instead of silently redirecting the run; hydration
   * happens at most once; and it is refused once any {@link #providerState(String)} view exists,
   * because a view already reflects the pre-hydration state and swapping the session underneath it
   * would make a provider observe state that was never persisted for it.
   *
   * @param restored the session restored from the loaded snapshot; must not be {@code null} and
   *     must carry this run's session id
   * @param metadata the loaded snapshot's persistence bookkeeping; must not be {@code null}
   * @throws NullPointerException if {@code restored} or {@code metadata} is {@code null}
   * @throws IllegalArgumentException if {@code restored} carries a different session id
   * @throws IllegalStateException if this run is sessionless, has already been hydrated, or has
   *     already created a provider state view
   */
  public synchronized void hydrate(AgentSession restored, SessionSnapshotMetadata metadata) {
    Objects.requireNonNull(restored, "session must not be null");
    Objects.requireNonNull(metadata, "metadata must not be null");
    if (session == null) {
      throw new IllegalStateException("a sessionless run cannot be hydrated");
    }
    if (snapshotMetadata != null) {
      throw new IllegalStateException("session context is already hydrated");
    }
    requireNoStateViews();
    if (!session.sessionId().equals(restored.sessionId())) {
      throw new IllegalArgumentException("hydrated session id must be " + session.sessionId());
    }
    session = restored;
    snapshotMetadata = metadata;
  }

  /**
   * Returns the persistence bookkeeping of the snapshot this run was restored from, or empty when
   * no stored snapshot was loaded — either because no store is configured, the run is sessionless,
   * or the session has never been saved.
   */
  public synchronized Optional<SessionSnapshotMetadata> snapshotMetadata() {
    return Optional.ofNullable(snapshotMetadata);
  }

  /**
   * Limits which session state namespaces {@link #updatedSession()} writes back, to the source ids
   * the engine actually resolved for this run.
   *
   * <p>This is framework lifecycle plumbing invoked once by the engine after it binds the run's
   * context providers. Without it, a provider that reaches a sibling namespace through {@link
   * #providerState(String)} — which this class documents as reachable plumbing rather than an
   * isolation boundary — would have that write persisted under the sibling's name, so a namespace
   * nobody owns in this run could overwrite the state of a provider that is configured elsewhere.
   * Restricting write-back keeps the durable session a function of the providers the run actually
   * ran.
   *
   * <p>Namespaces outside the allow-list are dropped from the update entirely: neither a value set
   * on them nor a clear of them changes what a later save writes, so stored state for an unbound
   * namespace survives the run untouched. A context that is never restricted keeps writing back
   * every touched namespace, which is what a run without a configured session store does.
   *
   * @param sourceIds the source ids that may be written back; must not be {@code null} and must not
   *     contain blank entries
   * @throws NullPointerException if {@code sourceIds} is {@code null} or contains {@code null}
   * @throws IllegalArgumentException if any source id is blank
   * @throws IllegalStateException if the restriction was already applied, or a provider state view
   *     already exists
   */
  public synchronized void restrictPersistedSources(Collection<String> sourceIds) {
    Objects.requireNonNull(sourceIds, "sourceIds must not be null");
    if (persistedSourceIds != null) {
      throw new IllegalStateException("session context persisted sources are already restricted");
    }
    requireNoStateViews();
    Set<String> normalized = new LinkedHashSet<>();
    for (String sourceId : sourceIds) {
      normalized.add(normalizeSourceId(sourceId));
    }
    persistedSourceIds = Collections.unmodifiableSet(normalized);
  }

  private void requireNoStateViews() {
    if (!providerStates.isEmpty()) {
      throw new IllegalStateException("session context state views already exist");
    }
  }

  public List<Message> inputMessages() {
    return inputMessages;
  }

  public synchronized List<Message> contextMessages() {
    List<Message> messages = new ArrayList<>(contextContributions.size());
    for (ContextMessageContribution contribution : contextContributions) {
      messages.add(contribution.message());
    }
    return List.copyOf(messages);
  }

  /**
   * Returns this run's accumulated context messages paired with the provider that contributed each
   * one, in the same global order {@link #contextMessages()} reports.
   *
   * <p>This is the supported way to select context by contributing provider — a history or audit
   * provider deciding what to store, for example. It reports the run-time fact the framework
   * observed rather than the message's own {@link Message#attribution()}, which a provider may
   * preserve from another session or set to any source id.
   */
  public synchronized List<ContextMessageContribution> contextContributions() {
    return List.copyOf(contextContributions);
  }

  /**
   * Appends an ordered, immutable-safe copy of {@code messages} to this context's accumulated
   * context messages, exactly as given. This is the unattributed accumulation hook: it appends the
   * messages unchanged and never stamps source attribution. A context provider contributing on
   * behalf of a source id uses {@link #addContextMessages(String, List)} instead, which stamps the
   * provider attribution before appending.
   *
   * <p>Null handling mirrors the constructor's {@code inputMessages} normalization: a {@code null}
   * collection is treated as "nothing to add" and is a no-op, while a non-null collection must not
   * contain {@code null} entries. A collection with a {@code null} entry is rejected in full (no
   * partial append) before any of its messages are appended.
   *
   * <p>Because no provider is named, the resulting {@link ContextMessageContribution} carries no
   * contributing source id: these messages come from an external, unspecified source, and a
   * consumer selecting context by contributing provider (a {@code HistoryProvider} filtering on
   * source ids, for example) can never match them by name.
   *
   * @param messages the messages to append, in order; may be {@code null}
   * @throws NullPointerException if {@code messages} is non-null and contains a {@code null} entry
   */
  public synchronized void addContextMessages(List<? extends Message> messages) {
    if (messages == null) {
      return;
    }
    List<ContextMessageContribution> normalized = new ArrayList<>();
    for (Message message : messages) {
      normalized.add(
          new ContextMessageContribution(
              null,
              Objects.requireNonNull(message, "contextMessages must not contain null entries")));
    }
    contextContributions.addAll(normalized);
  }

  /**
   * Appends provider-contributed context messages attributed to {@code sourceId}, in order. This is
   * the seam a {@link io.github.hellices.agentframework.spi.session.ContextProvider} uses from its
   * {@code beforeRun} hook; the engine places the accumulated context messages before the caller's
   * input in the model request, preserving provider declaration order.
   *
   * <p>A message that already carries a {@link MessageAttribution} with a non-blank source id keeps
   * it, so a provider can preserve cross-session provenance for memory it retrieved elsewhere. A
   * blank source id carries no provenance, so it is treated as absent and stamped like a missing
   * one. Any other message is stamped with the given {@code sourceId} and, when it carries no
   * attribution at all, source type {@code "AIContextProvider"}. The origin session id is only
   * filled in from this run's session when the message does not already carry one, so provenance a
   * provider attached for memory retrieved from another session survives the stamping.
   *
   * <p>Null handling matches {@link #addContextMessages(List)}: a {@code null} collection is a
   * no-op, and a collection containing a {@code null} entry is rejected in full before anything is
   * appended.
   *
   * <p>{@code sourceId} is also recorded as the contributing provider of each appended message, so
   * {@link #contextContributions()} reports who actually contributed it even when the message keeps
   * an attribution naming another source.
   *
   * @param sourceId the contributing provider's fixed source id; must not be blank
   * @param messages the messages to append, in order; may be {@code null}
   * @throws IllegalArgumentException if {@code sourceId} is blank
   * @throws NullPointerException if {@code sourceId} is {@code null}, or {@code messages} is
   *     non-null and contains a {@code null} entry
   */
  public synchronized void addContextMessages(String sourceId, List<? extends Message> messages) {
    String normalizedSourceId = normalizeSourceId(sourceId);
    if (messages == null) {
      return;
    }
    List<ContextMessageContribution> normalized = new ArrayList<>();
    for (Message message : messages) {
      Objects.requireNonNull(message, "contextMessages must not contain null entries");
      normalized.add(
          new ContextMessageContribution(
              normalizedSourceId, attribute(message, normalizedSourceId)));
    }
    contextContributions.addAll(normalized);
  }

  /**
   * Returns the source-bound session state view for {@code sourceId}, creating it on first use from
   * {@code session().state().get(sourceId)} and memoizing it for the rest of this run.
   *
   * <p>This is framework lifecycle plumbing, not a provider-facing API: the engine resolves one
   * view per configured provider and passes it to that provider's hooks, which is the only way a
   * provider is meant to reach session state. The returned view is bound to one namespace and
   * exposes no operation that names another namespace or the parent state map.
   *
   * <p>This method is not an isolation boundary. It is public because the engine lives in another
   * module, and this class deliberately also exposes {@link #session()}, whose {@code state()} map
   * is the whole session state. Code holding a {@code SessionContext} can therefore reach any
   * namespace, exactly as upstream .NET and Python allow through their session state bags. Calling
   * it from provider or application code is outside the provider contract; making it unreachable
   * requires module-level encapsulation (a qualified export), which this build does not have yet.
   *
   * <p>What is bounded is persistence, not reachability: once a run applied {@link
   * #restrictPersistedSources(Collection)}, a view created for a namespace outside the allow-list
   * still reads and writes normally for the rest of the run, but nothing it holds reaches {@link
   * #updatedSession()}, so it cannot outlive the run it was created in.
   *
   * @param sourceId the provider's fixed session-state namespace key; must not be blank
   * @throws IllegalArgumentException if {@code sourceId} is blank
   * @throws NullPointerException if {@code sourceId} is {@code null}
   */
  public synchronized ProviderSessionState providerState(String sourceId) {
    String normalizedSourceId = normalizeSourceId(sourceId);
    AgentSession current = session;
    return providerStates.computeIfAbsent(
        normalizedSourceId,
        key -> new ProviderState(key, current == null ? null : current.state().get(key)));
  }

  /**
   * Returns an immutable {@link AgentSession} carrying this run's provider state, for a later
   * persistence step to save. The original session is never mutated: every namespace touched
   * through {@link #providerState(String)} and allowed by {@link
   * #restrictPersistedSources(Collection)} is written back, a cleared namespace is removed, and
   * every other session state entry is preserved as it was.
   *
   * <p>Only the state <em>map</em> is new. The values it holds are the same object references the
   * original session held or the provider stored, so this is not a deep copy and it does not
   * protect a provider's state object from being mutated in place. A provider that mutates a value
   * it already stored changes what the original session observes too, and the change is visible
   * before any persistence step runs. Providers therefore replace their value through {@link
   * ProviderSessionState#set(Object)} with a new object, or keep only immutable values, rather than
   * mutating a stored value in place.
   *
   * @return the updated session, or empty for a sessionless run
   */
  public synchronized Optional<AgentSession> updatedSession() {
    if (session == null) {
      return Optional.empty();
    }
    Map<String, Object> updatedState = new LinkedHashMap<>(session.state());
    Set<String> allowedSourceIds = persistedSourceIds;
    providerStates.forEach(
        (sourceId, state) -> {
          if (allowedSourceIds != null && !allowedSourceIds.contains(sourceId)) {
            return;
          }
          Optional<Object> value = state.value();
          if (value.isPresent()) {
            updatedState.put(sourceId, value.get());
          } else {
            updatedState.remove(sourceId);
          }
        });
    return Optional.of(session.withState(Map.copyOf(updatedState)));
  }

  private Message attribute(Message message, String sourceId) {
    MessageAttribution attribution = message.attribution();
    if (attribution != null
        && attribution.sourceId() != null
        && !attribution.sourceId().isBlank()) {
      return message;
    }
    String currentSessionId = session == null ? null : session.sessionId();
    String originSessionId =
        attribution == null || attribution.originSessionId() == null
            ? currentSessionId
            : attribution.originSessionId();
    return new Message(
        message.role(),
        message.content(),
        new MessageAttribution(
            attribution == null ? PROVIDER_SOURCE_TYPE : attribution.sourceType(),
            sourceId,
            originSessionId),
        message.additionalProperties(),
        message.rawRepresentation());
  }

  private static String normalizeSourceId(String sourceId) {
    Objects.requireNonNull(sourceId, "sourceId must not be null");
    if (sourceId.isBlank()) {
      throw new IllegalArgumentException("sourceId must not be blank");
    }
    return sourceId;
  }

  public Map<String, Object> metadata() {
    return metadata;
  }

  /** Returns the run request attributes exposed as session context metadata. */
  public Map<String, Object> attributes() {
    return metadata;
  }

  public CancellationSignal cancellationSignal() {
    return cancellationSignal;
  }

  public synchronized Optional<AgentResponse> response() {
    return Optional.ofNullable(response);
  }

  /**
   * Fills the set-once response slot on successful terminal completion of the run this context was
   * created for. This is framework lifecycle plumbing invoked by {@code Agent}'s internal run
   * plumbing (see {@code Agent#completionAction}); it is not part of the provider or application
   * contract and must not be called from a {@link
   * io.github.hellices.agentframework.spi.session.ContextProvider} hook. It is public only because
   * the run plumbing and this type live in different modules; like {@link #providerState(String)}
   * it is a visibility compromise, not a capability boundary.
   *
   * <p>The slot can only be filled once: any second invocation, whether with the same or a
   * different value, is rejected because the response is set-once for the lifetime of this context.
   * A provider calling it therefore breaks its own run rather than corrupting another one.
   *
   * @param value the terminal response to store; must not be {@code null}
   * @throws NullPointerException if {@code value} is {@code null}
   * @throws IllegalStateException if the response slot has already been filled
   */
  public synchronized void complete(AgentResponse value) {
    if (response != null) {
      throw new IllegalStateException("session context response is already complete");
    }
    response = Objects.requireNonNull(value, "response must not be null");
  }

  /**
   * The one-namespace state view handed to a provider. It holds only its own source id and value,
   * so the view itself offers no operation that names the parent session state map or a sibling
   * provider's namespace.
   */
  private static final class ProviderState implements ProviderSessionState {

    private final String sourceId;
    private Object value;

    private ProviderState(String sourceId, Object value) {
      this.sourceId = sourceId;
      this.value = value;
    }

    @Override
    public String sourceId() {
      return sourceId;
    }

    @Override
    public synchronized Optional<Object> value() {
      return Optional.ofNullable(value);
    }

    @Override
    public synchronized <T> Optional<T> value(Class<T> type) {
      Objects.requireNonNull(type, "type must not be null");
      if (value == null) {
        return Optional.empty();
      }
      if (!type.isInstance(value)) {
        throw new IllegalStateException(
            "provider state for source '" + sourceId + "' is not a " + type.getName());
      }
      return Optional.of(type.cast(value));
    }

    @Override
    public synchronized void set(Object newValue) {
      value = Objects.requireNonNull(newValue, "value must not be null");
    }

    @Override
    public synchronized void clear() {
      value = null;
    }
  }
}
