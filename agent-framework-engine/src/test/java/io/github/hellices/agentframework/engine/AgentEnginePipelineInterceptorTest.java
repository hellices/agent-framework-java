package io.github.hellices.agentframework.engine;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hellices.agentframework.api.agent.Agent;
import io.github.hellices.agentframework.api.agent.AgentResponse;
import io.github.hellices.agentframework.api.agent.AgentResponseUpdate;
import io.github.hellices.agentframework.api.agent.AgentRun;
import io.github.hellices.agentframework.api.agent.AgentRunOptions;
import io.github.hellices.agentframework.api.agent.AgentRunRequest;
import io.github.hellices.agentframework.api.agent.AgentSession;
import io.github.hellices.agentframework.api.agent.AgentStreamingRun;
import io.github.hellices.agentframework.api.agent.CancellationSignal;
import io.github.hellices.agentframework.api.agent.RunContribution;
import io.github.hellices.agentframework.api.context.ContextAttributes;
import io.github.hellices.agentframework.api.message.Content;
import io.github.hellices.agentframework.api.message.FinishReason;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.Role;
import io.github.hellices.agentframework.api.message.TextContent;
import io.github.hellices.agentframework.api.message.ToolCallContent;
import io.github.hellices.agentframework.api.message.ToolResultContent;
import io.github.hellices.agentframework.api.session.SessionContext;
import io.github.hellices.agentframework.api.session.SessionSnapshot;
import io.github.hellices.agentframework.api.tool.FunctionTool;
import io.github.hellices.agentframework.api.tool.ToolResult;
import io.github.hellices.agentframework.api.value.JsonObject;
import io.github.hellices.agentframework.api.value.JsonValues;
import io.github.hellices.agentframework.spi.interception.AgentExecution;
import io.github.hellices.agentframework.spi.interception.AgentExecutionInterceptor;
import io.github.hellices.agentframework.spi.interception.ModelInvocationInterceptor;
import io.github.hellices.agentframework.spi.interception.SessionOperationInterceptor;
import io.github.hellices.agentframework.spi.interception.ToolInvocationInterceptor;
import io.github.hellices.agentframework.spi.model.ModelClient;
import io.github.hellices.agentframework.spi.model.ModelResponse;
import io.github.hellices.agentframework.spi.session.ContextProvider;
import io.github.hellices.agentframework.spi.session.SessionStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * The run-level contract of the four execution interceptor seams: each is entered exactly once per
 * the operation it wraps, a short-circuit performs no downstream work, a replacement re-enters the
 * ordinary pipeline, and a synchronous throw or {@code null} return is routed to the run's own
 * failure channel — the same for an ordinary run and a streaming run.
 */
class AgentEnginePipelineInterceptorTest {

  private static final Map<String, Object> SEOUL = Map.of("city", "Seoul");

  // --- seam invocation counts ------------------------------------------------------------------

  @Test
  void agentSeamRunsExactlyOncePerOrdinaryRunAndStreamingRun() {
    AtomicInteger ordinaryCount = new AtomicInteger();
    Agent ordinaryAgent =
        agentWith(
            builder -> builder.agentExecutionInterceptor(counting(ordinaryCount)), fixed("hi"));
    ordinaryAgent.run("hi").response().toCompletableFuture().join();
    assertThat(ordinaryCount).hasValue(1);

    AtomicInteger streamingCount = new AtomicInteger();
    Agent streamingAgent =
        agentWith(
            builder -> builder.agentExecutionInterceptor(counting(streamingCount)), fixed("hi"));
    streamResponse(streamingAgent);
    assertThat(streamingCount).hasValue(1);
  }

  @Test
  void modelSeamRunsOncePerIteration() {
    AtomicInteger modelCount = new AtomicInteger();
    Agent agent =
        agentWith(
            builder ->
                builder.modelInvocationInterceptor(
                    (invocation, next) -> {
                      modelCount.incrementAndGet();
                      return next.proceed(invocation);
                    }),
            toolThenText("weather", SEOUL, "done"),
            weatherTool());

    agent.run("hi").response().toCompletableFuture().join();

    assertThat(modelCount).hasValue(2);
  }

  @Test
  void toolSeamRunsOncePerExecutedBoundCall() {
    AtomicInteger toolCount = new AtomicInteger();
    ModelClient client =
        twoIterations(
            modelResponse(
                List.of(
                    new Message(
                        Role.ASSISTANT,
                        List.of(
                            new ToolCallContent("call-1", "weather", jsonObject(SEOUL)),
                            new ToolCallContent("call-2", "echo", jsonObject(Map.of()))))),
                FinishReason.TOOL_CALLS),
            modelResponse(List.of(assistant("done")), FinishReason.STOP));
    Agent agent =
        agentWith(
            builder ->
                builder.toolInvocationInterceptor(
                    (invocation, next) -> {
                      toolCount.incrementAndGet();
                      return next.proceed(invocation);
                    }),
            client,
            weatherTool(),
            echoTool());

    agent.run("hi").response().toCompletableFuture().join();

    assertThat(toolCount).hasValue(2);
  }

  @Test
  void sessionSeamWrapsEveryLoadAndSave() {
    List<String> operations = new ArrayList<>();
    RecordingStore store = new RecordingStore();
    SessionOperationInterceptor interceptor =
        (invocation, next) -> {
          operations.add(invocation.operation().name());
          return next.proceed(invocation);
        };
    Agent agent =
        AgentEngine.builder()
            .sessionStore(store)
            .sessionOperationInterceptor(interceptor)
            .build()
            .factory()
            .builderWithClient(fixed("hi"))
            .id("agent-1")
            .name("assistant")
            .build();

    run(agent, session("session-1"), "hi");

    assertThat(operations).containsExactly("LOAD", "SAVE");
    assertThat(store.log).containsExactly("load:session-1", "save:session-1");
  }

  // --- ordinary and streaming agree on seam order ----------------------------------------------

  @Test
  void ordinaryAndStreamingProduceIdenticalSeamOrder() {
    assertThat(seamOrder(false)).isEqualTo(seamOrder(true));
    assertThat(seamOrder(false))
        .containsExactly("agent", "session:LOAD", "model", "tool", "model", "session:SAVE");
  }

  private List<String> seamOrder(boolean streaming) {
    List<String> events = new ArrayList<>();
    RecordingStore store = new RecordingStore();
    Agent agent =
        AgentEngine.builder()
            .sessionStore(store)
            .agentExecutionInterceptor(
                (invocation, next) -> {
                  events.add("agent");
                  return next.proceed(invocation);
                })
            .modelInvocationInterceptor(
                (invocation, next) -> {
                  events.add("model");
                  return next.proceed(invocation);
                })
            .toolInvocationInterceptor(
                (invocation, next) -> {
                  events.add("tool");
                  return next.proceed(invocation);
                })
            .sessionOperationInterceptor(
                (invocation, next) -> {
                  events.add("session:" + invocation.operation().name());
                  return next.proceed(invocation);
                })
            .build()
            .factory()
            .builderWithClient(toolThenText("weather", SEOUL, "done"))
            .id("agent-1")
            .name("assistant")
            .tools(weatherTool())
            .build();

    if (streaming) {
      AgentStreamingRun<AgentResponseUpdate> run =
          agent.runStreaming(streamingRequest(session("session-1"), "hi"));
      drain(run.updates());
      run.response().toCompletableFuture().join();
    } else {
      run(agent, session("session-1"), "hi");
    }
    return events;
  }

  // --- short-circuit ---------------------------------------------------------------------------

  @Test
  void agentShortCircuitPerformsNoModelOrToolOrSessionWork() {
    AtomicInteger modelCount = new AtomicInteger();
    Agent agent =
        agentWith(
            builder ->
                builder
                    .agentExecutionInterceptor(
                        (invocation, next) ->
                            AgentExecution.fromUpdate(
                                agentUpdate("short-circuit"), invocation.cancellationSignal()))
                    .modelInvocationInterceptor(
                        (invocation, next) -> {
                          modelCount.incrementAndGet();
                          return next.proceed(invocation);
                        }),
            throwingModel(),
            weatherTool());

    AgentResponse response = agent.run("hi").response().toCompletableFuture().join();

    assertThat(response.text()).isEqualTo("short-circuit");
    assertThat(modelCount).hasValue(0);
  }

  @Test
  void agentShortCircuitInStreamingDerivesResponseFromCanonicalUpdates() {
    Agent agent =
        agentWith(
            builder ->
                builder.agentExecutionInterceptor(
                    (invocation, next) ->
                        AgentExecution.fromUpdate(
                            agentUpdate("short-circuit"), invocation.cancellationSignal())),
            throwingModel());

    AgentResponse response = streamResponse(agent);

    assertThat(response.text()).isEqualTo("short-circuit");
  }

  @Test
  void modelReplacementReEntersPipelineAccumulation() {
    ModelInvocationInterceptor interceptor =
        (invocation, next) ->
            EngineModels.of(modelResponse(List.of(assistant("replaced")), FinishReason.STOP));
    Agent agent =
        agentWith(builder -> builder.modelInvocationInterceptor(interceptor), throwingModel());

    AgentResponse response = agent.run("hi").response().toCompletableFuture().join();

    assertThat(response.text()).isEqualTo("replaced");
  }

  @Test
  void toolReplacementReEntersPipelineNormalization() {
    ToolInvocationInterceptor interceptor =
        (invocation, next) -> completedFuture(ToolResult.success(new TextContent("intercepted")));
    Agent agent =
        agentWith(
            builder -> builder.toolInvocationInterceptor(interceptor),
            toolThenText("weather", SEOUL, "done"),
            weatherTool());

    AgentResponse response = agent.run("hi").response().toCompletableFuture().join();

    assertThat(toolResultTexts(response)).containsExactly("intercepted");
  }

  @Test
  void sessionLoadShortCircuitSkipsTheStoreLoad() {
    RecordingStore store = new RecordingStore();
    SessionOperationInterceptor interceptor =
        (invocation, next) ->
            completedFuture(
                io.github.hellices.agentframework.spi.interception.SessionOperationResult.builder()
                    .operation(invocation.operation())
                    .session(invocation.session())
                    .snapshot(invocation.snapshot().orElse(null))
                    .build());
    Agent agent =
        AgentEngine.builder()
            .sessionStore(store)
            .sessionOperationInterceptor(interceptor)
            .build()
            .factory()
            .builderWithClient(fixed("hi"))
            .id("agent-1")
            .name("assistant")
            .build();

    run(agent, session("session-1"), "hi");

    assertThat(store.log).isEmpty();
  }

  // --- synchronous throws and null returns are routed to the failure channel -------------------

  @Test
  void synchronousModelInterceptorThrowFailsRunViaResponseStage() {
    IllegalStateException boom = new IllegalStateException("model interceptor boom");
    Agent agent =
        storeBackedAgent(
            builder ->
                builder.modelInvocationInterceptor(
                    (invocation, next) -> {
                      throw boom;
                    }),
            fixed("hi"));

    assertThatThrownBy(() -> run(agent, session("session-1"), "hi")).hasRootCause(boom);
  }

  @Test
  void nullModelInterceptorReturnFailsRunViaResponseStage() {
    Agent agent =
        storeBackedAgent(
            builder -> builder.modelInvocationInterceptor((invocation, next) -> null), fixed("hi"));

    assertThatThrownBy(() -> run(agent, session("session-1"), "hi"))
        .hasRootCauseInstanceOf(NullPointerException.class)
        .hasRootCauseMessage("model response publisher must not be null");
  }

  @Test
  void synchronousToolInterceptorThrowFailsRunViaResponseStage() {
    IllegalStateException boom = new IllegalStateException("tool interceptor boom");
    Agent agent =
        agentWith(
            builder ->
                builder.toolInvocationInterceptor(
                    (invocation, next) -> {
                      throw boom;
                    }),
            toolThenText("weather", SEOUL, "done"),
            weatherTool());

    assertThatThrownBy(() -> agent.run("hi").response().toCompletableFuture().join())
        .hasRootCause(boom);
  }

  @Test
  void synchronousSessionInterceptorThrowFailsRunViaResponseStage() {
    IllegalStateException boom = new IllegalStateException("session interceptor boom");
    Agent agent =
        storeBackedAgent(
            builder ->
                builder.sessionOperationInterceptor(
                    (invocation, next) -> {
                      throw boom;
                    }),
            fixed("hi"));

    assertThatThrownBy(() -> run(agent, session("session-1"), "hi")).hasRootCause(boom);
  }

  @Test
  void agentInterceptorThrowFailsRunViaResponseStageNotSynchronously() {
    IllegalStateException boom = new IllegalStateException("agent interceptor boom");
    Agent agent =
        agentWith(
            builder ->
                builder.agentExecutionInterceptor(
                    (invocation, next) -> {
                      throw boom;
                    }),
            fixed("hi"));

    // run(...) itself must not throw: the interceptor failure is reported on the run's response.
    CompletionStage<AgentResponse> response = agent.run("hi").response();
    assertThatThrownBy(() -> response.toCompletableFuture().join()).hasRootCause(boom);
  }

  // --- cancellation signal identity ------------------------------------------------------------

  @Test
  void agentExecutionWithForeignCancellationSignalFailsExplicitlyBeforeSubscription() {
    AtomicInteger modelCount = new AtomicInteger();
    Agent agent =
        agentWith(
            builder ->
                builder
                    .agentExecutionInterceptor(
                        (invocation, next) ->
                            AgentExecution.fromUpdate(
                                agentUpdate("short"), new CancellationSignal()))
                    .modelInvocationInterceptor(
                        (invocation, next) -> {
                          modelCount.incrementAndGet();
                          return next.proceed(invocation);
                        }),
            throwingModel());

    assertThatThrownBy(() -> agent.run("hi").response().toCompletableFuture().join())
        .hasRootCauseInstanceOf(IllegalStateException.class)
        .hasRootCauseMessage(
            "agent execution cancellationSignal must be the run's cancellation signal");
    assertThat(modelCount).hasValue(0);
  }

  @Test
  void agentExecutionReusingTheRequestCancellationSignalCompletes() {
    Agent agent =
        agentWith(
            builder ->
                builder.agentExecutionInterceptor(
                    (invocation, next) ->
                        AgentExecution.fromUpdate(
                            agentUpdate("ok"), invocation.cancellationSignal())),
            throwingModel());

    AgentResponse response = agent.run("hi").response().toCompletableFuture().join();

    assertThat(response.text()).isEqualTo("ok");
  }

  // --- F1: an agent short-circuit touches neither the context providers nor the store ----------

  @Test
  void agentShortCircuitDoesNotLoadOrSaveAPreseededStoreForOrdinaryRun() {
    RecordingStore store = new RecordingStore();
    SessionSnapshot seeded = seededSnapshot();
    store.seed(seeded);
    AgentSession input = session("session-1");
    Agent agent = shortCircuitingStoreAgent(store);

    AgentRun run = agent.run(request(input, "hi"));
    AgentResponse response = run.response().toCompletableFuture().join();

    assertThat(response.text()).isEqualTo("short-circuit");
    assertThat(store.log).isEmpty();
    assertThat(store.snapshots.get("session-1")).isEqualTo(seeded);
    assertThat(run.session().toCompletableFuture().join()).contains(input);
  }

  @Test
  void agentShortCircuitDoesNotLoadOrSaveAPreseededStoreForStreamingRun() {
    RecordingStore store = new RecordingStore();
    SessionSnapshot seeded = seededSnapshot();
    store.seed(seeded);
    AgentSession input = session("session-1");
    Agent agent = shortCircuitingStoreAgent(store);

    AgentStreamingRun<AgentResponseUpdate> run = agent.runStreaming(request(input, "hi"));
    drain(run.updates());
    AgentResponse response = run.response().toCompletableFuture().join();

    assertThat(response.text()).isEqualTo("short-circuit");
    assertThat(store.log).isEmpty();
    assertThat(store.snapshots.get("session-1")).isEqualTo(seeded);
    assertThat(run.session().toCompletableFuture().join()).contains(input);
  }

  // --- F2: a transformed execution is the sole source of the final response for both shapes -----

  @Test
  void agentUpdateMappingIsTheSoleSourceForTheOrdinaryResponse() {
    Agent agent =
        agentWith(
            builder -> builder.agentExecutionInterceptor(upperCasingInterceptor()), fixed("hello"));

    AgentResponse response = agent.run("hi").response().toCompletableFuture().join();

    assertThat(response.text()).isEqualTo("HELLO");
  }

  @Test
  void agentUpdateMappingReflectsIdenticallyInOrdinaryAndStreaming() {
    AgentResponse ordinary =
        agentWith(
                builder -> builder.agentExecutionInterceptor(upperCasingInterceptor()),
                fixed("hello"))
            .run("hi")
            .response()
            .toCompletableFuture()
            .join();
    AgentResponse streaming =
        streamResponse(
            agentWith(
                builder -> builder.agentExecutionInterceptor(upperCasingInterceptor()),
                fixed("hello")));

    assertThat(ordinary.text()).isEqualTo("HELLO");
    assertThat(streaming.text()).isEqualTo("HELLO");
  }

  @Test
  void agentUpdateReplacementReflectsIdenticallyInOrdinaryAndStreaming() {
    AgentResponse ordinary =
        agentWith(
                builder -> builder.agentExecutionInterceptor(replacingInterceptor("replaced-text")),
                fixed("hello"))
            .run("hi")
            .response()
            .toCompletableFuture()
            .join();
    AgentResponse streaming =
        streamResponse(
            agentWith(
                builder -> builder.agentExecutionInterceptor(replacingInterceptor("replaced-text")),
                fixed("hello")));

    assertThat(ordinary.text()).isEqualTo("replaced-text");
    assertThat(streaming.text()).isEqualTo("replaced-text");
  }

  @Test
  void aContextProviderSeesTheTransformedResponseBeforeSave() {
    List<String> order = new ArrayList<>();
    SessionStore store =
        new SessionStore() {
          @Override
          public CompletionStage<Optional<SessionSnapshot>> load(String sessionId) {
            order.add("load");
            return completedFuture(Optional.empty());
          }

          @Override
          public CompletionStage<Void> save(SessionSnapshot snapshot) {
            order.add("save");
            return completedFuture(null);
          }

          @Override
          public CompletionStage<Void> delete(String sessionId) {
            return completedFuture(null);
          }
        };
    ContextProvider provider =
        new ContextProvider() {
          @Override
          public CompletionStage<RunContribution> prepare(SessionContext context) {
            return completedFuture(RunContribution.empty());
          }

          @Override
          public CompletionStage<Void> complete(SessionContext context) {
            order.add("provider:" + context.response().map(AgentResponse::text).orElse("<none>"));
            return completedFuture(null);
          }
        };
    Agent agent =
        AgentEngine.builder()
            .sessionStore(store)
            .agentExecutionInterceptor(upperCasingInterceptor())
            .build()
            .factory()
            .builderWithClient(fixed("hello"))
            .id("agent-1")
            .name("assistant")
            .contextProviders(provider)
            .build();

    run(agent, session("session-1"), "hi");

    assertThat(order).containsExactly("load", "provider:HELLO", "save");
  }

  // --- I1: proceeding then abandoning the pipeline fails explicitly, identically, without saving
  // --

  @Test
  void agentInterceptorThatProceedsButAbandonsThePipelineFailsOrdinaryRunExplicitly() {
    RecordingStore store = new RecordingStore();
    store.seed(seededSnapshot());
    Agent agent = storeAgentWithInterceptor(store, abandoningInterceptor(), throwingModel());

    AgentRun run = agent.run(request(session("session-1"), "hi"));

    assertThatThrownBy(() -> run.response().toCompletableFuture().join())
        .hasRootCauseInstanceOf(IllegalStateException.class)
        .hasRootCauseMessage(PROCEEDED_WITHOUT_CONSUMING);
    assertThat(store.log).doesNotContain("save:session-1");
    assertThat(store.snapshots.get("session-1")).isEqualTo(seededSnapshot());
  }

  @Test
  void agentInterceptorThatProceedsButAbandonsThePipelineFailsStreamingRunExplicitly() {
    RecordingStore store = new RecordingStore();
    store.seed(seededSnapshot());
    Agent agent = storeAgentWithInterceptor(store, abandoningInterceptor(), throwingModel());

    AgentStreamingRun<AgentResponseUpdate> run =
        agent.runStreaming(request(session("session-1"), "hi"));
    Throwable updateFailure = drainForError(run.updates());

    assertThat(rootCause(updateFailure))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(PROCEEDED_WITHOUT_CONSUMING);
    assertThatThrownBy(() -> run.response().toCompletableFuture().join())
        .hasRootCauseInstanceOf(IllegalStateException.class)
        .hasRootCauseMessage(PROCEEDED_WITHOUT_CONSUMING);
    assertThat(store.log).doesNotContain("save:session-1");
    assertThat(store.snapshots.get("session-1")).isEqualTo(seededSnapshot());
  }

  @Test
  void abandoningThePipelineFailsOrdinaryAndStreamingWithTheIdenticalRootCause() {
    Throwable ordinary =
        rootCause(
            catchFailure(
                () ->
                    storeAgentWithInterceptor(
                            new RecordingStore(), abandoningInterceptor(), throwingModel())
                        .run(request(session("session-1"), "hi"))
                        .response()
                        .toCompletableFuture()
                        .join()));
    Throwable streaming =
        rootCause(
            catchFailure(
                () -> {
                  AgentStreamingRun<AgentResponseUpdate> run =
                      storeAgentWithInterceptor(
                              new RecordingStore(), abandoningInterceptor(), throwingModel())
                          .runStreaming(request(session("session-1"), "hi"));
                  drainForError(run.updates());
                  return run.response().toCompletableFuture().join();
                }));

    assertThat(ordinary).isInstanceOf(IllegalStateException.class);
    assertThat(streaming).isInstanceOf(IllegalStateException.class);
    assertThat(ordinary.getClass()).isEqualTo(streaming.getClass());
    assertThat(ordinary.getMessage()).isEqualTo(streaming.getMessage());
  }

  // --- helpers ---------------------------------------------------------------------------------

  private static final String PROCEEDED_WITHOUT_CONSUMING =
      "an agent interceptor that proceeds must return updates that consume the proceeded execution;"
          + " a replacement that abandons it must short-circuit without proceeding";

  private static Agent storeAgentWithInterceptor(
      SessionStore store, AgentExecutionInterceptor interceptor, ModelClient client) {
    return AgentEngine.builder()
        .sessionStore(store)
        .agentExecutionInterceptor(interceptor)
        .build()
        .factory()
        .builderWithClient(client)
        .id("agent-1")
        .name("assistant")
        .build();
  }

  private static AgentExecutionInterceptor abandoningInterceptor() {
    return (invocation, next) -> {
      next.proceed(invocation);
      return AgentExecution.fromUpdate(agentUpdate("abandoned"), invocation.cancellationSignal());
    };
  }

  private static Throwable drainForError(Flow.Publisher<AgentResponseUpdate> publisher) {
    CompletableFuture<Throwable> outcome = new CompletableFuture<>();
    publisher.subscribe(
        new Flow.Subscriber<>() {
          @Override
          public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
          }

          @Override
          public void onNext(AgentResponseUpdate item) {}

          @Override
          public void onError(Throwable throwable) {
            outcome.complete(throwable);
          }

          @Override
          public void onComplete() {
            outcome.complete(null);
          }
        });
    return outcome.join();
  }

  private static Throwable catchFailure(java.util.concurrent.Callable<?> action) {
    try {
      action.call();
      return null;
    } catch (Exception failure) {
      return failure;
    }
  }

  private static Throwable rootCause(Throwable throwable) {
    Throwable current = throwable;
    while (current.getCause() != null) {
      current = current.getCause();
    }
    return current;
  }

  private static Agent shortCircuitingStoreAgent(SessionStore store) {
    return AgentEngine.builder()
        .sessionStore(store)
        .agentExecutionInterceptor(
            (invocation, next) ->
                AgentExecution.fromUpdate(
                    agentUpdate("short-circuit"), invocation.cancellationSignal()))
        .build()
        .factory()
        .builderWithClient(throwingModel())
        .id("agent-1")
        .name("assistant")
        .build();
  }

  private static SessionSnapshot seededSnapshot() {
    return new SessionSnapshot(
        "session", "1.0", "session-1", null, 5, Instant.parse("2020-01-01T00:00:00Z"), Map.of());
  }

  private static AgentExecutionInterceptor upperCasingInterceptor() {
    return (invocation, next) ->
        next.proceed(invocation)
            .mapUpdates(update -> mapText(update, text -> text.toUpperCase(Locale.ROOT)));
  }

  private static AgentExecutionInterceptor replacingInterceptor(String replacement) {
    return (invocation, next) ->
        next.proceed(invocation).mapUpdates(update -> mapText(update, text -> replacement));
  }

  private static AgentResponseUpdate mapText(
      AgentResponseUpdate update, Function<String, String> textMapper) {
    List<Message> mapped = new ArrayList<>();
    for (Message message : update.messages()) {
      List<Content> content = new ArrayList<>();
      for (Content item : message.content()) {
        content.add(
            item instanceof TextContent text
                ? new TextContent(textMapper.apply(text.text()))
                : item);
      }
      mapped.add(new Message(message.role(), content));
    }
    return update.toBuilder().messages(mapped).build();
  }

  private static AgentExecutionInterceptor counting(AtomicInteger counter) {
    return (invocation, next) -> {
      counter.incrementAndGet();
      return next.proceed(invocation);
    };
  }

  private static Agent agentWith(
      java.util.function.Consumer<AgentEngineBuilder> configure,
      ModelClient client,
      FunctionTool... tools) {
    AgentEngineBuilder builder = AgentEngine.builder();
    configure.accept(builder);
    ModelClient effective = client == null ? fixed("hi") : client;
    return builder
        .build()
        .factory()
        .builderWithClient(effective)
        .id("agent-1")
        .name("assistant")
        .tools(tools)
        .build();
  }

  private static Agent storeBackedAgent(
      java.util.function.Consumer<AgentEngineBuilder> configure, ModelClient client) {
    AgentEngineBuilder builder = AgentEngine.builder().sessionStore(new RecordingStore());
    configure.accept(builder);
    return builder
        .build()
        .factory()
        .builderWithClient(client)
        .id("agent-1")
        .name("assistant")
        .build();
  }

  private static AgentResponse streamResponse(Agent agent) {
    AgentStreamingRun<AgentResponseUpdate> run = agent.runStreaming("hi");
    drain(run.updates());
    return run.response().toCompletableFuture().join();
  }

  private static void run(Agent agent, AgentSession session, String input) {
    agent.run(request(session, input)).response().toCompletableFuture().join();
  }

  private static AgentRunRequest request(AgentSession session, String input) {
    return AgentRunRequest.builder()
        .messages(Message.normalize(input))
        .session(session)
        .options(new AgentRunOptions())
        .cancellationSignal(new CancellationSignal())
        .attributes(ContextAttributes.empty())
        .build();
  }

  private static AgentRunRequest streamingRequest(AgentSession session, String input) {
    return request(session, input);
  }

  private static void drain(Flow.Publisher<AgentResponseUpdate> publisher) {
    CompletableFuture<Void> done = new CompletableFuture<>();
    publisher.subscribe(
        new Flow.Subscriber<>() {
          @Override
          public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
          }

          @Override
          public void onNext(AgentResponseUpdate item) {}

          @Override
          public void onError(Throwable throwable) {
            done.complete(null);
          }

          @Override
          public void onComplete() {
            done.complete(null);
          }
        });
    done.join();
  }

  private static AgentSession session(String sessionId) {
    return AgentSession.builder().sessionId(sessionId).build();
  }

  private static ModelClient fixed(String text) {
    return request -> EngineModels.of(modelResponse(List.of(assistant(text)), FinishReason.STOP));
  }

  private static ModelClient throwingModel() {
    return request -> {
      throw new AssertionError("model client must not be called");
    };
  }

  private static ModelClient toolThenText(
      String toolName, Map<String, Object> arguments, String finalText) {
    return twoIterations(
        modelResponse(
            List.of(
                new Message(
                    Role.ASSISTANT,
                    List.of(new ToolCallContent("call-1", toolName, jsonObject(arguments))))),
            FinishReason.TOOL_CALLS),
        modelResponse(List.of(assistant(finalText)), FinishReason.STOP));
  }

  private static ModelClient twoIterations(ModelResponse first, ModelResponse second) {
    AtomicInteger calls = new AtomicInteger();
    return request ->
        calls.getAndIncrement() == 0 ? EngineModels.of(first) : EngineModels.of(second);
  }

  private static ModelResponse modelResponse(List<Message> messages, FinishReason finishReason) {
    return ModelResponse.builder()
        .messages(messages)
        .finishReason(finishReason)
        .metadata(JsonObject.empty())
        .build();
  }

  private static Message assistant(String text) {
    return new Message(Role.ASSISTANT, List.of(new TextContent(text)));
  }

  private static AgentResponseUpdate agentUpdate(String text) {
    return AgentResponseUpdate.builder()
        .agentId("agent-1")
        .responseId("short")
        .messages(List.of(assistant(text)))
        .build();
  }

  private static FunctionTool weatherTool() {
    return FunctionTool.create(
        "weather",
        "weather",
        (JsonObject) JsonValues.fromJava(Map.of()),
        (arguments, context) ->
            completedFuture(ToolResult.success(new TextContent("sunny:" + arguments.get("city")))));
  }

  private static FunctionTool echoTool() {
    return FunctionTool.create(
        "echo",
        "echo",
        (JsonObject) JsonValues.fromJava(Map.of()),
        (arguments, context) -> completedFuture(ToolResult.success(new TextContent("echo"))));
  }

  private static JsonObject jsonObject(Map<String, Object> values) {
    return values.isEmpty() ? JsonObject.empty() : (JsonObject) JsonValues.fromJava(values);
  }

  private static List<String> toolResultTexts(AgentResponse response) {
    List<String> texts = new ArrayList<>();
    for (Message message : response.messages()) {
      for (Content content : message.content()) {
        if (content instanceof ToolResultContent result) {
          for (Content payload : result.content()) {
            if (payload instanceof TextContent text) {
              texts.add(text.text());
            }
          }
        }
      }
    }
    return texts;
  }

  private static final class RecordingStore implements SessionStore {

    private final List<String> log = new ArrayList<>();
    private final Map<String, SessionSnapshot> snapshots = new java.util.LinkedHashMap<>();

    private void seed(SessionSnapshot snapshot) {
      snapshots.put(snapshot.sessionId(), snapshot);
    }

    @Override
    public CompletionStage<Optional<SessionSnapshot>> load(String sessionId) {
      log.add("load:" + sessionId);
      return completedFuture(Optional.ofNullable(snapshots.get(sessionId)));
    }

    @Override
    public CompletionStage<Void> save(SessionSnapshot snapshot) {
      log.add("save:" + snapshot.sessionId());
      snapshots.put(snapshot.sessionId(), snapshot);
      return completedFuture(null);
    }

    @Override
    public CompletionStage<Void> delete(String sessionId) {
      log.add("delete:" + sessionId);
      snapshots.remove(sessionId);
      return completedFuture(null);
    }
  }
}
