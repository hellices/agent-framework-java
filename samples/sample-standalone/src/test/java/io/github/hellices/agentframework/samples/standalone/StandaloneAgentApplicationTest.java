package io.github.hellices.agentframework.samples.standalone;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.hellices.agentframework.api.agent.Agent;
import io.github.hellices.agentframework.api.agent.AgentFactory;
import io.github.hellices.agentframework.api.agent.AgentResponse;
import io.github.hellices.agentframework.api.agent.AgentRunRequest;
import io.github.hellices.agentframework.api.agent.AgentSession;
import io.github.hellices.agentframework.api.message.FinishReason;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.Role;
import io.github.hellices.agentframework.api.message.TextContent;
import io.github.hellices.agentframework.api.message.ToolCallContent;
import io.github.hellices.agentframework.api.message.ToolResultContent;
import io.github.hellices.agentframework.api.message.Usage;
import io.github.hellices.agentframework.api.tool.FunctionTool;
import io.github.hellices.agentframework.api.tool.ToolDefinition;
import io.github.hellices.agentframework.api.tool.ToolResult;
import io.github.hellices.agentframework.api.value.JsonObject;
import io.github.hellices.agentframework.api.value.JsonValues;
import io.github.hellices.agentframework.engine.AgentEngine;
import io.github.hellices.agentframework.engine.session.InMemorySessionStore;
import io.github.hellices.agentframework.engine.session.JacksonSessionSnapshotCodec;
import io.github.hellices.agentframework.spi.model.ModelClient;
import io.github.hellices.agentframework.spi.model.ModelRequest;
import io.github.hellices.agentframework.spi.model.ModelResponse;
import io.github.hellices.agentframework.spi.model.ModelResponseUpdate;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;

/**
 * Covers the sample without a network call, a credential, or a wall clock.
 *
 * <p>The agent assembly, the registered tool, and the whole function-tool loop are exercised
 * through an injected model client and an injected clock, and credential resolution is a pure
 * function over a supplied environment map. Nothing here reads the real process environment, the
 * real time, or builds an SDK client, so the suite runs identically on a laptop and in CI.
 *
 * <p>What these tests deliberately do not claim is that a live model calls the tool. Tool selection
 * belongs to the model. What is proved here is that the tool is registered on every request, that
 * the loop completes when the model does call it, and that the default prompt asks for it by name.
 */
class StandaloneAgentApplicationTest {

  private static final Clock FIXED_CLOCK =
      Clock.fixed(Instant.parse("2026-08-16T00:00:00Z"), ZoneOffset.UTC);
  private static final String FIXED_TIME = "2026-08-16T00:00:00Z";
  private static final Clock OTHER_CLOCK =
      Clock.fixed(Instant.parse("2026-08-17T00:00:00Z"), ZoneOffset.UTC);
  private static final String OTHER_TIME = "2026-08-17T00:00:00Z";

  @Test
  void runsWithAnInjectedModelClientAndOffersTheLocalTool() {
    ScriptedModelClient modelClient = new ScriptedModelClient(text("pong", null));

    Agent agent = StandaloneAgentApplication.createAgent(modelClient, FIXED_CLOCK);

    assertThat(agent.run("ping").response().toCompletableFuture().join().text()).isEqualTo("pong");
    // The tool reaches the wire on the very first request, which is what makes the live run a tool
    // loop rather than a plain completion.
    assertThat(modelClient.requests().get(0).tools())
        .singleElement()
        .satisfies(tool -> assertThat(tool.name()).isEqualTo(StandaloneAgentApplication.TOOL_NAME));
  }

  @Test
  void oneSharedFactoryBindsTwoDistinctAgentsWhileKeepingSharedSessionServicesUsable() {
    AgentFactory factory =
        AgentEngine.builder()
            .sessionStore(new InMemorySessionStore(new JacksonSessionSnapshotCodec()))
            .build()
            .factory();
    ScriptedModelClient firstModel =
        new ScriptedModelClient(text("first-one", null), text("first-two", null));
    ScriptedModelClient secondModel =
        new ScriptedModelClient(text("second-one", null), text("second-two", null));

    Agent firstAgent = StandaloneAgentApplication.createAgent(factory, firstModel, FIXED_CLOCK);
    Agent secondAgent =
        factory
            .builderWithClient(secondModel)
            .id("second-agent")
            .name("Second Agent")
            .description("Runs through the shared factory with a different tool.")
            .tools(fixedTextTool("other_clock", OTHER_TIME))
            .build();

    assertThat(run(firstAgent, "shared-first", "first prompt")).isEqualTo("first-one");
    assertThat(run(secondAgent, "shared-second", "other prompt")).isEqualTo("second-one");
    assertThat(run(firstAgent, "shared-first", "follow-up for first")).isEqualTo("first-two");
    assertThat(run(secondAgent, "shared-second", "follow-up for second")).isEqualTo("second-two");

    assertThat(firstAgent.id()).isEqualTo("standalone-agent");
    assertThat(secondAgent.id()).isEqualTo("second-agent");
    assertThat(firstModel.requests().get(0).tools())
        .extracting(ToolDefinition::name)
        .containsExactly(StandaloneAgentApplication.TOOL_NAME);
    assertThat(secondModel.requests().get(0).tools())
        .extracting(ToolDefinition::name)
        .containsExactly("other_clock");
    assertThat(firstModel.requests().get(1).messages())
        .extracting(Message::text)
        .containsExactly("first prompt", "first-one", "follow-up for first")
        .doesNotContain("other prompt", "second-one");
    assertThat(secondModel.requests().get(1).messages())
        .extracting(Message::text)
        .containsExactly("other prompt", "second-one", "follow-up for second")
        .doesNotContain("first prompt", "first-one");
  }

  @Test
  void runsTheFunctionToolLoopWithTheInjectedClock() {
    // The loop the live milestone is supposed to exercise, proved here with no network: the model
    // asks for the tool, the sample's tool answers from the injected clock, and the second request
    // carries that answer back.
    ScriptedModelClient modelClient =
        new ScriptedModelClient(
            toolCall("call_1", new Usage(3L, 1L, 4L)),
            text("It is " + FIXED_TIME, new Usage(5L, 2L, 7L)));

    AgentResponse response =
        StandaloneAgentApplication.createAgent(modelClient, FIXED_CLOCK)
            .run(StandaloneAgentApplication.defaultPrompt())
            .response()
            .toCompletableFuture()
            .join();

    assertThat(modelClient.requests()).hasSize(2);
    List<Message> second = modelClient.requests().get(1).messages();
    ToolResultContent result =
        (ToolResultContent)
            second.stream()
                .filter(message -> Role.TOOL.equals(message.role()))
                .flatMap(message -> message.content().stream())
                .findFirst()
                .orElseThrow();
    assertThat(result.callId()).isEqualTo("call_1");
    assertThat(result.content())
        .singleElement()
        .extracting(content -> ((TextContent) content).value())
        .isEqualTo(FIXED_TIME);
    assertThat(response.text()).isEqualTo("It is " + FIXED_TIME);
  }

  @Test
  void printsOnlyTheTerminalAssistantTextWhenAToolRoundNarratesFirst() {
    // A model is free to say something in the same round it asks for a tool. The engine keeps that
    // preamble in the transcript, in order, so AgentResponse#text() would glue "Let me check the
    // clock." onto the final answer and the sample would print a sentence the model never said as
    // one utterance. The sample prints the terminal round only.
    ScriptedModelClient modelClient =
        new ScriptedModelClient(
            toolCallAfterSaying("Let me check the clock.", "call_1", new Usage(3L, 1L, 4L)),
            text("It is " + FIXED_TIME, new Usage(5L, 2L, 7L)));

    AgentResponse response =
        StandaloneAgentApplication.createAgent(modelClient, FIXED_CLOCK)
            .run(StandaloneAgentApplication.defaultPrompt())
            .response()
            .toCompletableFuture()
            .join();

    assertThat(response.text()).isEqualTo("Let me check the clock.It is " + FIXED_TIME);
    assertThat(StandaloneAgentApplication.finalText(response)).isEqualTo("It is " + FIXED_TIME);
    // The preamble is not lost, only unprinted: the transcript still carries it before the tool
    // result, which is the order the engine appends messages in.
    assertThat(response.messages())
        .extracting(message -> message.role().value())
        .containsExactly("assistant", "tool", "assistant");
  }

  @Test
  void joinsEveryMessageOfTheTerminalRound() {
    // A round is not always one message. The contract is "the terminal round", not "the last
    // message", so a split answer is printed whole rather than truncated to its tail.
    ScriptedModelClient modelClient =
        new ScriptedModelClient(
            toolCallAfterSaying("Let me check the clock.", "call_1", null),
            round(null, "It is ", FIXED_TIME));

    AgentResponse response =
        StandaloneAgentApplication.createAgent(modelClient, FIXED_CLOCK)
            .run(StandaloneAgentApplication.defaultPrompt())
            .response()
            .toCompletableFuture()
            .join();

    assertThat(response.messages())
        .extracting(message -> message.role().value())
        .containsExactly("assistant", "tool", "assistant", "assistant");
    assertThat(StandaloneAgentApplication.finalText(response)).isEqualTo("It is " + FIXED_TIME);
  }

  @Test
  void printsBlankFinalTextWhenTheTerminalRoundHasNoContent() {
    // A provider can end the loop (no tool call, so the round is terminal) with an assistant
    // message that carries no content at all -- an empty completion, not merely an empty string.
    // finalText must report that emptiness rather than reach back into the preamble or any earlier
    // round: response.text() still concatenates the whole transcript and keeps the preamble, so a
    // fallback to it would silently print words the terminal round never said. The footer's
    // toolCalls count, not the printed text, is what still tells a reader the loop actually ran.
    ScriptedModelClient modelClient =
        new ScriptedModelClient(
            toolCallAfterSaying("Let me check the clock.", "call_1", new Usage(3L, 1L, 4L)),
            blank(new Usage(5L, 2L, 7L)));

    AgentResponse response =
        StandaloneAgentApplication.createAgent(modelClient, FIXED_CLOCK)
            .run(StandaloneAgentApplication.defaultPrompt())
            .response()
            .toCompletableFuture()
            .join();

    assertThat(response.text()).isEqualTo("Let me check the clock.");
    assertThat(StandaloneAgentApplication.finalText(response)).isEmpty();
    assertThat(response.messages())
        .extracting(message -> message.role().value())
        .containsExactly("assistant", "tool", "assistant");
    // The blank final text still leaves an observable outcome: the footer's tool-call count.
    assertThat(StandaloneAgentApplication.footer(response, "gpt-4.1-mini"))
        .isEqualTo(
            "[model=gpt-4.1-mini finishReason=STOP toolCalls=1 inputTokens=8 outputTokens=3]");
  }

  @Test
  void printsTheWholeAnswerWhenTheRunNeverCallsATool() {
    // Without a tool round the terminal round is the only round, so the sample prints everything
    // the model said rather than silently dropping earlier text.
    ScriptedModelClient modelClient = new ScriptedModelClient(text("pong", null));

    AgentResponse response =
        StandaloneAgentApplication.createAgent(modelClient, FIXED_CLOCK)
            .run("ping")
            .response()
            .toCompletableFuture()
            .join();

    assertThat(StandaloneAgentApplication.finalText(response)).isEqualTo(response.text());
  }

  @Test
  void rendersUnknownUsageAsNotAvailableInTheFooter() {
    // Usage is nullable on the API, and an OpenAI-compatible endpoint may omit it entirely. The
    // footer says so rather than printing a guessed zero that reads like a measurement.
    ScriptedModelClient modelClient = new ScriptedModelClient(text("pong", null));
    AgentResponse response =
        StandaloneAgentApplication.createAgent(modelClient, FIXED_CLOCK)
            .run("ping")
            .response()
            .toCompletableFuture()
            .join();

    assertThat(response.usage()).isNull();
    assertThat(StandaloneAgentApplication.footer(response, "llama3.1"))
        .isEqualTo(
            "[model=llama3.1 finishReason=STOP toolCalls=0 inputTokens=n/a outputTokens=n/a]");
  }

  @Test
  void namesTheSameToolInTheDefaultPromptAndTheToolDefinition() {
    // If these two drift apart, the default run silently stops exercising the loop while every test
    // still passes, which is exactly the gap this sample exists to close.
    assertThat(StandaloneAgentApplication.currentTimeTool(FIXED_CLOCK).definition().name())
        .isEqualTo(StandaloneAgentApplication.TOOL_NAME);
    assertThat(StandaloneAgentApplication.defaultPrompt())
        .contains(StandaloneAgentApplication.TOOL_NAME);
  }

  @Test
  void usesTheDefaultPromptWhenNoArgumentIsGiven() {
    assertThat(StandaloneAgentApplication.prompt(new String[0]))
        .isEqualTo(StandaloneAgentApplication.defaultPrompt());
  }

  @Test
  void keepsArbitraryUserArguments() {
    // The default prompt is a convenience, not a cage. Whatever the user passes is what is asked.
    assertThat(StandaloneAgentApplication.prompt(new String[] {"summarise", "this"}))
        .isEqualTo("summarise this");
  }

  @Test
  void reportsTheDocumentedMessageWhenTheApiKeyIsMissing() {
    // No silent fallback to a fake model. A sample that answers without a key teaches the wrong
    // thing and hides a misconfiguration until it matters. The message is a constant because the
    // provider README quotes it verbatim.
    assertThat(StandaloneAgentApplication.configuredApiKey(Map.of())).isNull();
    assertThat(StandaloneAgentApplication.MISSING_API_KEY_MESSAGE)
        .isEqualTo(
            "OPENAI_API_KEY is not set. Export a key (and optionally OPENAI_BASE_URL /"
                + " OPENAI_MODEL) before running the sample.");
  }

  @Test
  void treatsABlankApiKeyAsMissing() {
    assertThat(StandaloneAgentApplication.configuredApiKey(Map.of("OPENAI_API_KEY", "   ")))
        .isNull();
  }

  @Test
  void returnsAConfiguredApiKeyUnchanged() {
    assertThat(StandaloneAgentApplication.configuredApiKey(Map.of("OPENAI_API_KEY", "sk-test")))
        .isEqualTo("sk-test");
  }

  @Test
  void defaultsTheBaseUrlAndTheModel() {
    assertThat(StandaloneAgentApplication.baseUrl(Map.of())).isEqualTo("https://api.openai.com/v1");
    assertThat(StandaloneAgentApplication.model(Map.of())).isEqualTo("gpt-4.1-mini");
  }

  @Test
  void honoursAConfiguredBaseUrlAndModel() {
    // The same sample must reach a compatible endpoint, which is what makes the adapter useful
    // beyond api.openai.com.
    Map<String, String> environment =
        Map.of("OPENAI_BASE_URL", "http://localhost:11434/v1", "OPENAI_MODEL", "llama3.1");

    assertThat(StandaloneAgentApplication.baseUrl(environment))
        .isEqualTo("http://localhost:11434/v1");
    assertThat(StandaloneAgentApplication.model(environment)).isEqualTo("llama3.1");
  }

  @Test
  void printsANonSensitiveFooterThatCountsToolCalls() {
    // The footer is how a human sees that the loop ran. It carries a count, never the prompt, the
    // reply, or the tool's output.
    ScriptedModelClient modelClient =
        new ScriptedModelClient(
            toolCall("call_1", new Usage(3L, 1L, 4L)),
            text("It is " + FIXED_TIME, new Usage(5L, 2L, 7L)));
    AgentResponse response =
        StandaloneAgentApplication.createAgent(modelClient, FIXED_CLOCK)
            .run(StandaloneAgentApplication.defaultPrompt())
            .response()
            .toCompletableFuture()
            .join();

    assertThat(StandaloneAgentApplication.footer(response, "gpt-4.1-mini"))
        .isEqualTo(
            "[model=gpt-4.1-mini finishReason=STOP toolCalls=1 inputTokens=8 outputTokens=3]")
        .doesNotContain(FIXED_TIME)
        .doesNotContain(StandaloneAgentApplication.defaultPrompt());
  }

  private static ModelResponse text(String value, Usage usage) {
    return ModelResponse.builder()
        .messages(List.of(new Message(Role.ASSISTANT, List.of(new TextContent(value)))))
        .usage(usage)
        .finishReason(FinishReason.STOP)
        .build();
  }

  private static ModelResponse toolCall(String callId, Usage usage) {
    return ModelResponse.builder()
        .messages(
            List.of(
                new Message(
                    Role.ASSISTANT,
                    List.of(
                        new ToolCallContent(
                            callId, StandaloneAgentApplication.TOOL_NAME, JsonObject.empty())))))
        .usage(usage)
        .finishReason(FinishReason.TOOL_CALLS)
        .build();
  }

  /** A terminal completion whose assistant message carries no content at all. */
  private static ModelResponse blank(Usage usage) {
    return ModelResponse.builder()
        .messages(List.of(new Message(Role.ASSISTANT, List.of())))
        .usage(usage)
        .finishReason(FinishReason.STOP)
        .build();
  }

  private static ModelResponse round(Usage usage, String... values) {
    List<Message> messages = new ArrayList<>();
    for (String value : values) {
      messages.add(new Message(Role.ASSISTANT, List.of(new TextContent(value))));
    }
    return ModelResponse.builder()
        .messages(List.copyOf(messages))
        .usage(usage)
        .finishReason(FinishReason.STOP)
        .build();
  }

  private static ModelResponse toolCallAfterSaying(String preamble, String callId, Usage usage) {
    return ModelResponse.builder()
        .messages(
            List.of(
                new Message(
                    Role.ASSISTANT,
                    List.of(
                        new TextContent(preamble),
                        new ToolCallContent(
                            callId, StandaloneAgentApplication.TOOL_NAME, JsonObject.empty())))))
        .usage(usage)
        .finishReason(FinishReason.TOOL_CALLS)
        .build();
  }

  /** Answers from a script and records what the sample asked for. */
  private static final class ScriptedModelClient implements ModelClient {

    private final List<ModelRequest> requests = new ArrayList<>();
    private final Deque<ModelResponse> answers;

    ScriptedModelClient(ModelResponse... answers) {
      this.answers = new ArrayDeque<>(List.of(answers));
    }

    @Override
    public Flow.Publisher<ModelResponseUpdate> execute(ModelRequest request) {
      requests.add(request);
      ModelResponse answer = answers.poll();
      if (answer == null) {
        throw new IllegalStateException("the sample called the model more times than scripted");
      }
      ModelResponseUpdate update =
          ModelResponseUpdate.builder()
              .messages(answer.messages())
              .usage(answer.usage())
              .finishReason(answer.finishReason())
              .continuationToken(answer.continuationToken())
              .metadata(answer.metadata())
              .rawRepresentation(answer.rawRepresentation())
              .build();
      return subscriber ->
          subscriber.onSubscribe(
              new Flow.Subscription() {
                private boolean done;

                @Override
                public void request(long n) {
                  if (done || n <= 0) {
                    return;
                  }
                  done = true;
                  subscriber.onNext(update);
                  subscriber.onComplete();
                }

                @Override
                public void cancel() {
                  done = true;
                }
              });
    }

    List<ModelRequest> requests() {
      return List.copyOf(requests);
    }
  }

  private static String run(Agent agent, String sessionId, String input) {
    return agent
        .run(
            AgentRunRequest.builder()
                .session(AgentSession.builder().sessionId(sessionId).build())
                .messages(Message.normalize(input))
                .build())
        .response()
        .toCompletableFuture()
        .join()
        .text();
  }

  private static FunctionTool fixedTextTool(String name, String value) {
    return FunctionTool.create(
        name,
        "Returns fixed text.",
        JsonObject.builder()
            .put("type", JsonValues.fromJava("object"))
            .put("properties", JsonValues.fromJava(Map.of()))
            .build(),
        (arguments, context) ->
            CompletableFuture.completedFuture(ToolResult.success(new TextContent(value))));
  }
}
