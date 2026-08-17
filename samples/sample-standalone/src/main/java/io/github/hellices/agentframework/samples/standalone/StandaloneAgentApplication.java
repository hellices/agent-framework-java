package io.github.hellices.agentframework.samples.standalone;

import com.openai.client.OpenAIClientAsync;
import com.openai.client.okhttp.OpenAIOkHttpClientAsync;
import io.github.hellices.agentframework.api.agent.Agent;
import io.github.hellices.agentframework.api.agent.AgentFactory;
import io.github.hellices.agentframework.api.agent.AgentResponse;
import io.github.hellices.agentframework.api.message.Content;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.Role;
import io.github.hellices.agentframework.api.message.TextContent;
import io.github.hellices.agentframework.api.message.ToolCallContent;
import io.github.hellices.agentframework.api.message.Usage;
import io.github.hellices.agentframework.api.tool.FunctionTool;
import io.github.hellices.agentframework.api.tool.ToolResult;
import io.github.hellices.agentframework.api.value.JsonObject;
import io.github.hellices.agentframework.api.value.JsonValues;
import io.github.hellices.agentframework.engine.AgentEngine;
import io.github.hellices.agentframework.openai.OpenAiChatModelClient;
import io.github.hellices.agentframework.spi.model.ModelCatalog;
import io.github.hellices.agentframework.spi.model.ModelClient;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Runs one agent turn against a real OpenAI-compatible endpoint with no host framework.
 *
 * <p>Reads {@code OPENAI_API_KEY} (required), {@code OPENAI_BASE_URL} (defaults to the official
 * endpoint), and {@code OPENAI_MODEL} (defaults to {@code gpt-4.1-mini}). There is no deterministic
 * fallback: a sample that answers without a key would teach that the call succeeded when it never
 * happened.
 *
 * <p>One local function tool, {@value #TOOL_NAME}, is registered on every request. It reads the
 * supplied {@link java.time.Clock} and returns an ISO-8601 instant; it takes no arguments, opens no
 * file, socket, or process, and logs nothing. The default prompt asks the model to use it, so the
 * default run exercises the whole function-tool loop end to end. Passing arguments replaces that
 * prompt with whatever was asked, and the model is free to answer without calling the tool, which
 * is the ordinary response path rather than a failure. The footer reports how many tool calls the
 * run actually made.
 *
 * <p>This class owns the SDK client, and closes it, because the adapter deliberately does not.
 */
public final class StandaloneAgentApplication {

  /** The one tool this sample registers. Named in the default prompt so the two cannot drift. */
  static final String TOOL_NAME = "current_utc_time";

  /**
   * The whole output of a run without a credential. One sentence, printed to standard error, and
   * quoted verbatim in the provider README, so the sample and the documentation cannot drift.
   */
  static final String MISSING_API_KEY_MESSAGE =
      "OPENAI_API_KEY is not set. Export a key (and optionally OPENAI_BASE_URL /"
          + " OPENAI_MODEL) before running the sample.";

  /** A failed run must not look like a completed one to a shell, a script, or a CI step. */
  static final int MISSING_API_KEY_EXIT_CODE = 1;

  private static final String API_KEY_VARIABLE = "OPENAI_API_KEY";
  private static final String BASE_URL_VARIABLE = "OPENAI_BASE_URL";
  private static final String MODEL_VARIABLE = "OPENAI_MODEL";
  private static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";
  private static final String DEFAULT_MODEL = "gpt-4.1-mini";

  private StandaloneAgentApplication() {}

  /**
   * Builds the agent over a supplied model client and clock.
   *
   * <p>Both are parameters so the sample's own test can run the same assembly, including the tool
   * loop, deterministically and without a credential or a socket.
   *
   * @param modelClient the model client the agent calls, never {@code null}
   * @param clock the clock {@value #TOOL_NAME} reads, never {@code null}
   * @return the assembled agent, never {@code null}
   */
  public static Agent createAgent(ModelClient modelClient, Clock clock) {
    ModelCatalog catalog =
        ModelCatalog.builder().add("openai", modelClient).defaultModel("openai").build();
    AgentFactory factory = AgentEngine.builder().build().factory(catalog);
    return factory
        .builder()
        .id("standalone-agent")
        .name("Standalone Agent")
        .description("Runs without a host framework, calling an OpenAI-compatible endpoint.")
        .tools(currentTimeTool(clock))
        .build();
  }

  /**
   * Runs one turn and prints the answer and a footer of counts.
   *
   * <p>A missing or blank {@code OPENAI_API_KEY} is the one failure this method formats itself: it
   * prints {@value #MISSING_API_KEY_MESSAGE} to standard error and exits non-zero, before a client
   * is built, so a run without a credential costs no request, reports why in one line, and still
   * fails loudly enough for a script to notice. It is a configuration mistake, not a defect, and a
   * JVM stack trace would say nothing the sentence does not. Every other failure — a provider
   * fault, a transport error, a cancelled run — is deliberately left to the JVM's default handler,
   * trace and all, because there the trace is the diagnostic.
   *
   * @param args the prompt; the default prompt asks for {@value #TOOL_NAME} when none is given
   */
  public static void main(String[] args) {
    Map<String, String> environment = System.getenv();
    String apiKey = configuredApiKey(environment);
    if (apiKey == null) {
      System.err.println(MISSING_API_KEY_MESSAGE);
      System.exit(MISSING_API_KEY_EXIT_CODE);
      return;
    }
    String model = model(environment);
    OpenAIClientAsync client =
        OpenAIOkHttpClientAsync.builder().apiKey(apiKey).baseUrl(baseUrl(environment)).build();
    try {
      Agent agent =
          createAgent(
              OpenAiChatModelClient.builder().client(client).model(model).build(),
              Clock.systemUTC());
      AgentResponse response = agent.run(prompt(args)).response().toCompletableFuture().join();
      System.out.println(finalText(response));
      System.out.println(footer(response, model));
    } finally {
      // The sample created the client, so the sample closes it. A long-lived host would not.
      client.close();
    }
  }

  /**
   * The one tool the sample offers: the current UTC time, to the second, from the supplied clock.
   *
   * <p>Deliberately boring. It reads no argument, reaches nothing outside the process, and prints
   * nothing, so registering it in a sample that runs against someone's own credentials is safe.
   */
  static FunctionTool currentTimeTool(Clock clock) {
    return FunctionTool.create(
        TOOL_NAME,
        "Returns the current UTC time as an ISO-8601 instant.",
        JsonObject.builder()
            .put("type", JsonValues.fromJava("object"))
            .put("properties", JsonValues.fromJava(Map.of()))
            .build(),
        (arguments, context) ->
            CompletableFuture.completedFuture(
                ToolResult.success(
                    new TextContent(
                        DateTimeFormatter.ISO_INSTANT.format(
                            clock.instant().truncatedTo(ChronoUnit.SECONDS))))));
  }

  /** The prompt used when the sample is run with no arguments. Names the tool on purpose. */
  static String defaultPrompt() {
    return "Call the " + TOOL_NAME + " tool and tell me the current UTC time.";
  }

  static String prompt(String[] args) {
    return args.length == 0 ? defaultPrompt() : String.join(" ", args);
  }

  /**
   * The configured API key, or {@code null} when the variable is unset or blank.
   *
   * <p>Returns rather than throws because the caller has to print one sentence and exit, and an
   * exception thrown out of {@code main} prints a stack trace instead. A blank value is treated as
   * missing: an exported but empty variable is a mistake, not a credential.
   */
  static String configuredApiKey(Map<String, String> environment) {
    String apiKey = environment.get(API_KEY_VARIABLE);
    return apiKey == null || apiKey.isBlank() ? null : apiKey;
  }

  static String baseUrl(Map<String, String> environment) {
    return valueOrDefault(environment, BASE_URL_VARIABLE, DEFAULT_BASE_URL);
  }

  static String model(Map<String, String> environment) {
    return valueOrDefault(environment, MODEL_VARIABLE, DEFAULT_MODEL);
  }

  /**
   * The answer the run ended on: the text of the terminal assistant round, not of the whole
   * transcript.
   *
   * <p>{@link AgentResponse#text()} concatenates every message the run produced. A model that
   * narrates before it asks for a tool ("Let me check the clock.") therefore has that preamble
   * glued straight onto the final answer, and the sample would print one sentence the model never
   * said as one utterance. The engine appends messages in the order they were produced — each
   * round's assistant messages, then that round's tool results — so the terminal round is exactly
   * the trailing run of assistant messages, and everything before the last non-assistant message
   * belongs to an earlier round. A run that never calls a tool has only that trailing run, so this
   * prints the whole answer.
   *
   * <p>Nothing is discarded: {@code response.messages()} still carries the preamble for a caller
   * that wants the transcript. This is a presentation choice for a one-shot console sample.
   */
  static String finalText(AgentResponse response) {
    List<Message> messages = response.messages();
    int start = messages.size();
    while (start > 0 && Role.ASSISTANT.equals(messages.get(start - 1).role())) {
      start--;
    }
    StringBuilder text = new StringBuilder();
    for (Message message : messages.subList(start, messages.size())) {
      text.append(message.text());
    }
    return text.toString();
  }

  /**
   * A short run summary that carries no prompt text and no model output.
   *
   * <p>The tool-call count is what makes the loop observable: zero means the model answered without
   * calling the tool, which is its decision to make.
   */
  static String footer(AgentResponse response, String model) {
    Usage usage = response.usage();
    return "[model="
        + model
        + " finishReason="
        + response.finishReason()
        + " toolCalls="
        + toolCallCount(response)
        + " inputTokens="
        + (usage == null ? "n/a" : usage.inputTokens())
        + " outputTokens="
        + (usage == null ? "n/a" : usage.outputTokens())
        + "]";
  }

  private static long toolCallCount(AgentResponse response) {
    long calls = 0;
    for (Message message : response.messages()) {
      for (Content content : message.content()) {
        if (content instanceof ToolCallContent) {
          calls++;
        }
      }
    }
    return calls;
  }

  private static String valueOrDefault(
      Map<String, String> environment, String variable, String fallback) {
    String value = environment.get(variable);
    return value == null || value.isBlank() ? fallback : value;
  }
}
