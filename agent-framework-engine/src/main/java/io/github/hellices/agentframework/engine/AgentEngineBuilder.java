package io.github.hellices.agentframework.engine;

import io.github.hellices.agentframework.engine.internal.session.SessionCoordinator;
import io.github.hellices.agentframework.spi.session.SessionStore;
import io.github.hellices.agentframework.spi.session.StateCodecRegistry;
import java.util.Objects;

/**
 * Assembles a shared, model-independent {@link AgentEngine} from the services an agent's runs
 * share.
 *
 * <p>The engine owns no per-agent identity, model client, tool set, or provider list, so this
 * builder configures only the session services those runs coordinate through — the durable {@link
 * SessionStore} and the {@link StateCodecRegistry} that snapshots its state. Per-agent wiring is
 * supplied later, when an {@code AgentFactory} or {@link AgentEngine#bind} binds a declaration and
 * runtime to the built engine.
 */
public final class AgentEngineBuilder {

  private SessionStore sessionStore;
  private StateCodecRegistry stateCodecRegistry;

  AgentEngineBuilder() {}

  /**
   * Configures the durable session store an agent bound to the built engine loads from before a run
   * with a session and saves to after that run succeeded (SES-003, SES-014).
   *
   * <p>Without a store the engine performs no session I/O at all and a run's state lives only in
   * the session object the caller passes in and reads back. With a store configured, a run that
   * carries a session loads it before binding its context providers, and the stored session — not
   * the one on the request — is what the run's providers observe.
   *
   * @param sessionStore the store to load from and save to; must not be {@code null}
   * @throws NullPointerException if {@code sessionStore} is {@code null}
   */
  public AgentEngineBuilder sessionStore(SessionStore sessionStore) {
    this.sessionStore = Objects.requireNonNull(sessionStore, "sessionStore must not be null");
    return this;
  }

  /**
   * Configures the state codec registry used to snapshot and restore session state for the
   * configured {@link #sessionStore(SessionStore)}.
   *
   * <p>It is optional: an engine with a store and no registry uses {@code
   * StateCodecRegistry.builder().build()}, which carries only the framework's built-in state types.
   * A registry without a store is rejected at build time rather than silently ignored, because it
   * can only mean the caller expected persistence that would never happen.
   *
   * @param stateCodecRegistry the registry owning every persistable state type; must not be {@code
   *     null}
   * @throws NullPointerException if {@code stateCodecRegistry} is {@code null}
   */
  public AgentEngineBuilder stateCodecRegistry(StateCodecRegistry stateCodecRegistry) {
    this.stateCodecRegistry =
        Objects.requireNonNull(stateCodecRegistry, "stateCodecRegistry must not be null");
    return this;
  }

  public AgentEngine build() {
    if (sessionStore == null && stateCodecRegistry != null) {
      throw new IllegalStateException("stateCodecRegistry requires a configured sessionStore");
    }
    SessionCoordinator sessionCoordinator =
        sessionStore == null
            ? null
            : new SessionCoordinator(
                sessionStore,
                stateCodecRegistry == null
                    ? StateCodecRegistry.builder().build()
                    : stateCodecRegistry);
    return new AgentEngine(sessionCoordinator);
  }
}
