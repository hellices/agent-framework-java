package io.github.hellices.agentframework.samples.standalone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hellices.agentframework.api.agent.Agent;
import io.github.hellices.agentframework.api.agent.AgentResponse;
import io.github.hellices.agentframework.api.message.FinishReason;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.Role;
import io.github.hellices.agentframework.api.message.TextContent;
import io.github.hellices.agentframework.api.message.ToolCallContent;
import io.github.hellices.agentframework.api.message.ToolResultContent;
import io.github.hellices.agentframework.api.message.Usage;
import io.github.hellices.agentframework.spi.model.ModelClient;
import io.github.hellices.agentframework.spi.model.ModelRequest;
import io.github.hellices.agentframework.spi.model.ModelResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
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
  void failsWithAnExplicitMessageWhenTheApiKeyIsMissing() {
    // No silent fallback to a fake model. A sample that answers without a key teaches the wrong
    // thing and hides a misconfiguration until it matters.
    assertThatThrownBy(() -> StandaloneAgentApplication.requiredApiKey(Map.of()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(
            "OPENAI_API_KEY is not set. Export a key (and optionally OPENAI_BASE_URL /"
                + " OPENAI_MODEL) before running the sample.");
  }

  @Test
  void treatsABlankApiKeyAsMissing() {
    assertThatThrownBy(
            () -> StandaloneAgentApplication.requiredApiKey(Map.of("OPENAI_API_KEY", "   ")))
        .isInstanceOf(IllegalStateException.class);
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
    return new ModelResponse(
        List.of(new Message(Role.ASSISTANT, List.of(new TextContent(value)))),
        usage,
        FinishReason.STOP,
        Map.of(),
        null);
  }

  private static ModelResponse toolCall(String callId, Usage usage) {
    return new ModelResponse(
        List.of(
            new Message(
                Role.ASSISTANT,
                List.of(
                    new ToolCallContent(callId, StandaloneAgentApplication.TOOL_NAME, Map.of())))),
        usage,
        FinishReason.TOOL_CALLS,
        Map.of(),
        null);
  }

  /** Answers from a script and records what the sample asked for. */
  private static final class ScriptedModelClient implements ModelClient {

    private final List<ModelRequest> requests = new ArrayList<>();
    private final Deque<ModelResponse> answers;

    ScriptedModelClient(ModelResponse... answers) {
      this.answers = new ArrayDeque<>(List.of(answers));
    }

    @Override
    public CompletionStage<ModelResponse> run(ModelRequest request) {
      requests.add(request);
      ModelResponse answer = answers.poll();
      if (answer == null) {
        throw new IllegalStateException("the sample called the model more times than scripted");
      }
      return CompletableFuture.completedFuture(answer);
    }

    List<ModelRequest> requests() {
      return List.copyOf(requests);
    }
  }
}
