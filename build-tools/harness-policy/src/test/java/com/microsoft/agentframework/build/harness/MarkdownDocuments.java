package com.microsoft.agentframework.build.harness;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reads repository-owned Markdown for the documentation language, companion, and link policies.
 *
 * <p>{@link #files()} is an allowlist of locations, not a filtered walk of the working tree. It
 * returns Markdown from exactly the canonical locations section 8.1 of the documentation language
 * policy names: the root documents listed in {@link #OWNED_ROOT_DOCUMENTS}, the Markdown files
 * directly inside {@code .github}, and every Markdown file under {@code docs}. Everything else in
 * the working tree is out of scope because the repository does not own it as documentation:
 * generated build output, ignored session artifacts, agent plugin directories, nested worktrees,
 * dependency notices, and untracked scratch files. Nothing a contributor happens to leave on disk
 * can widen the canonical document set.
 *
 * <p>Ownership is decided by location alone, never by a directory name. A rule such as "skip every
 * directory called {@code build}" also skips {@code com/microsoft/agentframework/build/harness},
 * which is why {@code .gitignore} pins project output with {@code /build/}, {@code /*}{@code
 * /build/}, and {@code /*}{@code /*}{@code /build/} instead of a bare {@code build/}. A {@code
 * build}, {@code bin}, or {@code out} path segment inside an owned location is scanned like any
 * other segment.
 *
 * <p>The scan reads the filesystem rather than calling {@code git ls-files} so the policy runs in a
 * test JVM with no process dependency.
 */
final class MarkdownDocuments {

  /** The only Markdown file allowed to contain Korean text. */
  static final String KOREAN_COMPANION = "docs/ko/README.md";

  /** The English documentation index every directory index links back to. */
  static final String DOCUMENTATION_INDEX = "docs/README.md";

  /**
   * Root Markdown the repository owns: the product overview, the canonical instructions, the
   * contribution and security contracts, and the vendor instruction adapters.
   */
  private static final List<String> OWNED_ROOT_DOCUMENTS =
      List.of("README.md", "AGENTS.md", "CONTRIBUTING.md", "SECURITY.md", "CLAUDE.md", "GEMINI.md");

  /** GitHub metadata Markdown the repository owns: direct children only, as section 8.1 states. */
  private static final String OWNED_GITHUB_DIRECTORY = ".github";

  /** The documentation tree the repository owns in full. */
  private static final String OWNED_DOCUMENTATION_TREE = "docs";

  private static final String MARKDOWN_SUFFIX = ".md";

  private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");

  private static final Pattern FENCE = Pattern.compile("^\\s{0,3}(```|~~~)");

  private static final Pattern INLINE_LINK =
      Pattern.compile("\\[[^\\]]*\\]\\(\\s*<?([^)>\\s]+)>?(?:\\s+\"[^\"]*\")?\\s*\\)");

  private static final Pattern LINK_LABEL = Pattern.compile("\\[([^\\]]*)\\]\\([^)]*\\)");

  private static final Pattern ABSOLUTE_SCHEME = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*:");

  private MarkdownDocuments() {}

  /** A Markdown link, kept with its source position so a failure names the line to fix. */
  record Link(Path source, int line, String target) {

    String describe() {
      return relativePath(source) + ":" + line + " -> " + target;
    }
  }

  /**
   * Returns every repository-owned Markdown file, sorted by repository-relative path.
   *
   * @return the Markdown files this policy governs
   * @throws IOException when a canonical location cannot be read
   */
  static List<Path> files() throws IOException {
    return filesUnder(RepositoryPaths.root());
  }

  /**
   * Returns the Markdown files the canonical locations hold under a scan root.
   *
   * @param root the repository root, or an equivalent tree in a test
   * @return the owned Markdown files, sorted by root-relative path
   * @throws IOException when a canonical location cannot be read
   */
  static List<Path> filesUnder(Path root) throws IOException {
    List<Path> markdown = new ArrayList<>();
    for (String name : OWNED_ROOT_DOCUMENTS) {
      Path document = root.resolve(name);
      if (Files.isRegularFile(document)) {
        markdown.add(document);
      }
    }
    Path github = root.resolve(OWNED_GITHUB_DIRECTORY);
    if (Files.isDirectory(github)) {
      try (Stream<Path> children = Files.list(github)) {
        children.filter(MarkdownDocuments::isMarkdownFile).forEach(markdown::add);
      }
    }
    Path documentation = root.resolve(OWNED_DOCUMENTATION_TREE);
    if (Files.isDirectory(documentation)) {
      try (Stream<Path> tree = Files.walk(documentation)) {
        tree.filter(MarkdownDocuments::isMarkdownFile).forEach(markdown::add);
      }
    }
    markdown.sort(Comparator.comparing((Path file) -> relativePath(root, file)));
    return List.copyOf(markdown);
  }

  private static boolean isMarkdownFile(Path file) {
    Path name = file.getFileName();
    return name != null && name.toString().endsWith(MARKDOWN_SUFFIX) && Files.isRegularFile(file);
  }

  /**
   * Returns a repository-relative path with {@code /} separators.
   *
   * @param file any path inside the repository
   * @return the relative path
   */
  static String relativePath(Path file) {
    return relativePath(RepositoryPaths.root(), file);
  }

  /**
   * Returns a path relative to a scan root, with {@code /} separators.
   *
   * @param root the scan root
   * @param file any path inside that root
   * @return the relative path
   */
  static String relativePath(Path root, Path file) {
    return root.relativize(file).toString().replace('\\', '/');
  }

  /**
   * Reports whether text contains a character written in the Hangul script.
   *
   * @param text the text to inspect
   * @return {@code true} when at least one Hangul code point is present
   */
  static boolean containsHangul(String text) {
    return text.codePoints()
        .anyMatch(
            codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HANGUL);
  }

  /**
   * Returns every {@code path:line} in a document that still contains Hangul.
   *
   * @param file the document to inspect
   * @return the offending positions, empty when the document is free of Hangul
   * @throws IOException when the document cannot be read
   */
  static List<String> hangulLines(Path file) throws IOException {
    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    List<String> found = new ArrayList<>();
    for (int index = 0; index < lines.size(); index++) {
      if (containsHangul(lines.get(index))) {
        found.add(relativePath(file) + ":" + (index + 1));
      }
    }
    return List.copyOf(found);
  }

  /**
   * Returns every inline link outside fenced blocks.
   *
   * @param file the document to inspect
   * @return the links, in document order
   * @throws IOException when the document cannot be read
   */
  static List<Link> links(Path file) throws IOException {
    List<Link> links = new ArrayList<>();
    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    boolean insideFence = false;
    for (int index = 0; index < lines.size(); index++) {
      String line = lines.get(index);
      if (FENCE.matcher(line).find()) {
        insideFence = !insideFence;
        continue;
      }
      if (insideFence) {
        continue;
      }
      Matcher matcher = INLINE_LINK.matcher(line);
      while (matcher.find()) {
        links.add(new Link(file, index + 1, matcher.group(1)));
      }
    }
    return List.copyOf(links);
  }

  /**
   * Reports whether a link target names something inside this repository.
   *
   * @param target the raw link target
   * @return {@code false} for absolute URLs, protocol-relative URLs, and empty targets
   */
  static boolean isLocalTarget(String target) {
    if (target.isEmpty() || target.startsWith("//")) {
      return false;
    }
    return !ABSOLUTE_SCHEME.matcher(target).find();
  }

  /**
   * Returns the file part of a link target.
   *
   * @param target the raw link target
   * @return the part before {@code #}, empty for a same-document fragment
   */
  static String filePartOf(String target) {
    int hash = target.indexOf('#');
    return hash < 0 ? target : target.substring(0, hash);
  }

  /**
   * Returns the fragment of a link target.
   *
   * @param target the raw link target
   * @return the part after {@code #}, empty when there is none
   */
  static String fragmentOf(String target) {
    int hash = target.indexOf('#');
    return hash < 0 ? "" : target.substring(hash + 1);
  }

  /**
   * Resolves the file a link points at.
   *
   * @param source the document containing the link
   * @param target the raw link target
   * @return the resolved path, or empty when the target escapes the repository root
   */
  static Optional<Path> resolveTarget(Path source, String target) {
    Path root = RepositoryPaths.root();
    Path parent = source.getParent();
    Path base = parent == null ? root : parent;
    String filePart = filePartOf(target);
    Path resolved = (filePart.isEmpty() ? source : base.resolve(filePart)).normalize();
    return resolved.startsWith(root) ? Optional.of(resolved) : Optional.empty();
  }

  /**
   * Returns every anchor a Markdown document exposes, in document order.
   *
   * @param file the document to inspect
   * @return the generated anchors
   * @throws IOException when the document cannot be read
   */
  static Set<String> anchors(Path file) throws IOException {
    Set<String> anchors = new LinkedHashSet<>();
    Map<String, Integer> seen = new HashMap<>();
    boolean insideFence = false;
    for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
      if (FENCE.matcher(line).find()) {
        insideFence = !insideFence;
        continue;
      }
      if (insideFence) {
        continue;
      }
      Matcher heading = HEADING.matcher(line);
      if (!heading.matches()) {
        continue;
      }
      String anchor = anchorOf(heading.group(2));
      int repeat = seen.merge(anchor, 1, Integer::sum) - 1;
      anchors.add(repeat == 0 ? anchor : anchor + "-" + repeat);
    }
    return anchors;
  }

  /**
   * Renders heading text the way GitHub renders an anchor: link label only, lower case, every
   * character that is not a letter, digit, hyphen, or underscore dropped, spaces turned into
   * hyphens.
   *
   * @param headingText the heading text after the leading hashes
   * @return the anchor
   */
  static String anchorOf(String headingText) {
    String text = LINK_LABEL.matcher(headingText.trim()).replaceAll("$1");
    StringBuilder anchor = new StringBuilder(text.length());
    for (int index = 0; index < text.length(); index++) {
      char character = text.charAt(index);
      if (character == ' ') {
        anchor.append('-');
      } else if (Character.isLetterOrDigit(character) || character == '-' || character == '_') {
        anchor.append(Character.toLowerCase(character));
      }
    }
    return anchor.toString();
  }
}
