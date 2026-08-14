# Hosting, operations, and provider design

## 1. Scope

- `HOST-001..029`: hosting core and protocol adapters
- `OPS-001..026`: observability, errors, security, evaluation, packaging
- `PRV-001..010`: provider/infrastructure adapter policy

## 2. Modules

```text
hosting/agent-framework-hosting-core
  TargetResolver, HostingRequest, HostingResult, continuation/session coordination

protocols/
  agent-framework-responses
  agent-framework-a2a
  agent-framework-ag-ui
  agent-framework-mcp-hosting

providers/
  agent-framework-openai
  agent-framework-azure-openai
  ... one provider per artifact

integrations/
  agent-framework-opentelemetry
  agent-framework-evaluation
  storage/memory/governance adapters

starters/
  dependency-only framework starter artifacts

hosting/agent-framework-standalone
  plain-Java batteries-included assembly
```

Add each module together with its first feature during implementation; do not create empty modules
in advance.

## 3. Hosting core

The hosting core connects targets and state to protocol binders.

```text
validated HostRequest
  -> TargetResolver.resolve(context)
  -> load/create independent session snapshot
  -> run / restore-then-run
  -> persist session/checkpoint cursor
  -> HostResult
```

It does not include:

- HTTP route/status/header
- auth providers and security realms
- executor/scheduler
- transaction manager
- retry/circuit breaker
- server lifecycle

### 3.1 TargetResolver

Use a single asynchronous resolver port. Convert instances, `Supplier`s, async factories, builders,
and CDI/Spring providers through convenience adapters. Keep cache policy separate from target
source.

### 3.2 Session continuity

- loads return independent snapshots
- create-on-miss occurs once per key
- service session candidates are untrusted before authentication
- the host derives a trusted storage key from principal and tenant context
- single-writer coordination and authorization are host responsibilities

Workflows restore the session cursor first and use the durable store as a fallback.

## 4. Protocol adapters

Protocol modules own wire DTOs and pure converters, but not host-framework routes. Framework
applications do not need to go through a protocol endpoint to use an agent internally.

### 4.1 OpenAI Responses

- separate request parsing from candidate session-key extraction
- default mapping rejects overrides of agent-owned instructions, tools, and sampling
- separate branch pointers from mutable conversation heads
- minimal SSE profile + rich content profile
- preserve tools, reasoning, and media in the final payload

### 4.2 A2A

- remote agent implements public `Agent` contract
- separate local exposure helpers from framework binders
- structured service session id
- reject simultaneous use of a continuation token and new user input
- separate message, task, and artifact lifecycles
- map remote/local cancellation and A2A cancel requests to the task's terminal `CANCELED` state
- prohibit additional event or artifact emission after the canceled terminal event

### 4.3 AG-UI

- request alias and resume normalization
- separate predictive-delta and deterministic-snapshot types
- separate UI tool payloads from LLM text
- separate thread IDs from snapshot scope
- persist after stream completion

### 4.4 Foundry

Foundry divides its wire surface into two artifacts.

- `protocols/agent-framework-foundry-responses`: Responses request/stream/final payload and
  continuation mapping
- `protocols/agent-framework-foundry-invocations`: platform invocation envelope, hosted context,
  invocation status/result mapping

The two surfaces reuse hosting-core use cases but do not share or automatically convert wire DTOs
or endpoint contracts. The hosted path validates platform context, while the local path is enabled
only through explicit configuration.

### 4.5 Other hosting

DevUI and channels are optional protocol/adapter artifacts. An authenticated hosted header alone
does not elevate a request to local or development mode.

## 5. Observability

Use the OTel GenAI semantic conventions as the canonical vocabulary. Core APIs do not expose OTel
SDK, Micrometer, or Spring Observation types.

```text
engine semantic event
  -> TelemetrySink port
  -> OpenTelemetry adapter
  -> Micrometer bridge (Spring) / Quarkus OTel / Jakarta provider
```

Principles:

- separate bootstrap from per-agent and per-workflow wrappers
- prompt/tool argument/result capture default off
- separate logging from tracing
- send feature telemetry only to approved HTTPS origins
- application-scoped sticky disable
- do not instrument the same operation more than once
- category-level enable/disable

## 6. Errors, cancellation, resilience, security

The host maps errors by type and category.

- validation/programming: built-in Java exceptions
- provider/transport/checkpoint: bounded-context exception
- timeout: retain it in the result/status envelope
- cancellation: do not translate it into a failure

The resource owner cleans up process trees and remote tasks. A persistent executor is a
session-owned resource whose lifecycle is provided by the host.

Host adapters own retries, timeout policies, circuit breakers, and rate limiting. The engine
provides only idempotency and cancellation signals.

Shell/tool policies and allow/deny lists are not security boundaries. Unsafe bypasses require
explicit acknowledgement or capability. Normalize declarative-workflow state paths against an
allowlisted root, and reject reflective member access and path traversal by default.

## 7. Evaluation and compatibility

optional evaluation SPI:

- batch-oriented provider-neutral request
- explicit converter
- execution failure vs quality failure
- workflow aggregate + per-agent subresults
- deterministic generated evaluator/golden inputs

test levels:

- provider contract tests
- golden behavior scenarios
- wire protocol tests
- installability/native/framework smoke tests

## 8. Provider adapters

Each provider is a separate artifact.

- core has no provider SDK dependency
- no default all-providers bundle
- provider options/capabilities are adapter-owned typed values
- continuation/hosted state parsed only by adapter
- hosting/protocol independent from provider
- storage/memory/governance separate integrations

Provider adapter public facade:

- `ModelClient` implementation
- typed options/capabilities
- conversion utilities where justified
- maturity and compatibility metadata

New providers require no core modification, static registry, or automatic `ServiceLoader`
discovery. The host builder or DI container explicitly selects an adapter instance.

## 9. Maturity and packaging

- one repository version line
- Gradle BOM lists every published artifact
- maturity registry separate from version
- dependency bounds + locks + action/checksum pinning
- maturity-dependent CI/installability gates
- Java/.NET/Python compatibility judged by public surface and behavior

## 10. Framework-neutral lifecycle

| Concern | Port/owner |
| --- | --- |
| target creation/cache | `TargetResolver` + host |
| session authorization | host binder |
| session persistence | `SessionStore` adapter |
| HTTP/SSE | protocol + framework binder |
| telemetry provider | host bootstrap |
| model SDK client | provider adapter/host |
| retry/rate limit | host |
| release/maturity | repository policy |

## 11. Single ownership of overlapping features

Even when a framework and provider offer features with the same name, each execution path has
exactly one owner.

| Concern | Canonical owner | Adapter role |
| --- | --- | --- |
| agent/model/tool loop | AgentEngine | disable framework automatic execution |
| MCP connection/transport/auth | selected MCP adapter | either the direct SDK or Spring AI |
| MCP collision/sampling/task semantics | Agent Framework MCP integration | apply semantics above the connection |
| durable session | SessionStore port + adapter | Spring AI ChatMemory is context projection only |
| application retry/circuit breaker | host | core has no implicit retries |
| semantic telemetry events | Agent Framework | convert to Micrometer/OTel exporters |
| exporter/provider lifecycle | host framework | core does not bootstrap it |
| request auth/tenant | host binder | pass only validated context to core |

When multiple adapter candidates exist for a concern that requires exactly one selection, do not
choose arbitrarily by classpath order; require explicit configuration or a user bean. Multiple
named models may be registered together; only unqualified model selection without a default fails.

## 12. Tests

- hosting core without any framework dependency
- resolver adapters and cache policy
- concurrent create-once and independent snapshot
- untrusted session candidate never reaches store
- protocol converter/wire golden tests
- SSE terminal ordering and cancellation
- A2A cancel request/local cancellation → `CANCELED` terminal task
- telemetry redaction/origin/sticky/no-double-instrumentation
- provider common contract and option rejection
- BOM/maturity/installability policy

## 13. Current implementation

No hosting, protocol, provider, telemetry, or evaluation production modules exist yet. Only parts
of `OPS-023` and `OPS-024`, covering packaging/BOM and dependency policy, are `partial`; no item is
`implemented`.

## 14. Requirements mapping

The canonical rows for `HOST`, `OPS`, and `PRV` are in the
[Requirements traceability matrix](requirements-traceability-matrix.md).
