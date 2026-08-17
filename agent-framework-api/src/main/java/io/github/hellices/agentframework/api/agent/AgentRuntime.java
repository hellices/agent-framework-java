package io.github.hellices.agentframework.api.agent;

import io.github.hellices.agentframework.api.context.ContextAttributes;
import io.github.hellices.agentframework.api.tool.ToolBinding;
import io.github.hellices.agentframework.api.tool.ToolDefinition;
import io.github.hellices.agentframework.spi.model.ModelClient;
import io.github.hellices.agentframework.spi.session.ContextProvider;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class AgentRuntime {

  private final ModelClient modelClient;
  private final List<ToolBinding> toolBindings;
  private final List<ContextProvider> contextProviders;
  private final ContextAttributes attributes;

  private AgentRuntime(Builder builder) {
    this.modelClient = Objects.requireNonNull(builder.modelClient, "modelClient must not be null");
    List<ToolBinding> normalizedBindings = List.copyOf(builder.toolBindings);
    validateUniqueBindingNames(normalizedBindings);
    this.toolBindings = normalizedBindings;
    List<ContextProvider> normalizedProviders = List.copyOf(builder.contextProviders);
    validateContextProviders(normalizedProviders);
    this.contextProviders = normalizedProviders;
    this.attributes = builder.attributes == null ? ContextAttributes.empty() : builder.attributes;
  }

  public static Builder builder() {
    return new Builder();
  }

  public ModelClient modelClient() {
    return modelClient;
  }

  public List<ToolBinding> toolBindings() {
    return toolBindings;
  }

  public List<ContextProvider> contextProviders() {
    return contextProviders;
  }

  public ContextAttributes attributes() {
    return attributes;
  }

  public void validate(AgentDefinition definition) {
    Objects.requireNonNull(definition, "definition must not be null");
    Set<String> declaredToolNames = new LinkedHashSet<>();
    for (ToolDefinition tool : definition.tools()) {
      declaredToolNames.add(tool.name());
    }
    for (ToolBinding toolBinding : toolBindings) {
      if (!declaredToolNames.contains(toolBinding.toolName())) {
        throw new IllegalArgumentException(
            "tool binding has no matching declaration: " + toolBinding.toolName());
      }
    }
  }

  private static void validateUniqueBindingNames(List<ToolBinding> toolBindings) {
    Set<String> names = new LinkedHashSet<>();
    for (ToolBinding toolBinding : toolBindings) {
      if (!names.add(toolBinding.toolName())) {
        throw new IllegalArgumentException(
            "duplicate tool binding name: " + toolBinding.toolName());
      }
    }
  }

  private static void validateContextProviders(List<ContextProvider> contextProviders) {
    Set<String> sourceIds = new LinkedHashSet<>();
    for (ContextProvider contextProvider : contextProviders) {
      String sourceId = contextProvider.sourceId();
      if (sourceId == null || sourceId.isBlank()) {
        throw new IllegalArgumentException("context provider sourceId must not be blank");
      }
      if (!sourceIds.add(sourceId)) {
        throw new IllegalArgumentException("duplicate context provider sourceId: " + sourceId);
      }
    }
  }

  public static final class Builder {
    private ModelClient modelClient;
    private List<ToolBinding> toolBindings = new ArrayList<>();
    private List<ContextProvider> contextProviders = new ArrayList<>();
    private ContextAttributes attributes = ContextAttributes.empty();

    private Builder() {}

    public Builder modelClient(ModelClient modelClient) {
      this.modelClient = Objects.requireNonNull(modelClient, "modelClient must not be null");
      return this;
    }

    public Builder toolBinding(ToolBinding toolBinding) {
      toolBindings.add(Objects.requireNonNull(toolBinding, "toolBinding must not be null"));
      return this;
    }

    public Builder toolBindings(List<ToolBinding> toolBindings) {
      Objects.requireNonNull(toolBindings, "toolBindings must not be null");
      this.toolBindings = new ArrayList<>(toolBindings.size());
      for (ToolBinding toolBinding : toolBindings) {
        this.toolBindings.add(Objects.requireNonNull(toolBinding, "toolBinding must not be null"));
      }
      return this;
    }

    public Builder contextProvider(ContextProvider contextProvider) {
      contextProviders.add(
          Objects.requireNonNull(contextProvider, "contextProvider must not be null"));
      return this;
    }

    public Builder contextProviders(List<ContextProvider> contextProviders) {
      Objects.requireNonNull(contextProviders, "contextProviders must not be null");
      this.contextProviders = new ArrayList<>(contextProviders.size());
      for (ContextProvider contextProvider : contextProviders) {
        this.contextProviders.add(
            Objects.requireNonNull(contextProvider, "contextProvider must not be null"));
      }
      return this;
    }

    public Builder attributes(ContextAttributes attributes) {
      this.attributes = Objects.requireNonNull(attributes, "attributes must not be null");
      return this;
    }

    public AgentRuntime build() {
      return new AgentRuntime(this);
    }
  }
}
