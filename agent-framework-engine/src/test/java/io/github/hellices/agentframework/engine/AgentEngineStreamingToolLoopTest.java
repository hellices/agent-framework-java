package io.github.hellices.agentframework.engine;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hellices.agentframework.api.agent.AgentResponse;
import io.github.hellices.agentframework.api.agent.AgentResponseUpdate;
import io.github.hellices.agentframework.api.agent.AgentRunOptions;
import io.github.hellices.agentframework.api.agent.AgentRunRequest;
import io.github.hellices.agentframework.api.agent.AgentSession;
import io.github.hellices.agentframework.api.agent.AgentStreamingRun;
import io.github.hellices.agentframework.api.agent.CancellationSignal;
import io.github.hellices.agentframework.api.context.ContextAttributes;
import io.github.hellices.agentframework.api.message.Content;
import io.github.hellices.agentframework.api.message.FinishReason;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.Role;
import io.github.hellices.agentframework.api.message.TextContent;
import io.github.hellices.agentframework.api.message.ToolCallContent;
import io.github.hellices.agentframework.api.message.ToolResultContent;
import io.github.hellices.agentframework.api.message.Usage;
import io.github.hellices.agentframework.api.session.SessionContext;
import io.github.hellices.agentframework.api.session.SessionState;
import io.github.hellices.agentframework.api.session.SessionStateKey;
import io.github.hellices.agentframework.api.session.SessionStateValues;
import io.github.hellices.agentframework.api.tool.FunctionTool;
import io.github.hellices.agentframework.api.tool.ToolHandler;
import io.github.hellices.agentframework.api.tool.ToolResult;
import io.github.hellices.agentframework.api.value.JsonObject;
import io.github.hellices.agentframework.api.value.JsonValue;
import io.github.hellices.agentframework.api.value.JsonValues;
import io.github.hellices.agentframework.engine.session.InMemorySessionStore;
import io.github.hellices.agentframework.engine.session.JacksonSessionSnapshotCodec;
import io.github.hellices.agentframework.spi.model.ModelClient;
import io.github.hellices.agentframework.spi.model.ModelRequest;
import io.github.hellices.agentframework.spi.model.ModelResponse;
import io.github.hellices.agentframework.spi.model.ModelResponseUpdate;
import io.github.hellices.agentframework.spi.model.StreamingModelClient;
import io.github.hellices.agentframework.spi.session.ContextProvider;
import io.github.hellices.agentframework.spi.session.ProviderSessionState;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Streaming function tool loop (TOOL-015): the streaming path runs the same iteration, budget, and
 * result semantics as the ordinary path, forwards model updates live, and honours downstream demand
 * and cancellation across the model, tool, and tool-result phases.
 *
 * <p>Response parity is asserted as content and order — the roles, text, tool calls and tool
 * results a response reports, its usage, finish reason and terminal metadata — not as the message
 * objects themselves: a streamed response is reconstructed by {@code AgentResponse.fromUpdates},
 * which coalesces consecutive same-role chunks while no model port reports message ids. {@link
 * #consecutiveAssistantChunksAreReconstructedAsOneMessage()} characterises that difference.
 */
class AgentEngineStreamingToolLoopTest {

  private static final Map<String, Object> SEOUL = Map.of("city", "Seoul");

  @Test
  void streamingToolLoopMatchesTheOrdinaryRunFinalResponse() {
    List<ModelRequest> ordinaryRequests = new ArrayList<>();
    ModelClient ordinaryClient =
        request -> {
          ordinaryRequests.add(request);
          if (ordinaryRequests.size() == 1) {
            return completedFuture(
                modelResponse(
                    List.of(toolCall("call-1", "weather", SEOUL)),
                    new Usage(1, 2, 3, jsonObject(Map.of("cachedTokens", 1L))),
                    FinishReason.TOOL_CALLS,
                    Map.of()));
          }
          return completedFuture(
              modelResponse(
                  List.of(assistant("It is sunny")),
                  new Usage(4, 5, 9, jsonObject(Map.of("cachedTokens", 2L))),
                  FinishReason.STOP,
                  Map.of()));
        };
    List<ModelRequest> streamingRequests = new ArrayList<>();
    StreamingModelClient streamingClient =
        scripted(
            streamingRequests,
            List.of(
                List.of(
                    update(
                        toolCall("call-1", "weather", SEOUL),
                        new Usage(1, 2, 3, jsonObject(Map.of("cachedTokens", 1L))),
                        FinishReason.TOOL_CALLS)),
                List.of(
                    update(
                        assistant("It is sunny"),
                        new Usage(4, 5, 9, jsonObject(Map.of("cachedTokens", 2L))),
                        FinishReason.STOP))));

    AgentResponse ordinary =
        engine(ordinaryClient, weatherTool())
            .run("weather?")
            .response()
            .toCompletableFuture()
            .join();
    AgentStreamingRun<AgentResponseUpdate> run =
        engine(streamingClient, weatherTool()).runStreaming("weather?");
    RecordingSubscriber subscriber = subscribe(run.updates(), Long.MAX_VALUE);
    subscriber.completion.join();
    AgentResponse streaming = run.response().toCompletableFuture().join();

    assertThat(describe(streaming.messages())).isEqualTo(describe(ordinary.messages()));
    assertThat(streaming.usage()).isEqualTo(ordinary.usage());
    assertThat(streaming.finishReason()).isEqualTo(ordinary.finishReason());
    assertThat(describe(streamingRequests.get(1).messages()))
        .isEqualTo(describe(ordinaryRequests.get(1).messages()));
    assertThat(streamingRequests.get(1).tools()).isEqualTo(streamingRequests.get(0).tools());
    assertThat(subscriber.values)
        .anySatisfy(
            update ->
                assertThat(update.messages())
                    .anySatisfy(
                        message ->
                            assertThat(message.content())
                                .anyMatch(ToolResultContent.class::isInstance)));
    assertThat(subscriber.values)
        .allSatisfy(
            update -> {
              assertThat(update.agentId()).isEqualTo(streaming.agentId());
              assertThat(update.responseId()).isEqualTo(streaming.responseId());
              assertThat(update.createdAt()).isEqualTo(streaming.createdAt());
            });
    assertThat(subscriber.terminals).hasValue(1);
  }

  @Test
  void theFinalResponseCarriesOnlyTheTerminalIterationsMetadata() {
    Map<String, Object> firstMetadata = Map.of("iteration0", "secret-a", "shared", "first");
    Map<String, Object> terminalMetadata = Map.of("iteration1", "b", "shared", "second");

    AgentResponse ordinary =
        engine(twoIterations(firstMetadata, terminalMetadata), weatherTool())
            .run("weather?")
            .response()
            .toCompletableFuture()
            .join();
    AgentResponse streaming =
        streamedResponse(twoStreamedIterations(firstMetadata, terminalMetadata));

    assertThat(ordinary.additionalProperties()).isEqualTo(jsonObject(terminalMetadata));
    assertThat(streaming.additionalProperties()).isEqualTo(ordinary.additionalProperties());
    assertThat(streaming.additionalProperties().values()).doesNotContainKey("iteration0");
  }

  @Test
  void anEmptyTerminalIterationMetadataLeavesNoEarlierMetadataBehind() {
    Map<String, Object> firstMetadata = Map.of("iteration0", "secret-a");

    AgentResponse ordinary =
        engine(twoIterations(firstMetadata, Map.of()), weatherTool())
            .run("weather?")
            .response()
            .toCompletableFuture()
            .join();
    AgentResponse streaming = streamedResponse(twoStreamedIterations(firstMetadata, Map.of()));

    assertThat(ordinary.additionalProperties()).isEqualTo(JsonObject.empty());
    assertThat(streaming.additionalProperties()).isEqualTo(ordinary.additionalProperties());
  }

  @Test
  void modelMetadataReachesTheSubscriberOnceAsTheTerminalMetadataUpdate() {
    Map<String, Object> firstMetadata = Map.of("iteration0", "secret-a");
    Map<String, Object> terminalMetadata = Map.of("iteration1", "b");
    AgentStreamingRun<AgentResponseUpdate> run =
        engine(twoStreamedIterations(firstMetadata, terminalMetadata), weatherTool())
            .runStreaming("weather?");

    RecordingSubscriber subscriber = subscribe(run.updates(), Long.MAX_VALUE);
    subscriber.completion.join();

    assertThat(subscriber.values).hasSize(4);
    assertThat(subscriber.values.subList(0, 3))
        .allSatisfy(
            update -> assertThat(update.additionalProperties()).isEqualTo(JsonObject.empty()));
    AgentResponseUpdate terminal = subscriber.values.get(3);
    assertThat(terminal.additionalProperties()).isEqualTo(jsonObject(terminalMetadata));
    assertThat(terminal.messages()).isEmpty();
    assertThat(terminal.finishReason()).isNull();
    assertThat(terminal.usage()).isNull();
    assertThat(subscriber.terminals).hasValue(1);
  }

  @Test
  void aWholeToolCallEmittedAfterATextUpdateIsExecutedOnce() {
    List<Map<String, Object>> observedArguments = new ArrayList<>();
    FunctionTool weather = recordingWeatherTool(observedArguments);
    StreamingModelClient client =
        scripted(
            new ArrayList<>(),
            List.of(
                List.of(
                    update(assistant("checking "), null, FinishReason.TOOL_CALLS),
                    update(toolCall("call-1", "weather", SEOUL), null, FinishReason.TOOL_CALLS)),
                List.of(update(assistant("It is sunny"), null, FinishReason.STOP))));

    AgentStreamingRun<AgentResponseUpdate> run = engine(client, weather).runStreaming("weather?");
    RecordingSubscriber subscriber = subscribe(run.updates(), Long.MAX_VALUE);
    subscriber.completion.join();
    AgentResponse response = run.response().toCompletableFuture().join();

    assertThat(observedArguments).containsExactly(SEOUL);
    assertThat(describe(response.messages()))
        .containsExactly(
            "assistant|checking |call:call-1:weather:{city=Seoul}",
            "tool||result:call-1:weather:false:[sunny]",
            "assistant|It is sunny");
  }

  @Test
  void aToolCallSplitAcrossTwoUpdatesIsExecutedOnceWithItsMergedArguments() {
    List<Map<String, Object>> observedArguments = new ArrayList<>();
    FunctionTool weather = recordingWeatherTool(observedArguments);
    StreamingModelClient client =
        scripted(
            new ArrayList<>(),
            List.of(
                List.of(
                    update(
                        toolCall("call-1", "weather", Map.of("city", "Seo", "unit", "c")),
                        null,
                        FinishReason.TOOL_CALLS),
                    update(
                        toolCall("call-1", "weather", Map.of("city", "Seoul")),
                        null,
                        FinishReason.TOOL_CALLS)),
                List.of(update(assistant("It is sunny"), null, FinishReason.STOP))));

    AgentStreamingRun<AgentResponseUpdate> run = engine(client, weather).runStreaming("weather?");
    RecordingSubscriber subscriber = subscribe(run.updates(), Long.MAX_VALUE);
    subscriber.completion.join();
    AgentResponse response = run.response().toCompletableFuture().join();

    assertThat(observedArguments).containsExactly(Map.of("city", "Seoul", "unit", "c"));
    assertThat(toolResults(response)).containsExactly("call-1:weather");
  }

  @Test
  void theNextRequestEchoesTheMergedCallOfSplitFragmentsExactlyOnce() {
    List<ModelRequest> requests = new ArrayList<>();
    StreamingModelClient client =
        scripted(
            requests,
            List.of(
                List.of(
                    update(
                        toolCall("call-1", "weather", Map.of("city", "Seo", "unit", "c")),
                        null,
                        FinishReason.TOOL_CALLS),
                    update(
                        toolCall("call-1", "weather", Map.of("city", "Seoul")),
                        null,
                        FinishReason.TOOL_CALLS)),
                List.of(update(assistant("It is sunny"), null, FinishReason.STOP))));

    AgentStreamingRun<AgentResponseUpdate> run =
        engine(client, weatherTool()).runStreaming("weather?");
    subscribe(run.updates(), Long.MAX_VALUE).completion.join();

    assertThat(requestToolCalls(requests.get(1)))
        .singleElement()
        .satisfies(
            call -> {
              assertThat(call.callId()).isEqualTo("call-1");
              assertThat(call.name()).isEqualTo("weather");
              assertThat(call.arguments())
                  .isEqualTo(jsonObject(Map.of("city", "Seoul", "unit", "c")));
            });
    assertThat(requestToolResults(requests.get(1)))
        .singleElement()
        .satisfies(
            result -> {
              assertThat(result.callId()).isEqualTo("call-1");
              assertThat(result.name()).isEqualTo("weather");
            });
  }

  @Test
  void toolCallsSplitAcrossUpdatesKeepTheirFirstSeenOrder() {
    List<String> invocations = new ArrayList<>();
    FunctionTool first = recordingTool("first", invocations);
    FunctionTool second = recordingTool("second", invocations);
    StreamingModelClient client =
        scripted(
            new ArrayList<>(),
            List.of(
                List.of(
                    update(
                        toolCall("call-1", "first", Map.of("a", 1)), null, FinishReason.TOOL_CALLS),
                    update(
                        toolCall("call-2", "second", Map.of("b", 1)),
                        null,
                        FinishReason.TOOL_CALLS),
                    update(
                        toolCall("call-1", "first", Map.of("a", 2)),
                        null,
                        FinishReason.TOOL_CALLS)),
                List.of(update(assistant("done"), null, FinishReason.STOP))));

    AgentStreamingRun<AgentResponseUpdate> run =
        engine(client, first, second).runStreaming("call both");
    RecordingSubscriber subscriber = subscribe(run.updates(), Long.MAX_VALUE);
    subscriber.completion.join();
    AgentResponse response = run.response().toCompletableFuture().join();

    assertThat(invocations).containsExactly("first:{a=2}", "second:{b=1}");
    assertThat(toolResults(response)).containsExactly("call-1:first", "call-2:second");
  }

  @Test
  void aCallIdReportedWithTwoToolNamesFailsTheStream() {
    List<String> invocations = new ArrayList<>();
    FunctionTool first = recordingTool("first", invocations);
    FunctionTool second = recordingTool("second", invocations);
    StreamingModelClient client =
        scripted(
            new ArrayList<>(),
            List.of(
                List.of(
                    update(toolCall("call-1", "first", Map.of()), null, FinishReason.TOOL_CALLS),
                    update(toolCall("call-1", "second", Map.of()), null, FinishReason.TOOL_CALLS)),
                List.of(update(assistant("done"), null, FinishReason.STOP))));

    AgentStreamingRun<AgentResponseUpdate> run =
        engine(client, first, second).runStreaming("call both");
    RecordingSubscriber subscriber = subscribe(run.updates(), Long.MAX_VALUE);

    assertThat(subscriber.terminals).hasValue(1);
    assertThat(subscriber.failure.get())
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("tool call call-1 was reported as both 'first' and 'second'");
    assertThat(invocations).isEmpty();
  }

  @Test
  void multipleToolCallsAreExecutedAndReportedInCallOrder() {
    List<String> invocations = new ArrayList<>();
    CompletableFuture<ToolResult> firstResult = new CompletableFuture<>();
    FunctionTool first =
        tool(
            "first",
            "first",
            Map.of(),
            (arguments, context) -> {
              invocations.add("first");
              return firstResult;
            });
    FunctionTool second =
        tool(
            "second",
            "second",
            Map.of(),
            (arguments, context) -> {
              invocations.add("second");
              return completedFuture(ToolResult.success(new TextContent("two")));
            });
    StreamingModelClient client =
        scripted(
            new ArrayList<>(),
            List.of(
                List.of(
                    update(
                        new Message(
                            Role.ASSISTANT,
                            List.of(
                                new ToolCallContent("call-1", "first", JsonObject.empty()),
                                new ToolCallContent("call-2", "second", JsonObject.empty()))),
                        null,
                        FinishReason.TOOL_CALLS)),
                List.of(update(assistant("done"), null, FinishReason.STOP))));

    AgentStreamingRun<AgentResponseUpdate> run =
        engine(client, first, second).runStreaming("call both");
    RecordingSubscriber subscriber = subscribe(run.updates(), Long.MAX_VALUE);

    assertThat(invocations).containsExactly("first");
    assertThat(subscriber.completion).isNotDone();

    firstResult.complete(ToolResult.success(new TextContent("one")));
    subscriber.completion.join();
    AgentResponse response = run.response().toCompletableFuture().join();

    assertThat(invocations).containsExactly("first", "second");
    assertThat(describe(response.messages()))
        .containsExactly(
            "assistant||call:call-1:first:{}|call:call-2:second:{}",
            "tool||result:call-1:first:false:[one]|result:call-2:second:false:[two]",
            "assistant|done");
  }

  @Test
  void aFailedToolResultIsStreamedAsAnErrorResultAndTheLoopContinues() {
    FunctionTool broken =
        tool(
            "broken",
            "broken",
            Map.of(),
            (arguments, context) ->
                completedFuture(ToolResult.failure(new TextContent("tool said no"))));
    StreamingModelClient client =
        scripted(
            new ArrayList<>(),
            List.of(
                List.of(
                    update(toolCall("call-1", "broken", Map.of()), null, FinishReason.TOOL_CALLS)),
                List.of(update(assistant("recovered"), null, FinishReason.STOP))));

    AgentStreamingRun<AgentResponseUpdate> run = engine(client, broken).runStreaming("hi");
    RecordingSubscriber subscriber = subscribe(run.updates(), Long.MAX_VALUE);
    subscriber.completion.join();
    AgentResponse response = run.response().toCompletableFuture().join();

    assertThat(describe(response.messages()))
        .containsExactly(
            "assistant||call:call-1:broken:{}",
            "tool||result:call-1:broken:true:[tool said no]",
            "assistant|recovered");
  }

  @Test
  void aFailingToolStageFailsTheStreamExactlyOnce() {
    FunctionTool exploding =
        tool(
            "exploding",
            "exploding",
            Map.of(),
            (arguments, context) ->
                CompletableFuture.failedFuture(new IllegalStateException("tool failure")));
    StreamingModelClient client =
        scripted(
            new ArrayList<>(),
            List.of(
                List.of(
                    update(
                        toolCall("call-1", "exploding", Map.of()), null, FinishReason.TOOL_CALLS)),
                List.of(update(assistant("unreachable"), null, FinishReason.STOP))));

    AgentStreamingRun<AgentResponseUpdate> run = engine(client, exploding).runStreaming("hi");
    RecordingSubscriber subscriber = subscribe(run.updates(), Long.MAX_VALUE);

    assertThat(subscriber.terminals).hasValue(1);
    assertThat(subscriber.failure.get())
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("tool failure");
    assertThatThrownBy(() -> run.response().toCompletableFuture().join())
        .hasRootCauseInstanceOf(IllegalStateException.class)
        .hasRootCauseMessage("tool failure");
  }

  @Test
  void aToolHandlerReturningNoStageFailsTheStream() {
    List<CompletionStage<ToolResult>> stages = Collections.singletonList(null);
    FunctionTool silent = tool("silent", "silent", Map.of(), (arguments, context) -> stages.get(0));
    StreamingModelClient client =
        scripted(
            new ArrayList<>(),
            List.of(
                List.of(
                    update(toolCall("call-1", "silent", Map.of()), null, FinishReason.TOOL_CALLS)),
                List.of(update(assistant("unreachable"), null, FinishReason.STOP))));

    AgentStreamingRun<AgentResponseUpdate> run = engine(client, silent).runStreaming("hi");
    RecordingSubscriber subscriber = subscribe(run.updates(), Long.MAX_VALUE);

    assertThat(subscriber.terminals).hasValue(1);
    assertThat(subscriber.failure.get())
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("tool handler must not return null");
  }

  @Test
  void anUnknownToolCallFailsTheStream() {
    StreamingModelClient client =
        scripted(
            new ArrayList<>(),
            List.of(
                List.of(
                    update(
                        toolCall("call-1", "missing", Map.of()), null, FinishReason.TOOL_CALLS))));

    AgentStreamingRun<AgentResponseUpdate> run = engine(client, weatherTool()).runStreaming("hi");
    RecordingSubscriber subscriber = subscribe(run.updates(), Long.MAX_VALUE);

    assertThat(subscriber.terminals).hasValue(1);
    assertThat(subscriber.failure.get())
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("unknown tool call: missing");
  }

  @Test
  void anEmptyStreamingIterationFailsExplicitly() {
    StreamingModelClient client =
        streaming(
            request -> subscriber -> subscriber.onSubscribe(new EmptySubscription(subscriber)));

    AgentStreamingRun<AgentResponseUpdate> run = engine(client, weatherTool()).runStreaming("hi");
    RecordingSubscriber subscriber = subscribe(run.updates(), Long.MAX_VALUE);

    assertThat(subscriber.terminals).hasValue(1);
    assertThat(subscriber.failure.get())
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("model stream completed without any update");
  }

  @Test
  void aNullModelUpdateFailsTheStream() {
    List<ModelResponseUpdate> updates = Collections.singletonList(null);
    StreamingModelClient client =
        streaming(
            request ->
                subscriber ->
                    subscriber.onSubscribe(
                        new Flow.Subscription() {
                          private boolean emitted;

                          @Override
                          public void request(long n) {
                            if (emitted) {
                              return;
                            }
                            emitted = true;
                            subscriber.onNext(updates.get(0));
                          }

                          @Override
                          public void cancel() {
                            emitted = true;
                          }
                        }));

    AgentStreamingRun<AgentResponseUpdate> run = engine(client, weatherTool()).runStreaming("hi");
    RecordingSubscriber subscriber = subscribe(run.updates(), Long.MAX_VALUE);

    assertThat(subscriber.terminals).hasValue(1);
    assertThat(subscriber.failure.get())
        .isInstanceOf(NullPointerException.class)
        .hasMessage("model response update must not be null");
  }

  @Test
  void aNullPublisherForALaterIterationFailsTheStream() {
    AtomicInteger calls = new AtomicInteger();
    StreamingModelClient client =
        streaming(
            request -> {
              if (calls.getAndIncrement() == 0) {
                return publisher(
                    List.of(
                        update(
                            toolCall("call-1", "weather", SEOUL), null, FinishReason.TOOL_CALLS)));
              }
              return null;
            });

    AgentStreamingRun<AgentResponseUpdate> run = engine(client, weatherTool()).runStreaming("hi");
    RecordingSubscriber subscriber = subscribe(run.updates(), Long.MAX_VALUE);

    assertThat(subscriber.terminals).hasValue(1);
    assertThat(subscriber.failure.get())
        .isInstanceOf(NullPointerException.class)
        .hasMessage("model client update publisher must not be null");
  }

  @Test
  void aFirstIterationPublisherThatThrowsFromSubscribeFailsTheStream() {
    StreamingModelClient client =
        streaming(
            request ->
                subscriber -> {
                  throw new IllegalStateException("subscribe rejected");
                });

    AgentStreamingRun<AgentResponseUpdate> run = engine(client, weatherTool()).runStreaming("hi");
    RecordingSubscriber subscriber = subscribe(run.updates(), Long.MAX_VALUE);

    assertThat(subscriber.terminals).hasValue(1);
    assertThat(subscriber.failure.get())
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("subscribe rejected");
    assertThatThrownBy(() -> run.response().toCompletableFuture().join())
        .hasRootCauseInstanceOf(IllegalStateException.class)
        .hasRootCauseMessage("subscribe rejected");
  }

  @Test
  void aLaterIterationPublisherThatThrowsFromSubscribeFailsTheStream() {
    AtomicInteger calls = new AtomicInteger();
    StreamingModelClient client =
        streaming(
            request -> {
              if (calls.getAndIncrement() == 0) {
                return publisher(
                    List.of(
                        update(
                            toolCall("call-1", "weather", SEOUL), null, FinishReason.TOOL_CALLS)));
              }
              return subscriber -> {
                throw new IllegalStateException("subscribe rejected");
              };
            });

    AgentStreamingRun<AgentResponseUpdate> run = engine(client, weatherTool()).runStreaming("hi");
    RecordingSubscriber subscriber = subscribe(run.updates(), Long.MAX_VALUE);

    assertThat(subscriber.terminals).hasValue(1);
    assertThat(subscriber.failure.get())
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("subscribe rejected");
    assertThatThrownBy(() -> run.response().toCompletableFuture().join())
        .hasRootCauseInstanceOf(IllegalStateException.class)
        .hasRootCauseMessage("subscribe rejected");
    assertThat(calls).hasValue(2);
  }

  @Test
  void aSubscriberThatThrowsFromOnNextLeavesTheStreamDrainable() {
    ManualStreams streams = new ManualStreams();
    ThrowingOnceSubscriber subscriber = new ThrowingOnceSubscriber();
    AgentStreamingRun<AgentResponseUpdate> run =
        engine(streams.client(), weatherTool()).runStreaming("hi");
    run.updates().subscribe(subscriber);

    assertThatThrownBy(
            () -> streams.stream(0).emit(update(assistant("first"), null, FinishReason.STOP)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("subscriber rejected the update");
    streams.stream(0).emit(update(assistant("second"), null, FinishReason.STOP));
    streams.stream(0).complete();

    assertThat(subscriber.texts).containsExactly("second");
    assertThat(subscriber.terminals).hasValue(1);
  }

  @Test
  void aModelContinuationTokenFailsAToolsEnabledStreamingRun() {
    StreamingModelClient client =
        streaming(
            request ->
                publisher(
                    List.of(
                        ModelResponseUpdate.builder()
                            .messages(List.of(assistant("pending")))
                            .finishReason(FinishReason.STOP)
                            .continuationToken("continuation-1")
                            .metadata(JsonObject.empty())
                            .build())));

    AgentStreamingRun<AgentResponseUpdate> run = engine(client, weatherTool()).runStreaming("hi");
    RecordingSubscriber subscriber = subscribe(run.updates(), Long.MAX_VALUE);

    assertThat(subscriber.terminals).hasValue(1);
    assertThat(subscriber.failure.get())
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessage("model continuation with tool execution is not supported");
  }

  @Test
  void aStreamingRunWithToolsAndAContinuationTokenIsRejectedSynchronously() {
    StreamingModelClient client =
        streaming(
            request -> publisher(List.of(update(assistant("unused"), null, FinishReason.STOP))));
    AgentEngine engine = engine(client, weatherTool());
    AgentRunRequest request =
        request(
            List.of(),
            null,
            AgentRunOptions.builder().continuationToken("continuation-1").build(),
            new CancellationSignal(),
            ContextAttributes.empty());

    assertThatThrownBy(() -> engine.runStreaming(request))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessage("continuation tool execution is not supported");
  }

  @Test
  void theIterationBudgetDisablesToolsOnTheLastPermittedRequestExactlyAsOrdinaryRunsDo() {
    FunctionTool again =
        tool(
            "again",
            "again",
            Map.of(),
            (arguments, context) -> completedFuture(ToolResult.success(new TextContent("again"))));
    List<ModelRequest> ordinaryRequests = new ArrayList<>();
    ModelClient ordinaryClient =
        request -> {
          ordinaryRequests.add(request);
          if (request.tools().isEmpty()) {
            return completedFuture(
                modelResponse(List.of(assistant("finished")), null, FinishReason.STOP, Map.of()));
          }
          return completedFuture(
              modelResponse(
                  List.of(toolCall("call-1", "again", Map.of())),
                  null,
                  FinishReason.TOOL_CALLS,
                  Map.of()));
        };
    List<ModelRequest> streamingRequests = new ArrayList<>();
    StreamingModelClient streamingClient =
        streaming(
            request -> {
              streamingRequests.add(request);
              if (request.tools().isEmpty()) {
                return publisher(List.of(update(assistant("finished"), null, FinishReason.STOP)));
              }
              return publisher(
                  List.of(
                      update(
                          toolCall("call-1", "again", Map.of()), null, FinishReason.TOOL_CALLS)));
            });

    AgentResponse ordinary =
        AgentEngine.builder()
            .modelClient(ordinaryClient)
            .tools(again)
            .maxIterations(2)
            .build()
            .run("loop")
            .response()
            .toCompletableFuture()
            .join();
    AgentStreamingRun<AgentResponseUpdate> run =
        AgentEngine.builder()
            .modelClient(streamingClient)
            .tools(again)
            .maxIterations(2)
            .build()
            .runStreaming("loop");
    RecordingSubscriber subscriber = subscribe(run.updates(), Long.MAX_VALUE);
    subscriber.completion.join();
    AgentResponse streaming = run.response().toCompletableFuture().join();

    assertThat(streamingRequests).hasSameSizeAs(ordinaryRequests);
    assertThat(streamingRequests.get(0).tools()).isNotEmpty();
    assertThat(streamingRequests.get(1).tools()).isEmpty();
    assertThat(describe(streaming.messages())).isEqualTo(describe(ordinary.messages()));
  }

  @Test
  void toolCallsReturnedAfterToolsWereDisabledFailBothPaths() {
    FunctionTool tool =
        tool(
            "tool",
            "tool",
            Map.of(),
            (arguments, context) -> completedFuture(ToolResult.success(new TextContent("done"))));
    ModelClient ordinaryClient =
        request ->
            completedFuture(
                modelResponse(
                    List.of(toolCall("call-1", "tool", Map.of())),
                    null,
                    FinishReason.TOOL_CALLS,
                    Map.of()));
    StreamingModelClient streamingClient =
        streaming(
            request ->
                publisher(
                    List.of(
                        update(
                            toolCall("call-1", "tool", Map.of()), null, FinishReason.TOOL_CALLS))));

    assertThatThrownBy(
            () ->
                AgentEngine.builder()
                    .modelClient(ordinaryClient)
                    .tools(tool)
                    .maxIterations(1)
                    .build()
                    .run("hi")
                    .response()
                    .toCompletableFuture()
                    .join())
        .hasRootCauseInstanceOf(IllegalStateException.class)
        .hasRootCauseMessage("model returned tool calls after tools were disabled");
    AgentStreamingRun<AgentResponseUpdate> run =
        AgentEngine.builder()
            .modelClient(streamingClient)
            .tools(tool)
            .maxIterations(1)
            .build()
            .runStreaming("hi");
    RecordingSubscriber subscriber = subscribe(run.updates(), Long.MAX_VALUE);

    assertThat(subscriber.terminals).hasValue(1);
    assertThat(subscriber.failure.get())
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("model returned tool calls after tools were disabled");
  }

  @Test
  void providerHooksAndTheSessionSaveWrapTheWholeStreamingToolLoop() {
    List<String> log = new ArrayList<>();
    List<ModelRequest> requests = new ArrayList<>();
    StreamingModelClient client =
        streaming(
            request -> {
              requests.add(request);
              log.add("model");
              if (requests.size() == 1) {
                return publisher(
                    List.of(
                        update(
                            toolCall("call-1", "weather", SEOUL), null, FinishReason.TOOL_CALLS)));
              }
              return publisher(List.of(update(assistant("It is sunny"), null, FinishReason.STOP)));
            });
    CountingProvider provider = new CountingProvider("memory", log);
    InMemorySessionStore store = new InMemorySessionStore(new JacksonSessionSnapshotCodec());
    AgentEngine engine =
        AgentEngine.builder()
            .modelClient(client)
            .tools(weatherTool())
            .contextProviders(provider)
            .sessionStore(store)
            .build();
    AgentRunRequest request =
        request(
            Message.normalize("weather?"),
            session("session-1", null, Map.of()),
            new AgentRunOptions(),
            new CancellationSignal(),
            ContextAttributes.empty());

    AgentStreamingRun<AgentResponseUpdate> run = engine.runStreaming(request);
    RecordingSubscriber subscriber = subscribe(run.updates(), Long.MAX_VALUE);
    subscriber.completion.join();
    AgentResponse response = run.response().toCompletableFuture().join();

    assertThat(log).containsExactly("before:memory", "model", "model", "after:memory");
    assertThat(describe(requests.get(0).messages()))
        .containsExactly("user|context:memory", "user|weather?");
    assertThat(describe(requests.get(1).messages()))
        .containsExactly(
            "user|context:memory",
            "user|weather?",
            "assistant||call:call-1:weather:{city=Seoul}",
            "tool||result:call-1:weather:false:[sunny:Seoul]");
    assertThat(response.text()).isEqualTo("It is sunny");
    assertThat(run.session().toCompletableFuture().join()).isPresent();
    assertThat(store.load("session-1").toCompletableFuture().join()).isPresent();
  }

  @Test
  void modelUpdatesReachTheSubscriberBeforeTheIterationCompletes() {
    ManualStreams streams = new ManualStreams();
    AgentStreamingRun<AgentResponseUpdate> run =
        engine(streams.client(), weatherTool()).runStreaming("hi");
    RecordingSubscriber subscriber = subscribe(run.updates(), 0);
    subscriber.request(4);

    streams.stream(0).emit(update(assistant("thinking "), null, FinishReason.TOOL_CALLS));

    assertThat(subscriber.texts()).containsExactly("thinking ");
    assertThat(subscriber.completion).isNotDone();
    assertThat(streams.count()).isEqualTo(1);

    streams.stream(0)
        .emit(update(toolCall("call-1", "weather", SEOUL), null, FinishReason.TOOL_CALLS));
    streams.stream(0).complete();

    assertThat(subscriber.values).hasSize(3);
    assertThat(streams.count()).isEqualTo(2);

    streams.stream(1).emit(update(assistant("It is sunny"), null, FinishReason.STOP));
    streams.stream(1).complete();
    subscriber.completion.join();

    assertThat(run.response().toCompletableFuture().join().text())
        .isEqualTo("thinking It is sunny");
  }

  @Test
  void queuedToolResultsAndTheNextModelCallFollowDownstreamDemand() {
    ManualStreams streams = new ManualStreams();
    AgentStreamingRun<AgentResponseUpdate> run =
        engine(streams.client(), weatherTool()).runStreaming("hi");
    RecordingSubscriber subscriber = subscribe(run.updates(), 0);
    subscriber.request(1);

    streams.stream(0)
        .emit(update(toolCall("call-1", "weather", SEOUL), null, FinishReason.TOOL_CALLS));
    streams.stream(0).complete();

    assertThat(subscriber.values).hasSize(1);
    assertThat(streams.count()).isEqualTo(1);

    subscriber.request(1);

    assertThat(subscriber.values).hasSize(2);
    assertThat(describe(subscriber.values.get(1).messages()))
        .containsExactly("tool||result:call-1:weather:false:[sunny:Seoul]");
    assertThat(streams.count()).isEqualTo(2);

    subscriber.request(1);
    streams.stream(1).emit(update(assistant("It is sunny"), null, FinishReason.STOP));
    streams.stream(1).complete();
    subscriber.completion.join();

    assertThat(subscriber.terminals).hasValue(1);
  }

  @Test
  void nonPositiveDemandFailsTheStreamExactlyOnce() {
    ManualStreams streams = new ManualStreams();
    AgentStreamingRun<AgentResponseUpdate> run =
        engine(streams.client(), weatherTool()).runStreaming("hi");
    RecordingSubscriber subscriber = subscribe(run.updates(), 0);

    subscriber.request(0);

    assertThat(subscriber.terminals).hasValue(1);
    assertThat(subscriber.failure.get())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("demand must be greater than zero");
    assertThat(streams.stream(0).cancelled).isTrue();
    assertThatThrownBy(() -> run.response().toCompletableFuture().join())
        .hasRootCauseInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void cancellingBeforeAQueuedToolResultStopsTheLoop() {
    ManualStreams streams = new ManualStreams();
    AgentStreamingRun<AgentResponseUpdate> run =
        engine(streams.client(), weatherTool()).runStreaming("hi");
    RecordingSubscriber subscriber = subscribe(run.updates(), 0);
    subscriber.request(1);

    streams.stream(0)
        .emit(update(toolCall("call-1", "weather", SEOUL), null, FinishReason.TOOL_CALLS));
    streams.stream(0).complete();

    assertThat(subscriber.values).hasSize(1);
    assertThat(streams.count()).isEqualTo(1);

    subscriber.cancel();
    subscriber.request(4);

    assertThat(subscriber.values).hasSize(1);
    assertThat(streams.count()).isEqualTo(1);
    assertThatThrownBy(() -> run.response().toCompletableFuture().join())
        .hasRootCauseInstanceOf(CancellationException.class);
  }

  @Test
  void cancellingWhileAToolStageIsPendingFailsPromptlyAndNeverStartsTheNextModelCall() {
    CompletableFuture<ToolResult> pending = new CompletableFuture<>();
    FunctionTool slow = tool("slow", "slow", Map.of(), (arguments, context) -> pending);
    ManualStreams streams = new ManualStreams();
    AgentStreamingRun<AgentResponseUpdate> run = engine(streams.client(), slow).runStreaming("hi");
    RecordingSubscriber subscriber = subscribe(run.updates(), Long.MAX_VALUE);

    streams.stream(0)
        .emit(update(toolCall("call-1", "slow", Map.of()), null, FinishReason.TOOL_CALLS));
    streams.stream(0).complete();

    assertThat(subscriber.completion).isNotDone();
    assertThat(streams.count()).isEqualTo(1);

    run.cancel();

    assertThat(subscriber.terminals).hasValue(1);
    assertThat(subscriber.failure.get()).isInstanceOf(CancellationException.class);
    assertThatThrownBy(() -> run.response().toCompletableFuture().join())
        .hasRootCauseInstanceOf(CancellationException.class);

    pending.complete(ToolResult.success(new TextContent("late")));

    assertThat(streams.count()).isEqualTo(1);
    assertThat(subscriber.values).hasSize(1);
    assertThat(subscriber.terminals).hasValue(1);
  }

  @Test
  void cancellingWhileTheModelIsStreamingCancelsTheModelSubscription() {
    ManualStreams streams = new ManualStreams();
    AgentStreamingRun<AgentResponseUpdate> run =
        engine(streams.client(), weatherTool()).runStreaming("hi");
    RecordingSubscriber subscriber = subscribe(run.updates(), Long.MAX_VALUE);

    streams.stream(0).emit(update(assistant("thinking"), null, FinishReason.TOOL_CALLS));
    run.cancel();

    assertThat(streams.stream(0).cancelled).isTrue();
    assertThat(subscriber.terminals).hasValue(1);

    streams.stream(0).emit(update(assistant("late"), null, FinishReason.STOP));

    assertThat(subscriber.values).hasSize(1);
    assertThat(subscriber.terminals).hasValue(1);
  }

  /**
   * A cancellation that is already observable on the run's signal, but whose listeners have not
   * reached the loop yet, must not let a queued tool result start the next model call. The listener
   * registered here runs before the loop's own, so it drains the queued result — and therefore
   * reaches the point where the next iteration would begin — while the loop still believes it was
   * not cancelled.
   */
  @Test
  void aCancellationObservedBeforeTheNextIterationStartsNoFurtherModelCall() {
    AtomicReference<RecordingSubscriber> subscriberRef = new AtomicReference<>();
    CancellationSignal signal = new CancellationSignal();
    signal.onCancel(
        () -> {
          RecordingSubscriber pending = subscriberRef.get();
          if (pending != null) {
            pending.request(4);
          }
        });
    ManualStreams streams = new ManualStreams();
    AgentStreamingRun<AgentResponseUpdate> run =
        engine(streams.client(), weatherTool())
            .runStreaming(
                request(
                    Message.normalize("hi"),
                    null,
                    new AgentRunOptions(),
                    signal,
                    ContextAttributes.empty()));
    RecordingSubscriber subscriber = subscribe(run.updates(), 0);
    subscriberRef.set(subscriber);
    subscriber.request(1);

    streams.stream(0)
        .emit(update(toolCall("call-1", "weather", SEOUL), null, FinishReason.TOOL_CALLS));
    streams.stream(0).complete();

    assertThat(subscriber.values).hasSize(1);
    assertThat(streams.count()).isEqualTo(1);

    signal.cancel();

    assertThat(streams.count()).isEqualTo(1);
    assertThatThrownBy(() -> run.response().toCompletableFuture().join())
        .hasRootCauseInstanceOf(CancellationException.class);
  }

  @Test
  void demandRejectedFromOnSubscribeStartsNoModelCall() {
    ManualStreams streams = new ManualStreams();
    AgentEngine engine =
        AgentEngine.builder()
            .modelClient(streams.client())
            .tools(weatherTool())
            .contextProviders(new CountingProvider("memory", new ArrayList<>()))
            .build();
    AgentStreamingRun<AgentResponseUpdate> run = engine.runStreaming("hi");

    RecordingSubscriber subscriber = subscribeRequestingFromOnSubscribe(run.updates(), 0);

    assertThat(streams.count()).isZero();
    assertThat(subscriber.terminals).hasValue(1);
    assertThat(subscriber.failure.get())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("demand must be greater than zero");
  }

  /**
   * Characterises what "the same response an ordinary run returns" means for message structure.
   * Both paths report the same roles in the same order and the same content in the same order, but
   * an ordinary response keeps the message boundaries its model client returned, while a streamed
   * response is reconstructed by {@link AgentResponse#fromUpdates}, which coalesces consecutive
   * same-role chunks that carry no message id. No in-tree model port reports message ids, so the
   * streamed boundaries are a property of the reconstruction, not a guarantee of this loop.
   */
  @Test
  void consecutiveAssistantChunksAreReconstructedAsOneMessage() {
    AtomicInteger ordinaryCalls = new AtomicInteger();
    ModelClient ordinaryClient =
        request ->
            ordinaryCalls.getAndIncrement() == 0
                ? completedFuture(
                    modelResponse(
                        List.of(toolCall("call-1", "weather", SEOUL)),
                        null,
                        FinishReason.TOOL_CALLS,
                        Map.of()))
                : completedFuture(
                    modelResponse(
                        List.of(assistant("It is "), assistant("sunny")),
                        null,
                        FinishReason.STOP,
                        Map.of()));
    StreamingModelClient streamingClient =
        scripted(
            new ArrayList<>(),
            List.of(
                List.of(
                    update(toolCall("call-1", "weather", SEOUL), null, FinishReason.TOOL_CALLS)),
                List.of(
                    update(assistant("It is "), null, FinishReason.STOP),
                    update(assistant("sunny"), null, FinishReason.STOP))));

    AgentResponse ordinary =
        engine(ordinaryClient, weatherTool())
            .run("weather?")
            .response()
            .toCompletableFuture()
            .join();
    AgentResponse streaming = streamedResponse(streamingClient);

    assertThat(describe(ordinary.messages()))
        .containsExactly(
            "assistant||call:call-1:weather:{city=Seoul}",
            "tool||result:call-1:weather:false:[sunny:Seoul]",
            "assistant|It is ",
            "assistant|sunny");
    assertThat(describe(streaming.messages()))
        .containsExactly(
            "assistant||call:call-1:weather:{city=Seoul}",
            "tool||result:call-1:weather:false:[sunny:Seoul]",
            "assistant|It is sunny");
    assertThat(streaming.text()).isEqualTo(ordinary.text());
    assertThat(streaming.messages().stream().map(message -> message.role().value()).distinct())
        .containsExactlyElementsOf(
            ordinary.messages().stream()
                .map(message -> message.role().value())
                .distinct()
                .toList());
  }

  private static AgentEngine engine(ModelClient client, FunctionTool... tools) {
    return AgentEngine.builder()
        .id("agent-1")
        .name("assistant")
        .modelClient(client)
        .tools(tools)
        .build();
  }

  private static FunctionTool tool(
      String name, String description, Map<String, Object> inputSchema, ToolHandler handler) {
    return FunctionTool.create(
        name, description, (JsonObject) JsonValues.fromJava(inputSchema), handler);
  }

  private static AgentSession session(
      String sessionId, String serviceSessionId, Map<String, ?> state) {
    AgentSession.Builder builder =
        AgentSession.builder().sessionId(sessionId).state(sessionState(state));
    if (serviceSessionId != null) {
      builder.serviceSessionId(serviceSessionId);
    }
    return builder.build();
  }

  private static SessionState sessionState(Map<String, ?> state) {
    SessionState sessionState = SessionState.empty();
    for (Map.Entry<String, ?> entry : state.entrySet()) {
      sessionState = put(sessionState, entry.getKey(), entry.getValue());
    }
    return sessionState;
  }

  @SuppressWarnings("unchecked")
  private static SessionState put(SessionState state, String key, Object value) {
    if (SessionStateValues.isJsonValueShape(value)) {
      return state.with(SessionStateKey.of(key, JsonValue.class), JsonValues.fromJava(value));
    }
    return state.with((SessionStateKey<Object>) SessionStateKey.of(key, value.getClass()), value);
  }

  private static Map<String, Object> javaMap(JsonObject object) {
    java.util.LinkedHashMap<String, Object> values = new java.util.LinkedHashMap<>();
    object.values().forEach((key, value) -> values.put(key, JsonValues.toJava(value)));
    return Collections.unmodifiableMap(values);
  }

  private static FunctionTool weatherTool() {
    return tool(
        "weather",
        "Gets weather",
        Map.of("type", "object"),
        (arguments, context) ->
            completedFuture(ToolResult.success(new TextContent("sunny:" + arguments.get("city")))));
  }

  /** A weather tool that records the arguments each invocation was given. */
  private static FunctionTool recordingWeatherTool(List<Map<String, Object>> observedArguments) {
    return tool(
        "weather",
        "Gets weather",
        Map.of(),
        (arguments, context) -> {
          observedArguments.add(javaMap(arguments.values()));
          return completedFuture(ToolResult.success(new TextContent("sunny")));
        });
  }

  /** A tool that records {@code name:arguments} per invocation, so call order is observable. */
  private static FunctionTool recordingTool(String name, List<String> invocations) {
    return tool(
        name,
        name,
        Map.of(),
        (arguments, context) -> {
          invocations.add(name + ":" + javaMap(arguments.values()));
          return completedFuture(ToolResult.success(new TextContent(name)));
        });
  }

  /** An ordinary client that calls one tool and then answers, with per-iteration metadata. */
  private static ModelClient twoIterations(
      Map<String, Object> firstMetadata, Map<String, Object> terminalMetadata) {
    AtomicInteger calls = new AtomicInteger();
    return request ->
        calls.getAndIncrement() == 0
            ? completedFuture(
                modelResponse(
                    List.of(toolCall("call-1", "weather", SEOUL)),
                    null,
                    FinishReason.TOOL_CALLS,
                    firstMetadata))
            : completedFuture(
                modelResponse(
                    List.of(assistant("It is sunny")), null, FinishReason.STOP, terminalMetadata));
  }

  /** The streaming counterpart of {@link #twoIterations}, one update per iteration. */
  private static StreamingModelClient twoStreamedIterations(
      Map<String, Object> firstMetadata, Map<String, Object> terminalMetadata) {
    return scripted(
        new ArrayList<>(),
        List.of(
            List.of(
                update(
                    toolCall("call-1", "weather", SEOUL),
                    null,
                    FinishReason.TOOL_CALLS,
                    firstMetadata)),
            List.of(update(assistant("It is sunny"), null, FinishReason.STOP, terminalMetadata))));
  }

  /** Consumes a whole streaming run with unbounded demand and returns its assembled response. */
  private static AgentResponse streamedResponse(StreamingModelClient client) {
    AgentStreamingRun<AgentResponseUpdate> run =
        engine(client, weatherTool()).runStreaming("weather?");
    subscribe(run.updates(), Long.MAX_VALUE).completion.join();
    return run.response().toCompletableFuture().join();
  }

  /** The {@code callId:name} of every tool result the assembled response reports, in order. */
  private static List<String> toolResults(AgentResponse response) {
    List<String> results = new ArrayList<>();
    for (Message message : response.messages()) {
      for (Content content : message.content()) {
        if (content instanceof ToolResultContent result) {
          results.add(result.callId() + ":" + result.name());
        }
      }
    }
    return results;
  }

  /** The tool calls a model request echoes, across all of its messages, in order. */
  private static List<ToolCallContent> requestToolCalls(ModelRequest request) {
    return requestContent(request, ToolCallContent.class);
  }

  /** The tool results a model request reports, across all of its messages, in order. */
  private static List<ToolResultContent> requestToolResults(ModelRequest request) {
    return requestContent(request, ToolResultContent.class);
  }

  private static <T extends Content> List<T> requestContent(ModelRequest request, Class<T> type) {
    List<T> found = new ArrayList<>();
    for (Message message : request.messages()) {
      for (Content content : message.content()) {
        if (type.isInstance(content)) {
          found.add(type.cast(content));
        }
      }
    }
    return List.copyOf(found);
  }

  private static Message assistant(String text) {
    return new Message(Role.ASSISTANT, List.of(new TextContent(text)));
  }

  private static Message toolCall(String callId, String name, Map<String, Object> arguments) {
    return new Message(
        Role.ASSISTANT, List.of(new ToolCallContent(callId, name, jsonObject(arguments))));
  }

  private static ModelResponseUpdate update(Message message, Usage usage, FinishReason reason) {
    return update(message, usage, reason, Map.of());
  }

  private static ModelResponseUpdate update(
      Message message, Usage usage, FinishReason reason, Map<String, Object> metadata) {
    return ModelResponseUpdate.builder()
        .messages(List.of(message))
        .usage(usage)
        .finishReason(reason)
        .metadata(jsonObject(metadata))
        .build();
  }

  private static ModelResponse modelResponse(
      List<Message> messages,
      Usage usage,
      FinishReason finishReason,
      Map<String, Object> metadata) {
    return ModelResponse.builder()
        .messages(messages)
        .usage(usage)
        .finishReason(finishReason)
        .metadata(jsonObject(metadata))
        .build();
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

  private static JsonObject jsonObject(Map<String, Object> values) {
    return values.isEmpty() ? JsonObject.empty() : (JsonObject) JsonValues.fromJava(values);
  }

  /**
   * Projects messages onto their observable shape, because message content has no value equality.
   */
  private static List<String> describe(List<Message> messages) {
    List<String> described = new ArrayList<>();
    for (Message message : messages) {
      StringBuilder builder = new StringBuilder(message.role().value()).append('|');
      builder.append(message.text());
      for (Content content : message.content()) {
        if (content instanceof ToolCallContent call) {
          builder
              .append("|call:")
              .append(call.callId())
              .append(':')
              .append(call.name())
              .append(':')
              .append(call.arguments());
        } else if (content instanceof ToolResultContent result) {
          builder
              .append("|result:")
              .append(result.callId())
              .append(':')
              .append(result.name())
              .append(':')
              .append(result.error())
              .append(':')
              .append(result.content().stream().map(Content::text).toList());
        }
      }
      described.add(builder.toString());
    }
    return described;
  }

  private static StreamingModelClient streaming(
      Function<ModelRequest, Flow.Publisher<ModelResponseUpdate>> streaming) {
    return new StreamingModelClient() {
      @Override
      public CompletionStage<ModelResponse> run(ModelRequest request) {
        throw new IllegalStateException("a streaming run must not call the ordinary model port");
      }

      @Override
      public Flow.Publisher<ModelResponseUpdate> runStreaming(ModelRequest request) {
        return streaming.apply(request);
      }
    };
  }

  private static StreamingModelClient scripted(
      List<ModelRequest> requests, List<List<ModelResponseUpdate>> iterations) {
    AtomicInteger index = new AtomicInteger();
    return streaming(
        request -> {
          requests.add(request);
          int iteration = index.getAndIncrement();
          if (iteration >= iterations.size()) {
            throw new IllegalStateException("unexpected model call: " + iteration);
          }
          return publisher(iterations.get(iteration));
        });
  }

  /** Emits a scripted iteration strictly within the demand the loop granted it. */
  private static Flow.Publisher<ModelResponseUpdate> publisher(List<ModelResponseUpdate> updates) {
    return subscriber ->
        subscriber.onSubscribe(
            new Flow.Subscription() {
              private int index;
              private boolean done;
              private boolean emitting;
              private long demand;

              @Override
              public void request(long n) {
                if (done) {
                  return;
                }
                if (n <= 0) {
                  done = true;
                  subscriber.onError(
                      new IllegalArgumentException("demand must be greater than zero"));
                  return;
                }
                demand += n;
                if (emitting) {
                  return;
                }
                emitting = true;
                while (demand > 0 && index < updates.size()) {
                  demand--;
                  subscriber.onNext(updates.get(index++));
                }
                emitting = false;
                if (index == updates.size() && !done) {
                  done = true;
                  subscriber.onComplete();
                }
              }

              @Override
              public void cancel() {
                done = true;
              }
            });
  }

  private static RecordingSubscriber subscribe(
      Flow.Publisher<AgentResponseUpdate> publisher, long initialDemand) {
    RecordingSubscriber subscriber = new RecordingSubscriber(initialDemand, initialDemand > 0);
    publisher.subscribe(subscriber);
    return subscriber;
  }

  /** Subscribes with a subscriber that always calls {@code request} from {@code onSubscribe}. */
  private static RecordingSubscriber subscribeRequestingFromOnSubscribe(
      Flow.Publisher<AgentResponseUpdate> publisher, long initialDemand) {
    RecordingSubscriber subscriber = new RecordingSubscriber(initialDemand, true);
    publisher.subscribe(subscriber);
    return subscriber;
  }

  /** A subscriber whose demand a test controls, so no assertion depends on timing. */
  private static final class RecordingSubscriber implements Flow.Subscriber<AgentResponseUpdate> {
    private final List<AgentResponseUpdate> values = new ArrayList<>();
    private final CompletableFuture<Void> completion = new CompletableFuture<>();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final AtomicInteger terminals = new AtomicInteger();
    private final AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
    private final long initialDemand;
    private final boolean requestFromOnSubscribe;

    private RecordingSubscriber(long initialDemand, boolean requestFromOnSubscribe) {
      this.initialDemand = initialDemand;
      this.requestFromOnSubscribe = requestFromOnSubscribe;
    }

    @Override
    public void onSubscribe(Flow.Subscription value) {
      subscription.set(value);
      if (requestFromOnSubscribe) {
        value.request(initialDemand);
      }
    }

    @Override
    public void onNext(AgentResponseUpdate item) {
      values.add(item);
    }

    @Override
    public void onError(Throwable throwable) {
      terminals.incrementAndGet();
      failure.set(throwable);
      completion.completeExceptionally(throwable);
    }

    @Override
    public void onComplete() {
      terminals.incrementAndGet();
      completion.complete(null);
    }

    private void request(long n) {
      subscription.get().request(n);
    }

    private void cancel() {
      subscription.get().cancel();
    }

    private List<String> texts() {
      return values.stream().map(AgentResponseUpdate::text).toList();
    }
  }

  /** A subscriber that rejects its first update, so a drain has to survive a foreign failure. */
  private static final class ThrowingOnceSubscriber
      implements Flow.Subscriber<AgentResponseUpdate> {
    private final List<String> texts = new ArrayList<>();
    private final AtomicInteger terminals = new AtomicInteger();
    private boolean thrown;

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
      subscription.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(AgentResponseUpdate item) {
      if (!thrown) {
        thrown = true;
        throw new IllegalStateException("subscriber rejected the update");
      }
      texts.add(item.text());
    }

    @Override
    public void onError(Throwable throwable) {
      terminals.incrementAndGet();
    }

    @Override
    public void onComplete() {
      terminals.incrementAndGet();
    }
  }

  /** A streaming client whose iterations a test drives update by update. */
  private static final class ManualStreams {
    private final List<ManualStream> streams = new ArrayList<>();

    private StreamingModelClient client() {
      return streaming(
          request -> {
            ManualStream stream = new ManualStream();
            streams.add(stream);
            return stream;
          });
    }

    private ManualStream stream(int index) {
      return streams.get(index);
    }

    private int count() {
      return streams.size();
    }
  }

  private static final class ManualStream implements Flow.Publisher<ModelResponseUpdate> {
    private final AtomicLong demand = new AtomicLong();
    private final AtomicReference<Flow.Subscriber<? super ModelResponseUpdate>> subscriber =
        new AtomicReference<>();
    private volatile boolean cancelled;

    @Override
    public void subscribe(Flow.Subscriber<? super ModelResponseUpdate> downstream) {
      subscriber.set(downstream);
      downstream.onSubscribe(
          new Flow.Subscription() {
            @Override
            public void request(long n) {
              demand.addAndGet(n);
            }

            @Override
            public void cancel() {
              cancelled = true;
            }
          });
    }

    private void emit(ModelResponseUpdate update) {
      if (demand.get() <= 0) {
        throw new IllegalStateException("the loop requested no update from this model call");
      }
      demand.decrementAndGet();
      subscriber.get().onNext(update);
    }

    private void complete() {
      subscriber.get().onComplete();
    }
  }

  /** A model stream that completes without ever emitting an update. */
  private static final class EmptySubscription implements Flow.Subscription {
    private final Flow.Subscriber<? super ModelResponseUpdate> subscriber;
    private boolean done;

    private EmptySubscription(Flow.Subscriber<? super ModelResponseUpdate> subscriber) {
      this.subscriber = subscriber;
    }

    @Override
    public void request(long n) {
      if (done) {
        return;
      }
      done = true;
      subscriber.onComplete();
    }

    @Override
    public void cancel() {
      done = true;
    }
  }

  /** Records hook order and keeps a counter in session state, so a save can be observed. */
  private static final class CountingProvider implements ContextProvider {
    private final String sourceId;
    private final List<String> log;

    private CountingProvider(String sourceId, List<String> log) {
      this.sourceId = sourceId;
      this.log = log;
    }

    @Override
    public String sourceId() {
      return sourceId;
    }

    @Override
    public CompletionStage<Void> beforeRun(SessionContext context, ProviderSessionState state) {
      log.add("before:" + sourceId);
      context.addContextMessages(
          state.sourceId(),
          List.of(new Message(Role.USER, List.of(new TextContent("context:" + sourceId)))));
      return completedFuture(null);
    }

    @Override
    public CompletionStage<Void> afterRun(SessionContext context, ProviderSessionState state) {
      log.add("after:" + sourceId);
      return completedFuture(null);
    }
  }
}
