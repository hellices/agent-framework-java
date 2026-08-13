package io.github.hellices.agentframework.build.harness;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Fail-closed workflow rules shared by the scan of the real workflows and by the bypass probes that
 * feed the rules synthetic documents.
 */
final class WorkflowPolicy {

  static final Set<String> ALLOWED_RUNNER_SELECTORS = Set.of("arc-java-build", "ubuntu-latest");

  /**
   * The exact set of events allowed to start a workflow. The trusted condition asks only whether
   * the event is a pull request, so every other event is trusted by construction: a {@code
   * workflow_run}, {@code issue_comment}, or {@code repository_dispatch} job carrying the verbatim
   * trusted condition would reach {@code arc-java-build}. Allow listing the triggers is what keeps
   * that set of events reviewed.
   */
  static final Set<String> ALLOWED_TRIGGERS = Set.of("pull_request", "push", "workflow_dispatch");

  static final String TRUSTED_RUNNER_SELECTOR = "arc-java-build";

  /**
   * The fan-in job every pull-request workflow must end in. It is a policy constant, not a detail
   * of one workflow, because every rule that keeps a verification path honest is applied to the
   * gate of every pull-request workflow.
   */
  static final String RESULT_JOB = "verify-result";

  /** The whole {@code needs} context, which the result gate must consume instead of one job. */
  static final String NEEDS_JSON_VARIABLE = "NEEDS_JSON";

  static final String NEEDS_JSON_EXPRESSION = "${{ toJSON(needs) }}";

  /** The declarative {@code name=job[,job]} verification path map the result gate evaluates. */
  static final String VERIFICATION_PATHS_VARIABLE = "VERIFICATION_PATHS";

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

  private static final String UNPINNED_MESSAGE =
      " is neither a ./ local action nor pinned to a commit SHA";

  private static final String ESCAPING_MESSAGE = " resolves outside the repository";

  private static final String UNRESOLVED_MESSAGE = " resolves to no local action definition";

  private static final String COMPOSITE_MESSAGE = " instead of composite";

  /** The origin recorded for a reference a workflow declares directly. */
  private static final String WORKFLOW_ORIGIN = "the workflow";

  private static final Pattern EXPRESSION_WRAPPER =
      Pattern.compile("^\\$\\{\\{(?<body>.*)}}$", Pattern.DOTALL);

  private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

  private WorkflowPolicy() {}

  /**
   * Every workflow must declare at least one trigger, and every trigger it declares must be allow
   * listed. An unknown event is rejected rather than inspected, so widening the trust boundary
   * always requires editing this list.
   */
  static List<String> triggerViolations(JsonNode workflow) {
    List<String> triggers = WorkflowDocuments.triggerNames(workflow);
    if (triggers.isEmpty()) {
      return List.of("declares no trigger");
    }
    List<String> violations = new ArrayList<>();
    for (String trigger : triggers) {
      if (trigger == null || !ALLOWED_TRIGGERS.contains(trigger)) {
        violations.add(trigger + " is not an allowed trigger");
      }
    }
    return violations;
  }

  /**
   * Reads the {@code name=job[,job]} verification path map the result gate declares. Returns an
   * empty map when the gate declares none, so a missing map is reported by {@link
   * #verificationPathViolations(JsonNode, String)} rather than silently accepted.
   */
  static Map<String, List<String>> verificationPaths(JsonNode workflow, String resultJobName) {
    Map<String, List<String>> paths = new LinkedHashMap<>();
    String declared =
        WorkflowDocuments.stepEnvironment(WorkflowDocuments.firstRunStep(workflow, resultJobName))
            .getOrDefault(VERIFICATION_PATHS_VARIABLE, "");
    for (String definition : WHITESPACE_RUN.split(declared.trim())) {
      if (definition.isEmpty()) {
        continue;
      }
      int separator = definition.indexOf('=');
      String name = separator < 0 ? definition : definition.substring(0, separator);
      List<String> jobs = new ArrayList<>();
      if (separator >= 0) {
        for (String job : definition.substring(separator + 1).split(",", -1)) {
          if (!job.isEmpty()) {
            jobs.add(job);
          }
        }
      }
      paths.put(name, jobs);
    }
    return paths;
  }

  /**
   * The result gate must consume the whole {@code needs} context and must classify every job it
   * needs into exactly one verification path. Without this, a verification job added to {@code
   * needs} later is never examined by the gate, and the gate reports green while that job failed or
   * never ran.
   */
  static List<String> verificationPathViolations(JsonNode workflow, String resultJobName) {
    List<String> violations = new ArrayList<>();
    JsonNode gateStep = WorkflowDocuments.firstRunStep(workflow, resultJobName);
    Map<String, String> environment = WorkflowDocuments.stepEnvironment(gateStep);
    if (!NEEDS_JSON_EXPRESSION.equals(environment.get(NEEDS_JSON_VARIABLE))) {
      violations.add(
          resultJobName + " does not read the whole needs context as " + NEEDS_JSON_VARIABLE);
    }
    if (environment.getOrDefault(VERIFICATION_PATHS_VARIABLE, "").isBlank()) {
      violations.add(resultJobName + " declares no " + VERIFICATION_PATHS_VARIABLE);
      return violations;
    }

    List<String> needs = WorkflowDocuments.jobNeeds(WorkflowDocuments.job(workflow, resultJobName));
    Map<String, List<String>> paths = verificationPaths(workflow, resultJobName);
    Map<String, String> owner = new LinkedHashMap<>();
    for (Map.Entry<String, List<String>> path : paths.entrySet()) {
      if (path.getValue().isEmpty()) {
        violations.add("path " + path.getKey() + " declares no job");
        continue;
      }
      for (String job : path.getValue()) {
        if (!needs.contains(job)) {
          violations.add(
              "path "
                  + path.getKey()
                  + " declares "
                  + job
                  + ", which "
                  + resultJobName
                  + " does not need");
        } else if (owner.put(job, path.getKey()) != null) {
          violations.add(job + " is declared by more than one verification path");
        }
      }
    }
    for (String job : needs) {
      if (!owner.containsKey(job)) {
        violations.add(job + " belongs to no declared verification path");
      }
    }
    return violations;
  }

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
      if (!isLocalOrPinned(reference)) {
        violations.add(reference + UNPINNED_MESSAGE);
      }
    }
    return violations;
  }

  private static boolean isLocalOrPinned(String reference) {
    return WorkflowDocuments.isLocalReference(reference)
        || PINNED_EXTERNAL_REFERENCE.matcher(reference).matches();
  }

  /**
   * Walks every action a workflow can reach, through local composite actions, and applies the same
   * rules at every depth. {@link #actionPinningViolations(JsonNode)} accepts any {@code ./}
   * reference on sight, so without this walk a local composite action is an unreviewed hole: its
   * own steps may pull {@code attacker/action@main}, and a {@code ./} reference that resolves to
   * nothing is a step whose behaviour no rule in this repository has ever read.
   */
  static List<String> actionGraphViolations(
      JsonNode workflow, WorkflowDocuments.LocalActions actions) throws IOException {
    return actionGraphViolations(
        WorkflowDocuments.stepActionReferences(workflow), WORKFLOW_ORIGIN, actions);
  }

  /**
   * Applies the same walk to a local composite action definition found by scanning {@code
   * .github/actions}, so a definition that no workflow references yet is still held to the pinning
   * and resolution rules before somebody wires it up.
   */
  static List<String> compositeActionViolations(
      String reference, JsonNode action, WorkflowDocuments.LocalActions actions)
      throws IOException {
    List<String> violations = new ArrayList<>();
    String form = compositeFormViolation(reference, action);
    if (form != null) {
      violations.add(form);
    }
    violations.addAll(
        actionGraphViolations(
            WorkflowDocuments.actionStepReferences(action), reference, actions, Set.of(reference)));
    return violations;
  }

  /**
   * A local action must be a composite action. Any other {@code runs.using} — {@code docker} above
   * all, which pulls an image this repository never reviewed, but equally a Node action or a form
   * this policy does not know — declares no {@code steps}, so the walk would resolve it, find no
   * edge, and report it clean. Rejecting the form is what keeps that fail-closed.
   */
  private static String compositeFormViolation(String subject, JsonNode action) {
    if (WorkflowDocuments.isCompositeAction(action)) {
      return null;
    }
    String using = WorkflowDocuments.actionUsing(action);
    return subject
        + " declares runs.using "
        + (using.isEmpty() ? "nothing" : "'" + using + "'")
        + COMPOSITE_MESSAGE;
  }

  private static List<String> actionGraphViolations(
      List<String> roots, String origin, WorkflowDocuments.LocalActions actions)
      throws IOException {
    return actionGraphViolations(roots, origin, actions, Set.of());
  }

  private static List<String> actionGraphViolations(
      List<String> roots,
      String origin,
      WorkflowDocuments.LocalActions actions,
      Set<String> alreadyVisited)
      throws IOException {
    List<String> violations = new ArrayList<>();
    Set<String> visited = new LinkedHashSet<>(alreadyVisited);
    Deque<ActionEdge> pending = new ArrayDeque<>();
    for (String root : roots) {
      pending.addLast(new ActionEdge(origin, root));
    }
    while (!pending.isEmpty()) {
      ActionEdge edge = pending.removeFirst();
      String reference = edge.reference();
      if (!visited.add(reference)) {
        continue;
      }
      if (!WorkflowDocuments.isLocalReference(reference)) {
        if (!PINNED_EXTERNAL_REFERENCE.matcher(reference).matches()) {
          violations.add(edge.describe() + UNPINNED_MESSAGE);
        }
        continue;
      }
      if (!WorkflowDocuments.staysWithinRepository(reference)) {
        violations.add(edge.describe() + ESCAPING_MESSAGE);
        continue;
      }
      JsonNode definition = actions.read(reference);
      if (definition == null || definition.isMissingNode() || definition.isNull()) {
        violations.add(edge.describe() + UNRESOLVED_MESSAGE);
        continue;
      }
      String form = compositeFormViolation(edge.describe(), definition);
      if (form != null) {
        violations.add(form);
        continue;
      }
      for (String nested : WorkflowDocuments.actionStepReferences(definition)) {
        pending.addLast(new ActionEdge(reference, nested));
      }
    }
    return violations;
  }

  /** One {@code uses} edge: the action, and the workflow or action that declares it. */
  private record ActionEdge(String source, String reference) {

    String describe() {
      return reference + " used by " + source;
    }
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
   * Reports whether a workflow can be started by a pull request, which is what makes its result
   * gate part of the merge decision and therefore subject to every gate rule.
   */
  static boolean isPullRequestWorkflow(JsonNode workflow) {
    return WorkflowDocuments.triggerNames(workflow).contains("pull_request");
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
