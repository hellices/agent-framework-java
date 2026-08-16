package io.github.hellices.agentframework.build.harness;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads Gradle project registration and build files for repository policy tests. */
final class ProjectLayout {

  private static final Pattern INCLUDE =
      Pattern.compile("^\\s*include\\(\"(:[A-Za-z0-9:_-]+)\"\\)\\s*$", Pattern.MULTILINE);

  private static final Pattern CONFIGURATION =
      Pattern.compile("^\\s*([A-Za-z][A-Za-z0-9]*)\\s*\\(");

  private static final Pattern PROJECT_REFERENCE =
      Pattern.compile("project\\(\"(:[A-Za-z0-9:_-]+)\"\\)");

  private ProjectLayout() {}

  /**
   * Returns every Gradle project path registered in {@code settings.gradle.kts}.
   *
   * @return registered project paths such as {@code :agent-framework-api}
   */
  static List<String> includedProjects() {
    Matcher matcher = INCLUDE.matcher(read(RepositoryPaths.root().resolve("settings.gradle.kts")));
    List<String> projects = new ArrayList<>();
    while (matcher.find()) {
      projects.add(matcher.group(1));
    }
    return List.copyOf(projects);
  }

  /**
   * Returns the directory that backs a Gradle project path.
   *
   * @param gradlePath the Gradle project path
   * @return the project directory
   */
  static Path projectDirectory(String gradlePath) {
    return RepositoryPaths.root().resolve(gradlePath.substring(1).replace(':', '/'));
  }

  /**
   * Returns the build file of a Gradle project.
   *
   * @param gradlePath the Gradle project path
   * @return the build file path
   */
  static Path buildFile(String gradlePath) {
    return projectDirectory(gradlePath).resolve("build.gradle.kts");
  }

  /**
   * Returns the build file contents of a Gradle project.
   *
   * @param gradlePath the Gradle project path
   * @return the build file text
   */
  static String buildFileText(String gradlePath) {
    return read(buildFile(gradlePath));
  }

  /**
   * Returns the project dependencies a build file declares on a production configuration.
   *
   * <p>Test configurations are excluded on purpose. A test-only project dependency does not reach a
   * consumer, so treating it as a shipped dependency would force the allowlist to permit a
   * production dependency in order to permit a test.
   *
   * @param gradlePath the Gradle project path
   * @return the Gradle paths this project ships against
   */
  static List<String> projectDependenciesOf(String gradlePath) {
    return projectDependenciesIn(buildFileText(gradlePath));
  }

  /**
   * Returns the project dependencies a build file declares on a test configuration.
   *
   * @param gradlePath the Gradle project path
   * @return the Gradle paths this project compiles or runs its tests against
   */
  static List<String> testProjectDependenciesOf(String gradlePath) {
    return testProjectDependenciesIn(buildFileText(gradlePath));
  }

  static List<String> projectDependenciesIn(String buildFileText) {
    return dependenciesIn(buildFileText, false);
  }

  static List<String> testProjectDependenciesIn(String buildFileText) {
    return dependenciesIn(buildFileText, true);
  }

  private static List<String> dependenciesIn(String buildFileText, boolean testConfigurations) {
    List<String> dependencies = new ArrayList<>();
    for (String line : buildFileText.split("\\R", -1)) {
      Matcher reference = PROJECT_REFERENCE.matcher(line);
      if (!reference.find()) {
        continue;
      }
      Matcher configuration = CONFIGURATION.matcher(line);
      // "project(" and "platform(" also match the configuration shape, so a declaration whose
      // configuration sits on an earlier line would otherwise be classified as a configuration
      // called "project" and silently escape the allowlist.
      if (!configuration.find()
          || "project".equals(configuration.group(1))
          || "platform".equals(configuration.group(1))) {
        throw new IllegalStateException(
            "Cannot read the configuration of the project dependency "
                + reference.group(1)
                + ". Declare a project dependency on one line so the module composition policy can"
                + " tell a production dependency from a test dependency.");
      }
      boolean isTest = configuration.group(1).startsWith("test");
      if (isTest == testConfigurations) {
        dependencies.add(reference.group(1));
        while (reference.find()) {
          dependencies.add(reference.group(1));
        }
      }
    }
    return List.copyOf(dependencies);
  }

  private static String read(Path path) {
    try {
      return Files.readString(path, StandardCharsets.UTF_8);
    } catch (IOException cause) {
      throw new UncheckedIOException("Cannot read " + path, cause);
    }
  }
}
