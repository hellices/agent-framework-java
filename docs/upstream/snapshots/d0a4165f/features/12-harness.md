# 12. Harness

## 1. Document purpose and scope

This document covers only the harness-perspective features of the Microsoft Agent Framework. Specifically it covers **harness composition**, the **automatic loop policy**, the **todo provider**, the **mode provider**, **file memory**, **file access**, **structured memory**, **tool approval**, and the **invocation budget**. In Python the top-level entrypoint is `create_harness_agent(...)`, and in .NET `HarnessAgent`/`AsHarnessAgent(...)` plays the same role.  
([Python assembly entrypoint](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_agent.py#L302-L345), [Python actual assembly](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_agent.py#L563-L683), [.NET extension entrypoint](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/ChatClientHarnessExtensions.cs#L9-L39), [.NET harness overview](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs#L21-L75))

For the following areas this document **describes only the boundary and does not duplicate the details**.

- `compaction`: it explains only where the harness wires compaction, and splits the algorithm/strategy details into a separate document.  
  ([Python compaction wiring boundary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_agent.py#L82-L142), [.NET compaction wiring boundary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs#L166-L197))
- `skills`: it explains only when the harness attaches the skills provider, and splits the skill source/progressive loading/script execution details into a separate document.  
  ([Python skills opt-in wiring](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_agent.py#L200-L205), [.NET skills default wiring](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs#L320-L326))
- `background-tasks`: it leaves only the connection point that the loop can take background-task completion as a condition, and splits the background agent/task lifecycle itself into a separate document.  
  ([Python loop helper boundary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_loop.py#L796-L860), [.NET background completion evaluator boundary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Loop/BackgroundTaskCompletionLoopEvaluator.cs#L15-L35))
- `code-execution`: the harness body does not implement code execution/runtime; Python provides optional shell/codeact wiring and .NET provides manual wiring of a separate package.  
  ([Python shell wiring boundary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_agent.py#L221-L262), [no automatic shell/codeact wiring in the .NET harness provider list](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs#L287-L344), [.NET manual shell sample](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/samples/02-agents/Agents/Agent_Step21_ShellWithEnvironment/Program.cs#L57-L119))

Furthermore, the **basic agent core/tool contract**, the **workflow engine internals**, and the **hosting framework internals** are not in the direct scope of this document. How the harness connects to those boundaries is explained, however.  
([Python Agent assembly seam](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_agent.py#L664-L683), [.NET ChatClientAgent seam](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs#L249-L265))

---

## 2. Common model of state and snapshot

The harness features mostly use **`AgentSession` / session state** as their common storage. Python's `create_harness_agent(...)` enforces `require_per_service_call_history_persistence=True`, and .NET likewise enforces `RequirePerServiceCallChatHistoryPersistence = true`. The harness is therefore designed on the premise of **history preservation per service call**, finer than “per turn”.  
([Python agent construction](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_agent.py#L664-L677), [.NET ChatClientAgentOptions](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs#L249-L265))

On top of this common model, each feature layers a different kind of state.

- **Todo / mode / tool approval / background tasks**: they put serializable state directly into session state.  
  ([Python mode state](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_mode.py#L76-L87), [Python todo state](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_todo.py#L245-L285), [Python approval state](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_tool_approval.py#L158-L215), [Python background state](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_background_agents.py#L163-L186), [.NET todo state description](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Todo/TodoProvider.cs#L21-L24), [.NET mode state description](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/AgentMode/AgentModeProvider.cs#L20-L23))
- **File memory / structured memory**: they decide a routing key or a working folder through session state and put the actual payload in a file store.  
  ([Python file memory scope](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_file_memory.py#L234-L279), [Python structured memory owner routing](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_memory.py#L700-L745), [.NET file memory state initializer in harness](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs#L301-L312))
- **Automatic loop**: when the whole session can be snapshotted/restored, it keeps a pre-loop snapshot in fresh-context mode and rolls back between iterations.  
  ([Python fresh_context snapshot](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_loop.py#L421-L439), [Python restore logic](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_loop.py#L459-L474), [.NET fresh session snapshot](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Loop/LoopAgent.cs#L150-L159), [.NET snapshot caveat](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Loop/LoopAgentOptions.cs#L35-L53))

The two core judgement criteria that recur in this document are as follows.

1. **Is the state in session state or in a file store?**  
2. **Does the state persist to the next turn, or is it a snapshot valid only inside the same run?**

---

## 3. Harness composition

### Purpose
The purpose of harness composition is to let the caller assemble, in one step, the basic auxiliary features a research/coding/analysis-type agent needs, without composing the low-level chat pipeline and each provider wiring one by one.  
([Python summary docstring](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_agent.py#L3-L9), [.NET summary remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs#L21-L25))

### Boundary
Harness composition **assembles rather than directly implements** the following.

- function invocation loop
- per-service-call history persistence
- message injection
- built-in context providers
- tool approval layer
- optional loop layer
- optional web search / shell / file access / background / skills wiring

The harness is therefore an **opinionated assembly layer** rather than an orchestration kernel.  
([Python assembled features list](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_agent.py#L347-L363), [.NET assembled pipeline list](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs#L27-L75))

### Maturity
- **Python**: `create_harness_agent` itself is graduated and has no experimental metadata. Using `background_agents`, `file_access_store`, `loop_should_continue`, or `shell_executor`, however, raises a feature-level warning.  
  ([graduated test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_agent.py#L1299-L1318), [warning emission logic](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_agent.py#L541-L561))
- **.NET**: `HarnessAgent` is used like a stable top level, but the options related to compaction/loop/file-access/background agents carry the `[Experimental]` marking.  
  ([compaction options experimental](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgentOptions.cs#L45-L95), [loop options experimental](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgentOptions.cs#L149-L176), [file access experimental](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgentOptions.cs#L252-L275), [background experimental](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgentOptions.cs#L359-L380))

### Public APIs and types
- **Python**
  - `create_harness_agent`
  - `TodoProvider`, `AgentModeProvider`, `FileMemoryProvider`
  - `ToolApprovalMiddleware`
  - loop helper exports (`todos_remaining`, `background_tasks_running`, and so on)
  
  ([public exports](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/__init__.py#L558-L645))
- **.NET**
  - `ChatClientHarnessExtensions.AsHarnessAgent(...)`
  - `HarnessAgent`
  - `HarnessAgentOptions`
  
  ([AsHarnessAgent](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/ChatClientHarnessExtensions.cs#L9-L39), [HarnessAgentOptions](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgentOptions.cs#L11-L19))

### Detailed execution flow
#### Python
1. Decide the history provider. The default is `InMemoryHistoryProvider()`.  
2. Assemble compaction. The before-phase attaches as the agent `compaction_strategy` and the after-phase as a provider hook.  
3. Create the optional shell tool/provider.  
4. Attach the built-in context providers in order: history → compaction → todo → mode → file memory → optional file access → optional skills → optional background → optional shell environment → extra providers.  
5. Assemble the tool set: optional web search, optional shell tool, user tools.  
6. Assemble the middleware: default tool approval, optional loop (outermost), message injection, user middleware.  
7. Set `require_per_service_call_history_persistence=True` on the final `Agent(...)`.  
([history/compaction/shell assembly](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_agent.py#L563-L584), [provider order](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_agent.py#L145-L218), [tool assembly](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_agent.py#L611-L629), [middleware order](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_agent.py#L636-L677))

#### .NET
1. Compute the compaction strategy for the inner `ChatClientAgent`.  
2. Decide the default chat history provider and, when compaction is present, connect it as an in-memory reducer.  
3. Merge the harness instructions with the agent instructions.  
4. Assemble the context providers.  
5. Put approval response binding, approval-not-required bypassing, function invocation, message injection, per-service-call history persistence, optional compaction, and optional OTel onto the chat client builder.  
6. Wrap with the outer agent decorators optional loop → optional tool approval → optional OTel.  
([compaction/history/options](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs#L164-L212), [chat client stack](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs#L215-L247), [outer decorators](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs#L133-L161), [provider assembly](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs#L287-L344))

### State and persistence
- The built-in provider state of the Python harness is mostly kept in session state, while file memory/file access move out to a store.  
  ([provider assembly](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_agent.py#L145-L218))
- The .NET harness also uses per-provider session state, and the chat history has the per-service-call persistence premise.  
  ([ChatClientAgentOptions](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs#L249-L265))

### Extension points
- Python can override `history_provider`, `todo_provider`, `mode_provider`, `file_memory_store`, `file_access_store`, `skills_provider`, `background_agents`, `shell_executor`, `context_providers`, `middleware`, and `default_options`.  
  ([signature](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_agent.py#L302-L344))
- .NET can override `ChatHistoryProvider`, `AIContextProviders`, `LoopEvaluators`, `ToolApprovalAgentOptions`, `AgentSkillsSource`, `BackgroundAgents`, `FileAccessStore`, and so on.  
  ([options surface](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgentOptions.cs#L130-L176), [skills/background/file access options](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgentOptions.cs#L252-L380))

### Concurrency, streaming, and cancellation
Harness composition itself composes the concurrency model each provider/middleware has rather than implementing concurrency primitives directly. The **loop and approval**, however, both implement a separate streaming path so that an injected message or an approval request can be surfaced in the middle of the message stream.  
([Python loop streaming](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_loop.py#L559-L655), [Python approval streaming](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_tool_approval.py#L435-L502), [.NET loop streaming](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Loop/LoopAgent.cs#L220-L320), [.NET approval streaming](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/ToolApproval/ToolApprovalAgent.cs#L187-L280))

### Errors and validation
- Python rejects an invalid context/output token combination at assembly time.  
  ([validation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_agent.py#L530-L539))
- .NET likewise rejects an invalid token combination in the constructor.  
  ([tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Harness.UnitTests/HarnessAgentTests.cs#L50-L76))

### Security
The harness does not create a sandbox itself, but it enforces the following security decisions by default.

- It maintains approval/loop/history correctness on the premise of per-service-call persistence.  
- Python allows external skills/background agents/shell executor only as a caller opt-in.  
- .NET turns approval response binding and “approval not required bypassing” on by default to increase the consistency of a mixed approval batch.  
([Python security notes on skills/background/shell options](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_agent.py#L460-L489), [.NET approval binding defaults](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs#L218-L230))

### .NET implementation and tests
- The default built-in providers are todo/mode/file memory/skills.  
  ([provider list remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs#L36-L49), [actual assembly](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs#L287-L344))
- The tests pin invalid options, identity propagation, and the instructions merge.  
  ([HarnessAgentTests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Harness.UnitTests/HarnessAgentTests.cs#L40-L259), [HarnessAgentOptionsTests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Harness.UnitTests/HarnessAgentOptionsTests.cs#L13-L127))

### Python implementation and tests
- The default built-in providers are history/compaction/todo/mode/file memory, and skills is off by default.  
  ([assembly code](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_agent.py#L167-L218), [default provider test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_agent.py#L93-L110))
- The tests pin file access opt-in, shell auto-wiring, loop outermost ordering, and feature warning emission.  
  ([file access opt-in](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_agent.py#L148-L218), [shell wiring](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_agent.py#L905-L962), [loop ordering](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_agent.py#L1153-L1262), [feature warnings](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_agent.py#L1321-L1399))

### Documentation differences
The most important difference is the **default skills policy**.

- The Python harness does not attach skills by default. A `skills_provider` or `skills_paths` is required.  
  ([Python assembly](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_agent.py#L200-L205))
- The .NET harness attaches an `AgentSkillsProvider` based on the current working directory by default.  
  ([.NET assembly](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs#L320-L326), [.NET options remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgentOptions.cs#L315-L334))

Code first, a cross-SDK Java design has to decide this difference explicitly.

### Java decisions
- **Included in the MVP**: the harness assembler, todo/mode/file-memory, approval, the loop seam
- **Excluded from the MVP**: shared file access, advanced structured memory, shell auto-wiring
- **Default policy**: keeping **skills opt-in** and **file access opt-in** as on the Python side is the more conservative choice.  
  ([Python opt-in providers](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_agent.py#L189-L205), [.NET broader default set](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs#L287-L344))

### Acceptance scenarios
1. On default creation the Python harness must include history/compaction/todo/mode/file-memory.  
   ([test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_agent.py#L93-L110))
2. File access must not attach without a `file_access_store`.  
   ([test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_agent.py#L148-L168))
3. The .NET harness must fail at the constructor stage on an invalid context/output token combination.  
   ([test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Harness.UnitTests/HarnessAgentTests.cs#L50-L76))
4. Passing a shell executor to the Python harness must add the shell tool and the environment provider, and both must be skipped when the client does not support shell.  
   ([tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_agent.py#L905-L962))

---

## 4. Automatic loop policy

### Purpose
The purpose of the automatic loop policy is to make the agent keep working on the same “task intent” without the user prompting again. The loop works on **re-execution conditions** such as todo completion, background-task completion, a completion marker, and an AI judge result.  
([Python loop overview](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_loop.py#L3-L26), [.NET loop overview](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Loop/LoopAgent.cs#L20-L37))

### Boundary
This document covers only the **loop policy** and leaves the following as boundaries.

- `background-tasks`: the lifecycle of a background task itself is a separate document
- `compaction`: the loop merely operates on compacted history, and the compaction algorithm itself is a separate document
- `tool approval`: only the seam that the loop stops when it meets a pending approval is covered  
  ([Python pending approval seam](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_loop.py#L440-L457), [.NET pending approval seam](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Loop/LoopAgent.cs#L195-L199))

### Maturity
- **Python**: `AgentLoopMiddleware` is an experimental HARNESS feature.  
  ([decorator](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_loop.py#L214-L215), [package status](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L120-L124))
- **.NET**: `LoopAgent`, `TodoCompletionLoopEvaluator`, `BackgroundTaskCompletionLoopEvaluator`, and `AIJudgeLoopEvaluator` are all experimental.  
  ([LoopAgent](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Loop/LoopAgent.cs#L64-L65), [TodoCompletionLoopEvaluator](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Loop/TodoCompletionLoopEvaluator.cs#L41-L42), [BackgroundTaskCompletionLoopEvaluator](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Loop/BackgroundTaskCompletionLoopEvaluator.cs#L35-L36), [AIJudgeLoopEvaluator](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Loop/AIJudgeLoopEvaluator.cs#L54-L55))

### Public APIs and types
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

### Detailed execution flow
#### Python
The Python loop takes the form of middleware.

1. When necessary it first inserts `additional_instructions` as a system message.
2. When `fresh_context=True` and a session exists it takes a pre-loop snapshot.
3. On every iteration it calls the inner agent.
4. When the response has a pending `function_approval_request` it ends the loop immediately and returns to the caller.
5. Otherwise it evaluates `should_continue`.
6. When necessary it loads the `record_feedback` result into the progress log.
7. It composes the next input with `next_message`.
8. When `fresh_context=True` it restores the snapshot and then starts again with the original input plus the progress log plus the next message.  
([main process](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_loop.py#L415-L439), [non-streaming loop body](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_loop.py#L475-L557), [next-message/progress resolution](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_loop.py#L749-L793))

#### .NET
The .NET loop is a decorator plus an evaluator chain.

1. The caller input goes directly into the inner agent only on the first iteration.
2. After each iteration it evaluates the evaluators **in order**.
3. The first `Continue(...)` evaluator becomes the “driver of the next iteration”, and only its feedback is used as the next input.
4. When every evaluator returns `Stop()` the loop ends.
5. When there is a pending approval it terminates.
6. `LoopAgentOptions.MaxIterations` applies as a global safety cap.  
([evaluator priority semantics](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Loop/LoopAgent.cs#L25-L37), [run loop body](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Loop/LoopAgent.cs#L173-L217))

### State and persistence
- Python keeps the `progress` log in memory and restores the session snapshot only when `fresh_context` is set.  
  ([progress and snapshot state](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_loop.py#L482-L489), [snapshot restore](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_loop.py#L548-L553))
- .NET keeps the original messages, the feedback log, and an optional serialized session snapshot as internal loop state.  
  ([state in run core](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Loop/LoopAgent.cs#L141-L172))

### Extension points
- Python lets the caller freely combine `should_continue`, `next_message`, `record_feedback`, and `with_judge(criteria=..., instructions=...)`.  
  ([callback contracts](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_loop.py#L128-L141), [judge factory](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_loop.py#L347-L413))
- .NET extends through the evaluator chain and `LoopAgentOptions`.  
  ([LoopAgent ctors](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Loop/LoopAgent.cs#L79-L130), [LoopAgentOptions](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Loop/LoopAgentOptions.cs#L17-L117))

### Concurrency, streaming, and cancellation
- In Python the streaming and non-streaming implementations are separate, and in streaming the injected “nudge” message also flows as an update.  
  ([streaming path](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_loop.py#L559-L655))
- .NET can also surface iteration boundaries on the basis of the `ResponseId` in streaming, and flows on-behalf-of messages as updates.  
  ([streaming path](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Loop/LoopAgent.cs#L253-L320))
- Cancellation follows the async/cancellation-token model on both sides.  
  ([Python maybe-await callbacks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_loop.py#L144-L148), [.NET cancellation token usage](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Loop/LoopAgent.cs#L133-L138))

### Errors and validation
- Python fails when `max_iterations` is below 1. `looping_modes=[]` also fails.  
  ([max_iterations validation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_loop.py#L333-L346), [looping_modes validation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_loop.py#L903-L907))
- .NET rejects a null/empty evaluator set, a null evaluator element, and `MaxIterations < 1`.  
  ([tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/Loop/LoopAgentTests.cs#L24-L118))

### Security
An AI judge loop creates a **second external LLM boundary** on both sides. Because the original request and the latest agent response are sent to the judge model, there is an exfiltration and indirect prompt injection risk.  
([Python judge security note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_loop.py#L367-L382), [.NET judge security note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Loop/AIJudgeLoopEvaluator.cs#L43-L52))

### .NET implementation and tests
- `LoopAgent` maintains the aggregated transcript and usage.  
  ([aggregation logic](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Loop/LoopAgent.cs#L162-L217))
- The tests pin the immediate stop, multi-iteration aggregation, and last-response-only behavior.  
  ([LoopAgentTests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/Loop/LoopAgentTests.cs#L123-L210))
- The AI judge tests pin the structured verdict, the text fallback, custom instructions/feedback, and multimodal request forwarding.  
  ([AIJudgeLoopEvaluatorTests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/Loop/AIJudgeLoopEvaluatorTests.cs#L20-L220))

### Python implementation and tests
- The harness tests pin that the loop middleware must go in as the outermost.  
  ([outermost loop test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_agent.py#L1171-L1195))
- The loop feature itself emits an experimental warning on `loop_should_continue` opt-in.  
  ([warning test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_agent.py#L1359-L1379))

### Documentation differences
The difference that matters more in the code than in the documentation is the **difference in the structural model**.

- Python: a single `should_continue` predicate/middleware model  
- .NET: an ordered evaluator chain model

The two have a similar user-facing purpose, but the evaluator composition semantics differ.  
([Python callback shape](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_loop.py#L128-L141), [.NET first-continue-wins semantics](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Loop/LoopAgent.cs#L32-L37))

### Java decisions
In Java, **a Python-style predicate middleware for the MVP** and **a .NET-style evaluator chain for the follow-up stage** is appropriate. The former can be implemented quickly, and the latter exposes composability and priority semantics well.  
([Python middleware shape](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_loop.py#L214-L325), [.NET evaluator chain shape](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Loop/LoopAgent.cs#L25-L37))

### Acceptance scenarios
1. The loop must stop immediately when it meets a pending approval and must return the approval request to the caller.  
   ([Python](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_loop.py#L440-L457), [.NET](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Loop/LoopAgent.cs#L195-L199))
2. In .NET, when there are several evaluators only the first continue evaluator must drive the next iteration.  
   ([semantics](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Loop/LoopAgent.cs#L32-L37))
3. Python `todos_remaining(looping_modes=["execute"])` must keep going only in execute mode.  
   ([helper](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_loop.py#L876-L937))
4. When the AI judge returns `answered=false`, the gap feedback must be returned as the next iteration input.  
   ([Python judge next_message](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_loop.py#L201-L209), [.NET feedback template](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Loop/AIJudgeLoopEvaluator.cs#L102-L106))

---

## 5. Todo provider

### Purpose
The todo provider externalizes a long-running task into a **list of work items** so that the planning/execution/cleanup stages are tracked explicitly.  
([Python todo instructions](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_todo.py#L24-L48), [.NET todo remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Todo/TodoProvider.cs#L15-L38))

### Boundary
The todo provider is a **planning artifact store**, not a workflow DAG or a scheduler. Inside the harness it is used mainly at the following boundaries.

- Together with the mode provider it helps the “plan → execute” transition.
- It can be used as the completion predicate of the loop policy.
- It differs from the deliverables kept long term in structured memory/file memory.  
  ([Python mode default plan/execute guidance](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_mode.py#L32-L70), [Python loop todo helper](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_loop.py#L876-L937), [.NET todo loop evaluator](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Loop/TodoCompletionLoopEvaluator.cs#L15-L39))

### Maturity
- **Python**: `TodoProvider` and the session store look stable, and only the file-backed `TodoFileStore` falls into the experimental harness category.  
  ([package status text](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L120-L124), [TodoFileStore experimental marker](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_todo.py#L287-L288))
- **.NET**: the inspected `TodoProvider` carries no experimental marker.  
  ([type header](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Todo/TodoProvider.cs#L15-L40))

### Public APIs and types
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

### Detailed execution flow
#### Python
`before_run` performs the following.

1. `todos_add`
2. `todos_complete`
3. `todos_remove`
4. `todos_get_remaining`
5. `todos_get_all`

are all injected with `approval_mode="never_require"`.  
It also puts the current todo list in as a `### Current todo list` user message.  
([tools injection](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_todo.py#L493-L615))

#### .NET
`TodoProvider` provides the same 5-tool model and reads and writes the item list from session state. The loop evaluator does not take a separate provider instance directly and resolves it with `context.Agent.GetService<TodoProvider>()`.  
([provider remarks/tools](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Todo/TodoProvider.cs#L21-L38), [loop evaluator resolution](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Loop/TodoCompletionLoopEvaluator.cs#L101-L126))

### State and persistence
- The Python default puts `items` and `next_id` into `AgentSession.state[source_id]`.  
  ([TodoSessionStore](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_todo.py#L245-L285))
- The Python file store creates a JSON file per owner/session/source_id and stores it with an atomic replace.  
  ([path shaping](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_todo.py#L344-L394), [atomic save](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_todo.py#L419-L443))
- .NET stores the todo list in the session state bag.  
  ([remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Todo/TodoProvider.cs#L21-L24))

### Extension points
- Python can inject a custom `TodoStore`.  
  ([abstract store](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_todo.py#L228-L243), [provider ctor](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_todo.py#L462-L480))
- .NET can change the message formatting and the loop feedback through `TodoProviderOptions` and `TodoCompletionLoopEvaluatorOptions`.  
  ([provider ctor/options hook](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Todo/TodoProvider.cs#L74-L87), [loop evaluator options use](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Loop/TodoCompletionLoopEvaluator.cs#L60-L94))

### Concurrency, streaming, and cancellation
- Python has a per-session mutation lock.  
  ([lock table](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_todo.py#L481-L491))
- .NET states explicitly that it prevents duplicate IDs/lost updates with a per-session lock.  
  ([thread-safe remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Todo/TodoProvider.cs#L35-L38), [session lock fields](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Todo/TodoProvider.cs#L66-L71))
- There is no separate streaming-specific path; it works as an ordinary tool invocation on top of the upper-layer agent stream.  
  ([Python todo is regular tools](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_todo.py#L505-L595), [.NET todo tool surface](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Todo/TodoProvider.cs#L26-L33))

### Errors and validation
- Python rejects malformed session state, non-list items, and a non-int `next_id`.  
  ([validation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_todo.py#L257-L275))
- It also rejects an empty `todos_add/items/ids` payload.  
  ([add/complete/remove validation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_todo.py#L508-L509), [complete validation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_todo.py#L534-L535), [remove validation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_todo.py#L566-L567))
- The Python file store rejects session path traversal.  
  ([test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_todo.py#L214-L223))
- The .NET tests pin the complete/remove/add semantics.  
  ([tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/Todo/TodoProviderTests.cs#L44-L189))

### Security
The todo feature itself is low-risk, but the Python implementation using file-backed persistence reduces corruption/escape with path-safe encoding and atomic writes.  
([safe segment logic](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_todo.py#L364-L394), [atomic replace](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_todo.py#L431-L443))

### .NET implementation and tests
- `TodoProvider` provides the 5-tool surface and the session-state model.  
  ([provider remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Todo/TodoProvider.cs#L21-L38))
- The tests verify the tool count and the add/complete/remove behavior.  
  ([ProvideAIContext test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/Todo/TodoProviderTests.cs#L17-L40), [add tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/Todo/TodoProviderTests.cs#L44-L100), [complete tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/Todo/TodoProviderTests.cs#L104-L189))

### Python implementation and tests
- `TodoSessionStore` and `TodoFileStore` both prevent ID collisions through `next_id` clamping.  
  ([clamp helper](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_todo.py#L223-L225), [test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_todo.py#L180-L198))
- The file store tests also verify atomic write crash safety.  
  ([test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_todo.py#L130-L149))

### Documentation differences
No noticeable doc/code discrepancy was found in this area. The “experimental harness APIs” wording of the package status, however, points broadly at **harness sub-features including file-backed todo storage** rather than at the whole provider, so the actual maturity judgement has to be made per code and per export.  
([package status](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L120-L124), [TodoProvider stable-looking surface](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_todo.py#L446-L616))

### Java decisions
It is appropriate for the Java MVP to include a **session-state based todo provider** and to **exclude the file-backed store**. The session-state model alone is enough to implement the harness loop/mode integration, and a file store additionally has to consider path safety and crash safety.  
([session store](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_todo.py#L245-L285), [file store complexity](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_todo.py#L287-L443))

### Acceptance scenarios
1. `todos_add` must assign increasing IDs when several items are inserted.  
   ([.NET test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/Todo/TodoProviderTests.cs#L71-L100))
2. `todos_complete` must return 0 for an ID that does not exist.  
   ([.NET test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/Todo/TodoProviderTests.cs#L151-L166))
3. Even when a rename fails during a Python file-backed todo save, the existing state file must be kept.  
   ([test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_todo.py#L130-L149))

---

## 6. Mode provider

### Purpose
The mode provider lets the same agent switch explicitly between an **interactive planning mode** and an **autonomous execution mode**, fixing “when to ask and when to proceed autonomously” in session state.  
([Python default mode map](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_mode.py#L32-L71), [.NET remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/AgentMode/AgentModeProvider.cs#L20-L39))

### Boundary
The mode provider is a **behavioral policy selector**, not a **task state machine**. Combined with todo/loop it can create rules such as “plan mode is interactive, execute mode is an autonomous loop”, but the mode itself does not drive the loop.  
([Python todo loop mode gating](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_loop.py#L885-L894), [.NET todo loop mode gating](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Loop/TodoCompletionLoopEvaluator.cs#L29-L39))

### Maturity
- **Python**: it is treated as a stable harness built-in feature.  
  ([default assembly](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_agent.py#L176-L181))
- **.NET**: on the inspected surface it is a default built-in provider with no experimental marker.  
  ([built-in provider list](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs#L36-L43), [provider header](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/AgentMode/AgentModeProvider.cs#L15-L45))

### Public APIs and types
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

### Detailed execution flow
#### Python
- When there is no mode on the first run it uses the first entry of the configured mode list or `default_mode`.
- The `mode_set` tool changes session state directly, and when the agent changed it by its own hand it leaves no “external change notification”.
- When external code changes it through the `set_agent_mode(...)` helper, a user-role notification is added on the next `before_run` so that the model is not anchored to the previous tool-call anchor.  
([default resolution](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_mode.py#L111-L151), [external helper](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_mode.py#L154-L194), [before_run notification behavior](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_mode.py#L284-L325))

#### .NET
- The provider stores the current mode in session state and injects the current mode and the configured modes into the instructions.
- `AgentModeProviderOptions` specifies custom instructions, a custom mode set, and the default mode.  
  ([provider summary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/AgentMode/AgentModeProvider.cs#L15-L45), [options](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/AgentMode/AgentModeProviderOptions.cs#L15-L43))

### State and persistence
- Python keeps `current_mode` inside the `session.state[source_id]` dict in canonical lower case.  
  ([state model](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_mode.py#L76-L87), [get/set](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_mode.py#L139-L194))
- .NET likewise stores the current mode in the session state bag.  
  ([remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/AgentMode/AgentModeProvider.cs#L20-L23))

### Extension points
- Python can override `mode_instructions`, `instructions`, and `default_mode`.  
  ([ctor](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_mode.py#L215-L259))
- .NET provides `AgentModeProviderOptions.Modes`, `Instructions`, and `DefaultMode`.  
  ([options](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/AgentMode/AgentModeProviderOptions.cs#L15-L43))

### Concurrency, streaming, and cancellation
- Python stores into the session dict without an explicit lock, but the feature itself is short and simple.  
  ([state access](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_mode.py#L76-L87))
- .NET states explicitly that “concurrent reads and mutations are serialized using a per-session lock”.  
  ([thread-safe remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/AgentMode/AgentModeProvider.cs#L41-L43))

### Errors and validation
- Python rejects duplicate configured modes and an invalid mode set.  
  ([normalize validation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_mode.py#L90-L108))
- The .NET tests also verify that an invalid mode throws and that the existing mode is preserved.  
  ([test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/AgentMode/AgentModeProviderTests.cs#L102-L120))

### Security
The mode provider is not a privilege boundary, but it creates a **user interaction boundary**. That is, it fixes human-review rules such as “ask/wait for approval in plan mode” and “proceed autonomously in execute mode” in session state.  
([Python default mode instructions](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_mode.py#L33-L70))

### .NET implementation and tests
- The tests verify the 2-tool injection, the default mode, and the public helper behavior.  
  ([ProvideAIContext tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/AgentMode/AgentModeProviderTests.cs#L18-L62), [tool tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/AgentMode/AgentModeProviderTests.cs#L66-L120), [public helpers](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/AgentMode/AgentModeProviderTests.cs#L164-L199))

### Python implementation and tests
- The implementation even adds an external change notification to solve the mode anchoring problem. That is a harness-specific policy one step beyond a simple state get/set.  
  ([comment and implementation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_mode.py#L163-L167), [notification injection](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_mode.py#L315-L325))

### Documentation differences
No particular documentation-code discrepancy was confirmed. The Python implementation, however, states the operational insight that “system instructions alone are not enough for mode redirection” in a comment, so the code provides a stronger policy rationale than the documentation.  
([comment](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_mode.py#L188-L191))

### Java decisions
It is better to **include** the mode provider in the Java MVP. Taking the external mode change notification over as is in particular reduces the model anchoring problem.  
([Python external mode change handling](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_mode.py#L163-L194), [notification injection](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_mode.py#L315-L325))

### Acceptance scenarios
1. The default mode must be `plan`.  
   ([.NET test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/AgentMode/AgentModeProviderTests.cs#L127-L141))
2. An invalid mode set must fail and must not break the existing mode.  
   ([.NET test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/AgentMode/AgentModeProviderTests.cs#L102-L120))
3. A mode changed through the external helper must be exposed as a user-role notification on the next turn.  
   ([Python helper + before_run](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_mode.py#L154-L194), [notification emit](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_mode.py#L315-L325))

---

## 7. File memory

### Purpose
File memory is the **session-scoped working memory** the harness creates. Its purpose is to push material “worth reading again later”, such as plans, research results, and intermediate artifacts, out into durable files outside the chat transcript.  
([Python instructions](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_file_memory.py#L60-L74), [.NET instructions](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/FileMemory/FileMemoryProvider.cs#L69-L84))

### Boundary
- It is an **agent-managed memory namespace** rather than a shared persistent workspace.
- Unlike `FileAccessProvider`, it hides internal files (`memories.md`, description sidecars) from the agent-facing surface.
- It is also distinguished from the advanced transcript-backed structured memory.  
  ([Python distinction vs file access](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_file_memory.py#L16-L21), [Python internal file handling](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_file_memory.py#L93-L100), [.NET internal file handling](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/FileMemory/FileMemoryProvider.cs#L501-L538))

### Maturity
- **Python**: `FileMemoryProvider` is graduated. A test pins the absence of experimental metadata.  
  ([test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_file_memory.py#L291-L294))
- **.NET**: on the inspected surface there is no experimental marker, and it is a harness default built-in provider.  
  ([provider list](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs#L36-L43), [provider header](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/FileMemory/FileMemoryProvider.cs#L14-L42))

### Public APIs and types
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

### Detailed execution flow
#### Python
1. Decide the working folder from `scope` or `session_id`.
2. Create that folder when it does not exist.
3. Inject `file_memory_write/read/delete/ls/grep/replace/replace_lines` all as `never_require`.
4. When a `memories.md` index exists, inject it as a user message.
5. Rebuild the index after a write/delete.  
([working folder resolution](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_file_memory.py#L270-L299), [tool injection](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_file_memory.py#L301-L499), [index injection](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_file_memory.py#L501-L524))

#### .NET
1. Obtain the working folder of the current session through `ProviderSessionState<FileMemoryState>`.
2. Create the directory when a working folder exists.
3. Provide the 7 `file_memory_*` tools.
4. Read `memories.md` and inject it as a user message.
5. Update the description sidecar and the index together on a write/delete.  
([state and context](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/FileMemory/FileMemoryProvider.cs#L127-L159), [write path](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/FileMemory/FileMemoryProvider.cs#L161-L209), [tool creation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/FileMemory/FileMemoryProvider.cs#L419-L432))

### State and persistence
The most important cross-SDK difference in this feature is the **namespace policy**.

- The Python default namespace is `session_id`. Memory is therefore isolated per session automatically even when the same store is shared.  
  ([scope semantics](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_file_memory.py#L243-L279), [default session isolation test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_file_memory.py#L224-L240))
- The .NET harness default creates a new working folder from `DateTime.UtcNow + Guid`. That is a more ephemeral default namespace than Python's.  
  ([harness default initializer](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs#L301-L312))

### Extension points
- Python takes a custom `scope`, a custom `AgentFileStore`, and custom instructions.  
  ([ctor](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_file_memory.py#L239-L268))
- .NET can change the working folder policy with a custom `stateInitializer`.  
  ([ctor](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/FileMemory/FileMemoryProvider.cs#L93-L114), [test custom subfolder](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/FileMemory/FileMemoryProviderTests.cs#L156-L176))

### Concurrency, streaming, and cancellation
- Python serializes write/delete/index rebuild with a single provider-level `_write_lock`.  
  ([lock](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_file_memory.py#L265-L268), [write path lock use](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_file_memory.py#L333-L343))
- .NET has the same policy with a `SemaphoreSlim _writeLock`.  
  ([field](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/FileMemory/FileMemoryProvider.cs#L86-L92), [write/delete lock use](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/FileMemory/FileMemoryProvider.cs#L181-L208), [delete lock use](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/FileMemory/FileMemoryProvider.cs#L247-L260))
- There is no separate streaming path and it follows the ordinary tool invocation model.  
  ([Python regular function tools](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_file_memory.py#L315-L499), [.NET CreateTools](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/FileMemory/FileMemoryProvider.cs#L419-L432))

### Errors and validation
- Python rejects nested paths and reserved internal names.  
  ([validation in write](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_file_memory.py#L319-L330), [tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_file_memory.py#L255-L265))
- Even when the index read is broken, Python does not block the run and only skips the injection.  
  ([self-heal comment](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_file_memory.py#L501-L508))
- .NET throws an `ArgumentException` on a nested path/internal file name.  
  ([ValidateMemoryFileName](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/FileMemory/FileMemoryProvider.cs#L517-L538))

### Security
- File memory is approval-free, but it reduces accidental corruption with a flat namespace and internal file hiding.  
  ([Python flat namespace rule](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_file_memory.py#L111-L122), [.NET flat namespace rule](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/FileMemory/FileMemoryProvider.cs#L510-L538))

### .NET implementation and tests
- The tests verify the tool count, the description sidecar, stale description deletion, and a custom working subfolder.  
  ([tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/FileMemory/FileMemoryProviderTests.cs#L47-L176))

### Python implementation and tests
- The tests verify that internal files are hidden in list/search and that cross-session sharing is possible through an explicit shared scope.  
  ([hide internal files](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_file_memory.py#L203-L221), [shared scope](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_file_memory.py#L242-L252))

### Documentation differences
Unlike the “harness memory experimental” statement of the Python package status, `FileMemoryProvider` itself is graduated in the code and the tests. It is therefore right to read **structured memory and file memory separately**.  
([package status](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L120-L124), [graduated test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_file_memory.py#L291-L294))

### Java decisions
It is included in the Java MVP. For the default namespace, however, Python's `session_id`-scoped policy is better than .NET's timestamp plus GUID in terms of predictability and reproducibility.  
([Python scope](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_file_memory.py#L243-L279), [.NET default initializer](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs#L301-L312))

### Acceptance scenarios
1. After a memory file is stored, the `memories.md` index must be injected as user context on the next run.  
   ([Python test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_file_memory.py#L176-L201))
2. The reserved internal names (`memories.md`, `*_description.md`) must be rejected for storage.  
   ([Python test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_file_memory.py#L255-L265), [.NET validation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/FileMemory/FileMemoryProvider.cs#L523-L538))
3. Using an explicit shared scope must see the same memory namespace across sessions.  
   ([Python test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_file_memory.py#L242-L252))

---

## 8. File access

### Purpose
File access provides **shared, persistent CRUD/search/edit** capability over the working area the user opened. Its main uses are reading input data, writing output artifacts, and making small edits to existing files.  
([Python module docstring](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_file_access.py#L3-L20), [.NET remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/FileAccess/FileAccessProvider.cs#L17-L48))

### Boundary
- Unlike file memory it is a **shared store**.
- Unlike skills/resource loading it is general file manipulation over a user-visible workspace.
- It does not perform transcript/semantic extraction the way structured memory does.  
  ([Python distinction](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_file_access.py#L5-L19), [.NET distinction](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/FileAccess/FileAccessProvider.cs#L23-L33))

### Maturity
- **Python**: it is an experimental harness feature.  
  ([package status](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L120-L124), [provider marker](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_file_access.py#L1203-L1204))
- **.NET**: `FileAccessProvider` and `FileSystemAgentFileStore` are experimental.  
  ([provider marker](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/FileAccess/FileAccessProvider.cs#L76-L77), [store marker](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/FileStore/FileSystemAgentFileStore.cs#L31-L32))

### Public APIs and types
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

### Detailed execution flow
#### Python
- It injects `file_access_write/read/delete/ls/grep/replace/replace_lines`.
- By default **every tool is approval-required**.
- Read-only tool approval and write tool approval can be configured independently per group.
- With `disable_write_tools=True` the write-family tools themselves are hidden.  
([tool set and policy](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_file_access.py#L1229-L1253), [init options](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_file_access.py#L1296-L1337), [tool injection](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_file_access.py#L1443-L1589))

#### .NET
- It builds the same 7-tool surface as `AIFunction` objects and wraps the groups that require approval in `ApprovalRequiredAIFunction`.
- The read-only and write approval groups are separated, and the write tools can be hidden entirely.  
([remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/FileAccess/FileAccessProvider.cs#L46-L74), [tool creation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/FileAccess/FileAccessProvider.cs#L449-L479))

### State and persistence
- The provider itself is stateless, and the supplied `AgentFileStore` takes charge of persistence.  
  ([Python provider fields](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_file_access.py#L1332-L1343), [.NET StateKeys empty test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/FileAccess/FileAccessProviderTests.cs#L205-L216))
- The Python default harness does not attach file access at all.  
  ([opt-in wiring](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_agent.py#L189-L198))
- .NET likewise attaches it only when a `FileAccessStore` is present.  
  ([provider assembly](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs#L315-L318))

### Extension points
- Python provides a custom store, approval flags, and a hide-write-tools flag.  
  ([ctor](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_file_access.py#L1296-L1337))
- .NET adjusts the same axes through `FileAccessProviderOptions`.  
  ([harness options remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgentOptions.cs#L256-L275))

### Concurrency, streaming, and cancellation
- Python serializes write/delete/replace/replace_lines with a single async lock.  
  ([write lock comment](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_file_access.py#L1338-L1343))
- .NET likewise uses a provider-level semaphore.  
  ([semaphore field](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/FileAccess/FileAccessProvider.cs#L134-L140))
- There is no separate streaming-only implementation and it follows the ordinary tool invocation/approval flow.  
  ([Python tools are regular function tools](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_file_access.py#L1447-L1589), [.NET CreateTools regular AIFunctions](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/FileAccess/FileAccessProvider.cs#L449-L479))

### Errors and validation
- While normalizing the path, Python rejects a rooted path, a drive root, `.`, `..`, and a trailing separator.  
  ([normalize path](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_file_access.py#L128-L190))
- The regex search has a length cap of 256 and a worker-thread wall-clock timeout of 10 seconds.  
  ([regex guard](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_file_access.py#L68-L125))
- A non-UTF-8 file is a clean `ValueError` on read and is skipped plus logged in search.  
  ([read utf-8 error](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_file_access.py#L932-L960), [search utf-8 skip](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_file_access.py#L1065-L1095))
- The .NET file-system store uses a regex timeout of 5 seconds and root-containment/reparse-point rejection.  
  ([regex timeout](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/FileStore/FileSystemAgentFileStore.cs#L179-L180), [safe path resolution](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/FileStore/FileSystemAgentFileStore.cs#L307-L374))

### Security
- In both Python and .NET, approval is the default boundary.  
  ([Python default approval boundary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_file_access.py#L1230-L1247), [.NET default approval boundary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/FileAccess/FileAccessProvider.cs#L50-L74))
- Both sides carry an explicit security warning about name-based auto-approval rule collisions.  
  ([Python read-only rule warning](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_file_access.py#L1375-L1382), [Python all-tools warning](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_file_access.py#L1411-L1420), [.NET read-only rule warning](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/FileAccess/FileAccessProvider.cs#L187-L191), [.NET all-tools warning](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/FileAccess/FileAccessProvider.cs#L224-L228))
- The Python filesystem store states explicitly that it is for a single/cooperative tenant rather than a hostile co-tenant sandbox.  
  ([threat model remark](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_file_access.py#L780-L789))

### .NET implementation and tests
- The tests verify the default 7 tools, the default all-tools-require-approval, the per-group opt-out, and the auto-approval rules.  
  ([tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/FileAccess/FileAccessProviderTests.cs#L40-L158))

### Python implementation and tests
- The tests pin path normalization, in-memory/filesystem traversal rejection, symlink rejection, the regex timeout, non-UTF-8 handling, the approval mode distribution, and the `replace_lines` semantics.  
  ([path tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_file_access.py#L67-L106), [filesystem symlink tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_file_access.py#L286-L315), [approval tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_file_access.py#L543-L605), [replace_lines tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_file_access.py#L1104-L1234))

### Documentation differences
The creation moment of `FileSystemAgentFileStore` differs between Python and .NET.

- Python: it does not create the root directory in the constructor and lazily creates it on write/create_directory.  
  ([Python remark](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_file_access.py#L773-L779))
- .NET: it calls `Directory.CreateDirectory(fullRoot)` immediately in the constructor.  
  ([.NET ctor](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/FileStore/FileSystemAgentFileStore.cs#L45-L59))

Code first, the Java design has to decide in advance which semantics to adopt.

### Java decisions
It is excluded from the Java MVP. File access carries shared mutable state, an approval boundary, symlink/reparse hardening, and a regex guard all at once, so its surface is large. Introducing it as a separate module in a follow-up Java stage is appropriate.  
([Python experimental status](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L120-L124), [.NET experimental options](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgentOptions.cs#L256-L275))

### Acceptance scenarios
1. By default every file-access tool must be approval-required.  
   ([.NET test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/FileAccess/FileAccessProviderTests.cs#L54-L63))
2. Path traversal and symlink paths must be blocked on read/write/search.  
   ([Python normalize/store tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_file_access.py#L67-L106), [Python filesystem symlink test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_file_access.py#L295-L315), [.NET safe path](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/FileStore/FileSystemAgentFileStore.cs#L307-L374))
3. A runaway regex must be surfaced as a clean timeout/value error.  
   ([Python timeout guard](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_file_access.py#L106-L125), [.NET regex timeout](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/FileStore/FileSystemAgentFileStore.cs#L179-L180))

---

## 9. Structured memory

### Purpose
Structured memory combines a transcript archive with topic memory files to refine “the conversation records piled up along the way” into **durable memory organized by topic**. `MEMORY.md` is an always-loaded TOC, and the topic files and the transcript search are separated.  
([context prompt and files](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_memory.py#L30-L44), [MemoryContextProvider summary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_memory.py#L930-L987), [before_run injection](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_memory.py#L1180-L1346))

### Boundary
- This feature is **a separate layer rather than a higher-level replacement for file memory**.
- It is not a harness default built-in provider.
- No equivalent feature is visible in the .NET harness default built-in list; only `FileMemoryProvider` is there instead.  
  ([Python default built-ins exclude structured memory](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_agent.py#L145-L218), [.NET built-in provider list](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs#L36-L49), [.NET actual file memory wiring only](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs#L301-L312))

### Maturity
- **Python**: it is an experimental HARNESS feature.  
  ([experimental markers](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_memory.py#L244-L245), [provider marker](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_memory.py#L930-L931), [package status](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L120-L124))
- **.NET**: no harness-level equivalent structured memory provider was confirmed at this commit. In the code only file memory is stated as the default built-in.  
  ([.NET built-ins](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs#L36-L49), [.NET actual wiring](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs#L301-L312))

### Public APIs and types
- **Python**
  - `MemoryContextProvider`
  - `MemoryFileStore`
  - `MemoryStore`
  - `MemoryIndexEntry`
  - `MemoryTopicRecord`
  - memory tools: `list_memory_topics`, `read_memory_topic`, `write_memory`, `delete_memory_topic`, `search_memory_transcripts`, `consolidate_memories`
  
  ([types](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_memory.py#L245-L349), [store](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_memory.py#L655-L931), [tools](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_memory.py#L1203-L1290))
- **.NET**
  - No equivalent public type in that harness scope was confirmed. Among the built-in memory-related types, only `FileMemoryProvider` is assembled.  
    ([.NET harness wiring](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs#L301-L312))

### Detailed execution flow
#### Python
`before_run`:

1. rebuild/get the topic index
2. select the recent transcript turns
3. select the topic files to auto-load based on the input message
4. inject the memory tools
5. inject the recent turns and `MEMORY.md` plus the loaded topic files as user messages.  
([before_run core](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_memory.py#L1180-L1346))

`after_run`:

1. store the transcript archive
2. update the maintenance state
3. extract memory candidates with the extractor model
4. merge into the topic files
5. consolidate when the consolidation cadence has come  
([after_run core](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_memory.py#L1348-L1396), [extract](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_memory.py#L1398-L1470), [consolidate](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_memory.py#L1505-L1657))

### State and persistence
- The on-disk structure is `MEMORY.md`, `topics/`, `transcripts/`, and `state.json`.  
  ([store layout](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_memory.py#L655-L758))
- The owner routing metadata is stored in session state, and the actual memory root path is derived under the encoded owner id plus source id.  
  ([owner id routing](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_memory.py#L700-L745))

### Extension points
- The custom extraction prompt / consolidation prompt / consolidation client / history filter / selection limits / cadence can all be changed.  
  ([provider ctor options](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_memory.py#L941-L987))

### Concurrency, streaming, and cancellation
- There are async locks per topic and per maintenance state.  
  ([locks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_memory.py#L1025-L1050))
- Consolidation performs the LLM call outside the state lock to avoid a long block.  
  ([comment and flow](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_memory.py#L1513-L1526))

### Errors and validation
- An extraction model failure, invalid JSON, and a malformed payload are skipped after a warning.  
  ([extract failure paths](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_memory.py#L1412-L1442))
- Consolidation likewise skips on an empty/invalid/malformed payload.  
  ([consolidation failure paths](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_memory.py#L1602-L1645))
- Owner id path traversal is rejected.  
  ([validation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_memory.py#L700-L710), [test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_memory.py#L216-L231))

### Security
- A topic file can have several session contributors, and the provider attaches cross-session origin_session_ids to downstream observers.  
  ([origin propagation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_memory.py#L1317-L1346))
- The instructions state that raw transcript search should be used only when exact historical detail is needed.  
  ([instructions](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_memory.py#L1295-L1312))

### .NET implementation and tests
- No .NET source/test for a harness built-in structured memory was confirmed at this commit. The harness assembly is file-memory-only.  
  ([provider assembly](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs#L301-L312))

### Python implementation and tests
- The tests verify the file store topic/index/state/transcript search, owner path traversal rejection, and source_id namespacing.  
  ([tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_memory.py#L134-L245))

### Documentation differences
The “memory experimental” statement of the Python package status fits this structured memory exactly, but it is easy to confuse with file memory. On a code basis it is more accurate to read **only structured memory as experimental** and **file memory as graduated**.  
([package status](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L120-L124), [file memory graduated test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_file_memory.py#L291-L294))

### Java decisions
It is excluded from the Java MVP. Its surface is too large because it includes LLM-driven extraction/consolidation, topic selection, and a transcript archive, and it is experimental.  
([provider experimental status](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_memory.py#L930-L931))

### Acceptance scenarios
1. The agent run must continue even when the memory extraction call fails.  
   ([failure handling](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_memory.py#L1412-L1442))
2. Path traversal in an owner id must be rejected without a file system escape.  
   ([validation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_memory.py#L700-L710), [test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_memory.py#L216-L231))
3. Loaded topic files must expose the cross-session origin provenance along with them.  
   ([origin propagation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_memory.py#L1317-L1346))

---

## 10. Tool approval

### Purpose
Tool approval is the core safety layer where the harness handles **“tools requiring human approval” together with “tool auto-approval/standing rules”**. Beyond one-off prompt/response, its purpose is to keep queued approvals, replay, hidden mixed batches, and the hosted/local boundary consistent for the whole session.  
([Python middleware summary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_tool_approval.py#L343-L349), [.NET middleware summary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/ToolApproval/ToolApprovalAgent.cs#L17-L19))

### Boundary
- Approval is a **harness safety coordinator** on top of the basic tool contract.
- Features such as `FileAccessProvider`/`SkillsProvider`/shell/codeact can each create approval-required tools, but the queued presentation and the standing rule persistence are handled by the approval layer.  
  ([Python file access approval boundary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_file_access.py#L1230-L1247), [Python skills approval boundary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_skills.py#L1862-L1876), [.NET file access approval boundary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/FileAccess/FileAccessProvider.cs#L50-L74))

### Maturity
- **Python**: it looks like a stable public export and is harness default middleware.  
  ([public export](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/__init__.py#L565-L568), [default assembly](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_agent.py#L636-L646))
- **.NET**: `ToolApprovalAgent` is also a harness default decorator.  
  ([default assembly](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs#L151-L159))

### Public APIs and types
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

### Detailed execution flow
#### Python
1. Collect the `function_approval_response` from the caller inbound message and reflect it into the state.
2. When there are queued approval requests, re-evaluate them against the new standing rule.
3. When some remain, return only the next queued request to the caller.
4. When the queue is empty, call the inner agent.
5. Look for `function_approval_request` in the outbound message.
6. Classify them into rule match / heuristic auto-approval / first unresolved visible / remaining unresolved queued.
7. When they are all auto-approved, re-inject the approval responses as a user message and call the inner agent again.  
([process main](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_tool_approval.py#L381-L420), [inbound processing](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_tool_approval.py#L504-L555), [queue drain / outbound classify](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_tool_approval.py#L557-L628))

#### .NET
1. Strip the “always approve” wrapper from the inbound side and store the standing rule.
2. When there are existing queued requests, re-evaluate them against the new rule.
3. When a queue remains, surface only the next request.
4. Call the inner agent.
5. When every surfaced approval request is auto-approved, call the inner agent again inside the same run.
6. This re-call loop repeats only under a separate cap (`MaxAutoApprovalIterations`).  
([remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/ToolApproval/ToolApprovalAgent.cs#L20-L51), [run loop](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/ToolApproval/ToolApprovalAgent.cs#L119-L184))

### State and persistence
- The Python state payload is `rules`, `queued_approval_requests`, and `collected_approval_responses`.  
  ([ToolApprovalState](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_tool_approval.py#L158-L215))
- .NET likewise stores the rules and the queued state in the session state bag, and the approval carries over into the “next run” as well.  
  ([remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/ToolApproval/ToolApprovalAgent.cs#L44-L50))

### Extension points
- Python applies heuristic `auto_approval_rules` callbacks in order.  
  ([ctor/security note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_tool_approval.py#L351-L377), [auto rule match](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_tool_approval.py#L618-L628))
- .NET likewise provides `ToolApprovalAgentOptions.AutoApprovalRules`.  
  ([options](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/ToolApproval/ToolApprovalAgentOptions.cs#L24-L48))

### Concurrency, streaming, and cancellation
- Python collects the approval requests on the streaming path and then classifies them as a batch.  
  ([streaming](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_tool_approval.py#L435-L502))
- .NET likewise flows non-approval content immediately and re-enters when necessary after reclassifying the approval requests.  
  ([streaming](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/ToolApproval/ToolApprovalAgent.cs#L187-L280))

### Errors and validation
- Python cannot use the approval middleware without a session.  
  ([RuntimeError](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_tool_approval.py#L383-L386))
- A Python argument-scoped rule fixes a no-arg call as `{}` as well, preventing wildcard widening.  
  ([serialized args contract](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_tool_approval.py#L50-L58))
- Hosted tool approvals are reapplied only to calls with the same `server_label`.  
  ([server label](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_tool_approval.py#L61-L64), [rule match](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_tool_approval.py#L308-L321))
- .NET rejects an auto-approval cap below 1.  
  ([test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/ToolApproval/ToolApprovalAgentTests.cs#L2818-L2834))

### Security
- A name-based auto-approval collision is an explicit risk on both sides.  
  ([Python warning](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_tool_approval.py#L365-L376), [.NET warning](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/ToolApproval/ToolApprovalAgentOptions.cs#L41-L46))
- The .NET `AllToolsAutoApprovalRule` approves every tool, so it states explicitly that it must be used only in a fully trusted context.  
  ([rule warning](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/ToolApproval/ToolApprovalAgent.cs#L84-L109))

### .NET implementation and tests
- The tests verify rule persistence, deferred auto-approve, cap hit usage aggregation, and the default cap count.  
  ([basic tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/ToolApproval/ToolApprovalAgentTests.cs#L71-L220), [cap tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/ToolApproval/ToolApprovalAgentTests.cs#L2618-L2834))

### Python implementation and tests
- The tests verify that an approval resume does not mutate the caller-owned message, the reasoning plus function_call group replay, mixed hidden batch isolation, the hosted server boundary, and budget sharing.  
  ([resume immutability](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_tool_approval.py#L45-L105), [reasoning replay](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_tool_approval.py#L108-L220), [hosted boundary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_tool_approval.py#L1051-L1108), [budget sharing](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_tool_approval.py#L814-L914))

### Documentation differences
There is no large discrepancy, but the difference in budget handling is larger in the code than at the documentation level.

- Python: the approval middleware shares the function invocation budget state.
- .NET: the approval decorator has a separate `MaxAutoApprovalIterations` cap.  
  ([Python shared budget state hook](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_tool_approval.py#L26-L27), [Python injection of budget state](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_tool_approval.py#L387-L389), [.NET cap rationale](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/ToolApproval/ToolApprovalAgentOptions.cs#L50-L68))

### Java decisions
It must be included in the Java MVP. The following three elements in particular have to be taken along together.

1. standing rule persistence  
2. one-by-one queued approval surface  
3. distinguishing the hosted/local boundary  

Because the Python implementation is more direct, it is appropriate to reference the Python side first for the state machine design.  
([Python state machine core](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_tool_approval.py#L381-L420), [hosted boundary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_tool_approval.py#L308-L321))

### Acceptance scenarios
1. Even when several approval requests arise at once, only one at a time must be surfaced to the caller.  
   ([Python test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_tool_approval.py#L649-L705), [.NET remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/ToolApproval/ToolApprovalAgent.cs#L26-L35))
2. `always approve tool with arguments` must apply only to the exact arguments.  
   ([Python serialization/match contract](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_tool_approval.py#L50-L58), [match logic](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_tool_approval.py#L301-L321))
3. A hosted tool standing rule must be reapplied only for the same `server_label`.  
   ([Python test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_tool_approval.py#L1051-L1108))
4. Even when the cap is reached, the total usage must be the sum over all inner turns.  
   ([.NET cap usage test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/ToolApproval/ToolApprovalAgentTests.cs#L2618-L2779))

---

## 11. Invocation budget

### Purpose
The invocation budget limits the **cost, side effects, and infinite re-call risk** when tool-calling and approval re-entry go on for a long time. From the harness perspective the “inner function invocation budget” and the “outer approval/loop budget” are both at issue.  
([Python budget docs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_tools.py#L1332-L1360), [.NET approval cap rationale](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/ToolApproval/ToolApprovalAgentOptions.cs#L50-L68))

### Boundary
- The function invocation core budget and the harness loop budget are distinguished.
- Compaction reduces the context window but does not substitute for a budget.
- With approval auto-reentry, an ordinary per-request iteration cap alone may not be enough.  
  ([Python core budget config](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_tools.py#L1374-L1403), [.NET separate approval cap](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/ToolApproval/ToolApprovalAgentOptions.cs#L57-L66))

### Maturity
- **Python**: the core function invocation configuration is exposed like a stable public config.  
  ([normalize config](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_tools.py#L1383-L1403))
- **.NET**: `MaximumIterationsPerRequest` is part of the harness option surface, and the approval re-entry budget is separated into `ToolApprovalAgentOptions`.  
  ([MaximumIterationsPerRequest](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgentOptions.cs#L178-L185), [MaxAutoApprovalIterations](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/ToolApproval/ToolApprovalAgentOptions.cs#L50-L68))

### Public APIs and types
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

### Detailed execution flow
#### Python
The Python function invocation core has a three-stage flow.

1. **Phase 1**: resolve the inbound approval responses first.
2. In the process it records the function call count into the budget state.
3. When necessary it switches to `tool_choice="none"` so that no more tools can be called.
4. **Phase 2**: repeat the model turn and the local execution.
5. When `max_function_calls` is reached it forces a fallback text response.
6. When `max_iterations` is reached it turns the tools off and takes one more final response.  
([non-streaming budget flow](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_tools.py#L2898-L2995), [streaming budget flow](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_tools.py#L3028-L3145))

#### .NET
- The inner function invocation loop is limited by `FunctionInvokingChatClient.MaximumIterationsPerRequest`.
- Approval auto-reentry, however, calls the inner agent anew, so the per-request cap resets every time.
- The approval layer therefore has a separate `MaxAutoApprovalIterations` cap.
- The loop decorator has yet another `LoopAgentOptions.MaxIterations`.  
  ([harness inner invocation config](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs#L232-L235), [approval cap rationale](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/ToolApproval/ToolApprovalAgent.cs#L139-L145), [approval cap options](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/ToolApproval/ToolApprovalAgentOptions.cs#L57-L66), [loop cap](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Loop/LoopAgentOptions.cs#L17-L26))

### State and persistence
- Python stores `_function_invocation_budget_state` in `client_kwargs` to carry `total_function_calls` and `attempt_count` across approval re-entries.  
  ([budget state key](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_tool_approval.py#L26-L27), [injection](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_tool_approval.py#L387-L389), [core consumption](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_tools.py#L2902-L2911))
- The .NET approval cap does not expose a separately persisted budget state and limits the number of auto-reentries inside the same run.  
  ([options semantics](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/ToolApproval/ToolApprovalAgentOptions.cs#L50-L68))

### Extension points
- Python can adjust the function invocation configuration per chat client.  
  ([example and config](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_tools.py#L1362-L1371))
- .NET tunes the loop/approval/invocation caps independently.  
  ([HarnessAgentOptions](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgentOptions.cs#L149-L185), [ToolApprovalAgentOptions](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/ToolApproval/ToolApprovalAgentOptions.cs#L50-L68))

### Concurrency, streaming, and cancellation
- Python maintains the same budget semantics in both streaming and non-streaming.  
  ([non-streaming](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_tools.py#L2898-L2995), [streaming](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_tools.py#L3028-L3145))
- The .NET auto-approval cap tests show that whole-run usage aggregation is maintained even on the capped path.  
  ([test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/ToolApproval/ToolApprovalAgentTests.cs#L2618-L2779))

### Errors and validation
- Python enforces `max_iterations>=1` and `max_function_calls>=1 or None`.  
  ([validation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_tools.py#L1397-L1403))
- .NET rejects `MaxAutoApprovalIterations < 1`.  
  ([test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/ToolApproval/ToolApprovalAgentTests.cs#L2818-L2834))

### Security
A budget does not restrict privileges directly the way a sandbox or an approval does, but it is an operational safety control that prevents **cost explosion and repeated side effects**. The Python documentation also treats `max_function_calls` as the primary knob for preventing runaway tool usage.  
([docs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_tools.py#L1336-L1345))

### .NET implementation and tests
- The approval cap tests verify that the prior turn usage is not missing from the final response usage after the cap is hit.  
  ([test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/ToolApproval/ToolApprovalAgentTests.cs#L2618-L2779))
- It is also tested that the default cap causes `DefaultMaxAutoApprovalIterations + 1` inner calls.  
  ([test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/ToolApproval/ToolApprovalAgentTests.cs#L2781-L2815))

### Python implementation and tests
- The tests verify that an auto-approved re-entry consumes the shared `max_function_calls` budget and that approval resolution still happens first after the iteration budget is exhausted.  
  ([budget-share test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_tool_approval.py#L814-L865), [iteration-budget test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_tool_approval.py#L869-L914))

### Documentation differences
The cross-SDK semantics differ.

- Python binds approval re-entry into the same request budget through a **shared budget state**.
- .NET has a **separate cap on the approval loop**.

When designing Java code first, this difference has to be chosen explicitly.  
([Python shared state](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_tool_approval.py#L387-L389), [.NET separate cap](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/ToolApproval/ToolApprovalAgentOptions.cs#L50-L68))

### Java decisions
Java is better off adopting the Python-style shared budget state first. Because approval re-entry is counted inside the same logical request budget, explainability is better. A .NET-style outer cap can also be added as an extra operational safeguard.  
([Python shared budget state](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_tool_approval.py#L387-L389), [.NET cap rationale](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/ToolApproval/ToolApprovalAgent.cs#L139-L145))

### Acceptance scenarios
1. With `max_function_calls=1`, the second tool call after an auto-approved re-entry must be blocked.  
   ([Python test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_tool_approval.py#L814-L865))
2. Even when the iteration budget is exhausted, the result of the last approval resolution (`function_result`) must be surfaced.  
   ([Python test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_tool_approval.py#L869-L914))
3. After the .NET auto-approval cap is hit, the final response usage must be the sum of all previous inner turn usage.  
   ([test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/ToolApproval/ToolApprovalAgentTests.cs#L2618-L2779))

---

## 12. Java decision summary

### Included
- Harness assembler
- Todo provider
- Mode provider
- File memory
- Tool approval
- Shared invocation budget state
- Basic loop middleware/predicate

### Excluded
- File access
- Structured memory
- Shell auto-wiring
- Background tasks
- Code execution backends

### Recommended default policy
1. **skills/file access/background/code execution are opt-in**
2. **approval is on by default**
3. **the file memory namespace is `session_id` based**
4. **the loop exits immediately on a pending approval**
5. **the invocation budget is shared with approval re-entry**

This combination is a compromise between Python's conservative defaults and .NET's explicit decorator/middleware layering.  
([Python conservative opt-ins](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_agent.py#L189-L205), [.NET explicit outer layers](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs#L137-L161), [Python shared budget state](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_tool_approval.py#L387-L389))

---

## 13. Concrete acceptance scenarios

### Composition
- On default harness creation the built-in provider set must include only the expected defaults.  
  ([Python default provider test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_agent.py#L93-L110))

### Loop
- With the execute mode plus open todos combination the autonomous loop must keep running.  
  ([Python helper logic](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_loop.py#L876-L937))

### Todo
- The add/complete/remove tools must share the same session state and accumulate without ID collisions.  
  ([Python session store](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_todo.py#L245-L285), [.NET tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/Todo/TodoProviderTests.cs#L44-L148))

### Mode
- An external mode change must be re-announced to the model on the next turn prompt surface.  
  ([Python notification injection](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_mode.py#L315-L325))

### File memory
- After a save, the index must be rebuilt and injected on a subsequent run.  
  ([Python test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_file_memory.py#L176-L201), [.NET injection path](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/FileMemory/FileMemoryProvider.cs#L144-L155))

### File access
- The default must be approval-required, and an opt-out flag must affect only that group.  
  ([Python provider flags](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_file_access.py#L1302-L1330), [.NET tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/FileAccess/FileAccessProviderTests.cs#L54-L116))

### Structured memory
- An extractor/consolidator failure must not spread into a user-visible turn failure.  
  ([extract failure handling](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_memory.py#L1412-L1442), [consolidation failure handling](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_memory.py#L1602-L1645))

### Tool approval
- Multiple pending approvals must be surfaced one by one.  
  ([Python queue behavior](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_tool_approval.py#L566-L600), [.NET remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/ToolApproval/ToolApprovalAgent.cs#L26-L35))

### Invocation budget
- Even with approval re-entry, the number of function calls must be consumed inside the same logical request budget.  
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