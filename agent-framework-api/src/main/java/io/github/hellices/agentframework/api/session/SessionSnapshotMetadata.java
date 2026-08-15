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
 *
 * @param revision the revision of the snapshot this run was restored from; never negative
 * @param createdAt the instant the stored session was first written; carried forward by every later
 *     save so the creation time of a session is not rewritten on every run
 */
public record SessionSnapshotMetadata(long revision, Instant createdAt) {

  public SessionSnapshotMetadata {
    if (revision < 0) {
      throw new IllegalArgumentException("revision must not be negative");
    }
    createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
  }
}
