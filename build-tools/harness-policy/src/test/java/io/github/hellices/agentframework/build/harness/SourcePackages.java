package io.github.hellices.agentframework.build.harness;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SourcePackages {

  record Violation(Path source, String problem) {}

  private record Scan(List<Path> sources, List<Violation> failures) {}

  private static final Pattern PACKAGE =
      Pattern.compile("(?m)^package\\s+([A-Za-z0-9_.]+)\\s*;?\\s*$");

  private static final String FORBIDDEN_NAMESPACE = "com." + "microsoft.agentframework";

  private static final Pattern MICROSOFT_REFERENCE =
      Pattern.compile(
          "\\b" + Pattern.quote(FORBIDDEN_NAMESPACE) + "(?:\\.[A-Za-z_][A-Za-z0-9_]*)*");

  private static final Set<String> EXCLUDED_BEFORE_SOURCE_ROOT =
      Set.of(".git", ".gradle", ".worktrees", "build", "out");

  private SourcePackages() {}

  static List<Path> discover(Path repository) throws IOException {
    return scan(repository).sources();
  }

  static Optional<String> packageName(String source) {
    Matcher matcher = PACKAGE.matcher(withoutCommentsAndLiterals(source));
    return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
  }

  static boolean referencesMicrosoftNamespace(String source) {
    return MICROSOFT_REFERENCE.matcher(withoutCommentsAndLiterals(source)).find();
  }

  static String microsoftReferenceProblem() {
    return "source references " + FORBIDDEN_NAMESPACE + " namespace";
  }

  static List<Violation> violations(Path repository) throws IOException {
    Scan scan = scan(repository);
    List<Violation> violations = new ArrayList<>(scan.failures());
    for (Path source : scan.sources()) {
      String text;
      try {
        text = Files.readString(source, StandardCharsets.UTF_8);
      } catch (IOException cause) {
        violations.add(
            new Violation(source, "source must be readable as UTF-8: " + cause.getMessage()));
        continue;
      }

      boolean packageRequired =
          isJavaOrKotlin(source) && isCanonicalSource(repository.relativize(source));
      Optional<String> packageName = packageName(text);
      if (packageRequired && packageName.isEmpty()) {
        violations.add(new Violation(source, "source must declare a package"));
      } else if (packageRequired
          && !packageName.get().matches("io\\.github\\.hellices\\.agentframework(?:\\..+)?")) {
        violations.add(
            new Violation(source, "package must start with io.github.hellices.agentframework"));
      }
      if (referencesMicrosoftNamespace(text)) {
        violations.add(new Violation(source, microsoftReferenceProblem()));
      }
    }
    return List.copyOf(violations);
  }

  private static Scan scan(Path repository) throws IOException {
    List<Path> sources = new ArrayList<>();
    List<Violation> failures = new ArrayList<>();
    Files.walkFileTree(
        repository,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
            Path relative = repository.relativize(directory);
            return shouldSkipSubtree(relative)
                ? FileVisitResult.SKIP_SUBTREE
                : FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
            if (isJavaOrKotlin(file) || isBuildScript(file)) {
              sources.add(file);
            }
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFileFailed(Path file, IOException cause) {
            failures.add(new Violation(file, "cannot inspect path: " + cause.getMessage()));
            return FileVisitResult.CONTINUE;
          }
        });
    sources.sort(Path::compareTo);
    return new Scan(List.copyOf(sources), List.copyOf(failures));
  }

  private static boolean isJavaOrKotlin(Path path) {
    Path fileName = path.getFileName();
    if (fileName == null) {
      return false;
    }
    String name = fileName.toString();
    return name.endsWith(".java") || name.endsWith(".kt");
  }

  private static boolean isBuildScript(Path path) {
    Path fileName = path.getFileName();
    if (fileName == null) {
      return false;
    }
    String name = fileName.toString();
    return name.endsWith(".gradle.kts") || name.endsWith(".gradle");
  }

  private static boolean shouldSkipSubtree(Path relativeDirectory) {
    if (isUnderCanonicalSourceRoot(relativeDirectory)) {
      return false;
    }
    for (Path part : relativeDirectory) {
      if (EXCLUDED_BEFORE_SOURCE_ROOT.contains(part.toString())) {
        return true;
      }
    }
    return false;
  }

  private static boolean isCanonicalSource(Path relativePath) {
    return isUnderCanonicalSourceRoot(relativePath);
  }

  private static boolean isUnderCanonicalSourceRoot(Path relativePath) {
    for (int index = 0; index + 2 < relativePath.getNameCount(); index++) {
      if (!"src".equals(relativePath.getName(index).toString())) {
        continue;
      }
      String sourceSet = relativePath.getName(index + 1).toString();
      String language = relativePath.getName(index + 2).toString();
      if (Set.of("main", "test").contains(sourceSet)
          && Set.of("java", "kotlin").contains(language)) {
        return true;
      }
    }
    return false;
  }

  private static String withoutCommentsAndLiterals(String source) {
    StringBuilder visible = new StringBuilder(source.length());
    int index = 0;
    while (index < source.length()) {
      char current = source.charAt(index);
      char next = index + 1 < source.length() ? source.charAt(index + 1) : '\0';

      if (current == '/' && next == '/') {
        index = maskUntilLineEnd(source, visible, index + 2);
      } else if (current == '/' && next == '*') {
        index = maskBlockComment(source, visible, index + 2);
      } else if (current == '"' && source.startsWith("\"\"\"", index)) {
        index = maskTextBlock(source, visible, index + 3);
      } else if (current == '"' || current == '\'') {
        index = maskQuotedLiteral(source, visible, index + 1, current);
      } else {
        visible.append(current);
        index++;
      }
    }
    return visible.toString();
  }

  private static int maskUntilLineEnd(String source, StringBuilder visible, int index) {
    visible.append("  ");
    int cursor = index;
    while (cursor < source.length() && source.charAt(cursor) != '\n') {
      visible.append(' ');
      cursor++;
    }
    return cursor;
  }

  private static int maskBlockComment(String source, StringBuilder visible, int index) {
    visible.append("  ");
    int cursor = index;
    while (cursor < source.length()) {
      if (cursor + 1 < source.length()
          && source.charAt(cursor) == '*'
          && source.charAt(cursor + 1) == '/') {
        visible.append("  ");
        return cursor + 2;
      }
      visible.append(source.charAt(cursor) == '\n' ? '\n' : ' ');
      cursor++;
    }
    return cursor;
  }

  private static int maskTextBlock(String source, StringBuilder visible, int index) {
    visible.append("   ");
    int cursor = index;
    while (cursor < source.length()) {
      if (source.startsWith("\"\"\"", cursor)) {
        visible.append("   ");
        return cursor + 3;
      }
      visible.append(source.charAt(cursor) == '\n' ? '\n' : ' ');
      cursor++;
    }
    return cursor;
  }

  private static int maskQuotedLiteral(
      String source, StringBuilder visible, int index, char delimiter) {
    visible.append(' ');
    int cursor = index;
    boolean escaped = false;
    while (cursor < source.length()) {
      char current = source.charAt(cursor);
      visible.append(current == '\n' ? '\n' : ' ');
      cursor++;
      if (escaped) {
        escaped = false;
      } else if (current == '\\') {
        escaped = true;
      } else if (current == delimiter) {
        return cursor;
      }
    }
    return cursor;
  }
}
