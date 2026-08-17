package io.github.hellices.agentframework.spi.session;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * The immutable load/store policy of a {@link HistoryProvider} (SES-013).
 *
 * <p>One provider implementation covers primary history, an audit sink, and an evaluation sink,
 * because what a provider loads and what it stores is configuration rather than a subtype. A
 * store-only audit sink is {@code loadMessages=false}; an input-only sink adds {@code
 * storeOutputs=false}; a context-only sink stores just the context another provider contributed.
 *
 * <p>Values are configured through {@link #builder()} (or the canonical constructor) and never
 * change afterwards, so one policy instance can be shared by every session a provider serves.
 */
public final class HistoryPolicy {

  private final boolean loadMessages;
  private final boolean storeInputs;
  private final boolean storeContextMessages;
  private final Set<String> storeContextFrom;
  private final boolean storeOutputs;

  /**
   * Normalizes the source filter and keeps it tri-state: {@code null} stays {@code null} and means
   * "no filter", an explicitly empty selection is rejected rather than silently widened to "every
   * source", and a source id that carries no provenance ({@code null} or blank) is rejected rather
   * than silently dropped, because it would otherwise match nothing and quietly narrow what the
   * provider stores.
   *
   * @throws IllegalArgumentException if {@code storeContextFrom} is non-null and empty, or contains
   *     a blank entry
   * @throws NullPointerException if {@code storeContextFrom} contains a {@code null} entry
   */
  public HistoryPolicy(
      boolean loadMessages,
      boolean storeInputs,
      boolean storeContextMessages,
      Set<String> storeContextFrom,
      boolean storeOutputs) {
    this.loadMessages = loadMessages;
    this.storeInputs = storeInputs;
    this.storeContextMessages = storeContextMessages;
    this.storeContextFrom =
        storeContextFrom == null ? null : Set.copyOf(validatedSources(storeContextFrom));
    this.storeOutputs = storeOutputs;
  }

  /**
   * Returns the upstream default policy: load stored history, store the caller's input and the
   * run's output, and leave other providers' context messages to those providers.
   */
  public static HistoryPolicy defaults() {
    return builder().build();
  }

  public static Builder builder() {
    return new Builder();
  }

  public boolean loadMessages() {
    return loadMessages;
  }

  public boolean storeInputs() {
    return storeInputs;
  }

  public boolean storeContextMessages() {
    return storeContextMessages;
  }

  public Set<String> storeContextFrom() {
    return storeContextFrom;
  }

  public boolean storeOutputs() {
    return storeOutputs;
  }

  private static Set<String> validatedSources(Collection<String> sources) {
    if (sources.isEmpty()) {
      throw new IllegalArgumentException(
          "storeContextFrom must not be empty; use storeContextMessages(false)");
    }
    Set<String> normalized = new LinkedHashSet<>();
    for (String source : sources) {
      Objects.requireNonNull(source, "storeContextFrom must not contain null entries");
      if (source.isBlank()) {
        throw new IllegalArgumentException("storeContextFrom must not contain blank entries");
      }
      normalized.add(source);
    }
    return normalized;
  }

  /** Configures a {@link HistoryPolicy}; every flag defaults to the {@link #defaults()} value. */
  public static final class Builder {

    private boolean loadMessages = true;
    private boolean storeInputs = true;
    private boolean storeContextMessages;
    private Set<String> storeContextFrom;
    private boolean storeOutputs = true;

    private Builder() {}

    /** Sets whether the provider loads stored history before the run's first model call. */
    public Builder loadMessages(boolean value) {
      this.loadMessages = value;
      return this;
    }

    /** Sets whether the provider stores the caller's input messages after a successful run. */
    public Builder storeInputs(boolean value) {
      this.storeInputs = value;
      return this;
    }

    /** Sets whether the provider stores context messages other providers contributed. */
    public Builder storeContextMessages(boolean value) {
      this.storeContextMessages = value;
      return this;
    }

    /**
     * Restricts stored context to the given source ids, replacing any previously configured
     * selection.
     *
     * @throws IllegalArgumentException if no source id is given, or one of them is blank; storing
     *     no context at all is configured with {@code storeContextMessages(false)}, and storing
     *     every other source is configured with {@link #storeContextFromAnySource()}
     * @throws NullPointerException if {@code sourceIds} is {@code null} or contains a {@code null}
     */
    public Builder storeContextFrom(String... sourceIds) {
      Objects.requireNonNull(sourceIds, "storeContextFrom must not be null");
      return storeContextFrom(Arrays.asList(sourceIds));
    }

    /**
     * Restricts stored context to the given source ids, replacing any previously configured
     * selection.
     *
     * @throws IllegalArgumentException if {@code sourceIds} is empty, or contains a blank entry;
     *     storing no context at all is configured with {@code storeContextMessages(false)}, and
     *     storing every other source is configured with {@link #storeContextFromAnySource()}
     * @throws NullPointerException if {@code sourceIds} is {@code null} or contains a {@code null}
     */
    public Builder storeContextFrom(Collection<String> sourceIds) {
      Objects.requireNonNull(sourceIds, "storeContextFrom must not be null");
      this.storeContextFrom = Set.copyOf(validatedSources(sourceIds));
      return this;
    }

    /**
     * Removes any configured source filter, so every context source except the provider's own is
     * stored when {@link #storeContextMessages(boolean)} is enabled. This is the default, and the
     * way to express "no filter" without a {@code null} cast at the call site.
     */
    public Builder storeContextFromAnySource() {
      this.storeContextFrom = null;
      return this;
    }

    /** Sets whether the provider stores the run response's messages. */
    public Builder storeOutputs(boolean value) {
      this.storeOutputs = value;
      return this;
    }

    public HistoryPolicy build() {
      return new HistoryPolicy(
          loadMessages, storeInputs, storeContextMessages, storeContextFrom, storeOutputs);
    }
  }

  @Override
  public boolean equals(Object other) {
    if (!(other instanceof HistoryPolicy that)) {
      return false;
    }
    return loadMessages == that.loadMessages
        && storeInputs == that.storeInputs
        && storeContextMessages == that.storeContextMessages
        && storeOutputs == that.storeOutputs
        && Objects.equals(storeContextFrom, that.storeContextFrom);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        loadMessages, storeInputs, storeContextMessages, storeContextFrom, storeOutputs);
  }

  @Override
  public String toString() {
    return "HistoryPolicy[loadMessages="
        + loadMessages
        + ", storeInputs="
        + storeInputs
        + ", storeContextMessages="
        + storeContextMessages
        + ", storeContextFrom="
        + storeContextFrom
        + ", storeOutputs="
        + storeOutputs
        + "]";
  }
}
