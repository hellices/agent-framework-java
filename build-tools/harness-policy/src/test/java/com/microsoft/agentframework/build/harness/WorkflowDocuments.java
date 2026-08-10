package com.microsoft.agentframework.build.harness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

final class WorkflowDocuments {

  private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

  private static final JsonNode MISSING = MissingNode.getInstance();

  private static final String GROUP_SELECTOR_PREFIX = "group:";

  private static final Set<String> RUNS_ON_OBJECT_KEYS = Set.of("group", "labels");

  private WorkflowDocuments() {}

  static List<Path> files() throws IOException {
    Path directory = RepositoryPaths.root().resolve(".github/workflows");
    try (Stream<Path> entries = Files.list(directory)) {
      return entries
          .filter(Files::isRegularFile)
          .filter(WorkflowDocuments::isYamlFile)
          .sorted(Comparator.comparing(WorkflowDocuments::fileNameOf))
          .toList();
    }
  }

  static JsonNode read(Path workflow) throws IOException {
    return YAML_MAPPER.readTree(workflow.toFile());
  }

  static JsonNode parse(String yaml) throws IOException {
    return YAML_MAPPER.readTree(yaml);
  }

  static List<String> triggerNames(JsonNode workflow) {
    JsonNode triggers = workflow.has("on") ? workflow.get("on") : workflow.path("true");
    List<String> names = new ArrayList<>();
    if (triggers.isObject()) {
      triggers.fieldNames().forEachRemaining(names::add);
    } else if (triggers.isArray()) {
      triggers.forEach(trigger -> names.add(trigger.textValue()));
    } else if (triggers.isTextual()) {
      names.add(triggers.textValue());
    }
    return names;
  }

  static List<String> jobNames(JsonNode workflow) {
    List<String> names = new ArrayList<>();
    workflow.path("jobs").fieldNames().forEachRemaining(names::add);
    return names;
  }

  static List<JsonNode> jobs(JsonNode workflow) {
    List<JsonNode> jobs = new ArrayList<>();
    workflow.path("jobs").forEach(jobs::add);
    return jobs;
  }

  static JsonNode job(JsonNode workflow, String jobName) {
    return workflow.path("jobs").path(jobName);
  }

  static JsonNode jobUses(JsonNode job) {
    return job.path("uses");
  }

  static List<JsonNode> steps(JsonNode job) {
    List<JsonNode> steps = new ArrayList<>();
    job.path("steps").forEach(steps::add);
    return steps;
  }

  /** Returns the job identifiers a job depends on, in either the string or the sequence form. */
  static List<String> jobNeeds(JsonNode job) {
    JsonNode needs = job.path("needs");
    List<String> names = new ArrayList<>();
    if (needs.isTextual()) {
      names.add(needs.textValue());
    } else if (needs.isArray()) {
      needs.forEach(need -> names.add(need.textValue()));
    }
    return names;
  }

  /** Returns the {@code env} map a step declares, so a policy can read what the script consumes. */
  static Map<String, String> stepEnvironment(JsonNode step) {
    Map<String, String> environment = new LinkedHashMap<>();
    JsonNode declared = step.path("env");
    if (!declared.isObject()) {
      return environment;
    }
    Iterator<String> names = declared.fieldNames();
    while (names.hasNext()) {
      String name = names.next();
      environment.put(name, declared.path(name).asText());
    }
    return environment;
  }

  /**
   * Returns the first {@code run} step of {@code jobName}, or a missing node when there is none.
   */
  static JsonNode firstRunStep(JsonNode workflow, String jobName) {
    for (JsonNode step : steps(job(workflow, jobName))) {
      if (step.path("run").isTextual()) {
        return step;
      }
    }
    return MISSING;
  }

  static boolean declaresRunsOn(JsonNode job) {
    JsonNode runsOn = job.path("runs-on");
    return !runsOn.isMissingNode() && !runsOn.isNull();
  }

  /**
   * Reports whether {@code runs-on} uses a form this policy understands. Any other present form
   * fails closed so that an unparsed selector can never slip past the runner allow list.
   */
  static boolean declaresRecognizedRunsOn(JsonNode job) {
    JsonNode runsOn = job.path("runs-on");
    if (!declaresRunsOn(job)) {
      return false;
    }
    if (runsOn.isTextual()) {
      return true;
    }
    if (runsOn.isArray()) {
      return isTextualArray(runsOn);
    }
    if (runsOn.isObject()) {
      return isRecognizedRunsOnObject(runsOn);
    }
    return false;
  }

  /**
   * Returns every runner selector a job requests: plain labels, the labels of the {@code runs-on}
   * object form, and the runner group rendered as {@code group:<name>} so a group can never be
   * mistaken for an allowed label.
   */
  static List<String> runnerSelectors(JsonNode job) {
    List<String> selectors = new ArrayList<>(runnerLabels(job));
    JsonNode runsOn = job.path("runs-on");
    if (runsOn.isObject() && runsOn.path("group").isTextual()) {
      selectors.add(GROUP_SELECTOR_PREFIX + runsOn.path("group").textValue());
    }
    return selectors;
  }

  static List<String> runnerLabels(JsonNode job) {
    JsonNode runsOn = job.path("runs-on");
    List<String> labels = new ArrayList<>();
    if (!declaresRecognizedRunsOn(job)) {
      return labels;
    }
    if (runsOn.isTextual()) {
      labels.add(runsOn.textValue());
    } else if (runsOn.isArray()) {
      runsOn.forEach(label -> labels.add(label.textValue()));
    } else if (runsOn.isObject()) {
      JsonNode declared = runsOn.path("labels");
      if (declared.isTextual()) {
        labels.add(declared.textValue());
      } else if (declared.isArray()) {
        declared.forEach(label -> labels.add(label.textValue()));
      }
    }
    return labels;
  }

  static List<String> stepActionReferences(JsonNode workflow) {
    List<String> references = new ArrayList<>();
    for (JsonNode job : jobs(workflow)) {
      for (JsonNode step : steps(job)) {
        JsonNode stepUses = step.path("uses");
        if (stepUses.isTextual()) {
          references.add(stepUses.textValue());
        }
      }
    }
    return references;
  }

  /**
   * Returns the shell script of the first {@code run} step in {@code jobName}, so a policy test can
   * execute the real gate script instead of pattern matching its text.
   */
  static String runScript(JsonNode workflow, String jobName) {
    return firstRunStep(workflow, jobName).path("run").asText("");
  }

  static List<String> permissionValues(JsonNode workflow) {
    List<String> values = new ArrayList<>();
    collectPermissionValues(workflow.path("permissions"), values);
    for (JsonNode job : jobs(workflow)) {
      collectPermissionValues(job.path("permissions"), values);
    }
    return values;
  }

  private static void collectPermissionValues(JsonNode permissions, List<String> values) {
    if (permissions.isObject()) {
      permissions.forEach(value -> values.add(value.textValue()));
    } else if (permissions.isTextual()) {
      values.add(permissions.textValue());
    }
  }

  private static boolean isTextualArray(JsonNode array) {
    if (array.isEmpty()) {
      return false;
    }
    for (JsonNode element : array) {
      if (!element.isTextual()) {
        return false;
      }
    }
    return true;
  }

  private static boolean isRecognizedRunsOnObject(JsonNode runsOn) {
    Iterator<String> keys = runsOn.fieldNames();
    while (keys.hasNext()) {
      if (!RUNS_ON_OBJECT_KEYS.contains(keys.next())) {
        return false;
      }
    }
    JsonNode labels = runsOn.path("labels");
    JsonNode group = runsOn.path("group");
    boolean labelsRecognized =
        labels.isMissingNode()
            || labels.isTextual()
            || (labels.isArray() && isTextualArray(labels));
    boolean groupRecognized = group.isMissingNode() || group.isTextual();
    boolean anySelector = !labels.isMissingNode() || !group.isMissingNode();
    return labelsRecognized && groupRecognized && anySelector;
  }

  private static boolean isYamlFile(Path path) {
    String name = fileNameOf(path);
    return name.endsWith(".yml") || name.endsWith(".yaml");
  }

  private static String fileNameOf(Path path) {
    Path name = path.getFileName();
    return name == null ? "" : name.toString();
  }
}
