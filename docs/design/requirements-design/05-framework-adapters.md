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
  ModelCatalog
  AgentFactory
  model/provider ports
  session/history ports
  ordered interceptors
  execution strategy
  telemetry sink
  optional WorkflowRunner
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
starters/agent-framework-spring-boot-starter-spring-ai
integrations/agent-framework-spring-boot-responses-autoconfigure (optional)
starters/agent-framework-spring-boot-starter-responses (optional)
```

dependency direction:

```text
agent-framework-spring-boot-starter
  -> agent-framework-spring-boot-autoconfigure
  -> agent-framework-engine

agent-framework-spring-boot-starter-spring-ai
  -> agent-framework-spring-boot-starter
  -> agent-framework-spring-ai
```

starter는 production class를 갖지 않지만 autoconfiguration을 transitive dependency로 포함한다.
따라서 사용자가 auto-configuration class를 직접 import하거나 infrastructure `@Bean`을 작성할
필요가 없다.

### Pattern

- 단계별 `@AutoConfiguration`과 명시적 `before/after`
- `@ConditionalOnClass` and `@ConditionalOnMissingBean`
- typed `@ConfigurationProperties`
- `ObjectProvider<T>`로 optional ports 수집
- ordered `AgentEngineCustomizer` chain
- 조건을 만족하면 `AgentEngine`, configured `AgentFactory`, default `Agent` bean
- workflow module과 필수 ports가 있을 때 `WorkflowRunner` bean
- starter는 dependency aggregation만 수행

auto-configuration chain:

```text
SpringAiModelContributorAutoConfiguration
  after Spring AI model auto-configurations
  -> contributes named raw ChatModel / configured Spring AI ChatClient adapters

DirectModelContributorAutoConfiguration
  -> contributes named direct ModelClient adapters

AgentFrameworkModelCatalogAutoConfiguration
  after every model contributor auto-configuration
  -> validates names and builds exactly one immutable ModelCatalog

AgentFrameworkEngineAutoConfiguration
  after session/execution/interceptor auto-configurations
  -> AgentEngine

AgentFrameworkFactoryAutoConfiguration
  after engine + model catalog
  -> AgentFactory

AgentFrameworkDefaultAgentAutoConfiguration
  after factory
  -> optional default Agent
```

각 단계는 `@AutoConfiguration(after = ...)`, `afterName`, 또는 동등한 ordered import metadata로
명시한다. starter dependency declaration order에 기대지 않는다. negative bean condition이
나중에 생길 bean을 기다려 재평가될 것이라고 가정하지 않는다.

다른 artifact의 optional auto-configuration은 compile-time class reference 대신 `afterName`을
사용한다. Agent bean을 제공하는 third-party auto-configuration은
`AgentFrameworkDefaultAgentAutoConfiguration`보다 먼저 처리되도록 `before`를 선언해야 한다.
일반 application `@Configuration` bean은 auto-configuration보다 먼저 등록되므로 항상 default를
이긴다.

default bean conditions:

```text
AgentEngine
  missing user AgentEngine + required non-model runtime ports available

AgentFactory
  missing user AgentFactory + AgentEngine + ModelCatalog available

default Agent
  missing every user Agent
  + AgentFactory available
  + exactly one default model selection
  + agent.framework.default-agent.enabled=true (matchIfMissing)
```

user bean이 auto-configured default를 이긴다. model이 정확히 하나면 default Agent가 자동
생성된다. 여러 named model에 default가 없으면 factory는 유지하되 default Agent만 만들지 않고
condition report가 model 선택 방법을 설명한다.

`ModelCatalog`는 여러 model을 허용한다. Spring adapter는 named Spring AI model clients와 direct
`ModelClient` contributor를 하나의 immutable catalog로 변환하며 이름 충돌을 거부한다.
`agent.framework.default-model` property가 authoritative default다. property가 없을 때만
`@Primary`를 secondary hint로 읽고, 이 annotation은 다른 Spring AI injection에도 영향을 준다는
점을 문서화한다. default가 없어도 named builder는 계속 사용할 수 있다.

auto-configuration은 server endpoint를 자동 열지 않고 protocol starter의 explicit opt-in
property가 있을 때만 binder를 등록한다.

default Agent properties:

```yaml
agent:
  framework:
    default-agent:
      enabled: true
      name: assistant
      instructions: You are a helpful assistant.
```

properties는 name, instructions, model selection, session policy 같은 안전한 공통값만 다룬다.
classpath의 tool/MCP capability를 default Agent에 자동 연결하지 않는다.

### Agent interceptor와 Spring AOP

두 체계를 합치지 않는다.

```text
Spring Security / WebFilter
  -> Spring AOP on application service
    -> AgentEngine
      -> typed Agent Framework interceptors
        -> ModelClient adapter
          -> raw ChatModel
          OR
          -> configured Spring AI ChatClient
            -> compatible Spring AI Advisors
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

imperative tool bean의 `@Transactional`/method AOP 범위는 proxied method invocation까지만이다.
비동기 `CompletionStage`/publisher tool이 완료될 때까지 transaction을 유지해야 하면 tool
구현이 `TransactionTemplate` 또는 reactive transaction operator로 완료 경계를 명시적으로
소유한다. Agent interceptor가 Spring transaction을 암묵 연장하지 않는다.

application-service `@Transactional`이 AgentEngine 전체를 감쌀 때도 transaction을 agent
semantic boundary로 간주하지 않는다.

- blocking `agent.run(...).await()`를 transaction 안에서 호출하면 model/network/tool loop 전체가
  장기 transaction에 들어갈 수 있으므로 기본 예제로 권장하지 않는다.
- method가 `AgentRun`/`CompletionStage`를 반환하면 일반 imperative transaction은 method 반환과
  함께 끝나며 비동기 완료까지 유지된다고 가정하지 않는다.
- tool이 독립 transaction을 요구하면 `REQUIRES_NEW`, 외부 transaction을 금지하면
  `NOT_SUPPORTED`, 비동기 완료까지 필요하면 explicit template/reactive operator를 tool
  구현이 선택한다.

### Spring AI

- raw `ChatModel` → `ModelClient` (Advisor chain 없음)
- configured Spring AI `ChatClient` → `ModelClient` (compatible Advisor chain 보존)
- executable `ToolCallback` → `Tool` (`ToolDefinition` + callback-backed `ToolHandler`)
- descriptor-only/remote callback metadata → declaration-only `ToolDefinition`, attached through
  `AgentBuilder.declaredTools(...)`
- Reactor → core streaming adapter
- Micrometer Observation → telemetry adapter
- Spring AI MCP connection/client → borrowed `McpClientPort` adapter

engine이 tool loop를 소유하므로 Spring AI automatic tool execution을 비활성화한다.
`ChatMemory`는 session store가 아니라 optional context projection이다.

Advisor가 필요한 사용자는 configured Spring AI `ChatClient` adapter를 선택한다. raw
`ChatModel` adapter가 Advisor를 실행한다고 가장하지 않는다. configured client adapter도
automatic tool execution advisor/function path는 비활성화한다.

Agent Framework session은 durable history의 단일 기준이다. 독립 storage를 소유한 Spring AI
`ChatMemory` Advisor는 AgentEngine 경로에서 거부한다. ChatMemory API가 필요한 경우
`SessionBackedChatMemory` bridge가 현재 Agent Framework session/history projection에 위임하며
별도 load/store를 수행하지 않는다. provider가 service-managed history capability를 선언한
경우에는 AGT-016에 따라 Agent Framework local history load/store를 비활성화하지만, 이것은
Spring AI ChatMemory ownership과 다른 경로다.

opaque configured client의 Advisor 구성을 검사할 수 없으면 사용자가
`SpringAiClientDescriptor` 또는 동등 adapter metadata로 tool/history capability를 선언해야 한다.
선언되지 않은 configured client를 조용히 허용하지 않는다. 이 규칙으로 stateless/redaction
Advisor는 보존하면서 tool loop와 history는 한 계층만 소유한다.

| Spring AI feature | Agent Framework integration |
| --- | --- |
| ChatModel | model transport adapter로 사용 |
| Advisors | configured Spring AI ChatClient 안에서 유지; agent interceptor로 자동 변환 안 함 |
| automatic tool execution | AgentEngine 경로에서는 비활성 |
| MCP client | direct MCP adapter 대신 선택 가능 |
| ChatMemory | session-backed projection bridge만 허용; 독립 history owner는 거부 |
| Observation | semantic event의 Micrometer exporter/bridge |

MCP selector property hierarchy:

```text
agent.framework.mcp.servers.<name>.adapter
  > agent.framework.mcp.default-adapter
  > exactly one available adapter
```

named user `McpClientPort` bean은 해당 server의 property resolution보다 우선한다. direct MCP와
Spring AI MCP가 함께 존재하고 어떤 selector도 없으면 classpath 순서로 선택하지 않는다.
ambiguity는 `AgentFrameworkEngineAutoConfiguration`이 engine bean을 등록하기 전 configuration
phase에서 검출하고 condition report에 후보와 해결 property를 출력한다. 첫 run이나 bean method
내부까지 미루지 않는다.

## 5. Quarkus

### Default pattern

```text
integrations/quarkus-agent-framework
integrations/quarkus-agent-framework-deployment
```

- CDI beans/producers
- config mapping
- runtime `AgentEngineCustomizer`
- Mutiny adapter

first-class Quarkus extension으로 제공하므로 runtime/deployment sibling과 extension descriptor를
첫 릴리스부터 유지한다. `quarkus-agent-framework`가 stable consumer coordinate고 deployment
artifact는 build steps, generated ToolSet/route indexing, native metadata, recorder integration을
소유한다. runtime artifact는 `META-INF/quarkus-extension.*` descriptor로 deployment coordinate를
연결한다. 불필요한 reflection이나 build step은 추가하지 않지만 extension tooling 계약 자체는
생략하지 않는다.

Quarkus facade는 endpoint 없이 `AgentEngine`, `AgentFactory`, 조건부 `WorkflowRunner`, configured
`Agent`를 injectable CDI bean으로 제공한다. REST endpoint는 protocol extension을 추가할 때만
등록한다.

- single default model이면 extension이 `@DefaultBean`/synthetic default Agent를 제공
- user Agent bean이 default를 대체
- named model은 custom qualifier/`@Identifier`로 catalog에 기여
- zero model과 unqualified ambiguity는 startup diagnostic으로 보고

## 6. Jakarta EE

```text
integrations/agent-framework-jakarta-ee
integrations/agent-framework-jakarta-microprofile-config (optional)
```

- CDI producer가 ports와 `AgentEngine` 제공
- CDI 4 / Jakarta EE 10을 baseline으로 사용
- build-compatible 또는 portable extension이 existing Agent bean을 확인해 필요한 경우에만
  default Agent synthetic bean을 등록
- Jakarta REST binder는 protocol module의 converter 사용
- container-managed executor/transaction/security/request scope를 그대로 유지

core object가 `BeanManager`, `SecurityContext`, `ManagedExecutorService`를 직접 조회하지 않는다.
producer가 framework-neutral port로 변환한다.

Jakarta facade도 endpoint 없이 `AgentEngine`, `AgentFactory`, 조건부 `WorkflowRunner` CDI
injection을 먼저 제공한다. protocol-specific Jakarta REST binder는 별도 artifact이며
application의 JAX-RS/security 설정을 재정의하지 않는다.

pure Jakarta EE baseline config는 CDI producer/programmatic builder다. MicroProfile Config는
별도 optional bridge이며 Jakarta EE 지원의 전제 조건이 아니다. default Agent는
`@ApplicationScoped` interface bean으로 제공하고 user Agent가 있으면 extension이 back off한다.

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
| async | CompletionStage/Flow.Publisher | Reactor bridge | Mutiny bridge | CompletionStage/JAX-RS bridge |
| request identity | caller value | SecurityContext adapter | SecurityIdentity adapter | SecurityContext adapter |
| lifecycle | caller | bean lifecycle | CDI lifecycle | CDI lifecycle |
| config | builder | properties | ConfigMapping | CDI producer/programmatic; optional MicroProfile bridge |
| native metadata | caller/build | AOT hints in adapter | deployment artifact | implementation-specific adapter |

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
- raw ChatModel adapter has no Advisors; configured Spring AI ChatClient adapter preserves non-tool Advisors
- ChatMemory bean 유무와 무관하게 session continuity가 같고 history가 중복 저장되지 않음
- protocol terminal event order
- container shutdown closes owned resources only

### Lifecycle adapters

- Spring: owned async resources는 destroy method/`DisposableBean`, ordered graceful shutdown이
  필요하면 `SmartLifecycle`
- Quarkus: CDI disposer 또는 shutdown observer
- Jakarta EE: disposer/`@PreDestroy`
- borrowed model/MCP/executor/store는 어떤 framework adapter도 닫지 않음

Spring Boot, Quarkus, Jakarta EE tests may use their native test harness, but assertions target public
behavior rather than container internals.
