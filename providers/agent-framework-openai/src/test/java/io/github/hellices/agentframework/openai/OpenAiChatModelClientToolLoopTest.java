package io.github.hellices.agentframework.openai;

import static org.assertj.core.api.Assertions.assertThat;

import com.openai.core.JsonValue;
import com.openai.models.FunctionDefinition;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionMessageToolCall;
import com.openai.models.completions.CompletionUsage;
import io.github.hellices.agentframework.api.agent.AgentResponse;
import io.github.hellices.agentframework.api.message.Content;
import io.github.hellices.agentframework.api.message.FinishReason;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.Role;
import io.github.hellices.agentframework.api.message.TextContent;
import io.github.hellices.agentframework.api.message.ToolCallContent;
import io.github.hellices.agentframework.api.message.Usage;
import io.github.hellices.agentframework.api.tool.FunctionTool;
import io.github.hellices.agentframework.api.tool.ToolResult;
import io.github.hellices.agentframework.engine.AgentEngine;
import io.github.hellices.agentframework.spi.model.ModelClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

/**
 * Runs the real tool loop over a faked transport.
 *
 * <p>The engine is a test-only dependency of this module. These tests are here rather than in the
 * engine or the sample because this is the one place that can fail when either side changes: the
 * engine reports a whole round of tool results as one {@code Role.TOOL} message while Chat
 * Completions needs one message per tool call id, and the engine echoes the assistant turn the
 * response mapper produced while the request mapper only sends the model's own {@code arguments}
 * string back when that turn still carries its SDK message. Nothing else in the repository would
 * notice either of those going wrong.
 *
 * <p>Nothing here reaches the network. The adapter is built over the operations port, so the run
 * holds no {@code OpenAIClientAsync}, no base URL, and no credential, the tool is a deterministic
 * function of its arguments, and every provider answer is scripted in advance - a call the script
 * does not cover fails rather than being answered. {@code
 * needsNoTransportOfItsOwnToRunTheWholeLoop} asserts that as an outcome and not only as a setup.
 *
 * <p>The single wait is bounded, for the same reason as in {@code
 * OpenAiChatModelClientCancellationTest}: a regression that leaves the loop pending must fail the
 * build rather than hang it. It is a failure detector and not a race window - the fake answers on
 * the caller's thread, so the run is already settled before the wait begins. Nothing here sleeps,
 * starts a thread, or retries.
 */
class OpenAiChatModelClientToolLoopTest {

  private static final long BOUND_SECONDS = 5L;

  private static final String MODEL = "gpt-4.1-mini";
  private static final String USER_INPUT = "weather in Seoul?";
  private static final String TOOL_NAME = "weather";
  private static final String TOOL_DESCRIPTION = "Gets the weather of a city";
  private static final String CALL_ID = "call_1";

  /**
   * The arguments string as the model produced it, spacing included. Re-serialising the parsed map
   * yields {@code {"city":"Seoul"}}, so this constant is what tells the echo apart from a
   * reconstruction.
   */
  private static final String MODEL_ARGUMENTS = "{\"city\": \"Seoul\"}";

  private static final String FINAL_ANSWER = "It is sunny in Seoul";

  @Test
  void offersTheAgentsToolWithItsSchemaOnTheFirstRequest() throws Exception {
    Loop loop = runLoop();

    ChatCompletionCreateParams first = loop.request(0);
    assertThat(first.model().asString()).isEqualTo(MODEL);
    assertThat(first.messages())
        .singleElement()
        .satisfies(
            message -> assertThat(message.asUser().content().asText()).isEqualTo(USER_INPUT));
    assertThat(first.tools().orElseThrow()).hasSize(1);
    FunctionDefinition function = first.tools().orElseThrow().get(0).asFunction().function();
    assertThat(function.name()).isEqualTo(TOOL_NAME);
    assertThat(function.description()).hasValue(TOOL_DESCRIPTION);
    assertThat(function.parameters().orElseThrow()._additionalProperties())
        .containsEntry("type", JsonValue.from("object"))
        .containsEntry("properties", JsonValue.from(Map.of("city", Map.of("type", "string"))));
  }

  @Test
  void callsTheToolWithTheArgumentsTheModelProduced() throws Exception {
    Loop loop = runLoop();

    assertThat(loop.executedArguments()).singleElement().isEqualTo(Map.of("city", "Seoul"));
  }

  @Test
  void sendsTheEchoedToolCallAndOneToolMessagePerCallIdOnTheSecondRequest() throws Exception {
    Loop loop = runLoop();

    List<ChatCompletionMessageParam> second = loop.request(1).messages();
    assertThat(second).hasSize(3);
    assertThat(second.get(0).isUser()).isTrue();
    assertThat(second.get(1).isAssistant()).isTrue();
    assertThat(second.get(2).isTool()).isTrue();
    assertThat(second.get(0).asUser().content().asText()).isEqualTo(USER_INPUT);
    // The echo carries the SDK message the response mapper attached, so the arguments string is the
    // one the model produced rather than a re-serialised map.
    assertThat(second.get(1).asAssistant().toolCalls().orElseThrow())
        .singleElement()
        .satisfies(
            call -> {
              assertThat(call.asFunction().id()).isEqualTo(CALL_ID);
              assertThat(call.asFunction().function().name()).isEqualTo(TOOL_NAME);
              assertThat(call.asFunction().function().arguments()).isEqualTo(MODEL_ARGUMENTS);
            });
    // One framework tool message holding one result became one OpenAI tool message keyed by the
    // call id. This is the assertion that catches the fan-out being dropped.
    assertThat(second.get(2).asTool().toolCallId()).isEqualTo(CALL_ID);
    assertThat(second.get(2).asTool().content().asText()).isEqualTo("sunny:Seoul");
    // The budget is not spent, so the tool is still on offer: a second round remains possible.
    assertThat(loop.request(1).tools().orElseThrow())
        .singleElement()
        .satisfies(tool -> assertThat(tool.asFunction().function().name()).isEqualTo(TOOL_NAME));
  }

  @Test
  void endsTheLoopOnTheAnswerTheModelGaveAfterTheToolResult() throws Exception {
    Loop loop = runLoop();

    // Two model calls and no more: one that asked for the tool, one that answered with it. A third
    // would find no scripted answer and fail the run rather than reach a provider.
    assertThat(loop.operations().invocations()).isEqualTo(2);
    assertThat(loop.response().messages())
        .extracting(Message::role)
        .containsExactly(Role.ASSISTANT, Role.TOOL, Role.ASSISTANT);
    // The tool call and the tool result carry no text of their own, so the run's text is the
    // model's final answer; endsWith rather than isEqualTo keeps that a statement about the last
    // turn rather than about what the other two turns are allowed to contribute.
    assertThat(loop.response().text()).endsWith(FINAL_ANSWER);
    assertThat(loop.response().messages().get(2).text()).isEqualTo(FINAL_ANSWER);
    // The terminal turn's finish reason, not the tool round's: the run ended because the model
    // stopped, and reporting TOOL_CALLS here would tell a caller the run was left unfinished.
    assertThat(loop.response().finishReason()).isEqualTo(FinishReason.STOP);
    // Both rounds are billed, so the usage of the whole run is the sum and not the last call's.
    assertThat(loop.response().usage()).isEqualTo(new Usage(31L, 12L, 43L));
    assertThat(toolCallsOf(loop.response()))
        .singleElement()
        .satisfies(
            call -> {
              assertThat(call.callId()).isEqualTo(CALL_ID);
              assertThat(call.name()).isEqualTo(TOOL_NAME);
              assertThat(call.arguments()).isEqualTo(Map.of("city", "Seoul"));
            });
  }

  @Test
  void needsNoTransportOfItsOwnToRunTheWholeLoop() {
    FakeChatCompletionsOperations operations = scriptedProvider();
    AgentEngine engine = engineOver(operations, new ArrayList<>());

    CompletableFuture<AgentResponse> response =
        engine.run(USER_INPUT).response().toCompletableFuture();

    // Offline as an outcome and not only as a setup: the whole two-call loop, the tool, and the
    // response mapping are already settled on the calling thread by the time run() returns. A run
    // still pending here waited on something this test does not have and must never acquire - a
    // socket, a connection pool, an executor, a retry.
    assertThat(response).isCompleted();
    assertThat(operations.invocations()).isEqualTo(2);
  }

  private static Loop runLoop() throws InterruptedException, ExecutionException, TimeoutException {
    FakeChatCompletionsOperations operations = scriptedProvider();
    List<Map<String, Object>> executedArguments = new ArrayList<>();
    AgentEngine engine = engineOver(operations, executedArguments);

    AgentResponse response =
        engine
            .run(USER_INPUT)
            .response()
            .toCompletableFuture()
            .get(BOUND_SECONDS, TimeUnit.SECONDS);

    return new Loop(operations, List.copyOf(executedArguments), response);
  }

  /** One scripted conversation: the model asks for the tool, then answers with its result. */
  private static FakeChatCompletionsOperations scriptedProvider() {
    return new FakeChatCompletionsOperations()
        .answering(toolCallCompletion())
        .answering(textCompletion(FINAL_ANSWER));
  }

  /**
   * An agent whose only tool is a deterministic function of its arguments, so the second request
   * and the final answer are fixed by the script alone.
   *
   * @param executedArguments collects what each call handed the tool, in call order
   */
  private static AgentEngine engineOver(
      FakeChatCompletionsOperations operations, List<Map<String, Object>> executedArguments) {
    ModelClient client =
        OpenAiChatModelClient.builder().operations(operations).model(MODEL).build();
    FunctionTool weather =
        FunctionTool.create(
            TOOL_NAME,
            TOOL_DESCRIPTION,
            weatherSchema(),
            (arguments, context) -> {
              executedArguments.add(arguments.values());
              return CompletableFuture.completedFuture(
                  ToolResult.success(new TextContent("sunny:" + arguments.get("city"))));
            });
    return AgentEngine.builder().modelClient(client).tools(weather).build();
  }

  private static Map<String, Object> weatherSchema() {
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "object");
    schema.put("properties", Map.of("city", Map.of("type", "string")));
    return schema;
  }

  private static List<ToolCallContent> toolCallsOf(AgentResponse response) {
    List<ToolCallContent> calls = new ArrayList<>();
    for (Message message : response.messages()) {
      for (Content content : message.content()) {
        if (content instanceof ToolCallContent call) {
          calls.add(call);
        }
      }
    }
    return List.copyOf(calls);
  }

  private static ChatCompletion toolCallCompletion() {
    ChatCompletionMessage message =
        ChatCompletionMessage.builder()
            .content((String) null)
            .refusal((String) null)
            .addToolCall(
                ChatCompletionMessageToolCall.ofFunction(
                    ChatCompletionMessageFunctionToolCall.builder()
                        .id(CALL_ID)
                        .function(
                            ChatCompletionMessageFunctionToolCall.Function.builder()
                                .name(TOOL_NAME)
                                .arguments(MODEL_ARGUMENTS)
                                .build())
                        .build()))
            .build();
    return completion(message, ChatCompletion.Choice.FinishReason.TOOL_CALLS, usage(11L, 5L, 16L));
  }

  private static ChatCompletion textCompletion(String text) {
    return completion(
        ChatCompletionMessage.builder().content(text).refusal((String) null).build(),
        ChatCompletion.Choice.FinishReason.STOP,
        usage(20L, 7L, 27L));
  }

  private static ChatCompletion completion(
      ChatCompletionMessage message,
      ChatCompletion.Choice.FinishReason finishReason,
      CompletionUsage usage) {
    return ChatCompletion.builder()
        .id("chatcmpl-test")
        .created(1_700_000_000L)
        .model(MODEL)
        .addChoice(
            ChatCompletion.Choice.builder()
                .finishReason(finishReason)
                .index(0L)
                .logprobs((ChatCompletion.Choice.Logprobs) null)
                .message(message)
                .build())
        .usage(usage)
        .build();
  }

  private static CompletionUsage usage(long promptTokens, long completionTokens, long totalTokens) {
    return CompletionUsage.builder()
        .promptTokens(promptTokens)
        .completionTokens(completionTokens)
        .totalTokens(totalTokens)
        .build();
  }

  /** One finished run: what the adapter sent, what the tool saw, and what the caller got back. */
  private record Loop(
      FakeChatCompletionsOperations operations,
      List<Map<String, Object>> executedArguments,
      AgentResponse response) {

    ChatCompletionCreateParams request(int index) {
      return operations.requests().get(index);
    }
  }
}
