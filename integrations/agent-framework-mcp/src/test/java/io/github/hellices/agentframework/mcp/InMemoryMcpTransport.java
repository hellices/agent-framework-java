package io.github.hellices.agentframework.mcp;

import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import reactor.core.publisher.Mono;

/**
 * In-memory MCP transport that answers requests from a script and records what it was asked.
 *
 * <p>The transport records how often it was closed, which is how a test can show that the adapter
 * leaves a borrowed client untouched: closing the client is what would close this transport.
 *
 * <p>{@link #unmarshalFrom(Object, TypeRef)} returns the scripted result object unchanged, because
 * the results never leave the process, so no JSON mapper implementation is needed on the test
 * runtime.
 */
final class InMemoryMcpTransport implements McpClientTransport {

  private final Map<String, Function<Object, Object>> answers = new LinkedHashMap<>();
  private final List<String> methodsSent = new ArrayList<>();
  private final Map<String, Object> lastRequests = new LinkedHashMap<>();
  private final AtomicInteger closeCount = new AtomicInteger();
  private final List<Runnable> withheld = new ArrayList<>();
  private final Set<String> withholdMethods = new LinkedHashSet<>();

  private Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> inbound;

  /**
   * Stops answering the given method until {@link #releaseWithheld()} is called, which is how a
   * test observes an in-flight request.
   */
  InMemoryMcpTransport withholding(String method) {
    withholdMethods.add(method);
    return this;
  }

  /** Delivers every withheld response and stops withholding. */
  void releaseWithheld() {
    List<Runnable> pending = List.copyOf(withheld);
    withheld.clear();
    withholdMethods.clear();
    pending.forEach(Runnable::run);
  }

  InMemoryMcpTransport answering(String method, Function<Object, Object> answer) {
    answers.put(method, answer);
    return this;
  }

  List<String> methodsSent() {
    return List.copyOf(methodsSent);
  }

  Object lastRequestFor(String method) {
    return lastRequests.get(method);
  }

  int closeCount() {
    return closeCount.get();
  }

  @Override
  public Mono<Void> connect(
      Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
    this.inbound = handler;
    return Mono.empty();
  }

  @Override
  public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
    return Mono.fromRunnable(
        () -> {
          if (message instanceof McpSchema.JSONRPCNotification notification) {
            methodsSent.add(notification.method());
            return;
          }
          if (message instanceof McpSchema.JSONRPCRequest request) {
            methodsSent.add(request.method());
            lastRequests.put(request.method(), request.params());
            Runnable respond =
                () ->
                    inbound
                        .apply(
                            Mono.just(
                                new McpSchema.JSONRPCResponse(
                                    McpSchema.JSONRPC_VERSION,
                                    request.id(),
                                    answer(request),
                                    null)))
                        .subscribe();
            if (withholdMethods.contains(request.method())) {
              withheld.add(respond);
            } else {
              respond.run();
            }
          }
        });
  }

  @SuppressWarnings("unchecked")
  @Override
  public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
    return (T) data;
  }

  @Override
  public Mono<Void> closeGracefully() {
    closeCount.incrementAndGet();
    return Mono.empty();
  }

  private Object answer(McpSchema.JSONRPCRequest request) {
    if (McpSchema.METHOD_INITIALIZE.equals(request.method())) {
      return new McpSchema.InitializeResult(
          protocolVersions().get(protocolVersions().size() - 1),
          new McpSchema.ServerCapabilities(
              null,
              null,
              null,
              null,
              null,
              new McpSchema.ServerCapabilities.ToolCapabilities(null)),
          new McpSchema.Implementation("in-memory-server", null, "1.0.0", null, null, null),
          null,
          null);
    }
    Function<Object, Object> answer = answers.get(request.method());
    if (answer == null) {
      throw new IllegalStateException("no scripted answer for method " + request.method());
    }
    return answer.apply(request.params());
  }
}
