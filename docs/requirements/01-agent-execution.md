# 01 Agent execution and model calls

**Prefix** `AGT` · **Upstream features** [01 agent-lifecycle](../upstream/snapshots/d0a4165f/features/01-agent-lifecycle.md),
[03 model-execution](../upstream/snapshots/d0a4165f/features/03-model-execution.md)

Defines the contract for creating an agent, running it, calling the model, and returning the
result. The session storage format is owned by [06 Sessions and conversation state](06-sessions.md),
the tool call loop by [04 Tool definitions and the tool call loop](04-tools.md), and interceptors by
[07 Interceptors and context management](07-interceptors.md).

## Adoption scope

The `Grade` column in this document is, as [README](README.md#requirement-grades) defines it, how binding a requirement is once the decision to build the feature has been made; whether the feature is adopted at all follows the [compatibility matrix](../upstream/snapshots/d0a4165f/compatibility-matrix.md).

- The agent core (`AG01`–`AG04`) and model execution (`MOD01`, `MOD02`) are all adoption `Required`.

## Summary

| ID | Requirement | Adoption | Grade | Phase |
| --- | --- | --- | --- | --- |
| AGT-001 | The public entry point is unified into the single `Agent` type | Required | Required | MVP |
| AGT-002 | Every agent has a stable identifier | Required | Required | MVP |
| AGT-003 | The run and streaming entry points are separate | Required | Required | MVP |
| AGT-004 | Consuming only the stream still completes the run | Required | Required | MVP |
| AGT-005 | Cancellation is passed as an explicit argument | Required | Required | MVP |
| AGT-006 | The agent validates session compatibility only | Required | Required | MVP |
| AGT-007 | An agent can be wrapped with a decorator | Required | Required | MVP |
| AGT-008 | The run context is passed explicitly | Required | Required | MVP |
| AGT-009 | Model calls are separated behind the `ChatClient` port | Required | Required | MVP |
| AGT-010 | Optional model capabilities are exposed as separate interfaces | Required | Required | MVP |
| AGT-011 | Request options keep provider-neutral keys as the primary contract | Required | Required | MVP |
| AGT-012 | The option merge precedence is fixed | Required | Required | MVP |
| AGT-013 | The model client can be replaced per run | Required | Recommended | Core+ |
| AGT-014 | A continuation run cannot carry new input as well | Required | Required | Core+ |
| AGT-015 | The final response is reconstructed from the streamed fragments alone | Required | Required | MVP |
| AGT-016 | When the service stores history, local history is not stored twice | Required | Required | Core+ |

---

## AGT-001 The public entry point is unified into the single `Agent` type

**Requirement.** A user must be able to use an agent through the single `Agent` type. The core may
keep implementation layers underneath it, but it does not expose several competing top-level agent
types on its public surface.

**Upstream comparison**

- .NET: puts the `AIAgent` abstract class at the top and extends it with `ChatClientAgent` and `DelegatingAIAgent`.
- Python: splits the hierarchy into `BaseAgent`, `RawAgent`, `Agent`, and `SupportsAgentRun`, but users mostly work with `Agent`.

**Decision.** Both upstreams agree that the agent is the top-level abstraction of a run. They differ
in how much of the hierarchy they publish. Java takes the single-entry-point usability of Python and
hides the layering as an implementation detail. When the number of public types grows, an adapter
author cannot tell which one to implement.

**Acceptance criteria**

- `Agent` is the only agent type in the public packages that users are meant to implement directly.
- Logging, instrumentation, and policy wrappers all take an `Agent` and return an `Agent`.

**Evidence** [01 Public API and types](../upstream/snapshots/d0a4165f/features/01-agent-lifecycle.md)

---

## AGT-002 Every agent has a stable identifier

**Requirement.** An agent must generate its own identifier when it is not given one at construction
time, and that value must not change while the same instance is alive. Name and description are
optional.

**Upstream comparison**

- .NET: uses `Guid.NewGuid().ToString("N")` as the default when `Id` is empty. `Name` and `Description` are virtual properties.
- Python: creates a `uuid4()` string when `id` is omitted and keeps `name` and `description` as fields.

**Decision.** Both upstreams agree. Attributing a run to an agent in observability requires the
identifier to always exist. Allowing automatic generation to fail would give the tracing layer
conditional code, so the value is always filled in, without exception.

**Acceptance criteria**

- An agent created without an identifier still exposes a non-empty identifier.
- Reading the identifier twice from the same instance yields the same value.
- An explicitly supplied identifier is kept as is.

**Evidence** [01 Identity / Metadata](../upstream/snapshots/d0a4165f/features/01-agent-lifecycle.md)

---

## AGT-003 The run and streaming entry points are separate

**Requirement.** The core must offer the run that returns a completed response and the streaming
that returns incremental fragments as different methods. A single method does not change its return
type according to an argument value.

**Upstream comparison**

- .NET: separates `RunAsync()` and `RunStreamingAsync()`.
- Python: has one `run(..., stream=False|True)` method that returns either an awaitable or a stream.

**Decision.** The .NET approach is chosen. Python's single method is natural in a dynamic language,
but Java cannot express an API whose return type depends on an argument in its type system.
Separating them makes error handling, cancellation, and the choice of a reactive stream clear for
each path.

**Acceptance criteria**

- The completed run and the streaming run differ in method name and return type.
- Both paths produce the same final message for the same input.

**Evidence** [01 Run entry points](../upstream/snapshots/d0a4165f/features/01-agent-lifecycle.md),
[03 Run shapes](../upstream/snapshots/d0a4165f/features/03-model-execution.md)

---

## AGT-004 Consuming only the stream still completes the run

**Requirement.** Once the stream has been consumed to the end, the session state update and the
post-run processing must both be finished, without a separate request for the final response object.

**Upstream comparison**

- .NET: the streaming path also performs the session update and history storage.
- Python: pins by test that finishing the stream iteration alone sets `service_session_id` and that the provider stores history after the run.

**Decision.** The two are the same, and Python states it explicitly in a test. Without this rule,
only streaming users lose state, which is a silent data loss. The criterion for a completed run must
be "has the stream ended", not "has the final object been read".

**Acceptance criteria**

- Reading the session again after consuming the stream to the end contains the messages of this run.
- The condition above holds even when the final response object is never requested.
- Cancelling the stream partway does not commit partial state.

**Evidence** [01 acceptance scenarios](../upstream/snapshots/d0a4165f/features/01-agent-lifecycle.md)

---

## AGT-005 Cancellation is passed as an explicit argument

**Requirement.** The cancellation signal must be an explicit argument of the public run API.
Cancellation is not read from a thread local or an implicit execution context.

**Upstream comparison**

- .NET: takes a `CancellationToken` as an argument of every run method.
- Python: relies on the implicit task cancellation of asyncio.

**Decision.** The .NET approach is chosen. Java has no standard implicit cancellation equivalent to
asyncio. Making cancellation an explicit argument lets a host propagate its request timeout
unchanged, and a missing cancellation hand-off shows up at compile time or in review.

**Acceptance criteria**

- Both the completed run and the streaming run take a cancellation argument.
- When cancellation fires, it propagates into the model call and the tool calls in flight.
- A cancelled run is not reported as a success.

**Evidence** [03 Java decisions](../upstream/snapshots/d0a4165f/features/03-model-execution.md)

---

## AGT-006 The agent validates session compatibility only

**Requirement.** The agent must validate that the session it was given is compatible with it and
fail before calling the model when it is not. The storage format of the session payload is owned by
the session and the store.

**Upstream comparison**

- .NET: the agent owns the session serialization API and controls which session types are supported.
- Python: `AgentSession` and the store own the snapshot, and the agent rejects an invalid session input before the run.

**Decision.** The strengths of both are divided up. If the agent owned the format as well (.NET), a
general-purpose session store could not be built; if the session were also responsible for
compatibility (Python alone), per-agent constraints would be hard to express. So **compatibility
validation belongs to the agent and payload storage belongs to the session and the store**.

**Acceptance criteria**

- Running with an incompatible session fails before the model call.
- The failure is an explicit error, not a silent fallback.
- The session storage format can be read and written without knowing a specific agent implementation.

**Evidence** [01 State and snapshots](../upstream/snapshots/d0a4165f/features/01-agent-lifecycle.md),
[08 Sessions](../upstream/snapshots/d0a4165f/features/08-sessions.md)

---

## AGT-007 An agent can be wrapped with a decorator

**Requirement.** The core must provide a delegation form that takes an `Agent` and returns an
`Agent` with added behavior. Logging and instrumentation are assembled this way.

**Upstream comparison**

- .NET: assembles a pipeline with `DelegatingAIAgent` and `AIAgentBuilder`.
- Python: achieves the same effect with layered classes and middleware composition.

**Decision.** The .NET approach is chosen. An explicit delegating type lets a wrapper be tested
independently and its ordering be controlled. This does not conflict with
[AGT-001](#agt-001-the-public-entry-point-is-unified-into-the-single-agent-type). The delegating
type is an extension point, and the type users work with is still `Agent`.

**Acceptance criteria**

- A wrapped agent can still be used as an `Agent`.
- The identifier and the metadata can still be read after wrapping.
- Stacking several wrappers does not change the result of a run.

**Evidence** [01 Delegation / Wrapping](../upstream/snapshots/d0a4165f/features/01-agent-lifecycle.md)

---

## AGT-008 The run context is passed explicitly

**Requirement.** The running agent, the session, and the request metadata must be passed to
interceptors and tools as a run context. The core does not hold these values in thread-local global
state.

**Upstream comparison**

- .NET: has an `AgentRunContext` and fills it on every run path. Tests pin this.
- Python: passes the same information through the run context and the middleware context.

**Decision.** That a context is needed is the same on both sides. Only the way it is passed is
adapted to Java. Making thread locals the baseline loses the context in virtual threads, reactive
chains, and asynchronous composition. Explicit passing is safe whatever execution model the host
uses.

**Acceptance criteria**

- An interceptor can read the running agent and the session from the context.
- The context values survive a run that moves across threads.
- Reading the context does not depend on global static state.

**Evidence** [01 acceptance scenarios](../upstream/snapshots/d0a4165f/features/01-agent-lifecycle.md)

---

## AGT-009 Model calls are separated behind the `ChatClient` port

**Requirement.** The agent must not call a model provider SDK directly; it must call the model only
through the model call port defined by the core.

**Upstream comparison**

- .NET: has `IChatClient`, which `ChatClientAgent` uses.
- Python: has the `BaseChatClient` and `SupportsChatGetResponse` protocols.

**Decision.** Both upstreams agree. Without this separation, neither provider replacement nor
deterministic testing is possible. This port is owned by `agent-framework-api` and implemented by
provider adapters.

**Acceptance criteria**

- The core modules depend on no provider SDK.
- A whole agent run can be tested with a deterministic fake implementation alone.

**Evidence** [03 Public API and types](../upstream/snapshots/d0a4165f/features/03-model-execution.md)

---

## AGT-010 Optional model capabilities are exposed as separate interfaces

**Requirement.** A capability that not every provider supports must be kept out of the base port and
split into a separate capability interface. The caller must be able to check support before the run.

**Upstream comparison**

- .NET: obtains capabilities through service lookup.
- Python: states per-capability protocols such as `SupportsCodeInterpreterTool` and `SupportsWebSearchTool`.

**Decision.** The Python approach is chosen. Expressing a capability as a type lets the IDE and the
compiler report whether it is supported, and calling an unsupported capability shows up as a type
mismatch rather than a failed runtime string lookup.

**Acceptance criteria**

- A provider that implements only the base port is a valid implementation.
- Requesting an unsupported capability is not silently ignored; it fails explicitly or is filtered out beforehand.
- Checking support does not depend on exception handling.

**Evidence** [03 capability contracts](../upstream/snapshots/d0a4165f/features/03-model-execution.md)

---

## AGT-011 Request options keep provider-neutral keys as the primary contract

**Requirement.** Common request options such as temperature and maximum tokens must be defined as
core types, and provider-specific options must be held in a separate area. Provider-specific keys
are not mixed into the same plane as the common options.

**Upstream comparison**

- .NET: provides a rich `ChatOptions` and merge rules.
- Python: has common options and enforces constraints with a separate validation function.

**Decision.** Both upstreams keep the common options as the primary contract. Java takes the same
structure but separates provider-specific options so that swapping an adapter immediately reveals
which settings do not carry over.

**Acceptance criteria**

- A run that uses only common options keeps working when the provider is changed.
- A provider-specific option can be told apart by the provider it belongs to.
- Giving a provider-specific option to a provider that does not support it is not silently ignored.

**Evidence** [03 Request options](../upstream/snapshots/d0a4165f/features/03-model-execution.md)

---

## AGT-012 The option merge precedence is fixed

**Requirement.** When the agent default options and the run-time options overlap, the run-time
options win. This precedence must be documented and pinned by a test.

**Upstream comparison**

- .NET: implements the merge rules for default options and run options and pins them by test.
- Python: keeps option merging and validation in separate functions and tests them.

**Decision.** Both upstreams agree. An implicit merge rule produces bugs where the same setting is
applied differently depending on the path. What matters is not the rule itself but that "the rule
is fixed and verified".

**Acceptance criteria**

- Giving the same key on both sides applies the run-time value.
- A key not specified at run time keeps the agent default.
- The merge rule for the tool list is stated in the same document.

**Evidence** [03 Option merging](../upstream/snapshots/d0a4165f/features/03-model-execution.md)

---

## AGT-013 The model client can be replaced per run

**Requirement.** It must be possible to wrap or replace the model client at run time. Once replaced,
the original client must not be called during that run.

**Upstream comparison**

- .NET: uses the transformed client from the client factory in the run options, and a test verifies that the original is not called.
- Python: no equivalent per-run replacement point is confirmed.

**Decision.** The .NET approach is adopted, but the grade is recommended. Host concerns such as
retries, failover, and per-request credentials can be attached without changing the core. It is not
needed for the MVP, so it is placed in `Core+`.

**Acceptance criteria**

- Only the client replaced for that run is called.
- A run without a replacement uses the agent default client.

**Evidence** [03 acceptance scenarios](../upstream/snapshots/d0a4165f/features/03-model-execution.md)

---

## AGT-014 A continuation run cannot carry new input as well

**Requirement.** Passing a token that continues a previous run together with new user input in the
same run must be rejected.

**Upstream comparison**

- .NET: throws when a run with a continuation token set also has new input messages.
- Python: no corresponding constraint is confirmed in this snapshot.

**Decision.** The .NET constraint is adopted. Allowing both at once makes it ambiguous whether the
new input is applied before or after the continuation, and that ambiguity silently breaks the
conversation order. This is an application of the rule that chooses the safer side.

**Acceptance criteria**

- Passing a continuation token together with new input fails before the run.
- Passing only the continuation token resumes normally.

**Evidence** [03 continuation](../upstream/snapshots/d0a4165f/features/03-model-execution.md)

---

## AGT-015 The final response is reconstructed from the streamed fragments alone

**Requirement.** Collecting the fragments received by streaming must be enough to build the same
final response as a completed run. Usage and the finish reason must be reconstructed as well.

**Upstream comparison**

- .NET: provides a helper that combines the fragments into a final response.
- Python: reconstructs the final response from the fragments the stream wrapper buffered plus its finalization step.

**Decision.** Both upstreams agree. Without this requirement, streaming users lose usage totals
and the finish reason. For observability and billing to see the same values on both paths,
reconstruction has to be guaranteed.

**Acceptance criteria**

- For the same input, the messages of the completed run and of the streaming reconstruction match.
- The reconstructed response contains usage and the finish reason.

**Evidence** [03 Stream assembly](../upstream/snapshots/d0a4165f/features/03-model-execution.md),
[02 Response model](../upstream/snapshots/d0a4165f/features/02-message-content.md)

---

## AGT-016 When the service stores history, local history is not stored twice

**Requirement.** When the model service keeps the conversation history itself, the core must not
store the same history locally as well. When service-side storage is turned off, the local history
path must be used again.

**Upstream comparison**

- .NET: keeps state with the default in-memory history provider for clients that have no conversation identifier.
- Python: pins by test that local history is not injected automatically for a storing client by default, and that turning storage off falls back to the local path.

**Decision.** The intent is the same, and Python pins the switch-over condition by test as well.
Duplicate storage puts the same message into the prompt twice, wasting tokens and confusing the
model. There must always be exactly one owner of storage.

**Acceptance criteria**

- Local history is not injected automatically for a provider whose service stores history.
- Turning service-side storage off re-enables the local history path.
- On neither path does the same message enter the prompt twice.

**Evidence** [03 History storage paths](../upstream/snapshots/d0a4165f/features/03-model-execution.md),
[09 History providers](../upstream/snapshots/d0a4165f/features/09-history-context-memory.md)

---

## What this document does not cover

| Topic | Owning document |
| --- | --- |
| The structure of messages and content types | [02 Messages and the content model](02-message-content.md) |
| Structured output requests and parsing | [03 Structured output](03-structured-output.md) |
| The tool call loop and its iteration limit | [04 Tool definitions and the tool call loop](04-tools.md) |
| The session serialization format and stores | [06 Sessions and conversation state](06-sessions.md) |
| Interceptor execution order | [07 Interceptors and context management](07-interceptors.md) |
| Tracing and instrumentation | [11 Operational quality](11-operations.md) |
