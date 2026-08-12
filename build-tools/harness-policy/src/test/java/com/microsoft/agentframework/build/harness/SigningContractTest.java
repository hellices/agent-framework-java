package com.microsoft.agentframework.build.harness;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Proves signing attaches to every publication by inspecting real signed artifacts.
 *
 * <p>A fault has already reached this repository that no pull request could have caught: {@code
 * useInMemoryPgpKeys} builds no signatory when the password is null, which an unprotected CI key
 * produces, and the failure surfaces only at release time as "No configured signatory".
 *
 * <p>A text assertion cannot hold this. The guard that first replaced it was defeated by a single
 * extra space, so this test reads the {@code .asc} files a signed publish actually wrote.
 *
 * <p>It skips unless a signed publish has run, because a contributor without a key must still be
 * able to build. CI publishes with a throwaway key and sets {@code
 * agentframework.requireSignatures}, which turns a missing signature into a failure.
 */
class SigningContractTest {

  private static final List<String> PUBLISHED_ARTIFACTS =
      List.of(
          "agent-framework-api",
          "agent-framework-engine",
          "agent-framework-testkit",
          "agent-framework-bom");

  @Test
  void everyPublishedArtifactCarriesADetachedSignature() {
    Set<String> signedArtifacts = artifactsWithSignatures();

    if (signaturesAreRequired()) {
      assertThat(signedArtifacts)
          .withFailMessage(
              "No signatures found under %s. This build requires them because"
                  + " `agentframework.requireSignatures` is set, so signing did not attach to any"
                  + " publication.",
              publishedRepository())
          .isNotEmpty();
    } else {
      Assumptions.assumeTrue(
          !signedArtifacts.isEmpty(),
          "Skipped: no signed publish output. Publish with SIGNING_KEY set to verify signing.");
    }

    for (String artifact : PUBLISHED_ARTIFACTS) {
      assertThat(signedArtifacts)
          .withFailMessage(
              "%s published no detached signature. Maven Central rejects unsigned artifacts, and a"
                  + " publication that the signing convention never observed produces none.",
              artifact)
          .contains(artifact);
    }
  }

  @Test
  void everyMainJarIsSignedRatherThanOnlyTheMetadata() {
    // A signature on the pom alone would satisfy a file count while leaving the jar unsigned, which
    // Central rejects. Assert on the artifact consumers download.
    Set<String> signedJars = signatureTargets(".jar.asc");

    if (signaturesAreRequired()) {
      assertThat(signedJars)
          .withFailMessage("No signed jars under %s.", publishedRepository())
          .isNotEmpty();
    } else {
      Assumptions.assumeTrue(!signedJars.isEmpty(), "Skipped: no signed publish output.");
    }

    for (String artifact : PUBLISHED_ARTIFACTS) {
      if (artifact.endsWith("-bom")) {
        // A platform publishes only a pom, so it has no jar to sign.
        continue;
      }
      assertThat(signedJars)
          .withFailMessage("%s published a jar with no detached signature.", artifact)
          .contains(artifact);
    }
  }

  @Test
  void noPublishedJarIsLeftWithoutASignature() {
    // Publishing twice in one job leaves the earlier snapshot jars behind, unsigned. A check that
    // stops at "some signature exists" passes while an unsigned artifact sits in the repository, so
    // require every main jar to have its own.
    List<Path> unsigned = mainJarsWithoutSignature();

    if (!signaturesAreRequired()) {
      Assumptions.assumeTrue(
          !signatureTargets(".jar.asc").isEmpty(), "Skipped: no signed publish output.");
    }

    assertThat(unsigned)
        .withFailMessage(
            "These published jars carry no detached signature: %s. Maven Central rejects an"
                + " unsigned artifact even when its siblings are signed.",
            unsigned)
        .isEmpty();
  }

  private static List<Path> mainJarsWithoutSignature() {
    Path repository = publishedRepository();
    if (!Files.isDirectory(repository)) {
      return List.of();
    }

    try (Stream<Path> files = Files.walk(repository)) {
      return files
          .filter(Files::isRegularFile)
          .filter(path -> fileNameOf(path).endsWith(".jar"))
          .filter(path -> !fileNameOf(path).endsWith("-sources.jar"))
          .filter(path -> !fileNameOf(path).endsWith("-javadoc.jar"))
          .filter(path -> !Files.exists(path.resolveSibling(fileNameOf(path) + ".asc")))
          .toList();
    } catch (IOException cause) {
      throw new UncheckedIOException("Cannot scan " + repository, cause);
    }
  }

  private static boolean signaturesAreRequired() {
    return Boolean.parseBoolean(System.getProperty("agentframework.requireSignatures", "false"));
  }

  private static Set<String> artifactsWithSignatures() {
    return signatureTargets(".asc");
  }

  /** Returns the artifact directory names that own at least one signature with the given suffix. */
  private static Set<String> signatureTargets(String suffix) {
    Path repository = publishedRepository();
    if (!Files.isDirectory(repository)) {
      return Set.of();
    }

    try (Stream<Path> files = Files.walk(repository)) {
      return files
          .filter(Files::isRegularFile)
          .filter(path -> fileNameOf(path).endsWith(suffix))
          .map(SigningContractTest::owningArtifact)
          .collect(Collectors.toUnmodifiableSet());
    } catch (IOException cause) {
      throw new UncheckedIOException("Cannot scan " + repository, cause);
    }
  }

  /** Maps {@code .../agent-framework-api/0.1.0/x.jar.asc} to {@code agent-framework-api}. */
  private static String owningArtifact(Path signature) {
    Path versionDirectory = signature.getParent();
    if (versionDirectory == null) {
      return "";
    }
    Path artifactDirectory = versionDirectory.getParent();
    return artifactDirectory == null ? "" : fileNameOf(artifactDirectory);
  }

  private static Path publishedRepository() {
    return RepositoryPaths.root().resolve("build/maven-repository");
  }

  private static String fileNameOf(Path path) {
    Path name = path.getFileName();
    return name == null ? "" : name.toString();
  }
}
