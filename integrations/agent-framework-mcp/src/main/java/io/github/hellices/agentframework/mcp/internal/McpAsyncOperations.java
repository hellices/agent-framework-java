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
}
