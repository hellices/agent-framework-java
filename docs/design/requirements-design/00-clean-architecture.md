# Clean architecture and Java extension model

## 1. Purpose

Implement Microsoft Agent Framework's observable execution semantics in Java without including the
application runtime, DI container, or provider SDK in the core. The result is an `AgentEngine` that
can be embedded in plain Java; Spring Boot, Quarkus, and Jakarta EE are host adapters that assemble
the same ports.

## 2. Layers

```text
Application / host
  Spring Boot | Quarkus | Jakarta EE | CLI | plain Java
             |
Framework adapters and starters
             |
Protocol / provider / storage / telemetry adapters
             |
Application ports and public contracts
             |
AgentEngine and optional workflow runner
             |
Immutable domain values and deterministic state transitions
```

### 2.1 Public contracts

`agent-framework-api` owns the following:

- application-facing `Agent` and execution input/output
- immutable message, content, tool, and session values
- public ports implemented by providers, storage, and telemetry
- typed interceptors and lifecycle contracts

Ports are public APIs, but they reside in the
`io.github.hellices.agentframework.spi.*` package to distinguish them from application convenience
APIs. No separate SPI artifact is created initially. Splitting the module before there is evidence
that the API and SPI release cycles actually differ would only increase consumer dependencies.

General developers use the `AgentFactory`, `AgentBuilder`, `Agent`, `AgentRun`, `ToolSet`,
`Workflow`, and `Harness` facades rather than the SPI. The detailed usability contract is defined in
[Developer experience](06-developer-experience.md).

### 2.2 Application engine

`agent-framework-engine` depends only on the public API.

- run/turn state machine
- model→tool→model loop
- session load/change/persist ordering
- interceptor invocation
- stream finalization

Public entry points are limited to `AgentEngine` and its builder/factory. The state machine
implementation resides in `io.github.hellices.agentframework.engine.internal.*` so adapters cannot
reference it.

### 2.3 Optional application subsystems

- `workflow/agent-framework-workflow-api`: immutable graph, event, run, and checkpoint contracts
- `workflow/agent-framework-workflow-core`: graph validation and run/superstep state machine
- `integrations/agent-framework-harness`: optional facade that assembles an `Agent`
- `hosting/agent-framework-hosting-core`: target/session/checkpoint coordination only

These modules do not depend on engine internals. They compose through public APIs such as `Agent`,
session ports, and tool/content contracts.

### 2.4 Adapters

- `providers/`: model provider SDK adapters
- `integrations/`: MCP, storage, memory, Spring AI, and telemetry adapters
- `hosting/`: framework-neutral hosting coordination and standalone assembly
- `protocols/`: Responses, A2A, AG-UI, and MCP hosting wire adapters
- `starters/`: dependency-only framework starter artifacts

An adapter implements an outbound port or converts an inbound protocol to a public use case.
Adapters neither duplicate the engine state machine nor take ownership of the tool loop.

Integration is not endpoint-first. Internal framework users inject `AgentEngine` and `Agent` as
container-native components without going through an endpoint. Only applications that actually
expose a wire protocol such as Responses, A2A, or AG-UI add its separate opt-in module.

## 3. Dependency rules

```text
api                 <- engine
api                 <- provider/integration adapters
api + engine        <- plain-Java assembly and host auto-configuration
api                 <- workflow-core
workflow-api        <- workflow-core
api + workflow API  <- workflow adapters
api + hosting API   <- protocol adapters and binders
testkit             <- api
samples             <- published products (never reverse)
```

Prohibited:

- API→engine, API→framework/SDK
- provider/protocol→engine internals
- core→starter
- product→sample/testkit
- adapter discovery through a static global registry

## 4. Java public API rules

### 4.1 Values

- Default to immutable final classes and defensive copies.
- Provide a builder and `toBuilder()` for requests/options with many optional fields.
- Use records only for values with fixed components, such as IDs, closed snapshots, and stable
  events.
- Copy collections on construction and return unmodifiable views.
- Public parameters are non-null; represent an absent return value as `Optional<T>`.

### 4.2 Extension points

- Providers, tools, stores, and interceptors are small open interfaces.
- Only closed engine states and protocol discriminators may use sealed hierarchies.
- Create typed keys such as `ContextKey<T>` and `CapabilityKey<T>` through public factories without
  requiring global registration.
- Preserve provider-specific data in adapter-owned immutable options/values or typed extension
  envelopes.

### 4.3 Async and streaming

Framework-neutral public asynchronous contracts:

- single asynchronous result: `CompletionStage<T>`
- backpressure stream: `Flow.Publisher<T>`
- long-running execution control: `RunHandle`
- cancellation: explicit `CancellationSignal` and standard cancellation bridge

Framework adapters convert these types bidirectionally to Reactor, Mutiny, or Jakarta asynchronous
types and provide cancellation/backpressure contract tests.

The engine does not create executors or schedulers. Parallel tool execution composes an
`ExecutionStrategy` injected by the host or asynchronous results returned by a port.

### 4.4 Errors

- argument/state: `IllegalArgumentException`, `IllegalStateException`
- external/runtime: bounded-context unchecked exception + machine-readable category
- cancellation: separate control outcome; not wrapped in a generic framework exception
- adapters preserve the cause, and protocol binders convert it to an HTTP/A2A/MCP error

## 5. Framework assembly

### 5.1 Plain Java

The plain Java builder is the reference assembly.

```text
AgentEngine.builder()
  -> sessionStore(port)
  -> executionStrategy(host-owned)
  -> interceptors(explicit ordered list)
  -> build()

AgentFactory(engine, modelCatalog)
  -> builder() / builder(modelName) / builder(ModelClient)
```

Every feature provided by a framework adapter must also be expressible through this builder.

`hosting/agent-framework-standalone` is the batteries-included facade for this reference assembly.
It creates neither a server nor a DI container; it combines a JDK client, a host-owned execution
strategy, an in-memory store, and provider adapters into one `AutoCloseable` assembly. The
standalone path is the reference implementation for all framework contract tests.

### 5.2 Spring Boot

- `*-autoconfigure`: conditional beans, ordered customizers, and configuration properties
- `*-starter`: dependency aggregation only
- user beans take precedence over auto-configured beans
- convert `ObjectProvider`/ordered beans into explicit builder inputs
- Spring AI `ChatModel`/`ToolCallback` is an optional adapter
- disable Spring AI automatic tool execution when using the engine tool loop

The priority for Spring integration is use as native beans within a Spring application. Protocol
endpoint auto-configuration is enabled only when the starter for that protocol is explicitly
added.

### 5.3 Quarkus

- provide the `quarkus-agent-framework` runtime and
  `quarkus-agent-framework-deployment` as a first-class extension from the first release
- the runtime artifact owns the `META-INF/quarkus-extension.*` descriptor and consumer API
- the deployment artifact owns generated route/tool indexing, native-image hints, and
  recorder/build steps
- convert Mutiny `Uni`/`Multi` to the core async/stream contracts at the boundary
- do not expose build items, recorders, or Arc container types in the core API

The two artifacts are sibling projects under `integrations/`, and the runtime coordinate remains
stable. Even while there are few deployment build steps, do not omit the Quarkus CLI/platform
extension contract.

### 5.4 Jakarta EE

- assemble ports and the engine through CDI producers/portable extensions
- request/application scopes and `SecurityContext` are container-owned
- convert Jakarta REST asynchronous responses/streams in protocol binders
- the core does not reference `BeanManager`, `Instance<T>`, or transaction APIs

## 6. Lifecycle and scope

| Resource | Owner | Core behavior |
| --- | --- | --- |
| model/MCP HTTP client | adapter or host that created it | does not close a borrowed client |
| executor/scheduler | host/container | does not retain it, or references it through an explicit lifecycle port |
| session transaction | store adapter/host | defines only state transition ordering |
| telemetry provider/exporter | host bootstrap | emits only semantic events |
| request security context | host binder | passes only validated user/session context |
| engine | application scope | created and closed by the host |
| run/session context | run/session scope | no global static state |

## 7. Serialization and native image

- stable type ID + schema version + injected codec registry
- prohibit Java native serialization and class-name loading
- codec registry is instance-scoped and immutable after build
- annotation processors are the default generation path for workflow route/tool metadata
- the reflection path is an explicit opt-in and fails closed when metadata is insufficient
- framework native-image metadata is owned by the corresponding adapter

## 8. Test layers

1. unit: immutable values, merge rules, state transitions
2. API contract: provider/store/interceptor/target resolver implementations
3. golden compatibility: pinned upstream scenarios
4. wire: SSE/A2A/AG-UI/MCP payloads and cancellation
5. framework assembly: plain Java, Spring Boot, Quarkus, Jakarta EE
6. architecture policy: dependency direction, public packages, no framework leakage

Exact natural language and exact tool-call ordering are not long-term contracts. Only observable
semantics such as event ordering, state transitions, budgets, and result shapes are fixed.

## 9. First vertical slice

The first end-to-end slice is not a wrapper over Spring AI or LangChain4j.

```text
standalone AgentFactory
  -> AgentEngine
    -> direct provider ModelClient
    -> engine-owned tool loop
    -> engine-owned session/interceptor/finalization
```

Provider adapters own only the conversion of provider requests, responses, streams, and
cancellation. The SDK's automatic tool loop, memory, middleware, and retry runtime are not used as
the reference for execution semantics.

Slice acceptance:

- a standalone sample runs without Spring, Quarkus, or Jakarta
- the same scenario passes when the direct provider adapter is replaced with a deterministic fake
- model → tool → model iterations are observable as AgentEngine events
- engine contract tests verify session save/restore, cancellation, streaming finalization, and
  interceptor ordering
- provider SDK automatic tool execution is disabled

Spring AI and future LangChain4j integrations are added after this slice as `ModelClient`, `Tool`,
MCP, and telemetry adapters. They must pass the same contract suite and must not replace engine
semantics.

## 10. Major ADR candidates

| ADR | Decision |
| --- | --- |
| Async bridge | `CompletionStage`/`Flow.Publisher` cancellation and framework reactive bridge |
| API evolution | record/final class criteria and binary compatibility baseline |
| Extension values | typed keys and provider extension envelope |
| Schema | framework-neutral type descriptor and schema generator SPI |
| State codec | registry freeze, version migration, copy strategy |
| Execution strategy | host-owned concurrency and structured cancellation |
| Framework adapters | evidence for adding the Quarkus deployment split |

An ADR cannot change a requirement. It is used only to select public APIs and operational
trade-offs when multiple implementations satisfy a requirement.
