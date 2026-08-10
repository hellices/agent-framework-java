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

  private static final Pattern PINNED_EXTERNAL_REFERENCE =
      Pattern.compile("^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+(?:/[A-Za-z0-9._/-]+)?@[0-9a-f]{40}$");

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
}
