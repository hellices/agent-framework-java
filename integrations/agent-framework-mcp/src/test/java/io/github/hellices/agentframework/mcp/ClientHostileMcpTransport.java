package io.github.hellices.agentframework.mcp;

import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import reactor.core.publisher.Mono;

/**
 * A transport the SDK client cannot be built on.
 *
 * <p>Verified SDK fact 16: the {@code McpAsyncClient} constructor calls {@code protocolVersions()}
 * on the transport it was handed. Throwing from there is the deterministic way to reach the state
 * where a transport exists but a client does not, without starting a process or opening a socket.
 * That state is exactly where a transport leak hides, so it needs a fixture rather than a comment.
 *
 * <p>Every other method throws, because reaching any of them would mean a client was built after
 * all and the test is no longer testing what it claims to.
 */
final class ClientHostileMcpTransport implements McpClientTransport {

  private final AtomicInteger closeCount = new AtomicInteger();

  private Supplier<RuntimeException> refusal =
      () -> new IllegalStateException("transport refuses to negotiate");
  private Supplier<Throwable> closeFailure;

  /**
   * Refuses the client build with a caller supplied throwable.
   *
   * <p>A test that hands the same instance to {@link #failingClose(Supplier)} reaches the collision
   * where the failure being reported and the failure from cleaning up after it are one object,
   * which is where suppressing one onto the other is not allowed.
   */
  ClientHostileMcpTransport refusing(Supplier<RuntimeException> refusal) {
    this.refusal = refusal;
    return this;
  }

  /** Fails {@link #closeGracefully()}; the transport still counts as closed afterwards. */
  ClientHostileMcpTransport failingClose(Supplier<Throwable> failure) {
    this.closeFailure = failure;
    return this;
  }

  @Override
  public List<String> protocolVersions() {
    throw refusal.get();
  }

  @Override
  public Mono<Void> connect(
      Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
    throw new IllegalStateException("no client should have connected this transport");
  }

  @Override
  public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
    throw new IllegalStateException("no client should have sent on this transport");
  }

  @Override
  public <T> T unmarshalFrom(Object data, TypeRef<T> type) {
    throw new IllegalStateException("no client should have unmarshalled on this transport");
  }

  @Override
  public Mono<Void> closeGracefully() {
    return Mono.defer(
        () -> {
          closeCount.incrementAndGet();
          Supplier<Throwable> failure = closeFailure;
          return failure == null ? Mono.empty() : Mono.error(failure.get());
        });
  }

  int closeCount() {
    return closeCount.get();
  }
}
