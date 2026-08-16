package io.github.hellices.agentframework.mcp.internal;

import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpTransportException;
import io.modelcontextprotocol.spec.McpTransportSessionClosedException;
import io.modelcontextprotocol.spec.McpTransportSessionNotFoundException;
import java.util.function.Predicate;

/**
 * Decides what a failure means for an owned connection.
 *
 * <p>Both questions are answered from the failure's type and, for a JSON-RPC error, its numeric
 * code. Nothing here matches on a message, because a message is wording the SDK and the server are
 * free to change, and a reconnect policy that depended on wording would change behavior on an
 * upgrade without a single test failing.
 *
 * <p>The walk down the cause chain is bounded. A failure chain assembled by several layers can be
 * long, and a chain that loops back on itself would otherwise never terminate.
 */
final class McpFailures {

  private static final int MAX_CAUSE_DEPTH = 16;

  private McpFailures() {}

  /**
   * Reports whether the server answered {@code ping} with JSON-RPC {@code -32601}.
   *
   * <p>That answer means the server implements no ping, not that the connection is unhealthy, so
   * the caller should stop pinging that connection rather than replace it.
   *
   * @param failure the failure to classify, may be {@code null}
   * @return {@code true} if the server does not implement ping
   */
  static boolean isPingUnsupported(Throwable failure) {
    return matches(failure, McpFailures::methodNotFound);
  }

  /**
   * Reports whether the failure is the SDK saying the connection is gone.
   *
   * <p>Only the SDK's own transport failure types qualify. An application error, including a tool
   * that failed and a request that timed out, is the server's answer and is reported to the caller
   * instead of costing a reconnect and a second execution of a call that may have side effects.
   *
   * @param failure the failure to classify, may be {@code null}
   * @return {@code true} if the connection is known to be gone
   */
  static boolean isConnectionLoss(Throwable failure) {
    return matches(failure, McpFailures::transportFailure);
  }

  private static boolean methodNotFound(Throwable candidate) {
    if (!(candidate instanceof McpError error)) {
      return false;
    }
    McpSchema.JSONRPCResponse.JSONRPCError jsonRpcError = error.getJsonRpcError();
    if (jsonRpcError == null) {
      return false;
    }
    Integer code = jsonRpcError.code();
    return code != null && code.intValue() == McpSchema.ErrorCodes.METHOD_NOT_FOUND;
  }

  private static boolean transportFailure(Throwable candidate) {
    return candidate instanceof McpTransportSessionNotFoundException
        || candidate instanceof McpTransportSessionClosedException
        || candidate instanceof McpTransportException;
  }

  private static boolean matches(Throwable failure, Predicate<Throwable> predicate) {
    Throwable candidate = failure;
    for (int depth = 0; candidate != null && depth < MAX_CAUSE_DEPTH; depth++) {
      if (predicate.test(candidate)) {
        return true;
      }
      candidate = candidate.getCause();
    }
    return false;
  }
}
