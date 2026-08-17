package io.github.hellices.agentframework.openai.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import io.github.hellices.agentframework.api.agent.CancellationSignal;
import io.github.hellices.agentframework.api.message.ExtensionContent;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.Role;
import io.github.hellices.agentframework.api.message.TextContent;
import io.github.hellices.agentframework.api.message.ToolCallContent;
import io.github.hellices.agentframework.api.message.ToolResultContent;
import io.github.hellices.agentframework.api.value.JsonObject;
import io.github.hellices.agentframework.spi.model.ModelProviderOption;
import io.github.hellices.agentframework.spi.model.ModelRequest;
import io.github.hellices.agentframework.spi.model.ModelRequestOptions;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChatCompletionRequestMapperTest {

  private static final OpenAiChatSettings DEFAULTS =
      new OpenAiChatSettings("gpt-4.1-mini", null, null, Duration.ofSeconds(60));

  private final ChatCompletionRequestMapper mapper = new ChatCompletionRequestMapper();

  @Test
  void mapsEachSupportedRoleToItsMessageParam() {
    ChatCompletionCreateParams params =
        mapper.map(
            request(
                List.of(
                    message(Role.SYSTEM, "be brief"),
                    message(Role.of("developer"), "obey the tools"),
                    message(Role.USER, "hello"),
                    message(Role.ASSISTANT, "hi"))),
            DEFAULTS);

    List<ChatCompletionMessageParam> messages = params.messages();
    assertThat(messages).hasSize(4);
    assertThat(messages.get(0).asSystem().content().asText()).isEqualTo("be brief");
    assertThat(messages.get(1).asDeveloper().content().asText()).isEqualTo("obey the tools");
    assertThat(messages.get(2).asUser().content().asText()).isEqualTo("hello");
    assertThat(messages.get(3).asAssistant().content().orElseThrow().asText()).isEqualTo("hi");
  }

  @Test
  void joinsSeveralTextPartsInOrderWithNewlines() {
    ChatCompletionCreateParams params =
        mapper.map(
            request(
                List.of(
                    new Message(
                        Role.USER, List.of(new TextContent("first"), new TextContent("second"))))),
            DEFAULTS);

    assertThat(params.messages().get(0).asUser().content().asText()).isEqualTo("first\nsecond");
  }

  @Test
  void rejectsAnEmptyHistoryBeforeTheSdkBuilderCanReportItsOwnError() {
    // An empty `messages` list must fail with this adapter's own message, not
    // `ChatCompletionCreateParams.Builder.build()`'s generic "messages is not set" -
    // the caller needs a stable, adapter-owned diagnosis rather than an SDK implementation detail.
    assertThatThrownBy(() -> mapper.map(request(List.of()), DEFAULTS))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(
            "a model request must carry at least one message: openai chat completions has no"
                + " representation for an empty history");
  }

  @Test
  void rejectsAnUnknownRole() {
    assertThatThrownBy(
            () -> mapper.map(request(List.of(message(Role.of("auditor"), "who am i"))), DEFAULTS))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("auditor");
  }

  @Test
  void rejectsExtensionContentWithoutRevealingItsPayload() {
    // A message that says what could not be carried is useful. A message that quotes the payload is
    // a sensitive-data leak into logs and exception reports, which AGENTS.md forbids.
    assertThatThrownBy(
            () ->
                mapper.map(
                    request(List.of(new Message(Role.USER, List.of(new SecretContent())))),
                    DEFAULTS))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("test.secret")
        .hasMessageNotContaining("sensitive payload");
  }

  @Test
  void rejectsAToolCallOnANonAssistantMessage() {
    Message message =
        new Message(
            Role.USER, List.of(new ToolCallContent("call_1", "lookup", JsonObject.empty())));

    assertThatThrownBy(() -> mapper.map(request(List.of(message)), DEFAULTS))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("user");
  }

  @Test
  void rejectsAToolResultOutsideAToolMessage() {
    Message message =
        new Message(
            Role.ASSISTANT, List.of(new ToolResultContent("call_1", "lookup", List.of(), false)));

    assertThatThrownBy(() -> mapper.map(request(List.of(message)), DEFAULTS))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("assistant");
  }

  @Test
  void appliesTheAdapterDefaults() {
    OpenAiChatSettings settings =
        new OpenAiChatSettings("gpt-4.1-mini", 0.2, 256, Duration.ofSeconds(60));

    ChatCompletionCreateParams params =
        mapper.map(request(List.of(message(Role.USER, "hello"))), settings);

    assertThat(params.model().asString()).isEqualTo("gpt-4.1-mini");
    assertThat(params.temperature()).hasValue(0.2);
    assertThat(params.maxCompletionTokens()).hasValue(256L);
  }

  @Test
  void letsRequestOptionsOverrideTheAdapterDefaults() {
    // AGT-012 precedence, proved where it is implementable today. The engine cannot carry these
    // yet, which is why AGT-011 and AGT-012 stay partial, but the adapter side of the rule is real.
    OpenAiChatSettings settings =
        new OpenAiChatSettings("gpt-4.1-mini", 0.2, 256, Duration.ofSeconds(60));
    ModelRequestOptions options =
        ModelRequestOptions.builder().temperature(1.5).maxOutputTokens(64).build();

    ChatCompletionCreateParams params =
        mapper.map(
            ModelRequest.builder()
                .messages(List.of(message(Role.USER, "hello")))
                .options(options)
                .cancellationSignal(new CancellationSignal())
                .build(),
            settings);

    assertThat(params.temperature()).hasValue(1.5);
    assertThat(params.maxCompletionTokens()).hasValue(64L);
  }

  @Test
  void rejectsProviderOptionsUntilATypedSurfaceExists() {
    // AGT-011 says a provider-specific option handed to a provider that does not support it is not
    // silently ignored. This adapter supports none yet, so it says so instead of dropping them.
    ModelRequestOptions options =
        ModelRequestOptions.builder().providerOption(new UnsupportedOpenAiOption(7)).build();

    assertThatThrownBy(
            () ->
                mapper.map(
                    ModelRequest.builder()
                        .messages(List.of(message(Role.USER, "hello")))
                        .options(options)
                        .cancellationSignal(new CancellationSignal())
                        .build(),
                    DEFAULTS))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("openai");
  }

  private record UnsupportedOpenAiOption(int seed) implements ModelProviderOption {
    @Override
    public String providerId() {
      return "openai";
    }
  }

  @Test
  void omitsToolsWhenTheRequestOffersNone() {
    // Sending `tools: []` is rejected by some OpenAI-compatible servers, and the last tool loop
    // iteration deliberately withholds tools, so this is the ordinary case rather than an edge one.
    ChatCompletionCreateParams params =
        mapper.map(request(List.of(message(Role.USER, "hello"))), DEFAULTS);

    assertThat(params.tools()).isEmpty();
  }

  private static Message message(Role role, String text) {
    return new Message(role, List.of(new TextContent(text)));
  }

  private static ModelRequest request(List<Message> messages) {
    return ModelRequest.builder()
        .messages(messages)
        .options(ModelRequestOptions.empty())
        .cancellationSignal(new CancellationSignal())
        .build();
  }

  /**
   * Content the framework has no type for, used to prove the mapper refuses rather than guesses.
   */
  private static final class SecretContent extends ExtensionContent {

    private SecretContent() {
      super(JsonObject.empty(), null);
    }

    @Override
    public String type() {
      return "test.secret";
    }

    @Override
    public String text() {
      return "sensitive payload";
    }
  }
}
