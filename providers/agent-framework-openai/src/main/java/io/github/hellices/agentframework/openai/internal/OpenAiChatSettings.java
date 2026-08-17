package io.github.hellices.agentframework.openai.internal;

import java.time.Duration;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The adapter-owned request settings one {@code OpenAiChatModelClient} applies to every call.
 *
 * <p>These are defaults, not overrides: a value supplied on the {@code ModelRequest} wins. They
 * exist because the engine cannot carry request options to a provider yet, so without them a model
 * name could not reach the wire at all.
 */
public final class OpenAiChatSettings {

  private final String model;
  private final Double temperature;
  private final Integer maxOutputTokens;
  private final Duration requestTimeout;

  /**
   * Creates validated settings.
   *
   * @param model the model name sent on every request, never blank
   * @param temperature the default temperature between 0.0 and 2.0, or {@code null}
   * @param maxOutputTokens the default output token limit above zero, or {@code null}
   * @param requestTimeout the per-request timeout, never {@code null} and always positive
   * @throws IllegalArgumentException if any value is outside its contract
   */
  public OpenAiChatSettings(
      String model, Double temperature, Integer maxOutputTokens, Duration requestTimeout) {
    if (model == null || model.isBlank()) {
      throw new IllegalArgumentException("model must not be blank");
    }
    if (temperature != null
        && (!Double.isFinite(temperature) || temperature < 0.0 || temperature > 2.0)) {
      throw new IllegalArgumentException("temperature must be between 0.0 and 2.0");
    }
    if (maxOutputTokens != null && maxOutputTokens <= 0) {
      throw new IllegalArgumentException("maxOutputTokens must be greater than 0");
    }
    if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
      throw new IllegalArgumentException("requestTimeout must be positive");
    }
    this.model = model;
    this.temperature = temperature;
    this.maxOutputTokens = maxOutputTokens;
    this.requestTimeout = requestTimeout;
  }

  /**
   * Returns the model name sent on every request.
   *
   * @return the model name, never blank
   */
  public String model() {
    return model;
  }

  /**
   * Returns the default temperature.
   *
   * @return the temperature, or empty when the request decides
   */
  public Optional<Double> temperature() {
    return Optional.ofNullable(temperature);
  }

  /**
   * Returns the default output token limit.
   *
   * @return the limit, or empty when the request decides
   */
  public OptionalInt maxOutputTokens() {
    return maxOutputTokens == null ? OptionalInt.empty() : OptionalInt.of(maxOutputTokens);
  }

  /**
   * Returns the per-request timeout.
   *
   * <p>Cancellation stops the framework from waiting but does not abort the in-flight HTTP request,
   * so this is what bounds the work the framework has already stopped waiting for.
   *
   * @return the timeout, never {@code null} and always positive
   */
  public Duration requestTimeout() {
    return requestTimeout;
  }
}
