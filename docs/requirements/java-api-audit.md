# Java idiom and extensibility audit

This document records the pre-design review of all 244 requirements from the perspective of Java
public APIs and multi-framework extensibility. The review uses
[Java API and extension principles](java-api-principles.md) as its standard and verifies observable
upstream behavior against production source and tests in pinned snapshot `d0a4165f`.

## Conclusion

The architectural direction of the requirements is generally appropriate for a Java framework.

- Responsibilities are separated between `AgentEngine` and the host runtime.
- Providers, protocols, storage, and telemetry are adapters behind public ports.
- Typed interceptors and immutable workflow definitions fit Java strategy, decorator, and builder
  patterns.
- Spring Boot is the first host integration but not a core dependency; Quarkus and Jakarta EE can
  use the same ports.
- Public packages remain in the community namespace `io.github.hellices.agentframework`, not a
  `com.microsoft.*` production namespace.

Some requirements nevertheless risked translating Python `None`, `deepcopy`, mutable dictionaries,
and global registries, or .NET polymorphic registration overloads, directly into Java contracts.
The following items were corrected at the requirement level.

## Corrected requirements

| ID | Problem | Correction |
| --- | --- | --- |
| AGT-005 | Risk of directly copying .NET `CancellationToken` | Preserve explicit cancellation semantics and require bridges for `Future`, `Flow.Subscription`, interruption, and HTTP abort |
| AGT-011 | Risk of implementing provider-neutral “keys” as `Map<String,Object>` | Separate typed immutable common options from adapter-owned provider option types |
| MSG-003 | Risk of translating Python `None` into a Java `null` convenience | Use no-input or an empty list and reject `null` at public boundaries |
| MSG-005 | A purely sealed content hierarchy would block provider extensions | Require known core kinds together with a typed provider extension envelope |
| MSG-007 | Durable metadata and raw provider SDK objects would have the same lifecycle | Separate JSON-safe extension values from transient diagnostic handles |
| TOOL-003 | Reflection schema could guess parameter names and generic types | Infer only from trustworthy metadata and require an explicit schema when metadata is insufficient |
| TOOL-013 | A `null` tool result could become success, and arbitrary-object conversion had no clear boundary | Make the void adapter produce the upstream-compatible empty-string content, fail a typed handler that returns `null`, and assemble a default JSON-safe mapper |
| SES-005 | Risk of a Python-style process-global type registry | Require an instance-scoped immutable registry, stable type ids, and no `Class.forName` |
| SES-007 | The contract could mandate `deepcopy` and permit `null` for absence | Require immutable or structurally independent snapshots and explicit absence |
| INT-004 | Shared mutable context and `Map<String,Object>` extensions | Require immutable requests, controlled result replacement, and typed context keys |
| HAR-017 | Risk of implementing the polling registry as a static or global singleton | Require a host-injected, instance-scoped polling store |
| WF-008 | Callable registry scope was unclear | Require a workflow- or restore-scoped immutable registry with no global discovery |
| HOST-004 | Four Python/.NET shapes—instance, factory, async factory, and builder—could be fixed into the public API | Unify them behind one async `TargetResolver<T>` port with convenience adapters |
| HOST-010 | Requiring only a Spring binder would make other Java containers second-class adapters | Generalize to Spring Boot, Quarkus, and Jakarta EE binder families plus plain Java assembly |
| OPS-001 | Choosing OTel semantic conventions could be mistaken for a core SDK dependency | Require the standard vocabulary and a telemetry adapter, and prohibit OTel or Micrometer types in core signatures |
| OPS-006 | Sticky instrumentation state could become a JVM-global static | Limit it to application-scoped, host-owned control |

No requirement ids were added or retired, so the total remains 244.

## Items to decide in design

The following are Java design choices rather than requirement defects. Detailed designs and ADRs
settle them.

### Public type forms

- Use a final class with a builder for evolvable request and option types.
- Limit records to stable snapshots, events, and identifiers.
- Use open interfaces for externally implemented SPIs and sealed hierarchies only for closed engine
  states and events.
- Model `Role` as an immutable value type with known constants rather than as an enum.

### Asynchrony and streaming

- Use `CompletionStage<T>` for a single asynchronous result and `Flow.Publisher<T>` for a
  backpressure-aware stream as framework-neutral public contracts.
- Do not use `CompletionStage` itself as a cancellation token. An explicit cancellation signal and
  run handle bridge standard cancellation paths.
- The engine does not default to `ForkJoinPool.commonPool()` or create its own executor.

### Type tokens and schemas

- Pass structured output `T` through a framework-neutral `TypeRef<T>` or equivalent Java type
  descriptor.
- Use Jackson `JavaType`, Spring `ResolvableType`, and provider SDK schema types only inside
  adapters.
- An annotation processor can be the default generation path for workflow routes and tool metadata,
  but it does not remove the plain Java builder or explicit schema paths.

### Resource and adapter discovery

- Only the connection owner closes it; an adapter does not close a borrowed client.
- An integration that requires asynchronous close uses a completion-returning lifecycle port.
- The core does not automatically run `ServiceLoader`, component scanning, or a static registry.
- Spring Boot conditional beans, Quarkus CDI or runtime beans, and Jakarta EE producers assemble the
  same builders and ports.

### Errors

- Public exceptions are unchecked by default.
- Argument and state errors remain Java built-in exceptions.
- Only external failures such as provider, transport, and checkpoint failures have bounded-context
  exceptions and machine-readable categories.
- Cancellation is not wrapped as a generic framework failure.

## Requirements retained and why

The following items may look unlike a dynamic-language API, but should still be retained in Java.

| ID | Reason for retention |
| --- | --- |
| AGT-005 | Explicit cancellation is safer than thread-local state in reactive and virtual-thread environments, but directly copying a custom token is prohibited. |
| MSG-002 | A value type with known constants and custom roles is an established Java pattern, like an HTTP method. |
| INT-003 | Registration-order pre-processing and reverse-registration-order post-processing form a clear chain-of-responsibility contract. |
| WF-002 | Annotation processing is more suitable for AOT and native images than reflection alone; the explicit builder remains available. |
| WF-006 | The three edge kinds are closed execution semantics of the runtime; a richer builder can lower into them. |
| OPS-009 | Leaving `IllegalArgumentException` and `IllegalStateException` unwrapped by domain exceptions is idiomatic Java. |
| PRV-007 | Moving provider capabilities to adapter-owned typed surfaces prevents growth of core methods. |

## Comparison with the current Java code

The current production source is in a pre-design bootstrap state.

| Path | Current status | Requirement mapping |
| --- | --- | --- |
| `agent-framework-api/.../ApiContract.java` | Module-boundary marker | Does not implement a functional requirement |
| `agent-framework-engine/.../EngineContract.java` | Dependency-boundary marker | Does not implement a functional requirement |
| `agent-framework-testkit/.../DeterministicClock.java` | Deterministic time fixture | Future OPS/WF test support |
| `build-tools/harness-policy` | Repository, build, and publishing policy | Part of the build contract for OPS-023 and OPS-024 |

Functional requirements therefore cannot be marked “implemented” against the current code. For
each id, the design mapping matrix must record separately:

1. Target module, package, and type
2. Current implementation status: `absent`, `bootstrap`, `partial`, or `implemented`
3. Pinned upstream production and test evidence
4. Planned unit, contract, or golden test

The default status of every AGT through PRV functional id is currently `absent`; only parts of the
build and packaging requirements `OPS-023` and `OPS-024` are `partial`. No requirement yet meets all
acceptance criteria and qualifies as `implemented`.

## Design approval criteria

A completed design document must satisfy all of the following:

- All 244 ids are assigned to exactly one canonical design section.
- A cross-cutting reference does not change the canonical owner.
- Target Java symbols do not violate this document's null, typing, async, or lifecycle principles.
- The core design remains valid when Spring Boot, Quarkus, and Jakarta EE adapters are removed.
- An item absent from the current code is marked as a planned mapping rather than described as
  implemented.
