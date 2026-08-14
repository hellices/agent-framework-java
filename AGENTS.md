# Agent Framework for Java Repository Instructions

## Repository purpose

This repository implements Microsoft Agent Framework's observable execution semantics for Java.
The core deliverable is an embeddable `AgentEngine`, not an application server or DI container.
Read the approved designs under `docs/design/`, the requirements under `docs/requirements/`, and
the pinned upstream metadata under `docs/upstream/` before changing contracts.

This is a community implementation, not an official Microsoft product. Maven coordinates and Java
packages use `io.github.hellices.agentframework`; do not introduce `com.microsoft.*` production or
build packages unless ownership transfers to Microsoft through a reviewed migration.

## Source of truth

Use evidence in this order:

1. Pinned upstream production source.
2. Pinned upstream conformance and integration tests.
3. Pinned specifications and decision records.
4. Official Microsoft documentation.
5. Samples and README files.

Do not mix current upstream `main` behavior into the pinned snapshot without a reviewed delta.

## Architecture boundaries

- API modules must not depend on Spring, Quarkus, Jakarta EE, Micronaut, or provider SDKs.
- Engine modules depend only on public API and explicitly approved framework-neutral libraries.
- Provider and host adapters implement public ports and never reference engine internals.
- Core code does not create DI containers, HTTP servers, executors, schedulers, shutdown hooks,
  transaction managers, or global registries.
- Samples and testkits are never product dependencies.
- Provider-specific behavior is not promoted to a core contract without cross-provider evidence.

## Build ownership

- The build is Gradle Kotlin DSL only. Maven files must never be reintroduced.
- The Gradle Wrapper pins Gradle 9.7.0 with a verified distribution checksum.
- Dependency and tool versions live only in `gradle/libs.versions.toml`.
- Shared build behavior lives in the `build-logic` included build, never copied into project scripts.
- google-java-format runs only in `agentframework.quality-conventions` on the Gradle runtime JDK 17.

## Standard workflow

1. Read the relevant design, public contract, usages, and neighboring tests.
2. Define the affected projects and observable success criteria.
3. For behavior changes, add a failing unit or contract test first.
4. Make the smallest coherent implementation that passes the test.
5. Run the narrowest relevant Gradle task, then expand verification by risk.
6. Review the diff for public API, dependency, session-format, telemetry, and compatibility impact.

Reuse existing helpers and fixtures. Keep edits inside the affected projects. Do not hide failures
with broad catches, silent fallbacks, deleted assertions, or `@Disabled`.

## Merge policy

Pull requests are merged with squash merge so `main` receives one coherent commit per pull request.
Intermediate branch commits may be used during development. Before merging, ensure the pull request
title describes the final squashed commit and the pull request body records the completed change.
Merge commits and rebase merges are not used.

## Review loop

Every push to a pull request branch requests a Copilot review, and the loop continues until a
review returns no findings:

1. Push, then request a review from Copilot on the pull request.
2. Wait for the review rather than assuming silence means approval.
3. Reply to each inline comment describing what changed and why, then resolve the thread.
4. Check whether further comments arrived, and repeat from step 1 if the response required a push.

Reply with the reasoning, not just a confirmation: when a suggestion was declined or implemented
differently, say which and why. A green pipeline is not evidence that a review is unnecessary. Every
defect found in this repository so far was found while all checks were passing.

## Verification contract

Local and CI verification use the same Gradle tasks:

```bash
./gradlew policyCheck
./gradlew quality
./gradlew testJava17 testJava21 testJava25
./gradlew check
```

- CI-only quality logic is prohibited.
- Deterministic tests do not retry.
- A quality failure is never covered by a passing compatibility test.
- Public API changes require compatibility analysis after the first release.
- Instruction, workflow, contract, or build changes run `./gradlew policyCheck`.
- Final reports list commands run, outcomes, and any checks that could not run.

## Sensitive data

Prompt bodies, model responses, tool arguments, credentials, and personal agent traces are not
recorded by default. Commit only redacted examples. Keep local agent permissions, model choices,
tokens, credentials, and run artifacts in ignored files.

## Prohibited changes

- Do not commit secrets or local credentials.
- Do not reintroduce Maven build files.
- Do not automatically release, force-push, or change AKS/ARC infrastructure.
- Do not add framework types to core public APIs.
- Do not add blanket lint, vulnerability, or compatibility suppressions.
- Do not use `pull_request_target` or an unpinned external action.
- Do not move the upstream pin without a reviewed behavior delta.
- Do not treat exact natural-language output or exact tool-call order as a long-term contract.
