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
