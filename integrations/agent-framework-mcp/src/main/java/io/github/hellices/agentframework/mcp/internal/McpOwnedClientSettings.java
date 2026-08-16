package io.github.hellices.agentframework.mcp.internal;

import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import java.time.Duration;

/**
 * Settings applied to every client generation the owned lifecycle builds.
 *
 * <p>The schema validator is required rather than defaulted. The SDK resolves its default through a
 * service loader that only the JSON binding modules provide, and this module deliberately does not
 * depend on one, so a missing validator has to fail where the caller can fix it.
 */
public final class McpOwnedClientSettings {

  private final JsonSchemaValidator schemaValidator;
  private final Duration requestTimeout;
  private final Duration initializationTimeout;

  /**
   * Creates settings.
   *
   * @param schemaValidator validator applied to tool output schemas, never {@code null}
   * @param requestTimeout timeout for a single request, must be positive
   * @param initializationTimeout timeout for the handshake, must be positive
   * @throws IllegalArgumentException if an argument is {@code null} or a timeout is not positive
   */
  public McpOwnedClientSettings(
      JsonSchemaValidator schemaValidator,
      Duration requestTimeout,
      Duration initializationTimeout) {
    if (schemaValidator == null) {
      throw new IllegalArgumentException("schemaValidator must not be null");
    }
    this.requestTimeout = requirePositive(requestTimeout, "requestTimeout");
    this.initializationTimeout = requirePositive(initializationTimeout, "initializationTimeout");
    this.schemaValidator = schemaValidator;
  }

  /**
   * Returns the validator applied to tool output schemas.
   *
   * @return the validator, never {@code null}
   */
  public JsonSchemaValidator schemaValidator() {
    return schemaValidator;
  }

  /**
   * Returns the timeout for a single request.
   *
   * @return the timeout, never {@code null}
   */
  public Duration requestTimeout() {
    return requestTimeout;
  }

  /**
   * Returns the timeout for the handshake.
   *
   * @return the timeout, never {@code null}
   */
  public Duration initializationTimeout() {
    return initializationTimeout;
  }

  private static Duration requirePositive(Duration value, String name) {
    if (value == null) {
      throw new IllegalArgumentException(name + " must not be null");
    }
    if (value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(name + " must be positive");
    }
    return value;
  }
}
