package io.github.hellices.agentframework.mcp.internal;

import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.spec.McpClientTransport;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns the MCP client the adapter talks to.
 *
 * <p>The lifecycle holds at most one generation. A generation is one freshly created transport plus
 * one client built on it, and it is never reused after it was closed, because neither the stdio nor
 * the streamable HTTP transport can be revived.
 *
 * <p>The owner object itself is reusable. Its initial state is disconnected, an explicit {@link
 * #connect()} is always required before any operation, and an explicit {@link #connect()} after a
 * close creates a fresh generation. No operation ever starts a process or opens a connection on its
 * own.
 *
 * <p>The handshake runs on a subscription the caller cannot dispose. Disposing it would leave the
 * SDK initializer holding a permanently pending initialization, after which every later operation
 * blocks for the initialization timeout and no second handshake is ever attempted.
 */
public final class OwnedMcpClientLifecycle {

  private final McpClientTransportFactory transportFactory;
  private final McpOwnedClientSettings settings;
  private final Object lock = new Object();

  private CompletableFuture<Generation> pending;
  private Generation current;
  private CompletableFuture<Void> closing;
  private long epoch;

  /**
   * Creates a lifecycle.
   *
   * @param transportFactory creates each generation's transport, never {@code null}
   * @param settings settings applied to each generation's client, never {@code null}
   * @throws IllegalArgumentException if an argument is {@code null}
   */
  public OwnedMcpClientLifecycle(
      McpClientTransportFactory transportFactory, McpOwnedClientSettings settings) {
    if (transportFactory == null) {
      throw new IllegalArgumentException("transportFactory must not be null");
    }
    if (settings == null) {
      throw new IllegalArgumentException("settings must not be null");
    }
    this.transportFactory = transportFactory;
    this.settings = settings;
  }

  /**
   * Connects, creating and initializing a generation if there is none.
   *
   * <p>Calling this while connected is a no-op, and concurrent calls join the same handshake.
   * Cancelling the returned stage abandons the caller's interest only; the handshake keeps running
   * so the generation stays usable.
   *
   * @return a stage completing when a generation is ready, never {@code null}
   */
  public CompletableFuture<Void> connect() {
    return connectGeneration().thenApply(generation -> null);
  }

  /**
   * Closes the current generation and leaves the owner reusable.
   *
   * <p>Close ends one generation, not this object. The generation reference and everything cached
   * on it are dropped whether or not the underlying close succeeded, because a generation whose
   * close failed is not safe to keep using; a cleanup failure is reported to this caller instead of
   * being swallowed. A later explicit {@link #connect()} creates a fresh generation. Closing while
   * a handshake is in flight waits for that handshake to settle and then closes what it produced,
   * rather than abandoning a client the SDK is still initializing.
   *
   * <p>The cleanup itself is the SDK's, so this method imposes no deadline of its own: no timeout,
   * no blocking wait, and no thread or executor this module would have to own. A cleanup that never
   * completes therefore leaves the returned stage pending, and a caller that cannot wait forever
   * applies its own deadline to that stage. What it cannot do is wedge the owner, because the
   * generation is dropped before the cleanup starts and a later {@link #connect()} opens a fresh
   * one while the previous cleanup is still running.
   *
   * @return a stage completing when the generation is released, never {@code null}
   */
  public CompletableFuture<Void> close() {
    Generation generation;
    CompletableFuture<Generation> settling;
    CompletableFuture<Void> closure = new CompletableFuture<>();
    synchronized (lock) {
      epoch++;
      if (current == null && pending == null) {
        return closing == null ? CompletableFuture.completedFuture(null) : released(closing);
      }
      generation = current;
      settling = pending;
      current = null;
      pending = null;
      closing = closure;
    }
    if (generation != null) {
      release(generation, closure);
      return released(closure);
    }
    settling.whenComplete(
        (settled, failure) -> {
          if (settled == null) {
            closure.complete(null);
          } else {
            release(settled, closure);
          }
        });
    return released(closure);
  }

  /**
   * Starts a generation's cleanup and settles {@code closure} even when starting it does not
   * return.
   *
   * <p>The promise is already published when this runs, so a throwable that leaves {@link
   * Generation#close()} instead of failing the stage it returns has to settle it here. Otherwise
   * every other close caller, and every later close, is handed a stage nobody is left to complete —
   * the same failure mode {@code Generation.close()} guards against, one frame up. The settled
   * generation branch makes it worse than a wedge: it runs inside a completion callback whose
   * dependent stage nobody holds, so a throwable that only travels there is one no caller can ever
   * observe.
   */
  private static void release(Generation generation, CompletableFuture<Void> closure) {
    try {
      mirror(generation.close(), closure);
    } catch (RuntimeException | Error failure) {
      closure.completeExceptionally(failure);
      throw failure;
    }
  }

  /**
   * Gives one close caller its own view of the cleanup every close caller shares.
   *
   * <p>Repeated closes join one teardown, so handing the shared promise itself to each of them
   * would let any one caller decide the outcome for all the others, or abandon a teardown that is
   * already running, simply by cancelling or completing the stage it was given. A dependent stage
   * carries the same outcome and none of that authority, which is the same reason {@link
   * #connect()} never hands back the handshake promise itself.
   */
  private static CompletableFuture<Void> released(CompletableFuture<Void> closure) {
    return closure.thenApply(release -> release);
  }

  private CompletableFuture<Generation> connectGeneration() {
    long ticket;
    CompletableFuture<Generation> adopted;
    synchronized (lock) {
      if (current != null) {
        return CompletableFuture.completedFuture(current);
      }
      if (pending != null) {
        return pending.thenApply(generation -> generation);
      }
      closing = null;
      ticket = ++epoch;
      adopted = adopt();
    }
    try {
      startGeneration()
          .whenComplete((generation, failure) -> settle(ticket, adopted, generation, failure));
    } catch (RuntimeException | Error failure) {
      // A throwable that is not an ordinary failure keeps travelling, but the promise is already
      // published, so it has to be settled before this frame unwinds. Otherwise the owner stays in
      // connecting forever and every later connect joins a promise nobody is left to complete.
      settle(ticket, adopted, null, failure);
      throw failure;
    }
    return adopted.thenApply(generation -> generation);
  }

  /**
   * Registers a fresh promise as the pending handshake. The caller must hold {@code lock}.
   *
   * <p>The promise is published inside the lock but completed outside it. That ordering is what
   * makes concurrent connects coalesce: a second caller that takes the lock while the first is
   * still building a transport already sees a pending handshake to join.
   */
  private CompletableFuture<Generation> adopt() {
    CompletableFuture<Generation> adopted = new CompletableFuture<>();
    pending = adopted;
    return adopted;
  }

  /**
   * Moves the owner out of connecting, then hands the outcome to the callers waiting for it.
   *
   * <p>The order is the point, and it is expressed as two statements rather than as two callbacks
   * on the same promise. A caller that reacts to the outcome — a retry issued from a failure
   * callback is the ordinary shape — must find owner state that already reflects it, or it joins
   * the promise that just failed and no new generation is ever built. Registration order cannot
   * provide that: {@link CompletableFuture} runs dependents in the reverse order of registration,
   * so a publishing callback would run after every caller it was meant to unblock.
   *
   * <p>Neither statement runs while {@code lock} is held, so a caller woken by the completion can
   * connect again from inside its own callback. A generation the owner refused to adopt is released
   * last, after the callers have been told what happened, because releasing it runs SDK cleanup
   * that must not delay the outcome anyone is waiting for.
   */
  private void settle(
      long ticket,
      CompletableFuture<Generation> adopted,
      Generation generation,
      Throwable failure) {
    Generation orphan = publish(ticket, failure == null ? generation : null);
    if (failure == null) {
      adopted.complete(generation);
    } else {
      adopted.completeExceptionally(AsyncStages.unwrap(failure));
    }
    if (orphan != null) {
      // Nothing reaches this generation any more: the owner moved on while its handshake was still
      // running, so it is released here rather than left running as an unreachable client and
      // process. A close() chained onto the same handshake asks for the very same cleanup, and
      // Generation.close is idempotent, so this releases the generation exactly once whichever of
      // the two got there first.
      orphan.close();
    }
  }

  /**
   * Records the generation the owner ended up with, or none when the handshake failed.
   *
   * <p>The ticket is a counter rather than the promise itself so that nothing in this class has to
   * compare two object references for identity, and so that {@link #close()} can orphan a handshake
   * simply by moving the counter on.
   *
   * @return the generation the owner refused to adopt because the ticket no longer matches, or
   *     {@code null} when there is none. It is returned rather than closed here, because closing a
   *     client dismisses its in-flight requests on the closing thread and no external call runs
   *     while this lock is held.
   */
  private Generation publish(long ticket, Generation generation) {
    synchronized (lock) {
      if (ticket != epoch) {
        return generation;
      }
      pending = null;
      current = generation;
      return null;
    }
  }

  /**
   * Builds one generation and reports its outcome through the returned stage. Never called while
   * {@code lock} is held.
   *
   * <p>The returned stage is private to this class: it carries the handshake outcome to the caller
   * that adopted the generation, which settles the published promise from it. Nothing outside ever
   * sees it, so completing it cannot run caller code.
   *
   * <p>Every failure the three steps produce as an ordinary failure is reported through that stage
   * rather than thrown. A throwable that is not a {@link RuntimeException} — a service loader or
   * linkage error from a consumer's classpath, say — is not an ordinary failure and still leaves
   * this frame, which is why {@link #connectGeneration()} settles the published promise before
   * letting it propagate.
   *
   * <p>The three steps fail differently. A factory failure leaves nothing to clean up. A client
   * build failure leaves a live transport that only this method still knows about, so it closes it.
   * A subscription failure leaves a client that owns the transport, so the generation closes it,
   * once, through the same idempotent path every other close uses.
   */
  private CompletableFuture<Generation> startGeneration() {
    McpClientTransport transport;
    try {
      transport = transportFactory.create();
      if (transport == null) {
        throw new IllegalStateException("transportFactory returned no transport");
      }
    } catch (RuntimeException failure) {
      return AsyncStages.failed(failure);
    }
    McpAsyncClient client;
    try {
      client =
          McpClient.async(transport)
              .requestTimeout(settings.requestTimeout())
              .initializationTimeout(settings.initializationTimeout())
              .jsonSchemaValidator(settings.schemaValidator())
              .build();
    } catch (RuntimeException failure) {
      return reportAfterCleanup(closeUnowned(transport), failure);
    } catch (Error failure) {
      // Not an ordinary failure: it must keep travelling as the same instance rather than being
      // reported through this method's returned stage. The transport still exists and only this
      // method knows about it, so its close is started (not awaited) before the rethrow.
      closeUnowned(transport);
      throw failure;
    }
    Generation generation = new Generation(client);
    CompletableFuture<Generation> handshake = new CompletableFuture<>();
    try {
      client
          .initialize()
          .subscribe(
              result -> handshake.complete(generation),
              failure -> closeAfterFailedHandshake(generation, failure, handshake),
              () -> handshake.complete(generation));
    } catch (RuntimeException failure) {
      return reportAfterCleanup(generation.close(), failure);
    } catch (Error failure) {
      // Same rule as the client-build case: the generation already exists, so its close is
      // started (not awaited) before the same Error instance keeps travelling.
      generation.close();
      throw failure;
    }
    return handshake;
  }

  private static void closeAfterFailedHandshake(
      Generation generation, Throwable failure, CompletableFuture<Generation> handshake) {
    generation
        .close()
        .whenComplete((ignored, cleanupFailure) -> report(handshake, failure, cleanupFailure));
  }

  /** Closes a transport no client ever took ownership of. */
  private static CompletableFuture<Void> closeUnowned(McpClientTransport transport) {
    try {
      return AsyncStages.fromMono(transport.closeGracefully());
    } catch (RuntimeException failure) {
      return AsyncStages.failed(failure);
    }
  }

  private static CompletableFuture<Generation> reportAfterCleanup(
      CompletableFuture<Void> cleanup, Throwable failure) {
    CompletableFuture<Generation> failed = new CompletableFuture<>();
    cleanup.whenComplete((ignored, cleanupFailure) -> report(failed, failure, cleanupFailure));
    return failed;
  }

  /**
   * Fails {@code target} with what went wrong, annotated with what went wrong while cleaning up
   * after it.
   *
   * <p>A transport that reports the very instance it failed with — a cached or shared throwable —
   * would otherwise leave the target incomplete, because a throwable refuses to suppress itself and
   * that rejection would escape before the target was ever failed. Throwables do not redefine
   * equality, so comparing them is the identity check that rejection is based on, and completing in
   * a {@code finally} keeps any remaining trouble in the annotation from skipping it: what gets
   * dropped is the cleanup failure, never the failure a caller is waiting for.
   */
  private static void report(
      CompletableFuture<Generation> target, Throwable failure, Throwable cleanupFailure) {
    Throwable reported = AsyncStages.unwrap(failure);
    try {
      Throwable cleanup = cleanupFailure == null ? null : AsyncStages.unwrap(cleanupFailure);
      if (cleanup != null && !cleanup.equals(reported)) {
        reported.addSuppressed(cleanup);
      }
    } finally {
      target.completeExceptionally(reported);
    }
  }

  /** Completes {@code target} with whatever {@code source} produced, unwrapping the failure. */
  private static <T> void mirror(CompletableFuture<T> source, CompletableFuture<T> target) {
    source.whenComplete(
        (value, failure) -> {
          if (failure == null) {
            target.complete(value);
          } else {
            target.completeExceptionally(AsyncStages.unwrap(failure));
          }
        });
  }

  /** One transport and one client, closed at most once. */
  private static final class Generation {

    private final McpAsyncClient client;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CompletableFuture<Void> closure = new CompletableFuture<>();

    Generation(McpAsyncClient client) {
      this.client = client;
    }

    McpAsyncClient client() {
      return client;
    }

    /**
     * Releases this generation once, and reports that one release to every caller.
     *
     * <p>The closure is memoized, so the flag that guards it is also a promise nothing else will
     * ever complete: a cleanup that fails while it is being set up has to settle it here, or every
     * close caller waits on a stage no one is left to finish.
     */
    CompletableFuture<Void> close() {
      if (!closed.compareAndSet(false, true)) {
        return closure;
      }
      try {
        mirror(AsyncStages.fromMono(client.closeGracefully()), closure);
      } catch (RuntimeException failure) {
        closure.completeExceptionally(failure);
      } catch (Error failure) {
        // Not an ordinary failure, so it keeps travelling as the same instance; the memoized
        // closure is settled first because it is what every later close call is handed.
        closure.completeExceptionally(failure);
        throw failure;
      }
      return closure;
    }
  }
}
