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
 *
 * @param loadMessages whether {@link HistoryProvider#beforeRun} loads stored history into the run
 * @param storeInputs whether {@link HistoryProvider#afterRun} stores the caller's input messages
 * @param storeContextMessages whether {@code afterRun} stores context messages other providers
 *     contributed to the run
 * @param storeContextFrom the context source ids to store when {@code storeContextMessages} is
 *     enabled; empty means "every source except the provider's own", so a provider never re-stores
 *     the history it just loaded. Order is not significant. The set is ignored entirely when {@code
 *     storeContextMessages} is disabled.
 * @param storeOutputs whether {@code afterRun} stores the run response's messages
 */
public record HistoryPolicy(
    boolean loadMessages,
    boolean storeInputs,
    boolean storeContextMessages,
    Set<String> storeContextFrom,
    boolean storeOutputs) {

  /**
   * Normalizes the source filter: {@code null} means "no filter", and a source id that carries no
   * provenance ({@code null} or blank) is rejected rather than silently dropped, because it would
   * otherwise match nothing and quietly narrow what the provider stores.
   */
  public HistoryPolicy {
    storeContextFrom = Set.copyOf(validatedSources(storeContextFrom));
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

  private static Set<String> validatedSources(Collection<String> sources) {
    if (sources == null) {
      return Set.of();
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
    private Set<String> storeContextFrom = Set.of();
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
     * selection. An empty selection stores every context source except the provider's own.
     */
    public Builder storeContextFrom(String... sourceIds) {
      Objects.requireNonNull(sourceIds, "storeContextFrom must not be null");
      return storeContextFrom(Arrays.asList(sourceIds));
    }

    /**
     * Restricts stored context to the given source ids, replacing any previously configured
     * selection. An empty selection stores every context source except the provider's own.
     */
    public Builder storeContextFrom(Collection<String> sourceIds) {
      Objects.requireNonNull(sourceIds, "storeContextFrom must not be null");
      this.storeContextFrom = Set.copyOf(validatedSources(sourceIds));
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
}
