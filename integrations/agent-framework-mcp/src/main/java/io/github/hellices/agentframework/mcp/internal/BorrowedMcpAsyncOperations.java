package io.github.hellices.agentframework.mcp.internal;

import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import java.util.concurrent.CompletionStage;

/**
 * Performs MCP operations on a client the caller owns.
 *
 * <p>The client is borrowed. This class calls {@code listTools} and {@code callTool} and nothing
 * else: it never initializes, reconnects, or closes the client, and it registers no shutdown hook
 * and no cleaner, because the code that created the client and its transport decides when the
 * connection ends. An adapter that closed a shared client would break every other holder of it.
 *
 * <p>The client must already be initialized, and that is enforced rather than documented. The SDK
 * initializes lazily on the first operation and shares that one handshake with every later caller,
 * so an adapter that triggered it would own it: cancelling the first discovery would abort the
 * shared handshake, and because a cancellation is not an error signal the client would keep an
 * initialization that never completes and never retries, leaving every other holder waiting for a
 * connection that is already dead.
 */
public final class BorrowedMcpAsyncOperations implements McpAsyncOperations {

  private final McpAsyncClient client;

  /**
   * Creates operations over a borrowed client.
   *
   * @param client a connected and initialized client owned by the caller, never {@code null}
   * @throws IllegalArgumentException if the client is {@code null} or is not initialized
   */
  public BorrowedMcpAsyncOperations(McpAsyncClient client) {
    if (client == null) {
      throw new IllegalArgumentException("client must not be null");
    }
    if (!client.isInitialized()) {
      throw new IllegalArgumentException(
          "client must be initialized before it is adapted; call initialize() on it first, because"
              + " an adapter that drove the handshake would take over a lifecycle its owner keeps");
    }
    this.client = client;
  }

  @Override
  public CompletionStage<McpSchema.ListToolsResult> listTools(
      String cursor, Map<String, Object> meta) {
    return AsyncStages.fromMono(client.listTools(cursor, meta));
  }

  @Override
  public CompletionStage<McpSchema.CallToolResult> callTool(McpSchema.CallToolRequest request) {
    return AsyncStages.fromMono(client.callTool(request));
  }
}
