# Developer experience and progressive disclosure

## 1. Goals

Do not expose the internal clean-architecture complexity of modules and ports to application
developers. Most users should be able to run an agent by understanding only these four types.

```text
AgentFactory -> AgentBuilder -> Agent -> AgentRun
```

Minimal path:

```java
Agent agent =
    agents.builder()
        .instructions("You are a helpful assistant.")
        .build();

AgentResponse response = agent.run("Hello").await();
```

`await()` is an imperative convenience. Async applications use the `AgentRun.response()`
completion, while streaming applications use the `Flow.Publisher<AgentResponseUpdate>` from
`AgentStreamingRun.updates()` returned by `agent.runStreaming(...)`.

## 2. Public facade and advanced SPI

### General developer API

```text
Agent
AgentFactory
AgentBuilder
AgentRun / AgentStreamingRun
Tools / Tool / ToolSet
Workflow / WorkflowBuilder / WorkflowRunner
Harness
AgentApplication
```

### Integration developer SPI

```text
ModelClient
SessionStore
ExecutionStrategy
TargetResolver
StateCodecRegistry
McpClientPort
TelemetrySink
InterceptorRegistration
```

Although SPIs are public, they are not central to the getting-started API or IDE discovery. General
users use the `AgentFactory` created by a starter or standalone assembly.

## 3. AgentFactory

Do not share a mutable `AgentBuilder` as a singleton bean. The framework provides a singleton
`AgentFactory` that creates a new builder for each call.

```java
public interface AgentFactory {
    AgentBuilder builder();
    AgentBuilder builder(String modelName);
    AgentBuilder builderWithClient(ModelClient model);
    Agent bind(AgentDefinition definition, AgentRuntime runtime);
}
```

- the factory combines a model-independent `AgentEngine` with an immutable `ModelCatalog`
- use `builder()` when exactly one model is unambiguous
- when multiple models exist, select one by name or with `builderWithClient(ModelClient)`
- create the factory even when multiple named models exist; only a `builder()` call without a
  default fails explicitly, while `builder(name)` selects the corresponding model
- builders are thread-confined and build results are immutable
- `AgentBuilder.buildDefinition()` returns the declarative `AgentDefinition` (id, name, description,
  instructions, tool declarations, and `defaultRunOptions` including `maxToolIterations`) without a
  runtime binding; `build()` derives the `AgentRuntime` (model client, tool bindings, context
  providers) and binds it to the shared engine. Context providers and executable tool bindings live
  only in the runtime.
- `bind(definition, runtime)` binds an externally constructed definition; declaration-only tools on
  a manually built `AgentDefinition` are preserved

Compose the shared engine once and reuse it. `AgentEngine.builder().build()` configures only session
services; `engine.factory(catalog)` binds a model catalog, and `engine.factory()` provides the
explicit-client path over an empty catalog for `builderWithClient(model)`.

## 4. Plain Java / standalone

```java
try (AgentApplication app =
        AgentFramework.standalone()
            .ownedModel(() -> OpenAI.model("gpt-5")
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .build())
            .build()) {

    Agent agent =
        app.agents()
            .builder()
            .name("assistant")
            .instructions("You are a helpful assistant.")
            .build();

    System.out.println(agent.run("Hello").await().text());
}
```

`hosting/agent-framework-standalone` can assemble:

- provider adapter
- JDK `HttpClient`
- a virtual-thread or caller-provided execution strategy
- an in-memory session store for development
- an `AutoCloseable AgentApplication` that closes owned resources

It creates no HTTP server, DI container, shutdown hook, or global registry.

Method names distinguish ownership.

- `model(ModelClient)`: caller-owned borrowed model; the application does not close it
- `ownedModel(Supplier<? extends ModelClient>)`: the application creates it and closes it on
  shutdown

Callers that pass an already-created closeable model to `model(...)` must close it separately.
`AgentApplication.close()` is idempotent: repeated calls succeed, owned resources are closed
exactly once, and borrowed models, clients, stores, and executors are never closed.

## 5. Spring Boot

Existing Spring AI application:

```kotlin
implementation("io.github.hellices.agentframework:agent-framework-spring-boot-starter-spring-ai")
implementation("org.springframework.ai:spring-ai-starter-model-openai")
```

When Spring AI provides exactly one model bean, the auto-configuration included by the starter
creates `AgentEngine`, `AgentFactory`, and a default `Agent` bean. The default path requires no
application `@Configuration` or `@Bean Agent`.

```yaml
agent:
  framework:
    default-agent:
      instructions: You are a helpful assistant.
```

Application code injects the default Agent directly.

```java
@Service
final class SupportService {
    private final Agent agent;

    SupportService(Agent agent) {
        this.agent = agent;
    }

    String ask(String question) {
        return agent.run(question).await().text();
    }
}
```

Define an Agent bean only when multiple Agents, per-Agent tools/models/instructions, or a custom
session policy are required.

```java
@Bean
Agent assistant(AgentFactory agents, WeatherFunctions weather) {
    return agents.builder()
        .name("assistant")
        .instructions("You are a helpful assistant.")
        .tools(WeatherFunctionsToolSet.create(weather))
        .build();
}
```

A starter cannot invent a model by itself. One of the following must be present:

- a model bean created by the Spring AI-specific Agent Framework starter plus a Spring AI model
  starter
- a `ModelClient` created by a direct provider adapter
- user-defined `ModelClient`

With zero models, the condition report explains the missing dependency and neither `AgentFactory`
nor a default Agent is created. With multiple models and no default, `AgentFactory` is created but
the default Agent is not; a named builder or primary model is required.

## 6. Quarkus and Jakarta EE

Agent definitions are the same as in Spring; only the way the factory is obtained is
container-native.

```java
@ApplicationScoped
class Agents {
    @Produces
    @ApplicationScoped
    Agent assistant(AgentFactory agents, WeatherFunctions weather) {
        return agents.builder()
            .name("assistant")
            .tools(WeatherFunctionsToolSet.create(weather))
            .build();
    }
}
```

- Quarkus: CDI injection, ConfigMapping, Mutiny bridge, `@Identifier`/custom qualifier for named models
- Jakarta EE: CDI 4 producer/extension, optional MicroProfile Config bridge, Jakarta REST bridge

Do not use Spring annotations or Reactor types in shared API examples.

## 7. Tools

### 7.1 Explicit Java

```java
Tool weather =
    Tools.function(
        "weather",
        "Get weather for a city",
        WeatherRequest.class,
        request -> weatherService.get(request.city()));

Agent agent = agents.builder().tools(weather).build();
```

### 7.2 Portable annotation processing

```java
@AgentTool(description = "Get weather for a city")
Weather getWeather(@ToolParam String city) {
    return weatherService.get(city);
}
```

The annotation processor generates a framework-neutral companion, `WeatherFunctionsToolSet`, which
accepts a target instance and creates a `ToolSet`. Because the processor cannot alter the original
class to implement `ToolSet`, examples and DI adapters use the generated companion explicitly.
Spring component scanning and Quarkus/Jakarta reflection are not prerequisites for schema
generation.

Attach tools explicitly to each agent. Do not automatically expose every tool on the classpath or
in the DI container to every agent.

## 8. MCP

Users handle transport differences between the direct SDK and Spring AI MCP behind `ToolSet`.

```java
Agent assistant =
    agents.builder()
        .tools(mcpTools.named("github"))
        .build();
```

Spring configuration can create a named MCP `ToolSet`.

```yaml
agent:
  framework:
    mcp:
      default-adapter: spring-ai
      servers:
        github:
          adapter: spring-ai
```

Alternatively, use the direct adapter:

```yaml
agent:
  framework:
    mcp:
      servers:
        github:
          adapter: direct
          url: https://example.com/mcp
```

The per-server `adapter` takes precedence over the global `default-adapter`. If neither is set and
multiple direct/Spring AI candidates exist, creation of that server's ToolSet fails at startup.

Users attach a `ToolSet` to an agent explicitly. Discovering an MCP server does not automatically
expose its tools to every agent.

## 9. Workflow

```java
Workflow support =
    Workflow.builder()
        .start(classifier)
        .then(technicalSupport)
        .otherwise(customerSupport)
        .build();

WorkflowRun run = workflows.run(support, request);
```

Convert explicitly to expose a workflow through the Agent API.

```java
Agent supportAgent = workflows.asAgent(support);
supportAgent.run("My server is down");
```

The standalone or framework assembly injects `WorkflowRunner workflows`. The runner receives and
combines the execution strategy, clock, checkpoint store, and codec registry. Do not provide a
static global helper that runs or converts to an Agent using only an immutable `Workflow`
definition. In Spring, Quarkus, and Jakarta, `Workflow`, `WorkflowRunner`, and `Agent` are injectable
components, not endpoints.

## 10. Harness

Harness is an Agent decorator facade, not a separate runtime.

```java
Agent codingAgent =
    Harness.decorate(baseAgent)
        .todo()
        .approvals()
        .build();
```

Separate optional modules such as skills provide their own decorators or factories.

```java
Agent skilledAgent =
    Skills.decorate(codingAgent)
        .directory(skillDirectory)
        .build();
```

An optional Java JAR cannot add methods to an already-compiled `AgentBuilder`. Therefore, do not
predeclare `.harness()`, `.skills()`, or `.codeAct()` on the base builder. Harness, skills,
background-agent, shell, and CodeAct modules provide an `AgentDecorator`, `ToolSet`, or module-owned
builder/factory and are applied to an Agent explicitly.

## 11. Progressive disclosure levels

| Level | Audience | APIs used |
| --- | --- | --- |
| 0 | single agent | `AgentFactory`, `AgentBuilder`, `Agent.run` |
| 1 | tools/MCP | `Tool`, `ToolSet`, builder `.tools(...)` |
| 2 | session/interceptor | named builder customizer and typed options |
| 3 | workflow/harness | injected `WorkflowRunner`, optional decorator/factory |
| 4 | adapter development | public SPI and contract testkit |

Each level leaves the APIs from previous levels unchanged. The appearance of an SPI type or engine
internal in a simple example is a developer-experience regression.

## 12. Dependency experience

Do not create an artifact for every provider × framework combination.

| Environment | Minimum dependencies |
| --- | --- |
| standalone direct provider | `agent-framework-standalone` + provider adapter |
| Spring AI | Agent Framework Spring AI starter + Spring AI model starter |
| Spring direct provider | Agent Framework Spring Boot starter + provider adapter |
| Quarkus | Agent Framework Quarkus extension + provider adapter |
| Jakarta EE | Agent Framework Jakarta integration + provider adapter |

Align all dependencies through the BOM. For protocol endpoints, add only the required protocol
starter.

## 13. Developer experience acceptance tests

- starter + single model → `AgentFactory` is available
- starter + single model + no user Agent → default Agent injection is available
- user Agent/Engine/Factory bean → corresponding auto-configured default backs off
- zero model → actionable condition/error message
- multiple models without default → default Agent backs off; unqualified `builder()` fails with
  actionable model-selection error
- multiple named models + explicit selection → factory remains usable
- same Agent definition works in standalone, Spring, Quarkus, Jakarta tests
- tool/MCP attachment requires one explicit `.tools(...)`
- generated tool companion produces a type-correct `ToolSet`
- workflow run/as-agent requires an injected `WorkflowRunner`
- framework automatic tool loop does not execute a call twice
- endpoint module absent → no route/server side effect
- optional capability module absent → base Agent API unchanged
- introductory sample contains no SPI or engine-internal type
- repeated `AgentApplication.close()` succeeds, closes owned resources once, and never closes borrowed ones
