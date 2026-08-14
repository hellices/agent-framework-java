package io.github.hellices.agentframework.build.harness;

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
import java.util.regex.Pattern;
import java.util.stream.Stream;

final class WorkflowDocuments {

  private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

  private static final JsonNode MISSING = MissingNode.getInstance();

  private static final String GROUP_SELECTOR_PREFIX = "group:";

  private static final Set<String> RUNS_ON_OBJECT_KEYS = Set.of("group", "labels");

  /** The prefix that marks an action reference as local to this repository. */
  static final String LOCAL_REFERENCE_PREFIX = "./";

  /** The only {@code runs.using} form a local action may declare. */
  static final String COMPOSITE_USING = "composite";

  private static final String LOCAL_ACTIONS_DIRECTORY = ".github/actions";

  /** The file names GitHub accepts for an action definition, in the order it resolves them. */
  private static final List<String> ACTION_DEFINITION_NAMES = List.of("action.yml", "action.yaml");

  private static final Pattern PATH_SEPARATOR = Pattern.compile("[/\\\\]");

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

  /**
   * Returns every local composite action definition under {@code .github/actions}, at any depth.
   * The walk is recursive because an action may be nested in subdirectories, and an unscanned
   * definition is an unreviewed {@code uses} that no workflow rule would ever read.
   */
  static List<Path> actionFiles() throws IOException {
    return actionFiles(RepositoryPaths.root());
  }

  /** The same scan against an arbitrary root, so the discovery itself can be regression tested. */
  static List<Path> actionFiles(Path root) throws IOException {
    Path directory = root.resolve(LOCAL_ACTIONS_DIRECTORY);
    if (!Files.isDirectory(directory)) {
      return List.of();
    }
    try (Stream<Path> entries = Files.walk(directory)) {
      return entries
          .filter(Files::isRegularFile)
          .filter(path -> ACTION_DEFINITION_NAMES.contains(fileNameOf(path)))
          .sorted(Comparator.comparing(Path::toString))
          .toList();
    }
  }

  /** Renders an action definition file as the {@code ./} reference a workflow step would use. */
  static String actionReference(Path definition) {
    return actionReference(RepositoryPaths.root(), definition);
  }

  static String actionReference(Path root, Path definition) {
    Path directory = definition.getParent();
    if (directory == null) {
      return LOCAL_REFERENCE_PREFIX;
    }
    return LOCAL_REFERENCE_PREFIX
        + root.normalize().relativize(directory.normalize()).toString().replace('\\', '/');
  }

  static boolean isLocalReference(String reference) {
    return reference.startsWith(LOCAL_REFERENCE_PREFIX);
  }

  /**
   * Reports whether a {@code ./} reference names a path inside the repository once {@code .} and
   * {@code ..} segments are resolved. The check is textual so that it holds for synthetic probe
   * documents as well as for the working tree, and it rejects the repository root itself, which is
   * not an action.
   */
  static boolean staysWithinRepository(String reference) {
    int depth = 0;
    for (String segment : PATH_SEPARATOR.split(reference)) {
      if (segment.isEmpty() || ".".equals(segment)) {
        continue;
      }
      if ("..".equals(segment)) {
        depth--;
        if (depth < 0) {
          return false;
        }
      } else {
        depth++;
      }
    }
    return depth > 0;
  }

  /**
   * Resolves a {@code ./} local action reference to its parsed definition. The repository
   * implementation reads the working tree; bypass probes supply synthetic definitions so the same
   * rules are exercised against documents that are not committed.
   */
  @FunctionalInterface
  interface LocalActions {

    /** Returns the parsed definition, or a missing node when the reference resolves to none. */
    JsonNode read(String reference) throws IOException;
  }

  /** Resolves local action references against the checked-out repository. */
  static LocalActions repositoryActions() {
    return localActions(RepositoryPaths.root());
  }

  /** The same resolver against an arbitrary root, so its containment check can be probed. */
  static LocalActions localActions(Path root) {
    Path base = root.normalize();
    return reference -> {
      if (!isLocalReference(reference)) {
        return MISSING;
      }
      Path directory =
          base.resolve(reference.substring(LOCAL_REFERENCE_PREFIX.length())).normalize();
      if (!directory.startsWith(base) || directory.equals(base)) {
        return MISSING;
      }
      for (String name : ACTION_DEFINITION_NAMES) {
        Path definition = directory.resolve(name);
        if (Files.isRegularFile(definition)) {
          return read(definition);
        }
      }
      return MISSING;
    };
  }

  /**
   * Returns the {@code runs.using} an action declares, or an empty string when it declares none, so
   * an action form this policy cannot walk is rejected instead of resolved to zero references.
   */
  static String actionUsing(JsonNode action) {
    JsonNode using = action.path("runs").path("using");
    return using.isTextual() ? using.textValue() : "";
  }

  static boolean isCompositeAction(JsonNode action) {
    return COMPOSITE_USING.equals(actionUsing(action));
  }

  /**
   * Returns every {@code uses} declared by the composite steps of an action definition, so the
   * pinning rules reach the actions an action itself runs.
   */
  static List<String> actionStepReferences(JsonNode action) {
    List<String> references = new ArrayList<>();
    for (JsonNode step : action.path("runs").path("steps")) {
      JsonNode uses = step.path("uses");
      if (uses.isTextual()) {
        references.add(uses.textValue());
      }
    }
    return references;
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
