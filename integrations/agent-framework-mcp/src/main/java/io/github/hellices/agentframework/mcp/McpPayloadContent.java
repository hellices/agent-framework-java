package io.github.hellices.agentframework.mcp;

import io.github.hellices.agentframework.api.message.ExtensionContent;
import io.github.hellices.agentframework.api.value.JsonObject;

/**
 * Tool result content produced by an MCP server that the core content model does not describe.
 *
 * <p>An MCP server may answer with images, audio, embedded resources, or resource links. Those
 * variants have no core counterpart, so mapping them onto {@link
 * io.github.hellices.agentframework.api.message.TextContent} would either invent text or discard
 * the payload. This type keeps the server's own content type in {@link #payloadType()}, keeps the
 * descriptive fields in {@link #additionalProperties()}, and keeps the untouched SDK value in
 * {@link #rawRepresentation()} so a caller that knows the protocol can read it back.
 *
 * <p>Content of this kind is adapter owned. Persisting a message that carries it requires a
 * registered content codec, because the session format only defines the core content variants.
 */
public final class McpPayloadContent extends ExtensionContent {

  private static final String TYPE_PREFIX = "mcp_";

  private final String payloadType;

  /**
   * Creates MCP payload content.
   *
   * @param payloadType content type reported by the server, such as {@code image}, never {@code
   *     null} or blank
   * @param additionalProperties descriptive fields of the payload, may be {@code null}
   * @param rawRepresentation SDK value this content was mapped from, may be {@code null}
   * @throws IllegalArgumentException if the payload type is {@code null} or blank
   */
  public McpPayloadContent(
      String payloadType, JsonObject additionalProperties, Object rawRepresentation) {
    super(additionalProperties, rawRepresentation);
    if (payloadType == null || payloadType.isBlank()) {
      throw new IllegalArgumentException("payloadType must not be null or blank");
    }
    this.payloadType = payloadType;
  }

  /**
   * Returns the content type the MCP server reported, such as {@code image} or {@code resource}.
   *
   * @return the server reported content type, never {@code null} or blank
   */
  public String payloadType() {
    return payloadType;
  }

  @Override
  public String type() {
    return TYPE_PREFIX + payloadType;
  }
}
