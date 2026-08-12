# 11. Compaction

## 1. Document purpose and scope

This document organizes only the **compaction subsystem** of the Microsoft Agent Framework. The scope is as follows.

- Python's `CompactionProvider` and its strategy set
- .NET's `CompactionProvider`, the `CompactionStrategy` hierarchy, and `CompactionMessageIndex`
- grouping / annotation / indexing
- token estimation / trigger / target / threshold
- The sliding / truncation / summarization / tool-result / selective policy families
- session / context / chat history integration
- Errors, recovery, cancellation, and the security boundary

This document does not restate **the whole harness assembly rules**. Where the harness wires compaction is mentioned only as a boundary, and the assembly details are split out into the harness document.  
([Python top-level compaction exports](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/__init__.py#L58-L86), [Python public re-export subset](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/__init__.py#L598-L603), [.NET harness compaction wiring boundary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs#L166-L197))

---

## 2. State and snapshot model

Compaction is structurally different in the two SDKs.

- **Python** writes the group/token/excluded/summary linkage into `Message.additional_properties` as a **per-message annotation**. The compaction state is therefore attached to the message object.  
  ([annotation keys](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L26-L37), [annotation write path](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L607-L618), [exclusion write](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L718-L733))
- In **.NET** a separate `CompactionMessageGroup` / `CompactionMessageIndex` is the canonical state. Whether something is excluded and why is stored in the group object, and only whether it is a summary round-trips through the message's `AdditionalProperties[SummaryPropertyKey]`.  
  ([CompactionMessageGroup model](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionMessageGroup.cs#L12-L29), [group exclusion fields](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionMessageGroup.cs#L103-L115), [summary property key](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionMessageGroup.cs#L33-L40))

This difference leads to every subsequent design difference.

- Python is closer to **mutating the message list itself in place**.
- .NET is closer to **building a group index, compacting on top of it**, and then projecting.  
  ([Python strategy protocol mutates messages in place](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L61-L73), [.NET strategy base mutates index in place](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionStrategy.cs#L20-L27), [.NET provider returns projection of included messages](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionProvider.cs#L197-L202))

---

## 3. Purpose and boundary

The purpose of compaction is not simply to discard old messages but to reduce the context size while keeping **structural units the LLM API does not break on**. Both SDKs make the atomicity of tool-call/result pairs a core invariant.  
([Python grouping comment](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L253-L276), [.NET group kind remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionGroupKind.cs#L12-L15), [.NET ToolCall atomicity](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionGroupKind.cs#L34-L41))

The boundary of this document is as follows.

- **No duplication with the harness document**: the detailed rationale for where the harness plugs compaction in — before, after, or in loop — is not restated. The lifecycle points a provider supports are explained, however.  
  ([Python provider before/after hooks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L1494-L1618), [.NET strategy lifecycle remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionStrategy.cs#L37-L47))
- **Skills / background / code execution** are split into a separate document. Only the fact that tool-call content is a grouping target is covered here.  
  ([Python tool-call content types](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L42-L48), [.NET tool call grouping in index creation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionMessageIndex.cs#L197-L211))

---

## 4. Maturity

### Python
The Python compaction surface is exported directly from the top-level `agent_framework`, and in the inspected source no separate feature-stage decorator is attached above the classes/functions. At the code level it therefore looks like a **stable public utility layer**.  
([public compaction export list](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/__init__.py#L58-L86), [public re-exported functions](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/__init__.py#L598-L603), [provider definition without feature-stage decorator](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L1494-L1498))

### .NET
Conversely, the core types of the .NET compaction subsystem are all `[Experimental]`.

- `CompactionStrategy`
- `CompactionProvider`
- `CompactionMessageIndex`
- `CompactionMessageGroup`
- `CompactionGroupKind`
- each concrete strategy
- `CompactionTriggers`
- `ChatStrategyExtensions`

([CompactionStrategy experimental](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionStrategy.cs#L49-L50), [CompactionProvider experimental](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionProvider.cs#L46-L47), [CompactionMessageIndex experimental](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionMessageIndex.cs#L25-L26), [CompactionMessageGroup experimental](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionMessageGroup.cs#L30-L31), [CompactionGroupKind experimental](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionGroupKind.cs#L16-L17), [CompactionTriggers experimental](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionTriggers.cs#L22-L23), [ChatStrategyExtensions experimental](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/ChatStrategyExtensions.cs#L16-L17))

**Summary**: as of the current commit, compaction is a **more publicly stabilized surface on the Python side** and a **more explicitly experimental surface on the .NET side**.

---

## 5. Public APIs and types

## 5.1 Python

### Core types
- `TokenizerProtocol`
- `CharacterEstimatorTokenizer`
- `CompactionStrategy` (protocol)
- `CompactionProvider`
- `TruncationStrategy`
- `SlidingWindowStrategy`
- `SelectiveToolCallCompactionStrategy`
- `ToolResultCompactionStrategy`
- `SummarizationStrategy`
- `TokenBudgetComposedStrategy`
- `ContextWindowCompactionStrategy`

([exports list](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/__init__.py#L58-L86), [strategy/type definitions](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L52-L80), [strategy classes](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L797-L1732))

### Core functions
- `annotate_message_groups(...)`
- `annotate_token_counts(...)`
- `extend_compaction_messages(...)`
- `append_compaction_message(...)`
- `included_messages(...)`
- `included_token_count(...)`
- `apply_compaction(...)`

([functions](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L543-L715), [apply_compaction](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L1475-L1488))

## 5.2 .NET

### Core types
- `CompactionStrategy` (abstract base)
- `CompactionTrigger` (delegate)
- `CompactionTriggers`
- `CompactionProvider`
- `CompactionMessageIndex`
- `CompactionMessageGroup`
- `CompactionGroupKind`
- `SlidingWindowCompactionStrategy`
- `TruncationCompactionStrategy`
- `ToolResultCompactionStrategy`
- `SummarizationCompactionStrategy`
- `ContextWindowCompactionStrategy`
- `PipelineCompactionStrategy`
- `ChatReducerCompactionStrategy`
- `ChatStrategyExtensions.AsChatReducer()`

([strategy base](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionStrategy.cs#L15-L18), [trigger delegate](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionTrigger.cs#L15-L15), [trigger factory](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionTriggers.cs#L9-L23), [provider](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionProvider.cs#L19-L47), [index](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionMessageIndex.cs#L16-L26), [group](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionMessageGroup.cs#L11-L31), [sliding](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/SlidingWindowCompactionStrategy.cs#L13-L35), [truncation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/TruncationCompactionStrategy.cs#L11-L31), [tool-result](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/ToolResultCompactionStrategy.cs#L14-L49), [summarization](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/SummarizationCompactionStrategy.cs#L16-L48), [context-window](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/ContextWindowCompactionStrategy.cs#L13-L35), [pipeline](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/PipelineCompactionStrategy.cs#L13-L28), [chat-reducer strategy](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/ChatReducerCompactionStrategy.cs#L14-L46), [as chat reducer](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/ChatStrategyExtensions.cs#L13-L32))

### Important difference
- Python has **no explicit `CompactionTrigger` abstraction**. Each strategy carries the threshold/condition itself.
- In .NET every concrete strategy takes a **`trigger` plus an optional `target`**, separating “when to start” from “when to stop”.  
  ([Python truncation constructor style](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L809-L837), [.NET base trigger/target model](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionStrategy.cs#L25-L35), [.NET truncation ctor](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/TruncationCompactionStrategy.cs#L39-L57))

---

## 6. Grouping / annotation / indexing

## 6.1 Python: the message annotation model

Python decorates the messages with group annotations before compaction. The core keys are as follows.

- `_group.id`
- `_group.kind`
- `_group.index`
- `_group.has_reasoning`
- `_group.token_count`
- `_excluded`
- `_exclude_reason`
- `_summary_of_message_ids`
- `_summary_of_group_ids`
- `_summarized_by_summary_id`

([annotation keys](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L26-L37))

The group rules are as follows.

- system message → its own group
- user message → its own group
- assistant tool call plus the following tool results → `tool_call` group
- a reasoning-only assistant prefix in front of a tool call is absorbed into the same `tool_call` group
- a non-adjacent function result is linked back into the declaration group as long as the call id is **unambiguous**
- an ambiguous duplicate call id is not paired

([group_messages rules](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L200-L325), [linking non-adjacent results](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L150-L198), [atomicity tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_compaction.py#L159-L243), [ambiguous duplicate-id tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_compaction.py#L294-L308))

Python also supports incremental annotation. That is, it re-annotates only the new message tail and preserves the already computed prefix token counts and IDs.  
([incremental annotation core](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L543-L624), [extend_compaction_messages](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L676-L702), [preserve annotations/tokens test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_compaction.py#L435-L450), [unique message ids regression tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_compaction.py#L561-L615))

## 6.2 .NET: the group index model

In .NET, `CompactionMessageIndex.Create(messages)` turns a flat `ChatMessage` sequence into a set of `CompactionMessageGroup` objects. The group classification rules are as follows.

- system → `System`
- user → `User`
- assistant with tool calls + following tool results / reasoning-only assistant messages → `ToolCall`
- an assistant message carrying the summary marker → `Summary`
- ordinary assistant text → `AssistantText`

([index create remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionMessageIndex.cs#L73-L91), [append logic](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionMessageIndex.cs#L177-L264), [summary recognition](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionMessageIndex.cs#L527-L534))

Reasoning-only assistant messages can be merged into a tool-call group, but an assistant message mixing reasoning with plain text stays as `AssistantText`.  
([reasoning grouping source](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionMessageIndex.cs#L218-L257), [reasoning tool-call test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Compaction/CompactionMessageIndexTests.cs#L1388-L1410), [mixed reasoning/text test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Compaction/CompactionMessageIndexTests.cs#L1412-L1432))

.NET also supports incremental update. It finds the existing `_lastProcessedMessage` and appends only the suffix, and rebuilds everything when the message list has turned into a sliding window and the prefix has been cut.  
([incremental update algorithm](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionMessageIndex.cs#L99-L175), [incremental reasoning/tool-call append test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Compaction/CompactionMessageIndexTests.cs#L1451-L1477))

### Core difference
- Python has strong traceability based on **per-message annotation**.
- .NET is centred on the **group object**, so projection/metrics are simpler, but it does not plant original↔summary bidirectional metadata into the messages as broadly as Python does.  
  ([Python summary linkage keys](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L35-L37), [Python bidirectional summary tracing test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_compaction.py#L848-L877), [.NET summary insertion only marks SummaryPropertyKey](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/SummarizationCompactionStrategy.cs#L214-L218))

---

## 7. Token estimation / metrics / triggers

## 7.1 Python

Python token accounting applies the tokenizer to the result of `_serialize_message(message)`. The default tokenizer is `CharacterEstimatorTokenizer`, which uses a **4 chars/token** heuristic. Non-ASCII text is serialized with `ensure_ascii=False` so that it is not inflated by `\uXXXX` escapes when counted.  
([CharacterEstimatorTokenizer](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L76-L80), [serialize_message ensure_ascii=False](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L636-L645), [non-ASCII test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_compaction.py#L1928-L1941))

The metric helpers compute `included_messages(...)`, `included_token_count(...)`, and the included group/message counts from the annotations.  
([included projection helpers](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L705-L737))

## 7.2 .NET

.NET stores the byte/token/message count in `CompactionMessageGroup`, and `CompactionMessageIndex` exposes the aggregate metrics.

- `Total*` / `Included*` metrics
- `IncludedTurnCount`
- `IncludedNonSystemGroupCount`
- `RawMessageCount`

([group metrics fields](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionMessageGroup.cs#L73-L101), [index aggregate metrics](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionMessageIndex.cs#L316-L374))

Token count computation counts the text kinds (`TextContent`, `TextReasoningContent`, `ProtectedData`) directly when a `Tokenizer` is present, and approximates the remaining content as byteCount/4.  
([ComputeTokenCount](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionMessageIndex.cs#L403-L452), [ComputeContentByteCount coverage](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionMessageIndex.cs#L455-L495))

## 7.3 The .NET trigger / target model

.NET has a primary predicate called `CompactionTrigger`, which `CompactionStrategy` evaluates first. The optional `target` decides “when to stop” during compaction. When no target is given, the default is the **inverse of the trigger**.  
([CompactionTrigger delegate](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionTrigger.cs#L15-L15), [base trigger/target docs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionStrategy.cs#L25-L35), [default target code](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionStrategy.cs#L64-L68), [default target test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Compaction/CompactionStrategyTests.cs#L134-L171))

The prepared trigger factory provides the following.

- `Always`
- `Never`
- `TokensBelow`
- `TokensExceed`
- `MessagesExceed`
- `TurnsExceed`
- `GroupsExceed`
- `HasToolCalls`
- `All(...)`
- `Any(...)`

([CompactionTriggers](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionTriggers.cs#L25-L134))

### Important SDK difference
Python has no explicit trigger algebra and instead **embeds the threshold semantics in the strategy object itself**, while .NET **composes trigger/target orthogonally**. This is a difference that requires a choice in the Java design.  
([Python truncation signature](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L809-L837), [.NET trigger/target signature](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/TruncationCompactionStrategy.cs#L39-L57))

---

## 8. Detailed strategy families

## 8.1 Truncation

### Python
The Python `TruncationStrategy` is an **oldest-first group exclusion**.

- a token-based threshold when a tokenizer is present
- an included message count threshold when there is no tokenizer
- system groups are kept when `preserve_system=True`
- at least 1 retained group is always left

([strategy doc](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L797-L807), [constructor validation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L809-L837), [algorithm](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L839-L867))

The tests pin system anchor preservation, token limit compaction, preservation of an oversized latest group, and non-adjacent tool pair atomicity.  
([tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_compaction.py#L619-L710))

### .NET
The .NET `TruncationCompactionStrategy` is also an oldest-first exclusion, but the threshold semantics are separated into trigger/target and `MinimumPreservedGroups` acts as a hard floor.  
([strategy docs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/TruncationCompactionStrategy.cs#L11-L29), [ctor](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/TruncationCompactionStrategy.cs#L39-L63), [algorithm](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/TruncationCompactionStrategy.cs#L66-L109))

The tests verify trigger-not-met, oldest exclusion, system preservation, tool-call group atomicity, the exclude reason, and the minimum preserved behavior.  
([tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Compaction/TruncationCompactionStrategyTests.cs#L15-L220))

---

## 8.2 Sliding window

### Python
The Python `SlidingWindowStrategy` keeps the **most recent N non-system groups**. That is, the criterion is a group count rather than turns.  
([strategy docs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L870-L881), [algorithm](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L895-L913))

The tests verify a reused call-id pair, reasoning plus MCP call atomicity, and hosted tool reasoning adjacency.  
([tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_compaction.py#L346-L417))

### .NET
The .NET `SlidingWindowCompactionStrategy` keeps the **most recent N turns**. That is, it is `TurnIndex` based, not group-count based. `TurnIndex 0` or `null` is always protected.  
([strategy docs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/SlidingWindowCompactionStrategy.cs#L13-L33), [ctor and preserved turns semantics](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/SlidingWindowCompactionStrategy.cs#L43-L70), [algorithm](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/SlidingWindowCompactionStrategy.cs#L73-L139))

The tests verify oldest turn exclusion, system preservation, tool-call group preservation, the custom target early stop, and the minimum preserved turns hard floor.  
([tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Compaction/SlidingWindowCompactionStrategyTests.cs#L15-L220))

### Important difference
- Python sliding window = **group-based**
- .NET sliding window = **turn-based**

This difference means there is no behavior parity despite the same name.  
([Python group-based keep_last_groups](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L883-L913), [.NET turn-based minimumPreservedTurns](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/SlidingWindowCompactionStrategy.cs#L49-L70))

---

## 8.3 Selective tool-call policy

### Python
Python has a separate `SelectiveToolCallCompactionStrategy`. It targets only `tool_call` groups, keeps the most recent `keep_last_tool_call_groups` of them, and **excludes the remaining tool-call groups entirely**.  
([strategy docs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L916-L925), [algorithm](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L942-L966))

The tests verify old tool group exclusion, the removal of even the assistant tool pair when `keep=0`, and negative keep rejection.  
([tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_compaction.py#L715-L761))

### .NET
In the concrete strategy set of the .NET compaction package inspected at this commit, **no separate selective-only tool-call exclusion strategy** is visible. As a tool-call specific policy, `ToolResultCompactionStrategy` is provided, and it performs a **summary replacement** instead of a removal.  
([.NET strategy set in inspected sources: ToolResult](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/ToolResultCompactionStrategy.cs#L14-L49), [Truncation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/TruncationCompactionStrategy.cs#L11-L31), [Sliding](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/SlidingWindowCompactionStrategy.cs#L13-L35), [Summarization](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/SummarizationCompactionStrategy.cs#L16-L48), [ContextWindow](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/ContextWindowCompactionStrategy.cs#L13-L35), [Pipeline](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/PipelineCompactionStrategy.cs#L13-L28), [ChatReducer bridge](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/ChatReducerCompactionStrategy.cs#L14-L46))

---

## 8.4 Tool-result compaction

### Python
The Python `ToolResultCompactionStrategy` turns an old tool-call group into a **one-line bracket summary**.

Example shape:
- `[Tool results: get_weather: sunny, 18°C]`

It keeps the most recent `keep_last_tool_call_groups` groups as they are and collapses only the older groups.  
([strategy docs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L969-L981), [summary format implementation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L1085-L1101))

Python puts `_summary_of_message_ids` and `_summary_of_group_ids` into the summary message and leaves a `_summarized_by_summary_id` backlink on the original messages. It also re-applies the full group annotations to the summary message itself.  
([forward/back links insertion](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L1056-L1083), [bidirectional tracing test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_compaction.py#L1356-L1412))

The tool result summary cuts a large payload down to a bounded string and does not put an already excluded result back into the summary.  
([summary truncation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L1085-L1101), [large summary bound test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_compaction.py#L1290-L1316), [do not restore excluded results test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_compaction.py#L1319-L1354))

### .NET
The .NET `ToolResultCompactionStrategy` turns an old tool-call group into a **YAML-like multi-line block**.

Example shape:
```text
[Tool Calls]
get_weather:
  - Sunny and 72°F
search_docs:
  - Found 3 docs
```

([strategy docs and format example](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/ToolResultCompactionStrategy.cs#L14-L31), [default formatter implementation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/ToolResultCompactionStrategy.cs#L164-L254))

The original group becomes `IsExcluded=true`, and the replacement summary is inserted into the index as a new `Summary` group carrying `SummaryPropertyKey=true`. It does not leave original↔summary backlink metadata as broadly as Python does.  
([replacement logic](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/ToolResultCompactionStrategy.cs#L140-L159), [summary property key meaning](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionMessageGroup.cs#L33-L40))

The tests verify the collapse, recent preservation, multi-tool extraction, a compound trigger, and the target early stop.  
([tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Compaction/ToolResultCompactionStrategyTests.cs#L16-L193))

### Important difference
- Python summary = **compact one-line bracket text**
- .NET summary = **YAML-like block**
- Python leaves bidirectional trace links, while .NET is centred on the replacement group.  
([Python summary style](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L983-L1101), [.NET summary style](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/ToolResultCompactionStrategy.cs#L237-L254))

---

## 8.5 Summarization

### Python
The Python `SummarizationStrategy` summarizes the oldest included non-system groups into an assistant summary message and keeps the newest content near `target_count`.  
([strategy overview](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L1197-L1217), [selection and keep logic](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L1290-L1339))

Characteristics:

- It selects **only complete groups** within the `max_summary_input_tokens` budget.
- When the first group is too large it can skip it and select from the following groups.
- A summary generation failure / an empty summary returns `False` and keeps the originals.
- After 3 consecutive failures it raises an error log once.
- On success it resets the failure escalation state.

([input budget selection](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L1138-L1173), [failure handling](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L1348-L1372), [failure counter](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L1273-L1288), [complete-group budget tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_compaction.py#L879-L946), [repeated failure escalation tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_compaction.py#L975-L1046))

### .NET
The .NET `SummarizationCompactionStrategy` picks the oldest non-system groups, then generates a summary with a single LLM call and turns them into one `Summary` group. `MinimumPreservedGroups` is a hard floor.  
([strategy docs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/SummarizationCompactionStrategy.cs#L16-L45), [ctor](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/SummarizationCompactionStrategy.cs#L69-L106), [core algorithm](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/SummarizationCompactionStrategy.cs#L124-L223))

Failure semantics:

- a non-cancellation exception → restore the excluded groups, no summary insertion, return `false`
- an `OperationCanceledException` / `TaskCanceledException` → **propagate**
- an empty or whitespace response → use the `[Summary unavailable]` fallback

([failure restore path](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/SummarizationCompactionStrategy.cs#L189-L208), [empty fallback](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/SummarizationCompactionStrategy.cs#L210-L218), [tests for restore/cancel](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Compaction/SummarizationCompactionStrategyTests.cs#L417-L612))

### Important difference
- Python has a summary input budget and repeated failure escalation.
- .NET has no repeated failure counter, but explicitly tests cancellation propagation and the restore semantics.
- The Python summary leaves bidirectional links. The .NET summary is centred on `SummaryPropertyKey` and the restore semantics of the excluded originals.  
([Python links](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L1373-L1397), [Python summary annotation test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_compaction.py#L1432-L1455), [.NET insertion](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/SummarizationCompactionStrategy.cs#L214-L223))

---

## 8.6 Pipeline / token-budget / context-window

### Python
Python performs **token budget orchestration** with `TokenBudgetComposedStrategy` and `ContextWindowCompactionStrategy`.

- `TokenBudgetComposedStrategy` applies a strategy sequence in order and performs a deterministic fallback exclusion when the budget is still not met.
- `ContextWindowCompactionStrategy` uses input budget = `max_context_window_tokens - max_output_tokens`
  - tool-result eviction at 50%
  - truncation at 80%
  - default keep-last-tool-call-groups = 4

([TokenBudgetComposedStrategy](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L1400-L1473), [ContextWindowCompactionStrategy](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L1620-L1732))

The fallback first excludes old non-system groups, and runs a stricter strict fallback when the budget is still not met.  
([fallback logic](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L1450-L1472))

The tests verify the under-threshold no-op, tool-result eviction above 50%, truncation above 80%, `keep_last_tool_call_groups` preservation, and non-ASCII serialization correctness.  
([tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_compaction.py#L1775-L1941))

### .NET
.NET uses the combination of `PipelineCompactionStrategy` plus `ContextWindowCompactionStrategy`.

- `PipelineCompactionStrategy` applies the child strategies in order.
- `ContextWindowCompactionStrategy` computes the input budget and then
  - `ToolResultCompactionStrategy(trigger=TokensExceed(toolEvictionTokens), minimumPreservedGroups: 2)`
  - `TruncationCompactionStrategy(trigger=TokensExceed(truncationTokens), minimumPreservedGroups: 2)`
  composes the pipeline from these.

([PipelineCompactionStrategy](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/PipelineCompactionStrategy.cs#L13-L62), [ContextWindowCompactionStrategy](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/ContextWindowCompactionStrategy.cs#L13-L148))

The tests verify property setting, invalid thresholds, the tool eviction trigger, and the truncation trigger.  
([tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Compaction/ContextWindowCompactionStrategyTests.cs#L16-L219))

### Difference
- Python has an **explicit token-budget fallback orchestrator** called `TokenBudgetComposedStrategy`, while .NET's `PipelineCompactionStrategy` relies on the child trigger logic.
- The Python default keep-last-tool-call-groups is 4, while the tool-result phase of the .NET context-window pipeline uses minimum preserved groups 2.  
  ([Python context-window defaults](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L1647-L1662), [.NET context-window pipeline fixed params](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/ContextWindowCompactionStrategy.cs#L98-L107))

---

## 8.7 Chat reducer bridge (.NET only)

.NET has a bidirectional bridge.

1. `ChatReducerCompactionStrategy`: wraps an existing `IChatReducer` as a compaction strategy.  
2. `AsChatReducer()`: wraps any `CompactionStrategy` as an `IChatReducer`.  

([ChatReducerCompactionStrategy docs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/ChatReducerCompactionStrategy.cs#L15-L43), [ChatReducerCompactionStrategy core](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/ChatReducerCompactionStrategy.cs#L68-L91), [AsChatReducer](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/ChatStrategyExtensions.cs#L19-L57))

This bridge lets the .NET harness reuse the same strategy for `InMemoryChatHistoryProvider.ChatReducer` as well.  
([harness using AsChatReducer](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs#L190-L197))

Python has no equivalent `IChatReducer` bridge within the inspected compaction source scope.

---

## 9. Session / context integration

## 9.1 Python provider integration

The Python `CompactionProvider` has two lifecycle points.

- `before_run`: compacts the messages already loaded into the context and filters them by projection
- `after_run`: compacts the messages the history provider stored in session state, but leaves the excluded messages in storage to preserve the annotations

([provider class and before_run](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L1494-L1587), [after_run and storage semantics](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L1588-L1618))

The next-turn load behavior then depends on the history provider's `skip_excluded` option.  
([test skip_excluded true](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_compaction.py#L1734-L1752), [test default loads all](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_compaction.py#L1754-L1770))

`apply_compaction(...)` is also a direct helper for ad-hoc projection without a provider.  
([apply_compaction](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L1475-L1488), [projection test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_compaction.py#L1139-L1149))

## 9.2 .NET provider integration

The .NET `CompactionProvider` is a **pre-invocation compaction provider**.

- passthrough when there is no session or no messages
- skip compaction for a remote-managed session (a `ChatClientAgentSession.ConversationId` is present)
- when persisted `MessageGroups` exist in session state, load the existing index and update it incrementally
- otherwise create from scratch
- store the index into `State.MessageGroups` after compaction

([provider invocation flow](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionProvider.cs#L113-L181), [remote session skip](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionProvider.cs#L129-L135), [persisted state shape](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionProvider.cs#L210-L220))

The synthetic summary messages compaction produced are also force-marked with the chat history source attribution `ChatHistory`, so that they are not stored twice after the run ends.  
([source attribution fixup](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionProvider.cs#L183-L195))

### Important difference
- The Python provider handles both **before and after**.
- The .NET provider focuses on **pre-invocation compaction plus a session-persisted index**.
- .NET skips for a remote service-managed threaded session. The inspected Python provider has no equivalent remote conversation-id skip guard.  
  ([Python provider hooks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L1494-L1618), [.NET provider flow](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionProvider.cs#L123-L135))

---

## 10. State and persistence

### Python
Python compaction does not maintain much separate provider state object and relies on the message annotations and the storage policy of the history store. `after_run` leaves the excluded originals in storage so that they can be reused on the next turn.  
([after_run semantics](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L1600-L1618))

### .NET
.NET stores a `List<CompactionMessageGroup> MessageGroups` under `ProviderSessionState<State>`. This persistent index enables `Update(...)` based incremental compaction on the next invocation.  
([provider state](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionProvider.cs#L139-L145), [persist updated groups](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionProvider.cs#L179-L181), [state class](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionProvider.cs#L210-L220))

The default state key is also the strategy type name. There is a test that the key must be stable even for equivalent provider instances across sessions.  
([default state key](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionProvider.cs#L67-L76), [state key tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Compaction/CompactionProviderTests.cs#L23-L56))

---

## 11. Extension points

### Python
- custom tokenizer
- custom before/after provider strategy
- custom summarization client/prompt/input budget
- custom direct strategy composition via `TokenBudgetComposedStrategy`
- custom call-site `apply_compaction(...)`

([tokenizer protocol](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L51-L57), [provider ctor](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L1532-L1559), [summarization ctor knobs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L1219-L1269), [token budget composition](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L1400-L1431))

### .NET
- custom `CompactionTrigger`
- custom `target`
- custom concrete `CompactionStrategy` subclass
- strategy pipelines
- external `IChatReducer` interop
- `AsChatReducer()` bridge back into `IChatReducer`

([base class extensibility](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionStrategy.cs#L25-L35), [CompactionTriggers factory](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionTriggers.cs#L19-L134), [pipeline](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/PipelineCompactionStrategy.cs#L13-L62), [chat reducer bridge](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/ChatReducerCompactionStrategy.cs#L20-L42), [AsChatReducer](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/ChatStrategyExtensions.cs#L19-L57))

---

## 12. Concurrency, streaming, and cancellation

The compaction strategies themselves are mostly synchronous-style mutation, but asynchrony and cancellation semantics matter because of summarization / provider integration.

### Python
- strategies are `async def __call__(messages) -> bool`
- summarization performs an async client call.
- the repeated failure counter is instance-local state, so the behavior is shared when a strategy instance is shared.
- there is no separate streaming-specific compaction strategy; the upper-layer agent/client handles streaming.  
  ([CompactionStrategy protocol](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L61-L73), [SummarizationStrategy async path](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L1348-L1372), [failure counter fields](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L1269-L1288))

### .NET
- `CompactAsync(..., CancellationToken)` exists on every strategy/provider surface.
- `SummarizationCompactionStrategy` does not catch an `OperationCanceledException` and propagates it.
- `ChatReducerCompactionStrategy` and `AsChatReducer()` pass the cancellation token straight through to the reducer/strategy.  
  ([base CompactAsync signature](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionStrategy.cs#L96-L105), [summarization cancellation behavior](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/SummarizationCompactionStrategy.cs#L189-L208), [summarization cancel tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Compaction/SummarizationCompactionStrategyTests.cs#L521-L573), [chat reducer token pass-through test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Compaction/ChatReducerCompactionStrategyTests.cs#L205-L220), [AsChatReducer token pass-through test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Compaction/ChatStrategyExtensionsTests.cs#L83-L105))

---

## 13. Errors, validation, and recovery behavior

## 13.1 Python

### Value validation
- `TruncationStrategy`: `max_n > 0`, `compact_to > 0`, `compact_to <= max_n`
- `SlidingWindowStrategy`: `keep_last_groups > 0`
- `SelectiveToolCallCompactionStrategy`: `keep_last_tool_call_groups >= 0`
- `ToolResultCompactionStrategy`: `keep_last_tool_call_groups >= 0`
- `SummarizationStrategy`: `target_count > 0`, `threshold >= 0`, `max_summary_input_tokens > 0`
- `ContextWindowCompactionStrategy`: positive context, valid thresholds, truncation threshold >= tool eviction threshold

([Python validations](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L828-L833), [sliding validation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L890-L893), [selective validation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L938-L940), [tool-result validation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L999-L1001), [summarization validation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L1252-L1263), [context-window validation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L1684-L1693))

### Failure recovery
- summarizer exception → warning plus `False` plus keep the originals
- empty summary → warning + `False`
- no group fits input budget → warning + `False`
- repeated summary failure ≥ 3 → one-time error log

([failure logic](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L1348-L1372), [no-fit budget warning](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L1340-L1346), [failure escalation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L1273-L1288), [tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_compaction.py#L975-L1067))

## 13.2 .NET

### Value validation
- `ContextWindowCompactionStrategy` strictly validates the context/output/threshold ranges.
- The base `CompactionStrategy` rejects a null trigger.
- The provider rejects a null strategy.

([ContextWindow validation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/ContextWindowCompactionStrategy.cs#L66-L90), [base ctor](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionStrategy.cs#L64-L68), [provider ctor](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionProvider.cs#L53-L69), [provider null test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Compaction/CompactionProviderTests.cs#L17-L21))

### Failure recovery
- summarizer exception → restore the excluded groups, do not insert the summary group, return `false`
- cancellation → propagate
- when the reducer fails to reduce the message count it is treated as no compaction
- the provider silently skips on `no session`, `no messages`, or a `remote service-managed session`

([summarization restore](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/SummarizationCompactionStrategy.cs#L189-L208), [summarization tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Compaction/SummarizationCompactionStrategyTests.cs#L417-L612), [chat reducer no-op rule](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/ChatReducerCompactionStrategy.cs#L74-L80), [provider skip conditions](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionProvider.cs#L123-L135))

---

## 14. Security

Compaction itself is not a privilege-escalation feature, but it is an indirect prompt injection boundary in that **a summary or a reducer can permanently insert new assistant-like content into the history**.

### Python
The Python `SummarizationStrategy` takes the summarization client as an explicit opt-in and warns that an untrusted summarizer can put a malicious summary into persistent state.  
([security note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L1207-L1216), [ctor security note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L1231-L1236))

### .NET
.NET states the same risk in two places.

- `SummarizationCompactionStrategy`
- `ChatReducerCompactionStrategy` (when the reducer is external / LLM-backed)

([summarization security note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/SummarizationCompactionStrategy.cs#L36-L45), [chat reducer security note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/ChatReducerCompactionStrategy.cs#L35-L42), [provider security note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionProvider.cs#L35-L44))

### Additional correctness security
Tool-call/result atomicity is not merely an optimization but an API correctness/security invariant. A partial deletion produces a downstream LLM API error.  
([Python atomic grouping tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_compaction.py#L159-L243), [.NET ToolCall atomicity docs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionGroupKind.cs#L34-L41))

---

## 15. .NET implementation and tests

### Implementation summary
- `CompactionMessageIndex` takes charge of grouping/metrics/incremental update.
- The `CompactionStrategy` base takes charge of trigger/target/telemetry/logging.
- The concrete strategies mutate the index.
- `CompactionProvider` is a pre-invocation provider plus an incremental persisted index adapter.
- `AsChatReducer()` connects the same strategy into the chat reducer world.  
([index](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionMessageIndex.cs#L16-L26), [strategy base](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionStrategy.cs#L15-L18), [provider](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionProvider.cs#L19-L47), [AsChatReducer](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/ChatStrategyExtensions.cs#L19-L57))

### Test coverage points
- grouping / summary recognition / incremental update  
  ([CompactionMessageIndexTests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Compaction/CompactionMessageIndexTests.cs#L18-L260), [reasoning+toolcall tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Compaction/CompactionMessageIndexTests.cs#L1388-L1506))
- base strategy target semantics  
  ([CompactionStrategyTests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Compaction/CompactionStrategyTests.cs#L134-L203))
- trigger combinators  
  (`CompactionTriggersTests` file exists in test inventory and enumerates Tokens/Messages/Turns/Groups/HasToolCalls behavior)  
  ([test inventory file path](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Compaction/CompactionTriggersTests.cs))
- concrete strategies  
  ([SlidingWindowCompactionStrategyTests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Compaction/SlidingWindowCompactionStrategyTests.cs#L15-L220), [TruncationCompactionStrategyTests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Compaction/TruncationCompactionStrategyTests.cs#L15-L220), [ToolResultCompactionStrategyTests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Compaction/ToolResultCompactionStrategyTests.cs#L16-L193), [SummarizationCompactionStrategyTests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Compaction/SummarizationCompactionStrategyTests.cs#L35-L612), [ContextWindowCompactionStrategyTests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Compaction/ContextWindowCompactionStrategyTests.cs#L16-L219))
- provider and bridge  
  ([CompactionProviderTests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Compaction/CompactionProviderTests.cs#L17-L260), [ChatReducerCompactionStrategyTests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Compaction/ChatReducerCompactionStrategyTests.cs#L18-L220), [ChatStrategyExtensionsTests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Compaction/ChatStrategyExtensionsTests.cs#L18-L159))

---

## 16. Python implementation and tests

### Implementation summary
- compaction works on the basis of message annotations.
- `annotate_message_groups(...)` and `annotate_token_counts(...)` are the foundation.
- The strategies mutate the message list directly, and the provider compacts the context-loaded messages and the stored history in before/after respectively.
- `TokenBudgetComposedStrategy` and `ContextWindowCompactionStrategy` take charge of token-budget orchestration.  
([annotation core](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L543-L674), [strategies](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L797-L1732), [provider](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L1494-L1618))

### Test coverage points
- atomic grouping / reasoning / reused call-id / nonadjacent result pairing  
  ([tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_compaction.py#L159-L243), [reused/ambiguous id tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_compaction.py#L294-L377))
- incremental annotation correctness and unique ids  
  ([tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_compaction.py#L435-L615))
- strategy family  
  ([truncation/selective tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_compaction.py#L619-L761), [summarization tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_compaction.py#L848-L1067), [token-budget/apply_compaction tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_compaction.py#L1070-L1149), [tool-result tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_compaction.py#L1155-L1540))
- provider integration / history loading behavior / context-window thresholds  
  ([provider tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_compaction.py#L1542-L1770), [context-window tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_compaction.py#L1775-L1941))

---

## 17. Documentation differences and differences between the SDKs

## 17.1 Maturity difference
- Python: top-level stable-looking public utility
- .NET: the compaction package is experimental throughout  
  ([Python exports](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/__init__.py#L58-L86), [.NET experimental markers](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionStrategy.cs#L49-L50))

## 17.2 Same name, different semantics
- `SlidingWindow`: Python is group-based, .NET is turn-based  
  ([Python](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L870-L913), [.NET](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/SlidingWindowCompactionStrategy.cs#L14-L32))
- `ToolResultCompaction`: Python is a one-line bracket summary, .NET is a YAML-like block  
  ([Python](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L1085-L1101), [.NET](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/ToolResultCompactionStrategy.cs#L237-L254))
- selective policy: Python has an explicit strategy, and the inspected .NET set has no direct analog  
  ([Python selective strategy](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L916-L966), [.NET inspected strategy set](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/ToolResultCompactionStrategy.cs#L14-L49))

## 17.3 Provider integration difference
- The Python provider supports both before and after
- The .NET provider is a pre-invocation provider plus a persisted incremental index plus a chat reducer bridge  
  ([Python provider](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L1494-L1618), [.NET provider](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionProvider.cs#L19-L47), [.NET reducer bridge](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/ChatStrategyExtensions.cs#L19-L57))

## 17.4 Density difference between the documentation and the code
- In .NET the source XML doc explains the design in detail.
- In Python much of the strategy meaning and the edge cases is carried more in the test files and the inline comments.  
  ([.NET XML-heavy strategy docs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionStrategy.cs#L15-L47), [Python edge-case tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_compaction.py#L159-L1941))

---

## 18. Java decisions

### 18.1 What to include
The Java compaction MVP and follow-up design is better off including the following.

1. **An explicit grouping/index abstraction**  
   - Compromising between Python's annotation richness and .NET's index simplicity, it is better to keep an index internally while still leaving summary trace metadata on the messages.  
   ([Python trace-rich annotations](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L35-L37), [.NET index abstraction](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionMessageIndex.cs#L16-L26))
2. **trigger/target abstraction**  
   - The .NET model exposes composition and stopping semantics more clearly.  
   ([.NET trigger/target model](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionStrategy.cs#L25-L35))
3. **explicit SelectiveToolCall strategy**  
   - The explicit selective policy Python has is useful for Java too. .NET has no direct analog in the inspected set.  
   ([Python selective strategy](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L916-L966))
4. **Context-window convenience strategy**
   - The input budget computation and the multi-phase orchestration are needed as they are.  
   ([Python context-window](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L1620-L1732), [.NET context-window](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/ContextWindowCompactionStrategy.cs#L13-L148))

### 18.2 What to exclude or defer initially
1. **Do not promise the LLM summarization strategy as stable**
   - There is a persistent indirect prompt injection risk. Keeping it experimental in the first release is appropriate.  
   ([Python security note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L1207-L1216), [.NET security note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/SummarizationCompactionStrategy.cs#L36-L45))
2. **remote-threaded session special case**
   - Provider skip semantics for a remote conversation id session may be needed as in .NET, but it is better added once the Java session model is settled.  
   ([.NET remote skip](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionProvider.cs#L129-L135))
3. **chat reducer bridge**
   - When Java has no equivalent reducer abstraction it can be omitted in the first release.  
   ([.NET reducer bridge](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/ChatStrategyExtensions.cs#L19-L57))

### 18.3 Recommended default choices
- grouping semantics: **tool-call/result atomicity is mandatory**
- sliding semantics: **provide turn-based and group-based as two separate strategies**
- token estimator default: **a 4 chars/token heuristic plus prevention of non-ASCII inflation**
- summarization failure semantics: **restore originals + return false**
- provider integration: **before-run projection + optional after-store compaction hook**  
  ([Python non-ASCII serialization rule](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L643-L645), [Python summarization failure semantics](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L1348-L1372), [.NET summarization restore semantics](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/SummarizationCompactionStrategy.cs#L196-L208))

---

## 19. Acceptance scenarios

1. **tool-call/result atomicity**
   - Even with a non-adjacent approval gap, a tool result must be grouped into the same group as the declaration group as long as the call id is unambiguous.  
   ([Python test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_compaction.py#L209-L243))
   - In .NET too, an assistant tool call plus the tool result must be a single `ToolCall` group.  
   ([.NET test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Compaction/CompactionMessageIndexTests.cs#L83-L101))

2. **incremental update correctness**
   - Appending annotation/update for only the new message tail must not break the existing prefix ids and token counts.  
   ([Python incremental id tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_compaction.py#L561-L615), [.NET incremental update test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Compaction/CompactionMessageIndexTests.cs#L1451-L1477))

3. **system anchor preservation**
   - The truncation/sliding policies must preserve the system anchor.  
   ([Python truncation system anchor](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_compaction.py#L619-L635), [.NET truncation preserves system](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Compaction/TruncationCompactionStrategyTests.cs#L87-L112), [.NET sliding preserves system](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Compaction/SlidingWindowCompactionStrategyTests.cs#L65-L87))

4. **tool-result summary safety**
   - A large tool result must not enter the summary verbatim in full and must carry a truncation marker.  
   ([Python test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_compaction.py#L1290-L1316))

5. **summarization failure restore**
   - On a summarizer exception all originals must be restored and no summary group may be created.  
   ([Python failure test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_compaction.py#L975-L995), [.NET failure restore tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Compaction/SummarizationCompactionStrategyTests.cs#L420-L519))

6. **cancellation semantics**
   - .NET summarization compaction must not swallow an `OperationCanceledException`.  
   ([.NET cancel test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Compaction/SummarizationCompactionStrategyTests.cs#L521-L573))

7. **history load behavior**
   - Even when Python leaves excluded messages in storage, they must be left out of the next load when `skip_excluded=True`.  
   ([test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_compaction.py#L1734-L1752))

8. **context-window phase separation**
   - Above 50% only tool-result eviction must happen, and above 80% truncation must happen as well.  
   ([Python tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_compaction.py#L1796-L1868), [.NET tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Compaction/ContextWindowCompactionStrategyTests.cs#L122-L191))

9. **non-ASCII token accounting**
   - Python serialization must count a CJK string lower on an actual character basis than through `\uXXXX` escapes.  
   ([test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_compaction.py#L1928-L1941))

10. **remote service session skip (.NET)**
    - For a session carrying a remote conversation id the provider must pass through without applying compaction.  
    ([provider code](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionProvider.cs#L129-L135))

---

## 20. Source inventory

### Python source
- `python/packages/core/agent_framework/_compaction.py`  
  https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py
- `python/packages/core/agent_framework/__init__.py`  
  https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/__init__.py

### Python tests
- `python/packages/core/tests/core/test_compaction.py`  
  https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_compaction.py

### .NET source
- `dotnet/src/Microsoft.Agents.AI/Compaction/CompactionStrategy.cs`  
  https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionStrategy.cs
- `dotnet/src/Microsoft.Agents.AI/Compaction/CompactionProvider.cs`  
  https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionProvider.cs
- `dotnet/src/Microsoft.Agents.AI/Compaction/CompactionMessageIndex.cs`  
  https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionMessageIndex.cs
- `dotnet/src/Microsoft.Agents.AI/Compaction/CompactionMessageGroup.cs`  
  https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionMessageGroup.cs
- `dotnet/src/Microsoft.Agents.AI/Compaction/CompactionGroupKind.cs`  
  https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionGroupKind.cs
- `dotnet/src/Microsoft.Agents.AI/Compaction/CompactionTriggers.cs`  
  https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionTriggers.cs
- `dotnet/src/Microsoft.Agents.AI/Compaction/SlidingWindowCompactionStrategy.cs`  
  https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/SlidingWindowCompactionStrategy.cs
- `dotnet/src/Microsoft.Agents.AI/Compaction/TruncationCompactionStrategy.cs`  
  https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/TruncationCompactionStrategy.cs
- `dotnet/src/Microsoft.Agents.AI/Compaction/ToolResultCompactionStrategy.cs`  
  https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/ToolResultCompactionStrategy.cs
- `dotnet/src/Microsoft.Agents.AI/Compaction/SummarizationCompactionStrategy.cs`  
  https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/SummarizationCompactionStrategy.cs
- `dotnet/src/Microsoft.Agents.AI/Compaction/ContextWindowCompactionStrategy.cs`  
  https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/ContextWindowCompactionStrategy.cs
- `dotnet/src/Microsoft.Agents.AI/Compaction/PipelineCompactionStrategy.cs`  
  https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/PipelineCompactionStrategy.cs
- `dotnet/src/Microsoft.Agents.AI/Compaction/ChatReducerCompactionStrategy.cs`  
  https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/ChatReducerCompactionStrategy.cs
- `dotnet/src/Microsoft.Agents.AI/Compaction/ChatStrategyExtensions.cs`  
  https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/ChatStrategyExtensions.cs
- `dotnet/src/Microsoft.Agents.AI/Compaction/CompactionLogMessages.cs`  
  https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionLogMessages.cs

### .NET tests
- `dotnet/tests/Microsoft.Agents.AI.UnitTests/Compaction/CompactionMessageIndexTests.cs`  
  https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Compaction/CompactionMessageIndexTests.cs
- `dotnet/tests/Microsoft.Agents.AI.UnitTests/Compaction/CompactionStrategyTests.cs`  
  https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Compaction/CompactionStrategyTests.cs
- `dotnet/tests/Microsoft.Agents.AI.UnitTests/Compaction/CompactionProviderTests.cs`  
  https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Compaction/CompactionProviderTests.cs
- `dotnet/tests/Microsoft.Agents.AI.UnitTests/Compaction/SlidingWindowCompactionStrategyTests.cs`  
  https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Compaction/SlidingWindowCompactionStrategyTests.cs
- `dotnet/tests/Microsoft.Agents.AI.UnitTests/Compaction/TruncationCompactionStrategyTests.cs`  
  https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Compaction/TruncationCompactionStrategyTests.cs
- `dotnet/tests/Microsoft.Agents.AI.UnitTests/Compaction/ToolResultCompactionStrategyTests.cs`  
  https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Compaction/ToolResultCompactionStrategyTests.cs
- `dotnet/tests/Microsoft.Agents.AI.UnitTests/Compaction/SummarizationCompactionStrategyTests.cs`  
  https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Compaction/SummarizationCompactionStrategyTests.cs
- `dotnet/tests/Microsoft.Agents.AI.UnitTests/Compaction/ContextWindowCompactionStrategyTests.cs`  
  https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Compaction/ContextWindowCompactionStrategyTests.cs
- `dotnet/tests/Microsoft.Agents.AI.UnitTests/Compaction/ChatReducerCompactionStrategyTests.cs`  
  https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Compaction/ChatReducerCompactionStrategyTests.cs
- `dotnet/tests/Microsoft.Agents.AI.UnitTests/Compaction/ChatStrategyExtensionsTests.cs`  
  https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Compaction/ChatStrategyExtensionsTests.cs

### Harness integration boundary source
- `dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs`  
  https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs