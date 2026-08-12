# Documentation Language and Information Architecture

- Status: approved
- Date: 2026-08-12
- Scope: repository-owned Markdown documentation

## 1. Goal

The repository uses English as the single source of truth for technical documentation. Korean
readers receive one maintained companion guide at `docs/ko/README.md`. The companion explains the
project and routes readers to canonical English contracts without creating a second specification
tree that can drift.

This policy removes the current mixture of Korean narrative, English identifiers, and English source
excerpts from canonical documents. Code, stable identifiers, upstream symbols, commands, and
permalinks remain unchanged unless a link must move with a renamed heading.

## 2. Current problem

The documentation already has a mostly English root README, contribution guide, operational guide,
and module composition contract. Earlier foundation designs and all twelve requirement documents are
written primarily in Korean. The pinned upstream snapshot documents combine Korean analysis with
large English source excerpts and symbol names.

That mixture creates four problems:

1. Readers cannot predict a document's language from its path.
2. Search results and headings alternate languages within one documentation graph.
3. Requirement and design reviews require bilingual context even when the codebase contract is
   otherwise English.
4. There is no executable rule preventing new mixed-language canonical documents.

## 3. Considered approaches

### 3.1 Full English and Korean mirror trees

Maintain matching `docs/en/` and `docs/ko/` trees.

This gives Korean readers complete translations, but it doubles more than fifty technical documents,
including 244 requirements and a large evidence ledger. Every behavioral change would need two
contract updates, and translation lag could create two competing sources of truth.

### 3.2 English source with one Korean companion

Keep canonical documents at their existing stable paths and translate their narrative to English.
Maintain one curated Korean companion at `docs/ko/README.md`.

This keeps links and history stable, gives every contract one authoritative version, and still
provides a useful Korean entry point. The companion can explain architecture and workflow without
copying every acceptance criterion.

### 3.3 English for new documents only

Leave historical documents mixed and require English only for future additions.

This has the smallest immediate diff but preserves the discovery and maintenance problem that this
change is intended to solve.

**Decision:** use approach 3.2.

## 4. Canonical documentation structure

The canonical English graph is:

```text
README.md
docs/
├── README.md
├── design/
├── requirements/
├── operations/
└── upstream/
    └── snapshots/<revision>/
```

Responsibilities are:

| Location | Responsibility |
| --- | --- |
| `README.md` | Product overview, quick start, current state, primary navigation |
| `docs/README.md` | Complete documentation map and source-of-truth policy |
| `docs/design/` | Approved architecture and engineering decisions |
| `docs/requirements/` | Stable behavioral requirements and acceptance criteria |
| `docs/operations/` | Contributor, CI, runner, and repository operation guidance |
| `docs/upstream/` | Pinned upstream provenance, evidence, feature analysis, and coverage |
| `docs/ko/README.md` | The only maintained Korean companion document |

Files retain their existing paths during translation. Stable requirement IDs, upstream feature
numbers, snapshot revision names, and evidence URLs do not change.

## 5. Korean companion

`docs/ko/README.md` is a curated guide rather than a line-by-line translation. It contains:

1. project purpose and status;
2. runtime ownership and architecture boundaries;
3. quick start and common Gradle commands;
4. repository/module layout;
5. an explanation of requirements, designs, operations, and pinned upstream evidence;
6. current implementation state and next target;
7. contribution and security entry points;
8. an explicit statement that English documents are authoritative.

The guide links to canonical English documents. It does not duplicate requirement acceptance
criteria, compatibility tables, or upstream evidence ledgers.

## 6. Translation rules

Migration changes language, not contract meaning.

- Preserve requirement IDs, status values, support levels, table rows, and acceptance criteria.
- Preserve code blocks, commands, package names, class/member names, JSON fields, and configuration
  keys.
- Preserve upstream commit SHAs and permalinks.
- Translate prose, headings, captions, table labels, and explanatory notes into clear technical
  English.
- Use established project terms consistently: `AgentEngine`, host runtime, provider adapter,
  session, tool call, checkpoint, and conformance.
- Do not summarize or omit evidence while translating canonical contracts.
- Do not introduce new behavioral decisions as part of translation. Record any discovered ambiguity
  separately.

Quoted upstream text remains in its original language. The pinned upstream sources currently use
English, so this does not create an exception for Korean prose.

## 7. Navigation

The root README links to:

- `docs/README.md` as the full English documentation index;
- the requirements index;
- the design documents;
- the pinned upstream snapshot;
- `docs/ko/README.md` as the Korean companion.

Every directory index links back to `docs/README.md`. The Korean companion links back to the English
root README and documentation index.

Relative Markdown links must resolve after heading translation. When an internal anchor changes, all
inbound links change in the same commit.

## 8. Executable policy

`./gradlew policyCheck` enforces the language and navigation contract.

### 8.1 Canonical language

Repository-owned Markdown under these paths must not contain Hangul characters:

- root `README.md`, `AGENTS.md`, `CONTRIBUTING.md`, `SECURITY.md`, and vendor instruction adapters;
- `.github/*.md`;
- `docs/**/*.md`, except `docs/ko/README.md`.

Generated build output, ignored session artifacts, dependency notices, and the Korean companion are
outside this scan.

### 8.2 Companion uniqueness

- `docs/ko/README.md` must exist and contain Korean text.
- No other tracked Markdown file may live below `docs/ko/`.
- The root README and `docs/README.md` must link to the Korean companion.
- The companion must state that English is authoritative and link back to both English entry points.

### 8.3 Link integrity

Policy tests inspect tracked repository-owned Markdown files and verify that:

- every relative file link resolves;
- fragments targeting Markdown headings resolve to a generated GitHub-style anchor;
- absolute web URLs and code examples are not treated as local files;
- links do not escape the repository root.

The link checker reports source file, line, and unresolved target.

## 9. Migration sequence

1. Add the English documentation index and policy regression tests.
2. Translate root navigation and the three Korean design documents.
3. Translate the requirements index and twelve requirement contracts.
4. Translate upstream indexes, matrices, ledger, manifest, and feature analyses.
5. Expand and synchronize the single Korean companion.
6. Run the Hangul scan, relative-link validation, `policyCheck`, and full `check`.
7. Review translation diffs for contract preservation and broken evidence links.

Work is split by directory so independent translation passes do not edit the same files. A final
cross-document review checks terminology and navigation.

## 10. Success criteria

The migration is complete when:

1. all canonical repository-owned Markdown is English;
2. `docs/ko/README.md` is the only Korean document;
3. `docs/README.md` gives a complete, role-based documentation map;
4. all relative Markdown links and heading fragments resolve;
5. all 244 requirement IDs and all pinned upstream evidence remain present;
6. `./gradlew policyCheck` prevents language or navigation regression;
7. `./gradlew check` passes on the supported JDK toolchains.

## 11. Non-goals

- Maintaining a complete Korean mirror of every canonical document.
- Translating source code, symbols, commands, or upstream quotations.
- Changing product behavior, requirement decisions, support levels, or the upstream pin.
- Renaming stable document paths solely to introduce an `en` directory.
