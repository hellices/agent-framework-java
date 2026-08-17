# Interceptors, Approval, and Telemetry Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add typed chain-of-responsibility seams, core-owned tool approval, and a framework-neutral telemetry port on the converged run pipeline.

**Architecture:** Four open interceptor interfaces wrap typed invocation objects while `RunPipeline` remains the execution owner. Approval is explicit content plus versioned session state. Telemetry observes semantic operations through a neutral port; OpenTelemetry SDK integration remains optional.

**Tech Stack:** Java 17, CompletionStage, Flow.Publisher, immutable invocation contexts, JUnit 5, AssertJ.

## Global Constraints

- Requires completion of context/history contributions and unified run pipeline plans.
- Interceptors do not implement DI, transactions, host authorization, or general AOP.
- Registration order is pre-order; completion order is reverse.
- Every seam supports explicit short-circuit and typed result replacement.
- Provider automatic tool execution remains disabled.
- Approval requests/responses are core content and survive session serialization.
- Mandatory iteration, approval-chain, and tool-call limits cannot be disabled by interceptors.
- Prompt, response, and tool arguments are not recorded by default.
- Core API has no OpenTelemetry or Micrometer type.

---

### Task 1: Typed interceptor contracts

**Files:**
- Create: `agent-framework-api/src/main/java/io/github/hellices/agentframework/spi/interception/AgentExecutionInterceptor.java`
- Create: `agent-framework-api/src/main/java/io/github/hellices/agentframework/spi/interception/ModelInvocationInterceptor.java`
- Create: `agent-framework-api/src/main/java/io/github/hellices/agentframework/spi/interception/ToolInvocationInterceptor.java`
- Create: `agent-framework-api/src/main/java/io/github/hellices/agentframework/spi/interception/SessionOperationInterceptor.java`
- Create typed invocation/chain interfaces in the same package
- Test: `agent-framework-api/src/test/java/io/github/hellices/agentframework/spi/interception/InterceptorContractTest.java`

**Interfaces:**
- Produces:

```java
public interface AgentExecutionInterceptor {
  CompletionStage<AgentResponse> intercept(
      AgentInvocation invocation, AgentInvocationChain next);
}

public interface ModelInvocationInterceptor {
  Flow.Publisher<ModelResponseUpdate> intercept(
      ModelInvocation invocation, ModelInvocationChain next);
}

public interface ToolInvocationInterceptor {
  CompletionStage<ToolResult> intercept(
      ToolInvocation invocation, ToolInvocationChain next);
}

public interface SessionOperationInterceptor {
  CompletionStage<SessionOperationResult> intercept(
      SessionInvocation invocation, SessionInvocationChain next);
}
```

- [ ] **Step 1: Write failing API-shape tests**

Use reflection to assert four separate interfaces, no common everything-context method, immutable
invocations, typed next/result contracts, and absence of framework types.

- [ ] **Step 2: Verify RED**

```bash
./gradlew :agent-framework-api:test --tests '*InterceptorContractTest'
```

- [ ] **Step 3: Implement focused open interfaces**

Invocation objects contain only data needed by their seam plus cancellation and typed attributes.
They do not expose mutable engine state or a generic object bag.

- [ ] **Step 4: Verify GREEN and commit**

```bash
./gradlew :agent-framework-api:test --tests '*InterceptorContractTest'
git add agent-framework-api
git commit -m "api: add typed execution interceptors"
```

---

### Task 2: Ordered interceptor chains

**Files:**
- Create: `agent-framework-engine/src/main/java/io/github/hellices/agentframework/engine/internal/interception/InterceptorRegistry.java`
- Create one chain implementation per seam in the same package
- Modify: `agent-framework-engine/src/main/java/io/github/hellices/agentframework/engine/AgentEngineBuilder.java`
- Create: `agent-framework-engine/src/test/java/io/github/hellices/agentframework/engine/AgentEngineInterceptorTest.java`

**Interfaces:**
- Consumes: four typed interceptor lists.
- Produces ordered immutable chains assembled once by `AgentEngineBuilder`.

- [ ] **Step 1: Write failing order/short-circuit tests**

```java
assertThat(events).containsExactly("A-pre", "B-pre", "handler", "B-post", "A-post");
assertThat(shortCircuitEvents).containsExactly("A-pre");
```

Also assert unsupported seam registration cannot compile through the typed builder method and a null
stage/result fails the run explicitly.

- [ ] **Step 2: Verify RED**

```bash
./gradlew :agent-framework-engine:test --tests '*AgentEngineInterceptorTest'
```

- [ ] **Step 3: Implement immutable chain composition**

Compose from the last registration toward the first so the first registered interceptor is
outermost. Build chains once; do not sort or reverse again at run time.

- [ ] **Step 4: Verify GREEN and commit**

```bash
./gradlew :agent-framework-engine:test --tests '*AgentEngineInterceptorTest'
git add agent-framework-engine
git commit -m "engine: compose typed interceptor chains"
```

---

### Task 3: Wire interceptors into the unified pipeline

**Files:**
- Modify: `agent-framework-engine/src/main/java/io/github/hellices/agentframework/engine/internal/run/RunPipeline.java`
- Modify: engine model/tool/session coordinator classes
- Modify: `agent-framework-engine/src/test/java/io/github/hellices/agentframework/engine/AgentEngineInterceptorTest.java`
- Modify: `agent-framework-engine/src/test/java/io/github/hellices/agentframework/engine/AgentEngineRunParityTest.java`

**Interfaces:**
- Produces one invocation of the correct chain for each agent run, model iteration, tool call, and
  session load/save.

- [ ] **Step 1: Write failing seam-count tests**

Assert one agent interception per run, one model interception per iteration, one tool interception
per call, session interception around both load and save, and identical ordinary/streaming order.

- [ ] **Step 2: Verify RED**

```bash
./gradlew :agent-framework-engine:test --tests '*AgentEngineInterceptorTest' --tests '*AgentEngineRunParityTest'
```

- [ ] **Step 3: Route pipeline operations through chains**

Keep state transitions outside interceptor implementations. A replaced model/tool result re-enters
the normal accumulator and policy path.

- [ ] **Step 4: Verify GREEN and commit**

```bash
./gradlew :agent-framework-engine:test --tests '*AgentEngineInterceptorTest' --tests '*AgentEngineRunParityTest'
git add agent-framework-engine
git commit -m "engine: apply execution interceptors"
```

---

### Task 4: Approval content and persisted state

**Files:**
- Create: `agent-framework-api/src/main/java/io/github/hellices/agentframework/api/message/ToolApprovalRequestContent.java`
- Create: `agent-framework-api/src/main/java/io/github/hellices/agentframework/api/message/ToolApprovalResponseContent.java`
- Create: `agent-framework-api/src/main/java/io/github/hellices/agentframework/api/tool/ToolApprovalPolicy.java`
- Create: `agent-framework-api/src/main/java/io/github/hellices/agentframework/api/tool/ToolApprovalDecision.java`
- Create approval state codec under `agent-framework-engine/src/main/java/io/github/hellices/agentframework/engine/internal/tool/`
- Test: `agent-framework-api/src/test/java/io/github/hellices/agentframework/api/tool/ToolApprovalContractTest.java`
- Test: `agent-framework-engine/src/test/java/io/github/hellices/agentframework/engine/AgentEngineToolApprovalTest.java`

**Interfaces:**
- Produces:

```java
public interface ToolApprovalPolicy {
  ToolApprovalDecision evaluate(ToolApprovalContext context);
}

public enum ToolApprovalDecision { REQUIRE_APPROVAL, APPROVE, DENY }
```

Approval request content carries request id, tool call id/name, exact `JsonObject` arguments, and
host-boundary identity. Response content carries request id and approve/deny.

- [ ] **Step 1: Write failing serialization and binding tests**

Assert round-trip stability, response-to-original-request matching, exact argument preservation,
wrong request rejection, and denial normalization.

- [ ] **Step 2: Verify RED**

```bash
./gradlew :agent-framework-api:test --tests '*ToolApprovalContractTest'
./gradlew :agent-framework-engine:test --tests '*AgentEngineToolApprovalTest'
```

- [ ] **Step 3: Implement content, policy, and versioned state codec**

Store queue state under a reserved framework state key. Register its codec explicitly with the
engine-owned registry; do not use class names or native serialization.

- [ ] **Step 4: Verify GREEN and commit**

```bash
./gradlew :agent-framework-api:test --tests '*ToolApprovalContractTest'
./gradlew :agent-framework-engine:test --tests '*AgentEngineToolApprovalTest'
git add agent-framework-api agent-framework-engine
git commit -m "tool: add persisted approval contracts"
```

---

### Task 5: Approval state machine in the tool loop

**Files:**
- Modify: `agent-framework-engine/src/main/java/io/github/hellices/agentframework/engine/internal/run/RunPhase.java`
- Modify: `agent-framework-engine/src/main/java/io/github/hellices/agentframework/engine/internal/run/RunPipeline.java`
- Modify: `agent-framework-engine/src/main/java/io/github/hellices/agentframework/engine/internal/tool/ToolLoopPolicy.java`
- Modify: `agent-framework-engine/src/test/java/io/github/hellices/agentframework/engine/AgentEngineToolApprovalTest.java`
- Modify: `agent-framework-engine/src/test/java/io/github/hellices/agentframework/engine/AgentEngineRunParityTest.java`

**Interfaces:**
- Adds `RESOLVE_APPROVAL` and `WAIT_APPROVAL` state transitions.

- [ ] **Step 1: Write failing approval-flow tests**

Cover request emission, no tool execution while pending, exact response resume, denial synthetic
result, FIFO queue processing, standing approval bound to exact arguments/host, and maximum automatic
approval-chain length.

- [ ] **Step 2: Verify RED**

```bash
./gradlew :agent-framework-engine:test --tests '*AgentEngineToolApprovalTest'
```

- [ ] **Step 3: Implement approval before tool execution**

Resolve pending responses before the first model call. Persist an unanswered request and terminate
successfully with approval content. On resume, execute or deny only the queue head, append its tool
result, then advance.

- [ ] **Step 4: Verify ordinary/streaming parity**

```bash
./gradlew :agent-framework-engine:test --tests '*AgentEngineToolApprovalTest' --tests '*AgentEngineRunParityTest'
```

- [ ] **Step 5: Commit**

```bash
git add agent-framework-engine
git commit -m "engine: enforce tool approval state machine"
```

---

### Task 6: Framework-neutral telemetry and optional OTel adapter

**Files:**
- Create: `agent-framework-api/src/main/java/io/github/hellices/agentframework/spi/telemetry/TelemetrySink.java`
- Create semantic operation/event values in the same package
- Modify: `agent-framework-engine/src/main/java/io/github/hellices/agentframework/engine/internal/run/RunPipeline.java`
- Create: `integrations/agent-framework-otel/build.gradle.kts`
- Create: `integrations/agent-framework-otel/src/main/java/io/github/hellices/agentframework/otel/OpenTelemetrySink.java`
- Create: `integrations/agent-framework-otel/src/test/java/io/github/hellices/agentframework/otel/OpenTelemetrySinkTest.java`
- Modify: `settings.gradle.kts`
- Modify: `agent-framework-bom/build.gradle.kts`
- Modify: `gradle/libs.versions.toml`
- Modify: `docs/design/module-composition.md`
- Modify: `build-tools/harness-policy/src/test/java/io/github/hellices/agentframework/build/harness/ModuleCompositionPolicyTest.java`
- Test: `agent-framework-engine/src/test/java/io/github/hellices/agentframework/engine/AgentEngineTelemetryTest.java`

**Interfaces:**
- Produces semantic operations for agent run, model call, tool call, and session operation.

```java
public interface TelemetrySink {
  TelemetryOperation start(TelemetryStart start);
}

public interface TelemetryOperation extends AutoCloseable {
  void event(TelemetryEvent event);
  void fail(Throwable failure);
  void close();
}
```

- [ ] **Step 1: Write failing sensitive-data and nesting tests**

Assert operation nesting, failure identity, tool-call count metadata, and absence of prompt body,
model output, tool arguments, and tool results by default.

- [ ] **Step 2: Verify RED**

```bash
./gradlew :agent-framework-engine:test --tests '*AgentEngineTelemetryTest'
```

- [ ] **Step 3: Instrument semantic operations**

Use a no-op sink by default. The engine emits neutral values only. The OTel integration maps those
values to current GenAI semantic-convention attributes without importing OTel into API/engine.

- [ ] **Step 4: Verify the completed convergence**

```bash
./gradlew :agent-framework-api:test :agent-framework-engine:test
./gradlew :integrations:agent-framework-mcp:test :providers:agent-framework-openai:test
./gradlew policyCheck quality testJava17 testJava21 testJava25 check
```

Expected: all pass; telemetry tests prove sensitive values remain absent by default.

- [ ] **Step 5: Update requirements and commit**

Update `TOOL-016..021`, `INT-001..013`, and relevant `OPS` traceability rows only where acceptance
criteria are now executable.

```bash
git add .
git commit -m "feat: add approval and telemetry policies"
```
