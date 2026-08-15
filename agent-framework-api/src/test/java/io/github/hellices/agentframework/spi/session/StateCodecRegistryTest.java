package io.github.hellices.agentframework.spi.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hellices.agentframework.api.agent.AgentSession;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.MessageAttribution;
import io.github.hellices.agentframework.api.message.Role;
import io.github.hellices.agentframework.api.message.TextContent;
import io.github.hellices.agentframework.api.message.ToolCallContent;
import io.github.hellices.agentframework.api.session.SessionSnapshot;
import io.github.hellices.agentframework.api.session.SessionStateEntry;
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
        new AgentSession("session-1", "service-1", Map.of("preferences", new Preference("dark")));

    SessionSnapshot snapshot = registry.snapshot(session, 3, Instant.parse("2026-08-15T00:00:00Z"));
    AgentSession restored = registry.restore(snapshot);

    assertThat(snapshot.type()).isEqualTo("session");
    assertThat(snapshot.version()).isEqualTo("1.0");
    assertThat(snapshot.state().get("preferences").typeId()).isEqualTo("test.preference");
    assertThat(restored.state()).containsEntry("preferences", new Preference("dark"));
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
        registry.snapshot(
            new AgentSession("session-1", null, Map.of("message", message)), 0, Instant.EPOCH);

    AgentSession restored = registry.restore(snapshot);

    assertThat(restored.state().get("message"))
        .isInstanceOfSatisfying(
            Message.class,
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
                new AgentSession("session-1", null, Map.of("message", message)), 0, Instant.EPOCH));

    assertThat(restored.state().get("message"))
        .isInstanceOfSatisfying(
            Message.class,
            value -> {
              assertThat(((TextContent) value.content().get(0)).value()).isEmpty();
              assertThat(((ToolCallContent) value.content().get(1)).arguments())
                  .containsEntry("filter", null);
            });
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
  void unregisteredAndNonJsonSafeStateFailBeforeSnapshotCreation() {
    StateCodecRegistry registry = StateCodecRegistry.builder().build();

    assertThatThrownBy(
            () ->
                registry.snapshot(
                    new AgentSession("session-1", null, Map.of("unknown", new Object())),
                    0,
                    Instant.EPOCH))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("unregistered session state type: java.lang.Object");

    StateCodecRegistry invalidRegistry =
        StateCodecRegistry.builder().register(new NonFiniteCodec()).build();
    assertThatThrownBy(
            () ->
                invalidRegistry.snapshot(
                    new AgentSession("session-1", null, Map.of("invalid", new NonFiniteValue())),
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
  void ambiguousAssignableCodecsAreRejected() {
    StateCodecRegistry registry =
        StateCodecRegistry.builder()
            .register(new FirstMarkerCodec())
            .register(new SecondMarkerCodec())
            .build();

    assertThatThrownBy(
            () ->
                registry.snapshot(
                    new AgentSession("session-1", null, Map.of("value", new BothMarkers())),
                    0,
                    Instant.EPOCH))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ambiguous session state codecs");
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

  private record Preference(String theme) {}

  private record NonFiniteValue() {}

  private interface FirstMarker {}

  private interface SecondMarker {}

  private static final class BothMarkers implements FirstMarker, SecondMarker {}

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
}
