# 01-agent-lifecycle

이 문서는 Microsoft Agent Framework의 **Agent abstraction/lifecycle**, **identity/metadata**, **session 생성**, **run 진입점**, **delegation/wrapping 경계**만 다룬다. 범위 내에서 .NET은 `AIAgent`/`DelegatingAIAgent`/`ChatClientAgent`/`AIAgentBuilder` 축으로, Python은 `BaseAgent`/`RawAgent`/`Agent`/`SupportsAgentRun` 축으로 lifecycle을 구성한다. 두 구현 모두 “agent가 대화 실행의 최상위 추상화”라는 점은 같지만, .NET은 **async-only 메서드 + decorator chain**에 가깝고, Python은 **동기 형태의 public `run()`이 awaitable 또는 `ResponseStream`을 반환하는 layered class 구성**에 가깝다. [AIAgent.cs#L17-L38](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Abstractions/AIAgent.cs#L17-L38) [DelegatingAIAgent.cs#L13-L28](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Abstractions/DelegatingAIAgent.cs#L13-L28) [_agents.py#L364-L420](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_agents.py#L364-L420) [_agents.py#L1751-L1764](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_agents.py#L1751-L1764)

## 목적

이 기능의 목적은 모델 호출, 세션 상태, 도구 실행, provider 주입 같은 하위 기능들을 **하나의 agent lifecycle 표면**으로 통합하는 것이다. .NET의 `AIAgent`는 세션 생성/직렬화/역직렬화와 실행 API를 추상 메서드로 정의하고, Python의 `BaseAgent`/`RawAgent`는 세션 생성과 chat-client 연결, context provider와 middleware 결합을 lifecycle의 중심에 둔다. [AIAgent.cs#L138-L235](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Abstractions/AIAgent.cs#L138-L235) [_agents.py#L463-L507](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_agents.py#L463-L507) [_agents.py#L788-L908](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_agents.py#L788-L908)

## 경계

이 문서는 다음만 포함한다.

- 공개 agent 추상화와 lifecycle 표면: 생성, 식별자, 메타데이터, 세션 생성, run entry points, wrapping/delegation. [AIAgent.cs#L46-L106](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Abstractions/AIAgent.cs#L46-L106) [_agents.py#L425-L461](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_agents.py#L425-L461)
- agent가 session snapshot 형식을 **누가 소유하는지**와 그 차이. .NET은 agent가 snapshot API를 소유하고, Python은 `AgentSession`과 optional store가 snapshot을 소유한다. [AIAgent.cs#L168-L235](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Abstractions/AIAgent.cs#L168-L235) [_sessions.py#L1655-L1689](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_sessions.py#L1655-L1689)
- decorator, builder, layer 기반 wrapping 경계. [AIAgentBuilder.cs#L40-L178](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/AIAgentBuilder.cs#L40-L178) [_agents.py#L1751-L1845](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_agents.py#L1751-L1845)

다음은 의도적으로 제외한다.

- workflow/orchestration, harness, hosting/protocol, provider-specific transport/auth 구현. Python public export도 이들을 별도 모듈로 분리한다. [__init__.py#L109-L173](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/__init__.py#L109-L173) [__init__.py#L286-L290](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/__init__.py#L286-L290)

## 성숙도

- .NET의 `AIAgent`, `DelegatingAIAgent`, `ChatClientAgent`, `AIAgentBuilder` 선언부 자체에는 experimental attribute가 붙어 있지 않다. 실험 단계 표시는 continuation token, 일부 옵션, 일부 nested context 등에만 붙는다. [AIAgent.cs#L37-L38](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Abstractions/AIAgent.cs#L37-L38) [ChatClientAgent.cs#L39-L40](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientAgent.cs#L39-L40) [AIAgentBuilder.cs#L14-L17](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/AIAgentBuilder.cs#L14-L17) [AgentRunOptions.cs#L43-L56](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Abstractions/AgentRunOptions.cs#L43-L56)
- Python의 `BaseAgent`, `RawAgent`, `Agent`, `SupportsAgentRun`도 experimental decorator가 없다. experimental은 `SessionStore`, `FileHistoryProvider`, progressive tools, agent hooks 같은 주변 기능에만 붙는다. [_agents.py#L364-L420](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_agents.py#L364-L420) [_agents.py#L707-L719](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_agents.py#L707-L719) [_agents.py#L1751-L1764](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_agents.py#L1751-L1764) [_feature_stage.py#L43-L67](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_feature_stage.py#L43-L67)

## Identity / Metadata

### .NET

- `AIAgent.Id`는 `IdCore`가 null이면 기본값으로 `Guid.NewGuid().ToString("N")`를 사용한다. `Name`과 `Description`은 virtual 속성이다. [AIAgent.cs#L46-L94](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Abstractions/AIAgent.cs#L46-L94)
- `ChatClientAgent`는 생성 시 `ChatClientMetadata.ProviderName`을 읽어 `AIAgentMetadata`를 만들고, `Id`/`Name`/`Description`은 `ChatClientAgentOptions`에서 공급한다. [ChatClientAgent.cs#L122-L145](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientAgent.cs#L122-L145) [ChatClientAgent.cs#L176-L197](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientAgent.cs#L176-L197)
- `GetService()`는 metadata와 wrapped service 접근을 위한 공식 escape hatch다. `ChatClientAgent.GetService()`는 `AIAgentMetadata`, `IChatClient`, `ChatOptions`, `ChatClientAgentOptions`, context providers, chat history provider, inner chat client까지 노출한다. [AIAgent.cs#L108-L136](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Abstractions/AIAgent.cs#L108-L136) [ChatClientAgent.cs#L404-L413](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientAgent.cs#L404-L413)

### Python

- `BaseAgent.__init__()`는 `id`를 생략하면 `uuid4()` 문자열을 생성하고, `name`/`description`/`additional_properties`를 필드로 보관한다. [_agents.py#L425-L461](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_agents.py#L425-L461)
- `RawAgent.__init__()`는 초기화 중 client에서 `model`을 읽어 default options를 구성하고, 마지막에 `_update_agent_name_and_description()`를 호출해 client가 지원하면 agent name/description을 하위 client에 동기화한다. [_agents.py#L879-L907](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_agents.py#L879-L907) [_agents.py#L972-L981](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_agents.py#L972-L981)

### 차이 요약

- .NET은 metadata를 `GetService()`로 다시 꺼낼 수 있게 설계했고, Python은 agent object의 필드와 constructor state가 주 메타데이터 경로다. [ChatClientAgent.cs#L404-L413](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientAgent.cs#L404-L413) [_agents.py#L425-L461](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_agents.py#L425-L461)

## 공개 API·타입

### .NET

- 핵심 public abstraction은 `AIAgent`, `DelegatingAIAgent`, `AgentRunContext`, `AgentRunOptions`, `AgentSession`, `AgentResponse`, `AgentResponseUpdate`, `ChatClientAgent`, `AIAgentBuilder`다. [AIAgent.cs#L37-L38](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Abstractions/AIAgent.cs#L37-L38) [DelegatingAIAgent.cs#L28-L29](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Abstractions/DelegatingAIAgent.cs#L28-L29) [AgentRunContext.cs#L9-L42](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Abstractions/AgentRunContext.cs#L9-L42) [AgentRunOptions.cs#L11-L128](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Abstractions/AgentRunOptions.cs#L11-L128) [AgentSession.cs#L59-L119](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Abstractions/AgentSession.cs#L59-L119) [ChatClientAgent.cs#L39-L40](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientAgent.cs#L39-L40) [AIAgentBuilder.cs#L14-L17](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/AIAgentBuilder.cs#L14-L17)

### Python

- public export는 `Agent`, `BaseAgent`, `RawAgent`, `SupportsAgentRun`와 함께 session, middleware, response 타입들을 함께 노출한다. [__init__.py#L43-L46](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/__init__.py#L43-L46) [__init__.py#L174-L209](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/__init__.py#L174-L209) [__init__.py#L247-L285](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/__init__.py#L247-L285)
- `SupportsAgentRun`는 `run(messages=None, *, stream=False|True, session=None, ...)` 계약을 protocol로 정의하고, `BaseAgent`는 이 contract를 직접 구현하지 않는 최소 기반 클래스로 문서화돼 있다. [_agents.py#L224-L318](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_agents.py#L224-L318) [_agents.py#L364-L376](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_agents.py#L364-L376)

## 상태·스냅샷

- .NET lifecycle에서 session snapshot 형식은 **agent가 소유**한다. `AIAgent`는 `SerializeSessionAsync()`와 `DeserializeSessionAsync()`를 public API로 제공하고, 구현체가 supported session type 여부를 통제한다. [AIAgent.cs#L168-L235](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Abstractions/AIAgent.cs#L168-L235)
- 같은 이유로 .NET `AgentSession` 문서는 “세션은 agent가 생성하며, agent마다 서로 다른 behavior를 붙일 수 있으므로 다른 agent 간 재사용이 불가능할 수 있다”고 명시한다. [AgentSession.cs#L24-L43](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Abstractions/AgentSession.cs#L24-L43)
- Python lifecycle에서는 snapshot 형식이 **agent가 아니라 `AgentSession`과 store가 소유**한다. `AgentSession.to_dict()/from_dict()`가 기본 snapshot 경로이고, `SessionStore`/`FileSessionStore`는 그 snapshot을 저장하는 별도 계층이다. [_sessions.py#L1655-L1689](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_sessions.py#L1655-L1689) [_sessions.py#L1693-L1779](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_sessions.py#L1693-L1779)

## Create Session

### .NET

- `AIAgent.CreateSessionAsync()`는 abstract `CreateSessionCoreAsync()`를 통해 agent-compatible session을 만든다. [AIAgent.cs#L138-L166](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Abstractions/AIAgent.cs#L138-L166)
- `ChatClientAgent`의 기본 session type은 `ChatClientAgentSession`이고, parameterless create는 빈 session을, typed overload `CreateSessionAsync(string conversationId)`는 service-managed conversation continuation을 위한 session을 만든다. [ChatClientAgent.cs#L416-L445](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientAgent.cs#L416-L445)
- `ChatClientAgentSession`은 `ConversationId`와 `StateBag`를 함께 보관하며, `ConversationId`는 service-side history handle로 nullable이고 시간에 따라 변경될 수 있다. [ChatClientAgentSession.cs#L25-L65](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientAgentSession.cs#L25-L65)

### Python

- `BaseAgent.create_session()`은 lightweight `AgentSession`을 만들고, `get_session(service_session_id=...)`은 service-managed conversation handle을 가진 session을 만든다. [_agents.py#L463-L507](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_agents.py#L463-L507)
- Python `AgentSession`은 `session_id`, `service_session_id`, `state`만 가진 단순 컨테이너다. [_sessions.py#L1615-L1689](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_sessions.py#L1615-L1689)

### 차이 요약

- .NET은 “agent가 지원하는 concrete session type”을 강하게 통제하고, Python은 “공통 lightweight session shape + service_session_id”를 공유한다. [ChatClientAgent.cs#L452-L464](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientAgent.cs#L452-L464) [_agents.py#L463-L507](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_agents.py#L463-L507)

## Run Entry Points

### .NET

- `AIAgent`는 `RunAsync()`와 `RunStreamingAsync()`만 제공하며, 각각 no-message / string / single chat message / message collection overload를 가진다. 동기 blocking run은 없다. [AIAgent.cs#L237-L506](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Abstractions/AIAgent.cs#L237-L506)
- `RunAsync(string)`은 입력 문자열을 `ChatRole.User` 메시지로 감싸고, `RunStreamingAsync(string)`도 동일한 규칙을 따른다. [AIAgent.cs#L257-L305](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Abstractions/AIAgent.cs#L257-L305) [AIAgent.cs#L388-L436](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Abstractions/AIAgent.cs#L388-L436)

### Python

- public `Agent.run()`은 overload상 `stream=False`면 `Awaitable[AgentResponse]`, `stream=True`면 `ResponseStream[AgentResponseUpdate, AgentResponse]`를 반환한다. 즉 public method는 동기처럼 보이지만 실제 작업 결과는 비동기 객체다. [_agents.py#L1766-L1828](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_agents.py#L1766-L1828)
- 실제 core 구현인 `RawAgent.run()`도 같은 shape를 가지며, `messages`, `session`, `tools`, `options`, `compaction_strategy`, `tokenizer`, `function_invocation_kwargs`, `client_kwargs`를 받는다. [_agents.py#L983-L1040](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_agents.py#L983-L1040)
- message input shape는 `AgentRunInputs = str | Content | Message | Sequence[...]`이고, `normalize_messages()`가 문자열/단일 content를 user message로 정규화한다. [_types.py#L1844-L1881](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_types.py#L1844-L1881)

## 상세 실행 흐름

### .NET: `AIAgent` 공통 wrapper

1. public `RunAsync(messages, session, options, ct)`가 `CurrentRunContext = new AgentRunContext(...)`를 세팅한다. [AIAgent.cs#L334-L342](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Abstractions/AIAgent.cs#L334-L342)  
2. 그 다음 `RunCoreAsync(...)`를 호출한다. 구현체는 여기서 실제 provider/client 호출을 수행한다. [AIAgent.cs#L344-L370](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Abstractions/AIAgent.cs#L344-L370)  
3. public `RunStreamingAsync(...)`도 동일하게 context를 만들고 `RunCoreStreamingAsync(...)`를 순회한다. [AIAgent.cs#L464-L479](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Abstractions/AIAgent.cs#L464-L479)  
4. streaming에서는 각 `yield return` 뒤 호출자 코드가 실행되므로, base wrapper가 `CurrentRunContext`를 다시 복원한다. [AIAgent.cs#L472-L479](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Abstractions/AIAgent.cs#L472-L479)

### .NET: `ChatClientAgent` concrete 흐름

1. constructor는 mutable `ChatClientAgentOptions`를 clone하고, opt-out이 아니면 `WithDefaultAgentMiddleware()`로 chat client stack을 장식한다. 기본 chat history provider는 명시되지 않으면 `InMemoryChatHistoryProvider`다. [ChatClientAgent.cs#L122-L137](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientAgent.cs#L122-L137)
2. non-streaming `RunCoreAsync()`는 `PrepareSessionAndMessagesAsync()`로 safe session, merged chat options, input messages를 만든다. [ChatClientAgent.cs#L205-L218](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientAgent.cs#L205-L218)
3. safe session이 late-bound되었기 때문에 `EnsureRunContextHasSession()`로 ambient run context의 session을 실제 session으로 교체한다. [ChatClientAgent.cs#L219-L222](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientAgent.cs#L219-L222) [ChatClientAgent.cs#L938-L956](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientAgent.cs#L938-L956)
4. chat client 호출 실패 시 provider failure notification을 보내고 예외를 rethrow한다. 성공 시 conversation id update, author name 보정, provider success notification 후 `AgentResponse`를 반환한다. [ChatClientAgent.cs#L229-L263](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientAgent.cs#L229-L263)
5. streaming path는 inner enumerator를 직접 잡아 항상 dispose하고, 매 update마다 continuation token wrapping과 author name 보정을 수행한다. 완료 후에는 collected updates를 `ChatResponse`로 재조립해 conversation id update와 provider notification을 수행한다. [ChatClientAgent.cs#L321-L401](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientAgent.cs#L321-L401)

### Python: `Agent` → `RawAgent` layered 흐름

1. public `Agent.run()`은 telemetry/middleware layer를 거친 뒤 `super().run(...)`으로 내려간다. [ _agents.py#L1814-L1845](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_agents.py#L1814-L1845)
2. 실제 구현인 `RawAgent.run()`은 먼저 `_prepare_run_context()` coroutine을 준비하고, run identity를 만들고 persistence gate claim을 채택한다. [_agents.py#L1077-L1097](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_agents.py#L1077-L1097)
3. non-streaming이면 `_call_chat_client(ctx, stream=False)` 후 `_parse_non_streaming_response()`로 final `AgentResponse`를 만든다. streaming이면 `_call_chat_client(..., stream=True)` 후 `_parse_streaming_response()`가 `ResponseStream`에 transform/result hooks를 붙인다. [_agents.py#L1098-L1117](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_agents.py#L1098-L1117) [_agents.py#L1135-L1193](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_agents.py#L1135-L1193) [_agents.py#L1195-L1277](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_agents.py#L1195-L1277)
4. `_prepare_run_context()`는 run-level options와 agent defaults를 합치고, service-side storage 여부를 판정하고, 필요 시 `InMemoryHistoryProvider`를 자동 추가하고, context providers를 돌려 session messages / tools / instructions / middleware를 축적한다. [_agents.py#L1315-L1532](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_agents.py#L1315-L1532)
5. `_parse_non_streaming_response()`는 `author_name` 보정, `service_session_id` 갱신, `AgentResponse` wrapping, provider after_run을 수행한다. [_agents.py#L1163-L1193](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_agents.py#L1163-L1193)
6. `_parse_streaming_response()`는 update 수신 중 `conversation_id`를 session에 eager propagate하고, final result hook에서 provider after_run을 수행한다. [_agents.py#L1204-L1247](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_agents.py#L1204-L1247)

## 상태·영속화

- .NET lifecycle에서 session state의 durable payload는 `AgentSession.StateBag`에 저장되고, concrete provider/session이 그 위에 state를 얹는다. [AgentSession.cs#L76-L119](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Abstractions/AgentSession.cs#L76-L119)
- .NET `ChatClientAgent`는 constructor 시점에 chat history provider를 붙여 두지만, service가 conversation id를 반환해 server-side history를 관리한다고 드러나면 local `ChatHistoryProvider`와 충돌을 경고/예외/clear 중 하나로 처리한다. [ChatClientAgent.cs#L133-L145](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientAgent.cs#L133-L145) [ChatClientAgent.cs#L819-L849](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientAgent.cs#L819-L849)
- Python lifecycle는 session state ownership을 `AgentSession.state` dict에 둔다. provider가 있으면 run 시점에 ephemeral session을 자동 생성할 수 있고, `SessionStore`는 working copy와 stored snapshot을 deepcopy로 분리한다. [_agents.py#L1369-L1377](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_agents.py#L1369-L1377) [_sessions.py#L1693-L1768](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_sessions.py#L1693-L1768)

## Delegation / Wrapping 경계와 확장점

### .NET

- `DelegatingAIAgent`는 `Id`, `Name`, `Description`, `GetService()`, session create/serialize/deserialize, run, streaming run까지 전부 inner agent로 위임하는 **투명 decorator base**다. [DelegatingAIAgent.cs#L44-L102](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Abstractions/DelegatingAIAgent.cs#L44-L102)
- `AIAgentBuilder`는 agent factory를 쌓고 `Build()` 시 역순으로 적용한다. 따라서 **먼저 추가한 factory가 가장 바깥 wrapper**가 된다. [AIAgentBuilder.cs#L49-L70](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/AIAgentBuilder.cs#L49-L70)
- builder는 simple `Use(Func<AIAgent,AIAgent>)`, service-aware `Use(Func<AIAgent,IServiceProvider,AIAgent>)`, anonymous delegating run/runStreaming 구현, message context providers wrapper를 모두 지원한다. [AIAgentBuilder.cs#L72-L178](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/AIAgentBuilder.cs#L72-L178)

### Python

- Python의 wrapping은 agent 객체 decorator chain보다는 **계층형 클래스 구성**에 가깝다. `Agent`는 `AgentMiddlewareLayer`, `AgentTelemetryLayer`, `RawAgent`를 다중 상속하고, `run()` 구현은 `super().run(...)`으로 내려간다. [_agents.py#L1751-L1764](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_agents.py#L1751-L1764) [_agents.py#L1814-L1845](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_agents.py#L1814-L1845)
- chat client 쪽에서는 `BaseChatClient.as_agent()`가 client를 이미 붙인 `Agent`를 만들어 주는 convenience wrapper다. [_clients.py#L571-L658](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_clients.py#L571-L658)

### 차이 요약

- .NET의 wrapping boundary는 **런타임 decorator 객체 체인**이고, Python의 wrapping boundary는 **MRO 기반 layer 조합 + optional middleware pipeline**이다. [DelegatingAIAgent.cs#L13-L28](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Abstractions/DelegatingAIAgent.cs#L13-L28) [_agents.py#L1751-L1764](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_agents.py#L1751-L1764)

## 동시성·스트리밍·취소

- .NET lifecycle는 모든 핵심 API에 `CancellationToken`을 노출한다. [AIAgent.cs#L138-L235](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Abstractions/AIAgent.cs#L138-L235) [ChatClientAgent.cs#L205-L401](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientAgent.cs#L205-L401)
- .NET은 ambient lifecycle state를 `AsyncLocal<AgentRunContext?>`로 보관한다. 따라서 async 흐름 전체에 context가 따라가지만, streaming yield 경계에서는 복원이 필요하다. [AIAgent.cs#L40-L45](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Abstractions/AIAgent.cs#L40-L45) [AIAgent.cs#L96-L106](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Abstractions/AIAgent.cs#L96-L106) [AIAgent.cs#L470-L479](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Abstractions/AIAgent.cs#L470-L479)
- Python public lifecycle에는 explicit cancellation token이 없다. 취소는 반환된 awaitable이나 `ResponseStream`를 감싼 asyncio task cancellation에 의존한다. [_agents.py#L983-L1040](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_agents.py#L983-L1040) [_types.py#L3061-L3371](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_types.py#L3061-L3371)
- Python streaming lifecycle는 `ResponseStream`가 auto-finalization을 수행하므로, caller가 `async for`로 끝까지 소비하면 `get_final_response()`를 별도 호출하지 않아도 finalization hooks가 실행된다. [_types.py#L3326-L3330](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_types.py#L3326-L3330)

## 오류·검증·보안

- .NET `AIAgent` 문서는 agent가 user message, provider data, tool data, LLM output을 sanitize/validate하지 않으므로 system message는 developer-controlled여야 하고, assistant/tool outputs도 불신해야 한다고 적는다. [AIAgent.cs#L24-L35](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Abstractions/AIAgent.cs#L24-L35)
- .NET `ChatClientAgent` 문서도 tool invocation은 기본적으로 user approval 없이 실행되며, tool arguments는 untrusted input으로 취급해야 한다고 적는다. [ChatClientAgent.cs#L22-L37](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientAgent.cs#L22-L37)
- Python lifecycle는 generic chat path에서 structured `service_session_id`를 직접 넘기지 않도록 검증하고, string이 아니면 `AgentInvalidRequestException`을 던진다. 이는 “session shape는 만들 수 있지만, 모든 client가 그 shape를 실행에 사용할 수 있는 것은 아니다”라는 lifecycle boundary를 드러낸다. [_agents.py#L508-L529](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_agents.py#L508-L529)

## .NET 구현과 테스트

- `ChatClientAgent.CreateSessionAsync(string conversationId)`가 typed overload로 `ChatClientAgentSession.ConversationId`를 채우는 동작은 테스트로 검증된다. [ChatClientAgent_CreateSessionTests.cs#L15-L29](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/ChatClient/ChatClientAgent_CreateSessionTests.cs#L15-L29)
- `AIAgent.RunAsync(...)`와 `RunStreamingAsync(...)`의 모든 overload가 `CurrentRunContext`를 올바르게 세팅한다는 점도 테스트된다. [AIAgentTests.cs#L235-L330](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Abstractions.UnitTests/AIAgentTests.cs#L235-L330)
- `ChatClientAgent`가 conversation id를 받지 못하는 local-history 경로에서는 default `InMemoryChatHistoryProvider`를 사용해 request/response를 session에 축적한다는 점도 테스트된다. 이는 “session 생성 후 run이 stateful lifecycle로 이어진다”는 concrete 예다. [ChatClientAgent_ChatHistoryManagementTests.cs#L144-L172](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/ChatClient/ChatClientAgent_ChatHistoryManagementTests.cs#L144-L172)

## Python 구현과 테스트

- `Agent.create_session()`가 `AgentSession`을 반환하는 기본 lifecycle entrypoint라는 점은 테스트된다. [test_agents.py#L379-L386](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_agents.py#L379-L386)
- `Agent.get_session(service_session_id=...)`가 문자열 service session id를 보존하고, structured service session id도 session object에는 저장할 수 있다는 점도 테스트된다. [test_agents.py#L2668-L2691](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_agents.py#L2668-L2691)
- 반대로 generic chat client run 경로는 structured `service_session_id`를 거부한다는 점도 테스트된다. [test_agents.py#L2694-L2705](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_agents.py#L2694-L2705)
- streaming lifecycle에서는 caller가 `get_final_response()`를 호출하지 않아도, stream iteration만으로 `session.service_session_id`가 갱신되고 after_run persistence가 발생한다는 점이 테스트된다. [test_agents.py#L1098-L1165](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_agents.py#L1098-L1165)

## 문서 차이

- Python README는 가장 단순한 lifecycle만 보여준다. `Agent(client=..., instructions=...)`를 만들고 `asyncio.run(agent.run(...))`으로 실행하는 예제는 실제 코드의 public surface와 일치한다. [README.md#L69-L92](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/README.md#L69-L92) [_agents.py#L1766-L1828](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_agents.py#L1766-L1828)
- 다만 README는 `create_session()`/`get_session()`이나 structured `service_session_id` 경계, stream iteration만으로 finalization이 일어나는 점, layer-based `Agent` 구성을 설명하지 않는다. 코드와 테스트를 기준으로 보면 실제 lifecycle 표면은 README보다 넓다. [README.md#L69-L92](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/README.md#L69-L92) [_agents.py#L463-L507](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_agents.py#L463-L507) [test_agents.py#L1098-L1165](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_agents.py#L1098-L1165)
- .NET 측에서 확인한 코드-문서 미세 차이는 `ChatClientAgent.PrepareSessionAndMessagesAsync()`가 `AllowBackgroundResponses == true && session == null`이면 예외를 던지면서, 메시지 텍스트는 “continuing a background response with a continuation token”만 언급한다는 점이다. 코드 조건은 continuation token이 없어도 background response 전체에 session을 요구한다. 따라서 lifecycle 규칙은 **메시지보다 코드가 더 넓다**. [ChatClientAgent.cs#L713-L718](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientAgent.cs#L713-L718)

## Java 결정

- Java 설계는 .NET의 **명시적 agent abstraction + decorator/builder**와 Python의 **단일 public `Agent` 진입점**을 절충하는 것이 적절하다. 근거는 .NET이 lifecycle 경계와 session snapshot ownership을 명확히 드러내고, Python이 public 사용성을 단순화했기 때문이다. [AIAgent.cs#L138-L235](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Abstractions/AIAgent.cs#L138-L235) [AIAgentBuilder.cs#L40-L178](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/AIAgentBuilder.cs#L40-L178) [_agents.py#L1751-L1845](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_agents.py#L1751-L1845)
- 구체적으로는 Java에서 public surface를 `Agent` 하나로 모으되, 내부적으로는 `.NET`처럼 `DelegatingAgent`/`AgentBuilder`를 유지하는 편이 낫다. 그래야 logging, telemetry, policy wrapper를 독립적으로 조합할 수 있다. [DelegatingAIAgent.cs#L13-L28](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Abstractions/DelegatingAIAgent.cs#L13-L28) [LoggingAgent.cs#L16-L31](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/LoggingAgent.cs#L16-L31)
- run API shape는 Python식 “동기 메서드가 awaitable/stream을 반환”보다 .NET식 **명시적 async 메서드 분리**가 Java에 더 적합하다. Java에서는 `runAsync()`와 `streamAsync()`를 분리하면 checked/unchecked error handling, cancellation, reactive stream 선택이 더 명확해진다. [AIAgent.cs#L251-L506](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Abstractions/AIAgent.cs#L251-L506) [_agents.py#L983-L1040](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_agents.py#L983-L1040)
- session snapshot ownership은 agent-owned format(.NET)과 session-owned format(Python) 중 하나를 고르기보다, Java에서는 **agent가 compatibility를 검증하고 session/store가 payload를 보관하는 이중 구조**가 적합하다. 이는 agent별 session compatibility 검사와 generic session snapshot store 양쪽 요구를 동시에 만족시킨다. [AIAgent.cs#L168-L235](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Abstractions/AIAgent.cs#L168-L235) [_sessions.py#L1655-L1689](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_sessions.py#L1655-L1689)

## 구체 acceptance scenarios

1. **.NET agent wrapper는 모든 run overload에서 ambient run context를 채운다.** 성공 조건은 `CurrentRunContext.Agent == 호출한 agent`, `CurrentRunContext.Session == 전달한 session`, 그리고 message overload에 따라 request message count가 기대값과 일치하는 것이다. [AIAgentTests.cs#L235-L330](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Abstractions.UnitTests/AIAgentTests.cs#L235-L330)

2. **.NET `ChatClientAgent.CreateSessionAsync(conversationId)`는 service-managed continuation을 위한 concrete session을 만든다.** 성공 조건은 반환 타입이 `ChatClientAgentSession`이고 `ConversationId`가 seed 값과 동일한 것이다. [ChatClientAgent_CreateSessionTests.cs#L15-L29](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/ChatClient/ChatClientAgent_CreateSessionTests.cs#L15-L29)

3. **Python `Agent.create_session()`는 lightweight session을, `get_session(service_session_id=...)`는 service-bound session을 만든다.** 성공 조건은 각각 `AgentSession` 반환, 그리고 전달한 `service_session_id`의 보존이다. [test_agents.py#L379-L386](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_agents.py#L379-L386) [test_agents.py#L2668-L2691](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_agents.py#L2668-L2691)

4. **Python generic chat-agent run은 structured `service_session_id`를 허용하지 않는다.** 성공 조건은 run 전에 `AgentInvalidRequestException`이 발생하는 것이다. [test_agents.py#L2694-L2705](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_agents.py#L2694-L2705)

5. **Python streaming lifecycle은 `get_final_response()` 없이도 session lifecycle을 진전시킨다.** 성공 조건은 stream iteration만 끝내도 `session.service_session_id`가 set되고, after_run provider가 history를 저장하는 것이다. [test_agents.py#L1098-L1165](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_agents.py#L1098-L1165)

6. **.NET local-history lifecycle은 conversation id가 없는 client에서 default in-memory history provider를 통해 stateful로 동작한다.** 성공 조건은 run 후 session-backed history에 user message와 assistant response가 모두 존재하는 것이다. [ChatClientAgent_ChatHistoryManagementTests.cs#L144-L172](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/ChatClient/ChatClientAgent_ChatHistoryManagementTests.cs#L144-L172)

## Source Inventory

### .NET production source
- [`dotnet/src/Microsoft.Agents.AI.Abstractions/AIAgent.cs`](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Abstractions/AIAgent.cs)
- [`dotnet/src/Microsoft.Agents.AI.Abstractions/DelegatingAIAgent.cs`](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Abstractions/DelegatingAIAgent.cs)
- [`dotnet/src/Microsoft.Agents.AI.Abstractions/AgentRunContext.cs`](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Abstractions/AgentRunContext.cs)
- [`dotnet/src/Microsoft.Agents.AI.Abstractions/AgentRunOptions.cs`](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Abstractions/AgentRunOptions.cs)
- [`dotnet/src/Microsoft.Agents.AI.Abstractions/AgentSession.cs`](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Abstractions/AgentSession.cs)
- [`dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientAgent.cs`](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientAgent.cs)
- [`dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientAgentOptions.cs`](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientAgentOptions.cs)
- [`dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientAgentRunOptions.cs`](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientAgentRunOptions.cs)
- [`dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientAgentSession.cs`](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/ChatClient/ChatClientAgentSession.cs)
- [`dotnet/src/Microsoft.Agents.AI/AIAgentBuilder.cs`](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/AIAgentBuilder.cs)
- [`dotnet/src/Microsoft.Agents.AI/LoggingAgent.cs`](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/LoggingAgent.cs)
- [`dotnet/src/Microsoft.Agents.AI/OpenTelemetryAgent.cs`](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/OpenTelemetryAgent.cs)

### .NET tests
- [`dotnet/tests/Microsoft.Agents.AI.Abstractions.UnitTests/AIAgentTests.cs`](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Abstractions.UnitTests/AIAgentTests.cs)
- [`dotnet/tests/Microsoft.Agents.AI.UnitTests/ChatClient/ChatClientAgent_CreateSessionTests.cs`](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/ChatClient/ChatClientAgent_CreateSessionTests.cs)
- [`dotnet/tests/Microsoft.Agents.AI.UnitTests/ChatClient/ChatClientAgent_ChatHistoryManagementTests.cs`](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/ChatClient/ChatClientAgent_ChatHistoryManagementTests.cs)

### Python production source
- [`python/packages/core/agent_framework/__init__.py`](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/__init__.py)
- [`python/packages/core/agent_framework/_agents.py`](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_agents.py)
- [`python/packages/core/agent_framework/_clients.py`](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_clients.py)
- [`python/packages/core/agent_framework/_types.py`](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_types.py)
- [`python/packages/core/agent_framework/_sessions.py`](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_sessions.py)
- [`python/packages/core/agent_framework/_feature_stage.py`](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_feature_stage.py)

### Python tests
- [`python/packages/core/tests/core/test_agents.py`](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_agents.py)
- [`python/packages/core/tests/core/test_sessions.py`](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_sessions.py)

### reviewed docs
- [`python/packages/core/README.md`](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/README.md)