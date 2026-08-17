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
            Map.of("type", "object"),
            (arguments, context) ->
                completedFuture(
                    ToolResult.success(new TextContent("sunny:" + arguments.get("city")))));

    ToolResult result =
        tool.execute(
                new ToolArguments(Map.of("city", "Seoul")),
                new ToolContext(
                    new CancellationSignal(),
                    ContextAttributes.builder()
                        .put(ContextKey.of("tool", "traceId", String.class), "1")
                        .build()))
            .toCompletableFuture()
            .join();

    assertThat(tool.definition().name()).isEqualTo("weather");
    assertThat(tool.definition().inputSchema()).containsEntry("type", "object");
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
                    Map.of(),
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
