package io.github.hellices.agentframework.mcp;

import io.github.hellices.agentframework.api.tool.FunctionTool;
import io.github.hellices.agentframework.mcp.internal.McpClientTransportFactory;
import io.github.hellices.agentframework.mcp.internal.McpOwnedClientSettings;
import io.github.hellices.agentframework.mcp.internal.OwnedMcpTools;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;

/**
 * Exposes the tools of a stdio MCP server as framework tools, owning the server connection.
 *
 * <p>This is the owned counterpart of {@link ConnectedMcpClientAdapter}. It creates the transport
 * and the client, drives the handshake, validates the connection before every call, replaces it at
 * most once when it is lost, and closes it. Because the server is a child process, nothing here
 * happens implicitly: {@link #connect()} is required before {@link #discoverTools()}, and an
 * operation attempted before connect or after close fails instead of starting a process.
 *
 * <p>{@link #close()} ends the current connection, not this object. Calling {@link #connect()}
 * again afterwards starts a new server process, which is what a caller that deliberately reconnects
 * wants.
 *
 * <p>The command line is read once, when the builder is created, and copied. Mutating the {@link
 * ServerParameters} afterwards therefore cannot change what a later {@link #connect()} runs, which
 * matters because the SDK type hands out its own argument list and environment map directly.
 *
 * <p>Three limitations come from official SDK 2.0.0 and are not worked around here.
 *
 * <ul>
 *   <li>{@code ServerParameters} carries a command, arguments, and environment only, so there is no
 *       working directory option; run a command that sets its own working directory if you need
 *       one.
 *   <li>There is no stream encoding option either. The SDK stdio transport writes requests as UTF-8
 *       bytes but decodes the server's output with the JVM default charset, so a server that emits
 *       anything else, or a JVM started with a non-UTF-8 default charset, garbles non-ASCII text.
 *       Run the JVM with {@code -Dfile.encoding=UTF-8} and a server that speaks UTF-8.
 *   <li>The SDK stdio transport exposes no connect timeout, so this builder has none. {@link
 *       Builder#initializationTimeout(Duration) initializationTimeout} still bounds the handshake
 *       that follows the process start.
 * </ul>
 *
 * <p>Instances are safe to share once constructed.
 */
public final class McpStdioTools {

  private final OwnedMcpTools tools;

  McpStdioTools(
      McpClientTransportFactory transportFactory,
      McpOwnedClientSettings settings,
      McpToolAdapterOptions options) {
    this.tools = new OwnedMcpTools(transportFactory, settings, options);
  }

  /**
   * Starts building a provider for a stdio server.
   *
   * @param serverParameters the command, arguments, and environment of the server process, never
   *     {@code null}
   * @return a builder, never {@code null}
   * @throws IllegalArgumentException if the parameters are {@code null}, the command is blank, or
   *     an argument, environment key, or environment value is {@code null}
   */
  public static Builder builder(ServerParameters serverParameters) {
    return new Builder(serverParameters);
  }

  /**
   * Starts the server process and completes the MCP handshake.
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
   * Ends the server connection, leaving this object reusable.
   *
   * @return a stage completing when the process and the client are released, never {@code null}
   */
  public CompletionStage<Void> close() {
    return tools.close();
  }

  /** Builds an {@link McpStdioTools}. */
  public static final class Builder {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(20);
    private static final String JSON_MAPPER_REQUIRED =
        "jsonMapper is required: this module ships no JSON implementation, so add"
            + " io.modelcontextprotocol.sdk:mcp-json-jackson2 or"
            + " io.modelcontextprotocol.sdk:mcp-json-jackson3 and pass its McpJsonMapper";
    private static final String SCHEMA_VALIDATOR_REQUIRED =
        "schemaValidator is required: this module ships no JsonSchemaValidator, so add"
            + " io.modelcontextprotocol.sdk:mcp-json-jackson2 or"
            + " io.modelcontextprotocol.sdk:mcp-json-jackson3 and pass its validator";

    private final String command;
    private final List<String> args;
    private final Map<String, String> env;

    private McpJsonMapper jsonMapper;
    private JsonSchemaValidator schemaValidator;
    private Duration requestTimeout = DEFAULT_TIMEOUT;
    private Duration initializationTimeout = DEFAULT_TIMEOUT;
    private McpToolAdapterOptions toolOptions = McpToolAdapterOptions.defaults();

    private Builder(ServerParameters serverParameters) {
      if (serverParameters == null) {
        throw new IllegalArgumentException("serverParameters must not be null");
      }
      // Copied rather than held: ServerParameters returns its own argument list and environment
      // map, so keeping the instance until connect would let a caller change the command line of a
      // process this object has already promised to start. The values are also checked here, where
      // the caller can still fix them, instead of surfacing as a process-start failure on an SDK
      // thread later.
      this.command = requireCommand(serverParameters.getCommand());
      this.args = copyArgs(serverParameters.getArgs());
      this.env = copyEnv(serverParameters.getEnv());
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
     * Sets how long a single MCP request may take.
     *
     * @param requestTimeout a positive duration, never {@code null}
     * @return this builder, never {@code null}
     * @throws IllegalArgumentException if the duration is {@code null}, zero, or negative
     */
    public Builder requestTimeout(Duration requestTimeout) {
      this.requestTimeout = requirePositive(requestTimeout, "requestTimeout");
      return this;
    }

    /**
     * Sets how long the handshake may take.
     *
     * @param initializationTimeout a positive duration, never {@code null}
     * @return this builder, never {@code null}
     * @throws IllegalArgumentException if the duration is {@code null}, zero, or negative
     */
    public Builder initializationTimeout(Duration initializationTimeout) {
      this.initializationTimeout = requirePositive(initializationTimeout, "initializationTimeout");
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
     * Builds the provider without starting the server process.
     *
     * @return a provider, never {@code null}
     * @throws IllegalArgumentException if a required value is missing, a timeout is {@code null} or
     *     not positive, or the tool options are {@code null}
     */
    public McpStdioTools build() {
      // Read into locals so the factory captures the recorded values rather than this builder: a
      // builder reused after build() must not be able to change what an already built provider
      // starts.
      String processCommand = command;
      List<String> processArgs = args;
      Map<String, String> processEnv = env;
      McpJsonMapper mapper = jsonMapper;
      return build(
          () ->
              new StdioClientTransport(
                  serverParameters(processCommand, processArgs, processEnv), mapper));
    }

    /**
     * Builds the provider over a supplied transport factory.
     *
     * <p>Package-private: it exists so a test can drive the configured build end to end — the same
     * requirement checks, the same settings, the same tool options, the same provider — without a
     * server process. The public {@link #build()} is exactly this method plus the one factory that
     * starts a process, so nothing it verifies can drift from what a caller gets. The factory is
     * only called at {@link McpStdioTools#connect() connect}, here as there.
     *
     * @param transportFactory creates each generation's transport, never {@code null}
     * @return a provider, never {@code null}
     * @throws IllegalArgumentException if a required value is missing, a timeout is {@code null} or
     *     not positive, or the tool options are {@code null}
     */
    McpStdioTools build(McpClientTransportFactory transportFactory) {
      if (jsonMapper == null) {
        throw new IllegalArgumentException(JSON_MAPPER_REQUIRED);
      }
      if (schemaValidator == null) {
        throw new IllegalArgumentException(SCHEMA_VALIDATOR_REQUIRED);
      }
      return new McpStdioTools(
          transportFactory,
          new McpOwnedClientSettings(schemaValidator, requestTimeout, initializationTimeout),
          toolOptions);
    }

    /**
     * Returns the parameters a connect would start the process with.
     *
     * <p>Package-private: it exists so a test can read back the recorded command line through the
     * same construction the factory uses, without allocating the three schedulers a {@code
     * StdioClientTransport} constructor allocates.
     *
     * @return freshly built parameters, never {@code null}
     */
    ServerParameters serverParameters() {
      return serverParameters(command, args, env);
    }

    private static ServerParameters serverParameters(
        String command, List<String> args, Map<String, String> env) {
      return ServerParameters.builder(command).args(args).env(env).build();
    }

    private static String requireCommand(String command) {
      if (command == null || command.isBlank()) {
        throw new IllegalArgumentException("command must not be blank");
      }
      return command;
    }

    private static Duration requirePositive(Duration duration, String name) {
      if (duration == null) {
        throw new IllegalArgumentException(name + " must not be null");
      }
      if (duration.isZero() || duration.isNegative()) {
        throw new IllegalArgumentException(name + " must be positive");
      }
      return duration;
    }

    private static List<String> copyArgs(List<String> args) {
      for (String arg : args) {
        if (arg == null) {
          throw new IllegalArgumentException("args must not contain null");
        }
      }
      return List.copyOf(args);
    }

    private static Map<String, String> copyEnv(Map<String, String> env) {
      Map<String, String> copy = new LinkedHashMap<>();
      for (Map.Entry<String, String> entry : env.entrySet()) {
        if (entry.getKey() == null || entry.getValue() == null) {
          throw new IllegalArgumentException("env must not contain a null key or value");
        }
        copy.put(entry.getKey(), entry.getValue());
      }
      return Map.copyOf(copy);
    }
  }
}
