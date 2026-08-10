package com.microsoft.agentframework.build.harness;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class RepositoryGovernancePolicyTest {

  private static final List<String> REQUIRED_FILES =
      List.of(
          "AGENTS.md",
          "CLAUDE.md",
          "GEMINI.md",
          ".github/copilot-instructions.md",
          "CONTRIBUTING.md",
          "SECURITY.md",
          ".github/CODEOWNERS",
          ".editorconfig",
          ".gitattributes",
          ".gitignore",
          "docs/operations/github-actions-runner-contract.md");

  private static final List<String> VENDOR_ADAPTERS =
      List.of("CLAUDE.md", "GEMINI.md", ".github/copilot-instructions.md");

  private static final List<String> REQUIRED_AGENT_SECTIONS =
      List.of(
          "## Architecture boundaries",
          "## Standard workflow",
          "## Verification contract",
          "## Sensitive data",
          "## Prohibited changes");

  static Stream<String> requiredFiles() {
    return REQUIRED_FILES.stream();
  }

  static Stream<String> vendorAdapters() {
    return VENDOR_ADAPTERS.stream();
  }

  static Stream<String> requiredAgentSections() {
    return REQUIRED_AGENT_SECTIONS.stream();
  }

  @ParameterizedTest(name = "{0} exists")
  @MethodSource("requiredFiles")
  void requiredGovernanceFileExists(String relativePath) {
    assertThat(RepositoryPaths.root().resolve(relativePath)).isRegularFile();
  }

  @ParameterizedTest(name = "AGENTS.md declares {0}")
  @MethodSource("requiredAgentSections")
  void canonicalInstructionsDeclareRequiredSection(String heading) throws IOException {
    String text = read("AGENTS.md");

    assertThat(text).contains(heading);
  }

  @ParameterizedTest(name = "{0} stays a thin adapter")
  @MethodSource("vendorAdapters")
  void vendorAdapterStaysThin(String relativePath) throws IOException {
    Path adapter = RepositoryPaths.root().resolve(relativePath);
    List<String> lines = Files.readAllLines(adapter, StandardCharsets.UTF_8);
    String text = String.join("\n", lines);

    assertThat(lines).hasSizeLessThanOrEqualTo(20);
    assertThat(text).contains("AGENTS.md");
    assertThat(text).doesNotContain("## Architecture boundaries");
    assertThat(text).doesNotContain("## Verification contract");
  }

  @Test
  void canonicalInstructionsDescribeTheGradleVerificationEntryPoints() throws IOException {
    String text = read("AGENTS.md");

    assertThat(text).contains("./gradlew policyCheck");
    assertThat(text).contains("./gradlew quality");
    assertThat(text).contains("./gradlew testJava17 testJava21 testJava25");
    assertThat(text).contains("./gradlew check");
    assertThat(text).doesNotContain("mvnw");
  }

  @Test
  void contributingGuideUsesTheGradleWrapper() throws IOException {
    String text = read("CONTRIBUTING.md");

    assertThat(text).contains("./gradlew check");
    assertThat(text).doesNotContain("mvnw");
  }

  @Test
  void readmeLinksEveryHarnessEntryPoint() throws IOException {
    String text = read("README.md");

    assertThat(text).contains("(AGENTS.md)");
    assertThat(text).contains("(CONTRIBUTING.md)");
    assertThat(text).contains("(SECURITY.md)");
    assertThat(text).contains("(docs/operations/github-actions-runner-contract.md)");
    assertThat(text).contains("./gradlew check");
    assertThat(text).doesNotContain("mvnw");
  }

  @Test
  void codeOwnersProtectTheHarnessSurface() throws IOException {
    String text = read(".github/CODEOWNERS");

    assertThat(text).contains("/AGENTS.md");
    assertThat(text).contains("/.harness/");
    assertThat(text).contains("/.github/");
    assertThat(text).contains("/build-logic/");
    assertThat(text).contains("/gradle/");
  }

  private static String read(String relativePath) throws IOException {
    return Files.readString(RepositoryPaths.root().resolve(relativePath), StandardCharsets.UTF_8);
  }
}
