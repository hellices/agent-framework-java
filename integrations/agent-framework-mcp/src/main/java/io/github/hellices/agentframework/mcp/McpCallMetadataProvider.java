package io.github.hellices.agentframework.mcp;

import io.github.hellices.agentframework.api.tool.ToolContext;
import io.github.hellices.agentframework.api.value.JsonObject;

/**
 * Supplies protocol level request metadata for a single MCP tool call.
 *
 * <p>The returned entries travel in the {@code _meta} field of the outgoing {@code tools/call}
 * request. They never become tool arguments, because arguments are governed by the input schema the
 * server published and a server is entitled to reject anything the schema does not declare.
 *
 * <p>The provider is the only place where runtime execution state reaches the wire. Nothing from
 * {@link ToolContext} is forwarded unless an implementation of this interface selects it, so a host
 * decides deliberately which correlation identifiers or tenant markers leave the process.
 *
 * <p>Implementations run on the caller's invocation path and must be thread safe, must not block,
 * and must return promptly.
 */
@FunctionalInterface
public interface McpCallMetadataProvider {

  /**
   * Returns the request metadata for one tool call.
   *
   * @param context execution context of the invoking tool call, never {@code null}
   * @return metadata entries to publish, never {@code null}; an empty object omits {@code _meta}
   */
  JsonObject metadata(ToolContext context);
}
