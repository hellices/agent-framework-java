package io.github.hellices.agentframework.api.agent;

import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.tool.ToolDefinition;
import io.github.hellices.agentframework.spi.model.ModelRequestOptions;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class RunContribution {

  private static final RunContribution EMPTY = new Builder().build();

  private final List<Message> messages;
  private final List<String> instructionAdditions;
  private final List<ToolDefinition> tools;
  private final ModelRequestOptions modelOptions;

  private RunContribution(Builder builder) {
    this.messages = immutableMessages(builder.messages);
    this.instructionAdditions = immutableInstructionAdditions(builder.instructionAdditions);
    this.tools = immutableTools(builder.tools);
    this.modelOptions =
        Objects.requireNonNull(builder.modelOptions, "modelOptions must not be null");
  }

  public static Builder builder() {
    return new Builder();
  }

  public static RunContribution empty() {
    return EMPTY;
  }

  public List<Message> messages() {
    return List.copyOf(messages);
  }

  public List<String> instructionAdditions() {
    return List.copyOf(instructionAdditions);
  }

  public List<ToolDefinition> tools() {
    return List.copyOf(tools);
  }

  public ModelRequestOptions modelOptions() {
    return modelOptions;
  }

  public Builder toBuilder() {
    return new Builder()
        .messages(messages)
        .instructionAdditions(instructionAdditions)
        .tools(tools)
        .modelOptions(modelOptions);
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof RunContribution that)) {
      return false;
    }
    return messages.equals(that.messages)
        && instructionAdditions.equals(that.instructionAdditions)
        && tools.equals(that.tools)
        && modelOptions.equals(that.modelOptions);
  }

  @Override
  public int hashCode() {
    return Objects.hash(messages, instructionAdditions, tools, modelOptions);
  }

  @Override
  public String toString() {
    return "RunContribution[messages="
        + messages
        + ", instructionAdditions="
        + instructionAdditions
        + ", tools="
        + tools
        + ", modelOptions="
        + modelOptions
        + "]";
  }

  public static final class Builder {
    private List<Message> messages = List.of();
    private List<String> instructionAdditions = List.of();
    private List<ToolDefinition> tools = List.of();
    private ModelRequestOptions modelOptions = ModelRequestOptions.empty();

    private Builder() {}

    public Builder messages(List<? extends Message> messages) {
      this.messages =
          immutableMessages(Objects.requireNonNull(messages, "messages must not be null"));
      return this;
    }

    public Builder addMessage(Message message) {
      Objects.requireNonNull(message, "message must not be null");
      List<Message> updated = new ArrayList<>(messages);
      updated.add(message);
      messages = List.copyOf(updated);
      return this;
    }

    public Builder instructionAdditions(List<String> instructionAdditions) {
      this.instructionAdditions =
          immutableInstructionAdditions(
              Objects.requireNonNull(
                  instructionAdditions, "instructionAdditions must not be null"));
      return this;
    }

    public Builder addInstructionAddition(String instructionAddition) {
      String normalizedInstruction =
          Objects.requireNonNull(instructionAddition, "instructionAddition must not be null");
      if (normalizedInstruction.isBlank()) {
        throw new IllegalArgumentException("instructionAddition must not be blank");
      }
      List<String> updated = new ArrayList<>(instructionAdditions);
      updated.add(normalizedInstruction);
      instructionAdditions = List.copyOf(updated);
      return this;
    }

    public Builder tools(List<? extends ToolDefinition> tools) {
      this.tools = immutableTools(Objects.requireNonNull(tools, "tools must not be null"));
      return this;
    }

    public Builder addTool(ToolDefinition tool) {
      ToolDefinition normalizedTool = Objects.requireNonNull(tool, "tool must not be null");
      ensureUniqueToolName(tools, normalizedTool);
      List<ToolDefinition> updated = new ArrayList<>(tools);
      updated.add(normalizedTool);
      tools = List.copyOf(updated);
      return this;
    }

    public Builder modelOptions(ModelRequestOptions modelOptions) {
      this.modelOptions = Objects.requireNonNull(modelOptions, "modelOptions must not be null");
      return this;
    }

    public RunContribution build() {
      return new RunContribution(this);
    }
  }

  private static List<Message> immutableMessages(List<? extends Message> source) {
    List<Message> normalizedMessages = new ArrayList<>();
    for (Message message : source) {
      normalizedMessages.add(
          Objects.requireNonNull(message, "messages must not contain null entries"));
    }
    return List.copyOf(normalizedMessages);
  }

  private static List<String> immutableInstructionAdditions(List<String> source) {
    List<String> normalizedInstructions = new ArrayList<>();
    for (String instructionAddition : source) {
      String normalizedInstruction =
          Objects.requireNonNull(
              instructionAddition, "instructionAdditions must not contain null entries");
      if (normalizedInstruction.isBlank()) {
        throw new IllegalArgumentException("instructionAddition must not be blank");
      }
      normalizedInstructions.add(normalizedInstruction);
    }
    return List.copyOf(normalizedInstructions);
  }

  private static List<ToolDefinition> immutableTools(List<? extends ToolDefinition> source) {
    List<ToolDefinition> normalizedTools = new ArrayList<>();
    for (ToolDefinition tool : source) {
      ToolDefinition normalizedTool =
          Objects.requireNonNull(tool, "tools must not contain null entries");
      ensureUniqueToolName(normalizedTools, normalizedTool);
      normalizedTools.add(normalizedTool);
    }
    return List.copyOf(normalizedTools);
  }

  private static void ensureUniqueToolName(
      List<ToolDefinition> existingTools, ToolDefinition candidate) {
    for (ToolDefinition existingTool : existingTools) {
      if (existingTool.name().equals(candidate.name())) {
        throw new IllegalArgumentException(
            "tools must not contain duplicate names: " + candidate.name());
      }
    }
  }
}
