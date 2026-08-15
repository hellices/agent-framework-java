package io.github.hellices.agentframework.mcp.internal;

import io.github.hellices.agentframework.api.tool.FunctionTool;
import io.github.hellices.agentframework.mcp.McpToolAdapterOptions;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Reads every page of a server's tool list and turns the entries into framework tools.
 *
 * <p>Pagination is driven here rather than by the SDK convenience overload, because that overload
 * drops the cursor of the last page it read and would silently return a truncated catalogue. Pages
 * are followed until the server reports no further cursor, and a repeated cursor fails the
 * discovery instead of looping forever on a server that does not advance.
 */
public final class McpToolDiscovery {

  private static final Pattern UNSUPPORTED_NAME_CHARACTERS = Pattern.compile("[^A-Za-z0-9_-]+");

  private final McpAsyncOperations operations;
  private final McpToolAdapterOptions options;
  private final McpToolInvoker invoker;
  private final String normalizedPrefix;

  /**
   * Creates a discovery.
   *
   * @param operations operations over the borrowed client, never {@code null}
   * @param options adapter options, never {@code null}
   */
  public McpToolDiscovery(McpAsyncOperations operations, McpToolAdapterOptions options) {
    this.operations = operations;
    this.options = options;
    this.invoker = new McpToolInvoker(operations, options);
    this.normalizedPrefix = normalize(options.localNamePrefix());
  }

  /**
   * Discovers every tool the server publishes.
   *
   * <p>Cancelling the returned stage cancels the page request that is in flight, so a caller that
   * gives up does not leave the borrowed client working on its behalf.
   *
   * @return a stage completing with the tools in server order, never {@code null}
   */
  public CompletableFuture<List<FunctionTool>> discover() {
    AtomicReference<CompletableFuture<McpSchema.ListToolsResult>> pending = new AtomicReference<>();
    return AsyncStages.cancellable(
        readPages(McpSchema.FIRST_PAGE, new HashSet<>(), new LinkedHashMap<>(), pending),
        () -> {
          CompletableFuture<McpSchema.ListToolsResult> inFlight = pending.get();
          if (inFlight != null) {
            inFlight.cancel(true);
          }
        });
  }

  private CompletableFuture<List<FunctionTool>> readPages(
      String cursor,
      Set<String> seenCursors,
      Map<String, String> localNames,
      AtomicReference<CompletableFuture<McpSchema.ListToolsResult>> pending) {
    return AsyncStages.callSafely(
        () -> {
          CompletableFuture<McpSchema.ListToolsResult> page =
              AsyncStages.requireStage(operations.listTools(cursor, null), "tools/list");
          pending.set(page);
          return page.thenCompose(
              result -> {
                List<FunctionTool> tools = collect(result, cursor, localNames);
                String nextCursor = result.nextCursor();
                if (nextCursor == null || nextCursor.isBlank()) {
                  return CompletableFuture.completedFuture(tools);
                }
                if (!seenCursors.add(nextCursor)) {
                  throw new IllegalStateException(
                      "MCP server repeated tool list cursor '"
                          + nextCursor
                          + "', which would page forever");
                }
                return readPages(nextCursor, seenCursors, localNames, pending)
                    .thenApply(
                        remaining -> {
                          List<FunctionTool> all = new ArrayList<>(tools);
                          all.addAll(remaining);
                          return all;
                        });
              });
        });
  }

  private List<FunctionTool> collect(
      McpSchema.ListToolsResult page, String cursor, Map<String, String> localNames) {
    if (page == null) {
      throw new IllegalStateException(
          "MCP tools/list returned no result for cursor " + describe(cursor));
    }
    List<McpSchema.Tool> remoteTools = page.tools();
    if (remoteTools == null) {
      throw new IllegalStateException(
          "MCP tools/list returned no tools for cursor " + describe(cursor));
    }
    List<FunctionTool> tools = new ArrayList<>();
    for (McpSchema.Tool remoteTool : remoteTools) {
      tools.add(toFunctionTool(remoteTool, localNames));
    }
    return tools;
  }

  private FunctionTool toFunctionTool(McpSchema.Tool remoteTool, Map<String, String> localNames) {
    if (remoteTool == null) {
      throw new IllegalStateException("MCP tools/list published a null tool");
    }
    String remoteName = remoteTool.name();
    if (remoteName == null || remoteName.isBlank()) {
      throw new IllegalStateException("MCP tools/list published a tool without a name");
    }
    Map<String, Object> inputSchema = remoteTool.inputSchema();
    if (inputSchema == null) {
      throw new IllegalStateException(
          "MCP tools/list published tool '" + remoteName + "' without an input schema");
    }
    String localName = normalizedPrefix + normalize(remoteName);
    String previousRemoteName = localNames.put(localName, remoteName);
    if (previousRemoteName != null) {
      throw new IllegalStateException(
          "MCP tools '"
              + previousRemoteName
              + "' and '"
              + remoteName
              + "' both map to local name '"
              + localName
              + "'");
    }
    return FunctionTool.create(
        localName,
        remoteTool.description(),
        inputSchema,
        invoker.handlerFor(localName, remoteName, inputSchema));
  }

  /**
   * Returns a name that only uses characters a tool name may contain.
   *
   * <p>A model addresses a tool by name and providers restrict that name, while MCP allows names
   * this framework cannot expose verbatim. Replacing each run of unsupported characters with a
   * single underscore is deterministic, so the same server always yields the same local names.
   */
  private static String normalize(String name) {
    return UNSUPPORTED_NAME_CHARACTERS.matcher(name).replaceAll("_");
  }

  private static String describe(String cursor) {
    return cursor == null ? "the first page" : "'" + cursor + "'";
  }
}
