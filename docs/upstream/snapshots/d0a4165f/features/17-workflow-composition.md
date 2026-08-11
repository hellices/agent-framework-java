# 17-workflow-composition

## 상태/스냅샷

- 이 문서는 **워크플로를 다른 워크플로/에이전트 안에 합성하는 계층**만 다룬다. 대상은 .NET의 `SubworkflowBinding`, `WorkflowHostExecutor`, `WorkflowHostingExtensions.AsAIAgent(...)`, `WorkflowHostAgent`, `WorkflowSession`과 Python의 graph-based `WorkflowExecutor`, `Workflow.as_agent()`, 그리고 experimental functional workflow API(`@workflow`, `@step`, `RunContext`, `FunctionalWorkflowAgent`)다. ([.NET SubworkflowBinding](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/SubworkflowBinding.cs#L17-L46), [.NET WorkflowHostingExtensions](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/WorkflowHostingExtensions.cs#L8-L39), [.NET WorkflowHostExecutor](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/Specialized/WorkflowHostExecutor.cs#L17-L44), [.NET WorkflowSession](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/WorkflowSession.cs#L19-L52), [Python Workflow.as_agent](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_workflow.py#L1193-L1234), [Python WorkflowExecutor](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_workflow_executor.py#L106-L170), [Python functional workflow overview](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_functional.py#L1-L31))
- 이 기능군의 핵심은 **구성 경계(boundary)** 다.  
  - subworkflow를 executor처럼 감쌀 때 어떤 이벤트를 부모에게 forwarding할지  
  - workflow를 agent처럼 감쌀 때 어떤 출력만 최종 응답으로 합칠지  
  - functional workflow에서 step cache/checkpoint/request_info가 어떤 재실행 경계를 만들지  
  가 중요한 설계 포인트다. ([.NET WorkflowHostExecutor event forwarding](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/Specialized/WorkflowHostExecutor.cs#L188-L249), [.NET WorkflowSession stage invocation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/WorkflowSession.cs#L443-L647), [Python WorkflowExecutor result processing](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_workflow_executor.py#L498-L610), [Python StepWrapper semantics](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_functional.py#L411-L552))

## 목적·경계

### 목적

- 이 기능의 목적은 다음 네 가지다.
  1. **워크플로를 다른 워크플로 안에 중첩**해 계층형 구성을 가능하게 한다.
  2. **워크플로를 agent 표면으로 노출**해 agent ecosystem에서 재사용 가능하게 한다.
  3. **세션/상태/checkpoint 경계**를 composition 경계와 정렬한다.
  4. **stream/error/request-info를 경계 간에 재매핑**한다.  
  .NET은 `WorkflowHostExecutor`와 `WorkflowHostAgent`/`WorkflowSession`이, Python은 `WorkflowExecutor`, `Workflow.as_agent()`, `FunctionalWorkflowAgent`가 이 역할을 맡는다. ([.NET WorkflowHostExecutor role](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/Specialized/WorkflowHostExecutor.cs#L17-L44), [.NET WorkflowHostAgent role](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/WorkflowHostAgent.cs#L16-L27), [Python WorkflowExecutor overview](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_workflow_executor.py#L106-L170), [Python FunctionalWorkflowAgent overview](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_functional.py#L1331-L1354))

### 경계

- 포함 범위:
  - subworkflow binding 및 ownership
  - subworkflow ↔ parent 간 output/request/error forwarding
  - workflow-as-agent의 session/response composition
  - functional workflow의 workflow/step composition, replay, cache, HITL restart
- 제외 범위:
  - 일반 graph builder/edge topology 자체
  - 일반 workflow runtime의 기본 superstep scheduler
  - orchestration별 business policy
  - declarative YAML lowering 전반  
  inspected code 기준으로 이 문서의 직접 범위는 `.NET`의 `SubworkflowBinding.cs`, `WorkflowHostExecutor.cs`, `WorkflowHostAgent.cs`, `WorkflowSession.cs`, Python의 `_workflow_executor.py`, `_workflow.py`의 `as_agent`, `_functional.py`와 그 테스트들이다. ([.NET SubworkflowBinding](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/SubworkflowBinding.cs#L17-L46), [.NET WorkflowHostExecutor](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/Specialized/WorkflowHostExecutor.cs#L17-L321), [.NET WorkflowSession](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/WorkflowSession.cs#L19-L768), [Python WorkflowExecutor](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_workflow_executor.py#L106-L610), [Python functional workflow](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_functional.py#L1-L1572))

## 성숙도

- .NET composition 기능은 released `Microsoft.Agents.AI.Workflows` 패키지의 일부다. `WorkflowHostingExtensions.AsAIAgent(...)`, `SubworkflowBinding`, `WorkflowHostExecutor` 구현은 모두 이 stable package 안에 있다. ([.NET package metadata](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/Microsoft.Agents.AI.Workflows.csproj#L3-L23), [.NET AsAIAgent public extension](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/WorkflowHostingExtensions.cs#L8-L39))
- Python graph-based composition (`WorkflowExecutor`, `Workflow.as_agent`)은 stable core package 일부다. ([Python core metadata](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/pyproject.toml#L13-L24), [Workflow.as_agent](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_workflow.py#L1193-L1234), [WorkflowExecutor](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_workflow_executor.py#L106-L170))
- Python functional workflow API는 명시적으로 experimental이다. module header warning, `@experimental` annotations, 그리고 tests의 experimental stage 검증이 이를 뒷받침한다. ([functional workflow warning](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_functional.py#L3-L9), [get_run_context experimental](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_functional.py#L71-L79), [FunctionalWorkflow experimental](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_functional.py#L636-L699), [experimental stage tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/workflow/test_functional_workflow.py#L1744-L1758))

## 공개 API·타입

### .NET

- public workflow-as-agent entrypoint는 `WorkflowHostingExtensions.AsAIAgent(...)`다. 이 API는 `id`, `name`, `description`, `executionEnvironment`, `includeExceptionDetails`, `includeWorkflowOutputsInResponse`를 받는다. ([AsAIAgent public API](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/WorkflowHostingExtensions.cs#L13-L38))
- public subworkflow binding surface는 `SubworkflowBinding(Workflow workflowInstance, string id, ExecutorOptions? executorOptions = null)`이다. 이 타입은 `ExecutorBinding`을 상속하고, `WorkflowHostExecutor`를 만들어 child workflow를 executor처럼 노출한다. ([SubworkflowBinding](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/SubworkflowBinding.cs#L17-L46))
- 내부 구현 타입이지만 composition semantics를 규정하는 핵심은 `WorkflowHostExecutor`, `WorkflowHostAgent`, `WorkflowSession`이다. public behavior는 이들 구현에 의해 결정된다. ([WorkflowHostExecutor](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/Specialized/WorkflowHostExecutor.cs#L17-L44), [WorkflowHostAgent](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/WorkflowHostAgent.cs#L16-L52), [WorkflowSession](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/WorkflowSession.cs#L19-L52))

### Python

- graph-based composition public surface:
  - `WorkflowExecutor(workflow, id, allow_direct_output=False, propagate_request=False)`
  - `Workflow.as_agent(name=None, description=None, context_providers=None, **kwargs)`  
  ([WorkflowExecutor ctor](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_workflow_executor.py#L248-L279), [Workflow.as_agent](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_workflow.py#L1193-L1234))
- graph subworkflow correlation types:
  - `SubWorkflowRequestMessage`
  - `SubWorkflowResponseMessage`  
  ([SubWorkflowResponseMessage](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_workflow_executor.py#L60-L74), [SubWorkflowRequestMessage](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_workflow_executor.py#L75-L103))
- functional composition public surface:
  - `@workflow`
  - `FunctionalWorkflow`
  - `@step`
  - `StepWrapper`
  - `RunContext`
  - `get_run_context()`
  - `FunctionalWorkflowAgent`  
  ([functional workflow overview/public symbols](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_functional.py#L10-L31), [RunContext](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_functional.py#L107-L188), [StepWrapper](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_functional.py#L411-L552), [FunctionalWorkflow](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_functional.py#L636-L699), [FunctionalWorkflowAgent](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_functional.py#L1331-L1450))

## 상세 실행 흐름

### 1. .NET SubworkflowBinding과 WorkflowHostExecutor

- `SubworkflowBinding`은 child workflow를 executor binding으로 감싸는 public composition entry다. 생성 시 `CreateWorkflowExecutorFactory(...)`가 **ownership token을 child workflow에 먼저 부여**하고, 이후 `WorkflowHostExecutor`를 생성한다. 이 binding은 `IsSharedInstance=false`, `SupportsConcurrentSharedExecution=true`, `SupportsResetting=false`로 선언된다. 즉 child workflow는 shared child instance처럼 직접 재사용되지 않고 host executor가 매개한다. ([SubworkflowBinding factory and ownership](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/SubworkflowBinding.cs#L17-L46))
- `WorkflowHostExecutor`는 parent workflow 안에서 child workflow의 runtime proxy 역할을 한다.  
  - `ConfigureProtocol(...)`는 child workflow protocol의 yield types를 자신의 yields로 노출하고, catch-all route를 사용해 parent에서 온 모든 메시지를 child workflow start input 또는 `ExternalResponse`로 해석한다.  
  - 이미 running child가 있으면 incoming message를 기존 run에 enqueue하고, 없으면 새 subworkflow streaming run을 시작한다.  
  ([WorkflowHostExecutor protocol](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/Specialized/WorkflowHostExecutor.cs#L46-L76), [EnsureRunSendMessageAsync](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/Specialized/WorkflowHostExecutor.cs#L104-L164))
- child workflow가 external request를 emit하면 `WorkflowHostExecutor.ForwardWorkflowEventAsync(...)`가 이를 parent join context로 다시 보낸다. 이때 port id를 `"{hostExecutorId}.{childPortId}"`로 qualify하여 parent 쪽 response correlation을 확보하고, 내부적으로 `_pendingResponsePorts`에 original `RequestPortInfo`를 저장한다. 반대로 parent에서 돌아온 response는 `CheckAndUnqualifyResponse(...)`가 다시 child port id로 되돌린다. ([qualify/unqualify response port ids](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/Specialized/WorkflowHostExecutor.cs#L166-L186), [request forwarding and pendingResponsePorts](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/Specialized/WorkflowHostExecutor.cs#L201-L205))
- child workflow output forwarding은 executor option 경계로 제어된다. `WorkflowOutputEvent`를 받으면:
  - `AutoSendMessageHandlerResultObject=true`면 parent workflow로 message로 forward
  - `AutoYieldOutputHandlerResultObject=true`면 parent workflow output으로 yield  
  한다. 따라서 child output을 parent 내부 메시지로 볼지, parent 외부 caller-facing output으로 볼지 host executor 옵션이 결정한다. ([child output forwarding](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/Specialized/WorkflowHostExecutor.cs#L209-L223))
- child workflow internal lifecycle events(`WorkflowStartedEvent`, `SuperStepStartedEvent`, `SuperStepCompletedEvent`)는 parent로 forwarding하지 않는다. 반면 `WorkflowErrorEvent`는 `SubworkflowErrorEvent`, warning string은 `SubworkflowWarningEvent`로 wrapping해 parent에 전달한다. 즉 composition 경계가 raw child observability surface를 일부 숨기고 일부 재형식화한다. ([event boundary filtering](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/Specialized/WorkflowHostExecutor.cs#L188-L249))
- checkpoint restore 시 `WorkflowHostExecutor`는 child 전용 `InMemoryCheckpointManager`와 `_pendingResponsePorts`를 state에서 복원한다. checkpoint manager instance가 바뀌었으면 active child run stack을 `ResetAsync()`로 갈아엎고, 이후 `EnsureRunSendMessageAsync(resume:true)`로 child run을 resume한다. ([host executor checkpoint restore](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/Specialized/WorkflowHostExecutor.cs#L256-L321))

### 2. .NET workflow-as-agent: WorkflowHostAgent + WorkflowSession

- public `WorkflowHostingExtensions.AsAIAgent(...)`는 `WorkflowHostAgent`를 만든다. 이 agent는 workflow가 chat protocol인지 미리 `DescribeProtocolAsync()`로 검증하고, execution environment가 checkpointing이 없더라도 in-proc이면 implicit in-memory checkpointing을 붙일 수 있다. ([AsAIAgent public extension](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/WorkflowHostingExtensions.cs#L13-L38), [WorkflowHostAgent constructor and validation setup](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/WorkflowHostAgent.cs#L27-L52), [ValidateWorkflowAsync](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/WorkflowHostAgent.cs#L70-L74))
- `WorkflowSession`이 실제 세션 경계다. 이 세션은 다음을 들고 있다.
  - current `LastCheckpoint`
  - optional in-memory checkpoint manager
  - `StateBag`
  - `_pendingRequests` map  
  즉 workflow-as-agent는 agent session serialization 안에 workflow checkpointing state를 포함할 수 있다. ([WorkflowSession fields](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/WorkflowSession.cs#L19-L52), [session deserialize ctor](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/WorkflowSession.cs#L98-L130), [SessionState serialization payload](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/WorkflowSession.cs#L755-L767))
- 세션이 restore되면 `CreateOrResumeRunAsync(...)`가 `LastCheckpoint` 유무에 따라 fresh run 또는 resume run을 만든다. resume 경로는 **pending request republishing을 suppress**하는 internal resume API를 사용한다. 이유는 `WorkflowSession`이 pending request를 별도 관리하며 incoming agent-side responses를 `ExternalResponse`로 다시 변환해야 하기 때문이다. ([CreateOrResumeRunAsync](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/WorkflowSession.cs#L176-L206))
- `SendMessagesWithResponseConversionAsync(...)`는 workflow-as-agent composition의 핵심 경계다. 이 메서드는 incoming agent-side `ChatMessage` contents를 스캔해:
  - ordinary chat messages는 regular messages로
  - `FunctionResultContent` / `ToolApprovalResponseContent` 등 pending request와 매칭되는 것들은 `ExternalResponse`로  
  분리한다. regular messages를 먼저 보내고, external responses를 나중에 보내며, 처리한 pending request는 세션 map에서 제거한다. ([response conversion split](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/WorkflowSession.cs#L215-L289))
- workflow stage streaming은 `InvokeStageAsync(...)`가 수행한다. 이 메서드는 child `StreamingRun`의 raw `WorkflowEvent`들을 agent-facing `AgentResponseUpdate`로 바꾸며, 경계별로 다음 규칙을 적용한다.
  - `AgentResponseUpdateEvent` → 그대로 forward
  - `RequestInfoEvent` → AIContent request content로 변환하고 `_pendingRequests`에 등록
  - `WorkflowErrorEvent` / `ExecutorFailedEvent` → `ErrorContent`로 노출, 내부 graph ids는 숨김
  - `SuperStepCompletedEvent` → observability update only
  - `AgentResponseEvent` → 이미 streamed message id는 suppress하여 중복 방지
  - `WorkflowOutputEvent` → `includeWorkflowOutputsInResponse` 또는 terminal-output 여부에 따라 포함 여부 결정  
  ([InvokeStageAsync event translation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/WorkflowSession.cs#L443-L647))
- `WorkflowHostAgent.RunCoreAsync(...)`와 `RunCoreStreamingAsync(...)`는 결국 `WorkflowSession.InvokeStageAsync(...)`를 소비한다. merged final response는 terminal workflow outputs가 있으면 그것만, 없으면 전체 updates를 기반으로 만든다. 즉 workflow-as-agent의 최종 응답 경계는 workflow output designation과 host options이 함께 결정한다. ([RunCoreAsync](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/WorkflowHostAgent.cs#L109-L133), [RunCoreStreamingAsync](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/WorkflowHostAgent.cs#L135-L158), [terminal output merge policy](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/WorkflowHostAgent.cs#L160-L195))

### 3. Python graph composition: WorkflowExecutor

- Python `WorkflowExecutor`는 child workflow를 parent workflow 안에서 하나의 executor처럼 동작하게 한다. 공식 docstring이 이 타입의 성격을 거의 명세 수준으로 설명한다.  
  - output forwarding
  - request/response coordination
  - failure propagation
  - child workflow checkpoint embedding  
  이 모두가 이 타입의 계약이다. ([WorkflowExecutor overview](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_workflow_executor.py#L106-L246))
- `WorkflowExecutor`의 입력 타입은 child workflow start executor input types에 `SubWorkflowResponseMessage`를 추가한 것이고, output types는 child workflow output types에 필요시 `SubWorkflowRequestMessage`를 추가한 것이다. 즉 type 경계가 child workflow signature를 거의 그대로 상속한다. ([input/output type contract in doc](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_workflow_executor.py#L171-L191), [input_types impl](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_workflow_executor.py#L289-L302), [output_types impl](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_workflow_executor.py#L304-L320))
- `process_workflow(...)`는 child workflow를 새 input으로 실행한다. 이때 parent workflow state의 `WORKFLOW_RUN_KWARGS_KEY`를 읽어 function/client kwargs를 child workflow로 전달하고, child workflow가 아직 pending requests를 가진 상태에서 새 input을 받으면 warning을 남긴다. child result는 `_process_workflow_result(...)`로 정리된다. ([process_workflow start path](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_workflow_executor.py#L348-L405))
- `_process_workflow_result(...)`는 composition 경계를 명확히 만든다.
  - `allow_direct_output=False`면 child outputs를 parent messages로 보낸다.
  - `allow_direct_output=True`면 child outputs를 parent workflow output으로 직접 yield한다.
  - child intermediate outputs는 parent의 own output classifier를 우회해 `type='intermediate'` 이벤트로 재방출한다.
  - child `request_info`는 `_propagate_request=True`면 parent external request로, 아니면 `SubWorkflowRequestMessage`로 wrapping한다.
  - child failed state는 parent error event로 승격한다.  
  ([process result boundaries](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_workflow_executor.py#L498-L589))
- `_handle_response(...)`는 child workflow pending request map을 source-of-truth로 사용한다. unknown/already-handled request id는 warning 후 무시하고, known request면 child workflow에 `responses={request_id: response}`로 재진입한다. ([handle response source-of-truth](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_workflow_executor.py#L591-L610))
- checkpoint는 `WorkflowExecutor` 자신이 별도 pending-request bookkeeping을 들지 않고, child workflow의 in-memory checkpoint object를 그대로 parent executor state에 저장하는 방식이다. restore도 child checkpoint를 그대로 child runner에 되돌린다. legacy checkpoint fallback은 pending request 재발행만 지원한다. ([checkpoint embed](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_workflow_executor.py#L451-L470), [legacy fallback](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_workflow_executor.py#L472-L497))

### 4. Python workflow-as-agent: Workflow.as_agent()

- graph workflow의 agent adapter entrypoint는 `Workflow.as_agent(...)`다. docstring은 이 adapter가 string/`Message`/list input을 `list[Message]`로 normalize해 workflow start executor로 넘긴다고 밝히며, start executor가 `list[Message]`를 받을 수 없으면 실패한다고 명시한다. ([Workflow.as_agent contract](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_workflow.py#L1193-L1234))
- intermediate workflow events가 agent adapter를 통과할 때 어떻게 전달되는지는 tests가 pin한다. intermediate-designated executor의 output은 `AgentResponseUpdate`로 전달되고, hidden yield는 surfacing되지 않으며, `Message.additional_properties`도 intermediate forwarding 경로에서 유지된다. ([intermediate forwarding test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/workflow/test_workflow_agent_intermediate.py#L32-L67), [hidden yields non-streaming](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/workflow/test_workflow_agent_intermediate.py#L99-L120), [hidden yields streaming](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/workflow/test_workflow_agent_intermediate.py#L122-L145), [additional_properties preserved](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/workflow/test_workflow_agent_intermediate.py#L170-L206))

### 5. Python functional workflow API

- functional workflow는 graph compilation 없이 plain async function을 workflow로 쓰게 하는 experimental API다. `@workflow`가 async function을 `FunctionalWorkflow`로 감싸고, `@step`이 optional tracked step wrapper를 제공한다. `RunContext`는 HITL, custom events, key/value state를 제공한다. ([functional workflow overview](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_functional.py#L10-L31), [RunContext overview](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_functional.py#L107-L153), [@workflow decorator](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_functional.py#L1260-L1323))
- functional composition의 기본 재실행 경계는 `StepWrapper`다.  
  - step cache key는 `(step_name, call_index)`다.
  - cache hit 시 live execution pair(`executor_invoked`/`executor_completed`) 대신 `executor_bypassed`만 낸다.
  - `RunContext` parameter가 있으면 자동 주입한다.
  - live step completion 후 configured storage가 있으면 per-step checkpoint를 저장할 수 있다.  
  ([StepWrapper semantics](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_functional.py#L411-L552))
- `RunContext.request_info(...)`는 initial execution에서는 내부 `WorkflowInterrupted`를 던져 workflow를 suspend하고, replay에서 caller가 준 response를 직접 반환한다. `request_id`를 생략하면 deterministic `auto::<index>` id를 만든다. 또한 user state는 simple dict이지만 leading underscore key는 framework bookkeeping 예약 영역이라 금지된다. ([WorkflowInterrupted BaseException](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_functional.py#L87-L99), [RunContext.request_info](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_functional.py#L193-L250), [RunContext state API](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_functional.py#L263-L302))
- `FunctionalWorkflow`는 workflow name, discovered step names, function bytecode/co_names digest를 섞어 `graph_signature_hash`를 계산한다. restore 시 checkpoint의 hash가 다르면 incompatible workflow로 실패한다. ([FunctionalWorkflow init and graph_signature_hash](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_functional.py#L671-L699), [signature hash implementation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_functional.py#L1162-L1182), [checkpoint signature mismatch restore](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_functional.py#L930-L944))
- functional workflow restore 시 state 경계는 다음과 같다.
  - checkpoint restore면 `_step_cache`, `_step_cache_auto_request_info_counts`, user state, pending request_info events, `_original_message`를 복원한다.
  - responses-only replay면 `_last_message`와 last step cache를 메모리에서 가져온다.
  - live execution/interrupt/final completion 시 `_save_checkpoint(...)`가 step cache와 `_original_message`까지 checkpoint state에 포함한다.  
  ([restore state on checkpoint](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_functional.py#L925-L980), [live execution and interrupt checkpoints](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_functional.py#L999-L1067), [save_checkpoint payload](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_functional.py#L1142-L1160))
- functional workflow streaming은 진정한 per-token streaming이 아니다. code 주석은 `_execute()` 중 발생한 events를 버퍼링했다가 user function이 끝난 뒤 한꺼번에 yield한다고 명시한다. 즉 graph workflow의 event-as-produced streaming과 경계가 다르다. ([not true streaming note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_functional.py#L1011-L1016))
- `FunctionalWorkflowAgent`는 functional workflow를 agent-compatible surface로 감싼다. `request_info` events는 `function_approval_request` content로 바뀌어 agent caller에게 노출되고, `pending_requests` map에 보관된다. non-streaming에서는 final `AgentResponse`로, streaming에서는 `ResponseStream[AgentResponseUpdate, AgentResponse]`로 변환한다. ([FunctionalWorkflowAgent overview](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_functional.py#L1331-L1354), [FunctionalWorkflowAgent.run](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_functional.py#L1382-L1450), [streaming request_info to approval request](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_functional.py#L1492-L1544), [non-streaming result to AgentResponse](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_functional.py#L1545-L1572))

## 상태·영속화

### .NET

- subworkflow 경계에서 ownership은 `SubworkflowBinding`이 선점한다. 따라서 동일 child workflow를 여러 parent/subworkflow가 동시에 공유하는 직접적 graph misuse를 구조적으로 막는다. ([SubworkflowBinding ownership token](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/SubworkflowBinding.cs#L23-L35))
- `WorkflowHostExecutor`의 persistent state는 크게 두 부분이다.
  1. child 전용 `InMemoryCheckpointManager`
  2. `_pendingResponsePorts`  
  restore 시 checkpoint manager instance가 달라지면 기존 child run stack을 `ResetAsync()`로 정리하고 다시 resume한다. ([host executor checkpoint state](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/Specialized/WorkflowHostExecutor.cs#L256-L294), [reset on manager change](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/Specialized/WorkflowHostExecutor.cs#L296-L321))
- `WorkflowSession`은 workflow-as-agent 세션 경계다. serialized `SessionState`에는 `SessionId`, `LastCheckpoint`, optional `CheckpointManager`, `StateBag`, `PendingRequests`가 들어간다. 즉 agent session serialization이 workflow continuation state를 흡수한다. ([session ctor and pending requests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/WorkflowSession.cs#L70-L96), [deserialize ctor](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/WorkflowSession.cs#L98-L130), [SessionState payload](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/WorkflowSession.cs#L755-L767))

### Python

- graph-based `WorkflowExecutor`는 child checkpoint object를 parent executor state에 직접 embed한다. 이 방식은 child storage backend가 없어도 parent checkpoint 하나만으로 child shared state, messages, pending request_info events를 함께 복원할 수 있게 한다. ([sub_workflow_checkpoint save](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_workflow_executor.py#L451-L461), [sub_workflow_checkpoint restore](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_workflow_executor.py#L464-L470))
- functional workflow는 graph처럼 external runner context가 아니라 in-memory replay state를 가진다. `_last_message`, `_last_step_cache`, `_last_step_cache_auto_request_info_counts`, `_last_pending_request_ids`가 responses-only replay의 상태다. checkpoint storage가 있으면 `_save_checkpoint(...)`가 이를 external checkpoint로도 내보낸다. ([FunctionalWorkflow replay state fields](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_functional.py#L682-L699), [checkpoint save payload](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_functional.py#L1142-L1160))
- functional `RunContext` user state는 underscore-prefixed keys를 금지한다. framework bookkeeping(`_step_cache`, `_original_message`)와 같은 checkpoint payload 내부 키와 충돌하지 않게 하려는 설계다. ([underscore-prefixed user state forbidden](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_functional.py#L278-L302))

## 확장점

- .NET composition 확장점:
  - public `AsAIAgent(...)` 옵션으로 exception detail 포함 여부, workflow outputs 포함 여부, execution environment 선택
  - `SubworkflowBinding`을 통해 arbitrary child workflow를 executor binding으로 삽입
  - `WorkflowHostExecutor` options로 child outputs를 parent internal message로 보낼지, parent external output으로 yield할지 선택  
  ([AsAIAgent options](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/WorkflowHostingExtensions.cs#L13-L38), [WorkflowHostExecutor output forwarding options](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/Specialized/WorkflowHostExecutor.cs#L209-L223))
- Python graph composition 확장점:
  - `WorkflowExecutor(allow_direct_output, propagate_request)`
  - parent workflow가 `SubWorkflowRequestMessage`를 intercept해서 local handling 또는 external forwarding 선택
  - `Workflow.as_agent(description/context_providers/kwargs)` 표면  
  ([WorkflowExecutor ctor options](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_workflow_executor.py#L248-L279), [parent intercept pattern in doc](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_workflow_executor.py#L214-L233), [Workflow.as_agent args](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_workflow.py#L1193-L1234))
- Python functional composition 확장점:
  - `@workflow(name=..., description=..., checkpoint_storage=...)`
  - `@step(name=...)`
  - step 내부/외부 `RunContext` injection
  - `FunctionalWorkflow.as_agent(...)` agent adapter  
  ([workflow decorator options](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_functional.py#L1265-L1323), [step decorator options](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_functional.py#L560-L628), [FunctionalWorkflowAgent ctor](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_functional.py#L1358-L1376))

## 동시성·스트리밍·취소

### 동시성 경계

- .NET child workflow는 parent `WorkflowHostExecutor` 안에서 `InProcessRunner.CreateSubworkflowRunner(...)`로 만들어지고 join context에 attach된다. same host executor에 active run이 있으면 새 input은 existing run으로 enqueue된다. 따라서 child workflow concurrency는 parent host executor 경계에서 serialize된다. ([EnsureRunnerAsync](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/Specialized/WorkflowHostExecutor.cs#L81-L102), [EnsureRunSendMessageAsync reuse existing run](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/Specialized/WorkflowHostExecutor.cs#L104-L116))
- Python `WorkflowExecutor`는 single shared child workflow instance를 유지하므로, pending request가 남은 상태에서 새 input이 오면 warning을 남긴다. 이 overlap은 허용되지만 child workflow와 executors가 stateless일 때만 안전하다고 docstring이 경고한다. ([overlapping execution warning in docs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_workflow_executor.py#L198-L205), [warning in process_workflow](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_workflow_executor.py#L359-L371))
- Python functional workflow는 whole-workflow concurrency guard를 가진다. `_is_running`이 true면 second run은 `RuntimeError`로 거부된다. test도 이를 pin한다. ([FunctionalWorkflow _ensure_not_running](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_functional.py#L1246-L1254), [concurrent run test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/workflow/test_functional_workflow.py#L857-L872))

### 스트리밍 경계

- .NET `WorkflowSession.InvokeStageAsync(...)`는 child workflow의 raw `StreamingRun` stream을 agent-facing `AgentResponseUpdate` stream으로 변환한다. 이 과정에서 이미 stream으로 보낸 `AgentResponseUpdateEvent`와 completion `AgentResponseEvent` 사이의 중복 message id를 suppress한다. 즉 streaming 경계에서 “raw workflow events → de-duplicated agent updates” 재형식화가 일어난다. ([InvokeStageAsync agent update streaming](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/WorkflowSession.cs#L482-L505), [streamed message dedup in AgentResponseEvent](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/WorkflowSession.cs#L552-L590))
- Python graph `Workflow.as_agent()`는 intermediate outputs도 agent updates로 forwarding할 수 있고, hidden yields는 forwarding하지 않는다. 즉 graph output designation이 agent streaming boundary에 직접 영향을 준다. tests가 이 계약을 고정한다. ([intermediate forwarded](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/workflow/test_workflow_agent_intermediate.py#L32-L67), [hidden yields hidden in streaming](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/workflow/test_workflow_agent_intermediate.py#L122-L145))
- Python functional workflow는 “streaming”이지만 true live streaming이 아니다. step/workflow events는 execution 중 buffer에 쌓였다가 workflow function completion 후 replay된다. 반면 `FunctionalWorkflowAgent.run(stream=True)`는 이 buffered workflow events를 다시 `AgentResponseUpdate` stream으로 번역한다. ([functional streaming not true streaming](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_functional.py#L1011-L1016), [FunctionalWorkflowAgent streaming adapter](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_functional.py#L1492-L1520))

### 취소 경계

- inspected `.NET` composition code에서 child run은 `WorkflowHostExecutor.ResetAsync()` / `DisposeAsync()`로 정리된다. active streaming run을 dispose하고, active child runner event subscription을 끊고, join context에서 detach한다. 이 경계는 parent가 child lifecycle을 소유함을 뜻한다. ([WorkflowHostExecutor reset/dispose](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/Specialized/WorkflowHostExecutor.cs#L296-L321))
- Python functional workflow의 HITL interruption은 `WorkflowInterrupted`가 `BaseException`을 상속하기 때문에 user workflow 내부 `except Exception:` 블록이 composition control signal을 삼키지 못한다. 이는 취소/중단과 비슷한 non-local control boundary를 안전하게 보장한다. test도 이를 pin한다. ([WorkflowInterrupted BaseException](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_functional.py#L87-L99), [except Exception should not catch interruption](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/workflow/test_functional_workflow.py#L1034-L1053))

## 오류·검증·보안

- .NET workflow-as-agent는 error 노출을 경계에서 다시 제한한다. `WorkflowSession.InvokeStageAsync(...)`는 `WorkflowErrorEvent`/`ExecutorFailedEvent`를 `ErrorContent`로 바꾸되, `includeExceptionDetails=false`면 generic message만 surface하고 internal graph identifiers는 숨긴다. ([workflow error mapping](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/WorkflowSession.cs#L508-L545))
- .NET `WorkflowHostExecutor`는 child workflow internal lifecycle events를 부모에게 그대로 노출하지 않는다. error는 `SubworkflowErrorEvent`, warning은 `SubworkflowWarningEvent`로 wrapping한다. 이것이 observability 경계이자 정보 은닉 경계다. ([subworkflow event filtering/wrapping](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/Specialized/WorkflowHostExecutor.cs#L188-L249))
- .NET workflow-as-agent checkpoint identity는 inner agent ids/names가 변하면 resume가 실패한다. test는 stable inner ids로는 reconstruction 후 resume가 가능하지만, random ids 또는 renamed inner agents는 `InvalidDataException`으로 실패함을 고정한다. ([stable inner ids resume](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Workflows.UnitTests/WorkflowAgentCheckpointIdentityTests.cs#L31-L59), [random inner ids fail](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Workflows.UnitTests/WorkflowAgentCheckpointIdentityTests.cs#L61-L87), [stable ids but changed names fail](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Workflows.UnitTests/WorkflowAgentCheckpointIdentityTests.cs#L89-L112))
- Python `WorkflowExecutor`는 unknown/already-handled request id response를 warning 후 무시한다. 즉 child workflow pending request map이 유일한 진실 원천이다. ([unknown response ignored](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_workflow_executor.py#L597-L610))
- Python functional workflow는 checkpoint signature mismatch 시 restore를 거부한다. hash는 workflow name + discovered steps + code digest를 섞어 계산된다. ([signature mismatch error](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_functional.py#L930-L944), [signature hash computation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_functional.py#L1162-L1182))
- Python functional step cache import는 malformed cache key/index를 corruption으로 간주하고 실패한다. 이는 incompatible or corrupted checkpoint 방어다. ([corrupted step cache import checks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_functional.py#L377-L403), [tests for malformed cache import](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/workflow/test_functional_workflow.py#L1061-L1091))

## .NET 구현과 테스트

### 구현

- `SubworkflowBinding.cs` — public child-workflow binding entry, ownership token capture  
  <https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/SubworkflowBinding.cs#L17-L46>
- `Specialized/WorkflowHostExecutor.cs` — child workflow runtime proxy, event/request/output forwarding, child checkpoint restore  
  <https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/Specialized/WorkflowHostExecutor.cs#L17-L321>
- `WorkflowHostingExtensions.cs` — public workflow-as-agent extension  
  <https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/WorkflowHostingExtensions.cs#L8-L39>
- `WorkflowHostAgent.cs` — workflow agent wrapper, final response merge policy  
  <https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/WorkflowHostAgent.cs#L16-L196>
- `WorkflowSession.cs` — workflow-as-agent session, pending request map, request/response content translation, checkpoint-in-session serialization  
  <https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/WorkflowSession.cs#L19-L768>

### 테스트

- `WorkflowAgentCheckpointIdentityTests.cs` — inner agent identity stability가 workflow-as-agent checkpoint resume 호환성의 핵심임을 검증  
  <https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Workflows.UnitTests/WorkflowAgentCheckpointIdentityTests.cs#L16-L168>
- `GroupChatOrchestrationTests.cs` — composed workflow(그룹챗)에서 approval checkpoint/resume이 실제로 동작함을 end-to-end로 검증  
  <https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Workflows.UnitTests/GroupChatOrchestrationTests.cs#L28-L183>

## Python 구현과 테스트

### 구현

- `python/packages/core/agent_framework/_workflows/_workflow_executor.py` — graph subworkflow executor, direct-output/request propagation modes, child checkpoint embedding  
  <https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_workflow_executor.py#L106-L610>
- `python/packages/core/agent_framework/_workflows/_workflow.py` — graph workflow `as_agent()` public adapter  
  <https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_workflow.py#L1193-L1234>
- `python/packages/core/agent_framework/_workflows/_functional.py` — experimental functional workflow core, step cache, HITL replay, checkpointed code-shape signature, agent adapter  
  <https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_functional.py#L1-L1572>

### 테스트

- `python/packages/core/tests/workflow/test_sub_workflow.py` — graph subworkflow request interception, external forwarding, multiple child workflows, concurrent child invocations  
  <https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/workflow/test_sub_workflow.py#L179-L420>
- `python/packages/core/tests/workflow/test_workflow_agent.py` — graph workflow-as-agent end-to-end basic behavior  
  <https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/workflow/test_workflow_agent.py#L162-L220>
- `python/packages/core/tests/workflow/test_workflow_agent_intermediate.py` — graph workflow-as-agent intermediate/hidden output forwarding boundary  
  <https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/workflow/test_workflow_agent_intermediate.py#L32-L220>
- `python/packages/core/tests/workflow/test_functional_workflow.py` — functional workflow basic execution, HITL, checkpointing, `executor_bypassed`, `as_agent`, request_info-to-approval translation, signature mismatch  
  <https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/workflow/test_functional_workflow.py#L70-L1758>

## .NET/Python 차이

- **public composition model**: .NET은 explicit public `AsAIAgent(...)` extension + public `SubworkflowBinding`을 제공하고, 실제 runtime proxy는 internal `WorkflowHostExecutor`/`WorkflowSession`이 맡는다. Python graph composition은 public `WorkflowExecutor`와 `Workflow.as_agent()`가 더 직접적이다. ([.NET AsAIAgent](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/WorkflowHostingExtensions.cs#L13-L38), [.NET SubworkflowBinding](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/SubworkflowBinding.cs#L17-L46), [Python WorkflowExecutor](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_workflow_executor.py#L248-L279), [Python Workflow.as_agent](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_workflow.py#L1193-L1234))
- **session boundary**: .NET workflow-as-agent는 explicit `WorkflowSession` object가 checkpoint manager, last checkpoint, pending requests를 포함해 session serialization 경계를 만든다. Python graph workflow-as-agent는 inspected 범위상 별도 workflow session class보다 adapter/underlying workflow instance semantics에 더 의존한다. functional workflow는 또 다른 in-memory replay state를 가진다. ([.NET WorkflowSession state](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/WorkflowSession.cs#L19-L52), [functional replay state](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_functional.py#L682-L699))
- **subworkflow checkpointing**: .NET child workflow는 dedicated in-memory checkpoint manager를 parent host executor state 안에 저장하고, Python `WorkflowExecutor`는 child `WorkflowCheckpoint` object 자체를 parent executor checkpoint state에 embed한다. ([.NET host executor checkpoint manager state](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/Specialized/WorkflowHostExecutor.cs#L256-L294), [Python sub_workflow_checkpoint](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_workflow_executor.py#L451-L470))
- **streaming boundary**: .NET workflow-as-agent는 child workflow events를 live `AgentResponseUpdate`로 변환하면서 duplicate streamed messages를 제거한다. Python functional workflow는 event buffering 후 replay라 “true streaming”이 아니다. ([.NET live translation and dedup](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/WorkflowSession.cs#L482-L590), [functional not true streaming](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_functional.py#L1011-L1016))
- **functional composition**: Python에는 experimental `@workflow`/`@step` functional API가 있지만, inspected `.NET Microsoft.Agents.AI.Workflows*` 범위에서는 composition이 graph/subworkflow/agent-wrapper 형태로 구현된다. Python functional API는 code-shape hash와 step cache까지 composition 경계의 일부로 취급한다. ([Python functional API](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_functional.py#L1-L31), [FunctionalWorkflow signature hash](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_functional.py#L1162-L1182))

## 문서 차이

- Python graph `WorkflowExecutor` docstring은 구현 책임을 상당히 자세히 설명하고, 실제 code도 거의 그 계약을 그대로 따른다. 반면 .NET 쪽은 public `AsAIAgent(...)`만 보면 composition 경계의 실제 behavior가 잘 보이지 않고, `WorkflowHostExecutor`/`WorkflowSession` 내부 구현을 읽어야 pending request translation, terminal output merge, error masking 규칙이 드러난다. 따라서 composition은 .NET에서 **public docs보다 source implementation 의존도**가 높다. ([.NET AsAIAgent surface only](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/WorkflowHostingExtensions.cs#L13-L38), [.NET WorkflowSession actual boundary logic](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/WorkflowSession.cs#L443-L647), [Python WorkflowExecutor doc + code](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_workflow_executor.py#L106-L246))
- Python functional workflow 문서는 “streaming mode”를 지원한다고 하지만, 코드 주석은 현재 buffered-after-completion replay임을 분명히 말한다. 따라서 code-first로 해석하면 functional workflow streaming은 graph workflow streaming과 동일한 의미가 아니다. ([functional run doc](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_functional.py#L772-L805), [not true streaming note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_functional.py#L1011-L1016))

## Java 결정

- Java composition은 **graph workflow composition**과 **functional composition**을 분리된 API family로 설계하는 것이 좋다. Python처럼 둘을 한 패키지에 넣을 수는 있지만, 안정도(stable vs experimental)를 명확히 나눠야 한다. graph-based `WorkflowExecutor`/`Workflow.asAgent()`는 stable core로, functional workflow DSL은 preview/experimental로 시작하는 편이 안전하다. ([Python functional experimental](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_functional.py#L3-L9), [Python core graph as_agent stable package](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/pyproject.toml#L13-L24))
- Java subworkflow composition은 `.NET`처럼 **ownership token + host executor** 모델을 가져가는 것이 안전하다. child workflow instance를 아무 executor처럼 공유 가능하게 두면 lifecycle, checkpoint, overlapping request boundaries가 흐려진다. ([.NET SubworkflowBinding ownership](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/SubworkflowBinding.cs#L23-L35), [.NET WorkflowHostExecutor reset/lifecycle ownership](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/Specialized/WorkflowHostExecutor.cs#L296-L321))
- Java workflow-as-agent는 **session object에 checkpoint pointer + pending request map + option flags**를 같이 저장해야 한다. .NET `WorkflowSession`처럼 agent-session serialization이 workflow continuation state를 품게 해야 reconstruction/resume이 가능하다. ([.NET WorkflowSession fields](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/WorkflowSession.cs#L19-L52), [SessionState payload](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/WorkflowSession.cs#L755-L767))
- Java functional workflow가 도입된다면, Python functional API의 세 가지 설계를 그대로 참고할 가치가 있다.
  1. `WorkflowInterrupted extends BaseException`에 해당하는 non-local control signal
  2. `(step_name, call_index)` 기반 deterministic step cache
  3. code-shape hash 기반 checkpoint compatibility  
  이 셋이 없으면 step replay/HITL/restore가 불안정해진다. ([WorkflowInterrupted](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_functional.py#L87-L99), [StepWrapper cache key/bypass](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_functional.py#L512-L549), [signature hash](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_functional.py#L1162-L1182))
- Java agent adapter는 graph/functional 양쪽 모두에 대해 **request_info를 agent-surface approval content로 변환하는 공통 adapter contract**를 갖는 것이 좋다. Python functional agent가 이미 이 모델을 보여 준다. ([FunctionalWorkflowAgent request_info to approval content](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_functional.py#L1522-L1543))

## 구체 acceptance scenarios

1. **child workflow를 executor처럼 삽입하면 parent는 child output을 메시지 또는 output으로 선택적으로 받을 수 있어야 한다.**  
   Python `allow_direct_output=False`면 message forwarding, `True`면 direct yield다. .NET은 host executor option 조합이 같은 역할을 한다. ([Python direct output switch](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_workflow_executor.py#L524-L529), [.NET output forwarding options](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/Specialized/WorkflowHostExecutor.cs#L209-L223))

2. **child workflow request_info는 parent interception 또는 external propagation 중 하나로 재형식화되어야 한다.**  
   Python `propagate_request=False`면 `SubWorkflowRequestMessage`, `True`면 parent `request_info()`다. ([Python request propagation switch](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_workflow_executor.py#L544-L557))

3. **child workflow intermediate outputs는 parent output designation과 독립적으로 intermediate로 유지되어야 한다.**  
   Python은 `WorkflowExecutor`가 parent classifier를 우회해 `type='intermediate'`로 재발행한다. ([intermediate propagation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_workflow_executor.py#L531-L542))

4. **workflow-as-agent는 hidden yields를 최종 agent response에 넣지 않아야 한다.**  
   graph workflow agent adapter tests가 hidden yield suppression을 고정한다. ([hidden yields non-streaming](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/workflow/test_workflow_agent_intermediate.py#L99-L120), [hidden yields streaming](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/workflow/test_workflow_agent_intermediate.py#L122-L145))

5. **workflow-as-agent checkpoint resume는 inner agent identity가 안정적일 때만 성공해야 한다.**  
   .NET tests는 stable inner ids에서는 resume 성공, random ids 또는 renamed inner names에서는 실패를 고정한다. ([stable ids resume](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Workflows.UnitTests/WorkflowAgentCheckpointIdentityTests.cs#L31-L59), [random ids fail](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Workflows.UnitTests/WorkflowAgentCheckpointIdentityTests.cs#L61-L87), [renamed names fail](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Workflows.UnitTests/WorkflowAgentCheckpointIdentityTests.cs#L89-L112))

6. **functional workflow의 checkpoint restore는 completed step을 `executor_bypassed`로 replay하고 재실행하지 않아야 한다.**  
   ([executor_bypassed on replay](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/workflow/test_functional_workflow.py#L1099-L1135))

7. **functional workflow의 per-step checkpoint는 crash recovery를 가능하게 해야 한다.**  
   앞 단계 step은 cache replay로 건너뛰고, crash 났던 다음 step만 재실행되어야 한다. ([per-step checkpoint crash recovery](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/workflow/test_functional_workflow.py#L629-L672))

8. **functional workflow 내부 `except Exception:`은 HITL interrupt를 잡아서는 안 된다.**  
   `WorkflowInterrupted`는 `BaseException`이므로 pending request 상태로 빠져야 한다. ([WorkflowInterrupted contract](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_functional.py#L87-L99), [test_except_exception_does_not_catch_interrupt](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/workflow/test_functional_workflow.py#L1034-L1053))

9. **functional workflow agent adapter는 request_info를 function approval request content로 surface해야 한다.**  
   caller는 `pending_requests`에서 request id를 확인하고, agent response messages 안에서 `function_approval_request` content를 찾아야 한다. ([FunctionalWorkflowAgent request_info conversion](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_functional.py#L1522-L1543), [agent HITL test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/workflow/test_functional_workflow.py#L1661-L1681))

10. **subworkflow request interception은 workflow별로 scope를 달리할 수 있어야 한다.**  
    Python tests는 `workflow_a`와 `workflow_b`가 같은 child definition을 써도 parent interception rule을 workflow별로 다르게 적용하는 시나리오를 고정한다. ([workflow-scoped interception](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/workflow/test_sub_workflow.py#L268-L361))

## source inventory

### .NET 구현

- `dotnet/src/Microsoft.Agents.AI.Workflows/SubworkflowBinding.cs` — public subworkflow binding, child workflow ownership token 확보  
  <https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/SubworkflowBinding.cs#L17-L46>
- `dotnet/src/Microsoft.Agents.AI.Workflows/WorkflowHostingExtensions.cs` — public `AsAIAgent(...)` extension  
  <https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/WorkflowHostingExtensions.cs#L8-L39>
- `dotnet/src/Microsoft.Agents.AI.Workflows/Specialized/WorkflowHostExecutor.cs` — child workflow runtime proxy, request/output/error forwarding, child checkpoint manager restore  
  <https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/Specialized/WorkflowHostExecutor.cs#L17-L321>
- `dotnet/src/Microsoft.Agents.AI.Workflows/WorkflowHostAgent.cs` — workflow-as-agent wrapper  
  <https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/WorkflowHostAgent.cs#L16-L196>
- `dotnet/src/Microsoft.Agents.AI.Workflows/WorkflowSession.cs` — workflow-as-agent session boundary, request translation, response dedup, session serialization  
  <https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/WorkflowSession.cs#L19-L768>

### .NET 테스트

- `dotnet/tests/Microsoft.Agents.AI.Workflows.UnitTests/WorkflowAgentCheckpointIdentityTests.cs`  
  <https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Workflows.UnitTests/WorkflowAgentCheckpointIdentityTests.cs#L16-L168>
- `dotnet/tests/Microsoft.Agents.AI.Workflows.UnitTests/GroupChatOrchestrationTests.cs` — composed workflow approval checkpoint/resume 경계의 end-to-end 검증  
  <https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Workflows.UnitTests/GroupChatOrchestrationTests.cs#L28-L183>

### Python 구현

- `python/packages/core/agent_framework/_workflows/_workflow.py` — `Workflow.as_agent()` public surface  
  <https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_workflow.py#L1193-L1234>
- `python/packages/core/agent_framework/_workflows/_workflow_executor.py` — graph subworkflow composition core  
  <https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_workflow_executor.py#L106-L610>
- `python/packages/core/agent_framework/_workflows/_functional.py` — experimental functional workflow, step cache, HITL replay, functional agent adapter  
  <https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_workflows/_functional.py#L1-L1572>

### Python 테스트

- `python/packages/core/tests/workflow/test_sub_workflow.py` — graph subworkflow interception/forwarding/concurrent composition  
  <https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/workflow/test_sub_workflow.py#L179-L420>
- `python/packages/core/tests/workflow/test_workflow_agent.py` — graph workflow-as-agent 기본 end-to-end  
  <https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/workflow/test_workflow_agent.py#L162-L220>
- `python/packages/core/tests/workflow/test_workflow_agent_intermediate.py` — graph workflow-as-agent intermediate/hidden forwarding 경계  
  <https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/workflow/test_workflow_agent_intermediate.py#L32-L220>
- `python/packages/core/tests/workflow/test_functional_workflow.py` — functional composition, checkpoint, HITL, as_agent, signature mismatch, executor_bypassed  
  <https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/workflow/test_functional_workflow.py#L70-L1758>

### 메타데이터

- `.NET` workflows package metadata  
  <https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/Microsoft.Agents.AI.Workflows.csproj#L3-L23>
- `Python` core package metadata  
  <https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/pyproject.toml#L13-L24>