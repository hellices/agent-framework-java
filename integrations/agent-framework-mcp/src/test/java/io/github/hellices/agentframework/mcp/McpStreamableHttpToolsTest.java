package io.github.hellices.agentframework.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hellices.agentframework.api.tool.FunctionTool;
import io.github.hellices.agentframework.mcp.internal.McpOwnedClientSettings;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The streamable HTTP facade is validated without opening a socket.
 *
 * <p>Endpoint validation is done by this builder rather than left to the first request, because the
 * SDK only rejects a cross-origin endpoint when a request is finally sent, and a caller that
 * mistyped a URL should learn about it where the mistake is, not on the first tool call.
 *
 * <p>No test here constructs an {@code HttpClientStreamableHttpTransport}: its builder's {@code
 * build()} allocates a {@code java.net.http.HttpClient}, which starts a selector thread and cannot
 * be closed on Java 17. Everything the facade does with a real transport is exercised through the
 * package-private seam.
 */
class McpStreamableHttpToolsTest {

  private static final Duration SETTLE = Duration.ofSeconds(5);

  private static final Map<String, Object> SCHEMA =
      Map.of("type", "object", "properties", Map.of("query", Map.of("type", "string")));

  @Test
  void rejectsABaseUriThatIsNotHttp() {
    assertThatThrownBy(() -> McpStreamableHttpTools.builder(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("baseUri");
    assertThatThrownBy(() -> McpStreamableHttpTools.builder("   "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("baseUri");
    assertThatThrownBy(() -> McpStreamableHttpTools.builder("ftp://example.com"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("http");
    assertThatThrownBy(() -> McpStreamableHttpTools.builder("not a uri"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("baseUri");
    assertThatThrownBy(() -> McpStreamableHttpTools.builder("example.com/mcp"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("baseUri");
  }

  @Test
  void rejectsABaseUriNoHttpRequestCouldBeSentTo() {
    // A hostless HTTP URI parses, so nothing rejects it until it is used: the JDK request builder
    // refuses the resolved URI with "unsupported URI", and the SDK's absolute endpoint check
    // dereferences the base authority, so a same-scheme absolute endpoint would fail with a
    // NullPointerException instead of a message naming the base URI.
    assertThatThrownBy(() -> McpStreamableHttpTools.builder("https:/mcp"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("baseUri")
        .hasMessageContaining("host");
    assertThatThrownBy(() -> McpStreamableHttpTools.builder("https:///mcp"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("baseUri")
        .hasMessageContaining("host");
    // An authority is not enough: this one has no host the JDK accepts either.
    assertThatThrownBy(() -> McpStreamableHttpTools.builder("http://exa_mple.com"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("baseUri")
        .hasMessageContaining("host");
  }

  @Test
  void rejectsAnEndpointThatIsNotUnderTheBaseUri() {
    assertThatThrownBy(
            () ->
                McpStreamableHttpTools.builder("https://example.com/api")
                    .endpoint("https://evil.example.com/mcp"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Absolute endpoint URL does not match the base URL.");
    assertThatThrownBy(
            () ->
                McpStreamableHttpTools.builder("https://example.com/api")
                    .endpoint("https://example.com/other"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Absolute endpoint URL does not match the base URL.");
    assertThatThrownBy(
            () -> McpStreamableHttpTools.builder("https://example.com/api").endpoint(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("endpoint must not be null");
  }

  @Test
  void rejectsAnEndpointNoRequestCouldBeBuiltFrom() {
    // Blank is rejected here because the SDK transport builder's own endpoint check runs at
    // connect, where this facade builds the transport, so a blank endpoint would otherwise fail
    // the first connect rather than the call that set it.
    assertThatThrownBy(() -> McpStreamableHttpTools.builder("https://example.com").endpoint("   "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("endpoint must not be blank");
    assertThatThrownBy(
            () -> McpStreamableHttpTools.builder("https://example.com").endpoint("/ mcp"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("endpoint");
  }

  @Test
  void acceptsARelativeEndpointAndAnAbsoluteEndpointUnderTheBase() {
    assertThatCode(
            () ->
                McpStreamableHttpTools.builder("https://example.com/api")
                    .endpoint("/mcp")
                    .jsonMapper(new RejectingMcpJsonMapper())
                    .schemaValidator(new PermissiveJsonSchemaValidator())
                    .build())
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                McpStreamableHttpTools.builder("https://example.com/api")
                    .endpoint("https://example.com/api/mcp")
                    .jsonMapper(new RejectingMcpJsonMapper())
                    .schemaValidator(new PermissiveJsonSchemaValidator())
                    .build())
        .doesNotThrowAnyException();
  }

  @Test
  void recordsTheRequestUriTheSdkWouldResolvePerRequest() {
    assertThat(builder("https://example.com").requestUri())
        .isEqualTo(URI.create("https://example.com/mcp"));
    // A rooted endpoint replaces the base path, which is what URI resolution does and what the SDK
    // therefore does; only an absolute endpoint is checked against the base.
    assertThat(builder("https://example.com/api").endpoint("/mcp").requestUri())
        .isEqualTo(URI.create("https://example.com/mcp"));
    assertThat(builder("https://example.com/api/").endpoint("mcp").requestUri())
        .isEqualTo(URI.create("https://example.com/api/mcp"));
    assertThat(
            builder("https://example.com/api").endpoint("https://example.com/api/mcp").requestUri())
        .isEqualTo(URI.create("https://example.com/api/mcp"));
  }

  @Test
  void requiresAJsonMapperAndASchemaValidator() {
    assertThatThrownBy(
            () ->
                McpStreamableHttpTools.builder("https://example.com")
                    .schemaValidator(new PermissiveJsonSchemaValidator())
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mcp-json-jackson2");
    assertThatThrownBy(
            () ->
                McpStreamableHttpTools.builder("https://example.com")
                    .jsonMapper(new RejectingMcpJsonMapper())
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("schemaValidator");
  }

  @Test
  void rejectsAConnectTimeoutNoConnectionCouldUseAndRecordsTheOneItAccepts() {
    assertThatThrownBy(() -> builder("https://example.com").connectTimeout(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("connectTimeout must be positive");
    assertThatThrownBy(() -> builder("https://example.com").connectTimeout(Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("connectTimeout must be positive");
    assertThatThrownBy(() -> builder("https://example.com").connectTimeout(Duration.ofSeconds(-1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("connectTimeout must be positive");
    assertThat(builder("https://example.com").connectTimeout()).isEqualTo(Duration.ofSeconds(10));
    assertThat(
            builder("https://example.com").connectTimeout(Duration.ofSeconds(3)).connectTimeout())
        .isEqualTo(Duration.ofSeconds(3));
  }

  @Test
  void rejectsNullCollaboratorsAndNonPositiveTimeouts() {
    assertThatThrownBy(() -> builder("https://example.com").jsonMapper(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("jsonMapper must not be null");
    assertThatThrownBy(() -> builder("https://example.com").schemaValidator(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("schemaValidator must not be null");
    assertThatThrownBy(() -> builder("https://example.com").toolOptions(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("toolOptions must not be null");
    assertThatThrownBy(() -> builder("https://example.com").requestTimeout(Duration.ZERO).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("requestTimeout must be positive");
    assertThatThrownBy(
            () ->
                builder("https://example.com")
                    .initializationTimeout(Duration.ofSeconds(-1))
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("initializationTimeout must be positive");
  }

  @Test
  void buildsWithoutOpeningAConnectionAndRefusesToWorkUntilConnected() {
    McpStreamableHttpTools tools =
        McpStreamableHttpTools.builder("https://example.com")
            .jsonMapper(new RejectingMcpJsonMapper())
            .schemaValidator(new PermissiveJsonSchemaValidator())
            .build();

    assertThatThrownBy(() -> tools.discoverTools().toCompletableFuture().join())
        .rootCause()
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("connect()");
    assertThat(tools.close().toCompletableFuture()).succeedsWithin(SETTLE);
  }

  @Test
  void connectsDiscoversAndClosesThroughTheSeam() {
    InMemoryMcpTransport transport = publishingSearchIssues();
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(transport);
    McpStreamableHttpTools tools =
        new McpStreamableHttpTools(
            factory,
            new McpOwnedClientSettings(
                new PermissiveJsonSchemaValidator(), Duration.ofSeconds(5), Duration.ofSeconds(5)),
            McpToolAdapterOptions.defaults());

    tools.connect().toCompletableFuture().join();
    List<FunctionTool> discovered = tools.discoverTools().toCompletableFuture().join();
    tools.close().toCompletableFuture().join();

    assertThat(discovered).hasSize(1);
    assertThat(discovered.get(0).definition().name()).isEqualTo("search-issues");
    assertThat(factory.createdCount()).isEqualTo(1);
    assertThat(transport.closeCount()).isEqualTo(1);
  }

  @Test
  void carriesEveryConfiguredValueIntoTheProviderTheBuilderProduces() {
    InMemoryMcpTransport transport = publishingSearchIssues();
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(transport);

    McpStreamableHttpTools tools =
        builder("https://example.com")
            .endpoint("/mcp")
            .connectTimeout(Duration.ofSeconds(3))
            .toolOptions(McpToolAdapterOptions.builder().localNamePrefix("github_").build())
            .build(factory);

    // Nothing may be created by build(): the public path would have allocated an HttpClient here,
    // and Java 17 cannot close one.
    assertThat(factory.createdCount()).isZero();

    tools.connect().toCompletableFuture().join();
    List<FunctionTool> discovered = tools.discoverTools().toCompletableFuture().join();
    tools.close().toCompletableFuture().join();

    assertThat(discovered).hasSize(1);
    assertThat(discovered.get(0).definition().name()).isEqualTo("github_search-issues");
    assertThat(factory.createdCount()).isEqualTo(1);
    assertThat(transport.closeCount()).isEqualTo(1);
  }

  @Test
  void appliesTheSameRequirementsWhetherOrNotTheTransportIsSupplied() {
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory();
    assertThatThrownBy(
            () ->
                McpStreamableHttpTools.builder("https://example.com")
                    .schemaValidator(new PermissiveJsonSchemaValidator())
                    .build(factory))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("jsonMapper");
    assertThatThrownBy(
            () ->
                McpStreamableHttpTools.builder("https://example.com")
                    .jsonMapper(new RejectingMcpJsonMapper())
                    .build(factory))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("schemaValidator");
    assertThatThrownBy(
            () -> builder("https://example.com").requestTimeout(Duration.ZERO).build(factory))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("requestTimeout must be positive");
    assertThatThrownBy(
            () -> builder("https://example.com").initializationTimeout(null).build(factory))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("initializationTimeout must not be null");
    assertThat(factory.createdCount()).isZero();
  }

  private static InMemoryMcpTransport publishingSearchIssues() {
    return new InMemoryMcpTransport()
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
  }

  private static McpStreamableHttpTools.Builder builder(String baseUri) {
    return McpStreamableHttpTools.builder(baseUri)
        .jsonMapper(new RejectingMcpJsonMapper())
        .schemaValidator(new PermissiveJsonSchemaValidator());
  }
}
