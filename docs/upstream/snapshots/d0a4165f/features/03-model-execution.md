# 03-model-execution

이 문서는 Agent core의 **model client connection**, **capability contract**, **request options**, **sync/async 실행 경로**, **streaming/continuation**, **cancellation**, **response assembly**만 다룬다. .NET 쪽은 `IChatClient`를 감싸는 `ChatClientAgent`와 그 옵션/데코레이터 체인이 중심이고, Python 쪽은 `SupportsChatGetResponse` protocol, `BaseChatClient`, 그리고 이를 agent와 연결하는 `RawAgent`/`Agent` 경로가 중심이다. [ChatClientAgent.cs#L18-L19](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientAgent.cs#L18-L19) [ChatClientExtensions.cs#L18-L50](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientExtensions.cs#L18-L50) [_clients.py#L84-L200](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_clients.py#L84-L200) [_clients.py#L217-L340](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_clients.py#L217-L340)

## 목적

이 기능의 목적은 “모델 호출”을 agent core가 일관되게 다룰 수 있도록 **provider-neutral execution seam**을 제공하는 것이다. .NET은 `ChatClientAgent`가 `IChatClient` 호출을 lifecycle/session/provider 통합 지점으로 만들고, Python은 `SupportsChatGetResponse.get_response()`를 모든 chat model client의 공통 contract로 둔 뒤 `BaseChatClient`가 option validation, compaction, stream finalization을 담당한다. [ChatClientAgent.cs#L39-L40](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientAgent.cs#L39-L40) [_clients.py#L85-L200](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_clients.py#L85-L200) [_clients.py#L443-L558](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_clients.py#L443-L558)

## 경계

이 문서는 다음만 포함한다.

- model client와 agent의 연결 방식
- provider-neutral capability contract
- run/request options의 병합과 검증
- non-streaming / streaming model call path
- continuation token과 background/stream resumption
- cancellation surface
- partial updates에서 final response를 조립하는 방식 [ChatClientAgent.cs#L205-L264](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientAgent.cs#L205-L264) [ChatClientAgent.cs#L291-L401](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientAgent.cs#L291-L401) [_clients.py#L342-L364](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_clients.py#L342-L364) [_clients.py#L482-L558](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_clients.py#L482-L558)

다음은 의도적으로 제외한다.

- message/content taxonomy 자체
- tool declaration 및 tool loop 상세 알고리즘
- session/history/context provider 세부 구현
- workflow, hosting/protocol, provider-specific SDK internals [AgentResponse.cs#L14-L27](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Abstractions/AgentResponse.cs#L14-L27) [_types.py#L2214-L2465](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_types.py#L2214-L2465)

## 성숙도

- .NET의 `ChatClientAgent`, `ChatClientAgentOptions`, `ChatClientAgentRunOptions`, `AsAIAgent()`/`BuildAIAgent()`는 일반 공개 surface다. 실험 표시는 특정 우회 옵션(`EnableInvocableFunctionBypassing`)이나 continuation 관련 필드에만 붙는다. [ChatClientAgent.cs#L39-L40](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientAgent.cs#L39-L40) [ChatClientAgentOptions.cs#L18-L21](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientAgentOptions.cs#L18-L21) [ChatClientAgentOptions.cs#L243-L276](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientAgentOptions.cs#L243-L276) [ChatClientAgentRunOptions.cs#L15-L67](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientAgentRunOptions.cs#L15-L67)
- Python의 `SupportsChatGetResponse`, `BaseChatClient`, `as_agent()`, capability protocol들(`SupportsCodeInterpreterTool`, `SupportsWebSearchTool`, `SupportsImageGenerationTool`, `SupportsMCPTool`, `SupportsFileSearchTool`, `SupportsShellTool`)은 모두 일반 공개 surface다. [__init__.py#L46-L56](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/__init__.py#L46-L56) [_clients.py#L85-L200](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_clients.py#L85-L200) [_clients.py#L667-L846](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_clients.py#L667-L846)

## 공개 API·타입

### .NET

- model execution의 핵심 public type은 `ChatClientAgent`, `ChatClientAgentOptions`, `ChatClientAgentRunOptions`와 base `AgentRunOptions`다. `ChatClientAgent`는 `IChatClient`를 받아 agent facade를 만들고, `ChatClientAgentRunOptions`는 per-run `ChatOptions`와 `ChatClientFactory`를 추가한다. [ChatClientAgent.cs#L49-L118](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientAgent.cs#L49-L118) [ChatClientAgentRunOptions.cs#L9-L67](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientAgentRunOptions.cs#L9-L67) [AgentRunOptions.cs#L11-L128](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Abstractions/AgentRunOptions.cs#L11-L128)
- `ChatClientAgentOptions`는 default `ChatOptions`, `ChatHistoryProvider`, `AIContextProviders`, custom stack opt-out, per-service-call persistence, message injection, approval-related decorators 등 client stack 구성과 실행 모드를 함께 담는다. [ChatClientAgentOptions.cs#L18-L241](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientAgentOptions.cs#L18-L241)

### Python

- `SupportsChatGetResponse`는 모든 chat model client가 따라야 할 structural contract다. non-streaming이면 awaitable `ChatResponse`, streaming이면 `ResponseStream[ChatResponseUpdate, ChatResponse]`를 반환한다. [ _clients.py#L85-L200](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_clients.py#L85-L200)
- `BaseChatClient`는 이 contract의 공통 구현 골격으로, `compaction_strategy`, `tokenizer`, `additional_properties`, `STORES_BY_DEFAULT`, `get_response()`, `service_url()`, `as_agent()`를 제공한다. [_clients.py#L217-L340](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_clients.py#L217-L340) [_clients.py#L443-L658](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_clients.py#L443-L658)
- capability protocol들은 provider-hosted tool support를 런타임 체크 가능하게 드러낸다. `isinstance(client, SupportsCodeInterpreterTool)` 같은 식으로 provider 기능을 탐색한 뒤 agent에 tool config를 연결하는 방식이다. [_clients.py#L667-L846](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_clients.py#L667-L846)

## Model client connection / capability contracts

### .NET

- .NET pinned source 범위에는 Python식 “supports X” protocol 집합이 없다. capability는 `IChatClient`의 decoration stack과 `GetService()` discovery로 드러난다. 예를 들어 `ChatClientAgent`는 생성 시 `ChatClientMetadata`를 읽어 provider metadata를 만들고, stack 내부에 `FunctionInvokingChatClient`가 있는지 보고 function invocation middleware 지원 여부를 판정한다. [ChatClientAgent.cs#L122-L131](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientAgent.cs#L122-L131) [FunctionInvocationDelegatingAgentBuilderExtensions.cs#L33-L49](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/FunctionInvocationDelegatingAgentBuilderExtensions.cs#L33-L49)
- 기본 연결 경로는 `IChatClient.AsAIAgent()` 또는 `ChatClientBuilder.BuildAIAgent()`다. 둘 다 최종적으로 `ChatClientAgent`를 만든다. [ChatClientExtensions.cs#L18-L50](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientExtensions.cs#L18-L50) [ChatClientBuilderExtensions.cs#L15-L87](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientBuilderExtensions.cs#L15-L87)
- `UseProvidedChatClientAsIs=false`면 framework가 default decorator stack을 주입한다. 이 stack은 approval binding, approval-not-required bypass, optional invocable bypass, `FunctionInvokingChatClient`, optional message injection, optional per-service-call persistence, deferred OpenTelemetry slot을 구성한다. [ChatClientExtensions.cs#L52-L140](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientExtensions.cs#L52-L140)

### Python

- Python의 핵심 capability contract는 `SupportsChatGetResponse`. 이는 “sequence of `Message` in, complete `ChatResponse` or streaming `ResponseStream` out”을 structural protocol로 표현한다. [_clients.py#L85-L200](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_clients.py#L85-L200)
- provider-hosted 특수 기능은 별도 capability protocol로 분리되어 있다. 예를 들어 code interpreter, web search, image generation, MCP, file search, shell 지원 여부는 `Supports*Tool` protocol들로 노출된다. [_clients.py#L667-L846](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_clients.py#L667-L846)
- client를 agent로 연결하는 공식 seam은 `BaseChatClient.as_agent()`다. 이 API는 client 자신을 agent constructor에 넣고, agent-level defaults와 additional properties를 함께 싣는다. [_clients.py#L571-L658](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_clients.py#L571-L658)

### 차이 요약

- .NET은 capability를 **decorator presence + service discovery**로 드러내고, Python은 **protocol type**으로 드러낸다. [ChatClientExtensions.cs#L93-L100](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientExtensions.cs#L93-L100) [_clients.py#L85-L200](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_clients.py#L85-L200) [_clients.py#L667-L846](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_clients.py#L667-L846)

## Request Options

### .NET

- base `AgentRunOptions`는 `ContinuationToken`, `AllowBackgroundResponses`, `AdditionalProperties`, `ResponseFormat`을 제공한다. continuation과 background는 optional이며, unsupported implementation은 이를 무시할 수 있다. [AgentRunOptions.cs#L19-L127](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Abstractions/AgentRunOptions.cs#L19-L127)
- `ChatClientAgentRunOptions`는 여기서 더 나아가 per-run `ChatOptions`와 `ChatClientFactory`를 제공한다. [ChatClientAgentRunOptions.cs#L15-L67](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientAgentRunOptions.cs#L15-L67)
- `ChatClientAgent.CreateConfiguredChatOptions()`는 agent-level default `ChatOptions`와 request-level `ChatOptions`를 병합한다. scalar류는 request 우선, instructions는 newline concat, additional properties는 request 우선 덮어쓰기, stop sequences와 tools는 concat, `ResponseFormat`/`AllowBackgroundResponses`/`ContinuationToken`/`AdditionalProperties`는 `AgentRunOptions`에서 마지막 override를 적용한다. [ChatClientAgent.cs#L541-L688](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientAgent.cs#L541-L688)

### Python

- Python `ChatOptions`는 provider-neutral common denominator로 `model`, `temperature`, `top_p`, `max_tokens`, `stop`, `seed`, `logit_bias`, `frequency_penalty`, `presence_penalty`, `tools`, `tool_choice`, `allow_multiple_tool_calls`, `response_format`, `metadata`, `user`, `store`, `conversation_id`, `instructions`를 정의한다. [_types.py#L3660-L3727](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_types.py#L3660-L3727)
- `validate_chat_options()`는 numeric constraints를 검증하고 tool collection을 normalize한다. frequency/presence penalty는 `[-2.0, 2.0]`, temperature는 `[0.0, 2.0]`, `top_p`는 `[0.0, 1.0]`, `max_tokens`는 양수여야 한다. [_types.py#L3733-L3787](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_types.py#L3733-L3787)
- `BaseChatClient.get_response()`는 per-call `compaction_strategy`, `tokenizer`, `function_invocation_kwargs`, `client_kwargs`를 추가로 받는다. [_clients.py#L443-L558](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_clients.py#L443-L558)

## 상세 실행 흐름

### .NET non-streaming 경로

1. `ChatClientAgent.RunCoreAsync()`는 입력 메시지를 materialize한다. [ChatClientAgent.cs#L205-L212](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientAgent.cs#L205-L212)  
2. `PrepareSessionAndMessagesAsync()`가 session/chat options/messages/continuation token을 준비한다. 여기서 background/continuation validation, session compatibility, conversation id conflict 검사, history/context provider merge가 모두 일어난다. [ChatClientAgent.cs#L699-L807](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientAgent.cs#L699-L807)  
3. `ApplyRunOptionsTransformations()`가 per-run `ChatClientFactory`가 있으면 원래 client를 변환한다. [ChatClientAgent.cs#L266-L288](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientAgent.cs#L266-L288)  
4. 실제 model call은 `chatClient.GetResponseAsync(...)`로 내려간다. 실패 시 provider failure notification 후 예외를 다시 던진다. 성공 시 response의 conversation id를 session에 반영하고, response messages의 `AuthorName`을 채우고, provider success notification 후 `AgentResponse`로 래핑한다. [ChatClientAgent.cs#L223-L264](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientAgent.cs#L223-L264)

### .NET streaming 경로

1. streaming도 동일하게 `PrepareSessionAndMessagesAsync()`와 `ApplyRunOptionsTransformations()`를 거친다. [ChatClientAgent.cs#L291-L317](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientAgent.cs#L291-L317)  
2. `GetStreamingResponseAsync(...)`의 enumerator를 직접 잡고, 첫 `MoveNextAsync()`부터 try/catch로 감싸 예외 시 provider failure notification을 보낸다. [ChatClientAgent.cs#L321-L352](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientAgent.cs#L321-L352)  
3. 각 update마다 `AuthorName`을 보정하고, local `responseUpdates` 버퍼에 저장하고, host에는 wrapped continuation token이 붙은 `AgentResponseUpdate`를 `yield`한다. [ChatClientAgent.cs#L354-L367](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientAgent.cs#L354-L367)  
4. stream 종료 후 buffered `ChatResponseUpdate` 목록을 `ToChatResponse()`로 완성 response로 바꾸고, conversation id와 provider notifications를 마무리한다. [ChatClientAgent.cs#L385-L401](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientAgent.cs#L385-L401)

### Python direct client 경로

1. `BaseChatClient.get_response()`는 먼저 compaction/tokenizer override 유무를 본다. override가 없으면 `_inner_get_response()`로 직접 내려가고, 있으면 `_prepare_messages_for_model_call()` 후 호출한다. [_clients.py#L510-L558](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_clients.py#L510-L558)
2. streaming이면 `_inner_get_response(..., stream=True)`의 결과를 `ResponseStream.from_awaitable()`로 감싸고, non-streaming이면 coroutine을 그대로 돌려준다. [_clients.py#L524-L558](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_clients.py#L524-L558)
3. provider subclass는 `_inner_get_response(messages, stream, options, **kwargs)`를 구현해야 하며, 시작 시 `_validate_options()`를 호출하는 것이 기대된다. [_clients.py#L415-L439](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_clients.py#L415-L439) [_clients.py#L329-L340](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_clients.py#L329-L340)

### Python agent→client handoff 경로

- `RawAgent._call_chat_client()`는 이미 준비된 `session_messages`, merged `chat_options`, compaction/tokenizer override, `function_invocation_kwargs`, `client_kwargs`를 그대로 client contract에 전달한다. 즉 agent execution과 direct client execution의 마지막 seam은 `SupportsChatGetResponse.get_response()` 하나다. [_agents.py#L1119-L1161](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_agents.py#L1119-L1161)

## 상태·영속화

- .NET model execution은 `ConversationId`가 있으면 service-managed history를 사용한다고 보고, local `ChatHistoryProvider`와 충돌시키지 않으려 한다. `ResolveChatHistoryProvider()`는 `chatOptions.ConversationId` 또는 `session.ConversationId`가 있으면 local provider를 disengage한다. [ChatClientAgent.cs#L983-L1029](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientAgent.cs#L983-L1029)
- .NET background/continuation resumption에서는 `ChatClientAgentContinuationToken`이 inner provider continuation token 외에도 이전 input messages와 이미 수신한 updates를 보관해, 재개 시 session/history/provider에 충분한 context를 복원할 수 있게 한다. [ChatClientAgent.cs#L1054-L1091](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientAgent.cs#L1054-L1091)
- Python에서는 client-level capability `STORES_BY_DEFAULT`가 model execution의 storage ownership 힌트다. `True`면 agent는 local `InMemoryHistoryProvider`를 자동 주입하지 않고, `store=False`가 명시될 때만 local history path로 전환한다. [_clients.py#L279-L286](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_clients.py#L279-L286) [_agents.py#L1341-L1368](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_agents.py#L1341-L1368)

## 확장점

- .NET의 가장 직접적인 per-run 확장점은 `ChatClientAgentRunOptions.ChatClientFactory`다. 이는 agent의 configured client를 인수로 받아 run마다 다른 decorator/stack을 만들 수 있다. [ChatClientAgentRunOptions.cs#L55-L67](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientAgentRunOptions.cs#L55-L67)
- .NET의 custom client stack 사용자는 `UseProvidedChatClientAsIs=true`로 framework default decorators를 건너뛸 수 있고, 대신 `ChatClientBuilderExtensions`의 개별 extension method로 원하는 stack을 수동 조립할 수 있다. [ChatClientAgentOptions.cs#L50-L63](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientAgentOptions.cs#L50-L63) [ChatClientBuilderExtensions.cs#L89-L271](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientBuilderExtensions.cs#L89-L271)
- Python은 `BaseChatClient.as_agent()`로 client를 agent에 연결하고, provider subclasses는 `_inner_get_response()`만 구현하면 compaction/stream finalization/serialization contract를 재사용할 수 있다. [_clients.py#L415-L439](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_clients.py#L415-L439) [_clients.py#L571-L658](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_clients.py#L571-L658)

## 동시성·스트리밍·취소

- .NET은 agent-level과 chat-client-level 호출 모두 explicit `CancellationToken`을 사용한다. `IChatClient.GetResponseAsync()`/`GetStreamingResponseAsync()`에 같은 token이 전달된다. [ChatClientAgent.cs#L231-L238](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientAgent.cs#L231-L238) [ChatClientAgent.cs#L321-L352](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientAgent.cs#L321-L352)
- .NET streaming path는 enumerator를 반드시 `DisposeAsync()`하므로, caller가 중간에 break하더라도 downstream decorator의 finally block이 실행된다. [ChatClientAgent.cs#L334-L401](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientAgent.cs#L334-L401)
- Python contract에는 explicit cancellation 파라미터가 없다. cancellation은 awaitable task 또는 `ResponseStream` iteration을 감싼 asyncio 취소 메커니즘에 맡겨진다. [_clients.py#L171-L200](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_clients.py#L171-L200) [_types.py#L3061-L3371](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_types.py#L3061-L3371)
- Python `ResponseStream`는 pull context manager hook를 제공해, 각 underlying iterator pull 동안 telemetry/context state를 유지할 수 있게 한다. [_types.py#L3507-L3515](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_types.py#L3507-L3515)

## Response Assembly

### .NET

- non-streaming assembly는 underlying `ChatResponse`를 `new AgentResponse(chatResponse)`로 감싸는 얇은 래퍼다. `ChatResponse`가 이미 complete object이므로 extra assembly 로직이 거의 없다. [AgentResponse.cs#L48-L70](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Abstractions/AgentResponse.cs#L48-L70) [ChatClientAgent.cs#L259-L264](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientAgent.cs#L259-L264)
- streaming assembly는 buffered `ChatResponseUpdate` 목록을 final `ChatResponse`로 변환한 뒤 wrapper `AgentResponse`로 승격하는 두 단계다. host-facing `AgentResponseUpdate`는 각각 provider update를 그대로 감싸지만, 최종 응답은 stream end에서 한 번 더 조립된다. [ChatClientAgent.cs#L317-L401](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientAgent.cs#L317-L401) [AgentResponseUpdate.cs#L63-L79](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Abstractions/AgentResponseUpdate.cs#L63-L79)

### Python

- `BaseChatClient._build_response_stream()`는 stream source와 finalizer를 묶어 `ResponseStream`를 만든다. 기본 finalizer는 `_finalize_response_updates()`이며, 이는 다시 `ChatResponse.from_updates()`를 호출한다. [_clients.py#L342-L364](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_clients.py#L342-L364)
- `ChatResponse.from_updates()`는 `_process_update()`와 `_finalize_response()`를 통해 final response를 조립한다. 따라서 provider가 complete response를 바로 주지 않아도 update sequence만 있으면 core가 최종 response를 재구성할 수 있다. [_types.py#L2370-L2405](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_types.py#L2370-L2405) [_types.py#L2140-L2146](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_types.py#L2140-L2146)
- agent layer에서는 `_build_agent_response_from_chat_response()`가 `ChatResponse`를 `AgentResponse`로 변환하고 structured-output parse state를 유지한다. [_types.py#L2883-L2904](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_types.py#L2883-L2904)

## 오류·검증·보안

- .NET `PrepareSessionAndMessagesAsync()`는 background responses 사용 시 session이 없으면 예외를 던지고, continuation token이 있을 때 input messages가 함께 오면 예외를 던지며, session conversation id와 options conversation id가 서로 다르면 예외를 던진다. [ChatClientAgent.cs#L713-L747](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientAgent.cs#L713-L747)
- .NET `ChatClientFactory`가 null을 반환하면 `Throw.IfNull(chatClient)`에 걸려 실패한다. [ChatClientAgent.cs#L278-L285](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientAgent.cs#L278-L285)
- Python `validate_chat_options()`는 option-level numeric validation을 수행하고, unsupported field는 provider implementation에서 거부할 수 있게 남겨둔다. [_types.py#L3733-L3787](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_types.py#L3733-L3787)
- .NET `ChatClientAgent` 문서는 model service가 외부 endpoint이고, LLM output과 tool arguments는 untrusted input이므로 validation/sanitization이 필요하다고 경고한다. 이는 execution seam 자체가 trust boundary라는 뜻이다. [ChatClientAgent.cs#L20-L37](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientAgent.cs#L20-L37)

## .NET 구현과 테스트

- `ChatClientAgentRunOptionsTests`는 `ChatClientFactory`가 non-streaming과 streaming 양쪽에서 실제로 호출되어 original client 대신 transformed client가 사용됨을 검증한다. [ChatClientAgentRunOptionsTests.cs#L51-L90](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/ChatClient/ChatClientAgentRunOptionsTests.cs#L51-L90) [ChatClientAgentRunOptionsTests.cs#L104-L145](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/ChatClient/ChatClientAgentRunOptionsTests.cs#L104-L145)
- `ChatClientAgent_BackgroundResponsesTests`는 `AllowBackgroundResponses`와 `ContinuationToken`이 `AgentRunOptions` 또는 `ChatClientAgentRunOptions.ChatOptions` 어느 쪽에서 들어오든 최종 `ChatOptions`에 반영됨을 검증한다. [ChatClientAgent_BackgroundResponsesTests.cs#L34-L67](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/ChatClient/ChatClientAgent_BackgroundResponsesTests.cs#L34-L67) [ChatClientAgent_BackgroundResponsesTests.cs#L120-L150](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/ChatClient/ChatClientAgent_BackgroundResponsesTests.cs#L120-L150)

## Python 구현과 테스트

- `test_clients.py`는 `SupportsChatGetResponse` 구현이 non-streaming에서 `ChatResponse`, streaming에서 update sequence를 정상 제공한다는 최소 contract를 검증한다. [test_clients.py#L37-L47](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_clients.py#L37-L47)
- 같은 파일은 `BaseChatClient.as_agent()`가 `additional_properties`를 agent로 전달하지만, `function_invocation_configuration` 같은 client-only execution config를 받지 않는다는 점도 검증한다. 즉 execution stack의 seam이 명확히 나뉘어 있다. [test_clients.py#L59-L72](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_clients.py#L59-L72)
- `test_agents.py`의 `STORES_BY_DEFAULT` 테스트들은 model client가 server-side storage를 기본 지원하는지 여부가 agent-side injection 행동을 바꾼다는 것을 검증한다. [test_agents.py#L2839-L2918](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_agents.py#L2839-L2918)

## 문서 차이

- Python README는 direct client와 agent 사용 예제를 제공하지만, `BaseChatClient.get_response()`의 compaction override, `STORES_BY_DEFAULT`, capability protocol 집합, `ResponseStream.from_awaitable()` 같은 execution seam 세부 사항은 다루지 않는다. 코드가 문서보다 훨씬 정교한 execution contract를 가진다. [README.md#L94-L121](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/README.md#L94-L121) [_clients.py#L217-L340](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_clients.py#L217-L340) [_clients.py#L443-L558](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_clients.py#L443-L558)
- .NET 쪽은 public XML doc이 비교적 풍부하지만, pinned repo만으로는 `ChatMessage`/`ChatOptions`/`ChatResponse` 본체가 보이지 않는다. 따라서 실제 message/update payload 정의보다 **agent-core wrapping, option merge, stack composition**이 이 repo의 주 문서화 대상이다. [ChatClientAgent.cs#L49-L118](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientAgent.cs#L49-L118) [ChatClientAgent.cs#L541-L688](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientAgent.cs#L541-L688)

## Java 결정

- Java는 Python처럼 **명시적 client capability contract interface**를 먼저 세우는 것이 좋다. `ChatClient` 기본 contract와 `SupportsCodeInterpreter`, `SupportsWebSearch` 같은 세부 capability interface를 분리하면 compile-time discoverability가 높다. 이는 .NET의 decoration/service-discovery 방식보다 Java IDE와 타입 시스템에 더 잘 맞는다. [_clients.py#L85-L200](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_clients.py#L85-L200) [_clients.py#L667-L846](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_clients.py#L667-L846)
- 동시에 .NET의 `ChatClientFactory` 같은 per-run client transformation seam은 Java에도 매우 유용하다. 따라서 Java는 **typed capability interfaces + per-request client decorator factory**를 함께 두는 hybrid가 적절하다. [ChatClientAgentRunOptions.cs#L55-L67](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientAgentRunOptions.cs#L55-L67) [ChatClientAgentRunOptionsTests.cs#L51-L90](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/ChatClient/ChatClientAgentRunOptionsTests.cs#L51-L90)
- request options는 Python처럼 provider-neutral 공통 키를 1차 계약으로 두고, provider-specific options는 별도 typed extension object 또는 namespaced map으로 분리하는 편이 안전하다. .NET의 rich `ChatOptions` merge semantics와 Python의 `validate_chat_options()` 제약을 함께 반영하면, Java core도 option precedence와 validation 계층을 명확히 설계할 수 있다. [ChatClientAgent.cs#L541-L688](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientAgent.cs#L541-L688) [_types.py#L3660-L3787](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_types.py#L3660-L3787)
- cancellation은 .NET처럼 explicit token/handle을 public contract에 포함하는 편이 Java에 더 적합하다. Python식 implicit asyncio cancellation은 Java에서 표준적이지 않다. [ChatClientAgent.cs#L205-L401](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientAgent.cs#L205-L401) [_clients.py#L171-L200](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_clients.py#L171-L200)

## 구체 acceptance scenarios

1. **.NET per-run `ChatClientFactory`는 original client를 받아 transformed client를 사용해야 한다.** same run에서 original client가 아니라 transformed client만 호출되어야 한다. [ChatClientAgent.cs#L278-L285](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientAgent.cs#L278-L285) [ChatClientAgentRunOptionsTests.cs#L51-L90](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/ChatClient/ChatClientAgentRunOptionsTests.cs#L51-L90)

2. **.NET background options는 base `AgentRunOptions`와 `ChatClientAgentRunOptions.ChatOptions` 두 경로에서 들어와도 동일하게 final `ChatOptions`에 반영되어야 한다.** [ChatClientAgent.cs#L653-L687](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientAgent.cs#L653-L687) [ChatClientAgent_BackgroundResponsesTests.cs#L34-L67](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/ChatClient/ChatClientAgent_BackgroundResponsesTests.cs#L34-L67)

3. **.NET continuation resume에서는 input messages를 함께 줄 수 없어야 한다.** continuation token이 설정된 run에 새 input messages가 있으면 예외가 나야 한다. [ChatClientAgent.cs#L732-L736](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientAgent.cs#L732-L736)

4. **Python `SupportsChatGetResponse` 구현은 non-streaming과 streaming 두 shape를 모두 만족해야 한다.** non-streaming은 `ChatResponse`, streaming은 update iteration을 제공해야 한다. [test_clients.py#L37-L47](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_clients.py#L37-L47)

5. **Python `STORES_BY_DEFAULT=True` client는 local in-memory history를 자동 주입하지 않아야 하며, `store=False`가 들어오면 다시 local history path를 타야 한다.** [ _clients.py#L279-L286](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_clients.py#L279-L286) [test_agents.py#L2839-L2918](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_agents.py#L2839-L2918)

6. **Python response assembly는 streaming updates만으로 final `ChatResponse`를 재구성할 수 있어야 한다.** `ResponseStream.get_final_response()`는 buffered updates와 finalizer를 통해 final object를 돌려줘야 한다. [_clients.py#L342-L364](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_clients.py#L342-L364) [_types.py#L2370-L2405](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_types.py#L2370-L2405) [_types.py#L3373-L3479](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_types.py#L3373-L3479)

## Source Inventory

### .NET production source
- [`dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientAgent.cs`](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientAgent.cs)
- [`dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientAgentOptions.cs`](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientAgentOptions.cs)
- [`dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientAgentRunOptions.cs`](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientAgentRunOptions.cs)
- [`dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientExtensions.cs`](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientExtensions.cs)
- [`dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientBuilderExtensions.cs`](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientBuilderExtensions.cs)
- [`dotnet/src/Microsoft.Agents.AI.Abstractions/AgentRunOptions.cs`](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Abstractions/AgentRunOptions.cs)
- [`dotnet/src/Microsoft.Agents.AI.Abstractions/AgentResponse.cs`](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Abstractions/AgentResponse.cs)
- [`dotnet/src/Microsoft.Agents.AI.Abstractions/AgentResponseUpdate.cs`](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Abstractions/AgentResponseUpdate.cs)
- [`dotnet/src/Microsoft.Agents.AI/FunctionInvocationDelegatingAgentBuilderExtensions.cs`](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/FunctionInvocationDelegatingAgentBuilderExtensions.cs)

### .NET tests
- [`dotnet/tests/Microsoft.Agents.AI.UnitTests/ChatClient/ChatClientAgentRunOptionsTests.cs`](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/ChatClient/ChatClientAgentRunOptionsTests.cs)
- [`dotnet/tests/Microsoft.Agents.AI.UnitTests/ChatClient/ChatClientAgent_BackgroundResponsesTests.cs`](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/ChatClient/ChatClientAgent_BackgroundResponsesTests.cs)

### Python production source
- [`python/packages/core/agent_framework/__init__.py`](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/__init__.py)
- [`python/packages/core/agent_framework/_clients.py`](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_clients.py)
- [`python/packages/core/agent_framework/_agents.py`](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_agents.py)
- [`python/packages/core/agent_framework/_types.py`](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_types.py)

### Python tests
- [`python/packages/core/tests/core/test_clients.py`](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_clients.py)
- [`python/packages/core/tests/core/test_agents.py`](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_agents.py)

### reviewed docs
- [`python/packages/core/README.md`](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/README.md)