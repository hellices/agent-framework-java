package io.github.hellices.agentframework.engine;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hellices.agentframework.api.agent.AgentResponseUpdate;
import io.github.hellices.agentframework.api.agent.AgentRunOptions;
import io.github.hellices.agentframework.api.agent.AgentRunRequest;
import io.github.hellices.agentframework.api.agent.AgentStreamingRun;
import io.github.hellices.agentframework.api.agent.CancellationSignal;
import io.github.hellices.agentframework.api.message.Content;
import io.github.hellices.agentframework.api.message.FinishReason;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.Role;
import io.github.hellices.agentframework.api.message.TextContent;
import io.github.hellices.agentframework.api.message.ToolCallContent;
import io.github.hellices.agentframework.api.message.ToolResultContent;
import io.github.hellices.agentframework.api.tool.FunctionTool;
import io.github.hellices.agentframework.api.tool.ToolResult;
import io.github.hellices.agentframework.spi.model.ContinuationModelClient;
import io.github.hellices.agentframework.spi.model.ModelClient;
import io.github.hellices.agentframework.spi.model.ModelRequest;
import io.github.hellices.agentframework.spi.model.ModelResponse;
import io.github.hellices.agentframework.spi.model.ModelResponseUpdate;
import io.github.hellices.agentframework.spi.model.StreamingContinuationModelClient;
import io.github.hellices.agentframework.spi.model.StreamingModelClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AgentEngineTest {

  @Test
  void builderRejectsMissingModelClient() {
    assertThatThrownBy(() -> AgentEngine.builder().build())
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("modelClient must be configured");
  }

  @Test
  void ordinaryRunUsesTheModelPortAndMapsTheResponse() {
    AtomicReference<ModelRequest> capturedRequest = new AtomicReference<>();
    ModelClient client =
        request -> {
          capturedRequest.set(request);
          return completedFuture(response("hello"));
        };
    AgentEngine engine =
        AgentEngine.builder()
            .id("agent-1")
            .name("assistant")
            .description("test agent")
            .modelClient(client)
            .build();
    CancellationSignal signal = new CancellationSignal();
    AgentRunRequest request =
        new AgentRunRequest(
            Message.normalize("hi"), null, new AgentRunOptions(), signal, Map.of("traceId", "1"));

    var response = engine.run(request).response().toCompletableFuture().join();

    assertThat(capturedRequest.get().messages()).extracting(Message::text).containsExactly("hi");
    assertThat(capturedRequest.get().cancellationSignal()).isSameAs(signal);
    assertThat(capturedRequest.get().metadata()).containsEntry("traceId", "1");
    assertThat(response.agentId()).isEqualTo("agent-1");
    assertThat(response.authorName()).isEqualTo("assistant");
    assertThat(response.responseId()).isNotBlank();
    assertThat(response.text()).isEqualTo("hello");
    assertThat(response.finishReason()).isEqualTo(FinishReason.STOP);
  }

  @Test
  void runLevelFactoryReplacesTheDefaultModelClient() {
    AtomicBoolean originalCalled = new AtomicBoolean();
    ModelClient original =
        request -> {
          originalCalled.set(true);
          return completedFuture(response("original"));
        };
    ModelClient replacement = request -> completedFuture(response("replacement"));
    AgentEngine engine = AgentEngine.builder().modelClient(original).build();
    AgentRunOptions options =
        AgentRunOptions.builder().modelClientFactory(ignored -> replacement).build();
    AgentRunRequest request =
        new AgentRunRequest(
            Message.normalize("hi"), null, options, new CancellationSignal(), Map.of());

    var response = engine.run(request).response().toCompletableFuture().join();

    assertThat(originalCalled).isFalse();
    assertThat(response.text()).isEqualTo("replacement");
  }

  @Test
  void streamingRunUsesStreamingCapabilityAndReconstructsFinalResponse() {
    AgentEngine engine = AgentEngine.builder().modelClient(new StreamingFakeClient()).build();

    AgentStreamingRun<AgentResponseUpdate> run = engine.runStreaming("hi");

    assertThat(consume(run.updates()))
        .extracting(AgentResponseUpdate::text)
        .containsExactly("hel", "lo");
    assertThat(run.response().toCompletableFuture().join().text()).isEqualTo("hello");
  }

  @Test
  void streamingFailsExplicitlyWhenTheClientLacksTheCapability() {
    ModelClient client = request -> completedFuture(response("unused"));
    AgentEngine engine = AgentEngine.builder().modelClient(client).build();

    assertThatThrownBy(() -> engine.runStreaming("hi"))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessage("model client does not support streaming");
  }

  @Test
  void continuationUsesTheTypedContinuationCapability() {
    AtomicBoolean ordinaryRunCalled = new AtomicBoolean();
    AtomicReference<String> capturedToken = new AtomicReference<>();
    ContinuationModelClient client =
        new ContinuationModelClient() {
          @Override
          public java.util.concurrent.CompletionStage<ModelResponse> run(ModelRequest request) {
            ordinaryRunCalled.set(true);
            return completedFuture(response("ordinary"));
          }

          @Override
          public java.util.concurrent.CompletionStage<ModelResponse> resume(
              ModelRequest request, String continuationToken) {
            capturedToken.set(continuationToken);
            return completedFuture(response("resumed"));
          }
        };
    AgentEngine engine = AgentEngine.builder().modelClient(client).build();
    AgentRunRequest request =
        new AgentRunRequest(
            List.of(),
            null,
            AgentRunOptions.builder().continuationToken("continuation-1").build(),
            new CancellationSignal(),
            Map.of());

    var response = engine.run(request).response().toCompletableFuture().join();

    assertThat(ordinaryRunCalled).isFalse();
    assertThat(capturedToken).hasValue("continuation-1");
    assertThat(response.text()).isEqualTo("resumed");
  }

  @Test
  void continuationFailsExplicitlyWhenTheClientLacksTheCapability() {
    AgentEngine engine =
        AgentEngine.builder().modelClient(request -> completedFuture(response("unused"))).build();
    AgentRunRequest request =
        new AgentRunRequest(
            List.of(),
            null,
            AgentRunOptions.builder().continuationToken("continuation-1").build(),
            new CancellationSignal(),
            Map.of());

    assertThatThrownBy(() -> engine.run(request))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessage("model client does not support continuation");
  }

  @Test
  void streamingContinuationUsesTheCombinedCapability() {
    AgentEngine engine =
        AgentEngine.builder().modelClient(new StreamingContinuationFakeClient()).build();
    AgentRunRequest request =
        new AgentRunRequest(
            List.of(),
            null,
            AgentRunOptions.builder().continuationToken("continuation-1").build(),
            new CancellationSignal(),
            Map.of());

    AgentStreamingRun<AgentResponseUpdate> run = engine.runStreaming(request);

    assertThat(consume(run.updates()))
        .extracting(AgentResponseUpdate::text)
        .containsExactly("resumed");
  }

  @Test
  void cancellingAnInFlightRunCompletesTheResponseExceptionally() {
    CompletableFuture<ModelResponse> pendingResponse = new CompletableFuture<>();
    AgentEngine engine = AgentEngine.builder().modelClient(request -> pendingResponse).build();

    var run = engine.run("hi");
    run.cancel();
    pendingResponse.complete(response("late"));

    assertThat(run.response().toCompletableFuture()).isCompletedExceptionally();
  }

  @Test
  void cancellingTheSharedSignalCompletesAnInFlightRunExceptionally() {
    CompletableFuture<ModelResponse> pendingResponse = new CompletableFuture<>();
    AgentEngine engine = AgentEngine.builder().modelClient(request -> pendingResponse).build();
    CancellationSignal signal = new CancellationSignal();
    AgentRunRequest request =
        new AgentRunRequest(Message.normalize("hi"), null, new AgentRunOptions(), signal, Map.of());
    var run = engine.run(request);

    signal.cancel();

    assertThat(run.response().toCompletableFuture()).isCompletedExceptionally();
  }

  @Test
  void continuationTokenIsPreservedFromTheModelResponse() {
    ModelClient client =
        request ->
            completedFuture(
                new ModelResponse(
                    List.of(message("done")),
                    null,
                    FinishReason.STOP,
                    "continuation-2",
                    Map.of(),
                    null));
    AgentEngine engine = AgentEngine.builder().modelClient(client).build();

    var response = engine.run("hi").response().toCompletableFuture().join();

    assertThat(response.continuationToken()).isEqualTo("continuation-2");
  }

  @Test
  void cancellingAStreamingRunCompletesTheResponseExceptionally() {
    AgentEngine engine =
        AgentEngine.builder()
            .modelClient(
                new StreamingModelClient() {
                  @Override
                  public java.util.concurrent.CompletionStage<ModelResponse> run(
                      ModelRequest request) {
                    return completedFuture(response("unused"));
                  }

                  @Override
                  public Flow.Publisher<ModelResponseUpdate> runStreaming(ModelRequest request) {
                    return subscriber ->
                        subscriber.onSubscribe(
                            new Flow.Subscription() {
                              @Override
                              public void request(long n) {}

                              @Override
                              public void cancel() {}
                            });
                  }
                })
            .build();
    AgentStreamingRun<AgentResponseUpdate> run = engine.runStreaming("hi");
    run.updates().subscribe(new NoOpSubscriber());

    run.cancel();

    assertThat(run.response().toCompletableFuture()).isCompletedExceptionally();
  }

  @Test
  void emptyModelStreamCompletesWithAnEmptyResponse() {
    AgentEngine engine =
        AgentEngine.builder()
            .modelClient(
                new StreamingModelClient() {
                  @Override
                  public java.util.concurrent.CompletionStage<ModelResponse> run(
                      ModelRequest request) {
                    return completedFuture(response(""));
                  }

                  @Override
                  public Flow.Publisher<ModelResponseUpdate> runStreaming(ModelRequest request) {
                    return subscriber ->
                        subscriber.onSubscribe(
                            new Flow.Subscription() {
                              @Override
                              public void request(long n) {
                                subscriber.onComplete();
                              }

                              @Override
                              public void cancel() {}
                            });
                  }
                })
            .build();
    AgentStreamingRun<AgentResponseUpdate> run = engine.runStreaming("hi");

    assertThat(consume(run.updates())).isEmpty();
    assertThat(run.response().toCompletableFuture().join().messages()).isEmpty();
  }

  @Test
  void deterministicToolLoopExecutesAndReinjectsToolResults() {
    List<ModelRequest> requests = new ArrayList<>();
    ModelClient client =
        request -> {
          requests.add(request);
          if (requests.size() == 1) {
            return completedFuture(
                new ModelResponse(
                    List.of(
                        new Message(
                            Role.ASSISTANT,
                            List.of(
                                new ToolCallContent(
                                    "call-1", "weather", Map.of("city", "Seoul"))))),
                    null,
                    FinishReason.TOOL_CALLS,
                    Map.of(),
                    null));
          }
          return completedFuture(response("It is sunny"));
        };
    FunctionTool weather =
        FunctionTool.create(
            "weather",
            "Gets weather",
            Map.of("type", "object"),
            (arguments, context) ->
                completedFuture(
                    ToolResult.success(new TextContent("sunny:" + arguments.get("city")))));
    AgentEngine engine = AgentEngine.builder().modelClient(client).tools(weather).build();

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
        FunctionTool.create(
            "weather",
            "first",
            Map.of(),
            (arguments, context) -> completedFuture(ToolResult.success(new TextContent("one"))));
    FunctionTool second =
        FunctionTool.create(
            "weather",
            "second",
            Map.of(),
            (arguments, context) -> completedFuture(ToolResult.success(new TextContent("two"))));

    assertThatThrownBy(
            () ->
                AgentEngine.builder()
                    .modelClient(request -> completedFuture(response("unused")))
                    .tools(first, second)
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("duplicate tool name: weather");
  }

  @Test
  void unknownToolCallFailsTheRun() {
    AgentEngine engine =
        AgentEngine.builder()
            .modelClient(
                request ->
                    completedFuture(
                        new ModelResponse(
                            List.of(
                                new Message(
                                    Role.ASSISTANT,
                                    List.of(new ToolCallContent("call-1", "missing", Map.of())))),
                            null,
                            FinishReason.TOOL_CALLS,
                            Map.of(),
                            null)))
            .build();

    assertThatThrownBy(() -> engine.run("call").response().toCompletableFuture().join())
        .hasRootCauseInstanceOf(IllegalStateException.class)
        .hasRootCauseMessage("unknown tool call: missing");
  }

  @Test
  void iterationLimitRunsOneFinalModelCallWithoutTools() {
    List<ModelRequest> requests = new ArrayList<>();
    FunctionTool tool =
        FunctionTool.create(
            "again",
            "again",
            Map.of(),
            (arguments, context) -> completedFuture(ToolResult.success(new TextContent("again"))));
    ModelClient client =
        request -> {
          requests.add(request);
          if (request.tools().isEmpty()) {
            return completedFuture(response("finished"));
          }
          return completedFuture(
              new ModelResponse(
                  List.of(
                      new Message(
                          Role.ASSISTANT,
                          List.of(new ToolCallContent("call-1", "again", Map.of())))),
                  null,
                  FinishReason.TOOL_CALLS,
                  Map.of(),
                  null));
        };
    AgentEngine engine =
        AgentEngine.builder().modelClient(client).tools(tool).maxIterations(1).build();

    var response = engine.run("loop").response().toCompletableFuture().join();

    assertThat(requests).hasSize(1);
    assertThat(requests.get(0).tools()).isEmpty();
    assertThat(response.text()).contains("finished");
  }

  @Test
  void streamingWithToolsFailsUntilTheStreamingLoopIsImplemented() {
    FunctionTool tool =
        FunctionTool.create(
            "tool",
            "tool",
            Map.of(),
            (arguments, context) -> completedFuture(ToolResult.success(new TextContent("done"))));
    AgentEngine engine =
        AgentEngine.builder().modelClient(new StreamingFakeClient()).tools(tool).build();

    assertThatThrownBy(() -> engine.runStreaming("hi"))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessage("streaming tool execution is not supported");
  }

  @Test
  void continuationWithToolsFailsUntilContinuationLoopSemanticsAreImplemented() {
    FunctionTool tool =
        FunctionTool.create(
            "tool",
            "tool",
            Map.of(),
            (arguments, context) -> completedFuture(ToolResult.success(new TextContent("done"))));
    AgentEngine engine =
        AgentEngine.builder()
            .modelClient(new StreamingContinuationFakeClient())
            .tools(tool)
            .build();
    AgentRunRequest request =
        new AgentRunRequest(
            List.of(),
            null,
            AgentRunOptions.builder().continuationToken("continuation-1").build(),
            new CancellationSignal(),
            Map.of());

    assertThatThrownBy(() -> engine.run(request))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessage("continuation tool execution is not supported");
  }

  private static ModelResponse response(String text) {
    return new ModelResponse(
        List.of(message(text)), null, FinishReason.STOP, Map.of("provider", "fake"), null);
  }

  private static Message message(String text) {
    return new Message(Role.ASSISTANT, List.of(new TextContent(text)));
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

  private static final class StreamingFakeClient implements StreamingModelClient {
    @Override
    public java.util.concurrent.CompletionStage<ModelResponse> run(ModelRequest request) {
      return completedFuture(response("hello"));
    }

    @Override
    public Flow.Publisher<ModelResponseUpdate> runStreaming(ModelRequest request) {
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
                      new ModelResponseUpdate(
                          List.of(message("hel")), null, FinishReason.STOP, Map.of(), null));
                  subscriber.onNext(
                      new ModelResponseUpdate(
                          List.of(message("lo")), null, FinishReason.STOP, Map.of(), null));
                  subscriber.onComplete();
                }

                @Override
                public void cancel() {
                  completed = true;
                }
              });
    }
  }

  private static final class StreamingContinuationFakeClient
      implements StreamingContinuationModelClient {
    @Override
    public java.util.concurrent.CompletionStage<ModelResponse> run(ModelRequest request) {
      return completedFuture(response("ordinary"));
    }

    @Override
    public Flow.Publisher<ModelResponseUpdate> runStreaming(ModelRequest request) {
      return new SingleUpdatePublisher("ordinary");
    }

    @Override
    public java.util.concurrent.CompletionStage<ModelResponse> resume(
        ModelRequest request, String continuationToken) {
      return completedFuture(response("resumed"));
    }

    @Override
    public Flow.Publisher<ModelResponseUpdate> resumeStreaming(
        ModelRequest request, String continuationToken) {
      return new SingleUpdatePublisher("resumed");
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
                  new ModelResponseUpdate(
                      List.of(message(text)), null, FinishReason.STOP, Map.of(), null));
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
}
