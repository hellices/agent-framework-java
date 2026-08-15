package io.github.hellices.agentframework.spi.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class ModelCatalog {

  private final Map<String, ModelClient> clients;
  private final String defaultModelName;

  private ModelCatalog(Map<String, ModelClient> clients, String defaultModelName) {
    this.clients = Map.copyOf(clients);
    this.defaultModelName = defaultModelName;
  }

  public static Builder builder() {
    return new Builder();
  }

  public ModelClient resolve(String modelName) {
    String name = requireName(modelName, "modelName");
    ModelClient client = clients.get(name);
    if (client == null) {
      throw new IllegalArgumentException("unknown model: " + name);
    }
    return client;
  }

  public ModelClient defaultClient() {
    if (defaultModelName == null) {
      throw new IllegalStateException(
          "no default model is configured; configure defaultModel(name) or resolve a named model");
    }
    return clients.get(defaultModelName);
  }

  public Optional<String> defaultModelName() {
    return Optional.ofNullable(defaultModelName);
  }

  public Map<String, ModelClient> clients() {
    return clients;
  }

  public static final class Builder {
    private final Map<String, ModelClient> clients = new LinkedHashMap<>();
    private String defaultModelName;

    private Builder() {}

    public Builder add(String modelName, ModelClient client) {
      String name = requireName(modelName, "modelName");
      ModelClient value = Objects.requireNonNull(client, "modelClient must not be null");
      if (clients.putIfAbsent(name, value) != null) {
        throw new IllegalArgumentException("duplicate model name: " + name);
      }
      return this;
    }

    public Builder defaultModel(String modelName) {
      this.defaultModelName = requireName(modelName, "defaultModelName");
      return this;
    }

    public ModelCatalog build() {
      if (defaultModelName != null && !clients.containsKey(defaultModelName)) {
        throw new IllegalStateException("default model is not registered: " + defaultModelName);
      }
      return new ModelCatalog(clients, defaultModelName);
    }
  }

  private static String requireName(String value, String label) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(label + " must not be blank");
    }
    return value;
  }
}
