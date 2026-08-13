package io.github.hellices.agentframework.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class DeterministicClockTest {

  private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

  @Test
  void fixedClockNeverAdvances() {
    Clock clock = DeterministicClock.fixedAt(START);

    assertThat(clock.instant()).isEqualTo(START);
    assertThat(clock.instant()).isEqualTo(START);
  }

  @Test
  void steppingClockAdvancesByTheConfiguredStep() {
    Clock clock = DeterministicClock.steppingFrom(START, Duration.ofMillis(250));

    assertThat(clock.instant()).isEqualTo(START);
    assertThat(clock.instant()).isEqualTo(START.plusMillis(250));
    assertThat(clock.instant()).isEqualTo(START.plusMillis(500));
  }

  @Test
  void steppingClockKeepsItsPositionWhenTheZoneChanges() {
    Clock clock = DeterministicClock.steppingFrom(START, Duration.ofSeconds(1));
    Clock zoned = clock.withZone(ZoneId.of("Asia/Seoul"));

    assertThat(zoned.getZone()).isEqualTo(ZoneId.of("Asia/Seoul"));
    assertThat(zoned.instant()).isEqualTo(START);
  }

  @Test
  void rejectsNullArguments() {
    assertThatNullPointerException().isThrownBy(() -> DeterministicClock.fixedAt(null));
    assertThatNullPointerException()
        .isThrownBy(() -> DeterministicClock.steppingFrom(null, Duration.ZERO));
    assertThatNullPointerException().isThrownBy(() -> DeterministicClock.steppingFrom(START, null));
  }
}
