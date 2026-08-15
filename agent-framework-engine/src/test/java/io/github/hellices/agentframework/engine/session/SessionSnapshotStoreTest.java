package io.github.hellices.agentframework.engine.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hellices.agentframework.api.session.SessionSnapshot;
import io.github.hellices.agentframework.api.session.SessionStateEntry;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SessionSnapshotStoreTest {

  @Test
  void jsonCodecWritesAndChecksTheVersionedEnvelope() {
    JacksonSessionSnapshotCodec codec = new JacksonSessionSnapshotCodec();
    SessionSnapshot snapshot =
        new SessionSnapshot(
            "session",
            "1.0",
            "session-1",
            "service-1",
            4,
            Instant.parse("2026-08-15T00:00:00Z"),
            Map.of(
                "preference",
                new SessionStateEntry(
                    "test.preference",
                    1,
                    Map.of(
                        "theme", "dark",
                        "count", 7L,
                        "ratio", new BigDecimal("2.0"),
                        "precise", new BigDecimal("1.2345678901234567890123456789"),
                        "exponent", new BigDecimal("1E+400")))));

    byte[] encoded = codec.encode(snapshot);
    SessionSnapshot decoded = codec.decode(encoded);
    String json = new String(encoded, StandardCharsets.UTF_8);

    assertThat(json).contains("\"type\":\"session\"", "\"version\":\"1.0\"");
    assertThat(decoded).isEqualTo(snapshot);
    assertThatThrownBy(
            () ->
                codec.decode(
                    "{\"type\":\"other\",\"version\":\"1.0\"}".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(SessionSnapshotSchemaException.class)
        .hasMessage("snapshot type must be session");
    assertThatThrownBy(() -> codec.decode("{".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(SessionSnapshotParseException.class);
    assertThatThrownBy(
            () ->
                codec.decode(
                    ("{\"type\":\"session\",\"version\":\"1.0\",\"sessionId\":\"s\","
                            + "\"revision\":0,\"createdAt\":\"bad\",\"state\":{}}")
                        .getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(SessionSnapshotSchemaException.class);
    assertThatThrownBy(
            () ->
                codec.decode(
                    ("{\"type\":\"session\",\"version\":\"1.0\",\"sessionId\":\"s\","
                            + "\"createdAt\":\"1970-01-01T00:00:00Z\",\"state\":{}}")
                        .getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(SessionSnapshotSchemaException.class)
        .hasMessageContaining("revision");
    assertThatThrownBy(
            () ->
                codec.decode(
                    ("{\"type\":\"session\",\"version\":\"1.0\",\"sessionId\":\"s\","
                            + "\"revision\":0,\"createdAt\":\"1970-01-01T00:00:00Z\","
                            + "\"state\":{\"x\":{\"typeId\":\"test\",\"payload\":{}}}}")
                        .getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(SessionSnapshotSchemaException.class)
        .hasMessageContaining("codecVersion");
    assertThatThrownBy(
            () ->
                codec.decode(
                    snapshotJson("{\"kind\":\"string\",\"value\":123}")
                        .getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(SessionSnapshotSchemaException.class)
        .hasMessageContaining("string payload");
    assertThatThrownBy(
            () ->
                codec.decode(
                    snapshotJson("{\"kind\":\"decimal\",\"value\":\"" + "0".repeat(1_025) + "\"}")
                        .getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(SessionSnapshotSchemaException.class)
        .hasMessageContaining("length limit");
    assertThatThrownBy(() -> codec.decode(new byte[1_048_577]))
        .isInstanceOf(SessionSnapshotSchemaException.class)
        .hasMessageContaining("1 MiB");
    SessionSnapshot oversized =
        new SessionSnapshot(
            "session",
            "1.0",
            "session-large",
            null,
            0,
            Instant.EPOCH,
            Map.of(
                "value", new SessionStateEntry("test", 1, Map.of("text", "x".repeat(1_048_576)))));
    assertThatThrownBy(() -> codec.encode(oversized))
        .isInstanceOf(SessionSnapshotSchemaException.class)
        .hasMessageContaining("1 MiB");
    assertThatThrownBy(
            () -> new SessionStateEntry("test", 1, Map.of("decimal", new BigDecimal("1E-10001"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("scale limit");
  }

  @Test
  void inMemoryStoreReturnsIndependentSnapshotsAndExplicitAbsence() {
    JacksonSessionSnapshotCodec codec = new JacksonSessionSnapshotCodec();
    InMemorySessionStore store = new InMemorySessionStore(codec);
    Map<String, Object> mutablePayload = new LinkedHashMap<>();
    mutablePayload.put("theme", "dark");
    SessionSnapshot snapshot =
        new SessionSnapshot(
            "session",
            "1.0",
            "session-1",
            null,
            0,
            Instant.EPOCH,
            Map.of("preference", new SessionStateEntry("test.preference", 1, mutablePayload)));

    store.save(snapshot).toCompletableFuture().join();
    mutablePayload.put("theme", "changed");
    SessionSnapshot first = store.load("session-1").toCompletableFuture().join().orElseThrow();
    SessionSnapshot second = store.load("session-1").toCompletableFuture().join().orElseThrow();

    assertThat(first).isEqualTo(second);
    assertThat(((Map<?, ?>) first.state().get("preference").payload()).get("theme"))
        .isEqualTo("dark");
    assertThat(store.load("missing").toCompletableFuture().join()).isEmpty();
  }

  private static String snapshotJson(String payload) {
    return "{\"type\":\"session\",\"version\":\"1.0\",\"sessionId\":\"s\","
        + "\"revision\":0,\"createdAt\":\"1970-01-01T00:00:00Z\","
        + "\"state\":{\"value\":{\"typeId\":\"test\",\"codecVersion\":1,\"payload\":"
        + payload
        + "}}}";
  }
}
