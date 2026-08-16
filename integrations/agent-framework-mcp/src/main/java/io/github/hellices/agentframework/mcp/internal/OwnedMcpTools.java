package io.github.hellices.agentframework.mcp.internal;

import io.github.hellices.agentframework.api.tool.FunctionTool;
import io.github.hellices.agentframework.mcp.McpToolAdapterOptions;
import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * The body shared by every owned MCP tool provider.
 *
 * <p>Each transport gets its own public facade because the configuration differs, but the lifecycle
 * is identical: connect explicitly, discover, close. Keeping that here means the facades hold no
 * lifecycle logic at all, so a fix to reconnect or validation cannot apply to one transport and not
 * the other.
 */
public final class OwnedMcpTools {

  private final OwnedMcpClientLifecycle lifecycle;
  private final McpToolDiscovery discovery;

  /**
   * Creates a provider over a transport factory it owns.
   *
   * @param transportFactory creates each generation's transport, never {@code null}
   * @param settings settings applied to each generation's client, never {@code null}
   * @param options tool adapter options, never {@code null}
   * @throws IllegalArgumentException if an argument is {@code null}
   */
  public OwnedMcpTools(
      McpClientTransportFactory transportFactory,
      McpOwnedClientSettings settings,
      McpToolAdapterOptions options) {
    this.lifecycle = new OwnedMcpClientLifecycle(transportFactory, settings);
    // Checked here rather than left to McpToolDiscovery, which dereferences options and would
    // report a NullPointerException naming a class the caller never mentioned.
    if (options == null) {
      throw new IllegalArgumentException("options must not be null");
    }
    this.discovery = new McpToolDiscovery(new OwnedMcpAsyncOperations(lifecycle), options);
  }

  /**
   * Opens the connection, creating and initializing a client if there is none.
   *
   * @return a stage completing when the server is ready, never {@code null}
   */
  public CompletionStage<Void> connect() {
    return lifecycle.connect();
  }

  /**
   * Reads the server's whole tool catalogue.
   *
   * @return a stage completing with one tool per remote tool, never {@code null}
   */
  public CompletionStage<List<FunctionTool>> discoverTools() {
    return discovery.discover();
  }

  /**
   * Closes the current connection and leaves this provider reusable.
   *
   * @return a stage completing when the connection is released, never {@code null}
   */
  public CompletionStage<Void> close() {
    return lifecycle.close();
  }
}
