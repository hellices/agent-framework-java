# Workflow and harness design

## 1. Scope and phases

- `WF-001..035`: separate workflow subsystem
- `HAR-001..021`: optional harness composition

Workflow and harness are not core MVP product dependencies. They are developed as separate artifacts
after the core API stabilizes and do not reference `agent-framework-engine` internals.

## 2. Modules

```text
workflow/agent-framework-workflow-api
  graph definition, events, run/checkpoint contracts

workflow/agent-framework-workflow-core
  graph validation, runner, superstep state machine

workflow/agent-framework-workflow-processor
  annotation processor and generated route registry

workflow/agent-framework-workflow-orchestration
  sequential, concurrent, handoff, group chat, magentic

workflow/agent-framework-workflow-functional-experimental
  experimental functional steps, caching, signatures, interruption

workflow/agent-framework-declarative-spi
  typed HTTP/MCP/agent handler contracts

workflow/agent-framework-workflow-declarative
  safe workflow schema/normalize/lowering pipeline

workflow/agent-framework-agent-assets-declarative
  separately versioned agent asset definitions

integrations/agent-framework-harness
  harness builder, loop, todo/mode/approval providers

integrations/agent-framework-file-access
integrations/agent-framework-skills
integrations/agent-framework-background-agents
integrations/agent-framework-shell-tools
integrations/agent-framework-local-codeact
integrations/agent-framework-codeact-sandbox
```

The API/core split is applied earlier than in the core agent modules because evidence already shows
that workflow artifacts must evolve independently of provider/protocol adapters.

## 3. Workflow definition

`WorkflowBuilder` creates an immutable `Workflow`.

```text
Workflow
  WorkflowId + signature
  executors: stable id -> ExecutorDefinition
  edges: DIRECT | FAN_OUT | FAN_IN
  designated outputs
  route bindings
```

build validation:

- missing executor binding
- unreachable from the start point
- output empty/duplicate/overlap
- invalid edge/source/sink/type
- unresolved symbolic callable

The annotation processor is the primary path for generating route and type metadata. The plain Java
explicit builder uses the same registry contract. Runtime reflection is an opt-in fallback and does
not automatically require native-image metadata.

## 4. Workflow run

`WorkflowRun` is a mutable execution handle separate from the definition.

- run id/status
- event publisher
- explicit cancel
- pending requests
- latest checkpoint
- resume/respond API

The application-facing `WorkflowRunner` executes a definition or converts it into an Agent facade.
Host assembly creates the runner and injects `WorkflowExecutionStrategy`, `WorkflowClock`, the
checkpoint store, and the codec registry. The engine/runner does not create executors or schedulers.

### 4.1 Superstep

```text
START_SUPERSTEP
  -> DRAIN_EXTERNAL_AND_QUEUED_MESSAGES
  -> ROUTE
  -> EXECUTE_READY_NODES
  -> STAGE_STATE_AND_EVENTS
  -> COMMIT_OR_DISCARD
  -> SAVE_CHECKPOINT
  -> COMPLETE_SUPERSTEP
```

Pending state before an executor failure is discarded. The runtime does not pretend to roll back
committed state. Multiple writers to the same state key fail with a deterministic conflict.

### 4.2 Routing

- `DIRECT`: no downstream delivery when the condition is false
- `FAN_OUT`: the selector determines the sink set
- `FAN_IN`: execute once when the barrier for the declared source set is satisfied

Switch/multi-selection builders lower into the runtime graph's three edge kinds.

## 5. Events and status

Status is available through both polling and an event stream.

- polling: status snapshot, pending requests, latest checkpoint
- streaming: ordered `WorkflowEvent`

The event taxonomy is a framework-owned closed hierarchy with a payload extension envelope. The
runner generates the source/discriminator so user messages cannot spoof reserved lifecycle events.

Executor runtime sends also pass through a runner-owned gate.

- inject missing trace context from the current workflow context
- if an executor sends a reserved lifecycle event discriminator, reject it or safely convert it into
  a user event and emit an observability warning
- only the runner creates framework lifecycle events

Failure ordering:

```text
ExecutorFailed
WorkflowFailed
terminal completion
```

Stream close and run cancellation are separate. Unsubscribing does not implicitly cancel a durable
workflow.

## 6. Checkpoint and resume

checkpoint envelope:

- checkpoint id
- previous/parent checkpoint id
- workflow signature/schema version
- superstep and event cursor
- executor snapshots
- committed/shared/edge state
- pending messages and external requests
- deterministic order key

The codec registry is instance-scoped, allowlisted, and immutable. It does not use Java
serialization or class-name loading.

restore:

1. validate the workflow signature
2. remove stale queued events
3. restore the state/request registry
4. correlate and apply the supplied response by request ID + port ID
5. if requests remain pending, reject new input and republish only the requests, once
6. apply new input only when there are no pending requests

Store only symbolic names for callables and rebind them through the immutable registry in
`WorkflowRestoreOptions`. There is no global registry or silent no-op fallback.

Validate an external response's request ID and port ID against the pending checkpoint entry. For
approval execution arguments, use only the immutable `originalRequest` in the response payload as
the source of truth; do not execute arguments from a mutable checkpoint copy.

### 6.1 File checkpoint store

`FileCheckpointStore` contract:

- reject paths outside the configured root and symlink/real-path escapes
- temp file write + atomic move/replace
- stable checkpoint id/index ordering
- use only the allowlisted codec registry; prohibit Java native serialization and class-name loading
- distinguish decode corruption from workflow signature/schema mismatch

## 7. Composition

### 7.1 Subworkflow

Subworkflows use composition rather than inheritance.

- each child binding's `WorkflowHostExecutor` owns its ownership token, qualified response port, and
  child run/checkpoint lifecycle; the parent composes the binding
- `SubworkflowBindingOptions` separately specifies child final-output projection,
  intermediate-output propagation, and pending-request propagation
- include the child signature in the checkpoint signature

### 7.2 Workflow as Agent

`WorkflowAgent` is a public `Agent` decorator.

- store the workflow run/checkpoint in the agent session
- project events/output through the agent run/stream contract
- preserve continuation state with the session codec

### 7.3 Functional workflow

`agent-framework-workflow-functional-experimental` owns a separate stability surface.

- `FunctionalWorkflowBuilder`, typed `Step<I,O>`, immutable step signature
- the step result cache uses both the injected cache port and signature
- lower interruption/pending requests into core `WorkflowRun` semantics
- the experimental API does not weaken public compatibility of the graph/runtime core

### 7.4 Orchestration

Pattern builders use shared participant/output helpers.

- sequential: full-conversation / chain-only
- concurrent: participant fan-out + aggregator
- handoff: explicit graph or validated mesh
- group chat: exactly one orchestrator/manager
- magentic: manager, plan review, progress ledger

Participant sources are unified through a resolver adapter rather than expanding the public union
with instance/factory variants.

### 7.5 Declarative

This is an optional surface with three module boundaries.

- `agent-framework-declarative-spi`: typed HTTP/MCP/agent handlers and handler IDs
- `agent-framework-workflow-declarative`: schema AST → normalized model → graph lowering
- `agent-framework-agent-assets-declarative`: agent/model/tool asset definitions; separate stability
- safe instance-scoped type/handler registry
- no arbitrary class loading
- generated/explicit binding
- the build-time processor verifies that referenced handlers exist
- block state path traversal and use an environment-variable allowlist
- redact MCP secrets and replay the approval `originalRequest`
- declarative documents do not implicitly gain filesystem, network, or code-execution capabilities

## 8. Harness

`HarnessAgent` is an assembly facade around an `Agent`.

```text
HarnessBuilder
  base Agent
  loop predicate
  todo provider
  mode provider
  file memory provider
  approval provider
  optional skills/background/shell/code adapters
```

Invalid provider combinations fail at build time. Defaults are conservative and opt-in.

### 8.1 Loop

The harness loop is separate from the core model/tool loop.

- each iteration gets fresh run context
- stop immediately on a pending approval
- the predicate determines continuation
- calculate the outer harness iteration cap independently
- approval re-entry shares the core tool/request invocation budget

### 8.2 Providers

- todo: session-backed state, stable operation result
- mode: default `plan`, external change notification
- file memory: session scope, reserved names rejected
- approval: queue and standing rules in session

### 8.3 Optional unsafe capabilities

- skills: progressive disclosure; script execution approval by default
- background agents: injected instance-scoped polling store, `LOST` state
- shell: separate manually assembled tools module
- LocalCodeAct: not called a sandbox
- sandbox backend: separate module + explicit trust/capability

A denylist is a guardrail, not a security boundary. Host OS/container policy is the actual boundary.

## 9. Framework integration

- Spring/Quarkus/Jakarta create workflow executors and harness providers as beans, then pass them to
  an explicit builder
- convert container priority into a deterministic registration list
- do not mix request scope with durable workflow state
- the container owns executors, schedulers, transactions, and security
- annotation processor output creates the same runtime registry in every framework

## 10. Tests

### Graph

- binding/reachability/output validation
- generated route vs explicit builder equivalence
- edge lowering, fan-in barrier, condition drop

### Runtime

- superstep event order
- executor send trace injection and reserved-event spoof rejection
- state conflict and discard on failure
- cancellation vs stream unsubscribe
- executor failure ordering

### Checkpoint/HITL

- signature mismatch
- checkpoint identity and parent lineage
- stale event drain
- latest checkpoint deterministic sort
- supplied response applied before remaining request re-publication
- dual-key response validation
- approval response `originalRequest` as execution truth source
- child binding host-executor ownership
- malicious type/path rejection
- file root confinement, atomic replace, restricted decode

### Harness/orchestration

- conservative defaults
- approval pause and budget
- independent harness iteration cap and shared core approval budget
- provider session isolation
- pattern-specific builder validation
- background `LOST`, skill/file/shell boundaries

## 11. Current implementation

Workflow and harness modules do not exist yet. Every `WF`/`HAR` ID has status `absent`.

## 12. Requirements mapping

Canonical rows for `WF` and `HAR` are in the
[Requirements traceability matrix](requirements-traceability-matrix.md).
