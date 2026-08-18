package io.github.hellices.agentframework.mcp.internal;

import io.github.hellices.agentframework.api.tool.FunctionTool;
import io.github.hellices.agentframework.api.value.JsonObject;
import io.github.hellices.agentframework.api.value.JsonValue;
import io.github.hellices.agentframework.api.value.JsonValues;
import io.github.hellices.agentframework.mcp.McpToolAdapterOptions;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Reads every page of a server's tool list and turns the entries into framework tools.
 *
 * <p>Pagination is driven here rather than by the SDK convenience overload, because that overload
 * drops the cursor of the last page it read and would silently return a truncated catalogue. Pages
 * are followed until the server reports no further cursor. A repeated cursor and a run of pages
 * longer than the configured bound both fail the discovery, because a server that does not advance
 * and a server that never stops advancing are both faults a client should not be paged through
 * forever.
 *
 * <p>A whole discovery is one logical operation. When the operations port reports that the
 * connection behind a page was replaced, everything read so far is discarded and the catalogue is
 * read again from its first page, because a cursor belongs to the session that issued it. A port
 * over a borrowed client never reports that, so borrowed discovery is unaffected.
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
   * <p>Cancelling the returned stage cancels the page request that is in flight and stops the
   * paging, so a caller that gives up does not leave the client working on its behalf, including
   * when the cancellation arrives in the gap between one page completing and the next being
   * requested, and including after a restart.
   *
   * <p>The pages of one discovery are read through one scope, taken from the port here rather than
   * per page, because the whole read is one operation: what the scope carries is the single
   * recovery budget the pages share.
   *
   * @return a stage completing with the tools in server order, never {@code null}
   */
  public CompletableFuture<List<FunctionTool>> discover() {
    return AsyncStages.callSafely(
        () -> {
          PageReader reader = new PageReader(requireScope(operations.forPagedOperation()));
          return AsyncStages.cancellable(reader.readAll(), reader::cancel);
        });
  }

  /**
   * Returns the scope one discovery reads its pages through, rejecting a port that hands out none.
   *
   * <p>A port that answers with nothing would otherwise surface as a null pointer failure from the
   * first page request rather than as a statement about the port that broke its contract.
   */
  private static McpAsyncOperations requireScope(McpAsyncOperations scope) {
    if (scope == null) {
      throw new IllegalStateException("MCP operations returned no scope for a paged read");
    }
    return scope;
  }

  /**
   * Reads the pages of one discovery, accumulating the tools in server order.
   *
   * <p>One page is outstanding at a time and the completion of each page happens before the request
   * for the next one, so the paging state is only ever touched by one thread at a time even though
   * that thread changes when the SDK answers from its own.
   */
  private final class PageReader {

    private final McpAsyncOperations pageOperations;
    private final AtomicReference<CompletableFuture<McpSchema.ListToolsResult>> pending =
        new AtomicReference<>();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean restartable = new AtomicBoolean(true);
    private final Set<String> seenCursors = new HashSet<>();
    private final Map<String, String> localNames = new LinkedHashMap<>();
    private final List<FunctionTool> tools = new ArrayList<>();

    private String cursor = McpSchema.FIRST_PAGE;

    PageReader(McpAsyncOperations pageOperations) {
      this.pageOperations = pageOperations;
    }

    CompletableFuture<List<FunctionTool>> readAll() {
      CompletableFuture<List<FunctionTool>> discovered = new CompletableFuture<>();
      drive(discovered);
      return discovered;
    }

    void cancel() {
      cancelled.set(true);
      CompletableFuture<McpSchema.ListToolsResult> inFlight = pending.get();
      if (inFlight != null) {
        inFlight.cancel(true);
      }
    }

    /**
     * Requests pages until the discovery finishes or a page is still in flight.
     *
     * <p>A server that answers immediately completes its page on the requesting thread, so chaining
     * the next request onto the stage of the previous page would nest one set of frames per page
     * and exhaust the stack before a generously configured page bound was reached. Pages that are
     * already complete are therefore followed by this loop, and only a page that is still in flight
     * re-enters through its own completion.
     */
    private void drive(CompletableFuture<List<FunctionTool>> discovered) {
      while (true) {
        String requested = cursor;
        CompletableFuture<McpSchema.ListToolsResult> page = requestPage(discovered);
        if (page == null) {
          return;
        }
        if (!page.isDone()) {
          page.whenComplete(
              (result, failure) -> {
                if (failure != null) {
                  if (restart(failure)) {
                    drive(discovered);
                  } else {
                    discovered.completeExceptionally(AsyncStages.unwrap(failure));
                  }
                } else if (advance(result, requested, discovered)) {
                  drive(discovered);
                }
              });
          return;
        }
        McpSchema.ListToolsResult result;
        try {
          result = page.join();
        } catch (RuntimeException failure) {
          if (restart(failure)) {
            continue;
          }
          discovered.completeExceptionally(AsyncStages.unwrap(failure));
          return;
        }
        if (!advance(result, requested, discovered)) {
          return;
        }
      }
    }

    /**
     * Requests the next page, or fails the discovery and returns {@code null} when it cannot be
     * requested.
     */
    private CompletableFuture<McpSchema.ListToolsResult> requestPage(
        CompletableFuture<List<FunctionTool>> discovered) {
      // Cancelling the in flight page is a no-op once that page has completed, so the flag is what
      // stops the next request from being sent for a caller that has already gone.
      if (cancelled.get()) {
        discovered.completeExceptionally(new CancellationException("MCP tool discovery cancelled"));
        return null;
      }
      CompletableFuture<McpSchema.ListToolsResult> page;
      try {
        page = AsyncStages.requireStage(pageOperations.listTools(cursor, null), "tools/list");
      } catch (RuntimeException failure) {
        discovered.completeExceptionally(failure);
        return null;
      }
      pending.set(page);
      if (cancelled.get()) {
        page.cancel(true);
      }
      return page;
    }

    /**
     * Throws away everything read so far and starts the catalogue again from its first page, when
     * the page failed only because the connection behind it was replaced.
     *
     * <p>Everything has to go, not just the cursor. The collected tools came from a session that no
     * longer exists, and keeping them would either duplicate the entries the new session
     * republishes or fail the discovery on a local name collision with them. The seen cursors have
     * to go for a blunter reason: the new session will very likely hand out the same cursor
     * strings, and the repeated-cursor guard would read that as a server paging forever. Clearing
     * them also restores the page bound, so the replacement gets a whole catalogue's worth of pages
     * rather than whatever the dead session left over.
     *
     * <p>The flag bounds this loop on its own. The lifecycle also allows one replacement per paged
     * read, so in practice the second restart never arrives, but this class already bounds paging
     * by repeated cursors and by a page count, and a reader that can be restarted by something
     * outside it should bound that too.
     */
    private boolean restart(Throwable failure) {
      if (!(AsyncStages.unwrap(failure) instanceof McpConnectionReplacedException)) {
        return false;
      }
      if (!restartable.compareAndSet(true, false)) {
        return false;
      }
      tools.clear();
      localNames.clear();
      seenCursors.clear();
      cursor = McpSchema.FIRST_PAGE;
      return true;
    }

    /**
     * Collects a page and reports whether another one should be requested, completing the discovery
     * when the catalogue ends or the server pages beyond what it is allowed to.
     */
    private boolean advance(
        McpSchema.ListToolsResult result,
        String requested,
        CompletableFuture<List<FunctionTool>> discovered) {
      try {
        collect(result, requested);
      } catch (RuntimeException failure) {
        discovered.completeExceptionally(failure);
        return false;
      }
      String nextCursor = result.nextCursor();
      if (nextCursor == null || nextCursor.isBlank()) {
        discovered.complete(List.copyOf(tools));
        return false;
      }
      if (!seenCursors.add(nextCursor)) {
        discovered.completeExceptionally(
            new IllegalStateException(
                "MCP server repeated tool list cursor '"
                    + nextCursor
                    + "', which would page forever"));
        return false;
      }
      // Every page after the first is requested with a cursor that was recorded here, so the number
      // of recorded cursors is the number of pages that have already been read.
      if (seenCursors.size() >= options.maxDiscoveryPages()) {
        discovered.completeExceptionally(
            new IllegalStateException(
                "MCP server published more than "
                    + options.maxDiscoveryPages()
                    + " tool list pages, which is the configured discovery bound"));
        return false;
      }
      cursor = nextCursor;
      return true;
    }

    private void collect(McpSchema.ListToolsResult page, String cursor) {
      if (page == null) {
        throw new IllegalStateException(
            "MCP tools/list returned no result for cursor " + describe(cursor));
      }
      List<McpSchema.Tool> remoteTools = page.tools();
      if (remoteTools == null) {
        throw new IllegalStateException(
            "MCP tools/list returned no tools for cursor " + describe(cursor));
      }
      for (McpSchema.Tool remoteTool : remoteTools) {
        tools.add(toFunctionTool(remoteTool, localNames));
      }
    }
  }

  private FunctionTool toFunctionTool(McpSchema.Tool remoteTool, Map<String, String> localNames) {
    if (remoteTool == null) {
      throw new IllegalStateException("MCP tools/list published a null tool");
    }
    String remoteName = remoteTool.name();
    if (remoteName == null || remoteName.isBlank()) {
      throw new IllegalStateException("MCP tools/list published a tool without a name");
    }
    Map<String, Object> publishedSchema = remoteTool.inputSchema();
    if (publishedSchema == null) {
      throw new IllegalStateException(
          "MCP tools/list published tool '" + remoteName + "' without an input schema");
    }
    JsonValue normalizedSchema = JsonValues.fromJava(publishedSchema);
    if (!(normalizedSchema instanceof JsonObject inputSchema)) {
      throw new IllegalStateException(
          "MCP tools/list published tool '" + remoteName + "' with a non-object input schema");
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
