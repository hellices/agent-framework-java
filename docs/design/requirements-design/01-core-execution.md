# Core execution design

## 1. Scope

This document is the canonical owner of the following requirements.

- `AGT-001..016`: agent lifecycle, model call, streaming
- `MSG-001..013`: message/content/response/update
- `OUT-001..012`: structured output
- `TOOL-001..021`: tool definition, loop, budgets, approval

Session persistence and the interceptor pipeline are owned by
[02 State, extension, and MCP](02-state-extension-mcp.md); host assembly is owned by
[05 Framework adapters](05-framework-adapters.md).

## 2. Packages and responsibilities

### 2.1 API

```text
io.github.hellices.agentframework.api.agent
  Agent, AgentId, AgentRunRequest, AgentRunOptions, AgentResponse, AgentResponseUpdate
  AgentFactory, AgentBuilder, AgentRun, AgentStreamingRun, CancellationSignal

io.github.hellices.agentframework.api.message
  Message, Role, Content, TextContent, MediaContent, ToolCallContent, ToolResultContent
  ProviderExtensionContent, Usage, FinishReason, MessageAttribution

io.github.hellices.agentframework.api.tool
  Tools, Tool, ToolSet, ToolDefinition, ToolHandler, ToolArguments, ToolResult, ToolMode
  ToolExecutionOptions, ToolApprovalRequest, ToolApprovalResponse

io.github.hellices.agentframework.api.structured
  StructuredOutputRequest<T>, StructuredType<T>, StructuredValue<T>
  JsonSchema, StructuredOutputException

io.github.hellices.agentframework.spi.model
  ModelClient, ModelRequest, ModelResponse, ModelResponseUpdate
  ModelCatalog, ModelCapability<T>, ModelOptions

io.github.hellices.agentframework.spi.tool
  ToolSchemaGenerator, ToolArgumentValidator, ToolResultMapper, ExecutionStrategy
```

`ModelClient` does not expose provider SDK types. Its name is distinct to avoid conflicting with
the simple name of Spring AI's `ChatClient`. `ToolHandler` receives JSON-safe arguments produced by
the model and a `ToolContext` as separate arguments; the context is not included in the schema.

`ModelCatalog` is an instance-scoped immutable model index created by the host assembly.

- stable model name → `ModelClient`
- optional default model name
- duplicate name rejection
- no static/global discovery

### 2.2 Engine

```text
io.github.hellices.agentframework.engine
  AgentEngine, AgentEngineBuilder

io.github.hellices.agentframework.engine.internal.run
  RunStateMachine, RunState, RunFinalizer

io.github.hellices.agentframework.engine.internal.model
  ModelCallCoordinator, OptionsMerger, ResponseCoalescer

io.github.hellices.agentframework.engine.internal.tool
  ToolLoop, ToolBatchPlanner, ToolInvocationCoordinator
  ToolBudget, ApprovalCoordinator, ToolResultNormalizer

io.github.hellices.agentframework.engine.internal.structured
  StructuredOutputCoordinator, SchemaWrapper, StructuredValueParser
```

Implementors of public ports do not reference engine internal types.

## 3. Public API shape

### 3.1 Agent

`Agent` provides an identifier and two execution entry points.

```text
Agent.run(request)            -> AgentRun(final completion + cancel)
Agent.runStreaming(request)   -> AgentStreamingRun(updates + final completion + cancel)
```

`AgentRun.response()` provides a `CompletionStage<AgentResponse>`, and
`AgentStreamingRun.updates()` provides a `Flow.Publisher<AgentResponseUpdate>`. The `cancel()` method
on both handles triggers the same `CancellationSignal` passed to the request.

`AgentRunRequest` is a non-null immutable value.

- normalized message list
- optional session handle
- run options
- explicit cancellation signal
- typed execution attributes

A no-input execution is represented by `AgentRunRequest.empty()` or a separate overload. `null`
does not mean empty input.

`run(String)` and equivalent convenience overloads create a fresh `CancellationSignal`, build the
canonical request, and return the connected `AgentRun`. Callers that need deadline or parent-request
propagation construct `AgentRunRequest` with their own signal.

`AgentFactory` is a thread-safe singleton facade that creates a new `AgentBuilder` for every call.
Framework adapters and the standalone assembly provide a factory with fully assembled ports, so
general users do not interact directly with `AgentEngineBuilder`.

`AgentFactory` composes a shared, model-independent `AgentEngine` with a `ModelCatalog`. The engine
is created once through `AgentEngine.builder()` (session services only) and reused; each factory
binds it to a catalog. `AgentEngine` exposes two composition overloads:

- `engine.factory(ModelCatalog)`: a factory over an explicit catalog for `builder()`/`builder(name)`
  model selection
- `engine.factory()`: a factory over an empty catalog for the explicit-client path
  (`builderWithClient`); catalog-backed routes still fail with the same actionable messages

- `builder()`: uses the catalog's default model; fails with actionable guidance if there is no
  default
- `builder(name)`: resolves a named model
- `builderWithClient(ModelClient)`: uses an explicit model without a null-ambiguous Java overload
- `bind(AgentDefinition, AgentRuntime)`: binds an externally constructed, immutable definition and
  its runtime to the shared engine, preserving declaration-only tools that carry no binding

An `AgentBuilder` separates declaration from binding. `buildDefinition()` returns the declarative
`AgentDefinition` (id, name, description, instructions, tool declarations, and `defaultRunOptions`
including the `maxToolIterations` budget) with no runtime binding. `build()` derives the
`AgentRuntime` (selected `ModelClient`, tool bindings, and context providers) and calls
`engine.bind(definition, runtime)`. Context providers and executable tool bindings live only in the
runtime, never in the definition. A `FunctionTool` supplied to `tools()` always contributes both a
declaration and a binding; a manually constructed `AgentDefinition` may hold declaration-only tools,
and `bind` preserves that distinction.

### 3.2 AgentEngine

`AgentEngine` is an application service that implements `Agent` or creates an `Agent`
implementation. The host injects the following engine-wide ports through the builder.

- session services
- ordered interceptor lists
- `ExecutionStrategy`
- schema/result mapper
- telemetry sink

These engine-wide services are the target ownership for `AgentEngineBuilder`. In this Task 4 slice
the builder currently exposes only the shared `SessionStore` and `StateCodecRegistry`; the ordered
interceptor lists, `ExecutionStrategy`, schema/result mapper, and telemetry sink are added by later
approved convergence tasks. Whether present now or added later, these engine-wide services belong to
the engine and never to an individual `AgentDefinition` or `AgentRuntime`.

At build time, the builder rejects unsupported interceptor seams, missing mandatory ports, and
conflicting tool options.

The engine is not bound to a specific model. `AgentEngine.builder()` configures only shared session
services (session store, state codec registry) and `build()` returns the reusable engine.
`AgentFactory` selects a model from `ModelCatalog`, associates its `ModelClient` with an immutable
Agent definition, and `engine.bind(definition, runtime)` runs that definition. A run-level model
override uses the same public port.

### 3.3 Tool facade

`Tools` is a static factory that creates a `Tool` from an explicit Java handler. `ToolSet` is a
portable aggregate with a name and an immutable tool collection; MCP discovery and annotation
processor companions also return the same type.

```text
AgentBuilder.tools(Tool...)
AgentBuilder.tools(ToolSet...)
AgentBuilder.tools(Collection<? extends Tool>)
AgentBuilder.declaredTools(ToolDefinition...)
```

Each overload validates name conflicts and stores an immutable snapshot in the build result.
Attaching a `ToolSet` is explicit; it does not automatically collect every tool from a DI container
or the classpath. `declaredTools` exposes definitions to the model without registering local
handlers, and the core does not create fake tool results.

### 3.4 Values

- `Role`: immutable string value that provides known constants
- `Content`: core-known values and a `ProviderExtensionContent` envelope
- `Message`: role, content, attribution, and typed extension values
- `AgentResponse`/`AgentResponseUpdate`: content, usage, finish reason, and response/session metadata

A raw provider SDK object is a transient diagnostic handle. Snapshot and wire conversions consider
only JSON-safe extension values.

Executable architecture policy keeps these value rules honest: primary public API/SPI signatures do
not expose raw `Map<String, Object>` contracts, and only reviewed fixed values (`Usage` and
`MessageAttribution`) remain records. Session/tool/request/response/options values stay final
classes so later slices can extend them compatibly.

## 4. Execution state machine

```text
VALIDATE
  -> LOAD_SESSION
  -> BUILD_CONTEXT
  -> RESOLVE_PENDING_APPROVAL_QUEUE
       -> while head response exists: EXECUTE_OR_REJECT_HEAD
       -> APPEND_HEAD_TOOL_RESULT
       -> if unanswered head remains: WAIT_APPROVAL and stop
  -> PREPARE_MODEL_INPUT
       -> DRAIN_INJECTED_MESSAGES
  -> CALL_MODEL
  -> APPEND_MODEL_UPDATES
  -> OPTIONAL_PERSIST_SERVICE_HISTORY
  -> DECIDE_NEXT_ACTION
       ├─ actionable tools: PLAN_TOOL_BATCH
       │    -> if approval required: WAIT_APPROVAL and stop
       │    -> INVOKE_TOOLS
       │    -> APPEND_TOOL_RESULTS
       │    -> PREPARE_MODEL_INPUT
       ├─ no tools + injected queue non-empty: PREPARE_MODEL_INPUT
       └─ no tools + queue empty: ATTACH_LAZY_STRUCTURED_VALUE
  -> ATTACH_LAZY_STRUCTURED_VALUE
  -> FINALIZE_STREAM
  -> PERSIST_SESSION
  -> COMPLETE
```

### 4.1 Invariants

- Session compatibility is validated before a model call.
- A final response can be deterministically reconstructed from model updates alone.
- Non-streaming and streaming use the same tool loop and merge rules.
- An inbound approval response is validated against the original request and executed or rejected
  before the first model call.
- The approval queue is processed one item at a time from the head, and the model is not called
  until the queue is completely empty.
- The session injection queue is drained before every model iteration. If the queue remains
  non-empty after a tool-free response, a new internal model iteration starts instead of
  finalization.
- The per-service-call history option persists after each model response and is distinct from final
  session persistence.
- Structured target binding attaches a lazy value to the final response and does not parse it
  before the accessor is called.
- Tool batch input order and result order are identical.
- A tool awaiting approval is not executed.
- Successful finalization and session commit do not occur after cancellation.

## 5. Model port and options

`ModelClient` has only the minimum common functionality. Web search, code interpreter, and provider
continuation are exposed through typed `ModelCapability<T>` or adapter-specific APIs.

`ModelOptions` has typed immutable properties.

- temperature
- maximum output tokens
- cross-provider agreed values such as top-p
- adapter-owned provider options

Merge order:

```text
engine safe defaults
  < agent defaults
  < run options
  < host-enforced restrictions
```

A host restriction cannot be overridden by a lower-priority value. An unsupported provider option
is not ignored; it fails before the model call.

## 6. Streaming and cancellation

An Agent execution-stream subscription, model transport, and tool invocation observe the same
`CancellationSignal`. Adapters bridge standard cancellation. Unsubscribing from a durable workflow
event/watch stream stops observation only; it does not cancel that workflow run (WF-015).

```text
HTTP disconnect / Future.cancel / Subscription.cancel / Thread.interrupt
                -> CancellationSignal
                -> model + tool + session operation cancel
```

`AgentRun` has the following control surface.

- final response completion
- explicit cancel
- completion status

`AgentStreamingRun` adds an `updates` subscription to the same control surface. The cancel method
on both handles triggers the same request cancellation signal.

`AgentStreamingRun<T>` provides lifecycle-preserving transformations rather than exposing only a
raw publisher.

```text
updates(): Flow.Publisher<T>
mapUpdates(Function<T,R>): AgentStreamingRun<R>
flatMapUpdates(Function<T,Flow.Publisher<R>>): AgentStreamingRun<R>
```

A transformed run shares the original final response completion, finalizer, result hook, cleanup
hook, and cancellation signal. Even when wrappers are layered multiple times, hooks execute exactly
once in their original order.

Stream completion finalizes the run even if the updates consumer does not request the final
response. Early cancellation does not commit a partial response as a durable final response.

## 7. Tool model

### 7.1 Definition

`ToolDefinition` is immutable.

- stable name and description
- input schema
- execution availability: local, declaration-only, remote/hosted
- approval policy reference

Runtime counters and the error streak reside in `ToolRunState`.

### 7.2 Schema

Schema source priority:

1. explicit JSON schema
2. generated metadata from an annotation processor
3. reliable reflection metadata

When parameter names or generic types are not preserved, the framework requires an explicit schema
rather than guessing `arg0`. The `ToolSchemaGenerator` SPI converts Java reflection, record/bean
metadata, and adapter-specific schema systems without exposing Jackson or Spring types in the
public API.

### 7.3 Handler

```text
ToolHandler.invoke(ToolArguments, ToolContext) -> completion of ToolResult
```

- `ToolArguments`: validated JSON-safe values
- `ToolContext`: run/session/cancellation/typed attributes; outside the schema
- `null` from a typed handler: implementation error
- void convenience adapter: one empty-string text content value
- arbitrary object: default JSON-safe mapper; an explicit mapper overrides it

### 7.4 Tool options and merge

Tool policy merge order:

```text
engine safe defaults
  < agent ToolExecutionOptions
  < run ToolExecutionOptions
  < host-enforced restrictions
```

A run-level value overrides the agent default, while unspecified values are retained. Tool
collections are merged by stable name. A duplicate name in the same layer fails; a run-layer tool
with the same name explicitly replaces the agent default and appears only once in the final list.
Conflicts between executable tools and declaration-only definitions are validated by the same
rules.

## 8. Tool loop

### 8.1 Planning

`ToolBatchPlanner` classifies model calls as follows.

- executable local calls
- approval-required calls
- declaration-only/remote calls
- unknown calls

A mixed batch is not unconditionally executed in parallel. Only the executable local subset is
passed to `ExecutionStrategy`.

### 8.2 Budget

`ToolBudget` is run-scoped.

- maximum iterations
- maximum total calls
- maximum consecutive errors per request
- automatic approval chain limit

If a model response produces a batch larger than the budget, execution is blocked on a best-effort
basis after the current batch boundary. Calls that have already started are not arbitrarily
cancelled merely to meet the numeric limit.

### 8.3 Parallel execution

The engine does not create an executor. `ExecutionStrategy` encapsulates sequential/parallel policy
and host-owned execution resources. Results are reordered to match input call order.

### 8.4 Approval

Approval requests and responses are core `Content` values.

- request ID + exact tool name + canonical arguments
- a response validates both the ID and type against the original request
- denial is a synthetic terminal tool result
- standing approval includes the host/tenant boundary and an exact argument matcher
- the queue and counters are stored in session state

## 9. Structured output

`StructuredOutputRequest<T>` contains the following.

- framework-neutral `StructuredType<T>`
- JSON schema
- native preference
- wrapper metadata for a non-object target

Provider-native structured output is best effort. When valid JSON text is present, the fallback
parser works regardless of native support.

The parser source is the last non-empty assistant message in the final response. Only that
message's text content chunks are concatenated directly in order. Earlier assistant messages and
subsequent tool messages are excluded from the target.

Parsing must be deferred until `StructuredValue<T>.value()` is accessed. Errors are distinguished
as follows.

- empty or JSON `null`
- malformed JSON
- schema validation
- target type binding
- wrapper mismatch

If a wrapper was expected but bare JSON arrives, parsing retries once with the original JSON. A
typed value is not exposed in stream updates and is read only from the final response.

## 10. Error model

| Error | Type direction |
| --- | --- |
| null/invalid option, invalid URI | `IllegalArgumentException` |
| unsupported mode/seam | `IllegalStateException` |
| model/provider failure | `ModelException` + category/cause |
| tool validation | `ToolArgumentException` |
| tool execution | `ToolExecutionException` |
| structured parse/validation | separate unchecked subtype |
| cancellation | cancellation outcome; no generic exception wrapping |

A protocol binder converts these categories to HTTP/A2A/MCP errors without parsing message
strings.

## 11. Tests

### Unit

- message normalization, content projection, custom role round-trip
- immutable option merge and provider option rejection
- response update coalescing
- streaming map/flat-map preserves finalizer/result/cleanup hooks
- approval response executes/rejects before any resumed model call
- injected messages drain between tool results and the next model call
- per-service-call history persistence runs only when enabled
- schema inference fails closed
- structured parser selects only the last non-empty assistant text
- structured binding remains lazy until the final-response accessor
- result normalization, including the void/null distinction
- budget and approval matching
- run-level tool options win and same-name tools deduplicate deterministically

### Contract

- `ModelClientContract`
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

## 12. Current implementation

`ApiContract` and `EngineContract` are module markers and do not implement the behavior in this
document. The current status of every feature ID is `absent`; only the modules themselves are
`bootstrap`.

## 13. Requirement mapping

The canonical rows for `AGT`, `MSG`, `OUT`, and `TOOL` are in the
[Requirements traceability matrix](requirements-traceability-matrix.md). The target code family in
each row must match the packages and components in this document.
