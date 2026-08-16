package io.github.hellices.agentframework.mcp;

import io.github.hellices.agentframework.mcp.internal.McpClientTransportFactory;
import io.modelcontextprotocol.spec.McpClientTransport;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Hands out scripted in-memory transports in order and records how many were asked for.
 *
 * <p>The creation count is the observable a test uses to tell one generation from many: an owner
 * that reconnects twice, or that silently connects when it should have refused, asks for a
 * transport it was not scripted to get and fails loudly instead of passing quietly.
 */
final class ScriptedMcpTransportFactory implements McpClientTransportFactory {

  private final Deque<InMemoryMcpTransport> scripted = new ArrayDeque<>();
  private final List<InMemoryMcpTransport> created = new ArrayList<>();

  ScriptedMcpTransportFactory(InMemoryMcpTransport... transports) {
    scripted.addAll(List.of(transports));
  }

  @Override
  public McpClientTransport create() {
    if (scripted.isEmpty()) {
      throw new IllegalStateException(
          "the owned lifecycle asked for transport "
              + (created.size() + 1)
              + " but only "
              + created.size()
              + " were scripted");
    }
    InMemoryMcpTransport transport = scripted.removeFirst();
    created.add(transport);
    return transport;
  }

  int createdCount() {
    return created.size();
  }

  InMemoryMcpTransport created(int index) {
    return created.get(index);
  }
}
