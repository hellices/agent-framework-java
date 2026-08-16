package io.github.hellices.agentframework.mcp;

import io.github.hellices.agentframework.api.tool.FunctionTool;
import io.github.hellices.agentframework.mcp.internal.McpClientTransportFactory;
import io.github.hellices.agentframework.mcp.internal.McpOwnedClientSettings;
import io.github.hellices.agentframework.mcp.internal.OwnedMcpTools;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.util.Utils;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletionStage;

/**
 * Exposes the tools of a streamable HTTP MCP server as framework tools, owning the connection.
 *
 * <p>This is the owned counterpart of {@link ConnectedMcpClientAdapter} for HTTP. It creates the
 * transport and the client, drives the handshake, validates the connection before every call,
 * replaces it at most once when the server forgets it, and closes it. {@link #connect()} is
 * required before {@link #discoverTools()}, and {@link #close()} ends the current session while
 * leaving this object reusable.
 *
 * <p>The base URI and the endpoint are validated when they are set, using the same rule the SDK
 * applies when it finally builds a request URL: a relative endpoint is resolved against the base,
 * and an absolute endpoint must share the base's scheme and authority and start with its path. The
 * SDK applies that rule per request and this facade builds the transport at {@link #connect()}, so
 * a mistyped URL would otherwise surface on the first connect instead of at configuration time.
 *
 * <p>Each connection generation builds one SDK transport, and that transport allocates a {@link
 * java.net.http.HttpClient}. On Java 17 an {@code HttpClient} cannot be closed; its resources are
 * released when it becomes unreachable. That is the main reason a lost connection is allowed
 * exactly one replacement: an unbounded reconnect loop would accumulate clients that this module
 * cannot release.
 *
 * <p>This first version exposes no request headers, no custom {@code HttpClient} or client
 * customizer, no redirect policy, and no tracing hook. Those are separate requirements and are
 * deliberately not guessed at here.
 *
 * <p>Instances are safe to share once constructed.
 */
public final class McpStreamableHttpTools {

  private final OwnedMcpTools tools;

  McpStreamableHttpTools(
      McpClientTransportFactory transportFactory,
      McpOwnedClientSettings settings,
      McpToolAdapterOptions options) {
    this.tools = new OwnedMcpTools(transportFactory, settings, options);
  }

  /**
   * Starts building a provider for a streamable HTTP server.
   *
   * @param baseUri the server's base URI, an absolute {@code http} or {@code https} URI with a host
   * @return a builder, never {@code null}
   * @throws IllegalArgumentException if the base URI is missing, unparseable, not HTTP, or has no
   *     host
   */
  public static Builder builder(String baseUri) {
    return new Builder(baseUri);
  }

  /**
   * Opens the session and completes the MCP handshake.
   *
   * <p>Calling this while connected is a no-op, and concurrent calls share one handshake.
   *
   * @return a stage completing when the server is ready, never {@code null}
   */
  public CompletionStage<Void> connect() {
    return tools.connect();
  }

  /**
   * Reads the server's whole tool catalogue.
   *
   * @return a stage completing with one tool per remote tool, never {@code null}
   */
  public CompletionStage<List<FunctionTool>> discoverTools() {
    return tools.discoverTools();
  }

  /**
   * Ends the session, leaving this object reusable.
   *
   * @return a stage completing when the client is released, never {@code null}
   */
  public CompletionStage<Void> close() {
    return tools.close();
  }

  /** Builds an {@link McpStreamableHttpTools}. */
  public static final class Builder {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final String DEFAULT_ENDPOINT = "/mcp";
    private static final String JSON_MAPPER_REQUIRED =
        "jsonMapper is required: this module ships no JSON implementation, so add"
            + " io.modelcontextprotocol.sdk:mcp-json-jackson2 or"
            + " io.modelcontextprotocol.sdk:mcp-json-jackson3 and pass its McpJsonMapper";
    private static final String SCHEMA_VALIDATOR_REQUIRED =
        "schemaValidator is required: this module ships no JsonSchemaValidator, so add"
            + " io.modelcontextprotocol.sdk:mcp-json-jackson2 or"
            + " io.modelcontextprotocol.sdk:mcp-json-jackson3 and pass its validator";

    private final String baseUri;
    private final URI parsedBaseUri;

    private String endpoint = DEFAULT_ENDPOINT;
    private McpJsonMapper jsonMapper;
    private JsonSchemaValidator schemaValidator;
    private Duration requestTimeout = DEFAULT_TIMEOUT;
    private Duration initializationTimeout = DEFAULT_TIMEOUT;
    private Duration connectTimeout = DEFAULT_CONNECT_TIMEOUT;
    private McpToolAdapterOptions toolOptions = McpToolAdapterOptions.defaults();

    private Builder(String baseUri) {
      this.parsedBaseUri = requireHttpUri(baseUri);
      this.baseUri = baseUri;
    }

    /**
     * Sets the MCP endpoint, relative to the base URI or absolute under it.
     *
     * @param endpoint the endpoint, never {@code null} or blank
     * @return this builder, never {@code null}
     * @throws IllegalArgumentException if the endpoint is {@code null}, blank, unparseable, or
     *     absolute and outside the base URI
     */
    public Builder endpoint(String endpoint) {
      if (endpoint == null) {
        throw new IllegalArgumentException("endpoint must not be null");
      }
      // Blank is rejected here rather than left to the SDK transport builder, which requires text
      // too but only runs at connect, and to Utils.resolveUri, which silently resolves a blank
      // endpoint to the base URI.
      if (endpoint.isBlank()) {
        throw new IllegalArgumentException("endpoint must not be blank");
      }
      // Parsed here only so the failure names the endpoint; Utils.resolveUri then applies the
      // pinned origin rule, and its message is the SDK's own and is left untouched.
      requireUri(endpoint, "endpoint");
      Utils.resolveUri(parsedBaseUri, endpoint);
      this.endpoint = endpoint;
      return this;
    }

    /**
     * Sets the JSON mapper used to encode and decode MCP messages.
     *
     * @param jsonMapper the mapper, never {@code null}
     * @return this builder, never {@code null}
     * @throws IllegalArgumentException if the mapper is {@code null}
     */
    public Builder jsonMapper(McpJsonMapper jsonMapper) {
      if (jsonMapper == null) {
        throw new IllegalArgumentException("jsonMapper must not be null");
      }
      this.jsonMapper = jsonMapper;
      return this;
    }

    /**
     * Sets the validator used for tool output schemas.
     *
     * @param schemaValidator the validator, never {@code null}
     * @return this builder, never {@code null}
     * @throws IllegalArgumentException if the validator is {@code null}
     */
    public Builder schemaValidator(JsonSchemaValidator schemaValidator) {
      if (schemaValidator == null) {
        throw new IllegalArgumentException("schemaValidator must not be null");
      }
      this.schemaValidator = schemaValidator;
      return this;
    }

    /**
     * Sets how long a single MCP request may take, 20 seconds by default.
     *
     * @param requestTimeout a positive duration, never {@code null}
     * @return this builder, never {@code null}
     */
    public Builder requestTimeout(Duration requestTimeout) {
      this.requestTimeout = requestTimeout;
      return this;
    }

    /**
     * Sets how long the handshake may take, 20 seconds by default.
     *
     * @param initializationTimeout a positive duration, never {@code null}
     * @return this builder, never {@code null}
     */
    public Builder initializationTimeout(Duration initializationTimeout) {
      this.initializationTimeout = initializationTimeout;
      return this;
    }

    /**
     * Sets how long establishing the HTTP connection may take, 10 seconds by default.
     *
     * @param connectTimeout a positive duration, never {@code null}
     * @return this builder, never {@code null}
     * @throws IllegalArgumentException if the duration is {@code null}, zero, or negative
     */
    public Builder connectTimeout(Duration connectTimeout) {
      if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()) {
        throw new IllegalArgumentException("connectTimeout must be positive");
      }
      this.connectTimeout = connectTimeout;
      return this;
    }

    /**
     * Sets how remote tools are mapped to framework tools.
     *
     * @param toolOptions the options, never {@code null}
     * @return this builder, never {@code null}
     * @throws IllegalArgumentException if the options are {@code null}
     */
    public Builder toolOptions(McpToolAdapterOptions toolOptions) {
      if (toolOptions == null) {
        throw new IllegalArgumentException("toolOptions must not be null");
      }
      this.toolOptions = toolOptions;
      return this;
    }

    /**
     * Builds the provider without opening a connection.
     *
     * @return a provider, never {@code null}
     * @throws IllegalArgumentException if a required value is missing, a timeout is {@code null} or
     *     not positive, or the tool options are {@code null}
     */
    public McpStreamableHttpTools build() {
      // The factory is built from arguments, not from this builder, so a builder reused after
      // build() cannot change what an already built provider connects to.
      return build(transportFactory(baseUri, endpoint, jsonMapper, connectTimeout));
    }

    /**
     * Builds the provider over a supplied transport factory.
     *
     * <p>Package-private: it exists so a test can drive the configured build end to end — the same
     * requirement checks, the same settings, the same tool options, the same provider — without an
     * {@code HttpClient}, which Java 17 cannot close. The public {@link #build()} is exactly this
     * method plus the one factory that builds an SDK HTTP transport, so nothing it verifies can
     * drift from what a caller gets. The factory is only called at {@link
     * McpStreamableHttpTools#connect() connect}, here as there.
     *
     * @param transportFactory creates each generation's transport, never {@code null}
     * @return a provider, never {@code null}
     * @throws IllegalArgumentException if the factory is {@code null}, a required value is missing,
     *     or a timeout is {@code null} or not positive
     */
    McpStreamableHttpTools build(McpClientTransportFactory transportFactory) {
      if (jsonMapper == null) {
        throw new IllegalArgumentException(JSON_MAPPER_REQUIRED);
      }
      if (schemaValidator == null) {
        throw new IllegalArgumentException(SCHEMA_VALIDATOR_REQUIRED);
      }
      return new McpStreamableHttpTools(
          transportFactory,
          new McpOwnedClientSettings(schemaValidator, requestTimeout, initializationTimeout),
          toolOptions);
    }

    /**
     * Returns the URI a connect would send MCP requests to.
     *
     * <p>Package-private: it exists so a test can read back the recorded base URI and endpoint
     * through the same resolution the SDK performs per request, without allocating the {@code
     * HttpClient} that building a real transport allocates.
     *
     * @return the resolved request URI, never {@code null}
     */
    URI requestUri() {
      return Utils.resolveUri(parsedBaseUri, endpoint);
    }

    /**
     * Returns the connect timeout a connect would use.
     *
     * <p>Package-private, and for the same reason as {@link #requestUri()}: the value the caller
     * configured only reaches the SDK inside the transport factory, which no test may run.
     *
     * @return the recorded timeout, never {@code null}
     */
    Duration connectTimeout() {
      return connectTimeout;
    }

    private static McpClientTransportFactory transportFactory(
        String baseUri, String endpoint, McpJsonMapper jsonMapper, Duration connectTimeout) {
      return () ->
          HttpClientStreamableHttpTransport.builder(baseUri)
              .jsonMapper(jsonMapper)
              .endpoint(endpoint)
              .connectTimeout(connectTimeout)
              .build();
    }

    private static URI requireHttpUri(String baseUri) {
      if (baseUri == null || baseUri.isBlank()) {
        throw new IllegalArgumentException("baseUri must not be null or blank");
      }
      URI parsed = requireUri(baseUri, "baseUri");
      String scheme = parsed.getScheme();
      if (scheme == null) {
        throw new IllegalArgumentException("baseUri must be absolute, but was: " + baseUri);
      }
      String lowered = scheme.toLowerCase(Locale.ROOT);
      if (!"http".equals(lowered) && !"https".equals(lowered)) {
        throw new IllegalArgumentException("baseUri must use http or https, but was: " + baseUri);
      }
      // A hostless HTTP URI parses, so only its use fails: the JDK request builder rejects the
      // resolved URI with "unsupported URI", and the SDK's absolute endpoint check dereferences the
      // base authority, so a same-scheme absolute endpoint would fail endpoint() with a
      // NullPointerException instead of a message naming the base URI.
      if (parsed.getHost() == null) {
        throw new IllegalArgumentException("baseUri must have a host, but was: " + baseUri);
      }
      return parsed;
    }

    private static URI requireUri(String value, String name) {
      try {
        return new URI(value);
      } catch (URISyntaxException failure) {
        throw new IllegalArgumentException(name + " is not a valid URI: " + value, failure);
      }
    }
  }
}
