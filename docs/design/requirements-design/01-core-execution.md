# Core execution design

## 1. 범위

이 문서는 다음 요구사항의 canonical owner다.

- `AGT-001..016`: agent lifecycle, model call, streaming
- `MSG-001..013`: message/content/response/update
- `OUT-001..012`: structured output
- `TOOL-001..021`: tool definition, loop, budgets, approval

session persistence와 interceptor pipeline은
[02 State, extension, and MCP](02-state-extension-mcp.md), host assembly는
[05 Framework adapters](05-framework-adapters.md)가 소유한다.

## 2. 패키지와 책임

### 2.1 API

```text
com.microsoft.agentframework.api.agent
  Agent, AgentId, AgentRunRequest, AgentRunOptions, AgentResponse, AgentResponseUpdate
  AgentRun, AgentStreamingRun, CancellationSignal

com.microsoft.agentframework.api.message
  Message, Role, Content, TextContent, MediaContent, ToolCallContent, ToolResultContent
  ProviderExtensionContent, Usage, FinishReason, MessageAttribution

com.microsoft.agentframework.api.tool
  Tool, ToolDefinition, ToolHandler, ToolArguments, ToolResult, ToolMode
  ToolExecutionOptions, ToolApprovalRequest, ToolApprovalResponse

com.microsoft.agentframework.api.structured
  StructuredOutputRequest<T>, StructuredType<T>, StructuredValue<T>
  JsonSchema, StructuredOutputException

com.microsoft.agentframework.spi.model
  ChatClient, ChatRequest, ChatResponse, ChatResponseUpdate
  ModelCapability<T>, ModelOptions

com.microsoft.agentframework.spi.tool
  ToolSchemaGenerator, ToolArgumentValidator, ToolResultMapper, ExecutionStrategy
```

`ChatClient`는 공급자 SDK 타입을 노출하지 않는다. `ToolHandler`는 모델이 만든 JSON-safe
arguments와 `ToolContext`를 별도 인자로 받으며, context가 schema에 들어가지 않는다.

### 2.2 Engine

```text
com.microsoft.agentframework.engine
  AgentEngine, AgentEngineBuilder

com.microsoft.agentframework.engine.internal.run
  RunStateMachine, RunState, RunFinalizer

com.microsoft.agentframework.engine.internal.model
  ModelCallCoordinator, OptionsMerger, ResponseCoalescer

com.microsoft.agentframework.engine.internal.tool
  ToolLoop, ToolBatchPlanner, ToolInvocationCoordinator
  ToolBudget, ApprovalCoordinator, ToolResultNormalizer

com.microsoft.agentframework.engine.internal.structured
  StructuredOutputCoordinator, SchemaWrapper, StructuredValueParser
```

engine internal 타입은 public port 구현자가 참조하지 않는다.

## 3. Public API 형태

### 3.1 Agent

`Agent`는 식별자와 두 실행 진입점을 제공한다.

```text
Agent.run(request)            -> AgentRun(final completion + cancel)
Agent.runStreaming(request)   -> AgentStreamingRun(updates + final completion + cancel)
```

정확한 completion/stream 타입은 async ADR에서 결정한다. 기본 후보는
`CompletionStage<AgentResponse>`와 `Flow.Publisher<AgentResponseUpdate>`이며 둘 다 run handle
안에 있다. 두 handle의 `cancel()`은 request에 전달된 동일 `CancellationSignal`을 발동한다.

`AgentRunRequest`는 non-null immutable value다.

- normalized message list
- optional session handle
- run options
- explicit cancellation signal
- typed execution attributes

no-input 실행은 `AgentRunRequest.empty()` 또는 별도 overload로 표현한다. `null`은 빈 입력이
아니다.

### 3.2 AgentEngine

`AgentEngine`은 `Agent`를 구현하거나 `Agent` 구현을 만드는 application service다. host는
builder로 다음 port를 주입한다.

- `ChatClient`
- session services
- ordered interceptor lists
- `ExecutionStrategy`
- schema/result mapper
- telemetry sink

builder는 지원하지 않는 interceptor seam, 누락된 mandatory port, 상충하는 tool option을 build
시점에 거부한다.

### 3.3 Values

- `Role`: 알려진 상수를 제공하는 immutable string value
- `Content`: core-known value와 `ProviderExtensionContent` envelope
- `Message`: role, content, attribution, typed extension values
- `AgentResponse`/`AgentResponseUpdate`: content, usage, finish reason, response/session metadata

provider SDK raw object는 transient diagnostic handle이다. snapshot과 wire conversion은
JSON-safe extension value만 본다.

## 4. 실행 상태 기계

```text
VALIDATE
  -> LOAD_SESSION
  -> BUILD_CONTEXT
  -> CALL_MODEL
  -> APPEND_MODEL_UPDATES
  -> PLAN_TOOL_BATCH
       -> WAIT_APPROVAL
       -> INVOKE_TOOLS
       -> APPEND_TOOL_RESULTS
       -> CALL_MODEL
  -> PARSE_STRUCTURED_OUTPUT
  -> FINALIZE_STREAM
  -> PERSIST_SESSION
  -> COMPLETE
```

### 4.1 Invariants

- session compatibility는 model call 전에 검증한다.
- model update만으로 final response를 결정적으로 복원할 수 있다.
- non-streaming과 streaming은 같은 tool loop와 merge rule을 사용한다.
- tool batch의 input order와 result order가 같다.
- approval 대기 상태에서 승인되지 않은 tool은 실행되지 않는다.
- cancellation 뒤 success finalization과 session commit이 발생하지 않는다.

## 5. Model port와 options

`ChatClient`는 최소 공통 기능만 가진다. web search, code interpreter, provider continuation은
typed `ModelCapability<T>` 또는 adapter-specific API로 노출한다.

`ModelOptions`는 typed immutable properties를 가진다.

- temperature
- maximum output tokens
- top-p 등 cross-provider 합의가 있는 값
- adapter-owned provider options

merge 순서:

```text
engine safe defaults
  < agent defaults
  < run options
  < host-enforced restrictions
```

host restriction은 낮은 우선순위 값으로 덮이지 않는다. unsupported provider option은 무시하지
않고 model call 전에 실패한다.

## 6. Streaming과 cancellation

stream subscription, model transport, tool invocation은 같은 `CancellationSignal`을 관찰한다.
adapter는 standard 취소를 bridge한다.

```text
HTTP disconnect / Future.cancel / Subscription.cancel / Thread.interrupt
                -> CancellationSignal
                -> model + tool + session operation cancel
```

`AgentRun`은 다음 control surface를 가진다.

- final response completion
- explicit cancel
- completion status

`AgentStreamingRun`은 같은 control surface와 `updates` subscription을 추가한다. 두 handle의
cancel은 같은 request cancellation signal을 발동한다.

updates consumer가 final response를 요청하지 않아도 stream completion이 run finalization을
완료한다. 중도 취소는 부분 response를 durable final response로 commit하지 않는다.

## 7. Tool model

### 7.1 Definition

`ToolDefinition`은 immutable하다.

- stable name, description
- input schema
- execution availability: local, declaration-only, remote/hosted
- approval policy reference

runtime counters와 error streak는 `ToolRunState`에 둔다.

### 7.2 Schema

schema source 우선순위:

1. explicit JSON schema
2. generated metadata from annotation processor
3. reliable reflection metadata

parameter name/generic type이 보존되지 않으면 `arg0`를 추측하지 않고 explicit schema를 요구한다.
`ToolSchemaGenerator` SPI가 Java reflection, record/bean metadata, adapter-specific schema system을
변환하되 public API에 Jackson/Spring type을 노출하지 않는다.

### 7.3 Handler

```text
ToolHandler.invoke(ToolArguments, ToolContext) -> completion of ToolResult
```

- `ToolArguments`: validated JSON-safe values
- `ToolContext`: run/session/cancellation/typed attributes; schema 밖
- typed handler의 `null`: implementation error
- void convenience adapter: empty-string text content 하나
- arbitrary object: default JSON-safe mapper; explicit mapper overrides

## 8. Tool loop

### 8.1 Planning

`ToolBatchPlanner`는 model calls를 다음으로 분류한다.

- executable local calls
- approval-required calls
- declaration-only/remote calls
- unknown calls

혼합된 batch를 무조건 병렬 실행하지 않는다. 실행 가능한 local subset만 `ExecutionStrategy`에
전달한다.

### 8.2 Budget

`ToolBudget`은 run-scoped다.

- maximum iterations
- maximum total calls
- maximum consecutive errors per request
- automatic approval chain limit

한 model response가 budget보다 큰 batch를 만들면 현재 batch boundary 뒤에서 best-effort
차단한다. 이미 시작한 call을 수치 맞추기 위해 임의 취소하지 않는다.

### 8.3 Parallel execution

engine은 executor를 만들지 않는다. `ExecutionStrategy`가 sequential/parallel policy와
host-owned execution resource를 캡슐화한다. 결과는 input call order로 재정렬한다.

### 8.4 Approval

approval request/response는 core `Content`다.

- request id + exact tool name + canonical arguments
- response는 original request와 id/type을 함께 검증
- deny는 synthetic terminal tool result
- standing approval은 host/tenant boundary와 exact argument matcher 포함
- queue와 counters는 session state에 저장

## 9. Structured output

`StructuredOutputRequest<T>`는 다음을 가진다.

- framework-neutral `StructuredType<T>`
- JSON schema
- native preference
- wrapper metadata for non-object target

provider native structured output는 best effort다. 유효 JSON text가 있으면 native 지원 여부와
관계없이 fallback parser가 동작한다.

parse는 `StructuredValue<T>.value()` 접근 시점까지 지연할 수 있다. 오류를 구분한다.

- empty or JSON `null`
- malformed JSON
- schema validation
- target type binding
- wrapper mismatch

wrapper가 기대됐는데 bare JSON이 오면 원본 JSON으로 한 번 재시도한다. stream update에서 typed
value를 노출하지 않고 final response에서만 읽는다.

## 10. 오류 모델

| 오류 | 타입 방향 |
| --- | --- |
| null/invalid option, invalid URI | `IllegalArgumentException` |
| unsupported mode/seam | `IllegalStateException` |
| model/provider failure | `ModelException` + category/cause |
| tool validation | `ToolArgumentException` |
| tool execution | `ToolExecutionException` |
| structured parse/validation | 별도 unchecked subtype |
| cancellation | cancellation outcome; generic exception wrapping 금지 |

protocol binder는 이 category를 HTTP/A2A/MCP 오류로 변환하며 message string을 파싱하지 않는다.

## 11. 테스트

### Unit

- message normalization, content projection, custom role round-trip
- immutable option merge and provider option rejection
- response update coalescing
- schema inference fail-closed
- result normalization including void/null distinction
- budget and approval matching

### Contract

- `ChatClientContract`
- `ToolHandlerContract`
- `ToolSchemaGeneratorContract`
- `ExecutionStrategyContract`
- streaming/cancellation bridge contract

### Golden

- pinned upstream run/stream equivalence
- tool batch ordering and limits
- approval queue/resume
- structured wrapper/bare fallback
- final response reconstruction

## 12. 현재 구현

`ApiContract`와 `EngineContract`는 module marker이며 이 문서의 행동을 구현하지 않는다. 모든 기능
ID의 현재 상태는 `absent`이고 module 자체만 `bootstrap`이다.

## 13. 요구사항 매핑

`AGT`, `MSG`, `OUT`, `TOOL`의 canonical rows는
[Requirements traceability matrix](requirements-traceability-matrix.md)에 있다. 각 row의 목표
code family는 이 문서의 package/component와 일치해야 한다.
