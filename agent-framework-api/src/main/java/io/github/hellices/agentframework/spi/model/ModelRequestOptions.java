package io.github.hellices.agentframework.spi.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

public final class ModelRequestOptions {

  private final Double temperature;
  private final Integer maxOutputTokens;
  private final Map<String, Map<String, Object>> providerOptions;

  private ModelRequestOptions(
      Double temperature, Integer maxOutputTokens, Map<String, Map<String, Object>> providerOptions) {
    this.temperature = temperature;
    this.maxOutputTokens = maxOutputTokens;
    this.providerOptions = providerOptions;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static ModelRequestOptions empty() {
    return builder().build();
  }

  public Optional<Double> temperature() {
    return Optional.ofNullable(temperature);
  }

  public OptionalInt maxOutputTokens() {
    return maxOutputTokens == null ? OptionalInt.empty() : OptionalInt.of(maxOutputTokens);
  }

  public Optional<Map<String, Object>> providerOption(String providerId) {
    Objects.requireNonNull(providerId, "providerId must not be null");
    Map<String, Object> values = providerOptions.get(providerId);
    return values == null ? Optional.empty() : Optional.of(Map.copyOf(values));
  }

  public Map<String, Map<String, Object>> providerOptions() {
    return providerOptions;
  }

  public ModelRequestOptions merge(ModelRequestOptions override) {
    Objects.requireNonNull(override, "override must not be null");
    Double mergedTemperature = override.temperature != null ? override.temperature : temperature;
    Integer mergedMaxTokens =
        override.maxOutputTokens != null ? override.maxOutputTokens : maxOutputTokens;
    Map<String, Map<String, Object>> mergedProviderOptions = new LinkedHashMap<>(providerOptions);
    mergedProviderOptions.putAll(override.providerOptions);
    return new ModelRequestOptions(
        mergedTemperature, mergedMaxTokens, immutableProviderOptions(mergedProviderOptions));
  }

  public static final class Builder {
    private Double temperature;
    private Integer maxOutputTokens;
    private final Map<String, Map<String, Object>> providerOptions = new LinkedHashMap<>();

    private Builder() {}

    public Builder temperature(double value) {
      if (value < 0.0 || value > 2.0) {
        throw new IllegalArgumentException("temperature must be between 0.0 and 2.0");
      }
      this.temperature = value;
      return this;
    }

    public Builder maxOutputTokens(int value) {
      if (value <= 0) {
        throw new IllegalArgumentException("maxOutputTokens must be greater than 0");
      }
      this.maxOutputTokens = value;
      return this;
    }

    public Builder providerOption(ModelProviderOption option) {
      Objects.requireNonNull(option, "option must not be null");
      providerOptions.put(option.providerId(), option.values());
      return this;
    }

    public ModelRequestOptions build() {
      return new ModelRequestOptions(
          temperature, maxOutputTokens, immutableProviderOptions(providerOptions));
    }
  }

  private static Map<String, Map<String, Object>> immutableProviderOptions(
      Map<String, Map<String, Object>> source) {
    Map<String, Map<String, Object>> normalized = new LinkedHashMap<>();
    for (Map.Entry<String, Map<String, Object>> entry : source.entrySet()) {
      normalized.put(entry.getKey(), Map.copyOf(entry.getValue()));
    }
    return Map.copyOf(normalized);
  }
}
