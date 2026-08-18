package io.github.hellices.agentframework.engine.internal.session;

import io.github.hellices.agentframework.api.agent.AgentSession;
import io.github.hellices.agentframework.api.session.SessionContext;
import io.github.hellices.agentframework.api.session.SessionSnapshot;
import io.github.hellices.agentframework.api.session.SessionSnapshotMetadata;
import io.github.hellices.agentframework.spi.interception.SessionInvocation;
import io.github.hellices.agentframework.spi.interception.SessionInvocationChain;
import io.github.hellices.agentframework.spi.interception.SessionOperation;
import io.github.hellices.agentframework.spi.interception.SessionOperationResult;
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
 * session exactly as it was.
 *
 * <p>Concurrency between runs is last-writer-wins, and this class does not pretend otherwise. It
 * writes the revision that follows the one its own run read, so two runs that loaded the same
 * revision write the same next revision and the second write replaces the first: the later run's
 * session state wins whole, and the earlier run's turn is lost. That matches the design's file
 * store, whose atomic replace is last-writer-wins. Detecting the conflict instead would need a
 * conditional write, which {@link SessionStore} does not model — its {@code save} takes a snapshot
 * and nothing else — so a store cannot add optimistic concurrency behind this interface. Callers
 * that need it serialise runs on a session themselves until the SPI grows a compare-and-save
 * operation.
 */
public final class SessionCoordinator {

  /**
   * How one session-store operation is routed through the session interceptor seam. The default
   * calls the terminal directly; the engine supplies the registry's session chain so a chain
   * observes or replaces every load and save exactly once.
   */
  @FunctionalInterface
  public interface SessionSeam {
    CompletionStage<SessionOperationResult> intercept(
        SessionInvocation invocation, SessionInvocationChain terminal);
  }

  private static final SessionSeam DIRECT_SEAM =
      (invocation, terminal) -> terminal.proceed(invocation);

  private final SessionStore store;
  private final StateCodecRegistry registry;
  private final SessionSeam seam;

  public SessionCoordinator(SessionStore store, StateCodecRegistry registry) {
    this(store, registry, DIRECT_SEAM);
  }

  public SessionCoordinator(SessionStore store, StateCodecRegistry registry, SessionSeam seam) {
    this.store = Objects.requireNonNull(store, "sessionStore must not be null");
    this.registry = Objects.requireNonNull(registry, "stateCodecRegistry must not be null");
    this.seam = Objects.requireNonNull(seam, "sessionSeam must not be null");
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
    SessionInvocation invocation =
        SessionInvocation.builder()
            .operation(SessionOperation.LOAD)
            .session(session)
            .attributes(sessionContext.attributes())
            .cancellationSignal(sessionContext.cancellationSignal())
            .build();
    return invokeSeam(invocation, this::loadTerminal, "session load stage must not be null")
        .thenAccept(
            result -> {
              SessionOperationResult loaded =
                  Objects.requireNonNull(result, "session load result must not be null");
              loaded
                  .snapshot()
                  .ifPresent(
                      value ->
                          sessionContext.hydrate(
                              loaded.session(),
                              new SessionSnapshotMetadata(value.revision(), value.createdAt())));
            });
  }

  private CompletionStage<SessionOperationResult> loadTerminal(SessionInvocation invocation) {
    return requireStage(
            store.load(invocation.session().sessionId()),
            "session store load stage must not be null")
        .thenApply(
            loaded -> {
              Optional<SessionSnapshot> snapshot =
                  Objects.requireNonNull(loaded, "session store load result must not be null");
              if (snapshot.isEmpty()) {
                return SessionOperationResult.builder()
                    .operation(SessionOperation.LOAD)
                    .session(invocation.session())
                    .build();
              }
              SessionSnapshot value = snapshot.get();
              return SessionOperationResult.builder()
                  .operation(SessionOperation.LOAD)
                  .session(registry.restore(value))
                  .snapshot(value)
                  .build();
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
    SessionSnapshot snapshot = registry.snapshot(updated.get(), revision, createdAt);
    SessionInvocation invocation =
        SessionInvocation.builder()
            .operation(SessionOperation.SAVE)
            .session(updated.get())
            .snapshot(snapshot)
            .attributes(sessionContext.attributes())
            .cancellationSignal(sessionContext.cancellationSignal())
            .build();
    return invokeSeam(invocation, this::saveTerminal, "session save stage must not be null")
        .thenApply(ignored -> null);
  }

  private CompletionStage<SessionOperationResult> saveTerminal(SessionInvocation invocation) {
    SessionSnapshot snapshot =
        invocation
            .snapshot()
            .orElseThrow(() -> new IllegalStateException("SAVE invocation must carry a snapshot"));
    return requireStage(store.save(snapshot), "session store save stage must not be null")
        .thenApply(
            ignored ->
                SessionOperationResult.builder()
                    .operation(SessionOperation.SAVE)
                    .session(invocation.session())
                    .snapshot(snapshot)
                    .build());
  }

  /**
   * Runs the session seam for one operation, adapting a synchronous interceptor throw or a {@code
   * null} stage into a failed stage that carries the exact same cause. The engine gates the run's
   * first model call on the load stage and reports the save stage on the run's outcome, so routing
   * a synchronous seam failure into the returned stage is what lets the run's own response and
   * update channels report it instead of failing an unrelated caller synchronously.
   */
  private CompletionStage<SessionOperationResult> invokeSeam(
      SessionInvocation invocation, SessionInvocationChain terminal, String message) {
    try {
      return requireStage(seam.intercept(invocation, terminal), message);
    } catch (RuntimeException failure) {
      CompletableFuture<SessionOperationResult> failed = new CompletableFuture<>();
      failed.completeExceptionally(failure);
      return failed;
    }
  }

  private static <T> CompletionStage<T> requireStage(CompletionStage<T> stage, String message) {
    return Objects.requireNonNull(stage, message);
  }
}
