# Agent Framework for Java

Bring the observable execution semantics of [Microsoft Agent Framework](https://github.com/microsoft/agent-framework)
to Java.

> **Community project:** This repository is not an official Microsoft product and is not endorsed by
> Microsoft. It uses the community-owned Maven group and Java package
> `io.github.hellices.agentframework` to avoid impersonating or colliding with a future official Java
> SDK.

This project does not build an application server or a dependency injection container. The
deliverable is an embeddable `AgentEngine`. A host runtime such as Spring Boot keeps owning object
lifecycle, execution resources, security, transactions, and observability configuration.

> **Status:** early. Deterministic single-agent execution, typed public-contract policy, the core
> function-tool loop, session persistence, and one Preview provider adapter (OpenAI Chat
> Completions) are implemented; streaming for that adapter and host integrations remain in progress.
> See [Current state](#current-state).
>
> **API stability:** pre-1.0. Public contracts may evolve between requirement slices while the core
> execution semantics are being established.

## Why this split

An agent runtime and an application runtime solve different problems. Merging them produces two
systems that both claim to own thread pools, configuration, and request lifecycle, and neither can
be tested without the other.

So the boundary is explicit:

| Concern | Owner |
| --- | --- |
| Model call and tool call state transitions | `AgentEngine` |
| Session state change rules | `AgentEngine` |
| Streaming event order and interruption policy | `AgentEngine` |
| Dependency injection, configuration, object lifecycle | Host runtime |
| Executors, schedulers, HTTP server | Host runtime |
| Security, transactions, resilience policy | Host runtime and integration modules |
| Provider API translation | Provider adapter |

The engine stays framework neutral, so the same agent semantics run under Spring Boot, Quarkus, a
CLI, or a plain test harness.

## Design principles

- Prefer compatibility of **observable behavior** over matching upstream API names.
- Implement agent execution as an embedded state machine with no application framework dependency.
- Treat Spring AI and every other integration as an **optional adapter**, never a foundation.
- Keep one repository with several published artifacts, aligned by a single version and a BOM.

## Quick start

Requires JDK 17. Compatibility tests additionally use Temurin 21 and 25.

```bash
git clone https://github.com/hellices/agent-framework-java.git
cd agent-framework-java
./gradlew check
```

Everything local and in CI runs through the committed Gradle Wrapper. There is no separate CI-only
verification path.

| Command | Purpose |
| --- | --- |
| `./gradlew policyCheck` | Repository, artifact, and workflow policy regression |
| `./gradlew quality` | Formatting and static analysis on JDK 17 |
| `./gradlew testJava17` | Tests on the Temurin 17 launcher |
| `./gradlew check` | Everything above plus the 21 and 25 compatibility runs |
| `./gradlew publishAllPublicationsToBuildDirectoryRepository` | Publish every artifact to `build/maven-repository` |

If Temurin 21 or 25 is missing locally, `testJava21` and `testJava25` fail with a toolchain error.
Run the narrower tasks and let CI cover the rest.

## Repository layout

Core modules sit at the root. Extension families that grow to many modules get a grouping
directory, the shape Spring AI and Spring Boot use at this scale.

```text
agent-framework-api/        Public contracts and value types
agent-framework-engine/     Embedded execution state machine
agent-framework-testkit/    Deterministic fixtures for tests
agent-framework-bom/        Version alignment for published artifacts
integrations/               Protocol and service integrations
  agent-framework-mcp/      MCP client tools, borrowed or owned connection
providers/                  Model provider adapters
  agent-framework-openai/   OpenAI Chat Completions over a borrowed SDK client
build-logic/                Gradle convention plugins
build-tools/harness-policy/ Executable repository policy
config/                     Checkstyle, PMD, SpotBugs configuration
docs/                       Requirements, design, upstream analysis
samples/sample-standalone/  Runnable standalone Agent.run example
.harness/                   Agent artifact JSON schemas
```

Planned grouping directories are `starters/`, `protocols/`, `workflow/`, and `compatibility-tests/`.
Each is created when its first module lands.

Module rules are defined in [module composition](docs/design/module-composition.md) and enforced by
`./gradlew policyCheck`.

## Run a standalone agent

The standalone sample assembles an `Agent` over the OpenAI Chat Completions adapter and calls
`agent.run(...)` against a real endpoint, with no server, dependency-injection container, or global
registry. It needs a credential, because a sample that answered without one would teach that a call
succeeded when it never happened.

| Variable | Required | Default |
| --- | --- | --- |
| `OPENAI_API_KEY` | yes | none |
| `OPENAI_BASE_URL` | no | `https://api.openai.com/v1` |
| `OPENAI_MODEL` | no | `gpt-4.1-mini` |

`OPENAI_BASE_URL` points the same sample at any OpenAI-compatible endpoint. The sample also registers
one local `current_utc_time` function tool, and its default prompt asks for that tool by name, so a
run with no arguments exercises the whole function-tool loop.

```bash
export OPENAI_API_KEY="<your-api-key>"   # a placeholder; never commit a real key
./gradlew :samples:sample-standalone:run --args="hello"
```

It prints two lines: the model's answer — the terminal assistant round, so a preamble the model
emitted alongside a tool call is not printed, though `response.messages()` still carries it — and a
non-sensitive footer of the shape
`[model=… finishReason=… toolCalls=… inputTokens=… outputTokens=…]`. The `toolCalls` count is how you
see whether the model used the tool, which is the model's decision, not the sample's. `n/a` token
counts mean the endpoint reported no usage, which is common on OpenAI-compatible servers and is not
the same as a measured `0`. Model output is not deterministic, so no exact reply is documented here.

The adapter's own maturity, limitations, and test evidence are in the
[OpenAI adapter README](providers/agent-framework-openai/README.md).

## Documentation

Start here depending on what you need.

| Question | Document |
| --- | --- |
| What must Java build? | [Requirements](docs/requirements/README.md) |
| How does each requirement map to Java architecture and code? | [Requirements-driven design](docs/design/requirements-design/README.md) |
| Is the public API idiomatic and extensible Java? | [Java API audit](docs/requirements/java-api-audit.md) |
| Why is it built this way? | [Foundation design](docs/design/foundation-design.md) |
| How does the upstream framework behave? | [Upstream snapshot analysis](docs/upstream/snapshots/d0a4165f/README.md) |
| How do modules relate? | [Module composition](docs/design/module-composition.md) |
| How is the repository verified? | [Engineering harness design](docs/design/engineering-harness-design.md) |
| How does the build work? | [Gradle and Java ARC foundation](docs/design/gradle-kotlin-arc-foundation-design.md) |
| Where is every document listed? | [Documentation index](docs/README.md) |
| How is documentation organized? | [Documentation language and information architecture](docs/design/documentation-language-policy.md) |

The requirements are the contract. 244 requirements across twelve documents, each with a stable id,
a .NET and Python comparison, the reasoning behind the Java decision, and acceptance criteria.

## Contributing

New here? Start with the [getting started guide](docs/operations/getting-started.md). It walks from
a clone to a merged pull request.

- [Repository instructions](AGENTS.md)
- [Contributing guide](CONTRIBUTING.md)
- [Security policy](SECURITY.md)
- [GitHub Actions runner contract](docs/operations/github-actions-runner-contract.md)

Pick a requirement id, write the failing test first, then implement the smallest change that
satisfies it. Reference the id in the commit message so the contract and the code stay linked.

## Current state

The repository has a verified foundation, a deterministic offline engine and provider test suite,
and a live standalone execution path over a real endpoint.

**In place**

- Gradle Kotlin DSL build with convention plugins and dependency locking
- Executable repository policy covering build contract, governance, workflows, and module structure
- Agent artifact JSON schemas under `.harness/`
- CI on the `arc-java-build` ARC scale set with a fork-safe verification path
- 244 requirements derived from a pinned upstream snapshot
- Five published product modules with a compiled and tested surface
- Executable public-contract policy: only reviewed fixed records remain public and primary API/SPI
  signatures fail if they regress to raw `Map<String, Object>` contracts
- Deterministic `AgentEngine` run and function-tool loops, shared by ordinary and streaming runs
- Session persistence and restoration: versioned snapshots, in-memory and file session stores, the
  context provider pipeline, and default in-memory chat history
- OpenAI Chat Completions adapter (Preview) over a borrowed official SDK client, reaching the engine
  only through the neutral `ModelClient` port, with a deterministic offline test suite
- Runnable standalone `Agent.run(...)` sample calling a real OpenAI-compatible endpoint through that
  adapter, with one local function tool

**Not started**

- Interceptor pipeline and tool approval
- Provider streaming and Spring Boot hosting
- Workflows and protocol adapters

The next implementation stages add host integration, provider streaming, and further provider
adapters without changing the standalone agent definition.

## Translations

- [Korean companion guide](docs/ko/README.md)

English is the source of truth. The Korean companion guide is a single orientation document, not a
translation of the English documentation; when the two disagree, the English document wins.

## License

[MIT](LICENSE)
