package io.github.hellices.agentframework.otel;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.hellices.agentframework.spi.telemetry.TelemetryAttributes;
import io.github.hellices.agentframework.spi.telemetry.TelemetryEvent;
import io.github.hellices.agentframework.spi.telemetry.TelemetryOperation;
import io.github.hellices.agentframework.spi.telemetry.TelemetryOperationKind;
import io.github.hellices.agentframework.spi.telemetry.TelemetryStart;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Contract tests for {@link OpenTelemetrySink}: verifies that neutral telemetry values are mapped
 * to OTel GenAI semantic convention attributes without sensitive data.
 *
 * <p>{@code @SuppressWarnings("PMD.CloseResource")}: every test in this class exercises the
 * telemetry operation lifecycle explicitly — that is the subject under test. Resources are always
 * terminated by an explicit {@code close()} or {@code fail()} call; PMD cannot see this invariant
 * because it is part of the assertion, not structural control flow.
 */
@SuppressWarnings("PMD.CloseResource")
class OpenTelemetrySinkTest {

  @RegisterExtension
  static final OpenTelemetryExtension otelExtension = OpenTelemetryExtension.create();

  private OpenTelemetrySink sink() {
    return new OpenTelemetrySink(otelExtension.getOpenTelemetry());
  }

  // ── Agent run ─────────────────────────────────────────────────────────────

  @Test
  void agentRunSpanHasInvokeAgentName() {
    TelemetryOperation op =
        sink()
            .start(
                TelemetryStart.builder(TelemetryOperationKind.AGENT_RUN, "agent.run")
                    .attribute(TelemetryAttributes.AGENT_ID, "agent-1")
                    .attribute(TelemetryAttributes.AGENT_NAME, "assistant")
                    .build());
    op.close();

    List<SpanData> spans = otelExtension.getSpans();
    assertThat(spans).hasSize(1);
    SpanData span = spans.get(0);
    assertThat(span.getName()).isEqualTo("invoke_agent");
    assertThat(span.getAttributes().get(OpenTelemetrySink.GEN_AI_AGENT_ID)).isEqualTo("agent-1");
    assertThat(span.getAttributes().get(OpenTelemetrySink.GEN_AI_AGENT_NAME))
        .isEqualTo("assistant");
    assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.OK);
  }

  // ── Model call ────────────────────────────────────────────────────────────

  @Test
  void modelCallSpanHasChatOperationName() {
    TelemetryOperation op =
        sink()
            .start(
                TelemetryStart.builder(TelemetryOperationKind.MODEL_CALL, "model.call")
                    .attribute(TelemetryAttributes.MODEL_ITERATION, 0L)
                    .build());
    op.close();

    List<SpanData> spans = otelExtension.getSpans();
    assertThat(spans).hasSize(1);
    SpanData span = spans.get(0);
    assertThat(span.getName()).isEqualTo("chat");
    assertThat(span.getAttributes().get(OpenTelemetrySink.GEN_AI_OPERATION_NAME)).isEqualTo("chat");
    assertThat(span.getAttributes().get(OpenTelemetrySink.GEN_AI_MODEL_ITERATION)).isEqualTo(0L);
    assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.OK);
  }

  @Test
  void childSpanHasAgentRunAsDirectParent() {
    OpenTelemetrySink sink = sink();
    TelemetryOperation agentRunOp =
        sink.start(TelemetryStart.builder(TelemetryOperationKind.AGENT_RUN, "agent.run").build());
    TelemetryOperation modelCallOp =
        agentRunOp.startChild(
            TelemetryStart.builder(TelemetryOperationKind.MODEL_CALL, "model.call")
                .attribute(TelemetryAttributes.MODEL_ITERATION, 0L)
                .build());
    modelCallOp.close();
    agentRunOp.close();

    List<SpanData> spans = otelExtension.getSpans();
    assertThat(spans).hasSize(2);
    SpanData agentRunSpan =
        spans.stream()
            .filter(span -> span.getName().equals("invoke_agent"))
            .findFirst()
            .orElseThrow();
    SpanData modelCallSpan =
        spans.stream().filter(span -> span.getName().equals("chat")).findFirst().orElseThrow();
    assertThat(modelCallSpan.getParentSpanContext().getSpanId())
        .isEqualTo(agentRunSpan.getSpanContext().getSpanId());
    assertThat(modelCallSpan.getParentSpanContext().getTraceId())
        .isEqualTo(agentRunSpan.getSpanContext().getTraceId());
  }

  // ── Tool call ─────────────────────────────────────────────────────────────

  @Test
  void toolCallSpanIncludesToolNameAndBatchMetadata() {
    TelemetryOperation op =
        sink()
            .start(
                TelemetryStart.builder(TelemetryOperationKind.TOOL_CALL, "tool.call")
                    .attribute(TelemetryAttributes.TOOL_NAME, "weather")
                    .attribute(TelemetryAttributes.TOOL_CALL_COUNT, 2L)
                    .attribute(TelemetryAttributes.TOOL_CALL_INDEX, 1L)
                    .build());
    op.close();

    List<SpanData> spans = otelExtension.getSpans();
    assertThat(spans).hasSize(1);
    SpanData span = spans.get(0);
    assertThat(span.getName()).isEqualTo("execute_tool weather");
    assertThat(span.getAttributes().get(OpenTelemetrySink.GEN_AI_TOOL_NAME)).isEqualTo("weather");
    assertThat(span.getAttributes().get(OpenTelemetrySink.GEN_AI_TOOL_CALL_COUNT)).isEqualTo(2L);
    assertThat(span.getAttributes().get(OpenTelemetrySink.GEN_AI_TOOL_CALL_INDEX)).isEqualTo(1L);
  }

  // ── Failure ───────────────────────────────────────────────────────────────

  @Test
  void failSetsErrorStatusAndRecordsException() {
    RuntimeException cause = new RuntimeException("model broke");
    TelemetryOperation op =
        sink()
            .start(TelemetryStart.builder(TelemetryOperationKind.MODEL_CALL, "model.call").build());
    op.fail(cause);

    List<SpanData> spans = otelExtension.getSpans();
    assertThat(spans).hasSize(1);
    SpanData span = spans.get(0);
    assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
    assertThat(span.getEvents())
        .isNotEmpty()
        .anySatisfy(event -> assertThat(event.getName()).isEqualTo("exception"));
  }

  // ── Exactly-once close ────────────────────────────────────────────────────

  @Test
  void closeIsExactlyOnce() {
    TelemetryOperation op =
        sink().start(TelemetryStart.builder(TelemetryOperationKind.AGENT_RUN, "agent.run").build());
    op.close();
    op.close();
    op.close();

    assertThat(otelExtension.getSpans()).hasSize(1);
    assertThat(otelExtension.getSpans().get(0).getStatus().getStatusCode())
        .isEqualTo(StatusCode.OK);
  }

  @Test
  void failIsExactlyOnce() {
    RuntimeException cause = new RuntimeException("fail");
    TelemetryOperation op =
        sink()
            .start(TelemetryStart.builder(TelemetryOperationKind.MODEL_CALL, "model.call").build());
    op.fail(cause);
    op.fail(new RuntimeException("second fail"));

    List<SpanData> spans = otelExtension.getSpans();
    assertThat(spans).hasSize(1);
    assertThat(spans.get(0).getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
  }

  // ── Events ────────────────────────────────────────────────────────────────

  @Test
  void eventsAreRecordedOnSpan() {
    TelemetryOperation op =
        sink().start(TelemetryStart.builder(TelemetryOperationKind.AGENT_RUN, "agent.run").build());
    op.event(TelemetryEvent.of("approval.waiting"));
    op.close();

    List<SpanData> spans = otelExtension.getSpans();
    assertThat(spans).hasSize(1);
    List<EventData> events = spans.get(0).getEvents();
    assertThat(events).hasSize(1);
    assertThat(events.get(0).getName()).isEqualTo("approval.waiting");
  }

  // ── Sensitive data absence ────────────────────────────────────────────────

  @Test
  void noSensitiveDataIsPopulatedByThisAdapter() {
    TelemetryOperation op =
        sink()
            .start(
                TelemetryStart.builder(TelemetryOperationKind.TOOL_CALL, "tool.call")
                    .attribute(TelemetryAttributes.TOOL_NAME, "lookup")
                    .attribute(TelemetryAttributes.TOOL_CALL_COUNT, 1L)
                    .attribute(TelemetryAttributes.TOOL_CALL_INDEX, 0L)
                    .build());
    op.close();

    SpanData span = otelExtension.getSpans().get(0);
    span.getAttributes()
        .asMap()
        .forEach(
            (key, value) -> {
              String keyStr = key.getKey().toLowerCase();
              assertThat(keyStr)
                  .as("attribute key must not suggest sensitive data")
                  .doesNotContain("argument")
                  .doesNotContain("result")
                  .doesNotContain("prompt")
                  .doesNotContain("output")
                  .doesNotContain("credential");
            });
  }

  @Test
  void sessionOperationSpanMapsCorrectly() {
    TelemetryOperation op =
        sink()
            .start(
                TelemetryStart.builder(TelemetryOperationKind.SESSION_OPERATION, "session.op")
                    .attribute(TelemetryAttributes.SESSION_OPERATION, "load")
                    .attribute(TelemetryAttributes.SESSION_ID, "sess-1")
                    .build());
    op.close();

    List<SpanData> spans = otelExtension.getSpans();
    assertThat(spans).hasSize(1);
    SpanData span = spans.get(0);
    assertThat(span.getName()).isEqualTo("session.load");
    assertThat(span.getAttributes().get(AttributeKey.stringKey("session.id"))).isEqualTo("sess-1");
  }
}
