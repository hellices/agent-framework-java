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
 * <p>Module registration, applied conventions, dependency direction, and artifact coordinates are
 * build failures rather than review comments. Update {@code docs/design/module-composition.md} and
 * this test together.
 */
class ModuleCompositionPolicyTest {

  private static final List<String> LIBRARY_PROJECTS =
      List.of(":agent-framework-api", ":agent-framework-engine", ":agent-framework-testkit");

  private static final String PLATFORM_PROJECT = ":agent-framework-bom";

  private static final List<String> REQUIRED_PLUGINS =
      List.of(
          "agentframework.java-library-conventions",
          "agentframework.test-conventions",
          "agentframework.quality-conventions");

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
  void noProductProjectDependsOnTheHarnessPolicyProject() {
    for (String gradlePath : LIBRARY_PROJECTS) {
      assertThat(ProjectLayout.projectDependenciesOf(gradlePath))
          .doesNotContain(":build-tools:harness-policy");
    }
  }

  @Test
  void platformProjectUsesJavaPlatformAndListsEveryLibrary() {
    String buildFile = ProjectLayout.buildFileText(PLATFORM_PROJECT);
    assertThat(buildFile).contains("agentframework.platform-conventions");
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
  void repositoryDeclaresASingleGroupAndVersion() throws IOException {
    String text =
        Files.readString(
            RepositoryPaths.root().resolve("gradle.properties"), StandardCharsets.UTF_8);
    assertThat(text).contains("group=com.microsoft.agentframework");
    assertThat(text).containsPattern("(?m)^version=\\d+\\.\\d+\\.\\d+(-SNAPSHOT)?$");
  }

  @Test
  void documentedProjectsMatchRegisteredProjects() throws IOException {
    Path contract = RepositoryPaths.root().resolve("docs/design/module-composition.md");
    String text = Files.readString(contract, StandardCharsets.UTF_8);
    for (String gradlePath : LIBRARY_PROJECTS) {
      assertThat(text).contains("`" + gradlePath + "`");
    }
    assertThat(text).contains("`" + PLATFORM_PROJECT + "`");
  }
}
