package io.github.hellices.agentframework.mcp.internal;

import io.modelcontextprotocol.spec.McpClientTransport;

/**
 * Creates one brand new MCP client transport.
 *
 * <p>The seam exists because a transport is not reusable. The stdio transport starts a process and
 * allocates schedulers in its constructor, and the streamable HTTP transport binds one session, so
 * a generation that was closed can never be revived. Making creation a factory call keeps that rule
 * enforceable and lets a test hand out in-memory transports instead of processes and sockets.
 *
 * <p>Implementations must return a transport that has never been connected.
 */
@FunctionalInterface
public interface McpClientTransportFactory {

  /**
   * Creates one transport.
   *
   * @return a new, unconnected transport, never {@code null}
   */
  McpClientTransport create();
}
