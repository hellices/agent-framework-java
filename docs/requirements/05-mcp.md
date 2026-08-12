# 05 MCP integration

**Prefix** `MCP` · **Upstream features** [07 mcp-client-tools](../upstream/snapshots/d0a4165f/features/07-mcp-client-tools.md),
[24 mcp-hosting](../upstream/snapshots/d0a4165f/features/24-mcp-hosting.md)

Defines the contract for connecting MCP servers as remote tools and, when needed, exposing Java
agents or workflows as MCP tools. The local tool call loop and approval rules are owned by
[04 Tool definitions and the tool call loop](04-tools.md), general hosting and protocol adapters by
[10 Hosting and protocols](10-hosting.md), and the workflow runtime by
[09 Workflows and orchestration](09-workflows.md).

## Adoption scope

The `Grade` column in this document is, as [README](README.md#requirement-grades) defines it, how binding a requirement is once the decision to build the feature has been made; whether the feature is adopted at all follows the [compatibility matrix](../upstream/snapshots/d0a4165f/compatibility-matrix.md).

- MCP client tools (`MCP01`, `MCP02`) have adoption `Required`.
- `MCP-015`–`MCP-019`, corresponding to the MCP server hosting helper (`MCPH01`), have adoption `Optional`.

## Summary

| ID | Requirement | Adoption | Grade | Phase |
| --- | --- | --- | --- | --- |
| MCP-001 | Transport tools and connection adapters are co-located | Required | Required | Core+ |
| MCP-002 | Lifecycle is divided by connection ownership | Required | Required | Core+ |
| MCP-003 | Connection validation and reconnection are standardized | Required | Required | Core+ |
| MCP-004 | Tool discovery includes prefix and collision checking | Required | Required | Core+ |
| MCP-005 | Call arguments and metadata are separated | Required | Required | Core+ |
| MCP-006 | Prompts are exposed as functions and resources are kept as payload | Required | Required | Core+ |
| MCP-007 | Sampling is denied by default | Required | Required | Optional |
| MCP-008 | Sampling request count and token count are limited | Required | Required | Optional |
| MCP-009 | Trace propagation is differentiated by connection ownership | Required | Required | Core+ |
| MCP-010 | HTTP headers are propagated only to the same origin | Required | Required | Core+ |
| MCP-011 | Task-required tools are diverted to the task lifecycle | Required | Required | Optional |
| MCP-012 | The original call is not reissued after task creation | Required | Required | Optional |
| MCP-013 | Cancellation and timeouts are linked to remote cancellation | Required | Required | Optional |
| MCP-014 | Task terminal status is converted to an explicit result | Required | Required | Optional |
| MCP-015 | The general-purpose hosting helper is separated into its own artifact | Optional | Required | Hosting |
| MCP-016 | The hosting helper does not take on server host responsibilities | Optional | Required | Hosting |
| MCP-017 | The hosted agent/workflow adapter fixes schema and session rules | Optional | Required | Hosting |
| MCP-018 | Hosting result mapping produces a final result only once | Optional | Required | Hosting |
| MCP-019 | Prompts/resources/sampling/tasks hosting is outside the helper scope | Optional | Required | Hosting |

---

## MCP-001 Transport tools and connection adapters are co-located

**Requirement.** Java must provide both high-level MCP tool types that directly open stdio,
streamable HTTP, and WebSocket connections, and a low-level connection adapter that wraps an
already-connected
`McpClient`.

**Upstream comparison**

- .NET: There are no transport-specific tool classes; the focus is on task-aware wrapping of a connected `McpClient`.
- Python: Provides `MCPStdioTool`, `MCPStreamableHTTPTool`, and `MCPWebsocketTool` directly.

**Decision.** A hybrid combining both approaches is adopted. Transport-specific lifecycle and
security differences are surfaced as types like Python, while a separate seam for reusing existing
connections is kept like .NET.

**Acceptance criteria**

- The high-level API has MCP tool types for stdio, HTTP, and WebSocket.
- The low-level API has an adapter that wraps an already-connected `McpClient` or equivalent type.
- The low-level adapter does not accept new transport parameters.

**Evidence** [07 Public API and types](../upstream/snapshots/d0a4165f/features/07-mcp-client-tools.md),
[07 Java decisions](../upstream/snapshots/d0a4165f/features/07-mcp-client-tools.md)

---

## MCP-002 Lifecycle is divided by connection ownership

**Requirement.** High-level transport tools must own connection creation, closing, and reconnection,
while low-level connection adapters must not open or close the injected connection.

**Upstream comparison**

- .NET: The wrapper receives an already-connected `McpClient` as input and does not own the lifecycle.
- Python: The `MCPTool` base and transport subclasses directly manage the connect/close lifecycle.

**Decision.** The same boundary separation is required. The ownership rule that whichever side opens
the connection also closes it must be clear to prevent resource leaks and duplicate closes.

**Acceptance criteria**

- High-level transport tools have their own `connect()`/`close()` or equivalent lifecycle API.
- Low-level adapters do not close the injected connection.
- Even when multiple adapters share the same connection object, close responsibility remains with the connection provider.

**Evidence** [07 Client lifecycle](../upstream/snapshots/d0a4165f/features/07-mcp-client-tools.md),
[24 Client / server boundary](../upstream/snapshots/d0a4165f/features/24-mcp-hosting.md)

---

## MCP-003 Connection validation and reconnection are standardized

**Requirement.** High-level transport tools must verify connection validity, go through one
reconnection attempt when connection loss is detected, retry the original request, and clear all
caches and auxiliary state on close.

**Upstream comparison**

- .NET: The repo-local MCP surface does not directly expose a transport reconnect helper.
- Python: `_ensure_connected()` performs ping-based validity checking and reconnection, and `close()` clears the cache.

**Decision.** The Python approach is adopted. Transient disconnections are common for network MCP.
However, retries must be bounded. An incomplete close corrupts the sampling counter and discovery
cache.

**Acceptance criteria**

- On connection validation failure the tool attempts one reconnection and retries the original request.
- Servers that do not support ping are recorded in the capability cache to avoid repeated failures.
- After `close()`, the session, capability cache, sampling counter, and pending reload state are cleared.

**Evidence** [07 Client lifecycle](../upstream/snapshots/d0a4165f/features/07-mcp-client-tools.md)

---

## MCP-004 Tool discovery includes prefix and collision checking

**Requirement.** MCP tool discovery must read through pagination to the end, apply a local name
prefix, and fail if name collisions occur after normalization.

**Upstream comparison**

- .NET: Connected client tool wrapping converts the server tool list to an `AIFunction` list.
- Python: Reads `tools/list` with pagination and performs prefix application, allowlist safety, and normalized-name collision checking.

**Decision.** The Python approach is adopted. Allowing name collisions means allowlist and approval
rules can match the wrong tool. Tool discovery is a security boundary.

**Acceptance criteria**

- Tool discovery repeats `tools/list` until there is no `nextCursor`.
- The configured prefix is reflected in local tool names.
- If two different remote tools normalize to the same local name, the discovery step fails.

**Evidence** [07 Tool discovery / invocation](../upstream/snapshots/d0a4165f/features/07-mcp-client-tools.md),
[07 Concrete acceptance scenarios](../upstream/snapshots/d0a4165f/features/07-mcp-client-tools.md)

---

## MCP-005 Call arguments and metadata are separated

**Requirement.** MCP tool calls must send only declared input schema fields and explicitly allowed
extra arguments to the server, and must merge trace and runtime metadata via a separate `_meta`
path.

**Upstream comparison**

- .NET: The repo-local wrapper focuses on task wrapping rather than call argument filtering.
- Python: `_prepare_call_kwargs()` separates declared schema, extra arg names, `_meta`, and OTel metadata.

**Decision.** The Python approach is adopted. Mixing arguments and metadata breaks both server
schema validation and observability. Java must separate model-filled values from framework-attached
values.

**Acceptance criteria**

- General arguments not in the input schema are not forwarded to the server.
- Only explicitly allowed extra argument names are forwarded as exceptions.
- User `_meta` and framework metadata are merged without overwriting each other.

**Evidence** [07 Tool discovery / invocation](../upstream/snapshots/d0a4165f/features/07-mcp-client-tools.md)

---

## MCP-006 Prompts are exposed as functions and resources are kept as payload

**Requirement.** MCP prompts must be exposed as local function-style tools, and MCP resources must
not be promoted to a top-level discovery surface but handled only as content within tool or prompt
result payloads.

**Upstream comparison**

- .NET: Resources are closer to a skill-loading boundary, and no prompt hosting surface is visible.
- Python: Prompts are exposed as `FunctionTool` instances and resources are kept at the payload conversion boundary.

**Decision.** The Python approach is adopted. Prompts are a callable surface the model can select.
Keeping resources as payload avoids unnecessarily widening the discovery surface.

**Acceptance criteria**

- `prompts/list` results are converted to a local function-style tool list.
- The client MCP surface has no top-level resource discovery API such as `loadResources()`.
- `ResourceLink` and embedded resources within tool/prompt results are preserved as content.

**Evidence** [07 Resources / prompts boundary](../upstream/snapshots/d0a4165f/features/07-mcp-client-tools.md),
[07 Java decisions](../upstream/snapshots/d0a4165f/features/07-mcp-client-tools.md)

---

## MCP-007 Sampling is denied by default

**Requirement.** Server-initiated `sampling/createMessage` requests must be denied by default when
no explicit approval callback is present.

**Upstream comparison**

- .NET: No surface corresponding to Python's sampling approval callback is visible in the reviewed package.
- Python: If no sampling approval callback is present, the request is denied and no local LLM call is made.

**Decision.** The Python approach is adopted. An external MCP server can be an untrusted third
party. Permitting by default creates a confused deputy risk.

**Acceptance criteria**

- Without an approval callback, a sampling request ends with an error response.
- In that case the local chat client is not invoked.
- Even if the approval callback throws an exception, the result is denial.

**Evidence** [07 Sampling approval callback](../upstream/snapshots/d0a4165f/features/07-mcp-client-tools.md),
[07 Java decisions](../upstream/snapshots/d0a4165f/features/07-mcp-client-tools.md)

---

## MCP-008 Sampling request count and token count are limited

**Requirement.** Sampling must have per-connection request count and token count caps, and even
approved requests must not exceed these caps.

**Upstream comparison**

- .NET: No equivalent sampling budget API is visible in the repo-local MCP surface.
- Python: Defines `sampling_max_requests` and `sampling_max_tokens` and resets the counter on reconnect.

**Decision.** The Python approach is adopted. Denial by default alone is insufficient. Even approved
sampling can run away, so both quantity and size must be limited together.

**Acceptance criteria**

- The `maxTokens` of an approved sampling request is clamped to the configured cap.
- Once the sampling request count exceeds the cap, subsequent requests are denied.
- Opening a new connection resets the sampling request counter.

**Evidence** [07 Sampling approval callback](../upstream/snapshots/d0a4165f/features/07-mcp-client-tools.md)

---

## MCP-009 Trace propagation is differentiated by connection ownership

**Requirement.** For connections opened directly by Java, trace context and transport attributes
must be propagated in MCP metadata and headers. For connections managed by the host or provider,
only the propagation already present on the existing connection is honored, and the Java core must
not assume or overwrite new transport-level propagation.

**Upstream comparison**

- .NET: No explicit OTel span instrumentation like Python's is visible in the repo-local MCP package.
- Python: `create_mcp_client_span(...)` and `_meta` merging directly perform tracing for client-owned connections.

**Decision.** The difference between the two upstreams is reflected as-is. When Java opens the
transport it also assumes propagation responsibility. Conversely, when wrapping an already-open
connection the provider-managed boundary must be respected.

**Acceptance criteria**

- stdio, HTTP, and WebSocket high-level tools leave a trace span or equivalent observation event for every initialize, discovery, call, and prompt request.
- High-level tools inject trace context into MCP `_meta` or transport headers.
- The low-level connected-client adapter does not synthesize new transport headers and uses the propagation policy of the injected connection as-is.

**Evidence** [07 Trace / error / cancellation / security](../upstream/snapshots/d0a4165f/features/07-mcp-client-tools.md)

---

## MCP-010 HTTP headers are propagated only to the same origin

**Requirement.** Dynamic header injection for the streamable HTTP transport must apply only to the
same origin, and one call's header snapshot must not leak into another call during concurrent
invocations.

**Upstream comparison**

- .NET: The outbound declarative bridge implements cross-origin credential stripping and auto-redirect prohibition.
- Python: `header_provider` fixes same-origin policy and concurrent snapshot isolation via tests.

**Decision.** The safe defaults of both upstreams are combined. HTTP authentication headers are the
most easily leaked information. Both redirects and concurrency must be guarded against together.

**Acceptance criteria**

- Dynamic authentication headers are attached only to same-origin requests.
- `Authorization`/`Cookie`-class headers are not re-injected on cross-origin redirects or alternate-origin requests.
- Even when two calls are sent concurrently, each call uses only its own header snapshot.

**Evidence** [07 Trace / error / cancellation / security](../upstream/snapshots/d0a4165f/features/07-mcp-client-tools.md),
[24 Auth / error / security](../upstream/snapshots/d0a4165f/features/24-mcp-hosting.md)

---

## MCP-011 Task-required tools are diverted to the task lifecycle

**Requirement.** MCP tools with `taskSupport == required` must not be executed via the normal
`tools/call` path but automatically diverted to the task creation, poll, and result retrieval
lifecycle.

**Upstream comparison**

- .NET: Only tools with `ToolTaskSupport.Required` are wrapped in `TaskAwareMcpClientAIFunction`.
- Python: Required tools are recorded at discovery and `call_tool()` automatically routes to `call_tool_as_task()`.

**Decision.** Both upstreams agree. Requiring callers to manually branch for required tasks is
error-prone. Task-required is a contract of the tool definition, so the runtime must enforce it
automatically.

**Acceptance criteria**

- Task-required tools do not go through the normal inline call path.
- Optional or inline tools retain the existing `tools/call` path.
- Whether a tool is task-required is read from the discovery result metadata.

**Evidence** [07 MCP tasks / long-running operations](../upstream/snapshots/d0a4165f/features/07-mcp-client-tools.md),
[07 Concrete acceptance scenarios](../upstream/snapshots/d0a4165f/features/07-mcp-client-tools.md)

---

## MCP-012 The original call is not reissued after task creation

**Requirement.** After obtaining a task id from the task creation response, the same tool call must
not be reissued via `tools/call`, and the polling interval must clamp the server-suggested value to
a safe range.

**Upstream comparison**

- .NET: The task-aware wrapper continues with poll and result fetch after `CallToolAsTaskAsync()`.
- Python: `call_tool_as_task()` uses only `tasks/get` and `tasks/result` after create, and clamps the poll interval.

**Decision.** The common semantics of both upstreams are fixed. Reissuing the original call after
create can produce duplicate work on the server. The polling interval must also be bounded since the
server can suggest excessive values.

**Acceptance criteria**

- After receiving a task id, no second `tools/call` is issued for the same logical call.
- The server `pollInterval` is clamped within minimum and maximum safe bounds.
- If the create result is a plain non-task result, the task path ends immediately with an inline fallback.

**Evidence** [07 MCP tasks / long-running operations](../upstream/snapshots/d0a4165f/features/07-mcp-client-tools.md),
[07 Java decisions](../upstream/snapshots/d0a4165f/features/07-mcp-client-tools.md)

---

## MCP-013 Cancellation and timeouts are linked to remote cancellation

**Requirement.** Local cancellation and `maxTaskWait` expiration must by default lead to a
best-effort remote `tasks/cancel`, and this behavior must be disableable only via an option.

**Upstream comparison**

- .NET: `CancelRemoteTaskOnLocalCancellation=true` is the default and `CancelTaskAsync()` is attempted on cancellation.
- Python: Both local cancellation and deadline abandonment attempt a best-effort remote cancel.

**Decision.** Both upstreams agree. Cancelling only locally can leave the server task running.
The default must be cleanup. Opt-out is reserved as an exception for special server compatibility.

**Acceptance criteria**

- When local cancellation occurs, remote `tasks/cancel` is attempted under default settings.
- When the remote cancellation disable option is enabled, the above call is not issued.
- `maxTaskWait` exceeded ends with an explicit error accompanied by a best-effort remote cancellation.

**Evidence** [07 MCP tasks / long-running operations](../upstream/snapshots/d0a4165f/features/07-mcp-client-tools.md),
[07 .NET implementation and tests](../upstream/snapshots/d0a4165f/features/07-mcp-client-tools.md)

---

## MCP-014 Task terminal status is converted to an explicit result

**Requirement.** Terminal statuses other than `completed` and malformed result payloads of MCP tasks
must all be converted to explicit errors, and the final result shape of the success path must be
identical to that of an inline tool call.

**Upstream comparison**

- .NET: If not completed, `OperationCanceledException` or `InvalidOperationException` is surfaced.
- Python: `failed`, `cancelled`, `input_required`, and malformed payloads are converted to `ToolExecutionException`.

**Decision.** The common semantics of both upstreams are adopted. The task path must produce the
same final result shape as an inline call so that the upper tool loop does not need branching code.

**Acceptance criteria**

- A `completed` result is converted to `Content` or an equivalent result shape identical to an inline call.
- `failed`, `cancelled`, and `input_required` are not disguised as success results.
- A malformed task result payload becomes an explicit failure.

**Evidence** [07 MCP tasks / long-running operations](../upstream/snapshots/d0a4165f/features/07-mcp-client-tools.md)

---

## MCP-015 The general-purpose hosting helper is separated into its own artifact

**Requirement.** The general-purpose MCP server hosting helper must be provided as a separate
artifact from the Java core, and this asymmetry must directly reflect the fact that Python upstream
has a helper while the .NET repo-local source has no corresponding general-purpose helper.

**Upstream comparison**

- .NET: No repo-local general-purpose MCP server hosting helper is visible; only the outbound client bridge and task wrapper exist.
- Python: `agent-framework-hosting-mcp` provides a generic helper for app-owned MCP servers.

**Decision.** The Python helper is useful but placing it in the core pulls in host responsibilities.
Therefore Java adopts the helper but separates it into a distinct `Hosting` artifact. The asymmetry
with .NET is not hidden in documentation.

**Acceptance criteria**

- The MCP server hosting helper is a separate artifact from the core MCP client module.
- The core MCP client module does not depend on the hosting helper.
- Remote MCP client integration can be used without the hosting helper.

**Evidence** [24 Summary](../upstream/snapshots/d0a4165f/features/24-mcp-hosting.md),
[24 Maturity](../upstream/snapshots/d0a4165f/features/24-mcp-hosting.md),
[24 .NET / Python differences](../upstream/snapshots/d0a4165f/features/24-mcp-hosting.md)

---

## MCP-016 The hosting helper does not take on server host responsibilities

**Requirement.** The MCP hosting helper must own only tool schema and run/result conversion, and
must not take on server object creation, handler registration, transport, auth, session trust,
concurrency, or deployment responsibilities.

**Upstream comparison**

- .NET: The repo-local surface is closer to an outbound MCP client bridge than a hosting helper.
- Python: The README and AGENTS explicitly state that the helper does not own server, transport, or auth.

**Decision.** The Python boundary is adopted. This aligns with the principle that `AgentEngine` does
not take on host responsibilities. The helper must be a thin seam.

**Acceptance criteria**

- The helper public API has no generic MCP `Server` creation or handler registration API.
- The helper public API has no stdio/HTTP transport launcher.
- Auth/authz, session id trust, and concurrency policy remain host application contracts, not helper configuration.

**Evidence** [24 Boundary](../upstream/snapshots/d0a4165f/features/24-mcp-hosting.md),
[24 Client / server boundary](../upstream/snapshots/d0a4165f/features/24-mcp-hosting.md)

---

## MCP-017 The hosted agent/workflow adapter fixes schema and session rules

**Requirement.** The hosted agent adapter and workflow adapter must fix input schema generation
rules, chat option mapping, `session_id_parameter` semantics, and unsupported workflow continuation
errors.

**Upstream comparison**

- .NET: No general-purpose hosted agent/workflow adapter exists; the declarative bridge handles only remote MCP calls.
- Python: `AgentMCPTool` and `WorkflowMCPTool` provide schema derivation, session persistence, and workflow input validation.

**Decision.** The Python approach is adopted. If the host redefines schema and session semantics
every time, the tool contract becomes unstable. In particular, workflow external input must not be
silently accepted.

**Acceptance criteria**

- The agent adapter reflects extra parameters and chat option parameters under the same names in both schema and execution.
- When `sessionIdParameter` is used, the session is read with the same key and saved again after execution.
- The workflow adapter raises an explicit error when the input type is not singular or when external input continuation is required.

**Evidence** [24 Tools / resources / prompts exposure](../upstream/snapshots/d0a4165f/features/24-mcp-hosting.md),
[24 Session / lifecycle](../upstream/snapshots/d0a4165f/features/24-mcp-hosting.md),
[24 Python implementation and tests](../upstream/snapshots/d0a4165f/features/24-mcp-hosting.md)

---

## MCP-018 Hosting result mapping produces a final result only once

**Requirement.** The result mapping of the MCP hosting helper must produce only one final
`CallToolResult`, and partial tool result streaming must not be included in the helper contract.

**Upstream comparison**

- .NET: The declarative bridge also converts the completed `CallToolResult.ContentBlock` to a final `AIContent`.
- Python: `mcp_from_run(...)` produces only a final `ContentBlock[]` and does not perform partial result streaming.

**Decision.** Both upstreams agree. Even if the transport supports streaming, extending the tool
result contract to be incremental makes host and client semantics unnecessarily complex.

**Acceptance criteria**

- The helper API does not emit incremental `CallToolResult` chunks.
- The final result mapping preserves text, URI resource links, image/audio, and other binary resources.
- Unsupported content such as `function_call` is excluded from the MCP tool result payload.

**Evidence** [24 Streaming / result mapping](../upstream/snapshots/d0a4165f/features/24-mcp-hosting.md),
[24 Resources exposure](../upstream/snapshots/d0a4165f/features/24-mcp-hosting.md)

---

## MCP-019 Prompts/resources/sampling/tasks hosting is outside the helper scope

**Requirement.** The general-purpose MCP hosting helper must not provide `prompts/*`, `resources/*`,
sampling callback contracts, or long-running task/progress hosting APIs.

**Upstream comparison**

- .NET: `IMcpToolHandler` defines only tool invocation and has no hosting prompts/resources API.
- Python: The hosting helper exports only tool adapters and run/result conversion, leaving sampling and tasks as app-owned boundaries.

**Decision.** Both upstreams agree. Mixing the tool exposure seam with the full MCP server protocol
makes responsibilities excessively broad. If needed, a separate artifact must be designed.

**Acceptance criteria**

- The helper public API has no `prompts/list`, `prompts/get`, `resources/list`, or `resources/read` helpers.
- Sampling-only content is not carried in `CallToolResult.content`.
- Long-running tasks and progress notifications are owned by host protocol logic or client wrappers, not the helper.

**Evidence** [24 Boundary](../upstream/snapshots/d0a4165f/features/24-mcp-hosting.md),
[24 Prompts exposure](../upstream/snapshots/d0a4165f/features/24-mcp-hosting.md),
[24 Sampling / tasks boundary](../upstream/snapshots/d0a4165f/features/24-mcp-hosting.md)

---

## What this document does not cover

| Topic | Owning document |
| --- | --- |
| Local function tool definitions, budgets, approval loop | [04 Tool definitions and the tool call loop](04-tools.md) |
| Agent public execution API and model port | [01 Agent execution and model calls](01-agent-execution.md) |
| Workflow graph and checkpoint runtime | [09 Workflows and orchestration](09-workflows.md) |
| General hosting lifecycle and protocol adapters | [10 Hosting and protocols](10-hosting.md) |
| Provider-specific model integration and infrastructure adapters | [12 Provider integration](12-providers.md) |
