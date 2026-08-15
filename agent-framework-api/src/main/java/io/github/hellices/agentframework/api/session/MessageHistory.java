package io.github.hellices.agentframework.api.session;

import io.github.hellices.agentframework.api.message.Message;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * An immutable ordered conversation history stored in one session state namespace (SES-013,
 * SES-014).
 *
 * <p>This is the durable state value a message-history {@code ContextProvider} keeps, and it exists
 * as a named type rather than a bare {@code List<Message>} so the framework state codec registry
 * can resolve it by exact class. Registering a codec for {@code List} instead would claim every
 * list a provider ever stores and make the registry's assignable-codec lookup ambiguous the moment
 * a second collection-shaped state type is registered.
 *
 * <p>The list is copied on construction and exposed unmodifiable, so a history that has been handed
 * to a session can never be changed in place by whoever built it — the invariant {@code
 * ProviderSessionState#set(Object)} documents for stored values.
 */
public record MessageHistory(List<Message> messages) {

  private static final MessageHistory EMPTY = new MessageHistory(List.of());

  public MessageHistory {
    Objects.requireNonNull(messages, "messages must not be null");
    List<Message> normalized = new ArrayList<>(messages.size());
    for (Object entry : messages) {
      Objects.requireNonNull(entry, "messages must not contain null entries");
      if (!(entry instanceof Message message)) {
        throw new IllegalArgumentException("messages must only contain Message entries");
      }
      normalized.add(message);
    }
    messages = Collections.unmodifiableList(normalized);
  }

  /** Returns the shared empty history. */
  public static MessageHistory empty() {
    return EMPTY;
  }

  /** Returns a history holding an ordered copy of {@code messages}. */
  public static MessageHistory of(List<? extends Message> messages) {
    Objects.requireNonNull(messages, "messages must not be null");
    return new MessageHistory(new ArrayList<Message>(messages));
  }

  /**
   * Returns a new history with {@code additional} appended after this history's messages. This
   * history is left unchanged.
   */
  public MessageHistory append(List<? extends Message> additional) {
    Objects.requireNonNull(additional, "messages must not be null");
    List<Message> combined = new ArrayList<>(messages.size() + additional.size());
    combined.addAll(messages);
    combined.addAll(additional);
    return new MessageHistory(combined);
  }

  @Override
  public List<Message> messages() {
    return messages;
  }
}
