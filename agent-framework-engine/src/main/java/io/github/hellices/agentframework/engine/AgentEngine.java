package io.github.hellices.agentframework.engine;

import io.github.hellices.agentframework.api.agent.Agent;
import io.github.hellices.agentframework.api.agent.AgentResponse;
import io.github.hellices.agentframework.api.agent.AgentResponseUpdate;
import io.github.hellices.agentframework.api.agent.AgentRun;
import io.github.hellices.agentframework.api.agent.AgentRunContext;
import io.github.hellices.agentframework.api.agent.AgentRunRequest;
import io.github.hellices.agentframework.api.agent.AgentStreamingRun;
import io.github.hellices.agentframework.engine.internal.model.ModelResponseMapper;
import io.github.hellices.agentframework.spi.model.ContinuationModelClient;
import io.github.hellices.agentframework.spi.model.ModelClient;
import io.github.hellices.agentframework.spi.model.ModelRequest;
import io.github.hellices.agentframework.spi.model.ModelRequestOptions;
import io.github.hellices.agentframework.spi.model.ModelResponse;
import io.github.hellices.agentframework.spi.model.ModelResponseUpdate;
import io.github.hellices.agentframework.spi.model.StreamingContinuationModelClient;
import io.github.hellices.agentframework.spi.model.StreamingModelClient;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

public final class AgentEngine extends Agent {

  private final ModelClient modelClient;

  AgentEngine(String id, String name, String description, ModelClient modelClient) {
    super(id, name, description);
    this.modelClient = Objects.requireNonNull(modelClient, "modelClient must not be null");
  }

  public static AgentEngineBuilder builder() {
    return new AgentEngineBuilder();
  }

  @Override
  protected AgentRun runInternal(AgentRunContext context, AgentRunRequest request) {
    ModelClient selectedClient = request.options().resolveModelClient(modelClient);
    ModelRequest modelRequest = toModelRequest(request);
    String responseId = UUID.randomUUID().toString();
    Instant createdAt = Instant.now();
    CompletionStage<AgentResponse> response =
        Objects.requireNonNull(
                runModel(selectedClient, modelRequest, request),
                "model client response stage must not be null")
            .thenApply(
                modelResponse -> {
                  if (request.cancellationSignal().isCancelled()) {
                    throw new CancellationException("run was cancelled");
                  }
                  return ModelResponseMapper.toAgentResponse(
                      id(), responseId, name(), createdAt, modelResponse);
                });
    return new AgentRun(response, request.cancellationSignal());
  }

  @Override
  protected AgentStreamingRun<AgentResponseUpdate> runStreamingInternal(
      AgentRunContext context, AgentRunRequest request) {
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

  private static ModelRequest toModelRequest(AgentRunRequest request) {
    return new ModelRequest(
        request.messages(),
        ModelRequestOptions.empty(),
        request.cancellationSignal(),
        request.attributes());
  }

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
