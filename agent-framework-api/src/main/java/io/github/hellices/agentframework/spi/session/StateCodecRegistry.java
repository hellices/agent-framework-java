package io.github.hellices.agentframework.spi.session;

import io.github.hellices.agentframework.api.agent.AgentSession;
import io.github.hellices.agentframework.api.message.Content;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.MessageAttribution;
import io.github.hellices.agentframework.api.message.Role;
import io.github.hellices.agentframework.api.message.TextContent;
import io.github.hellices.agentframework.api.message.ToolApprovalRequestContent;
import io.github.hellices.agentframework.api.message.ToolApprovalResponseContent;
import io.github.hellices.agentframework.api.message.ToolCallContent;
import io.github.hellices.agentframework.api.message.ToolResultContent;
import io.github.hellices.agentframework.api.session.MessageHistory;
import io.github.hellices.agentframework.api.session.SessionSnapshot;
import io.github.hellices.agentframework.api.session.SessionState;
import io.github.hellices.agentframework.api.session.SessionStateEntry;
import io.github.hellices.agentframework.api.session.SessionStateKey;
import io.github.hellices.agentframework.api.value.JsonArray;
import io.github.hellices.agentframework.api.value.JsonNull;
import io.github.hellices.agentframework.api.value.JsonObject;
import io.github.hellices.agentframework.api.value.JsonValue;
import io.github.hellices.agentframework.api.value.JsonValues;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class StateCodecRegistry {

  private static final Field SESSION_STATE_ENTRIES_FIELD = sessionStateEntriesField();
  private static final Method SESSION_STATE_ENTRY_KEY_METHOD = sessionStateEntryMethod("key");
  private static final Method SESSION_STATE_ENTRY_VALUE_METHOD = sessionStateEntryMethod("value");

  private final Map<String, StateCodec<?>> byTypeId;
  private final Map<Class<?>, StateCodec<?>> byJavaType;

  private StateCodecRegistry(
      Map<String, StateCodec<?>> byTypeId, Map<Class<?>, StateCodec<?>> byJavaType) {
    this.byTypeId = Collections.unmodifiableMap(new LinkedHashMap<>(byTypeId));
    this.byJavaType = Collections.unmodifiableMap(new LinkedHashMap<>(byJavaType));
  }

  public static Builder builder() {
    return new Builder()
        .register(new JsonValueStateCodec())
        .register(new MessageStateCodec())
        .register(new MessageHistoryStateCodec());
  }

  public SessionSnapshot snapshot(AgentSession session, long revision, Instant createdAt) {
    Objects.requireNonNull(session, "session must not be null");
    Map<String, SessionStateEntry> entries = new LinkedHashMap<>();
    for (RawSessionStateEntry rawEntry : rawEntries(session.state())) {
      SessionStateKey<Object> key = rawEntry.key();
      StateCodec<Object> codec = codecForKey(key);
      entries.put(
          key.id(),
          new SessionStateEntry(
              codec.typeId(), codec.version(), codec.encode(key.type().cast(rawEntry.value()))));
    }
    return new SessionSnapshot(
        "session",
        "1.0",
        session.sessionId(),
        session.serviceSessionId().orElse(null),
        revision,
        createdAt,
        entries);
  }

  public AgentSession restore(SessionSnapshot snapshot) {
    Objects.requireNonNull(snapshot, "snapshot must not be null");
    if (!"session".equals(snapshot.type())) {
      throw new IllegalArgumentException("snapshot type must be session");
    }
    if (!"1.0".equals(snapshot.version())) {
      throw new IllegalArgumentException("snapshot version must be 1.0");
    }
    SessionState state = SessionState.empty();
    for (Map.Entry<String, SessionStateEntry> stateEntry : snapshot.state().entrySet()) {
      String key = stateEntry.getKey();
      SessionStateEntry entry = stateEntry.getValue();
      StateCodec<?> codec = byTypeId.get(entry.typeId());
      if (codec == null) {
        throw new IllegalArgumentException("unregistered session state type id: " + entry.typeId());
      }
      if (entry.codecVersion() != codec.version()) {
        throw new IllegalArgumentException(
            "unsupported codec version for " + entry.typeId() + ": " + entry.codecVersion());
      }
      try {
        state = restoreState(state, key, codec, entry.payload());
      } catch (SessionStateDecodingException failure) {
        throw failure;
      } catch (RuntimeException failure) {
        throw new SessionStateDecodingException(
            "failed to decode session state key " + key + " with type " + entry.typeId(), failure);
      }
    }
    AgentSession.Builder builder =
        AgentSession.builder().sessionId(snapshot.sessionId()).state(state);
    if (snapshot.serviceSessionId() != null) {
      builder.serviceSessionId(snapshot.serviceSessionId());
    }
    return builder.build();
  }

  /**
   * Resolves the codec for one session state value. Failures name the state key — the provider
   * namespace or source the value belongs to — because the value's own class is often a JDK
   * internal implementation type (an immutable list, for example) that says nothing about which
   * component wrote it.
   */
  @SuppressWarnings("unchecked")
  private StateCodec<Object> codecForKey(SessionStateKey<?> key) {
    StateCodec<?> exact = byJavaType.get(key.type());
    if (exact != null) {
      return (StateCodec<Object>) exact;
    }
    throw new IllegalArgumentException(
        "unregistered session state type for source '" + key.id() + "': " + key.type().getName());
  }

  @SuppressWarnings("unchecked")
  private static <T> SessionState restoreState(
      SessionState state, String key, StateCodec<?> codec, Object payload) {
    StateCodec<T> typedCodec = (StateCodec<T>) codec;
    T decoded = typedCodec.decode(payload);
    return state.with(SessionStateKey.of(key, typedCodec.javaType()), decoded);
  }

  @SuppressWarnings("unchecked")
  private static List<RawSessionStateEntry> rawEntries(SessionState state) {
    try {
      Map<String, ?> entries = (Map<String, ?>) SESSION_STATE_ENTRIES_FIELD.get(state);
      List<RawSessionStateEntry> rawEntries = new ArrayList<>(entries.size());
      for (Object entry : entries.values()) {
        rawEntries.add(
            new RawSessionStateEntry(
                (SessionStateKey<Object>) SESSION_STATE_ENTRY_KEY_METHOD.invoke(entry),
                SESSION_STATE_ENTRY_VALUE_METHOD.invoke(entry)));
      }
      return rawEntries;
    } catch (ReflectiveOperationException failure) {
      throw new IllegalStateException("failed to inspect session state entries", failure);
    }
  }

  private static Field sessionStateEntriesField() {
    try {
      Field field = SessionState.class.getDeclaredField("entries");
      field.setAccessible(true);
      return field;
    } catch (ReflectiveOperationException failure) {
      throw new ExceptionInInitializerError(failure);
    }
  }

  private static Method sessionStateEntryMethod(String name) {
    try {
      Class<?> entryType = Class.forName(SessionState.class.getName() + "$Entry");
      Method method = entryType.getDeclaredMethod(name);
      method.setAccessible(true);
      return method;
    } catch (ReflectiveOperationException failure) {
      throw new ExceptionInInitializerError(failure);
    }
  }

  private record RawSessionStateEntry(SessionStateKey<Object> key, Object value) {}

  public static final class Builder {
    private final Map<String, StateCodec<?>> byTypeId = new LinkedHashMap<>();
    private final Map<Class<?>, StateCodec<?>> byJavaType = new LinkedHashMap<>();

    private Builder() {}

    public Builder register(StateCodec<?> codec) {
      StateCodec<?> value = Objects.requireNonNull(codec, "codec must not be null");
      if (value.typeId() == null || value.typeId().isBlank()) {
        throw new IllegalArgumentException("state type id must not be blank");
      }
      if (value.version() < 1) {
        throw new IllegalArgumentException("codec version must be greater than 0");
      }
      Class<?> javaType =
          Objects.requireNonNull(value.javaType(), "codec Java type must not be null");
      if (byTypeId.containsKey(value.typeId())) {
        throw new IllegalArgumentException("duplicate state type id: " + value.typeId());
      }
      if (byJavaType.containsKey(javaType)) {
        throw new IllegalArgumentException("duplicate state Java type: " + javaType.getName());
      }
      byTypeId.put(value.typeId(), value);
      byJavaType.put(javaType, value);
      return this;
    }

    public StateCodecRegistry build() {
      return new StateCodecRegistry(byTypeId, byJavaType);
    }
  }

  /**
   * Encodes a whole conversation history as an array of the framework message payload, so the
   * durable form of a history is exactly the durable form of its messages and the two stay in step.
   *
   * <p>It is keyed on {@link MessageHistory} rather than on {@code List}, so the declared session
   * state key type resolves it unambiguously. A codec registered for {@code List} would not be used
   * for a {@code MessageHistory} slot because persistence follows the declared key type, not the
   * value's collection shape.
   */
  private static final class MessageHistoryStateCodec implements StateCodec<MessageHistory> {

    private final MessageStateCodec messageCodec = new MessageStateCodec();

    @Override
    public String typeId() {
      return "core.message_history";
    }

    @Override
    public int version() {
      return 1;
    }

    @Override
    public Class<MessageHistory> javaType() {
      return MessageHistory.class;
    }

    @Override
    public Object encode(MessageHistory value) {
      List<Object> encoded = new ArrayList<>(value.messages().size());
      for (Message message : value.messages()) {
        encoded.add(messageCodec.encode(message));
      }
      return encoded;
    }

    @Override
    public MessageHistory decode(Object payload) {
      if (!(payload instanceof List<?> entries)) {
        throw new IllegalArgumentException("message history payload must be an array");
      }
      List<Message> messages = new ArrayList<>(entries.size());
      for (Object entry : entries) {
        messages.add(messageCodec.decode(entry));
      }
      return new MessageHistory(messages);
    }
  }

  private static final class JsonValueStateCodec implements StateCodec<JsonValue> {

    @Override
    public String typeId() {
      return "core.json";
    }

    @Override
    public int version() {
      return 1;
    }

    @Override
    public Class<JsonValue> javaType() {
      return JsonValue.class;
    }

    @Override
    public Object encode(JsonValue value) {
      return JsonValues.toJava(value);
    }

    @Override
    public JsonValue decode(Object payload) {
      return decodeJsonValue(payload);
    }

    private static JsonValue decodeJsonValue(Object value) {
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
            throw new IllegalArgumentException("json value map keys must be strings");
          }
          builder.put(key, decodeJsonValue(entry.getValue()));
        }
        return builder.build();
      }
      if (value instanceof List<?> list) {
        List<JsonValue> items = new ArrayList<>();
        for (Object item : list) {
          items.add(decodeJsonValue(item));
        }
        return JsonArray.of(items);
      }
      return JsonValues.fromJava(value);
    }
  }

  private static final class MessageStateCodec implements StateCodec<Message> {

    @Override
    public String typeId() {
      return "core.message";
    }

    @Override
    public int version() {
      return 1;
    }

    @Override
    public Class<Message> javaType() {
      return Message.class;
    }

    @Override
    public Object encode(Message value) {
      Map<String, Object> encoded = new LinkedHashMap<>();
      encoded.put("role", value.role().value());
      encoded.put("content", value.content().stream().map(this::encodeContent).toList());
      if (value.attribution() != null) {
        encoded.put(
            "attribution",
            map(
                "sourceType",
                value.attribution().sourceType(),
                "sourceId",
                value.attribution().sourceId(),
                "originSessionId",
                value.attribution().originSessionId()));
      }
      encoded.put("additionalProperties", encodeJsonObject(value.additionalProperties()));
      return encoded;
    }

    @Override
    public Message decode(Object payload) {
      Map<?, ?> encoded = requireMap(payload, "message payload");
      List<Content> content = new ArrayList<>();
      for (Object item : requireList(encoded.get("content"), "message content")) {
        content.add(decodeContent(requireMap(item, "message content item")));
      }
      MessageAttribution attribution = null;
      Object encodedAttribution = encoded.get("attribution");
      if (encodedAttribution != null) {
        Map<?, ?> value = requireMap(encodedAttribution, "message attribution");
        attribution =
            new MessageAttribution(
                requireString(value, "sourceType"),
                nullableString(value, "sourceId"),
                nullableString(value, "originSessionId"));
      }
      return new Message(
          Role.of(requireString(encoded, "role")),
          content,
          attribution,
          castObject(encoded.get("additionalProperties"), "additional properties"),
          null);
    }

    private Object encodeContent(Content content) {
      Map<String, Object> encoded = new LinkedHashMap<>();
      encoded.put("type", content.type());
      encoded.put("additionalProperties", encodeJsonObject(content.additionalProperties()));
      if (content instanceof TextContent text) {
        encoded.put("text", text.value());
      } else if (content instanceof ToolCallContent call) {
        encoded.put("callId", call.callId());
        encoded.put("name", call.name());
        encoded.put("arguments", encodeJsonObject(call.arguments()));
      } else if (content instanceof ToolResultContent result) {
        encoded.put("callId", result.callId());
        encoded.put("name", result.name());
        encoded.put("content", result.content().stream().map(this::encodeContent).toList());
        encoded.put("error", result.error());
      } else if (content instanceof ToolApprovalRequestContent request) {
        encoded.put("requestId", request.requestId());
        encoded.put("toolCallId", request.toolCallId());
        encoded.put("toolName", request.toolName());
        encoded.put("arguments", encodeJsonObject(request.arguments()));
        request.hostBoundary().ifPresent(host -> encoded.put("hostBoundary", host));
      } else if (content instanceof ToolApprovalResponseContent response) {
        encoded.put("requestId", response.requestId());
        encoded.put("approved", response.approved());
      } else {
        throw new IllegalArgumentException(
            "unsupported framework message content type: "
                + content.getClass().getName()
                + ". Adapter-owned ExtensionContent needs a registered content codec before it can"
                + " be persisted.");
      }
      return encoded;
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

    private Content decodeContent(Map<?, ?> encoded) {
      String type = requireString(encoded, "type");
      JsonObject additionalProperties =
          castObject(encoded.get("additionalProperties"), "additional properties");
      return switch (type) {
        case "text" -> new TextContent(requireText(encoded, "text"), additionalProperties, null);
        case "tool_call" ->
            new ToolCallContent(
                requireString(encoded, "callId"),
                requireString(encoded, "name"),
                castObject(encoded.get("arguments"), "tool arguments"),
                additionalProperties,
                null);
        case "tool_result" -> {
          List<Content> nested = new ArrayList<>();
          for (Object item : nullableList(encoded.get("content"), "tool result content")) {
            nested.add(decodeContent(requireMap(item, "tool result content item")));
          }
          yield new ToolResultContent(
              requireString(encoded, "callId"),
              requireString(encoded, "name"),
              nested,
              requireBoolean(encoded, "error"),
              additionalProperties,
              null);
        }
        case "tool_approval_request" ->
            new ToolApprovalRequestContent(
                requireString(encoded, "requestId"),
                requireString(encoded, "toolCallId"),
                requireString(encoded, "toolName"),
                castObject(encoded.get("arguments"), "tool arguments"),
                nullableString(encoded, "hostBoundary"),
                additionalProperties,
                null);
        case "tool_approval_response" ->
            new ToolApprovalResponseContent(
                requireString(encoded, "requestId"),
                requireBoolean(encoded, "approved"),
                additionalProperties,
                null);
        default -> throw new IllegalArgumentException("unsupported message content type: " + type);
      };
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

    private static List<?> requireList(Object value, String label) {
      if (!(value instanceof List<?> list)) {
        throw new IllegalArgumentException(label + " must be an array");
      }
      return list;
    }

    private static List<?> nullableList(Object value, String label) {
      return value == null ? List.of() : requireList(value, label);
    }

    private static String requireString(Map<?, ?> value, String key) {
      Object item = value.get(key);
      if (!(item instanceof String text) || text.isBlank()) {
        throw new IllegalArgumentException(key + " must be non-blank text");
      }
      return text;
    }

    private static String requireText(Map<?, ?> value, String key) {
      Object item = value.get(key);
      if (!(item instanceof String text)) {
        throw new IllegalArgumentException(key + " must be text");
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

    private static boolean requireBoolean(Map<?, ?> value, String key) {
      Object item = value.get(key);
      if (!(item instanceof Boolean bool)) {
        throw new IllegalArgumentException(key + " must be boolean");
      }
      return bool;
    }

    private static Map<String, Object> map(Object... values) {
      Map<String, Object> result = new LinkedHashMap<>();
      for (int index = 0; index < values.length; index += 2) {
        result.put((String) values[index], values[index + 1]);
      }
      return result;
    }
  }
}
