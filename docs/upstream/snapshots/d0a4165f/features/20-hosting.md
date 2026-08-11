# 20. Hosting

## 범위

이 문서는 Microsoft Agent Framework의 **generic hosting lifecycle**, **hosting model과 protocol의 분리**, **host composition/lifecycle/scaling 책임**, 그리고 **.NET ASP.NET hosting 보조 레이어와 Python의 app-owned host seam**만 다룬다.  
OpenAI Responses, A2A, AG-UI, MCP, Foundry/DevUI/channel, identity/session routing의 세부 wire contract와 protocol별 auth/session 규칙은 각각 별도 문서에서 다루며, 여기서는 generic hosting과의 **접점과 경계**만 기술한다.  
이 문서의 기준은 고정 커밋 `d0a4165f170193ba1d026a259af40d35bb7eaefe`의 production source, tests, repo docs이다. (출처: [ADR-0027](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0027-hosting-channels.md#L50-L64), [Python hosting spec](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/specs/002-python-hosting-channels.md#L15-L24), [Python hosting sample README](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/samples/04-hosting/af-hosting/README.md#L6-L9), [.NET hosting sample README](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/samples/04-hosting/af-hosting/README.md#L9-L20))

## 요약

Agent Framework의 hosting 핵심은 “**프로토콜을 아는 helper/adapter**”와 “**실행 상태를 가진 generic hosting core**”를 분리하는 것이다. Python은 이 경계를 가장 엄격하게 구현해 `AgentState`/`WorkflowState`만 두고 route/auth/storage는 전부 앱이 소유하게 만들었고, .NET도 같은 방향을 따르지만 DI와 `AgentSessionStore`/`HostedWorkflowState`를 중심으로 구현한다. .NET에는 ASP.NET Core 전용 isolation-key helper가 별도 패키지로 존재하지만, 이것도 full host framework가 아니라 멀티유저 session namespace를 만드는 보조 레이어다. Host는 agent/workflow target의 생명주기, store 선택, checkpoint cursor, 인증/인가, 단일 writer 정책, multi-replica scaling 전략을 책임지며, generic hosting core는 그 중 **target resolution, session snapshotting, workflow resume**만 제공한다. (출처: [ADR-0027](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0027-hosting-channels.md#L74-L103), [ADR-0032](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0032-dotnet-hosting-protocol-helpers.md#L99-L143), [.NET Hosting csproj](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/Microsoft.Agents.AI.Hosting.csproj#L42-L46), [Python AgentState/WorkflowState](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting/agent_framework_hosting/_state.py#L74-L118))

---

## 목적

Hosting core의 목적은 특정 wire protocol을 몰라도 **하나의 agent 또는 workflow target을 애플리케이션이 소유한 서버 루트에 안정적으로 연결**하고, 여러 요청에 걸친 continuation 상태를 재사용할 수 있게 하는 것이다. 구체적으로는 다음 세 가지가 핵심이다.

1. **target resolution**: direct instance, factory, awaitable, builder 형태의 agent/workflow를 안정적으로 resolve한다.
2. **execution state**: agent session snapshot과 workflow checkpoint head를 요청 간에 이어준다.
3. **host seam**: route/auth/background/native client는 앱이 소유하되, generic state와 protocol adapter를 조합할 수 있게 한다.

Python 쪽 문서와 구현은 이를 “protocol helpers plus optional execution state”라고 명시하고, .NET ADR도 같은 방향에서 `AgentSessionStore`와 `HostedWorkflowState`를 protocol-neutral core로 위치시킨다. (출처: [ADR-0027 decision outcome](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0027-hosting-channels.md#L62-L103), [Python hosting README overview](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting/README.md#L3-L23), [ADR-0032 optional execution state](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0032-dotnet-hosting-protocol-helpers.md#L99-L143))

## 경계

이 문서의 hosting 범위는 다음까지만 포함한다.

- agent/workflow 등록과 resolve
- session store / checkpoint manager / checkpoint cursor
- app-owned route와의 접점
- DI/service lifetime 또는 callable/awaitable target lifecycle
- single-process vs durable/multi-replica 운영 책임의 분리

반대로 다음은 **이 문서의 상세 범위 밖**이다.

- OpenAI Responses payload 구조와 SSE 이벤트 상세
- A2A task lifecycle과 AG-UI event taxonomy
- MCP tool/result wire schema
- Foundry protocol v2 header contract
- DevUI API와 frontend behavior
- Telegram/ChatKit 채널별 inbound/outbound 메시지 규칙
- identity/session key routing의 protocol-specific 세부 규칙

즉, 이 문서는 “protocol adapter가 generic hosting core를 어떻게 사용하나”까지만 다루고, 각 adapter 자체의 wire semantics는 별도 문서로 넘긴다. (출처: [ADR-0027 helper families](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0027-hosting-channels.md#L105-L139), [Python hosting spec packages table](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/specs/002-python-hosting-channels.md#L64-L76), [ADR-0032 scope](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0032-dotnet-hosting-protocol-helpers.md#L144-L148))

---

## Hosting model과 protocol의 분리

### 핵심 구분

이 코드베이스는 **hosting model**과 **protocol**을 같은 것으로 보지 않는다.

- **hosting model**: 서버가 target을 어떻게 생성·주입·보존하고, 어떤 store/checkpoint/cursor를 쓰며, auth와 scaling을 어디서 담당하는가
- **protocol**: 외부 wire payload를 `Agent.run(...)`/`Workflow.run(...)` 값으로 바꾸고, 결과를 다시 외부 포맷으로 렌더링하는가

Python ADR는 protocol package가 `<protocol>_to_run(...)`, `<protocol>_from_run(...)`, `<protocol>_from_streaming_run(...)`, `<protocol>_session_id(...)` 같은 helper를 가져야 한다고 정의하고, generic hosting core는 `AgentState`/`WorkflowState`/`SessionStore` 같은 execution-state surface만 가져야 한다고 못박는다. .NET ADR도 OpenAI Responses용 public helper를 추가하면서, 그 이유를 “routing/auth/storage ownership model을 app으로 되돌리기 위한 것”이라고 설명한다. (출처: [ADR-0027 helper naming and families](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0027-hosting-channels.md#L105-L139), [Python hosting spec helper families](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/specs/002-python-hosting-channels.md#L78-L103), [ADR-0032 context/problem](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0032-dotnet-hosting-protocol-helpers.md#L14-L33), [ADR-0032 decision outcome](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0032-dotnet-hosting-protocol-helpers.md#L72-L98))

### 이 문서가 generic hosting에만 집중하는 이유

OpenAI Responses, A2A, AG-UI, MCP, Foundry, DevUI, Telegram, ChatKit은 모두 서로 다른 protocol/channel layer이지만, generic hosting 관점에서 보는 공통점은 같다.

- target을 **한 번 만들지 요청마다 만들지**
- session을 **copy-on-read snapshot**으로 읽을지
- workflow를 **restore-then-run**으로 이어갈지
- store를 **in-memory/dev only**로 둘지 **durable**로 둘지
- single-writer coordination을 **framework가 아닌 host가 책임질지**

이 공통점을 담당하는 부분이 바로 본 문서의 대상이다. (출처: [ADR-0027 session continuity](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0027-hosting-channels.md#L171-L238), [Python hosting README workflow state](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting/README.md#L112-L145), [.NET HostedWorkflowState remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/HostedWorkflowState.cs#L18-L39))

---

## Host composition / lifecycle / scaling 책임

### host가 반드시 책임지는 것

ADR와 samples, README를 종합하면 host는 다음을 책임진다.

- route declaration과 grouping
- dependency injection 및 target composition
- authentication / authorization
- request-derived id를 trusted key로 쓸지 여부
- background work / webhook acknowledgement policy
- response status codes와 framework-specific error mapping
- store selection (`in-memory`, file, redis, db, cloud store)
- per-session single-writer policy
- multi-replica continuity와 durable checkpoint/session design

이는 Python 쪽 문서에서 가장 명시적으로 드러나고, .NET helper-first sample들도 동일한 전제를 따른다. (출처: [ADR-0027 app responsibilities](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0027-hosting-channels.md#L74-L89), [Python hosting README route responsibilities](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting/README.md#L60-L71), [.NET af-hosting README](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/samples/04-hosting/af-hosting/README.md#L3-L20), [Python af-hosting README](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/samples/04-hosting/af-hosting/README.md#L1-L21))

### generic hosting core가 책임지는 것

반대로 hosting core는 다음만 책임진다.

- target resolve와 optional caching
- session snapshot lookup/set/delete
- workflow checkpoint head cursor 유지(.NET `HostedWorkflowState`)
- first-use session creation(Python `AgentState`, .NET store create-on-miss)
- restore-then-run workflow continuation

즉, generic hosting core는 “**어떻게 이어갈지**”는 돕지만 “**누가 그 이어가기를 허용받았는지**”는 판단하지 않는다. (출처: [Python AgentState](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting/agent_framework_hosting/_state.py#L74-L118), [Python AgentState.get_or_create_session](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting/agent_framework_hosting/_state.py#L163-L181), [.NET AgentSessionStore remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/AgentSessionStore.cs#L20-L45), [.NET HostedWorkflowState summary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/HostedWorkflowState.cs#L18-L22))

### lifecycle와 scaling 관점의 운영 규칙

Python ADR는 persistent single-process와 transient/multi-replica host를 명확히 구분한다.

- persistent single-process는 in-memory state를 dev/simple deployment에서 쓸 수 있다.
- transient host나 multi-replica host는 in-memory continuity에 의존하면 안 된다.
- workflow host는 `CheckpointStorage`와 필요 시 `session_id -> checkpoint_id` cursor를 durable하게 가져야 한다.

.NET `HostedWorkflowState` 문서도 같은 취지로, 기본 in-memory cursor는 common case 최적화일 뿐이고 durable `CheckpointManager`가 있어야 restart 이후 resume이 가능하다고 설명한다. (출처: [ADR-0027 persistent vs transient](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0027-hosting-channels.md#L193-L204), [.NET HostedWorkflowState durable remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/HostedWorkflowState.cs#L24-L39), [Python hosting README no eviction note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting/README.md#L63-L71))

---

## 성숙도

| 항목 | .NET | Python |
|---|---|---|
| Generic hosting core | `Microsoft.Agents.AI.Hosting` = `preview` | `agent-framework-hosting` = `alpha` |
| ASP.NET host seam | `Microsoft.Agents.AI.Hosting.AspNetCore` = `preview` | 별도 framework package 없음; helper-first seam |
| Session store feature | public package surface이나 store contract는 stable-ready preview 문맥 | core `SessionStore`/`FileSessionStore`는 feature-level experimental |

출처:
- [.NET Hosting csproj](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/Microsoft.Agents.AI.Hosting.csproj#L42-L46)
- [.NET Hosting.AspNetCore csproj](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AspNetCore/Microsoft.Agents.AI.Hosting.AspNetCore.csproj#L3-L8)
- [Python PACKAGE_STATUS](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L37-L41)
- [Python experimental SessionStore feature](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L139-L145)

---

## 공개 API·타입

## .NET generic hosting

- `HostApplicationBuilder.AddAIAgent(...)`
- `IServiceCollection.AddAIAgent(...)`
- `HostApplicationBuilder.AddWorkflow(...)`
- `AgentSessionStore`
- `AIHostAgent`
- `HostedWorkflowState`

이 조합은 DI-registered agent/workflow와 continuation state를 중심으로 설계되어 있다. agent는 keyed service registration으로, workflow는 keyed `Workflow` registration으로 노출된다. `AIHostAgent`는 store-backed session persistence wrapper이고, `HostedWorkflowState`는 workflow resume helper다. (출처: [HostApplicationBuilderAgentExtensions](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/HostApplicationBuilderAgentExtensions.cs#L11-L97), [AgentHostingServiceCollectionExtensions](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/AgentHostingServiceCollectionExtensions.cs#L11-L144), [HostApplicationBuilderWorkflowExtensions](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/HostApplicationBuilderWorkflowExtensions.cs#L11-L49), [AIHostAgent](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/AIHostAgent.cs#L10-L72), [HostedWorkflowState](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/HostedWorkflowState.cs#L18-L52))

## .NET ASP.NET hosting seam

- `UseClaimsBasedAgentIsolation(...)`
- `ClaimsIdentityAgentIsolationKeyProvider`
- `ClaimsIdentityAgentIsolationKeyProviderOptions`
- `IsolationKeyScopedAgentSessionStore`

이 패키지는 full HTTP host가 아니라 **authenticated principal에서 isolation key를 뽑아 session namespace를 scoping하는 보조 레이어**다. (출처: [ServiceCollectionExtensions](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AspNetCore/ServiceCollectionExtensions.cs#L13-L62), [ClaimsIdentityAgentIsolationKeyProvider](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AspNetCore/ClaimsIdentityAgentIsolationKeyProvider.cs#L13-L95), [IsolationKeyScopedAgentSessionStore](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/IsolationKeyScopedAgentSessionStore.cs#L9-L115))

## Python host seam

Python `agent-framework-hosting`의 public export는 다음만 노출한다.

- `AgentRunArgs`
- `AgentState`
- `SupportsBuild`
- `WorkflowRunArgs`
- `WorkflowState`

즉, 이 패키지는 protocol-neutral execution-state helper만 export하고, `SessionStore`는 core package에 남겨 둔다. (출처: [Python hosting __init__](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting/agent_framework_hosting/__init__.py#L1-L27))

---

## 상세 실행 흐름

## .NET: agent 등록과 resolve

1. host가 `AddAIAgent(name, ...)`로 keyed `AIAgent`를 등록한다.
2. DI는 configured lifetime으로 agent를 생성한다.
3. route 또는 protocol-specific adapter가 keyed agent를 resolve한다.
4. session continuity가 필요하면 `AgentSessionStore`를 통해 load/save한다.
5. 필요하면 `AIHostAgent`로 session-backed wrapper를 사용할 수 있다.

핵심 포인트는 **agent-side holder가 별도로 없고**, DI lifetime과 `AgentSessionStore`가 합쳐져 Python의 `AgentState` 역할을 분산 수행한다는 점이다. 이 비대칭성은 ADR-0032에서 의도적으로 설명된다. (출처: [AgentHostingServiceCollectionExtensions](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/AgentHostingServiceCollectionExtensions.cs#L25-L144), [AIHostAgent](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/AIHostAgent.cs#L24-L72), [ADR-0032 no agent-side holder](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0032-dotnet-hosting-protocol-helpers.md#L101-L143))

## .NET: workflow 등록과 restore-then-run

1. host가 `AddWorkflow(name, factory, lifetime)`로 keyed workflow를 등록한다.
2. 앱이 `HostedWorkflowState`를 workflow instance 또는 workflow factory로 만든다.
3. `RunOrResumeAsync(sessionId, input)` 호출 시:
   - in-memory cursor에서 checkpoint head를 찾고,
   - 없으면 `CheckpointManager.GetLatestCheckpointAsync`로 fallback하고,
   - 없으면 fresh run,
   - 있으면 checkpoint restore 후 새 input으로 run한다.
4. turn 종료 후 새 head checkpoint를 cursor에 기록한다.

resume semantics는 “halt된 실행을 입력 없이 이어붙이기”가 아니라 **restore-then-run-with-new-input**이다. (출처: [HostApplicationBuilderWorkflowExtensions](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/HostApplicationBuilderWorkflowExtensions.cs#L16-L49), [HostedWorkflowState constructors](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/HostedWorkflowState.cs#L55-L152), [HostedWorkflowState run/resume core](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/HostedWorkflowState.cs#L189-L269))

## .NET ASP.NET seam: principal → isolation key → scoped session id

1. `UseClaimsBasedAgentIsolation(...)`가 singleton `AgentIsolationKeyProvider`를 등록한다.
2. provider는 현재 `HttpContext.User`에서 configured claim을 읽는다.
3. `IsolationKeyScopedAgentSessionStore`는 그 key를 `escapedKey::sessionStoreId` 형식으로 붙여 inner store에 위임한다.
4. strict mode이면 key가 없을 때 fail-closed, non-strict이면 pass-through 한다.

즉 ASP.NET helper는 route를 만들지 않고, **멀티유저 session collision만 방지**한다. (출처: [UseClaimsBasedAgentIsolation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AspNetCore/ServiceCollectionExtensions.cs#L15-L62), [ClaimsIdentityAgentIsolationKeyProvider.GetIsolationKeyAsync](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AspNetCore/ClaimsIdentityAgentIsolationKeyProvider.cs#L69-L95), [IsolationKeyScopedAgentSessionStore](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/IsolationKeyScopedAgentSessionStore.cs#L51-L115))

## Python: `AgentState`

1. `AgentState(target, session_store=..., cache_target=...)`는 instance/callable/awaitable target을 받는다.
2. `get_target()`은 optional caching과 lock을 통해 target을 resolve한다.
3. `get_or_create_session(session_id)`는 store lookup 후 없으면 `target.create_session(session_id=...)`로 생성한다.
4. 생성된 session은 store에 저장하고, caller에는 independent working copy를 돌려준다.
5. caller는 run 완료 후 `set_session(session_id, session)`을 명시적으로 호출해야 한다.

Python 설계의 핵심은 **session creation이 store가 아니라 state object에 있다**는 점이다. (`microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting/agent_framework_hosting/_state.py#L82-L118`, [get_target](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting/agent_framework_hosting/_state.py#L119-L142), [get_or_create_session](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting/agent_framework_hosting/_state.py#L163-L181))

## Python: `WorkflowState`

1. direct `Workflow`, `build() -> Workflow`를 가진 builder, callable, awaitable을 받는다.
2. `SupportsBuild`이면 `build` method로 normalize 한다.
3. `get_target()`은 optional caching으로 workflow instance를 resolve한다.
4. checkpoint storage와 cursor는 `WorkflowState`가 아니라 **앱 코드가 직접** 넣는다.

이 때문에 Python workflow hosting은 generic helper 수준에서는 더 얇고, protocol adapter나 route code가 checkpoint cursor store를 직접 조합한다. (출처: [WorkflowState init](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting/agent_framework_hosting/_state.py#L193-L241), [WorkflowState.get_target](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting/agent_framework_hosting/_state.py#L243-L280), [Python hosting README workflow section](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting/README.md#L112-L145))

---

## 상태·영속화

## Agent session

### .NET
`.NET`의 generic contract는 `AgentSessionStore`다. `GetSessionAsync`는 저장된 session이 없으면 **새 session을 반환해도 되며**, 동시에 각 호출은 **독립 인스턴스**를 반환해야 한다. in-memory 구현은 `(agentId, sessionStoreId)` key 아래에 serialized `JsonElement`를 저장하고, 매번 deserialize하여 fresh copy semantics를 보장한다. (출처: [AgentSessionStore contract](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/AgentSessionStore.cs#L64-L106), [InMemoryAgentSessionStore](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/Local/InMemoryAgentSessionStore.cs#L42-L73))

### Python
Python core `SessionStore`는 `dict[str, AgentSession]` 기반이며, `get()`과 `set()` 모두 `copy.deepcopy`를 써서 stored snapshot과 working copy를 분리한다. `FileSessionStore`는 JSON 또는 MessagePack 파일에 typed envelope를 msgspec으로 encode/decode하고, custom state는 `register_state_type()` registry를 통해 cold-start restore를 가능하게 한다. (출처: [SessionStore](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_sessions.py#L1692-L1767), [FileSessionStore overview](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_sessions.py#L1769-L1797), [FileSessionStore init/get](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_sessions.py#L1806-L1858), [register_state_type](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_sessions.py#L268-L335))

## Workflow checkpoint와 snapshot head

### .NET
`HostedWorkflowState`는 `CheckpointManager`와 in-memory `sessionId -> CheckpointInfo` cursor를 유지한다. cursor miss 시에는 manager의 latest checkpoint를 읽어 restart 이후에도 resume이 가능하게 한다. 이 구조는 **head cursor + durable checkpoint store**의 2단 구조다. (출처: [HostedWorkflowState fields/remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/HostedWorkflowState.cs#L18-L39), [cursor miss fallback](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/HostedWorkflowState.cs#L218-L226), [Record/UpdateCursor](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/HostedWorkflowState.cs#L350-L382))

### Python
Python `WorkflowState`는 checkpoint를 저장하지 않는다. 앱이 `CheckpointStorage`와 필요 시 `session_id -> checkpoint_id` cursor store를 직접 가져야 한다. 공식 README와 spec 모두 이 점을 노골적으로 강조한다. (출처: [Python hosting README checkpointing](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting/README.md#L112-L145), [Python hosting spec workflow state](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/specs/002-python-hosting-channels.md#L167-L208))

---

## 확장점

### .NET
- `AddAIAgent(..., Func<IServiceProvider,string,AIAgent>)`로 custom factory를 넣을 수 있다.
- `ServiceLifetime`으로 target lifecycle을 바꿀 수 있다.
- custom `AgentSessionStore` 구현을 주입할 수 있다.
- `HostedWorkflowState`는 shared instance, per-run factory, cached factory 세 모드를 지원한다. (출처: [AddAIAgent custom factory](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/AgentHostingServiceCollectionExtensions.cs#L103-L133), [AddWorkflow custom factory](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/HostApplicationBuilderWorkflowExtensions.cs#L16-L49), [HostedWorkflowState constructors](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/HostedWorkflowState.cs#L55-L152))

### Python
- `AgentState`/`WorkflowState`는 direct instance, sync factory, async factory, awaitable을 지원한다.
- `cache_target=False`로 per-run target resolve를 강제할 수 있다.
- custom `SessionStore`와 `FileSessionStore(serialization_format="msgpack")`를 쓸 수 있다.
- custom session state object는 `register_state_type()`로 registry를 확장할 수 있다. (출처: [Python AgentState target forms](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting/agent_framework_hosting/_state.py#L82-L118), [WorkflowState target forms](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting/agent_framework_hosting/_state.py#L201-L241), [FileSessionStore msgpack](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting/README.md#L54-L58), [register_state_type](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_sessions.py#L268-L335))

---

## 동시성·스트리밍·취소

## 동시성

### .NET
- `HostedWorkflowState`는 shared workflow instance를 두 runner가 동시에 돌릴 수 없다고 문서화한다.
- factory mode의 기본값(`cacheWorkflow: false`)은 independent session 병렬성을 위해 fresh instance를 매 run마다 만든다.
- 그러나 **같은 session id에 대한 concurrent turn serialization은 host 책임**이라고 명시한다. (출처: [HostedWorkflowState instance remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/HostedWorkflowState.cs#L76-L81), [factory concurrency remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/HostedWorkflowState.cs#L121-L136))

### Python
- `AgentState`는 `session_id`별 `asyncio.Lock`으로 create-on-miss race를 serialize한다.
- 하지만 conversation-level optimistic concurrency나 single-writer coordination은 제공하지 않는다. (출처: [AgentState session lock](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting/agent_framework_hosting/_state.py#L173-L181), [Python hosting README concurrency note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting/README.md#L96-L102))

## 스트리밍

- 이 generic hosting 층 자체는 protocol-level streaming format을 만들지 않는다.
- 다만 .NET `HostedWorkflowState.RunOrResumeStreamingAsync(...)`는 restore-then-run semantics를 streaming turn에도 유지하고, consumer가 조기 종료해도 last checkpoint를 cursor에 기록한다.
- Python generic hosting은 streaming transformer를 제공하지 않고, protocol helper가 `ResponseStream`을 소유한다. (출처: [.NET streaming resume](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/HostedWorkflowState.cs#L273-L346), [ADR-0027 stream helper family](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0027-hosting-channels.md#L113-L119))

## 취소

- .NET workflow state는 `CancellationToken`을 전면적으로 관통시키지만, cached workflow build는 shared instance이므로 request-bound token 대신 `CancellationToken.None`을 쓴다.
- Python `AgentState`/`WorkflowState` 자체에는 별도 취소 policy가 없고, underlying target/route/framework가 취소를 처리한다. (출처: [.NET cached factory cancellation note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/HostedWorkflowState.cs#L121-L129), [Python state implementation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting/agent_framework_hosting/_state.py#L119-L142))

---

## 오류·검증·보안

## 오류와 검증

- Python `SessionStore.validate_session_id()`는 session id가 non-empty string인지 최소 검증만 수행한다.
- .NET `AgentSessionStore` contract는 implementer가 `sessionStoreId`를 opaque로 취급하고, parsing이나 key-format 가정을 하지 말라고 요구한다.
- `HostedWorkflowState`는 null/empty sessionId와 null input에 대해 즉시 예외를 낸다. (출처: [Python SessionStore.validate_session_id](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_sessions.py#L1711-L1723), [.NET AgentSessionStore implementer guidance](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/AgentSessionStore.cs#L38-L45), [.NET HostedWorkflowState input validation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/HostedWorkflowState.cs#L206-L213))

## 보안

- request-derived session id는 **authorization token이 아니라 continuation key**다.
- multi-user host는 반드시 principal dimension을 key에 합성해야 한다.
- Python ADR는 `<protocol>_session_id(...)` 결과를 “trusted key가 되기 전의 candidate key”라고 규정한다.
- .NET ASP.NET helper는 default unique claim을 `ClaimTypes.NameIdentifier`로 잡고, display name이나 alias 같은 mutable/non-unique claim 사용을 금지 수준으로 경고한다. (출처: [ADR-0027 security responsibilities](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0027-hosting-channels.md#L141-L160), [ADR-0027 session key trust boundary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0027-hosting-channels.md#L180-L191), [ClaimsIdentityAgentIsolationKeyProvider security warning](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AspNetCore/ClaimsIdentityAgentIsolationKeyProvider.cs#L24-L34))

---

## .NET 구현과 테스트

### 구현 핵심
- agent 등록: `AddAIAgent(...)`가 keyed `AIAgent` service를 등록한다.
- workflow 등록: `AddWorkflow(...)`가 keyed `Workflow` service를 등록한다.
- session persistence: `AgentSessionStore` + `AIHostAgent`
- workflow resume: `HostedWorkflowState`
- ASP.NET seam: `UseClaimsBasedAgentIsolation(...)` + `ClaimsIdentityAgentIsolationKeyProvider` + `IsolationKeyScopedAgentSessionStore` (출처: [AgentHostingServiceCollectionExtensions](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/AgentHostingServiceCollectionExtensions.cs#L25-L144), [HostApplicationBuilderWorkflowExtensions](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/HostApplicationBuilderWorkflowExtensions.cs#L16-L49), [AIHostAgent](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/AIHostAgent.cs#L24-L72), [ASP.NET ServiceCollectionExtensions](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AspNetCore/ServiceCollectionExtensions.cs#L13-L62))

### 테스트 커버리지
- agent registration keyed singleton 기본값, null/empty validation, multi-agent registration을 검증한다. (출처: [HostApplicationBuilderAgentExtensionsTests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.UnitTests/HostApplicationBuilderAgentExtensionsTests.cs#L130-L175))
- workflow registration keyed singleton과 `AddAsAIAgent` bridge를 검증한다. (출처: [HostApplicationBuilderWorkflowExtensionsTests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.UnitTests/HostApplicationBuilderWorkflowExtensionsTests.cs#L66-L104), [same file AddAsAIAgent tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.UnitTests/HostApplicationBuilderWorkflowExtensionsTests.cs#L164-L244))
- `HostedWorkflowState`는 first turn checkpoint, second turn resume, pending request non-blocking, cursor miss durable fallback, factory/cached factory concurrency를 검증한다. (출처: [HostedWorkflowStateTests first/second turn](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.UnitTests/HostedWorkflowStateTests.cs#L62-L124), [cursor miss fallback](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.UnitTests/HostedWorkflowStateTests.cs#L145-L166), [factory and cached factory tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.UnitTests/HostedWorkflowStateTests.cs#L174-L294))
- ASP.NET isolation provider tests는 default `NameIdentifier`, custom claim, null context, multi-claim behavior를 검증한다. (출처: [ClaimsIdentityAgentIsolationKeyProviderTests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.UnitTests/ClaimsIdentityAgentIsolationKeyProviderTests.cs#L101-L167), [same file security default claim test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.UnitTests/ClaimsIdentityAgentIsolationKeyProviderTests.cs#L115-L132))
- scoped session store tests는 strict/non-strict mode와 scoped key rewrite를 검증한다. (출처: [IsolationKeyScopedAgentSessionStoreTests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.UnitTests/IsolationKeyScopedAgentSessionStoreTests.cs#L97-L139), [same file save tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.UnitTests/IsolationKeyScopedAgentSessionStoreTests.cs#L163-L220))

---

## Python 구현과 테스트

### 구현 핵심
- `AgentState`는 target caching과 create-on-miss session lifecycle을 담당한다.
- `WorkflowState`는 workflow/builder/factory resolution만 담당한다.
- `SessionStore`/`FileSessionStore`는 core에 남아 있고, hosting package는 이를 재-export하지 않는다.
- helper-first hosting sample은 FastAPI route가 이 state surface를 조합하는 방식을 보여 준다. (출처: [AgentState/WorkflowState implementation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting/agent_framework_hosting/_state.py#L74-L280), [Python hosting public exports](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting/agent_framework_hosting/__init__.py#L7-L27), [Python core session store](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_sessions.py#L1692-L1858), [Python af-hosting sample README](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/samples/04-hosting/af-hosting/README.md#L10-L22))

### 테스트 커버리지
- hosting package가 `SessionStore`를 public export하지 않는다는 사실을 테스트가 고정한다. (출처: [test_state export contract](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting/tests/hosting/test_state.py#L105-L121))
- target caching on/off, async callable, bare awaitable, concurrent callers single-await를 검증한다. (출처: [test_state target resolution tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting/tests/hosting/test_state.py#L123-L188))
- opaque session id passthrough, empty id rejection, 20 concurrent create-once semantics를 검증한다. (출처: [opaque id pass-through](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting/tests/hosting/test_state.py#L202-L227), [concurrent get_or_create_session](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting/tests/hosting/test_state.py#L237-L254))

---

## 문서 차이

### 1. Python spec와 실제 public export의 차이
Python hosting spec 표는 `agent-framework-hosting`이 `SessionStore`까지 제공하는 것처럼 읽히지만, 실제 public export는 `AgentState`/`WorkflowState`와 run args만 노출하고 `SessionStore`는 core package에 남겨 둔다. test도 이 비공개 정책을 고정한다. 따라서 Java 설계 시에도 **execution-state package가 store 구현까지 재-export해야 한다고 가정하면 안 된다**.  
(출처: [Python hosting spec packages table](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/specs/002-python-hosting-channels.md#L64-L73), [Python hosting __init__](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting/agent_framework_hosting/__init__.py#L7-L27), [Python test enforcing no SessionStore export](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting/tests/hosting/test_state.py#L105-L109))

### 2. .NET의 Python 대비 의도적 비대칭성
Python에는 `AgentState`가 있지만 .NET에는 대응 public `HostedAgentState`가 없다. 이것은 결손이 아니라 ADR-0032가 설명한 설계 선택이다. .NET은 create-on-miss를 store에, target lifecycle을 DI에 맡기기 때문에 별도 holder가 불필요하다고 본다. 코드도 실제로 그렇게 구현돼 있다.  
(출처: [ADR-0032 no HostedAgentState rationale](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0032-dotnet-hosting-protocol-helpers.md#L101-L143), [AgentSessionStore API](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/AgentSessionStore.cs#L64-L106), [AgentHostingServiceCollectionExtensions](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/AgentHostingServiceCollectionExtensions.cs#L25-L144))

이 문서에서는 **코드와 ADR을 우선**했고, marketing-level README보다 source/export/test를 기준으로 generic hosting surface를 정리했다.

---

## Java 결정

## 배치 원칙

### API와 Engine이 단독 소유할 것
- `AgentSession`, structured `serviceSessionId`와 typed snapshot envelope
- `SessionStore` abstraction과 serialization codec registry
- `WorkflowState` 및 restore-then-run 실행 규칙

이 계약은 08 Session과 15~17 Workflow 문서가 정의한다. Hosting module은 같은 타입이나
store 계약을 다시 선언하지 않고 주입받아 사용한다.

### hosting core가 소유할 것
- protocol-neutral target resolution과 lifecycle wrapper
- `AgentState`/`WorkflowState`를 요청 처리에 연결하는 binder
- session load/run/save coordination
- protocol adapter가 사용할 run values, session snapshot과 checkpoint cursor projection

이 레이어는 OpenAI/A2A/AG-UI/MCP를 몰라야 한다. generic hosting의 본질은 wire protocol
구현이 아니라 core 실행 계약을 host 요청 수명에 연결하는 것이기 때문이다. (출처:
[ADR-0027 decision outcome](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0027-hosting-channels.md#L62-L103))

### optional adapter에 둘 것
- protocol-specific parser/renderer는 각 별도 문서의 범위
- 다만 hosting core와의 접점은 **run values, session snapshot, checkpoint cursor**로만 연결해야 한다

### host/framework module에 둘 것
- Spring MVC/WebFlux route binding
- principal-derived isolation key helper
- SSE writer/HTTP error mapping
- lifecycle hooks for startup/shutdown
- host-specific DI integration

### host application이 끝까지 책임질 것
- authn/authz
- request-derived key authorization
- durable session/checkpoint backend
- same-session single writer
- multi-replica scaling / sticky vs stateless topology
- background job model
- deployment/readiness/liveness

## 제안 아티팩트
- `agent-framework-api`
- `agent-framework-engine`
- `agent-framework-hosting-core`
- `agent-framework-hosting-spring`
- protocol adapters는 별도 문서 범위
- provider/infrastructure adapters(`openai`, `foundry`, `redis`, `cosmos`, `mem0` 등) 분리

---

## 구체 acceptance scenarios

1. **Agent target lifecycle**
   - direct instance, sync factory, async factory, builder 기반 target을 모두 수용해야 한다.
   - caching on/off semantics가 명확해야 한다.

2. **Session continuity**
   - agent session load가 stored snapshot의 독립 working copy를 반환해야 한다.
   - create-on-miss race에서 session은 정확히 한 번만 생성되어야 한다.
   - host는 post-run session save를 명시적으로 수행해야 한다.

3. **Workflow continuity**
   - workflow resume는 “restore only”가 아니라 “restore-then-run-with-new-input”이어야 한다.
   - checkpoint head cursor가 사라져도 durable checkpoint backend가 있으면 latest checkpoint에서 다시 이어져야 한다.

4. **Host composition**
   - route/auth/status code/background work 없이도 generic hosting core가 재사용 가능해야 한다.
   - ASP.NET/Spring 같은 host-specific integration은 optional module이어야 한다.

5. **Scaling**
   - in-memory store만으로는 multi-replica continuity를 보장하지 않는다는 사실이 code/docs/tests에서 명확해야 한다.
   - host는 durable session/checkpoint store와 session-scoped locking 정책을 교체 가능해야 한다.

6. **Security**
   - request-derived session id는 identity proof로 취급되지 않아야 한다.
   - multi-user host는 principal dimension을 key에 합성해야 한다.
   - non-unique claim이나 mutable display name을 isolation key 기본값으로 사용하지 않아야 한다.

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

## 결론

이 커밋에서 generic hosting은 “서버 프레임워크를 숨기는 abstraction”이 아니라, **서버가 스스로 조합해야 할 최소 execution-state substrate**로 설계되어 있다. Python은 더 순수한 helper-first model을 취하고, .NET은 DI와 store 중심 구조를 통해 같은 목적을 달성한다. Java 구현은 이 둘의 공통분모를 따라 **protocol-neutral hosting core + framework-specific host module**로 나누는 것이 가장 자연스럽다. (출처: [ADR-0027](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0027-hosting-channels.md#L62-L103), [ADR-0032](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0032-dotnet-hosting-protocol-helpers.md#L74-L143), [Python hosting source](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting/agent_framework_hosting/_state.py#L74-L280), [.NET hosting source](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/HostedWorkflowState.cs#L18-L382))