# Streaming Tool Loop Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement TOOL-015 so streaming and ordinary runs use identical tool iteration, result, budget, cancellation, and final-response semantics.

**Architecture:** A streaming loop publisher forwards each model update live while accumulating only the current iteration. At iteration completion it reconstructs the model response, invokes the existing core tool execution path, emits synthetic tool-result updates, and starts the next streaming model iteration with the same request/budget rules as the ordinary loop. Shared helpers own budget checks and next-request construction so streaming cannot invent a second interpretation.

**Tech Stack:** Java 17, `Flow.Publisher`, `CompletionStage`, existing `AgentStreamingRun` finalizer, JUnit 5, AssertJ.

## Global Constraints

- Core creates no executors, schedulers, global registries, or blocking worker pools.
- Model updates remain live; the implementation must not buffer a complete model call before forwarding its updates.
- Downstream demand and cancellation are honored across model, tool, and synthetic-result phases.
- Tool calls execute through the same `executeToolCalls` path as ordinary runs.
- `maxIterations`, tool disabling, result ordering, tool failure normalization, and cancellation checks are shared with ordinary execution.
- Consuming the stream to completion reconstructs the same ordered messages, tool results, usage, finish reason, and continuation metadata as an equivalent ordinary run.
- Approval behavior remains out of scope until TOOL-016.

---

### Task 1: Streaming tool iteration and final response

**Files:**
- Create: `agent-framework-engine/src/main/java/io/github/hellices/agentframework/engine/internal/tool/StreamingToolLoopPublisher.java`
- Create: `agent-framework-engine/src/main/java/io/github/hellices/agentframework/engine/internal/model/StreamingModelResponseAccumulator.java`
- Modify: `agent-framework-engine/src/main/java/io/github/hellices/agentframework/engine/AgentEngine.java`
- Test: `agent-framework-engine/src/test/java/io/github/hellices/agentframework/engine/AgentEngineStreamingToolLoopTest.java`
- Modify: `docs/design/requirements-design/requirements-traceability-matrix.md`

**Interfaces:**
- Consumes: `StreamingModelClient`, `FunctionTool`, `AgentResponse.fromUpdates`, existing ordinary `executeToolCalls` behavior.
- Produces: one `Flow.Publisher<AgentResponseUpdate>` whose terminal response is assembled by `AgentStreamingRun.fromUpdates`.

- [ ] **Step 1: Write failing parity tests**

Add deterministic clients that produce one tool-call iteration followed by a final answer. Assert:

```java
AgentResponse ordinary = ordinaryAgent.run(request).response().toCompletableFuture().join();
AgentStreamingRun<AgentResponseUpdate> streamingRun = streamingAgent.runStreaming(request);
List<AgentResponseUpdate> updates = consume(streamingRun.updates());
AgentResponse streaming = streamingRun.response().toCompletableFuture().join();

assertThat(streaming.messages()).isEqualTo(ordinary.messages());
assertThat(streaming.usage()).isEqualTo(ordinary.usage());
assertThat(updates).anySatisfy(update -> assertThat(update.messages())
    .anySatisfy(message -> assertThat(message.content())
        .anyMatch(ToolResultContent.class::isInstance)));
```

Cover split tool-call content across updates, multiple calls preserving input order, tool failure, empty final updates, and provider context/session persistence around the stream.

- [ ] **Step 2: Verify RED**

```bash
./gradlew :agent-framework-engine:test --tests '*AgentEngineStreamingToolLoopTest'
```

Expected: streaming with configured tools throws `UnsupportedOperationException`.

- [ ] **Step 3: Extract shared loop decisions**

Refactor `AgentEngine` so ordinary and streaming paths call the same helpers for:

```java
boolean toolsEnabledForIteration(int iteration);
void validateToolContinuation(ModelResponse response);
ModelRequest nextToolRequest(
    ModelRequest current, List<Message> responseMessages,
    Message toolResultMessage, int iteration);
```

Do not change ordinary observable behavior; run existing `AgentEngineTest` after extraction.

- [ ] **Step 4: Implement iteration accumulation**

`StreamingModelResponseAccumulator` maps each `ModelResponseUpdate` with the outer agent/response identity, records the mapped update, and reconstructs the iteration response using `AgentResponse.fromUpdates`. It rejects mixed response identities and empty model streams with explicit failures.

- [ ] **Step 5: Implement the streaming publisher**

The publisher owns one subscriber, downstream demand, current upstream subscription, iteration number, accumulated messages/usage, and cancellation state. It:

1. Subscribes to the current streaming model publisher.
2. Forwards model updates as demand permits while accumulating them.
3. On iteration completion, reconstructs tool calls.
4. If there are no calls, terminates normally.
5. Otherwise executes the existing tool path, emits one synthetic tool-result update per ordered result, builds the shared next request, and subscribes the next iteration.

No tool execution or next model call begins after cancellation. Any failure sends one `onError`; normal completion sends one `onComplete`.

- [ ] **Step 6: Add budget and cancellation tests**

Test identical `maxIterations` behavior, tools disabled on the last permitted request, cancellation while model streaming, cancellation while a tool stage is pending, invalid downstream demand, and downstream cancel before a queued synthetic result.

- [ ] **Step 7: Verify all engine behavior**

```bash
./gradlew :agent-framework-engine:test --tests '*AgentEngineStreamingToolLoopTest' --tests '*AgentEngineTest' --tests '*AgentEngineSessionContextTest'
./gradlew :agent-framework-engine:quality
./gradlew policyCheck
./gradlew testJava17 testJava25
```

Expected: all pass; JDK 21 remains CI-only when unavailable.

- [ ] **Step 8: Update traceability and commit**

Set TOOL-015 to `implemented` with `tool contract + ordinary/streaming parity + cancellation` evidence.

```bash
git add agent-framework-engine docs/design/requirements-design/requirements-traceability-matrix.md
git commit -m "engine: add streaming function tool loop"
```

---

## Plan Self-Review

- TOOL-015 iteration, budget, final-result parity, and shared semantics are covered.
- Live streaming, backpressure, and cancellation are explicit rather than delegated to an unbounded buffer.
- Existing session/provider lifecycle is included in parity tests.
- TOOL-016 approval content and later MCP/provider adapters remain outside this plan.
