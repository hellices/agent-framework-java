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
   * Documents that have not been translated yet. Every entry is removed by the task that translates
   * it, and {@link #pendingTranslationEntryStillContainsKoreanText(String)} fails once an entry no
   * longer needs migration, so this list can only shrink. It is deleted entirely by the final task.
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
