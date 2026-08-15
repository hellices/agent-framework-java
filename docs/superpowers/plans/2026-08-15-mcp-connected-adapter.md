# Connected MCP Client Adapter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the borrowed-connection half of MCP-001 plus MCP-004 and MCP-005: convert an already-connected official Java SDK client into safe local `FunctionTool` instances.

**Architecture:** A new `integrations/agent-framework-mcp` artifact depends on the public tool API and official MCP Java SDK 2.0.0. `ConnectedMcpClientAdapter` never opens or closes the injected client. It delegates through a narrow internal async operations port so pagination, naming, filtering, metadata, and result mapping are tested without processes or network.

**Tech Stack:** Java 17, official `io.modelcontextprotocol.sdk:mcp-core:2.0.0`, Reactor-to-`CompletionStage` bridging supplied by the SDK, Gradle Kotlin DSL, JUnit 5, AssertJ.

## Global Constraints

- The adapter borrows connection ownership and never initializes, opens, reconnects, or closes the injected client.
- Discovery follows every `nextCursor`, rejects cursor cycles, applies the configured prefix, and rejects post-normalization collisions.
- Only declared input-schema properties and explicitly configured extra argument names reach `tools/call`.
- Runtime/trace metadata is created through a separate callback and sent only in MCP `_meta`; it is never merged into arguments.
- The default metadata callback returns an empty map; arbitrary run attributes are not propagated by default.
- Tool results preserve MCP text as core text and non-text payloads as MCP-specific content without promoting SDK types into core modules.
- The module creates no executors, network clients, processes, global registries, or shutdown hooks.
- Sampling, transport ownership/reconnect, prompts/resources discovery, tasks, trace/header propagation, and hosting remain outside this PR.

---

### Task 1: Borrowed MCP client tool discovery and invocation

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `gradle/libs.versions.toml`
- Modify: `agent-framework-bom/build.gradle.kts`
- Create: `integrations/agent-framework-mcp/build.gradle.kts`
- Create: `integrations/agent-framework-mcp/src/main/java/io/github/hellices/agentframework/mcp/ConnectedMcpClientAdapter.java`
- Create: `integrations/agent-framework-mcp/src/main/java/io/github/hellices/agentframework/mcp/McpToolAdapterOptions.java`
- Create: `integrations/agent-framework-mcp/src/main/java/io/github/hellices/agentframework/mcp/McpCallMetadataProvider.java`
- Create: `integrations/agent-framework-mcp/src/main/java/io/github/hellices/agentframework/mcp/McpPayloadContent.java`
- Create internal operations/discovery/mapping classes under `.../mcp/internal/`
- Test: `integrations/agent-framework-mcp/src/test/java/io/github/hellices/agentframework/mcp/ConnectedMcpClientAdapterTest.java`
- Modify: `docs/design/requirements-design/requirements-traceability-matrix.md`

**Interfaces:**

```java
public final class ConnectedMcpClientAdapter {
  public ConnectedMcpClientAdapter(McpAsyncClient client);
  public ConnectedMcpClientAdapter(McpAsyncClient client, McpToolAdapterOptions options);
  public CompletionStage<List<FunctionTool>> discoverTools();
}

@FunctionalInterface
public interface McpCallMetadataProvider {
  Map<String, Object> metadata(ToolContext context);
}
```

- [ ] **Step 1: Add the integration module and pinned dependency**

Add `mcpSdk = "2.0.0"` and aliases for `mcp-core`. The integration module uses the existing Java/test/quality conventions, `api(project(":agent-framework-api"))`, and `api(libs.mcp.core)` because its public constructor accepts `McpAsyncClient`. Add the module to the framework BOM.

- [ ] **Step 2: Write failing discovery tests**

Use a fake internal operations port returning controlled `McpSchema.ListToolsResult` pages. Cover all pages, null/empty final cursors, repeated-cursor rejection, prefixing, normalized collisions, null tools/pages, declaration order, and no client close.

- [ ] **Step 3: Verify RED**

```bash
./gradlew :integrations:agent-framework-mcp:test
```

Expected: project/classes do not exist.

- [ ] **Step 4: Implement paginated discovery**

Start at `McpSchema.FIRST_PAGE`, call the cursor overload explicitly, and follow `nextCursor` until null/blank. Keep a cursor set and fail on repetition. Normalize local names by converting runs outside `[A-Za-z0-9_-]` to `_`, prepend a normalized optional prefix, and fail when two raw names map to one local name.

- [ ] **Step 5: Map remote tools to FunctionTool**

Copy remote name/description/input schema into the local definition. Each handler captures the raw remote name (never the normalized alias) and calls the internal operations port asynchronously.

- [ ] **Step 6: Filter arguments and separate metadata**

Build the MCP argument map in caller order from schema `properties` plus configured extra argument names. Drop every other key. Call the metadata callback separately, validate its map, and pass it as `CallToolRequest.meta`. Tests assert arguments never contain metadata and `_meta` never contains model arguments.

- [ ] **Step 7: Map tool results**

Map SDK text content to core `TextContent`. Preserve image/audio/resource/resource-link/structured payloads as `McpPayloadContent` with the SDK value only as integration-level raw representation. Map `isError == true` to `ToolResult.error=true`; null is false. Preserve result order.

- [ ] **Step 8: Verify lifecycle and failures**

Test client/list/call synchronous throws, failed Monos, null stages/results/content entries, metadata callback failures, cancellation of the returned stage, and prove the borrowed client is never closed by adapter/tool operations.

- [ ] **Step 9: Verify module and repository policies**

```bash
./gradlew :integrations:agent-framework-mcp:test :integrations:agent-framework-mcp:quality
./gradlew policyCheck
./gradlew testJava17 testJava25
```

- [ ] **Step 10: Update traceability and commit**

Set MCP-001 to `partial` (borrowed adapter only), MCP-004 and MCP-005 to `implemented`.

```bash
git add settings.gradle.kts gradle/libs.versions.toml agent-framework-bom integrations docs
git commit -m "mcp: add connected client tool adapter"
```

---

## Plan Self-Review

- The official SDK version and Java baseline are pinned centrally.
- No high-level transport type is claimed because the official 2.0.0 SDK has no WebSocket transport.
- Borrowed ownership, pagination, collision safety, argument filtering, and `_meta` separation have executable boundaries.
- MCP-002/003 lifecycle, MCP-006 prompts/resources, sampling, tasks, and hosting remain explicit later slices.
