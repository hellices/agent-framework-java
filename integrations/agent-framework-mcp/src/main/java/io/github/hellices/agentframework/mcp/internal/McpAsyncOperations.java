package io.github.hellices.agentframework.mcp.internal;

import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import java.util.concurrent.CompletionStage;

/**
 * The MCP operations the tool adapter performs on a connected client.
 *
 * <p>The port exists for two reasons. The SDK client types are final with non public constructors,
 * so adapter logic could otherwise only be exercised against a live server or a process. And the
 * port names exactly the two operations the adapter is allowed to perform, which keeps the borrowed
 * client's lifecycle out of reach: there is deliberately no method to connect, initialize,
 * reconnect, or close.
 *
 * <p>Implementations are used concurrently and must be thread safe.
 */
public interface McpAsyncOperations {

  /**
   * Lists one page of the server's tools.
   *
   * @param cursor opaque cursor of the page to read, {@code null} for the first page
   * @param meta protocol level request metadata, may be {@code null} to omit {@code _meta}
   * @return a stage completing with the page, never {@code null}
   */
  CompletionStage<McpSchema.ListToolsResult> listTools(String cursor, Map<String, Object> meta);

  /**
   * Calls one tool on the server.
   *
   * @param request the call request, never {@code null}
   * @return a stage completing with the result, never {@code null}
   */
  CompletionStage<McpSchema.CallToolResult> callTool(McpSchema.CallToolRequest request);

  /**
   * Returns the operations to use for one paged read, such as a whole tool discovery.
   *
   * <p>Reading a catalogue is one logical operation even though it is many requests, and an
   * implementation that owns its connection needs to know that in order to spend a recovery budget
   * once for the whole read rather than once per page. An implementation that borrows its
   * connection recovers from nothing and returns itself, which is why this is a default method.
   *
   * @return the operations for one paged read, never {@code null}
   */
  default McpAsyncOperations forPagedOperation() {
    return this;
  }
}
