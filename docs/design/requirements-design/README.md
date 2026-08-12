# Requirements-driven design

이 디렉터리는 `docs/requirements/`의 244개 요구사항을 Java 구현 구조로 변환한다. 요구사항은
무엇을 만들어야 하는지 정의하고, 이 설계는 어떤 경계와 타입으로 만들지 정의한다.

## 문서 구성

| 문서 | canonical requirement owner |
| --- | --- |
| [00 Clean architecture](00-clean-architecture.md) | 공통 계층, 의존성, Java API, framework assembly |
| [01 Core execution](01-core-execution.md) | `AGT`, `MSG`, `OUT`, `TOOL` |
| [02 State, extension, and MCP](02-state-extension-mcp.md) | `SES`, `INT`, `MCP` |
| [03 Workflow and harness](03-workflow-harness.md) | `WF`, `HAR` |
| [04 Hosting, operations, and providers](04-hosting-operations-providers.md) | `HOST`, `OPS`, `PRV` |
| [05 Framework adapters](05-framework-adapters.md) | framework별 조립 패턴; canonical requirement는 소유하지 않음 |
| [06 Developer experience](06-developer-experience.md) | progressive disclosure와 `agent.run()` 중심 facade |
| [Requirements traceability matrix](requirements-traceability-matrix.md) | 244개 ID의 설계·목표 코드·현재 코드·검증 매핑 |

한 요구사항은 정확히 한 문서가 canonical owner다. 여러 bounded context에 영향을 주는 경우 다른
문서는 cross-reference만 만들며 owner를 복제하지 않는다.

## 현재 코드 상태

현재 제품 코드는 모듈 부트스트랩 단계다.

- `agent-framework-api`: `ApiContract` marker만 존재
- `agent-framework-engine`: `EngineContract` marker와 dependency test만 존재
- `agent-framework-testkit`: `DeterministicClock` fixture만 존재
- `agent-framework-bom`: artifact alignment만 존재

따라서 매핑의 `현재` 열은 대부분 `absent`다. `planned` symbol을 현재 구현처럼 서술하지 않는다.
build, publishing, repository policy처럼 실제 코드가 있는 범위만 `implemented` 또는 `partial`로
표시한다.

## 매핑 규칙

각 요구사항은 다음 다섯 축으로 추적한다.

1. 요구사항 ID와 원문
2. canonical design section
3. 목표 Gradle module, package, Java symbol
4. 현재 구현 상태와 실제 경로
5. unit, contract, golden, wire verification

상태 값은 다음으로 제한한다.

| 상태 | 뜻 |
| --- | --- |
| `absent` | 목표 module 또는 production type이 없음 |
| `bootstrap` | module/marker만 있고 요구 행동은 없음 |
| `partial` | 요구 행동 일부가 production code와 test로 검증됨 |
| `implemented` | 수용 기준 전체가 code와 test에 연결됨 |

## 근거 우선순위

설계 판단은 저장소 계약의 근거 우선순위를 따른다.

1. pinned upstream production source
2. pinned upstream conformance/integration tests
3. requirements와 approved design
4. 공식 Java framework 문서
5. sample과 README

현재 upstream pin은 `d0a4165f170193ba1d026a259af40d35bb7eaefe`다. 현재 upstream `main`의
새 동작을 이 설계에 섞지 않는다.

## Java 및 framework 원칙

모든 설계는 [Java API and extension principles](../../requirements/java-api-principles.md)와
[Java idiom audit](../../requirements/java-api-audit.md)를 따른다.

- core API는 Spring, Quarkus, Jakarta EE, provider SDK, JSON mapper, Reactor, Mutiny 타입을
  노출하지 않는다.
- 값은 불변이고, 확장 지점은 typed open SPI다.
- 비동기·streaming·cancellation·close의 소유자를 계약에 표시한다.
- adapter는 public port만 구현하고 engine internal을 참조하지 않는다.
- plain Java에서 먼저 조립 가능해야 framework adapter도 유효하다.

## 설계 변경 절차

1. 요구사항 변경이면 `docs/requirements/`와 traceability matrix를 먼저 갱신한다.
2. public symbol 또는 module ownership 변경이면 canonical design과 module composition을 함께
   갱신한다.
3. mapping script/policy가 모든 ID를 정확히 한 번 찾는지 확인한다.
4. instruction·contract 변경이므로 `./gradlew policyCheck`를 실행한다.
5. push 후 Copilot review loop를 완료한다.
