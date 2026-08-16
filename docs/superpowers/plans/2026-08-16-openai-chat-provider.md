# OpenAI Chat Completions Provider Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the first real provider-backed standalone milestone. A new artifact,
`providers/agent-framework-openai`, implements the neutral `ModelClient` port over the official
`com.openai:openai-java` Chat Completions async API, and `samples/sample-standalone` stops faking a
model and calls a real endpoint. After this slice, `./gradlew :samples:sample-standalone:run` is a
real model call, the sample registers one harmless deterministic local function tool on every
request, and its default prompt asks the model to use that tool, so the default run exercises a real
function-tool loop against a live OpenAI-compatible service without changing the agent definition.

Two things this goal deliberately does **not** claim. It does not claim that a live model must call
the tool: tool selection is the model's decision, and an arbitrary user prompt may be answered
without a call, which is the ordinary response path and not an adapter failure. And it does not use
the live run as the correctness proof for the loop; the fan-out, the echo, and the byte-identical
arguments are proved deterministically and offline in Task 10. What the live run adds is that the
loop is really wired end to end, and the sample's footer reports the tool-call count so the loop is
observable rather than asserted.

**Architecture:** One public final class, `OpenAiChatModelClient`, with a nested `Builder`. The
builder takes a **borrowed** `OpenAIClientAsync` and a required model name; the adapter never
creates, configures, or closes an SDK client, so it allocates no OkHttp dispatcher, no connection
pool, and no executor. Everything else lives in
`io.github.hellices.agentframework.openai.internal`, which is public only because the facade needs
it and carries no compatibility promise, exactly as `io.github.hellices.agentframework.mcp.internal`
does today. Inside that package a one-method port, `ChatCompletionsOperations`, is the single place
the SDK is called, which is what makes every mapping rule unit-testable with no network, no mock
server, and no new test dependency. `ChatCompletionRequestMapper` turns a `ModelRequest` into
`ChatCompletionCreateParams`, `ChatCompletionResponseMapper` turns a `ChatCompletion` into a
`ModelResponse`, and `OpenAiCallBridge` owns cancellation registration, listener de-registration,
and failure unwrapping.

**Tech Stack:** Java 17 source and target, Gradle Kotlin DSL with the `build-logic` included build,
official `com.openai:openai-java:4.51.0` (aggregator artifact; there is no `openai-java-bom`),
Jackson 2.22.1 from the repository pin for tool-argument JSON, JUnit 5 and AssertJ for tests, and
`:agent-framework-engine` as a **test-only** project dependency for the one end-to-end proof that
the adapter and the real tool loop agree.

## Global Constraints

- The module is `providers/agent-framework-openai`. Its only **production** project dependency is
  `:agent-framework-api`. It never depends on the engine, a host, or another provider at compile or
  runtime, and it never references engine internals.
- The Java package root is `io.github.hellices.agentframework.openai`. Never introduce
  `com.microsoft.*`.
- Scope is Chat Completions, async, ordinary responses and the function-tool loop. Explicitly out:
  Spring AI, a raw HTTP protocol implementation, the Responses API, Azure-specific credential or
  deployment handling, streaming, vision, audio, embeddings, structured output or `response_format`,
  `tool_choice`, and Spring Boot auto-configuration. None of these gets a stub, a flag, or a
  half-wired seam.
- The adapter creates no executor, scheduler, thread, shutdown hook, retry loop, or global registry.
  Retry stays where the SDK and the host already own it; the adapter adds none of its own.
- Every compile runs with `-Xlint:all -Werror`, so a deprecation warning fails the build. Do not
  touch `ChatCompletion.systemFingerprint()` or `ChatCompletionFunctionMessageParam`; both are
  deprecated in the pinned SDK.
- `quality` runs spotless with google-java-format, checkstyle with `maxWarnings = 0`, PMD, and
  SpotBugs at `Effort.MAX` / `Confidence.MEDIUM`. Blanket suppressions are prohibited; only narrow
  class-and-method `Match` entries with a justification comment in `config/spotbugs/exclude.xml`.
  A `Match` is added only after an unfiltered run has reported that exact finding, never
  speculatively and never as one deferred batch at the end: Tasks 4, 6, 7, and 8 each run the
  provider module's `quality` task so a finding is met next to the code that caused it.
- Checkstyle forbids catching or declaring `java.lang.Throwable`. A `whenComplete` callback whose
  **parameter** is a `Throwable` is not a catch and is fine; an actual `catch (Throwable)` is not.
- PMD runs `CompareObjectsWithEquals`, so production code never compares two object references with
  `==` or `!=`. Comparisons against `null` are unaffected.
- Tests are deterministic. No network, no mock HTTP server, no Prism, no WireMock, no MockWebServer,
  no live credentials, no `Thread.sleep`, no retry-on-flake, no wall-clock timeout used as an
  assertion. CI must be able to run the whole suite offline with no environment variables set.
- Prompt text, model output, and tool arguments are never logged and never embedded in an exception
  message. Failure messages name the role, the content type, the tool name, and the call id, and
  stop there.
- Dependency versions live only in `gradle/libs.versions.toml`. Build files declare no inline
  version; `ModuleCompositionPolicyTest.libraryProjectDeclaresNoInlineDependencyVersion` asserts the
  build file does not contain `version = "`.
- AGT-011 and AGT-012 stay `partial`. `AgentEngine.toModelRequest` hard-codes
  `ModelRequestOptions.empty()` and `AgentRunOptions` carries no model options, so no per-run
  temperature or token limit can reach any provider yet. This slice takes those as adapter defaults
  and honours a directly supplied `ModelRequest.options()`, and it invents no metadata side channel
  to work around the gap. Wiring options through the engine is a core change and belongs in its own
  pull request.
- AGT-015 is untouched. Streaming assembly already ships in the engine; this slice adds no
  `StreamingModelClient` and changes no streaming code or status.
- This is the user review milestone. When the pull request is merged, stop. The user runs and
  reviews the real standalone sample before any streaming or Spring continuation is planned.

## Verified SDK 4.51.0 facts

Verified against `openai/openai-java` at tag `v4.51.0` (commit `fcdcc7ee0414bbd92284a0839c0b7a6997cf8415`)
and against the published Maven Central artifacts. Do not re-derive them; do not contradict them.

1. `com.openai:openai-java:4.51.0` exists on Maven Central. There is **no** `openai-java-bom`. The
   aggregator `openai-java` is the alignment mechanism: it pins `openai-java-client-okhttp`, which
   pins `openai-java-core`.
2. Transitive set from the published POMs: `openai-java-client-okhttp:4.51.0` and
   `kotlin-stdlib-jdk8:1.8.0` (compile); `okhttp:4.12.0` (runtime, dragging okio);
   `jackson-core` / `jackson-databind:2.18.9`, `error_prone_annotations:2.33.0`,
   `swagger-annotations:2.2.31` (compile); `jackson-annotations`, `jackson-datatype-jdk8`,
   `jackson-datatype-jsr310`, `jackson-module-kotlin:2.18.9` and
   `victools jsonschema-generator` / `-module-jackson` / `-module-swagger-2:4.38.0` (runtime).
3. `kotlin-reflect` is required at runtime and is **not** declared by any OpenAI POM. It arrives
   transitively through `jackson-module-kotlin`. Never exclude `jackson-module-kotlin`.
4. `openai-java-core-4.51.0.jar` is 53,201,196 bytes. That is a packaging fact worth one README
   line, not a correctness problem.
5. The published Gradle module metadata declares `org.gradle.jvm.version = 8`, and the project's own
   support matrix covers 8, 11, 17, 21, and 25, so the repository's `testJava17` / `testJava21` /
   `testJava25` matrix is supported.
6. `com.openai.client.OpenAIClientAsync` is a public interface with `chat()` returning
   `com.openai.services.async.ChatServiceAsync`, whose `completions()` returns
   `com.openai.services.async.chat.ChatCompletionServiceAsync`. `OpenAIClientAsync.close()` exists
   but the interface deliberately does **not** extend `AutoCloseable`, because the client is meant
   to be long-lived and host-owned.
7. `ChatCompletionServiceAsync` has both `create(ChatCompletionCreateParams)` and
   `create(ChatCompletionCreateParams, RequestOptions)`, each returning
   `CompletableFuture<ChatCompletion>`. `RequestOptions` is `com.openai.core.RequestOptions` and
   `RequestOptions.builder().timeout(java.time.Duration).build()` compiles.
8. `com.openai.client.okhttp.OpenAIOkHttpClientAsync.builder()` exposes `apiKey(String)`,
   `baseUrl(String)`, `timeout(Duration)`, `maxRetries(int)`, `fromEnv()`, and `build()` returning
   `OpenAIClientAsync`. `OpenAIOkHttpClientAsync.fromEnv()` also exists.
9. `ChatCompletionCreateParams.Builder` has `model(ChatModel)`, `model(String)`,
   `temperature(double)`, `maxCompletionTokens(long)`, `addMessage(ChatCompletionMessageParam)`,
   overloads of `addMessage` for each concrete param type, `addMessage(ChatCompletionMessage)`,
   `addTool(ChatCompletionTool)`, and `addTool(ChatCompletionFunctionTool)`. **`model(String)`
   exists**, correcting the research report, so `ChatModel.of(...)` is unnecessary.
10. Accessors on the built `ChatCompletionCreateParams`: `messages()` returns
    `List<ChatCompletionMessageParam>`, `model()` returns `ChatModel` even when set from a `String`,
    `temperature()` returns `Optional<Double>`, `maxCompletionTokens()` returns `Optional<Long>`,
    and `tools()` returns `Optional<List<ChatCompletionTool>>`. Note the union: it is
    `ChatCompletionTool`, not `ChatCompletionFunctionTool`, and `ChatCompletionTool.asFunction()`
    narrows it.
11. `ChatCompletionCreateParams.Builder.addMessage(ChatCompletionMessage)` is implemented as
    `addMessage(assistant.toParam())`, so an echoed SDK message lands in `messages()` as an
    **assistant** `ChatCompletionMessageParam`. A test can assert `isAssistant()` on it.
12. `ChatCompletionMessageParam` is a union with `ofDeveloper`, `ofSystem`, `ofUser`, `ofAssistant`,
    `ofTool`, and the deprecated `ofFunction`. For each variant `x` it exposes `Optional<X> x()`,
    `boolean isX()`, and `X asX()`.
13. `ChatCompletionSystemMessageParam`, `ChatCompletionDeveloperMessageParam`, and
    `ChatCompletionUserMessageParam` each have `Builder.content(String)` and a **required**
    `content()` returning a nested `Content` with `asText()`.
    `ChatCompletionToolMessageParam` adds `Builder.toolCallId(String)`, `String toolCallId()`, and
    the same required `content()` / `asText()` shape.
    `ChatCompletionAssistantMessageParam` differs: `content()` returns `Optional<Content>`, and it
    has `addToolCall(ChatCompletionMessageToolCall)`,
    `addToolCall(ChatCompletionMessageFunctionToolCall)`, and
    `toolCalls()` returning `Optional<List<ChatCompletionMessageToolCall>>`.
14. Function tools: `com.openai.models.chat.completions.ChatCompletionFunctionTool.builder()
    .function(FunctionDefinition)`, with `com.openai.models.FunctionDefinition.builder()
    .name(String).description(String).parameters(FunctionParameters)` and
    `com.openai.models.FunctionParameters.builder().putAdditionalProperty(String, JsonValue)`.
    `com.openai.core.JsonValue.from(Object)` accepts nested `Map` and `List`, so a plain
    `ToolDefinition.inputSchema()` maps directly with no schema library.
15. `ChatCompletion` exposes `id()`, `model()`, `created()` returning `long`, `choices()` returning
    `List<ChatCompletion.Choice>`, `usage()` returning `Optional<CompletionUsage>`, and
    `serviceTier()`. `ChatCompletion.Choice` exposes `message()`, `finishReason()`, `index()`, and
    `logprobs()`.
16. Hand-building a `ChatCompletion` in a test has non-obvious required fields.
    `ChatCompletionMessage.Builder` requires **both** `content` and `refusal`, which may be JSON
    null; `Choice.Builder` requires `finishReason`, `index`, `logprobs`, and `message`;
    `ChatCompletion.Builder` requires `id`, `choices`, `created`, and `model`, while `object_`
    defaults to `chat.completion` and need not be set. The minimal compilable form is:
    `ChatCompletionMessage.builder().content("ok").refusal((String) null).build()` and
    `ChatCompletion.Choice.builder().finishReason(FinishReason.STOP).index(0L)
    .logprobs((ChatCompletion.Choice.Logprobs) null).message(message).build()`. The casts are
    required to pick the nullable overload.
17. `ChatCompletion.Choice.FinishReason` is a wrapper class with the constants `STOP`, `LENGTH`,
    `TOOL_CALLS`, `CONTENT_FILTER`, and `FUNCTION_CALL`. `value()` returns a nested `Value` enum
    containing those five plus `_UNKNOWN`; `known()` throws on an unknown value, so the mapper uses
    `value()` and never `known()`.
18. `ChatCompletionMessage` exposes `content()` returning `Optional<String>`, `refusal()`, and
    `toolCalls()` returning `Optional<List<ChatCompletionMessageToolCall>>`. The union type is
    `ChatCompletionMessageToolCall` (not `...Union`), with `isFunction()`, `asFunction()`, and
    `ofFunction(...)`. `ChatCompletionMessageFunctionToolCall` has `id()` and `function()` with
    `name()` and `arguments()`, where `arguments()` is a JSON **String**. Test construction:
    `ChatCompletionMessageFunctionToolCall.builder().id("call_1").function(
    ChatCompletionMessageFunctionToolCall.Function.builder().name("lookup").arguments("{}").build())
    .build()`, then `ChatCompletionMessageToolCall.ofFunction(...)`.
19. `com.openai.models.completions.CompletionUsage` exposes `promptTokens()`, `completionTokens()`,
    and `totalTokens()` returning `long`, and its builder requires all three. The typed accessors
    throw `OpenAIInvalidDataException` when a field is absent on the wire.
20. Errors live in `com.openai.errors`: `OpenAIException extends RuntimeException`, then
    `OpenAIServiceException` (abstract) with `BadRequestException`, `RateLimitException`,
    `NotFoundException`, and `InternalServerException`, plus `OpenAIIoException`,
    `OpenAIInvalidDataException`, and `OpenAIRetryableException`. All are unchecked.
    `BadRequestException` has no public constructor; build one with
    `BadRequestException.builder().headers(com.openai.core.http.Headers.builder().build()).build()`.
    The simplest directly constructible SDK exception is `new OpenAIIoException("message")`.
21. Cancellation does not reach the transport. `ChatCompletionServiceAsyncImpl.create` returns
    `withRawResponse().create(...).thenApply { it.parse() }`, and the OkHttp call is cancelled only
    when the **transport** future itself completes with a `CancellationException`. A JDK
    `CompletableFuture.cancel()` never completes its antecedent, so cancelling the future the SDK
    returns does not abort the HTTP request.
22. The SDK ships no in-memory or fake client. Its own tests need a Prism mock server on
    `localhost:4010`, which is unusable here. `OpenAIClientAsync` is an interface, but it declares
    roughly twenty-six service accessors plus a nested `WithRawResponse`, so faking it is
    disproportionate. That is why this slice owns a one-method port instead.
23. The SDK checks Jackson at runtime: major 2, at least 2.13.4, with exactly one version rejected,
    2.18.1. The repository pin 2.22.1 passes.

## Corrections to the research report

The research report is otherwise accurate; these three points are corrected here and the corrected
form is what the tasks below implement.

1. The report was unsure whether a `model(String)` overload exists. It does (fact 9), so
   `ChatModel.of(...)` is not used anywhere in this slice.
2. The report described `params.tools()` as a list of function tools. It is
   `Optional<List<ChatCompletionTool>>` (fact 10), so a test asserting on a mapped tool must call
   `asFunction()` first.
3. The report suggested reading usage defensively and treating a missing field as "no usage or 0
   with a documented choice". This plan does not do that. A silent fallback is exactly what
   `AGENTS.md` forbids, and a `CompletionUsage` missing a field cannot be constructed through the
   SDK builder, so such a path would be untestable. Usage is read with the typed accessors, an
   absent `usage()` maps to `null`, and a compatible server that returns a partial usage object
   surfaces `OpenAIInvalidDataException` unchanged. That limitation is recorded in the adapter
   README instead of being hidden.

## Recorded gaps

These are stated, not fixed, in this slice.

- **G1 — request options never reach a provider.** `AgentEngine.toModelRequest` passes
  `ModelRequestOptions.empty()` and `AgentRunOptions` has no options field. AGT-011 and AGT-012 stay
  `partial`. The adapter takes defaults through its builder and lets a directly constructed
  `ModelRequest` override them, so the precedence rule is already implemented and tested at the
  adapter seam; only the engine wiring is missing.
- **G2 — one framework tool message, many OpenAI tool messages.** `ToolLoopPolicy.toolResultMessage`
  emits a single `Message(Role.TOOL, results)` holding N `ToolResultContent`, while Chat Completions
  requires one tool message per `tool_call_id`. The adapter fans out, and Task 4 pins that with a
  named test so nobody later "simplifies" it by dropping results.
- **G3 — `ToolCallContent.arguments` is `Map<String, Object>`.** OpenAI returns an arbitrary JSON
  string. A non-object argument value cannot round-trip, so the mapper fails explicitly. A JSON
  `null` *inside* an object is not affected: the key is kept with a `null` value, which
  `ToolCallContent` and `ToolArguments` both store, so an optional parameter the model explicitly
  nulled is still distinguishable from one it omitted. Fixing the non-object case properly means
  carrying the raw JSON in the core contract, which is a core change.
- **G4 — no request-id or rate-limit surface.** Response headers are reachable only through
  `withRawResponse()`. Deferred and listed as a README limitation.
- **G5 — no shared `ModelClient` contract-test base.** `agent-framework-testkit` holds only
  `DeterministicClock`, while the foundation design promises "every model provider must pass the
  same model client contract tests". Inventing that base while exactly one provider exists would
  encode this provider's shape as the contract. It is the follow-up that unblocks provider two.

## Request mapping table

Framework to `ChatCompletionCreateParams`. Every "fail" is explicit, names what it saw, and carries
no payload text.

| Framework input | SDK output | Rule |
| --- | --- | --- |
| `Role.SYSTEM` | `ChatCompletionMessageParam.ofSystem(...)` | text only |
| `Role.of("developer")` | `ofDeveloper(...)` | accepted explicitly even though `Role.knownValues()` lists four values |
| `Role.USER` | `ofUser(...)` | text only in this slice |
| `Role.ASSISTANT` | `ofAssistant(...)` or the echoed SDK message | see the echo rule below |
| `Role.TOOL` | one `ofTool(...)` **per** `ToolResultContent` | fan-out, keyed by `callId`, in order (G2) |
| `ToolResultContent.error()` | not represented | Chat Completions has no error flag on a tool message; the text still reaches the model and the limitation is documented, not disguised with an invented prefix |
| non-text content nested inside a `ToolResultContent` | fail | `IllegalArgumentException` naming the content type, or `UnsupportedOperationException` for extension content |
| any other role | fail | `IllegalArgumentException`, message names the role value |
| several `TextContent` in one message | one joined string | joined in order with `\n`; only `TextContent` contributes |
| `ToolCallContent` on a non-assistant message | fail | `IllegalArgumentException` naming the role |
| `ToolResultContent` outside `Role.TOOL` | fail | `IllegalArgumentException` naming the role |
| non-`ToolResultContent` inside a `Role.TOOL` message | fail | `IllegalArgumentException` naming the content type |
| any `ExtensionContent` | fail | `UnsupportedOperationException` naming `type()` only, never the payload |
| `ToolDefinition` | `ChatCompletionFunctionTool` | `name`, `description` when not blank, `inputSchema` entries as `JsonValue.from(value)` |
| empty `request.tools()` | no `tools` field at all | some compatible servers reject `tools: []` |
| `options().temperature()` | `temperature(double)` | request value wins over the builder default |
| `options().maxOutputTokens()` | `maxCompletionTokens(long)` | request value wins over the builder default |
| non-empty `options().providerOptions()` | fail | `IllegalArgumentException` naming the provider ids present |
| model name | `model(String)` | adapter-owned; the engine never supplies one |
| `tool_choice` | omitted | the neutral contract expresses nothing for it yet |

**Assistant echo rule.** When an assistant `Message` carries a `ChatCompletionMessage` in
`rawRepresentation()`, echo that object with `addMessage(ChatCompletionMessage)` so the `arguments`
string is byte-identical to what the model produced. Otherwise rebuild a
`ChatCompletionAssistantMessageParam` from the framework content: joined text when non-empty, then
one `ChatCompletionMessageFunctionToolCall` per `ToolCallContent`, with `arguments` re-serialised
from the parsed map. The engine preserves `rawRepresentation` through `echoedMessages` for a
non-streamed response, so the first branch is the normal one and the second is the fallback that
keeps a caller-constructed history working.

## Response mapping table

`ChatCompletion` to `ModelResponse`.

| SDK input | Framework output | Rule |
| --- | --- | --- |
| `choices()` size other than 1 | fail | this slice never sends `n`; `IllegalStateException` naming the count |
| `message.content()` present and not blank | `TextContent` | absent or blank contributes nothing; never an empty `TextContent` |
| `message.toolCalls()` | `ToolCallContent` per call | one assistant `Message`, text first, then calls in SDK order |
| a tool call that is not a function call | fail | `IllegalStateException`; no coercion |
| blank or absent tool-call id | fail | `IllegalStateException`; never a synthesised id |
| blank function name | fail | `IllegalStateException` naming the call id |
| `arguments()` equal to `""` | `Map.of()` | OpenAI emits `""` or `{}` for a zero-argument tool |
| an argument value that is JSON `null`, for example `{"unit":null}` | the key survives, mapped to a `null` value | models routinely emit `null` for an optional parameter; the parsed map is returned as `Collections.unmodifiableMap(new LinkedHashMap<>(...))` because `Map.copyOf` throws `NullPointerException` on a null value, and `ToolCallContent` copies it into a `LinkedHashMap`, which tolerates it |
| `arguments()` that is valid JSON but not an object | fail | `IllegalStateException` naming tool and call id, no payload (G3) |
| `arguments()` that is not valid JSON | fail | same failure, no payload, and no parser exception attached as a cause or as a suppressed throwable |
| `arguments()` with input after the first JSON value, for example `{"a":1}{"b":2}` or `{"a":1} oops` | fail | the parser runs with `DeserializationFeature.FAIL_ON_TRAILING_TOKENS`; `readTree` otherwise keeps the first value and drops the rest, which hands a tool a call the model did not make |
| `arguments()` with a repeated key, for example `{"city":"Seoul","city":"Busan"}` | fail | the parser runs with `StreamReadFeature.STRICT_DUPLICATE_DETECTION`; Jackson's default is last-wins, and silently choosing one of two values the model sent changes its intent, which is not a decision this adapter is entitled to make |
| any argument parse failure | no payload anywhere in the chain | the failure is this adapter's own `IllegalStateException` naming tool, call id, and the structural requirement, and the Jackson exception is attached nowhere - not as a cause and not as a suppressed throwable - because Jackson quotes the token it rejected in its own message text (`{"name": <token>}` yields `Unrecognized token '<token>'`), which no source-location setting redacts. `StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION` stays disabled as a second layer for any parser exception that escapes by a route the mapper does not catch |
| `message.functionCall()` present, the deprecated pre-tools payload | fail | `IllegalStateException` naming `function_call` and the function, never the arguments; the shape carries no call id, so it can neither be mapped nor be dropped without ending the run with an answer the model never gave |
| `finish_reason` `stop` | `FinishReason.STOP` | |
| `length` | `FinishReason.LENGTH` | |
| `tool_calls` | `FinishReason.TOOL_CALLS` | |
| `content_filter` | `FinishReason.CONTENT_FILTER` | |
| `function_call` | `FinishReason.TOOL_CALLS` | deprecated wire value, mapped deliberately and tested |
| `tool_calls` or `function_call` with no tool call in the message | fail | `IllegalStateException` naming the wire value and nothing else; `AgentEngine` ends its loop on an empty tool-call list, so the turn would otherwise be reported as a successful final answer the model never gave. It fires on a text-only turn too, because returning that text as the answer is the worse outcome |
| a tool call under `stop`, `length`, or any other finish reason | mapped, and the finish reason is reported as sent | deliberately asymmetric with the row above: a call that is present is executable whatever the reason says, and neither rejecting the turn nor rewriting the reason to `tool_calls` would report what the server actually returned |
| any unknown value | `FinishReason.UNKNOWN` | read through `value()`, never `known()` |
| `usage()` empty | `usage == null` | `ModelResponse` allows it and `AgentEngine.combineUsage` tolerates it |
| `usage()` present | `Usage(promptTokens, completionTokens, totalTokens)` | typed accessors; a partial usage object surfaces the SDK exception |
| `id()`, `model()`, `created()` | `metadata` | keys `openai.response.id`, `openai.response.model`, `openai.response.created` |
| the `ChatCompletion` object | `ModelResponse.rawRepresentation` | transient diagnostic handle |
| the `ChatCompletionMessage` object | `Message.rawRepresentation` | what makes the echo rule byte-faithful |
| continuation | always `null` | Chat Completions is stateless, and `ToolLoopPolicy.validateContinuation` rejects a token when tools are configured |
| a completion with neither text nor tool calls | no message at all | an empty assistant message is not what the model produced |

`serviceTier()` is deliberately not mapped. Nothing in this slice needs it, and adding a metadata
key for it would commit to an accessor shape no test here exercises.

## Cancellation contract

State it plainly in Javadoc, in the README, and in a test name.

1. A signal already cancelled when `run` is called means `ChatCompletionsOperations.create` is
   **never invoked**, and the returned stage fails with `CancellationException`.
2. After dispatch, cancelling completes the returned stage exceptionally with
   `CancellationException` promptly, and the cancellation listener is removed on **every**
   completion path through the handle `CancellationSignal.onCancel` returns, so a finished run
   leaves no listener behind.
3. The in-flight HTTP request is **not** aborted, because the future the SDK returns is derived by
   `thenApply` from the transport future (fact 21). Do not claim hard cancellation anywhere.
   Cancelling the future a caller takes from the returned stage aborts nothing either: that future
   is a copy of the outcome, not the call. A timeout is what bounds the abandoned work, and it
   bounds one **attempt**: the SDK client the host injected retries inside a single dispatch
   (`maxRetries` on its own builder, two by default), so abandoned work can last the per-request
   timeout times the number of attempts, plus the SDK's backoff between them.
4. A failure from the operations port is delivered with the original SDK exception preserved as the
   cause of the returned stage, unwrapped from any `CompletionException` or `ExecutionException`
   wrapper, never flattened into a message string.
5. The stage handed to a caller carries no completion authority: it is a minimal completion stage,
   so completing or cancelling the future taken from it settles that future alone and never decides
   this call's outcome, hides the provider's own answer, or removes a cancellation listener while
   the request it belongs to is still in flight.
6. A null argument is a call-site programming error, not a request failure: it is rejected by name
   where the call was made. A `java.lang.Error` from the dispatch is rethrown as the same instance
   after the bridge settles the listener and the result it owns, because turning a fatal error into
   a failed stage would offer it to a caller to map or retry as if the provider had answered.

## Module and build policy decision

`ProjectLayout.projectDependenciesOf` is a plain regular expression over the whole build file and
cannot tell `api(project(...))` from `testImplementation(project(...))`, while
`ModuleCompositionPolicyTest.libraryProjectOnlyDependsOnAllowedProjects` asserts exact set equality.
So as the policy stands today, adding `testImplementation(project(":agent-framework-engine"))` to
the provider build file **fails** `policyCheck`, and the obvious workaround — adding the engine to
`ALLOWED_DEPENDENCIES` — would silently legalise a *production* engine dependency from a provider,
which `AGENTS.md` and rule 3 of the module composition contract both forbid.

Task 1 therefore teaches the policy the difference: production project dependencies stay exactly
`:agent-framework-api`, and test-only project dependencies get their own explicit allowlist. The
end-to-end loop test then lives in the provider module, next to the adapter it proves, over the
package-private operations seam, and no public API grows to make a cross-module test possible.

Nothing about the production rule is relaxed. The production parse gets *stricter*, because a
declaration whose configuration it cannot read is now a failure rather than an unclassified match,
and both allowlists keep exact set equality. Task 2 adds the mutation proof: it takes the provider's
real build file, flips `testImplementation` to `implementation`, and asserts the production
allowlist assertion fails on the result. That is what makes "the test allowlist cannot legalise a
shipped engine dependency" an executed statement rather than a claim in a commit message.

The alternative considered and rejected was hosting that test in `:samples:sample-standalone`, which
is exempt from the library policy and already depends on the engine. It was rejected because the
sample cannot reach a package-private seam, so it would have forced either a public
`Builder.operations(...)` method — growing the supported facade purely for testability — or a
contract proof living in a sample rather than beside the code it proves.

## File Structure

Production, under `providers/agent-framework-openai/src/main/java/io/github/hellices/agentframework/openai/`:

- `OpenAiChatModelClient.java` (create) - the only supported public type. Final, implements
  `ModelClient`, nested `Builder`, package-private `Builder.operations(...)` seam.
- `package-info.java` (create) - what the adapter supports, what it borrows, and the cancellation
  limitation.
- `internal/ChatCompletionsOperations.java` (create) - the one-method port; the reason no test needs
  a network.
- `internal/SdkChatCompletionsOperations.java` (create) - the only place `OpenAIClientAsync` is
  called.
- `internal/OpenAiChatSettings.java` (create) - validated value object: model, optional temperature,
  optional maximum output tokens, request timeout.
- `internal/ChatCompletionRequestMapper.java` (create) - neutral request to
  `ChatCompletionCreateParams`.
- `internal/ChatCompletionResponseMapper.java` (create) - `ChatCompletion` to `ModelResponse`.
- `internal/OpenAiCallBridge.java` (create) - cancellation guard, listener de-registration, failure
  unwrapping.
- `internal/package-info.java` (create) - the "public only because the facade needs it" notice.

Tests, under `providers/agent-framework-openai/src/test/java/io/github/hellices/agentframework/openai/`:

Nine test classes and two support files. The README instruction in Task 12 names the nine test
classes; the two support files are fixtures, not evidence.

- `internal/ChatCompletionRequestMapperTest.java` (create) - test class
- `internal/ChatCompletionRequestMapperToolsTest.java` (create) - test class
- `internal/ChatCompletionResponseMapperTest.java` (create) - test class
- `internal/ChatCompletionResponseMapperToolCallTest.java` (create) - test class
- `internal/OpenAiChatSettingsTest.java` (create) - test class
- `internal/OpenAiCallBridgeTest.java` (create) - test class
- `internal/ChatCompletionsFixture.java` (create) - support: hand-built `ChatCompletion` values whose
  non-obvious required fields (fact 16) live in one place
- `FakeChatCompletionsOperations.java` (create) - support: records params, answers from a script,
  counts invocations
- `OpenAiChatModelClientTest.java` (create) - test class: builder validation, option precedence,
  provider-option rejection, failure preservation
- `OpenAiChatModelClientCancellationTest.java` (create) - test class
- `OpenAiChatModelClientToolLoopTest.java` (create) - test class: the end-to-end proof over
  `AgentEngine`

Sample:

- `samples/sample-standalone/src/main/java/io/github/hellices/agentframework/samples/standalone/StandaloneAgentApplication.java` (modify)
- `samples/sample-standalone/src/test/java/io/github/hellices/agentframework/samples/standalone/StandaloneAgentApplicationTest.java` (modify)
- `samples/sample-standalone/build.gradle.kts` (modify)
- `samples/sample-standalone/gradle.lockfile` (modify)

Build and policy:

- `settings.gradle.kts` (modify)
- `gradle/libs.versions.toml` (modify)
- `providers/agent-framework-openai/build.gradle.kts` (create)
- `providers/agent-framework-openai/gradle.lockfile` (create)
- `agent-framework-bom/build.gradle.kts` (modify)
- `build-tools/harness-policy/src/test/java/io/github/hellices/agentframework/build/harness/ProjectLayout.java` (modify)
- `build-tools/harness-policy/src/test/java/io/github/hellices/agentframework/build/harness/ProjectLayoutTest.java` (create)
- `build-tools/harness-policy/src/test/java/io/github/hellices/agentframework/build/harness/ModuleCompositionPolicyTest.java` (modify)
- `build-tools/harness-policy/src/test/java/io/github/hellices/agentframework/build/harness/PublishedBomContractTest.java` (modify)

Documentation:

- `providers/agent-framework-openai/README.md` (create)
- `docs/design/module-composition.md` (modify)
- `docs/design/requirements-design/requirements-traceability-matrix.md` (modify)
- `docs/operations/getting-started.md` (modify)
- `README.md` (modify)
- `docs/ko/README.md` (modify)

---

### Task 1: Teach the module policy to separate production from test project dependencies

`ProjectLayout.projectDependenciesOf` reads the whole build file with one regular expression, so the
module composition policy cannot tell a shipped dependency from a test-only one. Before a provider
can compile its end-to-end test against the engine, the policy has to make that distinction, or the
allowlist entry that permits the test would also permit a production dependency the architecture
forbids. This task changes only the harness; no product module changes and no existing allowlist
entry moves.

**Files:**
- Modify: `build-tools/harness-policy/src/test/java/io/github/hellices/agentframework/build/harness/ProjectLayout.java`
- Modify: `build-tools/harness-policy/src/test/java/io/github/hellices/agentframework/build/harness/ModuleCompositionPolicyTest.java`
- Test: `build-tools/harness-policy/src/test/java/io/github/hellices/agentframework/build/harness/ProjectLayoutTest.java`
- Modify: `docs/design/module-composition.md`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces, package-private in `io.github.hellices.agentframework.build.harness`:
  - `static List<String> ProjectLayout.projectDependenciesOf(String gradlePath)` - production
    configurations only, behaviour narrowed from today's "every configuration".
  - `static List<String> ProjectLayout.testProjectDependenciesOf(String gradlePath)` - test
    configurations only.
  - `static List<String> ProjectLayout.projectDependenciesIn(String buildFileText)` and
    `testProjectDependenciesIn(String buildFileText)` - the same parse over supplied text, so the
    self-test does not need a fixture project on disk.

- [ ] **Step 1: Write the failing parser self-test**

Create `build-tools/harness-policy/src/test/java/io/github/hellices/agentframework/build/harness/ProjectLayoutTest.java`:

```java
package io.github.hellices.agentframework.build.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Self test for the build file parser the module composition policy is built on.
 *
 * <p>The policy is only as trustworthy as this parse. A production dependency that the parser
 * reported as a test dependency would let a provider ship against the engine while
 * {@code policyCheck} stayed green, which is precisely the failure the dependency direction rules
 * exist to prevent.
 */
class ProjectLayoutTest {

  @Test
  void classifiesProductionConfigurationsAsProductionDependencies() {
    String buildFile =
        """
        dependencies {
            api(project(":agent-framework-api"))
            implementation(project(":agent-framework-engine"))
        }
        """;

    assertThat(ProjectLayout.projectDependenciesIn(buildFile))
        .containsExactly(":agent-framework-api", ":agent-framework-engine");
    assertThat(ProjectLayout.testProjectDependenciesIn(buildFile)).isEmpty();
  }

  @Test
  void classifiesTestConfigurationsAsTestDependencies() {
    String buildFile =
        """
        dependencies {
            api(project(":agent-framework-api"))
            testImplementation(project(":agent-framework-engine"))
            testRuntimeOnly(project(":agent-framework-testkit"))
        }
        """;

    assertThat(ProjectLayout.projectDependenciesIn(buildFile))
        .containsExactly(":agent-framework-api");
    assertThat(ProjectLayout.testProjectDependenciesIn(buildFile))
        .containsExactly(":agent-framework-engine", ":agent-framework-testkit");
  }

  @Test
  void classifiesAProjectInsideAPlatformWrapperByItsConfiguration() {
    String buildFile =
        """
        dependencies {
            api(platform(project(":agent-framework-bom")))
        }
        """;

    assertThat(ProjectLayout.projectDependenciesIn(buildFile))
        .containsExactly(":agent-framework-bom");
  }

  @Test
  void refusesAProjectDependencyWhoseConfigurationItCannotRead() {
    // A declaration split across lines would otherwise be invisible to the policy, and an invisible
    // dependency is worse than a rejected one: the allowlist would report a module as depending on
    // nothing while it shipped against the engine.
    String buildFile =
        """
        dependencies {
            api(
                project(":agent-framework-api"))
        }
        """;

    assertThatThrownBy(() -> ProjectLayout.projectDependenciesIn(buildFile))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(":agent-framework-api")
        .hasMessageContaining("one line");
  }

  @Test
  void classifiesEveryProjectReferenceAsExactlyOneKind() {
    // The narrowed production parse must lose nothing. If a configuration name the policy does not
    // recognise ever fell out of both lists, a shipped dependency would become invisible instead of
    // rejected, which is the whole failure mode this parse exists to prevent.
    String buildFile =
        """
        dependencies {
            api(project(":agent-framework-api"))
            implementation(project(":agent-framework-engine"))
            compileOnly(project(":agent-framework-testkit"))
            testImplementation(project(":agent-framework-engine"))
            testFixturesApi(project(":agent-framework-api"))
        }
        """;

    assertThat(ProjectLayout.projectDependenciesIn(buildFile))
        .containsExactly(
            ":agent-framework-api", ":agent-framework-engine", ":agent-framework-testkit");
    assertThat(ProjectLayout.testProjectDependenciesIn(buildFile))
        .containsExactly(":agent-framework-engine", ":agent-framework-api");
  }
}
```

`testFixturesApi` is classified as a test configuration by the `test` prefix, which is the intended
reading: nothing published from a library reaches a consumer through it in this repository, and no
module uses it today. If one ever should be treated as production, that is a policy decision to make
explicitly, not a parse to loosen quietly.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :build-tools:harness-policy:test --tests 'io.github.hellices.agentframework.build.harness.ProjectLayoutTest'`

Expected: compilation FAILS with `cannot find symbol: method projectDependenciesIn(java.lang.String)`
and `cannot find symbol: method testProjectDependenciesIn(java.lang.String)`.

- [ ] **Step 3: Implement the configuration-aware parse**

In `ProjectLayout.java`, replace the single `PROJECT_DEPENDENCY` pattern with a line-oriented parse:

```java
  private static final Pattern CONFIGURATION = Pattern.compile("^\\s*([A-Za-z][A-Za-z0-9]*)\\s*\\(");

  private static final Pattern PROJECT_REFERENCE =
      Pattern.compile("project\\(\"(:[A-Za-z0-9:_-]+)\"\\)");
```

and add the four methods:

```java
  /**
   * Returns the project dependencies a build file declares on a production configuration.
   *
   * <p>Test configurations are excluded on purpose. A test-only project dependency does not reach a
   * consumer, so treating it as a shipped dependency would force the allowlist to permit a
   * production dependency in order to permit a test.
   *
   * @param gradlePath the Gradle project path
   * @return the Gradle paths this project ships against
   */
  static List<String> projectDependenciesOf(String gradlePath) {
    return projectDependenciesIn(buildFileText(gradlePath));
  }

  /**
   * Returns the project dependencies a build file declares on a test configuration.
   *
   * @param gradlePath the Gradle project path
   * @return the Gradle paths this project compiles or runs its tests against
   */
  static List<String> testProjectDependenciesOf(String gradlePath) {
    return testProjectDependenciesIn(buildFileText(gradlePath));
  }

  static List<String> projectDependenciesIn(String buildFileText) {
    return dependenciesIn(buildFileText, false);
  }

  static List<String> testProjectDependenciesIn(String buildFileText) {
    return dependenciesIn(buildFileText, true);
  }

  private static List<String> dependenciesIn(String buildFileText, boolean testConfigurations) {
    List<String> dependencies = new ArrayList<>();
    for (String line : buildFileText.split("\\R", -1)) {
      Matcher reference = PROJECT_REFERENCE.matcher(line);
      if (!reference.find()) {
        continue;
      }
      Matcher configuration = CONFIGURATION.matcher(line);
      // "project(" and "platform(" also match the configuration shape, so a declaration whose
      // configuration sits on an earlier line would otherwise be classified as a configuration
      // called "project" and silently escape the allowlist.
      if (!configuration.find()
          || "project".equals(configuration.group(1))
          || "platform".equals(configuration.group(1))) {
        throw new IllegalStateException(
            "Cannot read the configuration of the project dependency "
                + reference.group(1)
                + ". Declare a project dependency on one line so the module composition policy can"
                + " tell a production dependency from a test dependency.");
      }
      boolean isTest = configuration.group(1).startsWith("test");
      if (isTest == testConfigurations) {
        dependencies.add(reference.group(1));
        while (reference.find()) {
          dependencies.add(reference.group(1));
        }
      }
    }
    return List.copyOf(dependencies);
  }
```

- [ ] **Step 4: Run the parser self-test again**

Run: `./gradlew :build-tools:harness-policy:test --tests 'io.github.hellices.agentframework.build.harness.ProjectLayoutTest'`

Expected: PASS, 5 tests. Confirm the count with
`grep -o 'tests="[0-9]*"' build-tools/harness-policy/build/test-results/test/TEST-io.github.hellices.agentframework.build.harness.ProjectLayoutTest.xml`.

- [ ] **Step 5: Add the test-dependency allowlist to the module composition policy**

In `ModuleCompositionPolicyTest.java`, add the map next to `ALLOWED_DEPENDENCIES`:

```java
  /**
   * Project dependencies a library may compile or run its tests against.
   *
   * <p>Separate from {@link #ALLOWED_DEPENDENCIES} because a test-only dependency reaches no
   * consumer. Folding the two together would mean that permitting a provider to test against the
   * engine also permitted it to ship against the engine, which the dependency direction rules
   * forbid.
   */
  private static final Map<String, List<String>> ALLOWED_TEST_DEPENDENCIES = Map.of();
```

Both allowlists keep exact set equality; neither becomes a "contains" check. Extract the production
assertion so the mutation proof in Task 2 can exercise the very assertion the policy runs, rather
than a copy of it that could drift:

```java
  @ParameterizedTest
  @MethodSource("libraryProjects")
  void libraryProjectOnlyDependsOnAllowedProjects(String gradlePath) {
    assertProductionDependenciesAllowed(
        gradlePath, ProjectLayout.projectDependenciesOf(gradlePath));
  }

  private static void assertProductionDependenciesAllowed(
      String gradlePath, List<String> productionDependencies) {
    assertThat(productionDependencies)
        .containsExactlyInAnyOrderElementsOf(ALLOWED_DEPENDENCIES.get(gradlePath));
  }

  @ParameterizedTest
  @MethodSource("libraryProjects")
  void libraryProjectOnlyTestsAgainstAllowedProjects(String gradlePath) {
    assertThat(ProjectLayout.testProjectDependenciesOf(gradlePath))
        .containsExactlyInAnyOrderElementsOf(
            ALLOWED_TEST_DEPENDENCIES.getOrDefault(gradlePath, List.of()));
  }
```

Then widen `noProductProjectDependsOnAHarnessProject` so narrowing the production parse does not
open a hole:

```java
  @Test
  void noProductProjectDependsOnAHarnessProject() {
    for (String gradlePath : LIBRARY_PROJECTS) {
      assertThat(ProjectLayout.projectDependenciesOf(gradlePath))
          .noneMatch(dependency -> dependency.startsWith(HARNESS_PREFIX));
      assertThat(ProjectLayout.testProjectDependenciesOf(gradlePath))
          .noneMatch(dependency -> dependency.startsWith(HARNESS_PREFIX));
    }
  }
```

- [ ] **Step 6: Record the distinction in the module composition contract**

In `docs/design/module-composition.md`, under "Rules", change rule 3 to name the two kinds
explicitly and add a rule for the test allowlist:

```markdown
3. `:agent-framework-engine`, `:agent-framework-testkit`, and `:integrations:agent-framework-mcp`
   depend on `:agent-framework-api` only. An integration additionally depends on the protocol or
   provider SDK it adapts, which never reaches the API or engine modules.
4. A production project dependency is what a consumer inherits, so it is what the dependency
   direction rules constrain. A test-only project dependency reaches no consumer and is allowlisted
   separately in `ModuleCompositionPolicyTest`. Declare every project dependency on one line; the
   policy reads the configuration from the same line and refuses a declaration it cannot classify.
```

Renumber the rules that follow.

- [ ] **Step 7: Run the whole policy suite**

Run: `./gradlew policyCheck`

Expected: PASS. Every existing library declares only production project dependencies, so each one's
test allowlist is empty and the new check passes without touching an existing module.

- [ ] **Step 8: Run quality**

Run: `./gradlew :build-tools:harness-policy:quality`

Expected: PASS. If spotless reports formatting, run `./gradlew spotlessApply` and re-run.

- [ ] **Step 9: Commit**

```bash
git add build-tools/harness-policy/src/test/java/io/github/hellices/agentframework/build/harness/ProjectLayout.java \
        build-tools/harness-policy/src/test/java/io/github/hellices/agentframework/build/harness/ProjectLayoutTest.java \
        build-tools/harness-policy/src/test/java/io/github/hellices/agentframework/build/harness/ModuleCompositionPolicyTest.java \
        docs/design/module-composition.md
git commit -m "$(cat <<'MSG'
policy: separate production from test project dependencies

The module composition policy read every project dependency with one regular
expression, so a test-only dependency could only be allowed by allowing a
production dependency as well. A provider that compiles its end-to-end test
against the engine would then also have been allowed to ship against it. The
parse now reads the configuration from the same line and refuses a declaration
it cannot classify, and test-only dependencies have their own allowlist.

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
MSG
)"
```

---

### Task 2: Register the provider module

Create the module skeleton and make every registration check see it: settings, the version catalog,
the build file, the BOM, the policy lists, the module composition document, and the lockfile. No
adapter behaviour ships in this task; the point is that the module exists, resolves, compiles, and
is governed before any code depends on it. This task also turns PRV-001 from a claim into a test.

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `gradle/libs.versions.toml`
- Create: `providers/agent-framework-openai/build.gradle.kts`
- Create: `providers/agent-framework-openai/src/main/java/io/github/hellices/agentframework/openai/package-info.java`
- Create: `providers/agent-framework-openai/src/main/java/io/github/hellices/agentframework/openai/internal/package-info.java`
- Create: `providers/agent-framework-openai/gradle.lockfile`
- Modify: `agent-framework-bom/build.gradle.kts`
- Modify: `build-tools/harness-policy/src/test/java/io/github/hellices/agentframework/build/harness/ModuleCompositionPolicyTest.java`
- Modify: `build-tools/harness-policy/src/test/java/io/github/hellices/agentframework/build/harness/PublishedBomContractTest.java`
- Modify: `docs/design/module-composition.md`

**Interfaces:**
- Consumes: `ProjectLayout.testProjectDependenciesOf` and `ALLOWED_TEST_DEPENDENCIES` from Task 1.
- Produces: the Gradle project `:providers:agent-framework-openai`, publishing as
  `agent-framework-openai`, and the packages `io.github.hellices.agentframework.openai` and
  `io.github.hellices.agentframework.openai.internal`.

- [ ] **Step 1: Add the failing policy expectations**

In `ModuleCompositionPolicyTest.java`:

```java
  private static final List<String> LIBRARY_PROJECTS =
      List.of(
          ":agent-framework-api",
          ":agent-framework-engine",
          ":agent-framework-testkit",
          ":integrations:agent-framework-mcp",
          ":providers:agent-framework-openai");
```

```java
  private static final Map<String, List<String>> ALLOWED_DEPENDENCIES =
      Map.of(
          ":agent-framework-api", List.of(),
          ":agent-framework-engine", List.of(":agent-framework-api"),
          ":agent-framework-testkit", List.of(":agent-framework-api"),
          ":integrations:agent-framework-mcp", List.of(":agent-framework-api"),
          ":providers:agent-framework-openai", List.of(":agent-framework-api"));
```

```java
  /**
   * Project dependencies a library may compile or run its tests against.
   *
   * <p>The OpenAI adapter proves that its mapping and the real tool loop agree by running
   * {@code AgentEngine} over a faked operations port. That proof belongs next to the adapter, and
   * the engine it needs is a test dependency only: it appears on no consumer classpath, which
   * {@code libraryProjectOnlyDependsOnAllowedProjects} keeps enforcing separately.
   */
  private static final Map<String, List<String>> ALLOWED_TEST_DEPENDENCIES =
      Map.of(":providers:agent-framework-openai", List.of(":agent-framework-engine"));
```

Add the executable evidence for PRV-001, which until now had none:

```java
  /** Lockfiles of the modules a provider SDK must never reach. */
  private static final List<String> CORE_LOCKFILES =
      List.of(
          "agent-framework-api/gradle.lockfile",
          "agent-framework-engine/gradle.lockfile",
          "agent-framework-testkit/gradle.lockfile");

  @Test
  void noProviderSdkReachesACoreClasspath() throws IOException {
    // PRV-001 is a resolution fact, not a build file fact: a provider SDK could arrive
    // transitively without any core build file naming it. The lockfiles are the only place that
    // shows what actually resolves.
    for (String lockfile : CORE_LOCKFILES) {
      String resolved =
          Files.readString(RepositoryPaths.root().resolve(lockfile), StandardCharsets.UTF_8);
      assertThat(resolved)
          .withFailMessage(
              "%s resolves a provider SDK. The core modules must know only the neutral ports.",
              lockfile)
          .doesNotContain("com.openai:");
    }
  }
```

Then add the mutation proof that the new test allowlist cannot legalise a shipped engine dependency.
It reads the provider's real build file, flips the one configuration, and asserts the production
assertion from Task 1 fails on the result:

```java
  @Test
  void aProductionEngineDependencyOnTheProviderFailsTheAllowlist() {
    // The point of splitting the allowlists is that permitting a test dependency must not permit a
    // shipped one. Asserting the split exists proves nothing; this mutates the real build file and
    // proves the production assertion rejects the result.
    String buildFile = ProjectLayout.buildFileText(":providers:agent-framework-openai");
    String testDeclaration = "testImplementation(project(\":agent-framework-engine\"))";
    assertThat(buildFile)
        .withFailMessage(
            "This proof mutates %s. If the declaration was reworded, update the mutation rather"
                + " than deleting the test.",
            testDeclaration)
        .contains(testDeclaration);

    String mutated =
        buildFile.replace(testDeclaration, "implementation(project(\":agent-framework-engine\"))");

    assertThat(ProjectLayout.testProjectDependenciesIn(mutated)).isEmpty();
    assertThat(ProjectLayout.projectDependenciesIn(mutated))
        .contains(":agent-framework-engine");
    assertThatThrownBy(
            () ->
                assertProductionDependenciesAllowed(
                    ":providers:agent-framework-openai",
                    ProjectLayout.projectDependenciesIn(mutated)))
        .isInstanceOf(AssertionError.class);
  }
```

This test needs `assertThatThrownBy` imported and the provider build file to exist, so it fails
until Step 4 lands, together with the rest of this step.

Finally, give the new artifact published-BOM evidence. In
`build-tools/harness-policy/src/test/java/io/github/hellices/agentframework/build/harness/PublishedBomContractTest.java`,
add the module to the hard-coded list, which still names only the four modules that shipped before
this slice:

```java
  private static final List<String> MANAGED_ARTIFACTS =
      List.of(
          "agent-framework-api",
          "agent-framework-engine",
          "agent-framework-testkit",
          "agent-framework-mcp",
          "agent-framework-openai");
```

Without this the BOM constraint added in Step 6 would be checked only by a build file match, and the
published BOM could stop managing the new artifact without any test noticing.

- [ ] **Step 2: Run the policy to verify it fails**

Run: `./gradlew :build-tools:harness-policy:test --tests 'io.github.hellices.agentframework.build.harness.ModuleCompositionPolicyTest'`

Expected: FAILS. `settingsRegistersEveryProductProject` reports the missing
`:providers:agent-framework-openai`, `platformDeclaresConstraintsRatherThanDependencies` reports the
missing BOM constraint, and the parameterized checks plus
`aProductionEngineDependencyOnTheProviderFailsTheAllowlist` fail with an `UncheckedIOException`
because the build file does not exist. `noProviderSdkReachesACoreClasspath` passes already, which is
the point: it must keep passing after the provider lands.

`PublishedBomContractTest` skips locally when nothing has been published, so it is not the RED
signal here; Task 13 step 4 is where it runs against a published BOM.

- [ ] **Step 3: Register the project and the dependency versions**

`settings.gradle.kts`, after the MCP line:

```kotlin
include(":integrations:agent-framework-mcp")
include(":providers:agent-framework-openai")
include(":samples:sample-standalone")
```

`gradle/libs.versions.toml`. **Two library entries are new, not one**: `jackson-bom` does not exist
in the catalog today, which declares only `jackson-databind` and `jackson-dataformat-yaml`. Adding
only `openai-java` makes `platform(libs.jackson.bom)` fail at configuration time with
`Unresolved reference: bom` in Step 10. Keep both blocks alphabetical:

```toml
[versions]
...
mcpSdk = "2.0.0"
openaiJava = "4.51.0"   # new
pmd = "7.26.0"
...

[libraries]
assertj-core = { module = "org.assertj:assertj-core", version.ref = "assertj" }
jackson-bom = { module = "com.fasterxml.jackson:jackson-bom", version.ref = "jackson" }          # new
jackson-databind = { module = "com.fasterxml.jackson.core:jackson-databind", version.ref = "jackson" }
...
networknt-json-schema-validator = { module = "com.networknt:json-schema-validator", version.ref = "jsonSchemaValidator" }
openai-java = { module = "com.openai:openai-java", version.ref = "openaiJava" }                  # new
```

The `# new` markers are for the implementer; drop them when writing the file, since the catalog
carries no comments today. `jackson-bom` reuses the existing `jackson = "2.22.1"` version, so no new
version key is needed for it.

- [ ] **Step 4: Create the build file**

`providers/agent-framework-openai/build.gradle.kts`:

```kotlin
plugins {
    id("agentframework.java-library-conventions")
    id("agentframework.test-conventions")
    id("agentframework.quality-conventions")
    id("agentframework.library-publishing-conventions")
}

description = "OpenAI Chat Completions model client for Agent Framework for Java."

dependencies {
    api(project(":agent-framework-api"))

    // `api`, not `implementation`: the public builder takes a borrowed `OpenAIClientAsync`, so a
    // consumer cannot call it without the SDK on its own compile classpath. Same reasoning as
    // `integrations/agent-framework-mcp`, which takes an `McpAsyncClient`.
    api(libs.openai.java)

    // The Jackson platform is `api` for the same reason the MCP module uses `api(platform(...))`:
    // a constraint declared on `implementation` lands only in `runtimeElements`, so a consumer -
    // including `:samples:sample-standalone` - would compile against the 2.18.9 jackson-databind
    // the SDK POM declares at compile scope while running on 2.22.1. That split is the skew this
    // line exists to remove. Jackson itself stays `implementation`: it is used to parse and
    // re-serialise tool arguments inside the adapter and appears nowhere on the public surface.
    api(platform(libs.jackson.bom))
    implementation(libs.jackson.databind)

    // Test only, never shipped: the end-to-end proof that this adapter and the real tool loop agree
    // runs `AgentEngine`. `ModuleCompositionPolicyTest` allowlists production and test project
    // dependencies separately, so this cannot become a production dependency by accident.
    testImplementation(project(":agent-framework-engine"))
}
```

**The trade-off this makes public.** A platform on `api` is published metadata, not a private
detail. In the POM it appears as a `<dependencyManagement>` entry with `<scope>import</scope>`,
exactly as `agent-framework-mcp` publishes `mcp-bom` today, and in the Gradle module metadata it
appears in both the `apiElements` and `runtimeElements` variants. So every consumer of this artifact
inherits an alignment of *all* Jackson modules at the repository pin, and a Gradle constraint raises
a version but never lowers one: a consumer that deliberately runs an older Jackson must say so
explicitly with its own platform, a `strictly` version, or a resolution rule. That is the price of
removing the compile/runtime skew, it is the precedent this repository already set for an SDK BOM,
and it is recorded in the adapter README rather than left for a consumer to discover.

The narrower alternative — `implementation(platform(...))` plus a second `constraints { }` block on
`api` — buys nothing here: the constraint would still be published, and it would state the same
version twice.

The archive name needs no configuration: Gradle already names the project after its leaf directory,
so `:providers:agent-framework-openai` publishes as `agent-framework-openai`.

- [ ] **Step 5: Create the package documentation**

`src/main/java/io/github/hellices/agentframework/openai/package-info.java`:

```java
/**
 * OpenAI Chat Completions model client for Agent Framework for Java.
 *
 * <p>{@code OpenAiChatModelClient} implements the neutral {@code ModelClient} port over the
 * official {@code com.openai:openai-java} Chat Completions async API. It supports ordinary text
 * responses and the function-tool loop. Streaming, the Responses API, structured output,
 * embeddings, and multimodal content are not supported here.
 *
 * <p>The SDK client is borrowed, never owned. The adapter never builds, configures, reconnects, or
 * closes it, because the client owns an HTTP dispatcher, a connection pool, and an executor that
 * the host created and the host must be free to shut down. Consequently this package allocates no
 * thread, no socket, and no shutdown hook, and discarding an adapter releases nothing.
 *
 * <p>Cancellation stops the framework from waiting; it does not abort an in-flight HTTP request.
 * The future the SDK returns is derived from its transport future, and a JDK
 * {@code CompletableFuture} never cancels its antecedent. A timeout is what bounds work the
 * framework has stopped waiting for, and it bounds one attempt: this package adds no retry, while
 * the SDK client the host injected retries inside a single call according to the host's own
 * {@code maxRetries}.
 *
 * <p>Requirements for this package live in {@code docs/requirements/12-providers.md} and
 * {@code docs/requirements/01-agent-execution.md}.
 */
package io.github.hellices.agentframework.openai;
```

`src/main/java/io/github/hellices/agentframework/openai/internal/package-info.java`:

```java
/**
 * Internal machinery for the OpenAI Chat Completions adapter.
 *
 * <p>Types in this package are public only because the adapter in the parent package needs them.
 * They carry no compatibility promise and may change in any release. Depend on
 * {@code io.github.hellices.agentframework.openai} instead.
 */
package io.github.hellices.agentframework.openai.internal;
```

Note the deliberate `{@code OpenAiChatModelClient}` in the first file rather than an `{@link}`. The
publishing convention runs javadoc with `-Xdoclint:all,-missing`, which turns an unresolvable
reference into an **error**, and that class does not exist until Task 8. Task 8 upgrades it to
`{@link io.github.hellices.agentframework.openai.OpenAiChatModelClient}` once the target exists, so
`./gradlew build` or `:providers:agent-framework-openai:javadoc` succeeds at every commit on this
branch rather than only at the end.

- [ ] **Step 6: Add the BOM constraint**

In `agent-framework-bom/build.gradle.kts`, inside `constraints`:

```kotlin
        api(project(":integrations:agent-framework-mcp"))
        api(project(":providers:agent-framework-openai"))
```

`PublishedBomContractTest.MANAGED_ARTIFACTS` already gained `agent-framework-openai` in Step 1, so
this constraint is checked against the published BOM rather than only against this build file.

- [ ] **Step 7: Document the module**

In `docs/design/module-composition.md`, add the product table row after the MCP row:

```markdown
| `:providers:agent-framework-openai` | `agent-framework-openai` | OpenAI Chat Completions model client over a borrowed official SDK client. | `:agent-framework-api` (production), `:agent-framework-engine` (test only) |
```

In the layout section, `providers/` is no longer planned: change the sentence that lists which
grouping directories exist so it names `providers/` alongside `integrations/`, `samples/`, and
`build-tools/`.

- [ ] **Step 8: Write the lockfile**

Run: `./gradlew :providers:agent-framework-openai:resolveAndLockAll --write-locks`

Expected: a new `providers/agent-framework-openai/gradle.lockfile` listing `com.openai:openai-java`,
`com.openai:openai-java-client-okhttp`, `com.openai:openai-java-core`, `com.squareup.okhttp3:okhttp`,
`com.squareup.okio:okio`, the Kotlin standard library, `org.jetbrains.kotlin:kotlin-reflect`, the
Jackson modules at the repository pin, `io.swagger.core.v3:swagger-annotations`,
`com.google.errorprone:error_prone_annotations`, and the three `com.github.victools` artifacts.

Read the file before committing it, and read it **per configuration**: each line ends with the
classpaths that resolved that module. Because the platform is on `api`, the Jackson lines must name
`compileClasspath` as well as `runtimeClasspath`, not runtime alone:

```bash
grep '^com.fasterxml.jackson' providers/agent-framework-openai/gradle.lockfile
```

Expected shape, matching what `agent-framework-engine/gradle.lockfile` already shows for the same
pin: `jackson-databind:2.22.1`, `jackson-core:2.22.1`, and `jackson-bom:2.22.1` on
`compileClasspath,runtimeClasspath,testCompileClasspath,testRuntimeClasspath`, with
`jackson-annotations:2.22`, which is the version the Jackson BOM aligns annotations to. Do not
"fix" the annotations line to 2.22.1; the pin is the BOM, not one coordinate.

Then check for skew explicitly:

```bash
grep '^com.fasterxml.jackson' providers/agent-framework-openai/gradle.lockfile | grep '2\.18\.'
```

Expected: no output. A `2.18.9` line means the platform is missing, misspelled, or declared on
`implementation`, which is the compile/runtime split this build file exists to avoid. If
`kotlin-reflect` is absent, something excluded `jackson-module-kotlin` and the SDK will fail at its
first request.

- [ ] **Step 9: Confirm the core lockfiles did not move**

Run:

```bash
git status --short -- '*/gradle.lockfile'
git diff --stat -- agent-framework-api/gradle.lockfile agent-framework-engine/gradle.lockfile \
    agent-framework-testkit/gradle.lockfile
```

Expected: `git status` shows exactly one untracked file, the new provider lockfile, and `git diff`
prints nothing. `git diff` alone would have been a no-op check here, because a brand-new untracked
lockfile never appears in a diff, so an unnoticed change to a core lockfile could have passed as
"only the new provider lockfile appears". An empty diff over the three named core lockfiles is
PRV-001 holding.

- [ ] **Step 10: Run the policy and compile**

Run: `./gradlew policyCheck :providers:agent-framework-openai:compileJava`

Expected: PASS. Every registration, documentation, convention, BOM, dependency, and test-dependency
check now sees the module.

- [ ] **Step 11: Commit**

```bash
git add settings.gradle.kts gradle/libs.versions.toml agent-framework-bom/build.gradle.kts \
        providers/agent-framework-openai \
        build-tools/harness-policy/src/test/java/io/github/hellices/agentframework/build/harness/ModuleCompositionPolicyTest.java \
        build-tools/harness-policy/src/test/java/io/github/hellices/agentframework/build/harness/PublishedBomContractTest.java \
        docs/design/module-composition.md
git commit -m "$(cat <<'MSG'
build: register the OpenAI provider module

Adds providers/agent-framework-openai with the official openai-java 4.51.0
aggregator on its own classpath, the Jackson platform on api so a consumer's
compile and runtime classpaths agree, the BOM constraint, the module composition
row, and the lockfile. The SDK is `api` because the public builder takes a
borrowed OpenAIClientAsync, and the engine is a test-only dependency for the
end-to-end tool loop proof that lands later in this branch. PRV-001 now has
executable evidence: a policy test reads the core lockfiles and fails if a
provider SDK ever resolves onto them, and a mutation test proves that flipping
the engine dependency to a production configuration fails the allowlist.

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
MSG
)"
```

---

### Task 3: Adapter settings and request mapping for roles, text, and options

The first behaviour: a validated settings value object, and the mapping of messages, joined text,
and request options onto `ChatCompletionCreateParams`. Tools and tool results come next; this task
is the part that has to be right before anything else can be, because every later test builds a
request through this mapper.

**Files:**
- Create: `providers/agent-framework-openai/src/main/java/io/github/hellices/agentframework/openai/internal/OpenAiChatSettings.java`
- Create: `providers/agent-framework-openai/src/main/java/io/github/hellices/agentframework/openai/internal/ChatCompletionRequestMapper.java`
- Test: `providers/agent-framework-openai/src/test/java/io/github/hellices/agentframework/openai/internal/OpenAiChatSettingsTest.java`
- Test: `providers/agent-framework-openai/src/test/java/io/github/hellices/agentframework/openai/internal/ChatCompletionRequestMapperTest.java`

**Interfaces:**
- Consumes: the module from Task 2.
- Produces, public in `io.github.hellices.agentframework.openai.internal`:
  - `OpenAiChatSettings(String model, Double temperature, Integer maxOutputTokens, Duration requestTimeout)`
  - `String model()`, `Optional<Double> temperature()`, `OptionalInt maxOutputTokens()`,
    `Duration requestTimeout()`
  - `ChatCompletionRequestMapper()` and
    `ChatCompletionCreateParams map(ModelRequest request, OpenAiChatSettings settings)`

- [ ] **Step 1: Write the failing settings test**

Create `providers/agent-framework-openai/src/test/java/io/github/hellices/agentframework/openai/internal/OpenAiChatSettingsTest.java`:

```java
package io.github.hellices.agentframework.openai.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class OpenAiChatSettingsTest {

  @Test
  void keepsTheModelAndTheOptionalValues() {
    OpenAiChatSettings settings =
        new OpenAiChatSettings("gpt-4.1-mini", 0.2, 256, Duration.ofSeconds(60));

    assertThat(settings.model()).isEqualTo("gpt-4.1-mini");
    assertThat(settings.temperature()).hasValue(0.2);
    assertThat(settings.maxOutputTokens()).hasValue(256);
    assertThat(settings.requestTimeout()).isEqualTo(Duration.ofSeconds(60));
  }

  @Test
  void leavesTheOptionalValuesEmptyWhenTheyAreNotSupplied() {
    OpenAiChatSettings settings =
        new OpenAiChatSettings("gpt-4.1-mini", null, null, Duration.ofSeconds(60));

    assertThat(settings.temperature()).isEmpty();
    assertThat(settings.maxOutputTokens()).isEmpty();
  }

  @Test
  void rejectsABlankModel() {
    assertThatThrownBy(() -> new OpenAiChatSettings("  ", null, null, Duration.ofSeconds(60)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("model");
  }

  @Test
  void rejectsATemperatureOutsideTheNeutralRange() {
    // The same 0.0 to 2.0 range ModelRequestOptions.Builder validates, so an adapter default and a
    // request option cannot disagree about what a legal temperature is.
    assertThatThrownBy(() -> new OpenAiChatSettings("m", 2.5, null, Duration.ofSeconds(60)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("temperature");
  }

  @Test
  void rejectsANonPositiveTokenLimit() {
    assertThatThrownBy(() -> new OpenAiChatSettings("m", null, 0, Duration.ofSeconds(60)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxOutputTokens");
  }

  @Test
  void rejectsANonPositiveRequestTimeout() {
    // A zero or negative timeout would mean the abandoned work after a cancellation is never
    // reclaimed, which is the one thing the timeout exists to bound.
    assertThatThrownBy(() -> new OpenAiChatSettings("m", null, null, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("requestTimeout");
  }
}
```

- [ ] **Step 2: Run the settings test to verify it fails**

Run: `./gradlew :providers:agent-framework-openai:test --tests 'io.github.hellices.agentframework.openai.internal.OpenAiChatSettingsTest'`

Expected: compilation FAILS with `cannot find symbol: class OpenAiChatSettings`.

- [ ] **Step 3: Implement the settings**

```java
package io.github.hellices.agentframework.openai.internal;

import java.time.Duration;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The adapter-owned request settings one {@code OpenAiChatModelClient} applies to every call.
 *
 * <p>These are defaults, not overrides: a value supplied on the {@code ModelRequest} wins. They
 * exist because the engine cannot carry request options to a provider yet, so without them a model
 * name could not reach the wire at all.
 */
public final class OpenAiChatSettings {

  private final String model;
  private final Double temperature;
  private final Integer maxOutputTokens;
  private final Duration requestTimeout;

  /**
   * Creates validated settings.
   *
   * @param model the model name sent on every request, never blank
   * @param temperature the default temperature between 0.0 and 2.0, or {@code null}
   * @param maxOutputTokens the default output token limit above zero, or {@code null}
   * @param requestTimeout the per-request timeout, never {@code null} and always positive
   * @throws IllegalArgumentException if any value is outside its contract
   */
  public OpenAiChatSettings(
      String model, Double temperature, Integer maxOutputTokens, Duration requestTimeout) {
    if (model == null || model.isBlank()) {
      throw new IllegalArgumentException("model must not be blank");
    }
    if (temperature != null && (!Double.isFinite(temperature) || temperature < 0.0 || temperature > 2.0)) {
      throw new IllegalArgumentException("temperature must be between 0.0 and 2.0");
    }
    if (maxOutputTokens != null && maxOutputTokens <= 0) {
      throw new IllegalArgumentException("maxOutputTokens must be greater than 0");
    }
    if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
      throw new IllegalArgumentException("requestTimeout must be positive");
    }
    this.model = model;
    this.temperature = temperature;
    this.maxOutputTokens = maxOutputTokens;
    this.requestTimeout = requestTimeout;
  }

  public String model() {
    return model;
  }

  public Optional<Double> temperature() {
    return Optional.ofNullable(temperature);
  }

  public OptionalInt maxOutputTokens() {
    return maxOutputTokens == null ? OptionalInt.empty() : OptionalInt.of(maxOutputTokens);
  }

  public Duration requestTimeout() {
    return requestTimeout;
  }
}
```

- [ ] **Step 4: Run the settings test again**

Run: `./gradlew :providers:agent-framework-openai:test --tests 'io.github.hellices.agentframework.openai.internal.OpenAiChatSettingsTest'`

Expected: PASS, 6 tests.

- [ ] **Step 5: Write the failing request mapping test**

Create `providers/agent-framework-openai/src/test/java/io/github/hellices/agentframework/openai/internal/ChatCompletionRequestMapperTest.java`:

```java
package io.github.hellices.agentframework.openai.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import io.github.hellices.agentframework.api.agent.CancellationSignal;
import io.github.hellices.agentframework.api.message.ExtensionContent;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.Role;
import io.github.hellices.agentframework.api.message.TextContent;
import io.github.hellices.agentframework.api.message.ToolCallContent;
import io.github.hellices.agentframework.api.message.ToolResultContent;
import io.github.hellices.agentframework.spi.model.ModelProviderOption;
import io.github.hellices.agentframework.spi.model.ModelRequest;
import io.github.hellices.agentframework.spi.model.ModelRequestOptions;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChatCompletionRequestMapperTest {

  private static final OpenAiChatSettings DEFAULTS =
      new OpenAiChatSettings("gpt-4.1-mini", null, null, Duration.ofSeconds(60));

  private final ChatCompletionRequestMapper mapper = new ChatCompletionRequestMapper();

  @Test
  void mapsEachSupportedRoleToItsMessageParam() {
    ChatCompletionCreateParams params =
        mapper.map(
            request(
                List.of(
                    message(Role.SYSTEM, "be brief"),
                    message(Role.of("developer"), "obey the tools"),
                    message(Role.USER, "hello"),
                    message(Role.ASSISTANT, "hi"))),
            DEFAULTS);

    List<ChatCompletionMessageParam> messages = params.messages();
    assertThat(messages).hasSize(4);
    assertThat(messages.get(0).asSystem().content().asText()).isEqualTo("be brief");
    assertThat(messages.get(1).asDeveloper().content().asText()).isEqualTo("obey the tools");
    assertThat(messages.get(2).asUser().content().asText()).isEqualTo("hello");
    assertThat(messages.get(3).asAssistant().content().orElseThrow().asText()).isEqualTo("hi");
  }

  @Test
  void joinsSeveralTextPartsInOrderWithNewlines() {
    ChatCompletionCreateParams params =
        mapper.map(
            request(
                List.of(
                    new Message(
                        Role.USER, List.of(new TextContent("first"), new TextContent("second"))))),
            DEFAULTS);

    assertThat(params.messages().get(0).asUser().content().asText()).isEqualTo("first\nsecond");
  }

  @Test
  void rejectsAnUnknownRole() {
    assertThatThrownBy(
            () -> mapper.map(request(List.of(message(Role.of("auditor"), "who am i"))), DEFAULTS))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("auditor");
  }

  @Test
  void rejectsExtensionContentWithoutRevealingItsPayload() {
    // A message that says what could not be carried is useful. A message that quotes the payload is
    // a sensitive-data leak into logs and exception reports, which AGENTS.md forbids.
    assertThatThrownBy(
            () ->
                mapper.map(
                    request(List.of(new Message(Role.USER, List.of(new SecretContent())))),
                    DEFAULTS))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("test.secret")
        .hasMessageNotContaining("sensitive payload");
  }

  @Test
  void rejectsAToolCallOnANonAssistantMessage() {
    Message message =
        new Message(Role.USER, List.of(new ToolCallContent("call_1", "lookup", Map.of())));

    assertThatThrownBy(() -> mapper.map(request(List.of(message)), DEFAULTS))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("user");
  }

  @Test
  void rejectsAToolResultOutsideAToolMessage() {
    Message message =
        new Message(
            Role.ASSISTANT,
            List.of(new ToolResultContent("call_1", "lookup", List.of(), false)));

    assertThatThrownBy(() -> mapper.map(request(List.of(message)), DEFAULTS))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("assistant");
  }

  @Test
  void appliesTheAdapterDefaults() {
    OpenAiChatSettings settings =
        new OpenAiChatSettings("gpt-4.1-mini", 0.2, 256, Duration.ofSeconds(60));

    ChatCompletionCreateParams params =
        mapper.map(request(List.of(message(Role.USER, "hello"))), settings);

    assertThat(params.model().asString()).isEqualTo("gpt-4.1-mini");
    assertThat(params.temperature()).hasValue(0.2);
    assertThat(params.maxCompletionTokens()).hasValue(256L);
  }

  @Test
  void letsRequestOptionsOverrideTheAdapterDefaults() {
    // AGT-012 precedence, proved where it is implementable today. The engine cannot carry these
    // yet, which is why AGT-011 and AGT-012 stay partial, but the adapter side of the rule is real.
    OpenAiChatSettings settings =
        new OpenAiChatSettings("gpt-4.1-mini", 0.2, 256, Duration.ofSeconds(60));
    ModelRequestOptions options =
        ModelRequestOptions.builder().temperature(1.5).maxOutputTokens(64).build();

    ChatCompletionCreateParams params =
        mapper.map(
            new ModelRequest(
                List.of(message(Role.USER, "hello")),
                options,
                new CancellationSignal(),
                List.of(),
                Map.of()),
            settings);

    assertThat(params.temperature()).hasValue(1.5);
    assertThat(params.maxCompletionTokens()).hasValue(64L);
  }

  @Test
  void rejectsProviderOptionsUntilATypedSurfaceExists() {
    // AGT-011 says a provider-specific option handed to a provider that does not support it is not
    // silently ignored. This adapter supports none yet, so it says so instead of dropping them.
    ModelRequestOptions options =
        ModelRequestOptions.builder()
            .providerOption(ModelProviderOption.of("openai", Map.of("seed", 7)))
            .build();

    assertThatThrownBy(
            () ->
                mapper.map(
                    new ModelRequest(
                        List.of(message(Role.USER, "hello")),
                        options,
                        new CancellationSignal(),
                        List.of(),
                        Map.of()),
                    DEFAULTS))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("openai");
  }

  @Test
  void omitsToolsWhenTheRequestOffersNone() {
    // Sending `tools: []` is rejected by some OpenAI-compatible servers, and the last tool loop
    // iteration deliberately withholds tools, so this is the ordinary case rather than an edge one.
    ChatCompletionCreateParams params =
        mapper.map(request(List.of(message(Role.USER, "hello"))), DEFAULTS);

    assertThat(params.tools()).isEmpty();
  }

  private static Message message(Role role, String text) {
    return new Message(role, List.of(new TextContent(text)));
  }

  private static ModelRequest request(List<Message> messages) {
    return new ModelRequest(
        messages, ModelRequestOptions.empty(), new CancellationSignal(), List.of(), Map.of());
  }

  /** Content the framework has no type for, used to prove the mapper refuses rather than guesses. */
  private static final class SecretContent extends ExtensionContent {

    private SecretContent() {
      super(Map.of(), null);
    }

    @Override
    public String type() {
      return "test.secret";
    }

    @Override
    public String text() {
      return "sensitive payload";
    }
  }
}
```

- [ ] **Step 6: Run the mapping test to verify it fails**

Run: `./gradlew :providers:agent-framework-openai:test --tests 'io.github.hellices.agentframework.openai.internal.ChatCompletionRequestMapperTest'`

Expected: compilation FAILS with `cannot find symbol: class ChatCompletionRequestMapper`.

- [ ] **Step 7: Implement the mapper for roles, text, and options**

Create `ChatCompletionRequestMapper.java`. Leave the tool paths to Task 4; a request that carries a
`Role.TOOL` message or a tool definition may still fail here, because Task 4 adds those tests before
their code.

```java
package io.github.hellices.agentframework.openai.internal;

import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionDeveloperMessageParam;
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;
import io.github.hellices.agentframework.api.message.Content;
import io.github.hellices.agentframework.api.message.ExtensionContent;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.Role;
import io.github.hellices.agentframework.api.message.TextContent;
import io.github.hellices.agentframework.api.message.ToolCallContent;
import io.github.hellices.agentframework.api.message.ToolResultContent;
import io.github.hellices.agentframework.spi.model.ModelRequest;
import java.util.ArrayList;
import java.util.List;

/** Translates a neutral {@code ModelRequest} into Chat Completions request parameters. */
public final class ChatCompletionRequestMapper {

  private static final Role DEVELOPER = Role.of("developer");

  /**
   * Maps a request onto Chat Completions parameters.
   *
   * @param request the neutral request, never {@code null}
   * @param settings the adapter defaults, never {@code null}
   * @return parameters ready to send
   * @throws IllegalArgumentException if a role, a content placement, or a provider option cannot be
   *     represented
   * @throws UnsupportedOperationException if the request carries adapter-owned extension content
   */
  public ChatCompletionCreateParams map(ModelRequest request, OpenAiChatSettings settings) {
    ChatCompletionCreateParams.Builder params =
        ChatCompletionCreateParams.builder().model(settings.model());
    applyOptions(request, settings, params);
    for (Message message : request.messages()) {
      appendMessage(message, params);
    }
    return params.build();
  }

  private void applyOptions(
      ModelRequest request, OpenAiChatSettings settings, ChatCompletionCreateParams.Builder params) {
    if (!request.options().providerOptions().isEmpty()) {
      throw new IllegalArgumentException(
          "openai chat completions accepts no provider options yet, but the request carried options"
              + " for: "
              + request.options().providerOptions().keySet());
    }
    request
        .options()
        .temperature()
        .or(settings::temperature)
        .ifPresent(temperature -> params.temperature(temperature.doubleValue()));
    OptionalInt maxOutputTokens =
        request.options().maxOutputTokens().isPresent()
            ? request.options().maxOutputTokens()
            : settings.maxOutputTokens();
    if (maxOutputTokens.isPresent()) {
      params.maxCompletionTokens(maxOutputTokens.getAsInt());
    }
  }

  private void appendMessage(Message message, ChatCompletionCreateParams.Builder params) {
    Role role = message.role();
    if (Role.SYSTEM.equals(role)) {
      params.addMessage(
          ChatCompletionSystemMessageParam.builder().content(textOf(message)).build());
    } else if (DEVELOPER.equals(role)) {
      params.addMessage(
          ChatCompletionDeveloperMessageParam.builder().content(textOf(message)).build());
    } else if (Role.USER.equals(role)) {
      params.addMessage(ChatCompletionUserMessageParam.builder().content(textOf(message)).build());
    } else if (Role.ASSISTANT.equals(role)) {
      params.addMessage(ChatCompletionAssistantMessageParam.builder().content(textOf(message)).build());
    } else {
      throw new IllegalArgumentException(
          "openai chat completions cannot map role: " + role.value());
    }
  }

  /**
   * Joins the text parts of a message in order.
   *
   * <p>Explicit iteration rather than {@code Message.text()}, because that also concatenates the
   * empty text of a tool call and would silently accept content this adapter must reject.
   */
  private String textOf(Message message) {
    List<String> parts = new ArrayList<>();
    for (Content content : message.content()) {
      requireRepresentable(content, message.role());
      if (content instanceof TextContent text) {
        parts.add(text.value());
      }
    }
    return String.join("\n", parts);
  }

  private void requireRepresentable(Content content, Role role) {
    if (content instanceof ExtensionContent) {
      throw new UnsupportedOperationException(
          "openai chat completions cannot carry content type: " + content.type());
    }
    if (content instanceof ToolCallContent && !Role.ASSISTANT.equals(role)) {
      throw new IllegalArgumentException(
          "a tool call may only appear on an assistant message, but appeared on role: "
              + role.value());
    }
    if (content instanceof ToolResultContent && !Role.TOOL.equals(role)) {
      throw new IllegalArgumentException(
          "a tool result may only appear on a tool message, but appeared on role: " + role.value());
    }
  }
}
```

Import `java.util.OptionalInt`. Note that `Optional.or` needs a `Supplier`, so `settings::temperature`
fits directly.

- [ ] **Step 8: Run the mapping test again**

Run: `./gradlew :providers:agent-framework-openai:test --tests 'io.github.hellices.agentframework.openai.internal.ChatCompletionRequestMapperTest'`

Expected: PASS, 10 tests. `Role.TOOL` is deliberately not mapped yet: a tool message currently falls
into the unknown-role failure, and Task 4 replaces that with the real fan-out together with the test
that pins it. Do not add a placeholder tool branch here to make the code look finished.

- [ ] **Step 9: Commit**

```bash
git add providers/agent-framework-openai/src
git commit -m "$(cat <<'MSG'
openai: map roles, text, and request options

Adds the adapter settings value object and the request mapper for system,
developer, user, and assistant messages. Text parts join in order with newlines,
an unknown role and misplaced tool content fail explicitly, and extension content
fails naming its type without quoting its payload. Request options win over the
adapter defaults, and provider options are rejected rather than dropped because
no typed OpenAI option surface exists yet.

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
MSG
)"
```

---

### Task 4: Request mapping for tools, tool results, and the assistant echo

The half that makes the tool loop work on the wire. Three rules matter and each gets its own named
test: a JSON-schema tool definition maps with no schema library, one framework `Role.TOOL` message
holding N results becomes N OpenAI tool messages (G2), and an echoed assistant turn prefers the
original SDK object so the `arguments` string stays byte-identical to what the model produced.

**Files:**
- Modify: `providers/agent-framework-openai/src/main/java/io/github/hellices/agentframework/openai/internal/ChatCompletionRequestMapper.java`
- Test: `providers/agent-framework-openai/src/test/java/io/github/hellices/agentframework/openai/internal/ChatCompletionRequestMapperToolsTest.java`
- Modify: `providers/agent-framework-openai/src/test/java/io/github/hellices/agentframework/openai/internal/ChatCompletionRequestMapperTest.java`

**Interfaces:**
- Consumes: `ChatCompletionRequestMapper` and `OpenAiChatSettings` from Task 3.
- Produces: no new type and no new constructor. `ChatCompletionRequestMapper.map` gains tool,
  tool-result, and echo behaviour, and the class gains a private `ObjectMapper` field it creates
  itself. An earlier draft added a package-private constructor taking an `ObjectMapper` "so the
  reconstruction path can be exercised with a deterministic mapper"; no test in this plan uses it,
  a default `ObjectMapper` already serialises a `LinkedHashMap` deterministically in insertion
  order, and an unused seam is untested code that also gives SpotBugs a stored-collaborator finding
  to report. It is not added.

- [ ] **Step 1: Write the failing tool mapping test**

Create `providers/agent-framework-openai/src/test/java/io/github/hellices/agentframework/openai/internal/ChatCompletionRequestMapperToolsTest.java`:

```java
package io.github.hellices.agentframework.openai.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openai.core.JsonValue;
import com.openai.models.FunctionDefinition;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionMessageToolCall;
import io.github.hellices.agentframework.api.agent.CancellationSignal;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.Role;
import io.github.hellices.agentframework.api.message.TextContent;
import io.github.hellices.agentframework.api.message.ToolCallContent;
import io.github.hellices.agentframework.api.message.ToolResultContent;
import io.github.hellices.agentframework.api.tool.ToolDefinition;
import io.github.hellices.agentframework.spi.model.ModelRequest;
import io.github.hellices.agentframework.spi.model.ModelRequestOptions;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChatCompletionRequestMapperToolsTest {

  private static final OpenAiChatSettings DEFAULTS =
      new OpenAiChatSettings("gpt-4.1-mini", null, null, Duration.ofSeconds(60));

  private final ChatCompletionRequestMapper mapper = new ChatCompletionRequestMapper();

  @Test
  void mapsAToolDefinitionOntoAFunctionToolWithItsSchemaIntact() {
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "object");
    schema.put("properties", Map.of("city", Map.of("type", "string")));
    schema.put("required", List.of("city"));
    ToolDefinition tool = new ToolDefinition("lookup", "Looks a city up", schema);

    ChatCompletionCreateParams params =
        mapper.map(requestWithTools(List.of(tool)), DEFAULTS);

    FunctionDefinition function = params.tools().orElseThrow().get(0).asFunction().function();
    assertThat(function.name()).isEqualTo("lookup");
    assertThat(function.description()).hasValue("Looks a city up");
    assertThat(function.parameters().orElseThrow()._additionalProperties())
        .containsEntry("type", JsonValue.from("object"))
        .containsEntry("properties", JsonValue.from(Map.of("city", Map.of("type", "string"))))
        .containsEntry("required", JsonValue.from(List.of("city")));
  }

  @Test
  void omitsABlankDescriptionAndAnEmptySchema() {
    // ToolDefinition normalises a null description to "". Sending an empty description or an empty
    // parameters object says something different from saying nothing, so neither is sent.
    ToolDefinition tool = new ToolDefinition("ping", null, Map.of());

    ChatCompletionCreateParams params = mapper.map(requestWithTools(List.of(tool)), DEFAULTS);

    FunctionDefinition function = params.tools().orElseThrow().get(0).asFunction().function();
    assertThat(function.description()).isEmpty();
    assertThat(function.parameters()).isEmpty();
  }

  @Test
  void fansOneToolMessageOutIntoOneParamPerResult() {
    // The engine reports a whole round of tool results as one Role.TOOL message holding N
    // ToolResultContent, while Chat Completions requires one tool message per tool_call_id. Losing
    // this fan-out means the model sees a call it never got an answer for, which providers reject.
    Message toolMessage =
        new Message(
            Role.TOOL,
            List.of(
                new ToolResultContent(
                    "call_1", "lookup", List.of(new TextContent("sunny")), false),
                new ToolResultContent(
                    "call_2", "lookup", List.of(new TextContent("rainy")), false)));

    ChatCompletionCreateParams params = mapper.map(request(List.of(toolMessage)), DEFAULTS);

    List<ChatCompletionMessageParam> messages = params.messages();
    assertThat(messages).hasSize(2);
    assertThat(messages.get(0).asTool().toolCallId()).isEqualTo("call_1");
    assertThat(messages.get(0).asTool().content().asText()).isEqualTo("sunny");
    assertThat(messages.get(1).asTool().toolCallId()).isEqualTo("call_2");
    assertThat(messages.get(1).asTool().content().asText()).isEqualTo("rainy");
  }

  @Test
  void carriesAFailedToolResultAsItsText() {
    // Chat Completions has no error flag on a tool message. The text still reaches the model, and
    // the limitation is documented rather than papered over with an invented prefix.
    Message toolMessage =
        new Message(
            Role.TOOL,
            List.of(
                new ToolResultContent(
                    "call_1", "lookup", List.of(new TextContent("lookup failed")), true)));

    ChatCompletionCreateParams params = mapper.map(request(List.of(toolMessage)), DEFAULTS);

    assertThat(params.messages().get(0).asTool().content().asText()).isEqualTo("lookup failed");
  }

  @Test
  void rejectsNonToolResultContentInsideAToolMessage() {
    Message toolMessage = new Message(Role.TOOL, List.of(new TextContent("loose text")));

    assertThatThrownBy(() -> mapper.map(request(List.of(toolMessage)), DEFAULTS))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("text");
  }

  @Test
  void echoesTheOriginalSdkAssistantMessageWhenOneIsAvailable() {
    // The arguments string the model produced must go back byte-identical. Re-serialising a parsed
    // map changes key order and number formatting, and some models are sensitive to that.
    ChatCompletionMessage sdkMessage =
        ChatCompletionMessage.builder()
            .content((String) null)
            .refusal((String) null)
            .addToolCall(
                ChatCompletionMessageToolCall.ofFunction(
                    ChatCompletionMessageFunctionToolCall.builder()
                        .id("call_1")
                        .function(
                            ChatCompletionMessageFunctionToolCall.Function.builder()
                                .name("lookup")
                                .arguments("{\"city\": \"Seoul\"}")
                                .build())
                        .build()))
            .build();
    Message assistant =
        new Message(
            Role.ASSISTANT,
            List.of(new ToolCallContent("call_1", "lookup", Map.of("city", "Seoul"))),
            null,
            Map.of(),
            sdkMessage);

    ChatCompletionCreateParams params = mapper.map(request(List.of(assistant)), DEFAULTS);

    ChatCompletionMessageToolCall echoed =
        params.messages().get(0).asAssistant().toolCalls().orElseThrow().get(0);
    assertThat(echoed.asFunction().function().arguments()).isEqualTo("{\"city\": \"Seoul\"}");
  }

  @Test
  void reconstructsTheAssistantEchoWhenNoSdkMessageIsAvailable() {
    // A caller-built history, a restored session, or a decorated response has no SDK object. The
    // echo must still be a legal assistant turn rather than a dropped tool call.
    Message assistant =
        new Message(
            Role.ASSISTANT,
            List.of(
                new TextContent("looking it up"),
                new ToolCallContent("call_1", "lookup", Map.of("city", "Seoul"))));

    ChatCompletionCreateParams params = mapper.map(request(List.of(assistant)), DEFAULTS);

    var echoed = params.messages().get(0).asAssistant();
    assertThat(echoed.content().orElseThrow().asText()).isEqualTo("looking it up");
    ChatCompletionMessageToolCall call = echoed.toolCalls().orElseThrow().get(0);
    assertThat(call.asFunction().id()).isEqualTo("call_1");
    assertThat(call.asFunction().function().name()).isEqualTo("lookup");
    assertThat(call.asFunction().function().arguments()).isEqualTo("{\"city\":\"Seoul\"}");
  }

  @Test
  void omitsAssistantContentWhenTheTurnIsOnlyToolCalls() {
    Message assistant =
        new Message(
            Role.ASSISTANT, List.of(new ToolCallContent("call_1", "lookup", Map.of())));

    ChatCompletionCreateParams params = mapper.map(request(List.of(assistant)), DEFAULTS);

    assertThat(params.messages().get(0).asAssistant().content()).isEmpty();
  }

  private static ModelRequest request(List<Message> messages) {
    return new ModelRequest(
        messages, ModelRequestOptions.empty(), new CancellationSignal(), List.of(), Map.of());
  }

  private static ModelRequest requestWithTools(List<ToolDefinition> tools) {
    return new ModelRequest(
        List.of(new Message(Role.USER, List.of(new TextContent("hello")))),
        ModelRequestOptions.empty(),
        new CancellationSignal(),
        tools,
        Map.of());
  }
}
```

Then delete `rejectsNonToolResultContentInsideAToolMessage` from
`ChatCompletionRequestMapperTest.java` if it is still there; it belongs here, where the real failure
message exists rather than the accidental unknown-role one.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :providers:agent-framework-openai:test --tests 'io.github.hellices.agentframework.openai.internal.ChatCompletionRequestMapperToolsTest'`

Expected: FAILS. `mapsAToolDefinitionOntoAFunctionToolWithItsSchemaIntact` fails on an empty
`tools()`, the tool-message tests fail with the unknown-role message naming `tool`, and the echo
tests fail because the assistant branch sets content unconditionally and drops tool calls.

- [ ] **Step 3: Implement tools, the tool-result fan-out, and the echo**

Add these imports to `ChatCompletionRequestMapper`; none of them is in the Task 3 import list, and
every one of them is used by the code below:

```java
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.core.JsonValue;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.chat.completions.ChatCompletionFunctionTool;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;
import io.github.hellices.agentframework.api.tool.ToolDefinition;
import java.util.Map;
```

Then add the JSON writer the reconstruction path needs:

```java
  private final ObjectMapper json = new ObjectMapper();
```

Call `applyTools(request, params)` from `map` after `applyOptions`, then change the two branches of
`appendMessage` so the assistant and tool roles delegate:

```java
    } else if (Role.ASSISTANT.equals(role)) {
      appendAssistantMessage(message, params);
    } else if (Role.TOOL.equals(role)) {
      appendToolMessages(message, params);
    } else {
      throw new IllegalArgumentException(
          "openai chat completions cannot map role: " + role.value());
    }
```

and add the new methods:

```java
  private void applyTools(ModelRequest request, ChatCompletionCreateParams.Builder params) {
    for (ToolDefinition tool : request.tools()) {
      FunctionDefinition.Builder function = FunctionDefinition.builder().name(tool.name());
      if (!tool.description().isBlank()) {
        function.description(tool.description());
      }
      Map<String, Object> schema = tool.inputSchema();
      if (!schema.isEmpty()) {
        FunctionParameters.Builder parameters = FunctionParameters.builder();
        schema.forEach((key, value) -> parameters.putAdditionalProperty(key, JsonValue.from(value)));
        function.parameters(parameters.build());
      }
      params.addTool(ChatCompletionFunctionTool.builder().function(function.build()).build());
    }
  }

  private void appendAssistantMessage(Message message, ChatCompletionCreateParams.Builder params) {
    String text = textOf(message);
    if (message.rawRepresentation() instanceof ChatCompletionMessage sdkMessage) {
      // The SDK object still carries the exact arguments string the model produced, which
      // re-serialising a parsed map cannot reproduce.
      params.addMessage(sdkMessage);
      return;
    }
    ChatCompletionAssistantMessageParam.Builder assistant =
        ChatCompletionAssistantMessageParam.builder();
    if (!text.isEmpty()) {
      assistant.content(text);
    }
    for (Content content : message.content()) {
      if (content instanceof ToolCallContent call) {
        assistant.addToolCall(
            ChatCompletionMessageFunctionToolCall.builder()
                .id(call.callId())
                .function(
                    ChatCompletionMessageFunctionToolCall.Function.builder()
                        .name(call.name())
                        .arguments(serializeArguments(call))
                        .build())
                .build());
      }
    }
    params.addMessage(assistant.build());
  }

  private void appendToolMessages(Message message, ChatCompletionCreateParams.Builder params) {
    // One framework tool message holds every result of one round; Chat Completions wants one
    // message per tool_call_id. Dropping the fan-out leaves a call without a result.
    for (Content content : message.content()) {
      if (!(content instanceof ToolResultContent result)) {
        throw new IllegalArgumentException(
            "a tool message may only carry tool results, but carried content type: "
                + content.type());
      }
      params.addMessage(
          ChatCompletionToolMessageParam.builder()
              .toolCallId(result.callId())
              .content(resultText(result))
              .build());
    }
  }

  private String resultText(ToolResultContent result) {
    List<String> parts = new ArrayList<>();
    for (Content content : result.content()) {
      if (content instanceof ExtensionContent) {
        throw new UnsupportedOperationException(
            "openai chat completions cannot carry content type: " + content.type());
      }
      if (!(content instanceof TextContent text)) {
        throw new IllegalArgumentException(
            "a tool result may only carry text, but carried content type: " + content.type());
      }
      parts.add(text.value());
    }
    return String.join("\n", parts);
  }

  private String serializeArguments(ToolCallContent call) {
    try {
      return json.writeValueAsString(call.arguments());
    } catch (JsonProcessingException failure) {
      throw new IllegalArgumentException(
          "openai chat completions cannot serialise the arguments of tool '"
              + call.name()
              + "' call '"
              + call.callId()
              + "'",
          failure);
    }
  }
```

The failure message names the tool and the call id and stops there. Do not append the arguments.

- [ ] **Step 4: Run both mapper tests**

Run: `./gradlew :providers:agent-framework-openai:test --tests 'io.github.hellices.agentframework.openai.internal.ChatCompletionRequestMapper*'`

Expected: PASS, 18 tests (10 in `ChatCompletionRequestMapperTest`, 8 in
`ChatCompletionRequestMapperToolsTest`).

- [ ] **Step 5: Run the module quality gate**

Run: `./gradlew :providers:agent-framework-openai:quality`

Expected: PASS. This runs here, and again in Tasks 6 and 8, so a static-analysis finding is met next
to the code that caused it instead of arriving as one batch at the end, where the cheapest response
is a broad suppression.

`ChatCompletionRequestMapper` stores no caller-supplied collaborator — it creates its own
`ObjectMapper` — so no `EI_EXPOSE_REP2` finding is expected here. If spotless reports formatting,
run `./gradlew spotlessApply` and re-run. If SpotBugs or PMD reports something else, fix the code;
add an exclusion only for a finding an unfiltered run actually produced, as one `Match` naming the
exact class and method with a comment saying why the reference is the design.

- [ ] **Step 6: Commit**

```bash
git add providers/agent-framework-openai/src
git commit -m "$(cat <<'MSG'
openai: map tools, tool results, and the assistant echo

A tool definition maps onto a function tool with its JSON schema intact and no
schema library, a blank description and an empty schema are omitted rather than
sent empty, and one framework tool message holding several results fans out into
one OpenAI tool message per tool_call_id. An echoed assistant turn prefers the
original SDK message so the arguments string stays byte-identical, and falls back
to reconstruction for a caller-built or restored history.

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
MSG
)"
```

---

### Task 5: Response mapping for text, finish reasons, usage, and metadata

Turn a `ChatCompletion` into a `ModelResponse`. Tool calls come in Task 6; this task covers the
single-choice rule, text, the whole finish-reason table including the deprecated `function_call`
value, usage, metadata, the raw representation on both the response and the message, and the
continuation token that is always null.

**Files:**
- Create: `providers/agent-framework-openai/src/main/java/io/github/hellices/agentframework/openai/internal/ChatCompletionResponseMapper.java`
- Test: `providers/agent-framework-openai/src/test/java/io/github/hellices/agentframework/openai/internal/ChatCompletionsFixture.java`
- Test: `providers/agent-framework-openai/src/test/java/io/github/hellices/agentframework/openai/internal/ChatCompletionResponseMapperTest.java`

**Interfaces:**
- Consumes: nothing from Tasks 3 and 4; the two mappers are independent.
- Produces, public in `io.github.hellices.agentframework.openai.internal`:
  - `ChatCompletionResponseMapper()` and `ModelResponse map(ChatCompletion completion)`
- Produces, package-private test fixture `ChatCompletionsFixture`:
  - `static ChatCompletion completion(ChatCompletionMessage message, ChatCompletion.Choice.FinishReason finishReason)`
  - `static ChatCompletion completion(ChatCompletionMessage message, ChatCompletion.Choice.FinishReason finishReason, CompletionUsage usage)`
  - `static ChatCompletion twoChoices()`
  - `static ChatCompletionMessage text(String content)`
  - `static ChatCompletionMessage withToolCalls(String content, ChatCompletionMessageToolCall... calls)`
  - `static ChatCompletionMessageToolCall functionCall(String id, String name, String arguments)`

- [ ] **Step 1: Write the fixture**

Create `ChatCompletionsFixture.java`. Fact 16 is the reason this exists: the required-field rules of
the SDK builders are non-obvious, and repeating them in every test invites one test to drift.

```java
package io.github.hellices.agentframework.openai.internal;

import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.chat.completions.ChatCompletionMessageToolCall;
import com.openai.models.completions.CompletionUsage;
import java.util.List;

/**
 * Hand-built Chat Completions responses for the response mapping tests.
 *
 * <p>The SDK builders require fields that are easy to miss: a message requires both content and
 * refusal even when both are JSON null, and a choice requires a finish reason, an index, logprobs,
 * and a message. Centralising that here keeps a mapping test about mapping.
 */
final class ChatCompletionsFixture {

  private ChatCompletionsFixture() {}

  static ChatCompletion completion(
      ChatCompletionMessage message, ChatCompletion.Choice.FinishReason finishReason) {
    return base(message, finishReason).build();
  }

  static ChatCompletion completion(
      ChatCompletionMessage message,
      ChatCompletion.Choice.FinishReason finishReason,
      CompletionUsage usage) {
    return base(message, finishReason).usage(usage).build();
  }

  static ChatCompletion twoChoices() {
    return ChatCompletion.builder()
        .id("chatcmpl-test")
        .created(1_700_000_000L)
        .model("gpt-4.1-mini")
        .addChoice(choice(text("first"), ChatCompletion.Choice.FinishReason.STOP, 0L))
        .addChoice(choice(text("second"), ChatCompletion.Choice.FinishReason.STOP, 1L))
        .build();
  }

  static ChatCompletionMessage text(String content) {
    return ChatCompletionMessage.builder().content(content).refusal((String) null).build();
  }

  static ChatCompletionMessage withToolCalls(
      String content, ChatCompletionMessageToolCall... calls) {
    ChatCompletionMessage.Builder message =
        ChatCompletionMessage.builder().content(content).refusal((String) null);
    for (ChatCompletionMessageToolCall call : calls) {
      message.addToolCall(call);
    }
    return message.build();
  }

  static ChatCompletionMessageToolCall functionCall(String id, String name, String arguments) {
    return ChatCompletionMessageToolCall.ofFunction(
        ChatCompletionMessageFunctionToolCall.builder()
            .id(id)
            .function(
                ChatCompletionMessageFunctionToolCall.Function.builder()
                    .name(name)
                    .arguments(arguments)
                    .build())
            .build());
  }

  static CompletionUsage usage(long promptTokens, long completionTokens, long totalTokens) {
    return CompletionUsage.builder()
        .promptTokens(promptTokens)
        .completionTokens(completionTokens)
        .totalTokens(totalTokens)
        .build();
  }

  private static ChatCompletion.Builder base(
      ChatCompletionMessage message, ChatCompletion.Choice.FinishReason finishReason) {
    return ChatCompletion.builder()
        .id("chatcmpl-test")
        .created(1_700_000_000L)
        .model("gpt-4.1-mini")
        .choices(List.of(choice(message, finishReason, 0L)));
  }

  private static ChatCompletion.Choice choice(
      ChatCompletionMessage message, ChatCompletion.Choice.FinishReason finishReason, long index) {
    return ChatCompletion.Choice.builder()
        .finishReason(finishReason)
        .index(index)
        .logprobs((ChatCompletion.Choice.Logprobs) null)
        .message(message)
        .build();
  }
}
```

- [ ] **Step 2: Write the failing response mapping test**

Create `ChatCompletionResponseMapperTest.java`:

```java
package io.github.hellices.agentframework.openai.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionMessage;
import io.github.hellices.agentframework.api.message.FinishReason;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.Role;
import io.github.hellices.agentframework.api.message.TextContent;
import io.github.hellices.agentframework.api.message.Usage;
import io.github.hellices.agentframework.spi.model.ModelResponse;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ChatCompletionResponseMapperTest {

  private final ChatCompletionResponseMapper mapper = new ChatCompletionResponseMapper();

  @Test
  void mapsATextOnlyCompletion() {
    ChatCompletionMessage message = ChatCompletionsFixture.text("hello there");
    ChatCompletion completion =
        ChatCompletionsFixture.completion(message, ChatCompletion.Choice.FinishReason.STOP);

    ModelResponse response = mapper.map(completion);

    assertThat(response.messages()).hasSize(1);
    Message mapped = response.messages().get(0);
    assertThat(mapped.role()).isEqualTo(Role.ASSISTANT);
    assertThat(mapped.content()).singleElement().isInstanceOf(TextContent.class);
    assertThat(mapped.text()).isEqualTo("hello there");
    assertThat(mapped.rawRepresentation()).isSameAs(message);
    assertThat(response.finishReason()).isEqualTo(FinishReason.STOP);
  }

  @Test
  void contributesNoTextContentWhenTheContentIsBlank() {
    // An empty TextContent is not the same statement as no content, and the engine concatenates
    // message text, so an empty part would silently change nothing while claiming the model spoke.
    ChatCompletion completion =
        ChatCompletionsFixture.completion(
            ChatCompletionsFixture.text("   "), ChatCompletion.Choice.FinishReason.STOP);

    assertThat(mapper.map(completion).messages()).isEmpty();
  }

  @Test
  void mapsUsageFromTheCompletion() {
    ChatCompletion completion =
        ChatCompletionsFixture.completion(
            ChatCompletionsFixture.text("hi"),
            ChatCompletion.Choice.FinishReason.STOP,
            ChatCompletionsFixture.usage(11L, 5L, 16L));

    assertThat(mapper.map(completion).usage()).isEqualTo(new Usage(11L, 5L, 16L));
  }

  @Test
  void leavesUsageNullWhenTheCompletionOmitsIt() {
    // ModelResponse allows a null usage and AgentEngine.combineUsage tolerates it, so a compatible
    // server that reports no usage still completes the run.
    ChatCompletion completion =
        ChatCompletionsFixture.completion(
            ChatCompletionsFixture.text("hi"), ChatCompletion.Choice.FinishReason.STOP);

    assertThat(mapper.map(completion).usage()).isNull();
  }

  @ParameterizedTest(name = "{0} maps to {1}")
  @MethodSource("finishReasons")
  void mapsEveryFinishReason(
      ChatCompletion.Choice.FinishReason wireValue, FinishReason expected) {
    ChatCompletion completion =
        ChatCompletionsFixture.completion(ChatCompletionsFixture.text("hi"), wireValue);

    assertThat(mapper.map(completion).finishReason()).isEqualTo(expected);
  }

  static Stream<Arguments> finishReasons() {
    return Stream.of(
        Arguments.of(ChatCompletion.Choice.FinishReason.STOP, FinishReason.STOP),
        Arguments.of(ChatCompletion.Choice.FinishReason.LENGTH, FinishReason.LENGTH),
        Arguments.of(ChatCompletion.Choice.FinishReason.TOOL_CALLS, FinishReason.TOOL_CALLS),
        Arguments.of(
            ChatCompletion.Choice.FinishReason.CONTENT_FILTER, FinishReason.CONTENT_FILTER),
        // The deprecated wire value still means the model asked for a call, so it is mapped
        // deliberately rather than falling through to UNKNOWN.
        Arguments.of(ChatCompletion.Choice.FinishReason.FUNCTION_CALL, FinishReason.TOOL_CALLS),
        // A value the pinned SDK has never seen must not throw: known() would, value() does not.
        Arguments.of(ChatCompletion.Choice.FinishReason.of("moon_phase"), FinishReason.UNKNOWN));
  }

  @Test
  void carriesResponseIdentityInMetadataAndTheCompletionAsTheRawRepresentation() {
    ChatCompletion completion =
        ChatCompletionsFixture.completion(
            ChatCompletionsFixture.text("hi"), ChatCompletion.Choice.FinishReason.STOP);

    ModelResponse response = mapper.map(completion);

    assertThat(response.metadata())
        .containsEntry("openai.response.id", "chatcmpl-test")
        .containsEntry("openai.response.model", "gpt-4.1-mini")
        .containsEntry("openai.response.created", 1_700_000_000L);
    assertThat(response.rawRepresentation()).isSameAs(completion);
  }

  @Test
  void leavesTheContinuationTokenNull() {
    // Chat Completions is stateless, and ToolLoopPolicy.validateContinuation rejects a token
    // whenever tools are configured, so inventing one would break the tool loop.
    ChatCompletion completion =
        ChatCompletionsFixture.completion(
            ChatCompletionsFixture.text("hi"), ChatCompletion.Choice.FinishReason.STOP);

    assertThat(mapper.map(completion).continuationToken()).isNull();
  }

  @Test
  void rejectsACompletionWithMoreThanOneChoice() {
    // This slice never sends `n`, so more than one choice means the server did something the
    // adapter does not model. Taking choices[0] would silently discard an answer.
    assertThatThrownBy(() -> mapper.map(ChatCompletionsFixture.twoChoices()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("2");
  }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./gradlew :providers:agent-framework-openai:test --tests 'io.github.hellices.agentframework.openai.internal.ChatCompletionResponseMapperTest'`

Expected: compilation FAILS with `cannot find symbol: class ChatCompletionResponseMapper`.

- [ ] **Step 4: Implement the response mapper**

```java
package io.github.hellices.agentframework.openai.internal;

import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.models.completions.CompletionUsage;
import io.github.hellices.agentframework.api.message.Content;
import io.github.hellices.agentframework.api.message.FinishReason;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.Role;
import io.github.hellices.agentframework.api.message.TextContent;
import io.github.hellices.agentframework.api.message.Usage;
import io.github.hellices.agentframework.spi.model.ModelResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Translates a Chat Completions response into a neutral {@code ModelResponse}. */
public final class ChatCompletionResponseMapper {

  /**
   * Maps a completion.
   *
   * @param completion the parsed response, never {@code null}
   * @return the neutral response
   * @throws IllegalStateException if the completion carries anything this adapter cannot represent
   */
  public ModelResponse map(ChatCompletion completion) {
    List<ChatCompletion.Choice> choices = completion.choices();
    if (choices.size() != 1) {
      throw new IllegalStateException(
          "openai chat completions returned "
              + choices.size()
              + " choices; exactly one is supported");
    }
    ChatCompletion.Choice choice = choices.get(0);
    ChatCompletionMessage message = choice.message();
    List<Content> content = new ArrayList<>();
    message
        .content()
        .filter(text -> !text.isBlank())
        .ifPresent(text -> content.add(new TextContent(text)));
    appendToolCalls(message, content);
    List<Message> messages =
        content.isEmpty()
            ? List.of()
            : List.of(new Message(Role.ASSISTANT, content, null, Map.of(), message));
    return new ModelResponse(
        messages,
        usageOf(completion),
        finishReasonOf(choice),
        null,
        metadataOf(completion),
        completion);
  }

  private static FinishReason finishReasonOf(ChatCompletion.Choice choice) {
    // value() rather than known(): known() throws for a reason the pinned SDK has never seen, and a
    // new wire value is a routine event, not a failure.
    return switch (choice.finishReason().value()) {
      case STOP -> FinishReason.STOP;
      case LENGTH -> FinishReason.LENGTH;
      case TOOL_CALLS, FUNCTION_CALL -> FinishReason.TOOL_CALLS;
      case CONTENT_FILTER -> FinishReason.CONTENT_FILTER;
      default -> FinishReason.UNKNOWN;
    };
  }

  private static Usage usageOf(ChatCompletion completion) {
    // The typed accessors throw when a field is absent on the wire. That is deliberate: a partial
    // usage object is a server contract violation, and inventing a zero would corrupt accounting.
    return completion
        .usage()
        .map(
            (CompletionUsage usage) ->
                new Usage(usage.promptTokens(), usage.completionTokens(), usage.totalTokens()))
        .orElse(null);
  }

  private static Map<String, Object> metadataOf(ChatCompletion completion) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("openai.response.id", completion.id());
    metadata.put("openai.response.model", completion.model());
    metadata.put("openai.response.created", completion.created());
    return Map.copyOf(metadata);
  }
}
```

`appendToolCalls` is a no-op stub in this task and gets its real body, and its tests, in Task 6:

```java
  private void appendToolCalls(ChatCompletionMessage message, List<Content> content) {
    // Task 6.
  }
```

Do not leave this stub in the branch beyond Task 6, and do not run
`:providers:agent-framework-openai:quality` while it exists: PMD's `UnusedFormalParameter` rule
inspects private methods, and this stub uses neither parameter. That is why the module quality gate
sits in Task 6, once the body is real, rather than here. It is also why the stub is bounded to one
task instead of being a convenient placeholder.

- [ ] **Step 5: Run the test again**

Run: `./gradlew :providers:agent-framework-openai:test --tests 'io.github.hellices.agentframework.openai.internal.ChatCompletionResponseMapperTest'`

Expected: PASS, 13 tests: 7 plain cases plus the 6 rows of the parameterized finish-reason table,
which JUnit counts individually. Confirm the exact number from the XML report rather than from this
sentence.

- [ ] **Step 6: Commit**

```bash
git add providers/agent-framework-openai/src
git commit -m "$(cat <<'MSG'
openai: map text responses, finish reasons, usage, and metadata

A completion becomes one assistant message carrying the SDK message as its raw
representation, blank content contributes nothing rather than an empty text part,
and the whole finish-reason table maps explicitly, including the deprecated
function_call value and an unknown value read through value() so a new wire
member never throws. Usage is mapped when present and left null when absent, the
response id, model, and created time land in metadata, the continuation token is
always null, and more than one choice fails rather than silently taking the first.

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
MSG
)"
```

---

### Task 6: Response mapping for tool calls and argument parsing

The other half of the round trip. A function tool call becomes a `ToolCallContent` with parsed
arguments, and every shape the neutral contract cannot represent fails by name instead of being
coerced.

**Files:**
- Modify: `providers/agent-framework-openai/src/main/java/io/github/hellices/agentframework/openai/internal/ChatCompletionResponseMapper.java`
- Test: `providers/agent-framework-openai/src/test/java/io/github/hellices/agentframework/openai/internal/ChatCompletionResponseMapperToolCallTest.java`

**Interfaces:**
- Consumes: `ChatCompletionResponseMapper` and `ChatCompletionsFixture` from Task 5.
- Produces: no new constructor. `ChatCompletionResponseMapper()` keeps its no-argument form and the
  class gains a private `ObjectMapper` field it creates itself. As in Task 4, the package-private
  `ChatCompletionResponseMapper(ObjectMapper)` seam an earlier draft proposed is not added: no test
  in this plan uses it, and an unused seam is untested code. The mapper it creates is configured
  rather than defaulted, because Jackson's defaults for this input are wrong in two ways that a
  caller cannot see (`readTree` stops after the first value, and duplicate keys resolve last-wins);
  the field below states each one, plus a third setting that keeps a parser message from quoting the
  source it failed on. Configuration is not enough on its own: Jackson also names the rejected token
  in its own message text, so no parser exception is attached to the adapter's failure at all.

- [ ] **Step 1: Write the failing tool-call test**

Create `ChatCompletionResponseMapperToolCallTest.java`:

```java
package io.github.hellices.agentframework.openai.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionMessageCustomToolCall;
import com.openai.models.chat.completions.ChatCompletionMessageToolCall;
import io.github.hellices.agentframework.api.message.Content;
import io.github.hellices.agentframework.api.message.FinishReason;
import io.github.hellices.agentframework.api.message.TextContent;
import io.github.hellices.agentframework.api.message.ToolCallContent;
import io.github.hellices.agentframework.spi.model.ModelResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChatCompletionResponseMapperToolCallTest {

  private final ChatCompletionResponseMapper mapper = new ChatCompletionResponseMapper();

  @Test
  void mapsFunctionToolCallsInOrderAfterTheText() {
    ChatCompletion completion =
        ChatCompletionsFixture.completion(
            ChatCompletionsFixture.withToolCalls(
                "looking it up",
                ChatCompletionsFixture.functionCall("call_1", "lookup", "{\"city\":\"Seoul\"}"),
                ChatCompletionsFixture.functionCall("call_2", "clock", "{\"zone\":\"KST\"}")),
            ChatCompletion.Choice.FinishReason.TOOL_CALLS);

    ModelResponse response = mapper.map(completion);

    List<Content> content = response.messages().get(0).content();
    assertThat(content).hasSize(3);
    assertThat(content.get(0)).isInstanceOf(TextContent.class);
    ToolCallContent first = (ToolCallContent) content.get(1);
    assertThat(first.callId()).isEqualTo("call_1");
    assertThat(first.name()).isEqualTo("lookup");
    assertThat(first.arguments()).isEqualTo(Map.of("city", "Seoul"));
    assertThat(((ToolCallContent) content.get(2)).callId()).isEqualTo("call_2");
    assertThat(response.finishReason()).isEqualTo(FinishReason.TOOL_CALLS);
  }

  @Test
  void mapsEmptyArgumentsToAnEmptyMap() {
    // OpenAI emits "" or "{}" for a zero-argument tool. Both mean the same thing, and neither is a
    // parse failure.
    ChatCompletion empty =
        ChatCompletionsFixture.completion(
            ChatCompletionsFixture.withToolCalls(
                null, ChatCompletionsFixture.functionCall("call_1", "ping", "")),
            ChatCompletion.Choice.FinishReason.TOOL_CALLS);
    ChatCompletion object =
        ChatCompletionsFixture.completion(
            ChatCompletionsFixture.withToolCalls(
                null, ChatCompletionsFixture.functionCall("call_1", "ping", "{}")),
            ChatCompletion.Choice.FinishReason.TOOL_CALLS);

    assertThat(toolCall(mapper.map(empty)).arguments()).isEmpty();
    assertThat(toolCall(mapper.map(object)).arguments()).isEmpty();
  }

  @Test
  void keepsAnArgumentWhoseJsonValueIsNull() {
    // Models routinely send null for an optional parameter: {"unit":null} means "I did not choose a
    // unit", which is not the same as omitting the key. Map.copyOf rejects a null value with a bare
    // NullPointerException, so a perfectly ordinary tool call would crash the loop with an
    // unexplained failure. ToolCallContent copies into a LinkedHashMap and keeps the null.
    ChatCompletion completion =
        ChatCompletionsFixture.completion(
            ChatCompletionsFixture.withToolCalls(
                null,
                ChatCompletionsFixture.functionCall(
                    "call_1", "lookup", "{\"unit\":null,\"city\":\"Seoul\"}")),
            ChatCompletion.Choice.FinishReason.TOOL_CALLS);

    Map<String, Object> arguments = toolCall(mapper.map(completion)).arguments();

    assertThat(arguments).hasSize(2).containsKey("unit").containsEntry("city", "Seoul");
    assertThat(arguments.get("unit")).isNull();
  }

  @Test
  void rejectsArgumentsThatAreNotAJsonObject() {
    // ToolCallContent.arguments is a Map, so a JSON array or scalar cannot round-trip. Failing here
    // is honest; coercing would invent a key the model never sent.
    ChatCompletion completion =
        ChatCompletionsFixture.completion(
            ChatCompletionsFixture.withToolCalls(
                null, ChatCompletionsFixture.functionCall("call_1", "lookup", "[1,2]")),
            ChatCompletion.Choice.FinishReason.TOOL_CALLS);

    assertThatThrownBy(() -> mapper.map(completion))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("lookup")
        .hasMessageContaining("call_1")
        .hasMessageNotContaining("[1,2]");
  }

  @Test
  void rejectsArgumentsThatAreNotValidJson() {
    ChatCompletion completion =
        ChatCompletionsFixture.completion(
            ChatCompletionsFixture.withToolCalls(
                null, ChatCompletionsFixture.functionCall("call_1", "lookup", "{oops")),
            ChatCompletion.Choice.FinishReason.TOOL_CALLS);

    assertThatThrownBy(() -> mapper.map(completion))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("call_1")
        .hasMessageNotContaining("oops")
        .hasNoCause();
  }

  @Test
  void rejectsAToolCallWithoutAnId() {
    // ToolCallContent rejects a blank call id, so without this check the failure would surface as
    // an unexplained IllegalArgumentException from the core value type. Never synthesise an id: the
    // tool result has to be keyed by the id the model actually issued.
    ChatCompletion completion =
        ChatCompletionsFixture.completion(
            ChatCompletionsFixture.withToolCalls(
                null, ChatCompletionsFixture.functionCall("", "lookup", "{}")),
            ChatCompletion.Choice.FinishReason.TOOL_CALLS);

    assertThatThrownBy(() -> mapper.map(completion))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("id");
  }

  @Test
  void rejectsAToolCallWithoutAName() {
    ChatCompletion completion =
        ChatCompletionsFixture.completion(
            ChatCompletionsFixture.withToolCalls(
                null, ChatCompletionsFixture.functionCall("call_1", "", "{}")),
            ChatCompletion.Choice.FinishReason.TOOL_CALLS);

    assertThatThrownBy(() -> mapper.map(completion))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("call_1");
  }

  @Test
  void rejectsAToolCallThatIsNotAFunctionCall() {
    ChatCompletionMessageToolCall custom =
        ChatCompletionMessageToolCall.ofCustom(
            ChatCompletionMessageCustomToolCall.builder()
                .id("call_9")
                .custom(
                    ChatCompletionMessageCustomToolCall.Custom.builder()
                        .name("run")
                        .input("ls")
                        .build())
                .build());
    ChatCompletion completion =
        ChatCompletionsFixture.completion(
            ChatCompletionsFixture.withToolCalls(null, custom),
            ChatCompletion.Choice.FinishReason.TOOL_CALLS);

    assertThatThrownBy(() -> mapper.map(completion))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("function");
  }

  private static ToolCallContent toolCall(ModelResponse response) {
    return (ToolCallContent) response.messages().get(0).content().get(0);
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :providers:agent-framework-openai:test --tests 'io.github.hellices.agentframework.openai.internal.ChatCompletionResponseMapperToolCallTest'`

Expected: FAILS, 8 tests. Every case fails on an empty content list, because `appendToolCalls` is
still the Task 5 stub.

`keepsAnArgumentWhoseJsonValueIsNull` must stay red for a second reason as well: it also fails
against the obvious implementation. `Map.copyOf(values)` throws `NullPointerException: null` with no
message naming the tool, the call, or the key, which is exactly the unexplained failure the failure
contract forbids. Do not write that line and then delete this test.

- [ ] **Step 3: Implement tool-call mapping**

Add these imports; none is in the Task 5 import list:

```java
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.chat.completions.ChatCompletionMessageToolCall;
import io.github.hellices.agentframework.api.message.ToolCallContent;
import java.util.Collections;
```

`java.util.LinkedHashMap` and `java.util.Map` are already imported by Task 5.

Replace the stub:

```java
  // Tool arguments are model output parsed into a Map a tool executor acts on, so the parser is
  // configured rather than defaulted: FAIL_ON_TRAILING_TOKENS because readTree otherwise keeps
  // `{"a":1}` out of `{"a":1}{"b":2}` and drops the rest, STRICT_DUPLICATE_DETECTION because
  // last-wins silently picks one of two values the model sent, and INCLUDE_SOURCE_IN_LOCATION
  // disabled so a parser exception cannot quote the arguments string into a log. That last one is
  // the second layer only: the exception below carries no cause, because Jackson names the token
  // it rejected in its own message text, which no source-location setting redacts.
  private final ObjectMapper json =
      JsonMapper.builder()
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
          .build();

  private void appendToolCalls(ChatCompletionMessage message, List<Content> content) {
    for (ChatCompletionMessageToolCall toolCall : message.toolCalls().orElse(List.of())) {
      if (!toolCall.isFunction()) {
        throw new IllegalStateException(
            "openai chat completions returned a tool call that is not a function call");
      }
      ChatCompletionMessageFunctionToolCall call = toolCall.asFunction();
      String callId = call.id();
      if (callId.isBlank()) {
        throw new IllegalStateException(
            "openai chat completions returned a tool call without an id");
      }
      String name = call.function().name();
      if (name.isBlank()) {
        throw new IllegalStateException(
            "openai chat completions returned a tool call without a name for call '"
                + callId
                + "'");
      }
      content.add(new ToolCallContent(callId, name, argumentsOf(call, callId, name), Map.of(), call));
    }
  }

  private Map<String, Object> argumentsOf(
      ChatCompletionMessageFunctionToolCall call, String callId, String name) {
    String arguments = call.function().arguments();
    if (arguments.isEmpty()) {
      return Map.of();
    }
    JsonNode parsed;
    try {
      parsed = json.readTree(arguments);
    } catch (JsonProcessingException failure) {
      // The parser exception is deliberately attached nowhere: Jackson names the token it choked
      // on, so its message is model output and a cause is printed by every logger that prints a
      // stack trace.
      throw new IllegalStateException(argumentFailure(name, callId));
    }
    if (!parsed.isObject()) {
      throw new IllegalStateException(argumentFailure(name, callId));
    }
    Map<String, Object> values = new LinkedHashMap<>();
    parsed
        .properties()
        .forEach(entry -> values.put(entry.getKey(), json.convertValue(entry.getValue(), Object.class)));
    // Collections.unmodifiableMap, never Map.copyOf. A JSON null argument value converts to a Java
    // null, and Map.copyOf rejects it with a bare NullPointerException that names neither the tool
    // nor the key. {"unit":null} is an ordinary thing for a model to send about an optional
    // parameter, and dropping or refusing the key would change what the model said.
    // ToolCallContent copies this into a LinkedHashMap, which keeps the null.
    return Collections.unmodifiableMap(values);
  }

  private static String argumentFailure(String name, String callId) {
    // Names the tool and the call id and stops there. The arguments are model output and are never
    // put in an exception message, nor reachable through one: no parser exception is attached.
    // "exactly one JSON object with unique keys" covers every way the string can fail to be one:
    // invalid JSON, an array or a scalar, a value followed by more input, and a repeated key.
    return "openai chat completions returned arguments that are not exactly one JSON object with"
        + " unique keys for tool '"
        + name
        + "' call '"
        + callId
        + "'";
  }
```

Note the raw representation: the `ToolCallContent` carries the SDK function call, which keeps the
exact arguments string reachable even after the map has been parsed.

`metadataOf` in Task 5 keeps `Map.copyOf`: `id()`, `model()`, and `created()` are all non-null, so
there is nothing for it to reject. The rule is not "never use `Map.copyOf`"; it is "never copy a map
whose values came from the model".

If `JsonNode.properties()` is unavailable on the pinned Jackson, use
`json.convertValue(parsed, new TypeReference<LinkedHashMap<String, Object>>() {})` after the
`isObject` check, then wrap that map the same way; do not fall back to an unchecked cast, which
`-Werror` would fail anyway. That alternative keeps null values too, and the same
`Collections.unmodifiableMap` rule applies to it.

The rejection tests this task's Step 1 lists cover the shape of a single parsed value. The strict
parser above needs three more that the response mapping table now carries as rows: two concatenated
objects, an object followed by input that is not JSON, and an object with a repeated key. Each
asserts the failure names the tool and the call id, and each asserts `hasNoCause()`. One more walks
the whole failure chain - causes *and* suppressed throwables - for input that really does leak
(`{"name": <token>}`, and an object followed by `<token>`, both of which make Jackson answer
`Unrecognized token '<token>'`) and asserts that no link in that chain is a
`JsonProcessingException` and that no message contains the token or the arguments string. Disabling
the source location does not buy that on its own, which is why the parser exception is dropped
rather than wrapped. Prove the parser settings the way the rest of this plan proves a rule: with a
mutation that removes the feature and the test that then fails.

- [ ] **Step 4: Run both response mapper tests**

Run: `./gradlew :providers:agent-framework-openai:test --tests 'io.github.hellices.agentframework.openai.internal.ChatCompletionResponseMapper*'`

Expected: PASS, 40 tests. The selector matches both response mapper classes:
`ChatCompletionResponseMapperTest` (17, Task 5's 13 plus the 4 it added) and
`ChatCompletionResponseMapperToolCallTest` (23, this task's 8 plus the 15 the strict parser, the
pairing asymmetry, and the sanitised parse failure required). Read the counts from the XML report
under `build/test-results/test/`, not from the console sentence.

- [ ] **Step 5: Run the module quality gate**

Run: `./gradlew :providers:agent-framework-openai:quality`

Expected: PASS. Same rule as Task 4: `ChatCompletionResponseMapper` creates its own `ObjectMapper`
and stores no caller-supplied collaborator, so no `EI_EXPOSE_REP2` finding is expected. Fix what is
reported; add a `Match` only for a finding an unfiltered run produced, naming the exact class and
method with a reason.

- [ ] **Step 6: Commit**

```bash
git add providers/agent-framework-openai/src
git commit -m "$(cat <<'MSG'
openai: map function tool calls and their arguments

A function tool call becomes a ToolCallContent carrying the SDK call as its raw
representation, with text first and calls in wire order. Empty and "{}" arguments
both mean no arguments; a JSON array, a scalar, or invalid JSON fails naming the
tool and the call id and never quoting the payload. An argument whose JSON value
is null keeps its key with a null value, because a model sending {"unit":null} is
ordinary and Map.copyOf would have crashed the loop with a bare
NullPointerException. A blank id or name and a non-function tool call fail rather
than being coerced, because a synthesised id would key a tool result the model
never asked for.

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
MSG
)"
```

---

### Task 7: The cancellation and failure bridge

One small class owns everything asynchronous about a call: refuse to dispatch when the signal is
already cancelled, complete the returned stage exceptionally when cancellation arrives after
dispatch, remove the cancellation listener on every completion path, and deliver a provider failure
with the original SDK exception preserved. Listener removal is only meaningful if it is observable,
so the bridge takes a narrow cancellation seam that a test can record against.

Three rules keep the class honest about what it does *not* own. A null argument is rejected by name
where the call was made, because a programming error must not arrive as a failed stage a caller
might map or retry. A `java.lang.Error` is rethrown as the same instance, after the listener and the
result this class owns are settled, because a fatal error is not a provider answer. And the stage
handed out is a minimal completion stage rather than the call's own future, so a caller that
completes or cancels the future it was given settles that future alone: the provider call keeps
running, its real answer still arrives, and the cancellation listener stays registered until the
call itself completes.

**Files:**
- Create: `providers/agent-framework-openai/src/main/java/io/github/hellices/agentframework/openai/internal/OpenAiCallBridge.java`
- Test: `providers/agent-framework-openai/src/test/java/io/github/hellices/agentframework/openai/internal/OpenAiCallBridgeTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces, in `io.github.hellices.agentframework.openai.internal`:
  - `public static <T> CompletionStage<T> OpenAiCallBridge.guard(CancellationSignal signal, Supplier<CompletableFuture<T>> dispatch)`
  - `public static Throwable OpenAiCallBridge.unwrap(Throwable failure)`
  - package-private `interface OpenAiCallBridge.Cancellation` with `boolean isCancelled()` and
    `Runnable onCancel(Runnable listener)`, plus two package-private seams over it:
    `static <T> CompletionStage<T> guardStage(Cancellation, Supplier<CompletableFuture<T>>)`, which
    is exactly what the public overload returns, and
    `static <T> CompletableFuture<T> guard(Cancellation, Supplier<CompletableFuture<T>>)`, the
    call's own future, whose deterministic state a test can assert on directly

- [ ] **Step 1: Write the failing bridge test**

```java
package io.github.hellices.agentframework.openai.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hellices.agentframework.api.agent.CancellationSignal;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OpenAiCallBridgeTest {

  @Test
  void doesNotDispatchWhenTheSignalIsAlreadyCancelled() {
    CancellationSignal signal = new CancellationSignal();
    signal.cancel();
    AtomicInteger dispatches = new AtomicInteger();

    CompletionStage<String> result =
        OpenAiCallBridge.guard(
            signal,
            () -> {
              dispatches.incrementAndGet();
              return CompletableFuture.completedFuture("never");
            });

    assertThat(dispatches).hasValue(0);
    assertThat(result).isCompletedExceptionally();
    // Read through unwrap rather than off join: the exposed stage relays this call's failure, so
    // every reader sees a CompletionException around the cancellation, and JDK 23 and later would
    // add a second CancellationException on top of a bare one anyway.
    assertThat(OpenAiCallBridge.unwrap(exposedFailureOf(result)))
        .isInstanceOf(CancellationException.class)
        .hasMessage("model call was cancelled before it was dispatched");
  }

  @Test
  void rejectsANullSignalOrANullDispatch() {
    // A call site that passes neither is a programming error rather than a request failure, so it
    // fails where it was made, by name, instead of arriving as a failed stage a caller might map.
    assertThatThrownBy(
            () -> OpenAiCallBridge.guard((CancellationSignal) null, CompletableFuture<String>::new))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("signal must not be null");
    assertThatThrownBy(() -> OpenAiCallBridge.guard(new CancellationSignal(), null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("dispatch must not be null");
  }

  @Test
  void rejectsANullCancellationOrANullDispatchOnTheSeam() {
    assertThatThrownBy(
            () ->
                OpenAiCallBridge.guard(
                    (OpenAiCallBridge.Cancellation) null, CompletableFuture<String>::new))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("cancellation must not be null");
    assertThatThrownBy(() -> OpenAiCallBridge.guard(new RecordingCancellation(), null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("dispatch must not be null");
  }

  @Test
  void rethrowsAFatalDispatchErrorAfterRemovingTheCancellationListener() {
    // An Error is not a provider failure. Completing the stage with it and returning would hand a
    // caller a fatal condition to map, retry, or log as a bad request, so the same instance is
    // rethrown - but the listener and the result this bridge owns are still settled first.
    RecordingCancellation cancellation = new RecordingCancellation();
    StackOverflowError fatal = new StackOverflowError("dispatch exhausted the stack");

    assertThatThrownBy(
            () ->
                OpenAiCallBridge.<String>guard(
                    cancellation,
                    () -> {
                      throw fatal;
                    }))
        .isSameAs(fatal);

    assertThat(cancellation.deregistered()).isTrue();
  }

  @Test
  void doesNotLetTheCallerCancelTheRunThroughTheStageItReturns() {
    // The stage handed out is a view, not the call. A caller that cancels its own future abandons
    // its own wait: it neither settles this call nor aborts the HTTP request, which is exactly what
    // cancelling the run's signal cannot do either.
    CancellationSignal signal = new CancellationSignal();
    CompletableFuture<String> inFlight = new CompletableFuture<>();

    CompletionStage<String> result = OpenAiCallBridge.guard(signal, () -> inFlight);
    assertThat(result.toCompletableFuture().cancel(true)).isTrue();

    assertThat(inFlight).isNotDone();
    assertThat(result).isNotDone();

    inFlight.complete("answer");

    assertThat(result).isCompletedWithValue("answer");
  }

  @Test
  void keepsTheCancellationListenerUntilTheProviderCallItselfCompletes() {
    // Cancelling the caller's own future settles nothing here: the provider call is still running,
    // so the listener that would fail this call on a real cancellation has to stay registered, and
    // it is removed when the call finishes rather than when a caller stops waiting for it.
    RecordingCancellation cancellation = new RecordingCancellation();
    CompletableFuture<String> inFlight = new CompletableFuture<>();

    CompletionStage<String> exposed = OpenAiCallBridge.guardStage(cancellation, () -> inFlight);
    assertThat(exposed.toCompletableFuture().cancel(true)).isTrue();

    assertThat(inFlight).isNotDone();
    assertThat(exposed).isNotDone();
    assertThat(cancellation.deregistered()).isFalse();

    inFlight.complete("answer");

    assertThat(exposed).isCompletedWithValue("answer");
    assertThat(cancellation.deregistered()).isTrue();
  }

  @Test
  void handsOutNoCompletionAuthorityOverTheProviderCall() {
    // A forged value on the caller's own future is the same kind of mistake as a forged
    // cancellation: it must not become this call's answer, and it must not settle the provider
    // call, whose real answer still arrives on the stage everyone else reads.
    RecordingCancellation cancellation = new RecordingCancellation();
    CompletableFuture<String> inFlight = new CompletableFuture<>();

    CompletionStage<String> exposed = OpenAiCallBridge.guardStage(cancellation, () -> inFlight);
    CompletableFuture<String> caller = exposed.toCompletableFuture();
    assertThat(caller.complete("forged")).isTrue();

    assertThat(inFlight).isNotDone();
    assertThat(exposed).isNotDone();

    inFlight.complete("answer");

    assertThat(exposed).isCompletedWithValue("answer");
    assertThat(caller).isCompletedWithValue("forged");
  }

  @Test
  void failsPromptlyWhenCancellationArrivesAfterDispatch() {
    CancellationSignal signal = new CancellationSignal();
    CompletableFuture<String> inFlight = new CompletableFuture<>();

    CompletionStage<String> result = OpenAiCallBridge.guard(signal, () -> inFlight);
    assertThat(result).isNotDone();
    signal.cancel();

    assertThat(result).isCompletedExceptionally();
    assertThat(OpenAiCallBridge.unwrap(exposedFailureOf(result)))
        .isInstanceOf(CancellationException.class)
        .hasMessage("model call was cancelled");
    // The in-flight call is deliberately left alone. The SDK derives its future with thenApply, so
    // nothing here can abort the HTTP request; the per-attempt timeout is what reclaims it.
    assertThat(inFlight).isNotDone();
  }

  @Test
  void removesTheCancellationListenerOnEveryCompletionPath() {
    assertThat(deregistrationAfter(dispatch -> dispatch.complete("done"))).isTrue();
    assertThat(deregistrationAfter(dispatch -> dispatch.completeExceptionally(new IllegalStateException("no"))))
        .isTrue();
  }

  @Test
  void removesTheCancellationListenerWhenTheCallIsCancelled() {
    RecordingCancellation cancellation = new RecordingCancellation();
    CompletableFuture<String> dispatch = new CompletableFuture<>();

    CompletableFuture<String> result = OpenAiCallBridge.guard(cancellation, () -> dispatch);
    cancellation.cancel();

    assertThat(result).isCompletedExceptionally();
    assertThat(cancellation.deregistered()).isTrue();
  }

  @Test
  void preservesTheOriginalFailureInstance() {
    CancellationSignal signal = new CancellationSignal();
    IllegalStateException failure = new IllegalStateException("upstream");

    CompletionStage<String> result =
        OpenAiCallBridge.guard(signal, () -> CompletableFuture.failedFuture(failure));

    assertThatThrownBy(result.toCompletableFuture()::join)
        .isInstanceOf(CompletionException.class)
        .satisfies(thrown -> assertThat(thrown.getCause()).isSameAs(failure));
  }

  @Test
  void deliversASynchronousDispatchFailureThroughTheStage() {
    // Mapping happens inside the dispatch supplier, so an unmappable request must arrive as a
    // failed stage rather than as a throw out of ModelClient.run.
    CancellationSignal signal = new CancellationSignal();
    IllegalArgumentException failure = new IllegalArgumentException("cannot map");

    CompletionStage<String> result =
        OpenAiCallBridge.guard(
            signal,
            () -> {
              throw failure;
            });

    assertThatThrownBy(result.toCompletableFuture()::join)
        .isInstanceOf(CompletionException.class)
        .satisfies(thrown -> assertThat(thrown.getCause()).isSameAs(failure));
  }

  @Test
  void unwrapsOnlyTheAsynchronousWrappers() {
    IllegalStateException cause = new IllegalStateException("cause");

    assertThat(OpenAiCallBridge.unwrap(new CompletionException(cause))).isSameAs(cause);
    assertThat(OpenAiCallBridge.unwrap(cause)).isSameAs(cause);
  }

  private static Throwable exposedFailureOf(CompletionStage<?> stage) {
    return stage.handle((value, failure) -> failure).toCompletableFuture().join();
  }

  private static boolean deregistrationAfter(
      java.util.function.Consumer<CompletableFuture<String>> completion) {
    RecordingCancellation cancellation = new RecordingCancellation();
    CompletableFuture<String> dispatch = new CompletableFuture<>();
    OpenAiCallBridge.guard(cancellation, () -> dispatch);
    completion.accept(dispatch);
    return cancellation.deregistered();
  }

  /** Records registration and removal, which a CancellationSignal cannot report on its own. */
  private static final class RecordingCancellation implements OpenAiCallBridge.Cancellation {

    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean deregistered = new AtomicBoolean();
    private Runnable listener;

    @Override
    public boolean isCancelled() {
      return cancelled.get();
    }

    @Override
    public Runnable onCancel(Runnable listener) {
      this.listener = listener;
      return () -> deregistered.set(true);
    }

    void cancel() {
      cancelled.set(true);
      listener.run();
    }

    boolean deregistered() {
      return deregistered.get();
    }
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :providers:agent-framework-openai:test --tests 'io.github.hellices.agentframework.openai.internal.OpenAiCallBridgeTest'`

Expected: compilation FAILS with `cannot find symbol: class OpenAiCallBridge`.

- [ ] **Step 3: Implement the bridge**

```java
package io.github.hellices.agentframework.openai.internal;

import io.github.hellices.agentframework.api.agent.CancellationSignal;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

/**
 * Bridges a framework cancellation signal and a provider call.
 *
 * <p>Cancellation stops the framework from waiting. It does not abort the HTTP request: the future
 * the SDK returns is derived from its transport future with {@code thenApply}, and a JDK {@code
 * CompletableFuture} never completes its antecedent. Cancelling the future taken from the returned
 * stage does not abort it either - that future is a copy, so it abandons one caller's wait and
 * nothing else. Only a timeout reclaims the work nobody is waiting for any more.
 *
 * <p>The bridge neither retries nor logs. It dispatches one call once, and a provider failure
 * reaches the caller as the instance the SDK threw, because that instance carries the status code,
 * the request id, and the retry advice a caller needs, and a second attempt this adapter never
 * announced would double a billed call. The SDK client the host injected may still retry inside
 * that single dispatch: {@code maxRetries} is the host's setting on its own client builder and
 * defaults to two, so an abandoned call can outlive the run by roughly the per-request timeout
 * times the number of attempts, plus the backoff the SDK waits between them. The adapter's
 * per-request timeout bounds one attempt, not the whole call.
 *
 * <p>Null arguments are a call-site programming error and are rejected where the call was made.
 * Everything the dispatch itself does - a mapping failure, no stage at all, a provider failure -
 * arrives through the returned stage instead, so a caller handles one failure path rather than two.
 * A {@link Error} is the exception to that rule: it is rethrown as thrown, after this bridge has
 * settled the listener and the result it owns.
 *
 * <p>The returned stage carries no completion authority. Cancellation completes it with a {@link
 * CancellationException}, which a caller should recognise by type rather than by message, and which
 * a reader always sees behind a {@link CompletionException} because the exposed stage relays this
 * call's outcome: {@link #unwrap(Throwable)} peels that wrapper.
 */
public final class OpenAiCallBridge {

  private OpenAiCallBridge() {}

  /**
   * The part of a cancellation signal this bridge uses.
   *
   * <p>A {@code CancellationSignal} cannot report whether a listener was removed, so a test that
   * asserted cleanup through it would assert nothing. This seam makes the removal observable.
   */
  interface Cancellation {

    boolean isCancelled();

    Runnable onCancel(Runnable listener);
  }

  /**
   * Runs a provider call under a cancellation signal.
   *
   * @param signal the run's cancellation signal, never {@code null}
   * @param dispatch produces the provider call; never invoked when the signal is already cancelled
   * @param <T> the value the provider call produces
   * @return a stage that fails with {@link CancellationException} on cancellation and otherwise
   *     mirrors the provider call, with the original failure preserved and never thrown from this
   *     method; completing or cancelling the future taken from it settles that future alone
   * @throws NullPointerException if {@code signal} or {@code dispatch} is {@code null}
   */
  public static <T> CompletionStage<T> guard(
      CancellationSignal signal, Supplier<CompletableFuture<T>> dispatch) {
    Objects.requireNonNull(signal, "signal must not be null");
    Objects.requireNonNull(dispatch, "dispatch must not be null");
    return guardStage(
        new Cancellation() {
          @Override
          public boolean isCancelled() {
            return signal.isCancelled();
          }

          @Override
          public Runnable onCancel(Runnable listener) {
            return signal.onCancel(listener);
          }
        },
        dispatch);
  }

  static <T> CompletionStage<T> guardStage(
      Cancellation cancellation, Supplier<CompletableFuture<T>> dispatch) {
    // A minimal stage, not the call's own future: a caller that completed or cancelled the future
    // it was handed would otherwise decide this call's outcome, hide the provider's own answer, and
    // remove a cancellation listener while the request it belongs to is still in flight.
    return guard(cancellation, dispatch).minimalCompletionStage();
  }

  static <T> CompletableFuture<T> guard(
      Cancellation cancellation, Supplier<CompletableFuture<T>> dispatch) {
    Objects.requireNonNull(cancellation, "cancellation must not be null");
    Objects.requireNonNull(dispatch, "dispatch must not be null");
    if (cancellation.isCancelled()) {
      return CompletableFuture.failedFuture(
          new CancellationException("model call was cancelled before it was dispatched"));
    }
    CompletableFuture<T> result = new CompletableFuture<>();
    Runnable deregistration =
        cancellation.onCancel(
            () ->
                result.completeExceptionally(
                    new CancellationException("model call was cancelled")));
    result.whenComplete((value, failure) -> deregistration.run());
    if (result.isDone()) {
      // Cancelled between the check and the registration. The listener already ran, so the request
      // must not be dispatched at all.
      return result;
    }
    CompletableFuture<T> dispatched;
    try {
      dispatched = dispatch.get();
    } catch (RuntimeException failure) {
      // Request mapping runs inside the supplier, and ModelClient.run must never throw
      // synchronously: the engine calls it from inside and outside a completion stage, so a throw
      // would surface in two different places depending on the tool loop iteration.
      result.completeExceptionally(failure);
      return result;
    } catch (Error fatal) {
      // A fatal error is not a request failure. Turning it into a failed stage would offer it to a
      // caller to map or retry as if the provider had answered, so the listener and the result this
      // bridge owns are settled and the same instance is rethrown.
      result.completeExceptionally(fatal);
      throw fatal;
    }
    if (dispatched == null) {
      // A port that answers with no stage would otherwise be a null pointer thrown from inside the
      // bridge, which is both the synchronous throw above and a cancellation listener that is never
      // removed because the result never completes.
      result.completeExceptionally(
          new IllegalStateException("the OpenAI chat completion call returned no stage"));
      return result;
    }
    dispatched.whenComplete(
        (value, failure) -> {
          if (failure == null) {
            result.complete(value);
          } else {
            result.completeExceptionally(unwrap(failure));
          }
        });
    return result;
  }

  /**
   * Returns the provider failure behind an asynchronous wrapper.
   *
   * @param failure the failure a completion stage reported, never {@code null}
   * @return the original provider exception instance where there is one
   */
  public static Throwable unwrap(Throwable failure) {
    boolean wrapped =
        failure instanceof CompletionException || failure instanceof ExecutionException;
    return wrapped && failure.getCause() != null ? failure.getCause() : failure;
  }
}
```

- [ ] **Step 4: Run the test again**

Run: `./gradlew :providers:agent-framework-openai:test --tests 'io.github.hellices.agentframework.openai.internal.OpenAiCallBridgeTest'`

Expected: PASS, 21 tests. The snippet above carries 13; the delivered file adds 8 more, each one
carried by a mutation: the registration race, listener removal on a synchronous dispatch failure,
value mirroring, the wrapper-preserving failure path asserted on the seam's raw future, no retry of
a failed call, a dispatch that answers with no stage, the derived-stage cancellation shape, and a
causeless wrapper. Read the count from the XML report under `build/test-results/test/`. The module
suite is then 96 tests (10 + 19 + 17 + 23 + 21 + 6).

- [ ] **Step 5: Run the module quality gate**

Run: `./gradlew :providers:agent-framework-openai:quality`

Expected: PASS. The bridge is static methods over a narrow interface and stores no caller-supplied
collaborator, so no `EI_EXPOSE_REP2` finding is expected. Watch instead for the three rules this
code is closest to: Checkstyle's `IllegalCatch` and `IllegalThrows` list `java.lang.Throwable` only,
so `catch (Error fatal)` is allowed and a `whenComplete` parameter of type `Throwable` is not a
catch at all; PMD's `CompareObjectsWithEquals` forbids `==` between references, and comparison
against `null` is unaffected. Fix what is reported rather than excluding it.

- [ ] **Step 6: Commit**

```bash
git add providers/agent-framework-openai/src
git commit -m "$(cat <<'MSG'
openai: bridge cancellation and provider failures

A cancelled signal means the provider call is never dispatched; cancellation
after dispatch fails the returned stage promptly and the listener is removed on
every completion path, which a narrow cancellation seam makes observable in a
test. Provider failures keep their original instance as the cause, and a
synchronous mapping failure is delivered through the stage rather than thrown, so
ModelClient.run behaves the same on every tool loop iteration. A null argument
and a fatal Error are the two things that do not arrive through the stage: the
first is rejected by name where the call was made, and the second is rethrown as
the same instance once the listener and the result this class owns are settled.

The stage handed out is a minimal completion stage, so completing or cancelling
the future taken from it settles that future alone, and the cancellation listener
stays registered until the call itself completes. The in-flight HTTP request is
deliberately not aborted by either route; the SDK gives no seam for it, and the
timeout that reclaims abandoned work bounds one attempt of a call the host's own
client may retry.

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
MSG
)"
```

---

### Task 8: The public model client

Assemble the facade: the one-method operations port, the SDK-backed implementation, the final
`OpenAiChatModelClient`, and its builder. This is the only public type the slice adds, and the test
pins that surface so a testing convenience cannot quietly become a supported method.

**Files:**
- Create: `providers/agent-framework-openai/src/main/java/io/github/hellices/agentframework/openai/internal/ChatCompletionsOperations.java`
- Create: `providers/agent-framework-openai/src/main/java/io/github/hellices/agentframework/openai/internal/SdkChatCompletionsOperations.java`
- Create: `providers/agent-framework-openai/src/main/java/io/github/hellices/agentframework/openai/OpenAiChatModelClient.java`
- Modify: `providers/agent-framework-openai/src/main/java/io/github/hellices/agentframework/openai/package-info.java`
- Modify, only if SpotBugs reports a finding: `config/spotbugs/exclude.xml`
- Test: `providers/agent-framework-openai/src/test/java/io/github/hellices/agentframework/openai/FakeChatCompletionsOperations.java`
- Test: `providers/agent-framework-openai/src/test/java/io/github/hellices/agentframework/openai/OpenAiChatModelClientTest.java`

**Interfaces:**
- Consumes: the mappers from Tasks 3 to 6 and the bridge from Task 7.
- Produces:
  - `public interface ChatCompletionsOperations` with
    `CompletableFuture<ChatCompletion> create(ChatCompletionCreateParams params, RequestOptions requestOptions)`
  - `public final class SdkChatCompletionsOperations implements ChatCompletionsOperations` with
    `SdkChatCompletionsOperations(OpenAIClientAsync client)`
  - `public final class OpenAiChatModelClient implements ModelClient` with `static Builder builder()`
    and `CompletionStage<ModelResponse> run(ModelRequest request)`
  - `public static final class OpenAiChatModelClient.Builder` with the public methods `client`,
    `model`, `temperature`, `maxOutputTokens`, `requestTimeout`, `build`, and the package-private
    `operations`

- [ ] **Step 1: Write the fake operations port**

```java
package io.github.hellices.agentframework.openai;

import com.openai.core.RequestOptions;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import io.github.hellices.agentframework.openai.internal.ChatCompletionsOperations;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * A scripted stand-in for the one SDK call this adapter makes.
 *
 * <p>The SDK ships no in-memory client and its own tests need a Prism mock server, so this port is
 * what keeps every adapter test deterministic, offline, and credential free.
 */
final class FakeChatCompletionsOperations implements ChatCompletionsOperations {

  private final Deque<Supplier<CompletableFuture<ChatCompletion>>> answers = new ArrayDeque<>();
  private final List<ChatCompletionCreateParams> requests = new ArrayList<>();
  private final List<RequestOptions> requestOptions = new ArrayList<>();

  FakeChatCompletionsOperations answering(ChatCompletion completion) {
    answers.add(() -> CompletableFuture.completedFuture(completion));
    return this;
  }

  FakeChatCompletionsOperations failingWith(RuntimeException failure) {
    answers.add(() -> CompletableFuture.failedFuture(failure));
    return this;
  }

  FakeChatCompletionsOperations withholding(CompletableFuture<ChatCompletion> withheld) {
    answers.add(() -> withheld);
    return this;
  }

  @Override
  public CompletableFuture<ChatCompletion> create(
      ChatCompletionCreateParams params, RequestOptions options) {
    requests.add(params);
    requestOptions.add(options);
    Supplier<CompletableFuture<ChatCompletion>> answer = answers.poll();
    if (answer == null) {
      throw new IllegalStateException(
          "the adapter called the provider "
              + requests.size()
              + " times, but only "
              + (requests.size() - 1)
              + " answers were scripted");
    }
    return answer.get();
  }

  List<ChatCompletionCreateParams> requests() {
    return List.copyOf(requests);
  }

  List<RequestOptions> requestOptions() {
    return List.copyOf(requestOptions);
  }

  int invocations() {
    return requests.size();
  }
}
```

- [ ] **Step 2: Write the failing client test**

```java
package io.github.hellices.agentframework.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openai.errors.OpenAIIoException;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionMessage;
import io.github.hellices.agentframework.api.agent.CancellationSignal;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.Role;
import io.github.hellices.agentframework.api.message.TextContent;
import io.github.hellices.agentframework.spi.model.ModelClient;
import io.github.hellices.agentframework.spi.model.ModelRequest;
import io.github.hellices.agentframework.spi.model.ModelRequestOptions;
import io.github.hellices.agentframework.spi.model.ModelResponse;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

class OpenAiChatModelClientTest {

  @Test
  void sendsTheMappedRequestAndReturnsTheMappedResponse() {
    FakeChatCompletionsOperations operations =
        new FakeChatCompletionsOperations().answering(completion("hello there"));
    ModelClient client = clientOver(operations, builder -> builder.model("gpt-4.1-mini"));

    ModelResponse response = client.run(request("hi")).toCompletableFuture().join();

    assertThat(operations.requests().get(0).model().asString()).isEqualTo("gpt-4.1-mini");
    assertThat(operations.requests().get(0).messages().get(0).asUser().content().asText())
        .isEqualTo("hi");
    assertThat(response.messages().get(0).text()).isEqualTo("hello there");
  }

  @Test
  void dispatchesNothingWhenTheClientIsBuilt() {
    // The adapter borrows a client; building one must not open a connection, start a thread, or
    // send a request, because the host decides when the client is used and when it is closed.
    FakeChatCompletionsOperations operations = new FakeChatCompletionsOperations();

    clientOver(operations, builder -> builder.model("gpt-4.1-mini"));

    assertThat(operations.invocations()).isZero();
  }

  @Test
  void appliesTheConfiguredRequestTimeoutToEveryCall() {
    FakeChatCompletionsOperations operations =
        new FakeChatCompletionsOperations().answering(completion("hi"));
    ModelClient client =
        clientOver(
            operations,
            builder -> builder.model("gpt-4.1-mini").requestTimeout(Duration.ofSeconds(45)));

    client.run(request("hi")).toCompletableFuture().join();

    assertThat(operations.requestOptions().get(0).getTimeout().request())
        .isEqualTo(Duration.ofSeconds(45));
  }

  @Test
  void defaultsTheRequestTimeoutToSixtySeconds() {
    FakeChatCompletionsOperations operations =
        new FakeChatCompletionsOperations().answering(completion("hi"));
    ModelClient client = clientOver(operations, builder -> builder.model("gpt-4.1-mini"));

    client.run(request("hi")).toCompletableFuture().join();

    assertThat(operations.requestOptions().get(0).getTimeout().request())
        .isEqualTo(Duration.ofSeconds(60));
  }

  @Test
  void preservesTheProviderFailureAsTheCause() {
    OpenAIIoException failure = new OpenAIIoException("connection reset");
    FakeChatCompletionsOperations operations =
        new FakeChatCompletionsOperations().failingWith(failure);
    ModelClient client = clientOver(operations, builder -> builder.model("gpt-4.1-mini"));

    assertThatThrownBy(() -> client.run(request("hi")).toCompletableFuture().join())
        .isInstanceOf(CompletionException.class)
        .satisfies(thrown -> assertThat(thrown.getCause()).isSameAs(failure));
  }

  @Test
  void deliversAMappingFailureThroughTheStageRatherThanThrowing() {
    FakeChatCompletionsOperations operations = new FakeChatCompletionsOperations();
    ModelClient client = clientOver(operations, builder -> builder.model("gpt-4.1-mini"));
    ModelRequest unmappable =
        new ModelRequest(
            List.of(new Message(Role.of("auditor"), List.of(new TextContent("hi")))),
            ModelRequestOptions.empty(),
            new CancellationSignal(),
            List.of(),
            Map.of());

    var stage = client.run(unmappable).toCompletableFuture();

    assertThat(operations.invocations()).isZero();
    assertThatThrownBy(stage::join)
        .isInstanceOf(CompletionException.class)
        .hasCauseInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void requiresAModel() {
    assertThatThrownBy(
            () ->
                OpenAiChatModelClient.builder()
                    .operations(new FakeChatCompletionsOperations())
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("model");
  }

  @Test
  void requiresAClient() {
    assertThatThrownBy(() -> OpenAiChatModelClient.builder().model("gpt-4.1-mini").build())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("client");
  }

  @Test
  void ownsNoLifecycle() {
    // The SDK client is borrowed. An adapter with a close() would invite a caller to shut down a
    // client the host still shares with other holders.
    assertThat(AutoCloseable.class).isNotAssignableFrom(OpenAiChatModelClient.class);
    assertThat(OpenAiChatModelClient.class.getMethods())
        .noneMatch(method -> "close".equals(method.getName()));
    assertThat(Modifier.isFinal(OpenAiChatModelClient.class.getModifiers())).isTrue();
  }

  @Test
  void keepsTheSupportedBuilderSurfaceSmall() {
    // The operations seam is a testing entry point, not a supported one. If it ever becomes public
    // this fails, which is the only reliable guard against a convenience becoming an API promise.
    List<String> publicMethods =
        Arrays.stream(OpenAiChatModelClient.Builder.class.getDeclaredMethods())
            .filter(method -> !method.isSynthetic())
            .filter(method -> Modifier.isPublic(method.getModifiers()))
            .map(Method::getName)
            .sorted()
            .toList();

    assertThat(publicMethods)
        .containsExactly(
            "build", "client", "maxOutputTokens", "model", "requestTimeout", "temperature");
  }

  private static ChatCompletion completion(String text) {
    ChatCompletionMessage message =
        ChatCompletionMessage.builder().content(text).refusal((String) null).build();
    return ChatCompletion.builder()
        .id("chatcmpl-test")
        .created(1_700_000_000L)
        .model("gpt-4.1-mini")
        .addChoice(
            ChatCompletion.Choice.builder()
                .finishReason(ChatCompletion.Choice.FinishReason.STOP)
                .index(0L)
                .logprobs((ChatCompletion.Choice.Logprobs) null)
                .message(message)
                .build())
        .build();
  }

  private static ModelRequest request(String text) {
    return new ModelRequest(
        List.of(new Message(Role.USER, List.of(new TextContent(text)))),
        ModelRequestOptions.empty(),
        new CancellationSignal(),
        List.of(),
        Map.of());
  }

  private static ModelClient clientOver(
      FakeChatCompletionsOperations operations,
      java.util.function.UnaryOperator<OpenAiChatModelClient.Builder> configure) {
    return configure.apply(OpenAiChatModelClient.builder().operations(operations)).build();
  }
}
```

Remove any import the final file does not use; `-Werror` does not fail on that but Checkstyle's
`UnusedImports` does.

- [ ] **Step 3: Run the test to verify it fails**

Run: `./gradlew :providers:agent-framework-openai:test --tests 'io.github.hellices.agentframework.openai.OpenAiChatModelClientTest'`

Expected: compilation FAILS with `cannot find symbol: class OpenAiChatModelClient` and
`cannot find symbol: class ChatCompletionsOperations`.

- [ ] **Step 4: Implement the operations port and the SDK binding**

```java
package io.github.hellices.agentframework.openai.internal;

import com.openai.core.RequestOptions;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import java.util.concurrent.CompletableFuture;

/**
 * The one provider operation this adapter performs.
 *
 * <p>Named this narrowly on purpose. The SDK client interface declares two dozen service accessors
 * and the SDK ships no fake, so a test that had to stand in for the client would either implement
 * all of them or start a mock HTTP server. This port is what keeps every adapter test offline.
 */
public interface ChatCompletionsOperations {

  CompletableFuture<ChatCompletion> create(
      ChatCompletionCreateParams params, RequestOptions requestOptions);
}
```

```java
package io.github.hellices.agentframework.openai.internal;

import com.openai.client.OpenAIClientAsync;
import com.openai.core.RequestOptions;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Routes the one operation to a borrowed SDK client.
 *
 * <p>The client is never built, configured, reconnected, or closed here. It owns an HTTP
 * dispatcher, a connection pool, and an executor that the host created, and the SDK deliberately
 * does not make it {@code AutoCloseable} because it is meant to outlive any single caller.
 */
public final class SdkChatCompletionsOperations implements ChatCompletionsOperations {

  private final OpenAIClientAsync client;

  public SdkChatCompletionsOperations(OpenAIClientAsync client) {
    this.client = Objects.requireNonNull(client, "client must not be null");
  }

  @Override
  public CompletableFuture<ChatCompletion> create(
      ChatCompletionCreateParams params, RequestOptions requestOptions) {
    return client.chat().completions().create(params, requestOptions);
  }
}
```

- [ ] **Step 5: Implement the public client**

```java
package io.github.hellices.agentframework.openai;

import com.openai.client.OpenAIClientAsync;
import com.openai.core.RequestOptions;
import io.github.hellices.agentframework.openai.internal.ChatCompletionRequestMapper;
import io.github.hellices.agentframework.openai.internal.ChatCompletionResponseMapper;
import io.github.hellices.agentframework.openai.internal.ChatCompletionsOperations;
import io.github.hellices.agentframework.openai.internal.OpenAiCallBridge;
import io.github.hellices.agentframework.openai.internal.OpenAiChatSettings;
import io.github.hellices.agentframework.openai.internal.SdkChatCompletionsOperations;
import io.github.hellices.agentframework.spi.model.ModelClient;
import io.github.hellices.agentframework.spi.model.ModelRequest;
import io.github.hellices.agentframework.spi.model.ModelResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * Calls OpenAI Chat Completions through the framework's neutral model port.
 *
 * <p>The SDK client is borrowed. This class never builds, configures, reconnects, or closes it, so
 * it allocates no thread, no connection, and no shutdown hook, and discarding an instance releases
 * nothing. The host that created the client decides when it is shut down.
 *
 * <p>The model name, and optionally a temperature and an output token limit, are adapter-owned
 * defaults, because the engine cannot carry request options to a provider yet. A value supplied on
 * the {@code ModelRequest} wins over the default.
 *
 * <p>{@link #run(ModelRequest)} never throws: a request this adapter cannot represent arrives as a
 * failed stage, so a caller handles one failure path rather than two.
 *
 * <p>Cancelling the run's signal fails the returned stage promptly and removes the listener, but it
 * does not abort the HTTP request already in flight, and neither does cancelling the future taken
 * from the returned stage: that future is a copy of the outcome, not the call. A timeout is what
 * bounds the abandoned work, and {@code requestTimeout} bounds one attempt. Retries are the host's
 * setting on the client it builds ({@code maxRetries}, two by default), not this adapter's: the
 * adapter dispatches one call once, so an abandoned call can run for the timeout times the number
 * of attempts, plus the SDK's backoff between them.
 *
 * <p>Instances are immutable and safe to share once built.
 */
public final class OpenAiChatModelClient implements ModelClient {

  private final ChatCompletionsOperations operations;
  private final OpenAiChatSettings settings;
  private final RequestOptions requestOptions;
  private final ChatCompletionRequestMapper requestMapper = new ChatCompletionRequestMapper();
  private final ChatCompletionResponseMapper responseMapper = new ChatCompletionResponseMapper();

  private OpenAiChatModelClient(ChatCompletionsOperations operations, OpenAiChatSettings settings) {
    this.operations = operations;
    this.settings = settings;
    this.requestOptions = RequestOptions.builder().timeout(settings.requestTimeout()).build();
  }

  public static Builder builder() {
    return new Builder();
  }

  @Override
  public CompletionStage<ModelResponse> run(ModelRequest request) {
    Objects.requireNonNull(request, "request must not be null");
    return OpenAiCallBridge.guard(
            request.cancellationSignal(),
            () -> operations.create(requestMapper.map(request, settings), requestOptions))
        .thenApply(responseMapper::map);
  }

  /** Configures one adapter over a borrowed client. */
  public static final class Builder {

    private OpenAIClientAsync client;
    private ChatCompletionsOperations operations;
    private String model;
    private Double temperature;
    private Integer maxOutputTokens;
    private Duration requestTimeout = Duration.ofSeconds(60);

    private Builder() {}

    /**
     * Sets the client this adapter borrows.
     *
     * @param client a client the caller created and will close, never {@code null}
     */
    public Builder client(OpenAIClientAsync client) {
      this.client = client;
      return this;
    }

    /** Sets the required model name sent on every request. */
    public Builder model(String model) {
      this.model = model;
      return this;
    }

    /** Sets the default temperature, which a request option overrides. */
    public Builder temperature(double temperature) {
      this.temperature = temperature;
      return this;
    }

    /** Sets the default output token limit, which a request option overrides. */
    public Builder maxOutputTokens(int maxOutputTokens) {
      this.maxOutputTokens = maxOutputTokens;
      return this;
    }

    /**
     * Sets the per-request timeout that bounds one attempt of a call nobody is waiting for any
     * more. How many attempts there are is the host's {@code maxRetries} on its own client.
     */
    public Builder requestTimeout(Duration requestTimeout) {
      this.requestTimeout = requestTimeout;
      return this;
    }

    /**
     * Sets the operations port directly, which lets a test drive the adapter with no client, no
     * socket, and no credentials. Not part of the supported surface.
     */
    Builder operations(ChatCompletionsOperations operations) {
      this.operations = operations;
      return this;
    }

    public OpenAiChatModelClient build() {
      OpenAiChatSettings settings =
          new OpenAiChatSettings(model, temperature, maxOutputTokens, requestTimeout);
      ChatCompletionsOperations resolved = operations;
      if (resolved == null) {
        if (client == null) {
          throw new IllegalStateException(
              "client must be set: the adapter borrows an OpenAIClientAsync and never creates one");
        }
        resolved = new SdkChatCompletionsOperations(client);
      }
      return new OpenAiChatModelClient(resolved, settings);
    }
  }
}
```

- [ ] **Step 6: Run the client test**

Run: `./gradlew :providers:agent-framework-openai:test --tests 'io.github.hellices.agentframework.openai.OpenAiChatModelClientTest'`

Expected: PASS, 10 tests.

- [ ] **Step 7: Upgrade the package documentation link**

`OpenAiChatModelClient` now exists, so change the first sentence of
`io/github/hellices/agentframework/openai/package-info.java` from `{@code OpenAiChatModelClient}` to
`{@link io.github.hellices.agentframework.openai.OpenAiChatModelClient}`. Task 2 deliberately used
`{@code}` because `-Xdoclint:all,-missing` turns an unresolvable reference into a javadoc error.

Verify with: `./gradlew :providers:agent-framework-openai:javadoc`

Expected: PASS, no warnings.

- [ ] **Step 8: Run the module quality gate**

Run: `./gradlew :providers:agent-framework-openai:quality`

Expected: PASS, or a small number of `EI_EXPOSE_REP2` findings on the collaborators this task
introduces. This is the task that adds them, which is why the gate runs here rather than at the end.

Run it **unfiltered first** and let it report. Only then add a `Match` per finding, to
`config/spotbugs/exclude.xml`, next to the existing MCP entries, naming the exact class and method
and saying why the stored reference is the design. The sites this task could plausibly produce are:

- `SdkChatCompletionsOperations.<init>`, storing the borrowed `OpenAIClientAsync`
- `OpenAiChatModelClient.<init>`, storing the `ChatCompletionsOperations` port
- `OpenAiChatModelClient$Builder.client`, a setter storing the same borrowed client
- `OpenAiChatModelClient$Builder.operations`, a setter storing the test seam

The builder setters are on this list because SpotBugs at `Effort.MAX` / `Confidence.MEDIUM` already
flags builder setters in this repository: `McpStdioTools$Builder.jsonMapper` and
`McpStreamableHttpTools$Builder.toolOptions` are both in `exclude.xml` today. `OpenAiChatSettings`
and `Duration` are immutable values and are not expected to be flagged.

This is a list of candidates, not a list of entries to write. Add nothing SpotBugs did not report,
and never a package-level match, a class-wide `Match` without a method, or a `@SuppressWarnings`. If
a finding is real, fix the code. One entry looks like:

```xml
  <!--
    SdkChatCompletionsOperations stores the caller's OpenAIClientAsync by reference on purpose: it
    must call the very client the host built and still owns, and copying it through its interface is
    not possible and would take over a lifecycle the host must keep.
  -->
  <Match>
    <Class name="io.github.hellices.agentframework.openai.internal.SdkChatCompletionsOperations"/>
    <Method name="&lt;init&gt;"/>
    <Bug pattern="EI_EXPOSE_REP2"/>
  </Match>
```

- [ ] **Step 9: Commit**

```bash
git add providers/agent-framework-openai/src config/spotbugs/exclude.xml
git commit -m "$(cat <<'MSG'
openai: add the OpenAiChatModelClient facade

The adapter now implements ModelClient over a one-method operations port, with an
SDK-backed implementation that borrows an OpenAIClientAsync and never creates,
configures, or closes one. Building a client dispatches nothing, every call
carries the configured request timeout with a 60 second default, and a provider
failure keeps its original instance as the cause. A test pins the supported
builder surface so the operations seam cannot become an API promise. Any SpotBugs
exclusion added here names one class and one method and says why the borrowed
reference is the design.

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
MSG
)"
```

---

### Task 9: Cancellation through the model client

Task 7 proved the bridge. This task proves the adapter uses it, with a real `CancellationSignal`
carried on a real `ModelRequest`, and records the transport limitation as an executable statement
rather than a comment somebody can delete.

**Files:**
- Test: `providers/agent-framework-openai/src/test/java/io/github/hellices/agentframework/openai/OpenAiChatModelClientCancellationTest.java`

**Interfaces:**
- Consumes: `OpenAiChatModelClient` and `FakeChatCompletionsOperations` from Task 8.
- Produces: no production change is expected. If one is needed, the bridge is wrong, not the test.

- [ ] **Step 1: Write the cancellation test**

```java
package io.github.hellices.agentframework.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionMessage;
import io.github.hellices.agentframework.api.agent.CancellationSignal;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.Role;
import io.github.hellices.agentframework.api.message.TextContent;
import io.github.hellices.agentframework.spi.model.ModelClient;
import io.github.hellices.agentframework.spi.model.ModelRequest;
import io.github.hellices.agentframework.spi.model.ModelRequestOptions;
import io.github.hellices.agentframework.spi.model.ModelResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

class OpenAiChatModelClientCancellationTest {

  @Test
  void neverCallsTheProviderWhenTheRunIsAlreadyCancelled() {
    FakeChatCompletionsOperations operations = new FakeChatCompletionsOperations();
    ModelClient client = client(operations);
    CancellationSignal signal = new CancellationSignal();
    signal.cancel();

    CompletionStage<ModelResponse> stage = client.run(request(signal));

    assertThat(operations.invocations()).isZero();
    assertThatThrownBy(() -> stage.toCompletableFuture().join())
        .isInstanceOf(CompletionException.class)
        .hasCauseInstanceOf(CancellationException.class);
  }

  @Test
  void failsPromptlyWhenTheRunIsCancelledAfterDispatchWithoutAbortingTheRequest() {
    // The name says what this slice can and cannot do. The framework stops waiting immediately; the
    // HTTP request is not aborted, because the SDK derives the future it returns from its transport
    // future and a JDK CompletableFuture never cancels its antecedent. Do not "fix" this test by
    // asserting the in-flight call was cancelled; fix the SDK seam first or change nothing.
    CompletableFuture<ChatCompletion> inFlight = new CompletableFuture<>();
    FakeChatCompletionsOperations operations =
        new FakeChatCompletionsOperations().withholding(inFlight);
    ModelClient client = client(operations);
    CancellationSignal signal = new CancellationSignal();

    CompletionStage<ModelResponse> stage = client.run(request(signal));
    assertThat(operations.invocations()).isEqualTo(1);
    signal.cancel();

    assertThatThrownBy(() -> stage.toCompletableFuture().join())
        .isInstanceOf(CompletionException.class)
        .hasCauseInstanceOf(CancellationException.class);
    assertThat(inFlight).isNotDone();
  }

  @Test
  void ignoresACancellationThatArrivesAfterTheRunFinished() {
    // The listener is removed on completion, so a late cancel is a no-op rather than a failure that
    // overwrites a delivered answer.
    CompletableFuture<ChatCompletion> inFlight = new CompletableFuture<>();
    FakeChatCompletionsOperations operations =
        new FakeChatCompletionsOperations().withholding(inFlight);
    ModelClient client = client(operations);
    CancellationSignal signal = new CancellationSignal();

    CompletionStage<ModelResponse> stage = client.run(request(signal));
    inFlight.complete(completion("finished"));
    ModelResponse response = stage.toCompletableFuture().join();
    signal.cancel();

    assertThat(response.messages().get(0).text()).isEqualTo("finished");
    assertThat(stage.toCompletableFuture().join()).isSameAs(response);
  }

  @Test
  void keepsTheCancellationWhenTheProviderAnswersAfterwards() {
    // The other side of the race. A cancelled run stays cancelled even though the abandoned request
    // eventually completes, which is exactly what happens in production because it is not aborted.
    CompletableFuture<ChatCompletion> inFlight = new CompletableFuture<>();
    FakeChatCompletionsOperations operations =
        new FakeChatCompletionsOperations().withholding(inFlight);
    ModelClient client = client(operations);
    CancellationSignal signal = new CancellationSignal();

    CompletionStage<ModelResponse> stage = client.run(request(signal));
    signal.cancel();
    inFlight.complete(completion("too late"));

    assertThatThrownBy(() -> stage.toCompletableFuture().join())
        .isInstanceOf(CompletionException.class)
        .hasCauseInstanceOf(CancellationException.class);
  }

  private static ModelClient client(FakeChatCompletionsOperations operations) {
    return OpenAiChatModelClient.builder().operations(operations).model("gpt-4.1-mini").build();
  }

  private static ModelRequest request(CancellationSignal signal) {
    return new ModelRequest(
        List.of(new Message(Role.USER, List.of(new TextContent("hi")))),
        ModelRequestOptions.empty(),
        signal,
        List.of(),
        Map.of());
  }

  private static ChatCompletion completion(String text) {
    ChatCompletionMessage message =
        ChatCompletionMessage.builder().content(text).refusal((String) null).build();
    return ChatCompletion.builder()
        .id("chatcmpl-test")
        .created(1_700_000_000L)
        .model("gpt-4.1-mini")
        .addChoice(
            ChatCompletion.Choice.builder()
                .finishReason(ChatCompletion.Choice.FinishReason.STOP)
                .index(0L)
                .logprobs((ChatCompletion.Choice.Logprobs) null)
                .message(message)
                .build())
        .build();
  }
}
```

- [ ] **Step 2: Run the test**

Run: `./gradlew :providers:agent-framework-openai:test --tests 'io.github.hellices.agentframework.openai.OpenAiChatModelClientCancellationTest'`

Expected: PASS, 4 tests, with no production change. If any case fails, the defect is in
`OpenAiCallBridge`; fix it there and keep the Task 7 tests green.

- [ ] **Step 3: Commit**

```bash
git add providers/agent-framework-openai/src
git commit -m "$(cat <<'MSG'
openai: pin the cancellation contract at the model client

An already cancelled run never reaches the provider, a cancellation after
dispatch fails the stage promptly, a late cancel cannot overwrite a delivered
answer, and a cancelled run stays cancelled when the abandoned request answers
later. The test names state that the in-flight HTTP request is not aborted, so
the limitation is recorded where it cannot be quietly deleted.

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
MSG
)"
```

---

### Task 10: The end-to-end tool loop over the real engine

The proof that the adapter and the engine agree. Everything before this tested one direction of one
mapping; this runs `AgentEngine` with a real `FunctionTool` and a faked transport, and asserts that
the second request the adapter sent carries the assistant echo and the tool message the model needs
in order to answer.

**Files:**
- Test: `providers/agent-framework-openai/src/test/java/io/github/hellices/agentframework/openai/OpenAiChatModelClientToolLoopTest.java`

**Interfaces:**
- Consumes: `OpenAiChatModelClient` and `FakeChatCompletionsOperations` from Task 8, and
  `:agent-framework-engine` as a test-only dependency allowlisted in Task 2.
- Produces: no production change.

- [ ] **Step 1: Write the end-to-end test**

```java
package io.github.hellices.agentframework.openai;

import static org.assertj.core.api.Assertions.assertThat;

import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionMessageToolCall;
import io.github.hellices.agentframework.api.message.TextContent;
import io.github.hellices.agentframework.api.tool.FunctionTool;
import io.github.hellices.agentframework.api.tool.ToolResult;
import io.github.hellices.agentframework.engine.AgentEngine;
import io.github.hellices.agentframework.spi.model.ModelClient;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * Runs the real tool loop over a faked transport.
 *
 * <p>The engine is a test-only dependency of this module. It is here rather than in the engine or
 * the sample because this is the one test that can fail when either side changes: the engine emits
 * one tool message holding every result of a round, and Chat Completions needs one message per tool
 * call id. Nothing else in the repository would notice that going wrong.
 */
class OpenAiChatModelClientToolLoopTest {

  @Test
  void runsAnOrdinaryFunctionToolLoopEndToEnd() {
    FakeChatCompletionsOperations operations =
        new FakeChatCompletionsOperations()
            .answering(toolCallCompletion())
            .answering(textCompletion("It is sunny in Seoul"));
    ModelClient client =
        OpenAiChatModelClient.builder().operations(operations).model("gpt-4.1-mini").build();
    FunctionTool weather =
        FunctionTool.create(
            "weather",
            "Gets the weather of a city",
            Map.of("type", "object", "properties", Map.of("city", Map.of("type", "string"))),
            (arguments, context) ->
                CompletableFuture.completedFuture(
                    ToolResult.success(new TextContent("sunny:" + arguments.get("city")))));
    AgentEngine engine = AgentEngine.builder().modelClient(client).tools(weather).build();

    var response = engine.run("weather in Seoul?").response().toCompletableFuture().join();

    assertThat(operations.invocations()).isEqualTo(2);

    ChatCompletionCreateParams first = operations.requests().get(0);
    assertThat(first.tools().orElseThrow())
        .singleElement()
        .satisfies(tool -> assertThat(tool.asFunction().function().name()).isEqualTo("weather"));

    List<ChatCompletionMessageParam> second = operations.requests().get(1).messages();
    assertThat(second).hasSize(3);
    assertThat(second.get(0).isUser()).isTrue();
    // The echo carries the SDK message the response mapper attached, so the arguments string is the
    // one the model produced rather than a re-serialised map.
    assertThat(second.get(1).asAssistant().toolCalls().orElseThrow())
        .singleElement()
        .satisfies(
            call -> {
              assertThat(call.asFunction().id()).isEqualTo("call_1");
              assertThat(call.asFunction().function().arguments())
                  .isEqualTo("{\"city\": \"Seoul\"}");
            });
    // One framework tool message holding one result became one OpenAI tool message keyed by the
    // call id. This is the assertion that catches the fan-out being dropped.
    assertThat(second.get(2).asTool().toolCallId()).isEqualTo("call_1");
    assertThat(second.get(2).asTool().content().asText()).isEqualTo("sunny:Seoul");

    assertThat(response.text()).endsWith("It is sunny in Seoul");
  }

  private static ChatCompletion toolCallCompletion() {
    ChatCompletionMessage message =
        ChatCompletionMessage.builder()
            .content((String) null)
            .refusal((String) null)
            .addToolCall(
                ChatCompletionMessageToolCall.ofFunction(
                    ChatCompletionMessageFunctionToolCall.builder()
                        .id("call_1")
                        .function(
                            ChatCompletionMessageFunctionToolCall.Function.builder()
                                .name("weather")
                                .arguments("{\"city\": \"Seoul\"}")
                                .build())
                        .build()))
            .build();
    return completion(message, ChatCompletion.Choice.FinishReason.TOOL_CALLS);
  }

  private static ChatCompletion textCompletion(String text) {
    return completion(
        ChatCompletionMessage.builder().content(text).refusal((String) null).build(),
        ChatCompletion.Choice.FinishReason.STOP);
  }

  private static ChatCompletion completion(
      ChatCompletionMessage message, ChatCompletion.Choice.FinishReason finishReason) {
    return ChatCompletion.builder()
        .id("chatcmpl-test")
        .created(1_700_000_000L)
        .model("gpt-4.1-mini")
        .addChoice(
            ChatCompletion.Choice.builder()
                .finishReason(finishReason)
                .index(0L)
                .logprobs((ChatCompletion.Choice.Logprobs) null)
                .message(message)
                .build())
        .build();
  }
}
```

- [ ] **Step 2: Run the test**

Run: `./gradlew :providers:agent-framework-openai:test --tests 'io.github.hellices.agentframework.openai.OpenAiChatModelClientToolLoopTest'`

Expected: PASS, 1 test. A failure on the second request's message count usually means the response
mapper stopped attaching the SDK message as `Message.rawRepresentation`, which makes the request
mapper fall back to reconstruction and changes the arguments string.

- [ ] **Step 3: Run the whole module suite**

Run: `./gradlew :providers:agent-framework-openai:test`

Expected: PASS. Confirm the total with
`grep -ho 'tests="[0-9]*"' providers/agent-framework-openai/build/test-results/test/*.xml`, which
should sum to 67 across nine test classes: 6 settings, 10 request mapping, 8 request tool mapping,
13 response mapping, 8 response tool-call mapping, 7 bridge, 10 client, 4 cancellation, and 1 tool
loop. Treat the total as a checksum, not a target: if it differs, find out which case moved before
changing the number.

- [ ] **Step 4: Commit**

```bash
git add providers/agent-framework-openai/src
git commit -m "$(cat <<'MSG'
openai: prove the adapter and the tool loop agree

Runs AgentEngine with a real function tool over a faked transport and asserts the
second request carries the assistant echo with the model's own arguments string
and one tool message per tool call id. The engine reports a whole round of tool
results as one message while Chat Completions needs one per call, and this is the
only test in the repository that fails when either side of that changes.

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
MSG
)"
```

---

### Task 11: The standalone sample calls a real endpoint

The sample stops pretending. `createAgent(ModelClient, Clock)` stays injectable so the sample keeps a
deterministic test, and `main` resolves credentials from the environment, owns the SDK client, and
closes it. No fallback to a fake, no demo mode, and no network in any test.

The sample also registers one function tool: `current_utc_time`, which reads an injected
`java.time.Clock` and returns an ISO-8601 instant. It takes no arguments, touches no file, no socket,
and no process, and it logs nothing, so it is safe to ship in a sample that anyone may run against
their own credentials. The default prompt asks the model to use it, which is what turns
`:samples:sample-standalone:run` into an observable function-tool loop instead of a plain
completion. An explicit user prompt still wins: `--args="..."` is passed through unchanged, and the
model may answer it without calling the tool, which is the ordinary path and not a failure.

The tool exists in `main`, so the live milestone exercises the real loop. Its correctness is not
proved by the live run: the sample's own tests inject a scripted `ModelClient` and a fixed `Clock`
and assert registration, the loop, and the tool output deterministically, with no network and no
credential.

**Files:**
- Modify: `samples/sample-standalone/build.gradle.kts`
- Modify: `samples/sample-standalone/src/main/java/io/github/hellices/agentframework/samples/standalone/StandaloneAgentApplication.java`
- Modify: `samples/sample-standalone/src/test/java/io/github/hellices/agentframework/samples/standalone/StandaloneAgentApplicationTest.java`
- Modify: `samples/sample-standalone/gradle.lockfile`

**Interfaces:**
- Consumes: `OpenAiChatModelClient` from Task 8.
- Produces, in `io.github.hellices.agentframework.samples.standalone`:
  - `public static Agent createAgent(ModelClient modelClient, Clock clock)`
  - package-private `static final String TOOL_NAME = "current_utc_time"`
  - package-private `static FunctionTool currentTimeTool(Clock clock)`,
    `static String defaultPrompt()`, `static String prompt(String[] args)`,
    `static String requiredApiKey(Map<String, String> environment)`,
    `static String baseUrl(Map<String, String> environment)`,
    `static String model(Map<String, String> environment)`,
    `static String footer(AgentResponse response, String model)`
  - The no-argument `createAgent()` is removed. It only ever returned the fake.

- [ ] **Step 1: Rewrite the sample test**

```java
package io.github.hellices.agentframework.samples.standalone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hellices.agentframework.api.agent.Agent;
import io.github.hellices.agentframework.api.agent.AgentResponse;
import io.github.hellices.agentframework.api.message.FinishReason;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.Role;
import io.github.hellices.agentframework.api.message.TextContent;
import io.github.hellices.agentframework.api.message.ToolCallContent;
import io.github.hellices.agentframework.api.message.ToolResultContent;
import io.github.hellices.agentframework.api.message.Usage;
import io.github.hellices.agentframework.spi.model.ModelClient;
import io.github.hellices.agentframework.spi.model.ModelRequest;
import io.github.hellices.agentframework.spi.model.ModelResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

/**
 * Covers the sample without a network call, a credential, or a wall clock.
 *
 * <p>The agent assembly, the registered tool, and the whole function-tool loop are exercised
 * through an injected model client and an injected clock, and credential resolution is a pure
 * function over a supplied environment map. Nothing here reads the real process environment, the
 * real time, or builds an SDK client, so the suite runs identically on a laptop and in CI.
 *
 * <p>What these tests deliberately do not claim is that a live model calls the tool. Tool selection
 * belongs to the model. What is proved here is that the tool is registered on every request, that
 * the loop completes when the model does call it, and that the default prompt asks for it by name.
 */
class StandaloneAgentApplicationTest {

  private static final Clock FIXED_CLOCK =
      Clock.fixed(Instant.parse("2026-08-16T00:00:00Z"), ZoneOffset.UTC);
  private static final String FIXED_TIME = "2026-08-16T00:00:00Z";

  @Test
  void runsWithAnInjectedModelClientAndOffersTheLocalTool() {
    ScriptedModelClient modelClient = new ScriptedModelClient(text("pong", null));

    Agent agent = StandaloneAgentApplication.createAgent(modelClient, FIXED_CLOCK);

    assertThat(agent.run("ping").response().toCompletableFuture().join().text()).isEqualTo("pong");
    // The tool reaches the wire on the very first request, which is what makes the live run a tool
    // loop rather than a plain completion.
    assertThat(modelClient.requests().get(0).tools())
        .singleElement()
        .satisfies(tool -> assertThat(tool.name()).isEqualTo(StandaloneAgentApplication.TOOL_NAME));
  }

  @Test
  void runsTheFunctionToolLoopWithTheInjectedClock() {
    // The loop the live milestone is supposed to exercise, proved here with no network: the model
    // asks for the tool, the sample's tool answers from the injected clock, and the second request
    // carries that answer back.
    ScriptedModelClient modelClient =
        new ScriptedModelClient(
            toolCall("call_1", new Usage(3L, 1L, 4L)),
            text("It is " + FIXED_TIME, new Usage(5L, 2L, 7L)));

    AgentResponse response =
        StandaloneAgentApplication.createAgent(modelClient, FIXED_CLOCK)
            .run(StandaloneAgentApplication.defaultPrompt())
            .response()
            .toCompletableFuture()
            .join();

    assertThat(modelClient.requests()).hasSize(2);
    List<Message> second = modelClient.requests().get(1).messages();
    ToolResultContent result =
        (ToolResultContent)
            second.stream()
                .filter(message -> Role.TOOL.equals(message.role()))
                .flatMap(message -> message.content().stream())
                .findFirst()
                .orElseThrow();
    assertThat(result.callId()).isEqualTo("call_1");
    assertThat(result.content())
        .singleElement()
        .extracting(content -> ((TextContent) content).value())
        .isEqualTo(FIXED_TIME);
    assertThat(response.text()).isEqualTo("It is " + FIXED_TIME);
  }

  @Test
  void namesTheSameToolInTheDefaultPromptAndTheToolDefinition() {
    // If these two drift apart, the default run silently stops exercising the loop while every test
    // still passes, which is exactly the gap this sample exists to close.
    assertThat(StandaloneAgentApplication.currentTimeTool(FIXED_CLOCK).definition().name())
        .isEqualTo(StandaloneAgentApplication.TOOL_NAME);
    assertThat(StandaloneAgentApplication.defaultPrompt())
        .contains(StandaloneAgentApplication.TOOL_NAME);
  }

  @Test
  void usesTheDefaultPromptWhenNoArgumentIsGiven() {
    assertThat(StandaloneAgentApplication.prompt(new String[0]))
        .isEqualTo(StandaloneAgentApplication.defaultPrompt());
  }

  @Test
  void keepsArbitraryUserArguments() {
    // The default prompt is a convenience, not a cage. Whatever the user passes is what is asked.
    assertThat(StandaloneAgentApplication.prompt(new String[] {"summarise", "this"}))
        .isEqualTo("summarise this");
  }

  @Test
  void failsWithAnExplicitMessageWhenTheApiKeyIsMissing() {
    // No silent fallback to a fake model. A sample that answers without a key teaches the wrong
    // thing and hides a misconfiguration until it matters.
    assertThatThrownBy(() -> StandaloneAgentApplication.requiredApiKey(Map.of()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(
            "OPENAI_API_KEY is not set. Export a key (and optionally OPENAI_BASE_URL /"
                + " OPENAI_MODEL) before running the sample.");
  }

  @Test
  void treatsABlankApiKeyAsMissing() {
    assertThatThrownBy(
            () -> StandaloneAgentApplication.requiredApiKey(Map.of("OPENAI_API_KEY", "   ")))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void defaultsTheBaseUrlAndTheModel() {
    assertThat(StandaloneAgentApplication.baseUrl(Map.of()))
        .isEqualTo("https://api.openai.com/v1");
    assertThat(StandaloneAgentApplication.model(Map.of())).isEqualTo("gpt-4.1-mini");
  }

  @Test
  void honoursAConfiguredBaseUrlAndModel() {
    // The same sample must reach a compatible endpoint, which is what makes the adapter useful
    // beyond api.openai.com.
    Map<String, String> environment =
        Map.of("OPENAI_BASE_URL", "http://localhost:11434/v1", "OPENAI_MODEL", "llama3.1");

    assertThat(StandaloneAgentApplication.baseUrl(environment))
        .isEqualTo("http://localhost:11434/v1");
    assertThat(StandaloneAgentApplication.model(environment)).isEqualTo("llama3.1");
  }

  @Test
  void printsANonSensitiveFooterThatCountsToolCalls() {
    // The footer is how a human sees that the loop ran. It carries a count, never the prompt, the
    // reply, or the tool's output.
    ScriptedModelClient modelClient =
        new ScriptedModelClient(
            toolCall("call_1", new Usage(3L, 1L, 4L)),
            text("It is " + FIXED_TIME, new Usage(5L, 2L, 7L)));
    AgentResponse response =
        StandaloneAgentApplication.createAgent(modelClient, FIXED_CLOCK)
            .run(StandaloneAgentApplication.defaultPrompt())
            .response()
            .toCompletableFuture()
            .join();

    assertThat(StandaloneAgentApplication.footer(response, "gpt-4.1-mini"))
        .isEqualTo(
            "[model=gpt-4.1-mini finishReason=STOP toolCalls=1 inputTokens=8 outputTokens=3]")
        .doesNotContain(FIXED_TIME)
        .doesNotContain(StandaloneAgentApplication.defaultPrompt());
  }

  private static ModelResponse text(String value, Usage usage) {
    return new ModelResponse(
        List.of(new Message(Role.ASSISTANT, List.of(new TextContent(value)))),
        usage,
        FinishReason.STOP,
        Map.of(),
        null);
  }

  private static ModelResponse toolCall(String callId, Usage usage) {
    return new ModelResponse(
        List.of(
            new Message(
                Role.ASSISTANT,
                List.of(
                    new ToolCallContent(
                        callId, StandaloneAgentApplication.TOOL_NAME, Map.of())))),
        usage,
        FinishReason.TOOL_CALLS,
        Map.of(),
        null);
  }

  /** Answers from a script and records what the sample asked for. */
  private static final class ScriptedModelClient implements ModelClient {

    private final List<ModelRequest> requests = new ArrayList<>();
    private final Deque<ModelResponse> answers;

    ScriptedModelClient(ModelResponse... answers) {
      this.answers = new ArrayDeque<>(List.of(answers));
    }

    @Override
    public CompletionStage<ModelResponse> run(ModelRequest request) {
      requests.add(request);
      ModelResponse answer = answers.poll();
      if (answer == null) {
        throw new IllegalStateException("the sample called the model more times than scripted");
      }
      return CompletableFuture.completedFuture(answer);
    }

    List<ModelRequest> requests() {
      return List.copyOf(requests);
    }
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :samples:sample-standalone:test`

Expected: compilation FAILS with `cannot find symbol` for `TOOL_NAME`, `currentTimeTool`,
`defaultPrompt`, `prompt`, `requiredApiKey`, `baseUrl`, `model`, `footer`, and
`createAgent(ModelClient, Clock)`.

- [ ] **Step 3: Add the provider dependency**

`samples/sample-standalone/build.gradle.kts`:

```kotlin
description = "Runnable standalone Agent.run sample over a real OpenAI-compatible endpoint."

dependencies {
    implementation(project(":agent-framework-api"))
    implementation(project(":agent-framework-engine"))
    implementation(project(":providers:agent-framework-openai"))
}
```

- [ ] **Step 4: Rewrite the sample**

```java
package io.github.hellices.agentframework.samples.standalone;

import com.openai.client.OpenAIClientAsync;
import com.openai.client.okhttp.OpenAIOkHttpClientAsync;
import io.github.hellices.agentframework.api.agent.Agent;
import io.github.hellices.agentframework.api.agent.AgentFactory;
import io.github.hellices.agentframework.api.agent.AgentResponse;
import io.github.hellices.agentframework.api.message.Content;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.TextContent;
import io.github.hellices.agentframework.api.message.ToolCallContent;
import io.github.hellices.agentframework.api.message.Usage;
import io.github.hellices.agentframework.api.tool.FunctionTool;
import io.github.hellices.agentframework.api.tool.ToolResult;
import io.github.hellices.agentframework.engine.AgentEngine;
import io.github.hellices.agentframework.openai.OpenAiChatModelClient;
import io.github.hellices.agentframework.spi.model.ModelCatalog;
import io.github.hellices.agentframework.spi.model.ModelClient;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Runs one agent turn against a real OpenAI-compatible endpoint with no host framework.
 *
 * <p>Reads {@code OPENAI_API_KEY} (required), {@code OPENAI_BASE_URL} (defaults to the official
 * endpoint), and {@code OPENAI_MODEL} (defaults to {@code gpt-4.1-mini}). There is no deterministic
 * fallback: a sample that answers without a key would teach that the call succeeded when it never
 * happened.
 *
 * <p>One local function tool, {@value #TOOL_NAME}, is registered on every request. It reads the
 * supplied {@link java.time.Clock} and returns an ISO-8601 instant; it takes no arguments, opens no
 * file, socket, or process, and logs nothing. The default prompt asks the model to use it, so the
 * default run exercises the whole function-tool loop end to end. Passing arguments replaces that
 * prompt with whatever was asked, and the model is free to answer without calling the tool, which
 * is the ordinary response path rather than a failure. The footer reports how many tool calls the
 * run actually made.
 *
 * <p>This class owns the SDK client, and closes it, because the adapter deliberately does not.
 */
public final class StandaloneAgentApplication {

  /** The one tool this sample registers. Named in the default prompt so the two cannot drift. */
  static final String TOOL_NAME = "current_utc_time";

  private static final String API_KEY_VARIABLE = "OPENAI_API_KEY";
  private static final String BASE_URL_VARIABLE = "OPENAI_BASE_URL";
  private static final String MODEL_VARIABLE = "OPENAI_MODEL";
  private static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";
  private static final String DEFAULT_MODEL = "gpt-4.1-mini";

  private StandaloneAgentApplication() {}

  /**
   * Builds the agent over a supplied model client and clock.
   *
   * <p>Both are parameters so the sample's own test can run the same assembly, including the tool
   * loop, deterministically and without a credential or a socket.
   */
  public static Agent createAgent(ModelClient modelClient, Clock clock) {
    ModelCatalog catalog =
        ModelCatalog.builder().add("openai", modelClient).defaultModel("openai").build();
    AgentFactory factory = AgentEngine.factory(catalog);
    return factory
        .builder()
        .id("standalone-agent")
        .name("Standalone Agent")
        .description("Runs without a host framework, calling an OpenAI-compatible endpoint.")
        .tools(currentTimeTool(clock))
        .build();
  }

  public static void main(String[] args) {
    Map<String, String> environment = System.getenv();
    String model = model(environment);
    OpenAIClientAsync client =
        OpenAIOkHttpClientAsync.builder()
            .apiKey(requiredApiKey(environment))
            .baseUrl(baseUrl(environment))
            .build();
    try {
      Agent agent =
          createAgent(
              OpenAiChatModelClient.builder().client(client).model(model).build(),
              Clock.systemUTC());
      AgentResponse response =
          agent.run(prompt(args)).response().toCompletableFuture().join();
      System.out.println(response.text());
      System.out.println(footer(response, model));
    } finally {
      // The sample created the client, so the sample closes it. A long-lived host would not.
      client.close();
    }
  }

  /**
   * The one tool the sample offers: the current UTC time, to the second, from the supplied clock.
   *
   * <p>Deliberately boring. It reads no argument, reaches nothing outside the process, and prints
   * nothing, so registering it in a sample that runs against someone's own credentials is safe.
   */
  static FunctionTool currentTimeTool(Clock clock) {
    return FunctionTool.create(
        TOOL_NAME,
        "Returns the current UTC time as an ISO-8601 instant.",
        Map.of("type", "object", "properties", Map.of()),
        (arguments, context) ->
            CompletableFuture.completedFuture(
                ToolResult.success(
                    new TextContent(
                        DateTimeFormatter.ISO_INSTANT.format(
                            clock.instant().truncatedTo(ChronoUnit.SECONDS))))));
  }

  /** The prompt used when the sample is run with no arguments. Names the tool on purpose. */
  static String defaultPrompt() {
    return "Call the " + TOOL_NAME + " tool and tell me the current UTC time.";
  }

  static String prompt(String[] args) {
    return args.length == 0 ? defaultPrompt() : String.join(" ", args);
  }

  static String requiredApiKey(Map<String, String> environment) {
    String apiKey = environment.get(API_KEY_VARIABLE);
    if (apiKey == null || apiKey.isBlank()) {
      throw new IllegalStateException(
          "OPENAI_API_KEY is not set. Export a key (and optionally OPENAI_BASE_URL /"
              + " OPENAI_MODEL) before running the sample.");
    }
    return apiKey;
  }

  static String baseUrl(Map<String, String> environment) {
    return valueOrDefault(environment, BASE_URL_VARIABLE, DEFAULT_BASE_URL);
  }

  static String model(Map<String, String> environment) {
    return valueOrDefault(environment, MODEL_VARIABLE, DEFAULT_MODEL);
  }

  /**
   * A short run summary that carries no prompt text and no model output.
   *
   * <p>The tool-call count is what makes the loop observable: zero means the model answered without
   * calling the tool, which is its decision to make.
   */
  static String footer(AgentResponse response, String model) {
    Usage usage = response.usage();
    return "[model="
        + model
        + " finishReason="
        + response.finishReason()
        + " toolCalls="
        + toolCallCount(response)
        + " inputTokens="
        + (usage == null ? "n/a" : usage.inputTokens())
        + " outputTokens="
        + (usage == null ? "n/a" : usage.outputTokens())
        + "]";
  }

  private static long toolCallCount(AgentResponse response) {
    long calls = 0;
    for (Message message : response.messages()) {
      for (Content content : message.content()) {
        if (content instanceof ToolCallContent) {
          calls++;
        }
      }
    }
    return calls;
  }

  private static String valueOrDefault(
      Map<String, String> environment, String variable, String fallback) {
    String value = environment.get(variable);
    return value == null || value.isBlank() ? fallback : value;
  }
}
```

- [ ] **Step 5: Regenerate the sample lockfile**

Run: `./gradlew :samples:sample-standalone:resolveAndLockAll --write-locks`

Expected: the sample lockfile grows by the SDK graph: okhttp, okio, the Kotlin standard library,
`kotlin-reflect`, the SDK jars, swagger annotations, error-prone annotations, and the three victools
artifacts.

Read the Jackson lines **per configuration**, because that is what the `api(platform(...))` decision
in Task 2 is for:

```bash
grep '^com.fasterxml.jackson' samples/sample-standalone/gradle.lockfile
```

Before this task the sample resolved Jackson on `runtimeClasspath,testRuntimeClasspath` only, since
the engine declares it as `implementation`. After it, `openai-java` is an `api` dependency of the
provider and its POM puts `jackson-databind` at **compile** scope, so Jackson now appears on
`compileClasspath` too. The platform is on `api` precisely so that the compile side lands at the
repository pin rather than at the SDK's 2.18.9: expect `jackson-databind:2.22.1`,
`jackson-core:2.22.1`, and `jackson-bom:2.22.1` naming both compile and runtime classpaths, and
`jackson-annotations:2.22`, which is the version the Jackson BOM aligns annotations to.

Then check for skew:

```bash
grep '^com.fasterxml.jackson' samples/sample-standalone/gradle.lockfile | grep '2\.18\.'
```

Expected: no output, and no coordinate listed twice at two versions. Had the platform stayed on
`implementation`, this is exactly where two versions of `jackson-databind` would have appeared, one
per classpath, and "Jackson stays at the repository pin" would have looked like a failure of this
step rather than of the build file.

- [ ] **Step 6: Run the sample test and confirm nothing reaches the network**

Run: `./gradlew :samples:sample-standalone:test`

Expected: PASS, 10 tests. Then confirm the suite is credential free and offline by running it with an
empty environment and no network: `env -u OPENAI_API_KEY -u OPENAI_BASE_URL -u OPENAI_MODEL ./gradlew
:samples:sample-standalone:test --rerun-tasks`. Expected: PASS. If it ever fails without a key, a
test has started resolving the real environment and must be fixed, not skipped.

- [ ] **Step 7: Run the sample quality gate**

Run: `./gradlew :samples:sample-standalone:quality`

Expected: PASS. The sample applies the same quality conventions as a library, and this task adds a
lambda-captured `Clock`, a tool definition, and a counting loop, so run the gate here rather than
discovering it in Task 13.

- [ ] **Step 8: Commit**

```bash
git add samples/sample-standalone
git commit -m "$(cat <<'MSG'
sample: run the standalone agent against a real endpoint

The sample now assembles OpenAiChatModelClient over an SDK client it creates and
closes, reading OPENAI_API_KEY, OPENAI_BASE_URL, and OPENAI_MODEL. A missing key
fails with an explicit message instead of falling back to the old deterministic
lambda, which answered without ever calling a model. It registers one harmless
local tool, current_utc_time, reading an injected Clock, and its default prompt
asks the model to use it, so the default run exercises the real function-tool
loop and the footer reports how many calls it made. An explicit prompt is passed
through unchanged and the model may still answer without a call. createAgent
takes the model client and the clock so the sample test proves registration, the
loop, and the tool output offline with no credential, and credential resolution
is tested as a pure function over a supplied environment.

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
MSG
)"
```

---

### Task 12: Adapter README, traceability, and repository documentation

PRV-010 asks for maturity, a README, and test evidence together. This task writes them and moves the
provider requirement statuses to what the code now supports, no further.

**Files:**
- Create: `providers/agent-framework-openai/README.md`
- Modify: `docs/design/requirements-design/requirements-traceability-matrix.md`
- Modify: `docs/design/module-composition.md`
- Modify: `docs/operations/getting-started.md`
- Modify: `README.md`
- Modify: `docs/ko/README.md`

**Interfaces:**
- Consumes: everything from Tasks 2 to 11.
- Produces: no code.

- [ ] **Step 1: Write the adapter README**

Create `providers/agent-framework-openai/README.md` covering, in this order:

1. **Maturity: Preview.** The public surface is one class and a builder, and it may change before
   the first release. Say so plainly.
2. **Install.** Gradle and Maven snippets for `io.github.hellices.agentframework:agent-framework-openai`
   with the BOM, noting that `com.openai:openai-java:4.51.0` arrives as an `api` dependency because
   the builder takes an `OpenAIClientAsync`.
3. **Configure and run.** The three environment variables the sample uses, the default base URL and
   model, and the exact failure text for a missing key. A short Java snippet showing the host
   building the SDK client, passing it to the builder, and closing it. State that the sample
   registers one local `current_utc_time` tool and that its default prompt asks for it, so the
   default run exercises the function-tool loop; state equally plainly that whether a live model
   calls the tool is the model's decision, and that the footer's `toolCalls` count is how a reader
   sees which path ran.
4. **Ownership.** The client is borrowed. The adapter creates no client, thread, connection, or
   shutdown hook, and has no `close()`. Retry belongs to the SDK's `maxRetries` and to the host, not
   to the adapter: the adapter dispatches one call once, and `requestTimeout` bounds one attempt of
   it, so an abandoned call costs the timeout times the host's attempt count, plus the SDK backoff.
5. **Limitations**, each stated as a fact rather than a roadmap promise:
   - Cancellation does not abort the in-flight HTTP request, and neither does cancelling the future
     taken from the returned stage; a timeout bounds it, per attempt.
   - No streaming, Responses API, structured output, embeddings, vision, audio, `tool_choice`, or
     Azure-specific credential and deployment handling.
   - Per-run `ModelRequestOptions` do not reach a provider yet (G1); the builder defaults are the
     only way to set temperature and token limits today.
   - Provider options are rejected rather than ignored, until a typed OpenAI option surface exists.
   - Tool-call arguments must be a JSON object (G3). A `null` *value* inside that object is kept as
     a null-valued key, so an explicitly nulled optional parameter is not confused with an omitted
     one.
   - Tool-call arguments are parsed strictly, and each of these is a named failure rather than a
     silent repair, because every alternative would hand a tool a call the model did not make:
     input after the first JSON value (`{"a":1}{"b":2}`, `{"a":1} oops`) and a repeated key
     (`{"city":"Seoul","city":"Busan"}`). Say that a parse failure names the tool, the call id, and
     the structural requirement, quotes no part of the arguments, and carries no parser exception
     as a cause or as a suppressed throwable, so nothing a logger prints contains model output. Say
     plainly what that costs: the parser's own wording and the failing column are not reported.
   - A turn whose finish reason is `tool_calls` or the deprecated `function_call` but which carries
     no tool call fails, because the engine ends its loop on an empty tool-call list and the run
     would otherwise report a successful final answer the model never gave. State the deliberate
     asymmetry in the same breath: a tool call that arrives under `stop` or `length` is mapped and
     the finish reason is reported as the server sent it.
   - The deprecated `function_call` message payload is rejected rather than mapped: it carries no
     call id, and a synthesised one would key a tool result to a call that never existed. Name the
     remedy, which is to configure the model with tools rather than functions.
   - A server that returns a partial `usage` object surfaces `OpenAIInvalidDataException`; usage is
     not guessed.
   - A tool result marked as an error is sent as its text, because Chat Completions has no error
     flag on a tool message.
   - No request-id or rate-limit metadata yet (G4).
   - The module publishes a Jackson platform on `api`, so a consumer inherits an alignment of every
     Jackson module at the repository pin and must override it explicitly to run an older Jackson.
     That is deliberate: without it a consumer would compile against the SDK's Jackson and run
     against this repository's.
6. **Test evidence.** Name the nine test classes and what each proves, and state that the suite is
   deterministic, offline, and credential free, so a reader can verify the claim by running
   `./gradlew :providers:agent-framework-openai:test` with no environment set.
7. **Packaging notes.** `openai-java-core-4.51.0.jar` is about 53 MB, `kotlin-reflect` is required at
   runtime and arrives only through `jackson-module-kotlin` so it must never be excluded, and the
   module aligns the SDK's Jackson modules to the repository pin.

Before moving on, read the limitations list back against the **fail** rows of the response mapping
table above and confirm that every rule a caller can hit at runtime appears in one of the two
places. A limitation the README omits is a behaviour a caller meets first as an exception in
production, which is the failure mode PRV-010 exists to prevent. The five rules this check exists
for, because each was added after the mapping table was first written and each is easy to leave
out, are: strict argument parsing (input after the first JSON value, and a repeated key), a
`tool_calls` or `function_call` finish reason with no tool call, the deprecated `function_call`
message payload, the deliberate acceptance of tool calls under a non-tool finish reason, and the
sanitised parse failure that attaches no parser exception as a cause or as a suppressed throwable.
Task 13 step 6 checks the same list from the reviewer's side.

- [ ] **Step 2: Update the traceability matrix**

Replace the five provider rows. Before:

```markdown
| PRV-001 | The core does not depend directly on a provider SDK | [04-hosting-operations-providers.md](04-hosting-operations-providers.md) | `providers/agent-framework-*`, provider contract test | `absent` | provider contract + integration test |
| PRV-002 | Providers and integrations are separated per artifact | [04-hosting-operations-providers.md](04-hosting-operations-providers.md) | `providers/agent-framework-*`, provider contract test | `absent` | provider contract + integration test |
| PRV-004 | The adapter porting priority is fixed from P0 to P4 | [04-hosting-operations-providers.md](04-hosting-operations-providers.md) | `providers/agent-framework-*`, provider contract test | `absent` | provider contract + integration test |
| PRV-007 | Provider-specific features are exposed as optional capabilities | [04-hosting-operations-providers.md](04-hosting-operations-providers.md) | `providers/agent-framework-*`, provider contract test | `absent` | provider contract + integration test |
| PRV-010 | An adapter provides maturity, README, and test evidence together and preserves its facade | [04-hosting-operations-providers.md](04-hosting-operations-providers.md) | `providers/agent-framework-*`, provider contract test | `absent` | provider contract + integration test |
```

After:

```markdown
| PRV-001 | The core does not depend directly on a provider SDK | [04-hosting-operations-providers.md](04-hosting-operations-providers.md) | `providers/agent-framework-openai` | `implemented` | the adapter implements the neutral `ModelClient`, and a policy test reads the api, engine, and testkit lockfiles and fails if a provider SDK resolves onto them |
| PRV-002 | Providers and integrations are separated per artifact | [04-hosting-operations-providers.md](04-hosting-operations-providers.md) | `providers/agent-framework-openai` | `implemented` | one artifact per provider, its own lockfile, and a module policy that allowlists each module's dependencies exactly |
| PRV-004 | The adapter porting priority is fixed from P0 to P4 | [04-hosting-operations-providers.md](04-hosting-operations-providers.md) | `providers/agent-framework-openai` | `partial` | the P1 OpenAI adapter ships; the rest of the priority table has no executable evidence yet |
| PRV-007 | Provider-specific features are exposed as optional capabilities | [04-hosting-operations-providers.md](04-hosting-operations-providers.md) | `providers/agent-framework-openai` | `partial` | no core method grew and the adapter owns its options; provider options are rejected rather than ignored, and no typed OpenAI option surface exists yet |
| PRV-010 | An adapter provides maturity, README, and test evidence together and preserves its facade | [04-hosting-operations-providers.md](04-hosting-operations-providers.md) | `providers/agent-framework-openai` | `partial` | maturity marker, README, and named deterministic test evidence ship; the facade-preservation criterion has no subject until a repo split or a move happens |
```

Then update AGT-009's verification column to name the provider evidence, keeping its `implemented`
status:

```markdown
| AGT-009 | Model calls are separated behind the `ModelClient` port | [01-core-execution.md](01-core-execution.md) | `api.agent`, `spi.model`, `engine.internal.run` | `implemented` | unit + agent contract + golden, now including a real provider adapter that reaches the engine only through `ModelClient` |
```

Do not touch AGT-011 and AGT-012: both stay `partial`, because the engine still passes
`ModelRequestOptions.empty()` and `AgentRunOptions` still carries no model options. Do not touch
AGT-015: this slice ships no streaming. Do not add or remove rows; the matrix invariant is 244 IDs.

- [ ] **Step 3: Update the module composition sample row**

The `:samples:sample-standalone` row still says "explicit model-client assembly", which now
understates it. Change the responsibility to "Runnable standalone `Agent.run` example over a real
OpenAI-compatible endpoint, with one local function tool." and add
`:providers:agent-framework-openai` to its allowed dependencies.

- [ ] **Step 4: Update the root README**

Four edits:

- The layout tree gains `providers/agent-framework-openai/`, and the sentence listing planned
  grouping directories drops `providers/`.
- "Run a standalone agent" currently says the sample assembles "an explicit deterministic
  `ModelClient`" and runs "without a server, dependency-injection container, provider credentials,
  or global registry". Both halves become false. It now states that the sample calls a real
  endpoint, lists the three environment variables with their defaults, says it registers one local
  `current_utc_time` tool that the default prompt asks for, shows the export plus `./gradlew
  :samples:sample-standalone:run --args="hello"`, and replaces the old expected output with a
  description of what is printed: the model's reply and a non-sensitive footer whose `toolCalls`
  count shows whether the model used the tool. Do not paste a fabricated model reply; the output is
  not deterministic any more.
- "Current state": the standalone sample bullet becomes a real-provider bullet, a bullet for the
  OpenAI Chat Completions adapter is added, "Four published product modules" becomes five, and
  "direct provider integrations" is removed from "Not started" while streaming for the provider and
  Spring hosting stay listed.
- The closing sentence "The next implementation stages add provider adapters and host integration"
  now overlaps what shipped; narrow it to host integration, streaming, and further providers.

- [ ] **Step 5: Correct the Korean entry point**

`docs/ko/README.md` is a first-class entry point and `DocumentationLanguagePolicyTest` only checks
that it exists, is written in Hangul, and links back, so a false claim there ships silently. Two
sentences become wrong with this slice, and both must be rewritten in Korean. Locate them by the
English meaning rather than by copying Korean text into this plan, which is a canonical English
document that the same policy forbids Korean text in:

- In the "current state" paragraph, the sentence that claims `samples/sample-standalone` can run
  `agent.run(...)` *without an external service*. After this task the sample requires
  `OPENAI_API_KEY` and calls a real endpoint, so state that instead, and mention the local
  `current_utc_time` tool the default prompt asks for.
- In the opening paragraph, the sentence listing *provider adapter* among the follow-up stages
  alongside session persistence and host integration. Session persistence already shipped and one
  provider adapter now ships; leave host integration and streaming as the follow-up.

Find both with `grep -n 'sample-standalone' docs/ko/README.md` and by reading the first and the
"current state" paragraphs; there is no other claim to change.

The module list line for `samples/sample-standalone` says the sample runs without a server or a
dependency-injection container, which stays true: it still needs neither. Do not rewrite what is
still correct, do not translate the sample's failure message, and keep environment variable names in
Latin script.

- [ ] **Step 6: Note the credential requirement in the getting started guide**

Add one short paragraph: the whole verification contract still runs with no credentials, and
`:samples:sample-standalone:run` is the only task that needs `OPENAI_API_KEY`. Tests never call a
network.

- [ ] **Step 7: Run the policy suite**

Run: `./gradlew policyCheck`

Expected: PASS. This is the step that most easily breaks: `DocumentationLanguagePolicyTest` requires
English outside `docs/ko` and Hangul inside it, `MarkdownLinkPolicyTest` resolves every local link in
every document under `docs/`, the repository root, and `.github`, and `ModuleCompositionPolicyTest`
requires every registered Gradle path to appear in `docs/design/module-composition.md`. Keep the
matrix rows in the existing
`[04-hosting-operations-providers.md](04-hosting-operations-providers.md)` form and do not turn a
file path into a link.

Note that `providers/agent-framework-openai/README.md` is outside the scanned locations, so the link
policy does not check it. Write its links correctly anyway.

- [ ] **Step 8: Commit**

```bash
git add providers/agent-framework-openai/README.md \
        docs/design/requirements-design/requirements-traceability-matrix.md \
        docs/design/module-composition.md docs/operations/getting-started.md README.md \
        docs/ko/README.md
git commit -m "$(cat <<'MSG'
docs: record the OpenAI Chat Completions adapter

Adds the adapter README with its Preview maturity, install and run instructions,
borrowed-client ownership, the full limitation list including the published
Jackson alignment, and the deterministic test evidence PRV-010 asks for. PRV-001
and PRV-002 move to implemented with the lockfile and module policy that prove
them. PRV-004, PRV-007, and PRV-010 move to partial: one P1 adapter is not the
whole priority table, no typed OpenAI option surface exists yet, and facade
preservation has no subject until a repo split. AGT-011 and AGT-012 stay partial
because the engine still cannot carry request options to a provider, and AGT-015
is untouched because no streaming ships here. The root and Korean entry points
stop claiming the standalone sample runs without an external service.

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
MSG
)"
```

---

### Task 13: Final verification, review loop, and the user review milestone

**Files:** none. This task runs the contract and opens the pull request.

- [ ] **Step 1: Run the repository policy suite**

Run: `./gradlew policyCheck`

Expected: PASS.

- [ ] **Step 2: Run the quality suite**

Run: `./gradlew quality`

Expected: PASS. Spotless applies google-java-format, Checkstyle allows zero warnings, PMD runs the
eight-rule ruleset, and SpotBugs runs at maximum effort with medium confidence.

Nothing new should appear here. Tasks 4, 6, 7, 8, and 11 each ran their module's `quality` task,
which is deliberate: a static-analysis wave met for the first time at the end of a branch is the
situation in which a broad suppression looks reasonable. If a finding does appear now, it is a
finding in code those gates did not cover; treat it as new work, not as a cleanup pass. Fix the code
where the finding is real, and where a stored reference is genuinely the design, add one `Match`
naming the exact class and method with a comment explaining why, as Task 8 step 8 describes. Never a
package-level match, never a class-wide `Match` without a method, never `@SuppressWarnings`, and
never a batch of matches added together "to get quality green".

If Spotless reports a formatting violation, run `./gradlew spotlessApply` and re-run.

- [ ] **Step 3: Run the compatibility test matrix**

Run: `./gradlew testJava17 testJava21 testJava25`

Expected: PASS on all three toolchains. The SDK targets Java 8 bytecode and its own matrix covers
17, 21, and 25, so a failure on exactly one JDK is a real defect rather than a toolchain gap. Do not
respond by widening a timeout: the whole suite is scripted futures with no wall-clock dependency, so
a timing difference means an ordering assumption, not slowness.

- [ ] **Step 4: Run the full build**

Run: `./gradlew check`

Expected: PASS.

- [ ] **Step 5: Prove the suite needs no credentials and no network**

Run: `env -u OPENAI_API_KEY -u OPENAI_BASE_URL -u OPENAI_MODEL ./gradlew check --rerun-tasks`

Expected: PASS. If anything fails only when the environment is empty, a test is reading the real
environment or reaching a network, and it must be fixed rather than skipped or tagged.

- [ ] **Step 6: Review the diff for contract impact**

Run: `git diff --stat main...HEAD` and `git diff main...HEAD -- '*/src/main/java/*'`

Check each of these and write the answer in the pull request body:

- **Public API:** exactly one new public supported type, `OpenAiChatModelClient`, with its nested
  `Builder`. `ChatCompletionsOperations`, `SdkChatCompletionsOperations`, `OpenAiChatSettings`,
  `ChatCompletionRequestMapper`, `ChatCompletionResponseMapper`, and `OpenAiCallBridge` live in
  `io.github.hellices.agentframework.openai.internal`, which the package documentation declares
  unsupported, so the pull request body must not describe them as public API. The sample's
  `createAgent()` becomes `createAgent(ModelClient, Clock)`; the sample is not published, so this is
  not a compatibility event, but say it plainly. Nothing in `agent-framework-api` or
  `agent-framework-engine` changed shape.
- **Dependencies:** two new catalog entries, `openai-java` and `jackson-bom`, both new lines rather
  than one new and one existing; one new module with its own lockfile; the sample lockfile grows by
  the SDK graph and gains Jackson on its compile classpath. The Jackson platform is declared on
  `api`, so the constraint is published: the POM gains a `<dependencyManagement>` import and the
  Gradle metadata carries it in both the `apiElements` and `runtimeElements` variants, which is the
  same shape `agent-framework-mcp` already publishes for `mcp-bom`. Say so in the body rather than
  describing Jackson as an internal detail. Confirm the core lockfiles are untouched with
  `git diff main...HEAD -- agent-framework-api/gradle.lockfile agent-framework-engine/gradle.lockfile
  agent-framework-testkit/gradle.lockfile`, which must be empty, and confirm no Jackson skew with
  `grep '^com.fasterxml.jackson' providers/agent-framework-openai/gradle.lockfile
  samples/sample-standalone/gradle.lockfile | grep '2\.18\.'`, which must print nothing.
- **Session format:** unaffected; this slice persists nothing.
- **Telemetry:** unaffected; this slice emits nothing and logs nothing. The sample prints the model's
  reply and a footer that carries the model name, the finish reason, a tool-call count, and token
  counts, and never the prompt, the tool arguments, or the tool output.
- **Compatibility:** additive for every published artifact. The BOM gains one constraint, and the
  published-BOM contract test now covers the new artifact.
- **Requirements:** PRV-001 and PRV-002 implemented; PRV-004, PRV-007, and PRV-010 partial; AGT-009
  still implemented with new provider evidence; AGT-011 and AGT-012 still partial and why; AGT-015
  untouched.
- **Scope honesty:** the body states that the live sample run exercises the function-tool loop by
  default because the sample registers a tool and asks for it, and that whether the model calls it
  is the model's decision. Do not write that the live run proves the loop; Task 10 proves the loop.
- **Documented failure rules:** every **fail** row of the response mapping table appears either in
  the adapter README's limitations list or in the mapper's javadoc. Check the four that were added
  after the table was first written by name: strict argument parsing (input after the first JSON
  value, and a repeated key), a `tool_calls` or `function_call` finish reason carrying no tool call,
  the deprecated `function_call` message payload, and tool calls accepted under a non-tool finish
  reason with the server's own finish reason reported. Check the fifth the same way: an argument
  parse failure carries no parser exception anywhere in its chain, so neither the README nor the
  javadoc may promise a preserved cause. A rule that only exists in a test is a rule a caller meets
  first as an exception in production.

- [ ] **Step 7: Push and run the review loop**

```bash
git push -u origin feat/openai-chat-provider
gh pr create --fill
gh pr review --request-reviewer Copilot 2>/dev/null || gh pr edit --add-reviewer Copilot
```

Then follow the review loop in `AGENTS.md`: wait for the review rather than assuming silence is
approval, reply to each inline comment with what changed and why, or with why the suggestion was
declined or implemented differently, resolve the thread, and repeat from the push step whenever a
response required a push. A green pipeline is not evidence that a review is unnecessary; every
defect found in this repository so far was found while all checks were passing.

- [ ] **Step 8: Merge and stop**

Merge with squash so `main` receives one coherent commit, and make sure the pull request title
describes that squashed commit and the body records the completed change.

Then **stop**. This is the user review milestone. The user runs, against a real endpoint:

```bash
export OPENAI_API_KEY=...
./gradlew :samples:sample-standalone:run
./gradlew :samples:sample-standalone:run --args="hello"
```

The first run uses the default prompt, which asks for the registered `current_utc_time` tool, so it
is the one that exercises the function-tool loop end to end: the model asks for the tool, the sample
answers from its clock, and the reply comes back on a second request. The footer reports
`toolCalls=`, which is how that is visible without turning on any logging. The second run is an
arbitrary prompt and will normally show `toolCalls=0`, which is the ordinary response path.

`toolCalls=0` on the first run is a model decision, not an adapter defect. A smaller or a
non-OpenAI compatible model may answer the question from its own knowledge. If that happens, the
loop is still proved by Task 10's offline test, and the live check to repeat is that a request with
`tools` was sent and the reply arrived; an adapter defect would surface as an exception or a
malformed request, not as a model declining to call a tool.

Review the result before any streaming, Responses, or Spring Boot work is planned. Do not start the
next slice.

---

## Plan self-review

This section records the review of the plan against the brief, so a reviewer can check the reasoning
rather than re-deriving it.

### Coverage of the brief

| Decision in the brief | Where it lands |
| --- | --- |
| New artifact `providers/agent-framework-openai` on official `com.openai:openai-java:4.51.0` | Task 2 |
| Chat Completions async only; no Spring AI, raw protocol, Responses, Azure, streaming, vision, audio, embeddings, structured output | Global Constraints, Task 12 README limitations |
| Public facade is final `OpenAiChatModelClient` with a nested `Builder` taking a borrowed client and a required model | Task 8 |
| Adapter never creates or closes an SDK client; the host owns OkHttp, executors, and resources | Task 8, Task 11 |
| SDK is `api` because it is on the public surface; Jackson is `implementation` behind an `api` platform | Task 2 |
| No provider SDK reaches api or engine | Task 2 policy test on the core lockfiles |
| Internal one-method `ChatCompletionsOperations` seam, SDK bridge, request mapper, response mapper, cancellation and error helper | Tasks 3 to 8 |
| No network, mock server, or test-server dependency | Global Constraints, Task 8 fake, Task 13 step 5 |
| Ordinary text and function-tool loop both work | Tasks 3 to 6, Task 10 offline, Task 11 in the live sample |
| Request mapping: five roles, deterministic text join, echo reconstruction, tool-message fan-out, JSON-schema tools, explicit rejections | Tasks 3 and 4, request mapping table |
| Response mapping: exactly one choice, ordered text and tool calls, argument rules, finish reasons, usage, metadata, raw object, null continuation | Tasks 5 and 6, response mapping table |
| Builder defaults with a positive request timeout and optional temperature and token limit; request options win; provider options rejected | Tasks 3 and 8 |
| AGT-011 and AGT-012 stay partial with no metadata side channel | Global Constraints, recorded gap G1, Task 12 |
| Cancellation: pre-cancel never dispatches, post-dispatch fails promptly and de-registers, no hard cancellation claim, no completion authority handed to a caller, a timeout bounds each attempt of abandoned work, exceptions preserved, no adapter retry | Cancellation contract, Tasks 7 and 9 |
| Sample moves from a fake to the real provider, keeps an injectable deterministic entry point, resolves three environment variables, fails explicitly on a missing key, owns and closes the client, registers one local tool the default prompt asks for | Task 11 |
| Provider README with maturity, install, environment, run, ownership, limitations, test evidence, packaging facts | Task 12 |
| Traceability updates for PRV-001, PRV-002, PRV-004, PRV-007, PRV-010, AGT-009, AGT-011, AGT-012, AGT-015 | Task 12 |
| Build: settings, catalog, BOM, policy, module docs, provider build file, lockfiles, sample dependency and lock | Tasks 1, 2, 11, 12 |
| Whether provider tests may use `testImplementation` on the engine | Module and build policy decision, Tasks 1 and 2 |
| Static analysis is met next to the code that causes it, never as one deferred wave | Tasks 4, 6, 7, 8, and 11 quality gates; Task 13 step 2 |
| Tests are TDD and deterministic, covering the mapping table, cancellation races, listener cleanup, exception preservation, two-choice failure, the engine tool loop, the sample environment, the sample tool loop, the public surface, laziness, and ownership | Tasks 3 to 11 |
| The user review milestone | Task 13 step 8 |

### SDK signatures

Every SDK type, method, and required builder field named in this plan was verified against tag
`v4.51.0` rather than taken from the research report. Three claims in that report were wrong or
unverified and are corrected in "Corrections to the research report": `model(String)` exists,
`params.tools()` is a list of `ChatCompletionTool` rather than of function tools, and defensive
usage reading is neither testable nor allowed here. The non-obvious required fields of
`ChatCompletionMessage.Builder`, `Choice.Builder`, and `ChatCompletion.Builder` are recorded as fact
16 and centralised in one test fixture, because they are the most likely cause of a test that will
not compile.

Two accessors carry residual risk. `RequestOptions.timeout` is a Kotlin `val`, so Java sees
`getTimeout()`; if the generated name differs, assert the timeout through `Timeout.request()` on
whatever accessor exists rather than deleting the assertion. `JsonNode.properties()` exists on
Jackson 2.22 but not on much older versions; the alternative is given inline in Task 6, and neither
alternative is an unchecked cast.

### Task boundaries

Each task ends green, ends with one commit, and touches one concern: policy, registration, settings
and role mapping, tool mapping, response mapping, tool-call mapping, the async bridge, the facade,
cancellation, the engine proof, the sample, documentation, and verification. Task 5 deliberately
leaves a named stub that Task 6 replaces, and the plan says so and says not to leave it behind. No
test is written in one task and rewritten in another: the tool-message failure case is introduced in
Task 4, where the code that produces its message exists, rather than being asserted loosely in
Task 3 and tightened later.

Static analysis is part of a task rather than a phase after all of them. Tasks 4, 6, 7, 8, and 11 run
their module's `quality` gate, so the only work left for Task 13 step 2 is confirmation. That
ordering exists because a wave of findings met at the end is what makes a broad suppression look
like a reasonable response. Task 5 is the one task that deliberately does not run the gate, because
the stub it leaves has two unused private parameters that PMD would report; Task 6 replaces the stub
and runs the gate.

### No placeholders

No task says "implement the rest", and no test is written to pass trivially. Every RED step names
the compilation error or the assertion that fails, and every GREEN step names an expected count that
can be checked against the XML report. The one intentional stub is bounded to a single task.

Every production behaviour in this plan is preceded by a failing test in the same or an earlier
task. There is no characterization test in this slice: nothing here documents existing behaviour
without changing it, so no test is exempt from the RED-first rule. Two proofs are worth naming
because they are easy to write the wrong way round. The JSON-null argument case is a RED test before
`Collections.unmodifiableMap` exists, not a note added after `Map.copyOf` threw. And the mutation
proof in Task 2 asserts that the production allowlist assertion *fails* on a mutated build file,
which cannot pass by accident.

### Type consistency

`ModelResponse.finishReason` is non-null, so the mapper always produces one, with `UNKNOWN` as the
explicit fallback. `ModelResponse.usage` is nullable and the engine tolerates null, so absent usage
maps to null rather than to zeroes. `ToolCallContent` and `ToolResultContent` reject a blank call id
or name, so the response mapper checks first and fails with a message that explains the provider
problem instead of surfacing a core value-type failure. `ToolDefinition.description` is normalised to
an empty string rather than null, so the mapper tests for blank rather than for null.
`ModelRequestOptions.maxOutputTokens` is an `OptionalInt` while the SDK takes a `long`, so the
widening is explicit at the call.

Null tolerance is the one place where the core types and the obvious JDK idiom disagree.
`ToolCallContent` stores arguments as `Collections.unmodifiableMap(new LinkedHashMap<>(...))` and
`ToolArguments` does the same, so both keep a null value; `Map.of` and `Map.copyOf` reject one. The
response mapper therefore never uses `Map.copyOf` for parsed arguments. `metadataOf` keeps
`Map.copyOf` because `id()`, `model()`, and `created()` are all non-null.

### Sensitive data

No prompt, model output, or tool argument is logged, printed, or embedded in an exception message.
Two tests assert the negative directly: extension content fails naming only its type, and invalid
tool arguments fail naming only the tool and the call id. A third walks the whole failure chain of a
parse failure - causes and suppressed throwables - for input Jackson would quote back, which is why
an argument parse failure carries no parser exception at all rather than a redacted one. The sample
prints the model's reply
because that is the point of a sample, and its footer carries only the model name, the finish
reason, a tool-call count, and token counts, which a test pins by exact string equality. The
sample's own tool reads a clock and returns a timestamp; it takes no argument, opens nothing, and
prints nothing, so registering it in a sample someone runs with their own credentials adds no
exposure. No test uses a real credential and no workflow gains one.

### Tool-call round trip

The round trip is closed and proved end to end in Task 10: the response mapper attaches the SDK
`ChatCompletionMessage` to the assistant `Message`, the engine preserves that message through
`echoedMessages` for a non-streamed response, and the request mapper prefers it so the `arguments`
string returned to the model is byte-identical. The reconstruction fallback exists for a
caller-built or restored history and is tested separately. The engine's single `Role.TOOL` message
fans out into one OpenAI tool message per call id, asserted both at the mapper and through the real
loop, so dropping the fan-out fails two tests rather than none.

### Cancellation

The claim is deliberately narrow. Pre-cancellation never dispatches, post-dispatch cancellation
fails the returned stage promptly, the listener is removed on every completion path, and the
in-flight HTTP request is not aborted. The last point is stated in the package documentation, in the
README, and in a test method name, so it cannot be quietly deleted. Listener removal is asserted
through a narrow cancellation seam because `CancellationSignal` cannot report it, which is the
difference between proving cleanup and assuming it. Both cancellation races are covered in both
orders.

### Dependency and public API impact

The provider's production dependencies are `:agent-framework-api`, the SDK as `api`, and Jackson as
`implementation` behind a platform declared on `api`. That platform is the one deliberately public
constraint this slice adds: it is published in the POM as a `<dependencyManagement>` import and in
both Gradle metadata variants, so a consumer inherits Jackson alignment at the repository pin and
must override it explicitly to run an older Jackson. The alternative, a platform on `implementation`,
leaves the constraint in `runtimeElements` only and makes every consumer compile against the SDK's
Jackson and run against this repository's, which is a worse and quieter outcome. `agent-framework-mcp`
already publishes the same shape for `mcp-bom`.

The engine is a test-only dependency, which the policy now allowlists separately so it cannot
silently become a production one, and Task 2's mutation test proves that flipping it to
`implementation` fails the production allowlist. The core lockfiles must not move, and a policy test
fails if a provider SDK ever resolves onto them. The published surface grows by exactly one class
and its builder, and a reflection test pins the builder's public method names so the operations seam
cannot drift into the supported API. The published BOM contract test gains the new artifact, so the
BOM constraint has published evidence rather than a build file match.

### Known risks

| Risk | Response |
| --- | --- |
| An SDK accessor name differs from the verified form and a test will not compile | Every name was read at tag `v4.51.0`; the two residual cases are named above with a non-weakening alternative |
| SpotBugs flags storing the borrowed client or the operations port | Task 8 step 8 runs the gate where those types are introduced, pre-lists the candidate sites, and requires an unfiltered report before any `Match` is written |
| The `api` Jackson platform surprises a consumer that pins an older Jackson | Stated in the build file comment, in the adapter README limitations, and in the pull request body; a consumer overrides it with its own platform or a `strictly` version |
| The 53 MB core jar surprises someone reading the sample distribution | Documented in the README packaging notes |
| `kotlin-reflect` is excluded by a future dependency cleanup and the SDK fails at its first request | Documented in the README and visible in both lockfiles |
| A reviewer reads the new test-only allowlist as a weakening of the dependency rules | Task 1 lands separately, with its own self-test and its own commit message explaining that the production allowlist got stricter, not looser, and Task 2 adds a mutation test that fails when a production engine dependency is declared |
| The live milestone run shows `toolCalls=0` and is read as a broken tool loop | The Goal, the sample javadoc, the README, and Task 13 step 8 all say tool selection is the model's decision; the loop itself is proved offline in Task 10 and in the sample's own test |
| The sample now needs a credential, so someone adds a CI job that runs it | Task 13 step 5 proves the whole `check` runs with an empty environment; a live smoke run, if ever wanted, belongs in a manually triggered workflow outside `check` |
