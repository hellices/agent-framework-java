# Agent and Engine Separation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Separate immutable agent definition, per-agent runtime binding, user-facing Agent facade, and model-independent AgentEngine application service.

**Architecture:** `AgentDefinition` contains declarative behavior, `AgentRuntime` contains executable ports, and package-private `BoundAgent` delegates runs to a shared `AgentEngine`. `AgentFactory` and a dedicated internal agent builder assemble the two without reusing `AgentEngineBuilder`.

**Tech Stack:** Java 17, immutable final classes, abstract facade plus delegation, CompletionStage, Flow.Publisher, JUnit 5, AssertJ.

## Global Constraints

- Requires completion of `2026-08-17-typed-public-contracts.md`.
- `AgentEngine` is final, thread-safe, model-independent, and does not extend `Agent`.
- `AgentDefinition` contains no model client, tool handler, session store, interceptor instance, or provider SDK object.
- `AgentRuntime` contains only public ports and immutable ordered collections.
- `AgentEngineBuilder` builds shared engine services; it never doubles as `AgentBuilder`.
- The default `Agent` implementation is package-private.
- Existing `agent.run(...)` application ergonomics remain.

---

### Task 1: Immutable AgentDefinition

**Files:**
- Create: `agent-framework-api/src/main/java/io/github/hellices/agentframework/api/agent/AgentDefinition.java`
- Test: `agent-framework-api/src/test/java/io/github/hellices/agentframework/api/agent/AgentDefinitionTest.java`

**Interfaces:**
- Produces:

```java
public final class AgentDefinition {
  public static Builder builder();
  public String id();
  public String name();
  public String description();
  public String instructions();
  public List<ToolDefinition> tools();
  public AgentRunOptions defaultRunOptions();
  public ContextAttributes attributes();
  public Builder toBuilder();
}
```

- [ ] **Step 1: Write failing immutability tests**

Assert generated id behavior, explicit blank rejection, list immutability, duplicate tool rejection,
`toBuilder()` equality, and absence of runtime collaborator fields.

- [ ] **Step 2: Verify RED**

```bash
./gradlew :agent-framework-api:test --tests '*AgentDefinitionTest'
```

Expected: compilation fails because `AgentDefinition` does not exist.

- [ ] **Step 3: Implement the final class and builder**

Use constructor validation and defensive copies. Treat instructions as an empty string only through
an explicit builder default; reject an explicit Java `null`.

- [ ] **Step 4: Verify GREEN and commit**

```bash
./gradlew :agent-framework-api:test --tests '*AgentDefinitionTest'
git add agent-framework-api
git commit -m "api: add immutable agent definition"
```

---

### Task 2: Immutable AgentRuntime and tool bindings

**Files:**
- Create: `agent-framework-api/src/main/java/io/github/hellices/agentframework/api/agent/AgentRuntime.java`
- Create: `agent-framework-api/src/main/java/io/github/hellices/agentframework/api/tool/ToolBinding.java`
- Test: `agent-framework-api/src/test/java/io/github/hellices/agentframework/api/agent/AgentRuntimeTest.java`

**Interfaces:**
- Consumes: `AgentDefinition`, `ModelClient`, `ContextProvider`, `ToolHandler`.
- Produces:

```java
public final class ToolBinding {
  public static ToolBinding of(String toolName, ToolHandler handler);
  public String toolName();
  public ToolHandler handler();
}

public final class AgentRuntime {
  public static Builder builder();
  public ModelClient modelClient();
  public List<ToolBinding> toolBindings();
  public List<ContextProvider> contextProviders();
  public ContextAttributes attributes();
  public void validate(AgentDefinition definition);
}
```

- [ ] **Step 1: Write failing binding-validation tests**

Cover missing handler, extra handler, duplicate binding, ordered providers, immutable collections,
and successful declaration-to-handler matching.

- [ ] **Step 2: Verify RED**

```bash
./gradlew :agent-framework-api:test --tests '*AgentRuntimeTest'
```

- [ ] **Step 3: Implement immutable runtime assembly**

Validation compares exact declared and bound tool-name sets. Do not place `SessionStore` in
`AgentRuntime`; persistence infrastructure belongs to the shared engine.

- [ ] **Step 4: Verify GREEN and commit**

```bash
./gradlew :agent-framework-api:test --tests '*AgentRuntimeTest'
git add agent-framework-api
git commit -m "api: add per-agent runtime binding"
```

---

### Task 3: Model-independent AgentEngine and BoundAgent

**Files:**
- Modify: `agent-framework-engine/src/main/java/io/github/hellices/agentframework/engine/AgentEngine.java`
- Create: `agent-framework-engine/src/main/java/io/github/hellices/agentframework/engine/BoundAgent.java`
- Modify: `agent-framework-engine/src/main/java/io/github/hellices/agentframework/engine/AgentEngineBuilder.java`
- Modify: `agent-framework-engine/src/test/java/io/github/hellices/agentframework/engine/AgentEngineTest.java`
- Modify: `agent-framework-engine/src/test/java/io/github/hellices/agentframework/engine/EngineDependencyTest.java`

**Interfaces:**
- Produces:

```java
public final class AgentEngine {
  public static AgentEngineBuilder builder();
  public Agent bind(AgentDefinition definition, AgentRuntime runtime);
}

final class BoundAgent extends Agent {
  BoundAgent(AgentDefinition definition, AgentRuntime runtime, AgentEngine engine);
}
```

- [ ] **Step 1: Write failing architecture and delegation tests**

```java
assertThat(Agent.class.isAssignableFrom(AgentEngine.class)).isFalse();
Agent agent = engine.bind(definition, runtime);
assertThat(agent.id()).isEqualTo(definition.id());
assertThat(agent.run("hello").response().toCompletableFuture())
    .succeedsWithin(Duration.ofSeconds(1));
```

- [ ] **Step 2: Verify RED**

```bash
./gradlew :agent-framework-engine:test --tests '*EngineDependencyTest' --tests '*AgentEngineTest'
```

Expected: architecture assertion fails because `AgentEngine extends Agent`.

- [ ] **Step 3: Move per-agent fields into BoundAgent/runtime**

Keep engine-owned session coordination and tool policy. Add package-private engine execution methods
that receive definition, runtime, context, and request explicitly. `BoundAgent` implements the
abstract facade hooks by delegation.

- [ ] **Step 4: Verify existing run behavior**

```bash
./gradlew :agent-framework-engine:test --tests '*AgentEngineTest' --tests '*AgentEngineStreamingToolLoopTest' --tests '*AgentEngineSessionContextTest'
```

Expected: all pass with no observable run change.

- [ ] **Step 5: Commit**

```bash
git add agent-framework-engine
git commit -m "engine: separate agent binding from execution"
```

---

### Task 4: Dedicated agent builder and catalog factory

**Files:**
- Modify: `agent-framework-api/src/main/java/io/github/hellices/agentframework/api/agent/AgentBuilder.java`
- Modify: `agent-framework-api/src/main/java/io/github/hellices/agentframework/api/agent/AgentFactory.java`
- Create: `agent-framework-engine/src/main/java/io/github/hellices/agentframework/engine/DefaultAgentBuilder.java`
- Modify: `agent-framework-engine/src/main/java/io/github/hellices/agentframework/engine/CatalogAgentFactory.java`
- Modify: `agent-framework-engine/src/test/java/io/github/hellices/agentframework/engine/AgentFactoryTest.java`

**Interfaces:**
- Consumes: `AgentEngine.bind`, definition/runtime builders.
- Produces:

```java
public interface AgentFactory {
  AgentBuilder builder();
  AgentBuilder builder(String modelName);
  AgentBuilder builderWithClient(ModelClient modelClient);
  Agent bind(AgentDefinition definition, AgentRuntime runtime);
}

public interface AgentBuilder {
  AgentBuilder instructions(String instructions);
  AgentBuilder tools(FunctionTool... tools);
  AgentDefinition buildDefinition();
  Agent build();
}
```

- [ ] **Step 1: Write failing factory tests**

Verify default/named/direct model selection, a fresh builder per call, definition-only build,
convenient `FunctionTool` splitting into declaration/binding, and exact validation failures.

- [ ] **Step 2: Verify RED**

```bash
./gradlew :agent-framework-engine:test --tests '*AgentFactoryTest'
```

- [ ] **Step 3: Implement DefaultAgentBuilder**

Keep `AgentEngineBuilder` dedicated to engine services. `DefaultAgentBuilder.buildDefinition()`
returns only declarations; `build()` creates `AgentRuntime` with handlers and calls `engine.bind`.

- [ ] **Step 4: Verify GREEN and commit**

```bash
./gradlew :agent-framework-engine:test --tests '*AgentFactoryTest' --tests '*AgentEngineTest'
git add agent-framework-api agent-framework-engine
git commit -m "engine: bind definitions through agent factory"
```

---

### Task 5: Standalone and documentation migration

**Files:**
- Modify: `samples/sample-standalone/src/main/java/io/github/hellices/agentframework/samples/standalone/StandaloneAgentApplication.java`
- Modify: `samples/sample-standalone/src/test/java/io/github/hellices/agentframework/samples/standalone/StandaloneAgentApplicationTest.java`
- Modify: `README.md`
- Modify: `docs/design/foundation-design.md`
- Modify: `docs/design/requirements-design/00-clean-architecture.md`
- Modify: `docs/design/requirements-design/01-core-execution.md`
- Modify: `docs/design/requirements-design/06-developer-experience.md`
- Modify: `docs/design/requirements-design/requirements-traceability-matrix.md`

**Interfaces:**
- Produces: the same `AgentFactory -> AgentBuilder -> Agent -> AgentRun` user path over the new model.

- [ ] **Step 1: Update the sample test to assert shared-engine binding**

Create two agents from one application/factory and assert distinct definitions use the same injected
engine services while selecting independent tools/models.

- [ ] **Step 2: Verify RED**

```bash
./gradlew :samples:sample-standalone:test
```

- [ ] **Step 3: Migrate sample and canonical design**

Document `AgentDefinition`, `AgentRuntime`, `BoundAgent`, engine builder ownership, and bean scopes.
Remove every statement that says `AgentEngine implements Agent`.

- [ ] **Step 4: Verify the whole slice**

```bash
./gradlew :agent-framework-api:test :agent-framework-engine:test :samples:sample-standalone:test
./gradlew policyCheck quality testJava17 testJava21 testJava25 check
```

Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add .
git commit -m "docs: align agent and engine architecture"
```
