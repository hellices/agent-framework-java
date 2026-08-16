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
 * <p>The instance a caller constructs is immutable and safe to share. The instance {@link
 * #forPagedOperation()} returns is not: it carries the single attempt, and therefore the single
 * reconnect budget, of one paged read, so it belongs to that read and must not outlive it.
 */
public final class OwnedMcpAsyncOperations implements McpAsyncOperations {

  private final OwnedMcpClientLifecycle lifecycle;
  private final OwnedMcpClientLifecycle.Attempt pagedOperation;

  /**
   * Creates operations over an owned lifecycle.
   *
   * @param lifecycle the lifecycle that owns the client, never {@code null}
   * @throws IllegalArgumentException if the lifecycle is {@code null}
   */
  public OwnedMcpAsyncOperations(OwnedMcpClientLifecycle lifecycle) {
    this(lifecycle, null);
  }

  private OwnedMcpAsyncOperations(
      OwnedMcpClientLifecycle lifecycle, OwnedMcpClientLifecycle.Attempt pagedOperation) {
    if (lifecycle == null) {
      throw new IllegalArgumentException("lifecycle must not be null");
    }
    this.lifecycle = lifecycle;
    this.pagedOperation = pagedOperation;
  }

  @Override
  public McpAsyncOperations forPagedOperation() {
    return new OwnedMcpAsyncOperations(lifecycle, OwnedMcpClientLifecycle.pagedAttempt());
  }

  @Override
  public CompletionStage<McpSchema.ListToolsResult> listTools(
      String cursor, Map<String, Object> meta) {
    // Always a paged attempt, scoped or not. A page request must never be repeated with its own
    // cursor on a new session, and an unscoped listTools has no reader to restart, so the honest
    // outcome there is a failure rather than a silently wrong page.
    OwnedMcpClientLifecycle.Attempt attempt =
        pagedOperation == null ? OwnedMcpClientLifecycle.pagedAttempt() : pagedOperation;
    return lifecycle.execute(client -> client.listTools(cursor, meta), attempt);
  }

  @Override
  public CompletionStage<McpSchema.CallToolResult> callTool(McpSchema.CallToolRequest request) {
    if (request == null) {
      return AsyncStages.failed(new IllegalArgumentException("request must not be null"));
    }
    // Deliberately the unscoped execute, even on an instance scoped to a paged read. A tool call is
    // one request made long after the discovery that found the tool, so it is its own operation
    // with its own budget rather than a straggler of the read's.
    return lifecycle.execute(client -> client.callTool(request));
  }
}
