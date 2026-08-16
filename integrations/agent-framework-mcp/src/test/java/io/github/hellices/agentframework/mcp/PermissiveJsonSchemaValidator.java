package io.github.hellices.agentframework.mcp;

import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import java.util.Map;

/**
 * Schema validator that accepts everything.
 *
 * <p>The SDK client resolves its default validator through a service loader that only the JSON
 * modules provide. Supplying this one keeps the integration tests focused on the adapter and free
 * of a JSON binding dependency, and it validates nothing the adapter is responsible for.
 */
final class PermissiveJsonSchemaValidator implements JsonSchemaValidator {

  @Override
  public ValidationResponse validate(Map<String, Object> schema, Object structuredContent) {
    return ValidationResponse.asValid("{}");
  }

  @Override
  public ValidationResponse validateSchema(Map<String, Object> schema) {
    return ValidationResponse.asValid("{}");
  }
}
