# Agent Framework for Java Engineering Harness Design

- Status: pending review
- Date: 2026-08-10
- Baseline: Microsoft Agent Framework upstream
  `d0a4165f170193ba1d026a259af40d35bb7eaefe`
- Scope: repository instructions, the Java quality gate, the compatibility harness, the agent task graph,
  harness regression, and the GitHub Actions and AKS ARC operational boundary

> **Subsequent decision:** the build tool and ARC runner configuration are superseded by the
> [Gradle Kotlin DSL and Java ARC Foundation design](./gradle-kotlin-arc-foundation-design.md).
> The Maven and general-purpose `aks-runners` content is not used as an implementation baseline.

## 1. Goal

This repository implements the observable execution semantics of the Microsoft Agent Framework (MAF)
in Java. The core must not be tied to Spring Boot, Quarkus, Jakarta EE, Micronaut, or any particular
DI container; each framework participates only as a host or as an optional integration module.

The engineering harness has the following four goals.

1. People and coding agents use the same architecture, quality, and verification rules.
2. The boundary between the Java core and each framework integration is verified automatically.
3. Differences between the behavior of the pinned MAF upstream and the Java implementation are tracked reproducibly.
4. The harness's own instructions, task graph, and evaluation results do not regress.

The harness is not part of the product runtime. A product artifact contains no coding agent, no CI
runner, no evaluation service, and no vendor-specific agent SDK.

## 2. Premises and provisional decisions

The repository currently holds only the approved foundation design and the upstream snapshot
documents. There is no Maven project, no Java source, no GitHub Actions, and no Git metadata yet.

The following decisions are used as the defaults of this design.

- The minimum supported Java is 17.
- CI verifies on Eclipse Temurin JDK 17, 21, and 25.
- While Java 17 is supported, the test foundation uses the JUnit 5 line. JUnit 6, which requires Java 21,
  is not adopted.
- The Maven 3.9 line and the Maven Wrapper are used. Maven 4 is handled as a separate decision after its GA
  and plugin compatibility are confirmed.
- `AGENTS.md` is used as the repository's vendor-neutral instruction source.
- Ordinary Java builds run on unprivileged ARC runners. Only work that requires Docker is isolated onto a separate
  privileged runner.
- Exact plugin versions are re-checked against Maven Central and the official releases at implementation time and pinned
  in the root POM properties, which act as the single version catalog.

## 3. Considered approaches

### 3.1 Repository-monolithic

Every agent setting, evaluation runner, ARC Helm values file, Kubernetes policy, and CI definition is
managed in this repository.

The advantage is that the entire configuration is discoverable from a single repository. The
disadvantages are that application changes and cluster operational permissions become coupled, that
ARC credential and runner security changes fall inside the blast radius of a product PR, and that
reuse from another repository is difficult.

### 3.2 Organization-platform-centralized

Most of the quality and agent workflows live in organization-wide reusable workflows or in a
separate platform repository.

The advantage is that consistency across several repositories is easy to maintain. The disadvantages
are that a developer who has checked out only this repository has difficulty understanding or
reproducing the real rules and execution paths, and that a central change can alter this
repository's verification without notice.

### 3.3 Layered portable harness

The product contract, the verification configuration, and the workflow entry points live in the
repository. Personal settings live locally, and runner and cluster security live in the platform
domain. Even when a central reusable workflow is used, the invoked version is pinned and the same
Maven command can be run locally.

**Decision:** adopt 3.3. It satisfies repository reproducibility and operational separation
together, and it does not lose the product rules when the workflows are later promoted to
organization-wide ones.

## 4. Ownership boundaries

### 4.1 Managed in the repository

The following assets must be reviewed together with product changes and are therefore kept in the
repository.

- `AGENTS.md` and the thin per-vendor instruction adapters
- the Maven Wrapper, the root parent POM, and the BOM
- `.editorconfig`, `.gitattributes`, and the formatter and lint configuration
- the compiler, Enforcer, dependency, static analysis, and architecture rules
- the unit, property, contract, integration, and conformance tests
- the provenance of MAF test vectors and the upstream pin
- the agent harness artifact schema and the deterministic eval fixtures
- the GitHub Actions workflows, composite actions, and Dependabot configuration
- the CODEOWNERS, dependency review, CodeQL, and OpenSSF Scorecard configuration
- public API compatibility exceptions and security suppressions
- the release SBOM and provenance generation rules

Every suppression records the rule ID, the reason, the scope, and the removal condition. An undated
global suppression is not permitted.

### 4.2 Managed locally

The following assets are tied to a personal environment, to credentials, or to execution cost and
are therefore not committed.

- personal agent permissions and model selection
- API keys, GitHub tokens, and Azure credentials
- per-IDE user settings
- the local Maven repository and build cache
- raw agent execution traces and temporary worktrees
- the local OpenTelemetry Collector endpoint
- per-person cost and turn limits and experimental model canaries

The repository may hold only example files that contain no secrets. The real file names are listed
in `.gitignore`, and secret scanning must fail when one is missing.

### 4.3 Managed in the platform or in a separate infrastructure repository

The following assets are not kept in the application repository.

- the Helm releases and values for the ARC controller and the runner scale sets
- the ARC GitHub App private key and the Kubernetes Secret
- runner image builds and the image admission policy
- the AKS namespace, RBAC, Pod Security Admission, and NetworkPolicy
- the CI-only node pool, the cluster autoscaler, and the Spot policy
- the Azure Monitor or Prometheus collection configuration
- the GitHub runner group and the organization action policy
- the Entra federated identity credential and the Azure role assignments

An application workflow references only the stable contract of a runner label. It does not infer or
create a runner's pod spec, credentials, or cluster permissions.

## 5. `AGENTS.md` design

### 5.1 Role

`AGENTS.md` is not a long development textbook but the execution contract for working in this
repository. It must be reviewable by people and must keep the same meaning across different agent
products.

It contains the following.

1. the repository's purpose and current implementation stage
2. links to the authoritative design and the upstream snapshot
3. module responsibilities and forbidden dependencies
4. the Java and Maven baselines and the standard commands
5. the pre-change exploration, test-first, and verification procedure
6. task risk tiers and approval conditions
7. the MAF conformance and provenance rules
8. the security, sensitive data, and telemetry rules
9. the documentation and public API change rules
10. the completion evidence format

The tool names, model names, token prices, or user home paths of a particular agent product are not
included.

### 5.2 Draft of the core instructions

The final `AGENTS.md` states the following rules.

#### Repository identity

- This project provides an embeddable `AgentEngine`, not a particular server framework.
- The observable execution semantics of the pinned upstream take precedence over a literal port of API names.
- The core does not own a DI container, an HTTP server, an executor, a scheduler, or a shutdown
  hook.

#### Dependency rules

- The API does not depend on Spring, Quarkus, Jakarta EE, Micronaut, or a provider SDK.
- The engine depends only on the API and on explicitly approved minimal common libraries.
- An adapter implements only public ports and does not reference an engine internal package.
- A host integration is responsible only for composition, and the core never references a host in return.
- Samples and the testkit are not dependency targets of a product artifact.

#### Working procedure

- Before a change, read the related design, the public contract, the usages, and the neighboring tests.
- A behavior change starts with a reproduction test or a failing contract test.
- Search for an existing helper and fixture, then reuse it or make it shared.
- Limit the change scope to the affected modules.
- Start from narrow verification and expand to matrix and deep verification according to the change risk.
- Do not hide a failure by deleting a test or turning it into `@Disabled`.

#### Completion criteria

- The requested behavior is verified through the public API or through observable events and state.
- The formatter, compiler, unit, architecture, and affected contract tests passed.
- For a public API, dependency, upstream conformance, or instruction change, the dedicated
  gate also passed.
- The commands run, their results, and the verification that could not be run are stated in the final response.

#### Prohibitions

- Do not record secrets, prompt bodies, tool arguments, or model responses in default telemetry.
- Do not add a broad catch or a silent fallback that turns a failure into the shape of a success.
- Do not mix the current state of upstream `main` with the pinned snapshot.
- Do not promote provider-specific behavior to a common core contract without verification.
- Do not let an agent automatically approve a release, a force push, an infrastructure change, or a widened
  suppression.

### 5.3 Per-vendor adapters

A per-vendor file does not duplicate `AGENTS.md`.

- `CLAUDE.md`: a short link telling the reader to read `AGENTS.md` first, plus only the Claude-specific local file names
- `GEMINI.md`: a link in the same style
- `.github/copilot-instructions.md`: a link in the same style

Per-tool instruction differences are minimized. A symlink can behave differently on a Windows
checkout and in some agent loaders, so a small ordinary file is used.

## 6. Java quality harness

### 6.1 Build baseline

- Commit the Maven Wrapper and the wrapper distribution checksum.
- Manage every dependency and plugin version in the root POM.
- The compiler uses `--release 17`, UTF-8, and a reasonable `-Xlint`.
- Warning-as-error is not applied globally from the start. It is promoted starting with the categories whose signal
  is stable in new code.
- Use `project.build.outputTimestamp` and the reproducible archive settings.
- Use Enforcer to verify the Java and Maven ranges, plugin versions, release dependencies, and the bytecode
  level.
- Dependency convergence can produce false positives in a framework BOM combination, so it is applied per module
  together with the intent.

`.mvn/jvm.config` holds only the options that are actually needed. Do not globally add `--add-opens`,
attach permission, or a javac internal export without justification for the sake of a test tool.

### 6.2 The role of each quality tool

| Tool | Role | Initial application |
| --- | --- | --- |
| Spotless | Deterministic Java, POM, and other text formatting | Required on a PR |
| Checkstyle | Public API Javadoc, naming, imports, and forbidden patterns | Required on a PR with narrow rules |
| Maven Enforcer | Toolchain, dependency, and plugin policy | Required on a PR |
| Maven Dependency Plugin | Undeclared and unused dependencies | Required on a PR, explicit exceptions allowed |
| PMD | Source-level bug patterns and complexity | Required on a PR after a baseline is generated |
| SpotBugs | Bytecode-level defects | Required on a PR after a baseline is generated |
| ArchUnit | Framework neutrality and module dependency | Required on a PR from the first code |
| JaCoCo | Coverage observation and ratchet | Start from reporting, then a diff ratchet |
| Revapi | Public API binary and source compatibility | Required after the first release |
| PIT | Defect detection power of the state machine tests | main and scheduled, plus release |
| CycloneDX | Aggregate SBOM | package and release |
| CodeQL/dependency review | SAST and new dependency risk | GitHub PR and schedule |

Error Prone and NullAway provide a high signal, but they complicate the combination of per-JDK javac
internal access and annotation processors. They are promoted optionally after their stability and
false positive rate are measured on the Java 17, 21, and 25 matrix. When nullness annotations are
introduced into the public API, the compatibility of JSpecify with each framework's nullness
interpretation is decided separately.

OWASP Dependency-Check carries a heavy operational burden from the NVD API and CPE matching, so it
is not bound to every local `verify`. GitHub dependency review and CodeQL are used as the PR
baseline, and Dependency-Check or a Trivy SBOM scan is operated on the scheduled and release paths
first and promoted according to its signal quality.

### 6.3 Test layers

#### Unit

- Use the JUnit 5 line and AssertJ.
- Verify core state transitions with deterministic fake model, tool, and session implementations.
- Use Mockito only in a limited way at external adapter boundaries.
- Inject time, IDs, the scheduler, and the random source.

#### Property

Verify the following invariants with jqwik.

- the session serialize and deserialize round trip
- preservation of tool call and result pairing
- the prohibition of an additional state commit after cancellation
- the determinism of the event sequence for the same scripted provider input
- once the workflow graph is added, the fan-in, checkpoint, and resume invariants

Record the seed and the shrunk counterexample in a CI artifact.

#### Architecture

Prevent the following with ArchUnit and Maven module graph verification.

- a reference from the core to a framework or provider package
- a reference from an adapter to an engine internal package
- a reference from the product to a sample or the testkit
- the creation of an executor, a server, or a DI container in the core
- the exposure of a framework type in the public API

Do not use a simple package name heuristic alone; verify the module dependencies and the bytecode
imports together.

#### Integration

- Use Maven Failsafe and a separate integration-test module.
- The Spring Boot, Quarkus, Jakarta EE/MicroProfile, and Micronaut adapters verify composition and lifecycle
  delegation with a real container or framework test harness.
- Use Testcontainers only for the session adapters that need an external store.
- Do not send a test that needs no container to a privileged runner.

#### Compatibility

Do not turn the support range into the cartesian product of all versions.

- core: JDK 17, 21, 25
- each framework adapter: the documented minimum supported line and the current supported line
- provider: the shared provider contract suite
- session store: the shared persistence contract suite
- sample: Maven Invoker or a standalone sample build

A framework BOM update must pass on both the minimum line and the current line.

### 6.4 Maven verification profiles

| Profile/command | Purpose | When it runs |
| --- | --- | --- |
| `fast` | Format check, compile, unit, ArchUnit | Local iteration |
| Default `verify` | Static analysis, unit/property/contract, coverage | Every trusted PR |
| `compat` | The JDK and framework support matrix | Affected PRs, main, schedule |
| `deep` | Testcontainers, MAF conformance, reproducibility | main, nightly |
| `mutation` | PIT on the engine's core state transitions | Engine changes, nightly |
| `release` | Revapi, full matrix, SBOM, source/javadoc, signing | Release |

The actual shape of the commands is settled in the implementation plan. Every CI job invokes a Maven
command that can be run locally, and no quality logic exists only in CI.

## 7. MAF compatibility harness

### 7.1 Baseline

MAF upstream is pinned by commit SHA. Production source and conformance tests take precedence over
documentation. The current .NET `AgentConformance.IntegrationTests` provides an abstract conformance
suite structure that provider implementations inherit.

The Java testkit uses the same pattern.

- a shared abstract contract suite
- a fixture interface that adapters implement
- deterministic fake fixtures
- real provider integration fixtures
- test vectors and upstream provenance metadata

Do not confuse a retried success of a real provider test with the stability of a deterministic test.
The deterministic suite must pass without retries. The retry count and the first failure of a live
provider suite are recorded in the results.

### 7.2 Initial golden scenarios

#### Upstream-equivalent scenarios

1. A run with no input does not fail.
2. The response to a string input and the agent ID are correct.
3. A message input and a multiple-message input keep the same meaning.
4. After two turns, the session history holds four user and assistant messages in order.
5. The concatenated streaming results of the scenarios above contain the expected text.
6. Execution is possible with agent instructions alone.
7. Single and consecutive tool calls are correctly linked to the results and the history.
8. A streaming tool call has the same meaning.

#### Mandatory Java engine extensions

9. After a session serialization round trip, the same history and state version are held.
10. Tool failure, approval rejection, and the iteration limit propagate as a typed failure.
11. After streaming cancellation, the event and session commit boundaries are consistent.
12. Backpressure or a slow subscriber does not break event ordering.
13. Re-running with the same idempotency key does not create duplicate tool side effects.
14. In telemetry, span parentage is correct and sensitive data is disabled by default.

Workflows are an independent design after the MVP, so no empty module and no passing placeholder
test is created for them now.

### 7.3 Normalization and provenance

A compatibility comparison separates the following.

- mandatory invariants: role, message count and order, agent ID, tool pairing, finish reason, state change
- permitted variation: natural language wording, optional fields of provider usage, chunk boundaries
- forbidden variation: inverted event order, duplicate tool execution, a commit after cancellation, a sensitive-data span

Each vector records the upstream repository, commit, path, test name, derivation method, and the
date it was checked. When MIT source is translated directly, the license notice obligation is
honored. Where possible, the test body is not copied; an idiomatic Java test is written
independently from the observable requirements.

### 7.4 Upstream delta

A weekly workflow detects changes on the following paths between the current pin and a candidate
upstream SHA.

- the .NET abstractions and conformance tests
- the Python core public types and conformance/evaluation tests
- the official specification and decision documents

A detected change produces an issue or a review artifact, not an automatic implementation or a
moved pin. A person approves the behavioral difference and its Java impact, and only then are the
snapshot, the matrix, and the pin updated atomically.

## 8. Agent task graph

### 8.1 Principles

- Use an explicit DAG rather than an unbounded self-loop.
- A node has one responsibility and a small input and output contract.
- An edge carries a verifiable artifact rather than the whole conversation.
- Separate the read, write, verify, and release permissions.
- A failure or missing evidence is not substituted with the shape of a success.
- Abbreviate the graph according to the task's risk and size, but never bypass a mandatory gate.

### 8.2 Standard DAG

```text
intake
  |
  v
risk-and-scope
  |
  +------> context --------+
  |                        |
  +------> impact ---------+--> plan
                                  |
                                  v
                            failing-test
                                  |
                                  v
                              implement
                                  |
             +--------------------+--------------------+
             |                    |                    |
             v                    v                    v
         build-test          static-arch         conformance
             |                    |                    |
             +--------------------+--------------------+
                                  |
                                  v
                         read-only-review
                                  |
                                  v
                         evidence-and-score
                                  |
                      +-----------+-----------+
                      |                       |
                      v                       v
                 human-gate              PR/complete
```

### 8.3 Artifact contracts

The repository holds JSON Schema or an equivalent portable schema.

- `TaskIntent`: the request, the non-goals, and the success criteria
- `ChangeContext`: the related design, modules, symbols, and existing tests
- `ImpactSet`: the paths that may change, the affected modules, and the risk tier
- `TestPlan`: the tests that must fail first and the verification commands that follow
- `ChangeSummary`: the changed files, the public contract, and the migration impact
- `VerificationResult`: the command, the exit code, the duration, and the artifact link
- `ReviewResult`: correctness and security findings and their disposition
- `RunScore`: the outcome, scope compliance, cost and time, and flake information

The raw per-run artifacts live in session/local or in CI artifact storage and are not committed.
Only the schema, the fixtures, and the anonymized regression baseline live in the repository.

### 8.4 Risk-based paths

| Tier | Example | Mandatory path |
| --- | --- | --- |
| Low | Typos, links, non-behavioral documentation | context, targeted check, review |
| Moderate | Internal implementation, tests, adapter changes | The full standard DAG |
| High | Public API, engine state, session format, dependencies | The full DAG plus compatibility plus a human gate |
| Critical | Release, credentials, workflow permissions, ARC/infra | Automatic execution forbidden, or a separate approval workflow |

A POM change is not treated as critical in itself. The risk is classified by the dependency scope,
the execution permissions of a build plugin, and whether release credentials are accessed.

### 8.5 Permissions and failure recovery

- The context, impact, and review nodes are read-only.
- The implement node modifies only what is inside the `ImpactSet` scope.
- The verify node does not modify source.
- Commit, push, release, and infrastructure changes are a separate permission and are disabled by default.
- A build failure may return to implement once, together with the evidence of its cause.
- If the same failure repeats or the scope grows, stop and escalate to a person.
- Resume is based on the verified artifacts and the repository state, not on conversation memory.

## 9. Harness regression strategy

### 9.1 Three layers

#### Layer A: deterministic repository fixtures

These work without an LLM and without an external network.

- detecting architecture violations
- incorrect module impact analysis
- blocking modification of forbidden files
- detecting missing verification evidence
- MAF scenarios based on deterministic fakes

They run on every PR and are not retried.

#### Layer B: pinned upstream conformance

The invariants of the MAF snapshot are applied to the Java fixtures and adapters. The upstream pin,
the Java harness version, and the vector provenance are included in the results.

This runs when the engine, the API, a provider, a session, or a conformance vector changes.

#### Layer C: agent instruction and tool-use eval

In a small temporary repository fixture, a real agent is asked to perform the following tasks.

- rejecting an incorrect request to add a framework type to the core
- a reproduction test and a fix for an engine bug
- reusing the contract suite when a provider adapter is added
- selecting the impact and the verification of a POM dependency change
- isolating the cause of a failing CI run
- reporting an upstream delta without an implementation change

An external agent invocation runs only on a trusted trigger, on a schedule, or by manual dispatch. A
fork PR does not run an eval that needs credentials.

### 9.2 Graders

A hard gate must be as deterministic as possible.

- the build and test exit codes
- whether the modified files are inside the `ImpactSet`
- whether a forbidden dependency or tool was used
- whether the required tests and evidence exist
- whether the public behavior and the MAF invariants hold
- whether a secret or a sensitive prompt is absent from the artifacts

An LLM grader is used only as an auxiliary signal for items that are hard to verify
deterministically, such as explanation quality. An LLM grader alone does not block a merge.

Exact natural language, a fully identical diff, or an exact tool-call sequence is not used as a hard gate.
The permitted tool set, the absence of dangerous tools, and the verification results are evaluated.

### 9.3 Baseline and differential evaluation

The baseline key contains the following.

- the `AGENTS.md` hash
- the harness schema and version
- the eval fixture version
- the model, provider, and version
- the tool runtime version
- the JDK and Maven version
- the upstream MAF SHA

A change to the instructions, the graph, a grader, the model, or the toolchain runs the same fixture
in the old and new configurations and compares the difference. The comparison metrics include not
only the pass rate but also scope violations, unsafe actions, duration, turn and tool counts, and
cost.

### 9.4 Flakes and canaries

- The permitted flake count of a deterministic suite is zero.
- A live agent or provider suite runs the same conditions several times and records a Wilson interval or a
  minimum-sample pass rate.
- Failures are not hidden by retries; first-attempt and eventual-pass results are kept separate.
- A new model, instruction set, or graph is canaried on a representative suite before it is expanded to the full suite.
- A drop in the pass rate of an existing stable suite, the appearance of a scope violation, or a large increase in cost
  blocks promotion.
- Per-person absolute cost figures are not committed, and the CI budget policy is kept separate from the trend baseline.

### 9.5 Scorecard and retention

Each run leaves at least the following metrics.

- the suite and fixture version
- the pass/fail outcome and the per-grader results
- the first-attempt and the retry results
- the changed files and modules
- the forbidden-action count
- the duration, the turn and tool counts, and optionally the cost
- the model, toolchain, and upstream identity
- the location of the redacted trace

A trace can contain a source diff and credentials, so access permissions, a retention period, and
redaction are applied. A public artifact does not contain raw prompts or model responses by default.

## 10. GitHub Actions and AKS ARC

### 10.1 Workflow composition

The repository holds the following workflow entry points.

| Workflow | Trigger | Role |
| --- | --- | --- |
| `ci.yml` | pull request, push | fast/default verify |
| `compatibility.yml` | Affected PRs, main, schedule, dispatch | The JDK and framework matrix |
| `security.yml` | PR, main, schedule | dependency review, CodeQL, SBOM scan |
| `harness-regression.yml` | Harness/instruction changes, schedule | Layer A through C eval |
| `upstream-watch.yml` | schedule, dispatch | Reporting the MAF delta against the pin |
| `release.yml` | A tag or a protected dispatch | Full gate, publish, attest |

Duplicated steps are extracted into a repository composite action or into a reusable workflow pinned
by SHA. When a reusable workflow is called from an external repository, its commit SHA is pinned.

### 10.2 Runner separation

#### `arc-java-build`

- ordinary Maven builds and non-container tests
- ephemeral and non-privileged
- review of a read-only root filesystem and a minimal service account
- internal service access blocked by default
- starting at `minRunners: 0` and tuned by queue latency

#### `arc-java-dind`

- running only Testcontainers and unavoidable image builds
- a separate namespace, runner group, and node pool
- the privileged Pod Security scope minimized
- the metadata endpoint and Kubernetes API access blocked
- only trusted branches and approved PRs permitted
- `minRunners: 0`

#### `arc-java-release`

- access to the protected environment and repository only
- accepts no ordinary PR job
- uses OIDC and short-lived credentials only
- no cluster permission beyond artifact publishing

The current product is a Java library, so a deploy runner does not need to be created in advance. It
is designed separately when an actual Azure deployment target appears.

### 10.3 Forks and the trust boundary

- Do not run external fork code on a privileged or internal-network ARC runner.
- If the repository is public, the minimal verification of a fork PR runs on a GitHub-hosted runner, or a restricted
  quarantine runner is used after maintainer approval.
- Do not check out and build the PR head under `pull_request_target`.
- Do not use an artifact or cache from a low-trust job as an executable in a trusted job.
- Do not run agent evals and live provider tests on a fork PR.

### 10.4 Workflow security

- The workflow-level `GITHUB_TOKEN` starts at `contents: read`.
- Only the permissions a job needs are elevated.
- Every action is pinned to a full commit SHA and updated by Dependabot.
- Checkout credential persistence is disabled when it is not needed.
- Azure and artifact attestation use GitHub OIDC.
- A release uses GitHub Environment reviewers and branch and tag restrictions.
- A stale PR run is cancelled by a concurrency group, but a release run is not cancelled.
- Credentials, Maven settings secrets, and build output are not stored in the cache.

### 10.5 Cache and artifacts

- The Maven dependency cache uses the GitHub cache service.
- The cache key includes the OS, the JDK, and the hash of the root POM and the module POMs.
- A framework compatibility job includes the framework line in the key.
- Build artifacts, test reports, the SBOM, mutation reports, and scorecards have distinct retention.
- Release JARs and the SBOM produce a provenance attestation.
- Ephemeral runner pod logs are shipped to Azure Monitor or an equivalent collector before the pod is deleted.

## 11. Graph engineering operational rules

The goal of graph optimization is not the node count but discovering failures early, cheaply, and
explainably.

### 11.1 Change impact selection

Verification is selected from the module dependency graph and the changed paths.

- documentation-only change: the link, format, and instruction related checks
- API change: compilation of every downstream adapter, Revapi, and the public contract
- engine change: all golden scenarios, property tests, and the mutation targets
- provider change: the provider contract and the shared golden scenarios
- session change: serialization, concurrency, and migration
- build, harness, or instruction change: the full deterministic harness and a representative live canary

Safety is not judged by a path filter alone. A change to the root POM, the BOM, the public API, or
the shared testkit expands to the full downstream set.

### 11.2 Fan-out and fan-in

Independent build, static analysis, and conformance runs are executed in parallel. Fan-in passes
only when every mandatory result exists and succeeded. `if: always()` may be used for report upload,
but it is not used to turn a failed verification into a success.

### 11.3 Budgets

- PR: fast feedback and mandatory correctness
- main and nightly: the broad matrix, mutation, and live conformance
- release: the full matrix, reproducibility, API compatibility, and the supply chain

When the time and cost budget is exceeded, change-impact selection, caching, test splitting, and the
proportion of deterministic fakes are improved before any check is deleted.

## 12. Staged adoption

### Stage 0: repository and contracts

- initializing the Git repository and preparing the default branch and its protection
- `AGENTS.md`, the license, and the contribution and security policies
- the Maven Wrapper, the Java 17 baseline, and the root parent and BOM
- the formatter, Enforcer, and reproducible builds
- a non-privileged `arc-java-build` smoke test in CI

The exit condition is that the same wrapper command reproduces locally and on ARC.

### Stage 1: quality of the first vertical slice

- the minimal API, engine, and testkit modules
- JUnit/AssertJ, deterministic fakes, and part of the upstream golden scenarios
- the ArchUnit boundaries and Spotless
- PMD and SpotBugs collecting a baseline in report mode
- the JaCoCo report and the diff ratchet baseline

The exit condition is that an ordinary response and the single tool loop pass deterministically on
Java 17, 21, and 25.

### Stage 2: contracts and the matrix

- the provider and session store contract suites
- property tests and streaming cancellation
- the Spring Boot, Quarkus, Jakarta EE, and Micronaut integration skeletons, added only in the order they are actually implemented
- the minimum and current framework version matrix
- blocking new PMD and SpotBugs violations

The exit condition is that a core change verifies the same public contract against every implemented adapter.

### Stage 3: compatibility and the agent harness

- the full initial set of MAF conformance vectors and their provenance
- the agent graph artifact schema and the deterministic eval fixtures
- `harness-regression` and `upstream-watch`
- the instruction baseline and the scorecard

The exit condition is being able to reproduce the safety and success-rate difference of an
`AGENTS.md` or graph change.

### Stage 4: supply chain and release

- Revapi and the SemVer policy
- the CycloneDX SBOM, dependency review, CodeQL, and Scorecard
- reproducible build comparison
- protected releases, OIDC, signing, and artifact attestation
- the PIT threshold for the engine

The exit condition is being able to confirm the source, dependencies, API, and build provenance of a
release artifact.

### Stage 5: optimization

- job splitting based on actual duration and queue latency
- ARC node pool and cache tuning
- the promotion decision based on the Error Prone and NullAway pilot results
- live agent eval canaries and flake budget tuning
- selecting the common parts to move into central reusable workflows

## 13. Success criteria

This harness must be able to demonstrate the following.

1. A test change that adds a framework dependency to the core fails on a PR.
2. The same agent scenario satisfies the same invariants standalone and on an implemented host adapter.
3. A change in the pinned MAF conformance is not implemented automatically but appears as a reviewable delta.
4. The success, scope violations, cost, and time of an agent eval can be compared before and after an instruction change.
5. An ordinary PR is verified without a privileged runner and without Azure credentials.
6. A release artifact carries API compatibility, SBOM, provenance, and full matrix evidence.
7. A developer can reproduce every mandatory CI command with the local Maven Wrapper.

## 14. Explicit non-goals

- creating every framework adapter as an empty module in the first stage
- applying every static analysis tool as a zero-warning hard gate from the first PR
- using a live LLM result as the baseline of a unit test
- adding a particular coding-agent vendor's SDK to the product build
- letting an agent perform a release or cluster operation without approval
- managing the ARC controller or the AKS security policy from the application repository
- using exact prompt text or an exact tool sequence as a long-term compatibility contract

## 15. References

### The project and MAF

- [Foundation design](./foundation-design.md)
- [Repository upstream baseline](../upstream/README.md)
- [MAF pinned RunTests.cs](https://raw.githubusercontent.com/microsoft/agent-framework/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/AgentConformance.IntegrationTests/RunTests.cs)
- [Microsoft Agent Framework workflows](https://learn.microsoft.com/en-us/agent-framework/workflows/)
- [Microsoft Agent Framework repository](https://github.com/microsoft/agent-framework)

### Java and Maven

- [Java SE support roadmap](https://www.oracle.com/java/technologies/java-se-support-roadmap.html)
- [Maven version history](https://maven.apache.org/docs/history.html)
- [Maven reproducible builds](https://maven.apache.org/guides/mini/guide-reproducible-builds.html)
- [JUnit documentation](https://docs.junit.org/)
- [Spotless](https://github.com/diffplug/spotless)
- [Checkstyle](https://checkstyle.org/)
- [PMD](https://pmd.github.io/)
- [SpotBugs](https://spotbugs.github.io/)
- [Error Prone](https://errorprone.info/)
- [NullAway](https://github.com/uber/NullAway)
- [JSpecify](https://jspecify.dev/)
- [ArchUnit](https://www.archunit.org/)
- [jqwik](https://jqwik.net/)
- [Testcontainers for Java](https://java.testcontainers.org/)
- [JaCoCo](https://www.jacoco.org/jacoco/)
- [PIT](https://pitest.org/)
- [Revapi](https://revapi.org/)
- [CycloneDX Maven plugin](https://github.com/CycloneDX/cyclonedx-maven-plugin)

### GitHub Actions and ARC

- [About Actions Runner Controller](https://docs.github.com/en/actions/concepts/runners/actions-runner-controller)
- [Deploying runner scale sets](https://docs.github.com/en/actions/how-tos/manage-runners/use-actions-runner-controller/deploy-runner-scale-sets)
- [GitHub Actions secure use](https://docs.github.com/en/actions/reference/security/secure-use)
- [Artifact attestations](https://docs.github.com/en/actions/how-tos/secure-your-work/use-artifact-attestations/use-artifact-attestations)
- [AKS Workload Identity](https://learn.microsoft.com/en-us/azure/aks/workload-identity-overview)
- [Kubernetes Pod Security Admission](https://kubernetes.io/docs/concepts/security/pod-security-admission/)

### Agent graph and evaluation

- [Anthropic agent loop](https://platform.claude.com/docs/en/agent-sdk/agent-loop)
- [Anthropic subagents](https://platform.claude.com/docs/en/agent-sdk/subagents)
- [Anthropic hooks](https://platform.claude.com/docs/en/agent-sdk/hooks)
- [Anthropic evaluation guidance](https://docs.anthropic.com/en/docs/test-and-evaluate/develop-tests)
- [OpenAI Evals](https://github.com/openai/evals)
