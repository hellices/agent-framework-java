# Agent Framework for Java

Microsoft Agent Framework의 실행 의미론을 Java에서 사용할 수 있도록 만드는 프로젝트입니다.

이 프로젝트는 독립적인 애플리케이션 서버나 DI 컨테이너를 만들지 않습니다. 핵심은 호스트
환경에 삽입할 수 있는 `AgentEngine`이며, Spring Boot 같은 애플리케이션 런타임이 객체
생명주기, 실행 자원, 보안, 트랜잭션과 관찰성 구성을 담당합니다.

## 핵심 방향

- Microsoft Agent Framework와 API 이름보다 관찰 가능한 실행 동작의 호환성을 우선합니다.
- 에이전트 실행은 Spring에 의존하지 않는 임베디드 상태 머신으로 구현합니다.
- Spring Boot는 호스트 런타임이며 `AgentEngine`을 Bean으로 조립합니다.
- Spring AI는 필수 기반이 아닌 선택적 어댑터로 취급합니다.
- 하나의 저장소에서 여러 Gradle artifact를 관리하는 multi-project monorepo를 사용합니다.
- 초기 릴리스는 단일 버전과 BOM으로 전체 artifact의 호환성을 보장합니다.

## 책임 경계

| 영역 | 소유자 |
| --- | --- |
| 모델·도구 호출의 상태 전이 | AgentEngine |
| 세션 상태 변경 규칙 | AgentEngine |
| 스트리밍 이벤트 순서와 중단 정책 | AgentEngine |
| DI, 설정, 객체 생명주기 | Spring Boot 또는 다른 호스트 |
| 실행기, 스케줄러, HTTP 서버 | 호스트 |
| 보안, 트랜잭션, 복원력 정책 | 호스트와 통합 모듈 |
| 공급자 API 변환 | Provider adapter |
| Spring AI 연동 | 선택적 Spring AI adapter |

## 문서

- [기초 설계와 로드맵](docs/design/foundation-design.md)
- [엔지니어링 하네스 설계](docs/design/engineering-harness-design.md)
- [Gradle Kotlin DSL 및 Java ARC Foundation 설계](docs/design/gradle-kotlin-arc-foundation-design.md)
- [요구사항](docs/requirements/README.md)
- [Upstream snapshot 분석 인덱스](docs/upstream/snapshots/d0a4165f/README.md)

## 기여와 하네스

- [저장소 작업 지침](AGENTS.md)
- [기여 가이드](CONTRIBUTING.md)
- [보안 정책](SECURITY.md)
- [GitHub Actions runner 계약](docs/operations/github-actions-runner-contract.md)

모든 로컬·CI 검증은 저장소에 포함된 Gradle Wrapper를 사용합니다.

```bash
./gradlew check
```

## 현재 상태

설계 기준과 요구사항을 확정하고, Gradle 엔지니어링 하네스를 저장소에 반영한 단계입니다.
제품 모듈은 아직 없습니다.

CI의 신뢰 경로는 `arc-java-build` ARC scale set에서 실행됩니다. 러너 계약과 실행 조건은
[GitHub Actions runner 계약](docs/operations/github-actions-runner-contract.md)을 따릅니다.

단계별 실행 계획은 저장소에 보관하지 않습니다. 무엇을 만들어야 하는지는
[요구사항](docs/requirements/README.md)이, 왜 그렇게 만드는지는
[설계 문서](docs/design/foundation-design.md)가 정의합니다.
