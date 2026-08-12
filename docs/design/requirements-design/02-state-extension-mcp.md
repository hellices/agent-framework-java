# State, extension, and MCP design

## 1. 범위

- `SES-001..020`: session, snapshot, history, context
- `INT-001..022`: typed interceptor, compaction
- `MCP-001..019`: MCP client tools and hosting helpers

## 2. 모듈과 패키지

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

MCP SDK type은 integration/protocol module 밖으로 나오지 않는다.

## 3. Session aggregate

`AgentSession`은 durable agent state의 단일 기준이다.

- local `SessionId`
- agent compatibility identity/version
- provider service handles as adapter-owned values
- typed state namespaces
- approval/injection queue references
- history policy metadata
- optimistic version

service conversation id는 authorization boundary가 아니다. host가 검증한 `UserContext`와
tenant/session routing key는 hosting layer가 제공한다.

### 3.1 Snapshot

```text
SessionSnapshot
  envelopeType
  schemaVersion
  sessionId
  revision
  createdAt
  state entries: stableTypeId + codecVersion + payload
```

snapshot은 immutable하다. 인메모리 store는 immutable 값, codec round-trip, copy-on-write 중
하나로 독립성을 보장하고 live mutable reference를 반환하지 않는다.

### 3.2 Codec registry

`StateCodecRegistry`는 builder로 조립하고 freeze 후 불변이다.

- stable type id↔Java type↔codec
- duplicate type/id 거부
- version validation/migration
- framework built-ins explicitly registered
- no class-name deserialization
- no static/global registry

## 4. Session store

```text
SessionStore.load(SessionId)                 -> explicit absence or versioned snapshot
SessionStore.create(...)
SessionStore.save(snapshot, expectedVersion) -> new version
SessionStore.delete(...)
```

store가 transaction과 persistence technology를 소유한다. engine은 load→run→save ordering과
optimistic conflict outcome만 정의한다.

file store:

- canonical root confinement
- symlink/real-path validation
- temp write + atomic replace
- corrupt parse와 schema mismatch 구분

## 5. SessionContext와 providers

매 run마다 새 `SessionContext`를 만든다.

- immutable session identity
- cancellation
- typed `ContextKey<T>` attributes
- provider namespaces
- staged state changes

context provider는 자신의 namespace만 읽고 쓴다. provider instance field나 global cache에
session-specific state를 숨기지 않는다.

### 5.1 History

`HistoryProvider`는 load/store를 독립적으로 opt in한다.

- service-managed history와 local history를 동시에 쓰지 않음
- leading instruction 중복 방지
- message injection queue는 session에 저장
- external memory는 explicit scope와 untrusted attribution

## 6. Typed interceptor pipeline

네 seam:

- `AgentRunInterceptor`
- `ModelCallInterceptor`
- `ToolCallInterceptor`
- `SessionOperationInterceptor`

각 context는 immutable request snapshot과 controlled result replacement를 가진다. arbitrary
setter와 `Map<String,Object>`는 없다.

ordering:

```text
A-pre -> B-pre -> handler -> B-post -> A-post
```

`next` 미호출은 short-circuit다. unsupported seam은 builder/initialization에서 실패한다.
Spring `Ordered`, Quarkus priority, CDI priority는 adapter가 deterministic list로 변환하며 engine이
다시 정렬하지 않는다.

## 7. Compaction

compaction은 optional strategy다.

```text
CompactionStrategy
  shouldStart(history, budget)
  project(history, index)
  afterPersist(snapshot) [optional]
```

invariants:

- tool call/result group atomicity
- stable group id/count across incremental updates
- start and stop criteria separated
- failed summarization restores original projection
- summary result contains only replacement message; original group is removed by coordinator
- internal index and trace metadata are explicit snapshot state

provider가 original history list를 직접 mutate하지 않는다.

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

async close가 필요하면 completion-returning lifecycle port를 사용한다.

### 8.2 Discovery

- cursor pagination to completion
- optional local prefix
- normalized-name collision fails
- immutable discovered tool definitions
- reload publishes a new snapshot, not in-place mutation

### 8.3 Invocation

- model arguments and MCP metadata separate
- trace/header propagation only through explicit context
- 각 호출은 immutable header snapshot을 만들고 다른 동시 호출과 공유하지 않음
- dynamic credentials는 같은 origin에만 주입하고 cross-origin redirect/alternate origin에서 제거
- sampling denied without explicit callback and bounded by request/token budgets
- task-required calls switch once to task lifecycle; original call not reissued
- local cancel/timeout attempts best-effort remote cancel

## 9. MCP hosting helper

`protocols/agent-framework-mcp-hosting` exposes only:

- agent-as-tool adapter
- workflow-as-tool adapter
- run request/result converters

It does not own an MCP server, transport, authentication, prompts, resources, sampling, or task
hosting. Those remain application/server SDK responsibilities.

schema is captured at adapter construction. Changing an agent/workflow requires a new adapter
instance. final MCP tool result is atomic even if the hosted run streamed internally.

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
- file traversal, atomic replace, corruption recovery
- optimistic conflict

### Interceptor/compaction

- pre/post order and short-circuit
- controlled result replacement
- typed attribute collision
- unsupported seam fail-fast
- tool group atomicity, incremental index, summary rollback

### MCP

- borrowed vs owned close contract
- reconnect once and cache clearing
- paginated discovery/collision
- argument/metadata separation
- sampling budget/default deny
- task switch/no duplicate call
- cancellation to remote
- public API boundary of hosting helper

## 12. 현재 구현

session/interceptor/MCP production code는 없다. `DeterministicClock`은 이후 snapshot/checkpoint
test fixture로 재사용할 수 있지만 요구 행동 구현은 아니다.

## 13. 요구사항 매핑

`SES`, `INT`, `MCP`의 canonical rows는
[Requirements traceability matrix](requirements-traceability-matrix.md)에 있다.
