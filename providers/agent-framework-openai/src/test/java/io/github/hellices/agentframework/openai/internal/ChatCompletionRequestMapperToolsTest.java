package io.github.hellices.agentframework.openai.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.openai.core.JsonValue;
import com.openai.models.FunctionDefinition;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionMessageToolCall;
import io.github.hellices.agentframework.api.agent.CancellationSignal;
import io.github.hellices.agentframework.api.message.ExtensionContent;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.Role;
import io.github.hellices.agentframework.api.message.TextContent;
import io.github.hellices.agentframework.api.message.ToolCallContent;
import io.github.hellices.agentframework.api.message.ToolResultContent;
import io.github.hellices.agentframework.api.tool.ToolDefinition;
import io.github.hellices.agentframework.spi.model.ModelRequest;
import io.github.hellices.agentframework.spi.model.ModelRequestOptions;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChatCompletionRequestMapperToolsTest {

  private static final OpenAiChatSettings DEFAULTS =
      new OpenAiChatSettings("gpt-4.1-mini", null, null, Duration.ofSeconds(60));

  private final ChatCompletionRequestMapper mapper = new ChatCompletionRequestMapper();

  @Test
  void mapsAToolDefinitionOntoAFunctionToolWithItsSchemaIntact() {
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "object");
    schema.put("properties", Map.of("city", Map.of("type", "string")));
    schema.put("required", List.of("city"));
    ToolDefinition tool = new ToolDefinition("lookup", "Looks a city up", schema);

    ChatCompletionCreateParams params = mapper.map(requestWithTools(List.of(tool)), DEFAULTS);

    FunctionDefinition function = params.tools().orElseThrow().get(0).asFunction().function();
    assertThat(function.name()).isEqualTo("lookup");
    assertThat(function.description()).hasValue("Looks a city up");
    assertThat(function.parameters().orElseThrow()._additionalProperties())
        .containsEntry("type", JsonValue.from("object"))
        .containsEntry("properties", JsonValue.from(Map.of("city", Map.of("type", "string"))))
        .containsEntry("required", JsonValue.from(List.of("city")));
  }

  @Test
  void mapsEveryToolInTheRequestInOrder() {
    // A partial mapping is the same defect as no mapping: the model is offered a tool set that is
    // not the one the engine authorised, and a call it makes for a dropped tool has no handler.
    ChatCompletionCreateParams params =
        mapper.map(
            requestWithTools(
                List.of(
                    new ToolDefinition("first", null, Map.of()),
                    new ToolDefinition("second", null, Map.of()))),
            DEFAULTS);

    assertThat(params.tools().orElseThrow())
        .extracting(tool -> tool.asFunction().function().name())
        .containsExactly("first", "second");
  }

  @Test
  void omitsABlankDescriptionAndAnEmptySchema() {
    // ToolDefinition normalises a null description to "". Sending an empty description or an empty
    // parameters object says something different from saying nothing, so neither is sent.
    ToolDefinition tool = new ToolDefinition("ping", null, Map.of());

    ChatCompletionCreateParams params = mapper.map(requestWithTools(List.of(tool)), DEFAULTS);

    FunctionDefinition function = params.tools().orElseThrow().get(0).asFunction().function();
    assertThat(function.description()).isEmpty();
    assertThat(function.parameters()).isEmpty();
  }

  @Test
  void fansOneToolMessageOutIntoOneParamPerResult() {
    // The engine reports a whole round of tool results as one Role.TOOL message holding N
    // ToolResultContent, while Chat Completions requires one tool message per tool_call_id. Losing
    // this fan-out means the model sees a call it never got an answer for, which providers reject.
    Message toolMessage =
        new Message(
            Role.TOOL,
            List.of(
                new ToolResultContent("call_1", "lookup", List.of(new TextContent("sunny")), false),
                new ToolResultContent(
                    "call_2", "lookup", List.of(new TextContent("rainy")), false)));

    ChatCompletionCreateParams params = mapper.map(request(List.of(toolMessage)), DEFAULTS);

    List<ChatCompletionMessageParam> messages = params.messages();
    assertThat(messages).hasSize(2);
    assertThat(messages.get(0).asTool().toolCallId()).isEqualTo("call_1");
    assertThat(messages.get(0).asTool().content().asText()).isEqualTo("sunny");
    assertThat(messages.get(1).asTool().toolCallId()).isEqualTo("call_2");
    assertThat(messages.get(1).asTool().content().asText()).isEqualTo("rainy");
  }

  @Test
  void joinsTheTextPartsOfOneToolResultWithNewlines() {
    Message toolMessage =
        new Message(
            Role.TOOL,
            List.of(
                new ToolResultContent(
                    "call_1",
                    "lookup",
                    List.of(new TextContent("sunny"), new TextContent("warm")),
                    false)));

    ChatCompletionCreateParams params = mapper.map(request(List.of(toolMessage)), DEFAULTS);

    assertThat(params.messages().get(0).asTool().content().asText()).isEqualTo("sunny\nwarm");
  }

  @Test
  void carriesAFailedToolResultAsItsText() {
    // Chat Completions has no error flag on a tool message. The text still reaches the model, and
    // the limitation is documented rather than papered over with an invented prefix.
    Message toolMessage =
        new Message(
            Role.TOOL,
            List.of(
                new ToolResultContent(
                    "call_1", "lookup", List.of(new TextContent("lookup failed")), true)));

    ChatCompletionCreateParams params = mapper.map(request(List.of(toolMessage)), DEFAULTS);

    assertThat(params.messages().get(0).asTool().content().asText()).isEqualTo("lookup failed");
  }

  @Test
  void rejectsNonToolResultContentInsideAToolMessage() {
    Message toolMessage = new Message(Role.TOOL, List.of(new TextContent("loose text")));

    assertThatThrownBy(() -> mapper.map(request(List.of(toolMessage)), DEFAULTS))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("text");
  }

  @Test
  void rejectsExtensionContentOnAToolMessageWithoutRevealingItsPayload() {
    // Extension content is refused the same way everywhere in this mapper, so a caller cannot learn
    // that a tool message is the one place an unmappable payload turns into an argument complaint.
    Message toolMessage = new Message(Role.TOOL, List.of(new SecretContent()));

    assertThatThrownBy(() -> mapper.map(request(List.of(toolMessage)), DEFAULTS))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("test.secret")
        .hasMessageNotContaining("sensitive payload");
  }

  @Test
  void rejectsExtensionContentInsideAToolResultWithoutRevealingItsPayload() {
    Message toolMessage =
        new Message(
            Role.TOOL,
            List.of(
                new ToolResultContent("call_1", "lookup", List.of(new SecretContent()), false)));

    assertThatThrownBy(() -> mapper.map(request(List.of(toolMessage)), DEFAULTS))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("test.secret")
        .hasMessageNotContaining("sensitive payload");
  }

  @Test
  void rejectsNonTextContentInsideAToolResult() {
    // A nested tool call has no representation inside a Chat Completions tool message. Flattening
    // it to its empty text would send an answer that silently lost what it was answering with.
    Message toolMessage =
        new Message(
            Role.TOOL,
            List.of(
                new ToolResultContent(
                    "call_1",
                    "lookup",
                    List.of(new ToolCallContent("call_2", "nested", Map.of())),
                    false)));

    assertThatThrownBy(() -> mapper.map(request(List.of(toolMessage)), DEFAULTS))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("tool_call");
  }

  @Test
  void echoesTheOriginalSdkAssistantMessageWhenOneIsAvailable() {
    // The arguments string the model produced must go back byte-identical. Re-serialising a parsed
    // map changes key order and number formatting, and some models are sensitive to that.
    ChatCompletionMessage sdkMessage =
        ChatCompletionMessage.builder()
            .content((String) null)
            .refusal((String) null)
            .addToolCall(
                ChatCompletionMessageToolCall.ofFunction(
                    ChatCompletionMessageFunctionToolCall.builder()
                        .id("call_1")
                        .function(
                            ChatCompletionMessageFunctionToolCall.Function.builder()
                                .name("lookup")
                                .arguments("{\"city\": \"Seoul\"}")
                                .build())
                        .build()))
            .build();
    Message assistant =
        new Message(
            Role.ASSISTANT,
            List.of(new ToolCallContent("call_1", "lookup", Map.of("city", "Seoul"))),
            null,
            Map.of(),
            sdkMessage);

    ChatCompletionCreateParams params = mapper.map(request(List.of(assistant)), DEFAULTS);

    ChatCompletionMessageToolCall echoed =
        params.messages().get(0).asAssistant().toolCalls().orElseThrow().get(0);
    assertThat(echoed.asFunction().function().arguments()).isEqualTo("{\"city\": \"Seoul\"}");
  }

  @Test
  void reconstructsTheAssistantEchoWhenNoSdkMessageIsAvailable() {
    // A caller-built history, a restored session, or a decorated response has no SDK object. The
    // echo must still be a legal assistant turn rather than a dropped tool call.
    Message assistant =
        new Message(
            Role.ASSISTANT,
            List.of(
                new TextContent("looking it up"),
                new ToolCallContent("call_1", "lookup", Map.of("city", "Seoul"))));

    ChatCompletionCreateParams params = mapper.map(request(List.of(assistant)), DEFAULTS);

    var echoed = params.messages().get(0).asAssistant();
    assertThat(echoed.content().orElseThrow().asText()).isEqualTo("looking it up");
    ChatCompletionMessageToolCall call = echoed.toolCalls().orElseThrow().get(0);
    assertThat(call.asFunction().id()).isEqualTo("call_1");
    assertThat(call.asFunction().function().name()).isEqualTo("lookup");
    assertThat(call.asFunction().function().arguments()).isEqualTo("{\"city\":\"Seoul\"}");
  }

  @Test
  void reconstructsTheAssistantEchoWhenTheRawRepresentationIsAForeignObject() {
    // A history provider or a decorating middleware can hand back a message whose raw handle is
    // some other SDK's object. Preferring the echo path only for a Chat Completions message is what
    // keeps that case a reconstruction rather than a dropped tool call.
    Message assistant =
        new Message(
            Role.ASSISTANT,
            List.of(new ToolCallContent("call_1", "lookup", Map.of("city", "Seoul"))),
            null,
            Map.of(),
            "a raw handle from another protocol");

    ChatCompletionCreateParams params = mapper.map(request(List.of(assistant)), DEFAULTS);

    ChatCompletionMessageToolCall call =
        params.messages().get(0).asAssistant().toolCalls().orElseThrow().get(0);
    assertThat(call.asFunction().id()).isEqualTo("call_1");
    assertThat(call.asFunction().function().arguments()).isEqualTo("{\"city\":\"Seoul\"}");
  }

  @Test
  void reconstructsEveryToolCallOfAnAssistantTurnInOrder() {
    Message assistant =
        new Message(
            Role.ASSISTANT,
            List.of(
                new ToolCallContent("call_1", "lookup", Map.of("city", "Seoul")),
                new ToolCallContent("call_2", "lookup", Map.of("city", "Busan"))));

    ChatCompletionCreateParams params = mapper.map(request(List.of(assistant)), DEFAULTS);

    assertThat(params.messages().get(0).asAssistant().toolCalls().orElseThrow())
        .extracting(call -> call.asFunction().id())
        .containsExactly("call_1", "call_2");
  }

  @Test
  void serialisesReconstructedArgumentsInDeclarationOrder() {
    // ToolCallContent keeps its arguments in insertion order, and the reconstruction must not
    // reorder them: an argument string that differs from the model's own changes the tool call.
    Map<String, Object> arguments = new LinkedHashMap<>();
    arguments.put("city", "Seoul");
    arguments.put("days", 3);
    Message assistant =
        new Message(Role.ASSISTANT, List.of(new ToolCallContent("call_1", "forecast", arguments)));

    ChatCompletionCreateParams params = mapper.map(request(List.of(assistant)), DEFAULTS);

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
        .isEqualTo("{\"city\":\"Seoul\",\"days\":3}");
  }

  @Test
  void omitsAssistantContentWhenTheTurnIsOnlyToolCalls() {
    Message assistant =
        new Message(Role.ASSISTANT, List.of(new ToolCallContent("call_1", "lookup", Map.of())));

    ChatCompletionCreateParams params = mapper.map(request(List.of(assistant)), DEFAULTS);

    assertThat(params.messages().get(0).asAssistant().content()).isEmpty();
  }

  @Test
  void rejectsExtensionContentOnAnAssistantTurnThatAlsoCallsATool() {
    // The echo path must validate before it builds, or an unmappable payload would leave silently
    // alongside a tool call the mapper did understand.
    Message assistant =
        new Message(
            Role.ASSISTANT,
            List.of(new ToolCallContent("call_1", "lookup", Map.of()), new SecretContent()));

    assertThatThrownBy(() -> mapper.map(request(List.of(assistant)), DEFAULTS))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("test.secret")
        .hasMessageNotContaining("sensitive payload");
  }

  @Test
  void rejectsExtensionContentOnAnEchoedSdkAssistantMessage() {
    // The SDK handle is a shortcut for the arguments string, not a licence to skip the content
    // check: a message the framework could not represent must fail whether or not it has one.
    ChatCompletionMessage sdkMessage =
        ChatCompletionMessage.builder().content("hi").refusal((String) null).build();
    Message assistant =
        new Message(Role.ASSISTANT, List.of(new SecretContent()), null, Map.of(), sdkMessage);

    assertThatThrownBy(() -> mapper.map(request(List.of(assistant)), DEFAULTS))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("test.secret")
        .hasMessageNotContaining("sensitive payload");
  }

  @Test
  void refusesArgumentsItCannotSerialiseWithoutRevealingThem() {
    // The reconstruction path is the only place this adapter writes JSON, and an argument value
    // Jackson has no serialiser for must fail loudly rather than echo an empty object. AGENTS.md
    // keeps tool arguments out of failure reports, so the message names the tool and the call and
    // stops there - and so does the chain behind it. Jackson appends the reference chain to its own
    // message ("through reference chain: java.util.LinkedHashMap[\"<key>\"]"), and an argument key
    // is part of the arguments, so attaching the serialiser exception as a cause would put the key
    // in every log that prints a stack trace while this adapter's own message carefully kept it
    // out. Same rule as the parse failure on the response side, for the same reason.
    String secretKey = "patient_record_id";
    Map<String, Object> arguments = new LinkedHashMap<>();
    arguments.put(secretKey, new Unserialisable());
    Message assistant =
        new Message(Role.ASSISTANT, List.of(new ToolCallContent("call_1", "lookup", arguments)));

    Throwable failure = catchThrowable(() -> mapper.map(request(List.of(assistant)), DEFAULTS));

    assertThat(failure).isInstanceOf(IllegalArgumentException.class).hasNoCause();
    assertThat(failure)
        .hasMessageContaining("lookup")
        .hasMessageContaining("call_1")
        .hasMessageNotContaining(secretKey);
    for (Throwable link : FailureChain.of(failure)) {
      assertThat(link).isNotInstanceOf(JsonProcessingException.class);
      assertThat(String.valueOf(link.getMessage())).doesNotContain(secretKey);
    }
  }

  @Test
  void refusesAToolTurnWithNoResultToReport() {
    // One framework tool message fans out into one param per result, so a message carrying none
    // used to contribute nothing at all: the assistant's tool call left the adapter unanswered and
    // the request went out anyway, which the provider rejects. The adapter cannot invent the
    // tool_call_id that would make such a message legal, so it refuses instead of dropping it.
    Message empty = new Message(Role.TOOL, List.of());

    assertThatThrownBy(() -> mapper.map(request(List.of(empty)), DEFAULTS))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(
            "a tool message must carry at least one tool result: openai chat completions"
                + " identifies a tool message by its tool_call_id, which an empty message cannot"
                + " supply");
  }

  @Test
  void refusesAnAssistantTurnThatWouldCarryNeitherContentNorAToolCall() {
    // The {"role":"assistant"} shape Chat Completions rejects. The SDK builder accepts it, so
    // without this rule the adapter would spend a billed request to be told it is invalid.
    Message empty = new Message(Role.ASSISTANT, List.of());

    assertThatThrownBy(() -> mapper.map(request(List.of(empty)), DEFAULTS))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(
            "an assistant message must carry text or a tool call: openai chat completions rejects"
                + " an assistant message with neither content nor tool_calls");
  }

  @Test
  void refusesAnEchoedSdkAssistantMessageWithNothingToSend() {
    // The echo path builds no param of its own, so the same rule has to be checked on the SDK
    // object: a message with no content, no refusal, and no tool call is the same unsendable shape.
    Message echoed =
        new Message(
            Role.ASSISTANT,
            List.of(),
            null,
            Map.of(),
            ChatCompletionMessage.builder().content((String) null).refusal((String) null).build());

    assertThatThrownBy(() -> mapper.map(request(List.of(echoed)), DEFAULTS))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(
            "an assistant message must carry text or a tool call: openai chat completions rejects"
                + " an assistant message with neither content nor tool_calls");
  }

  @Test
  void echoesAnSdkAssistantMessageThatOnlyRefuses() {
    // A refusal is content on the wire, so the rule is "nothing to send", not "no text": echoing a
    // refusal-only turn keeps the history the model produced intact.
    Message echoed =
        new Message(
            Role.ASSISTANT,
            List.of(),
            null,
            Map.of(),
            ChatCompletionMessage.builder()
                .content((String) null)
                .refusal("I cannot help with that")
                .build());

    ChatCompletionCreateParams params = mapper.map(request(List.of(echoed)), DEFAULTS);

    assertThat(params.messages().get(0).asAssistant().refusal())
        .contains("I cannot help with that");
  }

  @Test
  void sendsAnExplicitlyEmptyAssistantTextRatherThanRefusingIt() {
    // An empty text part is representable - {"role":"assistant","content":""} is a legal message -
    // so it is sent as what it is. Only a turn with no part at all has nothing the wire can carry.
    Message assistant = new Message(Role.ASSISTANT, List.of(new TextContent("")));

    ChatCompletionCreateParams params = mapper.map(request(List.of(assistant)), DEFAULTS);

    assertThat(params.messages().get(0).asAssistant().content().orElseThrow().asText()).isEmpty();
  }

  private static ModelRequest request(List<Message> messages) {
    return new ModelRequest(
        messages, ModelRequestOptions.empty(), new CancellationSignal(), List.of(), Map.of());
  }

  private static ModelRequest requestWithTools(List<ToolDefinition> tools) {
    return new ModelRequest(
        List.of(new Message(Role.USER, List.of(new TextContent("hello")))),
        ModelRequestOptions.empty(),
        new CancellationSignal(),
        tools,
        Map.of());
  }

  /**
   * Content the framework has no type for, used to prove the mapper refuses rather than guesses.
   */
  private static final class SecretContent extends ExtensionContent {

    private SecretContent() {
      super(Map.of(), null);
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

  /** An argument value Jackson has no serialiser for: no properties and no annotations. */
  private static final class Unserialisable {}
}
