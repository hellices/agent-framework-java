package io.github.hellices.agentframework.mcp;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Immutable configuration for adapting MCP tools into framework tools.
 *
 * <p>Instances are built through {@link #builder()} and are safe to share across threads and across
 * adapters. {@link #defaults()} adds no prefix, allows no arguments beyond the ones the server's
 * input schema declares, and publishes no request metadata.
 */
public final class McpToolAdapterOptions {

  private static final McpCallMetadataProvider NO_METADATA = context -> Map.of();
  private static final int DEFAULT_MAX_DISCOVERY_PAGES = 256;

  private final String localNamePrefix;
  private final Set<String> additionalArgumentNames;
  private final McpCallMetadataProvider callMetadataProvider;
  private final boolean includeResultPayload;
  private final int maxDiscoveryPages;

  private McpToolAdapterOptions(Builder builder) {
    this.localNamePrefix = builder.localNamePrefix;
    this.additionalArgumentNames = Set.copyOf(builder.additionalArgumentNames);
    this.callMetadataProvider = builder.callMetadataProvider;
    this.includeResultPayload = builder.includeResultPayload;
    this.maxDiscoveryPages = builder.maxDiscoveryPages;
  }

  /**
   * Returns options that apply no prefix, allow no extra arguments, and publish no metadata.
   *
   * @return the default options, never {@code null}
   */
  public static McpToolAdapterOptions defaults() {
    return builder().build();
  }

  /**
   * Returns a new builder.
   *
   * @return a builder seeded with the default options, never {@code null}
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Returns the text prepended to every local tool name.
   *
   * <p>The prefix is prepended verbatim after name normalization, so a caller that wants a
   * separator includes it, as in {@code github_}. A prefix keeps tools from two servers apart when
   * both publish the same remote name.
   *
   * @return the prefix, never {@code null}, empty when no prefix applies
   */
  public String localNamePrefix() {
    return localNamePrefix;
  }

  /**
   * Returns argument names that may be sent even though the server's input schema omits them.
   *
   * <p>Everything else a model produces is dropped before the request is built, because a server is
   * entitled to reject arguments its schema does not declare. This escape hatch exists for servers
   * that accept documented arguments they do not publish.
   *
   * @return the additional argument names, never {@code null}, unmodifiable
   */
  public Set<String> additionalArgumentNames() {
    return additionalArgumentNames;
  }

  /**
   * Returns the provider of protocol level request metadata.
   *
   * @return the metadata provider, never {@code null}
   */
  public McpCallMetadataProvider callMetadataProvider() {
    return callMetadataProvider;
  }

  /**
   * Returns whether a result's structured content and result {@code _meta} are carried as a
   * trailing {@link McpPayloadContent}.
   *
   * <p>Off by default, because both are ordinary features of a server that answers in text:
   * carrying them would turn a plain text result into adapter owned content, and the session format
   * only defines the framework content kinds, so a snapshot taken after such a call would fail long
   * after the call itself succeeded. Turning this on keeps the payload and accepts that a session
   * holding it cannot be persisted until a content codec for extension content exists.
   *
   * <p>A result whose only answer is structured content, with no content entries at all, keeps that
   * payload whatever this option says, because dropping it would report a successful call that
   * returned nothing rather than dropping an annotation on an answer.
   *
   * @return {@code true} when the trailing result payload is kept
   */
  public boolean includeResultPayload() {
    return includeResultPayload;
  }

  /**
   * Returns the highest number of {@code tools/list} pages one discovery may read.
   *
   * <p>Pagination is driven by cursors the server chooses, so a server that keeps issuing fresh
   * cursors would be followed until the process gives up. The bound turns that into a protocol
   * failure that names the limit.
   *
   * @return the page bound, always positive
   */
  public int maxDiscoveryPages() {
    return maxDiscoveryPages;
  }

  /** Builder for {@link McpToolAdapterOptions}. */
  public static final class Builder {

    private String localNamePrefix = "";
    private Set<String> additionalArgumentNames = Set.of();
    private McpCallMetadataProvider callMetadataProvider = NO_METADATA;
    private boolean includeResultPayload;
    private int maxDiscoveryPages = DEFAULT_MAX_DISCOVERY_PAGES;

    private Builder() {}

    /**
     * Sets the text prepended to every local tool name.
     *
     * @param localNamePrefix the prefix, never {@code null} or blank
     * @return this builder, never {@code null}
     * @throws IllegalArgumentException if the prefix is {@code null} or blank
     */
    public Builder localNamePrefix(String localNamePrefix) {
      if (localNamePrefix == null || localNamePrefix.isBlank()) {
        throw new IllegalArgumentException("localNamePrefix must not be null or blank");
      }
      this.localNamePrefix = localNamePrefix;
      return this;
    }

    /**
     * Replaces the argument names allowed beyond the server's input schema.
     *
     * @param additionalArgumentNames the argument names, never {@code null} and never containing a
     *     {@code null} or blank entry
     * @return this builder, never {@code null}
     * @throws IllegalArgumentException if the collection is {@code null} or holds a {@code null} or
     *     blank entry
     */
    public Builder additionalArgumentNames(Collection<String> additionalArgumentNames) {
      if (additionalArgumentNames == null) {
        throw new IllegalArgumentException("additionalArgumentNames must not be null");
      }
      Set<String> copy = new LinkedHashSet<>();
      for (String name : additionalArgumentNames) {
        copy.add(requireArgumentName(name));
      }
      this.additionalArgumentNames = copy;
      return this;
    }

    /**
     * Adds one argument name allowed beyond the server's input schema.
     *
     * @param additionalArgumentName the argument name, never {@code null} or blank
     * @return this builder, never {@code null}
     * @throws IllegalArgumentException if the name is {@code null} or blank
     */
    public Builder addAdditionalArgumentName(String additionalArgumentName) {
      Set<String> copy = new LinkedHashSet<>(additionalArgumentNames);
      copy.add(requireArgumentName(additionalArgumentName));
      this.additionalArgumentNames = copy;
      return this;
    }

    /**
     * Sets the provider of protocol level request metadata.
     *
     * @param callMetadataProvider the provider, never {@code null}
     * @return this builder, never {@code null}
     * @throws IllegalArgumentException if the provider is {@code null}
     */
    public Builder callMetadataProvider(McpCallMetadataProvider callMetadataProvider) {
      if (callMetadataProvider == null) {
        throw new IllegalArgumentException("callMetadataProvider must not be null");
      }
      this.callMetadataProvider = callMetadataProvider;
      return this;
    }

    /**
     * Sets whether a result's structured content and result {@code _meta} are kept as a trailing
     * payload.
     *
     * @param includeResultPayload {@code true} to keep the payload, accepting that a session
     *     holding it cannot be persisted until a content codec for extension content exists
     * @return this builder, never {@code null}
     */
    public Builder includeResultPayload(boolean includeResultPayload) {
      this.includeResultPayload = includeResultPayload;
      return this;
    }

    /**
     * Sets the highest number of {@code tools/list} pages one discovery may read.
     *
     * @param maxDiscoveryPages the page bound, always positive
     * @return this builder, never {@code null}
     * @throws IllegalArgumentException if the bound is not positive
     */
    public Builder maxDiscoveryPages(int maxDiscoveryPages) {
      if (maxDiscoveryPages <= 0) {
        throw new IllegalArgumentException("maxDiscoveryPages must be positive");
      }
      this.maxDiscoveryPages = maxDiscoveryPages;
      return this;
    }

    /**
     * Builds the options.
     *
     * @return the options, never {@code null}
     */
    public McpToolAdapterOptions build() {
      return new McpToolAdapterOptions(this);
    }

    private static String requireArgumentName(String name) {
      if (name == null || name.isBlank()) {
        throw new IllegalArgumentException("additionalArgumentNames must not hold a blank name");
      }
      return name;
    }
  }
}
