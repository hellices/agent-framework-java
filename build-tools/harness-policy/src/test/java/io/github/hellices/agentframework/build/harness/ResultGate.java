package io.github.hellices.agentframework.build.harness;

import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.provider.Arguments;

/**
 * The executable view of one workflow's {@code verify-result} job: its real shell script, its
 * declared verification paths, and the truth table those paths imply.
 *
 * <p>Nothing here is specific to {@code ci.yml}. The gate script is read from whichever workflow is
 * handed in and the truth table is derived from that workflow's own {@code needs} list and {@code
 * VERIFICATION_PATHS} map, so a second pull-request workflow is held to exactly the same standard
 * as the first instead of being trusted because only one workflow was ever executed.
 */
final class ResultGate {

  private static final String POSIX_SHELL = "sh";

  /** The results that must never let a verification path be reported as completed. */
  private static final List<String> BROKEN_RESULTS = List.of("failure", "cancelled", "skipped");

  /** Every result an unclassified job could report; none of them may reach a green gate. */
  private static final List<String> ANY_RESULT =
      List.of("success", "skipped", "failure", "cancelled");

  private static final String UNCLASSIFIED_JOB = "added-verify";

  private final String label;

  private final String script;

  private final String verificationPaths;

  private final Map<String, List<String>> paths = new LinkedHashMap<>();

  private final List<String> needs = new ArrayList<>();

  private ResultGate(String label, JsonNode workflow, String resultJobName) {
    this.label = label;
    this.script = WorkflowDocuments.runScript(workflow, resultJobName);
    this.verificationPaths =
        WorkflowDocuments.stepEnvironment(WorkflowDocuments.firstRunStep(workflow, resultJobName))
            .getOrDefault(WorkflowPolicy.VERIFICATION_PATHS_VARIABLE, "");
    this.paths.putAll(WorkflowPolicy.verificationPaths(workflow, resultJobName));
    this.needs.addAll(WorkflowDocuments.jobNeeds(WorkflowDocuments.job(workflow, resultJobName)));
  }

  static ResultGate of(String label, JsonNode workflow) {
    return new ResultGate(label, workflow, WorkflowPolicy.RESULT_JOB);
  }

  /** Replaces the gate script while keeping the wiring, so a tampered gate can be probed. */
  static ResultGate withScript(ResultGate original, String replacementScript) {
    return new ResultGate(original, replacementScript);
  }

  private ResultGate(ResultGate original, String replacementScript) {
    this.label = original.label;
    this.script = replacementScript;
    this.verificationPaths = original.verificationPaths;
    this.paths.putAll(original.paths);
    this.needs.addAll(original.needs);
  }

  @Override
  public String toString() {
    return label;
  }

  Set<String> pathNames() {
    return new LinkedHashSet<>(paths.keySet());
  }

  List<String> neededJobs() {
    return List.copyOf(needs);
  }

  static boolean isPosixShellAvailable() {
    return !System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
  }

  /**
   * Derives every needs context this gate must judge and the exit code it must produce, from the
   * workflow's own verification paths. Exactly one complete path exits zero; anything else — a
   * broken job, a half-skipped path, two complete paths, an unclassified job, a job that vanished
   * from {@code needs}, or an empty context — must exit non-zero.
   */
  List<Arguments> truthTable() {
    List<Arguments> table = new ArrayList<>();
    for (String path : paths.keySet()) {
      table.add(scenario("only the " + path + " path completes", completing(path), 0));
    }
    table.add(scenario("no path ran at all", uniform("skipped"), 1));
    table.add(scenario("every job failed", uniform("failure"), 1));
    if (paths.size() > 1) {
      table.add(scenario("every path completed", uniform("success"), 1));
    }
    for (Map.Entry<String, List<String>> path : paths.entrySet()) {
      for (String job : path.getValue()) {
        for (String broken : BROKEN_RESULTS) {
          Map<String, String> results = completing(path.getKey());
          results.put(job, broken);
          table.add(
              scenario("the " + path.getKey() + " path with " + job + " " + broken, results, 1));
        }
      }
    }
    String firstPath = paths.isEmpty() ? "" : paths.keySet().iterator().next();
    for (String added : ANY_RESULT) {
      Map<String, String> results = completing(firstPath);
      results.put(UNCLASSIFIED_JOB, added);
      table.add(scenario("an unclassified job reporting " + added, results, 1));
    }
    for (String job : needs) {
      Map<String, String> results = completing(firstPath);
      results.remove(job);
      table.add(scenario(job + " disappeared from needs", results, 1));
    }
    table.add(scenario("an empty needs context", new LinkedHashMap<>(), 1));
    table.add(scenario("an unset needs context", null, 1));
    return table;
  }

  private Arguments scenario(String name, Map<String, String> results, int expectedExitCode) {
    return arguments(label, name, this, results, expectedExitCode);
  }

  /** Every needed job skipped, except the jobs of {@code pathName}, which all succeeded. */
  private Map<String, String> completing(String pathName) {
    Map<String, String> results = uniform("skipped");
    for (String job : paths.getOrDefault(pathName, List.of())) {
      results.put(job, "success");
    }
    return results;
  }

  private Map<String, String> uniform(String result) {
    Map<String, String> results = new LinkedHashMap<>();
    for (String job : needs) {
      results.put(job, result);
    }
    return results;
  }

  /** Renders a needs context exactly as {@code toJSON(needs)} does. */
  static String renderNeedsJson(Map<String, String> results) {
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

  /** Executes the real gate script as a shell process against a synthesized needs context. */
  int run(Map<String, String> results) throws IOException, InterruptedException {
    ProcessBuilder builder = new ProcessBuilder(POSIX_SHELL, "-s");
    builder.redirectErrorStream(true);
    Map<String, String> environment = builder.environment();
    environment.put(
        WorkflowPolicy.NEEDS_JSON_VARIABLE, results == null ? "" : renderNeedsJson(results));
    environment.put(WorkflowPolicy.VERIFICATION_PATHS_VARIABLE, verificationPaths);

    Process gate = builder.start();
    try (OutputStream commands = gate.getOutputStream()) {
      commands.write(script.getBytes(StandardCharsets.UTF_8));
    }
    try (InputStream output = gate.getInputStream()) {
      output.readAllBytes();
    }
    if (!gate.waitFor(60, TimeUnit.SECONDS)) {
      gate.destroyForcibly();
      throw new IllegalStateException(label + " result gate did not terminate");
    }
    return gate.exitValue();
  }
}
