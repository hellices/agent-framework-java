package io.github.hellices.agentframework.build.harness;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads Gradle project registration and build files for repository policy tests.
 *
 * <p>The build file parse reads exactly one project dependency form, {@code
 * configuration(project(":path"))} on one line, and refuses everything else. A policy that cannot
 * see a dependency reports a module as depending on nothing, which passes; a policy that refuses a
 * form it cannot read fails and says so. Only the second failure mode is safe here.
 */
final class ProjectLayout {

  private static final Pattern INCLUDE =
      Pattern.compile("^\\s*include\\(\"(:[A-Za-z0-9:_-]+)\"\\)\\s*$", Pattern.MULTILINE);

  /** The one project dependency form this parse reads, named by every refusal it raises. */
  private static final String CANONICAL_FORM = "configuration(project(\":path\"))";

  private static final Pattern PROJECT_CALL = Pattern.compile("project\\s*\\(");

  private static final Pattern PROJECTS_ACCESSOR =
      Pattern.compile("projects\\s*\\.\\s*[A-Za-z_][A-Za-z0-9_]*");

  private static final Pattern LITERAL_PATH = Pattern.compile("\"(:[A-Za-z0-9:_-]+)\"");

  /**
   * Calls that carry a project dependency outward rather than declaring it.
   *
   * <p>Each hands the dependency to the call around it, so the configuration that governs the
   * dependency is the next name further out. Treating one of these as the configuration would
   * classify {@code api(platform(project(":agent-framework-bom")))} by the name {@code platform},
   * which decides nothing about whether a consumer inherits the dependency.
   */
  private static final Set<String> DEPENDENCY_WRAPPERS =
      Set.of("project", "platform", "enforcedPlatform", "testFixtures");

  private ProjectLayout() {}

  /**
   * Returns every Gradle project path registered in {@code settings.gradle.kts}.
   *
   * @return registered project paths such as {@code :agent-framework-api}
   */
  static List<String> includedProjects() {
    Matcher matcher = INCLUDE.matcher(read(RepositoryPaths.root().resolve("settings.gradle.kts")));
    List<String> projects = new ArrayList<>();
    while (matcher.find()) {
      projects.add(matcher.group(1));
    }
    return List.copyOf(projects);
  }

  /**
   * Returns the directory that backs a Gradle project path.
   *
   * @param gradlePath the Gradle project path
   * @return the project directory
   */
  static Path projectDirectory(String gradlePath) {
    return RepositoryPaths.root().resolve(gradlePath.substring(1).replace(':', '/'));
  }

  /**
   * Returns the build file of a Gradle project.
   *
   * @param gradlePath the Gradle project path
   * @return the build file path
   */
  static Path buildFile(String gradlePath) {
    return projectDirectory(gradlePath).resolve("build.gradle.kts");
  }

  /**
   * Returns the build file contents of a Gradle project.
   *
   * @param gradlePath the Gradle project path
   * @return the build file text
   */
  static String buildFileText(String gradlePath) {
    return read(buildFile(gradlePath));
  }

  /**
   * Returns the project dependencies a build file declares on a production configuration.
   *
   * <p>Test configurations are excluded on purpose. A test-only project dependency does not reach a
   * consumer, so treating it as a shipped dependency would force the allowlist to permit a
   * production dependency in order to permit a test.
   *
   * @param gradlePath the Gradle project path
   * @return the Gradle paths this project ships against
   */
  static List<String> projectDependenciesOf(String gradlePath) {
    return projectDependenciesIn(buildFileText(gradlePath));
  }

  /**
   * Returns the project dependencies a build file declares on a test configuration.
   *
   * @param gradlePath the Gradle project path
   * @return the Gradle paths this project compiles or runs its tests against
   */
  static List<String> testProjectDependenciesOf(String gradlePath) {
    return testProjectDependenciesIn(buildFileText(gradlePath));
  }

  static List<String> projectDependenciesIn(String buildFileText) {
    return dependenciesIn(buildFileText, false);
  }

  static List<String> testProjectDependenciesIn(String buildFileText) {
    return dependenciesIn(buildFileText, true);
  }

  private static List<String> dependenciesIn(String buildFileText, boolean testConfigurations) {
    List<String> dependencies = new ArrayList<>();
    for (CodeLine line : codeLines(buildFileText)) {
      Declaration declaration = declarationIn(line);
      if (declaration != null && declaration.test() == testConfigurations) {
        dependencies.add(declaration.path());
      }
    }
    return List.copyOf(dependencies);
  }

  /**
   * Reads the project dependency a single build file line declares.
   *
   * @param line one build file line with its comments already removed
   * @return the declaration, or {@code null} when the line declares no project dependency
   */
  private static Declaration declarationIn(CodeLine line) {
    rejectTypeSafeAccessors(line);
    List<ProjectCall> calls = projectCallsIn(line);
    if (calls.isEmpty()) {
      return null;
    }
    if (calls.size() > 1) {
      // A line carries one configuration, so attributing every reference on it to the first
      // configuration found would report `testImplementation(project(":a")); api(project(":b"))`
      // as two test dependencies and let ":b" ship unchecked.
      throw new IllegalStateException(
          "This line declares more than one project dependency: "
              + renderCalls(line, calls)
              + ". Declare each one as "
              + CANONICAL_FORM
              + " on its own line, because one line carries one configuration and the module"
              + " composition policy cannot tell which configuration governs which dependency.");
    }
    ProjectCall call = calls.get(0);
    String path = literalPath(line, call);
    String configuration = configurationFor(line, call);
    if (configuration == null) {
      throw new IllegalStateException(
          "Cannot read the configuration of the project dependency "
              + path
              + ". Declare it as "
              + CANONICAL_FORM
              + " on one line so the module composition policy can tell a production dependency"
              + " from a test dependency.");
    }
    return new Declaration(path, declaresTestDependency(configuration));
  }

  /**
   * Reports whether a configuration name declares a test-only dependency.
   *
   * <p>Every configuration whose name starts with {@code test} is test-only. That includes {@code
   * testFixturesApi} and {@code testFixturesImplementation}, which carry the dependencies of the
   * {@code testFixtures} source set rather than of the published artifact: a consumer never
   * inherits them by depending on the module, it reaches them only by asking for {@code
   * testFixtures(project(":path"))} from a test configuration of its own. Classifying them as
   * production would force the production allowlist to grant a dependency no published artifact
   * carries, which is the conflation the split allowlist exists to remove.
   *
   * <p>This holds while no module here publishes test fixtures. Applying {@code java-test-fixtures}
   * to a published library adds a {@code -test-fixtures} variant whose own dependencies do reach a
   * consumer, so that change must revisit this method and rule 4 of {@code
   * docs/design/module-composition.md} together.
   *
   * @param configuration the configuration a dependency is declared on
   * @return whether the dependency reaches tests only
   */
  private static boolean declaresTestDependency(String configuration) {
    return configuration.startsWith("test");
  }

  /**
   * Refuses a type-safe project accessor.
   *
   * <p>{@code implementation(projects.agentFrameworkEngine)} is a real project dependency that
   * carries no project path, so a parse that skipped it would report the module as depending on
   * nothing at all.
   *
   * @param line one build file line with its comments already removed
   */
  private static void rejectTypeSafeAccessors(CodeLine line) {
    Matcher accessor = PROJECTS_ACCESSOR.matcher(line.code());
    while (accessor.find()) {
      if (accessor.start() > 0 && isIdentifierPart(line.code().charAt(accessor.start() - 1))) {
        // `subprojects.` and `allprojects.` are Gradle collections, not accessors.
        continue;
      }
      throw new IllegalStateException(
          "Cannot read the project dependency `"
              + accessor.group()
              + "`. A type-safe project accessor names no project path, so the module composition"
              + " policy cannot tell which project it reaches. Declare it as "
              + CANONICAL_FORM
              + " on one line.");
    }
  }

  /**
   * Returns every {@code project(...)} call on a line, in source order.
   *
   * @param line one build file line with its comments already removed
   * @return the calls found outside string literals
   */
  private static List<ProjectCall> projectCallsIn(CodeLine line) {
    List<ProjectCall> calls = new ArrayList<>();
    Matcher call = PROJECT_CALL.matcher(line.code());
    while (call.find()) {
      char before = call.start() == 0 ? ' ' : line.code().charAt(call.start() - 1);
      if (isIdentifierPart(before)) {
        // A longer identifier that merely ends in "project", such as `subproject(`.
        continue;
      }
      int open = call.end() - 1;
      calls.add(
          new ProjectCall(
              call.start(), open, closingParenthesis(line.code(), open), before == '.'));
    }
    return calls;
  }

  /**
   * Returns the project path a call names, refusing any form the policy cannot resolve.
   *
   * @param line the line the call sits on
   * @param call the call to read
   * @return the Gradle project path
   */
  private static String literalPath(CodeLine line, ProjectCall call) {
    if (call.close() < 0) {
      throw new IllegalStateException(
          "Cannot read the project dependency `"
              + line.text().trim()
              + "`: its argument list does not close on this line. Declare it as "
              + CANONICAL_FORM
              + " on one line.");
    }
    String argument = line.text().substring(call.open() + 1, call.close()).trim();
    Matcher literal = LITERAL_PATH.matcher(argument);
    if (call.qualified() || !literal.matches()) {
      // `project(path = ":x")`, `project(":x", configuration = "y")`, `project(variable)`, and
      // `rootProject.project(":x")` all resolve to a real dependency. Reading nothing from them
      // would report the module as depending on nothing, so each is refused by name instead.
      throw new IllegalStateException(
          "Cannot read the project dependency `"
              + (call.qualified() ? line.text().trim() : "project(" + argument + ")")
              + "`. The module composition policy reads one form, "
              + CANONICAL_FORM
              + ", with an unqualified call and a literal path on one line; it resolves no other"
              + " expression to a project path.");
    }
    return literal.group(1);
  }

  /**
   * Returns the configuration that governs a project dependency.
   *
   * <p>The walk starts at the call and moves outward through wrapper calls, so {@code
   * api(platform(project(":agent-framework-bom")))} and {@code
   * testImplementation(testFixtures(project(":x")))} both report the configuration that decides
   * whether a consumer inherits the dependency.
   *
   * @param line the line the call sits on
   * @param call the call to read
   * @return the configuration name, or {@code null} when it is not on this line
   */
  private static String configurationFor(CodeLine line, ProjectCall call) {
    String code = line.code();
    int index = call.nameStart() - 1;
    while (true) {
      index = skipWhitespaceBackwards(code, index);
      if (index < 0 || code.charAt(index) != '(') {
        return null;
      }
      index = skipWhitespaceBackwards(code, index - 1);
      int end = index + 1;
      while (index >= 0 && isIdentifierPart(code.charAt(index))) {
        index--;
      }
      if (index + 1 == end) {
        return null;
      }
      String name = code.substring(index + 1, end);
      if (!DEPENDENCY_WRAPPERS.contains(name)) {
        return name;
      }
    }
  }

  private static String renderCalls(CodeLine line, List<ProjectCall> calls) {
    List<String> rendered = new ArrayList<>();
    for (ProjectCall call : calls) {
      int close = call.close() < 0 ? line.text().length() : call.close();
      rendered.add("project(" + line.text().substring(call.open() + 1, close).trim() + ")");
    }
    return String.join(", ", rendered);
  }

  private static int closingParenthesis(String code, int open) {
    int depth = 0;
    for (int index = open; index < code.length(); index++) {
      if (code.charAt(index) == '(') {
        depth++;
      } else if (code.charAt(index) == ')') {
        depth--;
        if (depth == 0) {
          return index;
        }
      }
    }
    return -1;
  }

  private static int skipWhitespaceBackwards(String code, int from) {
    int index = from;
    while (index >= 0 && Character.isWhitespace(code.charAt(index))) {
      index--;
    }
    return index;
  }

  private static boolean isIdentifierPart(char character) {
    return Character.isLetterOrDigit(character) || character == '_';
  }

  /**
   * Splits a build file into lines with comments removed.
   *
   * @param buildFileText the whole build file
   * @return one entry per source line
   */
  private static List<CodeLine> codeLines(String buildFileText) {
    List<CodeLine> lines = new ArrayList<>();
    CommentScanner scanner = new CommentScanner();
    for (String line : buildFileText.split("\\R", -1)) {
      lines.add(scanner.scan(line));
    }
    return List.copyOf(lines);
  }

  /**
   * One build file line, stripped of comments.
   *
   * @param text the line with comment characters replaced by spaces
   * @param code the same line with string and character literal contents blanked as well
   */
  private record CodeLine(String text, String code) {}

  /**
   * One {@code project(...)} call on a line.
   *
   * @param nameStart index of the {@code project} identifier
   * @param open index of the opening parenthesis
   * @param close index of the matching closing parenthesis, or {@code -1} when it is not on this
   *     line
   * @param qualified whether the call has a receiver, as in {@code rootProject.project(":path")}
   */
  private record ProjectCall(int nameStart, int open, int close, boolean qualified) {}

  /**
   * One project dependency a build file declares.
   *
   * @param path the Gradle project path depended on
   * @param test whether the declaring configuration reaches tests only
   */
  private record Declaration(String path, boolean test) {}

  /**
   * Removes Kotlin comments from a build file one line at a time.
   *
   * <p>Comment text is replaced by spaces rather than deleted, so every index in a scanned line
   * still matches the source line. The scan tracks string literals because cutting a line at the
   * first {@code //} would also cut it at the one inside {@code "https://example.invalid"} and
   * delete a real declaration that followed on the same line. It tracks block comment nesting and
   * raw strings across lines because Kotlin allows both to span them.
   */
  private static final class CommentScanner {

    private int blockCommentDepth;
    private boolean inRawString;

    CodeLine scan(String line) {
      StringBuilder text = new StringBuilder(line.length());
      StringBuilder code = new StringBuilder(line.length());
      int index = 0;
      while (index < line.length()) {
        if (blockCommentDepth > 0) {
          index = scanBlockComment(line, index, text, code);
        } else if (inRawString) {
          index = scanRawString(line, index, text, code);
        } else if (line.startsWith("//", index)) {
          blank(text, code, line.length() - index);
          index = line.length();
        } else if (line.startsWith("/*", index)) {
          // A KDoc block opens with "/**", which is a block comment that starts with an asterisk.
          blockCommentDepth = 1;
          blank(text, code, 2);
          index += 2;
        } else if (line.startsWith("\"\"\"", index)) {
          inRawString = true;
          keep(text, code, line, index, 3);
          index += 3;
        } else if (line.charAt(index) == '"' || line.charAt(index) == '\'') {
          index = scanLiteral(line, index, text, code);
        } else {
          text.append(line.charAt(index));
          code.append(line.charAt(index));
          index++;
        }
      }
      return new CodeLine(text.toString(), code.toString());
    }

    private int scanBlockComment(String line, int index, StringBuilder text, StringBuilder code) {
      if (line.startsWith("/*", index)) {
        blockCommentDepth++;
        blank(text, code, 2);
        return index + 2;
      }
      if (line.startsWith("*/", index)) {
        blockCommentDepth--;
        blank(text, code, 2);
        return index + 2;
      }
      blank(text, code, 1);
      return index + 1;
    }

    private int scanRawString(String line, int index, StringBuilder text, StringBuilder code) {
      if (line.startsWith("\"\"\"", index)) {
        inRawString = false;
        keep(text, code, line, index, 3);
        return index + 3;
      }
      keep(text, code, line, index, 1);
      return index + 1;
    }

    private int scanLiteral(String line, int start, StringBuilder text, StringBuilder code) {
      char delimiter = line.charAt(start);
      keep(text, code, line, start, 1);
      int index = start + 1;
      while (index < line.length()) {
        char current = line.charAt(index);
        if (current == '\\' && index + 1 < line.length()) {
          keep(text, code, line, index, 2);
          index += 2;
          continue;
        }
        keep(text, code, line, index, 1);
        index++;
        if (current == delimiter) {
          break;
        }
      }
      return index;
    }

    private static void keep(
        StringBuilder text, StringBuilder code, String line, int from, int length) {
      int end = Math.min(from + length, line.length());
      text.append(line, from, end);
      code.append(" ".repeat(end - from));
    }

    private static void blank(StringBuilder text, StringBuilder code, int length) {
      text.append(" ".repeat(length));
      code.append(" ".repeat(length));
    }
  }

  private static String read(Path path) {
    try {
      return Files.readString(path, StandardCharsets.UTF_8);
    } catch (IOException cause) {
      throw new UncheckedIOException("Cannot read " + path, cause);
    }
  }
}
