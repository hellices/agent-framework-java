# Owned MCP Transport Lifecycle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add owned stdio and streamable HTTP MCP tool providers that create, initialize, validate,
reconnect once, and close their own MCP client, so an application can obtain `FunctionTool` instances
from an MCP server without ever touching the official SDK client itself.

**Architecture:** A single internal state machine, `OwnedMcpClientLifecycle`, owns exactly one
*generation* at a time. A generation is one freshly created `McpClientTransport` plus one
`McpAsyncClient` built on it; a generation is never reused after it is closed. `connect()` drives the
SDK handshake on a subscription that caller cancellation cannot dispose, and concurrent `connect()`
calls coalesce onto that one handshake. Every list or call operation first validates the current
generation with `ping`, unless that generation already learned that the server answers `ping` with
JSON-RPC `-32601`. A validation failure or a typed connection-loss failure spends a single reconnect
budget: the stale generation is closed and one replacement generation is created. A single-request
operation is then retried exactly once on the replacement. A paged operation is not: a whole
`discoverTools()` is one logical operation with one budget, and a replacement makes it discard every
page it accumulated and start again from `McpSchema.FIRST_PAGE`, because an MCP cursor belongs to the
session that issued it. Two public final facades, `McpStdioTools` and `McpStreamableHttpTools`, wrap
that state machine, build the SDK transport lazily inside a package-private
`McpClientTransportFactory` seam, and reuse `McpToolDiscovery` and `McpToolInvoker` for the tool
mapping.

**Tech Stack:** Java 17 source and target, Gradle Kotlin DSL with the `build-logic` included build,
official `io.modelcontextprotocol.sdk:mcp-core:2.0.0` (`McpAsyncClient`, `StdioClientTransport`,
`HttpClientStreamableHttpTransport`), Project Reactor `Mono` bridged to `CompletableFuture` through
the existing `AsyncStages` helper, JUnit 5 and AssertJ for tests.

## Global Constraints

- The module is `integrations/agent-framework-mcp`; its only production dependency on repository code
  is `:agent-framework-api`. Do not add a dependency on any engine, host, or provider module.
- No new Gradle dependency and no change to `integrations/agent-framework-mcp/gradle.lockfile`. Every
  type used in this plan is already on the module compile or test classpath.
- Java package root is `io.github.hellices.agentframework.mcp`. Never introduce `com.microsoft.*`.
- Our code creates no executor, scheduler, thread, shutdown hook, or global registry. The SDK
  allocates its own; we only make sure we close what we created.
- Every compile runs with `-Xlint:all -Werror`. A deprecation warning fails the build, so use only the
  non-deprecated SDK constructors named in "Verified SDK 2.0.0 facts" below.
- `quality` runs spotless with google-java-format, checkstyle with `maxWarnings = 0`, PMD, and
  SpotBugs at `Effort.MAX` / `Confidence.MEDIUM`. Blanket suppressions are prohibited; only narrow
  class-and-method `<Match>` entries with a justification comment in `config/spotbugs/exclude.xml`.
- PMD runs `CompareObjectsWithEquals`, so production code in this slice never compares two object
  references with `==` or `!=`. Where identity matters, compare a `long` generation counter instead.
  Comparisons against `null` are unaffected and are used throughout.
- Tests are deterministic. No real process, no real socket, no `Thread.sleep`, no wall-clock timeout as
  an assertion mechanism, no retry-on-flake.
- No external call runs while the lifecycle's state lock is held. The transport factory, the SDK
  client constructor, the handshake subscription, and every `closeGracefully()` are invoked outside
  `synchronized (lock)`, because each of them can block, start a process, or re-enter this class:
  verified SDK fact 15 says closing a client dismisses its in-flight requests synchronously, and
  those dismissals run our own failure handling on the closing thread.
- Never construct `StdioClientTransport` in a test. Its constructor allocates three single-thread
  schedulers before any process starts, and nothing closes them if the test does not connect.
- Scope exclusions for this slice, all deliberate: no discovery caching, no prompts, no resources, no
  sampling, no elicitation, no tasks, no request headers, no custom `HttpClient`, no redirect policy,
  no tracing or telemetry escape hatch, no OAuth. MCP-009 and MCP-010 stay `absent`.
- MCP-001 stays `partial`. Official SDK 2.0.0 ships no WebSocket client transport, so a WebSocket
  facade cannot be written against the pinned upstream snapshot.
- MCP-003 stays `partial`. Its third acceptance criterion requires `close()` to clear "the session,
  capability cache, sampling counter, and pending reload state". This slice has no sampling and no
  reload state to clear, so only the first two exist; claiming `implemented` would assert coverage of
  criteria whose subjects do not exist yet.
- The existing borrowed adapter `ConnectedMcpClientAdapter` does not change behavior, and no existing
  borrowed test is edited or deleted; they are the regression guard that owned code did not leak into
  borrowed ownership. Tests may be added to the borrowed suites, and Task 11 adds one, because adding
  a guard is the opposite of relaxing one. `BorrowedMcpAsyncOperations` is likewise untouched: the one
  new method on `McpAsyncOperations` is a `default` that returns `this`, so borrowed paging behaves
  identically before and after this slice.

## Verified SDK 2.0.0 facts

These were confirmed by disassembling `mcp-core-2.0.0.jar` and by throwaway probe tests that were run
and then deleted. Do not re-derive them; do not contradict them.

1. `new StdioClientTransport(ServerParameters, McpJsonMapper)` is the only public constructor. The
   constructor allocates three single-thread schedulers immediately.
2. `ServerParameters.builder(String command)` exposes only `args`, `arg`, `env`, `addEnvVar`, `build`.
   There is no working-directory and no encoding option. Document that limitation; do not fake it.
3. `HttpClientStreamableHttpTransport.builder(String baseUri).build()` allocates a
   `java.net.http.HttpClient`. It does **not** validate the endpoint against the base URI at build
   time; `io.modelcontextprotocol.util.Utils.resolveUri(URI, String)` is only called per request. This
   corrects the research report, which claimed construction-time validation.
4. `Utils.resolveUri(URI base, String endpoint)` is `public static`, allocates nothing, and throws
   `IllegalArgumentException("Absolute endpoint URL does not match the base URL.")` when an absolute
   endpoint is not under the base URI. Our HTTP builder calls it eagerly for early validation.
   Confirmed results: `("https://example.com/api", "/mcp")` resolves to `https://example.com/mcp`;
   `("https://example.com/api/", "mcp")` resolves to `https://example.com/api/mcp`;
   `("https://example.com/api", "https://example.com/api/mcp")` is accepted;
   `("https://example.com/api", "https://example.com/other")` and
   `("https://example.com/api", "https://evil.example.com/mcp")` both throw.
5. `McpClient.async(McpClientTransport)` returns `McpClient.AsyncSpec` with `requestTimeout(Duration)`,
   `initializationTimeout(Duration)`, `jsonSchemaValidator(JsonSchemaValidator)`, `clientInfo`,
   `capabilities`, and `build()`.
6. `McpAsyncClient` exposes `Mono<InitializeResult> initialize()`, `Mono<Object> ping()`,
   `boolean isInitialized()`, `Mono<Void> closeGracefully()`, `Mono<ListToolsResult> listTools(String,
   Map<String, Object>)`, `Mono<CallToolResult> callTool(CallToolRequest)`.
7. `McpAsyncClient.closeGracefully()` is **not** idempotent. Calling it twice closes the transport
   twice. The owner must guarantee idempotency itself.
8. After close, `McpAsyncClient` silently re-runs the whole handshake on the next operation. A test
   transport that stays usable after close therefore proves nothing, which is why Task 1 makes the
   in-memory transport reject sends once closed.
9. A server answering `ping` with JSON-RPC `-32601` surfaces as `io.modelcontextprotocol.spec.McpError`
   with `getJsonRpcError().code()` equal to `McpSchema.ErrorCodes.METHOD_NOT_FOUND`, unwrapped, when the
   client is already initialized.
10. A closed transport surfaces `McpTransportSessionClosedException`; a server-side session loss
    surfaces `McpTransportSessionNotFoundException`. Both are unwrapped and both extend
    `RuntimeException` with no shared MCP base type.
11. `McpTransportSessionClosedException(String)` is deprecated for removal. Use the no-argument
    constructor.
12. Disposing the subscription that drives `initialize()` wedges the SDK `LifecycleInitializer`: the
    next operation blocks for the whole initialization timeout and then fails, and no second
    `initialize` request is ever sent. This is why `connect()` must not let caller cancellation reach
    the handshake subscription.
13. `McpJsonDefaults` resolves `McpJsonMapper` and `JsonSchemaValidator` through `ServiceLoader`, and
    neither `mcp-json-jackson2` nor `mcp-json-jackson3` is on this module's classpath. Both must be
    caller-supplied and required, with a fail-fast message naming the JSON modules.
14. `McpSchema.CallToolRequest(String, Map, Map)` is the non-deprecated constructor. The two-argument
    form is deprecated.
15. Closing an `McpAsyncClient` dismisses its in-flight requests **synchronously, inside
    `closeGracefully()`**, with a bare `java.lang.RuntimeException("MCP session with server
    terminated")` that has no cause. That type carries no information, so this plan never matches on
    it by string or by class. Instead a generation records that its owner closed it, and that flag is
    what makes a dismissed sibling call retryable. This behavior also makes the reconnect tests
    deterministic: closing the stale generation delivers every sibling failure before
    `closeGracefully()` returns.
16. The `McpAsyncClient` constructor, reached from `McpClient.AsyncSpec.build()`, calls
    `transport.protocolVersions()` and `transport.setExceptionHandler(...)` on the transport it was
    given. `protocolVersions()` is a `default` method on `McpTransport`, so a test transport can
    override it to throw and make client construction fail *after* a transport already exists. That
    is the deterministic way to exercise the "transport created, client not built" path without a
    process or a socket.
17. `McpSchema.FIRST_PAGE` is a `public static final String` assigned `null` in the class
    initializer. Requesting the first page therefore sends `cursor = null`, and a test that records
    requested cursors observes `null` for the first page.
18. `McpAsyncClient.listTools(String cursor, Map<String, Object> meta)` sends `tools/list` with a
    `McpSchema.PaginatedRequest(cursor, meta)` as its params, and `PaginatedRequest.cursor()` reads
    that cursor back. The in-memory transport does not serialize, so a scripted answer receives that
    record instance directly.
19. `Mono.subscribe(Consumer, Consumer, Runnable)` reports a throw raised during subscription through
    the error consumer rather than to the caller, so the handshake's failure path is the normal path
    even when the assembly itself fails.

## Resolved contradiction

The research report treats `close()` as terminal for the owner object. It is not. `close()` terminates
one generation. The owner object is reusable: its initial state is disconnected, an explicit
`connect()` is always required before discovery or invocation, and an explicit `connect()` after
`close()` creates a fresh generation. Task 3 contains the test that proves close-then-connect works,
and Task 4 contains the test that proves a post-close operation fails without creating any transport.

## File Structure

Production, all under `integrations/agent-framework-mcp/src/main/java/io/github/hellices/agentframework/mcp/`:

- `internal/McpClientTransportFactory.java` (create) - one-method seam that produces a brand new
  `McpClientTransport`. The only place the SDK transport is constructed; the reason tests never touch a
  process or a socket.
- `internal/McpOwnedClientSettings.java` (create) - validated value object carrying the JSON schema
  validator, request timeout, and initialization timeout used to build every generation.
- `internal/OwnedMcpClientLifecycle.java` (create) - the state machine: generation ownership, connect
  coalescing, idempotent close, ping validation, capability cache, single reconnect, single retry.
- `internal/OwnedMcpAsyncOperations.java` (create) - implements the existing `McpAsyncOperations` port
  by routing `listTools` and `callTool` through the lifecycle, so `McpToolDiscovery` and
  `McpToolInvoker` are reused untouched.
- `internal/McpFailures.java` (create) - pure failure classification: unsupported ping, connection loss.
- `internal/McpConnectionReplacedException.java` (create) - package-private internal signal that a
  paged operation ran into a replacement generation and must start again from the first page.
- `internal/OwnedMcpTools.java` (create) - the shared body of both public facades: it owns one
  lifecycle and one `McpToolDiscovery` and exposes connect, discover, and close. Both facades hold one
  of these and forward to it, so the transport-specific code in each facade is only its builder.
- `McpStdioTools.java` (create) - public final owned facade for a stdio server process.
- `McpStreamableHttpTools.java` (create) - public final owned facade for a streamable HTTP server.
- `package-info.java` (modify) - the package summary currently promises the module never opens or closes
  a client. That stops being true in Task 11.
- `internal/McpAsyncOperations.java` (modify) - one new `default` method, `forPagedOperation()`,
  returning `this`. It is the seam that lets an owned operations port give a whole paged read one
  shared reconnect budget while leaving the borrowed port's behavior identical.
- `internal/McpToolDiscovery.java` (modify) - obtain the paged scope once per `discover()`, and
  restart the whole read from `McpSchema.FIRST_PAGE` when a page reports a replacement generation.
- `internal/package-info.java` (unchanged).
- `ConnectedMcpClientAdapter.java`, `McpToolAdapterOptions.java`, `internal/McpToolInvoker.java`,
  `internal/AsyncStages.java`, `internal/BorrowedMcpAsyncOperations.java` (unchanged).

Tests, under `integrations/agent-framework-mcp/src/test/java/io/github/hellices/agentframework/mcp/`:

- `InMemoryMcpTransport.java` (modify) - reject sends after close, script JSON-RPC error answers, script
  send failures, script close failures, count occurrences of a method, answer `ping` by default helper.
- `ScriptedMcpTransportFactory.java` (create) - hands out pre-built in-memory transports in order and
  counts how many generations the lifecycle asked for.
- `ClientHostileMcpTransport.java` (create) - a transport whose `protocolVersions()` throws, so client
  construction fails after the transport exists. It is the only way to exercise that path without a
  process or a socket.
- `InMemoryMcpTransportTest.java` (create) - fixture self-test, because an unfaithful fixture silently
  invalidates every owned test.
- `OwnedMcpClientConnectTest.java` (create)
- `OwnedMcpClientCloseTest.java` (create)
- `OwnedMcpClientOperationTest.java` (create)
- `internal/McpFailuresTest.java` (create) - lives in the internal package because `McpFailures` is
  package-private.
- `OwnedMcpClientValidationTest.java` (create)
- `OwnedMcpClientRetryTest.java` (create)
- `McpDiscoveryPagingTest.java` (create) - paging across a replacement generation, for both ownership
  models.
- `RejectingMcpJsonMapper.java` (create) - an `McpJsonMapper` that throws from every method. The stdio
  builder requires a mapper, and no test in this module ever serializes anything, so a mapper that
  fails loudly is safer than one that quietly works.
- `McpStdioToolsTest.java` (create)
- `McpStreamableHttpToolsTest.java` (create)
- `BorrowedMcpClientIntegrationTest.java` (modify) - one added test: two adapters over one client, so
  the MCP-002 acceptance criterion about shared connections has an executable test. Every existing
  test in it stays unchanged and runs as regression.
- `ConnectedMcpClientAdapterTest.java`, `PermissiveJsonSchemaValidator.java`,
  `FakeMcpAsyncOperations.java` (unchanged, run as regression).

Documentation:

- `docs/design/requirements-design/requirements-traceability-matrix.md` (modify)
- `docs/design/requirements-design/02-state-extension-mcp.md` (modify)
- `docs/design/module-composition.md` (modify)
- `README.md` (modify)

---

### Task 1: Owned-lifecycle test transport fidelity

The in-memory transport shipped with the borrowed adapter stays usable after close and can only
answer successfully. Owned tests need a transport that behaves like a real one: single use, able to
return a JSON-RPC error, able to fail a send, and able to fail its own close. Without this, verified
SDK fact 8 means every owned test would silently pass against a client that re-ran its handshake.

**Files:**
- Modify: `integrations/agent-framework-mcp/src/test/java/io/github/hellices/agentframework/mcp/InMemoryMcpTransport.java`
- Test: `integrations/agent-framework-mcp/src/test/java/io/github/hellices/agentframework/mcp/InMemoryMcpTransportTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces, all package-private in `io.github.hellices.agentframework.mcp`:
  - `InMemoryMcpTransport answering(String method, Function<Object, Object> answer)` (existing)
  - `InMemoryMcpTransport answeringPing()`
  - `InMemoryMcpTransport answeringWithError(String method, int code, String message)`
  - `InMemoryMcpTransport failingSend(String method, Supplier<Throwable> failure)`
  - `InMemoryMcpTransport failingClose(Supplier<Throwable> failure)`
  - `InMemoryMcpTransport withholding(String method)` (existing)
  - `void releaseWithheld()` (existing)
  - `List<String> methodsSent()` (existing), `Object lastRequestFor(String method)` (existing),
    `int closeCount()` (existing)
  - `int countOf(String method)`
  - `boolean isClosed()`

- [ ] **Step 1: Write the failing fixture test**

Create `integrations/agent-framework-mcp/src/test/java/io/github/hellices/agentframework/mcp/InMemoryMcpTransportTest.java`:

```java
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
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :integrations:agent-framework-mcp:test --tests 'io.github.hellices.agentframework.mcp.InMemoryMcpTransportTest'`

Expected: compilation FAILS with `cannot find symbol: method answeringPing()`, `answeringWithError`,
`failingSend`, `failingClose`, `countOf`, `isClosed`.

- [ ] **Step 3: Replace the in-memory transport with the owned-capable version**

Replace the whole body of
`integrations/agent-framework-mcp/src/test/java/io/github/hellices/agentframework/mcp/InMemoryMcpTransport.java`
with:

```java
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
 * and will happily run a second handshake, so a transport that stayed usable would make every
 * owned close and reconnect assertion vacuous.
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
  private final Set<String> withholdMethods = new LinkedHashSet<>();

  private Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> inbound;
  private Supplier<Throwable> closeFailure;

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
   * <p>Withholding stops permanently, so a request that arrives after this call is answered at once.
   * A test that released a withheld handshake and then triggered a second one would otherwise wait
   * forever for a response nobody is going to release.
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
    return Mono.defer(
        () -> {
          closeCount.incrementAndGet();
          closed.set(true);
          Supplier<Throwable> failure = closeFailure;
          return failure == null ? Mono.empty() : Mono.error(failure.get());
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
```

- [ ] **Step 4: Run the fixture test to verify it passes**

Run: `./gradlew :integrations:agent-framework-mcp:test --tests 'io.github.hellices.agentframework.mcp.InMemoryMcpTransportTest'`

Expected: PASS, 6 tests.

- [ ] **Step 5: Run the borrowed regression suite**

Run: `./gradlew :integrations:agent-framework-mcp:test`

Expected: PASS. `BorrowedMcpClientIntegrationTest` and `ConnectedMcpClientAdapterTest` must be green
without edits. If a borrowed test now fails because the transport rejects a send after close, that is
a real finding: report it rather than weakening the fixture.

- [ ] **Step 6: Run quality on the module**

Run: `./gradlew :integrations:agent-framework-mcp:quality`

Expected: PASS. If spotless reports a formatting diff, run
`./gradlew :integrations:agent-framework-mcp:spotlessApply` and rerun.

- [ ] **Step 7: Commit**

```bash
git add integrations/agent-framework-mcp/src/test/java/io/github/hellices/agentframework/mcp/InMemoryMcpTransport.java \
        integrations/agent-framework-mcp/src/test/java/io/github/hellices/agentframework/mcp/InMemoryMcpTransportTest.java
git commit -m "$(cat <<'MSG'
mcp: make the in-memory transport single use

The owned lifecycle tests need a transport that behaves like a real one. The
SDK client does not remember that it was closed and silently runs a second
handshake, so a transport that stayed usable after close would make every
owned close and reconnect assertion vacuous.

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
MSG
)"
```

---

### Task 2: Transport factory seam, owned settings, and the first generation

A generation is one freshly created transport plus one client built on it. This task creates a
generation, drives the handshake so caller cancellation cannot wedge it, coalesces concurrent
connects, and cleans up a generation whose handshake failed.

**Files:**
- Create: `integrations/agent-framework-mcp/src/main/java/io/github/hellices/agentframework/mcp/internal/McpClientTransportFactory.java`
- Create: `integrations/agent-framework-mcp/src/main/java/io/github/hellices/agentframework/mcp/internal/McpOwnedClientSettings.java`
- Create: `integrations/agent-framework-mcp/src/main/java/io/github/hellices/agentframework/mcp/internal/OwnedMcpClientLifecycle.java`
- Create: `integrations/agent-framework-mcp/src/test/java/io/github/hellices/agentframework/mcp/ScriptedMcpTransportFactory.java`
- Create: `integrations/agent-framework-mcp/src/test/java/io/github/hellices/agentframework/mcp/ClientHostileMcpTransport.java`
- Test: `integrations/agent-framework-mcp/src/test/java/io/github/hellices/agentframework/mcp/OwnedMcpClientConnectTest.java`

**Interfaces:**
- Consumes: `InMemoryMcpTransport` from Task 1; the existing package-private
  `AsyncStages.fromMono/failed/unwrap` in `io.github.hellices.agentframework.mcp.internal`.
- Produces:
  - `public interface McpClientTransportFactory { McpClientTransport create(); }`
  - `public final class McpOwnedClientSettings` with
    `McpOwnedClientSettings(JsonSchemaValidator schemaValidator, Duration requestTimeout, Duration
    initializationTimeout)`, `JsonSchemaValidator schemaValidator()`, `Duration requestTimeout()`,
    `Duration initializationTimeout()`.
  - `public final class OwnedMcpClientLifecycle` with
    `OwnedMcpClientLifecycle(McpClientTransportFactory transportFactory, McpOwnedClientSettings
    settings)` and `CompletableFuture<Void> connect()`. `close()` arrives in Task 3 and
    `execute(...)` in Task 4.
  - `ScriptedMcpTransportFactory implements McpClientTransportFactory` with
    `ScriptedMcpTransportFactory(InMemoryMcpTransport... transports)`, `int createdCount()`,
    `InMemoryMcpTransport created(int index)`.
  - `ClientHostileMcpTransport implements McpClientTransport` with `int closeCount()` and a
    `protocolVersions()` that throws, so client construction fails after a transport exists.

- [ ] **Step 1: Write the failing connect test**

Create `integrations/agent-framework-mcp/src/test/java/io/github/hellices/agentframework/mcp/OwnedMcpClientConnectTest.java`:

```java
package io.github.hellices.agentframework.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hellices.agentframework.mcp.internal.McpClientTransportFactory;
import io.github.hellices.agentframework.mcp.internal.McpOwnedClientSettings;
import io.github.hellices.agentframework.mcp.internal.OwnedMcpClientLifecycle;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Connect behaviour of the owned lifecycle.
 *
 * <p>Every test drives the handshake through scripted in-memory transports, so no process is
 * started and no socket is opened. The transport factory counts how many generations were asked
 * for, which is the observable that separates "one generation" from "one generation per caller".
 */
class OwnedMcpClientConnectTest {

  private static final Duration SETTLE = Duration.ofSeconds(5);

  @Test
  void connectDrivesExactlyOneHandshakeAndOwnsTheGeneration() {
    InMemoryMcpTransport transport = new InMemoryMcpTransport().answeringPing();
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(transport);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());

    lifecycle.connect().join();

    assertThat(factory.createdCount()).isEqualTo(1);
    assertThat(transport.methodsSent())
        .containsExactly(McpSchema.METHOD_INITIALIZE, McpSchema.METHOD_NOTIFICATION_INITIALIZED);
    assertThat(transport.closeCount()).isZero();
  }

  @Test
  void repeatedConnectWhileConnectedCreatesNoSecondGeneration() {
    InMemoryMcpTransport transport = new InMemoryMcpTransport().answeringPing();
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(transport);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());

    lifecycle.connect().join();
    lifecycle.connect().join();
    lifecycle.connect().join();

    assertThat(factory.createdCount()).isEqualTo(1);
    assertThat(transport.countOf(McpSchema.METHOD_INITIALIZE)).isEqualTo(1);
  }

  @Test
  void concurrentConnectsCoalesceOntoOneHandshake() {
    InMemoryMcpTransport transport =
        new InMemoryMcpTransport().answeringPing().withholding(McpSchema.METHOD_INITIALIZE);
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(transport);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());

    CompletableFuture<Void> first = lifecycle.connect();
    CompletableFuture<Void> second = lifecycle.connect();

    assertThat(first).isNotDone();
    assertThat(second).isNotDone();
    assertThat(factory.createdCount()).isEqualTo(1);

    transport.releaseWithheld();

    assertThat(first).succeedsWithin(SETTLE);
    assertThat(second).succeedsWithin(SETTLE);
    assertThat(transport.countOf(McpSchema.METHOD_INITIALIZE)).isEqualTo(1);
  }

  @Test
  void callerCancellationCannotWedgeTheHandshake() {
    InMemoryMcpTransport transport =
        new InMemoryMcpTransport().answeringPing().withholding(McpSchema.METHOD_INITIALIZE);
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(transport);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());

    CompletableFuture<Void> cancelled = lifecycle.connect();
    assertThat(cancelled.cancel(true)).isTrue();

    transport.releaseWithheld();

    assertThat(lifecycle.connect()).succeedsWithin(SETTLE);
    assertThat(factory.createdCount()).isEqualTo(1);
    assertThat(transport.countOf(McpSchema.METHOD_INITIALIZE)).isEqualTo(1);
  }

  @Test
  void aFailedHandshakeClosesTheNewGenerationAndLeavesTheOwnerDisconnected() {
    InMemoryMcpTransport failing =
        new InMemoryMcpTransport()
            .failingSend(McpSchema.METHOD_INITIALIZE, () -> new IllegalStateException("no server"));
    InMemoryMcpTransport healthy = new InMemoryMcpTransport().answeringPing();
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(failing, healthy);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());

    assertThatThrownBy(() -> lifecycle.connect().join())
        .hasRootCauseInstanceOf(IllegalStateException.class);
    assertThat(failing.closeCount()).isEqualTo(1);

    lifecycle.connect().join();

    assertThat(factory.createdCount()).isEqualTo(2);
    assertThat(healthy.countOf(McpSchema.METHOD_INITIALIZE)).isEqualTo(1);
  }

  @Test
  void aFailedHandshakeReportsACleanupFailureAsSuppressed() {
    InMemoryMcpTransport failing =
        new InMemoryMcpTransport()
            .failingSend(McpSchema.METHOD_INITIALIZE, () -> new IllegalStateException("no server"))
            .failingClose(() -> new IllegalStateException("cleanup failed"));
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(failing);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());

    assertThatThrownBy(() -> lifecycle.connect().join())
        .satisfies(
            failure ->
                assertThat(failure.getCause().getSuppressed())
                    .anySatisfy(
                        suppressed -> assertThat(suppressed).hasMessage("cleanup failed")));
  }

  @Test
  void aTransportFactoryFailureLeavesTheOwnerDisconnected() {
    ScriptedMcpTransportFactory empty = new ScriptedMcpTransportFactory();
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(empty, settings());

    assertThatThrownBy(() -> lifecycle.connect().join())
        .hasRootCauseInstanceOf(IllegalStateException.class);
    assertThat(empty.createdCount()).isZero();
  }

  @Test
  void aClientBuildFailureClosesTheTransportOnceAndLeavesTheOwnerDisconnected() {
    ClientHostileMcpTransport hostile = new ClientHostileMcpTransport();
    AtomicInteger created = new AtomicInteger();
    McpClientTransportFactory factory =
        () -> {
          created.incrementAndGet();
          return hostile;
        };
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());

    // Assigning the stage is itself an assertion: connect() must report a build failure through the
    // returned stage, so a synchronous throw fails on this line rather than on the next one.
    CompletableFuture<Void> first = lifecycle.connect();

    assertThatThrownBy(first::join)
        .hasRootCauseInstanceOf(IllegalStateException.class)
        .hasRootCauseMessage("transport refuses to negotiate");
    assertThat(hostile.closeCount()).isEqualTo(1);

    // A failed start must leave nothing adopted, so the next connect has to ask for a new transport
    // and clean up after itself exactly as the first one did.
    assertThatThrownBy(() -> lifecycle.connect().join())
        .hasRootCauseInstanceOf(IllegalStateException.class);
    assertThat(created.get()).isEqualTo(2);
    assertThat(hostile.closeCount()).isEqualTo(2);
  }

  @Test
  void rejectsMissingCollaborators() {
    assertThatThrownBy(() -> new OwnedMcpClientLifecycle(null, settings()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("transportFactory must not be null");
    assertThatThrownBy(
            () -> new OwnedMcpClientLifecycle(new ScriptedMcpTransportFactory(), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("settings must not be null");
    assertThatThrownBy(
            () ->
                new McpOwnedClientSettings(
                    new PermissiveJsonSchemaValidator(), Duration.ZERO, Duration.ofSeconds(5)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("requestTimeout must be positive");
  }

  private static McpOwnedClientSettings settings() {
    return new McpOwnedClientSettings(
        new PermissiveJsonSchemaValidator(), Duration.ofSeconds(5), Duration.ofSeconds(5));
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :integrations:agent-framework-mcp:test --tests 'io.github.hellices.agentframework.mcp.OwnedMcpClientConnectTest'`

Expected: compilation FAILS with `package io.github.hellices.agentframework.mcp.internal does not
contain OwnedMcpClientLifecycle`, `cannot find symbol: class ScriptedMcpTransportFactory`, and
`cannot find symbol: class ClientHostileMcpTransport`.

- [ ] **Step 3: Create the transport factory seam**

Create `integrations/agent-framework-mcp/src/main/java/io/github/hellices/agentframework/mcp/internal/McpClientTransportFactory.java`:

```java
package io.github.hellices.agentframework.mcp.internal;

import io.modelcontextprotocol.spec.McpClientTransport;

/**
 * Creates one brand new MCP client transport.
 *
 * <p>The seam exists because a transport is not reusable. The stdio transport starts a process and
 * allocates schedulers in its constructor, and the streamable HTTP transport binds one session, so a
 * generation that was closed can never be revived. Making creation a factory call keeps that rule
 * enforceable and lets a test hand out in-memory transports instead of processes and sockets.
 *
 * <p>Implementations must return a transport that has never been connected.
 */
@FunctionalInterface
public interface McpClientTransportFactory {

  /**
   * Creates one transport.
   *
   * @return a new, unconnected transport, never {@code null}
   */
  McpClientTransport create();
}
```

- [ ] **Step 4: Create the owned client settings**

Create `integrations/agent-framework-mcp/src/main/java/io/github/hellices/agentframework/mcp/internal/McpOwnedClientSettings.java`:

```java
package io.github.hellices.agentframework.mcp.internal;

import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import java.time.Duration;

/**
 * Settings applied to every client generation the owned lifecycle builds.
 *
 * <p>The schema validator is required rather than defaulted. The SDK resolves its default through a
 * service loader that only the JSON binding modules provide, and this module deliberately does not
 * depend on one, so a missing validator has to fail where the caller can fix it.
 */
public final class McpOwnedClientSettings {

  private final JsonSchemaValidator schemaValidator;
  private final Duration requestTimeout;
  private final Duration initializationTimeout;

  /**
   * Creates settings.
   *
   * @param schemaValidator validator applied to tool output schemas, never {@code null}
   * @param requestTimeout timeout for a single request, must be positive
   * @param initializationTimeout timeout for the handshake, must be positive
   * @throws IllegalArgumentException if an argument is {@code null} or a timeout is not positive
   */
  public McpOwnedClientSettings(
      JsonSchemaValidator schemaValidator, Duration requestTimeout, Duration initializationTimeout) {
    if (schemaValidator == null) {
      throw new IllegalArgumentException("schemaValidator must not be null");
    }
    this.requestTimeout = requirePositive(requestTimeout, "requestTimeout");
    this.initializationTimeout = requirePositive(initializationTimeout, "initializationTimeout");
    this.schemaValidator = schemaValidator;
  }

  /**
   * Returns the validator applied to tool output schemas.
   *
   * @return the validator, never {@code null}
   */
  public JsonSchemaValidator schemaValidator() {
    return schemaValidator;
  }

  /**
   * Returns the timeout for a single request.
   *
   * @return the timeout, never {@code null}
   */
  public Duration requestTimeout() {
    return requestTimeout;
  }

  /**
   * Returns the timeout for the handshake.
   *
   * @return the timeout, never {@code null}
   */
  public Duration initializationTimeout() {
    return initializationTimeout;
  }

  private static Duration requirePositive(Duration value, String name) {
    if (value == null) {
      throw new IllegalArgumentException(name + " must not be null");
    }
    if (value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(name + " must be positive");
    }
    return value;
  }
}
```

- [ ] **Step 5: Create the lifecycle with connect only**

Create `integrations/agent-framework-mcp/src/main/java/io/github/hellices/agentframework/mcp/internal/OwnedMcpClientLifecycle.java`:

```java
package io.github.hellices.agentframework.mcp.internal;

import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.spec.McpClientTransport;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns the MCP client the adapter talks to.
 *
 * <p>The lifecycle holds at most one generation. A generation is one freshly created transport plus
 * one client built on it, and it is never reused after it was closed, because neither the stdio nor
 * the streamable HTTP transport can be revived.
 *
 * <p>The owner object itself is reusable. Its initial state is disconnected, an explicit
 * {@link #connect()} is always required before any operation, and an explicit {@link #connect()}
 * after a close creates a fresh generation. No operation ever starts a process or opens a
 * connection on its own.
 *
 * <p>The handshake runs on a subscription the caller cannot dispose. Disposing it would leave the
 * SDK initializer holding a permanently pending initialization, after which every later operation
 * blocks for the initialization timeout and no second handshake is ever attempted.
 */
public final class OwnedMcpClientLifecycle {

  private final McpClientTransportFactory transportFactory;
  private final McpOwnedClientSettings settings;
  private final Object lock = new Object();

  private CompletableFuture<Generation> pending;
  private Generation current;
  private long epoch;

  /**
   * Creates a lifecycle.
   *
   * @param transportFactory creates each generation's transport, never {@code null}
   * @param settings settings applied to each generation's client, never {@code null}
   * @throws IllegalArgumentException if an argument is {@code null}
   */
  public OwnedMcpClientLifecycle(
      McpClientTransportFactory transportFactory, McpOwnedClientSettings settings) {
    if (transportFactory == null) {
      throw new IllegalArgumentException("transportFactory must not be null");
    }
    if (settings == null) {
      throw new IllegalArgumentException("settings must not be null");
    }
    this.transportFactory = transportFactory;
    this.settings = settings;
  }

  /**
   * Connects, creating and initializing a generation if there is none.
   *
   * <p>Calling this while connected is a no-op, and concurrent calls join the same handshake.
   * Cancelling the returned stage abandons the caller's interest only; the handshake keeps running
   * so the generation stays usable.
   *
   * @return a stage completing when a generation is ready, never {@code null}
   */
  public CompletableFuture<Void> connect() {
    return connectGeneration().thenApply(generation -> null);
  }

  private CompletableFuture<Generation> connectGeneration() {
    CompletableFuture<Generation> handshake;
    synchronized (lock) {
      if (current != null) {
        return CompletableFuture.completedFuture(current);
      }
      if (pending != null) {
        return pending.thenApply(generation -> generation);
      }
      handshake = adopt(++epoch);
    }
    mirror(startGeneration(), handshake);
    return handshake.thenApply(generation -> generation);
  }

  /**
   * Registers a fresh promise as the pending handshake. The caller must hold {@code lock}.
   *
   * <p>The promise is published inside the lock but completed outside it. That ordering is what
   * makes concurrent connects coalesce: a second caller that takes the lock while the first is still
   * building a transport already sees a pending handshake to join.
   *
   * <p>The ticket is a counter rather than the handshake future itself so that nothing in this class
   * has to compare two object references for identity, and so that {@code close()} can orphan a
   * handshake simply by moving the counter on.
   */
  private CompletableFuture<Generation> adopt(long ticket) {
    CompletableFuture<Generation> handshake = new CompletableFuture<>();
    pending = handshake;
    handshake.whenComplete((generation, failure) -> publish(ticket, generation));
    return handshake;
  }

  private void publish(long ticket, Generation generation) {
    synchronized (lock) {
      if (ticket != epoch) {
        return;
      }
      pending = null;
      current = generation;
    }
  }

  /**
   * Builds one generation. Never called while {@code lock} is held, and never throws: every failure
   * is reported through the returned stage, so a caller that already published a pending handshake
   * cannot be left with a promise nobody completes.
   *
   * <p>The three steps fail differently. A factory failure leaves nothing to clean up. A client
   * build failure leaves a live transport that only this method still knows about, so it closes it.
   * A subscription failure leaves a client that owns the transport, so the generation closes it,
   * once, through the same idempotent path every other close uses.
   */
  private CompletableFuture<Generation> startGeneration() {
    McpClientTransport transport;
    try {
      transport = transportFactory.create();
      if (transport == null) {
        throw new IllegalStateException("transportFactory returned no transport");
      }
    } catch (RuntimeException failure) {
      return AsyncStages.failed(failure);
    }
    McpAsyncClient client;
    try {
      client =
          McpClient.async(transport)
              .requestTimeout(settings.requestTimeout())
              .initializationTimeout(settings.initializationTimeout())
              .jsonSchemaValidator(settings.schemaValidator())
              .build();
    } catch (RuntimeException failure) {
      return reportAfterCleanup(closeUnowned(transport), failure);
    }
    Generation generation = new Generation(client);
    CompletableFuture<Generation> handshake = new CompletableFuture<>();
    try {
      client
          .initialize()
          .subscribe(
              result -> handshake.complete(generation),
              failure -> closeAfterFailedHandshake(generation, failure, handshake),
              () -> handshake.complete(generation));
    } catch (RuntimeException failure) {
      return reportAfterCleanup(generation.close(), failure);
    }
    return handshake;
  }

  private static void closeAfterFailedHandshake(
      Generation generation, Throwable failure, CompletableFuture<Generation> handshake) {
    generation
        .close()
        .whenComplete(
            (ignored, cleanupFailure) ->
                handshake.completeExceptionally(reported(failure, cleanupFailure)));
  }

  /** Closes a transport no client ever took ownership of. */
  private static CompletableFuture<Void> closeUnowned(McpClientTransport transport) {
    try {
      return AsyncStages.fromMono(transport.closeGracefully());
    } catch (RuntimeException failure) {
      return AsyncStages.failed(failure);
    }
  }

  private static CompletableFuture<Generation> reportAfterCleanup(
      CompletableFuture<Void> cleanup, Throwable failure) {
    CompletableFuture<Generation> failed = new CompletableFuture<>();
    cleanup.whenComplete(
        (ignored, cleanupFailure) ->
            failed.completeExceptionally(reported(failure, cleanupFailure)));
    return failed;
  }

  private static Throwable reported(Throwable failure, Throwable cleanupFailure) {
    Throwable reported = AsyncStages.unwrap(failure);
    if (cleanupFailure != null) {
      reported.addSuppressed(AsyncStages.unwrap(cleanupFailure));
    }
    return reported;
  }

  /** Completes {@code target} with whatever {@code source} produced, unwrapping the failure. */
  private static <T> void mirror(CompletableFuture<T> source, CompletableFuture<T> target) {
    source.whenComplete(
        (value, failure) -> {
          if (failure == null) {
            target.complete(value);
          } else {
            target.completeExceptionally(AsyncStages.unwrap(failure));
          }
        });
  }

  /** One transport and one client, closed at most once. */
  private static final class Generation {

    private final McpAsyncClient client;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CompletableFuture<Void> closure = new CompletableFuture<>();

    Generation(McpAsyncClient client) {
      this.client = client;
    }

    McpAsyncClient client() {
      return client;
    }

    CompletableFuture<Void> close() {
      if (!closed.compareAndSet(false, true)) {
        return closure;
      }
      mirror(AsyncStages.fromMono(client.closeGracefully()), closure);
      return closure;
    }
  }
}
```

Note on the handshake subscription: `initialize()` is driven with `Mono.subscribe`, not with
`AsyncStages.fromMono`, precisely because `AsyncStages.fromMono` disposes its subscription when the
returned future is cancelled. `connect()` hands the caller a dependent future created by
`thenApply`, so cancelling it never reaches the handshake.

Note on the lock: `startGeneration()` runs outside `synchronized (lock)` because every one of its
three steps calls out of this class. The factory may start a process, `build()` reads the transport,
and `subscribe` can run the whole handshake and its failure handling on the calling thread. Holding
the lock across any of those would let an unrelated caller's `connect()` block behind a process
start, and would let a close-driven failure re-enter this class on a thread that already holds the
lock. `adopt` still publishes the pending handshake inside the lock, which is what makes concurrent
connects coalesce onto one generation.

Note on `catch (RuntimeException)` around `subscribe`: verified SDK fact 19 says Reactor reports a
throw raised during subscription through the error consumer, so this catch is expected to stay cold.
It is present because `client.initialize()` itself is a plain method call that runs before any
Reactor machinery, and because the cost of being wrong here is a leaked process rather than a failed
request.

`Generation.client()` is unused until Task 4. Leave it; Task 4's `execute` is its only caller and
splitting it out would churn the file twice.

- [ ] **Step 6: Create the scripted transport factory fixture**

Create `integrations/agent-framework-mcp/src/test/java/io/github/hellices/agentframework/mcp/ScriptedMcpTransportFactory.java`:

```java
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
```

- [ ] **Step 7: Create the client-hostile transport fixture**

Create `integrations/agent-framework-mcp/src/test/java/io/github/hellices/agentframework/mcp/ClientHostileMcpTransport.java`:

```java
package io.github.hellices.agentframework.mcp;

import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
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

  @Override
  public List<String> protocolVersions() {
    throw new IllegalStateException("transport refuses to negotiate");
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
    return Mono.fromRunnable(closeCount::incrementAndGet);
  }

  int closeCount() {
    return closeCount.get();
  }
}
```

- [ ] **Step 8: Run the connect test to verify it passes**

Run: `./gradlew :integrations:agent-framework-mcp:test --tests 'io.github.hellices.agentframework.mcp.OwnedMcpClientConnectTest'`

Expected: PASS, 9 tests.

- [ ] **Step 9: Run the module test and quality tasks**

Run: `./gradlew :integrations:agent-framework-mcp:test :integrations:agent-framework-mcp:quality`

Expected: PASS. If SpotBugs reports `EI_EXPOSE_REP2` on `McpOwnedClientSettings` or
`OwnedMcpClientLifecycle`, add a narrow class-and-method `<Match>` to `config/spotbugs/exclude.xml`
following the existing MCP entries, with a comment saying the collaborators are borrowed on purpose.
Do not add a blanket suppression and do not disable the detector.

- [ ] **Step 10: Commit**

```bash
git add integrations/agent-framework-mcp/src/main/java/io/github/hellices/agentframework/mcp/internal/McpClientTransportFactory.java \
        integrations/agent-framework-mcp/src/main/java/io/github/hellices/agentframework/mcp/internal/McpOwnedClientSettings.java \
        integrations/agent-framework-mcp/src/main/java/io/github/hellices/agentframework/mcp/internal/OwnedMcpClientLifecycle.java \
        integrations/agent-framework-mcp/src/test/java/io/github/hellices/agentframework/mcp/ScriptedMcpTransportFactory.java \
        integrations/agent-framework-mcp/src/test/java/io/github/hellices/agentframework/mcp/ClientHostileMcpTransport.java \
        integrations/agent-framework-mcp/src/test/java/io/github/hellices/agentframework/mcp/OwnedMcpClientConnectTest.java
git commit -m "$(cat <<'MSG'
mcp: own the first client generation

A generation is one new transport and one client built on it. Concurrent
connects join one handshake, and the handshake runs on a subscription the
caller cannot dispose, because disposing it leaves the SDK initializer
permanently pending and no later operation can recover.

Building a generation happens outside the state lock and never throws to the
caller. Once a transport exists, a failure while building or subscribing the
client closes that transport once and reports through the returned stage, so a
refused client cannot leak a process.

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
MSG
)"
```

---

### Task 3: Owned close, and reopen after close

**Files:**
- Modify: `integrations/agent-framework-mcp/src/main/java/io/github/hellices/agentframework/mcp/internal/OwnedMcpClientLifecycle.java`
- Test: `integrations/agent-framework-mcp/src/test/java/io/github/hellices/agentframework/mcp/OwnedMcpClientCloseTest.java` (create)

**Interfaces:**
- Consumes: `OwnedMcpClientLifecycle(McpClientTransportFactory, McpOwnedClientSettings)` and
  `CompletableFuture<Void> connect()` from Task 2; `ScriptedMcpTransportFactory` and the
  `InMemoryMcpTransport` API from Tasks 1 and 2.
- Produces: `CompletableFuture<Void> OwnedMcpClientLifecycle.close()`. Close ends the current
  generation, clears the owner's generation state whether or not the close succeeded, surfaces a
  cleanup failure to its caller, and leaves the owner reusable: a later explicit `connect()` creates
  a fresh generation.

This is the task that settles the contradiction in the research report. `close()` is terminal **for
one generation**, not for the owner object. The two tests that pin it down are
`connectAfterCloseCreatesAFreshGeneration` here and
`refusesToRunAnOperationAfterCloseWithoutCreatingATransport` in Task 4.

- [ ] **Step 1: Write the failing close test**

Create `integrations/agent-framework-mcp/src/test/java/io/github/hellices/agentframework/mcp/OwnedMcpClientCloseTest.java`:

```java
package io.github.hellices.agentframework.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hellices.agentframework.mcp.internal.McpOwnedClientSettings;
import io.github.hellices.agentframework.mcp.internal.OwnedMcpClientLifecycle;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * Close ends one generation, not the owner.
 *
 * <p>Each test asserts on the transports the factory handed out, because the transport records
 * exactly what the owner did to it: how many handshakes it drove and how many times it closed it.
 */
class OwnedMcpClientCloseTest {

  private static final Duration SETTLE = Duration.ofSeconds(5);

  @Test
  void closeEndsTheGenerationItCreated() {
    InMemoryMcpTransport transport = new InMemoryMcpTransport().answeringPing();
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(transport);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());
    lifecycle.connect().join();

    lifecycle.close().join();

    assertThat(transport.closeCount()).isEqualTo(1);
    assertThat(transport.isClosed()).isTrue();
    assertThat(factory.createdCount()).isEqualTo(1);
  }

  @Test
  void repeatedCloseClosesTheTransportOnlyOnce() {
    InMemoryMcpTransport transport = new InMemoryMcpTransport().answeringPing();
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(transport);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());
    lifecycle.connect().join();

    lifecycle.close().join();
    lifecycle.close().join();
    lifecycle.close().join();

    assertThat(transport.closeCount()).isEqualTo(1);
  }

  @Test
  void closeWithoutConnectCreatesNothingAndSucceeds() {
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory();
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());

    lifecycle.close().join();

    assertThat(factory.createdCount()).isZero();
  }

  @Test
  void closeDuringConnectWaitsForTheHandshakeToSettleAndThenCloses() {
    InMemoryMcpTransport transport =
        new InMemoryMcpTransport().answeringPing().withholding(McpSchema.METHOD_INITIALIZE);
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(transport);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());

    CompletableFuture<Void> connecting = lifecycle.connect();
    CompletableFuture<Void> closing = lifecycle.close();

    assertThat(closing).isNotDone();
    assertThat(transport.closeCount()).isZero();

    transport.releaseWithheld();

    assertThat(closing).succeedsWithin(SETTLE);
    assertThat(connecting).succeedsWithin(SETTLE);
    assertThat(transport.closeCount()).isEqualTo(1);
    assertThat(factory.createdCount()).isEqualTo(1);
  }

  @Test
  void closeSurfacesACleanupFailureAndStillReleasesTheGeneration() {
    InMemoryMcpTransport failing =
        new InMemoryMcpTransport()
            .answeringPing()
            .failingClose(() -> new IllegalStateException("close failed"));
    InMemoryMcpTransport replacement = new InMemoryMcpTransport().answeringPing();
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(failing, replacement);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());
    lifecycle.connect().join();

    assertThatThrownBy(() -> lifecycle.close().join())
        .hasRootCauseInstanceOf(IllegalStateException.class)
        .hasRootCauseMessage("close failed");

    lifecycle.connect().join();
    assertThat(factory.createdCount()).isEqualTo(2);
    assertThat(replacement.countOf(McpSchema.METHOD_INITIALIZE)).isEqualTo(1);
  }

  @Test
  void connectAfterCloseCreatesAFreshGeneration() {
    InMemoryMcpTransport first = new InMemoryMcpTransport().answeringPing();
    InMemoryMcpTransport second = new InMemoryMcpTransport().answeringPing();
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(first, second);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());

    lifecycle.connect().join();
    lifecycle.close().join();
    lifecycle.connect().join();

    assertThat(factory.createdCount()).isEqualTo(2);
    assertThat(first.closeCount()).isEqualTo(1);
    assertThat(second.isClosed()).isFalse();
    assertThat(second.methodsSent())
        .containsExactly(McpSchema.METHOD_INITIALIZE, McpSchema.METHOD_NOTIFICATION_INITIALIZED);

    lifecycle.close().join();
    assertThat(second.closeCount()).isEqualTo(1);
    assertThat(factory.createdCount()).isEqualTo(2);
  }

  private static McpOwnedClientSettings settings() {
    return new McpOwnedClientSettings(
        new PermissiveJsonSchemaValidator(), Duration.ofSeconds(5), Duration.ofSeconds(5));
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :integrations:agent-framework-mcp:test --tests 'io.github.hellices.agentframework.mcp.OwnedMcpClientCloseTest'`

Expected: FAIL to compile, `cannot find symbol: method close()` on `OwnedMcpClientLifecycle`.

- [ ] **Step 3: Add the close state to the lifecycle**

In `OwnedMcpClientLifecycle`, add one field next to `pending` and `current`:

```java
  private CompletableFuture<Void> closing;
```

and clear it when a new generation starts, by adding one line to `connectGeneration()` immediately
before `handshake = adopt(++epoch);`:

```java
      closing = null;
```

No new import is needed. Task 4 is what introduces `java.util.function.Function`.

- [ ] **Step 4: Add close()**

Add this method to `OwnedMcpClientLifecycle`, after `connect()`:

```java
  /**
   * Closes the current generation and leaves the owner reusable.
   *
   * <p>Close ends one generation, not this object. The generation reference and everything cached on
   * it are dropped whether or not the underlying close succeeded, because a generation whose close
   * failed is not safe to keep using; a cleanup failure is reported to this caller instead of being
   * swallowed. A later explicit {@link #connect()} creates a fresh generation. Closing while a
   * handshake is in flight waits for that handshake to settle and then closes what it produced,
   * rather than abandoning a client the SDK is still initializing.
   *
   * @return a stage completing when the generation is released, never {@code null}
   */
  public CompletableFuture<Void> close() {
    Generation generation;
    CompletableFuture<Generation> settling;
    CompletableFuture<Void> closure = new CompletableFuture<>();
    synchronized (lock) {
      epoch++;
      if (current == null && pending == null) {
        return closing == null ? CompletableFuture.completedFuture(null) : closing;
      }
      generation = current;
      settling = pending;
      current = null;
      pending = null;
      closing = closure;
    }
    if (generation != null) {
      mirror(generation.close(), closure);
      return closure;
    }
    settling.whenComplete(
        (settled, failure) -> {
          if (settled == null) {
            closure.complete(null);
          } else {
            mirror(settled.close(), closure);
          }
        });
    return closure;
  }
```

Four details that are easy to get wrong and that the tests above pin down:

1. `current` and `pending` are read and cleared inside the lock, but `generation.close()` runs outside
   it. Verified SDK fact 15 says closing a client dismisses its in-flight requests synchronously, and
   those dismissals run this module's own failure handling on the closing thread, which in Task 7 can
   re-enter this class. Holding the lock across the close would also make every concurrent caller
   wait behind a process teardown. The promise published as `closing` inside the lock is what keeps a
   concurrent `close()` joined to the same outcome.
2. `current` is cleared **before** `generation.close()` is called, so the owner is disconnected even
   if the close fails. `closing` is returned again by a repeated `close()` so a second caller observes
   the same outcome instead of a premature success.
3. `epoch++` is what makes `publish` a no-op when an in-flight handshake settles: `publish` compares
   its ticket with the current `epoch` and returns. The generation is therefore never adopted, and
   the close-during-connect branch closes it directly.
4. A handshake that failed already closed its own generation in `closeAfterFailedHandshake`, and
   `settling` then completes with `settled == null`, so this method must not close anything.

The two branches are exhaustive because `current` and `pending` are never both set:
`connectGeneration` only adopts a pending handshake when `current` is null, and `publish` swaps one
for the other while holding the lock. The early return covers the case where neither is set.

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :integrations:agent-framework-mcp:test --tests 'io.github.hellices.agentframework.mcp.OwnedMcpClientCloseTest'`

Expected: PASS, 6 tests.

- [ ] **Step 6: Run the module test task**

Run: `./gradlew :integrations:agent-framework-mcp:test :integrations:agent-framework-mcp:quality`

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add integrations/agent-framework-mcp/src/main/java/io/github/hellices/agentframework/mcp/internal/OwnedMcpClientLifecycle.java \
        integrations/agent-framework-mcp/src/test/java/io/github/hellices/agentframework/mcp/OwnedMcpClientCloseTest.java
git commit -m "$(cat <<'MSG'
mcp: close the owned generation without ending the owner

Close releases one generation and clears it even when the underlying close
fails, because a generation whose close failed is not safe to keep using.
The cleanup failure reaches the caller, and a later explicit connect opens
a fresh generation, so the owner object stays reusable.

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
MSG
)"
```

---

### Task 4: Operations that never connect implicitly

**Files:**
- Modify: `integrations/agent-framework-mcp/src/main/java/io/github/hellices/agentframework/mcp/internal/OwnedMcpClientLifecycle.java`
- Create: `integrations/agent-framework-mcp/src/main/java/io/github/hellices/agentframework/mcp/internal/OwnedMcpAsyncOperations.java`
- Test: `integrations/agent-framework-mcp/src/test/java/io/github/hellices/agentframework/mcp/OwnedMcpClientOperationTest.java` (create)

**Interfaces:**
- Consumes: `OwnedMcpClientLifecycle.connect()` and `close()` from Tasks 2 and 3; the already shipped
  `io.github.hellices.agentframework.mcp.internal.McpAsyncOperations` port with
  `CompletionStage<McpSchema.ListToolsResult> listTools(String cursor, Map<String, Object> meta)` and
  `CompletionStage<McpSchema.CallToolResult> callTool(McpSchema.CallToolRequest request)`; the
  already shipped `McpToolDiscovery(McpAsyncOperations, McpToolAdapterOptions)` with
  `CompletionStage<List<FunctionTool>> discover()`.
- Produces:
  - `<T> CompletableFuture<T> OwnedMcpClientLifecycle.execute(Function<McpAsyncClient, Mono<T>> operation)`.
    The operation is a function, not a prebuilt `Mono`, because Task 7 re-applies it to a different
    client to retry. It fails with `IllegalStateException` when there is no generation, and it never
    creates one.
  - `public final class OwnedMcpAsyncOperations implements McpAsyncOperations` with the constructor
    `OwnedMcpAsyncOperations(OwnedMcpClientLifecycle lifecycle)`.

- [ ] **Step 1: Write the failing operation test**

Create `integrations/agent-framework-mcp/src/test/java/io/github/hellices/agentframework/mcp/OwnedMcpClientOperationTest.java`:

```java
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
                        List.of(new McpSchema.TextContent("ok")), false, null, null));
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(transport);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());
    lifecycle.connect().join();
    FunctionTool tool =
        new McpToolDiscovery(new OwnedMcpAsyncOperations(lifecycle), McpToolAdapterOptions.defaults())
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
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :integrations:agent-framework-mcp:test --tests 'io.github.hellices.agentframework.mcp.OwnedMcpClientOperationTest'`

Expected: FAIL to compile, `cannot find symbol: method execute` and
`cannot find symbol: class OwnedMcpAsyncOperations`.

- [ ] **Step 3: Add execute() and the attempt holder to the lifecycle**

Add the imports `java.util.concurrent.atomic.AtomicReference`, `java.util.function.Function` and
`reactor.core.publisher.Mono` to `OwnedMcpClientLifecycle`, add the message constant next to the
class fields:

```java
  private static final String NOT_CONNECTED =
      "the MCP server connection is not open; call connect() before discovering or calling tools,"
          + " because an owned client never opens a connection implicitly and a lookup that started"
          + " a server process or a network session on its own would hide that cost from the caller";
```

then add these members after `close()`:

```java
  /**
   * Runs one operation on the current generation.
   *
   * <p>The operation is a function of the client rather than a prepared publisher because a retry
   * has to run it against a different client. Nothing here connects: an operation attempted with no
   * generation fails, and an operation attempted while a handshake is in flight joins that
   * handshake instead of starting another one.
   *
   * @param operation produces the SDK call for a given client, never {@code null}
   * @param <T> the operation result type
   * @return a stage completing with the operation result, never {@code null}
   */
  public <T> CompletableFuture<T> execute(Function<McpAsyncClient, Mono<T>> operation) {
    if (operation == null) {
      return AsyncStages.failed(new IllegalArgumentException("operation must not be null"));
    }
    Attempt attempt = new Attempt();
    CompletableFuture<T> result =
        currentGeneration().thenCompose(generation -> invoke(operation, generation, attempt));
    return AsyncStages.cancellable(result, attempt::cancel);
  }

  private CompletableFuture<Generation> currentGeneration() {
    synchronized (lock) {
      if (current != null) {
        return CompletableFuture.completedFuture(current);
      }
      if (pending != null) {
        return pending.thenApply(generation -> generation);
      }
    }
    return AsyncStages.failed(new IllegalStateException(NOT_CONNECTED));
  }

  private <T> CompletableFuture<T> invoke(
      Function<McpAsyncClient, Mono<T>> operation, Generation generation, Attempt attempt) {
    CompletableFuture<T> inFlight;
    try {
      Mono<T> call = operation.apply(generation.client());
      if (call == null) {
        return AsyncStages.failed(new IllegalStateException("operation produced no call"));
      }
      inFlight = AsyncStages.fromMono(call);
    } catch (RuntimeException failure) {
      return AsyncStages.failed(failure);
    }
    attempt.track(inFlight);
    return inFlight;
  }

  /** Tracks the in-flight call of one {@code execute} so cancellation can reach it. */
  private static final class Attempt {

    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicReference<CompletableFuture<?>> inFlight = new AtomicReference<>();

    void track(CompletableFuture<?> call) {
      inFlight.set(call);
      if (cancelled.get()) {
        call.cancel(true);
      }
    }

    void cancel() {
      cancelled.set(true);
      CompletableFuture<?> call = inFlight.get();
      if (call != null) {
        call.cancel(true);
      }
    }
  }
```

`currentGeneration()` builds the failure **outside** the `synchronized` block so no exception is
constructed while the lock is held, and it returns `pending.thenApply(...)` rather than `pending`
itself so a caller cannot complete or cancel the shared handshake future.

- [ ] **Step 4: Add the owned operations port**

Create `integrations/agent-framework-mcp/src/main/java/io/github/hellices/agentframework/mcp/internal/OwnedMcpAsyncOperations.java`:

```java
package io.github.hellices.agentframework.mcp.internal;

import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import java.util.concurrent.CompletionStage;

/**
 * Performs MCP operations on a client this module owns.
 *
 * <p>This is the owned counterpart of {@link BorrowedMcpAsyncOperations}. The borrowed version
 * refuses to act on a client whose owner has closed it; this one routes every call through {@link
 * OwnedMcpClientLifecycle}, which validates the current generation, replaces it at most once, and
 * retries the call at most once. It still never connects on its own: the caller opens the connection
 * explicitly, so a tool lookup can never start a server process as a side effect.
 *
 * <p>Instances are immutable and safe to share once constructed.
 */
public final class OwnedMcpAsyncOperations implements McpAsyncOperations {

  private final OwnedMcpClientLifecycle lifecycle;

  /**
   * Creates operations over an owned lifecycle.
   *
   * @param lifecycle the lifecycle that owns the client, never {@code null}
   * @throws IllegalArgumentException if the lifecycle is {@code null}
   */
  public OwnedMcpAsyncOperations(OwnedMcpClientLifecycle lifecycle) {
    if (lifecycle == null) {
      throw new IllegalArgumentException("lifecycle must not be null");
    }
    this.lifecycle = lifecycle;
  }

  @Override
  public CompletionStage<McpSchema.ListToolsResult> listTools(
      String cursor, Map<String, Object> meta) {
    return lifecycle.execute(client -> client.listTools(cursor, meta));
  }

  @Override
  public CompletionStage<McpSchema.CallToolResult> callTool(McpSchema.CallToolRequest request) {
    if (request == null) {
      return AsyncStages.failed(new IllegalArgumentException("request must not be null"));
    }
    return lifecycle.execute(client -> client.callTool(request));
  }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :integrations:agent-framework-mcp:test --tests 'io.github.hellices.agentframework.mcp.OwnedMcpClientOperationTest'`

Expected: PASS, 8 tests.

- [ ] **Step 6: Run the module test and quality tasks**

Run: `./gradlew :integrations:agent-framework-mcp:test :integrations:agent-framework-mcp:quality`

Expected: PASS. `BorrowedMcpClientIntegrationTest` and `ConnectedMcpClientAdapterTest` must still
pass unchanged; they are the regression guard that borrowed ownership was not touched.

- [ ] **Step 7: Commit**

```bash
git add integrations/agent-framework-mcp/src/main/java/io/github/hellices/agentframework/mcp/internal/OwnedMcpClientLifecycle.java \
        integrations/agent-framework-mcp/src/main/java/io/github/hellices/agentframework/mcp/internal/OwnedMcpAsyncOperations.java \
        integrations/agent-framework-mcp/src/test/java/io/github/hellices/agentframework/mcp/OwnedMcpClientOperationTest.java
git commit -m "$(cat <<'MSG'
mcp: route owned operations through the current generation

Operations take a function of the client so a later retry can re-run them
against a replacement. An operation without a generation fails instead of
connecting, because a tool lookup that started a server process or opened
a network session on its own would hide that cost from the caller.

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
MSG
)"
```

---

### Task 5: Failure classification

**Files:**
- Create: `integrations/agent-framework-mcp/src/main/java/io/github/hellices/agentframework/mcp/internal/McpFailures.java`
- Test: `integrations/agent-framework-mcp/src/test/java/io/github/hellices/agentframework/mcp/internal/McpFailuresTest.java` (create; this is the first test in the `internal` test package, so the directory has to be created too)

**Interfaces:**
- Consumes: nothing from earlier tasks. This class is pure and has no state.
- Produces: package-private `static boolean McpFailures.isPingUnsupported(Throwable failure)` and
  `static boolean McpFailures.isConnectionLoss(Throwable failure)`, used by Tasks 6 and 7.

Classification is by **type and JSON-RPC code only**. Matching on a message string would make the
behavior depend on wording the SDK is free to change, and it would silently start or stop reconnecting
after an SDK upgrade with no test failing.

- [ ] **Step 1: Write the failing classification test**

Create `integrations/agent-framework-mcp/src/test/java/io/github/hellices/agentframework/mcp/internal/McpFailuresTest.java`:

```java
package io.github.hellices.agentframework.mcp.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpTransportException;
import io.modelcontextprotocol.spec.McpTransportSessionClosedException;
import io.modelcontextprotocol.spec.McpTransportSessionNotFoundException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

/**
 * Classification decides whether the owner throws away a connection, so it is deliberately narrow.
 *
 * <p>Only a JSON-RPC {@code -32601} counts as an unsupported ping, and only the SDK's own transport
 * failure types count as a lost connection. Anything else is the server's answer and is handed back
 * to the caller unchanged, because reconnecting on an application error would restart a server
 * process every time a tool legitimately failed.
 */
class McpFailuresTest {

  @Test
  void treatsMethodNotFoundAsAnUnsupportedPing() {
    McpError failure = jsonRpcError(McpSchema.ErrorCodes.METHOD_NOT_FOUND, "unknown method: ping");

    assertThat(McpFailures.isPingUnsupported(failure)).isTrue();
    assertThat(McpFailures.isConnectionLoss(failure)).isFalse();
  }

  @Test
  void treatsEveryOtherJsonRpcErrorAsAnAnswerFromTheServer() {
    McpError failure = jsonRpcError(McpSchema.ErrorCodes.INTERNAL_ERROR, "server is unhappy");

    assertThat(McpFailures.isPingUnsupported(failure)).isFalse();
    assertThat(McpFailures.isConnectionLoss(failure)).isFalse();
  }

  @Test
  void treatsTheSdkTransportFailuresAsALostConnection() {
    assertThat(McpFailures.isConnectionLoss(new McpTransportSessionNotFoundException("gone")))
        .isTrue();
    assertThat(McpFailures.isConnectionLoss(new McpTransportSessionClosedException())).isTrue();
    assertThat(McpFailures.isConnectionLoss(new McpTransportException("stream broke"))).isTrue();
  }

  @Test
  void findsATransportFailureThroughACauseChain() {
    Throwable wrapped =
        new IllegalStateException(
            "Client failed to initialize", new McpTransportException("stream broke"));

    assertThat(McpFailures.isConnectionLoss(wrapped)).isTrue();
  }

  @Test
  void findsAnUnsupportedPingThroughACauseChain() {
    Throwable wrapped =
        new IllegalStateException(
            "wrapped", jsonRpcError(McpSchema.ErrorCodes.METHOD_NOT_FOUND, "unknown method"));

    assertThat(McpFailures.isPingUnsupported(wrapped)).isTrue();
  }

  @Test
  void treatsAnApplicationFailureAndARequestTimeoutAsSomethingToReportNotToRetry() {
    assertThat(McpFailures.isConnectionLoss(new IllegalStateException("tool exploded"))).isFalse();
    assertThat(McpFailures.isConnectionLoss(new TimeoutException("took too long"))).isFalse();
    assertThat(McpFailures.isConnectionLoss(new RuntimeException("MCP session with server terminated")))
        .isFalse();
  }

  @Test
  void terminatesOnACyclicCauseChain() {
    Throwable first = new IllegalStateException("first");
    Throwable second = new IllegalStateException("second");
    first.initCause(second);
    second.initCause(first);

    assertThat(McpFailures.isConnectionLoss(first)).isFalse();
    assertThat(McpFailures.isPingUnsupported(first)).isFalse();
  }

  @Test
  void treatsNoFailureAsNeither() {
    assertThat(McpFailures.isConnectionLoss(null)).isFalse();
    assertThat(McpFailures.isPingUnsupported(null)).isFalse();
  }

  private static McpError jsonRpcError(int code, String message) {
    return new McpError(new McpSchema.JSONRPCResponse.JSONRPCError(code, message));
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :integrations:agent-framework-mcp:test --tests 'io.github.hellices.agentframework.mcp.internal.McpFailuresTest'`

Expected: FAIL to compile, `cannot find symbol: class McpFailures`.

- [ ] **Step 3: Write the classifier**

Create `integrations/agent-framework-mcp/src/main/java/io/github/hellices/agentframework/mcp/internal/McpFailures.java`:

```java
package io.github.hellices.agentframework.mcp.internal;

import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpTransportException;
import io.modelcontextprotocol.spec.McpTransportSessionClosedException;
import io.modelcontextprotocol.spec.McpTransportSessionNotFoundException;
import java.util.function.Predicate;

/**
 * Decides what a failure means for an owned connection.
 *
 * <p>Both questions are answered from the failure's type and, for a JSON-RPC error, its numeric code.
 * Nothing here matches on a message, because a message is wording the SDK and the server are free to
 * change, and a reconnect policy that depended on wording would change behavior on an upgrade without
 * a single test failing.
 *
 * <p>The walk down the cause chain is bounded. A failure chain assembled by several layers can be
 * long, and a chain that loops back on itself would otherwise never terminate.
 */
final class McpFailures {

  private static final int MAX_CAUSE_DEPTH = 16;

  private McpFailures() {}

  /**
   * Reports whether the server answered {@code ping} with JSON-RPC {@code -32601}.
   *
   * <p>That answer means the server implements no ping, not that the connection is unhealthy, so the
   * caller should stop pinging that connection rather than replace it.
   *
   * @param failure the failure to classify, may be {@code null}
   * @return {@code true} if the server does not implement ping
   */
  static boolean isPingUnsupported(Throwable failure) {
    return matches(failure, McpFailures::methodNotFound);
  }

  /**
   * Reports whether the failure is the SDK saying the connection is gone.
   *
   * <p>Only the SDK's own transport failure types qualify. An application error, including a tool
   * that failed and a request that timed out, is the server's answer and is reported to the caller
   * instead of costing a reconnect and a second execution of a call that may have side effects.
   *
   * @param failure the failure to classify, may be {@code null}
   * @return {@code true} if the connection is known to be gone
   */
  static boolean isConnectionLoss(Throwable failure) {
    return matches(failure, McpFailures::transportFailure);
  }

  private static boolean methodNotFound(Throwable candidate) {
    if (!(candidate instanceof McpError error)) {
      return false;
    }
    McpSchema.JSONRPCResponse.JSONRPCError jsonRpcError = error.getJsonRpcError();
    if (jsonRpcError == null) {
      return false;
    }
    Integer code = jsonRpcError.code();
    return code != null && code.intValue() == McpSchema.ErrorCodes.METHOD_NOT_FOUND;
  }

  private static boolean transportFailure(Throwable candidate) {
    return candidate instanceof McpTransportSessionNotFoundException
        || candidate instanceof McpTransportSessionClosedException
        || candidate instanceof McpTransportException;
  }

  private static boolean matches(Throwable failure, Predicate<Throwable> predicate) {
    Throwable candidate = failure;
    for (int depth = 0; candidate != null && depth < MAX_CAUSE_DEPTH; depth++) {
      if (predicate.test(candidate)) {
        return true;
      }
      candidate = candidate.getCause();
    }
    return false;
  }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :integrations:agent-framework-mcp:test --tests 'io.github.hellices.agentframework.mcp.internal.McpFailuresTest'`

Expected: PASS, 8 tests.

- [ ] **Step 5: Run the module test and quality tasks**

Run: `./gradlew :integrations:agent-framework-mcp:test :integrations:agent-framework-mcp:quality`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add integrations/agent-framework-mcp/src/main/java/io/github/hellices/agentframework/mcp/internal/McpFailures.java \
        integrations/agent-framework-mcp/src/test/java/io/github/hellices/agentframework/mcp/internal/McpFailuresTest.java
git commit -m "$(cat <<'MSG'
mcp: classify failures by type instead of by message

Only a JSON-RPC -32601 means the server has no ping, and only the SDK's
transport failure types mean the connection is gone. Matching on wording
would change the reconnect policy on an SDK upgrade without a single test
failing, and retrying a timed out tool call could run its side effect twice.

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
MSG
)"
```

---

### Task 6: Ping validation, the per-generation ping capability cache, and one reconnect

**Files:**
- Modify: `integrations/agent-framework-mcp/src/main/java/io/github/hellices/agentframework/mcp/internal/OwnedMcpClientLifecycle.java`
- Test: `integrations/agent-framework-mcp/src/test/java/io/github/hellices/agentframework/mcp/OwnedMcpClientValidationTest.java` (create)

**Interfaces:**
- Consumes: `McpFailures.isPingUnsupported(Throwable)` and `McpFailures.isConnectionLoss(Throwable)`
  from Task 5; `execute`, `invoke`, `Attempt`, `adopt`, `publish`, `startGeneration`, and `Generation`
  from Tasks 2 and 4.
- Produces, all internal to `OwnedMcpClientLifecycle` and relied on by Task 7:
  - `Generation` gains `long epoch()`, `boolean pingUnsupported()`, and `void markPingUnsupported()`,
    and its constructor becomes `Generation(long epoch, McpAsyncClient client)`.
  - `startGeneration(long ticket)` replaces `startGeneration()`.
  - `Attempt` gains `boolean spendReconnect()`, which succeeds exactly once per `execute`.
  - `private <T> CompletableFuture<T> recover(Function<McpAsyncClient, Mono<T>> operation, Generation
    stale, Attempt attempt, Throwable failure)`,
    `private CompletableFuture<Generation> replaceGeneration(Generation stale)`, and
    `private static boolean cancelled(Throwable failure)`.

The behavior this task installs, stated once so no step has to guess:

1. Before every `listTools` and every `callTool`, the current generation is validated with `ping`.
2. If that generation already answered `ping` with `-32601`, no ping is sent; the operation runs at
   once. That cache is per generation and dies with it.
3. Any other ping failure spends the single reconnect budget of that operation: the stale generation
   is closed first, one replacement is created, and the operation runs on the replacement.
4. A cancelled ping is the exception to rule 3. Cancellation is the caller withdrawing, not the
   connection failing, so it fails the operation and leaves the generation open and adopted.
5. The replacement is not pinged. It has just completed a handshake.
6. If the stale close fails, or the replacement handshake fails, the owner ends disconnected and the
   caller sees the original failure with the cleanup or handshake failure attached as suppressed.
7. Concurrent failures from the same generation produce exactly one replacement.

- [ ] **Step 1: Write the failing validation test**

Create `integrations/agent-framework-mcp/src/test/java/io/github/hellices/agentframework/mcp/OwnedMcpClientValidationTest.java`:

```java
package io.github.hellices.agentframework.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.hellices.agentframework.mcp.internal.McpOwnedClientSettings;
import io.github.hellices.agentframework.mcp.internal.OwnedMcpClientLifecycle;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * Validation decides when the owner throws a connection away.
 *
 * <p>A stdio server is a child process and a streamable HTTP session is server state, so replacing a
 * generation is expensive and is bounded to once per operation. These tests count transports created,
 * pings sent, and transports closed, because those counts are the whole contract.
 */
class OwnedMcpClientValidationTest {

  private static final Duration SETTLE = Duration.ofSeconds(5);

  @Test
  void pingsBeforeEveryOperation() {
    InMemoryMcpTransport transport = toolServer().answeringPing();
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(transport);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());
    lifecycle.connect().join();

    listTools(lifecycle).join();
    listTools(lifecycle).join();

    assertThat(transport.countOf(McpSchema.METHOD_PING)).isEqualTo(2);
    assertThat(transport.countOf(McpSchema.METHOD_TOOLS_LIST)).isEqualTo(2);
    assertThat(factory.createdCount()).isEqualTo(1);
  }

  @Test
  void cachesAnUnsupportedPingAndKeepsUsingTheGeneration() {
    InMemoryMcpTransport transport =
        toolServer()
            .answeringWithError(
                McpSchema.METHOD_PING, McpSchema.ErrorCodes.METHOD_NOT_FOUND, "unknown method");
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(transport);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());
    lifecycle.connect().join();

    listTools(lifecycle).join();
    listTools(lifecycle).join();
    listTools(lifecycle).join();

    assertThat(transport.countOf(McpSchema.METHOD_PING)).isEqualTo(1);
    assertThat(transport.countOf(McpSchema.METHOD_TOOLS_LIST)).isEqualTo(3);
    assertThat(transport.closeCount()).isZero();
    assertThat(factory.createdCount()).isEqualTo(1);
  }

  @Test
  void probesPingAgainOnAFreshGeneration() {
    InMemoryMcpTransport first =
        toolServer()
            .answeringWithError(
                McpSchema.METHOD_PING, McpSchema.ErrorCodes.METHOD_NOT_FOUND, "unknown method");
    InMemoryMcpTransport second =
        toolServer()
            .answeringWithError(
                McpSchema.METHOD_PING, McpSchema.ErrorCodes.METHOD_NOT_FOUND, "unknown method");
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(first, second);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());

    lifecycle.connect().join();
    listTools(lifecycle).join();
    listTools(lifecycle).join();
    lifecycle.close().join();
    lifecycle.connect().join();
    listTools(lifecycle).join();

    assertThat(first.countOf(McpSchema.METHOD_PING)).isEqualTo(1);
    assertThat(second.countOf(McpSchema.METHOD_PING)).isEqualTo(1);
  }

  @Test
  void replacesTheGenerationWhenValidationFails() {
    InMemoryMcpTransport first =
        toolServer()
            .answeringWithError(
                McpSchema.METHOD_PING, McpSchema.ErrorCodes.INTERNAL_ERROR, "ping failed");
    InMemoryMcpTransport second = toolServer().answeringPing();
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(first, second);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());
    lifecycle.connect().join();

    listTools(lifecycle).join();

    assertThat(factory.createdCount()).isEqualTo(2);
    assertThat(first.closeCount()).isEqualTo(1);
    assertThat(first.countOf(McpSchema.METHOD_TOOLS_LIST)).isZero();
    assertThat(second.countOf(McpSchema.METHOD_PING)).isZero();
    assertThat(second.countOf(McpSchema.METHOD_TOOLS_LIST)).isEqualTo(1);
  }

  @Test
  void coalescesConcurrentValidationFailuresOntoOneReplacement() {
    InMemoryMcpTransport first =
        toolServer()
            .answeringWithError(
                McpSchema.METHOD_PING, McpSchema.ErrorCodes.INTERNAL_ERROR, "ping failed")
            .withholding(McpSchema.METHOD_PING);
    InMemoryMcpTransport second = toolServer().withholding(McpSchema.METHOD_INITIALIZE);
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(first, second);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());
    lifecycle.connect().join();

    CompletableFuture<McpSchema.ListToolsResult> firstCall = listTools(lifecycle);
    CompletableFuture<McpSchema.ListToolsResult> secondCall = listTools(lifecycle);
    assertThat(firstCall).isNotDone();
    assertThat(secondCall).isNotDone();

    first.releaseWithheld();

    assertThat(factory.createdCount()).isEqualTo(2);
    assertThat(first.closeCount()).isEqualTo(1);

    second.releaseWithheld();

    assertThat(firstCall).succeedsWithin(SETTLE);
    assertThat(secondCall).succeedsWithin(SETTLE);
    assertThat(second.countOf(McpSchema.METHOD_TOOLS_LIST)).isEqualTo(2);
    assertThat(second.countOf(McpSchema.METHOD_PING)).isZero();
  }

  @Test
  void aFailedReplacementHandshakeLeavesTheOwnerDisconnected() {
    InMemoryMcpTransport first =
        toolServer()
            .answeringWithError(
                McpSchema.METHOD_PING, McpSchema.ErrorCodes.INTERNAL_ERROR, "ping failed");
    InMemoryMcpTransport second =
        toolServer()
            .failingSend(
                McpSchema.METHOD_INITIALIZE, () -> new IllegalStateException("handshake refused"));
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(first, second);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());
    lifecycle.connect().join();

    Throwable failure = catchThrowable(() -> listTools(lifecycle).join());

    // The caller keeps the failure that actually stopped the operation; the reason the recovery
    // could not help is attached rather than substituted. The handshake failure is asserted through
    // the stack trace because the SDK is free to wrap an initialization failure, and pinning that
    // wrapping would make this test fail on an SDK upgrade for no behavioural reason.
    assertThat(failure).hasRootCauseInstanceOf(McpError.class);
    assertThat(failure.getCause().getSuppressed())
        .singleElement()
        .satisfies(
            suppressed ->
                assertThat(suppressed).hasStackTraceContaining("handshake refused"));
    assertThat(factory.createdCount()).isEqualTo(2);
    assertThat(first.closeCount()).isEqualTo(1);
    assertThat(second.closeCount()).isEqualTo(1);

    assertThatThrownBy(() -> listTools(lifecycle).join())
        .rootCause()
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("connect()");
    assertThat(factory.createdCount()).isEqualTo(2);
  }

  @Test
  void aFailedStaleCloseLeavesTheOwnerDisconnectedAndCreatesNoReplacement() {
    InMemoryMcpTransport first =
        toolServer()
            .answeringWithError(
                McpSchema.METHOD_PING, McpSchema.ErrorCodes.INTERNAL_ERROR, "ping failed")
            .failingClose(() -> new IllegalStateException("close failed"));
    InMemoryMcpTransport second = toolServer().answeringPing();
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(first, second);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());
    lifecycle.connect().join();

    Throwable failure = catchThrowable(() -> listTools(lifecycle).join());

    assertThat(failure).hasRootCauseInstanceOf(McpError.class);
    assertThat(failure.getCause().getSuppressed())
        .singleElement()
        .satisfies(
            suppressed ->
                assertThat(suppressed)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("close failed"));
    assertThat(factory.createdCount()).isEqualTo(1);

    assertThatThrownBy(() -> listTools(lifecycle).join())
        .rootCause()
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("connect()");
    assertThat(factory.createdCount()).isEqualTo(1);
  }

  @Test
  void cancellingDuringValidationNeitherClosesNorReplacesTheGeneration() {
    // A cancelled agent run must not cost the process its MCP server. The ping is withheld, so the
    // cancellation lands while validation is the only thing in flight, which is precisely where a
    // classifier that treats "not a known-good failure" as "connection lost" would close a live
    // generation and ask for a second transport the factory was never given.
    InMemoryMcpTransport transport =
        toolServer().answeringPing().withholding(McpSchema.METHOD_PING);
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(transport);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());
    lifecycle.connect().join();

    CompletableFuture<McpSchema.ListToolsResult> operation = listTools(lifecycle);
    assertThat(operation.cancel(true)).isTrue();
    transport.releaseWithheld();

    assertThat(operation).isCancelled();
    assertThat(transport.closeCount()).isZero();
    assertThat(transport.countOf(McpSchema.METHOD_TOOLS_LIST)).isZero();
    assertThat(factory.createdCount()).isEqualTo(1);
  }

  private static CompletableFuture<McpSchema.ListToolsResult> listTools(
      OwnedMcpClientLifecycle lifecycle) {
    return lifecycle.execute(client -> client.listTools(McpSchema.FIRST_PAGE, null));
  }

  private static InMemoryMcpTransport toolServer() {
    return new InMemoryMcpTransport()
        .answering(
            McpSchema.METHOD_TOOLS_LIST,
            params -> new McpSchema.ListToolsResult(List.of(), null, null));
  }

  private static McpOwnedClientSettings settings() {
    return new McpOwnedClientSettings(
        new PermissiveJsonSchemaValidator(), Duration.ofSeconds(5), Duration.ofSeconds(5));
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :integrations:agent-framework-mcp:test --tests 'io.github.hellices.agentframework.mcp.OwnedMcpClientValidationTest'`

Expected: FAIL. `pingsBeforeEveryOperation` fails with `expected: 2 but was: 0`, because nothing pings
yet, `replacesTheGenerationWhenValidationFails` fails with `expected: 2 but was: 1`, and
`cancellingDuringValidationNeitherClosesNorReplacesTheGeneration` fails on
`assertThat(operation.cancel(true)).isTrue()`, because with no validation the withheld ping is never
sent and `listTools` has already answered.

- [ ] **Step 3: Stamp the generation and cache the ping capability**

Replace the `Generation` class in `OwnedMcpClientLifecycle` with:

```java
  /**
   * One transport and one client, closed at most once.
   *
   * <p>The epoch is the identity of the generation. It is a number rather than the object reference
   * so the lifecycle never has to compare two references, and so a stale caller can ask whether the
   * owner still holds the generation it saw.
   *
   * <p>{@code pingUnsupported} is the only cache in this class, and it is deliberately per
   * generation: a different server process, or a different HTTP session, may well answer ping.
   */
  private static final class Generation {

    private final long epoch;
    private final McpAsyncClient client;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean pingUnsupported = new AtomicBoolean();
    private final CompletableFuture<Void> closure = new CompletableFuture<>();

    Generation(long epoch, McpAsyncClient client) {
      this.epoch = epoch;
      this.client = client;
    }

    long epoch() {
      return epoch;
    }

    McpAsyncClient client() {
      return client;
    }

    boolean pingUnsupported() {
      return pingUnsupported.get();
    }

    void markPingUnsupported() {
      pingUnsupported.set(true);
    }

    CompletableFuture<Void> close() {
      if (!closed.compareAndSet(false, true)) {
        return closure;
      }
      mirror(AsyncStages.fromMono(client.closeGracefully()), closure);
      return closure;
    }
  }
```

Then give `startGeneration` the ticket. Change its signature and the line that creates the generation:

```java
  private CompletableFuture<Generation> startGeneration(long ticket) {
```

```java
    Generation generation = new Generation(ticket, client);
```

and change the single existing call in `connectGeneration()`:

```java
      closing = null;
      ticket = ++epoch;
      handshake = adopt(ticket);
    }
    mirror(startGeneration(ticket), handshake);
```

where `ticket` is a new `long` local declared next to `handshake`, because the value is needed after
the lock is released.

- [ ] **Step 4: Give the attempt a reconnect budget**

Add one field and one method to the nested `Attempt` class:

```java
    private final AtomicBoolean reconnect = new AtomicBoolean(true);
```

```java
    boolean spendReconnect() {
      return reconnect.compareAndSet(true, false);
    }
```

- [ ] **Step 5: Validate before the operation**

Change `execute` to route through validation instead of straight to the call, by replacing the single
line `currentGeneration().thenCompose(generation -> invoke(operation, generation, attempt));` with:

```java
        currentGeneration().thenCompose(generation -> validate(operation, generation, attempt));
```

Then add these three methods after `invoke`:

```java
  private <T> CompletableFuture<T> validate(
      Function<McpAsyncClient, Mono<T>> operation, Generation generation, Attempt attempt) {
    if (generation.pingUnsupported()) {
      return invoke(operation, generation, attempt);
    }
    CompletableFuture<Object> ping = AsyncStages.fromMono(generation.client().ping());
    attempt.track(ping);
    return ping.handle(
            (ignored, failure) -> {
              if (failure == null) {
                return invoke(operation, generation, attempt);
              }
              Throwable reported = AsyncStages.unwrap(failure);
              if (McpFailures.isPingUnsupported(reported)) {
                generation.markPingUnsupported();
                return invoke(operation, generation, attempt);
              }
              return recover(operation, generation, attempt, reported);
            })
        .thenCompose(Function.identity());
  }

  private <T> CompletableFuture<T> recover(
      Function<McpAsyncClient, Mono<T>> operation,
      Generation stale,
      Attempt attempt,
      Throwable failure) {
    if (!attempt.spendReconnect()) {
      return AsyncStages.failed(failure);
    }
    return replaceGeneration(stale)
        .handle(
            (replacement, replacementFailure) -> {
              if (replacementFailure == null) {
                return invoke(operation, replacement, attempt);
              }
              failure.addSuppressed(AsyncStages.unwrap(replacementFailure));
              return AsyncStages.<T>failed(failure);
            })
        .thenCompose(Function.identity());
  }

  private CompletableFuture<Generation> replaceGeneration(Generation stale) {
    CompletableFuture<Void> staleClosure;
    CompletableFuture<Generation> replacement;
    long ticket;
    synchronized (lock) {
      if (current == null || current.epoch() != stale.epoch()) {
        if (current != null) {
          return CompletableFuture.completedFuture(current);
        }
        if (pending != null) {
          return pending.thenApply(generation -> generation);
        }
        return AsyncStages.failed(new IllegalStateException(NOT_CONNECTED));
      }
      current = null;
      staleClosure = new CompletableFuture<>();
      closing = staleClosure;
      ticket = ++epoch;
      replacement = adopt(ticket);
    }
    staleClosure.whenComplete(
        (ignored, failure) -> {
          if (failure == null) {
            mirror(startGeneration(ticket), replacement);
          } else {
            replacement.completeExceptionally(failure);
          }
        });
    mirror(stale.close(), staleClosure);
    return replacement.thenApply(generation -> generation);
  }
```

Three ordering rules in `replaceGeneration` are load-bearing, and the coalescing test exists because
of the first two:

1. `pending` is published **inside** the lock, before `stale.close()` is called. Closing the client
   dismisses its in-flight requests synchronously, so a sibling operation fails and calls
   `replaceGeneration` re-entrantly on this very thread. If `pending` were not already set, that
   sibling would see no generation and no handshake, and would either fail or start a second client.
2. `stale.close()` is called **outside** the lock, so nothing that runs during the dismissal is
   executed while holding it, and the replacement handshake only starts once the stale generation is
   really gone. Closing first is also what keeps at most one server process alive per owner.
3. `startGeneration(ticket)` runs from the `staleClosure` callback, which is also outside the lock, so
   the transport factory never runs under it either. A stale close that failed short-circuits to
   `replacement.completeExceptionally`, so no replacement transport is created at all.

`recover` reports the **original** failure and attaches the replacement failure with `addSuppressed`.
The caller asked for a tool call; the reason it could not happen is the original failure, and the
failed cleanup is context, not a replacement diagnosis.

- [ ] **Step 6: Run the validation test to see the cancellation guard fail**

Run: `./gradlew :integrations:agent-framework-mcp:test --tests 'io.github.hellices.agentframework.mcp.OwnedMcpClientValidationTest'`

Expected: FAIL, 1 of 8 in this class. `cancellingDuringValidationNeitherClosesNorReplacesTheGeneration`
fails on `assertThat(transport.closeCount()).isZero()` with `expected: 0 but was: 1`, because
validation as written in Step 5 reads "the ping did not succeed" as "the connection is gone", and a
cancelled ping is neither.

Task 4's `cancellingAnOperationNeverClosesTheGeneration` is failing too at this point, for the same
reason seen from the other side, so the module suite is red until Step 7. That is expected and is the
evidence the guard is load-bearing: the defect reaches a test written two tasks ago, which is exactly
why it is worth a guard rather than a comment.

- [ ] **Step 7: Classify cancellation before recovery**

Add the import `java.util.concurrent.CancellationException` to `OwnedMcpClientLifecycle`, add this
helper next to `recover`:

```java
  /**
   * Reports whether a failure is a caller or run cancellation rather than a connection problem.
   *
   * <p>Cancellation is the one failure that says nothing about the connection. A cancelled agent run
   * cancels its tool call, which cancels the validation ping in flight, and treating that as a lost
   * connection would close a healthy generation: a stdio server process would be killed and a
   * streamable HTTP session dropped because one caller changed its mind. It is matched by type
   * rather than by message because {@code CancellationException} is what {@code CompletableFuture}
   * itself raises.
   */
  private static boolean cancelled(Throwable failure) {
    return failure instanceof CancellationException;
  }
```

and add the guard to `validate`, immediately after `Throwable reported = AsyncStages.unwrap(failure);`
and **before** the unsupported-ping check:

```java
              if (cancelled(reported)) {
                return AsyncStages.<T>failed(reported);
              }
```

The guard sits in the failure branch of the validation stage, not around it, because a cancellation
that arrives before the ping is even sent already fails the operation without reaching this code.
Task 7 adds a second, separate guard on the call stage; the two are not one guard moved, because the
call stage also has to decide about connection loss and owner close, and validation does not.

- [ ] **Step 8: Run the validation test to verify it passes**

Run: `./gradlew :integrations:agent-framework-mcp:test --tests 'io.github.hellices.agentframework.mcp.OwnedMcpClientValidationTest'`

Expected: PASS, 8 tests.

- [ ] **Step 9: Run the module test and quality tasks**

Run: `./gradlew :integrations:agent-framework-mcp:test :integrations:agent-framework-mcp:quality`

Expected: PASS, including the Task 2, 3, and 4 test classes unchanged.

- [ ] **Step 10: Commit**

```bash
git add integrations/agent-framework-mcp/src/main/java/io/github/hellices/agentframework/mcp/internal/OwnedMcpClientLifecycle.java \
        integrations/agent-framework-mcp/src/test/java/io/github/hellices/agentframework/mcp/OwnedMcpClientValidationTest.java
git commit -m "$(cat <<'MSG'
mcp: validate the owned generation and replace it at most once

Every list and call validates the connection with ping first, and a server
that answers -32601 is recorded as having no ping so the generation is not
pinged again. Any other ping failure closes the stale generation, creates
one replacement, and runs the operation there. Concurrent failures from one
generation coalesce, so a broken connection cannot start two servers.

Cancellation is excluded from that rule. A cancelled run says nothing about
the connection, so it fails the operation and leaves the generation alone
instead of killing a server process the rest of the application is using.

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
MSG
)"
```

---

### Task 7: One retry of the original operation

**Files:**
- Modify: `integrations/agent-framework-mcp/src/main/java/io/github/hellices/agentframework/mcp/internal/OwnedMcpClientLifecycle.java`
- Test: `integrations/agent-framework-mcp/src/test/java/io/github/hellices/agentframework/mcp/OwnedMcpClientRetryTest.java` (create)

**Interfaces:**
- Consumes: `recover`, `replaceGeneration`, `Attempt.spendReconnect()`, and `Generation` from Task 6;
  `McpFailures.isConnectionLoss(Throwable)` from Task 5; `OwnedMcpAsyncOperations` and
  `McpToolDiscovery` from Task 4.
- Produces: `Generation` gains `boolean closedByOwner()`. `invoke` now retries the operation exactly
  once, and only when the failure is a typed connection loss or the generation was closed by this
  owner, and never when the failure is a cancellation. No new public API.

The second condition is not defensive padding. Verified SDK fact 15: when this owner closes a stale
generation, the SDK dismisses that generation's other in-flight requests with a bare
`RuntimeException` carrying no type and no cause. Matching that by message would be exactly the
fragility Task 5 rejects, so instead the generation records that its owner closed it, and a failure on
a generation the owner deliberately closed is retryable by construction.

- [ ] **Step 1: Write the failing retry test**

Create `integrations/agent-framework-mcp/src/test/java/io/github/hellices/agentframework/mcp/OwnedMcpClientRetryTest.java`:

```java
package io.github.hellices.agentframework.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.hellices.agentframework.api.tool.FunctionTool;
import io.github.hellices.agentframework.api.tool.ToolArguments;
import io.github.hellices.agentframework.api.tool.ToolContext;
import io.github.hellices.agentframework.api.tool.ToolResult;
import io.github.hellices.agentframework.mcp.internal.McpOwnedClientSettings;
import io.github.hellices.agentframework.mcp.internal.McpToolDiscovery;
import io.github.hellices.agentframework.mcp.internal.OwnedMcpAsyncOperations;
import io.github.hellices.agentframework.mcp.internal.OwnedMcpClientLifecycle;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpTransportSessionNotFoundException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * A lost connection costs one replacement and one repeat of the original call, never more.
 *
 * <p>The bound matters twice over. A retry loop against a server that keeps dropping the connection
 * would spawn processes or HTTP sessions without limit, and a retry of something that was not a
 * connection failure would run a tool's side effect a second time.
 */
class OwnedMcpClientRetryTest {

  private static final Duration SETTLE = Duration.ofSeconds(5);

  private static final Map<String, Object> SCHEMA =
      Map.of("type", "object", "properties", Map.of("query", Map.of("type", "string")));

  @Test
  void retriesTheOriginalOperationOnceAfterALostConnection() {
    InMemoryMcpTransport first =
        toolServer()
            .answeringPing()
            .failingSend(
                McpSchema.METHOD_TOOLS_LIST,
                () -> new McpTransportSessionNotFoundException("session expired"));
    InMemoryMcpTransport second = toolServer().answeringPing();
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(first, second);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());
    lifecycle.connect().join();

    listTools(lifecycle).join();

    assertThat(factory.createdCount()).isEqualTo(2);
    assertThat(first.closeCount()).isEqualTo(1);
    assertThat(first.countOf(McpSchema.METHOD_TOOLS_LIST)).isEqualTo(1);
    assertThat(second.countOf(McpSchema.METHOD_TOOLS_LIST)).isEqualTo(1);
    assertThat(second.countOf(McpSchema.METHOD_PING)).isZero();
  }

  @Test
  void neverReplacesTheGenerationTwiceForOneOperation() {
    InMemoryMcpTransport first =
        toolServer()
            .answeringPing()
            .failingSend(
                McpSchema.METHOD_TOOLS_LIST,
                () -> new McpTransportSessionNotFoundException("session expired"));
    InMemoryMcpTransport second =
        toolServer()
            .answeringPing()
            .failingSend(
                McpSchema.METHOD_TOOLS_LIST,
                () -> new McpTransportSessionNotFoundException("session expired again"));
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(first, second);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());
    lifecycle.connect().join();

    assertThatThrownBy(() -> listTools(lifecycle).join())
        .hasRootCauseInstanceOf(McpTransportSessionNotFoundException.class);

    assertThat(factory.createdCount()).isEqualTo(2);
    assertThat(first.closeCount()).isEqualTo(1);
    assertThat(second.closeCount()).isZero();
  }

  @Test
  void doesNotRetryAnApplicationError() {
    InMemoryMcpTransport transport =
        toolServer()
            .answeringPing()
            .answeringWithError(
                McpSchema.METHOD_TOOLS_LIST, McpSchema.ErrorCodes.INTERNAL_ERROR, "tool exploded");
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(transport);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());
    lifecycle.connect().join();

    assertThatThrownBy(() -> listTools(lifecycle).join())
        .hasRootCauseInstanceOf(McpError.class);

    assertThat(factory.createdCount()).isEqualTo(1);
    assertThat(transport.closeCount()).isZero();
    assertThat(transport.countOf(McpSchema.METHOD_TOOLS_LIST)).isEqualTo(1);
  }

  @Test
  void doesNotRetryWhenValidationAlreadySpentTheBudget() {
    InMemoryMcpTransport first =
        toolServer()
            .answeringWithError(
                McpSchema.METHOD_PING, McpSchema.ErrorCodes.INTERNAL_ERROR, "ping failed");
    InMemoryMcpTransport second =
        toolServer()
            .answeringPing()
            .failingSend(
                McpSchema.METHOD_TOOLS_LIST,
                () -> new McpTransportSessionNotFoundException("session expired"));
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(first, second);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());
    lifecycle.connect().join();

    assertThatThrownBy(() -> listTools(lifecycle).join())
        .hasRootCauseInstanceOf(McpTransportSessionNotFoundException.class);

    assertThat(factory.createdCount()).isEqualTo(2);
    assertThat(second.closeCount()).isZero();
  }

  @Test
  void retriesACallThatOurOwnReplacementDismissed() {
    InMemoryMcpTransport first =
        toolServer()
            .answeringPing()
            .withholding(McpSchema.METHOD_TOOLS_LIST)
            .failingSend(
                McpSchema.METHOD_TOOLS_CALL,
                () -> new McpTransportSessionNotFoundException("session expired"));
    InMemoryMcpTransport second = toolServer().answeringPing();
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(first, second);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());
    lifecycle.connect().join();

    CompletableFuture<McpSchema.ListToolsResult> dismissed = listTools(lifecycle);
    CompletableFuture<McpSchema.CallToolResult> lostConnection =
        lifecycle.execute(
            client -> client.callTool(new McpSchema.CallToolRequest("search-issues", null, null)));

    assertThat(lostConnection).succeedsWithin(SETTLE);
    assertThat(dismissed).succeedsWithin(SETTLE);
    assertThat(factory.createdCount()).isEqualTo(2);
    assertThat(first.closeCount()).isEqualTo(1);
    assertThat(second.countOf(McpSchema.METHOD_TOOLS_LIST)).isEqualTo(1);
    assertThat(second.countOf(McpSchema.METHOD_TOOLS_CALL)).isEqualTo(1);
  }

  @Test
  void retriesTheCallMadeThroughADiscoveredTool() {
    InMemoryMcpTransport first =
        toolServer()
            .answeringPing()
            .failingSend(
                McpSchema.METHOD_TOOLS_CALL,
                () -> new McpTransportSessionNotFoundException("session expired"));
    InMemoryMcpTransport second = toolServer().answeringPing();
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(first, second);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());
    lifecycle.connect().join();

    List<FunctionTool> tools =
        new McpToolDiscovery(
                new OwnedMcpAsyncOperations(lifecycle), McpToolAdapterOptions.defaults())
            .discover()
            .toCompletableFuture()
            .join();
    ToolResult result =
        tools
            .get(0)
            .execute(new ToolArguments(Map.of("query", "open")), new ToolContext(null, Map.of()))
            .toCompletableFuture()
            .join();

    assertThat(result.error()).isFalse();
    assertThat(result.content().get(0).text()).isEqualTo("found 2 issues");
    assertThat(factory.createdCount()).isEqualTo(2);
    assertThat(first.closeCount()).isEqualTo(1);
    assertThat(second.countOf(McpSchema.METHOD_TOOLS_CALL)).isEqualTo(1);
  }

  @Test
  void closingDuringAnInFlightCallFailsThatCallWithoutReconnecting() {
    // An explicit close is not a lost connection, even though the SDK dismisses the in-flight call
    // with the same untyped failure a lost connection produces. Retrying here would resurrect the
    // server the caller just shut down, which is the one thing an owned client must never do.
    InMemoryMcpTransport transport =
        toolServer().answeringPing().withholding(McpSchema.METHOD_TOOLS_CALL);
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(transport);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());
    lifecycle.connect().join();

    CompletableFuture<McpSchema.CallToolResult> inFlight =
        lifecycle.execute(
            client -> client.callTool(new McpSchema.CallToolRequest("search-issues", null, null)));

    lifecycle.close().join();

    // The dismissal type carries no information (verified SDK fact 15), so the assertion is on what
    // the owner did about it: it looked for a generation to repeat the call on, found none, and
    // attached that as the reason. One transport, one close, no factory call.
    Throwable failure = catchThrowable(inFlight::join);

    assertThat(failure.getCause().getSuppressed())
        .singleElement()
        .satisfies(
            suppressed ->
                assertThat(suppressed)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("connect()"));
    assertThat(factory.createdCount()).isEqualTo(1);
    assertThat(transport.closeCount()).isEqualTo(1);
    assertThat(transport.countOf(McpSchema.METHOD_TOOLS_CALL)).isEqualTo(1);
  }

  private static CompletableFuture<McpSchema.ListToolsResult> listTools(
      OwnedMcpClientLifecycle lifecycle) {
    return lifecycle.execute(client -> client.listTools(McpSchema.FIRST_PAGE, null));
  }

  private static InMemoryMcpTransport toolServer() {
    return new InMemoryMcpTransport()
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
                    List.of(new McpSchema.TextContent(null, "found 2 issues", null)),
                    Boolean.FALSE,
                    null,
                    null));
  }

  private static McpOwnedClientSettings settings() {
    return new McpOwnedClientSettings(
        new PermissiveJsonSchemaValidator(), Duration.ofSeconds(5), Duration.ofSeconds(5));
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :integrations:agent-framework-mcp:test --tests 'io.github.hellices.agentframework.mcp.OwnedMcpClientRetryTest'`

Expected: FAIL. `retriesTheOriginalOperationOnceAfterALostConnection` fails with
`McpTransportSessionNotFoundException: session expired`, because `invoke` still reports the first
failure instead of retrying, and `closingDuringAnInFlightCallFailsThatCallWithoutReconnecting` fails
because the dismissal carries no suppressed failure yet.

- [ ] **Step 3: Let a generation report that its owner closed it**

Add this accessor to the nested `Generation` class, next to `pingUnsupported()`:

```java
    boolean closedByOwner() {
      return closed.get();
    }
```

- [ ] **Step 4: Retry the operation once**

Replace the `return inFlight;` line at the end of `invoke` with:

```java
    return inFlight
        .handle(
            (result, failure) -> {
              if (failure == null) {
                return CompletableFuture.completedFuture(result);
              }
              Throwable reported = AsyncStages.unwrap(failure);
              return retryable(reported, generation)
                  ? recover(operation, generation, attempt, reported)
                  : AsyncStages.<T>failed(reported);
            })
        .thenCompose(Function.identity());
```

and add this method after `invoke`:

```java
  /**
   * Decides whether a failed call may be repeated on a replacement generation.
   *
   * <p>This is the call stage's own guard, separate from the validation guard Task 6 installed,
   * because the two answer different questions. Validation only has to decide whether the ping
   * failure says anything about the connection; a call also has to decide whether repeating it is
   * safe. They share only the cancellation rule.
   *
   * <p>A cancelled call is never repeated: the caller asked for it to stop. A generation this owner
   * closed makes its failure repeatable regardless of type, because the SDK dismisses the in-flight
   * calls of a closed client with an untyped failure, and that dismissal is the direct consequence of
   * a replacement this owner started for another operation, not of anything the server did. When the
   * close came from {@code close()} rather than from a replacement, the repeat finds no generation
   * and fails on the connect requirement, which is the correct answer: an explicit close must not
   * bring the server back.
   */
  private static boolean retryable(Throwable failure, Generation generation) {
    if (cancelled(failure)) {
      return false;
    }
    return McpFailures.isConnectionLoss(failure) || generation.closedByOwner();
  }
```

No new import is needed; `java.util.concurrent.CancellationException` arrived in Task 6 with
`cancelled(Throwable)`.

- [ ] **Step 5: Run the retry test to verify it passes**

Run: `./gradlew :integrations:agent-framework-mcp:test --tests 'io.github.hellices.agentframework.mcp.OwnedMcpClientRetryTest'`

Expected: PASS, 7 tests.

- [ ] **Step 6: Run the whole module and quality**

Run: `./gradlew :integrations:agent-framework-mcp:test :integrations:agent-framework-mcp:quality`

Expected: PASS. `OwnedMcpClientOperationTest.cancellingAnOperationNeverClosesTheGeneration` and
`OwnedMcpClientValidationTest.cancellingDuringValidationNeitherClosesNorReplacesTheGeneration` are the
guards that the new retry path does not turn a cancellation into a reconnect.

- [ ] **Step 7: Commit**

```bash
git add integrations/agent-framework-mcp/src/main/java/io/github/hellices/agentframework/mcp/internal/OwnedMcpClientLifecycle.java \
        integrations/agent-framework-mcp/src/test/java/io/github/hellices/agentframework/mcp/OwnedMcpClientRetryTest.java
git commit -m "$(cat <<'MSG'
mcp: repeat a lost call once on the replacement generation

A typed connection loss, or a dismissal caused by a replacement this owner
started, is repeated once on the new generation. A cancelled call and an
application error are not, because repeating a tool call that the server
actually answered would run its side effect twice.

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
MSG
)"
```

---

### Task 8: Paged discovery across a replacement

A whole `discoverTools()` is **one** logical operation with **one** reconnect budget. Tasks 6 and 7
made a single request survive a replacement by repeating it, and that is exactly wrong for a page: an
MCP cursor is opaque state belonging to the session that issued it, so repeating a page request with
the old cursor on a new session asks a stranger to continue someone else's sentence. The server may
reject it, or worse, answer from a different position and hand back a catalogue that is silently
wrong.

The rule this task installs:

1. A page request never repeats itself on a replacement generation.
2. When a page fails because the connection was replaced, the reader throws away every tool, every
   local name, and every cursor it accumulated, and starts again from `McpSchema.FIRST_PAGE`.
3. That restart happens at most once per discovery, because the whole discovery shares one budget. A
   second connection failure surfaces to the caller.
4. The `FunctionTool`s a discovery returns do not share the discovery's budget. Each call they make is
   a fresh operation with a fresh budget, because a tool call happens long after discovery ended.
5. Borrowed discovery is unchanged in every respect.

**Files:**
- Create: `integrations/agent-framework-mcp/src/main/java/io/github/hellices/agentframework/mcp/internal/McpConnectionReplacedException.java`
- Modify: `integrations/agent-framework-mcp/src/main/java/io/github/hellices/agentframework/mcp/internal/McpAsyncOperations.java`
- Modify: `integrations/agent-framework-mcp/src/main/java/io/github/hellices/agentframework/mcp/internal/McpToolDiscovery.java`
- Modify: `integrations/agent-framework-mcp/src/main/java/io/github/hellices/agentframework/mcp/internal/OwnedMcpClientLifecycle.java`
- Modify: `integrations/agent-framework-mcp/src/main/java/io/github/hellices/agentframework/mcp/internal/OwnedMcpAsyncOperations.java`
- Test: `integrations/agent-framework-mcp/src/test/java/io/github/hellices/agentframework/mcp/McpDiscoveryPagingTest.java` (create)

**Interfaces:**
- Consumes: `execute`, `recover`, `replaceGeneration`, `Attempt` from Tasks 4, 6, and 7; the already
  shipped `McpToolDiscovery`, `McpAsyncOperations`, `BorrowedMcpAsyncOperations`, and
  `ConnectedMcpClientAdapter`.
- Produces:
  - package-private `final class McpConnectionReplacedException extends RuntimeException`.
  - `default McpAsyncOperations McpAsyncOperations.forPagedOperation()` returning `this`.
  - `OwnedMcpClientLifecycle.Attempt` becomes package-private and gains
    `Attempt(boolean repeatOnReplacement)` and `boolean repeatsOnReplacement()`; the lifecycle gains
    `static Attempt pagedAttempt()` and the package-private overload
    `<T> CompletableFuture<T> execute(Function<McpAsyncClient, Mono<T>> operation, Attempt attempt)`.
  - `OwnedMcpAsyncOperations` gains a package-private constructor taking a shared paged attempt and an
    override of `forPagedOperation()`.
  - `McpToolDiscovery.PageReader` gains the paged scope and a one-shot restart.

- [ ] **Step 1: Write the failing paging test**

Create `integrations/agent-framework-mcp/src/test/java/io/github/hellices/agentframework/mcp/McpDiscoveryPagingTest.java`:

```java
package io.github.hellices.agentframework.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hellices.agentframework.api.tool.FunctionTool;
import io.github.hellices.agentframework.api.tool.ToolArguments;
import io.github.hellices.agentframework.api.tool.ToolContext;
import io.github.hellices.agentframework.api.tool.ToolResult;
import io.github.hellices.agentframework.mcp.internal.McpOwnedClientSettings;
import io.github.hellices.agentframework.mcp.internal.McpToolDiscovery;
import io.github.hellices.agentframework.mcp.internal.OwnedMcpAsyncOperations;
import io.github.hellices.agentframework.mcp.internal.OwnedMcpClientLifecycle;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpTransportSessionNotFoundException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A paged discovery is one operation, and a cursor belongs to the session that issued it.
 *
 * <p>Every test records the cursors each transport was asked for, because that list is the whole
 * contract: a cursor from a dead session must never appear in a request to a live one, and a
 * discovery that had to reconnect must start again from the first page rather than stitch two
 * catalogues together.
 */
class McpDiscoveryPagingTest {

  private static final Duration BLOCK = Duration.ofSeconds(5);

  private static final Map<String, Object> SCHEMA =
      Map.of("type", "object", "properties", Map.of("query", Map.of("type", "string")));

  @Test
  void readsEveryPageOfOneGenerationInOrder() {
    List<String> cursors = new ArrayList<>();
    InMemoryMcpTransport transport = catalogue(cursors, Integer.MAX_VALUE);
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(transport);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());
    lifecycle.connect().join();

    List<FunctionTool> tools = discover(lifecycle);

    assertThat(names(tools)).containsExactly("search-issues", "close-issues");
    assertThat(cursors).containsExactly(null, "page-2");
    assertThat(factory.createdCount()).isEqualTo(1);
  }

  @Test
  void restartsFromTheFirstPageOnTheReplacementGeneration() {
    List<String> staleCursors = new ArrayList<>();
    List<String> freshCursors = new ArrayList<>();
    InMemoryMcpTransport stale = catalogue(staleCursors, 1);
    InMemoryMcpTransport fresh = catalogue(freshCursors, Integer.MAX_VALUE);
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(stale, fresh);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());
    lifecycle.connect().join();

    List<FunctionTool> tools = discover(lifecycle);

    // The replacement is asked for the first page, not for the cursor the dead session handed out,
    // and the result is that session's catalogue alone. Page one of the stale generation was
    // discarded: had it been kept, its "search-issues" would have collided with the replacement's
    // and failed the discovery, and had it been merged, there would be three tools here.
    assertThat(staleCursors).containsExactly(null, "page-2");
    assertThat(freshCursors).containsExactly(null, "page-2");
    assertThat(names(tools)).containsExactly("search-issues", "close-issues");
    assertThat(factory.createdCount()).isEqualTo(2);
    assertThat(stale.closeCount()).isEqualTo(1);
    assertThat(fresh.closeCount()).isZero();
  }

  @Test
  void surfacesASecondConnectionFailureWithoutAThirdGeneration() {
    List<String> staleCursors = new ArrayList<>();
    List<String> freshCursors = new ArrayList<>();
    InMemoryMcpTransport stale = catalogue(staleCursors, 1);
    InMemoryMcpTransport fresh = catalogue(freshCursors, 1);
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(stale, fresh);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());
    lifecycle.connect().join();

    assertThatThrownBy(() -> discover(lifecycle))
        .rootCause()
        .isInstanceOf(McpTransportSessionNotFoundException.class);

    assertThat(factory.createdCount()).isEqualTo(2);
    assertThat(freshCursors).containsExactly(null, "page-2");
    assertThat(fresh.closeCount()).isZero();
  }

  @Test
  void givesEachDiscoveredToolItsOwnReconnectBudget() {
    InMemoryMcpTransport stale = catalogue(new ArrayList<>(), 1);
    InMemoryMcpTransport fresh =
        catalogue(new ArrayList<>(), Integer.MAX_VALUE)
            .failingSend(
                McpSchema.METHOD_TOOLS_CALL,
                () -> new McpTransportSessionNotFoundException("session expired"));
    InMemoryMcpTransport third = catalogue(new ArrayList<>(), Integer.MAX_VALUE);
    ScriptedMcpTransportFactory factory = new ScriptedMcpTransportFactory(stale, fresh, third);
    OwnedMcpClientLifecycle lifecycle = new OwnedMcpClientLifecycle(factory, settings());
    lifecycle.connect().join();

    List<FunctionTool> tools = discover(lifecycle);
    assertThat(factory.createdCount()).isEqualTo(2);

    ToolResult result =
        tools
            .get(0)
            .execute(new ToolArguments(Map.of("query", "open")), new ToolContext(null, Map.of()))
            .toCompletableFuture()
            .join();

    // The discovery spent its own budget and the tool still has one, because a tool call made
    // minutes later is not part of the operation that discovered it.
    assertThat(result.error()).isFalse();
    assertThat(factory.createdCount()).isEqualTo(3);
    assertThat(third.countOf(McpSchema.METHOD_TOOLS_CALL)).isEqualTo(1);
  }

  @Test
  void leavesBorrowedPagingUntouched() {
    List<String> cursors = new ArrayList<>();
    InMemoryMcpTransport transport = catalogue(cursors, 1);
    McpAsyncClient client =
        McpClient.async(transport)
            .requestTimeout(BLOCK)
            .initializationTimeout(BLOCK)
            .jsonSchemaValidator(new PermissiveJsonSchemaValidator())
            .build();
    client.initialize().block(BLOCK);
    ConnectedMcpClientAdapter adapter = new ConnectedMcpClientAdapter(client);

    // A borrowed adapter owns no connection, so it cannot replace one. The failure is the caller's
    // to handle, and the client it was lent stays open and initialized.
    assertThatThrownBy(() -> adapter.discoverTools().toCompletableFuture().join())
        .rootCause()
        .isInstanceOf(McpTransportSessionNotFoundException.class);

    assertThat(cursors).containsExactly(null, "page-2");
    assertThat(client.isInitialized()).isTrue();
    assertThat(transport.closeCount()).isZero();
  }

  private static List<FunctionTool> discover(OwnedMcpClientLifecycle lifecycle) {
    return new McpToolDiscovery(
            new OwnedMcpAsyncOperations(lifecycle), McpToolAdapterOptions.defaults())
        .discover()
        .toCompletableFuture()
        .join();
  }

  private static List<String> names(List<FunctionTool> tools) {
    return tools.stream().map(tool -> tool.definition().name()).toList();
  }

  /**
   * A two page catalogue that records the cursor of every page it was asked for and drops the
   * connection once it has answered {@code answerablePages} of them.
   */
  private static InMemoryMcpTransport catalogue(List<String> cursors, int answerablePages) {
    return new InMemoryMcpTransport()
        .answeringPing()
        .answering(
            McpSchema.METHOD_TOOLS_CALL,
            params ->
                new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent(null, "found 2 issues", null)),
                    Boolean.FALSE,
                    null,
                    null))
        .answering(
            McpSchema.METHOD_TOOLS_LIST,
            params -> {
              String cursor = ((McpSchema.PaginatedRequest) params).cursor();
              cursors.add(cursor);
              if (cursors.size() > answerablePages) {
                throw new McpTransportSessionNotFoundException("session expired");
              }
              return cursor == null
                  ? new McpSchema.ListToolsResult(List.of(tool("search-issues")), "page-2", null)
                  : new McpSchema.ListToolsResult(List.of(tool("close-issues")), null, null);
            });
  }

  private static McpSchema.Tool tool(String name) {
    return new McpSchema.Tool(name, null, name, SCHEMA, null, null, null, null);
  }

  private static McpOwnedClientSettings settings() {
    return new McpOwnedClientSettings(
        new PermissiveJsonSchemaValidator(), Duration.ofSeconds(5), Duration.ofSeconds(5));
  }
}
```

The scripted answer throws rather than using `failingSend`, because `failingSend` fails every request
for a method and this fixture has to answer the first page and then fail the second. A throw inside a
scripted answer propagates out of the `Mono.defer` in `sendMessage` and reaches the client as that
exception, unwrapped, exactly like `failingSend` does.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :integrations:agent-framework-mcp:test --tests 'io.github.hellices.agentframework.mcp.McpDiscoveryPagingTest'`

Expected: FAIL, 3 of 5. `readsEveryPageOfOneGenerationInOrder` and `leavesBorrowedPagingUntouched`
pass, because neither reconnects. `restartsFromTheFirstPageOnTheReplacementGeneration` fails with
`expected: [null, "page-2"] but was: ["page-2", ...]`: the replacement is asked to continue the dead
session's cursor. `surfacesASecondConnectionFailureWithoutAThirdGeneration` and
`givesEachDiscoveredToolItsOwnReconnectBudget` fail for the same reason.

- [ ] **Step 3: Add the internal restart signal**

Create `integrations/agent-framework-mcp/src/main/java/io/github/hellices/agentframework/mcp/internal/McpConnectionReplacedException.java`:

```java
package io.github.hellices.agentframework.mcp.internal;

/**
 * Reports that the connection behind a paged read was replaced, so the read must start again.
 *
 * <p>This is a signal between two collaborators in this package, not a diagnosis for a caller. A
 * single request survives a replacement by being repeated on the new generation, but a page cannot:
 * its cursor is opaque state that belongs to the session that issued it, and continuing someone
 * else's pagination on a new session is either rejected or, worse, answered from a different
 * position. The operation that caused the replacement is kept as the cause so that a reader which
 * cannot restart still reports something true.
 */
final class McpConnectionReplacedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  McpConnectionReplacedException(Throwable cause) {
    super("the MCP connection was replaced while reading a paged result", cause);
  }
}
```

- [ ] **Step 4: Give the operations port a paged scope**

Add this method to `McpAsyncOperations`, after `callTool`:

```java
  /**
   * Returns the operations to use for one paged read, such as a whole tool discovery.
   *
   * <p>Reading a catalogue is one logical operation even though it is many requests, and an
   * implementation that owns its connection needs to know that in order to spend a recovery budget
   * once for the whole read rather than once per page. An implementation that borrows its connection
   * recovers from nothing and returns itself, which is why this is a default method.
   *
   * @return the operations for one paged read, never {@code null}
   */
  default McpAsyncOperations forPagedOperation() {
    return this;
  }
```

`BorrowedMcpAsyncOperations` is deliberately left alone: inheriting the default is the statement that
borrowed paging is unchanged.

- [ ] **Step 5: Let an attempt describe what a replacement means for it**

In `OwnedMcpClientLifecycle`, replace the `Attempt` class comment and declaration, which Task 4
introduced as `/** Tracks the in-flight call of one {@code execute} so cancellation can reach it. */`
above `private static final class Attempt {`, with:

```java
  /**
   * Tracks the in-flight call of one operation so cancellation can reach it, and carries that
   * operation's single reconnect budget.
   *
   * <p>An operation is not always one request. A paged read is one attempt spanning many requests,
   * and it is the attempt that decides what happens after a replacement: a single request repeats
   * itself, a paged read cannot and asks its reader to start over instead.
   */
  static final class Attempt {
```

add the field and constructor:

```java
    private final boolean repeatOnReplacement;

    Attempt(boolean repeatOnReplacement) {
      this.repeatOnReplacement = repeatOnReplacement;
    }

    boolean repeatsOnReplacement() {
      return repeatOnReplacement;
    }
```

add the factory next to `execute`:

```java
  /**
   * Creates the shared attempt of one paged read.
   *
   * @return an attempt whose single reconnect covers every request of the read, never {@code null}
   */
  static Attempt pagedAttempt() {
    return new Attempt(false);
  }
```

split `execute` into the public entry point and a package-private overload:

```java
  public <T> CompletableFuture<T> execute(Function<McpAsyncClient, Mono<T>> operation) {
    return execute(operation, new Attempt(true));
  }

  /**
   * Runs one operation on the current generation as part of the given attempt.
   *
   * @param operation produces the SDK call for a given client, never {@code null}
   * @param attempt the attempt this operation belongs to, never {@code null}
   * @param <T> the operation result type
   * @return a stage completing with the operation result, never {@code null}
   */
  <T> CompletableFuture<T> execute(Function<McpAsyncClient, Mono<T>> operation, Attempt attempt) {
    if (operation == null) {
      return AsyncStages.failed(new IllegalArgumentException("operation must not be null"));
    }
    CompletableFuture<T> result =
        currentGeneration().thenCompose(generation -> validate(operation, generation, attempt));
    return AsyncStages.cancellable(result, attempt::cancel);
  }
```

and replace the `handle` block of `recover`, which Task 6 wrote with the success branch first, so an
attempt that does not repeat reports the signal instead of repeating the request. The failure branch
is unchanged in behavior and only moves to the top so the three outcomes read in the order they
occur:

```java
    return replaceGeneration(stale)
        .handle(
            (replacement, replacementFailure) -> {
              if (replacementFailure != null) {
                failure.addSuppressed(AsyncStages.unwrap(replacementFailure));
                return AsyncStages.<T>failed(failure);
              }
              if (!attempt.repeatsOnReplacement()) {
                return AsyncStages.<T>failed(new McpConnectionReplacedException(failure));
              }
              return invoke(operation, replacement, attempt);
            })
        .thenCompose(Function.identity());
```

The replacement is already adopted by the time this runs, so the reader's next request lands on it
without asking for another one. The budget is already spent, so a second failure takes the
`spendReconnect` early return and surfaces the real failure rather than another restart signal.

- [ ] **Step 6: Scope the owned operations to a paged read**

In `OwnedMcpAsyncOperations`, add the field, the second constructor, the override, and route
`listTools` through a paged attempt:

```java
  private final OwnedMcpClientLifecycle lifecycle;
  private final OwnedMcpClientLifecycle.Attempt pagedOperation;

  public OwnedMcpAsyncOperations(OwnedMcpClientLifecycle lifecycle) {
    this(lifecycle, null);
  }

  private OwnedMcpAsyncOperations(
      OwnedMcpClientLifecycle lifecycle, OwnedMcpClientLifecycle.Attempt pagedOperation) {
    if (lifecycle == null) {
      throw new IllegalArgumentException("lifecycle must not be null");
    }
    this.lifecycle = lifecycle;
    this.pagedOperation = pagedOperation;
  }

  @Override
  public McpAsyncOperations forPagedOperation() {
    return new OwnedMcpAsyncOperations(lifecycle, OwnedMcpClientLifecycle.pagedAttempt());
  }

  @Override
  public CompletionStage<McpSchema.ListToolsResult> listTools(
      String cursor, Map<String, Object> meta) {
    // Always a paged attempt, scoped or not. A page request must never be repeated with its own
    // cursor on a new session, and an unscoped listTools has no reader to restart, so the honest
    // outcome there is a failure rather than a silently wrong page.
    OwnedMcpClientLifecycle.Attempt attempt =
        pagedOperation == null ? OwnedMcpClientLifecycle.pagedAttempt() : pagedOperation;
    return lifecycle.execute(client -> client.listTools(cursor, meta), attempt);
  }

  @Override
  public CompletionStage<McpSchema.CallToolResult> callTool(McpSchema.CallToolRequest request) {
    if (request == null) {
      return AsyncStages.failed(new IllegalArgumentException("request must not be null"));
    }
    return lifecycle.execute(client -> client.callTool(request));
  }
```

`callTool` keeps the plain `execute`, which is what gives every discovered tool its own budget: the
invoker holds the unscoped operations the discovery was constructed with, never the paged scope.

Task 4's class comment ends with "Instances are immutable and safe to share once constructed", which a
scoped instance would make false, because it carries one mutable attempt. Replace that sentence with:

```java
 * <p>The instance a caller constructs is immutable and safe to share. The instance {@link
 * #forPagedOperation()} returns is not: it carries the single attempt, and therefore the single
 * reconnect budget, of one paged read, so it belongs to that read and must not outlive it.
```

- [ ] **Step 7: Restart the reader from the first page**

In `McpToolDiscovery`, take the paged scope once per discovery and hand it to the reader:

```java
  public CompletableFuture<List<FunctionTool>> discover() {
    McpAsyncOperations pageOperations = operations.forPagedOperation();
    if (pageOperations == null) {
      return AsyncStages.failed(
          new IllegalStateException("MCP operations returned no scope for a paged read"));
    }
    PageReader reader = new PageReader(pageOperations);
    return AsyncStages.cancellable(reader.readAll(), reader::cancel);
  }
```

Give `PageReader` the scope and a one-shot restart flag:

```java
    private final McpAsyncOperations pageOperations;
    private final AtomicBoolean restartable = new AtomicBoolean(true);

    PageReader(McpAsyncOperations pageOperations) {
      this.pageOperations = pageOperations;
    }
```

and request pages through it, by changing the one line in `requestPage`:

```java
        page = AsyncStages.requireStage(pageOperations.listTools(cursor, null), "tools/list");
```

Add the restart itself, after `requestPage`:

```java
    /**
     * Throws away everything read so far and starts the catalogue again from its first page, when
     * the page failed only because the connection behind it was replaced.
     *
     * <p>Everything has to go, not just the cursor. The collected tools came from a session that no
     * longer exists, and keeping them would either duplicate the entries the new session republishes
     * or fail the discovery on a local name collision with them. The seen cursors have to go for a
     * blunter reason: the new session will very likely hand out the same cursor strings, and the
     * repeated-cursor guard would read that as a server paging forever. Clearing them also restores
     * the page bound, so the replacement gets a whole catalogue's worth of pages rather than whatever
     * the dead session left over.
     *
     * <p>The flag bounds this loop on its own. The lifecycle also allows one replacement per paged
     * read, so in practice the second restart never arrives, but this class already bounds paging by
     * repeated cursors and by a page count, and a reader that can be restarted by something outside
     * it should bound that too.
     */
    private boolean restart(Throwable failure) {
      if (!(AsyncStages.unwrap(failure) instanceof McpConnectionReplacedException)) {
        return false;
      }
      if (!restartable.compareAndSet(true, false)) {
        return false;
      }
      tools.clear();
      localNames.clear();
      seenCursors.clear();
      cursor = McpSchema.FIRST_PAGE;
      return true;
    }
```

and handle it in both failure branches of `drive`:

```java
        if (!page.isDone()) {
          page.whenComplete(
              (result, failure) -> {
                if (failure != null) {
                  if (restart(failure)) {
                    drive(discovered);
                  } else {
                    discovered.completeExceptionally(AsyncStages.unwrap(failure));
                  }
                } else if (advance(result, requested, discovered)) {
                  drive(discovered);
                }
              });
          return;
        }
        McpSchema.ListToolsResult result;
        try {
          result = page.join();
        } catch (RuntimeException failure) {
          if (restart(failure)) {
            continue;
          }
          discovered.completeExceptionally(AsyncStages.unwrap(failure));
          return;
        }
```

Update the class comment of `McpToolDiscovery` so it stops describing the client as borrowed only:

```java
 * <p>A whole discovery is one logical operation. When the operations port reports that the
 * connection behind a page was replaced, everything read so far is discarded and the catalogue is
 * read again from its first page, because a cursor belongs to the session that issued it. A port
 * over a borrowed client never reports that, so borrowed discovery is unaffected.
```

- [ ] **Step 8: Run the paging test to verify it passes**

Run: `./gradlew :integrations:agent-framework-mcp:test --tests 'io.github.hellices.agentframework.mcp.McpDiscoveryPagingTest'`

Expected: PASS, 5 tests.

- [ ] **Step 9: Run the module test and quality tasks**

Run: `./gradlew :integrations:agent-framework-mcp:test :integrations:agent-framework-mcp:quality`

Expected: PASS. `ConnectedMcpClientAdapterTest`, `BorrowedMcpClientIntegrationTest`, and
`OwnedMcpClientOperationTest.discoversToolsThroughTheOwnedPort` are the regression guards that the new
scope changed neither borrowed discovery nor single-generation discovery.

- [ ] **Step 10: Commit**

```bash
git add integrations/agent-framework-mcp/src/main/java/io/github/hellices/agentframework/mcp/internal/McpConnectionReplacedException.java \
        integrations/agent-framework-mcp/src/main/java/io/github/hellices/agentframework/mcp/internal/McpAsyncOperations.java \
        integrations/agent-framework-mcp/src/main/java/io/github/hellices/agentframework/mcp/internal/McpToolDiscovery.java \
        integrations/agent-framework-mcp/src/main/java/io/github/hellices/agentframework/mcp/internal/OwnedMcpClientLifecycle.java \
        integrations/agent-framework-mcp/src/main/java/io/github/hellices/agentframework/mcp/internal/OwnedMcpAsyncOperations.java \
        integrations/agent-framework-mcp/src/test/java/io/github/hellices/agentframework/mcp/McpDiscoveryPagingTest.java
git commit -m "$(cat <<'MSG'
mcp: restart a paged discovery on a replaced connection

A cursor belongs to the session that issued it, so a page request is never
repeated on a replacement generation. A whole discovery is one operation with
one reconnect: when the connection is replaced, everything read so far is
discarded and the catalogue is read again from its first page, once. A second
connection failure surfaces.

Discovered tools keep their own budget, because a tool call made long after
discovery is not part of the operation that found it. Borrowed discovery is
unchanged: it owns no connection, so it can never see a replacement.

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
MSG
)"
```

---

### Task 9: The stdio facade

**Files:**
- Create: `integrations/agent-framework-mcp/src/main/java/io/github/hellices/agentframework/mcp/internal/OwnedMcpTools.java`
- Create: `integrations/agent-framework-mcp/src/main/java/io/github/hellices/agentframework/mcp/McpStdioTools.java`
- Test: `integrations/agent-framework-mcp/src/test/java/io/github/hellices/agentframework/mcp/RejectingMcpJsonMapper.java` (create)
- Test: `integrations/agent-framework-mcp/src/test/java/io/github/hellices/agentframework/mcp/McpStdioToolsTest.java` (create)

**Interfaces:**
- Consumes: `OwnedMcpClientLifecycle`, `OwnedMcpAsyncOperations`, `McpOwnedClientSettings`,
  `McpClientTransportFactory` from Tasks 2, 4, and 6; the already shipped `McpToolDiscovery` and
  `McpToolAdapterOptions`.
- Produces:
  - `public final class OwnedMcpTools` in `…mcp.internal`, constructor
    `OwnedMcpTools(McpClientTransportFactory transportFactory, McpOwnedClientSettings settings,
    McpToolAdapterOptions options)`, methods `CompletionStage<Void> connect()`,
    `CompletionStage<List<FunctionTool>> discoverTools()`, `CompletionStage<Void> close()`. Task 10
    reuses this class unchanged.
  - `public final class McpStdioTools` in `…mcp`, with `static Builder builder(ServerParameters
    serverParameters)`, the same three instance methods, and a package-private constructor
    `McpStdioTools(McpClientTransportFactory, McpOwnedClientSettings, McpToolAdapterOptions)` used
    only by tests.
  - `McpStdioTools.Builder` with `jsonMapper(McpJsonMapper)`, `schemaValidator(JsonSchemaValidator)`,
    `requestTimeout(Duration)`, `initializationTimeout(Duration)`, `toolOptions(McpToolAdapterOptions)`,
    `build()`.

There is deliberately no connect timeout on this builder: official SDK 2.0.0 stdio exposes none.
There is deliberately no working directory and no encoding: `ServerParameters` has no such option
(verified SDK fact 2). Say so in the Javadoc rather than inventing an option that silently does
nothing.

- [ ] **Step 1: Add the JSON mapper test double**

Create `integrations/agent-framework-mcp/src/test/java/io/github/hellices/agentframework/mcp/RejectingMcpJsonMapper.java`:

```java
package io.github.hellices.agentframework.mcp;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;

/**
 * An {@link McpJsonMapper} that refuses to do anything.
 *
 * <p>The stdio builder requires a mapper because the SDK stdio transport does, but no test in this
 * module ever serializes an MCP message: the in-memory transport passes result objects through. A
 * mapper that throws makes an accidental serialization obvious instead of quietly succeeding.
 */
final class RejectingMcpJsonMapper implements McpJsonMapper {

  @Override
  public <T> T readValue(String content, Class<T> type) {
    throw refuse();
  }

  @Override
  public <T> T readValue(byte[] content, Class<T> type) {
    throw refuse();
  }

  @Override
  public <T> T readValue(String content, TypeRef<T> type) {
    throw refuse();
  }

  @Override
  public <T> T readValue(byte[] content, TypeRef<T> type) {
    throw refuse();
  }

  @Override
  public <T> T convertValue(Object source, Class<T> type) {
    throw refuse();
  }

  @Override
  public <T> T convertValue(Object source, TypeRef<T> type) {
    throw refuse();
  }

  @Override
  public String writeValueAsString(Object value) {
    throw refuse();
  }

  @Override
  public byte[] writeValueAsBytes(Object value) {
    throw refuse();
  }

  private static UnsupportedOperationException refuse() {
    return new UnsupportedOperationException(
        "no test in this module serializes MCP messages; this mapper exists only to satisfy a"
            + " required builder argument");
  }
}
```

- [ ] **Step 2: Write the failing stdio facade test**

Create `integrations/agent-framework-mcp/src/test/java/io/github/hellices/agentframework/mcp/McpStdioToolsTest.java`:

```java
package io.github.hellices.agentframework.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hellices.agentframework.api.tool.FunctionTool;
import io.github.hellices.agentframework.mcp.internal.McpOwnedClientSettings;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The stdio facade is validated without ever starting a process.
 *
 * <p>No test here constructs a {@code StdioClientTransport}: its constructor allocates three
 * single-thread schedulers before any process exists, and a test that never connects would leak them.
 * Everything the facade does with a real transport is exercised through the package-private seam.
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
    return McpStdioTools.builder(ServerParameters.builder("no-such-command").build())
        .jsonMapper(new RejectingMcpJsonMapper())
        .schemaValidator(new PermissiveJsonSchemaValidator());
  }
}
```

The last test is the one the review asked for. `McpToolDiscovery` dereferences its options in its own
constructor, so without a guard a null reaches it and the caller gets a `NullPointerException` naming
a class the public builder never mentions, contradicting the `@throws IllegalArgumentException`
contract the provider documents. It is checked at the seam, in argument order, so the failure names
the argument the caller actually passed.

- [ ] **Step 3: Run the test to verify it fails**

Run: `./gradlew :integrations:agent-framework-mcp:test --tests 'io.github.hellices.agentframework.mcp.McpStdioToolsTest'`

Expected: FAIL to compile, `cannot find symbol: class McpStdioTools`.

- [ ] **Step 4: Write the shared owned tool provider**

Create `integrations/agent-framework-mcp/src/main/java/io/github/hellices/agentframework/mcp/internal/OwnedMcpTools.java`:

```java
package io.github.hellices.agentframework.mcp.internal;

import io.github.hellices.agentframework.api.tool.FunctionTool;
import io.github.hellices.agentframework.mcp.McpToolAdapterOptions;
import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * The body shared by every owned MCP tool provider.
 *
 * <p>Each transport gets its own public facade because the configuration differs, but the lifecycle
 * is identical: connect explicitly, discover, close. Keeping that here means the facades hold no
 * lifecycle logic at all, so a fix to reconnect or validation cannot apply to one transport and not
 * the other.
 */
public final class OwnedMcpTools {

  private final OwnedMcpClientLifecycle lifecycle;
  private final McpToolDiscovery discovery;

  /**
   * Creates a provider over a transport factory it owns.
   *
   * @param transportFactory creates each generation's transport, never {@code null}
   * @param settings settings applied to each generation's client, never {@code null}
   * @param options tool adapter options, never {@code null}
   * @throws IllegalArgumentException if an argument is {@code null}
   */
  public OwnedMcpTools(
      McpClientTransportFactory transportFactory,
      McpOwnedClientSettings settings,
      McpToolAdapterOptions options) {
    this.lifecycle = new OwnedMcpClientLifecycle(transportFactory, settings);
    // Checked here rather than left to McpToolDiscovery, which dereferences options and would
    // report a NullPointerException naming a class the caller never mentioned.
    if (options == null) {
      throw new IllegalArgumentException("options must not be null");
    }
    this.discovery = new McpToolDiscovery(new OwnedMcpAsyncOperations(lifecycle), options);
  }

  /**
   * Opens the connection, creating and initializing a client if there is none.
   *
   * @return a stage completing when the server is ready, never {@code null}
   */
  public CompletionStage<Void> connect() {
    return lifecycle.connect();
  }

  /**
   * Reads the server's whole tool catalogue.
   *
   * @return a stage completing with one tool per remote tool, never {@code null}
   */
  public CompletionStage<List<FunctionTool>> discoverTools() {
    return discovery.discover();
  }

  /**
   * Closes the current connection and leaves this provider reusable.
   *
   * @return a stage completing when the connection is released, never {@code null}
   */
  public CompletionStage<Void> close() {
    return lifecycle.close();
  }
}
```

- [ ] **Step 5: Write the stdio facade**

Create `integrations/agent-framework-mcp/src/main/java/io/github/hellices/agentframework/mcp/McpStdioTools.java`:

```java
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
import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * Exposes the tools of a stdio MCP server as framework tools, owning the server connection.
 *
 * <p>This is the owned counterpart of {@link ConnectedMcpClientAdapter}. It creates the transport and
 * the client, drives the handshake, validates the connection before every call, replaces it at most
 * once when it is lost, and closes it. Because the server is a child process, nothing here happens
 * implicitly: {@link #connect()} is required before {@link #discoverTools()}, and an operation
 * attempted before connect or after close fails instead of starting a process.
 *
 * <p>{@link #close()} ends the current connection, not this object. Calling {@link #connect()} again
 * afterwards starts a new server process, which is what a caller that deliberately reconnects wants.
 *
 * <p>Two limitations come from official SDK 2.0.0 and are not worked around here. {@code
 * ServerParameters} carries a command, arguments, and environment only, so there is no working
 * directory and no stream encoding option; run a command that sets its own working directory if you
 * need one. The SDK stdio transport also exposes no connect timeout, so this builder has none.
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
   * @throws IllegalArgumentException if the parameters are {@code null}
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

    private final ServerParameters serverParameters;

    private McpJsonMapper jsonMapper;
    private JsonSchemaValidator schemaValidator;
    private Duration requestTimeout = DEFAULT_TIMEOUT;
    private Duration initializationTimeout = DEFAULT_TIMEOUT;
    private McpToolAdapterOptions toolOptions = McpToolAdapterOptions.defaults();

    private Builder(ServerParameters serverParameters) {
      if (serverParameters == null) {
        throw new IllegalArgumentException("serverParameters must not be null");
      }
      this.serverParameters = serverParameters;
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
     */
    public Builder requestTimeout(Duration requestTimeout) {
      this.requestTimeout = requestTimeout;
      return this;
    }

    /**
     * Sets how long the handshake may take.
     *
     * @param initializationTimeout a positive duration, never {@code null}
     * @return this builder, never {@code null}
     */
    public Builder initializationTimeout(Duration initializationTimeout) {
      this.initializationTimeout = initializationTimeout;
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
     * @throws IllegalArgumentException if a required value is missing or a timeout is not positive
     */
    public McpStdioTools build() {
      if (jsonMapper == null) {
        throw new IllegalArgumentException(JSON_MAPPER_REQUIRED);
      }
      if (schemaValidator == null) {
        throw new IllegalArgumentException(SCHEMA_VALIDATOR_REQUIRED);
      }
      ServerParameters parameters = serverParameters;
      McpJsonMapper mapper = jsonMapper;
      return new McpStdioTools(
          () -> new StdioClientTransport(parameters, mapper),
          new McpOwnedClientSettings(schemaValidator, requestTimeout, initializationTimeout),
          toolOptions);
    }
  }
}
```

The factory lambda is the whole point of this class: `build()` records how to start the server, and
nothing starts until `connect()` runs. That is also why the constructor is package-private and takes a
factory, so the tests above never construct `StdioClientTransport`.

- [ ] **Step 6: Run the stdio test to verify it passes**

Run: `./gradlew :integrations:agent-framework-mcp:test --tests 'io.github.hellices.agentframework.mcp.McpStdioToolsTest'`

Expected: PASS, 6 tests.

- [ ] **Step 7: Run the module test and quality tasks**

Run: `./gradlew :integrations:agent-framework-mcp:test :integrations:agent-framework-mcp:quality`

Expected: PASS. If SpotBugs reports `EI_EXPOSE_REP2` for storing `ServerParameters`, add a narrow
class-and-method `<Match>` for `io.github.hellices.agentframework.mcp.McpStdioTools$Builder` in
`config/spotbugs/exclude.xml` with a comment saying the parameters are borrowed configuration owned by
the caller, following the existing MCP entries. Do not widen an existing match.

- [ ] **Step 8: Commit**

```bash
git add integrations/agent-framework-mcp/src/main/java/io/github/hellices/agentframework/mcp/internal/OwnedMcpTools.java \
        integrations/agent-framework-mcp/src/main/java/io/github/hellices/agentframework/mcp/McpStdioTools.java \
        integrations/agent-framework-mcp/src/test/java/io/github/hellices/agentframework/mcp/RejectingMcpJsonMapper.java \
        integrations/agent-framework-mcp/src/test/java/io/github/hellices/agentframework/mcp/McpStdioToolsTest.java
git commit -m "$(cat <<'MSG'
mcp: add an owned stdio tool provider

The builder records how to start the server; nothing starts until connect
is called, so building a provider is free and a tool lookup can never spawn
a process by accident. The missing working directory, encoding, and connect
timeout are SDK limits and are documented rather than faked.

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
MSG
)"
```

---

### Task 10: The streamable HTTP facade

**Files:**
- Create: `integrations/agent-framework-mcp/src/main/java/io/github/hellices/agentframework/mcp/McpStreamableHttpTools.java`
- Test: `integrations/agent-framework-mcp/src/test/java/io/github/hellices/agentframework/mcp/McpStreamableHttpToolsTest.java` (create)

**Interfaces:**
- Consumes: `OwnedMcpTools`, `McpOwnedClientSettings`, `McpClientTransportFactory` from Task 9 and
  earlier; `RejectingMcpJsonMapper`, `ScriptedMcpTransportFactory`, `InMemoryMcpTransport`, and
  `PermissiveJsonSchemaValidator` from the test sources.
- Produces: `public final class McpStreamableHttpTools` with `static Builder builder(String baseUri)`,
  `CompletionStage<Void> connect()`, `CompletionStage<List<FunctionTool>> discoverTools()`,
  `CompletionStage<Void> close()`, a package-private constructor
  `McpStreamableHttpTools(McpClientTransportFactory, McpOwnedClientSettings, McpToolAdapterOptions)`,
  and `Builder` with `endpoint(String)`, `jsonMapper(McpJsonMapper)`,
  `schemaValidator(JsonSchemaValidator)`, `requestTimeout(Duration)`,
  `initializationTimeout(Duration)`, `connectTimeout(Duration)`,
  `toolOptions(McpToolAdapterOptions)`, `build()`.

Scope reminder, restated because this is the class where the temptation is greatest: this first HTTP
facade exposes **no** request headers, **no** custom `HttpClient` or client customizer, **no** redirect
policy, and **no** tracing or telemetry hook. MCP-009 and MCP-010 stay `absent`. Adding an escape
hatch here would freeze an API before there is a requirement describing what it must guarantee.

- [ ] **Step 1: Write the failing HTTP facade test**

Create `integrations/agent-framework-mcp/src/test/java/io/github/hellices/agentframework/mcp/McpStreamableHttpToolsTest.java`:

```java
package io.github.hellices.agentframework.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hellices.agentframework.api.tool.FunctionTool;
import io.github.hellices.agentframework.mcp.internal.McpOwnedClientSettings;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The streamable HTTP facade is validated without opening a socket.
 *
 * <p>Endpoint validation is done by this builder rather than left to the first request, because the
 * SDK only rejects a cross-origin endpoint when a request is finally sent, and a caller that mistyped
 * a URL should learn about it where the mistake is, not on the first tool call.
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
    assertThatThrownBy(() -> McpStreamableHttpTools.builder("https://example.com/api").endpoint(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("endpoint must not be null");
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
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :integrations:agent-framework-mcp:test --tests 'io.github.hellices.agentframework.mcp.McpStreamableHttpToolsTest'`

Expected: FAIL to compile, `cannot find symbol: class McpStreamableHttpTools`.

- [ ] **Step 3: Write the streamable HTTP facade**

Create `integrations/agent-framework-mcp/src/main/java/io/github/hellices/agentframework/mcp/McpStreamableHttpTools.java`:

```java
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
 * transport and the client, drives the handshake, validates the session before every call, replaces
 * it at most once when the server forgets it, and closes it. {@link #connect()} is required before
 * {@link #discoverTools()}, and {@link #close()} ends the current session while leaving this object
 * reusable.
 *
 * <p>The endpoint is validated against the base URI when it is set, using the same rule the SDK
 * applies when it finally builds a request URL. The SDK only applies that rule per request, so a
 * mistyped endpoint would otherwise surface on the first tool call instead of at configuration time.
 *
 * <p>Each connection generation builds one SDK transport, and that transport allocates a {@link
 * java.net.http.HttpClient}. On Java 17 an {@code HttpClient} cannot be closed; its resources are
 * released when it becomes unreachable. That is the main reason a lost connection is allowed exactly
 * one replacement: an unbounded reconnect loop would accumulate clients that this module cannot
 * release.
 *
 * <p>This first version exposes no request headers, no custom {@code HttpClient}, no redirect policy,
 * and no tracing hook. Those are separate requirements and are deliberately not guessed at here.
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
   * @param baseUri the server's base URI, an absolute {@code http} or {@code https} URI
   * @return a builder, never {@code null}
   * @throws IllegalArgumentException if the base URI is missing, unparseable, or not HTTP
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
     * @param endpoint the endpoint, never {@code null}
     * @return this builder, never {@code null}
     * @throws IllegalArgumentException if the endpoint is {@code null} or resolves outside the base
     *     URI
     */
    public Builder endpoint(String endpoint) {
      if (endpoint == null) {
        throw new IllegalArgumentException("endpoint must not be null");
      }
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
     * Sets how long a single MCP request may take.
     *
     * @param requestTimeout a positive duration, never {@code null}
     * @return this builder, never {@code null}
     */
    public Builder requestTimeout(Duration requestTimeout) {
      this.requestTimeout = requestTimeout;
      return this;
    }

    /**
     * Sets how long the handshake may take.
     *
     * @param initializationTimeout a positive duration, never {@code null}
     * @return this builder, never {@code null}
     */
    public Builder initializationTimeout(Duration initializationTimeout) {
      this.initializationTimeout = initializationTimeout;
      return this;
    }

    /**
     * Sets how long establishing the HTTP connection may take.
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
     * @throws IllegalArgumentException if a required value is missing or a timeout is not positive
     */
    public McpStreamableHttpTools build() {
      if (jsonMapper == null) {
        throw new IllegalArgumentException(JSON_MAPPER_REQUIRED);
      }
      if (schemaValidator == null) {
        throw new IllegalArgumentException(SCHEMA_VALIDATOR_REQUIRED);
      }
      String uri = baseUri;
      String path = endpoint;
      McpJsonMapper mapper = jsonMapper;
      Duration connect = connectTimeout;
      return new McpStreamableHttpTools(
          () ->
              HttpClientStreamableHttpTransport.builder(uri)
                  .jsonMapper(mapper)
                  .endpoint(path)
                  .connectTimeout(connect)
                  .build(),
          new McpOwnedClientSettings(schemaValidator, requestTimeout, initializationTimeout),
          toolOptions);
    }

    private static URI requireHttpUri(String baseUri) {
      if (baseUri == null || baseUri.isBlank()) {
        throw new IllegalArgumentException("baseUri must not be null or blank");
      }
      URI parsed;
      try {
        parsed = new URI(baseUri);
      } catch (URISyntaxException failure) {
        throw new IllegalArgumentException("baseUri is not a valid URI: " + baseUri, failure);
      }
      String scheme = parsed.getScheme();
      if (scheme == null) {
        throw new IllegalArgumentException("baseUri must be absolute, but was: " + baseUri);
      }
      String lowered = scheme.toLowerCase(Locale.ROOT);
      if (!"http".equals(lowered) && !"https".equals(lowered)) {
        throw new IllegalArgumentException("baseUri must use http or https, but was: " + baseUri);
      }
      return parsed;
    }
  }
}
```

- [ ] **Step 4: Run the HTTP test to verify it passes**

Run: `./gradlew :integrations:agent-framework-mcp:test --tests 'io.github.hellices.agentframework.mcp.McpStreamableHttpToolsTest'`

Expected: PASS, 6 tests.

- [ ] **Step 5: Run the module test and quality tasks**

Run: `./gradlew :integrations:agent-framework-mcp:test :integrations:agent-framework-mcp:quality`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add integrations/agent-framework-mcp/src/main/java/io/github/hellices/agentframework/mcp/McpStreamableHttpTools.java \
        integrations/agent-framework-mcp/src/test/java/io/github/hellices/agentframework/mcp/McpStreamableHttpToolsTest.java
git commit -m "$(cat <<'MSG'
mcp: add an owned streamable HTTP tool provider

The endpoint is checked against the base URI when it is set, using the same
rule the SDK applies per request, so a mistyped URL fails at configuration
time instead of on the first tool call. Building opens nothing; the HTTP
client is created on connect, which matters because Java 17 cannot close one.

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
MSG
)"
```

---

### Task 11: Documentation, traceability, and full verification

**Files:**
- Modify: `integrations/agent-framework-mcp/src/main/java/io/github/hellices/agentframework/mcp/package-info.java`
- Modify: `docs/design/requirements-design/requirements-traceability-matrix.md:81-83`
- Modify: `docs/design/requirements-design/02-state-extension-mcp.md:346-349`
- Modify: `docs/design/module-composition.md:74`
- Modify: `README.md:85`
- Test: `integrations/agent-framework-mcp/src/test/java/io/github/hellices/agentframework/mcp/BorrowedMcpClientIntegrationTest.java` (modify)

**Interfaces:**
- Consumes: every class produced by Tasks 1-10.
- Produces: nothing new in code. This is the task that makes the tree's claims about itself true
  again, and it is the reviewer's gate for "does the documented status match the code".

The package documentation currently states that the module *never* opens, initializes, reconnects,
or closes a client. After Task 10 that sentence is false, and a false invariant in a package doc is
worse than no doc: the next contributor will believe it.

- [ ] **Step 1: Add the missing MCP-002 evidence**

This step is deliberately not RED-first, and saying so is the point. MCP-002's third acceptance
criterion, "even when multiple adapters share the same connection object, close responsibility
remains with the connection provider", is already satisfied by `ConnectedMcpClientAdapter`, which
holds no lifecycle code at all. What is missing is not behavior but evidence: nothing in the suite
fails if someone later adds a `close()` to the adapter, so the matrix would be claiming a guarantee no
test defends. A characterization test that passes on the first run is the correct instrument here,
and inventing a temporary defect to make it fail first would test the sabotage rather than the rule.

Add to `integrations/agent-framework-mcp/src/test/java/io/github/hellices/agentframework/mcp/BorrowedMcpClientIntegrationTest.java`:

```java
  @Test
  void leavesOneSharedClientOpenForEveryAdapterThatBorrowsIt() {
    InMemoryMcpTransport transport = searchServer();
    McpAsyncClient client = initializedClient(transport);

    ConnectedMcpClientAdapter first = new ConnectedMcpClientAdapter(client);
    ConnectedMcpClientAdapter second = new ConnectedMcpClientAdapter(client);
    List<FunctionTool> firstTools = first.discoverTools().toCompletableFuture().join();
    List<FunctionTool> secondTools = second.discoverTools().toCompletableFuture().join();

    // Both adapters work off the one session: two borrowers do not mean two handshakes, and neither
    // borrower may end the session the caller opened.
    assertThat(firstTools).hasSize(1);
    assertThat(secondTools).hasSize(1);
    assertThat(transport.countOf(McpSchema.METHOD_INITIALIZE)).isEqualTo(1);
    assertThat(transport.countOf(McpSchema.METHOD_TOOLS_LIST)).isEqualTo(2);
    assertThat(client.isInitialized()).isTrue();
    assertThat(transport.closeCount()).isZero();

    // The provider closes, once, and only because it chose to.
    client.closeGracefully().block(Duration.ofSeconds(5));
    assertThat(transport.closeCount()).isEqualTo(1);
  }
```

Run: `./gradlew :integrations:agent-framework-mcp:test --tests 'io.github.hellices.agentframework.mcp.BorrowedMcpClientIntegrationTest'`

Expected: PASS, 8 tests. If this test fails, stop: the adapter is closing or re-initializing a
borrowed client and MCP-002 is not implementable by documentation.

- [ ] **Step 2: Update the package documentation**

Replace the whole of
`integrations/agent-framework-mcp/src/main/java/io/github/hellices/agentframework/mcp/package-info.java`
with:

```java
/**
 * Model Context Protocol client integration for Agent Framework for Java.
 *
 * <p>This package exposes MCP server tools as framework tools under two ownership models.
 *
 * <p>{@link io.github.hellices.agentframework.mcp.ConnectedMcpClientAdapter} borrows an already
 * connected client: it never opens, initializes, reconnects, or closes one, because the application
 * or host that built the client owns its lifecycle.
 *
 * <p>{@link io.github.hellices.agentframework.mcp.McpStdioTools} and {@link
 * io.github.hellices.agentframework.mcp.McpStreamableHttpTools} own their connection: each builds
 * the transport and client, drives the handshake on an explicit {@code connect()}, validates the
 * session before each call, replaces it at most once when the server has forgotten it, and releases
 * it on {@code close()}. They never connect implicitly, so a call before {@code connect()} or after
 * {@code close()} fails instead of quietly starting a process or opening a socket.
 *
 * <p>WebSocket transports, prompts, resources, sampling, MCP tasks, request headers, and trace
 * propagation are deliberately absent and are separate requirement slices.
 *
 * <p>Requirements for this package live in {@code docs/requirements/05-mcp.md}, and the design that
 * governs discovery and invocation lives in {@code
 * docs/design/requirements-design/02-state-extension-mcp.md}.
 */
package io.github.hellices.agentframework.mcp;
```

- [ ] **Step 3: Update the traceability matrix**

In `docs/design/requirements-design/requirements-traceability-matrix.md`, replace the three MCP rows
at lines 81-83. Before:

```markdown
| MCP-001 | Transport tools and connection adapters are co-located | [02-state-extension-mcp.md](02-state-extension-mcp.md) | `integrations/agent-framework-mcp` | `partial` | connected-client adapter tests (transport tool types absent) |
| MCP-002 | Lifecycle is divided by connection ownership | [02-state-extension-mcp.md](02-state-extension-mcp.md) | `integrations/agent-framework-mcp` | `absent` | owned/borrowed lifecycle contract |
| MCP-003 | Connection validation and reconnection are standardized | [02-state-extension-mcp.md](02-state-extension-mcp.md) | `integrations/agent-framework-mcp` | `absent` | reconnect/cache contract |
```

After:

```markdown
| MCP-001 | Transport tools and connection adapters are co-located | [02-state-extension-mcp.md](02-state-extension-mcp.md) | `integrations/agent-framework-mcp` | `partial` | connected-client adapter tests + owned stdio and streamable HTTP facade tests (WebSocket transport tools absent because the official SDK 2.0.0 ships no WebSocket client transport) |
| MCP-002 | Lifecycle is divided by connection ownership | [02-state-extension-mcp.md](02-state-extension-mcp.md) | `integrations/agent-framework-mcp` | `implemented` | owned facades expose `connect()`/`close()`; borrowed adapter never closes the injected client, including when two adapters share one client |
| MCP-003 | Connection validation and reconnection are standardized | [02-state-extension-mcp.md](02-state-extension-mcp.md) | `integrations/agent-framework-mcp` | `partial` | ping validation before each list or call, per-session unsupported-ping cache, one replacement session and one retry of the original request, session and cache cleared on close (sampling counter and pending reload state not implemented) |
```

Do not touch MCP-009 or MCP-010: no header propagation and no trace propagation ship in this slice,
so both stay `absent`. Do not add or remove rows; the matrix invariant is 244 IDs.

MCP-002 becomes `implemented` because all three of its acceptance criteria now have executable
evidence, including the third one, which Step 0 below adds: close responsibility stays with the
provider even when several adapters share one connection object.

MCP-003 stays `partial`, and that is not pessimism. Its third acceptance criterion requires the
session, the capability cache, the *sampling counter*, and the *pending reload state* to be cleared on
close. This slice implements no sampling and no reload, so two of those four pieces of state do not
exist yet, and marking the row `implemented` would claim a guarantee whose subject is missing. The
verification column therefore states exactly what is implemented today and names what is outstanding;
the row moves to `implemented` in the slice that adds sampling and reload. A status legend that lets
`partial` mean "the parts that exist all work" is the only reading under which the matrix stays
useful as a gap list.

- [ ] **Step 4: Update the MCP design's current-implementation section**

In `docs/design/requirements-design/02-state-extension-mcp.md`, replace section 12. Before:

```markdown
## 12. Current implementation

There is no production session/interceptor/MCP code. `DeterministicClock` may be reused later as a
snapshot/checkpoint test fixture, but it does not implement the required behavior.
```

After:

```markdown
## 12. Current implementation

There is no production session or interceptor code. `DeterministicClock` may be reused later as a
snapshot/checkpoint test fixture, but it does not implement the required behavior.

`integrations/agent-framework-mcp` implements the client boundary in section 8.1 for both ownership
models. `ConnectedMcpClientAdapter` borrows a caller-owned client. `McpStdioTools` and
`McpStreamableHttpTools` own a stdio or streamable HTTP connection: an explicit `connect()` builds
one transport and client and completes the handshake, a `ping` validates the session before each
list or call unless that session answered `-32601`, a validation or connection-loss failure buys at
most one replacement session and at most one retry of the original operation, and `close()` releases
the session while leaving the object reusable. Discovery in section 8.2 and invocation in section 8.4
are shared by both models.

Prompts and resources (8.3) and the task lifecycle (8.5) remain absent, as do request headers, trace
propagation, and sampling. No WebSocket transport exists, because the official SDK 2.0.0 provides no
WebSocket client transport.
```

- [ ] **Step 5: Update the module composition and README summaries**

In `docs/design/module-composition.md`, line 74, replace the responsibility cell. Before:

```markdown
| `:integrations:agent-framework-mcp` | `agent-framework-mcp` | Model Context Protocol client integration over a borrowed SDK client. | `:agent-framework-api` |
```

After:

```markdown
| `:integrations:agent-framework-mcp` | `agent-framework-mcp` | Model Context Protocol client integration over a borrowed SDK client or an owned stdio or streamable HTTP connection. | `:agent-framework-api` |
```

In `README.md`, line 85, replace the module description. Before:

```text
  agent-framework-mcp/      MCP client tool adapter
```

After:

```text
  agent-framework-mcp/      MCP client tools, borrowed or owned connection
```

Keep the column alignment of the surrounding block; the description starts at the same column as the
neighbouring lines.

- [ ] **Step 6: Run the repository policy suite**

Run: `./gradlew policyCheck`

Expected: PASS. This is the task that most easily breaks it: `DocumentationLanguagePolicyTest`
requires English, `MarkdownLinkPolicyTest` resolves every markdown link, and
`ModuleCompositionPolicyTest` requires every registered Gradle path to appear in
`docs/design/module-composition.md`. If a link fails, check that the matrix rows still use the
existing `[02-state-extension-mcp.md](02-state-extension-mcp.md)` form and that no path was turned
into a link.

- [ ] **Step 7: Run the quality suite**

Run: `./gradlew quality`

Expected: PASS. Spotless applies google-java-format, Checkstyle allows zero warnings, PMD runs the
eight-rule ruleset, and SpotBugs runs at maximum effort with medium confidence.

If Spotless reports a formatting violation, run `./gradlew spotlessApply` and re-run. If SpotBugs
reports `EI_EXPOSE_REP2` on a constructor that stores a caller-supplied value, add a narrow match to
`config/spotbugs/exclude.xml` next to the existing MCP entries, naming the exact class and method and
explaining why the stored value is safe to share, for example:

```xml
  <!--
    McpOwnedClientSettings stores the caller's JsonSchemaValidator by reference on purpose: the
    validator is a stateless collaborator that the caller must keep using, and copying it is not
    possible through its interface.
  -->
  <Match>
    <Class name="io.github.hellices.agentframework.mcp.internal.McpOwnedClientSettings"/>
    <Method name="&lt;init&gt;"/>
    <Bug pattern="EI_EXPOSE_REP2"/>
  </Match>
```

Do not add a blanket suppression, a package-level match, or a `@SuppressWarnings` on a whole class.
If a finding is real, fix the code instead.

- [ ] **Step 8: Run the compatibility test matrix**

Run: `./gradlew testJava17 testJava21 testJava25`

Expected: PASS on all three toolchains. The owned lifecycle uses only `java.util.concurrent` and
Reactor types that behave identically across them, so a failure on exactly one JDK is a real defect,
most likely a timing assumption in a test. Do not respond by widening a timeout: find the ordering
that differs. Deterministic tests do not retry, and a quality failure is never covered by a passing
compatibility test.

- [ ] **Step 9: Run the full build**

Run: `./gradlew check`

Expected: PASS.

- [ ] **Step 10: Review the diff for contract impact**

Run: `git diff --stat bd0700c..HEAD` and `git diff bd0700c..HEAD -- '*/src/main/java/*'`

Check each of these and write the answer in the pull request body:

- Public API: exactly two new public types, `McpStdioTools` and `McpStreamableHttpTools`, plus their
  builders. `OwnedMcpTools`, `McpClientTransportFactory`, `McpOwnedClientSettings`,
  `OwnedMcpClientLifecycle`, `OwnedMcpAsyncOperations`, and `McpConnectionReplacedException` live in
  `io.github.hellices.agentframework.mcp.internal` and are internal: the package is documented as
  internal and is not part of the supported surface, so calling them `public API` in the pull request
  body would commit the project to a compatibility promise it never intended to make. Nothing existing
  changed shape, so `ConnectedMcpClientAdapter` and `McpToolAdapterOptions` are unaffected;
  `McpAsyncOperations`, also internal, gains one default method, which is source and binary compatible
  for its one external implementor, `BorrowedMcpAsyncOperations`.
- Dependencies: no new module dependency, no new entry in `gradle/libs.versions.toml`, no lockfile
  change. Confirm with `git diff bd0700c..HEAD -- gradle/ '*/build.gradle.kts'`, which must be empty.
- Session format: unaffected; this slice persists nothing.
- Telemetry: unaffected; this slice emits nothing.
- Compatibility: additive only.

- [ ] **Step 11: Commit**

```bash
git add integrations/agent-framework-mcp/src/main/java/io/github/hellices/agentframework/mcp/package-info.java \
        integrations/agent-framework-mcp/src/test/java/io/github/hellices/agentframework/mcp/BorrowedMcpClientIntegrationTest.java \
        docs/design/requirements-design/requirements-traceability-matrix.md \
        docs/design/requirements-design/02-state-extension-mcp.md \
        docs/design/module-composition.md \
        README.md
git commit -m "$(cat <<'MSG'
docs: record the owned MCP connection lifecycle

The package documentation promised the module never opens or closes a client,
which stopped being true once the owned facades landed. MCP-002 moves to
implemented, with the shared-client case it always claimed now covered by a
test. MCP-003 stays partial: ping validation, the capability cache, the single
reconnect, and clearing on close all ship, but its sampling counter and pending
reload state do not exist yet. MCP-001 stays partial because the official SDK
2.0.0 has no WebSocket client transport, and MCP-009 and MCP-010 stay absent
because this slice propagates neither headers nor traces.

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
MSG
)"
```

- [ ] **Step 12: Push and run the review loop**

```bash
git push -u origin feat/mcp-owned-lifecycle
gh pr create --fill
gh pr review --request-reviewer Copilot 2>/dev/null || gh pr edit --add-reviewer Copilot
```

Then follow the review loop in `AGENTS.md`: wait for the review rather than assuming silence is
approval, reply to each inline comment with what changed and why (or why the suggestion was declined
or implemented differently), resolve the thread, and repeat from the push step whenever a response
required a push. A green pipeline is not evidence that a review is unnecessary.

When the pull request is merged, use squash merge so `main` receives one coherent commit, and make
sure the pull request title describes that squashed commit.

---

## Plan self-review

This section records the review of the plan against the brief, so a reviewer can check the reasoning
rather than re-deriving it.

### Coverage of the brief

| Decision in the brief | Where it lands |
| --- | --- |
| Owned stdio and streamable HTTP high-level tool providers | Tasks 9 and 10 |
| Lifecycle ownership: build, initialize, close | Tasks 2 and 3 |
| Ping validation before list and call | Task 6 |
| Exactly one reconnect, one new generation | Task 6 |
| Retry of the original operation, exactly once | Task 7 |
| MCP-002 implemented, MCP-003 partial, MCP-001 partial, MCP-009 and MCP-010 absent | Task 11 |
| One generation is one new transport plus one new client, never reused after close | Tasks 2, 3, 6 |
| Initial state disconnected, explicit connect required | Tasks 2 and 4 |
| Repeated connect while connected is idempotent | Task 2 |
| Close is asynchronous and idempotent and clears state even when it fails | Task 3 |
| Close then explicit connect creates a fresh generation | Task 3 |
| Operations after close fail and start nothing | Task 4 |
| Handshake runs on an uncancellable subscription | Task 2 |
| Concurrent connect calls coalesce | Task 2 |
| Close during connect waits for the handshake to settle, then closes | Task 3 |
| No executors, schedulers, or global hooks created by this module | Global constraints, Tasks 2 and 9 |
| Final `McpStdioTools` and `McpStreamableHttpTools` with three public stages | Tasks 9 and 10 |
| Builders take transport config, explicit mapper and validator, timeouts, tool options | Tasks 9 and 10 |
| No public transport factory | Tasks 9 and 10, package-private seam constructor |
| Package-private factory seam for deterministic tests | Task 2 |
| Borrowed adapter unchanged | Task 4 regression step, Task 11 diff review |
| Ping capability cached per generation on `-32601` | Task 6 |
| Any other ping failure spends the single reconnect | Task 6 |
| Retry only on verified connection loss, never on application errors | Tasks 5 and 7 |
| Concurrent failures from one stale generation coalesce onto one replacement | Task 6 |
| Reconnect closes the stale generation before replacing it | Task 6 |
| Handshake failure closes the new transport and client and surfaces cleanup failures | Task 2 |
| Reconnect failure leaves the owner disconnected and reconnectable | Tasks 3 and 6 |
| Returned tools may retry their in-flight call once | Task 7 |
| Close clears only the generation and the ping capability cache | Tasks 3 and 6 |
| Stdio limitations documented | Task 9 |
| Java 17 `HttpClient` non-closeability documented | Task 10 |
| Deterministic tests, no process and no network | Task 1 and every later task |
| Facade construction without connecting, no leaked process or thread | Tasks 9 and 10 |
| Cancelling a caller during validation fails that caller and keeps the generation | Task 6 |
| A transport that exists is closed exactly once when its client cannot be built | Task 2 |
| A tool retained past `close()` fails on the connect requirement and starts nothing | Task 4 |
| Closing during an in-flight call fails that call without reconnecting | Task 7 |
| A paged discovery is one operation with one reconnect, restarted from the first page | Task 8 |
| A cursor is never replayed on a generation that did not issue it | Task 8 |
| Discovered tools carry their own budget, not the discovery's spent one | Task 8 |
| Two adapters over one borrowed client, neither closing it | Task 11 |

### Placeholders

Every code step contains the code. There is no "add error handling", no "similar to an earlier task",
and no reference to a type that no task defines. The two fixtures shared across tasks,
`ScriptedMcpTransportFactory` and `RejectingMcpJsonMapper`, are written out in full where they are
first needed, in Tasks 2 and 9, as is `ClientHostileMcpTransport` in Task 2.

### Name and type consistency

`McpOwnedClientSettings` is constructed as `(schemaValidator, requestTimeout, initializationTimeout)`
in every task. `Generation` is constructed as `(client)` in Task 2 and as `(epoch, client)` from Task
6 onward, and Task 6 rewrites both the class and its single call site in the same step.
`OwnedMcpTools`, `McpStdioTools`, and `McpStreamableHttpTools` all take
`(McpClientTransportFactory, McpOwnedClientSettings, McpToolAdapterOptions)`. Every method called on
the test transport in a later task is defined in Task 1: `answering`, `answeringPing`,
`answeringWithError`, `failingSend`, `failingClose`, `withholding`, `releaseWithheld`, `respond`,
`methodsSent`, `lastRequestFor`, `countOf`, `closeCount`, and `isClosed`.

`McpFailuresTest` is the only test in the `internal` test package, because `McpFailures` is
package-private. Every other test lives in `io.github.hellices.agentframework.mcp`, which is where
`InMemoryMcpTransport` and `ScriptedMcpTransportFactory` are package-private, and where the facades'
seam constructors are visible.

### Races that were considered

- **Connect during connect.** The second caller receives a stage derived from the same handshake.
  Only one transport is ever created; `createdCount()` proves it.
- **Cancel during connect.** The caller's stage is a `thenApply` dependent of the handshake, so
  cancelling it cannot dispose the subscription that drives `initialize()`. Probe evidence: disposing
  that subscription wedges the SDK's `LifecycleInitializer` permanently.
- **Close during connect.** `close()` bumps the epoch, so the settling handshake's `publish` is
  ignored, and close chains onto the handshake so the generation it produces is closed rather than
  orphaned. The connect stage still mirrors the handshake's outcome, which means a connect can
  succeed while the owner ends disconnected. That is honest: the handshake did succeed, and the
  caller asked for the close afterwards.
- **Connect during close.** The connect creates a fresh generation. It does not wait for the close.
  This is a deliberate choice: the owner is reusable, and blocking a reconnect behind a close that is
  itself waiting on a dying server would be worse than briefly holding two generations, one of which
  is already closing.
- **Handshake failure.** The generation closes itself, and `close()` therefore must not close it
  again; Task 3 handles the `null` generation case explicitly. Cleanup failure is attached to the
  reported failure with `addSuppressed` rather than swallowed.
- **Re-entrant replacement.** Closing a client dismisses its in-flight requests synchronously inside
  `closeGracefully()`, so a sibling operation can fail and call `replaceGeneration` while the first
  caller is still inside `stale.close()`. The replacement future is therefore published inside the
  lock and the stale close runs outside it, so the sibling joins the same replacement instead of
  creating a second client.
- **Stale caller after a replacement.** A caller holding an old generation compares epochs, not
  references, so it recognises that the owner has already moved on and reuses the replacement.
- **Cancellation during validation.** `CompletableFuture` hands the raw stored throwable to a
  `handle` dependent, so a cancelled caller arrives at the ping's failure branch as a bare
  `CancellationException`, which is neither a `-32601` nor a connection loss. Left unclassified it
  would have bought a replacement and closed a perfectly healthy generation on behalf of a caller who
  had already walked away. Task 6 classifies it first, before the unsupported-ping check, and Task 7
  reuses the same predicate at the call stage. Both stages keep their own guard because they fail for
  different reasons and a single guard at one of them would silently stop covering the other.
- **A tool that outlives its owner.** `FunctionTool`s handed out by a discovery hold the operations
  port, not a client, so a call made after `close()` finds no generation and fails on the connect
  requirement rather than opening a transport for an object the caller believes is shut down. Task 4
  proves it with `createdCount()`.
- **Close during an in-flight call.** The call is dismissed by the SDK's synchronous request cleanup,
  which surfaces as a bare `RuntimeException`. It is not connection loss and must not be, or every
  deliberate shutdown would resurrect the connection it was shutting down. Task 7 pins one transport,
  one close, and one factory creation.
- **Replacement during paging.** A cursor is session state. Repeating a page request on a replacement
  session is the one case where the retry rule of Tasks 6 and 7 is actively harmful, because the
  server may answer from a different position and the result is a catalogue that is wrong without
  being an error. Task 8 makes a paged read declare itself as such, so a replacement ends the read's
  current pass instead of continuing it, and the reader restarts from `McpSchema.FIRST_PAGE` with
  everything it had accumulated discarded.
- **Discovery budget versus tool budget.** The discovery's attempt is scoped to the discovery. The
  tools it returns keep the unscoped operations, so a call made minutes later starts with a full
  budget instead of inheriting one the discovery already spent.
- **PMD `CompareObjectsWithEquals`.** The epoch design exists partly because the ruleset forbids
  reference comparison, and adding a suppression to keep an identity check would have been the wrong
  trade.

### Test-first ordering

Every production behavior in this plan is introduced after a test that fails without it, and the plan
was re-read once specifically for that property because a step that adds behavior and its test
together looks identical in a diff to one that does not.

The places where the ordering needed correcting, and how:

- The call-stage cancellation guard and the validation-stage cancellation guard are separate
  behaviors, so Task 6 was split: a step that runs the suite and watches the validation cancellation
  test fail comes before the step that adds the guard, and Task 7 does the same for its own guard
  rather than inheriting the earlier one silently.
- The transport-leak case in Task 2 needed a fixture whose client build fails after the transport
  exists. `ClientHostileMcpTransport` is added as a fixture step, the test is written and run red,
  and only then does `startGeneration` learn to clean up.
- Task 8's paging test is written first and is expected to fail in three of its five cases, with the
  exact failure named, before any of `McpConnectionReplacedException`, `forPagedOperation()`, the
  `Attempt` flag, or the reader's restart exists.
- Task 11's MCP-002 test is the single exception, and it is called out in the step itself: the
  behavior already ships, and what is missing is a test that would fail if someone removed it. A
  characterization test that passes immediately is the honest instrument for that, and manufacturing a
  temporary defect to make it go red would have tested the defect.

Per-task expected results, which a reviewer can check against the run output: Task 1 six tests, Task 2
nine, Task 3 six, Task 4 eight, Task 5 eight, Task 6 eight, Task 7 seven, Task 8 five, Task 9 six,
Task 10 six, Task 11 eight in the borrowed integration suite plus the whole verification contract.

### SDK signatures

Every SDK type, method, constructor, and constant used in this plan was read from
`mcp-core-2.0.0.jar` with `javap`, and the behavioural claims were checked by running throwaway
probes against the real client. The results are in the verified-facts section. One claim in the
research report was wrong and is corrected here: `HttpClientStreamableHttpTransport.Builder.build()`
does **not** validate the endpoint against the base URI. The SDK resolves the endpoint per request,
so this plan validates it in our builder instead.

### Scope

Not in this slice, and deliberately so: discovery caching, prompts, resources, sampling, MCP tasks,
request headers, trace propagation, WebSocket transport, retry budgets larger than one, and any
public transport factory. Each of those is a separate requirement with its own acceptance criteria,
and shipping a guess at any of them would freeze an API before the requirement exists to judge it by.

### Known limitations, stated rather than hidden

- The official SDK 2.0.0 has no WebSocket client transport, so MCP-001 stays `partial`.
- `ServerParameters` exposes no working directory and no encoding, so a stdio server that needs a
  specific working directory cannot be configured through this facade yet.
- `StdioClientTransport`'s constructor allocates three single-thread schedulers before anything is
  connected. That is why no test constructs one without closing it, and why the facade defers
  transport construction to `connect()`.
- Java 17's `java.net.http.HttpClient` cannot be closed, so each HTTP generation's client is released
  only by garbage collection. The single-reconnect rule bounds how many can exist.
- Validating with a ping before every list and call doubles the round trips for a chatty agent. It is
  the only way to distinguish "the session is gone" from "the tool failed" without guessing from
  error text, and the per-generation capability cache removes the cost against servers that do not
  implement ping at all.
- MCP-003 stays `partial`. Ping validation, the per-session unsupported-ping cache, one replacement
  session with one retry of the original request, and clearing the session and cache on close all
  ship. Its acceptance criteria also require the sampling counter and the pending reload state to be
  cleared on close, and neither exists in this slice, so the requirement cannot be closed here. The
  slice that adds sampling and reload owns that move.
- Restarting a paged discovery re-reads the pages already read, so a catalogue that fails on its last
  page costs close to twice its page count. The alternative is worse: continuing a cursor on a session
  that never issued it risks a silently wrong catalogue, and a wrong tool list is not a cost, it is a
  defect. Servers that page at all are rare, and losing the connection mid-catalogue is rarer.
- A caller that reaches `OwnedMcpAsyncOperations.listTools` directly, outside a discovery, sees
  `McpConnectionReplacedException` instead of a repeated request, because there is no reader to
  restart. That surface is internal and its only production caller is `McpToolDiscovery`; the honest
  failure is preferable to a repeat that would carry a stale cursor.
- `addSuppressed` is called on a throwable that a concurrent caller may also be reading, and
  `Throwable`'s suppressed list is not synchronized. The window is real but narrow, and every safe
  alternative, copying the throwable or wrapping it, would either lose the caller's type or change the
  exception a caller already catches. This slice keeps the behavior unchanged and records the risk
  rather than trading a diagnostic aid for a type change nobody asked for.
- `PageReader`'s restart flag is, given the shared single-reconnect budget, unreachable a second time.
  It stays because the reader is driven by a signal from outside itself, and a loop that an external
  collaborator can restart should bound that restart locally, exactly as it already bounds paging by
  repeated cursors and by a maximum page count.
