# 11. Compaction

## 1. 문서 목적과 범위

이 문서는 Microsoft Agent Framework의 **compaction subsystem**만 별도로 정리한다. 범위는 다음이다.

- Python의 `CompactionProvider`와 전략 집합
- .NET의 `CompactionProvider`, `CompactionStrategy` 계층, `CompactionMessageIndex`
- grouping / annotation / indexing
- token estimation / trigger / target / threshold
- sliding / truncation / summarization / tool-result / selective 계열 정책
- session / context / chat history integration
- 오류, 복구, 취소, 보안 경계

이 문서는 **harness 조립 규칙 전체**를 다시 설명하지 않는다. Harness가 compaction을 어디에 배선하는지는 경계로만 언급하고, 조립 상세는 harness 문서로 분리한다.  
([Python top-level compaction exports](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/__init__.py#L58-L86), [Python public re-export subset](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/__init__.py#L598-L603), [.NET harness compaction wiring boundary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs#L166-L197))

---

## 2. 상태와 스냅샷 모델

Compaction은 두 SDK에서 구조적으로 다르다.

- **Python**은 `Message.additional_properties`에 group/token/excluded/summary linkage를 **메시지 단위 annotation**으로 적는다. 즉 compaction 상태가 메시지 객체에 붙는다.  
  ([annotation keys](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L26-L37), [annotation write path](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L607-L618), [exclusion write](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L718-L733))
- **.NET**은 별도의 `CompactionMessageGroup` / `CompactionMessageIndex`가 canonical state다. excluded 여부와 이유는 group object에 저장되고, summary 여부만 message의 `AdditionalProperties[SummaryPropertyKey]`로 round-trip된다.  
  ([CompactionMessageGroup model](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionMessageGroup.cs#L12-L29), [group exclusion fields](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionMessageGroup.cs#L103-L115), [summary property key](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionMessageGroup.cs#L33-L40))

이 차이는 이후 모든 설계 차이로 이어진다.

- Python은 **메시지 리스트 자체를 in-place mutate**하는 쪽에 가깝다.
- .NET은 **group index를 만들고 그 위에서 compact**한 뒤 projection하는 쪽에 가깝다.  
  ([Python strategy protocol mutates messages in place](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L61-L73), [.NET strategy base mutates index in place](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionStrategy.cs#L20-L27), [.NET provider returns projection of included messages](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionProvider.cs#L197-L202))

---

## 3. 목적·경계

Compaction의 목적은 단순히 오래된 메시지를 버리는 것이 아니라, **LLM API가 깨지지 않는 구조적 단위**를 유지하면서 context 크기를 줄이는 것이다. 두 SDK 모두 tool-call/result 쌍의 atomicity를 핵심 invariant로 둔다.  
([Python grouping comment](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L253-L276), [.NET group kind remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionGroupKind.cs#L12-L15), [.NET ToolCall atomicity](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionGroupKind.cs#L34-L41))

이 문서의 경계는 다음과 같다.

- **Harness 문서와 중복 금지**: harness가 before/after/in-loop 어디에 compaction을 꽂는지의 상세 rationale은 재서술하지 않는다. 다만 provider가 지원하는 lifecycle point는 설명한다.  
  ([Python provider before/after hooks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L1494-L1618), [.NET strategy lifecycle remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionStrategy.cs#L37-L47))
- **Skills / background / code execution**는 별도 문서로 분리한다. 여기서는 tool-call content가 grouping 대상이라는 정도만 다룬다.  
  ([Python tool-call content types](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L42-L48), [.NET tool call grouping in index creation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionMessageIndex.cs#L197-L211))

---

## 4. 성숙도

### Python
Python compaction surface는 top-level `agent_framework`에서 직접 export되며, inspected source 기준 class/function 위에 별도의 feature-stage decorator가 붙어 있지 않다. 즉 코드 수준에서는 **stable public utility layer**처럼 보인다.  
([public compaction export list](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/__init__.py#L58-L86), [public re-exported functions](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/__init__.py#L598-L603), [provider definition without feature-stage decorator](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L1494-L1498))

### .NET
반대로 .NET compaction subsystem의 핵심 타입은 모두 `[Experimental]`이다.

- `CompactionStrategy`
- `CompactionProvider`
- `CompactionMessageIndex`
- `CompactionMessageGroup`
- `CompactionGroupKind`
- 각 concrete strategy
- `CompactionTriggers`
- `ChatStrategyExtensions`

([CompactionStrategy experimental](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionStrategy.cs#L49-L50), [CompactionProvider experimental](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionProvider.cs#L46-L47), [CompactionMessageIndex experimental](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionMessageIndex.cs#L25-L26), [CompactionMessageGroup experimental](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionMessageGroup.cs#L30-L31), [CompactionGroupKind experimental](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionGroupKind.cs#L16-L17), [CompactionTriggers experimental](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionTriggers.cs#L22-L23), [ChatStrategyExtensions experimental](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/ChatStrategyExtensions.cs#L16-L17))

**요약**: compaction은 현재 commit 기준으로 **Python 쪽이 더 공개-안정화된 표면**, **.NET 쪽이 더 명시적으로 실험적 표면**이다.

---

## 5. 공개 API·타입

## 5.1 Python

### 핵심 타입
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

### 핵심 함수
- `annotate_message_groups(...)`
- `annotate_token_counts(...)`
- `extend_compaction_messages(...)`
- `append_compaction_message(...)`
- `included_messages(...)`
- `included_token_count(...)`
- `apply_compaction(...)`

([functions](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L543-L715), [apply_compaction](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L1475-L1488))

## 5.2 .NET

### 핵심 타입
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

### 중요한 차이
- Python에는 **명시적 `CompactionTrigger` 추상화가 없다**. 각 전략이 threshold/조건을 직접 가진다.
- .NET은 모든 concrete strategy가 **`trigger` + optional `target`**를 받아, “언제 시작하고 언제 멈출지”를 분리한다.  
  ([Python truncation constructor style](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L809-L837), [.NET base trigger/target model](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionStrategy.cs#L25-L35), [.NET truncation ctor](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/TruncationCompactionStrategy.cs#L39-L57))

---

## 6. Grouping / annotation / indexing

## 6.1 Python: 메시지 주석(annotation) 모델

Python은 compaction 전에 메시지를 group annotation으로 장식한다. 핵심 키는 다음이다.

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

그룹 규칙은 다음과 같다.

- system 메시지 → 독립 group
- user 메시지 → 독립 group
- assistant tool call + 뒤따르는 tool result → `tool_call` group
- reasoning-only assistant prefix가 tool call 앞에 있으면 같은 `tool_call` group으로 흡수
- non-adjacent function result도 call id가 **unambiguous**하면 declaration group에 다시 link
- ambiguous duplicate call id는 pair로 묶지 않음

([group_messages rules](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L200-L325), [linking non-adjacent results](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L150-L198), [atomicity tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_compaction.py#L159-L243), [ambiguous duplicate-id tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_compaction.py#L294-L308))

또한 Python은 incremental annotation을 지원한다. 즉 새 메시지 tail만 재annotate하고, 이미 계산된 prefix token count와 IDs를 보존한다.  
([incremental annotation core](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L543-L624), [extend_compaction_messages](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L676-L702), [preserve annotations/tokens test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_compaction.py#L435-L450), [unique message ids regression tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_compaction.py#L561-L615))

## 6.2 .NET: 그룹 인덱스(index) 모델

.NET은 `CompactionMessageIndex.Create(messages)`가 flat `ChatMessage` 시퀀스를 `CompactionMessageGroup` 집합으로 바꾼다. 그룹 분류 규칙은 다음이다.

- system → `System`
- user → `User`
- assistant with tool calls + following tool results / reasoning-only assistant messages → `ToolCall`
- summary marker가 붙은 assistant message → `Summary`
- 일반 assistant text → `AssistantText`

([index create remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionMessageIndex.cs#L73-L91), [append logic](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionMessageIndex.cs#L177-L264), [summary recognition](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionMessageIndex.cs#L527-L534))

reasoning-only assistant messages는 tool-call group에 합쳐질 수 있지만, reasoning + plain text가 섞인 assistant message는 `AssistantText`로 남는다.  
([reasoning grouping source](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionMessageIndex.cs#L218-L257), [reasoning tool-call test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Compaction/CompactionMessageIndexTests.cs#L1388-L1410), [mixed reasoning/text test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Compaction/CompactionMessageIndexTests.cs#L1412-L1432))

또한 .NET도 incremental update를 지원한다. 기존 `_lastProcessedMessage`를 찾아 suffix만 append하고, message list가 sliding window로 바뀌어 prefix가 잘렸으면 전체 rebuild한다.  
([incremental update algorithm](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionMessageIndex.cs#L99-L175), [incremental reasoning/tool-call append test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Compaction/CompactionMessageIndexTests.cs#L1451-L1477))

### 핵심 차이
- Python은 **per-message annotation** 기반 traceability가 강하다.
- .NET은 **group object** 중심이라 projection/metrics는 단순하지만, Python처럼 원본↔summary bidirectional metadata를 광범위하게 메시지에 심지는 않는다.  
  ([Python summary linkage keys](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L35-L37), [Python bidirectional summary tracing test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_compaction.py#L848-L877), [.NET summary insertion only marks SummaryPropertyKey](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/SummarizationCompactionStrategy.cs#L214-L218))

---

## 7. Token estimation / metrics / triggers

## 7.1 Python

Python token accounting은 `_serialize_message(message)` 결과에 tokenizer를 적용하는 방식이다. 기본 tokenizer는 `CharacterEstimatorTokenizer`로 **4 chars/token** 휴리스틱을 쓴다. non-ASCII 텍스트는 `ensure_ascii=False`로 직렬화하여 `\uXXXX` escape로 부풀려 세지 않는다.  
([CharacterEstimatorTokenizer](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L76-L80), [serialize_message ensure_ascii=False](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L636-L645), [non-ASCII test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_compaction.py#L1928-L1941))

metric helpers는 `included_messages(...)`, `included_token_count(...)`, included group/message counts를 annotation에서 계산한다.  
([included projection helpers](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L705-L737))

## 7.2 .NET

.NET은 `CompactionMessageGroup`에 byte/token/message count를 저장하고, `CompactionMessageIndex`가 aggregate metric을 노출한다.

- `Total*` / `Included*` metrics
- `IncludedTurnCount`
- `IncludedNonSystemGroupCount`
- `RawMessageCount`

([group metrics fields](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionMessageGroup.cs#L73-L101), [index aggregate metrics](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionMessageIndex.cs#L316-L374))

token count 계산은 `Tokenizer`가 있으면 텍스트류(`TextContent`, `TextReasoningContent`, `ProtectedData`)를 직접 세고, 나머지 content는 byteCount/4로 근사한다.  
([ComputeTokenCount](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionMessageIndex.cs#L403-L452), [ComputeContentByteCount coverage](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionMessageIndex.cs#L455-L495))

## 7.3 .NET trigger / target 모델

.NET은 `CompactionTrigger`라는 1차 predicate를 두고, `CompactionStrategy`가 이를 먼저 평가한다. optional `target`은 compaction 도중 “언제 멈출지”를 정한다. target을 주지 않으면 기본값은 **trigger의 inverse**다.  
([CompactionTrigger delegate](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionTrigger.cs#L15-L15), [base trigger/target docs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionStrategy.cs#L25-L35), [default target code](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionStrategy.cs#L64-L68), [default target test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Compaction/CompactionStrategyTests.cs#L134-L171))

준비된 trigger factory는 다음을 제공한다.

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

### 중요한 SDK 차이
Python은 explicit trigger algebra 없이 **전략 객체 자체에 threshold semantics가 박혀 있고**, .NET은 **trigger/target을 orthogonal하게 조합**한다. 이는 Java 설계에서 선택이 필요한 차이다.  
([Python truncation signature](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L809-L837), [.NET trigger/target signature](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/TruncationCompactionStrategy.cs#L39-L57))

---

## 8. 상세 전략군

## 8.1 Truncation

### Python
Python `TruncationStrategy`는 **oldest-first group exclusion**이다.

- tokenizer가 있으면 token-based threshold
- tokenizer가 없으면 included message count threshold
- `preserve_system=True`면 system groups는 유지
- 최소 1개 retained group은 항상 남긴다

([strategy doc](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L797-L807), [constructor validation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L809-L837), [algorithm](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L839-L867))

테스트는 system anchor 유지, token limit compaction, oversized latest group 유지, nonadjacent tool pair atomicity를 고정한다.  
([tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_compaction.py#L619-L710))

### .NET
.NET `TruncationCompactionStrategy`도 oldest-first exclusion이지만, threshold semantics는 trigger/target로 분리되고 `MinimumPreservedGroups`를 hard floor로 둔다.  
([strategy docs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/TruncationCompactionStrategy.cs#L11-L29), [ctor](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/TruncationCompactionStrategy.cs#L39-L63), [algorithm](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/TruncationCompactionStrategy.cs#L66-L109))

tests는 trigger-not-met, oldest exclusion, system preservation, tool-call group atomicity, exclude reason, minimum preserved behavior를 검증한다.  
([tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Compaction/TruncationCompactionStrategyTests.cs#L15-L220))

---

## 8.2 Sliding window

### Python
Python `SlidingWindowStrategy`는 **최근 non-system groups N개**를 남긴다. 즉 turn이 아니라 group 개수 기준이다.  
([strategy docs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L870-L881), [algorithm](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L895-L913))

tests는 reused call-id pair, reasoning+MCP call atomicity, hosted tool reasoning adjacency를 검증한다.  
([tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_compaction.py#L346-L417))

### .NET
.NET `SlidingWindowCompactionStrategy`는 **최근 turn N개**를 남긴다. 즉 `TurnIndex` 기반이다. group-count 기준이 아니다. `TurnIndex 0` 또는 `null`은 항상 보호된다.  
([strategy docs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/SlidingWindowCompactionStrategy.cs#L13-L33), [ctor and preserved turns semantics](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/SlidingWindowCompactionStrategy.cs#L43-L70), [algorithm](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/SlidingWindowCompactionStrategy.cs#L73-L139))

tests는 oldest turn exclusion, system preservation, tool-call group preservation, custom target early stop, minimum preserved turns hard floor를 검증한다.  
([tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Compaction/SlidingWindowCompactionStrategyTests.cs#L15-L220))

### 중요한 차이
- Python sliding window = **group-based**
- .NET sliding window = **turn-based**

이 차이는 같은 이름이라도 behavior parity가 아니다.  
([Python group-based keep_last_groups](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L883-L913), [.NET turn-based minimumPreservedTurns](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/SlidingWindowCompactionStrategy.cs#L49-L70))

---

## 8.3 Selective tool-call policy

### Python
Python에는 별도의 `SelectiveToolCallCompactionStrategy`가 있다. 이는 `tool_call` group만 대상으로 하며, 최신 `keep_last_tool_call_groups`개를 남기고 나머지 tool-call groups를 **완전히 제외**한다.  
([strategy docs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L916-L925), [algorithm](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L942-L966))

tests는 old tool groups exclusion, `keep=0`이면 assistant tool pair까지 모두 제거, negative keep rejection을 검증한다.  
([tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_compaction.py#L715-L761))

### .NET
이번 commit에서 inspected .NET compaction package의 concrete 전략 집합에는 **별도의 selective-only tool-call exclusion strategy**가 보이지 않는다. tool-call 특화 정책으로는 `ToolResultCompactionStrategy`가 제공되며, 이는 제거 대신 **summary replacement**를 수행한다.  
([.NET strategy set in inspected sources: ToolResult](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/ToolResultCompactionStrategy.cs#L14-L49), [Truncation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/TruncationCompactionStrategy.cs#L11-L31), [Sliding](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/SlidingWindowCompactionStrategy.cs#L13-L35), [Summarization](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/SummarizationCompactionStrategy.cs#L16-L48), [ContextWindow](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/ContextWindowCompactionStrategy.cs#L13-L35), [Pipeline](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/PipelineCompactionStrategy.cs#L13-L28), [ChatReducer bridge](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/ChatReducerCompactionStrategy.cs#L14-L46))

---

## 8.4 Tool-result compaction

### Python
Python `ToolResultCompactionStrategy`는 오래된 tool-call group을 **한 줄 bracket summary**로 바꾼다.

형태 예:
- `[Tool results: get_weather: sunny, 18°C]`

가장 최신 `keep_last_tool_call_groups`개는 그대로 남기고, 더 오래된 그룹만 collapse한다.  
([strategy docs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L969-L981), [summary format implementation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L1085-L1101))

Python은 summary message에 `_summary_of_message_ids`, `_summary_of_group_ids`를 넣고, 원본 메시지에는 `_summarized_by_summary_id` backlink를 남긴다. 또한 summary message 자체도 full group annotations를 다시 부여한다.  
([forward/back links insertion](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L1056-L1083), [bidirectional tracing test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_compaction.py#L1356-L1412))

tool result summary는 large payload를 bounded string으로 잘라내고, 이미 excluded된 result를 summary에 다시 넣지 않는다.  
([summary truncation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L1085-L1101), [large summary bound test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_compaction.py#L1290-L1316), [do not restore excluded results test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_compaction.py#L1319-L1354))

### .NET
.NET `ToolResultCompactionStrategy`는 오래된 tool-call group을 **YAML-like multi-line block**으로 바꾼다.

형태 예:
```text
[Tool Calls]
get_weather:
  - Sunny and 72°F
search_docs:
  - Found 3 docs
```

([strategy docs and format example](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/ToolResultCompactionStrategy.cs#L14-L31), [default formatter implementation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/ToolResultCompactionStrategy.cs#L164-L254))

원본 group은 `IsExcluded=true`가 되고, replacement summary는 `SummaryPropertyKey=true`가 붙은 새 `Summary` group으로 index에 insert된다. Python처럼 원본↔summary backlink metadata를 폭넓게 남기지는 않는다.  
([replacement logic](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/ToolResultCompactionStrategy.cs#L140-L159), [summary property key meaning](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionMessageGroup.cs#L33-L40))

tests는 collapse, recent preservation, multi-tool extraction, compound trigger, target early stop을 검증한다.  
([tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Compaction/ToolResultCompactionStrategyTests.cs#L16-L193))

### 중요한 차이
- Python summary = **compact one-line bracket text**
- .NET summary = **YAML-like block**
- Python은 bidirectional trace links를 남기고, .NET은 replacement group 중심이다.  
([Python summary style](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L983-L1101), [.NET summary style](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/ToolResultCompactionStrategy.cs#L237-L254))

---

## 8.5 Summarization

### Python
Python `SummarizationStrategy`는 oldest included non-system groups를 요약해 assistant summary message로 바꾸고, newest content near `target_count`를 남긴다.  
([strategy overview](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L1197-L1217), [selection and keep logic](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L1290-L1339))

특징:

- `max_summary_input_tokens` 예산 안에서 **complete groups만** 선택한다.
- 첫 group이 너무 크면 skip하고 뒤 그룹부터 선택할 수 있다.
- summary generation failure / empty summary는 `False`를 돌리고 originals를 유지한다.
- 3회 연속 실패하면 error log를 한 번 올린다.
- 성공하면 failure escalation 상태를 reset한다.

([input budget selection](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L1138-L1173), [failure handling](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L1348-L1372), [failure counter](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L1273-L1288), [complete-group budget tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_compaction.py#L879-L946), [repeated failure escalation tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_compaction.py#L975-L1046))

### .NET
.NET `SummarizationCompactionStrategy`는 oldest non-system groups를 고른 뒤, 하나의 LLM call로 summary를 생성해 single `Summary` group으로 바꾼다. `MinimumPreservedGroups`는 hard floor다.  
([strategy docs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/SummarizationCompactionStrategy.cs#L16-L45), [ctor](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/SummarizationCompactionStrategy.cs#L69-L106), [core algorithm](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/SummarizationCompactionStrategy.cs#L124-L223))

실패 semantics:

- non-cancellation exception → excluded groups 복구, no summary insertion, `false` 반환
- `OperationCanceledException` / `TaskCanceledException` → **전파**
- empty or whitespace response → `[Summary unavailable]` fallback 사용

([failure restore path](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/SummarizationCompactionStrategy.cs#L189-L208), [empty fallback](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/SummarizationCompactionStrategy.cs#L210-L218), [tests for restore/cancel](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Compaction/SummarizationCompactionStrategyTests.cs#L417-L612))

### 중요한 차이
- Python은 summary input budget과 repeated failure escalation을 가진다.
- .NET은 repeated failure counter는 없지만, cancellation propagation과 restore semantics를 명시적으로 테스트한다.
- Python summary는 bidirectional links를 남긴다. .NET summary는 `SummaryPropertyKey`와 excluded originals 복구 semantics 중심이다.  
([Python links](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L1373-L1397), [Python summary annotation test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_compaction.py#L1432-L1455), [.NET insertion](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/SummarizationCompactionStrategy.cs#L214-L223))

---

## 8.6 Pipeline / token-budget / context-window

### Python
Python은 `TokenBudgetComposedStrategy`와 `ContextWindowCompactionStrategy`로 **token budget orchestration**을 수행한다.

- `TokenBudgetComposedStrategy`는 strategy sequence를 순차 적용하고, 예산이 안 맞으면 deterministic fallback exclusion을 수행한다.
- `ContextWindowCompactionStrategy`는 input budget = `max_context_window_tokens - max_output_tokens`
  - 50%에서 tool-result eviction
  - 80%에서 truncation
  - default keep-last-tool-call-groups = 4

([TokenBudgetComposedStrategy](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L1400-L1473), [ContextWindowCompactionStrategy](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L1620-L1732))

fallback는 먼저 오래된 non-system groups를 제외하고, 그래도 예산이 안 맞으면 더 엄격한 strict fallback을 돈다.  
([fallback logic](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L1450-L1472))

tests는 under-threshold no-op, 50% 초과 시 tool-result eviction, 80% 초과 시 truncation, `keep_last_tool_call_groups` 보존, non-ASCII serialization correctness를 검증한다.  
([tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_compaction.py#L1775-L1941))

### .NET
.NET은 `PipelineCompactionStrategy` + `ContextWindowCompactionStrategy` 조합을 사용한다.

- `PipelineCompactionStrategy`는 child strategy를 순차 적용한다.
- `ContextWindowCompactionStrategy`는 input budget을 계산한 뒤
  - `ToolResultCompactionStrategy(trigger=TokensExceed(toolEvictionTokens), minimumPreservedGroups: 2)`
  - `TruncationCompactionStrategy(trigger=TokensExceed(truncationTokens), minimumPreservedGroups: 2)`
  로 파이프라인을 구성한다.

([PipelineCompactionStrategy](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/PipelineCompactionStrategy.cs#L13-L62), [ContextWindowCompactionStrategy](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/ContextWindowCompactionStrategy.cs#L13-L148))

tests는 property setting, invalid thresholds, tool eviction trigger, truncation trigger를 검증한다.  
([tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Compaction/ContextWindowCompactionStrategyTests.cs#L16-L219))

### 차이
- Python은 `TokenBudgetComposedStrategy`라는 **명시적 token-budget fallback orchestrator**를 갖고, .NET은 `PipelineCompactionStrategy`가 child trigger logic에 의존한다.
- Python default keep-last-tool-call-groups는 4, .NET context-window pipeline의 tool-result phase는 minimum preserved groups 2를 사용한다.  
  ([Python context-window defaults](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L1647-L1662), [.NET context-window pipeline fixed params](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/ContextWindowCompactionStrategy.cs#L98-L107))

---

## 8.7 Chat reducer bridge (.NET only)

.NET에는 양방향 bridge가 있다.

1. `ChatReducerCompactionStrategy`: existing `IChatReducer`를 compaction strategy로 감싼다.  
2. `AsChatReducer()`: any `CompactionStrategy`를 `IChatReducer`로 감싼다.  

([ChatReducerCompactionStrategy docs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/ChatReducerCompactionStrategy.cs#L15-L43), [ChatReducerCompactionStrategy core](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/ChatReducerCompactionStrategy.cs#L68-L91), [AsChatReducer](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/ChatStrategyExtensions.cs#L19-L57))

이 bridge는 .NET harness가 같은 strategy를 `InMemoryChatHistoryProvider.ChatReducer`에도 재사용할 수 있게 해준다.  
([harness using AsChatReducer](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Harness/HarnessAgent.cs#L190-L197))

Python에는 inspected compaction source 범위 안에서 동등한 `IChatReducer` bridge는 없다.

---

## 9. Session / context integration

## 9.1 Python provider integration

Python `CompactionProvider`는 두 lifecycle point를 가진다.

- `before_run`: context에 이미 올라와 있는 메시지를 compact하고 projection으로 필터링
- `after_run`: history provider가 session state에 저장한 메시지를 compact하되, excluded messages도 storage에는 남겨 annotation을 보존

([provider class and before_run](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L1494-L1587), [after_run and storage semantics](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L1588-L1618))

이때 next turn load behavior는 history provider의 `skip_excluded` 옵션에 달린다.  
([test skip_excluded true](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_compaction.py#L1734-L1752), [test default loads all](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_compaction.py#L1754-L1770))

또한 `apply_compaction(...)`는 provider 없이 ad-hoc projection을 할 수 있는 direct helper다.  
([apply_compaction](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L1475-L1488), [projection test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_compaction.py#L1139-L1149))

## 9.2 .NET provider integration

.NET `CompactionProvider`는 **pre-invocation compaction provider**다.

- session 또는 messages가 없으면 passthrough
- remote-managed session(`ChatClientAgentSession.ConversationId` 존재)이면 compact를 skip
- session state에 persisted `MessageGroups`가 있으면 기존 index를 불러와 incremental update
- 없으면 scratch create
- compact 후 `State.MessageGroups`에 index를 저장

([provider invocation flow](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionProvider.cs#L113-L181), [remote session skip](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionProvider.cs#L129-L135), [persisted state shape](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionProvider.cs#L210-L220))

또한 compact가 만들어낸 synthetic summary messages는 chat history source attribution을 강제로 `ChatHistory`로 마킹해, run 종료 후 중복 저장되지 않게 한다.  
([source attribution fixup](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionProvider.cs#L183-L195))

### 중요한 차이
- Python provider는 **before + after** 양쪽을 다룬다.
- .NET provider는 **pre-invocation compaction + session-persisted index**에 초점을 둔다.
- .NET은 remote service-managed threaded session에서는 skip한다. Python inspected provider에는 동등한 remote conversation-id skip guard가 없다.  
  ([Python provider hooks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L1494-L1618), [.NET provider flow](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionProvider.cs#L123-L135))

---

## 10. 상태·영속화

### Python
Python compaction은 별도 provider state object를 크게 유지하지 않고, 메시지 annotation과 history store의 저장 정책에 의존한다. `after_run`은 excluded originals를 storage에 남겨 다음 turn에서 reuse할 수 있게 한다.  
([after_run semantics](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L1600-L1618))

### .NET
.NET은 `ProviderSessionState<State>` 아래에 `List<CompactionMessageGroup> MessageGroups`를 저장한다. 이 persistent index는 다음 invocation에서 `Update(...)` 기반 incremental compaction을 가능하게 한다.  
([provider state](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionProvider.cs#L139-L145), [persist updated groups](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionProvider.cs#L179-L181), [state class](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionProvider.cs#L210-L220))

또한 default state key는 strategy type name이다. equivalent provider instances across sessions라도 key가 안정적이어야 한다는 테스트가 있다.  
([default state key](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionProvider.cs#L67-L76), [state key tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Compaction/CompactionProviderTests.cs#L23-L56))

---

## 11. 확장점

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

## 12. 동시성·스트리밍·취소

Compaction 전략 자체는 대부분 synchronous-style mutation이지만, summarization / provider integration 때문에 비동기와 cancellation semantics가 중요하다.

### Python
- strategies are `async def __call__(messages) -> bool`
- summarization은 async client call을 수행한다.
- repeated failure counter는 instance-local state라 strategy instance 공유 시 behavior도 공유된다.
- streaming-specific compaction strategy는 따로 없고, upper-layer agent/client가 streaming을 처리한다.  
  ([CompactionStrategy protocol](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L61-L73), [SummarizationStrategy async path](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L1348-L1372), [failure counter fields](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L1269-L1288))

### .NET
- `CompactAsync(..., CancellationToken)`가 모든 strategy/provider surface에 존재한다.
- `SummarizationCompactionStrategy`는 `OperationCanceledException`을 catch하지 않고 propagate한다.
- `ChatReducerCompactionStrategy`와 `AsChatReducer()`는 reducer/strategy에 cancellation token을 그대로 전달한다.  
  ([base CompactAsync signature](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionStrategy.cs#L96-L105), [summarization cancellation behavior](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/SummarizationCompactionStrategy.cs#L189-L208), [summarization cancel tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Compaction/SummarizationCompactionStrategyTests.cs#L521-L573), [chat reducer token pass-through test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Compaction/ChatReducerCompactionStrategyTests.cs#L205-L220), [AsChatReducer token pass-through test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Compaction/ChatStrategyExtensionsTests.cs#L83-L105))

---

## 13. 오류·검증·복구 동작

## 13.1 Python

### 값 검증
- `TruncationStrategy`: `max_n > 0`, `compact_to > 0`, `compact_to <= max_n`
- `SlidingWindowStrategy`: `keep_last_groups > 0`
- `SelectiveToolCallCompactionStrategy`: `keep_last_tool_call_groups >= 0`
- `ToolResultCompactionStrategy`: `keep_last_tool_call_groups >= 0`
- `SummarizationStrategy`: `target_count > 0`, `threshold >= 0`, `max_summary_input_tokens > 0`
- `ContextWindowCompactionStrategy`: positive context, valid thresholds, truncation threshold >= tool eviction threshold

([Python validations](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L828-L833), [sliding validation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L890-L893), [selective validation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L938-L940), [tool-result validation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L999-L1001), [summarization validation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L1252-L1263), [context-window validation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L1684-L1693))

### 실패 복구
- summarizer exception → warning + `False` + originals 유지
- empty summary → warning + `False`
- no group fits input budget → warning + `False`
- repeated summary failure ≥ 3 → one-time error log

([failure logic](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L1348-L1372), [no-fit budget warning](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L1340-L1346), [failure escalation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L1273-L1288), [tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_compaction.py#L975-L1067))

## 13.2 .NET

### 값 검증
- `ContextWindowCompactionStrategy`는 context/output/threshold 범위를 엄격히 검증한다.
- base `CompactionStrategy`는 null trigger를 거부한다.
- provider는 null strategy를 거부한다.

([ContextWindow validation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/ContextWindowCompactionStrategy.cs#L66-L90), [base ctor](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionStrategy.cs#L64-L68), [provider ctor](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionProvider.cs#L53-L69), [provider null test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Compaction/CompactionProviderTests.cs#L17-L21))

### 실패 복구
- summarizer exception → excluded groups 복구, summary group 미삽입, `false` 반환
- cancellation → 전파
- reducer가 message count를 줄이지 못하면 compaction 없음으로 본다
- provider는 `no session`, `no messages`, `remote service-managed session`이면 조용히 skip한다

([summarization restore](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/SummarizationCompactionStrategy.cs#L189-L208), [summarization tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Compaction/SummarizationCompactionStrategyTests.cs#L417-L612), [chat reducer no-op rule](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/ChatReducerCompactionStrategy.cs#L74-L80), [provider skip conditions](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionProvider.cs#L123-L135))

---

## 14. 보안

compaction 자체는 privilege-escalation 기능은 아니지만, **summary 또는 reducer가 새 assistant-like content를 history에 영구 삽입**할 수 있다는 점에서 간접 prompt injection boundary다.

### Python
Python `SummarizationStrategy`는 summarization client를 explicit opt-in으로 받고, untrusted summarizer가 악성 요약을 영속 state로 넣을 수 있다고 경고한다.  
([security note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L1207-L1216), [ctor security note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L1231-L1236))

### .NET
.NET은 두 군데서 같은 risk를 명시한다.

- `SummarizationCompactionStrategy`
- `ChatReducerCompactionStrategy` (external / LLM-backed reducer인 경우)

([summarization security note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/SummarizationCompactionStrategy.cs#L36-L45), [chat reducer security note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/ChatReducerCompactionStrategy.cs#L35-L42), [provider security note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionProvider.cs#L35-L44))

### 추가 correctness 보안
tool-call/result atomicity는 단순 최적화가 아니라 API correctness/security invariant다. partial deletion은 downstream LLM API error를 만든다.  
([Python atomic grouping tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_compaction.py#L159-L243), [.NET ToolCall atomicity docs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionGroupKind.cs#L34-L41))

---

## 15. .NET 구현과 테스트

### 구현 요약
- `CompactionMessageIndex`가 grouping/metrics/incremental update를 맡는다.
- `CompactionStrategy` base가 trigger/target/telemetry/logging을 맡는다.
- concrete strategies는 index를 mutate한다.
- `CompactionProvider`는 pre-invocation provider + incremental persisted index adapter다.
- `AsChatReducer()`는 same strategy를 chat reducer world에 연결한다.  
([index](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionMessageIndex.cs#L16-L26), [strategy base](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionStrategy.cs#L15-L18), [provider](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionProvider.cs#L19-L47), [AsChatReducer](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/ChatStrategyExtensions.cs#L19-L57))

### 테스트 커버리지 포인트
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

## 16. Python 구현과 테스트

### 구현 요약
- compaction은 message annotations를 기반으로 작동한다.
- `annotate_message_groups(...)`와 `annotate_token_counts(...)`가 토대다.
- 전략은 message list를 직접 mutate하며, provider는 context-loaded messages와 stored history를 각각 before/after에 compact한다.
- `TokenBudgetComposedStrategy`와 `ContextWindowCompactionStrategy`가 token-budget orchestration을 맡는다.  
([annotation core](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L543-L674), [strategies](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L797-L1732), [provider](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L1494-L1618))

### 테스트 커버리지 포인트
- atomic grouping / reasoning / reused call-id / nonadjacent result pairing  
  ([tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_compaction.py#L159-L243), [reused/ambiguous id tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_compaction.py#L294-L377))
- incremental annotation correctness and unique ids  
  ([tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_compaction.py#L435-L615))
- strategy family  
  ([truncation/selective tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_compaction.py#L619-L761), [summarization tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_compaction.py#L848-L1067), [token-budget/apply_compaction tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_compaction.py#L1070-L1149), [tool-result tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_compaction.py#L1155-L1540))
- provider integration / history loading behavior / context-window thresholds  
  ([provider tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_compaction.py#L1542-L1770), [context-window tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_compaction.py#L1775-L1941))

---

## 17. 문서 차이와 SDK 간 차이

## 17.1 성숙도 차이
- Python: top-level stable-looking public utility
- .NET: compaction package 전반이 experimental  
  ([Python exports](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/__init__.py#L58-L86), [.NET experimental markers](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionStrategy.cs#L49-L50))

## 17.2 같은 이름, 다른 semantics
- `SlidingWindow`: Python은 group-based, .NET은 turn-based  
  ([Python](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L870-L913), [.NET](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/SlidingWindowCompactionStrategy.cs#L14-L32))
- `ToolResultCompaction`: Python은 one-line bracket summary, .NET은 YAML-like block  
  ([Python](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L1085-L1101), [.NET](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/ToolResultCompactionStrategy.cs#L237-L254))
- selective policy: Python에는 explicit strategy가 있고, inspected .NET set에는 direct analog가 없다  
  ([Python selective strategy](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L916-L966), [.NET inspected strategy set](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/ToolResultCompactionStrategy.cs#L14-L49))

## 17.3 provider integration 차이
- Python provider는 before+after 양쪽 지원
- .NET provider는 pre-invocation provider + persisted incremental index + chat reducer bridge  
  ([Python provider](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L1494-L1618), [.NET provider](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionProvider.cs#L19-L47), [.NET reducer bridge](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/ChatStrategyExtensions.cs#L19-L57))

## 17.4 문서와 코드의 밀도 차이
- .NET은 source XML doc가 설계를 상세히 설명한다.
- Python은 전략 의미와 edge case의 많은 부분이 test file과 inline comments에 더 많이 실려 있다.  
  ([.NET XML-heavy strategy docs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionStrategy.cs#L15-L47), [Python edge-case tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_compaction.py#L159-L1941))

---

## 18. Java 결정

### 18.1 포함할 것
Java compaction MVP/후속 설계에서는 다음을 포함하는 것이 좋다.

1. **명시적 grouping/index abstraction**  
   - Python의 annotation richness와 .NET의 index simplicity를 절충해, 내부적으로는 index를 두되 summary trace metadata는 메시지에도 남기는 방식이 좋다.  
   ([Python trace-rich annotations](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L35-L37), [.NET index abstraction](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionMessageIndex.cs#L16-L26))
2. **trigger/target abstraction**  
   - .NET 모델이 composition과 stopping semantics를 더 명확히 드러낸다.  
   ([.NET trigger/target model](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionStrategy.cs#L25-L35))
3. **explicit SelectiveToolCall strategy**  
   - Python이 가진 명시적 selective policy는 Java에도 유용하다. .NET에는 inspected set상 direct analog가 없다.  
   ([Python selective strategy](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L916-L966))
4. **Context-window convenience strategy**
   - input budget 계산과 multi-phase orchestration은 그대로 필요하다.  
   ([Python context-window](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L1620-L1732), [.NET context-window](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/ContextWindowCompactionStrategy.cs#L13-L148))

### 18.2 초기에는 제외/보류할 것
1. **LLM summarization strategy를 stable로 약속하지 말 것**
   - persistent indirect prompt injection risk가 있다. first release는 experimental로 두는 것이 적절하다.  
   ([Python security note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L1207-L1216), [.NET security note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/SummarizationCompactionStrategy.cs#L36-L45))
2. **remote-threaded session special case**
   - .NET처럼 remote conversation id session에서는 provider skip semantics가 필요할 수 있지만, Java session model이 정해진 뒤 넣는 편이 낫다.  
   ([.NET remote skip](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/CompactionProvider.cs#L129-L135))
3. **chat reducer bridge**
   - Java에 동일한 reducer abstraction이 없다면 first release에서는 생략 가능하다.  
   ([.NET reducer bridge](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/ChatStrategyExtensions.cs#L19-L57))

### 18.3 권장 기본 선택
- grouping semantics: **tool-call/result atomicity 필수**
- sliding semantics: **turn-based와 group-based를 분리된 전략 두 개로 제공**
- token estimator default: **4 chars/token heuristic + non-ASCII inflation 방지**
- summarization failure semantics: **restore originals + return false**
- provider integration: **before-run projection + optional after-store compaction hook**  
  ([Python non-ASCII serialization rule](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L643-L645), [Python summarization failure semantics](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_compaction.py#L1348-L1372), [.NET summarization restore semantics](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Compaction/SummarizationCompactionStrategy.cs#L196-L208))

---

## 19. Acceptance scenarios

1. **tool-call/result atomicity**
   - non-adjacent approval gap이 있어도 unambiguous call id면 tool result는 declaration group과 같은 group으로 묶여야 한다.  
   ([Python test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_compaction.py#L209-L243))
   - .NET도 assistant tool call + tool result는 하나의 `ToolCall` group이어야 한다.  
   ([.NET test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Compaction/CompactionMessageIndexTests.cs#L83-L101))

2. **incremental update correctness**
   - 새 메시지 tail만 append annotation/update 해도 기존 prefix id와 token count가 깨지면 안 된다.  
   ([Python incremental id tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_compaction.py#L561-L615), [.NET incremental update test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Compaction/CompactionMessageIndexTests.cs#L1451-L1477))

3. **system anchor preservation**
   - truncation/sliding 정책은 system anchor를 보존해야 한다.  
   ([Python truncation system anchor](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_compaction.py#L619-L635), [.NET truncation preserves system](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Compaction/TruncationCompactionStrategyTests.cs#L87-L112), [.NET sliding preserves system](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Compaction/SlidingWindowCompactionStrategyTests.cs#L65-L87))

4. **tool-result summary safety**
   - large tool result는 summary에 verbatim 전체가 들어가면 안 되고 truncation marker가 있어야 한다.  
   ([Python test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_compaction.py#L1290-L1316))

5. **summarization failure restore**
   - summarizer exception 시 originals가 모두 복구되고 summary group은 생기지 않아야 한다.  
   ([Python failure test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_compaction.py#L975-L995), [.NET failure restore tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Compaction/SummarizationCompactionStrategyTests.cs#L420-L519))

6. **cancellation semantics**
   - .NET summarization compaction은 `OperationCanceledException`을 swallow하면 안 된다.  
   ([.NET cancel test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Compaction/SummarizationCompactionStrategyTests.cs#L521-L573))

7. **history load behavior**
   - Python에서 excluded messages를 storage에 남겨도 `skip_excluded=True`면 next load에서 빠져야 한다.  
   ([test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_compaction.py#L1734-L1752))

8. **context-window phase separation**
   - 50% 초과에서는 tool-result eviction만, 80% 초과에서는 truncation까지 발생해야 한다.  
   ([Python tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_compaction.py#L1796-L1868), [.NET tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/Compaction/ContextWindowCompactionStrategyTests.cs#L122-L191))

9. **non-ASCII token accounting**
   - Python serialization은 CJK 문자열을 `\uXXXX` escape보다 실제 문자 기준으로 더 낮게 계산해야 한다.  
   ([test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_compaction.py#L1928-L1941))

10. **remote service session skip (.NET)**
    - remote conversation id가 붙은 session에서는 provider가 compaction을 적용하지 않고 passthrough해야 한다.  
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