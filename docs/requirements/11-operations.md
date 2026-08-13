# 11 Operational quality

**Prefix** `OPS` · **Upstream features** [27 observability](../upstream/snapshots/d0a4165f/features/27-observability.md),
[28 errors-resilience-security](../upstream/snapshots/d0a4165f/features/28-errors-resilience-security.md),
[29 evaluation-testing](../upstream/snapshots/d0a4165f/features/29-evaluation-testing.md),
[30 packaging-compatibility](../upstream/snapshots/d0a4165f/features/30-packaging-compatibility.md)

Defines the contracts for observability, error classification, resilience boundaries, evaluation,
testing, packaging, and compatibility. The execution model and the protocols themselves are owned by
[01 Agent execution and model calls](01-agent-execution.md) and
[10 Hosting and protocols](10-hosting.md), and this document settles only the operational quality
rules that sit on top of them.

## Adoption scope

The `Grade` column in this document is, as [README](README.md#requirement-grades) defines it, how binding a requirement is once the decision to build the feature has been made; whether the feature is adopted at all follows the [compatibility matrix](../upstream/snapshots/d0a4165f/compatibility-matrix.md).

- The basic operational quality features (`OBS01`, `ERR01`, `SEC01`, `TEST01`, `PKG01`) have adoption `Required`.
- The optional observability extension (`OBS02`) and the evaluation features (`EVAL01`) have adoption `Optional`.

## Summary

| ID | Requirement | Adoption | Grade | Phase |
| --- | --- | --- | --- | --- |
| OPS-001 | The OpenTelemetry GenAI conventions are taken as the standard | Required | Required | Core+ |
| OPS-002 | Observability separates bootstrap from the wrappers | Required | Required | Core+ |
| OPS-003 | Sensitive data collection is a separate opt-in that is off by default | Required | Required | Core+ |
| OPS-004 | Logging is kept as a layer separate from tracing | Required | Required | Core+ |
| OPS-005 | Feature telemetry is emitted live only to approved origins | Optional | Recommended | Core+ |
| OPS-006 | Disabling instrumentation stays sticky | Required | Required | Core+ |
| OPS-007 | The same operation is not instrumented twice in two layers and per-category control exists | Required | Required | Core+ |
| OPS-008 | A common error taxonomy is exposed | Required | Required | Core+ |
| OPS-009 | Validation and programming errors are left as built-in exceptions | Required | Required | Core+ |
| OPS-010 | Cancellation is not translated into an ordinary failure | Required | Required | Core+ |
| OPS-011 | A timeout is recorded in the result envelope | Required | Required | Core+ |
| OPS-012 | Cleanup is performed per process tree and per remote task | Required | Required | Core+ |
| OPS-013 | A persistent executor is a session-owned resource | Required | Required | Core+ |
| OPS-014 | Shell and tool policies are not dressed up as security boundaries | Required | Required | Core+ |
| OPS-015 | Dangerous bypass paths carry explicit safeguards | Required | Required | Core+ |
| OPS-016 | Retry, timeout, and circuit breaker operational policies are owned by the host | Required | Required | Core+ |
| OPS-017 | The evaluation SPI uses a batch-oriented provider-neutral contract | Optional | Required | Core+ |
| OPS-018 | The evaluation converter is a first-class API and minimizes the payload sent outside | Optional | Required | Core+ |
| OPS-019 | Evaluation results separate execution failures from quality failures | Optional | Required | Core+ |
| OPS-020 | Workflow evaluation must have a public API and per-agent subresults | Optional | Required | Workflow |
| OPS-021 | Generated evaluators and golden inputs must be deterministically reproducible | Optional | Required | Optional |
| OPS-022 | Provider-common contract tests and golden scenarios are maintained | Required | Required | Core+ |
| OPS-023 | Packaging is based on a single version line and a Gradle BOM, with a separate stage registry | Required | Required | Core+ |
| OPS-024 | Dependency management uses validated bounds together with supply chain pinning | Required | Required | Core+ |
| OPS-025 | Compatibility and installability gates are tied to maturity | Required | Required | Core+ |
| OPS-026 | Cross-language compatibility is judged by the public surface and behavior | Required | Recommended | Core+ |

---

### Observability

## OPS-001 The OpenTelemetry GenAI conventions are taken as the standard

**Requirement.** The standard semantic convention for observability must be the OpenTelemetry GenAI
conventions.

**Upstream comparison**

- .NET: OpenTelemetryAgent and workflow telemetry build spans and metrics on the assumption of the GenAI semantic conventions.
- Python: The observability module manages the tracer, meter, and metrics views in one place and uses the same OTel semantics.

**Decision.** This is the common denominator of the two upstreams. Java must also base itself on OTel
GenAI rather than inventing its own convention, so that exporters and backends are compatible
immediately.

**Acceptance criteria**

- The public span and metric names match the OTel GenAI vocabulary.
- The provider, agent, and workflow identification tags sit on the standard semantics rather than on custom ad-hoc keys.

**Evidence** [27 observability](../upstream/snapshots/d0a4165f/features/27-observability.md)

---

## OPS-002 Observability separates bootstrap from the wrappers

**Requirement.** Java observability must separate the provider bootstrap layer from the agent and
workflow wrapper layer.

**Upstream comparison**

- .NET: A builder extension attaches OpenTelemetryAgent and the workflow wrapper.
- Python: observability.py manages the provider wiring and the accessors centrally.

**Decision.** The strengths of the two are combined. When bootstrap and wrappers are mixed, app-wide
initialization and per-run decoration contaminate each other.

**Acceptance criteria**

- A no-op wrapper can be configured even without OTel provider initialization.
- Only the agent or workflow wrapper can be replaced without changing the bootstrap API.

**Evidence** [27 observability](../upstream/snapshots/d0a4165f/features/27-observability.md)

---

## OPS-003 Sensitive data collection is a separate opt-in that is off by default

**Requirement.** The collection of sensitive data such as message content, tool args, and tool results
must be disabled by default and must be a separate opt-in.

**Upstream comparison**

- .NET: Raw inputs, outputs, and tool payloads enter spans only when EnableSensitiveData is switched on.
- Python: Sensitive payload capture is switched on only by calling enable_sensitive_telemetry() explicitly.

**Decision.** The user's instruction and the upstreams agree. The safer default is taken to prevent
accidental payload leakage in production environments.

**Acceptance criteria**

- The spans and logs of the default configuration contain no raw messages and no tool arguments.
- The configuration that switches sensitive data capture on and the one that switches it off are distinguished by tests.

**Evidence** [27 observability](../upstream/snapshots/d0a4165f/features/27-observability.md)

---

## OPS-004 Logging is kept as a layer separate from tracing

**Requirement.** The human-readable logging layer must be separated from the tracing layer, and
payload-bearing logs must be disabled by default.

**Upstream comparison**

- .NET: LoggingAgent distinguishes lifecycle logs from payload-bearing Trace logs.
- Python: The logger exporter is part of the observability bootstrap but is controlled by a switch separate from sensitive data capture.

**Decision.** The purpose is the same. Hiding tracing and logging on one path makes it easy for an
operator to switch on payload logs unexpectedly.

**Acceptance criteria**

- Switching on only the Debug level leaves lifecycle information and no payload.
- A redaction-aware serializer or formatter can be replaced.

**Evidence** [27 observability](../upstream/snapshots/d0a4165f/features/27-observability.md)

---

## OPS-005 Feature telemetry is emitted live only to approved origins

**Requirement.** Feature telemetry must be computed as a request-time live signal but must be emitted
only to approved first-party origins.

**Upstream comparison**

- .NET: In the production code of this snapshot the UA identity and the hosted prefix are visible, but a full live feature token implementation at the Python level is not directly confirmed.
- Python: Actually implements a process-global bitmask, an approved-origin check, stale token stripping, and an opt-out env var.

**Decision.** The Python model, whose implementation is verifiable and stricter, is adopted.
Preventing hidden telemetry and third-party leakage is the safer side.

**Acceptance criteria**

- The feature token is removed from the request header unless the origin is an approved HTTPS origin.
- A complete feature telemetry opt-out and a bitmask-only opt-out are supported separately.

**Evidence** [27 observability](../upstream/snapshots/d0a4165f/features/27-observability.md)

---

## OPS-006 Disabling instrumentation stays sticky

**Requirement.** When an operator switches instrumentation off, framework helpers must not be able to
switch it on again before a forced re-enable.

**Upstream comparison**

- .NET: Because of the builder opt-in structure, the automatic re-enable path is relatively narrow.
- Python: After disable_instrumentation() the sticky disable is kept and it is not switched on again without force=True.

**Decision.** Python's operational protection is stronger. Java also adopts the sticky disable to
prevent unexpected re-instrumentation.

**Acceptance criteria**

- After a disable, instrumentation is not switched on again merely by enabling sensitive telemetry or by calling the provider bootstrap.
- The forced re-enable path is surfaced as a separate API.

**Evidence** [27 observability](../upstream/snapshots/d0a4165f/features/27-observability.md)

---

## OPS-007 The same operation is not instrumented twice in two layers and per-category control exists

**Requirement.** The same operation must be instrumented in only one layer, and workflow
instrumentation must be switchable on and off per category.

**Upstream comparison**

- .NET: Does not decorate an already instrumented chat client again and keeps a category-level disable switch in the workflow telemetry.
- Python: The central bootstrap controls the tracer and meter configuration to avoid duplicate wiring and manages broad enable/disable.

**Decision.** The user's instruction "no duplicate instrumentation of the same operation" is
reflected directly. Duplicate spans only add cost and interpretation confusion.

**Acceptance criteria**

- Re-decorating an already instrumented inner client or transport becomes a no-op.
- The workflow build, run, executor, and message categories can be disabled independently.

**Evidence** [27 observability](../upstream/snapshots/d0a4165f/features/27-observability.md)

---

---

### Errors, resilience, and security

## OPS-008 A common error taxonomy is exposed

**Requirement.** Java must expose a common error taxonomy that allows branching per layer.

**Upstream comparison**

- .NET: In this snapshot, per-package local exception hierarchies mixed with built-in exceptions are central.
- Python: Provides a framework-wide exception hierarchy with agent, workflow, integration, and tool branches.

**Decision.** A classifiable taxonomy is what lets protocol binders and hosts branch in a
machine-readable way. The Python structure is adopted as the base.

**Acceptance criteria**

- The public exception types have top-level branches such as agent, workflow, integration, tool, and provider.
- A host can classify errors from the exception type alone, without parsing message strings.

**Evidence** [28 errors-resilience-security](../upstream/snapshots/d0a4165f/features/28-errors-resilience-security.md)

---

## OPS-009 Validation and programming errors are left as built-in exceptions

**Requirement.** Invalid arguments, invalid states, and configuration mistakes must be left as
built-in exceptions rather than wrapped in framework-specific domain errors.

**Upstream comparison**

- .NET: LocalShellExecutor uses ArgumentException and ArgumentOutOfRangeException directly.
- Python: The coding standard states the built-in boundaries such as ValueError, TypeError, and RuntimeError.

**Decision.** The intent of the two upstreams is the same. Wrapping even validation errors in domain
exceptions makes a mistake the user has to fix look like an operational failure.

**Acceptance criteria**

- Invalid API input fails with an IllegalArgumentException-family exception.
- The domain exception hierarchy is used only for external service failures and runtime failures.

**Evidence** [28 errors-resilience-security](../upstream/snapshots/d0a4165f/features/28-errors-resilience-security.md)

---

## OPS-010 Cancellation is not translated into an ordinary failure

**Requirement.** Cancellation must be kept as a control signal different from an ordinary failure and
must not be translated into a generic domain exception.

**Upstream comparison**

- .NET: The MCP wrapper surfaces the cancelled state as OperationCanceledException.
- Python: The declarative HTTP executor propagates CancelledError as is instead of wrapping it.

**Decision.** As the user instructs, cancellation is included in the taxonomy but is not
contaminated as a failure. That is what keeps host timeout, client abort, and human cancel exactly
distinguished.

**Acceptance criteria**

- A cancelled execution is not replaced by a generic FrameworkException.
- Cancellation propagation tests exist for both the tool transport and the workflow transport.

**Evidence** [28 errors-resilience-security](../upstream/snapshots/d0a4165f/features/28-errors-resilience-security.md)

---

## OPS-011 A timeout is recorded in the result envelope

**Requirement.** Long-running work such as shell and code execution must record whether a timeout
occurred in the result envelope, separately from exceptions.

**Upstream comparison**

- .NET: ShellResult has the TimedOut and ExitCode=124 convention.
- Python: The shell executor tries to collect the output after a timeout and return it as an envelope.

**Decision.** Both upstreams agree. Recording the timeout in the result structure is what lets the
upper agent and the harness apply follow-up policy deterministically.

**Acceptance criteria**

- A timeout result includes timedOut=true and standardized termination information.
- On paths where no timeout occurred, the same fields are consistently false or empty.

**Evidence** [28 errors-resilience-security](../upstream/snapshots/d0a4165f/features/28-errors-resilience-security.md)

---

## OPS-012 Cleanup is performed per process tree and per remote task

**Requirement.** Cleanup must cover the whole process tree and long-running remote tasks, not just a
single parent process.

**Upstream comparison**

- .NET: The shell cleans up the process tree after a timeout, and the MCP wrapper attempts a best-effort remote cancel on local cancellation.
- Python: kill_process_tree cleans up child processes too.

**Decision.** The safer default is taken. Shallow cleanup leaves orphaned processes and orphaned
tasks that eat operational resources.

**Acceptance criteria**

- On a local process timeout or cancel, the descendants are cleaned up too.
- A remote task wrapper attempts a best-effort remote cancel after a local cancel.

**Evidence** [28 errors-resilience-security](../upstream/snapshots/d0a4165f/features/28-errors-resilience-security.md)

---

## OPS-013 A persistent executor is a session-owned resource

**Requirement.** A persistent shell or code executor must be limited to a resource owned by a single
conversation or session.

**Upstream comparison**

- .NET: Keeps persistent shell session ownership and an interrupt-then-teardown rule on timeout.
- Python: The shell tooling is mostly stateless, but the samples and the policy reveal the need for session-level coordination and cleanup.

**Decision.** Enforcing session ownership is what prevents state leakage and command interleaving.
Sharing a singleton is particularly dangerous in Java.

**Acceptance criteria**

- A persistent executor instance is not shared by two sessions at the same time.
- The executor teardown path on session termination or recovery failure is specified.

**Evidence** [28 errors-resilience-security](../upstream/snapshots/d0a4165f/features/28-errors-resilience-security.md)

---

## OPS-014 Shell and tool policies are not dressed up as security boundaries

**Requirement.** Guardrails such as a regex denylist, an allowlist, and a command policy must not act
as a security boundary that replaces the need for approval and sandboxing.

**Upstream comparison**

- .NET: ShellPolicy warns directly about variable expansion and interpreter escape bypasses.
- Python: The shell policy documentation states the limits of a guardrail bluntly and keeps approval and the sandbox as the real boundaries.

**Decision.** If the trust level rises on a bypassable policy object alone, operators come to trust
it wrongly. Java must surface the separation between a policy-only guardrail and the real approval
and isolation boundaries in both the configuration and the API.

**Acceptance criteria**

- An unattended unsafe mode or an approval-disabled path is not created or activated without an explicit argument equivalent to `acknowledgeUnsafe`.
- A regex denylist, allowlist, or command policy setting alone does not switch off the approval-required default of a tool or an executor.
- An executor without a sandbox capability or an isolation backend is not reported as sandboxed in the API or the descriptor.

**Evidence** [28 errors-resilience-security](../upstream/snapshots/d0a4165f/features/28-errors-resilience-security.md)

---

## OPS-015 Dangerous bypass paths carry explicit safeguards

**Requirement.** Dangerous paths such as an unattended unsafe mode and declarative state path access
must enforce an explicit acknowledgement or a path safety check.

**Upstream comparison**

- .NET: The shell approval wrapper and the auto-approval collision warning limit dangerous bypass paths.
- Python: LocalShellTool requires acknowledge_unsafe and the declarative state path blocks reflective escape.

**Decision.** Both point in the direction of "switch dangerous bypasses on visibly". Java must also
forbid a silent opt-out and reduce the state traversal attack surface.

**Acceptance criteria**

- An unattended mode that switches approval off is not created without an explicit acknowledgement.
- A state path that amounts to a reflective escape or path traversal is blocked by default or by an error.

**Evidence** [28 errors-resilience-security](../upstream/snapshots/d0a4165f/features/28-errors-resilience-security.md)

---

## OPS-016 Retry, timeout, and circuit breaker operational policies are owned by the host

**Requirement.** Operational policies such as retry, request timeout, and circuit breaker must be
owned explicitly by the host or the adapter rather than being implicit core behavior.

**Upstream comparison**

- .NET: On the evidence of this snapshot, a framework-wide generic retry layer cannot be directly confirmed.
- Python: Rather than a common retry across the runtime, test workflows and per-layer settings are what can be confirmed.

**Decision.** An automatic retry that cannot be confirmed is not turned into a requirement. As the
user instructs, the retry, timeout, and circuit breaker operational policies are fixed as
host-owned.

**Acceptance criteria**

- There is no silent retry in the default core behavior.
- Retry, timeout, and circuit-breaker are activated only through host or adapter configuration.

**Evidence** [28 errors-resilience-security](../upstream/snapshots/d0a4165f/features/28-errors-resilience-security.md)

---

---

### Evaluation and testing

## OPS-017 The evaluation SPI uses a batch-oriented provider-neutral contract

**Requirement.** The evaluation SPI must use a batch-oriented provider-neutral contract.

**Upstream comparison**

- .NET: IAgentEvaluator uses batch-level EvaluateAsync(items, evalName, ...).
- Python: The Evaluator model likewise takes a list of EvalItem and returns EvalResults.

**Decision.** Both upstreams agree. A batch contract is what makes cloud evaluators, cost accounting,
and score aggregation efficient to handle.

**Acceptance criteria**

- Several EvalItems can be evaluated in a single evaluator call.
- A provider-specific evaluator uses the same EvalItem/EvalResults model.

**Evidence** [29 evaluation-testing](../upstream/snapshots/d0a4165f/features/29-evaluation-testing.md)

---

## OPS-018 The evaluation converter is a first-class API and minimizes the payload sent outside

**Requirement.** The converter that turns runtime messages and tool definitions into the evaluator
schema must be a first-class API, and the payload leaving for an external evaluator must be
minimized.

**Upstream comparison**

- .NET: The BuildEvalItem-family helpers assemble a minimal conversation and the tool definitions.
- Python: AgentEvalConverter performs the content conversion and the sanitization of unparseable tool arguments directly.

**Decision.** The Python side is stricter. The converter is not a simple formatter but an information
minimization boundary, so Java must also have a separate API and sanitization rules.

**Acceptance criteria**

- Without the converter, an evaluator does not parse a runtime Message directly.
- Unparseable tool arguments are replaced by a safe placeholder instead of a raw string.

**Evidence** [29 evaluation-testing](../upstream/snapshots/d0a4165f/features/29-evaluation-testing.md)

---

## OPS-019 Evaluation results separate execution failures from quality failures

**Requirement.** The evaluation result model must express an evaluator execution failure and a
failure to meet the quality bar as different states, and must provide gate helpers separately.

**Upstream comparison**

- .NET: AgentEvaluationResults carries Status, Error, AssertAllPassed, and AssertScoreAtLeast together.
- Python: EvalResults provides status, error, raise_for_status, and assert_score_at_least.

**Decision.** Mixing an infra failure with a quality failure makes CI and reporting swallow one of
the two incorrectly. A shared result model that separates the two failures is kept.

**Acceptance criteria**

- An evaluation execution failure is distinguished from a gate failure by a different status code or enum.
- The API that reads the raw results and the gate helper API are separated on top of the same model.

**Evidence** [29 evaluation-testing](../upstream/snapshots/d0a4165f/features/29-evaluation-testing.md)

---

## OPS-020 Workflow evaluation must have a public API and per-agent subresults

**Requirement.** Workflow evaluation must be provided as a separate public API and must return the
overall result together with per-agent subresults.

**Upstream comparison**

- .NET: The result model prepares SubResults, but an explicit public API for workflow evaluation cannot be directly confirmed on the evidence of this snapshot.
- Python: evaluate_workflow() exists as a public API and fills in the per-agent breakdown.

**Decision.** Python is more complete. Java must not stop at preparing only the result types but must
also require the explicit API.

**Acceptance criteria**

- A public API of the evaluateWorkflow kind exists.
- The result model preserves the overall score together with subresults keyed by executor or agent id.

**Evidence** [29 evaluation-testing](../upstream/snapshots/d0a4165f/features/29-evaluation-testing.md)

---

## OPS-021 Generated evaluators and golden inputs must be deterministically reproducible

**Requirement.** Generated or provider evaluators and golden inputs must be operated on the premise
of version pinning and deterministic reproduction.

**Upstream comparison**

- .NET: GeneratedEvaluatorRef assumes version pinning and fixes conformance traces as a disk corpus.
- Python: FoundryEvals manages the generated rubric evaluator reference and the tests use deterministic inputs.

**Decision.** The user's requirement of "deterministic reproduction" is reflected as it is. A
latest-floating evaluator or variable inputs blur regression judgements.

**Acceptance criteria**

- A generated evaluator reference does not force an unversioned latest default.
- The golden or trace corpus is kept as source-controlled fixed input.

**Evidence** [29 evaluation-testing](../upstream/snapshots/d0a4165f/features/29-evaluation-testing.md)

---

## OPS-022 Provider-common contract tests and golden scenarios are maintained

**Requirement.** Provider-common contract tests, golden scenarios, and protocol wire conformance tests
must be maintained as separate layers.

**Upstream comparison**

- .NET: Keeps the agent contract integration tests and the OpenAI trace-driven conformance suite apart.
- Python: Runs aggregate tests and provider-sharded integration jobs strongly, but an equivalent trace-driven wire harness cannot be directly confirmed on the evidence of this snapshot.

**Decision.** Java requires both. Common contract tests alone cannot catch wire regressions, and
golden scenarios alone cannot pin the provider-neutral contract.

**Acceptance criteria**

- A common contract test exists that can be run as the same suite even when the provider is changed.
- Representative scenarios are fixed with golden inputs and expected results.
- Protocol adapters have a trace-driven or equivalent wire conformance suite.

**Evidence** [29 evaluation-testing](../upstream/snapshots/d0a4165f/features/29-evaluation-testing.md)

---

---

### Packaging and compatibility

## OPS-023 Packaging is based on a single version line and a Gradle BOM, with a separate stage registry

**Requirement.** The Java monorepo must be packaged on the basis of a single version line and a
Gradle BOM, while managing maturity stages in a lifecycle registry separate from the artifact
metadata.

**Upstream comparison**

- .NET: Manages version and stage computation with a central VersionPrefix and csproj stage metadata.
- Python: Keeps the package lifecycle in a separate document with workspace packages and PACKAGE_STATUS.md.

**Decision.** The user's requirements of a single version, a BOM, and a maturity distinction are
fixed as one requirement. Keeping version computation and stage announcements out of the same file is
clearer for downstream consumers.

**Acceptance criteria**

- Every published artifact follows the same release train version.
- Consumers can import a Gradle BOM or platform to get the tested dependency set.
- Stages such as alpha, beta, preview, and released can be checked in a separate lifecycle registry.

**Evidence** [30 packaging-compatibility](../upstream/snapshots/d0a4165f/features/30-packaging-compatibility.md)

---

## OPS-024 Dependency management uses validated bounds together with supply chain pinning

**Requirement.** Dependency management must keep a tested version BOM together with validated bounds
and must record security-motivated pinning explicitly.

**Upstream comparison**

- .NET: Uses central package management, transitive pinning, and NuGet audit.
- Python: Manages dependency bounds rules together with constraint or override dependencies.

**Decision.** The strengths of the two are combined. Pinning only the tested version reduces consumer
flexibility, and keeping only bounds slows the supply chain response.

**Acceptance criteria**

- The recommended tested versions are pinned in a BOM or a central dependency file.
- The supported bounds and the reason for a security-motivated override or pinning change are left in documentation or comments.

**Evidence** [30 packaging-compatibility](../upstream/snapshots/d0a4165f/features/30-packaging-compatibility.md)

---

## OPS-025 Compatibility and installability gates are tied to maturity

**Requirement.** Binary or API compatibility gates and installability smoke tests must be tied to
artifact maturity and enforced in the release pipeline.

**Upstream comparison**

- .NET: Includes the package validation baseline, suppressions, and the install check in the release build.
- Python: The release workflow enforces package directory resolution and build artifact generation.

**Decision.** A release is not sufficient on a successful build alone. CI must also be responsible for
whether the actual installation and compatibility hold.

**Acceptance criteria**

- A stable artifact must pass the binary or API compatibility gate.
- The release pipeline verifies an actual resolve and build in a sample consumer project.

**Evidence** [30 packaging-compatibility](../upstream/snapshots/d0a4165f/features/30-packaging-compatibility.md)

---

## OPS-026 Cross-language compatibility is judged by the public surface and behavior

**Requirement.** Cross-language compatibility must be judged by the public surface, the behavioral
contract, facade preservation, and changelog-driven review, not by an identical version scheme.

**Upstream comparison**

- .NET: Maintains the public surface with version metadata, package validation, and conformance testing.
- Python: Maintains re-exports and the install surface even after a repo split and records promotions and compatibility adjustments in the changelog.

**Decision.** The user's requirement of "follow Java conventions but put behavioral compatibility
first" is worked out from the packaging perspective. Facade preservation and change review matter
more than matching version numbers.

**Acceptance criteria**

- Even when a repo split or a package move happens, whether the facade or the re-exports are preserved is stated.
- A stage promotion, a compatibility adjustment, or a central version change triggers a snapshot re-review through the changelog or an equivalent documentation signal.

**Evidence** [30 packaging-compatibility](../upstream/snapshots/d0a4165f/features/30-packaging-compatibility.md)

---

## What this document does not cover

| Topic | Owning document |
| --- | --- |
| The serialization format of sessions and checkpoints | [06 Sessions and conversation state](06-sessions.md) |
| Hosting routes and protocol binding | [10 Hosting and protocols](10-hosting.md) |
| Per-provider wire contracts and adapter surfaces | [12 Provider integrations](12-providers.md) |
| The business meaning of an individual workflow graph | [09 Workflows and orchestration](09-workflows.md) |
