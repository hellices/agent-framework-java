# 20. Hosting

## Scope

This document covers only the **generic hosting lifecycle** of Microsoft Agent Framework, the **separation of hosting model and protocol**, **host composition/lifecycle/scaling responsibilities**, and the **.NET ASP.NET hosting auxiliary layer and the Python app-owned host seam**.  
The detailed wire contracts of OpenAI Responses, A2A, AG-UI, MCP, Foundry/DevUI/channel, and identity/session routing, as well as per-protocol auth/session rules, are covered in separate documents; this document describes only the **interface points and boundaries** with generic hosting.  
The reference source for this document is the production source, tests, and repo docs of pinned commit `d0a4165f170193ba1d026a259af40d35bb7eaefe`. (Source: [ADR-0027](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0027-hosting-channels.md#L50-L64), [Python hosting spec](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/specs/002-python-hosting-channels.md#L15-L24), [Python hosting sample README](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/samples/04-hosting/af-hosting/README.md#L6-L9), [.NET hosting sample README](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/samples/04-hosting/af-hosting/README.md#L9-L20))

## Summary

The core of Agent Framework hosting is the separation of “**protocol-aware helpers/adapters**” from the “**generic hosting core that carries execution state**”. Python implements this boundary most strictly, placing only `AgentState`/`WorkflowState` there and leaving route/auth/storage entirely to the application; .NET follows the same direction but implements it around DI and `AgentSessionStore`/`HostedWorkflowState`. .NET has a separate package with an ASP.NET Core-specific isolation-key helper, but this is also an auxiliary layer that creates a multi-user session namespace, not a full host framework. The host is responsible for the lifecycle of agent/workflow targets, store selection, checkpoint cursor, authentication/authorization, single-writer policy, and multi-replica scaling strategy, while the generic hosting core provides only **target resolution, session snapshotting, and workflow resume** among those. (Source: [ADR-0027](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0027-hosting-channels.md#L74-L103), [ADR-0032](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0032-dotnet-hosting-protocol-helpers.md#L99-L143), [.NET Hosting csproj](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/Microsoft.Agents.AI.Hosting.csproj#L42-L46), [Python AgentState/WorkflowState](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting/agent_framework_hosting/_state.py#L74-L118))

---

## Purpose

The purpose of the hosting core is to **reliably connect a single agent or workflow target to an application-owned server route** without knowing the specific wire protocol, and to make continuation state reusable across multiple requests. Concretely, the following three aspects are central.

1. **target resolution**: reliably resolves agents/workflows in the form of a direct instance, factory, awaitable, or builder.
2. **execution state**: connects agent session snapshots and workflow checkpoint heads across requests.
3. **host seam**: allows the application to own route/auth/background/native clients while combining generic state and protocol adapters.

The Python documentation and implementation explicitly describe this as “protocol helpers plus optional execution state”, and the .NET ADR positions `AgentSessionStore` and `HostedWorkflowState` as a protocol-neutral core in the same direction. (Source: [ADR-0027 decision outcome](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0027-hosting-channels.md#L62-L103), [Python hosting README overview](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting/README.md#L3-L23), [ADR-0032 optional execution state](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0032-dotnet-hosting-protocol-helpers.md#L99-L143))

## Boundary

The hosting scope of this document includes only the following.

- agent/workflow registration and resolution
- session store / checkpoint manager / checkpoint cursor
- interface points with app-owned routes
- DI/service lifetime or callable/awaitable target lifecycle
- separation of operational responsibilities between single-process vs. durable/multi-replica

Conversely, the following are **outside the detailed scope of this document**.

- OpenAI Responses payload structure and SSE event details
- A2A task lifecycle and AG-UI event taxonomy
- MCP tool/result wire schema
- Foundry protocol v2 header contract
- DevUI API and frontend behavior
- Per-channel inbound/outbound message rules for Telegram/ChatKit
- Protocol-specific detailed rules for identity/session key routing

In other words, this document covers only “how protocol adapters use the generic hosting core”, and the wire semantics of each adapter itself are deferred to separate documents. (Source: [ADR-0027 helper families](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0027-hosting-channels.md#L105-L139), [Python hosting spec packages table](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/specs/002-python-hosting-channels.md#L64-L76), [ADR-0032 scope](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0032-dotnet-hosting-protocol-helpers.md#L144-L148))

---

## Separation of hosting model and protocol

### Core distinction

This codebase does not treat **hosting model** and **protocol** as the same thing.

- **hosting model**: how the server creates, injects, and persists targets; which store/checkpoint/cursor it uses; and where auth and scaling are handled
- **protocol**: converting external wire payloads into `Agent.run(...)`/`Workflow.run(...)` values and rendering results back into external formats

The Python ADR defines that protocol packages must have helpers such as `<protocol>_to_run(...)`, `<protocol>_from_run(...)`, `<protocol>_from_streaming_run(...)`, and `<protocol>_session_id(...)`, and mandates that the generic hosting core must carry only the execution-state surface of `AgentState`/`WorkflowState`/`SessionStore`. The .NET ADR also explains that, when adding a public helper for OpenAI Responses, the reason is “to return the routing/auth/storage ownership model to the application”. (Source: [ADR-0027 helper naming and families](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0027-hosting-channels.md#L105-L139), [Python hosting spec helper families](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/specs/002-python-hosting-channels.md#L78-L103), [ADR-0032 context/problem](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0032-dotnet-hosting-protocol-helpers.md#L14-L33), [ADR-0032 decision outcome](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0032-dotnet-hosting-protocol-helpers.md#L72-L98))

### Why this document focuses only on generic hosting

OpenAI Responses, A2A, AG-UI, MCP, Foundry, DevUI, Telegram, and ChatKit are all different protocol/channel layers, but they share the same common points when viewed from the generic hosting perspective.

- whether to **create the target once or on every request**
- whether to read a session as a **copy-on-read snapshot**
- whether to continue a workflow with **restore-then-run**
- whether to keep the store as **in-memory/dev only** or make it **durable**
- whether **the host, not the framework, is responsible for single-writer coordination**

The part responsible for these common aspects is precisely the subject of this document. (Source: [ADR-0027 session continuity](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0027-hosting-channels.md#L171-L238), [Python hosting README workflow state](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting/README.md#L112-L145), [.NET HostedWorkflowState remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/HostedWorkflowState.cs#L18-L39))

---

## Host composition / lifecycle / scaling responsibilities

### What the host must own

Synthesizing the ADR, samples, and README, the host is responsible for the following.

- route declaration and grouping
- dependency injection and target composition
- authentication / authorization
- whether to use a request-derived id as a trusted key
- background work / webhook acknowledgement policy
- response status codes and framework-specific error mapping
- store selection (`in-memory`, file, redis, db, cloud store)
- per-session single-writer policy
- multi-replica continuity and durable checkpoint/session design

This is most explicitly stated in the Python documentation, and the .NET helper-first samples also follow the same assumption. (Source: [ADR-0027 app responsibilities](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0027-hosting-channels.md#L74-L89), [Python hosting README route responsibilities](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting/README.md#L60-L71), [.NET af-hosting README](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/samples/04-hosting/af-hosting/README.md#L3-L20), [Python af-hosting README](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/samples/04-hosting/af-hosting/README.md#L1-L21))

### What the generic hosting core owns

Conversely, the hosting core is responsible only for the following.

- target resolution and optional caching
- session snapshot lookup/set/delete
- maintaining the workflow checkpoint head cursor (.NET `HostedWorkflowState`)
- first-use session creation(Python `AgentState`, .NET store create-on-miss)
- restore-then-run workflow continuation

In other words, the generic hosting core helps with “**how to continue**” but does not determine “**who is permitted to continue**”. (Source: [Python AgentState](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting/agent_framework_hosting/_state.py#L74-L118), [Python AgentState.get_or_create_session](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting/agent_framework_hosting/_state.py#L163-L181), [.NET AgentSessionStore remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/AgentSessionStore.cs#L20-L45), [.NET HostedWorkflowState summary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/HostedWorkflowState.cs#L18-L22))

### Operational rules from a lifecycle and scaling perspective

The Python ADR clearly distinguishes between a persistent single-process and a transient/multi-replica host.

- A persistent single-process may use in-memory state for dev/simple deployment.
- A transient or multi-replica host must not rely on in-memory continuity.
- A workflow host must maintain `CheckpointStorage` and, when necessary, a `session_id -> checkpoint_id` cursor durably.

The .NET `HostedWorkflowState` documentation conveys the same intent, explaining that the default in-memory cursor is only a common-case optimization and that a durable `CheckpointManager` is required for resume after restart. (Source: [ADR-0027 persistent vs transient](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0027-hosting-channels.md#L193-L204), [.NET HostedWorkflowState durable remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/HostedWorkflowState.cs#L24-L39), [Python hosting README no eviction note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting/README.md#L63-L71))

---

## Maturity

| Item | .NET | Python |
|---|---|---|
| Generic hosting core | `Microsoft.Agents.AI.Hosting` = `preview` | `agent-framework-hosting` = `alpha` |
| ASP.NET host seam | `Microsoft.Agents.AI.Hosting.AspNetCore` = `preview` | No separate framework package; helper-first seam |
| Session store feature | public package surface, but store contract is in a stable-ready preview context | core `SessionStore`/`FileSessionStore` is feature-level experimental |

Source:
- [.NET Hosting csproj](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/Microsoft.Agents.AI.Hosting.csproj#L42-L46)
- [.NET Hosting.AspNetCore csproj](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AspNetCore/Microsoft.Agents.AI.Hosting.AspNetCore.csproj#L3-L8)
- [Python PACKAGE_STATUS](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L37-L41)
- [Python experimental SessionStore feature](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L139-L145)

---

## Public APIs and types

## .NET generic hosting

- `HostApplicationBuilder.AddAIAgent(...)`
- `IServiceCollection.AddAIAgent(...)`
- `HostApplicationBuilder.AddWorkflow(...)`
- `AgentSessionStore`
- `AIHostAgent`
- `HostedWorkflowState`

This combination is designed around DI-registered agents/workflows and continuation state. Agents are exposed as keyed service registrations and workflows as keyed `Workflow` registrations. `AIHostAgent` is a store-backed session persistence wrapper, and `HostedWorkflowState` is a workflow resume helper. (Source: [HostApplicationBuilderAgentExtensions](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/HostApplicationBuilderAgentExtensions.cs#L11-L97), [AgentHostingServiceCollectionExtensions](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/AgentHostingServiceCollectionExtensions.cs#L11-L144), [HostApplicationBuilderWorkflowExtensions](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/HostApplicationBuilderWorkflowExtensions.cs#L11-L49), [AIHostAgent](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/AIHostAgent.cs#L10-L72), [HostedWorkflowState](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/HostedWorkflowState.cs#L18-L52))

## .NET ASP.NET hosting seam

- `UseClaimsBasedAgentIsolation(...)`
- `ClaimsIdentityAgentIsolationKeyProvider`
- `ClaimsIdentityAgentIsolationKeyProviderOptions`
- `IsolationKeyScopedAgentSessionStore`

This package is not a full HTTP host but rather **an auxiliary layer that extracts an isolation key from an authenticated principal and scopes the session namespace**. (Source: [ServiceCollectionExtensions](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AspNetCore/ServiceCollectionExtensions.cs#L13-L62), [ClaimsIdentityAgentIsolationKeyProvider](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AspNetCore/ClaimsIdentityAgentIsolationKeyProvider.cs#L13-L95), [IsolationKeyScopedAgentSessionStore](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/IsolationKeyScopedAgentSessionStore.cs#L9-L115))

## Python host seam

The public export of Python `agent-framework-hosting` exposes only the following.

- `AgentRunArgs`
- `AgentState`
- `SupportsBuild`
- `WorkflowRunArgs`
- `WorkflowState`

In other words, this package exports only protocol-neutral execution-state helpers and leaves `SessionStore` in the core package. (Source: [Python hosting __init__](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting/agent_framework_hosting/__init__.py#L1-L27))

---

## Detailed execution flow

## .NET: agent registration and resolution

1. The host registers a keyed `AIAgent` with `AddAIAgent(name, ...)`.
2. DI creates the agent with the configured lifetime.
3. A route or protocol-specific adapter resolves the keyed agent.
4. If session continuity is needed, it loads/saves through `AgentSessionStore`.
5. If needed, a session-backed wrapper can be used with `AIHostAgent`.

The key point is that **there is no separate agent-side holder**, and DI lifetime and `AgentSessionStore` together perform the distributed role that Python's `AgentState` plays. This asymmetry is intentionally explained in ADR-0032. (Source: [AgentHostingServiceCollectionExtensions](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/AgentHostingServiceCollectionExtensions.cs#L25-L144), [AIHostAgent](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/AIHostAgent.cs#L24-L72), [ADR-0032 no agent-side holder](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0032-dotnet-hosting-protocol-helpers.md#L101-L143))

## .NET: workflow registration and restore-then-run

1. The host registers a keyed workflow with `AddWorkflow(name, factory, lifetime)`.
2. The application creates a `HostedWorkflowState` from a workflow instance or workflow factory.
3. When `RunOrResumeAsync(sessionId, input)` is called:
   - it looks up the checkpoint head in the in-memory cursor,
   - falls back to `CheckpointManager.GetLatestCheckpointAsync` if not found,
   - performs a fresh run if still not found,
   - or restores the checkpoint and runs with the new input if found.
4. After the turn ends, it records the new head checkpoint in the cursor.

The resume semantics are **restore-then-run-with-new-input**, not “appending to a halted execution without input”. (Source: [HostApplicationBuilderWorkflowExtensions](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/HostApplicationBuilderWorkflowExtensions.cs#L16-L49), [HostedWorkflowState constructors](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/HostedWorkflowState.cs#L55-L152), [HostedWorkflowState run/resume core](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/HostedWorkflowState.cs#L189-L269))

## .NET ASP.NET seam: principal → isolation key → scoped session id

1. `UseClaimsBasedAgentIsolation(...)` registers a singleton `AgentIsolationKeyProvider`.
2. The provider reads the configured claim from the current `HttpContext.User`.
3. `IsolationKeyScopedAgentSessionStore` appends that key in the format `escapedKey::sessionStoreId` and delegates to the inner store.
4. In strict mode, it fails closed when the key is absent; in non-strict mode, it passes through.

In other words, the ASP.NET helper does not create routes but only **prevents multi-user session collisions**. (Source: [UseClaimsBasedAgentIsolation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AspNetCore/ServiceCollectionExtensions.cs#L15-L62), [ClaimsIdentityAgentIsolationKeyProvider.GetIsolationKeyAsync](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AspNetCore/ClaimsIdentityAgentIsolationKeyProvider.cs#L69-L95), [IsolationKeyScopedAgentSessionStore](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/IsolationKeyScopedAgentSessionStore.cs#L51-L115))

## Python: `AgentState`

1. `AgentState(target, session_store=..., cache_target=...)` accepts an instance/callable/awaitable target.
2. `get_target()` resolves the target via optional caching and a lock.
3. `get_or_create_session(session_id)` performs a store lookup and, if not found, creates one with `target.create_session(session_id=...)`.
4. The created session is saved in the store, and an independent working copy is returned to the caller.
5. After the run completes, the caller must explicitly call `set_session(session_id, session)`.

The key of the Python design is that **session creation resides in the state object, not the store**. (`microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting/agent_framework_hosting/_state.py#L82-L118`, [get_target](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting/agent_framework_hosting/_state.py#L119-L142), [get_or_create_session](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting/agent_framework_hosting/_state.py#L163-L181))

## Python: `WorkflowState`

1. Accepts a direct `Workflow`, a builder with `build() -> Workflow`, a callable, or an awaitable.
2. If `SupportsBuild`, it normalizes via the `build` method.
3. `get_target()` resolves the workflow instance with optional caching.
4. The checkpoint storage and cursor are provided **directly by application code**, not by `WorkflowState`.

For this reason, Python workflow hosting is thinner at the generic helper level, and protocol adapters or route code directly assemble the checkpoint cursor store. (Source: [WorkflowState init](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting/agent_framework_hosting/_state.py#L193-L241), [WorkflowState.get_target](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting/agent_framework_hosting/_state.py#L243-L280), [Python hosting README workflow section](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting/README.md#L112-L145))

---

## State and persistence

## Agent session

### .NET
The generic contract of `.NET` is `AgentSessionStore`. `GetSessionAsync` **may return a new session** if no stored session exists, and at the same time each call must return an **independent instance**. The in-memory implementation stores a serialized `JsonElement` under the `(agentId, sessionStoreId)` key and deserializes it each time to guarantee fresh copy semantics. (Source: [AgentSessionStore contract](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/AgentSessionStore.cs#L64-L106), [InMemoryAgentSessionStore](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/Local/InMemoryAgentSessionStore.cs#L42-L73))

### Python
The Python core `SessionStore` is based on `dict[str, AgentSession]`, and both `get()` and `set()` use `copy.deepcopy` to separate the stored snapshot from the working copy. `FileSessionStore` encodes/decodes a typed envelope to/from JSON or MessagePack files using msgspec, and custom state enables cold-start restore through the `register_state_type()` registry. (Source: [SessionStore](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_sessions.py#L1692-L1767), [FileSessionStore overview](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_sessions.py#L1769-L1797), [FileSessionStore init/get](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_sessions.py#L1806-L1858), [register_state_type](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_sessions.py#L268-L335))

## Workflow checkpoint and snapshot head

### .NET
`HostedWorkflowState` maintains a `CheckpointManager` and an in-memory `sessionId -> CheckpointInfo` cursor. On a cursor miss it reads the manager's latest checkpoint, enabling resume even after a restart. This structure is a 2-tier arrangement of **head cursor + durable checkpoint store**. (Source: [HostedWorkflowState fields/remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/HostedWorkflowState.cs#L18-L39), [cursor miss fallback](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/HostedWorkflowState.cs#L218-L226), [Record/UpdateCursor](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/HostedWorkflowState.cs#L350-L382))

### Python
Python `WorkflowState` does not store checkpoints. The application must directly own `CheckpointStorage` and, when necessary, a `session_id -> checkpoint_id` cursor store. Both the official README and the spec emphasize this point explicitly. (Source: [Python hosting README checkpointing](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting/README.md#L112-L145), [Python hosting spec workflow state](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/specs/002-python-hosting-channels.md#L167-L208))

---

## Extension points

### .NET
- A custom factory can be inserted with `AddAIAgent(..., Func<IServiceProvider,string,AIAgent>)`.
- The target lifecycle can be changed with `ServiceLifetime`.
- A custom `AgentSessionStore` implementation can be injected.
- `HostedWorkflowState` supports three modes: shared instance, per-run factory, and cached factory. (Source: [AddAIAgent custom factory](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/AgentHostingServiceCollectionExtensions.cs#L103-L133), [AddWorkflow custom factory](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/HostApplicationBuilderWorkflowExtensions.cs#L16-L49), [HostedWorkflowState constructors](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/HostedWorkflowState.cs#L55-L152))

### Python
- `AgentState`/`WorkflowState` supports direct instance, sync factory, async factory, and awaitable.
- Per-run target resolution can be forced with `cache_target=False`.
- A custom `SessionStore` and `FileSessionStore(serialization_format="msgpack")` can be used.
- Custom session state objects can extend the registry with `register_state_type()`. (Source: [Python AgentState target forms](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting/agent_framework_hosting/_state.py#L82-L118), [WorkflowState target forms](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting/agent_framework_hosting/_state.py#L201-L241), [FileSessionStore msgpack](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting/README.md#L54-L58), [register_state_type](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_sessions.py#L268-L335))

---

## Concurrency, streaming, and cancellation

## Concurrency

### .NET
- `HostedWorkflowState` documents that two runners cannot run a shared workflow instance concurrently.
- The default value of factory mode (`cacheWorkflow: false`) creates a fresh instance per run for independent session parallelism.
- However, it is explicitly stated that **concurrent turn serialization for the same session id is the host's responsibility**. (Source: [HostedWorkflowState instance remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/HostedWorkflowState.cs#L76-L81), [factory concurrency remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/HostedWorkflowState.cs#L121-L136))

### Python
- `AgentState` serializes the create-on-miss race with an `asyncio.Lock` per `session_id`.
- However, it does not provide conversation-level optimistic concurrency or single-writer coordination. (Source: [AgentState session lock](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting/agent_framework_hosting/_state.py#L173-L181), [Python hosting README concurrency note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting/README.md#L96-L102))

## Streaming

- This generic hosting layer itself does not produce a protocol-level streaming format.
- However, .NET `HostedWorkflowState.RunOrResumeStreamingAsync(...)` maintains restore-then-run semantics for streaming turns and records the last checkpoint in the cursor even if the consumer terminates early.
- Python generic hosting does not provide a streaming transformer; the protocol helper owns the `ResponseStream`. (Source: [.NET streaming resume](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/HostedWorkflowState.cs#L273-L346), [ADR-0027 stream helper family](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0027-hosting-channels.md#L113-L119))

## Cancellation

- The .NET workflow state threads `CancellationToken` through comprehensively, but cached workflow builds use a shared instance, so `CancellationToken.None` is used instead of a request-bound token.
- Python `AgentState`/`WorkflowState` itself has no separate cancellation policy; the underlying target/route/framework handles cancellation. (Source: [.NET cached factory cancellation note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/HostedWorkflowState.cs#L121-L129), [Python state implementation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting/agent_framework_hosting/_state.py#L119-L142))

---

## Errors, validation, and security

## Errors and validation

- Python `SessionStore.validate_session_id()` performs only minimal validation of whether the session id is a non-empty string.
- The .NET `AgentSessionStore` contract requires implementers to treat `sessionStoreId` as opaque and to make no assumptions about parsing or key format.
- `HostedWorkflowState` immediately throws exceptions for null/empty sessionId and null input. (Source: [Python SessionStore.validate_session_id](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_sessions.py#L1711-L1723), [.NET AgentSessionStore implementer guidance](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/AgentSessionStore.cs#L38-L45), [.NET HostedWorkflowState input validation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/HostedWorkflowState.cs#L206-L213))

## Security

- A request-derived session id is a **continuation key, not an authorization token**.
- A multi-user host must always compose the principal dimension into the key.
- The Python ADR stipulates that the result of `<protocol>_session_id(...)` is “a candidate key before becoming a trusted key”.
- The .NET ASP.NET helper sets the default unique claim to `ClaimTypes.NameIdentifier` and warns to the level of prohibition against using mutable/non-unique claims such as display names or aliases. (Source: [ADR-0027 security responsibilities](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0027-hosting-channels.md#L141-L160), [ADR-0027 session key trust boundary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0027-hosting-channels.md#L180-L191), [ClaimsIdentityAgentIsolationKeyProvider security warning](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AspNetCore/ClaimsIdentityAgentIsolationKeyProvider.cs#L24-L34))

---

## .NET implementation and tests

### Implementation highlights
- Agent registration: `AddAIAgent(...)` registers a keyed `AIAgent` service.
- Workflow registration: `AddWorkflow(...)` registers a keyed `Workflow` service.
- session persistence: `AgentSessionStore` + `AIHostAgent`
- workflow resume: `HostedWorkflowState`
- ASP.NET seam: `UseClaimsBasedAgentIsolation(...)` + `ClaimsIdentityAgentIsolationKeyProvider` + `IsolationKeyScopedAgentSessionStore` (Source: [AgentHostingServiceCollectionExtensions](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/AgentHostingServiceCollectionExtensions.cs#L25-L144), [HostApplicationBuilderWorkflowExtensions](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/HostApplicationBuilderWorkflowExtensions.cs#L16-L49), [AIHostAgent](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/AIHostAgent.cs#L24-L72), [ASP.NET ServiceCollectionExtensions](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AspNetCore/ServiceCollectionExtensions.cs#L13-L62))

### Test coverage
- Validates agent registration keyed singleton defaults, null/empty validation, and multi-agent registration. (Source: [HostApplicationBuilderAgentExtensionsTests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.UnitTests/HostApplicationBuilderAgentExtensionsTests.cs#L130-L175))
- Validates workflow registration keyed singleton and the `AddAsAIAgent` bridge. (Source: [HostApplicationBuilderWorkflowExtensionsTests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.UnitTests/HostApplicationBuilderWorkflowExtensionsTests.cs#L66-L104), [same file AddAsAIAgent tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.UnitTests/HostApplicationBuilderWorkflowExtensionsTests.cs#L164-L244))
- `HostedWorkflowState` validates first-turn checkpoint, second-turn resume, pending request non-blocking, cursor-miss durable fallback, and factory/cached factory concurrency. (Source: [HostedWorkflowStateTests first/second turn](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.UnitTests/HostedWorkflowStateTests.cs#L62-L124), [cursor miss fallback](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.UnitTests/HostedWorkflowStateTests.cs#L145-L166), [factory and cached factory tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.UnitTests/HostedWorkflowStateTests.cs#L174-L294))
- ASP.NET isolation provider tests validate default `NameIdentifier`, custom claim, null context, and multi-claim behavior. (Source: [ClaimsIdentityAgentIsolationKeyProviderTests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.UnitTests/ClaimsIdentityAgentIsolationKeyProviderTests.cs#L101-L167), [same file security default claim test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.UnitTests/ClaimsIdentityAgentIsolationKeyProviderTests.cs#L115-L132))
- Scoped session store tests validate strict/non-strict mode and scoped key rewriting. (Source: [IsolationKeyScopedAgentSessionStoreTests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.UnitTests/IsolationKeyScopedAgentSessionStoreTests.cs#L97-L139), [same file save tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.UnitTests/IsolationKeyScopedAgentSessionStoreTests.cs#L163-L220))

---

## Python implementation and tests

### Implementation highlights
- `AgentState` handles target caching and create-on-miss session lifecycle.
- `WorkflowState` handles only workflow/builder/factory resolution.
- `SessionStore`/`FileSessionStore` remain in core, and the hosting package does not re-export them.
- The helper-first hosting sample shows how a FastAPI route composes this state surface. (Source: [AgentState/WorkflowState implementation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting/agent_framework_hosting/_state.py#L74-L280), [Python hosting public exports](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting/agent_framework_hosting/__init__.py#L7-L27), [Python core session store](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_sessions.py#L1692-L1858), [Python af-hosting sample README](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/samples/04-hosting/af-hosting/README.md#L10-L22))

### Test coverage
- Tests fix the fact that the hosting package does not publicly export `SessionStore`. (Source: [test_state export contract](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting/tests/hosting/test_state.py#L105-L121))
- Validates target caching on/off, async callable, bare awaitable, and concurrent callers single-await. (Source: [test_state target resolution tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting/tests/hosting/test_state.py#L123-L188))
- Validates opaque session id passthrough, empty id rejection, and 20 concurrent create-once semantics. (Source: [opaque id pass-through](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting/tests/hosting/test_state.py#L202-L227), [concurrent get_or_create_session](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting/tests/hosting/test_state.py#L237-L254))

---

## Documentation differences

### 1. Discrepancy between the Python spec and the actual public export
The Python hosting spec table reads as if `agent-framework-hosting` also provides `SessionStore`, but the actual public export exposes only `AgentState`/`WorkflowState` and run args and leaves `SessionStore` in the core package. Tests also fix this non-export policy. Therefore, when designing for Java, **it must not be assumed that the execution-state package must also re-export store implementations**.  
(Source: [Python hosting spec packages table](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/specs/002-python-hosting-channels.md#L64-L73), [Python hosting __init__](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting/agent_framework_hosting/__init__.py#L7-L27), [Python test enforcing no SessionStore export](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting/tests/hosting/test_state.py#L105-L109))

### 2. Intentional asymmetry of .NET relative to Python
Python has `AgentState`, but .NET has no corresponding public `HostedAgentState`. This is not a deficiency but a design choice explained by ADR-0032. .NET delegates create-on-miss to the store and target lifecycle to DI, and therefore considers a separate holder unnecessary. The code is actually implemented that way.  
(Source: [ADR-0032 no HostedAgentState rationale](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0032-dotnet-hosting-protocol-helpers.md#L101-L143), [AgentSessionStore API](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/AgentSessionStore.cs#L64-L106), [AgentHostingServiceCollectionExtensions](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/AgentHostingServiceCollectionExtensions.cs#L25-L144))

In this document, **code and ADR were prioritized**, and the generic hosting surface was organized based on source/export/tests rather than marketing-level READMEs.

---

## Java decisions

## Placement principles

### What API and Engine should exclusively own
- `AgentSession`, structured `serviceSessionId`, and typed snapshot envelope
- `SessionStore` abstraction and serialization codec registry
- `WorkflowState` and restore-then-run execution rules

This contract is defined by documents 08 Session and 15–17 Workflow. The Hosting module
does not redeclare the same types or store contracts but instead receives them by injection.

### What the hosting core should own
- protocol-neutral target resolution and lifecycle wrapper
- binder that connects `AgentState`/`WorkflowState` to request processing
- session load/run/save coordination
- run values, session snapshot, and checkpoint cursor projection for use by protocol adapters

This layer must be unaware of OpenAI/A2A/AG-UI/MCP. The essence of generic hosting is not wire protocol
implementation but connecting the core execution contract to host request lifetimes. (Source:
[ADR-0027 decision outcome](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0027-hosting-channels.md#L62-L103))

### What to place in optional adapters
- protocol-specific parsers/renderers are within the scope of their respective separate documents
- however, the interface points with the hosting core must connect **only through run values, session snapshot, and checkpoint cursor**

### What to place in the host/framework module
- Spring MVC/WebFlux route binding
- principal-derived isolation key helper
- SSE writer/HTTP error mapping
- lifecycle hooks for startup/shutdown
- host-specific DI integration

### What the host application must own to the end
- authn/authz
- request-derived key authorization
- durable session/checkpoint backend
- same-session single writer
- multi-replica scaling / sticky vs stateless topology
- background job model
- deployment/readiness/liveness

## Proposed artifacts
- `agent-framework-api`
- `agent-framework-engine`
- `agent-framework-hosting-core`
- `agent-framework-hosting-spring`
- protocol adapters are within the scope of separate documents
- separate provider/infrastructure adapters (`openai`, `foundry`, `redis`, `cosmos`, `mem0`, etc.)

---

## Concrete acceptance scenarios

1. **Agent target lifecycle**
   - Must accept direct instance, sync factory, async factory, and builder-based targets.
   - Caching on/off semantics must be clear.

2. **Session continuity**
   - Agent session load must return an independent working copy of the stored snapshot.
   - In a create-on-miss race, the session must be created exactly once.
   - The host must explicitly perform the post-run session save.

3. **Workflow continuity**
   - Workflow resume must be “restore-then-run-with-new-input”, not “restore only”.
   - Even if the checkpoint head cursor is lost, if a durable checkpoint backend exists, execution must resume from the latest checkpoint.

4. **Host composition**
   - The generic hosting core must be reusable without route/auth/status code/background work.
   - Host-specific integrations such as ASP.NET/Spring must be optional modules.

5. **Scaling**
   - The fact that an in-memory store alone does not guarantee multi-replica continuity must be clear in code/docs/tests.
   - The host must be able to replace the durable session/checkpoint store and session-scoped locking policy.

6. **Security**
   - A request-derived session id must not be treated as an identity proof.
   - A multi-user host must compose the principal dimension into the key.
   - Non-unique claims or mutable display names must not be used as the default isolation key.

---

## Source inventory

### docs
- [ADR-0027: hosting channels / helper-first direction](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0027-hosting-channels.md#L50-L260)
- [ADR-0032: .NET hosting protocol helpers](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0032-dotnet-hosting-protocol-helpers.md#L72-L172)
- [Spec-002: Python hosting channels](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/specs/002-python-hosting-channels.md#L15-L208)

### .NET production source
- [HostApplicationBuilderAgentExtensions.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/HostApplicationBuilderAgentExtensions.cs#L11-L97)
- [AgentHostingServiceCollectionExtensions.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/AgentHostingServiceCollectionExtensions.cs#L11-L144)
- [HostApplicationBuilderWorkflowExtensions.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/HostApplicationBuilderWorkflowExtensions.cs#L11-L49)
- [AgentSessionStore.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/AgentSessionStore.cs#L10-L138)
- [AIHostAgent.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/AIHostAgent.cs#L10-L72)
- [HostedWorkflowState.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/HostedWorkflowState.cs#L18-L382)
- [Local/InMemoryAgentSessionStore.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/Local/InMemoryAgentSessionStore.cs#L10-L74)
- [ClaimsIdentityAgentIsolationKeyProvider.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AspNetCore/ClaimsIdentityAgentIsolationKeyProvider.cs#L13-L95)
- [ServiceCollectionExtensions.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AspNetCore/ServiceCollectionExtensions.cs#L13-L62)
- [IsolationKeyScopedAgentSessionStore.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/IsolationKeyScopedAgentSessionStore.cs#L9-L115)
- [Microsoft.Agents.AI.Hosting.csproj](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/Microsoft.Agents.AI.Hosting.csproj#L42-L46)
- [Microsoft.Agents.AI.Hosting.AspNetCore.csproj](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AspNetCore/Microsoft.Agents.AI.Hosting.AspNetCore.csproj#L3-L29)

### .NET tests
- [HostApplicationBuilderAgentExtensionsTests.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.UnitTests/HostApplicationBuilderAgentExtensionsTests.cs#L16-L237)
- [HostApplicationBuilderWorkflowExtensionsTests.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.UnitTests/HostApplicationBuilderWorkflowExtensionsTests.cs#L17-L260)
- [HostedWorkflowStateTests.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.UnitTests/HostedWorkflowStateTests.cs#L21-L317)
- [ClaimsIdentityAgentIsolationKeyProviderTests.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.UnitTests/ClaimsIdentityAgentIsolationKeyProviderTests.cs#L31-L220)
- [IsolationKeyScopedAgentSessionStoreTests.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.UnitTests/IsolationKeyScopedAgentSessionStoreTests.cs#L97-L220)

### Python production source
- [python/packages/hosting/README.md](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting/README.md#L1-L145)
- [python/packages/hosting/agent_framework_hosting/__init__.py](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting/agent_framework_hosting/__init__.py#L1-L27)
- [python/packages/hosting/agent_framework_hosting/_state.py](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting/agent_framework_hosting/_state.py#L74-L280)
- [python/packages/core/agent_framework/_sessions.py](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_sessions.py#L268-L335)
- [python/packages/core/agent_framework/_sessions.py SessionStore/FileSessionStore](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_sessions.py#L1692-L1858)
- [python/PACKAGE_STATUS.md](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L15-L52)
- [python/PACKAGE_STATUS.md experimental SessionStore feature](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L139-L145)

### Python tests
- [python/packages/hosting/tests/hosting/test_state.py](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting/tests/hosting/test_state.py#L105-L260)

### repo samples used as host-seam evidence
- [dotnet/samples/04-hosting/af-hosting/README.md](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/samples/04-hosting/af-hosting/README.md#L1-L53)
- [python/samples/04-hosting/af-hosting/README.md](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/samples/04-hosting/af-hosting/README.md#L1-L37)

## Conclusion

In this commit, generic hosting is designed not as “an abstraction that hides the server framework” but as the **minimum execution-state substrate that the server must compose on its own**. Python adopts a purer helper-first model, and .NET achieves the same goal through a DI- and store-centric structure. It is most natural for the Java implementation to follow the common denominator of both and divide into **protocol-neutral hosting core + framework-specific host module**. (Source: [ADR-0027](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0027-hosting-channels.md#L62-L103), [ADR-0032](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0032-dotnet-hosting-protocol-helpers.md#L74-L143), [Python hosting source](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting/agent_framework_hosting/_state.py#L74-L280), [.NET hosting source](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/HostedWorkflowState.cs#L18-L382))