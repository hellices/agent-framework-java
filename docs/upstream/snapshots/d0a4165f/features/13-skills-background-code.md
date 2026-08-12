# 13. Skills · Background · Code Execution

## 1. Document purpose and scope

This document covers the following feature groups of the Microsoft Agent Framework.

1. **The skills source/provider and the resource/script model**
2. **Background agents / background tasks**
3. **Shell environment provider / shell executors**
4. **LocalCodeAct**
5. **Hyperlight CodeAct**
6. **Monty CodeAct**

This document does not restate the harness assembly rules or the compaction algorithm. Where these features connect to the harness is described only as a boundary.  
- The Python harness connects `skills_provider`/`skills_paths`, `background_agents`, and `shell_executor` into the assembly.  
  ([Python harness assembly surface](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_agent.py#L302-L344), [Python provider assembly](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_agent.py#L145-L218))
- The .NET harness assembles skills and background agents as built-in/optional providers, but **wires shell and code execution manually** from separate packages.  
  ([.NET harness built-in/optional providers](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs#L36-L49), [.NET harness actual provider assembly](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs#L287-L344), [.NET shell sample manual wiring](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/samples/02-agents/Agents/Agent_Step21_ShellWithEnvironment/Program.cs#L57-L119))

---

## 2. Common comparison: support scope and the dividing lines

### 2.1 Python
The inspected optional execution/connectors on the Python side split along the following axes.

- `SkillsProvider` and `BackgroundAgentsProvider` inside the core package
- shell tooling in the separate `agent-framework-tools`
- separate `agent-framework-hyperlight`
- separate `agent-framework-monty`

`agent_framework.hyperlight`, `agent_framework.monty`, and `agent_framework.tools` appear only as lazy-loading namespaces.  
([core public exports for skills/background](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/__init__.py#L531-L539), [background helper re-exports](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/__init__.py#L602-L603), [hyperlight namespace](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/hyperlight/__init__.py#L3-L35), [monty namespace](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/monty/__init__.py#L3-L35), [tools namespace](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/tools/__init__.py#L3-L48), [package status for hyperlight/monty](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L42-L46))

### 2.2 .NET
The inspected optional execution/connectors on the .NET side split along the following axes.

- `AgentSkillsProvider` and `BackgroundAgentsProvider` in `Microsoft.Agents.AI`
- `Microsoft.Agents.AI.Tools.Shell`
- `Microsoft.Agents.AI.Hyperlight`
- `Microsoft.Agents.AI.LocalCodeAct`
- the MCP skills extension in `Microsoft.Agents.AI.Mcp`

That is, **.NET has LocalCodeAct as a separate package**, while on the Python side, on the inspected inventory basis, only Hyperlight/Monty appear as separate code-execution packages.  
([.NET AgentSkillsProvider](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Skills/AgentSkillsProvider.cs#L18-L52), [.NET BackgroundAgentsProvider](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/BackgroundAgents/BackgroundAgentsProvider.cs#L18-L48), [.NET Hyperlight README](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hyperlight/README.md#L7-L17), [.NET LocalCodeAct provider](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/LocalCodeActProvider.cs#L15-L31), [.NET MCP skills builder extension](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Mcp/Skills/AgentSkillsProviderBuilderMcpExtensions.cs#L8-L35))

---

## 3. Skills

### 3.1 Purpose and boundary
Skills provide domain-specific instructions/resources/scripts through **progressive disclosure**. The base system prompt advertises only the skill name/description, and the actual body/resources/scripts are pulled out through tools only when needed.  
([Python progressive disclosure](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_skills.py#L1829-L1849), [.NET progressive disclosure](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Skills/AgentSkillsProvider.cs#L22-L31))

Unlike search/memory/background work, this feature handles **work instructions and the attached execution assets**. Because script execution can also be involved, it carries a higher risk than a simple prompt snippet.  
([Python skills security note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_skills.py#L1851-L1860), [.NET skills security note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Skills/AgentSkillsProvider.cs#L41-L50))

### 3.2 Maturity
- **Python**
  - `SkillsProvider`, `SkillsSource`, and the file/in-memory/class-based skill model are exposed like a stable public surface.  
    ([public exports](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/__init__.py#L531-L539))
  - `MCPSkillResource`, `MCPSkill`, and `MCPSkillsSource` are experimental per the package status.  
    ([package status](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L129-L132), [MCPSkill experimental](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_skills.py#L4227-L4228), [MCPSkillsSource experimental](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_skills.py#L4858-L4859))
- **.NET**
  - The inspected `AgentSkillsProvider` / `AgentSkillsSource` surface carries no experimental marker.
  - MCP skills are separated into a separate builder extension and source package.  
    ([AgentSkillsProvider header](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Skills/AgentSkillsProvider.cs#L18-L52), [AgentSkillsSource header](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Skills/AgentSkillsSource.cs#L10-L33), [MCP builder extension](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Mcp/Skills/AgentSkillsProviderBuilderMcpExtensions.cs#L8-L35))

### 3.3 Public APIs and types
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

### 3.4 Detailed execution flow
#### Python
`SkillsProvider.before_run(...)` builds a `SkillsSourceContext(agent, session)` from the run context, takes the skills from the source, and builds the system prompt and the tools. When there is not a single skill it injects nothing.  
([before_run](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_skills.py#L2392-L2429))

There are always three tools on the surface.

- `load_skill`
- `read_skill_resource`
- `run_skill_script`

Each tool can have its own approval disable flag.  
([provider ctor flags](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_skills.py#L2036-L2047), [tool construction](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_skills.py#L2450-L2540))

#### .NET
`AgentSkillsProvider.ProvideAIContextAsync(...)` takes the skills from the source and builds the prompt and the tools. When there is no skill it falls back to the base context.  
([ProvideAIContextAsync](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Skills/AgentSkillsProvider.cs#L279-L291))

There are three tools on the surface here as well.

- `load_skill`
- `read_skill_resource`
- `run_skill_script`

Each tool can have its own approval wrapper attached.  
([BuildTools](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Skills/AgentSkillsProvider.cs#L314-L340))

### 3.5 State and persistence
#### Python
- The dedupe/cache wrapper is attached only when the provider builds a built-in source itself.
- A caller-supplied `SkillsSource` is not deduped/cached automatically. This is to prevent cross-tenant replay of a context-aware source.  
  ([provider ctor caching rules](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_skills.py#L2054-L2061), [disable_caching/cache_refresh_interval semantics](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_skills.py#L2080-L2095))

#### .NET
- A source pipeline built by the convenience constructors / builder is owned and disposed by the provider.
- Ownership of a caller-supplied `AgentSkillsSource` can remain on the caller side depending on the options.  
  ([ownership remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Skills/AgentSkillsProvider.cs#L33-L39), [Dispose behavior](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Skills/AgentSkillsProvider.cs#L294-L312))

### 3.6 Extension points
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

### 3.7 Concurrency, streaming, and cancellation
- Skills do not create a separate stream protocol and follow the ordinary tool invocation path.
- Python file/inline scripts support sync/async callables, and a file script can await an external runner.  
  ([InlineSkillScript run semantics](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_skills.py#L407-L446), [FileSkillScript run semantics](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_skills.py#L514-L539))
- The .NET resource/script base classes both take a cancellation token and pass it to the async I/O/runner.  
  ([AgentSkillResource.ReadAsync](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Skills/AgentSkillResource.cs#L36-L42), [AgentSkillScript.RunAsync](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Skills/AgentSkillScript.cs#L42-L50))

### 3.8 Errors and validation
#### Python
- `SkillFrontmatter` validates the name/description/compatibility at creation time.
- `FileSkillScript` enforces an absolute path and raises an error at run time when there is no runner.
- `_FileSkillResource.read()` raises a `ValueError` when the file is missing.  
  ([frontmatter validation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_skills.py#L628-L716), [FileSkillScript validation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_skills.py#L474-L539), [_FileSkillResource.read](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_skills.py#L269-L285))
- Inline scripts expect `dict` args and explicitly reject a string/array mismatch.  
  ([InlineSkillScript arg checks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_skills.py#L432-L446))

#### .NET
- A file skill script without a runner is an `InvalidOperationException`.
- The resource/script/tool name empty checks are blocked by the base types/logic.
- `ReadSkillResourceAsync` and `RunSkillScriptAsync` return a model-readable error string when the skill/resource/script is missing.  
  ([AgentFileSkillScript runner requirement](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Skills/File/AgentFileSkillScript.cs#L49-L63), [resource/script base ctor checks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Skills/AgentSkillResource.cs#L18-L23), [provider error strings](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Skills/AgentSkillsProvider.cs#L361-L449))

### 3.9 Security
#### Python
- File-based metadata is XML-escaped before entering the prompt.
- The file source defends against traversal/symlink escape.
- An external skill source is a trust boundary, and scripts are approval-required by default.
- MCP archive skills apply zip-slip/link escape/decompression bomb hardening.  
  ([XML skill content building](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_skills.py#L718-L757), [provider security note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_skills.py#L1847-L1860), [SkillsSource trust-boundary note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_skills.py#L2759-L2771), [MCP skills security note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_skills.py#L4891-L4904))
#### .NET
- The skill source itself is a trust boundary.
- `run_skill_script` can execute a script that came from an untrusted source.
- `IncludeDetailedErrors=true` can feed an exception message prompt injection back into the model, so it warns that it must be used only with a trusted source.
- The file skills source checks for traversal/symlink escape during resource/script discovery.  
  ([AgentSkillsSource security note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Skills/AgentSkillsSource.cs#L20-L31), [AgentSkillsProvider security note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Skills/AgentSkillsProvider.cs#L41-L50), [IncludeDetailedErrors warning](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Skills/AgentSkillsProviderOptions.cs#L17-L33), [file source traversal/symlink checks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Skills/File/AgentFileSkillsSource.cs#L314-L427), [script traversal/symlink checks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Skills/File/AgentFileSkillsSource.cs#L439-L549))
- The skill tool auto-approval rules also state the name collision risk explicitly.  
  ([.NET read-only rule warning](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Skills/AgentSkillsProvider.cs#L78-L107), [all-tools rule warning](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Skills/AgentSkillsProvider.cs#L111-L142))

### 3.10 .NET implementation and tests
- The provider injects nothing when there is no skill, and returns the prompt plus 3 tools when there is one.  
  ([ProvideAIContextAsync](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Skills/AgentSkillsProvider.cs#L279-L291))
- The file source implements the search depth, allowed extensions, script/resource filters, and symlink/traversal defense.  
  ([file source header](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Skills/File/AgentFileSkillsSource.cs#L20-L30), [resource scan](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Skills/File/AgentFileSkillsSource.cs#L314-L427), [script scan](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Skills/File/AgentFileSkillsSource.cs#L439-L549))
- This investigation did not read the .NET skills unit test bodies deeply and judged mainly on source-based verification.

### 3.11 Python implementation and tests
- `test_skills.py` broadly covers file discovery, path normalization, source decorators, and context-aware source helpers.  
  ([test file header/imports](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_skills.py#L1-L50), [helper functions for discovery](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_skills.py#L170-L217), [symlink support helper](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_skills.py#L124-L137))

### 3.12 Documentation differences
The biggest difference is the **default harness enablement**.

- Python harness: skills are opt-in
- .NET harness: file skills based on the current working directory are on by default

It is more accurate to see this as a difference in the **operational default** than as a difference in the skills design itself.  
([Python harness opt-in](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_agent.py#L200-L205), [.NET harness default skills](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs#L320-L326))

### 3.13 Java decisions
- **MVP inclusion candidate**: read-only skills advertise/load/read-resource
- **Follow-up stage**: script execution, external MCP skills
- **Default**: an explicit opt-in is safer than harness default on.
- **approval**: keep `run_skill_script` approval-required by default, and separate things so that only the read-only tools can have their own auto-approval.  
  ([Python per-tool approval model](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_skills.py#L1862-L1876), [.NET per-tool approval model](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Skills/AgentSkillsProvider.cs#L314-L340))

### 3.14 Acceptance scenarios
1. When there is no skill the provider must not inject the prompt/tools.  
   ([.NET behavior](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Skills/AgentSkillsProvider.cs#L279-L285))
2. `load_skill`/`read_skill_resource`/`run_skill_script` must each have an independent approval policy.  
   ([Python flags](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_skills.py#L2043-L2045), [Python tool build](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_skills.py#L2481-L2539), [.NET provider options](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Skills/AgentSkillsProviderOptions.cs#L35-L66))
3. File-based skill resource/script discovery must not allow traversal or symlink escape.  
   ([Python security promise](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_skills.py#L1847-L1849), [.NET file source checks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Skills/File/AgentFileSkillsSource.cs#L391-L427), [.NET script checks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Skills/File/AgentFileSkillsSource.cs#L504-L539))
4. An MCP archive skill must not expose scripts as runnable and must expose them only as read-only resources.  
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

### 4.1 Purpose and boundary
The background agents feature lets a parent agent hand work to a child agent and have it performed asynchronously in a separate session/task. This feature is not “general multi-agent orchestration”; it provides a **task registry, state updates, result retrieval, resumption, and cleanup** at the harness/documented provider level.  
([Python module summary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_background_agents.py#L3-L8), [.NET provider summary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/BackgroundAgents/BackgroundAgentsProvider.cs#L18-L35))

This feature can connect naturally to the loop but is separate from the loop itself. The structure is that a loop continuation predicate/evaluator reads whether a background task has completed.  
([Python loop helper for background tasks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_loop.py#L796-L860), [.NET background completion evaluator](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Loop/BackgroundTaskCompletionLoopEvaluator.cs#L15-L35))

### 4.2 Maturity
- **Python**: it is an experimental HARNESS feature.  
  ([provider marker](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_background_agents.py#L52-L53), [package status](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L120-L124))
- **.NET**: `BackgroundAgentsProvider` and the related loop evaluator are experimental as well.  
  ([provider marker](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/BackgroundAgents/BackgroundAgentsProvider.cs#L47-L48), [completion evaluator marker](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/Loop/BackgroundTaskCompletionLoopEvaluator.cs#L35-L36))

### 4.3 Public APIs and types
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

### 4.4 Detailed execution flow
#### Python
`BackgroundAgentsProvider.before_run(...)` injects the following tools.

- `background_agents_start_task`
- `background_agents_wait_for_first_completion`
- `background_agents_get_task_results`
- `background_agents_get_all_tasks`
- `background_agents_continue_task`
- `background_agents_clear_completed_task`

`start_task` creates a dedicated session per child agent and runs it with `asyncio.create_task(...)`. `continue_task` reuses the existing session of a completed/failed task and re-executes it. `clear_completed_task` removes the runtime reference and the session reference.  
([before_run start](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_background_agents.py#L315-L363), [wait/get/results/all](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_background_agents.py#L365-L447), [continue/clear](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_background_agents.py#L448-L536))

#### .NET
.NET provides the same 6-tool surface and puts the task metadata into serializable state and the in-flight task/session handles into runtime state. `GetIncompleteTasks(...)` refreshes the current runtime task state and then returns only the running tasks.  
([provider surface](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/BackgroundAgents/BackgroundAgentsProvider.cs#L27-L35), [runtime refresh](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/BackgroundAgents/BackgroundAgentsProvider.cs#L120-L149), [task finalization](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/BackgroundAgents/BackgroundAgentsProvider.cs#L201-L259))

### 4.5 State and persistence
#### Python
- serializable provider state: `next_task_id`, `tasks`
- non-serializable runtime state: `in_flight_tasks`, `background_sessions`
- When the provider instance disappears or restarts there is no in-flight reference, so a running task turns into `LOST`.  
  ([provider state init/save](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_background_agents.py#L163-L186), [runtime state type](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_background_agents.py#L111-L116), [LOST transition](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_background_agents.py#L219-L231), [provider instance loss comment](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_background_agents.py#L302-L306))

#### .NET
- serializable `BackgroundAgentState`
- `[JsonIgnore]` runtime state for `Task<AgentResponse>` and `AgentSession`
- Because an empty runtime state is created after deserialization, previously running tasks are treated as `Lost`.  
  ([runtime state remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/BackgroundAgents/BackgroundAgentRuntimeState.cs#L9-L17), [runtime fields](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/BackgroundAgents/BackgroundAgentRuntimeState.cs#L20-L31))

### 4.6 Extension points
#### Python
- constructor `instructions` override
- available agent list text injection
- arbitrary `SupportsAgentRun` collection  
  ([ctor](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_background_agents.py#L268-L300))

#### .NET
- Custom agent list formatting and an instruction override can be given through `BackgroundAgentsProviderOptions`.  
  ([provider option usage](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/BackgroundAgents/BackgroundAgentsProvider.cs#L81-L102))

### 4.7 Concurrency, streaming, and cancellation
- Python uses genuine concurrent `asyncio.Task` objects.  
  ([task start](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_background_agents.py#L352-L358), [continue starts new task](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_background_agents.py#L475-L483))
- .NET also has a concurrent task model, but the provider tests verify mostly non-streaming task-state behavior.  
  ([provider task runtime](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/BackgroundAgents/BackgroundAgentsProvider.cs#L120-L149))
- There is no separate streaming task-update protocol. Background tasks are a tool-polled state model.  
  ([Python tool polling surface](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_background_agents.py#L365-L447), [.NET tool list](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/BackgroundAgents/BackgroundAgentsProvider.cs#L27-L35))

### 4.8 Errors and validation
- Rejection of an empty agent list, an empty name, and a duplicate name (case-insensitive)  
  ([Python validation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_background_agents.py#L129-L149), [.NET tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/BackgroundAgents/BackgroundAgentsProviderTests.cs#L20-L97))
- It returns an explicit text error in each of the unknown task / unknown agent / still running / LOST / no session states.  
  ([Python result/continue/clear errors](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_background_agents.py#L416-L510), [.NET tests for running/failed/continue/clear](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/BackgroundAgents/BackgroundAgentsProviderTests.cs#L307-L443), [continue/clear assertions](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/BackgroundAgents/BackgroundAgentsProviderTests.cs#L559-L732))

### 4.9 Security
For background agents the **child agent itself is a trust boundary**. Both the text the parent agent hands over and the child output can become an untrusted data channel, and when the child tools/upstream model/system prompt are compromised, exfiltration or indirect prompt injection becomes possible.  
([Python security note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_background_agents.py#L258-L266), [.NET security note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/BackgroundAgents/BackgroundAgentsProvider.cs#L38-L45))

### 4.10 .NET implementation and tests
- Constructor validation, the 6-tool injection, the agent info in the instructions, and the running/completed/failed/continue/clear semantics are tested.  
  ([constructor/injection tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/BackgroundAgents/BackgroundAgentsProviderTests.cs#L20-L121), [runtime semantics tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/BackgroundAgents/BackgroundAgentsProviderTests.cs#L244-L520), [continue/clear tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/BackgroundAgents/BackgroundAgentsProviderTests.cs#L559-L732))

### 4.11 Python implementation and tests
- The tests pin the same set of constructor validation/tool injection/run-state behaviors.  
  ([tests header and constructor coverage](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_background_agents.py#L96-L145), [wait/result tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_harness_background_agents.py#L249-L319))

### 4.12 Documentation differences
No large documentation-code discrepancy was visible. On a code basis, however, the **runtime handle loss → LOST state transition** after a restart/deserialization is a very important operational contract, and the source comments state it more explicitly than the docs.  
([Python runtime-loss comment](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_background_agents.py#L302-L306), [.NET runtime-loss remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/BackgroundAgents/BackgroundAgentRuntimeState.cs#L9-L17))

### 4.13 Java decisions
- **Excluded from the MVP**
- Reasons:
  - experimental
  - it needs persistent runtime handle/session handle retention
  - the LOST semantics after a restart have to be designed well
  - the child agent trust boundary is large

Even when it is added in a follow-up stage, it is better to start with a **polling-style task registry** and to attach stream-style live updates later.  
([experimental status](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L120-L124), [runtime-state split](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Harness/BackgroundAgents/BackgroundAgentRuntimeState.cs#L20-L31))

### 4.14 Acceptance scenarios
1. Querying the result of a running task must return “still running”.  
   ([.NET test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/BackgroundAgents/BackgroundAgentsProviderTests.cs#L348-L379))
2. Querying the result of a failed task must return the failure text.  
   ([.NET test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/BackgroundAgents/BackgroundAgentsProviderTests.cs#L402-L443))
3. A running task must not be a target of clear/continue.  
   ([Python provider logic](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_background_agents.py#L462-L499), [.NET tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Harness/BackgroundAgents/BackgroundAgentsProviderTests.cs#L600-L732))
4. When the runtime refs disappear because of a provider restart, the task must be treated as LOST.  
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

### 5.1 Purpose and boundary
Shell tooling provides the capability that “the model emits a shell command and it is executed in the real environment”. Two layers are separated here.

1. **Shell executor/tool**: the actual command execution
2. **ShellEnvironmentProvider**: injecting the shell family/OS/installed CLI information into the system prompt

The environment provider is therefore a prompt steering layer rather than a tool.  
([Python ShellEnvironmentProvider docs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/README.md#L87-L119), [.NET ShellEnvironmentProvider summary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellEnvironmentProvider.cs#L14-L19), [.NET why instructions not messages](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellEnvironmentProvider.cs#L46-L57))

### 5.2 Maturity
- **Python**
  - The `agent-framework-tools` package is beta.  
    ([README](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/README.md#L1-L10))
  - The `shell_executor` feature that uses shell wiring in the harness is subject to a pre-release warning.  
    ([Python harness warning logic](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_agent.py#L273-L299))
- **.NET**
  - Shell is the separate package `Microsoft.Agents.AI.Tools.Shell`.
  - It is not a harness built-in, and manual composition is the norm.  
    ([package description](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/Microsoft.Agents.AI.Tools.Shell.csproj#L23-L23), [.NET harness no shell auto-wiring](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs#L287-L344))

### 5.3 Public APIs and types
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

### 5.4 Detailed execution flow
#### Python
- `LocalShellTool` / `DockerShellTool` connect to the agent as an ordinary `FunctionTool`.
- `ShellEnvironmentProvider.before_run(...)` caches the first-call probe and builds the instructions block with a formatter.  
  ([LocalShellTool usage contract](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_tool.py#L65-L139), [ShellEnvironmentProvider before_run and cache](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_environment.py#L103-L174))
- With the harness, a `create_harness_agent(...)` that received a `shell_executor` adds the shell tool and the provider automatically.  
  ([assembly helper](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_agent.py#L221-L262))

#### .NET
- `ShellExecutor.AsAIFunction(...)` builds the model-facing tool.
- `ShellEnvironmentProvider.ProvideAIContextAsync(...)` puts a snapshot probing the shell plus cwd plus tool versions into the instructions.
- The caller connects these two manually to the ordinary agent/context provider/tool surface.  
  ([ShellExecutor.AsAIFunction contract](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellExecutor.cs#L69-L88), [ShellEnvironmentProvider flow](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellEnvironmentProvider.cs#L98-L157), [sample manual composition](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/samples/02-agents/Agents/Agent_Step21_ShellWithEnvironment/Program.cs#L57-L119))

### 5.5 State and persistence
- **Local shell persistent mode** accumulates cwd/export/history/background jobs in the same shell process. Single-session ownership is therefore strongly required.  
  ([Python LocalShellTool single-session ownership](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_tool.py#L82-L92), [.NET ShellExecutor single-session ownership](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellExecutor.cs#L34-L47), [.NET LocalShellExecutor single-session ownership](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/LocalShellExecutor.cs#L32-L40))
- **Docker shell persistent mode** also accumulates long-lived container plus shell REPL state, so it has the same ownership rule.  
  ([Python DockerShellTool single-session ownership](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_docker.py#L27-L39), [.NET DockerShellExecutor single-session ownership](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/DockerShellExecutor.cs#L46-L57))
- The environment snapshot is a provider lifetime cache in Python and a first-call wins task cache plus explicit refresh in .NET.  
  ([Python snapshot cache](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_environment.py#L131-L174), [.NET snapshot task cache](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellEnvironmentProvider.cs#L61-L127))

### 5.6 Extension points
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

### 5.7 Concurrency, streaming, and cancellation
- Python persistent LocalShellTool lazily creates one `ShellSession` protected by an async lock.  
  ([session lock](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_tool.py#L189-L196), [persistent session start/close](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_tool.py#L200-L228))
- .NET shell executors follow async subprocess/container execution and timeout/cancellation token propagation.  
  ([ShellExecutor.RunAsync contract](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellExecutor.cs#L60-L67), [.NET environment provider propagates caller cancellation but swallows timeout-linked cancellation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellEnvironmentProvider.cs#L29-L44))
- There is no separate LLM streaming protocol, and only the tool invocation result text follows the ordinary tool result path.

### 5.8 Errors and validation
- The Python LocalShellTool refuses `approval_mode="never_require"` without `acknowledge_unsafe=True`.  
  ([constructor check](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_tool.py#L157-L167))
- The .NET `LocalShellExecutor.AsAIFunction(requireApproval:false)` likewise refuses without `AcknowledgeUnsafe`.  
  ([source](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/LocalShellExecutor.cs#L336-L343), [test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Tools.Shell.UnitTests/LocalShellExecutorTests.cs#L245-L255))
- The .NET persistent `cmd.exe` is unsupported because it has no sentinel-friendly REPL.  
  ([source](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/LocalShellExecutor.cs#L111-L115), [test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Tools.Shell.UnitTests/LocalShellExecutorTests.cs#L263-L269))
- The Python/.NET ShellEnvironmentProvider does not execute an invalid probe tool name and treats it as null.  
  ([Python tool-name validation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_environment.py#L222-L240), [.NET tool-name validation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellEnvironmentProvider.cs#L188-L212), [.NET invalid-name test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Tools.Shell.UnitTests/ShellEnvironmentProviderTests.cs#L164-L181))
- Python DockerShellTool blocks `extra_run_args` flags that would dismantle isolation defaults.  
  ([blocked flags](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_docker.py#L75-L127))

### 5.9 Security
- The Python `LocalShellTool` is **not a sandbox**, and approval is the only built-in security boundary.  
  ([README safety](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/README.md#L43-L67), [constructor warning](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_tool.py#L127-L137))
- The .NET `LocalShellExecutor` likewise treats approval-in-the-loop as the security boundary.  
  ([remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/LocalShellExecutor.cs#L43-L49), [AsAIFunction remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/LocalShellExecutor.cs#L318-L325))
- The Python `DockerShellTool` takes the container as the intended boundary, but it depends on the host/runtime/image/flags.  
  ([module docstring](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_docker.py#L3-L20), [arg docs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_docker.py#L317-L321))
- The .NET `DockerShellExecutor` says it is only a restrictive baseline and not the sole defense.  
  ([remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/DockerShellExecutor.cs#L22-L36))
- The name-based auto-approval collision risk exists for shell tools in the same way.  
  ([.NET LocalShellExecutor warning](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/LocalShellExecutor.cs#L329-L334), [.NET DockerShellExecutor warning](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/DockerShellExecutor.cs#L259-L264))

### 5.10 .NET implementation and tests
- `ShellEnvironmentProviderTests` verifies PowerShell/Posix detection, a custom formatter, missing tool nulling, duplicate probe dedup, the stderr fallback, and invalid tool-name suppression.  
  ([tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Tools.Shell.UnitTests/ShellEnvironmentProviderTests.cs#L20-L220))
- `LocalShellExecutorTests` verifies the empty default policy, the guardrail nature of the denylist, the timeout, the requireApproval opt-out ack, and persistent cmd.exe rejection.  
  ([tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Tools.Shell.UnitTests/LocalShellExecutorTests.cs#L147-L210), [unsafe ack tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Tools.Shell.UnitTests/LocalShellExecutorTests.cs#L245-L269))

### 5.11 Python implementation and tests
- `test_local_shell_tool.py` verifies stateless/persistent mode, timeouts, the policy, confine_workdir, and the approval mode.  
  ([tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/tests/test_local_shell_tool.py#L35-L321))
- `test_security.py` keeps it as a documented test that `ShellPolicy` is only a guardrail and not a security boundary.  
  ([tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/tests/test_security.py#L57-L164))

### 5.12 Documentation differences
The most important doc/code difference is the wording of the Python README.

- The README explains it as “once per session”, but the implementation holds a cached snapshot task on the provider instance and is therefore closer to a **provider lifetime cache**.  
  ([README wording](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/README.md#L91-L95), [Python implementation cache](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_environment.py#L131-L174))
- The .NET docs/comments also say “once per session”, but the actual implementation is a provider-level cached task plus refresh model.  
  ([.NET summary wording](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellEnvironmentProvider.cs#L15-L18), [.NET implementation cache](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellEnvironmentProvider.cs#L61-L127))

### 5.13 Java decisions
- **Excluded from the MVP body**, split into a separate tools module
- Local shell needs default approval-required plus an explicit unsafe ack
- The Docker/container tier is a separate module
- The shell environment provider is lightweight and can be included in the shell module together  
  ([Python local shell unsafe ack](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_tool.py#L157-L167), [.NET LocalShellExecutor opt-out check](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/LocalShellExecutor.cs#L336-L343))

### 5.14 Acceptance scenarios
1. Disabling approval on a local shell must fail without an explicit unsafe ack.  
   ([Python](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_tool.py#L157-L167), [.NET](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Tools.Shell.UnitTests/LocalShellExecutorTests.cs#L245-L255))
2. An invalid probe tool name must be recorded as null without shell injection.  
   ([.NET test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Tools.Shell.UnitTests/ShellEnvironmentProviderTests.cs#L164-L181), [Python implementation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_environment.py#L222-L240))
3. A persistent local shell must not be a target of cross-user sharing.  
   ([Python docs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_tool.py#L82-L92), [.NET docs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellExecutor.cs#L34-L47))
4. Docker extra args must reject isolation-breaking flags at construction time.  
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

### 6.1 Purpose and boundary
LocalCodeAct is not an isolated runtime like Hyperlight/Monty; it is a CodeAct surface that provides `execute_code` in a **local Python subprocess**. Its purpose is to keep the familiar CodeAct provider pattern in an environment where a sandboxed runtime is already guaranteed by the outer infrastructure (a container/VM/Foundry hosted agents and so on).  
([provider summary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/LocalCodeActProvider.cs#L15-L31), [README warning](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/README.md#L5-L14))

This feature is **not a security sandbox**. Unlike the model where “the runtime itself is the boundary”, as with Hyperlight/Monty, LocalCodeAct has to already be inside an externally isolated environment.  
([provider security note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/LocalCodeActProvider.cs#L21-L29), [ProcessExecutionLimits remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/ProcessExecutionLimits.cs#L8-L12))

### 6.2 Maturity
- The package README states explicitly that it is a **preview package**.  
  ([README status](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/README.md#L16-L23))
- No LocalCodeAct counterpart is visible in the inspected Python-side package inventory, and the optional code-execution packages appear as Hyperlight and Monty.  
  ([Python package status only lists hyperlight/monty](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L42-L46), [Python hyperlight namespace](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/hyperlight/__init__.py#L11-L18), [Python monty namespace](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/monty/__init__.py#L11-L17))

### 6.3 Public APIs and types
- `LocalCodeActProvider`
- `LocalCodeActProviderOptions`
- `LocalExecuteCodeFunction`
- `ProcessExecutionLimits`
- `FileMount`
- `FileMountMode`
- `CodeValidationException`  
  ([provider](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/LocalCodeActProvider.cs#L15-L31), [options](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/LocalCodeActProviderOptions.cs#L8-L90), [standalone function](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/LocalExecuteCodeFunction.cs#L16-L24), [limits](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/ProcessExecutionLimits.cs#L5-L35))

### 6.4 Detailed execution flow
#### Provider path
1. The provider keeps the tool registry and the file mounts in a concurrent dictionary.
2. In `ProvideAIContextAsync(...)` it captures the current tools/mounts as a snapshot.
3. It builds one snapshot-based `ExecuteCodeFunction` and returns the instructions plus the tools.  
([provider state and tool/mount registries](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/LocalCodeActProvider.cs#L31-L42), [ProvideAIContextAsync](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/LocalCodeActProvider.cs#L181-L202))

#### Standalone function path
1. In the constructor it fixes the validator / executor / tools / file mounts as a fixed snapshot.
2. At invocation it proceeds in the order optional validation → writable mounts snapshot → subprocess run → written files capture → final `AIContent` list assembly.  
([LocalExecuteCodeFunction ctor](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/LocalExecuteCodeFunction.cs#L32-L76), [CodeExecutor flow](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/Internal/CodeExecutor.cs#L10-L75))

### 6.5 State and persistence
- The provider does not keep per-run mutable interpreter state.
- `CodeExecutor.RunSnapshot` fixes the tool/mount view at the start of an invocation.
- Captured files cover only **files newly created under a read-write mount**, and modifications of existing files are not a capture target.  
  ([RunSnapshot](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/Internal/CodeExecutor.cs#L39-L51), [capture after run](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/Internal/CodeExecutor.cs#L60-L75), [README capture semantics](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/README.md#L50-L54))

### 6.6 Extension points
- custom `ExecutionLimits`
- provider-owned tools
- file mounts
- explicit environment dictionary
- working directory
- custom runner/validator script path
- validation allow/block lists for imports/builtins
- validation disable  
  ([options surface](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/LocalCodeActProviderOptions.cs#L13-L90), [README customization sections](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/README.md#L97-L170))

### 6.7 Concurrency, streaming, and cancellation
- `LocalCodeActProvider` creates an immutable snapshot at run start to separate it from later mutation.  
  ([snapshot creation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/LocalCodeActProvider.cs#L186-L193))
- The `LocalExecuteCodeFunction` construction-time snapshot is immutable as well.  
  ([remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/LocalExecuteCodeFunction.cs#L19-L23))
- Code validation, subprocess I/O, and execution all take a cancellation token.  
  ([validator creation with timeout](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/LocalExecuteCodeFunction.cs#L43-L55), [executor ExecuteAsync signature](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/Internal/CodeExecutor.cs#L53-L75))
- There is no separate streaming delta protocol. Output comes back as a final `AIContent` list.  
  ([BuildContents](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/Internal/CodeExecutor.cs#L77-L105))

### 6.8 Errors and validation
- The Python executable path is required in both the provider and the function constructor.  
  ([source checks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/LocalCodeActProvider.cs#L47-L50), [function checks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/LocalExecuteCodeFunction.cs#L35-L38), [tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.LocalCodeAct.UnitTests/LocalCodeActProviderOptionsTests.cs#L9-L23))
- Validation is on by default and uses a validator subprocess unless it is disabled.  
  ([provider ctor](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/LocalCodeActProvider.cs#L52-L67), [options default false test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.LocalCodeAct.UnitTests/LocalCodeActProviderOptionsTests.cs#L25-L30))
- The process limits cap the stdout/stderr/result/captured files sizes.  
  ([ProcessExecutionLimits](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/ProcessExecutionLimits.cs#L13-L35))

### 6.9 Security
- The most important contract is that LocalCodeAct is **not a sandbox**. The AST validator and the resource limits are only defense in depth.  
  ([provider security note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/LocalCodeActProvider.cs#L21-L29), [README warning](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/README.md#L5-L14), [README what it controls vs does not protect](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/README.md#L40-L69))
- The subprocess has an option contract so that it does not inherit the host env by default, and requires an explicit env dict.  
  ([options env semantics](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/LocalCodeActProviderOptions.cs#L26-L38), [README env section](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/README.md#L156-L170))
- The host tools exposed via `await call_tool(...)` are confined to the provider-owned registry.  
  ([README host tools](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/README.md#L71-L95))

### 6.10 .NET implementation and tests
- The provider tests verify the execute_code tool injection, tool/mount registry mutation, and the clear semantics.  
  ([tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.LocalCodeAct.UnitTests/LocalCodeActProviderTests.cs#L12-L114))
- The options tests pin the python executable requirement and the validation default.  
  ([tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.LocalCodeAct.UnitTests/LocalCodeActProviderOptionsTests.cs#L9-L30))

### 6.11 Python implementation and tests
- No LocalCodeAct counterpart was found in the inspected Python package inventory. The Python optional code-execution package inventory appears as Hyperlight and Monty.  
  ([Python package status](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L42-L46), [Python hyperlight namespace](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/hyperlight/__init__.py#L11-L18), [Python monty namespace](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/monty/__init__.py#L11-L17))

### 6.12 Documentation differences
The core alignment point between the documentation and the code is “not a sandbox”. No large contradiction was visible. When the README says “isolated environment”, however, it has to be read together with the code as meaning **subprocess/env inheritance control** rather than sandbox isolation.  
([README controls](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/README.md#L40-L54), [options Environment semantics](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/LocalCodeActProviderOptions.cs#L26-L38))

### 6.13 Java decisions
- **Excluded from the MVP**
- Reasons:
  - it does not provide a real sandbox boundary
  - building a similar feature on the Java side is easily mistaken for a sandbox
  - the operational risk is higher than a Hyperlight-class backend

If it is considered as a follow-up optional module, the strong prerequisite that “an external sandbox/VM/container already exists” has to be stamped firmly into the types/documentation.  
([README warning](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/README.md#L5-L14), [ProcessExecutionLimits remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/ProcessExecutionLimits.cs#L8-L12))

### 6.14 Acceptance scenarios
1. The provider/function constructor must fail without a Python executable path.  
   ([tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.LocalCodeAct.UnitTests/LocalCodeActProviderOptionsTests.cs#L9-L23))
2. `ValidationDisabled=false` must be the default.  
   ([test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.LocalCodeAct.UnitTests/LocalCodeActProviderOptionsTests.cs#L25-L30))
3. After a provider mutation the next run snapshot must reflect the change, but an already created standalone function snapshot must be immutable.  
   ([provider run snapshot creation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/LocalCodeActProvider.cs#L186-L193), [standalone function immutable capture](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/LocalExecuteCodeFunction.cs#L19-L23))
4. Only new files under read-write mounts must be captured.  
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

### 7.1 Purpose and boundary
Hyperlight is a **backend-specific CodeAct provider** that offers `execute_code` on top of a VM-isolated sandbox. It has a provider-owned tool registry separate from the direct agent tool surface, and guest code reaches the provider-owned tools only through `call_tool(...)`.  
([.NET Hyperlight README overview](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hyperlight/README.md#L3-L17), [Python README provider-owned tools](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hyperlight/README.md#L18-L24), [.NET provider remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hyperlight/HyperlightCodeActProvider.cs#L19-L30))

This document treats Hyperlight as a sandbox backend and separates it from the harness assembly itself.

### 7.2 Maturity
- **Python**: it is a beta package.  
  ([package status](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L42-L42), [pyproject classifier](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hyperlight/pyproject.toml#L13-L29))
- **.NET**: the README states explicitly that it is preview.  
  ([README status](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hyperlight/README.md#L38-L41))

### 7.3 Public APIs and types
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

### 7.4 Detailed execution flow
#### Python
- The provider builds a run-scoped execute_code tool with `create_run_tool()` on every run and puts the instructions plus the tool into the session context.  
  ([provider before_run](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hyperlight/agent_framework_hyperlight/_provider.py#L101-L114))
- The execute_code tool snapshots the backend/module/module_path/tools/mounts/allowed_domains and recomputes the approval mode.  
  ([ctor and state fields](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hyperlight/agent_framework_hyperlight/_execute_code_tool.py#L1090-L1126))

#### .NET
- The provider returns the run-scoped `execute_code` tool and the instructions from `ProvideAIContextAsync(...)`.
- It computes the effective approval from `CodeActApprovalMode` and the provider-owned tool registry snapshot.  
  ([provider remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hyperlight/HyperlightCodeActProvider.cs#L19-L44), [approval computation tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hyperlight.UnitTests/ApprovalComputationTests.cs#L9-L61), [ProvideAIContext tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hyperlight.UnitTests/ProvideAIContextTests.cs#L17-L50))

### 7.5 State and persistence
- Python Hyperlight runtime caches sandbox workers/snapshots by config key, but mutable unsendable sandbox objects never leak out of owner thread.  
  ([sandbox worker actor model](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hyperlight/agent_framework_hyperlight/_execute_code_tool.py#L101-L126), [worker execute/dispose](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hyperlight/agent_framework_hyperlight/_execute_code_tool.py#L179-L280))
- .NET Hyperlight README states snapshot/restore per run and fixed state key to prevent duplicate provider registration.  
  ([README snapshot/restore](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hyperlight/README.md#L18-L27), [fixed state key remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hyperlight/HyperlightCodeActProvider.cs#L31-L36))

### 7.6 Extension points
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

### 7.7 Concurrency, streaming, and cancellation
- Python Hyperlight explicitly isolates PyO3 unsendable objects to a dedicated worker thread to avoid cross-thread drop panic.  
  ([thread-confined worker rationale](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hyperlight/agent_framework_hyperlight/_execute_code_tool.py#L101-L126))
- .NET design docs require concurrent `execute_code` runs to use independent sandbox instances or synchronized snapshot/restore access.  
  ([design doc concurrency note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/features/code_act/dotnet-implementation.md#L425-L425))
- ordinary agent tool streaming contract applies; backend-specific partial output recovery is explicitly not portable.  
  ([Python design doc failure semantics](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/features/code_act/python-implementation.md#L329-L329), [.NET design doc failure semantics](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/features/code_act/dotnet-implementation.md#L404-L404))

### 7.8 Errors and validation
- Python approval is conservative: any provider-owned tool with `always_require` escalates `execute_code`.  
  ([approval resolution](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hyperlight/agent_framework_hyperlight/_execute_code_tool.py#L335-L346))
- Python mount paths stay under `/input`, and invalid network permission host-target mismatch can trigger retry handling.  
  ([mount path normalize](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hyperlight/agent_framework_hyperlight/_execute_code_tool.py#L540-L553), [network permission retry heuristic](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hyperlight/agent_framework_hyperlight/_execute_code_tool.py#L532-L537))
- .NET tests pin `AlwaysRequire` vs `NeverRequire` with/without approval-wrapped tools.  
  ([tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hyperlight.UnitTests/ApprovalComputationTests.cs#L9-L61))

### 7.9 Security
- Python hardens input staging and output capture against symlinks/reparse points and uses `O_NOFOLLOW`-style file open defense.  
  ([input walker hardening](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hyperlight/agent_framework_hyperlight/_execute_code_tool.py#L560-L590), [output read hardening](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hyperlight/agent_framework_hyperlight/_execute_code_tool.py#L689-L709))
- .NET Hyperlight is intended sandbox boundary and supports opt-in mounts + outbound allow-list + bundled approval model.  
  ([README supported capabilities](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hyperlight/README.md#L18-L27), [.NET feature design approval model](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/features/code_act/dotnet-implementation.md#L94-L111))

### 7.10 .NET implementation and tests
- `ProvideAIContextTests` verify single execute_code, approval wrapping, snapshot immutability of returned description.  
  ([tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hyperlight.UnitTests/ProvideAIContextTests.cs#L17-L103))
- `ApprovalComputationTests` verify bundled approval policy.  
  ([tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hyperlight.UnitTests/ApprovalComputationTests.cs#L9-L61))

### 7.11 Python implementation and tests
- README documents provider-owned, standalone, manual wiring modes.  
  ([README](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hyperlight/README.md#L18-L97))
- tests cover symlink/reparse hardening, allowed_domains normalization/retry, provider state and approval mode propagation.  
  ([selected tests in file](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hyperlight/tests/hyperlight/test_hyperlight_codeact.py#L632-L703), [allowed_domains tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hyperlight/tests/hyperlight/test_hyperlight_codeact.py#L1041-L1050), [strict network retry tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hyperlight/tests/hyperlight/test_hyperlight_codeact.py#L1200-L1244), [provider state tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hyperlight/tests/hyperlight/test_hyperlight_codeact.py#L1299-L1335))

### 7.12 Documentation differences
- Python default backend is `wasm`.  
  ([Python execute code tool default backend](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hyperlight/agent_framework_hyperlight/_execute_code_tool.py#L29-L30), [ctor default backend args](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hyperlight/agent_framework_hyperlight/_execute_code_tool.py#L1095-L1098))
- .NET default backend options constructor is JavaScript, and Wasm is explicit factory method.  
  ([.NET options default ctor](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hyperlight/HyperlightCodeActProviderOptions.cs#L21-L29), [CreateForWasm](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hyperlight/HyperlightCodeActProviderOptions.cs#L36-L47))

That is, the backend defaults differ even though the name “Hyperlight” is the same.

### 7.13 Java decisions
- **Excluded from the core MVP**, an optional module
- A concrete backend-first strategy is recommended
- The bundled approval model is kept
- Symlink-safe staging/capture is mandatory from the first implementation  
  ([cross-SDK CodeAct decision](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0024-codeact-integration.md#L159-L170), [Python approval resolution](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hyperlight/agent_framework_hyperlight/_execute_code_tool.py#L335-L346), [Python symlink hardening](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hyperlight/agent_framework_hyperlight/_execute_code_tool.py#L560-L590))

### 7.14 Acceptance scenarios
1. When even one provider-owned tool is approval-required, `execute_code` must become approval-required.  
   ([Python](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hyperlight/agent_framework_hyperlight/_execute_code_tool.py#L335-L346), [.NET tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hyperlight.UnitTests/ApprovalComputationTests.cs#L50-L61))
2. After a provider mutation the description of an already returned run-scoped execute_code must not change.  
   ([.NET test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hyperlight.UnitTests/ProvideAIContextTests.cs#L70-L85))
3. Input/output escape through a symlink/reparse point must be blocked.  
   ([Python hardening source](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hyperlight/agent_framework_hyperlight/_execute_code_tool.py#L560-L590), [output hardening source](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hyperlight/agent_framework_hyperlight/_execute_code_tool.py#L689-L709))
4. When the Wasm backend is used, a guest module path must be required (.NET).  
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

### 8.1 Purpose and boundary
Monty is a Python package that uses a Rust-based Python interpreter as a CodeAct backend. It follows the same provider/tool model as Hyperlight, but aims at cross-platform execution on an interpreter basis rather than on a hypervisor/WASM backend.  
([README summary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/monty/README.md#L1-L18), [provider summary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/monty/agent_framework_monty/_provider.py#L20-L27))

This document treats Monty as a backend distinct from Hyperlight and sees it as a different execution family from shell or LocalCodeAct.

### 8.2 Maturity
- It is a beta package.  
  ([package status](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L46-L46), [pyproject classifier](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/monty/pyproject.toml#L13-L24))

### 8.3 Public APIs and types
- `MontyCodeActProvider`
- `MontyExecuteCodeTool`
- `FileMount`
- `FileMountInput`
- `MountMode`  
  ([lazy namespace](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/monty/__init__.py#L11-L17), [provider ctor](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/monty/agent_framework_monty/_provider.py#L31-L48), [tool ctor](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/monty/agent_framework_monty/_execute_code_tool.py#L195-L225))

### 8.4 Detailed execution flow
- The provider builds a `create_run_tool()` snapshot on every run and injects the instructions plus `execute_code`.  
  ([before_run](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/monty/agent_framework_monty/_provider.py#L85-L98))
- `MontyExecuteCodeTool` exposes the managed tools inside the interpreter as typed async functions plus a fallback `call_tool(...)`.
- For read-write mounts it captures the changed files with a post-run scan after a pre-state snapshot.  
  ([tool overview](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/monty/agent_framework_monty/_execute_code_tool.py#L167-L193), [run path](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/monty/agent_framework_monty/_execute_code_tool.py#L391-L422))

### 8.5 State and persistence
- When `workspace_root` is present an `/input` read-write mount is added automatically.
- When there is an explicit `/input` mount, that one takes precedence.
- An `overlay` mount write is in-memory only, does not remain on the host, and is not a capture target either.
- A `read-write` mount is reflected on the host and is a capture target.  
  ([auto mount logic](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/monty/agent_framework_monty/_execute_code_tool.py#L383-L389), [README mount semantics](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/monty/README.md#L135-L149), [workspace_root tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/monty/tests/monty/test_monty_codeact.py#L307-L324))

### 8.6 Extension points
- tools
- approval_mode
- workspace_root
- file_mounts
- resource_limits  
  ([provider ctor](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/monty/agent_framework_monty/_provider.py#L31-L48), [tool ctor](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/monty/agent_framework_monty/_execute_code_tool.py#L195-L225), [README resource limits](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/monty/README.md#L108-L149))

### 8.7 Concurrency, streaming, and cancellation
- The object mutators assume same task/thread ownership and have no internal locking.  
  ([doc comment](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/monty/agent_framework_monty/_execute_code_tool.py#L190-L193))
- The result is returned as a final `list[Content]` and there is no separate stream protocol.  
  ([run code return type](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/monty/agent_framework_monty/_execute_code_tool.py#L391-L422))

### 8.8 Errors and validation
- Approval resolution escalates the whole `execute_code` when even one managed tool is `always_require`, exactly as in Hyperlight.  
  ([approval resolution](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/monty/agent_framework_monty/_execute_code_tool.py#L72-L81))
- Mount path normalization enforces an absolute POSIX path plus no `..`.  
  ([mount path normalize](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/monty/agent_framework_monty/_execute_code_tool.py#L84-L103))
- An execution exception is surfaced through `Content.from_error(...)`.  
  ([exception handling](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/monty/agent_framework_monty/_execute_code_tool.py#L410-L418))

### 8.9 Security
- By default OS/filesystem/network access is refused with a `PermissionError`.
- A scoped filesystem capability is opened through a mount.
- Post-capture skips symlinks to prevent host file leakage.
- A typed host tool call goes through type checking.  
  ([README default PermissionError and limited stdlib](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/monty/README.md#L168-L177), [symlink-safe file iteration](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/monty/agent_framework_monty/_execute_code_tool.py#L453-L558), [README type-checking note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/monty/README.md#L174-L175))

### 8.10 .NET implementation and tests
- There is no .NET implementation of Monty CodeAct in the inspected tree.
- The .NET feature docs only say that a “future backend such as Monty” could follow the same conceptual model.  
  ([.NET feature doc note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/features/code_act/dotnet-implementation.md#L6-L8))

### 8.11 Python implementation and tests
- `test_monty_codeact.py` verifies the approval mode, mounts, workspace_root, and the capture behavior.  
  ([tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/monty/tests/monty/test_monty_codeact.py#L182-L345), [capture tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/monty/tests/monty/test_monty_codeact.py#L602-L634))
- The integration tests verify workspace reads/writes, read-only/overlay mount rejection, and symlink-safe runtime/capture.  
  ([integration tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/monty/tests/monty/test_monty_codeact_integration.py#L254-L345), [symlink tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/monty/tests/monty/test_monty_codeact_integration.py#L381-L480))

### 8.12 Documentation differences
No large documentation-code discrepancy was visible. The README's “subset of Python”, “OS/filesystem/network denied by default”, and “overlay does not persist/capture” agree with the code and the tests.  
([README](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/monty/README.md#L151-L179), [runtime/capture code](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/monty/agent_framework_monty/_execute_code_tool.py#L391-L422), [capture semantics code](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/monty/agent_framework_monty/_execute_code_tool.py#L507-L558))

### 8.13 Java decisions
- **Excluded from the MVP**
- A candidate optional backend module later
- Reasons:
  - the absence of .NET parity
  - the interpreter capability surface is large
  - designing the host mount/resource limit semantics wrongly creates a security expectation mismatch  
  ([Python package status beta](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L46-L46), [.NET no implementation note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/features/code_act/dotnet-implementation.md#L6-L8))

### 8.14 Acceptance scenarios
1. When `workspace_root` is present an `/input` read-write mount must be created automatically.  
   ([test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/monty/tests/monty/test_monty_codeact.py#L307-L324))
2. An `overlay` mount write must not remain on the host and must not be captured.  
   ([README](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/monty/README.md#L140-L145), [integration test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/monty/tests/monty/test_monty_codeact_integration.py#L320-L334))
3. A read-only mount write must be refused as a `PermissionError`-class error and must not be captured.  
   ([integration test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/monty/tests/monty/test_monty_codeact_integration.py#L292-L315))
4. Host file leakage through a symlink must be blocked in both the runtime and the capture.  
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

## 9. Final Java decision summary

### Recommended for inclusion
- **Skills**
  - read-only progressive disclosure(`load_skill`, `read_skill_resource`)
  - file-based source with traversal/symlink defense
- **Shell environment provider**
  - but as a separate tools module split from shell execution itself
- **tool-backed provider pattern**
  - The “provider-owned tool registry plus run-scoped execute_code” structure that Hyperlight/Monty/LocalCodeAct showed is useful as a common pattern for the design of follow-up Java modules

([Python SkillsProvider tool model](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_skills.py#L2450-L2540), [.NET AgentSkillsProvider BuildTools](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Skills/AgentSkillsProvider.cs#L314-L340), [LocalCodeAct run-scoped snapshot pattern](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/LocalCodeActProvider.cs#L186-L193), [Python Hyperlight run tool pattern](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hyperlight/agent_framework_hyperlight/_provider.py#L109-L114))

### Recommended for exclusion from the MVP
- Background agents/tasks
- Script-executing skills
- Local shell execution
- LocalCodeAct
- Hyperlight/Monty backend modules

### Reasons
- background tasks have large runtime handle persistence and trust boundary concerns
- shell/local code execution risks being mistaken for a sandbox by operators
- Hyperlight/Monty have large backend portability and platform packaging issues
- script-executing skills are far more dangerous than resource-reading skills

([background trust boundary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_harness/_background_agents.py#L258-L266), [LocalShellTool not sandbox](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/README.md#L43-L67), [LocalCodeAct not sandbox](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.LocalCodeAct/README.md#L5-L14), [Hyperlight requirements preview](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hyperlight/README.md#L28-L41), [Monty beta/platform spread](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/monty/pyproject.toml#L30-L36))

### Recommended order of follow-up stages
1. read-only skills
2. shell environment provider
3. containerized shell
4. code execution backend SPI
5. Hyperlight-like backend
6. a LocalCodeAct-like backend comes last

---