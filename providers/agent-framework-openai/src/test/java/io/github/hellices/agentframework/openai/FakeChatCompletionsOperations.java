package io.github.hellices.agentframework.openai;

import com.openai.core.RequestOptions;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import io.github.hellices.agentframework.openai.internal.ChatCompletionsOperations;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * A scripted stand-in for the one SDK call this adapter makes.
 *
 * <p>The SDK ships no in-memory client and its own tests need a Prism mock server, so this port is
 * what keeps every adapter test deterministic, offline, and credential free.
 */
final class FakeChatCompletionsOperations implements ChatCompletionsOperations {

  private final Deque<Supplier<CompletableFuture<ChatCompletion>>> answers = new ArrayDeque<>();
  private final List<ChatCompletionCreateParams> requests = new ArrayList<>();
  private final List<RequestOptions> requestOptions = new ArrayList<>();

  FakeChatCompletionsOperations answering(ChatCompletion completion) {
    answers.add(() -> CompletableFuture.completedFuture(completion));
    return this;
  }

  FakeChatCompletionsOperations failingWith(RuntimeException failure) {
    answers.add(() -> CompletableFuture.failedFuture(failure));
    return this;
  }

  FakeChatCompletionsOperations withholding(CompletableFuture<ChatCompletion> withheld) {
    answers.add(() -> withheld);
    return this;
  }

  @Override
  public CompletableFuture<ChatCompletion> create(
      ChatCompletionCreateParams params, RequestOptions options) {
    requests.add(params);
    requestOptions.add(options);
    Supplier<CompletableFuture<ChatCompletion>> answer = answers.poll();
    if (answer == null) {
      throw new IllegalStateException(
          "the adapter called the provider "
              + requests.size()
              + " times, but only "
              + (requests.size() - 1)
              + " answers were scripted");
    }
    return answer.get();
  }

  List<ChatCompletionCreateParams> requests() {
    return List.copyOf(requests);
  }

  List<RequestOptions> requestOptions() {
    return List.copyOf(requestOptions);
  }

  int invocations() {
    return requests.size();
  }
}
