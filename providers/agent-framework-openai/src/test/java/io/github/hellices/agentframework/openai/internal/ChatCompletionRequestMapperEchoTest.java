package io.github.hellices.agentframework.openai.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.models.chat.completions.ChatCompletionMessageToolCall;
import io.github.hellices.agentframework.api.agent.CancellationSignal;
import io.github.hellices.agentframework.api.message.Content;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.Role;
import io.github.hellices.agentframework.api.message.TextContent;
import io.github.hellices.agentframework.api.message.ToolCallContent;
import io.github.hellices.agentframework.spi.model.ModelRequest;
import io.github.hellices.agentframework.spi.model.ModelRequestOptions;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The rule that decides whether an assistant turn is sent as the completion it came from.
 *
 * <p>Echoing the model's own {@code ChatCompletionMessage} is what keeps the {@code arguments}
 * string byte-exact, and it is safe only while the neutral content still says what that completion
 * says. A caller that appends text, adds, drops, reorders, or rewrites a tool call has changed the
 * turn, and sending the original completion would drop that change with no exception and no
 * warning. Every case below is therefore either a preserved echo or an explicit refusal; none of
 * them is a silent send of something else.
 */
class ChatCompletionRequestMapperEchoTest {

  private static final OpenAiChatSettings DEFAULTS =
      new OpenAiChatSettings("gpt-4.1-mini", null, null, Duration.ofSeconds(60));

  /** The model's own spacing, which re-serialising the parsed map cannot reproduce. */
  private static final String MODEL_ARGUMENTS = "{\"city\": \"Seoul\"}";

  private static final String EDITED_ECHO =
      "an edited assistant message cannot be echoed: its content no longer matches the openai chat"
          + " completion it carries as its raw representation, and sending that completion would"
          + " drop the edit; build the message without the raw representation to send it as it now"
          + " reads";

  private final ChatCompletionRequestMapper mapper = new ChatCompletionRequestMapper();

  @Test
  void echoesTheCompletionWhenTheNeutralContentStillSaysWhatItSays() {
    // The whole point of the echo: spacing inside the arguments string and inside the text survives
    // exactly, which a reconstruction from the parsed map cannot promise.
    ChatCompletionMessage sdkMessage =
        ChatCompletionsFixture.withToolCalls(
            "  looking it up  ",
            ChatCompletionsFixture.functionCall("call_1", "lookup", MODEL_ARGUMENTS));
    Message assistant =
        echo(
            sdkMessage,
            new TextContent("  looking it up  "),
            new ToolCallContent("call_1", "lookup", Map.of("city", "Seoul")));

    ChatCompletionCreateParams params = mapper.map(request(assistant), DEFAULTS);

    ChatCompletionAssistantMessageParam echoed = params.messages().get(0).asAssistant();
    assertThat(echoed.content().orElseThrow().asText()).isEqualTo("  looking it up  ");
    assertThat(echoed.toolCalls().orElseThrow())
        .singleElement()
        .satisfies(
            call ->
                assertThat(call.asFunction().function().arguments()).isEqualTo(MODEL_ARGUMENTS));
  }

  @Test
  void refusesAnEchoThatGainedTextTheCompletionNeverCarried() {
    // The reported defect: a caller took the assistant turn the response mapper produced, appended
    // its own text, and the request went out as the original completion with that text gone.
    Message assistant =
        echo(
            ChatCompletionsFixture.withToolCalls(
                null, ChatCompletionsFixture.functionCall("call_1", "lookup", MODEL_ARGUMENTS)),
            new TextContent("checking the weather"),
            new ToolCallContent("call_1", "lookup", Map.of("city", "Seoul")));

    assertThatThrownBy(() -> mapper.map(request(assistant), DEFAULTS))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(EDITED_ECHO);
  }

  @Test
  void refusesAnEchoWhoseTextWasRewritten() {
    Message assistant =
        echo(
            ChatCompletionsFixture.text("the original answer"),
            new TextContent("an edited answer"));

    assertThatThrownBy(() -> mapper.map(request(assistant), DEFAULTS))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(EDITED_ECHO);
  }

  @Test
  void refusesAnEchoWhoseTextWasRemoved() {
    // Removing the text is as much an edit as rewriting it: the completion would still be sent with
    // the text the caller deleted.
    Message assistant =
        echo(
            ChatCompletionsFixture.withToolCalls(
                "the original answer",
                ChatCompletionsFixture.functionCall("call_1", "lookup", MODEL_ARGUMENTS)),
            new ToolCallContent("call_1", "lookup", Map.of("city", "Seoul")));

    assertThatThrownBy(() -> mapper.map(request(assistant), DEFAULTS))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(EDITED_ECHO);
  }

  @Test
  void refusesAnEchoThatGainedAToolCall() {
    Message assistant =
        echo(
            ChatCompletionsFixture.withToolCalls(
                null, ChatCompletionsFixture.functionCall("call_1", "lookup", MODEL_ARGUMENTS)),
            new ToolCallContent("call_1", "lookup", Map.of("city", "Seoul")),
            new ToolCallContent("call_2", "lookup", Map.of("city", "Busan")));

    assertThatThrownBy(() -> mapper.map(request(assistant), DEFAULTS))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(EDITED_ECHO);
  }

  @Test
  void refusesAnEchoThatDroppedAToolCall() {
    // Dropping a call from the neutral turn leaves the completion's own call in the request, so the
    // next round answers a call the caller believed it had removed.
    Message assistant =
        echo(
            ChatCompletionsFixture.withToolCalls(
                null,
                ChatCompletionsFixture.functionCall("call_1", "lookup", MODEL_ARGUMENTS),
                ChatCompletionsFixture.functionCall("call_2", "lookup", "{\"city\": \"Busan\"}")),
            new ToolCallContent("call_1", "lookup", Map.of("city", "Seoul")));

    assertThatThrownBy(() -> mapper.map(request(assistant), DEFAULTS))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(EDITED_ECHO);
  }

  @Test
  void refusesAnEchoWhoseToolCallsWereReordered() {
    // Order is wire order. A set comparison would call this a match and send the model's order.
    Message assistant =
        echo(
            ChatCompletionsFixture.withToolCalls(
                null,
                ChatCompletionsFixture.functionCall("call_1", "lookup", MODEL_ARGUMENTS),
                ChatCompletionsFixture.functionCall("call_2", "lookup", "{\"city\": \"Busan\"}")),
            new ToolCallContent("call_2", "lookup", Map.of("city", "Busan")),
            new ToolCallContent("call_1", "lookup", Map.of("city", "Seoul")));

    assertThatThrownBy(() -> mapper.map(request(assistant), DEFAULTS))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(EDITED_ECHO);
  }

  @Test
  void refusesAnEchoWhoseToolCallIdWasRewritten() {
    // A rewritten call id is how a tool result is keyed. Echoing the completion would send the old
    // id and orphan the result the caller is about to report under the new one.
    Message assistant =
        echo(
            ChatCompletionsFixture.withToolCalls(
                null, ChatCompletionsFixture.functionCall("call_1", "lookup", MODEL_ARGUMENTS)),
            new ToolCallContent("call_9", "lookup", Map.of("city", "Seoul")));

    assertThatThrownBy(() -> mapper.map(request(assistant), DEFAULTS))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(EDITED_ECHO);
  }

  @Test
  void refusesAnEchoWhoseToolCallNameWasRewritten() {
    Message assistant =
        echo(
            ChatCompletionsFixture.withToolCalls(
                null, ChatCompletionsFixture.functionCall("call_1", "lookup", MODEL_ARGUMENTS)),
            new ToolCallContent("call_1", "forecast", Map.of("city", "Seoul")));

    assertThatThrownBy(() -> mapper.map(request(assistant), DEFAULTS))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(EDITED_ECHO);
  }

  @Test
  void refusesAnEchoWhoseToolCallArgumentsWereRewritten() {
    // The edit a caller is most likely to make - correcting an argument the model got wrong - is
    // exactly the one the echo used to discard.
    Message assistant =
        echo(
            ChatCompletionsFixture.withToolCalls(
                null, ChatCompletionsFixture.functionCall("call_1", "lookup", MODEL_ARGUMENTS)),
            new ToolCallContent("call_1", "lookup", Map.of("city", "Busan")));

    Throwable failure = catchThrowable(() -> mapper.map(request(assistant), DEFAULTS));

    assertThat(failure)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(EDITED_ECHO)
        .hasMessageNotContaining("Busan")
        .hasMessageNotContaining("Seoul");
  }

  @Test
  void refusesAnEchoWhoseArgumentsGainedAKey() {
    Message assistant =
        echo(
            ChatCompletionsFixture.withToolCalls(
                null, ChatCompletionsFixture.functionCall("call_1", "lookup", MODEL_ARGUMENTS)),
            new ToolCallContent("call_1", "lookup", Map.of("city", "Seoul", "unit", "celsius")));

    assertThatThrownBy(() -> mapper.map(request(assistant), DEFAULTS))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(EDITED_ECHO);
  }

  @Test
  void echoesATurnWhoseBlankCompletionContentContributedNoTextPart() {
    // The response mapper contributes no text part for blank content, so the turn it produced for a
    // completion whose content is "   " carries only the tool call. That is not an edit, and
    // refusing it would break the ordinary tool round trip.
    Message assistant =
        echo(
            ChatCompletionsFixture.withToolCalls(
                "   ", ChatCompletionsFixture.functionCall("call_1", "lookup", MODEL_ARGUMENTS)),
            new ToolCallContent("call_1", "lookup", Map.of("city", "Seoul")));

    ChatCompletionCreateParams params = mapper.map(request(assistant), DEFAULTS);

    assertThat(params.messages().get(0).asAssistant().content().orElseThrow().asText())
        .isEqualTo("   ");
  }

  @Test
  void refusesAnEchoThatGainedTextBesideARefusal() {
    // A refusal-only completion maps to a turn with no content at all, so any text on it is the
    // caller's, and echoing the refusal would drop it.
    Message assistant =
        echo(
            ChatCompletionMessage.builder()
                .content((String) null)
                .refusal("I cannot help with that")
                .build(),
            new TextContent("but here is how"));

    assertThatThrownBy(() -> mapper.map(request(assistant), DEFAULTS))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(EDITED_ECHO);
  }

  @Test
  void refusesAnEchoWhoseCompletionArgumentsRepeatAKeyWithoutRevealingThem() {
    // The comparison parses the completion's arguments with the same strict reader as the response
    // mapper. A lenient last-wins parse would read {"city":"Seoul","city":"Seoul"} as {"city":
    // "Seoul"}, call it a match, and send a duplicate key back to the provider.
    Message assistant =
        echo(
            ChatCompletionsFixture.withToolCalls(
                null,
                ChatCompletionsFixture.functionCall(
                    "call_1", "lookup", "{\"city\": \"Seoul\", \"city\": \"Seoul\"}")),
            new ToolCallContent("call_1", "lookup", Map.of("city", "Seoul")));

    Throwable failure = catchThrowable(() -> mapper.map(request(assistant), DEFAULTS));

    assertThat(failure).isInstanceOf(IllegalArgumentException.class).hasNoCause();
    assertThat(failure)
        .hasMessageContaining("lookup")
        .hasMessageContaining("call_1")
        .hasMessageNotContaining("city")
        .hasMessageNotContaining("Seoul");
    for (Throwable link : FailureChain.of(failure)) {
      assertThat(link).isNotInstanceOf(JsonProcessingException.class);
      assertThat(String.valueOf(link.getMessage())).doesNotContain("Seoul");
    }
  }

  @Test
  void refusesAnEchoWhoseCompletionArgumentsCarryTrailingInputWithoutRevealingIt() {
    // Same strictness on the other lenient default: readTree stops after the first value, so
    // {"city":"Seoul"} oops would compare equal and the trailing input would go back on the wire.
    Message assistant =
        echo(
            ChatCompletionsFixture.withToolCalls(
                null,
                ChatCompletionsFixture.functionCall(
                    "call_1", "lookup", "{\"city\": \"Seoul\"} patient_record_id")),
            new ToolCallContent("call_1", "lookup", Map.of("city", "Seoul")));

    Throwable failure = catchThrowable(() -> mapper.map(request(assistant), DEFAULTS));

    assertThat(failure).isInstanceOf(IllegalArgumentException.class).hasNoCause();
    assertThat(failure)
        .hasMessageContaining("lookup")
        .hasMessageContaining("call_1")
        .hasMessageNotContaining("patient_record_id")
        .hasMessageNotContaining("Seoul");
    for (Throwable link : FailureChain.of(failure)) {
      assertThat(link).isNotInstanceOf(JsonProcessingException.class);
      assertThat(String.valueOf(link.getMessage())).doesNotContain("patient_record_id");
    }
  }

  @Test
  void echoesTheTurnTheResponseMapperProducedFromACompletionCarryingBothTextAndACall() {
    // The round trip the engine actually runs: what the response mapper produces must always be
    // echoable, or the new rule would break the tool loop it is meant to protect.
    ChatCompletionMessage sdkMessage =
        ChatCompletionsFixture.withToolCalls(
            "let me look that up",
            ChatCompletionsFixture.functionCall("call_1", "lookup", MODEL_ARGUMENTS));
    ChatCompletion completion =
        ChatCompletionsFixture.completion(
            sdkMessage, ChatCompletion.Choice.FinishReason.TOOL_CALLS);
    Message mapped = new ChatCompletionResponseMapper().map(completion).messages().get(0);

    ChatCompletionCreateParams params = mapper.map(request(mapped), DEFAULTS);

    ChatCompletionAssistantMessageParam echoed = params.messages().get(0).asAssistant();
    assertThat(echoed.content().orElseThrow().asText()).isEqualTo("let me look that up");
    assertThat(echoed.toolCalls().orElseThrow())
        .singleElement()
        .satisfies(
            call ->
                assertThat(call.asFunction().function().arguments()).isEqualTo(MODEL_ARGUMENTS));
  }

  @Test
  void echoesTheTurnTheResponseMapperProducedFromArgumentsWithNestedAndNullValues() {
    // A parsed argument map is what the comparison is against, so the types the parser produces -
    // nested objects, arrays, numbers, and a null value - have to compare equal to themselves.
    String arguments = "{\"city\": \"Seoul\", \"unit\": null, \"days\": 3, \"tags\": [\"a\", 1]}";
    ChatCompletion completion =
        ChatCompletionsFixture.completion(
            ChatCompletionsFixture.withToolCalls(
                null, ChatCompletionsFixture.functionCall("call_1", "lookup", arguments)),
            ChatCompletion.Choice.FinishReason.TOOL_CALLS);
    Message mapped = new ChatCompletionResponseMapper().map(completion).messages().get(0);

    ChatCompletionCreateParams params = mapper.map(request(mapped), DEFAULTS);

    ChatCompletionMessageToolCall echoed =
        params.messages().get(0).asAssistant().toolCalls().orElseThrow().get(0);
    assertThat(echoed.asFunction().function().arguments()).isEqualTo(arguments);
  }

  @Test
  void echoesACompletionWhoseToolCallCarriesNoArgumentsAtAll() {
    // An empty arguments string is how a no-argument tool call arrives, and the response mapper
    // reads it as an empty map. The comparison has to agree, or every such call would be refused.
    Message assistant =
        echo(
            ChatCompletionsFixture.withToolCalls(
                null, ChatCompletionsFixture.functionCall("call_1", "now", "")),
            new ToolCallContent("call_1", "now", Map.of()));

    ChatCompletionCreateParams params = mapper.map(request(assistant), DEFAULTS);

    assertThat(
            params
                .messages()
                .get(0)
                .asAssistant()
                .toolCalls()
                .orElseThrow()
                .get(0)
                .asFunction()
                .function()
                .arguments())
        .isEmpty();
  }

  private static Message echo(ChatCompletionMessage sdkMessage, Content... content) {
    return new Message(Role.ASSISTANT, List.of(content), null, Map.of(), sdkMessage);
  }

  private static ModelRequest request(Message message) {
    return ModelRequest.builder()
        .messages(List.of(message))
        .options(ModelRequestOptions.empty())
        .cancellationSignal(new CancellationSignal())
        .build();
  }
}
