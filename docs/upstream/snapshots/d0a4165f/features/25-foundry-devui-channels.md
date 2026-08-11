# 25. Foundry hosting, DevUI, Aspire integration, ChatKit, Telegram, 기타 확인된 channel adapters

## 범위

이 문서는 다음 하위 기능을 다룬다.

1. **Foundry hosting**
2. **DevUI**
3. **Aspire 기반 DevUI integration**
4. **ChatKit integration**
5. **Telegram channel adapter**
6. **기타 확인된 channel/protocol adapters의 경계**

이 문서는 provider 일반 catalog와 identity/session routing의 상세 규칙은 따로 다루지 않는다. 여기서는 각 기능의 **host surface**, **UI/channel adapter 역할**, **상태·영속화 접점**, **streaming/오류/보안 경계**에 집중한다. OpenAI Responses, A2A, AG-UI, MCP는 이미 별도 문서 소유이므로, 이 문서에서는 중복을 피하고 해당 기능들과 만나는 경계만 기술한다. (출처: [루트 README의 Foundry Hosted Agents / DevUI 항목](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/README.md#L48-L59), [Python AGENTS.md의 Protocols & UI 목록](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/AGENTS.md#L115-L121))

## 요약

이 저장소에서 Foundry hosting은 **관리형 runtime contract에 Agent Framework target을 붙이는 호스팅 계층**이고, DevUI는 **개발·디버깅용 UI와 API surface**, Aspire integration은 **여러 backend를 묶는 in-process reverse proxy/aggregator**다. ChatKit과 Telegram은 둘 다 Python 중심 channel adapter지만 성격이 다르다. ChatKit은 OpenAI ChatKit frontend/server protocol과 Agent Framework agent stream을 연결하는 integration layer이고, Telegram은 Bot API update와 Agent Framework run/result를 양방향 변환하는 helper-only 패키지다. `.NET`은 Foundry hosting과 DevUI/Aspire integration을 적극적으로 구현하고, Python은 Foundry hosting, DevUI, ChatKit, Telegram을 더 넓게 제공한다. 반대로 ChatKit과 Telegram에 대응하는 `.NET` repo-local adapter는 확인되지 않았다. 또한 “채널/프로토콜 adapter”는 여러 개가 더 확인되지만(A2A, Responses, MCP, AG-UI), 이들은 이미 별도 문서 범위이며 여기서는 catalog 대신 boundary note만 남긴다. (출처: [Python foundry_hosting public exports](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/__init__.py#L5-L38), [.NET Foundry.Hosting csproj](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/Microsoft.Agents.AI.Foundry.Hosting.csproj#L5-L18), [.NET DevUI csproj](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.DevUI/Microsoft.Agents.AI.DevUI.csproj#L7-L13), [Aspire DevUI csproj](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Aspire.Hosting.AgentFramework.DevUI/Aspire.Hosting.AgentFramework.DevUI.csproj#L3-L18), [Python ChatKit README](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/chatkit/README.md#L1-L14), [Python Telegram README](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-telegram/README.md#L1-L24))

---

## 1. Foundry hosting

## 목적

Foundry hosting의 목적은 Agent Framework agent 또는 workflow를 **Foundry Agent Server / Foundry Hosted Agents runtime**에 연결해, 플랫폼이 제공하는 request context, state store, responses/invocations contract, toolbox bridge를 활용하도록 만드는 것이다. Python은 `ResponsesHostServer`와 `InvocationsHostServer`를 직접 제공하고, `.NET`은 Azure AI Responses Server SDK 기반 `AddFoundryResponses` + `MapFoundryResponses`와 `AgentFrameworkResponseHandler`로 같은 역할을 수행한다. (출처: [Python foundry_hosting public exports](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/__init__.py#L5-L38), [Python foundry_hosting README overview](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/README.md#L1-L24), [.NET FoundryHostingExtensions summary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/ServiceCollectionExtensions.cs#L25-L55), [.NET AgentFrameworkResponseHandler summary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/AgentFrameworkResponseHandler.cs#L19-L25))

## 경계

Foundry hosting layer가 하는 일은 다음이다.

- Foundry runtime request context 검증
- Agent Framework target run 호출
- platform-compatible response stream 생성
- hosted state store/session/checkpoint/function approval persistence 연결
- toolbox/MCP proxy와의 auth bridge 제공
- readiness/listen-port 같은 hosting contract 충족

반대로 이 계층은 provider 일반 catalog나 identity/session routing 전체 설계를 모두 소유하지 않는다. 예를 들어 per-user isolation 자체는 state store와 request context를 통해 강제되지만, 어떤 user/session key를 어떻게 설계했는지의 일반론은 별도 identity/session routing 문서의 범위다. (출처: [Python validate_foundry_request_context](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/_request_context.py#L41-L53), [.NET hosted platform context ADR](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0030-hosted-platform-context-agentserver-2.0.md#L15-L27), [.NET Foundry responses registration scope](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/ServiceCollectionExtensions.cs#L32-L63))

## 성숙도

| 구현 | 성숙도 |
|---|---|
| `.NET` `Microsoft.Agents.AI.Foundry.Hosting` | `preview` |
| Python `agent-framework-foundry-hosting` | `beta` |
| Python `FoundryAgentSessionStore` | feature-level experimental (`SESSION_STORE`) |

출처:
- [.NET Foundry.Hosting csproj](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/Microsoft.Agents.AI.Foundry.Hosting.csproj#L5-L18)
- [Python PACKAGE_STATUS foundry-hosting row](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L31-L34)
- [Python PACKAGE_STATUS SESSION_STORE feature row](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L139-L145)

## 공개 API·타입

### .NET
- `AddFoundryResponses(this IServiceCollection, ...)`
- `MapFoundryResponses(this IEndpointRouteBuilder, string prefix = "")`
- `AddFoundryToolboxes(...)`
- `HostedSessionContext`
- Foundry-specific `AgentSessionStore` abstraction 및 file/in-memory implementations  
  (출처: [.NET FoundryHostingExtensions public methods](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/ServiceCollectionExtensions.cs#L32-L63), [.NET AddFoundryToolboxes overloads](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/ServiceCollectionExtensions.cs#L116-L216), [.NET HostedSessionContext](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/HostedSessionContext.cs#L9-L52), [.NET Foundry AgentSessionStore contract](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/AgentSessionStore.cs#L11-L97))

### Python
- `ResponsesHostServer`
- `InvocationsHostServer`
- `FoundryToolbox`
- `FoundryAgentSessionStore`
- `FoundryCheckpointStore`
- `FoundryFunctionApprovalStore`
- `AgentSessionStoreProvider`, `CheckpointStoreProvider`, `FunctionApprovalStoreProvider`  
  (출처: [Python foundry_hosting __all__](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/__init__.py#L25-L38), [Python state store classes](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/_state_store.py#L63-L195), [Python session store classes](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/_state_store.py#L289-L335), [Python FoundryToolbox](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/_toolbox.py#L133-L223))

## 상세 실행 흐름

### .NET Responses path
1. host가 `AddFoundryResponses(...)`를 호출해 `ResponseHandler`로 `AgentFrameworkResponseHandler`를 등록한다.
2. `MapFoundryResponses()`가 Responses routes와 `/readiness` health check를 연결한다.
3. request가 들어오면 handler가:
   - agent/session store resolve
   - protocol compatibility 검사
   - hosted session identity context resolve
   - session load/create
   - history/input conversion
   - optional toolbox injection
   - `agent.RunStreamingAsync(...)`
   - output conversion 후 stream emit
   - finally에서 session persist  
   를 수행한다. (출처: [.NET AddFoundryResponses registration](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/ServiceCollectionExtensions.cs#L32-L63), [.NET MapFoundryResponses](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/ServiceCollectionExtensions.cs#L219-L255), [.NET AgentFrameworkResponseHandler main flow](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/AgentFrameworkResponseHandler.cs#L67-L170), [.NET handler run and final persistence](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/AgentFrameworkResponseHandler.cs#L374-L487))

### Python Responses path
1. `ResponsesHostServer`가 agent에 대한 server host 역할을 한다.
2. request마다 `validate_foundry_request_context(...)`를 호출한다.
3. regular agent path에서는:
   - approval/session storage resolve
   - `previous_response_id` 또는 `conversation_id` 기반 session lookup/create
   - lazy `_ensure_agent_ready()`
   - history + input items → run kwargs
   - streaming conversion and output tracking
   - finally에서 session persist  
   를 수행한다.
4. workflow agent path에서는:
   - latest checkpoint lookup
   - restore-only run
   - 새 input으로 streaming run
   - old checkpoints prune  
   을 수행한다. (출처: [Python ResponsesHostServer init and lazy agent lifecycle](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/_responses.py#L170-L305), [handle_response entry](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/_responses.py#L307-L320), [regular agent path](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/_responses.py#L322-L485), [workflow path](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/_responses.py#L486-L648))

### Python Invocations path
1. `InvocationsHostServer`가 request context에서 partition key를 계산한다.
2. hosted이면 `session_id:user_id`, local이면 `session_id`를 쓴다.
3. in-memory `_sessions` dict에서 session을 가져오고, stream flag에 따라 plain `Response` 또는 `StreamingResponse`를 돌려준다. (출처: [InvocationsHostServer partition key](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/_invocations.py#L43-L73), [invoke handler](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/_invocations.py#L75-L108))

## 상태·영속화

### .NET
`.NET` Foundry hosting은 자체 `AgentSessionStore` abstraction을 가진다. 기본 local/dev store는 in-memory 또는 file-system 구현이고, hosted 환경에서는 file store가 `$HOME/.checkpoints` 아래에 session JSON을 두도록 설계되어 있다. 또한 `HostedSessionContext`는 hosted session의 user identity를 담는 typed value다. (출처: [.NET Foundry AgentSessionStore contract](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/AgentSessionStore.cs#L11-L97), [.NET InMemoryAgentSessionStore](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/InMemoryAgentSessionStore.cs#L12-L74), [.NET FileSystemAgentSessionStore purpose and hosted path](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/FileSystemAgentSessionStore.cs#L15-L38), [default root resolution](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/FileSystemAgentSessionStore.cs#L87-L123), [.NET HostedSessionContext](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/HostedSessionContext.cs#L20-L29))

### Python
Python foundry hosting은 hosted일 때 `FoundryStateStore(..., user_isolation=True)`를 사용한다.

- agent sessions → `agent_sessions`
- workflow checkpoints → `checkpoints/<context_id>`
- function approvals → `function_approvals`

local path는 in-memory store/provider로 fallback한다. README도 이 state layout을 직접 설명한다. (출처: [Python README state store](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/README.md#L5-L24), [FoundryCheckpointStore](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/_state_store.py#L63-L84), [CheckpointStoreProvider](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/_state_store.py#L165-L195), [FoundryFunctionApprovalStore](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/_state_store.py#L214-L281), [FoundryAgentSessionStore](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/_state_store.py#L289-L335))

## 확장점

### .NET
- custom `AIAgent` 또는 keyed/non-keyed registrations
- custom `AgentSessionStore`
- custom `HostedSessionIsolationKeyProvider`
- `AddFoundryToolboxes(...)` credential/options/toolbox names
- custom `/readiness` route 선등록 시 framework가 중복 등록하지 않음  
  (출처: [.NET AddFoundryResponses overload with explicit agent and store](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/ServiceCollectionExtensions.cs#L66-L113), [.NET AddFoundryToolboxes](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/ServiceCollectionExtensions.cs#L116-L216), [.NET readiness duplicate-route guard](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/ServiceCollectionExtensions.cs#L355-L380))

### Python
- `ResponsesHostServer` / `InvocationsHostServer`에 custom store providers
- `FoundryToolbox`에 custom endpoint / token scope / timeout / prompt/tool loading
- custom context-scoped checkpoint storage와 approval store provider  
  (출처: [ResponsesHostServer constructor extension points](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/_responses.py#L174-L199), [FoundryToolbox init options](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/_toolbox.py#L174-L223))

## 동시성·스트리밍·취소

### .NET
- handler는 response stream을 직접 `yield return`하며, tool consent, shutdown incomplete, failed terminal events를 포함한다.
- `x-agent-foundry-call-id`는 async iterator yield 경계마다 다시 ambient context에 주입해 toolbox/MCP egress에서 잃지 않게 한다.
- session save는 stream 종료 후 finally에서 수행된다. (출처: [.NET consent/shutdown/failure loop](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/AgentFrameworkResponseHandler.cs#L393-L487), [.NET re-apply call id before egress](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/AgentFrameworkResponseHandler.cs#L242-L255), [.NET re-apply before MoveNextAsync](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/AgentFrameworkResponseHandler.cs#L395-L400))

### Python
- `ResponsesHostServer`는 `ResponseEventStream`을 사용해 streaming responses를 내보낸다.
- regular agent path는 session persist를 finally에서 수행하고, workflow path는 latest checkpoint 외 이전 checkpoints를 prune한다.
- `InvocationsHostServer` streaming path는 text-only chunks를 `text/event-stream`으로 보낸다. (출처: [Python regular path finally session set](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/_responses.py#L455-L484), [workflow checkpoint cleanup](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/_responses.py#L611-L622), [InvocationsHostServer streaming response](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/_invocations.py#L94-L105))

## 오류·검증·보안

### .NET
- hosted env에서 `x-agent-foundry-call-id`가 없으면 protocol 1.0.0 mismatch로 `501 unsupported_container_protocol_version`
- hosted env에서 user id context가 없으면 reject
- toolbox egress는 fresh bearer token, mandatory `Foundry-Features`, trace context, retry를 넣는다.  
  (출처: [.NET HostedProtocolCompatibility](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/HostedProtocolCompatibility.cs#L8-L74), [.NET missing user id handling](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/AgentFrameworkResponseHandler.cs#L97-L115), [.NET toolbox bearer token handler](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/FoundryToolboxBearerTokenHandler.cs#L14-L21), [.NET call-id forwarding and retry](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/FoundryToolboxBearerTokenHandler.cs#L45-L95), [.NET trace propagation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/FoundryToolboxBearerTokenHandler.cs#L123-L168))

### Python
- hosted env이면 `call_id`와 `user_id`가 request context에 있어야 한다.
- untrusted context values를 path segment로 쓸 때는 explicit validation을 한다.
- consent-required toolbox error는 hosted response stream의 `oauth_consent_request`로 surface한다.  
  (출처: [Python request context validation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/_request_context.py#L17-L39), [hosted context protocol-v2 checks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/_request_context.py#L41-L53), [consent URL parsing](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/_responses.py#L95-L164), [oauth_consent_request emission](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/_responses.py#L339-L370))

## .NET 구현과 테스트

- protocol mismatch 501은 `HostedProtocolCompatibilityTests`가 고정한다. (출처: [HostedProtocolCompatibilityTests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Foundry.Hosting.UnitTests/HostedProtocolCompatibilityTests.cs#L7-L57))
- hosted session context stamping, local null-key permissive path, mismatch 403은 `HostedSessionIdentityContextTests`가 고정한다. (출처: [HostedSessionIdentityContextTests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Foundry.Hosting.UnitTests/HostedSessionIdentityContextTests.cs#L17-L239))
- toolbox token scope / features header / call-id forwarding / degraded startup는 unit tests가 고정한다. (출처: [FoundryToolboxBearerTokenHandlerTests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Foundry.Hosting.UnitTests/FoundryToolboxBearerTokenHandlerTests.cs#L16-L204), [FoundryToolboxServiceTests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Foundry.Hosting.UnitTests/FoundryToolboxServiceTests.cs#L15-L257))

## Python 구현과 테스트

- `test_state_store.py`는 context-scoped checkpoint store, approval store, agent session store provider behavior를 검증한다. (출처: [Python test_state_store](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/tests/test_state_store.py#L63-L229))
- `test_responses.py`는 full HTTP pipeline, request context injection, hosted response behavior를 검증한다. (출처: [Python test_responses overview](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/tests/test_responses.py#L3-L9), [helpers and request context setup](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/tests/test_responses.py#L92-L105))
- `test_invocations.py`는 local vs hosted partition key와 400/500/streaming behavior를 검증한다. (출처: [Python test_invocations partition key tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/tests/test_invocations.py#L158-L194), [invoke tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/tests/test_invocations.py#L200-L220))

## 문서 차이

Python README는 `ResponsesHostServer`의 durable state store 구성은 설명하지만, `InvocationsHostServer`가 실제로는 process-local `_sessions` dict만 쓰는 점은 드러내지 않는다. 코드 우선으로 보면 Responses와 Invocations의 durability profile은 다르다. (출처: [Python README state store description](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/README.md#L5-L24), [InvocationsHostServer in-memory sessions](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry_hosting/agent_framework_foundry_hosting/_invocations.py#L38-L40))

## Java 결정

- `foundry-hosting`은 **core가 아닌 optional host adapter**여야 한다.
- Responses host와 Invocations host를 분리하고,
- toolbox auth/consent bridge는 별도 submodule로 두는 편이 적절하다.
- readiness/listen-port/runtime contract는 Spring/Servlet host binder가 맡고, generic hosting core에는 넣지 않는다.

## Acceptance scenarios

1. hosted runtime에서 protocol-v2 context가 없으면 clear failure를 반환해야 한다.
2. regular responses path는 request 종료 후 session을 persist해야 한다.
3. workflow path는 latest checkpoint를 restore하고 새 input으로 이어가야 한다.
4. local/dev run은 platform headers 없이도 동작해야 한다.
5. consent-required toolbox는 silent failure 대신 explicit consent event를 surface해야 한다.
6. dev-only file-backed session store는 hosted writable directory contract를 따라야 한다.

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

## 목적

DevUI의 목적은 agent/workflow를 **개발 중에 빠르게 발견, 실행, 검사, 배포 테스트**할 수 있는 developer-facing UI와 API surface를 제공하는 것이다. `.NET` DevUI는 OpenAI Responses/Conversations service 위에 올라가는 ASP.NET application surface이고, Python DevUI는 자체 `FastAPI` 서버(`DevServer`)로 entity discovery, local execution, optional OpenAI proxy, deployment endpoints까지 통합한다. (출처: [.NET DevUIExtensions summary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.DevUI/DevUIExtensions.cs#L8-L30), [Python DevServer class docs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/devui/agent_framework_devui/_server.py#L67-L93))

## 경계

DevUI는 dev/test용 interface이지 generic runtime contract가 아니다.

- `.NET`은 `AddOpenAIResponses`, `AddOpenAIConversations`, 그리고 해당 endpoint mapping이 선행되어야 DevUI가 그 위에서 동작한다.
- Python은 자체 API server를 띄우지만, auth/host binding/CORS는 DevServer 옵션으로 제어되는 **운영 경계가 있는 개발 도구**다.
- production deployment의 canonical application API를 대체하는 것이 아니다. (출처: [.NET DevUI requires OpenAI Responses and Conversations](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.DevUI/DevUIExtensions.cs#L18-L23), [Python DevServer mode/auth docs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/devui/agent_framework_devui/_server.py#L81-L93))

## 성숙도

| 구현 | 성숙도 |
|---|---|
| `.NET` `Microsoft.Agents.AI.DevUI` | `preview` |
| Python `agent-framework-devui` | `beta` |

출처:
- [.NET DevUI csproj](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.DevUI/Microsoft.Agents.AI.DevUI.csproj#L1-L13)
- [Python PACKAGE_STATUS devui row](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L29-L34)

## 공개 API·타입

### .NET
- `AddDevUI(this IHostApplicationBuilder, Action<DevUIOptions>? configure = null)`
- `MapDevUI(this IEndpointRouteBuilder)`
- `DevUIAuthFilter`
- `DevUIMiddleware`  
  (출처: [.NET HostApplicationBuilder AddDevUI](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.DevUI/HostApplicationBuilderExtensions.cs#L7-L33), [.NET MapDevUI](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.DevUI/DevUIExtensions.cs#L11-L64), [.NET DevUIAuthFilter](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.DevUI/DevUIAuthFilter.cs#L11-L104), [.NET DevUIMiddleware](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.DevUI/DevUIMiddleware.cs#L14-L260))

### Python
- `DevServer`
- `EntityDiscovery`
- internal OpenAI proxy/local executor composition
- package-level serve path via `agent_framework_devui` public entrypoints  
  (출처: [Python DevServer](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/devui/agent_framework_devui/_server.py#L67-L119), [EntityDiscovery](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/devui/agent_framework_devui/_discovery.py#L23-L52))

## 상세 실행 흐름

### .NET
1. host가 `AddDevUI(...)`로 services를 등록한다.
2. `MapDevUI()`는:
   - startup security warning check
   - `/meta`를 unauthenticated로 남겨 frontend bootstrapping에 사용
   - protected group에 custom endpoint conventions와 `DevUIAuthFilter`를 적용
   - `/devui` static frontend와 `/v1/entities` 계열 endpoint를 노출한다.  
   (출처: [.NET MapDevUI flow](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.DevUI/DevUIExtensions.cs#L38-L64))

### Python
1. `DevServer.get_app()`가 middleware(CORS, auth, Host header guard)를 구성한다.
2. `_register_routes()`가 `/health`, `/meta`, `/v1/entities`, `/v1/responses`, `/v1/conversations`, deployment endpoints를 매핑한다.
3. `/v1/responses`는 `X-Proxy-Backend` header가 `"openai"`면 OpenAI executor로, 아니면 local Agent Framework executor로 route한다.
4. entity execution 전 `_ensure_executor()` / `_ensure_openai_executor()`가 lazy init된다.  
   (출처: [Python middleware setup](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/devui/agent_framework_devui/_server.py#L397-L497), [route registration](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/devui/agent_framework_devui/_server.py#L499-L530), [executor initialization](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/devui/agent_framework_devui/_server.py#L219-L272), [responses endpoint routing](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/devui/agent_framework_devui/_server.py#L809-L883))

## 상태·영속화

### .NET
DevUI 자체가 독립 session store를 새로 정의하지는 않는다. underlying OpenAI Responses/Conversations services를 사용하고, keyed/non-keyed agent/workflow registrations을 resolve한다. 즉 DevUI state는 host가 이미 노출한 OpenAI-compatible backend의 state surface를 소비한다. (출처: [.NET DevUI prerequisite on responses/conversations](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.DevUI/DevUIExtensions.cs#L18-L23), [.NET DevUIExtensionsTests workflow/agent resolution](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.DevUI.UnitTests/DevUIExtensionsTests.cs#L42-L66))

### Python
Python DevServer는 자체적으로:
- discovered entities registry
- loaded object cache
- cleanup hooks
- running response task registry
- conversation/deployment managers  
를 가진다. entity discovery는 sparse scan 후 lazy load를 사용하고, hot reload를 위해 cache invalidation까지 지원한다. (출처: [EntityDiscovery fields](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/devui/agent_framework_devui/_discovery.py#L23-L35), [lazy loading and enrichment](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/devui/agent_framework_devui/_discovery.py#L75-L155), [invalidate_entity / invalidate_all](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/devui/agent_framework_devui/_discovery.py#L239-L288), [DevServer running tasks and managers](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/devui/agent_framework_devui/_server.py#L111-L116))

## 확장점

### .NET
- `DevUIOptions.AllowRemoteAccess`
- bearer token (`AuthToken` 또는 env var)
- endpoint convention hook `ConfigureEndpoints`
- keyed/non-keyed agent/workflow registrations that DevUI can discover  
  (출처: [.NET DevUIExtensions remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.DevUI/DevUIExtensions.cs#L24-L29), [.NET DevUIAuthFilter token source](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.DevUI/DevUIAuthFilter.cs#L24-L43))

### Python
- `entities_dir`
- `host` / `port`
- `cors_origins`
- `mode` (`developer` vs `user`)
- `auth_enabled`
- `auth_token`
- in-memory pending entity registration  
  (출처: [Python DevServer init options](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/devui/agent_framework_devui/_server.py#L71-L93), [set_pending_entities](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/devui/agent_framework_devui/_server.py#L117-L119))

## 동시성·스트리밍·취소

### .NET
`.NET` DevUI repo-local code 자체는 별도 streaming transport를 재정의하기보다, underlying OpenAI responses/conversations endpoints와 UI route serving을 결합한다. 핵심 concurrency/streaming contract는 backend responses service 쪽에 있다. (출처: [.NET DevUI prerequisites on OpenAI endpoints](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.DevUI/DevUIExtensions.cs#L18-L23))

### Python
- `/v1/responses/{response_id}/cancel`가 running task registry를 취소한다.
- streaming response는 hardcoded ACAO를 넣지 않고 CORSMiddleware에 맡긴다.
- `X-Response-ID`를 header에 넣어 cancellation/debug tracking에 사용한다.  
  (출처: [Python running tasks registry](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/devui/agent_framework_devui/_server.py#L113-L116), [responses streaming path headers](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/devui/agent_framework_devui/_server.py#L859-L877), [cancel endpoint](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/devui/agent_framework_devui/_server.py#L885-L907), [test no hardcoded ACAO](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/devui/tests/devui/test_server.py#L568-L588))

## 오류·검증·보안

### .NET
- default loopback-only
- non-loopback은 `AllowRemoteAccess` 없으면 403
- token configured 시 missing/wrong bearer token은 401
- `/meta`는 frontend bootstrap을 위해 unauthenticated reachable  
  (출처: [.NET DevUIAuthFilter loopback and token logic](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.DevUI/DevUIAuthFilter.cs#L46-L103), [.NET DevUI /meta rationale](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.DevUI/DevUIExtensions.cs#L49-L59))

### Python
- auth default-on
- non-loopback host + no explicit token은 `ValueError`
- loopback bind에서도 Host header allowlist를 강제
- `/meta`도 auth가 필요하도록 tests가 고정
- request content/metadata는 logs에 남기지 않도록 test가 존재  
  (출처: [Python auth/token resolution rules](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/devui/agent_framework_devui/_server.py#L141-L165), [auth middleware](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/devui/agent_framework_devui/_server.py#L414-L460), [host-header middleware](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/devui/agent_framework_devui/_server.py#L464-L492), [auth default and host restrictions tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/devui/tests/devui/test_server.py#L627-L756), [meta auth test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/devui/tests/devui/test_server.py#L797-L813), [log hygiene test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/devui/tests/devui/test_server.py#L657-L681))

## .NET 구현과 테스트

- `DevUIAccessControlTests`는 non-loopback default deny, remote allow override, bearer token, env token, `/meta` accessibility를 검증한다. (출처: [.NET DevUIAccessControlTests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.DevUI.UnitTests/DevUIAccessControlTests.cs#L40-L181))
- `DevUIExtensionsTests`는 keyed/non-keyed workflows 및 agents의 resolution을 검증한다. (출처: [.NET DevUIExtensionsTests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.DevUI.UnitTests/DevUIExtensionsTests.cs#L42-L220))

## Python 구현과 테스트

- `test_server.py`는 auth defaults, loopback/no-auth rules, host header allowlist, meta auth, streaming CORS, log hygiene를 검증한다. (출처: [Python test_server security posture section](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/devui/tests/devui/test_server.py#L557-L840))
- `test_discovery.py`는 agent/workflow discovery, empty directory, lazy load, type detection을 검증한다. (출처: [Python test_discovery](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/devui/tests/devui/test_discovery.py#L14-L220))

## 문서 차이

가장 큰 차이는 `/meta` 보안 정책이다.

- `.NET`은 `/meta`를 unauthenticated로 남긴다.
- Python tests는 `/meta`도 auth를 요구한다고 고정한다.

즉 같은 DevUI 범주지만 code-first behavior는 다르다. (출처: [.NET /meta unauthenticated note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.DevUI/DevUIExtensions.cs#L49-L59), [Python meta auth test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/devui/tests/devui/test_server.py#L797-L813))

## Java 결정

- DevUI는 **개발 도구 전용 module**로 두어야 한다.
- production runtime dependency가 아니라 별도 dev/test artifact로 유지하는 것이 적절하다.
- `meta`, discovery, local execution, optional proxy, deployment helpers를 한 곳에 두되, host binding과 auth policy는 Spring profile에 맞춰 별도 설정 가능하게 해야 한다.

## Acceptance scenarios

1. loopback-only default 또는 explicit remote access toggle이 있어야 한다.
2. discovered agent/workflow를 UI에서 stable하게 resolve할 수 있어야 한다.
3. `/meta`, `/v1/entities`, `/v1/responses`, `/v1/conversations` 등 DevUI endpoints가 일관된 auth policy를 가져야 한다.
4. Python path에서는 running response를 cancellation endpoint로 중단할 수 있어야 한다.
5. logs에 user input/metadata 같은 민감한 request payload가 새어나가지 않아야 한다.

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

## 목적

Aspire integration의 목적은 DevUI를 **분산 애플리케이션(AppHost) 안에서 여러 agent backend를 묶는 통합 개발 UI**로 제공하는 것이다. aggregator는 별도 container image 없이 AppHost 프로세스 안에서 reverse proxy처럼 동작하고, 여러 backend의 entity listing과 API route를 하나로 모은다. (출처: [Aspire AddDevUI remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Aspire.Hosting.AgentFramework.DevUI/AgentFrameworkBuilderExtensions.cs#L20-L37), [DevUIAggregatorHostedService summary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Aspire.Hosting.AgentFramework.DevUI/DevUIAggregatorHostedService.cs#L25-L29))

## 경계

Aspire integration은:

- development-time AppHost convenience
- in-process reverse proxy / aggregator
- resource annotation 기반 backend aggregation

에 집중한다. 배포 manifest에서는 제외되며, production public API gateway를 대체하는 기능이 아니다. Python에는 동일한 Aspire-specific integration이 보이지 않는다. (출처: [Aspire AddDevUI ExcludeFromManifest](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Aspire.Hosting.AgentFramework.DevUI/AgentFrameworkBuilderExtensions.cs#L57-L61), [same file dev-only remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Aspire.Hosting.AgentFramework.DevUI/AgentFrameworkBuilderExtensions.cs#L29-L37))

## 성숙도

| 구현 | 성숙도 |
|---|---|
| `.NET` `Aspire.Hosting.AgentFramework.DevUI` | `preview` |
| Python | 해당 없음 |

출처:
- [Aspire DevUI csproj](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Aspire.Hosting.AgentFramework.DevUI/Aspire.Hosting.AgentFramework.DevUI.csproj#L3-L18)

## 공개 API·타입

- `IDistributedApplicationBuilder.AddDevUI(name, port?)`
- `IResourceBuilder<DevUIResource>.WithAgentService(...)`
- `DevUIResource`
- internal `DevUIAggregatorHostedService`  
  (출처: [Aspire AddDevUI public API](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Aspire.Hosting.AgentFramework.DevUI/AgentFrameworkBuilderExtensions.cs#L15-L49), [WithAgentService](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Aspire.Hosting.AgentFramework.DevUI/AgentFrameworkBuilderExtensions.cs#L127-L184), [DevUIResource](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Aspire.Hosting.AgentFramework.DevUI/DevUIResource.cs#L7-L49))

## 상세 실행 흐름

1. AppHost가 `AddDevUI("devui")`를 호출한다.
2. `InitializeResourceEvent` 시점에 in-process `DevUIAggregatorHostedService`를 생성한다.
3. `BeforeResourceStartedEvent`를 publish해 backend dependencies를 기다린다.
4. aggregator가 start되고 allocated endpoint가 resource annotation에 기록된다.
5. Dashboard에는 `http://localhost:{port}/devui/` URL이 노출된다.
6. app stopping 시 aggregator를 stop/dispose한다. (출처: [Aspire AddDevUI initialization flow](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Aspire.Hosting.AgentFramework.DevUI/AgentFrameworkBuilderExtensions.cs#L62-L122))

Aggregator 자체는:

- `/health`
- `/v1/entities`
- `/v1/entities/{**entityPath}`
- `/v1/responses`
- `/v1/conversations/{**path}`
- `/meta`
- `/devui/{**path}`

를 노출하고, frontend는 embedded resources가 있으면 직접 서빙하고 없으면 첫 backend에서 proxy한다. (출처: [DevUIAggregatorHostedService route map](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Aspire.Hosting.AgentFramework.DevUI/DevUIAggregatorHostedService.cs#L268-L280), [serve embedded or proxy frontend](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Aspire.Hosting.AgentFramework.DevUI/DevUIAggregatorHostedService.cs#L154-L211))

## 상태·영속화

Aspire aggregator는 자체 agent execution state를 소유하지 않는다. 대신:
- backend URL resolution cache-less lookup
- `conversationId -> backend URL` map
- resource annotations 기반 metadata
를 유지한다. 특히 `conversationBackendMap`은 `agent_id`가 없는 후속 conversation GET routing을 위해 사용된다. (출처: [conversationBackendMap field](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Aspire.Hosting.AgentFramework.DevUI/DevUIAggregatorHostedService.cs#L39-L45), [ResolveBackends no caching note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Aspire.Hosting.AgentFramework.DevUI/DevUIAggregatorHostedService.cs#L283-L308))

## 확장점

- `port`
- `WithAgentService(resource, agents, entityIdPrefix)`
- backend가 자체 `/v1/entities`를 노출하지 않아도 AppHost-declared `agents` metadata로 entity listing 생성 가능  
  (출처: [AddDevUI signature](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Aspire.Hosting.AgentFramework.DevUI/AgentFrameworkBuilderExtensions.cs#L49-L53), [WithAgentService docs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Aspire.Hosting.AgentFramework.DevUI/AgentFrameworkBuilderExtensions.cs#L127-L154), [entity listing build from annotations](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Aspire.Hosting.AgentFramework.DevUI/DevUIAggregatorHostedService.cs#L335-L360))

## 동시성·스트리밍·취소

Aspire aggregator의 primary concern은 proxying과 aggregation이다. stateful execution concurrency보다 중요한 것은:
- backend URL의 late allocation 대응
- proxy request routing
- frontend serving fallback

이다. 별도 task cancellation surface는 보이지 않는다. (출처: [ResolveBackendUrl late availability handling](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Aspire.Hosting.AgentFramework.DevUI/DevUIAggregatorHostedService.cs#L310-L333))

## 오류·검증·보안

- aggregator startup failure는 resource state를 `FailedToStart`로 publish한다.
- frontend assembly가 없으면 embedded serve 대신 backend proxy fallback을 사용한다.
- backend entity fetch 실패는 warning log 후 다른 backends aggregation을 계속 진행한다. (출처: [startup failure handling](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Aspire.Hosting.AgentFramework.DevUI/AgentFrameworkBuilderExtensions.cs#L111-L120), [frontend resource load fallback](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Aspire.Hosting.AgentFramework.DevUI/DevUIAggregatorHostedService.cs#L114-L152), [entity fetch warning path](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Aspire.Hosting.AgentFramework.DevUI/DevUIAggregatorHostedService.cs#L370-L418))

## .NET 구현과 테스트

- `DevUIAggregatorHostedServiceTests`는 querystring agent_id rewrite, backend annotation wiring 등 aggregator behavior를 검증한다. (출처: [DevUIAggregatorHostedServiceTests query rewrite](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Aspire.Hosting.AgentFramework.DevUI.UnitTests/DevUIAggregatorHostedServiceTests.cs#L27-L163), [WithAgentService annotation behavior](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Aspire.Hosting.AgentFramework.DevUI.UnitTests/DevUIAggregatorHostedServiceTests.cs#L167-L220))

## Python 구현과 테스트

- 해당 없음. inspected repo에는 Python Aspire-specific DevUI integration이 없다.

## 문서 차이

- 큰 code/doc mismatch는 보이지 않았다.
- 다만 이 기능은 루트 README의 DevUI bullet보다 훨씬 구체적으로, 실제로는 **multi-backend in-process reverse proxy**라는 점이 source comments에 더 분명하다. (출처: [루트 README DevUI bullet](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/README.md#L58-L59), [Aspire AddDevUI detailed remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Aspire.Hosting.AgentFramework.DevUI/AgentFrameworkBuilderExtensions.cs#L23-L37))

## Java 결정

- Aspire에 대응하는 Java distributed app orchestrator가 있다면 **dev-only aggregator module**로 분리하는 것이 맞다.
- production API gateway와 섞지 말고, local dashboard/dev shell의 companion service처럼 다뤄야 한다.

## Acceptance scenarios

1. AppHost 안에서 여러 backend agent service를 하나의 DevUI entrypoint로 aggregate할 수 있어야 한다.
2. backend가 `/v1/entities`를 노출하지 않아도 AppHost metadata만으로 entity listing을 만들 수 있어야 한다.
3. frontend bundle이 없으면 첫 backend proxy fallback이 동작해야 한다.
4. startup failure가 resource state로 명확히 surface되어야 한다.

## Source inventory

- [dotnet/src/Aspire.Hosting.AgentFramework.DevUI/Aspire.Hosting.AgentFramework.DevUI.csproj](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Aspire.Hosting.AgentFramework.DevUI/Aspire.Hosting.AgentFramework.DevUI.csproj#L1-L36)
- [dotnet/src/Aspire.Hosting.AgentFramework.DevUI/AgentFrameworkBuilderExtensions.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Aspire.Hosting.AgentFramework.DevUI/AgentFrameworkBuilderExtensions.cs#L15-L185)
- [dotnet/src/Aspire.Hosting.AgentFramework.DevUI/DevUIAggregatorHostedService.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Aspire.Hosting.AgentFramework.DevUI/DevUIAggregatorHostedService.cs#L25-L418)
- [dotnet/src/Aspire.Hosting.AgentFramework.DevUI/DevUIResource.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Aspire.Hosting.AgentFramework.DevUI/DevUIResource.cs#L7-L49)
- [dotnet/tests/Aspire.Hosting.AgentFramework.DevUI.UnitTests/DevUIAggregatorHostedServiceTests.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Aspire.Hosting.AgentFramework.DevUI.UnitTests/DevUIAggregatorHostedServiceTests.cs#L22-L220)

---

## 4. ChatKit

## 목적

ChatKit integration의 목적은 Agent Framework agent stream을 **OpenAI ChatKit server/frontend protocol**에 맞는 thread item과 stream event로 연결하는 것이다. package는 Agent SDK integration과 유사한 seam을 제공한다. (출처: [Python ChatKit README overview](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/chatkit/README.md#L1-L14), [Python chatkit __init__](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/chatkit/agent_framework_chatkit/__init__.py#L3-L25))

## 경계

ChatKit package가 하는 일은:
- ChatKit thread items → Agent Framework `Message[]` 변환
- Agent Framework `AgentResponseUpdate` stream → ChatKit `ThreadStreamEvent` 변환

이다. 반대로:
- full web host
- frontend hosting
- thread store / attachment store
- domain allowlist setup
- OpenAI CDN dependency 해소  
는 host application 또는 OpenAI ChatKit ecosystem 책임이다. 특히 frontend는 self-hostable이 아니라고 README가 명시한다. (출처: [ChatKit README what this package provides](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/chatkit/README.md#L5-L14), [frontend requirements and network dependencies](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/chatkit/README.md#L25-L40), [air-gapped limitations](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/chatkit/README.md#L42-L52))

## 성숙도

| 구현 | 성숙도 |
|---|---|
| Python `agent-framework-chatkit` | `beta` |
| `.NET` repo-local | 확인된 대응 package 없음 |

출처:
- [Python PACKAGE_STATUS chatkit row](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L24-L27)

## 공개 API·타입

- `ThreadItemConverter`
- `simple_to_agent_input`
- `stream_agent_response`  
  (출처: [Python chatkit __all__](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/chatkit/agent_framework_chatkit/__init__.py#L12-L25))

## 상세 실행 흐름

### Inbound path
`ThreadItemConverter`는 ChatKit `UserMessageItem`/thread item을 Agent Framework `Message`로 바꾼다.

- text parts를 합쳐 user message text로 만든다.
- attachments가 있으면 attachment fetcher를 통해 content로 바꾼다.
- quoted text가 있으면 context message를 prepend할 수 있다.  
  (출처: [ThreadItemConverter class docs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/chatkit/agent_framework_chatkit/_converter.py#L39-L61), [user_message_to_input](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/chatkit/agent_framework_chatkit/_converter.py#L63-L120))

### Outbound path
`stream_agent_response(response_stream, thread_id, generate_id=None)`는 Agent Framework `AgentResponseUpdate` stream을 받아:

1. first delta 전에 `ThreadItemAddedEvent`
2. 각 text chunk마다 `ThreadItemUpdated` + `AssistantMessageContentPartTextDelta`
3. 마지막에 누적 text를 담은 `ThreadItemDoneEvent`

를 생성한다. 즉 ChatKit UI가 token-by-token streaming을 할 수 있게 한다. (출처: [stream_agent_response docs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/chatkit/agent_framework_chatkit/_streaming.py#L24-L49), [event emission logic](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/chatkit/agent_framework_chatkit/_streaming.py#L61-L108))

### End-to-end sample
sample `WeatherChatKitServer`는:
- thread store에서 full history load
- `simple_to_agent_input` 변환
- `agent.run(..., stream=True)`
- `stream_agent_response(...)`
- widget stream  
순으로 ChatKit backend를 구성한다. (출처: [ChatKit integration sample architecture and key points](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/samples/05-end-to-end/chatkit-integration/README.md#L82-L129))

## 상태·영속화

ChatKit integration package 자체는 persistence를 제공하지 않는다. thread/message/attachment persistence는 ChatKit `Store` / `AttachmentStore` 구현이 책임지며, sample은 `SQLiteStore`와 `FileBasedAttachmentStore`를 사용한다. 즉 channel adapter는 stateless conversion layer이고 durable state는 host가 구성해야 한다. (출처: [ChatKit README example note on your_store](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/chatkit/README.md#L70-L74), [sample store architecture](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/samples/05-end-to-end/chatkit-integration/README.md#L96-L108))

## 확장점

- custom attachment fetcher (`ThreadItemConverter`)
- custom ID generator (`stream_agent_response`)
- custom ChatKit store / attachment store
- custom widget rendering in sample app  
  (출처: [ThreadItemConverter attachment_data_fetcher](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/chatkit/agent_framework_chatkit/_converter.py#L42-L61), [stream_agent_response generate_id](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/chatkit/agent_framework_chatkit/_streaming.py#L24-L44), [sample widget rendering mention](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/samples/05-end-to-end/chatkit-integration/README.md#L110-L129))

## 동시성·스트리밍·취소

- stream helper는 multi-chunk text를 delta event로 순서 보존 전송한다.
- explicit cancellation API는 package에 없다; underlying server/framework가 처리해야 한다.
- empty stream은 event를 하나도 내지 않는다. (출처: [stream_agent_response implementation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/chatkit/agent_framework_chatkit/_streaming.py#L61-L108), [empty stream test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/chatkit/tests/test_streaming.py#L21-L35))

## 오류·검증·보안

- package 자체는 credential/auth layer를 제공하지 않는다.
- frontend는 OpenAI CDN, `chatgpt.com`, Mixpanel로 outbound network dependency를 가지므로 air-gapped/highly regulated environment에는 부적합하다.
- production frontend는 OpenAI domain allowlist와 domain key 설정이 필요하다. (출처: [frontend network requirements](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/chatkit/README.md#L25-L40), [air-gapped limitations](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/chatkit/README.md#L42-L52), [sample domain key configuration](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/samples/05-end-to-end/chatkit-integration/README.md#L153-L165))

## .NET 구현과 테스트

- inspected repo 범위에서 `.NET` 대응 ChatKit integration package는 확인되지 않았다.

## Python 구현과 테스트

- `test_streaming.py`는 empty stream, single text update, multi-chunk text update, custom id generator, empty content, non-text content 처리를 검증한다. (출처: [ChatKit streaming tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/chatkit/tests/test_streaming.py#L21-L157))
- `test_converter.py`는 `simple_to_agent_input` basic behavior를 검증한다. (출처: [ChatKit converter tests excerpt](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/chatkit/tests/test_converter.py#L664-L692))

## 문서 차이

- 큰 code/doc mismatch는 확인되지 않았다.
- 다만 README가 frontend 네트워크 의존성을 강하게 경고하고 있으며, source implementation은 이 문제를 완화하려는 별도 fallback을 제공하지 않는다. 따라서 “self-hostable backend”와 “self-hostable UX stack”을 구분해서 읽어야 한다. (출처: [ChatKit README backend vs frontend distinction](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/chatkit/README.md#L42-L52))

## Java 결정

- ChatKit은 **optional integration module**로 두는 것이 맞다.
- backend adapter만 제공하고, frontend delivery stack은 host app이 별도로 결정하게 두는 편이 안전하다.
- external CDN/domain allowlist dependency가 있는 UI stack이라면 Java backend package에서 숨기지 말고 명시적으로 surface해야 한다.

## Acceptance scenarios

1. ChatKit thread items를 Agent Framework message history로 변환할 수 있어야 한다.
2. Agent Framework text stream이 ChatKit delta events로 실시간 반영되어야 한다.
3. attachment-aware inbound conversion이 custom fetcher로 확장 가능해야 한다.
4. backend는 self-hostable이어도 frontend는 외부 네트워크 의존성이 있다는 사실이 명확해야 한다.

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

## 목적

Telegram adapter의 목적은 Telegram Bot API `Update` shape와 Agent Framework run/result를 양방향 변환하는 것이다. 즉 inbound message, edited message, callback query, media attachment를 Agent Framework `Message`/`Content`로 바꾸고, final/streaming response를 Telegram Bot API operation으로 바꾼다. (출처: [Python hosting-telegram README summary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-telegram/README.md#L1-L24), [Python parsing helper docs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-telegram/agent_framework_hosting_telegram/_parsing.py#L3-L13), [Python rendering helper docs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-telegram/agent_framework_hosting_telegram/_rendering.py#L3-L9))

## 경계

이 패키지는 **helper-only**다. 제공하지 않는 것은 다음이다.

- Bot API client
- polling/webhook lifecycle
- hosting/channel registry
- long-running service
- rate limit / retry policy
- command dispatch policy
- durable store selection

즉 Telegram integration은 application-owned bot service와 함께 써야 한다. (출처: [Telegram README responsibilities](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-telegram/README.md#L5-L24))

## 성숙도

| 구현 | 성숙도 |
|---|---|
| Python `agent-framework-hosting-telegram` | `alpha` |
| `.NET` repo-local | 확인된 대응 package 없음 |

출처:
- [Python PACKAGE_STATUS hosting-telegram row](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L37-L41)

## 공개 API·타입

- `telegram_chat_id`
- `telegram_session_id`
- `telegram_command`
- `telegram_callback_query_id`
- `telegram_media_file_id`
- `telegram_to_run`
- `telegram_from_run`
- `telegram_from_streaming_run`
- `TelegramOperation`  
  (출처: [Python hosting-telegram __all__](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-telegram/agent_framework_hosting_telegram/__init__.py#L7-L43))

## 상세 실행 흐름

### Inbound
1. helper가 `message`, `edited_message`, `callback_query` 중 하나를 찾는다.
2. `telegram_chat_id()`가 chat id를 추출한다.
3. `telegram_session_id(update, bot_id=...)`가 private chat이면 `telegram:<bot_id>:<user_id>`, 그 외는 `telegram:<bot_id>:<chat_id>`를 만든다.
4. `telegram_to_run(...)`는:
   - text / caption
   - optional resolved media URL
   - callback query data  
   를 Agent Framework `Message`/`Content`로 만든다. (출처: [chat_id extraction](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-telegram/agent_framework_hosting_telegram/_parsing.py#L61-L85), [session_id rules](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-telegram/agent_framework_hosting_telegram/_parsing.py#L88-L129), [telegram_to_run flow](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-telegram/agent_framework_hosting_telegram/_parsing.py#L262-L320))

### Outbound
- `telegram_from_run(...)`:
  - first image 있으면 `sendPhoto`
  - 아니면 `sendMessage`
  - empty response는 `"(no response)"` fallback
- `telegram_from_streaming_run(...)`:
  - cumulative text를 `editMessageText`
  - final images는 `sendPhoto`
  - image-only final response는 placeholder 삭제 후 `sendPhoto`  
  (출처: [telegram_from_run](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-telegram/agent_framework_hosting_telegram/_rendering.py#L63-L100), [telegram_from_streaming_run](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-telegram/agent_framework_hosting_telegram/_rendering.py#L103-L176))

### Sample app
sample webhook app은:
- aiogram `Bot` + `Dispatcher`
- `telegram_to_run(..., stream=True)`
- placeholder message 생성
- `telegram_from_streaming_run(...)` operations 실행
- stream 종료 후 stable per-chat session key에 `state.set_session(...)`  
  경로를 취한다. (출처: [sample app overview](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/samples/04-hosting/af-hosting/local_telegram/app.py#L16-L40), [tool + agent setup](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/samples/04-hosting/af-hosting/local_telegram/app.py#L88-L120), [handle_update run and render path](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/samples/04-hosting/af-hosting/local_telegram/app.py#L176-L243))

## 상태·영속화

Telegram helper package는 자체 store를 들고 있지 않다. sample은 `AgentState`를 쓰고, `telegram_session_id(...)`가 만든 stable key 아래 `AgentSession`을 저장한다. `/new` command는 해당 key를 지워 새 session을 시작한다. 즉 Telegram adapter는 state key policy를 제공하지만 persistence backend는 host가 선택한다. (출처: [README sessions/storage note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-telegram/README.md#L20-L24), [sample /new command deletes session](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/samples/04-hosting/af-hosting/local_telegram/app.py#L162-L166), [stream finalization persists session](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/samples/04-hosting/af-hosting/local_telegram/app.py#L241-L243))

## 확장점

- custom `resolve_file_url(file_id)` implementation
- app-owned command table/command effects
- actual Bot API HTTP client implementation
- parse mode / placeholder policy / edit throttling
- session store backend 교체  
  (출처: [README helper list](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-telegram/README.md#L25-L54), [sample resolve_file_url hook](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/samples/04-hosting/af-hosting/local_telegram/app.py#L193-L203))

## 동시성·스트리밍·취소

- sample은 `session_locks` dict로 chat/session별 updates를 serialize한다.
- streaming edits는 `EDIT_INTERVAL_SECONDS`로 throttle한다.
- helper package 자체는 cancel semantics를 정의하지 않는다. (출처: [sample session_locks and throttling constants](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/samples/04-hosting/af-hosting/local_telegram/app.py#L78-L82), [session lock use](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/samples/04-hosting/af-hosting/local_telegram/app.py#L187-L189), [edit throttling loop](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/samples/04-hosting/af-hosting/local_telegram/app.py#L227-L239))

## 오류·검증·보안

- media-only message가 있고 resolver가 없거나 `None`을 돌려주면 `ValueError`
- Telegram webhook secret은 delivery authenticity만 검증하고 end-user authorization은 별개
- helper는 rate limit / retry를 처리하지 않는다. (출처: [parsing unresolved media error](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-telegram/agent_framework_hosting_telegram/_parsing.py#L276-L291), [sample production readiness warning](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/samples/04-hosting/af-hosting/local_telegram/app.py#L24-L32), [README responsibilities incl. rate limits/retries](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-telegram/README.md#L10-L18))

## .NET 구현과 테스트

- inspected repo 범위에서 `.NET` 대응 Telegram hosting package는 확인되지 않았다.

## Python 구현과 테스트

- `test_parsing.py`는 chat id, private/group session id, command normalization, media file selection, callback query, unresolved media failure를 검증한다. (출처: [parsing tests chat/session/command](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-telegram/tests/hosting_telegram/test_parsing.py#L35-L115), [media tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-telegram/tests/hosting_telegram/test_parsing.py#L127-L183), [telegram_to_run tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-telegram/tests/hosting_telegram/test_parsing.py#L186-L260))
- `test_rendering.py`는 sendMessage/sendPhoto fallback, truncation, streaming edits, image-only deleteMessage path, iterator/finalizer error propagation을 검증한다. (출처: [rendering tests final responses](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-telegram/tests/hosting_telegram/test_rendering.py#L24-L126), [streaming rendering tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-telegram/tests/hosting_telegram/test_rendering.py#L128-L255))

## 문서 차이

- 큰 code/doc mismatch는 보이지 않았다.
- helper-only package라는 문서 설명과 실제 코드가 잘 일치한다. (출처: [README helper-only statement](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-telegram/README.md#L3-L8), [public exports](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-telegram/agent_framework_hosting_telegram/__init__.py#L7-L43))

## Java 결정

- Telegram은 **optional channel adapter**로 두는 것이 맞다.
- update parsing / session key helper / Bot API operation renderer만 제공하고,
- webhook/polling lifecycle, retries, auth, persistence는 host app이 직접 책임지는 구조가 적절하다.

## Acceptance scenarios

1. private chat과 group chat이 서로 다른 stable session key 규칙을 가져야 한다.
2. `/command@bot args`가 normalized command string으로 parse되어야 한다.
3. text response는 `sendMessage`, image response는 `sendPhoto`, image-only final stream은 `deleteMessage` + `sendPhoto`가 되어야 한다.
4. media-only input은 resolver 없으면 fail-fast 해야 한다.
5. multi-update race를 host가 session별 lock으로 제어할 수 있어야 한다.

## Source inventory

- [python/packages/hosting-telegram/README.md](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-telegram/README.md#L1-L85)
- [python/packages/hosting-telegram/agent_framework_hosting_telegram/__init__.py](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-telegram/agent_framework_hosting_telegram/__init__.py#L1-L43)
- [python/packages/hosting-telegram/agent_framework_hosting_telegram/_parsing.py](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-telegram/agent_framework_hosting_telegram/_parsing.py#L3-L320)
- [python/packages/hosting-telegram/agent_framework_hosting_telegram/_rendering.py](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-telegram/agent_framework_hosting_telegram/_rendering.py#L3-L177)
- [python/packages/hosting-telegram/tests/hosting_telegram/test_parsing.py](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-telegram/tests/hosting_telegram/test_parsing.py#L1-L260)
- [python/packages/hosting-telegram/tests/hosting_telegram/test_rendering.py](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-telegram/tests/hosting_telegram/test_rendering.py#L1-L256)
- [python/samples/04-hosting/af-hosting/local_telegram/app.py](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/samples/04-hosting/af-hosting/local_telegram/app.py#L1-L260)

---

## 6. 기타 확인된 channel / protocol adapters

## 목적

이 하위 섹션의 목적은 **이 저장소에서 추가로 확인된 channel/protocol adapter families가 존재한다는 사실과, 왜 이 문서에서 상세를 다루지 않는지**를 기록하는 것이다. inspected tree 기준으로 “Protocols & UI” 범주에는 A2A, hosting-a2a, hosting-mcp, AG-UI, ChatKit, DevUI가 보이며, 루트 README와 hosting sample tree에서는 Foundry Hosted Agents, OpenAI Responses-compatible hosting, Telegram도 확인된다. (출처: [Python AGENTS.md Protocols & UI list](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/AGENTS.md#L115-L121), [루트 README Foundry Hosted Agents and DevUI](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/README.md#L48-L59))

## 경계

이 문서에서 상세를 다루지 않는 confirmed adapter families는 다음이다.

- OpenAI Responses-compatible hosting
- A2A
- AG-UI
- MCP hosting

이들은 각각 별도 문서 범위다. 이 섹션은 general provider catalog를 대신하지 않고, 단지 **repo-local에 channel/protocol adapter family가 실제로 존재한다는 확인 메모** 역할만 한다. (출처: [Python AGENTS.md Protocols & UI list](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/AGENTS.md#L115-L121))

## 성숙도

이 하위 기능군 자체의 maturity는 각 별도 문서 범위에서 다루는 package state를 따른다. 이 문서에서는 요약만 남긴다.

- A2A: Python `beta`, .NET `preview`
- AG-UI: Python `released`, .NET hosting `preview`
- MCP hosting helper: Python `alpha`
- OpenAI Responses hosting: Python `alpha`, .NET `alpha`/existing route-owning stack coexistence  
  (출처: [Python PACKAGE_STATUS excerpt](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L17-L20), [Python PACKAGE_STATUS hosting rows](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L37-L40))

## 공개 API·타입

이 섹션의 목적은 상세 API 설명이 아니다. 다만 repo-local public export 기준으로:

- A2A: remote client wrapper + hosting helpers
- AG-UI: protocol adapter + endpoint/client
- MCP hosting: tool adapters
- Responses hosting: request/response helpers  
가 확인된다. (출처: [Python AGENTS list](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/AGENTS.md#L115-L121))

## 상세 실행 흐름

상세 flow는 각 개별 문서 소유다. 이 문서에서는 반복하지 않는다.

## 상태·영속화

각 feature가 서로 다른 continuation/state model을 가지므로 본 섹션에서는 통합 정리를 하지 않는다. identity/session routing 일반론도 별도 문서 범위다.

## 확장점

각 feature마다 protocol-specific 확장점이 다르므로 별도 문서에서 다룬다.

## 동시성·스트리밍·취소

각 feature가 서로 다른 transport와 event model을 가지므로 별도 문서에서 다룬다.

## 오류·검증·보안

각 feature별로 trust boundary가 크게 다르므로 별도 문서에서 다룬다.

## .NET 구현과 테스트

repo-local source는 위 protocol/UI adapter families가 실제로 존재함을 보여 준다. 상세 구현과 tests는 개별 문서 범위다.

## Python 구현과 테스트

Python `AGENTS.md`와 package structure가 channel/protocol adapter family 존재를 가장 직접적으로 보여 준다. (출처: [Python AGENTS.md](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/AGENTS.md#L115-L121))

## 문서 차이

- 이 섹션은 intentional boundary note라서 code/doc mismatch를 논하지 않는다.

## Java 결정

- Java에서도 channel/protocol adapter는 **core에서 분리된 optional artifact family**로 설계해야 한다.
- 하나의 “all channels” 패키지보다, protocol별 artifact가 버전·성숙도·보안 경계를 더 잘 드러낸다.

## Acceptance scenarios

1. repo 구조만 봐도 channel/protocol adapter families가 명확히 식별 가능해야 한다.
2. 각 adapter family는 core와 분리된 artifact 경계를 가져야 한다.
3. 문서 구조도 feature별로 분리되어야 하며, 한 문서가 모든 protocol을 섞어 설명하지 않아야 한다.

## Source inventory

- [python/AGENTS.md](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/AGENTS.md#L115-L121)
- [README.md](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/README.md#L48-L59)

## 결론

Foundry hosting, DevUI, Aspire integration, ChatKit, Telegram은 이 저장소에서 “generic hosting core 위에 얹힌 운영/개발/채널 계층”의 서로 다른 모습을 보여 준다. Foundry는 managed runtime adapter, DevUI/Aspire는 개발 도구와 multi-backend aggregator, ChatKit과 Telegram은 Python 중심 channel adapter다. 그리고 이들 외에도 confirmed protocol/channel adapter families가 존재하지만, 저장소의 설계 방향은 그것들을 하나의 monolithic host framework로 합치지 않고 **feature별 adapter**로 유지하는 쪽에 가깝다. Java에서도 이 원칙을 따라 core, host adapter, dev tooling, channel adapter를 분리하는 구성이 가장 자연스럽다. (출처: [루트 README feature bullets](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/README.md#L48-L59), [Python AGENTS Protocols & UI list](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/AGENTS.md#L115-L121))