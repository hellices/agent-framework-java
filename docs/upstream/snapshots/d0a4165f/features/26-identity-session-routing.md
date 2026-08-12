# 26. Identity / session routing

## Scope

This document covers Microsoft Agent Framework's **hosted identity / platform context**, **authorization responsibility**, **per-user isolation**, **risks of raw service session ID**, **route/link/multicast/session routing**, and **storage ownership**.  
The wire contracts for individual protocols such as OpenAI Responses, A2A, AG-UI, MCP, Foundry, and Telegram are already covered in each protocol's document; here, only **what kind of identifier each protocol produces and how the host must treat it** is compared to the extent necessary.  
Additionally, the general theory of the generic hosting lifecycle is within the scope of a separate document; this document focuses on “where to place the identifier, who should trust it, and what storage key it should be associated with.” (Source: [ADR-0027 security responsibilities](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0027-hosting-channels.md#L141-L160), [ADR-0027 session continuity](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0027-hosting-channels.md#L171-L205), [ADR-0029 identity lifetimes](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0029-python-agent-session-identity.md#L14-L33))

## Summary

The core principle of this repository is to **separate ids by lifecycle**. session/conversation, task, response/message, continuation token, and telemetry correlation are all distinct and must not be lumped into a single field or a single storage key merely because they are “needed to continue.” The Python ADR explicitly codifies this, deciding that **future-call continuation state belongs in durable session state**, **single-result identity is the response/message itself**, **unfinished work resume belongs in continuation token**, and **run correlation belongs in run/telemetry context**. As a result, raw `service_session_id` must remain a **service-owned opaque continuation handle** rather than a generic correlation field, and if a structured value is needed, the owner agent must interpret it. Authorization is the responsibility of the **host application**, not the framework, and `session_id`, `context_id`, `thread_id`, `conversation_id`, and `previous_response_id` returned by protocol helpers are all **untrusted candidate keys**. A multi-user host must not use these candidate keys as store keys until they have been bound to an authenticated principal/tenant/workspace/chat context. `.NET` implements this principle through the `AgentSessionStore` trust model, `IsolationKeyScopedAgentSessionStore`, `ClaimsIdentityAgentIsolationKeyProvider`, and Foundry Hosting's per-user/per-agent path partitioning. Python follows the same principle through helper-first documentation and `SessionStore`/`FileSessionStore`/Foundry state store/provider, and in A2A has in practice introduced structured service continuation such as `A2AServiceSessionId`. Route/link/multicast/active-channel routing is intentionally pushed outside the v1 minimal hosting core and remains only as a follow-up enhancement area. (Source: [ADR-0029 decision](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0029-python-agent-session-identity.md#L243-L267), [ADR-0029 service_session_id as richer service-owned value](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0029-python-agent-session-identity.md#L160-L193), [ADR-0027 trust boundary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0027-hosting-channels.md#L143-L160), [.NET AgentSessionStore trust model](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/AgentSessionStore.cs#L20-L45), [.NET Foundry per-user path partitioning ADR](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0031-hosted-per-user-session-storage-isolation.md#L53-L83), [Python A2A structured service_session_id](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/a2a/agent_framework_a2a/_agent.py#L59-L80), [ADR-0028 enhancements are follow-up only](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0028-hosting-linking-multicast-enhancements.md#L10-L15))

---

## Purpose

The purpose of the identity/session routing layer is to distinguish **which identifier is a value that continues what**, **which value is an opaque handle owned by whom**, **which value can become a store key**, and **which value must never be used as a generic correlation field**.  
This purpose has four concrete requirements.

1. **Preventing dangerous conflation**  
   `response_id`, `task_id`, `conversation_id`, `thread_id`, `service_session_id`, and `ContinuationToken` are not treated as interchangeable values. (Source: [ADR-0029 problem statement](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0029-python-agent-session-identity.md#L20-L33))

2. **Explicit trust boundary**  
   A protocol helper can extract candidate keys from a native payload, but whether those keys are used for actual persisted state lookup is decided by the host after authentication/authorization. (Source: [ADR-0027 application builder owns the trust boundary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0027-hosting-channels.md#L143-L156))

3. **per-user / per-tenant physical isolation**  
   As a defense-in-depth measure, guessed/forged ids are prevented from addressing another user's storage path in the first place. (Source: [ADR-0031 context/problem](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0031-hosted-per-user-session-storage-isolation.md#L14-L27), [ADR-0031 decision outcome path layout](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0031-hosted-per-user-session-storage-isolation.md#L53-L83))

4. **Separation of routing/linking/multicast follow-up**  
   cross-channel linking, active-channel routing, multicast delivery, and durable delivery runners share identity/storage/replay concerns but are treated as a separate follow-up layer from the v1 minimal core. (Source: [ADR-0028 enhancement areas](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0028-hosting-linking-multicast-enhancements.md#L24-L38), [ADR-0028 relationship to ADR-0027](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0028-hosting-linking-multicast-enhancements.md#L130-L132))

## Boundary

### What this document covers
- hosted/platform-provided identity context
- the nature of protocol-derived session/correlation ids
- store key ownership
- per-user isolation
- the distinction between trusted and untrusted identifiers
- how far the framework does not own route/link/multicast/session routing

### What this document does not cover
- the full request/response shape of each protocol
- agent/workflow execution internals
- general provider catalog theory
- detailed implementation of delivery codec / multicast durable runner
- specific channel UI behaviors

In other words, this document explains the **meaning and security boundaries of ids** per protocol. (Source: [ADR-0027 helper/session_id separation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0027-hosting-channels.md#L173-L191), [ADR-0028 candidate API names are design vocabulary only](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0028-hosting-linking-multicast-enhancements.md#L38-L39))

---

## Maturity

This area is a policy/abstraction spanning multiple layers rather than a single package, so maturity varies by individual implementation.

- The `.NET` generic session isolation helper (`Hosting.AspNetCore`, `IsolationKeyScopedAgentSessionStore`) is part of the `preview`-tier hosting stack. (Source: [Hosting.AspNetCore csproj](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AspNetCore/Microsoft.Agents.AI.Hosting.AspNetCore.csproj#L3-L29))
- `.NET` Foundry hosted identity/path partitioning sits atop the experimental Foundry hosting abstraction. (Source: [Foundry.Hosting csproj](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/Microsoft.Agents.AI.Foundry.Hosting.csproj#L5-L18))
- Python `SessionStore` / `FileSessionStore` is feature-level experimental. (Source: [Python PACKAGE_STATUS SESSION_STORE](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L139-L145))
- The route/link/multicast enhancement stack remains as a follow-up proposed direction. (Source: [ADR-0028 proposed direction](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0028-hosting-linking-multicast-enhancements.md#L74-L79))

---

## Public APIs and types

## Policy/design level
- Python ADR core concepts:
  - `AgentSession.session_id`
  - `AgentSession.service_session_id`
  - `ContinuationToken`
  - lifecycle-based identity split
  - `A2AServiceSessionId` as structured service-owned value  
  (Source: [ADR-0029 decision table and Option B](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0029-python-agent-session-identity.md#L245-L267), [ADR-0029 typed shape appendix](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0029-python-agent-session-identity.md#L295-L345))

## .NET
- `AgentSessionStore`
- `IsolationKeyScopedAgentSessionStore`
- `AgentIsolationKeyProvider`
- `ClaimsIdentityAgentIsolationKeyProvider`
- `HostedSessionContext`
- Foundry Hosting `AgentSessionStore` with `userId` parameter  
  (Source: [.NET AgentSessionStore](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/AgentSessionStore.cs#L48-L138), [.NET IsolationKeyScopedAgentSessionStore](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/IsolationKeyScopedAgentSessionStore.cs#L9-L115), [.NET ClaimsIdentityAgentIsolationKeyProvider](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AspNetCore/ClaimsIdentityAgentIsolationKeyProvider.cs#L13-L95), [.NET HostedSessionContext](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/HostedSessionContext.cs#L9-L52), [.NET Foundry AgentSessionStore with userId](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/AgentSessionStore.cs#L22-L96))

## Python
- `SessionStore`
- `FileSessionStore`
- `A2AServiceSessionId`
- deprecated compatibility type `A2AAgentSession`
- Foundry `FoundryAgentSessionStore` and context-scoped store providers  
  (Source: [Python SessionStore decision move to core](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0034-python-session-store-serialization.md#L192-L209), [Python A2AServiceSessionId and A2AAgentSession](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/a2a/agent_framework_a2a/_agent.py#L59-L80), [Foundry state stores](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/_state_store.py#L289-L335))

---

## Hosted identity / platform context

## Foundry hosted identity

### Design transition
The previous Foundry hosted identity was based on `IsolationContext` (`x-agent-user-isolation-key`, `x-agent-chat-isolation-key`), but has since changed to a `PlatformContext` (`x-agent-user-id`, `x-agent-foundry-call-id`) basis. The chat isolation key was removed and `HostedSessionContext` became a user-only type. (Source: [ADR-0030 context and breaking change](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0030-hosted-platform-context-agentserver-2.0.md#L15-L27), [ADR-0026 superseded note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0026-hosted-session-identity-context.md#L10-L13))

### .NET current implementation
`.NET` `PlatformHostedSessionIsolationKeyProvider` reads `ResponseContext.PlatformContext.UserIdKey` and converts it to `HostedSessionContext`. If this value is absent in a hosted environment, an error occurs; in a local environment, a null-permissive path exists. (Source: [.NET PlatformHostedSessionIsolationKeyProvider](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/PlatformHostedSessionIsolationKeyProvider.cs#L12-L45), [ADR-0031 local runs no longer fail closed](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0031-hosted-per-user-session-storage-isolation.md#L104-L119))

### Python current implementation
Python `validate_foundry_request_context(...)` requires `call_id` and `user_id` in a hosted environment. That is, hosted identity is enforced directly from the request context. (Source: [Python validate_foundry_request_context](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/_request_context.py#L41-L53))

---

## Authorization responsibility

The common framework principle is clear: **the application builder owns the trust boundary**.

- authenticate caller
- caller-supplied session/task/context/conversation/thread/response id authorize
- bind to an authorized principal/tenant/workspace/chat context
- parsed command/action effects authorize
- media/resource/file URL resolution is an explicit opt-in

This principle is the reason for separating a helper package “extracting the candidate key” from “opening the actual state with that key.” (Source: [ADR-0027 security responsibilities](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0027-hosting-channels.md#L141-L160))

### `.NET` specifics
The `.NET` `AgentSessionStore` comment makes clear that `sessionStoreId` is only a wire-originated chain-resume id, not an authorization token, and explains that a multi-user host must compose a principal dimension into the key. (Source: [.NET AgentSessionStore trust model](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/AgentSessionStore.cs#L20-L35))

### Python specifics
The Python hosting README also explains that `AgentState` passes the app-selected ID directly to the store, and each store implementation owns backend-specific validation/normalization. That is, the route/app performs authorization and the generic state helper passes the opaque key through unchanged. (Source: [Python hosting README opaque IDs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting/README.md#L24-L29))

---

## Raw service session ID risks

The Python ADR very strongly states that `service_session_id` must **not be used as a generic correlation field**. This value is a “service-owned value that lets that service continue a conversation, session, or thread” and must not be generically parsed or understood by other agent types or telemetry layers. (Source: [ADR-0029 service_session_id as opaque service-owned handle](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0029-python-agent-session-identity.md#L14-L19))

Why it is risky:

1. **lifecycle confusion**  
   `service_session_id` is future-call continuation state, not the same as response identity, run correlation, or task identity. (Source: [ADR-0029 lifecycle split](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0029-python-agent-session-identity.md#L247-L257))

2. **agent-specific shape**  
   OpenAI Responses typically requires only a single string, but A2A requires multi-field state such as `context_id + task_id + task_state`. If generic code directly parses this shape, provider-specific coupling is introduced. (Source: [ADR-0029 concrete gap example](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0029-python-agent-session-identity.md#L45-L66), [ADR-0029 Option B rationale](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0029-python-agent-session-identity.md#L160-L193))

3. **telemetry misuse risk**  
   OpenTelemetry `gen_ai.conversation.id` must be extracted by the owner agent via a hook such as `_get_otel_conversation_id(session)`; generic telemetry code must not parse structured `service_session_id`. (Source: [ADR-0029 telemetry extractor rule](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0029-python-agent-session-identity.md#L265-L268), [appendix implementation note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0029-python-agent-session-identity.md#L316-L345))

### Current implementation example: A2A
Python A2A has in practice introduced `A2AServiceSessionId` and is pushing `A2AAgentSession` to deprecated status. This is current implementation evidence that “structured service continuation that a raw single string cannot represent” must not be mixed with generic correlation. (Source: [Python A2AServiceSessionId](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/a2a/agent_framework_a2a/_agent.py#L59-L64), [deprecated A2AAgentSession](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/a2a/agent_framework_a2a/_agent.py#L67-L80), [sync into service_session_id](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/a2a/agent_framework_a2a/_agent.py#L171-L180))

---

## Per-user isolation

## Why a logical check alone is not sufficient

As ADR-0031 explains, if only a strict-resume identity check is present, **only after a forged `conversation_id` first resolves another user's file path** does the mismatch become apparent. Physical path partitioning is required for defense in depth. (Source: [ADR-0031 problem statement](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0031-hosted-per-user-session-storage-isolation.md#L14-L27))

## `.NET` implementation

### Generic multi-user isolation
`.NET` generic hosting uses `IsolationKeyScopedAgentSessionStore` to prepend an isolation key to the sessionStoreId. In strict mode it fails closed when the key is absent; in non-strict mode it passes through. (Source: [IsolationKeyScopedAgentSessionStore](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/IsolationKeyScopedAgentSessionStore.cs#L16-L115))

### ASP.NET claims-based identity source
The ASP.NET helper extracts the isolation key from the authenticated principal's claims. The default claim type is `ClaimTypes.NameIdentifier`, and it explicitly states that mutable/non-unique display-name-class claims are unsafe. (Source: [ClaimsIdentityAgentIsolationKeyProvider security warning](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AspNetCore/ClaimsIdentityAgentIsolationKeyProvider.cs#L19-L34), [GetIsolationKeyAsync only on authenticated principal](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AspNetCore/ClaimsIdentityAgentIsolationKeyProvider.cs#L78-L95))

### Foundry hosted physical partition
Foundry Hosting goes further, constructing the path itself as:
`{root}/a-{agent}/u-{userId}/c-{contextId}.json`
The `userId` is treated as an untrusted platform-injected partition key, validated as a safe path segment, and the final path is verified to be under the storage root. (Source: [ADR-0031 path layout](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0031-hosted-per-user-session-storage-isolation.md#L53-L83), [FileSystemAgentSessionStore.GetSessionPath](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/FileSystemAgentSessionStore.cs#L234-L279))

## Python implementation

### Opaque key pass-through
Python generic hosting passes the app-selected opaque key directly to the store. `SessionStore` itself has no knowledge of per-user isolation. (Source: [Python hosting README opaque IDs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting/README.md#L24-L29))

### FileSessionStore
Python `FileSessionStore` encodes opaque keys as filename-safe stems and provides file-backed persistence including path traversal defense. ADR-0034 explains that values such as `telegram:<bot-id>:<chat-id>` were intended to be supported directly as keys. (Source: [ADR-0034 SessionStore accepts opaque keys](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0034-python-session-store-serialization.md#L205-L209), [ADR-0034 FoundrySessionStore historical note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0034-python-session-store-serialization.md#L212-L232))

### Foundry per-user isolation
Python Foundry stores use `FoundryStateStore.get_or_create(..., user_isolation=True)` to delegate physical per-user partitioning to the platform storage layer. (Source: [FoundryCheckpointStore user_isolation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/_state_store.py#L80-L84), [FoundryFunctionApprovalStore user_isolation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/_state_store.py#L225-L226), [FoundryAgentSessionStore user_isolation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/_state_store.py#L294-L295))

---

## Session continuity and per-protocol comparison

ADR-0027 summarizes session continuity as “run parsing and isolation/session id selection must be kept as separate stages.” The isolation source can be one of several things.

- protocol input (`previous_response_id`, Telegram chat id, etc.)
- running environment (Foundry hosted user isolation)
- app-specific trusted middleware or route state  
  (Source: [ADR-0027 session continuity sources](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0027-hosting-channels.md#L171-L191))

The following is a minimum per-protocol comparison of this principle.

### OpenAI Responses
- `previous_response_id` is a rotating chain pointer
- `conversation`/`conversation_id` is a stable mutable head
- Both are request-derived candidate keys and require host authorization  
  (Source: [ADR-0029 OpenAI current implementation note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0029-python-agent-session-identity.md#L77-L80), [ADR-0027 examples](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0027-hosting-channels.md#L180-L188))

### A2A
- durable future-call state is `context_id + task_id + task_state`
- `reference_task_ids` is current request intent, not durable state
- in-progress work resume is continuation token  
  (Source: [ADR-0029 A2A current notes](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0029-python-agent-session-identity.md#L70-L79), [ADR-0029 appendix on task_id vs reference_task_ids](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0029-python-agent-session-identity.md#L270-L293))

### AG-UI
- `thread_id` is a wrapper-owned conversation key
- `run_id` is per-run event correlation
- AG-UI is covered in detail in a separate protocol document, but ADR-0029 already draws the line that `thread_id` maps to `AgentSession.session_id` and `run_id` is closer to wrapper-owned event correlation.  
  (Source: [ADR-0029 AG-UI out of scope note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0029-python-agent-session-identity.md#L41-L43))

### Foundry hosted
- platform-provided `user_id`/`call_id` are values from the request environment
- Raw spoofable headers must not be used as trusted hosted isolation in local/non-hosted situations.  
  (Source: [ADR-0027 Foundry-specific warning](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0027-hosting-channels.md#L162-L164), [.NET PlatformHostedSessionIsolationKeyProvider local behavior](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/PlatformHostedSessionIsolationKeyProvider.cs#L17-L25))

### Telegram
- A helper can produce a natural continuation key, but it can still only become a durable state key after host authorization.  
  (Source: [ADR-0027 Telegram session_id example](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0027-hosting-channels.md#L184-L185))

---

## Route / link / multicast / session routing

## Current v1 scope

ADR-0027 and the Python hosting README explicitly state that the following are **absent** from v1.

- cross-channel identity linking
- multicast delivery
- background delivery runners
- durable delivery/replay semantics
- framework-owned proactive/non-originating sends

In other words, “which session/output to fan out to which channel/route” is not the responsibility of the current generic hosting core. (Source: [ADR-0027 outcome and non-goals](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0027-hosting-channels.md#L62-L103), [Python hosting README follow-up enhancements note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting/README.md#L143-L145))

## Follow-up enhancement areas

ADR-0028 separates identity linking, authorization/allowlist, active-channel routing, multicast/all-linked delivery, background runs, durable delivery runners, retry/replay, and payload serialization into the **follow-up layered packages** area. That is, these share session routing/storage ownership concerns but are not yet part of the v1 public contract. (Source: [ADR-0028 enhancement areas](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0028-hosting-linking-multicast-enhancements.md#L24-L38), [Option C layered opt-in packages](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0028-hosting-linking-multicast-enhancements.md#L58-L64), [proposed direction](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0028-hosting-linking-multicast-enhancements.md#L74-L79), [storage requirements](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0028-hosting-linking-multicast-enhancements.md#L107-L115))

## Storage ownership

Even if the route/link/multicast layer moves to a future package, the storage ownership principles are already evident.

- Base `AgentSession` history / workflow checkpoint storage and enhancement storage must be separate.
- link records, active-channel state, delivery attempts, dead letters, and serialized payloads require independent TTL/deletion policies.  
  (Source: [ADR-0028 storage section](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0028-hosting-linking-multicast-enhancements.md#L107-L115))

---

## Storage ownership

## Generic principle

The key question for the Store is **who owns the key contract**.

- The host/application selects and authorizes the key.
- The generic state helper passes the opaque key through unchanged.
- The store implementation owns backend-specific validation/normalization.  
  (Source: [Python hosting README opaque keys and store ownership](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting/README.md#L24-L29), [.NET AgentSessionStore implementer guidance](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/AgentSessionStore.cs#L38-L45))

## `.NET` generic store ownership
`.NET` `AgentSessionStore`:
- requires treating `sessionStoreId` as opaque
- requires that a multi-user host attach a scoping decorator, because there is no principal/owner dimension  
  (Source: [.NET AgentSessionStore trust and implementer guidance](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/AgentSessionStore.cs#L20-L45))

## Python store ownership
Python `SessionStore` requires only a non-empty string, and `FileSessionStore` is designed to accommodate keys that cannot be encoded as a portable filename stem. In other words, the key contract follows an **application chooses, store validates/encodes** model. (Source: [Python SessionStore validate_session_id](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_sessions.py#L1711-L1723), [ADR-0034 opaque key decision](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0034-python-session-store-serialization.md#L205-L209))

## Hosted physical storage
In a hosted runtime, storage ownership is enforced one level more strictly.

- `.NET` Foundry: host-owned file layout `{root}/a-{agent}/u-{user}/c-{context}.json`
- Python Foundry: platform-owned `FoundryStateStore(..., user_isolation=True)` scope  
  (Source: [.NET FileSystemAgentSessionStore layout](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/FileSystemAgentSessionStore.cs#L236-L279), [Python Foundry session store](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/_state_store.py#L289-L308))

---

## Concurrency, streaming, and cancellation

From an identity/session routing perspective, the important points are **persist timing and single-writer ownership**.

- ADR-0027 requires persisting post-run session or checkpoint state only after `run(...)` or stream finalization.
- The host must serialize concurrent writers for a stable mutable head (`conversation`, per-chat session, AG-UI thread head, etc.).
- in-progress resume token must be separate from durable session state.  
  (Source: [ADR-0027 persist after run/finalization](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0027-hosting-channels.md#L157-L160), [ADR-0029 lifecycle split](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0029-python-agent-session-identity.md#L247-L257))

Foundry hosted `.NET` persists the session in a finally block after response stream termination, and the Python helper-first model also requires post-run persistence as shown in samples/README. This demonstrates that identifier semantics and persist timing are tightly coupled. (Source: [.NET Foundry handler finally save](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/AgentFrameworkResponseHandler.cs#L477-L486), [Python hosting README post-run session store](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting/README.md#L86-L102))

---

## Errors, validation, and security

## Common security principles
- ids extracted by a protocol helper are untrusted candidate keys
- a platform-provided isolation helper must be fail-closed or have an explicit fallback outside a trusted environment
- a path-based store requires path traversal defense
- logs/telemetry may surface opaque keys or isolation prefixes, so caution is required  
  (Source: [ADR-0027 trust boundary bullets](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0027-hosting-channels.md#L150-L159), [.NET AgentSessionStore logging note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/AgentSessionStore.cs#L43-L45), [ADR-0031 path traversal guard](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0031-hosted-per-user-session-storage-isolation.md#L74-L79))

## `.NET` validation points
- `ClaimsIdentityAgentIsolationKeyProvider` does not use the display name claim by default.
- `IsolationKeyScopedAgentSessionStore` throws an exception on a null key in strict mode.  
  (Source: [ClaimsIdentityAgentIsolationKeyProviderTests ignores name claim by default](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.UnitTests/ClaimsIdentityAgentIsolationKeyProviderTests.cs#L115-L132), [IsolationKeyScopedAgentSessionStoreTests strict mode](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.UnitTests/IsolationKeyScopedAgentSessionStoreTests.cs#L97-L114))

## Python validation points
- Both structured `service_session_id` and deprecated `A2AAgentSession` must be round-trip capable.
- The `SessionStore` key must satisfy the opaque non-empty contract.
- Foundry context validation must fail fast in a hosted environment.  
  (Source: [Python A2A session shape and sync](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/a2a/agent_framework_a2a/_agent.py#L116-L180), [Python SessionStore validate_session_id](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_sessions.py#L1711-L1723), [Python Foundry request context validation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/_request_context.py#L41-L53))

---

## .NET / Python differences

1. **Generic isolation strategy**
   - `.NET`: explicit isolation key provider + scoping decorator
   - Python: helper-first + opaque key pass-through + backend-specific provider/store logic  
   (Source: [.NET IsolationKeyScopedAgentSessionStore](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/IsolationKeyScopedAgentSessionStore.cs#L9-L115), [Python hosting README opaque IDs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting/README.md#L24-L29))

2. **Structured service continuation**
   - Python ADR and current A2A implementation actively adopt structured `service_session_id`
   - `.NET` still retains a protocol-specific session subclass (`A2AAgentSession`)  
   (Source: [ADR-0029 Option B chosen](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0029-python-agent-session-identity.md#L259-L267), [Python A2AServiceSessionId](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/a2a/agent_framework_a2a/_agent.py#L59-L64), [.NET A2AAgentSession](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.A2A/A2AAgentSession.cs#L12-L46))

3. **Foundry per-user isolation**
   - `.NET`: directly creates agent/user/context layers in the app file path layout
   - Python: delegates to the platform storage API `user_isolation=True`  
   (Source: [.NET FileSystemAgentSessionStore layout](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/FileSystemAgentSessionStore.cs#L236-L279), [Python FoundryStateStore user_isolation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/_state_store.py#L294-L295))

4. **Local fallback behavior**
   - `.NET` Foundry hosted identity provider treats null user id as a tolerated path in local non-hosted runs
   - Python Foundry request validator accepts a hosted flag and applies strict validation only when hosted  
   (Source: [.NET PlatformHostedSessionIsolationKeyProvider local remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/PlatformHostedSessionIsolationKeyProvider.cs#L17-L25), [Python validate_foundry_request_context signature and behavior](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/_request_context.py#L41-L53))

---

## .NET implementation and tests

## Implementation
- generic trust model: `AgentSessionStore`
- multi-user scoping: `IsolationKeyScopedAgentSessionStore`
- ASP.NET identity source: `ClaimsIdentityAgentIsolationKeyProvider`
- Foundry hosted platform context: `HostedSessionContext`, `PlatformHostedSessionIsolationKeyProvider`
- Foundry physical partitioning: file path layout with agent/user/context layers  
  (Source: [.NET AgentSessionStore](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/AgentSessionStore.cs#L20-L45), [.NET IsolationKeyScopedAgentSessionStore](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/IsolationKeyScopedAgentSessionStore.cs#L51-L115), [.NET ClaimsIdentityAgentIsolationKeyProvider](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AspNetCore/ClaimsIdentityAgentIsolationKeyProvider.cs#L19-L95), [.NET PlatformHostedSessionIsolationKeyProvider](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/PlatformHostedSessionIsolationKeyProvider.cs#L12-L45), [.NET FileSystemAgentSessionStore](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/FileSystemAgentSessionStore.cs#L234-L279))

## Tests
- `ClaimsIdentityAgentIsolationKeyProviderTests` validates the default claim source and the ignoring of name claims. (Source: [ClaimsIdentityAgentIsolationKeyProviderTests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.UnitTests/ClaimsIdentityAgentIsolationKeyProviderTests.cs#L101-L167))
- `IsolationKeyScopedAgentSessionStoreTests` validates strict/non-strict behavior and key rewriting. (Source: [IsolationKeyScopedAgentSessionStoreTests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.UnitTests/IsolationKeyScopedAgentSessionStoreTests.cs#L97-L220))
- `HostedSessionIdentityContextTests` validates Foundry hosted context stamping, the local permissive path, and mismatch 403. (Source: [HostedSessionIdentityContextTests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Foundry.Hosting.UnitTests/HostedSessionIdentityContextTests.cs#L25-L239))

---

## Python implementation and tests

## Implementation
- ADR-0029 provides the basis for lifecycle-based identity split
- `SessionStore` / `FileSessionStore` uses opaque key contract
- `A2AServiceSessionId` is the current implementation example of structured `service_session_id`
- Foundry stores delegate per-user isolation to the platform storage  
  (Source: [ADR-0029 decision](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0029-python-agent-session-identity.md#L243-L267), [Python A2AServiceSessionId current implementation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/a2a/agent_framework_a2a/_agent.py#L59-L80), [Python Foundry stores](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/_state_store.py#L289-L335))

## Tests
- Foundry state store tests validate context-scoped store/provider behavior and hosted storage shape. (Source: [Python test_state_store](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/tests/test_state_store.py#L63-L229))
- protocol-specific tests (A2A/Responses/AG-UI/Telegram, etc.) indirectly validate the candidate session key semantics produced by each protocol, but in this document that is delegated to the respective protocol documents.

---

## Documentation differences

The most important code-first difference is that the Python design documents have formally chosen the direction of extending `service_session_id` to a richer typed value. The current A2A implementation has in practice adopted this direction, but looking only at the generic hosting README, the simple opaque key model still appears more prominently. That is, “simple opaque string continuation” and “structured service-owned continuation” differ in emphasis depending on the documentation layer. However, this is better understood not as a contradiction but as a difference in focus between the design level and the generic usage guide level. (Source: [ADR-0029 Option B chosen](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0029-python-agent-session-identity.md#L259-L267), [Python hosting README opaque keys](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting/README.md#L24-L29), [Python current A2A implementation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/a2a/agent_framework_a2a/_agent.py#L59-L80))

Additionally, the route/link/multicast area is not often mentioned in README or high-level documents, but looking at ADR-0028, it is clearly isolated as a “follow-up enhancement stack outside the v1 core.” Therefore, one must not assume that these features are implicitly present by looking only at generic hosting. (Source: [ADR-0028 context and option C](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0028-hosting-linking-multicast-enhancements.md#L10-L15), [ADR-0028 proposed direction](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0028-hosting-linking-multicast-enhancements.md#L74-L79))

---

## Java / Spring host decisions

## Basic principles

1. **Separate session key extraction from authorization binding**.  
   The helper that parses `threadId`, `contextId`, `conversationId`, `taskId`, and `serviceSessionId` and the Spring host filter/interceptor that binds them to an authenticated principal/tenant must be अलग artifacts or at a minimum different layers.

2. **Support structured service continuation**.  
   Java's `AgentSession.serviceSessionId` must also be able to accommodate a provider-owned structured type, not just a `String`. In particular, multi-field continuation such as A2A is better represented as a separate typed record/class.

3. **Implement principal-derived isolation** only in the Spring host module**.  
   Example:
   - `Authentication` / JWT claims → isolation key
   - session store decorator
   - per-route candidate key authorization
   - snapshot scope / tenant binding

4. **Do not include route/link/multicast in core.**  
   Separate them into future enhancement modules (`hosting-linking`, `hosting-multicast`), with independent TTL/dead-letter policies from base session/checkpoint storage.

## Recommended modules
- `agent-framework-core`
- `agent-framework-hosting-core`
- `agent-framework-hosting-spring`
- `agent-framework-hosting-foundry`
- future:
  - `agent-framework-hosting-linking`
  - `agent-framework-hosting-multicast`
  - `agent-framework-hosting-delivery`

---

## Acceptance scenarios

1. **Untrusted candidate key**
   - Even if a protocol helper extracts `session_id`/`context_id`/`thread_id`, the host must not use them for store lookup until principal binding is complete.

2. **Opaque vs structured continuation**
   - A simple provider must be able to retain a raw string `service_session_id`.
   - A multi-field provider must be able to store and restore a structured `service_session_id`.
   - Telemetry must read only the primary conversation id through the owner agent's extractor.

3. **Per-user isolation**
   - A multi-user host must use a principal-scoped composite key instead of a bare sessionStoreId.
   - In a Foundry-style hosted runtime, a guessed id must not be able to address another user's storage path.

4. **Local fallback**
   - A local development path without a trusted hosted platform context must operate only with explicit local behavior (e.g., null user partition) and must not treat spoofable raw headers as production isolation.

5. **Post-run persistence**
   - The host must persist session or checkpoint state only after run/stream finalization.
   - For a stable key with mutable head semantics, the host must provide single-writer coordination.

6. **Route/link/multicast separation**
   - The v1 hosting core must not include cross-channel identity linking, active-channel routing, multicast fan-out, or durable delivery runners.
   - Future enhancement storage must have a separate lifecycle policy from base `AgentSession`/checkpoint storage.

7. **Path traversal / storage safety**
   - When a user-derived partition key is used as a file path, single safe path component validation and root-boundary verification must be present.

---

## Source inventory

### Docs
- [docs/decisions/0027-hosting-channels.md](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0027-hosting-channels.md#L141-L205)
- [docs/decisions/0028-hosting-linking-multicast-enhancements.md](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0028-hosting-linking-multicast-enhancements.md#L10-L132)
- [docs/decisions/0029-python-agent-session-identity.md](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0029-python-agent-session-identity.md#L14-L345)
- [docs/decisions/0030-hosted-platform-context-agentserver-2.0.md](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0030-hosted-platform-context-agentserver-2.0.md#L15-L84)
- [docs/decisions/0031-hosted-per-user-session-storage-isolation.md](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0031-hosted-per-user-session-storage-isolation.md#L14-L119)
- [docs/decisions/0034-python-session-store-serialization.md](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0034-python-session-store-serialization.md#L192-L240)

### .NET production source
- [dotnet/src/Microsoft.Agents.AI.Hosting/AgentSessionStore.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/AgentSessionStore.cs#L15-L138)
- [dotnet/src/Microsoft.Agents.AI.Hosting/IsolationKeyScopedAgentSessionStore.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/IsolationKeyScopedAgentSessionStore.cs#L9-L115)
- [dotnet/src/Microsoft.Agents.AI.Hosting.AspNetCore/ClaimsIdentityAgentIsolationKeyProvider.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AspNetCore/ClaimsIdentityAgentIsolationKeyProvider.cs#L13-L95)
- [dotnet/src/Microsoft.Agents.AI.Hosting.AspNetCore/ServiceCollectionExtensions.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AspNetCore/ServiceCollectionExtensions.cs#L13-L62)
- [dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/HostedSessionContext.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/HostedSessionContext.cs#L9-L52)
- [dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/PlatformHostedSessionIsolationKeyProvider.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/PlatformHostedSessionIsolationKeyProvider.cs#L12-L45)
- [dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/AgentSessionStore.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/AgentSessionStore.cs#L11-L97)
- [dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/FileSystemAgentSessionStore.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/FileSystemAgentSessionStore.cs#L234-L279)

### .NET tests
- [dotnet/tests/Microsoft.Agents.AI.Hosting.UnitTests/ClaimsIdentityAgentIsolationKeyProviderTests.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.UnitTests/ClaimsIdentityAgentIsolationKeyProviderTests.cs#L101-L220)
- [dotnet/tests/Microsoft.Agents.AI.Hosting.UnitTests/IsolationKeyScopedAgentSessionStoreTests.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.UnitTests/IsolationKeyScopedAgentSessionStoreTests.cs#L97-L220)
- [dotnet/tests/Microsoft.Agents.AI.Foundry.Hosting.UnitTests/HostedSessionIdentityContextTests.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Foundry.Hosting.UnitTests/HostedSessionIdentityContextTests.cs#L25-L260)

### Python production source
- [python/packages/hosting/README.md](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting/README.md#L24-L40)
- [python/packages/core/agent_framework/_sessions.py](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_sessions.py#L1692-L1767)
- [python/packages/a2a/agent_framework_a2a/_agent.py](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/a2a/agent_framework_a2a/_agent.py#L59-L80)
- [python/packages/foundry_hosting/agent_framework_foundry_hosting/_request_context.py](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/_request_context.py#L17-L53)
- [python/packages/foundry_hosting/agent_framework_foundry_hosting/_state_store.py](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/_state_store.py#L63-L335)
- [python/PACKAGE_STATUS.md](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L139-L145)

## Conclusion

The identity/session routing design of this repository follows the philosophy not of “storing all continuation-related values in one place,” but of **first defining the lifecycle and trust boundary of each value and then determining the appropriate storage location and ownership**. `service_session_id` is not a generic correlation field, and session/task/response/continuation/run correlation are distinct layers. The host is responsible for deciding which of these values to use as actual persisted state keys, which principal/tenant to bind them to, and how to partition them in which storage. `.NET` implements this philosophy through scoping decorators, claims-based providers, and Foundry physical path partitioning, and Python follows the same direction through lifecycle-based ADR, opaque key store contract, structured `service_session_id`, and hosted `user_isolation=True` store. A Java/Spring host must also follow these same principles and separate **candidate key extraction**, **authorization binding**, **storage partitioning**, and **future linking/multicast layers**. (Source: [ADR-0029 decision](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0029-python-agent-session-identity.md#L243-L267), [ADR-0027 trust boundary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0027-hosting-channels.md#L143-L160), [ADR-0031 decision outcome](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0031-hosted-per-user-session-storage-isolation.md#L53-L83), [ADR-0028 layered future routing/delivery stack](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0028-hosting-linking-multicast-enhancements.md#L74-L79))