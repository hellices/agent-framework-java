# Unified Run Pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace separate ordinary/streaming model and tool execution paths with one update-oriented run pipeline and one model invocation contract.

**Architecture:** Every model invocation returns a Reactive Streams publisher of updates. A per-run `RunExecution` and `RunStateMachine` own one subscription, tool iteration, accumulation, lifecycle completion, cancellation, and terminal outcome; ordinary and streaming handles are projections over that execution.

**Tech Stack:** Java 17, `Flow.Publisher`, `CompletionStage`, Reactive Streams rules, JUnit 5, AssertJ.

## Global Constraints

- Requires typed public contracts and Agent/Engine separation plans.
- No engine or adapter blocks, calls `join()`, or creates an executor.
- `onSubscribe` always precedes `onNext`, `onError`, and `onComplete`.
- One model invocation has one upstream subscription.
- Streaming runs are cold and unicast; the first subscriber starts execution.
- A second subscriber receives `onSubscribe` followed by `onError(IllegalStateException)`.
- `response()` alone does not start a streaming run.
- Update `onComplete` occurs only after context completion and successful session persistence.
- Ordinary and streaming outcomes preserve the same root failure.

---

### Task 1: Single ModelClient invocation contract

**Files:**
- Modify: `agent-framework-api/src/main/java/io/github/hellices/agentframework/spi/model/ModelClient.java`
- Delete: `agent-framework-api/src/main/java/io/github/hellices/agentframework/spi/model/StreamingModelClient.java`
- Delete: `agent-framework-api/src/main/java/io/github/hellices/agentframework/spi/model/ContinuationModelClient.java`
- Delete: `agent-framework-api/src/main/java/io/github/hellices/agentframework/spi/model/StreamingContinuationModelClient.java`
- Create: `agent-framework-testkit/src/main/java/io/github/hellices/agentframework/testkit/model/ModelPublishers.java`
- Test: `agent-framework-api/src/test/java/io/github/hellices/agentframework/spi/model/ModelClientContractTest.java`
- Test: `agent-framework-testkit/src/test/java/io/github/hellices/agentframework/testkit/model/ModelPublishersTest.java`

**Interfaces:**
- Produces:

```java
public interface ModelClient {
  Flow.Publisher<ModelResponseUpdate> execute(ModelRequest request);
}

public final class ModelPublishers {
  public static Flow.Publisher<ModelResponseUpdate> just(ModelResponseUpdate update);
  public static Flow.Publisher<ModelResponseUpdate> failed(Throwable failure);
  public static Flow.Publisher<ModelResponseUpdate> fromStage(
      CompletionStage<ModelResponseUpdate> stage, CancellationSignal cancellation);
}
```

- [ ] **Step 1: Write failing Reactive Streams contract tests**

Assert one-shot update order, failure order, positive-demand enforcement, cancellation propagation,
and exactly one terminal signal.

- [ ] **Step 2: Verify RED**

```bash
./gradlew :agent-framework-api:test --tests '*ModelClientContractTest'
./gradlew :agent-framework-testkit:test --tests '*ModelPublishersTest'
```

- [ ] **Step 3: Replace the capability cross-product**

Move continuation state into `ModelRequest`. Implement publishers whose subscription stores demand
atomically and emits only after a positive request. `fromStage` must install `onSubscribe` before
registering completion delivery.

- [ ] **Step 4: Verify GREEN and commit**

```bash
./gradlew :agent-framework-api:test --tests '*ModelClientContractTest'
./gradlew :agent-framework-testkit:test --tests '*ModelPublishersTest'
git add agent-framework-api agent-framework-testkit
git commit -m "api: unify model invocation contract"
```

---

### Task 2: Cold unicast streaming run

**Files:**
- Modify: `agent-framework-api/src/main/java/io/github/hellices/agentframework/api/agent/AgentStreamingRun.java`
- Test: `agent-framework-api/src/test/java/io/github/hellices/agentframework/api/agent/AgentStreamingRunTest.java`

**Interfaces:**
- Consumes: one cold source publisher and terminal response accumulator.
- Produces: a single-subscriber streaming handle.

- [ ] **Step 1: Write failing start/subscription tests**

```java
AgentStreamingRun<AgentResponseUpdate> run = streamingRun(source);
run.response(); // observation only
assertThat(source.subscriptionCount()).isZero();

consume(run.updates());
assertThat(source.subscriptionCount()).isOne();

assertSecondSubscriptionFails(run.updates(), IllegalStateException.class);
assertThat(source.subscriptionCount()).isOne();
```

- [ ] **Step 2: Verify RED**

```bash
./gradlew :agent-framework-api:test --tests '*AgentStreamingRunTest'
```

- [ ] **Step 3: Add the unicast gate**

Use `AtomicBoolean subscribed`. The rejected subscriber receives an empty subscription followed by
`onError`. Do not subscribe the source from `response()`, mapping operations, or construction.

- [ ] **Step 4: Verify backpressure/cancellation and commit**

```bash
./gradlew :agent-framework-api:test --tests '*AgentStreamingRunTest'
git add agent-framework-api
git commit -m "api: make streaming runs cold and unicast"
```

---

### Task 3: RunExecution and explicit state machine

**Files:**
- Create: `agent-framework-engine/src/main/java/io/github/hellices/agentframework/engine/internal/run/RunExecution.java`
- Create: `agent-framework-engine/src/main/java/io/github/hellices/agentframework/engine/internal/run/RunState.java`
- Create: `agent-framework-engine/src/main/java/io/github/hellices/agentframework/engine/internal/run/RunPhase.java`
- Create: `agent-framework-engine/src/main/java/io/github/hellices/agentframework/engine/internal/run/RunStateMachine.java`
- Create: `agent-framework-engine/src/test/java/io/github/hellices/agentframework/engine/internal/run/RunStateMachineTest.java`

**Interfaces:**
- Produces:

```java
enum RunPhase {
  VALIDATE, LOAD_SESSION, PREPARE_CONTEXT, PREPARE_MODEL_REQUEST, CALL_MODEL,
  ACCUMULATE_MODEL_UPDATES, PLAN_TOOL_ACTION, EXECUTE_TOOL_BATCH,
  FINALIZE_RESPONSE, COMPLETE_CONTEXT, PERSIST_SESSION, TERMINATED
}

final class RunStateMachine {
  RunPhase phase();
  void transitionTo(RunPhase next);
  void fail(Throwable failure);
  void cancel();
}
```

- [ ] **Step 1: Write failing transition tests**

Cover every valid edge, rejection of skipped/reversed transitions, one terminal outcome, failure
cause identity, and cancellation distinct from failure.

- [ ] **Step 2: Verify RED**

```bash
./gradlew :agent-framework-engine:test --tests '*RunStateMachineTest'
```

- [ ] **Step 3: Implement per-run state**

Keep mutable collections private to `RunState`; expose immutable snapshots. Do not put application
services in state objects.

- [ ] **Step 4: Verify GREEN and commit**

```bash
./gradlew :agent-framework-engine:test --tests '*RunStateMachineTest'
git add agent-framework-engine
git commit -m "engine: add explicit run state machine"
```

---

### Task 4: Unified RunPipeline and tool iteration

**Files:**
- Create: `agent-framework-engine/src/main/java/io/github/hellices/agentframework/engine/internal/run/RunPipeline.java`
- Modify: `agent-framework-engine/src/main/java/io/github/hellices/agentframework/engine/AgentEngine.java`
- Modify: `agent-framework-engine/src/main/java/io/github/hellices/agentframework/engine/internal/tool/ToolLoopPolicy.java`
- Reuse then delete: `agent-framework-engine/src/main/java/io/github/hellices/agentframework/engine/internal/tool/StreamingToolLoopPublisher.java`
- Reuse: `agent-framework-engine/src/main/java/io/github/hellices/agentframework/engine/internal/model/StreamingModelResponseAccumulator.java`
- Create: `agent-framework-engine/src/test/java/io/github/hellices/agentframework/engine/AgentEngineRunParityTest.java`

**Interfaces:**
- Consumes: `ModelClient.execute`, `RunStateMachine`, tool policy, session/context coordination.
- Produces one internal update publisher and terminal response stage per execution.

- [ ] **Step 1: Write failing ordinary/streaming parity tests**

Run identical scripted model sequences through `run` and `runStreaming`. Assert identical messages,
usage, finish reason, continuation state, tool calls/results, session state, and root errors.

- [ ] **Step 2: Verify RED**

```bash
./gradlew :agent-framework-engine:test --tests '*AgentEngineRunParityTest'
```

Expected: at least the configured-gate failure and lifecycle terminal-order scenarios differ.

- [ ] **Step 3: Move execution into RunPipeline**

The pipeline subscribes to each model publisher, forwards accepted updates when streaming, feeds the
same accumulator, executes tool batches through `ToolLoopPolicy`, and starts the next iteration.
Ordinary execution installs an internal subscriber requesting `Long.MAX_VALUE`; streaming forwards
downstream demand and cancellation.

- [ ] **Step 4: Remove duplicate execution**

Delete ordinary recursive `runToolLoop`, `resolveModelInvoker`, `resolveStreamingInvoker`, and
`StreamingToolLoopPublisher` after parity tests use `RunPipeline` exclusively.

- [ ] **Step 5: Verify GREEN and commit**

```bash
./gradlew :agent-framework-engine:test --tests '*AgentEngineRunParityTest' --tests '*AgentEngineTest' --tests '*AgentEngineStreamingToolLoopTest'
git add agent-framework-engine
git commit -m "engine: unify ordinary and streaming execution"
```

---

### Task 5: Terminal lifecycle and failure consistency

**Files:**
- Modify: `agent-framework-engine/src/main/java/io/github/hellices/agentframework/engine/internal/run/RunPipeline.java`
- Modify: `agent-framework-api/src/main/java/io/github/hellices/agentframework/api/agent/Agent.java`
- Modify: `agent-framework-api/src/main/java/io/github/hellices/agentframework/api/agent/AgentRun.java`
- Modify: `agent-framework-api/src/main/java/io/github/hellices/agentframework/api/agent/AgentStreamingRun.java`
- Create: `agent-framework-engine/src/test/java/io/github/hellices/agentframework/engine/AgentEngineTerminalOrderingTest.java`

**Interfaces:**
- Produces terminal order:
  `final update -> response -> context completion -> persistence -> response/session success -> onComplete`.

- [ ] **Step 1: Write failing lifecycle tests**

Use controllable stages for model, context completion, and session save. Assert the update publisher
does not complete before save, persistence failure produces stream `onError`, and response/session
stages carry the identical cause object.

- [ ] **Step 2: Verify RED**

```bash
./gradlew :agent-framework-engine:test --tests '*AgentEngineTerminalOrderingTest'
```

- [ ] **Step 3: Make RunPipeline the terminal owner**

Remove facade-level post-completion composition that can run after publisher completion. The
pipeline alone completes the response, session, and update terminal signal.

- [ ] **Step 4: Verify all cancellation boundaries**

```bash
./gradlew :agent-framework-engine:test --tests '*AgentEngineTerminalOrderingTest' --tests '*AgentEngineSessionContextTest' --tests '*AgentEngineRunParityTest'
```

Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add agent-framework-api agent-framework-engine
git commit -m "engine: align terminal lifecycle outcomes"
```

---

### Task 6: OpenAI, testkit, sample, and documentation migration

**Files:**
- Modify: `providers/agent-framework-openai/src/main/java/io/github/hellices/agentframework/openai/OpenAiChatModelClient.java`
- Modify: provider model mappers as required by typed updates
- Modify: `providers/agent-framework-openai/src/test/java/io/github/hellices/agentframework/openai/OpenAiChatModelClientTest.java`
- Modify: deterministic model clients under `agent-framework-testkit`
- Modify: sample model clients under `samples/sample-standalone`
- Modify: `README.md`
- Modify: `docs/design/requirements-design/01-core-execution.md`
- Modify: `docs/design/requirements-design/requirements-traceability-matrix.md`

**Interfaces:**
- Produces: every model provider implementing `execute(ModelRequest)`.

- [ ] **Step 1: Rewrite provider tests against the update contract**

Assert `onSubscribe` ordering, one terminal update for Chat Completions, cancellation forwarding,
failure cause preservation, and no close of borrowed clients.

- [ ] **Step 2: Verify RED**

```bash
./gradlew :providers:agent-framework-openai:test
```

- [ ] **Step 3: Implement demand-aware one-update adaptation**

Map the SDK completion stage through `ModelPublishers.fromStage`. Do not hand-write a publisher that
emits before demand or calls `onError` before `onSubscribe`.

- [ ] **Step 4: Verify the convergence slice**

```bash
./gradlew :agent-framework-api:test :agent-framework-engine:test :agent-framework-testkit:test
./gradlew :providers:agent-framework-openai:test :samples:sample-standalone:test
./gradlew policyCheck quality testJava17 testJava21 testJava25 check
```

Expected: all pass and `rg 'StreamingContinuationModelClient|StreamingModelClient|ContinuationModelClient'` finds no production references.

- [ ] **Step 5: Commit**

```bash
git add .
git commit -m "provider: migrate to unified model updates"
```

