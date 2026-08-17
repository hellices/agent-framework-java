# Agent Framework for Java Foundation Design

- Status: approved
- Approval date: 2026-08-10
- Scope: the architectural direction of the project, its module boundaries, and the initial development order

## 1. Goal

Provide the core execution semantics of the Microsoft Agent Framework (MAF) in Java. The Java
implementation is delivered as an embeddable agent execution engine that is not tied to any
particular application framework, and it must be composable in several host environments, including
Spring Boot.

This document is not a detailed implementation worksheet. It settles the responsibility boundaries,
the dependency direction, the MVP scope, and the verification order that must hold before
implementation begins.

## 2. Premises confirmed by the investigation

MAF is a broader concept than a model invocation wrapper. It provides an execution model that covers
agents, sessions, the tool execution loop, middleware, workflows, and hosting protocols.

Spring AI 2.x provides `ChatModel`, `ChatClient`, `ToolCallback`, Advisor, `ChatMemory`, MCP, and
model provider integrations. It does not, however, provide the same higher-level execution semantics
as MAF's session and workflow runtime.

Stacking the two frameworks directly therefore produces the following conflicts.

- The Java engine and Spring AI's `ToolCallingAdvisor` can both own the tool execution loop.
- MAF's persistable session and Spring AI's `ChatMemory` for context retention differ in lifetime and responsibility.
- Applying Micrometer and OpenTelemetry instrumentation independently can create duplicate spans.
- Changes to the Spring AI API can propagate into the core execution contract.

## 3. Considered approaches

### 3.1 A literal port of the MAF API

This approach transposes the .NET or Python public API into Java syntax. It gains surface
familiarity, but it requires continuously tracking the differences and changes between the two
original implementations, and it is likely to distort the execution and asynchrony conventions of
the Java ecosystem.

### 3.2 An extension built on Spring AI

This approach takes Spring AI as the execution foundation and offers a MAF-shaped API on top of it.
It allows a fast start inside a Spring application, but ownership of the agent loop, sessions, and
workflows becomes unclear, and reuse outside Spring is difficult.

### 3.3 An embeddable AgentEngine with optional adapters

This approach builds a framework-neutral API and execution state machine and delivers the provider
and Spring integrations as separate artifacts. The initial module count grows, but the
responsibilities and the dependency direction are clear, and MAF compatibility and Java ecosystem
integration can be maintained together.

**Decision:** adopt 3.3.

## 4. Core architectural decisions

### 4.1 An AgentEngine, not a runtime

The core of the project is not built as an application runtime. `AgentEngine` is an embeddable state
machine that advances model invocation, tool execution, and session changes in a consistent order.
It does not implement `Agent`: an application-scoped engine binds immutable `AgentDefinition` and
`AgentRuntime` pairs into ordinary `BoundAgent` instances, so one engine can serve many agents with
different identities, models, and tools.

```text
Application
    |
Host runtime (Spring Boot, Quarkus, Micronaut, Jakarta EE, CLI)
    |
AgentEngine
    |
Model / Tool / Session / Telemetry ports
    |
Provider and infrastructure adapters
```

`AgentEngine` owns the following responsibilities.

- the state transitions of an agent run and of a turn
- the linkage between model responses and tool call results
- the policy for tool iteration counts, interruption, approval, and failure propagation
- the rules for session state changes
- the observable event order in synchronous and streaming execution
- the deterministic state transitions of the future workflow graph

The host owns the following responsibilities.

- DI and object lifecycle
- thread pools, schedulers, and connection pools
- the HTTP server and its endpoints
- configuration, profiles, and secret supply
- authentication, authorization, and the security context
- transactions
- the operational settings for retry, timeout, rate limiting, and circuit breaking
- telemetry exporters and application startup and shutdown

The core does not create its own `ExecutorService`, scheduler, server, global registry, shutdown
hook, or DI container. The execution resources and port implementations it needs are passed in
through the constructor.

### 4.2 Extension points

The pre- and post-execution intervention that MAF-compatible behavior requires is retained, but
general-purpose application middleware is not reimplemented. The core provides only a typed
interceptor SPI with clear responsibilities.

- agent run interceptor
- model call interceptor
- tool call interceptor
- session operation interceptor

An interceptor is responsible only for inspecting and transforming requests and responses,
interrupting execution, observing errors, and passing an explicit execution context. It is not
responsible for DI, transactions, security, HTTP filtering, or component scanning.

The Spring integration module collects interceptor beans in order and passes them to the engine. A
Spring AI Advisor is not generically converted into this SPI. An Advisor is bound to the
`ChatClient` invocation model and therefore cannot express the full semantics of agents, sessions,
tools, and workflows.

### 4.3 Spring and Spring AI integration

Spring Boot is used as an actual host runtime. The auto-configuration module composes `AgentEngine`,
`AgentFactory`, the port implementations, and the interceptors as beans. The starter aggregates the
auto-configuration and its dependencies but contains no production classes. The core never
references `ApplicationContext`.

Spring AI is not a required dependency. Only the following capabilities are connected optionally,
after the core contract is stable.

- converting `ChatModel` into the model client port
- converting `ToolCallback` and the tool provider
- connecting MCP tool discovery and invocation
- connecting Micrometer observation to the core trace context

On the paths where the engine owns the tool loop, Spring AI's automatic tool execution is disabled.
`ChatMemory` may be used only as an optional short-term context projection, never as the session
store.

### 4.4 Session ownership

`AgentSession` is the single source of truth for persistable agent state. A provider conversation ID
and a Spring AI conversation ID are merely internal session metadata and are not used as an
authorization boundary or an external identifier.

A session store implementation declares its concurrency semantics, tenant and user ownership,
expiration, and serialization version. A file store preserves atomic-replace last-writer-wins;
database adapters may expose optimistic compare-and-save as a capability. The storage technology and
transactions are decided by the host and infrastructure adapter.

### 4.5 Observability

The core's canonical telemetry model follows the OpenTelemetry GenAI semantic convention. An agent
run, a model call, a tool call, and a session operation are distinct units of observation.

In the Spring integration, Micrometer Observation is connected to the same trace context, and the
same operation is not instrumented separately by the core and by Spring. Recording prompts, tool
arguments, and results is disabled by default to prevent the exposure of sensitive data.

## 5. Monorepo decision

A multi-project monorepo that manages several artifacts in one repository is used. The reason is to
verify an API change and the contract tests of every adapter as a single unit of change.

The build tool is Gradle Kotlin DSL. This decision was settled by the Gradle Kotlin DSL and Java ARC
Foundation design on the `gradle-arc-foundation` branch, and it replaces the Maven premise of the
initial draft. That build implementation and its design document are merged into `main` after they
pass the `arc-java-build` trusted execution gate, at which point they move into the `docs/design/`
structure of this repository.

The initial target structure is as follows.

```text
agent-framework-java/
├── agent-framework-bom
├── agent-framework-api
├── agent-framework-engine
├── agent-framework-testkit
├── providers/
│   └── agent-framework-openai
├── integrations/
│   ├── agent-framework-mcp
│   ├── agent-framework-spring-ai
│   └── agent-framework-spring-boot-autoconfigure
├── hosting/
│   ├── agent-framework-hosting-core
│   └── agent-framework-standalone
├── starters/
│   └── agent-framework-spring-boot-starter
├── protocols/
├── workflow/
├── compatibility-tests/
└── samples/
    ├── sample-standalone
    └── sample-spring-boot
```

This structure does not mean that every final directory is created up front. Only the modules a
given feature implementation step needs are added. The workflow, A2A, AG-UI, and standalone host
modules are not created as empty modules in the initial repository.

### 5.1 Dependency rules

- The API does not depend on an external application framework.
- The engine depends only on the API and does not depend on Spring.
- Provider and integration modules implement public ports and do not reference engine internals.
- Standalone or framework-native auto-configuration is responsible for composition, and the core
  never references a starter in return.
- Samples are not referenced from a product artifact.
- Compatibility tests verify product behavior only through the public API.

### 5.2 Release rules

Initially, every public artifact is released atomically under a single project version. Users obtain
compatible versions through the BOM. Independent per-module versions are considered only after there
is evidence that the actual release cadence and the compatibility requirements have diverged.

## 6. Initial product scope

### 6.1 Included in the MVP

- single agent execution
- ordinary responses and streaming responses
- the function tool execution loop
- session persistence and restoration
- typed interceptors
- one OpenAI-compatible or Azure OpenAI family provider
- direct integration with the MCP Java SDK
- OpenTelemetry observability
- Spring Boot auto-configuration that composes the engine/factory/default Agent, plus a
  dependency-only starter
- standalone and Spring Boot samples

### 6.2 Excluded from the MVP

- graph workflows and durable checkpoints
- multi-agent handoff, group chat, and orchestration
- harness, compaction, background tasks, and file memory
- A2A, AG-UI, and the OpenAI-compatible hosting endpoint
- an application server of its own
- DI, transaction, security, and resilience frameworks of its own
- automatic tool execution through Spring AI

The Spring AI adapter itself also proceeds only after the core contract and the direct provider
vertical slice are stable. This order does not abandon the Spring AI integration; it is the device
that verifies that the core contract is not dragged along by an external framework.

## 7. Foundation development order

### Stage 0: settle the compatibility baseline

- State the reference implementation, .NET or Python, for each capability.
- Define ordinary responses, a single tool, consecutive tools, tool failure, session restoration, and streaming cancellation as
  golden scenarios.
- Compare the observable behavior of inputs, output events, state changes, and errors rather than API names.

The exit condition is being able to explain the state changes and event order that the Java
implementation must produce for the same scenarios.

### Stage 1: the API and a deterministic AgentEngine

- Define the neutral model for messages, content, tool calls and results, usage, and the finish reason.
- Define the minimal contracts for the model, tool, session store, and telemetry ports.
- Verify a basic turn with a deterministic fake provider that works without an external LLM.

The exit condition is being able to reproduce an ordinary response and the tool loop repeatedly with
the fake provider alone.

### Stage 2: sessions, streaming, and MCP

- Add versioned session serialization and restoration.
- Verify cancellation, timeout propagation, and the streaming event order.
- Connect the MCP Java SDK directly to the tool port.

The exit condition is that session restoration across a process boundary and streaming cancellation
pass the contract tests.

### Stage 3: a direct provider and Spring Boot hosting

- Connect one provider adapter end to end.
- Prove the semantic kernel first with the direct provider and standalone assembly; the provider
  owns protocol translation, not the tool/session/interceptor loop.
- Have Spring Boot auto-configuration compose the engine, model catalog, factory, and conditional
  default Agent from host resources; the starter only aggregates dependencies.
- Use the same agent definition in the standalone and Spring Boot samples.

The exit condition is that the same golden scenarios pass on both hosts without changing the agent
definition in the application code.

### Stage 4: evaluating and implementing the Spring AI adapter

- Measure the conversion loss for `ChatModel`, tool callbacks, MCP, and observations.
- Confirm with a contract test that the automatic tool loop is disabled.
- Explicitly expose the structured output and streaming capabilities that cannot be supported.

When integration is possible through the public ports alone, without conversion loss or double
execution, it is released as a separate artifact.

### Stage 5: workflow and protocol extensions

After the MVP contract is stable, workflows are designed as an independent subproject. They are
extended in the order sequential, branching, parallel, checkpoint, HITL, and multi-agent. Hosting
protocols are added as adapters separate from the engine.

## 8. Verification strategy

- Every model provider must pass the same model client contract tests.
- Every session store must pass the serialization round-trip and concurrent update contract tests.
- The tool loop must verify the iteration limit, duplicate names, failure, approval rejection, and cancellation.
- Streaming must verify the event order, cancellation, and backpressure behavior.
- Spring Boot tests must verify bean composition and the delegation of the host lifecycle.
- Dependency rule tests must forbid references to Spring and to provider implementations from the core.
- Telemetry tests must verify that there are no duplicate spans and no sensitive data recorded by default.

The compatibility matrix records the MAF baseline version, the Java framework version, and the
verified Spring Boot and Spring AI versions. A snapshot API from the Spring AI main branch is not
used as the baseline for the core contract.

## 9. Principal risks and responses

| Risk | Response |
| --- | --- |
| The engine expands into the role of an application runtime | Verify the forbidden responsibilities and the dependency rules in CI |
| A double tool loop with Spring AI | Fix engine ownership and test that automatic tool execution is disabled |
| Confusion between a session and ChatMemory | Fix AgentSession as the single persistent state |
| The Spring API leaks into the core | Forbid a Spring dependency in the API and the engine |
| Duplicate instrumentation by Micrometer and OTel | Designate the same trace bridge and span owner |
| Differences between the original .NET and Python implementations | Record the per-capability reference implementation and the compatibility matrix |
| Inflation of the initial scope | Exclude workflows and protocol hosting from the MVP |

## 10. Next design deliverables

Before implementation work is broken down further, only the following two deliverables are written
first.

1. the compatibility table for the six golden scenarios
2. the minimal public contract between the API, the engine, the provider, and the host

Once both deliverables are approved, the file-, class-, and test-level implementation plan for the
first vertical slice is written.

## 11. References

- [Microsoft Agent Framework overview](https://learn.microsoft.com/en-us/agent-framework/overview/)
- [Microsoft Agent Framework Agents](https://learn.microsoft.com/en-us/agent-framework/agents/)
- [Microsoft Agent Framework Sessions](https://learn.microsoft.com/en-us/agent-framework/agents/conversations/session)
- [Microsoft Agent Framework Workflows](https://learn.microsoft.com/en-us/agent-framework/workflows/)
- [Spring AI ChatModel](https://github.com/spring-projects/spring-ai/blob/main/spring-ai-model/src/main/java/org/springframework/ai/chat/model/ChatModel.java)
- [Spring AI Tools](https://docs.spring.io/spring-ai/reference/api/tools.html)
- [Spring AI Chat Memory](https://docs.spring.io/spring-ai/reference/api/chat-memory.html)
