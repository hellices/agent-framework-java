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

  private enum Syntax {
    JAVA,
    KOTLIN,
    GROOVY
  }

  record Violation(Path source, String problem) {}

  record Report(List<Path> sources, List<Violation> violations) {}

  private record Scan(List<Path> sources, List<Violation> failures) {}

  private static final Pattern PACKAGE =
      Pattern.compile("(?m)^package\\s+([A-Za-z0-9_.]+)\\s*;?\\s*$");

  private static final String FORBIDDEN_NAMESPACE = "com." + "microsoft.agentframework";

  private static final Pattern MICROSOFT_REFERENCE =
      Pattern.compile(
          "\\b"
              + Pattern.quote(FORBIDDEN_NAMESPACE)
              + "(?![A-Za-z0-9_])"
              + "(?:\\.[A-Za-z_][A-Za-z0-9_]*)*");

  private static final String FORBIDDEN_NAMESPACE_PATH = "com/" + "microsoft/agentframework";

  private static final Pattern MICROSOFT_PATH_REFERENCE =
      Pattern.compile(
          "(?:^|[^A-Za-z0-9_.-])"
              + Pattern.quote(FORBIDDEN_NAMESPACE_PATH)
              + "(?:/[A-Za-z0-9_.-]+)*"
              + "(?=$|/|[^A-Za-z0-9_.-])");

  private static final Set<String> EXCLUDED_BEFORE_SOURCE_ROOT =
      Set.of(
          ".git",
          ".gradle",
          ".idea",
          ".kotlin",
          ".superpowers",
          ".venv",
          ".vscode",
          ".worktrees",
          "bin",
          "build",
          "node_modules",
          "out");

  private static final String MARKDOWN_MIGRATION_MARKER =
      "<!-- allow-retired-namespace: migration guidance -->";

  private SourcePackages() {}

  static List<Path> discover(Path repository) throws IOException {
    return inspect(repository).sources();
  }

  static Optional<String> packageName(String source) {
    return packageName(source, Syntax.JAVA);
  }

  static boolean referencesMicrosoftNamespace(String source) {
    return referencesMicrosoftNamespace(source, Syntax.JAVA);
  }

  static String microsoftReferenceProblem() {
    return "source references " + FORBIDDEN_NAMESPACE + " namespace";
  }

  static List<Violation> violations(Path repository) throws IOException {
    return inspect(repository).violations();
  }

  static Report inspect(Path repository) throws IOException {
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

      if (isTextAsset(source)) {
        if (!allowsRetiredNamespaceForMigration(source, text)
            && referencesMicrosoftNamespaceRaw(text)) {
          violations.add(new Violation(source, microsoftReferenceProblem()));
        }
        continue;
      }

      boolean packageRequired =
          isJavaOrKotlin(source) && isCanonicalSource(repository.relativize(source));
      Syntax syntax = syntax(source);
      Optional<String> packageName = packageName(text, syntax);
      if (packageRequired && packageName.isEmpty()) {
        violations.add(new Violation(source, "source must declare a package"));
      } else if (packageRequired
          && !packageName.get().matches("io\\.github\\.hellices\\.agentframework(?:\\..+)?")) {
        violations.add(
            new Violation(source, "package must start with io.github.hellices.agentframework"));
      }
      if (referencesMicrosoftNamespace(text, syntax)) {
        violations.add(new Violation(source, microsoftReferenceProblem()));
      }
    }
    return new Report(scan.sources(), List.copyOf(violations));
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
            if (isJavaOrKotlin(file)
                || isKotlinScript(file)
                || isBuildScript(file)
                || isTextAsset(file)) {
              sources.add(file);
            }
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFileFailed(Path file, IOException cause) {
            failures.add(new Violation(file, "cannot inspect path: " + cause.getMessage()));
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path directory, IOException cause) {
            if (cause != null) {
              failures.add(new Violation(directory, "cannot inspect path: " + cause.getMessage()));
            }
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

  private static boolean isKotlinScript(Path path) {
    Path fileName = path.getFileName();
    return fileName != null && fileName.toString().endsWith(".kts");
  }

  private static boolean isBuildScript(Path path) {
    Path fileName = path.getFileName();
    if (fileName == null) {
      return false;
    }
    String name = fileName.toString();
    return name.endsWith(".gradle.kts") || name.endsWith(".gradle");
  }

  private static boolean isTextAsset(Path path) {
    Path fileName = path.getFileName();
    if (fileName == null) {
      return false;
    }
    String name = fileName.toString();
    return name.equals(".gitignore")
        || name.equals(".gitattributes")
        || name.endsWith(".properties")
        || name.endsWith(".yml")
        || name.endsWith(".yaml")
        || name.endsWith(".toml")
        || name.endsWith(".md");
  }

  private static boolean allowsRetiredNamespaceForMigration(Path path, String text) {
    Path fileName = path.getFileName();
    return fileName != null
        && fileName.toString().endsWith(".md")
        && text.contains(MARKDOWN_MIGRATION_MARKER);
  }

  private static Syntax syntax(Path path) {
    Path fileName = path.getFileName();
    if (fileName == null) {
      throw new IllegalArgumentException("Source path must have a file name: " + path);
    }
    String name = fileName.toString();
    if (name.endsWith(".kt") || name.endsWith(".kts")) {
      return Syntax.KOTLIN;
    }
    return name.endsWith(".gradle") ? Syntax.GROOVY : Syntax.JAVA;
  }

  private static boolean shouldSkipSubtree(Path relativeDirectory) {
    if (isUnderCanonicalSourceRoot(relativeDirectory)
        || isPotentialSourceRootPrefix(relativeDirectory)) {
      return false;
    }
    for (Path part : relativeDirectory) {
      if (EXCLUDED_BEFORE_SOURCE_ROOT.contains(part.toString())) {
        return true;
      }
    }
    return false;
  }

  private static boolean isPotentialSourceRootPrefix(Path relativePath) {
    int nameCount = relativePath.getNameCount();
    return nameCount >= 2
        && "src".equals(relativePath.getName(nameCount - 2).toString())
        && !relativePath.getName(nameCount - 1).toString().isEmpty();
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
      if (!sourceSet.isEmpty() && Set.of("java", "kotlin").contains(language)) {
        return true;
      }
    }
    return false;
  }

  private static boolean referencesMicrosoftNamespace(String source, Syntax syntax) {
    String visible = withoutComments(source, syntax);
    return MICROSOFT_REFERENCE.matcher(visible).find()
        || MICROSOFT_PATH_REFERENCE.matcher(visible).find();
  }

  private static boolean referencesMicrosoftNamespaceRaw(String source) {
    return MICROSOFT_REFERENCE.matcher(source).find()
        || MICROSOFT_PATH_REFERENCE.matcher(source).find();
  }

  private static Optional<String> packageName(String source, Syntax syntax) {
    Matcher matcher = PACKAGE.matcher(withoutCommentsAndLiterals(source, syntax));
    return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
  }

  private static String withoutCommentsAndLiterals(String source, Syntax syntax) {
    StringBuilder visible = new StringBuilder(source.length());
    int index = 0;
    while (index < source.length()) {
      char current = source.charAt(index);
      char next = index + 1 < source.length() ? source.charAt(index + 1) : '\0';

      if (current == '/' && next == '/') {
        index = maskUntilLineEnd(source, visible, index + 2);
      } else if (current == '/' && next == '*') {
        index = maskBlockComment(source, visible, index + 2, syntax == Syntax.KOTLIN);
      } else if (isMultilineLiteral(source, index, syntax)) {
        String delimiter = source.substring(index, index + 3);
        index =
            maskMultilineLiteral(
                source,
                visible,
                index + 3,
                delimiter,
                syntax != Syntax.KOTLIN,
                syntax != Syntax.JAVA && "\"\"\"".equals(delimiter),
                syntax);
      } else if (syntax == Syntax.GROOVY && isGroovySlashyLiteral(source, index)) {
        index = maskGroovySlashyLiteral(source, visible, index, source.startsWith("$/", index));
      } else if (current == '"' || current == '\'') {
        index =
            maskQuotedLiteral(source, visible, index + 1, current, syntax != Syntax.JAVA, syntax);
      } else {
        visible.append(current);
        index++;
      }
    }
    return visible.toString();
  }

  private static String withoutComments(String source, Syntax syntax) {
    StringBuilder visible = new StringBuilder(source.length());
    int index = 0;
    while (index < source.length()) {
      char current = source.charAt(index);
      char next = index + 1 < source.length() ? source.charAt(index + 1) : '\0';

      if (syntax == Syntax.GROOVY && isGroovySlashyLiteral(source, index)) {
        index =
            copyGroovySlashyLiteral(source, visible, index, source.startsWith("$/", index), syntax);
      } else if (current == '/' && next == '/') {
        index = maskUntilLineEnd(source, visible, index + 2);
      } else if (current == '/' && next == '*') {
        index = maskBlockComment(source, visible, index + 2, syntax == Syntax.KOTLIN);
      } else if (isMultilineLiteral(source, index, syntax)) {
        String delimiter = source.substring(index, index + 3);
        index =
            copyMultilineLiteralRemovingComments(
                source,
                visible,
                index,
                delimiter,
                syntax != Syntax.KOTLIN,
                syntax != Syntax.JAVA && "\"\"\"".equals(delimiter),
                syntax);
      } else if (current == '"' || current == '\'') {
        index =
            copyQuotedLiteralRemovingComments(
                source, visible, index, current, syntax != Syntax.JAVA, syntax);
      } else {
        visible.append(current);
        index++;
      }
    }
    return visible.toString();
  }

  private static boolean isMultilineLiteral(String source, int index, Syntax syntax) {
    return source.startsWith("\"\"\"", index)
        || (syntax == Syntax.GROOVY && source.startsWith("'''", index));
  }

  private static boolean isGroovySlashyLiteral(String source, int index) {
    if (source.startsWith("$/", index)) {
      return true;
    }
    if (source.charAt(index) != '/'
        || index + 1 >= source.length()
        || source.charAt(index + 1) == '/'
        || source.charAt(index + 1) == '*') {
      return false;
    }
    for (int cursor = index - 1; cursor >= 0; cursor--) {
      char previous = source.charAt(cursor);
      if (!Character.isWhitespace(previous)) {
        if ("=(:,[!&|?{;~+-*%^<>".indexOf(previous) >= 0) {
          return true;
        }
        if (Character.isJavaIdentifierPart(previous)) {
          int wordEnd = cursor + 1;
          while (cursor >= 0 && Character.isJavaIdentifierPart(source.charAt(cursor))) {
            cursor--;
          }
          return Set.of("assert", "case", "return", "throw", "yield")
              .contains(source.substring(cursor + 1, wordEnd));
        }
        return false;
      }
    }
    return true;
  }

  private static int copyMultilineLiteral(
      String source,
      StringBuilder visible,
      int index,
      String delimiter,
      boolean supportsEscapedDelimiter) {
    int cursor = index + 3;
    visible.append(delimiter);
    while (cursor < source.length()) {
      if (source.startsWith(delimiter, cursor)
          && (!supportsEscapedDelimiter || !isEscaped(source, cursor))) {
        visible.append(delimiter);
        return cursor + 3;
      }
      visible.append(source.charAt(cursor));
      cursor++;
    }
    return cursor;
  }

  private static int copyQuotedLiteral(
      String source, StringBuilder visible, int index, char delimiter) {
    int cursor = index + 1;
    boolean escaped = false;
    visible.append(delimiter);
    while (cursor < source.length()) {
      char current = source.charAt(cursor);
      visible.append(current);
      cursor++;
      if (current == '\n') {
        return cursor;
      }
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

  private static int copyMultilineLiteralRemovingComments(
      String source,
      StringBuilder visible,
      int index,
      String delimiter,
      boolean supportsEscapedDelimiter,
      boolean supportsInterpolation,
      Syntax syntax) {
    int cursor = index + 3;
    visible.append(delimiter);
    while (cursor < source.length()) {
      if (source.startsWith(delimiter, cursor)
          && (!supportsEscapedDelimiter || !isEscaped(source, cursor))) {
        visible.append(delimiter);
        return cursor + 3;
      }
      if (supportsInterpolation
          && source.startsWith("${", cursor)
          && (syntax == Syntax.KOTLIN || !isEscaped(source, cursor))) {
        cursor = copyInterpolationWithoutComments(source, visible, cursor, syntax);
        continue;
      }
      visible.append(source.charAt(cursor));
      cursor++;
    }
    return cursor;
  }

  private static int copyQuotedLiteralRemovingComments(
      String source,
      StringBuilder visible,
      int index,
      char delimiter,
      boolean supportsInterpolation,
      Syntax syntax) {
    int cursor = index + 1;
    boolean escaped = false;
    visible.append(delimiter);
    while (cursor < source.length()) {
      char current = source.charAt(cursor);
      if (supportsInterpolation
          && delimiter == '"'
          && !escaped
          && source.startsWith("${", cursor)) {
        cursor = copyInterpolationWithoutComments(source, visible, cursor, syntax);
        continue;
      }
      visible.append(current);
      cursor++;
      if (current == '\n') {
        return cursor;
      }
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

  private static int copyGroovySlashyLiteral(
      String source, StringBuilder visible, int index, boolean dollarSlashy, Syntax syntax) {
    int openingLength = dollarSlashy ? 2 : 1;
    visible.append(source, index, index + openingLength);
    int cursor = index + openingLength;
    while (cursor < source.length()) {
      if (dollarSlashy && source.startsWith("/$", cursor) && !isDollarEscaped(source, cursor)) {
        visible.append("/$");
        return cursor + 2;
      }
      if (!dollarSlashy && source.charAt(cursor) == '/' && !isEscaped(source, cursor)) {
        visible.append('/');
        return cursor + 1;
      }
      if (source.startsWith("${", cursor) && (!dollarSlashy || !isDollarEscaped(source, cursor))) {
        cursor = copyInterpolationWithoutComments(source, visible, cursor, syntax);
        continue;
      }
      visible.append(source.charAt(cursor));
      cursor++;
    }
    return cursor;
  }

  private static int copyInterpolationWithoutComments(
      String source, StringBuilder visible, int index, Syntax syntax) {
    int end = findInterpolationEnd(source, index + 2, syntax);
    int expressionEnd = end < 0 ? source.length() : end;
    visible.append("${");
    visible.append(withoutComments(source.substring(index + 2, expressionEnd), syntax));
    if (end < 0) {
      return source.length();
    }
    visible.append('}');
    return end + 1;
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

  private static int maskBlockComment(
      String source, StringBuilder visible, int index, boolean supportsNesting) {
    visible.append("  ");
    int cursor = index;
    int depth = 1;
    while (cursor < source.length()) {
      if (supportsNesting
          && cursor + 1 < source.length()
          && source.charAt(cursor) == '/'
          && source.charAt(cursor + 1) == '*') {
        visible.append("  ");
        depth++;
        cursor += 2;
        continue;
      }
      if (cursor + 1 < source.length()
          && source.charAt(cursor) == '*'
          && source.charAt(cursor + 1) == '/') {
        visible.append("  ");
        depth--;
        cursor += 2;
        if (depth == 0) {
          return cursor;
        }
        continue;
      }
      visible.append(source.charAt(cursor) == '\n' ? '\n' : ' ');
      cursor++;
    }
    return cursor;
  }

  private static int maskGroovySlashyLiteral(
      String source, StringBuilder visible, int index, boolean dollarSlashy) {
    int openingLength = dollarSlashy ? 2 : 1;
    visible.append(" ".repeat(openingLength));
    int cursor = index + openingLength;
    while (cursor < source.length()) {
      if (dollarSlashy && source.startsWith("/$", cursor) && !isDollarEscaped(source, cursor)) {
        visible.append("  ");
        return cursor + 2;
      }
      if (!dollarSlashy && source.charAt(cursor) == '/' && !isEscaped(source, cursor)) {
        visible.append(' ');
        return cursor + 1;
      }
      if (source.startsWith("${", cursor) && (!dollarSlashy || !isDollarEscaped(source, cursor))) {
        cursor = maskInterpolation(source, visible, cursor, Syntax.GROOVY);
        continue;
      }
      if (isUnbracedGroovyInterpolation(source, cursor)
          && (!dollarSlashy || !isDollarEscaped(source, cursor))) {
        cursor = copyUnbracedGroovyInterpolation(source, visible, cursor);
        continue;
      }
      visible.append(source.charAt(cursor) == '\n' ? '\n' : ' ');
      cursor++;
    }
    return cursor;
  }

  private static int maskInterpolation(
      String source, StringBuilder visible, int index, Syntax syntax) {
    int end = findInterpolationEnd(source, index + 2, syntax);
    int expressionEnd = end < 0 ? source.length() : end;
    visible.append("${");
    visible.append(withoutCommentsAndLiterals(source.substring(index + 2, expressionEnd), syntax));
    if (end < 0) {
      return source.length();
    }
    visible.append('}');
    return end + 1;
  }

  private static int findInterpolationEnd(String source, int index, Syntax syntax) {
    int depth = 1;
    int cursor = index;
    StringBuilder discard = new StringBuilder();
    while (cursor < source.length()) {
      char current = source.charAt(cursor);
      char next = cursor + 1 < source.length() ? source.charAt(cursor + 1) : '\0';
      if (current == '/' && next == '/') {
        discard.setLength(0);
        cursor = maskUntilLineEnd(source, discard, cursor + 2);
      } else if (current == '/' && next == '*') {
        discard.setLength(0);
        cursor = maskBlockComment(source, discard, cursor + 2, syntax == Syntax.KOTLIN);
      } else if (isMultilineLiteral(source, cursor, syntax)) {
        String delimiter = source.substring(cursor, cursor + 3);
        discard.setLength(0);
        cursor = copyMultilineLiteral(source, discard, cursor, delimiter, syntax != Syntax.KOTLIN);
      } else if (syntax == Syntax.GROOVY && isGroovySlashyLiteral(source, cursor)) {
        discard.setLength(0);
        cursor = maskGroovySlashyLiteral(source, discard, cursor, source.startsWith("$/", cursor));
      } else if (current == '"' || current == '\'') {
        discard.setLength(0);
        cursor = copyQuotedLiteral(source, discard, cursor, current);
      } else {
        if (current == '{') {
          depth++;
        } else if (current == '}' && --depth == 0) {
          return cursor;
        }
        cursor++;
      }
    }
    return -1;
  }

  private static int maskMultilineLiteral(
      String source,
      StringBuilder visible,
      int index,
      String delimiter,
      boolean supportsEscapedDelimiter,
      boolean supportsInterpolation,
      Syntax syntax) {
    visible.append("   ");
    int cursor = index;
    while (cursor < source.length()) {
      if (source.startsWith(delimiter, cursor)
          && (!supportsEscapedDelimiter || !isEscaped(source, cursor))) {
        visible.append("   ");
        return cursor + 3;
      }
      if (supportsInterpolation
          && source.startsWith("${", cursor)
          && (syntax == Syntax.KOTLIN || !isEscaped(source, cursor))) {
        cursor = maskInterpolation(source, visible, cursor, syntax);
        continue;
      }
      if (supportsInterpolation
          && syntax == Syntax.GROOVY
          && isUnbracedGroovyInterpolation(source, cursor)
          && !isEscaped(source, cursor)) {
        cursor = copyUnbracedGroovyInterpolation(source, visible, cursor);
        continue;
      }
      visible.append(source.charAt(cursor) == '\n' ? '\n' : ' ');
      cursor++;
    }
    return cursor;
  }

  private static boolean isEscaped(String source, int index) {
    int backslashes = 0;
    for (int cursor = index - 1; cursor >= 0 && source.charAt(cursor) == '\\'; cursor--) {
      backslashes++;
    }
    return backslashes % 2 != 0;
  }

  private static boolean isDollarEscaped(String source, int index) {
    int dollars = 0;
    for (int cursor = index - 1; cursor >= 0 && source.charAt(cursor) == '$'; cursor--) {
      dollars++;
    }
    return dollars % 2 != 0;
  }

  private static int maskQuotedLiteral(
      String source,
      StringBuilder visible,
      int index,
      char delimiter,
      boolean supportsInterpolation,
      Syntax syntax) {
    visible.append(' ');
    int cursor = index;
    boolean escaped = false;
    while (cursor < source.length()) {
      char current = source.charAt(cursor);
      if (supportsInterpolation
          && delimiter == '"'
          && !escaped
          && current == '$'
          && cursor + 1 < source.length()
          && source.charAt(cursor + 1) == '{') {
        cursor = maskInterpolation(source, visible, cursor, syntax);
        continue;
      }
      if (supportsInterpolation
          && syntax == Syntax.GROOVY
          && delimiter == '"'
          && !escaped
          && isUnbracedGroovyInterpolation(source, cursor)) {
        cursor = copyUnbracedGroovyInterpolation(source, visible, cursor);
        continue;
      }
      visible.append(current == '\n' ? '\n' : ' ');
      cursor++;
      if (current == '\n') {
        return cursor;
      }
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

  private static boolean isUnbracedGroovyInterpolation(String source, int index) {
    return source.charAt(index) == '$'
        && index + 1 < source.length()
        && Character.isJavaIdentifierStart(source.charAt(index + 1));
  }

  private static int copyUnbracedGroovyInterpolation(
      String source, StringBuilder visible, int index) {
    int cursor = index + 2;
    while (cursor < source.length()) {
      char current = source.charAt(cursor);
      if (Character.isJavaIdentifierPart(current)) {
        cursor++;
      } else if (current == '.'
          && cursor + 1 < source.length()
          && Character.isJavaIdentifierStart(source.charAt(cursor + 1))) {
        cursor += 2;
      } else {
        break;
      }
    }
    visible.append(source, index, cursor);
    return cursor;
  }
}
