package io.github.hellices.agentframework.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionMessage;
import io.github.hellices.agentframework.api.agent.CancellationSignal;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.Role;
import io.github.hellices.agentframework.api.message.TextContent;
import io.github.hellices.agentframework.spi.model.ModelClient;
import io.github.hellices.agentframework.spi.model.ModelRequest;
import io.github.hellices.agentframework.spi.model.ModelRequestOptions;
import io.github.hellices.agentframework.spi.model.ModelResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

class OpenAiChatModelClientCancellationTest {

  @Test
  void neverCallsTheProviderWhenTheRunIsAlreadyCancelled() {
    FakeChatCompletionsOperations operations = new FakeChatCompletionsOperations();
    ModelClient client = client(operations);
    CancellationSignal signal = new CancellationSignal();
    signal.cancel();

    CompletionStage<ModelResponse> stage = client.run(request(signal));

    assertThat(operations.invocations()).isZero();
    assertThatThrownBy(() -> stage.toCompletableFuture().join())
        .isInstanceOf(CompletionException.class)
        .hasCauseInstanceOf(CancellationException.class);
  }

  @Test
  void failsPromptlyWhenTheRunIsCancelledAfterDispatchWithoutAbortingTheRequest() {
    // The name says what this slice can and cannot do. The framework stops waiting immediately; the
    // HTTP request is not aborted, because the SDK derives the future it returns from its transport
    // future and a JDK CompletableFuture never cancels its antecedent. Do not "fix" this test by
    // asserting the in-flight call was cancelled; fix the SDK seam first or change nothing.
    CompletableFuture<ChatCompletion> inFlight = new CompletableFuture<>();
    FakeChatCompletionsOperations operations =
        new FakeChatCompletionsOperations().withholding(inFlight);
    ModelClient client = client(operations);
    CancellationSignal signal = new CancellationSignal();

    CompletionStage<ModelResponse> stage = client.run(request(signal));
    assertThat(operations.invocations()).isEqualTo(1);
    signal.cancel();

    assertThatThrownBy(() -> stage.toCompletableFuture().join())
        .isInstanceOf(CompletionException.class)
        .hasCauseInstanceOf(CancellationException.class);
    assertThat(inFlight).isNotDone();
  }

  @Test
  void ignoresACancellationThatArrivesAfterTheRunFinished() {
    // The listener is removed on completion, so a late cancel is a no-op rather than a failure that
    // overwrites a delivered answer.
    CompletableFuture<ChatCompletion> inFlight = new CompletableFuture<>();
    FakeChatCompletionsOperations operations =
        new FakeChatCompletionsOperations().withholding(inFlight);
    ModelClient client = client(operations);
    CancellationSignal signal = new CancellationSignal();

    CompletionStage<ModelResponse> stage = client.run(request(signal));
    inFlight.complete(completion("finished"));
    ModelResponse response = stage.toCompletableFuture().join();
    signal.cancel();

    assertThat(response.messages().get(0).text()).isEqualTo("finished");
    assertThat(stage.toCompletableFuture().join()).isSameAs(response);
  }

  @Test
  void keepsTheCancellationWhenTheProviderAnswersAfterwards() {
    // The other side of the race. A cancelled run stays cancelled even though the abandoned request
    // eventually completes, which is exactly what happens in production because it is not aborted.
    CompletableFuture<ChatCompletion> inFlight = new CompletableFuture<>();
    FakeChatCompletionsOperations operations =
        new FakeChatCompletionsOperations().withholding(inFlight);
    ModelClient client = client(operations);
    CancellationSignal signal = new CancellationSignal();

    CompletionStage<ModelResponse> stage = client.run(request(signal));
    signal.cancel();
    inFlight.complete(completion("too late"));

    assertThatThrownBy(() -> stage.toCompletableFuture().join())
        .isInstanceOf(CompletionException.class)
        .hasCauseInstanceOf(CancellationException.class);
  }

  private static ModelClient client(FakeChatCompletionsOperations operations) {
    return OpenAiChatModelClient.builder().operations(operations).model("gpt-4.1-mini").build();
  }

  private static ModelRequest request(CancellationSignal signal) {
    return new ModelRequest(
        List.of(new Message(Role.USER, List.of(new TextContent("hi")))),
        ModelRequestOptions.empty(),
        signal,
        List.of(),
        Map.of());
  }

  private static ChatCompletion completion(String text) {
    ChatCompletionMessage message =
        ChatCompletionMessage.builder().content(text).refusal((String) null).build();
    return ChatCompletion.builder()
        .id("chatcmpl-test")
        .created(1_700_000_000L)
        .model("gpt-4.1-mini")
        .addChoice(
            ChatCompletion.Choice.builder()
                .finishReason(ChatCompletion.Choice.FinishReason.STOP)
                .index(0L)
                .logprobs((ChatCompletion.Choice.Logprobs) null)
                .message(message)
                .build())
        .build();
  }
}
