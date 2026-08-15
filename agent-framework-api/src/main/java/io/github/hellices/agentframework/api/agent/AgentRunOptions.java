package io.github.hellices.agentframework.api.agent;

import io.github.hellices.agentframework.spi.model.ModelClient;
import io.github.hellices.agentframework.spi.model.ModelClientFactory;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class AgentRunOptions {

  private final Map<String, Object> attributes;
  private final ModelClientFactory modelClientFactory;
  private final String continuationToken;

  public AgentRunOptions() {
    this(Map.of(), null, null);
  }

  public AgentRunOptions(Map<String, Object> attributes) {
    this(attributes, null, null);
  }

  private AgentRunOptions(
      Map<String, Object> attributes,
      ModelClientFactory modelClientFactory,
      String continuationToken) {
    this.attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    this.modelClientFactory = modelClientFactory;
    this.continuationToken = continuationToken;
  }

  public static Builder builder() {
    return new Builder();
  }

  public Map<String, Object> attributes() {
    return attributes;
  }

  public Optional<ModelClientFactory> modelClientFactory() {
    return Optional.ofNullable(modelClientFactory);
  }

  public Optional<String> continuationToken() {
    return Optional.ofNullable(continuationToken);
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
    private Map<String, Object> attributes = Map.of();
    private ModelClientFactory modelClientFactory;
    private String continuationToken;

    private Builder() {}

    public Builder attributes(Map<String, Object> attributes) {
      this.attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
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

    public AgentRunOptions build() {
      return new AgentRunOptions(attributes, modelClientFactory, continuationToken);
    }
  }
}
