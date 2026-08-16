package io.github.hellices.agentframework.build.harness;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Proves the published BOM manages versions instead of forcing dependencies.
 *
 * <p>Reading the build file is not enough. A BOM can name every module inside a {@code constraints}
 * block and still publish forced dependencies if a plain declaration sits beside it, so this test
 * asserts on the artifact a consumer actually resolves.
 *
 * <p>The pom is located by the version this build declares, not by sorting filenames. The publish
 * directory accumulates across versions and is never cleaned, and a lexicographic maximum would
 * pick {@code 0.9.0} over {@code 0.10.0}; the test would then pass while inspecting an artifact
 * nobody ships.
 *
 * <p>When no published pom exists the test skips rather than passes, so a local run stays
 * convenient without the result looking verified. CI publishes first and sets {@code
 * agentframework.requirePublishedBom}, which turns absence into a failure. That is what keeps this
 * contract from evaporating if the publish step breaks or the repository path moves.
 */
class PublishedBomContractTest {

  private static final List<String> MANAGED_ARTIFACTS =
      List.of(
          "agent-framework-api",
          "agent-framework-engine",
          "agent-framework-testkit",
          "agent-framework-mcp");

  private static final String PUBLISH_COMMAND =
      "./gradlew publishAllPublicationsToBuildDirectoryRepository";

  @Test
  void publishedBomManagesEveryLibraryVersion() {
    String dependencyManagement = sectionOf(requirePublishedBomPom(), "dependencyManagement");

    for (String artifact : MANAGED_ARTIFACTS) {
      assertThat(dependencyManagement)
          .withFailMessage(
              "The published BOM does not manage %s. Every library must be listed in the platform"
                  + " constraints, otherwise a consumer importing the BOM gets no version for it.",
              artifact)
          .contains("<artifactId>" + artifact + "</artifactId>");
    }
  }

  @Test
  void publishedBomForcesNoDependencyOnConsumers() {
    String withoutManagement = removeSection(requirePublishedBomPom(), "dependencyManagement");

    assertThat(withoutManagement)
        .withFailMessage(
            "The published BOM declares dependencies outside <dependencyManagement>. A consumer"
                + " importing it would receive those modules on the classpath instead of only"
                + " version alignment. Declare every entry inside `constraints { }` and do not"
                + " enable `allowDependencies()`.")
        .doesNotContain("<dependencies>");
  }

  @Test
  void publishedBomCarriesTheVersionThisBuildDeclares() {
    // Without this the assertions above could inspect a stale artifact from an earlier version and
    // report a contract the current build never satisfied.
    assertThat(requirePublishedBomPom())
        .withFailMessage(
            "The published BOM pom found for version %s does not declare it. Re-run `%s`.",
            declaredVersion(), PUBLISH_COMMAND)
        .contains("<version>" + declaredVersion() + "</version>");
  }

  /**
   * Returns the published pom, or aborts the calling test when publishing has not run.
   *
   * <p>Aborting registers as skipped, which is visible in a report. Returning normally would let a
   * broken publish path masquerade as a satisfied contract.
   */
  private static String requirePublishedBomPom() {
    Optional<String> pom = publishedBomPom();

    if (publishingIsRequired()) {
      assertThat(pom)
          .withFailMessage(
              "No published BOM pom for version %s under %s. This build requires one because"
                  + " `agentframework.requirePublishedBom` is set, so the publish step that should"
                  + " precede this check did not produce the expected artifact.",
              declaredVersion(), publishedRepository())
          .isPresent();
    } else {
      Assumptions.assumeTrue(
          pom.isPresent(),
          "Skipped: no published BOM pom for version "
              + declaredVersion()
              + ". Run `"
              + PUBLISH_COMMAND
              + "` first to verify the published artifact.");
    }

    return pom.orElseThrow();
  }

  private static boolean publishingIsRequired() {
    return Boolean.parseBoolean(System.getProperty("agentframework.requirePublishedBom", "false"));
  }

  private static String declaredVersion() {
    String version = System.getProperty("agentframework.version");
    if (version == null || version.isBlank()) {
      throw new IllegalStateException(
          "The build must pass -Dagentframework.version so this test can identify the pom it"
              + " produced instead of guessing from filenames.");
    }
    return version;
  }

  /**
   * Finds the pom for the declared version.
   *
   * <p>A snapshot publish expands the version into a timestamped filename, so an exact name match
   * is not possible. The search is scoped to the version directory and, among the files there,
   * picks the most recently written one.
   */
  private static Optional<String> publishedBomPom() {
    Path versionDirectory =
        publishedRepository()
            .resolve("io/github/hellices/agentframework/agent-framework-bom")
            .resolve(declaredVersion());

    if (!Files.isDirectory(versionDirectory)) {
      return Optional.empty();
    }

    try (Stream<Path> files = Files.list(versionDirectory)) {
      return files
          .filter(Files::isRegularFile)
          .filter(path -> fileNameOf(path).endsWith(".pom"))
          .max(Comparator.comparingLong(PublishedBomContractTest::lastModified))
          .map(PublishedBomContractTest::read);
    } catch (IOException cause) {
      throw new UncheckedIOException("Cannot scan " + versionDirectory, cause);
    }
  }

  private static long lastModified(Path path) {
    try {
      return Files.getLastModifiedTime(path).toMillis();
    } catch (IOException cause) {
      throw new UncheckedIOException("Cannot read the timestamp of " + path, cause);
    }
  }

  private static Path publishedRepository() {
    return RepositoryPaths.root().resolve("build/maven-repository");
  }

  private static String fileNameOf(Path path) {
    Path name = path.getFileName();
    return name == null ? "" : name.toString();
  }

  private static String sectionOf(String pom, String element) {
    int start = pom.indexOf("<" + element + ">");
    if (start < 0) {
      return "";
    }
    int end = pom.indexOf("</" + element + ">", start);
    return end < 0 ? "" : pom.substring(start, end);
  }

  private static String removeSection(String pom, String element) {
    int start = pom.indexOf("<" + element + ">");
    if (start < 0) {
      return pom;
    }
    String closing = "</" + element + ">";
    int end = pom.indexOf(closing, start);
    return end < 0 ? pom : pom.substring(0, start) + pom.substring(end + closing.length());
  }

  private static String read(Path path) {
    try {
      return Files.readString(path, StandardCharsets.UTF_8);
    } catch (IOException cause) {
      throw new UncheckedIOException("Cannot read " + path, cause);
    }
  }
}
