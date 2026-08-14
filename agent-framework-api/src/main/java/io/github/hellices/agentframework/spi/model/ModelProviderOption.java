package io.github.hellices.agentframework.spi.model;

import java.util.Map;
import java.util.Objects;

public record ModelProviderOption(String providerId, Map<String, Object> values) {

  public ModelProviderOption {
    Objects.requireNonNull(providerId, "providerId must not be null");
    if (providerId.isBlank()) {
      throw new IllegalArgumentException("providerId must not be blank");
    }
    values = values == null ? Map.of() : Map.copyOf(values);
  }

  public static ModelProviderOption of(String providerId, Map<String, Object> values) {
    return new ModelProviderOption(providerId, values);
  }
}
