package io.github.hellices.agentframework.build.harness;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourcePackagesTest {

  @TempDir Path repository;

  @Test
  void discoversEverySourceRootWithoutDroppingBuildPackageSegments() throws Exception {
    Path harnessSource =
        write(
            "module/src/test/java/io/github/hellices/agentframework/build/harness/Policy.java",
            "package io.github.hellices.agentframework.build.harness;");
    Path unregisteredSource =
        write(
            "future-module/src/main/kotlin/io/github/hellices/agentframework/future/Future.kt",
            "package io.github.hellices.agentframework.future");
    Path buildScript =
        write(
            "future-module/build.gradle.kts",
            "import io.github.hellices.agentframework.build.logic.RepositoryPolicyInputs");
    Path nestedCanonicalSource =
        write(
            "future-module/src/generated/src/main/java/"
                + "io/github/hellices/agentframework/future/Nested.java",
            "package io.github.hellices.agentframework.future;");
    write(
        ".worktrees/other/src/main/java/io/github/hellices/agentframework/Other.java",
        "package io.github.hellices.agentframework;");
    write(
        "module/build/src/main/java/io/github/hellices/agentframework/Generated.java",
        "package io.github.hellices.agentframework;");

    assertThat(SourcePackages.discover(repository))
        .containsExactlyInAnyOrder(
            harnessSource, unregisteredSource, buildScript, nestedCanonicalSource);
  }

  @Test
  void detectsPackageAndMicrosoftReferences() {
    String source =
        "package io.github.hellices.agentframework.example;\n"
            + "import "
            + "com."
            + "microsoft.agentframework.OfficialLookingType;\n";

    assertThat(SourcePackages.packageName(source))
        .hasValue("io.github.hellices.agentframework.example");
    assertThat(SourcePackages.referencesMicrosoftNamespace(source)).isTrue();
    assertThat(SourcePackages.packageName("final class PackageLess {}")).isEmpty();
  }

  @Test
  void reportsForbiddenNamespaceInAnUnregisteredSourceRoot() throws Exception {
    Path source =
        write(
            "unregistered/src/main/java/example/Bad.java",
            "package " + "com." + "microsoft.agentframework.bad;\n" + "final class Bad {}");

    assertThat(SourcePackages.violations(repository))
        .containsExactly(
            new SourcePackages.Violation(
                source, "package must start with io.github.hellices.agentframework"),
            new SourcePackages.Violation(source, SourcePackages.microsoftReferenceProblem()));
  }

  @Test
  void checksBuildScriptsWithoutRequiringAPackageDeclaration() throws Exception {
    Path buildScript =
        write(
            "future-module/build.gradle.kts",
            "import " + "com." + "microsoft.agentframework.build.logic.RepositoryPolicyInputs");

    assertThat(SourcePackages.violations(repository))
        .containsExactly(
            new SourcePackages.Violation(buildScript, SourcePackages.microsoftReferenceProblem()));
  }

  @Test
  void reportsTheSourcePathWhenUtf8DecodingFails() throws Exception {
    Path source = repository.resolve("module/src/main/java/example/Broken.java");
    Path parent = source.getParent();
    if (parent == null) {
      throw new IllegalStateException("Test source has no parent: " + source);
    }
    Files.createDirectories(parent);
    Files.write(source, new byte[] {(byte) 0xC3, 0x28});

    assertThat(SourcePackages.violations(repository))
        .singleElement()
        .satisfies(
            violation -> {
              assertThat(violation.source()).isEqualTo(source);
              assertThat(violation.problem()).startsWith("source must be readable as UTF-8:");
            });
  }

  private Path write(String relativePath, String contents) throws Exception {
    Path target = repository.resolve(relativePath);
    Path parent = target.getParent();
    if (parent == null) {
      throw new IllegalArgumentException("Test source must have a parent: " + relativePath);
    }
    Files.createDirectories(parent);
    return Files.writeString(target, contents);
  }
}
