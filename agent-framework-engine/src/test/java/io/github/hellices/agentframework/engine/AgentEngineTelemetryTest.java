package io.github.hellices.agentframework.engine;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hellices.agentframework.api.agent.Agent;
import io.github.hellices.agentframework.api.agent.AgentBuilder;
import io.github.hellices.agentframework.api.agent.AgentRunOptions;
import io.github.hellices.agentframework.api.agent.AgentRunRequest;
import io.github.hellices.agentframework.api.agent.AgentSession;
import io.github.hellices.agentframework.api.agent.CancellationSignal;
import io.github.hellices.agentframework.api.message.FinishReason;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.Role;
import io.github.hellices.agentframework.api.message.TextContent;
import io.github.hellices.agentframework.api.message.ToolCallContent;
import io.github.hellices.agentframework.api.session.SessionSnapshot;
import io.github.hellices.agentframework.api.tool.FunctionTool;
import io.github.hellices.agentframework.api.tool.ToolResult;
import io.github.hellices.agentframework.api.value.JsonObject;
import io.github.hellices.agentframework.api.value.JsonValues;
import io.github.hellices.agentframework.spi.model.ModelClient;
import io.github.hellices.agentframework.spi.model.ModelResponse;
import io.github.hellices.agentframework.spi.session.SessionStore;
import io.github.hellices.agentframework.spi.telemetry.TelemetryAttributes;
import io.github.hellices.agentframework.spi.telemetry.TelemetryEvent;
import io.github.hellices.agentframework.spi.telemetry.TelemetryOperation;
import io.github.hellices.agentframework.spi.telemetry.TelemetryOperationKind;
import io.github.hellices.agentframework.spi.telemetry.TelemetrySink;
import io.github.hellices.agentframework.spi.telemetry.TelemetryStart;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Behavioral tests for engine telemetry instrumentation (Task 6).
 *
 * <p>Tests in this class assert operation nesting, failure identity, tool-call count metadata, and
 * the absence of sensitive data from telemetry by default.
 */
class AgentEngineTelemetryTest {

  // ── Recording sink ───────────────────────────────────────────────────────

  /**
   * A recording {@link TelemetrySink} that captures every operation opened and every lifecycle
   * event on it, in order.
   */
  private static final class RecordingSink implements TelemetrySink {

    final List<OperationRecord> operations = new CopyOnWriteArrayList<>();
    final List<OperationRecord> closeOrder = new CopyOnWriteArrayList<>();

    @Override
    public TelemetryOperation start(TelemetryStart start) {
      OperationRecord record = new OperationRecord(start, null);
      operations.add(record);
      return operationFor(record);
    }

    private TelemetryOperation operationFor(OperationRecord record) {
      return new TelemetryOperation() {
        @Override
        public TelemetryOperation startChild(TelemetryStart start) {
          OperationRecord child = new OperationRecord(start, record);
          operations.add(child);
          return operationFor(child);
        }

        @Override
        public void event(TelemetryEvent event) {
          record.events.add(event);
        }

        @Override
        public void fail(Throwable failure) {
          record.failCount.incrementAndGet();
          record.failures.add(failure);
          record.failed = true;
        }

        @Override
        public void close() {
          record.closeCount.incrementAndGet();
          closeOrder.add(record);
          record.closed = true;
        }
      };
    }

    List<OperationRecord> ofKind(TelemetryOperationKind kind) {
      return operations.stream().filter(r -> r.start.kind() == kind).toList();
    }

    int closeIndexOf(OperationRecord record) {
      return closeOrder.indexOf(record);
    }
  }

  /** Plain recording data class (not AutoCloseable) to avoid PMD CloseResource false positives. */
  private static final class OperationRecord {
    final TelemetryStart start;
    final OperationRecord parent;
    final List<TelemetryEvent> events = new CopyOnWriteArrayList<>();
    final List<Throwable> failures = new CopyOnWriteArrayList<>();
    final AtomicInteger closeCount = new AtomicInteger(0);
    final AtomicInteger failCount = new AtomicInteger(0);
    volatile boolean closed = false;
    volatile boolean failed = false;

    OperationRecord(TelemetryStart start, OperationRecord parent) {
      this.start = start;
      this.parent = parent;
    }

    boolean isTerminated() {
      return closed || failed;
    }
  }

  // ── Helpers ──────────────────────────────────────────────────────────────

  private static AgentBuilder boundBuilder(AgentEngine engine, ModelClient model) {
    return engine.factory().builderWithClient(model);
  }

  private static AgentEngine engineWith(RecordingSink sink) {
    return AgentEngine.builder().telemetrySink(sink).build();
  }

  private static AgentEngine engineWithSession(RecordingSink sink) {
    return AgentEngine.builder()
        .telemetrySink(sink)
        .sessionStore(
            new io.github.hellices.agentframework.engine.session.InMemorySessionStore(
                new io.github.hellices.agentframework.engine.session.JacksonSessionSnapshotCodec()))
        .build();
  }

  private static AgentRunRequest simpleRequest(String text) {
    return AgentRunRequest.builder()
        .messages(Message.normalize(text))
        .options(new AgentRunOptions())
        .cancellationSignal(new CancellationSignal())
        .build();
  }

  private static AgentRunRequest sessionRequest(String sessionId, String text) {
    return AgentRunRequest.builder()
        .session(AgentSession.builder().sessionId(sessionId).build())
        .messages(Message.normalize(text))
        .options(new AgentRunOptions())
        .cancellationSignal(new CancellationSignal())
        .build();
  }

  private static ModelResponse textResponse(String text) {
    return ModelResponse.builder()
        .messages(List.of(new Message(Role.ASSISTANT, List.of(new TextContent(text)))))
        .finishReason(FinishReason.STOP)
        .build();
  }

  private static ModelResponse toolCallResponse(String callId, String toolName) {
    return ModelResponse.builder()
        .messages(
            List.of(
                new Message(
                    Role.ASSISTANT,
                    List.of(new ToolCallContent(callId, toolName, JsonObject.empty())))))
        .finishReason(FinishReason.TOOL_CALLS)
        .build();
  }

  private static ModelResponse twoToolCallResponse() {
    return ModelResponse.builder()
        .messages(
            List.of(
                new Message(
                    Role.ASSISTANT,
                    List.of(
                        new ToolCallContent("c1", "tool-a", JsonObject.empty()),
                        new ToolCallContent("c2", "tool-b", JsonObject.empty())))))
        .finishReason(FinishReason.TOOL_CALLS)
        .build();
  }

  private static FunctionTool echoTool(String name) {
    return FunctionTool.create(
        name,
        "An echo tool",
        (JsonObject) JsonValues.fromJava(Map.of("type", "object")),
        (args, ctx) -> completedFuture(ToolResult.success(new TextContent("ok"))));
  }

  private static <T> List<T> drain(Flow.Publisher<T> publisher) {
    List<T> out = new ArrayList<>();
    CompletableFuture<Void> done = new CompletableFuture<>();
    publisher.subscribe(
        new Flow.Subscriber<>() {
          @Override
          public void onSubscribe(Flow.Subscription s) {
            s.request(Long.MAX_VALUE);
          }

          @Override
          public void onNext(T item) {
            out.add(item);
          }

          @Override
          public void onError(Throwable t) {
            done.completeExceptionally(t);
          }

          @Override
          public void onComplete() {
            done.complete(null);
          }
        });
    done.join();
    return out;
  }

  private static final class FailingSessionStore implements SessionStore {

    private final RuntimeException failure;

    private FailingSessionStore(RuntimeException failure) {
      this.failure = failure;
    }

    @Override
    public CompletionStage<Optional<SessionSnapshot>> load(String sessionId) {
      return CompletableFuture.failedFuture(failure);
    }

    @Override
    public CompletionStage<Void> save(SessionSnapshot snapshot) {
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<Void> delete(String sessionId) {
      return CompletableFuture.completedFuture(null);
    }
  }

  // ── Tests ────────────────────────────────────────────────────────────────

  @Test
  void agentRunOperationIsOpenedForEveryRun() {
    RecordingSink sink = new RecordingSink();
    AgentEngine engine = engineWith(sink);
    Agent agent =
        boundBuilder(engine, req -> EngineModels.of(textResponse("hello")))
            .id("a1")
            .name("assistant")
            .description("d")
            .build();

    agent.run(simpleRequest("hi")).response().toCompletableFuture().join();

    assertThat(sink.ofKind(TelemetryOperationKind.AGENT_RUN)).hasSize(1);
  }

  @Test
  void agentRunOperationIsClosedAfterSuccess() {
    RecordingSink sink = new RecordingSink();
    AgentEngine engine = engineWith(sink);
    Agent agent =
        boundBuilder(engine, req -> EngineModels.of(textResponse("done")))
            .id("a1")
            .name("assistant")
            .description("d")
            .build();

    agent.run(simpleRequest("hi")).response().toCompletableFuture().join();

    List<OperationRecord> runs = sink.ofKind(TelemetryOperationKind.AGENT_RUN);
    assertThat(runs).hasSize(1);
    assertThat(runs.get(0).closed).isTrue();
    assertThat(runs.get(0).failed).isFalse();
    assertThat(runs.get(0).closeCount.get()).isEqualTo(1);
  }

  @Test
  void modelCallOperationIsNestedInsideAgentRunOperation() {
    RecordingSink sink = new RecordingSink();
    AgentEngine engine = engineWith(sink);
    Agent agent =
        boundBuilder(engine, req -> EngineModels.of(textResponse("hi")))
            .id("a1")
            .name("assistant")
            .description("d")
            .build();

    agent.run(simpleRequest("hello")).response().toCompletableFuture().join();

    List<OperationRecord> runs = sink.ofKind(TelemetryOperationKind.AGENT_RUN);
    List<OperationRecord> models = sink.ofKind(TelemetryOperationKind.MODEL_CALL);
    assertThat(runs).hasSize(1);
    assertThat(models).hasSize(1);
    // The model call operation must be started AFTER the run operation and closed BEFORE it.
    int runIdx = sink.operations.indexOf(runs.get(0));
    int modelIdx = sink.operations.indexOf(models.get(0));
    assertThat(modelIdx).isGreaterThan(runIdx);
    // All model-call operations must be closed before the run operation is closed.
    assertThat(models.get(0).isTerminated()).isTrue();
  }

  @Test
  void modelCallOperationIsChildOfAgentRun() {
    RecordingSink sink = new RecordingSink();
    AgentEngine engine = engineWith(sink);
    Agent agent =
        boundBuilder(engine, req -> EngineModels.of(textResponse("hi")))
            .id("a1")
            .name("assistant")
            .description("d")
            .build();

    agent.run(simpleRequest("hello")).response().toCompletableFuture().join();

    OperationRecord agentRunRec = sink.ofKind(TelemetryOperationKind.AGENT_RUN).get(0);
    OperationRecord modelCallRec = sink.ofKind(TelemetryOperationKind.MODEL_CALL).get(0);
    assertThat(modelCallRec.parent).isSameAs(agentRunRec);
  }

  @Test
  void cancelledOrdinaryRunTerminatesActiveModelOperation() {
    RecordingSink sink = new RecordingSink();
    AgentEngine engine = engineWith(sink);
    CompletableFuture<ModelResponse> pending = new CompletableFuture<>();
    Agent agent =
        boundBuilder(engine, req -> EngineModels.fromStage(pending))
            .id("a1")
            .name("assistant")
            .description("d")
            .build();

    var run = agent.run(simpleRequest("hi"));
    run.cancel();
    pending.complete(textResponse("late"));

    assertThatThrownBy(() -> run.response().toCompletableFuture().join())
        .hasRootCauseInstanceOf(CancellationException.class);

    List<OperationRecord> modelOps = sink.ofKind(TelemetryOperationKind.MODEL_CALL);
    assertThat(modelOps).hasSize(1);
    assertThat(modelOps.get(0).isTerminated()).isTrue();
    assertThat(modelOps.get(0).failCount.get()).isEqualTo(1);
    assertThat(modelOps.get(0).failures.get(0))
        .isInstanceOf(CancellationException.class)
        .hasMessage("run was cancelled");
  }

  @Test
  void toolCallOperationIsEmittedForEachToolCall() {
    RecordingSink sink = new RecordingSink();
    AtomicInteger callCount = new AtomicInteger();
    AgentEngine engine = engineWith(sink);

    Agent agent =
        boundBuilder(
                engine,
                req ->
                    callCount.incrementAndGet() == 1
                        ? EngineModels.of(toolCallResponse("c1", "echo"))
                        : EngineModels.of(textResponse("done")))
            .id("a1")
            .name("assistant")
            .description("d")
            .tools(echoTool("echo"))
            .build();

    agent.run(simpleRequest("hi")).response().toCompletableFuture().join();

    assertThat(sink.ofKind(TelemetryOperationKind.TOOL_CALL)).hasSize(1);
    OperationRecord toolOp = sink.ofKind(TelemetryOperationKind.TOOL_CALL).get(0);
    assertThat(toolOp.start.attributes().getString(TelemetryAttributes.TOOL_NAME))
        .isEqualTo("echo");
    assertThat(toolOp.isTerminated()).isTrue();
  }

  @Test
  void toolCallCountAttributeIsPopulatedWithBatchSize() {
    RecordingSink sink = new RecordingSink();
    AtomicInteger callCount = new AtomicInteger();
    AgentEngine engine = engineWith(sink);

    Agent agent =
        boundBuilder(
                engine,
                req ->
                    callCount.incrementAndGet() == 1
                        ? EngineModels.of(twoToolCallResponse())
                        : EngineModels.of(textResponse("done")))
            .id("a1")
            .name("assistant")
            .description("d")
            .tools(echoTool("tool-a"))
            .tools(echoTool("tool-b"))
            .build();

    agent.run(simpleRequest("hi")).response().toCompletableFuture().join();

    List<OperationRecord> toolOps = sink.ofKind(TelemetryOperationKind.TOOL_CALL);
    assertThat(toolOps).hasSize(2);
    for (OperationRecord op : toolOps) {
      assertThat(op.start.attributes().getLong(TelemetryAttributes.TOOL_CALL_COUNT)).isEqualTo(2L);
      assertThat(op.start.attributes().getLong(TelemetryAttributes.TOOL_CALL_INDEX)).isNotNull();
    }
    // Indices must be 0 and 1
    List<Long> indices =
        toolOps.stream()
            .map(op -> op.start.attributes().getLong(TelemetryAttributes.TOOL_CALL_INDEX))
            .toList();
    assertThat(indices).containsExactlyInAnyOrder(0L, 1L);
  }

  @Test
  void failureIdentityIsPreservedInAgentRunOperation() {
    RecordingSink sink = new RecordingSink();
    RuntimeException cause = new RuntimeException("model broke");
    AgentEngine engine = engineWith(sink);

    Agent agent =
        boundBuilder(engine, req -> EngineModels.failed(cause))
            .id("a1")
            .name("assistant")
            .description("d")
            .build();

    assertThatThrownBy(() -> agent.run(simpleRequest("hi")).response().toCompletableFuture().join())
        .hasCauseInstanceOf(RuntimeException.class);

    List<OperationRecord> runs = sink.ofKind(TelemetryOperationKind.AGENT_RUN);
    assertThat(runs).hasSize(1);
    OperationRecord runOp = runs.get(0);
    assertThat(runOp.failed).isTrue();
    assertThat(runOp.failures).hasSize(1);
    assertThat(runOp.failures.get(0)).isSameAs(cause);
    assertThat(runOp.failCount.get()).isEqualTo(1);
  }

  @Test
  void failureIdentityIsPreservedInModelCallOperation() {
    RecordingSink sink = new RecordingSink();
    RuntimeException cause = new RuntimeException("model error");
    AgentEngine engine = engineWith(sink);

    Agent agent =
        boundBuilder(engine, req -> EngineModels.failed(cause))
            .id("a1")
            .name("assistant")
            .description("d")
            .build();

    assertThatThrownBy(() -> agent.run(simpleRequest("hi")).response().toCompletableFuture().join())
        .hasCauseInstanceOf(RuntimeException.class);

    List<OperationRecord> models = sink.ofKind(TelemetryOperationKind.MODEL_CALL);
    assertThat(models).hasSize(1);
    OperationRecord modelOp = models.get(0);
    assertThat(modelOp.failed).isTrue();
    assertThat(modelOp.failures.get(0)).isSameAs(cause);
    assertThat(modelOp.failCount.get()).isEqualTo(1);
  }

  @Test
  void closeIsExactlyOncePerOperationOnSuccess() {
    RecordingSink sink = new RecordingSink();
    AtomicInteger callCount = new AtomicInteger();
    AgentEngine engine = engineWith(sink);

    Agent agent =
        boundBuilder(
                engine,
                req ->
                    callCount.incrementAndGet() == 1
                        ? EngineModels.of(toolCallResponse("c1", "echo"))
                        : EngineModels.of(textResponse("done")))
            .id("a1")
            .name("assistant")
            .description("d")
            .tools(echoTool("echo"))
            .build();

    agent.run(simpleRequest("hi")).response().toCompletableFuture().join();

    for (OperationRecord op : sink.operations) {
      assertThat(op.closeCount.get()).as("closeCount for %s", op.start.kind()).isEqualTo(1);
      assertThat(op.failCount.get()).as("failCount for %s", op.start.kind()).isEqualTo(0);
    }
  }

  @Test
  void noSensitiveDataInOperationAttributesByDefault() {
    RecordingSink sink = new RecordingSink();
    AtomicInteger callCount = new AtomicInteger();
    AgentEngine engine = engineWith(sink);

    Agent agent =
        boundBuilder(
                engine,
                req ->
                    callCount.incrementAndGet() == 1
                        ? EngineModels.of(toolCallResponse("c1", "echo"))
                        : EngineModels.of(textResponse("response body here")))
            .id("a1")
            .name("assistant")
            .description("d")
            .tools(echoTool("echo"))
            .build();

    agent.run(simpleRequest("prompt body here")).response().toCompletableFuture().join();

    for (OperationRecord op : sink.operations) {
      StringBuilder sb = new StringBuilder();
      op.start.attributes().forEach((k, v) -> sb.append(v.toString().toLowerCase()).append(" "));
      String allValues = sb.toString();
      // Prompt body, model output, tool arguments, and tool results must not appear
      assertThat(allValues)
          .as("operation %s must not contain prompt body", op.start.kind())
          .doesNotContain("prompt body here");
      assertThat(allValues)
          .as("operation %s must not contain model output", op.start.kind())
          .doesNotContain("response body here");
    }
  }

  @Test
  void agentRunAttributesIncludeAgentId() {
    RecordingSink sink = new RecordingSink();
    AgentEngine engine = engineWith(sink);
    Agent agent =
        boundBuilder(engine, req -> EngineModels.of(textResponse("hi")))
            .id("my-agent-id")
            .name("assistant")
            .description("d")
            .build();

    agent.run(simpleRequest("hello")).response().toCompletableFuture().join();

    OperationRecord runOp = sink.ofKind(TelemetryOperationKind.AGENT_RUN).get(0);
    assertThat(runOp.start.attributes().getString(TelemetryAttributes.AGENT_ID))
        .isEqualTo("my-agent-id");
  }

  @Test
  void streamingRunEmitsSameTelemetryStructureAsOrdinaryRun() {
    RecordingSink ordinarySink = new RecordingSink();
    RecordingSink streamingSink = new RecordingSink();

    AgentEngine ordinaryEngine = engineWith(ordinarySink);
    AgentEngine streamingEngine = engineWith(streamingSink);

    AtomicInteger oc = new AtomicInteger();
    AtomicInteger sc = new AtomicInteger();

    Agent ordinaryAgent =
        boundBuilder(
                ordinaryEngine,
                req ->
                    oc.incrementAndGet() == 1
                        ? EngineModels.of(toolCallResponse("c1", "echo"))
                        : EngineModels.of(textResponse("done")))
            .id("a1")
            .name("assistant")
            .description("d")
            .tools(echoTool("echo"))
            .build();

    Agent streamingAgent =
        boundBuilder(
                streamingEngine,
                req ->
                    sc.incrementAndGet() == 1
                        ? EngineModels.of(toolCallResponse("c1", "echo"))
                        : EngineModels.of(textResponse("done")))
            .id("a1")
            .name("assistant")
            .description("d")
            .tools(echoTool("echo"))
            .build();

    // ordinary run
    ordinaryAgent.run(simpleRequest("hi")).response().toCompletableFuture().join();

    // streaming run - drain the publisher
    drain(streamingAgent.runStreaming(simpleRequest("hi")).updates());

    // Both should emit the same kinds of operations
    List<TelemetryOperationKind> ordinaryKinds =
        ordinarySink.operations.stream().map(op -> op.start.kind()).toList();
    List<TelemetryOperationKind> streamingKinds =
        streamingSink.operations.stream().map(op -> op.start.kind()).toList();

    assertThat(ordinaryKinds).containsExactlyElementsOf(streamingKinds);
  }

  @Test
  void multipleIterationsProduceOneModelCallOperationPerIteration() {
    RecordingSink sink = new RecordingSink();
    AtomicInteger callCount = new AtomicInteger();
    AgentEngine engine = engineWith(sink);

    Agent agent =
        boundBuilder(
                engine,
                req ->
                    callCount.incrementAndGet() == 1
                        ? EngineModels.of(toolCallResponse("c1", "echo"))
                        : EngineModels.of(textResponse("done")))
            .id("a1")
            .name("assistant")
            .description("d")
            .tools(echoTool("echo"))
            .build();

    agent.run(simpleRequest("hi")).response().toCompletableFuture().join();

    assertThat(sink.ofKind(TelemetryOperationKind.MODEL_CALL)).hasSize(2);
    assertThat(sink.ofKind(TelemetryOperationKind.TOOL_CALL)).hasSize(1);
    assertThat(sink.ofKind(TelemetryOperationKind.AGENT_RUN)).hasSize(1);
  }

  @Test
  void sessionLoadOperationIsEmittedAndNestedInAgentRun() {
    RecordingSink sink = new RecordingSink();
    AgentEngine engine = engineWithSession(sink);
    Agent agent =
        boundBuilder(engine, req -> EngineModels.of(textResponse("hello")))
            .id("a1")
            .name("assistant")
            .description("d")
            .build();

    agent.run(sessionRequest("sid-1", "hello")).response().toCompletableFuture().join();

    OperationRecord agentRunRec = sink.ofKind(TelemetryOperationKind.AGENT_RUN).get(0);
    OperationRecord loadRec =
        sink.ofKind(TelemetryOperationKind.SESSION_OPERATION).stream()
            .filter(
                r ->
                    "load"
                        .equals(
                            r.start.attributes().getString(TelemetryAttributes.SESSION_OPERATION)))
            .findFirst()
            .orElseThrow();

    assertThat(loadRec.start.attributes().getString(TelemetryAttributes.SESSION_OPERATION))
        .isEqualTo("load");
    assertThat(loadRec.start.attributes().getString(TelemetryAttributes.SESSION_ID))
        .isEqualTo("sid-1");
    assertThat(loadRec.closeCount.get()).isEqualTo(1);
    assertThat(loadRec.failCount.get()).isEqualTo(0);
    assertThat(sink.closeIndexOf(loadRec)).isLessThan(sink.closeIndexOf(agentRunRec));
  }

  @Test
  void sessionSaveOperationIsEmittedAndNestedInAgentRun() {
    RecordingSink sink = new RecordingSink();
    AgentEngine engine = engineWithSession(sink);
    Agent agent =
        boundBuilder(engine, req -> EngineModels.of(textResponse("hello")))
            .id("a1")
            .name("assistant")
            .description("d")
            .build();

    agent.run(sessionRequest("sid-1", "hello")).response().toCompletableFuture().join();

    OperationRecord agentRunRec = sink.ofKind(TelemetryOperationKind.AGENT_RUN).get(0);
    OperationRecord saveRec =
        sink.ofKind(TelemetryOperationKind.SESSION_OPERATION).stream()
            .filter(
                r ->
                    "save"
                        .equals(
                            r.start.attributes().getString(TelemetryAttributes.SESSION_OPERATION)))
            .findFirst()
            .orElseThrow();

    assertThat(saveRec.start.attributes().getString(TelemetryAttributes.SESSION_OPERATION))
        .isEqualTo("save");
    assertThat(saveRec.start.attributes().getString(TelemetryAttributes.SESSION_ID))
        .isEqualTo("sid-1");
    assertThat(saveRec.closeCount.get()).isEqualTo(1);
    assertThat(saveRec.failCount.get()).isEqualTo(0);
    assertThat(sink.closeIndexOf(saveRec)).isLessThan(sink.closeIndexOf(agentRunRec));
  }

  @Test
  void sessionOperationLoadFailurePreservesRootCauseInAgentRun() {
    RecordingSink sink = new RecordingSink();
    RuntimeException cause = new RuntimeException("store failure");
    AgentEngine engine =
        AgentEngine.builder()
            .telemetrySink(sink)
            .sessionStore(new FailingSessionStore(cause))
            .build();
    Agent agent =
        boundBuilder(engine, req -> EngineModels.of(textResponse("hello")))
            .id("a1")
            .name("assistant")
            .description("d")
            .build();

    assertThatThrownBy(
            () ->
                agent.run(sessionRequest("sid-1", "hello")).response().toCompletableFuture().join())
        .isInstanceOf(CompletionException.class)
        .satisfies(failure -> assertThat(failure.getCause()).isSameAs(cause));

    OperationRecord agentRunRec = sink.ofKind(TelemetryOperationKind.AGENT_RUN).get(0);
    OperationRecord loadRec =
        sink.ofKind(TelemetryOperationKind.SESSION_OPERATION).stream()
            .filter(
                r ->
                    "load"
                        .equals(
                            r.start.attributes().getString(TelemetryAttributes.SESSION_OPERATION)))
            .findFirst()
            .orElseThrow();

    assertThat(sink.ofKind(TelemetryOperationKind.SESSION_OPERATION)).hasSize(1);
    assertThat(loadRec.failed).isTrue();
    assertThat(loadRec.failures).containsExactly(cause);
    assertThat(loadRec.failCount.get()).isEqualTo(1);
    assertThat(agentRunRec.failed).isTrue();
    assertThat(agentRunRec.failures).containsExactly(cause);
    assertThat(agentRunRec.failCount.get()).isEqualTo(1);
  }

  @Test
  void sessionOperationsAreNotEmittedForSessionlessRun() {
    RecordingSink sink = new RecordingSink();
    AgentEngine engine = engineWith(sink);
    Agent agent =
        boundBuilder(engine, req -> EngineModels.of(textResponse("hello")))
            .id("a1")
            .name("assistant")
            .description("d")
            .build();

    agent.run(simpleRequest("hello")).response().toCompletableFuture().join();

    assertThat(sink.ofKind(TelemetryOperationKind.SESSION_OPERATION)).isEmpty();
  }
}
