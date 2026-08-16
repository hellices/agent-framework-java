package io.github.hellices.agentframework.mcp;

import io.github.hellices.agentframework.mcp.internal.McpAsyncOperations;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/**
 * Scripted stand-in for the SDK-backed operations port.
 *
 * <p>The SDK client cannot be faked: {@code McpAsyncClient} has a package-private constructor, so
 * it can neither be subclassed nor instantiated from here. Driving the adapter through its internal
 * port is what keeps discovery, argument filtering, metadata separation, and failure mapping
 * testable without a process, a socket, or a mocking framework.
 *
 * <p>The port carries no lifecycle method, so a test can never observe the borrowed client being
 * closed through it. {@link ConnectedMcpClientAdapterTest} proves that absence separately.
 */
final class FakeMcpAsyncOperations implements McpAsyncOperations {

  private final List<String> requestedCursors = new ArrayList<>();
  private final List<Map<String, Object>> requestedListMetadata = new ArrayList<>();
  private final List<McpSchema.CallToolRequest> callRequests = new ArrayList<>();
  private final Deque<CompletionStage<McpSchema.ListToolsResult>> scriptedPages =
      new ArrayDeque<>();

  private Function<String, CompletionStage<McpSchema.ListToolsResult>> listing;
  private boolean pagedScopeMissing;
  private Function<McpSchema.CallToolRequest, CompletionStage<McpSchema.CallToolResult>> calling =
      request -> CompletableFuture.completedFuture(callResult(List.of(), null));

  static McpSchema.ListToolsResult page(List<McpSchema.Tool> tools, String nextCursor) {
    return new McpSchema.ListToolsResult(tools, nextCursor, null);
  }

  static McpSchema.CallToolResult callResult(List<McpSchema.Content> content, Boolean error) {
    return new McpSchema.CallToolResult(content, error, null, null);
  }

  static McpSchema.Tool tool(String name, Map<String, Object> inputSchema) {
    return new McpSchema.Tool(
        name, null, name + " description", inputSchema, null, null, null, null);
  }

  static Map<String, Object> objectSchema(String... properties) {
    Map<String, Object> declared = new LinkedHashMap<>();
    for (String property : properties) {
      declared.put(property, Map.of("type", "string"));
    }
    return Map.of("type", "object", "properties", declared);
  }

  FakeMcpAsyncOperations pages(McpSchema.ListToolsResult... pages) {
    for (McpSchema.ListToolsResult page : pages) {
      scriptedPages.add(CompletableFuture.completedFuture(page));
    }
    return this;
  }

  FakeMcpAsyncOperations stages(List<CompletionStage<McpSchema.ListToolsResult>> stages) {
    scriptedPages.addAll(stages);
    return this;
  }

  FakeMcpAsyncOperations listing(
      Function<String, CompletionStage<McpSchema.ListToolsResult>> listing) {
    this.listing = listing;
    return this;
  }

  FakeMcpAsyncOperations calling(
      Function<McpSchema.CallToolRequest, CompletionStage<McpSchema.CallToolResult>> calling) {
    this.calling = calling;
    return this;
  }

  FakeMcpAsyncOperations answering(McpSchema.CallToolResult result) {
    return calling(request -> CompletableFuture.completedFuture(result));
  }

  /**
   * Hands out no scope for a paged read, which is the one way an implementation of the port can
   * break the contract that {@code forPagedOperation()} never returns {@code null}.
   */
  FakeMcpAsyncOperations withoutPagedScope() {
    this.pagedScopeMissing = true;
    return this;
  }

  @Override
  public McpAsyncOperations forPagedOperation() {
    return pagedScopeMissing ? null : this;
  }

  List<String> requestedCursors() {
    return new ArrayList<>(requestedCursors);
  }

  List<Map<String, Object>> requestedListMetadata() {
    return new ArrayList<>(requestedListMetadata);
  }

  McpSchema.CallToolRequest lastCallRequest() {
    if (callRequests.isEmpty()) {
      throw new IllegalStateException("no tools/call request was recorded");
    }
    return callRequests.get(callRequests.size() - 1);
  }

  @Override
  public CompletionStage<McpSchema.ListToolsResult> listTools(
      String cursor, Map<String, Object> meta) {
    requestedCursors.add(cursor);
    requestedListMetadata.add(meta);
    if (listing != null) {
      return listing.apply(cursor);
    }
    if (scriptedPages.isEmpty()) {
      throw new IllegalStateException("no scripted tools/list page for cursor " + cursor);
    }
    return scriptedPages.removeFirst();
  }

  @Override
  public CompletionStage<McpSchema.CallToolResult> callTool(McpSchema.CallToolRequest request) {
    callRequests.add(request);
    return calling.apply(request);
  }
}
