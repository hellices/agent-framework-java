# 26. Identity / session routing

## 범위

이 문서는 Microsoft Agent Framework의 **hosted identity / platform context**, **authorization responsibility**, **per-user isolation**, **raw service session ID의 위험**, **route/link/multicast/session routing**, **storage ownership**을 다룬다.  
OpenAI Responses, A2A, AG-UI, MCP, Foundry, Telegram 등 개별 protocol의 wire contract는 이미 각 protocol 문서에서 다루므로, 여기서는 각 protocol이 **어떤 종류의 identifier를 내놓고 그것을 host가 어떻게 취급해야 하는지**만 필요한 범위에서 비교한다.  
또한 generic hosting lifecycle 일반론은 별도 문서 범위이며, 이 문서는 “identifier를 어디에 두고, 누가 신뢰하고, 어떤 storage key로 연결해야 하는가”에 집중한다. (출처: [ADR-0027 security responsibilities](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0027-hosting-channels.md#L141-L160), [ADR-0027 session continuity](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0027-hosting-channels.md#L171-L205), [ADR-0029 identity lifetimes](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0029-python-agent-session-identity.md#L14-L33))

## 요약

이 저장소의 핵심 원칙은 **id를 생명주기별로 분리**하는 것이다. session/conversation, task, response/message, continuation token, telemetry correlation은 모두 다르며, “계속하기 위해 필요하다”는 이유만으로 하나의 필드나 하나의 storage key에 뭉쳐서는 안 된다. Python ADR는 이를 명시적으로 정리해, **future-call continuation state는 durable session state**, **single-result identity는 response/message 자체**, **unfinished work resume은 continuation token**, **run correlation은 run/telemetry context**에 두라고 결정했다. 그 결과 raw `service_session_id`는 generic correlation field가 아니라 **service-owned opaque continuation handle**로 남아야 하며, structured value가 필요하면 owner agent가 해석해야 한다. Authorization은 framework가 아니라 **host application의 책임**이고, protocol helper가 돌려주는 `session_id`, `context_id`, `thread_id`, `conversation_id`, `previous_response_id`는 모두 **untrusted candidate key**다. Multi-user host는 이 candidate key를 authenticated principal/tenant/workspace/chat context에 bind하기 전까지 store key로 써서는 안 된다. `.NET`은 이 원칙을 `AgentSessionStore` trust model과 `IsolationKeyScopedAgentSessionStore`, `ClaimsIdentityAgentIsolationKeyProvider`, Foundry Hosting의 per-user/per-agent path partitioning으로 구현한다. Python은 helper-first 문서와 `SessionStore`/`FileSessionStore`/Foundry state store/provider를 통해 같은 원칙을 따르며, A2A에서는 `A2AServiceSessionId`처럼 structured service continuation을 실제로 도입했다. Route/link/multicast/active-channel routing은 의도적으로 v1 최소 hosting core 밖으로 밀려나 있으며, follow-up enhancement 영역으로만 남아 있다. (출처: [ADR-0029 decision](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0029-python-agent-session-identity.md#L243-L267), [ADR-0029 service_session_id as richer service-owned value](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0029-python-agent-session-identity.md#L160-L193), [ADR-0027 trust boundary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0027-hosting-channels.md#L143-L160), [.NET AgentSessionStore trust model](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/AgentSessionStore.cs#L20-L45), [.NET Foundry per-user path partitioning ADR](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0031-hosted-per-user-session-storage-isolation.md#L53-L83), [Python A2A structured service_session_id](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/a2a/agent_framework_a2a/_agent.py#L59-L80), [ADR-0028 enhancements are follow-up only](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0028-hosting-linking-multicast-enhancements.md#L10-L15))

---

## 목적

Identity/session routing 계층의 목적은 **어떤 identifier가 무엇을 계속하는 값인지**, **어떤 값이 누가 소유하는 opaque handle인지**, **어떤 값이 store key가 될 수 있는지**, **어떤 값은 절대 generic correlation field로 쓰면 안 되는지**를 분리하는 것이다.  
이 목적은 네 가지 실질 요구를 가진다.

1. **위험한 혼용 방지**  
   `response_id`, `task_id`, `conversation_id`, `thread_id`, `service_session_id`, `ContinuationToken`을 상호 교환 가능한 값처럼 다루지 않는다. (출처: [ADR-0029 problem statement](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0029-python-agent-session-identity.md#L20-L33))

2. **trust boundary 명시화**  
   protocol helper는 native payload에서 candidate key를 뽑아 줄 수 있지만, 그 key를 실제 persisted state lookup에 쓰는지 여부는 host가 인증/인가 후 결정한다. (출처: [ADR-0027 application builder owns the trust boundary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0027-hosting-channels.md#L143-L156))

3. **per-user / per-tenant physical isolation**  
   defense-in-depth 차원에서 guessed/forged id가 다른 사용자 스토리지 path 자체를 가리키지 못하게 한다. (출처: [ADR-0031 context/problem](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0031-hosted-per-user-session-storage-isolation.md#L14-L27), [ADR-0031 decision outcome path layout](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0031-hosted-per-user-session-storage-isolation.md#L53-L83))

4. **routing/linking/multicast follow-up 분리**  
   cross-channel linking, active-channel routing, multicast delivery, durable delivery runners는 identity/storage/replay 문제를 공유하지만, v1 최소 core와 분리된 후속 계층으로 다룬다. (출처: [ADR-0028 enhancement areas](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0028-hosting-linking-multicast-enhancements.md#L24-L38), [ADR-0028 relationship to ADR-0027](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0028-hosting-linking-multicast-enhancements.md#L130-L132))

## 경계

### 이 문서가 다루는 것
- hosted/platform-provided identity context
- protocol-derived session/correlation ids의 성격
- store key ownership
- per-user isolation
- trusted vs untrusted identifier 구분
- route/link/multicast/session routing을 어디까지 framework가 소유하지 않는지

### 이 문서가 다루지 않는 것
- 각 protocol의 full request/response shape
- agent/workflow execution internals
- provider catalog 일반론
- delivery codec / multicast durable runner 상세 구현
- specific channel UI behaviors

즉 이 문서는 protocol별 **id의 의미와 보안 경계**를 설명하는 문서다. (출처: [ADR-0027 helper/session_id separation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0027-hosting-channels.md#L173-L191), [ADR-0028 candidate API names are design vocabulary only](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0028-hosting-linking-multicast-enhancements.md#L38-L39))

---

## 성숙도

이 영역은 하나의 package가 아니라 여러 계층에 걸친 정책/abstraction이므로, maturity는 개별 구현에 따라 다르다.

- `.NET` generic session isolation helper (`Hosting.AspNetCore`, `IsolationKeyScopedAgentSessionStore`)는 `preview` 계열 hosting stack 일부다. (출처: [Hosting.AspNetCore csproj](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AspNetCore/Microsoft.Agents.AI.Hosting.AspNetCore.csproj#L3-L29))
- `.NET` Foundry hosted identity/path partitioning은 experimental Foundry hosting abstraction 위에 놓여 있다. (출처: [Foundry.Hosting csproj](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/Microsoft.Agents.AI.Foundry.Hosting.csproj#L5-L18))
- Python `SessionStore` / `FileSessionStore`는 feature-level experimental이다. (출처: [Python PACKAGE_STATUS SESSION_STORE](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L139-L145))
- route/link/multicast enhancement stack은 follow-up proposed direction으로 남아 있다. (출처: [ADR-0028 proposed direction](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0028-hosting-linking-multicast-enhancements.md#L74-L79))

---

## 공개 API·타입

## 정책/설계 레벨
- Python ADR 기준 핵심 개념:
  - `AgentSession.session_id`
  - `AgentSession.service_session_id`
  - `ContinuationToken`
  - lifecycle-based identity split
  - `A2AServiceSessionId` as structured service-owned value  
  (출처: [ADR-0029 decision table and Option B](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0029-python-agent-session-identity.md#L245-L267), [ADR-0029 typed shape appendix](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0029-python-agent-session-identity.md#L295-L345))

## .NET
- `AgentSessionStore`
- `IsolationKeyScopedAgentSessionStore`
- `AgentIsolationKeyProvider`
- `ClaimsIdentityAgentIsolationKeyProvider`
- `HostedSessionContext`
- Foundry Hosting `AgentSessionStore` with `userId` parameter  
  (출처: [.NET AgentSessionStore](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/AgentSessionStore.cs#L48-L138), [.NET IsolationKeyScopedAgentSessionStore](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/IsolationKeyScopedAgentSessionStore.cs#L9-L115), [.NET ClaimsIdentityAgentIsolationKeyProvider](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AspNetCore/ClaimsIdentityAgentIsolationKeyProvider.cs#L13-L95), [.NET HostedSessionContext](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/HostedSessionContext.cs#L9-L52), [.NET Foundry AgentSessionStore with userId](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/AgentSessionStore.cs#L22-L96))

## Python
- `SessionStore`
- `FileSessionStore`
- `A2AServiceSessionId`
- deprecated compatibility type `A2AAgentSession`
- Foundry `FoundryAgentSessionStore` and context-scoped store providers  
  (출처: [Python SessionStore decision move to core](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0034-python-session-store-serialization.md#L192-L209), [Python A2AServiceSessionId and A2AAgentSession](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/a2a/agent_framework_a2a/_agent.py#L59-L80), [Foundry state stores](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/_state_store.py#L289-L335))

---

## Hosted identity / platform context

## Foundry hosted identity

### 설계 전환
기존 Foundry hosted identity는 `IsolationContext` (`x-agent-user-isolation-key`, `x-agent-chat-isolation-key`) 기반이었으나, 현재는 `PlatformContext` (`x-agent-user-id`, `x-agent-foundry-call-id`) 기반으로 바뀌었다. chat isolation key는 제거되고 `HostedSessionContext`는 user-only 타입이 되었다. (출처: [ADR-0030 context and breaking change](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0030-hosted-platform-context-agentserver-2.0.md#L15-L27), [ADR-0026 superseded note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0026-hosted-session-identity-context.md#L10-L13))

### .NET 현재 구현
`.NET` `PlatformHostedSessionIsolationKeyProvider`는 `ResponseContext.PlatformContext.UserIdKey`를 읽어 `HostedSessionContext`로 바꾼다. hosted env에서 이 값이 없으면 error, local에서는 null 허용 경로가 있다. (출처: [.NET PlatformHostedSessionIsolationKeyProvider](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/PlatformHostedSessionIsolationKeyProvider.cs#L12-L45), [ADR-0031 local runs no longer fail closed](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0031-hosted-per-user-session-storage-isolation.md#L104-L119))

### Python 현재 구현
Python `validate_foundry_request_context(...)`는 hosted env에서 `call_id`와 `user_id`를 요구한다. 즉 hosted identity는 request context에서 직접 강제된다. (출처: [Python validate_foundry_request_context](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/_request_context.py#L41-L53))

---

## Authorization responsibility

Framework의 공통 원칙은 명확하다: **application builder가 trust boundary를 소유한다**.

- authenticate caller
- caller-supplied session/task/context/conversation/thread/response id authorize
- authorized principal/tenant/workspace/chat context와 bind
- parsed command/action effects authorize
- media/resource/file URL resolve는 explicit opt-in

이 원칙은 helper package가 candidate key를 “뽑아주는 것”과 “그 key로 실제 state를 여는 것”을 분리하는 이유다. (출처: [ADR-0027 security responsibilities](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0027-hosting-channels.md#L141-L160))

### `.NET` 구체화
`.NET` `AgentSessionStore` 주석은 `sessionStoreId`가 wire-originated chain-resume id일 뿐 authorization token이 아니라고 못박고, multi-user host는 principal dimension을 key에 합성해야 한다고 설명한다. (출처: [.NET AgentSessionStore trust model](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/AgentSessionStore.cs#L20-L35))

### Python 구체화
Python hosting README도 `AgentState`는 app-selected ID를 store에 그대로 넘기고, 각 store implementation이 backend-specific validation/normalization을 소유한다고 설명한다. 즉 route/app이 authorization을 하고, generic state helper는 opaque key를 그대로 전달한다. (출처: [Python hosting README opaque IDs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting/README.md#L24-L29))

---

## Raw service session ID 위험

Python ADR는 `service_session_id`를 **generic correlation field로 쓰지 말라**고 아주 강하게 설명한다. 이 값은 “service-owned value that lets that service continue a conversation, session, or thread”이며, 다른 agent type이나 telemetry layer가 generic하게 parse/understand해서는 안 된다. (출처: [ADR-0029 service_session_id as opaque service-owned handle](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0029-python-agent-session-identity.md#L14-L19))

왜 위험한가:

1. **lifecycle 혼동**  
   `service_session_id`는 future-call continuation state이지 response identity, run correlation, task identity와 동일하지 않다. (출처: [ADR-0029 lifecycle split](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0029-python-agent-session-identity.md#L247-L257))

2. **agent-specific shape**  
   OpenAI Responses는 보통 string 하나면 되지만, A2A는 `context_id + task_id + task_state`처럼 multi-field state가 필요하다. generic code가 이 shape를 직접 parse하면 provider-specific coupling이 생긴다. (출처: [ADR-0029 concrete gap example](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0029-python-agent-session-identity.md#L45-L66), [ADR-0029 Option B rationale](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0029-python-agent-session-identity.md#L160-L193))

3. **telemetry misuse 위험**  
   OpenTelemetry `gen_ai.conversation.id`는 owner agent가 `_get_otel_conversation_id(session)` 같은 hook으로 추출해야지, generic telemetry code가 structured `service_session_id`를 파싱하면 안 된다. (출처: [ADR-0029 telemetry extractor rule](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0029-python-agent-session-identity.md#L265-L268), [appendix implementation note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0029-python-agent-session-identity.md#L316-L345))

### 현재 구현 예: A2A
Python A2A는 실제로 `A2AServiceSessionId`를 도입했고, `A2AAgentSession`은 deprecated로 밀고 있다. 이는 “raw string 하나로는 부족한 structured service continuation”이 generic correlation과 섞여선 안 된다는 현재 구현 증거다. (출처: [Python A2AServiceSessionId](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/a2a/agent_framework_a2a/_agent.py#L59-L64), [deprecated A2AAgentSession](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/a2a/agent_framework_a2a/_agent.py#L67-L80), [sync into service_session_id](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/a2a/agent_framework_a2a/_agent.py#L171-L180))

---

## Per-user isolation

## 왜 logical check만으로 충분하지 않은가

ADR-0031이 설명하듯, strict-resume identity check만 있으면 **forged `conversation_id`가 다른 사용자의 파일 path를 먼저 resolve한 뒤에야** mismatch가 드러난다. defense in depth를 위해서는 physical path partitioning이 필요하다. (출처: [ADR-0031 problem statement](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0031-hosted-per-user-session-storage-isolation.md#L14-L27))

## `.NET` 구현

### Generic multi-user isolation
`.NET` generic hosting은 `IsolationKeyScopedAgentSessionStore`로 sessionStoreId 앞에 isolation key를 붙인다. strict mode면 key가 없을 때 fail-closed, non-strict mode면 pass-through다. (출처: [IsolationKeyScopedAgentSessionStore](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/IsolationKeyScopedAgentSessionStore.cs#L16-L115))

### ASP.NET claims-based identity source
ASP.NET helper는 authenticated principal의 claim에서 isolation key를 뽑는다. default claim type은 `ClaimTypes.NameIdentifier`이며, mutable/non-unique display name 계열 claim은 unsafe라고 명시한다. (출처: [ClaimsIdentityAgentIsolationKeyProvider security warning](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AspNetCore/ClaimsIdentityAgentIsolationKeyProvider.cs#L19-L34), [GetIsolationKeyAsync only on authenticated principal](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AspNetCore/ClaimsIdentityAgentIsolationKeyProvider.cs#L78-L95))

### Foundry hosted physical partition
Foundry Hosting은 더 강하게, path 자체를:
`{root}/a-{agent}/u-{userId}/c-{contextId}.json`
으로 만든다. `userId`는 untrusted platform-injected partition key로 취급되어 safe path segment인지 검증되고, 최종 path도 storage root 아래인지 다시 확인한다. (출처: [ADR-0031 path layout](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0031-hosted-per-user-session-storage-isolation.md#L53-L83), [FileSystemAgentSessionStore.GetSessionPath](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/FileSystemAgentSessionStore.cs#L234-L279))

## Python 구현

### Opaque key pass-through
Python generic hosting은 app-selected opaque key를 store에 그대로 넘긴다. `SessionStore` 자체는 per-user isolation을 모른다. (출처: [Python hosting README opaque IDs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting/README.md#L24-L29))

### FileSessionStore
Python `FileSessionStore`는 opaque keys를 filename-safe stem으로 encode하고, path traversal 방지를 포함한 file-backed persistence를 제공한다. ADR-0034는 provider ids like `telegram:<bot-id>:<chat-id>` 같은 값도 직접 key로 지원하려고 했음을 설명한다. (출처: [ADR-0034 SessionStore accepts opaque keys](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0034-python-session-store-serialization.md#L205-L209), [ADR-0034 FoundrySessionStore historical note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0034-python-session-store-serialization.md#L212-L232))

### Foundry per-user isolation
Python Foundry stores는 `FoundryStateStore.get_or_create(..., user_isolation=True)`를 사용해 physical per-user partitioning을 platform storage layer에 위임한다. (출처: [FoundryCheckpointStore user_isolation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/_state_store.py#L80-L84), [FoundryFunctionApprovalStore user_isolation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/_state_store.py#L225-L226), [FoundryAgentSessionStore user_isolation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/_state_store.py#L294-L295))

---

## Session continuity와 protocol별 비교

ADR-0027은 session continuity를 “run parsing과 isolation/session id selection을 분리된 단계로 둬야 한다”고 정리한다. isolation source는 여러 가지일 수 있다.

- protocol input (`previous_response_id`, Telegram chat id 등)
- running environment (Foundry hosted user isolation)
- app-specific trusted middleware or route state  
  (출처: [ADR-0027 session continuity sources](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0027-hosting-channels.md#L171-L191))

이 원칙을 protocol별로 최소 비교하면 다음과 같다.

### OpenAI Responses
- `previous_response_id`는 rotating chain pointer
- `conversation`/`conversation_id`는 stable mutable head
- 둘 다 request-derived candidate key이며 host authorization이 필요  
  (출처: [ADR-0029 OpenAI current implementation note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0029-python-agent-session-identity.md#L77-L80), [ADR-0027 examples](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0027-hosting-channels.md#L180-L188))

### A2A
- durable future-call state는 `context_id + task_id + task_state`
- `reference_task_ids`는 durable state가 아니라 current request intent
- in-progress work resume은 continuation token  
  (출처: [ADR-0029 A2A current notes](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0029-python-agent-session-identity.md#L70-L79), [ADR-0029 appendix on task_id vs reference_task_ids](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0029-python-agent-session-identity.md#L270-L293))

### AG-UI
- `thread_id`는 wrapper-owned conversation key
- `run_id`는 per-run event correlation
- AG-UI는 별도 protocol 문서가 상세를 다루지만, ADR-0029는 이미 `thread_id`가 `AgentSession.session_id`에, `run_id`는 wrapper-owned event correlation에 가깝다고 선을 긋는다.  
  (출처: [ADR-0029 AG-UI out of scope note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0029-python-agent-session-identity.md#L41-L43))

### Foundry hosted
- platform-provided `user_id`/`call_id`는 request environment에서 온 값
- raw spoofable header를 local/non-hosted 상황에서 trusted hosted isolation처럼 쓰면 안 된다.  
  (출처: [ADR-0027 Foundry-specific warning](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0027-hosting-channels.md#L162-L164), [.NET PlatformHostedSessionIsolationKeyProvider local behavior](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/PlatformHostedSessionIsolationKeyProvider.cs#L17-L25))

### Telegram
- helper가 natural continuation key를 만들어 줄 수 있지만, 그것도 여전히 host authorization 후에만 durable state key가 될 수 있다.  
  (출처: [ADR-0027 Telegram session_id example](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0027-hosting-channels.md#L184-L185))

---

## Route / link / multicast / session routing

## 현재 v1 범위

ADR-0027과 Python hosting README는 v1에 다음이 **없다**고 명시한다.

- cross-channel identity linking
- multicast delivery
- background delivery runners
- durable delivery/replay semantics
- framework-owned proactive/non-originating sends

즉 “어떤 session/output을 어떤 channel/route로 fan-out할지”는 현재 generic hosting core의 책임이 아니다. (출처: [ADR-0027 outcome and non-goals](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0027-hosting-channels.md#L62-L103), [Python hosting README follow-up enhancements note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting/README.md#L143-L145))

## Follow-up enhancement 영역

ADR-0028는 identity linking, authorization/allowlist, active-channel routing, multicast/all-linked delivery, background runs, durable delivery runners, retry/replay, payload serialization을 **follow-up layered packages** 영역으로 분리한다. 즉 이것은 session routing/storage ownership 문제를 공유하지만 아직 v1 public contract가 아니다. (출처: [ADR-0028 enhancement areas](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0028-hosting-linking-multicast-enhancements.md#L24-L38), [Option C layered opt-in packages](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0028-hosting-linking-multicast-enhancements.md#L58-L64), [proposed direction](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0028-hosting-linking-multicast-enhancements.md#L74-L79), [storage requirements](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0028-hosting-linking-multicast-enhancements.md#L107-L115))

## Storage ownership

route/link/multicast 계층이 future package로 가더라도 storage ownership 원칙은 이미 드러나 있다.

- base `AgentSession` history / workflow checkpoint와 enhancement storage는 분리되어야 한다.
- link records, active-channel state, delivery attempts, dead letters, serialized payloads는 independent TTL/deletion policy가 필요하다.  
  (출처: [ADR-0028 storage section](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0028-hosting-linking-multicast-enhancements.md#L107-L115))

---

## Storage ownership

## Generic principle

Store는 **who owns the key contract**가 중요하다.

- host/application이 key를 고르고 authorize한다.
- generic state helper는 opaque key를 그대로 전달한다.
- store implementation은 backend-specific validation/normalization을 소유한다.  
  (출처: [Python hosting README opaque keys and store ownership](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting/README.md#L24-L29), [.NET AgentSessionStore implementer guidance](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/AgentSessionStore.cs#L38-L45))

## `.NET` generic store ownership
`.NET` `AgentSessionStore`는:
- `sessionStoreId`를 opaque로 treat하라고 요구
- principal/owner dimension이 없으므로 multi-user host가 scoping decorator를 붙여야 한다고 요구  
  (출처: [.NET AgentSessionStore trust and implementer guidance](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/AgentSessionStore.cs#L20-L45))

## Python store ownership
Python `SessionStore`는 non-empty string만 요구하고, `FileSessionStore`는 portable filename stem으로 encode할 수 없는 key도 수용할 수 있게 설계되었다. 즉 key contract는 **application chooses, store validates/encodes** 모델이다. (출처: [Python SessionStore validate_session_id](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_sessions.py#L1711-L1723), [ADR-0034 opaque key decision](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0034-python-session-store-serialization.md#L205-L209))

## Hosted physical storage
Hosted runtime에서는 storage ownership이 한 단계 더 강해진다.

- `.NET` Foundry: host-owned file layout `{root}/a-{agent}/u-{user}/c-{context}.json`
- Python Foundry: platform-owned `FoundryStateStore(..., user_isolation=True)` scope  
  (출처: [.NET FileSystemAgentSessionStore layout](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/FileSystemAgentSessionStore.cs#L236-L279), [Python Foundry session store](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/_state_store.py#L289-L308))

---

## 동시성·스트리밍·취소

Identity/session routing 관점에서 중요한 점은 **persist timing과 single-writer ownership**이다.

- ADR-0027은 post-run session or checkpoint state를 `run(...)` 또는 stream finalization 이후에만 persist하라고 요구한다.
- stable mutable head (`conversation`, per-chat session, AG-UI thread head 등)는 concurrent writers를 host가 serialize해야 한다.
- in-progress resume token은 durable session state와 분리되어야 한다.  
  (출처: [ADR-0027 persist after run/finalization](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0027-hosting-channels.md#L157-L160), [ADR-0029 lifecycle split](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0029-python-agent-session-identity.md#L247-L257))

Foundry hosted `.NET`은 response stream 종료 후 finally에서 session을 persist하고, Python helper-first model도 sample/README에서 post-run persist를 요구한다. 이는 identifier semantics와 저장 시점이 강하게 연결돼 있음을 보여 준다. (출처: [.NET Foundry handler finally save](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/AgentFrameworkResponseHandler.cs#L477-L486), [Python hosting README post-run session store](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting/README.md#L86-L102))

---

## 오류·검증·보안

## 공통 보안 원칙
- protocol helper가 추출한 id는 untrusted candidate key
- platform-provided isolation helper는 trusted environment 밖에서 fail-closed 또는 explicit fallback이어야 한다
- path-based store는 path traversal 방어가 필요
- logs/telemetry는 opaque keys나 isolation prefix를 surface할 수 있으므로 주의가 필요  
  (출처: [ADR-0027 trust boundary bullets](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0027-hosting-channels.md#L150-L159), [.NET AgentSessionStore logging note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/AgentSessionStore.cs#L43-L45), [ADR-0031 path traversal guard](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0031-hosted-per-user-session-storage-isolation.md#L74-L79))

## `.NET` 검증 포인트
- `ClaimsIdentityAgentIsolationKeyProvider`는 default로 display name claim을 쓰지 않는다.
- `IsolationKeyScopedAgentSessionStore`는 strict mode에서 null key면 예외다.  
  (출처: [ClaimsIdentityAgentIsolationKeyProviderTests ignores name claim by default](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.UnitTests/ClaimsIdentityAgentIsolationKeyProviderTests.cs#L115-L132), [IsolationKeyScopedAgentSessionStoreTests strict mode](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.UnitTests/IsolationKeyScopedAgentSessionStoreTests.cs#L97-L114))

## Python 검증 포인트
- structured `service_session_id`와 deprecated `A2AAgentSession`이 모두 round-trip 가능해야 한다.
- `SessionStore` key는 opaque non-empty contract를 지켜야 한다.
- Foundry context validation은 hosted env에서 fail-fast 해야 한다.  
  (출처: [Python A2A session shape and sync](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/a2a/agent_framework_a2a/_agent.py#L116-L180), [Python SessionStore validate_session_id](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_sessions.py#L1711-L1723), [Python Foundry request context validation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/_request_context.py#L41-L53))

---

## .NET / Python 차이

1. **Generic isolation strategy**
   - `.NET`: explicit isolation key provider + scoping decorator
   - Python: helper-first + opaque key pass-through + backend-specific provider/store logic  
   (출처: [.NET IsolationKeyScopedAgentSessionStore](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/IsolationKeyScopedAgentSessionStore.cs#L9-L115), [Python hosting README opaque IDs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting/README.md#L24-L29))

2. **Structured service continuation**
   - Python ADR와 current A2A implementation은 structured `service_session_id`를 적극 수용
   - `.NET`은 protocol-specific session subclass(`A2AAgentSession`)를 여전히 둔다  
   (출처: [ADR-0029 Option B chosen](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0029-python-agent-session-identity.md#L259-L267), [Python A2AServiceSessionId](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/a2a/agent_framework_a2a/_agent.py#L59-L64), [.NET A2AAgentSession](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.A2A/A2AAgentSession.cs#L12-L46))

3. **Foundry per-user isolation**
   - `.NET`: app file path layout에 agent/user/context layers를 직접 만든다
   - Python: platform storage API `user_isolation=True`에 위임  
   (출처: [.NET FileSystemAgentSessionStore layout](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/FileSystemAgentSessionStore.cs#L236-L279), [Python FoundryStateStore user_isolation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/_state_store.py#L294-L295))

4. **Local fallback behavior**
   - `.NET` Foundry hosted identity provider는 local non-hosted run에서 null user id를 tolerated path로 둔다
   - Python Foundry request validator는 hosted 여부를 받아 hosted일 때만 strict 검사  
   (출처: [.NET PlatformHostedSessionIsolationKeyProvider local remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/PlatformHostedSessionIsolationKeyProvider.cs#L17-L25), [Python validate_foundry_request_context signature and behavior](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/_request_context.py#L41-L53))

---

## .NET 구현과 테스트

## 구현
- generic trust model: `AgentSessionStore`
- multi-user scoping: `IsolationKeyScopedAgentSessionStore`
- ASP.NET identity source: `ClaimsIdentityAgentIsolationKeyProvider`
- Foundry hosted platform context: `HostedSessionContext`, `PlatformHostedSessionIsolationKeyProvider`
- Foundry physical partitioning: file path layout with agent/user/context layers  
  (출처: [.NET AgentSessionStore](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/AgentSessionStore.cs#L20-L45), [.NET IsolationKeyScopedAgentSessionStore](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/IsolationKeyScopedAgentSessionStore.cs#L51-L115), [.NET ClaimsIdentityAgentIsolationKeyProvider](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AspNetCore/ClaimsIdentityAgentIsolationKeyProvider.cs#L19-L95), [.NET PlatformHostedSessionIsolationKeyProvider](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/PlatformHostedSessionIsolationKeyProvider.cs#L12-L45), [.NET FileSystemAgentSessionStore](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/FileSystemAgentSessionStore.cs#L234-L279))

## 테스트
- `ClaimsIdentityAgentIsolationKeyProviderTests`는 default claim source와 name claim 무시를 검증한다. (출처: [ClaimsIdentityAgentIsolationKeyProviderTests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.UnitTests/ClaimsIdentityAgentIsolationKeyProviderTests.cs#L101-L167))
- `IsolationKeyScopedAgentSessionStoreTests`는 strict/non-strict behavior와 key rewriting을 검증한다. (출처: [IsolationKeyScopedAgentSessionStoreTests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.UnitTests/IsolationKeyScopedAgentSessionStoreTests.cs#L97-L220))
- `HostedSessionIdentityContextTests`는 Foundry hosted context stamping, local permissive path, mismatch 403를 검증한다. (출처: [HostedSessionIdentityContextTests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Foundry.Hosting.UnitTests/HostedSessionIdentityContextTests.cs#L25-L239))

---

## Python 구현과 테스트

## 구현
- ADR-0029가 lifecycle-based identity split의 기준을 제공
- `SessionStore` / `FileSessionStore`는 opaque key contract
- `A2AServiceSessionId`가 structured `service_session_id` current implementation example
- Foundry stores가 per-user isolation을 플랫폼 storage에 위임  
  (출처: [ADR-0029 decision](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0029-python-agent-session-identity.md#L243-L267), [Python A2AServiceSessionId current implementation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/a2a/agent_framework_a2a/_agent.py#L59-L80), [Python Foundry stores](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/_state_store.py#L289-L335))

## 테스트
- Foundry state store tests는 context-scoped store/provider behavior와 hosted storage shape를 검증한다. (출처: [Python test_state_store](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/tests/test_state_store.py#L63-L229))
- protocol-specific tests(A2A/Responses/AG-UI/Telegram 등)는 각 protocol이 만들어내는 candidate session key semantics를 간접적으로 검증하지만, 본 문서에서는 protocol 문서로 위임한다.

---

## 문서 차이

가장 중요한 code-first 차이는 Python 쪽 설계 문서가 `service_session_id`를 richer typed value로 확장하는 방향을 정식으로 선택했다는 점이다. 현재 A2A 구현은 실제로 이 방향을 채택했지만, generic hosting README만 보면 여전히 simple opaque key model이 더 전면에 나온다. 즉 “simple opaque string continuation”와 “structured service-owned continuation”이 문서 계층별로 강조점이 다르다. 그러나 이는 모순이라기보다 설계 레벨과 generic usage guide 레벨의 초점 차이로 보는 것이 맞다. (출처: [ADR-0029 Option B chosen](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0029-python-agent-session-identity.md#L259-L267), [Python hosting README opaque keys](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting/README.md#L24-L29), [Python current A2A implementation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/a2a/agent_framework_a2a/_agent.py#L59-L80))

또한 route/link/multicast 쪽은 README나 high-level 문서에서 자주 언급되지 않지만, ADR-0028을 보면 명확히 “v1 core 밖의 follow-up enhancement stack”으로 격리되어 있다. 따라서 generic hosting만 보고 이 기능들이 implicit하게 있다고 가정하면 안 된다. (출처: [ADR-0028 context and option C](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0028-hosting-linking-multicast-enhancements.md#L10-L15), [ADR-0028 proposed direction](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0028-hosting-linking-multicast-enhancements.md#L74-L79))

---

## Java / Spring host 결정

## 기본 원칙

1. **session key extraction과 authorization binding을 분리**한다.  
   `threadId`, `contextId`, `conversationId`, `taskId`, `serviceSessionId`를 parse하는 helper와, 그것을 authenticated principal/tenant에 bind하는 Spring host filter/interceptor는 अलग artifact 또는 최소 다른 layer여야 한다.

2. **structured service continuation을 지원**한다.  
   Java의 `AgentSession.serviceSessionId`도 `String`만이 아니라 provider-owned structured type을 수용할 수 있어야 한다. 특히 A2A 같은 multi-field continuation은 별도 typed record/class로 표현하는 편이 좋다.

3. **Spring host module에서만 principal-derived isolation**을 구현**한다.  
   예:
   - `Authentication` / JWT claims → isolation key
   - session store decorator
   - per-route candidate key authorization
   - snapshot scope / tenant binding

4. **route/link/multicast는 core에 넣지 않는다.**  
   future enhancement module(`hosting-linking`, `hosting-multicast`)로 분리하고, base session/checkpoint storage와 별도 TTL/dead-letter policy를 가진다.

## 권장 모듈
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
   - protocol helper가 `session_id`/`context_id`/`thread_id`를 추출해도, host가 principal binding 전까지는 store lookup에 쓰지 않아야 한다.

2. **Opaque vs structured continuation**
   - simple provider는 raw string `service_session_id`를 유지할 수 있어야 한다.
   - multi-field provider는 structured `service_session_id`를 저장/복원할 수 있어야 한다.
   - telemetry는 owner agent의 extractor를 통해 only primary conversation id를 읽어야 한다.

3. **Per-user isolation**
   - multi-user host는 bare sessionStoreId 대신 principal-scoped composite key를 써야 한다.
   - Foundry-style hosted runtime에서는 guessed id가 다른 user의 storage path 자체를 가리키지 못해야 한다.

4. **Local fallback**
   - trusted hosted platform context가 없는 local development path는 explicit local behavior(예: null user partition)로만 동작해야 하며, spoofable raw headers를 production isolation처럼 간주하면 안 된다.

5. **Post-run persistence**
   - host는 run/stream finalization 이후에만 session 또는 checkpoint state를 persist해야 한다.
   - mutable head semantics를 갖는 stable key는 host가 single-writer coordination을 제공해야 한다.

6. **Route/link/multicast separation**
   - v1 hosting core는 cross-channel identity linking, active-channel routing, multicast fan-out, durable delivery runner를 포함하지 않아야 한다.
   - future enhancement storage는 base `AgentSession`/checkpoint storage와 별도 lifecycle policy를 가져야 한다.

7. **Path traversal / storage safety**
   - user-derived partition key가 file path로 쓰일 때는 single safe path component 검증과 root-boundary verification이 있어야 한다.

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

## 결론

이 저장소의 identity/session routing 설계는 “모든 continuation-related 값을 한 곳에 저장한다”가 아니라, **각 값의 생명주기와 trust boundary를 먼저 정의한 뒤 그에 맞는 저장 위치와 ownership을 정한다**는 철학을 따른다. `service_session_id`는 generic correlation field가 아니며, session/task/response/continuation/run correlation은 서로 다른 층이다. Host는 이들 중 어떤 값을 실제 persisted state key로 쓸지, 어떤 principal/tenant에 bind할지, 어떤 storage에 어떻게 partition할지 책임진다. `.NET`은 scoping decorator와 claims-based provider, Foundry physical path partitioning으로 이 철학을 구현하고, Python은 lifecycle-based ADR, opaque key store contract, structured `service_session_id`, hosted `user_isolation=True` store로 같은 방향을 따른다. Java/Spring host도 이 동일한 원칙을 따라 **candidate key extraction**, **authorization binding**, **storage partitioning**, **future linking/multicast layers**를 분리해야 한다. (출처: [ADR-0029 decision](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0029-python-agent-session-identity.md#L243-L267), [ADR-0027 trust boundary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0027-hosting-channels.md#L143-L160), [ADR-0031 decision outcome](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0031-hosted-per-user-session-storage-isolation.md#L53-L83), [ADR-0028 layered future routing/delivery stack](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0028-hosting-linking-multicast-enhancements.md#L74-L79))