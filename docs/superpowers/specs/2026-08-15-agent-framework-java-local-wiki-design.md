# Agent Framework for Java Local Wiki Design

## Goal

Create a local LLM Wiki project that helps a new contributor understand Agent Framework for Java,
navigate its architecture, and find the canonical contract behind each explanation.

The wiki is a derived, Korean-language exploration layer. The repository remains the source of truth,
and English identifiers, requirement IDs, commands, and code symbols remain unchanged.

## Decision

Create `agent-framework-java-wiki` beside the source repository. From the source repository, its
relative path is `../agent-framework-java-wiki`.

Do not store generated wiki pages in the Agent Framework for Java repository. This avoids:

- creating a second technical specification beside the canonical English documentation;
- weakening the single-Korean-companion policy;
- committing LLM-generated summaries that can drift from code and requirements;
- mixing personal model configuration, chat history, or ingest state with product sources.

The repository receives no implementation or configuration changes for the local wiki. This design
record is the only repository artifact produced by the wiki setup.

## Audience and Scope

The primary audience is a contributor who needs to answer:

1. What does this project implement, and what does it deliberately not own?
2. How are the API, engine, testkit, BOM, build logic, and policy harness related?
3. Where is the canonical requirement or design decision for a behavior?
4. What has been implemented, and what remains planned?
5. How should a change be developed, tested, and reviewed?

The initial wiki covers repository orientation, architecture, modules, core concepts, contribution
workflow, verification, and implementation status. It does not reproduce all 244 requirements,
upstream feature analyses, acceptance criteria, or public API documentation. Topic pages summarize
and route readers to those canonical sources.

## Alternatives Considered

### Repository-local ignored wiki

A `.llm-wiki/` directory would keep the wiki close to the sources, but it would add local state to the
product worktree and require a repository ignore rule for a personal application. It also makes the
boundary between product documentation and generated notes less obvious.

### Committed documentation wiki

A committed `docs/wiki/` tree would be shareable and reviewable. It would also have to be English,
participate in repository policy checks, and be maintained as a second explanation of contracts that
already have stable homes. The expected drift and review cost outweigh the benefit for an initial
local experiment.

### Separate local wiki

A sibling workspace isolates generated content and application state while allowing source links back
to the repository. It supports Korean explanations without changing the repository language policy.
This is the selected approach.

## Workspace Structure

Use the LLM Wiki project structure:

```text
agent-framework-java-wiki/
├── purpose.md
├── schema.md
├── raw/
│   └── sources/
│       └── repository/
├── wiki/
│   ├── index.md
│   ├── log.md
│   ├── overview.md
│   ├── architecture/
│   ├── modules/
│   ├── concepts/
│   ├── contributing/
│   ├── status/
│   └── sources/
├── workspace/
└── .llm-wiki/
```

`purpose.md` defines the audience, questions, scope, and authority boundary. `schema.md` defines page
types, frontmatter, links, citations, source precedence, ingest rules, and lint rules. LLM Wiki owns
`wiki/` and its application state. Synced repository files under `raw/sources/repository/` are
read-only inputs from the wiki maintainer's perspective.

## Source Corpus

Synchronize only committed files from the source repository's `HEAD`. Record the full source commit
SHA for each synchronization. Use an allowlist rather than copying the repository root:

- repository-owned Markdown;
- Gradle Kotlin build scripts and version catalog files;
- Gradle wrapper properties;
- GitHub Actions workflow definitions;
- harness JSON schemas;
- production Java sources;
- Java tests.

Exclude Git metadata, worktrees, build outputs, caches, IDE settings, local agent settings, logs,
environment files, credentials, and untracked files. Reading from `HEAD` rather than the working tree
also prevents an unfinished local edit from silently becoming wiki evidence.

LLM Wiki's source watcher does not directly ingest every source and configuration extension in this
corpus. Synchronization therefore copies Markdown unchanged and renders every other allowlisted text
file as a deterministic Markdown wrapper. A wrapper records the original repository-relative path,
source commit, and source language in frontmatter, then includes the committed content in a fenced
code block. Its mirror path appends `.md` to the original path, such as
`agent-framework-api/src/main/java/example/Agent.java.md`.

Synchronization stages the complete mirror in a temporary directory, then checksum-syncs it into
`raw/sources/repository/`. It removes deleted source files, preserves the repository hierarchy, and
changes only files whose committed content changed. Temporary files are removed whether the sync
succeeds or fails.

The initial corpus is small enough for staged ingestion, but ingestion should still proceed in this
order:

1. repository instructions, root navigation, and documentation indexes;
2. requirements and approved designs;
3. pinned upstream analyses;
4. build, workflow, and harness contracts;
5. production Java sources;
6. Java tests.

This order produces useful orientation pages early and reduces the chance that code-level details are
summarized without their governing contract.

## Authority and Citation Rules

The wiki must distinguish evidence discovery order from Java decision authority:

- inspect pinned upstream production and conformance evidence first when determining upstream
  behavior;
- use stable Java requirements and approved designs as the implementation contract;
- treat pinned upstream analysis as evidence, not as permission to override a recorded Java decision;
- do not introduce behavior from current upstream `main` unless the repository contains a reviewed
  delta;
- use official documentation only when the pinned repository evidence is insufficient;
- use samples and README files as orientation, not as stronger evidence than contracts.

Every factual page includes YAML frontmatter with `type`, `status`, `source_commit`, `updated`, and
`sources` fields. `source_commit` is the complete Git object ID used for the current source mirror,
and `sources` is a list of paths below `raw/sources/repository/`.

Pages use `[[wikilink]]` syntax for internal navigation. Important claims cite synced raw sources,
including a heading or stable requirement ID when possible. A page must say that it is derived and
link to the canonical source rather than presenting itself as a contract.

When sources disagree, the wiki records the tension and creates a review item. It does not guess,
silently select the newest wording, or merge current upstream behavior into the pinned snapshot.

## Initial Information Architecture

The first generation should create a focused set of pages:

### Orientation

- project overview and current maturity;
- runtime ownership boundary;
- documentation and evidence map;
- glossary of stable project terms.

### Architecture

- clean architecture and dependency direction;
- module composition;
- observable execution semantics;
- build and policy harness;
- source-of-truth and upstream pin model.

### Modules

- public API;
- engine;
- testkit;
- BOM and publication;
- build logic and harness policy.

### Concepts

- agent execution and model calls;
- messages, content, and streaming reconstruction;
- sessions, run context, and decorators;
- tools, approvals, and MCP;
- interceptors and context management;
- workflows, hosting, providers, and protocols as planned areas.

### Contribution and Status

- first-change workflow;
- requirement-driven test-first development;
- Gradle verification matrix;
- review and squash-merge loop;
- implemented, active, and not-started capability map.

`wiki/index.md` lists every page by category with a one-line summary. `wiki/overview.md` provides a
short reading path, and `wiki/log.md` records synchronization, ingest, review, and lint events.

## Data Flow

1. Resolve and validate the source repository and its `HEAD` commit.
2. Export the allowlisted committed files and synchronize the raw source mirror.
3. Record the source commit and corpus counts.
4. Let LLM Wiki detect or import the raw sources.
5. Wait for each ingest stage to complete before starting the next stage.
6. Generate or update the initial topic pages, index, overview, and log.
7. Review pages against the canonical sources.
8. Run graph and lint checks, then resolve or explicitly record findings.

Subsequent refreshes repeat the same flow. Pages that cite an older source commit are stale until
reviewed or regenerated. A sync alone does not imply that the generated wiki is current.

## Failure Handling

- Abort synchronization if the source repository, `HEAD`, or required entry documents cannot be
  resolved.
- Reject an empty allowlist result rather than deleting the existing raw corpus.
- Preserve the previous raw corpus if archive creation fails.
- Surface LLM ingest failures per source and retry only through LLM Wiki's explicit retry mechanism.
- Do not mark a stage complete while the ingest queue contains failed, cancelled, or pending items.
- Create review items for contradictions, ambiguous authority, missing citations, or unsupported
  claims.
- Never turn a partial ingest into a success-shaped overview update.
- Keep model credentials, provider configuration, API tokens, chat history, and local application
  state inside the local wiki workspace.

## Verification

Setup is complete only when all of the following are true:

1. The local project opens in LLM Wiki.
2. The recorded source SHA equals the Agent Framework for Java `HEAD`.
3. Corpus counts match the allowlisted tracked files.
4. No excluded path, untracked file, credential, or build output appears under `raw/sources/`.
5. The ingest queue completes without failed or pending sources.
6. The initial page set exists, and every page is listed in `wiki/index.md`.
7. Internal wikilinks resolve and no unexplained orphan page remains.
8. Important claims carry source references and the current source commit.
9. Spot checks of project purpose, architecture boundaries, current status, and verification commands
   match the canonical repository documents.
10. LLM Wiki graph and lint checks have no unresolved high-confidence errors; intentional gaps remain
    visible as review items.
11. The Agent Framework for Java tree receives no further change after this design record is
    committed.

## Security and Privacy

Only committed, allowlisted repository content and deterministic provenance wrappers enter the wiki.
Local settings and ignored files are never imported. The wiki does not enable web research during
initial generation, because external material could bypass the pinned-source policy. The application
API remains bound to loopback and token-protected if it is enabled later.

Generated pages must not include raw prompt bodies, unfiled chat transcripts, tool arguments,
credentials, or personal agent traces. Examples copied into pages must be redacted and traceable to
an allowed source.

## Future Options

If the local wiki proves useful, a later design may add a repository-owned, deterministic source
manifest or a small shared English navigation layer. That decision requires evidence that the wiki
reduces contributor effort enough to justify maintenance and policy cost. It is not part of the
initial setup.
