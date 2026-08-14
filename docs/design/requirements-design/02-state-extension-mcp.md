# State, extension, and MCP design

## 1. Scope

- `SES-001..020`: session, snapshot, history, context
- `INT-001..022`: typed interceptor, compaction
- `MCP-001..019`: MCP client tools and hosting helpers

## 2. Modules and packages

```text
agent-framework-api
  api.session      AgentSession, SessionId, SessionSnapshot, SessionContext
  api.interceptor  typed contexts, interceptors, ContextKey
  spi.session      SessionStore, SessionCodec, StateCodecRegistry

agent-framework-engine
  internal.session SessionCoordinator, SnapshotValidator, HistoryCoordinator
  internal.interceptor InterceptorPipeline
  internal.compaction CompactionCoordinator

integrations/agent-framework-mcp
  MCP client tools, transport adapters, discovery, sampling/task support

protocols/agent-framework-mcp-hosting
  Agent/workflow-as-tool helpers only
```

MCP SDK types do not escape the integration/protocol modules.

## 3. Session aggregate

`AgentSession` is the single source of truth for durable agent state.

- local `SessionId`
- agent compatibility identity/version
- provider service handles as adapter-owned values
- typed state namespaces
- approval/injection queue references
- history policy metadata
- optimistic version

A service conversation ID is not an authorization boundary. The hosting layer provides the
host-validated `UserContext` and tenant/session routing key.

### 3.1 Snapshot

```text
SessionSnapshot
  envelopeType
  schemaVersion
  sessionId
  serviceSessionId / provider service handles
  revision
  createdAt
  state entries: stableTypeId + codecVersion + payload
```

Snapshots are immutable. The in-memory store ensures isolation through immutable values, a codec
round trip, or copy-on-write, and never returns a live mutable reference.

### 3.2 Codec registry

`StateCodecRegistry` is assembled with a builder and is immutable after it is frozen.

- stable type id↔Java type↔codec
- reject duplicate types/IDs
- version validation/migration
- framework built-ins explicitly registered
- no class-name deserialization
- no static/global registry

## 4. Session store

```text
SessionStore.load(SessionId)                 -> explicit absence or versioned snapshot
SessionStore.create(...)
SessionStore.save(snapshot)                  -> store-specific new version
SessionStore.compareAndSave(snapshot, expectedVersion) -> optional optimistic capability
SessionStore.delete(...)
```

The store owns transactions and persistence technology. The engine defines only load→run→save
ordering and capability-specific conflict outcomes. The file store uses last-writer-wins through a
temporary write and atomic replacement, while a database store may optionally provide optimistic
compare-and-save.

file store:

- canonical root confinement
- symlink/real-path validation
- temp write + atomic replace
- distinguish corrupt input from a schema mismatch

## 5. SessionContext and providers

A new `SessionContext` is created for every run.

- immutable session identity
- cancellation
- typed `ContextKey<T>` attributes
- provider namespaces
- staged state changes

A context provider reads and writes only its own namespace. It does not hide session-specific state
in provider instance fields or a global cache.

### 5.1 History

`HistoryProvider` opts into load and store independently.

- do not use service-managed history and local history at the same time
- avoid duplicate leading instructions
- store the message injection queue in the session
- give external memory an explicit scope and untrusted attribution

## 6. Typed interceptor pipeline

This pipeline is the Java counterpart of upstream MAF middleware. It uses a different name because
the term middleware can encompass HTTP filters, Spring AOP, CDI interceptors, and provider advisors,
blurring responsibility boundaries.

Four seams:

- `AgentRunInterceptor`
- `ModelCallInterceptor`
- `ToolCallInterceptor`
- `SessionOperationInterceptor`

Each context has an immutable request snapshot and controlled result replacement. There are no
arbitrary setters or `Map<String,Object>`.

controlled mutation surface:

- `stageToolsForNextIteration(ToolSet)`: takes effect in the next model iteration, not the current
  batch
- `onBeforeFinalResponse(...)`: typed transformation before final-response creation
- `onStreamFinalize(...)`: result/cleanup hook invoked once before the terminal signal
- result replacement: permitted only in the post phase of the corresponding seam

ordering:

```text
A-pre -> B-pre -> handler -> B-post -> A-post
```

Not calling `next` is a short circuit. Unsupported seams fail during building/initialization.
Adapters convert Spring `Ordered`, Quarkus priority, and CDI priority into a deterministic list; the
engine does not sort it again.

### 6.1 Boundary with framework AOP

Agent interceptors are not automatically converted into Spring `Advisor`, AOP Alliance
`MethodInterceptor`, WebFilter, or CDI interceptors. Only the nesting of the overall execution is
fixed.

```text
HTTP/security filter
  -> application service AOP
    -> AgentEngine
      -> AgentRunInterceptor
        -> ModelCallInterceptor
          -> model adapter
            -> provider-native advisor/client middleware
```

The Spring adapter collects only beans that implement an Agent Framework interceptor interface.

1. Sort by Spring `Ordered`/`@Order`.
2. Sort equal-order entries deterministically by bean name.
3. Convert them into a list of `InterceptorRegistration(id, order, interceptor)`.
4. The engine preserves registration order and does not sort again.

Quarkus/Jakarta EE adapters likewise convert priority and bean identifier into the same registration
model. They do not create a single global order across container AOP and agent interceptors.

Cross-seam functionality is assembled as an immutable `InterceptorBundle`; registrations within the
bundle follow the same ordering contract.

## 7. Compaction

Compaction is an optional strategy.

```text
CompactionStrategy
  trigger: CompactionCondition
  target: CompactionCondition (optional; default = trigger.inverse())
  shouldStart(CompactionContext, history, budget)
  reachedTarget(CompactionContext, projectedHistory, budget)
  project(CompactionContext, history, index)
  afterPersist(CompactionContext, snapshot) [optional]
```

`CompactionContext` contains the run `CancellationSignal` and typed attributes. The summarization
model call, projection, and persistence all propagate the same signal through adapter cancellation.

invariants:

- tool call/result group atomicity
- stable group id/count across incremental updates
- start and stop criteria separated
- token/message/turn/group/tool-call conditions compose into trigger and target
- failed summarization restores original projection
- summary result contains only replacement message; original group is removed by coordinator
- internal index and trace metadata are explicit snapshot state

Providers do not mutate the original history list directly.

## 8. MCP client boundary

### 8.1 Ownership

high-level transport adapter:

- owns connect/reconnect/close
- idempotent lifecycle
- clears discovery/capability/sampling/task state on close

borrowed-client adapter:

- wraps connected client
- receives no transport config
- never opens/closes borrowed resource

When asynchronous close is required, use a completion-returning lifecycle port.

MCP transports/clients are replaceable adapters.

- `integrations/agent-framework-mcp`: MCP Java SDK direct adapter
- `integrations/agent-framework-spring-ai-mcp`: borrowed-client adapter around an MCP connection
  managed by Spring AI

Direct and borrowed adapters may coexist for different named servers. Each server/connection
assembly selects exactly one `McpClientPort` adapter and fails at startup if a server has multiple
candidates. Only the direct adapter owns transport/connect/auth and open/close. Borrowed Spring AI
and connected-client adapters use the provider/host-owned lifecycle, headers, and authentication and
do not close the client. The Agent Framework integration owns discovery normalization, collisions,
sampling budgets, task transitions, cancellation, and session/tool-loop semantics.

### 8.2 Discovery

- cursor pagination to completion
- optional local prefix
- normalized-name collision fails
- immutable discovered tool definitions
- reload publishes a new snapshot, not in-place mutation

### 8.3 Prompts and resources

- Convert MCP prompt descriptors into executable `Tool` instances under the prefix/collision rules.
- Expose prompt arguments as JSON Schema and normalize invocation results into core
  `Message`/`Content`.
- Convert MCP resources into `McpResourceContent` or an equivalent typed content payload rather than
  presenting them as executable tools.
- Preserve URI, MIME type, text/blob discriminator, and provider metadata in resource payloads.
- Prompt/resource pagination and cancellation use the same client lifecycle as tool
  discovery/invocation.

### 8.4 Invocation

- model arguments and MCP metadata separate
- trace/header propagation only through explicit context
- each call creates an immutable header snapshot that is not shared with other concurrent calls
- inject dynamic credentials only for the same origin and remove them on cross-origin redirects or
  alternate origins
- sampling denied without explicit callback and bounded by request/token budgets
- task-required calls switch once to task lifecycle; original call not reissued
- local cancel/timeout attempts best-effort remote cancel

### 8.5 Task lifecycle

```text
required task metadata
  -> callToolAsTask
     -> plain inline result: normalize and complete
     -> task id:
          clamp server poll interval to configured min/max
          poll tasks/get until terminal or maxTaskWait
          completed -> tasks/result -> same ToolResult shape as inline call
          failed/cancelled/input_required -> typed ToolExecutionException/outcome
          malformed terminal/result payload -> explicit failure
```

After receiving a task ID, do not republish the same logical call through `tools/call`. Local
cancellation and `maxTaskWait` expiry attempt best-effort `tasks/cancel` by default and may be
disabled only through an explicit compatibility option.

## 9. MCP hosting helper

`protocols/agent-framework-mcp-hosting` exposes only:

- agent-as-tool adapter
- workflow-as-tool adapter
- run request/result converters

It does not own an MCP server, transport, authentication, prompts, resources, sampling, or task
hosting. Those remain application/server SDK responsibilities.

The schema is captured at adapter construction. Changing an agent/workflow requires a new adapter
instance. The final MCP tool result is atomic even if the hosted run streamed internally.

## 10. Framework assembly

- plain Java: explicit registry/store/interceptor/MCP client builder
- Spring Boot: ordered bean collection and conditional adapter beans
- Quarkus/Jakarta EE: CDI producers provide the same instances
- no adapter uses core ServiceLoader/global discovery
- container owns client/executor/scope lifecycle

## 11. Tests

### Session

- duplicate codec/type id
- unregistered state and invalid codec output
- cold-start restore and version mismatch
- immutable/branch-independent in-memory snapshots
- file last-writer-wins vs optional optimistic compare-and-save
- file traversal, atomic replace, corruption recovery
- optimistic conflict

### Interceptor/compaction

- pre/post order and short-circuit
- controlled result replacement
- next-iteration tool staging and finalization hooks
- compaction cancellation propagation
- typed attribute collision
- unsupported seam fail-fast
- tool group atomicity, incremental index, summary rollback

### MCP

- borrowed vs owned close contract
- reconnect once and cache clearing
- paginated discovery/collision
- immutable per-call same-origin header snapshots
- argument/metadata separation
- prompt argument/result and embedded-resource conversion
- sampling budget/default deny
- task switch/no duplicate call
- poll interval clamp, inline fallback, timeout/terminal/malformed task result
- cancellation to remote
- public API boundary of hosting helper

## 12. Current implementation

There is no production session/interceptor/MCP code. `DeterministicClock` may be reused later as a
snapshot/checkpoint test fixture, but it does not implement the required behavior.

## 13. Requirements mapping

Canonical rows for `SES`, `INT`, and `MCP` are in the
[Requirements traceability matrix](requirements-traceability-matrix.md).
