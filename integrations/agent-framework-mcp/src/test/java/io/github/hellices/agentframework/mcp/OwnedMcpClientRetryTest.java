package io.github.hellices.agentframework.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.hellices.agentframework.api.tool.FunctionTool;
import io.github.hellices.agentframework.api.tool.ToolArguments;
import io.github.hellices.agentframework.api.tool.ToolContext;
import io.github.hellices.agentframework.api.tool.ToolResult;
import io.github.hellices.agentframework.mcp.internal.McpOwnedClientSettings;
import io.github.hellices.agentframework.mcp.internal.McpToolDiscovery;
import io.github.hellices.agentframework.mcp.internal.OwnedMcpAsyncOperations;
import io.github.hellices.agentframework.mcp.internal.OwnedMcpClientLifecycle;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpTransportSessionNotFoundException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

/**
 * A lost connection costs one replacement and one repeat of the original call, never more.
 *
 * <p>The bound matters twice over. A retry loop against a server that keeps dropping the connection
 * would spawn processes or HTTP sessions without limit, and a retry of something that was not a
 * connection failure would run a tool's side effect a second time.
 *
 * <p>The counts are the contract, so every test counts three things: transports the factory was
 * asked for, transports closed, and how often the original request reached each of them. A request
 * is issued once per generation and on at most two generations.
 */
class OwnedMcpClientRetryTest {

  private static final Duration SETTLE = Duration.ofSeconds(5);

  private static final Map<String, Object> SCHEMA =
      Map.of("type", "object", "properties", Map.of("query", Map.of("type", "string")));

  @Test
  void retriesTheOriginalOperationOnceAfterALostConnection() {
    InMemoryMcpTransport first =
        toolServer()
            .answeringPing()
            .failingSend(
                McpSchema.METHOD_TOOLS_LIST,
                () -> new McpTransportSessionNotFoundException("session expired"));
    InMemoryMcpTransport second = toolServer().answeringPing();
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(first, second);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());
    lifecycle.connect().join();

    assertThat(listTools(lifecycle)).succeedsWithin(SETTLE);

    assertThat(factory.createdCount()).isEqualTo(2);
    assertThat(first.closeCount()).isEqualTo(1);
    assertThat(first.countOf(McpSchema.METHOD_TOOLS_LIST)).isEqualTo(1);
    assertThat(second.countOf(McpSchema.METHOD_TOOLS_LIST)).isEqualTo(1);
    // The replacement was built by a handshake, so the repeat runs on it directly: validating a
    // connection that was created moments ago would be a second round trip proving nothing.
    assertThat(second.countOf(McpSchema.METHOD_PING)).isZero();
  }

  @Test
  void neverReplacesTheGenerationTwiceForOneOperation() {
    InMemoryMcpTransport first =
        toolServer()
            .answeringPing()
            .failingSend(
                McpSchema.METHOD_TOOLS_LIST,
                () -> new McpTransportSessionNotFoundException("session expired"));
    InMemoryMcpTransport second =
        toolServer()
            .answeringPing()
            .failingSend(
                McpSchema.METHOD_TOOLS_LIST,
                () -> new McpTransportSessionNotFoundException("session expired again"));
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(first, second);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());
    lifecycle.connect().join();

    // The second loss is reported rather than recovered from: a server that drops the connection
    // twice in a row is not one more replacement away from working. The message tells the two
    // losses apart, so this also pins that the caller is given the failure that actually stopped
    // it rather than the one the replacement was built for.
    assertThat(settledFailure(listTools(lifecycle)))
        .isInstanceOf(McpTransportSessionNotFoundException.class)
        .hasMessageContaining("session expired again");

    assertThat(factory.createdCount()).isEqualTo(2);
    assertThat(first.closeCount()).isEqualTo(1);
    assertThat(second.closeCount()).isZero();
    assertThat(second.countOf(McpSchema.METHOD_TOOLS_LIST)).isEqualTo(1);
  }

  @Test
  void doesNotRetryAnApplicationError() {
    InMemoryMcpTransport transport =
        toolServer()
            .answeringPing()
            .answeringWithError(
                McpSchema.METHOD_TOOLS_LIST, McpSchema.ErrorCodes.INTERNAL_ERROR, "tool exploded");
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(transport);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());
    lifecycle.connect().join();

    assertThat(settledFailure(listTools(lifecycle))).isInstanceOf(McpError.class);

    assertThat(factory.createdCount()).isEqualTo(1);
    assertThat(transport.closeCount()).isZero();
    assertThat(transport.countOf(McpSchema.METHOD_TOOLS_LIST)).isEqualTo(1);
  }

  @Test
  void doesNotRetryAFailureThatOnlyReadsLikeALostConnection() {
    // The classification is by type, never by wording. This failure arrives from the transport, is
    // worded exactly like the session loss two tests above, and is still not one: a request that
    // timed out may already have run its side effect on the server, so repeating it is the very
    // thing the type check exists to prevent.
    InMemoryMcpTransport transport =
        toolServer()
            .answeringPing()
            .failingSend(
                McpSchema.METHOD_TOOLS_LIST,
                () -> new TimeoutException("session expired: the server did not answer in time"));
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(transport);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());
    lifecycle.connect().join();

    assertThat(settledFailure(listTools(lifecycle))).isInstanceOf(TimeoutException.class);

    assertThat(factory.createdCount()).isEqualTo(1);
    assertThat(transport.closeCount()).isZero();
    assertThat(transport.countOf(McpSchema.METHOD_TOOLS_LIST)).isEqualTo(1);
  }

  @Test
  void doesNotRetryWhenValidationAlreadySpentTheBudget() {
    // One budget covers both stages of an operation. Validation replaced the generation here, so
    // the call's own connection loss has nothing left to spend and is reported: a third generation
    // for a single tool call is the runaway this bound exists to stop.
    InMemoryMcpTransport first =
        toolServer()
            .answeringWithError(
                McpSchema.METHOD_PING, McpSchema.ErrorCodes.INTERNAL_ERROR, "ping failed");
    InMemoryMcpTransport second =
        toolServer()
            .answeringPing()
            .failingSend(
                McpSchema.METHOD_TOOLS_LIST,
                () -> new McpTransportSessionNotFoundException("session expired"));
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(first, second);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());
    lifecycle.connect().join();

    assertThat(settledFailure(listTools(lifecycle)))
        .isInstanceOf(McpTransportSessionNotFoundException.class);

    assertThat(factory.createdCount()).isEqualTo(2);
    assertThat(first.closeCount()).isEqualTo(1);
    assertThat(second.closeCount()).isZero();
    assertThat(second.countOf(McpSchema.METHOD_TOOLS_LIST)).isEqualTo(1);
  }

  @Test
  void retriesACallThatOurOwnReplacementDismissed() {
    // Closing the stale generation dismisses the sibling request in flight on it with a bare
    // RuntimeException carrying no type and no cause. That dismissal is this owner's own doing, so
    // the sibling is repeated on the replacement its neighbour built, and it is recognised by the
    // generation's recorded state rather than by the wording of a failure the SDK owns.
    InMemoryMcpTransport first =
        toolServer()
            .answeringPing()
            .withholding(McpSchema.METHOD_TOOLS_LIST)
            .failingSend(
                McpSchema.METHOD_TOOLS_CALL,
                () -> new McpTransportSessionNotFoundException("session expired"));
    InMemoryMcpTransport second = toolServer().answeringPing();
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(first, second);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());
    lifecycle.connect().join();

    CompletableFuture<McpSchema.ListToolsResult> dismissed = listTools(lifecycle);
    CompletableFuture<McpSchema.CallToolResult> lostConnection =
        lifecycle.execute(
            client -> client.callTool(new McpSchema.CallToolRequest("search-issues", null, null)));

    assertThat(lostConnection).succeedsWithin(SETTLE);
    assertThat(dismissed).succeedsWithin(SETTLE);
    assertThat(factory.createdCount()).isEqualTo(2);
    assertThat(first.closeCount()).isEqualTo(1);
    assertThat(first.countOf(McpSchema.METHOD_TOOLS_LIST)).isEqualTo(1);
    assertThat(second.countOf(McpSchema.METHOD_TOOLS_LIST)).isEqualTo(1);
    assertThat(second.countOf(McpSchema.METHOD_TOOLS_CALL)).isEqualTo(1);
  }

  @Test
  void retriesTheCallMadeThroughADiscoveredTool() {
    InMemoryMcpTransport first =
        toolServer()
            .answeringPing()
            .failingSend(
                McpSchema.METHOD_TOOLS_CALL,
                () -> new McpTransportSessionNotFoundException("session expired"));
    InMemoryMcpTransport second = toolServer().answeringPing();
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(first, second);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());
    lifecycle.connect().join();

    List<FunctionTool> tools =
        new McpToolDiscovery(
                new OwnedMcpAsyncOperations(lifecycle), McpToolAdapterOptions.defaults())
            .discover()
            .toCompletableFuture()
            .join();
    ToolResult result =
        tools
            .get(0)
            .execute(new ToolArguments(Map.of("query", "open")), new ToolContext(null, Map.of()))
            .toCompletableFuture()
            .join();

    assertThat(result.error()).isFalse();
    assertThat(result.content().get(0).text()).isEqualTo("found 2 issues");
    assertThat(factory.createdCount()).isEqualTo(2);
    assertThat(first.closeCount()).isEqualTo(1);
    assertThat(first.countOf(McpSchema.METHOD_TOOLS_CALL)).isEqualTo(1);
    assertThat(second.countOf(McpSchema.METHOD_TOOLS_CALL)).isEqualTo(1);
  }

  @Test
  void closingDuringAnInFlightCallFailsThatCallWithoutReconnecting() {
    // An explicit close is not a lost connection, even though the SDK dismisses the in-flight call
    // with the same untyped failure a lost connection produces. Retrying here would resurrect the
    // server the caller just shut down, which is the one thing an owned client must never do.
    InMemoryMcpTransport transport =
        toolServer().answeringPing().withholding(McpSchema.METHOD_TOOLS_CALL);
    InMemoryMcpTransport neverUsed = toolServer().answeringPing();
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(transport, neverUsed);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());
    lifecycle.connect().join();

    CompletableFuture<McpSchema.CallToolResult> inFlight =
        lifecycle.execute(
            client -> client.callTool(new McpSchema.CallToolRequest("search-issues", null, null)));

    assertThat(lifecycle.close()).succeedsWithin(SETTLE);

    // The dismissal type carries no information (verified SDK fact 15), so the assertion is on what
    // the owner did about it: it looked for a generation to repeat the call on, found none, and
    // attached that as the reason. One transport, one close, and the spare the factory still holds
    // is what a reconnect would have taken.
    assertThat(settledFailure(inFlight).getSuppressed())
        .singleElement()
        .satisfies(
            suppressed ->
                assertThat(suppressed)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("connect()"));
    assertThat(factory.createdCount()).isEqualTo(1);
    assertThat(neverUsed.methodsSent()).isEmpty();
    assertThat(transport.closeCount()).isEqualTo(1);
    assertThat(transport.countOf(McpSchema.METHOD_TOOLS_CALL)).isEqualTo(1);
  }

  @Test
  void cancellingACallInFlightNeitherRepeatsItNorReplacesTheGeneration() {
    // Cancellation is the caller withdrawing, not the connection failing. A retry here would send
    // the tool call a second time for a caller that has already left, and the replacement it would
    // need is the second transport the factory is holding.
    InMemoryMcpTransport transport =
        toolServer().answeringPing().withholding(McpSchema.METHOD_TOOLS_CALL);
    InMemoryMcpTransport neverUsed = toolServer().answeringPing();
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(transport, neverUsed);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());
    lifecycle.connect().join();

    CompletableFuture<McpSchema.CallToolResult> inFlight =
        lifecycle.execute(
            client -> client.callTool(new McpSchema.CallToolRequest("search-issues", null, null)));
    assertThat(inFlight.cancel(true)).isTrue();
    transport.releaseWithheld();

    assertThat(inFlight).isCancelled();
    assertThat(factory.createdCount()).isEqualTo(1);
    assertThat(neverUsed.methodsSent()).isEmpty();
    assertThat(transport.closeCount()).isZero();
    assertThat(transport.countOf(McpSchema.METHOD_TOOLS_CALL)).isEqualTo(1);
  }

  @Test
  void doesNotRepeatACancellationThatCarriesALostConnectionCause() {
    // A cancellation is recognised by its own type, while a lost connection is recognised by
    // walking the cause chain, so a cancellation that carries a transport failure underneath it
    // answers to both descriptions. The order of the two questions is what settles it: asked the
    // other way round, this failure reads as a lost connection, and the caller that withdrew would
    // get a second server started for it and its tool call sent again on that server, this time
    // with a side effect nobody is waiting for. Nothing here cancelled the stage, so the
    // pre-dispatch guard is not watching: the decision belongs to this rule alone.
    InMemoryMcpTransport transport =
        toolServer()
            .answeringPing()
            .failingSend(
                McpSchema.METHOD_TOOLS_CALL,
                () -> {
                  CancellationException withdrawn = new CancellationException("run cancelled");
                  withdrawn.initCause(new McpTransportSessionNotFoundException("session expired"));
                  return withdrawn;
                });
    InMemoryMcpTransport neverUsed = toolServer().answeringPing();
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(transport, neverUsed);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());
    lifecycle.connect().join();

    CompletableFuture<McpSchema.CallToolResult> cancelled =
        lifecycle.execute(
            client -> client.callTool(new McpSchema.CallToolRequest("search-issues", null, null)));

    assertThat(settledFailure(cancelled))
        .isInstanceOf(CancellationException.class)
        .hasCauseInstanceOf(McpTransportSessionNotFoundException.class);
    assertThat(factory.createdCount()).isEqualTo(1);
    assertThat(neverUsed.methodsSent()).isEmpty();
    assertThat(transport.closeCount()).isZero();
    assertThat(transport.countOf(McpSchema.METHOD_TOOLS_CALL)).isEqualTo(1);
  }

  @Test
  void aCancelledCallIsNotResurrectedByTheReplacementItWasWaitingFor() {
    // The retry is what makes the pre-dispatch guards matter again: a repeat is dispatched from the
    // completion of a replacement handshake, which is a window long enough for the caller to
    // withdraw. The stale close is withheld, so the cancellation lands while the repeat is waiting
    // for the replacement, and the repeat must then not reach the new server at all.
    InMemoryMcpTransport first =
        toolServer()
            .answeringPing()
            .withholding(McpSchema.METHOD_TOOLS_CALL)
            .failingSend(
                McpSchema.METHOD_TOOLS_LIST,
                () -> new McpTransportSessionNotFoundException("session expired"))
            .withholdingClose();
    InMemoryMcpTransport second = toolServer().answeringPing();
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(first, second);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());
    lifecycle.connect().join();

    CompletableFuture<McpSchema.CallToolResult> withdrawn =
        lifecycle.execute(
            client -> client.callTool(new McpSchema.CallToolRequest("search-issues", null, null)));
    CompletableFuture<McpSchema.ListToolsResult> replacing = listTools(lifecycle);

    // The neighbour's connection loss closed the generation, which dismissed the call in flight on
    // it; both are now waiting for the one replacement the withheld close is holding up.
    assertThat(first.closeCount()).isEqualTo(1);
    assertThat(factory.createdCount()).isEqualTo(1);
    assertThat(withdrawn).isNotDone();
    assertThat(withdrawn.cancel(true)).isTrue();

    first.releaseWithheldClose();

    assertThat(replacing).succeedsWithin(SETTLE);
    assertThat(withdrawn).isCancelled();
    assertThat(factory.createdCount()).isEqualTo(2);
    assertThat(second.countOf(McpSchema.METHOD_TOOLS_LIST)).isEqualTo(1);
    assertThat(second.countOf(McpSchema.METHOD_TOOLS_CALL)).isZero();
    assertThat(second.closeCount()).isZero();
  }

  /**
   * Returns the failure a stage settled with, waiting under a bound that only detects a wedge.
   *
   * <p>The bound is not a wait for slow work — everything these tests drive completes on the
   * calling thread — it is how a promise nobody is left to complete is reported as a failing test
   * instead of a suite that never finishes. A retry that loses its result is exactly that failure,
   * so it must not be able to hang the suite.
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
            params ->
                new McpSchema.ListToolsResult(
                    List.of(
                        new McpSchema.Tool(
                            "search-issues", null, "search", SCHEMA, null, null, null, null)),
                    null,
                    null))
        .answering(
            McpSchema.METHOD_TOOLS_CALL,
            params ->
                new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent(null, "found 2 issues", null)),
                    Boolean.FALSE,
                    null,
                    null));
  }

  private static McpOwnedClientSettings settings() {
    return new McpOwnedClientSettings(
        new PermissiveJsonSchemaValidator(), Duration.ofSeconds(5), Duration.ofSeconds(5));
  }
}
