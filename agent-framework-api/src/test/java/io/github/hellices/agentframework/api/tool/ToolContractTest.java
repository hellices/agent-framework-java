package io.github.hellices.agentframework.api.tool;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hellices.agentframework.api.agent.CancellationSignal;
import io.github.hellices.agentframework.api.context.ContextAttributes;
import io.github.hellices.agentframework.api.context.ContextKey;
import io.github.hellices.agentframework.api.message.Content;
import io.github.hellices.agentframework.api.message.TextContent;
import io.github.hellices.agentframework.api.message.ToolCallContent;
import io.github.hellices.agentframework.api.message.ToolResultContent;
import io.github.hellices.agentframework.api.value.JsonObject;
import io.github.hellices.agentframework.api.value.JsonString;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolContractTest {

  @Test
  void functionToolExposesAnImmutableDefinitionAndExecutesItsHandler() {
    FunctionTool tool =
        FunctionTool.create(
            "weather",
            "Gets weather",
            JsonObject.builder().put("type", JsonString.of("object")).build(),
            (arguments, context) ->
                completedFuture(
                    ToolResult.success(
                        new TextContent("sunny:" + arguments.string("city").orElseThrow()))));

    ToolResult result =
        tool.execute(
                ToolArguments.of(JsonObject.builder().put("city", JsonString.of("Seoul")).build()),
                new ToolContext(
                    new CancellationSignal(),
                    ContextAttributes.builder()
                        .put(ContextKey.of("tool", "traceId", String.class), "1")
                        .build()))
            .toCompletableFuture()
            .join();

    assertThat(tool.definition().name()).isEqualTo("weather");
    assertThat(tool.definition().inputSchema().get("type")).contains(JsonString.of("object"));
    assertThat(tool.definition().toBuilder().build()).isEqualTo(tool.definition());
    assertThat(result.content())
        .extracting(content -> content.text())
        .containsExactly("sunny:Seoul");
  }

  @Test
  void toolNamesAndCallIdentifiersCannotBeBlank() {
    assertThatThrownBy(
            () ->
                FunctionTool.create(
                    " ",
                    "bad",
                    JsonObject.builder().build(),
                    (arguments, context) ->
                        completedFuture(ToolResult.success(new TextContent("unused")))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("tool name must not be blank");
    assertThatThrownBy(() -> new ToolCallContent(" ", "weather", Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("callId must not be blank");
  }

  @Test
  void toolCallAndResultContentKeepTypedPayloads() {
    ToolCallContent call =
        new ToolCallContent(
            "call-1", "weather", Map.of("city", "Seoul"), Map.of("provider", "fake"), "raw-call");
    ToolResultContent result =
        new ToolResultContent(
            "call-1",
            "weather",
            List.of(new TextContent("sunny")),
            false,
            Map.of("provider", "fake"),
            "raw-result");

    assertThat(call.type()).isEqualTo("tool_call");
    assertThat(call.arguments()).containsEntry("city", "Seoul");
    assertThat(call.additionalProperties()).containsEntry("provider", "fake");
    assertThat(call.rawRepresentation()).isEqualTo("raw-call");
    assertThat(result.type()).isEqualTo("tool_result");
    assertThat(result.content()).extracting(Content::text).containsExactly("sunny");
    assertThat(result.additionalProperties()).containsEntry("provider", "fake");
    assertThat(result.rawRepresentation()).isEqualTo("raw-result");
  }
}
