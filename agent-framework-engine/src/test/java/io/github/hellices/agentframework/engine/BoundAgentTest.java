package io.github.hellices.agentframework.engine;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hellices.agentframework.api.agent.Agent;
import io.github.hellices.agentframework.api.agent.AgentDefinition;
import io.github.hellices.agentframework.api.agent.AgentResponse;
import io.github.hellices.agentframework.api.agent.AgentRunOptions;
import io.github.hellices.agentframework.api.agent.AgentRuntime;
import io.github.hellices.agentframework.api.message.Content;
import io.github.hellices.agentframework.api.message.FinishReason;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.Role;
import io.github.hellices.agentframework.api.message.TextContent;
import io.github.hellices.agentframework.api.message.ToolCallContent;
import io.github.hellices.agentframework.api.message.ToolResultContent;
import io.github.hellices.agentframework.api.tool.ToolBinding;
import io.github.hellices.agentframework.api.tool.ToolDefinition;
import io.github.hellices.agentframework.api.tool.ToolResult;
import io.github.hellices.agentframework.api.value.JsonObject;
import io.github.hellices.agentframework.spi.model.ModelClient;
import io.github.hellices.agentframework.spi.model.ModelRequest;
import io.github.hellices.agentframework.spi.model.ModelResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class BoundAgentTest {

  @Test
  void bindProducesAnAgentCarryingTheDefinitionIdentity() {
    AgentEngine engine = new AgentEngine(null);
    AgentDefinition definition =
        AgentDefinition.builder().id("bound-1").name("assistant").description("desc").build();
    AgentRuntime runtime =
        AgentRuntime.builder().modelClient(request -> completedFuture(response("hi"))).build();

    Agent agent = engine.bind(definition, runtime);

    assertThat(agent).isInstanceOf(BoundAgent.class);
    assertThat(agent.id()).isEqualTo("bound-1");
    assertThat(agent.name()).isEqualTo("assistant");
    assertThat(agent.description()).isEqualTo("desc");
  }

  @Test
  void boundAgentDelegatesItsRunToTheEngine() {
    AgentEngine engine = new AgentEngine(null);
    AgentDefinition definition = AgentDefinition.builder().id("bound-2").build();
    AgentRuntime runtime =
        AgentRuntime.builder().modelClient(request -> completedFuture(response("hello"))).build();

    Agent agent = engine.bind(definition, runtime);

    assertThat(agent.run("hello").response().toCompletableFuture())
        .succeedsWithin(Duration.ofSeconds(1));
    assertThat(agent.run("hello").response().toCompletableFuture().join().text())
        .isEqualTo("hello");
  }

  @Test
  void bindValidatesTheRuntimeAgainstTheDefinitionBeforeReturning() {
    AgentEngine engine = new AgentEngine(null);
    AgentDefinition definition = AgentDefinition.builder().build();
    AgentRuntime runtime =
        AgentRuntime.builder()
            .modelClient(request -> completedFuture(response("unused")))
            .toolBinding(
                ToolBinding.of(
                    "ghost",
                    (arguments, context) ->
                        completedFuture(ToolResult.success(new TextContent("x")))))
            .build();

    assertThatThrownBy(() -> engine.bind(definition, runtime))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("tool binding has no matching declaration: ghost");
  }

  @Test
  void boundAgentDerivesTheToolBudgetFromTheDefinitionDefaultRunOptions() {
    AgentEngine engine = new AgentEngine(null);
    ToolDefinition weather = ToolDefinition.builder().name("weather").build();
    AgentDefinition definition =
        AgentDefinition.builder()
            .tool(weather)
            .defaultRunOptions(AgentRunOptions.builder().maxToolIterations(1).build())
            .build();
    ModelClient client =
        request ->
            completedFuture(
                ModelResponse.builder()
                    .messages(
                        List.of(
                            new Message(
                                Role.ASSISTANT,
                                List.of(
                                    new ToolCallContent("call-1", "weather", JsonObject.empty())))))
                    .finishReason(FinishReason.TOOL_CALLS)
                    .build());
    AgentRuntime runtime =
        AgentRuntime.builder()
            .modelClient(client)
            .toolBinding(
                ToolBinding.of(
                    "weather",
                    (arguments, context) ->
                        completedFuture(ToolResult.success(new TextContent("sunny")))))
            .build();

    Agent agent = engine.bind(definition, runtime);

    assertThatThrownBy(() -> agent.run("weather").response().toCompletableFuture().join())
        .hasRootCauseInstanceOf(IllegalStateException.class)
        .hasRootCauseMessage("model returned tool calls after tools were disabled");
  }

  @Test
  void aDeclarationOnlyToolIsOfferedToTheModel() {
    AgentEngine engine = new AgentEngine(null);
    ToolDefinition forecast = ToolDefinition.builder().name("forecast").build();
    AgentDefinition definition = AgentDefinition.builder().tool(forecast).build();
    AtomicReference<ModelRequest> captured = new AtomicReference<>();
    ModelClient client =
        request -> {
          captured.set(request);
          return completedFuture(response("done"));
        };
    AgentRuntime runtime = AgentRuntime.builder().modelClient(client).build();

    Agent agent = engine.bind(definition, runtime);
    agent.run("hi").response().toCompletableFuture().join();

    assertThat(captured.get().tools()).containsExactly(forecast);
  }

  @Test
  void anUnboundToolCallIsNotExecutedAndTheRunEndsWithTheModelResponse() {
    AgentEngine engine = new AgentEngine(null);
    ToolDefinition forecast = ToolDefinition.builder().name("forecast").build();
    AgentDefinition definition = AgentDefinition.builder().tool(forecast).build();
    ModelClient client =
        request ->
            completedFuture(
                toolCallResponse(new ToolCallContent("call-1", "forecast", JsonObject.empty())));
    AgentRuntime runtime = AgentRuntime.builder().modelClient(client).build();

    Agent agent = engine.bind(definition, runtime);
    AgentResponse response = agent.run("forecast").response().toCompletableFuture().join();

    assertThat(response.finishReason()).isEqualTo(FinishReason.TOOL_CALLS);
    assertThat(toolCalls(response)).extracting(ToolCallContent::name).containsExactly("forecast");
    assertThat(toolResults(response)).isEmpty();
  }

  @Test
  void aMixedBoundAndUnboundBatchExecutesNoneAndEndsWithTheModelResponse() {
    AgentEngine engine = new AgentEngine(null);
    ToolDefinition weather = ToolDefinition.builder().name("weather").build();
    ToolDefinition forecast = ToolDefinition.builder().name("forecast").build();
    AgentDefinition definition = AgentDefinition.builder().tool(weather).tool(forecast).build();
    ModelClient client =
        request ->
            completedFuture(
                toolCallResponse(
                    new ToolCallContent("call-1", "weather", JsonObject.empty()),
                    new ToolCallContent("call-2", "forecast", JsonObject.empty())));
    AtomicBoolean weatherRan = new AtomicBoolean();
    AgentRuntime runtime =
        AgentRuntime.builder()
            .modelClient(client)
            .toolBinding(
                ToolBinding.of(
                    "weather",
                    (arguments, context) -> {
                      weatherRan.set(true);
                      return completedFuture(ToolResult.success(new TextContent("sunny")));
                    }))
            .build();

    Agent agent = engine.bind(definition, runtime);
    AgentResponse response = agent.run("weather").response().toCompletableFuture().join();

    assertThat(weatherRan).isFalse();
    assertThat(response.finishReason()).isEqualTo(FinishReason.TOOL_CALLS);
    assertThat(toolResults(response)).isEmpty();
    assertThat(toolCalls(response))
        .extracting(ToolCallContent::name)
        .containsExactly("weather", "forecast");
  }

  private static ModelResponse toolCallResponse(ToolCallContent... calls) {
    return ModelResponse.builder()
        .messages(List.of(new Message(Role.ASSISTANT, List.<Content>of(calls))))
        .finishReason(FinishReason.TOOL_CALLS)
        .build();
  }

  private static List<ToolCallContent> toolCalls(AgentResponse response) {
    return contentOf(response, ToolCallContent.class);
  }

  private static List<ToolResultContent> toolResults(AgentResponse response) {
    return contentOf(response, ToolResultContent.class);
  }

  private static <T extends Content> List<T> contentOf(AgentResponse response, Class<T> type) {
    List<T> found = new ArrayList<>();
    for (Message message : response.messages()) {
      for (Content content : message.content()) {
        if (type.isInstance(content)) {
          found.add(type.cast(content));
        }
      }
    }
    return List.copyOf(found);
  }

  private static ModelResponse response(String text) {
    return ModelResponse.builder()
        .messages(List.of(new Message(Role.ASSISTANT, List.of(new TextContent(text)))))
        .finishReason(FinishReason.STOP)
        .build();
  }
}
