# Session Context and Providers Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete SES-011 through SES-014 with a fresh per-run session context, namespaced provider state, configurable history policies, and deterministic load-run-save coordination.

**Architecture:** The public API owns the per-run `SessionContext`; SPI providers receive only a source-bound state view. `AgentEngine` runs providers in forward/reverse order and later composes the existing `SessionStore` and `StateCodecRegistry` around successful runs. Work is split at reviewable PR boundaries so public lifecycle semantics land before persistence policy.

**Tech Stack:** Java 17 API baseline, `CompletionStage`, Gradle 9.7, JUnit 5, AssertJ.

## Global Constraints

- Core creates no executors, schedulers, global registries, servers, or DI containers.
- A new `SessionContext` is created for every ordinary and streaming run.
- Provider state is stored only under the provider's fixed `sourceId` session-state key.
- Provider hooks run `beforeRun` in declaration order and `afterRun` in reverse order.
- History storage defaults to end-of-run atomicity; per-service-call persistence remains out of scope until SES-015.
- Service-managed history and local history are never mixed.
- Every behavior change starts with a failing targeted test and ends with module quality verification.

---

### Task 1: Per-run SessionContext lifecycle (PR checkpoint)

**Files:**
- Create: `agent-framework-api/src/main/java/io/github/hellices/agentframework/api/session/SessionContext.java`
- Modify: `agent-framework-api/src/main/java/io/github/hellices/agentframework/api/agent/AgentRunContext.java`
- Modify: `agent-framework-api/src/main/java/io/github/hellices/agentframework/api/agent/Agent.java`
- Test: `agent-framework-api/src/test/java/io/github/hellices/agentframework/api/agent/AgentLifecycleTest.java`

**Interfaces:**
- Consumes: `AgentSession`, `AgentRunRequest.messages()`, `CancellationSignal`, `AgentResponse`.
- Produces: `SessionContext.session()`, `inputMessages()`, `contextMessages()`, `metadata()`, `cancellationSignal()`, and `response()`.

- [ ] **Step 1: Write failing lifecycle tests**

Add tests proving two invocations receive distinct `SessionContext` identities, input/session metadata are copied into each context, ordinary completion fills the response slot, and streaming completion fills it only after the terminal response stage completes.

```java
AgentRun first = agent.run(AgentRunRequest.of("one"));
AgentRun second = agent.run(AgentRunRequest.of("two"));

assertThat(agent.contexts).hasSize(2);
assertThat(agent.contexts.get(0)).isNotSameAs(agent.contexts.get(1));
assertThat(first.response().toCompletableFuture().join())
    .isSameAs(agent.contexts.get(0).response().orElseThrow());
```

- [ ] **Step 2: Verify the tests fail**

Run:

```bash
./gradlew :agent-framework-api:test --tests '*AgentLifecycleTest'
```

Expected: compilation fails because `SessionContext` and `AgentRunContext.sessionContext()` do not exist.

- [ ] **Step 3: Implement the controlled context**

Implement a final class with immutable session/input/metadata/cancellation fields, an ordered context-message list, and a set-once response slot.

```java
public final class SessionContext {
  private final AgentSession session;
  private final List<Message> inputMessages;
  private final List<Message> contextMessages = new ArrayList<>();
  private final Map<String, Object> metadata;
  private final CancellationSignal cancellationSignal;
  private AgentResponse response;

  public synchronized Optional<AgentResponse> response() {
    return Optional.ofNullable(response);
  }

  public synchronized void complete(AgentResponse value) {
    if (response != null) {
      throw new IllegalStateException("session context response is already complete");
    }
    response = Objects.requireNonNull(value, "response must not be null");
  }
}
```

Keep `AgentRunContext.session()` for compatibility and add `sessionContext`. In `Agent.run` and `runStreaming`, create exactly one context, pass it to `runInternal`, and register a success-only completion callback on the returned response stage.

- [ ] **Step 4: Verify API tests and quality**

```bash
./gradlew :agent-framework-api:test --tests '*AgentLifecycleTest' :agent-framework-api:quality
```

Expected: all lifecycle tests and API quality checks pass.

- [ ] **Step 5: Commit**

```bash
git add agent-framework-api
git commit -m "api: add per-run session context"
```

---

### Task 2: Namespaced ContextProvider pipeline (PR checkpoint)

**Files:**
- Create: `agent-framework-api/src/main/java/io/github/hellices/agentframework/spi/session/ContextProvider.java`
- Create: `agent-framework-api/src/main/java/io/github/hellices/agentframework/spi/session/ProviderSessionState.java`
- Modify: `agent-framework-api/src/main/java/io/github/hellices/agentframework/api/session/SessionContext.java`
- Modify: `agent-framework-engine/src/main/java/io/github/hellices/agentframework/engine/AgentEngineBuilder.java`
- Modify: `agent-framework-engine/src/main/java/io/github/hellices/agentframework/engine/AgentEngine.java`
- Test: `agent-framework-engine/src/test/java/io/github/hellices/agentframework/engine/AgentEngineSessionContextTest.java`

**Interfaces:**
- Consumes: Task 1 `SessionContext`.
- Produces:

```java
public interface ContextProvider {
  String sourceId();
  CompletionStage<Void> beforeRun(SessionContext context, ProviderSessionState state);
  CompletionStage<Void> afterRun(SessionContext context, ProviderSessionState state);
}
```

- [ ] **Step 1: Write failing ordering and isolation tests**

Use one provider instance with two different sessions. Assert forward `beforeRun`, reverse `afterRun`, source-attributed message injection, and distinct state slots for distinct `sourceId` values.

- [ ] **Step 2: Verify the tests fail**

```bash
./gradlew :agent-framework-engine:test --tests '*AgentEngineSessionContextTest'
```

Expected: compilation fails because provider contracts and builder configuration do not exist.

- [ ] **Step 3: Implement source-bound state**

`ProviderSessionState` exposes `Optional<Object> value()`, typed `value(Class<T>)`, `set(Object)`, and `clear()`. It never exposes the parent session map or another source key. `SessionContext` creates one view per configured provider from `AgentSession.state().get(sourceId)` and produces an updated immutable `AgentSession`.

- [ ] **Step 4: Implement provider execution**

Add `contextProviders(ContextProvider...)` to `AgentEngineBuilder`. `AgentEngine` composes `beforeRun` stages in declaration order before the first model request, includes context messages before caller input, stores the final response in the context, then composes `afterRun` in reverse order. Hook failures fail the run without invoking later phases.

- [ ] **Step 5: Verify engine tests and quality**

```bash
./gradlew :agent-framework-engine:test --tests '*AgentEngineSessionContextTest' :agent-framework-engine:quality
```

Expected: provider ordering, state isolation, and error propagation tests pass.

- [ ] **Step 6: Commit**

```bash
git add agent-framework-api agent-framework-engine
git commit -m "session: add context provider pipeline"
```

---

### Task 3: HistoryProvider flags and in-memory history

**Files:**
- Create: `agent-framework-api/src/main/java/io/github/hellices/agentframework/spi/session/HistoryProvider.java`
- Create: `agent-framework-api/src/main/java/io/github/hellices/agentframework/spi/session/HistoryPolicy.java`
- Create: `agent-framework-engine/src/main/java/io/github/hellices/agentframework/engine/session/InMemoryHistoryProvider.java`
- Test: `agent-framework-engine/src/test/java/io/github/hellices/agentframework/engine/session/InMemoryHistoryProviderTest.java`

**Interfaces:**
- Consumes: `ContextProvider`, `ProviderSessionState`, `SessionContext`.
- Produces constructor-configured `loadMessages`, `storeInputs`, `storeContextMessages`, `storeContextFrom`, and `storeOutputs` behavior.

- [ ] **Step 1: Write failing selective load/store tests**

Cover input-only, output-only, all-context, one `storeContextFrom` source, and load-disabled configurations.

- [ ] **Step 2: Verify tests fail**

```bash
./gradlew :agent-framework-engine:test --tests '*InMemoryHistoryProviderTest'
```

- [ ] **Step 3: Implement immutable history policy and provider base**

Use one immutable `HistoryPolicy` value. `HistoryProvider.beforeRun` loads only when enabled; `afterRun` constructs one ordered batch from the enabled input/context/output categories.

- [ ] **Step 4: Implement session-backed in-memory history**

Store a branch-safe immutable `List<Message>` as the provider namespace value. Do not keep messages in provider fields.

- [ ] **Step 5: Verify tests and quality**

```bash
./gradlew :agent-framework-engine:test --tests '*InMemoryHistoryProviderTest' :agent-framework-engine:quality
```

- [ ] **Step 6: Commit**

```bash
git add agent-framework-api agent-framework-engine
git commit -m "session: add configurable in-memory history"
```

---

### Task 4: Session load-run-save and default history policy (PR checkpoint)

**Files:**
- Modify: `agent-framework-engine/src/main/java/io/github/hellices/agentframework/engine/AgentEngineBuilder.java`
- Modify: `agent-framework-engine/src/main/java/io/github/hellices/agentframework/engine/AgentEngine.java`
- Create: `agent-framework-engine/src/main/java/io/github/hellices/agentframework/engine/session/SessionCoordinator.java`
- Modify: `agent-framework-api/src/main/java/io/github/hellices/agentframework/spi/session/StateCodecRegistry.java`
- Test: `agent-framework-engine/src/test/java/io/github/hellices/agentframework/engine/session/SessionCoordinatorTest.java`

**Interfaces:**
- Consumes: `SessionStore`, `StateCodecRegistry`, Tasks 1-3 providers.
- Produces deterministic `load -> restore -> beforeRun -> model/tool loop -> afterRun -> snapshot -> save`.

- [ ] **Step 1: Write failing orchestration tests**

Cover absent snapshot creation, existing snapshot revision increment, provider state restart restoration, no save after model/provider failure, and last response persistence after successful completion.

- [ ] **Step 2: Write failing default-history condition tests**

Assert auto-injection only when a request has a session, no load-enabled history provider exists, and `serviceSessionId` is absent. Assert no injection for sessionless runs or service-managed conversations.

- [ ] **Step 3: Verify tests fail**

```bash
./gradlew :agent-framework-engine:test --tests '*SessionCoordinatorTest'
```

- [ ] **Step 4: Implement coordinator**

The coordinator restores through the configured registry, preserves snapshot revision/creation metadata for the run, and saves revision `previous + 1` only after all success-path provider hooks complete. It returns failed stages unchanged and never catches broad runtime failures.

- [ ] **Step 5: Implement narrow default-history injection**

Resolve configured providers once per run. Append `InMemoryHistoryProvider` only under all three SES-014 conditions, then execute the normal provider pipeline without special cases.

- [ ] **Step 6: Verify complete session surface**

```bash
./gradlew :agent-framework-api:quality :agent-framework-engine:quality
./gradlew :agent-framework-api:test :agent-framework-engine:test
./gradlew testJava17 testJava25
```

Expected: all tests pass locally; JDK 21 remains CI-only when unavailable.

- [ ] **Step 7: Commit**

```bash
git add agent-framework-api agent-framework-engine
git commit -m "session: coordinate persisted run history"
```

---

## Plan Self-Review

- SES-011 maps to Task 1.
- SES-012 maps to Task 2.
- SES-013 maps to Task 3.
- SES-014 and load-run-save persistence map to Task 4.
- SES-015 and later message-injection/file-history/memory requirements remain explicitly outside this plan.
- Every task defines concrete interfaces, failing tests, focused verification, and a PR-capable checkpoint.
