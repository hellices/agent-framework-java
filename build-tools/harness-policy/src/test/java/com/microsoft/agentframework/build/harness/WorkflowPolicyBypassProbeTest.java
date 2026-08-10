package com.microsoft.agentframework.build.harness;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Probes the workflow policy with the documents an attacker would write. Each probe fails if the
 * policy stops rejecting the bypass it covers.
 */
class WorkflowPolicyBypassProbeTest {

  private static final String HEADER =
      """
      name: Probe
      on:
        pull_request:
      permissions:
        contents: read
      concurrency:
        group: probe
        cancel-in-progress: ${{ github.event_name == 'pull_request' }}
      jobs:
      """;

  private static final String OBJECT_RUNS_ON_PROBE =
      HEADER
          + """
            escape:
              runs-on:
                group: attacker-controlled
                labels:
                  - self-hosted
              steps:
                - run: echo probe
          """;

  private static final String LOCAL_REUSABLE_WORKFLOW_PROBE =
      HEADER
          + """
            escape:
              uses: ./.github/workflows/escape.yml
          """;

  private static final String LOCAL_COMPOSITE_ACTION_BASELINE =
      HEADER
          + """
            verify:
              runs-on: ubuntu-latest
              steps:
                - uses: ./.github/actions/setup-harness
                - run: echo probe
          """;

  static Stream<String> unrecognizedRunsOnProbes() {
    return Stream.of(
        HEADER + "    escape:\n      runs-on: 7\n      steps:\n        - run: echo probe\n",
        HEADER + "    escape:\n      runs-on: true\n      steps:\n        - run: echo probe\n",
        HEADER + "    escape:\n      runs-on: {}\n      steps:\n        - run: echo probe\n",
        HEADER + "    escape:\n      runs-on: []\n      steps:\n        - run: echo probe\n",
        HEADER
            + "    escape:\n      runs-on:\n        group: default\n        unknown: value\n"
            + "      steps:\n        - run: echo probe\n",
        HEADER + "    escape:\n      steps:\n        - run: echo probe\n");
  }

  @Test
  void objectFormRunsOnCannotReachAnUnreviewedRunner() throws IOException {
    JsonNode probe = WorkflowDocuments.parse(OBJECT_RUNS_ON_PROBE);

    assertThat(WorkflowDocuments.runnerLabels(WorkflowDocuments.job(probe, "escape")))
        .containsExactly("self-hosted");
    assertThat(WorkflowDocuments.runnerSelectors(WorkflowDocuments.job(probe, "escape")))
        .containsExactlyInAnyOrder("self-hosted", "group:attacker-controlled");
    assertThat(WorkflowPolicy.runnerViolations(probe))
        .contains(
            "escape selects the forbidden runner self-hosted",
            "escape selects the forbidden runner group:attacker-controlled");
  }

  @ParameterizedTest(name = "unrecognized runs-on probe {index} fails closed")
  @MethodSource("unrecognizedRunsOnProbes")
  void unrecognizedRunsOnFailsClosed(String probeDocument) throws IOException {
    JsonNode probe = WorkflowDocuments.parse(probeDocument);

    assertThat(WorkflowDocuments.runnerLabels(WorkflowDocuments.job(probe, "escape"))).isEmpty();
    assertThat(WorkflowPolicy.runnerViolations(probe)).isNotEmpty();
  }

  @Test
  void jobLevelLocalReusableWorkflowIsRejected() throws IOException {
    JsonNode probe = WorkflowDocuments.parse(LOCAL_REUSABLE_WORKFLOW_PROBE);

    assertThat(WorkflowPolicy.jobLevelUsesViolations(probe))
        .containsExactly(
            "escape delegates to the reusable workflow ./.github/workflows/escape.yml");
    assertThat(WorkflowPolicy.runnerViolations(probe)).contains("escape declares no runs-on");
    assertThat(WorkflowPolicy.actionPinningViolations(probe)).isEmpty();
  }

  @Test
  void stepLevelLocalCompositeActionStaysAllowed() throws IOException {
    JsonNode baseline = WorkflowDocuments.parse(LOCAL_COMPOSITE_ACTION_BASELINE);

    assertThat(WorkflowDocuments.stepActionReferences(baseline))
        .containsExactly("./.github/actions/setup-harness");
    assertThat(WorkflowPolicy.actionPinningViolations(baseline)).isEmpty();
    assertThat(WorkflowPolicy.jobLevelUsesViolations(baseline)).isEmpty();
    assertThat(WorkflowPolicy.runnerViolations(baseline)).isEmpty();
  }

  @Test
  void unpinnedStepActionIsRejected() throws IOException {
    JsonNode probe =
        WorkflowDocuments.parse(
            HEADER
                + """
                  escape:
                    runs-on: ubuntu-latest
                    steps:
                      - uses: attacker/action@main
                """);

    assertThat(WorkflowPolicy.actionPinningViolations(probe))
        .containsExactly(
            "attacker/action@main is neither a ./ local action nor pinned to a commit SHA");
  }

  static Stream<String> weakenedTrustedConditions() {
    return Stream.of(
        "github.event_name != 'pull_request'",
        "true",
        "always()",
        "github.event.pull_request.head.repo.full_name == github.repository",
        "github.event_name != 'pull_request'"
            + " || github.event.pull_request.head.repo.full_name != github.repository",
        "github.event_name != 'pull_request'"
            + " && github.event.pull_request.head.repo.full_name == github.repository");
  }

  /**
   * Condition comparison ignores formatting only. A trusted job whose guard is rewritten into any
   * weaker or different expression is still rejected.
   */
  @ParameterizedTest(name = "weakened trusted condition {index} is rejected")
  @MethodSource("weakenedTrustedConditions")
  void weakenedTrustedConditionIsRejected(String condition) throws IOException {
    JsonNode probe = WorkflowDocuments.parse(trustedJob("if: >-\n        " + condition + "\n"));

    assertThat(WorkflowPolicy.trustedRunnerConditionViolations(probe)).hasSize(1);
    assertThat(WorkflowPolicy.isTrustedCondition(WorkflowDocuments.job(probe, "trusted")))
        .isFalse();
  }

  @Test
  void trustedJobWithoutAnyConditionIsRejected() throws IOException {
    JsonNode probe = WorkflowDocuments.parse(trustedJob(""));

    assertThat(WorkflowPolicy.trustedRunnerConditionViolations(probe)).hasSize(1);
  }

  static Stream<String> equivalentTrustedConditions() {
    return Stream.of(
        "if: "
            + "github.event_name != 'pull_request' || "
            + "github.event.pull_request.head.repo.full_name == github.repository\n",
        "if: >-\n"
            + "        github.event_name != 'pull_request'\n"
            + "        || github.event.pull_request.head.repo.full_name == github.repository\n",
        "if: \"${{ github.event_name != 'pull_request' || "
            + "github.event.pull_request.head.repo.full_name == github.repository }}\"\n");
  }

  /** Reformatting the trusted condition is allowed: only its meaning is compared. */
  @ParameterizedTest(name = "equivalent trusted condition {index} is accepted")
  @MethodSource("equivalentTrustedConditions")
  void reformattedTrustedConditionStaysAccepted(String condition) throws IOException {
    JsonNode probe = WorkflowDocuments.parse(trustedJob(condition));

    assertThat(WorkflowPolicy.trustedRunnerConditionViolations(probe)).isEmpty();
    assertThat(WorkflowPolicy.isForkCondition(WorkflowDocuments.job(probe, "trusted"))).isFalse();
  }

  private static String trustedJob(String conditionEntry) {
    String indentedCondition = conditionEntry.isEmpty() ? "" : "      " + conditionEntry;
    return HEADER
        + "    trusted:\n"
        + indentedCondition
        + "      runs-on: arc-java-build\n"
        + "      steps:\n"
        + "        - run: echo probe\n";
  }
}
