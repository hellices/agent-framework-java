package io.github.hellices.agentframework.engine.internal.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hellices.agentframework.api.message.Content;
import io.github.hellices.agentframework.api.message.FinishReason;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.Role;
import io.github.hellices.agentframework.api.message.TextContent;
import io.github.hellices.agentframework.api.message.ToolCallContent;
import io.github.hellices.agentframework.spi.model.ModelResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The tool calls a response amounts to. A streamed response can report one call in fragments, so
 * the fragments of a call id are merged into the single call both loops execute exactly once.
 */
class ToolLoopPolicyTest {

  @Test
  void fragmentsOfOneCallIdBecomeOneCallWithMergedArguments() {
    ModelResponse response =
        response(
            new ToolCallContent("call-1", "weather", Map.of("city", "Seo", "unit", "c")),
            new ToolCallContent("call-1", "weather", Map.of("city", "Seoul")));

    List<ToolCallContent> calls = ToolLoopPolicy.toolCalls(response);

    assertThat(calls).hasSize(1);
    assertThat(calls.get(0).callId()).isEqualTo("call-1");
    assertThat(calls.get(0).name()).isEqualTo("weather");
    assertThat(calls.get(0).arguments()).isEqualTo(Map.of("city", "Seoul", "unit", "c"));
  }

  @Test
  void mergedCallsKeepTheOrderTheirFirstFragmentArrivedIn() {
    ModelResponse response =
        response(
            new ToolCallContent("call-2", "second", Map.of()),
            new ToolCallContent("call-1", "first", Map.of()),
            new ToolCallContent("call-2", "second", Map.of("done", true)));

    assertThat(ToolLoopPolicy.toolCalls(response))
        .extracting(ToolCallContent::callId)
        .containsExactly("call-2", "call-1");
  }

  @Test
  void mergingKeepsTheProviderPropertiesAndRawHandleOfTheFragments() {
    ModelResponse response =
        response(
            new ToolCallContent(
                "call-1", "weather", Map.of(), Map.of("index", 0, "shared", "first"), "raw-first"),
            new ToolCallContent(
                "call-1", "weather", Map.of(), Map.of("shared", "second"), "raw-second"),
            new ToolCallContent("call-1", "weather", Map.of(), Map.of("last", true), null));

    ToolCallContent merged = ToolLoopPolicy.toolCalls(response).get(0);

    assertThat(merged.additionalProperties())
        .isEqualTo(Map.of("index", 0, "shared", "second", "last", true));
    assertThat(merged.rawRepresentation()).isEqualTo("raw-second");
  }

  @Test
  void aCallIdReportedWithTwoToolNamesIsRejected() {
    ModelResponse response =
        response(
            new ToolCallContent("call-1", "weather", Map.of()),
            new ToolCallContent("call-1", "forecast", Map.of()));

    assertThatThrownBy(() -> ToolLoopPolicy.toolCalls(response))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("tool call call-1 was reported as both 'weather' and 'forecast'");
  }

  @Test
  void aResponseWithoutToolCallsHasNoCalls() {
    assertThat(ToolLoopPolicy.toolCalls(response(new TextContent("just text")))).isEmpty();
  }

  private static ModelResponse response(Content... content) {
    return new ModelResponse(
        List.of(new Message(Role.ASSISTANT, List.of(content))),
        null,
        FinishReason.TOOL_CALLS,
        Map.of(),
        null);
  }
}
