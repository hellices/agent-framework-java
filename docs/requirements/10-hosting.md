# 10 Hosting and protocols

**Prefix** `HOST` · **Upstream features** [20 hosting](../upstream/snapshots/d0a4165f/features/20-hosting.md),
[21 openai-responses-hosting](../upstream/snapshots/d0a4165f/features/21-openai-responses-hosting.md),
[22 a2a](../upstream/snapshots/d0a4165f/features/22-a2a.md),
[23 ag-ui](../upstream/snapshots/d0a4165f/features/23-ag-ui.md),
[25 foundry-devui-channels](../upstream/snapshots/d0a4165f/features/25-foundry-devui-channels.md),
[26 identity-session-routing](../upstream/snapshots/d0a4165f/features/26-identity-session-routing.md)

Defines the hosting core, OpenAI Responses, A2A, AG-UI, Foundry, DevUI, channels, and the identity
boundary. The session contracts are owned by [06 Sessions and conversation state](06-sessions.md)
and the workflow core by [09 Workflows and orchestration](09-workflows.md). This document
concentrates only on how hosting binds the contracts it is given and coordinates storage.

## Adoption scope

The `Grade` column in this document is, as [README](README.md#requirement-grades) defines it, how binding a requirement is once the decision to build the feature has been made; whether the feature is adopted at all follows the [compatibility matrix](../upstream/snapshots/d0a4165f/compatibility-matrix.md).

- The hosting core and the identity boundary (`HOST01`, `ID01`) have adoption `Required`.
- Responses (`RESP01`), A2A (`A2A01`), AG-UI (`AGUI01`), Foundry (`FND01`), and the DevUI and channel adapters are adoption `Optional` scope separated from the hosting core.

## Summary

| ID | Requirement | Adoption | Grade | Phase |
| --- | --- | --- | --- | --- |
| HOST-001 | The hosting model and the wire protocol are separated | Required | Required | Core+ |
| HOST-002 | The session contracts are owned by the API and the engine | Required | Required | Core+ |
| HOST-003 | The hosting core is responsible only for binding and state coordination | Required | Required | Core+ |
| HOST-004 | Target resolution supports instances, factories, async factories, and builders | Required | Required | Core+ |
| HOST-005 | Session continuity guarantees a working copy and a single-creation rule | Required | Required | Core+ |
| HOST-006 | Workflow continuation follows restore-then-run with a durable fallback | Required | Required | Workflow |
| HOST-007 | In-memory continuity is kept as a development convenience only | Required | Required | Hosting |
| HOST-008 | Authentication, authorization, the single writer, and scaling are the host's responsibility | Required | Required | Hosting |
| HOST-009 | A raw service session identifier is not an authorization boundary | Required | Required | Hosting |
| HOST-010 | The Spring host binder is split into an optional module | Required | Required | Hosting |
| HOST-011 | The Responses adapter separates request parsing from session key extraction | Optional | Required | Hosting |
| HOST-012 | The default Responses mapping strictly rejects caller overrides | Optional | Required | Hosting |
| HOST-013 | Responses continuation distinguishes a branch pointer from a mutable head | Optional | Required | Hosting |
| HOST-014 | Responses SSE supports a minimal profile and an extended profile together | Optional | Recommended | Hosting |
| HOST-015 | The final Responses payload preserves the tool, reasoning, and media item families | Optional | Required | Hosting |
| HOST-016 | A remote A2A agent wrapper is provided as a first-class adapter | Optional | Recommended | Hosting |
| HOST-017 | Local A2A exposure separates the helpers from the Spring binder | Optional | Required | Hosting |
| HOST-018 | A2A continuation is stored as a structured serviceSessionId | Optional | Required | Hosting |
| HOST-019 | An A2A continuation token and a new user message are not accepted together | Optional | Required | Hosting |
| HOST-020 | The A2A host distinguishes the message, task, and artifact lifecycles | Optional | Required | Hosting |
| HOST-021 | The AG-UI adapter owns request aliases and resume normalization | Optional | Required | Hosting |
| HOST-022 | AG-UI state separates predictive deltas from deterministic snapshots | Optional | Required | Hosting |
| HOST-023 | An AG-UI tool result separates the UI payload from the LLM text | Optional | Recommended | Hosting |
| HOST-024 | AG-UI persistence separates the threadId from the snapshot scope | Optional | Required | Hosting |
| HOST-025 | The AG-UI host binder provides SSE transport and a stream-complete save | Optional | Required | Hosting |
| HOST-026 | Foundry hosting is an optional adapter and separates Responses from Invocations | Optional | Required | Optional |
| HOST-027 | The hosted Foundry path validates the platform context and marks the local path explicitly | Optional | Required | Hosting |
| HOST-028 | DevUI is kept as a development-only artifact | Optional | Recommended | Optional |
| HOST-029 | Channel and protocol adapters are split into per-protocol optional artifacts | Optional | Required | Optional |

---

### Hosting core

## HOST-001 The hosting model and the wire protocol are separated

**Requirement.** Java must separate the hosting model and the wire protocol into different artifacts
and APIs.

**Upstream comparison**

- .NET: Splits generic hosting, the ASP.NET seam, and protocol-specific helpers, but some protocol packages also carry a route-owning implementation.
- Python: Takes a helper-first design and separates protocol helpers from generic hosting state more strictly.

**Decision.** The shared intent of the two upstreams is separation. Java adopts Python's stronger
boundary as the default and allows batteries-included routes only as an optional module.

**Acceptance criteria**

- The hosting core artifact does not depend directly on wire DTOs such as OpenAI Responses, A2A, or AG-UI.
- A protocol adapter can depend on the hosting core, but the hosting core does not refer back to a protocol adapter.

**Evidence** [20 hosting](../upstream/snapshots/d0a4165f/features/20-hosting.md),
[21 openai-responses-hosting](../upstream/snapshots/d0a4165f/features/21-openai-responses-hosting.md),
[22 a2a](../upstream/snapshots/d0a4165f/features/22-a2a.md),
[23 ag-ui](../upstream/snapshots/d0a4165f/features/23-ag-ui.md)

---

## HOST-002 The session contracts are owned by the API and the engine

**Requirement.** The AgentSession, SessionStore, and checkpoint contracts are owned by the API and
the engine, and hosting must not define them again.

**Upstream comparison**

- .NET: There are AgentSessionStore and HostedWorkflowState on the hosting side, but the contracts are used as protocol-neutral helpers.
- Python: SessionStore stays in the core and the hosting package exposes only execution-state helpers.

**Decision.** As the user requires, the boundary is narrowed further. If hosting owns the session
contracts again, the protocol and the store leak out to the host side, so Java fixes ownership with
the API and the engine.

**Acceptance criteria**

- Hosting-related modules do not create a separate public copy of SessionStore or AgentSession.
- The hosting public API uses only the session and checkpoint contracts it is given.

**Evidence** [20 hosting](../upstream/snapshots/d0a4165f/features/20-hosting.md),
[26 identity-session-routing](../upstream/snapshots/d0a4165f/features/26-identity-session-routing.md)

---

## HOST-003 The hosting core is responsible only for binding and state coordination

**Requirement.** The hosting core must be responsible only for target resolution, session load-save,
and workflow resume coordination.

**Upstream comparison**

- .NET: Ties together the target and the continuation through DI registration, AgentSessionStore, and HostedWorkflowState.
- Python: AgentState and WorkflowState provide only the target resolution and the session/workflow continuation seams.

**Decision.** This is the least common denominator where the two upstreams meet. Taking on auth,
status codes, and background executors beyond state coordination would make AgentEngine eat host
responsibilities.

**Acceptance criteria**

- The hosting core public types have no route declaration, HTTP status mapping, or authentication policy API.
- The hosting core exposes only the APIs that connect run values, the session snapshot, and the checkpoint cursor.

**Evidence** [20 hosting](../upstream/snapshots/d0a4165f/features/20-hosting.md)

---

## HOST-004 Target resolution supports instances, factories, async factories, and builders

**Requirement.** The hosting core must support target resolution based on a direct instance, a sync
factory, an async factory, and a builder, together with an explicit cache policy.

**Upstream comparison**

- .NET: AddAIAgent/AddWorkflow provide a custom factory and a lifetime choice.
- Python: AgentState/WorkflowState support instances, callables, awaitables, SupportsBuild, and cache_target on/off.

**Decision.** Both solve the same problem. Java adopts Python's breadth of input shapes together
with .NET's lifecycle selectivity to lower the cost of host assembly.

**Acceptance criteria**

- The same agent or workflow can be registered as an instance, a factory, an async factory, or a builder.
- The difference between the cache on and off settings is observable across repeated runs.

**Evidence** [20 hosting](../upstream/snapshots/d0a4165f/features/20-hosting.md)

---

## HOST-005 Session continuity guarantees a working copy and a single-creation rule

**Requirement.** A session load must return a working copy separate from the stored snapshot, and in
a create-on-miss race the session must be created only once per key.

**Upstream comparison**

- .NET: The AgentSessionStore contract returns an independent instance on each call and allows create-on-miss.
- Python: SessionStore uses deepcopy semantics and AgentState guarantees create-once with a per-session_id lock.

**Decision.** Both upstreams agree. Without a working copy, silent shared mutation appears, and
without blocking the create race the session is born twice on the first turn.

**Acceptance criteria**

- Before a store and a re-read, modifying the returned session is not reflected in the stored copy.
- Among the first requests arriving concurrently with the same new session key, session creation happens only once.

**Evidence** [20 hosting](../upstream/snapshots/d0a4165f/features/20-hosting.md)

---

## HOST-006 Workflow continuation follows restore-then-run with a durable fallback

**Requirement.** Workflow continuation must follow the restore-then-run-with-new-input rule and, when
there is no cursor, must fall back to the latest durable checkpoint.

**Upstream comparison**

- .NET: HostedWorkflowState implements restore-then-run and the latest checkpoint fallback directly.
- Python: WorkflowState does not store checkpoints itself, but the README and the spec assume an app-owned restore-then-run.

**Decision.** .NET is more concrete and Python assumes the same meaning. Java fixes this behavior as
the contract of the hosting workflow helper.

**Acceptance criteria**

- A resume call runs with the new input after restoring the previous checkpoint.
- Even when the in-memory cursor is empty, execution continues from the latest head when a durable checkpoint exists.
- When there is no checkpoint, it starts as a new execution.

**Evidence** [20 hosting](../upstream/snapshots/d0a4165f/features/20-hosting.md)

---

## HOST-007 In-memory continuity is kept as a development convenience only

**Requirement.** In-memory sessions and cursors must be provided only as a single-process development
convenience and must not be used as the default for transient or multi-replica deployments.

**Upstream comparison**

- .NET: The HostedWorkflowState documentation describes the in-memory cursor only as a common-case optimization.
- Python: The hosting ADR and README clearly distinguish a persistent single process from a transient host.

**Decision.** Both upstreams agree. The safer default is to leave "in-memory storage promises no
deployment continuity" in both the documentation and the tests.

**Acceptance criteria**

- The documentation and the tests state that in-memory storage does not guarantee multi-replica continuity.
- A durable session/checkpoint backend can be injected as a replaceable component.

**Evidence** [20 hosting](../upstream/snapshots/d0a4165f/features/20-hosting.md)

---

## HOST-008 Authentication, authorization, the single writer, and scaling are the host's responsibility

**Requirement.** Authentication, authorization, the same-session single writer, background work, and
the choice of scaling topology must be the responsibility of the host application.

**Upstream comparison**

- .NET: The samples and the ADR put routes, auth, store selection, and the single-writer policy on the host.
- Python: The hosting README pins routing, authentication, background work, and durable topology as app responsibilities.

**Decision.** The user's instruction is reflected as it is. AgentEngine must hold only the execution
contract, and hiding operational policy in a default implementation would make it dangerous
automation.

**Acceptance criteria**

- The generic hosting core has no default implementation of an authorization decision or a lock policy.
- The host binder exposes the background executor, the single-writer coordinator, and the durable store only as injection points.

**Evidence** [20 hosting](../upstream/snapshots/d0a4165f/features/20-hosting.md)

---

## HOST-009 A raw service session identifier is not an authorization boundary

**Requirement.** A raw session, thread, conversation, context, or task identifier emitted by a
protocol helper or a service must not be used as an authorization boundary, and the host must rebind
it to a principal-scoped key.

**Upstream comparison**

- .NET: Repeatedly warns not to treat sessionStoreId, ThreadId, and task/context ids as authorization tokens.
- Python: The helpers only extract a candidate key and leave it an opaque value until it becomes a trusted key.

**Decision.** This is the most important security principle. As the user requires, the rule that a
raw service session identifier is not used directly as an authorization boundary is fixed as its own
requirement.

**Acceptance criteria**

- The run parsing API and the trusted store key binding API are separated.
- In multi-user mode, persisted state cannot be opened with a key that has no principal or tenant dimension composed into it.

**Evidence** [20 hosting](../upstream/snapshots/d0a4165f/features/20-hosting.md),
[21 openai-responses-hosting](../upstream/snapshots/d0a4165f/features/21-openai-responses-hosting.md),
[22 a2a](../upstream/snapshots/d0a4165f/features/22-a2a.md),
[23 ag-ui](../upstream/snapshots/d0a4165f/features/23-ag-ui.md),
[26 identity-session-routing](../upstream/snapshots/d0a4165f/features/26-identity-session-routing.md)

---

## HOST-010 The Spring host binder is split into an optional module

**Requirement.** Spring MVC/WebFlux route binding, the SSE writer, HTTP error mapping, and the
principal-derived isolation helpers must live in an optional module separate from the hosting core.

**Upstream comparison**

- .NET: Keeps the host seam in separate packages such as Hosting.AspNetCore, Hosting.A2A.AspNetCore, and Hosting.AGUI.AspNetCore.
- Python: Prefers a helper-first seam that minimizes the framework package or writes app-owned routes directly.

**Decision.** In the Java ecosystem the Spring dependency is heavy. The .NET structure of a separate
host seam is therefore taken, while the generic core stays framework-independent as in Python.

**Acceptance criteria**

- The hosting core module does not carry a Spring dependency directly.
- Removing the Spring binder module still leaves the protocol adapters and the core tests intact.

**Evidence** [20 hosting](../upstream/snapshots/d0a4165f/features/20-hosting.md),
[22 a2a](../upstream/snapshots/d0a4165f/features/22-a2a.md),
[23 ag-ui](../upstream/snapshots/d0a4165f/features/23-ag-ui.md)

---

---

### OpenAI Responses

## HOST-011 The Responses adapter separates request parsing from session key extraction

**Requirement.** The OpenAI Responses adapter must separate the run request parsing API from the
continuation candidate key extraction API.

**Upstream comparison**

- .NET: Keeps ToAgentRunRequest and GetSessionStoreId as separate helpers.
- Python: Keeps responses_to_run and responses_session_id as separate helpers.

**Decision.** Both upstreams agree. This separation is what keeps "the request can be read" from
being confused with "state can be opened with that key".

**Acceptance criteria**

- Parsing the JSON body alone does not automatically perform a store lookup.
- The result of session key extraction stays a raw candidate value until it has passed host authorization.

**Evidence** [21 openai-responses-hosting](../upstream/snapshots/d0a4165f/features/21-openai-responses-hosting.md),
[26 identity-session-routing](../upstream/snapshots/d0a4165f/features/26-identity-session-routing.md)

---

## HOST-012 The default Responses mapping strictly rejects caller overrides

**Requirement.** The default OpenAI Responses request mapper must reject caller-supplied tools,
tool_choice, instructions, and sampling overrides by default.

**Upstream comparison**

- .NET: The default RunOptionsFactory implementation rejects the tools, tool_choice, instructions, and sampling fields as NotSupported.
- Python: Passes through many options apart from the transport fields, so a host allowlist is needed.

**Decision.** The safer default is taken. So that a self-hosted endpoint does not silently overwrite
the agent configuration, .NET's strict default is adopted as the Java default.

**Acceptance criteria**

- In the default configuration, an explicit validation error is raised when the caller sends tools or sampling values.
- The host can register a separate allowlist mapper to opt in only to the fields it needs.

**Evidence** [21 openai-responses-hosting](../upstream/snapshots/d0a4165f/features/21-openai-responses-hosting.md)

---

## HOST-013 Responses continuation distinguishes a branch pointer from a mutable head

**Requirement.** OpenAI Responses hosting must treat previous_response_id as an immutable branch
pointer and the conversation family as a stable mutable head.

**Upstream comparison**

- .NET: Explains that PreviousResponseId is not a stable partition key and that ConversationId is the stable key.
- Python: The README distinguishes the previous_response_id branch from the mutable conversation head directly.

**Decision.** Both upstreams agree. Mixing the branch and the head breaks parallel branching and the
single-writer serialization rule at the same time.

**Acceptance criteria**

- Even when two branches start from the same previous_response_id, their states do not mix.
- On the mutable conversation head path, the host must be able to apply single-writer coordination.

**Evidence** [21 openai-responses-hosting](../upstream/snapshots/d0a4165f/features/21-openai-responses-hosting.md)

---

## HOST-014 Responses SSE supports a minimal profile and an extended profile together

**Requirement.** OpenAI Responses streaming must guarantee the created-delta-terminal minimal profile
and, when needed, must additionally provide an itemized rich event profile.

**Upstream comparison**

- .NET: Has a full typed event union covering the response lifecycle, output items, content parts, function args, and reasoning deltas.
- Python: Provides a simple SSE helper centred on created, text delta, and completed or failed.

**Decision.** Two layers are needed to consider compatibility and implementation cost together. The
minimal profile is matched to the Python level, and the rich profile exposes .NET-level features as a
separate renderer option.

**Acceptance criteria**

- The simple profile always sends response.created and a terminal completed or failed.
- When there is text streaming, the delta order is preserved.
- Turning on the full profile can add function, reasoning, and media event families without loss.

**Evidence** [21 openai-responses-hosting](../upstream/snapshots/d0a4165f/features/21-openai-responses-hosting.md)

---

## HOST-015 The final Responses payload preserves the tool, reasoning, and media item families

**Requirement.** The final OpenAI Responses payload must express not only text but also the
function_call, function_call_output, reasoning, and media/file item families.

**Upstream comparison**

- .NET: Surfaces function call, reasoning, media, and approval events as a typed event union on the streaming path.
- Python: The final JSON renderer builds the reasoning, function_call, function_call_output, MCP, shell, approval, and media/file item families directly.

**Decision.** Both assume a surface beyond plain text. Java fixes the expressiveness of the final
payload at the Python level to prevent provider-neutral content loss.

**Acceptance criteria**

- The final JSON can carry function_call and function_call_output distinctly, in addition to assistant text.
- Reasoning and file or URI-family results are preserved without a text fallback.

**Evidence** [21 openai-responses-hosting](../upstream/snapshots/d0a4165f/features/21-openai-responses-hosting.md)

---

---

### A2A

## HOST-016 A remote A2A agent wrapper is provided as a first-class adapter

**Requirement.** Java must provide, as a first-class adapter, a wrapper that uses a remote A2A client
or AgentCard like a local Agent.

**Upstream comparison**

- .NET: Provides two bootstrap paths with IA2AClient.AsAIAgent and AgentCard.AsAIAgent.
- Python: A2AAgent supports the three paths of URL, agent_card, and an existing client.

**Decision.** This is a core feature both upstreams have. Pushing A2A away as a host-only feature
would weaken remote agent interoperability unnecessarily.

**Acceptance criteria**

- A remote A2A agent can be created from both a directly constructed client and a discovered card.
- Both immediate message and task responses are converted into the local agent response surface.

**Evidence** [22 a2a](../upstream/snapshots/d0a4165f/features/22-a2a.md)

---

## HOST-017 Local A2A exposure separates the helpers from the Spring binder

**Requirement.** When a local Agent or Workflow is exposed over A2A, the conversion helpers and the
Spring route binder must be separated.

**Upstream comparison**

- .NET: Keeps the A2A hosting package and the AspNetCore endpoint package apart.
- Python: Separates a helper-only package for assembling a native A2A server from the full executor path.

**Decision.** Helper-first is more portable. Java keeps a seam that lets the host assemble a native
A2A server as the default and keeps the Spring binder optional.

**Acceptance criteria**

- The A2A conversion/card helpers can be used without Spring.
- The Spring binder depends only on the helper layer and does not reimplement the protocol conversion.

**Evidence** [22 a2a](../upstream/snapshots/d0a4165f/features/22-a2a.md)

---

## HOST-018 A2A continuation is stored as a structured serviceSessionId

**Requirement.** A2A session continuation must be stored as a structured serviceSessionId that
carries contextId, taskId, and taskState.

**Upstream comparison**

- .NET: Preserves contextId, taskId, and taskState through a dedicated session type called A2AAgentSession.
- Python: Prefers A2AServiceSessionId and pushes A2AAgentSession into a deprecated compatibility layer.

**Decision.** The Python decision is more future-oriented. The value is stored as an adapter-owned
structured value so that the core does not have to know a dedicated session subclass.

**Acceptance criteria**

- serviceSessionId can store and restore both a simple string and a structured continuation.
- Generic telemetry or unrelated core code does not parse the A2A continuation fields directly.

**Evidence** [22 a2a](../upstream/snapshots/d0a4165f/features/22-a2a.md),
[26 identity-session-routing](../upstream/snapshots/d0a4165f/features/26-identity-session-routing.md)

---

## HOST-019 An A2A continuation token and a new user message are not accepted together

**Requirement.** A request that uses an A2A continuation token must not also carry a new user
message.

**Upstream comparison**

- .NET: Forbids using a ContinuationToken and user messages at the same time.
- Python: Separates the continuation_token path from the new task start path in the run API.

**Decision.** Both upstreams agree. Mixing continuation and new input in one request makes the
meaning of task resume and refinement ambiguous.

**Acceptance criteria**

- Giving a continuation token and new input at the same time raises a validation error.
- The poll or subscribe resume path is surfaced as a different API or mode from the fresh run path.

**Evidence** [22 a2a](../upstream/snapshots/d0a4165f/features/22-a2a.md)

---

## HOST-020 The A2A host distinguishes the message, task, and artifact lifecycles

**Requirement.** The A2A host must surface immediate messages, in-progress tasks, and artifact updates
as different lifecycles.

**Upstream comparison**

- .NET: The handler distinguishes a lightweight message from the task lifecycle depending on whether there is a continuation.
- Python: A2AExecutor builds task artifact updates with stable artifact ids and append semantics.

**Decision.** The heart of A2A is a task-aware surface. Flattening it like a text chat makes
long-running work and partial artifacts disappear.

**Acceptance criteria**

- An immediately completed response can end on the message surface, while work in progress exposes the task state and continuation information.
- Streaming chunks of the same logical response keep append semantics under a stable artifact id.

**Evidence** [22 a2a](../upstream/snapshots/d0a4165f/features/22-a2a.md)

---

---

### AG-UI

## HOST-021 The AG-UI adapter owns request aliases and resume normalization

**Requirement.** The AG-UI protocol adapter must accept both snake_case and camelCase aliases and
must normalize a legacy resume payload into the canonical interrupt-resume model.

**Upstream comparison**

- .NET: The repo-local implementation takes the AG-UI SDK RunAgentInput and only adds host concerns on top.
- Python: AGUIRequest implements the aliases and legacy resume coercion directly.

**Decision.** Regardless of whether an SDK exists, Java is better off keeping these normalization
rules in the protocol adapter itself. That is what keeps the host binder from reinterpreting the
request shape.

**Acceptance criteria**

- Aliases such as runId/run_id and threadId/thread_id are all allowed.
- A legacy resume shape is normalized into a canonical ResumeEntry array.

**Evidence** [23 ag-ui](../upstream/snapshots/d0a4165f/features/23-ag-ui.md)

---

## HOST-022 AG-UI state separates predictive deltas from deterministic snapshots

**Requirement.** The AG-UI state surface must provide predictive deltas and deterministic snapshots
through different APIs.

**Upstream comparison**

- .NET: The repo-local layer centres on preserving the raw StateSnapshotEvent and on session persistence.
- Python: PredictiveStateHandler and state_update() implement optimistic deltas and authoritative snapshots separately.

**Decision.** The Python implementation is more concrete and helps UI quality directly. Java also
separates the predictive path from the confirmed path to distinguish state jitter from the reflection
of an authoritative result.

**Acceptance criteria**

- A StateDeltaEvent can be built from tool args streaming alone.
- After the actual tool result, a separate deterministic snapshot API emits the authoritative state.

**Evidence** [23 ag-ui](../upstream/snapshots/d0a4165f/features/23-ag-ui.md)

---

## HOST-023 An AG-UI tool result separates the UI payload from the LLM text

**Requirement.** An AG-UI tool result must preserve the payload shown in the UI and the text result
the LLM reads back on different channels.

**Upstream comparison**

- .NET: The repo-local source concentrates on transport and continuity rather than tool event creation and does not implement the detailed payload split itself.
- Python: Implements a path that separates ToolCallResultEvent.content from the function result text directly.

**Decision.** To get UI friendliness and model stability at once, adopting the Python split is
better. Merging them into a single text contaminates the UI structure and the model context with each
other.

**Acceptance criteria**

- A tool result event can carry the display payload in a separate field.
- At the same time, the function result text to be re-injected into the LLM is not lost.

**Evidence** [23 ag-ui](../upstream/snapshots/d0a4165f/features/23-ag-ui.md)

---

## HOST-024 AG-UI persistence separates the threadId from the snapshot scope

**Requirement.** AG-UI persistence must separate the transport threadId from the snapshot scope,
which is an authorization boundary.

**Upstream comparison**

- .NET: Warns that using ThreadId as a bare persistent key is safe only for a single-user prototype.
- Python: Enforces snapshot_scope_resolver once snapshot persistence is switched on, and the workflow cache is keyed by scope together with thread.

**Decision.** This matches the user's requirement exactly. Trusting the threadId alone allows another
user's snapshot to be opened, so the scope resolver is kept as a required boundary.

**Acceptance criteria**

- When persistence is switched on, multi-user mode does not boot without a scope resolver.
- Even with the same threadId, a different scope does not share state or the workflow cache.

**Evidence** [23 ag-ui](../upstream/snapshots/d0a4165f/features/23-ag-ui.md),
[26 identity-session-routing](../upstream/snapshots/d0a4165f/features/26-identity-session-routing.md)

---

## HOST-025 The AG-UI host binder provides SSE transport and a stream-complete save

**Requirement.** The AG-UI host binder must handle SSE and keepalive as transport concerns and must
save the session at stream completion.

**Upstream comparison**

- .NET: Provides an SSE polyfill and SaveSessionAsync after stream completion.
- Python: The endpoint owns keepalive and the SSE headers, and the outer transport ends after the event stream ends.

**Decision.** This is the same separation of responsibilities. Keepalive is a transport option, and
the session save has to be tied to the completion of stream consumption to avoid committing partial
state.

**Acceptance criteria**

- The SSE headers and the keepalive interval are controlled by binder configuration.
- The session save happens after stream finalization, not right after the first delta.

**Evidence** [23 ag-ui](../upstream/snapshots/d0a4165f/features/23-ag-ui.md)

---

---

### Foundry, DevUI, and channels

## HOST-026 Foundry hosting is an optional adapter and separates Responses from Invocations

**Requirement.** Foundry hosting must be kept as an optional adapter rather than part of the core, and
must separate the Responses contract from the Invocations contract.

**Upstream comparison**

- .NET: Foundry.Hosting provides the Responses route and the toolbox bridge.
- Python: Provides ResponsesHostServer and InvocationsHostServer as separate surfaces.

**Decision.** Foundry is a contract specific to a managed runtime. Keeping it out of the core and
splitting the entrypoints per runtime contract is what reduces the test and deployment impact.

**Acceptance criteria**

- Core modules do not depend directly on the Foundry SDK or hosted runtime headers.
- Responses and Invocations are exposed as separate server surfaces or modules.

**Evidence** [25 foundry-devui-channels](../upstream/snapshots/d0a4165f/features/25-foundry-devui-channels.md)

---

## HOST-027 The hosted Foundry path validates the platform context and marks the local path explicitly

**Requirement.** The hosted Foundry path must validate the platform context and fail fast, and the
local development path must allow only an explicit fallback that is distinct from spoofable hosted
headers.

**Upstream comparison**

- .NET: Requires the call id and the user identity in a hosted environment and keeps a null-tolerant path for local.
- Python: validate_foundry_request_context enforces call_id and user_id in hosted mode.

**Decision.** Both upstreams agree. Letting hosted isolation be bypassed by imitating local headers
collapses the authorization boundary.

**Acceptance criteria**

- In hosted mode, a missing required platform context raises an explicit protocol error.
- Local mode is activated only through a separate configuration or code path and is not promoted by raw hosted headers alone.

**Evidence** [25 foundry-devui-channels](../upstream/snapshots/d0a4165f/features/25-foundry-devui-channels.md),
[26 identity-session-routing](../upstream/snapshots/d0a4165f/features/26-identity-session-routing.md)

---

## HOST-028 DevUI is kept as a development-only artifact

**Requirement.** DevUI and similar diagnostic UIs must be kept as development-only artifacts and must
not be made a default dependency of the production runtime.

**Upstream comparison**

- .NET: Provides DevUI and the Aspire DevUI integration as a preview dev surface.
- Python: DevServer provides a developer-facing UI and API but keeps auth, host binding, and CORS as explicit operational boundaries.

**Decision.** Both upstreams treat it as a development tool. Java takes the same separation so that
the production classpath and the attack surface are not widened unnecessarily.

**Acceptance criteria**

- Production hosting and the provider modules work normally even with the DevUI module excluded.
- The default DevUI access policy has restrictive defaults such as loopback only or an explicit remote enable.

**Evidence** [25 foundry-devui-channels](../upstream/snapshots/d0a4165f/features/25-foundry-devui-channels.md)

---

## HOST-029 Channel and protocol adapters are split into per-protocol optional artifacts

**Requirement.** Channel and protocol adapters such as ChatKit, Telegram, and DevUI must be split into
per-protocol optional artifacts and must not be bundled into a single all-channels bundle.

**Upstream comparison**

- .NET: Subdivides the host and protocol packages, as with Aspire DevUI, Hosting.AspNetCore, and A2A.AspNetCore.
- Python: Keeps ChatKit, hosting-telegram, hosting-mcp, hosting-a2a, and ag-ui as independent packages.

**Decision.** This is the direction the upstream inventory already shows. Because versions, risk, and
maturity differ per protocol, optional artifacts fit Java too.

**Acceptance criteria**

- Removing a channel adapter does not break the binary dependencies of the core or of the other protocol adapters.
- Each channel adapter is identified as a separate artifact in the documentation and the build configuration.

**Evidence** [25 foundry-devui-channels](../upstream/snapshots/d0a4165f/features/25-foundry-devui-channels.md),
[31 provider-integrations](../upstream/snapshots/d0a4165f/features/31-provider-integrations.md)

---

## What this document does not cover

| Topic | Owning document |
| --- | --- |
| The execution contract of Agent and ChatClient | [01 Agent execution and model calls](01-agent-execution.md) |
| The session serialization format and the internal structure of the stores | [06 Sessions and conversation state](06-sessions.md) |
| Workflow graphs and checkpoint semantics | [09 Workflows and orchestration](09-workflows.md) |
| Observability, the error taxonomy, packaging policy | [11 Operational quality](11-operations.md) |
| The provider inventory and general adapter priorities | [12 Provider integrations](12-providers.md) |
