package io.github.hellices.agentframework.openai.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.chat.completions.ChatCompletionMessageToolCall;
import com.openai.models.completions.CompletionUsage;
import io.github.hellices.agentframework.api.message.Content;
import io.github.hellices.agentframework.api.message.FinishReason;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.Role;
import io.github.hellices.agentframework.api.message.TextContent;
import io.github.hellices.agentframework.api.message.ToolCallContent;
import io.github.hellices.agentframework.api.message.Usage;
import io.github.hellices.agentframework.spi.model.ModelResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Translates a Chat Completions response into a neutral {@code ModelResponse}. */
public final class ChatCompletionResponseMapper {

  // Tool arguments are model output parsed into a Map a tool executor acts on, so the parser is
  // configured rather than defaulted.
  //
  // FAIL_ON_TRAILING_TOKENS: readTree reads one value and stops, so `{"a":1}{"b":2}` would arrive
  // as {"a":1} and the rest would vanish. Half of a response is not the call the model made.
  //
  // STRICT_DUPLICATE_DETECTION: Jackson's default is last-wins, which resolves `{"city":"Seoul",
  // "city":"Busan"}` to Busan and discards Seoul silently. Which value the model meant is not
  // knowable here, and choosing one changes the model's intent, so the duplicate fails instead.
  //
  // INCLUDE_SOURCE_IN_LOCATION disabled: Jackson's own message would otherwise quote the source it
  // failed on, and that source is the arguments string. No parse failure escapes this class - the
  // exception below carries no cause and no suppressed throwable, because Jackson names the token
  // it choked on in the message text itself, which no source-location setting can redact. This
  // line is the second layer rather than the rule: it keeps a parser exception that ever escapes
  // by a route this class does not catch from carrying the payload with it. It is also the pinned
  // Jackson's default, so stating it keeps a future upgrade from re-enabling it silently.
  private final ObjectMapper json =
      JsonMapper.builder()
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
          .build();

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
   * <p>Text comes first and function tool calls follow in wire order, each carrying its SDK call as
   * its raw representation so the {@code arguments} string the model produced stays byte-exact
   * through the echo. Arguments must be exactly one JSON object with unique keys: trailing input
   * after the object and a repeated key both fail, because keeping the first value and dropping the
   * rest would hand a tool a call the model did not make. Every argument failure is this adapter's
   * own {@code IllegalStateException} naming the tool, the call id, and that requirement, with no
   * parser exception attached as a cause or as a suppressed throwable: the parser names the token
   * it rejected, so its message is model output and belongs in no log. A finish reason of {@code
   * tool_calls} or the deprecated {@code function_call} that arrives with no tool call fails:
   * {@code AgentEngine} ends its loop on an empty tool-call list, so such a turn would otherwise be
   * reported as a successful final answer that the model never gave. The reverse pairing is
   * deliberately not policed - a tool call that arrives under {@code stop} or {@code length} is
   * mapped and the finish reason is reported as the server sent it, because calls that are present
   * are executable whatever the finish reason says, and only a promise the adapter cannot see is
   * dangerous.
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
    FinishReason finishReason = finishReasonOf(choice);
    List<Content> content = new ArrayList<>();
    message
        .content()
        .filter(text -> !text.isBlank())
        .ifPresent(text -> content.add(new TextContent(text)));
    appendToolCalls(message, content);
    requireAToolCallWhenTheFinishReasonPromisedOne(choice, finishReason, content);
    List<Message> messages =
        content.isEmpty()
            ? List.of()
            : List.of(new Message(Role.ASSISTANT, content, null, Map.of(), message));
    return new ModelResponse(
        messages, usageOf(completion), finishReason, null, metadataOf(completion), completion);
  }

  /**
   * Appends one {@code ToolCallContent} per function tool call, in wire order.
   *
   * <p>The SDK call becomes the content's raw representation: the parsed map cannot reproduce the
   * model's own key order or spacing, and {@link ChatCompletionRequestMapper} sends the original
   * string back when the handle survives.
   */
  private void appendToolCalls(ChatCompletionMessage message, List<Content> content) {
    rejectTheDeprecatedFunctionCallPayload(message);
    for (ChatCompletionMessageToolCall toolCall : message.toolCalls().orElse(List.of())) {
      if (!toolCall.isFunction()) {
        throw new IllegalStateException(
            "openai chat completions returned a tool call that is not a function call");
      }
      ChatCompletionMessageFunctionToolCall call = toolCall.asFunction();
      String callId = call.id();
      if (callId.isBlank()) {
        throw new IllegalStateException(
            "openai chat completions returned a tool call without an id");
      }
      String name = call.function().name();
      if (name.isBlank()) {
        throw new IllegalStateException(
            "openai chat completions returned a tool call without a name for call '"
                + callId
                + "'");
      }
      content.add(
          new ToolCallContent(callId, name, argumentsOf(call, callId, name), Map.of(), call));
    }
  }

  // The suppression covers this method alone, which exists so that reading one deprecated accessor
  // does not silence a deprecation warning over the whole tool-call loop. functionCall() is
  // deprecated because the wire shape is; reading it is how this adapter refuses that shape instead
  // of dropping the call it carries.
  @SuppressWarnings("deprecation")
  private static void rejectTheDeprecatedFunctionCallPayload(ChatCompletionMessage message) {
    Optional<ChatCompletionMessage.FunctionCall> payload = message.functionCall();
    if (payload.isEmpty()) {
      return;
    }
    // The pre-tools shape carries no call id, and a tool result is keyed by the id the model
    // issued. Synthesising one would answer a call that never existed, and ignoring the field
    // would drop a call the model asked for and end the run with an answer it never gave.
    throw new IllegalStateException(
        "openai chat completions returned the deprecated function_call payload for function '"
            + payload.get().name()
            + "'; it carries no call id to key a tool result by, so configure the model with"
            + " tools rather than functions");
  }

  private static void requireAToolCallWhenTheFinishReasonPromisedOne(
      ChatCompletion.Choice choice, FinishReason finishReason, List<Content> content) {
    if (finishReason != FinishReason.TOOL_CALLS
        || content.stream().anyMatch(ToolCallContent.class::isInstance)) {
      return;
    }
    // Names the wire value and stops there: whatever the turn did carry is model output, and a
    // server that mislabels a plain answer would otherwise have that answer echoed into a log.
    throw new IllegalStateException(
        "openai chat completions returned finish reason '"
            + choice.finishReason().asString()
            + "' with no tool call; a turn that promised a call and delivered none is a broken"
            + " response rather than a final answer");
  }

  private Map<String, Object> argumentsOf(
      ChatCompletionMessageFunctionToolCall call, String callId, String name) {
    String arguments = call.function().arguments();
    if (arguments.isEmpty()) {
      return Map.of();
    }
    JsonNode parsed;
    try {
      parsed = json.readTree(arguments);
    } catch (JsonProcessingException failure) {
      // The parser exception is deliberately not attached, as a cause or as a suppressed
      // throwable. Jackson names the token it choked on, so its message carries the payload
      // whatever the source location setting is: `{"name": <token>}` yields
      // "Unrecognized token '<token>'". A cause is printed by every logger that prints a stack
      // trace, so attaching it would put model output in a log while this adapter's own message
      // carefully kept it out. What is lost is the column and the parser's wording; what is kept
      // is the tool, the call id, and the structural requirement, which is what a caller acts on.
      throw new IllegalStateException(argumentFailure(name, callId));
    }
    if (!parsed.isObject()) {
      throw new IllegalStateException(argumentFailure(name, callId));
    }
    Map<String, Object> values = new LinkedHashMap<>();
    parsed
        .properties()
        .forEach(
            entry -> values.put(entry.getKey(), json.convertValue(entry.getValue(), Object.class)));
    // Collections.unmodifiableMap, never Map.copyOf. A JSON null argument value converts to a Java
    // null, and Map.copyOf rejects it with a bare NullPointerException that names neither the tool
    // nor the key. {"unit":null} is an ordinary thing for a model to send about an optional
    // parameter, and dropping or refusing the key would change what the model said.
    // ToolCallContent copies this into a LinkedHashMap, which keeps the null.
    return Collections.unmodifiableMap(values);
  }

  private static String argumentFailure(String name, String callId) {
    // Names the tool and the call id and stops there. The arguments are model output and are never
    // put in an exception message, nor reachable through one: no parser exception is attached.
    // "exactly one JSON object with unique keys" covers every way the string can fail to be one:
    // invalid JSON, an array or a scalar, a value followed by more input, and a repeated key.
    return "openai chat completions returned arguments that are not exactly one JSON object with"
        + " unique keys for tool '"
        + name
        + "' call '"
        + callId
        + "'";
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
