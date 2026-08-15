package io.github.hellices.agentframework.engine;

import io.github.hellices.agentframework.api.agent.Agent;
import io.github.hellices.agentframework.api.agent.AgentFactory;
import io.github.hellices.agentframework.api.agent.AgentResponse;
import io.github.hellices.agentframework.api.agent.AgentResponseUpdate;
import io.github.hellices.agentframework.api.agent.AgentRun;
import io.github.hellices.agentframework.api.agent.AgentRunContext;
import io.github.hellices.agentframework.api.agent.AgentRunRequest;
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
  private final List<ProviderBinding> contextProviders;
  private final int maxIterations;

  AgentEngine(
      String id,
      String name,
      String description,
      ModelClient modelClient,
      List<FunctionTool> tools,
      List<ContextProvider> contextProviders,
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
    this.contextProviders = bindContextProviders(contextProviders);
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

  public static AgentEngineBuilder builder() {
    return new AgentEngineBuilder();
  }

  public static AgentFactory factory(ModelCatalog catalog) {
    return new CatalogAgentFactory(catalog);
  }

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
    // Providers make the first model call asynchronous, so the run can be cancelled while a hook is
    // still pending. Re-checking here keeps the ordinary path aligned with the streaming one: once
    // the caller observed cancellation, no model request is issued when the hook finally completes.
    CompletionStage<AgentResponse> response =
        beforeRun(sessionContext, request.cancellationSignal())
            .thenCompose(
                ignored -> {
                  if (request.cancellationSignal().isCancelled()) {
                    throw new CancellationException("run was cancelled");
                  }
                  return runToolLoop(
                      selectedClient,
                      modelInvoker,
                      toModelRequest(request, sessionContext),
                      request,
                      0,
                      List.of(),
                      null);
                })
            .thenApply(
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
    Flow.Publisher<ModelResponseUpdate> modelUpdates =
        deferUntil(
            beforeRun(sessionContext, request.cancellationSignal()),
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
   * Composes every provider's {@code beforeRun} hook in declaration order, before the run's first
   * model call. Each hook receives the run's single {@link SessionContext} and the state view bound
   * to its own source id. A hook that fails, returns {@code null}, or is reached after the run was
   * cancelled fails the composed stage, so no later hook and no model call runs.
   */
  private CompletionStage<Void> beforeRun(
      SessionContext sessionContext, CancellationSignal cancellationSignal) {
    CompletionStage<Void> stage = CompletableFuture.completedFuture(null);
    for (ProviderBinding binding : contextProviders) {
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
   * Composes every provider's {@code afterRun} hook in reverse declaration order. The framework
   * calls this only after the run's terminal response completed successfully and the {@link
   * SessionContext} response slot was filled, so a hook observes the same context its {@code
   * beforeRun} opened, plus the final response. A hook failure fails the run and stops the
   * remaining (earlier-declared) hooks.
   */
  @Override
  protected CompletionStage<Void> afterRun(SessionContext sessionContext) {
    CompletionStage<Void> stage = CompletableFuture.completedFuture(null);
    for (int index = contextProviders.size() - 1; index >= 0; index--) {
      ProviderBinding binding = contextProviders.get(index);
      stage =
          stage.thenCompose(
              ignored ->
                  Objects.requireNonNull(
                      binding.provider().afterRun(sessionContext, binding.state(sessionContext)),
                      "context provider after-run stage must not be null"));
    }
    return stage;
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
   * run performs no model call before every {@code beforeRun} hook finished. The gate is composed
   * when the run starts, not when the stream is consumed, so hooks run in the same place as on the
   * ordinary path. A failed gate, or a failure while the source publisher is being created, is
   * delivered as a terminal {@code onError} after the mandatory {@code onSubscribe}, which the
   * run's response stage then reports.
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
