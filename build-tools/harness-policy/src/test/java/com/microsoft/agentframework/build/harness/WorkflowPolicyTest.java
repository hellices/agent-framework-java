package com.microsoft.agentframework.build.harness;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class WorkflowPolicyTest {
  // Matches step-level:  "      - uses: owner/repo@<40-hex> # comment"
  private static final Pattern PINNED_STEP_ACTION =
      Pattern.compile("\\s*-\\s+uses:\\s+[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+@[0-9a-f]{40}(?:\\s+#.*)?");

  // Matches job-level reusable workflow:  "    uses: owner/repo/.github/workflows/wf.yml@<40-hex>"
  // The line must NOT start with '-' (distinguishing it from step-level uses).
  private static final Pattern PINNED_JOB_USES =
      Pattern.compile("\\s+uses:\\s+[A-Za-z0-9_./:-]+@[0-9a-f]{40}(?:\\s+#.*)?");

  /**
   * Returns true when a line is a uses: reference (step-level or job-level) that is properly
   * pinned.
   */
  private static boolean isPinnedUsesLine(String line) {
    String trimmed = line.trim();
    if (trimmed.startsWith("- uses:")) {
      return PINNED_STEP_ACTION.matcher(line).matches();
    }
    if (trimmed.startsWith("uses:")) {
      return PINNED_JOB_USES.matcher(line).matches();
    }
    return true; // not a uses: line — not our concern
  }

  /** Collects every line that contains a uses: reference (step-level or job-level). */
  private static List<String> collectUsesLines(List<String> lines) {
    return lines.stream()
        .filter(
            line -> {
              String t = line.trim();
              return t.startsWith("- uses:") || t.startsWith("uses:");
            })
        .toList();
  }

  @Test
  void ciUsesTrustedArcRunnerAndPinnedActions() throws IOException {
    Path workflow = RepositoryPaths.root().resolve(".github/workflows/ci.yml");
    List<String> lines = Files.readAllLines(workflow);
    String text = String.join("\n", lines);
    List<String> actionLines = collectUsesLines(lines);

    assertThat(text).contains("runs-on: aks-runners");
    assertThat(text).contains("contents: read");
    assertThat(text).contains("persist-credentials: false");
    assertThat(text).contains("java: [17, 21, 25]");
    assertThat(text).contains("github.event.pull_request.head.repo.full_name == github.repository");
    assertThat(text).doesNotContain("pull_request_target");
    assertThat(text).doesNotContain("write-all");
    assertThat(actionLines).isNotEmpty();
    assertThat(actionLines).allMatch(WorkflowPolicyTest::isPinnedUsesLine);
  }

  @Test
  void jobLevelUsesPinEnforcement() {
    // Pinned job-level reusable workflow → must be accepted
    String pinnedJobLine =
        "    uses: org/repo/.github/workflows/wf.yml@abcdef1234567890abcdef1234567890abcdef12";
    assertThat(isPinnedUsesLine(pinnedJobLine))
        .as("job-level uses pinned to 40-char SHA should be accepted")
        .isTrue();

    // Unpinned tag reference at job level → must be rejected
    String unpinnedJobLine = "    uses: org/repo/.github/workflows/wf.yml@v1";
    assertThat(isPinnedUsesLine(unpinnedJobLine))
        .as("job-level uses with a tag ref should be rejected")
        .isFalse();

    // Pinned step-level action → still accepted
    String pinnedStepLine =
        "      - uses: actions/checkout@abcdef1234567890abcdef1234567890abcdef12";
    assertThat(isPinnedUsesLine(pinnedStepLine))
        .as("step-level uses pinned to 40-char SHA should be accepted")
        .isTrue();

    // Unpinned tag at step level → still rejected
    String unpinnedStepLine = "      - uses: actions/checkout@v4";
    assertThat(isPinnedUsesLine(unpinnedStepLine))
        .as("step-level uses with a tag ref should be rejected")
        .isFalse();
  }

  @Test
  void dependabotTracksMavenAndActions() throws IOException {
    String text = Files.readString(RepositoryPaths.root().resolve(".github/dependabot.yml"));

    assertThat(text).contains("package-ecosystem: \"maven\"");
    assertThat(text).contains("package-ecosystem: \"github-actions\"");
  }
}
