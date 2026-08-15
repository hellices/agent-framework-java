package io.github.hellices.agentframework.mcp;

import static io.github.hellices.agentframework.mcp.FakeMcpAsyncOperations.callResult;
import static io.github.hellices.agentframework.mcp.FakeMcpAsyncOperations.objectSchema;
import static io.github.hellices.agentframework.mcp.FakeMcpAsyncOperations.page;
import static io.github.hellices.agentframework.mcp.FakeMcpAsyncOperations.tool;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hellices.agentframework.api.message.Content;
import io.github.hellices.agentframework.api.message.TextContent;
import io.github.hellices.agentframework.api.tool.FunctionTool;
import io.github.hellices.agentframework.api.tool.ToolArguments;
import io.github.hellices.agentframework.api.tool.ToolContext;
import io.github.hellices.agentframework.api.tool.ToolResult;
import io.github.hellices.agentframework.mcp.internal.McpAsyncOperations;
import io.modelcontextprotocol.spec.McpSchema;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

class ConnectedMcpClientAdapterTest {

  private static final Map<String, Object> SEARCH_SCHEMA = objectSchema("query", "limit");

  @Test
  void readsEveryPageAndPreservesDiscoveryOrder() {
    FakeMcpAsyncOperations operations =
        new FakeMcpAsyncOperations()
            .pages(
                page(
                    List.of(tool("alpha", SEARCH_SCHEMA), tool("beta", SEARCH_SCHEMA)), "cursor-1"),
                page(List.of(tool("gamma", SEARCH_SCHEMA)), "cursor-2"),
                page(List.of(tool("delta", SEARCH_SCHEMA)), null));

    List<FunctionTool> tools = discover(adapter(operations));

    assertThat(tools.stream().map(functionTool -> functionTool.definition().name()))
        .containsExactly("alpha", "beta", "gamma", "delta");
    assertThat(operations.requestedCursors()).containsExactly(null, "cursor-1", "cursor-2");
  }

  @Test
  void attachesNoRequestMetadataToDiscovery() {
    FakeMcpAsyncOperations operations =
        new FakeMcpAsyncOperations().pages(page(List.of(tool("alpha", SEARCH_SCHEMA)), null));

    discover(adapter(operations));

    assertThat(operations.requestedListMetadata()).hasSize(1).containsOnlyNulls();
  }

  @Test
  void treatsABlankNextCursorAsTheLastPage() {
    FakeMcpAsyncOperations operations =
        new FakeMcpAsyncOperations()
            .pages(page(List.of(tool("alpha", SEARCH_SCHEMA)), "cursor-1"), page(List.of(), "   "));

    List<FunctionTool> tools = discover(adapter(operations));

    assertThat(tools).hasSize(1);
    assertThat(operations.requestedCursors()).containsExactly(null, "cursor-1");
  }

  @Test
  void failsWhenTheServerRepeatsAPaginationCursor() {
    FakeMcpAsyncOperations operations =
        new FakeMcpAsyncOperations()
            .pages(
                page(List.of(tool("alpha", SEARCH_SCHEMA)), "cursor-1"),
                page(List.of(tool("beta", SEARCH_SCHEMA)), "cursor-1"));

    assertThatThrownBy(() -> discover(adapter(operations)))
        .hasRootCauseInstanceOf(IllegalStateException.class)
        .rootCause()
        .hasMessageContaining("repeated tool list cursor")
        .hasMessageContaining("cursor-1");
    assertThat(operations.requestedCursors()).containsExactly(null, "cursor-1");
  }

  @Test
  void prefixesLocalNamesWhileInvokingTheRawRemoteName() {
    FakeMcpAsyncOperations operations =
        new FakeMcpAsyncOperations().pages(page(List.of(tool("search", SEARCH_SCHEMA)), null));
    McpToolAdapterOptions options =
        McpToolAdapterOptions.builder().localNamePrefix("github_").build();

    List<FunctionTool> tools = discover(adapter(operations, options));

    assertThat(tools.get(0).definition().name()).isEqualTo("github_search");
    invoke(tools.get(0), new ToolArguments(Map.of("query", "issues")));
    assertThat(operations.lastCallRequest().name()).isEqualTo("search");
  }

  @Test
  void normalizesCharactersOutsideTheLocalNameAlphabet() {
    FakeMcpAsyncOperations operations =
        new FakeMcpAsyncOperations()
            .pages(page(List.of(tool("search issues!", SEARCH_SCHEMA)), null));

    List<FunctionTool> tools = discover(adapter(operations));

    assertThat(tools.get(0).definition().name()).isEqualTo("search_issues_");
  }

  @Test
  void failsWhenTwoRemoteNamesNormalizeToTheSameLocalName() {
    FakeMcpAsyncOperations operations =
        new FakeMcpAsyncOperations()
            .pages(
                page(
                    List.of(
                        tool("search issues", SEARCH_SCHEMA), tool("search.issues", SEARCH_SCHEMA)),
                    null));

    assertThatThrownBy(() -> discover(adapter(operations)))
        .rootCause()
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("search issues")
        .hasMessageContaining("search.issues")
        .hasMessageContaining("search_issues");
  }

  @Test
  void copiesTheRemoteDefinitionIntoTheLocalTool() {
    FakeMcpAsyncOperations operations =
        new FakeMcpAsyncOperations().pages(page(List.of(tool("search", SEARCH_SCHEMA)), null));

    List<FunctionTool> tools = discover(adapter(operations));

    assertThat(tools.get(0).definition().description()).isEqualTo("search description");
    assertThat(tools.get(0).definition().inputSchema()).isEqualTo(SEARCH_SCHEMA);
  }

  @Test
  void rejectsAPageThatCarriesNoResult() {
    FakeMcpAsyncOperations operations =
        new FakeMcpAsyncOperations().listing(cursor -> CompletableFuture.completedFuture(null));

    assertThatThrownBy(() -> discover(adapter(operations)))
        .rootCause()
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("tools/list");
  }

  @Test
  void rejectsANullToolEntry() {
    List<McpSchema.Tool> withNull = Arrays.asList(tool("alpha", SEARCH_SCHEMA), null);
    FakeMcpAsyncOperations operations = new FakeMcpAsyncOperations().pages(page(withNull, null));

    assertThatThrownBy(() -> discover(adapter(operations)))
        .rootCause()
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("null tool");
  }

  @Test
  void rejectsABlankToolName() {
    FakeMcpAsyncOperations operations =
        new FakeMcpAsyncOperations().pages(page(List.of(tool("", SEARCH_SCHEMA)), null));

    assertThatThrownBy(() -> discover(adapter(operations)))
        .rootCause()
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("name");
  }

  @Test
  void rejectsAMissingListStage() {
    FakeMcpAsyncOperations operations = new FakeMcpAsyncOperations().listing(cursor -> null);

    assertThatThrownBy(() -> discover(adapter(operations)))
        .rootCause()
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void turnsASynchronousListFailureIntoAFailedStage() {
    FakeMcpAsyncOperations operations =
        new FakeMcpAsyncOperations()
            .listing(
                cursor -> {
                  throw new IllegalStateException("Server does not provide tools capability");
                });

    CompletionStage<List<FunctionTool>> discovery = adapter(operations).discoverTools();

    assertThat(discovery.toCompletableFuture()).isCompletedExceptionally();
    assertThatThrownBy(() -> discovery.toCompletableFuture().join())
        .rootCause()
        .hasMessage("Server does not provide tools capability");
  }

  @Test
  void turnsAFailedListStageIntoAFailedStage() {
    FakeMcpAsyncOperations operations =
        new FakeMcpAsyncOperations()
            .listing(
                cursor ->
                    CompletableFuture.failedFuture(new IllegalStateException("transport is gone")));

    assertThatThrownBy(() -> discover(adapter(operations)))
        .rootCause()
        .hasMessage("transport is gone");
  }

  @Test
  void sendsOnlySchemaDeclaredArgumentsInCallerOrder() {
    FakeMcpAsyncOperations operations = new FakeMcpAsyncOperations();
    Map<String, Object> supplied = new LinkedHashMap<>();
    supplied.put("query", "issues");
    supplied.put("smuggled", "value");
    supplied.put("limit", 5);

    invoke(singleTool(operations), new ToolArguments(supplied));

    assertThat(operations.lastCallRequest().arguments())
        .containsExactly(Map.entry("query", "issues"), Map.entry("limit", 5));
  }

  @Test
  void sendsExplicitlyAllowedExtraArguments() {
    FakeMcpAsyncOperations operations = new FakeMcpAsyncOperations();
    McpToolAdapterOptions options =
        McpToolAdapterOptions.builder().additionalArgumentNames(List.of("tenant")).build();
    FunctionTool tool = singleTool(operations, options);

    Map<String, Object> supplied = new LinkedHashMap<>();
    supplied.put("query", "issues");
    supplied.put("tenant", "acme");
    supplied.put("smuggled", "value");
    invoke(tool, new ToolArguments(supplied));

    assertThat(operations.lastCallRequest().arguments())
        .containsExactly(Map.entry("query", "issues"), Map.entry("tenant", "acme"));
  }

  @Test
  void sendsNoRequestMetadataByDefault() {
    FakeMcpAsyncOperations operations = new FakeMcpAsyncOperations();

    invoke(singleTool(operations), new ToolArguments(Map.of("query", "issues")));

    assertThat(operations.lastCallRequest().meta()).isNull();
  }

  @Test
  void keepsRuntimeContextOutOfArgumentsAndMetadata() {
    FakeMcpAsyncOperations operations = new FakeMcpAsyncOperations();
    ToolContext context = new ToolContext(null, Map.of("runId", "run-1"));

    singleTool(operations)
        .execute(new ToolArguments(Map.of("query", "issues")), context)
        .toCompletableFuture()
        .join();

    assertThat(operations.lastCallRequest().arguments()).doesNotContainKey("runId");
    assertThat(operations.lastCallRequest().meta()).isNull();
  }

  @Test
  void sendsProviderMetadataOnlyThroughRequestMetadata() {
    FakeMcpAsyncOperations operations = new FakeMcpAsyncOperations();
    McpToolAdapterOptions options =
        McpToolAdapterOptions.builder()
            .callMetadataProvider(context -> Map.of("runId", context.attributes().get("runId")))
            .build();

    singleTool(operations, options)
        .execute(
            new ToolArguments(Map.of("query", "issues")),
            new ToolContext(null, Map.of("runId", "run-1")))
        .toCompletableFuture()
        .join();

    assertThat(operations.lastCallRequest().meta()).containsExactly(Map.entry("runId", "run-1"));
    assertThat(operations.lastCallRequest().arguments()).containsOnlyKeys("query");
  }

  @Test
  void copiesProviderMetadataSoLaterMutationCannotReachTheRequest() {
    FakeMcpAsyncOperations operations = new FakeMcpAsyncOperations();
    Map<String, Object> mutable = new LinkedHashMap<>();
    mutable.put("traceId", "trace-1");
    McpToolAdapterOptions options =
        McpToolAdapterOptions.builder().callMetadataProvider(context -> mutable).build();

    invoke(singleTool(operations, options), new ToolArguments(Map.of("query", "issues")));
    mutable.put("traceId", "mutated");
    mutable.put("added", "later");

    assertThat(operations.lastCallRequest().meta())
        .containsExactly(Map.entry("traceId", "trace-1"));
  }

  @Test
  void rejectsMetadataThatIsNullOrCarriesBlankKeys() {
    FakeMcpAsyncOperations operations = new FakeMcpAsyncOperations();
    FunctionTool nullMetadata =
        singleTool(
            operations,
            McpToolAdapterOptions.builder().callMetadataProvider(context -> null).build());
    Map<String, Object> blankKey = new LinkedHashMap<>();
    blankKey.put("  ", "value");
    FunctionTool blankKeyMetadata =
        singleTool(
            new FakeMcpAsyncOperations(),
            McpToolAdapterOptions.builder().callMetadataProvider(context -> blankKey).build());

    assertThatThrownBy(() -> invoke(nullMetadata, new ToolArguments(Map.of())))
        .rootCause()
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> invoke(blankKeyMetadata, new ToolArguments(Map.of())))
        .rootCause()
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void turnsAMetadataProviderFailureIntoAFailedStage() {
    FakeMcpAsyncOperations operations = new FakeMcpAsyncOperations();
    McpToolAdapterOptions options =
        McpToolAdapterOptions.builder()
            .callMetadataProvider(
                context -> {
                  throw new IllegalArgumentException("no tenant on this run");
                })
            .build();
    FunctionTool tool = singleTool(operations, options);

    CompletionStage<ToolResult> execution =
        tool.execute(new ToolArguments(Map.of()), new ToolContext(null, Map.of()));

    assertThat(execution.toCompletableFuture()).isCompletedExceptionally();
    assertThatThrownBy(() -> execution.toCompletableFuture().join())
        .rootCause()
        .hasMessage("no tenant on this run");
  }

  @Test
  void mapsTextContentToCoreTextContent() {
    FakeMcpAsyncOperations operations =
        new FakeMcpAsyncOperations()
            .answering(
                callResult(List.of(new McpSchema.TextContent(null, "done", null)), Boolean.FALSE));

    ToolResult result = invoke(singleTool(operations), new ToolArguments(Map.of()));

    assertThat(result.error()).isFalse();
    assertThat(result.content()).singleElement().isInstanceOf(TextContent.class);
    assertThat(result.content().get(0).text()).isEqualTo("done");
  }

  @Test
  void mapsOtherContentVariantsToPayloadContentInResultOrder() {
    List<McpSchema.Content> content =
        List.of(
            new McpSchema.TextContent(null, "first", null),
            new McpSchema.ImageContent(null, "aGk=", "image/png", null),
            new McpSchema.AudioContent(null, "aGk=", "audio/wav", null),
            new McpSchema.EmbeddedResource(
                null,
                new McpSchema.TextResourceContents("file:///a.txt", "text/plain", "body", null),
                null),
            McpSchema.ResourceLink.builder().name("doc").uri("file:///b.txt").build());
    FakeMcpAsyncOperations operations =
        new FakeMcpAsyncOperations().answering(callResult(content, null));

    ToolResult result = invoke(singleTool(operations), new ToolArguments(Map.of()));

    assertThat(result.content().stream().map(Content::type))
        .containsExactly("text", "mcp_image", "mcp_audio", "mcp_resource", "mcp_resource_link");
    McpPayloadContent image = (McpPayloadContent) result.content().get(1);
    assertThat(image.payloadType()).isEqualTo("image");
    assertThat(image.additionalProperties()).containsEntry("mimeType", "image/png");
    assertThat(image.rawRepresentation()).isSameAs(content.get(1));
  }

  @Test
  void keepsAResourceLinkWithMissingFieldsFromFailingTheWholeResult() {
    // ResourceLink is the one content variant the SDK binds straight through its canonical
    // constructor, so a server that omits `uri` or `name` yields nulls that must not take the
    // sibling content down with them.
    List<McpSchema.Content> content =
        List.of(
            new McpSchema.TextContent(null, "first", null),
            new McpSchema.ResourceLink(null, null, null, null, null, null, null, null));
    FakeMcpAsyncOperations operations =
        new FakeMcpAsyncOperations().answering(callResult(content, null));

    ToolResult result = invoke(singleTool(operations), new ToolArguments(Map.of()));

    assertThat(result.content().stream().map(Content::type))
        .containsExactly("text", "mcp_resource_link");
    assertThat(result.content().get(1).additionalProperties()).isEmpty();
    assertThat(result.content().get(1).rawRepresentation()).isSameAs(content.get(1));
  }

  @Test
  void mapsIsErrorToTheToolResultErrorFlag() {
    FakeMcpAsyncOperations failing =
        new FakeMcpAsyncOperations()
            .answering(
                callResult(List.of(new McpSchema.TextContent(null, "boom", null)), Boolean.TRUE));
    FakeMcpAsyncOperations absent =
        new FakeMcpAsyncOperations()
            .answering(callResult(List.of(new McpSchema.TextContent(null, "ok", null)), null));

    assertThat(invoke(singleTool(failing), new ToolArguments(Map.of())).error()).isTrue();
    assertThat(invoke(singleTool(absent), new ToolArguments(Map.of())).error()).isFalse();
  }

  @Test
  void preservesStructuredContentAndResultMetadataAsPayload() {
    McpSchema.CallToolResult result =
        new McpSchema.CallToolResult(
            List.of(new McpSchema.TextContent(null, "done", null)),
            Boolean.FALSE,
            Map.of("count", 2),
            Map.of("elapsedMs", 12));
    FakeMcpAsyncOperations operations = new FakeMcpAsyncOperations().answering(result);

    ToolResult mapped = invoke(singleTool(operations), new ToolArguments(Map.of()));

    assertThat(mapped.content()).hasSize(2);
    McpPayloadContent payload = (McpPayloadContent) mapped.content().get(1);
    assertThat(payload.payloadType()).isEqualTo("result");
    assertThat(payload.additionalProperties())
        .containsEntry("structuredContent", Map.of("count", 2))
        .containsEntry("_meta", Map.of("elapsedMs", 12));
  }

  @Test
  void rejectsAMissingCallResultAndNullContentEntries() {
    FakeMcpAsyncOperations missing =
        new FakeMcpAsyncOperations().calling(request -> CompletableFuture.completedFuture(null));
    List<McpSchema.Content> withNull =
        Arrays.asList(new McpSchema.TextContent(null, "ok", null), null);
    FakeMcpAsyncOperations nullEntry =
        new FakeMcpAsyncOperations().answering(callResult(withNull, null));

    assertThatThrownBy(() -> invoke(singleTool(missing), new ToolArguments(Map.of())))
        .rootCause()
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> invoke(singleTool(nullEntry), new ToolArguments(Map.of())))
        .rootCause()
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("null content");
  }

  @Test
  void turnsASynchronousCallFailureIntoAFailedStage() {
    FakeMcpAsyncOperations operations =
        new FakeMcpAsyncOperations()
            .calling(
                request -> {
                  throw new IllegalStateException("session terminated");
                });
    FunctionTool tool = singleTool(operations);

    CompletionStage<ToolResult> execution =
        tool.execute(new ToolArguments(Map.of()), new ToolContext(null, Map.of()));

    assertThat(execution.toCompletableFuture()).isCompletedExceptionally();
    assertThatThrownBy(() -> execution.toCompletableFuture().join())
        .rootCause()
        .hasMessage("session terminated");
  }

  @Test
  void cancellingTheInvocationCancelsThePendingCall() {
    CompletableFuture<McpSchema.CallToolResult> pending = new CompletableFuture<>();
    FakeMcpAsyncOperations operations = new FakeMcpAsyncOperations().calling(request -> pending);
    FunctionTool tool = singleTool(operations);

    CompletionStage<ToolResult> execution =
        tool.execute(new ToolArguments(Map.of()), new ToolContext(null, Map.of()));
    assertThat(execution.toCompletableFuture().cancel(true)).isTrue();

    assertThat(pending).isCancelled();
  }

  @Test
  void cancellingDiscoveryCancelsThePendingPageRequest() {
    CompletableFuture<McpSchema.ListToolsResult> pending = new CompletableFuture<>();
    FakeMcpAsyncOperations operations = new FakeMcpAsyncOperations().listing(cursor -> pending);

    CompletionStage<List<FunctionTool>> discovery = adapter(operations).discoverTools();
    assertThat(discovery.toCompletableFuture().cancel(true)).isTrue();

    assertThat(pending).isCancelled();
  }

  @Test
  void exposesNoLifecycleOverTheBorrowedClient() {
    List<String> lifecycleNames = List.of("close", "closeGracefully", "shutdown", "finalize");

    assertThat(AutoCloseable.class.isAssignableFrom(ConnectedMcpClientAdapter.class)).isFalse();
    assertThat(declaredNames(ConnectedMcpClientAdapter.class))
        .doesNotContainAnyElementsOf(lifecycleNames);
    assertThat(declaredNames(McpAsyncOperations.class)).doesNotContainAnyElementsOf(lifecycleNames);
  }

  private static List<String> declaredNames(Class<?> type) {
    List<String> names = new ArrayList<>();
    for (Method method : type.getDeclaredMethods()) {
      names.add(method.getName());
    }
    return names;
  }

  private static ConnectedMcpClientAdapter adapter(McpAsyncOperations operations) {
    return adapter(operations, McpToolAdapterOptions.defaults());
  }

  private static ConnectedMcpClientAdapter adapter(
      McpAsyncOperations operations, McpToolAdapterOptions options) {
    return new ConnectedMcpClientAdapter(operations, options);
  }

  private static List<FunctionTool> discover(ConnectedMcpClientAdapter adapter) {
    return adapter.discoverTools().toCompletableFuture().join();
  }

  private static FunctionTool singleTool(FakeMcpAsyncOperations operations) {
    return singleTool(operations, McpToolAdapterOptions.defaults());
  }

  private static FunctionTool singleTool(
      FakeMcpAsyncOperations operations, McpToolAdapterOptions options) {
    operations.pages(page(List.of(tool("search", SEARCH_SCHEMA)), null));
    return discover(adapter(operations, options)).get(0);
  }

  private static ToolResult invoke(FunctionTool tool, ToolArguments arguments) {
    return tool.execute(arguments, new ToolContext(null, Map.of())).toCompletableFuture().join();
  }
}
