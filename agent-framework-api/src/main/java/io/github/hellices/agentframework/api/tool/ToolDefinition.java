package io.github.hellices.agentframework.api.tool;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record ToolDefinition(String name, String description, Map<String, Object> inputSchema) {

  public ToolDefinition {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("tool name must not be blank");
    }
    description = description == null ? "" : description;
    inputSchema =
        inputSchema == null
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(inputSchema));
  }

  @Override
  public Map<String, Object> inputSchema() {
    return Collections.unmodifiableMap(new LinkedHashMap<>(inputSchema));
  }
}
