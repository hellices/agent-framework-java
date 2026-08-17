package io.github.hellices.agentframework.engine;

import io.github.hellices.agentframework.api.agent.Agent;
import io.github.hellices.agentframework.api.agent.AgentDefinition;
import io.github.hellices.agentframework.api.agent.AgentRunOptions;
import io.github.hellices.agentframework.api.agent.AgentRuntime;
import io.github.hellices.agentframework.api.tool.FunctionTool;
import io.github.hellices.agentframework.api.tool.ToolBinding;
import io.github.hellices.agentframework.engine.internal.session.SessionCoordinator;
import io.github.hellices.agentframework.spi.model.ModelClient;
import io.github.hellices.agentframework.spi.session.ContextProvider;
import io.github.hellices.agentframework.spi.session.SessionStore;
import io.github.hellices.agentframework.spi.session.StateCodecRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Assembles a runnable agent by collecting an agent's declarative identity and tools alongside the
 * model client, context providers, and session services it runs with, then binding them through a
 * model-independent {@link AgentEngine}.
 *
 * <p>This builder is the bridge between the pre-separation single-object configuration and the
 * split {@link AgentDefinition}/{@link AgentRuntime}/{@link AgentEngine} model: {@link #build()}
 * derives a definition and a runtime from the configured values, constructs an engine over the
 * shared session services, and returns the bound {@link Agent}. It is intentionally not an {@code
 * AgentBuilder}, so the split objects are the only contract a later plan needs to refine.
 */
public final class AgentEngineBuilder {

  private String id;
  private String name;
  private String description;
  private ModelClient modelClient;
  private final List<FunctionTool> tools = new ArrayList<>();
  private final List<ContextProvider> contextProviders = new ArrayList<>();
  private SessionStore sessionStore;
  private StateCodecRegistry stateCodecRegistry;
  private int maxIterations = AgentRunOptions.DEFAULT_MAX_TOOL_ITERATIONS;

  AgentEngineBuilder() {}

  public AgentEngineBuilder id(String id) {
    this.id = id;
    return this;
  }

  public AgentEngineBuilder name(String name) {
    this.name = name;
    return this;
  }

  public AgentEngineBuilder description(String description) {
    this.description = description;
    return this;
  }

  public AgentEngineBuilder modelClient(ModelClient modelClient) {
    this.modelClient = modelClient;
    return this;
  }

  public AgentEngineBuilder tools(FunctionTool... tools) {
    if (tools != null) {
      for (FunctionTool tool : tools) {
        if (tool == null) {
          throw new IllegalArgumentException("tools must not contain null entries");
        }
        this.tools.add(tool);
      }
    }
    return this;
  }

  /**
   * Configures the context providers that participate in every run of the built agent, in
   * declaration order: {@code beforeRun} hooks run in this order before the first model call, and
   * {@code afterRun} hooks run in reverse order after a successful run.
   *
   * <p>Each provider's {@link ContextProvider#sourceId()} is read once when the agent is bound and
   * fixes the session state namespace it owns for the agent's lifetime. A blank source id or a
   * source id shared by two providers is rejected at build time, because both would let one
   * provider silently read or overwrite another provider's state.
   *
   * @param providers the providers to add, in order; may be {@code null}
   * @throws IllegalArgumentException if {@code providers} contains a {@code null} entry
   */
  public AgentEngineBuilder contextProviders(ContextProvider... providers) {
    if (providers != null) {
      for (ContextProvider provider : providers) {
        if (provider == null) {
          throw new IllegalArgumentException("contextProviders must not contain null entries");
        }
        this.contextProviders.add(provider);
      }
    }
    return this;
  }

  /**
   * Configures the durable session store the built agent loads from before a run with a session and
   * saves to after that run succeeded (SES-003, SES-014).
   *
   * <p>Without a store the built agent performs no session I/O at all and a run's state lives only
   * in the session object the caller passes in and reads back. With a store configured, a run that
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
   * <p>It is optional: an agent with a store and no registry uses {@code
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

  public AgentEngineBuilder maxIterations(int maxIterations) {
    if (maxIterations < 1) {
      throw new IllegalArgumentException("maxIterations must be greater than 0");
    }
    this.maxIterations = maxIterations;
    return this;
  }

  public Agent build() {
    if (modelClient == null) {
      throw new IllegalStateException("modelClient must be configured");
    }
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
    AgentEngine engine = new AgentEngine(sessionCoordinator);
    return engine.bind(toDefinition(), toRuntime());
  }

  private AgentDefinition toDefinition() {
    AgentDefinition.Builder definition =
        AgentDefinition.builder()
            .defaultRunOptions(AgentRunOptions.builder().maxToolIterations(maxIterations).build());
    if (id != null && !id.isBlank()) {
      definition.id(id);
    }
    if (name != null && !name.isBlank()) {
      definition.name(name);
    }
    if (description != null) {
      definition.description(description);
    }
    for (FunctionTool tool : tools) {
      definition.tool(tool.definition());
    }
    return definition.build();
  }

  private AgentRuntime toRuntime() {
    AgentRuntime.Builder runtime = AgentRuntime.builder().modelClient(modelClient);
    for (FunctionTool tool : tools) {
      runtime.toolBinding(ToolBinding.of(tool.definition().name(), tool::execute));
    }
    for (ContextProvider contextProvider : contextProviders) {
      runtime.contextProvider(contextProvider);
    }
    return runtime.build();
  }
}
