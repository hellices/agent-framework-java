# 25. Foundry hosting, DevUI, Aspire integration, ChatKit, Telegram, and other confirmed channel adapters

## Scope

This document covers the following sub-features.

1. **Foundry hosting**
2. **DevUI**
3. **Aspire-based DevUI integration**
4. **ChatKit integration**
5. **Telegram channel adapter**
6. **Boundary of other confirmed channel/protocol adapters**

This document does not cover the general provider catalog or the detailed rules of identity/session routing separately. It focuses on each feature's **host surface**, **UI/channel adapter role**, **state and persistence contact points**, and **streaming/error/security boundaries**. OpenAI Responses, A2A, AG-UI, and MCP are already owned by separate documents; this document avoids duplication and only describes the boundaries at which those features are encountered. (Source: [Root README Foundry Hosted Agents / DevUI section](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/README.md#L48-L59), [Python AGENTS.md Protocols & UI list](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/AGENTS.md#L115-L121))

## Summary

In this repository, Foundry hosting is the **hosting layer that attaches an Agent Framework target to a managed runtime contract**, DevUI is the **developer-facing UI and API surface for development and debugging**, and Aspire integration is the **in-process reverse proxy/aggregator that ties multiple backends together**. ChatKit and Telegram are both Python-centric channel adapters, but they differ in nature. ChatKit is an integration layer that connects the OpenAI ChatKit frontend/server protocol to an Agent Framework agent stream, and Telegram is a helper-only package that bidirectionally converts Bot API updates and Agent Framework run/results. `.NET` actively implements Foundry hosting and DevUI/Aspire integration, while Python provides Foundry hosting, DevUI, ChatKit, and Telegram more broadly. Conversely, no `.NET` repo-local adapters corresponding to ChatKit and Telegram have been confirmed. Furthermore, several additional “channel/protocol adapters” are confirmed (A2A, Responses, MCP, AG-UI), but those are already in the scope of separate documents; here only boundary notes are left instead of a catalog. (Source: [Python foundry_hosting public exports](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/__init__.py#L5-L38), [.NET Foundry.Hosting csproj](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/Microsoft.Agents.AI.Foundry.Hosting.csproj#L5-L18), [.NET DevUI csproj](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.DevUI/Microsoft.Agents.AI.DevUI.csproj#L7-L13), [Aspire DevUI csproj](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Aspire.Hosting.AgentFramework.DevUI/Aspire.Hosting.AgentFramework.DevUI.csproj#L3-L18), [Python ChatKit README](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/chatkit/README.md#L1-L14), [Python Telegram README](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-telegram/README.md#L1-L24))

---

## 1. Foundry hosting

## Purpose

The purpose of Foundry hosting is to connect an Agent Framework agent or workflow to the **Foundry Agent Server / Foundry Hosted Agents runtime**, enabling it to make use of the request context, state store, responses/invocations contract, and toolbox bridge provided by the platform. Python provides `ResponsesHostServer` and `InvocationsHostServer` directly, while `.NET` fulfills the same role via `AddFoundryResponses` + `MapFoundryResponses` and `AgentFrameworkResponseHandler` based on the Azure AI Responses Server SDK. (Source: [Python foundry_hosting public exports](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/__init__.py#L5-L38), [Python foundry_hosting README overview](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/README.md#L1-L24), [.NET FoundryHostingExtensions summary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/ServiceCollectionExtensions.cs#L25-L55), [.NET AgentFrameworkResponseHandler summary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/AgentFrameworkResponseHandler.cs#L19-L25))

## Boundary

The Foundry hosting layer does the following.

- Validates the Foundry runtime request context
- Invokes the Agent Framework target run
- Generates a platform-compatible response stream
- Connects hosted state store/session/checkpoint/function approval persistence
- Provides an auth bridge with the toolbox/MCP proxy
- Fulfills hosting contracts such as readiness/listen-port

Conversely, this layer does not own the entire design of the general provider catalog or identity/session routing. For example, per-user isolation itself is enforced through the state store and request context, but the general design of which user/session key to use and how is within the scope of a separate identity/session routing document. (Source: [Python validate_foundry_request_context](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/_request_context.py#L41-L53), [.NET hosted platform context ADR](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0030-hosted-platform-context-agentserver-2.0.md#L15-L27), [.NET Foundry responses registration scope](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/ServiceCollectionExtensions.cs#L32-L63))

## Maturity

| Implementation | Maturity |
|---|---|
| `.NET` `Microsoft.Agents.AI.Foundry.Hosting` | `preview` |
| Python `agent-framework-foundry-hosting` | `beta` |
| Python `FoundryAgentSessionStore` | feature-level experimental (`SESSION_STORE`) |

Source:
- [.NET Foundry.Hosting csproj](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/Microsoft.Agents.AI.Foundry.Hosting.csproj#L5-L18)
- [Python PACKAGE_STATUS foundry-hosting row](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L31-L34)
- [Python PACKAGE_STATUS SESSION_STORE feature row](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L139-L145)

## Public APIs and types

### .NET
- `AddFoundryResponses(this IServiceCollection, ...)`
- `MapFoundryResponses(this IEndpointRouteBuilder, string prefix = "")`
- `AddFoundryToolboxes(...)`
- `HostedSessionContext`
- Foundry-specific `AgentSessionStore` abstraction and file/in-memory implementations  
  (Source: [.NET FoundryHostingExtensions public methods](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/ServiceCollectionExtensions.cs#L32-L63), [.NET AddFoundryToolboxes overloads](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/ServiceCollectionExtensions.cs#L116-L216), [.NET HostedSessionContext](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/HostedSessionContext.cs#L9-L52), [.NET Foundry AgentSessionStore contract](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/AgentSessionStore.cs#L11-L97))

### Python
- `ResponsesHostServer`
- `InvocationsHostServer`
- `FoundryToolbox`
- `FoundryAgentSessionStore`
- `FoundryCheckpointStore`
- `FoundryFunctionApprovalStore`
- `AgentSessionStoreProvider`, `CheckpointStoreProvider`, `FunctionApprovalStoreProvider`  
  (Source: [Python foundry_hosting __all__](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/__init__.py#L25-L38), [Python state store classes](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/_state_store.py#L63-L195), [Python session store classes](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/_state_store.py#L289-L335), [Python FoundryToolbox](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/_toolbox.py#L133-L223))

## Detailed execution flow

### .NET Responses path
1. The host calls `AddFoundryResponses(...)` to register `AgentFrameworkResponseHandler` as the `ResponseHandler`.
2. `MapFoundryResponses()` connects the Responses routes and the `/readiness` health check.
3. When a request arrives, the handler:
   - agent/session store resolve
   - checks protocol compatibility
   - hosted session identity context resolve
   - session load/create
   - history/input conversion
   - optional toolbox injection
   - `agent.RunStreamingAsync(...)`
   - emits the stream after output conversion
   - session persist in the finally block  
   (Source: [.NET AddFoundryResponses registration](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/ServiceCollectionExtensions.cs#L32-L63), [.NET MapFoundryResponses](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/ServiceCollectionExtensions.cs#L219-L255), [.NET AgentFrameworkResponseHandler main flow](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/AgentFrameworkResponseHandler.cs#L67-L170), [.NET handler run and final persistence](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/AgentFrameworkResponseHandler.cs#L374-L487))

### Python Responses path
1. `ResponsesHostServer` acts as the server host for the agent.
2. It calls `validate_foundry_request_context(...)` for each request.
3. On the regular agent path:
   - approval/session storage resolve
   - session lookup/create based on `previous_response_id` or `conversation_id`
   - lazy `_ensure_agent_ready()`
   - history + input items → run kwargs
   - streaming conversion and output tracking
   - session persist in the finally block  
   is performed.
4. On the workflow agent path:
   - latest checkpoint lookup
   - restore-only run
   - streaming run with new input
   - old checkpoints prune  
   is performed. (Source: [Python ResponsesHostServer init and lazy agent lifecycle](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/_responses.py#L170-L305), [handle_response entry](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/_responses.py#L307-L320), [regular agent path](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/_responses.py#L322-L485), [workflow path](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/_responses.py#L486-L648))

### Python Invocations path
1. `InvocationsHostServer` computes the partition key from the request context.
2. If hosted, it uses `session_id:user_id`; if local, it uses `session_id`.
3. It retrieves the session from the in-memory `_sessions` dict and returns either a plain `Response` or a `StreamingResponse` depending on the stream flag. (Source: [InvocationsHostServer partition key](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/_invocations.py#L43-L73), [invoke handler](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/_invocations.py#L75-L108))

## State and persistence

### .NET
`.NET` Foundry hosting has its own `AgentSessionStore` abstraction. The default local/dev store is an in-memory or file-system implementation, and in hosted environments the file store is designed to place session JSON under `$HOME/.checkpoints`. In addition, `HostedSessionContext` is a typed value that carries the user identity of a hosted session. (Source: [.NET Foundry AgentSessionStore contract](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/AgentSessionStore.cs#L11-L97), [.NET InMemoryAgentSessionStore](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/InMemoryAgentSessionStore.cs#L12-L74), [.NET FileSystemAgentSessionStore purpose and hosted path](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/FileSystemAgentSessionStore.cs#L15-L38), [default root resolution](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/FileSystemAgentSessionStore.cs#L87-L123), [.NET HostedSessionContext](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/HostedSessionContext.cs#L20-L29))

### Python
Python foundry hosting uses `FoundryStateStore(..., user_isolation=True)` when hosted.

- agent sessions → `agent_sessions`
- workflow checkpoints → `checkpoints/<context_id>`
- function approvals → `function_approvals`

The local path falls back to an in-memory store/provider. The README also describes this state layout directly. (Source: [Python README state store](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/README.md#L5-L24), [FoundryCheckpointStore](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/_state_store.py#L63-L84), [CheckpointStoreProvider](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/_state_store.py#L165-L195), [FoundryFunctionApprovalStore](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/_state_store.py#L214-L281), [FoundryAgentSessionStore](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/_state_store.py#L289-L335))

## Extension points

### .NET
- custom `AIAgent` or keyed/non-keyed registrations
- custom `AgentSessionStore`
- custom `HostedSessionIsolationKeyProvider`
- `AddFoundryToolboxes(...)` credential/options/toolbox names
- If a custom `/readiness` route is registered first, the framework does not register it again  
  (Source: [.NET AddFoundryResponses overload with explicit agent and store](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/ServiceCollectionExtensions.cs#L66-L113), [.NET AddFoundryToolboxes](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/ServiceCollectionExtensions.cs#L116-L216), [.NET readiness duplicate-route guard](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/ServiceCollectionExtensions.cs#L355-L380))

### Python
- Custom store providers for `ResponsesHostServer` / `InvocationsHostServer`
- Custom endpoint / token scope / timeout / prompt/tool loading for `FoundryToolbox`
- Custom context-scoped checkpoint storage and approval store provider  
  (Source: [ResponsesHostServer constructor extension points](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/_responses.py#L174-L199), [FoundryToolbox init options](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/_toolbox.py#L174-L223))

## Concurrency, streaming, and cancellation

### .NET
- The handler directly `yield return`s the response stream, including tool consent, shutdown incomplete, and failed terminal events.
- `x-agent-foundry-call-id` is re-injected into the ambient context at each async iterator yield boundary so that it is not lost in toolbox/MCP egress.
- Session save is performed in the finally block after the stream ends. (Source: [.NET consent/shutdown/failure loop](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/AgentFrameworkResponseHandler.cs#L393-L487), [.NET re-apply call id before egress](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/AgentFrameworkResponseHandler.cs#L242-L255), [.NET re-apply before MoveNextAsync](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/AgentFrameworkResponseHandler.cs#L395-L400))

### Python
- `ResponsesHostServer` uses `ResponseEventStream` to emit streaming responses.
- The regular agent path performs session persist in the finally block, and the workflow path prunes previous checkpoints other than the latest checkpoint.
- The `InvocationsHostServer` streaming path sends text-only chunks as `text/event-stream`. (Source: [Python regular path finally session set](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/_responses.py#L455-L484), [workflow checkpoint cleanup](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/_responses.py#L611-L622), [InvocationsHostServer streaming response](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/_invocations.py#L94-L105))

## Errors, validation, and security

### .NET
- In a hosted env, if `x-agent-foundry-call-id` is absent, protocol 1.0.0 mismatch results in `501 unsupported_container_protocol_version`
- In a hosted env, if the user id context is absent, the request is rejected
- Toolbox egress includes a fresh bearer token, mandatory `Foundry-Features`, trace context, and retry.  
  (Source: [.NET HostedProtocolCompatibility](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/HostedProtocolCompatibility.cs#L8-L74), [.NET missing user id handling](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/AgentFrameworkResponseHandler.cs#L97-L115), [.NET toolbox bearer token handler](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/FoundryToolboxBearerTokenHandler.cs#L14-L21), [.NET call-id forwarding and retry](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/FoundryToolboxBearerTokenHandler.cs#L45-L95), [.NET trace propagation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/FoundryToolboxBearerTokenHandler.cs#L123-L168))

### Python
- In a hosted env, `call_id` and `user_id` must be present in the request context.
- Explicit validation is performed when untrusted context values are used as path segments.
- Consent-required toolbox errors are surfaced as `oauth_consent_request` in the hosted response stream.  
  (Source: [Python request context validation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/_request_context.py#L17-L39), [hosted context protocol-v2 checks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/_request_context.py#L41-L53), [consent URL parsing](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/_responses.py#L95-L164), [oauth_consent_request emission](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/_responses.py#L339-L370))

## .NET implementation and tests

- Protocol mismatch 501 is fixed by `HostedProtocolCompatibilityTests`. (Source: [HostedProtocolCompatibilityTests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Foundry.Hosting.UnitTests/HostedProtocolCompatibilityTests.cs#L7-L57))
- Hosted session context stamping, local null-key permissive path, and mismatch 403 are fixed by `HostedSessionIdentityContextTests`. (Source: [HostedSessionIdentityContextTests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Foundry.Hosting.UnitTests/HostedSessionIdentityContextTests.cs#L17-L239))
- Toolbox token scope / features header / call-id forwarding / degraded startup are fixed by unit tests. (Source: [FoundryToolboxBearerTokenHandlerTests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Foundry.Hosting.UnitTests/FoundryToolboxBearerTokenHandlerTests.cs#L16-L204), [FoundryToolboxServiceTests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Foundry.Hosting.UnitTests/FoundryToolboxServiceTests.cs#L15-L257))

## Python implementation and tests

- `test_state_store.py` validates context-scoped checkpoint store, approval store, and agent session store provider behavior. (Source: [Python test_state_store](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/tests/test_state_store.py#L63-L229))
- `test_responses.py` validates the full HTTP pipeline, request context injection, and hosted response behavior. (Source: [Python test_responses overview](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/tests/test_responses.py#L3-L9), [helpers and request context setup](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/tests/test_responses.py#L92-L105))
- `test_invocations.py` validates local vs hosted partition key and 400/500/streaming behavior. (Source: [Python test_invocations partition key tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/tests/test_invocations.py#L158-L194), [invoke tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/tests/test_invocations.py#L200-L220))

## Documentation differences

The Python README describes the durable state store configuration for `ResponsesHostServer`, but does not expose the fact that `InvocationsHostServer` actually uses only a process-local `_sessions` dict. Viewing code first, the durability profiles of Responses and Invocations differ. (Source: [Python README state store description](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/README.md#L5-L24), [InvocationsHostServer in-memory sessions](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/_invocations.py#L38-L40))

## Java decisions

- `foundry-hosting` must be an **optional host adapter, not core**.
- The Responses host and the Invocations host are separated, and
- it is appropriate to place the toolbox auth/consent bridge in a separate submodule.
- readiness/listen-port/runtime contract is handled by the Spring/Servlet host binder and is not placed in the generic hosting core.

## Acceptance scenarios

1. In a hosted runtime, if protocol-v2 context is absent, a clear failure must be returned.
2. The regular responses path must persist the session after the request ends.
3. The workflow path must restore the latest checkpoint and continue with new input.
4. A local/dev run must operate without platform headers.
5. A consent-required toolbox must surface an explicit consent event instead of silent failure.
6. The dev-only file-backed session store must follow the hosted writable directory contract.

## Source inventory

- [python/packages/foundry_hosting/README.md](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/README.md#L1-L24)
- [python/packages/foundry_hosting/agent_framework_foundry_hosting/__init__.py](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/__init__.py#L1-L38)
- [python/packages/foundry_hosting/agent_framework_foundry_hosting/_responses.py](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/_responses.py#L95-L648)
- [python/packages/foundry_hosting/agent_framework_foundry_hosting/_invocations.py](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/_invocations.py#L15-L108)
- [python/packages/foundry_hosting/agent_framework_foundry_hosting/_request_context.py](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/_request_context.py#L9-L53)
- [python/packages/foundry_hosting/agent_framework_foundry_hosting/_state_store.py](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/_state_store.py#L63-L335)
- [python/packages/foundry_hosting/agent_framework_foundry_hosting/_toolbox.py](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/_toolbox.py#L41-L320)
- [dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/Microsoft.Agents.AI.Foundry.Hosting.csproj](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/Microsoft.Agents.AI.Foundry.Hosting.csproj#L1-L27)
- [dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/ServiceCollectionExtensions.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/ServiceCollectionExtensions.cs#L25-L380)
- [dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/AgentFrameworkResponseHandler.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/AgentFrameworkResponseHandler.cs#L19-L620)
- [dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/HostedProtocolCompatibility.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/HostedProtocolCompatibility.cs#L8-L75)
- [dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/HostedSessionContext.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/HostedSessionContext.cs#L9-L52)
- [dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/AgentSessionStore.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/AgentSessionStore.cs#L11-L97)
- [dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/FileSystemAgentSessionStore.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/FileSystemAgentSessionStore.cs#L15-L279)
- [dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/FoundryToolboxBearerTokenHandler.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/FoundryToolboxBearerTokenHandler.cs#L14-L194)
- [dotnet/tests/Microsoft.Agents.AI.Foundry.Hosting.UnitTests/HostedProtocolCompatibilityTests.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Foundry.Hosting.UnitTests/HostedProtocolCompatibilityTests.cs#L7-L57)
- [dotnet/tests/Microsoft.Agents.AI.Foundry.Hosting.UnitTests/HostedSessionIdentityContextTests.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Foundry.Hosting.UnitTests/HostedSessionIdentityContextTests.cs#L17-L260)
- [dotnet/tests/Microsoft.Agents.AI.Foundry.Hosting.UnitTests/FoundryToolboxBearerTokenHandlerTests.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Foundry.Hosting.UnitTests/FoundryToolboxBearerTokenHandlerTests.cs#L16-L259)
- [dotnet/tests/Microsoft.Agents.AI.Foundry.Hosting.UnitTests/FoundryToolboxServiceTests.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Foundry.Hosting.UnitTests/FoundryToolboxServiceTests.cs#L15-L257)
- [python/packages/foundry_hosting/tests/test_state_store.py](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/tests/test_state_store.py#L1-L260)
- [python/packages/foundry_hosting/tests/test_responses.py](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/tests/test_responses.py#L1-L260)
- [python/packages/foundry_hosting/tests/test_invocations.py](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/tests/test_invocations.py#L1-L220)

---

## 2. DevUI

## Purpose

The purpose of DevUI is to provide developers with a developer-facing UI and API surface that enables them to **quickly discover, run, inspect, and deployment-test agents and workflows during development**. The `.NET` DevUI is an ASP.NET application surface sitting on top of the OpenAI Responses/Conversations service, and the Python DevUI integrates entity discovery, local execution, an optional OpenAI proxy, and deployment endpoints into its own `FastAPI` server (`DevServer`). (Source: [.NET DevUIExtensions summary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.DevUI/DevUIExtensions.cs#L8-L30), [Python DevServer class docs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/devui/agent_framework_devui/_server.py#L67-L93))

## Boundary

DevUI is a dev/test interface, not a generic runtime contract.

- `.NET` requires `AddOpenAIResponses`, `AddOpenAIConversations`, and the corresponding endpoint mappings to be in place before DevUI can operate on top of them.
- Python spins up its own API server, but auth/host binding/CORS is a **development tool with operational boundaries** controlled by DevServer options.
- It does not replace the canonical application API for production deployment. (Source: [.NET DevUI requires OpenAI Responses and Conversations](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.DevUI/DevUIExtensions.cs#L18-L23), [Python DevServer mode/auth docs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/devui/agent_framework_devui/_server.py#L81-L93))

## Maturity

| Implementation | Maturity |
|---|---|
| `.NET` `Microsoft.Agents.AI.DevUI` | `preview` |
| Python `agent-framework-devui` | `beta` |

Source:
- [.NET DevUI csproj](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.DevUI/Microsoft.Agents.AI.DevUI.csproj#L1-L13)
- [Python PACKAGE_STATUS devui row](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L29-L34)

## Public APIs and types

### .NET
- `AddDevUI(this IHostApplicationBuilder, Action<DevUIOptions>? configure = null)`
- `MapDevUI(this IEndpointRouteBuilder)`
- `DevUIAuthFilter`
- `DevUIMiddleware`  
  (Source: [.NET HostApplicationBuilder AddDevUI](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.DevUI/HostApplicationBuilderExtensions.cs#L7-L33), [.NET MapDevUI](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.DevUI/DevUIExtensions.cs#L11-L64), [.NET DevUIAuthFilter](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.DevUI/DevUIAuthFilter.cs#L11-L104), [.NET DevUIMiddleware](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.DevUI/DevUIMiddleware.cs#L14-L260))

### Python
- `DevServer`
- `EntityDiscovery`
- internal OpenAI proxy/local executor composition
- package-level serve path via `agent_framework_devui` public entrypoints  
  (Source: [Python DevServer](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/devui/agent_framework_devui/_server.py#L67-L119), [EntityDiscovery](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/devui/agent_framework_devui/_discovery.py#L23-L52))

## Detailed execution flow

### .NET
1. The host registers services with `AddDevUI(...)`.
2. `MapDevUI()`:
   - startup security warning check
   - leaves `/meta` unauthenticated for use in frontend bootstrapping
   - applies custom endpoint conventions and `DevUIAuthFilter` to the protected group
   - exposes the `/devui` static frontend and `/v1/entities`-family endpoints.  
   (Source: [.NET MapDevUI flow](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.DevUI/DevUIExtensions.cs#L38-L64))

### Python
1. `DevServer.get_app()` configures middleware (CORS, auth, Host header guard).
2. `_register_routes()` maps `/health`, `/meta`, `/v1/entities`, `/v1/responses`, `/v1/conversations`, and deployment endpoints.
3. `/v1/responses` routes to the OpenAI executor if the `X-Proxy-Backend` header is `"openai"`, and to the local Agent Framework executor otherwise.
4. Before entity execution, `_ensure_executor()` / `_ensure_openai_executor()` are lazily initialized.  
   (Source: [Python middleware setup](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/devui/agent_framework_devui/_server.py#L397-L497), [route registration](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/devui/agent_framework_devui/_server.py#L499-L530), [executor initialization](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/devui/agent_framework_devui/_server.py#L219-L272), [responses endpoint routing](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/devui/agent_framework_devui/_server.py#L809-L883))

## State and persistence

### .NET
DevUI itself does not define a new independent session store. It uses the underlying OpenAI Responses/Conversations services and resolves keyed/non-keyed agent/workflow registrations. That is, the DevUI state consumes the state surface of the OpenAI-compatible backend already exposed by the host. (Source: [.NET DevUI prerequisite on responses/conversations](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.DevUI/DevUIExtensions.cs#L18-L23), [.NET DevUIExtensionsTests workflow/agent resolution](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.DevUI.UnitTests/DevUIExtensionsTests.cs#L42-L66))

### Python
The Python DevServer has the following of its own:
- discovered entities registry
- loaded object cache
- cleanup hooks
- running response task registry
- conversation/deployment managers  
Entity discovery uses sparse scan followed by lazy load, and also supports cache invalidation for hot reload. (Source: [EntityDiscovery fields](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/devui/agent_framework_devui/_discovery.py#L23-L35), [lazy loading and enrichment](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/devui/agent_framework_devui/_discovery.py#L75-L155), [invalidate_entity / invalidate_all](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/devui/agent_framework_devui/_discovery.py#L239-L288), [DevServer running tasks and managers](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/devui/agent_framework_devui/_server.py#L111-L116))

## Extension points

### .NET
- `DevUIOptions.AllowRemoteAccess`
- bearer token (`AuthToken` or env var)
- endpoint convention hook `ConfigureEndpoints`
- keyed/non-keyed agent/workflow registrations that DevUI can discover  
  (Source: [.NET DevUIExtensions remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.DevUI/DevUIExtensions.cs#L24-L29), [.NET DevUIAuthFilter token source](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.DevUI/DevUIAuthFilter.cs#L24-L43))

### Python
- `entities_dir`
- `host` / `port`
- `cors_origins`
- `mode` (`developer` vs `user`)
- `auth_enabled`
- `auth_token`
- in-memory pending entity registration  
  (Source: [Python DevServer init options](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/devui/agent_framework_devui/_server.py#L71-L93), [set_pending_entities](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/devui/agent_framework_devui/_server.py#L117-L119))

## Concurrency, streaming, and cancellation

### .NET
The `.NET` DevUI repo-local code itself does not redefine a separate streaming transport; instead it combines the underlying OpenAI responses/conversations endpoints with UI route serving. The core concurrency/streaming contract resides on the backend responses service side. (Source: [.NET DevUI prerequisites on OpenAI endpoints](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.DevUI/DevUIExtensions.cs#L18-L23))

### Python
- `/v1/responses/{response_id}/cancel` cancels the running task registry.
- The streaming response delegates to CORSMiddleware without hardcoding ACAO.
- `X-Response-ID` is placed in the header for use in cancellation/debug tracking.  
  (Source: [Python running tasks registry](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/devui/agent_framework_devui/_server.py#L113-L116), [responses streaming path headers](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/devui/agent_framework_devui/_server.py#L859-L877), [cancel endpoint](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/devui/agent_framework_devui/_server.py#L885-L907), [test no hardcoded ACAO](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/devui/tests/devui/test_server.py#L568-L588))

## Errors, validation, and security

### .NET
- default loopback-only
- Non-loopback connections receive a 403 without `AllowRemoteAccess`
- When a token is configured, a missing or incorrect bearer token results in 401
- `/meta` is unauthenticated-reachable for frontend bootstrapping  
  (Source: [.NET DevUIAuthFilter loopback and token logic](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.DevUI/DevUIAuthFilter.cs#L46-L103), [.NET DevUI /meta rationale](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.DevUI/DevUIExtensions.cs#L49-L59))

### Python
- auth default-on
- non-loopback host + no explicit token results in `ValueError`
- Host header allowlist is enforced even on loopback binds
- Tests fix that `/meta` also requires auth
- A test exists to prevent request content/metadata from being logged  
  (Source: [Python auth/token resolution rules](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/devui/agent_framework_devui/_server.py#L141-L165), [auth middleware](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/devui/agent_framework_devui/_server.py#L414-L460), [host-header middleware](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/devui/agent_framework_devui/_server.py#L464-L492), [auth default and host restrictions tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/devui/tests/devui/test_server.py#L627-L756), [meta auth test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/devui/tests/devui/test_server.py#L797-L813), [log hygiene test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/devui/tests/devui/test_server.py#L657-L681))

## .NET implementation and tests

- `DevUIAccessControlTests` validates non-loopback default deny, remote allow override, bearer token, env token, and `/meta` accessibility. (Source: [.NET DevUIAccessControlTests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.DevUI.UnitTests/DevUIAccessControlTests.cs#L40-L181))
- `DevUIExtensionsTests` validates the resolution of keyed/non-keyed workflows and agents. (Source: [.NET DevUIExtensionsTests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.DevUI.UnitTests/DevUIExtensionsTests.cs#L42-L220))

## Python implementation and tests

- `test_server.py` validates auth defaults, loopback/no-auth rules, host header allowlist, meta auth, streaming CORS, and log hygiene. (Source: [Python test_server security posture section](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/devui/tests/devui/test_server.py#L557-L840))
- `test_discovery.py` validates agent/workflow discovery, empty directory, lazy load, and type detection. (Source: [Python test_discovery](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/devui/tests/devui/test_discovery.py#L14-L220))

## Documentation differences

The largest difference is the `/meta` security policy.

- `.NET` leaves `/meta` unauthenticated.
- Python tests fix that `/meta` also requires auth.

That is, while both belong to the DevUI category, the code-first behaviors differ. (Source: [.NET /meta unauthenticated note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.DevUI/DevUIExtensions.cs#L49-L59), [Python meta auth test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/devui/tests/devui/test_server.py#L797-L813))

## Java decisions

- DevUI must be placed as a **development-tool-only module**.
- It is appropriate to maintain it as a separate dev/test artifact rather than a production runtime dependency.
- `meta`, discovery, local execution, optional proxy, and deployment helpers must be co-located, but host binding and auth policy must be separately configurable according to the Spring profile.

## Acceptance scenarios

1. There must be a loopback-only default or an explicit remote access toggle.
2. Discovered agents/workflows must be stably resolvable in the UI.
3. DevUI endpoints such as `/meta`, `/v1/entities`, `/v1/responses`, and `/v1/conversations` must have a consistent auth policy.
4. On the Python path, a running response must be stoppable via a cancellation endpoint.
5. Sensitive request payloads such as user input/metadata must not leak into logs.

## Source inventory

- [dotnet/src/Microsoft.Agents.AI.DevUI/HostApplicationBuilderExtensions.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.DevUI/HostApplicationBuilderExtensions.cs#L7-L33)
- [dotnet/src/Microsoft.Agents.AI.DevUI/DevUIExtensions.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.DevUI/DevUIExtensions.cs#L8-L106)
- [dotnet/src/Microsoft.Agents.AI.DevUI/DevUIAuthFilter.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.DevUI/DevUIAuthFilter.cs#L11-L104)
- [dotnet/src/Microsoft.Agents.AI.DevUI/DevUIMiddleware.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.DevUI/DevUIMiddleware.cs#L14-L260)
- [dotnet/tests/Microsoft.Agents.AI.DevUI.UnitTests/DevUIAccessControlTests.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.DevUI.UnitTests/DevUIAccessControlTests.cs#L17-L183)
- [dotnet/tests/Microsoft.Agents.AI.DevUI.UnitTests/DevUIExtensionsTests.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.DevUI.UnitTests/DevUIExtensionsTests.cs#L11-L220)
- [python/packages/devui/agent_framework_devui/_server.py](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/devui/agent_framework_devui/_server.py#L67-L1202)
- [python/packages/devui/agent_framework_devui/_discovery.py](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/devui/agent_framework_devui/_discovery.py#L23-L320)
- [python/packages/devui/tests/devui/test_server.py](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/devui/tests/devui/test_server.py#L557-L840)
- [python/packages/devui/tests/devui/test_discovery.py](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/devui/tests/devui/test_discovery.py#L14-L220)

---

## 3. Aspire integration for DevUI

## Purpose

The purpose of Aspire integration is to provide DevUI as a **unified development UI that ties multiple agent backends together within a distributed application (AppHost)**. The aggregator operates like a reverse proxy inside the AppHost process without a separate container image, and consolidates entity listings and API routes from multiple backends into one. (Source: [Aspire AddDevUI remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Aspire.Hosting.AgentFramework.DevUI/AgentFrameworkBuilderExtensions.cs#L20-L37), [DevUIAggregatorHostedService summary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Aspire.Hosting.AgentFramework.DevUI/DevUIAggregatorHostedService.cs#L25-L29))

## Boundary

Aspire integration:

- development-time AppHost convenience
- in-process reverse proxy / aggregator
- resource annotation-based backend aggregation

focuses on. It is excluded from the deployment manifest and is not a feature that replaces a production public API gateway. No equivalent Aspire-specific integration is visible in Python. (Source: [Aspire AddDevUI ExcludeFromManifest](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Aspire.Hosting.AgentFramework.DevUI/AgentFrameworkBuilderExtensions.cs#L57-L61), [same file dev-only remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Aspire.Hosting.AgentFramework.DevUI/AgentFrameworkBuilderExtensions.cs#L29-L37))

## Maturity

| Implementation | Maturity |
|---|---|
| `.NET` `Aspire.Hosting.AgentFramework.DevUI` | `preview` |
| Python | N/A |

Source:
- [Aspire DevUI csproj](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Aspire.Hosting.AgentFramework.DevUI/Aspire.Hosting.AgentFramework.DevUI.csproj#L3-L18)

## Public APIs and types

- `IDistributedApplicationBuilder.AddDevUI(name, port?)`
- `IResourceBuilder<DevUIResource>.WithAgentService(...)`
- `DevUIResource`
- internal `DevUIAggregatorHostedService`  
  (Source: [Aspire AddDevUI public API](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Aspire.Hosting.AgentFramework.DevUI/AgentFrameworkBuilderExtensions.cs#L15-L49), [WithAgentService](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Aspire.Hosting.AgentFramework.DevUI/AgentFrameworkBuilderExtensions.cs#L127-L184), [DevUIResource](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Aspire.Hosting.AgentFramework.DevUI/DevUIResource.cs#L7-L49))

## Detailed execution flow

1. AppHost calls `AddDevUI("devui")`.
2. At `InitializeResourceEvent` time, it creates an in-process `DevUIAggregatorHostedService`.
3. It publishes `BeforeResourceStartedEvent` to wait for backend dependencies.
4. The aggregator starts and the allocated endpoint is recorded in the resource annotation.
5. The Dashboard exposes the URL `http://localhost:{port}/devui/`.
6. When the app stops, the aggregator is stopped/disposed. (Source: [Aspire AddDevUI initialization flow](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Aspire.Hosting.AgentFramework.DevUI/AgentFrameworkBuilderExtensions.cs#L62-L122))

The aggregator itself:

- `/health`
- `/v1/entities`
- `/v1/entities/{**entityPath}`
- `/v1/responses`
- `/v1/conversations/{**path}`
- `/meta`
- `/devui/{**path}`

is exposed, and if embedded resources are present the frontend is served directly; otherwise it proxies from the first backend. (Source: [DevUIAggregatorHostedService route map](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Aspire.Hosting.AgentFramework.DevUI/DevUIAggregatorHostedService.cs#L268-L280), [serve embedded or proxy frontend](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Aspire.Hosting.AgentFramework.DevUI/DevUIAggregatorHostedService.cs#L154-L211))

## State and persistence

The Aspire aggregator does not own its own agent execution state. Instead:
- backend URL resolution cache-less lookup
- `conversationId -> backend URL` map
- resource annotations-based metadata
it maintains. In particular, `conversationBackendMap` is used for subsequent conversation GET routing when no `agent_id` is present. (Source: [conversationBackendMap field](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Aspire.Hosting.AgentFramework.DevUI/DevUIAggregatorHostedService.cs#L39-L45), [ResolveBackends no caching note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Aspire.Hosting.AgentFramework.DevUI/DevUIAggregatorHostedService.cs#L283-L308))

## Extension points

- `port`
- `WithAgentService(resource, agents, entityIdPrefix)`
- Even if a backend does not expose its own `/v1/entities`, entity listing can be generated from AppHost-declared `agents` metadata  
  (Source: [AddDevUI signature](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Aspire.Hosting.AgentFramework.DevUI/AgentFrameworkBuilderExtensions.cs#L49-L53), [WithAgentService docs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Aspire.Hosting.AgentFramework.DevUI/AgentFrameworkBuilderExtensions.cs#L127-L154), [entity listing build from annotations](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Aspire.Hosting.AgentFramework.DevUI/DevUIAggregatorHostedService.cs#L335-L360))

## Concurrency, streaming, and cancellation

The primary concern of the Aspire aggregator is proxying and aggregation. More important than stateful execution concurrency is:
- handling late allocation of backend URLs
- proxy request routing
- frontend serving fallback

No separate task cancellation surface is visible. (Source: [ResolveBackendUrl late availability handling](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Aspire.Hosting.AgentFramework.DevUI/DevUIAggregatorHostedService.cs#L310-L333))

## Errors, validation, and security

- Aggregator startup failure publishes the resource state as `FailedToStart`.
- If no frontend assembly is present, a backend proxy fallback is used instead of embedded serving.
- A backend entity fetch failure logs a warning and continues aggregating other backends. (Source: [startup failure handling](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Aspire.Hosting.AgentFramework.DevUI/AgentFrameworkBuilderExtensions.cs#L111-L120), [frontend resource load fallback](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Aspire.Hosting.AgentFramework.DevUI/DevUIAggregatorHostedService.cs#L114-L152), [entity fetch warning path](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Aspire.Hosting.AgentFramework.DevUI/DevUIAggregatorHostedService.cs#L370-L418))

## .NET implementation and tests

- `DevUIAggregatorHostedServiceTests` validates aggregator behavior such as querystring agent_id rewrite and backend annotation wiring. (Source: [DevUIAggregatorHostedServiceTests query rewrite](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Aspire.Hosting.AgentFramework.DevUI.UnitTests/DevUIAggregatorHostedServiceTests.cs#L27-L163), [WithAgentService annotation behavior](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Aspire.Hosting.AgentFramework.DevUI.UnitTests/DevUIAggregatorHostedServiceTests.cs#L167-L220))

## Python implementation and tests

- Not applicable. The inspected repo contains no Python Aspire-specific DevUI integration.

## Documentation differences

- No significant code/doc mismatch was observed.
- However, this feature is described far more concretely than the DevUI bullet in the root README; the fact that it is actually a **multi-backend in-process reverse proxy** is more clearly evident in the source comments. (Source: [Root README DevUI bullet](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/README.md#L58-L59), [Aspire AddDevUI detailed remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Aspire.Hosting.AgentFramework.DevUI/AgentFrameworkBuilderExtensions.cs#L23-L37))

## Java decisions

- If there is a Java distributed app orchestrator corresponding to Aspire, it is appropriate to split it out as a **dev-only aggregator module**.
- It must not be mixed with the production API gateway, and must be treated as a companion service of the local dashboard/dev shell.

## Acceptance scenarios

1. Multiple backend agent services within an AppHost must be aggregatable into a single DevUI entrypoint.
2. Entity listing must be creatable from AppHost metadata alone even if a backend does not expose `/v1/entities`.
3. If no frontend bundle is present, the first backend proxy fallback must function.
4. Startup failure must be clearly surfaced as a resource state.

## Source inventory

- [dotnet/src/Aspire.Hosting.AgentFramework.DevUI/Aspire.Hosting.AgentFramework.DevUI.csproj](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Aspire.Hosting.AgentFramework.DevUI/Aspire.Hosting.AgentFramework.DevUI.csproj#L1-L36)
- [dotnet/src/Aspire.Hosting.AgentFramework.DevUI/AgentFrameworkBuilderExtensions.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Aspire.Hosting.AgentFramework.DevUI/AgentFrameworkBuilderExtensions.cs#L15-L185)
- [dotnet/src/Aspire.Hosting.AgentFramework.DevUI/DevUIAggregatorHostedService.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Aspire.Hosting.AgentFramework.DevUI/DevUIAggregatorHostedService.cs#L25-L418)
- [dotnet/src/Aspire.Hosting.AgentFramework.DevUI/DevUIResource.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Aspire.Hosting.AgentFramework.DevUI/DevUIResource.cs#L7-L49)
- [dotnet/tests/Aspire.Hosting.AgentFramework.DevUI.UnitTests/DevUIAggregatorHostedServiceTests.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Aspire.Hosting.AgentFramework.DevUI.UnitTests/DevUIAggregatorHostedServiceTests.cs#L22-L220)

---

## 4. ChatKit

## Purpose

The purpose of the ChatKit integration is to connect the Agent Framework agent stream to thread items and stream events conforming to the **OpenAI ChatKit server/frontend protocol**. The package provides a seam similar to the Agent SDK integration. (Source: [Python ChatKit README overview](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/chatkit/README.md#L1-L14), [Python chatkit __init__](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/chatkit/agent_framework_chatkit/__init__.py#L3-L25))

## Boundary

What the ChatKit package does:
- ChatKit thread items → Agent Framework `Message[]` conversion
- Agent Framework `AgentResponseUpdate` stream → ChatKit `ThreadStreamEvent` conversion

Conversely:
- full web host
- frontend hosting
- thread store / attachment store
- domain allowlist setup
- Resolving OpenAI CDN dependencies  
is the responsibility of the host application or the OpenAI ChatKit ecosystem. In particular, the README explicitly states that the frontend is not self-hostable. (Source: [ChatKit README what this package provides](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/chatkit/README.md#L5-L14), [frontend requirements and network dependencies](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/chatkit/README.md#L25-L40), [air-gapped limitations](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/chatkit/README.md#L42-L52))

## Maturity

| Implementation | Maturity |
|---|---|
| Python `agent-framework-chatkit` | `beta` |
| `.NET` repo-local | No confirmed corresponding package |

Source:
- [Python PACKAGE_STATUS chatkit row](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L24-L27)

## Public APIs and types

- `ThreadItemConverter`
- `simple_to_agent_input`
- `stream_agent_response`  
  (Source: [Python chatkit __all__](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/chatkit/agent_framework_chatkit/__init__.py#L12-L25))

## Detailed execution flow

### Inbound path
`ThreadItemConverter` converts a ChatKit `UserMessageItem`/thread item into an Agent Framework `Message`.

- It combines text parts into user message text.
- If attachments are present, they are converted to content via an attachment fetcher.
- If quoted text is present, a context message can be prepended.  
  (Source: [ThreadItemConverter class docs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/chatkit/agent_framework_chatkit/_converter.py#L39-L61), [user_message_to_input](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/chatkit/agent_framework_chatkit/_converter.py#L63-L120))

### Outbound path
`stream_agent_response(response_stream, thread_id, generate_id=None)` receives an Agent Framework `AgentResponseUpdate` stream and:

1. `ThreadItemAddedEvent` before the first delta
2. `ThreadItemUpdated` + `AssistantMessageContentPartTextDelta` for each text chunk
3. `ThreadItemDoneEvent` carrying the accumulated text at the end

generates. That is, it enables the ChatKit UI to perform token-by-token streaming. (Source: [stream_agent_response docs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/chatkit/agent_framework_chatkit/_streaming.py#L24-L49), [event emission logic](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/chatkit/agent_framework_chatkit/_streaming.py#L61-L108))

### End-to-end sample
The sample `WeatherChatKitServer`:
- loads the full history from the thread store
- performs `simple_to_agent_input` conversion
- `agent.run(..., stream=True)`
- `stream_agent_response(...)`
- widget stream  
constructs the ChatKit backend in that order. (Source: [ChatKit integration sample architecture and key points](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/samples/05-end-to-end/chatkit-integration/README.md#L82-L129))

## State and persistence

The ChatKit integration package itself does not provide persistence. Thread/message/attachment persistence is the responsibility of the ChatKit `Store` / `AttachmentStore` implementations; the sample uses `SQLiteStore` and `FileBasedAttachmentStore`. That is, the channel adapter is a stateless conversion layer and durable state must be configured by the host. (Source: [ChatKit README example note on your_store](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/chatkit/README.md#L70-L74), [sample store architecture](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/samples/05-end-to-end/chatkit-integration/README.md#L96-L108))

## Extension points

- custom attachment fetcher (`ThreadItemConverter`)
- custom ID generator (`stream_agent_response`)
- custom ChatKit store / attachment store
- custom widget rendering in sample app  
  (Source: [ThreadItemConverter attachment_data_fetcher](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/chatkit/agent_framework_chatkit/_converter.py#L42-L61), [stream_agent_response generate_id](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/chatkit/agent_framework_chatkit/_streaming.py#L24-L44), [sample widget rendering mention](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/samples/05-end-to-end/chatkit-integration/README.md#L110-L129))

## Concurrency, streaming, and cancellation

- The stream helper sends multi-chunk text as delta events in order.
- There is no explicit cancellation API in the package; the underlying server/framework must handle it.
- An empty stream emits no events. (Source: [stream_agent_response implementation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/chatkit/agent_framework_chatkit/_streaming.py#L61-L108), [empty stream test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/chatkit/tests/test_streaming.py#L21-L35))

## Errors, validation, and security

- The package itself does not provide a credential/auth layer.
- Because the frontend has outbound network dependencies on OpenAI CDN, `chatgpt.com`, and Mixpanel, it is unsuitable for air-gapped or highly regulated environments.
- The production frontend requires OpenAI domain allowlist and domain key configuration. (Source: [frontend network requirements](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/chatkit/README.md#L25-L40), [air-gapped limitations](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/chatkit/README.md#L42-L52), [sample domain key configuration](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/samples/05-end-to-end/chatkit-integration/README.md#L153-L165))

## .NET implementation and tests

- Within the scope of the inspected repo, no `.NET` counterpart ChatKit integration package was confirmed.

## Python implementation and tests

- `test_streaming.py` validates handling of empty stream, single text update, multi-chunk text update, custom id generator, empty content, and non-text content. (Source: [ChatKit streaming tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/chatkit/tests/test_streaming.py#L21-L157))
- `test_converter.py` validates the basic behavior of `simple_to_agent_input`. (Source: [ChatKit converter tests excerpt](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/chatkit/tests/test_converter.py#L664-L692))

## Documentation differences

- No significant code/doc mismatch was confirmed.
- However, the README strongly warns about frontend network dependencies, and the source implementation provides no separate fallback to mitigate this issue. Therefore, “self-hostable backend” and “self-hostable UX stack” must be read as distinct. (Source: [ChatKit README backend vs frontend distinction](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/chatkit/README.md#L42-L52))

## Java decisions

- It is appropriate to place ChatKit as an **optional integration module**.
- It is safer to provide only the backend adapter and let the host app separately decide the frontend delivery stack.
- If the UI stack has an external CDN/domain allowlist dependency, it must not be hidden in the Java backend package but explicitly surfaced.

## Acceptance scenarios

1. ChatKit thread items must be convertible to an Agent Framework message history.
2. An Agent Framework text stream must be reflected in real time as ChatKit delta events.
3. Attachment-aware inbound conversion must be extensible with a custom fetcher.
4. It must be clear that the backend may be self-hostable but the frontend has external network dependencies.

## Source inventory

- [python/packages/chatkit/README.md](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/chatkit/README.md#L1-L127)
- [python/packages/chatkit/agent_framework_chatkit/__init__.py](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/chatkit/agent_framework_chatkit/__init__.py#L1-L25)
- [python/packages/chatkit/agent_framework_chatkit/_converter.py](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/chatkit/agent_framework_chatkit/_converter.py#L39-L120)
- [python/packages/chatkit/agent_framework_chatkit/_streaming.py](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/chatkit/agent_framework_chatkit/_streaming.py#L24-L109)
- [python/packages/chatkit/tests/test_streaming.py](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/chatkit/tests/test_streaming.py#L21-L157)
- [python/packages/chatkit/tests/test_converter.py](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/chatkit/tests/test_converter.py#L664-L692)
- [python/samples/05-end-to-end/chatkit-integration/README.md](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/samples/05-end-to-end/chatkit-integration/README.md#L82-L165)

---

## 5. Telegram

## Purpose

The purpose of the Telegram adapter is to bidirectionally convert the Telegram Bot API `Update` shape and Agent Framework run/result. Specifically, it converts inbound messages, edited messages, callback queries, and media attachments into Agent Framework `Message`/`Content`, and converts final/streaming responses into Telegram Bot API operations. (Source: [Python hosting-telegram README summary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-telegram/README.md#L1-L24), [Python parsing helper docs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-telegram/agent_framework_hosting_telegram/_parsing.py#L3-L13), [Python rendering helper docs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-telegram/agent_framework_hosting_telegram/_rendering.py#L3-L9))

## Boundary

This package is **helper-only**. What it does not provide is as follows.

- Bot API client
- polling/webhook lifecycle
- hosting/channel registry
- long-running service
- rate limit / retry policy
- command dispatch policy
- durable store selection

That is, the Telegram integration must be used together with an application-owned bot service. (Source: [Telegram README responsibilities](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-telegram/README.md#L5-L24))

## Maturity

| Implementation | Maturity |
|---|---|
| Python `agent-framework-hosting-telegram` | `alpha` |
| `.NET` repo-local | No confirmed corresponding package |

Source:
- [Python PACKAGE_STATUS hosting-telegram row](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L37-L41)

## Public APIs and types

- `telegram_chat_id`
- `telegram_session_id`
- `telegram_command`
- `telegram_callback_query_id`
- `telegram_media_file_id`
- `telegram_to_run`
- `telegram_from_run`
- `telegram_from_streaming_run`
- `TelegramOperation`  
  (Source: [Python hosting-telegram __all__](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-telegram/agent_framework_hosting_telegram/__init__.py#L7-L43))

## Detailed execution flow

### Inbound
1. The helper looks for one of `message`, `edited_message`, or `callback_query`.
2. `telegram_chat_id()` extracts the chat id.
3. `telegram_session_id(update, bot_id=...)` produces `telegram:<bot_id>:<user_id>` for private chats and `telegram:<bot_id>:<chat_id>` for all others.
4. `telegram_to_run(...)`:
   - text / caption
   - optional resolved media URL
   - callback query data  
   is converted into Agent Framework `Message`/`Content`. (Source: [chat_id extraction](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-telegram/agent_framework_hosting_telegram/_parsing.py#L61-L85), [session_id rules](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-telegram/agent_framework_hosting_telegram/_parsing.py#L88-L129), [telegram_to_run flow](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-telegram/agent_framework_hosting_telegram/_parsing.py#L262-L320))

### Outbound
- `telegram_from_run(...)`:
  - `sendPhoto` if a first image is present
  - `sendMessage` otherwise
  - empty response falls back to `"(no response)"`
- `telegram_from_streaming_run(...)`:
  - cumulative text via `editMessageText`
  - final images via `sendPhoto`
  - image-only final response: deletes the placeholder then `sendPhoto`  
  (Source: [telegram_from_run](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-telegram/agent_framework_hosting_telegram/_rendering.py#L63-L100), [telegram_from_streaming_run](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-telegram/agent_framework_hosting_telegram/_rendering.py#L103-L176))

### Sample app
The sample webhook app:
- aiogram `Bot` + `Dispatcher`
- `telegram_to_run(..., stream=True)`
- creates a placeholder message
- executes `telegram_from_streaming_run(...)` operations
- after the stream ends, sets `state.set_session(...)` under a stable per-chat session key  
  takes this path. (Source: [sample app overview](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/samples/04-hosting/af-hosting/local_telegram/app.py#L16-L40), [tool + agent setup](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/samples/04-hosting/af-hosting/local_telegram/app.py#L88-L120), [handle_update run and render path](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/samples/04-hosting/af-hosting/local_telegram/app.py#L176-L243))

## State and persistence

The Telegram helper package does not carry its own store. The sample uses `AgentState` and stores an `AgentSession` under the stable key created by `telegram_session_id(...)`. The `/new` command deletes that key to start a new session. That is, the Telegram adapter provides a state key policy but the persistence backend is chosen by the host. (Source: [README sessions/storage note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-telegram/README.md#L20-L24), [sample /new command deletes session](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/samples/04-hosting/af-hosting/local_telegram/app.py#L162-L166), [stream finalization persists session](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/samples/04-hosting/af-hosting/local_telegram/app.py#L241-L243))

## Extension points

- custom `resolve_file_url(file_id)` implementation
- app-owned command table/command effects
- actual Bot API HTTP client implementation
- parse mode / placeholder policy / edit throttling
- session store backend replacement  
  (Source: [README helper list](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-telegram/README.md#L25-L54), [sample resolve_file_url hook](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/samples/04-hosting/af-hosting/local_telegram/app.py#L193-L203))

## Concurrency, streaming, and cancellation

- The sample serializes per-chat/session updates using a `session_locks` dict.
- Streaming edits are throttled by `EDIT_INTERVAL_SECONDS`.
- The helper package itself does not define cancel semantics. (Source: [sample session_locks and throttling constants](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/samples/04-hosting/af-hosting/local_telegram/app.py#L78-L82), [session lock use](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/samples/04-hosting/af-hosting/local_telegram/app.py#L187-L189), [edit throttling loop](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/samples/04-hosting/af-hosting/local_telegram/app.py#L227-L239))

## Errors, validation, and security

- If a media-only message is present and the resolver is absent or returns `None`, a `ValueError` is raised
- The Telegram webhook secret validates delivery authenticity only; end-user authorization is separate
- The helper does not handle rate limiting or retries. (Source: [parsing unresolved media error](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-telegram/agent_framework_hosting_telegram/_parsing.py#L276-L291), [sample production readiness warning](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/samples/04-hosting/af-hosting/local_telegram/app.py#L24-L32), [README responsibilities incl. rate limits/retries](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-telegram/README.md#L10-L18))

## .NET implementation and tests

- Within the scope of the inspected repo, no `.NET` counterpart Telegram hosting package was confirmed.

## Python implementation and tests

- `test_parsing.py` validates chat id, private/group session id, command normalization, media file selection, callback query, and unresolved media failure. (Source: [parsing tests chat/session/command](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-telegram/tests/hosting_telegram/test_parsing.py#L35-L115), [media tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-telegram/tests/hosting_telegram/test_parsing.py#L127-L183), [telegram_to_run tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-telegram/tests/hosting_telegram/test_parsing.py#L186-L260))
- `test_rendering.py` validates sendMessage/sendPhoto fallback, truncation, streaming edits, image-only deleteMessage path, and iterator/finalizer error propagation. (Source: [rendering tests final responses](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-telegram/tests/hosting_telegram/test_rendering.py#L24-L126), [streaming rendering tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-telegram/tests/hosting_telegram/test_rendering.py#L128-L255))

## Documentation differences

- No significant code/doc mismatch was observed.
- The document description of a helper-only package matches the actual code well. (Source: [README helper-only statement](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-telegram/README.md#L3-L8), [public exports](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-telegram/agent_framework_hosting_telegram/__init__.py#L7-L43))

## Java decisions

- It is appropriate to place Telegram as an **optional channel adapter**.
- Provide only the update parsing / session key helper / Bot API operation renderer, and
- it is appropriate for the host app to directly take responsibility for the webhook/polling lifecycle, retries, auth, and persistence.

## Acceptance scenarios

1. Private chats and group chats must have different stable session key rules.
2. `/command@bot args` must be parsed into a normalized command string.
3. A text response must use `sendMessage`, an image response must use `sendPhoto`, and an image-only final stream must use `deleteMessage` + `sendPhoto`.
4. Media-only input must fail fast if there is no resolver.
5. The host must be able to control multi-update races with a per-session lock.

## Source inventory

- [python/packages/hosting-telegram/README.md](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-telegram/README.md#L1-L85)
- [python/packages/hosting-telegram/agent_framework_hosting_telegram/__init__.py](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-telegram/agent_framework_hosting_telegram/__init__.py#L1-L43)
- [python/packages/hosting-telegram/agent_framework_hosting_telegram/_parsing.py](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-telegram/agent_framework_hosting_telegram/_parsing.py#L3-L320)
- [python/packages/hosting-telegram/agent_framework_hosting_telegram/_rendering.py](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-telegram/agent_framework_hosting_telegram/_rendering.py#L3-L177)
- [python/packages/hosting-telegram/tests/hosting_telegram/test_parsing.py](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-telegram/tests/hosting_telegram/test_parsing.py#L1-L260)
- [python/packages/hosting-telegram/tests/hosting_telegram/test_rendering.py](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-telegram/tests/hosting_telegram/test_rendering.py#L1-L256)
- [python/samples/04-hosting/af-hosting/local_telegram/app.py](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/samples/04-hosting/af-hosting/local_telegram/app.py#L1-L260)

---

## 6. Other confirmed channel / protocol adapters

## Purpose

The purpose of this subsection is to record the **fact that additional confirmed channel/protocol adapter families exist in this repository, and why their details are not covered in this document**. Based on the inspected tree, the “Protocols & UI” category shows A2A, hosting-a2a, hosting-mcp, AG-UI, ChatKit, and DevUI, and Foundry Hosted Agents, OpenAI Responses-compatible hosting, and Telegram are also confirmed in the root README and the hosting sample tree. (Source: [Python AGENTS.md Protocols & UI list](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/AGENTS.md#L115-L121), [Root README Foundry Hosted Agents and DevUI](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/README.md#L48-L59))

## Boundary

The confirmed adapter families whose details are not covered in this document are as follows.

- OpenAI Responses-compatible hosting
- A2A
- AG-UI
- MCP hosting

These are each within the scope of a separate document. This section does not substitute a general provider catalog; it serves only as a **confirmation note that channel/protocol adapter families actually exist in the repo-local**. (Source: [Python AGENTS.md Protocols & UI list](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/AGENTS.md#L115-L121))

## Maturity

The maturity of this sub-feature group itself follows the package state covered in each separate document's scope. This document retains only a summary.

- A2A: Python `beta`, .NET `preview`
- AG-UI: Python `released`, .NET hosting `preview`
- MCP hosting helper: Python `alpha`
- OpenAI Responses hosting: Python `alpha`, .NET `alpha`/existing route-owning stack coexistence  
  (Source: [Python PACKAGE_STATUS excerpt](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L17-L20), [Python PACKAGE_STATUS hosting rows](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L37-L40))

## Public APIs and types

The purpose of this section is not a detailed API description. However, based on repo-local public exports:

- A2A: remote client wrapper + hosting helpers
- AG-UI: protocol adapter + endpoint/client
- MCP hosting: tool adapters
- Responses hosting: request/response helpers  
is confirmed. (Source: [Python AGENTS list](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/AGENTS.md#L115-L121))

## Detailed execution flow

The detailed flow is owned by each individual document. It is not repeated in this document.

## State and persistence

Since each feature has a different continuation/state model, this section does not provide a consolidated summary. The general theory of identity/session routing is also within the scope of a separate document.

## Extension points

Since each feature has different protocol-specific extension points, they are covered in separate documents.

## Concurrency, streaming, and cancellation

Since each feature has a different transport and event model, they are covered in separate documents.

## Errors, validation, and security

Since the trust boundary differs greatly for each feature, they are covered in separate documents.

## .NET implementation and tests

The repo-local source shows that the above protocol/UI adapter families actually exist. Detailed implementation and tests are within the scope of individual documents.

## Python implementation and tests

Python `AGENTS.md` and the package structure most directly demonstrate the existence of the channel/protocol adapter family. (Source: [Python AGENTS.md](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/AGENTS.md#L115-L121))

## Documentation differences

- This section is an intentional boundary note, so code/doc mismatch is not discussed.

## Java decisions

- In Java as well, channel/protocol adapters must be designed as an **optional artifact family separated from core**.
- Rather than a single “all channels” package, per-protocol artifacts better expose versioning, maturity, and security boundaries.

## Acceptance scenarios

1. Channel/protocol adapter families must be clearly identifiable just by looking at the repo structure.
2. Each adapter family must have an artifact boundary separated from core.
3. The document structure must also be separated per feature, and a single document must not mix all protocols together.

## Source inventory

- [python/AGENTS.md](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/AGENTS.md#L115-L121)
- [README.md](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/README.md#L48-L59)

## Conclusion

Foundry hosting, DevUI, Aspire integration, ChatKit, and Telegram represent different facets of the “operational/development/channel layer built on top of a generic hosting core” in this repository. Foundry is a managed runtime adapter, DevUI/Aspire are development tools and a multi-backend aggregator, and ChatKit and Telegram are Python-centric channel adapters. And while additional confirmed protocol/channel adapter families exist beyond these, the design direction of the repository leans toward maintaining them as **per-feature adapters** rather than combining them into a single monolithic host framework. In Java as well, following this principle, a composition that separates core, host adapter, dev tooling, and channel adapters is the most natural. (Source: [Root README feature bullets](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/README.md#L48-L59), [Python AGENTS Protocols & UI list](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/AGENTS.md#L115-L121))