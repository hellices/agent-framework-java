package io.github.hellices.agentframework.openai.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessage;
import io.github.hellices.agentframework.api.agent.CancellationSignal;
import io.github.hellices.agentframework.api.message.FinishReason;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.Role;
import io.github.hellices.agentframework.api.message.TextContent;
import io.github.hellices.agentframework.api.message.Usage;
import io.github.hellices.agentframework.spi.model.ModelRequest;
import io.github.hellices.agentframework.spi.model.ModelRequestOptions;
import io.github.hellices.agentframework.spi.model.ModelResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ChatCompletionResponseMapperTest {

  private static final OpenAiChatSettings DEFAULTS =
      new OpenAiChatSettings("gpt-4.1-mini", null, null, Duration.ofSeconds(60));

  private final ChatCompletionResponseMapper mapper = new ChatCompletionResponseMapper();

  @Test
  void mapsATextOnlyCompletion() {
    ChatCompletionMessage message = ChatCompletionsFixture.text("hello there");
    ChatCompletion completion =
        ChatCompletionsFixture.completion(message, ChatCompletion.Choice.FinishReason.STOP);

    ModelResponse response = mapper.map(completion);

    assertThat(response.messages()).hasSize(1);
    Message mapped = response.messages().get(0);
    assertThat(mapped.role()).isEqualTo(Role.ASSISTANT);
    assertThat(mapped.content()).singleElement().isInstanceOf(TextContent.class);
    assertThat(mapped.text()).isEqualTo("hello there");
    assertThat(mapped.rawRepresentation()).isSameAs(message);
    assertThat(response.finishReason()).isEqualTo(FinishReason.STOP);
  }

  @Test
  void contributesNoTextContentWhenTheContentIsBlank() {
    // An empty TextContent is not the same statement as no content, and the engine concatenates
    // message text, so an empty part would silently change nothing while claiming the model spoke.
    ChatCompletion completion =
        ChatCompletionsFixture.completion(
            ChatCompletionsFixture.text("   "), ChatCompletion.Choice.FinishReason.STOP);

    assertThat(mapper.map(completion).messages()).isEmpty();
  }

  @Test
  void passesTheModelsTextThroughVerbatimIncludingItsOwnWhitespace() {
    // Blankness decides whether there is a message at all; it never edits the text of one. Trimming
    // here would rewrite model output that a downstream consumer may be matching exactly.
    ChatCompletion completion =
        ChatCompletionsFixture.completion(
            ChatCompletionsFixture.text("  hello there\n"),
            ChatCompletion.Choice.FinishReason.STOP);

    assertThat(mapper.map(completion).messages().get(0).text()).isEqualTo("  hello there\n");
  }

  @Test
  void mapsUsageFromTheCompletion() {
    ChatCompletion completion =
        ChatCompletionsFixture.completion(
            ChatCompletionsFixture.text("hi"),
            ChatCompletion.Choice.FinishReason.STOP,
            ChatCompletionsFixture.usage(11L, 5L, 16L));

    assertThat(mapper.map(completion).usage()).isEqualTo(new Usage(11L, 5L, 16L));
  }

  @Test
  void leavesUsageNullWhenTheCompletionOmitsIt() {
    // ModelResponse allows a null usage and AgentEngine.combineUsage tolerates it, so a compatible
    // server that reports no usage still completes the run.
    ChatCompletion completion =
        ChatCompletionsFixture.completion(
            ChatCompletionsFixture.text("hi"), ChatCompletion.Choice.FinishReason.STOP);

    assertThat(mapper.map(completion).usage()).isNull();
  }

  @ParameterizedTest(name = "{0} maps to {1}")
  @MethodSource("finishReasons")
  void mapsEveryFinishReason(ChatCompletion.Choice.FinishReason wireValue, FinishReason expected) {
    // A tool-call finish reason only ever describes a turn that carries a call, and the mapper
    // rejects the contradiction, so those two rows send the shape their wire value means. What is
    // pinned here is unchanged: which neutral reason each wire value maps to.
    ChatCompletionMessage message =
        expected == FinishReason.TOOL_CALLS
            ? ChatCompletionsFixture.withToolCalls(
                "hi", ChatCompletionsFixture.functionCall("call_1", "ping", "{}"))
            : ChatCompletionsFixture.text("hi");
    ChatCompletion completion = ChatCompletionsFixture.completion(message, wireValue);

    assertThat(mapper.map(completion).finishReason()).isEqualTo(expected);
  }

  static Stream<Arguments> finishReasons() {
    return Stream.of(
        Arguments.of(ChatCompletion.Choice.FinishReason.STOP, FinishReason.STOP),
        Arguments.of(ChatCompletion.Choice.FinishReason.LENGTH, FinishReason.LENGTH),
        Arguments.of(ChatCompletion.Choice.FinishReason.TOOL_CALLS, FinishReason.TOOL_CALLS),
        Arguments.of(
            ChatCompletion.Choice.FinishReason.CONTENT_FILTER, FinishReason.CONTENT_FILTER),
        // The deprecated wire value still means the model asked for a call, so it is mapped
        // deliberately rather than falling through to UNKNOWN.
        Arguments.of(ChatCompletion.Choice.FinishReason.FUNCTION_CALL, FinishReason.TOOL_CALLS),
        // A value the pinned SDK has never seen must not throw: known() would, value() does not.
        Arguments.of(ChatCompletion.Choice.FinishReason.of("moon_phase"), FinishReason.UNKNOWN));
  }

  @Test
  void carriesResponseIdentityInMetadataAndTheCompletionAsTheRawRepresentation() {
    ChatCompletion completion =
        ChatCompletionsFixture.completion(
            ChatCompletionsFixture.text("hi"), ChatCompletion.Choice.FinishReason.STOP);

    ModelResponse response = mapper.map(completion);

    assertThat(response.metadata())
        .containsEntry("openai.response.id", "chatcmpl-test")
        .containsEntry("openai.response.model", "gpt-4.1-mini")
        .containsEntry("openai.response.created", 1_700_000_000L);
    assertThat(response.rawRepresentation()).isSameAs(completion);
  }

  @Test
  void leavesTheContinuationTokenNull() {
    // Chat Completions is stateless, and ToolLoopPolicy.validateContinuation rejects a token
    // whenever tools are configured, so inventing one would break the tool loop.
    ChatCompletion completion =
        ChatCompletionsFixture.completion(
            ChatCompletionsFixture.text("hi"), ChatCompletion.Choice.FinishReason.STOP);

    assertThat(mapper.map(completion).continuationToken()).isNull();
  }

  @Test
  void rejectsACompletionWithMoreThanOneChoice() {
    // This slice never sends `n`, so more than one choice means the server did something the
    // adapter does not model. Taking choices[0] would silently discard an answer.
    assertThatThrownBy(() -> mapper.map(ChatCompletionsFixture.twoChoices()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("2");
  }

  @Test
  void rejectsACompletionWithNoChoiceAtAll() {
    // The rule is exactly one choice, not at most one: a compatible server that answers with an
    // empty choices array has produced no turn, and choices.get(0) would fail as an
    // IndexOutOfBoundsException that names neither the adapter nor the reason.
    assertThatThrownBy(() -> mapper.map(ChatCompletionsFixture.noChoices()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("0");
  }

  @Test
  void reportsATurnThatSaidNothingThroughTheResponseRatherThanAnEmptyMessage() {
    // Absent content, not blank content: the wire shape of a turn that carries no text at all.
    // The turn is not lost by contributing no message - the finish reason, the usage, the metadata
    // and the raw completion all still describe it, so a caller can see that the model stopped
    // without speaking instead of seeing an assistant message that says nothing.
    ChatCompletion completion =
        ChatCompletionsFixture.completion(
            ChatCompletionsFixture.withoutContent(),
            ChatCompletion.Choice.FinishReason.LENGTH,
            ChatCompletionsFixture.usage(9L, 0L, 9L));

    ModelResponse response = mapper.map(completion);

    assertThat(response.messages()).isEmpty();
    assertThat(response.finishReason()).isEqualTo(FinishReason.LENGTH);
    assertThat(response.usage()).isEqualTo(new Usage(9L, 0L, 9L));
    assertThat(response.metadata()).containsEntry("openai.response.id", "chatcmpl-test");
    assertThat(response.rawRepresentation()).isSameAs(completion);
  }

  @Test
  void contributesNoAssistantMessageTheRequestMapperCouldNotSendBack() {
    // Why "no message at all" rather than an empty assistant message: the engine appends
    // response.messages() to the history it echoes on the next iteration, and an assistant Message
    // with no content is the {"role":"assistant"} shape Chat Completions rejects. The request
    // mapper now refuses that shape outright, so dropping this rule would no longer send an
    // unsendable request - it would fail an ordinary empty completion the model was entitled to
    // give. The counterfactual is asserted below so both halves stay pinned together.
    ChatCompletion completion =
        ChatCompletionsFixture.completion(
            ChatCompletionsFixture.withoutContent(), ChatCompletion.Choice.FinishReason.STOP);
    ChatCompletionRequestMapper requestMapper = new ChatCompletionRequestMapper();

    List<Message> history = new ArrayList<>();
    history.add(new Message(Role.USER, List.of(new TextContent("hello"))));
    history.addAll(mapper.map(completion).messages());
    ChatCompletionCreateParams params = requestMapper.map(request(history), DEFAULTS);

    assertThat(params.messages()).hasSize(1);
    assertThat(params.messages().get(0).isUser()).isTrue();

    assertThatThrownBy(
            () ->
                requestMapper.map(
                    request(List.of(new Message(Role.ASSISTANT, List.of()))), DEFAULTS))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("neither content nor tool_calls");
  }

  private static ModelRequest request(List<Message> messages) {
    return new ModelRequest(
        messages, ModelRequestOptions.empty(), new CancellationSignal(), List.of(), Map.of());
  }
}
