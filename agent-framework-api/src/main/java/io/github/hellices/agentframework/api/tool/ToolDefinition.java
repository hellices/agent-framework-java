package io.github.hellices.agentframework.api.tool;

import io.github.hellices.agentframework.api.value.JsonObject;
import java.util.Objects;

public final class ToolDefinition {

  private static final JsonObject EMPTY_SCHEMA = JsonObject.builder().build();

  private final String name;
  private final String description;
  private final JsonObject inputSchema;

  private ToolDefinition(Builder builder) {
    String normalizedName = builder.name;
    if (normalizedName == null || normalizedName.isBlank()) {
      throw new IllegalArgumentException("tool name must not be blank");
    }
    this.name = normalizedName;
    this.description = builder.description == null ? "" : builder.description;
    this.inputSchema = builder.inputSchema == null ? EMPTY_SCHEMA : builder.inputSchema;
  }

  public static Builder builder() {
    return new Builder();
  }

  public String name() {
    return name;
  }

  public String description() {
    return description;
  }

  public JsonObject inputSchema() {
    return inputSchema;
  }

  public Builder toBuilder() {
    return new Builder().name(name).description(description).inputSchema(inputSchema);
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof ToolDefinition that)) {
      return false;
    }
    return name.equals(that.name)
        && description.equals(that.description)
        && inputSchema.equals(that.inputSchema);
  }

  @Override
  public int hashCode() {
    return Objects.hash(name, description, inputSchema);
  }

  @Override
  public String toString() {
    return "ToolDefinition[name="
        + name
        + ", description="
        + description
        + ", inputSchema="
        + inputSchema
        + "]";
  }

  public static final class Builder {
    private String name;
    private String description = "";
    private JsonObject inputSchema = EMPTY_SCHEMA;

    private Builder() {}

    public Builder name(String name) {
      this.name = name;
      return this;
    }

    public Builder description(String description) {
      this.description = description == null ? "" : description;
      return this;
    }

    public Builder inputSchema(JsonObject inputSchema) {
      this.inputSchema = inputSchema == null ? EMPTY_SCHEMA : inputSchema;
      return this;
    }

    public ToolDefinition build() {
      return new ToolDefinition(this);
    }
  }
}
