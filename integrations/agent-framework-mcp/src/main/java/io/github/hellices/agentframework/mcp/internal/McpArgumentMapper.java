package io.github.hellices.agentframework.mcp.internal;

import io.github.hellices.agentframework.api.tool.ToolArguments;
import io.github.hellices.agentframework.api.tool.ToolContext;
import io.github.hellices.agentframework.api.value.JsonNull;
import io.github.hellices.agentframework.api.value.JsonObject;
import io.github.hellices.agentframework.api.value.JsonValue;
import io.github.hellices.agentframework.api.value.JsonValues;
import io.github.hellices.agentframework.mcp.McpToolAdapterOptions;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Builds an MCP {@code tools/call} request from framework arguments and execution context.
 *
 * <p>Two separations are enforced here. Arguments carry only what the server's input schema
 * declares plus the names a host explicitly allowed, so a model cannot smuggle extra fields into a
 * call that the server would reject or, worse, silently honour. Execution context reaches the wire
 * only through the configured metadata provider and only as {@code _meta}, so runtime attributes
 * such as conversation identifiers never appear as tool arguments.
 */
public final class McpArgumentMapper {

  private static final String PROPERTIES = "properties";

  private final McpToolAdapterOptions options;

  /**
   * Creates an argument mapper.
   *
   * @param options adapter options, never {@code null}
   */
  public McpArgumentMapper(McpToolAdapterOptions options) {
    this.options = options;
  }

  /**
   * Returns the argument names accepted for a tool.
   *
   * <p>Only a literal top level {@code properties} map is read. A schema that declares its shape
   * through {@code $ref}, {@code allOf}, {@code anyOf}, or {@code additionalProperties} alone
   * therefore declares no property here, and a call for such a tool carries only the names a host
   * allowed explicitly through {@link McpToolAdapterOptions#additionalArgumentNames()}. Composing
   * schemas is deliberately not attempted: a partial composition would accept some arguments and
   * silently drop others, which is harder to diagnose than an empty set that a host can override.
   *
   * @param inputSchema the input schema the server published, never {@code null}
   * @return the accepted names, never {@code null}
   */
  public Set<String> acceptedArgumentNames(JsonObject inputSchema) {
    Set<String> accepted = new LinkedHashSet<>();
    JsonValue properties = inputSchema.get(PROPERTIES).orElse(null);
    if (properties instanceof JsonObject propertyMap) {
      accepted.addAll(propertyMap.values().keySet());
    }
    accepted.addAll(options.additionalArgumentNames());
    return accepted;
  }

  /**
   * Builds the call request for one invocation.
   *
   * @param remoteName the tool name as the server published it, never {@code null}
   * @param acceptedArgumentNames argument names this tool accepts, never {@code null}
   * @param arguments arguments produced by the caller, never {@code null}
   * @param context execution context of the invocation, never {@code null}
   * @return the request to send, never {@code null}
   * @throws IllegalStateException if the metadata provider returns {@code null}
   * @throws IllegalArgumentException if the metadata provider returns a blank key, or a JSON null
   *     value
   */
  public McpSchema.CallToolRequest toRequest(
      String remoteName,
      Set<String> acceptedArgumentNames,
      ToolArguments arguments,
      ToolContext context) {
    Map<String, Object> selected = new LinkedHashMap<>();
    for (Map.Entry<String, JsonValue> argument : arguments.values().values().entrySet()) {
      if (acceptedArgumentNames.contains(argument.getKey())) {
        selected.put(argument.getKey(), JsonValues.toJava(argument.getValue()));
      }
    }
    return new McpSchema.CallToolRequest(remoteName, selected, requestMetadata(context));
  }

  private Map<String, Object> requestMetadata(ToolContext context) {
    JsonObject metadata = options.callMetadataProvider().metadata(context);
    if (metadata == null) {
      throw new IllegalStateException("callMetadataProvider must not return null metadata");
    }
    if (metadata.isEmpty()) {
      return null;
    }
    Map<String, Object> copy = new LinkedHashMap<>();
    for (Map.Entry<String, JsonValue> entry : metadata.values().entrySet()) {
      String key = entry.getKey();
      if (key.isBlank()) {
        throw new IllegalArgumentException("call metadata must not hold a blank key");
      }
      if (entry.getValue() instanceof JsonNull) {
        // A JSON null reaches the server as a null the protocol does not define for `_meta`, and it
        // is far more often a provider that failed to resolve a value than a value that is
        // meaningfully absent, so it fails here with the key that produced it.
        throw new IllegalArgumentException(
            "call metadata value for '" + key + "' must not be JsonNull");
      }
      copy.put(key, JsonValues.toJava(entry.getValue()));
    }
    return copy;
  }
}
