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
import java.util.Locale;
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
 * policy names: every root Markdown file, every Markdown file under {@code .github}, and every
 * Markdown file under {@code docs}. Everything else in the working tree is out of scope because the
 * repository does not own it as documentation: generated build output, ignored session artifacts,
 * agent plugin directories, nested worktrees, dependency notices, and scratch files outside those
 * locations.
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

  /** The GitHub metadata tree the repository owns in full. */
  private static final String OWNED_GITHUB_DIRECTORY = ".github";

  /** The documentation tree the repository owns in full. */
  private static final String OWNED_DOCUMENTATION_TREE = "docs";

  private static final String MARKDOWN_SUFFIX = ".md";

  private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");

  private static final Pattern FENCE = Pattern.compile("^\\s{0,3}(```|~~~)");

  private static final Pattern INLINE_CODE = Pattern.compile("`+[^`]*`+");

  private static final Pattern INLINE_LINK =
      Pattern.compile("\\[[^\\]]*\\]\\(\\s*<?([^)>\\s]+)>?(?:\\s+\"[^\"]*\")?\\s*\\)");

  private static final Pattern REFERENCE_DEFINITION =
      Pattern.compile(
          "^\\s{0,3}\\[([^\\]]+)\\]:\\s*<?([^>\\s]+)>?"
              + "(?:\\s+(?:\"[^\"]*\"|'[^']*'|\\([^)]*\\)))?\\s*$");

  private static final Pattern FULL_REFERENCE_LINK =
      Pattern.compile("(?<!!)\\[([^\\]]+)\\]\\[([^\\]]*)\\]");

  private static final Pattern SHORTCUT_REFERENCE_LINK =
      Pattern.compile("(?<!!)\\[([^\\]]+)\\](?!\\s*[\\[(])");

  private static final Pattern AUTOLINK = Pattern.compile("<([^<>\\s]+)>");

  private static final Pattern LINK_LABEL = Pattern.compile("\\[([^\\]]*)\\]\\([^)]*\\)");

  private static final Pattern ABSOLUTE_SCHEME = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*:");

  private MarkdownDocuments() {}

  /** A Markdown link, kept with its source position so a failure names the line to fix. */
  record Link(Path source, int line, String target) {

    String describe() {
      return relativePath(source) + ":" + line + " -> " + target;
    }
  }

  private record LinkCandidate(int start, int end, String target) {}

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
    if (Files.isDirectory(root)) {
      try (Stream<Path> children = Files.list(root)) {
        children.filter(MarkdownDocuments::isMarkdownFile).forEach(markdown::add);
      }
    }
    Path github = root.resolve(OWNED_GITHUB_DIRECTORY);
    if (Files.isDirectory(github)) {
      try (Stream<Path> tree = Files.walk(github)) {
        tree.filter(MarkdownDocuments::isMarkdownFile).forEach(markdown::add);
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
   * Returns every inline, reference-style, and relative autolink outside fenced blocks.
   *
   * @param file the document to inspect
   * @return the links, in document order
   * @throws IOException when the document cannot be read
   */
  static List<Link> links(Path file) throws IOException {
    List<Link> links = new ArrayList<>();
    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    Map<String, String> references = referenceTargets(lines);
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
      if (REFERENCE_DEFINITION.matcher(line).matches()) {
        continue;
      }
      List<LinkCandidate> candidates = new ArrayList<>();
      Matcher inline = INLINE_LINK.matcher(line);
      while (inline.find()) {
        candidates.add(new LinkCandidate(inline.start(), inline.end(), inline.group(1)));
      }
      Matcher fullReference = FULL_REFERENCE_LINK.matcher(line);
      while (fullReference.find()) {
        String label =
            fullReference.group(2).isEmpty() ? fullReference.group(1) : fullReference.group(2);
        String target = references.get(normalizeReferenceLabel(label));
        if (target != null) {
          candidates.add(new LinkCandidate(fullReference.start(), fullReference.end(), target));
        }
      }
      Matcher shortcutReference = SHORTCUT_REFERENCE_LINK.matcher(line);
      while (shortcutReference.find()) {
        String target = references.get(normalizeReferenceLabel(shortcutReference.group(1)));
        if (target != null) {
          candidates.add(
              new LinkCandidate(shortcutReference.start(), shortcutReference.end(), target));
        }
      }
      Matcher autolink = AUTOLINK.matcher(line);
      while (autolink.find()) {
        if (isRelativeAutolinkTarget(autolink.group(1))) {
          candidates.add(new LinkCandidate(autolink.start(), autolink.end(), autolink.group(1)));
        }
      }
      List<LinkCandidate> codeSpans = new ArrayList<>();
      Matcher inlineCode = INLINE_CODE.matcher(line);
      while (inlineCode.find()) {
        codeSpans.add(new LinkCandidate(inlineCode.start(), inlineCode.end(), ""));
      }
      candidates.sort(Comparator.comparingInt(LinkCandidate::start));
      List<LinkCandidate> accepted = new ArrayList<>();
      for (LinkCandidate candidate : candidates) {
        if (codeSpans.stream().noneMatch(codeSpan -> overlaps(codeSpan, candidate))
            && accepted.stream().noneMatch(existing -> overlaps(existing, candidate))) {
          accepted.add(candidate);
          links.add(new Link(file, index + 1, candidate.target()));
        }
      }
    }
    return List.copyOf(links);
  }

  private static Map<String, String> referenceTargets(List<String> lines) {
    Map<String, String> references = new HashMap<>();
    boolean insideFence = false;
    for (String line : lines) {
      if (FENCE.matcher(line).find()) {
        insideFence = !insideFence;
        continue;
      }
      if (insideFence) {
        continue;
      }
      Matcher definition = REFERENCE_DEFINITION.matcher(line);
      if (definition.matches()) {
        references.putIfAbsent(normalizeReferenceLabel(definition.group(1)), definition.group(2));
      }
    }
    return references;
  }

  private static String normalizeReferenceLabel(String label) {
    return label.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
  }

  private static boolean isRelativeAutolinkTarget(String target) {
    if (!isLocalTarget(target) || target.contains("@")) {
      return false;
    }
    String filePart = filePartOf(target);
    return target.startsWith("#")
        || target.startsWith("./")
        || target.startsWith("../")
        || filePart.contains("/")
        || filePart.matches(".*\\.[A-Za-z0-9]+$");
  }

  private static boolean overlaps(LinkCandidate first, LinkCandidate second) {
    return first.start() < second.end() && second.start() < first.end();
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
   * Reports whether every target path component uses the case stored by the filesystem.
   *
   * @param root the root from which to compare path components
   * @param target the target path to inspect
   * @return {@code true} when the target is inside the root and every component matches exactly
   * @throws IOException when a target parent cannot be read
   */
  static boolean hasExactPathCase(Path root, Path target) throws IOException {
    Path normalizedRoot = root.toAbsolutePath().normalize();
    Path normalizedTarget = target.toAbsolutePath().normalize();
    if (!normalizedTarget.startsWith(normalizedRoot)) {
      return false;
    }
    Path current = normalizedRoot;
    for (Path component : normalizedRoot.relativize(normalizedTarget)) {
      if (!Files.isDirectory(current)) {
        return false;
      }
      try (Stream<Path> children = Files.list(current)) {
        Optional<Path> exact =
            children.filter(child -> component.equals(child.getFileName())).findFirst();
        if (exact.isEmpty()) {
          return false;
        }
        current = exact.get();
      }
    }
    return true;
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
