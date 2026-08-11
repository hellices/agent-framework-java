# 21. OpenAI Responses-compatible hosting

## 범위

이 문서는 Microsoft Agent Framework의 **OpenAI Responses-compatible hosting**만 다룬다. 대상은 다음이다.

- endpoint surface와 route shape
- request/response wire types
- request body → agent/workflow run 값 매핑
- streaming SSE event 모델
- `previous_response_id` / `conversation` 기반 continuation state
- tool call / reasoning / multimodal output 매핑
- error mapping, validation, security boundary
- .NET/Python 구현 차이
- conformance test coverage
- Java 설계 결정

반대로 generic hosting lifecycle, DI/store/checkpoint abstraction 자체는 `20-hosting.md`의 범위이며 여기서는 중복을 줄이고, OpenAI Responses protocol과 직접 맞닿는 부분만 기술한다. 또한 A2A, AG-UI, MCP, Foundry hosted runtime, DevUI, Telegram/ChatKit 채널 세부는 별도 문서의 범위다. (출처: [ADR-0032 scope](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0032-dotnet-hosting-protocol-helpers.md#L144-L148), [Python hosting spec packages table](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/specs/002-python-hosting-channels.md#L64-L73))

## 요약

OpenAI Responses-compatible hosting은 두 언어에서 **서로 다른 packaging 철학**을 가진다. .NET은 하나의 패키지 안에서 **helper-only façade**(`OpenAIResponses`)와 **route-owning server**(`MapOpenAIResponses` + internal `IResponsesService`)를 둘 다 제공하고, Python은 **helper-only** 패키지만 제공해 FastAPI/Starlette/Django/Azure Functions가 route와 auth, status code, background work를 소유하게 한다. .NET의 public helper는 `JsonElement` 경계를 사용하며, 기본 `RunOptionsFactory`가 request-supplied generation/tool settings를 거부하는 **strict default**를 채택한다. Python helper는 dict/SSE string 경계를 사용하고 기본적으로 transport fields를 제외한 대부분의 request fields를 `options`로 전달하는 **permissive default**라서, 실제 허용 여부는 host route가 따로 필터링해야 한다. Streaming도 차이가 커서 .NET은 `response.created`, `response.in_progress`, `response.output_item.*`, `response.content_part.*`, `response.output_text.delta`, `response.function_call_arguments.*`, `response.completed` 등 풍부한 event union을 가지지만, Python helper는 실질적으로 `response.created`, `response.output_text.delta`, terminal `response.completed`/`response.failed`만 방출한다. Continuation 모델은 양쪽 모두 `previous_response_id`를 immutable snapshot branch point, `conversation`/`conversation_id`를 mutable head로 본다. (출처: [.NET OpenAIResponses helper](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/OpenAIResponses.cs#L15-L30), [.NET route-owning endpoints](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/EndpointRouteBuilderExtensions.Responses.cs#L60-L109), [ADR-0032 existing route-owning server](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0032-dotnet-hosting-protocol-helpers.md#L21-L33), [Python hosting-responses README](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/README.md#L19-L21), [.NET map options strict default](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/OpenAIResponsesMapOptions.cs#L20-L31), [Python request option remap/pass-through](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/agent_framework_hosting_responses/_parsing.py#L45-L53), [Python responses_to_run](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/agent_framework_hosting_responses/_parsing.py#L173-L196), [.NET streaming event union](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/Responses/Models/StreamingResponseEvent.cs#L13-L33), [Python streaming SSE helper](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/agent_framework_hosting_responses/_parsing.py#L862-L956), [.NET session-id trust boundary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/OpenAIResponses.cs#L120-L150), [Python mutable-head note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/README.md#L57-L60))

---

## 목적

이 기능의 목적은 **OpenAI Responses API와 호환되는 HTTP/SSE surface**를 제공하면서, Agent Framework target을 그대로 사용하게 하는 것이다. 여기서 “호환”은 단순 text completion만이 아니라 다음을 포함한다.

- request `input`의 string/list-of-items/message envelope 처리
- reasoning/tool/media/file 같은 다양한 output item family
- SSE 기반 streaming lifecycle
- `previous_response_id` 또는 explicit `conversation` continuity
- tool calls와 tool results, approval류 event의 표면화
- route-owning server와 app-owned route 양쪽 사용 패턴 지원

.NET ADR는 이 helper surface를 “app-owned routing counterpart”로 설명하고, Python README도 “FastAPI/Starlette/Django/Azure Functions code owns route registration, authentication, status codes, response construction, and background work”라고 못박는다. (출처: [.NET OpenAIResponses summary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/OpenAIResponses.cs#L15-L23), [ADR-0032 public surface](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0032-dotnet-hosting-protocol-helpers.md#L78-L98), [Python hosting-responses README](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/README.md#L3-L21))

## 경계

### 이 기능이 하는 일

- OpenAI Responses-shaped request를 parse해서 agent/workflow run 값으로 바꾼다.
- Agent Framework 결과를 Responses-shaped JSON payload 또는 SSE stream으로 렌더링한다.
- continuation id(`previous_response_id`, `conversation`)를 **노출**한다.
- route-owning implementation이 있을 경우 response CRUD, streaming replay, input item listing까지 제공할 수 있다(.NET). (출처: [.NET IResponsesService](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/Responses/IResponsesService.cs#L15-L111), [.NET endpoint mapping](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/EndpointRouteBuilderExtensions.Responses.cs#L84-L107))

### 이 기능이 하지 않는 일

- caller authentication / authorization
- request-derived id를 trusted store key로 binding
- per-conversation single-writer coordination
- durable background executor
- vendor-specific non-Responses APIs 전체 구현
- checkpoint/session store choice

이 책임은 host application에 남는다. Python README는 이를 직접 말하고, .NET sample README도 `MapOpenAIResponses`와 app-owned route 두 패턴을 구분해 설명한다. (출처: [Python hosting-responses README responsibilities](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/README.md#L19-L21), [.NET af-hosting README two ways](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/samples/04-hosting/af-hosting/README.md#L9-L20))

---

## 성숙도

| 항목 | 상태 |
|---|---|
| .NET `Microsoft.Agents.AI.Hosting.OpenAI` | `alpha` |
| Python `agent-framework-hosting-responses` | `alpha` |
| .NET route-owning server | package 내부 구현, helper와 공존 |
| Python route-owning server | 없음, app/framework가 직접 구현 |

출처:
- [.NET Hosting.OpenAI csproj](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/Microsoft.Agents.AI.Hosting.OpenAI.csproj#L3-L9)
- [Python PACKAGE_STATUS](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L37-L41)
- [ADR-0032 current .NET state](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0032-dotnet-hosting-protocol-helpers.md#L21-L24)

---

## 공개 API·타입

## .NET

### Helper façade
- `OpenAIResponses.ToAgentRunRequest(JsonElement body, OpenAIResponsesMapOptions? mapOptions = null)`
- `OpenAIResponses.WriteResponse(AgentResponse response, string responseId, string? conversationId = null)`
- `OpenAIResponses.WriteResponseStreamAsync(IAsyncEnumerable<AgentResponseUpdate> updates, string responseId, string? conversationId = null, CancellationToken ct = default)`
- `OpenAIResponses.GetSessionStoreId(OpenAIResponsesRunRequest request)`
- `OpenAIResponses.CreateResponseId()`
- `OpenAIResponsesRunRequest` (`Messages`, `Options`, `PreviousResponseId`, `ConversationId`)
- `OpenAIResponsesMapOptions.RunOptionsFactory` (출처: [OpenAIResponses public API](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/OpenAIResponses.cs#L32-L164), [OpenAIResponsesRunRequest](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/OpenAIResponsesRunRequest.cs#L8-L49), [OpenAIResponsesMapOptions](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/OpenAIResponsesMapOptions.cs#L13-L99))

### Route-owning endpoint surface
- `MapOpenAIResponses(...)`
- internal `IResponsesService` contract:
  - `ValidateRequestAsync`
  - `CreateResponseAsync`
  - `CreateResponseStreamingAsync`
  - `GetResponseAsync`
  - `GetResponseStreamingAsync`
  - `CancelResponseAsync`
  - `DeleteResponseAsync`
  - `ListResponseInputItemsAsync`  
  (출처: [EndpointRouteBuilderExtensions.Responses](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/EndpointRouteBuilderExtensions.Responses.cs#L21-L163), [IResponsesService](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/Responses/IResponsesService.cs#L15-L111))

## Python

- `messages_from_responses_input`
- `responses_to_run`
- `responses_session_id`
- `create_conversation_id`
- `create_response_id`
- `responses_from_run`
- `responses_from_streaming_run`

Python public boundary는 dict와 SSE string이며, 내부 구현은 `openai.types.responses` 모델을 사용하지만 이를 public API로 직접 노출하지 않는다. (출처: [Python hosting-responses __init__](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/agent_framework_hosting_responses/__init__.py#L1-L31), [Python parsing imports OpenAI SDK types](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/agent_framework_hosting_responses/_parsing.py#L23-L39), [Python response payload helper](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/agent_framework_hosting_responses/_parsing.py#L844-L859))

---

## Endpoint와 wire types

## .NET request wire model

`.NET` route-owning server는 server-side internal DTO `CreateResponse`를 사용한다. 이 타입은 다음 범주를 포함한다.

- input / agent / model / instructions
- generation controls: `max_output_tokens`, `temperature`, `top_p`, `reasoning`
- storage/streaming controls: `store`, `stream`, `background`, `stream_options`
- continuity controls: `previous_response_id`, `conversation`
- tool controls: `tools`, `tool_choice`, `parallel_tool_calls`, `max_tool_calls`
- metadata/safety/cache/prompt/service tier/truncation
- deprecated `user` field

즉 .NET은 route-owning server path에서 OpenAI-like request body를 상당히 넓게 받아들인다. (출처: [.NET CreateResponse model](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/Responses/Models/CreateResponse.cs#L10-L199))

## .NET streaming wire model

`.NET` streaming event union은 polymorphic `StreamingResponseEvent`로 정의되며, 최소한 다음 계열을 가진다.

- response lifecycle: `response.created`, `response.in_progress`, `response.completed`, `response.incomplete`, `response.failed`, `response.cancelled`
- item/content lifecycle: `response.output_item.added`, `response.output_item.done`, `response.content_part.added`, `response.content_part.done`
- delta families: `response.output_text.delta`, `response.output_text.done`, `response.function_call_arguments.delta`, `response.function_call_arguments.done`, `response.reasoning_summary_text.delta`, `response.reasoning_summary_text.done`
- workflow/function-approval 계열 이벤트

이는 OpenAI-compatible text streaming을 넘어 richer itemized streaming model을 노린 구현이다. (출처: [StreamingResponseEvent union](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/Responses/Models/StreamingResponseEvent.cs#L13-L33), [response lifecycle event classes](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/Responses/Models/StreamingResponseEvent.cs#L61-L190), [output item event classes](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/Responses/Models/StreamingResponseEvent.cs#L192-L250))

## Python wire model

Python helper는 public boundary를 **typed SDK object가 아니라** 다음 두 가지로 제한한다.

- request body: `Mapping[str, Any]`
- response body: `dict[str, Any]` 또는 `AsyncIterator[str]` SSE frames

내부적으로는 `OpenAIResponse`, `ResponseOutputItem`, `ResponseOutputMessage`, `ResponseOutputText`, `ResponseFunctionToolCall`, `ResponseFunctionToolCallOutputItem` 같은 OpenAI Python SDK types를 사용해 payload를 구성한다. 즉, helper 내부는 SDK-type-aware지만 host에 노출되는 건 dict/SSE string이다. (출처: [Python parsing imports](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/agent_framework_hosting_responses/_parsing.py#L23-L39), [responses_from_run boundary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/agent_framework_hosting_responses/_parsing.py#L199-L235), [SSE formatting helpers](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/agent_framework_hosting_responses/_parsing.py#L852-L859))

---

## Request mapping

## 입력 `input` 처리

### Python
Python parser는 `input`을 다음으로 해석한다.

- plain string → single user message
- list of loose content items (`input_text`, `input_image`, `input_file`) → buffered user message
- `{type:"message", role, content:[...]}` envelope → explicit role message
- `input_image`는 string 또는 object `image_url`
- `input_file`는 `file_url` 또는 `file_id`

unsupported item type은 `ValueError`로 reject된다. (출처: [messages_from_responses_input](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/agent_framework_hosting_responses/_parsing.py#L55-L124), [Python parsing tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/tests/hosting_responses/test_parsing.py#L31-L119))

### .NET
`.NET` helper는 `CreateResponse.Input.GetInputMessages()`를 돌며 `InputMessage.ToChatMessage()`로 변환한다. public helper는 request body를 한 번 parse한 뒤 `Messages`와 `Options`를 묶은 `OpenAIResponsesRunRequest`를 반환한다. (출처: [.NET ToAgentRunRequest](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/OpenAIResponses.cs#L45-L72), [OpenAIResponsesRunRequest](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/OpenAIResponsesRunRequest.cs#L27-L49))

## request settings → run options 매핑

### .NET: strict default
`.NET`의 기본 `RunOptionsFactory`는 다음 request settings가 오면 `NotSupportedException`을 던진다.

- `temperature`
- `top_p`
- `max_output_tokens`
- `instructions`
- `tools`
- `tool_choice`

즉 self-contained agent endpoint에서 caller가 silently agent configuration을 override하지 못하게 막는다. 단 `model`은 informational field라 unsupported setting으로 보지 않는다. (출처: [OpenAIResponsesMapOptions remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/OpenAIResponsesMapOptions.cs#L20-L31), [RejectRequestSettings implementation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/OpenAIResponsesMapOptions.cs#L42-L99))

### Python: permissive default
Python helper는 transport-owned keys(`input`, `stream`, `previous_response_id`, `conversation_id`)를 제외한 non-`None` fields를 거의 그대로 `options`에 넣고, 다만 다음 remap을 수행한다.

- `max_output_tokens` → `max_tokens`
- `parallel_tool_calls` → `allow_multiple_tool_calls`

즉 기본 정책이 더 permissive해서, production host는 별도의 allow/deny filter를 반드시 가져야 한다. sample도 `ALLOWED_REQUEST_OPTIONS`를 두고 options를 다시 좁힌다. (출처: [Python option remap table](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/agent_framework_hosting_responses/_parsing.py#L45-L53), [responses_to_run](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/agent_framework_hosting_responses/_parsing.py#L173-L196), [Python sample option filter](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/samples/04-hosting/af-hosting/local_responses/app.py#L127-L133))

---

## 상세 실행 흐름

## .NET helper path

1. request body를 `JsonElement`로 받는다.
2. `ToAgentRunRequest()`가 body를 internal `CreateResponse` DTO로 deserialize한다.
3. missing `input` 또는 JSON parse 실패는 `ArgumentException`으로 surface 된다.
4. caller는 parsed request에서 `GetSessionStoreId()`를 별도로 호출해 continuation candidate key를 얻는다.
5. caller가 agent/workflow run을 수행한다.
6. `WriteResponse()` 또는 `WriteResponseStreamAsync()`가 Responses JSON/SSE를 만든다. (출처: [.NET ToAgentRunRequest parse path](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/OpenAIResponses.cs#L45-L72), [GetSessionStoreId separation and trust boundary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/OpenAIResponses.cs#L120-L150), [WriteResponse and WriteResponseStreamAsync](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/OpenAIResponses.cs#L74-L118))

## .NET route-owning path

1. `MapOpenAIResponses`가 `/responses` group을 만든다.
2. POST create, GET response, POST cancel, DELETE response, GET input_items를 매핑한다.
3. `ResponsesHttpHandler.CreateResponseAsync`가 request validation을 먼저 수행한다.
4. `shouldStream = query ? stream : request.Stream ? false` 로 streaming/non-streaming을 결정한다.
5. streaming이면 `SseJsonResult<StreamingResponseEvent>`를 반환하고, non-streaming이면 final `Response` object를 반환한다.
6. route-owning implementation은 internal `InMemoryResponsesService`를 통해 response state, background run, cached streaming replay, cancel/delete까지 가진다. (출처: [MapOpenAIResponses route map](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/EndpointRouteBuilderExtensions.Responses.cs#L71-L109), [ResponsesHttpHandler create/get/cancel/delete](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/Responses/ResponsesHttpHandler.cs#L29-L181), [InMemoryResponsesService responsibilities](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/Responses/InMemoryResponsesService.cs#L17-L25))

## Python helper path

1. app route가 raw `dict[str, Any]` body를 받는다.
2. `responses_to_run(body)`가 messages/options/stream flag를 만든다.
3. `responses_session_id(body)`가 `(id, is_conversation_id)`를 리턴한다.
4. route가 session lookup/save policy를 직접 결정한다.
5. non-streaming이면 `responses_from_run(...)`을 JSONResponse에 넣고, streaming이면 `responses_from_streaming_run(...)`을 `StreamingResponse(text/event-stream)`에 넣는다. (출처: [Python helper README example](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/README.md#L22-L55), [Python sample route](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/samples/04-hosting/af-hosting/local_responses/app.py#L116-L192))

---

## 상태·영속화

## Continuation 모델: immutable snapshot vs mutable head

이 기능에서 가장 중요한 상태 개념은 `previous_response_id`와 `conversation`의 차이다.

- `previous_response_id`는 **immutable continuation snapshot**이다.
  - 여러 요청이 같은 prior response에서 갈라질 수 있다.
  - 각 branch는 새 `resp_*` id 아래에 저장된다.
- `conversation`/`conversation_id`는 **mutable head**다.
  - 같은 stable id를 한 요청만 advance해야 한다.
  - helper는 locking이나 optimistic concurrency control을 제공하지 않는다.

Python README는 이를 직접 설명하고, .NET helper도 `PreviousResponseId`는 stable partition key가 아니고 `ConversationId`가 stable key라고 설명한다. (출처: [Python mutable head note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/README.md#L57-L60), [.NET OpenAIResponsesRunRequest docs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/OpenAIResponsesRunRequest.cs#L39-L49), [.NET GetSessionStoreId remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/OpenAIResponses.cs#L131-L142))

## .NET route-owning server의 response/conversation state

`.NET` `InMemoryResponsesService`는 `ResponseState`를 memory cache에 유지하며 다음을 가진다.

- final `Response`
- original `CreateResponse`
- streaming updates list
- output item index map
- optional completion task / cancellation token source

이 서비스는 `conversation`과 `previous_response_id`의 상호배타성을 validate하고, conversation storage가 있으면 해당 conversation 존재 여부를 먼저 확인한다. 실행 중에는 conversation history를 prepend하고, 완료 후 input items와 output items를 conversation storage에 추가한다. (출처: [ResponseState structure](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/Responses/InMemoryResponsesService.cs#L27-L128), [ValidateRequestAsync mutual exclusivity and conversation existence](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/Responses/InMemoryResponsesService.cs#L150-L181), [load conversation history and append items after success](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/Responses/InMemoryResponsesService.cs#L442-L489))

## Python helper의 state boundary

Python helper package 자체는 session store를 만들지 않는다. sample은 `AgentState` 또는 `WorkflowState`와 core `SessionStore`/`FileSessionStore`를 조합한다. 즉 Responses helper는 state를 “관리”하지 않고 **host가 state를 붙일 수 있게 해 주는 seam**만 제공한다. (출처: [Python hosting-responses README state note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/README.md#L62-L65), [Python sample AgentState + FileSessionStore](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/samples/04-hosting/af-hosting/local_responses/app.py#L74-L111))

---

## Tool calls

## .NET: streaming-time event generation

`.NET` streaming renderer는 `AgentResponseUpdate` content type에 따라 generator를 선택한다.

- `TextContent` → assistant message events
- `TextReasoningContent` → reasoning events
- `FunctionCallContent` → function call events
- `FunctionResultContent` → function result events
- `ToolApprovalRequestContent` / `ToolApprovalResponseContent` → approval events
- image/audio/file/hosted file content → corresponding media/file events

즉 .NET은 streaming path에서도 단순 text delta만이 아니라 tool/reasoning/media를 **typed event union**으로 계속 노출한다. (출처: [AgentResponseUpdateExtensions generator switch](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/Responses/AgentResponseUpdateExtensions.cs#L192-L220), [completed response construction after streamed items](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/Responses/AgentResponseUpdateExtensions.cs#L225-L279))

## Python: final payload item families

Python final renderer는 `Content.type`에 따라 다양한 `ResponseOutputItem` family로 매핑한다.

- `text` → assistant message
- `text_reasoning` → `reasoning`
- `function_call` → `function_call`
- `function_result` → `function_call_output`
- `code_interpreter_tool_call` / result
- `image_generation_tool_call` / result
- `mcp_server_tool_call` / result
- `shell_tool_call` / result
- `function_approval_request` / `function_approval_response`
- `data` / `uri` / `hosted_file` → media/file style items

즉 Python은 **final JSON payload의 item family 폭**이 넓지만, streaming helper는 이 richness를 full itemized delta로 내보내지는 않는다. (출처: [Python content-to-output-items switch](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/agent_framework_hosting_responses/_parsing.py#L324-L418), [reasoning/function call/result mapping](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/agent_framework_hosting_responses/_parsing.py#L485-L533), [MCP/shell/approval/media mappings](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/agent_framework_hosting_responses/_parsing.py#L577-L679))

## Host 책임과의 접점

Tool choice나 exposed tools 자체를 request에서 얼마까지 받아들일지는 host 정책이다.

- .NET helper 기본값은 request-supplied `tools`/`tool_choice`를 거부한다.
- Python helper는 일단 option으로 pass-through하므로 host route가 허용 목록을 다시 정해야 한다. (출처: [.NET RejectRequestSettings](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/OpenAIResponsesMapOptions.cs#L54-L96), [Python responses_to_run](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/agent_framework_hosting_responses/_parsing.py#L185-L196))

---

## 오류 매핑

## .NET route-owning server

### validation 단계
`ResponsesHttpHandler`는 validation error를 `ResponseErrorCodes`로 HTTP status에 매핑한다.

- `conversation_not_found` → 404
- 그 외 validation failures → 400

에러 body는 OpenAI-style `ErrorResponse` 형식이다. (출처: [ResponseErrorCodes](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/Responses/ResponseErrorCodes.cs#L12-L38), [ResponsesHttpHandler validation mapping](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/Responses/ResponsesHttpHandler.cs#L37-L56))

### create/get/cancel/delete 단계
- non-streaming success: `200 OK`
- queued background response: `202 Accepted`
- unexpected exception: `500 Problem`
- cancel/delete invalid operations: `400` 또는 `404` depending on path  
  (출처: [create response success/failure mapping](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/Responses/ResponsesHttpHandler.cs#L75-L97), [cancel mapping](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/Responses/ResponsesHttpHandler.cs#L138-L160), [delete and list-input-items mapping](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/Responses/ResponsesHttpHandler.cs#L163-L226))

## Python helper

Python helper는 HTTP status mapping을 하지 않는다.

- parse/shape 문제 → `ValueError`
- streaming iteration/finalizer failure → `response.failed` SSE event
- host route가 `HTTPException`/status code를 정해야 한다

sample은 invalid body를 400으로 바꾸고, 정상 non-streaming은 200 JSON, streaming은 `text/event-stream`으로 만든다. (출처: [Python helper README](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/README.md#L19-L21), [Python sample invalid body -> 400](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/samples/04-hosting/af-hosting/local_responses/app.py#L116-L123), [Python streaming failed event](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/agent_framework_hosting_responses/_parsing.py#L930-L956))

---

## 확장점

## .NET
- `OpenAIResponsesMapOptions.RunOptionsFactory`
  - strict reject 유지
  - 일부 fields만 map
  - 완전 custom `AgentRunOptions` 생성
- route-owning path에서는 custom `IResponsesService` registration도 가능하다. endpoint builder는 DI의 `IResponsesService`를 사용한다. (출처: [OpenAIResponsesMapOptions property](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/OpenAIResponsesMapOptions.cs#L13-L40), [MapOpenAIResponses() using DI IResponsesService](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/EndpointRouteBuilderExtensions.Responses.cs#L124-L163))

## Python
- app route는
  - allowed request options 재정의
  - session id trust policy
  - session store backend
  - workflow cursor model
  - auth middleware
  - background work
  를 모두 자유롭게 교체할 수 있다. (출처: [Python hosting-responses README ownership](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/README.md#L19-L21), [Python sample route custom policy](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/samples/04-hosting/af-hosting/local_responses/app.py#L127-L133))

---

## 동시성·스트리밍·취소

## 동시성

- `conversation`/`conversation_id`는 mutable head라서 host가 single-writer coordination을 제공해야 한다.
- `previous_response_id`는 branch-friendly immutable snapshot이므로 동시 분기가 가능하다.
- helper는 conversation-level locking을 제공하지 않는다. (출처: [Python mutable head note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/README.md#L57-L60), [.NET GetSessionStoreId remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/OpenAIResponses.cs#L131-L142))

## 스트리밍

### .NET
- route-owning path는 `SseJsonResult<T>`를 사용해 `text/event-stream`, `Cache-Control: no-cache,no-store`, `Connection: keep-alive`, `Content-Encoding: identity`와 buffering disable을 설정한다.
- event payload는 typed `StreamingResponseEvent`를 JSON serialize한 `event:`/`data:` framing으로 내려간다. (출처: [SseJsonResult headers and buffering](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/SseJsonResult.cs#L42-L58), [OpenAIResponses.WriteResponseStreamAsync frame format](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/OpenAIResponses.cs#L101-L117))

### Python
- helper는 `event: <type>\ndata: <json>\n\n` 문자열을 직접 생성한다.
- public streaming helper는 `response.created`, text deltas, terminal completed/failed만 방출한다. (출처: [Python SSE formatting](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/agent_framework_hosting_responses/_parsing.py#L852-L859), [Python responses_from_streaming_run](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/agent_framework_hosting_responses/_parsing.py#L862-L956))

## 취소

### .NET
- route-owning server는 background response만 cancel 가능하며, 그렇지 않으면 `InvalidOperationException`을 400으로 매핑한다.
- cached streaming replay는 `GetResponseStreamingAsync(startingAfter)`로 일부 offset 이후부터 다시 읽을 수 있다. (출처: [CancelResponseAsync rules](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/Responses/InMemoryResponsesService.cs#L260-L286), [IResponsesService GetResponseStreamingAsync](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/Responses/IResponsesService.cs#L63-L72))
- `background=true`일 때 `CreateResponseAsync`는 실행을 시작하고 queued response를 즉시 반환한다. (출처: [background create behavior](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/Responses/InMemoryResponsesService.cs#L193-L210))

### Python
- helper package는 cancel API나 background response store를 제공하지 않는다.
- “background work”는 명시적으로 app/framework 책임이다. (출처: [Python hosting-responses README responsibilities](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/README.md#L19-L21))

---

## 오류·검증·보안

## 검증

### .NET
- malformed JSON 또는 missing `input`는 helper에서 `ArgumentException`
- route-owning server는 `conversation` and `previous_response_id` 동시 지정 금지
- optional conversation store가 있으면 referenced conversation 존재를 선검증  
  (출처: [.NET ToAgentRunRequest parse errors](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/OpenAIResponses.cs#L47-L60), [.NET ValidateRequestAsync](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/Responses/InMemoryResponsesService.cs#L154-L181))

### Python
- `input`은 non-empty string 또는 non-empty list여야 한다.
- unsupported `input_*` shapes는 `ValueError`
- nonstandard id prefix는 reject하지 않고 warning만 준다. (출처: [Python messages_from_responses_input validation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/agent_framework_hosting_responses/_parsing.py#L82-L124), [Python responses_session_id warnings](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/agent_framework_hosting_responses/_parsing.py#L137-L170))

## 보안

- request-derived continuation id는 둘 다 **untrusted candidate key**다.
- host는 caller를 authenticate하고 그 id를 authorized principal에 bind한 뒤에만 session/checkpoint key로 써야 한다.
- .NET helper는 이 trust boundary를 보이기 위해 `GetSessionStoreId(...)`를 `ToAgentRunRequest(...)`와 분리했다.
- Python helper도 session id extraction을 run parsing과 분리한다. (출처: [.NET trust boundary remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/OpenAIResponses.cs#L25-L29), [.NET separate GetSessionStoreId](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/OpenAIResponses.cs#L131-L145), [Python session id helper docs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/agent_framework_hosting_responses/_parsing.py#L137-L149))

---

## .NET/Python 차이

1. **Packaging**
   - .NET: helper + route-owning server 동시 제공
   - Python: helper-only  
   (출처: [ADR-0032 current .NET stack](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0032-dotnet-hosting-protocol-helpers.md#L21-L33), [Python README responsibilities](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/README.md#L19-L21))

2. **Boundary type**
   - .NET helper public boundary: `JsonElement`
   - Python helper public boundary: `dict[str, Any]` + SSE strings  
   (출처: [.NET OpenAIResponses helper](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/OpenAIResponses.cs#L80-L98), [Python __all__ exports](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/agent_framework_hosting_responses/__init__.py#L22-L31))

3. **Default request mapping policy**
   - .NET: strict reject
   - Python: permissive pass-through/remap  
   (출처: [.NET RejectRequestSettings](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/OpenAIResponsesMapOptions.cs#L54-L99), [Python responses_to_run](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/agent_framework_hosting_responses/_parsing.py#L185-L196))

4. **Streaming richness**
   - .NET: full typed event union
   - Python: created + text delta + terminal event 중심  
   (출처: [.NET StreamingResponseEvent union](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/Responses/Models/StreamingResponseEvent.cs#L13-L33), [Python responses_from_streaming_run](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/agent_framework_hosting_responses/_parsing.py#L881-L956))

5. **Background/cancel**
   - .NET route-owning path: built-in queued background responses와 cancel/delete 지원
   - Python helper path: host responsibility  
   (출처: [InMemoryResponsesService background/cancel](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/Responses/InMemoryResponsesService.cs#L193-L210), [same file cancel](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/Responses/InMemoryResponsesService.cs#L260-L301), [Python README ownership](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/README.md#L19-L21))

---

## .NET 구현과 테스트

### 구현 요약
- helper façade: `OpenAIResponses`
- route-owning endpoint: `MapOpenAIResponses`
- internal execution/storage service: `IResponsesService`, default `InMemoryResponsesService`
- HTTP handler: `ResponsesHttpHandler`
- SSE result writer: `SseJsonResult<T>`  
  (출처: [OpenAIResponses.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/OpenAIResponses.cs#L15-L164), [IResponsesService](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/Responses/IResponsesService.cs#L15-L111), [ResponsesHttpHandler](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/Responses/ResponsesHttpHandler.cs#L13-L227), [SseJsonResult](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/SseJsonResult.cs#L15-L75))

### Conformance tests
- `OpenAIResponsesHostingTests`는 **real server process나 live model 없이** TestServer + deterministic mock chat client로 in-process hosting을 검증한다.
- 검증 항목:
  - non-streaming Responses-shaped JSON
  - SSE create/completed
  - multi-turn continuity
  - `previous_response_id` independent branch
  - stable `conversation` mutable head
  - malformed body 400
  - workflow run/resume path  
  (출처: [OpenAIResponsesHostingTests class comment](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests/OpenAIResponsesHostingTests.cs#L22-L30), [non-streaming/streaming tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests/OpenAIResponsesHostingTests.cs#L39-L76), [session continuity and branches](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests/OpenAIResponsesHostingTests.cs#L79-L147), [malformed body and workflow resume](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests/OpenAIResponsesHostingTests.cs#L157-L194))

---

## Python 구현과 테스트

### 구현 요약
- parser/render helper는 `_parsing.py` 한 곳에 모여 있다.
- public package는 helper 함수만 export한다.
- sample route는 FastAPI + `AgentState`/`FileSessionStore`를 조합해 `/responses`를 직접 만든다.  
  (출처: [Python hosting-responses __init__](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/agent_framework_hosting_responses/__init__.py#L1-L31), [Python sample app](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/samples/04-hosting/af-hosting/local_responses/app.py#L1-L192))

### Conformance tests
- `test_http_round_trip.py`는 `httpx.AsyncClient` + `ASGITransport`로 POST→FastAPI route→JSON/SSE 전체를 **실소켓 없이** 검증한다.
- 검증 항목:
  - 200 JSON payload
  - invalid input 400
  - SSE created/delta/completed
  - three-turn `previous_response_id` continuity  
  (출처: [HTTP round-trip class comment](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/tests/hosting_responses/test_http_round_trip.py#L3-L12), [round-trip app wiring](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/tests/hosting_responses/test_http_round_trip.py#L121-L169), [non-streaming and streaming tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/tests/hosting_responses/test_http_round_trip.py#L197-L234), [session continuity test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/tests/hosting_responses/test_http_round_trip.py#L236-L260))
- `test_parsing.py`는 parser와 renderer semantics를 고정한다.
  - input item variants
  - session id precedence
  - multimodal/tool output item mapping
  - streaming completed/failed event behavior  
  (출처: [input parsing tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/tests/hosting_responses/test_parsing.py#L31-L119), [session id and run helper tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/tests/hosting_responses/test_parsing.py#L122-L177), [output item mapping tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/tests/hosting_responses/test_parsing.py#L178-L227), [streaming completed/failed tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/tests/hosting_responses/test_parsing.py#L243-L317))

---

## 문서 차이

## 1. .NET sample의 `conversationId` 전달 불일치
`.NET` app-owned sample은 주석에서 `conversation`이 mutable head라고 설명하며 `conversationId`와 `saveId`를 계산하지만, 실제 render 호출에서는 `WriteResponseStreamAsync(..., responseId, responseId, ...)` 및 `WriteResponse(..., responseId, responseId)`처럼 computed `conversationId`가 아니라 `responseId`를 넘긴다. helper/library contract와 sample commentary가 완전히 일치하지 않는 부분이므로, **코드 우선**으로 보면 sample-level inconsistency가 있다. (출처: [.NET sample commentary and computed ids](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/samples/04-hosting/af-hosting/local_responses/Server/Program.cs#L72-L80), [.NET sample actual render calls](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/samples/04-hosting/af-hosting/local_responses/Server/Program.cs#L84-L104))

## 2. helper-first 원칙과 .NET route-owning server의 공존
ADR-0032는 helper-first 방향을 따르면서도, 기존 `.NET` stack에 이미 `MapOpenAIResponses`라는 route-owning server가 있기 때문에 helper façade를 **같은 package 안에 추가**하는 방식을 택했다고 설명한다. 따라서 “helper-first”라는 문구만 보면 Python과 같은 pure helper package를 기대할 수 있지만, 실제 .NET 코드는 helper와 batteries-included server를 함께 가진다. (출처: [ADR-0032 context and capability gap](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0032-dotnet-hosting-protocol-helpers.md#L21-L33), [ADR-0032 decision outcome](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0032-dotnet-hosting-protocol-helpers.md#L72-L98))

---

## Java 결정

## 권장 모듈 구조

### 1. protocol adapter
- `agent-framework-hosting-openai-responses`
- public boundary는 **Jackson `JsonNode` 또는 `Map<String,Object>`** 수준으로 유지
- request parser / final response writer / SSE writer / session-id extractor 포함

`.NET`의 `JsonElement` boundary와 Python의 dict boundary 모두 “vendor SDK type을 host public API에 직접 노출하지 않는다”는 공통점을 가지므로, Java도 이 방향이 좋다. (출처: [.NET JsonElement façade](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/OpenAIResponses.cs#L80-L98), [Python dict boundary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/agent_framework_hosting_responses/__init__.py#L22-L31))

### 2. optional host binder
- `agent-framework-hosting-openai-responses-spring`
- Spring MVC/WebFlux용 `/responses` route binder
- status code mapping, SSE `text/event-stream`, exception mapper

`.NET`처럼 batteries-included endpoint가 필요하면 protocol adapter와는 **별도 artifact**로 두는 편이 generic hosting core와 덜 섞인다.

### 3. default request mapping policy
Java 기본 정책은 **.NET과 같은 strict default**를 권장한다.

- caller-supplied `tools`, `tool_choice`, `instructions`, sampling controls를 기본 거부
- host가 explicit allowlist 또는 custom mapper로 opt-in

이유는 Python식 permissive default가 self-hosted production endpoint에서 무심코 agent 설정 override를 허용할 수 있기 때문이다. (출처: [.NET RejectRequestSettings](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/OpenAIResponsesMapOptions.cs#L54-L96), [Python permissive mapping](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/agent_framework_hosting_responses/_parsing.py#L185-L196))

### 4. streaming event scope
Java는 최소 두 모드를 고려하는 것이 좋다.

- **simple mode**: Python처럼 `response.created` + text delta + terminal event
- **full mode**: .NET처럼 output item / content part / function args / reasoning delta까지 포함

이를 separate renderer profile이나 option으로 나누는 것이 구현 비용과 호환성 균형상 유리하다.

---

## 구체 acceptance scenarios

1. **Request parsing**
   - `input`이 string, loose content item list, explicit message envelope 모두 허용되어야 한다.
   - malformed body나 missing `input`은 clean validation error가 나와야 한다.

2. **Continuation semantics**
   - `previous_response_id`는 immutable branch point여야 한다.
   - 같은 prior response에서 두 branch를 시작해도 서로 state가 섞이면 안 된다.
   - `conversation`/`conversation_id`는 mutable head이므로 host가 single-writer coordination을 강제할 수 있어야 한다.

3. **Request option policy**
   - strict-default host는 caller-supplied `tools`/`tool_choice`/sampling fields를 거부해야 한다.
   - permissive host는 explicit allowlist로 필요한 fields만 agent run options에 넣어야 한다.

4. **Streaming**
   - SSE는 최소 `response.created`와 terminal `response.completed` 또는 `response.failed`를 내려야 한다.
   - text streaming이 있는 경우 delta event ordering이 보장되어야 한다.
   - rich mode에서는 function/reasoning/media deltas가 loss 없이 itemized event로 나와야 한다.

5. **Tool calls**
   - final response payload는 assistant message만이 아니라 `function_call`, `function_call_output`, reasoning, media/file item families를 표현할 수 있어야 한다.
   - host가 tool exposure policy를 통제할 수 있어야 한다.

6. **Error mapping**
   - validation failures는 request fault로, execution failures는 server fault로 구분되어야 한다.
   - streaming iteration/finalizer failure가 있을 경우 terminal failed event가 partial output과 함께 내려가야 한다.

7. **Host ownership**
   - authn/authz, trusted session binding, durable store, background work, cancel semantics은 framework helper 밖에 남아 있어야 한다.

---

## Source inventory

### docs
- [docs/decisions/0032-dotnet-hosting-protocol-helpers.md](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0032-dotnet-hosting-protocol-helpers.md#L14-L177)
- [docs/specs/002-python-hosting-channels.md](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/specs/002-python-hosting-channels.md#L210-L253)

### .NET production source
- [dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/OpenAIResponses.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/OpenAIResponses.cs#L15-L164)
- [dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/OpenAIResponsesRunRequest.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/OpenAIResponsesRunRequest.cs#L8-L49)
- [dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/OpenAIResponsesMapOptions.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/OpenAIResponsesMapOptions.cs#L13-L99)
- [dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/EndpointRouteBuilderExtensions.Responses.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/EndpointRouteBuilderExtensions.Responses.cs#L21-L163)
- [dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/Responses/IResponsesService.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/Responses/IResponsesService.cs#L15-L111)
- [dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/Responses/InMemoryResponsesService.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/Responses/InMemoryResponsesService.cs#L17-L577)
- [dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/Responses/ResponsesHttpHandler.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/Responses/ResponsesHttpHandler.cs#L13-L227)
- [dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/Responses/ResponseErrorCodes.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/Responses/ResponseErrorCodes.cs#L7-L38)
- [dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/SseJsonResult.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/SseJsonResult.cs#L15-L75)
- [dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/Responses/Models/CreateResponse.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/Responses/Models/CreateResponse.cs#L10-L200)
- [dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/Responses/Models/StreamingResponseEvent.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/Responses/Models/StreamingResponseEvent.cs#L9-L280)
- [dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/Responses/AgentResponseUpdateExtensions.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/Responses/AgentResponseUpdateExtensions.cs#L31-L320)
- [dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/Microsoft.Agents.AI.Hosting.OpenAI.csproj](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/Microsoft.Agents.AI.Hosting.OpenAI.csproj#L3-L38)

### .NET tests and samples
- [dotnet/tests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests/OpenAIResponsesHostingTests.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests/OpenAIResponsesHostingTests.cs#L22-L277)
- [dotnet/samples/04-hosting/af-hosting/README.md](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/samples/04-hosting/af-hosting/README.md#L1-L53)
- [dotnet/samples/04-hosting/af-hosting/local_responses/Server/Program.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/samples/04-hosting/af-hosting/local_responses/Server/Program.cs#L56-L113)

### Python production source
- [python/packages/hosting-responses/README.md](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/README.md#L1-L65)
- [python/packages/hosting-responses/agent_framework_hosting_responses/__init__.py](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/agent_framework_hosting_responses/__init__.py#L1-L31)
- [python/packages/hosting-responses/agent_framework_hosting_responses/_parsing.py](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/agent_framework_hosting_responses/_parsing.py#L1-L960)
- [python/PACKAGE_STATUS.md](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L37-L41)

### Python tests and samples
- [python/packages/hosting-responses/tests/hosting_responses/test_http_round_trip.py](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/tests/hosting_responses/test_http_round_trip.py#L1-L260)
- [python/packages/hosting-responses/tests/hosting_responses/test_parsing.py](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/tests/hosting_responses/test_parsing.py#L1-L317)
- [python/samples/04-hosting/af-hosting/README.md](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/samples/04-hosting/af-hosting/README.md#L1-L37)
- [python/samples/04-hosting/af-hosting/local_responses/app.py](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/samples/04-hosting/af-hosting/local_responses/app.py#L1-L209)

## 결론

OpenAI Responses-compatible hosting은 Agent Framework에서 가장 protocol-heavy한 hosting seam 중 하나이며, 실제 구현은 “동일 기능의 단순 포팅”이 아니다. `.NET`은 strict mapping + rich event union + route-owning server까지 포함하는 폭넓은 구현이고, Python은 helper-only + host-owned policy라는 더 좁고 명시적인 경계를 가진다. Java는 이 둘 중 **public boundary의 단순성은 Python을, 기본 정책의 보수성은 .NET을** 따르는 구성이 가장 무난하다. 즉, helper API는 JSON tree/dict 경계로 유지하되, 기본 request mapping은 strict-default로 두고, batteries-included route binder는 별도 artifact로 분리하는 편이 장기적으로 가장 안정적이다. (출처: [ADR-0032 decision outcome](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0032-dotnet-hosting-protocol-helpers.md#L72-L98), [Python hosting-responses README](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/README.md#L19-L21), [.NET strict default mapping](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/OpenAIResponsesMapOptions.cs#L54-L99), [Python permissive default mapping](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/agent_framework_hosting_responses/_parsing.py#L185-L196))