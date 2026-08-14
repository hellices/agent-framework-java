package io.github.hellices.agentframework.build.harness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

final class SourcePackages {

  record Violation(Path source, String problem) {}

  private static final Pattern PACKAGE =
      Pattern.compile("(?m)^package\\s+([A-Za-z0-9_.]+)\\s*;?\\s*$");

  private static final Pattern MICROSOFT_REFERENCE =
      Pattern.compile("\\bcom\\.microsoft(?:\\.[A-Za-z_][A-Za-z0-9_]*)+");

  private static final Set<String> EXCLUDED_BEFORE_SOURCE_ROOT =
      Set.of(".git", ".gradle", ".worktrees", "build", "out");

  private SourcePackages() {}

  static List<Path> discover(Path repository) throws IOException {
    try (Stream<Path> files = Files.walk(repository)) {
      return files
          .filter(Files::isRegularFile)
          .filter(SourcePackages::isJavaOrKotlin)
          .filter(path -> isCanonicalSource(repository.relativize(path)))
          .sorted()
          .toList();
    }
  }

  static Optional<String> packageName(String source) {
    Matcher matcher = PACKAGE.matcher(source);
    return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
  }

  static boolean referencesMicrosoftNamespace(String source) {
    return MICROSOFT_REFERENCE.matcher(source).find();
  }

  static List<Violation> violations(Path repository) throws IOException {
    List<Violation> violations = new ArrayList<>();
    for (Path source : discover(repository)) {
      String text = Files.readString(source);
      Optional<String> packageName = packageName(text);
      if (packageName.isEmpty()) {
        violations.add(new Violation(source, "source must declare a package"));
      } else if (!packageName.get().matches("io\\.github\\.hellices\\.agentframework(?:\\..+)?")) {
        violations.add(
            new Violation(source, "package must start with io.github.hellices.agentframework"));
      }
      if (referencesMicrosoftNamespace(text)) {
        violations.add(new Violation(source, "source references com.microsoft namespace"));
      }
    }
    return List.copyOf(violations);
  }

  private static boolean isJavaOrKotlin(Path path) {
    Path fileName = path.getFileName();
    if (fileName == null) {
      return false;
    }
    String name = fileName.toString();
    return name.endsWith(".java") || name.endsWith(".kt");
  }

  private static boolean isCanonicalSource(Path relativePath) {
    for (int index = 0; index + 3 < relativePath.getNameCount(); index++) {
      if (!"src".equals(relativePath.getName(index).toString())) {
        continue;
      }
      String sourceSet = relativePath.getName(index + 1).toString();
      String language = relativePath.getName(index + 2).toString();
      if (!Set.of("main", "test").contains(sourceSet)
          || !Set.of("java", "kotlin").contains(language)) {
        continue;
      }
      for (int parent = 0; parent < index; parent++) {
        if (EXCLUDED_BEFORE_SOURCE_ROOT.contains(relativePath.getName(parent).toString())) {
          return false;
        }
      }
      return true;
    }
    return false;
  }
}
