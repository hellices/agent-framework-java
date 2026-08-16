package io.github.hellices.agentframework.mcp.internal;

import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import reactor.core.publisher.Mono;

/**
 * Performs MCP operations on a client the caller owns.
 *
 * <p>The client is borrowed. This class calls {@code listTools} and {@code callTool} and nothing
 * else: it never initializes, reconnects, or closes the client, and it registers no shutdown hook
 * and no cleaner, because the code that created the client and its transport decides when the
 * connection ends. An adapter that closed a shared client would break every other holder of it.
 *
 * <p>The client must already be initialized, and that is enforced on every operation rather than
 * once at construction. The SDK initializes lazily and shares one handshake with every later
 * caller, so whichever operation finds no initialization becomes the handshake driver. That
 * reference is cleared again when the owner closes the client and when a streamable HTTP session
 * expires, so an adapter that only checked at construction would later re-open a connection its
 * owner had ended, and cancelling that operation would abort the handshake and leave every other
 * holder waiting for a connection that never completes. The check sits inside {@link Mono#defer} so
 * it runs at subscription time, which narrows the gap to a genuine concurrent reset instead of the
 * whole lifetime of the adapter.
 */
public final class BorrowedMcpAsyncOperations implements McpAsyncOperations {

  private static final String NOT_INITIALIZED =
      "client must be initialized before it is adapted; call initialize() on it first, because an"
          + " adapter that drove the handshake would take over a lifecycle its owner keeps";
  private static final String NO_LONGER_INITIALIZED =
      "client is no longer initialized: its owner closed it or its session was reset, and the"
          + " adapter does not drive a new handshake on a lifecycle it borrows";

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
      throw new IllegalArgumentException(NOT_INITIALIZED);
    }
    this.client = client;
  }

  @Override
  public CompletionStage<McpSchema.ListToolsResult> listTools(
      String cursor, Map<String, Object> meta) {
    return AsyncStages.fromMono(whileInitialized(() -> client.listTools(cursor, meta)));
  }

  @Override
  public CompletionStage<McpSchema.CallToolResult> callTool(McpSchema.CallToolRequest request) {
    return AsyncStages.fromMono(whileInitialized(() -> client.callTool(request)));
  }

  private <T> Mono<T> whileInitialized(Supplier<Mono<T>> operation) {
    return Mono.defer(
        () ->
            client.isInitialized()
                ? operation.get()
                : Mono.error(new IllegalStateException(NO_LONGER_INITIALIZED)));
  }
}
