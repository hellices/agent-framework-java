package io.github.hellices.agentframework.api.message;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ToolCallContent extends Content {

  private final String callId;
  private final String name;
  private final Map<String, Object> arguments;

  public ToolCallContent(String callId, String name, Map<String, Object> arguments) {
    this(callId, name, arguments, Map.of(), null);
  }

  public ToolCallContent(
      String callId,
      String name,
      Map<String, Object> arguments,
      Map<String, Object> additionalProperties,
      Object rawRepresentation) {
    super(additionalProperties, rawRepresentation);
    this.callId = requireText(callId, "callId");
    this.name = requireText(name, "tool name");
    this.arguments =
        arguments == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
  }

  public String callId() {
    return callId;
  }

  public String name() {
    return name;
  }

  public Map<String, Object> arguments() {
    return Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
  }

  @Override
  public String type() {
    return "tool_call";
  }

  private static String requireText(String value, String label) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(label + " must not be blank");
    }
    return value;
  }
}
