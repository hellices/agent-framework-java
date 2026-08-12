# Agent Framework for Java 기초 설계

- 상태: 승인
- 승인일: 2026-08-10
- 범위: 프로젝트의 아키텍처 방향, 모듈 경계, 초기 개발 순서

## 1. 목적

Microsoft Agent Framework(MAF)의 핵심 실행 의미론을 Java에서 제공한다. Java 구현은
특정 애플리케이션 프레임워크에 종속되지 않는 임베디드 에이전트 실행 엔진으로 제공하며,
Spring Boot를 포함한 여러 호스트 환경에서 조립할 수 있어야 한다.

이 문서는 상세 구현 작업표가 아니다. 구현을 시작하기 전에 유지해야 할 책임 경계,
의존성 방향, MVP 범위와 검증 순서를 확정한다.

## 2. 조사에서 확인한 전제

MAF는 모델 호출 래퍼보다 넓은 개념이다. Agent, session, tool execution loop,
middleware, workflow와 hosting protocol을 포함하는 실행 모델을 제공한다.

Spring AI 2.x는 `ChatModel`, `ChatClient`, `ToolCallback`, Advisor, `ChatMemory`,
MCP와 모델 공급자 통합을 제공한다. 그러나 MAF의 session 및 workflow runtime과 동일한
상위 실행 의미론을 제공하지 않는다.

따라서 두 프레임워크를 직접 포개면 다음 충돌이 발생한다.

- Java 엔진과 Spring AI `ToolCallingAdvisor`가 도구 실행 루프를 중복 소유할 수 있다.
- MAF의 영속 가능한 session과 Spring AI의 문맥 유지용 `ChatMemory`는 수명과 책임이 다르다.
- Micrometer와 OpenTelemetry 계측을 독립적으로 적용하면 중복 span이 생성될 수 있다.
- Spring AI API 변화가 코어 실행 계약에 전파될 수 있다.

## 3. 검토한 접근법

### 3.1 MAF API 직역

.NET 또는 Python 공개 API를 Java 문법으로 옮기는 방식이다. 표면적인 친숙함은 얻지만,
두 원본 구현의 차이와 변경을 계속 추적해야 하며 Java 생태계의 실행·비동기 관습을
왜곡할 가능성이 높다.

### 3.2 Spring AI 기반 확장

Spring AI를 실행 기반으로 삼고 그 위에 MAF 형태의 API를 제공하는 방식이다. Spring
애플리케이션에서는 빠르게 시작할 수 있지만 agent loop, session과 workflow의 소유권이
불분명해지고 Spring 밖에서 재사용하기 어렵다.

### 3.3 임베디드 AgentEngine과 선택적 어댑터

프레임워크 중립적인 API와 실행 상태 머신을 만들고 공급자 및 Spring 통합을 별도
artifact로 제공한다. 초기 모듈 수는 늘지만 책임과 의존성 방향이 명확하며 MAF 호환성과
Java 생태계 통합을 함께 유지할 수 있다.

**결정:** 3.3을 채택한다.

## 4. 핵심 아키텍처 결정

### 4.1 Runtime이 아닌 AgentEngine

프로젝트의 코어를 애플리케이션 런타임으로 만들지 않는다. `AgentEngine`은 모델 호출,
도구 실행과 session 변경을 일관된 순서로 진행하는 임베디드 상태 머신이다.

```text
Application
    |
Host runtime (Spring Boot, Quarkus, Micronaut, Jakarta EE, CLI)
    |
AgentEngine
    |
Model / Tool / Session / Telemetry ports
    |
Provider and infrastructure adapters
```

`AgentEngine`이 소유하는 책임은 다음과 같다.

- agent run과 turn의 상태 전이
- 모델 응답과 도구 호출 결과의 연결
- 도구 반복 횟수, 중단, 승인과 실패 전파 정책
- session 상태 변경 규칙
- 동기 및 스트리밍 실행에서 관찰 가능한 이벤트 순서
- 향후 workflow graph의 결정적 상태 전이

호스트가 소유하는 책임은 다음과 같다.

- DI와 객체 생명주기
- 스레드 풀, 스케줄러와 연결 풀
- HTTP 서버와 endpoint
- 설정, profile과 secret 공급
- 인증, 권한과 security context
- 트랜잭션
- 재시도, timeout, rate limit과 circuit breaker의 운영 설정
- telemetry exporter와 애플리케이션 시작·종료

코어는 자체 `ExecutorService`, scheduler, 서버, 전역 registry, shutdown hook 또는 DI
container를 생성하지 않는다. 필요한 실행 자원과 포트 구현은 생성자를 통해 전달받는다.

### 4.2 확장 지점

MAF 호환 동작에 필요한 실행 전후 개입은 유지하되 범용 애플리케이션 middleware를
재구현하지 않는다. 코어는 책임이 명확한 typed interceptor SPI만 제공한다.

- agent run interceptor
- model call interceptor
- tool call interceptor
- session operation interceptor

인터셉터는 요청·응답 검사와 변환, 실행 중단, 오류 관찰과 명시적 실행 context 전달만
담당한다. DI, transaction, security, HTTP filtering과 component scanning은 담당하지
않는다.

Spring 통합 모듈은 interceptor Bean을 순서대로 수집해 엔진에 전달한다. Spring AI
Advisor를 이 SPI로 일반 변환하지 않는다. Advisor는 `ChatClient` 호출 모델에 묶여 있어
agent, session, tool과 workflow 전체의 의미를 표현할 수 없기 때문이다.

### 4.3 Spring 및 Spring AI 통합

Spring Boot는 실제 호스트 런타임으로 사용한다. Auto-configuration 모듈이 `AgentEngine`, port
구현과 interceptor를 Bean으로 조립하고 starter는 dependency aggregation만 담당한다. 코어는
`ApplicationContext`를 참조하지 않는다.

Spring AI는 필수 의존성이 아니다. 코어 계약이 안정된 뒤 다음 기능만 선택적으로
연결한다.

- `ChatModel`을 model client port로 변환
- `ToolCallback`과 tool provider 변환
- MCP tool discovery와 호출 연결
- Micrometer observation을 코어 trace context에 연결

엔진이 도구 루프를 소유하는 경로에서는 Spring AI의 자동 도구 실행을 비활성화한다.
`ChatMemory`는 session 저장소가 아닌 선택적 단기 문맥 투영으로만 사용할 수 있다.

### 4.4 Session 소유권

`AgentSession`이 영속 가능한 에이전트 상태의 단일 기준이다. 공급자 conversation ID와
Spring AI conversation ID는 session의 내부 metadata일 뿐 권한 경계나 외부 식별자로
사용하지 않는다.

Session 저장소 구현은 낙관적 동시성 제어, tenant 및 사용자 소유권, 만료와 직렬화 버전을
다룰 수 있어야 한다. 저장 기술과 트랜잭션은 호스트 및 infrastructure adapter가
결정한다.

### 4.5 관찰성

코어의 canonical telemetry 모델은 OpenTelemetry GenAI semantic convention을 따른다.
Agent run, model call, tool call과 session operation은 구분된 관찰 단위다.

Spring 통합에서는 Micrometer Observation을 동일 trace context에 연결하며 동일 작업을
코어와 Spring에서 각각 계측하지 않는다. Prompt, tool argument와 결과 기록은 민감정보
노출을 막기 위해 기본 비활성화한다.

## 5. Monorepo 결정

하나의 저장소에서 여러 artifact를 관리하는 multi-project monorepo를 사용한다.
API 변경과 모든 adapter의 contract test를 한 변경 단위로 검증하기 위해서다.

빌드 도구는 Gradle Kotlin DSL을 사용한다. 이 결정은 `gradle-arc-foundation` 브랜치의
Gradle Kotlin DSL 및 Java ARC Foundation 설계에서 확정했으며, 초기 초안의 Maven 전제를
대체한다. 해당 빌드 구현과 설계 문서는 `arc-java-build` 신뢰 실행 게이트를 통과한 뒤
`main`에 반영하며, 그때 이 저장소의 `docs/design/` 구조로 옮긴다.

초기 목표 구조는 다음과 같다.

```text
agent-framework-java/
├── agent-framework-bom
├── agent-framework-api
├── agent-framework-engine
├── agent-framework-testkit
├── providers/
│   └── agent-framework-openai
├── integrations/
│   ├── agent-framework-mcp
│   ├── agent-framework-spring-ai
│   └── agent-framework-spring-boot-autoconfigure
├── hosting/
│   ├── agent-framework-hosting-core
│   └── agent-framework-standalone
├── starters/
│   └── agent-framework-spring-boot-starter
├── compatibility-tests/
└── samples/
    ├── standalone-agent
    └── spring-boot-agent
```

이 구조는 최종 디렉터리를 미리 모두 생성한다는 의미가 아니다. 각 기능을 구현하는 단계에
필요한 모듈만 추가한다. Workflow, A2A, AG-UI와 standalone host 모듈은 초기 저장소에
빈 모듈로 만들지 않는다.

### 5.1 의존성 규칙

- API는 외부 애플리케이션 프레임워크에 의존하지 않는다.
- Engine은 API에만 의존하며 Spring에 의존하지 않는다.
- Provider 및 integration 모듈은 공개 port를 구현하고 엔진 내부 구현을 참조하지 않는다.
- standalone 또는 framework-native facade가 조립을 담당하며 코어가 starter를 역참조하지 않는다.
- Sample은 제품 artifact에서 참조하지 않는다.
- Compatibility test는 공개 API를 통해서만 제품 동작을 검증한다.

### 5.2 릴리스 규칙

초기에는 모든 공개 artifact를 하나의 프로젝트 버전으로 원자적 릴리스한다. 사용자는 BOM을
통해 호환 버전을 가져온다. 모듈별 독립 버전은 실제 릴리스 주기와 호환성 요구가 분리된
증거가 생긴 후에만 검토한다.

## 6. 초기 제품 범위

### 6.1 MVP에 포함

- 단일 agent 실행
- 일반 응답과 스트리밍 응답
- function tool 실행 루프
- session 저장 및 복원
- typed interceptor
- OpenAI-compatible 또는 Azure OpenAI 계열 provider 하나
- MCP Java SDK 직접 연동
- OpenTelemetry 관찰성
- Spring Boot에서 엔진을 조립하는 auto-configuration과 dependency-only starter
- standalone 및 Spring Boot sample

### 6.2 MVP에서 제외

- graph workflow와 durable checkpoint
- multi-agent handoff, group chat과 orchestration
- harness, compaction, background task와 file memory
- A2A, AG-UI와 OpenAI-compatible hosting endpoint
- 자체 애플리케이션 서버
- 자체 DI, transaction, security와 resilience framework
- Spring AI를 통한 자동 도구 실행

Spring AI adapter 자체도 코어 계약과 직접 provider vertical slice가 안정된 후 진행한다.
이 순서는 Spring AI 통합을 포기하는 것이 아니라 코어 계약이 외부 프레임워크에 끌려가지
않도록 검증하는 장치다.

## 7. 기초 개발 순서

### 단계 0: 호환성 기준 확정

- .NET과 Python 중 기능별 기준 구현을 명시한다.
- 일반 응답, 단일 도구, 연속 도구, 도구 실패, session 복원과 스트리밍 취소를 golden
  scenario로 정의한다.
- API 이름보다 입력, 출력 이벤트, 상태 변화와 오류의 관찰 가능한 동작을 비교한다.

종료 조건은 동일 scenario에서 Java 구현이 만들어야 할 상태 변화와 이벤트 순서를
설명할 수 있는 것이다.

### 단계 1: API와 결정적 AgentEngine

- 메시지, content, tool call/result, usage와 종료 원인의 중립 모델을 정의한다.
- 모델, 도구, session 저장소와 telemetry port의 최소 계약을 정의한다.
- 외부 LLM 없이 동작하는 deterministic fake provider로 기본 turn을 검증한다.

종료 조건은 fake provider만으로 일반 응답과 tool loop를 반복 재현할 수 있는 것이다.

### 단계 2: Session, 스트리밍과 MCP

- 버전이 있는 session 직렬화와 복원을 추가한다.
- 취소, timeout 전파와 스트리밍 이벤트 순서를 검증한다.
- MCP Java SDK를 tool port에 직접 연결한다.

종료 조건은 프로세스 경계를 넘은 session 복원과 스트리밍 취소가 contract test를
통과하는 것이다.

### 단계 3: 직접 Provider와 Spring Boot 호스팅

- 공급자 adapter 하나를 end-to-end로 연결한다.
- Spring Boot auto-configuration이 호스트 자원으로 엔진을 조립하고 starter는 필요한
  dependency만 집계한다.
- Standalone과 Spring Boot sample에 동일한 agent 정의를 사용한다.

종료 조건은 애플리케이션 코드의 agent 정의를 바꾸지 않고 두 호스트에서 동일한 golden
scenario가 통과하는 것이다.

### 단계 4: Spring AI Adapter 평가 및 구현

- `ChatModel`, tool callback, MCP와 observation의 변환 손실을 측정한다.
- 자동 tool loop가 비활성화되었음을 contract test로 확인한다.
- 지원하지 못하는 structured output 및 streaming capability를 명시적으로 노출한다.

변환 손실이나 이중 실행 없이 공개 port만으로 통합 가능한 경우 별도 artifact로
릴리스한다.

### 단계 5: Workflow와 Protocol 확장

MVP 계약이 안정된 후 workflow를 독립 하위 프로젝트로 설계한다. 순차, 분기, 병렬,
checkpoint, HITL과 multi-agent 순서로 확장한다. Hosting protocol은 engine과 분리된
adapter로 추가한다.

## 8. 검증 전략

- 모든 model provider는 동일한 model client contract test를 통과해야 한다.
- 모든 session store는 직렬화 round-trip과 동시 갱신 contract test를 통과해야 한다.
- Tool loop는 반복 제한, 중복 이름, 실패, 승인 거부와 취소를 검증해야 한다.
- 스트리밍은 이벤트 순서, 취소와 backpressure 동작을 검증해야 한다.
- Spring Boot test는 Bean 조립과 호스트 lifecycle 위임을 검증해야 한다.
- Dependency rule test는 코어에서 Spring 및 provider 구현 참조를 금지해야 한다.
- Telemetry test는 중복 span과 기본 민감정보 기록이 없음을 검증해야 한다.

Compatibility matrix에는 MAF 기준 버전, Java framework 버전, Spring Boot 및 Spring AI
검증 버전을 기록한다. Spring AI main branch의 snapshot API를 코어 계약의 기준으로
사용하지 않는다.

## 9. 주요 위험과 대응

| 위험 | 대응 |
| --- | --- |
| Engine이 애플리케이션 런타임 역할까지 확장 | 금지 책임과 의존성 규칙을 CI에서 검증 |
| Spring AI와 이중 tool loop | 엔진 소유권 고정 및 자동 tool execution 비활성화 테스트 |
| Session과 ChatMemory 혼동 | AgentSession을 단일 영속 상태로 고정 |
| Spring API가 코어에 유입 | API/Engine의 Spring dependency 금지 |
| Micrometer와 OTel 중복 계측 | 동일 trace bridge와 span 소유자 지정 |
| 원본 .NET/Python 구현 차이 | 기능별 기준 구현과 compatibility matrix 기록 |
| 초기 범위 과대화 | Workflow와 protocol hosting을 MVP에서 제외 |

## 10. 다음 설계 산출물

구현 작업을 세분화하기 전에 다음 두 산출물만 먼저 작성한다.

1. 여섯 개 golden scenario의 호환성 표
2. API, Engine, provider와 host 사이의 최소 공개 계약

두 산출물이 승인된 뒤 첫 vertical slice의 파일·클래스·테스트 단위 구현 계획을 작성한다.

## 11. 참고 자료

- [Microsoft Agent Framework 개요](https://learn.microsoft.com/en-us/agent-framework/overview/)
- [Microsoft Agent Framework Agents](https://learn.microsoft.com/en-us/agent-framework/agents/)
- [Microsoft Agent Framework Sessions](https://learn.microsoft.com/en-us/agent-framework/agents/conversations/session)
- [Microsoft Agent Framework Workflows](https://learn.microsoft.com/en-us/agent-framework/workflows/)
- [Spring AI ChatModel](https://github.com/spring-projects/spring-ai/blob/main/spring-ai-model/src/main/java/org/springframework/ai/chat/model/ChatModel.java)
- [Spring AI Tools](https://docs.spring.io/spring-ai/reference/api/tools.html)
- [Spring AI Chat Memory](https://docs.spring.io/spring-ai/reference/api/chat-memory.html)
