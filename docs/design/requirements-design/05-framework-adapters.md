# Framework adapter patterns

## 1. 목적

Spring Boot, Quarkus, Jakarta EE를 core runtime으로 만들지 않고 동일한 clean-architecture port를
각 container의 관습에 맞게 조립한다. 이 문서는 canonical requirement owner가 아니며
`HOST-010`, `HOST-008`, `OPS-016`, `PRV-001..007`의 framework별 구현을 설명한다.

## 2. 공통 assembly contract

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

## 3. Spring Boot

### Artifacts

```text
integrations/agent-framework-spring-ai
integrations/agent-framework-spring-boot-autoconfigure
starters/agent-framework-spring-boot-starter
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

### Spring AI

- `ChatModel` → `ChatClient`
- `ToolCallback` → `ToolDefinition`/remote declaration adapter
- Reactor → core streaming adapter
- Micrometer Observation → telemetry adapter

engine이 tool loop를 소유하므로 Spring AI automatic tool execution을 비활성화한다.
`ChatMemory`는 session store가 아니라 optional context projection이다.

## 4. Quarkus

### Default pattern

```text
integrations/agent-framework-quarkus
```

- CDI beans/producers
- config mapping
- runtime `AgentEngineCustomizer`
- Mutiny adapter

### Runtime/deployment split gate

다음 중 하나가 구현될 때만 `*-deployment` artifact를 추가한다.

- annotation processor output를 Quarkus build item으로 index
- generated route registration
- native-image reflection/resource hints
- recorder로 runtime init과 static init 분리

단순 CDI wiring만 있으면 runtime/deployment split은 금지한다. Quarkus pattern을 모방하기 위한
빈 deployment module을 만들지 않는다.

## 5. Jakarta EE

```text
integrations/agent-framework-jakarta
```

- CDI producer가 ports와 `AgentEngine` 제공
- portable extension은 automatic discovery가 꼭 필요한 경우만 사용
- Jakarta REST binder는 protocol module의 converter 사용
- container-managed executor/transaction/security/request scope를 그대로 유지

core object가 `BeanManager`, `SecurityContext`, `ManagedExecutorService`를 직접 조회하지 않는다.
producer가 framework-neutral port로 변환한다.

## 6. Plain Java

plain Java는 별도 integration dependency 없이 public builder를 사용한다.

- explicit object construction
- injected executor/client/store
- deterministic interceptor order
- explicit lifecycle close

sample과 testkit의 기준 경로다. framework adapter contract test는 plain Java assembly와 동일
behavior scenario를 실행한다.

## 7. Framework comparison

| 관심사 | Plain Java | Spring Boot | Quarkus | Jakarta EE |
| --- | --- | --- | --- | --- |
| object discovery | explicit builder | conditional beans | CDI beans | CDI producers |
| ordering | list order | `Ordered`→list | priority→list | priority→list |
| async | core candidate | Reactor bridge | Mutiny bridge | CompletionStage/JAX-RS bridge |
| request identity | caller value | SecurityContext adapter | SecurityIdentity adapter | SecurityContext adapter |
| lifecycle | caller | bean lifecycle | CDI lifecycle | CDI lifecycle |
| config | builder | properties | config mapping | MicroProfile Config/producer |
| native metadata | caller/build | AOT hints in adapter | deployment artifact if needed | implementation-specific adapter |

## 8. Dependency and publication rules

- framework type appears only in its artifact
- framework adapter depends on public API; auto-config may depend on public engine factory
- starter has no production classes
- protocol converter does not depend on framework binder
- BOM aligns every adapter
- optional framework dependencies are not promoted to core constraints unless needed for published
  adapter compatibility

## 9. Contract tests

same scenario suite:

- single agent run/stream
- cancellation propagation
- tool loop executes once (no framework duplicate loop)
- session continuity and request isolation
- ordered interceptor application
- provider option mapping/rejection
- protocol terminal event order
- container shutdown closes owned resources only

Spring Boot, Quarkus, Jakarta EE tests may use their native test harness, but assertions target public
behavior rather than container internals.
