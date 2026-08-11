# 13. Skills · Background · Code Execution

## 1. 문서 목적과 범위

이 문서는 Microsoft Agent Framework의 다음 기능군을 다룬다.

1. **Skills source/provider와 resource/script 모델**
2. **Background agents / background tasks**
3. **Shell environment provider / shell executors**
4. **LocalCodeAct**
5. **Hyperlight CodeAct**
6. **Monty CodeAct**

본 문서는 harness 조립 규칙이나 compaction 알고리즘을 다시 설명하지 않는다. 다만 이 기능들이 harness와 어디서 접속하는지는 경계로만 기술한다.  
- Python harness는 `skills_provider`/`skills_paths`, `background_agents`, `shell_executor`를 조립에 연결한다.  
  ([Python harness assembly surface](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_agent.py#L302-L344), [Python provider assembly](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_agent.py#L145-L218))
- .NET harness는 skills와 background agents는 built-in/optional provider로 조립하지만, shell과 code execution은 별도 package를 **수동 배선**한다.  
  ([.NET harness built-in/optional providers](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs#L36-L49), [.NET harness actual provider assembly](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs#L287-L344), [.NET shell sample manual wiring](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/samples/02-agents/Agents/Agent_Step21_ShellWithEnvironment/Program.cs#L57-L119))

---

## 2. 공통 비교: 지원 범위와 분리선

### 2.1 Python
Python 쪽 inspected optional execution/connectors는 다음 축으로 나뉜다.

- core package 안의 `SkillsProvider`, `BackgroundAgentsProvider`
- separate `agent-framework-tools`의 shell tooling
- separate `agent-framework-hyperlight`
- separate `agent-framework-monty`

`agent_framework.hyperlight`, `agent_framework.monty`, `agent_framework.tools`는 lazy-loading namespace로만 보인다.  
([core public exports for skills/background](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/__init__.py#L531-L539), [background helper re-exports](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/__init__.py#L602-L603), [hyperlight namespace](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/hyperlight/__init__.py#L3-L35), [monty namespace](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/monty/__init__.py#L3-L35), [tools namespace](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/tools/__init__.py#L3-L48), [package status for hyperlight/monty](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L42-L46))

### 2.2 .NET
.NET 쪽 inspected optional execution/connectors는 다음 축으로 나뉜다.

- `Microsoft.Agents.AI`의 `AgentSkillsProvider`, `BackgroundAgentsProvider`
- `Microsoft.Agents.AI.Tools.Shell`
- `Microsoft.Agents.AI.Hyperlight`
- `Microsoft.Agents.AI.LocalCodeAct`
- `Microsoft.Agents.AI.Mcp`의 MCP skills extension

즉 .NET은 **LocalCodeAct가 별도 package로 존재**하고, Python은 inspected inventory 기준으로 Hyperlight/Monty만 별도 code-execution package로 드러난다.  
([.NET AgentSkillsProvider](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Skills/AgentSkillsProvider.cs#L18-L52), [.NET BackgroundAgentsProvider](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/BackgroundAgents/BackgroundAgentsProvider.cs#L18-L48), [.NET Hyperlight README](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hyperlight/README.md#L7-L17), [.NET LocalCodeAct provider](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/LocalCodeActProvider.cs#L15-L31), [.NET MCP skills builder extension](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Mcp/Skills/AgentSkillsProviderBuilderMcpExtensions.cs#L8-L35))

---

## 3. Skills

### 3.1 목적·경계
Skills는 domain-specific instructions/resources/scripts를 **progressive disclosure**로 제공한다. 즉 기본 system prompt에는 skill name/description만 광고하고, 실제 본문/리소스/스크립트는 필요할 때만 tool로 꺼낸다.  
([Python progressive disclosure](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_skills.py#L1829-L1849), [.NET progressive disclosure](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Skills/AgentSkillsProvider.cs#L22-L31))

이 기능은 검색/메모리/배경 작업과 달리 **작업 지시와 부속 실행 자산**을 다룬다. 또한 script 실행이 포함될 수 있으므로 단순 prompt snippet보다 위험도가 높다.  
([Python skills security note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_skills.py#L1851-L1860), [.NET skills security note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Skills/AgentSkillsProvider.cs#L41-L50))

### 3.2 성숙도
- **Python**
  - `SkillsProvider`, `SkillsSource`, file/in-memory/class-based skill model은 stable public surface처럼 노출된다.  
    ([public exports](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/__init__.py#L531-L539))
  - `MCPSkillResource`, `MCPSkill`, `MCPSkillsSource`는 package status상 experimental이다.  
    ([package status](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L129-L132), [MCPSkill experimental](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_skills.py#L4227-L4228), [MCPSkillsSource experimental](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_skills.py#L4858-L4859))
- **.NET**
  - inspected `AgentSkillsProvider` / `AgentSkillsSource` surface에는 experimental marker가 없다.
  - MCP skills는 separate builder extension과 source package로 분리되어 있다.  
    ([AgentSkillsProvider header](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Skills/AgentSkillsProvider.cs#L18-L52), [AgentSkillsSource header](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Skills/AgentSkillsSource.cs#L10-L33), [MCP builder extension](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Mcp/Skills/AgentSkillsProviderBuilderMcpExtensions.cs#L8-L35))

### 3.3 공개 API·타입
#### Python
- `SkillResource`, `InlineSkillResource`, `_FileSkillResource`
- `SkillScript`, `InlineSkillScript`, `FileSkillScript`
- `Skill`, `InlineSkill`, `ClassSkill`, `FileSkill`
- `SkillFrontmatter`
- `SkillsProvider`
- `SkillsSource`, `FileSkillsSource`, `InMemorySkillsSource`, `FilteringSkillsSource`, `DeduplicatingSkillsSource`, `CachingSkillsSource`, `AggregatingSkillsSource`
- `MCPSkillResource`, `MCPSkill`, `MCPSkillsSource`

([resource/script base types](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_skills.py#L106-L149), [file resource](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_skills.py#L233-L286), [file script](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_skills.py#L457-L539), [frontmatter](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_skills.py#L606-L716), [provider](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_skills.py#L1829-L1915), [source abstraction](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_skills.py#L2728-L2788), [file source](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_skills.py#L2788-L2895), [MCP types](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_skills.py#L4186-L4275), [MCPSkillsSource](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_skills.py#L4858-L4979))

#### .NET
- `AgentSkillResource`
- `AgentSkillScript`
- `AgentFileSkillResource`
- `AgentFileSkillScript`
- `AgentSkillsProvider`
- `AgentSkillsProviderOptions`
- `AgentSkillsSource`
- `AgentFileSkillsSource`
- `AgentSkillsProviderBuilder`
- `UseMcpSkills(...)`

([resource base](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Skills/AgentSkillResource.cs#L10-L43), [script base](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Skills/AgentSkillScript.cs#L11-L51), [file resource](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Skills/File/AgentFileSkillResource.cs#L12-L43), [file script](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Skills/File/AgentFileSkillScript.cs#L11-L71), [provider](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Skills/AgentSkillsProvider.cs#L18-L52), [provider options](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Skills/AgentSkillsProviderOptions.cs#L5-L67), [source abstraction](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Skills/AgentSkillsSource.cs#L10-L41), [file source](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Skills/File/AgentFileSkillsSource.cs#L20-L30), [MCP builder extension](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Mcp/Skills/AgentSkillsProviderBuilderMcpExtensions.cs#L13-L34))

### 3.4 상세 실행 흐름
#### Python
`SkillsProvider.before_run(...)`는 run context에서 `SkillsSourceContext(agent, session)`를 만들고, source에서 skills를 받아 system prompt와 tools를 만든다. skill이 하나도 없으면 아무것도 주입하지 않는다.  
([before_run](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_skills.py#L2392-L2429))

tool 표면은 항상 세 개다.

- `load_skill`
- `read_skill_resource`
- `run_skill_script`

각 도구는 개별 approval disable flag를 가질 수 있다.  
([provider ctor flags](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_skills.py#L2036-L2047), [tool construction](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_skills.py#L2450-L2540))

#### .NET
`AgentSkillsProvider.ProvideAIContextAsync(...)`는 source에서 skills를 가져와 prompt와 tools를 만든다. skill이 없으면 base context로 빠진다.  
([ProvideAIContextAsync](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Skills/AgentSkillsProvider.cs#L279-L291))

tool 표면 역시 세 개다.

- `load_skill`
- `read_skill_resource`
- `run_skill_script`

각 tool은 개별 approval wrapper를 붙일 수 있다.  
([BuildTools](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Skills/AgentSkillsProvider.cs#L314-L340))

### 3.5 상태·영속화
#### Python
- built-in source를 provider가 직접 만들 때만 dedupe/cache wrapper를 붙인다.
- caller-supplied `SkillsSource`는 자동 dedupe/cache하지 않는다. context-aware source의 cross-tenant replay를 막기 위해서다.  
  ([provider ctor caching rules](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_skills.py#L2054-L2061), [disable_caching/cache_refresh_interval semantics](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_skills.py#L2080-L2095))

#### .NET
- convenience constructors / builder가 만든 source pipeline은 provider가 own하고 dispose한다.
- caller-supplied `AgentSkillsSource`는 ownership이 옵션에 따라 caller 쪽에 남을 수 있다.  
  ([ownership remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Skills/AgentSkillsProvider.cs#L33-L39), [Dispose behavior](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Skills/AgentSkillsProvider.cs#L294-L312))

### 3.6 확장점
#### Python
- file/in-memory/custom/MCP source composition
- resource/script decorators
- custom instruction template
- per-tool approval disable
- cache refresh interval
- external MCP source

([source graph](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_skills.py#L2728-L2895), [provider ctor knobs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_skills.py#L2036-L2125), [resource/script decorators on inline/class skills](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_skills.py#L930-L980))

#### .NET
- file skill directories
- builder-level or per-source script runner
- custom source
- filter
- caching pipeline
- MCP source extension

([builder capabilities](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Skills/AgentSkillsProviderBuilder.cs#L21-L27), [file script runner requirement](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Skills/AgentSkillsProviderBuilder.cs#L79-L81), [UseFileScriptRunner](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Skills/AgentSkillsProviderBuilder.cs#L169-L175), [UseMcpSkills](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Mcp/Skills/AgentSkillsProviderBuilderMcpExtensions.cs#L13-L34))

### 3.7 동시성·스트리밍·취소
- Skills는 별도 stream protocol을 만들지 않고 ordinary tool invocation 경로를 따른다.
- Python file/inline script는 sync/async callable을 지원하고, file script는 external runner를 await할 수 있다.  
  ([InlineSkillScript run semantics](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_skills.py#L407-L446), [FileSkillScript run semantics](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_skills.py#L514-L539))
- .NET resource/script base classes 모두 cancellation token을 받아 async I/O/runner에 전달한다.  
  ([AgentSkillResource.ReadAsync](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Skills/AgentSkillResource.cs#L36-L42), [AgentSkillScript.RunAsync](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Skills/AgentSkillScript.cs#L42-L50))

### 3.8 오류·검증
#### Python
- `SkillFrontmatter`는 name/description/compatibility를 생성 시 검증한다.
- `FileSkillScript`는 absolute path를 강제하고 runner가 없으면 run 시 에러를 낸다.
- `_FileSkillResource.read()`는 file 없으면 `ValueError`를 낸다.  
  ([frontmatter validation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_skills.py#L628-L716), [FileSkillScript validation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_skills.py#L474-L539), [_FileSkillResource.read](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_skills.py#L269-L285))
- inline scripts는 `dict` args를 기대하고, string/array mismatch를 명시적으로 거부한다.  
  ([InlineSkillScript arg checks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_skills.py#L432-L446))

#### .NET
- file skill scripts는 runner 없으면 `InvalidOperationException`.
- resource/script/tool name empty checks는 base types/logic이 막는다.
- `ReadSkillResourceAsync`와 `RunSkillScriptAsync`는 skill/resource/script 없음이면 model-readable error string을 반환한다.  
  ([AgentFileSkillScript runner requirement](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Skills/File/AgentFileSkillScript.cs#L49-L63), [resource/script base ctor checks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Skills/AgentSkillResource.cs#L18-L23), [provider error strings](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Skills/AgentSkillsProvider.cs#L361-L449))

### 3.9 보안
#### Python
- file-based metadata는 XML-escape되어 prompt에 들어간다.
- file source는 traversal/symlink escape를 방어한다.
- external skill source는 trust boundary이며, scripts는 approval-required default다.
- MCP archive skills는 zip-slip/link escape/decompression bomb hardening을 한다.  
  ([XML skill content building](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_skills.py#L718-L757), [provider security note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_skills.py#L1847-L1860), [SkillsSource trust-boundary note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_skills.py#L2759-L2771), [MCP skills security note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_skills.py#L4891-L4904))
#### .NET
- skill source 자체가 trust boundary다.
- `run_skill_script`는 untrusted source에서 온 script를 실행할 수 있다.
- `IncludeDetailedErrors=true`는 exception message prompt-injection을 다시 모델에 먹일 수 있으므로 trusted source에서만 쓰라고 경고한다.
- file skills source는 resource/script discovery 시 traversal/symlink escape를 검사한다.  
  ([AgentSkillsSource security note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Skills/AgentSkillsSource.cs#L20-L31), [AgentSkillsProvider security note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Skills/AgentSkillsProvider.cs#L41-L50), [IncludeDetailedErrors warning](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Skills/AgentSkillsProviderOptions.cs#L17-L33), [file source traversal/symlink checks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Skills/File/AgentFileSkillsSource.cs#L314-L427), [script traversal/symlink checks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Skills/File/AgentFileSkillsSource.cs#L439-L549))
- skill tool auto-approval rules도 name collision 위험을 명시한다.  
  ([.NET read-only rule warning](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Skills/AgentSkillsProvider.cs#L78-L107), [all-tools rule warning](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Skills/AgentSkillsProvider.cs#L111-L142))

### 3.10 .NET 구현과 테스트
- provider는 skill이 없으면 아무것도 주입하지 않고, 있으면 prompt+3 tools를 반환한다.  
  ([ProvideAIContextAsync](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Skills/AgentSkillsProvider.cs#L279-L291))
- file source는 search depth, allowed extensions, script/resource filter, symlink/traversal 방어를 구현한다.  
  ([file source header](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Skills/File/AgentFileSkillsSource.cs#L20-L30), [resource scan](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Skills/File/AgentFileSkillsSource.cs#L314-L427), [script scan](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Skills/File/AgentFileSkillsSource.cs#L439-L549))
- 이번 조사에서는 .NET skills unit test 본문까지는 깊게 읽지 않았고, source-based verification 위주로 판단했다.

### 3.11 Python 구현과 테스트
- `test_skills.py`는 file discovery, path normalization, source decorators, context-aware source helpers를 폭넓게 커버한다.  
  ([test file header/imports](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_skills.py#L1-L50), [helper functions for discovery](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_skills.py#L170-L217), [symlink support helper](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_skills.py#L124-L137))

### 3.12 문서 차이
가장 큰 차이는 **default harness enablement**다.

- Python harness: skills는 opt-in
- .NET harness: current working directory 기반 file skills가 기본 on

이는 skills 자체 설계 차이보다 **운영 기본값** 차이로 보는 편이 정확하다.  
([Python harness opt-in](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_agent.py#L200-L205), [.NET harness default skills](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs#L320-L326))

### 3.13 Java 결정
- **MVP 포함 후보**: read-only skills advertise/load/read-resource
- **후속 단계**: script execution, external MCP skills
- **기본값**: harness default on 보다는 explicit opt-in이 더 안전하다.
- **approval**: `run_skill_script`는 기본 approval-required 유지, read-only tools만 별도 auto-approval 가능하도록 분리할 것.  
  ([Python per-tool approval model](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_skills.py#L1862-L1876), [.NET per-tool approval model](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Skills/AgentSkillsProvider.cs#L314-L340))

### 3.14 Acceptance scenarios
1. skill이 없으면 provider는 prompt/tools를 주입하지 않아야 한다.  
   ([.NET behavior](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Skills/AgentSkillsProvider.cs#L279-L285))
2. `load_skill`/`read_skill_resource`/`run_skill_script`는 각각 독립 approval 정책을 가져야 한다.  
   ([Python flags](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_skills.py#L2043-L2045), [Python tool build](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_skills.py#L2481-L2539), [.NET provider options](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Skills/AgentSkillsProviderOptions.cs#L35-L66))
3. file-based skill resource/script discovery는 traversal과 symlink escape를 허용하면 안 된다.  
   ([Python security promise](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_skills.py#L1847-L1849), [.NET file source checks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Skills/File/AgentFileSkillsSource.cs#L391-L427), [.NET script checks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Skills/File/AgentFileSkillsSource.cs#L504-L539))
4. MCP archive skill은 scripts를 runnable로 노출하지 않고 read-only resources로만 노출해야 한다.  
   ([Python MCPSkillsSource docs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_skills.py#L4872-L4878))

### 3.15 Source inventory
- Python  
  - `_skills.py`  
    https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_skills.py
  - `__init__.py`  
    https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/__init__.py
  - `test_skills.py`  
    https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_skills.py
- .NET  
  - `AgentSkillsProvider.cs`  
    https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Skills/AgentSkillsProvider.cs
  - `AgentSkillsSource.cs`  
    https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Skills/AgentSkillsSource.cs
  - `AgentSkillResource.cs`  
    https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Skills/AgentSkillResource.cs
  - `AgentSkillScript.cs`  
    https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Skills/AgentSkillScript.cs
  - `AgentSkillsProviderOptions.cs`  
    https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Skills/AgentSkillsProviderOptions.cs
  - `File/AgentFileSkillsSource.cs`  
    https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Skills/File/AgentFileSkillsSource.cs
  - `File/AgentFileSkillResource.cs`  
    https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Skills/File/AgentFileSkillResource.cs
  - `File/AgentFileSkillScript.cs`  
    https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Skills/File/AgentFileSkillScript.cs
  - `AgentSkillsProviderBuilderMcpExtensions.cs`  
    https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Mcp/Skills/AgentSkillsProviderBuilderMcpExtensions.cs

---

## 4. Background agents / background tasks

### 4.1 목적·경계
Background agents 기능은 parent agent가 child agent에게 작업을 넘기고, 별도 session/task로 비동기 수행하게 만드는 기능이다. 이 기능은 “일반 multi-agent orchestration”이 아니라, harness/documented provider 차원에서 **task registry, 상태 갱신, 결과 회수, 재개, 정리**를 제공한다.  
([Python module summary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_background_agents.py#L3-L8), [.NET provider summary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/BackgroundAgents/BackgroundAgentsProvider.cs#L18-L35))

이 기능은 loop와 자연스럽게 이어질 수 있지만, loop 자체와는 별도다. background task 완료 여부를 loop continuation predicate/evaluator가 읽는 구조다.  
([Python loop helper for background tasks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_loop.py#L796-L860), [.NET background completion evaluator](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Loop/BackgroundTaskCompletionLoopEvaluator.cs#L15-L35))

### 4.2 성숙도
- **Python**: experimental HARNESS feature다.  
  ([provider marker](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_background_agents.py#L52-L53), [package status](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L120-L124))
- **.NET**: `BackgroundAgentsProvider`와 관련 loop evaluator도 experimental이다.  
  ([provider marker](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/BackgroundAgents/BackgroundAgentsProvider.cs#L47-L48), [completion evaluator marker](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Loop/BackgroundTaskCompletionLoopEvaluator.cs#L35-L36))

### 4.3 공개 API·타입
#### Python
- `BackgroundTaskStatus`
- `BackgroundTaskInfo`
- `BackgroundAgentsProvider`
- loop helper: `background_tasks_running`, `background_tasks_running_message`

([types](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_background_agents.py#L43-L64), [provider](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_background_agents.py#L241-L266), [public re-exports](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/__init__.py#L602-L603))

#### .NET
- `BackgroundTaskStatus`
- `BackgroundTaskInfo`
- `BackgroundAgentsProvider`
- `BackgroundTaskCompletionLoopEvaluator`

([provider remarks/tool list](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/BackgroundAgents/BackgroundAgentsProvider.cs#L22-L35), [completion evaluator](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Loop/BackgroundTaskCompletionLoopEvaluator.cs#L15-L35))

### 4.4 상세 실행 흐름
#### Python
`BackgroundAgentsProvider.before_run(...)`는 다음 도구를 주입한다.

- `background_agents_start_task`
- `background_agents_wait_for_first_completion`
- `background_agents_get_task_results`
- `background_agents_get_all_tasks`
- `background_agents_continue_task`
- `background_agents_clear_completed_task`

`start_task`는 child agent마다 dedicated session을 만들고 `asyncio.create_task(...)`로 돌린다. `continue_task`는 완료/실패한 task의 기존 session을 재사용해 재실행한다. `clear_completed_task`는 runtime reference와 session reference를 제거한다.  
([before_run start](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_background_agents.py#L315-L363), [wait/get/results/all](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_background_agents.py#L365-L447), [continue/clear](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_background_agents.py#L448-L536))

#### .NET
.NET도 같은 6-tool surface를 제공하고, task metadata는 serializable state에, in-flight task/session handles는 runtime state에 둔다. `GetIncompleteTasks(...)`는 현재 runtime task 상태를 refresh한 뒤 running tasks만 돌려준다.  
([provider surface](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/BackgroundAgents/BackgroundAgentsProvider.cs#L27-L35), [runtime refresh](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/BackgroundAgents/BackgroundAgentsProvider.cs#L120-L149), [task finalization](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/BackgroundAgents/BackgroundAgentsProvider.cs#L201-L259))

### 4.5 상태·영속화
#### Python
- serializable provider state: `next_task_id`, `tasks`
- non-serializable runtime state: `in_flight_tasks`, `background_sessions`
- provider instance가 사라지거나 restart되면 in-flight reference가 없으므로 running task는 `LOST`로 바뀐다.  
  ([provider state init/save](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_background_agents.py#L163-L186), [runtime state type](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_background_agents.py#L111-L116), [LOST transition](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_background_agents.py#L219-L231), [provider instance loss comment](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_background_agents.py#L302-L306))

#### .NET
- serializable `BackgroundAgentState`
- `[JsonIgnore]` runtime state for `Task<AgentResponse>` and `AgentSession`
- deserialization 후 empty runtime state가 생기므로 이전 running tasks는 `Lost`로 간주된다.  
  ([runtime state remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/BackgroundAgents/BackgroundAgentRuntimeState.cs#L9-L17), [runtime fields](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/BackgroundAgents/BackgroundAgentRuntimeState.cs#L20-L31))

### 4.6 확장점
#### Python
- constructor `instructions` override
- available agent list text injection
- arbitrary `SupportsAgentRun` collection  
  ([ctor](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_background_agents.py#L268-L300))

#### .NET
- custom agent list formatting과 instruction override를 `BackgroundAgentsProviderOptions`로 줄 수 있다.  
  ([provider option usage](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/BackgroundAgents/BackgroundAgentsProvider.cs#L81-L102))

### 4.7 동시성·스트리밍·취소
- Python은 genuine concurrent `asyncio.Task`를 사용한다.  
  ([task start](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_background_agents.py#L352-L358), [continue starts new task](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_background_agents.py#L475-L483))
- .NET도 concurrent task model이지만, provider tests는 mostly non-streaming task-state behavior를 검증한다.  
  ([provider task runtime](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/BackgroundAgents/BackgroundAgentsProvider.cs#L120-L149))
- 별도의 streaming task-update protocol은 없다. background tasks는 tool-polled 상태 모델이다.  
  ([Python tool polling surface](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_background_agents.py#L365-L447), [.NET tool list](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/BackgroundAgents/BackgroundAgentsProvider.cs#L27-L35))

### 4.8 오류·검증
- empty agent list, empty name, duplicate name(case-insensitive) 거절  
  ([Python validation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_background_agents.py#L129-L149), [.NET tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/BackgroundAgents/BackgroundAgentsProviderTests.cs#L20-L97))
- unknown task/unknown agent/still running/LOST/no session 각 상태에서 explicit text error를 돌린다.  
  ([Python result/continue/clear errors](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_background_agents.py#L416-L510), [.NET tests for running/failed/continue/clear](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/BackgroundAgents/BackgroundAgentsProviderTests.cs#L307-L443), [continue/clear assertions](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/BackgroundAgents/BackgroundAgentsProviderTests.cs#L559-L732))

### 4.9 보안
Background agents는 **child agent 자체가 trust boundary**다. parent agent가 넘기는 텍스트와 child output 모두 untrusted data channel이 될 수 있고, child tools/upstream model/system prompt가 손상되면 exfiltration이나 indirect prompt injection이 가능하다.  
([Python security note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_background_agents.py#L258-L266), [.NET security note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/BackgroundAgents/BackgroundAgentsProvider.cs#L38-L45))

### 4.10 .NET 구현과 테스트
- constructor validation, 6-tool injection, agent info in instructions, running/completed/failed/continue/clear semantics가 테스트된다.  
  ([constructor/injection tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/BackgroundAgents/BackgroundAgentsProviderTests.cs#L20-L121), [runtime semantics tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/BackgroundAgents/BackgroundAgentsProviderTests.cs#L244-L520), [continue/clear tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/BackgroundAgents/BackgroundAgentsProviderTests.cs#L559-L732))

### 4.11 Python 구현과 테스트
- tests는 same set의 constructor validation/tool injection/run-state behaviors를 고정한다.  
  ([tests header and constructor coverage](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_background_agents.py#L96-L145), [wait/result tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_background_agents.py#L249-L319))

### 4.12 문서 차이
큰 문서-코드 불일치는 보이지 않았다. 다만 code 기준으로 보면 restart/deserialization 이후 **runtime handle loss → LOST 상태 전이**가 매우 중요한 operational contract인데, docs보다 source comments가 더 명시적이다.  
([Python runtime-loss comment](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_background_agents.py#L302-L306), [.NET runtime-loss remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/BackgroundAgents/BackgroundAgentRuntimeState.cs#L9-L17))

### 4.13 Java 결정
- **MVP 제외**
- 이유:
  - experimental
  - persistent runtime handle/session handle 유지가 필요
  - restart 이후 LOST semantics를 잘 설계해야 함
  - child agent trust boundary가 크다

후속 단계에서 넣더라도 **polling-style task registry**로 시작하고, stream-style live updates는 나중에 붙이는 편이 낫다.  
([experimental status](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L120-L124), [runtime-state split](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/BackgroundAgents/BackgroundAgentRuntimeState.cs#L20-L31))

### 4.14 Acceptance scenarios
1. running task result 조회는 “still running”을 반환해야 한다.  
   ([.NET test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/BackgroundAgents/BackgroundAgentsProviderTests.cs#L348-L379))
2. failed task result 조회는 failure text를 반환해야 한다.  
   ([.NET test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/BackgroundAgents/BackgroundAgentsProviderTests.cs#L402-L443))
3. running task는 clear/continue 대상이 되면 안 된다.  
   ([Python provider logic](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_background_agents.py#L462-L499), [.NET tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/BackgroundAgents/BackgroundAgentsProviderTests.cs#L600-L732))
4. provider restart로 runtime refs가 사라지면 task는 LOST로 간주되어야 한다.  
   ([Python LOST transition](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_background_agents.py#L219-L231), [.NET runtime-loss remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/BackgroundAgents/BackgroundAgentRuntimeState.cs#L9-L17))

### 4.15 Source inventory
- Python  
  - `_background_agents.py`  
    https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_background_agents.py
  - `test_harness_background_agents.py`  
    https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_background_agents.py
- .NET  
  - `BackgroundAgentsProvider.cs`  
    https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/BackgroundAgents/BackgroundAgentsProvider.cs
  - `BackgroundAgentRuntimeState.cs`  
    https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/BackgroundAgents/BackgroundAgentRuntimeState.cs
  - `BackgroundTaskCompletionLoopEvaluator.cs`  
    https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Loop/BackgroundTaskCompletionLoopEvaluator.cs
  - `BackgroundAgentsProviderTests.cs`  
    https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/BackgroundAgents/BackgroundAgentsProviderTests.cs

---

## 5. Shell environment / shell executors

### 5.1 목적·경계
Shell tooling은 “모델이 shell command를 emit하고 실제 환경에서 실행한다”는 capability를 제공한다. 여기서 두 층이 분리된다.

1. **Shell executor/tool**: 실제 명령 실행
2. **ShellEnvironmentProvider**: 그 shell family/OS/installed CLI 정보를 system prompt에 주입

즉 environment provider는 tool이 아니라 prompt steering layer다.  
([Python ShellEnvironmentProvider docs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/README.md#L87-L119), [.NET ShellEnvironmentProvider summary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellEnvironmentProvider.cs#L14-L19), [.NET why instructions not messages](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellEnvironmentProvider.cs#L46-L57))

### 5.2 성숙도
- **Python**
  - `agent-framework-tools` package는 beta다.  
    ([README](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/README.md#L1-L10))
  - harness에서 shell wiring을 쓰는 `shell_executor` feature는 pre-release warning 대상이다.  
    ([Python harness warning logic](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_agent.py#L273-L299))
- **.NET**
  - shell은 separate package `Microsoft.Agents.AI.Tools.Shell`이다.
  - harness built-in이 아니고 manual composition이 기준이다.  
    ([package description](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/Microsoft.Agents.AI.Tools.Shell.csproj#L23-L23), [.NET harness no shell auto-wiring](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs#L287-L344))

### 5.3 공개 API·타입
#### Python
- `LocalShellTool`
- `DockerShellTool`
- `ShellEnvironmentProvider`
- `ShellEnvironmentProviderOptions`
- `ShellEnvironmentSnapshot`
- `ShellPolicy`
- `ShellExecutor`
- `ShellFamily`, `ShellMode`  
  ([lazy exports](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/tools/__init__.py#L11-L31))

#### .NET
- `ShellExecutor`
- `LocalShellExecutor`
- `DockerShellExecutor`
- `ShellEnvironmentProvider`
- `ShellEnvironmentProviderOptions`
- `ShellEnvironmentSnapshot`  
  ([ShellExecutor base](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellExecutor.cs#L10-L18), [ShellEnvironmentProviderOptions](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellEnvironmentProviderOptions.cs#L9-L40))

### 5.4 상세 실행 흐름
#### Python
- `LocalShellTool` / `DockerShellTool`는 ordinary `FunctionTool`로 에이전트에 연결된다.
- `ShellEnvironmentProvider.before_run(...)`는 first-call probe를 cache하고 formatter로 instructions block을 만든다.  
  ([LocalShellTool usage contract](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_tool.py#L65-L139), [ShellEnvironmentProvider before_run and cache](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_environment.py#L103-L174))
- harness를 쓰면 `shell_executor`를 받은 `create_harness_agent(...)`가 shell tool과 provider를 자동 추가한다.  
  ([assembly helper](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_agent.py#L221-L262))

#### .NET
- `ShellExecutor.AsAIFunction(...)`가 model-facing tool을 만든다.
- `ShellEnvironmentProvider.ProvideAIContextAsync(...)`는 shell+cwd+tool versions를 probe한 snapshot을 instructions로 넣는다.
- caller는 이 둘을 일반 agent/context provider/tool surface에 수동으로 연결한다.  
  ([ShellExecutor.AsAIFunction contract](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellExecutor.cs#L69-L88), [ShellEnvironmentProvider flow](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellEnvironmentProvider.cs#L98-L157), [sample manual composition](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/samples/02-agents/Agents/Agent_Step21_ShellWithEnvironment/Program.cs#L57-L119))

### 5.5 상태·영속화
- **Local shell persistent mode**는 cwd/export/history/background jobs를 같은 shell process에 누적한다. 따라서 single-session ownership이 강하게 요구된다.  
  ([Python LocalShellTool single-session ownership](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_tool.py#L82-L92), [.NET ShellExecutor single-session ownership](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellExecutor.cs#L34-L47), [.NET LocalShellExecutor single-session ownership](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/LocalShellExecutor.cs#L32-L40))
- **Docker shell persistent mode**도 long-lived container + shell REPL state를 누적하므로 same ownership rule을 가진다.  
  ([Python DockerShellTool single-session ownership](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_docker.py#L27-L39), [.NET DockerShellExecutor single-session ownership](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/DockerShellExecutor.cs#L46-L57))
- environment snapshot은 Python에선 provider lifetime cache, .NET에선 first-call wins task cache + explicit refresh다.  
  ([Python snapshot cache](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_environment.py#L131-L174), [.NET snapshot task cache](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellEnvironmentProvider.cs#L61-L127))

### 5.6 확장점
#### Python
- shell override
- workdir / confine_workdir
- env / clean_env
- policy
- timeout
- audit hook
- instructions formatter
- docker runtime/image/network/user/mount/read_only_root/custom extra_run_args

([LocalShellTool args](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_tool.py#L94-L139), [ShellEnvironmentProviderOptions](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_environment.py#L61-L85), [DockerShellTool args](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_docker.py#L273-L353))
#### .NET
- shell function name/description/approval
- environment provider override family / probe tools / formatter
- docker runtime flags via constructor options  
  ([ShellExecutor.AsAIFunction](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellExecutor.cs#L69-L88), [ShellEnvironmentProviderOptions](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellEnvironmentProviderOptions.cs#L14-L40), [DockerShellExecutor description args](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/DockerShellExecutor.cs#L267-L301))

### 5.7 동시성·스트리밍·취소
- Python persistent LocalShellTool lazily creates one `ShellSession` protected by an async lock.  
  ([session lock](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_tool.py#L189-L196), [persistent session start/close](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_tool.py#L200-L228))
- .NET shell executors follow async subprocess/container execution and timeout/cancellation token propagation.  
  ([ShellExecutor.RunAsync contract](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellExecutor.cs#L60-L67), [.NET environment provider propagates caller cancellation but swallows timeout-linked cancellation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellEnvironmentProvider.cs#L29-L44))
- 별도의 LLM streaming protocol은 없고 tool invocation result text만 일반 tool result path를 따른다.

### 5.8 오류·검증
- Python LocalShellTool는 `approval_mode="never_require"`이면 `acknowledge_unsafe=True` 없이는 거부한다.  
  ([constructor check](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_tool.py#L157-L167))
- .NET `LocalShellExecutor.AsAIFunction(requireApproval:false)`도 `AcknowledgeUnsafe` 없이는 거부한다.  
  ([source](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/LocalShellExecutor.cs#L336-L343), [test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Tools.Shell.UnitTests/LocalShellExecutorTests.cs#L245-L255))
- .NET persistent `cmd.exe`는 sentinel-friendly REPL이 없어 unsupported다.  
  ([source](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/LocalShellExecutor.cs#L111-L115), [test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Tools.Shell.UnitTests/LocalShellExecutorTests.cs#L263-L269))
- Python/.NET ShellEnvironmentProvider는 invalid probe tool name을 실행하지 않고 null 처리한다.  
  ([Python tool-name validation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_environment.py#L222-L240), [.NET tool-name validation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellEnvironmentProvider.cs#L188-L212), [.NET invalid-name test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Tools.Shell.UnitTests/ShellEnvironmentProviderTests.cs#L164-L181))
- Python DockerShellTool blocks `extra_run_args` flags that would dismantle isolation defaults.  
  ([blocked flags](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_docker.py#L75-L127))

### 5.9 보안
- Python `LocalShellTool`은 **not a sandbox**이며 approval이 유일한 built-in security boundary다.  
  ([README safety](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/README.md#L43-L67), [constructor warning](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_tool.py#L127-L137))
- .NET `LocalShellExecutor`도 approval-in-the-loop를 security boundary로 본다.  
  ([remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/LocalShellExecutor.cs#L43-L49), [AsAIFunction remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/LocalShellExecutor.cs#L318-L325))
- Python `DockerShellTool`은 container를 intended boundary로 삼지만, host/runtime/image/flags에 의존한다.  
  ([module docstring](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_docker.py#L3-L20), [arg docs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_docker.py#L317-L321))
- .NET `DockerShellExecutor`는 restrictive baseline일 뿐 sole defense가 아니라고 말한다.  
  ([remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/DockerShellExecutor.cs#L22-L36))
- name-based auto-approval collision risk는 shell tools에도 동일하게 존재한다.  
  ([.NET LocalShellExecutor warning](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/LocalShellExecutor.cs#L329-L334), [.NET DockerShellExecutor warning](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/DockerShellExecutor.cs#L259-L264))

### 5.10 .NET 구현과 테스트
- `ShellEnvironmentProviderTests`는 PowerShell/Posix detection, custom formatter, missing tool nulling, duplicate probe dedup, stderr fallback, invalid tool-name suppression을 검증한다.  
  ([tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Tools.Shell.UnitTests/ShellEnvironmentProviderTests.cs#L20-L220))
- `LocalShellExecutorTests`는 default policy empty, denylist guardrail nature, timeout, requireApproval opt-out ack, persistent cmd.exe rejection을 검증한다.  
  ([tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Tools.Shell.UnitTests/LocalShellExecutorTests.cs#L147-L210), [unsafe ack tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Tools.Shell.UnitTests/LocalShellExecutorTests.cs#L245-L269))

### 5.11 Python 구현과 테스트
- `test_local_shell_tool.py`는 stateless/persistent mode, timeouts, policy, confine_workdir, approval mode를 검증한다.  
  ([tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/tests/test_local_shell_tool.py#L35-L321))
- `test_security.py`는 `ShellPolicy`가 guardrail일 뿐 security boundary가 아님을 문서화된 테스트로 유지한다.  
  ([tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/tests/test_security.py#L57-L164))

### 5.12 문서 차이
가장 중요한 doc/code 차이는 Python README의 wording이다.

- README는 “once per session”이라고 설명하지만, 구현은 provider instance에 cached snapshot task를 들고 있어 **provider lifetime cache**에 더 가깝다.  
  ([README wording](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/README.md#L91-L95), [Python implementation cache](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_environment.py#L131-L174))
- .NET docs/comments도 “once per session”이라고 말하지만 실제 구현은 provider-level cached task + refresh model이다.  
  ([.NET summary wording](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellEnvironmentProvider.cs#L15-L18), [.NET implementation cache](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellEnvironmentProvider.cs#L61-L127))

### 5.13 Java 결정
- **MVP 본체에서는 제외**, 별도 tools module로 분리
- Local shell은 default approval-required + explicit unsafe ack 필요
- Docker/container tier는 separate module
- Shell environment provider는 lightweight라 shell module에 함께 포함 가능  
  ([Python local shell unsafe ack](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_tool.py#L157-L167), [.NET LocalShellExecutor opt-out check](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/LocalShellExecutor.cs#L336-L343))

### 5.14 Acceptance scenarios
1. local shell에서 approval disable은 explicit unsafe ack 없이는 실패해야 한다.  
   ([Python](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_tool.py#L157-L167), [.NET](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Tools.Shell.UnitTests/LocalShellExecutorTests.cs#L245-L255))
2. invalid probe tool name은 shell injection 없이 null로 기록되어야 한다.  
   ([.NET test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Tools.Shell.UnitTests/ShellEnvironmentProviderTests.cs#L164-L181), [Python implementation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_environment.py#L222-L240))
3. persistent local shell은 cross-user 공유 대상이 아니어야 한다.  
   ([Python docs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_tool.py#L82-L92), [.NET docs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellExecutor.cs#L34-L47))
4. Docker extra args는 isolation-breaking flags를 construction time에 거부해야 한다.  
   ([Python source](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_docker.py#L75-L127))

### 5.15 Source inventory
- Python  
  - `python/packages/tools/README.md`  
    https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/README.md
  - `python/packages/tools/agent_framework_tools/shell/_tool.py`  
    https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_tool.py
  - `python/packages/tools/agent_framework_tools/shell/_environment.py`  
    https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_environment.py
  - `python/packages/tools/agent_framework_tools/shell/_docker.py`  
    https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_docker.py
  - `python/packages/tools/tests/test_local_shell_tool.py`  
    https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/tests/test_local_shell_tool.py
  - `python/packages/tools/tests/test_security.py`  
    https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/tests/test_security.py
- .NET  
  - `dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellExecutor.cs`  
    https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellExecutor.cs
  - `dotnet/src/Microsoft.Agents.AI.Tools.Shell/LocalShellExecutor.cs`  
    https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/LocalShellExecutor.cs
  - `dotnet/src/Microsoft.Agents.AI.Tools.Shell/DockerShellExecutor.cs`  
    https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/DockerShellExecutor.cs
  - `dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellEnvironmentProvider.cs`  
    https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellEnvironmentProvider.cs
  - `dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellEnvironmentProviderOptions.cs`  
    https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellEnvironmentProviderOptions.cs
  - `dotnet/tests/Microsoft.Agents.AI.Tools.Shell.UnitTests/ShellEnvironmentProviderTests.cs`  
    https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Tools.Shell.UnitTests/ShellEnvironmentProviderTests.cs
  - `dotnet/tests/Microsoft.Agents.AI.Tools.Shell.UnitTests/LocalShellExecutorTests.cs`  
    https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Tools.Shell.UnitTests/LocalShellExecutorTests.cs

---

## 6. LocalCodeAct (.NET only)

### 6.1 목적·경계
LocalCodeAct는 Hyperlight/Monty 같은 isolated runtime이 아니라, **로컬 Python subprocess**에서 `execute_code`를 제공하는 CodeAct surface다. 목적은 sandboxed runtime이 이미 바깥 infra에서 보장되는 환경(컨테이너/VM/Foundry hosted agents 등)에서 familiar CodeAct provider 패턴을 유지하는 것이다.  
([provider summary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/LocalCodeActProvider.cs#L15-L31), [README warning](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/README.md#L5-L14))

이 기능은 **보안 sandbox가 아니다**. Hyperlight/Monty와 같은 “runtime 자체가 boundary”인 모델과 달리, LocalCodeAct는 이미 외부에서 격리된 환경 안에 있어야 한다.  
([provider security note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/LocalCodeActProvider.cs#L21-L29), [ProcessExecutionLimits remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/ProcessExecutionLimits.cs#L8-L12))

### 6.2 성숙도
- package README는 **preview package**라고 명시한다.  
  ([README status](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/README.md#L16-L23))
- Python-side inspected package inventory에는 LocalCodeAct counterpart가 보이지 않고, optional code-execution packages는 Hyperlight와 Monty로 드러난다.  
  ([Python package status only lists hyperlight/monty](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L42-L46), [Python hyperlight namespace](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/hyperlight/__init__.py#L11-L18), [Python monty namespace](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/monty/__init__.py#L11-L17))

### 6.3 공개 API·타입
- `LocalCodeActProvider`
- `LocalCodeActProviderOptions`
- `LocalExecuteCodeFunction`
- `ProcessExecutionLimits`
- `FileMount`
- `FileMountMode`
- `CodeValidationException`  
  ([provider](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/LocalCodeActProvider.cs#L15-L31), [options](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/LocalCodeActProviderOptions.cs#L8-L90), [standalone function](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/LocalExecuteCodeFunction.cs#L16-L24), [limits](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/ProcessExecutionLimits.cs#L5-L35))

### 6.4 상세 실행 흐름
#### Provider path
1. provider는 tool registry와 file mounts를 concurrent dictionary에 유지한다.
2. `ProvideAIContextAsync(...)`에서 현재 tools/mounts를 snapshot으로 캡처한다.
3. snapshot 기반 `ExecuteCodeFunction`을 1개 만들고 instructions + tools를 반환한다.  
([provider state and tool/mount registries](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/LocalCodeActProvider.cs#L31-L42), [ProvideAIContextAsync](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/LocalCodeActProvider.cs#L181-L202))

#### Standalone function path
1. constructor에서 validator / executor / tools / file mounts를 고정 snapshot으로 잡는다.
2. invocation 때 optional validation → writable mounts snapshot → subprocess run → written files capture → final `AIContent` list assemble 순으로 진행한다.  
([LocalExecuteCodeFunction ctor](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/LocalExecuteCodeFunction.cs#L32-L76), [CodeExecutor flow](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/Internal/CodeExecutor.cs#L10-L75))

### 6.5 상태·영속화
- provider는 per-run mutable interpreter state를 유지하지 않는다.
- `CodeExecutor.RunSnapshot`이 invocation 시작 시 tool/mount view를 고정한다.
- captured files는 **read-write mounts 아래 새로 생긴 파일**만 대상으로 하고, 기존 파일 수정은 capture 대상이 아니다.  
  ([RunSnapshot](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/Internal/CodeExecutor.cs#L39-L51), [capture after run](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/Internal/CodeExecutor.cs#L60-L75), [README capture semantics](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/README.md#L50-L54))

### 6.6 확장점
- custom `ExecutionLimits`
- provider-owned tools
- file mounts
- explicit environment dictionary
- working directory
- custom runner/validator script path
- validation allow/block lists for imports/builtins
- validation disable  
  ([options surface](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/LocalCodeActProviderOptions.cs#L13-L90), [README customization sections](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/README.md#L97-L170))

### 6.7 동시성·스트리밍·취소
- `LocalCodeActProvider`는 run start에 immutable snapshot을 만들어 later mutation과 분리한다.  
  ([snapshot creation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/LocalCodeActProvider.cs#L186-L193))
- `LocalExecuteCodeFunction`도 construction-time snapshot이 immutable이다.  
  ([remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/LocalExecuteCodeFunction.cs#L19-L23))
- Code validation, subprocess I/O, execution 모두 cancellation token을 받는다.  
  ([validator creation with timeout](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/LocalExecuteCodeFunction.cs#L43-L55), [executor ExecuteAsync signature](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/Internal/CodeExecutor.cs#L53-L75))
- 별도 streaming delta protocol은 없다. output은 final `AIContent` list로 돌아온다.  
  ([BuildContents](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/Internal/CodeExecutor.cs#L77-L105))

### 6.8 오류·검증
- Python executable path는 provider/function constructor 모두 필수다.  
  ([source checks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/LocalCodeActProvider.cs#L47-L50), [function checks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/LocalExecuteCodeFunction.cs#L35-L38), [tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.LocalCodeAct.UnitTests/LocalCodeActProviderOptionsTests.cs#L9-L23))
- validation은 default on이고, disabled가 아니면 validator subprocess를 사용한다.  
  ([provider ctor](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/LocalCodeActProvider.cs#L52-L67), [options default false test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.LocalCodeAct.UnitTests/LocalCodeActProviderOptionsTests.cs#L25-L30))
- process limits cap stdout/stderr/result/captured files sizes를 가진다.  
  ([ProcessExecutionLimits](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/ProcessExecutionLimits.cs#L13-L35))

### 6.9 보안
- LocalCodeAct는 **sandbox가 아님**이 가장 중요한 계약이다. AST validator와 resource limits는 defense-in-depth일 뿐이다.  
  ([provider security note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/LocalCodeActProvider.cs#L21-L29), [README warning](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/README.md#L5-L14), [README what it controls vs does not protect](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/README.md#L40-L69))
- subprocess는 host env를 기본 상속하지 않도록 option contract를 두고, explicit env dict를 요구한다.  
  ([options env semantics](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/LocalCodeActProviderOptions.cs#L26-L38), [README env section](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/README.md#L156-L170))
- host tools exposed via `await call_tool(...)`는 provider-owned registry에만 국한된다.  
  ([README host tools](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/README.md#L71-L95))

### 6.10 .NET 구현과 테스트
- provider tests는 execute_code tool injection, tool/mount registry mutation, clear semantics를 검증한다.  
  ([tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.LocalCodeAct.UnitTests/LocalCodeActProviderTests.cs#L12-L114))
- options tests는 python executable requirement와 validation default를 고정한다.  
  ([tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.LocalCodeAct.UnitTests/LocalCodeActProviderOptionsTests.cs#L9-L30))

### 6.11 Python 구현과 테스트
- inspected Python package inventory에는 LocalCodeAct counterpart를 찾지 못했다. Python optional code-execution package inventory는 Hyperlight와 Monty로 나타난다.  
  ([Python package status](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L42-L46), [Python hyperlight namespace](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/hyperlight/__init__.py#L11-L18), [Python monty namespace](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/monty/__init__.py#L11-L17))

### 6.12 문서 차이
문서와 코드의 핵심 정렬점은 “not a sandbox”다. 큰 모순은 보이지 않았다. 다만 README가 “isolated environment”라고 말할 때의 의미는 sandbox isolation이 아니라 **subprocess/env inheritance control**이라는 점을 코드와 함께 읽어야 한다.  
([README controls](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/README.md#L40-L54), [options Environment semantics](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/LocalCodeActProviderOptions.cs#L26-L38))

### 6.13 Java 결정
- **MVP 제외**
- 이유:
  - 실제 sandbox boundary를 제공하지 않음
  - Java 쪽에서 비슷한 기능을 만들면 sandbox처럼 오해받기 쉬움
  - Hyperlight류 backend보다 운영 리스크가 큼

후속 optional module로 고려한다면, “external sandbox/VM/container가 이미 있다”는 strong prerequisite를 타입/문서에 강하게 박아야 한다.  
([README warning](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/README.md#L5-L14), [ProcessExecutionLimits remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/ProcessExecutionLimits.cs#L8-L12))

### 6.14 Acceptance scenarios
1. provider/function constructor는 Python executable path가 없으면 실패해야 한다.  
   ([tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.LocalCodeAct.UnitTests/LocalCodeActProviderOptionsTests.cs#L9-L23))
2. `ValidationDisabled=false`가 기본이어야 한다.  
   ([test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.LocalCodeAct.UnitTests/LocalCodeActProviderOptionsTests.cs#L25-L30))
3. provider mutation 후 next run snapshot에는 변경이 반영되어야 하지만, 이미 생성된 standalone function snapshot은 immutable이어야 한다.  
   ([provider run snapshot creation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/LocalCodeActProvider.cs#L186-L193), [standalone function immutable capture](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/LocalExecuteCodeFunction.cs#L19-L23))
4. new files under read-write mounts만 capture되어야 한다.  
   ([README capture semantics](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/README.md#L50-L54), [CodeExecutor capture call](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/Internal/CodeExecutor.cs#L60-L75))

### 6.15 Source inventory
- `.NET`
  - `LocalCodeActProvider.cs`  
    https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/LocalCodeActProvider.cs
  - `LocalExecuteCodeFunction.cs`  
    https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/LocalExecuteCodeFunction.cs
  - `LocalCodeActProviderOptions.cs`  
    https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/LocalCodeActProviderOptions.cs
  - `ProcessExecutionLimits.cs`  
    https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/ProcessExecutionLimits.cs
  - `Internal/CodeExecutor.cs`  
    https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/Internal/CodeExecutor.cs
  - `README.md`  
    https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/README.md
  - `LocalCodeActProviderTests.cs`  
    https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.LocalCodeAct.UnitTests/LocalCodeActProviderTests.cs
  - `LocalCodeActProviderOptionsTests.cs`  
    https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.LocalCodeAct.UnitTests/LocalCodeActProviderOptionsTests.cs
- `Python`
  - no inspected LocalCodeAct counterpart in package inventory; compared using:
    - `python/PACKAGE_STATUS.md`
    - `python/packages/core/agent_framework/hyperlight/__init__.py`
    - `python/packages/core/agent_framework/monty/__init__.py`

---

## 7. Hyperlight CodeAct

### 7.1 목적·경계
Hyperlight는 VM-isolated sandbox 위에서 `execute_code`를 제공하는 **backend-specific CodeAct provider**다. direct agent tool surface와는 분리된 provider-owned tool registry를 가지며, guest code는 `call_tool(...)`로만 provider-owned tools에 접근한다.  
([.NET Hyperlight README overview](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hyperlight/README.md#L3-L17), [Python README provider-owned tools](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hyperlight/README.md#L18-L24), [.NET provider remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hyperlight/HyperlightCodeActProvider.cs#L19-L30))

이 문서는 sandbox backend로서의 Hyperlight를 다루며, harness 자체 조립과는 분리한다.

### 7.2 성숙도
- **Python**: beta package다.  
  ([package status](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L42-L42), [pyproject classifier](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hyperlight/pyproject.toml#L13-L29))
- **.NET**: README에서 preview라고 명시한다.  
  ([README status](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hyperlight/README.md#L38-L41))

### 7.3 공개 API·타입
#### Python
- `HyperlightCodeActProvider`
- `HyperlightExecuteCodeTool`
- `AllowedDomain`, `AllowedDomainInput`
- `FileMount`, `FileMountInput`  
  ([lazy namespace](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/hyperlight/__init__.py#L11-L18), [provider type](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hyperlight/agent_framework_hyperlight/_provider.py#L18-L48))
#### .NET
- `HyperlightCodeActProvider`
- `HyperlightCodeActProviderOptions`
- `HyperlightExecuteCodeFunction`
- `AllowedDomain`
- `FileMount`
- `CodeActApprovalMode`  
  ([README entry points](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hyperlight/README.md#L7-L17), [options type](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hyperlight/HyperlightCodeActProviderOptions.cs#L10-L18), [provider type](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hyperlight/HyperlightCodeActProvider.cs#L14-L46))

### 7.4 상세 실행 흐름
#### Python
- provider는 run마다 `create_run_tool()`로 run-scoped execute_code tool을 만들고, instructions + tool을 session context에 넣는다.  
  ([provider before_run](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hyperlight/agent_framework_hyperlight/_provider.py#L101-L114))
- execute_code tool은 backend/module/module_path/tools/mounts/allowed_domains를 snapshot하고 approval mode를 재계산한다.  
  ([ctor and state fields](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hyperlight/agent_framework_hyperlight/_execute_code_tool.py#L1090-L1126))

#### .NET
- provider는 `ProvideAIContextAsync(...)`에서 run-scoped `execute_code` tool과 instructions를 돌려준다.
- `CodeActApprovalMode`와 provider-owned tool registry snapshot으로 effective approval을 계산한다.  
  ([provider remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hyperlight/HyperlightCodeActProvider.cs#L19-L44), [approval computation tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hyperlight.UnitTests/ApprovalComputationTests.cs#L9-L61), [ProvideAIContext tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hyperlight.UnitTests/ProvideAIContextTests.cs#L17-L50))

### 7.5 상태·영속화
- Python Hyperlight runtime caches sandbox workers/snapshots by config key, but mutable unsendable sandbox objects never leak out of owner thread.  
  ([sandbox worker actor model](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hyperlight/agent_framework_hyperlight/_execute_code_tool.py#L101-L126), [worker execute/dispose](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hyperlight/agent_framework_hyperlight/_execute_code_tool.py#L179-L280))
- .NET Hyperlight README states snapshot/restore per run and fixed state key to prevent duplicate provider registration.  
  ([README snapshot/restore](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hyperlight/README.md#L18-L27), [fixed state key remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hyperlight/HyperlightCodeActProvider.cs#L31-L36))

### 7.6 확장점
#### Python
- `tools`
- `approval_mode`
- `workspace_root`
- `file_mounts`
- `allowed_domains`
- `backend`
- `module`
- `module_path`  
  ([ctor args](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hyperlight/agent_framework_hyperlight/_execute_code_tool.py#L1090-L1099))
#### .NET
- `CreateForWasm(modulePath)` / `CreateForJavaScript()`
- heap/stack size
- tools
- approval mode
- host input directory
- file mounts
- allowed domains  
  ([options](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hyperlight/HyperlightCodeActProviderOptions.cs#L21-L99))

### 7.7 동시성·스트리밍·취소
- Python Hyperlight explicitly isolates PyO3 unsendable objects to a dedicated worker thread to avoid cross-thread drop panic.  
  ([thread-confined worker rationale](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hyperlight/agent_framework_hyperlight/_execute_code_tool.py#L101-L126))
- .NET design docs require concurrent `execute_code` runs to use independent sandbox instances or synchronized snapshot/restore access.  
  ([design doc concurrency note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/features/code_act/dotnet-implementation.md#L425-L425))
- ordinary agent tool streaming contract applies; backend-specific partial output recovery is explicitly not portable.  
  ([Python design doc failure semantics](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/features/code_act/python-implementation.md#L329-L329), [.NET design doc failure semantics](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/features/code_act/dotnet-implementation.md#L404-L404))

### 7.8 오류·검증
- Python approval is conservative: any provider-owned tool with `always_require` escalates `execute_code`.  
  ([approval resolution](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hyperlight/agent_framework_hyperlight/_execute_code_tool.py#L335-L346))
- Python mount paths stay under `/input`, and invalid network permission host-target mismatch can trigger retry handling.  
  ([mount path normalize](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hyperlight/agent_framework_hyperlight/_execute_code_tool.py#L540-L553), [network permission retry heuristic](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hyperlight/agent_framework_hyperlight/_execute_code_tool.py#L532-L537))
- .NET tests pin `AlwaysRequire` vs `NeverRequire` with/without approval-wrapped tools.  
  ([tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hyperlight.UnitTests/ApprovalComputationTests.cs#L9-L61))

### 7.9 보안
- Python hardens input staging and output capture against symlinks/reparse points and uses `O_NOFOLLOW`-style file open defense.  
  ([input walker hardening](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hyperlight/agent_framework_hyperlight/_execute_code_tool.py#L560-L590), [output read hardening](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hyperlight/agent_framework_hyperlight/_execute_code_tool.py#L689-L709))
- .NET Hyperlight is intended sandbox boundary and supports opt-in mounts + outbound allow-list + bundled approval model.  
  ([README supported capabilities](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hyperlight/README.md#L18-L27), [.NET feature design approval model](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/features/code_act/dotnet-implementation.md#L94-L111))

### 7.10 .NET 구현과 테스트
- `ProvideAIContextTests` verify single execute_code, approval wrapping, snapshot immutability of returned description.  
  ([tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hyperlight.UnitTests/ProvideAIContextTests.cs#L17-L103))
- `ApprovalComputationTests` verify bundled approval policy.  
  ([tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hyperlight.UnitTests/ApprovalComputationTests.cs#L9-L61))

### 7.11 Python 구현과 테스트
- README documents provider-owned, standalone, manual wiring modes.  
  ([README](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hyperlight/README.md#L18-L97))
- tests cover symlink/reparse hardening, allowed_domains normalization/retry, provider state and approval mode propagation.  
  ([selected tests in file](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hyperlight/tests/hyperlight/test_hyperlight_codeact.py#L632-L703), [allowed_domains tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hyperlight/tests/hyperlight/test_hyperlight_codeact.py#L1041-L1050), [strict network retry tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hyperlight/tests/hyperlight/test_hyperlight_codeact.py#L1200-L1244), [provider state tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hyperlight/tests/hyperlight/test_hyperlight_codeact.py#L1299-L1335))

### 7.12 문서 차이
- Python default backend is `wasm`.  
  ([Python execute code tool default backend](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hyperlight/agent_framework_hyperlight/_execute_code_tool.py#L29-L30), [ctor default backend args](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hyperlight/agent_framework_hyperlight/_execute_code_tool.py#L1095-L1098))
- .NET default backend options constructor is JavaScript, and Wasm is explicit factory method.  
  ([.NET options default ctor](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hyperlight/HyperlightCodeActProviderOptions.cs#L21-L29), [CreateForWasm](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hyperlight/HyperlightCodeActProviderOptions.cs#L36-L47))

즉 “Hyperlight”라는 이름은 같아도 backend default는 다르다.

### 7.13 Java 결정
- **core MVP 제외**, optional module
- concrete backend-first 전략 권장
- bundled approval model은 유지
- symlink-safe staging/capture는 첫 구현부터 필수  
  ([cross-SDK CodeAct decision](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0024-codeact-integration.md#L159-L170), [Python approval resolution](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hyperlight/agent_framework_hyperlight/_execute_code_tool.py#L335-L346), [Python symlink hardening](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hyperlight/agent_framework_hyperlight/_execute_code_tool.py#L560-L590))

### 7.14 Acceptance scenarios
1. provider-owned tool 중 하나라도 approval-required면 `execute_code`는 approval-required가 되어야 한다.  
   ([Python](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hyperlight/agent_framework_hyperlight/_execute_code_tool.py#L335-L346), [.NET tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hyperlight.UnitTests/ApprovalComputationTests.cs#L50-L61))
2. provider mutation 후 이미 반환된 run-scoped execute_code description은 바뀌면 안 된다.  
   ([.NET test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hyperlight.UnitTests/ProvideAIContextTests.cs#L70-L85))
3. symlink/reparse-point를 통한 input/output escape는 막혀야 한다.  
   ([Python hardening source](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hyperlight/agent_framework_hyperlight/_execute_code_tool.py#L560-L590), [output hardening source](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hyperlight/agent_framework_hyperlight/_execute_code_tool.py#L689-L709))
4. Wasm backend 사용 시 guest module path가 필요해야 한다(.NET).  
   ([README requirements](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hyperlight/README.md#L28-L37), [CreateForWasm](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hyperlight/HyperlightCodeActProviderOptions.cs#L36-L41))

### 7.15 Source inventory
- Python  
  - `python/packages/hyperlight/README.md`  
    https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hyperlight/README.md
  - `python/packages/hyperlight/pyproject.toml`  
    https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hyperlight/pyproject.toml
  - `python/packages/hyperlight/agent_framework_hyperlight/_provider.py`  
    https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hyperlight/agent_framework_hyperlight/_provider.py
  - `python/packages/hyperlight/agent_framework_hyperlight/_execute_code_tool.py`  
    https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hyperlight/agent_framework_hyperlight/_execute_code_tool.py
  - `python/packages/hyperlight/tests/hyperlight/test_hyperlight_codeact.py`  
    https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hyperlight/tests/hyperlight/test_hyperlight_codeact.py
- .NET  
  - `dotnet/src/Microsoft.Agents.AI.Hyperlight/README.md`  
    https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hyperlight/README.md
  - `dotnet/src/Microsoft.Agents.AI.Hyperlight/HyperlightCodeActProvider.cs`  
    https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hyperlight/HyperlightCodeActProvider.cs
  - `dotnet/src/Microsoft.Agents.AI.Hyperlight/HyperlightCodeActProviderOptions.cs`  
    https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hyperlight/HyperlightCodeActProviderOptions.cs
  - `dotnet/tests/Microsoft.Agents.AI.Hyperlight.UnitTests/ProvideAIContextTests.cs`  
    https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hyperlight.UnitTests/ProvideAIContextTests.cs
  - `dotnet/tests/Microsoft.Agents.AI.Hyperlight.UnitTests/ApprovalComputationTests.cs`  
    https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hyperlight.UnitTests/ApprovalComputationTests.cs
  - `docs/decisions/0024-codeact-integration.md`  
    https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0024-codeact-integration.md
  - `docs/features/code_act/dotnet-implementation.md`  
    https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/features/code_act/dotnet-implementation.md
  - `docs/features/code_act/python-implementation.md`  
    https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/features/code_act/python-implementation.md

---

## 8. Monty CodeAct (Python only)

### 8.1 목적·경계
Monty는 Rust-based Python interpreter를 CodeAct backend로 쓰는 Python package다. Hyperlight와 같은 provider/tool 모델을 따르지만, hypervisor/WASM backend가 아니라 interpreter 기반으로 cross-platform 실행을 목표로 한다.  
([README summary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/monty/README.md#L1-L18), [provider summary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/monty/agent_framework_monty/_provider.py#L20-L27))

이 문서에서는 Monty를 Hyperlight와 구분된 backend로 다루며, shell이나 LocalCodeAct와는 다른 execution family로 본다.

### 8.2 성숙도
- beta package다.  
  ([package status](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L46-L46), [pyproject classifier](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/monty/pyproject.toml#L13-L24))

### 8.3 공개 API·타입
- `MontyCodeActProvider`
- `MontyExecuteCodeTool`
- `FileMount`
- `FileMountInput`
- `MountMode`  
  ([lazy namespace](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/monty/__init__.py#L11-L17), [provider ctor](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/monty/agent_framework_monty/_provider.py#L31-L48), [tool ctor](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/monty/agent_framework_monty/_execute_code_tool.py#L195-L225))

### 8.4 상세 실행 흐름
- provider는 run마다 `create_run_tool()` snapshot을 만들고 instructions + `execute_code`를 주입한다.  
  ([before_run](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/monty/agent_framework_monty/_provider.py#L85-L98))
- `MontyExecuteCodeTool`은 managed tools를 typed async functions + fallback `call_tool(...)`로 interpreter 안에 노출한다.
- read-write mounts는 pre-state snapshot 이후 post-run scan으로 변경 파일을 capture한다.  
  ([tool overview](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/monty/agent_framework_monty/_execute_code_tool.py#L167-L193), [run path](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/monty/agent_framework_monty/_execute_code_tool.py#L391-L422))

### 8.5 상태·영속화
- `workspace_root`가 있으면 `/input` read-write mount가 자동으로 추가된다.
- explicit `/input` mount가 있으면 그것이 우선한다.
- `overlay` mount는 write가 in-memory only이고 host에 남지 않으며 capture 대상도 아니다.
- `read-write` mount는 host에 반영되고 capture 대상이다.  
  ([auto mount logic](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/monty/agent_framework_monty/_execute_code_tool.py#L383-L389), [README mount semantics](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/monty/README.md#L135-L149), [workspace_root tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/monty/tests/monty/test_monty_codeact.py#L307-L324))

### 8.6 확장점
- tools
- approval_mode
- workspace_root
- file_mounts
- resource_limits  
  ([provider ctor](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/monty/agent_framework_monty/_provider.py#L31-L48), [tool ctor](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/monty/agent_framework_monty/_execute_code_tool.py#L195-L225), [README resource limits](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/monty/README.md#L108-L149))

### 8.7 동시성·스트리밍·취소
- object mutators는 same task/thread ownership을 전제로 하고 internal locking은 없다.  
  ([doc comment](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/monty/agent_framework_monty/_execute_code_tool.py#L190-L193))
- result는 final `list[Content]`로 반환되며 separate stream protocol은 없다.  
  ([run code return type](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/monty/agent_framework_monty/_execute_code_tool.py#L391-L422))

### 8.8 오류·검증
- approval resolution은 Hyperlight와 동일하게 managed tool 중 하나라도 `always_require`면 `execute_code` 전체를 escalate한다.  
  ([approval resolution](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/monty/agent_framework_monty/_execute_code_tool.py#L72-L81))
- mount path normalization은 absolute POSIX path + no `..`를 강제한다.  
  ([mount path normalize](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/monty/agent_framework_monty/_execute_code_tool.py#L84-L103))
- execution exception은 `Content.from_error(...)`로 surfaced 된다.  
  ([exception handling](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/monty/agent_framework_monty/_execute_code_tool.py#L410-L418))

### 8.9 보안
- 기본적으로 OS/filesystem/network access는 `PermissionError`로 거절된다.
- mount를 통해 scoped filesystem capability를 연다.
- post-capture는 symlink를 건너뛰어 host file leakage를 막는다.
- typed host tool call은 type-checking을 거친다.  
  ([README default PermissionError and limited stdlib](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/monty/README.md#L168-L177), [symlink-safe file iteration](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/monty/agent_framework_monty/_execute_code_tool.py#L453-L558), [README type-checking note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/monty/README.md#L174-L175))

### 8.10 .NET 구현과 테스트
- inspected tree에는 Monty CodeAct의 .NET implementation이 없다.
- .NET feature docs는 “future backend such as Monty”가 같은 conceptual model을 따를 수 있다고만 말한다.  
  ([.NET feature doc note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/features/code_act/dotnet-implementation.md#L6-L8))

### 8.11 Python 구현과 테스트
- `test_monty_codeact.py`는 approval mode, mounts, workspace_root, capture behavior를 검증한다.  
  ([tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/monty/tests/monty/test_monty_codeact.py#L182-L345), [capture tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/monty/tests/monty/test_monty_codeact.py#L602-L634))
- integration tests는 workspace reads/writes, read-only/overlay mount rejection, symlink-safe runtime/capture를 검증한다.  
  ([integration tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/monty/tests/monty/test_monty_codeact_integration.py#L254-L345), [symlink tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/monty/tests/monty/test_monty_codeact_integration.py#L381-L480))

### 8.12 문서 차이
큰 문서-코드 불일치는 보이지 않았다. README의 “subset of Python”, “OS/filesystem/network denied by default”, “overlay does not persist/capture”는 코드와 테스트가 일치한다.  
([README](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/monty/README.md#L151-L179), [runtime/capture code](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/monty/agent_framework_monty/_execute_code_tool.py#L391-L422), [capture semantics code](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/monty/agent_framework_monty/_execute_code_tool.py#L507-L558))

### 8.13 Java 결정
- **MVP 제외**
- 이후 optional backend module 후보
- 이유:
  - .NET parity 부재
  - interpreter capability surface 큼
  - host mount/resource limit semantics를 잘못 설계하면 security expectation mismatch 발생  
  ([Python package status beta](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L46-L46), [.NET no implementation note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/features/code_act/dotnet-implementation.md#L6-L8))

### 8.14 Acceptance scenarios
1. `workspace_root`가 있으면 `/input` read-write mount가 자동 생겨야 한다.  
   ([test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/monty/tests/monty/test_monty_codeact.py#L307-L324))
2. `overlay` mount write는 host에 남지 않고 capture되지 않아야 한다.  
   ([README](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/monty/README.md#L140-L145), [integration test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/monty/tests/monty/test_monty_codeact_integration.py#L320-L334))
3. read-only mount write는 `PermissionError`류로 거절되고 capture되지 않아야 한다.  
   ([integration test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/monty/tests/monty/test_monty_codeact_integration.py#L292-L315))
4. symlink를 통한 host file leakage는 runtime/capture 양쪽에서 막혀야 한다.  
   ([integration symlink tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/monty/tests/monty/test_monty_codeact_integration.py#L381-L480))

### 8.15 Source inventory
- Python  
  - `python/packages/monty/README.md`  
    https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/monty/README.md
  - `python/packages/monty/pyproject.toml`  
    https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/monty/pyproject.toml
  - `python/packages/monty/agent_framework_monty/_provider.py`  
    https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/monty/agent_framework_monty/_provider.py
  - `python/packages/monty/agent_framework_monty/_execute_code_tool.py`  
    https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/monty/agent_framework_monty/_execute_code_tool.py
  - `python/packages/monty/tests/monty/test_monty_codeact.py`  
    https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/monty/tests/monty/test_monty_codeact.py
  - `python/packages/monty/tests/monty/test_monty_codeact_integration.py`  
    https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/monty/tests/monty/test_monty_codeact_integration.py
  - `python/packages/core/agent_framework/monty/__init__.py`  
    https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/monty/__init__.py
- .NET  
  - no inspected Monty backend implementation in `dotnet/src`
  - conceptual reference only:
    - `docs/features/code_act/dotnet-implementation.md`  
      https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/features/code_act/dotnet-implementation.md

---

## 9. 최종 Java 결정 요약

### 포함 권장
- **Skills**
  - read-only progressive disclosure(`load_skill`, `read_skill_resource`)
  - file-based source with traversal/symlink defense
- **Shell environment provider**
  - 단, shell execution 자체와 분리된 별도 tools module
- **tool-backed provider pattern**
  - Hyperlight/Monty/LocalCodeAct가 보여준 “provider-owned tool registry + run-scoped execute_code” 구조는 Java 후속 module 설계의 공통 패턴으로 유용함

([Python SkillsProvider tool model](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_skills.py#L2450-L2540), [.NET AgentSkillsProvider BuildTools](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Skills/AgentSkillsProvider.cs#L314-L340), [LocalCodeAct run-scoped snapshot pattern](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/LocalCodeActProvider.cs#L186-L193), [Python Hyperlight run tool pattern](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hyperlight/agent_framework_hyperlight/_provider.py#L109-L114))

### MVP 제외 권장
- Background agents/tasks
- Script-executing skills
- Local shell execution
- LocalCodeAct
- Hyperlight/Monty backend modules

### 이유
- background tasks는 runtime handle persistence와 trust boundary가 큼
- shell/local code execution은 운영자가 sandbox로 오해할 위험이 있음
- Hyperlight/Monty는 backend portability와 platform packaging 이슈가 큼
- script-executing skills는 resource-reading skills보다 훨씬 위험하다

([background trust boundary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_background_agents.py#L258-L266), [LocalShellTool not sandbox](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/README.md#L43-L67), [LocalCodeAct not sandbox](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/README.md#L5-L14), [Hyperlight requirements preview](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hyperlight/README.md#L28-L41), [Monty beta/platform spread](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/monty/pyproject.toml#L30-L36))

### 후속 단계 권장 순서
1. read-only skills
2. shell environment provider
3. containerized shell
4. code execution backend SPI
5. Hyperlight-like backend
6. LocalCodeAct-like backend는 최후순위

---