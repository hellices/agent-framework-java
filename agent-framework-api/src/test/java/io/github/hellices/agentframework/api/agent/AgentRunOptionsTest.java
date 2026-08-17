package io.github.hellices.agentframework.api.agent;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hellices.agentframework.api.context.ContextAttributes;
import io.github.hellices.agentframework.api.context.ContextKey;
import io.github.hellices.agentframework.api.message.FinishReason;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.session.SessionContext;
import io.github.hellices.agentframework.api.tool.ToolContext;
import io.github.hellices.agentframework.spi.model.ModelClient;
import io.github.hellices.agentframework.spi.model.ModelRequest;
import io.github.hellices.agentframework.spi.model.ModelResponse;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class AgentRunOptionsTest {

  @Test
  void attributesUseTypedContextKeys() {
    ContextKey<String> tenant = ContextKey.of("agent", "tenant", String.class);
    ContextAttributes attributes = ContextAttributes.builder().put(tenant, "acme").build();

    AgentRunOptions options = AgentRunOptions.builder().attributes(attributes).build();

    assertThat(options.attributes().get(tenant)).contains("acme");
  }

  @Test
  void rawMapAttributeBridgesAreRemoved() {
    assertThat(findMethod(AgentRunOptions.Builder.class, "attributes", Map.class)).isEmpty();
    assertThat(findConstructor(AgentRunOptions.class, Map.class)).isEmpty();
    assertThat(
            findConstructor(
                AgentRunRequest.class,
                List.class,
                AgentSession.class,
                AgentRunOptions.class,
                CancellationSignal.class,
                Map.class))
        .isEmpty();
    assertThat(findConstructor(AgentRunContext.class, Agent.class, AgentSession.class, Map.class))
        .isEmpty();
    assertThat(
            findConstructor(
                SessionContext.class,
                AgentSession.class,
                List.class,
                Map.class,
                CancellationSignal.class))
        .isEmpty();
    assertThat(findConstructor(ToolContext.class, CancellationSignal.class, Map.class)).isEmpty();
    assertThat(returnType(AgentRunRequest.class, "attributes")).isEqualTo(ContextAttributes.class);
    assertThat(returnType(AgentRunContext.class, "attributes")).isEqualTo(ContextAttributes.class);
    assertThat(returnType(SessionContext.class, "attributes")).isEqualTo(ContextAttributes.class);
    assertThat(returnType(ToolContext.class, "attributes")).isEqualTo(ContextAttributes.class);
  }

  @Test
  void modelClientFactoryReplacesTheDefaultForOneRun() {
    AtomicBoolean originalCalled = new AtomicBoolean();
    AtomicBoolean replacementCalled = new AtomicBoolean();
    ModelClient original =
        request -> {
          originalCalled.set(true);
          return completedFuture(response());
        };
    ModelClient replacement =
        request -> {
          replacementCalled.set(true);
          return completedFuture(response());
        };
    AgentRunOptions options =
        AgentRunOptions.builder().modelClientFactory(ignored -> replacement).build();

    options.resolveModelClient(original).run(ModelRequest.builder().build());

    assertThat(originalCalled).isFalse();
    assertThat(replacementCalled).isTrue();
  }

  @Test
  void absentFactoryKeepsTheDefaultClient() {
    ModelClient original = request -> completedFuture(response());

    assertThat(new AgentRunOptions().resolveModelClient(original)).isSameAs(original);
  }

  @Test
  void factoryCannotReturnNull() {
    AgentRunOptions options = AgentRunOptions.builder().modelClientFactory(ignored -> null).build();

    assertThatThrownBy(() -> options.resolveModelClient(request -> completedFuture(response())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("modelClientFactory must not return null");
  }

  @Test
  void continuationWithNewInputIsRejected() {
    AgentRunOptions options = AgentRunOptions.builder().continuationToken("continuation-1").build();

    assertThatThrownBy(() -> request(Message.normalize("new input"), options))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("continuationToken cannot be combined with input messages");
  }

  @Test
  void continuationWithoutInputIsAccepted() {
    AgentRunOptions options = AgentRunOptions.builder().continuationToken("continuation-1").build();

    assertThat(request(List.of(), options).options().continuationToken())
        .contains("continuation-1");
  }

  @Test
  void blankContinuationTokenIsRejected() {
    assertThatThrownBy(() -> AgentRunOptions.builder().continuationToken(" "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("continuationToken must not be blank");
  }

  private static AgentRunRequest request(
      List<? extends Message> messages, AgentRunOptions options) {
    return AgentRunRequest.builder()
        .messages(messages)
        .options(options)
        .cancellationSignal(new CancellationSignal())
        .attributes(ContextAttributes.empty())
        .build();
  }

  private static ModelResponse response() {
    return ModelResponse.builder().finishReason(FinishReason.STOP).build();
  }

  private static java.util.Optional<Method> findMethod(
      Class<?> type, String name, Class<?>... parameterTypes) {
    try {
      return java.util.Optional.of(type.getDeclaredMethod(name, parameterTypes));
    } catch (NoSuchMethodException missing) {
      return java.util.Optional.empty();
    }
  }

  private static java.util.Optional<Constructor<?>> findConstructor(
      Class<?> type, Class<?>... parameterTypes) {
    try {
      return java.util.Optional.of(type.getDeclaredConstructor(parameterTypes));
    } catch (NoSuchMethodException missing) {
      return java.util.Optional.empty();
    }
  }

  private static Class<?> returnType(Class<?> type, String methodName) {
    try {
      return type.getMethod(methodName).getReturnType();
    } catch (NoSuchMethodException failure) {
      throw new AssertionError(failure);
    }
  }
}
