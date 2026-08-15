package io.github.hellices.agentframework.api.tool;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record ToolArguments(Map<String, Object> values) {

  public ToolArguments {
    values = values == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(values));
  }

  public Object get(String name) {
    return values.get(name);
  }

  @Override
  public Map<String, Object> values() {
    return Collections.unmodifiableMap(new LinkedHashMap<>(values));
  }
}
