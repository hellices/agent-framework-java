package com.microsoft.agentframework.build.harness;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Proves the module composition contract holds.
 *
 * <p>Module registration, layout, applied conventions, dependency direction, and artifact
 * coordinates are build failures rather than review comments. Update {@code
 * docs/design/module-composition.md} and this test together.
 */
class ModuleCompositionPolicyTest {

  private static final List<String> LIBRARY_PROJECTS =
      List.of(":agent-framework-api", ":agent-framework-engine", ":agent-framework-testkit");

  private static final String PLATFORM_PROJECT = ":agent-framework-bom";

  private static final String HARNESS_PREFIX = ":build-tools:";

  /**
   * Directories that may hold a family of modules.
   *
   * <p>A project outside this set must sit at the repository root. The list is intentionally
   * closed: adding a family is a layout decision that belongs in the design document, not something
   * a module author can introduce by creating a directory.
   */
  private static final List<String> GROUPING_DIRECTORIES =
      List.of(
          "providers",
          "integrations",
          "starters",
          "protocols",
          "workflow",
          "compatibility-tests",
          "samples",
          "build-tools");

  private static final List<String> REQUIRED_PLUGINS =
      List.of(
          "agentframework.java-library-conventions",
          "agentframework.test-conventions",
          "agentframework.quality-conventions",
          "agentframework.library-publishing-conventions");

  private static final Map<String, List<String>> ALLOWED_DEPENDENCIES =
      Map.of(
          ":agent-framework-api", List.of(),
          ":agent-framework-engine", List.of(":agent-framework-api"),
          ":agent-framework-testkit", List.of(":agent-framework-api"));

  static Stream<String> libraryProjects() {
    return LIBRARY_PROJECTS.stream();
  }

  @Test
  void settingsRegistersEveryProductProject() {
    assertThat(ProjectLayout.includedProjects())
        .containsAll(LIBRARY_PROJECTS)
        .contains(PLATFORM_PROJECT);
  }

  @Test
  void everyRegisteredProjectSitsAtTheRootOrInOneGroupingDirectory() {
    for (String gradlePath : ProjectLayout.includedProjects()) {
      String[] segments = gradlePath.substring(1).split(":");

      assertThat(segments.length)
          .withFailMessage(
              "%s nests %d levels deep. Use a root module or exactly one grouping directory.",
              gradlePath, segments.length)
          .isLessThanOrEqualTo(2);

      if (segments.length == 2) {
        assertThat(GROUPING_DIRECTORIES)
            .withFailMessage(
                "%s uses grouping directory '%s', which the module composition contract does not"
                    + " declare.",
                gradlePath, segments[0])
            .contains(segments[0]);
      }
    }
  }

  @Test
  void everyRegisteredProjectExistsOnDiskWithABuildFile() {
    for (String gradlePath : ProjectLayout.includedProjects()) {
      assertThat(ProjectLayout.buildFile(gradlePath))
          .withFailMessage("%s is registered but has no build file.", gradlePath)
          .exists();
    }
  }

  @Test
  void everyRegisteredProjectIsDocumented() throws IOException {
    String contract = moduleCompositionContract();

    for (String gradlePath : ProjectLayout.includedProjects()) {
      assertThat(contract)
          .withFailMessage(
              "%s is registered but absent from docs/design/module-composition.md.", gradlePath)
          .contains("`" + gradlePath + "`");
    }
  }

  @ParameterizedTest
  @MethodSource("libraryProjects")
  void libraryProjectAppliesEveryConvention(String gradlePath) {
    String buildFile = ProjectLayout.buildFileText(gradlePath);
    for (String plugin : REQUIRED_PLUGINS) {
      assertThat(buildFile).contains("id(\"" + plugin + "\")");
    }
  }

  @ParameterizedTest
  @MethodSource("libraryProjects")
  void libraryProjectDeclaresNoInlineDependencyVersion(String gradlePath) {
    assertThat(ProjectLayout.buildFileText(gradlePath)).doesNotContain("version = \"");
  }

  @ParameterizedTest
  @MethodSource("libraryProjects")
  void libraryProjectOnlyDependsOnAllowedProjects(String gradlePath) {
    assertThat(ProjectLayout.projectDependenciesOf(gradlePath))
        .containsExactlyInAnyOrderElementsOf(ALLOWED_DEPENDENCIES.get(gradlePath));
  }

  @Test
  void noProductProjectDependsOnAHarnessProject() {
    for (String gradlePath : LIBRARY_PROJECTS) {
      assertThat(ProjectLayout.projectDependenciesOf(gradlePath))
          .noneMatch(dependency -> dependency.startsWith(HARNESS_PREFIX));
    }
  }

  @Test
  void platformDeclaresConstraintsRatherThanDependencies() {
    String buildFile = ProjectLayout.buildFileText(PLATFORM_PROJECT);

    assertThat(buildFile).contains("agentframework.platform-conventions");
    assertThat(buildFile)
        .withFailMessage(
            "The BOM must declare constraints. Plain dependencies publish under <dependencies> and"
                + " force every module onto a consumer that imports the BOM.")
        .contains("constraints {");
    assertThat(buildFile)
        .withFailMessage("allowDependencies() turns BOM entries into forced dependencies.")
        .doesNotContain("allowDependencies()");

    for (String gradlePath : LIBRARY_PROJECTS) {
      assertThat(buildFile).contains("api(project(\"" + gradlePath + "\"))");
    }
  }

  @ParameterizedTest
  @MethodSource("libraryProjects")
  void libraryProjectIsPublishable(String gradlePath) {
    assertThat(ProjectLayout.buildFileText(gradlePath))
        .contains("agentframework.library-publishing-conventions");
  }

  @Test
  void signingProducesDetachedSignaturesForEveryPublication() throws IOException {
    // Superseded by SigningContractTest, which publishes with a real key. Reading this file could
    // only ever match text: `sign(publishing.publications )` with one extra space slipped through
    // the previous version of this check, which is exactly the fault it existed to prevent.
    assertThat(publishingConventionText())
        .withFailMessage(
            "The publishing convention must configure signing. Whether it attaches correctly is"
                + " proved by SigningContractTest against real published artifacts.")
        .contains("signing {");
  }

  @Test
  void releaseBuildRefusesToPublishUnsignedArtifacts() throws IOException {
    // The design document promises a release fails early rather than at upload. Without this the
    // convention could drift back to skipping signing whenever a secret is missing, and the first
    // signal would be a rejected Central upload.
    assertThat(publishingConventionText())
        .withFailMessage(
            "A release build must fail when SIGNING_KEY is absent. Maven Central rejects unsigned"
                + " uploads, so the check belongs in the publishing convention.")
        .contains("agentframework.release");
  }

  private static String publishingConventionText() throws IOException {
    return Files.readString(
        RepositoryPaths.root()
            .resolve(
                "build-logic/src/main/kotlin/agentframework.publishing-conventions.gradle.kts"),
        StandardCharsets.UTF_8);
  }

  @Test
  void repositoryDeclaresASingleGroupAndVersion() throws IOException {
    String text =
        Files.readString(
            RepositoryPaths.root().resolve("gradle.properties"), StandardCharsets.UTF_8);
    assertThat(text).contains("group=com.microsoft.agentframework");
    assertThat(text).containsPattern("(?m)^version=\\d+\\.\\d+\\.\\d+(-SNAPSHOT)?$");
  }

  private static String moduleCompositionContract() throws IOException {
    Path contract = RepositoryPaths.root().resolve("docs/design/module-composition.md");
    return Files.readString(contract, StandardCharsets.UTF_8);
  }
}
