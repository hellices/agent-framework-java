package io.github.hellices.agentframework.build.harness;

import java.nio.file.Files;
import java.nio.file.Path;

/** Locates the repository root from any Gradle project working directory. */
public final class RepositoryPaths {

  private RepositoryPaths() {}

  /**
   * Returns the repository root directory.
   *
   * @return the closest ancestor directory that contains both {@code settings.gradle.kts} and
   *     {@code gradle/libs.versions.toml}
   * @throws IllegalStateException when no ancestor directory contains both markers
   */
  public static Path root() {
    Path candidate = Path.of("").toAbsolutePath();
    while (candidate != null) {
      boolean hasSettings = Files.isRegularFile(candidate.resolve("settings.gradle.kts"));
      boolean hasCatalog = Files.isRegularFile(candidate.resolve("gradle/libs.versions.toml"));
      if (hasSettings && hasCatalog) {
        return candidate;
      }
      candidate = candidate.getParent();
    }
    throw new IllegalStateException(
        "Cannot locate the repository root from the working directory.");
  }
}
