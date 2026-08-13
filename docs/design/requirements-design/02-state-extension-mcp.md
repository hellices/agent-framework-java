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
  serviceSessionId / provider service handles
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
SessionStore.save(snapshot)                  -> store-specific new version
SessionStore.compareAndSave(snapshot, expectedVersion) -> optional optimistic capability
SessionStore.delete(...)
```

store가 transaction과 persistence technology를 소유한다. engine은 load→run→save ordering과
capability-specific conflict outcome만 정의한다. file store는 temp write + atomic replace의
last-writer-wins를 사용하고, database store는 optional optimistic compare-and-save를 제공할 수
있다.

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

이 pipeline은 upstream MAF middleware의 Java 대응물이다. 이름을 바꾼 이유는 middleware라는
말이 HTTP filter, Spring AOP, CDI interceptor, provider advisor까지 모두 포함해 책임 경계를
흐리기 때문이다.

네 seam:

- `AgentRunInterceptor`
- `ModelCallInterceptor`
- `ToolCallInterceptor`
- `SessionOperationInterceptor`

각 context는 immutable request snapshot과 controlled result replacement를 가진다. arbitrary
setter와 `Map<String,Object>`는 없다.

controlled mutation surface:

- `stageToolsForNextIteration(ToolSet)`: 현재 batch가 아니라 다음 model iteration에 반영
- `onBeforeFinalResponse(...)`: final response 생성 전 typed transform
- `onStreamFinalize(...)`: result/cleanup hook; terminal signal 전 한 번
- result replacement: 해당 seam의 post phase에서만 허용

ordering:

```text
A-pre -> B-pre -> handler -> B-post -> A-post
```

`next` 미호출은 short-circuit다. unsupported seam은 builder/initialization에서 실패한다.
Spring `Ordered`, Quarkus priority, CDI priority는 adapter가 deterministic list로 변환하며 engine이
다시 정렬하지 않는다.

### 6.1 Framework AOP와의 경계

Agent interceptor를 Spring `Advisor`, AOP Alliance `MethodInterceptor`, WebFilter, CDI
interceptor로 자동 변환하지 않는다. 전체 실행의 nesting만 고정한다.

```text
HTTP/security filter
  -> application service AOP
    -> AgentEngine
      -> AgentRunInterceptor
        -> ModelCallInterceptor
          -> model adapter
            -> provider-native advisor/client middleware
```

Spring adapter는 Agent Framework interceptor interface를 구현한 bean만 수집한다.

1. Spring `Ordered`/`@Order`로 정렬한다.
2. 같은 order는 bean name으로 결정적으로 정렬한다.
3. `InterceptorRegistration(id, order, interceptor)` 목록으로 변환한다.
4. engine은 등록 순서를 그대로 사용하고 재정렬하지 않는다.

Quarkus/Jakarta EE adapter도 priority와 bean identifier를 같은 registration model로 변환한다.
container AOP와 agent interceptor 사이에 하나의 전역 order를 만들지 않는다.

여러 seam에 걸친 기능은 immutable `InterceptorBundle`로 조립하되 bundle 내부 등록도 동일
order contract를 따른다.

## 7. Compaction

compaction은 optional strategy다.

```text
CompactionStrategy
  trigger: CompactionCondition
  target: CompactionCondition (optional; default = trigger.inverse())
  shouldStart(CompactionContext, history, budget)
  reachedTarget(CompactionContext, projectedHistory, budget)
  project(CompactionContext, history, index)
  afterPersist(CompactionContext, snapshot) [optional]
```

`CompactionContext`는 run `CancellationSignal`과 typed attributes를 포함한다. summarization
model call, projection, persistence는 모두 같은 signal을 adapter cancellation까지 전파한다.

invariants:

- tool call/result group atomicity
- stable group id/count across incremental updates
- start and stop criteria separated
- token/message/turn/group/tool-call conditions compose into trigger and target
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

MCP transport/client는 교체 가능한 adapter다.

- `integrations/agent-framework-mcp`: MCP Java SDK direct adapter
- `integrations/agent-framework-spring-ai-mcp`: Spring AI가 관리하는 MCP connection을 감싸는
  borrowed-client adapter

direct와 borrowed adapter는 서로 다른 named server에서 함께 존재할 수 있다. 각 server/connection
조립은 정확히 하나의 `McpClientPort` adapter를 선택하며 같은 server에 후보가 둘 이상이면 시작
단계에서 실패한다. direct adapter만 transport/connect/auth와 open/close를 소유한다. borrowed
Spring AI/connected-client adapter는 provider/host가 소유한 lifecycle, headers, auth를 사용하고
client를 닫지 않는다. discovery normalization, collision, sampling budget, task transition,
cancellation과 session/tool-loop 의미는 Agent Framework integration이 소유한다.

### 8.2 Discovery

- cursor pagination to completion
- optional local prefix
- normalized-name collision fails
- immutable discovered tool definitions
- reload publishes a new snapshot, not in-place mutation

### 8.3 Prompts and resources

- MCP prompt descriptor는 prefix/collision 규칙을 적용한 executable `Tool`로 변환한다.
- prompt arguments는 JSON schema로 노출하고 invocation result는 core `Message`/`Content`로
  정규화한다.
- MCP resource는 실행 가능한 tool로 가장하지 않고 `McpResourceContent` 또는 동등한 typed
  content payload로 변환한다.
- resource payload는 URI, MIME type, text/blob discriminator, provider metadata를 보존한다.
- prompt/resource pagination과 cancellation은 tool discovery/invocation과 같은 client lifecycle을
  사용한다.

### 8.4 Invocation

- model arguments and MCP metadata separate
- trace/header propagation only through explicit context
- 각 호출은 immutable header snapshot을 만들고 다른 동시 호출과 공유하지 않음
- dynamic credentials는 같은 origin에만 주입하고 cross-origin redirect/alternate origin에서 제거
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

task id를 받은 뒤 같은 logical call을 `tools/call`로 재발행하지 않는다. local cancellation과
`maxTaskWait` 만료는 기본적으로 best-effort `tasks/cancel`을 시도하고 explicit compatibility
option에서만 비활성화한다.

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

## 12. 현재 구현

session/interceptor/MCP production code는 없다. `DeterministicClock`은 이후 snapshot/checkpoint
test fixture로 재사용할 수 있지만 요구 행동 구현은 아니다.

## 13. 요구사항 매핑

`SES`, `INT`, `MCP`의 canonical rows는
[Requirements traceability matrix](requirements-traceability-matrix.md)에 있다.
