package io.github.hellices.agentframework.spi.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

public final class ModelRequestOptions {

  private final Double temperature;
  private final Integer maxOutputTokens;
  private final Map<Class<? extends ModelProviderOption>, ModelProviderOption> providerOptions;

  private ModelRequestOptions(
      Double temperature,
      Integer maxOutputTokens,
      Map<Class<? extends ModelProviderOption>, ModelProviderOption> providerOptions) {
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

  public <T extends ModelProviderOption> Optional<T> providerOption(Class<T> optionType) {
    Objects.requireNonNull(optionType, "optionType must not be null");
    ModelProviderOption option = providerOptions.get(optionType);
    if (option == null) {
      return Optional.empty();
    }
    return Optional.of(optionType.cast(option));
  }

  public Map<Class<? extends ModelProviderOption>, ModelProviderOption> providerOptions() {
    return immutableProviderOptions(providerOptions);
  }

  public ModelRequestOptions merge(ModelRequestOptions override) {
    Objects.requireNonNull(override, "override must not be null");
    Double mergedTemperature = override.temperature != null ? override.temperature : temperature;
    Integer mergedMaxTokens =
        override.maxOutputTokens != null ? override.maxOutputTokens : maxOutputTokens;
    Map<Class<? extends ModelProviderOption>, ModelProviderOption> mergedProviderOptions =
        new LinkedHashMap<>(providerOptions);
    mergedProviderOptions.putAll(override.providerOptions);
    return new ModelRequestOptions(
        mergedTemperature, mergedMaxTokens, immutableProviderOptions(mergedProviderOptions));
  }

  public static final class Builder {
    private Double temperature;
    private Integer maxOutputTokens;
    private final Map<Class<? extends ModelProviderOption>, ModelProviderOption> providerOptions =
        new LinkedHashMap<>();

    private Builder() {}

    public Builder temperature(double value) {
      if (!Double.isFinite(value) || value < 0.0 || value > 2.0) {
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
      String providerId = option.providerId();
      Objects.requireNonNull(providerId, "providerId must not be null");
      if (providerId.isBlank()) {
        throw new IllegalArgumentException("providerId must not be blank");
      }
      Class<? extends ModelProviderOption> optionType = option.getClass();
      if (providerOptions.containsKey(optionType)) {
        throw new IllegalArgumentException(
            "provider option type already configured: " + optionType.getName());
      }
      providerOptions.put(optionType, option);
      return this;
    }

    public ModelRequestOptions build() {
      return new ModelRequestOptions(
          temperature, maxOutputTokens, immutableProviderOptions(providerOptions));
    }
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof ModelRequestOptions that)) {
      return false;
    }
    return Objects.equals(temperature, that.temperature)
        && Objects.equals(maxOutputTokens, that.maxOutputTokens)
        && providerOptions.equals(that.providerOptions);
  }

  @Override
  public int hashCode() {
    return Objects.hash(temperature, maxOutputTokens, providerOptions);
  }

  @Override
  public String toString() {
    return "ModelRequestOptions[temperature="
        + temperature
        + ", maxOutputTokens="
        + maxOutputTokens
        + ", providerOptions="
        + providerOptions
        + "]";
  }

  private static Map<Class<? extends ModelProviderOption>, ModelProviderOption>
      immutableProviderOptions(
          Map<Class<? extends ModelProviderOption>, ModelProviderOption> source) {
    return Map.copyOf(source);
  }
}
