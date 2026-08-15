package io.github.hellices.agentframework.api.agent;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hellices.agentframework.api.message.FinishReason;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.spi.model.ModelClient;
import io.github.hellices.agentframework.spi.model.ModelRequest;
import io.github.hellices.agentframework.spi.model.ModelResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class AgentRunOptionsTest {

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

    options.resolveModelClient(original).run(new ModelRequest(List.of(), null, Map.of()));

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
    assertThatThrownBy(() -> AgentRunOptions.builder().continuationToken(" ").build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("continuationToken must not be blank");
  }

  private static AgentRunRequest request(
      List<? extends Message> messages, AgentRunOptions options) {
    return new AgentRunRequest(messages, null, options, new CancellationSignal(), Map.of());
  }

  private static ModelResponse response() {
    return new ModelResponse(List.of(), null, FinishReason.STOP, Map.of(), null);
  }
}
