# 24. MCP hosting

## Scope

This document covers only the **MCP(Model Context Protocol) server hosting and client/server boundary** of the Microsoft Agent Framework. The subjects are as follows.

- scope and limits of the MCP server hosting helper
- tools exposure and tool schema/result mapping
- absence or boundary of resources/prompts exposure
- stdio / streamable HTTP transport ownership
- session/lifecycle and session key policy
- hosting boundary of sampling / MCP tasks
- auth / error / stream mapping
- .NET / Python implementation differences
- Java design decisions

Conversely, the **detailed behavior of MCP client tools themselves** is considered within the scope of the separate “Document 07”. In this document, client-side MCP task wrappers and remote MCP invocation are covered only to the **minimum necessary to explain the hosting boundary**. Additionally, generic hosting lifecycle, OpenAI Responses, A2A, AG-UI, Foundry hosted runtime, DevUI, and Telegram/ChatKit are each within the scope of separate documents. (Source: [Python hosting-mcp README scope](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/README.md#L1-L20), [Python hosting-mcp AGENTS boundary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/AGENTS.md#L19-L24), [.NET IMcpToolHandler scope](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative/IMcpToolHandler.cs#L10-L41))

## Summary

Based on this repository, **a generic MCP server hosting helper exists explicitly only in Python**, and no corresponding generic MCP hosting package is visible in `.NET`. Python's `agent-framework-hosting-mcp` provides only **tool adapters and conversion helpers** for app-owned MCP servers, leaving the `Server`, handler registration, stdio/streamable HTTP transport, authentication, authorization, session trust, concurrency, and deployment as application responsibilities. Therefore, Python MCP hosting is “a seam that attaches the MCP tool surface to the Agent Framework run surface”, not a full host framework. In contrast, the closest things to MCP in `.NET` repo-local source are the **client-side long-running task wrapper** of `Microsoft.Agents.AI.Mcp` and the **outbound MCP client bridge** of `Microsoft.Agents.AI.Workflows.Declarative.Mcp.DefaultMcpToolHandler`. These are on the side of the server **calling** an external MCP server, rather than being a hosting adapter; in particular, the `.NET` declarative handler forces `HttpTransportMode.StreamableHttp` and includes credential origin pinning to prevent cross-origin credential leakage. Tools exposure is central to both languages, but prompts/resources exposure is almost absent from the repo-local hosting surface. The Python helper exports only the API for creating and invoking a single `Tool`, and the `.NET` declarative MCP handler in practice handles only `InvokeToolAsync(...)` and the reserved `tools/list`, with no `prompts/*` or `resources/*` hosting API. MCP sampling and experimental tasks also remain as separate client/protocol concerns rather than core concerns of the hosting helper; the Python README explicitly states that sampling-only `ToolUseContent` must not be placed into `CallToolResult.content`, and tasks/progress/streaming partial results are also left as app-owned concerns. (Source: [Python hosting-mcp README overview](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/README.md#L1-L20), [Python hosting-mcp AGENTS boundary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/AGENTS.md#L1-L24), [Python __all__ exports only tools](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/agent_framework_hosting_mcp/__init__.py#L1-L22), [.NET Mcp package description](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Mcp/Microsoft.Agents.AI.Mcp.csproj#L19-L22), [.NET DefaultMcpToolHandler summary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative.Mcp/DefaultMcpToolHandler.cs#L21-L29), [reserved tools/list operation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative.Mcp/DefaultMcpToolHandler.cs#L33-L38), [.NET StreamableHttp pinning](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative.Mcp/DefaultMcpToolHandler.cs#L233-L247), [Python sampling/tasks boundary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/README.md#L148-L174))

---

## Purpose

The purpose of MCP hosting support is to expose an Agent Framework target as a **`Tool` surface that the native MCP SDK understands**, or conversely to connect MCP `tools/call` style input/result to an Agent Framework run/result. The key here is “making a single agent or workflow appear as a single MCP tool”, not the framework owning the entire MCP server protocol (`prompts/list`, `resources/read`, sampling, transport session lifecycle).

Python README states this very clearly. The package provides:

- `mcp_to_run(...)`
- `mcp_from_run(...)`
- `AgentMCPTool`
- `WorkflowMCPTool`

only, and the surrounding application is responsible for the low-level MCP `Server`, request context, transport, authentication, and deployment. The closest thing in `.NET`, `DefaultMcpToolHandler`, is also a client bridge that abstracts MCP tool invocation within a declarative workflow, and does not create an MCP server itself. (Source: [Python hosting-mcp README public surface](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/README.md#L5-L20), [Python hosting-mcp AGENTS summary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/AGENTS.md#L1-L18), [.NET IMcpToolHandler abstracts invoke only](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative/IMcpToolHandler.cs#L10-L41), [.NET DefaultMcpToolHandler purpose](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative.Mcp/DefaultMcpToolHandler.cs#L21-L29))

## Boundary

### What this feature does
- derive/generate MCP `Tool` schema from an agent or workflow
- convert MCP `tools/call` arguments into Agent Framework run values
- convert Agent Framework `AgentResponse` or `WorkflowRunResult` into MCP `ContentBlock` items
- provide a helper API to be connected to an app-owned MCP server
- provide a bridge that configures a streamable HTTP client for outbound MCP tool invocation (.NET declarative workflow path)  
  (Source: [Python AgentMCPTool summary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/agent_framework_hosting_mcp/_agent_tool.py#L22-L29), [Python WorkflowMCPTool summary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/agent_framework_hosting_mcp/_workflow_tool.py#L23-L29), [.NET DefaultMcpToolHandler InvokeToolAsync](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative.Mcp/DefaultMcpToolHandler.cs#L66-L102))

### What this feature does not do
- create a generic MCP server object
- handler registration
- manage stdio transport
- own the entire streamable HTTP session lifecycle
- authentication / authorization policy
- session identifier trust policy
- concurrency control
- prompts/resources registry exposure
- sampling callback contract
- host-owned orchestration of the MCP tasks lifecycle

The Python README and AGENTS documents explicitly state this boundary, and also clearly note that `CallToolResult` is a single final result. The `.NET` declarative handler also exposes only one `IMcpToolHandler` and has no `prompts/*`/`resources/*` host API. (Source: [Python hosting-mcp README boundaries](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/README.md#L18-L20), [Python AGENTS boundary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/AGENTS.md#L19-L24), [Python CallToolResult final result note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/README.md#L154-L159), [.NET IMcpToolHandler tool-only contract](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative/IMcpToolHandler.cs#L17-L41))

---

## Maturity

| Area | .NET | Python |
|---|---|---|
| Generic MCP hosting helper | No generic package | `agent-framework-hosting-mcp` = `alpha` |
| MCP client/task wrapper | `Microsoft.Agents.AI.Mcp` = `alpha` | Not a separate full hosting; integrations scattered across helper/agent integrations |
| Declarative workflow MCP bridge | Present (`DefaultMcpToolHandler`) | N/A |
| First-party app-owned MCP server example | No generic helper | `StreamableHTTPSessionManager` example via integration test |

Source:
- [Python PACKAGE_STATUS hosting-mcp alpha](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L37-L40)
- [.NET Mcp csproj alpha](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Mcp/Microsoft.Agents.AI.Mcp.csproj#L1-L22)
- [.NET declarative MCP csproj description](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative.Mcp/Microsoft.Agents.AI.Workflows.Declarative.Mcp.csproj#L20-L26)

---

## Public APIs and types

## Python hosting helper
- `AgentMCPTool`
- `WorkflowMCPTool`
- `mcp_to_run`
- `mcp_from_run`  
  (Source: [Python hosting-mcp __all__](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/agent_framework_hosting_mcp/__init__.py#L1-L22))

## .NET repo-local MCP-adjacent surface
- `IMcpToolHandler`
- `DefaultMcpToolHandler`
- `McpTaskOptions`
- `McpClientTaskExtensions.ListAgentToolsWithTaskSupportAsync(...)`
- internal `TaskAwareMcpClientAIFunction`  
  (Source: [.NET IMcpToolHandler](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative/IMcpToolHandler.cs#L10-L41), [.NET DefaultMcpToolHandler](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative.Mcp/DefaultMcpToolHandler.cs#L29-L64), [.NET McpTaskOptions](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Mcp/McpTaskOptions.cs#L7-L39), [.NET client task extensions](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Mcp/McpClientTaskExtensions.cs#L13-L60), [.NET TaskAwareMcpClientAIFunction](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Mcp/TaskAwareMcpClientAIFunction.cs#L15-L147))

## What is absent from the public surface
The repo-local hosting/public surface lacks the following.

- MCP prompt hosting API
- MCP resource hosting API
- generic stdio server host
- generic streamable HTTP server host

The fact that the Python helper's public exports are tool adapters only, and that the `.NET` tool-only `IMcpToolHandler` exists, supports this absence. (Source: [Python hosting-mcp __all__ only tools](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/agent_framework_hosting_mcp/__init__.py#L16-L22), [.NET IMcpToolHandler tool-only contract](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative/IMcpToolHandler.cs#L17-L41))

---

## Tools / resources / prompts exposure

## Tools exposure

### Python
The core of the Python helper is “exposing a single agent or a single workflow as a single MCP `Tool`”.

- `AgentMCPTool` generates a `Tool` definition based on the target agent's name/description.
- `parameters` adds app-owned extra JSON Schema properties.
- `chat_option_parameters` is included in the schema and is explicitly copied as Agent Framework chat options.
- If `session_id_parameter` is present, `AgentState` get/run/set is performed on every tool call.  
  (Source: [AgentMCPTool constructor and schema rules](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/agent_framework_hosting_mcp/_agent_tool.py#L31-L87), [Tool schema generation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/agent_framework_hosting_mcp/_agent_tool.py#L89-L120), [call_tool with session persistence](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/agent_framework_hosting_mcp/_agent_tool.py#L134-L183))

`WorkflowMCPTool` derives `inputSchema` from the input type of the workflow start executor. An object-shaped input becomes a top-level JSON object schema, and a primitive input is wrapped under a configurable `argument_name` property. (Source: [WorkflowMCPTool schema derivation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/agent_framework_hosting_mcp/_workflow_tool.py#L62-L90), [primitive wrapping and validation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/agent_framework_hosting_mcp/_workflow_tool.py#L91-L99))

### .NET
The `.NET` declarative MCP bridge is not a surface for **exposing** server-side tools, but a surface for **invoking** tools from a remote MCP server within a declarative workflow. However, it supports a special operation that returns the tool discovery result of a remote MCP server as JSON text via the reserved `toolName == "tools/list"`. That is, the `.NET` repo-local MCP exposure is closer to “my workflow sees the tools of an external MCP server” than “I become an MCP server”. (Source: [DefaultMcpToolHandler ListToolsToolName](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative.Mcp/DefaultMcpToolHandler.cs#L33-L38), [list tools branch in InvokeToolAsync](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative.Mcp/DefaultMcpToolHandler.cs#L76-L82), [SerializeToolsList](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative.Mcp/DefaultMcpToolHandler.cs#L389-L421))

## Resources exposure

Python `mcp_from_run(...)` converts Agent Framework output content into MCP result blocks while:

- external URI → `ResourceLink`
- binary non-image/audio → `EmbeddedResource(BlobResourceContents)`
- using the optional app-owned `additional_properties["uri"]` as the binary resource uri, and falling back to `af://binary` if absent

However, this is merely **resource-like content within a tool result payload**, not a feature that exposes the MCP server's `resources/list` or `resources/read` endpoint. (Source: [Python mcp_from_run resource mapping](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/agent_framework_hosting_mcp/_conversion.py#L65-L155), [README note on app-owned binary resource URI](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/README.md#L143-L146))

The `.NET` declarative bridge converts inbound `CallToolResult.ContentBlock` into `AIContent`, mapping `ResourceLinkBlock` to `UriContent`. This too is **resource result consumption**, not a resources exposure API. (Source: [DefaultMcpToolHandler ConvertContentBlock](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative.Mcp/DefaultMcpToolHandler.cs#L345-L365), [CreateAdditionalProperties for ResourceLinkBlock](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative.Mcp/DefaultMcpToolHandler.cs#L367-L387))

## Prompts exposure

The repo-local hosting surface shows no MCP `prompts/list`, `prompts/get`, or prompt template hosting helper.

- The Python hosting-mcp public API exports only `Tool` adapters and run/result conversion.
- `.NET` `IMcpToolHandler` also defines only one `InvokeToolAsync(...)`.

Therefore, the code-first interpretation is that prompt exposure in this scope is **not supported, or is left to app-owned custom MCP SDK composition**. (Source: [Python hosting-mcp __all__](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/agent_framework_hosting_mcp/__init__.py#L16-L22), [.NET IMcpToolHandler single method](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative/IMcpToolHandler.cs#L17-L41))

---

## Client / server boundary

## Python

The Python helper package draws the boundary clearly.

- What the helper owns:
  - tool schema derive
  - call arguments → run values conversion
  - final result → `ContentBlock[]` conversion
  - optional `AgentState` get/run/set
- What the app owns:
  - native MCP `Server`
  - handler registration
  - request context
  - stdio or streamable HTTP transport
  - auth/authz
  - session key trust
  - concurrency
  - deployment

Both the README and AGENTS documents repeat this point. (Source: [Python hosting-mcp README boundary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/README.md#L18-L20), [Python AGENTS boundary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/AGENTS.md#L19-L24), [AgentMCPTool schema/execute only](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/agent_framework_hosting_mcp/_agent_tool.py#L22-L29))

## .NET

No repo-local generic MCP server hosting helper is visible in `.NET`. What exists instead is:

1. **client-side** long-running task wrapper in `Microsoft.Agents.AI.Mcp`
2. **declarative workflow outbound MCP client bridge** in `DefaultMcpToolHandler`

That is, `.NET` repo-local MCP support is closer to “connecting safely as a client to an existing MCP server, or invoking from a workflow” than “hosting an MCP server”. This is also why this document covers these only as a **boundary reference** rather than a detailed implementation. (Source: [.NET Mcp package description](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Mcp/Microsoft.Agents.AI.Mcp.csproj#L19-L22), [.NET TaskAwareMcpClientAIFunction summary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Mcp/TaskAwareMcpClientAIFunction.cs#L15-L41), [.NET DefaultMcpToolHandler summary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative.Mcp/DefaultMcpToolHandler.cs#L21-L29))

---

## Detailed execution flow

## Python agent tool adapter

1. The host creates `AgentMCPTool(target, ...)`.
2. `list_tools()` generates a single native MCP `Tool` definition from the target agent metadata.
3. When `call_tool(name, arguments)` is called:
   - tool name validation
   - `mcp_to_run(arguments, argument_name=..., chat_option_arguments=...)`
   - session load if `session_id_parameter` is present
   - `target.run(...)`
   - generate `ContentBlock[]` with `mcp_from_run(result)`  
  (Source: [AgentMCPTool.list_tools](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/agent_framework_hosting_mcp/_agent_tool.py#L89-L120), [AgentMCPTool.call_tool](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/agent_framework_hosting_mcp/_agent_tool.py#L134-L183), [mcp_to_run](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/agent_framework_hosting_mcp/_conversion.py#L19-L62))

## Python workflow tool adapter

1. The host creates `WorkflowMCPTool(target, ...)`.
2. `_input_adapter(workflow)` reads a single start executor input type.
3. `list_tools()` derives the JSON Schema of the input type.
4. `call_tool(...)` validates the arguments against that type, then calls `workflow.run(..., stream=False)`.
5. `mcp_from_run(result)` converts only completed outputs into `ContentBlock[]`.
6. If the workflow requires external input, the adapter raises `ValueError` and the host must design the continuation contract.  
   (Source: [WorkflowMCPTool._input_adapter](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/agent_framework_hosting_mcp/_workflow_tool.py#L83-L90), [Workflow schema generation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/agent_framework_hosting_mcp/_workflow_tool.py#L62-L81), [workflow input and call_tool](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/agent_framework_hosting_mcp/_workflow_tool.py#L91-L147), [external input error](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/agent_framework_hosting_mcp/_workflow_tool.py#L100-L121))

## Python end-to-end streamable HTTP example

The integration test shows an example of actual app-owned MCP server composition.

1. native `Server("hosting-mcp-integration")`
2. Connecting `AgentMCPTool` to `@server.list_tools()` / `@server.call_tool()`
3. Configuring the streamable HTTP transport lifecycle with `StreamableHTTPSessionManager`
4. Starlette `Mount("/", app=session_manager.handle_request)`
5. Making an HTTP call with a separate `MCPStreamableHTTPTool` client

That is, the helper does not create the transport, but the integration test demonstrates that it **integrates well with streamable HTTP in practice**. (Source: [Python MCP integration test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/tests/hosting_mcp/test_integration.py#L55-L118))

## .NET declarative workflow bridge

1. A workflow action or handler calls `IMcpToolHandler.InvokeToolAsync(serverUrl, serverLabel, toolName, arguments, headers, connectionName, ct)`.
2. `DefaultMcpToolHandler` reuses a `McpClient` with the `(url,label,connection,headersHash)` cache key.
3. If `httpClientProvider` is present, a per-server auth `HttpClient` is injected; otherwise an owned `HttpClient` is created.
4. The owned client:
   - `UseCookies=false`
   - `AllowAutoRedirect=false`
   - origin pinning handler
   - `HttpTransportMode.StreamableHttp`
   is used.
5. If `toolName == "tools/list"`, `ListToolsAsync()` is executed to produce a JSON text result.
6. For a normal tool, `CallToolAsync()` is performed and the `CallToolResult.ContentBlock` items are converted to `AIContent`. (Source: [cache key and GetOrCreateClientAsync](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative.Mcp/DefaultMcpToolHandler.cs#L150-L189), [CreateClientAsync transport options](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative.Mcp/DefaultMcpToolHandler.cs#L191-L247), [tools/list branch](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative.Mcp/DefaultMcpToolHandler.cs#L76-L82), [call tool branch](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative.Mcp/DefaultMcpToolHandler.cs#L84-L102))

---

## stdio / streamable HTTP

## Python

The Python hosting helper does not own the transport. Both the README and AGENTS explicitly state that **stdio or streamable HTTP transport is app-owned**. The actual integration test uses `StreamableHTTPSessionManager` to demonstrate the HTTP path, but the package itself does not provide a stdio helper. (Source: [Python README transport ownership](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/README.md#L171-L174), [Python AGENTS transport boundary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/AGENTS.md#L21-L24), [Python integration test StreamableHTTPSessionManager](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/tests/hosting_mcp/test_integration.py#L66-L88))

## .NET

No surface for selecting stdio/HTTP transport of a generic MCP server host is visible in repo-local `.NET` code. Rather, `DefaultMcpToolHandler` forces `StreamableHttp` as the **client-side outbound transport**. This is a security choice to avoid server-advertised cross-origin SSE endpoints or redirects. Therefore, `.NET` MCP is also closer to an **outbound client bridge than hosting**, from the transport perspective as well. (Source: [DefaultMcpToolHandler TransportMode.StreamableHttp](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative.Mcp/DefaultMcpToolHandler.cs#L233-L247), [origin pinning rationale](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative.Mcp/DefaultMcpToolHandler.cs#L425-L449))

---

## Session / lifecycle

## Python

### Agent session persistence
`AgentMCPTool` operates with one mutable conversation key semantics when `session_id_parameter` is set.

- `AgentState.get_or_create_session(session_id)` on every call
- `target.run(..., session=session)`
- `state.set_session(session_id, session)` after completion

Branching (`previous_response_id` style) is not provided, and the application must design source/destination ids under a separate contract. Concurrency serialization for the same session id is also the host's responsibility. (Source: [README session_id_parameter section](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/README.md#L111-L136), [AgentMCPTool session path](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/agent_framework_hosting_mcp/_agent_tool.py#L168-L183))

### Workflow lifecycle
`WorkflowMCPTool` documents that if concurrent independent calls are needed due to workflow instance statefulness, the `WorkflowState(..., cache_target=False)` factory path should be used. Checkpoint restoration and human-in-the-loop continuation are application-owned contracts. (Source: [README workflow host note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/README.md#L98-L109), [WorkflowMCPTool init doc](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/agent_framework_hosting_mcp/_workflow_tool.py#L31-L50))

## .NET

The lifecycle of `.NET` `DefaultMcpToolHandler` is “client cache + owned HttpClient lifecycle”.

- `McpClient` reuse per cache key
- owned `HttpClient` is disposed only on handler disposal
- user-provided `HttpClient` is not disposed
- `DisposeAsync()` cleans up all cached clients and owned HttpClients

That is, this is an **outbound MCP connection/session lifecycle**, not a server session. (Source: [client cache fields](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative.Mcp/DefaultMcpToolHandler.cs#L41-L44), [DisposeAsync lifecycle](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative.Mcp/DefaultMcpToolHandler.cs#L121-L148))

---

## Sampling / tasks boundary

## Python

The Python hosting helper **does not own** sampling and experimental MCP tasks as a server hosting concern.

- `mcp_from_run(...)` targets `CallToolResult.content`, so sampling-only `ToolUseContent` is not placed in it.
- MCP sampling callbacks must use a separate response contract.
- `CallToolResult` is a single final result and is not converted into partial streaming results.
- Progress notifications and experimental tasks are also application-owned protocol concerns. (Source: [README ToolUseContent boundary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/README.md#L148-L152), [README final result and tasks boundary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/README.md#L154-L159), [AGENTS same boundary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/AGENTS.md#L47-L53))

## .NET

MCP tasks exist in repo-local `.NET` but are owned by the `Microsoft.Agents.AI.Mcp` client package. `McpTaskOptions` and `TaskAwareMcpClientAIFunction`:

- `CallToolAsTaskAsync`
- `PollTaskUntilCompleteAsync`
- `GetTaskResultAsync`
- optional remote `tasks/cancel` on local cancellation

are implemented, but this is a **client-side tool wrapper, not a hosting helper**. Therefore, this document records only their existence and boundary. (Source: [McpTaskOptions](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Mcp/McpTaskOptions.cs#L7-L39), [TaskAwareMcpClientAIFunction summary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Mcp/TaskAwareMcpClientAIFunction.cs#L15-L41), [poll/cancel logic](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Mcp/TaskAwareMcpClientAIFunction.cs#L110-L146), [ListAgentToolsWithTaskSupportAsync wrapping policy](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Mcp/McpClientTaskExtensions.cs#L19-L33))

---

## Streaming / result mapping

## Python

### No partial MCP tool result streaming
The Python helper does not convert tool results into streaming partial chunks. `mcp_from_run(...)` returns only a final `ContentBlock[]`. Even if the streamable HTTP transport can carry multiple MCP messages, that does not directly translate into incremental streaming of `CallToolResult.content`. (Source: [README no partial content blocks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/README.md#L154-L159))

### Result content mapping
`mcp_from_run(...)` performs the following.

- `text` → `TextContent`
- external `uri` → `ResourceLink`
- inline image/audio data → `ImageContent` / `AudioContent`
- other binary data → `EmbeddedResource(BlobResourceContents)`
- unsupported content (e.g. `function_call`) is omitted from the tool result

Therefore, the MCP server hosting helper maintains the boundary that “a tool result may contain resource-like blocks, but does not provide a resources server surface”. (Source: [mcp_from_run mapping](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/agent_framework_hosting_mcp/_conversion.py#L65-L155))

## .NET

The `.NET` declarative MCP bridge also converts `CallToolResult.ContentBlock` into a final `AIContent`.

- If it is an error result, a text content `"Error: ..."` is created.
- If it is a normal result, every `ContentBlock` is converted to `ToAIContent()` or a fallback `UriContent` / `TextContent`.

Here too the important point is that it is **one completed result mapping**, not a tool result streaming host. (Source: [PopulateResultContent error path](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative.Mcp/DefaultMcpToolHandler.cs#L305-L343), [ConvertContentBlock](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative.Mcp/DefaultMcpToolHandler.cs#L345-L365))

---

## Auth / error / security

## Python

- the helper does not perform request authentication/authorization.
- when session ids are used, the host must authenticate/authorize.
- MCP tool schema is an app-owned contract, so validation of option types/ranges is also the responsibility of the native MCP schema.
- Invalid argument selection or invalid data URI is surfaced as `ValueError`. (Source: [README auth/session trust](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/README.md#L124-L136), [README schema validation ownership](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/README.md#L53-L56), [mcp_to_run validation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/agent_framework_hosting_mcp/_conversion.py#L44-L62), [mcp_from_run invalid data URI](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/agent_framework_hosting_mcp/_conversion.py#L107-L113))

## .NET

`.NET` `DefaultMcpToolHandler` is more proactive in auth and error handling.

### Per-server auth
- A per-server authenticated `HttpClient` can be injected via `httpClientProvider(serverUrl, ct)`. (Source: [httpClientProvider docs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative.Mcp/DefaultMcpToolHandler.cs#L47-L64))

### Preventing credential leakage
The owned `HttpClient`:
- `UseCookies=false`
- `AllowAutoRedirect=false`
- `OriginPinningHandler`
- `TransportMode = StreamableHttp`

is used. `OriginPinningHandler` removes `Authorization`, `Proxy-Authorization`, and `Cookie` from cross-origin requests. That is, even if a malicious MCP server advertises a cross-origin SSE endpoint or redirect, credentials are prevented from going to the new origin. (Source: [handler creation with safe defaults](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative.Mcp/DefaultMcpToolHandler.cs#L206-L247), [OriginPinningHandler docs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative.Mcp/DefaultMcpToolHandler.cs#L425-L449), [StripCredentialHeadersOnCrossOrigin](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative.Mcp/DefaultMcpToolHandler.cs#L485-L513))

### Error mapping
- If `CallToolResult.IsError == true`, text is collected from the content blocks and converted to a `TextContent` in the form `"Error: ..."`.
- A non-error but unknown content block becomes a fallback `TextContent(block.ToString())`. (Source: [PopulateResultContent error mapping](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative.Mcp/DefaultMcpToolHandler.cs#L305-L326), [ConvertContentBlock fallback](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative.Mcp/DefaultMcpToolHandler.cs#L345-L364))

---

## .NET / Python differences

1. **Presence of a generic server hosting helper**
   - Python: explicit `AgentMCPTool` / `WorkflowMCPTool` helpers present
   - .NET: no generic server hosting helper; only outbound client bridge and task wrapper exist repo-locally  
   (Source: [Python hosting-mcp __all__](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/agent_framework_hosting_mcp/__init__.py#L16-L22), [.NET IMcpToolHandler only](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative/IMcpToolHandler.cs#L17-L41))

2. **transport ownership**
   - Python: both stdio and streamable HTTP are host app ownership
   - .NET: repo-local implementation holds only the outbound `StreamableHttp` client bridge  
   (Source: [Python README transport ownership](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/README.md#L171-L174), [.NET StreamableHttp transport](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative.Mcp/DefaultMcpToolHandler.cs#L233-L247))

3. **surface area**
   - Python: tool schema derive + run conversion + final result conversion
   - .NET: declarative workflow invoke bridge + client-side task support  
   (Source: [Python AgentMCPTool/WorkflowMCPTool](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/README.md#L58-L96), [.NET TaskAwareMcpClientAIFunction](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Mcp/TaskAwareMcpClientAIFunction.cs#L15-L41))

4. **prompts/resources exposure**
   - Python: tool exposure only, no resources/prompts hosting helper
   - .NET: tool invocation only, only the `tools/list` special-case is present  
   (Source: [Python __all__ tool-only exports](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/agent_framework_hosting_mcp/__init__.py#L16-L22), [.NET ListToolsToolName](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative.Mcp/DefaultMcpToolHandler.cs#L33-L38))

5. **sampling/tasks**
   - Python hosting helper: sampling/tasks app-owned boundary
   - .NET: tasks support exists but is owned by the client package, not the hosting helper  
   (Source: [Python tasks boundary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/README.md#L154-L159), [.NET McpTaskOptions](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Mcp/McpTaskOptions.cs#L7-L39))

---

## .NET implementation and tests

## Implementation
- The `.NET` repo-local MCP-adjacent hosting surface is `IMcpToolHandler` / `DefaultMcpToolHandler`.
- This performs remote MCP server tool invocation in client-bridge form within a declarative workflow.
- It implements `StreamableHttp` enforcement, per-header-hash client caching, origin pinning, the `tools/list` special operation, and `CallToolResult` → `AIContent` mapping. (Source: [DefaultMcpToolHandler summary and fields](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative.Mcp/DefaultMcpToolHandler.cs#L21-L44), [InvokeToolAsync](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative.Mcp/DefaultMcpToolHandler.cs#L66-L102), [CreateClientAsync secure transport](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative.Mcp/DefaultMcpToolHandler.cs#L206-L247))

## Tests
- `McpTaskOptionsTests` validates the sane defaults of the client-side task wrapper. (Source: [McpTaskOptionsTests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Mcp.UnitTests/McpTaskOptionsTests.cs#L7-L18))
- `ListAgentToolsWithTaskSupportTests` validates the task-capable MCP tool wrapping policy. This is client boundary evidence, not a hosting helper. (Source: [ListAgentToolsWithTaskSupportTests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Mcp.UnitTests/ListAgentToolsWithTaskSupportTests.cs#L15-L54))
- `TaskAwareMcpClientAIFunctionTests` validates task-aware invocation, TTL propagation, cancellation -> `tasks/cancel`, and failed task handling. This is also a client-side concern and is used in this document only as a boundary reference. (Source: [TaskAwareMcpClientAIFunctionTests happy path](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Mcp.UnitTests/TaskAwareMcpClientAIFunctionTests.cs#L17-L35), [TTL propagation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Mcp.UnitTests/TaskAwareMcpClientAIFunctionTests.cs#L37-L67), [cancellation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Mcp.UnitTests/TaskAwareMcpClientAIFunctionTests.cs#L69-L117))

---

## Python implementation and tests

## Implementation
- `AgentMCPTool` and `WorkflowMCPTool` are the generic MCP server hosting seam.
- `mcp_to_run()` converts MCP JSON arguments into `AgentRunArgs`.
- `mcp_from_run()` converts an AF response into `ContentBlock[]`.
- The integration test validates that the combination with a native MCP `Server` + `StreamableHTTPSessionManager` actually works. (Source: [Python hosting-mcp __init__](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/agent_framework_hosting_mcp/__init__.py#L1-L22), [AgentMCPTool implementation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/agent_framework_hosting_mcp/_agent_tool.py#L22-L183), [WorkflowMCPTool implementation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/agent_framework_hosting_mcp/_workflow_tool.py#L23-L147), [mcp_from_run conversion](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/agent_framework_hosting_mcp/_conversion.py#L65-L155), [integration test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/tests/hosting_mcp/test_integration.py#L55-L118))

## Tests
- `test_agent_tool.py` validates schema generation, extra parameters, session persistence, and required session parameter semantics. (Source: [agent tool tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/tests/hosting_mcp/test_agent_tool.py#L47-L156))
- `test_workflow_tool.py` validates object/primitive schema derivation, structured output serialization, external input rejection, and multiple input types rejection. (Source: [workflow tool tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/tests/hosting_mcp/test_workflow_tool.py#L47-L131))
- `test_conversion.py` validates text/resource/image/audio/binary mapping and invalid data URI handling. (Source: [conversion tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/tests/hosting_mcp/test_conversion.py#L10-L144))
- `test_integration.py` validates that a hosted agent tool call on an app-owned MCP server + streamable HTTP path actually round-trips over HTTP. (Source: [integration test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/tests/hosting_mcp/test_integration.py#L55-L118))

---

## Documentation differences

The largest difference is the **gap between the scope implied by the name “MCP hosting” and the actual code scope**. The Python package name is `hosting-mcp`, but as the README and AGENTS documents explicitly state, it does not provide a full host and gives only a tool adapter and conversion seam. That is, looking at the package name alone suggests it includes server lifecycle/transport, but in reality it is a deliberately thin adapter. (Source: [Python hosting-mcp README first paragraph](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/README.md#L1-L20), [Python AGENTS boundary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/AGENTS.md#L19-L24))

In contrast, `.NET` has a package named `Microsoft.Agents.AI.Mcp` but does not provide a generic server hosting surface. The actual repo-local code is a client-side task wrapper and a declarative workflow outbound bridge. Therefore, from a code-first perspective, calling it “.NET MCP client/server boundary helpers” is more accurate than “.NET MCP hosting helper”. (Source: [.NET Mcp csproj description](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Mcp/Microsoft.Agents.AI.Mcp.csproj#L19-L22), [.NET IMcpToolHandler contract](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative/IMcpToolHandler.cs#L10-L41))

---

## Java decisions

## Recommended architecture

### 1. `agent-framework-hosting-mcp`
- same role as the Python helper
- `AgentMcpToolAdapter`, `WorkflowMcpToolAdapter`
- `mcpToRun`, `mcpFromRun`
- tool exposure only
- does not include prompts/resources hosting

### 2. `agent-framework-hosting-mcp-spring`
- host binder that composes the MCP SDK `Server` with Spring MVC/WebFlux
- stdio / streamable HTTP is selected by the host profile/launcher
- auth filter, session id trust policy, and concurrency policy are injected by the host

### 3. `agent-framework-mcp-client`
- MCP task-aware client wrapper
- separates client-side protocol concerns such as sampling/tasks
- kept separate from the hosting artifact of this document

## Policy
- The default public boundary should center on a JSON-like schema/result adapter rather than exposing the full vendor SDK types.
- Prompt/resource exposure must be separated into a distinct artifact or distinct interface from tools.
- The streamable HTTP / stdio lifecycle should be owned by the transport launcher, and tool mapping by the hosting adapter, keeping the two separate.

---

## Acceptance scenarios

1. **Tool exposure**
   - A single agent must be exposable as a single MCP `Tool`.
   - A single workflow must be exposable as a single `Tool` based on the start executor input schema.
   - App-owned additional parameters and chat option parameters must be reflected in both the schema and execution without drift.

2. **Transport boundary**
   - The helper must not force stdio/streamable HTTP transport and must be attachable to an app-owned MCP SDK composition.
   - An actual HTTP round-trip must be possible on the streamable HTTP path.

3. **Session**
   - When `session_id_parameter` is used, state must be continuable under the same key with mutable conversation semantics.
   - It must be clear in the documentation and API that concurrency serialization for the same session key is the host's responsibility.

4. **Workflow**
   - Schema derivation must be possible only when the workflow has exactly one input type.
   - A workflow that requires external input must cause the adapter to raise an explicit error rather than a silent success.

5. **Resources/prompts boundary**
   - Resource-like content within a tool result (`ResourceLink`, `EmbeddedResource`) must be supported, while the `resources/*` / `prompts/*` server surface must be separated into a distinct contract.

6. **Sampling/tasks boundary**
   - The hosting helper must not place sampling-only `ToolUseContent` into `CallToolResult.content`.
   - Long-running tasks/progress notifications must remain in app-owned protocol logic or a client-side wrapper, not in the hosting helper.

7. **Security**
   - Authenticated server/client credentials must not leak to cross-origin redirects or server-advertised alternate origins.
   - Session identifiers must not be treated as bearer credentials.

---

## Source inventory

### Docs
- [python/packages/hosting-mcp/README.md](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/README.md#L1-L175)
- [python/packages/hosting-mcp/AGENTS.md](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/AGENTS.md#L1-L54)

### Python production source
- [python/packages/hosting-mcp/agent_framework_hosting_mcp/__init__.py](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/agent_framework_hosting_mcp/__init__.py#L1-L22)
- [python/packages/hosting-mcp/agent_framework_hosting_mcp/_agent_tool.py](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/agent_framework_hosting_mcp/_agent_tool.py#L22-L184)
- [python/packages/hosting-mcp/agent_framework_hosting_mcp/_workflow_tool.py](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/agent_framework_hosting_mcp/_workflow_tool.py#L23-L147)
- [python/packages/hosting-mcp/agent_framework_hosting_mcp/_conversion.py](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/agent_framework_hosting_mcp/_conversion.py#L19-L155)

### Python tests
- [python/packages/hosting-mcp/tests/hosting_mcp/test_agent_tool.py](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/tests/hosting_mcp/test_agent_tool.py#L47-L156)
- [python/packages/hosting-mcp/tests/hosting_mcp/test_workflow_tool.py](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/tests/hosting_mcp/test_workflow_tool.py#L47-L131)
- [python/packages/hosting-mcp/tests/hosting_mcp/test_conversion.py](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/tests/hosting_mcp/test_conversion.py#L10-L144)
- [python/packages/hosting-mcp/tests/hosting_mcp/test_integration.py](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/tests/hosting_mcp/test_integration.py#L55-L118)

### .NET production source
- [dotnet/src/Microsoft.Agents.AI.Mcp/Microsoft.Agents.AI.Mcp.csproj](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Mcp/Microsoft.Agents.AI.Mcp.csproj#L1-L39)
- [dotnet/src/Microsoft.Agents.AI.Mcp/McpTaskOptions.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Mcp/McpTaskOptions.cs#L7-L39)
- [dotnet/src/Microsoft.Agents.AI.Mcp/McpClientTaskExtensions.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Mcp/McpClientTaskExtensions.cs#L13-L60)
- [dotnet/src/Microsoft.Agents.AI.Mcp/TaskAwareMcpClientAIFunction.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Mcp/TaskAwareMcpClientAIFunction.cs#L15-L147)
- [dotnet/src/Microsoft.Agents.AI.Workflows.Declarative/IMcpToolHandler.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative/IMcpToolHandler.cs#L10-L41)
- [dotnet/src/Microsoft.Agents.AI.Workflows.Declarative.Mcp/DefaultMcpToolHandler.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative.Mcp/DefaultMcpToolHandler.cs#L21-L514)

### .NET tests
- [dotnet/tests/Microsoft.Agents.AI.Mcp.UnitTests/McpTaskOptionsTests.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Mcp.UnitTests/McpTaskOptionsTests.cs#L7-L18)
- [dotnet/tests/Microsoft.Agents.AI.Mcp.UnitTests/ListAgentToolsWithTaskSupportTests.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Mcp.UnitTests/ListAgentToolsWithTaskSupportTests.cs#L15-L54)
- [dotnet/tests/Microsoft.Agents.AI.Mcp.UnitTests/TaskAwareMcpClientAIFunctionTests.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Mcp.UnitTests/TaskAwareMcpClientAIFunctionTests.cs#L17-L159)

## Conclusion

Repo-local MCP hosting is not a model of “the framework owns the entire MCP server”, but a model that separates the **tool exposure seam** and the **outbound client bridge**. Python provides a refined `Tool` adapter and conversion helper that attaches to an app-owned MCP server, while `.NET` implements the remote MCP invocation and long-running task client boundary more strongly, instead of a generic server host. For Java, it is most natural to follow this by separating the **tool-centric hosting adapter**, the **transport-aware host binder**, and the **client-side task/sampling artifact**. (Source: [Python hosting-mcp boundary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/README.md#L18-L20), [.NET DefaultMcpToolHandler streamable HTTP + pinning](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative.Mcp/DefaultMcpToolHandler.cs#L206-L247), [Python integration over StreamableHTTPSessionManager](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/tests/hosting_mcp/test_integration.py#L76-L118))