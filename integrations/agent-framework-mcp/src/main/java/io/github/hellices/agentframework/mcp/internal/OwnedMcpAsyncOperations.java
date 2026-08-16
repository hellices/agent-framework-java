package io.github.hellices.agentframework.mcp.internal;

import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import java.util.concurrent.CompletionStage;

/**
 * Performs MCP operations on a client this module owns.
 *
 * <p>This is the owned counterpart of {@link BorrowedMcpAsyncOperations}. The borrowed version
 * refuses to act on a client whose owner has closed it; this one routes every call through {@link
 * OwnedMcpClientLifecycle}, which validates the current generation, replaces it at most once, and
 * retries the call at most once. It still never connects on its own: the caller opens the
 * connection explicitly, so a tool lookup can never start a server process as a side effect.
 *
 * <p>Instances are immutable and safe to share once constructed.
 */
public final class OwnedMcpAsyncOperations implements McpAsyncOperations {

  private final OwnedMcpClientLifecycle lifecycle;

  /**
   * Creates operations over an owned lifecycle.
   *
   * @param lifecycle the lifecycle that owns the client, never {@code null}
   * @throws IllegalArgumentException if the lifecycle is {@code null}
   */
  public OwnedMcpAsyncOperations(OwnedMcpClientLifecycle lifecycle) {
    if (lifecycle == null) {
      throw new IllegalArgumentException("lifecycle must not be null");
    }
    this.lifecycle = lifecycle;
  }

  @Override
  public CompletionStage<McpSchema.ListToolsResult> listTools(
      String cursor, Map<String, Object> meta) {
    return lifecycle.execute(client -> client.listTools(cursor, meta));
  }

  @Override
  public CompletionStage<McpSchema.CallToolResult> callTool(McpSchema.CallToolRequest request) {
    if (request == null) {
      return AsyncStages.failed(new IllegalArgumentException("request must not be null"));
    }
    return lifecycle.execute(client -> client.callTool(request));
  }
}
