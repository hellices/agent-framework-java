# agent-framework-openai

Calls OpenAI Chat Completions through the framework's neutral `ModelClient` port, over an
`OpenAIClientAsync` the host builds and owns.

> **Community project.** This module is not an official Microsoft or OpenAI product and is not
> endorsed by either. It publishes under the community-owned group and package
> `io.github.hellices.agentframework` so it cannot be mistaken for, or collide with, an official
> Java SDK.

## Maturity: Preview

The supported surface is one class and its builder: `OpenAiChatModelClient` and
`OpenAiChatModelClient.Builder`. Everything under `io.github.hellices.agentframework.openai.internal`
is implementation detail and may change or disappear without notice.

Preview means exactly that: **the public surface may change before the first release**, including
builder method names, defaults, and the exception types listed below. Pin the version you build
against and read the changelog before upgrading. Nothing here is covered by a compatibility promise
yet.

## Install

The repository version is `0.1.0-SNAPSHOT` and no release has been published, so the snippets below
describe the coordinates the build produces. `./gradlew publishAllPublicationsToBuildDirectoryRepository`
writes them into `build/maven-repository` if you want to consume them locally.

Gradle:

```kotlin
dependencies {
    implementation(platform("io.github.hellices.agentframework:agent-framework-bom:0.1.0-SNAPSHOT"))
    implementation("io.github.hellices.agentframework:agent-framework-openai")
    implementation("io.github.hellices.agentframework:agent-framework-engine")
}
```

Maven:

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.github.hellices.agentframework</groupId>
      <artifactId>agent-framework-bom</artifactId>
      <version>0.1.0-SNAPSHOT</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependencies>
  <dependency>
    <groupId>io.github.hellices.agentframework</groupId>
    <artifactId>agent-framework-openai</artifactId>
  </dependency>
  <dependency>
    <groupId>io.github.hellices.agentframework</groupId>
    <artifactId>agent-framework-engine</artifactId>
  </dependency>
</dependencies>
```

`com.openai:openai-java:4.51.0` arrives as an **`api`** dependency, not `implementation`: the builder
takes an `OpenAIClientAsync`, so a consumer cannot call it without the SDK on its own compile
classpath. It is a transitive compile dependency by design, not by accident.

## Configure and run

The standalone sample, `:samples:sample-standalone`, is the runnable reference. It reads three
environment variables:

| Variable | Required | Default |
| --- | --- | --- |
| `OPENAI_API_KEY` | yes | none |
| `OPENAI_BASE_URL` | no | `https://api.openai.com/v1` |
| `OPENAI_MODEL` | no | `gpt-4.1-mini` |

`OPENAI_BASE_URL` is what makes an OpenAI-compatible endpoint — a local server, a gateway, a proxy —
reachable through the same code. The adapter itself reads no environment variable at all: it takes a
client and a model name, and the sample is what maps the environment onto them.

Run it:

```bash
export OPENAI_API_KEY="<your-api-key>"        # never commit this value
export OPENAI_BASE_URL="https://api.openai.com/v1"   # optional
export OPENAI_MODEL="gpt-4.1-mini"                   # optional

./gradlew :samples:sample-standalone:run --args="hello"
```

With no key set, the sample fails before any client is built, with exactly:

```text
OPENAI_API_KEY is not set. Export a key (and optionally OPENAI_BASE_URL / OPENAI_MODEL) before running the sample.
```

A blank key is treated as a missing one. There is no demo mode, no fallback model, and no fake: a
sample that answered without a key would teach that a call succeeded when it never happened.

Assembling the adapter in a host looks like this:

```java
OpenAIClientAsync client =
    OpenAIOkHttpClientAsync.builder()
        .apiKey(System.getenv("OPENAI_API_KEY"))
        .baseUrl("https://api.openai.com/v1")
        .build();
try {
  ModelClient modelClient =
      OpenAiChatModelClient.builder()
          .client(client)          // borrowed: this adapter never closes it
          .model("gpt-4.1-mini")   // required
          .temperature(0.2)        // optional default
          .maxOutputTokens(512)    // optional default
          .requestTimeout(Duration.ofSeconds(60)) // optional, 60s when unset
          .build();

  ModelCatalog catalog =
      ModelCatalog.builder().add("openai", modelClient).defaultModel("openai").build();
  Agent agent = AgentEngine.factory(catalog).builder().id("assistant").build();
  AgentResponse response = agent.run("hello").response().toCompletableFuture().join();
} finally {
  client.close(); // the host built it, so the host closes it
}
```

The sample registers one local function tool, `current_utc_time`, on every request, and its default
prompt asks for that tool by name, so a bare `:samples:sample-standalone:run` exercises the whole
function-tool loop rather than a plain completion. **Whether a live model actually calls the tool is
the model's decision.** A run that answers directly is an ordinary response, not a regression. The
footer's `toolCalls=` count is how a reader sees which path ran.

The sample prints two lines: the model's answer, then a footer of the shape

```text
[model=<model> finishReason=<reason> toolCalls=<count> inputTokens=<count|n/a> outputTokens=<count|n/a>]
```

Two things about that output are easy to misread:

- The answer printed is the **terminal assistant round**, not the whole transcript. A model that
  narrates before it asks for a tool ("Let me check the clock.") does not have that preamble printed;
  it is still in `response.messages()`, which nothing discards.
- `inputTokens=n/a` and `outputTokens=n/a` mean the endpoint **reported no usage**, which is common
  on OpenAI-compatible servers. It is not an error, and it is not the same as a measured `0`.

Model output is not deterministic, so no exact reply is documented here. Every value in this
document is a redacted placeholder.

### Credentials and privacy

- `OPENAI_API_KEY` is a credential. Export it in your shell or read it from a secret manager; do not
  put it in a build file, a lockfile, a test, or a commit. All keys, URLs, and model names shown here
  are placeholders.
- Running the sample sends your prompt to whatever `OPENAI_BASE_URL` points at, and that endpoint's
  own retention and training policy applies. Nothing in this repository changes it.
- The adapter logs nothing. Its exception messages deliberately carry no prompt text, no model
  output, and no tool arguments, and neither does anything attached to them: neither the parser
  exception behind arguments it cannot read nor the serialiser exception behind arguments it cannot
  write is attached as a cause or as a suppressed throwable, because both quote part of the
  arguments — see the two argument limitations below, which exist so that a logger printing a stack
  trace cannot leak model output.
- That rule covers the failures this adapter raises. A failure raised by the provider is passed
  through as the SDK threw it, so its message is the server's own and is outside this adapter's
  control.
- The sample's footer carries counts only: never the prompt, the reply, or the tool's output.

## Ownership

**The SDK client is borrowed.** The adapter never builds, configures, reconnects, or closes an
`OpenAIClientAsync`. It creates no thread, no connection, no executor, and no shutdown hook, it has
no `close()` of its own, and discarding an instance releases nothing. The host that created the
client is the only holder that knows when nobody else is using it, so the host closes it. Instances
are immutable and safe to share once built.

**Retry belongs to the SDK and to the host, not to the adapter.** The adapter dispatches one call
once and never retries it. `maxRetries` on the client the host builds (two, by default, in the
official SDK) decides how many attempts there are, and `requestTimeout` on this builder bounds **one
attempt**. So an abandoned call costs the timeout times the host's attempt count, plus the SDK's
backoff between attempts. Budget for that product, not for the timeout alone.

## Limitations

Each of these is a fact about the shipped code, not a roadmap promise.

- **Cancellation does not abort the in-flight HTTP request.** Cancelling the run's signal fails the
  returned stage promptly and removes the listener, but the request already on the wire keeps
  running, and cancelling the future taken from the returned stage does not change that either —
  that future is a copy of the outcome, not the call. A timeout is what bounds the abandoned work,
  per attempt.
- **No streaming**, and no Responses API, structured output, embeddings, vision, audio,
  `tool_choice`, or Azure-specific credential and deployment handling.
- **Per-run `ModelRequestOptions` do not reach a provider yet** (G1). The engine still passes
  `ModelRequestOptions.empty()`, so the builder defaults are the only way to set temperature and
  token limits today.
- **Provider options are rejected rather than ignored.** A request carrying a non-empty
  `providerOptions()` fails, naming the provider ids present, until a typed OpenAI option surface
  exists. Silently dropping them would be worse.
- **A tool call that is not a function call fails, and so does a blank call id or a blank function
  name.** Chat Completions can also return a custom tool call
  (`ChatCompletionMessageCustomToolCall`), which this adapter does not map; a function call is
  expected to carry both an id and a name, and either arriving blank fails the same way. **No id or
  name is ever synthesised**: a synthesised id would key a tool result to a call the model never
  issued, and a synthesised name would invoke a tool the model never named. Each of the three is its
  own `IllegalStateException`, distinct from the argument-parsing failures below.
- **Tool-call arguments must be a JSON object** (G3). A `null` *value* inside that object is kept as
  a null-valued key, so `{"unit":null}` — an explicitly nulled optional parameter — is not confused
  with an omitted one.
- **Tool-call arguments are parsed strictly**, and each of the following is a named failure rather
  than a silent repair, because every alternative hands a tool a call the model did not make:
  - input after the first JSON value, such as `{"a":1}{"b":2}` or `{"a":1} oops`;
  - a repeated key, such as `{"city":"Seoul","city":"Busan"}`, where Jackson's default of last-wins
    would silently discard one of two values the model sent;
  - valid JSON that is not an object, and anything that is not valid JSON at all.

  A parse failure is an `IllegalStateException` naming the tool, the call id, and the structural
  requirement ("exactly one JSON object with unique keys"). It quotes no part of the arguments and
  carries **no parser exception as a cause and none as a suppressed throwable**, because Jackson
  names the token it rejected in its own message text, so attaching it would put model output into
  every log that prints a stack trace. What that costs is stated plainly: the parser's own wording
  and the failing column are not reported.
- **Arguments this adapter cannot write back fail the same way, and just as quietly.** Sending an
  assistant turn re-serialises its tool-call arguments whenever the turn arrives without the SDK
  message it came from — a history a caller built or restored, since arguments this adapter parsed
  are JSON values that always re-serialise. An argument value Jackson has no serialiser for fails
  with an `IllegalArgumentException` naming the tool and the call id, again with **no serialiser
  exception attached as a cause or as a suppressed throwable**, because Jackson appends the failing
  key to its own message (`through reference chain: ...["<key>"]`) and an argument key is part of
  the arguments. The cost, stated plainly: the Java type Jackson could not write is not reported.
- **A `tool_calls` or deprecated `function_call` finish reason with no tool call fails.**
  `AgentEngine` ends its loop on an empty tool-call list, so such a turn would otherwise be reported
  as a successful final answer the model never gave. The asymmetry is deliberate: a tool call that
  arrives under `stop` or `length` **is** mapped, and the finish reason is reported as the server
  sent it, because a call that is present is executable whatever the reason says.
- **The deprecated `function_call` message payload is rejected rather than mapped.** It carries no
  call id, and a synthesised one would key a tool result to a call that never existed. The remedy is
  to configure the model with tools rather than functions.
- **A completion with other than exactly one choice fails.** This adapter never sends `n`, so a
  second choice is an answer it cannot report, and the first is not silently preferred.
- **A partial `usage` object surfaces `OpenAIInvalidDataException`** from the SDK's typed accessors.
  Usage is never guessed; an absent `usage` object as a whole is reported as no usage at all, which
  is a different thing from zero.
- **A tool result marked as an error is sent as its text.** Chat Completions has no error flag on a
  tool message, so `ToolResultContent.error()` is dropped rather than disguised as an invented
  prefix the model would have to guess at.
- **No request-id or rate-limit metadata yet** (G4). The response metadata carries
  `openai.response.id`, `openai.response.model`, and `openai.response.created`, and nothing else.
- **The module publishes a Jackson platform on `api`.** A consumer therefore inherits an alignment of
  every Jackson module at this repository's pin (2.22.1) and must override it explicitly to run an
  older Jackson. That is deliberate: without it a consumer would compile against the Jackson the SDK
  POM declares and run against this repository's, which is exactly the skew the alignment removes.

## Test evidence

Nine test classes, 133 executed tests at the time of writing. The whole suite is **deterministic,
offline, and credential free**: no
test builds an SDK client, opens a socket, reads the process environment, sleeps, or retries. Verify
that claim by running it with nothing exported:

```bash
./gradlew :providers:agent-framework-openai:test
```

| Test class | What it proves |
| --- | --- |
| `OpenAiChatSettingsTest` | the adapter-owned defaults validate model, temperature, output token limit, and request timeout at construction, not on the wire |
| `ChatCompletionRequestMapperTest` | roles, text placement, options, and the refusals for an empty history, an unmappable role, and a provider option |
| `ChatCompletionRequestMapperToolsTest` | tool definitions and schemas on the request, one tool message per `tool_call_id`, the byte-exact echo of the SDK message, every unsendable-message refusal, and that arguments it cannot serialise fail with no part of them anywhere in the failure chain |
| `ChatCompletionResponseMapperTest` | choices, text, finish reasons including the deprecated wire value and unknown values, usage, metadata, and the raw handles |
| `ChatCompletionResponseMapperToolCallTest` | tool-call mapping and every strict-argument failure, the finish-reason-without-a-call rule and its deliberate asymmetry, the rejection of the deprecated `function_call` payload, and that no failure carries model output as a message, a cause, or a suppressed throwable |
| `OpenAiCallBridgeTest` | the cancellation seam: no dispatch when already cancelled, prompt failure after dispatch, listener removal on every completion path, no completion authority handed to the caller, the original failure instance preserved, and one dispatch with no retry |
| `OpenAiChatModelClientTest` | the assembled adapter: builder validation, defaults reaching the wire, the request timeout applied per call, mapping failures delivered through the stage rather than thrown, a `null` request thrown at the call site, that the client is borrowed and never closed, and that the supported builder surface stays small |
| `OpenAiChatModelClientCancellationTest` | the same cancellation contract observed from the public port, including that a late cancellation cannot overwrite a delivered answer |
| `OpenAiChatModelClientToolLoopTest` | the adapter and the real `AgentEngine` tool loop agree end to end — tool offered with its schema, called with the arguments the model produced, results echoed as tool messages, loop ended on the model's answer — with no transport of its own |

## Packaging notes

- `openai-java-core-4.51.0.jar` is about **53 MB**. That is the SDK's own size, not something this
  module adds, but it is large enough to matter in a container image or a shaded artifact.
- `kotlin-reflect` is required at runtime and reaches the classpath only through
  `jackson-module-kotlin`. **Never exclude it**; the SDK fails at runtime without it.
- The module aligns the SDK's Jackson modules to this repository's pin by publishing
  `com.fasterxml.jackson:jackson-bom` on `api`. See the last limitation above for why, and for what
  a consumer must do to run an older Jackson.

## Related documents

- [Repository README](../../README.md)
- [Module composition](../../docs/design/module-composition.md)
- [Requirements traceability matrix](../../docs/design/requirements-design/requirements-traceability-matrix.md)
- [Getting started](../../docs/operations/getting-started.md)
