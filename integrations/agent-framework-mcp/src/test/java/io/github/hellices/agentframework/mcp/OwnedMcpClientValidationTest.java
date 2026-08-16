package io.github.hellices.agentframework.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.hellices.agentframework.mcp.internal.McpClientTransportFactory;
import io.github.hellices.agentframework.mcp.internal.McpOwnedClientSettings;
import io.github.hellices.agentframework.mcp.internal.OwnedMcpClientLifecycle;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpTransportException;
import java.time.Duration;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Validation decides when the owner throws a connection away.
 *
 * <p>A stdio server is a child process and a streamable HTTP session is server state, so replacing
 * a generation is expensive and is bounded to once per operation. These tests count transports
 * created, pings sent, and transports closed, because those counts are the whole contract.
 */
class OwnedMcpClientValidationTest {

  private static final Duration SETTLE = Duration.ofSeconds(5);

  @Test
  void pingsBeforeEveryOperation() {
    InMemoryMcpTransport transport = toolServer().answeringPing();
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(transport);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());
    lifecycle.connect().join();

    listTools(lifecycle).join();
    listTools(lifecycle).join();

    assertThat(transport.countOf(McpSchema.METHOD_PING)).isEqualTo(2);
    assertThat(transport.countOf(McpSchema.METHOD_TOOLS_LIST)).isEqualTo(2);
    assertThat(factory.createdCount()).isEqualTo(1);
  }

  @Test
  void cachesAnUnsupportedPingAndKeepsUsingTheGeneration() {
    InMemoryMcpTransport transport =
        toolServer()
            .answeringWithError(
                McpSchema.METHOD_PING, McpSchema.ErrorCodes.METHOD_NOT_FOUND, "unknown method");
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(transport);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());
    lifecycle.connect().join();

    listTools(lifecycle).join();
    listTools(lifecycle).join();
    listTools(lifecycle).join();

    assertThat(transport.countOf(McpSchema.METHOD_PING)).isEqualTo(1);
    assertThat(transport.countOf(McpSchema.METHOD_TOOLS_LIST)).isEqualTo(3);
    assertThat(transport.closeCount()).isZero();
    assertThat(factory.createdCount()).isEqualTo(1);
  }

  @Test
  void probesPingAgainOnAFreshGeneration() {
    InMemoryMcpTransport first =
        toolServer()
            .answeringWithError(
                McpSchema.METHOD_PING, McpSchema.ErrorCodes.METHOD_NOT_FOUND, "unknown method");
    InMemoryMcpTransport second =
        toolServer()
            .answeringWithError(
                McpSchema.METHOD_PING, McpSchema.ErrorCodes.METHOD_NOT_FOUND, "unknown method");
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(first, second);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());

    lifecycle.connect().join();
    listTools(lifecycle).join();
    listTools(lifecycle).join();
    lifecycle.close().join();
    lifecycle.connect().join();
    listTools(lifecycle).join();

    assertThat(first.countOf(McpSchema.METHOD_PING)).isEqualTo(1);
    assertThat(second.countOf(McpSchema.METHOD_PING)).isEqualTo(1);
  }

  @Test
  void replacesTheGenerationWhenValidationFails() {
    InMemoryMcpTransport first =
        toolServer()
            .answeringWithError(
                McpSchema.METHOD_PING, McpSchema.ErrorCodes.INTERNAL_ERROR, "ping failed");
    InMemoryMcpTransport second = toolServer().answeringPing();
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(first, second);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());
    lifecycle.connect().join();

    listTools(lifecycle).join();

    assertThat(factory.createdCount()).isEqualTo(2);
    assertThat(first.closeCount()).isEqualTo(1);
    assertThat(first.countOf(McpSchema.METHOD_TOOLS_LIST)).isZero();
    assertThat(second.countOf(McpSchema.METHOD_PING)).isZero();
    assertThat(second.countOf(McpSchema.METHOD_TOOLS_LIST)).isEqualTo(1);
  }

  @Test
  void replacesTheGenerationWhenAPingFailsWithAGenericTransportFailure() {
    // The ping stage does not ask whether a failure may be repeated, because a ping has no side
    // effect to repeat: any non-cancellation failure buys the one replacement. That is what keeps a
    // genuinely dead connection healed even though the call stage refuses to repeat this failure
    // type, and it is the reason narrowing the call stage's rule costs nothing operationally.
    InMemoryMcpTransport first =
        toolServer()
            .failingSend(
                McpSchema.METHOD_PING, () -> new McpTransportException("connection reset"));
    InMemoryMcpTransport second = toolServer().answeringPing();
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(first, second);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());
    lifecycle.connect().join();

    listTools(lifecycle).join();

    assertThat(factory.createdCount()).isEqualTo(2);
    assertThat(first.closeCount()).isEqualTo(1);
    assertThat(first.countOf(McpSchema.METHOD_TOOLS_LIST)).isZero();
    assertThat(second.countOf(McpSchema.METHOD_TOOLS_LIST)).isEqualTo(1);
  }

  @Test
  void coalescesConcurrentValidationFailuresOntoOneReplacement() {
    InMemoryMcpTransport first =
        toolServer()
            .answeringWithError(
                McpSchema.METHOD_PING, McpSchema.ErrorCodes.INTERNAL_ERROR, "ping failed")
            .withholding(McpSchema.METHOD_PING);
    InMemoryMcpTransport second = toolServer().withholding(McpSchema.METHOD_INITIALIZE);
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(first, second);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());
    lifecycle.connect().join();

    CompletableFuture<McpSchema.ListToolsResult> firstCall = listTools(lifecycle);
    CompletableFuture<McpSchema.ListToolsResult> secondCall = listTools(lifecycle);
    assertThat(firstCall).isNotDone();
    assertThat(secondCall).isNotDone();

    first.releaseWithheld();

    assertThat(factory.createdCount()).isEqualTo(2);
    assertThat(first.closeCount()).isEqualTo(1);

    second.releaseWithheld();

    assertThat(firstCall).succeedsWithin(SETTLE);
    assertThat(secondCall).succeedsWithin(SETTLE);
    assertThat(second.countOf(McpSchema.METHOD_TOOLS_LIST)).isEqualTo(2);
    assertThat(second.countOf(McpSchema.METHOD_PING)).isZero();
  }

  @Test
  void aFailedReplacementHandshakeLeavesTheOwnerDisconnected() {
    InMemoryMcpTransport first =
        toolServer()
            .answeringWithError(
                McpSchema.METHOD_PING, McpSchema.ErrorCodes.INTERNAL_ERROR, "ping failed");
    InMemoryMcpTransport second =
        toolServer()
            .failingSend(
                McpSchema.METHOD_INITIALIZE, () -> new IllegalStateException("handshake refused"));
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(first, second);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());
    lifecycle.connect().join();

    Throwable failure = catchThrowable(() -> listTools(lifecycle).join());

    // The caller keeps the failure that actually stopped the operation; the reason the recovery
    // could not help is attached rather than substituted. The handshake failure is asserted through
    // the stack trace because the SDK is free to wrap an initialization failure, and pinning that
    // wrapping would make this test fail on an SDK upgrade for no behavioural reason.
    assertThat(failure).hasRootCauseInstanceOf(McpError.class);
    assertThat(failure.getCause().getSuppressed())
        .singleElement()
        .satisfies(
            suppressed -> assertThat(suppressed).hasStackTraceContaining("handshake refused"));
    assertThat(factory.createdCount()).isEqualTo(2);
    assertThat(first.closeCount()).isEqualTo(1);
    assertThat(second.closeCount()).isEqualTo(1);

    assertThatThrownBy(() -> listTools(lifecycle).join())
        .rootCause()
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("connect()");
    assertThat(factory.createdCount()).isEqualTo(2);
  }

  @Test
  void aFailedStaleCloseLeavesTheOwnerDisconnectedAndCreatesNoReplacement() {
    InMemoryMcpTransport first =
        toolServer()
            .answeringWithError(
                McpSchema.METHOD_PING, McpSchema.ErrorCodes.INTERNAL_ERROR, "ping failed")
            .failingClose(() -> new IllegalStateException("close failed"));
    InMemoryMcpTransport second = toolServer().answeringPing();
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(first, second);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());
    lifecycle.connect().join();

    Throwable failure = catchThrowable(() -> listTools(lifecycle).join());

    assertThat(failure).hasRootCauseInstanceOf(McpError.class);
    assertThat(failure.getCause().getSuppressed())
        .singleElement()
        .satisfies(
            suppressed ->
                assertThat(suppressed)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("close failed"));
    assertThat(factory.createdCount()).isEqualTo(1);

    assertThatThrownBy(() -> listTools(lifecycle).join())
        .rootCause()
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("connect()");
    assertThat(factory.createdCount()).isEqualTo(1);
  }

  @Test
  void cancellingDuringValidationNeitherClosesNorReplacesTheGeneration() {
    // A cancelled agent run must not cost the process its MCP server. The ping is withheld, so the
    // cancellation lands while validation is the only thing in flight, which is precisely where a
    // classifier that treats "not a known-good failure" as "connection lost" would close a live
    // generation and ask for a second transport the factory was never given.
    InMemoryMcpTransport transport =
        toolServer().answeringPing().withholding(McpSchema.METHOD_PING);
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(transport);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());
    lifecycle.connect().join();

    CompletableFuture<McpSchema.ListToolsResult> operation = listTools(lifecycle);
    assertThat(operation.cancel(true)).isTrue();
    transport.releaseWithheld();

    assertThat(operation).isCancelled();
    assertThat(transport.closeCount()).isZero();
    assertThat(transport.countOf(McpSchema.METHOD_TOOLS_LIST)).isZero();
    assertThat(factory.createdCount()).isEqualTo(1);
  }

  @Test
  void releasesTheStaleGenerationBeforeCreatingItsReplacement() {
    // Two live servers per owner is the thing owning the connection is supposed to prevent: a stdio
    // server is a child process and an HTTP session is state the server holds, so the replacement
    // may only be built once the old one is really gone. The withheld close is the window in which
    // an owner that built the replacement first would be running both.
    InMemoryMcpTransport first =
        toolServer()
            .answeringWithError(
                McpSchema.METHOD_PING, McpSchema.ErrorCodes.INTERNAL_ERROR, "ping failed")
            .withholdingClose();
    InMemoryMcpTransport second = toolServer().answeringPing();
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(first, second);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());
    lifecycle.connect().join();

    CompletableFuture<McpSchema.ListToolsResult> operation = listTools(lifecycle);

    assertThat(first.closeCount()).isEqualTo(1);
    assertThat(factory.createdCount()).isEqualTo(1);
    assertThat(operation).isNotDone();

    first.releaseWithheldClose();

    assertThat(operation).succeedsWithin(SETTLE);
    assertThat(factory.createdCount()).isEqualTo(2);
    assertThat(second.countOf(McpSchema.METHOD_TOOLS_LIST)).isEqualTo(1);
  }

  @Test
  void closingWhileTheStaleGenerationIsBeingReleasedStartsNoReplacement() {
    // The stale close is what the replacement waits for, and releasing a child process or an HTTP
    // session is not instant, so an owner can be closed inside that window. A replacement started
    // regardless would launch a server process for an owner that is already shut down, and the
    // explicit connect requirement is exactly the promise that nothing does that.
    InMemoryMcpTransport first =
        toolServer()
            .answeringWithError(
                McpSchema.METHOD_PING, McpSchema.ErrorCodes.INTERNAL_ERROR, "ping failed")
            .withholdingClose();
    InMemoryMcpTransport second = toolServer().answeringPing();
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(first, second);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());
    lifecycle.connect().join();

    CompletableFuture<McpSchema.ListToolsResult> operation = listTools(lifecycle);
    CompletableFuture<Void> closing = lifecycle.close();
    assertThat(operation).isNotDone();

    first.releaseWithheldClose();

    assertThat(closing).succeedsWithin(SETTLE);
    assertThat(catchThrowable(operation::join)).hasRootCauseInstanceOf(McpError.class);
    assertThat(factory.createdCount()).isEqualTo(1);
    assertThat(second.closeCount()).isZero();
  }

  @Test
  void aValidationFailureAfterTheOwnerWasClosedStartsNoReplacement() {
    // Validation puts a request on the wire before every operation, so the ping is the request most
    // likely to be in flight when the owner is closed. Closing dismisses it, and the dismissal is
    // neither a cancellation nor -32601, so it arrives at the replacement path with no generation
    // left to replace. An owner that reconnected there would start a server process for a caller
    // that never asked to connect, on an owner the caller just shut down: the second scripted
    // transport is what such a reconnect would take.
    InMemoryMcpTransport transport =
        toolServer().answeringPing().withholding(McpSchema.METHOD_PING);
    InMemoryMcpTransport neverUsed = toolServer().answeringPing();
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(transport, neverUsed);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());
    lifecycle.connect().join();

    CompletableFuture<McpSchema.ListToolsResult> operation = listTools(lifecycle);
    assertThat(transport.countOf(McpSchema.METHOD_PING)).isEqualTo(1);
    assertThat(operation).isNotDone();

    assertThat(lifecycle.close()).succeedsWithin(SETTLE);
    // The server's answer arrives after the connection it belonged to is gone. It changes nothing:
    // the operation was already settled by the dismissal, and a closed transport delivers nothing.
    transport.releaseWithheld();

    // Nothing was reconnected and nothing was closed twice: one transport was ever asked for, it
    // was released exactly once, and the spare the factory still holds was never touched.
    assertThat(factory.createdCount()).isEqualTo(1);
    assertThat(neverUsed.methodsSent()).isEmpty();
    assertThat(neverUsed.closeCount()).isZero();
    assertThat(transport.closeCount()).isEqualTo(1);
    assertThat(transport.countOf(McpSchema.METHOD_TOOLS_LIST)).isZero();

    // The caller keeps what stopped its operation — the dismissal — with the reason the owner did
    // not recover attached: the same explicit connect requirement every operation without a
    // generation is failed on.
    Throwable failure = settledFailure(operation);
    assertThat(failure.getSuppressed())
        .singleElement()
        .satisfies(
            suppressed ->
                assertThat(suppressed)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("connect()"));
  }

  @Test
  void aFatalStartFailureDuringReplacementLeavesTheOwnerReusableRatherThanWedged() {
    // A replacement publishes its handshake promise before the stale generation is released, so a
    // throwable that leaves the start without settling that promise is not a failed operation but a
    // permanently wedged owner: the caller never hears back and every later connect joins a promise
    // nobody is left to complete. A ServiceConfigurationError from a consumer's classpath is not an
    // ordinary failure, so it is the input that reaches the guard.
    InMemoryMcpTransport failing =
        toolServer()
            .answeringWithError(
                McpSchema.METHOD_PING, McpSchema.ErrorCodes.INTERNAL_ERROR, "ping failed");
    InMemoryMcpTransport healthy = toolServer().answeringPing();
    AtomicInteger created = new AtomicInteger();
    McpClientTransportFactory factory =
        () -> {
          int attempt = created.incrementAndGet();
          if (attempt == 1) {
            return failing;
          }
          if (attempt == 2) {
            throw new ServiceConfigurationError("no JSON provider on this classpath");
          }
          return healthy;
        };
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());
    lifecycle.connect().join();

    CompletableFuture<McpSchema.ListToolsResult> operation = listTools(lifecycle);

    Throwable failure = settledFailure(operation);
    assertThat(failure).isInstanceOf(McpError.class);
    assertThat(failure.getSuppressed())
        .singleElement()
        .satisfies(
            suppressed ->
                assertThat(suppressed)
                    .isInstanceOf(ServiceConfigurationError.class)
                    .hasMessage("no JSON provider on this classpath"));
    assertThat(created.get()).isEqualTo(2);
    assertThat(failing.closeCount()).isEqualTo(1);

    // The owner ends disconnected rather than connecting: no handshake was left published, so this
    // operation is refused outright instead of joining one nobody is left to complete.
    assertThat(settledFailure(listTools(lifecycle)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("connect()");
    assertThat(created.get()).isEqualTo(2);

    // Disconnected and reusable: an explicit connect builds a fresh generation, and the operation
    // the fatal error stopped can be made again.
    assertThat(lifecycle.connect()).succeedsWithin(SETTLE);
    assertThat(created.get()).isEqualTo(3);
    assertThat(listTools(lifecycle)).succeedsWithin(SETTLE);
    assertThat(healthy.countOf(McpSchema.METHOD_TOOLS_LIST)).isEqualTo(1);
  }

  /**
   * Returns the failure a stage settled with, waiting under a bound that only detects a wedge.
   *
   * <p>The bound is not a wait for slow work — everything these tests drive completes on the
   * calling thread — it is how a promise nobody is left to complete is reported as a failing test
   * instead of a suite that never finishes. It is deliberately not {@code failsWithin}, which
   * accepts the timeout itself as the failure and would pass on exactly that wedge.
   */
  private static Throwable settledFailure(CompletableFuture<?> stage) {
    Throwable observed = catchThrowable(() -> stage.get(SETTLE.toMillis(), TimeUnit.MILLISECONDS));
    assertThat(observed).isInstanceOf(ExecutionException.class);
    return observed.getCause();
  }

  private static CompletableFuture<McpSchema.ListToolsResult> listTools(
      OwnedMcpClientLifecycle lifecycle) {
    return lifecycle.execute(client -> client.listTools(McpSchema.FIRST_PAGE, null));
  }

  private static InMemoryMcpTransport toolServer() {
    return new InMemoryMcpTransport()
        .answering(
            McpSchema.METHOD_TOOLS_LIST,
            params -> new McpSchema.ListToolsResult(List.of(), null, null));
  }

  private static McpOwnedClientSettings settings() {
    return new McpOwnedClientSettings(
        new PermissiveJsonSchemaValidator(), Duration.ofSeconds(5), Duration.ofSeconds(5));
  }
}
