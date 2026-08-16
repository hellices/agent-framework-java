package io.github.hellices.agentframework.mcp.internal;

import io.github.hellices.agentframework.api.tool.ToolHandler;
import io.github.hellices.agentframework.mcp.McpToolAdapterOptions;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Builds the handler that runs a discovered MCP tool.
 *
 * <p>The handler keeps the name the server published, so a local name that was normalized or
 * prefixed for the model never reaches the wire. The accepted argument names are computed once at
 * discovery, because the input schema of a discovered tool does not change while the tool exists.
 *
 * <p>An invocation stops on either cancellation channel: the stage the framework holds and the
 * cancellation signal of the run that asked for the call. The tool loop uses the second one, so
 * without it a cancelled run would leave the borrowed client working until the SDK request timeout.
 */
public final class McpToolInvoker {

  private final McpAsyncOperations operations;
  private final McpArgumentMapper argumentMapper;
  private final McpResultMapper resultMapper;

  /**
   * Creates an invoker.
   *
   * @param operations operations over the borrowed client, never {@code null}
   * @param options adapter options, never {@code null}
   */
  public McpToolInvoker(McpAsyncOperations operations, McpToolAdapterOptions options) {
    this.operations = operations;
    this.argumentMapper = new McpArgumentMapper(options);
    this.resultMapper = new McpResultMapper(options);
  }

  /**
   * Returns the handler for one discovered tool.
   *
   * @param localName the name the model sees, never {@code null}
   * @param remoteName the name the server published, never {@code null}
   * @param inputSchema the input schema the server published, never {@code null}
   * @return the handler, never {@code null}
   */
  public ToolHandler handlerFor(
      String localName, String remoteName, Map<String, Object> inputSchema) {
    Set<String> acceptedArgumentNames = argumentMapper.acceptedArgumentNames(inputSchema);
    return (arguments, context) ->
        AsyncStages.callSafely(
            () -> {
              McpSchema.CallToolRequest request =
                  argumentMapper.toRequest(remoteName, acceptedArgumentNames, arguments, context);
              CompletableFuture<McpSchema.CallToolResult> call =
                  AsyncStages.requireStage(operations.callTool(request), "tools/call");
              return AsyncStages.cancelledWithRun(
                  context.cancellationSignal(),
                  () -> call.cancel(true),
                  AsyncStages.cancellable(
                      call.thenApply(result -> resultMapper.toToolResult(localName, result)),
                      () -> call.cancel(true)));
            });
  }
}
