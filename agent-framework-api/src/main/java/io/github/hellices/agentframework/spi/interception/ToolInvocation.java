package io.github.hellices.agentframework.spi.interception;

import io.github.hellices.agentframework.api.message.ToolCallContent;
import io.github.hellices.agentframework.api.tool.ToolArguments;
import io.github.hellices.agentframework.api.tool.ToolContext;
import io.github.hellices.agentframework.api.tool.ToolDefinition;
import java.util.Objects;

/** Immutable tool-call invocation data passed through the interceptor seam. */
public final class ToolInvocation {

  private final ToolCallContent toolCall;
  private final ToolDefinition toolDefinition;
  private final ToolArguments arguments;
  private final ToolContext context;

  private ToolInvocation(Builder builder) {
    this.toolCall =
        immutableCopy(Objects.requireNonNull(builder.toolCall, "toolCall must not be null"));
    this.toolDefinition =
        Objects.requireNonNull(builder.toolDefinition, "toolDefinition must not be null");
    this.arguments =
        builder.arguments == null ? ToolArguments.of(toolCall.arguments()) : builder.arguments;
    this.context = Objects.requireNonNull(builder.context, "context must not be null");
  }

  public static Builder builder() {
    return new Builder();
  }

  public ToolCallContent toolCall() {
    return immutableCopy(toolCall);
  }

  public ToolDefinition toolDefinition() {
    return toolDefinition;
  }

  public ToolArguments arguments() {
    return arguments;
  }

  public ToolContext context() {
    return context;
  }

  public Builder toBuilder() {
    return new Builder()
        .toolCall(toolCall)
        .toolDefinition(toolDefinition)
        .arguments(arguments)
        .context(context);
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof ToolInvocation that)) {
      return false;
    }
    return toolCall.equals(that.toolCall)
        && toolDefinition.equals(that.toolDefinition)
        && arguments.equals(that.arguments)
        && context.equals(that.context);
  }

  @Override
  public int hashCode() {
    return Objects.hash(toolCall, toolDefinition, arguments, context);
  }

  @Override
  public String toString() {
    return "ToolInvocation[toolCall="
        + toolCall
        + ", toolDefinition="
        + toolDefinition
        + ", arguments="
        + arguments
        + ", context="
        + context
        + "]";
  }

  public static final class Builder {
    private ToolCallContent toolCall;
    private ToolDefinition toolDefinition;
    private ToolArguments arguments;
    private ToolContext context;

    private Builder() {}

    public Builder toolCall(ToolCallContent toolCall) {
      this.toolCall = Objects.requireNonNull(toolCall, "toolCall must not be null");
      return this;
    }

    public Builder toolDefinition(ToolDefinition toolDefinition) {
      this.toolDefinition =
          Objects.requireNonNull(toolDefinition, "toolDefinition must not be null");
      return this;
    }

    public Builder arguments(ToolArguments arguments) {
      this.arguments = Objects.requireNonNull(arguments, "arguments must not be null");
      return this;
    }

    public Builder context(ToolContext context) {
      this.context = Objects.requireNonNull(context, "context must not be null");
      return this;
    }

    public ToolInvocation build() {
      return new ToolInvocation(this);
    }
  }

  private static ToolCallContent immutableCopy(ToolCallContent toolCall) {
    return new ToolCallContent(
        toolCall.callId(),
        toolCall.name(),
        toolCall.arguments(),
        toolCall.additionalProperties(),
        toolCall.rawRepresentation());
  }
}
