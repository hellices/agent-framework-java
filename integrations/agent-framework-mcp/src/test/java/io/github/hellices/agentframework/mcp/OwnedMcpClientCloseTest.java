package io.github.hellices.agentframework.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hellices.agentframework.mcp.internal.McpOwnedClientSettings;
import io.github.hellices.agentframework.mcp.internal.OwnedMcpClientLifecycle;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Close ends one generation, not the owner.
 *
 * <p>Each test asserts on the transports the factory handed out, because the transport records
 * exactly what the owner did to it: how many handshakes it drove and how many times it closed it.
 */
class OwnedMcpClientCloseTest {

  private static final Duration SETTLE = Duration.ofSeconds(5);

  @Test
  void closeEndsTheGenerationItCreated() {
    InMemoryMcpTransport transport = new InMemoryMcpTransport().answeringPing();
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(transport);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());
    lifecycle.connect().join();

    lifecycle.close().join();

    assertThat(transport.closeCount()).isEqualTo(1);
    assertThat(transport.isClosed()).isTrue();
    assertThat(factory.createdCount()).isEqualTo(1);
  }

  @Test
  void repeatedCloseClosesTheTransportOnlyOnce() {
    InMemoryMcpTransport transport = new InMemoryMcpTransport().answeringPing();
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(transport);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());
    lifecycle.connect().join();

    lifecycle.close().join();
    lifecycle.close().join();
    lifecycle.close().join();

    assertThat(transport.closeCount()).isEqualTo(1);
  }

  @Test
  void closeWithoutConnectCreatesNothingAndSucceeds() {
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory();
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());

    lifecycle.close().join();

    assertThat(factory.createdCount()).isZero();
  }

  @Test
  void closeDuringConnectWaitsForTheHandshakeToSettleAndThenCloses() {
    InMemoryMcpTransport transport =
        new InMemoryMcpTransport().answeringPing().withholding(McpSchema.METHOD_INITIALIZE);
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(transport);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());

    CompletableFuture<Void> connecting = lifecycle.connect();
    CompletableFuture<Void> closing = lifecycle.close();

    assertThat(closing).isNotDone();
    assertThat(transport.closeCount()).isZero();

    transport.releaseWithheld();

    assertThat(closing).succeedsWithin(SETTLE);
    assertThat(connecting).succeedsWithin(SETTLE);
    assertThat(transport.closeCount()).isEqualTo(1);
    assertThat(factory.createdCount()).isEqualTo(1);
  }

  @Test
  void closeSurfacesACleanupFailureAndStillReleasesTheGeneration() {
    InMemoryMcpTransport failing =
        new InMemoryMcpTransport()
            .answeringPing()
            .failingClose(() -> new IllegalStateException("close failed"));
    InMemoryMcpTransport replacement = new InMemoryMcpTransport().answeringPing();
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(failing, replacement);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());
    lifecycle.connect().join();

    assertThatThrownBy(() -> lifecycle.close().join())
        .hasRootCauseInstanceOf(IllegalStateException.class)
        .hasRootCauseMessage("close failed");

    lifecycle.connect().join();
    assertThat(factory.createdCount()).isEqualTo(2);
    assertThat(replacement.countOf(McpSchema.METHOD_INITIALIZE)).isEqualTo(1);
  }

  @Test
  void connectAfterCloseCreatesAFreshGeneration() {
    InMemoryMcpTransport first = new InMemoryMcpTransport().answeringPing();
    InMemoryMcpTransport second = new InMemoryMcpTransport().answeringPing();
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(first, second);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());

    lifecycle.connect().join();
    lifecycle.close().join();
    lifecycle.connect().join();

    assertThat(factory.createdCount()).isEqualTo(2);
    assertThat(first.closeCount()).isEqualTo(1);
    assertThat(second.isClosed()).isFalse();
    assertThat(second.methodsSent())
        .containsExactly(McpSchema.METHOD_INITIALIZE, McpSchema.METHOD_NOTIFICATION_INITIALIZED);

    lifecycle.close().join();
    assertThat(second.closeCount()).isEqualTo(1);
    assertThat(factory.createdCount()).isEqualTo(2);
  }

  @Test
  void anOrphanedHandshakeIsClosedOnceAndNeverBecomesCurrent() {
    InMemoryMcpTransport orphaned =
        new InMemoryMcpTransport().answeringPing().withholding(McpSchema.METHOD_INITIALIZE);
    InMemoryMcpTransport reopened = new InMemoryMcpTransport().answeringPing();
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(orphaned, reopened);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());

    CompletableFuture<Void> connecting = lifecycle.connect();
    CompletableFuture<Void> closing = lifecycle.close();

    // A reopen while the orphaned handshake is still running moves the owner two generations away
    // from the ticket that handshake carries, which is the state its late arrival has to survive.
    lifecycle.connect().join();
    orphaned.releaseWithheld();

    assertThat(connecting).succeedsWithin(SETTLE);
    assertThat(closing).succeedsWithin(SETTLE);
    assertThat(orphaned.closeCount()).isEqualTo(1);
    assertThat(orphaned.isClosed()).isTrue();

    // The late generation was never adopted: the owner still holds the one it opened afterwards, so
    // closing now releases that one and asks for no further transport.
    assertThat(reopened.closeCount()).isZero();
    lifecycle.close().join();
    assertThat(reopened.closeCount()).isEqualTo(1);
    assertThat(factory.createdCount()).isEqualTo(2);
  }

  @Test
  void closeDuringConnectSurfacesTheOrphanedGenerationsCleanupFailure() {
    InMemoryMcpTransport orphaned =
        new InMemoryMcpTransport()
            .answeringPing()
            .withholding(McpSchema.METHOD_INITIALIZE)
            .failingClose(() -> new IllegalStateException("close failed"));
    InMemoryMcpTransport reopened = new InMemoryMcpTransport().answeringPing();
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(orphaned, reopened);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());

    CompletableFuture<Void> connecting = lifecycle.connect();
    CompletableFuture<Void> closing = lifecycle.close();
    orphaned.releaseWithheld();

    // Releasing an orphan is still a release someone asked for: its failure belongs to the close
    // caller rather than to the connect caller, whose handshake did succeed.
    assertThatThrownBy(closing::join)
        .hasRootCauseInstanceOf(IllegalStateException.class)
        .hasRootCauseMessage("close failed");
    assertThat(connecting).succeedsWithin(SETTLE);
    assertThat(orphaned.closeCount()).isEqualTo(1);

    lifecycle.connect().join();
    assertThat(factory.createdCount()).isEqualTo(2);
    assertThat(reopened.countOf(McpSchema.METHOD_INITIALIZE)).isEqualTo(1);
  }

  @Test
  void closeDuringAHandshakeThatFailsReleasesNothingTwice() {
    InMemoryMcpTransport failing =
        new InMemoryMcpTransport()
            .answeringWithError(McpSchema.METHOD_INITIALIZE, -32000, "no server")
            .withholding(McpSchema.METHOD_INITIALIZE);
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(failing);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());

    CompletableFuture<Void> connecting = lifecycle.connect();
    CompletableFuture<Void> closing = lifecycle.close();
    failing.releaseWithheld();

    // A handshake that failed already closed the generation it built, so there is nothing left for
    // close to release and nothing to report: the failure belongs to the connect caller.
    assertThat(closing).succeedsWithin(SETTLE);
    assertThatThrownBy(connecting::join).hasRootCauseInstanceOf(RuntimeException.class);
    assertThat(failing.closeCount()).isEqualTo(1);
  }

  @Test
  void aCleanupThatThrowsInsteadOfFailingItsPublisherStillFailsTheCloseStage() {
    InMemoryMcpTransport throwing =
        new InMemoryMcpTransport()
            .answeringPing()
            .throwingClose(() -> new IllegalStateException("close threw"));
    InMemoryMcpTransport reopened = new InMemoryMcpTransport().answeringPing();
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(throwing, reopened);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());
    lifecycle.connect().join();

    // A transport that throws where it should have returned a failed publisher must not leave the
    // generation's memoized cleanup pending: a close caller waits on it and every later close is
    // handed the very same one.
    assertThatThrownBy(() -> lifecycle.close().join())
        .hasRootCauseInstanceOf(IllegalStateException.class)
        .hasRootCauseMessage("close threw");
    assertThatThrownBy(() -> lifecycle.close().join())
        .hasRootCauseInstanceOf(IllegalStateException.class)
        .hasRootCauseMessage("close threw");
    assertThat(throwing.closeCount()).isEqualTo(1);

    lifecycle.connect().join();
    assertThat(factory.createdCount()).isEqualTo(2);
  }

  @Test
  void aCleanupThatThrowsAnErrorFailsTheStageEveryOtherCloseCallerHolds() {
    Error fatal = new NoClassDefFoundError("no transport class on this classpath");
    InMemoryMcpTransport throwing =
        new InMemoryMcpTransport().answeringPing().throwingCloseError(() -> fatal);
    InMemoryMcpTransport reopened = new InMemoryMcpTransport().answeringPing();
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(throwing, reopened);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());
    lifecycle.connect().join();

    // An Error is not an ordinary failure, so the caller that started the cleanup receives the very
    // instance rather than a stage carrying it.
    assertThatThrownBy(lifecycle::close).isSameAs(fatal);

    // Everyone else was handed a view of the promise this close published before it started the
    // cleanup. A bounded get is the hang detector: if the Error left that promise pending, this
    // wait ends in a timeout rather than in the failure the caller was owed.
    CompletableFuture<Void> shared = lifecycle.close();
    assertThatThrownBy(() -> shared.get(SETTLE.toMillis(), TimeUnit.MILLISECONDS))
        .isInstanceOf(ExecutionException.class)
        .hasRootCauseInstanceOf(NoClassDefFoundError.class);
    assertThat(throwing.closeCount()).isEqualTo(1);

    lifecycle.connect().join();
    assertThat(factory.createdCount()).isEqualTo(2);
    assertThat(reopened.countOf(McpSchema.METHOD_INITIALIZE)).isEqualTo(1);
  }

  @Test
  void closeDuringAHandshakeFailsWhenTheLateCleanupThrowsAnError() {
    Error fatal = new NoClassDefFoundError("no transport class on this classpath");
    InMemoryMcpTransport throwing =
        new InMemoryMcpTransport()
            .answeringPing()
            .withholding(McpSchema.METHOD_INITIALIZE)
            .throwingCloseError(() -> fatal);
    InMemoryMcpTransport reopened = new InMemoryMcpTransport().answeringPing();
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(throwing, reopened);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());

    CompletableFuture<Void> connecting = lifecycle.connect();
    CompletableFuture<Void> closing = lifecycle.close();
    throwing.releaseWithheld();

    // The late release starts the cleanup from inside a completion callback, and the stage that
    // callback returns is held by nobody. An Error that only travels there is one no caller can
    // ever observe, so a bounded get on the published close stage is what tells a permanent pend
    // apart from the failure this caller asked for.
    assertThatThrownBy(() -> closing.get(SETTLE.toMillis(), TimeUnit.MILLISECONDS))
        .isInstanceOf(ExecutionException.class)
        .hasRootCauseInstanceOf(NoClassDefFoundError.class);
    assertThat(connecting).succeedsWithin(SETTLE);
    assertThat(throwing.closeCount()).isEqualTo(1);

    // A later close is handed the same settled promise rather than a pending one, and the owner is
    // still reusable, because the generation was dropped before its cleanup was ever started.
    CompletableFuture<Void> shared = lifecycle.close();
    assertThatThrownBy(() -> shared.get(SETTLE.toMillis(), TimeUnit.MILLISECONDS))
        .isInstanceOf(ExecutionException.class)
        .hasRootCauseInstanceOf(NoClassDefFoundError.class);
    lifecycle.connect().join();
    assertThat(factory.createdCount()).isEqualTo(2);
    assertThat(reopened.countOf(McpSchema.METHOD_INITIALIZE)).isEqualTo(1);
  }

  @Test
  void aCleanupThatNeverCompletesLeavesTheStagePendingAndTheOwnerReusable() {
    InMemoryMcpTransport stalling = new InMemoryMcpTransport().answeringPing().withholdingClose();
    InMemoryMcpTransport reopened = new InMemoryMcpTransport().answeringPing();
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(stalling, reopened);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());
    lifecycle.connect().join();

    CompletableFuture<Void> stalled = lifecycle.close();

    assertThat(stalled).isNotDone();
    assertThat(stalling.closeCount()).isEqualTo(1);

    // The owner cleared the generation before the cleanup started, so a cleanup that never returns
    // cannot stop a reopen. No deadline is invented for work this module does not run: nothing but
    // the cleanup itself ever completes the stage, and the caller keeps the choice of waiting.
    lifecycle.connect().join();

    assertThat(factory.createdCount()).isEqualTo(2);
    assertThat(reopened.countOf(McpSchema.METHOD_INITIALIZE)).isEqualTo(1);
    assertThat(stalled).isNotDone();

    stalling.releaseWithheldClose();
    assertThat(stalled).succeedsWithin(SETTLE);
  }

  @Test
  void cancellingOneCloseStageLeavesTheCleanupAndTheOtherCallersAlone() {
    InMemoryMcpTransport stalling = new InMemoryMcpTransport().answeringPing().withholdingClose();
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(stalling);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());
    lifecycle.connect().join();

    CompletableFuture<Void> first = lifecycle.close();
    CompletableFuture<Void> second = lifecycle.close();

    assertThat(first.cancel(true)).isTrue();

    // Repeated close callers share one cleanup but must not share one promise: a caller that walks
    // away cannot decide the outcome for the callers that stayed, and cannot abandon a teardown
    // that is already running.
    assertThat(second).isNotDone();
    assertThat(stalling.closeCount()).isEqualTo(1);

    stalling.releaseWithheldClose();

    assertThat(second).succeedsWithin(SETTLE);
    assertThat(lifecycle.close()).succeedsWithin(SETTLE);
    assertThat(stalling.closeCount()).isEqualTo(1);
  }

  private static McpOwnedClientSettings settings() {
    return new McpOwnedClientSettings(
        new PermissiveJsonSchemaValidator(), Duration.ofSeconds(5), Duration.ofSeconds(5));
  }
}
