package io.github.hellices.agentframework.build.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Self test for the build file parser the module composition policy is built on.
 *
 * <p>The policy is only as trustworthy as this parse. A production dependency that the parser
 * reported as a test dependency would let a provider ship against the engine while {@code
 * policyCheck} stayed green, which is precisely the failure the dependency direction rules exist to
 * prevent.
 */
class ProjectLayoutTest {

  @Test
  void classifiesProductionConfigurationsAsProductionDependencies() {
    String buildFile =
        """
        dependencies {
            api(project(":agent-framework-api"))
            implementation(project(":agent-framework-engine"))
        }
        """;

    assertThat(ProjectLayout.projectDependenciesIn(buildFile))
        .containsExactly(":agent-framework-api", ":agent-framework-engine");
    assertThat(ProjectLayout.testProjectDependenciesIn(buildFile)).isEmpty();
  }

  @Test
  void classifiesTestConfigurationsAsTestDependencies() {
    String buildFile =
        """
        dependencies {
            api(project(":agent-framework-api"))
            testImplementation(project(":agent-framework-engine"))
            testRuntimeOnly(project(":agent-framework-testkit"))
        }
        """;

    assertThat(ProjectLayout.projectDependenciesIn(buildFile))
        .containsExactly(":agent-framework-api");
    assertThat(ProjectLayout.testProjectDependenciesIn(buildFile))
        .containsExactly(":agent-framework-engine", ":agent-framework-testkit");
  }

  @Test
  void classifiesAProjectInsideAPlatformWrapperByItsConfiguration() {
    String buildFile =
        """
        dependencies {
            api(platform(project(":agent-framework-bom")))
        }
        """;

    assertThat(ProjectLayout.projectDependenciesIn(buildFile))
        .containsExactly(":agent-framework-bom");
  }

  @Test
  void refusesAProjectDependencyWhoseConfigurationItCannotRead() {
    // A declaration split across lines would otherwise be invisible to the policy, and an invisible
    // dependency is worse than a rejected one: the allowlist would report a module as depending on
    // nothing while it shipped against the engine.
    String buildFile =
        """
        dependencies {
            api(
                project(":agent-framework-api"))
        }
        """;

    assertThatThrownBy(() -> ProjectLayout.projectDependenciesIn(buildFile))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(":agent-framework-api")
        .hasMessageContaining("one line");
  }

  @Test
  void refusesTwoProjectDependenciesDeclaredOnOneLine() {
    // Reading the configuration once per line and applying it to every reference on that line
    // would report the production `api` dependency as a test dependency, which is the exact
    // inversion the split parse exists to prevent. There is no configuration that applies to the
    // whole line, so the only honest answer is a refusal.
    String buildFile =
        """
        dependencies {
            testImplementation(project(":agent-framework-engine")); api(project(":agent-framework-api"))
        }
        """;

    assertThatThrownBy(() -> ProjectLayout.projectDependenciesIn(buildFile))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(":agent-framework-engine")
        .hasMessageContaining(":agent-framework-api")
        .hasMessageContaining("configuration(project(\":path\"))");

    assertThatThrownBy(() -> ProjectLayout.testProjectDependenciesIn(buildFile))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(":agent-framework-engine")
        .hasMessageContaining(":agent-framework-api");
  }

  @Test
  void refusesAProjectPathPassedAsANamedArgument() {
    // `project(path = ":x")` resolves to the same dependency, so silently reading nothing would
    // report the module as depending on nothing at all.
    String buildFile =
        """
        dependencies {
            api(project(path = ":agent-framework-api"))
        }
        """;

    assertThatThrownBy(() -> ProjectLayout.projectDependenciesIn(buildFile))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("path = \":agent-framework-api\"")
        .hasMessageContaining("configuration(project(\":path\"))");
  }

  @Test
  void refusesAProjectDependencyThatCarriesExtraArguments() {
    String buildFile =
        """
        dependencies {
            api(project(":agent-framework-api", configuration = "shadow"))
        }
        """;

    assertThatThrownBy(() -> ProjectLayout.projectDependenciesIn(buildFile))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("configuration = \"shadow\"")
        .hasMessageContaining("configuration(project(\":path\"))");
  }

  @Test
  void refusesANonLiteralProjectPath() {
    String buildFile =
        """
        val target = ":agent-framework-api"
        dependencies {
            api(project(target))
        }
        """;

    assertThatThrownBy(() -> ProjectLayout.projectDependenciesIn(buildFile))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("configuration(project(\":path\"))");
  }

  @Test
  void refusesATypeSafeProjectAccessor() {
    // A type-safe accessor carries no path text at all, so a parse that ignored it would report a
    // module that ships against the engine as depending on nothing.
    String buildFile =
        """
        dependencies {
            implementation(projects.agentFrameworkEngine)
        }
        """;

    assertThatThrownBy(() -> ProjectLayout.projectDependenciesIn(buildFile))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("projects.agentFrameworkEngine")
        .hasMessageContaining("configuration(project(\":path\"))");
  }

  @Test
  void ignoresProjectReferencesInsideComments() {
    // A commented-out or documented dependency declares nothing. Reading one would refuse a build
    // file that is entirely legal, and a policy that fails on legal input gets suppressed.
    String buildFile =
        """
        /**
         * Once declared api(project(":agent-framework-engine")).
         */
        dependencies {
            // testImplementation(project(":agent-framework-testkit"))
            api(project(":agent-framework-api")) // was project(":agent-framework-engine")
            /* implementation(project(":agent-framework-testkit")) */
        }
        """;

    assertThat(ProjectLayout.projectDependenciesIn(buildFile))
        .containsExactly(":agent-framework-api");
    assertThat(ProjectLayout.testProjectDependenciesIn(buildFile)).isEmpty();
  }

  @Test
  void ignoresProjectReferencesInsideAMultiLineBlockComment() {
    String buildFile =
        """
        dependencies {
            /*
            api(project(":agent-framework-engine"))
            testImplementation(project(":agent-framework-testkit"))
            */
            api(project(":agent-framework-api"))
        }
        """;

    assertThat(ProjectLayout.projectDependenciesIn(buildFile))
        .containsExactly(":agent-framework-api");
    assertThat(ProjectLayout.testProjectDependenciesIn(buildFile)).isEmpty();
  }

  @Test
  void readsADependencyThatFollowsAStringHoldingASlashPair() {
    // Cutting each line at the first "//" would delete this declaration along with the URL, and a
    // dependency the parser cannot see is worse than one it refuses: the allowlist would report
    // the module as depending on nothing while it shipped against the project.
    String buildFile =
        """
        dependencies {
            val home = "https://example.invalid"; api(project(":agent-framework-api"))
        }
        """;

    assertThat(ProjectLayout.projectDependenciesIn(buildFile))
        .containsExactly(":agent-framework-api");
  }

  @Test
  void ignoresAProjectReferenceThatIsOnlyStringContent() {
    String buildFile =
        """
        dependencies {
            api(project(":agent-framework-api"))
        }
        tasks.register("report") {
            doLast { println("api(project(\\":agent-framework-engine\\"))") }
        }
        """;

    assertThat(ProjectLayout.projectDependenciesIn(buildFile))
        .containsExactly(":agent-framework-api");
    assertThat(ProjectLayout.testProjectDependenciesIn(buildFile)).isEmpty();
  }

  @Test
  void classifiesATestFixturesWrapperByTheConfigurationAroundIt() {
    // `testFixtures(...)` names a variant, not a configuration. Reading it as the configuration
    // would classify the same dependency the same way no matter who declared it.
    String buildFile =
        """
        dependencies {
            api(testFixtures(project(":agent-framework-api")))
            testImplementation(testFixtures(project(":agent-framework-testkit")))
        }
        """;

    assertThat(ProjectLayout.projectDependenciesIn(buildFile))
        .containsExactly(":agent-framework-api");
    assertThat(ProjectLayout.testProjectDependenciesIn(buildFile))
        .containsExactly(":agent-framework-testkit");
  }

  @Test
  void refusesAQualifiedProjectCall() {
    String buildFile =
        """
        dependencies {
            api(rootProject.project(":agent-framework-api"))
        }
        """;

    assertThatThrownBy(() -> ProjectLayout.projectDependenciesIn(buildFile))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("rootProject.project")
        .hasMessageContaining("configuration(project(\":path\"))");
  }

  @Test
  void ignoresAProjectReferenceInsideAMultiLineRawString() {
    String buildFile =
        """
        val notice =
            \"\"\"
            api(project(":agent-framework-engine"))
            \"\"\"

        dependencies {
            api(project(":agent-framework-api"))
        }
        """;

    assertThat(ProjectLayout.projectDependenciesIn(buildFile))
        .containsExactly(":agent-framework-api");
    assertThat(ProjectLayout.testProjectDependenciesIn(buildFile)).isEmpty();
  }

  @Test
  void classifiesEveryProjectReferenceAsExactlyOneKind() {
    // The narrowed production parse must lose nothing. If a configuration name the policy does not
    // recognise ever fell out of both lists, a shipped dependency would become invisible instead of
    // rejected, which is the whole failure mode this parse exists to prevent.
    String buildFile =
        """
        dependencies {
            api(project(":agent-framework-api"))
            implementation(project(":agent-framework-engine"))
            compileOnly(project(":agent-framework-testkit"))
            testImplementation(project(":agent-framework-engine"))
            testFixturesApi(project(":agent-framework-api"))
        }
        """;

    assertThat(ProjectLayout.projectDependenciesIn(buildFile))
        .containsExactly(
            ":agent-framework-api", ":agent-framework-engine", ":agent-framework-testkit");
    assertThat(ProjectLayout.testProjectDependenciesIn(buildFile))
        .containsExactly(":agent-framework-engine", ":agent-framework-api");
  }
}
