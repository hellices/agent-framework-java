package io.github.hellices.agentframework.samples.standalone;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Runs the sample's real {@code main} in a child JVM, because the behaviour under test is what a
 * process prints and what it exits with, and neither is observable from inside the test JVM.
 *
 * <p>The child is launched with the same launcher as the test task, so {@code testJava17}, {@code
 * testJava21}, and {@code testJava25} each prove it on their own runtime. Its classpath comes from
 * the {@code sample.runtime.classpath} system property set in the sample's build script; a Gradle
 * test worker does not carry the sample's runtime classpath in {@code java.class.path}.
 *
 * <p>An exact assertion on standard error is only meaningful if the parent cannot add lines to it.
 * A JVM echoes {@code JAVA_TOOL_OPTIONS}, {@code _JAVA_OPTIONS}, and {@code JDK_JAVA_OPTIONS} to
 * standard error as a "Picked up" line, and a child process inherits all three, so a developer or a
 * CI image that exports one would otherwise turn this suite red with no defect in the sample.
 * {@link #scrubInheritedEnvironment(Map)} removes them, and a pair of parameterised tests pins each
 * one: the first seeds it into the inherited environment and requires the documented sentence to
 * still stand alone, the second injects the same value past the scrub and requires the echo to
 * appear, so the first cannot pass merely because the probe went inert.
 *
 * <p>Nothing here needs a credential and nothing reaches the network. The missing-key runs exit
 * before an SDK client exists, and the run that does build one is pointed at a base URL whose
 * scheme the HTTP client rejects before it opens a socket.
 */
class StandaloneAgentApplicationLaunchTest {

  /**
   * Bounds a whole sample child: its startup, its work, and its output. Generous, because it is a
   * hang bound rather than a performance assertion; the runs it covers finish in under a second.
   */
  private static final Duration SAMPLE_RUN_TIMEOUT = Duration.ofSeconds(120);

  /** Bounds the child that is meant to hang, so the timeout path itself is proven quickly. */
  private static final Duration HANGING_RUN_TIMEOUT = Duration.ofSeconds(2);

  /**
   * How long a destroyed child gets to die, and a reader gets to reach end of stream, before the
   * failure says so instead of waiting on.
   */
  private static final Duration CLEANUP_TIMEOUT = Duration.ofSeconds(10);

  /**
   * Larger than the 64 KiB pipe buffer of every platform this builds on, so a child that writes it
   * to one stream blocks until that stream is drained.
   */
  private static final int NOISE_CHARS = 1 << 20;

  /**
   * A harmless option: it defines a property nothing reads. Its only job is to make a JVM print the
   * "Picked up" line that would break an exact assertion on standard error.
   */
  private static final String LAUNCHER_OPTION_PROBE = "-Dagentframework.launch.probe=1";

  /**
   * A base URL the HTTP client rejects on sight, so the run fails inside the call with no socket,
   * no DNS lookup, and no retry.
   *
   * <p>A closed loopback port would be a truer transport failure, but nothing this test controls
   * bounds its cost: the adapter's per-attempt timeout is 60 seconds and the SDK client retries
   * twice by default, so an environment that drops packets to a closed port rather than refusing
   * them turns this into a three-minute run and a different exception type. An unsupported scheme
   * fails at the same place in the same async chain — inside {@code agent.run(...)}, after the
   * client was built — which is what this test is about.
   */
  private static final String REJECTED_SCHEME_BASE_URL = "ftp://127.0.0.1:1/v1";

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

  /**
   * The variable names are written out here rather than read from a list shared with {@link
   * #scrubInheritedEnvironment(Map)} on purpose: a shared list would delete the case that proves a
   * scrub at the moment the scrub itself was deleted, and the suite would stay green through the
   * regression.
   */
  @ParameterizedTest
  @ValueSource(strings = {"JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS"})
  void keepsStandardErrorToTheDocumentedLineWhenTheParentExportedLauncherOptions(String variable)
      throws Exception {
    Execution execution =
        runSample(inherited -> inherited.put(variable, LAUNCHER_OPTION_PROBE), environment -> {});

    assertThat(execution.standardError().lines())
        .describedAs("%s reached the child, so the scrub for it is missing", variable)
        .containsExactly(StandaloneAgentApplication.MISSING_API_KEY_MESSAGE);
    assertThat(execution.standardOutput()).isEmpty();
    assertThat(execution.exitCode()).isNotZero();
  }

  @ParameterizedTest
  @ValueSource(strings = {"JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS"})
  void showsThatEachLauncherOptionWouldBeEchoedWithoutTheScrub(String variable) throws Exception {
    // Set past the scrub, so this measures the runtime rather than the helper: it is the evidence
    // that the paired test passes because the variable was removed, not because this JVM ignores
    // it.
    Execution execution =
        runSample(environment -> environment.put(variable, LAUNCHER_OPTION_PROBE));

    assertThat(execution.standardError())
        .describedAs(
            "this runtime no longer echoes %s, so it can no longer prove the scrub", variable)
        .contains("Picked up " + variable + ": " + LAUNCHER_OPTION_PROBE)
        .contains(StandaloneAgentApplication.MISSING_API_KEY_MESSAGE);
    assertThat(execution.exitCode()).isNotZero();
  }

  @Test
  void keepsTheDiagnosticWhenTheKeyIsPresentAndTheRunFails() throws Exception {
    Execution execution =
        runSample(
            environment -> {
              environment.put("OPENAI_API_KEY", "not-a-real-key");
              environment.put("OPENAI_BASE_URL", REJECTED_SCHEME_BASE_URL);
            });

    // Only the missing-key branch is formatted. A failure raised once the run is under way keeps
    // its type and its stack trace, which is the whole diagnostic for a run that really did try to
    // call out.
    assertThat(execution.standardError())
        .doesNotContain(StandaloneAgentApplication.MISSING_API_KEY_MESSAGE)
        .contains("Exception in thread \"main\"")
        .contains("java.util.concurrent.CompletionException")
        .contains("IllegalArgumentException")
        .contains("\n\tat ");
    assertThat(execution.standardOutput()).isEmpty();
    assertThat(execution.exitCode()).isNotZero();
  }

  @Test
  void drainsBothStreamsOfAChildThatOverflowsAPipeBuffer() throws Exception {
    // NoisyChild fills standard error before it writes a byte to standard output, so a helper that
    // read one stream to its end before starting the other would block here until the bound
    // expired.
    Execution execution =
        run(
            helperCommand(NoisyChild.class),
            SAMPLE_RUN_TIMEOUT,
            inherited -> {},
            environment -> {});

    assertThat(execution.standardError()).hasSize(NOISE_CHARS).doesNotContain("o");
    assertThat(execution.standardOutput()).hasSize(NOISE_CHARS).doesNotContain("e");
    assertThat(execution.exitCode()).isZero();
  }

  @Test
  void destroysAndReportsAChildThatNeverFinishes() {
    // ParkingChild never exits on its own, so this is the timeout path: the bound has to end the
    // process rather than merely stop waiting for it, and it has to say what happened.
    assertThatThrownBy(
            () ->
                run(
                    helperCommand(ParkingChild.class),
                    HANGING_RUN_TIMEOUT,
                    inherited -> {},
                    environment -> {}))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("did not finish within PT2S")
        .hasMessageContaining("terminated=true")
        .hasMessageContaining("readers finished=true");
  }

  private static Execution runSample(Consumer<Map<String, String>> environmentCustomiser)
      throws IOException, InterruptedException {
    return runSample(inherited -> {}, environmentCustomiser);
  }

  /**
   * Runs the sample in a child JVM.
   *
   * @param inheritedEnvironmentCustomiser applied before the scrub, so a test can stage what a
   *     developer or a CI image may have exported
   * @param environmentCustomiser applied after the scrub, so a test can set what the child must see
   */
  private static Execution runSample(
      Consumer<Map<String, String>> inheritedEnvironmentCustomiser,
      Consumer<Map<String, String>> environmentCustomiser)
      throws IOException, InterruptedException {
    String classpath = System.getProperty("sample.runtime.classpath");
    assertThat(classpath)
        .describedAs(
            "sample.runtime.classpath is set by samples/sample-standalone/build.gradle.kts")
        .isNotBlank();

    List<String> command =
        List.of(javaBinary(), "-cp", classpath, StandaloneAgentApplication.class.getName());
    return run(command, SAMPLE_RUN_TIMEOUT, inheritedEnvironmentCustomiser, environmentCustomiser);
  }

  /**
   * Starts a child, drains both of its streams concurrently, and bounds the whole of it.
   *
   * <p>Each stream is read by its own task from the moment the process starts, because a child that
   * fills the pipe buffer of a stream nobody is reading blocks forever, and a parent that read one
   * stream to its end before starting on the other would block with it. The bound covers the
   * process rather than the wait: when it expires the child is destroyed forcibly, its death and
   * its readers' end of stream are each awaited for a bounded time, and the failure reports both.
   * Readers run on daemon threads, so a reader that somehow outlived its stream still could not
   * hold the test JVM open.
   */
  private static Execution run(
      List<String> command,
      Duration runTimeout,
      Consumer<Map<String, String>> inheritedEnvironmentCustomiser,
      Consumer<Map<String, String>> environmentCustomiser)
      throws IOException, InterruptedException {
    ProcessBuilder builder = new ProcessBuilder(new ArrayList<>(command));
    Map<String, String> environment = builder.environment();
    inheritedEnvironmentCustomiser.accept(environment);
    scrubInheritedEnvironment(environment);
    environmentCustomiser.accept(environment);

    Process process = builder.start();
    ExecutorService readers = Executors.newFixedThreadPool(2, daemonReaderThreads());
    Execution execution;
    try {
      Future<String> standardOutput = readers.submit(() -> read(process.getInputStream()));
      Future<String> standardError = readers.submit(() -> read(process.getErrorStream()));
      // Neither child reads input; an open pipe to one that never will is only a handle to leak.
      process.getOutputStream().close();
      if (!process.waitFor(runTimeout.toMillis(), MILLISECONDS)) {
        throw destroyAndDescribe(process, readers, runTimeout);
      }
      execution =
          new Execution(
              process.exitValue(),
              drained(standardOutput, "standard output"),
              drained(standardError, "standard error"));
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly();
      }
      readers.shutdownNow();
    }
    assertThat(readers.awaitTermination(CLEANUP_TIMEOUT.toMillis(), MILLISECONDS))
        .describedAs("the output readers finished after the child exited")
        .isTrue();
    assertThat(process.isAlive()).describedAs("the child process is gone").isFalse();
    return execution;
  }

  /**
   * Removes every variable that would otherwise let the machine running the suite decide the
   * outcome: the sample's own configuration, and the three variables a JVM answers with a "Picked
   * up" line on standard error.
   */
  private static void scrubInheritedEnvironment(Map<String, String> environment) {
    environment.remove("OPENAI_API_KEY");
    environment.remove("OPENAI_BASE_URL");
    environment.remove("OPENAI_MODEL");
    environment.remove("JAVA_TOOL_OPTIONS");
    environment.remove("_JAVA_OPTIONS");
    environment.remove("JDK_JAVA_OPTIONS");
  }

  private static AssertionError destroyAndDescribe(
      Process process, ExecutorService readers, Duration runTimeout) throws InterruptedException {
    process.destroyForcibly();
    boolean terminated = process.waitFor(CLEANUP_TIMEOUT.toMillis(), MILLISECONDS);
    readers.shutdownNow();
    boolean readersFinished = readers.awaitTermination(CLEANUP_TIMEOUT.toMillis(), MILLISECONDS);
    return new AssertionError(
        "the child did not finish within "
            + runTimeout
            + "; after destroyForcibly, terminated="
            + terminated
            + " and readers finished="
            + readersFinished
            + " within "
            + CLEANUP_TIMEOUT);
  }

  private static String drained(Future<String> reader, String stream) throws InterruptedException {
    try {
      return reader.get(CLEANUP_TIMEOUT.toMillis(), MILLISECONDS);
    } catch (TimeoutException stillOpen) {
      reader.cancel(true);
      throw new AssertionError(
          "the child's " + stream + " was still open " + CLEANUP_TIMEOUT + " after it exited",
          stillOpen);
    } catch (ExecutionException failed) {
      throw new AssertionError("reading the child's " + stream + " failed", failed.getCause());
    }
  }

  private static List<String> helperCommand(Class<?> helper) {
    return List.of(javaBinary(), "-cp", testClasses(), helper.getName());
  }

  /**
   * The directory this test class was loaded from, which is all a helper child needs: both helpers
   * use only {@code java.base}.
   */
  private static String testClasses() {
    try {
      return Path.of(
              StandaloneAgentApplicationLaunchTest.class
                  .getProtectionDomain()
                  .getCodeSource()
                  .getLocation()
                  .toURI())
          .toString();
    } catch (URISyntaxException notAFile) {
      throw new AssertionError("the test classes are not on the file system", notAFile);
    }
  }

  private static ThreadFactory daemonReaderThreads() {
    AtomicInteger created = new AtomicInteger();
    return runnable -> {
      Thread thread = new Thread(runnable, "launch-test-reader-" + created.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    };
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

  /** Writes more than a pipe buffer to standard error first, then the same to standard output. */
  public static final class NoisyChild {

    private NoisyChild() {}

    public static void main(String[] args) {
      System.err.print("e".repeat(NOISE_CHARS));
      System.err.flush();
      System.out.print("o".repeat(NOISE_CHARS));
      System.out.flush();
    }
  }

  /** Never exits, so only the parent's bound can end it. */
  public static final class ParkingChild {

    private ParkingChild() {}

    public static void main(String[] args) throws InterruptedException {
      Thread.currentThread().join();
    }
  }
}
