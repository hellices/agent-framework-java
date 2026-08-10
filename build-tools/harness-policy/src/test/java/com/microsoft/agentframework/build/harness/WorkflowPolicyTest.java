package com.microsoft.agentframework.build.harness;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class WorkflowPolicyTest {
  private static final Pattern PINNED_ACTION =
      Pattern.compile("\\s*-\\s+uses:\\s+[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+@[0-9a-f]{40}(?:\\s+#.*)?");

  @Test
  void ciUsesTrustedArcRunnerAndPinnedActions() throws IOException {
    Path workflow = RepositoryPaths.root().resolve(".github/workflows/ci.yml");
    List<String> lines = Files.readAllLines(workflow);
    String text = String.join("\n", lines);
    List<String> actionLines =
        lines.stream().filter(line -> line.trim().startsWith("- uses:")).toList();

    assertThat(text).contains("runs-on: aks-runners");
    assertThat(text).contains("contents: read");
    assertThat(text).contains("persist-credentials: false");
    assertThat(text).contains("java: [17, 21, 25]");
    assertThat(text).contains("github.event.pull_request.head.repo.full_name == github.repository");
    assertThat(text).doesNotContain("pull_request_target");
    assertThat(text).doesNotContain("write-all");
    assertThat(actionLines).isNotEmpty();
    assertThat(actionLines).allMatch(line -> PINNED_ACTION.matcher(line).matches());
  }

  @Test
  void dependabotTracksMavenAndActions() throws IOException {
    String text = Files.readString(RepositoryPaths.root().resolve(".github/dependabot.yml"));

    assertThat(text).contains("package-ecosystem: \"maven\"");
    assertThat(text).contains("package-ecosystem: \"github-actions\"");
  }
}
