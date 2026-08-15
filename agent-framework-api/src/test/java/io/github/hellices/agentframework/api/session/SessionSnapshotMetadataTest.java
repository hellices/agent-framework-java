package io.github.hellices.agentframework.api.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class SessionSnapshotMetadataTest {

  @Test
  void metadataCarriesTheLoadedRevisionAndCreationInstant() {
    SessionSnapshotMetadata metadata =
        new SessionSnapshotMetadata(4, Instant.parse("2026-01-01T00:00:00Z"));

    assertThat(metadata.revision()).isEqualTo(4);
    assertThat(metadata.createdAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
  }

  @Test
  void aNegativeRevisionIsRejected() {
    Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");

    assertThatThrownBy(() -> new SessionSnapshotMetadata(-1, createdAt))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("revision must not be negative");
  }

  @Test
  void aNullCreationInstantIsRejected() {
    assertThatThrownBy(() -> new SessionSnapshotMetadata(0, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("createdAt must not be null");
  }
}
