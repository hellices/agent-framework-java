# 06 Sessions and conversation state

**Prefix** `SES` · **Upstream features** [08 sessions](../upstream/snapshots/d0a4165f/features/08-sessions.md),
[09 history-context-memory](../upstream/snapshots/d0a4165f/features/09-history-context-memory.md)

Defines contracts for session identification, snapshots, stores, per-run context, and
history/memory integration. Interceptor ordering and compaction are owned by
[07 Interceptors and context management](07-interceptors.md), the tool call loop by
[04 Tool definitions and the tool call loop](04-tools.md), and hosting boundaries by
[10 Hosting and protocols](10-hosting.md).

## Adoption scope

The `Grade` column in this document is, as [README](README.md#requirement-grades) defines it, how binding a requirement is once the decision to build the feature has been made; whether the feature is adopted at all follows the [compatibility matrix](../upstream/snapshots/d0a4165f/compatibility-matrix.md).

- Sessions and stores (`SES01`, `SES02`) and history/context (`CTX01`, `CTX02`) are all adoption `Required` as part of the core execution path.

## Summary

| ID | Requirement | Adoption | Grade | Phase |
| --- | --- | --- | --- | --- |
| SES-001 | The public session separates local ID from the service handle | Required | Required | MVP |
| SES-002 | The service conversation identifier is not an authorization boundary | Required | Required | MVP |
| SES-003 | The session is the single source of truth for persisted conversation state | Required | Required | MVP |
| SES-004 | Durable snapshots enforce a type-and-version envelope | Required | Required | Core+ |
| SES-005 | Session state is serialized via a stable type registry | Required | Required | Core+ |
| SES-006 | State values are strictly validated before durable storage | Required | Required | Core+ |
| SES-007 | The in-memory store returns branch-safe copies | Required | Required | Core+ |
| SES-008 | The file store replaces atomically and last writer wins | Required | Recommended | Core+ |
| SES-009 | The file-based session store prevents path traversal | Required | Required | Core+ |
| SES-010 | Parsing corruption and schema mismatch are distinguished for recovery | Required | Required | Core+ |
| SES-011 | Each run creates an independent `SessionContext` | Required | Required | MVP |
| SES-012 | Context providers manage state within a session namespace | Required | Required | Core+ |
| SES-013 | The history provider exposes optional load/store flags | Required | Required | Core+ |
| SES-014 | Default in-memory history injection conditions are strictly limited | Required | Recommended | Core+ |
| SES-015 | Per-service-call history storage is an explicit option | Required | Recommended | Core+ |
| SES-016 | Local history mode does not mix with an existing service conversation | Required | Required | Core+ |
| SES-017 | The message injection queue is attached to the session and preserves conversation continuity | Required | Required | Core+ |
| SES-018 | File history is stored in a channel separate from the session snapshot | Required | Recommended | Optional |
| SES-019 | External memory integrates with explicit scopes and untrusted context | Required | Recommended | Optional |
| SES-020 | Hosting isolation is handled by a separate user context | Required | Required | Hosting |

---

## SES-001 The public session separates local ID from the service handle

**Requirement.** The public session type must have the framework-owned `sessionId` and the
opaque `serviceSessionId` returned by the provider as separate fields.

**Upstream comparison**

- .NET: Separates local state from the service continuation handle via `AgentSession` and `ChatClientAgentSession.ConversationId`.
- Python: `AgentSession` directly exposes `session_id`, `service_session_id`, and `state`.

**Decision.** The intent is the same in both upstreams. Java adopts a separated public model like
Python. This avoids mixing the lifecycles of the local store key and the provider continuation
handle.

**Acceptance criteria**

- The session object always exposes a non-empty `sessionId`.
- `serviceSessionId` is optional, but when present it is stored in a different field from `sessionId`.
- The serialized session records the two values separately.

**Evidence** [08 state/snapshot](../upstream/snapshots/d0a4165f/features/08-sessions.md),
[08 Java design decisions](../upstream/snapshots/d0a4165f/features/08-sessions.md)

---

## SES-002 The service conversation identifier is not an authorization boundary

**Requirement.** `serviceSessionId` is internal metadata for continuation only and must not be
used as a basis for user isolation or authorization checks.

**Upstream comparison**

- .NET: Hosting boundaries are handled by `HostedSessionContext.UserId` and user-partitioned stores, not by `ConversationId`.
- Python: Explicitly states that `service_session_id` is trusted application state but not an authorization boundary.

**Decision.** Both upstreams agree. Using this value as an authorization boundary turns provider
format changes into security rules. The safer default is a separate host identity context.

**Acceptance criteria**

- The core session API contains no access-control logic based on `serviceSessionId`.
- Even with the same `serviceSessionId`, sessions are not treated as identical when host user contexts differ.
- Documentation and API naming do not describe `serviceSessionId` as an authorization token.

**Evidence** [08 errors/validation/security](../upstream/snapshots/d0a4165f/features/08-sessions.md),
[09 boundaries](../upstream/snapshots/d0a4165f/features/09-history-context-memory.md)

---

## SES-003 The session is the single source of truth for persisted conversation state

**Requirement.** Persisted conversation state must reside only in the session or in an external
store explicitly referenced by the session; session-specific state must not be hidden in provider
instance fields or global caches.

**Upstream comparison**

- .NET: Provider-local state is placed in `StateBag` and `ProviderSessionState<TState>`.
- Python: Provider state is placed in `AgentSession.state[source_id]` or an external backend, not in provider instances.

**Decision.** Both upstreams agree. The session must be the single source of truth for restart and
resumption to work. Placing state in instance fields may pass tests but evaporates at process
boundaries.

**Acceptance criteria**

- The only public extension points for storing per-session provider state are the session state namespace or an external store reference.
- Reusing the same provider instance across two sessions does not mix per-session state.
- After a process restart, restoring a session allows reading provider state from the previous turn.

**Evidence** [08 purpose/boundaries](../upstream/snapshots/d0a4165f/features/08-sessions.md),
[09 state/purpose](../upstream/snapshots/d0a4165f/features/09-history-context-memory.md),
[09 state/persistence](../upstream/snapshots/d0a4165f/features/09-history-context-memory.md)

---

## SES-004 Durable snapshots enforce a type-and-version envelope

**Requirement.** Durable session snapshots must use an envelope format containing at minimum
`type`, `version`, `sessionId`, `serviceSessionId`, and `state`.

**Upstream comparison**

- .NET: The inspected core session path is a plain JSON object round-trip; an explicit version envelope is not confirmed.
- Python: `_SessionSnapshot` uses a fixed envelope including `type="session"` and `version="1.0"`.

**Decision.** The Python approach is adopted. Without a version field, schema migration and error
classification are impossible. Java defaults to an envelope rather than a plain object for
long-term compatibility.

**Acceptance criteria**

- The top-level object of a serialized snapshot contains `type` and `version` fields.
- Deserialization checks `type` and `version` first.
- JSON and optional binary codecs share the same logical envelope.

**Evidence** [08 state/snapshot](../upstream/snapshots/d0a4165f/features/08-sessions.md),
[08 Java design decisions](../upstream/snapshots/d0a4165f/features/08-sessions.md)

---

## SES-005 Session state is serialized via a stable type registry

**Requirement.** Custom session state must be serialized through a registry that registers stable
type ID and codec pairs, and framework-owned default types must be pre-registered.

**Upstream comparison**

- .NET: `StateBag` provides typed access, but no Python-style public type-id registry is confirmed in the inspected core path.
- Python: `register_state_type()` enforces stable type id, codec pair, and framework default type pre-registration.

**Decision.** The Python approach is adopted. The default Java JSON mapper alone cannot safely
restore types during cold-start recovery. An explicit registry is more predictable.

**Acceptance criteria**

- The same type cannot be registered under two different type ids.
- The same type id cannot be reused for a different type.
- Framework default message types are serialized and restored without additional registration.

**Evidence** [08 public API/types](../upstream/snapshots/d0a4165f/features/08-sessions.md),
[08 extension points](../upstream/snapshots/d0a4165f/features/08-sessions.md),
[08 Java design decisions](../upstream/snapshots/d0a4165f/features/08-sessions.md)

---

## SES-006 State values are strictly validated before durable storage

**Requirement.** The durable store must reject unregistered runtime objects, invalid codec results,
and non-restorable values before persisting them.

**Upstream comparison**

- .NET: Warns about sensitive session restoration and provides a session type gate, but a Python-level durable-state validator is not confirmed in this scope.
- Python: Blocks duplicate type id, invalid encoder output, unsupported object, and non-finite float via fail-fast.

**Decision.** Python's strictness is adopted. If validation at save time is loose, corrupted
snapshots explode belatedly in production environments. The safer default is rejection before save.

**Acceptance criteria**

- Saving fails when an unregistered custom object is present in state.
- Saving fails when an encoder emits a value different from its declared type id.
- A failed save does not alter the existing snapshot file.

**Evidence** [08 errors/validation/security](../upstream/snapshots/d0a4165f/features/08-sessions.md),
[08 acceptance scenarios](../upstream/snapshots/d0a4165f/features/08-sessions.md)

---

## SES-007 The in-memory store returns branch-safe copies

**Requirement.** The in-memory session store must use copies at both save time and retrieval time
and must not return live references directly.

**Upstream comparison**

- .NET: An equivalent public in-memory session store contract is not confirmed in the inspected scope.
- Python: Both `SessionStore.set()` and `get()` use deepcopy to isolate branch-like continuations.

**Decision.** The Python approach is adopted. In Java too, the most common bug is corrupting the
stored value by modifying the retrieval result directly. Copy semantics must be the default for
safe session-branch re-execution.

**Acceptance criteria**

- Modifying a session object after saving does not change the value inside the store.
- Modifying a `get()` result and calling `get()` again returns the originally stored state.
- Querying a non-existent key returns `null` or an explicit absence indicator, not an empty copy.

**Evidence** [08 detailed execution flow](../upstream/snapshots/d0a4165f/features/08-sessions.md),
[08 concurrency/cancellation](../upstream/snapshots/d0a4165f/features/08-sessions.md),
[08 acceptance scenarios](../upstream/snapshots/d0a4165f/features/08-sessions.md)

---

## SES-008 The file store replaces atomically and last writer wins

**Requirement.** The file-based session store must write to a temporary file first and then commit
via atomic replacement; concurrent-write semantics are fixed at last-writer-wins.

**Upstream comparison**

- .NET: Prevents torn writes via temp file followed by overwrite rename and fixes last-writer-wins in documentation and tests.
- Python: `FileSessionStore` also uses temp file and replace; cross-process coordination is not performed.

**Decision.** Both upstreams agree. The core does not need to be responsible for distributed
locking. However, partial file exposure must be prevented. Java also defaults to simple,
verifiable atomic replacement.

**Acceptance criteria**

- Even if the process is interrupted during a save, a half-written snapshot file does not remain at the final path.
- When two writes to the same session race, the last successful write becomes the final state.
- The save implementation does not overwrite the existing file in place.

**Evidence** [08 detailed execution flow](../upstream/snapshots/d0a4165f/features/08-sessions.md),
[08 concurrency/cancellation](../upstream/snapshots/d0a4165f/features/08-sessions.md)

---

## SES-009 The file-based session store prevents path traversal

**Requirement.** The file-based session store must encode session IDs into safe filenames and
reject final paths that escape the store root.

**Upstream comparison**

- .NET: The hosted file store builds agent/user/conversation partition paths and tests for traversal rejection.
- Python: Implements safe filename stem and resolved-path containment checks, along with symlink escape rejection.

**Decision.** Both upstreams agree. Since session IDs can be external input, they must not be used
directly as filenames. Root containment checking is a required security rule for the Java file
store.

**Acceptance criteria**

- Saving with a session ID such as `../` does not create a file outside the root.
- Attempting to escape the root via symlinks causes both save and retrieval to fail.
- Unsafe session IDs are stored under an encoded filename.

**Evidence** [08 errors/validation/security](../upstream/snapshots/d0a4165f/features/08-sessions.md),
[08 acceptance scenarios](../upstream/snapshots/d0a4165f/features/08-sessions.md)

---

## SES-010 Parsing corruption and schema mismatch are distinguished for recovery

**Requirement.** Quarantine is performed only when snapshot bytes cannot be parsed; for version
mismatch, state schema mismatch, or decoder failure, the original file must be preserved and the
operation must fail.

**Upstream comparison**

- .NET: An equivalent quarantine policy in the core session path is not confirmed.
- Python: Only undecoded bytes are quarantined; version/schema/decoder issues leave the original intact.

**Decision.** The Python policy is adopted. Parsing corruption and compatibility errors require
different operational responses. Treating both as corruption loses recoverable snapshots.

**Acceptance criteria**

- On invalid JSON or binary parsing failure, the original file is moved to a quarantine location.
- When a read fails due to an unsupported `version`, the original file remains in place.
- When a read fails due to a decoder exception, the original file remains in place.

**Evidence** [08 detailed execution flow](../upstream/snapshots/d0a4165f/features/08-sessions.md),
[08 errors/validation/security](../upstream/snapshots/d0a4165f/features/08-sessions.md),
[08 Java design decisions](../upstream/snapshots/d0a4165f/features/08-sessions.md)

---

## SES-011 Each run creates an independent `SessionContext`

**Requirement.** Each run must create and use an independent `SessionContext` containing the
session ID, service handle, input messages, context messages, metadata, and response slot.

**Upstream comparison**

- .NET: Solves the same problem via separated objects such as `ChatHistoryProvider`, `AIContextProvider`, and `MessageInjectingChatClient`.
- Python: `SessionContext` is created fresh per run and gathers messages, middleware, metadata, and response in one place.

**Decision.** Java adopts Python's single run context as the default model. In Java's type system
an explicit context is needed for interceptors and providers to share the same execution unit.

**Acceptance criteria**

- Two runs use different `SessionContext` instances.
- `SessionContext` holds the final response object after the run completes.
- There is no path by which a provider hook or interceptor reads session information without a context being created.

**Evidence** [09 state/purpose](../upstream/snapshots/d0a4165f/features/09-history-context-memory.md),
[09 API/types](../upstream/snapshots/d0a4165f/features/09-history-context-memory.md),
[09 Java decisions](../upstream/snapshots/d0a4165f/features/09-history-context-memory.md)

---

## SES-012 Context providers manage state within a session namespace

**Requirement.** `ContextProvider` participates in a run only via `beforeRun` and `afterRun` hooks,
and per-provider persistent state must be stored only under a fixed namespace key in the session
state.

**Upstream comparison**

- .NET: Places provider state in the session state via the `AIContextProvider` and `ProviderSessionState<TState>` combination.
- Python: `ContextProvider` stores provider-scoped state in `AgentSession.state[source_id]`.

**Decision.** Both upstreams agree. If a provider holds session state in its own fields, session
resumption and parallel execution break. Fixing a namespace key makes it easy for stores and tests
to determine state ownership.

**Acceptance criteria**

- Provider implementations store per-session persistent state only under the session state namespace.
- The same provider functionality can be implemented with `beforeRun` and `afterRun` alone.
- Different provider source keys do not share the same state slot.

**Evidence** [09 state/purpose](../upstream/snapshots/d0a4165f/features/09-history-context-memory.md),
[09 extension points](../upstream/snapshots/d0a4165f/features/09-history-context-memory.md),
[09 state/persistence](../upstream/snapshots/d0a4165f/features/09-history-context-memory.md)

---

## SES-013 The history provider exposes optional load/store flags

**Requirement.** `HistoryProvider` must expose optional policy flags such as `loadMessages`,
`storeInputs`, `storeContextMessages`, `storeContextFrom`, and `storeOutputs` through a single
base history interface.

**Upstream comparison**

- .NET: `ChatHistoryProvider` separates load/store points but does not have a Python-style public flag set.
- Python: `HistoryProvider` expresses primary history, audit sink, and evaluation sink all through a single contract.

**Decision.** The Python approach is adopted. In Java too, reusing the same storage adapter for
primary history and audit sink is simpler. Without flags, similar provider types proliferate.

**Acceptance criteria**

- A single `HistoryProvider` implementation can be configured to store only inputs or only outputs.
- When `storeContextFrom` is specified, only context messages from that source are stored.
- Flag configuration is completed at the constructor or builder level without adding provider subtypes.

**Evidence** [09 state/purpose](../upstream/snapshots/d0a4165f/features/09-history-context-memory.md),
[09 API/types](../upstream/snapshots/d0a4165f/features/09-history-context-memory.md),
[09 Java decisions](../upstream/snapshots/d0a4165f/features/09-history-context-memory.md)

---

## SES-014 Default in-memory history injection conditions are strictly limited

**Requirement.** The default in-memory history provider must be auto-injected only when a session
exists, no load-enabled history provider is present, and no service-stored conversation exists.

**Upstream comparison**

- .NET: Separates the service-stored path from the local history provider path.
- Python: Production code auto-adds `InMemoryHistoryProvider` only under the above conditions.

**Decision.** The Python code and tests are followed. If auto-injection conditions are broad,
service-stored history and local history overlap. Per the principle that code overrides
documentation, the narrow conditions are fixed.

**Acceptance criteria**

- If a load-enabled history provider already exists, the default in-memory history is not added.
- If the service stores history for the session, the default in-memory history is not added.
- If both of the above conditions are false and a session exists, the default in-memory history is added.

**Evidence** [09 execution flow](../upstream/snapshots/d0a4165f/features/09-history-context-memory.md),
[09 documentation discrepancies](../upstream/snapshots/d0a4165f/features/09-history-context-memory.md)

---

## SES-015 Per-service-call history storage is an explicit option

**Requirement.** The behavior of storing history up to intermediate calls during the tool loop
must not be the default; per-service-call persistence is performed only when explicitly enabled.

**Upstream comparison**

- .NET: Opt-in via the `RequirePerServiceCallChatHistoryPersistence` option and a dedicated decorator.
- Python: The middleware owns per-call persistence only when `require_per_service_call_history_persistence=True`.

**Decision.** Both upstreams agree. Defaulting to end-of-run atomicity is simpler. Intermediate
storage is expensive and boundary-complex, so explicit opt-in is correct.

**Acceptance criteria**

- When the option is off, the history provider stores only at run completion.
- When the option is on, the history provider or the service conversation handle is updated after each service call.
- Even though the option's on and off states share the same session API, the only behavioral
  difference is the documented one.

**Evidence** [09 chat history persistence consistency](../upstream/snapshots/d0a4165f/features/09-history-context-memory.md),
[09 Java decisions](../upstream/snapshots/d0a4165f/features/09-history-context-memory.md)

---

## SES-016 Local history mode does not mix with an existing service conversation

**Requirement.** If a run with local-history-based per-service-call persistence already has a real
service conversation identifier, it must fail explicitly before execution begins.

**Upstream comparison**

- .NET: Fails with a conflict when a local history provider and a real `ConversationId` are simultaneously present.
- Python: Immediately errors in local-history mode when an existing service-managed conversation is present.

**Decision.** Both upstreams agree. Mixing both paths makes it ambiguous which history is the
source of truth. The safer default is fast failure rather than silent mixing.

**Acceptance criteria**

- In local per-call persistence mode, the presence of a real service conversation ID causes failure before the model call.
- On failure, no new history is stored in either the local history provider or the service.
- In a mode where the service stores history, the above check does not apply.

**Evidence** [09 Python: per-service-call history consistency](../upstream/snapshots/d0a4165f/features/09-history-context-memory.md),
[09 errors/security](../upstream/snapshots/d0a4165f/features/09-history-context-memory.md),
[09 acceptance scenarios](../upstream/snapshots/d0a4165f/features/09-history-context-memory.md)

---

## SES-017 The message injection queue is attached to the session and preserves conversation continuity

**Requirement.** The queue for inserting additional messages between model calls must be stored in
session state; follow-up calls continue until the queue is drained, and the latest service
conversation handle is passed to the next call.

**Upstream comparison**

- .NET: `MessageInjectingChatClient` owns the session `StateBag` queue and `ConversationId` propagation.
- Python: Places `message_injection.pending_messages` in session state and performs queue drain and follow-up calls in an internal loop.

**Decision.** Both upstreams agree. If the queue lives outside the session, state is broken at
resumption time and during parallel calls. Continuity handle propagation must also be fixed as a
session-driven rule.

**Acceptance criteria**

- Messages placed in the queue are included in the next model call.
- If there is no actionable follow-up but new messages remain in the queue, an internal follow-up call fires again.
- If the first call creates a new `serviceSessionId`, the next follow-up call uses that value.

**Evidence** [09 API/types](../upstream/snapshots/d0a4165f/features/09-history-context-memory.md),
[09 Python: message injection queue](../upstream/snapshots/d0a4165f/features/09-history-context-memory.md),
[09 .NET: split pipeline](../upstream/snapshots/d0a4165f/features/09-history-context-memory.md)

---

## SES-018 File history is stored in a channel separate from the session snapshot

**Requirement.** When file-based history storage is needed, it must not be appended to the session
snapshot file; an append-only history channel must be operated in a separate format.

**Upstream comparison**

- .NET: A separate core file history channel is not confirmed in the inspected scope.
- Python: `FileHistoryProvider` uses a JSONL or length-prefixed MessagePack append-only file.

**Decision.** The Python approach is adopted. Mixing the session snapshot and append log in a
single file blurs recovery and compaction boundaries. Java separates the two channels that serve
different storage purposes.

**Acceptance criteria**

- The session snapshot file and the history file differ in format or path convention.
- History storage is append-only and does not modify existing messages in place.
- History file corruption does not directly break the session snapshot restoration path.

**Evidence** [09 state/persistence](../upstream/snapshots/d0a4165f/features/09-history-context-memory.md),
[09 Java decisions](../upstream/snapshots/d0a4165f/features/09-history-context-memory.md)

---

## SES-019 External memory integrates with explicit scopes and untrusted context

**Requirement.** External memory providers must receive storage scope and retrieval scope
separately, and retrieved memories must be injected as untrusted context messages rather than
instructions.

**Upstream comparison**

- .NET: Foundry, Mem0, and chat-history memory providers follow explicit scope and user-message injection patterns.
- Python: Mem0, CosmosMemory, and FoundryMemory separate storage scope from retrieval scope and inject as untrusted context messages.

**Decision.** Both upstreams agree. Promoting retrieval results to instructions collapses the
indirect prompt injection boundary. Scope separation is also a safe default.

**Acceptance criteria**

- Memory providers receive storage scope and retrieval scope as separate arguments or have separate initialization policies.
- Retrieval results enter the context message channel, not the system/instruction channel.
- If scope initialization fails, the provider throws an explicit exception or becomes inactive.

**Evidence** [09 boundaries](../upstream/snapshots/d0a4165f/features/09-history-context-memory.md),
[09 extension points](../upstream/snapshots/d0a4165f/features/09-history-context-memory.md),
[09 common boundaries of provider memory (Mem0/Cosmos/Foundry etc.)](../upstream/snapshots/d0a4165f/features/09-history-context-memory.md),
[09 Java decisions](../upstream/snapshots/d0a4165f/features/09-history-context-memory.md)

---

## SES-020 Hosting isolation is handled by a separate user context

**Requirement.** The multi-user hosting module must isolate sessions via a write-once user context
and user-partitioned store; this responsibility must not be delegated to the core `AgentEngine`
or `serviceSessionId`.

**Upstream comparison**

- .NET: `HostedSessionContext` and user-partitioned `AgentSessionStore` handle this responsibility.
- Python: The core session only defines that it is not an auth boundary; an equivalent hosted identity runtime is not confirmed in this scope.

**Decision.** Only the `.NET` hosting boundary is adopted, but responsibility is limited to the
Hosting phase. User isolation is the host's job. If the core knows tenant policies, boundaries
blur.

**Acceptance criteria**

- The hosting module stores sessions under a per-user partition.
- Resuming the same session under a different user context fails explicitly.
- The core session type does not make a host user ID field required.

**Evidence** [08 detailed execution flow](../upstream/snapshots/d0a4165f/features/08-sessions.md),
[08 errors/validation/security](../upstream/snapshots/d0a4165f/features/08-sessions.md),
[08 Java design decisions](../upstream/snapshots/d0a4165f/features/08-sessions.md)

---

## What this document does not cover

| Topic | Owning document |
| --- | --- |
| Interceptor ordering and streaming boundaries | [07 Interceptors and context management](07-interceptors.md) |
| Tool definitions, tool call loop, and approval flows | [04 Tool definitions and the tool call loop](04-tools.md) |
| How the harness assembles default providers | [08 Harness features](08-harness.md) |
| Workflow checkpoints and long-running restoration | [09 Workflows and orchestration](09-workflows.md) |
| HTTP request identification and hosting protocols | [10 Hosting and protocols](10-hosting.md) |
