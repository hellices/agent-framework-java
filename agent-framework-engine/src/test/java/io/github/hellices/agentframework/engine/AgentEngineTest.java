package io.github.hellices.agentframework.engine;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hellices.agentframework.api.agent.Agent;
import io.github.hellices.agentframework.api.agent.AgentBuilder;
import io.github.hellices.agentframework.api.agent.AgentResponse;
import io.github.hellices.agentframework.api.agent.AgentResponseUpdate;
import io.github.hellices.agentframework.api.agent.AgentRunOptions;
import io.github.hellices.agentframework.api.agent.AgentRunRequest;
import io.github.hellices.agentframework.api.agent.AgentSession;
import io.github.hellices.agentframework.api.agent.AgentStreamingRun;
import io.github.hellices.agentframework.api.agent.CancellationSignal;
import io.github.hellices.agentframework.api.context.ContextAttributes;
import io.github.hellices.agentframework.api.context.ContextKey;
import io.github.hellices.agentframework.api.message.Content;
import io.github.hellices.agentframework.api.message.FinishReason;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.Role;
import io.github.hellices.agentframework.api.message.TextContent;
import io.github.hellices.agentframework.api.message.ToolCallContent;
import io.github.hellices.agentframework.api.message.ToolResultContent;
import io.github.hellices.agentframework.api.message.Usage;
import io.github.hellices.agentframework.api.tool.FunctionTool;
import io.github.hellices.agentframework.api.tool.ToolContext;
import io.github.hellices.agentframework.api.tool.ToolHandler;
import io.github.hellices.agentframework.api.tool.ToolResult;
import io.github.hellices.agentframework.api.value.JsonObject;
import io.github.hellices.agentframework.api.value.JsonValues;
import io.github.hellices.agentframework.spi.model.ModelClient;
import io.github.hellices.agentframework.spi.model.ModelRequest;
import io.github.hellices.agentframework.spi.model.ModelResponse;
import io.github.hellices.agentframework.spi.model.ModelResponseUpdate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AgentEngineTest {

  private static AgentBuilder boundBuilder(ModelClient client) {
    return AgentEngine.builder().build().factory().builderWithClient(client);
  }

  private static final ContextKey<String> TRACE_ID =
      ContextKey.of("agent", "traceId", String.class);

  @Test
  void ordinaryRunUsesTheModelPortAndMapsTheResponse() {
    AtomicReference<ModelRequest> capturedRequest = new AtomicReference<>();
    ModelClient client =
        request -> {
          capturedRequest.set(request);
          return EngineModels.of(response("hello"));
        };
    Agent engine =
        boundBuilder(client).id("agent-1").name("assistant").description("test agent").build();

    CancellationSignal signal = new CancellationSignal();
    AgentRunRequest request =
        request(
            Message.normalize("hi"),
            null,
            new AgentRunOptions(),
            signal,
            ContextAttributes.builder().put(TRACE_ID, "1").build());

    var response = engine.run(request).response().toCompletableFuture().join();

    assertThat(capturedRequest.get().messages()).extracting(Message::text).containsExactly("hi");
    assertThat(capturedRequest.get().cancellationSignal()).isSameAs(signal);
    assertThat(capturedRequest.get().metadata().isEmpty()).isTrue();
    assertThat(response.agentId()).isEqualTo("agent-1");
    assertThat(response.authorName()).isEqualTo("assistant");
    assertThat(response.responseId()).isNotBlank();
    assertThat(response.text()).isEqualTo("hello");
    assertThat(response.finishReason()).isEqualTo(FinishReason.STOP);
  }

  @Test
  void toolContextReceivesMergedRunAttributesWithRequestOverrides() {
    ContextKey<String> tenant = ContextKey.of("agent", "tenant", String.class);
    ContextKey<String> region = ContextKey.of("agent", "region", String.class);
    ContextAttributes optionAttributes =
        ContextAttributes.builder().put(tenant, "from-options").put(region, "westus").build();
    ContextAttributes requestAttributes =
        ContextAttributes.builder().put(tenant, "from-request").build();
    AtomicReference<ToolContext> capturedContext = new AtomicReference<>();
    AtomicBoolean firstRequest = new AtomicBoolean(true);
    ModelClient client =
        request -> {
          if (firstRequest.getAndSet(false)) {
            return EngineModels.of(
                modelResponse(
                    List.of(
                        new Message(
                            Role.ASSISTANT,
                            List.of(
                                new ToolCallContent(
                                    "call-1", "weather", jsonObject(Map.of("city", "Seoul")))))),
                    null,
                    FinishReason.TOOL_CALLS,
                    null,
                    Map.of()));
          }
          return EngineModels.of(response("done"));
        };
    FunctionTool weather =
        tool(
            "weather",
            "Gets weather",
            Map.of("type", "object"),
            (arguments, context) -> {
              capturedContext.set(context);
              return completedFuture(ToolResult.success(new TextContent("sunny")));
            });
    Agent engine = boundBuilder(client).tools(weather).build();
    AgentRunRequest request =
        request(
            Message.normalize("weather?"),
            null,
            AgentRunOptions.builder().attributes(optionAttributes).build(),
            new CancellationSignal(),
            requestAttributes);

    engine.run(request).response().toCompletableFuture().join();

    assertThat(capturedContext.get()).isNotNull();
    assertThat(capturedContext.get().attributes().get(tenant)).contains("from-request");
    assertThat(capturedContext.get().attributes().get(region)).contains("westus");
  }

  @Test
  void runLevelFactoryReplacesTheDefaultModelClient() {
    AtomicBoolean originalCalled = new AtomicBoolean();
    ModelClient original =
        request -> {
          originalCalled.set(true);
          return EngineModels.of(response("original"));
        };
    ModelClient replacement = request -> EngineModels.of(response("replacement"));
    Agent engine = boundBuilder(original).build();
    AgentRunOptions options =
        AgentRunOptions.builder().modelClientFactory(ignored -> replacement).build();
    AgentRunRequest request =
        request(
            Message.normalize("hi"),
            null,
            options,
            new CancellationSignal(),
            ContextAttributes.empty());

    var response = engine.run(request).response().toCompletableFuture().join();

    assertThat(originalCalled).isFalse();
    assertThat(response.text()).isEqualTo("replacement");
  }

  @Test
  void modelRequestReceivesMergedRunAttributesWithRequestOverrides() {
    ContextKey<String> tenant = ContextKey.of("agent", "tenant", String.class);
    ContextKey<String> region = ContextKey.of("agent", "region", String.class);
    ContextAttributes optionAttributes =
        ContextAttributes.builder().put(tenant, "from-options").put(region, "westus").build();
    ContextAttributes requestAttributes =
        ContextAttributes.builder().put(tenant, "from-request").build();
    AtomicReference<ModelRequest> capturedRequest = new AtomicReference<>();
    ModelClient client =
        request -> {
          capturedRequest.set(request);
          return EngineModels.of(response("done"));
        };
    Agent engine = boundBuilder(client).build();
    AgentRunRequest request =
        request(
            Message.normalize("hi"),
            null,
            AgentRunOptions.builder().attributes(optionAttributes).build(),
            new CancellationSignal(),
            requestAttributes);

    engine.run(request).response().toCompletableFuture().join();

    assertThat(capturedRequest.get()).isNotNull();
    assertThat(capturedRequest.get().attributes().get(tenant)).contains("from-request");
    assertThat(capturedRequest.get().attributes().get(region)).contains("westus");
  }

  @Test
  void streamingRunUsesStreamingCapabilityAndReconstructsFinalResponse() {
    Agent engine = boundBuilder(new StreamingFakeClient()).build();

    AgentStreamingRun<AgentResponseUpdate> run = engine.runStreaming("hi");

    assertThat(consume(run.updates()))
        .extracting(AgentResponseUpdate::text)
        .containsExactly("hel", "lo");
    assertThat(run.response().toCompletableFuture().join().text()).isEqualTo("hello");
  }

  @Test
  void continuationTokenOnTheRequestReachesTheModelClient() {
    AtomicReference<ModelRequest> capturedRequest = new AtomicReference<>();
    ModelClient client =
        request -> {
          capturedRequest.set(request);
          String token = request.continuationToken();
          return EngineModels.of(response(token == null ? "ordinary" : "resumed"));
        };
    Agent engine = boundBuilder(client).build();
    AgentRunRequest request =
        request(
            List.of(),
            null,
            AgentRunOptions.builder().continuationToken("continuation-1").build(),
            new CancellationSignal(),
            ContextAttributes.empty());

    var response = engine.run(request).response().toCompletableFuture().join();

    assertThat(capturedRequest.get().continuationToken()).isEqualTo("continuation-1");
    assertThat(response.text()).isEqualTo("resumed");
  }

  @Test
  void streamingContinuationTokenOnTheRequestReachesTheModelClient() {
    Agent engine = boundBuilder(new StreamingContinuationFakeClient()).build();
    AgentRunRequest request =
        request(
            List.of(),
            null,
            AgentRunOptions.builder().continuationToken("continuation-1").build(),
            new CancellationSignal(),
            ContextAttributes.empty());

    AgentStreamingRun<AgentResponseUpdate> run = engine.runStreaming(request);

    assertThat(consume(run.updates()))
        .extracting(AgentResponseUpdate::text)
        .containsExactly("resumed");
  }

  @Test
  void cancellingAnInFlightRunCompletesTheResponseExceptionally() {
    CompletableFuture<ModelResponse> pendingResponse = new CompletableFuture<>();
    Agent engine = boundBuilder(request -> EngineModels.fromStage(pendingResponse)).build();

    var run = engine.run("hi");
    run.cancel();
    pendingResponse.complete(response("late"));

    assertThat(run.response().toCompletableFuture()).isCompletedExceptionally();
  }

  @Test
  void cancellingTheSharedSignalCompletesAnInFlightRunExceptionally() {
    CompletableFuture<ModelResponse> pendingResponse = new CompletableFuture<>();
    Agent engine = boundBuilder(request -> EngineModels.fromStage(pendingResponse)).build();
    CancellationSignal signal = new CancellationSignal();
    AgentRunRequest request =
        request(
            Message.normalize("hi"),
            null,
            new AgentRunOptions(),
            signal,
            ContextAttributes.empty());
    var run = engine.run(request);

    signal.cancel();

    assertThat(run.response().toCompletableFuture()).isCompletedExceptionally();
  }

  @Test
  void continuationTokenIsPreservedFromTheModelResponse() {
    ModelClient client =
        request ->
            EngineModels.of(
                modelResponse(
                    List.of(message("done")), null, FinishReason.STOP, "continuation-2", Map.of()));
    Agent engine = boundBuilder(client).build();

    var response = engine.run("hi").response().toCompletableFuture().join();

    assertThat(response.continuationToken()).isEqualTo("continuation-2");
  }

  @Test
  void cancellingAStreamingRunCompletesTheResponseExceptionally() {
    Agent engine =
        boundBuilder(
                (ModelClient)
                    request ->
                        subscriber ->
                            subscriber.onSubscribe(
                                new Flow.Subscription() {
                                  @Override
                                  public void request(long n) {}

                                  @Override
                                  public void cancel() {}
                                }))
            .build();
    AgentStreamingRun<AgentResponseUpdate> run = engine.runStreaming("hi");
    run.updates().subscribe(new NoOpSubscriber());

    run.cancel();

    assertThat(run.response().toCompletableFuture()).isCompletedExceptionally();
  }

  @Test
  void emptyModelStreamFailsBothTheStreamAndTheResponse() {
    Agent engine =
        boundBuilder(
                (ModelClient)
                    request ->
                        subscriber ->
                            subscriber.onSubscribe(
                                new Flow.Subscription() {
                                  @Override
                                  public void request(long n) {
                                    subscriber.onComplete();
                                  }

                                  @Override
                                  public void cancel() {}
                                }))
            .build();
    AgentStreamingRun<AgentResponseUpdate> run = engine.runStreaming("hi");

    // An empty model stream is a failure for every run: the streamed view errors instead of
    // completing empty, exactly as the ordinary view fails.
    assertThatThrownBy(() -> consume(run.updates()))
        .hasRootCauseInstanceOf(IllegalStateException.class)
        .hasRootCauseMessage("model stream completed without any update");
    assertThatThrownBy(() -> run.response().toCompletableFuture().join())
        .hasRootCauseInstanceOf(IllegalStateException.class)
        .hasRootCauseMessage("model stream completed without any update");
  }

  @Test
  void deterministicToolLoopExecutesAndReinjectsToolResults() {
    List<ModelRequest> requests = new ArrayList<>();
    ModelClient client =
        request -> {
          requests.add(request);
          if (requests.size() == 1) {
            return EngineModels.of(
                modelResponse(
                    List.of(
                        new Message(
                            Role.ASSISTANT,
                            List.of(
                                new ToolCallContent(
                                    "call-1", "weather", jsonObject(Map.of("city", "Seoul")))))),
                    null,
                    FinishReason.TOOL_CALLS,
                    null,
                    Map.of()));
          }
          return EngineModels.of(response("It is sunny"));
        };
    FunctionTool weather =
        tool(
            "weather",
            "Gets weather",
            Map.of("type", "object"),
            (arguments, context) ->
                completedFuture(
                    ToolResult.success(new TextContent("sunny:" + arguments.get("city")))));
    Agent engine = boundBuilder(client).tools(weather).build();

    var response = engine.run("weather?").response().toCompletableFuture().join();

    assertThat(requests).hasSize(2);
    assertThat(requests.get(0).tools())
        .extracting(definition -> definition.name())
        .containsExactly("weather");
    assertThat(requests.get(1).messages().get(2).content())
        .singleElement()
        .isInstanceOfSatisfying(
            ToolResultContent.class,
            result -> {
              assertThat(result.callId()).isEqualTo("call-1");
              assertThat(result.content()).extracting(Content::text).containsExactly("sunny:Seoul");
            });
    assertThat(response.text()).endsWith("It is sunny");
    assertThat(response.messages()).hasSize(3);
    assertThat(response.messages().get(0).content()).first().isInstanceOf(ToolCallContent.class);
    assertThat(response.messages().get(1).content()).first().isInstanceOf(ToolResultContent.class);
  }

  @Test
  void builderRejectsDuplicateToolNames() {
    FunctionTool first =
        tool(
            "weather",
            "first",
            Map.of(),
            (arguments, context) -> completedFuture(ToolResult.success(new TextContent("one"))));
    FunctionTool second =
        tool(
            "weather",
            "second",
            Map.of(),
            (arguments, context) -> completedFuture(ToolResult.success(new TextContent("two"))));

    assertThatThrownBy(
            () ->
                boundBuilder(request -> EngineModels.of(response("unused")))
                    .tools(first, second)
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("duplicate tool name: weather");
  }

  @Test
  void unknownToolCallFailsTheRun() {
    Agent engine =
        boundBuilder(
                request ->
                    EngineModels.of(
                        modelResponse(
                            List.of(
                                new Message(
                                    Role.ASSISTANT,
                                    List.of(
                                        new ToolCallContent(
                                            "call-1", "missing", JsonObject.empty())))),
                            null,
                            FinishReason.TOOL_CALLS,
                            null,
                            Map.of())))
            .build();

    assertThatThrownBy(() -> engine.run("call").response().toCompletableFuture().join())
        .hasRootCauseInstanceOf(IllegalStateException.class)
        .hasRootCauseMessage("unknown tool call: missing");
  }

  @Test
  void iterationLimitRunsOneFinalModelCallWithoutTools() {
    List<ModelRequest> requests = new ArrayList<>();
    FunctionTool tool =
        tool(
            "again",
            "again",
            Map.of(),
            (arguments, context) -> completedFuture(ToolResult.success(new TextContent("again"))));
    ModelClient client =
        request -> {
          requests.add(request);
          if (request.tools().isEmpty()) {
            return EngineModels.of(response("finished"));
          }
          return EngineModels.of(
              modelResponse(
                  List.of(
                      new Message(
                          Role.ASSISTANT,
                          List.of(new ToolCallContent("call-1", "again", JsonObject.empty())))),
                  null,
                  FinishReason.TOOL_CALLS,
                  null,
                  Map.of()));
        };
    Agent engine = boundBuilder(client).tools(tool).maxIterations(2).build();

    var response = engine.run("loop").response().toCompletableFuture().join();

    assertThat(requests).hasSize(2);
    assertThat(requests.get(0).tools()).isNotEmpty();
    assertThat(requests.get(1).tools()).isEmpty();
    assertThat(response.text()).contains("finished");
  }

  @Test
  void streamingRunsExecuteToolsAndAssembleTheSameResponseAsAnOrdinaryRun() {
    FunctionTool tool =
        tool(
            "tool",
            "tool",
            Map.of(),
            (arguments, context) -> completedFuture(ToolResult.success(new TextContent("done"))));
    ModelClient client =
        new ModelClient() {
          private final List<ModelRequest> requests = new ArrayList<>();

          private ModelResponse nextResponse(ModelRequest request) {
            requests.add(request);
            if (requests.size() == 1) {
              return modelResponse(
                  List.of(
                      new Message(
                          Role.ASSISTANT,
                          List.of(new ToolCallContent("call-1", "tool", JsonObject.empty())))),
                  null,
                  FinishReason.TOOL_CALLS,
                  null,
                  Map.of());
            }
            return response("finished");
          }

          @Override
          public Flow.Publisher<ModelResponseUpdate> execute(ModelRequest request) {
            ModelResponse response = nextResponse(request);
            return subscriber ->
                subscriber.onSubscribe(
                    new Flow.Subscription() {
                      private boolean completed;

                      @Override
                      public void request(long n) {
                        if (completed || n <= 0) {
                          return;
                        }
                        completed = true;
                        subscriber.onNext(
                            modelResponseUpdate(
                                response.messages(),
                                response.usage(),
                                response.finishReason(),
                                null,
                                Map.of()));
                        subscriber.onComplete();
                      }

                      @Override
                      public void cancel() {
                        completed = true;
                      }
                    });
          }
        };
    Agent engine = boundBuilder(client).tools(tool).build();

    AgentStreamingRun<AgentResponseUpdate> run = engine.runStreaming("hi");

    assertThat(consume(run.updates())).hasSize(3);
    AgentResponse response = run.response().toCompletableFuture().join();
    assertThat(response.messages())
        .extracting(Message::role)
        .containsExactly(Role.ASSISTANT, Role.TOOL, Role.ASSISTANT);
    assertThat(response.text()).isEqualTo("finished");
  }

  @Test
  void continuationWithToolsFailsUntilContinuationLoopSemanticsAreImplemented() {
    FunctionTool tool =
        tool(
            "tool",
            "tool",
            Map.of(),
            (arguments, context) -> completedFuture(ToolResult.success(new TextContent("done"))));
    Agent engine = boundBuilder(new StreamingContinuationFakeClient()).tools(tool).build();

    AgentRunRequest request =
        request(
            List.of(),
            null,
            AgentRunOptions.builder().continuationToken("continuation-1").build(),
            new CancellationSignal(),
            ContextAttributes.empty());

    assertThatThrownBy(() -> engine.run(request))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessage("continuation tool execution is not supported");
  }

  @Test
  void toolsEnabledRunRejectsAContinuationTokenReturnedByTheModel() {
    FunctionTool tool =
        tool(
            "tool",
            "tool",
            Map.of(),
            (arguments, context) -> completedFuture(ToolResult.success(new TextContent("done"))));
    ModelClient client =
        request ->
            EngineModels.of(
                modelResponse(
                    List.of(message("pending")),
                    null,
                    FinishReason.STOP,
                    "continuation-1",
                    Map.of()));
    Agent engine = boundBuilder(client).tools(tool).build();

    assertThatThrownBy(() -> engine.run("hi").response().toCompletableFuture().join())
        .hasRootCauseInstanceOf(UnsupportedOperationException.class)
        .hasRootCauseMessage("model continuation with tool execution is not supported");
  }

  @Test
  void toolLoopAccumulatesUsageAcrossModelCalls() {
    int[] calls = {0};
    FunctionTool tool =
        tool(
            "tool",
            "tool",
            Map.of(),
            (arguments, context) -> completedFuture(ToolResult.success(new TextContent("done"))));
    ModelClient client =
        request -> {
          calls[0]++;
          if (calls[0] == 1) {
            return EngineModels.of(
                modelResponse(
                    List.of(
                        new Message(
                            Role.ASSISTANT,
                            List.of(new ToolCallContent("call-1", "tool", JsonObject.empty())))),
                    new Usage(1, 2, 3, jsonObject(Map.of("cachedTokens", 1L))),
                    FinishReason.TOOL_CALLS,
                    null,
                    Map.of()));
          }
          return EngineModels.of(
              modelResponse(
                  List.of(message("done")),
                  new Usage(4, 5, 9, jsonObject(Map.of("cachedTokens", 2L))),
                  FinishReason.STOP,
                  null,
                  Map.of()));
        };
    Agent engine = boundBuilder(client).tools(tool).build();

    var usage = engine.run("hi").response().toCompletableFuture().join().usage();

    assertThat(usage).isEqualTo(new Usage(5, 7, 12, jsonObject(Map.of("cachedTokens", 3L))));
  }

  private static ModelResponse response(String text) {
    return modelResponse(
        List.of(message(text)), null, FinishReason.STOP, null, Map.of("provider", "fake"));
  }

  private static FunctionTool tool(
      String name, String description, Map<String, Object> inputSchema, ToolHandler handler) {
    return FunctionTool.create(
        name, description, (JsonObject) JsonValues.fromJava(inputSchema), handler);
  }

  private static Message message(String text) {
    return new Message(Role.ASSISTANT, List.of(new TextContent(text)));
  }

  private static AgentRunRequest request(
      List<? extends Message> messages,
      AgentSession session,
      AgentRunOptions options,
      CancellationSignal cancellationSignal,
      ContextAttributes attributes) {
    return AgentRunRequest.builder()
        .messages(messages)
        .session(session)
        .options(options)
        .cancellationSignal(cancellationSignal)
        .attributes(attributes)
        .build();
  }

  private static <T> List<T> consume(Flow.Publisher<T> publisher) {
    List<T> values = new ArrayList<>();
    CompletableFuture<Void> completion = new CompletableFuture<>();
    publisher.subscribe(
        new Flow.Subscriber<>() {
          @Override
          public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
          }

          @Override
          public void onNext(T item) {
            values.add(item);
          }

          @Override
          public void onError(Throwable throwable) {
            completion.completeExceptionally(throwable);
          }

          @Override
          public void onComplete() {
            completion.complete(null);
          }
        });
    completion.join();
    return values;
  }

  private static final class StreamingFakeClient implements ModelClient {
    @Override
    public Flow.Publisher<ModelResponseUpdate> execute(ModelRequest request) {
      return subscriber ->
          subscriber.onSubscribe(
              new Flow.Subscription() {
                private boolean completed;

                @Override
                public void request(long n) {
                  if (completed || n <= 0) {
                    return;
                  }
                  completed = true;
                  subscriber.onNext(
                      modelResponseUpdate(
                          List.of(message("hel")), null, FinishReason.STOP, null, Map.of()));
                  subscriber.onNext(
                      modelResponseUpdate(
                          List.of(message("lo")), null, FinishReason.STOP, null, Map.of()));
                  subscriber.onComplete();
                }

                @Override
                public void cancel() {
                  completed = true;
                }
              });
    }
  }

  private static final class StreamingContinuationFakeClient implements ModelClient {
    @Override
    public Flow.Publisher<ModelResponseUpdate> execute(ModelRequest request) {
      return new SingleUpdatePublisher(
          request.continuationToken() == null ? "ordinary" : "resumed");
    }
  }

  private static final class SingleUpdatePublisher implements Flow.Publisher<ModelResponseUpdate> {
    private final String text;

    private SingleUpdatePublisher(String text) {
      this.text = text;
    }

    @Override
    public void subscribe(Flow.Subscriber<? super ModelResponseUpdate> subscriber) {
      subscriber.onSubscribe(
          new Flow.Subscription() {
            private boolean completed;

            @Override
            public void request(long n) {
              if (completed || n <= 0) {
                return;
              }
              completed = true;
              subscriber.onNext(
                  modelResponseUpdate(
                      List.of(message(text)), null, FinishReason.STOP, null, Map.of()));
              subscriber.onComplete();
            }

            @Override
            public void cancel() {
              completed = true;
            }
          });
    }
  }

  private static final class NoOpSubscriber implements Flow.Subscriber<AgentResponseUpdate> {
    @Override
    public void onSubscribe(Flow.Subscription subscription) {
      subscription.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(AgentResponseUpdate item) {}

    @Override
    public void onError(Throwable throwable) {}

    @Override
    public void onComplete() {}
  }

  private static ModelResponse modelResponse(
      List<Message> messages,
      Usage usage,
      FinishReason finishReason,
      String continuationToken,
      Map<String, Object> metadata) {
    return ModelResponse.builder()
        .messages(messages)
        .usage(usage)
        .finishReason(finishReason)
        .continuationToken(continuationToken)
        .metadata(jsonObject(metadata))
        .build();
  }

  private static ModelResponseUpdate modelResponseUpdate(
      List<Message> messages,
      Usage usage,
      FinishReason finishReason,
      String continuationToken,
      Map<String, Object> metadata) {
    return ModelResponseUpdate.builder()
        .messages(messages)
        .usage(usage)
        .finishReason(finishReason)
        .continuationToken(continuationToken)
        .metadata(jsonObject(metadata))
        .build();
  }

  private static JsonObject jsonObject(Map<String, Object> values) {
    return values.isEmpty() ? JsonObject.empty() : (JsonObject) JsonValues.fromJava(values);
  }
}
