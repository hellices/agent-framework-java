package io.github.hellices.agentframework.mcp;

import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpTransportSessionClosedException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import reactor.core.publisher.Mono;

/**
 * In-memory MCP transport that answers requests from a script and records what it was asked.
 *
 * <p>The transport records how often it was closed, which is how a test can show that the adapter
 * leaves a borrowed client untouched: closing the client is what would close this transport.
 *
 * <p>A closed transport refuses every further send. A real transport is single use: the stdio
 * transport has destroyed its process and disposed its schedulers, and the streamable HTTP
 * transport has swapped in a closed session. The SDK client does not remember that it was closed
 * and will happily run a second handshake, so a transport that stayed usable would make every owned
 * close and reconnect assertion vacuous.
 *
 * <p>Tests must configure, drive, release, and inspect an instance from one thread. The ordered
 * script and observation collections are intentionally not a concurrent transport simulation; the
 * atomic close state only models close visibility at the SDK boundary.
 *
 * <p>{@link #unmarshalFrom(Object, TypeRef)} returns the scripted result object unchanged, because
 * the results never leave the process, so no JSON mapper implementation is needed on the test
 * runtime.
 */
final class InMemoryMcpTransport implements McpClientTransport {

  private final Map<String, Function<Object, Object>> answers = new LinkedHashMap<>();
  private final Map<String, McpSchema.JSONRPCResponse.JSONRPCError> errorAnswers =
      new LinkedHashMap<>();
  private final Map<String, Supplier<Throwable>> sendFailures = new LinkedHashMap<>();
  private final List<String> methodsSent = new ArrayList<>();
  private final Map<String, Object> lastRequests = new LinkedHashMap<>();
  private final AtomicInteger closeCount = new AtomicInteger();
  private final AtomicBoolean closed = new AtomicBoolean();
  private final List<Runnable> withheld = new ArrayList<>();
  private final List<Runnable> withheldCloses = new ArrayList<>();
  private final Set<String> withholdMethods = new LinkedHashSet<>();

  private Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> inbound;
  private Supplier<Throwable> closeFailure;
  private Supplier<RuntimeException> closeThrow;
  private Supplier<Error> closeThrowError;
  private boolean withholdClose;

  /**
   * Stops answering the given method until {@link #releaseWithheld()} is called, which is how a
   * test observes an in-flight request.
   */
  InMemoryMcpTransport withholding(String method) {
    withholdMethods.add(method);
    return this;
  }

  /**
   * Delivers every withheld response and stops withholding.
   *
   * <p>Withholding stops permanently, so a request that arrives after this call is answered at
   * once. A test that released a withheld handshake and then triggered a second one would otherwise
   * wait forever for a response nobody is going to release.
   */
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

  /** Answers {@code ping} with an empty result, which is what a healthy server does. */
  InMemoryMcpTransport answeringPing() {
    return answering(McpSchema.METHOD_PING, params -> Map.of());
  }

  /** Answers the method with a JSON-RPC error, which the SDK surfaces as an {@code McpError}. */
  InMemoryMcpTransport answeringWithError(String method, int code, String message) {
    errorAnswers.put(method, new McpSchema.JSONRPCResponse.JSONRPCError(code, message));
    return this;
  }

  /** Fails the send of the method, which is how a transport level failure is scripted. */
  InMemoryMcpTransport failingSend(String method, Supplier<Throwable> failure) {
    sendFailures.put(method, failure);
    return this;
  }

  /** Fails {@link #closeGracefully()}; the transport still counts as closed afterwards. */
  InMemoryMcpTransport failingClose(Supplier<Throwable> failure) {
    this.closeFailure = failure;
    return this;
  }

  /**
   * Throws from {@link #closeGracefully()} instead of returning a failed publisher, which is what a
   * transport that fails while setting its own teardown up does. The attempt still counts as a
   * close, because the transport was asked to close and is unusable afterwards either way.
   */
  InMemoryMcpTransport throwingClose(Supplier<RuntimeException> failure) {
    this.closeThrow = failure;
    return this;
  }

  /**
   * Throws an {@link Error} from {@link #closeGracefully()} rather than an ordinary failure, which
   * is what a linkage error from a consumer's classpath does while a teardown is being set up.
   *
   * <p>Reactor deliberately refuses to route a JVM-fatal throwable — {@code VirtualMachineError},
   * {@code ThreadDeath}, {@code LinkageError} — to an error consumer, so this one keeps travelling
   * out of {@code subscribe} and out of the caller that started the cleanup. That is the only
   * scripted input that reaches the guards protecting the memoized and lifecycle-level close
   * promises. The attempt still counts as a close, for the same reason {@link
   * #throwingClose(Supplier)} does.
   */
  InMemoryMcpTransport throwingCloseError(Supplier<Error> failure) {
    this.closeThrowError = failure;
    return this;
  }

  /**
   * Starts {@link #closeGracefully()} but withholds its completion until {@link
   * #releaseWithheldClose()}, which is how a test observes a teardown that has begun and not
   * finished.
   *
   * <p>The transport counts the close and refuses sends from the moment teardown starts, because a
   * real transport is unusable as soon as it begins tearing down, not when it finishes.
   */
  InMemoryMcpTransport withholdingClose() {
    this.withholdClose = true;
    return this;
  }

  /**
   * Completes every withheld close and stops withholding, on the calling thread.
   *
   * <p>Withholding stops permanently for the same reason {@link #releaseWithheld()} does: a later
   * close must not wait for a release nobody is going to issue.
   */
  void releaseWithheldClose() {
    List<Runnable> pending = List.copyOf(withheldCloses);
    withheldCloses.clear();
    withholdClose = false;
    pending.forEach(Runnable::run);
  }

  List<String> methodsSent() {
    return List.copyOf(methodsSent);
  }

  int countOf(String method) {
    return (int) methodsSent.stream().filter(method::equals).count();
  }

  Object lastRequestFor(String method) {
    return lastRequests.get(method);
  }

  int closeCount() {
    return closeCount.get();
  }

  boolean isClosed() {
    return closed.get();
  }

  @Override
  public Mono<Void> connect(
      Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
    this.inbound = handler;
    return Mono.empty();
  }

  @Override
  public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
    return Mono.defer(
        () -> {
          if (closed.get()) {
            return Mono.error(new McpTransportSessionClosedException());
          }
          if (message instanceof McpSchema.JSONRPCNotification notification) {
            methodsSent.add(notification.method());
            return Mono.empty();
          }
          if (!(message instanceof McpSchema.JSONRPCRequest request)) {
            return Mono.empty();
          }
          methodsSent.add(request.method());
          lastRequests.put(request.method(), request.params());
          Supplier<Throwable> failure = sendFailures.get(request.method());
          if (failure != null) {
            return Mono.error(failure.get());
          }
          Runnable respond =
              () -> {
                if (!closed.get()) {
                  inbound.apply(Mono.just(response(request))).subscribe();
                }
              };
          if (withholdMethods.contains(request.method())) {
            withheld.add(respond);
          } else {
            respond.run();
          }
          return Mono.empty();
        });
  }

  @SuppressWarnings("unchecked")
  @Override
  public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
    return (T) data;
  }

  @Override
  public Mono<Void> closeGracefully() {
    Supplier<RuntimeException> thrown = closeThrow;
    if (thrown != null) {
      closeCount.incrementAndGet();
      closed.set(true);
      throw thrown.get();
    }
    Supplier<Error> fatal = closeThrowError;
    if (fatal != null) {
      closeCount.incrementAndGet();
      closed.set(true);
      throw fatal.get();
    }
    return Mono.defer(
        () -> {
          closeCount.incrementAndGet();
          closed.set(true);
          Supplier<Throwable> failure = closeFailure;
          if (failure != null) {
            return Mono.error(failure.get());
          }
          if (!withholdClose) {
            return Mono.empty();
          }
          return Mono.<Void>create(sink -> withheldCloses.add(sink::success));
        });
  }

  private McpSchema.JSONRPCResponse response(McpSchema.JSONRPCRequest request) {
    McpSchema.JSONRPCResponse.JSONRPCError error = errorAnswers.get(request.method());
    if (error != null) {
      return new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id(), null, error);
    }
    return new McpSchema.JSONRPCResponse(
        McpSchema.JSONRPC_VERSION, request.id(), answer(request), null);
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
