# Run Options Contracts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the approved AGT-013 per-run model-client replacement seam and AGT-014 continuation/input validation contract.

**Architecture:** `ModelClientFactory` is a provider-neutral SPI function that transforms the agent's default `ModelClient` for one run. Immutable `AgentRunOptions` carries the optional factory and opaque continuation token; `AgentRunRequest` rejects ambiguous continuation plus new messages at construction time.

**Tech Stack:** Java 17 public API, JUnit 5, AssertJ, Gradle Kotlin DSL.

## Global Constraints

- Core APIs expose no provider SDK or framework type.
- Invalid continuation requests fail before agent or model execution.
- A model-client factory must return a non-null client.
- Existing `AgentRunOptions()` and `AgentRunOptions(Map<String, Object>)` source behavior remains available.
- Tests follow RED-GREEN-REFACTOR.

---

### Task 1: Typed per-run model-client replacement

**Files:**
- Create: `agent-framework-api/src/main/java/io/github/hellices/agentframework/spi/model/ModelClientFactory.java`
- Modify: `agent-framework-api/src/main/java/io/github/hellices/agentframework/api/agent/AgentRunOptions.java`
- Create: `agent-framework-api/src/test/java/io/github/hellices/agentframework/api/agent/AgentRunOptionsTest.java`

**Interfaces:**
- Consumes: `ModelClient`
- Produces: `ModelClientFactory.create(ModelClient defaultClient)`, `AgentRunOptions.builder()`, `AgentRunOptions.resolveModelClient(ModelClient)`

- [ ] **Step 1: Write the failing replacement tests**

```java
@Test
void modelClientFactoryReplacesTheDefaultForOneRun() {
  ModelClient original = request -> failedFuture(new AssertionError("original called"));
  ModelClient replacement = request -> completedFuture(response());
  AgentRunOptions options =
      AgentRunOptions.builder().modelClientFactory(ignored -> replacement).build();

  assertThat(options.resolveModelClient(original)).isSameAs(replacement);
}

@Test
void absentFactoryKeepsTheDefaultClient() {
  ModelClient original = request -> completedFuture(response());
  assertThat(new AgentRunOptions().resolveModelClient(original)).isSameAs(original);
}

@Test
void factoryCannotReturnNull() {
  AgentRunOptions options = AgentRunOptions.builder().modelClientFactory(ignored -> null).build();
  assertThatThrownBy(() -> options.resolveModelClient(request -> completedFuture(response())))
      .isInstanceOf(IllegalStateException.class)
      .hasMessage("modelClientFactory must not return null");
}
```

- [ ] **Step 2: Verify RED**

Run: `./gradlew :agent-framework-api:test --tests '*AgentRunOptionsTest'`

Expected: compilation fails because `ModelClientFactory`, `builder()`, and `resolveModelClient` do not exist.

- [ ] **Step 3: Implement the minimal typed contract**

```java
@FunctionalInterface
public interface ModelClientFactory {
  ModelClient create(ModelClient defaultClient);
}
```

Add immutable builder fields for `ModelClientFactory`, continuation token, and attributes. `resolveModelClient` requires a non-null default and rejects a null transformed client.

- [ ] **Step 4: Verify GREEN**

Run: `./gradlew :agent-framework-api:test --tests '*AgentRunOptionsTest'`

Expected: all `AgentRunOptionsTest` tests pass.

### Task 2: Continuation and input mutual exclusion

**Files:**
- Modify: `agent-framework-api/src/main/java/io/github/hellices/agentframework/api/agent/AgentRunRequest.java`
- Modify: `agent-framework-api/src/test/java/io/github/hellices/agentframework/api/agent/AgentRunOptionsTest.java`

**Interfaces:**
- Consumes: `AgentRunOptions.continuationToken()`, normalized `AgentRunRequest.messages()`
- Produces: constructor-time `IllegalArgumentException` for continuation plus new input

- [ ] **Step 1: Write failing continuation tests**

```java
@Test
void continuationWithNewInputIsRejected() {
  AgentRunOptions options =
      AgentRunOptions.builder().continuationToken("continuation-1").build();
  assertThatThrownBy(() -> request(Message.normalize("new input"), options))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessage("continuationToken cannot be combined with input messages");
}

@Test
void continuationWithoutInputIsAccepted() {
  AgentRunOptions options =
      AgentRunOptions.builder().continuationToken("continuation-1").build();
  assertThat(request(List.of(), options).options().continuationToken())
      .contains("continuation-1");
}
```

- [ ] **Step 2: Verify RED**

Run: `./gradlew :agent-framework-api:test --tests '*AgentRunOptionsTest'`

Expected: the mixed continuation/input request is accepted and the test fails.

- [ ] **Step 3: Add construction-time validation**

After normalizing messages and options in `AgentRunRequest`, reject a present continuation token when `messages` is non-empty. Reject blank continuation tokens in the options builder.

- [ ] **Step 4: Verify GREEN and repository gates**

Run:

```bash
./gradlew :agent-framework-api:test
./gradlew policyCheck quality testJava17 testJava21 testJava25 check
```

Expected: all tasks pass where the corresponding local JDK is installed; unavailable JDK toolchains are verified by PR CI.

### Task 3: Review and delivery loop

**Files:**
- Review all files changed by Tasks 1-2.

**Interfaces:**
- Consumes: final branch diff
- Produces: squash-merge-ready PR with zero unresolved or suppressed Copilot findings

- [ ] **Step 1:** Run an independent high-confidence code review and fix all Critical/Important findings with regression tests.
- [ ] **Step 2:** Commit with the repository trailer, push, and create the PR.
- [ ] **Step 3:** Request `copilot-pull-request-reviewer[bot]` through the GitHub review-request API.
- [ ] **Step 4:** Reply to and resolve every inline thread; re-request review after each push.
- [ ] **Step 5:** Merge only after CI is green and a fresh Copilot review reports no new or suppressed comments.
