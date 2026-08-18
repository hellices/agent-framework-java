package io.github.hellices.agentframework.spi.model;

/**
 * The capabilities a {@link ModelClient} advertises so the engine can adapt without probing the
 * provider's concrete type.
 *
 * <p>The only capability today is {@link #serviceManagesHistory()}: whether the model service keeps
 * the conversation history itself. The engine reads it once, when an agent is bound, to decide who
 * owns the conversation — the model service, or a local in-memory history — so the same message is
 * never stored on both sides and replayed twice into one request (AGT-016).
 *
 * <p>This is a value with an open future: it is immutable, two descriptors with the same flags are
 * equal, and every construction goes through {@link #builder()} (or {@link #toBuilder()}). A later
 * capability is added as another builder flag, so a provider that only knows today's flags keeps
 * compiling and every existing caller keeps its behaviour.
 */
public final class ModelCapabilities {

  private final boolean serviceManagesHistory;

  private ModelCapabilities(boolean serviceManagesHistory) {
    this.serviceManagesHistory = serviceManagesHistory;
  }

  /**
   * Returns the descriptor for a provider that keeps no history itself: the local history path owns
   * the conversation. This is what {@link ModelClient#capabilities()} returns by default.
   */
  public static ModelCapabilities defaults() {
    return builder().build();
  }

  public static Builder builder() {
    return new Builder();
  }

  /** Returns a builder seeded with this descriptor's flags, for an evolvable copy-and-change. */
  public Builder toBuilder() {
    return builder().serviceManagesHistory(serviceManagesHistory);
  }

  /**
   * Whether the model service stores the conversation history itself. When {@code true}, the engine
   * never injects a default local history, because doing so would duplicate the stored conversation
   * into the prompt (AGT-016).
   */
  public boolean serviceManagesHistory() {
    return serviceManagesHistory;
  }

  /**
   * Configures a {@link ModelCapabilities}; every flag defaults to the {@link #defaults()} value.
   */
  public static final class Builder {

    private boolean serviceManagesHistory;

    private Builder() {}

    /** Sets whether the model service keeps the conversation history itself. */
    public Builder serviceManagesHistory(boolean value) {
      this.serviceManagesHistory = value;
      return this;
    }

    public ModelCapabilities build() {
      return new ModelCapabilities(serviceManagesHistory);
    }
  }

  @Override
  public boolean equals(Object other) {
    if (!(other instanceof ModelCapabilities that)) {
      return false;
    }
    return serviceManagesHistory == that.serviceManagesHistory;
  }

  @Override
  public int hashCode() {
    return Boolean.hashCode(serviceManagesHistory);
  }

  @Override
  public String toString() {
    return "ModelCapabilities[serviceManagesHistory=" + serviceManagesHistory + "]";
  }
}
