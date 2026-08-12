# Documentation

English is the source of truth for every document in this repository. `docs/ko/README.md` is a
curated Korean companion guide; when it disagrees with an English document, the English document
wins.

The rule and its enforcement are defined in
[documentation language and information architecture](design/documentation-language-policy.md) and
checked by `./gradlew policyCheck`.

## Start here

| Question | Document |
| --- | --- |
| What is this project and how do I build it? | [Root README](../README.md) |
| How do I make my first change? | [Getting started](operations/getting-started.md) |
| What must Java build? | [Requirements index](requirements/README.md) |
| Why is it built this way? | [Foundation design](design/foundation-design.md) |
| Where is code allowed to live? | [Module composition](design/module-composition.md) |
| How does the upstream framework behave? | [Upstream snapshot index](upstream/snapshots/d0a4165f/README.md) |
| Do you have a Korean guide? | [Korean companion](ko/README.md) |

## Map

| Location | Responsibility |
| --- | --- |
| `docs/design/` | Approved architecture and engineering decisions |
| `docs/requirements/` | Stable behavioral requirements and acceptance criteria |
| `docs/operations/` | Contributor, CI, runner, and repository operation guidance |
| `docs/upstream/` | Pinned upstream provenance, evidence, feature analysis, and coverage |
| `docs/plans/` | Implementation plans for in-flight work |
| `docs/ko/README.md` | The only maintained Korean companion document |

## Design

- [Foundation design](design/foundation-design.md) — architecture direction, module boundaries, and the initial build order.
- [Module composition](design/module-composition.md) — the module contract that `./gradlew policyCheck` enforces.
- [Engineering harness design](design/engineering-harness-design.md) — repository instructions, quality gates, and the agent work graph.
- [Gradle and Java ARC foundation](design/gradle-kotlin-arc-foundation-design.md) — build tool and runner decisions.
- [Documentation language and information architecture](design/documentation-language-policy.md) — the language and navigation contract.

## Requirements

The requirements are the implementation contract: 244 requirements across twelve documents, each
with a stable id, a .NET and Python comparison, the Java decision, and acceptance criteria.

- [Requirements index](requirements/README.md) — id scheme, grades, release phases, and decision rules.
- [01 agent execution and model calls](requirements/01-agent-execution.md)
- [02 messages and content](requirements/02-message-content.md)
- [03 structured output](requirements/03-structured-output.md)
- [04 tools and the tool call loop](requirements/04-tools.md)
- [05 MCP integration](requirements/05-mcp.md)
- [06 sessions and conversation state](requirements/06-sessions.md)
- [07 interceptors and context management](requirements/07-interceptors.md)
- [08 harness features](requirements/08-harness.md)
- [09 workflows and orchestration](requirements/09-workflows.md)
- [10 hosting and protocols](requirements/10-hosting.md)
- [11 operational quality](requirements/11-operations.md)
- [12 provider integrations](requirements/12-providers.md)

## Operations

- [Getting started](operations/getting-started.md) — clone to merged pull request.
- [GitHub Actions runner contract](operations/github-actions-runner-contract.md) — the runner surface CI depends on.
- [Repository instructions](../AGENTS.md), [contributing guide](../CONTRIBUTING.md), and [security policy](../SECURITY.md).

## Upstream evidence

- [Upstream analysis policy](upstream/README.md) — the pin, the evidence order, and the update rule.
- [Snapshot index](upstream/snapshots/d0a4165f/README.md) — reading order across the 31 feature analyses.
- [Snapshot manifest](upstream/snapshots/d0a4165f/snapshot-manifest.md) — commit, tree, checksum, and package versions.
- [Coverage ledger](upstream/snapshots/d0a4165f/coverage-ledger.md) — proof that no upstream set is unmapped.
- [Compatibility matrix](upstream/snapshots/d0a4165f/compatibility-matrix.md) — per-feature .NET and Python status with the Java target.
- [Feature analyses](upstream/snapshots/d0a4165f/features/) — the 31 per-feature evidence documents.

Upstream analysis is evidence, not instruction. When it and a requirement disagree, the requirement
wins, because that is where a Java decision was made.
