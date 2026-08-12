# Workflow and harness design

## 1. 범위와 단계

- `WF-001..035`: 별도 workflow subsystem
- `HAR-001..021`: optional harness composition

workflow와 harness는 core MVP 제품 의존성이 아니다. core API가 안정된 뒤 별도 artifact로
개발하며 `agent-framework-engine` internal을 참조하지 않는다.

## 2. 모듈

```text
workflow/agent-framework-workflow-api
  graph definition, events, run/checkpoint contracts

workflow/agent-framework-workflow-core
  graph validation, runner, superstep state machine

workflow/agent-framework-workflow-processor
  annotation processor and generated route registry

workflow/agent-framework-workflow-orchestration
  sequential, concurrent, handoff, group chat, magentic

workflow/agent-framework-workflow-declarative
  optional safe declarative loading/generation

integrations/agent-framework-harness
  harness builder, loop, todo/mode/approval providers

integrations/agent-framework-file-access
integrations/agent-framework-skills
integrations/agent-framework-background-agents
integrations/agent-framework-shell-tools
integrations/agent-framework-local-codeact
integrations/agent-framework-codeact-sandbox
```

API/core 분리는 workflow artifact가 provider/protocol adapter와 독립적으로 진화해야 하는 증거가
이미 있으므로 core agent module보다 일찍 적용한다.

## 3. Workflow definition

`WorkflowBuilder`는 immutable `Workflow`를 만든다.

```text
Workflow
  WorkflowId + signature
  executors: stable id -> ExecutorDefinition
  edges: DIRECT | FAN_OUT | FAN_IN
  designated outputs
  route bindings
```

build validation:

- executor binding 누락
- 시작점에서 도달 불가
- output empty/duplicate/overlap
- invalid edge/source/sink/type
- unresolved symbolic callable

annotation processor가 route와 type metadata를 생성하는 기본 경로다. plain Java explicit builder도
동일 registry contract를 사용한다. runtime reflection은 opt-in fallback이며 native image
metadata를 자동 요구하지 않는다.

## 4. Workflow run

`WorkflowRun`은 mutable execution handle이고 definition과 분리된다.

- run id/status
- event publisher
- explicit cancel
- pending requests
- latest checkpoint
- resume/respond API

engine/runner는 executor나 scheduler를 만들지 않는다. `WorkflowExecutionStrategy`와
`WorkflowClock`을 host가 주입한다.

### 4.1 Superstep

```text
START_SUPERSTEP
  -> DRAIN_EXTERNAL_AND_QUEUED_MESSAGES
  -> ROUTE
  -> EXECUTE_READY_NODES
  -> STAGE_STATE_AND_EVENTS
  -> COMMIT_OR_DISCARD
  -> SAVE_CHECKPOINT
  -> COMPLETE_SUPERSTEP
```

executor 실패 전 pending state는 discard한다. committed state 이후 rollback을 가장하지 않는다.
같은 state key의 다중 writer는 deterministic conflict로 실패한다.

### 4.2 Routing

- `DIRECT`: condition false면 downstream delivery 없음
- `FAN_OUT`: selector가 sink set 결정
- `FAN_IN`: declared source set의 barrier 충족 시 한 번 실행

switch/multi-selection builder는 runtime graph의 세 edge kind로 lowering한다.

## 5. Events and status

상태는 polling과 event stream을 모두 제공한다.

- polling: status snapshot, pending requests, latest checkpoint
- streaming: ordered `WorkflowEvent`

event taxonomy는 framework-owned closed hierarchy이지만 payload extension envelope를 가진다.
user message가 reserved lifecycle event를 spoof할 수 없도록 source/discriminator를 runner가
생성한다.

실패 ordering:

```text
ExecutorFailed
WorkflowFailed
terminal completion
```

stream close와 run cancel은 별도다. subscription 해제가 durable workflow를 암묵 취소하지 않는다.

## 6. Checkpoint and resume

checkpoint envelope:

- workflow signature/schema version
- superstep and event cursor
- executor snapshots
- committed/shared/edge state
- pending messages and external requests
- deterministic order key

codec registry는 instance-scoped, allowlisted, immutable하다. Java serialization/class-name loading을
사용하지 않는다.

restore:

1. workflow signature 검증
2. stale queued events 제거
3. state/request registry 복원
4. pending request 한 번 재발행
5. 새 input 또는 response 적용

callable은 symbolic name만 저장하고 `WorkflowRestoreOptions`의 immutable registry에 재바인딩한다.
global registry와 silent no-op fallback은 없다.

외부 response는 request id와 port id를 함께 검증한다. approval은 checkpoint의
`originalRequest`를 진실 원천으로 사용한다.

## 7. Composition

### 7.1 Subworkflow

subworkflow는 inheritance보다 composition으로 실행한다.

- parent가 child definition/run lifecycle을 소유
- child output을 parent message로 변환
- checkpoint signature에 child signature 포함

### 7.2 Workflow as Agent

`WorkflowAgent`는 public `Agent` decorator다.

- workflow run/checkpoint를 agent session에 저장
- agent run/stream contract로 events/output projection
- continuation state를 session codec로 보존

### 7.3 Orchestration

pattern builder가 공통 participant/output helper를 사용한다.

- sequential: full-conversation / chain-only
- concurrent: participant fan-out + aggregator
- handoff: explicit graph 또는 validated mesh
- group chat: exactly one orchestrator/manager
- magentic: manager, plan review, progress ledger

participant source는 instance/factory variants를 public union으로 늘리지 않고 resolver adapter로
통일한다.

### 7.4 Declarative

optional module이다.

- safe type/handler registry
- no arbitrary class loading
- generated/explicit binding
- declarative document가 filesystem/network/code execution capability를 암묵 획득하지 않음

## 8. Harness

`HarnessAgent`는 `Agent`를 감싸는 조립 facade다.

```text
HarnessBuilder
  base Agent
  loop predicate
  todo provider
  mode provider
  file memory provider
  approval provider
  optional skills/background/shell/code adapters
```

잘못된 provider 조합은 build 시 실패한다. 기본값은 보수적 opt-in이다.

### 8.1 Loop

harness loop는 core model/tool loop와 별도다.

- each iteration gets fresh run context
- pending approval에서 즉시 정지
- predicate가 continuation을 결정
- iteration/approval budget 공유

### 8.2 Providers

- todo: session-backed state, stable operation result
- mode: default `plan`, external change notification
- file memory: session scope, reserved names rejected
- approval: queue and standing rules in session

### 8.3 Optional unsafe capabilities

- skills: progressive disclosure; script execution approval by default
- background agents: injected instance-scoped polling store, `LOST` state
- shell: separate manually assembled tools module
- LocalCodeAct: sandbox로 부르지 않음
- sandbox backend: separate module + explicit trust/capability

denylist는 security boundary가 아니라 guardrail이다. host OS/container policy가 실제 boundary다.

## 9. Framework integration

- Spring/Quarkus/Jakarta는 workflow executor/harness provider를 bean으로 생성한 뒤 explicit builder에
  전달
- container priority는 deterministic registration list로 변환
- request scope를 workflow durable state와 혼합하지 않음
- executor/scheduler/transaction/security는 container 소유
- annotation processor output은 모든 framework에서 같은 runtime registry를 생성

## 10. Tests

### Graph

- binding/reachability/output validation
- generated route vs explicit builder equivalence
- edge lowering, fan-in barrier, condition drop

### Runtime

- superstep event order
- state conflict and discard on failure
- cancellation vs stream unsubscribe
- executor failure ordering

### Checkpoint/HITL

- signature mismatch
- stale event drain
- latest checkpoint deterministic sort
- pending request de-duplication
- dual-key response validation
- approval original request restoration
- malicious type/path rejection

### Harness/orchestration

- conservative defaults
- approval pause and budget
- provider session isolation
- pattern-specific builder validation
- background `LOST`, skill/file/shell boundaries

## 11. 현재 구현

workflow와 harness module은 아직 존재하지 않는다. 모든 `WF`/`HAR` ID 상태는 `absent`다.

## 12. 요구사항 매핑

`WF`, `HAR`의 canonical rows는
[Requirements traceability matrix](requirements-traceability-matrix.md)에 있다.
