# 12. Harness

## 1. 문서 목적과 범위

이 문서는 Microsoft Agent Framework의 harness 관점 기능만 다룬다. 구체적으로는 **harness composition**, **automatic loop policy**, **todo provider**, **mode provider**, **file memory**, **file access**, **structured memory**, **tool approval**, **invocation budget**를 다룬다. Python에서는 상위 진입점이 `create_harness_agent(...)`이고, .NET에서는 `HarnessAgent`/`AsHarnessAgent(...)`가 같은 역할을 한다.  
([Python 조립 진입점](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_agent.py#L302-L345), [Python 실제 조립](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_agent.py#L563-L683), [.NET 확장 진입점](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/ChatClientHarnessExtensions.cs#L9-L39), [.NET harness 개요](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs#L21-L75))

다음 영역은 **이 문서에서 상세를 중복하지 않고 경계만 기술**한다.

- `compaction`: harness가 어디에 compaction을 배선하는지만 설명하고, 알고리즘/전략 상세는 별도 문서로 분리한다.  
  ([Python compaction 배선 경계](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_agent.py#L82-L142), [.NET compaction 배선 경계](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs#L166-L197))
- `skills`: harness가 skills provider를 언제 붙이는지만 설명하고, skill source/progressive loading/script 실행 상세는 별도 문서로 분리한다.  
  ([Python skills opt-in 배선](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_agent.py#L200-L205), [.NET skills 기본 배선](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs#L320-L326))
- `background-tasks`: loop가 background-task completion을 조건으로 삼을 수 있다는 연결점만 남기고, background agent/task 자체 수명주기는 별도 문서로 분리한다.  
  ([Python loop helper 경계](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_loop.py#L796-L860), [.NET background completion evaluator 경계](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Loop/BackgroundTaskCompletionLoopEvaluator.cs#L15-L35))
- `code-execution`: harness 본체가 code execution/runtime를 구현하지는 않으며, Python은 optional shell/codeact wiring을, .NET은 별도 package 수동 배선을 제공한다.  
  ([Python shell wiring 경계](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_agent.py#L221-L262), [.NET harness provider 목록에 shell/codeact 자동 배선 없음](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs#L287-L344), [.NET shell 수동 샘플](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/samples/02-agents/Agents/Agent_Step21_ShellWithEnvironment/Program.cs#L57-L119))

또한 **Agent core/tool 기본 계약**, **workflow 엔진 내부**, **hosting 프레임워크 내부**는 이 문서의 직접 범위가 아니다. 다만 harness가 그 경계에 어떻게 접속하는지는 설명한다.  
([Python Agent 조립 seam](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_agent.py#L664-L683), [.NET ChatClientAgent seam](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs#L249-L265))

---

## 2. 상태와 스냅샷의 공통 모델

Harness 기능들은 대부분 **`AgentSession` / session state**를 공통 저장소로 사용한다. Python은 `create_harness_agent(...)`가 `require_per_service_call_history_persistence=True`를 강제하고, .NET도 `RequirePerServiceCallChatHistoryPersistence = true`를 강제한다. 즉 harness는 “turn 단위”보다 더 세밀한 **service-call 단위 이력 보존**을 전제로 설계되어 있다.  
([Python agent construction](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_agent.py#L664-L677), [.NET ChatClientAgentOptions](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs#L249-L265))

이 공통 모델 위에 각 기능은 서로 다른 종류의 state를 얹는다.

- **Todo / mode / tool approval / background tasks**: session state에 직접 serializable state를 둔다.  
  ([Python mode state](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_mode.py#L76-L87), [Python todo state](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_todo.py#L245-L285), [Python approval state](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_tool_approval.py#L158-L215), [Python background state](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_background_agents.py#L163-L186), [.NET todo state 설명](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Todo/TodoProvider.cs#L21-L24), [.NET mode state 설명](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/AgentMode/AgentModeProvider.cs#L20-L23))
- **File memory / structured memory**: session state로 routing key 또는 working folder를 정하고, 실제 payload는 file store에 둔다.  
  ([Python file memory scope](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_file_memory.py#L234-L279), [Python structured memory owner routing](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_memory.py#L700-L745), [.NET file memory state initializer in harness](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs#L301-L312))
- **Automatic loop**: session 전체를 snapshot/restore할 수 있는 경우, fresh-context mode에서 pre-loop snapshot을 보관하고 iteration 사이에 되돌린다.  
  ([Python fresh_context snapshot](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_loop.py#L421-L439), [Python restore logic](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_loop.py#L459-L474), [.NET fresh session snapshot](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Loop/LoopAgent.cs#L150-L159), [.NET snapshot caveat](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Loop/LoopAgentOptions.cs#L35-L53))

이 문서에서 반복되는 핵심 판단 기준은 다음 두 가지다.

1. **상태가 session state에 있나, file store에 있나**  
2. **state가 다음 turn까지 지속되나, 같은 run 안에서만 유효한 snapshot인가**

---

## 3. Harness composition

### 목적
Harness composition의 목적은 호출자가 low-level chat pipeline과 개별 provider wiring을 일일이 구성하지 않고도, 연구/코딩/분석형 agent에 필요한 기본 부속기능을 한 번에 조립하게 하는 것이다.  
([Python summary docstring](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_agent.py#L3-L9), [.NET summary remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs#L21-L25))

### 경계
Harness composition은 다음을 **직접 구현하지 않고 조립**한다.

- function invocation loop
- per-service-call history persistence
- message injection
- built-in context providers
- tool approval layer
- optional loop layer
- optional web search / shell / file access / background / skills wiring

즉 harness는 orchestration kernel이라기보다 **opinionated assembly layer**다.  
([Python assembled features list](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_agent.py#L347-L363), [.NET assembled pipeline list](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs#L27-L75))

### 성숙도
- **Python**: `create_harness_agent` 자체는 graduated이며 experimental metadata가 없다. 다만 `background_agents`, `file_access_store`, `loop_should_continue`, `shell_executor`를 쓰면 feature-level warning이 발생한다.  
  ([graduated test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_agent.py#L1299-L1318), [warning emission logic](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_agent.py#L541-L561))
- **.NET**: `HarnessAgent`는 stable top-level처럼 쓰이지만, compaction/loop/file-access/background agents 관련 option은 `[Experimental]` 표식을 갖는다.  
  ([compaction options experimental](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgentOptions.cs#L45-L95), [loop options experimental](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgentOptions.cs#L149-L176), [file access experimental](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgentOptions.cs#L252-L275), [background experimental](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgentOptions.cs#L359-L380))

### 공개 API·타입
- **Python**
  - `create_harness_agent`
  - `TodoProvider`, `AgentModeProvider`, `FileMemoryProvider`
  - `ToolApprovalMiddleware`
  - loop helper exports (`todos_remaining`, `background_tasks_running` 등)
  
  ([public exports](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/__init__.py#L558-L645))
- **.NET**
  - `ChatClientHarnessExtensions.AsHarnessAgent(...)`
  - `HarnessAgent`
  - `HarnessAgentOptions`
  
  ([AsHarnessAgent](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/ChatClientHarnessExtensions.cs#L9-L39), [HarnessAgentOptions](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgentOptions.cs#L11-L19))

### 상세 실행 흐름
#### Python
1. history provider를 정한다. 기본은 `InMemoryHistoryProvider()`다.  
2. compaction을 조립한다. before-phase는 agent `compaction_strategy`, after-phase는 provider hook로 붙인다.  
3. optional shell tool/provider를 만든다.  
4. built-in context providers를 순서대로 붙인다: history → compaction → todo → mode → file memory → optional file access → optional skills → optional background → optional shell environment → extra providers.  
5. tool set을 조립한다: optional web search, optional shell tool, user tools.  
6. middleware를 조립한다: default tool approval, optional loop(outermost), message injection, user middleware.  
7. 최종 `Agent(...)`에 `require_per_service_call_history_persistence=True`를 설정한다.  
([history/compaction/shell assembly](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_agent.py#L563-L584), [provider order](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_agent.py#L145-L218), [tool assembly](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_agent.py#L611-L629), [middleware order](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_agent.py#L636-L677))

#### .NET
1. inner `ChatClientAgent`용 compaction strategy를 계산한다.  
2. default chat history provider를 정하고, compaction이 있으면 in-memory reducer로 연결한다.  
3. harness instructions + agent instructions를 병합한다.  
4. context providers를 조립한다.  
5. chat client builder에 approval response binding, approval-not-required bypassing, function invocation, message injection, per-service-call history persistence, optional compaction, optional OTel을 올린다.  
6. outer agent decorators로 optional loop → optional tool approval → optional OTel을 감싼다.  
([compaction/history/options](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs#L164-L212), [chat client stack](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs#L215-L247), [outer decorators](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs#L133-L161), [provider assembly](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs#L287-L344))

### 상태·영속화
- Python harness의 built-in provider state는 대부분 session state에 유지되고, file memory/file access는 store로 빠진다.  
  ([provider assembly](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_agent.py#L145-L218))
- .NET harness도 provider별 session state를 사용하며, chat history는 per-service-call persistence 전제를 가진다.  
  ([ChatClientAgentOptions](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs#L249-L265))

### 확장점
- Python은 `history_provider`, `todo_provider`, `mode_provider`, `file_memory_store`, `file_access_store`, `skills_provider`, `background_agents`, `shell_executor`, `context_providers`, `middleware`, `default_options`를 override할 수 있다.  
  ([signature](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_agent.py#L302-L344))
- .NET은 `ChatHistoryProvider`, `AIContextProviders`, `LoopEvaluators`, `ToolApprovalAgentOptions`, `AgentSkillsSource`, `BackgroundAgents`, `FileAccessStore` 등을 override할 수 있다.  
  ([options surface](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgentOptions.cs#L130-L176), [skills/background/file access options](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgentOptions.cs#L252-L380))

### 동시성·스트리밍·취소
Harness composition 자체는 concurrency primitive를 직접 구현하기보다, 각 provider/middleware가 가진 concurrency 모델을 합성한다. 다만 **loop와 approval**은 둘 다 streaming path를 별도로 구현해, 메시지 스트림 중간에 injected message나 approval request를 표면화한다.  
([Python loop streaming](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_loop.py#L559-L655), [Python approval streaming](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_tool_approval.py#L435-L502), [.NET loop streaming](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Loop/LoopAgent.cs#L220-L320), [.NET approval streaming](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/ToolApproval/ToolApprovalAgent.cs#L187-L280))

### 오류·검증
- Python은 invalid context/output token 조합을 조립 시점에 거부한다.  
  ([validation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_agent.py#L530-L539))
- .NET도 constructor에서 invalid token 조합을 거부한다.  
  ([tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Harness.UnitTests/HarnessAgentTests.cs#L50-L76))

### 보안
Harness가 직접 sandbox를 만들지는 않지만, 다음 보안 결정들을 기본으로 강제한다.

- per-service-call persistence를 전제로 approval/loop/history correctness를 유지한다.  
- Python은 external skills/background agents/shell executor를 caller opt-in으로만 허용한다.  
- .NET은 approval response binding과 “approval not required bypassing”을 기본 on으로 하여 mixed approval batch의 일관성을 높인다.  
([Python security notes on skills/background/shell options](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_agent.py#L460-L489), [.NET approval binding defaults](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs#L218-L230))

### .NET 구현과 테스트
- 기본 built-in provider는 todo/mode/file memory/skills다.  
  ([provider list remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs#L36-L49), [actual assembly](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs#L287-L344))
- tests는 invalid options, identity propagation, instructions merge를 고정한다.  
  ([HarnessAgentTests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Harness.UnitTests/HarnessAgentTests.cs#L40-L259), [HarnessAgentOptionsTests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Harness.UnitTests/HarnessAgentOptionsTests.cs#L13-L127))

### Python 구현과 테스트
- 기본 built-in provider는 history/compaction/todo/mode/file memory이고 skills는 기본 off다.  
  ([assembly code](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_agent.py#L167-L218), [default provider test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_agent.py#L93-L110))
- tests는 file access opt-in, shell auto-wiring, loop outermost ordering, feature warning emission을 고정한다.  
  ([file access opt-in](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_agent.py#L148-L218), [shell wiring](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_agent.py#L905-L962), [loop ordering](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_agent.py#L1153-L1262), [feature warnings](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_agent.py#L1321-L1399))

### 문서 차이
가장 중요한 차이는 **default skills policy**다.

- Python harness는 skills를 기본으로 붙이지 않는다. `skills_provider`나 `skills_paths`가 있어야 한다.  
  ([Python assembly](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_agent.py#L200-L205))
- .NET harness는 current working directory 기반 `AgentSkillsProvider`를 기본으로 붙인다.  
  ([.NET assembly](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs#L320-L326), [.NET options remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgentOptions.cs#L315-L334))

코드 우선으로 보면, cross-SDK Java 설계에서는 이 차이를 명시적으로 결정해야 한다.

### Java 결정
- **MVP 포함**: harness assembler, todo/mode/file-memory, approval, loop seam
- **MVP 제외**: shared file access, advanced structured memory, shell auto-wiring
- **기본 policy**: Python 쪽처럼 **skills opt-in**, **file access opt-in**으로 두는 것이 더 보수적이다.  
  ([Python opt-in providers](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_agent.py#L189-L205), [.NET broader default set](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs#L287-L344))

### Acceptance scenarios
1. 기본 생성 시 Python harness는 history/compaction/todo/mode/file-memory를 포함해야 한다.  
   ([test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_agent.py#L93-L110))
2. `file_access_store` 없이는 file access가 붙지 않아야 한다.  
   ([test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_agent.py#L148-L168))
3. .NET harness는 invalid context/output token 조합에서 constructor 단계에서 실패해야 한다.  
   ([test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Harness.UnitTests/HarnessAgentTests.cs#L50-L76))
4. shell executor를 Python harness에 넘기면 shell tool과 environment provider가 추가되어야 하고, client가 shell 미지원이면 둘 다 skip되어야 한다.  
   ([tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_agent.py#L905-L962))

---

## 4. Automatic loop policy

### 목적
Automatic loop policy의 목적은 사용자가 다시 prompt하지 않아도 agent가 같은 “작업 intent”를 이어서 처리하게 만드는 것이다. loop는 todo completion, background-task completion, completion marker, AI judge 결과 같은 **재실행 조건** 위에서 동작한다.  
([Python loop overview](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_loop.py#L3-L26), [.NET loop overview](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Loop/LoopAgent.cs#L20-L37))

### 경계
이 문서는 **loop policy**만 다루며, 다음은 경계만 남긴다.

- `background-tasks`: background task 자체 lifecycle은 별도 문서
- `compaction`: loop가 compaction된 history 위에서 동작할 뿐, compaction 알고리즘 자체는 별도 문서
- `tool approval`: loop가 pending approval을 만나면 중단한다는 seam만 다룸  
  ([Python pending approval seam](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_loop.py#L440-L457), [.NET pending approval seam](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Loop/LoopAgent.cs#L195-L199))

### 성숙도
- **Python**: `AgentLoopMiddleware`는 experimental HARNESS feature다.  
  ([decorator](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_loop.py#L214-L215), [package status](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L120-L124))
- **.NET**: `LoopAgent`, `TodoCompletionLoopEvaluator`, `BackgroundTaskCompletionLoopEvaluator`, `AIJudgeLoopEvaluator` 모두 experimental이다.  
  ([LoopAgent](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Loop/LoopAgent.cs#L64-L65), [TodoCompletionLoopEvaluator](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Loop/TodoCompletionLoopEvaluator.cs#L41-L42), [BackgroundTaskCompletionLoopEvaluator](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Loop/BackgroundTaskCompletionLoopEvaluator.cs#L35-L36), [AIJudgeLoopEvaluator](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Loop/AIJudgeLoopEvaluator.cs#L54-L55))

### 공개 API·타입
- **Python**
  - `AgentLoopMiddleware`
  - `JudgeVerdict`
  - `todos_remaining(...)`, `todos_remaining_message(...)`
  - `background_tasks_running(...)`, `background_tasks_running_message(...)`
  
  ([module exports](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_loop.py#L53-L60), [public re-exports](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/__init__.py#L602-L603), [public re-exports 2](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/__init__.py#L637-L638))
- **.NET**
  - `LoopAgent`
  - `LoopAgentOptions`
  - `LoopEvaluator`, `LoopEvaluation`
  - `TodoCompletionLoopEvaluator`
  - `BackgroundTaskCompletionLoopEvaluator`
  - `AIJudgeLoopEvaluator`
  
  ([LoopAgent ctor/public surface](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Loop/LoopAgent.cs#L79-L130), [LoopAgentOptions](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Loop/LoopAgentOptions.cs#L12-L117))

### 상세 실행 흐름
#### Python
Python loop는 middleware 형태다.

1. 필요하면 `additional_instructions`를 system message로 먼저 삽입한다.
2. `fresh_context=True`이고 session이 있으면 pre-loop snapshot을 잡는다.
3. 매 iteration에서 inner agent를 호출한다.
4. 응답에 pending `function_approval_request`가 있으면 즉시 loop를 종료하고 caller로 반환한다.
5. 아니면 `should_continue`를 평가한다.
6. 필요 시 `record_feedback` 결과를 progress log에 적재한다.
7. `next_message`로 다음 입력을 구성한다.
8. `fresh_context=True`면 snapshot을 복원한 뒤 원본 입력 + progress log + next message로 다시 시작한다.  
([main process](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_loop.py#L415-L439), [non-streaming loop body](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_loop.py#L475-L557), [next-message/progress resolution](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_loop.py#L749-L793))

#### .NET
.NET loop는 decorator + evaluator chain이다.

1. caller input는 첫 iteration에만 직접 inner agent로 들어간다.
2. 각 iteration 후 evaluators를 **순서대로** 평가한다.
3. 첫 번째 `Continue(...)` evaluator가 “다음 iteration 구동자”가 되고, 그 feedback만 다음 입력으로 사용된다.
4. 모든 evaluator가 `Stop()`이면 loop가 끝난다.
5. pending approval이 있으면 종료한다.
6. `LoopAgentOptions.MaxIterations`가 global safety cap으로 적용된다.  
([evaluator priority semantics](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Loop/LoopAgent.cs#L25-L37), [run loop body](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Loop/LoopAgent.cs#L173-L217))

### 상태·영속화
- Python은 `progress` log를 in-memory로 유지하고, `fresh_context`일 때만 session snapshot을 복원한다.  
  ([progress and snapshot state](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_loop.py#L482-L489), [snapshot restore](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_loop.py#L548-L553))
- .NET은 original messages, feedback log, optional serialized session snapshot을 내부 loop state로 보관한다.  
  ([state in run core](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Loop/LoopAgent.cs#L141-L172))

### 확장점
- Python은 `should_continue`, `next_message`, `record_feedback`, `with_judge(criteria=..., instructions=...)`를 caller가 자유롭게 조합할 수 있다.  
  ([callback contracts](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_loop.py#L128-L141), [judge factory](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_loop.py#L347-L413))
- .NET은 evaluator chain과 `LoopAgentOptions`로 확장한다.  
  ([LoopAgent ctors](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Loop/LoopAgent.cs#L79-L130), [LoopAgentOptions](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Loop/LoopAgentOptions.cs#L17-L117))

### 동시성·스트리밍·취소
- Python은 streaming/non-streaming 구현이 분리되어 있고, streaming에서는 injected “nudge” message도 update로 흐른다.  
  ([streaming path](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_loop.py#L559-L655))
- .NET도 streaming에서 iteration 경계를 `ResponseId` 기반으로 표면화할 수 있고, on-behalf-of messages를 업데이트로 흘린다.  
  ([streaming path](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Loop/LoopAgent.cs#L253-L320))
- 취소는 둘 다 async/cancellation-token 모델을 따른다.  
  ([Python maybe-await callbacks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_loop.py#L144-L148), [.NET cancellation token usage](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Loop/LoopAgent.cs#L133-L138))

### 오류·검증
- Python은 `max_iterations`가 1 미만이면 실패한다. `looping_modes=[]`도 실패한다.  
  ([max_iterations validation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_loop.py#L333-L346), [looping_modes validation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_loop.py#L903-L907))
- .NET은 null/empty evaluator set, null evaluator element, `MaxIterations < 1`를 거절한다.  
  ([tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/Loop/LoopAgentTests.cs#L24-L118))

### 보안
AI judge loop는 양쪽 모두 **second external LLM boundary**를 만든다. 즉 원 요청과 최신 agent response가 judge model로 전송되므로, exfiltration과 indirect prompt injection 위험이 있다.  
([Python judge security note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_loop.py#L367-L382), [.NET judge security note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Loop/AIJudgeLoopEvaluator.cs#L43-L52))

### .NET 구현과 테스트
- `LoopAgent`는 aggregated transcript와 usage를 유지한다.  
  ([aggregation logic](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Loop/LoopAgent.cs#L162-L217))
- tests는 immediate stop, multi-iteration aggregation, last-response-only를 고정한다.  
  ([LoopAgentTests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/Loop/LoopAgentTests.cs#L123-L210))
- AI judge tests는 structured verdict, text fallback, custom instructions/feedback, multimodal request forwarding을 고정한다.  
  ([AIJudgeLoopEvaluatorTests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/Loop/AIJudgeLoopEvaluatorTests.cs#L20-L220))

### Python 구현과 테스트
- harness tests는 loop middleware가 outermost로 들어가야 함을 고정한다.  
  ([outermost loop test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_agent.py#L1171-L1195))
- loop feature 자체는 `loop_should_continue` opt-in 시 experimental warning을 낸다.  
  ([warning test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_agent.py#L1359-L1379))

### 문서 차이
문서보다 코드에서 더 중요한 차이는 **구조 모델의 차이**다.

- Python: 하나의 `should_continue` predicate/middleware 모델  
- .NET: ordered evaluator chain 모델

둘은 user-facing 목적은 비슷하지만, evaluator composition semantics는 다르다.  
([Python callback shape](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_loop.py#L128-L141), [.NET first-continue-wins semantics](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Loop/LoopAgent.cs#L32-L37))

### Java 결정
Java에서는 **MVP는 Python식 predicate middleware**, **후속 단계는 .NET식 evaluator chain**이 적절하다. 전자는 빠르게 구현 가능하고, 후자는 조합성과 우선순위 semantics를 잘 드러낸다.  
([Python middleware shape](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_loop.py#L214-L325), [.NET evaluator chain shape](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Loop/LoopAgent.cs#L25-L37))

### Acceptance scenarios
1. loop는 pending approval을 만나면 즉시 중단하고 caller에 approval request를 반환해야 한다.  
   ([Python](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_loop.py#L440-L457), [.NET](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Loop/LoopAgent.cs#L195-L199))
2. .NET에서 여러 evaluator가 있을 때 첫 번째 continue evaluator만 다음 iteration을 구동해야 한다.  
   ([semantics](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Loop/LoopAgent.cs#L32-L37))
3. Python `todos_remaining(looping_modes=["execute"])`는 execute mode에서만 계속 돌아야 한다.  
   ([helper](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_loop.py#L876-L937))
4. AI judge가 `answered=false`이면 gap feedback을 다음 iteration 입력으로 돌려줘야 한다.  
   ([Python judge next_message](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_loop.py#L201-L209), [.NET feedback template](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Loop/AIJudgeLoopEvaluator.cs#L102-L106))

---

## 5. Todo provider

### 목적
Todo provider는 long-running task를 **작업 항목 리스트**로 외재화해, 계획/실행/정리 단계를 명시적으로 추적하게 한다.  
([Python todo instructions](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_todo.py#L24-L48), [.NET todo remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Todo/TodoProvider.cs#L15-L38))

### 경계
Todo provider는 **planning artifact store**이지 workflow DAG나 scheduler가 아니다. harness 안에서는 주로 다음 경계에서 사용된다.

- mode provider와 함께 “plan → execute” 전환을 도와준다.
- loop policy의 completion predicate로 쓰일 수 있다.
- structured memory/file memory에 장기 보관되는 결과물과는 다르다.  
  ([Python mode default plan/execute guidance](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_mode.py#L32-L70), [Python loop todo helper](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_loop.py#L876-L937), [.NET todo loop evaluator](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Loop/TodoCompletionLoopEvaluator.cs#L15-L39))

### 성숙도
- **Python**: `TodoProvider`와 session store는 stable로 보이며, file-backed `TodoFileStore`만 experimental harness 범주다.  
  ([package status text](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L120-L124), [TodoFileStore experimental marker](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_todo.py#L287-L288))
- **.NET**: inspected `TodoProvider`에는 experimental marker가 없다.  
  ([type header](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Todo/TodoProvider.cs#L15-L40))

### 공개 API·타입
- **Python**
  - `TodoItem`
  - `TodoProvider`
  - `TodoSessionStore`
  - `TodoFileStore`
  - `TodoStore`
  
  ([type definitions](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_todo.py#L51-L243), [public re-exports](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/__init__.py#L558-L562))
- **.NET**
  - `TodoProvider`
  - `TodoProviderOptions`
  - `TodoCompletionLoopEvaluator`
  
  ([TodoProvider header](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Todo/TodoProvider.cs#L15-L40), [TodoCompletionLoopEvaluator header](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Loop/TodoCompletionLoopEvaluator.cs#L15-L42))

### 상세 실행 흐름
#### Python
`before_run`에서 다음을 수행한다.

1. `todos_add`
2. `todos_complete`
3. `todos_remove`
4. `todos_get_remaining`
5. `todos_get_all`

을 전부 `approval_mode="never_require"`로 주입한다.  
그리고 현재 todo list를 `### Current todo list` user message로 함께 넣는다.  
([tools injection](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_todo.py#L493-L615))

#### .NET
`TodoProvider`도 동일한 5-tool model을 제공하며, session state에서 item list를 읽고 쓴다. loop evaluator는 별도 provider instance를 직접 받지 않고 `context.Agent.GetService<TodoProvider>()`로 resolve한다.  
([provider remarks/tools](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Todo/TodoProvider.cs#L21-L38), [loop evaluator resolution](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Loop/TodoCompletionLoopEvaluator.cs#L101-L126))

### 상태·영속화
- Python 기본은 `AgentSession.state[source_id]`에 `items`와 `next_id`를 둔다.  
  ([TodoSessionStore](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_todo.py#L245-L285))
- Python file store는 owner/session/source_id별 JSON file을 생성하고, atomic replace로 저장한다.  
  ([path shaping](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_todo.py#L344-L394), [atomic save](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_todo.py#L419-L443))
- .NET은 session state bag에 todo list를 저장한다.  
  ([remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Todo/TodoProvider.cs#L21-L24))

### 확장점
- Python은 custom `TodoStore`를 주입할 수 있다.  
  ([abstract store](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_todo.py#L228-L243), [provider ctor](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_todo.py#L462-L480))
- .NET은 `TodoProviderOptions`와 `TodoCompletionLoopEvaluatorOptions`로 message formatting과 loop feedback을 바꿀 수 있다.  
  ([provider ctor/options hook](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Todo/TodoProvider.cs#L74-L87), [loop evaluator options use](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Loop/TodoCompletionLoopEvaluator.cs#L60-L94))

### 동시성·스트리밍·취소
- Python은 per-session mutation lock을 둔다.  
  ([lock table](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_todo.py#L481-L491))
- .NET은 per-session lock으로 duplicate ID/lost update를 막는다고 명시한다.  
  ([thread-safe remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Todo/TodoProvider.cs#L35-L38), [session lock fields](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Todo/TodoProvider.cs#L66-L71))
- 별도의 스트리밍 특화 경로는 없고, 상위 agent stream 위에서 일반 tool invocation으로 동작한다.  
  ([Python todo is regular tools](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_todo.py#L505-L595), [.NET todo tool surface](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Todo/TodoProvider.cs#L26-L33))

### 오류·검증
- Python은 malformed session state, non-list items, non-int `next_id`를 거절한다.  
  ([validation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_todo.py#L257-L275))
- `todos_add/items/ids` empty payload도 거절한다.  
  ([add/complete/remove validation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_todo.py#L508-L509), [complete validation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_todo.py#L534-L535), [remove validation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_todo.py#L566-L567))
- Python file store는 session path traversal을 거절한다.  
  ([test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_todo.py#L214-L223))
- .NET tests는 complete/remove/add semantics를 고정한다.  
  ([tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/Todo/TodoProviderTests.cs#L44-L189))

### 보안
Todo 기능 자체는 low-risk지만, file-backed persistence를 쓰는 Python 구현은 path-safe encoding과 atomic write로 corruption/escape를 줄인다.  
([safe segment logic](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_todo.py#L364-L394), [atomic replace](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_todo.py#L431-L443))

### .NET 구현과 테스트
- `TodoProvider`는 5-tool surface와 session-state model을 제공한다.  
  ([provider remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Todo/TodoProvider.cs#L21-L38))
- tests는 tool count, add/complete/remove behavior를 검증한다.  
  ([ProvideAIContext test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/Todo/TodoProviderTests.cs#L17-L40), [add tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/Todo/TodoProviderTests.cs#L44-L100), [complete tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/Todo/TodoProviderTests.cs#L104-L189))

### Python 구현과 테스트
- `TodoSessionStore`와 `TodoFileStore`는 모두 `next_id` clamping을 통해 ID collision을 막는다.  
  ([clamp helper](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_todo.py#L223-L225), [test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_todo.py#L180-L198))
- file store tests는 atomic write crash safety까지 검증한다.  
  ([test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_todo.py#L130-L149))

### 문서 차이
이 영역에서 눈에 띄는 doc/code 불일치는 없었다. 다만 package status의 “experimental harness APIs” 설명은 provider 전체가 아니라 **file-backed todo storage 포함 harness 하위 기능**을 넓게 가리키므로, 실제 성숙도 판단은 코드와 export 단위로 해야 한다.  
([package status](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L120-L124), [TodoProvider stable-looking surface](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_todo.py#L446-L616))

### Java 결정
Java MVP에는 **session-state 기반 todo provider**를 포함하고, **file-backed store는 제외**하는 것이 적절하다. session-state model만으로도 harness loop/mode integration을 구현할 수 있고, file store는 path safety와 crash safety까지 고려해야 하기 때문이다.  
([session store](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_todo.py#L245-L285), [file store complexity](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_todo.py#L287-L443))

### Acceptance scenarios
1. `todos_add`는 여러 item을 넣을 때 증가하는 ID를 부여해야 한다.  
   ([.NET test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/Todo/TodoProviderTests.cs#L71-L100))
2. `todos_complete`는 존재하지 않는 ID에 대해 0을 반환해야 한다.  
   ([.NET test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/Todo/TodoProviderTests.cs#L151-L166))
3. Python file-backed todo save 도중 rename 실패가 나도 기존 상태 파일은 유지되어야 한다.  
   ([test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_todo.py#L130-L149))

---

## 6. Mode provider

### 목적
Mode provider는 같은 agent를 **interactive planning mode**와 **autonomous execution mode**로 명시적으로 전환하게 해, “언제 질문하고 언제 자율적으로 진행할지”를 session state로 고정한다.  
([Python default mode map](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_mode.py#L32-L71), [.NET remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/AgentMode/AgentModeProvider.cs#L20-L39))

### 경계
Mode provider는 **task state machine**이 아니라 **behavioral policy selector**다. Todo/loop와 결합하면 “plan mode는 interactive, execute mode는 autonomous loop” 같은 규칙을 만들 수 있지만, mode 자체가 loop를 돌리지는 않는다.  
([Python todo loop mode gating](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_loop.py#L885-L894), [.NET todo loop mode gating](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Loop/TodoCompletionLoopEvaluator.cs#L29-L39))

### 성숙도
- **Python**: stable harness built-in feature로 취급된다.  
  ([default assembly](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_agent.py#L176-L181))
- **.NET**: inspected surface상 experimental marker 없이 기본 built-in provider다.  
  ([built-in provider list](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs#L36-L43), [provider header](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/AgentMode/AgentModeProvider.cs#L15-L45))

### 공개 API·타입
- **Python**
  - `AgentModeProvider`
  - `get_agent_mode(...)`
  - `set_agent_mode(...)`
  - `mode_get` / `mode_set` tools
  
  ([public helpers and provider](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_mode.py#L118-L194), [tool injection](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_mode.py#L289-L314), [public re-exports](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/__init__.py#L618-L635))
- **.NET**
  - `AgentModeProvider`
  - `AgentModeProviderOptions`
  - nested `AgentMode`
  - `GetModeAsync(...)`, `SetModeAsync(...)`
  
  ([provider remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/AgentMode/AgentModeProvider.cs#L30-L45), [options type](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/AgentMode/AgentModeProviderOptions.cs#L9-L72))

### 상세 실행 흐름
#### Python
- 첫 run에서 mode가 없으면 configured mode 목록의 첫 항목 또는 `default_mode`를 쓴다.
- `mode_set` tool은 session state를 직접 바꾸고, agent가 자기 손으로 바꾼 경우에는 “external change notification”을 남기지 않는다.
- 외부 코드가 `set_agent_mode(...)` helper로 바꾸면, 다음 `before_run` 때 user-role notification을 추가해 모델이 이전 tool-call anchor에 묶이지 않게 한다.  
([default resolution](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_mode.py#L111-L151), [external helper](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_mode.py#L154-L194), [before_run notification behavior](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_mode.py#L284-L325))

#### .NET
- provider는 current mode를 session state에 저장하고, instructions에 current mode와 configured modes를 주입한다.
- `AgentModeProviderOptions`로 custom instructions, custom mode set, default mode를 지정한다.  
  ([provider summary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/AgentMode/AgentModeProvider.cs#L15-L45), [options](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/AgentMode/AgentModeProviderOptions.cs#L15-L43))

### 상태·영속화
- Python은 `session.state[source_id]` dict 안의 `current_mode`를 canonical lower-case로 유지한다.  
  ([state model](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_mode.py#L76-L87), [get/set](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_mode.py#L139-L194))
- .NET도 session state bag에 current mode를 저장한다.  
  ([remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/AgentMode/AgentModeProvider.cs#L20-L23))

### 확장점
- Python은 `mode_instructions`, `instructions`, `default_mode`를 override할 수 있다.  
  ([ctor](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_mode.py#L215-L259))
- .NET은 `AgentModeProviderOptions.Modes`, `Instructions`, `DefaultMode`를 제공한다.  
  ([options](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/AgentMode/AgentModeProviderOptions.cs#L15-L43))

### 동시성·스트리밍·취소
- Python은 명시적 lock 없이 session dict에 저장하지만, 기능 자체가 짧고 단순하다.  
  ([state access](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_mode.py#L76-L87))
- .NET은 “concurrent reads and mutations are serialized using a per-session lock”를 명시한다.  
  ([thread-safe remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/AgentMode/AgentModeProvider.cs#L41-L43))

### 오류·검증
- Python은 duplicate configured modes와 invalid mode set을 거절한다.  
  ([normalize validation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_mode.py#L90-L108))
- .NET tests도 invalid mode가 throw되고 기존 mode가 보존되는지 검증한다.  
  ([test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/AgentMode/AgentModeProviderTests.cs#L102-L120))

### 보안
Mode provider는 privilege boundary는 아니지만, **user interaction boundary**를 만든다. 즉 “plan mode에서는 질문/승인 대기”, “execute mode에서는 자율 진행” 같은 인간-검토 규칙을 session state로 고정한다.  
([Python default mode instructions](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_mode.py#L33-L70))

### .NET 구현과 테스트
- tests는 2-tool injection, default mode, public helper behavior를 검증한다.  
  ([ProvideAIContext tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/AgentMode/AgentModeProviderTests.cs#L18-L62), [tool tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/AgentMode/AgentModeProviderTests.cs#L66-L120), [public helpers](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/AgentMode/AgentModeProviderTests.cs#L164-L199))

### Python 구현과 테스트
- 구현은 mode anchoring 문제를 해결하기 위해 external change notification까지 넣는다. 이는 단순 state get/set보다 한 단계 더 나간 harness-specific policy다.  
  ([comment and implementation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_mode.py#L163-L167), [notification injection](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_mode.py#L315-L325))

### 문서 차이
특별한 문서-코드 불일치는 확인하지 못했다. 다만 Python 쪽 구현은 “시스템 instructions만으로는 mode redirection이 충분하지 않다”는 operational insight를 주석에 명시해, 문서보다 코드가 더 강한 정책 근거를 제공한다.  
([comment](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_mode.py#L188-L191))

### Java 결정
Java에는 mode provider를 **MVP 포함**하는 것이 좋다. 특히 external mode change notification은 그대로 가져오는 편이 모델 anchoring 문제를 줄인다.  
([Python external mode change handling](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_mode.py#L163-L194), [notification injection](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_mode.py#L315-L325))

### Acceptance scenarios
1. 기본 mode는 `plan`이어야 한다.  
   ([.NET test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/AgentMode/AgentModeProviderTests.cs#L127-L141))
2. invalid mode set은 실패하고 기존 mode를 망가뜨리지 않아야 한다.  
   ([.NET test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/AgentMode/AgentModeProviderTests.cs#L102-L120))
3. external helper로 바꾼 mode는 다음 turn에 user-role notification으로 드러나야 한다.  
   ([Python helper + before_run](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_mode.py#L154-L194), [notification emit](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_mode.py#L315-L325))

---

## 7. File memory

### 목적
File memory는 harness가 만든 **session-scoped working memory**다. 목적은 plan, research result, intermediate artifact 같은 “나중에 다시 읽을 가치가 있는 자료”를 chat transcript 밖의 durable file로 밀어내는 것이다.  
([Python instructions](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_file_memory.py#L60-L74), [.NET instructions](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/FileMemory/FileMemoryProvider.cs#L69-L84))

### 경계
- shared persistent workspace가 아니라 **agent-managed memory namespace**다.
- `FileAccessProvider`와는 다르게, internal files(`memories.md`, description sidecars)를 agent-facing 표면에서 숨긴다.
- advanced transcript-backed structured memory와도 구분된다.  
  ([Python distinction vs file access](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_file_memory.py#L16-L21), [Python internal file handling](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_file_memory.py#L93-L100), [.NET internal file handling](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/FileMemory/FileMemoryProvider.cs#L501-L538))

### 성숙도
- **Python**: `FileMemoryProvider`는 graduated다. 테스트가 experimental metadata 부재를 고정한다.  
  ([test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_file_memory.py#L291-L294))
- **.NET**: inspected surface상 experimental marker가 없고, harness default built-in provider다.  
  ([provider list](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs#L36-L43), [provider header](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/FileMemory/FileMemoryProvider.cs#L14-L42))

### 공개 API·타입
- **Python**
  - `FileMemoryProvider`
  - `DEFAULT_FILE_MEMORY_SOURCE_ID`
  - `file_memory_*` tool family
  
  ([provider type](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_file_memory.py#L220-L279))
- **.NET**
  - `FileMemoryProvider`
  - `FileMemoryState`
  - `FileMemoryProviderOptions`
  
  ([provider ctor](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/FileMemory/FileMemoryProvider.cs#L93-L114))

### 상세 실행 흐름
#### Python
1. working folder를 `scope` 또는 `session_id`로 정한다.
2. 해당 폴더가 없으면 만든다.
3. `file_memory_write/read/delete/ls/grep/replace/replace_lines`를 전부 `never_require`로 주입한다.
4. `memories.md` index가 있으면 이를 user message로 주입한다.
5. write/delete 후에는 index를 rebuild한다.  
([working folder resolution](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_file_memory.py#L270-L299), [tool injection](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_file_memory.py#L301-L499), [index injection](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_file_memory.py#L501-L524))

#### .NET
1. `ProviderSessionState<FileMemoryState>`로 current session의 working folder를 얻는다.
2. working folder가 있으면 directory를 만든다.
3. 7개 `file_memory_*` tool을 제공한다.
4. `memories.md`를 읽어 user message로 주입한다.
5. write/delete 시 description sidecar와 index를 함께 갱신한다.  
([state and context](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/FileMemory/FileMemoryProvider.cs#L127-L159), [write path](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/FileMemory/FileMemoryProvider.cs#L161-L209), [tool creation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/FileMemory/FileMemoryProvider.cs#L419-L432))

### 상태·영속화
이 기능에서 가장 중요한 cross-SDK 차이는 **namespace policy**다.

- Python 기본 namespace는 `session_id`다. 즉 같은 store를 공유해도 session별로 memory가 자동 격리된다.  
  ([scope semantics](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_file_memory.py#L243-L279), [default session isolation test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_file_memory.py#L224-L240))
- .NET harness default는 `DateTime.UtcNow + Guid`로 새 working folder를 만든다. 즉 Python보다 더 ephemeral한 기본 namespace다.  
  ([harness default initializer](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs#L301-L312))

### 확장점
- Python은 custom `scope`, custom `AgentFileStore`, custom instructions를 받는다.  
  ([ctor](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_file_memory.py#L239-L268))
- .NET은 custom `stateInitializer`로 working folder policy를 바꿀 수 있다.  
  ([ctor](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/FileMemory/FileMemoryProvider.cs#L93-L114), [test custom subfolder](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/FileMemory/FileMemoryProviderTests.cs#L156-L176))

### 동시성·스트리밍·취소
- Python은 single provider-level `_write_lock`으로 write/delete/index rebuild를 serialize한다.  
  ([lock](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_file_memory.py#L265-L268), [write path lock use](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_file_memory.py#L333-L343))
- .NET도 `SemaphoreSlim _writeLock`으로 같은 정책을 가진다.  
  ([field](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/FileMemory/FileMemoryProvider.cs#L86-L92), [write/delete lock use](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/FileMemory/FileMemoryProvider.cs#L181-L208), [delete lock use](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/FileMemory/FileMemoryProvider.cs#L247-L260))
- 별도 streaming path는 없고 일반 tool invocation 모델을 따른다.  
  ([Python regular function tools](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_file_memory.py#L315-L499), [.NET CreateTools](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/FileMemory/FileMemoryProvider.cs#L419-L432))

### 오류·검증
- Python은 nested path와 reserved internal names를 거절한다.  
  ([validation in write](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_file_memory.py#L319-L330), [tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_file_memory.py#L255-L265))
- Python은 index read가 깨져도 run을 막지 않고 injection만 skip한다.  
  ([self-heal comment](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_file_memory.py#L501-L508))
- .NET은 nested path/internal file name에서 `ArgumentException`을 던진다.  
  ([ValidateMemoryFileName](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/FileMemory/FileMemoryProvider.cs#L517-L538))

### 보안
- file memory는 approval-free이지만, flat namespace와 internal file hiding으로 accidental corruption을 줄인다.  
  ([Python flat namespace rule](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_file_memory.py#L111-L122), [.NET flat namespace rule](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/FileMemory/FileMemoryProvider.cs#L510-L538))

### .NET 구현과 테스트
- tests는 tool count, description sidecar, stale description deletion, custom working subfolder를 검증한다.  
  ([tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/FileMemory/FileMemoryProviderTests.cs#L47-L176))

### Python 구현과 테스트
- tests는 list/search에서 internal files를 숨기고, explicit shared scope를 통해 cross-session 공유가 가능함을 검증한다.  
  ([hide internal files](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_file_memory.py#L203-L221), [shared scope](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_file_memory.py#L242-L252))

### 문서 차이
Python package status의 “harness memory experimental” 서술과 달리, `FileMemoryProvider` 자체는 코드와 테스트상 graduated다. 따라서 **structured memory와 file memory를 분리**해서 읽는 것이 맞다.  
([package status](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L120-L124), [graduated test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_file_memory.py#L291-L294))

### Java 결정
Java MVP에는 포함한다. 다만 기본 namespace는 .NET의 timestamp+GUID보다 Python의 `session_id`-scoped policy가 예측 가능성과 재현성 면에서 낫다.  
([Python scope](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_file_memory.py#L243-L279), [.NET default initializer](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs#L301-L312))

### Acceptance scenarios
1. memory file 저장 후 다음 run에서 `memories.md` index가 user context로 inject되어야 한다.  
   ([Python test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_file_memory.py#L176-L201))
2. reserved internal names(`memories.md`, `*_description.md`)는 저장 거부되어야 한다.  
   ([Python test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_file_memory.py#L255-L265), [.NET validation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/FileMemory/FileMemoryProvider.cs#L523-L538))
3. explicit shared scope를 쓰면 session을 넘어 같은 memory namespace를 봐야 한다.  
   ([Python test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_file_memory.py#L242-L252))

---

## 8. File access

### 목적
File access는 user가 열어준 working area에 대해 **shared, persistent CRUD/search/edit** 기능을 제공한다. 입력 데이터 읽기, 출력 artifact 쓰기, 기존 파일 소규모 수정이 주 용도다.  
([Python module docstring](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_file_access.py#L3-L20), [.NET remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/FileAccess/FileAccessProvider.cs#L17-L48))

### 경계
- file memory와 달리 **shared store**다.
- skills/resource loading과 달리 user-visible workspace에 대한 일반 파일 조작이다.
- structured memory처럼 transcript/semantic extraction을 하지 않는다.  
  ([Python distinction](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_file_access.py#L5-L19), [.NET distinction](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/FileAccess/FileAccessProvider.cs#L23-L33))

### 성숙도
- **Python**: experimental harness feature다.  
  ([package status](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L120-L124), [provider marker](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_file_access.py#L1203-L1204))
- **.NET**: `FileAccessProvider`와 `FileSystemAgentFileStore`가 experimental이다.  
  ([provider marker](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/FileAccess/FileAccessProvider.cs#L76-L77), [store marker](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/FileStore/FileSystemAgentFileStore.cs#L31-L32))

### 공개 API·타입
- **Python**
  - `FileAccessProvider`
  - `AgentFileStore`
  - `InMemoryAgentFileStore`
  - `FileSystemAgentFileStore`
  - `FileSearchResult`, `FileSearchMatch`, `FileStoreEntry`
  
  ([store abstractions](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_file_access.py#L288-L770), [provider](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_file_access.py#L1204-L1603))
- **.NET**
  - `FileAccessProvider`
  - `FileAccessProviderOptions`
  - `AgentFileStore`
  - `FileSystemAgentFileStore`
  
  ([harness options surface](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgentOptions.cs#L256-L275))

### 상세 실행 흐름
#### Python
- `file_access_write/read/delete/ls/grep/replace/replace_lines`를 주입한다.
- 기본은 **모든 도구가 approval-required**다.
- read-only tool approval과 write tool approval을 그룹별로 독립 설정할 수 있다.
- `disable_write_tools=True`면 write 계열 도구 자체를 숨긴다.  
([tool set and policy](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_file_access.py#L1229-L1253), [init options](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_file_access.py#L1296-L1337), [tool injection](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_file_access.py#L1443-L1589))

#### .NET
- 같은 7-tool surface를 `AIFunction`으로 만들고, approval이 필요한 그룹은 `ApprovalRequiredAIFunction`으로 감싼다.
- read-only/write approval group은 분리되어 있고, write tools는 아예 숨길 수도 있다.  
([remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/FileAccess/FileAccessProvider.cs#L46-L74), [tool creation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/FileAccess/FileAccessProvider.cs#L449-L479))

### 상태·영속화
- provider 자체는 stateless이고, supplied `AgentFileStore`가 persistence를 담당한다.  
  ([Python provider fields](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_file_access.py#L1332-L1343), [.NET StateKeys empty test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/FileAccess/FileAccessProviderTests.cs#L205-L216))
- Python default harness는 file access를 아예 붙이지 않는다.  
  ([opt-in wiring](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_agent.py#L189-L198))
- .NET도 `FileAccessStore`가 있을 때만 붙는다.  
  ([provider assembly](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs#L315-L318))

### 확장점
- Python은 custom store와 approval flags, hide-write-tools flag를 제공한다.  
  ([ctor](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_file_access.py#L1296-L1337))
- .NET도 `FileAccessProviderOptions`를 통해 같은 축을 조정한다.  
  ([harness options remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgentOptions.cs#L256-L275))

### 동시성·스트리밍·취소
- Python은 write/delete/replace/replace_lines를 single async lock으로 serialize한다.  
  ([write lock comment](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_file_access.py#L1338-L1343))
- .NET도 provider-level semaphore를 사용한다.  
  ([semaphore field](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/FileAccess/FileAccessProvider.cs#L134-L140))
- 별도 streaming 전용 구현은 없고 일반 tool invocation/approval flow를 따른다.  
  ([Python tools are regular function tools](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_file_access.py#L1447-L1589), [.NET CreateTools regular AIFunctions](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/FileAccess/FileAccessProvider.cs#L449-L479))

### 오류·검증
- Python은 path를 normalize하면서 rooted path/drive root/`.`/`..`/trailing separator를 거절한다.  
  ([normalize path](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_file_access.py#L128-L190))
- regex search는 length cap 256, worker-thread wall-clock timeout 10초를 둔다.  
  ([regex guard](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_file_access.py#L68-L125))
- non-UTF-8 파일은 read에서 clean `ValueError`, search에서는 skip+log 처리다.  
  ([read utf-8 error](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_file_access.py#L932-L960), [search utf-8 skip](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_file_access.py#L1065-L1095))
- .NET file-system store는 regex timeout 5초와 root-containment/reparse-point rejection을 쓴다.  
  ([regex timeout](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/FileStore/FileSystemAgentFileStore.cs#L179-L180), [safe path resolution](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/FileStore/FileSystemAgentFileStore.cs#L307-L374))

### 보안
- Python과 .NET 모두 approval이 기본 경계다.  
  ([Python default approval boundary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_file_access.py#L1230-L1247), [.NET default approval boundary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/FileAccess/FileAccessProvider.cs#L50-L74))
- name-based auto-approval rule collision은 양쪽 모두 명시적 보안 경고를 둔다.  
  ([Python read-only rule warning](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_file_access.py#L1375-L1382), [Python all-tools warning](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_file_access.py#L1411-L1420), [.NET read-only rule warning](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/FileAccess/FileAccessProvider.cs#L187-L191), [.NET all-tools warning](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/FileAccess/FileAccessProvider.cs#L224-L228))
- Python filesystem store는 hostile co-tenant sandbox가 아니라 single/cooperative-tenant용이라고 명시한다.  
  ([threat model remark](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_file_access.py#L780-L789))

### .NET 구현과 테스트
- tests는 기본 7 tools, default all-tools-require-approval, group별 opt-out, auto-approval rules를 검증한다.  
  ([tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/FileAccess/FileAccessProviderTests.cs#L40-L158))

### Python 구현과 테스트
- tests는 path normalization, in-memory/filesystem traversal rejection, symlink rejection, regex timeout, non-UTF-8 handling, approval mode distribution, `replace_lines` semantics를 고정한다.  
  ([path tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_file_access.py#L67-L106), [filesystem symlink tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_file_access.py#L286-L315), [approval tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_file_access.py#L543-L605), [replace_lines tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_file_access.py#L1104-L1234))

### 문서 차이
Python과 .NET의 `FileSystemAgentFileStore` 생성 시점이 다르다.

- Python: root directory를 constructor에서 만들지 않고 write/create_directory 때 lazy create한다.  
  ([Python remark](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_file_access.py#L773-L779))
- .NET: constructor에서 즉시 `Directory.CreateDirectory(fullRoot)`를 호출한다.  
  ([.NET ctor](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/FileStore/FileSystemAgentFileStore.cs#L45-L59))

코드 우선으로 보면, Java 설계에서 어떤 semantics를 채택할지 미리 정해야 한다.

### Java 결정
Java MVP에서는 제외한다. file access는 shared mutable state, approval boundary, symlink/reparse hardening, regex guard를 모두 수반해 표면적이 크기 때문이다. Java 후속 단계에서 별도 module로 도입하는 편이 적절하다.  
([Python experimental status](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L120-L124), [.NET experimental options](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgentOptions.cs#L256-L275))

### Acceptance scenarios
1. default는 모든 file-access tool이 approval-required여야 한다.  
   ([.NET test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/FileAccess/FileAccessProviderTests.cs#L54-L63))
2. path traversal과 symlink path는 read/write/search에서 차단되어야 한다.  
   ([Python normalize/store tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_file_access.py#L67-L106), [Python filesystem symlink test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_file_access.py#L295-L315), [.NET safe path](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/FileStore/FileSystemAgentFileStore.cs#L307-L374))
3. runaway regex는 clean timeout/value error로 surface되어야 한다.  
   ([Python timeout guard](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_file_access.py#L106-L125), [.NET regex timeout](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/FileStore/FileSystemAgentFileStore.cs#L179-L180))

---

## 9. Structured memory

### 목적
Structured memory는 transcript archive와 topic memory 파일을 결합해, “그때그때 쌓인 대화 기록”을 **주제별 durable memory**로 정제하려는 기능이다. `MEMORY.md`는 always-loaded TOC이고, topic files와 transcript search가 나뉜다.  
([context prompt and files](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_memory.py#L30-L44), [MemoryContextProvider summary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_memory.py#L930-L987), [before_run injection](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_memory.py#L1180-L1346))

### 경계
- 이 기능은 **file memory의 상위 대체재가 아니라 별도 계층**이다.
- harness default built-in provider는 아니다.
- .NET harness default built-in 목록에는 이 동등 기능이 보이지 않고, 대신 `FileMemoryProvider`만 있다.  
  ([Python default built-ins exclude structured memory](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_agent.py#L145-L218), [.NET built-in provider list](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs#L36-L49), [.NET actual file memory wiring only](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs#L301-L312))

### 성숙도
- **Python**: experimental HARNESS feature다.  
  ([experimental markers](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_memory.py#L244-L245), [provider marker](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_memory.py#L930-L931), [package status](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L120-L124))
- **.NET**: 이 commit에서 harness-level 동등 structured memory provider는 확인되지 않았다. 코드상 기본 built-in은 file memory만 명시된다.  
  ([.NET built-ins](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs#L36-L49), [.NET actual wiring](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs#L301-L312))

### 공개 API·타입
- **Python**
  - `MemoryContextProvider`
  - `MemoryFileStore`
  - `MemoryStore`
  - `MemoryIndexEntry`
  - `MemoryTopicRecord`
  - memory tools: `list_memory_topics`, `read_memory_topic`, `write_memory`, `delete_memory_topic`, `search_memory_transcripts`, `consolidate_memories`
  
  ([types](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_memory.py#L245-L349), [store](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_memory.py#L655-L931), [tools](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_memory.py#L1203-L1290))
- **.NET**
  - 해당 harness 범위의 동등 public type은 확인되지 않음. built-in memory 관련 type으로는 `FileMemoryProvider`만 조립된다.  
    ([.NET harness wiring](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs#L301-L312))

### 상세 실행 흐름
#### Python
`before_run`:

1. topic index를 rebuild/get
2. recent transcript turns를 선택
3. input message 기반으로 auto-load할 topic files를 선택
4. memory tools를 주입
5. recent turns와 `MEMORY.md` + loaded topic files를 user messages로 주입한다.  
([before_run core](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_memory.py#L1180-L1346))

`after_run`:

1. transcript archive 저장
2. maintenance state 갱신
3. extractor model로 memory candidates 추출
4. topic files에 merge
5. consolidation cadence가 되었으면 consolidate  
([after_run core](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_memory.py#L1348-L1396), [extract](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_memory.py#L1398-L1470), [consolidate](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_memory.py#L1505-L1657))

### 상태·영속화
- on-disk structure는 `MEMORY.md`, `topics/`, `transcripts/`, `state.json`이다.  
  ([store layout](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_memory.py#L655-L758))
- owner routing metadata는 session state에 저장하고, 실제 memory root path는 encoded owner id + source id 아래로 파생된다.  
  ([owner id routing](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_memory.py#L700-L745))

### 확장점
- custom extraction prompt / consolidation prompt / consolidation client / history filter / selection limits / cadence를 전부 바꿀 수 있다.  
  ([provider ctor options](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_memory.py#L941-L987))

### 동시성·스트리밍·취소
- topic 단위, maintenance state 단위 async lock을 둔다.  
  ([locks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_memory.py#L1025-L1050))
- consolidation은 LLM call을 state lock 밖에서 수행해 긴 block을 피한다.  
  ([comment and flow](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_memory.py#L1513-L1526))

### 오류·검증
- extraction model failure, invalid JSON, malformed payload는 warning 후 skip한다.  
  ([extract failure paths](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_memory.py#L1412-L1442))
- consolidation도 empty/invalid/malformed payload면 skip한다.  
  ([consolidation failure paths](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_memory.py#L1602-L1645))
- owner id path traversal은 거절한다.  
  ([validation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_memory.py#L700-L710), [test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_memory.py#L216-L231))

### 보안
- topic files는 여러 session contributor를 가질 수 있고, provider는 cross-session origin_session_ids를 downstream observer에게 붙인다.  
  ([origin propagation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_memory.py#L1317-L1346))
- raw transcript search는 exact historical detail이 필요할 때만 쓰라고 instructions에 적는다.  
  ([instructions](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_memory.py#L1295-L1312))

### .NET 구현과 테스트
- 이 commit에서 harness built-in structured memory에 대한 .NET source/test는 확인하지 못했다. harness assembly는 file-memory-only다.  
  ([provider assembly](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs#L301-L312))

### Python 구현과 테스트
- tests는 file store topic/index/state/transcript search, owner path traversal rejection, source_id namespacing을 검증한다.  
  ([tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_memory.py#L134-L245))

### 문서 차이
Python package status의 “memory experimental” 서술은 이 structured memory에는 정확히 맞지만, file memory와 혼동하기 쉽다. 코드 기준으로는 **structured memory만 experimental**, **file memory는 graduated**로 읽는 편이 정확하다.  
([package status](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L120-L124), [file memory graduated test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_file_memory.py#L291-L294))

### Java 결정
Java MVP에서는 제외한다. LLM-driven extraction/consolidation, topic selection, transcript archive까지 포함되어 표면적이 너무 크고 experimental이기 때문이다.  
([provider experimental status](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_memory.py#L930-L931))

### Acceptance scenarios
1. memory extraction call이 실패해도 agent run은 계속되어야 한다.  
   ([failure handling](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_memory.py#L1412-L1442))
2. owner id에 path traversal이 있으면 file system escape 없이 거부되어야 한다.  
   ([validation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_memory.py#L700-L710), [test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_memory.py#L216-L231))
3. loaded topic files는 cross-session origin provenance를 함께 노출해야 한다.  
   ([origin propagation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_memory.py#L1317-L1346))

---

## 10. Tool approval

### 목적
Tool approval은 harness가 **“인간 승인 필요 도구”와 “도구 자동 승인/standing rule”**을 함께 다루는 핵심 안전 레이어다. 단발성 prompt/response를 넘어서, queued approvals, replay, hidden mixed batches, hosted/local 경계를 session 동안 일관되게 유지하는 것이 목적이다.  
([Python middleware summary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_tool_approval.py#L343-L349), [.NET middleware summary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/ToolApproval/ToolApprovalAgent.cs#L17-L19))

### 경계
- approval은 기본 tool contract 위의 **harness safety coordinator**다.
- `FileAccessProvider`/`SkillsProvider`/shell/codeact 같은 기능은 각자 approval-required tool을 만들 수 있지만, queued presentation과 standing rule persistence는 approval layer가 맡는다.  
  ([Python file access approval boundary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_file_access.py#L1230-L1247), [Python skills approval boundary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_skills.py#L1862-L1876), [.NET file access approval boundary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/FileAccess/FileAccessProvider.cs#L50-L74))

### 성숙도
- **Python**: stable public export로 보이며 harness default middleware다.  
  ([public export](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/__init__.py#L565-L568), [default assembly](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_agent.py#L636-L646))
- **.NET**: `ToolApprovalAgent`도 harness default decorator다.  
  ([default assembly](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs#L151-L159))

### 공개 API·타입
- **Python**
  - `ToolApprovalMiddleware`
  - `ToolApprovalRule`
  - `ToolApprovalState`
  - `ToolApprovalRuleCallback`
  - `create_always_approve_tool_response(...)`
  - `create_always_approve_tool_with_arguments_response(...)`
  
  ([public export list](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/__init__.py#L565-L568), [helper creators](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_tool_approval.py#L218-L245))
- **.NET**
  - `ToolApprovalAgent`
  - `ToolApprovalAgentOptions`
  - static `AllToolsAutoApprovalRule`
  
  ([options](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/ToolApproval/ToolApprovalAgentOptions.cs#L10-L68), [all-tools rule](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/ToolApproval/ToolApprovalAgent.cs#L84-L109))

### 상세 실행 흐름
#### Python
1. caller inbound message에서 `function_approval_response`를 걷어내고 state에 반영한다.
2. queued approval requests가 있으면 새 standing rule로 재평가한다.
3. 남아 있으면 다음 queued request 하나만 caller에게 돌려준다.
4. queue가 비었으면 inner agent를 호출한다.
5. outbound message에서 `function_approval_request`를 찾는다.
6. rule match / heuristic auto-approval / first unresolved visible / remaining unresolved queued로 분류한다.
7. 전부 auto-approved면 approval responses를 user message로 다시 주입해 inner agent를 재호출한다.  
([process main](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_tool_approval.py#L381-L420), [inbound processing](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_tool_approval.py#L504-L555), [queue drain / outbound classify](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_tool_approval.py#L557-L628))

#### .NET
1. inbound에서 “always approve” wrapper를 벗기고 standing rule을 저장한다.
2. 기존 queued requests가 있으면 새 rule로 재평가한다.
3. 남은 queue가 있으면 다음 request 하나만 surface한다.
4. inner agent를 호출한다.
5. 모든 surfaced approval requests가 auto-approved면 같은 run 안에서 inner agent를 다시 호출한다.
6. 이 재호출 loop는 별도 cap(`MaxAutoApprovalIterations`) 아래에서만 반복된다.  
([remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/ToolApproval/ToolApprovalAgent.cs#L20-L51), [run loop](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/ToolApproval/ToolApprovalAgent.cs#L119-L184))

### 상태·영속화
- Python state payload는 `rules`, `queued_approval_requests`, `collected_approval_responses`다.  
  ([ToolApprovalState](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_tool_approval.py#L158-L215))
- .NET도 session state bag에 rules와 queued state를 저장하며, approval는 “다음 run”에서도 이어진다.  
  ([remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/ToolApproval/ToolApprovalAgent.cs#L44-L50))

### 확장점
- Python은 heuristic `auto_approval_rules` callback을 순서대로 적용한다.  
  ([ctor/security note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_tool_approval.py#L351-L377), [auto rule match](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_tool_approval.py#L618-L628))
- .NET도 `ToolApprovalAgentOptions.AutoApprovalRules`를 제공한다.  
  ([options](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/ToolApproval/ToolApprovalAgentOptions.cs#L24-L48))

### 동시성·스트리밍·취소
- Python은 streaming path에서 approval requests를 collect 후 batch classify한다.  
  ([streaming](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_tool_approval.py#L435-L502))
- .NET도 non-approval content는 즉시 흘리고 approval requests는 재분류 후 필요 시 재진입한다.  
  ([streaming](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/ToolApproval/ToolApprovalAgent.cs#L187-L280))

### 오류·검증
- Python은 session 없이 approval middleware를 사용할 수 없다.  
  ([RuntimeError](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_tool_approval.py#L383-L386))
- Python argument-scoped rule은 no-arg call도 `{}`로 고정해 wildcard widening을 막는다.  
  ([serialized args contract](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_tool_approval.py#L50-L58))
- hosted tool approvals는 `server_label`이 같은 call에만 재적용된다.  
  ([server label](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_tool_approval.py#L61-L64), [rule match](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_tool_approval.py#L308-L321))
- .NET은 auto-approval cap이 1 미만이면 거절한다.  
  ([test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/ToolApproval/ToolApprovalAgentTests.cs#L2818-L2834))

### 보안
- name-based auto-approval collision은 양쪽 모두 명시적 위험이다.  
  ([Python warning](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_tool_approval.py#L365-L376), [.NET warning](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/ToolApproval/ToolApprovalAgentOptions.cs#L41-L46))
- .NET `AllToolsAutoApprovalRule`는 모든 tool을 승인하므로 fully trusted context에서만 쓰라고 명시한다.  
  ([rule warning](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/ToolApproval/ToolApprovalAgent.cs#L84-L109))

### .NET 구현과 테스트
- tests는 rule persistence, deferred auto-approve, cap hit usage aggregation, default cap count를 검증한다.  
  ([basic tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/ToolApproval/ToolApprovalAgentTests.cs#L71-L220), [cap tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/ToolApproval/ToolApprovalAgentTests.cs#L2618-L2834))

### Python 구현과 테스트
- tests는 approval resume이 caller-owned message를 변형하지 않음, reasoning+function_call group replay, mixed hidden batch isolation, hosted server boundary, budget sharing을 검증한다.  
  ([resume immutability](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_tool_approval.py#L45-L105), [reasoning replay](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_tool_approval.py#L108-L220), [hosted boundary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_tool_approval.py#L1051-L1108), [budget sharing](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_tool_approval.py#L814-L914))

### 문서 차이
큰 불일치는 없지만, budget 처리 방식은 문서 수준보다 코드 차이가 더 크다.

- Python: approval middleware가 function invocation budget state를 공유한다.
- .NET: approval decorator가 별도 `MaxAutoApprovalIterations` cap을 둔다.  
  ([Python shared budget state hook](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_tool_approval.py#L26-L27), [Python injection of budget state](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_tool_approval.py#L387-L389), [.NET cap rationale](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/ToolApproval/ToolApprovalAgentOptions.cs#L50-L68))

### Java 결정
Java MVP에는 반드시 포함한다. 특히 다음 세 요소를 같이 가져와야 한다.

1. standing rule persistence  
2. one-by-one queued approval surface  
3. hosted/local boundary 구분  

Python 구현이 더 직접적이므로, 상태기계 설계는 Python 쪽을 우선 참조하는 편이 적합하다.  
([Python state machine core](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_tool_approval.py#L381-L420), [hosted boundary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_tool_approval.py#L308-L321))

### Acceptance scenarios
1. approval request 여러 개가 한 번에 생겨도 caller에게는 하나씩만 surface되어야 한다.  
   ([Python test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_tool_approval.py#L649-L705), [.NET remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/ToolApproval/ToolApprovalAgent.cs#L26-L35))
2. `always approve tool with arguments`는 exact arguments에만 적용되어야 한다.  
   ([Python serialization/match contract](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_tool_approval.py#L50-L58), [match logic](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_tool_approval.py#L301-L321))
3. hosted tool standing rule은 같은 `server_label`에서만 재적용되어야 한다.  
   ([Python test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_tool_approval.py#L1051-L1108))
4. cap에 도달해도 total usage는 모든 inner turn 합산값이어야 한다.  
   ([.NET cap usage test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/ToolApproval/ToolApprovalAgentTests.cs#L2618-L2779))

---

## 11. Invocation budget

### 목적
Invocation budget은 tool-calling과 approval re-entry가 길어질 때 **비용, side effect, 무한 재호출 위험**을 제한한다. harness 관점에서는 “inner function invocation budget”과 “outer approval/loop budget”이 함께 문제다.  
([Python budget docs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_tools.py#L1332-L1360), [.NET approval cap rationale](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/ToolApproval/ToolApprovalAgentOptions.cs#L50-L68))

### 경계
- function invocation core budget과 harness loop budget은 구분된다.
- compaction은 context window를 줄이지만 budget을 대신하지 않는다.
- approval auto-reentry가 있으면 ordinary per-request iteration cap만으로는 충분하지 않을 수 있다.  
  ([Python core budget config](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_tools.py#L1374-L1403), [.NET separate approval cap](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/ToolApproval/ToolApprovalAgentOptions.cs#L57-L66))

### 성숙도
- **Python**: core function invocation configuration은 stable public config처럼 노출된다.  
  ([normalize config](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_tools.py#L1383-L1403))
- **.NET**: `MaximumIterationsPerRequest`는 harness option surface의 일부이고, approval re-entry budget은 `ToolApprovalAgentOptions`로 분리된다.  
  ([MaximumIterationsPerRequest](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgentOptions.cs#L178-L185), [MaxAutoApprovalIterations](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/ToolApproval/ToolApprovalAgentOptions.cs#L50-L68))

### 공개 API·타입
- **Python**
  - `function_invocation_configuration["max_iterations"]`
  - `function_invocation_configuration["max_function_calls"]`
  - `normalize_function_invocation_configuration(...)`
  
  ([config surface](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_tools.py#L1374-L1403))
- **.NET**
  - `HarnessAgentOptions.MaximumIterationsPerRequest`
  - `ToolApprovalAgentOptions.MaxAutoApprovalIterations`
  - `LoopAgentOptions.MaxIterations`
  
  ([harness per-request cap](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgentOptions.cs#L178-L185), [approval cap](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/ToolApproval/ToolApprovalAgentOptions.cs#L50-L68), [loop cap](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Loop/LoopAgentOptions.cs#L17-L26))

### 상세 실행 흐름
#### Python
Python function invocation core는 3단계 흐름을 가진다.

1. **Phase 1**: inbound approval responses를 먼저 resolve한다.
2. 그 과정에서 function call count를 budget state에 기록한다.
3. 필요하면 `tool_choice="none"`으로 전환해 더 이상 tool을 못 부르게 한다.
4. **Phase 2**: model turn과 local execution을 반복한다.
5. `max_function_calls`에 닿으면 fallback text response를 강제한다.
6. `max_iterations`에 닿으면 tools를 꺼서 final response 한 번 더 받는다.  
([non-streaming budget flow](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_tools.py#L2898-L2995), [streaming budget flow](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_tools.py#L3028-L3145))

#### .NET
- inner function invocation loop는 `FunctionInvokingChatClient.MaximumIterationsPerRequest`로 제한한다.
- 하지만 approval auto-reentry는 inner agent를 새로 다시 부르므로 per-request cap이 매번 리셋된다.
- 그래서 approval layer가 별도 `MaxAutoApprovalIterations` cap을 둔다.
- loop decorator는 또 별도의 `LoopAgentOptions.MaxIterations`를 갖는다.  
  ([harness inner invocation config](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs#L232-L235), [approval cap rationale](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/ToolApproval/ToolApprovalAgent.cs#L139-L145), [approval cap options](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/ToolApproval/ToolApprovalAgentOptions.cs#L57-L66), [loop cap](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Loop/LoopAgentOptions.cs#L17-L26))

### 상태·영속화
- Python은 `_function_invocation_budget_state`를 `client_kwargs`에 저장해 approval 재진입 사이에 `total_function_calls`와 `attempt_count`를 이어간다.  
  ([budget state key](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_tool_approval.py#L26-L27), [injection](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_tool_approval.py#L387-L389), [core consumption](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_tools.py#L2902-L2911))
- .NET approval cap은 별도 persisted budget state를 노출하지는 않고, same run 안의 auto-reentry 횟수를 제한한다.  
  ([options semantics](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/ToolApproval/ToolApprovalAgentOptions.cs#L50-L68))

### 확장점
- Python은 chat client별 function invocation configuration을 조절할 수 있다.  
  ([example and config](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_tools.py#L1362-L1371))
- .NET은 loop/approval/invocation caps를 각각 독립적으로 튜닝한다.  
  ([HarnessAgentOptions](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgentOptions.cs#L149-L185), [ToolApprovalAgentOptions](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/ToolApproval/ToolApprovalAgentOptions.cs#L50-L68))

### 동시성·스트리밍·취소
- Python은 streaming/non-streaming 모두 같은 budget semantics를 유지한다.  
  ([non-streaming](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_tools.py#L2898-L2995), [streaming](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_tools.py#L3028-L3145))
- .NET auto-approval cap tests는 capped path에서도 whole-run usage aggregation이 유지됨을 보여준다.  
  ([test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/ToolApproval/ToolApprovalAgentTests.cs#L2618-L2779))

### 오류·검증
- Python은 `max_iterations>=1`, `max_function_calls>=1 or None`을 강제한다.  
  ([validation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_tools.py#L1397-L1403))
- .NET은 `MaxAutoApprovalIterations < 1`이면 거절한다.  
  ([test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/ToolApproval/ToolApprovalAgentTests.cs#L2818-L2834))

### 보안
Budget은 sandbox나 approval처럼 직접 권한을 제한하지는 않지만, **비용 폭주와 반복 side effect**를 막는 operational safety control이다. Python 문서도 `max_function_calls`를 runaway tool usage를 막는 primary knob라고 본다.  
([docs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_tools.py#L1336-L1345))

### .NET 구현과 테스트
- approval cap tests는 cap hit 후 final response usage에 prior turn usage가 누락되지 않음을 검증한다.  
  ([test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/ToolApproval/ToolApprovalAgentTests.cs#L2618-L2779))
- default cap이 `DefaultMaxAutoApprovalIterations + 1` inner call을 유발하는 것도 테스트한다.  
  ([test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/ToolApproval/ToolApprovalAgentTests.cs#L2781-L2815))

### Python 구현과 테스트
- tests는 auto-approved re-entry가 shared `max_function_calls` budget을 소비하고, iteration budget exhausted 후에도 approval resolution이 먼저 일어남을 검증한다.  
  ([budget-share test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_tool_approval.py#L814-L865), [iteration-budget test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_tool_approval.py#L869-L914))

### 문서 차이
Cross-SDK semantics가 다르다.

- Python은 **shared budget state**로 approval 재진입을 같은 request budget 안에 묶는다.
- .NET은 **separate cap on approval loop**를 둔다.

코드 우선으로 Java 설계를 할 때는 이 차이를 명시적으로 고르는 것이 필요하다.  
([Python shared state](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_tool_approval.py#L387-L389), [.NET separate cap](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/ToolApproval/ToolApprovalAgentOptions.cs#L50-L68))

### Java 결정
Java는 Python식 shared budget state를 우선 채택하는 편이 낫다. approval 재진입이 같은 logical request budget 안에서 계산되기 때문에 설명 가능성이 더 좋다. 다만 .NET식 outer cap도 추가 운영 안전장치로 둘 수 있다.  
([Python shared budget state](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_tool_approval.py#L387-L389), [.NET cap rationale](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/ToolApproval/ToolApprovalAgent.cs#L139-L145))

### Acceptance scenarios
1. `max_function_calls=1`일 때 auto-approved 재진입 이후 두 번째 tool call은 막혀야 한다.  
   ([Python test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_tool_approval.py#L814-L865))
2. iteration budget이 다 찼더라도 마지막 approval resolution 결과(`function_result`)는 표면화되어야 한다.  
   ([Python test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_tool_approval.py#L869-L914))
3. .NET auto-approval cap hit 후 final response usage는 이전 모든 inner turn usage 합계여야 한다.  
   ([test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/ToolApproval/ToolApprovalAgentTests.cs#L2618-L2779))

---

## 12. Java 결정 요약

### 포함
- Harness assembler
- Todo provider
- Mode provider
- File memory
- Tool approval
- Shared invocation budget state
- Basic loop middleware/predicate

### 제외
- File access
- Structured memory
- Shell auto-wiring
- Background tasks
- Code execution backends

### 권장 기본 정책
1. **skills/file access/background/code execution은 opt-in**
2. **approval은 default on**
3. **file memory namespace는 `session_id` 기반**
4. **loop는 approval pending 시 즉시 탈출**
5. **invocation budget은 approval re-entry와 공유**

이 조합은 Python의 보수적 default와 .NET의 명시적 decorator/middleware layering을 절충한 형태다.  
([Python conservative opt-ins](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_agent.py#L189-L205), [.NET explicit outer layers](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs#L137-L161), [Python shared budget state](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_tool_approval.py#L387-L389))

---

## 13. 구체 acceptance scenarios

### Composition
- 기본 harness 생성 시 built-in provider 집합은 예상된 기본값만 포함해야 한다.  
  ([Python default provider test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_agent.py#L93-L110))

### Loop
- execute mode + open todos 조합이면 autonomous loop가 계속 돌아야 한다.  
  ([Python helper logic](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_loop.py#L876-L937))

### Todo
- add/complete/remove tool이 같은 session state를 공유하고, ID 충돌 없이 누적되어야 한다.  
  ([Python session store](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_todo.py#L245-L285), [.NET tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/Todo/TodoProviderTests.cs#L44-L148))

### Mode
- external mode change는 다음 turn prompt 표면에서 모델에게 재고지되어야 한다.  
  ([Python notification injection](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_mode.py#L315-L325))

### File memory
- 저장 후 index가 rebuild되고 subsequent run에서 injected 되어야 한다.  
  ([Python test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_file_memory.py#L176-L201), [.NET injection path](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/FileMemory/FileMemoryProvider.cs#L144-L155))

### File access
- default는 approval-required, opt-out flag가 해당 그룹에만 영향을 줘야 한다.  
  ([Python provider flags](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_file_access.py#L1302-L1330), [.NET tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/FileAccess/FileAccessProviderTests.cs#L54-L116))

### Structured memory
- extractor/consolidator failure는 user-visible turn failure로 번지지 않아야 한다.  
  ([extract failure handling](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_memory.py#L1412-L1442), [consolidation failure handling](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_memory.py#L1602-L1645))

### Tool approval
- multiple pending approvals는 one-by-one으로 surface되어야 한다.  
  ([Python queue behavior](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_tool_approval.py#L566-L600), [.NET remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/ToolApproval/ToolApprovalAgent.cs#L26-L35))

### Invocation budget
- approval 재진입이 있어도 같은 logical request budget 안에서 function call 수를 소비해야 한다.  
  ([Python shared budget test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_tool_approval.py#L814-L865))

---

## 14. Source inventory

### Source
- Python harness assembly:  
  `python/packages/core/agent_framework/_harness/_agent.py`  
  `python/packages/core/agent_framework/_harness/_loop.py`  
  `python/packages/core/agent_framework/_harness/_todo.py`  
  `python/packages/core/agent_framework/_harness/_mode.py`  
  `python/packages/core/agent_framework/_harness/_file_memory.py`  
  `python/packages/core/agent_framework/_harness/_file_access.py`  
  `python/packages/core/agent_framework/_harness/_memory.py`  
  `python/packages/core/agent_framework/_harness/_tool_approval.py`  
  `python/packages/core/agent_framework/_tools.py`  
  ([agent](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_agent.py), [loop](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_loop.py), [todo](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_todo.py), [mode](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_mode.py), [file memory](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_file_memory.py), [file access](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_file_access.py), [structured memory](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_memory.py), [approval](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_tool_approval.py), [tools core](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_tools.py))

- Python public export/lazy namespace:  
  `python/packages/core/agent_framework/__init__.py`  
  `python/packages/core/agent_framework/tools/__init__.py`  
  ([public exports](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/__init__.py), [tools namespace](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/tools/__init__.py))

- .NET harness assembly and built-ins:  
  `dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs`  
  `dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgentOptions.cs`  
  `dotnet/src/Microsoft.Agents.AI.Harness/ChatClientHarnessExtensions.cs`  
  `dotnet/src/Microsoft.Agents.AI/Harness/Todo/TodoProvider.cs`  
  `dotnet/src/Microsoft.Agents.AI/Harness/AgentMode/AgentModeProvider.cs`  
  `dotnet/src/Microsoft.Agents.AI/Harness/AgentMode/AgentModeProviderOptions.cs`  
  `dotnet/src/Microsoft.Agents.AI/Harness/FileMemory/FileMemoryProvider.cs`  
  `dotnet/src/Microsoft.Agents.AI/Harness/FileAccess/FileAccessProvider.cs`  
  `dotnet/src/Microsoft.Agents.AI/Harness/FileStore/FileSystemAgentFileStore.cs`  
  `dotnet/src/Microsoft.Agents.AI/Harness/FileStore/StorePaths.cs`  
  `dotnet/src/Microsoft.Agents.AI/Harness/Loop/LoopAgent.cs`  
  `dotnet/src/Microsoft.Agents.AI/Harness/Loop/LoopAgentOptions.cs`  
  `dotnet/src/Microsoft.Agents.AI/Harness/Loop/TodoCompletionLoopEvaluator.cs`  
  `dotnet/src/Microsoft.Agents.AI/Harness/Loop/BackgroundTaskCompletionLoopEvaluator.cs`  
  `dotnet/src/Microsoft.Agents.AI/Harness/Loop/AIJudgeLoopEvaluator.cs`  
  `dotnet/src/Microsoft.Agents.AI/Harness/ToolApproval/ToolApprovalAgent.cs`  
  `dotnet/src/Microsoft.Agents.AI/Harness/ToolApproval/ToolApprovalAgentOptions.cs`  
  ([HarnessAgent](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs), [HarnessAgentOptions](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgentOptions.cs), [extension](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/ChatClientHarnessExtensions.cs), [TodoProvider](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Todo/TodoProvider.cs), [AgentModeProvider](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/AgentMode/AgentModeProvider.cs), [FileMemoryProvider](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/FileMemory/FileMemoryProvider.cs), [FileAccessProvider](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/FileAccess/FileAccessProvider.cs), [LoopAgent](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Loop/LoopAgent.cs), [ToolApprovalAgent](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/ToolApproval/ToolApprovalAgent.cs))

### Tests
- Python:  
  `python/packages/core/tests/core/test_harness_agent.py`  
  `python/packages/core/tests/core/test_harness_todo.py`  
  `python/packages/core/tests/core/test_harness_file_memory.py`  
  `python/packages/core/tests/core/test_harness_file_access.py`  
  `python/packages/core/tests/core/test_harness_memory.py`  
  `python/packages/core/tests/core/test_harness_tool_approval.py`  
  ([harness agent tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_agent.py), [todo tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_todo.py), [file memory tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_file_memory.py), [file access tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_file_access.py), [structured memory tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_memory.py), [approval tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_tool_approval.py))

- .NET:  
  `dotnet/tests/Microsoft.Agents.AI.Harness.UnitTests/HarnessAgentTests.cs`  
  `dotnet/tests/Microsoft.Agents.AI.Harness.UnitTests/HarnessAgentOptionsTests.cs`  
  `dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/Loop/LoopAgentTests.cs`  
  `dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/Loop/AIJudgeLoopEvaluatorTests.cs`  
  `dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/Todo/TodoProviderTests.cs`  
  `dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/AgentMode/AgentModeProviderTests.cs`  
  `dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/FileMemory/FileMemoryProviderTests.cs`  
  `dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/FileAccess/FileAccessProviderTests.cs`  
  `dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/ToolApproval/ToolApprovalAgentTests.cs`  
  ([HarnessAgentTests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Harness.UnitTests/HarnessAgentTests.cs), [HarnessAgentOptionsTests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Harness.UnitTests/HarnessAgentOptionsTests.cs), [LoopAgentTests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/Loop/LoopAgentTests.cs), [AIJudgeLoopEvaluatorTests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/Loop/AIJudgeLoopEvaluatorTests.cs), [TodoProviderTests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/Todo/TodoProviderTests.cs), [AgentModeProviderTests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/AgentMode/AgentModeProviderTests.cs), [FileMemoryProviderTests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/FileMemory/FileMemoryProviderTests.cs), [FileAccessProviderTests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/FileAccess/FileAccessProviderTests.cs), [ToolApprovalAgentTests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/ToolApproval/ToolApprovalAgentTests.cs))

### Repo docs / samples
- `python/PACKAGE_STATUS.md`
- `python/packages/tools/README.md`
- `python/samples/02-agents/harness/harness_research.py`
- `python/samples/02-agents/tools/local_shell_with_environment_provider.py`
- `dotnet/samples/02-agents/Harness/Harness_Step05_Loop/Program.cs`
- `dotnet/samples/02-agents/Agents/Agent_Step21_ShellWithEnvironment/Program.cs`  
  ([PACKAGE_STATUS](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md), [tools README](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/README.md), [harness sample](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/samples/02-agents/harness/harness_research.py), [python shell sample](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/samples/02-agents/tools/local_shell_with_environment_provider.py), [.NET loop sample](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/samples/02-agents/Harness/Harness_Step05_Loop/Program.cs), [.NET shell sample](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/samples/02-agents/Agents/Agent_Step21_ShellWithEnvironment/Program.cs))