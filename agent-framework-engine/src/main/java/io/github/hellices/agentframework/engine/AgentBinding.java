package io.github.hellices.agentframework.engine;

import io.github.hellices.agentframework.api.agent.AgentDefinition;
import io.github.hellices.agentframework.api.agent.AgentRuntime;
import io.github.hellices.agentframework.api.agent.AgentSession;
import io.github.hellices.agentframework.api.session.SessionContext;
import io.github.hellices.agentframework.api.session.SessionStateKey;
import io.github.hellices.agentframework.api.tool.FunctionTool;
import io.github.hellices.agentframework.api.tool.ToolBinding;
import io.github.hellices.agentframework.api.tool.ToolDefinition;
import io.github.hellices.agentframework.engine.internal.tool.ToolLoopPolicy;
import io.github.hellices.agentframework.engine.session.InMemoryHistoryProvider;
import io.github.hellices.agentframework.spi.session.ContextProvider;
import io.github.hellices.agentframework.spi.session.HistoryPolicy;
import io.github.hellices.agentframework.spi.session.HistoryProvider;
import io.github.hellices.agentframework.spi.session.StatefulContextProvider;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The immutable, per-agent execution state a {@link BoundAgent} carries into the shared {@link
 * AgentEngine}: its identity, the model client and tool loop it runs, and the context providers it
 * resolves each run.
 *
 * <p>An {@link AgentEngine} owns no per-agent state, so everything an agent needs to run that is
 * not shared session coordination lives here, computed once when {@link AgentEngine#bind} binds an
 * {@link AgentDefinition} to an {@link AgentRuntime}. Because it is fixed at bind time and
 * read-only afterward, one binding serves every concurrent run of its agent, and the provider list
 * a run resolves is a pure function of this binding and the run's effective session.
 */
final class AgentBinding {

  private final AgentDefinition definition;
  private final AgentRuntime runtime;
  private final ToolLoopPolicy toolLoop;
  private final List<ProviderBinding> configuredProviders;
  private final DefaultHistory defaultHistory;

  private AgentBinding(AgentDefinition definition, AgentRuntime runtime) {
    this.definition = definition;
    this.runtime = runtime;
    this.toolLoop =
        new ToolLoopPolicy(
            definition.tools(),
            executableTools(definition, runtime),
            definition.defaultRunOptions().maxToolIterations());
    this.configuredProviders = bindContextProviders(runtime.contextProviders());
    this.defaultHistory = bindDefaultHistory(this.configuredProviders);
  }

  /**
   * Binds a definition to a runtime after validating the runtime against the definition, so no
   * binding exists whose bound handlers do not match declared tools.
   */
  static AgentBinding create(AgentDefinition definition, AgentRuntime runtime) {
    Objects.requireNonNull(definition, "definition must not be null");
    Objects.requireNonNull(runtime, "runtime must not be null");
    runtime.validate(definition);
    return new AgentBinding(definition, runtime);
  }

  String id() {
    return definition.id();
  }

  String name() {
    return definition.name();
  }

  AgentDefinition definition() {
    return definition;
  }

  AgentRuntime runtime() {
    return runtime;
  }

  io.github.hellices.agentframework.spi.model.ModelClient modelClient() {
    return runtime.modelClient();
  }

  ToolLoopPolicy toolLoop() {
    return toolLoop;
  }

  /**
   * Rebuilds each bound tool as a callable pair by pairing its declaration with the handler the
   * runtime bound to it. A declared tool with no matching binding is declaration-only: it is still
   * offered to the model because {@link ToolLoopPolicy} is given every declaration, but no
   * executable body is reconstructed for it here, so a model call to it ends the run instead of
   * being executed locally (TOOL-006).
   */
  private static List<FunctionTool> executableTools(
      AgentDefinition definition, AgentRuntime runtime) {
    Map<String, ToolBinding> bindingsByName = new LinkedHashMap<>();
    for (ToolBinding binding : runtime.toolBindings()) {
      bindingsByName.put(binding.toolName(), binding);
    }
    List<FunctionTool> tools = new ArrayList<>();
    for (ToolDefinition tool : definition.tools()) {
      ToolBinding binding = bindingsByName.get(tool.name());
      if (binding != null) {
        tools.add(FunctionTool.bind(tool, binding.handler()));
      }
    }
    return tools;
  }

  /**
   * Reads every stateful provider's state-key id exactly once, so the session state namespace a
   * provider owns is fixed for this agent's lifetime and cannot drift between runs, and rejects a
   * blank or duplicated namespace before any run can mix two providers' state. A stateless provider
   * owns no namespace, so it reserves nothing and is bound with a {@code null} source id.
   */
  private static List<ProviderBinding> bindContextProviders(List<ContextProvider> providers) {
    List<ProviderBinding> bindings = new ArrayList<>();
    Set<String> stateKeyIds = new LinkedHashSet<>();
    for (ContextProvider provider : providers) {
      String sourceId = null;
      if (provider instanceof StatefulContextProvider<?> stateful) {
        SessionStateKey<?> stateKey =
            Objects.requireNonNull(
                stateful.stateKey(), "stateful context provider stateKey must not be null");
        sourceId = stateKey.id();
        if (sourceId.isBlank()) {
          throw new IllegalArgumentException("context provider stateKey id must not be blank");
        }
        if (!stateKeyIds.add(sourceId)) {
          throw new IllegalArgumentException("duplicate context provider stateKey id: " + sourceId);
        }
      }
      bindings.add(new ProviderBinding(sourceId, provider));
    }
    return List.copyOf(bindings);
  }

  /**
   * Decides once, when the agent is bound, whether this agent owns a default in-memory chat history
   * (SES-014).
   *
   * <p>A configured {@link HistoryProvider} that loads messages already answers "what did we say
   * before?" for every run, so injecting a second history on top of it would replay the same
   * conversation twice into one model request. A history provider that only records — an audit or
   * evaluation sink with {@code loadMessages(false)} — answers nothing, so it does not suppress the
   * default: without it a session would silently lose multi-turn behaviour.
   *
   * <p>The namespace is always {@link InMemoryHistoryProvider#DEFAULT_SOURCE_ID}. Deriving it from
   * the configuration instead — picking the first free {@code in_memory-N} — would make a stored
   * conversation unreadable the moment a provider is added on that name: the run would load nothing
   * and append into a second namespace while the durable conversation stayed orphaned under the
   * first. A stable namespace makes the collision a configuration error instead, reported by {@link
   * #resolveProviders(SessionContext)} for the runs that would actually need the default.
   *
   * @return the binding to append for eligible runs, or a conflicting or suppressed marker
   */
  private static DefaultHistory bindDefaultHistory(List<ProviderBinding> configured) {
    for (ProviderBinding binding : configured) {
      if (binding.provider() instanceof HistoryProvider history
          && history.policy().loadMessages()) {
        return new DefaultHistory(null, false);
      }
    }
    for (ProviderBinding binding : configured) {
      if (InMemoryHistoryProvider.DEFAULT_SOURCE_ID.equals(binding.sourceId())) {
        return new DefaultHistory(null, true);
      }
    }
    return new DefaultHistory(
        new ProviderBinding(
            InMemoryHistoryProvider.DEFAULT_SOURCE_ID,
            new InMemoryHistoryProvider(
                InMemoryHistoryProvider.DEFAULT_SOURCE_ID, HistoryPolicy.defaults())),
        false);
  }

  /**
   * Resolves the provider list for one run: the configured providers, plus the default in-memory
   * history when this run is eligible for it (SES-014).
   *
   * <p>A run is eligible only when it has a session to keep history in and the effective session is
   * not service-managed. A sessionless run has nowhere to store the conversation, and a run whose
   * session carries a {@code serviceSessionId} has the conversation kept by the model service, so
   * in both cases injecting a history would either lose it or duplicate it.
   *
   * <p>An eligible run whose default namespace is owned by a configured provider fails here, before
   * the model is called and before anything is saved, rather than quietly moving the default
   * elsewhere: the alternative orphans whatever conversation is already stored under that name. The
   * failure is scoped to the runs that need the default, so a sessionless or service-managed run of
   * the same agent is unaffected.
   *
   * <p>The decision reads the run's effective session, which is the stored one once the coordinator
   * hydrated the context. Because hydration happens before this is ever called and is set-once, and
   * because the configured list and the default binding are both fixed at bind time, this function
   * returns the same list — the same provider instances in the same order — for the before-run and
   * after-run hooks of one run.
   */
  List<ProviderBinding> resolveProviders(SessionContext sessionContext) {
    AgentSession session = sessionContext.session();
    if (session == null || session.serviceSessionId().isPresent()) {
      return configuredProviders;
    }
    if (defaultHistory.namespaceConflict()) {
      throw new IllegalStateException(
          "context provider sourceId '"
              + InMemoryHistoryProvider.DEFAULT_SOURCE_ID
              + "' is reserved for the default in-memory chat history of a session run; "
              + "configure a load-enabled HistoryProvider or a different sourceId");
    }
    if (defaultHistory.binding() == null) {
      return configuredProviders;
    }
    List<ProviderBinding> resolved = new ArrayList<>(configuredProviders.size() + 1);
    resolved.addAll(configuredProviders);
    resolved.add(defaultHistory.binding());
    return List.copyOf(resolved);
  }

  /**
   * The agent's bind-time answer to "does this agent have a default in-memory chat history, and can
   * it use its namespace?".
   *
   * @param binding the provider to append for eligible runs, or {@code null} when a configured
   *     load-enabled history provider already covers history or the namespace is taken
   * @param namespaceConflict whether the default namespace is owned by a configured provider that
   *     does not load history, which makes an otherwise eligible run a configuration error
   */
  private record DefaultHistory(ProviderBinding binding, boolean namespaceConflict) {}

  /**
   * A context provider bound to the fixed source id read once when the agent was bound, so a
   * provider cannot change the session state namespace it owns between runs or hooks. The source id
   * is the state-key id of a {@link StatefulContextProvider}, or {@code null} for a stateless
   * provider that owns no namespace.
   */
  record ProviderBinding(String sourceId, ContextProvider provider) {}
}
