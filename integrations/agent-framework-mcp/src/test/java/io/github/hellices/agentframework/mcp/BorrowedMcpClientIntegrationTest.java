package io.github.hellices.agentframework.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hellices.agentframework.api.tool.FunctionTool;
import io.github.hellices.agentframework.api.tool.ToolArguments;
import io.github.hellices.agentframework.api.tool.ToolContext;
import io.github.hellices.agentframework.api.tool.ToolResult;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Proves the adapter drives a real {@link McpAsyncClient} and leaves it open.
 *
 * <p>The sibling tests drive the internal operations port, which cannot show that the SDK client is
 * called correctly or that its lifecycle is untouched, because {@code McpAsyncClient} cannot be
 * faked: its constructor is not public and the class is not extensible. These tests run the genuine
 * client over an in-memory transport, so the initialize handshake, the {@code tools/list} request,
 * and the {@code tools/call} request are the ones the SDK really produces, and the transport can
 * count the closes that a borrowed client must never receive.
 */
class BorrowedMcpClientIntegrationTest {

  private static final Map<String, Object> SCHEMA =
      Map.of("type", "object", "properties", Map.of("query", Map.of("type", "string")));

  @Test
  void discoversAndInvokesToolsOverARealClient() {
    InMemoryMcpTransport transport =
        new InMemoryMcpTransport()
            .answering(
                McpSchema.METHOD_TOOLS_LIST,
                params ->
                    new McpSchema.ListToolsResult(
                        List.of(
                            new McpSchema.Tool(
                                "search-issues", null, "search", SCHEMA, null, null, null, null)),
                        null,
                        null))
            .answering(
                McpSchema.METHOD_TOOLS_CALL,
                params ->
                    new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent(null, "found 2 issues", null)),
                        Boolean.FALSE,
                        null,
                        null));
    McpAsyncClient client = initializedClient(transport);

    ConnectedMcpClientAdapter adapter =
        new ConnectedMcpClientAdapter(
            client, McpToolAdapterOptions.builder().localNamePrefix("github_").build());
    List<FunctionTool> tools = adapter.discoverTools().toCompletableFuture().join();
    ToolResult result =
        tools
            .get(0)
            .execute(
                new ToolArguments(Map.of("query", "open", "smuggled", "value")),
                new ToolContext(null, Map.of("conversationId", "c-1")))
            .toCompletableFuture()
            .join();

    assertThat(tools).hasSize(1);
    assertThat(tools.get(0).definition().name()).isEqualTo("github_search-issues");
    assertThat(result.error()).isFalse();
    assertThat(result.content().get(0).text()).isEqualTo("found 2 issues");
    assertThat(transport.methodsSent())
        .containsExactly(
            McpSchema.METHOD_INITIALIZE,
            McpSchema.METHOD_NOTIFICATION_INITIALIZED,
            McpSchema.METHOD_TOOLS_LIST,
            McpSchema.METHOD_TOOLS_CALL);
    assertThat(transport.lastRequestFor(McpSchema.METHOD_TOOLS_CALL))
        .isInstanceOfSatisfying(
            McpSchema.CallToolRequest.class,
            request -> {
              assertThat(request.name()).isEqualTo("search-issues");
              assertThat(request.arguments()).containsOnlyKeys("query");
              assertThat(request.meta()).isNull();
            });
  }

  @Test
  void neverClosesTheBorrowedClientOnFailureOrCancellation() {
    InMemoryMcpTransport transport =
        new InMemoryMcpTransport()
            .answering(
                McpSchema.METHOD_TOOLS_LIST,
                params ->
                    new McpSchema.ListToolsResult(
                        List.of(
                            new McpSchema.Tool(
                                "search-issues", null, "search", SCHEMA, null, null, null, null)),
                        null,
                        null))
            .answering(
                McpSchema.METHOD_TOOLS_CALL,
                params -> {
                  throw new IllegalStateException("tool exploded");
                });
    McpAsyncClient client = initializedClient(transport);
    ConnectedMcpClientAdapter adapter = new ConnectedMcpClientAdapter(client);

    FunctionTool tool = adapter.discoverTools().toCompletableFuture().join().get(0);
    assertThatThrownBy(
            () ->
                tool.execute(new ToolArguments(Map.of()), new ToolContext(null, Map.of()))
                    .toCompletableFuture()
                    .join())
        .isNotNull();
    transport.withholding(McpSchema.METHOD_TOOLS_LIST);
    adapter.discoverTools().toCompletableFuture().cancel(true);
    transport.releaseWithheld();

    assertThat(transport.closeCount()).isZero();
    assertThat(client.isInitialized()).isTrue();
    assertThat(adapter.discoverTools().toCompletableFuture().join()).hasSize(1);
  }

  @Test
  void refusesAClientThatHasNotCompletedItsHandshake() {
    McpAsyncClient client = client(new InMemoryMcpTransport());

    assertThatThrownBy(() -> new ConnectedMcpClientAdapter(client))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("initialize");
  }

  private static McpAsyncClient initializedClient(InMemoryMcpTransport transport) {
    McpAsyncClient client = client(transport);
    client.initialize().block(Duration.ofSeconds(5));
    return client;
  }

  private static McpAsyncClient client(InMemoryMcpTransport transport) {
    return McpClient.async(transport)
        .requestTimeout(Duration.ofSeconds(5))
        .initializationTimeout(Duration.ofSeconds(5))
        .jsonSchemaValidator(new PermissiveJsonSchemaValidator())
        .build();
  }
}
