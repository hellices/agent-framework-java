package io.github.hellices.agentframework.engine.session;

import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.session.SessionContext;
import io.github.hellices.agentframework.spi.session.HistoryPolicy;
import io.github.hellices.agentframework.spi.session.HistoryProvider;
import io.github.hellices.agentframework.spi.session.ProviderSessionState;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * The built-in {@link HistoryProvider} that keeps conversation history in the session itself
 * (SES-013).
 *
 * <p>History lives only in this provider's {@link ProviderSessionState} namespace, as an immutable
 * {@code List<Message>}. The provider holds no messages, no cache, and no session identity in its
 * own fields, so one instance can serve any number of sessions concurrently: two sessions read and
 * write two different namespaces, and neither can observe the other's turns.
 *
 * <p>Storing never mutates the list a session already holds. Each save publishes a new immutable
 * list, so a session value that was already snapshotted, forked, or handed to another run keeps
 * exactly the history it had. Two runs started from the same session therefore each see the shared
 * prefix plus their own turn, and neither branch corrupts the other.
 *
 * <p>Durable persistence of that namespace is not wired here: a session store must be able to
 * encode a message list through the state codec registry before a snapshot can carry this history,
 * which is the session coordination slice (SES-014). Until then the history survives as long as the
 * session value it lives in, and snapshotting a session that holds this namespace fails naming it.
 *
 * <p>This is a reference implementation, not a production-scale store: every read and every write
 * walks and re-validates the whole stored list, and a save copies it, so a turn costs time linear
 * in the conversation length. That is the price of publishing a fresh immutable list per save,
 * which is what makes branching and cross-session reuse safe.
 */
public final class InMemoryHistoryProvider extends HistoryProvider {

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
    super(sourceId, policy);
  }

  @Override
  public CompletionStage<List<Message>> getMessages(
      SessionContext context, ProviderSessionState state) {
    Objects.requireNonNull(state, "state must not be null");
    return CompletableFuture.completedFuture(storedMessages(state));
  }

  @Override
  public CompletionStage<Void> saveMessages(
      SessionContext context, ProviderSessionState state, List<Message> messages) {
    Objects.requireNonNull(state, "state must not be null");
    Objects.requireNonNull(messages, "messages must not be null");
    List<Message> updated = new ArrayList<>(storedMessages(state));
    for (Message message : messages) {
      updated.add(Objects.requireNonNull(message, "messages must not contain null entries"));
    }
    state.set(List.copyOf(updated));
    return CompletableFuture.completedFuture(null);
  }

  /**
   * Reads the namespace as a message list. A namespace holding anything else is a corrupted or
   * foreign session slot, and reporting it as "no history" would silently start a new conversation
   * on top of state someone else owns, so it fails instead.
   */
  private static List<Message> storedMessages(ProviderSessionState state) {
    Optional<Object> stored = state.value();
    if (stored.isEmpty()) {
      return List.of();
    }
    if (!(stored.get() instanceof List<?> entries)) {
      throw new IllegalStateException(
          "history state for source '" + state.sourceId() + "' is not a " + List.class.getName());
    }
    List<Message> messages = new ArrayList<>();
    for (Object entry : entries) {
      if (!(entry instanceof Message message)) {
        throw new IllegalStateException(
            "history state for source '"
                + state.sourceId()
                + "' must contain only "
                + Message.class.getName()
                + " entries");
      }
      messages.add(message);
    }
    return List.copyOf(messages);
  }
}
