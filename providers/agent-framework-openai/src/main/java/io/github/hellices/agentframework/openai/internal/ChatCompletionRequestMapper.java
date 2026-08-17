package io.github.hellices.agentframework.openai.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.core.JsonValue;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionDeveloperMessageParam;
import com.openai.models.chat.completions.ChatCompletionFunctionTool;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;
import io.github.hellices.agentframework.api.message.Content;
import io.github.hellices.agentframework.api.message.ExtensionContent;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.Role;
import io.github.hellices.agentframework.api.message.TextContent;
import io.github.hellices.agentframework.api.message.ToolCallContent;
import io.github.hellices.agentframework.api.message.ToolResultContent;
import io.github.hellices.agentframework.api.tool.ToolDefinition;
import io.github.hellices.agentframework.spi.model.ModelRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

/** Translates a neutral {@code ModelRequest} into Chat Completions request parameters. */
public final class ChatCompletionRequestMapper {

  private static final Role DEVELOPER = Role.of("developer");

  private static final String UNSENDABLE_ASSISTANT_MESSAGE =
      "an assistant message must carry text or a tool call: openai chat completions rejects an"
          + " assistant message with neither content nor tool_calls";

  private final ObjectMapper json = new ObjectMapper();

  /**
   * Maps a request onto Chat Completions parameters.
   *
   * <p>Every tool the request offers becomes a function tool, one {@code Role.TOOL} message becomes
   * one Chat Completions tool message per result it carries, and an echoed assistant turn prefers
   * the originating SDK message so the {@code arguments} string the model produced is sent back
   * unchanged.
   *
   * <p>Three histories have no message shape at all, and all three fail here rather than reaching
   * the wire. An empty {@code request.messages()} is refused first, before anything else is built,
   * because {@code ChatCompletionCreateParams.Builder.build()} would otherwise throw its own {@code
   * IllegalStateException} ("`messages` is required, but was not set") - an SDK implementation
   * detail this adapter does not own and would otherwise leak to a caller in place of a stable
   * diagnosis. A {@code Role.TOOL} message carrying no result would fan out into no message,
   * leaving the assistant's tool call unanswered in a request the provider rejects, and no {@code
   * tool_call_id} can be invented for it. An assistant turn that would carry neither {@code
   * content} nor {@code tool_calls} - a framework message with no text part and no tool call, or an
   * echoed SDK message with no content, no refusal, and no tool call - is the {@code
   * {"role":"assistant"}} shape Chat Completions rejects; the SDK builder accepts it silently, so
   * nothing downstream would catch it. An assistant turn that carries a text part sends it even
   * when it is empty, because {@code content: ""} is representable, and omits {@code content} only
   * when it carries no text part at all.
   *
   * <p>{@code ToolResultContent.error()} has no representation: a Chat Completions tool message
   * carries no error flag. A failed result is sent as its text, which is what the model reads, and
   * the flag is dropped rather than disguised as an invented prefix the model would have to guess
   * at.
   *
   * @param request the neutral request, never {@code null}
   * @param settings the adapter defaults, never {@code null}
   * @return parameters ready to send
   * @throws IllegalArgumentException if the request carries no message, or if a role, a content
   *     placement, a message with nothing the wire can carry, a tool call's arguments, or a
   *     provider option cannot be represented
   * @throws UnsupportedOperationException if the request carries adapter-owned extension content
   */
  public ChatCompletionCreateParams map(ModelRequest request, OpenAiChatSettings settings) {
    if (request.messages().isEmpty()) {
      throw new IllegalArgumentException(
          "a model request must carry at least one message: openai chat completions has no"
              + " representation for an empty history");
    }
    ChatCompletionCreateParams.Builder params =
        ChatCompletionCreateParams.builder().model(settings.model());
    applyOptions(request, settings, params);
    applyTools(request, params);
    for (Message message : request.messages()) {
      appendMessage(message, params);
    }
    return params.build();
  }

  private void applyOptions(
      ModelRequest request,
      OpenAiChatSettings settings,
      ChatCompletionCreateParams.Builder params) {
    if (!request.options().providerOptions().isEmpty()) {
      throw new IllegalArgumentException(
          "openai chat completions accepts no provider options yet, but the request carried options"
              + " for: "
              + request.options().providerOptions().keySet());
    }
    request
        .options()
        .temperature()
        .or(settings::temperature)
        .ifPresent(temperature -> params.temperature(temperature.doubleValue()));
    OptionalInt maxOutputTokens =
        request.options().maxOutputTokens().isPresent()
            ? request.options().maxOutputTokens()
            : settings.maxOutputTokens();
    if (maxOutputTokens.isPresent()) {
      params.maxCompletionTokens(maxOutputTokens.getAsInt());
    }
  }

  private void applyTools(ModelRequest request, ChatCompletionCreateParams.Builder params) {
    for (ToolDefinition tool : request.tools()) {
      FunctionDefinition.Builder function = FunctionDefinition.builder().name(tool.name());
      if (!tool.description().isBlank()) {
        function.description(tool.description());
      }
      Map<String, Object> schema = tool.inputSchema();
      if (!schema.isEmpty()) {
        FunctionParameters.Builder parameters = FunctionParameters.builder();
        schema.forEach(
            (key, value) -> parameters.putAdditionalProperty(key, JsonValue.from(value)));
        function.parameters(parameters.build());
      }
      params.addTool(ChatCompletionFunctionTool.builder().function(function.build()).build());
    }
  }

  private void appendMessage(Message message, ChatCompletionCreateParams.Builder params) {
    Role role = message.role();
    if (Role.SYSTEM.equals(role)) {
      params.addMessage(
          ChatCompletionSystemMessageParam.builder().content(textOf(message)).build());
    } else if (DEVELOPER.equals(role)) {
      params.addMessage(
          ChatCompletionDeveloperMessageParam.builder().content(textOf(message)).build());
    } else if (Role.USER.equals(role)) {
      params.addMessage(ChatCompletionUserMessageParam.builder().content(textOf(message)).build());
    } else if (Role.ASSISTANT.equals(role)) {
      appendAssistantMessage(message, params);
    } else if (Role.TOOL.equals(role)) {
      appendToolMessages(message, params);
    } else {
      throw new IllegalArgumentException(
          "openai chat completions cannot map role: " + role.value());
    }
  }

  private void appendAssistantMessage(Message message, ChatCompletionCreateParams.Builder params) {
    // Validated before either path is chosen: an SDK handle is a shortcut for the arguments string,
    // not a licence to send content this adapter has already said it cannot represent.
    String text = textOf(message);
    if (message.rawRepresentation() instanceof ChatCompletionMessage sdkMessage) {
      // The SDK object still carries the exact arguments string the model produced, which
      // re-serialising a parsed map cannot reproduce.
      requireSendable(sdkMessage);
      params.addMessage(sdkMessage);
      return;
    }
    ChatCompletionAssistantMessageParam.Builder assistant =
        ChatCompletionAssistantMessageParam.builder();
    boolean carriesText = false;
    boolean carriesToolCall = false;
    for (Content content : message.content()) {
      if (content instanceof TextContent) {
        // Keyed on the part rather than on its length: an explicitly empty text is representable
        // as content "", while a turn with no text part at all omits content instead.
        carriesText = true;
      } else if (content instanceof ToolCallContent call) {
        carriesToolCall = true;
        assistant.addToolCall(
            ChatCompletionMessageFunctionToolCall.builder()
                .id(call.callId())
                .function(
                    ChatCompletionMessageFunctionToolCall.Function.builder()
                        .name(call.name())
                        .arguments(serializeArguments(call))
                        .build())
                .build());
      }
    }
    if (!carriesText && !carriesToolCall) {
      throw new IllegalArgumentException(UNSENDABLE_ASSISTANT_MESSAGE);
    }
    if (carriesText) {
      assistant.content(text);
    }
    params.addMessage(assistant.build());
  }

  /**
   * Refuses an echoed SDK message that would reach the wire carrying nothing.
   *
   * <p>The echo path adds the SDK object itself, so the rule the reconstruction path enforces on
   * framework content has to be enforced here on the object. A refusal counts as something to send:
   * the rule is about a message the API rejects, not about a message without text.
   */
  private void requireSendable(ChatCompletionMessage sdkMessage) {
    boolean carriesToolCall = sdkMessage.toolCalls().map(calls -> !calls.isEmpty()).orElse(false);
    if (sdkMessage.content().isEmpty() && sdkMessage.refusal().isEmpty() && !carriesToolCall) {
      throw new IllegalArgumentException(UNSENDABLE_ASSISTANT_MESSAGE);
    }
  }

  private void appendToolMessages(Message message, ChatCompletionCreateParams.Builder params) {
    // One framework tool message holds every result of one round; Chat Completions wants one
    // message per tool_call_id. Dropping the fan-out leaves a call without a result.
    if (message.content().isEmpty()) {
      // Zero results used to fan out into zero messages, so the request went out with the
      // assistant's tool call unanswered. There is no tool_call_id this adapter could invent to
      // make such a message legal, so it says so instead of dropping the turn.
      throw new IllegalArgumentException(
          "a tool message must carry at least one tool result: openai chat completions identifies"
              + " a tool message by its tool_call_id, which an empty message cannot supply");
    }
    for (Content content : message.content()) {
      requireRepresentable(content, message.role());
      if (!(content instanceof ToolResultContent result)) {
        throw new IllegalArgumentException(
            "a tool message may only carry tool results, but carried content type: "
                + content.type());
      }
      params.addMessage(
          ChatCompletionToolMessageParam.builder()
              .toolCallId(result.callId())
              .content(resultText(result))
              .build());
    }
  }

  private String resultText(ToolResultContent result) {
    List<String> parts = new ArrayList<>();
    for (Content content : result.content()) {
      if (content instanceof ExtensionContent) {
        throw new UnsupportedOperationException(
            "openai chat completions cannot carry content type: " + content.type());
      }
      if (!(content instanceof TextContent text)) {
        throw new IllegalArgumentException(
            "a tool result may only carry text, but carried content type: " + content.type());
      }
      parts.add(text.value());
    }
    return String.join("\n", parts);
  }

  private String serializeArguments(ToolCallContent call) {
    try {
      return json.writeValueAsString(call.arguments());
    } catch (JsonProcessingException failure) {
      throw new IllegalArgumentException(
          "openai chat completions cannot serialise the arguments of tool '"
              + call.name()
              + "' call '"
              + call.callId()
              + "'",
          failure);
    }
  }

  /**
   * Joins the text parts of a message in order.
   *
   * <p>Explicit iteration rather than {@code Message.text()}, because that also concatenates the
   * empty text of a tool call and would silently accept content this adapter must reject.
   */
  private String textOf(Message message) {
    List<String> parts = new ArrayList<>();
    for (Content content : message.content()) {
      requireRepresentable(content, message.role());
      if (content instanceof TextContent text) {
        parts.add(text.value());
      }
    }
    return String.join("\n", parts);
  }

  private void requireRepresentable(Content content, Role role) {
    if (content instanceof ExtensionContent) {
      throw new UnsupportedOperationException(
          "openai chat completions cannot carry content type: " + content.type());
    }
    if (content instanceof ToolCallContent && !Role.ASSISTANT.equals(role)) {
      throw new IllegalArgumentException(
          "a tool call may only appear on an assistant message, but appeared on role: "
              + role.value());
    }
    if (content instanceof ToolResultContent && !Role.TOOL.equals(role)) {
      throw new IllegalArgumentException(
          "a tool result may only appear on a tool message, but appeared on role: " + role.value());
    }
  }
}
