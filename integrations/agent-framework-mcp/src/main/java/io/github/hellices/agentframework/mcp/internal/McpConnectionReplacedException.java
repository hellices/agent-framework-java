package io.github.hellices.agentframework.mcp.internal;

/**
 * Reports that the connection behind a paged read was replaced, so the read must start again.
 *
 * <p>This is a signal between two collaborators in this package, not a diagnosis for a caller. A
 * single request survives a replacement by being repeated on the new generation, but a page cannot:
 * its cursor is opaque state that belongs to the session that issued it, and continuing someone
 * else's pagination on a new session is either rejected or, worse, answered from a different
 * position. The operation that caused the replacement is kept as the cause so that a reader which
 * cannot restart still reports something true.
 */
final class McpConnectionReplacedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  McpConnectionReplacedException(Throwable cause) {
    super("the MCP connection was replaced while reading a paged result", cause);
  }
}
