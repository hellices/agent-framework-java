package io.github.hellices.agentframework.build.harness;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class DocumentationLanguagePolicyTest {

  static Stream<String> canonicalDocuments() {
    return documentPaths().stream()
        .filter(path -> !MarkdownDocuments.KOREAN_COMPANION.equals(path));
  }

  static Stream<String> directoryIndexes() {
    return documentPaths().stream()
        .filter(path -> path.startsWith("docs/"))
        .filter(path -> path.endsWith("/README.md"))
        .filter(path -> !MarkdownDocuments.DOCUMENTATION_INDEX.equals(path));
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
