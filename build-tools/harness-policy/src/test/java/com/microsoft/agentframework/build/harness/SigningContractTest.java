package com.microsoft.agentframework.build.harness;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
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
    requireSignedPublishOutput();

    Set<String> signedArtifacts = signatureTargets(".asc");

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
    // A signature on the pom, or on the sources and javadoc jars alone, would satisfy a file count
    // while leaving the main jar unsigned. Central rejects that, so assert on the artifact
    // consumers actually download.
    requireSignedPublishOutput();

    Set<String> signedJars = signatureTargets(".jar.asc", SigningContractTest::isMainJarSignature);

    for (String artifact : PUBLISHED_ARTIFACTS) {
      if (artifact.endsWith("-bom")) {
        // A platform publishes only a pom, so it has no jar to sign.
        continue;
      }
      assertThat(signedJars)
          .withFailMessage("%s published a main jar with no detached signature.", artifact)
          .contains(artifact);
    }
  }

  private static boolean isMainJarSignature(Path signature) {
    // Accept only a jar with no classifier. Excluding a list of known classifiers would still count
    // `-tests`, `-all`, or a native classifier as the main jar, and this assertion exists precisely
    // to name the artifact a consumer downloads.
    //
    // A snapshot expands the version in the filename but not in the directory, so the version
    // cannot be compared directly; the classifier is whatever follows it.
    return classifierOf(signature).isEmpty();
  }

  /**
   * Returns the classifier of a signed jar, or an empty string when it has none.
   *
   * <p>A Maven filename is {@code <artifactId>-<version>[-<classifier>].jar}. The artifact id is
   * known from the directory, so whatever remains after it and the version is the classifier. The
   * version itself is not known for a snapshot, so it is matched as the leading segments that are
   * not a classifier: everything up to the last hyphen group that is non-numeric and not a
   * timestamp.
   */
  private static String classifierOf(Path signature) {
    String name = fileNameOf(signature);
    String withoutSuffix = name.substring(0, Math.max(0, name.length() - ".jar.asc".length()));
    String prefix = owningArtifact(signature) + "-";

    if (!withoutSuffix.startsWith(prefix)) {
      return "";
    }

    String versionAndClassifier = withoutSuffix.substring(prefix.length());
    int lastSeparator = versionAndClassifier.lastIndexOf('-');
    if (lastSeparator < 0) {
      return "";
    }

    String tail = versionAndClassifier.substring(lastSeparator + 1);
    // A snapshot's build number is the trailing `-1`; a release version has no trailing segment
    // that is purely digits. Either way a digits-only tail is part of the version, not a
    // classifier.
    return tail.chars().allMatch(Character::isDigit) ? "" : tail;
  }

  @Test
  void noPublishedJarIsLeftWithoutASignature() {
    // Publishing twice in one job leaves the earlier snapshot jars behind, unsigned. A check that
    // stops at "some signature exists" passes while an unsigned artifact sits in the repository, so
    // require every main jar to have its own.
    //
    // The presence check comes first: with no publish output at all this assertion has nothing to
    // examine and would otherwise report success on an empty list.
    requireSignedPublishOutput();

    List<Path> unsigned = mainJarsWithoutSignature();

    assertThat(unsigned)
        .withFailMessage(
            "These published jars carry no detached signature: %s. Maven Central rejects an"
                + " unsigned artifact even when its siblings are signed.",
            unsigned)
        .isEmpty();
  }

  /**
   * Fails when signatures are required but none were published, and skips when they are optional.
   *
   * <p>Every assertion in this class is vacuous without publish output, so each one calls this
   * first. A test that inspects an empty directory must not report success.
   */
  private static void requireSignedPublishOutput() {
    Set<String> signed = signatureTargets(".asc");

    if (signaturesAreRequired()) {
      assertThat(signed)
          .withFailMessage(
              "No signatures found under %s. This build requires them because"
                  + " `agentframework.requireSignatures` is set, so signing did not attach to any"
                  + " publication.",
              publishedRepository())
          .isNotEmpty();
    } else {
      Assumptions.assumeTrue(
          !signed.isEmpty(),
          "Skipped: no signed publish output. Publish with a signing key set to verify signing.");
    }
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

  /** Returns the artifact directory names that own at least one signature with the given suffix. */
  private static Set<String> signatureTargets(String suffix) {
    return signatureTargets(suffix, path -> true);
  }

  private static Set<String> signatureTargets(String suffix, Predicate<Path> accept) {
    Path repository = publishedRepository();
    if (!Files.isDirectory(repository)) {
      return Set.of();
    }

    try (Stream<Path> files = Files.walk(repository)) {
      return files
          .filter(Files::isRegularFile)
          .filter(path -> fileNameOf(path).endsWith(suffix))
          .filter(accept)
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
