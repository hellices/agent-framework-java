# English Documentation Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make English the single source of truth for every repository-owned Markdown document,
keep one maintained Korean companion at `docs/ko/README.md`, and make `./gradlew policyCheck` fail
whenever that contract regresses.

**Architecture:** A new English documentation index at `docs/README.md` becomes the documentation
map. Four new Java policy sources under `build-tools/harness-policy` scan the repository-owned
Markdown that section 8.1 of the policy names — the six root documents, `.github/*.md`, and
`docs/**/*.md` — and enforce three rules: no Korean text outside the companion, exactly one document
under `docs/ko/`, and every relative Markdown link plus heading fragment resolves inside the
repository.
Fifty-two Korean-centred documents are then translated in place, in directory-sized batches, each
batch guarded by objective before/after preservation counts. The policy tests carry a shrinking
`PENDING_TRANSLATION` list so the rule is executable from the first commit and cannot be satisfied
by a stale suppression; the list is deleted in the final task.

**Tech Stack:** Markdown, Gradle Kotlin DSL 9.7.0, Java 17 toolchain, JUnit Jupiter 6.1.3, AssertJ
3.27.7, `ripgrep` and `git` for verification commands.

## Global Constraints

- Work only in the worktree `/Users/hwang-inhwan/workspace/agent-framework-java/.worktrees/docs-english-source`, on branch `docs-english-source`. Every path in this plan is relative to that directory.
- The approved specification is `docs/design/documentation-language-policy.md`. It is already English; do not edit it.
- Canonical documents keep their existing paths. Do not rename, move, split, or delete a document.
- Preserve all 244 requirement IDs, every upstream commit SHA `d0a4165f170193ba1d026a259af40d35bb7eaefe`, every upstream permalink, every table row, every fenced block, and every acceptance criterion.
- Translate prose, headings, captions, table labels, and explanatory notes only. Never summarize, compress, merge, drop, or reorder content while translating.
- Never introduce a new behavioral decision, support level, grade, phase, or requirement while translating. If translation exposes an ambiguity, leave the meaning as written and record the ambiguity in the task's final report instead of resolving it in the document.
- Keep code blocks, commands, package names, class and member names, JSON fields, configuration keys, module names, and upstream quotations exactly as they are.
- Use these project terms consistently: `AgentEngine`, host runtime, provider adapter, session, tool call, checkpoint, conformance.
- Requirement and matrix controlled values are translated with this fixed glossary, anchored on the grade definition table in `docs/requirements/README.md`:

  | Source value, identified by its definition | English value |
  | --- | --- |
  | Grade whose definition is "the feature is not released without this requirement" | `Required` |
  | Grade whose definition is "may be implemented differently when the rationale is recorded" | `Recommended` |
  | Grade whose definition is "implement when needed; release without it" | `Optional` |
  | Compatibility-matrix adoption verdict meaning "not decided in this snapshot" | `Deferred` |
  | Requirement status meaning "withdrawn, id never reused" | `Withdrawn` |

- Release phase values `MVP`, `Core+`, `Workflow`, `Hosting`, and `Optional` are already ASCII. Copy them unchanged.
- The five bold labels inside every requirement section keep a fixed order and render exactly as `**Requirement.**`, `**Upstream comparison**`, `**Decision.**`, `**Acceptance criteria**`, `**Evidence**`. The document header label renders as `**Prefix**` and `**Upstream features**`.
- This plan file lives under `docs/` and is therefore governed by the same no-Korean rule. It must stay free of Korean characters, which is why Korean source strings are described by their role and by shell patterns instead of being quoted.
- No new Gradle dependency. `build-tools/harness-policy/gradle.lockfile` must be unchanged at the end of every task. If a task changes it, the task took a wrong turn.
- New Java code lives in package `com.microsoft.agentframework.build.harness` under `build-tools/harness-policy/src/test/java/`, matches google-java-format (2-space indent, 100-column target), and passes Checkstyle's 120-column limit.
- Verification entry points are `./gradlew policyCheck`, `./gradlew quality`, `./gradlew testJava17 testJava21 testJava25`, and `./gradlew check`. `testJava21` and `testJava25` fail with a toolchain error when Temurin 21 or 25 is absent locally; report that instead of hiding it.
- Never use `@Disabled`, a broad `catch`, a deleted assertion, or a widened allowlist to make a check pass.
- Every commit message in this plan ends with a blank line and then:

  `Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>`

### Verification toolbox

Every translation task uses the same shell helpers. Paste all three of them — `doc_metrics`,
`column_shape`, and `column_values` — into the shell before running any metric step of a task.
`doc_metrics` prints nine counts in this fixed order: requirement ids, URLs, upstream pin
mentions, fence markers, table rows, table separators, level-2 headings, level-3 headings, bullets.

```bash
doc_metrics() {
  local text; text="$(cat)"
  local pattern
  for pattern in '^\| *[A-Z]{2,6}-[0-9]{3} *\|' 'https?://' 'd0a4165f170193ba1d026a259af40d35bb7eaefe' '^```' '^\|' '^\|[ :|-]+\|$' '^## ' '^### ' '^ *[-*] '; do
    printf '%s ' "$(printf '%s\n' "$text" | rg --count-matches "$pattern" || echo 0)"
  done
  printf '\n'
}
```

Compare the committed version against the working tree with:

```bash
for f in <files of this batch>; do
  printf '%-74s HEAD %s\n' "$f" "$(git show HEAD:"$f" | doc_metrics)"
  printf '%-74s WORK %s\n' "$f" "$(doc_metrics < "$f")"
done
```

The `HEAD` and `WORK` lines of each file must show identical nine-number sequences.

Requirement tables carry three extra column checks. `$4` is the adoption column, `$5` is the grade
column, `$6` is the release phase column:

```bash
column_shape() { awk -F'|' -v c="$1" '/^\| *[A-Z]{2,6}-[0-9]{3} *\|/ {gsub(/^ +| +$/,"",$c); print $c}' | sort | uniq -c | awk '{print $1}' | sort -n | paste -sd, -; }
column_values() { awk -F'|' -v c="$1" '/^\| *[A-Z]{2,6}-[0-9]{3} *\|/ {gsub(/^ +| +$/,"",$c); print $c}' | sort -u | paste -sd, -; }
```

The Korean scan used throughout is script-based and needs no Korean literal:

```bash
rg -l '\p{Hangul}' --glob '*.md' .
```

## File Map

**Created**

| Path | Responsibility |
| --- | --- |
| `docs/README.md` | Role-based English documentation map and source-of-truth statement |
| `build-tools/harness-policy/src/test/java/com/microsoft/agentframework/build/harness/MarkdownDocuments.java` | Repository-owned Markdown discovery, Korean detection, link extraction, GitHub-style anchor generation |
| `build-tools/harness-policy/src/test/java/com/microsoft/agentframework/build/harness/DocumentationLanguagePolicyTest.java` | Canonical-language scan, companion uniqueness, bidirectional entry links, shrinking migration list |
| `build-tools/harness-policy/src/test/java/com/microsoft/agentframework/build/harness/MarkdownLinkPolicyTest.java` | Relative link resolution, heading fragment resolution, repository-escape rejection, anchor unit tests |
| `docs/plans/documentation-language-migration.md` | This plan |

**Modified**

| Path | Change |
| --- | --- |
| `build-tools/harness-policy/build.gradle.kts` | Declare the policy input tree through the shared `RepositoryPolicyInputs`, which removes build output by location instead of by name or depth |
| `README.md` | English label on the companion link, link to `docs/README.md`, remove the last Korean characters |
| `docs/operations/getting-started.md` | Replace the one Korean grade value with `Required` |
| `docs/ko/README.md` | Backlink to `docs/README.md`, authoritative-source marker, expanded companion guide |
| `docs/design/foundation-design.md` | Translate |
| `docs/design/engineering-harness-design.md` | Translate |
| `docs/design/gradle-kotlin-arc-foundation-design.md` | Translate |
| `docs/requirements/README.md` | Translate, add documentation-index backlink |
| `docs/requirements/01-agent-execution.md` … `docs/requirements/12-providers.md` | Translate all twelve requirement contracts, retarget the grade anchor |
| `docs/upstream/README.md` | Translate, add documentation-index backlink |
| `docs/upstream/snapshots/d0a4165f/README.md` | Translate, retarget the self anchor |
| `docs/upstream/snapshots/d0a4165f/snapshot-manifest.md` | Translate |
| `docs/upstream/snapshots/d0a4165f/coverage-ledger.md` | Translate |
| `docs/upstream/snapshots/d0a4165f/compatibility-matrix.md` | Translate |
| `docs/upstream/snapshots/d0a4165f/features/01-agent-lifecycle.md` … `features/31-provider-integrations.md` | Translate all thirty-one feature analyses |

**Never modified**

`docs/design/documentation-language-policy.md`, `docs/design/module-composition.md`,
`docs/operations/github-actions-runner-contract.md`, `AGENTS.md`, `CLAUDE.md`, `GEMINI.md`,
`.github/copilot-instructions.md`, `CONTRIBUTING.md`, `SECURITY.md`, `gradle/libs.versions.toml`,
`build-tools/harness-policy/gradle.lockfile`.

**Baseline: the 55 files that currently contain Korean text**

| Owner | Count | Files |
| --- | ---: | --- |
| Task 1 | 2 | `README.md`, `docs/operations/getting-started.md` |
| Task 2 | 3 | `docs/design/foundation-design.md`, `docs/design/engineering-harness-design.md`, `docs/design/gradle-kotlin-arc-foundation-design.md` |
| Task 3 | 7 | `docs/requirements/README.md`, `docs/requirements/01-agent-execution.md`, `02-message-content.md`, `03-structured-output.md`, `04-tools.md`, `05-mcp.md`, `06-sessions.md` |
| Task 4 | 6 | `docs/requirements/07-interceptors.md`, `08-harness.md`, `09-workflows.md`, `10-hosting.md`, `11-operations.md`, `12-providers.md` |
| Task 5 | 20 | `docs/upstream/README.md`, `docs/upstream/snapshots/d0a4165f/README.md`, `snapshot-manifest.md`, `coverage-ledger.md`, `compatibility-matrix.md`, `features/01-agent-lifecycle.md` … `features/15-workflow-runtime.md` |
| Task 6 | 16 | `docs/upstream/snapshots/d0a4165f/features/16-workflow-checkpoint-hitl.md` … `features/31-provider-integrations.md` |
| Task 7 | 1 | `docs/ko/README.md` (stays Korean by policy) |

---

### Task 1: English documentation hub and executable language, link, and companion policy

**Files:**
- Create: `build-tools/harness-policy/src/test/java/com/microsoft/agentframework/build/harness/MarkdownDocuments.java`
- Create: `build-tools/harness-policy/src/test/java/com/microsoft/agentframework/build/harness/MarkdownDocumentsTest.java`
- Create: `build-tools/harness-policy/src/test/java/com/microsoft/agentframework/build/harness/DocumentationLanguagePolicyTest.java`
- Create: `build-tools/harness-policy/src/test/java/com/microsoft/agentframework/build/harness/MarkdownLinkPolicyTest.java`
- Create: `docs/README.md`
- Create: `build-logic/src/main/kotlin/com/microsoft/agentframework/build/logic/RepositoryPolicyInputs.kt`
- Create: `build-logic/src/test/kotlin/com/microsoft/agentframework/build/logic/RepositoryPolicyInputsTest.kt`
- Modify: `build-tools/harness-policy/build.gradle.kts`
- Modify: `build-tools/harness-policy/src/test/java/com/microsoft/agentframework/build/harness/BuildContractPolicyTest.java` (one added case)
- Modify: `README.md`
- Modify: `docs/operations/getting-started.md`
- Modify: `docs/requirements/README.md` (one added link line only)
- Modify: `docs/upstream/README.md` (one added link line only)
- Modify: `docs/ko/README.md` (one added link line and one added marker sentence only)
- Test: the four Java sources above are the tests; they run under `./gradlew :build-tools:harness-policy:test` and `./gradlew policyCheck`

**Interfaces:**
- Consumes: `RepositoryPaths.root()` from `build-tools/harness-policy/src/main/java/com/microsoft/agentframework/build/harness/RepositoryPaths.java`, which returns the closest ancestor holding both `settings.gradle.kts` and `gradle/libs.versions.toml`.
- Produces, used by Tasks 2 through 8:
  - `MarkdownDocuments.KOREAN_COMPANION` — `String`, value `docs/ko/README.md`
  - `MarkdownDocuments.DOCUMENTATION_INDEX` — `String`, value `docs/README.md`
  - `MarkdownDocuments.files()` — `List<Path>` of the Markdown the canonical locations of policy section 8.1 hold, throws `IOException`
  - `MarkdownDocuments.filesUnder(Path root)` — the same allowlist scan against any root, so the ownership invariant is testable, throws `IOException`
  - `MarkdownDocuments.relativePath(Path)` — `String` with `/` separators
  - `MarkdownDocuments.relativePath(Path root, Path file)` — `String` relative to a scan root
  - `MarkdownDocuments.containsHangul(String)` — `boolean`
  - `MarkdownDocuments.hangulLines(Path)` — `List<String>` of `path:line`, throws `IOException`
  - `MarkdownDocuments.links(Path)` — `List<MarkdownDocuments.Link>`, throws `IOException`
  - `MarkdownDocuments.Link` — `record Link(Path source, int line, String target)` with `String describe()`
  - `MarkdownDocuments.isLocalTarget(String)` — `boolean`
  - `MarkdownDocuments.filePartOf(String)` and `MarkdownDocuments.fragmentOf(String)` — `String`
  - `MarkdownDocuments.resolveTarget(Path source, String target)` — `Optional<Path>`, empty when the target escapes the repository root
  - `MarkdownDocuments.anchors(Path)` — `Set<String>`, throws `IOException`
  - `MarkdownDocuments.anchorOf(String headingText)` — `String`
  - `DocumentationLanguagePolicyTest.PENDING_TRANSLATION` — the 52-entry migration list that Tasks 2 through 6 shrink and Task 8 deletes
  - `DocumentationLanguagePolicyTest.PENDING_TRANSLATION_SIZE` — the exact size that list must have, lowered in the same commit that removes entries
  - `DocumentationLanguagePolicyTest.ORIGINAL_PENDING_TRANSLATION` — the frozen 52-entry membership the ratchet was installed with; never edited by Tasks 2 through 6, deleted by Task 8
  - `DocumentationLanguagePolicyTest.ORIGINAL_PENDING_TRANSLATION_SIZE` — `52`, the size of that frozen baseline
  - `RepositoryPolicyInputs.repositoryPolicySources(Project)` — `FileTree` of the repository files the policy tasks read
  - `RepositoryPolicyInputs.excludePatterns(File)` — `List<String>` of the Ant patterns that tree excludes

- [ ] **Step 1: Write the Markdown scanner helper**

Create `build-tools/harness-policy/src/test/java/com/microsoft/agentframework/build/harness/MarkdownDocuments.java`:

```java
package com.microsoft.agentframework.build.harness;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reads repository-owned Markdown for the documentation language, companion, and link policies.
 *
 * <p>{@link #files()} is an allowlist of locations, not a filtered walk of the working tree. It
 * returns Markdown from exactly the canonical locations section 8.1 of the documentation language
 * policy names: the root documents listed in {@link #OWNED_ROOT_DOCUMENTS}, the Markdown files
 * directly inside {@code .github}, and every Markdown file under {@code docs}. Everything else in
 * the working tree is out of scope because the repository does not own it as documentation:
 * generated build output, ignored session artifacts, agent plugin directories, nested worktrees,
 * dependency notices, and untracked scratch files. Nothing a contributor happens to leave on disk
 * can widen the canonical document set.
 *
 * <p>Ownership is decided by location alone, never by a directory name. A rule such as "skip every
 * directory called {@code build}" also skips {@code com/microsoft/agentframework/build/harness},
 * which is why {@code .gitignore} pins project output with {@code /build/}, {@code /*}{@code
 * /build/}, and {@code /*}{@code /*}{@code /build/} instead of a bare {@code build/}. A {@code
 * build}, {@code bin}, or {@code out} path segment inside an owned location is scanned like any
 * other segment.
 *
 * <p>The scan reads the filesystem rather than calling {@code git ls-files} so the policy runs in a
 * test JVM with no process dependency.
 */
final class MarkdownDocuments {

  /** The only Markdown file allowed to contain Korean text. */
  static final String KOREAN_COMPANION = "docs/ko/README.md";

  /** The English documentation index every directory index links back to. */
  static final String DOCUMENTATION_INDEX = "docs/README.md";

  /**
   * Root Markdown the repository owns: the product overview, the canonical instructions, the
   * contribution and security contracts, and the vendor instruction adapters.
   */
  private static final List<String> OWNED_ROOT_DOCUMENTS =
      List.of("README.md", "AGENTS.md", "CONTRIBUTING.md", "SECURITY.md", "CLAUDE.md", "GEMINI.md");

  /** GitHub metadata Markdown the repository owns: direct children only, as section 8.1 states. */
  private static final String OWNED_GITHUB_DIRECTORY = ".github";

  /** The documentation tree the repository owns in full. */
  private static final String OWNED_DOCUMENTATION_TREE = "docs";

  private static final String MARKDOWN_SUFFIX = ".md";

  private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");

  private static final Pattern FENCE = Pattern.compile("^\\s{0,3}(```|~~~)");

  private static final Pattern INLINE_LINK =
      Pattern.compile("\\[[^\\]]*\\]\\(\\s*<?([^)>\\s]+)>?(?:\\s+\"[^\"]*\")?\\s*\\)");

  private static final Pattern LINK_LABEL = Pattern.compile("\\[([^\\]]*)\\]\\([^)]*\\)");

  private static final Pattern ABSOLUTE_SCHEME = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*:");

  private MarkdownDocuments() {}

  /** A Markdown link, kept with its source position so a failure names the line to fix. */
  record Link(Path source, int line, String target) {

    String describe() {
      return relativePath(source) + ":" + line + " -> " + target;
    }
  }

  /**
   * Returns every repository-owned Markdown file, sorted by repository-relative path.
   *
   * @return the Markdown files this policy governs
   * @throws IOException when a canonical location cannot be read
   */
  static List<Path> files() throws IOException {
    return filesUnder(RepositoryPaths.root());
  }

  /**
   * Returns the Markdown files the canonical locations hold under a scan root.
   *
   * @param root the repository root, or an equivalent tree in a test
   * @return the owned Markdown files, sorted by root-relative path
   * @throws IOException when a canonical location cannot be read
   */
  static List<Path> filesUnder(Path root) throws IOException {
    List<Path> markdown = new ArrayList<>();
    for (String name : OWNED_ROOT_DOCUMENTS) {
      Path document = root.resolve(name);
      if (Files.isRegularFile(document)) {
        markdown.add(document);
      }
    }
    Path github = root.resolve(OWNED_GITHUB_DIRECTORY);
    if (Files.isDirectory(github)) {
      try (Stream<Path> children = Files.list(github)) {
        children.filter(MarkdownDocuments::isMarkdownFile).forEach(markdown::add);
      }
    }
    Path documentation = root.resolve(OWNED_DOCUMENTATION_TREE);
    if (Files.isDirectory(documentation)) {
      try (Stream<Path> tree = Files.walk(documentation)) {
        tree.filter(MarkdownDocuments::isMarkdownFile).forEach(markdown::add);
      }
    }
    markdown.sort(Comparator.comparing((Path file) -> relativePath(root, file)));
    return List.copyOf(markdown);
  }

  private static boolean isMarkdownFile(Path file) {
    Path name = file.getFileName();
    return name != null && name.toString().endsWith(MARKDOWN_SUFFIX) && Files.isRegularFile(file);
  }

  /**
   * Returns a repository-relative path with {@code /} separators.
   *
   * @param file any path inside the repository
   * @return the relative path
   */
  static String relativePath(Path file) {
    return relativePath(RepositoryPaths.root(), file);
  }

  /**
   * Returns a path relative to a scan root, with {@code /} separators.
   *
   * @param root the scan root
   * @param file any path inside that root
   * @return the relative path
   */
  static String relativePath(Path root, Path file) {
    return root.relativize(file).toString().replace('\\', '/');
  }

  /**
   * Reports whether text contains a character written in the Hangul script.
   *
   * @param text the text to inspect
   * @return {@code true} when at least one Hangul code point is present
   */
  static boolean containsHangul(String text) {
    return text.codePoints()
        .anyMatch(
            codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HANGUL);
  }

  /**
   * Returns every {@code path:line} in a document that still contains Hangul.
   *
   * @param file the document to inspect
   * @return the offending positions, empty when the document is free of Hangul
   * @throws IOException when the document cannot be read
   */
  static List<String> hangulLines(Path file) throws IOException {
    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    List<String> found = new ArrayList<>();
    for (int index = 0; index < lines.size(); index++) {
      if (containsHangul(lines.get(index))) {
        found.add(relativePath(file) + ":" + (index + 1));
      }
    }
    return List.copyOf(found);
  }

  /**
   * Returns every inline link outside fenced blocks.
   *
   * @param file the document to inspect
   * @return the links, in document order
   * @throws IOException when the document cannot be read
   */
  static List<Link> links(Path file) throws IOException {
    List<Link> links = new ArrayList<>();
    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    boolean insideFence = false;
    for (int index = 0; index < lines.size(); index++) {
      String line = lines.get(index);
      if (FENCE.matcher(line).find()) {
        insideFence = !insideFence;
        continue;
      }
      if (insideFence) {
        continue;
      }
      Matcher matcher = INLINE_LINK.matcher(line);
      while (matcher.find()) {
        links.add(new Link(file, index + 1, matcher.group(1)));
      }
    }
    return List.copyOf(links);
  }

  /**
   * Reports whether a link target names something inside this repository.
   *
   * @param target the raw link target
   * @return {@code false} for absolute URLs, protocol-relative URLs, and empty targets
   */
  static boolean isLocalTarget(String target) {
    if (target.isEmpty() || target.startsWith("//")) {
      return false;
    }
    return !ABSOLUTE_SCHEME.matcher(target).find();
  }

  /**
   * Returns the file part of a link target.
   *
   * @param target the raw link target
   * @return the part before {@code #}, empty for a same-document fragment
   */
  static String filePartOf(String target) {
    int hash = target.indexOf('#');
    return hash < 0 ? target : target.substring(0, hash);
  }

  /**
   * Returns the fragment of a link target.
   *
   * @param target the raw link target
   * @return the part after {@code #}, empty when there is none
   */
  static String fragmentOf(String target) {
    int hash = target.indexOf('#');
    return hash < 0 ? "" : target.substring(hash + 1);
  }

  /**
   * Resolves the file a link points at.
   *
   * @param source the document containing the link
   * @param target the raw link target
   * @return the resolved path, or empty when the target escapes the repository root
   */
  static Optional<Path> resolveTarget(Path source, String target) {
    Path root = RepositoryPaths.root();
    Path parent = source.getParent();
    Path base = parent == null ? root : parent;
    String filePart = filePartOf(target);
    Path resolved = (filePart.isEmpty() ? source : base.resolve(filePart)).normalize();
    return resolved.startsWith(root) ? Optional.of(resolved) : Optional.empty();
  }

  /**
   * Returns every anchor a Markdown document exposes, in document order.
   *
   * @param file the document to inspect
   * @return the generated anchors
   * @throws IOException when the document cannot be read
   */
  static Set<String> anchors(Path file) throws IOException {
    Set<String> anchors = new LinkedHashSet<>();
    Map<String, Integer> seen = new HashMap<>();
    boolean insideFence = false;
    for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
      if (FENCE.matcher(line).find()) {
        insideFence = !insideFence;
        continue;
      }
      if (insideFence) {
        continue;
      }
      Matcher heading = HEADING.matcher(line);
      if (!heading.matches()) {
        continue;
      }
      String anchor = anchorOf(heading.group(2));
      int repeat = seen.merge(anchor, 1, Integer::sum) - 1;
      anchors.add(repeat == 0 ? anchor : anchor + "-" + repeat);
    }
    return anchors;
  }

  /**
   * Renders heading text the way GitHub renders an anchor: link label only, lower case, every
   * character that is not a letter, digit, hyphen, or underscore dropped, spaces turned into
   * hyphens.
   *
   * @param headingText the heading text after the leading hashes
   * @return the anchor
   */
  static String anchorOf(String headingText) {
    String text = LINK_LABEL.matcher(headingText.trim()).replaceAll("$1");
    StringBuilder anchor = new StringBuilder(text.length());
    for (int index = 0; index < text.length(); index++) {
      char character = text.charAt(index);
      if (character == ' ') {
        anchor.append('-');
      } else if (Character.isLetterOrDigit(character) || character == '-' || character == '_') {
        anchor.append(Character.toLowerCase(character));
      }
    }
    return anchor.toString();
  }
}
```

- [ ] **Step 2: Write the failing language, companion, and navigation policy test**

Create `build-tools/harness-policy/src/test/java/com/microsoft/agentframework/build/harness/DocumentationLanguagePolicyTest.java`. `ORIGINAL_PENDING_TRANSLATION` and `PENDING_TRANSLATION` below both list exactly the 52 documents Tasks 2 through 6 translate; copy them verbatim. The two sets start identical and then diverge: only `PENDING_TRANSLATION` shrinks, while `ORIGINAL_PENDING_TRANSLATION` stays frozen as the evidence of what the ratchet was installed with. Pinning the size alone would accept a one-for-one swap that translates one document and exempts a different one, so membership is pinned too.

```java
package com.microsoft.agentframework.build.harness;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class DocumentationLanguagePolicyTest {

  /**
   * The membership the migration declared when the ratchet was installed. It is frozen: no task
   * ever adds to it, edits it, or reorders it, and only the final task deletes it together with
   * {@link #PENDING_TRANSLATION}. Pinning membership instead of a count alone is what makes {@link
   * #pendingTranslationListOnlyHoldsOriginallyDeclaredDocuments()} reject a one-for-one swap that
   * translates one document and exempts a different one at the same size.
   */
  private static final Set<String> ORIGINAL_PENDING_TRANSLATION =
      Set.of(
          "docs/design/engineering-harness-design.md",
          "docs/design/foundation-design.md",
          "docs/design/gradle-kotlin-arc-foundation-design.md",
          "docs/requirements/README.md",
          "docs/requirements/01-agent-execution.md",
          "docs/requirements/02-message-content.md",
          "docs/requirements/03-structured-output.md",
          "docs/requirements/04-tools.md",
          "docs/requirements/05-mcp.md",
          "docs/requirements/06-sessions.md",
          "docs/requirements/07-interceptors.md",
          "docs/requirements/08-harness.md",
          "docs/requirements/09-workflows.md",
          "docs/requirements/10-hosting.md",
          "docs/requirements/11-operations.md",
          "docs/requirements/12-providers.md",
          "docs/upstream/README.md",
          "docs/upstream/snapshots/d0a4165f/README.md",
          "docs/upstream/snapshots/d0a4165f/compatibility-matrix.md",
          "docs/upstream/snapshots/d0a4165f/coverage-ledger.md",
          "docs/upstream/snapshots/d0a4165f/snapshot-manifest.md",
          "docs/upstream/snapshots/d0a4165f/features/01-agent-lifecycle.md",
          "docs/upstream/snapshots/d0a4165f/features/02-message-content.md",
          "docs/upstream/snapshots/d0a4165f/features/03-model-execution.md",
          "docs/upstream/snapshots/d0a4165f/features/04-structured-output.md",
          "docs/upstream/snapshots/d0a4165f/features/05-function-tools.md",
          "docs/upstream/snapshots/d0a4165f/features/06-tool-approval.md",
          "docs/upstream/snapshots/d0a4165f/features/07-mcp-client-tools.md",
          "docs/upstream/snapshots/d0a4165f/features/08-sessions.md",
          "docs/upstream/snapshots/d0a4165f/features/09-history-context-memory.md",
          "docs/upstream/snapshots/d0a4165f/features/10-middleware.md",
          "docs/upstream/snapshots/d0a4165f/features/11-compaction.md",
          "docs/upstream/snapshots/d0a4165f/features/12-harness.md",
          "docs/upstream/snapshots/d0a4165f/features/13-skills-background-code.md",
          "docs/upstream/snapshots/d0a4165f/features/14-workflow-graph.md",
          "docs/upstream/snapshots/d0a4165f/features/15-workflow-runtime.md",
          "docs/upstream/snapshots/d0a4165f/features/16-workflow-checkpoint-hitl.md",
          "docs/upstream/snapshots/d0a4165f/features/17-workflow-composition.md",
          "docs/upstream/snapshots/d0a4165f/features/18-orchestrations.md",
          "docs/upstream/snapshots/d0a4165f/features/19-declarative.md",
          "docs/upstream/snapshots/d0a4165f/features/20-hosting.md",
          "docs/upstream/snapshots/d0a4165f/features/21-openai-responses-hosting.md",
          "docs/upstream/snapshots/d0a4165f/features/22-a2a.md",
          "docs/upstream/snapshots/d0a4165f/features/23-ag-ui.md",
          "docs/upstream/snapshots/d0a4165f/features/24-mcp-hosting.md",
          "docs/upstream/snapshots/d0a4165f/features/25-foundry-devui-channels.md",
          "docs/upstream/snapshots/d0a4165f/features/26-identity-session-routing.md",
          "docs/upstream/snapshots/d0a4165f/features/27-observability.md",
          "docs/upstream/snapshots/d0a4165f/features/28-errors-resilience-security.md",
          "docs/upstream/snapshots/d0a4165f/features/29-evaluation-testing.md",
          "docs/upstream/snapshots/d0a4165f/features/30-packaging-compatibility.md",
          "docs/upstream/snapshots/d0a4165f/features/31-provider-integrations.md");

  /** The size {@link #ORIGINAL_PENDING_TRANSLATION} was declared with. */
  private static final int ORIGINAL_PENDING_TRANSLATION_SIZE = 52;

  /**
   * The number of documents still awaiting translation. A translating task lowers this number in
   * the same commit that removes the entries, so an entry added to {@link #PENDING_TRANSLATION}
   * fails {@link #pendingTranslationListHasNotWidened()} instead of quietly exempting one more
   * document from the language scan.
   */
  private static final int PENDING_TRANSLATION_SIZE = 52;

  /**
   * Documents that have not been translated yet. Every entry is removed by the task that translates
   * it, and {@link #pendingTranslationEntryStillContainsKoreanText(String)} fails once an entry no
   * longer needs migration, so this list can only shrink, and only towards {@link
   * #ORIGINAL_PENDING_TRANSLATION}. It is deleted entirely by the final task.
   */
  private static final Set<String> PENDING_TRANSLATION =
      Set.of(
          "docs/design/engineering-harness-design.md",
          "docs/design/foundation-design.md",
          "docs/design/gradle-kotlin-arc-foundation-design.md",
          "docs/requirements/README.md",
          "docs/requirements/01-agent-execution.md",
          "docs/requirements/02-message-content.md",
          "docs/requirements/03-structured-output.md",
          "docs/requirements/04-tools.md",
          "docs/requirements/05-mcp.md",
          "docs/requirements/06-sessions.md",
          "docs/requirements/07-interceptors.md",
          "docs/requirements/08-harness.md",
          "docs/requirements/09-workflows.md",
          "docs/requirements/10-hosting.md",
          "docs/requirements/11-operations.md",
          "docs/requirements/12-providers.md",
          "docs/upstream/README.md",
          "docs/upstream/snapshots/d0a4165f/README.md",
          "docs/upstream/snapshots/d0a4165f/compatibility-matrix.md",
          "docs/upstream/snapshots/d0a4165f/coverage-ledger.md",
          "docs/upstream/snapshots/d0a4165f/snapshot-manifest.md",
          "docs/upstream/snapshots/d0a4165f/features/01-agent-lifecycle.md",
          "docs/upstream/snapshots/d0a4165f/features/02-message-content.md",
          "docs/upstream/snapshots/d0a4165f/features/03-model-execution.md",
          "docs/upstream/snapshots/d0a4165f/features/04-structured-output.md",
          "docs/upstream/snapshots/d0a4165f/features/05-function-tools.md",
          "docs/upstream/snapshots/d0a4165f/features/06-tool-approval.md",
          "docs/upstream/snapshots/d0a4165f/features/07-mcp-client-tools.md",
          "docs/upstream/snapshots/d0a4165f/features/08-sessions.md",
          "docs/upstream/snapshots/d0a4165f/features/09-history-context-memory.md",
          "docs/upstream/snapshots/d0a4165f/features/10-middleware.md",
          "docs/upstream/snapshots/d0a4165f/features/11-compaction.md",
          "docs/upstream/snapshots/d0a4165f/features/12-harness.md",
          "docs/upstream/snapshots/d0a4165f/features/13-skills-background-code.md",
          "docs/upstream/snapshots/d0a4165f/features/14-workflow-graph.md",
          "docs/upstream/snapshots/d0a4165f/features/15-workflow-runtime.md",
          "docs/upstream/snapshots/d0a4165f/features/16-workflow-checkpoint-hitl.md",
          "docs/upstream/snapshots/d0a4165f/features/17-workflow-composition.md",
          "docs/upstream/snapshots/d0a4165f/features/18-orchestrations.md",
          "docs/upstream/snapshots/d0a4165f/features/19-declarative.md",
          "docs/upstream/snapshots/d0a4165f/features/20-hosting.md",
          "docs/upstream/snapshots/d0a4165f/features/21-openai-responses-hosting.md",
          "docs/upstream/snapshots/d0a4165f/features/22-a2a.md",
          "docs/upstream/snapshots/d0a4165f/features/23-ag-ui.md",
          "docs/upstream/snapshots/d0a4165f/features/24-mcp-hosting.md",
          "docs/upstream/snapshots/d0a4165f/features/25-foundry-devui-channels.md",
          "docs/upstream/snapshots/d0a4165f/features/26-identity-session-routing.md",
          "docs/upstream/snapshots/d0a4165f/features/27-observability.md",
          "docs/upstream/snapshots/d0a4165f/features/28-errors-resilience-security.md",
          "docs/upstream/snapshots/d0a4165f/features/29-evaluation-testing.md",
          "docs/upstream/snapshots/d0a4165f/features/30-packaging-compatibility.md",
          "docs/upstream/snapshots/d0a4165f/features/31-provider-integrations.md");

  private static final List<String> DIRECTORY_INDEXES =
      List.of(
          "docs/requirements/README.md",
          "docs/upstream/README.md",
          MarkdownDocuments.KOREAN_COMPANION);

  static Stream<String> canonicalDocuments() {
    return documentPaths().stream()
        .filter(path -> !MarkdownDocuments.KOREAN_COMPANION.equals(path))
        .filter(path -> !PENDING_TRANSLATION.contains(path));
  }

  static Stream<String> pendingTranslation() {
    return PENDING_TRANSLATION.stream().sorted();
  }

  static Stream<String> directoryIndexes() {
    return DIRECTORY_INDEXES.stream();
  }

  @ParameterizedTest(name = "{0} is written in English")
  @MethodSource("canonicalDocuments")
  void canonicalDocumentContainsNoKoreanText(String relativePath) throws IOException {
    List<String> korean =
        MarkdownDocuments.hangulLines(RepositoryPaths.root().resolve(relativePath));

    assertThat(korean)
        .withFailMessage(
            "%s is a canonical English document. Korean text remains at %s.", relativePath, korean)
        .isEmpty();
  }

  @ParameterizedTest(name = "{0} still awaits translation")
  @MethodSource("pendingTranslation")
  void pendingTranslationEntryStillContainsKoreanText(String relativePath) throws IOException {
    Path document = RepositoryPaths.root().resolve(relativePath);

    assertThat(document).isRegularFile();
    assertThat(MarkdownDocuments.containsHangul(Files.readString(document, StandardCharsets.UTF_8)))
        .withFailMessage(
            "%s no longer contains Korean text. Remove it from PENDING_TRANSLATION so the language"
                + " scan covers it.",
            relativePath)
        .isTrue();
  }

  @Test
  void pendingTranslationListHasNotWidened() {
    assertThat(PENDING_TRANSLATION)
        .withFailMessage(
            "The migration list must hold exactly %d entries but holds %d. The list only shrinks:"
                + " remove an entry and lower PENDING_TRANSLATION_SIZE in the commit that translates"
                + " the document, and never add a document to it.",
            PENDING_TRANSLATION_SIZE, PENDING_TRANSLATION.size())
        .hasSize(PENDING_TRANSLATION_SIZE);
  }

  @Test
  void pendingTranslationListOnlyHoldsOriginallyDeclaredDocuments() {
    List<String> undeclared =
        PENDING_TRANSLATION.stream()
            .filter(path -> !ORIGINAL_PENDING_TRANSLATION.contains(path))
            .sorted()
            .toList();

    assertThat(ORIGINAL_PENDING_TRANSLATION)
        .withFailMessage(
            "The migration list may only hold documents the ratchet was installed with. A size"
                + " check alone accepts a one-for-one swap that translates one document and exempts"
                + " another, so membership is pinned too. Entries that were never declared: %s.",
            undeclared)
        .containsAll(PENDING_TRANSLATION);
  }

  @Test
  void originalPendingTranslationListIsTheFrozenBaseline() {
    assertThat(ORIGINAL_PENDING_TRANSLATION)
        .withFailMessage(
            "The declared baseline is frozen at %d entries but holds %d. Shrink"
                + " PENDING_TRANSLATION instead; ORIGINAL_PENDING_TRANSLATION is the evidence that"
                + " the list never widened.",
            ORIGINAL_PENDING_TRANSLATION_SIZE, ORIGINAL_PENDING_TRANSLATION.size())
        .hasSize(ORIGINAL_PENDING_TRANSLATION_SIZE);
    assertThat(PENDING_TRANSLATION_SIZE)
        .withFailMessage(
            "The migration list ratchets down from %d, so a target size above the declared baseline"
                + " can only come from widening it.",
            ORIGINAL_PENDING_TRANSLATION_SIZE)
        .isBetween(0, ORIGINAL_PENDING_TRANSLATION_SIZE);
  }

  @Test
  void everyPendingTranslationEntryIsAScannedDocument() {
    assertThat(documentPaths())
        .withFailMessage(
            "Every migration entry must name a document the scan covers, otherwise an entry can"
                + " exempt a path that no policy reads. Scanned documents: %s. Migration list: %s.",
            documentPaths(), PENDING_TRANSLATION)
        .containsAll(PENDING_TRANSLATION);
  }

  @Test
  void documentationIndexExistsAndIsScanned() {
    assertThat(RepositoryPaths.root().resolve(MarkdownDocuments.DOCUMENTATION_INDEX))
        .as("The English documentation index every directory index links back to")
        .isRegularFile();
    assertThat(documentPaths()).contains(MarkdownDocuments.DOCUMENTATION_INDEX);
  }

  @Test
  void koreanCompanionExistsAndIsKorean() throws IOException {
    Path companion = RepositoryPaths.root().resolve(MarkdownDocuments.KOREAN_COMPANION);

    assertThat(companion).isRegularFile();
    assertThat(
            MarkdownDocuments.containsHangul(Files.readString(companion, StandardCharsets.UTF_8)))
        .isTrue();
  }

  @Test
  void koreanCompanionIsTheOnlyDocumentUnderDocsKo() {
    List<String> underDocsKo =
        documentPaths().stream().filter(path -> path.startsWith("docs/ko/")).toList();

    assertThat(underDocsKo)
        .withFailMessage(
            "docs/ko must hold exactly one companion document, but holds %s. A second Korean"
                + " document creates a mirror tree that drifts from the English contracts.",
            underDocsKo)
        .containsExactly(MarkdownDocuments.KOREAN_COMPANION);
  }

  @Test
  void koreanCompanionDeclaresEnglishAsAuthoritative() throws IOException {
    String companion = read(MarkdownDocuments.KOREAN_COMPANION);

    assertThat(companion).contains("English documents are authoritative");
    assertThat(companion).contains("](../../README.md)");
    assertThat(companion).contains("](../README.md)");
  }

  @Test
  void englishEntryPointsLinkToTheKoreanCompanion() throws IOException {
    assertThat(read("README.md")).contains("](docs/ko/README.md)");
    assertThat(read(MarkdownDocuments.DOCUMENTATION_INDEX)).contains("](ko/README.md)");
  }

  @Test
  void rootReadmeLinksTheDocumentationIndex() throws IOException {
    assertThat(read("README.md")).contains("](docs/README.md)");
  }

  @ParameterizedTest(name = "{0} links back to the documentation index")
  @MethodSource("directoryIndexes")
  void directoryIndexLinksBackToTheDocumentationIndex(String relativePath) throws IOException {
    Path root = RepositoryPaths.root();
    Path parent =
        Objects.requireNonNull(
            root.resolve(relativePath).getParent(),
            "A directory index always lives inside a directory.");
    String backlink =
        "](" + parent.relativize(root.resolve(MarkdownDocuments.DOCUMENTATION_INDEX)) + ")";

    assertThat(read(relativePath)).contains(backlink.replace('\\', '/'));
  }

  private static List<String> documentPaths() {
    try {
      return MarkdownDocuments.files().stream().map(MarkdownDocuments::relativePath).toList();
    } catch (IOException cause) {
      throw new UncheckedIOException("Cannot scan repository Markdown.", cause);
    }
  }

  private static String read(String relativePath) throws IOException {
    return Files.readString(RepositoryPaths.root().resolve(relativePath), StandardCharsets.UTF_8);
  }
}
```

- [ ] **Step 3: Write the failing link integrity test**

Create `build-tools/harness-policy/src/test/java/com/microsoft/agentframework/build/harness/MarkdownLinkPolicyTest.java`:

```java
package com.microsoft.agentframework.build.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class MarkdownLinkPolicyTest {

  static Stream<String> documents() {
    try {
      return MarkdownDocuments.files().stream().map(MarkdownDocuments::relativePath);
    } catch (IOException cause) {
      throw new UncheckedIOException("Cannot scan repository Markdown.", cause);
    }
  }

  @ParameterizedTest(name = "{0} resolves every local link")
  @MethodSource("documents")
  void localLinksResolve(String relativePath) throws IOException {
    Path document = RepositoryPaths.root().resolve(relativePath);
    List<String> unresolved = new ArrayList<>();

    for (MarkdownDocuments.Link link : MarkdownDocuments.links(document)) {
      if (!MarkdownDocuments.isLocalTarget(link.target())) {
        continue;
      }
      Optional<Path> resolved = MarkdownDocuments.resolveTarget(document, link.target());
      if (resolved.isEmpty()) {
        unresolved.add(link.describe() + " (escapes the repository root)");
        continue;
      }
      Path file = resolved.get();
      if (!Files.exists(file)) {
        unresolved.add(link.describe() + " (no such file)");
        continue;
      }
      String fragment = MarkdownDocuments.fragmentOf(link.target());
      if (fragment.isEmpty() || !file.toString().endsWith(".md")) {
        continue;
      }
      if (!MarkdownDocuments.anchors(file).contains(fragment)) {
        unresolved.add(link.describe() + " (no such heading anchor)");
      }
    }

    assertThat(unresolved)
        .withFailMessage("%s has unresolved links: %s", relativePath, unresolved)
        .isEmpty();
  }

  @Test
  void linkResolutionRejectsTargetsOutsideTheRepository() {
    Path source = RepositoryPaths.root().resolve("docs/README.md");

    assertThat(MarkdownDocuments.resolveTarget(source, "../../etc/passwd")).isEmpty();
    assertThat(MarkdownDocuments.resolveTarget(source, "requirements/README.md")).isPresent();
  }

  @Test
  void absoluteAndProtocolRelativeUrlsAreNotTreatedAsFiles() {
    assertThat(MarkdownDocuments.isLocalTarget("https://github.com/microsoft/agent-framework"))
        .isFalse();
    assertThat(MarkdownDocuments.isLocalTarget("mailto:security@example.com")).isFalse();
    assertThat(MarkdownDocuments.isLocalTarget("//cdn.example.com/x.png")).isFalse();
    assertThat(MarkdownDocuments.isLocalTarget("../requirements/README.md")).isTrue();
    assertThat(MarkdownDocuments.isLocalTarget("#current-state")).isTrue();
  }

  @Test
  void headingAnchorsFollowGitHubRules() {
    assertThat(MarkdownDocuments.anchorOf("Requirement grades")).isEqualTo("requirement-grades");
    assertThat(MarkdownDocuments.anchorOf("AGT-001 A single public `Agent` entry point"))
        .isEqualTo("agt-001-a-single-public-agent-entry-point");
    assertThat(MarkdownDocuments.anchorOf("13. Skills, background work, and code execution"))
        .isEqualTo("13-skills-background-work-and-code-execution");
  }

  @Test
  void anchorsIgnoreFencedBlocksAndNumberRepeatedHeadings(@TempDir Path directory)
      throws IOException {
    Path document = directory.resolve("sample.md");
    Files.writeString(
        document,
        String.join(
            "\n", "# Title", "```text", "# Not a heading", "```", "## Notes", "## Notes", ""),
        StandardCharsets.UTF_8);

    assertThat(MarkdownDocuments.anchors(document)).containsExactly("title", "notes", "notes-1");
  }

  @Test
  void linkExtractionReadsEveryLinkOutsideFencedBlocks(@TempDir Path directory) throws IOException {
    Path document = directory.resolve("sample.md");
    Files.writeString(
        document,
        String.join(
            "\n",
            "[index](../README.md) and [site](https://example.com).",
            "```bash",
            "[fenced](never-scanned.md)",
            "```",
            "| [table](docs/README.md) | [anchor](#current-state) |",
            ""),
        StandardCharsets.UTF_8);

    assertThat(MarkdownDocuments.links(document))
        .extracting(MarkdownDocuments.Link::line, MarkdownDocuments.Link::target)
        .containsExactly(
            tuple(1, "../README.md"),
            tuple(1, "https://example.com"),
            tuple(5, "docs/README.md"),
            tuple(5, "#current-state"));
  }
}
```

- [ ] **Step 3b: Write the failing scanner ownership test**

`files()` decides which documents every other policy reads, so its scope needs a direct test.
Create
`build-tools/harness-policy/src/test/java/com/microsoft/agentframework/build/harness/MarkdownDocumentsTest.java`
with `@TempDir` cases that build a fake repository and assert `MarkdownDocuments.filesUnder(root)`
returns exactly the canonical locations of policy section 8.1:

- the six owned root documents, `.github/*.md`, and `docs/**/*.md` are returned, while
  `LICENSE.md`, `.github/ISSUE_TEMPLATE/bug.md`, and Markdown inside a module are not;
- untracked Markdown outside the owned locations — a root scratch file, a `notes/` directory,
  `.copilot/`, `.superpowers/`, `.worktrees/`, and `.harness/runs/` — is ignored;
- Markdown under a `build`, `bin`, or `out` path segment **inside** `docs/` is returned, which is
  the case a name-based directory exclusion silently drops;
- generated project output (`build/`, `*/*/build/`, `node_modules/`) is out of scope because it is
  not an owned location;
- the result is sorted by root-relative path;
- and, against the real repository, every scanned path is a root document, a direct `.github`
  child, or a file under `docs/`.

- [ ] **Step 4: Run the new tests and confirm they fail**

Run: `./gradlew :build-tools:harness-policy:test --tests '*DocumentationLanguagePolicyTest*' --tests '*MarkdownLinkPolicyTest*' --tests '*MarkdownDocumentsTest*'`

Expected: FAIL, with exactly ten failing cases, because `docs/README.md` does not exist yet, the
entry points do not link the index or the companion, and two documents still hold Korean text:

| # | Test | Failing case | Why |
| ---: | --- | --- | --- |
| 1 | `DocumentationLanguagePolicyTest.canonicalDocumentContainsNoKoreanText` | `README.md` | the companion link label on `README.md:141` is still written in Korean |
| 2 | `DocumentationLanguagePolicyTest.canonicalDocumentContainsNoKoreanText` | `docs/operations/getting-started.md` | the requirement grade value is still written in Korean |
| 3 | `DocumentationLanguagePolicyTest.documentationIndexExistsAndIsScanned` | — | `docs/README.md` does not exist |
| 4 | `DocumentationLanguagePolicyTest.koreanCompanionDeclaresEnglishAsAuthoritative` | — | the companion has no authoritative-source marker and no `](../README.md)` |
| 5 | `DocumentationLanguagePolicyTest.englishEntryPointsLinkToTheKoreanCompanion` | — | `NoSuchFileException` for `docs/README.md` |
| 6 | `DocumentationLanguagePolicyTest.rootReadmeLinksTheDocumentationIndex` | — | `README.md` has no `](docs/README.md)` |
| 7 | `DocumentationLanguagePolicyTest.directoryIndexLinksBackToTheDocumentationIndex` | `docs/requirements/README.md` | no backlink yet |
| 8 | `DocumentationLanguagePolicyTest.directoryIndexLinksBackToTheDocumentationIndex` | `docs/upstream/README.md` | no backlink yet |
| 9 | `DocumentationLanguagePolicyTest.directoryIndexLinksBackToTheDocumentationIndex` | `docs/ko/README.md` | no backlink yet |
| 10 | `MarkdownDocumentsTest.repositoryScanReturnsOwnedLocationsOnly` | — | the scan cannot return `docs/README.md` |

`MarkdownLinkPolicyTest` passes at this point: no document links to `docs/README.md` yet.

- [ ] **Step 5: Create the English documentation index**

Create `docs/README.md` with exactly this content:

```markdown
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
```

- [ ] **Step 6: Point the English entry points at the index and the companion**

In `README.md`, replace the `## Translations` section body so the companion link carries an English
label, and add the documentation index to the documentation table.

Replace:

```markdown
| How is documentation organized? | [Documentation language and information architecture](docs/design/documentation-language-policy.md) |
```

with:

```markdown
| Where is every document listed? | [Documentation index](docs/README.md) |
| How is documentation organized? | [Documentation language and information architecture](docs/design/documentation-language-policy.md) |
```

Replace the whole `## Translations` body — the bullet and the sentence under it — with:

```markdown
- [Korean companion guide](docs/ko/README.md)

English is the source of truth. The Korean companion guide is a single orientation document, not a
translation of the English documentation; when the two disagree, the English document wins.
```

The previous sentence, "English is the source of truth. Translations follow.", promised translations
that section 11 of the policy lists as a non-goal.

- [ ] **Step 7: Remove the last Korean value from the operations guide**

In `docs/operations/getting-started.md`, the first bullet of the "Pick your first change" list grades
the example requirement with the Korean word for the strongest grade. Replace that bullet with:

```markdown
- graded **Required** so the decision is settled,
```

- [ ] **Step 8: Add the documentation-index backlinks**

- In `docs/requirements/README.md`, append a bullet to the closing related-documents list whose target is `../README.md`. Keep the label in the file's current language so the document stays internally consistent until Task 3 translates it.
- In `docs/upstream/README.md`, append a bullet to the document-set list whose target is `../README.md`, with the label in the file's current language.
- In `docs/ko/README.md`, add a bullet whose target is `../README.md` next to the existing `../../README.md` link, with a Korean label, and add the exact ASCII sentence `English documents are authoritative.` to the same section.

Verify the three targets with:

```bash
rg -n '\]\(\.\./README\.md\)' docs/requirements/README.md docs/upstream/README.md docs/ko/README.md
rg -n 'English documents are authoritative\.' docs/ko/README.md
```

Expected: one match in each of the four lines of output.

- [ ] **Step 9: Declare the policy input tree by location, not by name or depth**

The policy tasks read repository files, so the declared input tree decides whether an edit re-runs a
policy at all. Two hazards have to be excluded at once, and a single glob cannot do both:

- a rule that matches a `build` segment anywhere also removes
  `build-tools/harness-policy/src/test/java/com/microsoft/agentframework/build/harness`, where these
  policies live, so an edit to a policy source would drop out of the declared inputs;
- a depth-limited rule such as `*`/`build/**` and `*`/`*`/`build/**` still removes `docs/build/` and
  `docs/*`/`build/`, which the scanner deliberately owns, so a canonical document under a `build`
  path segment would leave every policy task UP-TO-DATE.

Build output is therefore removed by location: only a directory that actually is a Gradle project
root - it holds a `build.gradle.kts` or a `settings.gradle.kts` - loses its own `build` directory.
Untracked, generated, or agent-owned locations - `.git`, `.gradle`, `.kotlin`, `.gradle-bootstrap`,
`.superpowers`, `.worktrees`, and `.harness/runs` - are removed by name at any depth instead,
because a policy never reads from them and an untracked agent plugin directory must never invalidate
or feed a policy task. The rule is shared build behaviour, so it lives in the `build-logic` included
build and is never copied into a project script.

Create `build-logic/src/main/kotlin/com/microsoft/agentframework/build/logic/RepositoryPolicyInputs.kt`:

```kotlin
package com.microsoft.agentframework.build.logic

import java.io.File
import java.util.ArrayDeque
import org.gradle.api.Project
import org.gradle.api.file.FileTree

/**
 * Declares the repository files the policy tasks read.
 *
 * The policy tasks read repository files that Gradle cannot infer from a compile classpath. Without
 * declaring them, a workflow, instruction, contract, or documentation edit leaves every policy task
 * UP-TO-DATE and `check` reports success without re-running a single policy.
 *
 * Build output is removed by location, never by name and never by depth. A rule matching a `build`
 * segment anywhere also removes
 * `build-tools/harness-policy/src/test/java/com/microsoft/agentframework/build/harness`, where the
 * policies themselves live. A depth-limited rule still removes `docs/build/`, so a canonical
 * document under a `build` path segment would stop invalidating the policies. Only a directory that
 * is a Gradle project root - it holds a `build.gradle.kts` or a `settings.gradle.kts` - contributes
 * an exclusion, and only for its own `build` directory.
 */
object RepositoryPolicyInputs {

    /** Files that mark a directory as the root of a Gradle project or of an included build. */
    private val BUILD_SCRIPT_NAMES = setOf("build.gradle.kts", "settings.gradle.kts")

    /**
     * How deep a project root is searched for. `build-tools/harness-policy` is the deepest project
     * this repository declares, and `.gitignore` pins project output to the same three levels.
     */
    private const val MAXIMUM_PROJECT_DEPTH = 3

    /** Directories never descended into while looking for project roots. */
    private val UNSEARCHED_DIRECTORIES =
        setOf(
            "build",
            "node_modules",
            ".git",
            ".gradle",
            ".kotlin",
            ".gradle-bootstrap",
            ".superpowers",
            ".worktrees"
        )

    /**
     * Generated, vendored, or agent-owned locations the repository does not own as source. They are
     * matched at any depth because a policy never reads from them.
     */
    private val NON_SOURCE_EXCLUSIONS =
        listOf(
            "**/.git/**",
            "**/.gradle/**",
            "**/.kotlin/**",
            "**/.gradle-bootstrap/**",
            ".superpowers/**",
            ".worktrees/**",
            ".harness/runs/**"
        )

    /**
     * Returns the Ant patterns the policy input tree excludes, relative to the repository root.
     *
     * @param repositoryRoot the root of the repository the policies read
     * @return the build output of every Gradle project root, plus the non-source locations
     */
    fun excludePatterns(repositoryRoot: File): List<String> =
        projectOutputExclusions(repositoryRoot) + NON_SOURCE_EXCLUSIONS

    /**
     * Returns the repository files the policy tasks read.
     *
     * @param project the project that owns the policy tasks
     * @return the repository tree without project output and without non-source locations
     */
    fun repositoryPolicySources(project: Project): FileTree {
        val repositoryRoot = project.rootProject.layout.projectDirectory
        return repositoryRoot.asFileTree.matching { exclude(excludePatterns(repositoryRoot.asFile)) }
    }

    private fun projectOutputExclusions(repositoryRoot: File): List<String> {
        val exclusions = mutableListOf<String>()
        val queue = ArrayDeque<Pair<File, Int>>()
        queue.addLast(repositoryRoot to 0)
        while (queue.isNotEmpty()) {
            val (directory, depth) = queue.removeFirst()
            if (isProjectRoot(directory)) {
                exclusions.add(buildOutputPattern(repositoryRoot, directory))
            }
            if (depth == MAXIMUM_PROJECT_DEPTH) {
                continue
            }
            directory.listFiles()
                ?.filter { it.isDirectory && it.name !in UNSEARCHED_DIRECTORIES }
                ?.forEach { queue.addLast(it to depth + 1) }
        }
        return exclusions.sorted()
    }

    private fun isProjectRoot(directory: File): Boolean =
        BUILD_SCRIPT_NAMES.any { directory.resolve(it).isFile }

    private fun buildOutputPattern(repositoryRoot: File, projectRoot: File): String {
        val relative =
            repositoryRoot
                .toPath()
                .relativize(projectRoot.toPath())
                .joinToString("/") { segment -> segment.toString() }
        return if (relative.isEmpty()) "build/**" else "$relative/build/**"
    }
}
```

Then, in `build-tools/harness-policy/build.gradle.kts`, replace the inline `matching { exclude(...) }`
tree with the shared declaration:

```kotlin
import com.microsoft.agentframework.build.logic.RepositoryPolicyInputs

// ...

val repositoryPolicySources = RepositoryPolicyInputs.repositoryPolicySources(project)
```

Create `build-logic/src/test/kotlin/com/microsoft/agentframework/build/logic/RepositoryPolicyInputsTest.kt`.
The unit cases pin which directories contribute an exclusion; the Gradle TestKit cases run a task
that declares the same tree twice and prove that a Markdown edit under `docs/build/` re-runs it
while a project-output edit does not:

```kotlin
package com.microsoft.agentframework.build.logic

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The repository policy tasks read repository files instead of a compile classpath, so the declared
 * input tree decides whether an edit re-runs a policy at all. Excluding build output by name or by
 * depth also drops documentation that legitimately lives under a `build` path segment, which turns
 * a documentation change into a silent UP-TO-DATE.
 */
class RepositoryPolicyInputsTest {

    @Test
    fun onlyGradleProjectRootsContributeABuildOutputExclusion(@TempDir root: File) {
        writeRepository(root)

        val patterns = RepositoryPolicyInputs.excludePatterns(root)

        assertThat(patterns)
            .contains("build/**", "module/build/**", "group/leaf/build/**")
            .doesNotContain(
                "docs/build/**",
                "group/build/**",
                "**/build/**",
                "*/build/**",
                "*/*/build/**"
            )
    }

    @Test
    fun untrackedAndGeneratedNoiseStaysOutsideTheInputTree(@TempDir root: File) {
        writeRepository(root)

        val patterns = RepositoryPolicyInputs.excludePatterns(root)

        assertThat(patterns)
            .contains(
                "**/.git/**",
                "**/.gradle/**",
                "**/.kotlin/**",
                "**/.gradle-bootstrap/**",
                ".superpowers/**",
                ".worktrees/**",
                ".harness/runs/**"
            )
    }

    @Test
    fun documentationUnderABuildPathSegmentInvalidatesThePolicyTask(@TempDir root: File) {
        writeRepository(root)
        val documentation = root.resolve("docs/build/reference.md")
        val projectOutput = root.resolve("module/build/generated.md")

        assertThat(runProbe(root).outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(runProbe(root).outcome).isEqualTo(TaskOutcome.UP_TO_DATE)

        documentation.writeText("# Reference\n\nA canonical document that changed.\n")

        assertThat(runProbe(root).outcome).isEqualTo(TaskOutcome.SUCCESS)

        projectOutput.writeText("# Generated\n\nProject output that changed.\n")

        assertThat(runProbe(root).outcome).isEqualTo(TaskOutcome.UP_TO_DATE)
    }

    @Test
    fun policySourcesUnderABuildPackageInvalidateThePolicyTask(@TempDir root: File) {
        writeRepository(root)
        val policySource = root.resolve("module/src/test/java/com/example/build/harness/Policy.java")

        assertThat(runProbe(root).outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(runProbe(root).outcome).isEqualTo(TaskOutcome.UP_TO_DATE)

        policySource.writeText("class Policy { int rules = 2; }\n")

        assertThat(runProbe(root).outcome).isEqualTo(TaskOutcome.SUCCESS)
    }

    private fun runProbe(root: File) =
        GradleRunner.create()
            .withProjectDir(root)
            .withArguments("policyProbe", "--stacktrace")
            .build()
            .task(":policyProbe")!!

    private fun writeRepository(root: File) {
        write(root, "settings.gradle.kts", "rootProject.name = \"fixture\"\ninclude(\":module\")\n")
        write(
            root,
            "build.gradle.kts",
            """
            buildscript {
                dependencies {
                    classpath(files("$BUILD_LOGIC_CLASSES"))
                }
            }

            val repositoryPolicySources =
                com.microsoft.agentframework.build.logic.RepositoryPolicyInputs
                    .repositoryPolicySources(project)
            val marker = layout.buildDirectory.file("policy-probe.txt")

            tasks.register("policyProbe") {
                inputs.files(repositoryPolicySources)
                    .withPropertyName("repositoryPolicySources")
                    .withPathSensitivity(PathSensitivity.RELATIVE)
                outputs.file(marker)
                doLast {
                    marker.get().asFile.writeText("ran")
                }
            }
            """.trimIndent() + "\n"
        )
        write(root, "module/build.gradle.kts", "")
        write(root, "group/leaf/build.gradle.kts", "")
        write(root, "docs/build/reference.md", "# Reference\n")
        write(root, "docs/README.md", "# Documentation\n")
        write(root, "module/build/generated.md", "# Generated\n")
        write(
            root,
            "module/src/test/java/com/example/build/harness/Policy.java",
            "class Policy {}\n"
        )
    }

    private fun write(root: File, relativePath: String, content: String) {
        val file = root.resolve(relativePath)
        file.parentFile.mkdirs()
        file.writeText(content)
    }

    private companion object {

        /**
         * Where this test loaded the helper from. The fixture build script puts exactly that on its
         * buildscript classpath, so the probe exercises the production code rather than a copy of
         * the patterns.
         */
        val BUILD_LOGIC_CLASSES: String =
            File(
                    RepositoryPolicyInputs::class
                        .java
                        .protectionDomain
                        .codeSource
                        .location
                        .toURI()
                )
                .invariantSeparatorsPath
    }
}
```

Run: `./gradlew buildLogicTest`

Expected: PASS. Replacing `RepositoryPolicyInputs.excludePatterns` with the depth-limited
`"build/**", "*/build/**", "*/*/build/**"` list fails
`onlyGradleProjectRootsContributeABuildOutputExclusion` and
`documentationUnderABuildPathSegmentInvalidatesThePolicyTask`, which is the regression this step
prevents.

`BuildContractPolicyTest.policyInputsExcludeProjectOutputByLocationOnly` keeps the project script
honest under `./gradlew policyCheck`: it fails if the script stops using the shared declaration or
reintroduces a name-matched or depth-matched build glob.

- [ ] **Step 10: Format and run the tests to verify they pass**

Run: `./gradlew :build-tools:harness-policy:spotlessApply`
Then run: `./gradlew :build-tools:harness-policy:test --tests '*DocumentationLanguagePolicyTest*' --tests '*MarkdownLinkPolicyTest*' --tests '*MarkdownDocumentsTest*'`

Expected: PASS. The language scan covers every Markdown file except `docs/ko/README.md` and the 52
`PENDING_TRANSLATION` entries; the link test reports no unresolved link in any of the 66 owned
documents (64 before this migration, plus this plan and `docs/README.md`); the scanner test proves
those 66 come from the canonical locations only.

- [ ] **Step 11: Confirm the policy entry point and the quality gate**

Run: `./gradlew policyCheck quality buildLogicTest`

Expected: BUILD SUCCESSFUL. `policyCheck` runs the new tests through `:build-tools:harness-policy:test`; `quality` proves the new sources satisfy spotless, Checkstyle, PMD, and SpotBugs; `buildLogicTest` runs the Gradle TestKit probe for the declared policy input tree.

Run: `git diff --stat build-tools/harness-policy/gradle.lockfile`

Expected: no output, because no dependency changed.

- [ ] **Step 12: Commit**

```bash
git add build-tools/harness-policy/src/test/java/com/microsoft/agentframework/build/harness/MarkdownDocuments.java \
  build-tools/harness-policy/src/test/java/com/microsoft/agentframework/build/harness/MarkdownDocumentsTest.java \
  build-tools/harness-policy/src/test/java/com/microsoft/agentframework/build/harness/DocumentationLanguagePolicyTest.java \
  build-tools/harness-policy/src/test/java/com/microsoft/agentframework/build/harness/MarkdownLinkPolicyTest.java \
  build-tools/harness-policy/src/test/java/com/microsoft/agentframework/build/harness/BuildContractPolicyTest.java \
  build-logic/src/main/kotlin/com/microsoft/agentframework/build/logic/RepositoryPolicyInputs.kt \
  build-logic/src/test/kotlin/com/microsoft/agentframework/build/logic/RepositoryPolicyInputsTest.kt \
  build-tools/harness-policy/build.gradle.kts docs/README.md README.md \
  docs/operations/getting-started.md docs/requirements/README.md docs/upstream/README.md docs/ko/README.md
git commit -m "docs: add the English documentation index and language policy tests" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 2: Translate the three Korean design documents

**Files:**
- Modify: `docs/design/foundation-design.md`
- Modify: `docs/design/engineering-harness-design.md`
- Modify: `docs/design/gradle-kotlin-arc-foundation-design.md`
- Modify: `build-tools/harness-policy/src/test/java/com/microsoft/agentframework/build/harness/DocumentationLanguagePolicyTest.java` (remove three `PENDING_TRANSLATION` entries)
- Test: `./gradlew :build-tools:harness-policy:test --tests '*DocumentationLanguagePolicyTest*' --tests '*MarkdownLinkPolicyTest*'`

**Interfaces:**
- Consumes: `DocumentationLanguagePolicyTest.PENDING_TRANSLATION`, `MarkdownDocuments.hangulLines(Path)` from Task 1.
- Produces: the English design vocabulary that Tasks 3 through 8 reuse — `Status`, `Date`, `Scope`, `Supersedes`, `Retains`, `Decision`, `Considered approaches`, `Non-goals` — matching the already-English exemplars `docs/design/documentation-language-policy.md` and `docs/design/module-composition.md`.

- [ ] **Step 1: Record the preservation baseline**

Paste the `doc_metrics` function from Global Constraints, then run:

```bash
for f in docs/design/foundation-design.md docs/design/engineering-harness-design.md docs/design/gradle-kotlin-arc-foundation-design.md; do
  printf '%-74s HEAD %s\n' "$f" "$(git show HEAD:"$f" | doc_metrics)"
done
```

Expected, and the values every later check must reproduce exactly:

| File | ids | urls | pin | fence | rows | seps | h2 | h3 | bullets |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `docs/design/foundation-design.md` | 0 | 7 | 0 | 4 | 9 | 1 | 11 | 18 | 81 |
| `docs/design/engineering-harness-design.md` | 0 | 32 | 2 | 2 | 36 | 4 | 15 | 45 | 276 |
| `docs/design/gradle-kotlin-arc-foundation-design.md` | 0 | 8 | 0 | 10 | 12 | 1 | 14 | 15 | 84 |

- [ ] **Step 2: Make the policy demand English for these three documents**

In `DocumentationLanguagePolicyTest.java`, delete these three lines from `PENDING_TRANSLATION`:

```java
          "docs/design/engineering-harness-design.md",
          "docs/design/foundation-design.md",
          "docs/design/gradle-kotlin-arc-foundation-design.md",
```

Lower `PENDING_TRANSLATION_SIZE` from `52` to `49` in the same edit, so
`pendingTranslationListHasNotWidened` keeps pinning the exact size of the list. Remove the entries from `PENDING_TRANSLATION` only; `ORIGINAL_PENDING_TRANSLATION` is frozen and is never edited, so `pendingTranslationListOnlyHoldsOriginallyDeclaredDocuments` keeps rejecting a document that was swapped in rather than translated.

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew :build-tools:harness-policy:test --tests '*DocumentationLanguagePolicyTest*'`

Expected: FAIL with three `canonicalDocumentContainsNoKoreanText` cases, each naming the file and the
Korean line numbers, for example `docs/design/foundation-design.md is a canonical English document.
Korean text remains at [docs/design/foundation-design.md:1, ...]`.

- [ ] **Step 4: Translate `docs/design/foundation-design.md`**

Translate every Korean sentence, heading, list item, and table cell into technical English. Rules for
this file:

- Translate the whole document. Do not summarize, drop, merge, or reorder a single paragraph, bullet, table row, or numbered decision.
- Render the metadata bullets at the top preserving the source label: `- Status: approved`, `- Approval date: 2026-08-10`, `- Scope: ...`.
- Keep the numbered section structure (`## 1. ...` through the last section) and the exact number of headings shown in Step 1.
- Keep every symbol, type, package, artifact, and command exactly as written: `AgentEngine`, `ChatModel`, `ChatClient`, `ToolCallback`, `ChatMemory`, `ToolCallingAdvisor`, `agent-framework-api`, `./gradlew check`, and every module and directory name.
- Keep all seven URLs and both fenced blocks pairs (four fence markers) byte-identical.
- Preserve the decision content: which approaches were considered, which one was chosen, and why. A translated decision that reads as a new decision is a defect.

- [ ] **Step 5: Translate `docs/design/engineering-harness-design.md`**

Same rules, plus:

- The status value means "awaiting review"; render it `- Status: pending review`.
- The blockquote near the top records that the Gradle and ARC design supersedes this document's build tool and runner decisions. Translate it as a note and keep its link target `./gradle-kotlin-arc-foundation-design.md` unchanged.
- Keep both mentions of `d0a4165f170193ba1d026a259af40d35bb7eaefe` and all 32 URLs.
- Keep all four Markdown tables at 36 table rows with 4 separator rows, and keep every row's cell count.

- [ ] **Step 6: Translate `docs/design/gradle-kotlin-arc-foundation-design.md`**

Same rules, plus:

- Render the metadata labels as `- Status: approved`, `- Date: 2026-08-10`, `- Revised: 2026-08-11 — ...`, `- Supersedes: ...`, `- Retains: ...`.
- Keep every Gradle task name, scale set name (`arc-java-build`, `aks-runners`), Helm value, Kubernetes noun, registry name, and branch name exactly as written.
- Keep all ten fence markers and all eight URLs.

- [ ] **Step 7: Verify preservation**

```bash
for f in docs/design/foundation-design.md docs/design/engineering-harness-design.md docs/design/gradle-kotlin-arc-foundation-design.md; do
  printf '%-74s HEAD %s\n' "$f" "$(git show HEAD:"$f" | doc_metrics)"
  printf '%-74s WORK %s\n' "$f" "$(doc_metrics < "$f")"
done
```

Expected: for each file the `HEAD` and `WORK` lines are identical and match the table in Step 1.

Run: `rg -l '\p{Hangul}' docs/design`

Expected: no output.

- [ ] **Step 8: Run the tests to verify they pass**

Run: `./gradlew :build-tools:harness-policy:test --tests '*DocumentationLanguagePolicyTest*' --tests '*MarkdownLinkPolicyTest*'`

Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add docs/design build-tools/harness-policy/src/test/java/com/microsoft/agentframework/build/harness/DocumentationLanguagePolicyTest.java
git commit -m "docs: translate the foundation, harness, and build design documents" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 3: Translate the requirements index and requirements 01 to 06

**Files:**
- Modify: `docs/requirements/README.md`
- Modify: `docs/requirements/01-agent-execution.md`
- Modify: `docs/requirements/02-message-content.md`
- Modify: `docs/requirements/03-structured-output.md`
- Modify: `docs/requirements/04-tools.md`
- Modify: `docs/requirements/05-mcp.md`
- Modify: `docs/requirements/06-sessions.md`
- Modify: `docs/requirements/07-interceptors.md` … `docs/requirements/12-providers.md` (link target only, see Step 6)
- Modify: `build-tools/harness-policy/src/test/java/com/microsoft/agentframework/build/harness/DocumentationLanguagePolicyTest.java` (remove seven `PENDING_TRANSLATION` entries)
- Test: `./gradlew :build-tools:harness-policy:test --tests '*DocumentationLanguagePolicyTest*' --tests '*MarkdownLinkPolicyTest*'`

**Interfaces:**
- Consumes: the glossary in Global Constraints and the design vocabulary from Task 2.
- Produces, used by Task 4 and Task 8:
  - the heading `## Requirement grades` in `docs/requirements/README.md`, whose anchor is `requirement-grades`
  - the shared link target `(README.md#requirement-grades)` used by all twelve requirement documents
  - the five section labels `**Requirement.**`, `**Upstream comparison**`, `**Decision.**`, `**Acceptance criteria**`, `**Evidence**`
  - the header labels `**Prefix**` and `**Upstream features**`
  - the grade values `Required`, `Recommended`, `Optional`

- [ ] **Step 1: Record the preservation baseline**

```bash
for f in docs/requirements/README.md docs/requirements/0[1-6]-*.md; do
  printf '%-56s HEAD %s\n' "$f" "$(git show HEAD:"$f" | doc_metrics)"
  printf '%-56s cols %s | %s | %s\n' "$f" \
    "$(git show HEAD:"$f" | column_shape 4)" "$(git show HEAD:"$f" | column_shape 5)" "$(git show HEAD:"$f" | column_shape 6)"
done
```

Expected:

| File | ids | urls | pin | fence | rows | seps | h2 | h3 | bullets |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `docs/requirements/README.md` | 0 | 0 | 0 | 0 | 30 | 4 | 7 | 0 | 8 |
| `docs/requirements/01-agent-execution.md` | 16 | 0 | 0 | 0 | 26 | 2 | 19 | 0 | 75 |
| `docs/requirements/02-message-content.md` | 13 | 0 | 0 | 0 | 22 | 2 | 16 | 0 | 67 |
| `docs/requirements/03-structured-output.md` | 12 | 0 | 0 | 0 | 21 | 2 | 15 | 0 | 61 |
| `docs/requirements/04-tools.md` | 21 | 0 | 0 | 0 | 31 | 2 | 24 | 0 | 106 |
| `docs/requirements/05-mcp.md` | 19 | 0 | 0 | 0 | 28 | 2 | 22 | 0 | 97 |
| `docs/requirements/06-sessions.md` | 20 | 0 | 0 | 0 | 29 | 2 | 23 | 0 | 101 |

The `cols` line prints the sorted value-count shape of the adoption, grade, and phase columns. Record
the printed values; Step 7 requires the identical shape.

- [ ] **Step 2: Make the policy demand English for these seven documents**

In `DocumentationLanguagePolicyTest.java`, delete these seven lines from `PENDING_TRANSLATION`:

```java
          "docs/requirements/README.md",
          "docs/requirements/01-agent-execution.md",
          "docs/requirements/02-message-content.md",
          "docs/requirements/03-structured-output.md",
          "docs/requirements/04-tools.md",
          "docs/requirements/05-mcp.md",
          "docs/requirements/06-sessions.md",
```

Lower `PENDING_TRANSLATION_SIZE` from `49` to `42` in the same edit. Remove the entries from `PENDING_TRANSLATION` only; `ORIGINAL_PENDING_TRANSLATION` is frozen and is never edited, so `pendingTranslationListOnlyHoldsOriginallyDeclaredDocuments` keeps rejecting a document that was swapped in rather than translated.

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew :build-tools:harness-policy:test --tests '*DocumentationLanguagePolicyTest*'`

Expected: FAIL with seven `canonicalDocumentContainsNoKoreanText` cases naming the seven files.

- [ ] **Step 4: Translate `docs/requirements/README.md`**

- Translate every sentence, heading, list item, and table cell. The document has 30 table rows across four tables; every row survives with the same cell count.
- Use these headings so the produced anchor contract holds: `## Requirement documents`, `## Requirement ids`, `## Requirement grades`, `## Release phases`, `## The shape of a requirement`, `## Decision rules`, `## Related documents`.
- Translate the grade table with the glossary in Global Constraints: the strongest grade becomes `Required`, the middle grade becomes `Recommended`, the weakest becomes `Optional`. Keep the definition sentences complete; do not shorten them.
- Keep the twelve document rows in order with their prefixes `AGT`, `MSG`, `OUT`, `TOOL`, `MCP`, `SES`, `INT`, `HAR`, `WF`, `HOST`, `OPS`, `PRV`, their requirement counts 16, 13, 12, 21, 19, 20, 22, 21, 35, 29, 26, 10, and their upstream feature numbers.
- Keep the statement that 244 requirements cover all 31 upstream features.
- Keep the five-part requirement shape list and the five decision rules, each fully translated.
- Keep the release phase table rows `MVP`, `Core+`, `Workflow`, `Hosting`, `Optional` with their scope text translated.
- Keep the `../upstream/snapshots/d0a4165f/compatibility-matrix.md`, `../design/foundation-design.md`, `../upstream/snapshots/d0a4165f/README.md`, and `../README.md` link targets unchanged.

- [ ] **Step 5: Translate `docs/requirements/01-agent-execution.md` through `docs/requirements/06-sessions.md`**

Translate one file at a time, in ascending order. For every file:

- Translate the whole document. Never summarize a requirement, drop a bullet from an acceptance criteria list, merge two requirements, or reorder rows.
- Keep every requirement id exactly as written, in the same order, in both the summary table and the section headings.
- Keep the summary table columns in order: id, requirement sentence, adoption, grade, phase. Translate the sentence, map adoption and grade with the glossary, copy the phase unchanged.
- Give every requirement section the heading form `## <ID> <translated requirement sentence>`, matching the sentence in the summary table.
- Render the five section labels exactly as `**Requirement.**`, `**Upstream comparison**`, `**Decision.**`, `**Acceptance criteria**`, `**Evidence**`, in that order, once per requirement.
- Render the header labels as `**Prefix**` and `**Upstream features**`, and keep their link targets unchanged.
- Keep the `.NET:` and `Python:` bullet structure of every upstream comparison, and keep every symbol name inside it unchanged.
- Keep every evidence link target under `../upstream/snapshots/d0a4165f/` unchanged; translate only the link label.
- Keep the `---` separators between requirement sections.
- In `01-agent-execution.md`, the prose that cross-references `AGT-001` uses a same-document fragment. After the `AGT-001` heading is translated, update that fragment to the new anchor. Find it with `rg -n '\]\(#' docs/requirements/01-agent-execution.md` and compute the anchor with the rule in `MarkdownDocuments.anchorOf`: lower case, punctuation and backticks dropped, spaces become hyphens.

- [ ] **Step 6: Retarget the shared grade anchor in all twelve requirement documents**

The twelve requirement documents link to the grade section of the index with a Korean anchor. The
index heading is now `## Requirement grades`, so every link must move in this commit, including the
links inside the six documents Task 4 translates.

```bash
rg -c '\(README\.md#' docs/requirements
perl -pi -e 's/\(README\.md#[^)]*\)/(README.md#requirement-grades)/g' docs/requirements/*.md
rg --count-matches '\(README\.md#requirement-grades\)' docs/requirements/*.md --no-filename | paste -sd+ - | bc
```

Expected: the first command reports one match in each of the twelve numbered documents; the last
command prints `12`.

- [ ] **Step 7: Verify preservation**

```bash
for f in docs/requirements/README.md docs/requirements/0[1-6]-*.md; do
  printf '%-56s HEAD %s\n' "$f" "$(git show HEAD:"$f" | doc_metrics)"
  printf '%-56s WORK %s\n' "$f" "$(doc_metrics < "$f")"
  printf '%-56s cols HEAD %s | %s | %s\n' "$f" \
    "$(git show HEAD:"$f" | column_shape 4)" "$(git show HEAD:"$f" | column_shape 5)" "$(git show HEAD:"$f" | column_shape 6)"
  printf '%-56s cols WORK %s | %s | %s\n' "$f" \
    "$(column_shape 4 < "$f")" "$(column_shape 5 < "$f")" "$(column_shape 6 < "$f")"
  printf '%-56s vals WORK %s | %s | %s\n' "$f" \
    "$(column_values 4 < "$f")" "$(column_values 5 < "$f")" "$(column_values 6 < "$f")"
done
```

Expected: `HEAD` and `WORK` metric lines identical per file; `cols HEAD` and `cols WORK` identical per
file; every `vals WORK` value drawn only from `Required`, `Recommended`, `Optional`, `Deferred`,
`MVP`, `Core+`, `Workflow`, `Hosting`.

Then check the section labels, which must equal the id count of each file:

```bash
for f in docs/requirements/0[1-6]-*.md; do
  printf '%s ids=%s req=%s cmp=%s dec=%s acc=%s evd=%s\n' "$f" \
    "$(rg --count-matches '^\| *[A-Z]{2,6}-[0-9]{3} *\|' "$f")" \
    "$(rg --count-matches '^\*\*Requirement\.\*\*' "$f")" \
    "$(rg --count-matches '^\*\*Upstream comparison\*\*' "$f")" \
    "$(rg --count-matches '^\*\*Decision\.\*\*' "$f")" \
    "$(rg --count-matches '^\*\*Acceptance criteria\*\*' "$f")" \
    "$(rg --count-matches '^\*\*Evidence\*\*' "$f")"
done
```

Expected: all six counts equal on every line, and equal to 16, 13, 12, 21, 19, 20 respectively.

```bash
rg --count-matches '^## [A-Z]{2,6}-[0-9]{3} ' docs/requirements/0[1-6]-*.md
rg -l '\p{Hangul}' docs/requirements/README.md docs/requirements/0[1-6]-*.md
```

Expected: heading counts 16, 13, 12, 21, 19, 20; no output from the second command.

- [ ] **Step 8: Run the tests to verify they pass**

Run: `./gradlew :build-tools:harness-policy:test --tests '*DocumentationLanguagePolicyTest*' --tests '*MarkdownLinkPolicyTest*'`

Expected: PASS. The link test proves the retargeted `README.md#requirement-grades` anchor resolves
from all twelve documents.

- [ ] **Step 9: Commit**

```bash
git add docs/requirements build-tools/harness-policy/src/test/java/com/microsoft/agentframework/build/harness/DocumentationLanguagePolicyTest.java
git commit -m "docs: translate the requirements index and requirements 01-06" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 4: Translate requirements 07 to 12

**Files:**
- Modify: `docs/requirements/07-interceptors.md`
- Modify: `docs/requirements/08-harness.md`
- Modify: `docs/requirements/09-workflows.md`
- Modify: `docs/requirements/10-hosting.md`
- Modify: `docs/requirements/11-operations.md`
- Modify: `docs/requirements/12-providers.md`
- Modify: `build-tools/harness-policy/src/test/java/com/microsoft/agentframework/build/harness/DocumentationLanguagePolicyTest.java` (remove six `PENDING_TRANSLATION` entries)
- Test: `./gradlew :build-tools:harness-policy:test --tests '*DocumentationLanguagePolicyTest*' --tests '*MarkdownLinkPolicyTest*'`

**Interfaces:**
- Consumes: from Task 3, the headings `## Requirement grades`, the link target `(README.md#requirement-grades)` already applied to these six files, the section labels `**Requirement.**`, `**Upstream comparison**`, `**Decision.**`, `**Acceptance criteria**`, `**Evidence**`, the header labels `**Prefix**` and `**Upstream features**`, and the grade values `Required`, `Recommended`, `Optional`.
- Produces: the complete English requirement corpus, 244 ids across twelve documents, for Task 8's final count.

- [ ] **Step 1: Record the preservation baseline**

```bash
for f in docs/requirements/0[7-9]-*.md docs/requirements/1[0-2]-*.md; do
  printf '%-56s HEAD %s\n' "$f" "$(git show HEAD:"$f" | doc_metrics)"
  printf '%-56s cols %s | %s | %s\n' "$f" \
    "$(git show HEAD:"$f" | column_shape 4)" "$(git show HEAD:"$f" | column_shape 5)" "$(git show HEAD:"$f" | column_shape 6)"
done
```

Expected:

| File | ids | urls | pin | fence | rows | seps | h2 | h3 | bullets |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `docs/requirements/07-interceptors.md` | 22 | 0 | 0 | 0 | 31 | 2 | 25 | 0 | 112 |
| `docs/requirements/08-harness.md` | 21 | 0 | 0 | 0 | 31 | 2 | 24 | 0 | 107 |
| `docs/requirements/09-workflows.md` | 35 | 0 | 0 | 0 | 44 | 2 | 38 | 6 | 184 |
| `docs/requirements/10-hosting.md` | 29 | 0 | 0 | 0 | 38 | 2 | 32 | 5 | 120 |
| `docs/requirements/11-operations.md` | 26 | 0 | 0 | 0 | 34 | 2 | 29 | 4 | 109 |
| `docs/requirements/12-providers.md` | 10 | 0 | 0 | 0 | 18 | 2 | 13 | 4 | 44 |

- [ ] **Step 2: Make the policy demand English for these six documents**

In `DocumentationLanguagePolicyTest.java`, delete these six lines from `PENDING_TRANSLATION`:

```java
          "docs/requirements/07-interceptors.md",
          "docs/requirements/08-harness.md",
          "docs/requirements/09-workflows.md",
          "docs/requirements/10-hosting.md",
          "docs/requirements/11-operations.md",
          "docs/requirements/12-providers.md",
```

Lower `PENDING_TRANSLATION_SIZE` from `42` to `36` in the same edit. Remove the entries from `PENDING_TRANSLATION` only; `ORIGINAL_PENDING_TRANSLATION` is frozen and is never edited, so `pendingTranslationListOnlyHoldsOriginallyDeclaredDocuments` keeps rejecting a document that was swapped in rather than translated.

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew :build-tools:harness-policy:test --tests '*DocumentationLanguagePolicyTest*'`

Expected: FAIL with six `canonicalDocumentContainsNoKoreanText` cases naming the six files.

- [ ] **Step 4: Translate the six documents**

Translate one file at a time, in ascending order, applying exactly the rules listed in Task 3 Step 5:

- Translate the whole document. Never summarize a requirement, drop a bullet from an acceptance criteria list, merge two requirements, or reorder rows.
- Keep every requirement id exactly as written, in the same order, in both the summary table and the section headings.
- Keep the summary table columns in order: id, requirement sentence, adoption, grade, phase. Translate the sentence, map adoption and grade with the glossary in Global Constraints, copy the phase unchanged.
- Give every requirement section the heading form `## <ID> <translated requirement sentence>`, matching the sentence in the summary table.
- Render the five section labels exactly as `**Requirement.**`, `**Upstream comparison**`, `**Decision.**`, `**Acceptance criteria**`, `**Evidence**`, in that order, once per requirement.
- Render the header labels as `**Prefix**` and `**Upstream features**`, and keep their link targets unchanged.
- Keep the `.NET:` and `Python:` bullet structure of every upstream comparison, and keep every symbol name inside it unchanged.
- Keep every evidence link target under `../upstream/snapshots/d0a4165f/` unchanged; translate only the link label.
- Keep the `---` separators between requirement sections.
- `09-workflows.md`, `10-hosting.md`, `11-operations.md`, and `12-providers.md` also carry level-3 headings that group requirements; keep their number exactly as listed in Step 1 and translate their text.
- Leave the `(README.md#requirement-grades)` link introduced by Task 3 untouched.

Commit after `09-workflows.md` is finished so the largest file lands separately:

```bash
git add docs/requirements/07-interceptors.md docs/requirements/08-harness.md docs/requirements/09-workflows.md
git commit -m "docs: translate requirements 07-09" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

- [ ] **Step 5: Verify preservation**

```bash
BASE=$(git rev-list -1 --grep='docs: plan the English documentation migration' HEAD)
for f in docs/requirements/0[7-9]-*.md docs/requirements/1[0-2]-*.md; do
  printf '%-56s BASE %s\n' "$f" "$(git show "$BASE":"$f" | doc_metrics)"
  printf '%-56s WORK %s\n' "$f" "$(doc_metrics < "$f")"
done
```

`BASE` is the commit that carries this plan and the untranslated documents, so the comparison stays
correct after the intermediate commit in Step 4. The printed numbers must match the table in Step 1
exactly for every file.

```bash
for f in docs/requirements/0[7-9]-*.md docs/requirements/1[0-2]-*.md; do
  printf '%s ids=%s req=%s cmp=%s dec=%s acc=%s evd=%s h2ids=%s\n' "$f" \
    "$(rg --count-matches '^\| *[A-Z]{2,6}-[0-9]{3} *\|' "$f")" \
    "$(rg --count-matches '^\*\*Requirement\.\*\*' "$f")" \
    "$(rg --count-matches '^\*\*Upstream comparison\*\*' "$f")" \
    "$(rg --count-matches '^\*\*Decision\.\*\*' "$f")" \
    "$(rg --count-matches '^\*\*Acceptance criteria\*\*' "$f")" \
    "$(rg --count-matches '^\*\*Evidence\*\*' "$f")" \
    "$(rg --count-matches '^## [A-Z]{2,6}-[0-9]{3} ' "$f")"
done
```

Expected: on every line all seven counts are equal, and equal to 22, 21, 35, 29, 26, 10 respectively.

```bash
for f in docs/requirements/0[7-9]-*.md docs/requirements/1[0-2]-*.md; do
  printf '%s vals %s | %s | %s\n' "$f" "$(column_values 4 < "$f")" "$(column_values 5 < "$f")" "$(column_values 6 < "$f")"
done
rg -l '\p{Hangul}' docs/requirements
rg --count-matches '^\| *[A-Z]{2,6}-[0-9]{3} *\|' docs/requirements/*.md --no-filename | paste -sd+ - | bc
```

Expected: every value drawn only from `Required`, `Recommended`, `Optional`, `Deferred`, `MVP`,
`Core+`, `Workflow`, `Hosting`; no output from the Korean scan; the id total prints `244`.

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew :build-tools:harness-policy:test --tests '*DocumentationLanguagePolicyTest*' --tests '*MarkdownLinkPolicyTest*'`

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add docs/requirements build-tools/harness-policy/src/test/java/com/microsoft/agentframework/build/harness/DocumentationLanguagePolicyTest.java
git commit -m "docs: translate requirements 10-12 and enforce English requirements" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 5: Translate the upstream indexes, manifest, ledger, matrix, and features 01 to 15

**Files:**
- Modify: `docs/upstream/README.md`
- Modify: `docs/upstream/snapshots/d0a4165f/README.md`
- Modify: `docs/upstream/snapshots/d0a4165f/snapshot-manifest.md`
- Modify: `docs/upstream/snapshots/d0a4165f/coverage-ledger.md`
- Modify: `docs/upstream/snapshots/d0a4165f/compatibility-matrix.md`
- Modify: `docs/upstream/snapshots/d0a4165f/features/01-agent-lifecycle.md` through `features/15-workflow-runtime.md` (fifteen files)
- Modify: `build-tools/harness-policy/src/test/java/com/microsoft/agentframework/build/harness/DocumentationLanguagePolicyTest.java` (remove twenty `PENDING_TRANSLATION` entries)
- Test: `./gradlew :build-tools:harness-policy:test --tests '*DocumentationLanguagePolicyTest*' --tests '*MarkdownLinkPolicyTest*'`

**Interfaces:**
- Consumes: the glossary in Global Constraints; the `Required`, `Recommended`, `Optional`, `Deferred` verdict vocabulary produced by Task 3.
- Produces, used by Task 6 and Task 8:
  - the heading `## Document map by feature group` in `docs/upstream/snapshots/d0a4165f/README.md`, anchor `document-map-by-feature-group`
  - the coverage ledger verification phrase `OK (H1 present, snapshot=d0a4165f)`
  - the coverage ledger title column, which must keep quoting each feature document's exact H1 text

- [ ] **Step 1: Record the preservation baseline**

```bash
for f in docs/upstream/README.md docs/upstream/snapshots/d0a4165f/*.md docs/upstream/snapshots/d0a4165f/features/0*.md docs/upstream/snapshots/d0a4165f/features/1[0-5]-*.md; do
  printf '%-74s HEAD %s\n' "$f" "$(git show HEAD:"$f" | doc_metrics)"
  printf '%-74s NF   %s\n' "$f" "$(git show HEAD:"$f" | awk -F'|' '/^\|/ {print NF}' | sort | uniq -c | paste -sd, -)"
done
```

Expected:

| File | ids | urls | pin | fence | rows | seps | h2 | h3 | bullets |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `docs/upstream/README.md` | 0 | 1 | 1 | 0 | 0 | 0 | 4 | 0 | 8 |
| `docs/upstream/snapshots/d0a4165f/README.md` | 0 | 1 | 2 | 0 | 9 | 1 | 6 | 5 | 35 |
| `docs/upstream/snapshots/d0a4165f/compatibility-matrix.md` | 0 | 7 | 8 | 0 | 106 | 6 | 8 | 0 | 17 |
| `docs/upstream/snapshots/d0a4165f/coverage-ledger.md` | 0 | 253 | 254 | 2 | 316 | 11 | 13 | 0 | 8 |
| `docs/upstream/snapshots/d0a4165f/snapshot-manifest.md` | 0 | 2 | 3 | 4 | 57 | 3 | 5 | 2 | 7 |
| `features/01-agent-lifecycle.md` | 0 | 157 | 157 | 0 | 0 | 0 | 19 | 21 | 83 |
| `features/02-message-content.md` | 0 | 168 | 168 | 0 | 0 | 0 | 19 | 21 | 98 |
| `features/03-model-execution.md` | 0 | 134 | 134 | 0 | 0 | 0 | 18 | 18 | 80 |
| `features/04-structured-output.md` | 0 | 152 | 152 | 0 | 0 | 0 | 18 | 20 | 85 |
| `features/05-function-tools.md` | 0 | 154 | 154 | 0 | 0 | 0 | 19 | 17 | 97 |
| `features/06-tool-approval.md` | 0 | 194 | 194 | 0 | 0 | 0 | 20 | 23 | 103 |
| `features/07-mcp-client-tools.md` | 0 | 196 | 196 | 0 | 0 | 0 | 18 | 26 | 108 |
| `features/08-sessions.md` | 0 | 200 | 200 | 0 | 0 | 0 | 15 | 31 | 112 |
| `features/09-history-context-memory.md` | 0 | 219 | 219 | 0 | 0 | 0 | 19 | 20 | 121 |
| `features/10-middleware.md` | 0 | 234 | 234 | 0 | 0 | 0 | 16 | 20 | 96 |
| `features/11-compaction.md` | 0 | 315 | 315 | 2 | 0 | 0 | 42 | 50 | 253 |
| `features/12-harness.md` | 0 | 510 | 510 | 0 | 0 | 0 | 14 | 150 | 320 |
| `features/13-skills-background-code.md` | 0 | 441 | 441 | 0 | 0 | 0 | 9 | 96 | 407 |
| `features/14-workflow-graph.md` | 0 | 217 | 218 | 0 | 0 | 0 | 15 | 20 | 96 |
| `features/15-workflow-runtime.md` | 0 | 202 | 202 | 0 | 0 | 0 | 14 | 23 | 114 |

- [ ] **Step 2: Make the policy demand English for these twenty documents**

In `DocumentationLanguagePolicyTest.java`, delete these twenty lines from `PENDING_TRANSLATION`:

```java
          "docs/upstream/README.md",
          "docs/upstream/snapshots/d0a4165f/README.md",
          "docs/upstream/snapshots/d0a4165f/compatibility-matrix.md",
          "docs/upstream/snapshots/d0a4165f/coverage-ledger.md",
          "docs/upstream/snapshots/d0a4165f/snapshot-manifest.md",
          "docs/upstream/snapshots/d0a4165f/features/01-agent-lifecycle.md",
          "docs/upstream/snapshots/d0a4165f/features/02-message-content.md",
          "docs/upstream/snapshots/d0a4165f/features/03-model-execution.md",
          "docs/upstream/snapshots/d0a4165f/features/04-structured-output.md",
          "docs/upstream/snapshots/d0a4165f/features/05-function-tools.md",
          "docs/upstream/snapshots/d0a4165f/features/06-tool-approval.md",
          "docs/upstream/snapshots/d0a4165f/features/07-mcp-client-tools.md",
          "docs/upstream/snapshots/d0a4165f/features/08-sessions.md",
          "docs/upstream/snapshots/d0a4165f/features/09-history-context-memory.md",
          "docs/upstream/snapshots/d0a4165f/features/10-middleware.md",
          "docs/upstream/snapshots/d0a4165f/features/11-compaction.md",
          "docs/upstream/snapshots/d0a4165f/features/12-harness.md",
          "docs/upstream/snapshots/d0a4165f/features/13-skills-background-code.md",
          "docs/upstream/snapshots/d0a4165f/features/14-workflow-graph.md",
          "docs/upstream/snapshots/d0a4165f/features/15-workflow-runtime.md",
```

Lower `PENDING_TRANSLATION_SIZE` from `36` to `16` in the same edit. Remove the entries from `PENDING_TRANSLATION` only; `ORIGINAL_PENDING_TRANSLATION` is frozen and is never edited, so `pendingTranslationListOnlyHoldsOriginallyDeclaredDocuments` keeps rejecting a document that was swapped in rather than translated.

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew :build-tools:harness-policy:test --tests '*DocumentationLanguagePolicyTest*'`

Expected: FAIL with twenty `canonicalDocumentContainsNoKoreanText` cases naming the twenty files.

- [ ] **Step 4: Translate the upstream policy document and the snapshot index**

`docs/upstream/README.md`:

- Translate every sentence. Keep the pinned identity block exactly: the repository URL, `Branch: main`, `Commit: d0a4165f170193ba1d026a259af40d35bb7eaefe`, and the commit time.
- Keep the five-item evidence priority list in order and fully translated: pinned production source; pinned unit, integration, and conformance tests; pinned specifications and feature documents; official Microsoft documentation; samples and READMEs.
- Keep the document-set links and the documentation-index backlink added in Task 1.
- Keep the update policy paragraph: a new upstream pin adds a new commit directory and a delta document instead of overwriting the existing snapshot.

`docs/upstream/snapshots/d0a4165f/README.md`:

- Translate every sentence, including all 35 bullets that describe the 31 feature documents. Do not shorten a description to a keyword list.
- Keep the snapshot identity table with all 9 rows and both mentions of the full commit SHA.
- Name the feature map heading exactly `## Document map by feature group`, and update the same-document link that targets it to `(#document-map-by-feature-group)`. Find it with `rg -n '\]\(#' docs/upstream/snapshots/d0a4165f/README.md`.
- Keep all relative link targets under `./features/`, `./coverage-ledger.md`, `./compatibility-matrix.md`, and `./snapshot-manifest.md` unchanged.

- [ ] **Step 5: Translate the manifest, ledger, and matrix**

`docs/upstream/snapshots/d0a4165f/snapshot-manifest.md`:

- Translate the section headings and prose. Keep all 57 table rows, all 4 fence markers, the archive checksum, the tracked file counts, and every version string byte-identical.

`docs/upstream/snapshots/d0a4165f/coverage-ledger.md`:

- Translate the extraction-rule paragraph, the section headings, and the verification column. Render the verification cell as `OK (H1 present, snapshot=d0a4165f)` in all 31 rows of the feature-document table.
- The title column quotes each feature document's H1 text. Keep every value byte-identical to the corresponding feature document's H1 as it stands after this task; Task 6 updates the one row whose H1 it translates.
- Keep all 316 table rows, all 253 URLs, all 254 pin mentions, both fence markers, and the ten machine-check summary rows with their discovered, mapped, unmapped, and count values unchanged.

`docs/upstream/snapshots/d0a4165f/compatibility-matrix.md`:

- Translate the judgement rules, the phase scope table, and every prose cell of the 71-row matrix.
- Keep the implementation-status vocabulary in English exactly as written: `Implemented/Tested`, `Implemented/Partial tests`, `Partial`, `Docs-only`, `Language-only`, `Provider-specific`, `Not found`.
- Map the Java adoption verdicts with the glossary in Global Constraints to `Required`, `Optional`, and `Deferred`.
- Keep the summary counts unchanged: 71 matrix rows; .NET `Implemented/Tested 37`, `Implemented/Partial tests 7`, `Partial 10`, `Provider-specific 13`, `Language-only 4`; Python `Implemented/Tested 53`, `Implemented/Partial tests 2`, `Provider-specific 13`, `Language-only 3`; repo docs `Docs-only 71`; release phase `MVP/Core+ 38`, `Workflow 9`, `Hosting 7`, `Optional adapters 17`.

Commit this half of the task:

```bash
git add docs/upstream/README.md docs/upstream/snapshots/d0a4165f/README.md docs/upstream/snapshots/d0a4165f/snapshot-manifest.md docs/upstream/snapshots/d0a4165f/coverage-ledger.md docs/upstream/snapshots/d0a4165f/compatibility-matrix.md
git commit -m "docs: translate the upstream indexes, manifest, ledger, and matrix" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

- [ ] **Step 6: Translate features 01 to 08**

Translate `features/01-agent-lifecycle.md`, `02-message-content.md`, `03-model-execution.md`,
`04-structured-output.md`, `05-function-tools.md`, `06-tool-approval.md`, `07-mcp-client-tools.md`,
and `08-sessions.md`, one file at a time. For every file:

- Translate every heading, sentence, and bullet. These documents are the evidence base: never drop, merge, or summarize a bullet, and never remove a permalink because a neighbouring bullet cites the same file.
- Keep the H1 exactly as written.
- Keep the `.NET` and `Python` subsection structure and the difference subsections, with the level-3 heading counts listed in Step 1.
- Keep every permalink byte-identical, including its `#L<start>-L<end>` range. Every URL count in Step 1 must survive.
- Keep every symbol, type, decorator, attribute, package, and file path in its original form.
- Quoted upstream text stays in its original language, which is English in this snapshot.
- Where a bullet records a difference or a drift between .NET and Python, translate the observation without resolving it.

Commit:

```bash
git add docs/upstream/snapshots/d0a4165f/features
git commit -m "docs: translate upstream features 01-08" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

- [ ] **Step 7: Translate features 09 to 15**

Translate `features/09-history-context-memory.md`, `10-middleware.md`, `11-compaction.md`,
`12-harness.md`, `13-skills-background-code.md`, `14-workflow-graph.md`, and
`15-workflow-runtime.md` with exactly the rules in Step 6.

`12-harness.md` carries 510 permalinks across 150 level-3 headings and `13-skills-background-code.md`
carries 441 permalinks across 96 level-3 headings. Translate them section by section and re-check the
counts after each one rather than at the end.

- [ ] **Step 8: Verify preservation**

```bash
BASE=$(git rev-list -1 --grep='docs: plan the English documentation migration' HEAD)
for f in docs/upstream/README.md docs/upstream/snapshots/d0a4165f/*.md docs/upstream/snapshots/d0a4165f/features/0*.md docs/upstream/snapshots/d0a4165f/features/1[0-5]-*.md; do
  printf '%-74s BASE %s\n' "$f" "$(git show "$BASE":"$f" | doc_metrics)"
  printf '%-74s WORK %s\n' "$f" "$(doc_metrics < "$f")"
  printf '%-74s NFB  %s\n' "$f" "$(git show "$BASE":"$f" | awk -F'|' '/^\|/ {print NF}' | sort | uniq -c | paste -sd, -)"
  printf '%-74s NFW  %s\n' "$f" "$(awk -F'|' '/^\|/ {print NF}' "$f" | sort | uniq -c | paste -sd, -)"
done
```

Expected: `BASE` equals `WORK` and `NFB` equals `NFW` for every file, and the numbers match the table
in Step 1.

```bash
rg -l '\p{Hangul}' docs/upstream/README.md docs/upstream/snapshots/d0a4165f/*.md docs/upstream/snapshots/d0a4165f/features/0*.md docs/upstream/snapshots/d0a4165f/features/1[0-5]-*.md
rg --count-matches 'https://github\.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/' docs/upstream/snapshots/d0a4165f/features/0*.md docs/upstream/snapshots/d0a4165f/features/1[0-5]-*.md --no-filename | paste -sd+ - | bc
```

Expected: no output from the first command; the second prints `3493`.

- [ ] **Step 9: Run the tests to verify they pass**

Run: `./gradlew :build-tools:harness-policy:test --tests '*DocumentationLanguagePolicyTest*' --tests '*MarkdownLinkPolicyTest*'`

Expected: PASS.

- [ ] **Step 10: Commit**

```bash
git add docs/upstream build-tools/harness-policy/src/test/java/com/microsoft/agentframework/build/harness/DocumentationLanguagePolicyTest.java
git commit -m "docs: translate upstream features 09-15" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 6: Translate upstream features 16 to 31

**Files:**
- Modify: `docs/upstream/snapshots/d0a4165f/features/16-workflow-checkpoint-hitl.md` through `features/31-provider-integrations.md` (sixteen files)
- Modify: `docs/upstream/snapshots/d0a4165f/coverage-ledger.md` (one title cell, see Step 5)
- Modify: `build-tools/harness-policy/src/test/java/com/microsoft/agentframework/build/harness/DocumentationLanguagePolicyTest.java` (remove sixteen `PENDING_TRANSLATION` entries)
- Test: `./gradlew :build-tools:harness-policy:test --tests '*DocumentationLanguagePolicyTest*' --tests '*MarkdownLinkPolicyTest*'`

**Interfaces:**
- Consumes: the feature-document translation rules and the coverage ledger title contract from Task 5.
- Produces: the last untranslated canonical documents, leaving `docs/ko/README.md` as the only entry left in `PENDING_TRANSLATION` handling for Task 7 and Task 8.

- [ ] **Step 1: Record the preservation baseline**

```bash
for f in docs/upstream/snapshots/d0a4165f/features/1[6-9]-*.md docs/upstream/snapshots/d0a4165f/features/2*.md docs/upstream/snapshots/d0a4165f/features/3*.md; do
  printf '%-74s HEAD %s\n' "$f" "$(git show HEAD:"$f" | doc_metrics)"
  printf '%-74s NF   %s\n' "$f" "$(git show HEAD:"$f" | awk -F'|' '/^\|/ {print NF}' | sort | uniq -c | paste -sd, -)"
done
```

Expected:

| File | ids | urls | pin | fence | rows | seps | h2 | h3 | bullets |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `features/16-workflow-checkpoint-hitl.md` | 0 | 269 | 270 | 0 | 0 | 0 | 16 | 23 | 180 |
| `features/17-workflow-composition.md` | 0 | 212 | 212 | 0 | 0 | 0 | 16 | 23 | 170 |
| `features/18-orchestrations.md` | 0 | 291 | 292 | 0 | 0 | 0 | 14 | 63 | 173 |
| `features/19-declarative.md` | 0 | 248 | 249 | 0 | 0 | 0 | 15 | 14 | 208 |
| `features/20-hosting.md` | 0 | 154 | 156 | 0 | 5 | 1 | 37 | 30 | 172 |
| `features/21-openai-responses-hosting.md` | 0 | 166 | 166 | 0 | 6 | 1 | 51 | 29 | 240 |
| `features/22-a2a.md` | 0 | 170 | 170 | 0 | 5 | 1 | 48 | 31 | 216 |
| `features/23-ag-ui.md` | 0 | 163 | 163 | 0 | 5 | 1 | 44 | 25 | 205 |
| `features/24-mcp-hosting.md` | 0 | 150 | 150 | 0 | 6 | 1 | 50 | 19 | 180 |
| `features/25-foundry-devui-channels.md` | 0 | 267 | 266 | 0 | 21 | 5 | 99 | 31 | 317 |
| `features/26-identity-session-routing.md` | 0 | 149 | 149 | 0 | 0 | 0 | 46 | 23 | 147 |
| `features/27-observability.md` | 0 | 202 | 203 | 0 | 0 | 0 | 14 | 49 | 281 |
| `features/28-errors-resilience-security.md` | 0 | 269 | 270 | 0 | 0 | 0 | 14 | 64 | 388 |
| `features/29-evaluation-testing.md` | 0 | 274 | 275 | 0 | 0 | 0 | 14 | 68 | 386 |
| `features/30-packaging-compatibility.md` | 0 | 216 | 217 | 0 | 0 | 0 | 14 | 61 | 317 |
| `features/31-provider-integrations.md` | 0 | 377 | 378 | 0 | 135 | 11 | 12 | 16 | 55 |

- [ ] **Step 2: Make the policy demand English for these sixteen documents**

In `DocumentationLanguagePolicyTest.java`, delete these sixteen lines from `PENDING_TRANSLATION`:

```java
          "docs/upstream/snapshots/d0a4165f/features/16-workflow-checkpoint-hitl.md",
          "docs/upstream/snapshots/d0a4165f/features/17-workflow-composition.md",
          "docs/upstream/snapshots/d0a4165f/features/18-orchestrations.md",
          "docs/upstream/snapshots/d0a4165f/features/19-declarative.md",
          "docs/upstream/snapshots/d0a4165f/features/20-hosting.md",
          "docs/upstream/snapshots/d0a4165f/features/21-openai-responses-hosting.md",
          "docs/upstream/snapshots/d0a4165f/features/22-a2a.md",
          "docs/upstream/snapshots/d0a4165f/features/23-ag-ui.md",
          "docs/upstream/snapshots/d0a4165f/features/24-mcp-hosting.md",
          "docs/upstream/snapshots/d0a4165f/features/25-foundry-devui-channels.md",
          "docs/upstream/snapshots/d0a4165f/features/26-identity-session-routing.md",
          "docs/upstream/snapshots/d0a4165f/features/27-observability.md",
          "docs/upstream/snapshots/d0a4165f/features/28-errors-resilience-security.md",
          "docs/upstream/snapshots/d0a4165f/features/29-evaluation-testing.md",
          "docs/upstream/snapshots/d0a4165f/features/30-packaging-compatibility.md",
          "docs/upstream/snapshots/d0a4165f/features/31-provider-integrations.md",
```

Lower `PENDING_TRANSLATION_SIZE` from `16` to `0` in the same edit. The list is then empty and Task 8
deletes it together with the frozen baseline. Remove the entries from `PENDING_TRANSLATION` only; `ORIGINAL_PENDING_TRANSLATION` is frozen and is never edited, so `pendingTranslationListOnlyHoldsOriginallyDeclaredDocuments` keeps rejecting a document that was swapped in rather than translated.

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew :build-tools:harness-policy:test --tests '*DocumentationLanguagePolicyTest*'`

Expected: FAIL with sixteen `canonicalDocumentContainsNoKoreanText` cases naming the sixteen files.

- [ ] **Step 4: Translate features 16 to 23**

Translate `16-workflow-checkpoint-hitl.md`, `17-workflow-composition.md`, `18-orchestrations.md`,
`19-declarative.md`, `20-hosting.md`, `21-openai-responses-hosting.md`, `22-a2a.md`, and
`23-ag-ui.md`, one file at a time, with these rules:

- Translate every heading, sentence, and bullet. Never drop, merge, or summarize a bullet, and never remove a permalink because a neighbouring bullet cites the same file.
- Keep the H1 exactly as written.
- Keep every permalink byte-identical, including its `#L<start>-L<end>` range.
- Keep every symbol, type, decorator, attribute, package, endpoint, protocol name, and file path in its original form.
- Keep the level-2 and level-3 heading counts listed in Step 1.
- Keep the tables in `20-hosting.md`, `21-openai-responses-hosting.md`, `22-a2a.md`, and `23-ag-ui.md` at their row and separator counts, with the same number of cells per row.
- Quoted upstream text stays in its original language.
- Translate observed differences and drift without resolving them.

Commit:

```bash
git add docs/upstream/snapshots/d0a4165f/features
git commit -m "docs: translate upstream features 16-23" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

- [ ] **Step 5: Translate features 24 to 31 and resynchronize the coverage ledger**

Translate `24-mcp-hosting.md`, `25-foundry-devui-channels.md`, `26-identity-session-routing.md`,
`27-observability.md`, `28-errors-resilience-security.md`, `29-evaluation-testing.md`,
`30-packaging-compatibility.md`, and `31-provider-integrations.md` with the rules in Step 4, plus:

- `25-foundry-devui-channels.md` is the one feature document whose H1 contains Korean words. Translate only that trailing phrase, keeping the rest of the title byte-identical, so the H1 becomes `# 25. Foundry hosting, DevUI, Aspire integration, ChatKit, Telegram, and other confirmed channel adapters`.
- In the same commit, update the title cell of the `25-foundry-devui-channels.md` row in `docs/upstream/snapshots/d0a4165f/coverage-ledger.md` to quote that exact new H1 text, so the ledger keeps matching the document it verifies.
- `31-provider-integrations.md` holds a 135-row inventory table. Keep every row and every cell count; translate only the descriptive cells.

Verify the ledger and the H1 agree:

```bash
rg -n '^# ' docs/upstream/snapshots/d0a4165f/features/25-foundry-devui-channels.md
rg -n '25-foundry-devui-channels' docs/upstream/snapshots/d0a4165f/coverage-ledger.md
```

Expected: the ledger row quotes the same title text that the H1 now carries.

- [ ] **Step 6: Verify preservation**

```bash
BASE=$(git rev-list -1 --grep='docs: plan the English documentation migration' HEAD)
for f in docs/upstream/snapshots/d0a4165f/features/1[6-9]-*.md docs/upstream/snapshots/d0a4165f/features/2*.md docs/upstream/snapshots/d0a4165f/features/3*.md; do
  printf '%-74s BASE %s\n' "$f" "$(git show "$BASE":"$f" | doc_metrics)"
  printf '%-74s WORK %s\n' "$f" "$(doc_metrics < "$f")"
  printf '%-74s NFB  %s\n' "$f" "$(git show "$BASE":"$f" | awk -F'|' '/^\|/ {print NF}' | sort | uniq -c | paste -sd, -)"
  printf '%-74s NFW  %s\n' "$f" "$(awk -F'|' '/^\|/ {print NF}' "$f" | sort | uniq -c | paste -sd, -)"
done
rg -l '\p{Hangul}' docs
rg --count-matches 'https://github\.com/microsoft/agent-framework/blob/' docs --no-filename | paste -sd+ - | bc
rg --count-matches 'https://github\.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/' docs/upstream/snapshots/d0a4165f/features/1[6-9]-*.md docs/upstream/snapshots/d0a4165f/features/2*.md docs/upstream/snapshots/d0a4165f/features/3*.md --no-filename | paste -sd+ - | bc
```

Expected: `BASE` equals `WORK` and `NFB` equals `NFW` for every file and matches the table in Step 1;
the Korean scan prints only `docs/ko/README.md`; the two permalink totals print `7286` and `3575`.

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./gradlew :build-tools:harness-policy:test --tests '*DocumentationLanguagePolicyTest*' --tests '*MarkdownLinkPolicyTest*'`

Expected: PASS, with `PENDING_TRANSLATION` now empty of feature documents and every remaining
canonical document scanned.

- [ ] **Step 8: Commit**

```bash
git add docs/upstream build-tools/harness-policy/src/test/java/com/microsoft/agentframework/build/harness/DocumentationLanguagePolicyTest.java
git commit -m "docs: translate upstream features 24-31" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 7: Expand the Korean companion and finish navigation

**Files:**
- Modify: `docs/ko/README.md`
- Modify: `docs/README.md` (only if a link listed in Step 3 is missing)
- Modify: `README.md` (only if a link listed in Step 3 is missing)
- Test: `./gradlew :build-tools:harness-policy:test --tests '*DocumentationLanguagePolicyTest*' --tests '*MarkdownLinkPolicyTest*'`

**Interfaces:**
- Consumes: `DocumentationLanguagePolicyTest.koreanCompanionExistsAndIsKorean`, `koreanCompanionIsTheOnlyDocumentUnderDocsKo`, `koreanCompanionDeclaresEnglishAsAuthoritative`, `englishEntryPointsLinkToTheKoreanCompanion`, and `directoryIndexLinksBackToTheDocumentationIndex` from Task 1.
- Produces: the finished companion, which Task 8 only re-verifies.

This plan file is governed by the same no-Korean rule as the rest of `docs/`, so the Korean prose
cannot be embedded here. Write it directly in `docs/ko/README.md` against the section contract,
link contract, and prohibition list below. Every one of them is objectively checkable by the
commands in Step 4.

- [ ] **Step 1: Rewrite the companion against the section contract**

`docs/ko/README.md` is a curated guide, not a line-by-line translation of the root README. Give it
these eight sections, in this order, written in Korean:

1. project purpose and current status;
2. runtime ownership and architecture boundaries, stating that `AgentEngine` owns model call and tool call state transitions, session state change rules, and streaming event order, while the host runtime owns dependency injection, configuration, object lifecycle, executors, schedulers, the HTTP server, security, and transactions;
3. quick start and the common Gradle commands `./gradlew policyCheck`, `./gradlew quality`, `./gradlew testJava17`, `./gradlew check`, with the JDK 17 requirement and the Temurin 21 and 25 compatibility note;
4. repository and module layout, listing `agent-framework-api`, `agent-framework-engine`, `agent-framework-testkit`, `agent-framework-bom`, `build-logic`, `build-tools/harness-policy`, `config`, `docs`, and `.harness`;
5. what the requirements, designs, operations guides, and pinned upstream evidence are for, and when to read each;
6. current implementation state and the next target, which is the `agent-framework-api` type model;
7. contribution and security entry points;
8. an explicit statement that English documents are authoritative, containing the exact ASCII sentence `English documents are authoritative.`

Keep the existing self-referencing fragment link working: if the current-state heading changes, update
the same-document link that targets it in the same edit. Find it with `rg -n '\]\(#' docs/ko/README.md`
and compute the new anchor with the rule in `MarkdownDocuments.anchorOf`.

- [ ] **Step 2: Apply the companion prohibitions**

The companion routes readers to canonical English contracts. It must not become a second
specification tree, so it must not contain:

- any requirement id such as `AGT-001`, or any requirement summary table;
- any acceptance criterion;
- any compatibility matrix row or verdict;
- any upstream permalink or the full commit SHA `d0a4165f170193ba1d026a259af40d35bb7eaefe`;
- any coverage ledger content.

Where the reader needs that content, link the English document instead.

- [ ] **Step 3: Apply the link contract**

`docs/ko/README.md` must contain link targets `../../README.md`, `../README.md`,
`../requirements/README.md`, `../design/foundation-design.md`, `../design/module-composition.md`,
`../operations/getting-started.md`, `../upstream/snapshots/d0a4165f/README.md`, `../../AGENTS.md`,
`../../CONTRIBUTING.md`, `../../SECURITY.md`, and `../../LICENSE`.

`README.md` must contain `](docs/README.md)` and `](docs/ko/README.md)`; `docs/README.md` must
contain `](ko/README.md)` and `](../README.md)`. Task 1 added all four; re-add anything missing.

- [ ] **Step 4: Verify the companion**

```bash
doc_metrics < docs/ko/README.md
rg -c '\p{Hangul}' docs/ko/README.md
rg --count-matches 'English documents are authoritative\.' docs/ko/README.md
rg --count-matches '\| *[A-Z]{2,6}-[0-9]{3} *\|' docs/ko/README.md || echo "0 requirement ids"
rg --count-matches 'd0a4165f170193ba1d026a259af40d35bb7eaefe' docs/ko/README.md || echo "0 pin mentions"
rg --count-matches 'https://github\.com/microsoft/agent-framework/blob/' docs/ko/README.md || echo "0 permalinks"
for t in ../../README.md ../README.md ../requirements/README.md ../design/foundation-design.md ../design/module-composition.md ../operations/getting-started.md ../upstream/snapshots/d0a4165f/README.md ../../AGENTS.md ../../CONTRIBUTING.md ../../SECURITY.md ../../LICENSE; do
  printf '%-48s %s\n' "$t" "$(rg --count-matches -F "]($t)" docs/ko/README.md || echo MISSING)"
done
git ls-files 'docs/ko/*' 
```

Expected: `doc_metrics` reports `0` in the requirement-id and pin columns; the Korean scan reports a
non-zero line count; the marker sentence matches once; the three prohibition checks print their `0`
fallback message; every link target reports at least `1` and none reports `MISSING`; `git ls-files`
lists only `docs/ko/README.md`.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :build-tools:harness-policy:test --tests '*DocumentationLanguagePolicyTest*' --tests '*MarkdownLinkPolicyTest*'`

Expected: PASS, including `koreanCompanionDeclaresEnglishAsAuthoritative`,
`koreanCompanionIsTheOnlyDocumentUnderDocsKo`, and every
`directoryIndexLinksBackToTheDocumentationIndex` case.

- [ ] **Step 6: Commit**

```bash
git add docs/ko/README.md docs/README.md README.md
git commit -m "docs: expand the Korean companion and documentation navigation" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 8: Remove the migration list and run the final regression and review

**Files:**
- Modify: `build-tools/harness-policy/src/test/java/com/microsoft/agentframework/build/harness/DocumentationLanguagePolicyTest.java` (delete `PENDING_TRANSLATION` and its guard test)
- Modify: any document a check in this task proves wrong
- Test: `./gradlew policyCheck`, `./gradlew quality`, `./gradlew testJava17 testJava21 testJava25`, `./gradlew check`

**Interfaces:**
- Consumes: everything produced by Tasks 1 through 7.
- Produces: an unconditional language policy with no suppression list, and the final report.

- [ ] **Step 1: Prove the migration list is empty of live entries**

Run: `rg -n 'PENDING_TRANSLATION' build-tools/harness-policy/src/test/java/com/microsoft/agentframework/build/harness/DocumentationLanguagePolicyTest.java`

Expected: the constant is declared and referenced, and `Set.of(` is followed immediately by `)` with
no remaining document paths. If any path remains, the task that owned it is unfinished; stop and
finish that task first.

- [ ] **Step 2: Delete the migration list and its guard**

In `DocumentationLanguagePolicyTest.java`:

- delete the `PENDING_TRANSLATION` and `ORIGINAL_PENDING_TRANSLATION` fields, the `PENDING_TRANSLATION_SIZE` and `ORIGINAL_PENDING_TRANSLATION_SIZE` constants, and their Javadoc;
- delete the `pendingTranslation()` method source, the `pendingTranslationEntryStillContainsKoreanText` test, the `pendingTranslationListHasNotWidened` test, the `pendingTranslationListOnlyHoldsOriginallyDeclaredDocuments` test, the `originalPendingTranslationListIsTheFrozenBaseline` test, and the `everyPendingTranslationEntryIsAScannedDocument` test;
- delete the now-unused `java.util.Set` import, which no remaining member uses; keep `java.nio.charset.StandardCharsets`, `java.nio.file.Files`, and `java.util.List`, which the companion and directory-index tests still use;
- reduce `canonicalDocuments()` to:

```java
  static Stream<String> canonicalDocuments() {
    return documentPaths().stream()
        .filter(path -> !MarkdownDocuments.KOREAN_COMPANION.equals(path));
  }
```

The scan is now unconditional: every repository-owned Markdown file except the companion must be
English, with no allowlist that a future document could be added to.

- [ ] **Step 3: Run the language and link policy to verify it passes unconditionally**

Run: `./gradlew :build-tools:harness-policy:test --tests '*DocumentationLanguagePolicyTest*' --tests '*MarkdownLinkPolicyTest*'`

Expected: PASS with one `canonicalDocumentContainsNoKoreanText` case per document and no skipped or
suppressed case.

- [ ] **Step 4: Prove the policy actually fails on a regression**

```bash
python3 -c "open('docs/README.md','a',encoding='utf-8').write('\\n' + chr(0xD55C) + chr(0xAE00) + '\\n')"
./gradlew :build-tools:harness-policy:test --tests '*DocumentationLanguagePolicyTest*'
```

Expected: FAIL naming `docs/README.md` and the injected line number. Then restore the file and
re-verify:

```bash
git checkout -- docs/README.md
./gradlew :build-tools:harness-policy:test --tests '*DocumentationLanguagePolicyTest*'
git status --short docs/README.md
```

Expected: PASS, and `git status --short` prints nothing.

- [ ] **Step 5: Run the whole-corpus preservation comparison**

```bash
BASE=$(git rev-list -1 --grep='docs: plan the English documentation migration' HEAD)
for f in $(git ls-files 'docs/*.md' 'README.md' | rg -v '^docs/(plans/|README\.md$|ko/)'); do
  b="$(git show "$BASE":"$f" | doc_metrics)"
  w="$(doc_metrics < "$f")"
  [ "$b" = "$w" ] || printf 'MISMATCH %-70s BASE %s WORK %s\n' "$f" "$b" "$w"
done
echo "comparison complete"
```

Expected: only `comparison complete`. `README.md` is compared too and must match except for the
documentation-index row Task 1 added; if the loop reports `README.md` with a table-row difference of
exactly one, confirm the difference is that row and nothing else.

- [ ] **Step 6: Run the global evidence and requirement counts**

```bash
CORPUS=$(git ls-files 'docs/*.md' 'README.md' | rg -v '^docs/plans/')
rg --count-matches '^\| *[A-Z]{2,6}-[0-9]{3} *\|' $CORPUS --no-filename | paste -sd+ - | bc
rg --count-matches 'https://github\.com/microsoft/agent-framework/blob/' $CORPUS --no-filename | paste -sd+ - | bc
rg --count-matches 'd0a4165f170193ba1d026a259af40d35bb7eaefe' $CORPUS --no-filename | paste -sd+ - | bc
rg --count-matches '^\*\*Requirement\.\*\*' docs/requirements/*.md --no-filename | paste -sd+ - | bc
rg --count-matches '^\*\*Upstream comparison\*\*' docs/requirements/*.md --no-filename | paste -sd+ - | bc
rg --count-matches '^\*\*Decision\.\*\*' docs/requirements/*.md --no-filename | paste -sd+ - | bc
rg --count-matches '^\*\*Acceptance criteria\*\*' docs/requirements/*.md --no-filename | paste -sd+ - | bc
rg --count-matches '^\*\*Evidence\*\*' docs/requirements/*.md --no-filename | paste -sd+ - | bc
rg --count-matches '^## [A-Z]{2,6}-[0-9]{3} ' docs/requirements/*.md --no-filename | paste -sd+ - | bc
```

Expected, in order: `244`, `7286`, `7350`, `244`, `244`, `244`, `244`, `244`, `244`.

- [ ] **Step 7: Run the terminology sweep**

```bash
for f in $(git ls-files 'docs/requirements/*.md'); do printf '%-48s %s | %s | %s\n' "$f" "$(column_values 4 < "$f")" "$(column_values 5 < "$f")" "$(column_values 6 < "$f")"; done
rg -in 'mandatory|must-have|nice to have|deprecated grade' docs || echo "no off-glossary grade words"
rg -n 'AgentEngine' docs --count-matches --no-filename | paste -sd+ - | bc
rg -in '\bagent engine\b' docs || echo "no unspaced-term drift"
rg -in 'upstream original|original framework analysis document' docs || echo "no literal-translation drift"
```

Expected: every adoption, grade, and phase value drawn only from `Required`, `Recommended`,
`Optional`, `Deferred`, `MVP`, `Core+`, `Workflow`, `Hosting`; the three drift probes print their
fallback message or a short list you then fix in place.

- [ ] **Step 8: Run the language and link scans one last time**

```bash
rg -l '\p{Hangul}' --glob '*.md' .
rg -l '\p{Hangul}' --glob '*.md' --glob '!docs/ko/README.md' .
git ls-files 'docs/ko/*'
```

Expected: the first command prints exactly `docs/ko/README.md`; the second prints nothing; the third
prints exactly `docs/ko/README.md`.

- [ ] **Step 9: Run the full verification contract**

Run: `./gradlew policyCheck`
Expected: BUILD SUCCESSFUL.

Run: `./gradlew quality`
Expected: BUILD SUCCESSFUL.

Run: `./gradlew testJava17 testJava21 testJava25`
Expected: BUILD SUCCESSFUL. If Temurin 21 or 25 is not installed, this fails with a toolchain
resolution error; report that outcome verbatim instead of narrowing the command.

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL.

Run: `git diff --stat build-tools/harness-policy/gradle.lockfile gradle/libs.versions.toml`
Expected: no output.

- [ ] **Step 10: Review the migration diff**

```bash
BASE=$(git rev-list -1 --grep='docs: plan the English documentation migration' HEAD)
git diff --stat "$BASE"..HEAD
git diff "$BASE"..HEAD -- docs/requirements/01-agent-execution.md | head -200
```

Read the diff of at least one requirement document, one design document, one feature analysis, the
coverage ledger, and the compatibility matrix, and confirm for each:

- no requirement, bullet, table row, or acceptance criterion disappeared;
- no permalink changed;
- no decision, grade, adoption verdict, support level, or release phase changed meaning;
- no new behavioral claim was introduced;
- every heading rename that had inbound links moved those links in the same commit.

Record any ambiguity found during translation in the final report rather than resolving it in a
document.

- [ ] **Step 11: Commit**

```bash
git add build-tools/harness-policy/src/test/java/com/microsoft/agentframework/build/harness/DocumentationLanguagePolicyTest.java docs README.md
git commit -m "docs: enforce the English documentation policy without a migration list" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

- [ ] **Step 12: Report**

State, in this order: the commands run and their outcomes; any check that could not run and why; the
final counts from Step 6; the list of documents translated per task; and every ambiguity found but
deliberately not resolved.

---

## Task 2 review — minor fixes report

**Commands run and outcomes:**

1. `./gradlew :build-tools:harness-policy:test --tests '*DocumentationLanguagePolicyTest*' --tests '*MarkdownLinkPolicyTest*'` — PASS
2. `./gradlew policyCheck` — PASS

**Checks that could not run:** `testJava21` and `testJava25` require Temurin 21 and 25 toolchains; not verified locally.

**Six minor fixes applied (one commit):**

| # | File | Change |
| --- | --- | --- |
| 1 | `docs/design/gradle-kotlin-arc-foundation-design.md` | Fixed fragment verb: "to operate triple in number" → "triple in number" |
| 2 | `docs/design/gradle-kotlin-arc-foundation-design.md` | Clarified object: "The whole document" → "The whole Secret document" |
| 3 | `docs/design/engineering-harness-design.md` | Fixed subject-verb in golden scenario 5: "Concatenating…contains" → "The concatenated streaming results…contain" |
| 4 | `docs/design/engineering-harness-design.md` | Restored source wording: removed spurious "access" from "Azure access and artifact attestation use GitHub OIDC" |
| 5 | `docs/design/engineering-harness-design.md` | Changed "permitted flake rate" → "permitted flake count" |
| 6 | `docs/design/foundation-design.md` | Preserved metadata distinction: `Date:` → `Approval date:` |

**Metrics (HEAD vs WORK — identical for all three files):**

| File | ids | urls | pin | fence | rows | seps | h2 | h3 | bullets |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `docs/design/foundation-design.md` | 0 | 7 | 0 | 4 | 9 | 1 | 11 | 18 | 81 |
| `docs/design/engineering-harness-design.md` | 0 | 32 | 2 | 2 | 36 | 4 | 15 | 45 | 276 |
| `docs/design/gradle-kotlin-arc-foundation-design.md` | 0 | 8 | 0 | 10 | 12 | 1 | 14 | 15 | 84 |

**Plan update:** Step 4 instruction updated to reflect `Approval date:` for `foundation-design.md`.

**Ambiguities:** none.

---

## Success criteria

The migration is complete when all of the following hold:

1. `rg -l '\p{Hangul}' --glob '*.md' .` prints exactly `docs/ko/README.md`.
2. `git ls-files 'docs/ko/*'` prints exactly `docs/ko/README.md`.
3. `docs/README.md` gives a complete role-based documentation map and links the companion.
4. `MarkdownLinkPolicyTest` reports no unresolved relative link or heading fragment.
5. The requirement id total is `244` and the upstream permalink total is `7286`.
6. `./gradlew policyCheck` fails when Korean text, a second `docs/ko` document, a broken relative link, or a missing entry-point link is introduced.
7. `./gradlew check` passes on the supported JDK toolchains.
