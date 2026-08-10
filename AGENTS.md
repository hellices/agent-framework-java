# Agent Framework for Java Repository Instructions

## Repository purpose

This repository implements Microsoft Agent Framework's observable execution semantics for Java.
The core deliverable is an embeddable `AgentEngine`, not an application server or DI container.
Read the approved designs under `docs/superpowers/specs/` and the pinned upstream metadata under
`docs/upstream/` before changing contracts.

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

## Standard workflow

1. Read the relevant design, public contract, usages, and neighboring tests.
2. Define the affected modules and observable success criteria.
3. For behavior changes, add a failing unit or contract test first.
4. Make the smallest coherent implementation that passes the test.
5. Run the narrowest relevant Maven Wrapper command, then expand verification by risk.
6. Review the diff for public API, dependency, session-format, telemetry, and compatibility impact.

Reuse existing helpers and fixtures. Keep edits inside the affected modules. Do not hide failures with
broad catches, silent fallbacks, deleted assertions, or `@Disabled`.

## Verification contract

- Local and CI verification uses `./mvnw`; CI-only quality logic is prohibited.
- Deterministic tests do not retry.
- Live provider retries report the first failure separately from eventual success.
- Public API changes require compatibility analysis after the first release.
- Engine, session, streaming, tool, and provider changes run their affected conformance suites.
- Instruction, workflow, or harness schema changes run harness policy regression.
- Final reports list commands run, outcomes, and any checks that could not run.

## Sensitive data

Prompt bodies, model responses, tool arguments, credentials, and personal agent traces are not
recorded by default. Commit only redacted examples. Keep local agent permissions, model choices,
tokens, credentials, and run artifacts in ignored files.

## Prohibited changes

- Do not commit secrets or local credentials.
- Do not automatically release, force-push, or change AKS/ARC infrastructure.
- Do not add framework types to core public APIs.
- Do not add blanket lint, vulnerability, or compatibility suppressions.
- Do not move the upstream pin without a reviewed behavior delta.
- Do not treat exact natural-language output or exact tool-call order as a long-term contract.
