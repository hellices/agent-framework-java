package io.github.hellices.agentframework.engine.internal.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hellices.agentframework.api.agent.AgentResponseUpdate;
import io.github.hellices.agentframework.api.message.FinishReason;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.Role;
import io.github.hellices.agentframework.api.message.TextContent;
import io.github.hellices.agentframework.api.message.ToolCallContent;
import io.github.hellices.agentframework.api.message.Usage;
import io.github.hellices.agentframework.spi.model.ModelResponse;
import io.github.hellices.agentframework.spi.model.ModelResponseUpdate;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StreamingModelResponseAccumulatorTest {

  private static final ResponseIdentity IDENTITY =
      new ResponseIdentity("agent-1", "response-1", "agent", Instant.parse("2026-01-01T00:00:00Z"));

  @Test
  void reassemblesTheToolCallsUsageAndOutcomeOfOneModelCall() {
    StreamingModelResponseAccumulator accumulator = new StreamingModelResponseAccumulator(IDENTITY);

    accumulator.record(
        new ModelResponseUpdate(
            List.of(message(new TextContent("looking it up"))),
            new Usage(3, 0, 3, Map.of()),
            FinishReason.TOOL_CALLS,
            Map.of(),
            null));
    accumulator.record(
        new ModelResponseUpdate(
            List.of(message(new ToolCallContent("call-1", "weather", Map.of("city", "Seoul")))),
            new Usage(0, 4, 4, Map.of()),
            FinishReason.TOOL_CALLS,
            Map.of(),
            null));

    ModelResponse response = accumulator.toModelResponse();

    assertThat(response.finishReason()).isEqualTo(FinishReason.TOOL_CALLS);
    assertThat(response.usage()).isEqualTo(new Usage(3, 4, 7, Map.of()));
    assertThat(response.messages()).hasSize(1);
    assertThat(response.messages().get(0).content())
        .extracting(content -> content instanceof ToolCallContent call ? call.name() : "text")
        .containsExactly("text", "weather");
  }

  @Test
  void anEmptyModelCallFailsInsteadOfInventingAResponse() {
    StreamingModelResponseAccumulator accumulator = new StreamingModelResponseAccumulator(IDENTITY);

    assertThat(accumulator.isEmpty()).isTrue();
    assertThatThrownBy(accumulator::toModelResponse)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("model stream completed without any update");
  }

  @Test
  void anUpdateOfAnotherResponseIsRejectedWhereItIsRecorded() {
    StreamingModelResponseAccumulator accumulator = new StreamingModelResponseAccumulator(IDENTITY);
    AgentResponseUpdate foreign =
        new AgentResponseUpdate(
            "agent-1",
            "response-2",
            null,
            "agent",
            IDENTITY.createdAt(),
            FinishReason.STOP,
            null,
            List.of(message(new TextContent("elsewhere"))),
            null,
            Map.of(),
            null);

    assertThatThrownBy(() -> accumulator.record(foreign))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("model update does not belong to this agent response");
  }

  @Test
  void aNullModelUpdateIsRejected() {
    StreamingModelResponseAccumulator accumulator = new StreamingModelResponseAccumulator(IDENTITY);

    assertThatThrownBy(() -> accumulator.record((ModelResponseUpdate) null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("model response update must not be null");
  }

  @Test
  void recordedUpdatesCarryTheRunsIdentityAndTheModelsValues() {
    StreamingModelResponseAccumulator accumulator = new StreamingModelResponseAccumulator(IDENTITY);

    AgentResponseUpdate mapped =
        accumulator.record(
            new ModelResponseUpdate(
                List.of(message(new TextContent("hi"))),
                null,
                FinishReason.STOP,
                Map.of("provider", "fake"),
                "raw"));

    assertThat(mapped.agentId()).isEqualTo("agent-1");
    assertThat(mapped.responseId()).isEqualTo("response-1");
    assertThat(mapped.authorName()).isEqualTo("agent");
    assertThat(mapped.createdAt()).isEqualTo(IDENTITY.createdAt());
    assertThat(mapped.finishReason()).isEqualTo(FinishReason.STOP);
    assertThat(mapped.additionalProperties()).containsExactly(Map.entry("provider", "fake"));
    assertThat(mapped.rawRepresentation()).isEqualTo("raw");
  }

  @Test
  void aSynthesisedMessageUpdateReportsNoModelOutcome() {
    AgentResponseUpdate update =
        IDENTITY.messageUpdate(List.of(message(new TextContent("engine made this"))));

    assertThat(update.agentId()).isEqualTo("agent-1");
    assertThat(update.responseId()).isEqualTo("response-1");
    assertThat(update.messageId()).isNull();
    assertThat(update.finishReason()).isNull();
    assertThat(update.continuationToken()).isNull();
    assertThat(update.usage()).isNull();
    assertThat(update.additionalProperties()).isEmpty();
    assertThat(update.rawRepresentation()).isNull();
  }

  private static Message message(io.github.hellices.agentframework.api.message.Content content) {
    return new Message(Role.ASSISTANT, List.of(content));
  }
}
