package io.github.hellices.agentframework.openai.internal;

import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionDeveloperMessageParam;
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;
import io.github.hellices.agentframework.api.message.Content;
import io.github.hellices.agentframework.api.message.ExtensionContent;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.Role;
import io.github.hellices.agentframework.api.message.TextContent;
import io.github.hellices.agentframework.api.message.ToolCallContent;
import io.github.hellices.agentframework.api.message.ToolResultContent;
import io.github.hellices.agentframework.spi.model.ModelRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

/** Translates a neutral {@code ModelRequest} into Chat Completions request parameters. */
public final class ChatCompletionRequestMapper {

  private static final Role DEVELOPER = Role.of("developer");

  /**
   * Maps a request onto Chat Completions parameters.
   *
   * @param request the neutral request, never {@code null}
   * @param settings the adapter defaults, never {@code null}
   * @return parameters ready to send
   * @throws IllegalArgumentException if a role, a content placement, or a provider option cannot be
   *     represented
   * @throws UnsupportedOperationException if the request carries adapter-owned extension content
   */
  public ChatCompletionCreateParams map(ModelRequest request, OpenAiChatSettings settings) {
    ChatCompletionCreateParams.Builder params =
        ChatCompletionCreateParams.builder().model(settings.model());
    applyOptions(request, settings, params);
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
      params.addMessage(
          ChatCompletionAssistantMessageParam.builder().content(textOf(message)).build());
    } else {
      throw new IllegalArgumentException(
          "openai chat completions cannot map role: " + role.value());
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
