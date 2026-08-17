package io.github.hellices.agentframework.openai.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessageCustomToolCall;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.chat.completions.ChatCompletionMessageToolCall;
import io.github.hellices.agentframework.api.agent.CancellationSignal;
import io.github.hellices.agentframework.api.message.Content;
import io.github.hellices.agentframework.api.message.FinishReason;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.Role;
import io.github.hellices.agentframework.api.message.TextContent;
import io.github.hellices.agentframework.api.message.ToolCallContent;
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

class ChatCompletionResponseMapperToolCallTest {

  private static final OpenAiChatSettings DEFAULTS =
      new OpenAiChatSettings("gpt-4.1-mini", null, null, Duration.ofSeconds(60));

  private final ChatCompletionResponseMapper mapper = new ChatCompletionResponseMapper();

  @Test
  void mapsFunctionToolCallsInOrderAfterTheText() {
    ChatCompletion completion =
        ChatCompletionsFixture.completion(
            ChatCompletionsFixture.withToolCalls(
                "looking it up",
                ChatCompletionsFixture.functionCall("call_1", "lookup", "{\"city\":\"Seoul\"}"),
                ChatCompletionsFixture.functionCall("call_2", "clock", "{\"zone\":\"KST\"}")),
            ChatCompletion.Choice.FinishReason.TOOL_CALLS);

    ModelResponse response = mapper.map(completion);

    List<Content> content = response.messages().get(0).content();
    assertThat(content).hasSize(3);
    assertThat(content.get(0)).isInstanceOf(TextContent.class);
    ToolCallContent first = (ToolCallContent) content.get(1);
    assertThat(first.callId()).isEqualTo("call_1");
    assertThat(first.name()).isEqualTo("lookup");
    assertThat(first.arguments()).isEqualTo(Map.of("city", "Seoul"));
    assertThat(((ToolCallContent) content.get(2)).callId()).isEqualTo("call_2");
    assertThat(response.finishReason()).isEqualTo(FinishReason.TOOL_CALLS);
  }

  @Test
  void mapsEmptyArgumentsToAnEmptyMap() {
    // OpenAI emits "" or "{}" for a zero-argument tool. Both mean the same thing, and neither is a
    // parse failure.
    ChatCompletion empty =
        ChatCompletionsFixture.completion(
            ChatCompletionsFixture.withToolCalls(
                null, ChatCompletionsFixture.functionCall("call_1", "ping", "")),
            ChatCompletion.Choice.FinishReason.TOOL_CALLS);
    ChatCompletion object =
        ChatCompletionsFixture.completion(
            ChatCompletionsFixture.withToolCalls(
                null, ChatCompletionsFixture.functionCall("call_1", "ping", "{}")),
            ChatCompletion.Choice.FinishReason.TOOL_CALLS);

    assertThat(toolCall(mapper.map(empty)).arguments()).isEmpty();
    assertThat(toolCall(mapper.map(object)).arguments()).isEmpty();
  }

  @Test
  void keepsAnArgumentWhoseJsonValueIsNull() {
    // Models routinely send null for an optional parameter: {"unit":null} means "I did not choose a
    // unit", which is not the same as omitting the key. Map.copyOf rejects a null value with a bare
    // NullPointerException, so a perfectly ordinary tool call would crash the loop with an
    // unexplained failure. ToolCallContent copies into a LinkedHashMap and keeps the null.
    ChatCompletion completion =
        ChatCompletionsFixture.completion(
            ChatCompletionsFixture.withToolCalls(
                null,
                ChatCompletionsFixture.functionCall(
                    "call_1", "lookup", "{\"unit\":null,\"city\":\"Seoul\"}")),
            ChatCompletion.Choice.FinishReason.TOOL_CALLS);

    Map<String, Object> arguments = toolCall(mapper.map(completion)).arguments();

    assertThat(arguments).hasSize(2).containsKey("unit").containsEntry("city", "Seoul");
    assertThat(arguments.get("unit")).isNull();
  }

  @Test
  void keepsNestedObjectsAndArraysInsideTheArguments() {
    // A tool schema is not required to be flat, and a JSON object or array argument has to
    // arrive as a Map or a List rather than as a JsonNode or a string: the tool executor reads
    // the map the model sent, and the request mapper re-serialises it when no SDK handle
    // survives.
    ChatCompletion completion =
        ChatCompletionsFixture.completion(
            ChatCompletionsFixture.withToolCalls(
                null,
                ChatCompletionsFixture.functionCall(
                    "call_1", "search", "{\"filter\":{\"tags\":[\"a\",\"b\"]},\"limit\":3}")),
            ChatCompletion.Choice.FinishReason.TOOL_CALLS);

    Map<String, Object> arguments = toolCall(mapper.map(completion)).arguments();

    assertThat(arguments).containsEntry("filter", Map.of("tags", List.of("a", "b")));
    assertThat(arguments).containsEntry("limit", 3);
  }

  @Test
  void carriesTheSdkCallAsTheRawRepresentationSoTheArgumentsStringStaysExact() {
    // The parsed map cannot reproduce the model's own spacing or key order, and the echo rule in
    // ChatCompletionRequestMapper is byte-faithful only while the SDK call is reachable.
    ChatCompletion completion =
        ChatCompletionsFixture.completion(
            ChatCompletionsFixture.withToolCalls(
                null,
                ChatCompletionsFixture.functionCall("call_1", "lookup", "{\"city\" : \"Seoul\"}")),
            ChatCompletion.Choice.FinishReason.TOOL_CALLS);

    Object raw = toolCall(mapper.map(completion)).rawRepresentation();

    assertThat(raw).isInstanceOf(ChatCompletionMessageFunctionToolCall.class);
    assertThat(((ChatCompletionMessageFunctionToolCall) raw).function().arguments())
        .isEqualTo("{\"city\" : \"Seoul\"}");
  }

  @Test
  void producesAToolCallTurnTheRequestMapperCanSendBackUnchanged() {
    // The other half of Task 5's counterfactual: a mapped tool-calling turn is echoed into the next
    // request, and the arguments string that comes back out is the one the model produced rather
    // than a re-serialisation of the parsed map.
    ChatCompletion completion =
        ChatCompletionsFixture.completion(
            ChatCompletionsFixture.withToolCalls(
                "looking it up",
                ChatCompletionsFixture.functionCall("call_1", "lookup", "{\"city\" : \"Seoul\"}")),
            ChatCompletion.Choice.FinishReason.TOOL_CALLS);

    List<Message> history = new ArrayList<>();
    history.add(new Message(Role.USER, List.of(new TextContent("weather in Seoul?"))));
    List<Message> turn = mapper.map(completion).messages();
    history.addAll(turn);
    ChatCompletionCreateParams params =
        new ChatCompletionRequestMapper().map(request(history), DEFAULTS);

    assertThat(turn.get(0).content()).hasSize(2).element(1).isInstanceOf(ToolCallContent.class);
    assertThat(params.messages()).hasSize(2);
    List<ChatCompletionMessageToolCall> echoed =
        params.messages().get(1).asAssistant().toolCalls().orElseThrow();
    assertThat(echoed).hasSize(1);
    assertThat(echoed.get(0).asFunction().id()).isEqualTo("call_1");
    assertThat(echoed.get(0).asFunction().function().arguments())
        .isEqualTo("{\"city\" : \"Seoul\"}");
  }

  @Test
  void rejectsArgumentsThatAreNotAJsonObject() {
    // ToolCallContent.arguments is a Map, so a JSON array or scalar cannot round-trip. Failing here
    // is honest; coercing would invent a key the model never sent.
    ChatCompletion completion =
        ChatCompletionsFixture.completion(
            ChatCompletionsFixture.withToolCalls(
                null, ChatCompletionsFixture.functionCall("call_1", "lookup", "[1,2]")),
            ChatCompletion.Choice.FinishReason.TOOL_CALLS);

    assertThatThrownBy(() -> mapper.map(completion))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("lookup")
        .hasMessageContaining("call_1")
        .hasMessageNotContaining("[1,2]");
  }

  @Test
  void rejectsArgumentsThatAreAJsonScalar() {
    // "7" parses, so only the isObject check stands between a scalar and a tool call with no
    // arguments at all.
    ChatCompletion completion =
        ChatCompletionsFixture.completion(
            ChatCompletionsFixture.withToolCalls(
                null, ChatCompletionsFixture.functionCall("call_1", "lookup", "7")),
            ChatCompletion.Choice.FinishReason.TOOL_CALLS);

    assertThatThrownBy(() -> mapper.map(completion))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("lookup")
        .hasMessageContaining("call_1");
  }

  @Test
  void rejectsArgumentsThatAreNotValidJson() {
    ChatCompletion completion =
        ChatCompletionsFixture.completion(
            ChatCompletionsFixture.withToolCalls(
                null, ChatCompletionsFixture.functionCall("call_1", "lookup", "{oops")),
            ChatCompletion.Choice.FinishReason.TOOL_CALLS);

    assertThatThrownBy(() -> mapper.map(completion))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("call_1")
        .hasMessageNotContaining("oops")
        .hasNoCause();
  }

  @Test
  void rejectsArgumentsThatConcatenateTwoJsonObjects() {
    // Jackson reads one value and stops, so without FAIL_ON_TRAILING_TOKENS the second object is
    // dropped without a word and the tool is called with the first one. Two objects are not one
    // argument list, and picking a half of a response the model did not send that way is exactly
    // the silent coercion this mapper refuses everywhere else.
    ChatCompletion completion =
        ChatCompletionsFixture.completion(
            ChatCompletionsFixture.withToolCalls(
                null,
                ChatCompletionsFixture.functionCall(
                    "call_1", "lookup", "{\"city\":\"Seoul\"}{\"city\":\"Busan\"}")),
            ChatCompletion.Choice.FinishReason.TOOL_CALLS);

    assertThatThrownBy(() -> mapper.map(completion))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("lookup")
        .hasMessageContaining("call_1")
        .hasMessageNotContaining("Seoul")
        .hasMessageNotContaining("Busan")
        .hasNoCause();
  }

  @Test
  void rejectsArgumentsThatTrailGarbageAfterAJsonObject() {
    // The same hole with something that is not even JSON after the object: truncated or doubled
    // output from a compatible server reaches the tool as a valid-looking call unless the parser
    // is told that a value has to consume the whole string.
    ChatCompletion completion =
        ChatCompletionsFixture.completion(
            ChatCompletionsFixture.withToolCalls(
                null,
                ChatCompletionsFixture.functionCall(
                    "call_1", "lookup", "{\"city\":\"Seoul\"} oops")),
            ChatCompletion.Choice.FinishReason.TOOL_CALLS);

    assertThatThrownBy(() -> mapper.map(completion))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("lookup")
        .hasMessageContaining("call_1")
        .hasMessageNotContaining("Seoul")
        .hasMessageNotContaining("oops")
        .hasNoCause();
  }

  @Test
  void rejectsArgumentsThatRepeatAKey() {
    // Jackson's default is last-wins, which silently changes what the model asked for: a call that
    // said Seoul and then Busan would reach the tool as Busan alone, and neither the caller nor the
    // model would ever learn that a value was discarded. The duplicate is ambiguous rather than
    // resolvable, so it fails.
    ChatCompletion completion =
        ChatCompletionsFixture.completion(
            ChatCompletionsFixture.withToolCalls(
                null,
                ChatCompletionsFixture.functionCall(
                    "call_1", "lookup", "{\"city\":\"Seoul\",\"city\":\"Busan\"}")),
            ChatCompletion.Choice.FinishReason.TOOL_CALLS);

    assertThatThrownBy(() -> mapper.map(completion))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("lookup")
        .hasMessageContaining("call_1")
        .hasMessageNotContaining("Seoul")
        .hasMessageNotContaining("Busan")
        .hasNoCause();
  }

  @Test
  void quotesNoArgumentValueAnywhereInAParseFailureChain() {
    // Both shapes below make Jackson name the token it choked on, and that token is model output.
    // Disabling the source location does not help here: the quoted text is in the parser's own
    // message, not in a source excerpt. A cause is printed by every logger that prints a stack
    // trace, so "the failure names the tool and the call id and nothing else" only holds if no
    // parser exception is attached anywhere in the chain, as a cause or as a suppressed throwable.
    String secret = "sk9f3abSecretPatientToken";
    List<String> leakingArguments =
        List.of("{\"name\": " + secret + "}", "{\"city\":\"Seoul\"} " + secret);

    for (String arguments : leakingArguments) {
      ChatCompletion completion =
          ChatCompletionsFixture.completion(
              ChatCompletionsFixture.withToolCalls(
                  null, ChatCompletionsFixture.functionCall("call_1", "lookup", arguments)),
              ChatCompletion.Choice.FinishReason.TOOL_CALLS);

      Throwable failure = catchThrowable(() -> mapper.map(completion));

      assertThat(failure).isInstanceOf(IllegalStateException.class).hasNoCause();
      assertThat(failure)
          .hasMessageContaining("lookup")
          .hasMessageContaining("call_1")
          .hasMessageContaining("exactly one JSON object with unique keys");
      for (Throwable link : FailureChain.of(failure)) {
        assertThat(link).isNotInstanceOf(JsonProcessingException.class);
        assertThat(String.valueOf(link.getMessage()))
            .doesNotContain(secret)
            .doesNotContain(arguments);
      }
    }
  }

  @Test
  void rejectsAToolCallWithoutAnId() {
    // ToolCallContent rejects a blank call id, so without this check the failure would surface as
    // an unexplained IllegalArgumentException from the core value type. Never synthesise an id: the
    // tool result has to be keyed by the id the model actually issued.
    ChatCompletion completion =
        ChatCompletionsFixture.completion(
            ChatCompletionsFixture.withToolCalls(
                null, ChatCompletionsFixture.functionCall("", "lookup", "{}")),
            ChatCompletion.Choice.FinishReason.TOOL_CALLS);

    assertThatThrownBy(() -> mapper.map(completion))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("id");
  }

  @Test
  void rejectsAToolCallWithoutAName() {
    ChatCompletion completion =
        ChatCompletionsFixture.completion(
            ChatCompletionsFixture.withToolCalls(
                null, ChatCompletionsFixture.functionCall("call_1", "", "{}")),
            ChatCompletion.Choice.FinishReason.TOOL_CALLS);

    assertThatThrownBy(() -> mapper.map(completion))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("call_1");
  }

  @Test
  void rejectsAToolCallThatIsNotAFunctionCall() {
    ChatCompletionMessageToolCall custom =
        ChatCompletionMessageToolCall.ofCustom(
            ChatCompletionMessageCustomToolCall.builder()
                .id("call_9")
                .custom(
                    ChatCompletionMessageCustomToolCall.Custom.builder()
                        .name("run")
                        .input("ls")
                        .build())
                .build());
    ChatCompletion completion =
        ChatCompletionsFixture.completion(
            ChatCompletionsFixture.withToolCalls(null, custom),
            ChatCompletion.Choice.FinishReason.TOOL_CALLS);

    assertThatThrownBy(() -> mapper.map(completion))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("openai chat completions")
        .hasMessageContaining("function");
  }

  @ParameterizedTest(name = "{0} with no tool call fails")
  @MethodSource("toolCallFinishReasons")
  void rejectsAToolCallFinishReasonThatCarriesNoToolCall(
      ChatCompletion.Choice.FinishReason wireValue) {
    // The server said it stopped to call a tool and then sent none. Nothing downstream would
    // notice: AgentEngine ends its loop on an empty tool-call list, so the run would finish
    // successfully with no answer at all. A turn that promised a call and delivered none is a
    // broken response, not a final answer.
    ChatCompletion completion =
        ChatCompletionsFixture.completion(ChatCompletionsFixture.withoutContent(), wireValue);

    assertThatThrownBy(() -> mapper.map(completion))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(wireValue.asString())
        .hasMessageContaining("no tool call");
  }

  static Stream<Arguments> toolCallFinishReasons() {
    return Stream.of(
        Arguments.of(ChatCompletion.Choice.FinishReason.TOOL_CALLS),
        // The deprecated wire value maps to the same neutral reason, so it makes the same promise.
        Arguments.of(ChatCompletion.Choice.FinishReason.FUNCTION_CALL));
  }

  @Test
  void rejectsAToolCallFinishReasonThatCarriesOnlyText() {
    // The same contradiction with something to show for it. A compatible server that failed to
    // parse its own tool syntax and left the call in the content would otherwise have that text
    // returned as the model's final answer, which is the one outcome the caller must not be
    // handed silently.
    ChatCompletion completion =
        ChatCompletionsFixture.completion(
            ChatCompletionsFixture.text("{\"name\": \"lookup\"}"),
            ChatCompletion.Choice.FinishReason.TOOL_CALLS);

    assertThatThrownBy(() -> mapper.map(completion))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("tool_calls")
        .hasMessageNotContaining("lookup");
  }

  @ParameterizedTest(name = "a tool call arriving with {0} is mapped and the reason stays {1}")
  @MethodSource("nonToolFinishReasons")
  void mapsAToolCallThatArrivesWithANonToolFinishReason(
      ChatCompletion.Choice.FinishReason wireValue, FinishReason expected) {
    // The asymmetric half of the pairing rule, stated as an executable fact rather than as a
    // sentence in a javadoc. A call that is present is executable whatever the finish reason says,
    // and compatible servers do label a tool-calling turn "stop" or truncate one with "length".
    // Rejecting that turn, or rewriting its finish reason to TOOL_CALLS to make the pair agree,
    // would break runs that work today and would report a reason the server never sent.
    ChatCompletion completion =
        ChatCompletionsFixture.completion(
            ChatCompletionsFixture.withToolCalls(
                "on it",
                ChatCompletionsFixture.functionCall("call_1", "lookup", "{\"city\":\"Seoul\"}")),
            wireValue);

    ModelResponse response = mapper.map(completion);

    List<Content> content = response.messages().get(0).content();
    assertThat(content).hasSize(2);
    ToolCallContent call = (ToolCallContent) content.get(1);
    assertThat(call.callId()).isEqualTo("call_1");
    assertThat(call.name()).isEqualTo("lookup");
    assertThat(call.arguments()).isEqualTo(Map.of("city", "Seoul"));
    assertThat(response.finishReason()).isEqualTo(expected);
  }

  static Stream<Arguments> nonToolFinishReasons() {
    return Stream.of(
        Arguments.of(ChatCompletion.Choice.FinishReason.STOP, FinishReason.STOP),
        // A turn cut off mid-flight still carries whatever calls arrived whole.
        Arguments.of(ChatCompletion.Choice.FinishReason.LENGTH, FinishReason.LENGTH));
  }

  @Test
  void rejectsTheDeprecatedFunctionCallPayload() {
    // The pre-tools wire shape: one call on the message with no id of its own. It cannot be mapped,
    // because a tool result is keyed by the call id the model issued and this shape has none, and
    // synthesising one would key a result to a call that never existed. Ignoring the field instead
    // would drop a call the model asked for and end the run with an empty answer.
    ChatCompletion completion =
        ChatCompletionsFixture.completion(
            ChatCompletionsFixture.withDeprecatedFunctionCall(
                "lookup", "{\"city\":\"Seoul\",\"secret\":\"pii\"}"),
            ChatCompletion.Choice.FinishReason.FUNCTION_CALL);

    assertThatThrownBy(() -> mapper.map(completion))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("function_call")
        .hasMessageContaining("lookup")
        .hasMessageNotContaining("Seoul")
        .hasMessageNotContaining("pii");
  }

  @Test
  void mapsAToolCallOnlyTurnWithoutInventingText() {
    // The wire shape of a turn that is nothing but calls: content is JSON null, and the message the
    // adapter produces carries the calls alone rather than an empty text part.
    ChatCompletion completion =
        ChatCompletionsFixture.completion(
            ChatCompletionsFixture.withToolCalls(
                null, ChatCompletionsFixture.functionCall("call_1", "ping", "{}")),
            ChatCompletion.Choice.FinishReason.TOOL_CALLS);

    Message message = mapper.map(completion).messages().get(0);

    assertThat(message.role()).isEqualTo(Role.ASSISTANT);
    assertThat(message.content()).singleElement().isInstanceOf(ToolCallContent.class);
    assertThat(message.text()).isEmpty();
  }

  private static ToolCallContent toolCall(ModelResponse response) {
    return (ToolCallContent) response.messages().get(0).content().get(0);
  }

  private static ModelRequest request(List<Message> messages) {
    return ModelRequest.builder()
        .messages(messages)
        .options(ModelRequestOptions.empty())
        .cancellationSignal(new CancellationSignal())
        .build();
  }
}
