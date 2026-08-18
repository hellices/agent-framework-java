package io.github.hellices.agentframework.engine;

import io.github.hellices.agentframework.engine.internal.interception.InterceptorRegistry;
import io.github.hellices.agentframework.engine.internal.session.SessionCoordinator;
import io.github.hellices.agentframework.engine.internal.tool.ToolApprovalQueueStateCodec;
import io.github.hellices.agentframework.spi.interception.AgentExecutionInterceptor;
import io.github.hellices.agentframework.spi.interception.ModelInvocationInterceptor;
import io.github.hellices.agentframework.spi.interception.SessionOperationInterceptor;
import io.github.hellices.agentframework.spi.interception.ToolInvocationInterceptor;
import io.github.hellices.agentframework.spi.session.SessionStore;
import io.github.hellices.agentframework.spi.session.StateCodecRegistry;
import java.util.ArrayList;
import java.util.List;
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
  private final List<AgentExecutionInterceptor> agentExecutionInterceptors = new ArrayList<>();
  private final List<ModelInvocationInterceptor> modelInvocationInterceptors = new ArrayList<>();
  private final List<ToolInvocationInterceptor> toolInvocationInterceptors = new ArrayList<>();
  private final List<SessionOperationInterceptor> sessionOperationInterceptors = new ArrayList<>();

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
   * <p>It is optional: an engine with a store and no registry uses the engine's own default
   * registry, which extends {@code StateCodecRegistry.builder()} with the engine-owned tool
   * approval queue codec so approval state (TOOL-020) persists without extra caller wiring. A
   * caller-supplied registry is used exactly as given, so a custom registry built independently of
   * the engine must register that codec itself to persist approval state. A registry without a
   * store is rejected at build time rather than silently ignored, because it can only mean the
   * caller expected persistence that would never happen.
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

  /** Registers one agent-execution interceptor in outer-to-inner declaration order. */
  public AgentEngineBuilder agentExecutionInterceptor(AgentExecutionInterceptor interceptor) {
    agentExecutionInterceptors.add(
        Objects.requireNonNull(interceptor, "agentExecutionInterceptor must not be null"));
    return this;
  }

  /** Registers agent-execution interceptors in outer-to-inner declaration order. */
  public AgentEngineBuilder agentExecutionInterceptors(
      List<? extends AgentExecutionInterceptor> interceptors) {
    agentExecutionInterceptors.addAll(
        validatedSnapshot(interceptors, "agentExecutionInterceptors"));
    return this;
  }

  /** Registers one model-invocation interceptor in outer-to-inner declaration order. */
  public AgentEngineBuilder modelInvocationInterceptor(ModelInvocationInterceptor interceptor) {
    modelInvocationInterceptors.add(
        Objects.requireNonNull(interceptor, "modelInvocationInterceptor must not be null"));
    return this;
  }

  /** Registers model-invocation interceptors in outer-to-inner declaration order. */
  public AgentEngineBuilder modelInvocationInterceptors(
      List<? extends ModelInvocationInterceptor> interceptors) {
    modelInvocationInterceptors.addAll(
        validatedSnapshot(interceptors, "modelInvocationInterceptors"));
    return this;
  }

  /** Registers one tool-invocation interceptor in outer-to-inner declaration order. */
  public AgentEngineBuilder toolInvocationInterceptor(ToolInvocationInterceptor interceptor) {
    toolInvocationInterceptors.add(
        Objects.requireNonNull(interceptor, "toolInvocationInterceptor must not be null"));
    return this;
  }

  /** Registers tool-invocation interceptors in outer-to-inner declaration order. */
  public AgentEngineBuilder toolInvocationInterceptors(
      List<? extends ToolInvocationInterceptor> interceptors) {
    toolInvocationInterceptors.addAll(
        validatedSnapshot(interceptors, "toolInvocationInterceptors"));
    return this;
  }

  /** Registers one session-operation interceptor in outer-to-inner declaration order. */
  public AgentEngineBuilder sessionOperationInterceptor(SessionOperationInterceptor interceptor) {
    sessionOperationInterceptors.add(
        Objects.requireNonNull(interceptor, "sessionOperationInterceptor must not be null"));
    return this;
  }

  /** Registers session-operation interceptors in outer-to-inner declaration order. */
  public AgentEngineBuilder sessionOperationInterceptors(
      List<? extends SessionOperationInterceptor> interceptors) {
    sessionOperationInterceptors.addAll(
        validatedSnapshot(interceptors, "sessionOperationInterceptors"));
    return this;
  }

  public AgentEngine build() {
    if (sessionStore == null && stateCodecRegistry != null) {
      throw new IllegalStateException("stateCodecRegistry requires a configured sessionStore");
    }
    InterceptorRegistry interceptorRegistry =
        new InterceptorRegistry(
            List.copyOf(agentExecutionInterceptors),
            List.copyOf(modelInvocationInterceptors),
            List.copyOf(toolInvocationInterceptors),
            List.copyOf(sessionOperationInterceptors));
    SessionCoordinator sessionCoordinator =
        sessionStore == null
            ? null
            : new SessionCoordinator(
                sessionStore,
                stateCodecRegistry == null ? defaultStateCodecRegistry() : stateCodecRegistry,
                interceptorRegistry::interceptSession);
    return new AgentEngine(sessionCoordinator, interceptorRegistry);
  }

  /**
   * The registry an engine uses when the caller configures a {@link #sessionStore(SessionStore)}
   * but no {@link #stateCodecRegistry(StateCodecRegistry)}: the framework's own default plus the
   * engine-owned {@link ToolApprovalQueueStateCodec}, registered explicitly under its reserved type
   * id rather than through class names or native serialization.
   */
  private static StateCodecRegistry defaultStateCodecRegistry() {
    return StateCodecRegistry.builder().register(new ToolApprovalQueueStateCodec()).build();
  }

  private static <T> List<T> validatedSnapshot(List<? extends T> interceptors, String label) {
    List<? extends T> value = Objects.requireNonNull(interceptors, label + " must not be null");
    List<T> snapshot = new ArrayList<>(value.size());
    for (int index = 0; index < value.size(); index++) {
      snapshot.add(
          Objects.requireNonNull(value.get(index), label + "[" + index + "] must not be null"));
    }
    return List.copyOf(snapshot);
  }
}
