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

  private CompletableFuture<Generation> connectGeneration() {
    CompletableFuture<Generation> handshake;
    synchronized (lock) {
      if (current != null) {
        return CompletableFuture.completedFuture(current);
      }
      if (pending != null) {
        return pending.thenApply(generation -> generation);
      }
      handshake = adopt(++epoch);
    }
    mirror(startGeneration(), handshake);
    return handshake.thenApply(generation -> generation);
  }

  /**
   * Registers a fresh promise as the pending handshake. The caller must hold {@code lock}.
   *
   * <p>The promise is published inside the lock but completed outside it. That ordering is what
   * makes concurrent connects coalesce: a second caller that takes the lock while the first is
   * still building a transport already sees a pending handshake to join.
   *
   * <p>The ticket is a counter rather than the handshake future itself so that nothing in this
   * class has to compare two object references for identity, and so that {@code close()} can orphan
   * a handshake simply by moving the counter on.
   */
  private CompletableFuture<Generation> adopt(long ticket) {
    CompletableFuture<Generation> handshake = new CompletableFuture<>();
    pending = handshake;
    handshake.whenComplete((generation, failure) -> publish(ticket, generation));
    return handshake;
  }

  private void publish(long ticket, Generation generation) {
    synchronized (lock) {
      if (ticket != epoch) {
        return;
      }
      pending = null;
      current = generation;
    }
  }

  /**
   * Builds one generation. Never called while {@code lock} is held, and never throws: every failure
   * is reported through the returned stage, so a caller that already published a pending handshake
   * cannot be left with a promise nobody completes.
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
    }
    return handshake;
  }

  private static void closeAfterFailedHandshake(
      Generation generation, Throwable failure, CompletableFuture<Generation> handshake) {
    generation
        .close()
        .whenComplete(
            (ignored, cleanupFailure) ->
                handshake.completeExceptionally(reported(failure, cleanupFailure)));
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
    cleanup.whenComplete(
        (ignored, cleanupFailure) ->
            failed.completeExceptionally(reported(failure, cleanupFailure)));
    return failed;
  }

  private static Throwable reported(Throwable failure, Throwable cleanupFailure) {
    Throwable reported = AsyncStages.unwrap(failure);
    if (cleanupFailure != null) {
      reported.addSuppressed(AsyncStages.unwrap(cleanupFailure));
    }
    return reported;
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

    CompletableFuture<Void> close() {
      if (!closed.compareAndSet(false, true)) {
        return closure;
      }
      mirror(AsyncStages.fromMono(client.closeGracefully()), closure);
      return closure;
    }
  }
}
