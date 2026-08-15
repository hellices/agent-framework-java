package io.github.hellices.agentframework.engine;

import io.github.hellices.agentframework.api.agent.Agent;
import io.github.hellices.agentframework.api.agent.AgentFactory;
import io.github.hellices.agentframework.api.agent.AgentResponse;
import io.github.hellices.agentframework.api.agent.AgentResponseUpdate;
import io.github.hellices.agentframework.api.agent.AgentRun;
import io.github.hellices.agentframework.api.agent.AgentRunContext;
import io.github.hellices.agentframework.api.agent.AgentRunRequest;
import io.github.hellices.agentframework.api.agent.AgentStreamingRun;
import io.github.hellices.agentframework.api.message.Content;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.Role;
import io.github.hellices.agentframework.api.message.ToolCallContent;
import io.github.hellices.agentframework.api.message.ToolResultContent;
import io.github.hellices.agentframework.api.message.Usage;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

public final class AgentEngine extends Agent {

  private final ModelClient modelClient;
  private final Map<String, FunctionTool> tools;
  private final List<ToolDefinition> toolDefinitions;
  private final int maxIterations;

  AgentEngine(
      String id,
      String name,
      String description,
      ModelClient modelClient,
      List<FunctionTool> tools,
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
    this.maxIterations = maxIterations;
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
    ModelRequest modelRequest = toModelRequest(request);
    String responseId = UUID.randomUUID().toString();
    Instant createdAt = Instant.now();
    CompletionStage<AgentResponse> response =
        runToolLoop(selectedClient, modelRequest, request, 0, List.of(), null)
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
    String responseId = UUID.randomUUID().toString();
    Instant createdAt = Instant.now();
    Flow.Publisher<ModelResponseUpdate> modelUpdates =
        Objects.requireNonNull(
            runModelStreaming(selectedClient, toModelRequest(request), request),
            "model client update publisher must not be null");
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

  private ModelRequest toModelRequest(AgentRunRequest request) {
    return new ModelRequest(
        request.messages(),
        ModelRequestOptions.empty(),
        request.cancellationSignal(),
        maxIterations > 1 ? toolDefinitions : List.of(),
        request.attributes());
  }

  private CompletionStage<ToolLoopResult> runToolLoop(
      ModelClient client,
      ModelRequest modelRequest,
      AgentRunRequest request,
      int iteration,
      List<Message> accumulatedMessages,
      Usage accumulatedUsage) {
    CompletionStage<ModelResponse> responseStage =
        iteration == 0 ? runModel(client, modelRequest, request) : client.run(modelRequest);
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

  private static CompletionStage<ModelResponse> runModel(
      ModelClient client, ModelRequest modelRequest, AgentRunRequest request) {
    return request
        .options()
        .continuationToken()
        .map(
            token -> {
              if (!(client instanceof ContinuationModelClient continuationClient)) {
                throw new UnsupportedOperationException(
                    "model client does not support continuation");
              }
              return continuationClient.resume(modelRequest, token);
            })
        .orElseGet(() -> client.run(modelRequest));
  }

  private static Flow.Publisher<ModelResponseUpdate> runModelStreaming(
      ModelClient client, ModelRequest modelRequest, AgentRunRequest request) {
    return request
        .options()
        .continuationToken()
        .map(
            token -> {
              if (!(client instanceof StreamingContinuationModelClient continuationClient)) {
                throw new UnsupportedOperationException(
                    "model client does not support streaming continuation");
              }
              return continuationClient.resumeStreaming(modelRequest, token);
            })
        .orElseGet(
            () -> {
              if (!(client instanceof StreamingModelClient streamingClient)) {
                throw new UnsupportedOperationException("model client does not support streaming");
              }
              return streamingClient.runStreaming(modelRequest);
            });
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
