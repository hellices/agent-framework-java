# Clean architecture and Java extension model

## 1. 목적

Microsoft Agent Framework의 관찰 가능한 실행 의미를 Java에 구현하되 애플리케이션 runtime,
DI container, provider SDK를 core에 포함하지 않는다. 결과물은 plain Java에 embed할 수 있는
`AgentEngine`이며 Spring Boot, Quarkus, Jakarta EE는 동일 port를 조립하는 host adapter다.

## 2. 계층

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

`agent-framework-api`가 다음을 소유한다.

- application-facing `Agent`와 실행 input/output
- immutable message, content, tool, session value
- provider·storage·telemetry가 구현하는 public port
- typed interceptor와 lifecycle contract

port는 공개 API이지만 application 편의 API와 구분되는 `com.microsoft.agentframework.spi.*`
package에 둔다. 초기에는 별도 SPI artifact를 만들지 않는다. API와 SPI 릴리스 주기가 실제로
분리된 증거가 생기기 전 module을 나누면 consumer dependency만 늘어난다.

일반 개발자는 SPI가 아니라 `AgentFactory`, `AgentBuilder`, `Agent`, `AgentRun`, `ToolSet`,
`Workflow`, `Harness` facade를 사용한다. 상세 사용성 계약은
[Developer experience](06-developer-experience.md)가 정의한다.

### 2.2 Application engine

`agent-framework-engine`은 public API에만 의존한다.

- run/turn state machine
- model→tool→model loop
- session load/change/persist ordering
- interceptor invocation
- stream finalization

public entry는 `AgentEngine`과 builder/factory에 한정한다. 상태 기계 구현은
`com.microsoft.agentframework.engine.internal.*`에 두고 adapter가 참조하지 못하게 한다.

### 2.3 Optional application subsystems

- `workflow/agent-framework-workflow-core`: immutable graph, run state machine, checkpoint port
- `integrations/agent-framework-harness`: `Agent`를 조립하는 optional facade
- `hosting/agent-framework-hosting-core`: target/session/checkpoint coordination only

이 모듈들은 engine internal에 의존하지 않는다. `Agent`, session port, tool/content contract처럼
공개 API를 통해 조합한다.

### 2.4 Adapters

- `providers/`: model provider SDK adapter
- `integrations/`: MCP, storage, memory, Spring AI, telemetry adapter
- `hosting/`: framework-neutral hosting coordination and standalone assembly
- `protocols/`: Responses, A2A, AG-UI, MCP hosting wire adapter
- `starters/`: dependency-only framework starter artifacts

adapter는 outbound port를 구현하거나 inbound protocol을 public use case로 변환한다. adapter가
engine state machine을 복제하거나 tool loop를 다시 소유하지 않는다.

integration은 endpoint-first가 아니다. framework 내부 사용자는 endpoint를 거치지 않고
container-native component로 `AgentEngine`과 `Agent`를 주입받는다. endpoint는 Responses, A2A,
AG-UI 같은 wire protocol을 실제로 노출할 애플리케이션만 별도 opt-in module로 추가한다.

## 3. 의존성 규칙

```text
api                 <- engine
api                 <- provider/integration adapters
api + engine        <- plain-Java assembly and host auto-configuration
api                 <- workflow-core
api + workflow API  <- workflow adapters
api + hosting API   <- protocol adapters and binders
testkit             <- api
samples             <- published products (never reverse)
```

금지:

- API→engine, API→framework/SDK
- provider/protocol→engine internal
- core→starter
- product→sample/testkit
- static global registry로 adapter 발견

## 4. Java public API 규칙

### 4.1 Values

- 불변 final class와 defensive copy를 기본으로 한다.
- 선택 항목이 많은 request/options에는 builder와 `toBuilder()`를 제공한다.
- ID, closed snapshot, stable event처럼 구성 요소가 고정된 값만 record로 둔다.
- collection은 생성 시 복사하고 unmodifiable view를 반환한다.
- public parameter는 non-null이며 부재 반환은 `Optional<T>`로 표현한다.

### 4.2 Extension points

- provider/tool/store/interceptor는 small open interface다.
- 닫힌 engine 상태와 protocol discriminator만 sealed hierarchy를 사용할 수 있다.
- `ContextKey<T>`, `CapabilityKey<T>` 같은 typed key는 public factory로 생성하고 전역 등록을
  요구하지 않는다.
- provider-specific data는 adapter-owned immutable option/value 또는 typed extension envelope로
  보존한다.

### 4.3 Async and streaming

기본 설계 후보:

- 단일 비동기 결과: `CompletionStage<T>`
- backpressure stream: `Flow.Publisher<T>`
- 장기 실행 제어: `RunHandle`
- 취소: 명시 `CancellationSignal`과 standard cancellation bridge

최종 타입은 첫 vertical slice ADR에서 확정한다. custom stream을 선택하더라도
`CompletionStage`/`Flow.Publisher` adapter contract test가 필요하다.

engine은 executor/scheduler를 만들지 않는다. 병렬 tool execution은 host가 주입한
`ExecutionStrategy` 또는 port가 반환한 비동기 결과를 조합한다.

### 4.4 Errors

- argument/state: `IllegalArgumentException`, `IllegalStateException`
- external/runtime: bounded-context unchecked exception + machine-readable category
- cancellation: 별도 control outcome; generic framework exception으로 감싸지 않음
- adapter는 cause를 보존하고 protocol binder가 HTTP/A2A/MCP error로 변환

## 5. 프레임워크 조립

### 5.1 Plain Java

plain Java builder가 reference assembly다.

```text
AgentEngine.builder()
  -> modelClient(port)
  -> sessionStore(port)
  -> executionStrategy(host-owned)
  -> interceptors(explicit ordered list)
  -> build()
```

framework adapter가 제공하는 기능은 이 builder로도 표현 가능해야 한다.

`hosting/agent-framework-standalone`은 이 reference assembly의 batteries-included facade다.
서버나 DI container를 만들지 않고 JDK client, host-owned execution strategy, in-memory store,
provider adapter를 하나의 `AutoCloseable` assembly로 묶는다. standalone 경로가 모든
framework contract test의 기준 구현이다.

### 5.2 Spring Boot

- `*-autoconfigure`: conditional beans, ordered customizers, configuration properties
- `*-starter`: dependency aggregation only
- user bean이 auto-configured bean을 이김
- `ObjectProvider`/ordered beans를 명시 builder input으로 변환
- Spring AI `ChatModel`/`ToolCallback`은 optional adapter
- engine tool loop 사용 시 Spring AI automatic tool execution 비활성

Spring integration은 Spring application 안에서 native bean처럼 사용되는 것이 우선이다.
protocol endpoint auto-configuration은 protocol별 starter가 명시적으로 추가된 경우에만
활성화한다.

### 5.3 Quarkus

- 기본은 CDI bean을 제공하는 runtime artifact 하나
- recorder, generated route metadata, reflection/native-image hint가 실제로 필요할 때만
  deployment artifact 추가
- Mutiny `Uni`/`Multi`는 boundary에서 core async/stream contract로 변환
- build item, recorder, Arc container type은 core API에 노출하지 않음

runtime/deployment split이 필요하면 기존 `agent-framework-quarkus` runtime coordinate는
유지하고 `agent-framework-quarkus-deployment` sibling project만 추가한다. repository directory
nesting을 늘리지 않아도 Quarkus artifact 역할과 consumer coordinate를 모두 유지할 수 있다.

### 5.4 Jakarta EE

- CDI producer/portable extension으로 port와 engine 조립
- request/application scope와 `SecurityContext`는 container 소유
- Jakarta REST async response/stream은 protocol binder에서 변환
- core는 `BeanManager`, `Instance<T>`, transaction API를 참조하지 않음

## 6. Lifecycle과 scope

| 자원 | 소유자 | core 동작 |
| --- | --- | --- |
| model/MCP HTTP client | 생성한 adapter 또는 host | borrowed client는 close하지 않음 |
| executor/scheduler | host/container | 저장하지 않거나 명시 lifecycle port로 참조 |
| session transaction | store adapter/host | state transition 순서만 정의 |
| telemetry provider/exporter | host bootstrap | semantic events만 발행 |
| request security context | host binder | 검증된 user/session context만 전달 |
| engine | application scope | host가 생성·종료 |
| run/session context | run/session scope | 전역 static 상태 없음 |

## 7. 직렬화와 native image

- stable type id + schema version + injected codec registry
- Java native serialization과 class-name loading 금지
- codec registry는 instance-scoped, build 후 immutable
- annotation processor는 workflow route/tool metadata의 기본 생성 경로
- reflection path는 명시 opt-in이며 metadata 불충분 시 fail-closed
- framework native-image metadata는 해당 adapter가 소유

## 8. 테스트 계층

1. unit: immutable values, merge rules, state transitions
2. API contract: provider/store/interceptor/target resolver implementations
3. golden compatibility: pinned upstream scenarios
4. wire: SSE/A2A/AG-UI/MCP payload and cancellation
5. framework assembly: plain Java, Spring Boot, Quarkus, Jakarta EE
6. architecture policy: dependency direction, public package, no framework leakage

exact natural language와 exact tool-call ordering은 long-term contract로 삼지 않는다. event ordering,
state transition, budgets, result shape처럼 관찰 가능한 의미만 고정한다.

## 9. 주요 ADR 후보

| ADR | 결정 |
| --- | --- |
| Async API | `CompletionStage`/`Flow.Publisher` 확정 여부와 cancellation bridge |
| API evolution | record/final class 기준과 binary compatibility baseline |
| Extension values | typed key와 provider extension envelope |
| Schema | framework-neutral type descriptor와 schema generator SPI |
| State codec | registry freeze, version migration, copy strategy |
| Execution strategy | host-owned concurrency and structured cancellation |
| Framework adapters | Quarkus deployment split을 추가하는 증거 |

ADR이 요구사항을 바꿀 수는 없다. 여러 구현이 요구사항을 만족할 때 public API와 운영 trade-off를
선택하는 데만 사용한다.
