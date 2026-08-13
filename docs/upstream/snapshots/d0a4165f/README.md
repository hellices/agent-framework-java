# d0a4165f upstream snapshot index

This document collects, in one place, the evidence documents captured from the
`d0a4165f170193ba1d026a259af40d35bb7eaefe` snapshot before moving on to the Java implementation
plan, and it fixes the reading order and the judgement gates.

## Snapshot identity

| Item | Value |
| --- | --- |
| Repository | `https://github.com/microsoft/agent-framework.git` |
| Branch at capture | `main` |
| Full commit | `d0a4165f170193ba1d026a259af40d35bb7eaefe` |
| Git tree | `0dbd7a60d70ad3b588b5b2ad77131b3a0879c3cf` |
| Commit time | `2026-08-10T05:51:59Z` |
| Subject | `[BREAKING] Python: Migrate FHA to responses==2.0.0b1 and add Foundry state store (#7533)` |
| Baseline manifest | [snapshot-manifest.md](./snapshot-manifest.md) |

## Analysis priority

When two sources describe the same feature differently, judge them in the following order.

1. Production source at the pinned commit
2. Unit, integration, and conformance tests at the pinned commit
3. Specifications and feature documents at the pinned commit
4. Official documentation on Microsoft Learn
5. Samples and READMEs

A feature that exists only in documentation and cannot be confirmed in code and tests is not
treated as implemented, and a difference between the .NET and Python implementations is recorded
as drift instead of being standardized arbitrarily, and then separated out as a Java design
decision.

## Deliverable summary

- [Snapshot manifest](./snapshot-manifest.md): fixes the baseline commit, tree, package versions,
  repository composition, and the snapshot update procedure.
- [31 feature docs](#document-map-by-feature-group): break the evidence down per feature, from
  core, workflow, hosting, observability, and packaging through to the provider inventory.
- [Coverage ledger](./coverage-ledger.md): records that no mapping is missing between the 31
  feature documents and the upstream code, test, ADR, and spec sets.
- [71-row compatibility matrix](./compatibility-matrix.md): binds the per-feature .NET/Python
  implementation status, the Java target, the release phase, the owning module, and the acceptance
  scenario into a single table.

## Document map by feature group

### 1. Agent core and state surface

- [`01-agent-lifecycle.md` — 01-agent-lifecycle](./features/01-agent-lifecycle.md): covers the
  agent abstraction, identity/metadata, session seed, run entrypoint, and delegation boundary.
- [`02-message-content.md` — 02-message-content](./features/02-message-content.md): organizes
  role, multimodal content, usage/update/final response, normalization, and stream finalization.
- [`03-model-execution.md` — 03-model-execution](./features/03-model-execution.md): covers the
  model client contract, request options, sync/async execution, streaming continuation, and
  cancellation.
- [`04-structured-output.md` — 04-structured-output](./features/04-structured-output.md):
  organizes structured schema requests, provider capability, fallback parse, validation, and
  stream constraints.
- [`05-function-tools.md` — 05-function-tools](./features/05-function-tools.md): covers function
  tool definition, schema generation, argument validation, tool selection, budget, and parallel
  execution semantics.
- [`06-tool-approval.md` — 06-tool-approval](./features/06-tool-approval.md): organizes the
  approval request/response, the standing approval rule, the approval-aware wrapper, and the
  denial result rules.
- [`07-mcp-client-tools.md` — 07-mcp-client-tools](./features/07-mcp-client-tools.md): covers the
  MCP client lifecycle, tool discovery/invocation, transport differences, and the approval
  boundary.
- [`08-sessions.md` — 08-sessions](./features/08-sessions.md): compares AgentSession
  identity/state, create/serialize/deserialize, and the store/file-backed session surface.
- [`09-history-context-memory.md` — 09-history-context-memory](./features/09-history-context-memory.md):
  organizes the history provider, the context provider, memory injection, and the persistence
  adapter boundary.

### 2. Execution pipeline and harness

- [`10-middleware.md` — 10. Middleware](./features/10-middleware.md): compares the middleware
  insertion points against the .NET decorator seam and the Python typed pipeline.
- [`11-compaction.md` — 11. Compaction](./features/11-compaction.md): organizes the trigger, the
  summarization contract, and the state rewrite rules of the compaction subsystem.
- [`12-harness.md` — 12. Harness](./features/12-harness.md): covers harness composition, loop
  policy, todo/mode provider, file memory, and the workspace access scope.
- [`13-skills-background-code.md` — 13. Skills · Background · Code Execution](./features/13-skills-background-code.md):
  gathers and organizes only the contracts and boundaries related to skills loading, background
  tasks, and code execution.

### 3. Workflow and orchestration

- [`14-workflow-graph.md` — 14-workflow-graph](./features/14-workflow-graph.md): covers the
  workflow graph definition, node/edge shape, builder surface, and the graph-level invariants.
- [`15-workflow-runtime.md` — 15-workflow-runtime](./features/15-workflow-runtime.md): organizes
  the execution loop of a built workflow, the runner, the streaming run handle, and the
  responsibilities of the state manager.
- [`16-workflow-checkpoint-hitl.md` — 16-workflow-checkpoint-hitl](./features/16-workflow-checkpoint-hitl.md):
  organizes checkpoint/resume, persistence, request-response, and the HITL approval flow together.
- [`17-workflow-composition.md` — 17-workflow-composition](./features/17-workflow-composition.md):
  covers subworkflows, workflow-as-agent, hosted composition, and the session propagation rules.
- [`18-orchestrations.md` — 18-orchestrations](./features/18-orchestrations.md): compares the
  public surface of the sequential, concurrent, handoff, and group chat orchestration layers.
- [`19-declarative.md` — 19-declarative](./features/19-declarative.md): organizes declarative
  agent assets, schema, workflow loading, runtime/state binding, and the validation boundary.

### 4. Hosting and protocol adapters

- [`20-hosting.md` — 20. Hosting](./features/20-hosting.md): covers the generic hosting lifecycle,
  host composition, the scaling responsibility, and the ASP.NET/Python hosting helper layers.
- [`21-openai-responses-hosting.md` — 21. OpenAI Responses-compatible hosting](./features/21-openai-responses-hosting.md):
  organizes the OpenAI Responses-compatible hosting contract, the endpoint shape, and the state
  ownership rules.
- [`22-a2a.md` — 22. A2A](./features/22-a2a.md): covers the A2A integration, the hosting adapter,
  the way agents are exposed, and the transport boundary.
- [`23-ag-ui.md` — 23. AG-UI](./features/23-ag-ui.md): organizes the AG-UI adapter and hosting,
  the event surface, and the rules for passing UI-facing state.
- [`24-mcp-hosting.md` — 24. MCP hosting](./features/24-mcp-hosting.md): covers MCP server
  hosting, the client/server boundary, hosted tool exposure, and protocol ownership.
- [`25-foundry-devui-channels.md` — 25. Foundry hosting, DevUI, Aspire integration, ChatKit, Telegram, and other confirmed channel adapters](./features/25-foundry-devui-channels.md):
  organizes Foundry hosting together with DevUI, Aspire, and the channel adapter family as one
  group.
- [`26-identity-session-routing.md` — 26. Identity / session routing](./features/26-identity-session-routing.md):
  covers hosted identity, the authorization responsibility, per-user isolation, and session
  routing drift.

### 5. Quality, packaging, and the provider ecosystem

- [`27-observability.md` — 27. Observability](./features/27-observability.md): organizes
  OpenTelemetry, feature telemetry, logging, and the criteria for handling sensitive data.
- [`28-errors-resilience-security.md` — 28. Errors, Resilience, Security](./features/28-errors-resilience-security.md):
  covers the error taxonomy, the validation boundary, cancellation, timeout, cleanup, and the
  retry/security boundary.
- [`29-evaluation-testing.md` — 29. Evaluation & Testing](./features/29-evaluation-testing.md):
  organizes the evaluation API, checks/scoring, agent/workflow evaluation, and the
  generated/provider test harness.
- [`30-packaging-compatibility.md` — 30. Packaging & Compatibility](./features/30-packaging-compatibility.md):
  covers the package layout, release metadata, the compatibility surface, stability markers, and
  the distribution units.
- [`31-provider-integrations.md` — 31. Provider / Integration Inventory](./features/31-provider-integrations.md):
  aggregates the provider/integration inventory of the 33 dotnet projects and the 35 python
  packages.

## Reading order

1. Fix the commit, tree, package versions, and snapshot scope with
   [snapshot-manifest.md](./snapshot-manifest.md).
2. Skim the feature group map in this index and first separate the Java MVP/Core+, Workflow,
   Hosting, and Optional adapters categories.
3. Read the core runtime and the state model in documents `01` through `09` and settle the base
   contract of the Java `AgentEngine`.
4. Read the execution seams such as middleware, compaction, harness, and skills/background/code
   execution in documents `10` through `13`.
5. Decide from documents `14` through `19` whether workflow and orchestration are split into a
   separate subproject scope.
6. Read the hosting, protocol adapter, and identity/session routing boundaries in documents `20`
   through `26` and fix the host responsibility.
7. Cross-check documents `27` through `31` against [coverage-ledger.md](./coverage-ledger.md) and
   [compatibility-matrix.md](./compatibility-matrix.md) to confirm the implementation phase, the
   acceptance scenarios, and the optional adapter split.

## Gate for moving on to Java implementation planning

Planning at the level of Java files, classes, and methods starts only when all of the following
conditions are met.

1. The scope of the 31 feature documents above has been read, and it is agreed, on the basis of
   [compatibility-matrix.md](./compatibility-matrix.md), which Java module each core, runtime,
   workflow, hosting, and provider item belongs to.
2. The differences left by the [.NET/Python drift, docs-only, and provider-specific
   judgements](./compatibility-matrix.md) are separated into core contracts and optional adapters
   and recorded without arbitrary standardization.
3. [coverage-ledger.md](./coverage-ledger.md) confirms that no mapping is missing between the 31
   documents and the upstream code, test, ADR, and spec sets.
4. Features outside the [snapshot-manifest.md](./snapshot-manifest.md) baseline commit, and
   Learn/sample-only information, are blocked from entering the planning input.

Java implementation planning is written only after this gate passes; before that, fixing the
documentary evidence takes priority.

- [Documentation index](../../../README.md)
