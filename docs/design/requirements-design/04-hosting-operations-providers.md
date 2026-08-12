# Hosting, operations, and provider design

## 1. 범위

- `HOST-001..029`: hosting core and protocol adapters
- `OPS-001..026`: observability, errors, security, evaluation, packaging
- `PRV-001..010`: provider/infrastructure adapter policy

## 2. 모듈

```text
agent-framework-hosting-core
  TargetResolver, HostingRequest, HostingResult, continuation/session coordination

protocols/
  agent-framework-responses
  agent-framework-a2a
  agent-framework-ag-ui
  agent-framework-mcp-hosting

providers/
  agent-framework-openai
  agent-framework-azure-openai
  ... one provider per artifact

integrations/
  agent-framework-opentelemetry
  agent-framework-evaluation
  storage/memory/governance adapters

starters/
  Spring Boot, Quarkus, Jakarta EE binders
```

module은 구현 단계에 첫 기능과 함께 추가하며 빈 module을 미리 만들지 않는다.

## 3. Hosting core

hosting core는 target과 state를 protocol binder에 연결한다.

```text
validated HostRequest
  -> TargetResolver.resolve(context)
  -> load/create independent session snapshot
  -> run / restore-then-run
  -> persist session/checkpoint cursor
  -> HostResult
```

포함하지 않는 것:

- HTTP route/status/header
- auth provider와 security realm
- executor/scheduler
- transaction manager
- retry/circuit breaker
- server lifecycle

### 3.1 TargetResolver

하나의 비동기 resolver port를 사용한다. instance, `Supplier`, async factory, builder, CDI/Spring
provider는 convenience adapter로 변환한다. cache policy와 target source는 분리한다.

### 3.2 Session continuity

- load는 independent snapshot
- create-on-miss는 key당 한 번
- service session candidate는 auth 전 untrusted
- host가 principal/tenant context로 trusted storage key를 도출
- single-writer coordination과 authorization은 host 책임

workflow는 session cursor를 먼저 restore하고 durable store를 fallback으로 사용한다.

## 4. Protocol adapters

protocol module은 wire DTO와 pure converter를 소유하고 host framework route는 소유하지 않는다.

### 4.1 OpenAI Responses

- request parse와 candidate session key extraction 분리
- default mapping은 agent-owned instructions/tools/sampling override 거부
- branch pointer와 mutable conversation head 분리
- minimal SSE profile + rich content profile
- final payload에 tool/reasoning/media 보존

### 4.2 A2A

- remote agent implements public `Agent` contract
- local expose helper와 framework binder 분리
- structured service session id
- continuation token + new user input 동시 사용 거부
- message/task/artifact lifecycle 분리

### 4.3 AG-UI

- request aliases/resume normalization
- predictive delta vs deterministic snapshot type 분리
- UI tool payload vs LLM text 분리
- thread id vs snapshot scope 분리
- stream completion 뒤 persist

### 4.4 Other hosting

Foundry, DevUI, channels는 optional protocol/adapter artifact다. local/dev mode는 authenticated hosted
header만으로 승격되지 않는다.

## 5. Observability

OTel GenAI semantic convention을 canonical vocabulary로 사용한다. core API는 OTel SDK,
Micrometer, Spring Observation 타입을 노출하지 않는다.

```text
engine semantic event
  -> TelemetrySink port
  -> OpenTelemetry adapter
  -> Micrometer bridge (Spring) / Quarkus OTel / Jakarta provider
```

원칙:

- bootstrap과 per-agent/workflow wrapper 분리
- prompt/tool argument/result capture default off
- logging과 tracing 분리
- approved HTTPS origin에만 feature telemetry
- application-scoped sticky disable
- 같은 operation 중복 계측 금지
- category-level enable/disable

## 6. Errors, cancellation, resilience, security

host가 type/category로 error를 매핑한다.

- validation/programming: built-in Java exceptions
- provider/transport/checkpoint: bounded-context exception
- timeout: result/status envelope에 남김
- cancellation: failure로 번역하지 않음

process tree/remote task cleanup은 resource owner가 수행한다. persistent executor는 session-owned
resource이며 host가 lifecycle을 제공한다.

retry, timeout policy, circuit breaker, rate limiting은 host adapter가 소유한다. engine은
idempotency/cancellation signal만 제공한다.

shell/tool policy와 allow/deny list는 security boundary가 아니다. unsafe bypass에는 explicit
acknowledgement/capability가 필요하다. declarative workflow의 state path는 allowlisted root에서
정규화하고 reflective member access와 path traversal을 기본 거부한다.

## 7. Evaluation and compatibility

optional evaluation SPI:

- batch-oriented provider-neutral request
- explicit converter
- execution failure vs quality failure
- workflow aggregate + per-agent subresults
- deterministic generated evaluator/golden inputs

test levels:

- provider contract tests
- golden behavior scenarios
- wire protocol tests
- installability/native/framework smoke tests

## 8. Provider adapters

각 provider는 별도 artifact다.

- core has no provider SDK dependency
- no default all-providers bundle
- provider options/capabilities are adapter-owned typed values
- continuation/hosted state parsed only by adapter
- hosting/protocol independent from provider
- storage/memory/governance separate integrations

provider adapter public facade:

- `ChatClient` implementation
- typed options/capabilities
- conversion utilities where justified
- maturity and compatibility metadata

새 provider는 core modification, static registry, automatic `ServiceLoader` discovery를 요구하지
않는다. host builder/DI가 명시적으로 adapter instance를 선택한다.

## 9. Maturity and packaging

- one repository version line
- Gradle BOM lists every published artifact
- maturity registry separate from version
- dependency bounds + locks + action/checksum pinning
- maturity-dependent CI/installability gates
- Java/.NET/Python compatibility judged by public surface and behavior

## 10. Framework-neutral lifecycle

| 관심사 | port/owner |
| --- | --- |
| target creation/cache | `TargetResolver` + host |
| session authorization | host binder |
| session persistence | `SessionStore` adapter |
| HTTP/SSE | protocol + framework binder |
| telemetry provider | host bootstrap |
| model SDK client | provider adapter/host |
| retry/rate limit | host |
| release/maturity | repository policy |

## 11. Tests

- hosting core without any framework dependency
- resolver adapters and cache policy
- concurrent create-once and independent snapshot
- untrusted session candidate never reaches store
- protocol converter/wire golden tests
- SSE terminal ordering and cancellation
- telemetry redaction/origin/sticky/no-double-instrumentation
- provider common contract and option rejection
- BOM/maturity/installability policy

## 12. 현재 구현

hosting/protocol/provider/telemetry/evaluation production module은 아직 없다. packaging/BOM과
dependency policy에 해당하는 `OPS-023`·`OPS-024` 일부만 `partial`이고 `implemented` 항목은
없다.

## 13. 요구사항 매핑

`HOST`, `OPS`, `PRV`의 canonical rows는
[Requirements traceability matrix](requirements-traceability-matrix.md)에 있다.
