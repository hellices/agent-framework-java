package io.github.hellices.agentframework.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openai.client.OpenAIClientAsync;
import com.openai.core.RequestOptions;
import com.openai.errors.OpenAIIoException;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.services.async.ChatServiceAsync;
import com.openai.services.async.chat.ChatCompletionServiceAsync;
import io.github.hellices.agentframework.api.agent.CancellationSignal;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.Role;
import io.github.hellices.agentframework.api.message.TextContent;
import io.github.hellices.agentframework.openai.internal.OpenAiCallBridge;
import io.github.hellices.agentframework.openai.internal.SdkChatCompletionsOperations;
import io.github.hellices.agentframework.spi.model.ModelClient;
import io.github.hellices.agentframework.spi.model.ModelRequest;
import io.github.hellices.agentframework.spi.model.ModelRequestOptions;
import io.github.hellices.agentframework.spi.model.ModelResponse;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

class OpenAiChatModelClientTest {

  @Test
  void sendsTheMappedRequestAndReturnsTheMappedResponse() {
    FakeChatCompletionsOperations operations =
        new FakeChatCompletionsOperations().answering(completion("hello there"));
    ModelClient client = clientOver(operations, builder -> builder.model("gpt-4.1-mini"));

    ModelResponse response = client.run(request("hi")).toCompletableFuture().join();

    assertThat(operations.requests().get(0).model().asString()).isEqualTo("gpt-4.1-mini");
    assertThat(operations.requests().get(0).messages().get(0).asUser().content().asText())
        .isEqualTo("hi");
    assertThat(response.messages().get(0).text()).isEqualTo("hello there");
  }

  @Test
  void dispatchesNothingWhenTheClientIsBuilt() {
    // The adapter borrows a client; building one must not open a connection, start a thread, or
    // send a request, because the host decides when the client is used and when it is closed.
    FakeChatCompletionsOperations operations = new FakeChatCompletionsOperations();

    clientOver(operations, builder -> builder.model("gpt-4.1-mini"));

    assertThat(operations.invocations()).isZero();
  }

  @Test
  void appliesTheConfiguredRequestTimeoutToEveryCall() {
    FakeChatCompletionsOperations operations =
        new FakeChatCompletionsOperations().answering(completion("hi")).answering(completion("ho"));
    ModelClient client =
        clientOver(
            operations,
            builder -> builder.model("gpt-4.1-mini").requestTimeout(Duration.ofSeconds(45)));

    client.run(request("hi")).toCompletableFuture().join();
    client.run(request("again")).toCompletableFuture().join();

    assertThat(operations.requestOptions())
        .allSatisfy(
            options ->
                assertThat(options.getTimeout().request()).isEqualTo(Duration.ofSeconds(45)));
  }

  @Test
  void defaultsTheRequestTimeoutToSixtySeconds() {
    FakeChatCompletionsOperations operations =
        new FakeChatCompletionsOperations().answering(completion("hi"));
    ModelClient client = clientOver(operations, builder -> builder.model("gpt-4.1-mini"));

    client.run(request("hi")).toCompletableFuture().join();

    assertThat(operations.requestOptions().get(0).getTimeout().request())
        .isEqualTo(Duration.ofSeconds(60));
  }

  @Test
  void carriesTheBuilderDefaultsOntoTheRequestItSends() {
    // Without these, the builder would accept a temperature and a token limit and send neither,
    // which no other test would notice: the mapper reads them off the settings, not off the client.
    FakeChatCompletionsOperations operations =
        new FakeChatCompletionsOperations().answering(completion("hi"));
    ModelClient client =
        clientOver(
            operations,
            builder -> builder.model("gpt-4.1-mini").temperature(0.25).maxOutputTokens(128));

    client.run(request("hi")).toCompletableFuture().join();

    assertThat(operations.requests().get(0).temperature()).hasValue(0.25);
    assertThat(operations.requests().get(0).maxCompletionTokens()).hasValue(128L);
  }

  @Test
  void preservesTheProviderFailureAsTheCause() {
    OpenAIIoException failure = new OpenAIIoException("connection reset");
    FakeChatCompletionsOperations operations =
        new FakeChatCompletionsOperations().failingWith(failure);
    ModelClient client = clientOver(operations, builder -> builder.model("gpt-4.1-mini"));

    assertThatThrownBy(() -> client.run(request("hi")).toCompletableFuture().join())
        .isInstanceOf(CompletionException.class)
        .satisfies(thrown -> assertThat(thrown.getCause()).isSameAs(failure));
  }

  @Test
  void deliversAMappingFailureThroughTheStageRatherThanThrowing() {
    FakeChatCompletionsOperations operations = new FakeChatCompletionsOperations();
    ModelClient client = clientOver(operations, builder -> builder.model("gpt-4.1-mini"));
    ModelRequest unmappable =
        new ModelRequest(
            List.of(new Message(Role.of("auditor"), List.of(new TextContent("hi")))),
            ModelRequestOptions.empty(),
            new CancellationSignal(),
            List.of(),
            Map.of());

    var stage = client.run(unmappable).toCompletableFuture();

    assertThat(operations.invocations()).isZero();
    assertThatThrownBy(stage::join)
        .isInstanceOf(CompletionException.class)
        .hasCauseInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void deliversAHistoryTheProviderWouldRejectThroughTheStageWithoutSendingIt() {
    // The two shapes Chat Completions has no message for: a tool turn with no result to report and
    // an assistant turn with neither text nor a tool call. Both fail before the call is dispatched,
    // so the adapter never spends a billed request on a payload the provider answers with a 400.
    FakeChatCompletionsOperations operations = new FakeChatCompletionsOperations();
    ModelClient client = clientOver(operations, builder -> builder.model("gpt-4.1-mini"));

    var emptyToolTurn =
        client.run(historyOf(new Message(Role.TOOL, List.of()))).toCompletableFuture();
    var emptyAssistantTurn =
        client.run(historyOf(new Message(Role.ASSISTANT, List.of()))).toCompletableFuture();

    assertThat(operations.invocations()).isZero();
    assertThatThrownBy(emptyToolTurn::join)
        .isInstanceOf(CompletionException.class)
        .hasCauseInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(emptyAssistantTurn::join)
        .isInstanceOf(CompletionException.class)
        .hasCauseInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void deliversAnEmptyHistoryThroughTheStageWithoutDispatchingIt() {
    // An empty `messages` list must fail with this adapter's own stable message, before the
    // operations port is ever reached, rather than as the SDK builder's own "messages is required,
    // but was not set" once the request finally hits ChatCompletionCreateParams.Builder.build().
    FakeChatCompletionsOperations operations = new FakeChatCompletionsOperations();
    ModelClient client = clientOver(operations, builder -> builder.model("gpt-4.1-mini"));

    var stage = client.run(historyOf()).toCompletableFuture();

    assertThat(operations.invocations()).isZero();
    assertThatThrownBy(stage::join)
        .isInstanceOf(CompletionException.class)
        .cause()
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(
            "a model request must carry at least one message: openai chat completions has no"
                + " representation for an empty history");
  }

  @Test
  void rejectsANullRequestWhereTheCallWasMade() {
    // A missing request is a call-site programming error, not a provider failure: it fails by name
    // where it was made rather than arriving as a stage a caller might map, retry, or log.
    FakeChatCompletionsOperations operations = new FakeChatCompletionsOperations();
    ModelClient client = clientOver(operations, builder -> builder.model("gpt-4.1-mini"));

    assertThatThrownBy(() -> client.run(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("request must not be null");
    assertThat(operations.invocations()).isZero();
  }

  @Test
  void failsARunWhoseSignalWasAlreadyCancelledWithoutDispatchingIt() {
    // Proves the run's own signal reaches the bridge: an adapter that passed a fresh signal would
    // dispatch a call the caller had already abandoned, and every other test would still pass.
    FakeChatCompletionsOperations operations = new FakeChatCompletionsOperations();
    ModelClient client = clientOver(operations, builder -> builder.model("gpt-4.1-mini"));
    CancellationSignal signal = new CancellationSignal();
    signal.cancel();

    CompletionStage<ModelResponse> run =
        client.run(
            new ModelRequest(
                List.of(new Message(Role.USER, List.of(new TextContent("hi")))),
                ModelRequestOptions.empty(),
                signal,
                List.of(),
                Map.of()));

    assertThat(operations.invocations()).isZero();
    assertThat(OpenAiCallBridge.unwrap(failureOf(run)))
        .isInstanceOf(CancellationException.class)
        .hasMessage("model call was cancelled before it was dispatched");
  }

  @Test
  void handsOutNoCompletionAuthorityOverTheCallItReturns() {
    // The stage is a view of the call, not the call. A caller that completes the future it takes
    // from it settles its own copy: it neither answers for the model nor aborts the HTTP request.
    CompletableFuture<ChatCompletion> inFlight = new CompletableFuture<>();
    FakeChatCompletionsOperations operations =
        new FakeChatCompletionsOperations().withholding(inFlight);
    ModelClient client = clientOver(operations, builder -> builder.model("gpt-4.1-mini"));

    CompletionStage<ModelResponse> run = client.run(request("hi"));
    CompletableFuture<ModelResponse> caller = run.toCompletableFuture();
    assertThat(caller.complete(null)).isTrue();

    assertThat(run.toCompletableFuture()).isNotDone();
    inFlight.complete(completion("hello there"));

    assertThat(run.toCompletableFuture().join().messages().get(0).text()).isEqualTo("hello there");
    assertThat(caller.join()).isNull();
  }

  @Test
  void requiresAModel() {
    assertThatThrownBy(
            () ->
                OpenAiChatModelClient.builder()
                    .operations(new FakeChatCompletionsOperations())
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("model");
    assertThatThrownBy(
            () ->
                OpenAiChatModelClient.builder()
                    .operations(new FakeChatCompletionsOperations())
                    .model("  ")
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("model");
  }

  @Test
  void requiresAClient() {
    assertThatThrownBy(() -> OpenAiChatModelClient.builder().model("gpt-4.1-mini").build())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("client");
  }

  @Test
  void rejectsATemperatureOutsideTheRangeTheProviderAccepts() {
    // Both halves of the guard, and the value that is neither: a builder that only checked one
    // bound, or forgot that a double can be NaN, would send a request the provider rejects.
    assertThatThrownBy(() -> configured(builder -> builder.temperature(-0.1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("temperature must be between 0.0 and 2.0");
    assertThatThrownBy(() -> configured(builder -> builder.temperature(2.1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("temperature must be between 0.0 and 2.0");
    assertThatThrownBy(() -> configured(builder -> builder.temperature(Double.NaN)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("temperature must be between 0.0 and 2.0");

    assertThat(configured(builder -> builder.temperature(0.0))).isNotNull();
    assertThat(configured(builder -> builder.temperature(2.0))).isNotNull();
  }

  @Test
  void rejectsAnOutputTokenLimitThatIsNotPositive() {
    assertThatThrownBy(() -> configured(builder -> builder.maxOutputTokens(0)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("maxOutputTokens must be greater than 0");
    assertThatThrownBy(() -> configured(builder -> builder.maxOutputTokens(-1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("maxOutputTokens must be greater than 0");

    assertThat(configured(builder -> builder.maxOutputTokens(1))).isNotNull();
  }

  @Test
  void rejectsARequestTimeoutThatIsNotPositive() {
    // A zero or negative timeout would bound nothing, and a null one would fail later inside the
    // SDK's own builder, where the message would not say which setting was wrong.
    assertThatThrownBy(() -> configured(builder -> builder.requestTimeout(Duration.ZERO)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("requestTimeout must be positive");
    assertThatThrownBy(() -> configured(builder -> builder.requestTimeout(Duration.ofSeconds(-1))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("requestTimeout must be positive");
    assertThatThrownBy(() -> configured(builder -> builder.requestTimeout(null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("requestTimeout must be positive");

    assertThat(configured(builder -> builder.requestTimeout(Duration.ofMillis(1)))).isNotNull();
  }

  @Test
  void borrowsTheSdkClientForEveryCallAndNeverClosesIt() {
    // The only test that drives the real SDK binding. OpenAIClientAsync declares its own close(),
    // so "the host owns the lifecycle" is a claim about what this adapter calls, not about what
    // the SDK offers: the recorder fails the moment the adapter reaches for anything else.
    BorrowedClientRecorder recorder = new BorrowedClientRecorder(completion("hello there"));
    ModelClient client =
        OpenAiChatModelClient.builder()
            .client(recorder.client())
            .model("gpt-4.1-mini")
            .requestTimeout(Duration.ofSeconds(45))
            .build();

    assertThat(recorder.calls()).isEmpty();

    ModelResponse first = client.run(request("hi")).toCompletableFuture().join();
    ModelResponse second = client.run(request("again")).toCompletableFuture().join();

    assertThat(first.messages().get(0).text()).isEqualTo("hello there");
    assertThat(second.messages().get(0).text()).isEqualTo("hello there");
    assertThat(recorder.calls())
        .containsExactly("chat", "completions", "create", "chat", "completions", "create");
    assertThat(recorder.calls()).doesNotContain("close");
    // The timeout has to survive the binding too: the SDK's one-argument create() compiles just as
    // well and would send every request with the SDK's own default instead.
    assertThat(recorder.requestOptions())
        .hasSize(2)
        .allSatisfy(
            options ->
                assertThat(options.getTimeout().request()).isEqualTo(Duration.ofSeconds(45)));
  }

  @Test
  void rejectsAnAbsentSdkClientAtTheSeamItself() {
    assertThatThrownBy(() -> new SdkChatCompletionsOperations(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("client must not be null");
  }

  @Test
  void ownsNoLifecycle() {
    // The SDK client is borrowed. An adapter with a close() would invite a caller to shut down a
    // client the host still shares with other holders.
    assertThat(AutoCloseable.class.isAssignableFrom(OpenAiChatModelClient.class)).isFalse();
    assertThat(OpenAiChatModelClient.class.getMethods())
        .noneMatch(method -> "close".equals(method.getName()));
    assertThat(Modifier.isFinal(OpenAiChatModelClient.class.getModifiers())).isTrue();
  }

  @Test
  void keepsTheSupportedBuilderSurfaceSmall() {
    // The operations seam is a testing entry point, not a supported one. If it ever becomes public
    // this fails, which is the only reliable guard against a convenience becoming an API promise.
    List<String> publicMethods =
        Arrays.stream(OpenAiChatModelClient.Builder.class.getDeclaredMethods())
            .filter(method -> !method.isSynthetic())
            .filter(method -> Modifier.isPublic(method.getModifiers()))
            .map(Method::getName)
            .sorted()
            .toList();

    assertThat(publicMethods)
        .containsExactly(
            "build", "client", "maxOutputTokens", "model", "requestTimeout", "temperature");
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

  private static ModelRequest request(String text) {
    return historyOf(new Message(Role.USER, List.of(new TextContent(text))));
  }

  private static ModelRequest historyOf(Message... messages) {
    return new ModelRequest(
        List.of(messages),
        ModelRequestOptions.empty(),
        new CancellationSignal(),
        List.of(),
        Map.of());
  }

  private static ModelClient clientOver(
      FakeChatCompletionsOperations operations,
      UnaryOperator<OpenAiChatModelClient.Builder> configure) {
    return configure.apply(OpenAiChatModelClient.builder().operations(operations)).build();
  }

  private static OpenAiChatModelClient configured(
      UnaryOperator<OpenAiChatModelClient.Builder> configure) {
    return configure
        .apply(
            OpenAiChatModelClient.builder()
                .operations(new FakeChatCompletionsOperations())
                .model("gpt-4.1-mini"))
        .build();
  }

  private static Throwable failureOf(CompletionStage<?> stage) {
    return stage.handle((value, failure) -> failure).toCompletableFuture().join();
  }

  /**
   * Records every SDK method the adapter reaches for on a borrowed client.
   *
   * <p>The SDK ships no in-memory client, and {@code OpenAIClientAsync} declares two dozen service
   * accessors plus a {@code close()}, so a hand-written stand-in would be unreadable and would not
   * fail if a future change started closing the client. A proxy records the calls instead, and
   * answers only the three the adapter is allowed to make.
   */
  private static final class BorrowedClientRecorder implements InvocationHandler {

    private final List<String> calls = new ArrayList<>();
    private final List<RequestOptions> requestOptions = new ArrayList<>();
    private final ChatCompletion answer;

    BorrowedClientRecorder(ChatCompletion answer) {
      this.answer = answer;
    }

    OpenAIClientAsync client() {
      return proxy(OpenAIClientAsync.class);
    }

    List<String> calls() {
      return List.copyOf(calls);
    }

    List<RequestOptions> requestOptions() {
      return List.copyOf(requestOptions);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] arguments) {
      calls.add(method.getName());
      return switch (method.getName()) {
        case "chat" -> proxy(ChatServiceAsync.class);
        case "completions" -> proxy(ChatCompletionServiceAsync.class);
        case "create" -> {
          requestOptions.add((RequestOptions) arguments[1]);
          yield CompletableFuture.completedFuture(answer);
        }
        default ->
            throw new UnsupportedOperationException(
                "the adapter reached for " + method.getName() + " on a client it only borrows");
      };
    }

    private <T> T proxy(Class<T> type) {
      return type.cast(
          Proxy.newProxyInstance(
              Thread.currentThread().getContextClassLoader(), new Class<?>[] {type}, this));
    }
  }
}
