# Typed Public Contracts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace raw object maps and evolvable public records with typed, immutable Java contracts that can support the Agent/Engine and run-pipeline convergence.

**Architecture:** A closed `JsonValue` hierarchy represents persistable JSON data, while `ContextKey<T>` and open provider option interfaces represent transient typed extension data. Evolvable requests, responses, sessions, messages, usage, and tool definitions become final classes with builders and value equality.

**Tech Stack:** Java 17, sealed interfaces, `BigDecimal`, Gradle Kotlin DSL, JUnit 5, AssertJ.

## Global Constraints

- Java 17 is the minimum language level; do not use Java 21-only pattern matching.
- Core API exposes no Jackson, Reactor, Spring, provider SDK, or native serialization type.
- `JsonNull` represents JSON null; public JSON containers never contain Java `null`.
- Provider-specific options are immutable provider-owned types implementing an open API interface.
- Requests/options/responses that may gain fields are final classes with builders and `toBuilder()`.
- Records remain only for demonstrably fixed identifiers, coordinates, and version metadata.
- No deprecated raw-map bridge is retained in the final pre-1.0 API.
- Run the narrowest owning-module tests after each red/green cycle.

---

### Task 1: Closed JSON value model

**Files:**
- Create: `agent-framework-api/src/main/java/io/github/hellices/agentframework/api/value/JsonValue.java`
- Create: `agent-framework-api/src/main/java/io/github/hellices/agentframework/api/value/JsonNull.java`
- Create: `agent-framework-api/src/main/java/io/github/hellices/agentframework/api/value/JsonBoolean.java`
- Create: `agent-framework-api/src/main/java/io/github/hellices/agentframework/api/value/JsonNumber.java`
- Create: `agent-framework-api/src/main/java/io/github/hellices/agentframework/api/value/JsonString.java`
- Create: `agent-framework-api/src/main/java/io/github/hellices/agentframework/api/value/JsonArray.java`
- Create: `agent-framework-api/src/main/java/io/github/hellices/agentframework/api/value/JsonObject.java`
- Create: `agent-framework-api/src/main/java/io/github/hellices/agentframework/api/value/JsonValues.java`
- Test: `agent-framework-api/src/test/java/io/github/hellices/agentframework/api/value/JsonValueTest.java`

**Interfaces:**
- Produces: `JsonValue`, `JsonObject`, and `JsonValues.fromJava(Object)` used by every later task.

- [ ] **Step 1: Write the failing closed-value tests**

```java
assertThat(JsonValues.fromJava(Map.of("enabled", true, "count", 2)))
    .isEqualTo(JsonObject.builder()
        .put("count", JsonNumber.of(2))
        .put("enabled", JsonBoolean.of(true))
        .build());
assertThat(JsonValues.fromJava(null)).isSameAs(JsonNull.instance());
assertThatThrownBy(() -> JsonValues.fromJava(Double.NaN))
    .isInstanceOf(IllegalArgumentException.class);
assertThatThrownBy(() -> JsonObject.builder().put("bad", null))
    .isInstanceOf(NullPointerException.class);
```

- [ ] **Step 2: Run the test to verify RED**

```bash
./gradlew :agent-framework-api:test --tests '*JsonValueTest'
```

Expected: compilation fails because `api.value` does not exist.

- [ ] **Step 3: Implement the closed hierarchy**

```java
public sealed interface JsonValue
    permits JsonNull, JsonBoolean, JsonNumber, JsonString, JsonArray, JsonObject {}

public final class JsonNumber implements JsonValue {
  private final BigDecimal value;

  public static JsonNumber of(Number value) { /* validate finite and normalize */ }
  public BigDecimal value() { return value; }
}

public final class JsonObject implements JsonValue {
  public static Builder builder() { return new Builder(); }
  public Map<String, JsonValue> values() { return values; }
  public Optional<JsonValue> get(String name) { return Optional.ofNullable(values.get(name)); }
}
```

Implement structural `equals`, `hashCode`, defensive copies, deterministic object insertion order,
maximum nesting depth 64, finite-number validation, and long-range/decimal limits matching
`SessionStateValues`.

- [ ] **Step 4: Run tests and quality**

```bash
./gradlew :agent-framework-api:test --tests '*JsonValueTest'
./gradlew :agent-framework-api:quality
```

Expected: both pass.

- [ ] **Step 5: Commit**

```bash
git add agent-framework-api
git commit -m "api: add typed JSON value model"
```

---

### Task 2: Typed transient attributes and provider options

**Files:**
- Create: `agent-framework-api/src/main/java/io/github/hellices/agentframework/api/context/ContextKey.java`
- Create: `agent-framework-api/src/main/java/io/github/hellices/agentframework/api/context/ContextAttributes.java`
- Modify: `agent-framework-api/src/main/java/io/github/hellices/agentframework/spi/model/ModelProviderOption.java`
- Modify: `agent-framework-api/src/main/java/io/github/hellices/agentframework/spi/model/ModelRequestOptions.java`
- Modify: `agent-framework-api/src/main/java/io/github/hellices/agentframework/api/agent/AgentRunOptions.java`
- Test: `agent-framework-api/src/test/java/io/github/hellices/agentframework/api/context/ContextAttributesTest.java`
- Test: `agent-framework-api/src/test/java/io/github/hellices/agentframework/spi/model/ModelOptionsTest.java`
- Test: `agent-framework-api/src/test/java/io/github/hellices/agentframework/api/agent/AgentRunOptionsTest.java`

**Interfaces:**
- Consumes: `JsonValue`.
- Produces: `ContextKey<T>`, immutable `ContextAttributes`, and typed `ModelProviderOption`.

- [ ] **Step 1: Write failing type-isolation tests**

```java
ContextKey<String> tenant = ContextKey.of("agent", "tenant", String.class);
ContextAttributes attributes = ContextAttributes.builder().put(tenant, "acme").build();
assertThat(attributes.get(tenant)).contains("acme");

OpenAiOptions options = new OpenAiOptions("reasoning-high");
ModelRequestOptions request = ModelRequestOptions.builder().providerOption(options).build();
assertThat(request.providerOption(OpenAiOptions.class)).containsSame(options);
```

Define `OpenAiOptions` as a test-local immutable class implementing `ModelProviderOption`.

- [ ] **Step 2: Verify RED**

```bash
./gradlew :agent-framework-api:test --tests '*ContextAttributesTest' --tests '*ModelOptionsTest' --tests '*AgentRunOptionsTest'
```

Expected: compilation fails on the new typed contracts.

- [ ] **Step 3: Implement typed contracts**

```java
public interface ModelProviderOption {
  String providerId();
}

public final class ContextKey<T> {
  public static <T> ContextKey<T> of(String namespace, String name, Class<T> type);
  public String namespace();
  public String name();
  public Class<T> type();
}

public final class ContextAttributes {
  public static Builder builder();
  public <T> Optional<T> get(ContextKey<T> key);
  public Builder toBuilder();
}
```

Store provider options by concrete option class, reject duplicate classes in one request, and merge
run-level options over definition-level options. Replace `AgentRunOptions.attributes()` with
`ContextAttributes`.

- [ ] **Step 4: Verify GREEN**

```bash
./gradlew :agent-framework-api:test --tests '*ContextAttributesTest' --tests '*ModelOptionsTest' --tests '*AgentRunOptionsTest'
```

Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add agent-framework-api
git commit -m "api: add typed execution extensions"
```

---

### Task 3: Typed tool and session state contracts

**Files:**
- Modify: `agent-framework-api/src/main/java/io/github/hellices/agentframework/api/tool/ToolArguments.java`
- Modify: `agent-framework-api/src/main/java/io/github/hellices/agentframework/api/tool/ToolDefinition.java`
- Modify: `agent-framework-api/src/main/java/io/github/hellices/agentframework/api/tool/ToolContext.java`
- Modify: `agent-framework-api/src/main/java/io/github/hellices/agentframework/api/agent/AgentSession.java`
- Modify: `agent-framework-api/src/main/java/io/github/hellices/agentframework/api/session/SessionStateValues.java`
- Modify: `agent-framework-api/src/main/java/io/github/hellices/agentframework/api/session/SessionContext.java`
- Test: `agent-framework-api/src/test/java/io/github/hellices/agentframework/api/tool/ToolContractTest.java`
- Test: `agent-framework-api/src/test/java/io/github/hellices/agentframework/api/agent/AgentSessionTest.java`
- Test: `agent-framework-api/src/test/java/io/github/hellices/agentframework/api/session/SessionContextTest.java`

**Interfaces:**
- Consumes: `JsonObject`, `ContextAttributes`.
- Produces: final `ToolArguments`, `ToolDefinition`, `ToolContext`, and `AgentSession`.

- [ ] **Step 1: Write failing typed-state tests**

```java
ToolArguments arguments =
    ToolArguments.of(JsonObject.builder().put("city", JsonString.of("Seoul")).build());
assertThat(arguments.string("city")).contains("Seoul");

AgentSession session = AgentSession.builder()
    .sessionId("session-1")
    .state(JsonObject.builder().put("turn", JsonNumber.of(1)).build())
    .build();
assertThat(session.toBuilder().build()).isEqualTo(session);
```

- [ ] **Step 2: Verify RED**

```bash
./gradlew :agent-framework-api:test --tests '*ToolContractTest' --tests '*AgentSessionTest' --tests '*SessionContextTest'
```

Expected: compilation fails on typed factories/builders.

- [ ] **Step 3: Replace raw map contracts**

```java
public final class ToolDefinition {
  public static Builder builder();
  public String name();
  public String description();
  public JsonObject inputSchema();
  public Builder toBuilder();
}

public final class AgentSession {
  public static Builder builder();
  public String sessionId();
  public Optional<String> serviceSessionId();
  public JsonObject state();
  public Builder toBuilder();
}
```

Use `JsonObject` for persisted session values and `ContextAttributes` for `ToolContext`. Update
`SessionContext` to convert provider state only through `JsonValue` or a registered `StateCodec`;
do not expose a new `Object` entry point.

- [ ] **Step 4: Verify GREEN**

```bash
./gradlew :agent-framework-api:test --tests '*ToolContractTest' --tests '*AgentSessionTest' --tests '*SessionContextTest'
```

Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add agent-framework-api
git commit -m "api: type tool and session state"
```

---

### Task 4: Evolvable model request and response values

**Files:**
- Modify: `agent-framework-api/src/main/java/io/github/hellices/agentframework/spi/model/ModelRequest.java`
- Modify: `agent-framework-api/src/main/java/io/github/hellices/agentframework/spi/model/ModelResponse.java`
- Modify: `agent-framework-api/src/main/java/io/github/hellices/agentframework/spi/model/ModelResponseUpdate.java`
- Test: `agent-framework-api/src/test/java/io/github/hellices/agentframework/spi/model/ModelOptionsTest.java`
- Create: `agent-framework-api/src/test/java/io/github/hellices/agentframework/spi/model/ModelValuesTest.java`

**Interfaces:**
- Consumes: typed options, attributes, tools, and `JsonObject`.
- Produces: final builder-based model request/response/update classes.

- [ ] **Step 1: Write failing builder round-trip tests**

```java
ModelRequest request = ModelRequest.builder()
    .messages(List.of(new Message(Role.USER, List.of(new TextContent("hello")))))
    .continuationToken("service-turn-1")
    .attributes(ContextAttributes.empty())
    .build();
assertThat(request.toBuilder().build()).isEqualTo(request);

ModelResponseUpdate update = ModelResponseUpdate.builder()
    .finishReason(FinishReason.STOP)
    .metadata(JsonObject.empty())
    .build();
assertThat(update.toBuilder().build()).isEqualTo(update);
```

- [ ] **Step 2: Verify RED**

```bash
./gradlew :agent-framework-api:test --tests '*ModelOptionsTest' --tests '*ModelValuesTest'
```

Expected: compilation fails because the current records have no builders.

- [ ] **Step 3: Implement final values with value equality**

Keep existing accessor names. Add `builder()` and `toBuilder()`. Move continuation state into
`ModelRequest`; remove it from capability interfaces in the later pipeline plan. Use `JsonObject`
for serializable metadata and keep `rawRepresentation` as an explicitly transient diagnostic
handle.

- [ ] **Step 4: Verify GREEN**

```bash
./gradlew :agent-framework-api:test --tests '*ModelOptionsTest' --tests '*ModelValuesTest'
```

Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add agent-framework-api
git commit -m "api: make model values evolvable"
```

---

### Task 5: Evolvable agent and message values

**Files:**
- Modify: `agent-framework-api/src/main/java/io/github/hellices/agentframework/api/agent/AgentResponse.java`
- Modify: `agent-framework-api/src/main/java/io/github/hellices/agentframework/api/agent/AgentResponseUpdate.java`
- Modify: `agent-framework-api/src/main/java/io/github/hellices/agentframework/api/agent/AgentRunRequest.java`
- Modify: `agent-framework-api/src/main/java/io/github/hellices/agentframework/api/agent/AgentRunContext.java`
- Modify: `agent-framework-api/src/main/java/io/github/hellices/agentframework/api/message/Message.java`
- Modify: `agent-framework-api/src/main/java/io/github/hellices/agentframework/api/message/Usage.java`
- Modify: all `api.message.Content` implementations that expose `Map<String, Object>`
- Test: `agent-framework-api/src/test/java/io/github/hellices/agentframework/api/agent/AgentResponseAssemblyTest.java`
- Test: `agent-framework-api/src/test/java/io/github/hellices/agentframework/api/agent/AgentRunContextTest.java`
- Test: `agent-framework-api/src/test/java/io/github/hellices/agentframework/api/message/MessageTest.java`

**Interfaces:**
- Produces: builder-based agent request/response/update and typed message metadata.

- [ ] **Step 1: Write failing copy and reconstruction tests**

```java
AgentResponse response = AgentResponse.builder()
    .agentId("agent-1")
    .responseId("response-1")
    .messages(List.of(new Message(Role.ASSISTANT, List.of(new TextContent("done")))))
    .additionalProperties(JsonObject.empty())
    .build();
assertThat(response.toBuilder().build()).isEqualTo(response);
assertThat(AgentResponse.fromUpdates(split(response))).isEqualTo(response);
```

- [ ] **Step 2: Verify RED**

```bash
./gradlew :agent-framework-api:test --tests '*AgentResponseAssemblyTest' --tests '*AgentRunContextTest' --tests '*MessageTest'
```

Expected: compilation fails on builder and typed metadata methods.

- [ ] **Step 3: Convert evolvable records and metadata**

Preserve response reconstruction, message-boundary, usage-summing, finish-reason, and continuation
semantics. Implement numeric usage extension merging over `JsonNumber`; the last update wins for
non-numeric extension values. Keep transient `rawRepresentation` out of equality and serialization
only if the approved compatibility requirements explicitly permit it; otherwise preserve existing
value equality.

- [ ] **Step 4: Verify GREEN**

```bash
./gradlew :agent-framework-api:test --tests '*AgentResponseAssemblyTest' --tests '*AgentRunContextTest' --tests '*MessageTest'
./gradlew :agent-framework-api:test
```

Expected: all API tests pass.

- [ ] **Step 5: Commit**

```bash
git add agent-framework-api
git commit -m "api: harden agent message values"
```

---

### Task 6: Migrate engine, adapters, policy, and documentation

**Files:**
- Modify: all compilation failures under `agent-framework-engine/src/`
- Modify: all compilation failures under `agent-framework-testkit/src/`
- Modify: all compilation failures under `integrations/agent-framework-mcp/src/`
- Modify: all compilation failures under `providers/agent-framework-openai/src/`
- Modify: `docs/requirements/java-api-audit.md`
- Modify: `docs/design/requirements-design/00-clean-architecture.md`
- Modify: `docs/design/requirements-design/01-core-execution.md`
- Modify: `docs/design/requirements-design/requirements-traceability-matrix.md`
- Modify: `README.md`
- Test: existing tests in every affected project

**Interfaces:**
- Consumes: Tasks 1-5.
- Produces: a compiling repository with no primary raw object-map extension contract.

- [ ] **Step 1: Add architecture-policy assertions**

Add tests in `build-tools/harness-policy` that fail when an evolvable allowlisted API type is a
record or when primary API/SPI signatures expose `Map<String, Object>`.

- [ ] **Step 2: Verify RED**

```bash
./gradlew policyCheck
```

Expected: the new policy reports remaining record/raw-map violations.

- [ ] **Step 3: Migrate call sites without compatibility bridges**

Use builders and typed values directly in engine, MCP, OpenAI, testkit, and samples. Delete
`fromLegacyOptions` and map-based constructors once no production or test caller remains.

- [ ] **Step 4: Verify the whole slice**

```bash
./gradlew :agent-framework-api:test :agent-framework-engine:test :agent-framework-testkit:test
./gradlew :integrations:agent-framework-mcp:test :providers:agent-framework-openai:test
./gradlew policyCheck quality testJava17 testJava21 testJava25 check
```

Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add .
git commit -m "api: complete typed contract migration"
```
