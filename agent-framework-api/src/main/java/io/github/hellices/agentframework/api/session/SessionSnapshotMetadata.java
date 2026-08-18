package io.github.hellices.agentframework.api.session;

import java.time.Instant;
import java.util.Objects;

/**
 * The persistence bookkeeping a run carries from the snapshot it was loaded from, so the save that
 * ends the run can continue that snapshot's history instead of starting a new one (SES-003).
 *
 * <p>It is attached to the run's {@link SessionContext} rather than kept in an engine-side map
 * keyed by run, because {@code Agent}'s after-run hook only receives the context: keeping the
 * metadata on the context is what lets the load and the save of one run agree without the engine
 * holding per-run mutable state.
 */
public final class SessionSnapshotMetadata {

  private final long revision;
  private final Instant createdAt;

  public SessionSnapshotMetadata(long revision, Instant createdAt) {
    if (revision < 0) {
      throw new IllegalArgumentException("revision must not be negative");
    }
    this.revision = revision;
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
  }

  public long revision() {
    return revision;
  }

  public Instant createdAt() {
    return createdAt;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof SessionSnapshotMetadata that
        && revision == that.revision
        && createdAt.equals(that.createdAt);
  }

  @Override
  public int hashCode() {
    return Objects.hash(revision, createdAt);
  }

  @Override
  public String toString() {
    return "SessionSnapshotMetadata[revision=" + revision + ", createdAt=" + createdAt + "]";
  }
}
