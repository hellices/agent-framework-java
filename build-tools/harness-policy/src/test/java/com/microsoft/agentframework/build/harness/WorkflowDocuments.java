package com.microsoft.agentframework.build.harness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

final class WorkflowDocuments {

  private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

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

  static List<JsonNode> steps(JsonNode job) {
    List<JsonNode> steps = new ArrayList<>();
    job.path("steps").forEach(steps::add);
    return steps;
  }

  static List<String> runnerLabels(JsonNode job) {
    JsonNode runsOn = job.path("runs-on");
    List<String> labels = new ArrayList<>();
    if (runsOn.isTextual()) {
      labels.add(runsOn.textValue());
    } else if (runsOn.isArray()) {
      runsOn.forEach(label -> labels.add(label.textValue()));
    }
    return labels;
  }

  static List<String> actionReferences(JsonNode workflow) {
    List<String> references = new ArrayList<>();
    for (JsonNode job : jobs(workflow)) {
      JsonNode jobUses = job.path("uses");
      if (jobUses.isTextual()) {
        references.add(jobUses.textValue());
      }
      for (JsonNode step : steps(job)) {
        JsonNode stepUses = step.path("uses");
        if (stepUses.isTextual()) {
          references.add(stepUses.textValue());
        }
      }
    }
    return references;
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

  private static boolean isYamlFile(Path path) {
    String name = fileNameOf(path);
    return name.endsWith(".yml") || name.endsWith(".yaml");
  }

  private static String fileNameOf(Path path) {
    Path name = path.getFileName();
    return name == null ? "" : name.toString();
  }
}
