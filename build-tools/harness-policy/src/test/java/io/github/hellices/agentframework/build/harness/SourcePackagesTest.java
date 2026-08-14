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
    Path integrationTestSource =
        write(
            "future-module/src/integrationTest/java/"
                + "io/github/hellices/agentframework/future/Integration.java",
            "package io.github.hellices.agentframework.future;");
    Path buildSourceSet =
        write(
            "future-module/src/build/java/"
                + "io/github/hellices/agentframework/future/BuildSource.java",
            "package io.github.hellices.agentframework.future;");
    Path outSourceSet =
        write(
            "future-module/src/out/kotlin/"
                + "io/github/hellices/agentframework/future/OutSource.kt",
            "package io.github.hellices.agentframework.future");
    Path kotlinScript =
        write(
            "scripts/check.kts",
            "import io.github.hellices.agentframework.build.logic.RepositoryPolicyInputs");
    write(
        ".worktrees/other/src/main/java/io/github/hellices/agentframework/Other.java",
        "package io.github.hellices.agentframework;");
    write(
        ".superpowers/notes.md",
        "Legacy " + "com." + "microsoft.agentframework reference in a local artifact.");
    write(
        "module/build/src/main/java/io/github/hellices/agentframework/Generated.java",
        "package io.github.hellices.agentframework;");

    assertThat(SourcePackages.discover(repository))
        .containsExactlyInAnyOrder(
            harnessSource,
            unregisteredSource,
            buildScript,
            nestedCanonicalSource,
            integrationTestSource,
            buildSourceSet,
            outSourceSet,
            kotlinScript);
  }

  @Test
  void reportsForbiddenNamespaceOutsideCanonicalSourceRoots() throws Exception {
    Path source =
        write(
            "scripts/Legacy.java",
            "package " + "com." + "microsoft.agentframework.legacy;\n" + "final class Legacy {}");

    assertThat(SourcePackages.violations(repository))
        .containsExactly(
            new SourcePackages.Violation(source, SourcePackages.microsoftReferenceProblem()));
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
    assertThat(
            SourcePackages.referencesMicrosoftNamespace(
                "import " + "com." + "microsoft.agentframeworkish.Unrelated;"))
        .isFalse();
    assertThat(SourcePackages.packageName("final class PackageLess {}")).isEmpty();
  }

  @Test
  void ignoresPackageLikeTextInsideCommentsAndTextBlocks() {
    String source =
        """
        /*
        package com.microsoft.agentframework.comment;
        */
        package io.github.hellices.agentframework.example;
        final class Example {
          String text = \"\"\"
              package com.microsoft.agentframework.text;
              \"\"\";
        }
        """;

    assertThat(SourcePackages.packageName(source))
        .hasValue("io.github.hellices.agentframework.example");
    assertThat(SourcePackages.referencesMicrosoftNamespace(source)).isFalse();
    assertThat(
            SourcePackages.referencesMicrosoftNamespace(
                "/* Path: " + "com/" + "microsoft/agentframework/comment */"))
        .isFalse();
  }

  @Test
  void anUnterminatedSingleLineLiteralDoesNotHideFollowingReferences() {
    String source =
        "package io.github.hellices.agentframework.example;\n"
            + "String broken = \"unterminated\n"
            + "import "
            + "com."
            + "microsoft.agentframework.Hidden;\n";

    assertThat(SourcePackages.referencesMicrosoftNamespace(source)).isTrue();
  }

  @Test
  void detectsRetiredSlashSeparatedNamespaceInsideAString() {
    String source =
        "package io.github.hellices.agentframework.example;\n"
            + "String path = \""
            + "com/"
            + "microsoft/agentframework/internal"
            + "\";\n";

    assertThat(SourcePackages.referencesMicrosoftNamespace(source)).isTrue();
    assertThat(
            SourcePackages.referencesMicrosoftNamespace(
                "String path = \"" + "com/" + "microsoft/agentframeworkish\";"))
        .isFalse();
    assertThat(
            SourcePackages.referencesMicrosoftNamespace(
                "String url = \"https://example." + "com/" + "microsoft/agentframework/status\";"))
        .isFalse();
  }

  @Test
  void escapedTextBlockDelimiterDoesNotExposeDottedTextOrHideSlashPaths() {
    String dottedText =
        "String text = \"\"\"\n"
            + "  escaped delimiter: \\\"\"\"\n"
            + "  import "
            + "com."
            + "microsoft.agentframework.Hidden;\n"
            + "\"\"\";\n";
    String slashPath =
        "String text = \"\"\"\n"
            + "  escaped delimiter: \\\"\"\"\n"
            + "  // "
            + "com/"
            + "microsoft/agentframework/internal\n"
            + "\"\"\";\n";

    assertThat(SourcePackages.referencesMicrosoftNamespace(dottedText)).isFalse();
    assertThat(SourcePackages.referencesMicrosoftNamespace(slashPath)).isTrue();
  }

  @Test
  void checksStandaloneKotlinScriptsWithoutRequiringAPackageDeclaration() throws Exception {
    Path script =
        write(
            "scripts/legacy.kts", "val path = \"" + "com/" + "microsoft/agentframework/internal\"");

    assertThat(SourcePackages.violations(repository))
        .containsExactly(
            new SourcePackages.Violation(script, SourcePackages.microsoftReferenceProblem()));
  }

  @Test
  void kotlinRawStringDelimiterClosesEvenWhenPrecededByABackslash() throws Exception {
    Path source =
        write(
            "scripts/RawString.kt",
            "val text = \"\"\"value\\\"\"\";\n"
                + "import "
                + "com."
                + "microsoft.agentframework.Hidden\n");

    assertThat(SourcePackages.violations(repository))
        .containsExactly(
            new SourcePackages.Violation(source, SourcePackages.microsoftReferenceProblem()));
  }

  @Test
  void ignoresForbiddenNamespaceInsideNestedKotlinComments() throws Exception {
    write(
        "scripts/NestedComment.kt",
        "/* outer /* nested */ "
            + "com."
            + "microsoft.agentframework.Commented */\n"
            + "val valid = true\n");

    assertThat(SourcePackages.violations(repository)).isEmpty();
  }

  @Test
  void ignoresForbiddenNamespaceInsideGroovyMultilineStrings() throws Exception {
    write(
        "module/legacy.gradle",
        "def text = '''\n" + "com." + "microsoft.agentframework.Text\n" + "'''\n");

    assertThat(SourcePackages.violations(repository)).isEmpty();
  }

  @Test
  void detectsForbiddenNamespaceInsideKotlinAndGroovyInterpolation() throws Exception {
    Path kotlin =
        write(
            "scripts/Interpolation.kt",
            "val value = \"${" + "com." + "microsoft.agentframework.Legacy.VALUE" + "}\"\n");
    Path groovy =
        write(
            "module/interpolation.gradle",
            "def value = \"${" + "com." + "microsoft.agentframework.Legacy.VALUE" + "}\"\n");

    assertThat(SourcePackages.violations(repository))
        .containsExactly(
            new SourcePackages.Violation(groovy, SourcePackages.microsoftReferenceProblem()),
            new SourcePackages.Violation(kotlin, SourcePackages.microsoftReferenceProblem()));
  }

  @Test
  void ignoresDottedTextInsideGroovyLiteralForms() throws Exception {
    write(
        "module/literals.gradle",
        "def slashy = /"
            + "com."
            + "microsoft.agentframework.Slashy/\n"
            + "def dollarSlashy = $/"
            + "com."
            + "microsoft.agentframework.DollarSlashy/$\n"
            + "def triple = \"\"\"inside \\\"\"\" "
            + "com."
            + "microsoft.agentframework.Triple still literal\"\"\"\n"
            + "def literal() { return /"
            + "com."
            + "microsoft.agentframework.Returned/ }\n"
            + "def pattern = ~/"
            + "com."
            + "microsoft.agentframework.Pattern/\n"
            + "def combined = prefix + /"
            + "com."
            + "microsoft.agentframework.Combined/\n"
            + "def single = '$"
            + "com."
            + "microsoft.agentframework.Single'\n");

    assertThat(SourcePackages.violations(repository)).isEmpty();
  }

  @Test
  void ignoresDottedStringLiteralInsideInterpolation() throws Exception {
    write(
        "scripts/LiteralInterpolation.kt",
        "val value = \"${\"" + "com." + "microsoft.agentframework.Text" + "\"}\"\n");

    assertThat(SourcePackages.violations(repository)).isEmpty();
  }

  @Test
  void kotlinRawStringBackslashDoesNotEscapeInterpolation() throws Exception {
    Path source =
        write(
            "scripts/RawInterpolation.kt",
            "val value = \"\"\"\\${"
                + "com."
                + "microsoft.agentframework.Legacy.VALUE"
                + "}\"\"\"\n");

    assertThat(SourcePackages.violations(repository))
        .containsExactly(
            new SourcePackages.Violation(source, SourcePackages.microsoftReferenceProblem()));
  }

  @Test
  void detectsGroovyUnbracedInterpolationAcrossGStringForms() throws Exception {
    Path source =
        write(
            "module/gstrings.gradle",
            "def regular = \"$"
                + "com."
                + "microsoft.agentframework.Regular\"\n"
                + "def triple = \"\"\"$"
                + "com."
                + "microsoft.agentframework.Triple\"\"\"\n"
                + "def slashy = /$"
                + "com."
                + "microsoft.agentframework.Slashy/\n"
                + "def dollarSlashy = $/$"
                + "com."
                + "microsoft.agentframework.DollarSlashy/$\n");

    assertThat(SourcePackages.violations(repository))
        .containsExactly(
            new SourcePackages.Violation(source, SourcePackages.microsoftReferenceProblem()));
  }

  @Test
  void slashPathCommentsInsideInterpolationRemainIgnored() throws Exception {
    write(
        "scripts/CommentedInterpolation.kt",
        "val value = \"${ /* " + "com/" + "microsoft/agentframework/comment */ 1 }\"\n");

    assertThat(SourcePackages.violations(repository)).isEmpty();
  }

  @Test
  void slashPathsInsideDollarSlashyStringsRemainVisible() throws Exception {
    Path source =
        write(
            "module/dollar-slashy.gradle",
            "def path = $/prefix // " + "com/" + "microsoft/agentframework/internal/$\n");

    assertThat(SourcePackages.violations(repository))
        .containsExactly(
            new SourcePackages.Violation(source, SourcePackages.microsoftReferenceProblem()));
  }

  @Test
  void backslashDoesNotEscapeInterpolationInsideGroovySlashyStrings() throws Exception {
    Path source =
        write(
            "module/slashy-interpolation.gradle",
            "def value = /\\${" + "com." + "microsoft.agentframework.Legacy.VALUE" + "}/\n");

    assertThat(SourcePackages.violations(repository))
        .containsExactly(
            new SourcePackages.Violation(source, SourcePackages.microsoftReferenceProblem()));
  }

  @Test
  void escapedDollarSlashyDelimiterDoesNotHideFollowingSlashPath() throws Exception {
    Path source =
        write(
            "module/escaped-dollar-slashy.gradle",
            "def path = $/before $/$ // " + "com/" + "microsoft/agentframework/internal /$\n");

    assertThat(SourcePackages.violations(repository))
        .containsExactly(
            new SourcePackages.Violation(source, SourcePackages.microsoftReferenceProblem()));
  }

  @Test
  void escapedDollarBeforeRealDelimiterDoesNotHideFollowingCode() throws Exception {
    Path source =
        write(
            "module/dollar-parity.gradle",
            "def value = $/literal$$/$\n"
                + "import "
                + "com."
                + "microsoft.agentframework.Hidden\n");

    assertThat(SourcePackages.violations(repository))
        .containsExactly(
            new SourcePackages.Violation(source, SourcePackages.microsoftReferenceProblem()));
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
  void checksConfigurationAndDocumentationAssetsAsRawText() throws Exception {
    Path catalog =
        write(
            "gradle/libs.versions.toml",
            "legacy = \"" + "com." + "microsoft.agentframework.Legacy\"");
    Path workflow =
        write(
            ".github/workflows/legacy.yml",
            "path: " + "com/" + "microsoft/agentframework/internal");
    Path documentation =
        write("docs/legacy.md", "Do not use `" + "com." + "microsoft.agentframework.Legacy`.");

    assertThat(SourcePackages.violations(repository))
        .containsExactly(
            new SourcePackages.Violation(workflow, SourcePackages.microsoftReferenceProblem()),
            new SourcePackages.Violation(documentation, SourcePackages.microsoftReferenceProblem()),
            new SourcePackages.Violation(catalog, SourcePackages.microsoftReferenceProblem()));
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

  @Test
  void inspectReturnsSourcesAndViolationsFromOneReport() throws Exception {
    Path good =
        write(
            "module/src/main/java/io/github/hellices/agentframework/Good.java",
            "package io.github.hellices.agentframework;");
    Path bad =
        write(
            "module/src/jmh/java/example/Bad.java",
            "package " + "com." + "microsoft.agentframework.bad;");

    SourcePackages.Report report = SourcePackages.inspect(repository);

    assertThat(report.sources()).containsExactlyInAnyOrder(good, bad);
    assertThat(report.violations())
        .containsExactly(
            new SourcePackages.Violation(
                bad, "package must start with io.github.hellices.agentframework"),
            new SourcePackages.Violation(bad, SourcePackages.microsoftReferenceProblem()));
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
