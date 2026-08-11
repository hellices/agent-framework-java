# 23. AG-UI

## 범위

이 문서는 Microsoft Agent Framework의 **AG-UI adapter 및 hosting**만 다룬다. 대상은 다음이다.

- AG-UI request/event와 Agent Framework run/update의 매핑
- AG-UI server endpoint/adapter/hosting 표면
- state snapshot / predictive delta / deterministic state update
- tool call / tool result / approval interrupt와 UI payload 경계
- streaming transport, SSE framing, keepalive
- session continuity와 identity 경계
- .NET / Python 구현 차이
- conformance tests
- Java 설계 결정

반대로 generic hosting lifecycle, session store abstraction 일반론은 별도 generic hosting 문서의 범위이며 여기서는 AG-UI-specific request/event/state semantics와 hosting 접점만 기술한다. 또한 OpenAI Responses, A2A, MCP, Foundry/DevUI, Telegram/ChatKit 세부는 각각 별도 문서 범위다. (출처: [Python AG-UI README](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/README.md#L1-L3), [Python endpoint helper scope](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_endpoint.py#L95-L119), [.NET AG-UI endpoint remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore/AGUIEndpointRouteBuilderExtensions.cs#L27-L32))

## 요약

AG-UI support는 두 언어에서 제공 범위가 다르다. `.NET`은 주로 **ASP.NET Core server hosting layer**에 집중하며, AG-UI event conversion 파이프라인 자체는 외부 AG-UI .NET SDK에 위임하고 Agent Framework 쪽은 session persistence와 JSON resolver chain, SSE result, isolation-scoped session keying만 얹는다. Python은 더 넓어서 **server endpoint**, **client adapter**, **event converter**, **predictive state delta**, **deterministic tool-result state snapshot**, **thread snapshot persistence**까지 자체 구현한다. AG-UI의 request model은 단순 messages 외에 `state`, `tools`, `context`, `forwarded_props`, `availableInterrupts`, `resume`, `threadId`, `runId`, `parentRunId`를 포함하며, Python은 이를 `AGUIRequest`로 직접 모델링한다. state는 두 층으로 처리되는데, `predict_state_config`는 streaming tool args에서 낙관적 `StateDeltaEvent`를 만들고, `state_update(...)`는 tool 실행 후 실제 결과에 근거한 deterministic `StateSnapshotEvent`를 만든다. tool result도 LLM-bound text와 UI-bound display payload를 분리할 수 있으며, `ToolCallResultEvent.content`에는 UI payload가, 내부 function result에는 LLM text가 유지된다. session/identity 경계에서는 AG-UI `threadId`가 authorization boundary가 아니므로, .NET은 `IsolationKeyScopedAgentSessionStore`를 권장하고, Python은 snapshot persistence 사용 시 `snapshot_scope_resolver`를 필수로 강제한다. (출처: [.NET AG-UI endpoint remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore/AGUIEndpointRouteBuilderExtensions.cs#L27-L32), [.NET session trust model for ThreadId](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore/AGUIEndpointRouteBuilderExtensions.cs#L79-L103), [Python AGUIRequest fields](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_types.py#L94-L141), [Python AGUI client/server exports](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/__init__.py#L7-L56), [PredictiveStateHandler](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_predictive_state.py#L19-L233), [state_update helper](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_state.py#L43-L137), [tool result snapshot emission](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_run_common.py#L713-L795), [Python snapshot scope guard](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_endpoint.py#L54-L74))

---

## 목적

AG-UI support의 목적은 Agent Framework target을 **UI-friendly event protocol**로 연결하는 것이다. 이 목적은 단순 chat text streaming보다 넓다.

- AG-UI client가 보낼 request shape를 run-ready input으로 바꾼다.
- agent/workflow 실행 중 발생한 텍스트, tool call, tool result, step, interrupt, state 변화를 AG-UI event stream으로 바꾼다.
- multi-turn thread continuity를 유지한다.
- tool 실행 전/후의 state 변화를 UI와 공유한다.
- workflow나 approval 대기 같은 human-in-the-loop 상태를 canonical interrupt model로 표현한다.

Python README는 AG-UI를 web interface와 streaming protocol integration으로 설명하고, .NET 주석은 “AG-UI SDK가 `ChatResponseUpdate` → AG-UI event stream 변환을 제공하고, Agent Framework layer는 hosting concerns만 추가한다”고 설명한다. (출처: [Python AG-UI README summary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/README.md#L1-L3), [.NET AG-UI endpoint remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore/AGUIEndpointRouteBuilderExtensions.cs#L27-L32))

## 경계

### 이 기능이 하는 일
- AG-UI request payload를 target run input으로 파싱/정규화한다.
- AG-UI wire events를 `ChatResponseUpdate` 또는 raw UI event stream으로 surface한다.
- tool call/result, state snapshot/delta, interrupt/resume를 AG-UI model에 맞춰 표면화한다.
- thread/session continuity를 위한 key와 snapshot store hook을 제공한다.
- UI-only display payload와 LLM-visible tool result text를 분리한다. (출처: [Python event converter](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_event_converters.py#L18-L97), [state_update design](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_state.py#L49-L119), [.NET MapAGUIServer session persistence path](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore/AGUIEndpointRouteBuilderExtensions.cs#L142-L188))

### 이 기능이 하지 않는 일
- `threadId` 자체를 신뢰 가능한 identity로 만드는 것
- host-wide authn/authz
- generic session store lifecycle 관리
- protocol-neutral checkpoint abstraction
- external deployment/runtime orchestration

Python endpoint는 auth dependencies를 caller가 넣도록 하고, snapshot persistence는 app-defined scope resolver 없이는 켤 수 없게 만든다. .NET도 `ThreadId`를 bare persistent key로 쓰는 것은 single-user/prototyping에만 안전하다고 문서화한다. (출처: [Python endpoint dependencies and snapshot scope requirement](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_endpoint.py#L95-L119), [Python snapshot scope fail-fast](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_endpoint.py#L54-L74), [.NET ThreadId trust model](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore/AGUIEndpointRouteBuilderExtensions.cs#L79-L103))

---

## 성숙도

| 영역 | .NET | Python |
|---|---|---|
| AG-UI server hosting | `Microsoft.Agents.AI.Hosting.AGUI.AspNetCore` = `preview` | `agent-framework-ag-ui` = `released` |
| AG-UI client adapter | repo 범위 내 first-party 구현 없음(AGUI SDK 활용) | `AGUIChatClient` 포함 |
| Snapshot/state tooling | server integration tests로 검증 | package에 first-party 구현 포함 |

출처:
- [.NET Hosting.AGUI.AspNetCore csproj](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore.csproj#L13-L17)
- [Python PACKAGE_STATUS released ag-ui](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L17-L20)
- [Python ag-ui pyproject stable classifier](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/pyproject.toml#L13-L23)

---

## 공개 API·타입

## .NET
- `AddAGUIServer(this IServiceCollection)`
- `MapAGUIServer(this IEndpointRouteBuilder, ...)`
- `AGUIServerSentEventsResult` (pre-.NET 10 SSE polyfill)
- `ConfigureAGUIJsonOptions`를 통한 resolver-chain configuration  
  (출처: [.NET AddAGUIServer](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore/AGUIServerServiceCollectionExtensions.cs#L12-L29), [.NET MapAGUIServer overloads](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore/AGUIEndpointRouteBuilderExtensions.cs#L33-L173), [.NET AGUI SSE polyfill](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore/AGUIServerSentEventsResult.cs#L19-L135), [.NET ConfigureAGUIJsonOptions](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore/ConfigureAGUIJsonOptions.cs#L9-L25))

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
  (출처: [Python public exports](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/__init__.py#L7-L56))

## Request model
Python은 request model을 package 내부에서 직접 정의한다. 주요 필드는 다음이다.

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
  (출처: [AGUIRequest fields and aliases](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_types.py#L94-L141))

---

## Request mapping

## Python request mapping

### AGUIRequest parsing
`AGUIRequest`는 camelCase/snake_case를 함께 받아들인다.

- `runId` ↔ `run_id`
- `threadId` ↔ `thread_id`
- `forwardedProps` ↔ `forwarded_props`
- `parentRunId` ↔ `parent_run_id`
- `availableInterrupts` ↔ `available_interrupts`

`resume`는 legacy shapes를 canonical `ResumeEntry[]`로 normalize한다. 단일 object, `{interrupts:[...]}`, `{interrupt:[...]}` 형태도 수용한다. (출처: [legacy resume coercion](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_types.py#L28-L69), [AGUIRequest aliases and resume field](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_types.py#L94-L141))

### Endpoint-side normalization
`add_agent_framework_fastapi_endpoint`는 request body를 `model_dump(exclude_none=True)`로 풀고:

- optional snapshot scope를 넣고
- default state를 merge하거나 deferred default state로 저장하고
- runner에 `input_data`를 그대로 넘긴다.

즉 request-level normalization과 transport concerns(keepalive, SSE headers)는 endpoint layer가 담당한다. (출처: [endpoint request handling](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_endpoint.py#L145-L179), [snapshot/default state logic](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_endpoint.py#L153-L170))

## .NET request mapping

`.NET` repo-local implementation은 AG-UI request body model을 직접 재정의하지 않는다. 서버 endpoint는 AG-UI SDK의 `RunAgentInput`를 받으며, `input.ToChatRequestContext(jsonSerializerOptions, streamOptions)`로 변환한 뒤 `ctx.Messages`와 `ctx.ChatOptions`를 사용한다. 즉 request mapping의 핵심은 AG-UI SDK에 있고, Agent Framework layer는:

- keyed agent resolution
- threadId defaulting
- session load/save
- `AIHostAgent.RunStreamingAsync(...)` 호출  
만 덧붙인다. (출처: [.NET endpoint mapping body handling](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore/AGUIEndpointRouteBuilderExtensions.cs#L125-L170))

---

## Event mapping

## Python: AGUI event → Agent Framework update

`AGUIEventConverter`는 AG-UI event dict를 `ChatResponseUpdate` 스타일의 update로 바꾼다. 지원되는 대표 event는 다음과 같다.

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

`RUN_STARTED`는 `thread_id`, `run_id`를 `additional_properties`에 저장하고, `TEXT_MESSAGE_CONTENT`는 `Content.from_text(delta)`로, `TOOL_CALL_START/ARGS`는 `Content.from_function_call(...)`로, `TOOL_CALL_RESULT`는 `role="tool"` + `Content.from_function_result(...)`로 변환한다. `RUN_FINISHED`는 `outcome.interrupts`나 legacy `interrupt` payload를 metadata로 보존하고, `RUN_ERROR`는 `Content.from_error(...)`로 surface한다. (출처: [AGUIEventConverter convert_event switch](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_event_converters.py#L44-L97), [RUN_STARTED mapping](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_event_converters.py#L99-L111), [text message mappings](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_event_converters.py#L113-L138), [tool call mappings](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_event_converters.py#L140-L211), [RUN_FINISHED and RUN_ERROR mapping](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_event_converters.py#L213-L278))

## .NET: Agent Framework update → AG-UI event

`.NET` repo 내부 구현은 AG-UI event generation 자체를 직접 갖지 않는다. 주석상 이 파이프라인은 public AG-UI .NET SDK의 `ChatResponseUpdateAGUIExtensions.AsAGUIEventStreamAsync`가 담당하며, Agent Framework layer는 이를 `AIHostAgent`와 `AgentSessionStore` 위에 올린다. 따라서 repo-local source에서 직접 확인 가능한 것은 **AG-UI SDK 이벤트 stream을 SSE로 노출하는 hosting layer**와 그 주변 state/persistence semantics다. (출처: [.NET endpoint remarks on AG-UI SDK pipeline](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore/AGUIEndpointRouteBuilderExtensions.cs#L27-L32), [.NET endpoint stream path](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore/AGUIEndpointRouteBuilderExtensions.cs#L148-L170))

---

## State snapshot / delta

## Python: predictive delta

`PredictiveStateHandler`는 `predict_state_config`를 기반으로 streaming tool args를 관찰해 `StateDeltaEvent`를 만든다.

- partial JSON chunk는 regex 기반으로 부분 값을 추출
- complete JSON이 되면 fully parsed args에서 값 추출
- 중복 값은 재방출하지 않음
- pending updates는 나중에 `current_state`에 적용

이는 **LLM이 호출하려는 tool arguments를 보고 UI state를 미리 예측해서 바꾸는 낙관적 경로**다. (출처: [PredictiveStateHandler overview](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_predictive_state.py#L19-L39), [extract_state_value](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_predictive_state.py#L45-L76), [emit_streaming_deltas](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_predictive_state.py#L93-L129), [partial and complete delta emission](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_predictive_state.py#L131-L194), [StateDeltaEvent construction](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_predictive_state.py#L196-L226))

## Python: deterministic snapshot

`state_update(...)`는 tool가 실제 실행된 뒤 반환하는 `Content` helper다. 이 함수는 reserved keys:

- `__ag_ui_tool_result_state__`
- `__ag_ui_tool_result_display__`

를 `additional_properties`에 싣는다. 이후 `_emit_tool_result_common(...)`은:

1. `ToolCallEndEvent`
2. `ToolCallResultEvent`
3. predictive pending updates apply
4. deterministic state merge
5. 하나의 coalesced `StateSnapshotEvent`

순으로 내보낸다. 즉 **predictive delta는 optimistic**, **state_update snapshot은 authoritative**다. (출처: [state_update reserved keys and semantics](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_state.py#L26-L35), [state_update function](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_state.py#L43-L137), [_extract_tool_result_state](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_run_common.py#L671-L696), [_emit_tool_result_common state merge + StateSnapshotEvent](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_run_common.py#L713-L795))

## .NET: state snapshot visibility

`.NET` repo-local AG-UI layer는 predictive delta나 deterministic state helper를 구현하지 않는다. 대신 integration tests는 **incoming AG-UI thread state가 `RunAgentInput.State`로 들어오고, 서버가 방출한 `StateSnapshotEvent`가 `ChatResponseUpdate.RawRepresentation`로 surface된다**는 점을 고정한다. 즉 repo 기준으로 .NET layer의 역할은 state event를 새로 만들기보다 **raw AG-UI state events를 preserving**하는 것이다. (출처: [.NET SharedStateTests summary comments](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore.IntegrationTests/SharedStateTests.cs#L30-L42), [.NET state snapshot assertions](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore.IntegrationTests/SharedStateTests.cs#L47-L62), [RawRepresentation preservation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore.IntegrationTests/SharedStateTests.cs#L82-L92))

---

## Tool calls

## Python

### Tool call events
`_agent_run.py`와 `_run_common.py`는 tool call flow를 AG-UI event sequence로 나눈다.

- `ToolCallStartEvent`
- `ToolCallArgsEvent`
- `ToolCallEndEvent`
- `ToolCallResultEvent`

여기서 중요한 점은 **tool result의 UI payload와 LLM payload가 분리**된다는 것이다.

- LLM에는 `raw_result` 문자열이 function result로 유지
- UI에는 `display_result`가 있으면 `ToolCallResultEvent.content`로 표시
- `state_update(..., tool_result=...)`가 이 경로를 활성화한다. (출처: [_resolve_ui_payload](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_run_common.py#L704-L710), [_emit_tool_result_common tool result event](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_run_common.py#L741-L765), [state_update tool_result docs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_state.py#L57-L67), [same file example](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_state.py#L80-L100))

### Approval/HITL
AG-UI interrupt model은 canonical `Interrupt` / `ResumeEntry`를 그대로 사용한다. tool approval interrupts는 `reason: "tool_call"` 계열이고, workflow `request_info` interrupts는 `reason: "input_required"`다. `AGUIRequest.available_interrupts`와 `resume`는 이 canonical model을 직접 받는다. (출처: [Python README interrupts and resume](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/README.md#L142-L152), [AGUIRequest available_interrupts and resume](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_types.py#L133-L141))

### Workflow-side tool/result replay
`AgentFrameworkWorkflow` 내부 `_WorkflowSnapshotBuilder`는 `ToolCallStartEvent`, `ToolCallArgsEvent`, `ToolCallResultEvent`를 replayable message snapshot으로 축약한다. tool result가 도착하면 현재 tool-call group을 닫아 subsequent tool calls와 구분한다. (출처: [Workflow snapshot builder observe tool calls](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_workflow.py#L122-L178))

## .NET

`.NET` repo-local 구현은 AG-UI SDK가 만든 tool call events를 직접 재생성하지 않는다. 다만 integration tests는 server-side tools와 client-side tools 둘 다 `FunctionCallContent` / `FunctionResultContent`로 surface된다는 점을 고정한다. 즉 AF-hosting layer 관점에서 tool call path의 직접 책임은 “event generation”보다는 **streaming transport, session continuity, tool execution orchestration이 AGUI SDK+Agent stack과 함께 올바르게 동작하게 하는 것**이다. (출처: [.NET ToolCallingTests server tool](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore.IntegrationTests/ToolCallingTests.cs#L33-L71), [.NET ToolCallingTests client tool](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore.IntegrationTests/ToolCallingTests.cs#L122-L159), [.NET simultaneous server and client tools](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore.IntegrationTests/ToolCallingTests.cs#L211-L260))

---

## Streaming

## .NET

### SSE transport
AG-UI ASP.NET layer는 .NET 10 이상이면 framework의 `TypedResults.ServerSentEvents`, 그 이전이면 `AGUIServerSentEventsResult` polyfill을 사용한다. polyfill은:

- `Content-Type: text/event-stream`
- `Cache-Control: no-cache,no-store`
- `Pragma: no-cache`
- SSE framing

을 설정하고, streaming 중 예외가 나면 raw exception 대신 generic `RunErrorEvent`를 보내며 full exception은 server log에만 남긴다. (출처: [.NET endpoint selection of SSE path](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore/AGUIEndpointRouteBuilderExtensions.cs#L162-L171), [.NET AGUIServerSentEventsResult headers](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore/AGUIServerSentEventsResult.cs#L37-L55), [.NET generic RunErrorEvent emission](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore/AGUIServerSentEventsResult.cs#L56-L79))

### Session save after stream
`.NET` endpoint는 AG-UI event stream을 wrapper로 감싸, enumeration이 끝난 뒤 `hostAgent.SaveSessionAsync(threadId, session)`를 호출한다. 즉 session persistence는 stream completion에 묶여 있다. (출처: [.NET SaveSessionAfterStreamingAsync](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore/AGUIEndpointRouteBuilderExtensions.cs#L175-L188))

## Python

### Endpoint SSE
Python FastAPI endpoint는 `EventEncoder`로 AG-UI events를 SSE string/bytes로 encode한다. `keepalive_seconds`가 있으면 `sse-starlette`의 ping comment를 쓰고, 없으면 plain `StreamingResponse`를 쓴다. headers는 `Cache-Control: no-cache`, `Connection: keep-alive`, `X-Accel-Buffering: no`다. (출처: [Python endpoint event_generator and headers](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_endpoint.py#L185-L253))

### Keepalive ownership
keepalive는 runner가 아니라 endpoint transport 설정이다. default는 15초이고, docstring이 “SSE comments이며 AG-UI events를 바꾸지 않는다”고 명시한다. tests도 `keepalive_seconds`가 endpoint-owned option임을 고정한다. (출처: [endpoint signature and docs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_endpoint.py#L81-L119), [keepalive tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/tests/ag_ui/test_endpoint.py#L154-L213))

### HTTP round-trip fidelity
Python `test_http_round_trip.py`는 POST → SSE bytes → parsed event sequence 검증으로 text chat, tool call, workflow, headers를 고정한다. (출처: [HTTP round-trip tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/tests/ag_ui/test_http_round_trip.py#L48-L77), [tool call round-trip](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/tests/ag_ui/test_http_round_trip.py#L80-L114), [workflow SSE round-trip](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/tests/ag_ui/test_http_round_trip.py#L166-L186), [SSE headers](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/tests/ag_ui/test_http_round_trip.py#L206-L217))

---

## 오류·검증·보안

## Python

### Endpoint-level errors
- runner streaming 중 예외가 나면 generic `RunErrorEvent`를 encode해서 stream에 실어 보낸다.
- endpoint outer layer 예외는 HTTP 500 `An internal error has occurred.`로 바꾼다. (출처: [event_generator stream error -> RunErrorEvent](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_endpoint.py#L221-L231), [outer endpoint 500](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_endpoint.py#L254-L256))

### Resume / interrupt validation
legacy resume payloads는 normalization을 거치고, invalid status나 cancelled workflow resume은 `RunErrorEvent` 계열로 바뀐다. pending request 취소 시에는 runner context에서 해당 pending request를 제거한다. (출처: [resume coercion helpers](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_types.py#L28-L69), [workflow resume error helpers](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_workflow_run.py#L270-L303))

### Identity / authorization boundary
- `threadId`는 authorization boundary가 아니다.
- snapshot persistence가 켜진 경우 `snapshot_scope_resolver`가 강제된다.
- default state/snapshot/workflow_factory cache도 `snapshot_scope` 기준으로 분리된다.  
  (출처: [snapshot scope requirement](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_endpoint.py#L54-L74), [endpoint doc on snapshot scope](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_endpoint.py#L111-L119), [workflow cache keyed by (snapshot_scope, thread_id)](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_workflow.py#L206-L215))

## .NET

### ThreadId trust boundary
`.NET` AG-UI hosting 주석은 `RunAgentInput.ThreadId`가 wire에서 오며 authorization token이 아니라고 명시한다. persistent store를 붙인 multi-user host는 `IsolationKeyScopedAgentSessionStore` 또는 custom `AgentIsolationKeyProvider`를 써야 한다. (출처: [.NET ThreadId trust model](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore/AGUIEndpointRouteBuilderExtensions.cs#L79-L103))

### Stream failure privacy
SSE polyfill은 streaming exception의 raw message를 client에 노출하지 않고 generic `RunErrorEvent`만 보낸다. 이는 내부 세부를 숨기는 보안 선택이다. (출처: [.NET AGUIServerSentEventsResult generic error message](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore/AGUIServerSentEventsResult.cs#L56-L79))

---

## Session / identity 경계

## .NET

- AG-UI continuation key는 `threadId`
- client가 threadId를 안 보내면 server가 GUID를 생성해 request에 다시 써 넣는다
- session store가 있으면 `threadId` 아래 session load/save
- isolation provider가 있으면 scoped session key 적용  
  (출처: [.NET threadId generation and session lookup](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore/AGUIEndpointRouteBuilderExtensions.cs#L142-L160), [.NET isolation wrapper application](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore/AGUIEndpointRouteBuilderExtensions.cs#L113-L123))

integration test는 in-memory session store를 붙인 multi-turn flow에서 second turn이 `"Turn 2"`를 반환하는지로 persistence round-trip을 검증한다. (출처: [.NET SessionPersistenceTests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore.IntegrationTests/SessionPersistenceTests.cs#L29-L93))

## Python

Python에는 두 층의 session identity가 있다.

1. **transport thread identity**: `threadId`
2. **snapshot authorization boundary**: `snapshot_scope`

workflow wrapper는 `workflow_factory` instance cache조차 `(snapshot_scope, thread_id)`로 keying한다. 따라서 같은 `threadId`라도 다른 scope라면 mutable workflow state를 공유하지 않는다. (출처: [workflow cache key comments](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_workflow.py#L206-L215))

또한 `AgentFrameworkWorkflow`의 snapshot builder는 replayable messages/state/interrupt를 AGUI thread snapshot으로 축약 저장한다. 이는 raw event 로그를 그대로 들고 있지 않고도 thread replay를 가능하게 한다. (출처: [Workflow snapshot builder](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_workflow.py#L50-L106))

---

## .NET/Python 차이

1. **제공 범위**
   - .NET: server hosting 중심, event conversion은 AGUI SDK 의존
   - Python: server + client + event converter + predictive state + snapshot store까지 first-party 구현  
   (출처: [.NET endpoint remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore/AGUIEndpointRouteBuilderExtensions.cs#L27-L32), [Python public exports](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/__init__.py#L7-L56))

2. **request modeling**
   - .NET repo-local: AGUI SDK `RunAgentInput`
   - Python: in-repo `AGUIRequest` with aliasing/legacy resume coercion  
   (출처: [.NET endpoint body handling](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore/AGUIEndpointRouteBuilderExtensions.cs#L125-L140), [Python AGUIRequest](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_types.py#L94-L141))

3. **state support depth**
   - .NET repo-local: raw `StateSnapshotEvent` preservation and session persistence 검증
   - Python: predictive `StateDeltaEvent` + deterministic `StateSnapshotEvent` + UI display payload split  
   (출처: [.NET SharedStateTests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore.IntegrationTests/SharedStateTests.cs#L30-L62), [Python PredictiveStateHandler](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_predictive_state.py#L19-L233), [Python state_update](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_state.py#L43-L137))

4. **tool result handling**
   - .NET tests confirm server/client tools both surface through AG-UI stream, but repo-local logic lives mostly outside this repo
   - Python repo-local implementation explicitly separates LLM result text and UI display payload  
   (출처: [.NET ToolCallingTests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore.IntegrationTests/ToolCallingTests.cs#L33-L260), [Python _emit_tool_result_common](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_run_common.py#L713-L795))

5. **identity boundary enforcement**
   - .NET: `ThreadId` untrusted + isolation-scoped session store 권장
   - Python: `snapshot_scope_resolver` mandatory when persistence active  
   (출처: [.NET trust model](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore/AGUIEndpointRouteBuilderExtensions.cs#L79-L103), [Python snapshot scope requirement](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_endpoint.py#L54-L74))

---

## .NET 구현과 테스트

## 구현
- `AddAGUIServer()`는 ASP.NET `JsonOptions`에 Agent Framework abstractions resolver와 AG-UI SDK resolver를 체인으로 추가한다. (출처: [.NET AddAGUIServer](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore/AGUIServerServiceCollectionExtensions.cs#L17-L29), [.NET ConfigureAGUIJsonOptions](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore/ConfigureAGUIJsonOptions.cs#L15-L25))
- `MapAGUIServer(...)`는 keyed agent resolve, optional session store wrap, `threadId` generation, `AIHostAgent.RunStreamingAsync` call, stream-completion session save를 수행한다. (출처: [.NET MapAGUIServer](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore/AGUIEndpointRouteBuilderExtensions.cs#L71-L188))
- pre-.NET 10 target에서는 `AGUIServerSentEventsResult`가 SSE polyfill 역할을 한다. (출처: [.NET AGUI SSE polyfill](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore/AGUIServerSentEventsResult.cs#L19-L135))

## 테스트
- endpoint unit tests는:
  - keyed agent resolve
  - session store resolve/fallback
  - null argument validation  
  을 검증한다. (출처: [.NET AGUIEndpointRouteBuilderExtensionsTests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore.UnitTests/AGUIEndpointRouteBuilderExtensionsTests.cs#L21-L161))
- JSON option tests는 resolver chain이 AG-UI wire types와 Agent Framework types 모두를 해석할 수 있음을 검증한다. (출처: [.NET ConfigureAGUIJsonOptionsTests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore.UnitTests/ConfigureAGUIJsonOptionsTests.cs#L17-L46))
- SSE result tests는 headers, framing, flush, cancellation을 검증한다. (출처: [.NET AGUIServerSentEventsResultTests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore.UnitTests/AGUIServerSentEventsResultTests.cs#L23-L149))
- integration tests는:
  - basic streamed assistant text
  - run lifecycle ids
  - session persistence across requests
  - shared state snapshot raw representation
  - server/client tool calling  
  을 검증한다. (출처: [.NET BasicStreamingTests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore.IntegrationTests/BasicStreamingTests.cs#L29-L217), [.NET SessionPersistenceTests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore.IntegrationTests/SessionPersistenceTests.cs#L29-L146), [.NET SharedStateTests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore.IntegrationTests/SharedStateTests.cs#L30-L238), [.NET ToolCallingTests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore.IntegrationTests/ToolCallingTests.cs#L33-L260))

---

## Python 구현과 테스트

## 구현
- `AGUIRequest`가 request wire shape를 정의한다.
- `add_agent_framework_fastapi_endpoint`가 FastAPI route와 SSE transport를 만든다.
- `AgentFrameworkAgent`와 `AgentFrameworkWorkflow`가 target wrapper 역할을 한다.
- `AGUIEventConverter`는 AG-UI events를 AF `ChatResponseUpdate`로 바꾼다.
- `PredictiveStateHandler`와 `state_update()`가 state delta/snapshot semantics를 구현한다.
- `_WorkflowSnapshotBuilder`가 replayable thread snapshot을 만든다.  
  (출처: [AGUIRequest](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_types.py#L94-L141), [FastAPI endpoint helper](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_endpoint.py#L81-L256), [AgentFrameworkAgent](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_agent.py#L76-L156), [AgentFrameworkWorkflow](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_workflow.py#L181-L220), [AGUIEventConverter](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_event_converters.py#L18-L278), [PredictiveStateHandler](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_predictive_state.py#L19-L233), [Workflow snapshot builder](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_workflow.py#L50-L106))

## 테스트
- endpoint tests는 raw agent / wrapped agent / workflow / keepalive option / SSE header behavior를 검증한다. (출처: [Python endpoint tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/tests/ag_ui/test_endpoint.py#L99-L204))
- event converter tests는 run started, text deltas, tool call args/results, run finished interrupt metadata를 검증한다. (출처: [Python event converter tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/tests/ag_ui/test_event_converters.py#L17-L240))
- predictive state tests는 partial/complete JSON에서 delta 생성, deduplication, pending updates를 검증한다. (출처: [Python predictive state tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/tests/ag_ui/test_predictive_state.py#L10-L200))
- HTTP round-trip tests는 full SSE pipeline을 고정한다. (출처: [Python HTTP round-trip tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/tests/ag_ui/test_http_round_trip.py#L48-L217))
- golden tests는 deterministic state snapshot과 backend tool scenarios를 고정한다. (출처: [Python golden deterministic state](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/tests/ag_ui/golden/test_scenario_deterministic_state.py#L87-L183), [Python golden agentic chat](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/tests/ag_ui/golden/test_scenario_agentic_chat.py#L53-L139))

---

## 문서 차이

가장 중요한 언어 차이는 **기능 범위 자체**다. `.NET` README-equivalent source comments는 AG-UI SDK가 event conversion을 담당하고 Agent Framework layer는 hosting concerns만 추가한다고 분명히 말한다. 반면 Python README와 source는 client, endpoint, event converter, predictive state, snapshot persistence까지 package 내부에서 구현한다. 따라서 “AG-UI support”라는 이름이 같아도 repo-local 구현 깊이는 대칭이 아니다. 코드 기준으로는:

- `.NET` = hosting wrapper + persistence/integration layer
- Python = full protocol adapter + hosting + client + state tooling

으로 기록해야 한다. (출처: [.NET remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore/AGUIEndpointRouteBuilderExtensions.cs#L27-L32), [Python AG-UI README quick start and client](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/README.md#L11-L107))

또 하나의 code-first 차이는 state semantics다. Python은 `StateDeltaEvent`와 `StateSnapshotEvent`를 명시적으로 구분하고 deterministic `state_update` 경로를 별도로 제공하지만, `.NET` repo-local implementation은 이런 state authoring logic을 재구현하지 않고 raw AG-UI state events 보존 쪽에 머문다. (출처: [Python state_update doc](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_state.py#L5-L14), [Python PredictiveStateHandler doc](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_predictive_state.py#L19-L27), [.NET SharedStateTests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore.IntegrationTests/SharedStateTests.cs#L30-L42))

---

## Java 결정

## 권장 아키텍처

### 1. Protocol adapter
- `agent-framework-agui`
- 포함:
  - request DTO / alias handling
  - AG-UI event encoder/decoder
  - tool call/result mapping
  - predictive state and deterministic state snapshot helpers
  - workflow snapshot builder
  - optional client adapter

Python 구현이 이미 보여 주듯, AG-UI는 단순 “host binder”보다 protocol-level semantics가 많기 때문에 protocol adapter를 독립 artifact로 두는 편이 맞다.

### 2. Host binder
- `agent-framework-agui-spring`
- 포함:
  - Spring MVC/WebFlux endpoint binder
  - SSE/keepalive transport
  - auth dependency injection point
  - principal-derived snapshot scope helper

`.NET`처럼 host binder는 protocol semantics를 재구현하기보다 transport와 persistence integration에 집중해야 한다.

### 3. State policy
- `threadId`는 bare persistent key로 쓰지 않는다.
- snapshot persistence가 켜진 경우 host가 explicit `SnapshotScopeResolver`를 반드시 제공해야 한다.
- predictive delta와 deterministic snapshot은 서로 다른 API surface로 유지한다.

### 4. Client
- client adapter는 선택적으로 별도 artifact(`agent-framework-agui-client`)로 분리할 수 있다.
- Python처럼 protocol adapter에 포함할 수도 있지만, Java ecosystem에서는 server/runtime 의존성과 분리하는 편이 운영상 낫다.

---

## Acceptance scenarios

1. **Request compatibility**
   - `runId`/`run_id`, `threadId`/`thread_id`, `forwardedProps`/`forwarded_props` 같은 alias를 모두 수용해야 한다.
   - legacy resume payload가 canonical interrupt/resume model로 normalize되어야 한다.

2. **Text streaming**
   - text response는 `RUN_STARTED` → `TEXT_MESSAGE_START` → one or more `TEXT_MESSAGE_CONTENT` → `TEXT_MESSAGE_END` → `RUN_FINISHED` 순서를 만족해야 한다.
   - SSE framing과 headers가 event streaming에 맞아야 한다.

3. **Tool call lifecycle**
   - tool call은 start/args/end/result event를 구분해 surface해야 한다.
   - UI-bound tool result payload와 LLM-bound text result를 분리할 수 있어야 한다.

4. **State**
   - predictive tool args streaming에서 `StateDeltaEvent`를 emit할 수 있어야 한다.
   - actual tool result 기반 deterministic state merge 후 `StateSnapshotEvent`를 emit할 수 있어야 한다.
   - 둘이 동시에 active일 때 single coalesced snapshot을 낼 수 있어야 한다.

5. **Interrupt / resume**
   - workflow `request_info` 또는 approval pause는 canonical interrupt model로 surface되어야 한다.
   - resume payload validation이 실패하면 run error 또는 fail-fast response가 나와야 한다.

6. **Session / identity**
   - `threadId` 없이 요청이 오면 host가 새 thread id를 만들 수 있어야 한다.
   - multi-user deployment에서는 `threadId`와 별도로 principal/snapshot scope dimension이 필요해야 한다.
   - 같은 `threadId`라도 다른 scope에서는 state를 공유하지 않아야 한다.

7. **Replay / snapshots**
   - workflow output을 raw events 없이도 replayable thread snapshot으로 재구성할 수 있어야 한다.
   - text/tool/result/state/interrupt가 snapshot에 반영돼야 한다.

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

## 결론

AG-UI는 Agent Framework에서 단순 “streaming UI adapter”보다 넓은 개념이며, 특히 Python 구현은 request model, event model, state model, snapshot model, tool result display model까지 모두 own 한다. `.NET`은 반대로 AG-UI SDK를 활용한 hosting integration에 집중해, session persistence와 ASP.NET transport, JSON resolver, isolation 경계를 안정적으로 올리는 역할을 한다. Java 구현은 이 두 모델을 절충해 **protocol adapter와 host binder를 분리**하고, state snapshot/delta와 interrupt/resume을 AG-UI 전용 first-class 개념으로 다루는 편이 가장 자연스럽다. (출처: [.NET AG-UI endpoint remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore/AGUIEndpointRouteBuilderExtensions.cs#L27-L32), [Python AG-UI public exports](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/__init__.py#L7-L56), [Python tool-result state and snapshot logic](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_run_common.py#L713-L795), [Python snapshot scope design](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/ag-ui/agent_framework_ag_ui/_workflow.py#L206-L215))