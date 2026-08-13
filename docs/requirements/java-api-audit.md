# Java idiom and extensibility audit

이 문서는 설계 확정 전에 244개 요구사항을 Java 공개 API와 다중 프레임워크 확장성 관점에서
다시 검토한 결과다. 검토 기준은 [Java API and extension principles](java-api-principles.md)이며,
원본의 관찰 가능한 동작은 pinned snapshot `d0a4165f`의 production source와 tests로 확인했다.

## 결론

요구사항의 아키텍처 방향은 대체로 Java framework에 적합하다.

- `AgentEngine`과 host runtime의 책임이 분리돼 있다.
- provider, protocol, storage, telemetry가 public port 뒤의 adapter로 분리돼 있다.
- typed interceptor와 immutable workflow definition은 Java의 strategy, decorator, builder
  패턴과 잘 맞는다.
- Spring Boot가 첫 host integration이지만 core dependency는 아니며, Quarkus와 Jakarta EE가
  동일 port를 사용할 수 있다.

다만 일부 요구사항은 Python의 `None`·`deepcopy`·mutable dictionary·global registry 또는
.NET의 다형적 registration overload를 Java 계약으로 직역할 위험이 있었다. 다음 항목을
요구사항 수준에서 수정했다.

## 수정한 요구사항

| ID | 문제 | 수정 |
| --- | --- | --- |
| AGT-005 | .NET `CancellationToken` 직역 가능성 | 명시 취소 의미는 유지하고 `Future`, `Flow.Subscription`, interruption, HTTP abort bridge를 요구 |
| AGT-011 | provider-neutral “키”가 `Map<String,Object>`로 구현될 위험 | 공통 옵션은 typed immutable contract, provider 옵션은 adapter-owned type으로 분리 |
| MSG-003 | Python `None`을 Java `null` 편의로 직역 | no-input/빈 목록을 사용하고 `null`은 public boundary에서 거부 |
| MSG-005 | 순수 sealed content hierarchy가 provider 확장을 차단 | 알려진 core kind와 typed provider extension envelope를 함께 요구 |
| MSG-007 | durable metadata와 provider SDK raw object가 같은 수명주기 | JSON-safe extension value와 transient diagnostic handle을 분리 |
| TOOL-003 | parameter name과 generic type을 추측한 reflection schema | 신뢰 가능한 metadata만 추론하고 불충분하면 명시 schema를 요구 |
| TOOL-013 | `null` tool result를 성공으로 변환하고 임의 객체 변환 경계가 불명확 | void adapter는 원본과 같은 빈 문자열 콘텐츠를 만들고 typed handler `null`은 실패하며 기본 JSON-safe mapper를 조립 |
| SES-005 | Python식 process-global type registry 가능성 | instance-scoped immutable registry, stable type id, no `Class.forName` |
| SES-007 | `deepcopy` 구현을 계약으로 고정하고 부재에 `null` 허용 | immutable/structurally independent snapshot과 explicit absence로 변경 |
| INT-004 | shared mutable context와 `Map<String,Object>` 확장 | immutable request, controlled result replacement, typed context key |
| HAR-017 | polling registry가 static/global singleton으로 구현될 위험 | host-injected instance-scoped polling store로 변경 |
| WF-008 | callable registry scope가 불명확 | workflow/restore-scoped immutable registry, no global discovery |
| HOST-004 | instance/factory/async/builder 네 가지 Python/.NET shape를 public API에 고정 | 하나의 async `TargetResolver<T>` port와 convenience adapters로 통일 |
| HOST-010 | Spring binder만 요구해 다른 Java container가 2급 adapter가 됨 | Spring Boot, Quarkus, Jakarta EE binder family와 plain Java assembly로 일반화 |
| OPS-001 | OTel semantic convention 결정이 core SDK dependency로 오해될 수 있음 | 표준 vocabulary와 telemetry adapter를 요구하고 core signature의 OTel/Micrometer 타입을 금지 |
| OPS-006 | sticky instrumentation state가 JVM-global static이 될 위험 | application-scoped host-owned control로 한정 |

요구사항 ID를 추가하거나 폐기하지 않았으므로 총수는 244개로 유지된다.

## 설계에서 결정할 항목

다음은 요구사항 결함이 아니라 Java 설계 선택이다. 상세 설계와 ADR에서 확정한다.

### 공개 타입 형태

- 진화 가능성이 높은 request/options에는 final class + builder를 사용한다.
- record는 안정된 snapshot·event·identifier에 한정한다.
- 외부 구현 대상 SPI는 open interface, 닫힌 engine state/event만 sealed hierarchy를 사용한다.
- `Role`은 enum이 아니라 알려진 상수를 제공하는 immutable value type으로 둔다.

### 비동기와 스트리밍

- 단일 비동기 결과는 `CompletionStage<T>`, backpressure stream은 `Flow.Publisher<T>`를
framework-neutral public contract로 사용한다.
- `CompletionStage` 자체를 취소 토큰으로 사용하지 않는다. 명시적 cancellation signal과
run handle이 standard cancellation path를 bridge한다.
- engine은 `ForkJoinPool.commonPool()`이나 자체 executor를 기본값으로 만들지 않는다.

### 타입 토큰과 schema

- structured output의 `T`는 framework-neutral `TypeRef<T>` 또는 동등한 Java type descriptor로
전달한다.
- Jackson `JavaType`, Spring `ResolvableType`, provider SDK schema type은 adapter 안에서만
사용한다.
- annotation processor는 workflow route와 tool metadata의 기본 생성 경로가 될 수 있지만,
plain Java builder와 명시 schema 경로를 제거하지 않는다.

### 자원과 adapter 발견

- connection owner만 close하며 borrowed client adapter는 close하지 않는다.
- async close가 필요한 integration은 completion-returning lifecycle port를 사용한다.
- core는 `ServiceLoader`, component scan, static registry를 자동 실행하지 않는다.
- Spring Boot conditional beans, Quarkus CDI/runtime beans, Jakarta EE producers는 같은 builder와
port를 조립한다.

### 오류

- public exception은 unchecked를 기본으로 한다.
- argument/state 오류는 Java built-in 예외를 유지한다.
- provider, transport, checkpoint 같은 외부 실패만 bounded-context exception과 machine-readable
category를 가진다.
- cancellation은 generic framework failure로 감싸지 않는다.

## 유지한 요구사항과 이유

다음 항목은 동적 언어와 다르게 보이지만 Java에서도 유지하는 편이 맞다.

| ID | 유지 이유 |
| --- | --- |
| AGT-005 | explicit cancellation은 reactive/virtual-thread 환경에서도 thread-local보다 안전하다. 단, custom token 직역은 금지했다. |
| MSG-002 | 알려진 상수와 custom role을 함께 가진 value type은 HTTP method와 같은 Java 패턴이다. |
| INT-003 | registration-order pre / reverse-order post는 chain-of-responsibility의 명확한 계약이다. |
| WF-002 | annotation processing은 reflection-only보다 AOT·native image에 유리하다. 명시 builder는 함께 유지한다. |
| WF-006 | 세 edge kind는 runtime의 닫힌 실행 의미다. richer builder가 이를 lowering할 수 있다. |
| OPS-009 | `IllegalArgumentException`과 `IllegalStateException`을 domain exception으로 감싸지 않는 것은 Java 관습이다. |
| PRV-007 | provider capability를 adapter-owned typed surface로 분리하면 core method 증가를 막는다. |

## 현재 Java 코드와의 대조

현재 production source는 설계 구현 전 bootstrap 상태다.

| 경로 | 현재 상태 | 요구사항 매핑 |
| --- | --- | --- |
| `agent-framework-api/.../ApiContract.java` | 모듈 경계 marker | 기능 요구사항 구현 아님 |
| `agent-framework-engine/.../EngineContract.java` | dependency-boundary marker | 기능 요구사항 구현 아님 |
| `agent-framework-testkit/.../DeterministicClock.java` | 결정적 시간 fixture | 이후 OPS/WF test support |
| `build-tools/harness-policy` | repository/build/publishing policy | OPS-023·024의 일부 build contract |

따라서 기능 요구사항을 현재 코드에 “구현 완료”로 연결할 수 없다. 설계 매핑 매트릭스는 각 ID에
대해 다음을 별도로 기록해야 한다.

1. 목표 module/package/type
2. 현재 구현 상태: `absent`, `bootstrap`, `partial`, `implemented`
3. pinned upstream production/test evidence
4. 예정 unit/contract/golden test

현재 모든 AGT~PRV 기능 ID의 기본 상태는 `absent`이고, build/packaging 요구사항 중
`OPS-023`과 `OPS-024` 일부만 `partial`이다. 전체 수용 기준을 만족해 `implemented`로 판정된
요구사항은 아직 없다.

## 설계 승인 조건

설계 문서가 완료되려면 다음을 만족해야 한다.

- 244개 ID가 정확히 한 canonical design section에 배정된다.
- cross-cutting reference는 canonical owner를 바꾸지 않는다.
- 목표 Java symbol은 이 문서의 null, typing, async, lifecycle 원칙을 위반하지 않는다.
- Spring Boot, Quarkus, Jakarta EE adapter를 제거해도 core design이 성립한다.
- 현재 코드가 없는 항목은 planned mapping으로 표시하며 구현된 것처럼 서술하지 않는다.
