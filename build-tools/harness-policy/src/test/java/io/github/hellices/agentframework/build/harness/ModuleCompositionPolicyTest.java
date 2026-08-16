package io.github.hellices.agentframework.build.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Proves the module composition contract holds.
 *
 * <p>Module registration, layout, applied conventions, dependency direction, and artifact
 * coordinates are build failures rather than review comments. Update {@code
 * docs/design/module-composition.md} and this test together.
 */
class ModuleCompositionPolicyTest {

  private static final List<String> LIBRARY_PROJECTS =
      List.of(
          ":agent-framework-api",
          ":agent-framework-engine",
          ":agent-framework-testkit",
          ":integrations:agent-framework-mcp",
          ":providers:agent-framework-openai");

  private static final String PLATFORM_PROJECT = ":agent-framework-bom";

  /**
   * Projects allowed to sit at the repository root.
   *
   * <p>Written out rather than derived from {@link #LIBRARY_PROJECTS}. Deriving it would couple two
   * unrelated decisions: {@code everyRegisteredProductProjectIsClassified} tells the author of a
   * new module to add it to the library list, and if that list also governed root placement,
   * following that advice would legalise {@code :agent-framework-openai} at the root — the case the
   * layout check exists to reject.
   *
   * <p>Adding an entry here is a layout decision and belongs in the design document.
   */
  private static final List<String> ROOT_PROJECTS =
      List.of(
          ":agent-framework-api",
          ":agent-framework-engine",
          ":agent-framework-testkit",
          ":agent-framework-bom");

  /**
   * Grouping directories whose modules are built but never published.
   *
   * <p>Kept separate from the grouping list so that adding a family does not accidentally waive its
   * publishing obligation.
   */
  private static final List<String> NON_PUBLISHED_GROUPS =
      List.of("samples", "compatibility-tests");

  private static final String HARNESS_PREFIX = ":build-tools:";

  /**
   * Directories that may hold a family of modules.
   *
   * <p>A project outside this set must sit at the repository root. The list is intentionally
   * closed: adding a family is a layout decision that belongs in the design document, not something
   * a module author can introduce by creating a directory.
   */
  private static final List<String> GROUPING_DIRECTORIES =
      List.of(
          "providers",
          "integrations",
          "hosting",
          "starters",
          "protocols",
          "workflow",
          "compatibility-tests",
          "samples",
          "build-tools");

  private static final List<String> REQUIRED_PLUGINS =
      List.of(
          "agentframework.java-library-conventions",
          "agentframework.test-conventions",
          "agentframework.quality-conventions",
          "agentframework.library-publishing-conventions");

  private static final Map<String, List<String>> ALLOWED_DEPENDENCIES =
      Map.of(
          ":agent-framework-api", List.of(),
          ":agent-framework-engine", List.of(":agent-framework-api"),
          ":agent-framework-testkit", List.of(":agent-framework-api"),
          ":integrations:agent-framework-mcp", List.of(":agent-framework-api"),
          ":providers:agent-framework-openai", List.of(":agent-framework-api"));

  /**
   * Project dependencies a library may compile or run its tests against.
   *
   * <p>Separate from {@link #ALLOWED_DEPENDENCIES} because a test-only dependency reaches no
   * consumer. Folding the two together would mean that permitting a provider to test against the
   * engine also permitted it to ship against the engine, which the dependency direction rules
   * forbid.
   *
   * <p>The OpenAI adapter proves that its mapping and the real tool loop agree by running {@code
   * AgentEngine} over a faked operations port. That proof belongs next to the adapter, and the
   * engine it needs is a test dependency only: it appears on no consumer classpath, which {@code
   * libraryProjectOnlyDependsOnAllowedProjects} keeps enforcing separately.
   *
   * <p>A library that tests against no other project needs no entry, which is why the assertion
   * reads this map with a default rather than a lookup: an empty entry per library would be
   * ceremony that says nothing. {@code dependencyAllowlistsCoverExactlyTheLibraryProjects} keeps
   * that convenience honest by refusing a key that names anything but a library project, so a typo
   * cannot sit here looking like a granted permission that no assertion reads.
   */
  private static final Map<String, List<String>> ALLOWED_TEST_DEPENDENCIES =
      Map.of(":providers:agent-framework-openai", List.of(":agent-framework-engine"));

  /** Lockfiles of the modules a provider SDK must never reach. */
  private static final List<String> CORE_LOCKFILES =
      List.of(
          "agent-framework-api/gradle.lockfile",
          "agent-framework-engine/gradle.lockfile",
          "agent-framework-testkit/gradle.lockfile");

  static Stream<String> libraryProjects() {
    return LIBRARY_PROJECTS.stream();
  }

  @Test
  void rootAllowlistAndLibraryListStayConsistent() {
    // The two lists are separate on purpose, so they can drift. A root project that is neither a
    // library nor the platform would be a layout decision nobody recorded, and a library placed in
    // a group is fine but must not linger in the root allowlist.
    for (String rootProject : ROOT_PROJECTS) {
      assertThat(LIBRARY_PROJECTS.contains(rootProject) || PLATFORM_PROJECT.equals(rootProject))
          .withFailMessage(
              "%s is allowed at the root but is neither a library nor the platform. The root is"
                  + " reserved for the core modules; anything else belongs in a grouping"
                  + " directory.",
              rootProject)
          .isTrue();
    }
  }

  @Test
  void everyRegisteredProductProjectIsClassified() {
    // The lists above drive the publishing, dependency-direction, and BOM checks. A new module can
    // otherwise satisfy the layout and existence checks while none of those ever look at it, so
    // registering one without classifying it must fail here rather than pass silently.
    List<String> unclassified =
        ProjectLayout.includedProjects().stream()
            .filter(path -> !path.startsWith(HARNESS_PREFIX))
            .filter(path -> !LIBRARY_PROJECTS.contains(path))
            .filter(path -> !PLATFORM_PROJECT.equals(path))
            .filter(path -> !isExemptFromPublishing(path))
            .toList();

    assertThat(unclassified)
        .withFailMessage(
            "These projects are registered but classified by no policy: %s. Add each to"
                + " LIBRARY_PROJECTS, to the platform, or to a group that this contract exempts"
                + " from publishing, so the publishing, dependency, and BOM checks cover it.",
            unclassified)
        .isEmpty();
  }

  /**
   * Reports whether a project is deliberately outside the published surface.
   *
   * <p>Samples and compatibility tests exist to be built, not shipped, so they carry no publishing
   * or BOM obligation.
   */
  private static boolean isExemptFromPublishing(String gradlePath) {
    return NON_PUBLISHED_GROUPS.stream()
        .anyMatch(group -> gradlePath.startsWith(":" + group + ":"));
  }

  @Test
  void settingsRegistersEveryProductProject() {
    assertThat(ProjectLayout.includedProjects())
        .containsAll(LIBRARY_PROJECTS)
        .contains(PLATFORM_PROJECT);
  }

  @Test
  void everyRegisteredProjectSitsAtTheRootOrInOneGroupingDirectory() {
    for (String gradlePath : ProjectLayout.includedProjects()) {
      String[] segments = gradlePath.substring(1).split(":");

      assertThat(segments.length)
          .withFailMessage(
              "%s nests %d levels deep. Use a root module or exactly one grouping directory.",
              gradlePath, segments.length)
          .isLessThanOrEqualTo(2);

      if (segments.length == 2) {
        assertThat(GROUPING_DIRECTORIES)
            .withFailMessage(
                "%s uses grouping directory '%s', which the module composition contract does not"
                    + " declare.",
                gradlePath, segments[0])
            .contains(segments[0]);
      } else {
        // Without a closed list, a root path is unconstrained and `:agent-framework-openai` would
        // satisfy the layout contract even though a provider belongs under `providers/`. That is
        // the case this test exists to reject.
        assertThat(ROOT_PROJECTS)
            .withFailMessage(
                "%s sits at the repository root, which is reserved for the core modules %s. A"
                    + " provider, sample, or tool belongs in a grouping directory: %s.",
                gradlePath, ROOT_PROJECTS, GROUPING_DIRECTORIES)
            .contains(gradlePath);
      }
    }
  }

  @Test
  void everyRegisteredProjectExistsOnDiskWithABuildFile() {
    for (String gradlePath : ProjectLayout.includedProjects()) {
      assertThat(ProjectLayout.buildFile(gradlePath))
          .withFailMessage("%s is registered but has no build file.", gradlePath)
          .exists();
    }
  }

  @Test
  void everyRegisteredProjectIsDocumented() throws IOException {
    String contract = moduleCompositionContract();

    for (String gradlePath : ProjectLayout.includedProjects()) {
      assertThat(contract)
          .withFailMessage(
              "%s is registered but absent from docs/design/module-composition.md.", gradlePath)
          .contains("`" + gradlePath + "`");
    }
  }

  @ParameterizedTest
  @MethodSource("libraryProjects")
  void libraryProjectAppliesEveryConvention(String gradlePath) {
    String buildFile = ProjectLayout.buildFileText(gradlePath);
    for (String plugin : REQUIRED_PLUGINS) {
      assertThat(buildFile).contains("id(\"" + plugin + "\")");
    }
  }

  @ParameterizedTest
  @MethodSource("libraryProjects")
  void libraryProjectDeclaresNoInlineDependencyVersion(String gradlePath) {
    assertThat(ProjectLayout.buildFileText(gradlePath)).doesNotContain("version = \"");
  }

  @Test
  void everyRegisteredBuildFileParsesUnderTheProjectDependencyRules() {
    // The parse refuses every form it cannot classify, so a rule that is too strict fails a build
    // file that is entirely legal, and a policy that fails on legal input gets suppressed. Reading
    // every registered project, not only the libraries the allowlists cover, keeps that failure
    // here. `:agent-framework-bom` is the case that matters most: its header comment writes out
    // `api(project(...))` as prose, and a parse that read comments would refuse the BOM.
    for (String gradlePath : ProjectLayout.includedProjects()) {
      assertThatCode(() -> ProjectLayout.projectDependenciesOf(gradlePath))
          .withFailMessage(
              "%s declares a project dependency the module composition policy cannot read. Either"
                  + " the build file needs the canonical form, or the parse is refusing something"
                  + " legal.",
              gradlePath)
          .doesNotThrowAnyException();
      assertThatCode(() -> ProjectLayout.testProjectDependenciesOf(gradlePath))
          .withFailMessage(
              "%s declares a test project dependency the module composition policy cannot read.",
              gradlePath)
          .doesNotThrowAnyException();
    }
  }

  @Test
  void dependencyAllowlistsCoverExactlyTheLibraryProjects() {
    // Both allowlists are read through LIBRARY_PROJECTS, so a key for anything else is a
    // permission no assertion ever applies: a typo, or a module that moved, would sit here looking
    // granted while the project it names went unchecked or failed with a null allowlist instead.
    assertThat(ALLOWED_DEPENDENCIES.keySet())
        .withFailMessage(
            "ALLOWED_DEPENDENCIES must hold one entry per library project. Its keys are %s but the"
                + " library projects are %s. Every library needs an explicit entry, even an empty"
                + " one, and an entry for anything else is never read.",
            ALLOWED_DEPENDENCIES.keySet(), LIBRARY_PROJECTS)
        .containsExactlyInAnyOrderElementsOf(LIBRARY_PROJECTS);

    assertThat(LIBRARY_PROJECTS)
        .withFailMessage(
            "ALLOWED_TEST_DEPENDENCIES holds keys that are not library projects: %s. A library"
                + " with no test project dependency needs no entry, but a key naming anything else"
                + " grants a permission no assertion reads.",
            ALLOWED_TEST_DEPENDENCIES.keySet().stream()
                .filter(gradlePath -> !LIBRARY_PROJECTS.contains(gradlePath))
                .toList())
        .containsAll(ALLOWED_TEST_DEPENDENCIES.keySet());
  }

  @ParameterizedTest
  @MethodSource("libraryProjects")
  void libraryProjectOnlyDependsOnAllowedProjects(String gradlePath) {
    assertProductionDependenciesAllowed(
        gradlePath, ProjectLayout.projectDependenciesOf(gradlePath));
  }

  private static void assertProductionDependenciesAllowed(
      String gradlePath, List<String> productionDependencies) {
    assertThat(productionDependencies)
        .containsExactlyInAnyOrderElementsOf(ALLOWED_DEPENDENCIES.get(gradlePath));
  }

  @ParameterizedTest
  @MethodSource("libraryProjects")
  void libraryProjectOnlyTestsAgainstAllowedProjects(String gradlePath) {
    assertThat(ProjectLayout.testProjectDependenciesOf(gradlePath))
        .containsExactlyInAnyOrderElementsOf(
            ALLOWED_TEST_DEPENDENCIES.getOrDefault(gradlePath, List.of()));
  }

  @Test
  void aProductionEngineDependencyOnTheProviderFailsTheAllowlist() {
    // The point of splitting the allowlists is that permitting a test dependency must not permit a
    // shipped one. Asserting the split exists proves nothing; this mutates the real build file and
    // proves the production assertion rejects the result.
    String buildFile = ProjectLayout.buildFileText(":providers:agent-framework-openai");
    String testDeclaration = "testImplementation(project(\":agent-framework-engine\"))";
    assertThat(buildFile)
        .withFailMessage(
            "This proof mutates %s. If the declaration was reworded, update the mutation rather"
                + " than deleting the test.",
            testDeclaration)
        .contains(testDeclaration);

    String mutated =
        buildFile.replace(testDeclaration, "implementation(project(\":agent-framework-engine\"))");

    assertThat(ProjectLayout.testProjectDependenciesIn(mutated)).isEmpty();
    assertThat(ProjectLayout.projectDependenciesIn(mutated)).contains(":agent-framework-engine");
    assertThatThrownBy(
            () ->
                assertProductionDependenciesAllowed(
                    ":providers:agent-framework-openai",
                    ProjectLayout.projectDependenciesIn(mutated)))
        .isInstanceOf(AssertionError.class);
  }

  @Test
  void noProviderSdkReachesACoreClasspath() throws IOException {
    // PRV-001 is a resolution fact, not a build file fact: a provider SDK could arrive
    // transitively without any core build file naming it. The lockfiles are the only place that
    // shows what actually resolves.
    for (String lockfile : CORE_LOCKFILES) {
      String resolved =
          Files.readString(RepositoryPaths.root().resolve(lockfile), StandardCharsets.UTF_8);
      assertThat(resolved)
          .withFailMessage(
              "%s resolves a provider SDK. The core modules must know only the neutral ports.",
              lockfile)
          .doesNotContain("com.openai:");
    }
  }

  @Test
  void noProductProjectDependsOnAHarnessProject() {
    for (String gradlePath : LIBRARY_PROJECTS) {
      assertThat(ProjectLayout.projectDependenciesOf(gradlePath))
          .noneMatch(dependency -> dependency.startsWith(HARNESS_PREFIX));
      assertThat(ProjectLayout.testProjectDependenciesOf(gradlePath))
          .noneMatch(dependency -> dependency.startsWith(HARNESS_PREFIX));
    }
  }

  @Test
  void platformDeclaresConstraintsRatherThanDependencies() {
    String buildFile = ProjectLayout.buildFileText(PLATFORM_PROJECT);

    assertThat(buildFile).contains("agentframework.platform-conventions");
    assertThat(buildFile)
        .withFailMessage(
            "The BOM must declare constraints. Plain dependencies publish under <dependencies> and"
                + " force every module onto a consumer that imports the BOM.")
        .contains("constraints {");
    assertThat(buildFile)
        .withFailMessage("allowDependencies() turns BOM entries into forced dependencies.")
        .doesNotContain("allowDependencies()");

    for (String gradlePath : LIBRARY_PROJECTS) {
      assertThat(buildFile).contains("api(project(\"" + gradlePath + "\"))");
    }
  }

  @ParameterizedTest
  @MethodSource("libraryProjects")
  void libraryProjectIsPublishable(String gradlePath) {
    assertThat(ProjectLayout.buildFileText(gradlePath))
        .contains("agentframework.library-publishing-conventions");
  }

  @Test
  void signingProducesDetachedSignaturesForEveryPublication() throws IOException {
    // Superseded by SigningContractTest, which publishes with a real key. Reading this file could
    // only ever match text: `sign(publishing.publications )` with one extra space slipped through
    // the previous version of this check, which is exactly the fault it existed to prevent.
    assertThat(publishingConventionText())
        .withFailMessage(
            "The publishing convention must configure signing. Whether it attaches correctly is"
                + " proved by SigningContractTest against real published artifacts.")
        .contains("signing {");
  }

  @Test
  void releaseBuildRefusesToPublishUnsignedArtifacts() throws IOException {
    // The design document promises a release fails early rather than at upload. Without this the
    // convention could drift back to skipping signing whenever a secret is missing, and the first
    // signal would be a rejected Central upload.
    assertThat(publishingConventionText())
        .withFailMessage(
            "A release build must fail when SIGNING_KEY is absent. Maven Central rejects unsigned"
                + " uploads, so the check belongs in the publishing convention.")
        .contains("agentframework.release");
  }

  private static String publishingConventionText() throws IOException {
    return Files.readString(
        RepositoryPaths.root()
            .resolve(
                "build-logic/src/main/kotlin/agentframework.publishing-conventions.gradle.kts"),
        StandardCharsets.UTF_8);
  }

  @Test
  void repositoryDeclaresASingleGroupAndVersion() throws IOException {
    String text =
        Files.readString(
            RepositoryPaths.root().resolve("gradle.properties"), StandardCharsets.UTF_8);
    assertThat(text).containsPattern("(?m)^group=io\\.github\\.hellices\\.agentframework$");
    assertThat(text).containsPattern("(?m)^version=\\d+\\.\\d+\\.\\d+(-SNAPSHOT)?$");
  }

  @Test
  void communitySourcesUseOwnedNamespaceAndDoNotReferenceMicrosoftPackages() throws IOException {
    SourcePackages.Report report = SourcePackages.inspect(RepositoryPaths.root());
    assertThat(report.sources())
        .contains(
            RepositoryPaths.root()
                .resolve(
                    "agent-framework-api/src/main/java/"
                        + "io/github/hellices/agentframework/api/ApiContract.java"));
    assertThat(report.scanFailures()).as("namespace source scan failures").isEmpty();
    assertThat(report.violations()).as("retired namespace references").isEmpty();
  }

  private static String moduleCompositionContract() throws IOException {
    Path contract = RepositoryPaths.root().resolve("docs/design/module-composition.md");
    return Files.readString(contract, StandardCharsets.UTF_8);
  }
}
