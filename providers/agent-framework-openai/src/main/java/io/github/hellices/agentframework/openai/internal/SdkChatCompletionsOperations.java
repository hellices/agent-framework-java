package io.github.hellices.agentframework.openai.internal;

import com.openai.client.OpenAIClientAsync;
import com.openai.core.RequestOptions;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Routes the one operation to a borrowed SDK client.
 *
 * <p>The client is never built, configured, reconnected, or closed here. It owns an HTTP
 * dispatcher, a connection pool, and an executor that the host created, and {@code
 * OpenAIClientAsync} declares a {@code close()} of its own: calling it would shut down a client the
 * host may still share with other holders, so this class calls exactly one method on it, {@code
 * chat().completions().create}, and never that one.
 */
public final class SdkChatCompletionsOperations implements ChatCompletionsOperations {

  private final OpenAIClientAsync client;

  /**
   * Binds the operation to a client the caller owns.
   *
   * @param client the borrowed client, never {@code null}
   * @throws NullPointerException if {@code client} is {@code null}
   */
  public SdkChatCompletionsOperations(OpenAIClientAsync client) {
    this.client = Objects.requireNonNull(client, "client must not be null");
  }

  @Override
  public CompletableFuture<ChatCompletion> create(
      ChatCompletionCreateParams params, RequestOptions requestOptions) {
    return client.chat().completions().create(params, requestOptions);
  }
}
