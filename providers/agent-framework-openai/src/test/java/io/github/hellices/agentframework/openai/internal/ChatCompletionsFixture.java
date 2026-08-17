package io.github.hellices.agentframework.openai.internal;

import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.chat.completions.ChatCompletionMessageToolCall;
import com.openai.models.completions.CompletionUsage;
import java.util.List;

/**
 * Hand-built Chat Completions responses for the response mapping tests.
 *
 * <p>The SDK builders require fields that are easy to miss: a message requires both content and
 * refusal even when both are JSON null, and a choice requires a finish reason, an index, logprobs,
 * and a message. Centralising that here keeps a mapping test about mapping.
 */
final class ChatCompletionsFixture {

  private ChatCompletionsFixture() {}

  static ChatCompletion completion(
      ChatCompletionMessage message, ChatCompletion.Choice.FinishReason finishReason) {
    return base(message, finishReason).build();
  }

  static ChatCompletion completion(
      ChatCompletionMessage message,
      ChatCompletion.Choice.FinishReason finishReason,
      CompletionUsage usage) {
    return base(message, finishReason).usage(usage).build();
  }

  static ChatCompletion twoChoices() {
    return ChatCompletion.builder()
        .id("chatcmpl-test")
        .created(1_700_000_000L)
        .model("gpt-4.1-mini")
        .addChoice(choice(text("first"), ChatCompletion.Choice.FinishReason.STOP, 0L))
        .addChoice(choice(text("second"), ChatCompletion.Choice.FinishReason.STOP, 1L))
        .build();
  }

  /**
   * A completion whose {@code choices} array is empty, which a compatible server can return when it
   * refuses a request outright.
   */
  static ChatCompletion noChoices() {
    return ChatCompletion.builder()
        .id("chatcmpl-test")
        .created(1_700_000_000L)
        .model("gpt-4.1-mini")
        .choices(List.of())
        .build();
  }

  static ChatCompletionMessage text(String content) {
    return ChatCompletionMessage.builder().content(content).refusal((String) null).build();
  }

  /**
   * A message whose {@code content} is JSON null rather than an empty or blank string, which is
   * what a completion carrying nothing but tool calls looks like on the wire.
   */
  static ChatCompletionMessage withoutContent() {
    return ChatCompletionMessage.builder().content((String) null).refusal((String) null).build();
  }

  static ChatCompletionMessage withToolCalls(
      String content, ChatCompletionMessageToolCall... calls) {
    ChatCompletionMessage.Builder message =
        ChatCompletionMessage.builder().content(content).refusal((String) null);
    for (ChatCompletionMessageToolCall call : calls) {
      message.addToolCall(call);
    }
    return message.build();
  }

  /**
   * A message carrying the deprecated {@code function_call} payload instead of {@code tool_calls},
   * which is what a server still speaking the pre-tools shape returns. The payload has no call id
   * of its own, which is what makes it unmappable rather than merely old.
   */
  // The SDK deprecates the field because the wire shape is deprecated; a fixture for the shape the
  // mapper must reject has to build exactly that shape.
  @SuppressWarnings("deprecation")
  static ChatCompletionMessage withDeprecatedFunctionCall(String name, String arguments) {
    return ChatCompletionMessage.builder()
        .content((String) null)
        .refusal((String) null)
        .functionCall(
            ChatCompletionMessage.FunctionCall.builder().name(name).arguments(arguments).build())
        .build();
  }

  static ChatCompletionMessageToolCall functionCall(String id, String name, String arguments) {
    return ChatCompletionMessageToolCall.ofFunction(
        ChatCompletionMessageFunctionToolCall.builder()
            .id(id)
            .function(
                ChatCompletionMessageFunctionToolCall.Function.builder()
                    .name(name)
                    .arguments(arguments)
                    .build())
            .build());
  }

  static CompletionUsage usage(long promptTokens, long completionTokens, long totalTokens) {
    return CompletionUsage.builder()
        .promptTokens(promptTokens)
        .completionTokens(completionTokens)
        .totalTokens(totalTokens)
        .build();
  }

  private static ChatCompletion.Builder base(
      ChatCompletionMessage message, ChatCompletion.Choice.FinishReason finishReason) {
    return ChatCompletion.builder()
        .id("chatcmpl-test")
        .created(1_700_000_000L)
        .model("gpt-4.1-mini")
        .choices(List.of(choice(message, finishReason, 0L)));
  }

  private static ChatCompletion.Choice choice(
      ChatCompletionMessage message, ChatCompletion.Choice.FinishReason finishReason, long index) {
    return ChatCompletion.Choice.builder()
        .finishReason(finishReason)
        .index(index)
        .logprobs((ChatCompletion.Choice.Logprobs) null)
        .message(message)
        .build();
  }
}
