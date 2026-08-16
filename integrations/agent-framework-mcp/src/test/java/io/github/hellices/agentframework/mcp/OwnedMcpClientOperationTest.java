package io.github.hellices.agentframework.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hellices.agentframework.api.tool.FunctionTool;
import io.github.hellices.agentframework.api.tool.ToolArguments;
import io.github.hellices.agentframework.api.tool.ToolContext;
import io.github.hellices.agentframework.mcp.internal.McpOwnedClientSettings;
import io.github.hellices.agentframework.mcp.internal.McpToolDiscovery;
import io.github.hellices.agentframework.mcp.internal.OwnedMcpAsyncOperations;
import io.github.hellices.agentframework.mcp.internal.OwnedMcpClientLifecycle;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Operations run on the generation the owner already has, and only on that one.
 *
 * <p>An owned client that reconnected implicitly would start a server process or open a network
 * connection from a call that reads like a plain tool lookup, so every negative test here also
 * asserts that the transport factory was not called.
 */
class OwnedMcpClientOperationTest {

  private static final Duration SETTLE = Duration.ofSeconds(5);

  private static final Map<String, Object> SCHEMA =
      Map.of("type", "object", "properties", Map.of("query", Map.of("type", "string")));

  @Test
  void refusesToRunAnOperationBeforeConnectWithoutCreatingATransport() {
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory();
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());

    assertThatThrownBy(() -> lifecycle.execute(client -> client.ping()).join())
        .rootCause()
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("connect()");

    assertThat(factory.createdCount()).isZero();
  }

  @Test
  void refusesToRunAnOperationAfterCloseWithoutCreatingATransport() {
    InMemoryMcpTransport transport = new InMemoryMcpTransport().answeringPing();
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(transport);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());
    lifecycle.connect().join();
    lifecycle.close().join();

    assertThatThrownBy(() -> lifecycle.execute(client -> client.ping()).join())
        .rootCause()
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("connect()");

    assertThat(factory.createdCount()).isEqualTo(1);
    assertThat(transport.countOf(McpSchema.METHOD_PING)).isZero();
  }

  @Test
  void runsTheOperationOnTheCurrentGeneration() {
    InMemoryMcpTransport transport = new InMemoryMcpTransport().answeringPing();
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(transport);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());
    lifecycle.connect().join();

    lifecycle.execute(client -> client.ping()).join();

    assertThat(transport.countOf(McpSchema.METHOD_PING)).isEqualTo(1);
    assertThat(factory.createdCount()).isEqualTo(1);
  }

  @Test
  void anOperationStartedWhileConnectingJoinsThatHandshake() {
    InMemoryMcpTransport transport =
        new InMemoryMcpTransport().answeringPing().withholding(McpSchema.METHOD_INITIALIZE);
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(transport);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());

    CompletableFuture<Void> connecting = lifecycle.connect();
    CompletableFuture<Object> operation = lifecycle.execute(client -> client.ping());

    assertThat(operation).isNotDone();
    assertThat(factory.createdCount()).isEqualTo(1);

    transport.releaseWithheld();

    assertThat(connecting).succeedsWithin(SETTLE);
    assertThat(operation).succeedsWithin(SETTLE);
    assertThat(factory.createdCount()).isEqualTo(1);
    assertThat(transport.countOf(McpSchema.METHOD_PING)).isEqualTo(1);
  }

  @Test
  void cancellingAnOperationNeverClosesTheGeneration() {
    InMemoryMcpTransport transport =
        new InMemoryMcpTransport().answeringPing().withholding(McpSchema.METHOD_PING);
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(transport);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());
    lifecycle.connect().join();

    CompletableFuture<Object> operation = lifecycle.execute(client -> client.ping());
    assertThat(operation.cancel(true)).isTrue();
    transport.releaseWithheld();

    assertThat(operation).isCancelled();
    assertThat(transport.closeCount()).isZero();
    assertThat(factory.createdCount()).isEqualTo(1);
    assertThat(transport.countOf(McpSchema.METHOD_PING)).isEqualTo(1);
  }

  @Test
  void discoversToolsThroughTheOwnedPort() {
    InMemoryMcpTransport transport =
        new InMemoryMcpTransport()
            .answeringPing()
            .answering(
                McpSchema.METHOD_TOOLS_LIST,
                params ->
                    new McpSchema.ListToolsResult(
                        List.of(
                            new McpSchema.Tool(
                                "search-issues", null, "search", SCHEMA, null, null, null, null)),
                        null,
                        null));
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(transport);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());
    lifecycle.connect().join();

    List<FunctionTool> tools =
        new McpToolDiscovery(
                new OwnedMcpAsyncOperations(lifecycle), McpToolAdapterOptions.defaults())
            .discover()
            .toCompletableFuture()
            .join();

    assertThat(tools).hasSize(1);
    assertThat(tools.get(0).definition().name()).isEqualTo("search-issues");
    assertThat(transport.countOf(McpSchema.METHOD_TOOLS_LIST)).isEqualTo(1);
  }

  @Test
  void aToolRetainedAfterCloseFailsOnTheConnectRequirementAndCreatesNoTransport() {
    // The realistic engine shape: an agent is configured once, keeps the FunctionTool it was given,
    // and calls it later. If the owner closed in between, the call must fail on the same explicit
    // connect requirement as any other operation and must not resurrect the server on its own.
    InMemoryMcpTransport transport =
        new InMemoryMcpTransport()
            .answeringPing()
            .answering(
                McpSchema.METHOD_TOOLS_LIST,
                params ->
                    new McpSchema.ListToolsResult(
                        List.of(
                            new McpSchema.Tool(
                                "search-issues", null, "search", SCHEMA, null, null, null, null)),
                        null,
                        null))
            .answering(
                McpSchema.METHOD_TOOLS_CALL,
                params ->
                    new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent(null, "ok", null)), false, null, null));
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(transport);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());
    lifecycle.connect().join();
    FunctionTool tool =
        new McpToolDiscovery(
                new OwnedMcpAsyncOperations(lifecycle), McpToolAdapterOptions.defaults())
            .discover()
            .toCompletableFuture()
            .join()
            .get(0);

    lifecycle.close().join();

    assertThatThrownBy(
            () ->
                tool.execute(new ToolArguments(Map.of()), new ToolContext(null, Map.of()))
                    .toCompletableFuture()
                    .join())
        .rootCause()
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("connect()");
    assertThat(factory.createdCount()).isEqualTo(1);
    assertThat(transport.countOf(McpSchema.METHOD_TOOLS_CALL)).isZero();
    assertThat(transport.closeCount()).isEqualTo(1);
  }

  @Test
  void cancellingAnOperationThatJoinedAHandshakeLeavesTheHandshakeAndTheGenerationAlone() {
    InMemoryMcpTransport transport =
        new InMemoryMcpTransport().answeringPing().withholding(McpSchema.METHOD_INITIALIZE);
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(transport);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());

    CompletableFuture<Void> connecting = lifecycle.connect();
    CompletableFuture<Object> operation = lifecycle.execute(client -> client.ping());
    assertThat(operation.cancel(true)).isTrue();

    transport.releaseWithheld();

    // One caller left; the connection it was waiting for is not its to end. The handshake still
    // completes, the generation stays open, and the owner still serves the next operation.
    assertThat(connecting).succeedsWithin(SETTLE);
    assertThat(operation).isCancelled();
    assertThat(transport.closeCount()).isZero();
    lifecycle.execute(client -> client.ping()).join();
    assertThat(factory.createdCount()).isEqualTo(1);
  }

  @Test
  void anOperationLosesTheHandshakeTheOwnerClosedAndReachesNoServer() {
    // The owner closed while this operation was still waiting for the handshake it joined. The
    // generation that handshake produced belongs to nobody: it is released as soon as it arrives.
    // An operation that still ran through it would send a request on a client being torn down, and
    // on the SDK's lazy initialization path could drive a second handshake on a connection the
    // caller believes is shut down.
    InMemoryMcpTransport transport =
        new InMemoryMcpTransport().answeringPing().withholding(McpSchema.METHOD_INITIALIZE);
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(transport);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());

    CompletableFuture<Void> connecting = lifecycle.connect();
    CompletableFuture<Object> operation = lifecycle.execute(client -> client.ping());
    CompletableFuture<Void> closing = lifecycle.close();

    transport.releaseWithheld();

    assertThat(connecting).succeedsWithin(SETTLE);
    assertThat(closing).succeedsWithin(SETTLE);
    // A get with a deadline rather than join, because an operation left waiting on a client that is
    // being closed is the failure this test is about and join would hang on it instead of failing.
    assertThatThrownBy(() -> operation.get(SETTLE.toMillis(), TimeUnit.MILLISECONDS))
        .rootCause()
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("connect()");
    assertThat(transport.countOf(McpSchema.METHOD_PING)).isZero();
    assertThat(transport.closeCount()).isEqualTo(1);
    assertThat(factory.createdCount()).isEqualTo(1);
  }

  @Test
  void rejectsAMissingLifecycleAndAMissingOperation() {
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory();
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());

    assertThatThrownBy(() -> new OwnedMcpAsyncOperations(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("lifecycle must not be null");
    assertThatThrownBy(() -> lifecycle.execute(null).join())
        .rootCause()
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("operation must not be null");
  }

  private static McpOwnedClientSettings settings() {
    return new McpOwnedClientSettings(
        new PermissiveJsonSchemaValidator(), Duration.ofSeconds(5), Duration.ofSeconds(5));
  }
}
