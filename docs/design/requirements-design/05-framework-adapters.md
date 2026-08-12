# Framework adapter patterns

## 1. 목적

Spring Boot, Quarkus, Jakarta EE를 core runtime으로 만들지 않고 동일한 clean-architecture port를
각 container의 관습에 맞게 조립한다. 이 문서는 canonical requirement owner가 아니며
`HOST-010`, `HOST-008`, `OPS-016`, `PRV-001..007`의 framework별 구현을 설명한다.

통합 목표는 공통 endpoint를 각 framework에 이식하는 것이 아니다. 애플리케이션 내부에서는
framework-native component로 agent를 사용하고, wire endpoint는 별도 protocol opt-in으로
추가한다.

## 2. 검토한 통합 전략

| 전략 | 장점 | 문제 | 결정 |
| --- | --- | --- | --- |
| 공통 endpoint 중심 | 구현 하나, 언어/프레임워크 독립 | DI/config/lifecycle/AOP가 이질적이고 내부 호출도 network hop 필요 | 거부 |
| framework별 완전 구현 | 가장 native한 API | tool/session/middleware semantics가 분기하고 수정·호환성 비용이 배수로 증가 | 거부 |
| thin semantic kernel + thick native facade | 의미는 하나, 사용 경험은 native | adapter contract와 중복 기능 소유권을 엄격히 관리해야 함 | 채택 |

adapter는 단순 DTO 변환만 하는 얇은 계층이 아니다. container-native discovery, config,
lifecycle, reactive, telemetry 사용 경험을 책임지는 충분히 두꺼운 facade다. 다만 실행 상태
기계와 session/tool semantics는 복제하지 않는다.

## 3. 공통 assembly contract

모든 framework는 plain Java reference builder와 같은 값을 만든다.

```text
AgentFrameworkAssembly
  AgentEngine
  model/provider ports
  session/history ports
  ordered interceptors
  execution strategy
  telemetry sink
  optional hosting/protocol binders
```

container가 없는 unit test에서 같은 assembly를 만들 수 없으면 adapter 경계가 잘못된 것이다.

```text
semantic kernel
  -> framework-native facade
    -> optional protocol endpoint binder
```

- semantic kernel: 모든 framework에서 동일한 실행 의미
- native facade: DI/config/lifecycle/reactive/telemetry 관습에 맞는 사용 경험
- endpoint binder: 외부 wire protocol이 필요한 경우만 설치

## 4. Spring Boot

### Artifacts

```text
integrations/agent-framework-spring-ai
integrations/agent-framework-spring-boot-autoconfigure
starters/agent-framework-spring-boot-starter
integrations/agent-framework-spring-boot-responses-autoconfigure (optional)
starters/agent-framework-spring-boot-starter-responses (optional)
```

### Pattern

- `@AutoConfiguration`
- `@ConditionalOnClass` and `@ConditionalOnMissingBean`
- typed `@ConfigurationProperties`
- `ObjectProvider<T>`로 optional ports 수집
- ordered `AgentEngineCustomizer` chain
- starter는 dependency aggregation만 수행

user bean이 auto-configured default를 이긴다. auto-configuration은 server endpoint를 자동 열지
않고 protocol starter의 explicit opt-in property가 있을 때만 binder를 등록한다.

### Agent interceptor와 Spring AOP

두 체계를 합치지 않는다.

```text
Spring Security / WebFilter
  -> Spring AOP on application service
    -> AgentEngine
      -> typed Agent Framework interceptors
        -> Spring AI adapter
          -> Spring AI Advisors
            -> ChatModel

AgentEngine tool loop
  -> ToolCallInterceptor
    -> Spring-AOP-proxied tool bean
      -> tool implementation
```

- arbitrary `Advisor`를 Agent interceptor로 자동 변환하지 않는다.
- Spring AOP order와 agent interceptor order를 하나의 숫자 공간으로 합치지 않는다.
- `ListableBeanFactory`에서 Agent Framework interceptor interface의 bean name과 instance를 함께
  조회한다.
- Spring comparator로 order를 비교하고, 같은 order는 canonical bean name으로 정렬한다.
- 정렬 결과를 `InterceptorRegistration(id, order, interceptor)`로 만든다.
- user가 `AgentEngine` bean을 직접 제공하면 auto-configuration은 interceptor를 다시 붙이지 않는다.
- Spring AOP가 필요한 tool은 tool 구현 bean 자체를 proxy한다.

### Spring AI

- `ChatModel` → `ChatClient`
- `ToolCallback` → `ToolDefinition`/remote declaration adapter
- Reactor → core streaming adapter
- Micrometer Observation → telemetry adapter
- Spring AI MCP connection/client → borrowed `McpClientPort` adapter

engine이 tool loop를 소유하므로 Spring AI automatic tool execution을 비활성화한다.
`ChatMemory`는 session store가 아니라 optional context projection이다.

| Spring AI feature | Agent Framework integration |
| --- | --- |
| ChatModel | model transport adapter로 사용 |
| Advisors | ChatModel 내부 advisor로 유지; agent interceptor로 자동 변환 안 함 |
| automatic tool execution | AgentEngine 경로에서는 비활성 |
| MCP client | direct MCP adapter 대신 선택 가능 |
| ChatMemory | durable session 대체 금지; context projection만 |
| Observation | semantic event의 Micrometer exporter/bridge |

direct MCP와 Spring AI MCP가 함께 존재하면 `agent-framework.mcp.adapter` 설정 또는 user
`McpClientPort` bean을 요구한다. classpath 순서로 선택하지 않는다.

## 5. Quarkus

### Default pattern

```text
integrations/agent-framework-quarkus
```

- CDI beans/producers
- config mapping
- runtime `AgentEngineCustomizer`
- Mutiny adapter

### Runtime/deployment split gate

다음 중 하나가 구현될 때만 `agent-framework-quarkus-deployment` sibling artifact를 추가한다.
기존 `agent-framework-quarkus`는 runtime artifact와 stable consumer facade로 유지한다.

- annotation processor output를 Quarkus build item으로 index
- generated route registration
- native-image reflection/resource hints
- recorder로 runtime init과 static init 분리

단순 CDI wiring만 있으면 runtime/deployment split은 금지한다. Quarkus pattern을 모방하기 위한
빈 deployment module을 만들지 않는다.

Quarkus facade는 endpoint 없이 `AgentEngine`과 configured `Agent`를 injectable CDI bean으로
제공한다. REST endpoint는 protocol extension을 추가할 때만 등록한다.

## 6. Jakarta EE

```text
integrations/agent-framework-jakarta
```

- CDI producer가 ports와 `AgentEngine` 제공
- portable extension은 automatic discovery가 꼭 필요한 경우만 사용
- Jakarta REST binder는 protocol module의 converter 사용
- container-managed executor/transaction/security/request scope를 그대로 유지

core object가 `BeanManager`, `SecurityContext`, `ManagedExecutorService`를 직접 조회하지 않는다.
producer가 framework-neutral port로 변환한다.

Jakarta facade도 endpoint 없이 CDI injection을 먼저 제공한다. protocol-specific Jakarta REST
binder는 별도 artifact이며 application의 JAX-RS/security 설정을 재정의하지 않는다.

## 7. Plain Java

plain Java는 별도 integration dependency 없이 public builder를 사용한다.

- explicit object construction
- injected executor/client/store
- deterministic interceptor order
- explicit lifecycle close

sample과 testkit의 기준 경로다. framework adapter contract test는 plain Java assembly와 동일
behavior scenario를 실행한다.

### Standalone convenience

`hosting/agent-framework-standalone`은 독립 실행의 기준 facade다.

- provider adapter와 engine builder를 조립
- JDK `HttpClient` 같은 framework-neutral client 사용 가능
- virtual-thread 또는 caller-provided execution strategy 선택
- in-memory store를 development default로 제공
- 자신이 생성한 자원만 닫는 `AutoCloseable AgentApplication`
- HTTP server, DI container, global registry는 생성하지 않음

따라서 agent는 Spring 없이 실행 가능하다. Spring/Quarkus/Jakarta adapter는 standalone과 같은
contract suite를 통과해야 한다.

## 8. Framework comparison

| 관심사 | Plain Java | Spring Boot | Quarkus | Jakarta EE |
| --- | --- | --- | --- | --- |
| object discovery | explicit builder | conditional beans | CDI beans | CDI producers |
| ordering | list order | `Ordered`→list | priority→list | priority→list |
| async | core candidate | Reactor bridge | Mutiny bridge | CompletionStage/JAX-RS bridge |
| request identity | caller value | SecurityContext adapter | SecurityIdentity adapter | SecurityContext adapter |
| lifecycle | caller | bean lifecycle | CDI lifecycle | CDI lifecycle |
| config | builder | properties | config mapping | MicroProfile Config/producer |
| native metadata | caller/build | AOT hints in adapter | deployment artifact if needed | implementation-specific adapter |

## 9. Dependency and publication rules

- framework type appears only in its artifact
- framework adapter depends on public API; auto-config may depend on public engine factory
- Spring Boot starter는 production class 없이 dependency aggregation만 수행한다.
- protocol converter does not depend on framework binder
- BOM aligns every adapter
- optional framework dependencies are not promoted to core constraints unless needed for published
  adapter compatibility

## 10. Contract tests

same scenario suite:

- single agent run/stream
- cancellation propagation
- tool loop executes once (no framework duplicate loop)
- session continuity and request isolation
- ordered interceptor application
- equal-order interceptor tie-break by stable component id
- framework AOP wraps engine/tool beans without being registered as an agent interceptor
- user-supplied engine is not auto-decorated a second time
- provider option mapping/rejection
- direct MCP and framework MCP ambiguity fails at startup
- Spring AI automatic tool execution stays disabled on AgentEngine paths
- protocol terminal event order
- container shutdown closes owned resources only

Spring Boot, Quarkus, Jakarta EE tests may use their native test harness, but assertions target public
behavior rather than container internals.
