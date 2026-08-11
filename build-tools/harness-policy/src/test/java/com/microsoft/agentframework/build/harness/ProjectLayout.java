package com.microsoft.agentframework.build.harness;

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

  private static final Pattern PROJECT_DEPENDENCY =
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
   * Returns the project dependencies declared in a build file.
   *
   * @param gradlePath the Gradle project path
   * @return the Gradle paths this project depends on
   */
  static List<String> projectDependenciesOf(String gradlePath) {
    Matcher matcher = PROJECT_DEPENDENCY.matcher(buildFileText(gradlePath));
    List<String> dependencies = new ArrayList<>();
    while (matcher.find()) {
      dependencies.add(matcher.group(1));
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
