package io.github.hellices.agentframework.openai.internal;

import com.openai.core.RequestOptions;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import java.util.concurrent.CompletableFuture;

/**
 * The one provider operation this adapter performs.
 *
 * <p>Named this narrowly on purpose. The SDK client interface declares two dozen service accessors
 * and the SDK ships no fake, so a test that had to stand in for the client would either implement
 * all of them or start a mock HTTP server. This port is what keeps every adapter test offline.
 */
public interface ChatCompletionsOperations {

  /**
   * Sends one chat completion request.
   *
   * @param params the request parameters, never {@code null}
   * @param requestOptions the per-call options, never {@code null}
   * @return the pending completion; the caller neither completes nor cancels it
   */
  CompletableFuture<ChatCompletion> create(
      ChatCompletionCreateParams params, RequestOptions requestOptions);
}
