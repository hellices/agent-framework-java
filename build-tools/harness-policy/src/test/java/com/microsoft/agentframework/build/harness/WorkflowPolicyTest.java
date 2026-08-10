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
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class WorkflowPolicyTest {

  private static final Pattern PINNED_EXTERNAL_REFERENCE =
      Pattern.compile("^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+(?:/[A-Za-z0-9._/-]+)?@[0-9a-f]{40}$");

  private static final Set<String> ALLOWED_RUNNER_LABELS =
      Set.of("arc-java-build", "ubuntu-latest");

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
    List<String> references = WorkflowDocuments.actionReferences(WorkflowDocuments.read(workflow));

    assertThat(references).isNotEmpty();
    for (String reference : references) {
      boolean local = reference.startsWith("./");
      boolean pinned = PINNED_EXTERNAL_REFERENCE.matcher(reference).matches();
      assertThat(local || pinned)
          .as("%s must be a ./ local action or pinned to a full commit SHA", reference)
          .isTrue();
    }
  }

  @ParameterizedTest(name = "{0} uses only allowed runner labels")
  @MethodSource("workflows")
  void workflowUsesOnlyAllowedRunnerLabels(Path workflow) throws IOException {
    List<String> labels = new ArrayList<>();
    for (JsonNode job : WorkflowDocuments.jobs(WorkflowDocuments.read(workflow))) {
      labels.addAll(WorkflowDocuments.runnerLabels(job));
    }

    assertThat(labels).isNotEmpty();
    assertThat(labels).isSubsetOf(ALLOWED_RUNNER_LABELS);
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
}
