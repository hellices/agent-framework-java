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
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

/**
 * Pins the cancellation contract of the public adapter, with a real {@link CancellationSignal} on a
 * real {@link ModelRequest}.
 *
 * <p>Every wait here is bounded. The provider stand-in deliberately withholds its answer, so an
 * unbounded {@code join()} would hang the build rather than fail it in exactly the regressions this
 * class exists to catch - a cancellation that stops completing the run, or a pre-dispatch
 * short-circuit that stops firing. The bound is a failure detector and not a race window: {@code
 * CancellationSignal.cancel()} runs its listeners inline on the calling thread and the fake answers
 * on the caller's thread too, so every outcome asserted here is already settled before the wait
 * begins. Nothing in this class sleeps.
 *
 * <p>Failures are read through {@code get}, which reports a failed run as an {@link
 * ExecutionException} around the cause the adapter completed with, on every JDK this project builds
 * on.
 */
class OpenAiChatModelClientCancellationTest {

  private static final long BOUND_SECONDS = 5L;

  @Test
  void neverCallsTheProviderWhenTheRunIsAlreadyCancelled() {
    FakeChatCompletionsOperations operations = new FakeChatCompletionsOperations();
    ModelClient client = client(operations);
    CancellationSignal signal = new CancellationSignal();
    signal.cancel();

    CompletionStage<ModelResponse> stage = client.run(request(signal));

    // The message is the half that distinguishes this path. Without the pre-dispatch short-circuit
    // the run would still fail with a CancellationException and would still never reach the
    // provider - an already-cancelled signal runs the listener inline at registration, before the
    // dispatch - so only the message tells "refused before dispatch" apart from "cancelled while
    // in flight". Asserting both halves is what makes deleting the short-circuit fail here.
    assertThat(operations.invocations()).isZero();
    assertThatThrownBy(() -> boundedOutcomeOf(stage))
        .isInstanceOf(ExecutionException.class)
        .cause()
        .isInstanceOf(CancellationException.class)
        .hasMessage("model call was cancelled before it was dispatched");
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

    // Bounded: an adapter whose cancellation stopped completing the run leaves this stage pending
    // for ever, and the wait has to end in a failed assertion rather than in a hung build.
    assertThatThrownBy(() -> boundedOutcomeOf(stage))
        .isInstanceOf(ExecutionException.class)
        .cause()
        .isInstanceOf(CancellationException.class)
        .hasMessage("model call was cancelled");
    assertThat(inFlight).isNotDone();
  }

  @Test
  void keepsTheDeliveredAnswerWhenACancellationArrivesAfterTheRunFinished() throws Exception {
    // What this pins is the immutability of a delivered outcome, not the removal of the listener.
    // A public CancellationSignal cannot report whether a listener was deregistered, and this test
    // could not tell the difference: once the run has answered, write-once completion semantics
    // reject a later cancellation whether the listener was removed or merely ran and lost. Listener
    // removal is pinned where it is observable - the Cancellation seam in
    // OpenAiCallBridgeTest.removesTheCancellationListenerOnEveryCompletionPath (Task 7).
    CompletableFuture<ChatCompletion> inFlight = new CompletableFuture<>();
    FakeChatCompletionsOperations operations =
        new FakeChatCompletionsOperations().withholding(inFlight);
    ModelClient client = client(operations);
    CancellationSignal signal = new CancellationSignal();

    CompletionStage<ModelResponse> stage = client.run(request(signal));
    inFlight.complete(completion("finished"));
    ModelResponse response = boundedOutcomeOf(stage);
    signal.cancel();

    assertThat(response.messages().get(0).text()).isEqualTo("finished");
    assertThat(boundedOutcomeOf(stage)).isSameAs(response);
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

    assertThatThrownBy(() -> boundedOutcomeOf(stage))
        .isInstanceOf(ExecutionException.class)
        .cause()
        .isInstanceOf(CancellationException.class)
        .hasMessage("model call was cancelled");
  }

  @Test
  void keepsTheCancellationWhenTheProviderFailsAfterwards() {
    // The abandoned request is not aborted, so it can fail as easily as it can answer: a socket the
    // SDK gives up on, a 5xx, a per-attempt timeout. The cancellation stays the run's outcome. A
    // caller that asked to stop must not be handed a provider error to interpret, retry, or report
    // as the reason the run ended, and a substituted provider failure would also make the outcome
    // depend on which of two abandoned events happened to land first.
    //
    // The provider failure is dropped rather than attached with addSuppressed, deliberately: the
    // failure of a call nobody is waiting for any more is not part of this run's answer, and
    // suppression would put a payload-carrying provider exception on a path a caller only ever
    // inspects for cancellation. It is unobservable by design, not by oversight - assert that here
    // so a later "helpful" addSuppressed is a deliberate contract change rather than a silent one.
    CompletableFuture<ChatCompletion> inFlight = new CompletableFuture<>();
    FakeChatCompletionsOperations operations =
        new FakeChatCompletionsOperations().withholding(inFlight);
    ModelClient client = client(operations);
    CancellationSignal signal = new CancellationSignal();

    CompletionStage<ModelResponse> stage = client.run(request(signal));
    signal.cancel();
    inFlight.completeExceptionally(new IllegalStateException("provider failed after the cancel"));

    assertThat(operations.invocations()).isEqualTo(1);
    assertThatThrownBy(() -> boundedOutcomeOf(stage))
        .isInstanceOf(ExecutionException.class)
        .cause()
        .isInstanceOf(CancellationException.class)
        .hasMessage("model call was cancelled")
        .hasNoCause()
        .hasNoSuppressedExceptions();
  }

  private static ModelResponse boundedOutcomeOf(CompletionStage<ModelResponse> stage)
      throws InterruptedException, ExecutionException, TimeoutException {
    return stage.toCompletableFuture().get(BOUND_SECONDS, TimeUnit.SECONDS);
  }

  private static ModelClient client(FakeChatCompletionsOperations operations) {
    return OpenAiChatModelClient.builder().operations(operations).model("gpt-4.1-mini").build();
  }

  private static ModelRequest request(CancellationSignal signal) {
    return ModelRequest.builder()
        .messages(List.of(new Message(Role.USER, List.of(new TextContent("hi")))))
        .options(ModelRequestOptions.empty())
        .cancellationSignal(signal)
        .build();
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
