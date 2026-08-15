package io.github.hellices.agentframework.engine.internal.session;

import io.github.hellices.agentframework.api.agent.AgentSession;
import io.github.hellices.agentframework.api.session.SessionContext;
import io.github.hellices.agentframework.api.session.SessionSnapshot;
import io.github.hellices.agentframework.api.session.SessionSnapshotMetadata;
import io.github.hellices.agentframework.spi.session.SessionStore;
import io.github.hellices.agentframework.spi.session.StateCodecRegistry;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Owns the durable half of a run: load and restore the stored session before the run binds its
 * context providers, and snapshot and save the updated session after the run succeeded (SES-003,
 * SES-014).
 *
 * <p>It is stateless between runs. Everything one run needs to connect its load to its save — the
 * restored session and the revision and creation instant of the snapshot it came from — lives on
 * that run's {@link SessionContext}, so the coordinator holds no per-run map, no registry, and no
 * executor, and two concurrent runs of the same agent cannot observe each other's bookkeeping.
 *
 * <p>The order is deliberate and fails closed: a run that cannot load, cannot restore, or cannot
 * run its hooks never reaches {@link #save(SessionContext)}, so a failed run leaves the stored
 * session exactly as it was. Concurrency between runs is the store's concern: this class always
 * writes the revision that follows the one it read, and a store that wants stricter guarantees than
 * last-writer-wins enforces them in {@link SessionStore#save(SessionSnapshot)}.
 */
public final class SessionCoordinator {

  private final SessionStore store;
  private final StateCodecRegistry registry;

  public SessionCoordinator(SessionStore store, StateCodecRegistry registry) {
    this.store = Objects.requireNonNull(store, "sessionStore must not be null");
    this.registry = Objects.requireNonNull(registry, "stateCodecRegistry must not be null");
  }

  /**
   * Loads the stored snapshot for this run's session and hydrates the run's context with it, before
   * any context provider is resolved or bound.
   *
   * <p>A sessionless run performs no store I/O at all. When nothing is stored for the session, the
   * request's own session stays in force and the run will save at revision 0. When a snapshot
   * exists, the restored session becomes the run's source of truth and its revision and creation
   * instant are carried to the save.
   *
   * @param sessionContext the run's context; must not be {@code null}
   * @return a stage completing once the run may bind its providers
   */
  public CompletionStage<Void> load(SessionContext sessionContext) {
    Objects.requireNonNull(sessionContext, "sessionContext must not be null");
    AgentSession session = sessionContext.session();
    if (session == null) {
      return CompletableFuture.completedFuture(null);
    }
    return requireStage(
            store.load(session.sessionId()), "session store load stage must not be null")
        .thenAccept(
            loaded -> {
              Optional<SessionSnapshot> snapshot =
                  Objects.requireNonNull(loaded, "session store load result must not be null");
              snapshot.ifPresent(
                  value ->
                      sessionContext.hydrate(
                          registry.restore(value),
                          new SessionSnapshotMetadata(value.revision(), value.createdAt())));
            });
  }

  /**
   * Snapshots the session this run produced and writes it to the store, after every {@code
   * afterRun} hook completed.
   *
   * <p>The snapshot continues the loaded one: a run that restored revision {@code n} writes {@code
   * n + 1} and keeps the stored creation instant, while a session that was never stored is written
   * at revision 0 and stamps its creation instant now. A sessionless run has no updated session and
   * performs no store I/O.
   *
   * @param sessionContext the run's context; must not be {@code null}
   * @return a stage completing once the snapshot was written
   */
  public CompletionStage<Void> save(SessionContext sessionContext) {
    Objects.requireNonNull(sessionContext, "sessionContext must not be null");
    Optional<AgentSession> updated = sessionContext.updatedSession();
    if (updated.isEmpty()) {
      return CompletableFuture.completedFuture(null);
    }
    Optional<SessionSnapshotMetadata> metadata = sessionContext.snapshotMetadata();
    long revision = metadata.map(value -> Math.addExact(value.revision(), 1L)).orElse(0L);
    Instant createdAt = metadata.map(SessionSnapshotMetadata::createdAt).orElseGet(Instant::now);
    return requireStage(
        store.save(registry.snapshot(updated.get(), revision, createdAt)),
        "session store save stage must not be null");
  }

  private static <T> CompletionStage<T> requireStage(CompletionStage<T> stage, String message) {
    return Objects.requireNonNull(stage, message);
  }
}
