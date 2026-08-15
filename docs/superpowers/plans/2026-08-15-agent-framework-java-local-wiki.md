# Agent Framework for Java Local Wiki Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create a local, Korean-language LLM Wiki that explains Agent Framework for Java and links every important claim back to a committed canonical source.

**Architecture:** A sibling `agent-framework-java-wiki` workspace contains LLM Wiki state, generated pages, local maintenance scripts, and a Git history that is independent from the product repository. A deterministic synchronization script exports allowlisted files from the product repository's committed `HEAD`, wraps non-Markdown text as ingestible Markdown, and stages the corpus for LLM Wiki's source watcher.

**Tech Stack:** LLM Wiki desktop application, Bash 3.2+, Git, rsync, Markdown, YAML frontmatter

## Global Constraints

- Create `agent-framework-java-wiki` beside the Agent Framework for Java repository.
- Keep generated wiki pages and personal LLM Wiki state out of the Agent Framework for Java repository.
- Write wiki prose in Korean while preserving English identifiers, requirement IDs, commands, and code symbols.
- Treat the Agent Framework for Java repository as authoritative; the wiki is always a derived exploration layer.
- Synchronize only committed, allowlisted files from the source repository's `HEAD`.
- Exclude Git metadata, worktrees, build outputs, caches, IDE settings, local agent settings, logs, environment files, credentials, and untracked files.
- Copy Markdown unchanged and render every other allowlisted text file as a deterministic Markdown wrapper containing its original path, source commit, language, and committed content.
- Do not enable web research during initial generation.
- Record contradictions and ambiguity as review items instead of guessing or importing current upstream `main`.
- Keep LLM Wiki's API loopback-only and token-protected if it is enabled.
- Do not modify the Agent Framework for Java tree while executing this plan.
- Follow the approved design in [`../specs/2026-08-15-agent-framework-java-local-wiki-design.md`](../specs/2026-08-15-agent-framework-java-local-wiki-design.md).

## File Structure

The implementation creates or manages these files outside the product repository:

| File | Responsibility |
| --- | --- |
| `../agent-framework-java-wiki/purpose.md` | Defines the audience, questions, scope, authority boundary, language, and current thesis. |
| `../agent-framework-java-wiki/schema.md` | Defines page types, routing, frontmatter, citations, authority, linking, ingest, and lint rules. |
| `../agent-framework-java-wiki/.gitignore` | Keeps raw mirrors, credentials, application state, and machine-local evidence out of the local wiki Git history. |
| `../agent-framework-java-wiki/workspace/bin/sync-sources.sh` | Produces a cumulative, stage-selectable mirror from the source repository's committed `HEAD`. |
| `../agent-framework-java-wiki/workspace/tests/project-contract-test.sh` | Checks the local project structure and schema contract. |
| `../agent-framework-java-wiki/workspace/tests/sync-sources-test.sh` | Verifies source selection, staging, wrapping, deletion, and failure safety. |
| `../agent-framework-java-wiki/workspace/source-state.md` | Records the machine-local source path, commit, stage, count, and synchronization time. |
| `../agent-framework-java-wiki/workspace/source-worktree-baseline.txt` | Records the product worktree state so execution can prove it added no product changes. |
| `../agent-framework-java-wiki/workspace/prompts/bootstrap-architecture.md` | Gives the local Chat Agent the exact orientation, architecture, and module page set. |
| `../agent-framework-java-wiki/workspace/prompts/bootstrap-concepts.md` | Gives the local Chat Agent the exact concepts, contribution, and status page set. |
| `../agent-framework-java-wiki/workspace/prompts/finalize-wiki.md` | Directs the Chat Agent to reconcile the index, overview, log, citations, and review items. |
| `../agent-framework-java-wiki/raw/sources/repository/` | Holds the ignored, reproducible source mirror watched by LLM Wiki. |
| `../agent-framework-java-wiki/wiki/` | Holds generated and curated wiki pages tracked by the local wiki Git repository. |

---

### Task 1: Create the LLM Wiki Project

**Files:**
- Create through LLM Wiki: `../agent-framework-java-wiki/`
- Create: `../agent-framework-java-wiki/workspace/source-worktree-baseline.txt`

**Interfaces:**
- Consumes: the installed `LLM Wiki.app` and the current Agent Framework for Java repository.
- Produces: a registered General-template LLM Wiki project at the required sibling path.

- [ ] **Step 1: Resolve the source, parent, and target paths**

Run from the Agent Framework for Java repository:

```bash
SOURCE_REPO="$(git rev-parse --show-toplevel)"
WORKSPACE_PARENT="$(dirname "$SOURCE_REPO")"
WIKI_ROOT="$WORKSPACE_PARENT/agent-framework-java-wiki"
printf 'SOURCE_REPO=%s\nWORKSPACE_PARENT=%s\nWIKI_ROOT=%s\n' \
  "$SOURCE_REPO" "$WORKSPACE_PARENT" "$WIKI_ROOT"
test ! -e "$WIKI_ROOT"
```

Expected: the three absolute paths are printed and `test` exits successfully. If the target already
exists, stop and inspect it; do not delete or overwrite it.

- [ ] **Step 2: Create the project in LLM Wiki**

Run:

```bash
open -a "LLM Wiki"
```

In the application:

1. Select **New Project**.
2. Choose the **General** template.
3. Enter `agent-framework-java-wiki` as the project name.
4. Select the printed `WORKSPACE_PARENT` as the location.
5. Create and open the project.

Expected: LLM Wiki opens the new project and displays its Wiki, Sources, Chat, Graph, Lint, Review,
and Settings views.

- [ ] **Step 3: Verify the generated project skeleton**

Run:

```bash
SOURCE_REPO="$(git rev-parse --show-toplevel)"
WIKI_ROOT="$(dirname "$SOURCE_REPO")/agent-framework-java-wiki"
test -f "$WIKI_ROOT/purpose.md"
test -f "$WIKI_ROOT/schema.md"
test -f "$WIKI_ROOT/wiki/index.md"
test -f "$WIKI_ROOT/wiki/log.md"
test -f "$WIKI_ROOT/wiki/overview.md"
test -d "$WIKI_ROOT/raw/sources"
```

Expected: every `test` exits successfully.

- [ ] **Step 4: Record the source worktree baseline**

Run:

```bash
SOURCE_REPO="$(git rev-parse --show-toplevel)"
WIKI_ROOT="$(dirname "$SOURCE_REPO")/agent-framework-java-wiki"
mkdir -p "$WIKI_ROOT/workspace"
git -C "$SOURCE_REPO" status --porcelain=v1 \
  > "$WIKI_ROOT/workspace/source-worktree-baseline.txt"
```

Expected: the baseline file exists. It may contain unrelated pre-existing worktree changes.

### Task 2: Define the Wiki Contract

**Files:**
- Create: `../agent-framework-java-wiki/.gitignore`
- Modify: `../agent-framework-java-wiki/purpose.md`
- Modify: `../agent-framework-java-wiki/schema.md`
- Create: `../agent-framework-java-wiki/workspace/tests/project-contract-test.sh`
- Create directories: `../agent-framework-java-wiki/wiki/{architecture,modules,contributing,status}`

**Interfaces:**
- Consumes: the General-template project from Task 1.
- Produces: a Korean-output project contract and custom page routing used by every ingest and Chat Agent update.

- [ ] **Step 1: Write the failing project contract test**

Create `../agent-framework-java-wiki/workspace/tests/project-contract-test.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"

for file in purpose.md schema.md .gitignore; do
  test -f "$ROOT/$file" || {
    printf 'missing file: %s\n' "$file" >&2
    exit 1
  }
done

for dir in wiki/architecture wiki/modules wiki/concepts wiki/contributing wiki/status wiki/sources; do
  test -d "$ROOT/$dir" || {
    printf 'missing directory: %s\n' "$dir" >&2
    exit 1
  }
done

for needle in \
  "Write explanatory prose in Korean" \
  "derived exploration layer" \
  "workspace/source-state.md"; do
  grep -Fq "$needle" "$ROOT/purpose.md" || {
    printf 'purpose.md missing: %s\n' "$needle" >&2
    exit 1
  }
done

for needle in \
  "| architecture | wiki/architecture/ |" \
  "| module | wiki/modules/ |" \
  "| guide | wiki/contributing/ |" \
  "| status | wiki/status/ |" \
  "source_commit" \
  "raw/sources/repository/" \
  "Current upstream main is not evidence"; do
  grep -Fq "$needle" "$ROOT/schema.md" || {
    printf 'schema.md missing: %s\n' "$needle" >&2
    exit 1
  }
done

PLACEHOLDER_PATTERN='T(BD)|T(ODO)|FIX(ME)'
if grep -En "$PLACEHOLDER_PATTERN" "$ROOT/purpose.md" "$ROOT/schema.md"; then
  printf 'project contract contains a placeholder\n' >&2
  exit 1
fi

grep -Fqx '.llm-wiki/' "$ROOT/.gitignore"
grep -Fqx 'raw/' "$ROOT/.gitignore"
grep -Fqx 'workspace/source-state.md' "$ROOT/.gitignore"
grep -Fqx 'workspace/source-worktree-baseline.txt' "$ROOT/.gitignore"

printf 'project contract: PASS\n'
```

Make it executable:

```bash
chmod +x ../agent-framework-java-wiki/workspace/tests/project-contract-test.sh
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
../agent-framework-java-wiki/workspace/tests/project-contract-test.sh
```

Expected: FAIL because the General template still has placeholders and lacks the custom directories,
custom schema, and `.gitignore`.

- [ ] **Step 3: Write the project purpose**

Replace `../agent-framework-java-wiki/purpose.md` with:

```markdown
# Project Purpose

## Goal

Maintain a Korean-language wiki that helps a first-time Agent Framework for Java contributor
understand project boundaries, architecture, modules, core execution concepts, implementation status,
and development workflow, then navigate to the canonical evidence for each explanation.

## Key Questions

1. What does this project implement, and which runtime responsibilities does it deliberately not own?
2. How do the API, engine, testkit, BOM, build logic, and policy harness relate?
3. Where is the requirement, design, or pinned upstream evidence that governs a behavior?
4. Which contracts exist now, and which capabilities remain planned?
5. Which tests, Gradle checks, reviews, and squash-merge steps deliver a change safely?

## Scope

**In scope:**

- repository orientation and runtime ownership boundaries;
- clean architecture and module dependency direction;
- public API, engine, testkit, BOM, build logic, and policy harness;
- agent execution, messages and content, streaming, sessions and context, tools, and MCP;
- relationships among requirements, designs, and pinned upstream evidence;
- contribution workflow, verification commands, review, and squash merge;
- implemented, active, and planned capability status.

**Out of scope:**

- reproducing all 244 requirements or their acceptance criteria;
- rewriting the complete pinned upstream analysis;
- importing unreviewed behavior from current upstream main;
- facts added through external web research;
- model settings, credentials, prompts, chat transcripts, or personal agent traces.

## Authority Boundary

This wiki is a derived exploration layer, not a contract. English documents and code in the Agent
Framework for Java repository are authoritative. If the wiki and source disagree, follow the source.
Inspect pinned production and conformance evidence to establish upstream behavior, and follow stable
requirements and approved designs for Java implementation decisions.

## Language

Write explanatory prose in Korean. Preserve English identifiers, requirement IDs, commands, paths,
packages, classes, methods, and JSON fields exactly.

## Current Source Snapshot

`workspace/source-state.md` records the current source repository, commit, ingest stage, and file
count. A page whose `source_commit` differs from that file is stale.

## Current Thesis

The wiki does not create new contracts. Its value is shortening the path among distributed canonical
sources and giving every explanation a verifiable source path or stable requirement ID.
```

- [ ] **Step 4: Write the wiki schema**

Replace `../agent-framework-java-wiki/schema.md` with:

```markdown
# Wiki Schema

## Page Types

| Type | Directory | Purpose |
| --- | --- | --- |
| overview | wiki/ | High-level reading path and project summary |
| architecture | wiki/architecture/ | Boundaries, dependency direction, evidence, and engineering design |
| module | wiki/modules/ | Responsibilities, public surface, dependencies, and current status of one module |
| concept | wiki/concepts/ | Observable execution concepts and cross-module behavior |
| guide | wiki/contributing/ | Contributor workflow, verification, and review guidance |
| status | wiki/status/ | Implemented, active, and planned capability maps |
| source | wiki/sources/ | Maps and summaries of canonical source groups |
| query | wiki/queries/ | Open contradictions or questions that require human review |
| synthesis | wiki/synthesis/ | Cross-source conclusions that cite every contributing source |

## Naming Conventions

- Use `kebab-case.md`.
- Keep code symbols, package names, requirement IDs, and commands in their original English form.
- Write explanatory prose in Korean.
- Use stable topic names rather than dates, branches, or model names.

## Frontmatter

Every factual page must contain these YAML fields:

- `type`: one value from the Page Types table.
- `title`: a human-readable title.
- `status`: `derived`, `review-needed`, or `stale`.
- `tags`: a YAML list.
- `related`: a YAML list of wiki page slugs.
- `source_commit`: the full 40-character commit from `workspace/source-state.md`.
- `sources`: a YAML list of paths below `raw/sources/repository/`.
- `created`: an ISO date.
- `updated`: an ISO date.

`wiki/index.md` and `wiki/log.md` are operational files and do not require factual-page frontmatter.

## Source References

- Every important claim must cite at least one path below `raw/sources/repository/`.
- Prefer a stable requirement ID or Markdown heading in the prose when one exists.
- Markdown sources keep their repository-relative path.
- Wrapped sources append `.md` to the original path and expose `source_path`, `source_commit`, and
  `source_language` in wrapper frontmatter.
- A page is stale when its `source_commit` differs from `workspace/source-state.md`.
- The wiki summarizes and routes; it does not copy complete requirement tables or acceptance criteria.

## Authority Rules

1. Inspect pinned upstream production source first to establish upstream behavior.
2. Inspect pinned upstream conformance and integration evidence next.
3. Use stable Java requirements and approved designs as the Java implementation contract.
4. Treat pinned upstream analysis as evidence, not permission to override a recorded Java decision.
5. Current upstream main is not evidence unless the repository contains a reviewed delta.
6. Use official documentation only when pinned repository evidence is insufficient.
7. Use samples and README files for orientation, not as stronger evidence than contracts.

## Index and Linking

- Use `[[page-slug]]` for internal links.
- `wiki/index.md` lists every factual page exactly once by page type with a one-line Korean summary.
- Every factual page has at least one inbound wikilink.
- `wiki/overview.md` gives the shortest useful reading path for a new contributor.
- Source paths are written as Markdown links when the target is a synced Markdown file and as inline
  paths when the target is a wrapped source.

## Contradiction Handling

When sources disagree:

1. Do not guess or silently prefer newer natural-language wording.
2. Mark the affected page `review-needed`.
3. State both claims and cite both sources.
4. Create or update a `query` page.
5. Add a Review item for the human decision.
6. Resolve the question only from stronger repository evidence or an approved decision.

## Ingest Rules

- Ingest only files produced by `workspace/bin/sync-sources.sh`.
- Do not use web research during initial generation.
- Complete one cumulative ingest stage before starting the next.
- Do not update the overview as if ingestion succeeded while the Activity queue has failed, cancelled,
  or pending items.
- Do not record prompts, raw model responses, tool arguments, credentials, chat transcripts, or
  personal agent traces in factual pages.

## Log Format

`wiki/log.md` records entries in reverse chronological order:

```text
## YYYY-MM-DD | action | source commit

- Scope:
- Result:
- Review items:
```

## Lint Rules

- No unexplained orphan page.
- No unresolved wikilink.
- No factual page without current `source_commit` and at least one source.
- No unsupported claim presented as a contract.
- No duplicate page for an existing concept.
- No full requirement or upstream evidence table copied into the wiki.
```

- [ ] **Step 5: Add local-state exclusions and routing directories**

Create `../agent-framework-java-wiki/.gitignore`:

```gitignore
.llm-wiki/
raw/
workspace/source-state.md
workspace/source-worktree-baseline.txt
.obsidian/workspace*
.DS_Store
```

Create the custom directories:

```bash
mkdir -p \
  ../agent-framework-java-wiki/wiki/architecture \
  ../agent-framework-java-wiki/wiki/modules \
  ../agent-framework-java-wiki/wiki/contributing \
  ../agent-framework-java-wiki/wiki/status \
  ../agent-framework-java-wiki/workspace/bin \
  ../agent-framework-java-wiki/workspace/prompts
```

- [ ] **Step 6: Run the project contract test**

Run:

```bash
../agent-framework-java-wiki/workspace/tests/project-contract-test.sh
```

Expected:

```text
project contract: PASS
```

- [ ] **Step 7: Initialize the local wiki Git history**

Run:

```bash
SOURCE_REPO="$(git rev-parse --show-toplevel)"
WIKI_ROOT="$(dirname "$SOURCE_REPO")/agent-framework-java-wiki"
git -C "$WIKI_ROOT" init
git -C "$WIKI_ROOT" add .gitignore .obsidian purpose.md schema.md wiki workspace/tests
git -C "$WIKI_ROOT" commit \
  -m "chore: initialize agent framework wiki" \
  -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

Expected: one local commit containing the project contract and no `raw/` or `.llm-wiki/` content.

### Task 3: Implement Deterministic Source Synchronization

**Files:**
- Create: `../agent-framework-java-wiki/workspace/bin/sync-sources.sh`
- Create: `../agent-framework-java-wiki/workspace/tests/sync-sources-test.sh`
- Generate and ignore: `../agent-framework-java-wiki/workspace/source-state.md`
- Generate and ignore: `../agent-framework-java-wiki/raw/sources/repository/`

**Interfaces:**
- Consumes: `sync-sources.sh [SOURCE_REPOSITORY] [foundation|contracts|upstream|build|main|test|all]`.
- Produces: a cumulative source mirror and `workspace/source-state.md`; returns nonzero before changing the mirror if the repository or allowlist is invalid.

- [ ] **Step 1: Write the failing synchronization test**

Create `../agent-framework-java-wiki/workspace/tests/sync-sources-test.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
SYNC="$ROOT/workspace/bin/sync-sources.sh"
TMP_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/afj-wiki-sync-test.XXXXXX")"
trap 'rm -rf "$TMP_ROOT"' EXIT

SOURCE="$TMP_ROOT/source"
WIKI="$TMP_ROOT/wiki"
mkdir -p \
  "$SOURCE/docs/requirements" \
  "$SOURCE/docs/upstream/snapshots/pin" \
  "$SOURCE/.harness/schemas" \
  "$SOURCE/module/src/main/java/example" \
  "$SOURCE/module/src/test/java/example" \
  "$SOURCE/module/build" \
  "$WIKI/raw/sources/repository" \
  "$WIKI/workspace"

git -C "$SOURCE" init -q
git -C "$SOURCE" config user.name "Wiki Sync Test"
git -C "$SOURCE" config user.email "wiki-sync-test@example.invalid"

printf '# Root\ncommitted\n' > "$SOURCE/README.md"
printf '# Requirement\n' > "$SOURCE/docs/requirements/01-contract.md"
printf '# Upstream\n' > "$SOURCE/docs/upstream/snapshots/pin/README.md"
mkdir -p "$SOURCE/docs/upstream/snapshots/pin/features"
printf '# Upstream Feature\n' > "$SOURCE/docs/upstream/snapshots/pin/features/01-feature.md"
printf 'rootProject.name = "fixture"\n' > "$SOURCE/settings.gradle.kts"
printf '{"type":"object"}\n' > "$SOURCE/.harness/schemas/agent.json"
printf 'package example; class Agent {}\n' \
  > "$SOURCE/module/src/main/java/example/Agent.java"
printf 'package example; class AgentTest {}\n' \
  > "$SOURCE/module/src/test/java/example/AgentTest.java"
printf '# generated output\n' > "$SOURCE/module/build/generated.md"
printf 'do-not-import\n' > "$SOURCE/.env"
printf 'unsupported\n' > "$SOURCE/notes.txt"

git -C "$SOURCE" add .
git -C "$SOURCE" commit -q \
  -m "test: create sync fixture" \
  -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"

printf 'keep\n' > "$WIKI/raw/sources/repository/keep.md"

assert_file() {
  test -f "$1" || {
    printf 'expected file: %s\n' "$1" >&2
    exit 1
  }
}

assert_absent() {
  test ! -e "$1" || {
    printf 'unexpected path: %s\n' "$1" >&2
    exit 1
  }
}

WIKI_ROOT_OVERRIDE="$WIKI" "$SYNC" "$SOURCE" foundation
assert_file "$WIKI/raw/sources/repository/README.md"
assert_file "$WIKI/raw/sources/repository/docs/upstream/snapshots/pin/README.md"
assert_absent "$WIKI/raw/sources/repository/docs/requirements/01-contract.md"
assert_absent "$WIKI/raw/sources/repository/docs/upstream/snapshots/pin/features/01-feature.md"

WIKI_ROOT_OVERRIDE="$WIKI" "$SYNC" "$SOURCE" contracts
assert_file "$WIKI/raw/sources/repository/docs/requirements/01-contract.md"
assert_absent "$WIKI/raw/sources/repository/docs/upstream/snapshots/pin/features/01-feature.md"

WIKI_ROOT_OVERRIDE="$WIKI" "$SYNC" "$SOURCE" all
assert_file "$WIKI/raw/sources/repository/docs/upstream/snapshots/pin/README.md"
assert_file "$WIKI/raw/sources/repository/docs/upstream/snapshots/pin/features/01-feature.md"
assert_file "$WIKI/raw/sources/repository/settings.gradle.kts.md"
assert_file "$WIKI/raw/sources/repository/.harness/schemas/agent.json.md"
assert_file "$WIKI/raw/sources/repository/module/src/main/java/example/Agent.java.md"
assert_file "$WIKI/raw/sources/repository/module/src/test/java/example/AgentTest.java.md"
assert_absent "$WIKI/raw/sources/repository/settings.gradle.kts"
assert_absent "$WIKI/raw/sources/repository/module/build"
assert_absent "$WIKI/raw/sources/repository/.env"
assert_absent "$WIKI/raw/sources/repository/notes.txt"

HEAD_SHA="$(git -C "$SOURCE" rev-parse HEAD)"
grep -Fq "source_path: 'module/src/main/java/example/Agent.java'" \
  "$WIKI/raw/sources/repository/module/src/main/java/example/Agent.java.md"
grep -Fq "source_commit: $HEAD_SHA" \
  "$WIKI/raw/sources/repository/module/src/main/java/example/Agent.java.md"
grep -Fq '~~~~java' \
  "$WIKI/raw/sources/repository/module/src/main/java/example/Agent.java.md"

printf '# Root\nuncommitted\n' > "$SOURCE/README.md"
printf '# untracked\n' > "$SOURCE/SECRET.md"
WIKI_ROOT_OVERRIDE="$WIKI" "$SYNC" "$SOURCE" all
grep -Fq 'committed' "$WIKI/raw/sources/repository/README.md"
assert_absent "$WIKI/raw/sources/repository/SECRET.md"

git -C "$SOURCE" restore README.md
git -C "$SOURCE" rm -q module/src/main/java/example/Agent.java
git -C "$SOURCE" commit -q \
  -m "test: delete source file" \
  -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
WIKI_ROOT_OVERRIDE="$WIKI" "$SYNC" "$SOURCE" all
assert_absent "$WIKI/raw/sources/repository/module/src/main/java/example/Agent.java.md"

CURRENT_SHA="$(git -C "$SOURCE" rev-parse HEAD)"
grep -Fq "Commit: \`$CURRENT_SHA\`" "$WIKI/workspace/source-state.md"
grep -Fq 'Stage: `all`' "$WIKI/workspace/source-state.md"
grep -Fq 'Files: `7`' "$WIKI/workspace/source-state.md"

EMPTY_SOURCE="$TMP_ROOT/empty-source"
EMPTY_WIKI="$TMP_ROOT/empty-wiki"
mkdir -p "$EMPTY_SOURCE" "$EMPTY_WIKI/raw/sources/repository" "$EMPTY_WIKI/workspace"
git -C "$EMPTY_SOURCE" init -q
git -C "$EMPTY_SOURCE" config user.name "Wiki Sync Test"
git -C "$EMPTY_SOURCE" config user.email "wiki-sync-test@example.invalid"
printf 'unsupported\n' > "$EMPTY_SOURCE/notes.txt"
git -C "$EMPTY_SOURCE" add notes.txt
git -C "$EMPTY_SOURCE" commit -q \
  -m "test: create empty allowlist fixture" \
  -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
printf 'preserve\n' > "$EMPTY_WIKI/raw/sources/repository/keep.md"

if WIKI_ROOT_OVERRIDE="$EMPTY_WIKI" "$SYNC" "$EMPTY_SOURCE" all; then
  printf 'empty allowlist unexpectedly succeeded\n' >&2
  exit 1
fi
assert_file "$EMPTY_WIKI/raw/sources/repository/keep.md"

printf 'source synchronization: PASS\n'
```

Make it executable:

```bash
chmod +x ../agent-framework-java-wiki/workspace/tests/sync-sources-test.sh
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
../agent-framework-java-wiki/workspace/tests/sync-sources-test.sh
```

Expected: FAIL because `workspace/bin/sync-sources.sh` does not exist.

- [ ] **Step 3: Implement the synchronization script**

Create `../agent-framework-java-wiki/workspace/bin/sync-sources.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
DEFAULT_WIKI_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd -P)"
WIKI_ROOT="${WIKI_ROOT_OVERRIDE:-$DEFAULT_WIKI_ROOT}"
SOURCE_REPO_INPUT="${1:-$WIKI_ROOT/../agent-framework-java}"
STAGE="${2:-all}"

if ! SOURCE_REPO="$(cd "$SOURCE_REPO_INPUT" 2>/dev/null && pwd -P)"; then
  printf 'source repository does not exist: %s\n' "$SOURCE_REPO_INPUT" >&2
  exit 2
fi

git -C "$SOURCE_REPO" rev-parse --is-inside-work-tree >/dev/null 2>&1 || {
  printf 'not a Git repository: %s\n' "$SOURCE_REPO" >&2
  exit 2
}

case "$STAGE" in
  foundation) STAGE_LIMIT=1 ;;
  contracts) STAGE_LIMIT=2 ;;
  upstream) STAGE_LIMIT=3 ;;
  build) STAGE_LIMIT=4 ;;
  main) STAGE_LIMIT=5 ;;
  test|all) STAGE_LIMIT=6 ;;
  *)
    printf 'unknown stage: %s\n' "$STAGE" >&2
    printf 'valid stages: foundation contracts upstream build main test all\n' >&2
    exit 2
    ;;
esac

rank_for() {
  local path="$1"

  case "/$path/" in
    */.git/*|*/.worktrees/*|*/build/*|*/.gradle/*|*/.idea/*|*/.vscode/*|*/.llm-wiki/*)
      return 1
      ;;
  esac

  if [[ "$path" == *.md ]]; then
    case "$path" in
      */*) ;;
      *) printf '1\n'; return 0 ;;
    esac
    case "$path" in
      docs/README.md|docs/ko/README.md|docs/operations/*|docs/requirements/README.md|docs/design/requirements-design/README.md|docs/upstream/README.md|docs/upstream/snapshots/*/README.md)
        printf '1\n'
        ;;
      docs/requirements/*|docs/design/*)
        printf '2\n'
        ;;
      docs/upstream/*)
        printf '3\n'
        ;;
      *)
        printf '4\n'
        ;;
    esac
    return 0
  fi

  case "$path" in
    *.gradle.kts|gradle/libs.versions.toml|gradle/wrapper/gradle-wrapper.properties|.github/workflows/*.yml|.github/workflows/*.yaml|.harness/*.json)
      printf '4\n'
      ;;
    */src/main/*.java)
      printf '5\n'
      ;;
    */src/test/*.java)
      printf '6\n'
      ;;
    *)
      return 1
      ;;
  esac
}

language_for() {
  case "$1" in
    *.java) printf 'java\n' ;;
    *.gradle.kts) printf 'kotlin\n' ;;
    *.toml) printf 'toml\n' ;;
    *.properties) printf 'properties\n' ;;
    *.json) printf 'json\n' ;;
    *.yaml|*.yml) printf 'yaml\n' ;;
    *) printf 'text\n' ;;
  esac
}

COMMIT="$(git -C "$SOURCE_REPO" rev-parse HEAD)"
TMP_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/afj-wiki-sync.XXXXXX")"
trap 'rm -rf "$TMP_ROOT"' EXIT
MIRROR="$TMP_ROOT/repository"
mkdir -p "$MIRROR"

FILE_COUNT=0
while IFS= read -r -d '' FILE; do
  RANK="$(rank_for "$FILE")" || continue
  (( RANK <= STAGE_LIMIT )) || continue

  if [[ "$FILE" == *.md ]]; then
    OUTPUT="$MIRROR/$FILE"
    mkdir -p "$(dirname "$OUTPUT")"
    git -C "$SOURCE_REPO" show "$COMMIT:$FILE" > "$OUTPUT"
  else
    OUTPUT="$MIRROR/$FILE.md"
    mkdir -p "$(dirname "$OUTPUT")"
    LANGUAGE="$(language_for "$FILE")"
    if [[ "$FILE" == *"'"* ]]; then
      printf 'unsupported single quote in repository path: %s\n' "$FILE" >&2
      exit 4
    fi
    {
      printf '%s\n' '---'
      printf "source_path: '%s'\n" "$FILE"
      printf 'source_commit: %s\n' "$COMMIT"
      printf 'source_language: %s\n' "$LANGUAGE"
      printf '%s\n\n' '---'
      printf '# Source: `%s`\n\n' "$FILE"
      printf '~~~~%s\n' "$LANGUAGE"
      git -C "$SOURCE_REPO" show "$COMMIT:$FILE"
      printf '\n~~~~\n'
    } > "$OUTPUT"
  fi

  (( FILE_COUNT += 1 ))
done < <(git -C "$SOURCE_REPO" ls-tree -r --name-only -z "$COMMIT")

if (( FILE_COUNT == 0 )); then
  printf 'allowlist produced no files for stage %s at %s\n' "$STAGE" "$COMMIT" >&2
  exit 3
fi

DESTINATION="$WIKI_ROOT/raw/sources/repository"
mkdir -p "$DESTINATION" "$WIKI_ROOT/workspace"
rsync -a --checksum --delete "$MIRROR/" "$DESTINATION/"

SYNCED_AT="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
STATE_TMP="$TMP_ROOT/source-state.md"
{
  printf '# Source State\n\n'
  printf -- '- Repository: `%s`\n' "$SOURCE_REPO"
  printf -- '- Commit: `%s`\n' "$COMMIT"
  printf -- '- Stage: `%s`\n' "$STAGE"
  printf -- '- Files: `%s`\n' "$FILE_COUNT"
  printf -- '- Synced: `%s`\n' "$SYNCED_AT"
} > "$STATE_TMP"
mv "$STATE_TMP" "$WIKI_ROOT/workspace/source-state.md"

printf 'synced %s files from %s at %s (stage: %s)\n' \
  "$FILE_COUNT" "$SOURCE_REPO" "$COMMIT" "$STAGE"
```

Make it executable:

```bash
chmod +x ../agent-framework-java-wiki/workspace/bin/sync-sources.sh
```

- [ ] **Step 4: Run the synchronization tests**

Run:

```bash
../agent-framework-java-wiki/workspace/tests/sync-sources-test.sh
```

Expected:

```text
source synchronization: PASS
```

- [ ] **Step 5: Run shell syntax checks**

Run:

```bash
bash -n ../agent-framework-java-wiki/workspace/bin/sync-sources.sh
bash -n ../agent-framework-java-wiki/workspace/tests/project-contract-test.sh
bash -n ../agent-framework-java-wiki/workspace/tests/sync-sources-test.sh
```

Expected: all commands exit successfully with no output.

- [ ] **Step 6: Commit the local maintenance tools**

Run:

```bash
SOURCE_REPO="$(git rev-parse --show-toplevel)"
WIKI_ROOT="$(dirname "$SOURCE_REPO")/agent-framework-java-wiki"
git -C "$WIKI_ROOT" add workspace/bin workspace/tests
git -C "$WIKI_ROOT" commit \
  -m "build: add deterministic source synchronization" \
  -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

Expected: a local commit containing the synchronization script and both passing test scripts.

### Task 4: Synchronize and Ingest the Source Corpus

**Files:**
- Generate: `../agent-framework-java-wiki/raw/sources/repository/`
- Generate: `../agent-framework-java-wiki/workspace/source-state.md`
- Modify through LLM Wiki: `../agent-framework-java-wiki/wiki/`

**Interfaces:**
- Consumes: the tested synchronizer from Task 3 and LLM Wiki's source watcher.
- Produces: a complete, successfully ingested corpus at one recorded source commit.

- [ ] **Step 1: Configure the source watcher**

In LLM Wiki:

1. Open **Settings**.
2. Open **Source Folder Auto Watch**.
3. Enable **Monitor project source folder**.
4. Enable **Auto-ingest allowed source files**.
5. Ensure `.md` is enabled under **Allowed file types**.
6. Set parsing concurrency to `2`.
7. Set ingest concurrency to `1`.
8. Save the settings.

Expected: the project source watcher is running. No source-code extension needs enabling because the
synchronizer renders non-Markdown sources as Markdown wrappers.

- [ ] **Step 2: Run and complete the foundation ingest**

Run:

```bash
../agent-framework-java-wiki/workspace/bin/sync-sources.sh \
  "$(git rev-parse --show-toplevel)" foundation
```

In LLM Wiki, open **Activity** and wait until the queue has no pending, running, failed, or cancelled
item. Resolve any explicit failure before continuing.

Expected: repository instructions, root navigation, operations, Korean companion, and major indexes
are ingested.

- [ ] **Step 3: Run and complete the contracts ingest**

Run:

```bash
../agent-framework-java-wiki/workspace/bin/sync-sources.sh \
  "$(git rev-parse --show-toplevel)" contracts
```

Wait for the Activity queue to finish successfully.

Expected: requirements and approved designs are added without removing foundation sources.

- [ ] **Step 4: Run and complete the pinned upstream ingest**

Run:

```bash
../agent-framework-java-wiki/workspace/bin/sync-sources.sh \
  "$(git rev-parse --show-toplevel)" upstream
```

Wait for the Activity queue to finish successfully.

Expected: pinned upstream analyses are added only after the Java contracts are available.

- [ ] **Step 5: Run and complete the build-contract ingest**

Run:

```bash
../agent-framework-java-wiki/workspace/bin/sync-sources.sh \
  "$(git rev-parse --show-toplevel)" build
```

Wait for the Activity queue to finish successfully.

Expected: remaining Markdown, Gradle scripts, version catalog, wrapper properties, workflows, and
harness schemas are ingested.

- [ ] **Step 6: Run and complete the production-source ingest**

Run:

```bash
../agent-framework-java-wiki/workspace/bin/sync-sources.sh \
  "$(git rev-parse --show-toplevel)" main
```

Wait for the Activity queue to finish successfully.

Expected: production Java source wrappers are ingested.

- [ ] **Step 7: Run and complete the test-source ingest**

Run:

```bash
../agent-framework-java-wiki/workspace/bin/sync-sources.sh \
  "$(git rev-parse --show-toplevel)" all
```

Wait for the Activity queue to finish successfully.

Expected: Java test wrappers complete the corpus and `workspace/source-state.md` records stage `all`.

- [ ] **Step 8: Verify the final mirror and recorded state**

Run:

```bash
SOURCE_REPO="$(git rev-parse --show-toplevel)"
WIKI_ROOT="$(dirname "$SOURCE_REPO")/agent-framework-java-wiki"
SOURCE_SHA="$(git -C "$SOURCE_REPO" rev-parse HEAD)"
RAW_COUNT="$(find "$WIKI_ROOT/raw/sources/repository" -type f | wc -l | tr -d ' ')"
STATE_COUNT="$(awk -F'`' '/^- Files:/{print $2}' "$WIKI_ROOT/workspace/source-state.md")"

grep -Fq "Commit: \`$SOURCE_SHA\`" "$WIKI_ROOT/workspace/source-state.md"
grep -Fq 'Stage: `all`' "$WIKI_ROOT/workspace/source-state.md"
test "$RAW_COUNT" = "$STATE_COUNT"
test -z "$(find "$WIKI_ROOT/raw/sources/repository" \
  \( -path '*/build/*' -o -path '*/.git/*' -o -path '*/.worktrees/*' \) -print -quit)"
```

Expected: every command succeeds, the mirror count equals the recorded count, and excluded paths are
absent.

- [ ] **Step 9: Commit the ingest-generated wiki state**

Review the local diff first:

```bash
SOURCE_REPO="$(git rev-parse --show-toplevel)"
WIKI_ROOT="$(dirname "$SOURCE_REPO")/agent-framework-java-wiki"
git -C "$WIKI_ROOT" status --short
git -C "$WIKI_ROOT" diff --check
```

Confirm that `.llm-wiki/`, `raw/`, `workspace/source-state.md`, and the product repository are not
staged. Then run:

```bash
git -C "$WIKI_ROOT" add wiki
git -C "$WIKI_ROOT" commit \
  -m "docs: ingest agent framework sources" \
  -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

Expected: generated wiki pages are versioned locally; raw sources and personal application state
remain ignored.

### Task 5: Curate, Lint, and Verify the Initial Wiki

**Files:**
- Create: `../agent-framework-java-wiki/workspace/prompts/bootstrap-architecture.md`
- Create: `../agent-framework-java-wiki/workspace/prompts/bootstrap-concepts.md`
- Create: `../agent-framework-java-wiki/workspace/prompts/finalize-wiki.md`
- Create or modify through LLM Wiki: `../agent-framework-java-wiki/wiki/overview.md`
- Create or modify through LLM Wiki: `../agent-framework-java-wiki/wiki/index.md`
- Create or modify through LLM Wiki: `../agent-framework-java-wiki/wiki/log.md`
- Create through LLM Wiki: `../agent-framework-java-wiki/wiki/{architecture,modules,concepts,contributing,status,sources}/*.md`

**Interfaces:**
- Consumes: the fully ingested corpus, `purpose.md`, `schema.md`, and `workspace/source-state.md`.
- Produces: the approved initial page set, a complete index, resolved internal links, review items for real ambiguity, and a clean local wiki commit.

- [ ] **Step 1: Write the architecture bootstrap prompt**

Create `../agent-framework-java-wiki/workspace/prompts/bootstrap-architecture.md`:

```markdown
# Bootstrap Orientation, Architecture, and Modules

Read `purpose.md`, `schema.md`, and `workspace/source-state.md` before writing. Use only ingested
repository sources. Do not use web search. Write Korean prose and preserve English identifiers,
commands, paths, package names, class names, and requirement IDs.

Create or update these pages:

- `wiki/overview.md`
- `wiki/architecture/project-boundaries.md`
- `wiki/architecture/module-composition.md`
- `wiki/architecture/evidence-and-authority.md`
- `wiki/architecture/build-and-policy-harness.md`
- `wiki/modules/api.md`
- `wiki/modules/engine.md`
- `wiki/modules/testkit.md`
- `wiki/modules/bom-and-publication.md`
- `wiki/sources/repository-source-map.md`

For each factual page:

1. Follow the page type and directory mapping in `schema.md`.
2. Set `status: derived`.
3. Set `source_commit` to the full commit in `workspace/source-state.md`.
4. Add precise `sources` below `raw/sources/repository/`.
5. Cite stable requirement IDs or source headings in the prose where possible.
6. State implemented and planned behavior separately.
7. Do not duplicate full requirement tables or acceptance criteria.
8. Add useful `[[wikilink]]` connections.

If sources conflict or authority is ambiguous, set `status: review-needed`, explain both claims with
citations, and create a query page instead of guessing.
```

- [ ] **Step 2: Run the architecture bootstrap prompt**

Open LLM Wiki **Chat**, paste the complete prompt from
`workspace/prompts/bootstrap-architecture.md`, and let the Chat Agent create or update the requested
files.

Expected: all ten requested pages exist and the Agent reports no unsupported source or web-search use.

- [ ] **Step 3: Write the concepts bootstrap prompt**

Create `../agent-framework-java-wiki/workspace/prompts/bootstrap-concepts.md`:

```markdown
# Bootstrap Concepts, Contribution, and Status

Read `purpose.md`, `schema.md`, `workspace/source-state.md`, and the existing architecture/module
pages before writing. Use only ingested repository sources. Do not use web search. Write Korean prose
and preserve English identifiers, commands, paths, package names, class names, and requirement IDs.

Create or update these pages:

- `wiki/concepts/observable-execution.md`
- `wiki/concepts/messages-content-and-streaming.md`
- `wiki/concepts/sessions-context-and-decorators.md`
- `wiki/concepts/tools-approvals-and-mcp.md`
- `wiki/concepts/planned-capabilities.md`
- `wiki/contributing/first-change-workflow.md`
- `wiki/contributing/verification-and-review.md`
- `wiki/status/implementation-status.md`

For each factual page:

1. Follow the page type and directory mapping in `schema.md`.
2. Set `status: derived`.
3. Set `source_commit` to the full commit in `workspace/source-state.md`.
4. Add precise `sources` below `raw/sources/repository/`.
5. Cite stable requirement IDs or source headings in the prose where possible.
6. Clearly distinguish public contracts already present from engine behavior that is not implemented.
7. Do not treat exact natural-language output or exact tool-call order as a long-term contract.
8. Add inbound and outbound `[[wikilink]]` connections.

If sources conflict or authority is ambiguous, set `status: review-needed`, explain both claims with
citations, and create a query page instead of guessing.
```

- [ ] **Step 4: Run the concepts bootstrap prompt**

Open LLM Wiki **Chat**, paste the complete prompt from
`workspace/prompts/bootstrap-concepts.md`, and let the Chat Agent create or update the requested files.

Expected: all eight requested pages exist and separate current implementation from planned behavior.

- [ ] **Step 5: Write the finalization prompt**

Create `../agent-framework-java-wiki/workspace/prompts/finalize-wiki.md`:

```markdown
# Finalize the Initial Wiki

Read `purpose.md`, `schema.md`, `workspace/source-state.md`, every factual page under `wiki/`, and the
current `wiki/index.md`, `wiki/overview.md`, and `wiki/log.md`. Use only ingested repository sources.
Do not use web search.

Perform one consistency pass:

1. Make `wiki/index.md` list every factual page exactly once under its schema page type with a
   one-line Korean summary.
2. Make `wiki/overview.md` the shortest useful reading path for a new contributor.
3. Ensure every factual page has the current `source_commit`, at least one precise source, and at
   least one inbound wikilink.
4. Remove duplicate pages only when their information is fully merged into the retained page.
5. Do not copy complete requirement tables, acceptance criteria, or upstream evidence ledgers.
6. Mark unresolved contradictions and ambiguous authority as `review-needed` and create query pages.
7. Append a reverse-chronological `initial-generation` entry to `wiki/log.md` with source commit,
   scope, result, and review item count.
8. Do not record prompts, raw model responses, tool arguments, credentials, chat transcripts, or
   personal agent traces.

Report the exact paths changed and every remaining Review item.
```

- [ ] **Step 6: Run the finalization prompt**

Open LLM Wiki **Chat**, paste the complete prompt from `workspace/prompts/finalize-wiki.md`, and let
the Chat Agent complete the consistency pass.

Expected: the Agent reports changed paths and an explicit review-item list, which may be empty.

- [ ] **Step 7: Run filesystem acceptance checks**

Run:

```bash
SOURCE_REPO="$(git rev-parse --show-toplevel)"
WIKI_ROOT="$(dirname "$SOURCE_REPO")/agent-framework-java-wiki"
SOURCE_SHA="$(git -C "$SOURCE_REPO" rev-parse HEAD)"

REQUIRED_PAGES=(
  wiki/overview.md
  wiki/architecture/project-boundaries.md
  wiki/architecture/module-composition.md
  wiki/architecture/evidence-and-authority.md
  wiki/architecture/build-and-policy-harness.md
  wiki/modules/api.md
  wiki/modules/engine.md
  wiki/modules/testkit.md
  wiki/modules/bom-and-publication.md
  wiki/concepts/observable-execution.md
  wiki/concepts/messages-content-and-streaming.md
  wiki/concepts/sessions-context-and-decorators.md
  wiki/concepts/tools-approvals-and-mcp.md
  wiki/concepts/planned-capabilities.md
  wiki/contributing/first-change-workflow.md
  wiki/contributing/verification-and-review.md
  wiki/status/implementation-status.md
  wiki/sources/repository-source-map.md
)

for relative in "${REQUIRED_PAGES[@]}"; do
  page="$WIKI_ROOT/$relative"
  test -f "$page"
  grep -Fq "source_commit: $SOURCE_SHA" "$page"
  grep -Fq 'sources:' "$page"
  slug="$(basename "$page" .md)"
  grep -Fq "[[$slug]]" "$WIKI_ROOT/wiki/index.md"
done

test -z "$(grep -ERl 'T(BD)|T(ODO)|FIX(ME)' \
  "$WIKI_ROOT/purpose.md" "$WIKI_ROOT/schema.md" "$WIKI_ROOT/wiki" || true)"
```

Expected: every command succeeds; all required pages carry the current source commit and are indexed.

- [ ] **Step 8: Run LLM Wiki lint and graph checks**

In LLM Wiki:

1. Open **Lint** and run a full wiki check.
2. Resolve broken links, stale commit findings, missing citations, duplicate pages, and unexplained
   orphans.
3. Leave genuine contradictions as visible Review items.
4. Open **Graph** and confirm the overview, architecture, module, concept, contribution, status, and
   source-map clusters are connected.
5. Open **Review** and confirm every remaining item describes a real human decision.

Expected: no unresolved high-confidence lint error; no unexplained orphan; intentional ambiguity is
visible in Review.

- [ ] **Step 9: Prove the product worktree was not changed**

Run:

```bash
SOURCE_REPO="$(git rev-parse --show-toplevel)"
WIKI_ROOT="$(dirname "$SOURCE_REPO")/agent-framework-java-wiki"
CURRENT_STATUS="$(mktemp "${TMPDIR:-/tmp}/afj-current-status.XXXXXX")"
trap 'rm -f "$CURRENT_STATUS"' EXIT
git -C "$SOURCE_REPO" status --porcelain=v1 > "$CURRENT_STATUS"
cmp "$WIKI_ROOT/workspace/source-worktree-baseline.txt" "$CURRENT_STATUS"
```

Expected: `cmp` exits successfully with no output.

- [ ] **Step 10: Run all local checks and commit the curated wiki**

Run:

```bash
SOURCE_REPO="$(git rev-parse --show-toplevel)"
WIKI_ROOT="$(dirname "$SOURCE_REPO")/agent-framework-java-wiki"

"$WIKI_ROOT/workspace/tests/project-contract-test.sh"
"$WIKI_ROOT/workspace/tests/sync-sources-test.sh"
bash -n "$WIKI_ROOT/workspace/bin/sync-sources.sh"
git -C "$WIKI_ROOT" diff --check
git -C "$WIKI_ROOT" status --short
```

Expected: both tests print `PASS`, syntax and diff checks succeed, and status lists only intended wiki
pages and prompt files.

Commit:

```bash
git -C "$WIKI_ROOT" add wiki workspace/prompts
git -C "$WIKI_ROOT" commit \
  -m "docs: curate initial agent framework wiki" \
  -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
git -C "$WIKI_ROOT" status --short
```

Expected: the commit succeeds and the final status is empty because all remaining machine-local state
is ignored.
