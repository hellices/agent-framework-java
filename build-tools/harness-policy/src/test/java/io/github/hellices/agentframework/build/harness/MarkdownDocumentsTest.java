package io.github.hellices.agentframework.build.harness;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the ownership invariant of {@link MarkdownDocuments#files()}: the scan is an allowlist of
 * the canonical locations in section 8.1 of the documentation language policy, not a filtered walk
 * of the working tree.
 */
class MarkdownDocumentsTest {

  @Test
  void scanCoversTheCanonicalLocationsAndNothingElse(@TempDir Path root) throws IOException {
    writeMarkdown(
        root,
        "README.md",
        "LICENSE.md",
        "AGENTS.md",
        "CONTRIBUTING.md",
        "SECURITY.md",
        "CLAUDE.md",
        "GEMINI.md",
        ".github/copilot-instructions.md",
        ".github/ISSUE_TEMPLATE/bug.md",
        "docs/README.md",
        "docs/ko/README.md",
        "docs/upstream/snapshots/d0a4165f/features/01-agent-lifecycle.md");
    writeMarkdown(
        root,
        "build-tools/harness-policy/README.md",
        "agent-framework-api/src/main/java/io/github/hellices/agentframework/api/package.md");

    assertThat(scan(root))
        .containsExactly(
            ".github/ISSUE_TEMPLATE/bug.md",
            ".github/copilot-instructions.md",
            "AGENTS.md",
            "CLAUDE.md",
            "CONTRIBUTING.md",
            "GEMINI.md",
            "LICENSE.md",
            "README.md",
            "SECURITY.md",
            "docs/README.md",
            "docs/ko/README.md",
            "docs/upstream/snapshots/d0a4165f/features/01-agent-lifecycle.md");
  }

  @Test
  void everyRootMarkdownDocumentIsOwnedWithoutUpdatingAFileNameAllowlist(@TempDir Path root)
      throws IOException {
    writeMarkdown(root, "README.md", "FUTURE-POLICY.md");

    assertThat(scan(root)).containsExactly("FUTURE-POLICY.md", "README.md");
  }

  @Test
  void githubMarkdownIsOwnedAtEveryDepth(@TempDir Path root) throws IOException {
    writeMarkdown(
        root, ".github/copilot-instructions.md", ".github/ISSUE_TEMPLATE/config/triage.md");

    assertThat(scan(root))
        .containsExactly(
            ".github/ISSUE_TEMPLATE/config/triage.md", ".github/copilot-instructions.md");
  }

  @Test
  void markdownOutsideTheOwnedLocationsIsIgnored(@TempDir Path root) throws IOException {
    writeMarkdown(root, "docs/README.md");
    writeMarkdown(
        root,
        "notes/review-findings.md",
        ".copilot/session.md",
        ".superpowers/sdd/task-1-brief.md",
        ".worktrees/other/docs/README.md",
        ".harness/runs/2026-08-12/report.md");

    assertThat(scan(root)).containsExactly("docs/README.md");
  }

  @Test
  void markdownUnderABuildPackagePathStaysInScopeWhenItsLocationIsOwned(@TempDir Path root)
      throws IOException {
    writeMarkdown(
        root,
        "docs/design/samples/io/github/hellices/agentframework/build/harness/usage.md",
        "docs/operations/bin/runner-notes.md",
        "docs/upstream/out/evidence.md");

    assertThat(scan(root))
        .containsExactly(
            "docs/design/samples/io/github/hellices/agentframework/build/harness/usage.md",
            "docs/operations/bin/runner-notes.md",
            "docs/upstream/out/evidence.md");
  }

  @Test
  void generatedOutputStaysOutOfScope(@TempDir Path root) throws IOException {
    writeMarkdown(root, "docs/README.md");
    writeMarkdown(
        root,
        "build/reports/policy.md",
        "build-tools/harness-policy/build/reports/tests/summary.md",
        "node_modules/some-package/README.md",
        ".gradle/cache.md");

    assertThat(scan(root)).containsExactly("docs/README.md");
  }

  @Test
  void scanIsSortedByRepositoryRelativePath(@TempDir Path root) throws IOException {
    writeMarkdown(
        root,
        "docs/requirements/README.md",
        "docs/design/foundation-design.md",
        "docs/README.md",
        "README.md");

    assertThat(scan(root))
        .containsExactly(
            "README.md",
            "docs/README.md",
            "docs/design/foundation-design.md",
            "docs/requirements/README.md");
  }

  @Test
  void repositoryScanReturnsOwnedLocationsOnly() throws IOException {
    List<String> scanned =
        MarkdownDocuments.files().stream().map(MarkdownDocuments::relativePath).toList();

    assertThat(scanned)
        .contains(
            "README.md",
            "AGENTS.md",
            "CONTRIBUTING.md",
            "SECURITY.md",
            "CLAUDE.md",
            "GEMINI.md",
            ".github/copilot-instructions.md",
            MarkdownDocuments.DOCUMENTATION_INDEX,
            MarkdownDocuments.KOREAN_COMPANION);
    assertThat(scanned)
        .allSatisfy(
            path ->
                assertThat(isOwnedLocation(path))
                    .withFailMessage(
                        "%s is not one of the locations section 8.1 of the documentation language"
                            + " policy owns, so the scan reaches outside the canonical document set.",
                        path)
                    .isTrue());
  }

  private static boolean isOwnedLocation(String relativePath) {
    if (relativePath.startsWith("docs/")) {
      return true;
    }
    if (relativePath.startsWith(".github/")) {
      return true;
    }
    return relativePath.indexOf('/') < 0;
  }

  private static List<String> scan(Path root) throws IOException {
    return MarkdownDocuments.filesUnder(root).stream()
        .map(file -> MarkdownDocuments.relativePath(root, file))
        .toList();
  }

  private static void writeMarkdown(Path root, String... relativePaths) throws IOException {
    for (String relativePath : relativePaths) {
      Path file = root.resolve(relativePath);
      Files.createDirectories(
          Objects.requireNonNull(
              file.getParent(), "A written file always has a parent directory."));
      Files.writeString(file, "# " + relativePath + "\n", StandardCharsets.UTF_8);
    }
  }
}
