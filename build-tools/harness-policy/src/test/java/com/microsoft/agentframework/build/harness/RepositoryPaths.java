package com.microsoft.agentframework.build.harness;

import java.nio.file.Files;
import java.nio.file.Path;

final class RepositoryPaths {
  private RepositoryPaths() {}

  static Path root() {
    Path candidate = Path.of("").toAbsolutePath();
    while (candidate != null) {
      if (Files.isRegularFile(candidate.resolve("AGENTS.md"))
          && Files.isDirectory(candidate.resolve("docs/upstream"))) {
        return candidate;
      }
      candidate = candidate.getParent();
    }
    throw new IllegalStateException(
        "Cannot locate repository root from " + Path.of("").toAbsolutePath());
  }
}
