package io.github.hellices.agentframework.spi.interception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hellices.agentframework.api.agent.AgentDefinition;
import io.github.hellices.agentframework.api.agent.AgentResponse;
import io.github.hellices.agentframework.api.agent.AgentRunRequest;
import io.github.hellices.agentframework.api.agent.AgentSession;
import io.github.hellices.agentframework.api.agent.CancellationSignal;
import io.github.hellices.agentframework.api.context.ContextAttributes;
import io.github.hellices.agentframework.api.context.ContextKey;
import io.github.hellices.agentframework.api.message.ToolCallContent;
import io.github.hellices.agentframework.api.session.SessionSnapshot;
import io.github.hellices.agentframework.api.session.SessionStateEntry;
import io.github.hellices.agentframework.api.tool.ToolArguments;
import io.github.hellices.agentframework.api.tool.ToolContext;
import io.github.hellices.agentframework.api.tool.ToolDefinition;
import io.github.hellices.agentframework.api.tool.ToolResult;
import io.github.hellices.agentframework.api.value.JsonObject;
import io.github.hellices.agentframework.api.value.JsonString;
import io.github.hellices.agentframework.spi.model.ModelRequest;
import io.github.hellices.agentframework.spi.model.ModelResponseUpdate;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InterceptorContractTest {

  @Test
  void exposesFourSeparateTypedInterceptorSeams() throws Exception {
    assertInterceptorShape(
        AgentExecutionInterceptor.class,
        "intercept",
        "java.util.concurrent.CompletionStage<" + AgentResponse.class.getName() + ">",
        AgentInvocation.class,
        AgentInvocationChain.class);
    assertInterceptorShape(
        ModelInvocationInterceptor.class,
        "intercept",
        "java.util.concurrent.Flow$Publisher<" + ModelResponseUpdate.class.getName() + ">",
        ModelInvocation.class,
        ModelInvocationChain.class);
    assertInterceptorShape(
        ToolInvocationInterceptor.class,
        "intercept",
        "java.util.concurrent.CompletionStage<" + ToolResult.class.getName() + ">",
        ToolInvocation.class,
        ToolInvocationChain.class);
    assertInterceptorShape(
        SessionOperationInterceptor.class,
        "intercept",
        "java.util.concurrent.CompletionStage<" + SessionOperationResult.class.getName() + ">",
        SessionInvocation.class,
        SessionInvocationChain.class);

    assertChainShape(
        AgentInvocationChain.class,
        "java.util.concurrent.CompletionStage<" + AgentResponse.class.getName() + ">",
        AgentInvocation.class);
    assertChainShape(
        ModelInvocationChain.class,
        "java.util.concurrent.Flow$Publisher<" + ModelResponseUpdate.class.getName() + ">",
        ModelInvocation.class);
    assertChainShape(
        ToolInvocationChain.class,
        "java.util.concurrent.CompletionStage<" + ToolResult.class.getName() + ">",
        ToolInvocation.class);
    assertChainShape(
        SessionInvocationChain.class,
        "java.util.concurrent.CompletionStage<" + SessionOperationResult.class.getName() + ">",
        SessionInvocation.class);

    assertThat(
            List.of(
                AgentInvocation.class,
                ModelInvocation.class,
                ToolInvocation.class,
                SessionInvocation.class))
        .allSatisfy(type -> assertThat(type.getInterfaces()).isEmpty())
        .allSatisfy(type -> assertThat(type.getSuperclass()).isEqualTo(Object.class));
  }

  @Test
  void invocationValuesAreImmutableAndEvolvable() {
    AgentDefinition definition = AgentDefinition.builder().name("agent").build();
    AgentRunRequest request = AgentRunRequest.of("hello");
    ContextAttributes agentAttributes = attributes("agent", "trace", "run-1");
    ContextAttributes updatedAgentAttributes = attributes("agent", "trace", "run-2");
    CancellationSignal cancellationSignal = new CancellationSignal();

    AgentInvocation agentInvocation =
        AgentInvocation.builder()
            .agentDefinition(definition)
            .request(request)
            .effectiveAttributes(agentAttributes)
            .cancellationSignal(cancellationSignal)
            .build();
    AgentInvocation updatedAgentInvocation =
        agentInvocation.toBuilder().effectiveAttributes(updatedAgentAttributes).build();

    assertThat(agentInvocation.agentDefinition()).isEqualTo(definition);
    assertThat(agentInvocation.request()).isEqualTo(request);
    assertThat(agentInvocation.effectiveAttributes()).isEqualTo(agentAttributes);
    assertThat(agentInvocation.cancellationSignal()).isSameAs(cancellationSignal);
    assertThat(updatedAgentInvocation.effectiveAttributes()).isEqualTo(updatedAgentAttributes);
    assertThat(agentInvocation.effectiveAttributes()).isEqualTo(agentAttributes);

    ModelRequest modelRequest =
        ModelRequest.builder()
            .attributes(attributes("model", "trace", "model-1"))
            .cancellationSignal(cancellationSignal)
            .build();
    ModelInvocation modelInvocation =
        ModelInvocation.builder()
            .agentId("agent-1")
            .sessionId("session-1")
            .request(modelRequest)
            .build();
    ModelInvocation updatedModelInvocation =
        modelInvocation.toBuilder().sessionId("session-2").build();

    assertThat(modelInvocation.agentId()).isEqualTo("agent-1");
    assertThat(modelInvocation.sessionId()).contains("session-1");
    assertThat(modelInvocation.request()).isEqualTo(modelRequest);
    assertThat(modelInvocation.cancellationSignal()).isSameAs(cancellationSignal);
    assertThat(updatedModelInvocation.sessionId()).contains("session-2");
    assertThat(modelInvocation.sessionId()).contains("session-1");

    JsonObject argumentsJson = JsonObject.builder().put("city", JsonString.of("Seoul")).build();
    ToolCallContent toolCall = new ToolCallContent("call-1", "weather", argumentsJson);
    ToolDefinition toolDefinition = ToolDefinition.builder().name("weather").build();
    ToolArguments arguments = ToolArguments.of(argumentsJson);
    ToolContext toolContext =
        new ToolContext(cancellationSignal, attributes("tool", "trace", "tool-1"));
    ToolInvocation toolInvocation =
        ToolInvocation.builder()
            .toolCall(toolCall)
            .toolDefinition(toolDefinition)
            .arguments(arguments)
            .context(toolContext)
            .build();
    ToolInvocation updatedToolInvocation =
        toolInvocation.toBuilder()
            .arguments(
                ToolArguments.of(JsonObject.builder().put("city", JsonString.of("Busan")).build()))
            .build();

    assertThat(toolInvocation.toolCall()).isEqualTo(toolCall);
    assertThat(toolInvocation.toolDefinition()).isEqualTo(toolDefinition);
    assertThat(toolInvocation.arguments()).isEqualTo(arguments);
    assertThat(toolInvocation.context()).isEqualTo(toolContext);
    assertThat(updatedToolInvocation.arguments().string("city")).contains("Busan");
    assertThat(toolInvocation.arguments().string("city")).contains("Seoul");

    AgentSession session = AgentSession.builder().sessionId("session-1").build();
    SessionSnapshot snapshot =
        new SessionSnapshot(
            "agent-session",
            "1",
            "session-1",
            null,
            1,
            Instant.parse("2026-08-18T00:00:00Z"),
            Map.of("history", new SessionStateEntry("history", 1, Map.of("count", "1"))));
    SessionInvocation sessionInvocation =
        SessionInvocation.builder()
            .operation(SessionOperation.LOAD)
            .session(session)
            .attributes(attributes("session", "trace", "load-1"))
            .cancellationSignal(cancellationSignal)
            .build();
    SessionInvocation updatedSessionInvocation =
        sessionInvocation.toBuilder().operation(SessionOperation.SAVE).snapshot(snapshot).build();

    assertThat(sessionInvocation.operation()).isEqualTo(SessionOperation.LOAD);
    assertThat(sessionInvocation.session()).isEqualTo(session);
    assertThat(sessionInvocation.snapshot()).isEmpty();
    assertThat(updatedSessionInvocation.operation()).isEqualTo(SessionOperation.SAVE);
    assertThat(updatedSessionInvocation.snapshot()).contains(snapshot);
    assertThat(sessionInvocation.operation()).isEqualTo(SessionOperation.LOAD);

    SessionOperationResult result =
        SessionOperationResult.builder()
            .operation(SessionOperation.SAVE)
            .session(session)
            .snapshot(snapshot)
            .build();
    SessionOperationResult updatedResult =
        result.toBuilder()
            .snapshot(
                new SessionSnapshot(
                    "agent-session",
                    "1",
                    "session-1",
                    null,
                    2,
                    Instant.parse("2026-08-18T00:01:00Z"),
                    Map.of()))
            .build();

    assertThat(result.operation()).isEqualTo(SessionOperation.SAVE);
    assertThat(result.snapshot()).contains(snapshot);
    assertThat(updatedResult.snapshot())
        .hasValueSatisfying(value -> assertThat(value.revision()).isEqualTo(2));
    assertThat(result.snapshot()).contains(snapshot);
  }

  @Test
  void buildersRejectNullRequiredInputsAndFrameworkLeakage() {
    assertThatThrownBy(() -> AgentInvocation.builder().build())
        .isInstanceOf(NullPointerException.class)
        .hasMessage("agentDefinition must not be null");
    assertThatThrownBy(() -> ModelInvocation.builder().build())
        .isInstanceOf(NullPointerException.class)
        .hasMessage("agentId must not be null");
    assertThatThrownBy(() -> ToolInvocation.builder().build())
        .isInstanceOf(NullPointerException.class)
        .hasMessage("toolCall must not be null");
    assertThatThrownBy(() -> SessionInvocation.builder().build())
        .isInstanceOf(NullPointerException.class)
        .hasMessage("operation must not be null");
    assertThatThrownBy(() -> SessionOperationResult.builder().build())
        .isInstanceOf(NullPointerException.class)
        .hasMessage("operation must not be null");

    assertNoForbiddenTypes(AgentInvocation.class);
    assertNoForbiddenTypes(ModelInvocation.class);
    assertNoForbiddenTypes(ToolInvocation.class);
    assertNoForbiddenTypes(SessionInvocation.class);
    assertNoForbiddenTypes(SessionOperationResult.class);
  }

  private static void assertInterceptorShape(
      Class<?> type,
      String methodName,
      String expectedReturnType,
      Class<?> expectedInvocationType,
      Class<?> expectedChainType)
      throws Exception {
    assertThat(type.isInterface()).isTrue();
    assertThat(type.getInterfaces()).isEmpty();
    Method method = type.getDeclaredMethod(methodName, expectedInvocationType, expectedChainType);
    assertThat(method.getGenericReturnType().getTypeName()).isEqualTo(expectedReturnType);
    assertThat(type.getDeclaredMethods()).containsExactly(method);
  }

  private static void assertChainShape(
      Class<?> chainType, String expectedReturnType, Class<?> expectedInvocationType)
      throws Exception {
    assertThat(chainType.isInterface()).isTrue();
    Method proceed = chainType.getDeclaredMethod("proceed", expectedInvocationType);
    assertThat(proceed.getGenericReturnType().getTypeName()).isEqualTo(expectedReturnType);
    assertThat(chainType.getDeclaredMethods()).containsExactly(proceed);
  }

  private static ContextAttributes attributes(String namespace, String name, String value) {
    return ContextAttributes.builder()
        .put(ContextKey.of(namespace, name, String.class), value)
        .build();
  }

  private static void assertNoForbiddenTypes(Class<?> type) {
    assertThat(Modifier.isFinal(type.getModifiers())).isTrue();
    for (Field field : type.getDeclaredFields()) {
      assertThat(Modifier.isPrivate(field.getModifiers())).isTrue();
      assertThat(Modifier.isFinal(field.getModifiers())).isTrue();
      assertTypeAllowed(field.getGenericType());
    }
    for (Method method : type.getDeclaredMethods()) {
      if (!Modifier.isPublic(method.getModifiers())) {
        continue;
      }
      assertThat(method.getName()).doesNotStartWith("set");
      assertTypeAllowed(method.getGenericReturnType());
      for (Type parameterType : method.getGenericParameterTypes()) {
        assertTypeAllowed(parameterType);
      }
    }
  }

  private static void assertTypeAllowed(Type type) {
    String typeName = type.getTypeName();
    assertThat(typeName).doesNotContain("java.util.Map");
    assertThat(typeName).doesNotContain("java.lang.ThreadLocal");
    assertThat(typeName).doesNotContain("org.springframework");
    assertThat(typeName).doesNotContain("jakarta.");
    assertThat(typeName).doesNotContain("javax.");
    assertThat(typeName).doesNotContain("io.micrometer");
    assertThat(typeName).doesNotContain("io.opentelemetry");
  }
}
