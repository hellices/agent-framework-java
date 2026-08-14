# Java API and extension principles

This document defines the Java public API principles that apply to all 244 requirements under
`docs/requirements/`. It preserves observable upstream execution semantics without directly
transposing Python's dynamic object model or .NET-specific runtime types into the Java API.

## 1. Explicit, null-safe contracts

- Public inputs are non-null by default. Absence is represented by an empty collection, a dedicated
  `empty()` factory, or `Optional<T>` in a return position.
- `null` is not silently converted into empty input or a successful result. A separate adapter turns
  a `void` function or no-result tool into an explicit empty result.
- `Optional<T>` is used only as a return type, not as a field, method parameter, or collection
  element.

## 2. Immutable values and evolvable APIs

- Messages, responses, options, session snapshots, and workflow definitions are immutable values.
  Types with many optional properties provide a builder and `toBuilder()` or a copy factory.
- A public record is used only for a closed value whose components will remain stable. Adding a
  record component breaks source and binary compatibility, so records are not used for evolvable
  option or request types.
- An SPI intended for external implementations is an open interface. A sealed hierarchy is used
  only for a closed state or event set fully owned by the framework.
- Even when the known kinds are closed, provider extensions are preserved in a typed extension
  envelope. A global factory registry or a core change must not be required to add a provider.

## 3. Type-safe extension data

- Extension options and contexts in the public API do not use `Map<String, Object>` as their primary
  contract. Common options are typed properties, and provider options are adapter-owned immutable
  types.
- Extensible attributes use `ContextKey<T>` or an equivalent typed key. A key identifies both its
  namespace and value type, and a collision fails.
- Persistable extra properties are limited to a JSON-safe value model or registered codecs. A raw
  provider SDK object is a transient diagnostic handle and is not serialized automatically into a
  session snapshot.

## 4. Asynchrony, streaming, and cancellation

- A single asynchronous result uses `CompletionStage<T>`, and a backpressure-aware stream uses
  `Flow.Publisher<T>` as the framework-neutral public contract.
- Reactor `Mono`/`Flux`, Mutiny `Uni`/`Multi`, and Jakarta REST asynchronous types are converted in
  adapters.
- Cancellation travels through an explicit execution signal and run handle. Adapters bridge
  `Future.cancel`, thread interruption, and HTTP client abort. Where the contract permits it, an
  agent execution stream can connect `Flow.Subscription.cancel` to run cancellation. Canceling a
  durable workflow event or watch subscription stops observation only; only an explicit workflow
  handle cancels the run.
- The core does not create an executor, scheduler, or virtual-thread factory. When concurrent
  execution is needed, it composes host-injected execution resources or asynchronous results already
  returned by a port.

## 5. Errors and resource ownership

- Public exceptions are unchecked by default. Invalid arguments remain
  `IllegalArgumentException`, invalid states remain in the `IllegalStateException` family, and only
  external failures are classified as bounded-context exceptions.
- Cancellation is not wrapped as an ordinary failure. Its cause and machine-readable category are
  preserved.
- Only the object that created a resource closes it. An injected client, executor, or store is not
  closed.
- Synchronous shutdown uses `AutoCloseable`; asynchronous shutdown uses an explicit
  completion-returning lifecycle port. Close operations must be idempotent.

## 6. Serialization and type discovery

- Public Java packages use the community namespace `io.github.hellices.agentframework`; production
  contracts do not use a `com.microsoft.*` namespace.
- Java native serialization, arbitrary class names in serialized data, and `Class.forName`-based
  restoration are not used.
- State and checkpoints are restored through stable type ids, schema versions, and an explicitly
  injected codec registry. The registry is instance-scoped and immutable after assembly.
- Function and tool schema inference occurs only when trustworthy Java type and parameter metadata
  are available. If parameter names were not retained or generic erasure prevents an accurate
  schema, the framework requires an explicit schema rather than guessing.
- The core does not automatically run `ServiceLoader`, component scanning, or a global registry.
  Plain Java, Spring Boot, Quarkus, and Jakarta EE adapters use the same explicit builder or
  constructor assembly contract.

## 7. Framework adapters

- Spring Boot separates auto-configuration from its starter and assembles components through
  conditional beans and customizers. Spring AI is an optional model or tool adapter and does not
  replace the core tool loop.
- A first-class Quarkus extension provides stable runtime and deployment artifacts together. The
  runtime artifact owns the extension descriptor; the deployment artifact owns required recorders,
  generated metadata, and native-image build steps.
- Jakarta EE uses CDI producers or a portable extension and container-owned scopes. The core does
  not look up the CDI container or request context.
- Every adapter implements only public ports and does not reference engine-internal packages. Core
  contract tests must continue to run after an adapter is removed.

## 8. Verification

Every public type in the design and implementation must answer these questions:

1. Can it be assembled explicitly in plain Java without a DI container?
2. Can Spring Boot, Quarkus, and Jakarta EE inject the same port while retaining their own scopes and
   lifecycles?
3. Are provider SDK, JSON mapper, and reactive library types absent from the core API?
4. Can a new adapter be added without a core change, global registration, or reflection
   configuration?
5. Is there one identifiable owner for asynchronous completion, cancellation, resource shutdown, and
   session persistence?

If any answer is no, the requirement mapping is not complete.
