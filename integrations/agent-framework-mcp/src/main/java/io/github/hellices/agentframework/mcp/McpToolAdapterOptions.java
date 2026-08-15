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

  private final String localNamePrefix;
  private final Set<String> additionalArgumentNames;
  private final McpCallMetadataProvider callMetadataProvider;

  private McpToolAdapterOptions(Builder builder) {
    this.localNamePrefix = builder.localNamePrefix;
    this.additionalArgumentNames = Set.copyOf(builder.additionalArgumentNames);
    this.callMetadataProvider = builder.callMetadataProvider;
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

  /** Builder for {@link McpToolAdapterOptions}. */
  public static final class Builder {

    private String localNamePrefix = "";
    private Set<String> additionalArgumentNames = Set.of();
    private McpCallMetadataProvider callMetadataProvider = NO_METADATA;

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
