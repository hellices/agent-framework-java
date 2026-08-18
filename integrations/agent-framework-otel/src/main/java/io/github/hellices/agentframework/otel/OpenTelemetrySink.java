package io.github.hellices.agentframework.otel;

import io.github.hellices.agentframework.spi.telemetry.TelemetryAttributeMap;
import io.github.hellices.agentframework.spi.telemetry.TelemetryAttributes;
import io.github.hellices.agentframework.spi.telemetry.TelemetryEvent;
import io.github.hellices.agentframework.spi.telemetry.TelemetryOperation;
import io.github.hellices.agentframework.spi.telemetry.TelemetryOperationKind;
import io.github.hellices.agentframework.spi.telemetry.TelemetrySink;
import io.github.hellices.agentframework.spi.telemetry.TelemetryStart;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link TelemetrySink} that maps Agent Framework for Java neutral telemetry values to
 * OpenTelemetry GenAI semantic convention spans.
 *
 * <p>This adapter requires an {@link OpenTelemetry} instance provided by the caller. The engine
 * modules ({@code agent-framework-api} and {@code agent-framework-engine}) do not import OTel; only
 * this adapter does.
 *
 * <h2>Span mapping</h2>
 *
 * <ul>
 *   <li>{@link TelemetryOperationKind#AGENT_RUN}: span name {@code invoke_agent}, attributes {@code
 *       gen_ai.agent.id} and {@code gen_ai.agent.name}.
 *   <li>{@link TelemetryOperationKind#MODEL_CALL}: span name {@code chat}, attributes {@code
 *       gen_ai.operation.name} and iteration index.
 *   <li>{@link TelemetryOperationKind#TOOL_CALL}: span name {@code execute_tool {toolName}},
 *       attributes {@code gen_ai.tool.name}, {@code gen_ai.tool.call.count}, and {@code
 *       gen_ai.tool.call.index}.
 *   <li>{@link TelemetryOperationKind#SESSION_OPERATION}: span name {@code session.{operation}},
 *       attributes {@code session.id}.
 * </ul>
 *
 * <p>Sensitive data (prompt bodies, model output, tool arguments, tool results, credentials, and
 * personal traces) is never added to spans by this adapter — only the stable, non-sensitive
 * attributes from {@link TelemetryAttributes} are mapped.
 */
public final class OpenTelemetrySink implements TelemetrySink {

  private static final String INSTRUMENTATION_SCOPE = "io.github.hellices.agentframework";

  // GenAI semantic convention attribute keys
  static final AttributeKey<String> GEN_AI_OPERATION_NAME =
      AttributeKey.stringKey("gen_ai.operation.name");
  static final AttributeKey<String> GEN_AI_AGENT_ID = AttributeKey.stringKey("gen_ai.agent.id");
  static final AttributeKey<String> GEN_AI_AGENT_NAME = AttributeKey.stringKey("gen_ai.agent.name");
  static final AttributeKey<String> GEN_AI_TOOL_NAME = AttributeKey.stringKey("gen_ai.tool.name");
  static final AttributeKey<Long> GEN_AI_TOOL_CALL_COUNT =
      AttributeKey.longKey("gen_ai.tool.call.count");
  static final AttributeKey<Long> GEN_AI_TOOL_CALL_INDEX =
      AttributeKey.longKey("gen_ai.tool.call.index");
  static final AttributeKey<Long> GEN_AI_MODEL_ITERATION =
      AttributeKey.longKey("gen_ai.model.iteration");

  private final Tracer tracer;

  /**
   * Creates a sink backed by the given {@link OpenTelemetry} instance.
   *
   * @param openTelemetry the OTel instance to create spans through; must not be null
   */
  public OpenTelemetrySink(OpenTelemetry openTelemetry) {
    Objects.requireNonNull(openTelemetry, "openTelemetry must not be null");
    this.tracer = openTelemetry.getTracer(INSTRUMENTATION_SCOPE);
  }

  @Override
  public TelemetryOperation start(TelemetryStart start) {
    Objects.requireNonNull(start, "start must not be null");
    Span span = buildSpan(start);
    return new OtelTelemetryOperation(span, tracer);
  }

  private Span buildSpan(TelemetryStart start) {
    return buildSpanWithParent(tracer, start, null);
  }

  private Span buildChildSpan(TelemetryStart start, Span parentSpan) {
    return buildSpanWithParent(tracer, start, Context.root().with(parentSpan));
  }

  private static Span buildSpanWithParent(
      Tracer tracer, TelemetryStart start, Context parentContext) {
    TelemetryAttributeMap attrs = start.attributes();
    return switch (start.kind()) {
      case AGENT_RUN -> {
        var builder = tracer.spanBuilder("invoke_agent").setSpanKind(SpanKind.INTERNAL);
        if (parentContext != null) {
          builder = builder.setParent(parentContext);
        }
        Span span = builder.startSpan();
        String agentId = attrs.getString(TelemetryAttributes.AGENT_ID);
        if (agentId != null) {
          span.setAttribute(GEN_AI_AGENT_ID, agentId);
        }
        String agentName = attrs.getString(TelemetryAttributes.AGENT_NAME);
        if (agentName != null && !agentName.isEmpty()) {
          span.setAttribute(GEN_AI_AGENT_NAME, agentName);
        }
        span.setAttribute(GEN_AI_OPERATION_NAME, "invoke_agent");
        yield span;
      }
      case MODEL_CALL -> {
        var builder = tracer.spanBuilder("chat").setSpanKind(SpanKind.CLIENT);
        if (parentContext != null) {
          builder = builder.setParent(parentContext);
        }
        Span span = builder.startSpan();
        span.setAttribute(GEN_AI_OPERATION_NAME, "chat");
        Long iteration = attrs.getLong(TelemetryAttributes.MODEL_ITERATION);
        if (iteration != null) {
          span.setAttribute(GEN_AI_MODEL_ITERATION, iteration);
        }
        yield span;
      }
      case TOOL_CALL -> {
        String toolName = attrs.getString(TelemetryAttributes.TOOL_NAME);
        String spanName = toolName != null ? "execute_tool " + toolName : "execute_tool";
        var builder = tracer.spanBuilder(spanName).setSpanKind(SpanKind.INTERNAL);
        if (parentContext != null) {
          builder = builder.setParent(parentContext);
        }
        Span span = builder.startSpan();
        if (toolName != null) {
          span.setAttribute(GEN_AI_TOOL_NAME, toolName);
        }
        Long count = attrs.getLong(TelemetryAttributes.TOOL_CALL_COUNT);
        if (count != null) {
          span.setAttribute(GEN_AI_TOOL_CALL_COUNT, count);
        }
        Long index = attrs.getLong(TelemetryAttributes.TOOL_CALL_INDEX);
        if (index != null) {
          span.setAttribute(GEN_AI_TOOL_CALL_INDEX, index);
        }
        yield span;
      }
      case SESSION_OPERATION -> {
        String operation = attrs.getString(TelemetryAttributes.SESSION_OPERATION);
        String spanName = operation != null ? "session." + operation : "session.operation";
        var builder = tracer.spanBuilder(spanName).setSpanKind(SpanKind.INTERNAL);
        if (parentContext != null) {
          builder = builder.setParent(parentContext);
        }
        Span span = builder.startSpan();
        String sessionId = attrs.getString(TelemetryAttributes.SESSION_ID);
        if (sessionId != null) {
          span.setAttribute(AttributeKey.stringKey("session.id"), sessionId);
        }
        yield span;
      }
    };
  }

  private final class OtelTelemetryOperation implements TelemetryOperation {

    private final Span span;
    private final Tracer tracer;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private OtelTelemetryOperation(Span span, Tracer tracer) {
      this.span = span;
      this.tracer = tracer;
    }

    @Override
    public TelemetryOperation startChild(TelemetryStart start) {
      Objects.requireNonNull(start, "start must not be null");
      Span childSpan = buildChildSpan(start, span);
      return new OtelTelemetryOperation(childSpan, tracer);
    }

    @Override
    public void event(TelemetryEvent event) {
      Objects.requireNonNull(event, "event must not be null");
      if (event.attributes().isEmpty()) {
        span.addEvent(event.name());
      } else {
        io.opentelemetry.api.common.AttributesBuilder builder = Attributes.builder();
        event
            .attributes()
            .forEach(
                (k, v) -> {
                  if (v instanceof String s) {
                    builder.put(AttributeKey.stringKey(k), s);
                  } else if (v instanceof Long l) {
                    builder.put(AttributeKey.longKey(k), l);
                  }
                });
        span.addEvent(event.name(), builder.build());
      }
    }

    @Override
    public void fail(Throwable failure) {
      Objects.requireNonNull(failure, "failure must not be null");
      if (closed.compareAndSet(false, true)) {
        span.recordException(failure);
        span.setStatus(StatusCode.ERROR, failure.getMessage());
        span.end();
      }
    }

    @Override
    public void close() {
      if (closed.compareAndSet(false, true)) {
        span.setStatus(StatusCode.OK);
        span.end();
      }
    }
  }
}
