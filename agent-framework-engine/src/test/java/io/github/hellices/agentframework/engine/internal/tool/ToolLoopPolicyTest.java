package io.github.hellices.agentframework.engine.internal.tool;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hellices.agentframework.api.message.Content;
import io.github.hellices.agentframework.api.message.FinishReason;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.Role;
import io.github.hellices.agentframework.api.message.TextContent;
import io.github.hellices.agentframework.api.message.ToolCallContent;
import io.github.hellices.agentframework.api.message.ToolResultContent;
import io.github.hellices.agentframework.api.tool.FunctionTool;
import io.github.hellices.agentframework.api.tool.ToolDefinition;
import io.github.hellices.agentframework.api.tool.ToolResult;
import io.github.hellices.agentframework.api.value.JsonObject;
import io.github.hellices.agentframework.api.value.JsonValues;
import io.github.hellices.agentframework.spi.model.ModelRequest;
import io.github.hellices.agentframework.spi.model.ModelResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The tool calls a response amounts to, and the request the iteration after them is made with. A
 * streamed response can report one call in fragments, so the fragments of a call id are merged into
 * the single call both loops execute exactly once and echo exactly once.
 */
class ToolLoopPolicyTest {

  @Test
  void fragmentsOfOneCallIdBecomeOneCallWithMergedArguments() {
    ModelResponse response =
        response(
            new ToolCallContent(
                "call-1", "weather", jsonObject(Map.of("city", "Seo", "unit", "c"))),
            new ToolCallContent("call-1", "weather", jsonObject(Map.of("city", "Seoul"))));

    List<ToolCallContent> calls = ToolLoopPolicy.toolCalls(response);

    assertThat(calls).hasSize(1);
    assertThat(calls.get(0).callId()).isEqualTo("call-1");
    assertThat(calls.get(0).name()).isEqualTo("weather");
    assertThat(calls.get(0).arguments())
        .isEqualTo(jsonObject(Map.of("city", "Seoul", "unit", "c")));
  }

  @Test
  void mergedCallsKeepTheOrderTheirFirstFragmentArrivedIn() {
    ModelResponse response =
        response(
            new ToolCallContent("call-2", "second", JsonObject.empty()),
            new ToolCallContent("call-1", "first", JsonObject.empty()),
            new ToolCallContent("call-2", "second", jsonObject(Map.of("done", true))));

    assertThat(ToolLoopPolicy.toolCalls(response))
        .extracting(ToolCallContent::callId)
        .containsExactly("call-2", "call-1");
  }

  @Test
  void mergingKeepsTheProviderPropertiesAndRawHandleOfTheFragments() {
    ModelResponse response =
        response(
            new ToolCallContent(
                "call-1",
                "weather",
                JsonObject.empty(),
                jsonObject(Map.of("index", 0, "shared", "first")),
                "raw-first"),
            new ToolCallContent(
                "call-1",
                "weather",
                JsonObject.empty(),
                jsonObject(Map.of("shared", "second")),
                "raw-second"),
            new ToolCallContent(
                "call-1", "weather", JsonObject.empty(), jsonObject(Map.of("last", true)), null));

    ToolCallContent merged = ToolLoopPolicy.toolCalls(response).get(0);

    assertThat(merged.additionalProperties())
        .isEqualTo(jsonObject(Map.of("index", 0, "shared", "second", "last", true)));
    assertThat(merged.rawRepresentation()).isEqualTo("raw-second");
  }

  @Test
  void aCallIdReportedWithTwoToolNamesIsRejected() {
    ModelResponse response =
        response(
            new ToolCallContent("call-1", "weather", JsonObject.empty()),
            new ToolCallContent("call-1", "forecast", JsonObject.empty()));

    assertThatThrownBy(() -> ToolLoopPolicy.toolCalls(response))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("tool call call-1 was reported as both 'weather' and 'forecast'");
  }

  @Test
  void aResponseWithoutToolCallsHasNoCalls() {
    assertThat(ToolLoopPolicy.toolCalls(response(new TextContent("just text")))).isEmpty();
  }

  @Test
  void theNextRequestEchoesTheMergedCallInPlaceOfItsFragments() {
    List<Message> responseMessages =
        List.of(
            new Message(
                Role.ASSISTANT,
                List.of(
                    new TextContent("checking"),
                    new ToolCallContent(
                        "call-1", "weather", jsonObject(Map.of("city", "Seo", "unit", "c"))))),
            new Message(
                Role.ASSISTANT,
                List.of(
                    new ToolCallContent(
                        "call-1", "weather", jsonObject(Map.of("city", "Seoul"))))));
    List<ToolCallContent> calls = ToolLoopPolicy.toolCalls(response(responseMessages));
    Message results = ToolLoopPolicy.toolResultMessage(List.of(result("call-1", "weather")));

    ModelRequest next =
        new ToolLoopPolicy(List.of(), List.of(), 4)
            .nextRequest(request(), responseMessages, calls, results, 0);

    assertThat(next.messages()).hasSize(3);
    assertThat(next.messages().get(1).text()).isEqualTo("checking");
    assertThat(toolCallsOf(next))
        .singleElement()
        .satisfies(
            call -> {
              assertThat(call.callId()).isEqualTo("call-1");
              assertThat(call.arguments())
                  .isEqualTo(jsonObject(Map.of("city", "Seoul", "unit", "c")));
            });
    assertThat(toolResultsOf(next)).extracting(ToolResultContent::callId).containsExactly("call-1");
  }

  @Test
  void aMessageLeftEmptyByItsFragmentsIsDroppedFromTheNextRequest() {
    Message text = new Message(Role.ASSISTANT, List.of(new TextContent("checking")));
    List<Message> responseMessages =
        List.of(
            text,
            new Message(
                Role.ASSISTANT,
                List.of(
                    new ToolCallContent("call-1", "weather", jsonObject(Map.of("city", "Seo"))))),
            new Message(
                Role.ASSISTANT,
                List.of(
                    new ToolCallContent(
                        "call-1", "weather", jsonObject(Map.of("city", "Seoul"))))));
    List<ToolCallContent> calls = ToolLoopPolicy.toolCalls(response(responseMessages));
    Message results = ToolLoopPolicy.toolResultMessage(List.of(result("call-1", "weather")));

    ModelRequest next =
        new ToolLoopPolicy(List.of(), List.of(), 4)
            .nextRequest(request(), responseMessages, calls, results, 0);

    assertThat(next.messages()).hasSize(4);
    assertThat(next.messages().get(1)).isSameAs(text);
    assertThat(toolCallsOf(next)).hasSize(1);
  }

  @Test
  void anUnsplitResponseIsEchoedMessageForMessage() {
    Message assistant =
        new Message(
            Role.ASSISTANT,
            List.of(
                new TextContent("checking"),
                new ToolCallContent("call-1", "weather", jsonObject(Map.of("city", "Seoul"))),
                new ToolCallContent("call-2", "forecast", jsonObject(Map.of("city", "Seoul")))));
    List<Message> responseMessages = List.of(assistant);
    List<ToolCallContent> calls = ToolLoopPolicy.toolCalls(response(responseMessages));
    Message results =
        ToolLoopPolicy.toolResultMessage(
            List.of(result("call-1", "weather"), result("call-2", "forecast")));

    ModelRequest next =
        new ToolLoopPolicy(List.of(), List.of(), 4)
            .nextRequest(request(), responseMessages, calls, results, 0);

    assertThat(next.messages()).element(1).isSameAs(assistant);
    assertThat(next.messages()).last().isSameAs(results);
  }

  @Test
  void everyDeclarationIsOfferedToTheModelIncludingDeclarationOnlyOnes() {
    ToolDefinition weather = ToolDefinition.builder().name("weather").build();
    ToolDefinition forecast = ToolDefinition.builder().name("forecast").build();
    ToolLoopPolicy policy =
        new ToolLoopPolicy(List.of(weather, forecast), List.of(boundTool("weather")), 4);

    assertThat(policy.hasTools()).isTrue();
    assertThat(policy.toolsForIteration(0)).containsExactly(weather, forecast);
  }

  @Test
  void canExecuteAllIsTrueOnlyWhenEveryCallHasABoundBody() {
    ToolDefinition weather = ToolDefinition.builder().name("weather").build();
    ToolDefinition forecast = ToolDefinition.builder().name("forecast").build();
    ToolLoopPolicy policy =
        new ToolLoopPolicy(List.of(weather, forecast), List.of(boundTool("weather")), 4);
    ToolCallContent boundCall = new ToolCallContent("call-1", "weather", JsonObject.empty());
    ToolCallContent declarationOnlyCall =
        new ToolCallContent("call-2", "forecast", JsonObject.empty());

    assertThat(policy.canExecuteAll(List.of(boundCall))).isTrue();
    assertThat(policy.canExecuteAll(List.of(declarationOnlyCall))).isFalse();
    assertThat(policy.canExecuteAll(List.of(boundCall, declarationOnlyCall))).isFalse();
  }

  private static FunctionTool boundTool(String name) {
    return FunctionTool.create(
        name,
        name,
        JsonObject.empty(),
        (arguments, context) -> completedFuture(ToolResult.success(new TextContent("ok"))));
  }

  private static ModelRequest request() {
    return ModelRequest.builder()
        .messages(List.of(new Message(Role.USER, List.of(new TextContent("weather?")))))
        .build();
  }

  private static ToolResultContent result(String callId, String name) {
    return new ToolResultContent(callId, name, List.of(new TextContent("sunny")), false);
  }

  private static List<ToolCallContent> toolCallsOf(ModelRequest request) {
    return contentOf(request, ToolCallContent.class);
  }

  private static List<ToolResultContent> toolResultsOf(ModelRequest request) {
    return contentOf(request, ToolResultContent.class);
  }

  private static <T extends Content> List<T> contentOf(ModelRequest request, Class<T> type) {
    List<T> found = new ArrayList<>();
    for (Message message : request.messages()) {
      for (Content content : message.content()) {
        if (type.isInstance(content)) {
          found.add(type.cast(content));
        }
      }
    }
    return List.copyOf(found);
  }

  private static ModelResponse response(List<Message> messages) {
    return ModelResponse.builder().messages(messages).finishReason(FinishReason.TOOL_CALLS).build();
  }

  private static ModelResponse response(Content... content) {
    return ModelResponse.builder()
        .messages(List.of(new Message(Role.ASSISTANT, List.of(content))))
        .finishReason(FinishReason.TOOL_CALLS)
        .build();
  }

  private static JsonObject jsonObject(Map<String, ?> values) {
    return values.isEmpty() ? JsonObject.empty() : (JsonObject) JsonValues.fromJava(values);
  }
}
