# 23. AG-UI

## Scope

This document covers only the **AG-UI adapter and hosting** of Microsoft Agent Framework. The targets are as follows.

- Mapping of AG-UI requests/events to Agent Framework run/update
- AG-UI server endpoint/adapter/hosting surface
- state snapshot / predictive delta / deterministic state update
- tool call / tool result / approval interrupt and UI payload boundary
- streaming transport, SSE framing, keepalive
- session continuity and identity boundary
- .NET / Python implementation differences
- conformance tests
- Java design decisions

Conversely, generic hosting lifecycle and general session store abstraction are within the scope of a separate generic hosting document; this document describes only AG-UI-specific request/event/state semantics and hosting contact points. Additionally, details of OpenAI Responses, A2A, MCP, Foundry/DevUI, and Telegram/ChatKit are each within the scope of separate documents. (Source: [Python AG-UI README](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/README.md#L1-L3), [Python endpoint helper scope](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_endpoint.py#L95-L119), [.NET AG-UI endpoint remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore/AGUIEndpointRouteBuilderExtensions.cs#L27-L32))

## Summary

AG-UI support has a different scope of coverage across the two languages. `.NET` focuses primarily on the **ASP.NET Core server hosting layer**, delegates the AG-UI event conversion pipeline itself to the external AG-UI .NET SDK, and the Agent Framework side adds only session persistence, JSON resolver chain, SSE result, and isolation-scoped session keying. Python is broader, self-implementing all the way to **server endpoint**, **client adapter**, **event converter**, **predictive state delta**, **deterministic tool-result state snapshot**, and **thread snapshot persistence**. The AG-UI request model includes `state`, `tools`, `context`, `forwarded_props`, `availableInterrupts`, `resume`, `threadId`, `runId`, and `parentRunId` in addition to simple messages, and Python models these directly as `AGUIRequest`. State is handled in two layers: `predict_state_config` produces an optimistic `StateDeltaEvent` from streaming tool args, and `state_update(...)` produces a deterministic `StateSnapshotEvent` based on actual results after tool execution. Tool results can also separate LLM-bound text from UI-bound display payload, with `ToolCallResultEvent.content` carrying the UI payload and the internal function result retaining the LLM text. At the session/identity boundary, since the AG-UI `threadId` is not an authorization boundary, .NET recommends `IsolationKeyScopedAgentSessionStore`, and Python mandatorily enforces `snapshot_scope_resolver` when snapshot persistence is used. (Source: [.NET AG-UI endpoint remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore/AGUIEndpointRouteBuilderExtensions.cs#L27-L32), [.NET session trust model for ThreadId](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore/AGUIEndpointRouteBuilderExtensions.cs#L79-L103), [Python AGUIRequest fields](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_types.py#L94-L141), [Python AGUI client/server exports](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/__init__.py#L7-L56), [PredictiveStateHandler](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_predictive_state.py#L19-L233), [state_update helper](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_state.py#L43-L137), [tool result snapshot emission](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_run_common.py#L713-L795), [Python snapshot scope guard](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_endpoint.py#L54-L74))

---

## Purpose

The purpose of AG-UI support is to connect the Agent Framework target to a **UI-friendly event protocol**. This purpose is broader than simple chat text streaming.

- Converts the request shape sent by the AG-UI client into run-ready input.
- Converts text, tool calls, tool results, steps, interrupts, and state changes that occur during agent/workflow execution into an AG-UI event stream.
- Maintains multi-turn thread continuity.
- Shares state changes before and after tool execution with the UI.
- Expresses human-in-the-loop states such as workflow or approval waits using the canonical interrupt model.

The Python README describes AG-UI as a web interface and streaming protocol integration, and the .NET comments state that “the AG-UI SDK provides `ChatResponseUpdate` → AG-UI event stream conversion, and the Agent Framework layer only adds hosting concerns.” (Source: [Python AG-UI README summary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/README.md#L1-L3), [.NET AG-UI endpoint remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore/AGUIEndpointRouteBuilderExtensions.cs#L27-L32))

## Boundary

### What this feature does
- Parses and normalizes the AG-UI request payload into target run input.
- Surfaces AG-UI wire events as `ChatResponseUpdate` or a raw UI event stream.
- Surfaces tool call/result, state snapshot/delta, and interrupt/resume in accordance with the AG-UI model.
- Provides keys and snapshot store hooks for thread/session continuity.
- Separates UI-only display payload from LLM-visible tool result text. (Source: [Python event converter](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_event_converters.py#L18-L97), [state_update design](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_state.py#L49-L119), [.NET MapAGUIServer session persistence path](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore/AGUIEndpointRouteBuilderExtensions.cs#L142-L188))

### What this feature does not do
- Making `threadId` itself a trustworthy identity
- host-wide authn/authz
- Managing generic session store lifecycle
- protocol-neutral checkpoint abstraction
- external deployment/runtime orchestration

The Python endpoint requires the caller to provide auth dependencies, and makes it impossible to enable snapshot persistence without an app-defined scope resolver. .NET also documents that using `ThreadId` as a bare persistent key is only safe for single-user/prototyping scenarios. (Source: [Python endpoint dependencies and snapshot scope requirement](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_endpoint.py#L95-L119), [Python snapshot scope fail-fast](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_endpoint.py#L54-L74), [.NET ThreadId trust model](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore/AGUIEndpointRouteBuilderExtensions.cs#L79-L103))

---

## Maturity

| Area | .NET | Python |
|---|---|---|
| AG-UI server hosting | `Microsoft.Agents.AI.Hosting.AGUI.AspNetCore` = `preview` | `agent-framework-ag-ui` = `released` |
| AG-UI client adapter | no first-party implementation within repo scope (uses AGUI SDK) | includes `AGUIChatClient` |
| Snapshot/state tooling | verified with server integration tests | includes first-party implementation in package |

Source:
- [.NET Hosting.AGUI.AspNetCore csproj](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore.csproj#L13-L17)
- [Python PACKAGE_STATUS released ag-ui](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L17-L20)
- [Python ag-ui pyproject stable classifier](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/pyproject.toml#L13-L23)

---

## Public APIs and types

## .NET
- `AddAGUIServer(this IServiceCollection)`
- `MapAGUIServer(this IEndpointRouteBuilder, ...)`
- `AGUIServerSentEventsResult` (pre-.NET 10 SSE polyfill)
- Resolver-chain configuration via `ConfigureAGUIJsonOptions`  
  (Source: [.NET AddAGUIServer](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore/AGUIServerServiceCollectionExtensions.cs#L12-L29), [.NET MapAGUIServer overloads](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore/AGUIEndpointRouteBuilderExtensions.cs#L33-L173), [.NET AGUI SSE polyfill](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore/AGUIServerSentEventsResult.cs#L19-L135), [.NET ConfigureAGUIJsonOptions](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore/ConfigureAGUIJsonOptions.cs#L9-L25))

## Python
- `AgentFrameworkAgent`
- `AgentFrameworkWorkflow`
- `WorkflowFactory`
- `add_agent_framework_fastapi_endpoint`
- `AGUIChatClient`
- `AGUIHttpService`
- `AGUIEventConverter`
- `AGUIRequest`
- snapshot store types (`AGUIThreadSnapshotStore`, `InMemoryAGUIThreadSnapshotStore`, `SnapshotScope`, `SnapshotScopeResolver`)
- `state_update`  
  (Source: [Python public exports](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/__init__.py#L7-L56))

## Request model
Python defines the request model directly within the package. The main fields are as follows.

- `messages`
- `run_id` / `runId`
- `thread_id` / `threadId`
- `state`
- `tools`
- `context`
- `forwarded_props` / `forwardedProps`
- `parent_run_id` / `parentRunId`
- `available_interrupts`
- `resume`  
  (Source: [AGUIRequest fields and aliases](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_types.py#L94-L141))

---

## Request mapping

## Python request mapping

### AGUIRequest parsing
`AGUIRequest` accepts both camelCase and snake_case.

- `runId` ↔ `run_id`
- `threadId` ↔ `thread_id`
- `forwardedProps` ↔ `forwarded_props`
- `parentRunId` ↔ `parent_run_id`
- `availableInterrupts` ↔ `available_interrupts`

`resume` normalizes legacy shapes to canonical `ResumeEntry[]`. It also accepts the forms of a single object, `{interrupts:[...]}`, and `{interrupt:[...]}`. (Source: [legacy resume coercion](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_types.py#L28-L69), [AGUIRequest aliases and resume field](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_types.py#L94-L141))

### Endpoint-side normalization
`add_agent_framework_fastapi_endpoint` unpacks the request body with `model_dump(exclude_none=True)` and:

- inserts optional snapshot scope, and
- merges default state or stores it as deferred default state, and
- passes `input_data` to the runner as-is.

That is, request-level normalization and transport concerns (keepalive, SSE headers) are handled by the endpoint layer. (Source: [endpoint request handling](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_endpoint.py#L145-L179), [snapshot/default state logic](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_endpoint.py#L153-L170))

## .NET request mapping

The `.NET` repo-local implementation does not directly redefine the AG-UI request body model. The server endpoint receives `RunAgentInput` from the AG-UI SDK, converts it via `input.ToChatRequestContext(jsonSerializerOptions, streamOptions)`, and then uses `ctx.Messages` and `ctx.ChatOptions`. That is, the core of request mapping lies in the AG-UI SDK, and the Agent Framework layer only adds:

- keyed agent resolution
- threadId defaulting
- session load/save
- `AIHostAgent.RunStreamingAsync(...)` call  
as the sole addition. (Source: [.NET endpoint mapping body handling](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore/AGUIEndpointRouteBuilderExtensions.cs#L125-L170))

---

## Event mapping

## Python: AGUI event → Agent Framework update

`AGUIEventConverter` converts AG-UI event dicts to `ChatResponseUpdate`-style updates. The representative supported events are as follows.

- `RUN_STARTED`
- `TEXT_MESSAGE_START`
- `TEXT_MESSAGE_CONTENT`
- `TEXT_MESSAGE_END`
- `TOOL_CALL_START`
- `TOOL_CALL_ARGS`
- `TOOL_CALL_END`
- `TOOL_CALL_RESULT`
- `RUN_FINISHED`
- `RUN_ERROR`
- `CUSTOM` / `CUSTOM_EVENT`

`RUN_STARTED` stores `thread_id` and `run_id` in `additional_properties`; `TEXT_MESSAGE_CONTENT` converts to `Content.from_text(delta)`; `TOOL_CALL_START/ARGS` converts to `Content.from_function_call(...)`; `TOOL_CALL_RESULT` converts to `role="tool"` + `Content.from_function_result(...)`. `RUN_FINISHED` preserves `outcome.interrupts` or a legacy `interrupt` payload as metadata, and `RUN_ERROR` surfaces via `Content.from_error(...)`. (Source: [AGUIEventConverter convert_event switch](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_event_converters.py#L44-L97), [RUN_STARTED mapping](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_event_converters.py#L99-L111), [text message mappings](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_event_converters.py#L113-L138), [tool call mappings](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_event_converters.py#L140-L211), [RUN_FINISHED and RUN_ERROR mapping](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_event_converters.py#L213-L278))

## .NET: Agent Framework update → AG-UI event

The `.NET` repo-internal implementation does not directly contain AG-UI event generation itself. According to the comments, this pipeline is handled by `ChatResponseUpdateAGUIExtensions.AsAGUIEventStreamAsync` from the public AG-UI .NET SDK, and the Agent Framework layer mounts this on top of `AIHostAgent` and `AgentSessionStore`. Consequently, what can be directly confirmed from the repo-local source is the **hosting layer that exposes the AG-UI SDK event stream as SSE** and the surrounding state/persistence semantics. (Source: [.NET endpoint remarks on AG-UI SDK pipeline](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore/AGUIEndpointRouteBuilderExtensions.cs#L27-L32), [.NET endpoint stream path](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore/AGUIEndpointRouteBuilderExtensions.cs#L148-L170))

---

## State snapshot / delta

## Python: predictive delta

`PredictiveStateHandler` observes streaming tool args based on `predict_state_config` and produces `StateDeltaEvent`.

- partial JSON chunks extract partial values using regex
- when JSON becomes complete, values are extracted from fully parsed args
- duplicate values are not re-emitted
- pending updates are applied to `current_state` later

This is the **optimistic path that inspects the tool arguments the LLM intends to call and proactively predicts and changes UI state**. (Source: [PredictiveStateHandler overview](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_predictive_state.py#L19-L39), [extract_state_value](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_predictive_state.py#L45-L76), [emit_streaming_deltas](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_predictive_state.py#L93-L129), [partial and complete delta emission](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_predictive_state.py#L131-L194), [StateDeltaEvent construction](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_predictive_state.py#L196-L226))

## Python: deterministic snapshot

`state_update(...)` is a `Content` helper returned after a tool is actually executed. This function loads reserved keys:

- `__ag_ui_tool_result_state__`
- `__ag_ui_tool_result_display__`

into `additional_properties`. Thereafter, `_emit_tool_result_common(...)`:

1. `ToolCallEndEvent`
2. `ToolCallResultEvent`
3. predictive pending updates apply
4. deterministic state merge
5. one coalesced `StateSnapshotEvent`

emits in this order. That is, **predictive delta is optimistic**, and **the state_update snapshot is authoritative**. (Source: [state_update reserved keys and semantics](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_state.py#L26-L35), [state_update function](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_state.py#L43-L137), [_extract_tool_result_state](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_run_common.py#L671-L696), [_emit_tool_result_common state merge + StateSnapshotEvent](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_run_common.py#L713-L795))

## .NET: state snapshot visibility

The `.NET` repo-local AG-UI layer does not implement predictive delta or a deterministic state helper. Instead, integration tests pin the fact that **incoming AG-UI thread state enters via `RunAgentInput.State` and the `StateSnapshotEvent` emitted by the server surfaces as `ChatResponseUpdate.RawRepresentation`**. That is, by repo standards, the role of the .NET layer is **preserving raw AG-UI state events** rather than generating new state events. (Source: [.NET SharedStateTests summary comments](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore.IntegrationTests/SharedStateTests.cs#L30-L42), [.NET state snapshot assertions](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore.IntegrationTests/SharedStateTests.cs#L47-L62), [RawRepresentation preservation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore.IntegrationTests/SharedStateTests.cs#L82-L92))

---

## Tool calls

## Python

### Tool call events
`_agent_run.py` and `_run_common.py` divide the tool call flow into an AG-UI event sequence.

- `ToolCallStartEvent`
- `ToolCallArgsEvent`
- `ToolCallEndEvent`
- `ToolCallResultEvent`

The important point here is that **the UI payload and LLM payload of the tool result are separated**.

- For the LLM, the `raw_result` string is retained as the function result
- For the UI, if `display_result` is present, it is displayed as `ToolCallResultEvent.content`
- `state_update(..., tool_result=...)` activates this path. (Source: [_resolve_ui_payload](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_run_common.py#L704-L710), [_emit_tool_result_common tool result event](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_run_common.py#L741-L765), [state_update tool_result docs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_state.py#L57-L67), [same file example](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_state.py#L80-L100))

### Approval/HITL
The AG-UI interrupt model uses the canonical `Interrupt` / `ResumeEntry` as-is. Tool approval interrupts belong to the `reason: "tool_call"` family, and workflow `request_info` interrupts have `reason: "input_required"`. `AGUIRequest.available_interrupts` and `resume` accept this canonical model directly. (Source: [Python README interrupts and resume](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/README.md#L142-L152), [AGUIRequest available_interrupts and resume](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_types.py#L133-L141))

### Workflow-side tool/result replay
The `_WorkflowSnapshotBuilder` inside `AgentFrameworkWorkflow` compacts `ToolCallStartEvent`, `ToolCallArgsEvent`, and `ToolCallResultEvent` into a replayable message snapshot. When a tool result arrives, it closes the current tool-call group to distinguish it from subsequent tool calls. (Source: [Workflow snapshot builder observe tool calls](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_workflow.py#L122-L178))

## .NET

The `.NET` repo-local implementation does not directly regenerate tool call events produced by the AG-UI SDK. However, integration tests pin the fact that both server-side tools and client-side tools surface as `FunctionCallContent` / `FunctionResultContent`. That is, from the AF-hosting layer perspective, the direct responsibility of the tool call path is not “event generation” but rather **ensuring that streaming transport, session continuity, and tool execution orchestration operate correctly together with the AGUI SDK+Agent stack**. (Source: [.NET ToolCallingTests server tool](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore.IntegrationTests/ToolCallingTests.cs#L33-L71), [.NET ToolCallingTests client tool](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore.IntegrationTests/ToolCallingTests.cs#L122-L159), [.NET simultaneous server and client tools](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore.IntegrationTests/ToolCallingTests.cs#L211-L260))

---

## Streaming

## .NET

### SSE transport
The AG-UI ASP.NET layer uses the framework's `TypedResults.ServerSentEvents` for .NET 10 and above, and the `AGUIServerSentEventsResult` polyfill for earlier versions. The polyfill:

- `Content-Type: text/event-stream`
- `Cache-Control: no-cache,no-store`
- `Pragma: no-cache`
- SSE framing

sets these, and if an exception occurs during streaming, sends a generic `RunErrorEvent` instead of the raw exception while keeping the full exception only in the server log. (Source: [.NET endpoint selection of SSE path](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore/AGUIEndpointRouteBuilderExtensions.cs#L162-L171), [.NET AGUIServerSentEventsResult headers](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore/AGUIServerSentEventsResult.cs#L37-L55), [.NET generic RunErrorEvent emission](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore/AGUIServerSentEventsResult.cs#L56-L79))

### Session save after stream
The `.NET` endpoint wraps the AG-UI event stream and calls `hostAgent.SaveSessionAsync(threadId, session)` after enumeration completes. That is, session persistence is tied to stream completion. (Source: [.NET SaveSessionAfterStreamingAsync](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore/AGUIEndpointRouteBuilderExtensions.cs#L175-L188))

## Python

### Endpoint SSE
The Python FastAPI endpoint encodes AG-UI events to SSE strings/bytes using `EventEncoder`. If `keepalive_seconds` is set, it uses the ping comment from `sse-starlette`; otherwise, it uses a plain `StreamingResponse`. The headers are `Cache-Control: no-cache`, `Connection: keep-alive`, and `X-Accel-Buffering: no`. (Source: [Python endpoint event_generator and headers](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_endpoint.py#L185-L253))

### Keepalive ownership
Keepalive is an endpoint transport setting, not a runner setting. The default is 15 seconds, and the docstring explicitly states “these are SSE comments and do not alter AG-UI events.” Tests also pin that `keepalive_seconds` is an endpoint-owned option. (Source: [endpoint signature and docs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_endpoint.py#L81-L119), [keepalive tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/tests/ag_ui/test_endpoint.py#L154-L213))

### HTTP round-trip fidelity
Python `test_http_round_trip.py` pins text chat, tool calls, workflow, and headers through POST → SSE bytes → parsed event sequence verification. (Source: [HTTP round-trip tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/tests/ag_ui/test_http_round_trip.py#L48-L77), [tool call round-trip](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/tests/ag_ui/test_http_round_trip.py#L80-L114), [workflow SSE round-trip](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/tests/ag_ui/test_http_round_trip.py#L166-L186), [SSE headers](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/tests/ag_ui/test_http_round_trip.py#L206-L217))

---

## Errors, validation, and security

## Python

### Endpoint-level errors
- If an exception occurs during runner streaming, a generic `RunErrorEvent` is encoded and sent over the stream.
- Exceptions at the endpoint outer layer are converted to HTTP 500 `An internal error has occurred.` (Source: [event_generator stream error -> RunErrorEvent](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_endpoint.py#L221-L231), [outer endpoint 500](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_endpoint.py#L254-L256))

### Resume / interrupt validation
Legacy resume payloads undergo normalization, and invalid status or cancelled workflow resume is converted to a `RunErrorEvent` family response. When a pending request is cancelled, the corresponding pending request is removed from the runner context. (Source: [resume coercion helpers](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_types.py#L28-L69), [workflow resume error helpers](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_workflow_run.py#L270-L303))

### Identity / authorization boundary
- `threadId` is not an authorization boundary.
- When snapshot persistence is enabled, `snapshot_scope_resolver` is enforced.
- The default state/snapshot/workflow_factory cache is also partitioned by `snapshot_scope`.  
  (Source: [snapshot scope requirement](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_endpoint.py#L54-L74), [endpoint doc on snapshot scope](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_endpoint.py#L111-L119), [workflow cache keyed by (snapshot_scope, thread_id)](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_workflow.py#L206-L215))

## .NET

### ThreadId trust boundary
The `.NET` AG-UI hosting comments explicitly state that `RunAgentInput.ThreadId` comes from the wire and is not an authorization token. A multi-user host with a persistent store must use `IsolationKeyScopedAgentSessionStore` or a custom `AgentIsolationKeyProvider`. (Source: [.NET ThreadId trust model](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore/AGUIEndpointRouteBuilderExtensions.cs#L79-L103))

### Stream failure privacy
The SSE polyfill does not expose the raw message of a streaming exception to the client and sends only a generic `RunErrorEvent`. This is a security choice to hide internal details. (Source: [.NET AGUIServerSentEventsResult generic error message](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore/AGUIServerSentEventsResult.cs#L56-L79))

---

## Session / identity boundary

## .NET

- The AG-UI continuation key is `threadId`
- If the client does not send a threadId, the server generates a GUID and writes it back into the request
- If a session store is present, sessions are loaded/saved under `threadId`
- If an isolation provider is present, a scoped session key is applied  
  (Source: [.NET threadId generation and session lookup](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore/AGUIEndpointRouteBuilderExtensions.cs#L142-L160), [.NET isolation wrapper application](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore/AGUIEndpointRouteBuilderExtensions.cs#L113-L123))

The integration test verifies the persistence round-trip by checking whether the second turn in a multi-turn flow with an in-memory session store returns `"Turn 2"`. (Source: [.NET SessionPersistenceTests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore.IntegrationTests/SessionPersistenceTests.cs#L29-L93))

## Python

Python has two layers of session identity.

1. **transport thread identity**: `threadId`
2. **snapshot authorization boundary**: `snapshot_scope`

The workflow wrapper keys even the `workflow_factory` instance cache by `(snapshot_scope, thread_id)`. Therefore, even with the same `threadId`, mutable workflow state is not shared if the scope differs. (Source: [workflow cache key comments](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_workflow.py#L206-L215))

Furthermore, the snapshot builder of `AgentFrameworkWorkflow` compactly stores replayable messages/state/interrupt as an AGUI thread snapshot. This enables thread replay without retaining the raw event log intact. (Source: [Workflow snapshot builder](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_workflow.py#L50-L106))

---

## .NET/Python differences

1. **Scope of coverage**
   - .NET: centered on server hosting, event conversion depends on AGUI SDK
   - Python: first-party implementation covering server + client + event converter + predictive state + snapshot store  
   (Source: [.NET endpoint remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore/AGUIEndpointRouteBuilderExtensions.cs#L27-L32), [Python public exports](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/__init__.py#L7-L56))

2. **request modeling**
   - .NET repo-local: AGUI SDK `RunAgentInput`
   - Python: in-repo `AGUIRequest` with aliasing/legacy resume coercion  
   (Source: [.NET endpoint body handling](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore/AGUIEndpointRouteBuilderExtensions.cs#L125-L140), [Python AGUIRequest](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_types.py#L94-L141))

3. **state support depth**
   - .NET repo-local: raw `StateSnapshotEvent` preservation and session persistence verification
   - Python: predictive `StateDeltaEvent` + deterministic `StateSnapshotEvent` + UI display payload split  
   (Source: [.NET SharedStateTests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore.IntegrationTests/SharedStateTests.cs#L30-L62), [Python PredictiveStateHandler](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_predictive_state.py#L19-L233), [Python state_update](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_state.py#L43-L137))

4. **tool result handling**
   - .NET tests confirm server/client tools both surface through AG-UI stream, but repo-local logic lives mostly outside this repo
   - Python repo-local implementation explicitly separates LLM result text and UI display payload  
   (Source: [.NET ToolCallingTests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore.IntegrationTests/ToolCallingTests.cs#L33-L260), [Python _emit_tool_result_common](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_run_common.py#L713-L795))

5. **identity boundary enforcement**
   - .NET: `ThreadId` untrusted + isolation-scoped session store recommended
   - Python: `snapshot_scope_resolver` mandatory when persistence active  
   (Source: [.NET trust model](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore/AGUIEndpointRouteBuilderExtensions.cs#L79-L103), [Python snapshot scope requirement](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_endpoint.py#L54-L74))

---

## .NET implementation and tests

## Implementation
- `AddAGUIServer()` chains the Agent Framework abstractions resolver and the AG-UI SDK resolver into ASP.NET `JsonOptions`. (Source: [.NET AddAGUIServer](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore/AGUIServerServiceCollectionExtensions.cs#L17-L29), [.NET ConfigureAGUIJsonOptions](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore/ConfigureAGUIJsonOptions.cs#L15-L25))
- `MapAGUIServer(...)` performs keyed agent resolution, optional session store wrapping, `threadId` generation, the `AIHostAgent.RunStreamingAsync` call, and stream-completion session save. (Source: [.NET MapAGUIServer](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore/AGUIEndpointRouteBuilderExtensions.cs#L71-L188))
- For pre-.NET 10 targets, `AGUIServerSentEventsResult` serves as the SSE polyfill. (Source: [.NET AGUI SSE polyfill](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore/AGUIServerSentEventsResult.cs#L19-L135))

## Tests
- Endpoint unit tests verify:
  - keyed agent resolve
  - session store resolve/fallback
  - null argument validation  
  are verified. (Source: [.NET AGUIEndpointRouteBuilderExtensionsTests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore.UnitTests/AGUIEndpointRouteBuilderExtensionsTests.cs#L21-L161))
- JSON option tests verify that the resolver chain can interpret both AG-UI wire types and Agent Framework types. (Source: [.NET ConfigureAGUIJsonOptionsTests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore.UnitTests/ConfigureAGUIJsonOptionsTests.cs#L17-L46))
- SSE result tests verify headers, framing, flush, and cancellation. (Source: [.NET AGUIServerSentEventsResultTests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore.UnitTests/AGUIServerSentEventsResultTests.cs#L23-L149))
- Integration tests verify:
  - basic streamed assistant text
  - run lifecycle ids
  - session persistence across requests
  - shared state snapshot raw representation
  - server/client tool calling  
  are verified. (Source: [.NET BasicStreamingTests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore.IntegrationTests/BasicStreamingTests.cs#L29-L217), [.NET SessionPersistenceTests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore.IntegrationTests/SessionPersistenceTests.cs#L29-L146), [.NET SharedStateTests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore.IntegrationTests/SharedStateTests.cs#L30-L238), [.NET ToolCallingTests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore.IntegrationTests/ToolCallingTests.cs#L33-L260))

---

## Python implementation and tests

## Implementation
- `AGUIRequest` defines the request wire shape.
- `add_agent_framework_fastapi_endpoint` creates the FastAPI route and SSE transport.
- `AgentFrameworkAgent` and `AgentFrameworkWorkflow` serve as target wrappers.
- `AGUIEventConverter` converts AG-UI events to AF `ChatResponseUpdate`.
- `PredictiveStateHandler` and `state_update()` implement state delta/snapshot semantics.
- `_WorkflowSnapshotBuilder` creates a replayable thread snapshot.  
  (Source: [AGUIRequest](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_types.py#L94-L141), [FastAPI endpoint helper](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_endpoint.py#L81-L256), [AgentFrameworkAgent](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_agent.py#L76-L156), [AgentFrameworkWorkflow](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_workflow.py#L181-L220), [AGUIEventConverter](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_event_converters.py#L18-L278), [PredictiveStateHandler](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_predictive_state.py#L19-L233), [Workflow snapshot builder](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_workflow.py#L50-L106))

## Tests
- Endpoint tests verify raw agent / wrapped agent / workflow / keepalive option / SSE header behavior. (Source: [Python endpoint tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/tests/ag_ui/test_endpoint.py#L99-L204))
- Event converter tests verify run started, text deltas, tool call args/results, and run finished interrupt metadata. (Source: [Python event converter tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/tests/ag_ui/test_event_converters.py#L17-L240))
- Predictive state tests verify delta generation from partial/complete JSON, deduplication, and pending updates. (Source: [Python predictive state tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/tests/ag_ui/test_predictive_state.py#L10-L200))
- HTTP round-trip tests pin the full SSE pipeline. (Source: [Python HTTP round-trip tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/tests/ag_ui/test_http_round_trip.py#L48-L217))
- Golden tests pin deterministic state snapshots and backend tool scenarios. (Source: [Python golden deterministic state](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/tests/ag_ui/golden/test_scenario_deterministic_state.py#L87-L183), [Python golden agentic chat](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/tests/ag_ui/golden/test_scenario_agentic_chat.py#L53-L139))

---

## Documentation differences

The most significant language difference is **the scope of functionality itself**. The `.NET` README-equivalent source comments clearly state that the AG-UI SDK handles event conversion and the Agent Framework layer only adds hosting concerns. In contrast, the Python README and source implement the client, endpoint, event converter, predictive state, and snapshot persistence within the package. Therefore, even though the name “AG-UI support” is the same, the depth of the repo-local implementation is not symmetric. In code terms:

- `.NET` = hosting wrapper + persistence/integration layer
- Python = full protocol adapter + hosting + client + state tooling

must be recorded as such. (Source: [.NET remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore/AGUIEndpointRouteBuilderExtensions.cs#L27-L32), [Python AG-UI README quick start and client](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/README.md#L11-L107))

Another code-first difference is state semantics. Python explicitly distinguishes `StateDeltaEvent` from `StateSnapshotEvent` and provides a separate deterministic `state_update` path, whereas the `.NET` repo-local implementation does not reimplement such state authoring logic and instead stays on the side of preserving raw AG-UI state events. (Source: [Python state_update doc](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_state.py#L5-L14), [Python PredictiveStateHandler doc](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_predictive_state.py#L19-L27), [.NET SharedStateTests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore.IntegrationTests/SharedStateTests.cs#L30-L42))

---

## Java decisions

## Recommended architecture

### 1. Protocol adapter
- `agent-framework-agui`
- Includes:
  - request DTO / alias handling
  - AG-UI event encoder/decoder
  - tool call/result mapping
  - predictive state and deterministic state snapshot helpers
  - workflow snapshot builder
  - optional client adapter

As the Python implementation already demonstrates, AG-UI has more protocol-level semantics than a simple “host binder,” so it is appropriate to keep the protocol adapter as an independent artifact.

### 2. Host binder
- `agent-framework-agui-spring`
- Includes:
  - Spring MVC/WebFlux endpoint binder
  - SSE/keepalive transport
  - auth dependency injection point
  - principal-derived snapshot scope helper

Like `.NET`, the host binder must focus on transport and persistence integration rather than reimplementing protocol semantics.

### 3. State policy
- `threadId` is not used as a bare persistent key.
- When snapshot persistence is enabled, the host must provide an explicit `SnapshotScopeResolver`.
- Predictive delta and deterministic snapshot are maintained as separate API surfaces.

### 4. Client
- The client adapter can optionally be separated into a distinct artifact (`agent-framework-agui-client`).
- It may also be included in the protocol adapter as in Python, but in the Java ecosystem it is operationally better to separate it from server/runtime dependencies.

---

## Acceptance scenarios

1. **Request compatibility**
   - All aliases such as `runId`/`run_id`, `threadId`/`thread_id`, and `forwardedProps`/`forwarded_props` must be accepted.
   - Legacy resume payloads must be normalized to the canonical interrupt/resume model.

2. **Text streaming**
   - Text responses must satisfy the order `RUN_STARTED` → `TEXT_MESSAGE_START` → one or more `TEXT_MESSAGE_CONTENT` → `TEXT_MESSAGE_END` → `RUN_FINISHED`.
   - SSE framing and headers must conform to event streaming requirements.

3. **Tool call lifecycle**
   - Tool calls must be surfaced with distinct start/args/end/result events.
   - UI-bound tool result payload and LLM-bound text result must be separable.

4. **State**
   - `StateDeltaEvent` must be emittable from predictive tool args streaming.
   - `StateSnapshotEvent` must be emittable after deterministic state merge based on actual tool results.
   - When both are active simultaneously, a single coalesced snapshot must be producible.

5. **Interrupt / resume**
   - Workflow `request_info` or approval pause must be surfaced using the canonical interrupt model.
   - If resume payload validation fails, a run error or fail-fast response must be returned.

6. **Session / identity**
   - If a request arrives without a `threadId`, the host must be able to generate a new thread id.
   - In a multi-user deployment, a principal/snapshot scope dimension separate from `threadId` must be required.
   - Even with the same `threadId`, state must not be shared across different scopes.

7. **Replay / snapshots**
   - Workflow output must be reconstructible as a replayable thread snapshot without raw events.
   - text/tool/result/state/interrupt must be reflected in the snapshot.

---

## Source inventory

### .NET production source
- [dotnet/src/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore.csproj](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore.csproj#L1-L39)
- [dotnet/src/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore/AGUIEndpointRouteBuilderExtensions.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore/AGUIEndpointRouteBuilderExtensions.cs#L24-L189)
- [dotnet/src/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore/AGUIServerServiceCollectionExtensions.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore/AGUIServerServiceCollectionExtensions.cs#L12-L29)
- [dotnet/src/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore/AGUIServerSentEventsResult.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore/AGUIServerSentEventsResult.cs#L19-L135)
- [dotnet/src/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore/ConfigureAGUIJsonOptions.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore/ConfigureAGUIJsonOptions.cs#L9-L25)

### .NET tests
- [dotnet/tests/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore.UnitTests/AGUIEndpointRouteBuilderExtensionsTests.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore.UnitTests/AGUIEndpointRouteBuilderExtensionsTests.cs#L16-L220)
- [dotnet/tests/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore.UnitTests/AGUIServerSentEventsResultTests.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore.UnitTests/AGUIServerSentEventsResultTests.cs#L18-L151)
- [dotnet/tests/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore.UnitTests/ConfigureAGUIJsonOptionsTests.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore.UnitTests/ConfigureAGUIJsonOptionsTests.cs#L12-L46)
- [dotnet/tests/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore.IntegrationTests/BasicStreamingTests.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore.IntegrationTests/BasicStreamingTests.cs#L24-L220)
- [dotnet/tests/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore.IntegrationTests/SessionPersistenceTests.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore.IntegrationTests/SessionPersistenceTests.cs#L24-L146)
- [dotnet/tests/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore.IntegrationTests/SharedStateTests.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore.IntegrationTests/SharedStateTests.cs#L25-L260)
- [dotnet/tests/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore.IntegrationTests/ToolCallingTests.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore.IntegrationTests/ToolCallingTests.cs#L22-L260)

### Python production source
- [python/PACKAGE_STATUS.md](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L17-L20)
- [python/packages/ag-ui/README.md](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/README.md#L1-L180)
- [python/packages/ag-ui/agent_framework_ag_ui/__init__.py](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/__init__.py#L1-L56)
- [python/packages/ag-ui/agent_framework_ag_ui/_types.py](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_types.py#L1-L218)
- [python/packages/ag-ui/agent_framework_ag_ui/_endpoint.py](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_endpoint.py#L35-L256)
- [python/packages/ag-ui/agent_framework_ag_ui/_agent.py](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_agent.py#L18-L156)
- [python/packages/ag-ui/agent_framework_ag_ui/_workflow.py](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_workflow.py#L47-L220)
- [python/packages/ag-ui/agent_framework_ag_ui/_workflow_run.py](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_workflow_run.py#L49-L344)
- [python/packages/ag-ui/agent_framework_ag_ui/_event_converters.py](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_event_converters.py#L18-L278)
- [python/packages/ag-ui/agent_framework_ag_ui/_predictive_state.py](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_predictive_state.py#L19-L233)
- [python/packages/ag-ui/agent_framework_ag_ui/_state.py](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_state.py#L1-L138)
- [python/packages/ag-ui/agent_framework_ag_ui/_run_common.py](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_run_common.py#L671-L795)
- [python/packages/ag-ui/agent_framework_ag_ui/_http_service.py](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_http_service.py#L116-L272)
- [python/packages/ag-ui/agent_framework_ag_ui/_client.py](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_client.py#L123-L213)

### Python tests
- [python/packages/ag-ui/tests/ag_ui/test_endpoint.py](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/tests/ag_ui/test_endpoint.py#L99-L220)
- [python/packages/ag-ui/tests/ag_ui/test_event_converters.py](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/tests/ag_ui/test_event_converters.py#L14-L240)
- [python/packages/ag-ui/tests/ag_ui/test_predictive_state.py](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/tests/ag_ui/test_predictive_state.py#L10-L220)
- [python/packages/ag-ui/tests/ag_ui/test_http_round_trip.py](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/tests/ag_ui/test_http_round_trip.py#L48-L220)
- [python/packages/ag-ui/tests/ag_ui/golden/test_scenario_agentic_chat.py](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/tests/ag_ui/golden/test_scenario_agentic_chat.py#L53-L139)
- [python/packages/ag-ui/tests/ag_ui/golden/test_scenario_deterministic_state.py](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/tests/ag_ui/golden/test_scenario_deterministic_state.py#L87-L183)

## Conclusion

AG-UI is a broader concept than a simple “streaming UI adapter” in Agent Framework, and in particular the Python implementation owns the request model, event model, state model, snapshot model, and tool result display model in their entirety. `.NET`, on the other hand, focuses on hosting integration using the AG-UI SDK and serves the role of stably mounting session persistence, ASP.NET transport, JSON resolver, and isolation boundaries. The Java implementation strikes a balance between these two models, and it is most natural to **separate the protocol adapter and host binder** and treat state snapshot/delta and interrupt/resume as AG-UI-specific first-class concepts. (Source: [.NET AG-UI endpoint remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore/AGUIEndpointRouteBuilderExtensions.cs#L27-L32), [Python AG-UI public exports](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/__init__.py#L7-L56), [Python tool-result state and snapshot logic](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_run_common.py#L713-L795), [Python snapshot scope design](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_workflow.py#L206-L215))