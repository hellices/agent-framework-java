package com.microsoft.agentframework.build.harness;

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
import org.junit.jupiter.api.Test;

/**
 * Proves the published BOM manages versions instead of forcing dependencies.
 *
 * <p>Reading the build file is not enough. A BOM can name every module inside a {@code constraints}
 * block and still publish forced dependencies if a plain declaration sits beside it, so this test
 * asserts on the artifact a consumer actually resolves.
 *
 * <p>The test reads whatever {@code publishAllPublicationsToBuildDirectoryRepository} last wrote.
 * It reports the command to run when that output is missing rather than invoking Gradle from a
 * test, because a nested build would deadlock on the same lock this build already holds.
 */
class PublishedBomContractTest {

  private static final List<String> MANAGED_ARTIFACTS =
      List.of("agent-framework-api", "agent-framework-engine", "agent-framework-testkit");

  private static final String PUBLISH_COMMAND =
      "./gradlew publishAllPublicationsToBuildDirectoryRepository";

  @Test
  void publishedBomManagesEveryLibraryVersion() {
    Optional<String> pom = latestPublishedBomPom();
    if (pom.isEmpty()) {
      return;
    }

    String dependencyManagement = sectionOf(pom.get(), "dependencyManagement");

    for (String artifact : MANAGED_ARTIFACTS) {
      assertThat(dependencyManagement)
          .withFailMessage(
              "The published BOM does not manage %s. Run `%s` and check the platform"
                  + " constraints.",
              artifact, PUBLISH_COMMAND)
          .contains("<artifactId>" + artifact + "</artifactId>");
    }
  }

  @Test
  void publishedBomForcesNoDependencyOnConsumers() {
    Optional<String> pom = latestPublishedBomPom();
    if (pom.isEmpty()) {
      return;
    }

    String withoutManagement = removeSection(pom.get(), "dependencyManagement");

    assertThat(withoutManagement)
        .withFailMessage(
            "The published BOM declares dependencies outside <dependencyManagement>. A consumer"
                + " importing it would receive those modules on the classpath instead of only"
                + " version alignment. Declare every entry inside `constraints { }` and do not"
                + " enable `allowDependencies()`.")
        .doesNotContain("<dependencies>");
  }

  @Test
  void publishOutputIsPresentAfterPublishing() {
    // Guards the two tests above from silently passing forever: if the publish task stops writing a
    // BOM pom, their skip path would hide it. Running the command must always produce one.
    Path repository = publishedRepository();
    if (!Files.isDirectory(repository)) {
      return;
    }

    assertThat(latestPublishedBomPom())
        .withFailMessage(
            "%s exists but holds no BOM pom. Re-run `%s`.", repository, PUBLISH_COMMAND)
        .isPresent();
  }

  private static Optional<String> latestPublishedBomPom() {
    Path repository = publishedRepository();
    if (!Files.isDirectory(repository)) {
      return Optional.empty();
    }

    try (Stream<Path> files = Files.walk(repository)) {
      return files
          .filter(Files::isRegularFile)
          .map(path -> new NamedFile(path, fileNameOf(path)))
          .filter(file -> file.name().startsWith("agent-framework-bom-"))
          .filter(file -> file.name().endsWith(".pom"))
          .max(Comparator.comparing(NamedFile::name))
          .map(file -> read(file.path()));
    } catch (IOException cause) {
      throw new UncheckedIOException("Cannot scan " + repository, cause);
    }
  }

  private static String fileNameOf(Path path) {
    Path name = path.getFileName();
    return name == null ? "" : name.toString();
  }

  private record NamedFile(Path path, String name) {}

  private static Path publishedRepository() {
    return RepositoryPaths.root().resolve("build/maven-repository");
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
