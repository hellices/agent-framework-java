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
      if (!MarkdownDocuments.hasExactPathCase(RepositoryPaths.root(), file)) {
        unresolved.add(link.describe() + " (path case does not match the target)");
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

  @Test
  void linkExtractionReadsReferenceStyleLinks(@TempDir Path directory) throws IOException {
    Path document = directory.resolve("sample.md");
    Files.writeString(
        document,
        String.join(
            "\n",
            "Read the [requirements][requirements].",
            "",
            "[requirements]: ../requirements/README.md \"Requirements\"",
            ""),
        StandardCharsets.UTF_8);

    assertThat(MarkdownDocuments.links(document))
        .extracting(MarkdownDocuments.Link::line, MarkdownDocuments.Link::target)
        .containsExactly(tuple(1, "../requirements/README.md"));
  }

  @Test
  void linkExtractionReadsRelativeAutolinks(@TempDir Path directory) throws IOException {
    Path document = directory.resolve("sample.md");
    Files.writeString(document, "Read <../requirements/README.md>.\n", StandardCharsets.UTF_8);

    assertThat(MarkdownDocuments.links(document))
        .extracting(MarkdownDocuments.Link::line, MarkdownDocuments.Link::target)
        .containsExactly(tuple(1, "../requirements/README.md"));
  }

  @Test
  void exactPathCaseIsIndependentOfFileSystemCaseSensitivity(@TempDir Path directory)
      throws IOException {
    Path target = directory.resolve("docs/Guide.md");
    Files.createDirectories(directory.resolve("docs"));
    Files.writeString(target, "# Guide\n", StandardCharsets.UTF_8);

    assertThat(MarkdownDocuments.hasExactPathCase(directory, target)).isTrue();
    assertThat(MarkdownDocuments.hasExactPathCase(directory, directory.resolve("docs/guide.md")))
        .isFalse();
  }
}
