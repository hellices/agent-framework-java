package io.github.hellices.agentframework.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.hellices.agentframework.api.context.ContextAttributes;
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
import io.modelcontextprotocol.spec.McpTransportException;
import io.modelcontextprotocol.spec.McpTransportSessionNotFoundException;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * A lost connection costs one replacement and one repeat of the original call, never more.
 *
 * <p>The bound matters twice over. A retry loop against a server that keeps dropping the connection
 * would spawn processes or HTTP sessions without limit, and a retry of something that was not a
 * connection failure would run a tool's side effect a second time.
 *
 * <p>What may be repeated is decided per operation, so the tests below name the operation they are
 * about. A {@code tools/call} is repeated only on a failure that proves the server never had it,
 * and never because this owner dismissed it while replacing the connection for something else. A
 * {@code tools/list} read changes nothing on the server, so a dismissal its owner caused is
 * recovered from — as a restart signal, because a page is never re-sent with a cursor the dead
 * session issued.
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
  void doesNotRepeatACallThatFailedWithAGenericTransportFailure() {
    // The SDK's generic transport failure is not a lost session. Version 2.0.0 raises it for the
    // body of a successful POST its mapper could not read, and for a 400 or a 404 from a streamable
    // HTTP server that issues no session id — cases in which the server accepted the call and may
    // already have run it. Only the two session-scoped failures may be repeated, and they are named
    // one by one because they are peers of this type rather than subtypes of it, so this one is
    // reported: one transport, one dispatch, and the spare the factory still holds is what a
    // replacement would have taken.
    InMemoryMcpTransport transport =
        toolServer()
            .answeringPing()
            .failingSend(
                McpSchema.METHOD_TOOLS_CALL,
                () ->
                    new McpTransportException(
                        "Error parsing response body", new IOException("unexpected end of input")));
    InMemoryMcpTransport neverUsed = toolServer().answeringPing();
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(transport, neverUsed);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());
    lifecycle.connect().join();

    CompletableFuture<McpSchema.CallToolResult> unparseable =
        lifecycle.execute(
            client -> client.callTool(new McpSchema.CallToolRequest("search-issues", null, null)));

    assertThat(settledFailure(unparseable))
        .isInstanceOf(McpTransportException.class)
        .hasCauseInstanceOf(IOException.class);
    assertThat(factory.createdCount()).isEqualTo(1);
    assertThat(neverUsed.methodsSent()).isEmpty();
    assertThat(transport.closeCount()).isZero();
    assertThat(transport.countOf(McpSchema.METHOD_TOOLS_CALL)).isEqualTo(1);
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
  void doesNotRepeatACallThatASiblingsReplacementDismissed() {
    // The trigger is only a sibling's validation ping going unanswered, which a merely slow server
    // produces. Replacing the generation closes it, and closing it dismisses the tools/call already
    // on the wire with a bare RuntimeException. That dismissal says nothing about the server: the
    // call may have arrived and run there before the close. Repeating it on the replacement would
    // run its side effect a second time, so it is reported to the caller that asked for it.
    AtomicInteger pings = new AtomicInteger();
    InMemoryMcpTransport first =
        toolServer()
            .answering(
                McpSchema.METHOD_PING,
                params -> {
                  if (pings.incrementAndGet() > 1) {
                    throw new McpTransportException("the server did not answer the ping in time");
                  }
                  return Map.of();
                })
            .withholding(McpSchema.METHOD_TOOLS_CALL);
    InMemoryMcpTransport second = toolServer().answeringPing();
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(first, second);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());
    lifecycle.connect().join();
    OwnedMcpAsyncOperations operations = new OwnedMcpAsyncOperations(lifecycle);

    CompletableFuture<McpSchema.CallToolResult> dismissedCall =
        operations
            .callTool(new McpSchema.CallToolRequest("search-issues", null, null))
            .toCompletableFuture();
    assertThat(dismissedCall).isNotDone();
    assertThat(first.countOf(McpSchema.METHOD_TOOLS_CALL)).isEqualTo(1);

    // The sibling's ping is the second one this server is asked for, and it is the one that fails.
    CompletableFuture<McpSchema.ListToolsResult> sibling = listTools(lifecycle);

    // The sibling heals: its ping had no side effect to repeat, so it buys the replacement and runs
    // there. The call it dismissed on the way does not follow it.
    assertThat(sibling).succeedsWithin(SETTLE);
    Throwable reported = settledFailure(dismissedCall);
    assertThat(reported).isNotInstanceOf(CancellationException.class);
    // Nothing was attached to it, because no recovery was attempted for this call at all.
    assertThat(reported.getSuppressed()).isEmpty();
    assertThat(factory.createdCount()).isEqualTo(2);
    assertThat(first.closeCount()).isEqualTo(1);
    assertThat(first.countOf(McpSchema.METHOD_TOOLS_CALL)).isEqualTo(1);
    assertThat(second.countOf(McpSchema.METHOD_TOOLS_CALL)).isZero();
    assertThat(second.countOf(McpSchema.METHOD_TOOLS_LIST)).isEqualTo(1);
    assertThat(second.closeCount()).isZero();
  }

  @Test
  void doesNotRepeatAGenericRequestThatOurOwnReplacementDismissed() {
    // The generic entry point is handed a function of a client and cannot tell a read from a tool
    // call, so it assumes the worst: a request dismissed by this owner's own replacement is
    // reported rather than sent again. An operation that really is safe to repeat or restart says
    // so through its own attempt, which is what the paged read below does.
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

    // The neighbour's own failure is the typed session loss, which proves the server never had its
    // request, so that one is repeated on the replacement.
    assertThat(lostConnection).succeedsWithin(SETTLE);
    assertThat(settledFailure(dismissed)).isNotInstanceOf(CancellationException.class);
    assertThat(factory.createdCount()).isEqualTo(2);
    assertThat(first.closeCount()).isEqualTo(1);
    assertThat(first.countOf(McpSchema.METHOD_TOOLS_LIST)).isEqualTo(1);
    assertThat(second.countOf(McpSchema.METHOD_TOOLS_LIST)).isZero();
    assertThat(second.countOf(McpSchema.METHOD_TOOLS_CALL)).isEqualTo(1);
  }

  @Test
  void tellsAPagedReadThatOurOwnReplacementDismissedToStartOver() {
    // The other side of the same rule. A tools/list page has no side effect to repeat, so the
    // dismissal its owner caused is recovered from — but a page is never re-sent, because its
    // cursor belongs to the session that issued it. The read is told the connection was replaced,
    // which is the signal a paged reader restarts from the first page on.
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

    CompletableFuture<McpSchema.ListToolsResult> dismissedRead =
        new OwnedMcpAsyncOperations(lifecycle)
            .listTools(McpSchema.FIRST_PAGE, null)
            .toCompletableFuture();
    CompletableFuture<McpSchema.CallToolResult> lostConnection =
        lifecycle.execute(
            client -> client.callTool(new McpSchema.CallToolRequest("search-issues", null, null)));

    assertThat(lostConnection).succeedsWithin(SETTLE);
    Throwable reported = settledFailure(dismissedRead);
    assertThat(reported.getClass().getSimpleName()).isEqualTo("McpConnectionReplacedException");
    assertThat(factory.createdCount()).isEqualTo(2);
    assertThat(first.closeCount()).isEqualTo(1);
    assertThat(first.countOf(McpSchema.METHOD_TOOLS_LIST)).isEqualTo(1);
    assertThat(second.countOf(McpSchema.METHOD_TOOLS_LIST)).isZero();
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
            .execute(
                new ToolArguments(Map.of("query", "open")),
                new ToolContext(null, ContextAttributes.empty()))
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
    // the owner did about it: nothing at all. A call the server may already have run is not made
    // safe to repeat by the owner recognising its own close, so no generation is looked for and
    // nothing is attached to the failure. One transport, one close, and the spare the factory still
    // holds is what a reconnect would have taken.
    assertThat(settledFailure(inFlight).getSuppressed()).isEmpty();
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
    // The repeat is what makes the pre-dispatch guards matter again: it is dispatched from the
    // completion of a replacement handshake, which is a window long enough for the caller to
    // withdraw. The call's own session loss is what buys that replacement — the one failure a call
    // may be repeated on — and the withheld close holds the replacement up, so the cancellation
    // lands while the repeat is waiting for it. The repeat must then not reach the new server.
    InMemoryMcpTransport first =
        toolServer()
            .answeringPing()
            .failingSend(
                McpSchema.METHOD_TOOLS_CALL,
                () -> new McpTransportSessionNotFoundException("session expired"))
            .withholdingClose();
    InMemoryMcpTransport second = toolServer().answeringPing();
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(first, second);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());
    lifecycle.connect().join();

    CompletableFuture<McpSchema.CallToolResult> withdrawn =
        lifecycle.execute(
            client -> client.callTool(new McpSchema.CallToolRequest("search-issues", null, null)));

    // The stale generation is already closing and the repeat is waiting for the replacement the
    // withheld close is holding up.
    assertThat(first.closeCount()).isEqualTo(1);
    assertThat(factory.createdCount()).isEqualTo(1);
    assertThat(withdrawn).isNotDone();
    assertThat(withdrawn.cancel(true)).isTrue();

    first.releaseWithheldClose();

    assertThat(withdrawn).isCancelled();
    assertThat(factory.createdCount()).isEqualTo(2);
    assertThat(second.countOf(McpSchema.METHOD_TOOLS_CALL)).isZero();
    // Cancellation ends one operation, not the generation it would have used: the replacement the
    // owner adopted stays open for the next caller.
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
