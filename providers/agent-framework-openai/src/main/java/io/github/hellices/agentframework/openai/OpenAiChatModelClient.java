package io.github.hellices.agentframework.openai;

import com.openai.client.OpenAIClientAsync;
import com.openai.core.RequestOptions;
import io.github.hellices.agentframework.openai.internal.ChatCompletionRequestMapper;
import io.github.hellices.agentframework.openai.internal.ChatCompletionResponseMapper;
import io.github.hellices.agentframework.openai.internal.ChatCompletionsOperations;
import io.github.hellices.agentframework.openai.internal.ModelResponseUpdatePublisher;
import io.github.hellices.agentframework.openai.internal.OpenAiCallBridge;
import io.github.hellices.agentframework.openai.internal.OpenAiChatSettings;
import io.github.hellices.agentframework.openai.internal.SdkChatCompletionsOperations;
import io.github.hellices.agentframework.spi.model.ModelClient;
import io.github.hellices.agentframework.spi.model.ModelRequest;
import io.github.hellices.agentframework.spi.model.ModelResponse;
import io.github.hellices.agentframework.spi.model.ModelResponseUpdate;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * Calls OpenAI Chat Completions through the framework's neutral model port.
 *
 * <p>The SDK client is borrowed. This class never builds, configures, reconnects, or closes it, so
 * it allocates no thread, no connection, and no shutdown hook, and discarding an instance releases
 * nothing. {@code OpenAIClientAsync} declares a {@code close()} of its own, which this adapter
 * never calls: the host that created the client is the only holder that knows when no one else is
 * using it.
 *
 * <p>The model name, and optionally a temperature and an output token limit, are adapter-owned
 * defaults, because the engine cannot carry request options to a provider yet. A value supplied on
 * the {@code ModelRequest} wins over the default.
 *
 * <p>{@link #execute(ModelRequest)} reports a request this adapter cannot represent as a terminal
 * {@code onError} rather than a throw, so a caller handles one failure path rather than two. That
 * covers a request mapping failure - including an empty {@code request.messages()}, which this
 * adapter refuses with its own stable message before the SDK builder ever sees the request - and a
 * response mapping failure, which arrives the same way once the provider has answered: a completion
 * with other than exactly one choice, a {@code tool_calls} or {@code function_call} finish reason
 * with no tool call to back it up, or a tool call whose id or arguments this adapter cannot
 * represent, whether that is a duplicate key, trailing input after the JSON object, or anything
 * else the arguments string does not parse as. Two things still leave it by throwing: a {@code
 * null} request, which is a call-site programming error rather than a provider outcome, and an
 * {@link Error}, which is not a request failure and must not be offered to a caller to map or retry
 * as if the provider had answered.
 *
 * <p>Cancelling the run's signal settles the adapter's one-update outcome promptly and removes the
 * listener, but it does not abort the HTTP request already in flight, and neither does cancelling a
 * future collected from that publisher: that future is a copy of the outcome, not the call. A
 * timeout is what bounds the abandoned work, and {@code requestTimeout} bounds one attempt. Retries
 * are the host's setting on the client it builds ({@code maxRetries}, two by default), not this
 * adapter's: the adapter dispatches one call once, so an abandoned call can run for the timeout
 * times the number of attempts, plus the SDK's backoff between them.
 *
 * <p>Instances are immutable and safe to share once built.
 */
public final class OpenAiChatModelClient implements ModelClient {

  private final ChatCompletionsOperations operations;
  private final OpenAiChatSettings settings;
  private final RequestOptions requestOptions;
  private final ChatCompletionRequestMapper requestMapper = new ChatCompletionRequestMapper();
  private final ChatCompletionResponseMapper responseMapper = new ChatCompletionResponseMapper();

  private OpenAiChatModelClient(ChatCompletionsOperations operations, OpenAiChatSettings settings) {
    this.operations = operations;
    this.settings = settings;
    this.requestOptions = RequestOptions.builder().timeout(settings.requestTimeout()).build();
  }

  /**
   * Starts configuring one adapter.
   *
   * @return a new builder, never {@code null}
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Invokes the model for one request.
   *
   * <p>The returned publisher emits exactly one update — this adapter answers in one shot — and
   * then completes, or terminates with the failure of a request this adapter cannot map onto the
   * wire (including an empty {@code request.messages()}), a response this adapter cannot map back
   * (other than exactly one choice, a tool finish reason with no tool call, or a tool call whose id
   * or arguments cannot be represented), a cancelled run, or the provider itself, with the
   * provider's own exception instance preserved. Cancelling the subscription fails the in-flight
   * call's cancellation signal but does not abort the HTTP request already dispatched.
   *
   * @param request the neutral request, never {@code null}
   * @return a publisher of the model's single response update, never {@code null}
   * @throws NullPointerException if {@code request} is {@code null}
   */
  @Override
  public Flow.Publisher<ModelResponseUpdate> execute(ModelRequest request) {
    Objects.requireNonNull(request, "request must not be null");
    CompletionStage<ModelResponseUpdate> stage =
        OpenAiCallBridge.guard(
                request.cancellationSignal(),
                () -> operations.create(requestMapper.map(request, settings), requestOptions))
            .thenApply(responseMapper::map)
            .thenApply(OpenAiChatModelClient::toUpdate);
    return new ModelResponseUpdatePublisher(stage, request.cancellationSignal());
  }

  private static ModelResponseUpdate toUpdate(ModelResponse response) {
    return ModelResponseUpdate.builder()
        .messages(response.messages())
        .usage(response.usage())
        .finishReason(response.finishReason())
        .continuationToken(response.continuationToken())
        .metadata(response.metadata())
        .rawRepresentation(response.rawRepresentation())
        .build();
  }

  /** Configures one adapter over a borrowed client. */
  public static final class Builder {

    private OpenAIClientAsync client;
    private ChatCompletionsOperations operations;
    private String model;
    private Double temperature;
    private Integer maxOutputTokens;
    private Duration requestTimeout = Duration.ofSeconds(60);

    private Builder() {}

    /**
     * Sets the client this adapter borrows.
     *
     * @param client a client the caller created and will close, never {@code null}
     * @return this builder
     */
    public Builder client(OpenAIClientAsync client) {
      this.client = client;
      return this;
    }

    /**
     * Sets the required model name sent on every request.
     *
     * @param model the model name, never blank
     * @return this builder
     */
    public Builder model(String model) {
      this.model = model;
      return this;
    }

    /**
     * Sets the default temperature, which a request option overrides.
     *
     * @param temperature a value between 0.0 and 2.0
     * @return this builder
     */
    public Builder temperature(double temperature) {
      this.temperature = temperature;
      return this;
    }

    /**
     * Sets the default output token limit, which a request option overrides.
     *
     * @param maxOutputTokens a limit greater than zero
     * @return this builder
     */
    public Builder maxOutputTokens(int maxOutputTokens) {
      this.maxOutputTokens = maxOutputTokens;
      return this;
    }

    /**
     * Sets the per-request timeout that bounds one attempt of a call nobody is waiting for any
     * more. How many attempts there are is the host's {@code maxRetries} on its own client.
     *
     * @param requestTimeout a positive duration; 60 seconds when never set
     * @return this builder
     */
    public Builder requestTimeout(Duration requestTimeout) {
      this.requestTimeout = requestTimeout;
      return this;
    }

    /**
     * Sets the operations port directly, which lets a test drive the adapter with no client, no
     * socket, and no credentials. Not part of the supported surface.
     */
    Builder operations(ChatCompletionsOperations operations) {
      this.operations = operations;
      return this;
    }

    /**
     * Builds one adapter over the configured collaborators.
     *
     * @return a new adapter, never {@code null}
     * @throws IllegalArgumentException if the model, temperature, output token limit, or request
     *     timeout is outside what the provider accepts
     * @throws IllegalStateException if no client was set
     */
    public OpenAiChatModelClient build() {
      OpenAiChatSettings settings =
          new OpenAiChatSettings(model, temperature, maxOutputTokens, requestTimeout);
      ChatCompletionsOperations resolved = operations;
      if (resolved == null) {
        if (client == null) {
          throw new IllegalStateException(
              "client must be set: the adapter borrows an OpenAIClientAsync and never creates one");
        }
        resolved = new SdkChatCompletionsOperations(client);
      }
      return new OpenAiChatModelClient(resolved, settings);
    }
  }
}
