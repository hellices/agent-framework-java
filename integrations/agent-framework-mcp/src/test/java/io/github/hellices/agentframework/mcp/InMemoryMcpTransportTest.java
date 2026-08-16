package io.github.hellices.agentframework.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpTransportSessionClosedException;
import io.modelcontextprotocol.spec.McpTransportSessionNotFoundException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * Self test for the in-memory transport.
 *
 * <p>The owned lifecycle tests are only as trustworthy as this fixture. A transport that keeps
 * working after it was closed would let a closed client silently run a second handshake, and every
 * close and reconnect assertion built on it would be meaningless.
 */
class InMemoryMcpTransportTest {

  private static final Duration BLOCK = Duration.ofSeconds(5);

  @Test
  void rejectsSendsOnceClosed() {
    InMemoryMcpTransport transport = new InMemoryMcpTransport().answeringPing();
    transport.connect(inbound -> inbound).block(BLOCK);
    transport.closeGracefully().block(BLOCK);

    assertThatThrownBy(() -> transport.sendMessage(pingRequest()).block(BLOCK))
        .isInstanceOf(McpTransportSessionClosedException.class);
    assertThat(transport.isClosed()).isTrue();
    assertThat(transport.closeCount()).isEqualTo(1);
  }

  @Test
  void stopsAClosedClientFromRunningASecondHandshake() {
    InMemoryMcpTransport transport =
        new InMemoryMcpTransport()
            .answeringPing()
            .answering(
                McpSchema.METHOD_TOOLS_LIST,
                params -> new McpSchema.ListToolsResult(List.of(), null, null));
    McpAsyncClient client = clientOver(transport);
    client.initialize().block(BLOCK);
    client.closeGracefully().block(BLOCK);

    // The SDK is free to wrap an initialization failure, and this fixture test is not the place to
    // pin how it does that, so the assertion names the type that must be somewhere in the chain
    // rather than the wrapper on top of it. The counted initialize is the second half of the
    // statement: the client tried to hand shake again and the closed transport refused before it
    // recorded anything.
    assertThatThrownBy(() -> client.listTools(null, null).block(BLOCK))
        .isInstanceOf(RuntimeException.class)
        .hasStackTraceContaining(McpTransportSessionClosedException.class.getName());
    assertThat(transport.countOf(McpSchema.METHOD_INITIALIZE)).isEqualTo(1);
    assertThat(transport.isClosed()).isTrue();
  }

  @Test
  void answersAScriptedJsonRpcErrorAsAnMcpError() {
    InMemoryMcpTransport transport =
        new InMemoryMcpTransport()
            .answeringWithError(
                McpSchema.METHOD_PING, McpSchema.ErrorCodes.METHOD_NOT_FOUND, "Method not found");
    McpAsyncClient client = clientOver(transport);
    client.initialize().block(BLOCK);

    assertThatThrownBy(() -> client.ping().block(BLOCK))
        .isInstanceOf(McpError.class)
        .satisfies(
            failure ->
                assertThat(((McpError) failure).getJsonRpcError().code())
                    .isEqualTo(McpSchema.ErrorCodes.METHOD_NOT_FOUND));
  }

  @Test
  void failsAScriptedSendAndStillRecordsTheAttempt() {
    InMemoryMcpTransport transport =
        new InMemoryMcpTransport()
            .answeringPing()
            .failingSend(
                McpSchema.METHOD_TOOLS_LIST,
                () -> new McpTransportSessionNotFoundException("session gone"));
    McpAsyncClient client = clientOver(transport);
    client.initialize().block(BLOCK);

    assertThatThrownBy(() -> client.listTools(null, null).block(BLOCK))
        .isInstanceOf(McpTransportSessionNotFoundException.class);
    assertThat(transport.countOf(McpSchema.METHOD_TOOLS_LIST)).isEqualTo(1);
  }

  @Test
  void failsAScriptedCloseAndStaysClosed() {
    InMemoryMcpTransport transport =
        new InMemoryMcpTransport()
            .answeringPing()
            .failingClose(() -> new IllegalStateException("close failed"));
    transport.connect(inbound -> inbound).block(BLOCK);

    assertThatThrownBy(() -> transport.closeGracefully().block(BLOCK))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("close failed");
    assertThat(transport.isClosed()).isTrue();
  }

  @Test
  void throwsFromCloseWhenScriptedToAndStaysClosed() {
    InMemoryMcpTransport transport =
        new InMemoryMcpTransport()
            .answeringPing()
            .throwingClose(() -> new IllegalStateException("close threw"));
    transport.connect(inbound -> inbound).block(BLOCK);

    // The throw arrives where the publisher should have been returned, which is the failure mode a
    // scripted Mono.error cannot reproduce and the one an owner must not let wedge a close caller.
    assertThatThrownBy(transport::closeGracefully)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("close threw");
    assertThat(transport.isClosed()).isTrue();
    assertThat(transport.closeCount()).isEqualTo(1);
  }

  @Test
  void withholdsACloseUntilItIsReleased() {
    InMemoryMcpTransport transport = new InMemoryMcpTransport().answeringPing().withholdingClose();
    transport.connect(inbound -> inbound).block(BLOCK);

    CompletableFuture<Void> closing = transport.closeGracefully().toFuture();

    // Teardown has begun and not finished: the transport already refuses everything, which is what
    // makes it a faithful model of a cleanup that is still running.
    assertThat(closing).isNotDone();
    assertThat(transport.closeCount()).isEqualTo(1);
    assertThat(transport.isClosed()).isTrue();
    assertThatThrownBy(() -> transport.sendMessage(pingRequest()).block(BLOCK))
        .isInstanceOf(McpTransportSessionClosedException.class);

    transport.releaseWithheldClose();

    assertThat(closing).succeedsWithin(BLOCK);
  }

  @Test
  void countsHowOftenAMethodWasSent() {
    InMemoryMcpTransport transport = new InMemoryMcpTransport().answeringPing();
    McpAsyncClient client = clientOver(transport);
    client.initialize().block(BLOCK);
    client.ping().block(BLOCK);
    client.ping().block(BLOCK);

    assertThat(transport.countOf(McpSchema.METHOD_PING)).isEqualTo(2);
    assertThat(transport.countOf(McpSchema.METHOD_TOOLS_LIST)).isZero();
  }

  private static McpSchema.JSONRPCRequest pingRequest() {
    return new McpSchema.JSONRPCRequest(
        McpSchema.JSONRPC_VERSION, McpSchema.METHOD_PING, "1", Map.of());
  }

  private static McpAsyncClient clientOver(InMemoryMcpTransport transport) {
    return McpClient.async(transport)
        .requestTimeout(BLOCK)
        .initializationTimeout(BLOCK)
        .jsonSchemaValidator(new PermissiveJsonSchemaValidator())
        .build();
  }
}
