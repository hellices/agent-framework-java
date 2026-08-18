package io.github.hellices.agentframework.api.agent;

import io.github.hellices.agentframework.api.context.ContextAttributes;
import io.github.hellices.agentframework.spi.model.ModelClient;
import io.github.hellices.agentframework.spi.model.ModelClientFactory;
import java.util.Objects;
import java.util.Optional;

public final class AgentRunOptions {

  /** The default tool loop iteration budget when a caller sets none. */
  public static final int DEFAULT_MAX_TOOL_ITERATIONS = 5;

  private final ContextAttributes attributes;
  private final ModelClientFactory modelClientFactory;
  private final String continuationToken;
  private final int maxToolIterations;

  public AgentRunOptions() {
    this(ContextAttributes.empty(), null, null, DEFAULT_MAX_TOOL_ITERATIONS);
  }

  public AgentRunOptions(ContextAttributes attributes) {
    this(attributes, null, null, DEFAULT_MAX_TOOL_ITERATIONS);
  }

  private AgentRunOptions(
      ContextAttributes attributes,
      ModelClientFactory modelClientFactory,
      String continuationToken,
      int maxToolIterations) {
    this.attributes = attributes == null ? ContextAttributes.empty() : attributes;
    this.modelClientFactory = modelClientFactory;
    this.continuationToken = continuationToken;
    if (maxToolIterations < 1) {
      throw new IllegalArgumentException("maxToolIterations must be greater than 0");
    }
    this.maxToolIterations = maxToolIterations;
  }

  public static Builder builder() {
    return new Builder();
  }

  public ContextAttributes attributes() {
    return attributes;
  }

  public Optional<ModelClientFactory> modelClientFactory() {
    return Optional.ofNullable(modelClientFactory);
  }

  public Optional<String> continuationToken() {
    return Optional.ofNullable(continuationToken);
  }

  public int maxToolIterations() {
    return maxToolIterations;
  }

  public ModelClient resolveModelClient(ModelClient defaultClient) {
    Objects.requireNonNull(defaultClient, "defaultClient must not be null");
    if (modelClientFactory == null) {
      return defaultClient;
    }
    ModelClient transformed = modelClientFactory.create(defaultClient);
    if (transformed == null) {
      throw new IllegalStateException("modelClientFactory must not return null");
    }
    return transformed;
  }

  public static final class Builder {
    private ContextAttributes attributes = ContextAttributes.empty();
    private ModelClientFactory modelClientFactory;
    private String continuationToken;
    private int maxToolIterations = DEFAULT_MAX_TOOL_ITERATIONS;

    private Builder() {}

    public Builder attributes(ContextAttributes attributes) {
      this.attributes = attributes == null ? ContextAttributes.empty() : attributes;
      return this;
    }

    public Builder modelClientFactory(ModelClientFactory modelClientFactory) {
      this.modelClientFactory =
          Objects.requireNonNull(modelClientFactory, "modelClientFactory must not be null");
      return this;
    }

    public Builder continuationToken(String continuationToken) {
      if (continuationToken != null && continuationToken.isBlank()) {
        throw new IllegalArgumentException("continuationToken must not be blank");
      }
      this.continuationToken = continuationToken;
      return this;
    }

    public Builder maxToolIterations(int maxToolIterations) {
      if (maxToolIterations < 1) {
        throw new IllegalArgumentException("maxToolIterations must be greater than 0");
      }
      this.maxToolIterations = maxToolIterations;
      return this;
    }

    public AgentRunOptions build() {
      return new AgentRunOptions(
          attributes, modelClientFactory, continuationToken, maxToolIterations);
    }
  }
}
