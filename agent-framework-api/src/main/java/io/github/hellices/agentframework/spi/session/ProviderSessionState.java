package io.github.hellices.agentframework.spi.session;

import java.util.Optional;

/**
 * The source-bound view a {@link ContextProvider} gets over its own slice of session state
 * (SES-012).
 *
 * <p>A view is created per run from {@code AgentSession.state().get(sourceId)} and is the only
 * durable state a provider may keep: a provider that stores per-session state in its own fields
 * breaks session resumption and parallel execution, because the same provider instance is shared by
 * every session it is configured for.
 *
 * <p>The view exposes exactly one namespace. It never exposes the parent session state map and
 * never reaches another provider's {@code sourceId}, so two providers configured on the same agent
 * cannot read or overwrite each other's slot.
 */
public interface ProviderSessionState {

  /** Returns the fixed session-state namespace key this view is bound to. */
  String sourceId();

  /** Returns the current value stored under this namespace, or empty when nothing is stored. */
  Optional<Object> value();

  /**
   * Returns the current value stored under this namespace when it is an instance of {@code type}.
   *
   * @param type the expected state type; must not be {@code null}
   * @return the typed value, or empty when nothing is stored
   * @throws IllegalStateException if a value is stored but is not an instance of {@code type},
   *     because silently reporting "no state" would hide a mismatched or corrupted session slot
   */
  <T> Optional<T> value(Class<T> type);

  /**
   * Replaces the value stored under this namespace.
   *
   * @param value the new state value; must not be {@code null} (use {@link #clear()} to remove the
   *     namespace)
   * @throws NullPointerException if {@code value} is {@code null}
   */
  void set(Object value);

  /** Removes the value stored under this namespace. */
  void clear();
}
