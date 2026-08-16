package io.github.hellices.agentframework.mcp.internal;

import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
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
   * Reports whether the failure is the SDK saying the server no longer has the session the request
   * was addressed to.
   *
   * <p>Only the two session-scoped SDK failures qualify, because this answer decides whether a
   * request is <em>sent again</em>. A session the server does not have is one that accepted
   * nothing, so a request that failed that way ran nowhere and repeating it is safe.
   *
   * <p>The SDK's base {@code McpTransportException} deliberately does not qualify. Version 2.0.0
   * raises it for the body of a <em>successful</em> POST response its JSON mapper could not read,
   * and for a 400 or a 404 from a streamable HTTP server that issues no session id — cases in which
   * the server received the request, may have executed it, and only the answer went wrong.
   * Repeating a {@code tools/call} there would run its side effect twice, which is a worse outcome
   * than reporting a connection that really was lost. A connection that is genuinely gone is healed
   * one operation later anyway: the validation ping of the next operation has no side effect to
   * repeat and replaces the generation on any failure at all.
   *
   * <p>An application error, including a tool that failed and a request that timed out, is the
   * server's answer and is reported to the caller for the same reason.
   *
   * @param failure the failure to classify, may be {@code null}
   * @return {@code true} if the request may be repeated on a replacement connection
   */
  static boolean isRepeatableConnectionLoss(Throwable failure) {
    return matches(failure, McpFailures::sessionGone);
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

  private static boolean sessionGone(Throwable candidate) {
    return candidate instanceof McpTransportSessionNotFoundException
        || candidate instanceof McpTransportSessionClosedException;
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
