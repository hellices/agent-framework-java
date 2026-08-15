package io.github.hellices.agentframework.api.session;

import io.github.hellices.agentframework.api.agent.AgentResponse;
import io.github.hellices.agentframework.api.agent.AgentSession;
import io.github.hellices.agentframework.api.agent.CancellationSignal;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.MessageAttribution;
import io.github.hellices.agentframework.spi.session.ProviderSessionState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

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
 */
public final class SessionContext {

  /** Source type stamped onto provider-contributed context messages that carry no attribution. */
  private static final String CONTEXT_SOURCE_TYPE = "Context";

  private final AgentSession session;
  private final List<Message> inputMessages;
  private final List<Message> contextMessages = new ArrayList<>();
  private final Map<String, ProviderState> providerStates = new LinkedHashMap<>();
  private final Map<String, Object> metadata;
  private final CancellationSignal cancellationSignal;
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

  public AgentSession session() {
    return session;
  }

  public List<Message> inputMessages() {
    return inputMessages;
  }

  public synchronized List<Message> contextMessages() {
    return List.copyOf(contextMessages);
  }

  /**
   * Appends an ordered, immutable-safe copy of {@code messages} to this context's accumulated
   * context messages. This is a plain accumulation hook for engines/providers to make {@link
   * #contextMessages()} usable; it does not attach any provider source attribution to the messages
   * it appends (source-bound attribution is owned by a later context-provider slice).
   *
   * <p>Null handling mirrors the constructor's {@code inputMessages} normalization: a {@code null}
   * collection is treated as "nothing to add" and is a no-op, while a non-null collection must not
   * contain {@code null} entries. A collection with a {@code null} entry is rejected in full (no
   * partial append) before any of its messages are appended.
   *
   * @param messages the messages to append, in order; may be {@code null}
   * @throws NullPointerException if {@code messages} is non-null and contains a {@code null} entry
   */
  public synchronized void addContextMessages(List<? extends Message> messages) {
    if (messages == null) {
      return;
    }
    List<Message> normalized = new ArrayList<>();
    for (Message message : messages) {
      normalized.add(
          Objects.requireNonNull(message, "contextMessages must not contain null entries"));
    }
    contextMessages.addAll(normalized);
  }

  /**
   * Appends provider-contributed context messages attributed to {@code sourceId}, in order. This is
   * the seam a {@link io.github.hellices.agentframework.spi.session.ContextProvider} uses from its
   * {@code beforeRun} hook; the engine places the accumulated context messages before the caller's
   * input in the model request, preserving provider declaration order.
   *
   * <p>A message that already carries a {@link MessageAttribution} with a source id keeps it, so a
   * provider can preserve cross-session provenance for memory it retrieved elsewhere. Any other
   * message is stamped with source type {@code "Context"}, the given {@code sourceId}, and this
   * run's session id as the origin session id.
   *
   * <p>Null handling matches {@link #addContextMessages(List)}: a {@code null} collection is a
   * no-op, and a collection containing a {@code null} entry is rejected in full before anything is
   * appended.
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
    List<Message> normalized = new ArrayList<>();
    for (Message message : messages) {
      Objects.requireNonNull(message, "contextMessages must not contain null entries");
      normalized.add(attribute(message, normalizedSourceId));
    }
    contextMessages.addAll(normalized);
  }

  /**
   * Returns the source-bound session state view for {@code sourceId}, creating it on first use from
   * {@code session().state().get(sourceId)} and memoizing it for the rest of this run.
   *
   * <p>This is framework plumbing: the engine resolves one view per configured provider and passes
   * it to that provider's hooks. The returned view exposes only its own namespace, never the parent
   * session state map or another provider's namespace.
   *
   * @param sourceId the provider's fixed session-state namespace key; must not be blank
   * @throws IllegalArgumentException if {@code sourceId} is blank
   * @throws NullPointerException if {@code sourceId} is {@code null}
   */
  public synchronized ProviderSessionState providerState(String sourceId) {
    String normalizedSourceId = normalizeSourceId(sourceId);
    return providerStates.computeIfAbsent(
        normalizedSourceId,
        key -> new ProviderState(key, session == null ? null : session.state().get(key)));
  }

  /**
   * Returns an immutable {@link AgentSession} carrying this run's provider state, for a later
   * persistence step to save. The original session is never mutated: every namespace touched
   * through {@link #providerState(String)} is written back, a cleared namespace is removed, and
   * every other session state entry is preserved as it was.
   *
   * @return the updated session, or empty for a sessionless run
   */
  public synchronized Optional<AgentSession> updatedSession() {
    if (session == null) {
      return Optional.empty();
    }
    Map<String, Object> updatedState = new LinkedHashMap<>(session.state());
    providerStates.forEach(
        (sourceId, state) -> {
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
    if (attribution != null && attribution.sourceId() != null) {
      return message;
    }
    return new Message(
        message.role(),
        message.content(),
        new MessageAttribution(
            attribution == null ? CONTEXT_SOURCE_TYPE : attribution.sourceType(),
            sourceId,
            session == null ? null : session.sessionId()),
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
   * created for. This is framework lifecycle completion invoked by {@code Agent}'s internal run
   * plumbing (see {@code Agent#completionAction}); it is not intended to be called by provider or
   * application code.
   *
   * <p>The slot can only be filled once: any second invocation, whether with the same or a
   * different value, is rejected because the response is set-once for the lifetime of this context.
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
   * so there is no path from a provider's view to the parent session state map or to a sibling
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
