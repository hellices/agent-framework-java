package com.microsoft.agentframework.build.harness;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class BuildContractPolicyTest {

  private static final String DISTRIBUTION_SHA256 =
      "84fbba45c7f4c64abc77460e1c00f541e9f960e3c7ed2538f1ede19eacd873ae";

  private static final String WRAPPER_JAR_SHA256 =
      "7a9ce74cff467ca1bf60a4fcd9f05185acceda4d0f382434d393e17864262c5d";

  private static final String QUALITY_CONVENTION =
      "build-logic/src/main/kotlin/agentframework.quality-conventions.gradle.kts";

  private static final String JAVA_LIBRARY_CONVENTION =
      "build-logic/src/main/kotlin/agentframework.java-library-conventions.gradle.kts";

  private static final String TEST_CONVENTION =
      "build-logic/src/main/kotlin/agentframework.test-conventions.gradle.kts";

  private static final List<String> FORBIDDEN_MAVEN_NAMES =
      List.of("pom.xml", "mvnw", "mvnw.cmd", ".mvn");

  private static final Set<String> IGNORED_DIRECTORIES =
      Set.of(".git", "build", ".gradle", ".kotlin", ".worktrees");

  @Test
  void wrapperPinsGradle970WithAVerifiedDistribution() throws IOException {
    String properties = read("gradle/wrapper/gradle-wrapper.properties");

    assertThat(properties).contains("gradle-9.7.0-bin.zip");
    assertThat(properties).contains("distributionSha256Sum=" + DISTRIBUTION_SHA256);
    assertThat(properties).contains("validateDistributionUrl=true");
  }

  @Test
  void wrapperJarMatchesThePinnedChecksum() throws IOException {
    Path wrapperJar = RepositoryPaths.root().resolve("gradle/wrapper/gradle-wrapper.jar");
    String recorded = read("gradle/wrapper/gradle-wrapper.jar.sha256").trim();

    assertThat(recorded).isEqualTo(WRAPPER_JAR_SHA256);
    assertThat(sha256(wrapperJar)).isEqualTo(WRAPPER_JAR_SHA256);
  }

  @Test
  void versionCatalogPinsEveryToolVersion() throws IOException {
    String catalog = read("gradle/libs.versions.toml");

    // The contract is that every tool carries an exact version, not that it carries one particular
    // version. Asserting the literal values turned a legitimate dependency upgrade into a policy
    // violation, which is the opposite of what this test is for.
    for (String tool :
        List.of(
            "assertj",
            "checkstyle",
            "googleJavaFormat",
            "jackson",
            "jacoco",
            "jsonSchemaValidator",
            "junit",
            "pmd",
            "spotbugsPlugin",
            "spotbugsTool",
            "spotless")) {
      assertThat(catalog)
          .withFailMessage(
              "%s must be pinned to an exact version in the catalog. A range or a missing entry"
                  + " makes a build depend on when it ran.",
              tool)
          .containsPattern("(?m)^" + tool + " = \"[0-9][0-9A-Za-z.+-]*\"$");
    }
  }

  @Test
  void javaBaselineIsPinnedToRelease17() throws IOException {
    String conventions = read(JAVA_LIBRARY_CONVENTION);

    assertThat(conventions).contains("JavaLanguageVersion.of(17)");
    assertThat(conventions).contains("options.release.set(17)");
    assertThat(conventions).contains("-Werror");
  }

  @Test
  void formatterRunsOnlyInsideTheQualityConvention() throws IOException {
    String quality = read(QUALITY_CONVENTION);
    String javaLibrary = read(JAVA_LIBRARY_CONVENTION);
    String testConvention = read(TEST_CONVENTION);

    assertThat(quality).contains("id(\"com.diffplug.spotless\")");
    assertThat(quality).contains("googleJavaFormat(");
    assertThat(javaLibrary).doesNotContain("spotless");
    assertThat(testConvention).doesNotContain("spotless");
  }

  @Test
  void toolchainProvisioningIsDisabled() throws IOException {
    String properties = read("gradle.properties");
    String fromEnv = "org.gradle.java.installations.fromEnv=";

    assertThat(properties).contains("org.gradle.java.installations.auto-download=false");
    assertThat(properties).contains(fromEnv + "JAVA_HOME_17_X64,JAVA_HOME_21_X64,JAVA_HOME_25_X64");
  }

  @Test
  void policyProjectLocksItsResolvedClasspaths() throws IOException {
    String lockfile = read("build-tools/harness-policy/gradle.lockfile");

    // Same reasoning as the catalog check: the contract is that each dependency is locked to an
    // exact coordinate, not that it is locked to today's version.
    for (String module :
        List.of(
            "com.networknt:json-schema-validator",
            "org.assertj:assertj-core",
            "org.junit.jupiter:junit-jupiter",
            "com.fasterxml.jackson.core:jackson-databind")) {
      assertThat(lockfile)
          .withFailMessage(
              "%s must appear in the lockfile with an exact version, otherwise the policy project"
                  + " resolves differently from one run to the next.",
              module)
          .containsPattern("(?m)^" + Pattern.quote(module) + ":[0-9][0-9A-Za-z.+-]*=");
    }
  }

  @Test
  void mavenBuildFilesAreAbsent() throws IOException {
    Path root = RepositoryPaths.root();
    List<String> found = new ArrayList<>();

    Files.walkFileTree(
        root,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
            String name = fileNameOf(directory);
            if (!directory.equals(root) && IGNORED_DIRECTORIES.contains(name)) {
              return FileVisitResult.SKIP_SUBTREE;
            }
            if (FORBIDDEN_MAVEN_NAMES.contains(name)) {
              found.add(root.relativize(directory).toString());
            }
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
            if (FORBIDDEN_MAVEN_NAMES.contains(fileNameOf(file))) {
              found.add(root.relativize(file).toString());
            }
            return FileVisitResult.CONTINUE;
          }
        });

    assertThat(found).isEmpty();
  }

  private static String fileNameOf(Path path) {
    Path name = path.getFileName();
    return name == null ? "" : name.toString();
  }

  private static String sha256(Path file) throws IOException {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
    } catch (NoSuchAlgorithmException cause) {
      throw new IllegalStateException("SHA-256 must be available", cause);
    }
  }

  private static String read(String relativePath) throws IOException {
    return Files.readString(RepositoryPaths.root().resolve(relativePath), StandardCharsets.UTF_8);
  }
}
