# 21. OpenAI Responses-compatible hosting

## Scope

This document covers only **OpenAI Responses-compatible hosting** in Microsoft Agent Framework. The subjects are as follows.

- endpoint surface and route shape
- request/response wire types
- mapping of request body → agent/workflow run values
- streaming SSE event model
- `previous_response_id` / `conversation`-based continuation state
- tool call / reasoning / multimodal output mapping
- error mapping, validation, security boundary
- .NET/Python implementation differences
- conformance test coverage
- Java design decisions

Conversely, the generic hosting lifecycle and DI/store/checkpoint abstractions themselves fall within the scope of `20-hosting.md`; this document reduces duplication and describes only the parts that directly interface with the OpenAI Responses protocol. Additionally, details of A2A, AG-UI, MCP, Foundry hosted runtime, DevUI, and Telegram/ChatKit channels are within the scope of separate documents. (Source: [ADR-0032 scope](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0032-dotnet-hosting-protocol-helpers.md#L144-L148), [Python hosting spec packages table](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/specs/002-python-hosting-channels.md#L64-L73))

## Summary

OpenAI Responses-compatible hosting has **different packaging philosophies** in the two languages. .NET provides both a **helper-only façade** (`OpenAIResponses`) and a **route-owning server** (`MapOpenAIResponses` + internal `IResponsesService`) within a single package, while Python provides only a **helper-only** package, leaving FastAPI/Starlette/Django/Azure Functions to own the route, auth, status codes, and background work. The .NET public helper uses a `JsonElement` boundary and adopts a **strict default** in which the default `RunOptionsFactory` rejects request-supplied generation/tool settings. The Python helper uses a dict/SSE string boundary and is a **permissive default** that passes most request fields other than transport fields through to `options`, so the host route must separately filter what is actually permitted. Streaming also differs significantly: .NET has a rich event union including `response.created`, `response.in_progress`, `response.output_item.*`, `response.content_part.*`, `response.output_text.delta`, `response.function_call_arguments.*`, and `response.completed`, whereas the Python helper emits essentially only `response.created`, `response.output_text.delta`, and the terminal `response.completed`/`response.failed`. Both sides treat `previous_response_id` as an immutable snapshot branch point and `conversation`/`conversation_id` as a mutable head in the continuation model. (Source: [.NET OpenAIResponses helper](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/OpenAIResponses.cs#L15-L30), [.NET route-owning endpoints](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/EndpointRouteBuilderExtensions.Responses.cs#L60-L109), [ADR-0032 existing route-owning server](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0032-dotnet-hosting-protocol-helpers.md#L21-L33), [Python hosting-responses README](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/README.md#L19-L21), [.NET map options strict default](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/OpenAIResponsesMapOptions.cs#L20-L31), [Python request option remap/pass-through](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/agent_framework_hosting_responses/_parsing.py#L45-L53), [Python responses_to_run](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/agent_framework_hosting_responses/_parsing.py#L173-L196), [.NET streaming event union](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/Responses/Models/StreamingResponseEvent.cs#L13-L33), [Python streaming SSE helper](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/agent_framework_hosting_responses/_parsing.py#L862-L956), [.NET session-id trust boundary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/OpenAIResponses.cs#L120-L150), [Python mutable-head note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/README.md#L57-L60))

---

## Purpose

The purpose of this feature is to provide an **HTTP/SSE surface compatible with the OpenAI Responses API** while allowing the Agent Framework target to be used as is. Here, “compatibility” includes not just simple text completion but also the following.

- processing of request `input` as a string, list-of-items, or message envelope
- diverse output item families such as reasoning/tool/media/file
- SSE-based streaming lifecycle
- `previous_response_id` or explicit `conversation` continuity
- surfacing of tool calls, tool results, and approval-type events
- support for both route-owning server and app-owned route usage patterns

The .NET ADR describes this helper surface as an “app-owned routing counterpart”, and the Python README also states that “FastAPI/Starlette/Django/Azure Functions code owns route registration, authentication, status codes, response construction, and background work”. (Source: [.NET OpenAIResponses summary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/OpenAIResponses.cs#L15-L23), [ADR-0032 public surface](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0032-dotnet-hosting-protocol-helpers.md#L78-L98), [Python hosting-responses README](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/README.md#L3-L21))

## Boundary

### What this feature does

- Parses OpenAI Responses-shaped requests and converts them into agent/workflow run values.
- Renders Agent Framework results as a Responses-shaped JSON payload or SSE stream.
- **Exposes** continuation ids (`previous_response_id`, `conversation`).
- When a route-owning implementation is present, it can additionally provide response CRUD, streaming replay, and input item listing (.NET). (Source: [.NET IResponsesService](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/Responses/IResponsesService.cs#L15-L111), [.NET endpoint mapping](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/EndpointRouteBuilderExtensions.Responses.cs#L84-L107))

### What this feature does not do

- caller authentication / authorization
- binding a request-derived id as a trusted store key
- per-conversation single-writer coordination
- durable background executor
- implementing the full set of vendor-specific non-Responses APIs
- checkpoint/session store choice

These responsibilities remain with the host application. The Python README states this explicitly, and the .NET sample README also explains the two patterns — `MapOpenAIResponses` and app-owned route — as distinct. (Source: [Python hosting-responses README responsibilities](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/README.md#L19-L21), [.NET af-hosting README two ways](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/samples/04-hosting/af-hosting/README.md#L9-L20))

---

## Maturity

| Item | Status |
|---|---|
| .NET `Microsoft.Agents.AI.Hosting.OpenAI` | `alpha` |
| Python `agent-framework-hosting-responses` | `alpha` |
| .NET route-owning server | internal package implementation, coexists with helper |
| Python route-owning server | absent; app/framework implements directly |

Source:
- [.NET Hosting.OpenAI csproj](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/Microsoft.Agents.AI.Hosting.OpenAI.csproj#L3-L9)
- [Python PACKAGE_STATUS](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L37-L41)
- [ADR-0032 current .NET state](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0032-dotnet-hosting-protocol-helpers.md#L21-L24)

---

## Public APIs and types

## .NET

### Helper façade
- `OpenAIResponses.ToAgentRunRequest(JsonElement body, OpenAIResponsesMapOptions? mapOptions = null)`
- `OpenAIResponses.WriteResponse(AgentResponse response, string responseId, string? conversationId = null)`
- `OpenAIResponses.WriteResponseStreamAsync(IAsyncEnumerable<AgentResponseUpdate> updates, string responseId, string? conversationId = null, CancellationToken ct = default)`
- `OpenAIResponses.GetSessionStoreId(OpenAIResponsesRunRequest request)`
- `OpenAIResponses.CreateResponseId()`
- `OpenAIResponsesRunRequest` (`Messages`, `Options`, `PreviousResponseId`, `ConversationId`)
- `OpenAIResponsesMapOptions.RunOptionsFactory` (Source: [OpenAIResponses public API](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/OpenAIResponses.cs#L32-L164), [OpenAIResponsesRunRequest](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/OpenAIResponsesRunRequest.cs#L8-L49), [OpenAIResponsesMapOptions](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/OpenAIResponsesMapOptions.cs#L13-L99))

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
  (Source: [EndpointRouteBuilderExtensions.Responses](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/EndpointRouteBuilderExtensions.Responses.cs#L21-L163), [IResponsesService](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/Responses/IResponsesService.cs#L15-L111))

## Python

- `messages_from_responses_input`
- `responses_to_run`
- `responses_session_id`
- `create_conversation_id`
- `create_response_id`
- `responses_from_run`
- `responses_from_streaming_run`

The Python public boundary consists of dicts and SSE strings; the internal implementation uses `openai.types.responses` models but does not expose them directly as a public API. (Source: [Python hosting-responses __init__](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/agent_framework_hosting_responses/__init__.py#L1-L31), [Python parsing imports OpenAI SDK types](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/agent_framework_hosting_responses/_parsing.py#L23-L39), [Python response payload helper](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/agent_framework_hosting_responses/_parsing.py#L844-L859))

---

## Endpoint and wire types

## .NET request wire model

The `.NET` route-owning server uses the server-side internal DTO `CreateResponse`. This type includes the following categories.

- input / agent / model / instructions
- generation controls: `max_output_tokens`, `temperature`, `top_p`, `reasoning`
- storage/streaming controls: `store`, `stream`, `background`, `stream_options`
- continuity controls: `previous_response_id`, `conversation`
- tool controls: `tools`, `tool_choice`, `parallel_tool_calls`, `max_tool_calls`
- metadata/safety/cache/prompt/service tier/truncation
- deprecated `user` field

That is, .NET accepts a fairly broad range of OpenAI-like request bodies on the route-owning server path. (Source: [.NET CreateResponse model](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/Responses/Models/CreateResponse.cs#L10-L199))

## .NET streaming wire model

The `.NET` streaming event union is defined as a polymorphic `StreamingResponseEvent` and has at minimum the following families.

- response lifecycle: `response.created`, `response.in_progress`, `response.completed`, `response.incomplete`, `response.failed`, `response.cancelled`
- item/content lifecycle: `response.output_item.added`, `response.output_item.done`, `response.content_part.added`, `response.content_part.done`
- delta families: `response.output_text.delta`, `response.output_text.done`, `response.function_call_arguments.delta`, `response.function_call_arguments.done`, `response.reasoning_summary_text.delta`, `response.reasoning_summary_text.done`
- workflow/function-approval family events

This is an implementation aimed at a richer itemized streaming model that goes beyond OpenAI-compatible text streaming. (Source: [StreamingResponseEvent union](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/Responses/Models/StreamingResponseEvent.cs#L13-L33), [response lifecycle event classes](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/Responses/Models/StreamingResponseEvent.cs#L61-L190), [output item event classes](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/Responses/Models/StreamingResponseEvent.cs#L192-L250))

## Python wire model

The Python helper restricts the public boundary to the following two forms **rather than typed SDK objects**.

- request body: `Mapping[str, Any]`
- response body: `dict[str, Any]` or `AsyncIterator[str]` SSE frames

Internally, the payload is composed using OpenAI Python SDK types such as `OpenAIResponse`, `ResponseOutputItem`, `ResponseOutputMessage`, `ResponseOutputText`, `ResponseFunctionToolCall`, and `ResponseFunctionToolCallOutputItem`. That is, the helper interior is SDK-type-aware, but what is exposed to the host is dict/SSE string. (Source: [Python parsing imports](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/agent_framework_hosting_responses/_parsing.py#L23-L39), [responses_from_run boundary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/agent_framework_hosting_responses/_parsing.py#L199-L235), [SSE formatting helpers](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/agent_framework_hosting_responses/_parsing.py#L852-L859))

---

## Request mapping

## Processing the `input` field

### Python
The Python parser interprets `input` as the following.

- plain string → single user message
- list of loose content items (`input_text`, `input_image`, `input_file`) → buffered user message
- `{type:"message", role, content:[...]}` envelope → explicit role message
- `input_image` is a string or an object `image_url`
- `input_file` is a `file_url` or `file_id`

Unsupported item types are rejected with a `ValueError`. (Source: [messages_from_responses_input](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/agent_framework_hosting_responses/_parsing.py#L55-L124), [Python parsing tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/tests/hosting_responses/test_parsing.py#L31-L119))

### .NET
The `.NET` helper iterates over `CreateResponse.Input.GetInputMessages()` and converts each item with `InputMessage.ToChatMessage()`. The public helper parses the request body once and returns an `OpenAIResponsesRunRequest` that bundles `Messages` and `Options`. (Source: [.NET ToAgentRunRequest](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/OpenAIResponses.cs#L45-L72), [OpenAIResponsesRunRequest](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/OpenAIResponsesRunRequest.cs#L27-L49))

## Mapping request settings to run options

### .NET: strict default
The `.NET` default `RunOptionsFactory` throws a `NotSupportedException` when any of the following request settings are present.

- `temperature`
- `top_p`
- `max_output_tokens`
- `instructions`
- `tools`
- `tool_choice`

That is, it prevents callers from silently overriding agent configuration at a self-contained agent endpoint. However, `model` is treated as an informational field and is not considered an unsupported setting. (Source: [OpenAIResponsesMapOptions remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/OpenAIResponsesMapOptions.cs#L20-L31), [RejectRequestSettings implementation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/OpenAIResponsesMapOptions.cs#L42-L99))

### Python: permissive default
The Python helper places nearly all non-`None` fields other than transport-owned keys (`input`, `stream`, `previous_response_id`, `conversation_id`) into `options` as-is, but performs the following remappings.

- `max_output_tokens` → `max_tokens`
- `parallel_tool_calls` → `allow_multiple_tool_calls`

That is, the default policy is more permissive, so a production host must have a separate allow/deny filter. The sample also defines `ALLOWED_REQUEST_OPTIONS` and further narrows the options. (Source: [Python option remap table](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/agent_framework_hosting_responses/_parsing.py#L45-L53), [responses_to_run](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/agent_framework_hosting_responses/_parsing.py#L173-L196), [Python sample option filter](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/samples/04-hosting/af-hosting/local_responses/app.py#L127-L133))

---

## Detailed execution flow

## .NET helper path

1. Receives the request body as a `JsonElement`.
2. `ToAgentRunRequest()` deserializes the body into the internal `CreateResponse` DTO.
3. A missing `input` or JSON parse failure is surfaced as an `ArgumentException`.
4. The caller separately calls `GetSessionStoreId()` on the parsed request to obtain the continuation candidate key.
5. The caller performs the agent/workflow run.
6. `WriteResponse()` or `WriteResponseStreamAsync()` produces the Responses JSON/SSE. (Source: [.NET ToAgentRunRequest parse path](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/OpenAIResponses.cs#L45-L72), [GetSessionStoreId separation and trust boundary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/OpenAIResponses.cs#L120-L150), [WriteResponse and WriteResponseStreamAsync](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/OpenAIResponses.cs#L74-L118))

## .NET route-owning path

1. `MapOpenAIResponses` creates the `/responses` group.
2. Maps POST create, GET response, POST cancel, DELETE response, and GET input_items.
3. `ResponsesHttpHandler.CreateResponseAsync` performs request validation first.
4. Determines streaming/non-streaming via `shouldStream = query ? stream : request.Stream ? false`.
5. If streaming, returns `SseJsonResult<StreamingResponseEvent>`; if non-streaming, returns the final `Response` object.
6. The route-owning implementation manages response state, background run, cached streaming replay, and cancel/delete through the internal `InMemoryResponsesService`. (Source: [MapOpenAIResponses route map](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/EndpointRouteBuilderExtensions.Responses.cs#L71-L109), [ResponsesHttpHandler create/get/cancel/delete](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/Responses/ResponsesHttpHandler.cs#L29-L181), [InMemoryResponsesService responsibilities](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/Responses/InMemoryResponsesService.cs#L17-L25))

## Python helper path

1. The app route receives a raw `dict[str, Any]` body.
2. `responses_to_run(body)` produces the messages/options/stream flag.
3. `responses_session_id(body)` returns `(id, is_conversation_id)`.
4. The route determines the session lookup/save policy directly.
5. If non-streaming, places `responses_from_run(...)` in a JSONResponse; if streaming, places `responses_from_streaming_run(...)` in a `StreamingResponse(text/event-stream)`. (Source: [Python helper README example](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/README.md#L22-L55), [Python sample route](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/samples/04-hosting/af-hosting/local_responses/app.py#L116-L192))

---

## State and persistence

## Continuation model: immutable snapshot vs mutable head

The most important state concept in this feature is the difference between `previous_response_id` and `conversation`.

- `previous_response_id` is an **immutable continuation snapshot**.
  - Multiple requests can branch from the same prior response.
  - Each branch is stored under a new `resp_*` id.
- `conversation`/`conversation_id` is a **mutable head**.
  - Only one request must advance the same stable id.
  - The helper provides neither locking nor optimistic concurrency control.

The Python README explains this directly, and the .NET helper also states that `PreviousResponseId` is not a stable partition key whereas `ConversationId` is the stable key. (Source: [Python mutable head note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/README.md#L57-L60), [.NET OpenAIResponsesRunRequest docs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/OpenAIResponsesRunRequest.cs#L39-L49), [.NET GetSessionStoreId remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/OpenAIResponses.cs#L131-L142))

## Response/conversation state in the .NET route-owning server

The `.NET` `InMemoryResponsesService` maintains `ResponseState` in a memory cache and holds the following.

- final `Response`
- original `CreateResponse`
- streaming updates list
- output item index map
- optional completion task / cancellation token source

This service validates the mutual exclusivity of `conversation` and `previous_response_id` and, if a conversation storage is present, first checks for the existence of that conversation. During execution it prepends the conversation history, and upon completion adds the input items and output items to the conversation storage. (Source: [ResponseState structure](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/Responses/InMemoryResponsesService.cs#L27-L128), [ValidateRequestAsync mutual exclusivity and conversation existence](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/Responses/InMemoryResponsesService.cs#L150-L181), [load conversation history and append items after success](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/Responses/InMemoryResponsesService.cs#L442-L489))

## State boundary of the Python helper

The Python helper package itself does not create a session store. The sample combines `AgentState` or `WorkflowState` with the core `SessionStore`/`FileSessionStore`. That is, the Responses helper does not “manage” state; it provides only a **seam that allows the host to attach state**. (Source: [Python hosting-responses README state note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/README.md#L62-L65), [Python sample AgentState + FileSessionStore](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/samples/04-hosting/af-hosting/local_responses/app.py#L74-L111))

---

## Tool calls

## .NET: streaming-time event generation

The `.NET` streaming renderer selects a generator based on the `AgentResponseUpdate` content type.

- `TextContent` → assistant message events
- `TextReasoningContent` → reasoning events
- `FunctionCallContent` → function call events
- `FunctionResultContent` → function result events
- `ToolApprovalRequestContent` / `ToolApprovalResponseContent` → approval events
- image/audio/file/hosted file content → corresponding media/file events

That is, .NET continues to expose tool/reasoning/media as a **typed event union** even on the streaming path, not just simple text deltas. (Source: [AgentResponseUpdateExtensions generator switch](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/Responses/AgentResponseUpdateExtensions.cs#L192-L220), [completed response construction after streamed items](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/Responses/AgentResponseUpdateExtensions.cs#L225-L279))

## Python: final payload item families

The Python final renderer maps to various `ResponseOutputItem` families based on `Content.type`.

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

That is, Python has a broad **item family breadth in the final JSON payload**, but the streaming helper does not emit this richness as full itemized deltas. (Source: [Python content-to-output-items switch](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/agent_framework_hosting_responses/_parsing.py#L324-L418), [reasoning/function call/result mapping](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/agent_framework_hosting_responses/_parsing.py#L485-L533), [MCP/shell/approval/media mappings](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/agent_framework_hosting_responses/_parsing.py#L577-L679))

## Interface with host responsibilities

How much of the tool choice and exposed tools from a request is accepted is a host policy decision.

- The .NET helper default rejects request-supplied `tools`/`tool_choice`.
- The Python helper passes them through as options for now, so the host route must re-establish the allowlist. (Source: [.NET RejectRequestSettings](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/OpenAIResponsesMapOptions.cs#L54-L96), [Python responses_to_run](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/agent_framework_hosting_responses/_parsing.py#L185-L196))

---

## Error mapping

## .NET route-owning server

### Validation phase
`ResponsesHttpHandler` maps validation errors to HTTP statuses via `ResponseErrorCodes`.

- `conversation_not_found` → 404
- other validation failures → 400

The error body follows the OpenAI-style `ErrorResponse` format. (Source: [ResponseErrorCodes](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/Responses/ResponseErrorCodes.cs#L12-L38), [ResponsesHttpHandler validation mapping](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/Responses/ResponsesHttpHandler.cs#L37-L56))

### create/get/cancel/delete phase
- non-streaming success: `200 OK`
- queued background response: `202 Accepted`
- unexpected exception: `500 Problem`
- cancel/delete invalid operations: `400` or `404` depending on path  
  (Source: [create response success/failure mapping](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/Responses/ResponsesHttpHandler.cs#L75-L97), [cancel mapping](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/Responses/ResponsesHttpHandler.cs#L138-L160), [delete and list-input-items mapping](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/Responses/ResponsesHttpHandler.cs#L163-L226))

## Python helper

The Python helper does not perform HTTP status mapping.

- parse/shape issues → `ValueError`
- streaming iteration/finalizer failure → `response.failed` SSE event
- the host route must determine the `HTTPException`/status code

The sample converts an invalid body to 400, a successful non-streaming response to 200 JSON, and a streaming response to `text/event-stream`. (Source: [Python helper README](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/README.md#L19-L21), [Python sample invalid body -> 400](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/samples/04-hosting/af-hosting/local_responses/app.py#L116-L123), [Python streaming failed event](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/agent_framework_hosting_responses/_parsing.py#L930-L956))

---

## Extension points

## .NET
- `OpenAIResponsesMapOptions.RunOptionsFactory`
  - maintain strict reject
  - map only selected fields
  - generate fully custom `AgentRunOptions`
- On the route-owning path, registering a custom `IResponsesService` is also possible. The endpoint builder uses `IResponsesService` from DI. (Source: [OpenAIResponsesMapOptions property](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/OpenAIResponsesMapOptions.cs#L13-L40), [MapOpenAIResponses() using DI IResponsesService](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/EndpointRouteBuilderExtensions.Responses.cs#L124-L163))

## Python
- The app route can
  - redefine allowed request options
  - session id trust policy
  - session store backend
  - workflow cursor model
  - auth middleware
  - background work
  can all be freely replaced. (Source: [Python hosting-responses README ownership](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/README.md#L19-L21), [Python sample route custom policy](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/samples/04-hosting/af-hosting/local_responses/app.py#L127-L133))

---

## Concurrency, streaming, and cancellation

## Concurrency

- `conversation`/`conversation_id` is a mutable head, so the host must provide single-writer coordination.
- `previous_response_id` is a branch-friendly immutable snapshot, so concurrent branching is possible.
- The helper does not provide conversation-level locking. (Source: [Python mutable head note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/README.md#L57-L60), [.NET GetSessionStoreId remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/OpenAIResponses.cs#L131-L142))

## Streaming

### .NET
- The route-owning path uses `SseJsonResult<T>` to set `text/event-stream`, `Cache-Control: no-cache,no-store`, `Connection: keep-alive`, `Content-Encoding: identity`, and disables buffering.
- The event payload is sent with `event:`/`data:` framing, JSON-serialized from a typed `StreamingResponseEvent`. (Source: [SseJsonResult headers and buffering](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/SseJsonResult.cs#L42-L58), [OpenAIResponses.WriteResponseStreamAsync frame format](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/OpenAIResponses.cs#L101-L117))

### Python
- The helper directly generates `event: <type>\ndata: <json>\n\n` strings.
- The public streaming helper emits only `response.created`, text deltas, and the terminal completed/failed. (Source: [Python SSE formatting](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/agent_framework_hosting_responses/_parsing.py#L852-L859), [Python responses_from_streaming_run](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/agent_framework_hosting_responses/_parsing.py#L862-L956))

## Cancellation

### .NET
- The route-owning server can only cancel background responses; otherwise it maps an `InvalidOperationException` to 400.
- Cached streaming replay can be re-read from a partial offset onward with `GetResponseStreamingAsync(startingAfter)`. (Source: [CancelResponseAsync rules](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/Responses/InMemoryResponsesService.cs#L260-L286), [IResponsesService GetResponseStreamingAsync](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/Responses/IResponsesService.cs#L63-L72))
- When `background=true`, `CreateResponseAsync` starts execution and immediately returns a queued response. (Source: [background create behavior](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/Responses/InMemoryResponsesService.cs#L193-L210))

### Python
- The helper package provides neither a cancel API nor a background response store.
- “background work” is explicitly the responsibility of the app/framework. (Source: [Python hosting-responses README responsibilities](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/README.md#L19-L21))

---

## Errors, validation, and security

## Validation

### .NET
- malformed JSON or a missing `input` raises an `ArgumentException` in the helper
- the route-owning server prohibits specifying both `conversation` and `previous_response_id` simultaneously
- if an optional conversation store is present, pre-validates the existence of the referenced conversation  
  (Source: [.NET ToAgentRunRequest parse errors](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/OpenAIResponses.cs#L47-L60), [.NET ValidateRequestAsync](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/Responses/InMemoryResponsesService.cs#L154-L181))

### Python
- `input` must be a non-empty string or a non-empty list.
- unsupported `input_*` shapes raise a `ValueError`
- nonstandard id prefixes are not rejected; only a warning is issued. (Source: [Python messages_from_responses_input validation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/agent_framework_hosting_responses/_parsing.py#L82-L124), [Python responses_session_id warnings](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/agent_framework_hosting_responses/_parsing.py#L137-L170))

## Security

- both request-derived continuation ids are **untrusted candidate keys**.
- the host must authenticate the caller and bind the id to an authorized principal before using it as a session/checkpoint key.
- The .NET helper separates `GetSessionStoreId(...)` from `ToAgentRunRequest(...)` to make this trust boundary visible.
- The Python helper also separates session id extraction from run parsing. (Source: [.NET trust boundary remarks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/OpenAIResponses.cs#L25-L29), [.NET separate GetSessionStoreId](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/OpenAIResponses.cs#L131-L145), [Python session id helper docs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/agent_framework_hosting_responses/_parsing.py#L137-L149))

---

## .NET/Python differences

1. **Packaging**
   - .NET: provides both helper and route-owning server
   - Python: helper-only  
   (Source: [ADR-0032 current .NET stack](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0032-dotnet-hosting-protocol-helpers.md#L21-L33), [Python README responsibilities](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/README.md#L19-L21))

2. **Boundary type**
   - .NET helper public boundary: `JsonElement`
   - Python helper public boundary: `dict[str, Any]` + SSE strings  
   (Source: [.NET OpenAIResponses helper](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/OpenAIResponses.cs#L80-L98), [Python __all__ exports](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/agent_framework_hosting_responses/__init__.py#L22-L31))

3. **Default request mapping policy**
   - .NET: strict reject
   - Python: permissive pass-through/remap  
   (Source: [.NET RejectRequestSettings](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/OpenAIResponsesMapOptions.cs#L54-L99), [Python responses_to_run](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/agent_framework_hosting_responses/_parsing.py#L185-L196))

4. **Streaming richness**
   - .NET: full typed event union
   - Python: created + text delta + terminal event focused  
   (Source: [.NET StreamingResponseEvent union](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/Responses/Models/StreamingResponseEvent.cs#L13-L33), [Python responses_from_streaming_run](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/agent_framework_hosting_responses/_parsing.py#L881-L956))

5. **Background/cancel**
   - .NET route-owning path: built-in queued background responses with cancel/delete support
   - Python helper path: host responsibility  
   (Source: [InMemoryResponsesService background/cancel](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/Responses/InMemoryResponsesService.cs#L193-L210), [same file cancel](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/Responses/InMemoryResponsesService.cs#L260-L301), [Python README ownership](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/README.md#L19-L21))

---

## .NET implementation and tests

### Implementation summary
- helper façade: `OpenAIResponses`
- route-owning endpoint: `MapOpenAIResponses`
- internal execution/storage service: `IResponsesService`, default `InMemoryResponsesService`
- HTTP handler: `ResponsesHttpHandler`
- SSE result writer: `SseJsonResult<T>`  
  (Source: [OpenAIResponses.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/OpenAIResponses.cs#L15-L164), [IResponsesService](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/Responses/IResponsesService.cs#L15-L111), [ResponsesHttpHandler](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/Responses/ResponsesHttpHandler.cs#L13-L227), [SseJsonResult](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/SseJsonResult.cs#L15-L75))

### Conformance tests
- `OpenAIResponsesHostingTests` verifies in-process hosting with a TestServer and a deterministic mock chat client, **without a real server process or live model**.
- Verified items:
  - non-streaming Responses-shaped JSON
  - SSE create/completed
  - multi-turn continuity
  - `previous_response_id` independent branch
  - stable `conversation` mutable head
  - malformed body 400
  - workflow run/resume path  
  (Source: [OpenAIResponsesHostingTests class comment](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests/OpenAIResponsesHostingTests.cs#L22-L30), [non-streaming/streaming tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests/OpenAIResponsesHostingTests.cs#L39-L76), [session continuity and branches](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests/OpenAIResponsesHostingTests.cs#L79-L147), [malformed body and workflow resume](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests/OpenAIResponsesHostingTests.cs#L157-L194))

---

## Python implementation and tests

### Implementation summary
- Parser and render helpers are consolidated in a single file `_parsing.py`.
- The public package exports only helper functions.
- The sample route combines FastAPI with `AgentState`/`FileSessionStore` to construct `/responses` directly.  
  (Source: [Python hosting-responses __init__](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/agent_framework_hosting_responses/__init__.py#L1-L31), [Python sample app](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/samples/04-hosting/af-hosting/local_responses/app.py#L1-L192))

### Conformance tests
- `test_http_round_trip.py` verifies the full POST→FastAPI route→JSON/SSE flow using `httpx.AsyncClient` + `ASGITransport`, **without a real socket**.
- Verified items:
  - 200 JSON payload
  - invalid input 400
  - SSE created/delta/completed
  - three-turn `previous_response_id` continuity  
  (Source: [HTTP round-trip class comment](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/tests/hosting_responses/test_http_round_trip.py#L3-L12), [round-trip app wiring](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/tests/hosting_responses/test_http_round_trip.py#L121-L169), [non-streaming and streaming tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/tests/hosting_responses/test_http_round_trip.py#L197-L234), [session continuity test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/tests/hosting_responses/test_http_round_trip.py#L236-L260))
- `test_parsing.py` pins the parser and renderer semantics.
  - input item variants
  - session id precedence
  - multimodal/tool output item mapping
  - streaming completed/failed event behavior  
  (Source: [input parsing tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/tests/hosting_responses/test_parsing.py#L31-L119), [session id and run helper tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/tests/hosting_responses/test_parsing.py#L122-L177), [output item mapping tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/tests/hosting_responses/test_parsing.py#L178-L227), [streaming completed/failed tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/tests/hosting_responses/test_parsing.py#L243-L317))

---

## Documentation differences

## 1. `conversationId` passing inconsistency in the .NET sample
The `.NET` app-owned sample explains in comments that `conversation` is a mutable head and computes `conversationId` and `saveId`, but in the actual render calls passes `responseId` rather than the computed `conversationId`, as in `WriteResponseStreamAsync(..., responseId, responseId, ...)` and `WriteResponse(..., responseId, responseId)`. Since the helper/library contract and the sample commentary do not fully agree, **taking the code as authoritative** reveals a sample-level inconsistency. (Source: [.NET sample commentary and computed ids](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/samples/04-hosting/af-hosting/local_responses/Server/Program.cs#L72-L80), [.NET sample actual render calls](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/samples/04-hosting/af-hosting/local_responses/Server/Program.cs#L84-L104))

## 2. Coexistence of the helper-first principle and the .NET route-owning server
ADR-0032 explains that, while following the helper-first direction, the decision to **add the helper façade to the same package** was made because the existing `.NET` stack already contained a route-owning server called `MapOpenAIResponses`. Therefore, reading only the phrase “helper-first” might suggest a pure helper package like Python, but the actual .NET code contains both the helper and a batteries-included server. (Source: [ADR-0032 context and capability gap](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0032-dotnet-hosting-protocol-helpers.md#L21-L33), [ADR-0032 decision outcome](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0032-dotnet-hosting-protocol-helpers.md#L72-L98))

---

## Java decisions

## Recommended module structure

### 1. protocol adapter
- `agent-framework-hosting-openai-responses`
- Keep the public boundary at the level of **Jackson `JsonNode` or `Map<String,Object>`**
- Include request parser / final response writer / SSE writer / session-id extractor

Both the `.NET` `JsonElement` boundary and the Python dict boundary share the property of “not exposing vendor SDK types directly in the host public API”, so Java should follow this direction. (Source: [.NET JsonElement façade](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/OpenAIResponses.cs#L80-L98), [Python dict boundary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/agent_framework_hosting_responses/__init__.py#L22-L31))

### 2. optional host binder
- `agent-framework-hosting-openai-responses-spring`
- `/responses` route binder for Spring MVC/WebFlux
- status code mapping, SSE `text/event-stream`, exception mapper

If a batteries-included endpoint is needed as in `.NET`, keeping it as a **separate artifact** from the protocol adapter reduces entanglement with the generic hosting core.

### 3. default request mapping policy
The Java default policy recommends the **same strict default as .NET**.

- reject caller-supplied `tools`, `tool_choice`, `instructions`, and sampling controls by default
- the host opts in with an explicit allowlist or custom mapper

The reason is that a Python-style permissive default can inadvertently allow callers to override agent configuration at a self-hosted production endpoint. (Source: [.NET RejectRequestSettings](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/OpenAIResponsesMapOptions.cs#L54-L96), [Python permissive mapping](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/agent_framework_hosting_responses/_parsing.py#L185-L196))

### 4. streaming event scope
Java should consider at minimum two modes.

- **simple mode**: `response.created` + text delta + terminal event, as in Python
- **full mode**: includes output item / content part / function args / reasoning delta, as in .NET

Splitting these into a separate renderer profile or option is advantageous in terms of the balance between implementation cost and compatibility.

---

## Concrete acceptance scenarios

1. **Request parsing**
   - `input` must be accepted as a string, a loose content item list, and an explicit message envelope.
   - A malformed body or missing `input` must produce a clean validation error.

2. **Continuation semantics**
   - `previous_response_id` must be an immutable branch point.
   - Starting two branches from the same prior response must not allow their states to intermix.
   - `conversation`/`conversation_id` is a mutable head, so the host must be able to enforce single-writer coordination.

3. **Request option policy**
   - A strict-default host must reject caller-supplied `tools`/`tool_choice`/sampling fields.
   - A permissive host must place only required fields into agent run options via an explicit allowlist.

4. **Streaming**
   - SSE must emit at minimum `response.created` and a terminal `response.completed` or `response.failed`.
   - When text streaming is present, delta event ordering must be guaranteed.
   - In rich mode, function/reasoning/media deltas must emerge as itemized events without loss.

5. **Tool calls**
   - The final response payload must be able to represent not only assistant messages but also `function_call`, `function_call_output`, reasoning, and media/file item families.
   - The host must be able to control the tool exposure policy.

6. **Error mapping**
   - Validation failures must be distinguished as request faults, and execution failures as server faults.
   - When a streaming iteration/finalizer failure occurs, a terminal failed event must be sent together with partial output.

7. **Host ownership**
   - authn/authz, trusted session binding, durable store, background work, and cancel semantics must remain outside the framework helper.

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

## Conclusion

OpenAI Responses-compatible hosting is one of the most protocol-heavy hosting seams in Agent Framework, and the actual implementation is not a “simple port of the same feature”. `.NET` is a broad implementation that includes strict mapping, a rich event union, and a route-owning server, while Python has a narrower and more explicit boundary of helper-only + host-owned policy. Of the two, the configuration that follows **Python for simplicity of public boundary and .NET for conservatism of default policy** is the most appropriate for Java. That is, keeping the helper API at the JSON tree/dict boundary, using strict-default for the base request mapping, and separating the batteries-included route binder as a separate artifact is the most stable long-term approach. (Source: [ADR-0032 decision outcome](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0032-dotnet-hosting-protocol-helpers.md#L72-L98), [Python hosting-responses README](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/README.md#L19-L21), [.NET strict default mapping](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting.OpenAI/OpenAIResponsesMapOptions.cs#L54-L99), [Python permissive default mapping](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-responses/agent_framework_hosting_responses/_parsing.py#L185-L196))