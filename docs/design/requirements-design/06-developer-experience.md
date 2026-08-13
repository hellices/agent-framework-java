# Developer experience and progressive disclosure

## 1. 목표

내부 clean architecture의 module·port 복잡도를 애플리케이션 개발자에게 노출하지 않는다.
대부분의 사용자는 다음 네 타입만 이해하면 agent를 실행할 수 있어야 한다.

```text
AgentFactory -> AgentBuilder -> Agent -> AgentRun
```

최소 경로:

```java
Agent agent =
    agents.builder()
        .instructions("You are a helpful assistant.")
        .build();

AgentResponse response = agent.run("Hello").await();
```

`await()`는 imperative convenience다. async 애플리케이션은 `AgentRun.response()` completion을
사용하고, streaming 애플리케이션은 `agent.runStreaming(...)`이 반환한
`AgentStreamingRun.updates()`의 `Flow.Publisher<AgentResponseUpdate>`를 사용한다.

## 2. 공개 facade와 고급 SPI

### 일반 개발자 API

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

### integration 개발자 SPI

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

SPI는 공개되어도 getting-started API와 IDE discovery의 중심이 아니다. 일반 사용자는 starter나
standalone assembly가 만든 `AgentFactory`를 사용한다.

## 3. AgentFactory

mutable `AgentBuilder`를 singleton bean으로 공유하지 않는다. framework가 singleton
`AgentFactory`를 제공하고 호출마다 새 builder를 만든다.

```java
public interface AgentFactory {
    AgentBuilder builder();
    AgentBuilder builder(String modelName);
    AgentBuilder builder(ModelClient model);
}
```

- factory는 model-independent `AgentEngine`과 immutable `ModelCatalog`를 합성한다.
- model 하나가 명확하면 `builder()` 사용
- model이 여러 개면 name 또는 explicit port 선택
- 여러 named model이 있어도 factory는 생성하며, default가 없는 `builder()` 호출만 명시적으로
  실패한다. `builder(name)`은 해당 model을 선택한다.
- builder는 thread-confined이고 build 결과는 immutable

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

`hosting/agent-framework-standalone`은 다음을 조립할 수 있다.

- provider adapter
- JDK `HttpClient`
- virtual-thread 또는 caller-provided execution strategy
- development용 in-memory session store
- owned resource를 닫는 `AutoCloseable AgentApplication`

HTTP server, DI container, shutdown hook, global registry는 만들지 않는다.

ownership은 method 이름으로 구분한다.

- `model(ModelClient)`: caller-owned borrowed model; application이 닫지 않음
- `ownedModel(Supplier<? extends ModelClient>)`: application이 생성하고 종료 시 닫음

이미 생성한 closeable model을 `model(...)`로 넘긴 호출자는 별도로 close해야 한다.
`AgentApplication.close()`는 idempotent다. 반복 호출은 성공하고 owned resource는 정확히 한 번
닫으며 borrowed model/client/store/executor는 닫지 않는다.

## 5. Spring Boot

기존 Spring AI 애플리케이션:

```kotlin
implementation("io.github.hellices.agentframework:agent-framework-spring-boot-starter-spring-ai")
implementation("org.springframework.ai:spring-ai-starter-model-openai")
```

Spring AI가 정확히 하나의 model bean을 제공하면 starter가 포함한 auto-configuration이
`AgentEngine`, `AgentFactory`, default `Agent` Bean을 만든다. 기본 경로에는 application
`@Configuration`이나 `@Bean Agent`가 필요 없다.

```yaml
agent:
  framework:
    default-agent:
      instructions: You are a helpful assistant.
```

사용 코드는 default Agent를 바로 주입한다.

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

다중 Agent, Agent별 tool/model/instructions, custom session policy가 필요할 때만 Agent Bean을
정의한다.

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

starter만으로 model을 발명할 수는 없다. 다음 중 하나가 있어야 한다.

- Spring AI-specific Agent Framework starter + Spring AI model starter가 만든 model bean
- direct provider adapter가 만든 `ModelClient`
- user-defined `ModelClient`

zero model이면 condition report가 missing dependency를 설명하고 `AgentFactory`/default Agent를
만들지 않는다. 여러 model인데 default가 없으면 `AgentFactory`는 만들되 default Agent를
만들지 않고 named builder 또는 primary model을 요구한다.

## 6. Quarkus와 Jakarta EE

Agent 정의는 Spring과 동일하다. factory를 얻는 방법만 container-native하다.

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

Spring annotation이나 Reactor 타입을 공유 API 예제에 사용하지 않는다.

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

annotation processor가 target instance를 받아 `ToolSet`을 만드는 framework-neutral companion
`WeatherFunctionsToolSet`을 생성한다. processor는 원본 class가 `ToolSet`을 구현하게 바꿀 수
없으므로 예제와 DI adapter는 생성 companion을 명시적으로 사용한다. Spring component scan,
Quarkus/Jakarta reflection은 schema 생성의 필수 조건이 아니다.

도구는 agent별로 명시 연결한다. classpath/DI container의 모든 tool을 모든 agent에 자동
노출하지 않는다.

## 8. MCP

사용자는 direct SDK와 Spring AI MCP의 transport 차이를 `ToolSet` 뒤에서 다룬다.

```java
Agent assistant =
    agents.builder()
        .tools(mcpTools.named("github"))
        .build();
```

Spring configuration은 named MCP `ToolSet`을 만들 수 있다.

```yaml
agent:
  framework:
    mcp:
      default-adapter: spring-ai
      servers:
        github:
          adapter: spring-ai
```

또는 direct adapter:

```yaml
agent:
  framework:
    mcp:
      servers:
        github:
          adapter: direct
          url: https://example.com/mcp
```

server별 `adapter`가 global `default-adapter`를 이긴다. 둘 다 없고 direct/Spring AI 후보가
둘 이상이면 해당 server의 ToolSet 생성이 startup에서 실패한다.

사용자가 `ToolSet`을 agent에 붙이는 행위는 명시적이다. MCP server가 발견됐다는 이유만으로
모든 agent에 tool이 자동 노출되지 않는다.

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

Agent API로 노출하려면 명시적으로 변환한다.

```java
Agent supportAgent = workflows.asAgent(support);
supportAgent.run("My server is down");
```

`WorkflowRunner workflows`는 standalone 또는 framework assembly가 주입한다. runner가
execution strategy, clock, checkpoint store, codec registry를 주입받아 묶는다. immutable
`Workflow` 정의만으로 실행하거나 Agent로 변환하는 static global helper는 제공하지 않는다.
Spring/Quarkus/Jakarta에서 `Workflow`, `WorkflowRunner`, `Agent`는 injectable component일 뿐
endpoint가 아니다.

## 10. Harness

Harness는 별도 runtime이 아니라 Agent decorator facade다.

```java
Agent codingAgent =
    Harness.decorate(baseAgent)
        .todo()
        .approvals()
        .build();
```

skills 같은 별도 optional module은 자신의 decorator/factory를 제공한다.

```java
Agent skilledAgent =
    Skills.decorate(codingAgent)
        .directory(skillDirectory)
        .build();
```

Java optional jar는 이미 컴파일된 `AgentBuilder`에 메서드를 추가할 수 없다. 따라서 base
builder에 `.harness()`, `.skills()`, `.codeAct()`를 미리 늘어놓지 않는다. harness, skills,
background agents, shell, CodeAct module은 `AgentDecorator`, `ToolSet`, 또는 module-owned
builder/factory를 제공하고 명시적으로 Agent에 적용한다.

## 11. Progressive disclosure levels

| Level | 대상 | 사용하는 API |
| --- | --- | --- |
| 0 | 단일 agent | `AgentFactory`, `AgentBuilder`, `Agent.run` |
| 1 | tools/MCP | `Tool`, `ToolSet`, builder `.tools(...)` |
| 2 | session/interceptor | named builder customizer와 typed options |
| 3 | workflow/harness | injected `WorkflowRunner`, optional decorator/factory |
| 4 | adapter 개발 | public SPI와 contract testkit |

각 level은 이전 level의 API를 바꾸지 않는다. 간단한 예제에서 SPI type이나 engine internal이
등장하면 developer experience regression이다.

## 12. Dependency experience

provider × framework 조합별 artifact를 모두 만들지 않는다.

| 환경 | 최소 dependencies |
| --- | --- |
| standalone direct provider | `agent-framework-standalone` + provider adapter |
| Spring AI | Agent Framework Spring AI starter + Spring AI model starter |
| Spring direct provider | Agent Framework Spring Boot starter + provider adapter |
| Quarkus | Agent Framework Quarkus extension + provider adapter |
| Jakarta EE | Agent Framework Jakarta integration + provider adapter |

모든 dependency는 BOM으로 정렬한다. protocol endpoint는 필요한 protocol starter만 추가한다.

## 13. Developer experience acceptance tests

- starter + single model → `AgentFactory` 사용 가능
- starter + single model + no user Agent → default Agent injection 가능
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
