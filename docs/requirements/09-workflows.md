# 09 Workflows and orchestration

**Prefix** `WF` · **Upstream features** [14 workflow-graph](../upstream/snapshots/d0a4165f/features/14-workflow-graph.md),
[15 workflow-runtime](../upstream/snapshots/d0a4165f/features/15-workflow-runtime.md),
[16 workflow-checkpoint-hitl](../upstream/snapshots/d0a4165f/features/16-workflow-checkpoint-hitl.md),
[17 workflow-composition](../upstream/snapshots/d0a4165f/features/17-workflow-composition.md),
[18 orchestrations](../upstream/snapshots/d0a4165f/features/18-orchestrations.md),
[19 declarative](../upstream/snapshots/d0a4165f/features/19-declarative.md)

Defines the contracts of the workflow subproject for graph definition, the runtime, checkpoints,
human intervention, composition, orchestration, and the declarative surface. The phase in this
document is `Workflow` almost everywhere. Harness assembly and hosting protocols are owned by other
documents.

## Adoption scope

The `Grade` column in this document is, as [README](README.md#requirement-grades) defines it, how binding a requirement is once the decision to build the feature has been made; whether the feature is adopted at all follows the [compatibility matrix](../upstream/snapshots/d0a4165f/compatibility-matrix.md).

- The workflow core (`WF01`–`WF06`) has adoption `Required`.
- Orchestration (`ORC01`, `ORC02`) and declarative workflows (`DEC01`) have adoption `Optional`.

## Summary

| ID | Requirement | Adoption | Grade | Phase |
| --- | --- | --- | --- | --- |
| WF-001 | The graph definition and the execution runtime are separated | Required | Required | Workflow |
| WF-002 | Executor route registration prefers the annotation processor path | Required | Recommended | Workflow |
| WF-003 | The build is rejected when an unbound executor remains | Required | Required | Workflow |
| WF-004 | The build is rejected when an executor is unreachable from the start point | Required | Required | Workflow |
| WF-005 | Output designation rejects empty, duplicate, and overlapping sets at build time | Required | Required | Workflow |
| WF-006 | The runtime edge kinds are fixed as `DIRECT`, `FAN_OUT`, and `FAN_IN` | Required | Required | Workflow |
| WF-007 | Edge execution semantics are fixed as a conditional drop and a fan-in barrier | Required | Required | Workflow |
| WF-008 | Serialized callables are not restored without explicit rebinding | Required | Required | Workflow |
| WF-009 | Workflow execution is controlled through a first-class run handle | Required | Required | Workflow |
| WF-010 | State is exposed both by polling and by status events | Required | Recommended | Workflow |
| WF-011 | The superstep lifecycle and the checkpoint order are fixed | Required | Required | Workflow |
| WF-012 | Shared state has both a scoped API and pending/committed buffers | Required | Required | Workflow |
| WF-013 | Multiple writes to the same state key are treated as an explicit failure | Required | Required | Workflow |
| WF-014 | Message sending propagates the trace context and blocks reserved event spoofing | Required | Required | Workflow |
| WF-015 | The public cancel API and stream consumer cancellation are separated | Required | Required | Workflow |
| WF-016 | A new message is rejected by default while a pending request remains | Required | Required | Workflow |
| WF-017 | The failure event order is executor failure followed by workflow failure | Required | Required | Workflow |
| WF-018 | A checkpoint carries the full state needed for continuation | Required | Required | Workflow |
| WF-019 | Restore validates the signature and drains stale events first | Required | Required | Workflow |
| WF-020 | Determining the latest checkpoint relies on an ordering contract | Required | Required | Workflow |
| WF-021 | Pending requests are republished after restore without being duplicated | Required | Required | Workflow |
| WF-022 | An external response is validated on both the request id and the port id | Required | Required | Workflow |
| WF-023 | Approval resumption takes the `original_request` payload as the source of truth | Required | Required | Workflow |
| WF-024 | The file checkpoint store provides path and deserialization safeguards by default | Required | Required | Workflow |
| WF-025 | The run handle provides resume and pending request query APIs | Required | Recommended | Workflow |
| WF-026 | Sub-workflow composition follows the host executor ownership model | Required | Required | Workflow |
| WF-027 | Child output, intermediate, and request propagation policies are configured explicitly | Required | Required | Workflow |
| WF-028 | The workflow-as-agent session serializes the continuation state as well | Required | Required | Workflow |
| WF-029 | Functional workflows are split into a separate experimental API | Required | Recommended | Workflow |
| WF-030 | Orchestration has a shared output designation helper and explicit default policies | Optional | Required | Workflow |
| WF-031 | Sequential and concurrent provide per-pattern contracts and a request-info wrapper | Optional | Recommended | Workflow |
| WF-032 | Handoff has a default mesh, capability validation, filtering, and pending-request blocking | Optional | Required | Workflow |
| WF-033 | Group-chat has a single orchestrator contract and a no-self-echo rule | Optional | Required | Workflow |
| WF-034 | Magentic has a single manager contract and a plan review and replan flow | Optional | Required | Workflow |
| WF-035 | Declarative workflows have a separated module, safe state paths, and a typed handler SPI | Optional | Required | Workflow |

### Graph

---

## WF-001 The graph definition and the execution runtime are separated

**Requirement.** Java workflows must separate the immutable graph definition and the mutable
run/runtime into different types.

**Upstream comparison**

- .NET: `WorkflowBuilder` creates the definition and `Workflow` plus `Run`/`StreamingRun` handle the execution.
- Python: `WorkflowBuilder` creates a `Workflow` and the runtime executes it with a separate runner/context.

**Decision.** The direction is the same. Definition and execution must be separated so that
checkpoint compatibility can be judged by graph identity. When the definition object has an
independent identity, the restore, observability, and reflection APIs can be simplified.

**Acceptance criteria**

- The definition object produced by the build does not itself hold the messages and state of a running execution.
- Starting an execution creates a separate run handle or runner state object.
- The checkpoint compatibility check is based on the definition signature, not on the current runtime.

**Evidence** [14 graph](../upstream/snapshots/d0a4165f/features/14-workflow-graph.md), [15 runtime](../upstream/snapshots/d0a4165f/features/15-workflow-runtime.md)

---

## WF-002 Executor route registration prefers the annotation processor path

**Requirement.** The primary path for Java executor route registration must be annotation
processor-generated code, not runtime reflection.

**Upstream comparison**

- .NET: A Roslyn generator generates the route and protocol code.
- Python: Reads the `@handler` annotation through runtime introspection.

**Decision.** Java takes the .NET path. Considering cold start, validation quality, and AOT
friendliness together, generated code is better. Python-style introspection is convenient, but
making a reflection-only path the standard is expensive in Java.

**Acceptance criteria**

- The official guide documents the annotation processor registration path as the default path.
- The generated code includes the input type, the output type, and the route registration.
- Even when a reflection-only path exists, it is treated only as a secondary path.

**Evidence** [14 Executor protocol and type system](../upstream/snapshots/d0a4165f/features/14-workflow-graph.md), [19 differences between the .NET generator / source generator and the Python implementation](../upstream/snapshots/d0a4165f/features/19-declarative.md)

---

## WF-003 The build is rejected when an unbound executor remains

**Requirement.** When an executor without a concrete binding remains in the graph definition, the
build must fail.

**Upstream comparison**

- .NET: Tracks placeholder/unbound executors and rejects them in `Build()`.
- Python: The builder calls the graph validator before creating the final `Workflow`.

**Decision.** The .NET tests are more direct, so that contract is adopted. Allowing an unbound
executor hides the error until the first message arrives. Failing at the build stage is clearer.

**Acceptance criteria**

- If even one unbound executor remains, the build ends with an exception.
- A failed definition object does not create an execution start point.
- The error message includes an identifier for which executor was not bound.

**Evidence** [14 Graph validation](../upstream/snapshots/d0a4165f/features/14-workflow-graph.md)

---

## WF-004 The build is rejected when an executor is unreachable from the start point

**Requirement.** When an executor that cannot be reached from the start executor remains in the
definition, the build must fail.

**Upstream comparison**

- .NET: Rejects unreachable executors with a BFS-based reachability check.
- Python: The validator takes graph connectivity as a validation target.

**Decision.** Both upstreams agree. Leaving dead nodes makes output designation and checkpoint state
needlessly complicated. A workflow must be a connected graph.

**Acceptance criteria**

- If an executor cannot be reached from the start point, the build fails.
- Every path connected by direct, fan-out, and fan-in is a validation target.
- The reachability check finishes before execution.

**Evidence** [14 Graph validation](../upstream/snapshots/d0a4165f/features/14-workflow-graph.md)

---

## WF-005 Output designation rejects empty, duplicate, and overlapping sets at build time

**Requirement.** An explicit output/intermediate designation must fail at build time when it is
empty, duplicated, or overlapping.

**Upstream comparison**

- .NET: The builder decides the default designation, but validation is limited.
- Python: Rejects the build when the explicit output designation is empty, duplicated, or overlapping.

**Decision.** Python's stricter validation is adopted. When the output visibility contract is
ambiguous, the agent adapter and the orchestration terminal/intermediate semantics break
immediately.

**Acceptance criteria**

- If the explicit output set is empty, the build fails.
- If the same executor appears in both output and intermediate at once, the build fails.
- A duplicate designation makes the build fail.

**Evidence** [14 Graph validation](../upstream/snapshots/d0a4165f/features/14-workflow-graph.md), [18 orchestration](../upstream/snapshots/d0a4165f/features/18-orchestrations.md)

---

## WF-006 The runtime edge kinds are fixed as `DIRECT`, `FAN_OUT`, and `FAN_IN`

**Requirement.** The Java runtime edge model must expose only the three kinds `DIRECT`, `FAN_OUT`,
and `FAN_IN`, and must handle switch or multi-selection through builder lowering.

**Upstream comparison**

- .NET: Limits the runtime edge kinds to `Direct`, `FanOut`, and `FanIn`.
- Python: Has a richer core edge hierarchy, including `SwitchCaseEdgeGroup`.

**Decision.** Java takes .NET's narrow runtime model. The simpler the runtime state and
observability are, the better. The richness of the DSL can be lowered by the builder.

**Acceptance criteria**

- The public runtime edge enum has no switch-specific kind.
- A switch or multi-selection builder API is lowered into a fan-out-family structure after the build.
- Checkpoint state can be serialized assuming only the three edge kinds.

**Evidence** [14 public API/types](../upstream/snapshots/d0a4165f/features/14-workflow-graph.md), [14 Java decisions](../upstream/snapshots/d0a4165f/features/14-workflow-graph.md)

---

## WF-007 Edge execution semantics are fixed as a conditional drop and a fan-in barrier

**Requirement.** When the condition of a direct edge is false the message is dropped but this is not
seen as a routing error, and fan-in must run the sink only after every source has emitted at least
one message.

**Upstream comparison**

- .NET: Treats a false direct edge condition as a drop and the fan-in runner keeps a per-source buffer.
- Python: Treats a false single edge condition as a drop and the fan-in buffer decides when it is ready.

**Decision.** Both upstreams agree. A false condition is a normal routing outcome, and barrier
semantics are the heart of fan-in. Misreading either as a failure breaks workflow convergence and
checkpoint reproducibility.

**Acceptance criteria**

- When a direct edge condition is false, no downstream delivery happens, but no workflow failure happens either.
- The fan-in sink does not run before every source has emitted a message.
- Once fan-in is ready, the sink is activated only once per source set.

**Evidence** [14 Edge groups and routing execution](../upstream/snapshots/d0a4165f/features/14-workflow-graph.md)

---

## WF-008 Serialized callables are not restored without explicit rebinding

**Requirement.** When edges and workflow definitions are serialized, live callables must not be
stored, and on restore a symbolic name must be used only when it is rebound to an explicit registry.

**Upstream comparison**

- .NET: Restore compatibility checks the workflow signature, but the callable restoration surface is narrow.
- Python: Stores only the name instead of the callable and installs a fail-closed placeholder when restoration is impossible.

**Decision.** Python's fail-closed model is taken. The most dangerous outcome is a serialized
definition quietly performing different routing. Without registry rebinding it must fail explicitly.

**Acceptance criteria**

- A live lambda or method reference is not stored as such in the serialized definition.
- If a needed callable cannot be found after restore, an explicit exception is raised on the first call.
- Automatic fallback routing or a silent no-op restoration is not allowed.

**Evidence** [14 state/persistence](../upstream/snapshots/d0a4165f/features/14-workflow-graph.md), [14 Java decisions](../upstream/snapshots/d0a4165f/features/14-workflow-graph.md)

### Runtime

---

## WF-009 Workflow execution is controlled through a first-class run handle

**Requirement.** Java workflow execution must expose a first-class run handle that bundles
streaming, status queries, response submission, resumption, and cancellation.

**Upstream comparison**

- .NET: `Run` and `StreamingRun` split `GetStatusAsync`, `ResumeAsync`, `SendResponseAsync`, and `CancelRunAsync`.
- Python: A single `Workflow.run(...)` takes streaming and non-streaming, responses, and checkpoint restore all together.

**Decision.** Java takes .NET's explicit handle structure but gathers the control plane into one
handle. The more explicit operational control is, the better, and a unified overload alone blurs the
resumption and response submission contracts.

**Acceptance criteria**

- A workflow run handle type exists in the public API.
- The handle has status query, response submission, cancellation, and stream consumption APIs.
- Even when a non-streaming helper exists, it is implemented internally on the same handle contract.

**Evidence** [15 runtime](../upstream/snapshots/d0a4165f/features/15-workflow-runtime.md), [16 Java decisions](../upstream/snapshots/d0a4165f/features/16-workflow-checkpoint-hitl.md)

---

## WF-010 State is exposed both by polling and by status events

**Requirement.** Java must expose the run state through both a polling API and explicit status
events.

**Upstream comparison**

- .NET: State centers on `GetStatusAsync()` polling.
- Python: `status` events and `status_timeline()` are central.

**Decision.** The compromise of providing both together is the most practical. Polling is convenient
for the operational control plane, and status events are convenient for auditing and telemetry.

**Acceptance criteria**

- The current state can be checked synchronously from the run handle.
- The stream or the result object includes state transition events.
- The final state agrees between the polling result and the status timeline.

**Evidence** [15 messages, events, and the state timeline](../upstream/snapshots/d0a4165f/features/15-workflow-runtime.md), [15 Java decisions](../upstream/snapshots/d0a4165f/features/15-workflow-runtime.md)

---

## WF-011 The superstep lifecycle and the checkpoint order are fixed

**Requirement.** The common lifecycle of one superstep must be fixed in the order start event,
message delivery and joined work execution, state commit, checkpoint, completion event.

**Upstream comparison**

- .NET: After the superstep starts, it proceeds through deliveries and joined subworkflows, the checkpoint, and the complete event.
- Python: Proceeds through `superstep_started`, iteration execution, state commit, checkpoint, and `superstep_completed`.

**Decision.** Both upstreams agree. If this order changes, the events and the checkpoint point at
different realities. That matters especially in a resumable system.

**Acceptance criteria**

- `superstep_started` comes before `superstep_completed`.
- When the completion event has appeared, that step's state commit and checkpoint are already finished.
- A started event later than the checkpoint of the same step does not appear again with the same step number.

**Evidence** [15 Superstep / iteration loop](../upstream/snapshots/d0a4165f/features/15-workflow-runtime.md)

---

## WF-012 Shared state has both a scoped API and pending/committed buffers

**Requirement.** The shared state API must be able to express the executor scope, and the internal
implementation must separate the pending and committed buffers and commit only at a superstep
boundary.

**Upstream comparison**

- .NET: Has a state API that takes a scope name and a staged update publish model.
- Python: Has a flat key API but two buffer layers, `_pending` and `_committed`.

**Decision.** Java combines .NET's scoped API with Python's two-layer buffer model. A scope is needed
to handle conflicts in a multi-agent workflow, and the pending/committed separation is what makes
superstep atomicity hold.

**Acceptance criteria**

- The public state API can express at least an executor or scope unit.
- A value written inside the same step stays only in the pending buffer until the commit.
- The value read at the start of the next superstep is the result of the previous commit.

**Evidence** [15 Shared state and the run context](../upstream/snapshots/d0a4165f/features/15-workflow-runtime.md)

---

## WF-013 Multiple writes to the same state key are treated as an explicit failure

**Requirement.** When conflicting multiple writes happen in the same superstep, Java must report an
explicit runtime failure instead of quietly overwriting with last-write-wins.

**Upstream comparison**

- .NET: Has a test that surfaces a fanned-out multiple writer conflict as a runtime error.
- Python: Describes several writes in the same step on flat state as last write wins.

**Decision.** The safer default is taken, so the .NET contract is adopted. Last-write-wins creates
silent loss under parallel fan-out. It is better to surface a conflict as a failure.

**Acceptance criteria**

- When a conflicting write to the same key happens, the workflow raises an explicit error.
- The state commit succeeds only when there is no conflict.
- The conflict error makes it possible to identify which key and which writers conflicted.

**Evidence** [14 errors/validation/security](../upstream/snapshots/d0a4165f/features/14-workflow-graph.md), [15 .NET tests](../upstream/snapshots/d0a4165f/features/15-workflow-runtime.md)

---

## WF-014 Message sending propagates the trace context and blocks reserved event spoofing

**Requirement.** The runtime `send_message` must put the trace context into the envelope, and when an
executor directly spoofs a reserved event such as `output`, `intermediate`, or a lifecycle event, the
framework must reject it.

**Upstream comparison**

- .NET: `SendMessageAsync` injects a trace carrier.
- Python: `send_message()` injects the trace context and `add_event()` turns a reserved event into a warning.

**Decision.** Both contracts are carried together. Trace propagation is basic to observability, and
reserved event protection is basic to the integrity of the event channel.

**Acceptance criteria**

- When a message sent by an executor has no trace context, it is detectable in observability mode.
- When an executor puts a reserved event type in directly, the original event is ignored or replaced by a warning.
- Output/intermediate events created by the framework can be distinguished from events spoofed by an executor.

**Evidence** [14 errors/validation/security](../upstream/snapshots/d0a4165f/features/14-workflow-graph.md), [15 message sending and trace context propagation](../upstream/snapshots/d0a4165f/features/15-workflow-runtime.md)

---

## WF-015 The public cancel API and stream consumer cancellation are separated

**Requirement.** The public `cancel()` that actually stops the workflow and the consumer
cancellation that stops stream consumption must have different effects.

**Upstream comparison**

- .NET: Cancelling `WatchStreamAsync()` only ends the stream, while workflow cancellation is handled by `CancelRunAsync()`.
- Python: No public cancel API is visible; only internal cancellation handling is visible.

**Decision.** Java adopts the .NET model. The authority of the consumer and of the execution owner
must be separated. If the workflow disappears just because stream consumption ended, hosting and UI
integration become difficult.

**Acceptance criteria**

- Cancelling the stream consumption token alone does not turn the workflow state into `CANCELLED` or terminated.
- Calling the public cancel API transitions the subsequent state to cancelled or terminated.
- A cancelled run is not reported as successful.

**Evidence** [15 concurrency/streaming/cancellation](../upstream/snapshots/d0a4165f/features/15-workflow-runtime.md)

---

## WF-016 A new message is rejected by default while a pending request remains

**Requirement.** Sending a new message to a run that still has a pending external request must be
rejected by default, and allowing it must require an explicit override or resume token.

**Upstream comparison**

- .NET: Designs around explicit resume/response paths.
- Python: Allows a fresh message with a warning even when a pending request exists.

**Decision.** Following the overlap policy the user emphasized, the safer side is taken. As the
Python tests show, an old response can be applied to a moved-on state. That must not be allowed by
default.

**Acceptance criteria**

- Sending a new message to a run with a pending request fails immediately in the default configuration.
- Unless the override mode is switched on, an old request response and a new message are not mixed in the same state.
- Even when a permissive mode exists, its use is explicit in the calling API.

**Evidence** [15 Java decisions](../upstream/snapshots/d0a4165f/features/15-workflow-runtime.md), [16 Java decisions](../upstream/snapshots/d0a4165f/features/16-workflow-checkpoint-hitl.md)

---

## WF-017 The failure event order is executor failure followed by workflow failure

**Requirement.** When an executor failure happens, `executor_failed` must be observed first and the
workflow-level failure must be promoted after it.

**Upstream comparison**

- .NET: Can surface both a workflow error event and an executor failure event.
- Python: Tests fix that `executor_failed` must appear before `failed`.

**Decision.** Python's more concrete event order is adopted. Debugging and retry policies have to
know the root cause executor first.

**Acceptance criteria**

- In the event stream of a failed run, the executor failure precedes the workflow failure.
- The executor failure event includes the id of the failed executor.
- The workflow failure event summarizes the same failure again but does not replace the executor failure.

**Evidence** [14 concrete acceptance scenarios](../upstream/snapshots/d0a4165f/features/14-workflow-graph.md), [15 concrete acceptance scenarios](../upstream/snapshots/d0a4165f/features/15-workflow-runtime.md)

### Checkpoints and human intervention

---

## WF-018 A checkpoint carries the full state needed for continuation

**Requirement.** The checkpoint payload must carry the workflow signature, the runner queue, the
pending external requests, the shared state, the edge state, and the executor snapshot together.

**Upstream comparison**

- .NET: `Checkpoint` carries workflow info, runner state, state data, edge state, and the parent.
- Python: `WorkflowCheckpoint` carries the signature hash, messages, state, pending requests, iteration, and lineage.

**Decision.** The same continuation contract is adopted. Designing checkpoints and HITL separately
makes it impossible to republish pending requests correctly after a restore.

**Acceptance criteria**

- A single checkpoint is enough to show the pending requests again after a restore.
- The shared state and the edge/executor state are restored together.
- A lineage or parent pointer exists so that the latest checkpoint chain can be traced.

**Evidence** [16 checkpoints and human intervention](../upstream/snapshots/d0a4165f/features/16-workflow-checkpoint-hitl.md)

---

## WF-019 Restore validates the signature and drains stale events first

**Requirement.** A checkpoint restore must first validate the current workflow definition signature,
and must apply the state after draining the existing event buffer.

**Upstream comparison**

- .NET: Drains the event stream buffer before the restore and checks workflow compatibility.
- Python: Rejects the restore when the `graph_signature_hash` differs.

**Decision.** The safeguards of both implementations are adopted. Restoring a checkpoint whose
definition has changed, or leaving stale events behind, makes the caller see two different
timelines.

**Acceptance criteria**

- If the current workflow signature and the checkpoint signature differ, the restore fails.
- Immediately after a restore, old events created after the checkpoint do not flow out again.
- The restore clears the event buffer before applying the state.

**Evidence** [14 state/persistence](../upstream/snapshots/d0a4165f/features/14-workflow-graph.md), [16 .NET checkpoint creation and restoration](../upstream/snapshots/d0a4165f/features/16-workflow-checkpoint-hitl.md)

---

## WF-020 Determining the latest checkpoint relies on an ordering contract

**Requirement.** The checkpoint store must return its index in oldest-first, newest-last order, and
a latest checkpoint query must use that last entry.

**Upstream comparison**

- .NET: States the store ordering contract and treats the last entry as the latest.
- Python: Has lineage and timestamps but emphasizes latest ordering less as a public contract.

**Decision.** .NET's stricter store contract is adopted. If the determination of the latest differs
between implementations, the restore API is not deterministic.

**Acceptance criteria**

- The checkpoint store contract documentation states oldest-first/newest-last.
- `latestCheckpoint()` uses the last element of the index.
- A store implementation that breaks the ordering contract fails in tests.

**Evidence** [16 .NET checkpoint creation and restoration](../upstream/snapshots/d0a4165f/features/16-workflow-checkpoint-hitl.md)

---

## WF-021 Pending requests are republished after restore without being duplicated

**Requirement.** Restoring from a checkpoint that has pending requests must republish the request
information, and a request that has already been handled must not be published again.

**Upstream comparison**

- .NET: Republishes pending requests after a resume and fixes duplicate-free completion with tests.
- Python: Does not re-emit a request_info that has already been answered on the `responses + checkpoint_id` path.

**Decision.** Both upstreams agree. The caller must be asked again after a restore, but must not be
made to process the same question twice.

**Acceptance criteria**

- Restoring a checkpoint with pending requests shows the same request id again.
- The state immediately after a restore reflects the pending request state.
- After a response has been provided for the same request, the same request_info is not exposed again.

**Evidence** [16 pending request republication and approval persistence](../upstream/snapshots/d0a4165f/features/16-workflow-checkpoint-hitl.md)

---

## WF-022 An external response is validated on both the request id and the port id

**Requirement.** An external response must be accepted only when not just the request id but also the
port id of the original request matches.

**Upstream comparison**

- .NET: Rejects a forged response whose request id is the same but whose port id differs.
- Python: Validates the pending request map and the response type, but the port-level correlation surface is thinner.

**Decision.** The stricter .NET contract is adopted. Checking only the request id lets a forged
response enter a different execution path.

**Acceptance criteria**

- A response with the same request id but a different port id is rejected.
- Even after a forged response has been rejected, a legitimate response can still be accepted later for the same request.
- A response with an unknown request id is rejected too.

**Evidence** [15 errors/validation/security](../upstream/snapshots/d0a4165f/features/15-workflow-runtime.md), [16 request-response core flow](../upstream/snapshots/d0a4165f/features/16-workflow-checkpoint-hitl.md)

---

## WF-023 Approval resumption takes the `original_request` payload as the source of truth

**Requirement.** Resumption of tool approval, MCP approval, and agent approval must use
`original_request` inside the response payload as the single source of truth, not the mutable
workflow state.

**Upstream comparison**

- .NET: Stores a function/MCP approval snapshot and uses that snapshot on resumption.
- Python: The declarative approval tests fix that only the `original_request` payload is trusted and stale state is ignored.

**Decision.** Python's stricter regression contract is adopted as the specification. Even if the
state changes while an approval is pending, the approved original request must not change. Swapping
of concurrent approvals must be blocked too.

**Acceptance criteria**

- Even when stale state has different arguments, the resumed execution uses the arguments from `original_request`.
- Even when two approval requests are pending at once, the responses are not swapped with each other.
- When an approval is denied, the original tool call is not executed.

**Evidence** [16 checkpoints and human intervention](../upstream/snapshots/d0a4165f/features/16-workflow-checkpoint-hitl.md), [19 declarative](../upstream/snapshots/d0a4165f/features/19-declarative.md)

---

## WF-024 The file checkpoint store provides path and deserialization safeguards by default

**Requirement.** The file checkpoint store must reject paths outside the storage root, and must
build in atomic writes and a restricted set of deserialization types by default.

**Upstream comparison**

- .NET: Provides file name escaping and an ordered index.
- Python: Provides `_validate_file_path`, atomic `os.replace`, and restricted decoding.

**Decision.** Java adopts Python's path validation and restricted decoding as the default and also
carries over file name normalization as in .NET. A file store is a security boundary.

**Acceptance criteria**

- If a checkpoint id resolves to a file outside the root, both storing and querying fail.
- Storing completes through an atomic replace-family operation.
- Loading does not deserialize objects outside the allowed type set.

**Evidence** [16 state/persistence](../upstream/snapshots/d0a4165f/features/16-workflow-checkpoint-hitl.md)

---

## WF-025 The run handle provides resume and pending request query APIs

**Requirement.** The run handle must provide the operational APIs corresponding to
`resumeFrom(checkpoint)`, `respond(requestId, payload)`, `listPendingRequests()`, and
`latestCheckpoint()`.

**Upstream comparison**

- .NET: A checkpointable run has the checkpoints, last checkpoint, and restore APIs directly.
- Python: Performs the same work through the `Workflow.run(checkpoint_id=..., responses=...)` combination.

**Decision.** Java takes a handle-centred control plane. For runtime operations, explicit methods are
safer than a combination of separate parameters.

**Acceptance criteria**

- The latest checkpoint identifier can be queried from the run handle.
- The pending request list can be queried together with request ids and type information.
- Checkpoint restore and response submission are exposed as separate methods.

**Evidence** [16 Java decisions](../upstream/snapshots/d0a4165f/features/16-workflow-checkpoint-hitl.md)

### Composition

---

## WF-026 Sub-workflow composition follows the host executor ownership model

**Requirement.** When a child workflow is inserted into a parent graph, a shared child instance must
not be reused directly; a host executor holding an ownership token must own the child lifecycle and
the response port translation.

**Upstream comparison**

- .NET: `SubworkflowBinding` and `WorkflowHostExecutor` use an ownership token and qualified ports.
- Python: `WorkflowExecutor` wraps a child workflow like an executor and reshapes requests/responses.

**Decision.** Java adopts .NET's ownership model. Sharing a child workflow like an ordinary executor
blurs the lifecycle, checkpoint, and overlap boundaries.

**Acceptance criteria**

- The parent does not directly share and run the child workflow instance.
- The child's external request ports are translated into qualified ids at the parent boundary.
- On restore, the child run stack and the pending response port state are restored as well.

**Evidence** [17 composition](../upstream/snapshots/d0a4165f/features/17-workflow-composition.md), [16 subworkflow request-response](../upstream/snapshots/d0a4165f/features/16-workflow-checkpoint-hitl.md)

---

## WF-027 Child output, intermediate, and request propagation policies are configured explicitly

**Requirement.** Sub-workflow composition must explicitly configure whether child output is sent as a
parent message or yielded as direct output, how child intermediates are shown, and whether the
parent intercepts child requests or propagates them outward.

**Upstream comparison**

- .NET: A host executor option forwards child output either as a message or as output.
- Python: `allow_direct_output` and `propagate_request` control this and intermediates are republished separately.

**Decision.** Python's more explicit public knobs are adopted. When what is terminal at the
composition boundary is hidden, the observability of the agent adapter and the parent workflow
becomes unstable.

**Acceptance criteria**

- The child output forwarding policy is surfaced as a public option.
- Child intermediate output can be kept as intermediate independently of the parent terminal policy.
- Child requests can choose between parent interception and external propagation.

**Evidence** [17 Python graph composition](../upstream/snapshots/d0a4165f/features/17-workflow-composition.md)

---

## WF-028 The workflow-as-agent session serializes the continuation state as well

**Requirement.** The workflow-as-agent session must serialize the checkpoint pointer, the pending
request map, and the option flags together, and must not put internal hidden yields into the final
agent response.

**Upstream comparison**

- .NET: `WorkflowSession` stores the last checkpoint and pending requests in the session.
- Python: The `Workflow.as_agent()` tests fix hidden yields suppression and intermediate forwarding.

**Decision.** The essentials of both implementations are combined. If the agent session does not know
the continuation state, multi-turn resumption is impossible, and exposing hidden yields lets the
internal graph implementation contaminate the user contract.

**Acceptance criteria**

- The session serialization payload includes the workflow checkpoint pointer and the pending request information.
- Hidden yields are not included in the body of the final agent response.
- If the internal agent id or name is not stable, checkpoint resumption fails.

**Evidence** [17 workflow-as-agent](../upstream/snapshots/d0a4165f/features/17-workflow-composition.md)

---

## WF-029 Functional workflows are split into a separate experimental API

**Requirement.** Functional workflows must be kept as an experimental API separate from graph
workflows, and must have a deterministic step cache, non-local control signals such as
`WorkflowInterrupted`, and a code-shape signature hash as their default contract.

**Upstream comparison**

- .NET: There is no equivalent public functional API in the inspected scope.
- Python: Provides `@workflow`, `@step`, `WorkflowInterrupted`, the step cache, and the signature hash as experimental features.

**Decision.** The difference in stability is large, so separation is right. Promising the functional
API at the same stability as the core graph would overstate checkpoint compatibility and streaming
semantics.

**Acceptance criteria**

- The functional workflow package carries a different experimental stability marker from graph workflows.
- The step cache key includes the step name and the call index.
- A restore fails when the workflow signature hash differs.
- `except Exception:` does not catch a HITL interruption.

**Evidence** [17 Python functional workflow API](../upstream/snapshots/d0a4165f/features/17-workflow-composition.md)

### Orchestration

---

## WF-030 Orchestration has a shared output designation helper and explicit default policies

**Requirement.** Every orchestration builder must share a common participant-output designation
helper, and must fix the default terminal/intermediate policy of each pattern as an explicit
constant or enum.

**Upstream comparison**

- .NET: `OrchestrationBuilderBase` and the per-pattern builders create the default designation implicitly.
- Python: `_participant_output_config.py` is responsible for the shared validation and designation computation.

**Decision.** Java pulls the shared helper out into a separate shared module and keeps the default
policy as an explicit contract rather than an implicit rule. Copying the current drift between the
upstreams as is would break parity.

**Acceptance criteria**

- Sequential, concurrent, handoff, group-chat, and magentic use the same designation helper.
- Each builder holds its own default output policy as a constant or enum identifiable in code.
- An unknown participant, an overlap, and an invalid designation fail through the shared rules.

**Evidence** [18 orchestration](../upstream/snapshots/d0a4165f/features/18-orchestrations.md)

---

## WF-031 Sequential and concurrent provide per-pattern contracts and a request-info wrapper

**Requirement.** The Sequential builder must provide a full-conversation default and a chain-only
option, the Concurrent builder must provide parallel fan-out and an aggregator contract, and both
must expose a generic request-info wrapper.

**Upstream comparison**

- .NET: Sequential provides full conversation versus chain-only, and concurrent provides a custom aggregator.
- Python: Both sequential and concurrent provide `with_request_info(...)` and support a concurrent callback aggregator.

**Decision.** Java separates the builder contracts of the two patterns but carries the request-info
wrapper over as a shared user experience. This wrapper is what makes the role difference from the
specialized handoff/magentic clear.

**Acceptance criteria**

- In the sequential default mode, the next participant receives the whole previous conversation.
- In the sequential chain-only mode, the next participant receives only the immediately preceding agent response.
- Concurrent runs the participants in parallel and converges on a single aggregator output.
- Both the sequential and the concurrent builder can apply the request-info wrapper to a particular participant subset.

**Evidence** [18 Sequential](../upstream/snapshots/d0a4165f/features/18-orchestrations.md), [18 Concurrent](../upstream/snapshots/d0a4165f/features/18-orchestrations.md)

---

## WF-032 Handoff has a default mesh, capability validation, filtering, and pending-request blocking

**Requirement.** The Handoff builder must create a default mesh topology when there is no explicit
graph, must validate participant capabilities at build time, must filter handoff call artifacts, and
must block handoff while a pending request remains.

**Upstream comparison**

- .NET: Implements the default mesh, handoff filtering, and pending request blocks handoff.
- Python: Requires participants to be `Agent`, requires per-service-call history persistence, and creates a default mesh.

**Decision.** The safeguards of the two upstreams are combined. Handoff deals with a lot of shared
conversation and continuation state, so capability validation and artifact filtering are essential.

**Acceptance criteria**

- When there is no explicit handoff graph, a default mesh between all participants is created.
- A handoff target receives the original user context but not the handoff tool/function artifacts of the source agent.
- If the participant capability constraints are not satisfied, the build fails.
- No handoff happens while a pending request exists.

**Evidence** [18 Handoff](../upstream/snapshots/d0a4165f/features/18-orchestrations.md)

---

## WF-033 Group-chat has a single orchestrator contract and a no-self-echo rule

**Requirement.** The Group-chat builder must require exactly one orchestrator or manager source, must
not broadcast the current speaker's response back to itself, and must terminate when the same
speaker is picked again immediately.

**Upstream comparison**

- .NET: `GroupChatHost` implements the no-self-echo and same-speaker termination guards.
- Python: The builder validates the exactly-one orchestrator configuration and participant uniqueness.

**Decision.** The contracts of the two upstreams are combined. The heart of group-chat is the central
selector. More than one orchestrator source makes the meaning ambiguous, and self-echo invites
infinite loops and wasted tokens.

**Acceptance criteria**

- Exactly one orchestrator/manager configuration is allowed.
- The current speaker's message is broadcast only to the other participants.
- If the manager immediately picks the same speaker again, the workflow transitions to termination or to a fail-safe path.
- The default terminal output policy is fixed on the host/orchestrator side.

**Evidence** [18 Group-chat](../upstream/snapshots/d0a4165f/features/18-orchestrations.md)

---

## WF-034 Magentic has a single manager contract and a plan review and replan flow

**Requirement.** The Magentic builder must allow exactly one manager source, and must have the plan
review pause, progress ledger-based stall/loop detection, and the reset+replan path as a public
contract.

**Upstream comparison**

- .NET: Has `RequirePlanSignoff` defaulting to true and progress ledger failure recovery.
- Python: Enforces exactly one manager source and plan review is opt-in.

**Decision.** Java adopts the exactly-one manager source contract strongly and exposes the plan review
and replan flow as an explicit policy. Because this is planning-centric orchestration, the manager
SPI is the heart of it.

**Acceptance criteria**

- Exactly one of instance, factory, manager-agent, and manager-agent-factory is allowed as the manager source.
- When plan review is switched on, an external review request occurs after the initial plan.
- When the progress ledger detects a stall or a loop, it transitions to the reset+replan path.
- The default terminal output policy is fixed to the manager only.

**Evidence** [18 Magentic](../upstream/snapshots/d0a4165f/features/18-orchestrations.md)

### Declarative

---

## WF-035 Declarative workflows have a separated module, safe state paths, and a typed handler SPI

**Requirement.** The declarative surface must keep the workflow declarative and the agent-assets
declarative as separate modules, and must provide together the
`schema AST → normalized model → graph lowering` pipeline, safe state paths and an Env allowlist, a
typed HTTP/MCP/agent handler SPI, build-time validation that the handlers exist, and secret
redaction plus `original_request`-based re-execution on approval resumption.

**Upstream comparison**

- .NET: The workflow declarative centres on typed handler interfaces and source-generated JSON metadata.
- Python: Separates workflow/agent assets and has path safety, an Env allowlist, handler existence validation, and approval binding tests.

**Decision.** Declarative is not one feature but a bundle of several security boundaries. As
instructed by the user, it is included in the workflow subproject, but with strong module separation
and strong security defaults. Code and tests are stronger evidence than documentation, so the safe
defaults are chosen.

**Acceptance criteria**

- The workflow declarative and the agent-asset declarative have different modules or stability markers.
- The workflow YAML does not allow empty actions, and the build fails when a required HTTP/MCP handler is missing.
- State paths reject unsafe attribute traversal and `Env` exposure is controlled by an allowlist or safe mode.
- The MCP approval surface does not expose secret header values.
- Approval resumption uses the function name and arguments from the `original_request` payload.

**Evidence** [19 Declarative](../upstream/snapshots/d0a4165f/features/19-declarative.md), [16 declarative approval/HITL flow](../upstream/snapshots/d0a4165f/features/16-workflow-checkpoint-hitl.md)

---

## What this document does not cover

| Topic | Owning document |
| --- | --- |
| Single agent execution and model calls | [01 Agent execution and model calls](01-agent-execution.md) |
| The general tool call loop and the harness approval layer | [04 Tool definitions and the tool call loop](04-tools.md), [08 Harness features](08-harness.md) |
| Session stores and conversation history | [06 Sessions and conversation state](06-sessions.md) |
| Hosting protocols and remote execution adapters | [10 Hosting and protocols](10-hosting.md) |
| Operational observability, deployment, security operations | [11 Operational quality](11-operations.md) |
