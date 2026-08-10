package com.microsoft.agentframework.build.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
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

  private static final String RESULT_JOB = "verify-result";

  private static final String FORK_JOB = "fork-verify";

  private static final String TRUSTED_QUALITY_JOB = "trusted-quality";

  private static final String TRUSTED_COMPATIBILITY_JOB = "trusted-compatibility";

  private static final String FORK_PATH = "fork";

  private static final String TRUSTED_PATH = "trusted";

  private static final String NEEDS_JSON_VARIABLE = "NEEDS_JSON";

  private static final String NEEDS_JSON_EXPRESSION = "${{ toJSON(needs) }}";

  private static final String VERIFICATION_PATHS_VARIABLE = "VERIFICATION_PATHS";

  private static final String CI_WORKFLOW = ".github/workflows/ci.yml";

  private static final String RUNNER_CONTRACT = "docs/operations/github-actions-runner-contract.md";

  private static final String POSIX_SHELL = "sh";

  static Stream<Path> workflows() throws IOException {
    return WorkflowDocuments.files().stream();
  }

  @Test
  void repositoryDefinesAtLeastOneWorkflow() throws IOException {
    assertThat(WorkflowDocuments.files()).isNotEmpty();
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
  @MethodSource("workflows")
  void forkVerificationRunsOnHostedRunnersOnly(Path workflow) throws IOException {
    JsonNode document = WorkflowDocuments.read(workflow);
    assumeTrue(WorkflowDocuments.triggerNames(document).contains("pull_request"));

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
  @MethodSource("workflows")
  void trustedAndForkConditionsAreMutuallyExclusive(Path workflow) throws IOException {
    JsonNode document = WorkflowDocuments.read(workflow);
    assumeTrue(WorkflowDocuments.triggerNames(document).contains("pull_request"));

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
  void runnerContractBlocksMergingBeforeTheTrustedScaleSetExists() throws IOException {
    String contract = readRunnerContract();

    assertThat(contract).contains("## Merge gate: do not merge before `arc-java-build` exists");
    assertThat(contract).contains("agent-framework-java-platform");
    assertThat(contract).contains("verify-result");
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
  }

  private static String readRunnerContract() throws IOException {
    return Files.readString(
        RepositoryPaths.root().resolve(RUNNER_CONTRACT), StandardCharsets.UTF_8);
  }

  @ParameterizedTest(name = "{0} fans in to the required result job")
  @MethodSource("workflows")
  void pullRequestWorkflowFansInToTheRequiredResultJob(Path workflow) throws IOException {
    JsonNode document = WorkflowDocuments.read(workflow);
    assumeTrue(WorkflowDocuments.triggerNames(document).contains("pull_request"));

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
   * a verification job added later cannot stay unexamined.
   */
  @Test
  void resultGateConsumesTheWholeNeedsContext() throws IOException {
    Map<String, String> environment = WorkflowDocuments.stepEnvironment(resultGateStep());

    assertThat(environment).containsEntry(NEEDS_JSON_VARIABLE, NEEDS_JSON_EXPRESSION);
    assertThat(environment).containsKey(VERIFICATION_PATHS_VARIABLE);

    String script = resultGateScript();
    assertThat(script).contains(NEEDS_JSON_VARIABLE).contains(VERIFICATION_PATHS_VARIABLE);
    assertThat(environment.values()).noneMatch(value -> value.contains(".result"));
  }

  @Test
  void resultGateAssignsEveryNeededJobToExactlyOneVerificationPath() throws IOException {
    JsonNode document = WorkflowDocuments.read(RepositoryPaths.root().resolve(CI_WORKFLOW));

    assertThat(WorkflowPolicy.verificationPathViolations(document, RESULT_JOB)).isEmpty();
    assertThat(WorkflowPolicy.verificationPaths(document, RESULT_JOB))
        .containsOnlyKeys(FORK_PATH, TRUSTED_PATH);
  }

  static Stream<Arguments> resultGateCases() {
    return Stream.of(
        arguments("skipped", "success", "success", 0),
        arguments("success", "skipped", "skipped", 0),
        arguments("skipped", "failure", "success", 1),
        arguments("skipped", "success", "failure", 1),
        arguments("skipped", "success", "cancelled", 1),
        arguments("skipped", "success", "skipped", 1),
        arguments("skipped", "skipped", "skipped", 1),
        arguments("failure", "skipped", "skipped", 1),
        arguments("cancelled", "skipped", "skipped", 1),
        arguments("success", "success", "success", 1),
        arguments("failure", "failure", "failure", 1));
  }

  /**
   * Executes the real {@code verify-result} script against the full result truth table. Replacing
   * the script with an unconditional {@code exit 0} fails every case that must exit non-zero.
   */
  @ParameterizedTest(name = "fork={0} quality={1} compatibility={2} exits with {3}")
  @MethodSource("resultGateCases")
  void resultGateAcceptsOnlyOneCompleteVerificationPath(
      String fork, String quality, String compatibility, int expectedExitCode)
      throws IOException, InterruptedException {
    assumeTrue(isPosixShellAvailable());

    assertThat(runResultGate(needsResults(fork, quality, compatibility)))
        .isEqualTo(expectedExitCode);
  }

  static Stream<String> addedVerificationJobResults() {
    return Stream.of("success", "skipped", "failure", "cancelled");
  }

  /**
   * Regression for the false-green hole: a verification job that is wired into {@code needs} but
   * into no verification path fails the gate, whatever it reported, so adding a job without
   * classifying it can never be reported as a completed verification.
   */
  @ParameterizedTest(name = "an added verification job reporting {0} cannot report green")
  @MethodSource("addedVerificationJobResults")
  void resultGateRejectsAJobThatBelongsToNoVerificationPath(String addedResult)
      throws IOException, InterruptedException {
    assumeTrue(isPosixShellAvailable());

    Map<String, String> results = needsResults("skipped", "success", "success");
    results.put("added-verify", addedResult);

    assertThat(runResultGate(results)).isEqualTo(1);
  }

  @Test
  void resultGateRejectsAVerificationJobThatDisappearedFromNeeds()
      throws IOException, InterruptedException {
    assumeTrue(isPosixShellAvailable());

    Map<String, String> results = needsResults("skipped", "success", "success");
    results.remove(TRUSTED_COMPATIBILITY_JOB);

    assertThat(runResultGate(results)).isEqualTo(1);
  }

  @Test
  void resultGateRejectsAnEmptyNeedsContext() throws IOException, InterruptedException {
    assumeTrue(isPosixShellAvailable());

    assertThat(runResultGate(new LinkedHashMap<>())).isEqualTo(1);
    assertThat(runResultGate(null)).isEqualTo(1);
  }

  private static boolean isPosixShellAvailable() {
    return !System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
  }

  private static Map<String, String> needsResults(
      String fork, String quality, String compatibility) {
    Map<String, String> results = new LinkedHashMap<>();
    results.put(FORK_JOB, fork);
    results.put(TRUSTED_QUALITY_JOB, quality);
    results.put(TRUSTED_COMPATIBILITY_JOB, compatibility);
    return results;
  }

  /** Renders the {@code needs} context exactly as {@code toJSON(needs)} does. */
  private static String renderNeedsJson(Map<String, String> results) {
    StringBuilder json = new StringBuilder("{");
    for (Map.Entry<String, String> entry : results.entrySet()) {
      if (json.length() > 1) {
        json.append(',');
      }
      json.append("\n  \"")
          .append(entry.getKey())
          .append("\": {\n    \"result\": \"")
          .append(entry.getValue())
          .append("\",\n    \"outputs\": {}\n  }");
    }
    return json.append("\n}").toString();
  }

  private static JsonNode resultGateStep() throws IOException {
    JsonNode document = WorkflowDocuments.read(RepositoryPaths.root().resolve(CI_WORKFLOW));
    JsonNode step = WorkflowDocuments.firstRunStep(document, RESULT_JOB);

    assertThat(step.isObject()).isTrue();
    return step;
  }

  private static String resultGateScript() throws IOException {
    Path workflow = RepositoryPaths.root().resolve(CI_WORKFLOW);
    String script = WorkflowDocuments.runScript(WorkflowDocuments.read(workflow), RESULT_JOB);

    assertThat(script).isNotBlank();
    return script;
  }

  private static int runResultGate(Map<String, String> results)
      throws IOException, InterruptedException {
    ProcessBuilder builder = new ProcessBuilder(POSIX_SHELL, "-s");
    builder.redirectErrorStream(true);
    Map<String, String> environment = builder.environment();
    environment.put(NEEDS_JSON_VARIABLE, results == null ? "" : renderNeedsJson(results));
    environment.put(
        VERIFICATION_PATHS_VARIABLE,
        WorkflowDocuments.stepEnvironment(resultGateStep()).get(VERIFICATION_PATHS_VARIABLE));

    Process gate = builder.start();
    try (OutputStream commands = gate.getOutputStream()) {
      commands.write(resultGateScript().getBytes(StandardCharsets.UTF_8));
    }
    try (InputStream output = gate.getInputStream()) {
      output.readAllBytes();
    }
    assertThat(gate.waitFor(60, TimeUnit.SECONDS)).isTrue();
    return gate.exitValue();
  }
}
