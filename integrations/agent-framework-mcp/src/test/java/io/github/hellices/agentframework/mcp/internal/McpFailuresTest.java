package io.github.hellices.agentframework.mcp.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpTransportException;
import io.modelcontextprotocol.spec.McpTransportSessionClosedException;
import io.modelcontextprotocol.spec.McpTransportSessionNotFoundException;
import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

/**
 * Classification decides whether the owner repeats a request, so it is deliberately narrow.
 *
 * <p>Only a JSON-RPC {@code -32601} counts as an unsupported ping, and only the two SDK failures
 * that name a session the server no longer has count as a connection loss a request may be repeated
 * on. Anything else is handed back to the caller unchanged, because repeating what may already have
 * run is worse than reporting it.
 */
class McpFailuresTest {

  @Test
  void treatsMethodNotFoundAsAnUnsupportedPing() {
    McpError failure = jsonRpcError(McpSchema.ErrorCodes.METHOD_NOT_FOUND, "unknown method: ping");

    assertThat(McpFailures.isPingUnsupported(failure)).isTrue();
    assertThat(McpFailures.isRepeatableConnectionLoss(failure)).isFalse();
  }

  @Test
  void treatsEveryOtherJsonRpcErrorAsAnAnswerFromTheServer() {
    McpError failure = jsonRpcError(McpSchema.ErrorCodes.INTERNAL_ERROR, "server is unhappy");

    assertThat(McpFailures.isPingUnsupported(failure)).isFalse();
    assertThat(McpFailures.isRepeatableConnectionLoss(failure)).isFalse();
  }

  @Test
  void treatsTheSdkSessionFailuresAsARepeatableLostConnection() {
    // These two are the only failures the SDK raises to say the server no longer has the session
    // the request was addressed to. A request the server never accepted is one nothing ran for, so
    // it is the only kind that may be sent again.
    assertThat(
            McpFailures.isRepeatableConnectionLoss(
                new McpTransportSessionNotFoundException("gone")))
        .isTrue();
    assertThat(McpFailures.isRepeatableConnectionLoss(new McpTransportSessionClosedException()))
        .isTrue();
  }

  @Test
  void refusesToRepeatAGenericTransportFailure() {
    // SDK 2.0.0 raises the base type for far more than a lost connection: the body of a successful
    // POST that its mapper could not read, and a 400 or a 404 from a streamable HTTP server that
    // issues no session id. The server has answered by then, so a tools/call classified as a lost
    // connection here would be sent a second time and run its side effect twice.
    assertThat(McpFailures.isRepeatableConnectionLoss(new McpTransportException("stream broke")))
        .isFalse();
    assertThat(
            McpFailures.isRepeatableConnectionLoss(
                new McpTransportException(
                    "Error parsing response body", new IOException("unexpected end of input"))))
        .isFalse();
  }

  @Test
  void findsASessionFailureThroughACauseChain() {
    Throwable wrapped =
        new IllegalStateException(
            "Client failed to initialize", new McpTransportSessionNotFoundException("gone"));

    assertThat(McpFailures.isRepeatableConnectionLoss(wrapped)).isTrue();
  }

  @Test
  void refusesToRepeatAGenericTransportFailureFoundThroughACauseChain() {
    Throwable wrapped =
        new IllegalStateException(
            "Client failed to initialize", new McpTransportException("stream broke"));

    assertThat(McpFailures.isRepeatableConnectionLoss(wrapped)).isFalse();
  }

  @Test
  void findsAnUnsupportedPingThroughACauseChain() {
    Throwable wrapped =
        new IllegalStateException(
            "wrapped", jsonRpcError(McpSchema.ErrorCodes.METHOD_NOT_FOUND, "unknown method"));

    assertThat(McpFailures.isPingUnsupported(wrapped)).isTrue();
  }

  @Test
  void treatsAnApplicationFailureAndARequestTimeoutAsSomethingToReportNotToRetry() {
    assertThat(McpFailures.isRepeatableConnectionLoss(new IllegalStateException("tool exploded")))
        .isFalse();
    assertThat(McpFailures.isRepeatableConnectionLoss(new TimeoutException("took too long")))
        .isFalse();
    assertThat(McpFailures.isRepeatableConnectionLoss(new CancellationException("withdrawn")))
        .isFalse();
    assertThat(
            McpFailures.isRepeatableConnectionLoss(
                new RuntimeException("MCP session with server terminated")))
        .isFalse();
  }

  @Test
  void terminatesOnACyclicCauseChain() {
    Throwable first = new IllegalStateException("first");
    Throwable second = new IllegalStateException("second");
    first.initCause(second);
    second.initCause(first);

    assertThat(McpFailures.isRepeatableConnectionLoss(first)).isFalse();
    assertThat(McpFailures.isPingUnsupported(first)).isFalse();
  }

  @Test
  void treatsNoFailureAsNeither() {
    assertThat(McpFailures.isRepeatableConnectionLoss(null)).isFalse();
    assertThat(McpFailures.isPingUnsupported(null)).isFalse();
  }

  private static McpError jsonRpcError(int code, String message) {
    return new McpError(new McpSchema.JSONRPCResponse.JSONRPCError(code, message));
  }
}
