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
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.ToolCallContent;
import io.github.hellices.agentframework.api.message.Usage;
import io.github.hellices.agentframework.api.session.SessionContext;
import io.github.hellices.agentframework.api.value.JsonNumber;
import io.github.hellices.agentframework.api.value.JsonObject;
import io.github.hellices.agentframework.api.value.JsonValue;
import io.github.hellices.agentframework.engine.AgentBinding.ProviderBinding;
import io.github.hellices.agentframework.engine.internal.model.ModelResponseMapper;
import io.github.hellices.agentframework.engine.internal.model.ResponseIdentity;
import io.github.hellices.agentframework.engine.internal.session.SessionCoordinator;
import io.github.hellices.agentframework.engine.internal.tool.StreamingToolLoopPublisher;
import io.github.hellices.agentframework.engine.internal.tool.ToolLoopPolicy;
import io.github.hellices.agentframework.spi.model.ContinuationModelClient;
import io.github.hellices.agentframework.spi.model.ModelCatalog;
import io.github.hellices.agentframework.spi.model.ModelClient;
import io.github.hellices.agentframework.spi.model.ModelRequest;
import io.github.hellices.agentframework.spi.model.ModelRequestOptions;
import io.github.hellices.agentframework.spi.model.ModelResponse;
import io.github.hellices.agentframework.spi.model.ModelResponseUpdate;
import io.github.hellices.agentframework.spi.model.StreamingContinuationModelClient;
import io.github.hellices.agentframework.spi.model.StreamingModelClient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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

  AgentEngine(SessionCoordinator sessionCoordinator) {
    this.sessionCoordinator = sessionCoordinator;
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

  /**
   * Resolves this run's providers and tells the run's context which session state namespaces a
   * later save may write back.
   *
   * <p>Restricting write-back here is what keeps the durable session a function of the providers
   * the run actually ran: {@code SessionContext#providerState(String)} is reachable plumbing rather
   * than an isolation boundary, so without this a provider reaching a sibling namespace would have
   * that write persisted under a name nobody owned in this run.
   */
  private List<ProviderBinding> bindRun(AgentBinding binding, SessionContext sessionContext) {
    List<ProviderBinding> resolved = binding.resolveProviders(sessionContext);
    List<String> sourceIds = new ArrayList<>(resolved.size());
    for (ProviderBinding providerBinding : resolved) {
      sourceIds.add(providerBinding.sourceId());
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
    ToolLoopPolicy toolLoop = binding.toolLoop();
    if (toolLoop.hasTools() && request.options().continuationToken().isPresent()) {
      throw new UnsupportedOperationException("continuation tool execution is not supported");
    }
    ModelClient selectedClient = request.options().resolveModelClient(binding.modelClient());
    Function<ModelRequest, CompletionStage<ModelResponse>> modelInvoker =
        resolveModelInvoker(selectedClient, request);
    SessionContext sessionContext = context.sessionContext();
    String responseId = UUID.randomUUID().toString();
    Instant createdAt = Instant.now();
    Supplier<CompletionStage<ToolLoopResult>> firstToolLoop =
        () ->
            runToolLoop(
                toolLoop,
                selectedClient,
                modelInvoker,
                toModelRequest(binding, request, sessionContext),
                request,
                0,
                List.of(),
                null);
    CompletionStage<Void> gate = runGate(binding, sessionContext, request.cancellationSignal());
    CompletionStage<ToolLoopResult> toolLoopResult =
        gate == null
            ? firstToolLoop.get()
            : gate.thenCompose(
                ignored -> {
                  // The gate makes the first model call asynchronous, so the run can be cancelled
                  // while the store or a hook is still pending. Re-checking here keeps the ordinary
                  // path aligned with the streaming one: once the caller observed cancellation, no
                  // model request is issued when the gate finally completes.
                  if (request.cancellationSignal().isCancelled()) {
                    throw new CancellationException("run was cancelled");
                  }
                  return firstToolLoop.get();
                });
    CompletionStage<AgentResponse> response =
        toolLoopResult.thenApply(
            result -> {
              if (request.cancellationSignal().isCancelled()) {
                throw new CancellationException("run was cancelled");
              }
              ModelResponse terminal = result.terminalResponse();
              return ModelResponseMapper.toAgentResponse(
                  binding.id(),
                  responseId,
                  binding.name(),
                  createdAt,
                  ModelResponse.builder()
                      .messages(result.messages())
                      .usage(result.usage())
                      .finishReason(terminal.finishReason())
                      .continuationToken(terminal.continuationToken())
                      .metadata(terminal.metadata())
                      .rawRepresentation(terminal.rawRepresentation())
                      .build());
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
      AgentBinding binding, SessionContext sessionContext, CancellationSignal cancellationSignal) {
    if (sessionCoordinator != null && sessionContext.session() != null) {
      return sessionCoordinator
          .load(sessionContext)
          .thenCompose(
              ignored ->
                  beforeRun(bindRun(binding, sessionContext), sessionContext, cancellationSignal));
    }
    List<ProviderBinding> resolved = bindRun(binding, sessionContext);
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
   * on the gate (a continuation token combined with tools, a client lacking the streaming or
   * streaming-continuation capability) stay synchronous in both shapes. Which shape applies follows
   * the same rule {@link #runInternal} documents: a run that carries a session always has a gate.
   *
   * <p>A run with tools streams through {@link StreamingToolLoopPublisher}, which turns the same
   * tool loop the ordinary run executes into one update stream: every model call's updates are
   * forwarded live, each tool result is reported as an update the engine synthesised, and the whole
   * loop assembles into one response carrying the content, order, tool results, usage and terminal
   * outcome of an equivalent ordinary run (TOOL-015). What that response does not reproduce is the
   * message boundaries an ordinary client returned, because a streamed response is reconstructed by
   * {@link AgentResponse#fromUpdates} — see {@link StreamingToolLoopPublisher} for why inventing a
   * message identity would be worse. A run without tools keeps mapping the single model stream
   * directly, so nothing about it changed.
   */
  AgentStreamingRun<AgentResponseUpdate> runStreamingInternal(
      AgentBinding binding, AgentRunContext context, AgentRunRequest request) {
    ToolLoopPolicy toolLoop = binding.toolLoop();
    if (toolLoop.hasTools() && request.options().continuationToken().isPresent()) {
      throw new UnsupportedOperationException("continuation tool execution is not supported");
    }
    ModelClient selectedClient = request.options().resolveModelClient(binding.modelClient());
    Function<ModelRequest, Flow.Publisher<ModelResponseUpdate>> streamingInvoker =
        resolveStreamingInvoker(selectedClient, request);
    SessionContext sessionContext = context.sessionContext();
    ResponseIdentity identity =
        new ResponseIdentity(
            binding.id(), UUID.randomUUID().toString(), binding.name(), Instant.now());
    CompletionStage<Void> gate = runGate(binding, sessionContext, request.cancellationSignal());
    AtomicReference<ModelRequest> firstRequest = new AtomicReference<>();
    Supplier<Flow.Publisher<ModelResponseUpdate>> firstStream =
        () -> {
          ModelRequest modelRequest = toModelRequest(binding, request, sessionContext);
          firstRequest.set(modelRequest);
          return Objects.requireNonNull(
              streamingInvoker.apply(modelRequest),
              "model client update publisher must not be null");
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
    Flow.Publisher<AgentResponseUpdate> agentUpdates =
        toolLoop.hasTools()
            ? new StreamingToolLoopPublisher(
                () -> modelUpdates,
                firstRequest::get,
                streamingInvoker,
                toolLoop,
                request,
                identity)
            : subscriber ->
                modelUpdates.subscribe(
                    new MappingSubscriber(
                        subscriber,
                        identity.agentId(),
                        identity.responseId(),
                        identity.authorName(),
                        identity.createdAt()));
    return AgentStreamingRun.fromUpdates(
        agentUpdates,
        request.cancellationSignal(),
        () ->
            AgentResponse.builder()
                .agentId(identity.agentId())
                .responseId(identity.responseId())
                .authorName(identity.authorName())
                .createdAt(identity.createdAt())
                .messages(List.of())
                .additionalProperties(JsonObject.empty())
                .build());
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
   * resolution is a pure function of the agent's bind-time configuration and the run's set-once
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
  CompletionStage<Void> afterRun(AgentBinding binding, SessionContext sessionContext) {
    List<ProviderBinding> providers = binding.resolveProviders(sessionContext);
    CompletionStage<Void> stage = CompletableFuture.completedFuture(null);
    for (int index = providers.size() - 1; index >= 0; index--) {
      ProviderBinding providerBinding = providers.get(index);
      stage =
          stage.thenCompose(
              ignored ->
                  Objects.requireNonNull(
                      providerBinding
                          .provider()
                          .afterRun(sessionContext, providerBinding.state(sessionContext)),
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
  private ModelRequest toModelRequest(
      AgentBinding binding, AgentRunRequest request, SessionContext sessionContext) {
    List<Message> messages = new ArrayList<>(sessionContext.contextMessages());
    messages.addAll(request.messages());
    return ModelRequest.builder()
        .messages(messages)
        .options(ModelRequestOptions.empty())
        .continuationToken(request.options().continuationToken().orElse(null))
        .attributes(sessionContext.attributes())
        .cancellationSignal(request.cancellationSignal())
        .tools(binding.toolLoop().toolsForIteration(0))
        .metadata(JsonObject.empty())
        .build();
  }

  private CompletionStage<ToolLoopResult> runToolLoop(
      ToolLoopPolicy toolLoop,
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
              toolLoop.validateContinuation(response);
              List<Message> outputMessages = new ArrayList<>(accumulatedMessages);
              outputMessages.addAll(response.messages());
              Usage usage = combineUsage(accumulatedUsage, response.usage());
              List<ToolCallContent> calls = ToolLoopPolicy.toolCalls(response);
              if (calls.isEmpty()) {
                return CompletableFuture.completedFuture(
                    new ToolLoopResult(response, List.copyOf(outputMessages), usage));
              }
              if (request.cancellationSignal().isCancelled()) {
                throw new CancellationException("run was cancelled");
              }
              toolLoop.requireIterationBudget(iteration);
              if (!toolLoop.canExecuteAll(calls) && toolLoop.declaresAll(calls)) {
                // The model invoked a declaration-only tool: it is declared and offered to the
                // model but has no local body, so the run ends with this response (its tool calls
                // intact) rather than fabricating a result the Java core cannot produce
                // (TOOL-006). Host or provider code can still act on the returned tool calls. A
                // call to an undeclared tool is not handled here: it reaches execution and fails
                // with the existing safe error.
                return CompletableFuture.completedFuture(
                    new ToolLoopResult(response, List.copyOf(outputMessages), usage));
              }
              return toolLoop
                  .executeToolCalls(calls, request)
                  .thenCompose(
                      results -> {
                        Message toolResultMessage = ToolLoopPolicy.toolResultMessage(results);
                        ModelRequest nextRequest =
                            toolLoop.nextRequest(
                                modelRequest,
                                response.messages(),
                                calls,
                                toolResultMessage,
                                iteration);
                        outputMessages.add(toolResultMessage);
                        return runToolLoop(
                            toolLoop,
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

  private static Usage combineUsage(Usage accumulated, Usage update) {
    if (accumulated == null) {
      return update;
    }
    if (update == null) {
      return accumulated;
    }
    return new Usage(
        Math.addExact(accumulated.inputTokens(), update.inputTokens()),
        Math.addExact(accumulated.outputTokens(), update.outputTokens()),
        Math.addExact(accumulated.totalTokens(), update.totalTokens()),
        mergeAdditionalProperties(
            accumulated.additionalProperties(),
            update.additionalProperties(),
            AgentEngine::combineUsageProperty));
  }

  private static JsonValue combineUsageProperty(JsonValue accumulated, JsonValue update) {
    if (accumulated instanceof JsonNumber left && update instanceof JsonNumber right) {
      return JsonNumber.of(left.value().add(right.value()));
    }
    return update;
  }

  private static JsonObject mergeAdditionalProperties(
      JsonObject accumulated,
      JsonObject update,
      java.util.function.BiFunction<JsonValue, JsonValue, JsonValue> merger) {
    if ((accumulated == null || accumulated.isEmpty()) && (update == null || update.isEmpty())) {
      return JsonObject.empty();
    }
    if (accumulated == null || accumulated.isEmpty()) {
      return update == null ? JsonObject.empty() : update;
    }
    if (update == null || update.isEmpty()) {
      return accumulated;
    }
    Map<String, JsonValue> merged = new LinkedHashMap<>(accumulated.values());
    update
        .values()
        .forEach(
            (key, value) ->
                merged.merge(
                    key,
                    value,
                    (left, right) ->
                        Objects.requireNonNull(
                            merger.apply(left, right), "merged value must not be null")));
    JsonObject.Builder builder = JsonObject.builder();
    merged.forEach(builder::put);
    return builder.build();
  }

  private record ToolLoopResult(
      ModelResponse terminalResponse, List<Message> messages, Usage usage) {}

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
    return modelRequest ->
        continuationClient.resume(modelRequest, modelRequest.continuationToken());
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
    return modelRequest ->
        continuationClient.resumeStreaming(modelRequest, modelRequest.continuationToken());
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
