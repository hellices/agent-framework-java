package io.github.hellices.agentframework.engine.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hellices.agentframework.api.session.SessionSnapshot;
import io.github.hellices.agentframework.api.session.SessionStateEntry;
import io.github.hellices.agentframework.spi.session.SessionSnapshotCodec;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class JacksonSessionSnapshotCodec implements SessionSnapshotCodec {

  private static final int MAX_SNAPSHOT_BYTES = 1_048_576;
  private static final int MAX_DECIMAL_TEXT_LENGTH = 1_024;
  private static final int MAX_DECIMAL_SCALE = 10_000;

  private final ObjectMapper objectMapper;

  public JacksonSessionSnapshotCodec() {
    this(new ObjectMapper());
  }

  JacksonSessionSnapshotCodec(ObjectMapper objectMapper) {
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
  }

  @Override
  public byte[] encode(SessionSnapshot snapshot) {
    SessionSnapshot value = Objects.requireNonNull(snapshot, "snapshot must not be null");
    if (!"session".equals(value.type())) {
      throw new SessionSnapshotSchemaException("snapshot type must be session");
    }
    if (!"1.0".equals(value.version())) {
      throw new SessionSnapshotSchemaException("snapshot version must be 1.0");
    }
    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("type", value.type());
    envelope.put("version", value.version());
    envelope.put("sessionId", value.sessionId());
    envelope.put("serviceSessionId", value.serviceSessionId());
    envelope.put("revision", value.revision());
    envelope.put("createdAt", value.createdAt().toString());
    Map<String, Object> state = new LinkedHashMap<>();
    value
        .state()
        .forEach(
            (key, entry) -> {
              Map<String, Object> encodedEntry = new LinkedHashMap<>();
              encodedEntry.put("typeId", entry.typeId());
              encodedEntry.put("codecVersion", entry.codecVersion());
              encodedEntry.put("payload", wireValue(entry.payload()));
              state.put(key, encodedEntry);
            });
    envelope.put("state", state);
    try {
      byte[] encoded = objectMapper.writeValueAsBytes(envelope);
      if (encoded.length > MAX_SNAPSHOT_BYTES) {
        throw new SessionSnapshotSchemaException("session snapshot exceeds the 1 MiB limit");
      }
      return encoded;
    } catch (IOException failure) {
      throw new SessionSnapshotParseException("failed to encode session snapshot", failure);
    }
  }

  @Override
  public SessionSnapshot decode(byte[] encoded) {
    Objects.requireNonNull(encoded, "encoded must not be null");
    if (encoded.length > MAX_SNAPSHOT_BYTES) {
      throw new SessionSnapshotSchemaException("session snapshot exceeds the 1 MiB limit");
    }
    JsonNode root;
    try {
      root = objectMapper.readTree(encoded);
    } catch (IOException failure) {
      throw new SessionSnapshotParseException("failed to parse session snapshot", failure);
    }
    if (!root.isObject()) {
      throw new SessionSnapshotSchemaException("snapshot must be a JSON object");
    }
    if (!"session".equals(text(root, "type"))) {
      throw new SessionSnapshotSchemaException("snapshot type must be session");
    }
    if (!"1.0".equals(text(root, "version"))) {
      throw new SessionSnapshotSchemaException("snapshot version must be 1.0");
    }
    JsonNode stateNode = root.path("state");
    if (!stateNode.isObject()) {
      throw new SessionSnapshotSchemaException("snapshot state must be an object");
    }
    try {
      Map<String, SessionStateEntry> state = new LinkedHashMap<>();
      stateNode
          .properties()
          .forEach(
              entry -> {
                JsonNode value = entry.getValue();
                if (!value.isObject()) {
                  throw new SessionSnapshotSchemaException(
                      "snapshot state entry must be an object: " + entry.getKey());
                }
                JsonNode codecVersion = value.get("codecVersion");
                if (codecVersion == null
                    || !codecVersion.isIntegralNumber()
                    || !codecVersion.canConvertToInt()
                    || codecVersion.intValue() < 1) {
                  throw new SessionSnapshotSchemaException(
                      "snapshot codecVersion must be a positive integer: " + entry.getKey());
                }
                if (!value.has("payload")) {
                  throw new SessionSnapshotSchemaException(
                      "snapshot state entry must contain payload: " + entry.getKey());
                }
                state.put(
                    entry.getKey(),
                    new SessionStateEntry(
                        text(value, "typeId"),
                        codecVersion.intValue(),
                        logicalValue(value.get("payload"))));
              });
      return new SessionSnapshot(
          "session",
          "1.0",
          text(root, "sessionId"),
          root.path("serviceSessionId").isNull()
              ? null
              : root.path("serviceSessionId").asText(null),
          revision(root),
          Instant.parse(text(root, "createdAt")),
          state);
    } catch (SessionSnapshotSchemaException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw new SessionSnapshotSchemaException("invalid session snapshot: " + failure.getMessage());
    }
  }

  private static long revision(JsonNode root) {
    JsonNode value = root.get("revision");
    if (value == null
        || !value.isIntegralNumber()
        || !value.canConvertToLong()
        || value.longValue() < 0) {
      throw new SessionSnapshotSchemaException("snapshot revision must be a non-negative integer");
    }
    return value.longValue();
  }

  private static Object wireValue(Object value) {
    Map<String, Object> encoded = new LinkedHashMap<>();
    if (value == null) {
      encoded.put("kind", "null");
    } else if (value instanceof String text) {
      encoded.put("kind", "string");
      encoded.put("value", text);
    } else if (value instanceof Boolean bool) {
      encoded.put("kind", "boolean");
      encoded.put("value", bool);
    } else if (value instanceof Long number) {
      encoded.put("kind", "integer");
      encoded.put("value", number.toString());
    } else if (value instanceof java.math.BigDecimal decimal) {
      encoded.put("kind", "decimal");
      encoded.put("value", decimalText(decimal));
    } else if (value instanceof List<?> list) {
      encoded.put("kind", "array");
      encoded.put("value", list.stream().map(JacksonSessionSnapshotCodec::wireValue).toList());
    } else if (value instanceof Map<?, ?> map) {
      Map<String, Object> object = new java.util.TreeMap<>();
      map.forEach(
          (key, item) ->
              object.put(
                  Objects.requireNonNull((String) key, "payload key must not be null"),
                  wireValue(item)));
      encoded.put("kind", "object");
      encoded.put("value", object);
    } else {
      throw new SessionSnapshotSchemaException(
          "snapshot payload contains unsupported value: " + value.getClass().getName());
    }
    return encoded;
  }

  private static Object logicalValue(JsonNode node) {
    if (node == null || !node.isObject()) {
      throw new SessionSnapshotSchemaException("snapshot payload value must be typed");
    }
    String kind = text(node, "kind");
    JsonNode value = node.get("value");
    return switch (kind) {
      case "null" -> null;
      case "string" -> requiredText(value, "string payload");
      case "boolean" -> requiredBoolean(value, "boolean payload");
      case "integer" -> parseLong(requiredText(value, "integer payload"));
      case "decimal" -> parseDecimal(requiredText(value, "decimal payload"));
      case "array" -> decodeArray(value);
      case "object" -> decodeObject(value);
      default -> throw new SessionSnapshotSchemaException("unknown snapshot payload kind: " + kind);
    };
  }

  private static Object decodeArray(JsonNode node) {
    if (node == null || !node.isArray()) {
      throw new SessionSnapshotSchemaException("array payload must contain an array");
    }
    List<Object> values = new ArrayList<>();
    node.forEach(item -> values.add(logicalValue(item)));
    return java.util.Collections.unmodifiableList(values);
  }

  private static Object decodeObject(JsonNode node) {
    if (node == null || !node.isObject()) {
      throw new SessionSnapshotSchemaException("object payload must contain an object");
    }
    Map<String, Object> values = new java.util.TreeMap<>();
    node.properties().forEach(entry -> values.put(entry.getKey(), logicalValue(entry.getValue())));
    return java.util.Collections.unmodifiableMap(values);
  }

  private static String requiredText(JsonNode node, String label) {
    if (node == null || !node.isTextual()) {
      throw new SessionSnapshotSchemaException(label + " must be text");
    }
    return node.textValue();
  }

  private static boolean requiredBoolean(JsonNode node, String label) {
    if (node == null || !node.isBoolean()) {
      throw new SessionSnapshotSchemaException(label + " must be boolean");
    }
    return node.booleanValue();
  }

  private static long parseLong(String value) {
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException failure) {
      throw new SessionSnapshotSchemaException("invalid integer payload: " + value);
    }
  }

  private static java.math.BigDecimal parseDecimal(String value) {
    if (value.length() > MAX_DECIMAL_TEXT_LENGTH) {
      throw new SessionSnapshotSchemaException("decimal payload exceeds the length limit");
    }
    try {
      java.math.BigDecimal decimal = new java.math.BigDecimal(value);
      if (Math.abs((long) decimal.scale()) > MAX_DECIMAL_SCALE) {
        throw new SessionSnapshotSchemaException("decimal payload exceeds the scale limit");
      }
      return decimal.stripTrailingZeros();
    } catch (NumberFormatException failure) {
      throw new SessionSnapshotSchemaException("invalid decimal payload: " + value);
    }
  }

  private static String decimalText(java.math.BigDecimal value) {
    String encoded = value.toString();
    if (encoded.length() > MAX_DECIMAL_TEXT_LENGTH) {
      throw new SessionSnapshotSchemaException("decimal payload exceeds the length limit");
    }
    if (Math.abs((long) value.scale()) > MAX_DECIMAL_SCALE) {
      throw new SessionSnapshotSchemaException("decimal payload exceeds the scale limit");
    }
    return encoded;
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual() || value.asText().isBlank()) {
      throw new SessionSnapshotSchemaException("snapshot field must be text: " + field);
    }
    return value.asText();
  }
}
