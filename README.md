# Agent Framework for Java

Bring the observable execution semantics of [Microsoft Agent Framework](https://github.com/microsoft/agent-framework)
to Java.

This project does not build an application server or a dependency injection container. The
deliverable is an embeddable `AgentEngine`. A host runtime such as Spring Boot keeps owning object
lifecycle, execution resources, security, transactions, and observability configuration.

> **Status:** early. The build harness, requirements, and module skeleton are in place. Agent
> behavior is not implemented yet. See [Current state](#current-state).

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
build-logic/                Gradle convention plugins
build-tools/harness-policy/ Executable repository policy
config/                     Checkstyle, PMD, SpotBugs configuration
docs/                       Requirements, design, upstream analysis
.harness/                   Agent artifact JSON schemas
```

Planned grouping directories are `providers/`, `integrations/`, `starters/`, `protocols/`,
`workflow/`, `compatibility-tests/`, and `samples/`. Each is created when its first module lands.

Module rules are defined in [module composition](docs/design/module-composition.md) and enforced by
`./gradlew policyCheck`.

## Documentation

Start here depending on what you need.

| Question | Document |
| --- | --- |
| What must Java build? | [Requirements](docs/requirements/README.md) |
| Why is it built this way? | [Foundation design](docs/design/foundation-design.md) |
| How does the upstream framework behave? | [Upstream snapshot analysis](docs/upstream/snapshots/d0a4165f/README.md) |
| How do modules relate? | [Module composition](docs/design/module-composition.md) |
| How is the repository verified? | [Engineering harness design](docs/design/engineering-harness-design.md) |
| How does the build work? | [Gradle and Java ARC foundation](docs/design/gradle-kotlin-arc-foundation-design.md) |

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

The repository has a verified foundation and no agent behavior yet.

**In place**

- Gradle Kotlin DSL build with convention plugins and dependency locking
- Executable repository policy covering build contract, governance, workflows, and module structure
- Agent artifact JSON schemas under `.harness/`
- CI on the `arc-java-build` ARC scale set with a fork-safe verification path
- 244 requirements derived from a pinned upstream snapshot
- Four product modules with a compiled, tested, publishable surface

**Not started**

- Message and content types, model client ports, tool loop, sessions, interceptors
- Workflows, hosting, protocol adapters, provider integrations

The first implementation target is the `agent-framework-api` type model, because every other module
depends on it and the compatibility matrix marks it required for the initial release.

## Translations

- [한국어](docs/ko/README.md)

English is the source of truth. Translations follow.

## License

[MIT](LICENSE)
