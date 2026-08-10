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

  private static final String TRUSTED_CONDITION =
      "github.event_name != 'pull_request' "
          + "|| github.event.pull_request.head.repo.full_name == github.repository";

  private static final String FORK_CONDITION =
      "github.event_name == 'pull_request' "
          + "&& github.event.pull_request.head.repo.full_name != github.repository";

  private static final String CANCEL_EXPRESSION = "${{ github.event_name == 'pull_request' }}";

  private static final String TRUSTED_LABEL = "arc-java-build";

  private static final String HOSTED_LABEL = "ubuntu-latest";

  private static final String RESULT_JOB = "verify-result";

  private static final String CI_WORKFLOW = ".github/workflows/ci.yml";

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
    JsonNode document = WorkflowDocuments.read(workflow);
    List<String> jobNames = WorkflowDocuments.jobNames(document);

    for (String jobName : jobNames) {
      JsonNode job = document.path("jobs").path(jobName);
      if (WorkflowDocuments.runnerLabels(job).contains(TRUSTED_LABEL)) {
        assertThat(job.path("if").textValue())
            .as("%s must only run trusted code", jobName)
            .isEqualTo(TRUSTED_CONDITION);
      }
    }
  }

  @ParameterizedTest(name = "{0} isolates fork verification on hosted runners")
  @MethodSource("workflows")
  void forkVerificationRunsOnHostedRunnersOnly(Path workflow) throws IOException {
    JsonNode document = WorkflowDocuments.read(workflow);
    assumeTrue(WorkflowDocuments.triggerNames(document).contains("pull_request"));

    List<String> forkJobs = new ArrayList<>();
    for (String jobName : WorkflowDocuments.jobNames(document)) {
      JsonNode job = document.path("jobs").path(jobName);
      if (FORK_CONDITION.equals(job.path("if").textValue())) {
        forkJobs.add(jobName);
        assertThat(WorkflowDocuments.runnerLabels(job)).containsExactly(HOSTED_LABEL);
      }
    }

    assertThat(forkJobs).isNotEmpty();
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

  @Test
  void resultGateReadsEveryVerificationJobResult() throws IOException {
    String script = resultGateScript();

    assertThat(script)
        .contains("FORK_RESULT")
        .contains("TRUSTED_QUALITY_RESULT")
        .contains("TRUSTED_COMPATIBILITY_RESULT");
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
    assumeTrue(!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"));

    assertThat(runResultGate(fork, quality, compatibility)).isEqualTo(expectedExitCode);
  }

  private static String resultGateScript() throws IOException {
    Path workflow = RepositoryPaths.root().resolve(CI_WORKFLOW);
    String script = WorkflowDocuments.runScript(WorkflowDocuments.read(workflow), RESULT_JOB);

    assertThat(script).isNotBlank();
    return script;
  }

  private static int runResultGate(String fork, String quality, String compatibility)
      throws IOException, InterruptedException {
    ProcessBuilder builder = new ProcessBuilder(POSIX_SHELL, "-s");
    builder.redirectErrorStream(true);
    Map<String, String> environment = builder.environment();
    environment.put("FORK_RESULT", fork);
    environment.put("TRUSTED_QUALITY_RESULT", quality);
    environment.put("TRUSTED_COMPATIBILITY_RESULT", compatibility);

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
