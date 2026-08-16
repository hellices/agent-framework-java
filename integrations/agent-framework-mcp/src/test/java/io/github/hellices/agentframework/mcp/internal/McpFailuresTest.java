package io.github.hellices.agentframework.mcp.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpTransportException;
import io.modelcontextprotocol.spec.McpTransportSessionClosedException;
import io.modelcontextprotocol.spec.McpTransportSessionNotFoundException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

/**
 * Classification decides whether the owner throws away a connection, so it is deliberately narrow.
 *
 * <p>Only a JSON-RPC {@code -32601} counts as an unsupported ping, and only the SDK's own transport
 * failure types count as a lost connection. Anything else is the server's answer and is handed back
 * to the caller unchanged, because reconnecting on an application error would restart a server
 * process every time a tool legitimately failed.
 */
class McpFailuresTest {

  @Test
  void treatsMethodNotFoundAsAnUnsupportedPing() {
    McpError failure = jsonRpcError(McpSchema.ErrorCodes.METHOD_NOT_FOUND, "unknown method: ping");

    assertThat(McpFailures.isPingUnsupported(failure)).isTrue();
    assertThat(McpFailures.isConnectionLoss(failure)).isFalse();
  }

  @Test
  void treatsEveryOtherJsonRpcErrorAsAnAnswerFromTheServer() {
    McpError failure = jsonRpcError(McpSchema.ErrorCodes.INTERNAL_ERROR, "server is unhappy");

    assertThat(McpFailures.isPingUnsupported(failure)).isFalse();
    assertThat(McpFailures.isConnectionLoss(failure)).isFalse();
  }

  @Test
  void treatsTheSdkTransportFailuresAsALostConnection() {
    assertThat(McpFailures.isConnectionLoss(new McpTransportSessionNotFoundException("gone")))
        .isTrue();
    assertThat(McpFailures.isConnectionLoss(new McpTransportSessionClosedException())).isTrue();
    assertThat(McpFailures.isConnectionLoss(new McpTransportException("stream broke"))).isTrue();
  }

  @Test
  void findsATransportFailureThroughACauseChain() {
    Throwable wrapped =
        new IllegalStateException(
            "Client failed to initialize", new McpTransportException("stream broke"));

    assertThat(McpFailures.isConnectionLoss(wrapped)).isTrue();
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
    assertThat(McpFailures.isConnectionLoss(new IllegalStateException("tool exploded"))).isFalse();
    assertThat(McpFailures.isConnectionLoss(new TimeoutException("took too long"))).isFalse();
    assertThat(
            McpFailures.isConnectionLoss(
                new RuntimeException("MCP session with server terminated")))
        .isFalse();
  }

  @Test
  void terminatesOnACyclicCauseChain() {
    Throwable first = new IllegalStateException("first");
    Throwable second = new IllegalStateException("second");
    first.initCause(second);
    second.initCause(first);

    assertThat(McpFailures.isConnectionLoss(first)).isFalse();
    assertThat(McpFailures.isPingUnsupported(first)).isFalse();
  }

  @Test
  void treatsNoFailureAsNeither() {
    assertThat(McpFailures.isConnectionLoss(null)).isFalse();
    assertThat(McpFailures.isPingUnsupported(null)).isFalse();
  }

  private static McpError jsonRpcError(int code, String message) {
    return new McpError(new McpSchema.JSONRPCResponse.JSONRPCError(code, message));
  }
}
