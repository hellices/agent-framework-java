package io.github.hellices.agentframework.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hellices.agentframework.mcp.internal.McpClientTransportFactory;
import io.github.hellices.agentframework.mcp.internal.McpOwnedClientSettings;
import io.github.hellices.agentframework.mcp.internal.OwnedMcpClientLifecycle;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Connect behaviour of the owned lifecycle.
 *
 * <p>Every test drives the handshake through scripted in-memory transports, so no process is
 * started and no socket is opened. The transport factory counts how many generations were asked
 * for, which is the observable that separates "one generation" from "one generation per caller".
 */
class OwnedMcpClientConnectTest {

  private static final Duration SETTLE = Duration.ofSeconds(5);

  @Test
  void connectDrivesExactlyOneHandshakeAndOwnsTheGeneration() {
    InMemoryMcpTransport transport = new InMemoryMcpTransport().answeringPing();
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(transport);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());

    lifecycle.connect().join();

    assertThat(factory.createdCount()).isEqualTo(1);
    assertThat(transport.methodsSent())
        .containsExactly(McpSchema.METHOD_INITIALIZE, McpSchema.METHOD_NOTIFICATION_INITIALIZED);
    assertThat(transport.closeCount()).isZero();
  }

  @Test
  void repeatedConnectWhileConnectedCreatesNoSecondGeneration() {
    InMemoryMcpTransport transport = new InMemoryMcpTransport().answeringPing();
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(transport);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());

    lifecycle.connect().join();
    lifecycle.connect().join();
    lifecycle.connect().join();

    assertThat(factory.createdCount()).isEqualTo(1);
    assertThat(transport.countOf(McpSchema.METHOD_INITIALIZE)).isEqualTo(1);
  }

  @Test
  void concurrentConnectsCoalesceOntoOneHandshake() {
    InMemoryMcpTransport transport =
        new InMemoryMcpTransport().answeringPing().withholding(McpSchema.METHOD_INITIALIZE);
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(transport);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());

    CompletableFuture<Void> first = lifecycle.connect();
    CompletableFuture<Void> second = lifecycle.connect();

    assertThat(first).isNotDone();
    assertThat(second).isNotDone();
    assertThat(factory.createdCount()).isEqualTo(1);

    transport.releaseWithheld();

    assertThat(first).succeedsWithin(SETTLE);
    assertThat(second).succeedsWithin(SETTLE);
    assertThat(transport.countOf(McpSchema.METHOD_INITIALIZE)).isEqualTo(1);
  }

  @Test
  void callerCancellationCannotWedgeTheHandshake() {
    InMemoryMcpTransport transport =
        new InMemoryMcpTransport().answeringPing().withholding(McpSchema.METHOD_INITIALIZE);
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(transport);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());

    CompletableFuture<Void> cancelled = lifecycle.connect();
    assertThat(cancelled.cancel(true)).isTrue();

    transport.releaseWithheld();

    assertThat(lifecycle.connect()).succeedsWithin(SETTLE);
    assertThat(factory.createdCount()).isEqualTo(1);
    assertThat(transport.countOf(McpSchema.METHOD_INITIALIZE)).isEqualTo(1);
  }

  @Test
  void aFailedHandshakeClosesTheNewGenerationAndLeavesTheOwnerDisconnected() {
    InMemoryMcpTransport failing =
        new InMemoryMcpTransport()
            .failingSend(McpSchema.METHOD_INITIALIZE, () -> new IllegalStateException("no server"));
    InMemoryMcpTransport healthy = new InMemoryMcpTransport().answeringPing();
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(failing, healthy);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());

    assertThatThrownBy(() -> lifecycle.connect().join())
        .hasRootCauseInstanceOf(IllegalStateException.class);
    assertThat(failing.closeCount()).isEqualTo(1);

    lifecycle.connect().join();

    assertThat(factory.createdCount()).isEqualTo(2);
    assertThat(healthy.countOf(McpSchema.METHOD_INITIALIZE)).isEqualTo(1);
  }

  @Test
  void aFailedHandshakeReportsACleanupFailureAsSuppressed() {
    InMemoryMcpTransport failing =
        new InMemoryMcpTransport()
            .failingSend(McpSchema.METHOD_INITIALIZE, () -> new IllegalStateException("no server"))
            .failingClose(() -> new IllegalStateException("cleanup failed"));
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(failing);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());

    assertThatThrownBy(() -> lifecycle.connect().join())
        .satisfies(
            failure ->
                assertThat(failure.getCause().getSuppressed())
                    .anySatisfy(suppressed -> assertThat(suppressed).hasMessage("cleanup failed")));
  }

  @Test
  void aTransportFactoryFailureLeavesTheOwnerDisconnected() {
    ScriptedMcpTransportFactory empty = new ScriptedMcpTransportFactory();
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(empty, settings());

    assertThatThrownBy(() -> lifecycle.connect().join())
        .hasRootCauseInstanceOf(IllegalStateException.class);
    assertThat(empty.createdCount()).isZero();
  }

  @Test
  void aClientBuildFailureClosesTheTransportOnceAndLeavesTheOwnerDisconnected() {
    ClientHostileMcpTransport hostile = new ClientHostileMcpTransport();
    AtomicInteger created = new AtomicInteger();
    McpClientTransportFactory factory =
        () -> {
          created.incrementAndGet();
          return hostile;
        };
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());

    // Assigning the stage is itself an assertion: connect() must report a build failure through the
    // returned stage, so a synchronous throw fails on this line rather than on the next one.
    CompletableFuture<Void> first = lifecycle.connect();

    assertThatThrownBy(first::join)
        .hasRootCauseInstanceOf(IllegalStateException.class)
        .hasRootCauseMessage("transport refuses to negotiate");
    assertThat(hostile.closeCount()).isEqualTo(1);

    // A failed start must leave nothing adopted, so the next connect has to ask for a new transport
    // and clean up after itself exactly as the first one did.
    assertThatThrownBy(() -> lifecycle.connect().join())
        .hasRootCauseInstanceOf(IllegalStateException.class);
    assertThat(created.get()).isEqualTo(2);
    assertThat(hostile.closeCount()).isEqualTo(2);
  }

  @Test
  void rejectsMissingCollaborators() {
    assertThatThrownBy(() -> new OwnedMcpClientLifecycle(null, settings()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("transportFactory must not be null");
    assertThatThrownBy(() -> new OwnedMcpClientLifecycle(new ScriptedMcpTransportFactory(), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("settings must not be null");
    assertThatThrownBy(
            () ->
                new McpOwnedClientSettings(
                    new PermissiveJsonSchemaValidator(), Duration.ZERO, Duration.ofSeconds(5)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("requestTimeout must be positive");
  }

  private static McpOwnedClientSettings settings() {
    return new McpOwnedClientSettings(
        new PermissiveJsonSchemaValidator(), Duration.ofSeconds(5), Duration.ofSeconds(5));
  }
}
