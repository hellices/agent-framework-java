package io.github.hellices.agentframework.engine.internal.tool;

import io.github.hellices.agentframework.api.message.ToolApprovalRequestContent;
import io.github.hellices.agentframework.api.value.JsonArray;
import io.github.hellices.agentframework.api.value.JsonNull;
import io.github.hellices.agentframework.api.value.JsonObject;
import io.github.hellices.agentframework.api.value.JsonValue;
import io.github.hellices.agentframework.api.value.JsonValues;
import io.github.hellices.agentframework.spi.session.StateCodec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The versioned {@link StateCodec} for {@link ToolApprovalQueueState}, registered explicitly with
 * the engine's default {@link io.github.hellices.agentframework.spi.session.StateCodecRegistry} so
 * the approval queue survives a session save/load round trip under its reserved state key without
 * relying on class names or native serialization.
 */
public final class ToolApprovalQueueStateCodec implements StateCodec<ToolApprovalQueueState> {

  @Override
  public String typeId() {
    return "engine.tool_approval_queue";
  }

  @Override
  public int version() {
    return 1;
  }

  @Override
  public Class<ToolApprovalQueueState> javaType() {
    return ToolApprovalQueueState.class;
  }

  @Override
  public Object encode(ToolApprovalQueueState value) {
    List<Object> encoded = new ArrayList<>(value.pending().size());
    for (ToolApprovalRequestContent request : value.pending()) {
      encoded.add(encodeRequest(request));
    }
    return encoded;
  }

  @Override
  public ToolApprovalQueueState decode(Object payload) {
    if (!(payload instanceof List<?> entries)) {
      throw new IllegalArgumentException("tool approval queue payload must be an array");
    }
    List<ToolApprovalRequestContent> pending = new ArrayList<>(entries.size());
    for (Object entry : entries) {
      pending.add(decodeRequest(requireMap(entry, "tool approval queue entry")));
    }
    return ToolApprovalQueueState.of(pending);
  }

  private static Map<String, Object> encodeRequest(ToolApprovalRequestContent request) {
    Map<String, Object> encoded = new LinkedHashMap<>();
    encoded.put("requestId", request.requestId());
    encoded.put("toolCallId", request.toolCallId());
    encoded.put("toolName", request.toolName());
    encoded.put("arguments", encodeJsonObject(request.arguments()));
    encoded.put("hostBoundary", request.hostBoundary().orElse(null));
    encoded.put("additionalProperties", encodeJsonObject(request.additionalProperties()));
    return encoded;
  }

  private static ToolApprovalRequestContent decodeRequest(Map<?, ?> encoded) {
    return new ToolApprovalRequestContent(
        requireString(encoded, "requestId"),
        requireString(encoded, "toolCallId"),
        requireString(encoded, "toolName"),
        castObject(encoded.get("arguments"), "tool approval arguments"),
        nullableString(encoded, "hostBoundary"),
        castObject(encoded.get("additionalProperties"), "additional properties"),
        null);
  }

  private static Object encodeJsonObject(JsonObject value) {
    Map<String, Object> encoded = new LinkedHashMap<>();
    value.values().forEach((name, item) -> encoded.put(name, encodeJsonValue(item)));
    return encoded;
  }

  private static Object encodeJsonValue(JsonValue value) {
    if (value instanceof JsonObject jsonObject) {
      return encodeJsonObject(jsonObject);
    }
    if (value instanceof JsonArray jsonArray) {
      List<Object> encoded = new ArrayList<>();
      for (JsonValue item : jsonArray.values()) {
        encoded.add(encodeJsonValue(item));
      }
      return encoded;
    }
    return JsonValues.toJava(value);
  }

  private static JsonObject castObject(Object value, String label) {
    if (value == null) {
      return JsonObject.empty();
    }
    if (value instanceof JsonObject jsonObject) {
      return jsonObject;
    }
    JsonValue jsonValue = decodeJsonValue(value, label);
    if (jsonValue instanceof JsonObject jsonObject) {
      return jsonObject;
    }
    throw new IllegalArgumentException(label + " must be an object");
  }

  private static JsonValue decodeJsonValue(Object value, String label) {
    if (value == null) {
      return JsonNull.instance();
    }
    if (value instanceof JsonValue jsonValue) {
      return jsonValue;
    }
    if (value instanceof Map<?, ?> map) {
      JsonObject.Builder builder = JsonObject.builder();
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        if (!(entry.getKey() instanceof String key)) {
          throw new IllegalArgumentException(label + " map keys must be strings");
        }
        builder.put(key, decodeJsonValue(entry.getValue(), label + " entry"));
      }
      return builder.build();
    }
    if (value instanceof List<?> list) {
      List<JsonValue> items = new ArrayList<>();
      for (Object item : list) {
        items.add(decodeJsonValue(item, label + " item"));
      }
      return JsonArray.of(items);
    }
    return JsonValues.fromJava(value);
  }

  private static Map<?, ?> requireMap(Object value, String label) {
    if (!(value instanceof Map<?, ?> map)) {
      throw new IllegalArgumentException(label + " must be an object");
    }
    return map;
  }

  private static String requireString(Map<?, ?> value, String key) {
    Object item = value.get(key);
    if (!(item instanceof String text) || text.isBlank()) {
      throw new IllegalArgumentException(key + " must be non-blank text");
    }
    return text;
  }

  private static String nullableString(Map<?, ?> value, String key) {
    Object item = value.get(key);
    if (item == null) {
      return null;
    }
    if (!(item instanceof String text)) {
      throw new IllegalArgumentException(key + " must be text or null");
    }
    return text;
  }
}
