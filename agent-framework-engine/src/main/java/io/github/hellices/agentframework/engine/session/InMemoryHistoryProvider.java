package io.github.hellices.agentframework.engine.session;

import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.session.MessageHistory;
import io.github.hellices.agentframework.api.session.SessionContext;
import io.github.hellices.agentframework.spi.session.HistoryPolicy;
import io.github.hellices.agentframework.spi.session.HistoryProvider;
import io.github.hellices.agentframework.spi.session.ProviderSessionState;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * The built-in {@link HistoryProvider} that keeps conversation history in the session itself
 * (SES-013).
 *
 * <p>History lives only in this provider's {@link ProviderSessionState} namespace, as an immutable
 * {@link MessageHistory}. The provider holds no messages, no cache, and no session identity in its
 * own fields, so one instance can serve any number of sessions concurrently: two sessions read and
 * write two different namespaces, and neither can observe the other's turns.
 *
 * <p>Storing never mutates the history a session already holds. Each save publishes a new immutable
 * {@code MessageHistory}, so a session value that was already snapshotted, forked, or handed to
 * another run keeps exactly the history it had. Two runs started from the same session therefore
 * each see the shared prefix plus their own turn, and neither branch corrupts the other.
 *
 * <p>{@code MessageHistory} is a registered framework state type, so a configured session store
 * persists this namespace and a later run restores the same conversation (SES-014). The messages
 * survive the round trip except for their provider-specific {@code rawRepresentation} handle, which
 * the framework message codec deliberately drops because it is not durable.
 *
 * <p>This is a reference implementation, not a production-scale store: every write copies the whole
 * stored history, so a turn costs time linear in the conversation length. That is the price of
 * publishing a fresh immutable history per save, which is what makes branching and cross-session
 * reuse safe.
 */
public final class InMemoryHistoryProvider extends PolicyDrivenHistoryProvider<MessageHistory> {

  /** The session-state namespace used when no source id is configured. */
  public static final String DEFAULT_SOURCE_ID = "in_memory";

  /** Creates a provider on the default namespace with the default policy. */
  public InMemoryHistoryProvider() {
    this(DEFAULT_SOURCE_ID, HistoryPolicy.defaults());
  }

  /** Creates a provider on the default namespace with the given policy. */
  public InMemoryHistoryProvider(HistoryPolicy policy) {
    this(DEFAULT_SOURCE_ID, policy);
  }

  /**
   * Creates a provider on its own namespace, so several history providers — a primary history and
   * an audit sink, for example — can observe the same run without sharing storage.
   */
  public InMemoryHistoryProvider(String sourceId, HistoryPolicy policy) {
    super(sourceId, MessageHistory.class, policy);
  }

  @Override
  public CompletionStage<List<Message>> load(
      SessionContext context, ProviderSessionState<MessageHistory> state) {
    Objects.requireNonNull(state, "state must not be null");
    return CompletableFuture.completedFuture(storedMessages(state));
  }

  @Override
  public CompletionStage<Void> append(
      SessionContext context, ProviderSessionState<MessageHistory> state, List<Message> messages) {
    Objects.requireNonNull(state, "state must not be null");
    Objects.requireNonNull(messages, "messages must not be null");
    for (Message message : messages) {
      Objects.requireNonNull(message, "messages must not contain null entries");
    }
    state.set(storedHistory(state).append(messages));
    return CompletableFuture.completedFuture(null);
  }

  private static List<Message> storedMessages(ProviderSessionState<MessageHistory> state) {
    return storedHistory(state).messages();
  }

  /**
   * Reads the namespace as a {@link MessageHistory}. The typed state key guarantees the stored
   * value is a {@code MessageHistory}, so an empty slot simply starts a new conversation.
   */
  private static MessageHistory storedHistory(ProviderSessionState<MessageHistory> state) {
    return state.value().orElseGet(MessageHistory::empty);
  }
}
