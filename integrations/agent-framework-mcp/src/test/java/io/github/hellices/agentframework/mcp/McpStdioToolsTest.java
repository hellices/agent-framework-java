package io.github.hellices.agentframework.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hellices.agentframework.api.tool.FunctionTool;
import io.github.hellices.agentframework.mcp.internal.McpOwnedClientSettings;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The stdio facade is validated without ever starting a process.
 *
 * <p>No test here constructs a {@code StdioClientTransport}: its constructor allocates three
 * single-thread schedulers before any process exists, and a test that never connects would leak
 * them. Everything the facade does with a real transport is exercised through the package-private
 * seam.
 */
class McpStdioToolsTest {

  private static final Duration SETTLE = Duration.ofSeconds(5);

  private static final Map<String, Object> SCHEMA =
      Map.of("type", "object", "properties", Map.of("query", Map.of("type", "string")));

  @Test
  void requiresAJsonMapperAndNamesTheModuleThatProvidesOne() {
    assertThatThrownBy(
            () ->
                McpStdioTools.builder(ServerParameters.builder("no-such-command").build())
                    .schemaValidator(new PermissiveJsonSchemaValidator())
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("jsonMapper")
        .hasMessageContaining("mcp-json-jackson2")
        .hasMessageContaining("mcp-json-jackson3");
  }

  @Test
  void requiresASchemaValidator() {
    assertThatThrownBy(
            () ->
                McpStdioTools.builder(ServerParameters.builder("no-such-command").build())
                    .jsonMapper(new RejectingMcpJsonMapper())
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("schemaValidator");
  }

  @Test
  void rejectsMissingServerParametersNullCollaboratorsAndNonPositiveTimeouts() {
    assertThatThrownBy(() -> McpStdioTools.builder(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("serverParameters must not be null");
    assertThatThrownBy(() -> builder().jsonMapper(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("jsonMapper must not be null");
    assertThatThrownBy(() -> builder().schemaValidator(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("schemaValidator must not be null");
    assertThatThrownBy(() -> builder().toolOptions(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("toolOptions must not be null");
    assertThatThrownBy(() -> builder().requestTimeout(Duration.ZERO).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("requestTimeout must be positive");
    assertThatThrownBy(() -> builder().initializationTimeout(Duration.ofSeconds(-1)).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("initializationTimeout must be positive");
  }

  @Test
  void rejectsACommandLineNoProcessCouldRun() {
    assertThatThrownBy(() -> McpStdioTools.builder(ServerParameters.builder("   ").build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("command must not be blank");
    assertThatThrownBy(
            () ->
                McpStdioTools.builder(
                    ServerParameters.builder("server")
                        .args(Arrays.asList("--stdio", null))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("args must not contain null");
    Map<String, String> environment = new HashMap<>();
    environment.put("MCP_TOKEN", null);
    assertThatThrownBy(
            () ->
                McpStdioTools.builder(ServerParameters.builder("server").env(environment).build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("env must not contain a null key or value");
  }

  @Test
  void copiesTheCommandLineSoALaterMutationCannotChangeWhatConnectWouldStart() {
    ServerParameters parameters =
        ServerParameters.builder("server")
            .args(new ArrayList<>(List.of("--stdio")))
            .env(Map.of("MCP_TOKEN", "recorded"))
            .build();

    McpStdioTools.Builder builder = builder(parameters);
    parameters.getArgs().add("--injected");
    parameters.getEnv().put("MCP_TOKEN", "replaced");

    ServerParameters recorded = builder.serverParameters();
    assertThat(recorded.getCommand()).isEqualTo("server");
    assertThat(recorded.getArgs()).containsExactly("--stdio");
    assertThat(recorded.getEnv()).containsEntry("MCP_TOKEN", "recorded");
  }

  @Test
  void buildsWithoutStartingAProcessAndRefusesToWorkUntilConnected() {
    McpStdioTools tools = builder().build();

    assertThatThrownBy(() -> tools.discoverTools().toCompletableFuture().join())
        .rootCause()
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("connect()");
    assertThat(tools.close().toCompletableFuture()).succeedsWithin(SETTLE);
  }

  @Test
  void connectsDiscoversAndClosesThroughTheSeam() {
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
    McpStdioTools tools =
        new McpStdioTools(
            factory,
            new McpOwnedClientSettings(
                new PermissiveJsonSchemaValidator(), Duration.ofSeconds(5), Duration.ofSeconds(5)),
            McpToolAdapterOptions.builder().localNamePrefix("github_").build());

    tools.connect().toCompletableFuture().join();
    List<FunctionTool> discovered = tools.discoverTools().toCompletableFuture().join();
    tools.close().toCompletableFuture().join();

    assertThat(discovered).hasSize(1);
    assertThat(discovered.get(0).definition().name()).isEqualTo("github_search-issues");
    assertThat(factory.createdCount()).isEqualTo(1);
    assertThat(transport.closeCount()).isEqualTo(1);
  }

  @Test
  void rejectsNullToolOptionsAtTheSeamWithoutDereferencingThem() {
    assertThatThrownBy(
            () ->
                new McpStdioTools(
                    new ScriptedMcpTransportFactory(),
                    new McpOwnedClientSettings(
                        new PermissiveJsonSchemaValidator(),
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(5)),
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("options must not be null");
  }

  private static McpStdioTools.Builder builder() {
    return builder(ServerParameters.builder("no-such-command").build());
  }

  private static McpStdioTools.Builder builder(ServerParameters serverParameters) {
    return McpStdioTools.builder(serverParameters)
        .jsonMapper(new RejectingMcpJsonMapper())
        .schemaValidator(new PermissiveJsonSchemaValidator());
  }
}
