package com.microsoft.agentframework.build.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class WorkflowPolicyTest {

  private static final Set<String> ALLOWED_PERMISSION_VALUES = Set.of("read", "none");

  private static final String CANCEL_EXPRESSION = "${{ github.event_name == 'pull_request' }}";

  private static final String TRUSTED_LABEL = "arc-java-build";

  private static final String HOSTED_LABEL = "ubuntu-latest";

  private static final String RESULT_JOB = WorkflowPolicy.RESULT_JOB;

  private static final String FORK_PATH = "fork";

  private static final String TRUSTED_PATH = "trusted";

  private static final String NEEDS_JSON_VARIABLE = WorkflowPolicy.NEEDS_JSON_VARIABLE;

  private static final String NEEDS_JSON_EXPRESSION = WorkflowPolicy.NEEDS_JSON_EXPRESSION;

  private static final String VERIFICATION_PATHS_VARIABLE =
      WorkflowPolicy.VERIFICATION_PATHS_VARIABLE;

  private static final String CI_WORKFLOW = ".github/workflows/ci.yml";

  private static final String RUNNER_CONTRACT = "docs/operations/github-actions-runner-contract.md";

  static Stream<Path> workflows() throws IOException {
    return WorkflowDocuments.files().stream();
  }

  /**
   * Every workflow a pull request can start, not only {@code ci.yml}. The result gate rules below
   * are parameterized over this source, because a second pull-request workflow with a gate that
   * classifies nothing is a merge-blocking check that reports green without verifying anything.
   */
  static Stream<Path> pullRequestWorkflows() throws IOException {
    return pullRequestWorkflowFiles().stream();
  }

  private static List<Path> pullRequestWorkflowFiles() throws IOException {
    List<Path> matching = new ArrayList<>();
    for (Path workflow : WorkflowDocuments.files()) {
      if (WorkflowPolicy.isPullRequestWorkflow(WorkflowDocuments.read(workflow))) {
        matching.add(workflow);
      }
    }
    return matching;
  }

  private static String workflowName(Path workflow) {
    Path name = workflow.getFileName();
    return name == null ? workflow.toString() : name.toString();
  }

  @Test
  void repositoryDefinesAtLeastOneWorkflow() throws IOException {
    assertThat(WorkflowDocuments.files()).isNotEmpty();
  }

  @Test
  void repositoryDefinesAtLeastOnePullRequestWorkflow() throws IOException {
    assertThat(pullRequestWorkflowFiles()).isNotEmpty();
  }

  @ParameterizedTest(name = "{0} never uses pull_request_target")
  @MethodSource("workflows")
  void workflowNeverUsesPullRequestTarget(Path workflow) throws IOException {
    List<String> triggers = WorkflowDocuments.triggerNames(WorkflowDocuments.read(workflow));

    assertThat(triggers).doesNotContain("pull_request_target");
  }

  /**
   * The trusted condition treats every event that is not a pull request as trusted, so the set of
   * events that can start a workflow is itself part of the trust boundary and is allow listed.
   */
  @ParameterizedTest(name = "{0} declares only allow-listed triggers")
  @MethodSource("workflows")
  void workflowDeclaresOnlyAllowListedTriggers(Path workflow) throws IOException {
    JsonNode document = WorkflowDocuments.read(workflow);

    assertThat(WorkflowDocuments.triggerNames(document)).isNotEmpty();
    assertThat(WorkflowPolicy.triggerViolations(document)).isEmpty();
  }

  @Test
  void triggerAllowListIsExactlyThePullRequestPushAndDispatchEvents() {
    assertThat(WorkflowPolicy.ALLOWED_TRIGGERS)
        .containsExactlyInAnyOrder("pull_request", "push", "workflow_dispatch");
  }

  @Test
  void continuousIntegrationDeclaresOnlyTheAllowListedTriggers() throws IOException {
    JsonNode document = WorkflowDocuments.read(RepositoryPaths.root().resolve(CI_WORKFLOW));

    assertThat(WorkflowDocuments.triggerNames(document))
        .containsExactlyInAnyOrderElementsOf(WorkflowPolicy.ALLOWED_TRIGGERS);
  }

  @ParameterizedTest(name = "{0} pins every external reference")
  @MethodSource("workflows")
  void workflowPinsEveryExternalReference(Path workflow) throws IOException {
    JsonNode document = WorkflowDocuments.read(workflow);

    assertThat(WorkflowDocuments.stepActionReferences(document)).isNotEmpty();
    assertThat(WorkflowPolicy.actionPinningViolations(document)).isEmpty();
  }

  /**
   * Pinning stops at the workflow file: a {@code ./} reference is accepted on sight. This walks
   * through every local composite action a workflow can reach and applies the same rules there, so
   * a local action cannot be the place where {@code attacker/action@main} enters, and a {@code ./}
   * reference that resolves to nothing is rejected instead of silently trusted.
   */
  @ParameterizedTest(name = "{0} resolves and pins every action it can reach")
  @MethodSource("workflows")
  void workflowResolvesAndPinsEveryActionItCanReach(Path workflow) throws IOException {
    JsonNode document = WorkflowDocuments.read(workflow);

    assertThat(
            WorkflowPolicy.actionGraphViolations(document, WorkflowDocuments.repositoryActions()))
        .isEmpty();
  }

  /**
   * Scans {@code .github/actions} recursively so a composite action is held to the pinning and
   * resolution rules even before a workflow references it. The scan is currently empty; the rules
   * it applies are pinned by {@code WorkflowPolicyBypassProbeTest}.
   */
  @Test
  void everyLocalCompositeActionResolvesAndPinsItsOwnActions() throws IOException {
    WorkflowDocuments.LocalActions actions = WorkflowDocuments.repositoryActions();
    List<String> violations = new ArrayList<>();
    for (Path definition : WorkflowDocuments.actionFiles()) {
      String reference = WorkflowDocuments.actionReference(definition);
      violations.addAll(
          WorkflowPolicy.compositeActionViolations(
              reference, WorkflowDocuments.read(definition), actions));
    }

    assertThat(violations).isEmpty();
  }

  /** Every action definition the scan finds must be reachable through its own {@code ./} path. */
  @Test
  void everyLocalCompositeActionIsAddressableByItsReference() throws IOException {
    WorkflowDocuments.LocalActions actions = WorkflowDocuments.repositoryActions();
    for (Path definition : WorkflowDocuments.actionFiles()) {
      String reference = WorkflowDocuments.actionReference(definition);

      assertThat(WorkflowDocuments.staysWithinRepository(reference)).isTrue();
      assertThat(actions.read(reference).isMissingNode()).isFalse();
    }
  }

  @ParameterizedTest(name = "{0} never delegates a job to a reusable workflow")
  @MethodSource("workflows")
  void workflowNeverDelegatesJobsToReusableWorkflows(Path workflow) throws IOException {
    assertThat(WorkflowPolicy.jobLevelUsesViolations(WorkflowDocuments.read(workflow))).isEmpty();
  }

  @ParameterizedTest(name = "{0} uses only allowed runner labels")
  @MethodSource("workflows")
  void workflowUsesOnlyAllowedRunnerLabels(Path workflow) throws IOException {
    JsonNode document = WorkflowDocuments.read(workflow);
    List<String> selectors = new ArrayList<>();
    for (JsonNode job : WorkflowDocuments.jobs(document)) {
      selectors.addAll(WorkflowDocuments.runnerSelectors(job));
    }

    assertThat(selectors).isNotEmpty();
    assertThat(WorkflowPolicy.runnerViolations(document)).isEmpty();
  }

  @ParameterizedTest(name = "{0} declares least-privilege permissions")
  @MethodSource("workflows")
  void workflowDeclaresLeastPrivilegePermissions(Path workflow) throws IOException {
    JsonNode document = WorkflowDocuments.read(workflow);

    assertThat(document.path("permissions").isObject()).isTrue();
    assertThat(WorkflowDocuments.permissionValues(document)).isSubsetOf(ALLOWED_PERMISSION_VALUES);
  }

  @ParameterizedTest(name = "{0} never persists checkout credentials")
  @MethodSource("workflows")
  void workflowNeverPersistsCheckoutCredentials(Path workflow) throws IOException {
    for (JsonNode job : WorkflowDocuments.jobs(WorkflowDocuments.read(workflow))) {
      for (JsonNode step : WorkflowDocuments.steps(job)) {
        JsonNode uses = step.path("uses");
        if (uses.isTextual() && uses.textValue().startsWith("actions/checkout@")) {
          JsonNode persist = step.path("with").path("persist-credentials");
          assertThat(persist.isBoolean()).isTrue();
          assertThat(persist.booleanValue()).isFalse();
        }
      }
    }
  }

  @ParameterizedTest(name = "{0} cancels only pull request runs")
  @MethodSource("workflows")
  void workflowCancelsOnlyPullRequestRuns(Path workflow) throws IOException {
    JsonNode concurrency = WorkflowDocuments.read(workflow).path("concurrency");

    assertThat(concurrency.isObject()).isTrue();
    assertThat(concurrency.path("cancel-in-progress").textValue()).isEqualTo(CANCEL_EXPRESSION);
  }

  @ParameterizedTest(name = "{0} gates every trusted runner job")
  @MethodSource("workflows")
  void trustedJobsRequireTheSameRepositoryCondition(Path workflow) throws IOException {
    assertThat(WorkflowPolicy.trustedRunnerConditionViolations(WorkflowDocuments.read(workflow)))
        .isEmpty();
  }

  @ParameterizedTest(name = "{0} isolates fork verification on hosted runners")
  @MethodSource("pullRequestWorkflows")
  void forkVerificationRunsOnHostedRunnersOnly(Path workflow) throws IOException {
    JsonNode document = WorkflowDocuments.read(workflow);

    List<String> forkJobs = new ArrayList<>();
    for (String jobName : WorkflowDocuments.jobNames(document)) {
      JsonNode job = WorkflowDocuments.job(document, jobName);
      if (WorkflowPolicy.isForkCondition(job)) {
        forkJobs.add(jobName);
        assertThat(WorkflowDocuments.runnerSelectors(job)).containsExactly(HOSTED_LABEL);
      }
    }

    assertThat(forkJobs).isNotEmpty();
  }

  @ParameterizedTest(name = "{0} keeps the trusted and fork paths mutually exclusive")
  @MethodSource("pullRequestWorkflows")
  void trustedAndForkConditionsAreMutuallyExclusive(Path workflow) throws IOException {
    JsonNode document = WorkflowDocuments.read(workflow);

    for (String jobName : WorkflowDocuments.jobNames(document)) {
      JsonNode job = WorkflowDocuments.job(document, jobName);
      assertThat(WorkflowPolicy.isTrustedCondition(job) && WorkflowPolicy.isForkCondition(job))
          .as("%s cannot belong to both verification paths", jobName)
          .isFalse();
      if (WorkflowDocuments.runnerSelectors(job).contains(TRUSTED_LABEL)) {
        assertThat(WorkflowPolicy.isForkCondition(job))
            .as("%s must never run under the fork condition", jobName)
            .isFalse();
      }
    }
  }

  @Test
  void runnerContractDocumentsTheDeployedRunnerLabels() throws IOException {
    String contract = readRunnerContract();

    for (String selector : WorkflowPolicy.ALLOWED_RUNNER_SELECTORS) {
      assertThat(contract).contains(selector);
    }
  }

  @Test
  void runnerContractExplainsTrustedRunnerAvailability() throws IOException {
    String contract = readRunnerContract();

    // The contract must keep explaining where the trusted label comes from, what an unavailable
    // scale set looks like, and why that blocks a green result. The heading text is deliberately
    // not asserted so the document can describe registration and relocation, not just first setup.
    assertThat(contract).contains("`arc-java-build`");
    assertThat(contract).contains("agent-framework-java-platform");
    assertThat(contract).contains("verify-result");
    assertThat(contract).contains("queued");

    // These two carry the failure that cost the most time: changing the URL alone leaves the scale
    // set id from the previous repository cached, and the listener then crash-loops. Generic words
    // above would survive a rewrite that dropped the recovery procedure, so assert the tokens that
    // only this procedure uses.
    assertThat(contract).contains("runner-scale-set-id");
    assertThat(contract).contains("helm uninstall");
  }

  @Test
  void runnerContractDocumentsTheTriggerAllowList() throws IOException {
    String contract = readRunnerContract();

    assertThat(contract).contains("## Trigger allow list");
    for (String trigger : WorkflowPolicy.ALLOWED_TRIGGERS) {
      assertThat(contract).contains("`" + trigger + "`");
    }
    assertThat(contract).contains("workflow_run");
  }

  @Test
  void runnerContractDocumentsTheGenericResultGate() throws IOException {
    String contract = readRunnerContract();

    assertThat(contract).contains(NEEDS_JSON_VARIABLE).contains(VERIFICATION_PATHS_VARIABLE);
    assertThat(contract).contains("belongs to no path");
    assertThat(contract).contains("every workflow a pull request can start");
  }

  @Test
  void runnerContractDocumentsTheLocalCompositeActionRules() throws IOException {
    String contract = readRunnerContract();

    assertThat(contract).contains("## Local composite actions");
    assertThat(contract).contains(".github/actions");
  }

  private static String readRunnerContract() throws IOException {
    return Files.readString(
        RepositoryPaths.root().resolve(RUNNER_CONTRACT), StandardCharsets.UTF_8);
  }

  @ParameterizedTest(name = "{0} fans in to the required result job")
  @MethodSource("pullRequestWorkflows")
  void pullRequestWorkflowFansInToTheRequiredResultJob(Path workflow) throws IOException {
    JsonNode document = WorkflowDocuments.read(workflow);

    List<String> jobNames = new ArrayList<>(WorkflowDocuments.jobNames(document));
    assertThat(jobNames).contains(RESULT_JOB);

    JsonNode resultJob = document.path("jobs").path(RESULT_JOB);
    assertThat(resultJob.path("if").textValue()).isEqualTo("always()");

    List<String> needs = new ArrayList<>();
    resultJob.path("needs").forEach(need -> needs.add(need.textValue()));
    jobNames.remove(RESULT_JOB);

    assertThat(needs).containsExactlyInAnyOrderElementsOf(jobNames);
  }

  @Test
  void dependencyUpdatesTrackGradleAndActions() throws IOException {
    String text =
        Files.readString(
            RepositoryPaths.root().resolve(".github/dependabot.yml"), StandardCharsets.UTF_8);

    assertThat(text).contains("package-ecosystem: \"gradle\"");
    assertThat(text).contains("directory: \"/build-logic\"");
    assertThat(text).contains("package-ecosystem: \"github-actions\"");
  }

  /**
   * The gate must read the whole {@code needs} context, never one hand-written variable per job, so
   * a verification job added later cannot stay unexamined. This holds for every workflow a pull
   * request can start, because each of them fans in to a merge-blocking {@code verify-result}.
   */
  @ParameterizedTest(name = "{0} consumes the whole needs context")
  @MethodSource("pullRequestWorkflows")
  void resultGateConsumesTheWholeNeedsContext(Path workflow) throws IOException {
    JsonNode document = WorkflowDocuments.read(workflow);
    JsonNode step = WorkflowDocuments.firstRunStep(document, RESULT_JOB);
    assertThat(step.isObject()).isTrue();

    Map<String, String> environment = WorkflowDocuments.stepEnvironment(step);
    assertThat(environment).containsEntry(NEEDS_JSON_VARIABLE, NEEDS_JSON_EXPRESSION);
    assertThat(environment).containsKey(VERIFICATION_PATHS_VARIABLE);

    String script = WorkflowDocuments.runScript(document, RESULT_JOB);
    assertThat(script).isNotBlank();
    assertThat(script).contains(NEEDS_JSON_VARIABLE).contains(VERIFICATION_PATHS_VARIABLE);
    assertThat(environment.values()).noneMatch(value -> value.contains(".result"));
  }

  @ParameterizedTest(name = "{0} classifies every needed job into exactly one path")
  @MethodSource("pullRequestWorkflows")
  void resultGateAssignsEveryNeededJobToExactlyOneVerificationPath(Path workflow)
      throws IOException {
    JsonNode document = WorkflowDocuments.read(workflow);

    assertThat(WorkflowPolicy.verificationPathViolations(document, RESULT_JOB)).isEmpty();
    assertThat(WorkflowPolicy.verificationPaths(document, RESULT_JOB)).isNotEmpty();
  }

  @Test
  void continuousIntegrationDeclaresExactlyTheForkAndTrustedPaths() throws IOException {
    JsonNode document = WorkflowDocuments.read(RepositoryPaths.root().resolve(CI_WORKFLOW));

    assertThat(WorkflowPolicy.verificationPaths(document, RESULT_JOB))
        .containsOnlyKeys(FORK_PATH, TRUSTED_PATH);
  }

  /**
   * Every needs context each pull-request gate must judge, derived from that workflow's own
   * verification paths rather than from a table hand-written for {@code ci.yml}. A second workflow
   * therefore arrives with its own truth table instead of inheriting the first one's coverage.
   */
  static Stream<Arguments> resultGateScenarios() throws IOException {
    List<Arguments> scenarios = new ArrayList<>();
    for (Path workflow : pullRequestWorkflowFiles()) {
      scenarios.addAll(
          ResultGate.of(workflowName(workflow), WorkflowDocuments.read(workflow)).truthTable());
    }
    return scenarios.stream();
  }

  /**
   * Executes each workflow's real {@code verify-result} script against its full truth table.
   * Replacing any of those scripts with an unconditional {@code exit 0} fails every case that must
   * exit non-zero.
   */
  @ParameterizedTest(name = "{0}: {1} exits with {4}")
  @MethodSource("resultGateScenarios")
  void resultGateAcceptsOnlyOneCompleteVerificationPath(
      String workflow,
      String scenario,
      ResultGate gate,
      Map<String, String> results,
      int expectedExitCode)
      throws IOException, InterruptedException {
    assumeTrue(ResultGate.isPosixShellAvailable());

    assertThat(gate.run(results)).isEqualTo(expectedExitCode);
  }

  /** The generated truth table must actually accept something, or it proves nothing. */
  @ParameterizedTest(name = "{0} has a completable verification path")
  @MethodSource("pullRequestWorkflows")
  void resultGateTruthTableCoversAnAcceptingAndARejectingCase(Path workflow) throws IOException {
    List<Arguments> table =
        ResultGate.of(workflowName(workflow), WorkflowDocuments.read(workflow)).truthTable();

    assertThat(table).anyMatch(scenario -> exitCodeOf(scenario) == 0);
    assertThat(table).anyMatch(scenario -> exitCodeOf(scenario) != 0);
  }

  private static int exitCodeOf(Arguments scenario) {
    Object[] values = scenario.get();
    return (Integer) values[values.length - 1];
  }
}
