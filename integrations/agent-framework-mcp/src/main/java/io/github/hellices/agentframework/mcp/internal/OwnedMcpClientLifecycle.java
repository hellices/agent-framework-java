package io.github.hellices.agentframework.mcp.internal;

import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.spec.McpClientTransport;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import reactor.core.publisher.Mono;

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
 * <p>An operation replaces the generation it was given at most once, and only after the connection
 * behind it was lost: either the validation ping went unanswered, or the call itself failed the way
 * the SDK reports a session the server no longer has. A single request is then repeated once on the
 * replacement, so one request reaches at most two generations and each of them exactly once. The
 * two stages ask different questions: a ping has no side effect, so any failure of it buys the
 * replacement, while a call is repeated only when the failure proves the server never accepted it.
 * A paged read is one operation spanning many requests and is never repeated that way, because its
 * cursor belongs to the session that issued it; its reader is told the connection was replaced and
 * starts the read again. The stale generation is always released before the replacement is created,
 * so an owner never holds two live servers, and concurrent failures on one generation produce one
 * replacement rather than one each.
 *
 * <p>Replacing a generation also dismisses whatever else was in flight on it, and that dismissal
 * says nothing about the server: a request may have arrived and run before the close. Which
 * operations may act on it therefore follows the same at-most-once rule as everything else here. A
 * catalogue read restarts, because reading changes nothing. A tool call, and any operation the
 * owner cannot tell apart from one, is reported to its caller, because the alternative is running
 * its side effect twice.
 *
 * <p>The handshake runs on a subscription the caller cannot dispose. Disposing it would leave the
 * SDK initializer holding a permanently pending initialization, after which every later operation
 * blocks for the initialization timeout and no second handshake is ever attempted.
 */
public final class OwnedMcpClientLifecycle {

  private static final String NOT_CONNECTED =
      "the MCP server connection is not open; call connect() before discovering or calling tools,"
          + " because an owned client never opens a connection implicitly and a lookup that started"
          + " a server process or a network session on its own would hide that cost from the caller";

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
   * Runs one operation on the current generation.
   *
   * <p>The operation is a function of the client rather than a prepared publisher because a retry
   * has to run it against a different client. Nothing here connects: an operation attempted with no
   * generation fails, and an operation attempted while a handshake is in flight joins that
   * handshake instead of starting another one. It runs on the generation the owner holds when the
   * call starts, or on the one generation that replaced it, so an operation whose generation the
   * owner released while it waited fails on the same explicit connect requirement rather than
   * reaching a server through a connection nobody owns any more.
   *
   * <p>The generation is validated with a ping before the operation is dispatched, because a stdio
   * server can have died and an HTTP session can have been dropped since the last call, and finding
   * that out from a tool call means finding it out after the call may already have run. A server
   * that answers the ping with {@code -32601} implements no ping, which is recorded on that
   * generation so it is asked once and never again. Any other validation failure replaces the
   * generation once and runs the operation on the replacement; a validation that was cancelled
   * replaces nothing, because a caller changing its mind says nothing about the connection.
   *
   * <p>A connection can also be lost between the ping and the answer, so a call that fails the way
   * the SDK reports a session the server no longer has is repeated once on a replacement
   * generation. Validation and the call share one reconnect budget, which bounds an operation at
   * two generations and one repeat whichever stage spent it. Nothing else is repeated: an
   * application error, a request that timed out, a transport failure that leaves it unknown whether
   * the server ran the request, and the untyped dismissal this owner causes when it replaces the
   * generation for another operation are all reported, because repeating a tool call the server may
   * already have run would run its side effect twice. A connection that really is gone is healed by
   * the next operation's ping instead.
   *
   * <p>This entry point is one request whose effect on the server is unknown to the owner, so it
   * takes the conservative attempt from {@link #sideEffectingAttempt()}. An operation made of
   * several requests takes its attempt from {@link #pagedAttempt()} and passes it to every request
   * of the read, which spends the budget once for all of them and reports the replacement to the
   * caller rather than repeating a request the new session cannot interpret.
   *
   * <p>Cancelling the returned stage before the operation was dispatched stops it from being
   * dispatched at all, so a cancelled call never reaches the server; a cancellation that arrives
   * after dispatch disposes the in-flight request instead. Either way the generation stays open.
   *
   * @param operation produces the SDK call for a given client, never {@code null}
   * @param <T> the operation result type
   * @return a stage completing with the operation result, never {@code null}
   */
  public <T> CompletableFuture<T> execute(Function<McpAsyncClient, Mono<T>> operation) {
    return execute(operation, sideEffectingAttempt());
  }

  /**
   * Creates the attempt of one request that may change what the server holds.
   *
   * <p>This is the conservative policy, and it is what the generic {@link #execute(Function)} uses,
   * because that entry point is handed a function of a client and cannot tell a catalogue read from
   * a tool call. A single request repeats itself on a replacement, but only for a failure that
   * proves the server never had it; a request dismissed because this owner closed the generation
   * for another operation is reported, since the server may have run it already.
   *
   * @return an attempt for one request whose repeat must be earned, never {@code null}
   */
  static Attempt sideEffectingAttempt() {
    return new Attempt(true, false);
  }

  /**
   * Creates the shared attempt of one paged read.
   *
   * <p>A paged read is many requests and one operation, so its attempt is created once by the
   * caller that drives the pages and handed to every request of that read. That is what makes the
   * reconnect budget cover the read rather than each page, and what tells {@link #recover} to
   * report a replacement instead of repeating a request whose cursor died with its session.
   *
   * <p>Reading a catalogue changes nothing on the server, so this is also the attempt that may act
   * on a dismissal this owner caused: a page the owner's own replacement threw away is worth
   * recovering, and the recovery is the reader being told to start over rather than the page being
   * sent again.
   *
   * @return an attempt whose single reconnect covers every request of the read, never {@code null}
   */
  static Attempt pagedAttempt() {
    return new Attempt(false, true);
  }

  /**
   * Runs one operation on the current generation as part of the given attempt.
   *
   * @param operation produces the SDK call for a given client, never {@code null}
   * @param attempt the attempt this operation belongs to, never {@code null}
   * @param <T> the operation result type
   * @return a stage completing with the operation result, never {@code null}
   */
  <T> CompletableFuture<T> execute(Function<McpAsyncClient, Mono<T>> operation, Attempt attempt) {
    if (operation == null) {
      return AsyncStages.failed(new IllegalArgumentException("operation must not be null"));
    }
    CompletableFuture<T> result =
        currentGeneration().thenCompose(generation -> validate(operation, generation, attempt));
    return AsyncStages.cancellable(result, attempt::cancel);
  }

  private CompletableFuture<Generation> currentGeneration() {
    synchronized (lock) {
      if (current != null) {
        return CompletableFuture.completedFuture(current);
      }
      if (pending != null) {
        return pending.thenApply(generation -> generation);
      }
    }
    return AsyncStages.failed(new IllegalStateException(NOT_CONNECTED));
  }

  /**
   * Checks the generation with a ping, then runs the operation on it or on its replacement.
   *
   * <p>The ping is the cheapest request that proves the connection is alive without touching the
   * server's state, and it is sent before the operation rather than after it fails because a tool
   * call that reached the server may have run its side effect before the failure was reported. The
   * result of asking is remembered only when the server says it has no ping: that answer belongs to
   * the server behind this generation and dies with it.
   */
  private <T> CompletableFuture<T> validate(
      Function<McpAsyncClient, Mono<T>> operation, Generation generation, Attempt attempt) {
    if (!isCurrent(generation)) {
      // The same rule the dispatch applies, applied before anything is put on the wire. A ping is a
      // request like any other, so a generation the owner already released must not be pinged
      // either; and recovering from that ping would be worse than sending it, because it would move
      // the operation onto the generation the owner holds now, which is the one generation this
      // operation was never allowed to run on.
      return AsyncStages.failed(new IllegalStateException(NOT_CONNECTED));
    }
    if (attempt.cancelled()) {
      // The caller withdrew while this operation was waiting for the handshake it joined, so
      // nothing is sent at all, not even the validation ping.
      return AsyncStages.failed(new CancellationException());
    }
    if (generation.pingUnsupported()) {
      return invoke(operation, generation, attempt);
    }
    CompletableFuture<Object> ping = AsyncStages.fromMono(generation.client().ping());
    attempt.track(ping);
    return ping.handle(
            (ignored, failure) -> {
              if (failure == null) {
                return invoke(operation, generation, attempt);
              }
              Throwable reported = AsyncStages.unwrap(failure);
              if (cancelled(reported)) {
                return AsyncStages.<T>failed(reported);
              }
              if (McpFailures.isPingUnsupported(reported)) {
                generation.markPingUnsupported();
                return invoke(operation, generation, attempt);
              }
              return recover(operation, generation, attempt, reported);
            })
        .thenCompose(Function.identity());
  }

  /**
   * Spends this operation's single reconnect on one replacement generation, then runs on it.
   *
   * <p>The budget is one per {@code execute} rather than one per failure or one per stage, because
   * a generation whose connection is gone twice in a row is a server that is not coming back, and
   * an owner that kept replacing it would start one process after another on behalf of a single
   * tool call. The validation and the call it guards therefore draw on the same budget: an
   * operation whose ping already bought a replacement reports the call's own connection loss
   * instead of buying a third generation.
   *
   * <p>The caller is failed with what actually stopped its operation. A replacement that could not
   * be built is attached to that failure rather than substituted for it: the caller asked for a
   * tool call, and the reason it did not happen is the failure, not the cleanup that came after.
   *
   * <p>What happens once the replacement exists is the attempt's decision. A single request is
   * repeated on it, because the request carries everything the new session needs. A paged read is
   * not: the request that failed carries a cursor the dead session issued, so the reader is told
   * the connection was replaced and starts its catalogue again from the first page. Either way the
   * replacement is already adopted, so the next request lands on it without asking for another one.
   */
  private <T> CompletableFuture<T> recover(
      Function<McpAsyncClient, Mono<T>> operation,
      Generation stale,
      Attempt attempt,
      Throwable failure) {
    if (!attempt.spendReconnect()) {
      return AsyncStages.failed(failure);
    }
    return replaceGeneration(stale)
        .handle(
            (replacement, replacementFailure) -> {
              if (replacementFailure != null) {
                return AsyncStages.<T>failed(
                    annotate(failure, AsyncStages.unwrap(replacementFailure)));
              }
              if (!attempt.repeatsOnReplacement()) {
                return AsyncStages.<T>failed(new McpConnectionReplacedException(failure));
              }
              return invoke(operation, replacement, attempt);
            })
        .thenCompose(Function.identity());
  }

  /**
   * Reports whether a failure is a caller or run cancellation rather than a connection problem.
   *
   * <p>Cancellation is the one failure that says nothing about the connection. A cancelled agent
   * run cancels its tool call, which cancels the validation ping in flight, and treating that as a
   * lost connection would close a healthy generation: a stdio server process would be killed and a
   * streamable HTTP session dropped because one caller changed its mind. It is matched by type
   * rather than by message because {@code CancellationException} is what {@code CompletableFuture}
   * itself raises.
   */
  private static boolean cancelled(Throwable failure) {
    return failure instanceof CancellationException;
  }

  /**
   * Dispatches the operation on one generation, and repeats it once if the connection was lost.
   *
   * <p>This runs at most twice per {@code execute}: once on the generation the operation was given,
   * and once on the generation that replaced it. The two guards below are checked on both passes,
   * because both windows are real. The first pass can be handed a generation the owner released
   * while the validation ping was in flight; the second is dispatched from the completion of a
   * replacement handshake the caller may have withdrawn from or the owner may already have closed.
   */
  private <T> CompletableFuture<T> invoke(
      Function<McpAsyncClient, Mono<T>> operation, Generation generation, Attempt attempt) {
    if (!isCurrent(generation)) {
      // The owner moved on while this operation was waiting for the handshake it joined, so the
      // generation it was handed is already being released. Running through it would send a request
      // on a client that is being torn down and, on the SDK's lazy initialization path, could drive
      // a second handshake on a connection the caller believes is shut down. The caller is told the
      // connection is not open, which is what every other operation without a generation is told,
      // and no transport is created to satisfy it.
      return AsyncStages.failed(new IllegalStateException(NOT_CONNECTED));
    }
    if (attempt.cancelled()) {
      // The caller withdrew while this operation was waiting for the handshake it joined. Applying
      // the operation subscribes to the SDK call, and subscribing is what puts the request on the
      // wire, so dispatching here would run a tool's side effect on the server for a caller that is
      // already gone. Nothing is closed and no reconnect is attempted: cancellation ends one
      // operation, not the generation it would have used.
      return AsyncStages.failed(new CancellationException());
    }
    CompletableFuture<T> inFlight;
    try {
      Mono<T> call = operation.apply(generation.client());
      if (call == null) {
        return AsyncStages.failed(new IllegalStateException("operation produced no call"));
      }
      inFlight = AsyncStages.fromMono(call);
    } catch (RuntimeException failure) {
      return AsyncStages.failed(failure);
    }
    attempt.track(inFlight);
    return inFlight
        .handle(
            (result, failure) -> {
              if (failure == null) {
                return CompletableFuture.completedFuture(result);
              }
              Throwable reported = AsyncStages.unwrap(failure);
              return retryable(reported, generation, attempt)
                  ? recover(operation, generation, attempt, reported)
                  : AsyncStages.<T>failed(reported);
            })
        .thenCompose(Function.identity());
  }

  /**
   * Decides whether a failed call may be recovered on a replacement generation.
   *
   * <p>This is the call stage's own guard, separate from the validation guard, because the two
   * answer different questions. Validation only has to decide whether the ping failure says
   * anything about the connection; a call also has to decide whether acting on the failure is safe.
   * That is why the two are not the same predicate: a ping replaces the generation on any failure,
   * while a call is recovered only when the SDK named the session the server no longer has. They
   * share only the cancellation rule.
   *
   * <p>A cancelled call is never recovered: the caller asked for it to stop. The second arm is a
   * generation this owner closed, whose other in-flight requests the SDK dismisses with an untyped
   * failure. Reading that state off the generation is what keeps the decision out of message
   * matching, which an untyped dismissal would otherwise invite — but the dismissal itself proves
   * nothing about the server. The request may have arrived and run before the close, so the arm is
   * open only to an operation whose attempt says a dismissal is safe to act on: an idempotent read,
   * never a tool call. When the close came from {@link #close()} rather than from a replacement,
   * such a read finds no generation and fails on the connect requirement, which is the correct
   * answer: an explicit close must not bring the server back.
   */
  private static boolean retryable(Throwable failure, Generation generation, Attempt attempt) {
    if (cancelled(failure)) {
      return false;
    }
    return McpFailures.isRepeatableConnectionLoss(failure)
        || (attempt.recoversFromOwnerDismissal() && generation.closedByOwner());
  }

  /**
   * Reports whether the owner still holds this generation.
   *
   * <p>Generations are told apart by the ticket that created them, so the check is a comparison of
   * two numbers. Nothing is created, called, or closed while {@code lock} is held: the failure the
   * answer may lead to is built by the caller, outside the lock.
   */
  private boolean isCurrent(Generation generation) {
    synchronized (lock) {
      return current != null && current.epoch() == generation.epoch();
    }
  }

  /**
   * Tracks the in-flight call of one operation so cancellation can reach it, and carries that
   * operation's single reconnect budget and its two recovery rules.
   *
   * <p>An operation is not always one request. A paged read is one attempt spanning many requests,
   * and it is the attempt that decides what happens after a replacement: a single request repeats
   * itself, a paged read cannot and asks its reader to start over instead.
   *
   * <p>The two rules are independent and answer different questions. {@link
   * #repeatsOnReplacement()} asks what to do once a replacement exists, and {@link
   * #recoversFromOwnerDismissal()} asks whether a request this owner's own close threw away may be
   * acted on at all. A paged read says no to the first and yes to the second: it must not re-send a
   * page, and it is safe to restart. A tool call says the opposite: it carries everything a new
   * session needs, and it must never be sent twice on the strength of a dismissal that proves
   * nothing about what the server already did.
   */
  static final class Attempt {

    private final boolean repeatOnReplacement;
    private final boolean recoverFromOwnerDismissal;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean reconnect = new AtomicBoolean(true);
    private final AtomicReference<CompletableFuture<?>> inFlight = new AtomicReference<>();

    private Attempt(boolean repeatOnReplacement, boolean recoverFromOwnerDismissal) {
      this.repeatOnReplacement = repeatOnReplacement;
      this.recoverFromOwnerDismissal = recoverFromOwnerDismissal;
    }

    /**
     * Reports whether the operation that failed may be dispatched again on the replacement.
     *
     * <p>It is a property of the operation, not of the failure: the failure is the same lost
     * connection either way. What differs is whether the request means the same thing to a session
     * that never issued it.
     */
    boolean repeatsOnReplacement() {
      return repeatOnReplacement;
    }

    /**
     * Reports whether this operation may be recovered when the failure is the SDK dismissing it
     * because this owner closed the generation underneath it.
     *
     * <p>Such a dismissal is evidence about the owner, never about the server: the request may have
     * arrived and run before the close. Only an operation that changes nothing — a catalogue read —
     * can afford to act on it, and even then the recovery is a restart rather than a repeat. An
     * operation that may have had a side effect is reported instead, because the alternative is
     * running that side effect a second time on a fresh session.
     */
    boolean recoversFromOwnerDismissal() {
      return recoverFromOwnerDismissal;
    }

    /**
     * Reports whether the caller already withdrew, so the dispatch can be suppressed before the
     * operation is applied. A cancellation arriving after this answer is still caught by {@link
     * #track(CompletableFuture)}, which cancels the call the dispatch produced.
     */
    boolean cancelled() {
      return cancelled.get();
    }

    /**
     * Takes this operation's single reconnect, and reports whether it was still there to take.
     *
     * <p>The budget is a claim rather than a counter so that two failures racing on the same
     * operation cannot both decide to replace a generation. Replacing a connection can cost a
     * process launch and a fresh handshake, so an operation is allowed to cause exactly one.
     */
    boolean spendReconnect() {
      return reconnect.compareAndSet(true, false);
    }

    void track(CompletableFuture<?> call) {
      inFlight.set(call);
      if (cancelled.get()) {
        call.cancel(true);
      }
    }

    void cancel() {
      cancelled.set(true);
      CompletableFuture<?> call = inFlight.get();
      if (call != null) {
        call.cancel(true);
      }
    }
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
      startGeneration(ticket)
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
   * Replaces the generation an operation could not validate, once per generation.
   *
   * <p>The stale generation is dropped and released <em>before</em> the replacement is created. An
   * owner that built the replacement first would run two servers at once — a second child process,
   * or a second HTTP session — for as long as the old one took to go away, which is exactly the
   * cost owning the connection is supposed to bound.
   *
   * <p>The replacement handshake is published as the pending one inside the lock, before the stale
   * close is started. Closing a client dismisses its in-flight requests on the closing thread, so a
   * sibling operation fails and re-enters this method on this very thread; a handshake published
   * after the close began would arrive too late for that sibling, which would then either fail with
   * no connection or start a second generation of its own.
   *
   * <p>Nothing external runs while {@code lock} is held. The stale close is started once it is
   * released, and the replacement's transport is created later still, from the callback that close
   * completes.
   *
   * @param stale the generation the caller failed to validate, never {@code null}
   * @return the generation to continue on, never {@code null}
   */
  private CompletableFuture<Generation> replaceGeneration(Generation stale) {
    CompletableFuture<Void> staleClosure;
    CompletableFuture<Generation> replacement;
    long ticket;
    synchronized (lock) {
      if (current == null) {
        // The owner moved on: it was closed, or a sibling already dropped this generation and its
        // replacement is on the way. Either way this operation closes nothing and starts nothing.
        // It joins the handshake if there is one, and otherwise fails on the same explicit connect
        // requirement every operation without a generation fails on.
        return pending != null
            ? pending.thenApply(generation -> generation)
            : AsyncStages.failed(new IllegalStateException(NOT_CONNECTED));
      }
      if (current.epoch() != stale.epoch()) {
        // A sibling already replaced the generation this operation failed on, so the work is done.
        // Replacing again would throw away a connection nothing has shown to be broken.
        return CompletableFuture.completedFuture(current);
      }
      current = null;
      staleClosure = new CompletableFuture<>();
      closing = staleClosure;
      ticket = ++epoch;
      replacement = adopt();
    }
    staleClosure.whenComplete(
        (released, cleanupFailure) -> continueReplacement(ticket, replacement, cleanupFailure));
    release(stale, staleClosure);
    return replacement.thenApply(generation -> generation);
  }

  /**
   * Creates the replacement generation once the stale one is really gone.
   *
   * <p>A stale close that failed creates nothing. What it left behind is in an unknown state — a
   * stdio process that would not die, an HTTP session that was not released — and starting a second
   * server next to it is not a recovery, so the owner ends disconnected and the caller is told.
   *
   * <p>A ticket the owner has already moved past creates nothing either: it means the owner was
   * closed while the stale generation was being released, and a replacement would then be a server
   * process started for an owner that is shut down. The check is an early exit rather than the
   * guarantee: a close that lands after it is still caught by {@link #publish(long, Generation)},
   * which refuses the generation and hands it back to be released.
   */
  private void continueReplacement(
      long ticket, CompletableFuture<Generation> replacement, Throwable cleanupFailure) {
    if (cleanupFailure != null) {
      settle(ticket, replacement, null, cleanupFailure);
      return;
    }
    if (!isCurrentTicket(ticket)) {
      settle(ticket, replacement, null, new IllegalStateException(NOT_CONNECTED));
      return;
    }
    try {
      startGeneration(ticket)
          .whenComplete((generation, failure) -> settle(ticket, replacement, generation, failure));
    } catch (RuntimeException | Error failure) {
      // Same rule as connectGeneration: the promise is already published, so a throwable that is
      // not an ordinary failure has to settle it before this frame unwinds, or the owner stays in
      // connecting forever and every later connect joins a promise nobody is left to complete.
      settle(ticket, replacement, null, failure);
      throw failure;
    }
  }

  /** Reports whether the owner is still waiting for the generation this ticket was issued for. */
  private boolean isCurrentTicket(long ticket) {
    synchronized (lock) {
      return ticket == epoch;
    }
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
   *
   * @param ticket the ticket the generation is tagged with, which is what later tells it apart from
   *     the generation that replaced it
   */
  private CompletableFuture<Generation> startGeneration(long ticket) {
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
    Generation generation = new Generation(ticket, client);
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

  /**
   * Releases the generation whose handshake failed, and settles {@code handshake} even when
   * starting that release does not return.
   *
   * <p>This runs from the SDK's error consumer, which on every real transport is a stack the
   * subscription in {@link #startGeneration(long)} already returned from. Nothing that started the
   * handshake is left to catch a throwable here, so an {@link Error} leaving {@link
   * Generation#close()} — it settles its own memoized closure and rethrows — would take the only
   * remaining path to {@code handshake} with it. The promise is already published by then, so the
   * owner would stay in connecting forever and every later {@link #connect()} and {@link #close()}
   * would join a promise nobody is left to complete.
   *
   * <p>It is the same guard {@link #release(Generation, CompletableFuture)} applies one frame up,
   * and it reports the same thing this method reports on every other path: the handshake failure
   * the caller asked about, with the cleanup trouble attached to it rather than substituted for it.
   * The throwable then keeps travelling as the same instance, because settling a promise is not a
   * reason to swallow an {@code Error}.
   */
  private static void closeAfterFailedHandshake(
      Generation generation, Throwable failure, CompletableFuture<Generation> handshake) {
    CompletableFuture<Void> cleanup;
    try {
      cleanup = generation.close();
    } catch (RuntimeException | Error cleanupFailure) {
      report(handshake, failure, cleanupFailure);
      throw cleanupFailure;
    }
    cleanup.whenComplete((ignored, cleanupFailure) -> report(handshake, failure, cleanupFailure));
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
   * <p>Completing in a {@code finally} keeps any remaining trouble in the annotation from skipping
   * it: what gets dropped is the cleanup failure, never the failure a caller is waiting for.
   */
  private static void report(
      CompletableFuture<Generation> target, Throwable failure, Throwable cleanupFailure) {
    Throwable reported = AsyncStages.unwrap(failure);
    try {
      annotate(reported, cleanupFailure == null ? null : AsyncStages.unwrap(cleanupFailure));
    } finally {
      target.completeExceptionally(reported);
    }
  }

  /**
   * Attaches context to the failure a caller is waiting for, and returns that same failure.
   *
   * <p>A throwable refuses to suppress itself, so a transport or a recovery that reported the very
   * instance that started the trouble — a cached or shared throwable — would otherwise turn the
   * annotation into a second failure. Throwables do not redefine equality, so comparing them is the
   * identity check that rejection is based on.
   */
  private static Throwable annotate(Throwable failure, Throwable annotation) {
    if (annotation != null && !annotation.equals(failure)) {
      failure.addSuppressed(annotation);
    }
    return failure;
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

  /**
   * One transport and one client, closed at most once, tagged with the ticket that created it.
   *
   * <p>The ticket makes a generation identifiable without comparing references. A generation is an
   * identity rather than a value — two generations are never interchangeable, however alike they
   * look — and the owner's ticket counter only moves forward, so no two generations ever carry the
   * same one.
   *
   * <p>{@code pingUnsupported} is the only thing this class remembers about a server, and it is
   * deliberately remembered per generation: the next generation may be a different server process,
   * or a different HTTP session on a different node, which is free to implement ping.
   */
  private static final class Generation {

    private final long epoch;
    private final McpAsyncClient client;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean pingUnsupported = new AtomicBoolean();
    private final CompletableFuture<Void> closure = new CompletableFuture<>();

    Generation(long epoch, McpAsyncClient client) {
      this.epoch = epoch;
      this.client = client;
    }

    long epoch() {
      return epoch;
    }

    McpAsyncClient client() {
      return client;
    }

    /** Reports whether this server already answered a ping with {@code -32601}. */
    boolean pingUnsupported() {
      return pingUnsupported.get();
    }

    /**
     * Reports whether this owner closed this generation.
     *
     * <p>It is the same flag {@link #close()} claims, read rather than taken. A call dismissed by
     * that close failed because of something this owner did, which is what lets an operation
     * recognise the SDK's untyped dismissal without inspecting the failure it carries. Recognising
     * it is not permission to repeat it: whether the dismissed operation may act on that answer is
     * the attempt's decision, because a request the server may already have run is not made safe by
     * knowing who closed the connection.
     */
    boolean closedByOwner() {
      return closed.get();
    }

    /**
     * Records that this server has no ping, so it is not asked again.
     *
     * <p>Setting it twice is the ordinary case rather than a race to guard: two operations that
     * validate at the same time both learn the same answer, and both write the same value.
     */
    void markPingUnsupported() {
      pingUnsupported.set(true);
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
