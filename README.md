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
- 하나의 저장소에서 여러 Maven artifact를 관리하는 multi-module monorepo를 사용합니다.
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

- [기초 설계와 로드맵](docs/superpowers/specs/2026-08-10-agent-framework-java-foundation-design.md)
- [엔지니어링 하네스 설계](docs/superpowers/specs/2026-08-10-agent-framework-java-engineering-harness-design.md)
- [Gradle Kotlin DSL 및 Java ARC Foundation 설계](docs/superpowers/specs/2026-08-10-gradle-kotlin-arc-foundation-design.md)

현재 저장소는 설계 기준을 확정한 단계입니다. 파일·클래스·메서드 단위 구현 계획은 기초
계약과 호환성 시나리오를 검토한 후 별도로 작성합니다.
