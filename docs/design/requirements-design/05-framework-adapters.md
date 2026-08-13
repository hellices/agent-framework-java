# Framework adapter patterns

## 1. Purpose

Assemble the same clean-architecture ports according to each container's conventions without making
Spring Boot, Quarkus, or Jakarta EE the core runtime. This document is not the canonical requirement
owner; it describes framework-specific implementations of `HOST-010`, `HOST-008`, `OPS-016`, and
`PRV-001..007`.

The integration goal is not to port a common endpoint to every framework. Applications use agents
internally as framework-native components and add wire endpoints through a separate protocol
opt-in.

## 2. Integration strategies considered

| Strategy | Advantages | Problems | Decision |
| --- | --- | --- | --- |
| common endpoint first | one implementation, language/framework independent | DI, configuration, lifecycle, and AOP differ, and internal calls also require a network hop | rejected |
| complete per-framework implementation | most native API | tool, session, and middleware semantics diverge, multiplying maintenance and compatibility costs | rejected |
| thin semantic kernel + thick native facade | one set of semantics with a native user experience | requires strict management of adapter contracts and ownership of overlapping features | adopted |

An adapter is not a thin layer that merely converts DTOs. It is a sufficiently substantial facade
responsible for container-native discovery, configuration, lifecycle, reactive, and telemetry
experience. It does not, however, duplicate the execution state machine or session/tool semantics.

## 3. Common assembly contract

Every framework produces the same values as the plain Java reference builder.

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

If the same assembly cannot be created in a unit test without a container, the adapter boundary is
incorrect.

```text
semantic kernel
  -> framework-native facade
    -> optional protocol endpoint binder
```

- semantic kernel: identical execution semantics across all frameworks
- native facade: an experience aligned with DI, configuration, lifecycle, reactive, and telemetry
  conventions
- endpoint binder: installed only when an external wire protocol is required

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

The starter has no production classes but includes autoconfiguration as a transitive dependency.
Users therefore do not need to import auto-configuration classes directly or write infrastructure
`@Bean`s.

### Pattern

- staged `@AutoConfiguration` with explicit `before`/`after`
- `@ConditionalOnClass` and `@ConditionalOnMissingBean`
- typed `@ConfigurationProperties`
- collect optional ports through `ObjectProvider<T>`
- ordered `AgentEngineCustomizer` chain
- provide `AgentEngine`, a configured `AgentFactory`, and a default `Agent` bean when conditions are
  satisfied
- provide a `WorkflowRunner` bean when the workflow module and required ports are present
- the starter performs dependency aggregation only

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

Declare each stage through `@AutoConfiguration(after = ...)`, `afterName`, or equivalent ordered
import metadata. Do not rely on starter dependency declaration order. Do not assume that a negative
bean condition will wait for a later bean and be reevaluated.

Optional auto-configuration from another artifact uses `afterName` instead of a compile-time class
reference. Third-party auto-configuration that provides an Agent bean must declare `before` so it
is processed ahead of `AgentFrameworkDefaultAgentAutoConfiguration`. Regular application
`@Configuration` beans are registered before auto-configuration and therefore always take
precedence over the default.

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

A user-defined bean takes precedence over the auto-configured default. When exactly one model is
available, the default Agent is created automatically. When multiple named models have no default,
the factory remains available, but the default Agent is not created and the condition report
explains how to select a model.

`ModelCatalog` allows multiple models. The Spring adapter converts named Spring AI model clients
and direct `ModelClient` contributors into one immutable catalog and rejects name collisions. The
`agent.framework.default-model` property is the authoritative default. Only when the property is
absent is `@Primary` read as a secondary hint; document that this annotation also affects other
Spring AI injection. Named builders remain available even without a default.

Auto-configuration does not open a server endpoint automatically; it registers a binder only when
the protocol starter's explicit opt-in property is present.

default Agent properties:

```yaml
agent:
  framework:
    default-agent:
      enabled: true
      name: assistant
      instructions: You are a helpful assistant.
```

Properties cover only safe common values such as name, instructions, model selection, and session
policy. Do not automatically attach tool or MCP capabilities from the classpath to the default
Agent.

### Agent interceptors and Spring AOP

Do not merge the two systems.

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

- do not automatically convert arbitrary `Advisor`s into Agent interceptors
- do not combine Spring AOP order and Agent interceptor order into one numeric space
- retrieve both bean names and instances of the Agent Framework interceptor interface from
  `ListableBeanFactory`
- compare order with the Spring comparator and sort equal-order entries by canonical bean name
- convert the sorted result to `InterceptorRegistration(id, order, interceptor)`
- when a user provides an `AgentEngine` bean directly, auto-configuration does not attach
  interceptors again
- proxy the tool implementation bean itself when a tool requires Spring AOP

For an imperative tool bean, the scope of `@Transactional` and method AOP ends with the proxied
method invocation. If a transaction must remain open until an asynchronous `CompletionStage` or
publisher tool completes, the tool implementation explicitly owns the completion boundary through
`TransactionTemplate` or a reactive transaction operator. Agent interceptors do not implicitly
extend Spring transactions.

Even when an application-service `@Transactional` surrounds the entire AgentEngine invocation, do
not treat the transaction as an Agent semantic boundary.

- calling blocking `agent.run(...).await()` inside a transaction can place the entire
  model/network/tool loop in a long-running transaction, so it is not recommended as the default
  example
- when a method returns `AgentRun` or `CompletionStage`, a regular imperative transaction ends when
  the method returns; do not assume it remains open until asynchronous completion
- the tool implementation selects `REQUIRES_NEW` when it requires an independent transaction,
  `NOT_SUPPORTED` when it prohibits an outer transaction, or an explicit template/reactive operator
  when it requires a transaction through asynchronous completion

### Spring AI

- raw `ChatModel` → `ModelClient` (no Advisor chain)
- configured Spring AI `ChatClient` → `ModelClient` (preserves compatible Advisor chains)
- executable `ToolCallback` → `Tool` (`ToolDefinition` + callback-backed `ToolHandler`)
- descriptor-only/remote callback metadata → declaration-only `ToolDefinition`, attached through
  `AgentBuilder.declaredTools(...)`
- Reactor → core streaming adapter
- Micrometer Observation → telemetry adapter
- Spring AI MCP connection/client → borrowed `McpClientPort` adapter

Because the engine owns the tool loop, disable Spring AI automatic tool execution. `ChatMemory` is
an optional context projection, not a session store.

Users who need Advisors select the configured Spring AI `ChatClient` adapter. Do not imply that the
raw `ChatModel` adapter runs Advisors. The configured-client adapter also disables automatic tool
execution Advisor/function paths.

The Agent Framework session is the single source of truth for durable history. Reject Spring AI
`ChatMemory` Advisors that own independent storage on AgentEngine paths. When the ChatMemory API is
required, the `SessionBackedChatMemory` bridge delegates to the current Agent Framework
session/history projection and performs no separate load or store. When a provider declares a
service-managed history capability, disable Agent Framework local history load/store according to
AGT-016; this is a different path from Spring AI ChatMemory ownership.

When the Advisor configuration of an opaque configured client cannot be inspected, the user must
declare tool/history capabilities through `SpringAiClientDescriptor` or equivalent adapter
metadata. Do not silently allow an undeclared configured client. This rule preserves stateless and
redaction Advisors while ensuring that only one layer owns the tool loop and history.

| Spring AI feature | Agent Framework integration |
| --- | --- |
| ChatModel | used as a model transport adapter |
| Advisors | retained inside a configured Spring AI ChatClient; not automatically converted to Agent interceptors |
| automatic tool execution | disabled on AgentEngine paths |
| MCP client | may be selected instead of the direct MCP adapter |
| ChatMemory | only a session-backed projection bridge is allowed; independent history owners are rejected |
| Observation | Micrometer exporter/bridge for semantic events |

MCP selector property hierarchy:

```text
agent.framework.mcp.servers.<name>.adapter
  > agent.framework.mcp.default-adapter
  > exactly one available adapter
```

A named user-defined `McpClientPort` bean takes precedence over property resolution for that server.
When direct MCP and Spring AI MCP are both present and no selector is configured, do not choose by
classpath order. Detect the ambiguity during the configuration phase, before
`AgentFrameworkEngineAutoConfiguration` registers the engine bean, and report the candidates and
resolution property in the condition report. Do not defer detection until the first run or the
execution of a bean method.

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

Because this is provided as a first-class Quarkus extension, maintain runtime/deployment sibling
artifacts and the extension descriptor from the first release. `quarkus-agent-framework` is the
stable consumer coordinate, and the deployment artifact owns build steps, generated ToolSet/route
indexing, native metadata, and recorder integration. The runtime artifact links the deployment
coordinate through the `META-INF/quarkus-extension.*` descriptor. Do not add unnecessary reflection
or build steps, but do not omit the extension tooling contract itself.

Without an endpoint, the Quarkus facade provides `AgentEngine`, `AgentFactory`, a conditional
`WorkflowRunner`, and configured `Agent`s as injectable CDI beans. Register REST endpoints only
when a protocol extension is added.

- with a single default model, the extension provides an `@DefaultBean`/synthetic default Agent
- a user-defined Agent bean replaces the default
- named models contribute to the catalog through a custom qualifier or `@Identifier`
- report zero models and unqualified ambiguity through startup diagnostics

## 6. Jakarta EE

```text
integrations/agent-framework-jakarta-ee
integrations/agent-framework-jakarta-microprofile-config (optional)
```

- CDI producers provide ports and `AgentEngine`
- use CDI 4 / Jakarta EE 10 as the baseline
- a build-compatible or portable extension checks for existing Agent beans and registers a
  synthetic default Agent bean only when needed
- the Jakarta REST binder uses converters from the protocol module
- preserve container-managed executor, transaction, security, and request scopes

Core objects do not look up `BeanManager`, `SecurityContext`, or `ManagedExecutorService` directly.
Producers convert them into framework-neutral ports.

The Jakarta facade likewise provides CDI injection of `AgentEngine`, `AgentFactory`, and a
conditional `WorkflowRunner` without an endpoint. Protocol-specific Jakarta REST binders are
separate artifacts and do not override the application's JAX-RS or security configuration.

The pure Jakarta EE baseline configuration uses CDI producers or a programmatic builder.
MicroProfile Config is a separate optional bridge and is not a prerequisite for Jakarta EE support.
Provide the default Agent as an `@ApplicationScoped` interface bean, and have the extension back off
when a user-defined Agent exists.

## 7. Plain Java

Plain Java uses the public builder without a separate integration dependency.

- explicit object construction
- injected executor/client/store
- deterministic interceptor order
- explicit lifecycle close

This is the reference path for samples and the testkit. Framework-adapter contract tests run the
same behavior scenarios as the plain Java assembly.

### Standalone convenience

`hosting/agent-framework-standalone` is the reference facade for standalone execution.

- assembles provider adapters and the engine builder
- may use framework-neutral clients such as JDK `HttpClient`
- selects a virtual-thread or caller-provided execution strategy
- provides an in-memory store as the development default
- provides an `AutoCloseable AgentApplication` that closes only resources it created
- creates no HTTP server, DI container, or global registry

Agents can therefore run without Spring. Spring, Quarkus, and Jakarta adapters must pass the same
contract suite as standalone.

## 8. Framework comparison

| Concern | Plain Java | Spring Boot | Quarkus | Jakarta EE |
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
- Spring Boot starters perform dependency aggregation only and contain no production classes.
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
- session continuity is identical with or without a ChatMemory bean, and history is not stored twice
- protocol terminal event order
- container shutdown closes owned resources only

### Lifecycle adapters

- Spring: owned async resources use a destroy method or `DisposableBean`; use `SmartLifecycle` when
  ordered graceful shutdown is required
- Quarkus: CDI disposer or shutdown observer
- Jakarta EE: disposer/`@PreDestroy`
- no framework adapter closes borrowed models, MCP clients, executors, or stores

Spring Boot, Quarkus, and Jakarta EE tests may use their native test harness, but assertions target
public behavior rather than container internals.
