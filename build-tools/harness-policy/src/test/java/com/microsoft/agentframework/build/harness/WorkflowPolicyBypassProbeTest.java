package com.microsoft.agentframework.build.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
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
    assertThat(
            WorkflowPolicy.actionGraphViolations(
                baseline, probeActions(Map.of(SETUP_HARNESS, compositeAction(PINNED_ACTION)))))
        .isEmpty();
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

  static Stream<String> forbiddenTriggers() {
    return Stream.of(
        "workflow_run",
        "pull_request_target",
        "issue_comment",
        "repository_dispatch",
        "workflow_call",
        "schedule");
  }

  /**
   * The trusted condition only asks whether the event is a pull request, so every other event is
   * trusted by construction. A trigger outside the allow list therefore hands an attacker a trusted
   * run, and the allow list is the rule that closes it.
   */
  @ParameterizedTest(name = "{0} is not an allowed trigger")
  @MethodSource("forbiddenTriggers")
  void triggerOutsideTheAllowListIsRejected(String trigger) throws IOException {
    JsonNode probe = WorkflowDocuments.parse(triggeredTrustedWorkflow(trigger));

    assertThat(WorkflowDocuments.triggerNames(probe)).containsExactly(trigger);
    assertThat(WorkflowPolicy.triggerViolations(probe))
        .containsExactly(trigger + " is not an allowed trigger");
  }

  /**
   * Regression for the exact bypass: a {@code workflow_run} job carrying the verbatim trusted
   * condition passes every condition rule, because {@code workflow_run} is not a pull request. Only
   * the trigger allow list keeps it off {@code arc-java-build}.
   */
  @Test
  void workflowRunTrustedJobIsCaughtOnlyByTheTriggerAllowList() throws IOException {
    JsonNode probe = WorkflowDocuments.parse(triggeredTrustedWorkflow("workflow_run"));
    JsonNode trusted = WorkflowDocuments.job(probe, "trusted");

    assertThat(WorkflowDocuments.runnerSelectors(trusted)).containsExactly("arc-java-build");
    assertThat(WorkflowPolicy.isTrustedCondition(trusted)).isTrue();
    assertThat(WorkflowPolicy.trustedRunnerConditionViolations(probe)).isEmpty();
    assertThat(WorkflowPolicy.runnerViolations(probe)).isEmpty();
    assertThat(WorkflowPolicy.triggerViolations(probe))
        .containsExactly("workflow_run is not an allowed trigger");
  }

  @ParameterizedTest(name = "{0} stays an allowed trigger")
  @MethodSource("allowedTriggers")
  void allowListedTriggerStaysAccepted(String trigger) throws IOException {
    JsonNode probe = WorkflowDocuments.parse(triggeredTrustedWorkflow(trigger));

    assertThat(WorkflowPolicy.triggerViolations(probe)).isEmpty();
  }

  static Stream<String> allowedTriggers() {
    return WorkflowPolicy.ALLOWED_TRIGGERS.stream();
  }

  @Test
  void workflowWithoutAnyTriggerIsRejected() throws IOException {
    JsonNode probe =
        WorkflowDocuments.parse(
            """
            name: Probe
            permissions:
              contents: read
            jobs:
              verify:
                runs-on: ubuntu-latest
                steps:
                  - run: echo probe
            """);

    assertThat(WorkflowPolicy.triggerViolations(probe)).containsExactly("declares no trigger");
  }

  @Test
  void triggerAllowListRejectsAnyEventOutsideTheThreeReviewedOnes() throws IOException {
    JsonNode probe =
        WorkflowDocuments.parse(
            HEADER.replace("  pull_request:\n", "  pull_request:\n  workflow_run:\n")
                + "    verify:\n      runs-on: ubuntu-latest\n      steps:\n"
                + "        - run: echo probe\n");

    assertThat(WorkflowPolicy.triggerViolations(probe))
        .containsExactly("workflow_run is not an allowed trigger");
  }

  private static String triggeredTrustedWorkflow(String trigger) {
    return "name: Probe\n"
        + "on:\n"
        + "  "
        + trigger
        + ":\n"
        + "permissions:\n"
        + "  contents: read\n"
        + "concurrency:\n"
        + "  group: probe\n"
        + "  cancel-in-progress: ${{ github.event_name == 'pull_request' }}\n"
        + "jobs:\n"
        + "  trusted:\n"
        + "    if: >-\n"
        + "      "
        + WorkflowPolicy.TRUSTED_CONDITION
        + "\n"
        + "    runs-on: arc-java-build\n"
        + "    steps:\n"
        + "      - run: echo probe\n";
  }

  private static final String GATE_HEADER =
      HEADER
          + "    fork-verify:\n"
          + "      if: >-\n"
          + "        "
          + WorkflowPolicy.FORK_CONDITION
          + "\n"
          + "      runs-on: ubuntu-latest\n"
          + "      steps:\n"
          + "        - run: echo fork\n"
          + "    trusted-verify:\n"
          + "      if: >-\n"
          + "        "
          + WorkflowPolicy.TRUSTED_CONDITION
          + "\n"
          + "      runs-on: arc-java-build\n"
          + "      steps:\n"
          + "        - run: echo trusted\n";

  private static String gateWorkflow(String needs, String verificationPaths) {
    return GATE_HEADER
        + "    verify-result:\n"
        + "      if: always()\n"
        + "      needs:\n"
        + needs
        + "      runs-on: ubuntu-latest\n"
        + "      steps:\n"
        + "        - env:\n"
        + "            NEEDS_JSON: ${{ toJSON(needs) }}\n"
        + "            VERIFICATION_PATHS: |\n"
        + verificationPaths
        + "          run: echo gate\n";
  }

  private static final String BOTH_NEEDS = "        - fork-verify\n        - trusted-verify\n";

  /** A verification job wired into {@code needs} but into no path is a false-green hole. */
  @Test
  void verificationJobOutsideEveryPathIsRejected() throws IOException {
    JsonNode probe =
        WorkflowDocuments.parse(gateWorkflow(BOTH_NEEDS, "              fork=fork-verify\n"));

    assertThat(WorkflowPolicy.verificationPathViolations(probe, "verify-result"))
        .containsExactly("trusted-verify belongs to no declared verification path");
  }

  @Test
  void verificationPathClaimingAJobOutsideNeedsIsRejected() throws IOException {
    JsonNode probe =
        WorkflowDocuments.parse(
            gateWorkflow(
                "        - fork-verify\n",
                "              fork=fork-verify\n              trusted=trusted-verify\n"));

    assertThat(WorkflowPolicy.verificationPathViolations(probe, "verify-result"))
        .containsExactly("path trusted declares trusted-verify, which verify-result does not need");
  }

  @Test
  void verificationJobClaimedByTwoPathsIsRejected() throws IOException {
    JsonNode probe =
        WorkflowDocuments.parse(
            gateWorkflow(
                BOTH_NEEDS,
                "              fork=fork-verify,trusted-verify\n"
                    + "              trusted=trusted-verify\n"));

    assertThat(WorkflowPolicy.verificationPathViolations(probe, "verify-result"))
        .containsExactly("trusted-verify is declared by more than one verification path");
  }

  @Test
  void gateThatReadsASingleJobResultInsteadOfTheNeedsContextIsRejected() throws IOException {
    JsonNode probe =
        WorkflowDocuments.parse(
            GATE_HEADER
                + "    verify-result:\n"
                + "      if: always()\n"
                + "      needs:\n"
                + BOTH_NEEDS
                + "      runs-on: ubuntu-latest\n"
                + "      steps:\n"
                + "        - env:\n"
                + "            FORK_RESULT: ${{ needs.fork-verify.result }}\n"
                + "          run: echo gate\n");

    assertThat(WorkflowPolicy.verificationPathViolations(probe, "verify-result"))
        .contains(
            "verify-result does not read the whole needs context as NEEDS_JSON",
            "verify-result declares no VERIFICATION_PATHS");
  }

  @Test
  void wellFormedGateIsAccepted() throws IOException {
    JsonNode probe =
        WorkflowDocuments.parse(
            gateWorkflow(
                BOTH_NEEDS,
                "              fork=fork-verify\n              trusted=trusted-verify\n"));

    assertThat(WorkflowPolicy.verificationPathViolations(probe, "verify-result")).isEmpty();
    assertThat(WorkflowPolicy.verificationPaths(probe, "verify-result"))
        .containsExactly(
            java.util.Map.entry("fork", List.of("fork-verify")),
            java.util.Map.entry("trusted", List.of("trusted-verify")));
  }

  private static final String SETUP_HARNESS = "./.github/actions/setup-harness";

  private static final String NESTED_TOOLCHAIN = "./.github/actions/nested/toolchain";

  private static final String MISSING_ACTION = "./.github/actions/missing";

  private static final String ESCAPING_ACTION = "./../attacker-actions/setup";

  private static final String PINNED_ACTION =
      "actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1";

  private static final String UNPINNED_ACTION = "attacker/action@main";

  /** Resolves {@code ./} references from synthetic definitions instead of the working tree. */
  private static WorkflowDocuments.LocalActions probeActions(Map<String, String> definitions) {
    return reference -> {
      String definition = definitions.get(reference);
      return definition == null ? MissingNode.getInstance() : WorkflowDocuments.parse(definition);
    };
  }

  /** A composite action definition whose steps use every reference handed in. */
  private static String compositeAction(String... references) {
    StringBuilder action = new StringBuilder("name: Probe action\nruns:\n  using: composite\n");
    action.append("  steps:\n");
    for (String reference : references) {
      action.append("    - uses: ").append(reference).append('\n');
    }
    action.append("    - run: echo composite\n      shell: bash\n");
    return action.toString();
  }

  private static String workflowUsing(String reference) {
    return HEADER
        + "    verify:\n"
        + "      runs-on: ubuntu-latest\n"
        + "      steps:\n"
        + "        - uses: "
        + reference
        + "\n        - run: echo probe\n";
  }

  /**
   * The exact hole: a {@code ./} reference is accepted on sight by the pinning rule, so a local
   * composite action was free to pull an unpinned external action and to point at a path that does
   * not exist. The graph walk reaches both.
   */
  @Test
  void localCompositeActionPullingAnUnpinnedActionIsRejected() throws IOException {
    JsonNode probe = WorkflowDocuments.parse(workflowUsing(SETUP_HARNESS));
    WorkflowDocuments.LocalActions actions =
        probeActions(Map.of(SETUP_HARNESS, compositeAction(UNPINNED_ACTION, MISSING_ACTION)));

    assertThat(WorkflowPolicy.actionPinningViolations(probe)).isEmpty();
    assertThat(WorkflowPolicy.actionGraphViolations(probe, actions))
        .containsExactlyInAnyOrder(
            UNPINNED_ACTION
                + " used by "
                + SETUP_HARNESS
                + " is neither a ./ local action nor pinned to a commit SHA",
            MISSING_ACTION
                + " used by "
                + SETUP_HARNESS
                + " resolves to no local action definition");
  }

  @Test
  void workflowReferencingANonexistentLocalActionIsRejected() throws IOException {
    JsonNode probe = WorkflowDocuments.parse(workflowUsing(MISSING_ACTION));

    assertThat(WorkflowPolicy.actionPinningViolations(probe)).isEmpty();
    assertThat(WorkflowPolicy.actionGraphViolations(probe, probeActions(Map.of())))
        .containsExactly(
            MISSING_ACTION + " used by the workflow resolves to no local action definition");
  }

  /** Recursion, not one hop: an unpinned action two composites deep is still reached. */
  @Test
  void unpinnedActionNestedTwoCompositesDeepIsRejected() throws IOException {
    JsonNode probe = WorkflowDocuments.parse(workflowUsing(SETUP_HARNESS));
    Map<String, String> definitions = new LinkedHashMap<>();
    definitions.put(SETUP_HARNESS, compositeAction(PINNED_ACTION, NESTED_TOOLCHAIN));
    definitions.put(NESTED_TOOLCHAIN, compositeAction(UNPINNED_ACTION));

    assertThat(WorkflowPolicy.actionGraphViolations(probe, probeActions(definitions)))
        .containsExactly(
            UNPINNED_ACTION
                + " used by "
                + NESTED_TOOLCHAIN
                + " is neither a ./ local action nor pinned to a commit SHA");
  }

  static Stream<String> escapingLocalReferences() {
    return Stream.of(ESCAPING_ACTION, "./../../etc/actions", "./");
  }

  /** A nested local reference must stay inside this repository. */
  @ParameterizedTest(name = "the local reference {0} is rejected")
  @MethodSource("escapingLocalReferences")
  void localReferenceThatLeavesTheRepositoryIsRejected(String reference) throws IOException {
    JsonNode probe = WorkflowDocuments.parse(workflowUsing(SETUP_HARNESS));
    WorkflowDocuments.LocalActions actions =
        probeActions(Map.of(SETUP_HARNESS, compositeAction(reference)));

    assertThat(WorkflowDocuments.staysWithinRepository(reference)).isFalse();
    assertThat(WorkflowPolicy.actionGraphViolations(probe, actions))
        .containsExactly(
            reference + " used by " + SETUP_HARNESS + " resolves outside the repository");
  }

  @Test
  void wellFormedCompositeActionGraphIsAccepted() throws IOException {
    JsonNode probe = WorkflowDocuments.parse(workflowUsing(SETUP_HARNESS));
    Map<String, String> definitions = new LinkedHashMap<>();
    definitions.put(SETUP_HARNESS, compositeAction(PINNED_ACTION, NESTED_TOOLCHAIN));
    definitions.put(NESTED_TOOLCHAIN, compositeAction(PINNED_ACTION));

    assertThat(WorkflowPolicy.actionGraphViolations(probe, probeActions(definitions))).isEmpty();
  }

  /** A cyclic local reference must terminate rather than hang the scan. */
  @Test
  void cyclicLocalCompositeActionsTerminate() throws IOException {
    JsonNode probe = WorkflowDocuments.parse(workflowUsing(SETUP_HARNESS));
    Map<String, String> definitions = new LinkedHashMap<>();
    definitions.put(SETUP_HARNESS, compositeAction(NESTED_TOOLCHAIN));
    definitions.put(NESTED_TOOLCHAIN, compositeAction(SETUP_HARNESS));

    assertThat(WorkflowPolicy.actionGraphViolations(probe, probeActions(definitions))).isEmpty();
  }

  /**
   * The {@code .github/actions} scan holds a definition to the rules before any workflow wires it
   * up, so an unpinned action cannot be committed now and referenced in a later change.
   */
  @Test
  void compositeActionScanRejectsADefinitionNoWorkflowReferencesYet() throws IOException {
    JsonNode orphan = WorkflowDocuments.parse(compositeAction(UNPINNED_ACTION, MISSING_ACTION));

    assertThat(
            WorkflowPolicy.compositeActionViolations(SETUP_HARNESS, orphan, probeActions(Map.of())))
        .containsExactlyInAnyOrder(
            UNPINNED_ACTION
                + " used by "
                + SETUP_HARNESS
                + " is neither a ./ local action nor pinned to a commit SHA",
            MISSING_ACTION
                + " used by "
                + SETUP_HARNESS
                + " resolves to no local action definition");
  }

  @Test
  void compositeActionScanAcceptsAPinnedDefinition() throws IOException {
    JsonNode definition = WorkflowDocuments.parse(compositeAction(PINNED_ACTION));

    assertThat(
            WorkflowPolicy.compositeActionViolations(
                SETUP_HARNESS, definition, probeActions(Map.of())))
        .isEmpty();
  }

  private static final String ALPHA_JOB = "alpha-verify";

  private static final String BETA_JOB = "beta-verify";

  /**
   * A second workflow a pull request can start, with its own job and path names. Its result gate is
   * a merge-blocking check exactly like the first workflow's, so every gate rule must apply to it.
   */
  private static String secondPullRequestWorkflow(String gateScript) {
    return "name: Second\n"
        + "on:\n"
        + "  pull_request:\n"
        + "permissions:\n"
        + "  contents: read\n"
        + "concurrency:\n"
        + "  group: second\n"
        + "  cancel-in-progress: ${{ github.event_name == 'pull_request' }}\n"
        + "jobs:\n"
        + "  "
        + ALPHA_JOB
        + ":\n"
        + "    if: >-\n      "
        + WorkflowPolicy.FORK_CONDITION
        + "\n"
        + "    runs-on: ubuntu-latest\n"
        + "    steps:\n"
        + "      - run: echo alpha\n"
        + "  "
        + BETA_JOB
        + ":\n"
        + "    if: >-\n      "
        + WorkflowPolicy.TRUSTED_CONDITION
        + "\n"
        + "    runs-on: arc-java-build\n"
        + "    steps:\n"
        + "      - run: echo beta\n"
        + "  "
        + WorkflowPolicy.RESULT_JOB
        + ":\n"
        + "    if: always()\n"
        + "    needs:\n      - "
        + ALPHA_JOB
        + "\n      - "
        + BETA_JOB
        + "\n"
        + "    runs-on: ubuntu-latest\n"
        + "    steps:\n"
        + "      - env:\n"
        + "          NEEDS_JSON: ${{ toJSON(needs) }}\n"
        + "          VERIFICATION_PATHS: |\n"
        + "            alpha="
        + ALPHA_JOB
        + "\n            beta="
        + BETA_JOB
        + "\n"
        + "        run: |\n"
        + indent(gateScript, "          ");
  }

  private static String indent(String script, String prefix) {
    StringBuilder indented = new StringBuilder();
    for (String line : script.split("\n", -1)) {
      indented.append(line.isEmpty() ? "" : prefix + line).append('\n');
    }
    return indented.toString();
  }

  /** The repository's one gate implementation, read from the workflow that already carries it. */
  private static String repositoryGateScript() throws IOException {
    String workflow =
        Files.readString(
            RepositoryPaths.root().resolve(".github/workflows/ci.yml"), StandardCharsets.UTF_8);
    return WorkflowDocuments.runScript(
        WorkflowDocuments.parse(workflow), WorkflowPolicy.RESULT_JOB);
  }

  private static ResultGate secondGate(String gateScript) throws IOException {
    return ResultGate.of(
        "second.yml", WorkflowDocuments.parse(secondPullRequestWorkflow(gateScript)));
  }

  /** The truth table is derived from the second workflow's own paths, not from {@code ci.yml}. */
  @Test
  void secondPullRequestWorkflowGetsItsOwnTruthTable() throws IOException {
    ResultGate gate = secondGate(repositoryGateScript());

    assertThat(gate.pathNames()).containsExactly("alpha", "beta");
    assertThat(gate.neededJobs()).containsExactly(ALPHA_JOB, BETA_JOB);
    assertThat(gate.truthTable()).isNotEmpty();
  }

  @Test
  void secondPullRequestWorkflowWithAHonestGateSatisfiesItsTruthTable()
      throws IOException, InterruptedException {
    assumeTrue(ResultGate.isPosixShellAvailable());

    ResultGate gate = secondGate(repositoryGateScript());
    List<String> mismatches = new ArrayList<>();
    for (Arguments scenario : gate.truthTable()) {
      if (gate.run(resultsOf(scenario)) != expectedExitCodeOf(scenario)) {
        mismatches.add(nameOf(scenario));
      }
    }

    assertThat(mismatches).isEmpty();
  }

  /**
   * The false-green regression for a second workflow: a gate that unconditionally exits zero is a
   * required check that verifies nothing. Every rejecting case of the second workflow's own truth
   * table must catch it, which is what parameterizing the gate tests over every pull-request
   * workflow buys. Scanning only {@code ci.yml} would have reported this workflow as green.
   */
  @Test
  void secondPullRequestWorkflowWhoseGateAlwaysExitsZeroIsCaught()
      throws IOException, InterruptedException {
    assumeTrue(ResultGate.isPosixShellAvailable());

    ResultGate falseGreen = secondGate("exit 0\n");
    List<String> rejecting = new ArrayList<>();
    List<String> caught = new ArrayList<>();
    for (Arguments scenario : falseGreen.truthTable()) {
      if (expectedExitCodeOf(scenario) == 0) {
        continue;
      }
      rejecting.add(nameOf(scenario));
      if (falseGreen.run(resultsOf(scenario)) != expectedExitCodeOf(scenario)) {
        caught.add(nameOf(scenario));
      }
    }

    assertThat(rejecting).isNotEmpty();
    assertThat(caught).containsExactlyElementsOf(rejecting);
  }

  /** The static gate rules apply to a second workflow too, not only to the scanned first one. */
  @Test
  void secondPullRequestWorkflowThatClassifiesNoJobIsRejected() throws IOException {
    JsonNode probe =
        WorkflowDocuments.parse(
            secondPullRequestWorkflow(repositoryGateScript())
                .replace("            alpha=" + ALPHA_JOB + "\n", ""));

    assertThat(WorkflowPolicy.isPullRequestWorkflow(probe)).isTrue();
    assertThat(WorkflowPolicy.verificationPathViolations(probe, WorkflowPolicy.RESULT_JOB))
        .containsExactly(ALPHA_JOB + " belongs to no declared verification path");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, String> resultsOf(Arguments scenario) {
    return (Map<String, String>) scenario.get()[3];
  }

  private static int expectedExitCodeOf(Arguments scenario) {
    return (Integer) scenario.get()[4];
  }

  private static String nameOf(Arguments scenario) {
    return (String) scenario.get()[1];
  }

  static Stream<String> nonCompositeActionForms() {
    return Stream.of(
        "name: Probe action\nruns:\n  using: docker\n  image: docker://attacker/image:latest\n",
        "name: Probe action\nruns:\n  using: node20\n  main: attacker.js\n",
        "name: Probe action\nruns:\n  using: unknown-runtime\n",
        "name: Probe action\nruns:\n  steps:\n    - run: echo probe\n      shell: bash\n",
        "name: Probe action\n");
  }

  /**
   * A local action that is not a composite action declares no {@code steps}, so the graph walk
   * would resolve it, find no edge, and report it clean — while a {@code docker} action pulls a
   * mutable image this repository never reviewed. The form itself is therefore rejected.
   */
  @ParameterizedTest(name = "non-composite local action form {index} is rejected")
  @MethodSource("nonCompositeActionForms")
  void nonCompositeLocalActionIsRejected(String definition) throws IOException {
    JsonNode probe = WorkflowDocuments.parse(workflowUsing(SETUP_HARNESS));
    WorkflowDocuments.LocalActions actions = probeActions(Map.of(SETUP_HARNESS, definition));

    assertThat(WorkflowPolicy.actionPinningViolations(probe)).isEmpty();
    assertThat(WorkflowPolicy.actionGraphViolations(probe, actions))
        .singleElement()
        .asString()
        .startsWith(SETUP_HARNESS + " used by the workflow declares runs.using ")
        .endsWith(" instead of composite");
  }

  @Test
  void dockerLocalActionCannotSmuggleAMutableImage() throws IOException {
    JsonNode probe = WorkflowDocuments.parse(workflowUsing(SETUP_HARNESS));
    WorkflowDocuments.LocalActions actions =
        probeActions(
            Map.of(
                SETUP_HARNESS,
                "name: Probe action\nruns:\n  using: docker\n"
                    + "  image: docker://attacker/image:latest\n"));

    assertThat(WorkflowPolicy.actionGraphViolations(probe, actions))
        .containsExactly(
            SETUP_HARNESS
                + " used by the workflow declares runs.using 'docker' instead of composite");
  }

  @Test
  void compositeActionScanRejectsANonCompositeDefinition() throws IOException {
    JsonNode definition =
        WorkflowDocuments.parse(
            "name: Probe action\nruns:\n  using: docker\n  image: Dockerfile\n");

    assertThat(
            WorkflowPolicy.compositeActionViolations(
                SETUP_HARNESS, definition, probeActions(Map.of())))
        .containsExactly(SETUP_HARNESS + " declares runs.using 'docker' instead of composite");
  }

  /**
   * The rules above are driven with synthetic definitions, so nothing in them exercises the
   * discovery and resolution that decide whether a committed local action is scanned at all. These
   * probes run the real recursive scan and the real resolver against a fixture tree: a wrong
   * directory constant, a broken relativize, or an inverted containment check would otherwise ship
   * green.
   */
  @Test
  void repositoryScanFindsEveryActionDefinitionAtEveryDepth(@TempDir Path root) throws IOException {
    Path shallow = writeAction(root, ".github/actions/setup-harness/action.yml", PINNED_ACTION);
    Path deep =
        writeAction(root, ".github/actions/nested/deep/toolchain/action.yaml", UNPINNED_ACTION);
    Files.createDirectories(root.resolve(".github/actions/not-an-action"));
    Files.writeString(root.resolve(".github/actions/not-an-action/README.md"), "ignored");

    List<Path> found = WorkflowDocuments.actionFiles(root);

    assertThat(found).containsExactlyInAnyOrder(shallow, deep);
    assertThat(found)
        .map(definition -> WorkflowDocuments.actionReference(root, definition))
        .containsExactlyInAnyOrder(
            "./.github/actions/setup-harness", "./.github/actions/nested/deep/toolchain");
  }

  @Test
  void repositoryScanReturnsNothingWhenTheDirectoryIsAbsent(@TempDir Path root) throws IOException {
    assertThat(WorkflowDocuments.actionFiles(root)).isEmpty();
  }

  @Test
  void repositoryResolverReadsResolvesAndContainsLocalReferences(@TempDir Path root)
      throws IOException {
    writeAction(root, ".github/actions/setup-harness/action.yml", PINNED_ACTION);
    Files.writeString(
        root.resolve(".github/actions/setup-harness/action.yaml"),
        compositeAction(UNPINNED_ACTION));
    WorkflowDocuments.LocalActions actions = WorkflowDocuments.localActions(root);

    assertThat(WorkflowDocuments.actionStepReferences(actions.read(SETUP_HARNESS)))
        .as("action.yml must win over action.yaml, as GitHub resolves it")
        .containsExactly(PINNED_ACTION);
    assertThat(actions.read(MISSING_ACTION).isMissingNode()).isTrue();
    assertThat(actions.read("./").isMissingNode()).isTrue();
    assertThat(actions.read(ESCAPING_ACTION).isMissingNode()).isTrue();
    assertThat(actions.read(UNPINNED_ACTION).isMissingNode()).isTrue();
  }

  /** The containment check must hold even when the escaping path really exists on disk. */
  @Test
  void repositoryResolverRefusesToLeaveItsRoot(@TempDir Path enclosing) throws IOException {
    Path root = enclosing.resolve("repository");
    writeAction(enclosing, "attacker-actions/setup/action.yml", UNPINNED_ACTION);
    Files.createDirectories(root);

    assertThat(Files.isRegularFile(enclosing.resolve("attacker-actions/setup/action.yml")))
        .isTrue();
    assertThat(WorkflowDocuments.localActions(root).read(ESCAPING_ACTION).isMissingNode()).isTrue();
  }

  @Test
  void repositoryScanAndResolverRejectAnUnpinnedDefinitionOnDisk(@TempDir Path root)
      throws IOException {
    writeAction(root, ".github/actions/setup-harness/action.yml", NESTED_TOOLCHAIN);
    writeAction(root, ".github/actions/nested/toolchain/action.yml", UNPINNED_ACTION);
    WorkflowDocuments.LocalActions actions = WorkflowDocuments.localActions(root);

    List<String> violations = new ArrayList<>();
    for (Path definition : WorkflowDocuments.actionFiles(root)) {
      violations.addAll(
          WorkflowPolicy.compositeActionViolations(
              WorkflowDocuments.actionReference(root, definition),
              WorkflowDocuments.read(definition),
              actions));
    }

    assertThat(violations)
        .contains(
            UNPINNED_ACTION
                + " used by "
                + NESTED_TOOLCHAIN
                + " is neither a ./ local action nor pinned to a commit SHA");
  }

  private static Path writeAction(Path root, String relativePath, String... references)
      throws IOException {
    Path definition = root.resolve(relativePath);
    Path directory = definition.getParent();
    if (directory != null) {
      Files.createDirectories(directory);
    }
    Files.writeString(definition, compositeAction(references));
    return definition;
  }
}
