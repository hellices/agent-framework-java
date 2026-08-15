package io.github.hellices.agentframework.mcp.internal;

import io.github.hellices.agentframework.api.message.Content;
import io.github.hellices.agentframework.api.message.TextContent;
import io.github.hellices.agentframework.api.tool.ToolResult;
import io.github.hellices.agentframework.mcp.McpPayloadContent;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps an MCP {@code tools/call} result onto a framework tool result.
 *
 * <p>Content order is preserved because a server orders its content deliberately. Text becomes core
 * text content, and every other protocol variant becomes {@link McpPayloadContent} carrying the
 * server's content type, its descriptive fields, and the untouched SDK value, so no payload is
 * silently dropped and none is misrepresented as text. Structured content and result metadata are
 * appended as one trailing payload rather than folded into the text, because they describe the
 * result rather than being part of it.
 */
public final class McpResultMapper {

  private static final String RESULT_PAYLOAD_TYPE = "result";
  private static final String META = "_meta";

  /**
   * Maps a call result.
   *
   * @param localName local name of the tool that was called, never {@code null}
   * @param result the result the server returned, never {@code null}
   * @return the framework result, never {@code null}
   * @throws IllegalStateException if the result or one of its content entries is {@code null}
   */
  public ToolResult toToolResult(String localName, McpSchema.CallToolResult result) {
    if (result == null) {
      throw new IllegalStateException("MCP server returned no result for tool '" + localName + "'");
    }
    List<Content> content = new ArrayList<>();
    for (McpSchema.Content item : result.content()) {
      if (item == null) {
        throw new IllegalStateException(
            "MCP server returned a null content entry for tool '" + localName + "'");
      }
      content.add(toContent(item));
    }
    Content summary = resultPayload(result);
    if (summary != null) {
      content.add(summary);
    }
    return new ToolResult(content, Boolean.TRUE.equals(result.isError()));
  }

  private static Content toContent(McpSchema.Content item) {
    Map<String, Object> properties = new LinkedHashMap<>();
    putMeta(properties, item.meta());
    if (item instanceof McpSchema.TextContent text) {
      return new TextContent(text.text(), properties, text);
    }
    if (item instanceof McpSchema.ImageContent image) {
      properties.put("mimeType", image.mimeType());
      properties.put("data", image.data());
    } else if (item instanceof McpSchema.AudioContent audio) {
      properties.put("mimeType", audio.mimeType());
      properties.put("data", audio.data());
    } else if (item instanceof McpSchema.EmbeddedResource resource) {
      properties.put("uri", resource.resource().uri());
      putIfPresent(properties, "mimeType", resource.resource().mimeType());
    } else if (item instanceof McpSchema.ResourceLink link) {
      putIfPresent(properties, "uri", link.uri());
      putIfPresent(properties, "name", link.name());
      putIfPresent(properties, "mimeType", link.mimeType());
      putIfPresent(properties, "size", link.size());
    }
    return new McpPayloadContent(item.type(), properties, item);
  }

  private static Content resultPayload(McpSchema.CallToolResult result) {
    Map<String, Object> properties = new LinkedHashMap<>();
    putIfPresent(properties, "structuredContent", result.structuredContent());
    putMeta(properties, result.meta());
    return properties.isEmpty()
        ? null
        : new McpPayloadContent(RESULT_PAYLOAD_TYPE, properties, result);
  }

  private static void putMeta(Map<String, Object> properties, Map<String, Object> meta) {
    if (meta != null && !meta.isEmpty()) {
      properties.put(META, Collections.unmodifiableMap(new LinkedHashMap<>(meta)));
    }
  }

  private static void putIfPresent(Map<String, Object> properties, String key, Object value) {
    // Every descriptive field is optional here even when the protocol calls it required: the SDK
    // substitutes empty values for most missing fields during deserialization but binds
    // ResourceLink straight through its canonical constructor, so a server that omits a field
    // yields null. Dropping the absent key keeps one malformed entry from failing the whole result.
    if (value != null) {
      properties.put(key, value);
    }
  }
}
