package io.github.hellices.agentframework.api.agent;

import io.github.hellices.agentframework.api.context.ContextAttributes;
import io.github.hellices.agentframework.api.tool.ToolDefinition;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class AgentDefinition {

  private final String id;
  private final String name;
  private final String description;
  private final String instructions;
  private final List<ToolDefinition> tools;
  private final AgentRunOptions defaultRunOptions;
  private final ContextAttributes attributes;

  private AgentDefinition(Builder builder) {
    String normalizedId = builder.id;
    if (normalizedId == null) {
      normalizedId = UUID.randomUUID().toString();
    } else if (normalizedId.isBlank()) {
      throw new IllegalArgumentException("id must not be blank");
    }
    String normalizedName = builder.name;
    if (normalizedName == null) {
      normalizedName = "agent";
    } else if (normalizedName.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
    AgentRunOptions normalizedRunOptions =
        builder.defaultRunOptions == null ? new AgentRunOptions() : builder.defaultRunOptions;
    if (normalizedRunOptions.modelClientFactory().isPresent()) {
      throw new IllegalArgumentException("defaultRunOptions must not include modelClientFactory");
    }
    if (normalizedRunOptions.continuationToken().isPresent()) {
      throw new IllegalArgumentException(
          "defaultRunOptions must not include continuationToken; use AgentRunRequest options for per-run continuation");
    }
    List<ToolDefinition> normalizedTools = List.copyOf(builder.tools);
    validateUniqueToolNames(normalizedTools);
    this.id = normalizedId;
    this.name = normalizedName;
    this.description = builder.description;
    this.instructions = builder.instructions;
    this.tools = normalizedTools;
    this.defaultRunOptions = normalizedRunOptions;
    this.attributes = builder.attributes == null ? ContextAttributes.empty() : builder.attributes;
  }

  public static Builder builder() {
    return new Builder();
  }

  public String id() {
    return id;
  }

  public String name() {
    return name;
  }

  public String description() {
    return description;
  }

  public String instructions() {
    return instructions;
  }

  public List<ToolDefinition> tools() {
    return tools;
  }

  public AgentRunOptions defaultRunOptions() {
    return defaultRunOptions;
  }

  public ContextAttributes attributes() {
    return attributes;
  }

  public Builder toBuilder() {
    return new Builder()
        .id(id)
        .name(name)
        .description(description)
        .instructions(instructions)
        .tools(tools)
        .defaultRunOptions(defaultRunOptions)
        .attributes(attributes);
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof AgentDefinition that)) {
      return false;
    }
    return id.equals(that.id)
        && name.equals(that.name)
        && description.equals(that.description)
        && instructions.equals(that.instructions)
        && tools.equals(that.tools)
        && equalRunOptions(defaultRunOptions, that.defaultRunOptions)
        && attributes.equals(that.attributes);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        id,
        name,
        description,
        instructions,
        tools,
        defaultRunOptions.attributes(),
        defaultRunOptions.modelClientFactory().orElse(null),
        defaultRunOptions.continuationToken().orElse(null),
        defaultRunOptions.maxToolIterations(),
        attributes);
  }

  @Override
  public String toString() {
    return "AgentDefinition[id="
        + id
        + ", name="
        + name
        + ", description="
        + description
        + ", instructions="
        + instructions
        + ", tools="
        + tools
        + ", defaultRunOptions="
        + defaultRunOptions
        + ", attributes="
        + attributes
        + "]";
  }

  private static boolean equalRunOptions(AgentRunOptions left, AgentRunOptions right) {
    return left.attributes().equals(right.attributes())
        && left.modelClientFactory().equals(right.modelClientFactory())
        && left.continuationToken().equals(right.continuationToken())
        && left.maxToolIterations() == right.maxToolIterations();
  }

  private static void validateUniqueToolNames(List<ToolDefinition> tools) {
    Set<String> names = new LinkedHashSet<>();
    for (ToolDefinition tool : tools) {
      if (!names.add(tool.name())) {
        throw new IllegalArgumentException("duplicate tool name: " + tool.name());
      }
    }
  }

  public static final class Builder {
    private String id;
    private String name;
    private String description = "";
    private String instructions = "";
    private List<ToolDefinition> tools = new ArrayList<>();
    private AgentRunOptions defaultRunOptions;
    private ContextAttributes attributes = ContextAttributes.empty();

    private Builder() {}

    public Builder id(String id) {
      this.id = Objects.requireNonNull(id, "id must not be null");
      return this;
    }

    public Builder name(String name) {
      this.name = Objects.requireNonNull(name, "name must not be null");
      return this;
    }

    public Builder description(String description) {
      this.description = Objects.requireNonNull(description, "description must not be null");
      return this;
    }

    public Builder instructions(String instructions) {
      this.instructions = Objects.requireNonNull(instructions, "instructions must not be null");
      return this;
    }

    public Builder tool(ToolDefinition tool) {
      tools.add(Objects.requireNonNull(tool, "tool must not be null"));
      return this;
    }

    public Builder tools(List<ToolDefinition> tools) {
      Objects.requireNonNull(tools, "tools must not be null");
      this.tools = new ArrayList<>(tools.size());
      for (ToolDefinition tool : tools) {
        this.tools.add(Objects.requireNonNull(tool, "tool must not be null"));
      }
      return this;
    }

    public Builder defaultRunOptions(AgentRunOptions defaultRunOptions) {
      this.defaultRunOptions =
          Objects.requireNonNull(defaultRunOptions, "defaultRunOptions must not be null");
      return this;
    }

    public Builder attributes(ContextAttributes attributes) {
      this.attributes = Objects.requireNonNull(attributes, "attributes must not be null");
      return this;
    }

    public AgentDefinition build() {
      return new AgentDefinition(this);
    }
  }
}
