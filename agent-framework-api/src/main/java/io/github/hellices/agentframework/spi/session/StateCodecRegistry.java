package io.github.hellices.agentframework.spi.session;

import io.github.hellices.agentframework.api.agent.AgentSession;
import io.github.hellices.agentframework.api.message.Content;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.MessageAttribution;
import io.github.hellices.agentframework.api.message.Role;
import io.github.hellices.agentframework.api.message.TextContent;
import io.github.hellices.agentframework.api.message.ToolCallContent;
import io.github.hellices.agentframework.api.message.ToolResultContent;
import io.github.hellices.agentframework.api.session.MessageHistory;
import io.github.hellices.agentframework.api.session.SessionSnapshot;
import io.github.hellices.agentframework.api.session.SessionStateEntry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class StateCodecRegistry {

  private final Map<String, StateCodec<?>> byTypeId;
  private final Map<Class<?>, StateCodec<?>> byJavaType;

  private StateCodecRegistry(
      Map<String, StateCodec<?>> byTypeId, Map<Class<?>, StateCodec<?>> byJavaType) {
    this.byTypeId = Collections.unmodifiableMap(new LinkedHashMap<>(byTypeId));
    this.byJavaType = Collections.unmodifiableMap(new LinkedHashMap<>(byJavaType));
  }

  public static Builder builder() {
    return new Builder().register(new MessageStateCodec()).register(new MessageHistoryStateCodec());
  }

  public SessionSnapshot snapshot(AgentSession session, long revision, Instant createdAt) {
    Objects.requireNonNull(session, "session must not be null");
    Map<String, SessionStateEntry> entries = new LinkedHashMap<>();
    session
        .state()
        .forEach(
            (key, value) -> {
              StateCodec<Object> codec = codecForValue(key, value);
              entries.put(
                  key, new SessionStateEntry(codec.typeId(), codec.version(), codec.encode(value)));
            });
    return new SessionSnapshot(
        "session",
        "1.0",
        session.sessionId(),
        session.serviceSessionId(),
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
    Map<String, Object> state = new LinkedHashMap<>();
    snapshot
        .state()
        .forEach(
            (key, entry) -> {
              StateCodec<?> codec = byTypeId.get(entry.typeId());
              if (codec == null) {
                throw new IllegalArgumentException(
                    "unregistered session state type id: " + entry.typeId());
              }
              if (entry.codecVersion() != codec.version()) {
                throw new IllegalArgumentException(
                    "unsupported codec version for "
                        + entry.typeId()
                        + ": "
                        + entry.codecVersion());
              }
              try {
                state.put(key, codec.decode(entry.payload()));
              } catch (SessionStateDecodingException failure) {
                throw failure;
              } catch (RuntimeException failure) {
                throw new SessionStateDecodingException(
                    "failed to decode session state key " + key + " with type " + entry.typeId(),
                    failure);
              }
            });
    return new AgentSession(snapshot.sessionId(), snapshot.serviceSessionId(), state);
  }

  /**
   * Resolves the codec for one session state value. Failures name the state key — the provider
   * namespace or source the value belongs to — because the value's own class is often a JDK
   * internal implementation type (an immutable list, for example) that says nothing about which
   * component wrote it.
   */
  @SuppressWarnings("unchecked")
  private StateCodec<Object> codecForValue(String key, Object value) {
    Objects.requireNonNull(value, "session state values must not be null");
    StateCodec<?> exact = byJavaType.get(value.getClass());
    if (exact != null) {
      return (StateCodec<Object>) exact;
    }
    StateCodec<?> assignable = null;
    for (StateCodec<?> codec : byJavaType.values()) {
      if (codec.javaType().isInstance(value)) {
        if (assignable != null) {
          throw new IllegalArgumentException(
              "ambiguous session state codecs for source '"
                  + key
                  + "': "
                  + value.getClass().getName());
        }
        assignable = codec;
      }
    }
    if (assignable != null) {
      return (StateCodec<Object>) assignable;
    }
    throw new IllegalArgumentException(
        "unregistered session state type for source '" + key + "': " + value.getClass().getName());
  }

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
   * <p>It is keyed on {@link MessageHistory} rather than on {@code List}, so the exact-class lookup
   * resolves it with no ambiguity: a codec registered for {@code List} would claim every
   * list-shaped state value in every namespace and would make the assignable-codec fallback
   * ambiguous as soon as a second collection-shaped state type were registered.
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
      encoded.put("additionalProperties", value.additionalProperties());
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
          castMap(encoded.get("additionalProperties")),
          null);
    }

    private Object encodeContent(Content content) {
      Map<String, Object> encoded = new LinkedHashMap<>();
      encoded.put("type", content.type());
      encoded.put("additionalProperties", content.additionalProperties());
      if (content instanceof TextContent text) {
        encoded.put("text", text.value());
      } else if (content instanceof ToolCallContent call) {
        encoded.put("callId", call.callId());
        encoded.put("name", call.name());
        encoded.put("arguments", call.arguments());
      } else if (content instanceof ToolResultContent result) {
        encoded.put("callId", result.callId());
        encoded.put("name", result.name());
        encoded.put("content", result.content().stream().map(this::encodeContent).toList());
        encoded.put("error", result.error());
      } else {
        throw new IllegalArgumentException(
            "unsupported framework message content type: "
                + content.getClass().getName()
                + ". Adapter-owned ExtensionContent needs a registered content codec before it can"
                + " be persisted.");
      }
      return encoded;
    }

    private Content decodeContent(Map<?, ?> encoded) {
      String type = requireString(encoded, "type");
      Map<String, Object> additionalProperties = castMap(encoded.get("additionalProperties"));
      return switch (type) {
        case "text" -> new TextContent(requireText(encoded, "text"), additionalProperties, null);
        case "tool_call" ->
            new ToolCallContent(
                requireString(encoded, "callId"),
                requireString(encoded, "name"),
                castNullableMap(encoded.get("arguments"), "tool arguments"),
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
        default -> throw new IllegalArgumentException("unsupported message content type: " + type);
      };
    }

    private static Map<String, Object> castMap(Object value) {
      if (value == null) {
        return Map.of();
      }
      Map<?, ?> source = requireMap(value, "additional properties");
      Map<String, Object> copy = new LinkedHashMap<>();
      source.forEach(
          (key, item) -> {
            if (!(key instanceof String text)) {
              throw new IllegalArgumentException("additional property keys must be strings");
            }
            if (item == null) {
              throw new IllegalArgumentException("additional property values must not be null");
            }
            copy.put(text, item);
          });
      return Map.copyOf(copy);
    }

    private static Map<String, Object> castNullableMap(Object value, String label) {
      if (value == null) {
        return Map.of();
      }
      Map<?, ?> source = requireMap(value, label);
      Map<String, Object> copy = new LinkedHashMap<>();
      source.forEach(
          (key, item) -> {
            if (!(key instanceof String text)) {
              throw new IllegalArgumentException(label + " keys must be strings");
            }
            copy.put(text, item);
          });
      return java.util.Collections.unmodifiableMap(copy);
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
