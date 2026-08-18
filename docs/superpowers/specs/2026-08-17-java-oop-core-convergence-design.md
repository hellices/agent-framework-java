# Java OOP core convergence design

- Status: approved
- Date: 2026-08-17
- Scope: pre-1.0 core API and engine convergence before host and workflow integration
- Compatibility policy: breaking changes are allowed when they produce a coherent 1.0 contract

## 1. Purpose

Bring the implemented Java core back into alignment with the repository's approved clean
architecture while incorporating the portable-SDK lessons demonstrated by Microsoft Agent Framework
for Go.

The redesign does not transpose Go APIs into Java. It preserves observable Microsoft Agent Framework
semantics and expresses them through Java's nominal type system, object-oriented encapsulation,
dependency inversion, and JVM asynchronous contracts.

The result must have:

- one authoritative run state machine for ordinary and streaming execution;
- a model-independent `AgentEngine` application service;
- an immutable agent definition separate from runtime collaborators;
- a small application-facing `Agent` facade;
- open outbound ports and typed extension values;
- no provider SDK, DI container, reactive library, or JSON mapper type in the core API;
- failure, cancellation, finalization, and persistence semantics that do not depend on incidental
  configuration.

## 2. Why convergence is needed now

The approved requirements-driven design already describes a model-independent engine and immutable
agent definition. The implementation has not yet reached that shape:

- `AgentEngine` extends `Agent` and contains one agent's identity, model, tools, providers, and
  session configuration.
- ordinary and streaming tool loops have separate execution implementations.
- a provider failure may be thrown synchronously or reported asynchronously depending on whether a
  session/context gate exists.
- optional model behavior is represented by a growing cross-product of capability interfaces.
- evolvable requests, responses, options, and sessions are exposed as public records.
- provider options, tool schemas, arguments, metadata, and session values expose raw
  `Map<String, Object>` contracts.
- `ContextProvider` mutates shared run context and every provider receives a persistent namespace,
  even when it is stateless.
- `HistoryProvider` is an abstract class even though it is an externally implemented SPI.

These choices are still inexpensive to change before a public 1.0 release. Host adapters, additional
providers, structured output, interceptors, and workflows would multiply their migration cost.

## 3. Design principles

### 3.1 Object roles are explicit

Each public object has one reason to change:

- a definition describes an agent;
- an engine executes definitions;
- an agent facade binds a definition to runtime collaborators;
- a run handle controls and observes one execution;
- a port represents one outbound capability;
- a state machine owns mutable state for one run only.

Inheritance is used only for the application-facing `Agent` polymorphic contract. Runtime
composition uses final classes and delegation.

### 3.2 Dependency inversion remains the primary boundary

The API module owns application contracts and outbound ports. The engine depends on those contracts.
Providers, storage, telemetry, MCP, and host integrations implement or assemble public ports.

No adapter references an engine-internal type. No core type references Spring, Reactor, Mutiny,
Jackson, an HTTP client, or a provider SDK.

### 3.3 One execution has one source of truth

Ordinary and streaming APIs are two views of the same execution. They do not have independent tool
loops, lifecycle hooks, finalizers, or persistence paths.

### 3.4 Public types encode their constraints

A public signature must communicate whether a value is JSON-safe, provider-specific, optional,
stateful, or extensible. Documentation alone must not narrow an `Object` contract.

### 3.5 Policy is composable without losing ownership

Interceptors implement chain-of-responsibility around typed seams. They do not take ownership of the
run state machine. The core continues to own tool iteration, approval, cancellation, finalization,
and session persistence.

## 4. Target architecture

```text
Application / host
        |
        v
AgentFactory -----> AgentBuilder
        |                 |
        |                 v
        |          AgentDefinition
        |                 |
        +-------> BoundAgent (package-private engine implementation)
                          |
                          v
                     AgentEngine
                          |
                          v
                     RunPipeline
                          |
        +-----------------+-------------------+
        |                 |                   |
   ModelClient       SessionStore       typed interceptors
        |                 |                   |
 provider adapter   storage adapter     host/integration policy
```

`BoundAgent` is an implementation name used by this design. It is not a public contract.

## 5. Public object model

### 5.1 `AgentDefinition`

`AgentDefinition` is an immutable final class in `api.agent`.

It contains only declarative agent identity and behavior:

- stable id;
- name and description;
- instructions;
- immutable tool declarations;
- default run options that are provider-neutral;
- typed extension attributes whose lifetime belongs to the definition.

It does not contain:

- an executor or scheduler;
- a session store;
- telemetry exporters;
- a DI context;
- a provider SDK object;
- mutable run or session state.

It uses a builder and `toBuilder()`. It is not a record because structured output defaults, tool
policy, and future agent metadata will evolve.

### 5.2 `AgentEngine`

`AgentEngine` is a final, thread-safe, model-independent application service in
`agent-framework-engine`.

It owns shared execution policy and services:

- run pipeline factory;
- ordered interceptor registries;
- session coordination;
- tool execution strategy;
- state codec registry;
- telemetry port;
- framework limits and safe defaults.

It does not extend `Agent` and does not contain one agent's id, instructions, tools, or selected
model.

It exposes a binding operation used by `AgentFactory`:

```java
Agent bind(AgentDefinition definition, AgentRuntime runtime);
```

`AgentRuntime` is an immutable public advanced-assembly contract in `api.agent`. It contains only
public runtime collaborators whose selection may vary per agent:

- selected `ModelClient`;
- executable tool bindings keyed by declared tool name;
- ordered context/history providers;
- per-agent session policy;
- per-agent interceptor registrations when a seam explicitly permits them.

General users do not construct it; `AgentFactory` and host adapters do. Making it public preserves
plain-Java assembly and lets Spring, Quarkus, Jakarta EE, and future workflow modules bind an
`AgentDefinition` without referencing engine internals.

### 5.3 `Agent`

`Agent` remains the single application-facing abstract polymorphic type.

It keeps:

- identity accessors;
- `run(...)`;
- `runStreaming(...)`;
- decorator support.

The lifecycle implementation currently embedded in the abstract base is reduced to stable facade
behavior. Engine-specific execution is delegated to the bound engine object rather than inherited
by `AgentEngine`.

The default engine implementation is package-private. Users depend on `Agent`, not on
`DefaultAgent`, `BoundAgent`, or the state machine.

### 5.4 `AgentFactory` and `AgentBuilder`

`AgentFactory` is the composition boundary between:

- the shared `AgentEngine`;
- a `ModelCatalog`;
- immutable agent definitions;
- per-agent runtime collaborators.

Every builder call returns a fresh, thread-confined builder. `build()` creates an immutable
definition and binds it to the engine. Builders do not expose engine internals.

Advanced users can build an `AgentDefinition` independently and ask the factory to bind it. The
builder accepts convenient executable tools but splits each one into a declaration owned by the
definition and a handler binding owned by `AgentRuntime`. This supports configuration files,
framework bean definitions, tests, and workflow-as-agent composition without making definitions
executable by themselves.

## 6. Unified execution model

### 6.1 `RunExecution`

Every call creates one internal `RunExecution`. It owns:

- a run id;
- immutable input and definition;
- effective runtime collaborators;
- cancellation signal;
- mutable `RunState`;
- one terminal outcome;
- one update publisher.

An `AgentRun` and an `AgentStreamingRun` are projections over a `RunExecution`; they are not separate
executions.

### 6.2 `RunPipeline`

`RunPipeline` is the application use-case coordinator. It advances one `RunStateMachine` through:

```text
VALIDATE
  -> LOAD_SESSION
  -> PREPARE_CONTEXT
  -> RESOLVE_APPROVAL
  -> PREPARE_MODEL_REQUEST
  -> CALL_MODEL
  -> ACCUMULATE_MODEL_UPDATES
  -> PLAN_TOOL_ACTION
       -> WAIT_APPROVAL
       -> EXECUTE_TOOL_BATCH
       -> APPEND_TOOL_RESULTS
       -> PREPARE_MODEL_REQUEST
  -> FINALIZE_RESPONSE
  -> COMPLETE_CONTEXT
  -> PERSIST_SESSION
  -> TERMINATE
```

The pipeline is shared by ordinary and streaming entry points. Tool decisions, usage merging,
message reconstruction, approval, context completion, and session persistence exist once.

### 6.3 Streaming and ordinary views

The internal execution is update-oriented:

- model adapters produce `ModelResponseUpdate` publishers;
- a provider with only a single response is adapted to one terminal update without blocking;
- the state machine consumes updates and emits agent updates;
- the final response is reconstructed from the same accepted updates and engine-generated tool
  events.

`AgentRun.response()` observes the terminal response without exposing updates.

`AgentStreamingRun.updates()` exposes updates, while `response()` observes the same execution's
terminal response.

The streaming view is a cold, unicast publisher. Its first subscriber starts the execution and owns
backpressure; a second subscriber receives `onSubscribe` followed by `onError` with an
`IllegalStateException`. Calling `response()` alone does not start a streaming run. The ordinary
view starts immediately and installs an internal collecting subscriber. Both forms create exactly
one upstream model subscription per model iteration and never silently duplicate a run.

### 6.4 Terminal ordering

The update publisher does not emit `onComplete` until all success-critical lifecycle work has
finished:

1. final model/tool update accepted;
2. response reconstructed;
3. context providers completed;
4. session persisted;
5. terminal response published;
6. update publisher completes.

If context completion or persistence fails, the update publisher terminates with `onError` and the
response/session stages fail with the same root cause. This removes the current split where updates
can complete successfully before the authoritative run fails.

## 7. Model port redesign

### 7.1 One invocation contract

The cross-product of `ModelClient`, `StreamingModelClient`, `ContinuationModelClient`, and
`StreamingContinuationModelClient` is replaced by one model invocation contract.

Preferred target:

```java
public interface ModelClient {
  Flow.Publisher<ModelResponseUpdate> execute(ModelRequest request);
}
```

The request carries continuation state explicitly. A provider that implements a non-streaming
remote API returns a publisher that emits one complete update. No engine or adapter blocks to create
that publisher.

This contract is selected because:

- the final response is already required to be reconstructable from updates;
- tool and approval execution naturally consume a sequence;
- continuation is invocation data, not a different object capability;
- structured output and service-managed history do not create interface cross-products;
- every provider follows the same error and cancellation path.

### 7.2 Capabilities

Capabilities describe valid requests; they do not choose alternate engine implementations.

`ModelCapabilities` is an immutable value queried from a client or catalog entry. It begins with
capabilities required by implemented features only:

- supported structured output modes;
- whether the service manages conversation history;
- supported content kinds when validation before remote invocation is possible.

Streaming is not a boolean capability because every model call has an update publisher. Native
incremental delivery may be exposed as descriptive metadata but does not alter the contract.

Capability declarations are validated against adapter contract tests. Unsupported requested
behavior fails before remote I/O.

### 7.3 Model request and options

`ModelRequest` becomes an immutable final class with a builder. It carries:

- messages;
- typed neutral options;
- continuation token/state;
- cancellation signal;
- tool definitions;
- typed execution attributes.

Provider-specific options implement an open marker contract owned by the API, with immutable option
types owned by provider modules. A provider reads only its option type. There is no primary
`Map<String, Object>` provider option API and no permanent legacy option conversion path.

## 8. Typed value model

### 8.1 JSON values

Introduce a closed, immutable `JsonValue` hierarchy:

- null;
- boolean;
- number;
- string;
- array;
- object.

It is framework-owned and may be sealed because JSON value kinds are closed. It replaces raw
`Object` payloads in JSON-shaped session values, tool arguments, JSON schemas, and serializable
extension envelopes. A typed session value may instead remain an immutable Java object behind
`SessionStateKey<T>` when an explicit `StateCodec<T>` owns its persisted representation.

Adapters convert between `JsonValue` and Jackson, provider SDK, or protocol representations outside
the core API.

### 8.2 Evolvable values

The following categories use final classes with builders and copy methods:

- requests and options;
- agent/model responses;
- response updates;
- agent definitions;
- session handles when new metadata is expected;
- tool definitions.

Records remain appropriate only for fixed values such as stable identifiers, closed coordinates,
and version metadata.

### 8.3 Typed attributes

Execution-only extensions use `ContextKey<T>` or an equivalent typed key. Session state uses
`SessionStateKey<T>` and exposes no raw parent map; its values are either `JsonValue` or immutable
types with an explicitly registered state codec.

Raw provider SDK objects may be exposed only as transient diagnostic handles with explicit lifetime
and non-serialization guarantees.

## 9. Context and history providers

### 9.1 Contribution instead of shared mutation

`ContextProvider` becomes an open interface whose pre-run operation returns an immutable
`RunContribution`:

```text
RunContribution
  context messages
  instruction additions
  additional tools
  typed model options
```

The engine merges contributions in registration order using explicit rules:

- messages preserve contribution order;
- instruction additions use a documented concatenation policy;
- duplicate tool names fail before the model call;
- neutral options merge by declared precedence;
- conflicting provider-specific options fail unless their owning type defines replacement.

The engine applies contributions to its private state. Providers do not mutate core-owned request
collections.

### 9.2 Stateful provider capability

A stateless context provider does not receive or reserve a session namespace.

A provider that persists state implements `StatefulContextProvider<S>` and exposes a stable
`SessionStateKey<S>`. The engine binds it to `ProviderSessionState<S>`, a restricted typed state view,
and rejects duplicate stable ids at assembly.

### 9.3 History

`HistoryProvider` becomes an open interface dedicated to conversation history storage. Policy-driven
load/store behavior is supplied through composition or a convenience implementation, not mandatory
inheritance.

History remains distinct from general context:

- history loads and stores conversation messages;
- context providers may contribute instructions, tools, model options, or retrieved messages;
- service-managed history suppresses local default history;
- audit/evaluation sinks declare whether they load, store, or both.

The existing versioned session format, codec registry, provider namespace isolation, and
load-before-run/save-after-success ordering remain.

## 10. Interceptors and tool policy

Typed interceptor seams remain:

- agent execution;
- model invocation;
- tool invocation;
- session operation.

Each interceptor follows chain-of-responsibility with:

- an immutable typed context;
- a `next` invocation object;
- pre-processing in registration order;
- post-processing in reverse order;
- explicit short-circuiting;
- result replacement only through typed return values.

The tool loop is not delegated to a provider or host middleware. It remains a core state-machine
responsibility.

Tool auto-call, budgets, approval, and telemetry may be factored into engine-owned policy objects or
built-in interceptor bundles, but:

- provider SDK automatic tool execution is disabled;
- approval requests/responses are core content values;
- approval state is persisted through the session state machine;
- interceptor installation cannot create two tool loops;
- disabling a convenience policy cannot bypass mandatory safety limits.

## 11. Error and cancellation semantics

Synchronous exceptions are limited to local contract violations detectable before a run starts:

- null or invalid arguments;
- incompatible definition/runtime assembly;
- unsupported requested capability;
- duplicate tools or provider state keys.

After a run handle is returned, all external and lifecycle failures are asynchronous:

- provider transport/protocol failure;
- context/history failure;
- tool execution failure according to tool policy;
- session load/save failure;
- cancellation.

The ordinary response stage, streaming terminal signal, and session stage preserve the same root
failure. Configuration does not change where that failure is observed.

Cancellation:

- is represented by one signal owned by the run;
- reaches session loading, providers, model calls, tools, interceptors, and persistence where
  cancellation is still valid;
- never becomes a successful empty response;
- is not wrapped as an ordinary provider failure.

## 12. Module and dependency impact

No new production module is required for core convergence.

```text
agent-framework-api
  AgentDefinition, revised Agent/Run contracts
  JsonValue and typed attributes
  revised model/context/history/interceptor ports

agent-framework-engine
  AgentEngine application service
  package-private bound Agent implementation
  unified RunPipeline and RunStateMachine
  adapters for single-response model implementations

agent-framework-testkit
  deterministic run scripts and contract suites

providers/*
  revised single model invocation port

integrations/*
  revised typed values and lifecycle contracts
```

OpenTelemetry remains an optional integration artifact. The core owns semantic operation names and a
telemetry port, not the OTel SDK.

## 13. Migration strategy

This is a pre-1.0 convergence, not a compatibility layer project.

Implementation proceeds in coherent slices:

1. add characterization tests for current observable behavior;
2. introduce typed JSON and evolvable value classes;
3. introduce `AgentDefinition` and separate `AgentEngine` from `Agent`;
4. replace model capability cross-products with the single update contract;
5. implement the unified run pipeline and terminal ordering;
6. convert context/history providers to contribution and capability contracts;
7. add typed interceptors and approval on the unified pipeline;
8. migrate MCP, OpenAI, testkit, samples, and documentation;
9. remove obsolete types and legacy conversion methods;
10. run the full repository verification contract.

Temporary adapters may exist within one implementation branch to keep slices testable. They are not
published, documented, or retained in the final pre-1.0 API unless a concrete external consumer
requires them.

## 14. Verification

### 14.1 Architecture tests

- API has no engine, framework, mapper, reactive-library, or provider SDK dependency.
- `AgentEngine` does not extend `Agent`.
- provider and integration production code references only public API/SPI.
- only allowlisted fixed public values are records.
- raw `Map<String, Object>` is absent from primary public extension contracts.
- engine internals are not referenced outside the engine module.

### 14.2 Run contract tests

Every scenario runs through ordinary and streaming views of the same scripted execution:

- plain response;
- one and multiple tool iterations;
- tool failure;
- cancellation at each asynchronous boundary;
- context contribution;
- local and service-managed history;
- session restore and save;
- approval wait, denial, approval, and resume;
- structured output success and failure.

The tests assert identical:

- accepted message and content order;
- tool decisions and budgets;
- final response;
- usage;
- session state;
- root failure;
- lifecycle event order.

### 14.3 Provider contracts

Every provider verifies:

- one model invocation per engine iteration;
- a non-streaming response adapts to one update without blocking;
- native streaming preserves update order and cancellation;
- unsupported capabilities fail before remote I/O;
- provider-specific options are typed and isolated;
- borrowed resources are not closed.

### 14.4 Completion gate

Before host integration begins:

- the standalone sample uses only the final public facade;
- OpenAI and MCP pass their contract tests;
- ordinary and streaming execution share the same state machine;
- context/session persistence cannot fail after the update publisher reported success;
- design, traceability, module composition, and current-state documentation match implementation;
- `policyCheck`, `quality`, Java 17/21/25 tests, and `check` pass.

## 15. Deliberately deferred work

The convergence does not pull these features forward:

- compaction strategies;
- skills and file memory;
- graph workflows;
- checkpoint/HITL workflow runtime;
- A2A, AG-UI, or Responses hosting;
- additional provider adapters;
- Spring AI conversion.

They follow core convergence, provider streaming, interceptor/approval, telemetry, and host assembly.
Their contracts may use the new contribution, capability, definition, and run abstractions without
changing them.

## 16. Decision summary

1. Keep Microsoft Agent Framework observable behavior as the compatibility target.
2. Use Go as the closest portable-SDK architecture sibling, not as a Java API template.
3. Separate immutable agent definition, bound agent facade, and model-independent engine.
4. Use one update-oriented run pipeline for ordinary and streaming execution.
5. Replace model capability interface cross-products with one invocation contract plus typed
   capabilities.
6. Replace evolvable public records and raw object maps with builders, typed options, typed keys, and
   `JsonValue`.
7. Make context/history extension points open interfaces using immutable contributions and explicit
   state capability.
8. Keep tool-loop ownership, session ordering, and safety policy in the core.
9. Complete convergence before framework hosting or workflow implementation.
