package io.github.hellices.agentframework.mcp;

import static io.github.hellices.agentframework.mcp.FakeMcpAsyncOperations.callResult;
import static io.github.hellices.agentframework.mcp.FakeMcpAsyncOperations.objectSchema;
import static io.github.hellices.agentframework.mcp.FakeMcpAsyncOperations.page;
import static io.github.hellices.agentframework.mcp.FakeMcpAsyncOperations.tool;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hellices.agentframework.api.agent.AgentSession;
import io.github.hellices.agentframework.api.agent.CancellationSignal;
import io.github.hellices.agentframework.api.context.ContextAttributes;
import io.github.hellices.agentframework.api.context.ContextKey;
import io.github.hellices.agentframework.api.message.Content;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.Role;
import io.github.hellices.agentframework.api.message.TextContent;
import io.github.hellices.agentframework.api.session.SessionSnapshot;
import io.github.hellices.agentframework.api.session.SessionState;
import io.github.hellices.agentframework.api.session.SessionStateKey;
import io.github.hellices.agentframework.api.session.SessionStateValues;
import io.github.hellices.agentframework.api.tool.FunctionTool;
import io.github.hellices.agentframework.api.tool.ToolArguments;
import io.github.hellices.agentframework.api.tool.ToolContext;
import io.github.hellices.agentframework.api.tool.ToolResult;
import io.github.hellices.agentframework.api.value.JsonNull;
import io.github.hellices.agentframework.api.value.JsonObject;
import io.github.hellices.agentframework.api.value.JsonString;
import io.github.hellices.agentframework.api.value.JsonValue;
import io.github.hellices.agentframework.api.value.JsonValues;
import io.github.hellices.agentframework.mcp.internal.McpAsyncOperations;
import io.github.hellices.agentframework.spi.session.StateCodecRegistry;
import io.modelcontextprotocol.spec.McpSchema;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ConnectedMcpClientAdapterTest {

  private static final ContextKey<String> RUN_ID = ContextKey.of("mcp", "runId", String.class);

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
  void failsWhenTheServerNeverStopsPublishingNewPages() {
    // A server that keeps handing out fresh cursors is a protocol fault, not a large catalogue:
    // followed to the end it exhausts the stack rather than the patience of the caller.
    FakeMcpAsyncOperations operations = endlesslyPagingServer();

    assertThatThrownBy(() -> discover(adapter(operations)))
        .rootCause()
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("256");
    assertThat(operations.requestedCursors()).hasSize(256);
  }

  @Test
  void boundsPaginationByTheConfiguredPageLimit() {
    FakeMcpAsyncOperations operations = endlesslyPagingServer();

    assertThatThrownBy(
            () ->
                discover(
                    adapter(
                        operations, McpToolAdapterOptions.builder().maxDiscoveryPages(3).build())))
        .rootCause()
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("3");
    assertThat(operations.requestedCursors()).containsExactly(null, "cursor-1", "cursor-2");
  }

  @Test
  void reportsThePageBoundEvenWhenItIsRaisedFarBeyondTheStackDepthOfAPageChain() {
    // Chaining each page onto the stage of the page before it exhausted the stack after roughly
    // 1500 pages that a server answered immediately, so a raised bound turned the protocol fault
    // into a stack overflow instead of the failure the bound exists to report.
    FakeMcpAsyncOperations operations = endlesslyPagingServer();

    assertThatThrownBy(
            () ->
                discover(
                    adapter(
                        operations,
                        McpToolAdapterOptions.builder().maxDiscoveryPages(4096).build())))
        .rootCause()
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("4096");
    assertThat(operations.requestedCursors()).hasSize(4096);
  }

  @Test
  void stopsPagingWhenACancelLandsBetweenTwoPages() {
    // The in-flight page cancel is a no-op once that page has completed, which is the window in
    // which the old recursion kept paging a borrowed client for a caller that had already left.
    UncancellableFuture<McpSchema.ListToolsResult> firstPage = new UncancellableFuture<>();
    FakeMcpAsyncOperations operations =
        new FakeMcpAsyncOperations()
            .listing(
                cursor ->
                    cursor == null
                        ? firstPage
                        : CompletableFuture.completedFuture(
                            page(List.of(tool("beta", SEARCH_SCHEMA)), null)));

    CompletableFuture<List<FunctionTool>> discovery =
        adapter(operations).discoverTools().toCompletableFuture();
    discovery.cancel(true);
    firstPage.complete(page(List.of(tool("alpha", SEARCH_SCHEMA)), "cursor-1"));

    assertThat(discovery).isCancelled();
    assertThat(operations.requestedCursors()).containsExactly((String) null);
  }

  @Test
  void prefixesLocalNamesWhileInvokingTheRawRemoteName() {
    FakeMcpAsyncOperations operations =
        new FakeMcpAsyncOperations().pages(page(List.of(tool("search", SEARCH_SCHEMA)), null));
    McpToolAdapterOptions options =
        McpToolAdapterOptions.builder().localNamePrefix("github_").build();

    List<FunctionTool> tools = discover(adapter(operations, options));

    assertThat(tools.get(0).definition().name()).isEqualTo("github_search");
    invoke(tools.get(0), toolArguments(Map.of("query", "issues")));
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
    assertThat(JsonValues.toJava(tools.get(0).definition().inputSchema())).isEqualTo(SEARCH_SCHEMA);
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
  void rejectsAPortThatHandsOutNoScopeForAPagedRead() {
    // The scope of a paged read is taken once, before the first page is requested, so a port that
    // answers with nothing is reported as the broken port it is rather than as a null pointer
    // failure from inside the reader.
    FakeMcpAsyncOperations operations =
        new FakeMcpAsyncOperations().pages(page(List.of(tool("alpha", SEARCH_SCHEMA)), null));

    assertThatThrownBy(() -> discover(adapter(operations.withoutPagedScope())))
        .rootCause()
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("scope");
    assertThat(operations.requestedCursors()).isEmpty();
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

    invoke(singleTool(operations), toolArguments(supplied));

    assertThat(operations.lastCallRequest().arguments())
        .containsExactly(Map.entry("query", "issues"), Map.entry("limit", new BigDecimal("5")));
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
    invoke(tool, toolArguments(supplied));

    assertThat(operations.lastCallRequest().arguments())
        .containsExactly(Map.entry("query", "issues"), Map.entry("tenant", "acme"));
  }

  @Test
  void sendsNoRequestMetadataByDefault() {
    FakeMcpAsyncOperations operations = new FakeMcpAsyncOperations();

    invoke(singleTool(operations), toolArguments(Map.of("query", "issues")));

    assertThat(operations.lastCallRequest().meta()).isNull();
  }

  @Test
  void keepsRuntimeContextOutOfArgumentsAndMetadata() {
    FakeMcpAsyncOperations operations = new FakeMcpAsyncOperations();
    ToolContext context =
        new ToolContext(null, ContextAttributes.builder().put(RUN_ID, "run-1").build());

    singleTool(operations)
        .execute(toolArguments(Map.of("query", "issues")), context)
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
            .callMetadataProvider(
                context ->
                    JsonObject.builder()
                        .put("runId", JsonString.of(context.attributes().get(RUN_ID).orElseThrow()))
                        .build())
            .build();

    singleTool(operations, options)
        .execute(
            toolArguments(Map.of("query", "issues")),
            new ToolContext(null, ContextAttributes.builder().put(RUN_ID, "run-1").build()))
        .toCompletableFuture()
        .join();

    assertThat(operations.lastCallRequest().meta()).containsExactly(Map.entry("runId", "run-1"));
    assertThat(operations.lastCallRequest().arguments()).containsOnlyKeys("query");
  }

  @Test
  void providerMetadataIsAlreadyImmutableAtThePublicBoundary() {
    FakeMcpAsyncOperations operations = new FakeMcpAsyncOperations();
    JsonObject metadata = JsonObject.builder().put("traceId", JsonString.of("trace-1")).build();
    McpToolAdapterOptions options =
        McpToolAdapterOptions.builder().callMetadataProvider(context -> metadata).build();

    invoke(singleTool(operations, options), toolArguments(Map.of("query", "issues")));

    assertThat(operations.lastCallRequest().meta())
        .containsExactly(Map.entry("traceId", "trace-1"));
  }

  @Test
  void rejectsMetadataThatIsNullOrCarriesBlankKeysOrNullValues() {
    FakeMcpAsyncOperations operations = new FakeMcpAsyncOperations();
    FunctionTool nullMetadata =
        singleTool(
            operations,
            McpToolAdapterOptions.builder().callMetadataProvider(context -> null).build());
    FunctionTool blankKeyMetadata =
        singleTool(
            new FakeMcpAsyncOperations(),
            McpToolAdapterOptions.builder()
                .callMetadataProvider(
                    context -> JsonObject.builder().put("  ", JsonString.of("value")).build())
                .build());
    FunctionTool nullValueMetadata =
        singleTool(
            new FakeMcpAsyncOperations(),
            McpToolAdapterOptions.builder()
                .callMetadataProvider(
                    context -> JsonObject.builder().put("traceId", JsonNull.instance()).build())
                .build());

    assertThatThrownBy(() -> invoke(nullMetadata, toolArguments(Map.of())))
        .rootCause()
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> invoke(blankKeyMetadata, toolArguments(Map.of())))
        .rootCause()
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("blank key");
    assertThatThrownBy(() -> invoke(nullValueMetadata, toolArguments(Map.of())))
        .rootCause()
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("traceId");
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
        tool.execute(toolArguments(Map.of()), new ToolContext(null, ContextAttributes.empty()));

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

    ToolResult result = invoke(singleTool(operations), toolArguments(Map.of()));

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

    ToolResult result = invoke(singleTool(operations), toolArguments(Map.of()));

    assertThat(result.content().stream().map(Content::type))
        .containsExactly("text", "mcp_image", "mcp_audio", "mcp_resource", "mcp_resource_link");
    McpPayloadContent image = (McpPayloadContent) result.content().get(1);
    assertThat(image.payloadType()).isEqualTo("image");
    assertThat(image.additionalProperties())
        .isEqualTo(jsonObject(Map.of("mimeType", "image/png", "data", "aGk=")));
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

    ToolResult result = invoke(singleTool(operations), toolArguments(Map.of()));

    assertThat(result.content().stream().map(Content::type))
        .containsExactly("text", "mcp_resource_link");
    assertThat(result.content().get(1).additionalProperties()).isEqualTo(JsonObject.empty());
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

    assertThat(invoke(singleTool(failing), toolArguments(Map.of())).error()).isTrue();
    assertThat(invoke(singleTool(absent), toolArguments(Map.of())).error()).isFalse();
  }

  @Test
  void keepsATextResultPersistableWhenTheServerAlsoReturnsStructuredContent() {
    // Structured output and result `_meta` are ordinary features of a text answering server, so
    // carrying them by default would make a plain text result unpersistable long after the tool
    // call itself succeeded.
    McpSchema.CallToolResult result =
        new McpSchema.CallToolResult(
            List.of(new McpSchema.TextContent(null, "done", null)),
            Boolean.FALSE,
            Map.of("count", 2),
            Map.of("elapsedMs", 12));
    FakeMcpAsyncOperations operations = new FakeMcpAsyncOperations().answering(result);

    ToolResult mapped = invoke(singleTool(operations), toolArguments(Map.of()));

    assertThat(mapped.content()).singleElement().isInstanceOf(TextContent.class);
    assertThat(mapped.content().get(0).text()).isEqualTo("done");
    assertThatCode(() -> snapshotOf(mapped)).doesNotThrowAnyException();
  }

  @Test
  void preservesStructuredContentAndResultMetadataAsPayloadWhenAsked() {
    McpSchema.CallToolResult result =
        new McpSchema.CallToolResult(
            List.of(new McpSchema.TextContent(null, "done", null)),
            Boolean.FALSE,
            Map.of("count", 2),
            Map.of("elapsedMs", 12));
    FakeMcpAsyncOperations operations = new FakeMcpAsyncOperations().answering(result);
    McpToolAdapterOptions options =
        McpToolAdapterOptions.builder().includeResultPayload(true).build();

    ToolResult mapped = invoke(singleTool(operations, options), toolArguments(Map.of()));

    assertThat(mapped.content()).hasSize(2);
    McpPayloadContent payload = (McpPayloadContent) mapped.content().get(1);
    assertThat(payload.payloadType()).isEqualTo("result");
    assertThat(payload.additionalProperties())
        .isEqualTo(
            jsonObject(
                Map.of("structuredContent", Map.of("count", 2), "_meta", Map.of("elapsedMs", 12))));
    assertThatThrownBy(() -> snapshotOf(mapped))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("registered content codec");
  }

  @Test
  void keepsAStructuredOnlyAnswerRatherThanReportingAnEmptySuccess() {
    // The serialized text mirror of structured content is a recommendation, not a rule, so a
    // server may answer with structured content alone. Dropping it with the payload option off
    // would report a successful call that carries no answer at all.
    McpSchema.CallToolResult result =
        new McpSchema.CallToolResult(List.of(), Boolean.FALSE, Map.of("count", 2), null);
    FakeMcpAsyncOperations operations = new FakeMcpAsyncOperations().answering(result);

    ToolResult mapped = invoke(singleTool(operations), toolArguments(Map.of()));

    assertThat(mapped.content()).hasSize(1);
    McpPayloadContent payload = (McpPayloadContent) mapped.content().get(0);
    assertThat(payload.payloadType()).isEqualTo("result");
    assertThat(payload.additionalProperties())
        .isEqualTo(jsonObject(Map.of("structuredContent", Map.of("count", 2))));
  }

  @Test
  void leavesAnAnswerlessResultEmptyRatherThanCarryingItsMetadataAlone() {
    // Result metadata annotates an answer instead of being one, so a server that returns neither
    // content nor structured content said nothing, and saying nothing stays persistable.
    McpSchema.CallToolResult result =
        new McpSchema.CallToolResult(List.of(), Boolean.FALSE, null, Map.of("elapsedMs", 12));
    FakeMcpAsyncOperations operations = new FakeMcpAsyncOperations().answering(result);

    ToolResult mapped = invoke(singleTool(operations), toolArguments(Map.of()));

    assertThat(mapped.content()).isEmpty();
    assertThatCode(() -> snapshotOf(mapped)).doesNotThrowAnyException();
  }

  @Test
  void keepsNonTextContentAsPayloadWhateverTheResultPayloadOptionSays() {
    List<McpSchema.Content> content =
        List.of(new McpSchema.ImageContent(null, "aGk=", "image/png", null));
    FakeMcpAsyncOperations operations =
        new FakeMcpAsyncOperations().answering(callResult(content, null));

    ToolResult mapped = invoke(singleTool(operations), toolArguments(Map.of()));

    assertThat(mapped.content()).singleElement().isInstanceOf(McpPayloadContent.class);
    assertThatThrownBy(() -> snapshotOf(mapped))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("registered content codec");
  }

  @Test
  void rejectsAMissingCallResultAndNullContentEntries() {
    FakeMcpAsyncOperations missing =
        new FakeMcpAsyncOperations().calling(request -> CompletableFuture.completedFuture(null));
    List<McpSchema.Content> withNull =
        Arrays.asList(new McpSchema.TextContent(null, "ok", null), null);
    FakeMcpAsyncOperations nullEntry =
        new FakeMcpAsyncOperations().answering(callResult(withNull, null));

    assertThatThrownBy(() -> invoke(singleTool(missing), toolArguments(Map.of())))
        .rootCause()
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> invoke(singleTool(nullEntry), toolArguments(Map.of())))
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
        tool.execute(toolArguments(Map.of()), new ToolContext(null, ContextAttributes.empty()));

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
        tool.execute(toolArguments(Map.of()), new ToolContext(null, ContextAttributes.empty()));
    assertThat(execution.toCompletableFuture().cancel(true)).isTrue();

    assertThat(pending).isCancelled();
  }

  @Test
  void cancellingTheRunCancelsTheCallItStarted() {
    // The tool loop never cancels the stage a tool returned; it cancels the run. A borrowed client
    // that keeps working for an abandoned run is exactly what this signal exists to prevent.
    RecordingFuture<McpSchema.CallToolResult> pending = new RecordingFuture<>();
    FakeMcpAsyncOperations operations = new FakeMcpAsyncOperations().calling(request -> pending);
    FunctionTool tool = singleTool(operations);
    CancellationSignal signal = new CancellationSignal();

    CompletionStage<ToolResult> execution =
        tool.execute(toolArguments(Map.of()), new ToolContext(signal, ContextAttributes.empty()));
    signal.cancel();

    assertThat(execution.toCompletableFuture()).isCompletedExceptionally();
    assertThat(pending).isCancelled();
  }

  @Test
  void cancelsACallStartedByAnAlreadyCancelledRun() {
    RecordingFuture<McpSchema.CallToolResult> pending = new RecordingFuture<>();
    FakeMcpAsyncOperations operations = new FakeMcpAsyncOperations().calling(request -> pending);
    FunctionTool tool = singleTool(operations);
    CancellationSignal signal = new CancellationSignal();
    signal.cancel();

    CompletionStage<ToolResult> execution =
        tool.execute(toolArguments(Map.of()), new ToolContext(signal, ContextAttributes.empty()));

    assertThat(execution.toCompletableFuture()).isCompletedExceptionally();
    assertThat(pending).isCancelled();
  }

  @Test
  void detachesTheRunListenerWhenTheCallCompletes() {
    // A run signal outlives a single tool call, so a listener left behind by every completed call
    // would accumulate for the whole run.
    RecordingFuture<McpSchema.CallToolResult> pending = new RecordingFuture<>();
    FakeMcpAsyncOperations operations = new FakeMcpAsyncOperations().calling(request -> pending);
    FunctionTool tool = singleTool(operations);
    CancellationSignal signal = new CancellationSignal();

    CompletionStage<ToolResult> execution =
        tool.execute(toolArguments(Map.of()), new ToolContext(signal, ContextAttributes.empty()));
    pending.complete(callResult(List.of(new McpSchema.TextContent(null, "ok", null)), null));
    signal.cancel();

    assertThat(execution.toCompletableFuture().join().content().get(0).text()).isEqualTo("ok");
    assertThat(pending.cancelAttempts()).isZero();
  }

  @Test
  void leavesACompletedCallAloneWhenItsStageIsCancelledAfterwards() {
    RecordingFuture<McpSchema.CallToolResult> pending = new RecordingFuture<>();
    FakeMcpAsyncOperations operations = new FakeMcpAsyncOperations().calling(request -> pending);
    FunctionTool tool = singleTool(operations);

    CompletionStage<ToolResult> execution =
        tool.execute(toolArguments(Map.of()), new ToolContext(null, ContextAttributes.empty()));
    pending.complete(callResult(List.of(new McpSchema.TextContent(null, "ok", null)), null));

    assertThat(execution.toCompletableFuture().cancel(true)).isFalse();
    assertThat(pending.cancelAttempts()).isZero();
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

  private static FakeMcpAsyncOperations endlesslyPagingServer() {
    AtomicInteger pageNumber = new AtomicInteger();
    return new FakeMcpAsyncOperations()
        .listing(
            cursor -> {
              int number = pageNumber.incrementAndGet();
              return CompletableFuture.completedFuture(
                  page(List.of(tool("search-" + number, SEARCH_SCHEMA)), "cursor-" + number));
            });
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
    return tool.execute(arguments, new ToolContext(null, ContextAttributes.empty()))
        .toCompletableFuture()
        .join();
  }

  private static SessionSnapshot snapshotOf(ToolResult result) {
    AgentSession session =
        session("session-1", null, Map.of("message", new Message(Role.TOOL, result.content())));
    return StateCodecRegistry.builder().build().snapshot(session, 0, Instant.EPOCH);
  }

  private static ToolArguments toolArguments(Map<String, ?> values) {
    JsonObject.Builder builder = JsonObject.builder();
    values.forEach((key, value) -> builder.put(key, JsonValues.fromJava(value)));
    return ToolArguments.of(builder.build());
  }

  private static JsonObject jsonObject(Map<String, ?> values) {
    JsonObject.Builder builder = JsonObject.builder();
    values.forEach((key, value) -> builder.put(key, JsonValues.fromJava(value)));
    return builder.build();
  }

  private static AgentSession session(
      String sessionId, String serviceSessionId, Map<String, ?> state) {
    AgentSession.Builder builder =
        AgentSession.builder().sessionId(sessionId).state(sessionState(state));
    if (serviceSessionId != null) {
      builder.serviceSessionId(serviceSessionId);
    }
    return builder.build();
  }

  private static SessionState sessionState(Map<String, ?> state) {
    SessionState sessionState = SessionState.empty();
    for (Map.Entry<String, ?> entry : state.entrySet()) {
      sessionState = put(sessionState, entry.getKey(), entry.getValue());
    }
    return sessionState;
  }

  @SuppressWarnings("unchecked")
  private static SessionState put(SessionState state, String key, Object value) {
    if (SessionStateValues.isJsonValueShape(value)) {
      return state.with(SessionStateKey.of(key, JsonValue.class), JsonValues.fromJava(value));
    }
    return state.with((SessionStateKey<Object>) SessionStateKey.of(key, value.getClass()), value);
  }

  /** Records every cancellation attempt, including the ones a completed future would ignore. */
  private static final class RecordingFuture<T> extends CompletableFuture<T> {

    private final AtomicInteger cancelAttempts = new AtomicInteger();

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
      cancelAttempts.incrementAndGet();
      return super.cancel(mayInterruptIfRunning);
    }

    int cancelAttempts() {
      return cancelAttempts.get();
    }
  }

  /** Models a page request that had already completed when the cancellation reached it. */
  private static final class UncancellableFuture<T> extends CompletableFuture<T> {

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
      return false;
    }
  }
}
