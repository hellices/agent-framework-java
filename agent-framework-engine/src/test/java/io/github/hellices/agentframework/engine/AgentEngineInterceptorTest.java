package io.github.hellices.agentframework.engine;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hellices.agentframework.api.agent.AgentDefinition;
import io.github.hellices.agentframework.api.agent.AgentResponseUpdate;
import io.github.hellices.agentframework.api.agent.AgentRunRequest;
import io.github.hellices.agentframework.api.agent.AgentSession;
import io.github.hellices.agentframework.api.agent.CancellationSignal;
import io.github.hellices.agentframework.api.context.ContextAttributes;
import io.github.hellices.agentframework.api.message.FinishReason;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.Role;
import io.github.hellices.agentframework.api.message.TextContent;
import io.github.hellices.agentframework.api.message.ToolCallContent;
import io.github.hellices.agentframework.api.session.SessionSnapshot;
import io.github.hellices.agentframework.api.tool.ToolContext;
import io.github.hellices.agentframework.api.tool.ToolDefinition;
import io.github.hellices.agentframework.api.tool.ToolResult;
import io.github.hellices.agentframework.api.value.JsonObject;
import io.github.hellices.agentframework.spi.interception.AgentExecution;
import io.github.hellices.agentframework.spi.interception.AgentExecutionInterceptor;
import io.github.hellices.agentframework.spi.interception.AgentInvocation;
import io.github.hellices.agentframework.spi.interception.ModelInvocation;
import io.github.hellices.agentframework.spi.interception.ModelInvocationInterceptor;
import io.github.hellices.agentframework.spi.interception.SessionInvocation;
import io.github.hellices.agentframework.spi.interception.SessionOperation;
import io.github.hellices.agentframework.spi.interception.SessionOperationInterceptor;
import io.github.hellices.agentframework.spi.interception.SessionOperationResult;
import io.github.hellices.agentframework.spi.interception.ToolInvocation;
import io.github.hellices.agentframework.spi.interception.ToolInvocationInterceptor;
import io.github.hellices.agentframework.spi.model.ModelRequest;
import io.github.hellices.agentframework.spi.model.ModelResponse;
import io.github.hellices.agentframework.spi.model.ModelResponseUpdate;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;

class AgentEngineInterceptorTest {

  @Test
  void agentChainRunsInRegistrationOrderAndAllowsReplacement() {
    List<String> events = new ArrayList<>();
    AgentExecutionInterceptor interceptorA =
        (invocation, next) -> {
          events.add("A-pre");
          AgentExecution execution =
              next.proceed(invocation)
                  .mapUpdates(update -> update.toBuilder().responseId("A").build());
          events.add("A-post");
          return execution;
        };
    AgentExecutionInterceptor interceptorB =
        (invocation, next) -> {
          events.add("B-pre");
          AgentExecution execution =
              next.proceed(invocation)
                  .mapUpdates(update -> update.toBuilder().responseId("B").build());
          events.add("B-post");
          return execution;
        };
    AgentEngine engine =
        AgentEngine.builder()
            .agentExecutionInterceptors(List.of(interceptorA, interceptorB))
            .build();

    AgentExecution execution =
        engine.interceptAgent(
            agentInvocation(),
            invocation -> {
              events.add("handler");
              return AgentExecution.fromUpdate(
                  agentUpdate("handler", "handler"), invocation.cancellationSignal());
            });

    assertThat(events).containsExactly("A-pre", "B-pre", "handler", "B-post", "A-post");
    List<AgentResponseUpdate> updates = consume(execution.updates());
    assertThat(updates).hasSize(1);
    assertThat(updates.get(0).responseId()).isEqualTo("A");
    assertThat(text(updates.get(0))).isEqualTo("handler");
  }

  @Test
  void agentChainShortCircuitsWithoutInvokingLaterInterceptors() {
    List<String> events = new ArrayList<>();
    AgentEngine engine =
        AgentEngine.builder()
            .agentExecutionInterceptor(
                (invocation, next) -> {
                  events.add("A-pre");
                  return AgentExecution.fromUpdate(
                      agentUpdate("short", "short-circuit"), invocation.cancellationSignal());
                })
            .agentExecutionInterceptor(
                (invocation, next) -> {
                  events.add("B-pre");
                  return next.proceed(invocation);
                })
            .build();

    AgentExecution execution =
        engine.interceptAgent(
            agentInvocation(),
            invocation -> {
              events.add("handler");
              return AgentExecution.fromUpdate(
                  agentUpdate("handler", "handler"), invocation.cancellationSignal());
            });

    assertThat(events).containsExactly("A-pre");
    assertThat(consume(execution.updates()))
        .extracting(AgentResponseUpdate::text)
        .containsExactly("short-circuit");
  }

  @Test
  void agentChainRejectsNullExecutionAndPropagatesSynchronousThrow() {
    IllegalStateException boom = new IllegalStateException("boom");
    AgentEngine nullInterceptorEngine =
        AgentEngine.builder().agentExecutionInterceptor((invocation, next) -> null).build();
    AgentEngine throwingEngine =
        AgentEngine.builder()
            .agentExecutionInterceptor(
                (invocation, next) -> {
                  throw boom;
                })
            .build();

    assertThatThrownBy(
            () ->
                nullInterceptorEngine.interceptAgent(
                    agentInvocation(),
                    invocation ->
                        AgentExecution.fromUpdate(
                            agentUpdate("handler", "handler"), invocation.cancellationSignal())))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("agent execution must not be null");
    assertThatThrownBy(
            () ->
                throwingEngine.interceptAgent(
                    agentInvocation(),
                    invocation ->
                        AgentExecution.fromUpdate(
                            agentUpdate("handler", "handler"), invocation.cancellationSignal())))
        .isSameAs(boom);
  }

  @Test
  void modelChainRunsInRegistrationOrderAndAllowsReplacement() {
    List<String> events = new ArrayList<>();
    ModelInvocationInterceptor interceptorA =
        (invocation, next) -> {
          events.add("A-pre");
          Flow.Publisher<ModelResponseUpdate> publisher = modelPublisher("A");
          next.proceed(invocation);
          events.add("A-post");
          return publisher;
        };
    ModelInvocationInterceptor interceptorB =
        (invocation, next) -> {
          events.add("B-pre");
          Flow.Publisher<ModelResponseUpdate> publisher = modelPublisher("B");
          next.proceed(invocation);
          events.add("B-post");
          return publisher;
        };
    AgentEngine engine =
        AgentEngine.builder()
            .modelInvocationInterceptors(List.of(interceptorA, interceptorB))
            .build();

    Flow.Publisher<ModelResponseUpdate> publisher =
        engine.interceptModel(
            modelInvocation(),
            invocation -> {
              events.add("handler");
              return modelPublisher("handler");
            });

    assertThat(events).containsExactly("A-pre", "B-pre", "handler", "B-post", "A-post");
    assertThat(consume(publisher))
        .extracting(AgentEngineInterceptorTest::text)
        .containsExactly("A");
  }

  @Test
  void modelChainShortCircuitsWithoutInvokingLaterInterceptors() {
    List<String> events = new ArrayList<>();
    AgentEngine engine =
        AgentEngine.builder()
            .modelInvocationInterceptor(
                (invocation, next) -> {
                  events.add("A-pre");
                  return modelPublisher("short-circuit");
                })
            .modelInvocationInterceptor(
                (invocation, next) -> {
                  events.add("B-pre");
                  return next.proceed(invocation);
                })
            .build();

    Flow.Publisher<ModelResponseUpdate> publisher =
        engine.interceptModel(
            modelInvocation(),
            invocation -> {
              events.add("handler");
              return modelPublisher("handler");
            });

    assertThat(events).containsExactly("A-pre");
    assertThat(consume(publisher))
        .extracting(AgentEngineInterceptorTest::text)
        .containsExactly("short-circuit");
  }

  @Test
  void modelChainRejectsNullPublisher() {
    AgentEngine engine =
        AgentEngine.builder().modelInvocationInterceptor((invocation, next) -> null).build();

    assertThatThrownBy(
            () -> engine.interceptModel(modelInvocation(), invocation -> modelPublisher("handler")))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("model response publisher must not be null");
  }

  @Test
  void toolChainRunsInRegistrationOrderAndAllowsReplacement() {
    List<String> events = new ArrayList<>();
    ToolInvocationInterceptor interceptorA =
        (invocation, next) -> {
          events.add("A-pre");
          return next.proceed(invocation)
              .thenApply(
                  ignored -> {
                    events.add("A-post");
                    return toolResult("A");
                  });
        };
    ToolInvocationInterceptor interceptorB =
        (invocation, next) -> {
          events.add("B-pre");
          return next.proceed(invocation)
              .thenApply(
                  ignored -> {
                    events.add("B-post");
                    return toolResult("B");
                  });
        };
    AgentEngine engine =
        AgentEngine.builder()
            .toolInvocationInterceptors(List.of(interceptorA, interceptorB))
            .build();

    ToolResult result =
        engine
            .interceptTool(
                toolInvocation(),
                invocation -> {
                  events.add("handler");
                  return completedFuture(toolResult("handler"));
                })
            .toCompletableFuture()
            .join();

    assertThat(events).containsExactly("A-pre", "B-pre", "handler", "B-post", "A-post");
    assertThat(text(result)).isEqualTo("A");
  }

  @Test
  void toolChainShortCircuitsWithoutInvokingLaterInterceptors() {
    List<String> events = new ArrayList<>();
    AgentEngine engine =
        AgentEngine.builder()
            .toolInvocationInterceptor(
                (invocation, next) -> {
                  events.add("A-pre");
                  return completedFuture(toolResult("short-circuit"));
                })
            .toolInvocationInterceptor(
                (invocation, next) -> {
                  events.add("B-pre");
                  return next.proceed(invocation);
                })
            .build();

    ToolResult result =
        engine
            .interceptTool(
                toolInvocation(),
                invocation -> {
                  events.add("handler");
                  return completedFuture(toolResult("handler"));
                })
            .toCompletableFuture()
            .join();

    assertThat(events).containsExactly("A-pre");
    assertThat(text(result)).isEqualTo("short-circuit");
  }

  @Test
  void toolChainRejectsNullStageNullValueAndPropagatesSynchronousThrow() {
    IllegalStateException boom = new IllegalStateException("boom");
    AgentEngine nullStageEngine =
        AgentEngine.builder().toolInvocationInterceptor((invocation, next) -> null).build();
    AgentEngine nullValueEngine =
        AgentEngine.builder()
            .toolInvocationInterceptor((invocation, next) -> completedFuture(null))
            .build();
    AgentEngine throwingEngine =
        AgentEngine.builder()
            .toolInvocationInterceptor(
                (invocation, next) -> {
                  throw boom;
                })
            .build();

    assertThatThrownBy(
            () ->
                nullStageEngine.interceptTool(
                    toolInvocation(), invocation -> completedFuture(toolResult("handler"))))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("tool result stage must not be null");
    assertThatThrownBy(
            () ->
                nullValueEngine
                    .interceptTool(
                        toolInvocation(), invocation -> completedFuture(toolResult("handler")))
                    .toCompletableFuture()
                    .join())
        .hasCauseInstanceOf(NullPointerException.class)
        .hasRootCauseMessage("tool result must not be null");
    assertThatThrownBy(
            () ->
                throwingEngine.interceptTool(
                    toolInvocation(), invocation -> completedFuture(toolResult("handler"))))
        .isSameAs(boom);
  }

  @Test
  void sessionChainRunsInRegistrationOrderAndAllowsReplacement() {
    List<String> events = new ArrayList<>();
    SessionOperationInterceptor interceptorA =
        (invocation, next) -> {
          events.add("A-pre");
          return next.proceed(invocation)
              .thenApply(
                  ignored -> {
                    events.add("A-post");
                    return sessionResult(invocation.session(), 2);
                  });
        };
    SessionOperationInterceptor interceptorB =
        (invocation, next) -> {
          events.add("B-pre");
          return next.proceed(invocation)
              .thenApply(
                  ignored -> {
                    events.add("B-post");
                    return sessionResult(invocation.session(), 1);
                  });
        };
    AgentEngine engine =
        AgentEngine.builder()
            .sessionOperationInterceptors(List.of(interceptorA, interceptorB))
            .build();

    SessionOperationResult result =
        engine
            .interceptSession(
                sessionSaveInvocation(),
                invocation -> {
                  events.add("handler");
                  return completedFuture(sessionResult(invocation.session(), 0));
                })
            .toCompletableFuture()
            .join();

    assertThat(events).containsExactly("A-pre", "B-pre", "handler", "B-post", "A-post");
    assertThat(result.snapshot())
        .hasValueSatisfying(snapshot -> assertThat(snapshot.revision()).isEqualTo(2));
  }

  @Test
  void sessionChainShortCircuitsWithoutInvokingLaterInterceptors() {
    List<String> events = new ArrayList<>();
    AgentSession session = session();
    AgentEngine engine =
        AgentEngine.builder()
            .sessionOperationInterceptor(
                (invocation, next) -> {
                  events.add("A-pre");
                  return completedFuture(
                      SessionOperationResult.builder()
                          .operation(SessionOperation.LOAD)
                          .session(session)
                          .build());
                })
            .sessionOperationInterceptor(
                (invocation, next) -> {
                  events.add("B-pre");
                  return next.proceed(invocation);
                })
            .build();

    SessionOperationResult result =
        engine
            .interceptSession(
                sessionLoadInvocation(),
                invocation -> {
                  events.add("handler");
                  return completedFuture(
                      SessionOperationResult.builder()
                          .operation(SessionOperation.LOAD)
                          .session(session)
                          .build());
                })
            .toCompletableFuture()
            .join();

    assertThat(events).containsExactly("A-pre");
    assertThat(result.operation()).isEqualTo(SessionOperation.LOAD);
  }

  @Test
  void sessionChainRejectsNullStageAndNullValue() {
    AgentEngine nullStageEngine =
        AgentEngine.builder().sessionOperationInterceptor((invocation, next) -> null).build();
    AgentEngine nullValueEngine =
        AgentEngine.builder()
            .sessionOperationInterceptor((invocation, next) -> completedFuture(null))
            .build();

    assertThatThrownBy(
            () ->
                nullStageEngine.interceptSession(
                    sessionLoadInvocation(),
                    invocation ->
                        completedFuture(
                            SessionOperationResult.builder()
                                .operation(SessionOperation.LOAD)
                                .session(invocation.session())
                                .build())))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("session result stage must not be null");
    assertThatThrownBy(
            () ->
                nullValueEngine
                    .interceptSession(
                        sessionLoadInvocation(),
                        invocation ->
                            completedFuture(
                                SessionOperationResult.builder()
                                    .operation(SessionOperation.LOAD)
                                    .session(invocation.session())
                                    .build()))
                    .toCompletableFuture()
                    .join())
        .hasCauseInstanceOf(NullPointerException.class)
        .hasRootCauseMessage("session operation result must not be null");
  }

  @Test
  void builderExposesTypedRegistrationOnlyRejectsNullsAndSnapshotsBuiltEngines() throws Exception {
    assertThat(
            AgentEngineBuilder.class.getDeclaredMethod(
                "agentExecutionInterceptor", AgentExecutionInterceptor.class))
        .isNotNull();
    assertThat(AgentEngineBuilder.class.getDeclaredMethod("agentExecutionInterceptors", List.class))
        .isNotNull();
    assertThat(
            AgentEngineBuilder.class.getDeclaredMethod(
                "modelInvocationInterceptor", ModelInvocationInterceptor.class))
        .isNotNull();
    assertThat(
            AgentEngineBuilder.class.getDeclaredMethod("modelInvocationInterceptors", List.class))
        .isNotNull();
    assertThat(
            AgentEngineBuilder.class.getDeclaredMethod(
                "toolInvocationInterceptor", ToolInvocationInterceptor.class))
        .isNotNull();
    assertThat(AgentEngineBuilder.class.getDeclaredMethod("toolInvocationInterceptors", List.class))
        .isNotNull();
    assertThat(
            AgentEngineBuilder.class.getDeclaredMethod(
                "sessionOperationInterceptor", SessionOperationInterceptor.class))
        .isNotNull();
    assertThat(
            AgentEngineBuilder.class.getDeclaredMethod("sessionOperationInterceptors", List.class))
        .isNotNull();
    assertThat(findMethod(AgentEngineBuilder.class, "interceptor", Object.class)).isNull();
    assertThat(findMethod(AgentEngineBuilder.class, "interceptors", List.class)).isNull();
    assertThat(AgentEngine.class.getDeclaredFields())
        .extracting(Field::getName)
        .contains("interceptorRegistry")
        .doesNotContain("agentDefinition", "runtime", "modelClient");

    assertThatThrownBy(() -> AgentEngine.builder().agentExecutionInterceptor(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("agentExecutionInterceptor must not be null");
    assertThatThrownBy(() -> AgentEngine.builder().modelInvocationInterceptor(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("modelInvocationInterceptor must not be null");
    assertThatThrownBy(() -> AgentEngine.builder().toolInvocationInterceptor(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("toolInvocationInterceptor must not be null");
    assertThatThrownBy(() -> AgentEngine.builder().sessionOperationInterceptor(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("sessionOperationInterceptor must not be null");
    assertThatThrownBy(
            () ->
                AgentEngine.builder()
                    .agentExecutionInterceptors(
                        asAgentInterceptorList(
                            (invocation, next) -> next.proceed(invocation), null)))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("agentExecutionInterceptors[1] must not be null");

    AgentExecutionInterceptor firstInterceptor =
        (invocation, next) ->
            next.proceed(invocation)
                .mapUpdates(update -> update.toBuilder().responseId("first").build());
    AgentExecutionInterceptor secondInterceptor =
        (invocation, next) ->
            AgentExecution.fromUpdate(
                agentUpdate("second", "second"), invocation.cancellationSignal());
    AgentEngineBuilder builder = AgentEngine.builder().agentExecutionInterceptor(firstInterceptor);
    AgentEngine first = builder.build();
    builder.agentExecutionInterceptor(secondInterceptor);
    AgentEngine second = builder.build();

    assertThat(
            consume(
                first
                    .interceptAgent(
                        agentInvocation(),
                        invocation ->
                            AgentExecution.fromUpdate(
                                agentUpdate("handler", "handler"), invocation.cancellationSignal()))
                    .updates()))
        .extracting(AgentResponseUpdate::text)
        .containsExactly("handler");
    assertThat(
            consume(
                second
                    .interceptAgent(
                        agentInvocation(),
                        invocation ->
                            AgentExecution.fromUpdate(
                                agentUpdate("handler", "handler"), invocation.cancellationSignal()))
                    .updates()))
        .extracting(AgentResponseUpdate::text)
        .containsExactly("second");
  }

  private static AgentInvocation agentInvocation() {
    return AgentInvocation.builder()
        .agentDefinition(AgentDefinition.builder().name("agent").build())
        .request(AgentRunRequest.empty())
        .build();
  }

  private static ModelInvocation modelInvocation() {
    return ModelInvocation.builder()
        .agentId("agent-1")
        .sessionId("session-1")
        .request(ModelRequest.builder().build())
        .build();
  }

  private static ToolInvocation toolInvocation() {
    return ToolInvocation.builder()
        .toolCall(new ToolCallContent("call-1", "weather", JsonObject.empty()))
        .toolDefinition(ToolDefinition.builder().name("weather").build())
        .context(new ToolContext(new CancellationSignal(), ContextAttributes.empty()))
        .build();
  }

  private static SessionInvocation sessionLoadInvocation() {
    return SessionInvocation.builder()
        .operation(SessionOperation.LOAD)
        .session(session())
        .cancellationSignal(new CancellationSignal())
        .build();
  }

  private static SessionInvocation sessionSaveInvocation() {
    return SessionInvocation.builder()
        .operation(SessionOperation.SAVE)
        .session(session())
        .snapshot(snapshot(0))
        .cancellationSignal(new CancellationSignal())
        .build();
  }

  private static AgentSession session() {
    return AgentSession.builder().sessionId("session-1").build();
  }

  private static SessionOperationResult sessionResult(AgentSession session, int revision) {
    return SessionOperationResult.builder()
        .operation(SessionOperation.SAVE)
        .session(session)
        .snapshot(snapshot(revision))
        .build();
  }

  private static SessionSnapshot snapshot(int revision) {
    return new SessionSnapshot(
        "agent-session",
        "1",
        "session-1",
        null,
        revision,
        Instant.parse("2026-08-18T00:00:00Z"),
        Map.of());
  }

  private static Flow.Publisher<ModelResponseUpdate> modelPublisher(String text) {
    return EngineModels.of(modelResponse(text));
  }

  private static ModelResponse modelResponse(String text) {
    return ModelResponse.builder()
        .messages(List.of(assistant(text)))
        .finishReason(FinishReason.STOP)
        .build();
  }

  private static AgentResponseUpdate agentUpdate(String responseId, String text) {
    return AgentResponseUpdate.builder()
        .agentId("agent-1")
        .responseId(responseId)
        .messages(List.of(assistant(text)))
        .build();
  }

  private static Message assistant(String text) {
    return new Message(Role.ASSISTANT, List.of(new TextContent(text)));
  }

  private static List<AgentExecutionInterceptor> asAgentInterceptorList(
      AgentExecutionInterceptor... interceptors) {
    List<AgentExecutionInterceptor> values = new ArrayList<>();
    for (AgentExecutionInterceptor interceptor : interceptors) {
      values.add(interceptor);
    }
    return values;
  }

  private static ToolResult toolResult(String text) {
    return ToolResult.success(new TextContent(text));
  }

  private static String text(AgentResponseUpdate update) {
    return update.text();
  }

  private static String text(ToolResult result) {
    return ((TextContent) result.content().get(0)).text();
  }

  private static String text(ModelResponseUpdate update) {
    StringBuilder builder = new StringBuilder();
    for (Message message : update.messages()) {
      builder.append(message.text());
    }
    return builder.toString();
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
    completion.toCompletableFuture().join();
    return List.copyOf(values);
  }

  private static Method findMethod(Class<?> type, String name, Class<?> parameterType) {
    try {
      return type.getDeclaredMethod(name, parameterType);
    } catch (NoSuchMethodException ignored) {
      return null;
    }
  }
}
