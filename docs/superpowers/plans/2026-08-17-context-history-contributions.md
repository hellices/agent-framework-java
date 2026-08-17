# Context and History Contributions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace shared SessionContext mutation with immutable run contributions, distinguish stateless from stateful providers, and expose history as an open composition-based SPI.

**Architecture:** Context providers return ordered `RunContribution` values. Only `StatefulContextProvider` receives a namespaced state view. History storage is an interface; `PolicyDrivenHistoryProvider` composes storage with the existing immutable policy.

**Tech Stack:** Java 17, CompletionStage, immutable builders, open interfaces, JUnit 5, AssertJ.

## Global Constraints

- Requires completion of the unified run pipeline plan.
- Providers never mutate core-owned message, tool, instruction, or option collections.
- Stateless providers reserve no session namespace.
- Duplicate state keys and duplicate contributed tool names fail before model I/O.
- Existing load-before-run, reverse-completion, save-after-success, attribution, and history-policy semantics remain.
- Provider hooks never block or return null stages/contributions.

---

### Task 1: Immutable RunContribution

**Files:**
- Create: `agent-framework-api/src/main/java/io/github/hellices/agentframework/api/agent/RunContribution.java`
- Test: `agent-framework-api/src/test/java/io/github/hellices/agentframework/api/agent/RunContributionTest.java`

**Interfaces:**
- Produces:

```java
public final class RunContribution {
  public static Builder builder();
  public List<Message> messages();
  public List<String> instructionAdditions();
  public List<ToolDefinition> tools();
  public ModelRequestOptions modelOptions();
  public Builder toBuilder();
}
```

- [ ] **Step 1: Write failing value and merge tests**

Assert immutable order, duplicate tool-name rejection, typed option merging, empty contribution, and
`toBuilder()` equality.

- [ ] **Step 2: Verify RED**

```bash
./gradlew :agent-framework-api:test --tests '*RunContributionTest'
```

- [ ] **Step 3: Implement contribution and explicit merger**

Create package-private engine `RunContributionMerger` only when pipeline integration starts; keep the
API value free of engine policy.

- [ ] **Step 4: Verify GREEN and commit**

```bash
./gradlew :agent-framework-api:test --tests '*RunContributionTest'
git add agent-framework-api
git commit -m "api: add immutable run contributions"
```

---

### Task 2: Contribution-based provider interfaces

**Files:**
- Modify: `agent-framework-api/src/main/java/io/github/hellices/agentframework/spi/session/ContextProvider.java`
- Create: `agent-framework-api/src/main/java/io/github/hellices/agentframework/spi/session/StatefulContextProvider.java`
- Modify: `agent-framework-api/src/main/java/io/github/hellices/agentframework/spi/session/ProviderSessionState.java`
- Test: `agent-framework-api/src/test/java/io/github/hellices/agentframework/spi/session/ContextProviderContractTest.java`

**Interfaces:**
- Produces:

```java
public interface ContextProvider {
  CompletionStage<RunContribution> prepare(SessionContext context);
  CompletionStage<Void> complete(SessionContext context);
}

public interface StatefulContextProvider<S> extends ContextProvider {
  SessionStateKey<S> stateKey();
  CompletionStage<RunContribution> prepare(
      SessionContext context, ProviderSessionState<S> state);
  CompletionStage<Void> complete(
      SessionContext context, ProviderSessionState<S> state);
}
```

Default bridge methods on `StatefulContextProvider` delegate only when invoked by the engine with a
state view; direct stateless invocation is rejected to avoid silently dropping state.

- [ ] **Step 1: Write failing capability tests**

Assert stateless providers have no key, stateful blank keys fail assembly, hook stages/contributions
cannot be null, and completion sees the final response.

- [ ] **Step 2: Verify RED**

```bash
./gradlew :agent-framework-api:test --tests '*ContextProviderContractTest'
```

- [ ] **Step 3: Implement open interfaces**

Remove `sourceId()` from stateless `ContextProvider`. Keep stable-id and duplicate validation on the
engine binding for `StatefulContextProvider<?>`.

- [ ] **Step 4: Verify GREEN and commit**

```bash
./gradlew :agent-framework-api:test --tests '*ContextProviderContractTest'
git add agent-framework-api
git commit -m "api: separate stateful context providers"
```

---

### Task 3: Engine contribution lifecycle

**Files:**
- Create: `agent-framework-engine/src/main/java/io/github/hellices/agentframework/engine/internal/context/RunContributionMerger.java`
- Create: `agent-framework-engine/src/main/java/io/github/hellices/agentframework/engine/internal/context/ContextProviderPipeline.java`
- Modify: `agent-framework-engine/src/main/java/io/github/hellices/agentframework/engine/internal/run/RunPipeline.java`
- Modify: `agent-framework-engine/src/test/java/io/github/hellices/agentframework/engine/AgentEngineSessionContextTest.java`

**Interfaces:**
- Consumes: ordered providers and `RunContribution`.
- Produces: effective messages, instructions, tools, and options before each model iteration.

- [ ] **Step 1: Write failing lifecycle tests**

Assert forward prepare order, reverse complete order, contribution merge order, duplicate tool
failure before model invocation, no state entry for stateless providers, state isolation for
stateful providers, and cancellation between hooks.

- [ ] **Step 2: Verify RED**

```bash
./gradlew :agent-framework-engine:test --tests '*AgentEngineSessionContextTest'
```

- [ ] **Step 3: Integrate immutable contributions**

The pipeline accumulates contributions privately and creates a new `ModelRequest` per iteration.
Providers do not receive mutable request collections.

- [ ] **Step 4: Verify GREEN and commit**

```bash
./gradlew :agent-framework-engine:test --tests '*AgentEngineSessionContextTest' --tests '*AgentEngineRunParityTest'
git add agent-framework-engine
git commit -m "engine: apply context contributions"
```

---

### Task 4: Open HistoryProvider and policy composition

**Files:**
- Modify: `agent-framework-api/src/main/java/io/github/hellices/agentframework/spi/session/HistoryProvider.java`
- Create: `agent-framework-engine/src/main/java/io/github/hellices/agentframework/engine/session/PolicyDrivenHistoryProvider.java`
- Modify: `agent-framework-engine/src/main/java/io/github/hellices/agentframework/engine/session/InMemoryHistoryProvider.java`
- Modify: `agent-framework-engine/src/test/java/io/github/hellices/agentframework/engine/session/InMemoryHistoryProviderTest.java`

**Interfaces:**
- Produces:

```java
public interface HistoryProvider<S> extends StatefulContextProvider<S> {
  HistoryPolicy policy();
  CompletionStage<List<Message>> load(
      SessionContext context, ProviderSessionState<S> state);
  CompletionStage<Void> append(
      SessionContext context, ProviderSessionState<S> state, List<Message> messages);
}

public abstract class PolicyDrivenHistoryProvider<S> implements HistoryProvider<S> {
  // final prepare/complete implement existing HistoryPolicy semantics
}
```

- [ ] **Step 1: Add interface substitution tests**

Implement a test-local `HistoryProvider` without extending an engine class. Re-run attribution,
selection, audit sink, evaluation sink, and cross-history duplication scenarios.

- [ ] **Step 2: Verify RED**

```bash
./gradlew :agent-framework-engine:test --tests '*InMemoryHistoryProviderTest'
```

- [ ] **Step 3: Extract policy behavior by composition**

Move the current abstract API implementation into engine `PolicyDrivenHistoryProvider`. Rename
`getMessages/saveMessages` to `load/append` consistently and preserve exact batch ordering.

- [ ] **Step 4: Verify GREEN and commit**

```bash
./gradlew :agent-framework-engine:test --tests '*InMemoryHistoryProviderTest' --tests '*SessionSnapshotStoreTest'
git add agent-framework-api agent-framework-engine
git commit -m "session: open history provider SPI"
```

---

### Task 5: Default and service-managed history behavior

**Files:**
- Modify: `agent-framework-engine/src/main/java/io/github/hellices/agentframework/engine/AgentEngine.java`
- Modify: `agent-framework-engine/src/main/java/io/github/hellices/agentframework/engine/internal/context/ContextProviderPipeline.java`
- Modify: `agent-framework-engine/src/test/java/io/github/hellices/agentframework/engine/AgentEngineSessionContextTest.java`
- Modify: `agent-framework-engine/src/test/java/io/github/hellices/agentframework/engine/session/InMemoryHistoryProviderTest.java`
- Modify: canonical requirements/design/traceability documents for `SES-012..014`

**Interfaces:**
- Consumes: model service-history capability and per-agent provider list.

- [ ] **Step 1: Write conflict-resolution tests**

Cover local session default history, configured loading history suppression of default history,
store-only audit provider coexistence, service-managed session suppression, and stable default state
key collision rejection.

- [ ] **Step 2: Verify RED**

```bash
./gradlew :agent-framework-engine:test --tests '*AgentEngineSessionContextTest' --tests '*InMemoryHistoryProviderTest'
```

- [ ] **Step 3: Implement one assembly-time resolution policy**

Resolve providers once when binding an agent; do not recompute a mutable list after a run. The run
may skip a resolved local history provider when its effective session is service-managed.

- [ ] **Step 4: Verify the whole slice**

```bash
./gradlew :agent-framework-api:test :agent-framework-engine:test
./gradlew policyCheck quality testJava17 testJava21 testJava25 check
```

- [ ] **Step 5: Commit**

```bash
git add .
git commit -m "session: converge context and history lifecycle"
```
