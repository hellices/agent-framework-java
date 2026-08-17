package io.github.hellices.agentframework.engine;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hellices.agentframework.api.agent.Agent;
import io.github.hellices.agentframework.api.agent.AgentBuilder;
import io.github.hellices.agentframework.api.agent.AgentDefinition;
import io.github.hellices.agentframework.api.agent.AgentFactory;
import io.github.hellices.agentframework.api.agent.AgentRuntime;
import io.github.hellices.agentframework.api.message.FinishReason;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.Role;
import io.github.hellices.agentframework.api.message.TextContent;
import io.github.hellices.agentframework.api.message.ToolCallContent;
import io.github.hellices.agentframework.api.tool.FunctionTool;
import io.github.hellices.agentframework.api.tool.ToolDefinition;
import io.github.hellices.agentframework.api.tool.ToolResult;
import io.github.hellices.agentframework.api.value.JsonObject;
import io.github.hellices.agentframework.api.value.JsonValues;
import io.github.hellices.agentframework.spi.model.ModelCatalog;
import io.github.hellices.agentframework.spi.model.ModelClient;
import io.github.hellices.agentframework.spi.model.ModelRequest;
import io.github.hellices.agentframework.spi.model.ModelResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AgentFactoryTest {

  @Test
  void factoryBuildsAnAgentFromTheDefaultCatalogModel() {
    ModelClient client = request -> completedFuture(response("default"));
    AgentEngine engine = AgentEngine.builder().build();

    Agent agent =
        engine
            .factory(
                ModelCatalog.builder()
                    .add("deterministic", client)
                    .defaultModel("deterministic")
                    .build())
            .builder()
            .id("standalone")
            .name("Standalone")
            .build();

    assertThat(agent.id()).isEqualTo("standalone");
    assertThat(agent.run("hello").response().toCompletableFuture().join().text())
        .isEqualTo("default");
  }

  @Test
  void factoryCanSelectNamedAndExplicitModels() {
    ModelClient named = request -> completedFuture(response("named"));
    ModelClient explicit = request -> completedFuture(response("explicit"));
    AgentEngine engine = AgentEngine.builder().build();

    assertThat(
            engine
                .factory(ModelCatalog.builder().add("named", named).build())
                .builder("named")
                .build()
                .run("hi")
                .response()
                .toCompletableFuture()
                .join()
                .text())
        .isEqualTo("named");
    assertThat(
            engine
                .factory(ModelCatalog.builder().build())
                .builderWithClient(explicit)
                .build()
                .run("hi")
                .response()
                .toCompletableFuture()
                .join()
                .text())
        .isEqualTo("explicit");
  }

  @Test
  void factoryHandsOutAFreshBuilderPerCall() {
    ModelClient client = request -> completedFuture(response("shared"));
    AgentFactory factory = AgentEngine.builder().build().factory(ModelCatalog.builder().build());

    AgentBuilder first = factory.builderWithClient(client).id("first");
    AgentBuilder second = factory.builderWithClient(client).id("second");

    assertThat(first).isNotSameAs(second);
    assertThat(first.build().id()).isEqualTo("first");
    assertThat(second.build().id()).isEqualTo("second");
  }

  @Test
  void buildDefinitionReturnsDeclarationsWithoutBindingAnAgent() {
    ModelClient client = request -> completedFuture(response("declared"));
    AgentFactory factory = AgentEngine.builder().build().factory(ModelCatalog.builder().build());

    AgentDefinition definition =
        factory
            .builderWithClient(client)
            .id("declared-agent")
            .name("Declared")
            .instructions("be concise")
            .tools(weatherTool())
            .maxIterations(3)
            .buildDefinition();

    assertThat(definition.id()).isEqualTo("declared-agent");
    assertThat(definition.name()).isEqualTo("Declared");
    assertThat(definition.instructions()).isEqualTo("be concise");
    assertThat(definition.tools()).extracting(ToolDefinition::name).containsExactly("get_weather");
    assertThat(definition.defaultRunOptions().maxToolIterations()).isEqualTo(3);
  }

  @Test
  void aFunctionToolProducesBothADeclarationAndAnExecutableBinding() {
    AgentFactory factory = AgentEngine.builder().build().factory(ModelCatalog.builder().build());

    Agent agent =
        factory.builderWithClient(new WeatherThenTextClient()).tools(weatherTool()).build();

    assertThat(agent.run("weather in paris").response().toCompletableFuture().join().text())
        .isEqualTo("done");
  }

  @Test
  void bindPreservesADeclarationOnlyToolFromAManuallyConstructedDefinition() {
    ModelClient client = request -> completedFuture(response("noop"));
    AgentFactory factory = AgentEngine.builder().build().factory(ModelCatalog.builder().build());
    AgentDefinition definition = AgentDefinition.builder().tool(weatherTool().definition()).build();
    AgentRuntime runtime = AgentRuntime.builder().modelClient(client).build();

    Agent agent = factory.bind(definition, runtime);

    assertThat(agent.run("hi").response().toCompletableFuture().join().text()).isEqualTo("noop");
  }

  @Test
  void instructionsAreStoredDeclarativelyWithoutAffectingTheModelRequest() {
    List<ModelRequest> captured = new ArrayList<>();
    ModelClient client =
        request -> {
          captured.add(request);
          return completedFuture(response("answer"));
        };
    AgentFactory factory = AgentEngine.builder().build().factory(ModelCatalog.builder().build());

    Agent agent =
        factory.builderWithClient(client).instructions("you are a helpful assistant").build();

    agent.run("hi").response().toCompletableFuture().join();

    assertThat(captured).hasSize(1);
    assertThat(captured.get(0).messages())
        .noneMatch(
            message ->
                message.content().stream()
                    .anyMatch(
                        content ->
                            content instanceof TextContent text
                                && text.text().contains("you are a helpful assistant")));
  }

  @Test
  void builderWithClientRejectsANullModelClient() {
    AgentFactory factory = AgentEngine.builder().build().factory(ModelCatalog.builder().build());
    assertThatThrownBy(() -> factory.builderWithClient(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("modelClient must not be null");
  }

  @Test
  void maxIterationsRejectsANonPositiveBudget() {
    ModelClient client = request -> completedFuture(response("x"));
    AgentFactory factory = AgentEngine.builder().build().factory(ModelCatalog.builder().build());
    assertThatThrownBy(() -> factory.builderWithClient(client).maxIterations(0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("maxIterations must be greater than 0");
  }

  @Test
  void toolsRejectsANullEntry() {
    ModelClient client = request -> completedFuture(response("x"));
    AgentFactory factory = AgentEngine.builder().build().factory(ModelCatalog.builder().build());
    assertThatThrownBy(() -> factory.builderWithClient(client).tools((FunctionTool) null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("tools must not contain null entries");
  }

  private static final class WeatherThenTextClient implements ModelClient {

    private final AtomicInteger calls = new AtomicInteger();

    @Override
    public CompletionStage<ModelResponse> run(ModelRequest request) {
      if (calls.getAndIncrement() == 0) {
        return completedFuture(
            ModelResponse.builder()
                .messages(
                    List.of(
                        new Message(
                            Role.ASSISTANT,
                            List.of(
                                new ToolCallContent(
                                    "call-1",
                                    "get_weather",
                                    jsonObject(Map.of("city", "paris")))))))
                .finishReason(FinishReason.TOOL_CALLS)
                .build());
      }
      return completedFuture(response("done"));
    }
  }

  private static FunctionTool weatherTool() {
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "object");
    schema.put("properties", Map.of("city", Map.of("type", "string")));
    JsonObject inputSchema = (JsonObject) JsonValues.fromJava(schema);
    return FunctionTool.create(
        "get_weather",
        "Looks up weather",
        inputSchema,
        (arguments, context) ->
            completedFuture(
                ToolResult.success(
                    new TextContent("sunny:" + arguments.string("city").orElseThrow()))));
  }

  private static JsonObject jsonObject(Map<String, Object> values) {
    return (JsonObject) JsonValues.fromJava(values);
  }

  private static ModelResponse response(String text) {
    return ModelResponse.builder()
        .messages(List.of(new Message(Role.ASSISTANT, List.of(new TextContent(text)))))
        .finishReason(FinishReason.STOP)
        .build();
  }
}
