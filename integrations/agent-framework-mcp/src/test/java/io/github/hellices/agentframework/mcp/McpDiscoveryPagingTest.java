package io.github.hellices.agentframework.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.hellices.agentframework.api.tool.FunctionTool;
import io.github.hellices.agentframework.api.tool.ToolArguments;
import io.github.hellices.agentframework.api.tool.ToolContext;
import io.github.hellices.agentframework.api.tool.ToolResult;
import io.github.hellices.agentframework.mcp.internal.McpOwnedClientSettings;
import io.github.hellices.agentframework.mcp.internal.McpToolDiscovery;
import io.github.hellices.agentframework.mcp.internal.OwnedMcpAsyncOperations;
import io.github.hellices.agentframework.mcp.internal.OwnedMcpClientLifecycle;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpTransportSessionNotFoundException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * A paged discovery is one operation, and a cursor belongs to the session that issued it.
 *
 * <p>Every test records the cursors each transport was asked for, because that list is the whole
 * contract: a cursor from a dead session must never appear in a request to a live one, and a
 * discovery that had to reconnect must start again from the first page rather than stitch two
 * catalogues together.
 */
class McpDiscoveryPagingTest {

  private static final Duration BLOCK = Duration.ofSeconds(5);

  private static final Map<String, Object> SCHEMA =
      Map.of("type", "object", "properties", Map.of("query", Map.of("type", "string")));

  @Test
  void readsEveryPageOfOneGenerationInOrder() {
    List<String> cursors = new ArrayList<>();
    InMemoryMcpTransport transport = catalogue(cursors, Integer.MAX_VALUE);
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(transport);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());
    lifecycle.connect().join();

    List<FunctionTool> tools = discover(lifecycle);

    assertThat(names(tools)).containsExactly("search-issues", "close-issues");
    assertThat(cursors).containsExactly(null, "page-2");
    assertThat(factory.createdCount()).isEqualTo(1);
  }

  @Test
  void restartsFromTheFirstPageOnTheReplacementGeneration() {
    List<String> staleCursors = new ArrayList<>();
    List<String> freshCursors = new ArrayList<>();
    InMemoryMcpTransport stale = catalogue(staleCursors, 1);
    InMemoryMcpTransport fresh = catalogue(freshCursors, Integer.MAX_VALUE);
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(stale, fresh);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());
    lifecycle.connect().join();

    List<FunctionTool> tools = discover(lifecycle);

    // The replacement is asked for the first page, not for the cursor the dead session handed out,
    // and the result is that session's catalogue alone. Page one of the stale generation was
    // discarded: had it been kept, its "search-issues" would have collided with the replacement's
    // and failed the discovery, and had it been merged, there would be three tools here.
    assertThat(staleCursors).containsExactly(null, "page-2");
    assertThat(freshCursors).containsExactly(null, "page-2");
    assertThat(names(tools)).containsExactly("search-issues", "close-issues");
    assertThat(factory.createdCount()).isEqualTo(2);
    assertThat(stale.closeCount()).isEqualTo(1);
    assertThat(fresh.closeCount()).isZero();
  }

  @Test
  void surfacesASecondConnectionFailureWithoutAThirdGeneration() {
    List<String> staleCursors = new ArrayList<>();
    List<String> freshCursors = new ArrayList<>();
    InMemoryMcpTransport stale = catalogue(staleCursors, 1);
    InMemoryMcpTransport fresh = catalogue(freshCursors, 1);
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(stale, fresh);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());
    lifecycle.connect().join();

    assertThatThrownBy(() -> discover(lifecycle))
        .rootCause()
        .isInstanceOf(McpTransportSessionNotFoundException.class);

    assertThat(factory.createdCount()).isEqualTo(2);
    assertThat(freshCursors).containsExactly(null, "page-2");
    assertThat(fresh.closeCount()).isZero();
  }

  @Test
  void restartsAPageThatASiblingsReplacementDismissed() {
    List<String> staleCursors = new ArrayList<>();
    List<String> freshCursors = new ArrayList<>();
    InMemoryMcpTransport stale = catalogueWithheldAfterTheFirstPage(staleCursors);
    InMemoryMcpTransport fresh = catalogue(freshCursors, Integer.MAX_VALUE);
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(stale, fresh);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());
    lifecycle.connect().join();

    CompletableFuture<List<FunctionTool>> discovery =
        new McpToolDiscovery(
                new OwnedMcpAsyncOperations(lifecycle), McpToolAdapterOptions.defaults())
            .discover();
    // The second page is on the wire and unanswered, which is the request a sibling's replacement
    // dismisses.
    assertThat(discovery).isNotDone();
    assertThat(stale.countOf(McpSchema.METHOD_TOOLS_LIST)).isEqualTo(2);

    // A neighbouring tool call loses the session and replaces the generation; closing it dismisses
    // the page in flight with the SDK's untyped failure.
    CompletableFuture<McpSchema.CallToolResult> sibling =
        new OwnedMcpAsyncOperations(lifecycle)
            .callTool(new McpSchema.CallToolRequest("search-issues", null, null))
            .toCompletableFuture();

    assertThat(sibling).succeedsWithin(BLOCK);
    // A page read has no side effect, so the read recovers from a dismissal its own owner caused —
    // it spends its one reconnect on the replacement the sibling already built rather than buying a
    // third generation, and starts the catalogue again from the first page. The replacement is
    // never asked to continue the cursor the dead session issued.
    assertThat(discovery).succeedsWithin(BLOCK);
    assertThat(names(discovery.join())).containsExactly("search-issues", "close-issues");
    assertThat(staleCursors).containsExactly((String) null);
    assertThat(freshCursors).containsExactly(null, "page-2").doesNotContain("stale-page-2");
    assertThat(factory.createdCount()).isEqualTo(2);
    assertThat(stale.closeCount()).isEqualTo(1);
    assertThat(fresh.closeCount()).isZero();
  }

  @Test
  void aFailedReplacementDuringAPagedReadReportsTheFailureThatStoppedIt() {
    List<String> staleCursors = new ArrayList<>();
    List<String> refusedCursors = new ArrayList<>();
    InMemoryMcpTransport stale = catalogue(staleCursors, 1);
    InMemoryMcpTransport refused =
        catalogue(refusedCursors, Integer.MAX_VALUE)
            .failingSend(
                McpSchema.METHOD_INITIALIZE, () -> new IllegalStateException("handshake refused"));
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(stale, refused);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());
    lifecycle.connect().join();

    Throwable failure = catchThrowable(() -> discover(lifecycle));

    // No replacement exists, so there is nothing to restart on and nothing to call a read replaced
    // by. The caller is told what actually stopped the second page — the session that issued the
    // cursor is gone — and the handshake that could not repair it is attached to that failure
    // rather than reported in its place.
    assertThat(failure)
        .rootCause()
        .isInstanceOf(McpTransportSessionNotFoundException.class)
        .satisfies(
            stopped ->
                assertThat(stopped.getSuppressed())
                    .singleElement()
                    .satisfies(
                        suppressed ->
                            assertThat(suppressed).hasStackTraceContaining("handshake refused")));
    assertThat(staleCursors).containsExactly(null, "page-2");
    assertThat(refusedCursors).isEmpty();
    assertThat(factory.createdCount()).isEqualTo(2);
  }

  @Test
  void aSecondDiscoveryThroughTheSamePortGetsItsOwnBudget() {
    InMemoryMcpTransport first = catalogue(new ArrayList<>(), 1);
    InMemoryMcpTransport second = catalogue(new ArrayList<>(), 3);
    InMemoryMcpTransport third = catalogue(new ArrayList<>(), Integer.MAX_VALUE);
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(first, second, third);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());
    lifecycle.connect().join();
    OwnedMcpAsyncOperations operations = new OwnedMcpAsyncOperations(lifecycle);
    McpToolDiscovery discovery = new McpToolDiscovery(operations, McpToolAdapterOptions.defaults());

    List<FunctionTool> firstCatalogue = discovery.discover().toCompletableFuture().join();
    assertThat(factory.createdCount()).isEqualTo(2);

    List<FunctionTool> secondCatalogue = discovery.discover().toCompletableFuture().join();

    // The budget belongs to one read, not to the port that served it. The first discovery spent its
    // reconnect on the second generation; the second loses its own connection mid-read and must
    // still be able to buy the third, or a long-lived adapter could re-discover exactly once in its
    // lifetime.
    assertThat(names(firstCatalogue)).containsExactly("search-issues", "close-issues");
    assertThat(names(secondCatalogue)).containsExactly("search-issues", "close-issues");
    assertThat(factory.createdCount()).isEqualTo(3);
  }

  @Test
  void givesEachDiscoveredToolItsOwnReconnectBudget() {
    InMemoryMcpTransport stale = catalogue(new ArrayList<>(), 1);
    InMemoryMcpTransport fresh =
        catalogue(new ArrayList<>(), Integer.MAX_VALUE)
            .failingSend(
                McpSchema.METHOD_TOOLS_CALL,
                () -> new McpTransportSessionNotFoundException("session expired"));
    InMemoryMcpTransport third = catalogue(new ArrayList<>(), Integer.MAX_VALUE);
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(stale, fresh, third);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());
    lifecycle.connect().join();

    List<FunctionTool> tools = discover(lifecycle);
    assertThat(factory.createdCount()).isEqualTo(2);

    ToolResult result =
        tools
            .get(0)
            .execute(new ToolArguments(Map.of("query", "open")), new ToolContext(null, Map.of()))
            .toCompletableFuture()
            .join();

    // The discovery spent its own budget and the tool still has one, because a tool call made
    // minutes later is not part of the operation that discovered it.
    assertThat(result.error()).isFalse();
    assertThat(factory.createdCount()).isEqualTo(3);
    assertThat(third.countOf(McpSchema.METHOD_TOOLS_CALL)).isEqualTo(1);
  }

  @Test
  void stopsPagingWhenTheCallerCancelsAfterARestart() {
    InMemoryMcpTransport stale = catalogue(new ArrayList<>(), 1);
    List<String> freshCursors = new ArrayList<>();
    InMemoryMcpTransport fresh =
        catalogue(freshCursors, Integer.MAX_VALUE).withholding(McpSchema.METHOD_TOOLS_LIST);
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(stale, fresh);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());
    lifecycle.connect().join();

    CompletableFuture<List<FunctionTool>> discovery =
        new McpToolDiscovery(
                new OwnedMcpAsyncOperations(lifecycle), McpToolAdapterOptions.defaults())
            .discover();
    // The restart already happened, and its first page is in flight on the replacement.
    assertThat(discovery).isNotDone();
    assertThat(fresh.countOf(McpSchema.METHOD_TOOLS_LIST)).isEqualTo(1);

    discovery.cancel(true);
    // Answering the page the caller withdrew from must not start the next one: a restart does not
    // hand the reader a second life, and a cancelled discovery stays cancelled.
    fresh.releaseWithheld();

    assertThatThrownBy(discovery::join).isInstanceOf(CancellationException.class);
    assertThat(freshCursors).containsExactly((String) null);
    assertThat(fresh.countOf(McpSchema.METHOD_TOOLS_LIST)).isEqualTo(1);
    assertThat(factory.createdCount()).isEqualTo(2);
    assertThat(fresh.closeCount()).isZero();
  }

  @Test
  void refusesToRepeatAPageRequestedOutsideAPagedRead() {
    List<String> staleCursors = new ArrayList<>();
    List<String> freshCursors = new ArrayList<>();
    InMemoryMcpTransport stale = catalogue(staleCursors, 1);
    InMemoryMcpTransport fresh = catalogue(freshCursors, Integer.MAX_VALUE);
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(stale, fresh);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());
    lifecycle.connect().join();
    OwnedMcpAsyncOperations operations = new OwnedMcpAsyncOperations(lifecycle);
    operations.listTools(McpSchema.FIRST_PAGE, null).toCompletableFuture().join();

    Throwable failure =
        catchThrowable(() -> operations.listTools("page-2", null).toCompletableFuture().join());

    // A page asked for outside a paged read has no reader to start over, so the replacement is
    // reported with what caused it rather than asked to continue a cursor it never issued.
    assertThat(failure).hasRootCauseInstanceOf(McpTransportSessionNotFoundException.class);
    assertThat(failure.getCause().getClass().getSimpleName())
        .isEqualTo("McpConnectionReplacedException");
    assertThat(factory.createdCount()).isEqualTo(2);
    assertThat(freshCursors).isEmpty();
  }

  @Test
  void leavesBorrowedPagingUntouched() {
    List<String> cursors = new ArrayList<>();
    InMemoryMcpTransport transport = catalogue(cursors, 1);
    McpAsyncClient client =
        McpClient.async(transport)
            .requestTimeout(BLOCK)
            .initializationTimeout(BLOCK)
            .jsonSchemaValidator(new PermissiveJsonSchemaValidator())
            .build();
    client.initialize().block(BLOCK);
    ConnectedMcpClientAdapter adapter = new ConnectedMcpClientAdapter(client);

    // A borrowed adapter owns no connection, so it cannot replace one. The failure is the caller's
    // to handle, and the client it was lent stays open and initialized.
    assertThatThrownBy(() -> adapter.discoverTools().toCompletableFuture().join())
        .rootCause()
        .isInstanceOf(McpTransportSessionNotFoundException.class);

    assertThat(cursors).containsExactly(null, "page-2");
    assertThat(client.isInitialized()).isTrue();
    assertThat(transport.closeCount()).isZero();
  }

  private static List<FunctionTool> discover(OwnedMcpClientLifecycle lifecycle) {
    return new McpToolDiscovery(
            new OwnedMcpAsyncOperations(lifecycle), McpToolAdapterOptions.defaults())
        .discover()
        .toCompletableFuture()
        .join();
  }

  private static List<String> names(List<FunctionTool> tools) {
    return tools.stream().map(tool -> tool.definition().name()).toList();
  }

  /**
   * A two page catalogue that records the cursor of every page it was asked for and drops the
   * connection once it has answered {@code answerablePages} of them.
   */
  private static InMemoryMcpTransport catalogue(List<String> cursors, int answerablePages) {
    return new InMemoryMcpTransport()
        .answeringPing()
        .answering(
            McpSchema.METHOD_TOOLS_CALL,
            params ->
                new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent(null, "found 2 issues", null)),
                    Boolean.FALSE,
                    null,
                    null))
        .answering(
            McpSchema.METHOD_TOOLS_LIST,
            params -> {
              String cursor = ((McpSchema.PaginatedRequest) params).cursor();
              cursors.add(cursor);
              if (cursors.size() > answerablePages) {
                throw new McpTransportSessionNotFoundException("session expired");
              }
              return cursor == null
                  ? new McpSchema.ListToolsResult(List.of(tool("search-issues")), "page-2", null)
                  : new McpSchema.ListToolsResult(List.of(tool("close-issues")), null, null);
            });
  }

  /**
   * A catalogue that answers its first page and then leaves every later page in flight.
   *
   * <p>Withholding starts from inside the first page's answer, so the request that hangs is the
   * second page — the one carrying a cursor the session issued. Its tool call always loses the
   * session, which is how a sibling operation replaces the generation while that page is pending.
   *
   * <p>The cursor it issues is named for the session it belongs to, so a restart that wrongly
   * carried it over to the replacement would be visible in the replacement's own recorded cursors
   * rather than hidden behind a token both catalogues happen to share.
   */
  private static InMemoryMcpTransport catalogueWithheldAfterTheFirstPage(List<String> cursors) {
    InMemoryMcpTransport transport = new InMemoryMcpTransport();
    return transport
        .answeringPing()
        .failingSend(
            McpSchema.METHOD_TOOLS_CALL,
            () -> new McpTransportSessionNotFoundException("session expired"))
        .answering(
            McpSchema.METHOD_TOOLS_LIST,
            params -> {
              cursors.add(((McpSchema.PaginatedRequest) params).cursor());
              transport.withholding(McpSchema.METHOD_TOOLS_LIST);
              return new McpSchema.ListToolsResult(
                  List.of(tool("search-issues")), "stale-page-2", null);
            });
  }

  private static McpSchema.Tool tool(String name) {
    return new McpSchema.Tool(name, null, name, SCHEMA, null, null, null, null);
  }

  private static McpOwnedClientSettings settings() {
    return new McpOwnedClientSettings(
        new PermissiveJsonSchemaValidator(), Duration.ofSeconds(5), Duration.ofSeconds(5));
  }
}
