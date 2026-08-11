# 18-orchestrations

## 상태/스냅샷

- 이 문서는 고정 커밋 `d0a4165f170193ba1d026a259af40d35bb7eaefe` 기준의 orchestration 계층만 다룬다. 범위는 .NET `SequentialWorkflowBuilder`, `ConcurrentWorkflowBuilder`, `HandoffWorkflowBuilder`, `GroupChatWorkflowBuilder`, `MagenticWorkflowBuilder`와 Python `SequentialBuilder`, `ConcurrentBuilder`, `HandoffBuilder`, `GroupChatBuilder`, `MagenticBuilder`다. ([.NET AgentWorkflowBuilder helper surface](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/AgentWorkflowBuilder.cs#L13-L188), [Python orchestrations package description](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/pyproject.toml#L1-L27))
- 이 문서가 보는 공통 관심사는 “고수준 다중 agent 패턴을 core workflow graph로 어떻게 낮추는가”와 “그 과정에서 participant output, HITL/request-info, checkpointable state, stream surface를 어떻게 정의하는가”다. 세부 그래프 runtime이나 generic workflow state 모델은 별도 문서 범위로 간주한다. ([.NET OrchestrationBuilderBase purpose](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/OrchestrationBuilderBase.cs#L9-L18), [Python participant output config helper](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_participant_output_config.py#L1-L20))

## 목적·경계

### 목적

- orchestration 계층의 목적은 개별 executor/agent를 직접 연결하는 대신, 자주 쓰는 협업 패턴을 별도 builder 표면으로 캡슐화하는 것이다. 이 계층은 participant 목록, coordination 규칙, terminal/intermediate output contract, optional HITL wrapper, checkpoint-enabled runtime wiring을 한 번에 제공한다. ([.NET Sequential remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/SequentialWorkflowBuilder.cs#L10-L23), [.NET Concurrent remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/ConcurrentWorkflowBuilder.cs#L13-L27), [Python Sequential builder overview](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_sequential.py#L65-L96), [Python GroupChat builder overview](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_group_chat.py#L593-L613))

### 경계

- 포함 범위:
  - orchestration builder API
  - participant resolution과 wrapper 선택
  - orchestration-specific graph lowering
  - orchestration-specific state and checkpoint hooks
  - orchestration-specific output designation/HITL defaults
- 제외 범위:
  - core workflow graph validation 세부
  - generic workflow runtime loop
  - declarative YAML workflows
  - generic workflow-as-agent composition internals  
  이 분리는 .NET에서는 `OrchestrationBuilderBase`와 각 orchestration builder 파일, Python에서는 `_participant_output_config.py`, `_workflow_builder.py`, 각 orchestration builder 파일로 구현이 나뉜다는 점에서도 보인다. ([.NET OrchestrationBuilderBase](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/OrchestrationBuilderBase.cs#L16-L153), [Python OrchestrationWorkflowBuilder](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_workflow_builder.py#L1-L12), [Python participant output config](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_participant_output_config.py#L63-L168))

## 성숙도

- .NET orchestration builders는 stable `Microsoft.Agents.AI.Workflows` 패키지의 일부다. 다만 `HandoffsWorkflowBuilder`는 obsolete alias이며 향후 제거 예정이고, `MagenticWorkflowBuilder.WithResponseLanguage(...)`, `WithPromptOverrides(...)`는 `[Experimental]`이다. ([.NET workflows package released](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/Microsoft.Agents.AI.Workflows.csproj#L3-L23), [HandoffsWorkflowBuilder obsolete](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/HandoffWorkflowBuilder.cs#L18-L28), [Magentic experimental methods](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/MagenticWorkflowBuilder.cs#L89-L145))
- Python orchestrations 패키지는 `Development Status :: 5 - Production/Stable` classifier를 가진 stable 패키지다. inspected 범위에서 orchestration builder 자체에 experimental marker는 없고, 별도 experimental workflow surface는 functional workflow API 쪽에만 있다. ([Python orchestrations metadata](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/pyproject.toml#L13-L24), [Python functional workflow experimental warning](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_functional.py#L3-L9))

## 공통 공개 API·타입

### .NET 공통 표면

- helper entrypoint는 `AgentWorkflowBuilder`다. 여기서 sequential/concurrent/handoff/group-chat/magentic builder 생성 또는 즉시 build helper를 제공한다. ([AgentWorkflowBuilder helpers](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/AgentWorkflowBuilder.cs#L13-L188))
- 공통 fluent 표면은 `OrchestrationBuilderBase<TBuilder>`가 제공한다. 여기에는 `WithName`, `WithDescription`, `WithOutputFrom`, `WithIntermediateOutputFrom`, 그리고 “explicit designation이 한 번이라도 들어오면 default designation을 완전히 대체한다”는 규칙이 구현돼 있다. ([OrchestrationBuilderBase metadata/output API](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/OrchestrationBuilderBase.cs#L19-L153))
- participant는 모두 최종적으로 `ExecutorBinding`으로 변환된다. 대부분의 orchestration은 agent를 `BindAsExecutor(...)`로 낮추고, handoff만 custom `HandoffAgentExecutor` factory binding을 만든다. ([Sequential binds agents](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/SequentialWorkflowBuilder.cs#L68-L81), [Concurrent binds agents](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/ConcurrentWorkflowBuilder.cs#L67-L78), [GroupChat binds agents](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/GroupChatWorkflowBuilder.cs#L58-L79), [Handoff custom factories](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/HandoffWorkflowBuilder.cs#L445-L503), [Magentic team bindings](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/MagenticWorkflowBuilder.cs#L148-L186))

### Python 공통 표면

- orchestration runtime은 core `WorkflowBuilder`를 직접 상속하는 `OrchestrationWorkflowBuilder`를 쓴다. 이 subclass는 feature usage attribution만 orchestration 쪽으로 넘기고, 실제 graph build 동작은 core builder를 그대로 사용한다. ([OrchestrationWorkflowBuilder](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_workflow_builder.py#L1-L12))
- 공통 participant output contract는 `_resolve_participant_output_config(...)`가 담당한다. 이 helper는 `output_from`, `intermediate_output_from`, `all`, `all_other`, explicit defaults suppression, overlap rejection을 모든 orchestration에 동일하게 적용한다. ([participant output config resolution](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_participant_output_config.py#L63-L136))
- agent participant는 보통 `AgentExecutor`로 감싸지만, request-info를 켠 orchestration에서는 `AgentApprovalExecutor`로 감싼다. 이 wrapper는 inner workflow를 만들어 agent 응답 승인/보강 흐름을 orchestration 경계 안에 캡슐화한다. ([AgentApprovalExecutor overview](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_orchestration_request_info.py#L169-L236))

## 공통 builder/runtime 접점

- .NET 공통 접점 1: **metadata + output designation**. 모든 orchestration builder는 `ApplyMetadata(...)`와 `ApplyOutputDesignations(...)`를 호출해 workflow name/description과 terminal/intermediate output surface를 최종 core `WorkflowBuilder`에 반영한다. ([ApplyMetadata / ApplyOutputDesignations](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/OrchestrationBuilderBase.cs#L91-L153))
- .NET 공통 접점 2: **AIAgent host executors**. sequential/concurrent/group-chat/magentic는 대부분 `AIAgentHostOptions` 또는 specialized host executors를 써서 participant messages를 agent-friendly session/context로 재구성한다. ([Sequential AIAgentHostOptions](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/SequentialWorkflowBuilder.cs#L68-L72), [Concurrent AIAgentHostOptions](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/ConcurrentWorkflowBuilder.cs#L67-L78), [GroupChat host edges](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/GroupChatWorkflowBuilder.cs#L64-L79), [Magentic binds agents to orchestrator](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/MagenticWorkflowBuilder.cs#L154-L174))
- Python 공통 접점 1: **participant resolution**. sequential/concurrent/group-chat/magentic는 `SupportsAgentRun | Executor`를 받아 executor로 낮춘다. handoff만 예외적으로 `Agent` subtype만 받는다. ([Sequential resolve_participants](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_sequential.py#L189-L229), [Concurrent resolve_participants](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_concurrent.py#L359-L381), [GroupChat resolve_participants](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_group_chat.py#L972-L1003), [Magentic resolve_participants](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_magentic.py#L1759-L1777), [Handoff participants require Agent](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_handoff.py#L578-L580))
- Python 공통 접점 2: **request-info wrapper 선택**. sequential/concurrent/group-chat는 `with_request_info(...)`가 켜지면 participant 일부 또는 전부를 `AgentApprovalExecutor`로 감싼다. handoff/magentic는 각자의 specialized HITL을 갖는다. ([Sequential with_request_info](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_sequential.py#L156-L187), [Concurrent with_request_info](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_concurrent.py#L326-L357), [GroupChat with_request_info](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_group_chat.py#L884-L911))
- 두 언어 모두 orchestration은 결국 core workflow graph로 낮아지므로, checkpointing/streaming/error semantics는 기본 runtime 위에 올라간다. 차이는 **default output contract**, **participant wrapper 종류**, **builder 표면에 직접 노출된 HITL knobs**에서 발생한다. ([.NET common output designation base](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/OrchestrationBuilderBase.cs#L49-L89), [Python common participant output helper](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_participant_output_config.py#L63-L136))

## 하위 기능 1. Sequential Orchestration

### 목적·경계

- sequential orchestration은 participant를 순서대로 실행하는 파이프라인이다. .NET은 “한 agent의 output이 다음 agent의 input”인 pipeline을 만들고 마지막에 aggregator를 붙이며, Python은 input을 `list[Message]` conversation으로 normalize한 뒤 participant chain을 만든다. ([.NET Sequential overview](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/SequentialWorkflowBuilder.cs#L10-L23), [Python Sequential overview](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_sequential.py#L65-L96))

### 공개 API·타입

- .NET은 `SequentialWorkflowBuilder(params IEnumerable<AIAgent> agents)`와 `WithChainOnlyAgentResponses(bool)`를 제공한다. helper entrypoint는 `AgentWorkflowBuilder.BuildSequential(...)` / `CreateSequentialBuilderWith(...)`다. ([Sequential builder ctor/options](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/SequentialWorkflowBuilder.cs#L23-L58), [AgentWorkflowBuilder sequential helpers](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/AgentWorkflowBuilder.cs#L15-L74), [CreateSequentialBuilderWith](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/AgentWorkflowBuilder.cs#L162-L169))
- Python은 `SequentialBuilder(participants=..., checkpoint_storage=..., chain_only_agent_responses=..., output_from=..., intermediate_output_from=...)`와 `with_request_info(...)`를 제공한다. ([Python SequentialBuilder ctor](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_sequential.py#L98-L129), [with_request_info](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_sequential.py#L156-L187))

### 상세 실행 흐름

- .NET은 `AIAgentHostOptions`를 구성한 뒤 모든 agent를 executor binding으로 바꾸고, binding들을 직렬 edge로 연결한다. 마지막에는 `OutputMessagesExecutor`를 추가해 누적된 `ChatMessage`들을 최종 workflow output으로 만들고, 기본 designation은 end terminal + 모든 agents intermediate다. ([Sequential build flow](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/SequentialWorkflowBuilder.cs#L68-L101))
- `WithChainOnlyAgentResponses(true)`면 `ForwardIncomingMessages=false`가 되어 downstream agent가 full accumulated conversation 대신 직전 agent 응답만 받는다. 테스트도 이 차이를 고정한다. ([chain_only_agent_responses option](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/SequentialWorkflowBuilder.cs#L42-L58), [default full conversation test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Workflows.UnitTests/SequentialWorkflowBuilderTests.cs#L74-L90), [chain-only test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Workflows.UnitTests/SequentialWorkflowBuilderTests.cs#L92-L108))
- Python은 `_InputToConversation` executor가 string/message/list input을 `list[Message]`로 normalize하고, participants를 순서대로 연결한다. `chain_only_agent_responses=True`면 wrapped `AgentExecutor`의 `context_mode`가 `last_agent`로 바뀐다. ([input normalizer](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_sequential.py#L49-L63), [resolve participants / context_mode](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_sequential.py#L201-L229), [build graph](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_sequential.py#L231-L272))

### 상태·영속화

- .NET sequential builder 자체가 별도 orchestration state를 만들지는 않는다. persisted state는 각 participant executor의 session/state와 terminal output executor의 flow에 귀속된다. builder는 이름/설명/output designation만 추가한다. ([Sequential build structure](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/SequentialWorkflowBuilder.cs#L83-L101))
- Python도 orchestration-specific state object는 없다. 다만 `with_request_info()`를 쓰면 participant가 `AgentApprovalExecutor` inner workflow로 래핑되어, 승인 재개용 subworkflow checkpoint/state가 participant 경계 안에 생긴다. ([Sequential with_request_info wrapper selection](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_sequential.py#L213-L225), [AgentApprovalExecutor inner workflow](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_orchestration_request_info.py#L206-L236))

### 확장점

- .NET 확장점은 participant ordering, metadata, output designation, chain-only mode다. orchestration-specific builder surface는 비교적 얇다. ([Sequential builder options](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/SequentialWorkflowBuilder.cs#L23-L58))
- Python은 추가로 `with_request_info()`를 제공한다. 특정 agent subset만 human review loop로 감쌀 수 있다. ([Sequential with_request_info](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_sequential.py#L156-L187))

### 동시성·스트리밍·취소

- sequential orchestration은 participant 간 병렬성이 없다. 스트리밍은 participant wrapper가 흘리는 updates에 의존하며, chain-only/full-conversation 차이는 input shaping에만 영향을 준다. ([Sequential tests run order](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Workflows.UnitTests/SequentialWorkflowBuilderTests.cs#L34-L72))

### 오류·검증·보안

- .NET은 zero participants build, non-participant designation 등을 거부한다. ([Sequential invalid args tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Workflows.UnitTests/SequentialWorkflowBuilderTests.cs#L17-L26), [non-participant designation test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Workflows.UnitTests/SequentialWorkflowBuilderTests.cs#L149-L159))
- Python은 empty participants, duplicate participant instances, invalid designation config를 거부한다. ([participant validation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_sequential.py#L131-L154), [designation validation tests shared](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/tests/test_orchestration_intermediate_vs_terminal.py#L739-L776))

### .NET 구현과 테스트

- 주요 구현: `SequentialWorkflowBuilder.cs`
- 주요 테스트: `SequentialWorkflowBuilderTests.cs`가 실행 순서, full conversation default, chain-only mode, designation defaults/override, metadata propagation을 검증한다. ([Sequential implementation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/SequentialWorkflowBuilder.cs#L10-L103), [Sequential tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Workflows.UnitTests/SequentialWorkflowBuilderTests.cs#L15-L179))

### Python 구현과 테스트

- 주요 구현: `_sequential.py`
- 주요 테스트: `test_orchestration_intermediate_vs_terminal.py`가 `output_from="all"`, `intermediate_output_from="all_other"` 같은 designation contract를 고정한다. ([Python sequential implementation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_sequential.py#L1-L272), [Python sequential designation tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/tests/test_orchestration_intermediate_vs_terminal.py#L642-L672))

### Acceptance scenarios

- 기본 sequential mode에서 downstream participant는 prior full conversation을 본다. ([.NET full conversation test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Workflows.UnitTests/SequentialWorkflowBuilderTests.cs#L74-L90))
- chain-only mode에서 downstream participant는 직전 agent output만 본다. ([.NET chain-only test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Workflows.UnitTests/SequentialWorkflowBuilderTests.cs#L92-L108))
- Python에서 `intermediate_output_from="all_other"`와 terminal participant 지정 조합이 정확히 분리되어야 한다. ([Python sequential all_other test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/tests/test_orchestration_intermediate_vs_terminal.py#L652-L662))

## 하위 기능 2. Concurrent Orchestration

### 목적·경계

- concurrent orchestration은 동일 입력을 여러 participant에 fan-out하고, fan-in aggregator로 결합하는 패턴이다. ([.NET Concurrent overview](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/ConcurrentWorkflowBuilder.cs#L13-L27), [Python Concurrent overview](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_concurrent.py#L34-L52))

### 공개 API·타입

- .NET은 `ConcurrentWorkflowBuilder(params IEnumerable<AIAgent> agents)`와 `WithAggregator(...)`를 제공한다. helper entrypoint는 `AgentWorkflowBuilder.BuildConcurrent(...)` / `CreateConcurrentBuilderWith(...)`다. ([Concurrent builder surface](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/ConcurrentWorkflowBuilder.cs#L27-L54), [AgentWorkflowBuilder concurrent helpers](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/AgentWorkflowBuilder.cs#L76-L127), [CreateConcurrentBuilderWith](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/AgentWorkflowBuilder.cs#L171-L178))
- Python은 `ConcurrentBuilder(participants=..., checkpoint_storage=..., output_from=..., intermediate_output_from=...)`, `.with_aggregator(...)`, `.with_request_info(...)`를 제공한다. ([Python ConcurrentBuilder ctor](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_concurrent.py#L179-L239), [with_aggregator](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_concurrent.py#L269-L324), [with_request_info](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_concurrent.py#L326-L357))

### 상세 실행 흐름

- .NET은 `ChatForwardingExecutor start`를 fan-out source로 두고, 각 participant 뒤에 `AggregateTurnMessagesExecutor` accumulator를 달며, fan-in barrier 후 `ConcurrentEndExecutor`로 합친다. default aggregator는 각 participant가 낸 마지막 `ChatMessage`를 모으는 함수다. ([Concurrent build graph](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/ConcurrentWorkflowBuilder.cs#L64-L103))
- Python은 `_DispatchToAllParticipants`가 `AgentExecutorRequest`를 모든 participant에 broadcast하고, fan-in은 participant `AgentExecutorResponse`들을 aggregator로 모은다. default aggregator `_AggregateAgentConversations`는 각 participant response에서 final assistant message 하나씩 추출해 `AgentResponse`를 만든다. ([dispatch executor](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_concurrent.py#L55-L80), [default aggregator](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_concurrent.py#L83-L137), [build graph](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_concurrent.py#L383-L434))
- Python custom aggregator는 executor 또는 callback(sync/async) 둘 다 가능하고, callback이 non-None을 반환하면 그 값이 workflow output이 된다. ([callback aggregator](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_concurrent.py#L139-L177), [with_aggregator semantics](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_concurrent.py#L269-L324))

### 상태·영속화

- .NET concurrent builder 자체는 orchestration 전용 상태 객체를 추가하지 않는다. persisted state는 participant sessions, accumulators, end aggregator 경로에 속한다. ([Concurrent build graph only](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/ConcurrentWorkflowBuilder.cs#L64-L103))
- Python도 별도 orchestration state object는 없다. request-info를 켠 participant만 `AgentApprovalExecutor` inner workflow state를 가진다. ([Concurrent request-info wrapper selection](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_concurrent.py#L359-L381))

### 확장점

- .NET은 custom aggregator function을 주입할 수 있다. ([WithAggregator](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/ConcurrentWorkflowBuilder.cs#L46-L54))
- Python은 custom aggregator executor/callback과 request-info wrapper 양쪽을 선택할 수 있다. ([with_aggregator](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_concurrent.py#L269-L324), [with_request_info](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_concurrent.py#L326-L357))

### 동시성·스트리밍·취소

- concurrent orchestration의 본질은 participant 병렬 실행이다. .NET 테스트는 barrier를 써서 agents가 병렬로 진행되는지 검증한다. ([parallel execution test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Workflows.UnitTests/ConcurrentWorkflowBuilderTests.cs#L30-L57))
- streaming/cancel semantics는 core workflow runtime을 그대로 따른다. orchestration 자체는 별도 cancel API를 추가하지 않는다. ([Concurrent build relies on core workflow](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_concurrent.py#L383-L434))

### 오류·검증·보안

- participant가 없으면 build 실패하고, explicit designation overlap/unknown participant는 공통 validation helper가 거부한다. Python default aggregator는 빈 results list나 assistant reply 부재를 error로 취급한다. ([.NET invalid args](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Workflows.UnitTests/ConcurrentWorkflowBuilderTests.cs#L20-L28), [Python helper overlap validation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_participant_output_config.py#L72-L136), [Python default aggregator empty results guard](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_concurrent.py#L96-L100), [no assistant reply error](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_concurrent.py#L132-L137))

### .NET 구현과 테스트

- 주요 구현: `ConcurrentWorkflowBuilder.cs`
- 주요 테스트: `ConcurrentWorkflowBuilderTests.cs`가 병렬 실행, default/explicit designation, `as_agent` forwarding under futures-on contract를 검증한다. ([Concurrent implementation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/ConcurrentWorkflowBuilder.cs#L13-L104), [Concurrent tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Workflows.UnitTests/ConcurrentWorkflowBuilderTests.cs#L18-L163))

### Python 구현과 테스트

- 주요 구현: `_concurrent.py`
- 주요 테스트: `test_orchestration_intermediate_vs_terminal.py`의 공통 designation validation과 concurrent builder contract 부분이 해당한다. ([Python concurrent implementation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_concurrent.py#L34-L434), [shared designation validation tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/tests/test_orchestration_intermediate_vs_terminal.py#L739-L776))

### Acceptance scenarios

- 두 participant가 병렬로 실행되고 단일 aggregator output으로 수렴해야 한다. ([.NET parallel test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Workflows.UnitTests/ConcurrentWorkflowBuilderTests.cs#L30-L57))
- custom callback aggregator가 non-None을 반환하면 그 값이 workflow output이 되어야 한다. ([Python callback aggregator contract](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_concurrent.py#L161-L177))
- explicit participant output designation이 aggregator-only default를 override해야 한다. ([.NET explicit designations replace defaults](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Workflows.UnitTests/ConcurrentWorkflowBuilderTests.cs#L76-L96))

## 하위 기능 3. Handoff Orchestration

### 목적·경계

- handoff orchestration은 decentralized routing 패턴이다. 현재 speaker agent가 handoff tool/function을 호출해 다음 specialist에게 제어권을 넘긴다. ([.NET Handoff builder summary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/HandoffWorkflowBuilder.cs#L32-L43), [Python HandoffBuilder overview](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_handoff.py#L568-L592))

### 공개 API·타입

- .NET은 `HandoffWorkflowBuilder`, `WithHandoff(s)`, `AddParticipants`, `WithHandoffInstructions`, `EmitAgentResponse(Update)Events`, `WithToolCallFilteringBehavior`, `EnableReturnToPrevious`, `WithAutonomousMode`, `WithTerminationCondition`을 제공한다. `HandoffsWorkflowBuilder`는 obsolete alias다. ([Handoff public surface](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/HandoffWorkflowBuilder.cs#L18-L28), [handoff config methods](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/HandoffWorkflowBuilder.cs#L80-L154), [WithHandoff/AddParticipants](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/HandoffWorkflowBuilder.cs#L156-L280), [autonomous/termination methods](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/HandoffWorkflowBuilder.cs#L285-L443))
- Python은 `HandoffBuilder(participants=..., termination_condition=..., output_from=..., intermediate_output_from=...)`, `.participants(...)`, `.add_handoff(...)`, `.with_start_agent(...)`, `.with_autonomous_mode(...)`, `.with_checkpointing(...)`, `.with_termination_condition(...)`를 제공한다. 다만 participant는 `Agent` subclass여야 하며 generic `SupportsAgentRun`는 지원하지 않는다. ([Python HandoffBuilder ctor](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_handoff.py#L594-L657), [participants require Agent](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_handoff.py#L578-L580), [participants validation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_handoff.py#L659-L711), [add_handoff/start/autonomous/termination](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_handoff.py#L713-L929))

### 상세 실행 흐름

- .NET handoff build는 `HandoffStartExecutor`, `HandoffEndExecutor`를 만들고, participant마다 `HandoffAgentExecutor` factory binding을 생성한다. explicit handoff가 없으면 default mesh handoffs를 만든다. `EnableReturnToPrevious()`가 켜져 있으면 start executor downstream이 switch가 되고, autonomous mode가 켜져 있으면 end executor downstream에 self-loop routing switch가 추가된다. ([create executor bindings and default mesh](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/HandoffWorkflowBuilder.cs#L445-L544), [build graph and switches](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/HandoffWorkflowBuilder.cs#L546-L638))
- .NET runtime 중심은 `HandoffAgentExecutor`다. incoming state에 handoff target이 있으면 source agent의 handoff function call/tool result 메시지를 필터링한 뒤 target agent에게 전달하고, no handoff + termination false면 `HandoffEndExecutor` 또는 autonomous loop-back으로 이어진다. pending approval/function-call requests가 있는 상태에서는 handoff를 허용하지 않는다. ([message filtering and invocation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/Specialized/HandoffAgentExecutor.cs#L241-L260), [shared conversation/autonomous/termination path](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/Specialized/HandoffAgentExecutor.cs#L262-L345), [agent streaming updates and handoff detection](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/Specialized/HandoffAgentExecutor.cs#L425-L509))
- Python handoff는 fully connected participant graph를 만들고, 실제 handoff mechanics는 각 `HandoffAgentExecutor`의 internal tool/middleware/state가 담당한다. explicit handoff config가 없으면 모든 agent가 서로 handoff 가능한 mesh topology를 만든다. ([Python build graph fully connected](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_handoff.py#L931-L1011), [default mesh handoff config](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_handoff.py#L1026-L1073), [executor resolution with autonomous flags](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_handoff.py#L1075-L1115))

### 상태·영속화

- .NET handoff는 orchestration-specific state가 많다. `HandoffAgentExecutor`는 shared conversation bookmark, agent session, pending approval/function-call requests, autonomous turn counter, termination stamping을 checkpoint한다. ([shared state and outgoing state stamping](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/Specialized/HandoffAgentExecutor.cs#L262-L345), [checkpoint hooks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/Specialized/HandoffAgentExecutor.cs#L385-L420))
- Python handoff executor는 autonomous mode turn counter를 state에 저장/복원한다. builder 레벨 checkpointing은 inner core workflow storage에 위임한다. ([autonomous turn counter restore](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_handoff.py#L427-L437), [autonomous turn state save/restore markers](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_handoff.py#L552-L560), [with_checkpointing](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_handoff.py#L849-L896))

### 확장점

- .NET은 handoff instructions, tool-call filtering behavior, return-to-previous, autonomous per-agent prompts/limits, termination predicate를 확장점으로 둔다. ([handoff instructions/filtering](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/HandoffWorkflowBuilder.cs#L80-L142), [autonomous config](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/HandoffWorkflowBuilder.cs#L285-L399), [termination condition](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/HandoffWorkflowBuilder.cs#L401-L443))
- Python은 explicit handoff graph, start agent override, autonomous prompts/turn_limits, checkpointing, termination predicate를 제공한다. ([add_handoff](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_handoff.py#L713-L795), [with_start_agent](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_handoff.py#L797-L816), [with_autonomous_mode](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_handoff.py#L818-L847), [with_termination_condition](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_handoff.py#L898-L929))

### 동시성·스트리밍·취소

- handoff는 논리적으로 한 active speaker 중심이므로 participant 간 동시 발화는 없다. 대신 autonomous mode는 동일 agent를 여러 번 연속 실행할 수 있다. ([.NET autonomous loop build hook](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/HandoffWorkflowBuilder.cs#L597-L610), [Python autonomous mode docs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_handoff.py#L825-L845))
- .NET은 streaming response updates를 optional로 yield할 수 있고, pending request가 있으면 handoff를 금지해 inconsistent cross-agent state를 방지한다. ([EmitAgentResponseUpdateEvents option](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/HandoffWorkflowBuilder.cs#L111-L132), [pending request blocks handoff](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/Specialized/HandoffAgentExecutor.cs#L253-L260))

### 오류·검증·보안

- .NET은 target agent에 handoff reason을 만들 수 없으면 explicit/default handoff registration을 거부한다. 또한 source agent의 handoff function call/tool result messages가 target agent에 전달되면 안 된다는 regression test를 갖는다. ([reason resolution validation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/HandoffWorkflowBuilder.cs#L232-L245), [default mesh reason validation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/HandoffWorkflowBuilder.cs#L525-L535), [handoff filtering regression test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Workflows.UnitTests/HandoffOrchestrationTests.cs#L187-L226))
- Python은 participant가 반드시 `Agent`여야 하고, 모든 participant에 `require_per_service_call_history_persistence=True`가 설정돼야 한다. 그렇지 않으면 build 실패다. ([Agent-only participants](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_handoff.py#L697-L702), [service call history persistence requirement](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_handoff.py#L951-L968))

### .NET 구현과 테스트

- 주요 구현: `HandoffWorkflowBuilder.cs`, `Specialized/HandoffAgentExecutor.cs`
- 주요 테스트: `HandoffOrchestrationTests.cs`가 no-transfer/one-transfer, handoff filtering, metadata, invalid argument behavior를 검증한다. ([Handoff implementation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/HandoffWorkflowBuilder.cs#L18-L638), [Handoff executor implementation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/Specialized/HandoffAgentExecutor.cs#L84-L509), [Handoff tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Workflows.UnitTests/HandoffOrchestrationTests.cs#L21-L257))

### Python 구현과 테스트

- 주요 구현: `_handoff.py`
- 주요 테스트: `test_orchestration_intermediate_vs_terminal.py`가 output designation contract와 shared validation을 고정한다. ([Python handoff implementation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_handoff.py#L568-L1130), [handoff designation tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/tests/test_orchestration_intermediate_vs_terminal.py#L448-L565))

### Acceptance scenarios

- explicit handoff graph가 없으면 default mesh handoff topology가 형성되어야 한다. ([.NET default handoffs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/HandoffWorkflowBuilder.cs#L564-L569), [Python default mesh handoffs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_handoff.py#L1060-L1073))
- handoff target agent는 original user context를 받되, source agent의 handoff function call/tool result messages는 받지 않아야 한다. ([.NET filtering test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Workflows.UnitTests/HandoffOrchestrationTests.cs#L187-L226))
- `intermediate_output_from=["alpha"]`만 줘도 default output set과 충돌 없이 alpha는 intermediate, 나머지는 final로 정리되어야 한다. ([Python demotion regression test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/tests/test_orchestration_intermediate_vs_terminal.py#L529-L565))

## 하위 기능 4. Group-chat Orchestration

### 목적·경계

- group-chat orchestration은 중앙 orchestrator/manager가 다음 speaker를 고르고, speaker의 응답을 다른 participant들에게 broadcast하는 centralized conversation 패턴이다. ([.NET GroupChat builder purpose](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/GroupChatWorkflowBuilder.cs#L13-L15), [Python GroupChatBuilder purpose](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_group_chat.py#L593-L609))

### 공개 API·타입

- .NET은 `GroupChatWorkflowBuilder`, `GroupChatManager`, `RoundRobinGroupChatManager`를 제공한다. builder는 `AddParticipants(...)`를 통해 participants를 등록하고 manager factory를 받아 workflow를 build한다. ([GroupChat builder API](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/GroupChatWorkflowBuilder.cs#L15-L43), [GroupChatManager API](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/GroupChatManager.cs#L16-L95), [RoundRobinGroupChatManager](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/RoundRobinGroupChatManager.cs#L13-L41))
- Python은 `GroupChatBuilder(participants|participant_factories, orchestrator_agent|orchestrator|selection_func, ...)`를 제공하며, exactly one orchestrator config가 필요하다. `with_request_info`, `with_checkpointing`, `with_termination_condition`, `with_max_rounds`도 있다. ([Python GroupChatBuilder ctor](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_group_chat.py#L617-L693), [orchestrator config validation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_group_chat.py#L695-L743), [termination/checkpoint/request_info methods](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_group_chat.py#L793-L911))

### 상세 실행 흐름

- .NET은 `GroupChatHost`가 canonical conversation을 들고 있는 실제 orchestrator executor다. 이 host는 incoming delta를 history에 추가하고, `ShouldTerminateAsync(...)`를 먼저 확인한 후, manager의 `UpdateHistoryAsync(...)`로 broadcast payload를 shape하고, broadcast 후 `SelectNextAgentAsync(...)`로 다음 speaker를 골라 해당 participant에게만 `TurnToken`을 보낸다. completion 시에는 전체 history를 output으로 yield한다. ([GroupChatHost main turn loop](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/Specialized/GroupChatHost.cs#L32-L120))
- .NET builder는 host와 각 participant를 양방향 edge로 연결한다. participants는 자신의 메시지를 host로 다시 보내고, host는 현재 speaker를 제외한 나머지 participant들에게 broadcast한다. ([GroupChatWorkflowBuilder build graph](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/GroupChatWorkflowBuilder.cs#L64-L90), [host broadcast excludes current speaker](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/Specialized/GroupChatHost.cs#L96-L109))
- Python은 builder가 orchestrator와 participants를 bi-directional edge로 연결하고, concrete orchestrator는 manager agent 기반 또는 `selection_func` 기반으로 구현된다. selected participant의 reply를 history에 추가하고, non-speaker participants에게 broadcast한 뒤 다음 participant를 선택하는 구조다. ([GroupChatBuilder build graph](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_group_chat.py#L1005-L1040), [selection-function orchestrator flow hits](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_group_chat.py#L929-L964))

### 상태·영속화

- .NET `GroupChatManager`는 base-state `IterationCount`를 reserved key에 저장하고, subclass state는 `PrefixingWorkflowContext`로 key prefix를 붙여 격리 저장한다. `RoundRobinGroupChatManager`는 `_nextIndex`를 별도로 checkpoint한다. `GroupChatHost`는 `_history`와 `_currentSpeakerExecutorId`를 저장한다. ([GroupChatManager checkpoint API](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/GroupChatManager.cs#L104-L164), [PrefixingWorkflowContext](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/GroupChatManager.cs#L169-L224), [RoundRobin state](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/RoundRobinGroupChatManager.cs#L74-L89), [GroupChatHost checkpoint hooks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/Specialized/GroupChatHost.cs#L133-L158))
- Python group-chat orchestrations는 공통 `OrchestrationState` dataclass를 써 conversation, round index, orchestrator name, metadata, task를 serialization-friendly하게 담는다. ([OrchestrationState](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_orchestration_state.py#L33-L93))

### 확장점

- .NET은 custom `GroupChatManager` subclass를 통해 next-speaker selection, broadcast filtering, termination, subclass-specific checkpointing을 확장할 수 있다. ([GroupChatManager hooks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/GroupChatManager.cs#L52-L95), [checkpoint hooks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/GroupChatManager.cs#L104-L164))
- Python은 `selection_func`, custom `BaseGroupChatOrchestrator`, orchestrator agent, participant factories, request-info wrapping을 모두 선택할 수 있다. ([orchestrator variants](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_group_chat.py#L617-L743), [request_info wrapping](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_group_chat.py#L884-L911))

### 동시성·스트리밍·취소

- group-chat은 논리적으로 한 speaker씩 turn을 갖지만, broadcast send는 여러 participant에게 병렬적으로 갈 수 있다. streaming/cancel semantics는 core runtime을 그대로 따른다. ([GroupChatHost broadcast async fan-out](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/Specialized/GroupChatHost.cs#L96-L109))

### 오류·검증·보안

- .NET host는 manager가 방금 speaker였던 동일 participant를 다시 선택하면 termination 처리한다. 즉 self-echo loop를 fail-safe로 막는다. ([same-speaker termination guard](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/Specialized/GroupChatHost.cs#L75-L85))
- Python은 duplicate participant names, participant/factory 동시 사용, orchestrator config 다중 지정 등을 build-time에 거부한다. ([participant uniqueness and source choice](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_group_chat.py#L745-L791), [exactly one orchestrator config](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_group_chat.py#L732-L743))

### .NET 구현과 테스트

- 주요 구현: `GroupChatWorkflowBuilder.cs`, `GroupChatManager.cs`, `RoundRobinGroupChatManager.cs`, `Specialized/GroupChatHost.cs`
- 주요 테스트: `GroupChatOrchestrationTests.cs`가 approval request checkpoint/resume, denial 후 conversation continuation, external function-call resolution continuation을 검증한다. ([GroupChat implementation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/GroupChatWorkflowBuilder.cs#L13-L92), [GroupChatHost implementation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/Specialized/GroupChatHost.cs#L11-L159), [GroupChat tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Workflows.UnitTests/GroupChatOrchestrationTests.cs#L17-L258))

### Python 구현과 테스트

- 주요 구현: `_group_chat.py`
- 주요 테스트: `test_orchestration_intermediate_vs_terminal.py`가 default terminal output이 orchestrator only라는 점과 explicit participant output/intermediate designation을 고정한다. ([Python group_chat implementation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_group_chat.py#L593-L1040), [group-chat designation tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/tests/test_orchestration_intermediate_vs_terminal.py#L370-L440))

### Acceptance scenarios

- round-robin manager가 participant A, B를 순서대로 선택하고 complete 시 전체 conversation history를 output으로 내야 한다. ([RoundRobinGroupChatManager](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/RoundRobinGroupChatManager.cs#L43-L89), [GroupChatHost complete output](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/Specialized/GroupChatHost.cs#L112-L120))
- current speaker의 응답은 다른 participant들에게만 broadcast되고 자기 자신에게는 echo되지 않아야 한다. ([GroupChatHost broadcast exclusion](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/Specialized/GroupChatHost.cs#L99-L107))
- Python 기본 contract에서는 orchestrator만 terminal output이어야 하고, participant outputs는 explicit designation 없이는 hidden이어야 한다. ([Python default output contract test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/tests/test_orchestration_intermediate_vs_terminal.py#L370-L396))

## 하위 기능 5. Magentic Orchestration

### 목적·경계

- magentic orchestration은 manager가 task plan, progress ledger, replanning, final answer synthesis를 담당하는 planning-centric 패턴이다. 일반 group-chat보다 planning/replanning/HITL review가 더 강한 orchestration이다. ([.NET Magentic overview](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/MagenticWorkflowBuilder.cs#L16-L29), [Python Magentic overview](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_magentic.py#L1381-L1400))

### 공개 API·타입

- .NET은 `MagenticWorkflowBuilder(managerAgent)`에 대해 participant 추가, round/reset/stall limits, `RequirePlanSignoff`, experimental `WithResponseLanguage`, experimental `WithPromptOverrides`를 제공한다. ([.NET Magentic builder surface](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/MagenticWorkflowBuilder.cs#L31-L145))
- Python은 `MagenticBuilder(participants=..., manager|manager_factory|manager_agent|manager_agent_factory, ..., enable_plan_review=False, checkpoint_storage=..., output_from=..., intermediate_output_from=...)`를 제공하고, `with_plan_review(...)`, `with_checkpointing(...)`를 노출한다. ([Python MagenticBuilder ctor](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_magentic.py#L1403-L1492), [with_plan_review](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_magentic.py#L1523-L1569), [with_checkpointing](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_magentic.py#L1571-L1611))

### 상세 실행 흐름

- .NET `MagenticWorkflowBuilder.ReduceToWorkflowBuilder()`는 manager agent로부터 `MagenticOrchestrator` binding을 만들고, team bindings를 orchestrator와 양방향 fan-out/direct edges로 연결한다. default designation은 orchestrator terminal + team intermediate다. ([ReduceToWorkflowBuilder](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/MagenticWorkflowBuilder.cs#L148-L186), [Build validation and return](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/MagenticWorkflowBuilder.cs#L189-L221))
- .NET `MagenticOrchestrator`는 first turn에 `UpdatePlanAndDelegateAsync(...)`로 initial plan을 만들고, `requirePlanSignoff`가 true면 plan review request를 external port로 보내고, 아니면 바로 coordination round로 들어간다. 이후 각 round마다 progress ledger를 만들고, complete/stall/loop/invalid next speaker를 판단해 final answer 또는 reset+replan 또는 next speaker delegation으로 이어진다. ([plan update and review request](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/Specialized/Magentic/MagenticOrchestrator.cs#L111-L183), [TakeTurn first-vs-subsequent](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/Specialized/Magentic/MagenticOrchestrator.cs#L186-L210), [RunCoordinationRoundAsync](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/Specialized/Magentic/MagenticOrchestrator.cs#L248-L337), [ResetAndReplanAsync / PrepareFinalAnswerAsync](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/Specialized/Magentic/MagenticOrchestrator.cs#L339-L355))
- Python은 `MagenticBuilder`가 custom manager instance/factory 또는 manager-agent 기반 `StandardMagenticManager`를 만들고, 이를 `MagenticOrchestrator`로 감싼다. build graph는 group-chat과 비슷한 bidirectional orchestrator↔participant 구조지만, orchestration logic은 manager-led planning/replanning 중심이다. ([manager resolution](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_magentic.py#L1613-L1757), [build graph](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_magentic.py#L1779-L1807))

### 상태·영속화

- .NET `MagenticOrchestrator`는 `MagenticTaskContext`와 current speaker executor id를 checkpoint state에 저장한다. restore 시 task context를 state에서 복원하고, build-time `responseLanguage`와 `promptOverrides`는 checkpoint 값이 아니라 builder configuration으로 다시 적용한다. ([checkpoint save](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/Specialized/Magentic/MagenticOrchestrator.cs#L357-L373), [checkpoint restore](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/Specialized/Magentic/MagenticOrchestrator.cs#L376-L405))
- Python은 orchestration-wide state를 `OrchestrationState` 또는 manager-specific state로 모델링하며, checkpoint storage는 core workflow 수준에서 주입된다. build-time config와 runtime conversation/progress state가 분리된다. ([OrchestrationState common shape](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_orchestration_state.py#L33-L93), [with_checkpointing](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_magentic.py#L1571-L1611))

### 확장점

- .NET은 manager agent, participants, plan signoff, response language, prompt override, limits가 확장점이다. progress-ledger prompt override는 `{schema}` placeholder를 반드시 포함해야 한다. ([builder options](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/MagenticWorkflowBuilder.cs#L48-L87), [experimental options](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/MagenticWorkflowBuilder.cs#L89-L145), [schema placeholder validation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/MagenticWorkflowBuilder.cs#L197-L202))
- Python은 custom manager instance/factory, manager agent factory, all prompt fields, max stalls/resets/rounds, plan review flag를 builder surface에서 제공한다. ([manager config knobs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_magentic.py#L1403-L1492), [set_manager resolution rules](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_magentic.py#L1613-L1721))

### 동시성·스트리밍·취소

- Magentic도 active speaker는 한 명이지만, non-speaker participants에게 reply broadcast가 발생한다. `TurnToken`은 chosen speaker에게만 간다. ([broadcast reply to other participants](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/Specialized/Magentic/MagenticOrchestrator.cs#L214-L239), [next speaker turn token](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/Specialized/Magentic/MagenticOrchestrator.cs#L324-L337))
- plan review는 external request/HITL로 동작하므로, orchestration은 planning phase에서 일시 중단될 수 있다. ([SubmitPlanReviewRequestAsync](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/Specialized/Magentic/MagenticOrchestrator.cs#L111-L121), [ProcessPlanReviewAsync](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/Specialized/Magentic/MagenticOrchestrator.cs#L124-L160))

### 오류·검증·보안

- .NET은 progress ledger 생성 실패를 warning event로 남기고 reset+replan으로 회복을 시도한다. invalid next speaker도 warning 후 final answer path로 넘어간다. ([progress ledger failure recovery](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/Specialized/Magentic/MagenticOrchestrator.cs#L265-L280), [invalid next speaker warning](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/Specialized/Magentic/MagenticOrchestrator.cs#L307-L321))
- Python은 manager config가 exactly one of `manager`, `manager_factory`, `manager_agent`, `manager_agent_factory`여야 하고, participant names가 unique여야 한다. ([manager config exclusivity](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_magentic.py#L1658-L1664), [participant uniqueness](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_magentic.py#L1494-L1521))

### .NET 구현과 테스트

- 주요 구현: `MagenticWorkflowBuilder.cs`, `Specialized/Magentic/MagenticOrchestrator.cs`
- 주요 테스트: `MagenticWorkflowBuilderTests.cs`가 default/explicit designations, experimental chaining, progress-ledger prompt `{schema}` validation을 검증한다. ([Magentic implementation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/MagenticWorkflowBuilder.cs#L16-L221), [Magentic orchestrator](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/Specialized/Magentic/MagenticOrchestrator.cs#L13-L405), [Magentic tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Workflows.UnitTests/MagenticWorkflowBuilderTests.cs#L10-L143))

### Python 구현과 테스트

- 주요 구현: `_magentic.py`
- 주요 테스트: `test_orchestration_intermediate_vs_terminal.py`가 manager-only default terminal output과 explicit worker output/intermediate designation을 검증한다. ([Python magentic implementation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_magentic.py#L1381-L1811), [Magentic designation tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/tests/test_orchestration_intermediate_vs_terminal.py#L572-L639))

### Acceptance scenarios

- initial plan 생성 후 plan review를 켠 경우 external review request가 발생해야 한다. ([.NET plan review request path](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/Specialized/Magentic/MagenticOrchestrator.cs#L162-L183))
- progress ledger가 stall/loop를 감지하면 reset+replan으로 넘어가야 한다. ([stall handling](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/Specialized/Magentic/MagenticOrchestrator.cs#L290-L303), [ResetAndReplanAsync](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/Specialized/Magentic/MagenticOrchestrator.cs#L339-L346))
- Python 기본 contract에서는 manager만 terminal output이고 worker outputs는 explicit designation 없이는 hidden이어야 한다. ([Python default output test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/tests/test_orchestration_intermediate_vs_terminal.py#L602-L617))

## .NET/Python 차이

- **output 기본 계약**이 가장 큰 차이다.  
  - Sequential: .NET은 terminal end executor + agents intermediate, Python은 마지막 participant만 terminal  
  - Concurrent: .NET은 aggregator terminal + agents/accumulators intermediate, Python은 aggregator만 terminal  
  - Handoff: .NET은 end executor terminal + agents intermediate, Python은 모든 participant가 terminal  
  - Group-chat: .NET은 host/orchestrator terminal + participants intermediate, Python은 orchestrator만 terminal  
  - Magentic: .NET은 manager terminal + workers intermediate, Python은 manager만 terminal  
  이 차이는 코드와 테스트 둘 다에서 확인된다. ([.NET Sequential defaults](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/SequentialWorkflowBuilder.cs#L94-L99), [Python Sequential defaults](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_sequential.py#L252-L265), [.NET Concurrent defaults](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/ConcurrentWorkflowBuilder.cs#L95-L100), [Python Concurrent defaults](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_concurrent.py#L415-L428), [.NET Handoff defaults](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/HandoffWorkflowBuilder.cs#L623-L635), [Python Handoff docs default output](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_handoff.py#L582-L592), [.NET GroupChat defaults](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/GroupChatWorkflowBuilder.cs#L81-L88), [Python GroupChat defaults](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_group_chat.py#L1020-L1034), [.NET Magentic defaults](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/MagenticWorkflowBuilder.cs#L176-L183), [Python Magentic defaults](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_magentic.py#L1787-L1800))
- **HITL builder surface**도 다르다. Python sequential/concurrent/group-chat는 generic `with_request_info(...)` wrapper를 직접 제공하지만, .NET 같은 builders에는 대응 generic knob가 없고, HITL은 participant specialized executors나 handoff/magentic specialized flows에 내장되는 경향이 강하다. ([Python Sequential request_info](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_sequential.py#L156-L187), [Python Concurrent request_info](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_concurrent.py#L326-L357), [Python GroupChat request_info](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_group_chat.py#L884-L911))
- **handoff participant type 제약**도 다르다. Python handoff는 `Agent`만 허용하고 generic `SupportsAgentRun`는 허용하지 않지만, .NET은 `AIAgent` 기반 builder surface를 갖고 별도 client capability validation을 runtime/build 단계에서 맡긴다. ([Python Agent-only handoff](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_handoff.py#L578-L580), [Python participants validation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_handoff.py#L697-L702), [.NET Handoff builder surface](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/HandoffWorkflowBuilder.cs#L27-L37))
- **Magentic defaults**도 차이가 있다. .NET은 `RequirePlanSignoff` default가 true이고, Python은 `enable_plan_review` default가 false다. ([.NET requirePlanSignoff default field](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/MagenticWorkflowBuilder.cs#L34-L39), [.NET RequirePlanSignoff method](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/MagenticWorkflowBuilder.cs#L78-L87), [Python enable_plan_review default](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_magentic.py#L1421-L1428))

## 문서 차이

- .NET sequential/concurrent/handoff builder comments 일부는 “Python-aligned” 또는 “matches Python”을 시사하지만, 실제 default output contract는 언어 간 다르다. 코드와 테스트를 기준으로 보면 alignment보다 divergence가 더 정확한 설명이다. ([.NET Sequential remark](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/SequentialWorkflowBuilder.cs#L15-L21), [.NET Concurrent remark](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/ConcurrentWorkflowBuilder.cs#L19-L25), [.NET Handoff default comment](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/HandoffWorkflowBuilder.cs#L626-L629), [Python handoff doc actual contract](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_handoff.py#L582-L592))
- Python 쪽은 `test_orchestration_intermediate_vs_terminal.py`가 사실상의 공통 명세 역할을 한다. README보다 이 테스트가 각 orchestration의 terminal/intermediate visibility semantics를 더 정확하게 고정한다. ([shared designation tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/tests/test_orchestration_intermediate_vs_terminal.py#L370-L776))

## Java 결정

- Java orchestration 계층은 **공통 participant-output designation helper**와 **pattern-specific builder**를 분리해야 한다. Python `_participant_output_config.py`와 .NET `OrchestrationBuilderBase`가 각각 다른 방식으로 같은 문제를 풀고 있으므로, Java는 이를 명시적 shared module로 끌어내는 편이 유지보수에 유리하다. ([.NET OrchestrationBuilderBase](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/OrchestrationBuilderBase.cs#L49-L153), [Python participant output helper](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_participant_output_config.py#L63-L136))
- Java는 output defaults를 언어별 암묵 규칙으로 두지 말고, orchestration builder마다 `defaultOutputPolicy`를 명시적으로 internal constant 또는 public enum으로 두는 편이 낫다. 현재 .NET/Python drift를 그대로 답습하면 cross-language parity가 깨진다. ([.NET vs Python default contracts across builders](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/SequentialWorkflowBuilder.cs#L94-L99), [Python Sequential defaults](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_sequential.py#L252-L265), [Python Handoff docs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_handoff.py#L582-L592))
- Java handoff는 Python처럼 provider capability를 강제하는 validation(`must be Agent`, `require_per_service_call_history_persistence`)을 build-time에 두는 것이 좋다. user confusion과 runtime corruption을 줄일 수 있다. ([Python handoff validation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_handoff.py#L697-L702), [service-call-history persistence requirement](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_handoff.py#L951-L968))
- Java Magentic는 plan review, progress ledger, replanning을 하나의 “manager SPI”로 추상화해야 한다. Python의 `manager / manager_factory / manager_agent / manager_agent_factory` 네 경로와 .NET의 manager-agent 기반 orchestrator를 참고해, public builder에는 exactly-one manager source contract를 두는 편이 좋다. ([Python exactly-one manager source](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_magentic.py#L1658-L1664), [.NET manager-agent binding](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/MagenticWorkflowBuilder.cs#L212-L220))
- Java sequential/concurrent/group-chat는 Python-style `withRequestInfo(...)` wrapper surface를 넣는 편이 좋다. generic HITL wrapper가 builder surface에 있으면 orchestration 사용자 경험이 일관되고, specialized flows(handoff/magentic)와도 구분이 쉽다. ([Python request_info wrapper surfaces](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_sequential.py#L156-L187), [Concurrent request_info](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_concurrent.py#L326-L357), [GroupChat request_info](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_group_chat.py#L884-L911))

## source inventory

### 공통 .NET

- `dotnet/src/Microsoft.Agents.AI.Workflows/AgentWorkflowBuilder.cs`  
  <https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/AgentWorkflowBuilder.cs#L13-L188>
- `dotnet/src/Microsoft.Agents.AI.Workflows/OrchestrationBuilderBase.cs`  
  <https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/OrchestrationBuilderBase.cs#L9-L153>

### 공통 Python

- `python/packages/orchestrations/agent_framework_orchestrations/_workflow_builder.py`  
  <https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_workflow_builder.py#L1-L12>
- `python/packages/orchestrations/agent_framework_orchestrations/_participant_output_config.py`  
  <https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_participant_output_config.py#L1-L168>
- `python/packages/orchestrations/agent_framework_orchestrations/_orchestration_request_info.py`  
  <https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_orchestration_request_info.py#L19-L242>

### Sequential

- `.NET` `SequentialWorkflowBuilder.cs`  
  <https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/SequentialWorkflowBuilder.cs#L10-L103>
- `.NET` `SequentialWorkflowBuilderTests.cs`  
  <https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Workflows.UnitTests/SequentialWorkflowBuilderTests.cs#L15-L179>
- `Python` `_sequential.py`  
  <https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_sequential.py#L1-L272>

### Concurrent

- `.NET` `ConcurrentWorkflowBuilder.cs`  
  <https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/ConcurrentWorkflowBuilder.cs#L13-L104>
- `.NET` `ConcurrentWorkflowBuilderTests.cs`  
  <https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Workflows.UnitTests/ConcurrentWorkflowBuilderTests.cs#L18-L163>
- `Python` `_concurrent.py`  
  <https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_concurrent.py#L1-L434>

### Handoff

- `.NET` `HandoffWorkflowBuilder.cs`  
  <https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/HandoffWorkflowBuilder.cs#L18-L638>
- `.NET` `Specialized/HandoffAgentExecutor.cs`  
  <https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/Specialized/HandoffAgentExecutor.cs#L84-L509>
- `.NET` `HandoffOrchestrationTests.cs`  
  <https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Workflows.UnitTests/HandoffOrchestrationTests.cs#L21-L257>
- `Python` `_handoff.py`  
  <https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_handoff.py#L568-L1130>

### Group-chat

- `.NET` `GroupChatWorkflowBuilder.cs`  
  <https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/GroupChatWorkflowBuilder.cs#L13-L92>
- `.NET` `GroupChatManager.cs`  
  <https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/GroupChatManager.cs#L16-L224>
- `.NET` `RoundRobinGroupChatManager.cs`  
  <https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/RoundRobinGroupChatManager.cs#L13-L91>
- `.NET` `Specialized/GroupChatHost.cs`  
  <https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/Specialized/GroupChatHost.cs#L11-L159>
- `.NET` `GroupChatOrchestrationTests.cs`  
  <https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Workflows.UnitTests/GroupChatOrchestrationTests.cs#L17-L258>
- `Python` `_group_chat.py`  
  <https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_group_chat.py#L593-L1044>

### Magentic

- `.NET` `MagenticWorkflowBuilder.cs`  
  <https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/MagenticWorkflowBuilder.cs#L16-L221>
- `.NET` `Specialized/Magentic/MagenticOrchestrator.cs`  
  <https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/Specialized/Magentic/MagenticOrchestrator.cs#L13-L405>
- `.NET` `MagenticWorkflowBuilderTests.cs`  
  <https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Workflows.UnitTests/MagenticWorkflowBuilderTests.cs#L10-L143>
- `Python` `_magentic.py`  
  <https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/agent_framework_orchestrations/_magentic.py#L1381-L1811>

### 공통 Python tests for output contract

- `python/packages/orchestrations/tests/test_orchestration_intermediate_vs_terminal.py`  
  <https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/tests/test_orchestration_intermediate_vs_terminal.py#L370-L776>

### 메타데이터

- `.NET` workflows package metadata  
  <https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/Microsoft.Agents.AI.Workflows.csproj#L3-L23>
- `Python` orchestrations package metadata  
  <https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/orchestrations/pyproject.toml#L1-L27>