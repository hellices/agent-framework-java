# Agent Framework for Java

[Microsoft Agent Framework](https://github.com/microsoft/agent-framework)의 관찰 가능한 실행
의미론을 Java에서 사용할 수 있게 만듭니다.

이 프로젝트는 애플리케이션 서버나 DI 컨테이너를 만들지 않습니다. 산출물은 호스트에 삽입할 수
있는 `AgentEngine`이며, Spring Boot 같은 호스트 런타임이 객체 생명주기, 실행 자원, 보안,
트랜잭션, 관찰성 구성을 계속 소유합니다.

> **상태:** 초기 단계입니다. 빌드 하네스, 요구사항, 모듈 뼈대가 준비됐고 에이전트 동작은 아직
> 구현하지 않았습니다. [현재 상태](#현재-상태)를 참고하세요.

## 왜 이렇게 나누는가

에이전트 런타임과 애플리케이션 런타임은 서로 다른 문제를 풉니다. 둘을 합치면 스레드 풀, 설정,
요청 수명주기를 양쪽이 모두 소유한다고 주장하게 되고, 결국 어느 쪽도 상대 없이는 테스트할 수
없게 됩니다.

그래서 경계를 명시합니다.

| 관심사 | 소유자 |
| --- | --- |
| 모델 호출과 도구 호출의 상태 전이 | `AgentEngine` |
| 세션 상태 변경 규칙 | `AgentEngine` |
| 스트리밍 이벤트 순서와 중단 정책 | `AgentEngine` |
| DI, 설정, 객체 생명주기 | 호스트 런타임 |
| 실행기, 스케줄러, HTTP 서버 | 호스트 런타임 |
| 보안, 트랜잭션, 복원력 정책 | 호스트 런타임과 통합 모듈 |
| 공급자 API 변환 | 공급자 어댑터 |

엔진이 프레임워크 중립을 유지하므로 같은 에이전트 의미론을 Spring Boot, Quarkus, CLI, 순수
테스트 하네스 어디서나 실행할 수 있습니다.

## 설계 원칙

- 업스트림 API 이름 일치보다 **관찰 가능한 동작**의 호환을 우선합니다.
- 에이전트 실행은 애플리케이션 프레임워크에 의존하지 않는 임베디드 상태 머신으로 구현합니다.
- Spring AI를 포함한 모든 통합은 기반이 아니라 **선택적 어댑터**로 취급합니다.
- 하나의 저장소에서 여러 아티팩트를 관리하고 단일 버전과 BOM으로 정렬합니다.

## 빠른 시작

JDK 17이 필요합니다. 호환성 테스트는 Temurin 21과 25를 추가로 사용합니다.

```bash
git clone https://github.com/hellices/agent-framework-java.git
cd agent-framework-java
./gradlew check
```

로컬과 CI 모두 저장소에 포함된 Gradle Wrapper로 실행합니다. CI 전용 검증 경로는 없습니다.

| 명령 | 목적 |
| --- | --- |
| `./gradlew policyCheck` | 저장소·아티팩트·워크플로 정책 회귀 |
| `./gradlew quality` | JDK 17에서 포매팅과 정적 분석 |
| `./gradlew testJava17` | Temurin 17 런처로 테스트 |
| `./gradlew check` | 위 전부와 21·25 호환성 실행 |
| `./gradlew publishAllPublicationsToBuildDirectoryRepository` | 모든 아티팩트를 `build/maven-repository`에 게시 |

로컬에 Temurin 21이나 25가 없으면 `testJava21`, `testJava25`가 툴체인 오류로 실패합니다. 좁은
태스크만 실행하고 나머지는 CI에 맡기면 됩니다.

## 저장소 구조

핵심 모듈은 루트에 둡니다. 모듈이 많아질 계열은 그룹 디렉터리로 묶으며, 이는 Spring AI와
Spring Boot가 같은 규모에서 쓰는 형태입니다.

```text
agent-framework-api/        공개 계약과 값 타입
agent-framework-engine/     임베디드 실행 상태 머신
agent-framework-testkit/    결정적 테스트 fixture
agent-framework-bom/        published artifact 버전 정렬
build-logic/                Gradle convention plugin
build-tools/harness-policy/ 실행 가능한 저장소 정책
config/                     Checkstyle, PMD, SpotBugs 설정
docs/                       요구사항, 설계, 업스트림 분석
.harness/                   에이전트 아티팩트 JSON 스키마
```

예정된 그룹 디렉터리는 `providers/`, `integrations/`, `starters/`, `protocols/`, `workflow/`,
`compatibility-tests/`, `samples/`입니다. 각 디렉터리는 첫 모듈이 들어올 때 만듭니다.

모듈 규칙은 [모듈 구성 계약](../design/module-composition.md)이 정의하고 `./gradlew policyCheck`가
강제합니다.

## 문서

필요에 따라 시작점을 고르세요.

| 질문 | 문서 |
| --- | --- |
| Java는 무엇을 만들어야 하는가 | [요구사항](../requirements/README.md) |
| 왜 그렇게 만드는가 | [기초 설계](../design/foundation-design.md) |
| 원본 프레임워크는 어떻게 동작하는가 | [업스트림 스냅샷 분석](../upstream/snapshots/d0a4165f/README.md) |
| 모듈은 어떤 관계인가 | [모듈 구성 계약](../design/module-composition.md) |
| 저장소는 어떻게 검증되는가 | [엔지니어링 하네스 설계](../design/engineering-harness-design.md) |
| 빌드는 어떻게 동작하는가 | [Gradle과 Java ARC 기반 설계](../design/gradle-kotlin-arc-foundation-design.md) |

요구사항이 계약입니다. 12개 문서에 244개 요구사항이 있고, 각각 고정 ID, .NET과 Python 비교,
Java 판단 근거, 수용 기준을 가집니다.

## 기여

- [저장소 작업 지침](../../AGENTS.md)
- [기여 가이드](../../CONTRIBUTING.md)
- [보안 정책](../../SECURITY.md)
- [GitHub Actions runner 계약](../operations/github-actions-runner-contract.md)

요구사항 ID를 하나 고르고, 실패하는 테스트를 먼저 쓴 뒤, 그것을 통과시키는 최소 변경을
구현하세요. 커밋 메시지에 ID를 남기면 계약과 코드가 계속 연결됩니다.

## 현재 상태

검증된 기반은 있고 에이전트 동작은 아직 없습니다.

**준비된 것**

- convention plugin과 의존성 잠금을 갖춘 Gradle Kotlin DSL 빌드
- 빌드 계약, 거버넌스, 워크플로, 모듈 구조를 덮는 실행 가능한 저장소 정책
- `.harness/` 아래 에이전트 아티팩트 JSON 스키마
- fork 안전 검증 경로를 갖춘 `arc-java-build` ARC scale set CI
- 고정된 업스트림 스냅샷에서 도출한 244개 요구사항
- 컴파일·테스트·배포 가능한 표면을 가진 제품 모듈 4개

**시작하지 않은 것**

- 메시지와 콘텐츠 타입, 모델 클라이언트 포트, 도구 루프, 세션, 인터셉터
- 워크플로, 호스팅, 프로토콜 어댑터, 공급자 통합

첫 구현 대상은 `agent-framework-api`의 타입 모델입니다. 다른 모든 모듈이 여기에 의존하고,
호환성 매트릭스도 초기 릴리스 필수로 판정했기 때문입니다.

## 번역

이 문서는 [영문 README](../../README.md)의 번역본입니다. 영문이 정본이며 번역이 뒤따릅니다.

## 라이선스

[MIT](../../LICENSE)
