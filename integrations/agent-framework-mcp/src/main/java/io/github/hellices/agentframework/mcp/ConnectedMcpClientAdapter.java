package io.github.hellices.agentframework.mcp;

import io.github.hellices.agentframework.api.tool.FunctionTool;
import io.github.hellices.agentframework.mcp.internal.BorrowedMcpAsyncOperations;
import io.github.hellices.agentframework.mcp.internal.McpAsyncOperations;
import io.github.hellices.agentframework.mcp.internal.McpToolDiscovery;
import io.modelcontextprotocol.client.McpAsyncClient;
import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * Exposes the tools of a connected MCP server as framework tools.
 *
 * <p>The client is borrowed, never owned. The adapter requires it to be connected and initialized
 * already, and it never initializes, reconnects, or closes it, because a client is usually shared
 * and the code that created it and its transport decides when the connection ends. An adapter that
 * closed a shared client would break every other holder, one that reconnected would hide a
 * transport failure the owner needs to see, and one that drove the initial handshake would let a
 * cancelled discovery abort a handshake every other holder is waiting on. Consequently this class
 * has no lifecycle method and nothing to release, so discarding it is safe.
 *
 * <p>{@link #discoverTools()} reads the server's whole tool catalogue and returns one {@link
 * FunctionTool} per remote tool, in the order the server published them. Each returned tool calls
 * the server on execution using the remote name, so the local name may be normalized or prefixed
 * for the model without affecting the wire. Discovery is a snapshot: a server that changes its tool
 * list later is only observed by discovering again.
 *
 * <p>Instances are immutable and safe to share once constructed.
 */
public final class ConnectedMcpClientAdapter {

  private final McpToolDiscovery discovery;

  /**
   * Creates an adapter over a borrowed client with default options.
   *
   * @param client a connected and initialized client owned by the caller, never {@code null}
   * @throws IllegalArgumentException if the client is {@code null} or is not initialized
   */
  public ConnectedMcpClientAdapter(McpAsyncClient client) {
    this(client, McpToolAdapterOptions.defaults());
  }

  /**
   * Creates an adapter over a borrowed client.
   *
   * @param client a connected and initialized client owned by the caller, never {@code null}
   * @param options adapter options, never {@code null}
   * @throws IllegalArgumentException if the client is {@code null} or is not initialized, or if the
   *     options are {@code null}
   */
  public ConnectedMcpClientAdapter(McpAsyncClient client, McpToolAdapterOptions options) {
    this(new BorrowedMcpAsyncOperations(client), options);
  }

  /**
   * Creates an adapter over the operations port, which lets a test drive the adapter without a
   * server, a transport, or a process. The port names only the two operations the adapter performs
   * and cannot reach the client's lifecycle.
   *
   * @param operations operations over the borrowed client, never {@code null}
   * @param options adapter options, never {@code null}
   * @throws IllegalArgumentException if the operations or the options are {@code null}
   */
  ConnectedMcpClientAdapter(McpAsyncOperations operations, McpToolAdapterOptions options) {
    if (operations == null) {
      throw new IllegalArgumentException("operations must not be null");
    }
    if (options == null) {
      throw new IllegalArgumentException("options must not be null");
    }
    this.discovery = new McpToolDiscovery(operations, options);
  }

  /**
   * Discovers every tool the connected server publishes.
   *
   * <p>Every page of the server's tool list is read, so a paginated catalogue is returned whole. A
   * server that repeats a cursor or pages beyond {@link McpToolAdapterOptions#maxDiscoveryPages()}
   * fails the discovery rather than being followed forever. Cancelling the returned stage, or a
   * stage derived from it, cancels the page request that is in flight, stops any further page from
   * being requested, and leaves the borrowed client untouched.
   *
   * @return a stage completing with the discovered tools in server order, never {@code null}
   */
  public CompletionStage<List<FunctionTool>> discoverTools() {
    return discovery.discover();
  }
}
