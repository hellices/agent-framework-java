package io.github.hellices.agentframework.openai.internal;

import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.models.completions.CompletionUsage;
import io.github.hellices.agentframework.api.message.Content;
import io.github.hellices.agentframework.api.message.FinishReason;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.Role;
import io.github.hellices.agentframework.api.message.TextContent;
import io.github.hellices.agentframework.api.message.Usage;
import io.github.hellices.agentframework.spi.model.ModelResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Translates a Chat Completions response into a neutral {@code ModelResponse}. */
public final class ChatCompletionResponseMapper {

  /**
   * Maps a completion.
   *
   * <p>Exactly one choice is expected: this adapter never sends {@code n}, so a second choice is an
   * answer it cannot report and the first one is not silently preferred.
   *
   * <p>Content that is absent or blank contributes no text part, and a completion left with nothing
   * to say contributes no message at all. An assistant message with neither text nor a tool call is
   * not what the model produced, and it is not representable on the wire either: the engine echoes
   * {@code ModelResponse.messages()} back into the next request, where {@link
   * ChatCompletionRequestMapper} would turn it into an assistant message param carrying neither
   * content nor tool calls, which Chat Completions rejects. Such a turn is still reported in full
   * through the finish reason, the usage, the metadata, and the raw completion, so it is visible
   * rather than silent. The engine draws the same line for the messages it rewrites itself, where a
   * message left with nothing to say is dropped instead of echoed empty.
   *
   * @param completion the parsed response, never {@code null}
   * @return the neutral response
   * @throws IllegalStateException if the completion carries anything this adapter cannot represent
   */
  public ModelResponse map(ChatCompletion completion) {
    List<ChatCompletion.Choice> choices = completion.choices();
    if (choices.size() != 1) {
      throw new IllegalStateException(
          "openai chat completions returned "
              + choices.size()
              + " choices; exactly one is supported");
    }
    ChatCompletion.Choice choice = choices.get(0);
    ChatCompletionMessage message = choice.message();
    List<Content> content = new ArrayList<>();
    message
        .content()
        .filter(text -> !text.isBlank())
        .ifPresent(text -> content.add(new TextContent(text)));
    List<Message> messages =
        content.isEmpty()
            ? List.of()
            : List.of(new Message(Role.ASSISTANT, content, null, Map.of(), message));
    return new ModelResponse(
        messages,
        usageOf(completion),
        finishReasonOf(choice),
        null,
        metadataOf(completion),
        completion);
  }

  private static FinishReason finishReasonOf(ChatCompletion.Choice choice) {
    // value() rather than known(): known() throws for a reason the pinned SDK has never seen, and a
    // new wire value is a routine event, not a failure.
    return switch (choice.finishReason().value()) {
      case STOP -> FinishReason.STOP;
      case LENGTH -> FinishReason.LENGTH;
      case TOOL_CALLS, FUNCTION_CALL -> FinishReason.TOOL_CALLS;
      case CONTENT_FILTER -> FinishReason.CONTENT_FILTER;
      default -> FinishReason.UNKNOWN;
    };
  }

  private static Usage usageOf(ChatCompletion completion) {
    // The typed accessors throw when a field is absent on the wire. That is deliberate: a partial
    // usage object is a server contract violation, and inventing a zero would corrupt accounting.
    return completion
        .usage()
        .map(
            (CompletionUsage usage) ->
                new Usage(usage.promptTokens(), usage.completionTokens(), usage.totalTokens()))
        .orElse(null);
  }

  private static Map<String, Object> metadataOf(ChatCompletion completion) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("openai.response.id", completion.id());
    metadata.put("openai.response.model", completion.model());
    metadata.put("openai.response.created", completion.created());
    return Map.copyOf(metadata);
  }
}
