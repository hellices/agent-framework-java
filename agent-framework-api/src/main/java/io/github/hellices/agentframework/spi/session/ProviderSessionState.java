package io.github.hellices.agentframework.spi.session;

import io.github.hellices.agentframework.api.session.SessionStateKey;
import java.util.Optional;

/**
 * The typed, single-namespace view a {@link StatefulContextProvider} gets over its own slice of
 * session state (SES-012).
 *
 * <p>A view is created per run from {@code AgentSession.state().get(key)} for the provider's {@link
 * StatefulContextProvider#stateKey()} and is the only durable state a provider may keep: a provider
 * that stores per-session state in its own fields breaks session resumption and parallel execution,
 * because the same provider instance is shared by every session it is configured for.
 *
 * <p>The view is bound to exactly one {@link SessionStateKey}. It declares no operation that takes
 * another key and none that returns the parent session state map, and every value it reads or
 * writes is the declared type {@code S} rather than a raw {@code Object}, so a provider written
 * against this view has no way to read or overwrite a sibling's slot, needs no cross-key API, and
 * never stores a value whose type does not match the key it declared.
 *
 * <p>That is a contract statement about this view, not a capability boundary around session state.
 * The per-run {@code SessionContext} that carries these views is itself public and exposes the
 * whole session through {@code session().state()}, matching upstream .NET and Python, so
 * cross-namespace access remains framework plumbing that is simply outside the provider contract.
 * What the framework does bound is durability: a run writes back only the namespaces of the
 * providers it actually resolved, so a cross-namespace write cannot survive the run that made it.
 *
 * @param <S> the declared state type this view reads and writes
 */
public interface ProviderSessionState<S> {

  /** Returns the fixed, typed session-state key this view is bound to. */
  SessionStateKey<S> key();

  /** Returns the current value stored under this key, or empty when nothing is stored. */
  Optional<S> value();

  /**
   * Replaces the value stored under this key.
   *
   * <p>The value is stored by reference, not copied. The updated session a run produces reuses the
   * same reference, so mutating a stored value in place changes what every holder of it observes,
   * including the session this run started from. Store an immutable value, or replace it through
   * this method with a new object, instead of mutating one already stored.
   *
   * @param value the new state value; must not be {@code null} (use {@link #clear()} to remove the
   *     namespace)
   * @throws NullPointerException if {@code value} is {@code null}
   */
  void set(S value);

  /** Removes the value stored under this key. */
  void clear();
}
