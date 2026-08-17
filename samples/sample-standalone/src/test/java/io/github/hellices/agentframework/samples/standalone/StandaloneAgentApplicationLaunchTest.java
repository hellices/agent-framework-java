package io.github.hellices.agentframework.samples.standalone;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Runs the sample's real {@code main} in a child JVM, because the behaviour under test is what a
 * process prints and what it exits with, and neither is observable from inside the test JVM.
 *
 * <p>The child is launched with the same launcher as the test task, so {@code testJava17}, {@code
 * testJava21}, and {@code testJava25} each prove it on their own runtime. Its classpath comes from
 * the {@code sample.runtime.classpath} system property set in the sample's build script; a Gradle
 * test worker does not carry the sample's runtime classpath in {@code java.class.path}.
 *
 * <p>Nothing here needs a credential. The missing-key runs exit before an SDK client exists, and
 * the run that does build one is pointed at a closed loopback port, so no request leaves the
 * machine.
 */
class StandaloneAgentApplicationLaunchTest {

  /** Nothing listens on port 1, so a run that gets as far as the SDK fails locally and fast. */
  private static final String UNREACHABLE_BASE_URL = "http://127.0.0.1:1/v1";

  @Test
  void printsOnlyTheDocumentedMessageAndFailsWhenTheApiKeyIsMissing() throws Exception {
    Execution execution = runSample(environment -> {});

    // Exactly one line, so no stack trace: a missing credential is a configuration mistake, and a
    // JVM trace tells the reader nothing the sentence does not.
    assertThat(execution.standardError().lines())
        .containsExactly(StandaloneAgentApplication.MISSING_API_KEY_MESSAGE);
    assertThat(execution.standardOutput()).isEmpty();
    // The run still fails, so a script or a CI step cannot mistake it for a completed turn.
    assertThat(execution.exitCode()).isNotZero();
  }

  @Test
  void treatsABlankApiKeyAsMissingWhenLaunched() throws Exception {
    Execution execution = runSample(environment -> environment.put("OPENAI_API_KEY", "   "));

    assertThat(execution.standardError().lines())
        .containsExactly(StandaloneAgentApplication.MISSING_API_KEY_MESSAGE);
    assertThat(execution.standardOutput()).isEmpty();
    assertThat(execution.exitCode()).isNotZero();
  }

  @Test
  void keepsTheDiagnosticWhenTheKeyIsPresentAndTheEndpointFails() throws Exception {
    Execution execution =
        runSample(
            environment -> {
              environment.put("OPENAI_API_KEY", "not-a-real-key");
              environment.put("OPENAI_BASE_URL", UNREACHABLE_BASE_URL);
            });

    // Only the missing-key branch is formatted. A transport or provider failure keeps its type and
    // its stack trace, which is the whole diagnostic for a run that really did try to call out.
    assertThat(execution.standardError())
        .doesNotContain(StandaloneAgentApplication.MISSING_API_KEY_MESSAGE)
        .contains("Exception in thread \"main\"")
        .contains("ConnectException");
    assertThat(execution.exitCode()).isNotZero();
  }

  private static Execution runSample(Consumer<Map<String, String>> environmentCustomiser)
      throws IOException, InterruptedException {
    String classpath = System.getProperty("sample.runtime.classpath");
    assertThat(classpath)
        .describedAs(
            "sample.runtime.classpath is set by samples/sample-standalone/build.gradle.kts")
        .isNotBlank();

    List<String> command = new ArrayList<>();
    command.add(javaBinary());
    command.add("-cp");
    command.add(classpath);
    command.add(StandaloneAgentApplication.class.getName());

    ProcessBuilder builder = new ProcessBuilder(command);
    Map<String, String> environment = builder.environment();
    // Whatever the developer exported must not decide the outcome of this test.
    environment.remove("OPENAI_API_KEY");
    environment.remove("OPENAI_BASE_URL");
    environment.remove("OPENAI_MODEL");
    environmentCustomiser.accept(environment);

    Process process = builder.start();
    String standardOutput = read(process.getInputStream());
    String standardError = read(process.getErrorStream());
    assertThat(process.waitFor(2, TimeUnit.MINUTES))
        .describedAs("the sample process finished")
        .isTrue();
    return new Execution(process.exitValue(), standardOutput, standardError);
  }

  private static String javaBinary() {
    return ProcessHandle.current()
        .info()
        .command()
        .orElseGet(() -> Path.of(System.getProperty("java.home"), "bin", "java").toString());
  }

  private static String read(InputStream stream) throws IOException {
    try (InputStream source = stream) {
      return new String(source.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private record Execution(int exitCode, String standardOutput, String standardError) {}
}
