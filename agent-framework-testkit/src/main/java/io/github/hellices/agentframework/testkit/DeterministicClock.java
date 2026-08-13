package io.github.hellices.agentframework.testkit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Reproducible clocks for golden scenarios.
 *
 * <p>Tests that assert on event ordering, timeouts, or usage windows cannot read the wall clock and
 * stay deterministic, so they take a clock from here instead.
 */
public final class DeterministicClock {

  private DeterministicClock() {}

  /**
   * Returns a clock that always reports the same instant.
   *
   * @param instant the instant every read returns
   * @return a fixed UTC clock
   * @throws NullPointerException when {@code instant} is null
   */
  public static Clock fixedAt(Instant instant) {
    Objects.requireNonNull(instant, "instant");
    return Clock.fixed(instant, ZoneOffset.UTC);
  }

  /**
   * Returns a clock that advances by a fixed step after each read.
   *
   * <p>The first read returns {@code start}. Each later read adds one more {@code step}.
   *
   * @param start the instant the first read returns
   * @param step the amount added before each later read
   * @return a stepping UTC clock
   * @throws NullPointerException when {@code start} or {@code step} is null
   */
  public static Clock steppingFrom(Instant start, Duration step) {
    Objects.requireNonNull(start, "start");
    Objects.requireNonNull(step, "step");
    return new SteppingClock(start, step, ZoneOffset.UTC);
  }

  private static final class SteppingClock extends Clock {

    private final Instant start;
    private final Duration step;
    private final ZoneId zone;
    private final AtomicLong reads = new AtomicLong();

    private SteppingClock(Instant start, Duration step, ZoneId zone) {
      this.start = start;
      this.step = step;
      this.zone = zone;
    }

    @Override
    public ZoneId getZone() {
      return zone;
    }

    @Override
    public Clock withZone(ZoneId targetZone) {
      return new SteppingClock(start, step, Objects.requireNonNull(targetZone, "targetZone"));
    }

    @Override
    public Instant instant() {
      return start.plus(step.multipliedBy(reads.getAndIncrement()));
    }
  }
}
