package io.github.hellices.agentframework.mcp;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;

/**
 * An {@link McpJsonMapper} that refuses to do anything.
 *
 * <p>The stdio builder requires a mapper because the SDK stdio transport does, but no test in this
 * module ever serializes an MCP message: the in-memory transport passes result objects through. A
 * mapper that throws makes an accidental serialization obvious instead of quietly succeeding.
 */
final class RejectingMcpJsonMapper implements McpJsonMapper {

  @Override
  public <T> T readValue(String content, Class<T> type) {
    throw refuse();
  }

  @Override
  public <T> T readValue(byte[] content, Class<T> type) {
    throw refuse();
  }

  @Override
  public <T> T readValue(String content, TypeRef<T> type) {
    throw refuse();
  }

  @Override
  public <T> T readValue(byte[] content, TypeRef<T> type) {
    throw refuse();
  }

  @Override
  public <T> T convertValue(Object source, Class<T> type) {
    throw refuse();
  }

  @Override
  public <T> T convertValue(Object source, TypeRef<T> type) {
    throw refuse();
  }

  @Override
  public String writeValueAsString(Object value) {
    throw refuse();
  }

  @Override
  public byte[] writeValueAsBytes(Object value) {
    throw refuse();
  }

  private static UnsupportedOperationException refuse() {
    return new UnsupportedOperationException(
        "no test in this module serializes MCP messages; this mapper exists only to satisfy a"
            + " required builder argument");
  }
}
