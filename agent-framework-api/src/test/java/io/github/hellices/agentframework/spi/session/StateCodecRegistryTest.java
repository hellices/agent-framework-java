package io.github.hellices.agentframework.spi.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hellices.agentframework.api.agent.AgentSession;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.MessageAttribution;
import io.github.hellices.agentframework.api.message.Role;
import io.github.hellices.agentframework.api.message.TextContent;
import io.github.hellices.agentframework.api.message.ToolCallContent;
import io.github.hellices.agentframework.api.session.MessageHistory;
import io.github.hellices.agentframework.api.session.SessionSnapshot;
import io.github.hellices.agentframework.api.session.SessionState;
import io.github.hellices.agentframework.api.session.SessionStateEntry;
import io.github.hellices.agentframework.api.session.SessionStateKey;
import io.github.hellices.agentframework.api.session.SessionStateValues;
import io.github.hellices.agentframework.api.value.JsonValue;
import io.github.hellices.agentframework.api.value.JsonValues;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class StateCodecRegistryTest {

  @Test
  void customStateRoundTripsThroughAStableTypeId() {
    StateCodecRegistry registry =
        StateCodecRegistry.builder().register(new PreferenceCodec()).build();
    AgentSession session =
        session("session-1", "service-1", Map.of("preferences", new Preference("dark")));

    SessionSnapshot snapshot = registry.snapshot(session, 3, Instant.parse("2026-08-15T00:00:00Z"));
    AgentSession restored = registry.restore(snapshot);

    assertThat(snapshot.type()).isEqualTo("session");
    assertThat(snapshot.version()).isEqualTo("1.0");
    assertThat(snapshot.state().get("preferences").typeId()).isEqualTo("test.preference");
    assertThat(restored.state().get(SessionStateKey.of("preferences", Preference.class)))
        .contains(new Preference("dark"));
  }

  @Test
  void frameworkMessageCodecIsRegisteredByDefault() {
    StateCodecRegistry registry = StateCodecRegistry.builder().build();
    Message message =
        new Message(
            Role.USER,
            List.of(new TextContent("hello")),
            new MessageAttribution("memory", "source-1", "origin-1"),
            Map.of("retries", 1, "ratio", 0.5),
            new Object());
    SessionSnapshot snapshot =
        registry.snapshot(session("session-1", null, Map.of("message", message)), 0, Instant.EPOCH);

    AgentSession restored = registry.restore(snapshot);

    assertThat(restored.state().get(SessionStateKey.of("message", Message.class)))
        .hasValueSatisfying(
            value -> {
              assertThat(value.role()).isEqualTo(Role.USER);
              assertThat(value.text()).isEqualTo("hello");
              assertThat(value.attribution())
                  .isEqualTo(new MessageAttribution("memory", "source-1", "origin-1"));
              assertThat(value.additionalProperties())
                  .containsEntry("retries", 1L)
                  .containsEntry("ratio", new BigDecimal("0.5"));
              assertThat(value.rawRepresentation()).isNull();
            });
  }

  @Test
  void frameworkMessageCodecPreservesBlankTextAndNullToolArgumentValues() {
    StateCodecRegistry registry = StateCodecRegistry.builder().build();
    Map<String, Object> arguments = new LinkedHashMap<>();
    arguments.put("query", "x");
    arguments.put("filter", null);
    Message message =
        new Message(
            Role.ASSISTANT,
            List.of(new TextContent(""), new ToolCallContent("call-1", "search", arguments)));

    AgentSession restored =
        registry.restore(
            registry.snapshot(
                session("session-1", null, Map.of("message", message)), 0, Instant.EPOCH));

    assertThat(restored.state().get(SessionStateKey.of("message", Message.class)))
        .hasValueSatisfying(
            value -> {
              assertThat(((TextContent) value.content().get(0)).value()).isEmpty();
              assertThat(((ToolCallContent) value.content().get(1)).arguments())
                  .containsEntry("filter", null);
            });
  }

  @Test
  void frameworkMessageHistoryCodecIsRegisteredByDefault() {
    StateCodecRegistry registry = StateCodecRegistry.builder().build();
    MessageHistory history =
        MessageHistory.of(
            List.of(
                new Message(
                    Role.USER,
                    List.of(new TextContent("hello")),
                    new MessageAttribution("memory", "source-1", "origin-1"),
                    Map.of("retries", 1),
                    new Object()),
                new Message(Role.ASSISTANT, List.of(new TextContent("hi")))));
    SessionSnapshot snapshot =
        registry.snapshot(
            session("session-1", null, Map.of("in_memory", history)), 0, Instant.EPOCH);

    AgentSession restored = registry.restore(snapshot);

    assertThat(snapshot.state().get("in_memory").typeId()).isEqualTo("core.message_history");
    assertThat(snapshot.state().get("in_memory").codecVersion()).isEqualTo(1);
    assertThat(restored.state().get(SessionStateKey.of("in_memory", MessageHistory.class)))
        .hasValueSatisfying(
            value -> {
              assertThat(value.messages()).extracting(Message::text).containsExactly("hello", "hi");
              assertThat(value.messages().get(0).attribution())
                  .isEqualTo(new MessageAttribution("memory", "source-1", "origin-1"));
              assertThat(value.messages().get(0).additionalProperties())
                  .containsEntry("retries", 1L);
              assertThat(value.messages().get(0).rawRepresentation()).isNull();
            });
  }

  @Test
  void anEmptyMessageHistoryRoundTripsWithoutBeingConfusedForAList() {
    StateCodecRegistry registry = StateCodecRegistry.builder().build();

    AgentSession restored =
        registry.restore(
            registry.snapshot(
                session("session-1", null, Map.of("in_memory", MessageHistory.empty())),
                0,
                Instant.EPOCH));

    assertThat(restored.state().get(SessionStateKey.of("in_memory", MessageHistory.class)))
        .contains(MessageHistory.empty());
  }

  @Test
  void aPlainListOfMessagesIsStillAnUnregisteredStateType() {
    StateCodecRegistry registry = StateCodecRegistry.builder().build();
    Map<String, Object> state =
        Map.of("in_memory", List.of(new Message(Role.USER, List.of(new TextContent("hello")))));
    AgentSession session = session("session-1", null, state);

    assertThatThrownBy(() -> registry.snapshot(session, 0, Instant.EPOCH))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageStartingWith("unregistered session state type for source 'in_memory'");
  }

  @Test
  void corruptBuiltInMessageHistoryPayloadHasAClassifiedFailure() {
    StateCodecRegistry registry = StateCodecRegistry.builder().build();
    SessionSnapshot snapshot =
        new SessionSnapshot(
            "session",
            "1.0",
            "session-1",
            null,
            0,
            Instant.EPOCH,
            Map.of("in_memory", new SessionStateEntry("core.message_history", 1, "not-a-list")));

    assertThatThrownBy(() -> registry.restore(snapshot))
        .isInstanceOf(SessionStateDecodingException.class)
        .hasMessage("failed to decode session state key in_memory with type core.message_history");
  }

  @Test
  void duplicateTypeIdsAndJavaTypesAreRejected() {
    StateCodecRegistry.Builder builder =
        StateCodecRegistry.builder().register(new PreferenceCodec());

    assertThatThrownBy(() -> builder.register(new DuplicateIdCodec()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("duplicate state type id: test.preference");
    assertThatThrownBy(() -> builder.register(new DuplicateJavaTypeCodec()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("duplicate state Java type: " + Preference.class.getName());
  }

  @Test
  void snapshotAndRestorePreserveTheDeclaredStateKeyType() {
    SessionStateKey<Marker> key = SessionStateKey.of("memory", Marker.class);
    StateCodecRegistry registry =
        StateCodecRegistry.builder()
            .register(new MarkerCodec())
            .register(new MarkerValueCodec())
            .build();
    AgentSession session =
        AgentSession.builder()
            .sessionId("session-1")
            .state(SessionState.empty().with(key, new MarkerValue("dark")))
            .build();

    AgentSession restored = registry.restore(registry.snapshot(session, 0, Instant.EPOCH));

    assertThat(restored.state().get(key)).contains(new MarkerValue("dark"));
  }

  @Test
  void unregisteredAndNonJsonSafeStateFailBeforeSnapshotCreation() {
    StateCodecRegistry registry = StateCodecRegistry.builder().build();

    assertThatThrownBy(
            () ->
                registry.snapshot(
                    session("session-1", null, Map.of("unknown", new Object())), 0, Instant.EPOCH))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("unregistered session state type for source 'unknown': java.lang.Object");

    StateCodecRegistry invalidRegistry =
        StateCodecRegistry.builder().register(new NonFiniteCodec()).build();
    assertThatThrownBy(
            () ->
                invalidRegistry.snapshot(
                    session("session-1", null, Map.of("invalid", new NonFiniteValue())),
                    0,
                    Instant.EPOCH))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("state payload must contain only finite numbers");

    assertThatThrownBy(
            () ->
                new SessionStateEntry("test.mutable-number", 1, Map.of("value", new AtomicLong(1))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported number type");
  }

  @Test
  void snapshotRequiresACodecForTheDeclaredStateKeyType() {
    StateCodecRegistry registry =
        StateCodecRegistry.builder()
            .register(new FirstMarkerCodec())
            .register(new SecondMarkerCodec())
            .build();

    assertThatThrownBy(
            () ->
                registry.snapshot(
                    session("session-1", null, Map.of("value", new BothMarkers())),
                    0,
                    Instant.EPOCH))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unregistered session state type for source 'value'");
  }

  @Test
  void corruptBuiltInMessagePayloadHasAClassifiedFailure() {
    StateCodecRegistry registry = StateCodecRegistry.builder().build();
    SessionSnapshot snapshot =
        new SessionSnapshot(
            "session",
            "1.0",
            "session-1",
            null,
            0,
            Instant.EPOCH,
            Map.of("message", new SessionStateEntry("core.message", 1, Map.of())));

    assertThatThrownBy(() -> registry.restore(snapshot))
        .isInstanceOf(SessionStateDecodingException.class)
        .hasMessageContaining("message")
        .hasMessageContaining("core.message");
  }

  @Test
  void restoreRejectsAnIncompatibleEnvelopeBeforeStateDecoding() {
    StateCodecRegistry registry = StateCodecRegistry.builder().build();
    SessionSnapshot incompatible =
        new SessionSnapshot("other", "2.0", "session-1", null, 0, Instant.EPOCH, Map.of());

    assertThatThrownBy(() -> registry.restore(incompatible))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("snapshot type must be session");
  }

  private static AgentSession session(
      String sessionId, String serviceSessionId, Map<String, ?> state) {
    AgentSession.Builder builder =
        AgentSession.builder().sessionId(sessionId).state(sessionState(state));
    if (serviceSessionId != null) {
      builder.serviceSessionId(serviceSessionId);
    }
    return builder.build();
  }

  private static SessionState sessionState(Map<String, ?> state) {
    SessionState sessionState = SessionState.empty();
    for (Map.Entry<String, ?> entry : state.entrySet()) {
      sessionState = put(sessionState, entry.getKey(), entry.getValue());
    }
    return sessionState;
  }

  @SuppressWarnings("unchecked")
  private static SessionState put(SessionState state, String key, Object value) {
    if (SessionStateValues.isJsonValueShape(value)) {
      return state.with(SessionStateKey.of(key, JsonValue.class), JsonValues.fromJava(value));
    }
    return state.with((SessionStateKey<Object>) SessionStateKey.of(key, value.getClass()), value);
  }

  private record Preference(String theme) {}

  private record NonFiniteValue() {}

  private interface FirstMarker {}

  private interface SecondMarker {}

  private interface Marker {}

  private static final class BothMarkers implements FirstMarker, SecondMarker {}

  private record MarkerValue(String value) implements Marker {}

  private static final class PreferenceCodec implements StateCodec<Preference> {
    @Override
    public String typeId() {
      return "test.preference";
    }

    @Override
    public int version() {
      return 1;
    }

    @Override
    public Class<Preference> javaType() {
      return Preference.class;
    }

    @Override
    public Object encode(Preference value) {
      return Map.of("theme", value.theme());
    }

    @Override
    public Preference decode(Object payload) {
      return new Preference((String) ((Map<?, ?>) payload).get("theme"));
    }
  }

  private static final class DuplicateIdCodec implements StateCodec<String> {
    @Override
    public String typeId() {
      return "test.preference";
    }

    @Override
    public int version() {
      return 1;
    }

    @Override
    public Class<String> javaType() {
      return String.class;
    }

    @Override
    public Object encode(String value) {
      return value;
    }

    @Override
    public String decode(Object payload) {
      return (String) payload;
    }
  }

  private static final class DuplicateJavaTypeCodec implements StateCodec<Preference> {
    @Override
    public String typeId() {
      return "test.other-preference";
    }

    @Override
    public int version() {
      return 1;
    }

    @Override
    public Class<Preference> javaType() {
      return Preference.class;
    }

    @Override
    public Object encode(Preference value) {
      return Map.of();
    }

    @Override
    public Preference decode(Object payload) {
      return new Preference("");
    }
  }

  private static final class NonFiniteCodec implements StateCodec<NonFiniteValue> {
    @Override
    public String typeId() {
      return "test.non-finite";
    }

    @Override
    public int version() {
      return 1;
    }

    @Override
    public Class<NonFiniteValue> javaType() {
      return NonFiniteValue.class;
    }

    @Override
    public Object encode(NonFiniteValue value) {
      return Double.NaN;
    }

    @Override
    public NonFiniteValue decode(Object payload) {
      return new NonFiniteValue();
    }
  }

  private static final class FirstMarkerCodec implements StateCodec<FirstMarker> {
    @Override
    public String typeId() {
      return "test.first-marker";
    }

    @Override
    public int version() {
      return 1;
    }

    @Override
    public Class<FirstMarker> javaType() {
      return FirstMarker.class;
    }

    @Override
    public Object encode(FirstMarker value) {
      return Map.of();
    }

    @Override
    public FirstMarker decode(Object payload) {
      return new BothMarkers();
    }
  }

  private static final class SecondMarkerCodec implements StateCodec<SecondMarker> {
    @Override
    public String typeId() {
      return "test.second-marker";
    }

    @Override
    public int version() {
      return 1;
    }

    @Override
    public Class<SecondMarker> javaType() {
      return SecondMarker.class;
    }

    @Override
    public Object encode(SecondMarker value) {
      return Map.of();
    }

    @Override
    public SecondMarker decode(Object payload) {
      return new BothMarkers();
    }
  }

  private static final class MarkerCodec implements StateCodec<Marker> {
    @Override
    public String typeId() {
      return "test.marker";
    }

    @Override
    public int version() {
      return 1;
    }

    @Override
    public Class<Marker> javaType() {
      return Marker.class;
    }

    @Override
    public Object encode(Marker value) {
      return Map.of("value", ((MarkerValue) value).value());
    }

    @Override
    public Marker decode(Object payload) {
      return new MarkerValue((String) ((Map<?, ?>) payload).get("value"));
    }
  }

  private static final class MarkerValueCodec implements StateCodec<MarkerValue> {
    @Override
    public String typeId() {
      return "test.marker-value";
    }

    @Override
    public int version() {
      return 1;
    }

    @Override
    public Class<MarkerValue> javaType() {
      return MarkerValue.class;
    }

    @Override
    public Object encode(MarkerValue value) {
      return Map.of("value", value.value());
    }

    @Override
    public MarkerValue decode(Object payload) {
      return new MarkerValue((String) ((Map<?, ?>) payload).get("value"));
    }
  }
}
