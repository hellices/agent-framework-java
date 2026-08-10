package com.microsoft.agentframework.build.harness;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Fail-closed workflow rules shared by the scan of the real workflows and by the bypass probes that
 * feed the rules synthetic documents.
 */
final class WorkflowPolicy {

  static final Set<String> ALLOWED_RUNNER_SELECTORS = Set.of("arc-java-build", "ubuntu-latest");

  static final String TRUSTED_RUNNER_SELECTOR = "arc-java-build";

  /**
   * The only condition under which a job may run on the trusted runner: never on a pull request
   * that comes from a fork.
   */
  static final String TRUSTED_CONDITION =
      "github.event_name != 'pull_request'"
          + " || github.event.pull_request.head.repo.full_name == github.repository";

  /** The exact negation of {@link #TRUSTED_CONDITION}, so the two paths cannot both run. */
  static final String FORK_CONDITION =
      "github.event_name == 'pull_request'"
          + " && github.event.pull_request.head.repo.full_name != github.repository";

  private static final Pattern PINNED_EXTERNAL_REFERENCE =
      Pattern.compile("^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+(?:/[A-Za-z0-9._/-]+)?@[0-9a-f]{40}$");

  private static final Pattern EXPRESSION_WRAPPER =
      Pattern.compile("^\\$\\{\\{(?<body>.*)}}$", Pattern.DOTALL);

  private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

  private WorkflowPolicy() {}

  /**
   * Every job must declare a {@code runs-on} this policy can parse, and every selector it resolves
   * to must be allow listed. A missing, unparsed, or unknown selector is a violation so that no job
   * can reach an unreviewed runner by using a form the parser does not understand.
   */
  static List<String> runnerViolations(JsonNode workflow) {
    List<String> violations = new ArrayList<>();
    for (String jobName : WorkflowDocuments.jobNames(workflow)) {
      JsonNode job = WorkflowDocuments.job(workflow, jobName);
      if (!WorkflowDocuments.declaresRunsOn(job)) {
        violations.add(jobName + " declares no runs-on");
        continue;
      }
      if (!WorkflowDocuments.declaresRecognizedRunsOn(job)) {
        violations.add(jobName + " declares an unrecognized runs-on form");
        continue;
      }
      for (String selector : WorkflowDocuments.runnerSelectors(job)) {
        if (!ALLOWED_RUNNER_SELECTORS.contains(selector)) {
          violations.add(jobName + " selects the forbidden runner " + selector);
        }
      }
    }
    return violations;
  }

  /**
   * Job-level {@code uses} delegates the whole job to a reusable workflow that carries its own
   * {@code runs-on}, which would move execution off the reviewed runner allow list. Step-level
   * {@code uses} is untouched, so local composite actions stay available.
   */
  static List<String> jobLevelUsesViolations(JsonNode workflow) {
    List<String> violations = new ArrayList<>();
    for (String jobName : WorkflowDocuments.jobNames(workflow)) {
      JsonNode uses = WorkflowDocuments.jobUses(WorkflowDocuments.job(workflow, jobName));
      if (!uses.isMissingNode() && !uses.isNull()) {
        violations.add(jobName + " delegates to the reusable workflow " + uses.asText());
      }
    }
    return violations;
  }

  /** Step actions must be local {@code ./} composite actions or pinned to a full commit SHA. */
  static List<String> actionPinningViolations(JsonNode workflow) {
    List<String> violations = new ArrayList<>();
    for (String reference : WorkflowDocuments.stepActionReferences(workflow)) {
      boolean local = reference.startsWith("./");
      boolean pinned = PINNED_EXTERNAL_REFERENCE.matcher(reference).matches();
      if (!local && !pinned) {
        violations.add(reference + " is neither a ./ local action nor pinned to a commit SHA");
      }
    }
    return violations;
  }

  /**
   * Normalizes a job condition so that only its meaning is compared. YAML line folding,
   * indentation, and the optional {@code ${{ }}} wrapper are formatting that GitHub Actions treats
   * identically, so they are erased. Nothing else is rewritten: operators, operands, and their
   * order survive, so a weakened condition never normalizes to an accepted one.
   */
  static String normalizedCondition(JsonNode job) {
    JsonNode condition = job.path("if");
    if (!condition.isTextual()) {
      return "";
    }
    String text = condition.textValue().trim();
    var wrapper = EXPRESSION_WRAPPER.matcher(text);
    if (wrapper.matches()) {
      text = wrapper.group("body").trim();
    }
    return WHITESPACE_RUN.matcher(text).replaceAll(" ").trim();
  }

  static boolean hasCondition(JsonNode job, String expectedCondition) {
    return normalizedCondition(job)
        .equals(WHITESPACE_RUN.matcher(expectedCondition).replaceAll(" "));
  }

  static boolean isTrustedCondition(JsonNode job) {
    return hasCondition(job, TRUSTED_CONDITION);
  }

  static boolean isForkCondition(JsonNode job) {
    return hasCondition(job, FORK_CONDITION);
  }

  /**
   * Every job that selects the trusted runner must carry exactly the trusted condition, so fork
   * pull-request code can never execute on {@code arc-java-build}.
   */
  static List<String> trustedRunnerConditionViolations(JsonNode workflow) {
    List<String> violations = new ArrayList<>();
    for (String jobName : WorkflowDocuments.jobNames(workflow)) {
      JsonNode job = WorkflowDocuments.job(workflow, jobName);
      if (!WorkflowDocuments.runnerSelectors(job).contains(TRUSTED_RUNNER_SELECTOR)) {
        continue;
      }
      if (!isTrustedCondition(job)) {
        violations.add(
            jobName
                + " runs on "
                + TRUSTED_RUNNER_SELECTOR
                + " under the condition \""
                + normalizedCondition(job)
                + "\" instead of the trusted condition");
      }
    }
    return violations;
  }
}
