# Getting started

This guide takes you from a clone to a merged change. It assumes you have not seen the repository
before.

## 1. Prerequisites

| Tool | Version | Why |
| --- | --- | --- |
| JDK | 17 | The build runs on 17 and compiles with `--release 17` |
| Eclipse Temurin | 21 and 25 | Compatibility tasks run tests on these launchers |
| Git | any recent | Wrapper validation and policy tests read repository state |

Gradle never downloads a JDK. Toolchain auto-provisioning is disabled on purpose so that the JDK
used locally is the same one an operator installed on the CI runner.

Check what Gradle can see:

```bash
./gradlew -q javaToolchains
```

If 21 or 25 is missing, install them and re-run. Until then, use `testJava17` and let CI run the
rest.

## 2. Verify a clean checkout

```bash
git clone https://github.com/hellices/agent-framework-java.git
cd agent-framework-java
./gradlew check
```

A clean checkout must pass before you change anything. If it does not, that is a repository bug and
worth an issue rather than a local workaround.

No credential is needed for any of this. The whole verification contract — `policyCheck`, `quality`,
`testJava17`, `testJava21`, `testJava25`, and `check` — runs offline: no test calls a network or
reads a provider key. The single exception is `./gradlew :samples:sample-standalone:run`, which calls
a real OpenAI-compatible endpoint and therefore needs `OPENAI_API_KEY` exported (optionally
`OPENAI_BASE_URL` and `OPENAI_MODEL`). It is a sample, not part of `check`, so skipping it leaves the
verification contract complete. See the
[adapter README](../../providers/agent-framework-openai/README.md) for the variables and the
credential handling.

## 3. Understand the layers

Read in this order. Each answers a different question.

1. [Requirements](../requirements/README.md) — what Java must build, as numbered contracts.
2. [Foundation design](../design/foundation-design.md) — why the engine and the host are separate.
3. [Module composition](../design/module-composition.md) — where code is allowed to live.
4. [Upstream analysis](../upstream/snapshots/d0a4165f/README.md) — how the original framework behaves.

The upstream analysis is evidence, not instruction. When it and a requirement disagree, the
requirement wins, because the requirement is where a Java decision was made.

## 4. Pick your first change

Every requirement has a stable id such as `AGT-002`. Good first targets share three traits:

- graded **Required** so the decision is settled,
- staged **MVP** so nothing else must land first,
- owned by `agent-framework-api` so no execution machinery is needed.

For example, `AGT-002` requires that every agent expose a stable identifier, generating one when the
caller does not supply it. Its acceptance criteria are already written and testable.

## 5. Work the change

The repository expects test-first development because acceptance criteria are already stated in the
requirement.

```bash
# 1. Write the failing test in the owning module
./gradlew :agent-framework-api:testJava17 --tests '*AgentIdentityTest*'
# Expect: FAIL

# 2. Implement the smallest change that satisfies the requirement

# 3. Confirm
./gradlew :agent-framework-api:testJava17 --tests '*AgentIdentityTest*'
# Expect: PASS

# 4. Widen by risk
./gradlew policyCheck quality testJava17
```

Formatting is applied by a tool, never argued about in review:

```bash
./gradlew spotlessApply
```

## 6. Adding a dependency

Versions live in `gradle/libs.versions.toml`. Inline versions in a build file fail
`BuildContractPolicyTest`.

```bash
# after editing the catalog and the module build file
./gradlew :agent-framework-api:resolveAndLockAll --write-locks
```

Commit the regenerated `gradle.lockfile`. An unlocked classpath fails resolution.

## 7. Commit and open a pull request

Reference the requirement id so the contract and the code stay linked:

```text
feat(api): generate a stable agent identifier

Implements AGT-002. An agent created without an explicit id now exposes a
generated identifier that stays constant for the instance lifetime, so
telemetry can attribute a run without conditional handling.
```

State in the pull request:

- the observable behavior that changed,
- the requirement ids involved,
- the commands you ran and their outcome,
- any check you could not run locally and why.

## 8. What CI does

| Job | Runner | Scope |
| --- | --- | --- |
| Fork minimum verification | `ubuntu-latest` | Fork pull requests: policy, quality, JDK 17 tests |
| Trusted quality | `arc-java-build` | Policy and quality on JDK 17 |
| Trusted compatibility | `arc-java-build` | Tests on JDK 17, 21, and 25 |
| Verify result | `ubuntu-latest` | Requires exactly one complete verification path |

Trusted jobs run on a self-hosted scale set described in the
[runner contract](github-actions-runner-contract.md). There is no CI-only quality logic; every check
above is a Gradle task you can run locally.

If trusted jobs sit in `queued` and never start, the scale set is not registered for this
repository. That is an infrastructure state, not a problem with your change. Verify locally and say
so in the pull request:

```bash
./gradlew policyCheck quality testJava17 buildLogicTest
./gradlew publishAllPublicationsToBuildDirectoryRepository
```

## Troubleshooting

**`Cannot find a Java installation ... matching languageVersion=21`**
Temurin 21 is not installed. Install it or run `testJava17` only.

**`The following files had format violations`**
Run `./gradlew spotlessApply`.

**A policy test fails after you moved or renamed a document**
The policy suite reads repository files by path. Update the test and the document together; that
coupling is intentional, so structure cannot drift silently.

**Gradle reports every policy task as up to date after you edited a document**
The policy project declares repository files as task inputs. If this happens, the file you edited
sits in an excluded directory such as `build/` or `.gradle/`.
