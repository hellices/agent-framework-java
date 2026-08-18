package io.github.hellices.agentframework.engine;

import io.github.hellices.agentframework.api.agent.Agent;
import io.github.hellices.agentframework.api.agent.AgentDefinition;
import io.github.hellices.agentframework.api.agent.AgentFactory;
import io.github.hellices.agentframework.api.agent.AgentResponse;
import io.github.hellices.agentframework.api.agent.AgentResponseUpdate;
import io.github.hellices.agentframework.api.agent.AgentRun;
import io.github.hellices.agentframework.api.agent.AgentRunContext;
import io.github.hellices.agentframework.api.agent.AgentRunRequest;
import io.github.hellices.agentframework.api.agent.AgentRuntime;
import io.github.hellices.agentframework.api.agent.AgentStreamingRun;
import io.github.hellices.agentframework.api.agent.CancellationSignal;
import io.github.hellices.agentframework.api.agent.RunContribution;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.session.SessionContext;
import io.github.hellices.agentframework.api.tool.ToolResult;
import io.github.hellices.agentframework.api.value.JsonObject;
import io.github.hellices.agentframework.engine.internal.context.ContextProviderPipeline;
import io.github.hellices.agentframework.engine.internal.context.ProviderBinding;
import io.github.hellices.agentframework.engine.internal.context.RunContributionMerger;
import io.github.hellices.agentframework.engine.internal.interception.InterceptorRegistry;
import io.github.hellices.agentframework.engine.internal.model.ModelResponseMapper;
import io.github.hellices.agentframework.engine.internal.model.ResponseIdentity;
import io.github.hellices.agentframework.engine.internal.run.RunExecution;
import io.github.hellices.agentframework.engine.internal.run.RunPipeline;
import io.github.hellices.agentframework.engine.internal.session.SessionCoordinator;
import io.github.hellices.agentframework.engine.internal.tool.ToolLoopPolicy;
import io.github.hellices.agentframework.spi.interception.AgentExecution;
import io.github.hellices.agentframework.spi.interception.AgentInvocation;
import io.github.hellices.agentframework.spi.interception.AgentInvocationChain;
import io.github.hellices.agentframework.spi.interception.ModelInvocation;
import io.github.hellices.agentframework.spi.interception.ModelInvocationChain;
import io.github.hellices.agentframework.spi.interception.SessionInvocation;
import io.github.hellices.agentframework.spi.interception.SessionInvocationChain;
import io.github.hellices.agentframework.spi.interception.SessionOperationResult;
import io.github.hellices.agentframework.spi.interception.ToolInvocation;
import io.github.hellices.agentframework.spi.interception.ToolInvocationChain;
import io.github.hellices.agentframework.spi.model.ModelCatalog;
import io.github.hellices.agentframework.spi.model.ModelClient;
import io.github.hellices.agentframework.spi.model.ModelRequest;
import io.github.hellices.agentframework.spi.model.ModelResponse;
import io.github.hellices.agentframework.spi.model.ModelResponseUpdate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The embeddable, model-independent execution engine. It owns only the services an agent's runs
 * share — session coordination — and holds no per-agent identity, model client, tool set, or
 * provider list, so one immutable engine is safe to share across every agent and every concurrent
 * run.
 *
 * <p>An agent is produced by {@link #bind(AgentDefinition, AgentRuntime)}: the declarative {@link
 * AgentDefinition} and the runnable {@link AgentRuntime} become an immutable {@link AgentBinding}
 * that a {@link BoundAgent} carries back into this engine's execution hooks. Because the engine is
 * stateless per agent, the same run, streaming, tool-loop, and session behaviour applies regardless
 * of which agent a binding describes.
 */
public final class AgentEngine {

  private final SessionCoordinator sessionCoordinator;
  private final InterceptorRegistry interceptorRegistry;

  AgentEngine(SessionCoordinator sessionCoordinator) {
    this(sessionCoordinator, new InterceptorRegistry(List.of(), List.of(), List.of(), List.of()));
  }

  AgentEngine(SessionCoordinator sessionCoordinator, InterceptorRegistry interceptorRegistry) {
    this.sessionCoordinator = sessionCoordinator;
    this.interceptorRegistry =
        Objects.requireNonNull(interceptorRegistry, "interceptorRegistry must not be null");
  }

  public static AgentEngineBuilder builder() {
    return new AgentEngineBuilder();
  }

  /**
   * Composes this model-independent engine with a {@link ModelCatalog} into an {@link AgentFactory}
   * that binds agents to this engine, so one shared engine can produce many agents that differ only
   * in their declaration and runtime.
   *
   * @param catalog the catalog a produced builder resolves default and named models from
   * @return a factory that binds every agent it produces to this engine
   */
  public AgentFactory factory(ModelCatalog catalog) {
    return new CatalogAgentFactory(this, catalog);
  }

  /**
   * Returns an {@link AgentFactory} over this engine with no model catalog, for direct-client
   * assembly: {@code factory().builderWithClient(client)} produces an agent from a supplied {@link
   * ModelClient} without manufacturing a synthetic one-entry catalog first.
   *
   * <p>The factory carries an empty catalog, so the catalog-backed routes still fail with their
   * usual actionable messages: {@link AgentFactory#builder()} reports that no default model is
   * configured, and {@link AgentFactory#builder(String)} reports the named model is unknown. Reach
   * for {@link #factory(ModelCatalog)} when default or named model selection is needed.
   *
   * @return a factory that binds every agent it produces to this engine
   */
  public AgentFactory factory() {
    return factory(ModelCatalog.builder().build());
  }

  /**
   * Binds this model-independent engine to a concrete agent by pairing a declarative {@link
   * AgentDefinition} with a runnable {@link AgentRuntime}, validating the runtime against the
   * definition before returning so no agent exists whose bound handlers do not match its declared
   * tools.
   *
   * @param definition the agent's declarative identity, tools, and default run options
   * @param runtime the agent's model client, tool handlers, and context providers
   * @return an {@link Agent} that delegates its execution hooks to this engine
   */
  public Agent bind(AgentDefinition definition, AgentRuntime runtime) {
    Objects.requireNonNull(definition, "definition must not be null");
    Objects.requireNonNull(runtime, "runtime must not be null");
    runtime.validate(definition);
    return new BoundAgent(definition, runtime, this);
  }

  AgentExecution interceptAgent(AgentInvocation invocation, AgentInvocationChain terminal) {
    return interceptorRegistry.interceptAgent(invocation, terminal);
  }

  Flow.Publisher<ModelResponseUpdate> interceptModel(
      ModelInvocation invocation, ModelInvocationChain terminal) {
    return interceptorRegistry.interceptModel(invocation, terminal);
  }

  CompletionStage<ToolResult> interceptTool(
      ToolInvocation invocation, ToolInvocationChain terminal) {
    return interceptorRegistry.interceptTool(invocation, terminal);
  }

  CompletionStage<SessionOperationResult> interceptSession(
      SessionInvocation invocation, SessionInvocationChain terminal) {
    return interceptorRegistry.interceptSession(invocation, terminal);
  }

  /**
   * Resolves this run's providers and tells the run's context which session state namespaces a
   * later save may write back.
   *
   * <p>Restricting write-back here is what keeps the durable session a function of the providers
   * the run actually ran: {@code SessionContext#providerState(SessionStateKey)} is reachable
   * plumbing rather than an isolation boundary, so without this a provider reaching a sibling
   * namespace would have that write persisted under a name nobody owned in this run. Only stateful
   * providers own a namespace; a stateless provider reserves nothing and contributes no id here.
   */
  private List<ProviderBinding> bindRun(AgentBinding binding, SessionContext sessionContext) {
    List<ProviderBinding> resolved = binding.resolveProviders(sessionContext);
    List<String> sourceIds = new ArrayList<>(resolved.size());
    for (ProviderBinding providerBinding : resolved) {
      String sourceId = providerBinding.sourceId();
      if (sourceId != null) {
        sourceIds.add(sourceId);
      }
    }
    sessionContext.restrictPersistedSources(sourceIds);
    return resolved;
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
  AgentRun runInternal(AgentBinding binding, AgentRunContext context, AgentRunRequest request) {
    ResponseIdentity identity =
        new ResponseIdentity(
            binding.id(), UUID.randomUUID().toString(), binding.name(), Instant.now());
    SessionContext sessionContext = context.sessionContext();
    AgentPipelineHandle handle = interceptRun(binding, context, request, identity);
    if (handle.failed()) {
      return AgentRun.engineManaged(
          failedStage(handle.failure()),
          request.cancellationSignal(),
          sessionContext::updatedSession);
    }
    return AgentRun.engineManaged(
        finalizeOrdinary(binding, sessionContext, identity, handle),
        request.cancellationSignal(),
        sessionContext::updatedSession);
  }

  /**
   * Derives an ordinary run's response and drives its post-run lifecycle, from whichever source the
   * agent seam left canonical.
   *
   * <p>A pass-through run reads the pipeline's boundary-preserving terminal response, exactly as it
   * did before the seam existed: an internal subscriber drives every model and tool iteration to
   * completion while the caller waits only for that assembled response. A run whose agent
   * interceptor replaced or mapped the execution's updates instead reconstructs its response from
   * those transformed updates — the same updates a streaming caller would see — so the
   * transformation is the sole source of the final response for both run shapes. A short-circuited
   * run reconstructs from the interceptor's updates too, then finalizes without any pipeline,
   * provider, or store work.
   */
  private CompletionStage<AgentResponse> finalizeOrdinary(
      AgentBinding binding,
      SessionContext sessionContext,
      ResponseIdentity identity,
      AgentPipelineHandle handle) {
    RunPipeline pipeline = handle.pipeline();
    if (pipeline != null && !handle.transformed()) {
      RunExecution execution = pipeline.execution();
      CompletionStage<ModelResponse> terminal = pipeline.terminalResponse();
      pipeline.subscribe(new DrainingSubscriber());
      return terminal
          .thenApply(
              model ->
                  ModelResponseMapper.toAgentResponse(
                      identity.agentId(),
                      identity.responseId(),
                      identity.authorName(),
                      identity.createdAt(),
                      model))
          .thenCompose(
              agentResponse -> completeRun(binding, sessionContext, execution, agentResponse));
    }
    CompletionStage<AgentResponse> reconstructed = collectUpdates(handle.execution().updates());
    if (pipeline != null) {
      RunExecution execution = pipeline.execution();
      return reconstructed.thenCompose(
          agentResponse -> completeRun(binding, sessionContext, execution, agentResponse));
    }
    return reconstructed.thenCompose(
        agentResponse -> finalizeShortCircuit(sessionContext, agentResponse));
  }

  /**
   * Builds the one update-oriented pipeline a run executes, whichever way it is consumed. The first
   * model call is decided here so that a run without an asynchronous gate keeps failing
   * synchronously when its client throws or returns {@code null}, exactly as it did before the
   * context provider pipeline existed; with a gate the first call is deferred until the session was
   * loaded and every {@code beforeRun} hook completed, so the same failures are instead reported on
   * the run's terminal signal. The run's explicit state machine is advanced through the phases the
   * engine owns — validation, session load and context preparation — before the pipeline takes it
   * from the first model request through response finalisation.
   */
  private RunPipeline buildPipeline(
      AgentBinding binding,
      AgentRunContext context,
      AgentRunRequest request,
      ResponseIdentity identity) {
    ToolLoopPolicy toolLoop = binding.toolLoop();
    if (toolLoop.hasTools() && request.options().continuationToken().isPresent()) {
      throw new UnsupportedOperationException("continuation tool execution is not supported");
    }
    ModelClient selectedClient = request.options().resolveModelClient(binding.modelClient());
    SessionContext sessionContext = context.sessionContext();
    String modelAgentId = binding.id();
    String modelSessionId =
        sessionContext.session() == null ? null : sessionContext.session().sessionId();
    Function<ModelRequest, Flow.Publisher<ModelResponseUpdate>> invoker =
        modelRequest ->
            interceptModel(
                ModelInvocation.builder()
                    .agentId(modelAgentId)
                    .sessionId(modelSessionId)
                    .request(modelRequest)
                    .build(),
                modelInvocation ->
                    Objects.requireNonNull(
                        selectedClient.execute(modelInvocation.request()),
                        "model client update publisher must not be null"));
    ToolLoopPolicy.BoundToolInvoker toolInvoker =
        (tool, call, toolContext) ->
            interceptTool(
                ToolInvocation.builder()
                    .toolCall(call)
                    .toolDefinition(tool.definition())
                    .context(toolContext)
                    .build(),
                toolInvocation ->
                    tool.execute(toolInvocation.arguments(), toolInvocation.context()));
    RunExecution execution = RunExecution.create(request, binding.definition(), binding.runtime());
    RunEffectiveState state =
        new RunEffectiveState(
            binding, RunContributionMerger.merge(binding.definition(), List.of()));
    CompletionStage<Void> gate =
        runGate(binding, sessionContext, request.cancellationSignal(), state);
    AtomicReference<ModelRequest> firstRequest = new AtomicReference<>();
    Supplier<Flow.Publisher<ModelResponseUpdate>> firstStream =
        () -> {
          ModelRequest modelRequest = toModelRequest(state, request, sessionContext);
          firstRequest.set(modelRequest);
          return Objects.requireNonNull(
              invoker.apply(modelRequest), "model client update publisher must not be null");
        };
    Flow.Publisher<ModelResponseUpdate> modelUpdates =
        gate == null
            ? firstStream.get()
            : deferUntil(
                gate,
                request.cancellationSignal(),
                () -> {
                  if (request.cancellationSignal().isCancelled()) {
                    throw new CancellationException("run was cancelled");
                  }
                  return firstStream.get();
                });
    return new RunPipeline(
        () -> modelUpdates,
        firstRequest::get,
        invoker,
        state::toolLoop,
        toolInvoker,
        request,
        identity,
        execution);
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
      AgentBinding binding,
      SessionContext sessionContext,
      CancellationSignal cancellationSignal,
      RunEffectiveState state) {
    if (sessionCoordinator != null && sessionContext.session() != null) {
      return sessionCoordinator
          .load(sessionContext)
          .thenCompose(
              ignored ->
                  prepareRun(
                      binding,
                      bindRun(binding, sessionContext),
                      sessionContext,
                      cancellationSignal,
                      state));
    }
    List<ProviderBinding> resolved = bindRun(binding, sessionContext);
    if (resolved.isEmpty()) {
      return null;
    }
    return prepareRun(binding, resolved, sessionContext, cancellationSignal, state);
  }

  /**
   * Starts a streaming run. Without an asynchronous gate — no configured session store for a run
   * with a session, and no resolved context providers — the model's update publisher is created
   * eagerly and a client that throws or returns {@code null} fails this call synchronously, exactly
   * as it did before the context provider pipeline existed. With a gate the first model call must
   * happen after the session was loaded and every {@code beforeRun} hook completed, so publisher
   * creation is deferred and the same failures are instead delivered to the update subscriber as a
   * terminal {@code onError}, which the run's response stage reports. The only failure that does
   * not depend on the gate — a continuation token combined with tools — stays synchronous in both
   * shapes. Which shape applies follows the same rule {@link #runInternal} documents: a run that
   * carries a session always has a gate.
   *
   * <p>A run streams through the shared {@link RunPipeline}, which turns the same tool loop the
   * ordinary run executes into one update stream: every model call's updates are forwarded live,
   * each tool result is reported as an update the engine synthesised, and the whole loop assembles
   * into one response carrying the content, order, tool results, usage and terminal outcome of an
   * equivalent ordinary run (TOOL-015). What that response does not reproduce is the message
   * boundaries an ordinary client returned, because a streamed response is reconstructed by {@link
   * AgentResponse#fromUpdates} — see {@link RunPipeline} for why inventing a message identity would
   * be worse. A run without tools follows the same pipeline, so an unknown tool call fails it as it
   * does an ordinary run rather than being forwarded unexecuted.
   */
  AgentStreamingRun<AgentResponseUpdate> runStreamingInternal(
      AgentBinding binding, AgentRunContext context, AgentRunRequest request) {
    ResponseIdentity identity =
        new ResponseIdentity(
            binding.id(), UUID.randomUUID().toString(), binding.name(), Instant.now());
    SessionContext sessionContext = context.sessionContext();
    AgentPipelineHandle handle = interceptRun(binding, context, request, identity);
    if (handle.failed()) {
      return AgentStreamingRun.engineManaged(
          failingPublisher(handle.failure()),
          request.cancellationSignal(),
          response -> failedStage(handle.failure()),
          sessionContext::updatedSession);
    }
    if (handle.pipeline() != null) {
      RunExecution execution = handle.pipeline().execution();
      return AgentStreamingRun.engineManaged(
          handle.execution().updates(),
          request.cancellationSignal(),
          response -> completeRun(binding, sessionContext, execution, response),
          sessionContext::updatedSession);
    }
    return AgentStreamingRun.engineManaged(
        handle.execution().updates(),
        request.cancellationSignal(),
        response -> finalizeShortCircuit(sessionContext, response),
        sessionContext::updatedSession);
  }

  /**
   * Wraps the pre-finalization {@link AgentExecution} the agent seam produces around a run's
   * pipeline, so the four interceptor families see a run once each in the right place. The chain's
   * terminal lazily builds the run's {@link RunPipeline} exactly when a chain proceeds to it, so an
   * interceptor that short-circuits performs no model, session, or tool work: no pipeline is built,
   * and the returned handle carries only the canonical updates the interceptor chose. A synchronous
   * failure the pipeline build raises stays synchronous — preserving the eager run's failure shape
   * — while a failure an interceptor raises, a {@code null} it returns, or a cancellation signal it
   * swaps is routed to the run's own response or update failure channel with its exact root cause.
   */
  private AgentPipelineHandle interceptRun(
      AgentBinding binding,
      AgentRunContext context,
      AgentRunRequest request,
      ResponseIdentity identity) {
    AgentInvocation invocation =
        AgentInvocation.builder().agentDefinition(binding.definition()).request(request).build();
    AtomicReference<RunPipeline> pipelineRef = new AtomicReference<>();
    AtomicReference<AgentExecution> terminalRef = new AtomicReference<>();
    AgentInvocationChain terminal =
        proceeding -> {
          try {
            RunPipeline pipeline = buildPipeline(binding, context, request, identity);
            pipelineRef.set(pipeline);
            AgentExecution built =
                AgentExecution.fromUpdates(pipeline, request.cancellationSignal());
            terminalRef.set(built);
            return built;
          } catch (RuntimeException failure) {
            throw new PipelineBuildException(failure);
          }
        };
    AgentExecution execution;
    try {
      execution = interceptAgent(invocation, terminal);
    } catch (PipelineBuildException failure) {
      // A pipeline-build failure keeps failing the caller synchronously, exactly as an eager run
      // did before the seam existed; only interceptor-raised failures are routed asynchronously.
      throw failure.cause();
    } catch (RuntimeException failure) {
      return AgentPipelineHandle.failed(failure);
    }
    if (!execution.cancellationSignal().equals(request.cancellationSignal())) {
      // The seam must hand back the run's own signal (CancellationSignal has identity equality), so
      // a cancellation reaches the stream the run subscribes to. A swapped signal fails explicitly
      // here, before any subscription, rather than leaking a run that cannot be cancelled.
      return AgentPipelineHandle.failed(
          new IllegalStateException(
              "agent execution cancellationSignal must be the run's cancellation signal"));
    }
    RunPipeline pipeline = pipelineRef.get();
    // An interceptor that replaces or maps the execution hands back a different value than the
    // terminal built (AgentExecution has identity equality), so its transformed updates — not the
    // pipeline's raw response — become the sole source of the run's final response, for the
    // ordinary
    // run as much as the streaming one. A pass-through interceptor returns the terminal's own
    // execution unchanged, so an ordinary run keeps its boundary-preserving terminal response.
    boolean transformed = pipeline != null && !execution.equals(terminalRef.get());
    return AgentPipelineHandle.of(execution, pipeline, transformed);
  }

  /**
   * Subscribes to a short-circuited execution's canonical updates and reconstructs the run's final
   * response from them, the same way a streamed run's finalizer does, so a short-circuit and a
   * normal run derive their response from the same update contract.
   */
  private static CompletionStage<AgentResponse> collectUpdates(
      Flow.Publisher<AgentResponseUpdate> updates) {
    CompletableFuture<AgentResponse> outcome = new CompletableFuture<>();
    updates.subscribe(
        new Flow.Subscriber<>() {
          private final List<AgentResponseUpdate> buffered = new ArrayList<>();

          @Override
          public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
          }

          @Override
          public void onNext(AgentResponseUpdate item) {
            buffered.add(Objects.requireNonNull(item, "agent response update must not be null"));
          }

          @Override
          public void onError(Throwable throwable) {
            outcome.completeExceptionally(unwrap(throwable));
          }

          @Override
          public void onComplete() {
            try {
              outcome.complete(AgentResponse.fromUpdates(List.copyOf(buffered)));
            } catch (RuntimeException failure) {
              outcome.completeExceptionally(failure);
            }
          }
        });
    return outcome.minimalCompletionStage();
  }

  /**
   * Completes the lifecycle of a short-circuited run: it fills the run's response slot once from
   * the canonical updates the interceptor returned and completes with the run's input session. A
   * pre-finalization short-circuit performed no session load and resolved no context provider, so
   * it runs neither the provider {@code afterRun} hooks nor a session save — persisting here would
   * overwrite a stored session it never read, regressing its revision. A run that built its
   * pipeline uses {@link #completeRun} instead, which owns the full provider and persistence
   * lifecycle.
   */
  private CompletionStage<AgentResponse> finalizeShortCircuit(
      SessionContext sessionContext, AgentResponse response) {
    CompletableFuture<AgentResponse> outcome = new CompletableFuture<>();
    try {
      sessionContext.complete(response);
    } catch (RuntimeException immediate) {
      outcome.completeExceptionally(immediate);
      return outcome.minimalCompletionStage();
    }
    outcome.complete(response);
    return outcome.minimalCompletionStage();
  }

  private static <T> CompletionStage<T> failedStage(Throwable failure) {
    CompletableFuture<T> outcome = new CompletableFuture<>();
    outcome.completeExceptionally(failure);
    return outcome.minimalCompletionStage();
  }

  private static Flow.Publisher<AgentResponseUpdate> failingPublisher(Throwable failure) {
    return subscriber -> {
      Objects.requireNonNull(subscriber, "subscriber must not be null");
      subscriber.onSubscribe(EmptySubscription.INSTANCE);
      subscriber.onError(failure);
    };
  }

  /**
   * Marks a failure raised while the agent seam's terminal builds the run's pipeline, so the run
   * can keep failing the caller synchronously for a pipeline-build failure while routing an
   * interceptor-raised failure to the run's own response or update channel.
   */
  private static final class PipelineBuildException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient RuntimeException cause;

    PipelineBuildException(RuntimeException cause) {
      super(cause);
      this.cause = cause;
    }

    RuntimeException cause() {
      return cause;
    }
  }

  /**
   * The outcome of running the agent interceptor seam once for a run: either a routed failure, a
   * built pipeline with its execution, or a short-circuited execution carrying only canonical
   * updates (its pipeline is {@code null}).
   */
  private static final class AgentPipelineHandle {

    private final AgentExecution execution;
    private final RunPipeline pipeline;
    private final boolean transformed;
    private final RuntimeException failure;

    private AgentPipelineHandle(
        AgentExecution execution,
        RunPipeline pipeline,
        boolean transformed,
        RuntimeException failure) {
      this.execution = execution;
      this.pipeline = pipeline;
      this.transformed = transformed;
      this.failure = failure;
    }

    static AgentPipelineHandle of(
        AgentExecution execution, RunPipeline pipeline, boolean transformed) {
      return new AgentPipelineHandle(
          Objects.requireNonNull(execution, "execution must not be null"),
          pipeline,
          transformed,
          null);
    }

    static AgentPipelineHandle failed(RuntimeException failure) {
      return new AgentPipelineHandle(
          null, null, false, Objects.requireNonNull(failure, "failure must not be null"));
    }

    boolean failed() {
      return failure != null;
    }

    RuntimeException failure() {
      return failure;
    }

    AgentExecution execution() {
      return execution;
    }

    RunPipeline pipeline() {
      return pipeline;
    }

    /**
     * Whether an interceptor replaced or mapped the execution's updates, so its transformed updates
     * are the sole source of the run's final response even on the ordinary path.
     */
    boolean transformed() {
      return transformed;
    }
  }

  /**
   * Composes the run's context providers into their {@code prepare} hooks and folds the resulting
   * contributions into the run's effective request. The {@link ContextProviderPipeline} runs every
   * hook in declaration order — checking cancellation before each, folding each contribution's
   * messages into the run's context with provider attribution, and failing the composed stage on
   * any hook failure, {@code null} stage, {@code null} contribution, or cancellation observed
   * before a hook — so no later hook or model call runs. The accumulated contributions are then
   * merged with the agent's declaration, once, into the effective instructions, tools, and options
   * the run's first model call is built from; a contributed tool name that duplicates an earlier
   * declaration fails here, before the model is called.
   */
  private CompletionStage<Void> prepareRun(
      AgentBinding binding,
      List<ProviderBinding> providers,
      SessionContext sessionContext,
      CancellationSignal cancellationSignal,
      RunEffectiveState state) {
    return new ContextProviderPipeline(providers)
        .prepare(sessionContext, cancellationSignal)
        .thenAccept(contributions -> state.apply(binding, contributions));
  }

  /**
   * Owns the entire post-run lifecycle of an engine (bound-agent) run, in the one order every run
   * observes:
   *
   * <ol>
   *   <li>the run's state machine enters context completion, then the run's {@link SessionContext}
   *       response slot is filled exactly once with the reconstructed response;
   *   <li>every resolved context provider's {@code afterRun} hook runs in reverse declaration
   *       order, each observing the same context its {@code beforeRun} opened plus the final
   *       response;
   *   <li>the state machine enters session persistence and the session this run produced is saved;
   *   <li>on success the run terminalises successfully — detaching its cancellation listener — and
   *       the returned stage completes with the response, which is what lets the ordinary response
   *       stage complete and the streaming update publisher emit {@code onComplete}.
   * </ol>
   *
   * <p>Any provider-completion or save failure terminalises the run exceptionally with the raw
   * unwrapped cause and completes the returned stage exceptionally with that identical cause, so
   * the ordinary and streaming response and session stages all fail with the exact same root cause,
   * and a streaming subscriber sees {@code onError} — never {@code onComplete} — for such a run.
   * Because this method owns the lifecycle, the {@link Agent} facade must not re-run its completion
   * action or {@code afterRun} seam for the engine-managed run it returns.
   */
  private CompletionStage<AgentResponse> completeRun(
      AgentBinding binding,
      SessionContext sessionContext,
      RunExecution execution,
      AgentResponse response) {
    CompletableFuture<AgentResponse> outcome = new CompletableFuture<>();
    try {
      execution.enterContextCompletion();
      sessionContext.complete(response);
    } catch (RuntimeException immediate) {
      execution.terminateExceptionally(immediate);
      outcome.completeExceptionally(immediate);
      return outcome.minimalCompletionStage();
    }
    runProviderAfterRun(binding, sessionContext)
        .thenCompose(
            ignored -> {
              execution.enterSessionPersistence();
              return saveSession(sessionContext);
            })
        .whenComplete(
            (ignored, failure) -> {
              if (failure == null) {
                execution.terminateSuccessfully();
                outcome.complete(response);
              } else {
                Throwable cause = unwrap(failure);
                execution.terminateExceptionally(cause);
                outcome.completeExceptionally(cause);
              }
            });
    return outcome.minimalCompletionStage();
  }

  /**
   * Composes every resolved provider's {@code complete} hook in reverse declaration order. The
   * providers are the same ones {@code prepare} used, because resolution is a pure function of the
   * agent's bind-time configuration and the run's set-once effective session. A stateful provider
   * resolves its own key-bound state view through the default {@code complete} bridge. A hook
   * failure fails the composed stage and stops the remaining (earlier-declared) hooks, so a run
   * that fails anywhere never reaches the save and leaves the stored session untouched.
   */
  private CompletionStage<Void> runProviderAfterRun(
      AgentBinding binding, SessionContext sessionContext) {
    return new ContextProviderPipeline(binding.resolveProviders(sessionContext))
        .complete(sessionContext);
  }

  /**
   * Saves the session this run produced, or completes without a write when the run carries no
   * session or the engine has no store.
   *
   * <p>A run with a session and a configured store saves even when it owns no session state
   * namespace at all — a service-managed conversation with no configured provider, for example. The
   * snapshot is the durable record of the session itself: skipping it would leave a session the
   * caller created with a service handle unrecorded, so a later run could not tell "never stored"
   * from "stored with no local state" and would accept any service handle for it. The cost is one
   * revision and one write per run of such a session.
   */
  private CompletionStage<Void> saveSession(SessionContext sessionContext) {
    if (sessionCoordinator == null || sessionContext.session() == null) {
      return CompletableFuture.completedFuture(null);
    }
    return sessionCoordinator.save(sessionContext);
  }

  /**
   * Builds the run's first model request from the effective contributions: the deduplicated leading
   * instruction messages, then the provider-contributed context messages in contribution order,
   * then the caller's input; the merged model options over the definition defaults; and the
   * effective tool declarations offered to the model. Every later tool-loop iteration reuses this
   * request's options and leading instruction and context messages, and the effective tool
   * declarations, through the run's per-run {@link ToolLoopPolicy}.
   */
  private ModelRequest toModelRequest(
      RunEffectiveState state, AgentRunRequest request, SessionContext sessionContext) {
    RunContributionMerger merger = state.merger();
    List<Message> messages =
        merger.assembleMessages(sessionContext.contextMessages(), request.messages());
    return ModelRequest.builder()
        .messages(messages)
        .options(merger.options())
        .continuationToken(request.options().continuationToken().orElse(null))
        .attributes(sessionContext.attributes())
        .cancellationSignal(request.cancellationSignal())
        .tools(state.toolLoop().toolsForIteration(0))
        .metadata(JsonObject.empty())
        .build();
  }

  /**
   * The mutable per-run holder for a run's effective contribution state. It is created with the
   * agent's definition-only merge so an eager run with no provider still applies the definition's
   * instructions, tools, and option defaults; when the run has a context gate, {@link
   * #apply(AgentBinding, List)} recomputes the merge from the accumulated contributions once,
   * before the first model call. The run's {@link RunPipeline} reads {@link #toolLoop()} through a
   * supplier, so every tool-loop iteration offers the same effective tool declarations.
   */
  private static final class RunEffectiveState {

    private volatile RunContributionMerger merger;
    private volatile ToolLoopPolicy toolLoop;

    RunEffectiveState(AgentBinding binding, RunContributionMerger merger) {
      this.merger = merger;
      this.toolLoop = binding.toolLoop(merger.toolDeclarations());
    }

    void apply(AgentBinding binding, List<RunContribution> contributions) {
      RunContributionMerger merged =
          RunContributionMerger.merge(binding.definition(), contributions);
      this.merger = merged;
      this.toolLoop = binding.toolLoop(merged.toolDeclarations());
    }

    RunContributionMerger merger() {
      return merger;
    }

    ToolLoopPolicy toolLoop() {
      return toolLoop;
    }
  }

  /**
   * The subscriber an ordinary run installs to drive its pipeline to completion. It requests every
   * update and discards them: the run's response is read from {@link
   * RunPipeline#terminalResponse()} instead, so the loop's live updates have somewhere to go while
   * the ordinary caller waits only for the assembled response. Terminal signals are ignored here
   * because the terminal response stage already carries the run's success or failure.
   */
  private static final class DrainingSubscriber implements Flow.Subscriber<AgentResponseUpdate> {

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
      subscription.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(AgentResponseUpdate item) {
      // The ordinary run observes the assembled terminal response, not the individual updates.
    }

    @Override
    public void onError(Throwable throwable) {
      // The terminal response stage carries the failure the ordinary caller observes.
    }

    @Override
    public void onComplete() {
      // The terminal response stage carries the success the ordinary caller observes.
    }
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
}
