package io.github.hellices.agentframework.mcp.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.hellices.agentframework.api.tool.FunctionTool;
import io.github.hellices.agentframework.mcp.McpToolAdapterOptions;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

/**
 * A reader restarts a paged read once, whatever the port keeps reporting.
 *
 * <p>The lifecycle allows one replacement per paged read, so a second replacement signal does not
 * arrive in production. This test drives the reader through the port directly, because a bound that
 * is only enforced by a collaborator is a bound that disappears the day that collaborator changes.
 */
class McpToolDiscoveryRestartTest {

  @Test
  void restartsAtMostOncePerDiscovery() {
    ReplacingPort port = new ReplacingPort(2);

    Throwable failure =
        catchThrowable(
            () -> new McpToolDiscovery(port, McpToolAdapterOptions.defaults()).discover().join());

    // Two replacements were reported. The first spent the reader's restart and asked for the first
    // page again; the second had none left and reached the caller, so no third read was started.
    assertThat(failure).hasCauseInstanceOf(McpConnectionReplacedException.class);
    assertThat(port.requestedCursors()).containsExactly(null, null);
  }

  @Test
  void restartsAPageThatWasStillInFlightWhenTheConnectionWasReplaced() {
    PendingPort port = new PendingPort();

    CompletableFuture<List<FunctionTool>> discovery =
        new McpToolDiscovery(port, McpToolAdapterOptions.defaults()).discover();
    assertThat(discovery).isNotDone();

    port.reportReplacement();

    // A page of a real transport is answered later and on another thread, so the replacement is
    // usually reported to a request that is still in flight rather than to one that already failed.
    assertThat(discovery.join())
        .extracting(tool -> tool.definition().name())
        .containsExactly("beta");
    assertThat(port.requestedCursors()).containsExactly(null, null);
  }

  /** Reports a replaced connection for the first {@code replacements} pages, then answers. */
  private static final class ReplacingPort implements McpAsyncOperations {

    private final List<String> requestedCursors = new ArrayList<>();
    private final int replacements;

    ReplacingPort(int replacements) {
      this.replacements = replacements;
    }

    List<String> requestedCursors() {
      return new ArrayList<>(requestedCursors);
    }

    @Override
    public CompletionStage<McpSchema.ListToolsResult> listTools(
        String cursor, Map<String, Object> meta) {
      requestedCursors.add(cursor);
      if (requestedCursors.size() <= replacements) {
        return AsyncStages.failed(
            new McpConnectionReplacedException(new IllegalStateException("connection lost")));
      }
      return CompletableFuture.completedFuture(
          new McpSchema.ListToolsResult(List.of(), null, null));
    }

    @Override
    public CompletionStage<McpSchema.CallToolResult> callTool(McpSchema.CallToolRequest request) {
      throw new UnsupportedOperationException("this port only reads pages");
    }
  }

  /** Leaves the first page in flight until the test reports that the connection was replaced. */
  private static final class PendingPort implements McpAsyncOperations {

    private final List<String> requestedCursors = new ArrayList<>();
    private final CompletableFuture<McpSchema.ListToolsResult> inFlight = new CompletableFuture<>();

    List<String> requestedCursors() {
      return new ArrayList<>(requestedCursors);
    }

    void reportReplacement() {
      inFlight.completeExceptionally(
          new McpConnectionReplacedException(new IllegalStateException("connection lost")));
    }

    @Override
    public CompletionStage<McpSchema.ListToolsResult> listTools(
        String cursor, Map<String, Object> meta) {
      requestedCursors.add(cursor);
      if (requestedCursors.size() == 1) {
        return inFlight.copy();
      }
      return CompletableFuture.completedFuture(
          new McpSchema.ListToolsResult(
              List.of(
                  new McpSchema.Tool(
                      "beta",
                      null,
                      "beta",
                      Map.of("type", "object", "properties", Map.of()),
                      null,
                      null,
                      null,
                      null)),
              null,
              null));
    }

    @Override
    public CompletionStage<McpSchema.CallToolResult> callTool(McpSchema.CallToolRequest request) {
      throw new UnsupportedOperationException("this port only reads pages");
    }
  }
}
