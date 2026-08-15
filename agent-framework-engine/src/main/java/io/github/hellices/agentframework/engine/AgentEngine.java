package io.github.hellices.agentframework.engine;

import io.github.hellices.agentframework.api.agent.Agent;
import io.github.hellices.agentframework.api.agent.AgentFactory;
import io.github.hellices.agentframework.api.agent.AgentResponse;
import io.github.hellices.agentframework.api.agent.AgentResponseUpdate;
import io.github.hellices.agentframework.api.agent.AgentRun;
import io.github.hellices.agentframework.api.agent.AgentRunContext;
import io.github.hellices.agentframework.api.agent.AgentRunRequest;
import io.github.hellices.agentframework.api.agent.AgentSession;
import io.github.hellices.agentframework.api.agent.AgentStreamingRun;
import io.github.hellices.agentframework.api.agent.CancellationSignal;
import io.github.hellices.agentframework.api.message.Content;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.Role;
import io.github.hellices.agentframework.api.message.ToolCallContent;
import io.github.hellices.agentframework.api.message.ToolResultContent;
import io.github.hellices.agentframework.api.message.Usage;
import io.github.hellices.agentframework.api.session.SessionContext;
import io.github.hellices.agentframework.api.tool.FunctionTool;
import io.github.hellices.agentframework.api.tool.ToolArguments;
import io.github.hellices.agentframework.api.tool.ToolContext;
import io.github.hellices.agentframework.api.tool.ToolDefinition;
import io.github.hellices.agentframework.api.tool.ToolResult;
import io.github.hellices.agentframework.engine.internal.model.ModelResponseMapper;
import io.github.hellices.agentframework.engine.internal.session.SessionCoordinator;
import io.github.hellices.agentframework.engine.session.InMemoryHistoryProvider;
import io.github.hellices.agentframework.spi.model.ContinuationModelClient;
import io.github.hellices.agentframework.spi.model.ModelCatalog;
import io.github.hellices.agentframework.spi.model.ModelClient;
import io.github.hellices.agentframework.spi.model.ModelRequest;
import io.github.hellices.agentframework.spi.model.ModelRequestOptions;
import io.github.hellices.agentframework.spi.model.ModelResponse;
import io.github.hellices.agentframework.spi.model.ModelResponseUpdate;
import io.github.hellices.agentframework.spi.model.StreamingContinuationModelClient;
import io.github.hellices.agentframework.spi.model.StreamingModelClient;
import io.github.hellices.agentframework.spi.session.ContextProvider;
import io.github.hellices.agentframework.spi.session.HistoryPolicy;
import io.github.hellices.agentframework.spi.session.HistoryProvider;
import io.github.hellices.agentframework.spi.session.ProviderSessionState;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.function.Function;
import java.util.function.Supplier;

public final class AgentEngine extends Agent {

  private final ModelClient modelClient;
  private final Map<String, FunctionTool> tools;
  private final List<ToolDefinition> toolDefinitions;
  private final List<ProviderBinding> configuredProviders;
  private final DefaultHistory defaultHistory;
  private final SessionCoordinator sessionCoordinator;
  private final int maxIterations;

  AgentEngine(
      String id,
      String name,
      String description,
      ModelClient modelClient,
      List<FunctionTool> tools,
      List<ContextProvider> contextProviders,
      SessionCoordinator sessionCoordinator,
      int maxIterations) {
    super(id, name, description);
    this.modelClient = Objects.requireNonNull(modelClient, "modelClient must not be null");
    Map<String, FunctionTool> indexedTools = new LinkedHashMap<>();
    for (FunctionTool tool : tools) {
      String toolName = tool.definition().name();
      if (indexedTools.putIfAbsent(toolName, tool) != null) {
        throw new IllegalArgumentException("duplicate tool name: " + toolName);
      }
    }
    this.tools = Map.copyOf(indexedTools);
    this.toolDefinitions = indexedTools.values().stream().map(FunctionTool::definition).toList();
    this.configuredProviders = bindContextProviders(contextProviders);
    this.defaultHistory = bindDefaultHistory(this.configuredProviders);
    this.sessionCoordinator = sessionCoordinator;
    this.maxIterations = maxIterations;
  }

  /**
   * Reads every provider's {@code sourceId} exactly once, so the session state namespace a provider
   * owns is fixed for this agent's lifetime and cannot drift between runs, and rejects a blank or
   * duplicated namespace before any run can mix two providers' state.
   */
  private static List<ProviderBinding> bindContextProviders(List<ContextProvider> providers) {
    List<ProviderBinding> bindings = new ArrayList<>();
    Set<String> sourceIds = new LinkedHashSet<>();
    for (ContextProvider provider : providers) {
      String sourceId = provider.sourceId();
      if (sourceId == null || sourceId.isBlank()) {
        throw new IllegalArgumentException("context provider sourceId must not be blank");
      }
      if (!sourceIds.add(sourceId)) {
        throw new IllegalArgumentException("duplicate context provider sourceId: " + sourceId);
      }
      bindings.add(new ProviderBinding(sourceId, provider));
    }
    return List.copyOf(bindings);
  }

  /**
   * Decides once, when the agent is built, whether this agent owns a default in-memory chat history
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
   * The agent's build-time answer to "does this agent have a default in-memory chat history, and
   * can it use its namespace?".
   *
   * @param binding the provider to append for eligible runs, or {@code null} when a configured
   *     load-enabled history provider already covers history or the namespace is taken
   * @param namespaceConflict whether the default namespace is owned by a configured provider that
   *     does not load history, which makes an otherwise eligible run a configuration error
   */
  private record DefaultHistory(ProviderBinding binding, boolean namespaceConflict) {}

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
   * because the configured list and the default binding are both fixed at build time, this function
   * returns the same list — the same provider instances in the same order — for the before-run and
   * after-run hooks of one run.
   */
  private List<ProviderBinding> resolveProviders(SessionContext sessionContext) {
    AgentSession session = sessionContext.session();
    if (session == null || session.serviceSessionId() != null) {
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
   * Resolves this run's providers and tells the run's context which session state namespaces a
   * later save may write back.
   *
   * <p>Restricting write-back here is what keeps the durable session a function of the providers
   * the run actually ran: {@code SessionContext#providerState(String)} is reachable plumbing rather
   * than an isolation boundary, so without this a provider reaching a sibling namespace would have
   * that write persisted under a name nobody owned in this run.
   */
  private List<ProviderBinding> bindRun(SessionContext sessionContext) {
    List<ProviderBinding> resolved = resolveProviders(sessionContext);
    List<String> sourceIds = new ArrayList<>(resolved.size());
    for (ProviderBinding binding : resolved) {
      sourceIds.add(binding.sourceId());
    }
    sessionContext.restrictPersistedSources(sourceIds);
    return resolved;
  }

  public static AgentEngineBuilder builder() {
    return new AgentEngineBuilder();
  }

  public static AgentFactory factory(ModelCatalog catalog) {
    return new CatalogAgentFactory(catalog);
  }

  /**
   * Starts an ordinary run. Without an asynchronous gate — no resolved context providers and no
   * configured session store for a run with a session — there is nothing to wait for, so the tool
   * loop's first model call is started eagerly and a client that throws or returns {@code null}
   * fails this call synchronously, exactly as it did before the context provider pipeline existed.
   * With a gate the first model call must happen after the session was loaded and every {@code
   * beforeRun} hook completed, so the tool loop is started only once the composed stage completes,
   * and the same failures are instead reported on the run's response and session stages. This
   * mirrors {@link #runStreamingInternal} exactly, without duplicating its tool-loop-specific
   * cancellation re-check (below), which streaming has no equivalent of because it has no loop.
   *
   * <p>Which shape a run gets is a property of the run, not of the client: a sessionless run of an
   * agent with no context providers is eager, and any run that carries a session has a gate,
   * because such a run always resolves at least the default in-memory chat history (SES-014) unless
   * its session is service-managed. Passing a session to an agent whose model client throws
   * synchronously therefore moves that failure from {@code run(...)} to {@link AgentRun#response()}
   * — source-compatible, but visible to caller code that wraps {@code run(...)} in {@code
   * try}/{@code catch}. The rule is deliberately a function of the configuration and the request
   * alone: making it depend on whether the store or a hook happened to complete before the check
   * would make the same code throw synchronously or asynchronously from one run to the next.
   */
  @Override
  protected AgentRun runInternal(AgentRunContext context, AgentRunRequest request) {
    if (!tools.isEmpty() && request.options().continuationToken().isPresent()) {
      throw new UnsupportedOperationException("continuation tool execution is not supported");
    }
    ModelClient selectedClient = request.options().resolveModelClient(modelClient);
    Function<ModelRequest, CompletionStage<ModelResponse>> modelInvoker =
        resolveModelInvoker(selectedClient, request);
    SessionContext sessionContext = context.sessionContext();
    String responseId = UUID.randomUUID().toString();
    Instant createdAt = Instant.now();
    Supplier<CompletionStage<ToolLoopResult>> toolLoop =
        () ->
            runToolLoop(
                selectedClient,
                modelInvoker,
                toModelRequest(request, sessionContext),
                request,
                0,
                List.of(),
                null);
    CompletionStage<Void> gate = runGate(sessionContext, request.cancellationSignal());
    CompletionStage<ToolLoopResult> toolLoopResult =
        gate == null
            ? toolLoop.get()
            : gate.thenCompose(
                ignored -> {
                  // The gate makes the first model call asynchronous, so the run can be cancelled
                  // while the store or a hook is still pending. Re-checking here keeps the ordinary
                  // path aligned with the streaming one: once the caller observed cancellation, no
                  // model request is issued when the gate finally completes.
                  if (request.cancellationSignal().isCancelled()) {
                    throw new CancellationException("run was cancelled");
                  }
                  return toolLoop.get();
                });
    CompletionStage<AgentResponse> response =
        toolLoopResult.thenApply(
            result -> {
              if (request.cancellationSignal().isCancelled()) {
                throw new CancellationException("run was cancelled");
              }
              ModelResponse terminal = result.terminalResponse();
              return ModelResponseMapper.toAgentResponse(
                  id(),
                  responseId,
                  name(),
                  createdAt,
                  new ModelResponse(
                      result.messages(),
                      result.usage(),
                      terminal.finishReason(),
                      terminal.continuationToken(),
                      terminal.metadata(),
                      terminal.rawRepresentation()));
            });
    return new AgentRun(response, request.cancellationSignal());
  }

  /**
   * Composes everything that must happen before this run's first model call, or returns {@code
   * null} when nothing must.
   *
   * <p>The order is load, then bind, then the forward {@code beforeRun} hooks: the stored session
   * has to be in place before the run resolves and binds its providers, so a provider observes the
   * state that was actually persisted for it and the injected-history decision is made from the
   * stored session rather than the request's.
   *
   * <p>Returning {@code null} rather than a completed stage is what preserves the synchronous
   * failure shape of a run with no gate at all: a model client that throws must still fail the
   * caller's {@code run} call, not the response stage. Only a run that resolves no provider and
   * touches no store reaches that branch — see {@link #runInternal} for what that means for a run
   * that carries a session.
   */
  private CompletionStage<Void> runGate(
      SessionContext sessionContext, CancellationSignal cancellationSignal) {
    if (sessionCoordinator != null && sessionContext.session() != null) {
      return sessionCoordinator
          .load(sessionContext)
          .thenCompose(
              ignored -> beforeRun(bindRun(sessionContext), sessionContext, cancellationSignal));
    }
    List<ProviderBinding> resolved = bindRun(sessionContext);
    if (resolved.isEmpty()) {
      return null;
    }
    return beforeRun(resolved, sessionContext, cancellationSignal);
  }

  /**
   * Starts a streaming run. Without an asynchronous gate — no configured session store for a run
   * with a session, and no resolved context providers — the model's update publisher is created
   * eagerly and a client that throws or returns {@code null} fails this call synchronously, exactly
   * as it did before the context provider pipeline existed. With a gate the first model call must
   * happen after the session was loaded and every {@code beforeRun} hook completed, so publisher
   * creation is deferred and the same failures are instead delivered to the update subscriber as a
   * terminal {@code onError}, which the run's response stage reports. Failures that do not depend
   * on the gate (unsupported tools, a client lacking the streaming or streaming-continuation
   * capability) stay synchronous in both shapes. Which shape applies follows the same rule {@link
   * #runInternal} documents: a run that carries a session always has a gate.
   */
  @Override
  protected AgentStreamingRun<AgentResponseUpdate> runStreamingInternal(
      AgentRunContext context, AgentRunRequest request) {
    if (!tools.isEmpty()) {
      throw new UnsupportedOperationException("streaming tool execution is not supported");
    }
    ModelClient selectedClient = request.options().resolveModelClient(modelClient);
    Function<ModelRequest, Flow.Publisher<ModelResponseUpdate>> streamingInvoker =
        resolveStreamingInvoker(selectedClient, request);
    SessionContext sessionContext = context.sessionContext();
    String responseId = UUID.randomUUID().toString();
    Instant createdAt = Instant.now();
    CompletionStage<Void> gate = runGate(sessionContext, request.cancellationSignal());
    Flow.Publisher<ModelResponseUpdate> modelUpdates =
        gate == null
            ? Objects.requireNonNull(
                streamingInvoker.apply(toModelRequest(request, sessionContext)),
                "model client update publisher must not be null")
            : deferUntil(
                gate,
                request.cancellationSignal(),
                () -> {
                  if (request.cancellationSignal().isCancelled()) {
                    throw new CancellationException("run was cancelled");
                  }
                  return Objects.requireNonNull(
                      streamingInvoker.apply(toModelRequest(request, sessionContext)),
                      "model client update publisher must not be null");
                });
    Flow.Publisher<AgentResponseUpdate> agentUpdates =
        subscriber ->
            modelUpdates.subscribe(
                new MappingSubscriber(subscriber, id(), responseId, name(), createdAt));
    return AgentStreamingRun.fromUpdates(
        agentUpdates,
        request.cancellationSignal(),
        () ->
            new AgentResponse(
                id(), responseId, null, name(), createdAt, null, List.of(), null, Map.of(), null));
  }

  /**
   * Composes every resolved provider's {@code beforeRun} hook in declaration order, before the
   * run's first model call. Each hook receives the run's single {@link SessionContext} and the
   * state view bound to its own source id. A hook that fails, returns {@code null}, or is reached
   * after the run was cancelled fails the composed stage, so no later hook and no model call runs.
   */
  private CompletionStage<Void> beforeRun(
      List<ProviderBinding> providers,
      SessionContext sessionContext,
      CancellationSignal cancellationSignal) {
    CompletionStage<Void> stage = CompletableFuture.completedFuture(null);
    for (ProviderBinding binding : providers) {
      stage =
          stage.thenCompose(
              ignored -> {
                if (cancellationSignal.isCancelled()) {
                  throw new CancellationException("run was cancelled");
                }
                return Objects.requireNonNull(
                    binding.provider().beforeRun(sessionContext, binding.state(sessionContext)),
                    "context provider before-run stage must not be null");
              });
    }
    return stage;
  }

  /**
   * Composes every resolved provider's {@code afterRun} hook in reverse declaration order, then
   * saves the session this run produced. The framework calls this only after the run's terminal
   * response completed successfully and the {@link SessionContext} response slot was filled, so a
   * hook observes the same context its {@code beforeRun} opened, plus the final response. A hook
   * failure fails the run and stops the remaining (earlier-declared) hooks, and the save is reached
   * only once every hook succeeded, so a run that fails anywhere leaves the stored session
   * untouched.
   *
   * <p>The provider list is recomputed rather than carried from the start of the run, because
   * {@code Agent} hands this seam nothing but the context. That is safe precisely because
   * resolution is a pure function of the agent's build-time configuration and the run's set-once
   * effective session: this recomputation yields the same bindings, in the same order, that {@code
   * beforeRun} used.
   *
   * <p>A run with a session and a configured store saves even when it owns no session state
   * namespace at all — a service-managed conversation with no configured provider, for example. The
   * snapshot is the durable record of the session itself: skipping it would leave a session the
   * caller created with a service handle unrecorded, so a later run could not tell "never stored"
   * from "stored with no local state" and would accept any service handle for it. The cost is one
   * revision and one write per run of such a session.
   */
  @Override
  protected CompletionStage<Void> afterRun(SessionContext sessionContext) {
    List<ProviderBinding> providers = resolveProviders(sessionContext);
    CompletionStage<Void> stage = CompletableFuture.completedFuture(null);
    for (int index = providers.size() - 1; index >= 0; index--) {
      ProviderBinding binding = providers.get(index);
      stage =
          stage.thenCompose(
              ignored ->
                  Objects.requireNonNull(
                      binding.provider().afterRun(sessionContext, binding.state(sessionContext)),
                      "context provider after-run stage must not be null"));
    }
    if (sessionCoordinator == null || sessionContext.session() == null) {
      return stage;
    }
    return stage.thenCompose(ignored -> sessionCoordinator.save(sessionContext));
  }

  /**
   * Builds the model request for a run: provider-contributed context messages come first, in the
   * order the providers contributed them, followed by the caller's input messages.
   */
  private ModelRequest toModelRequest(AgentRunRequest request, SessionContext sessionContext) {
    List<Message> messages = new ArrayList<>(sessionContext.contextMessages());
    messages.addAll(request.messages());
    return new ModelRequest(
        messages,
        ModelRequestOptions.empty(),
        request.cancellationSignal(),
        maxIterations > 1 ? toolDefinitions : List.of(),
        request.attributes());
  }

  private CompletionStage<ToolLoopResult> runToolLoop(
      ModelClient client,
      Function<ModelRequest, CompletionStage<ModelResponse>> modelInvoker,
      ModelRequest modelRequest,
      AgentRunRequest request,
      int iteration,
      List<Message> accumulatedMessages,
      Usage accumulatedUsage) {
    CompletionStage<ModelResponse> responseStage =
        iteration == 0 ? modelInvoker.apply(modelRequest) : client.run(modelRequest);
    return Objects.requireNonNull(responseStage, "model client response stage must not be null")
        .thenCompose(
            response -> {
              validateToolContinuation(response);
              List<Message> outputMessages = new ArrayList<>(accumulatedMessages);
              outputMessages.addAll(response.messages());
              Usage usage = combineUsage(accumulatedUsage, response.usage());
              List<ToolCallContent> calls = toolCalls(response);
              if (calls.isEmpty()) {
                return CompletableFuture.completedFuture(
                    new ToolLoopResult(response, List.copyOf(outputMessages), usage));
              }
              if (request.cancellationSignal().isCancelled()) {
                throw new CancellationException("run was cancelled");
              }
              if (iteration + 1 >= maxIterations) {
                throw new IllegalStateException(
                    "model returned tool calls after tools were disabled");
              }
              return executeToolCalls(calls, request, 0, List.of())
                  .thenCompose(
                      results -> {
                        List<Message> nextMessages = new ArrayList<>(modelRequest.messages());
                        nextMessages.addAll(response.messages());
                        Message toolResultMessage = new Message(Role.TOOL, results);
                        nextMessages.add(toolResultMessage);
                        outputMessages.add(toolResultMessage);
                        ModelRequest nextRequest =
                            new ModelRequest(
                                nextMessages,
                                modelRequest.options(),
                                modelRequest.cancellationSignal(),
                                iteration + 2 < maxIterations ? toolDefinitions : List.of(),
                                modelRequest.metadata());
                        return runToolLoop(
                            client,
                            modelInvoker,
                            nextRequest,
                            request,
                            iteration + 1,
                            List.copyOf(outputMessages),
                            usage);
                      });
            });
  }

  private void validateToolContinuation(ModelResponse response) {
    if (!tools.isEmpty() && response.continuationToken() != null) {
      throw new UnsupportedOperationException(
          "model continuation with tool execution is not supported");
    }
  }

  private CompletionStage<List<Content>> executeToolCalls(
      List<ToolCallContent> calls,
      AgentRunRequest request,
      int index,
      List<Content> accumulatedResults) {
    if (index >= calls.size()) {
      return CompletableFuture.completedFuture(accumulatedResults);
    }
    if (request.cancellationSignal().isCancelled()) {
      throw new CancellationException("run was cancelled");
    }
    ToolCallContent call = calls.get(index);
    FunctionTool tool = tools.get(call.name());
    if (tool == null) {
      throw new IllegalStateException("unknown tool call: " + call.name());
    }
    CompletionStage<ToolResult> resultStage =
        Objects.requireNonNull(
            tool.execute(
                new ToolArguments(call.arguments()),
                new ToolContext(request.cancellationSignal(), request.attributes())),
            "tool handler response stage must not be null");
    return resultStage.thenCompose(
        result -> {
          List<Content> nextResults = new ArrayList<>(accumulatedResults);
          nextResults.add(
              new ToolResultContent(call.callId(), call.name(), result.content(), result.error()));
          return executeToolCalls(calls, request, index + 1, List.copyOf(nextResults));
        });
  }

  private static Usage combineUsage(Usage accumulated, Usage update) {
    if (accumulated == null) {
      return update;
    }
    if (update == null) {
      return accumulated;
    }
    Map<String, Object> additionalProperties =
        new LinkedHashMap<>(accumulated.additionalProperties());
    update
        .additionalProperties()
        .forEach(
            (key, value) ->
                additionalProperties.merge(key, value, AgentEngine::combineUsageProperty));
    return new Usage(
        Math.addExact(accumulated.inputTokens(), update.inputTokens()),
        Math.addExact(accumulated.outputTokens(), update.outputTokens()),
        Math.addExact(accumulated.totalTokens(), update.totalTokens()),
        additionalProperties);
  }

  private static Object combineUsageProperty(Object accumulated, Object update) {
    if (isIntegralNumber(accumulated) && isIntegralNumber(update)) {
      return Math.addExact(((Number) accumulated).longValue(), ((Number) update).longValue());
    }
    if (accumulated instanceof Number left && update instanceof Number right) {
      return left.doubleValue() + right.doubleValue();
    }
    return update;
  }

  private static boolean isIntegralNumber(Object value) {
    return value instanceof Byte
        || value instanceof Short
        || value instanceof Integer
        || value instanceof Long;
  }

  private static List<ToolCallContent> toolCalls(ModelResponse response) {
    List<ToolCallContent> calls = new ArrayList<>();
    for (Message message : response.messages()) {
      for (Content content : message.content()) {
        if (content instanceof ToolCallContent call) {
          calls.add(call);
        }
      }
    }
    return List.copyOf(calls);
  }

  private record ToolLoopResult(
      ModelResponse terminalResponse, List<Message> messages, Usage usage) {}

  /**
   * A context provider bound to the fixed source id read once when the agent was built, so a
   * provider cannot change the session state namespace it owns between runs or hooks.
   */
  private record ProviderBinding(String sourceId, ContextProvider provider) {

    private ProviderSessionState state(SessionContext sessionContext) {
      return sessionContext.providerState(sourceId);
    }
  }

  /**
   * Resolves how the first model call of this run is made, without making it. Capability mismatches
   * (a continuation token for a client that cannot resume) still fail synchronously from {@code
   * run}, while the call itself happens only after every {@code beforeRun} hook completed.
   */
  private static Function<ModelRequest, CompletionStage<ModelResponse>> resolveModelInvoker(
      ModelClient client, AgentRunRequest request) {
    Optional<String> continuationToken = request.options().continuationToken();
    if (continuationToken.isEmpty()) {
      return client::run;
    }
    if (!(client instanceof ContinuationModelClient continuationClient)) {
      throw new UnsupportedOperationException("model client does not support continuation");
    }
    String token = continuationToken.get();
    return modelRequest -> continuationClient.resume(modelRequest, token);
  }

  /** The streaming counterpart of {@link #resolveModelInvoker}. */
  private static Function<ModelRequest, Flow.Publisher<ModelResponseUpdate>>
      resolveStreamingInvoker(ModelClient client, AgentRunRequest request) {
    Optional<String> continuationToken = request.options().continuationToken();
    if (continuationToken.isEmpty()) {
      if (!(client instanceof StreamingModelClient streamingClient)) {
        throw new UnsupportedOperationException("model client does not support streaming");
      }
      return streamingClient::runStreaming;
    }
    if (!(client instanceof StreamingContinuationModelClient continuationClient)) {
      throw new UnsupportedOperationException(
          "model client does not support streaming continuation");
    }
    String token = continuationToken.get();
    return modelRequest -> continuationClient.resumeStreaming(modelRequest, token);
  }

  /**
   * Delays subscribing to the model's update publisher until {@code gate} completes, so a streaming
   * run performs no model call before every {@code beforeRun} hook finished. It is used only when
   * at least one context provider is configured; a run without providers keeps creating the model
   * publisher eagerly, so nothing that could fail synchronously before becomes deferred here. The
   * gate is composed when the run starts, not when the stream is consumed, so hooks run in the same
   * place as on the ordinary path. A failed gate, or a failure while the source publisher is being
   * created, is delivered as a terminal {@code onError} after the mandatory {@code onSubscribe},
   * which the run's response stage then reports.
   *
   * <p>Cancellation races the gate rather than waiting for it. A hook still pending when the run is
   * cancelled would otherwise leave an already-subscribed consumer without any signal until the
   * hook completed, so the cancellation terminates the subscription immediately and the model is
   * never called even if the hook completes afterwards. The listener is removed as soon as either
   * side wins, so a completed run keeps no registration on the run's cancellation signal.
   */
  private static Flow.Publisher<ModelResponseUpdate> deferUntil(
      CompletionStage<Void> gate,
      CancellationSignal cancellationSignal,
      Supplier<Flow.Publisher<ModelResponseUpdate>> source) {
    return subscriber -> {
      Objects.requireNonNull(subscriber, "subscriber must not be null");
      CompletableFuture<Void> started = new CompletableFuture<>();
      Runnable removeCancellationListener =
          cancellationSignal.onCancel(
              () -> started.completeExceptionally(new CancellationException("run was cancelled")));
      gate.whenComplete(
          (ignored, failure) -> {
            if (failure == null) {
              started.complete(null);
            } else {
              started.completeExceptionally(failure);
            }
          });
      started.whenComplete(
          (ignored, failure) -> {
            removeCancellationListener.run();
            Flow.Publisher<ModelResponseUpdate> publisher = null;
            Throwable terminalFailure = unwrap(failure);
            if (terminalFailure == null) {
              try {
                publisher = source.get();
              } catch (RuntimeException sourceFailure) {
                terminalFailure = sourceFailure;
              }
            }
            if (terminalFailure != null) {
              subscriber.onSubscribe(EmptySubscription.INSTANCE);
              subscriber.onError(terminalFailure);
              return;
            }
            publisher.subscribe(subscriber);
          });
    };
  }

  private static Throwable unwrap(Throwable failure) {
    if (failure instanceof CompletionException && failure.getCause() != null) {
      return failure.getCause();
    }
    return failure;
  }

  private enum EmptySubscription implements Flow.Subscription {
    INSTANCE;

    @Override
    public void request(long n) {
      // A stream that failed before it started has nothing to deliver on demand.
    }

    @Override
    public void cancel() {
      // A stream that failed before it started has nothing left to cancel.
    }
  }

  private static final class MappingSubscriber implements Flow.Subscriber<ModelResponseUpdate> {
    private final Flow.Subscriber<? super AgentResponseUpdate> downstream;
    private final String agentId;
    private final String responseId;
    private final String authorName;
    private final Instant createdAt;

    private MappingSubscriber(
        Flow.Subscriber<? super AgentResponseUpdate> downstream,
        String agentId,
        String responseId,
        String authorName,
        Instant createdAt) {
      this.downstream = downstream;
      this.agentId = agentId;
      this.responseId = responseId;
      this.authorName = authorName;
      this.createdAt = createdAt;
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
      downstream.onSubscribe(subscription);
    }

    @Override
    public void onNext(ModelResponseUpdate item) {
      downstream.onNext(
          ModelResponseMapper.toAgentResponseUpdate(
              agentId, responseId, authorName, createdAt, item));
    }

    @Override
    public void onError(Throwable throwable) {
      downstream.onError(throwable);
    }

    @Override
    public void onComplete() {
      downstream.onComplete();
    }
  }
}
