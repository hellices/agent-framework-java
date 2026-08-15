package io.github.hellices.agentframework.mcp.internal;

import io.github.hellices.agentframework.api.tool.ToolArguments;
import io.github.hellices.agentframework.api.tool.ToolContext;
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
   * @param inputSchema the input schema the server published, never {@code null}
   * @return the accepted names, never {@code null}
   */
  public Set<String> acceptedArgumentNames(Map<String, Object> inputSchema) {
    Set<String> accepted = new LinkedHashSet<>();
    if (inputSchema.get(PROPERTIES) instanceof Map<?, ?> properties) {
      for (Object key : properties.keySet()) {
        if (key instanceof String name) {
          accepted.add(name);
        }
      }
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
   * @throws IllegalArgumentException if the metadata provider returns a blank key
   */
  public McpSchema.CallToolRequest toRequest(
      String remoteName,
      Set<String> acceptedArgumentNames,
      ToolArguments arguments,
      ToolContext context) {
    Map<String, Object> selected = new LinkedHashMap<>();
    for (Map.Entry<String, Object> argument : arguments.values().entrySet()) {
      if (acceptedArgumentNames.contains(argument.getKey())) {
        selected.put(argument.getKey(), argument.getValue());
      }
    }
    return new McpSchema.CallToolRequest(remoteName, selected, requestMetadata(context));
  }

  private Map<String, Object> requestMetadata(ToolContext context) {
    Map<String, Object> metadata = options.callMetadataProvider().metadata(context);
    if (metadata == null) {
      throw new IllegalStateException("callMetadataProvider must not return null metadata");
    }
    if (metadata.isEmpty()) {
      return null;
    }
    Map<String, Object> copy = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : metadata.entrySet()) {
      String key = entry.getKey();
      if (key == null || key.isBlank()) {
        throw new IllegalArgumentException("call metadata must not hold a blank key");
      }
      copy.put(key, entry.getValue());
    }
    return copy;
  }
}
