# Module composition contract

This repository is a Gradle multi-project monorepo. Module identity, layout, dependency direction,
and artifact coordinates are enforced by `build-tools/harness-policy`. Update this document and
`ModuleCompositionPolicyTest` together.

## Layout

Core modules sit at the repository root. Extension families that grow to many modules sit under a
single grouping directory.

```text
agent-framework-api/            Public contracts
agent-framework-engine/         Execution state machine
agent-framework-testkit/        Deterministic fixtures
agent-framework-bom/            Version alignment

providers/                      Model provider adapters
integrations/                   MCP, Spring AI, memory, storage, and executable framework adapters
hosting/                        Framework-neutral target/session/checkpoint coordination
starters/                       Host framework starters
protocols/                      Responses, A2A, AG-UI, MCP server hosting
workflow/                       Workflow graph and orchestration
compatibility-tests/            Golden scenarios against upstream behavior
samples/                        Runnable examples, never published

build-logic/                    Gradle convention plugins (included build)
build-tools/harness-policy/     Executable repository policy
config/                         Checkstyle, PMD, SpotBugs configuration
docs/                           Requirements, design, upstream analysis
```

Grouping directories are `providers/`, `integrations/`, `hosting/`, `starters/`, `protocols/`,
`workflow/`, and `compatibility-tests/`. `providers/` now contains the OpenAI Chat Completions
client, `integrations/` contains the MCP client integration,
`samples/` contains the standalone example, and `build-tools/` already exists for harness code; the
remaining directories are planned.
This list is closed and mirrored in `ModuleCompositionPolicyTest`; a project registered outside it,
or nested more than one level deep, fails `policyCheck`.

Grouping directories are created when the first module in that family lands, not in advance. An
empty directory communicates nothing and still costs a reader a lookup.

### Why this shape

Two layouts are common in Java monorepos of this size, and each fits a different kind of module set.

A **flat root** works when modules are homogeneous. Spring Framework, Micronaut Core, and
openai-java put every module at the root because the modules are variations of one thing, so a
reader scanning the root learns the whole system.

**Grouping directories** work when modules are heterogeneous. Spring AI keeps its core at the root
and groups `models/`, `vector-stores/`, `starters/`, and `mcp/`. Spring Boot groups `platform/`,
`starter/`, `documentation/`, `smoke-test/`, `integration-test/`, and `system-test/`. LangChain4j
groups by domain the same way.

This project's planned modules are heterogeneous: a provider adapter, a protocol host, a Spring Boot
starter, and a sample have almost nothing in common. At 20 to 30 modules a flat root would force a
reader to infer families from name prefixes alone.

Deeper nesting is rejected. Quarkus splits each extension into runtime and deployment artifacts,
which earns its complexity because a Quarkus extension genuinely executes in two build phases. This
project uses sibling `quarkus-agent-framework` and `quarkus-agent-framework-deployment` leaf modules
from the first release so Quarkus CLI/platform tooling recognises a real extension. A second directory
level would add path depth without adding published identity.

## Product projects

| Gradle path | Artifact | Responsibility | Allowed project dependencies |
| --- | --- | --- | --- |
| `:agent-framework-api` | `agent-framework-api` | Public contracts and value types. | none |
| `:agent-framework-engine` | `agent-framework-engine` | Embedded execution state machine. | `:agent-framework-api` |
| `:agent-framework-testkit` | `agent-framework-testkit` | Deterministic fixtures and contract-test bases. | `:agent-framework-api` |
| `:agent-framework-bom` | `agent-framework-bom` | `java-platform` listing every published artifact. | none |
| `:integrations:agent-framework-mcp` | `agent-framework-mcp` | Model Context Protocol client integration over a borrowed SDK client or an owned stdio or streamable HTTP connection. | `:agent-framework-api` |
| `:providers:agent-framework-openai` | `agent-framework-openai` | OpenAI Chat Completions model client over a borrowed official SDK client. | `:agent-framework-api` (production), `:agent-framework-engine` (test only) |
| `:samples:sample-standalone` | not published | Runnable standalone `Agent.run` example with explicit model-client assembly. | `:agent-framework-api`, `:agent-framework-engine` |

## Harness projects

| Gradle path | Published | Responsibility |
| --- | --- | --- |
| `:build-tools:harness-policy` | no | Executable repository, artifact, and workflow policy. |

## Naming

- A published module's leaf directory name equals its artifact id.
  `providers/agent-framework-openai` publishes as `agent-framework-openai`.
- A grouping directory never appears in published coordinates. A grouped module sets its archive
  name from the leaf directory so the Gradle path and the artifact id stay independent.
- Samples use a `sample-` prefix and are never published, so an artifact search never returns one.
- Compatibility modules name the surface they verify, such as `compatibility-tests/openai-responses`.

### Namespace ownership

This repository is a community implementation and does not own Microsoft's Maven or Java namespace.

- Maven group: `io.github.hellices.agentframework`
- Java package root: `io.github.hellices.agentframework`
- artifact ids retain the neutral `agent-framework-*` naming

Do not publish `com.microsoft.*` classes or coordinates unless the project is formally transferred to
Microsoft and the namespace migration is reviewed. Before the first public release, a transfer may
rename the namespace directly. After a public release, an official namespace uses a new major line
and an explicit compatibility/migration artifact; it never creates split packages or silently changes
the package of an existing coordinate.

## Rules

1. Every library project applies `agentframework.java-library-conventions`,
   `agentframework.test-conventions`, `agentframework.quality-conventions`, and
   `agentframework.library-publishing-conventions`.
2. `:agent-framework-api` declares no project dependency. It is the root of the graph.
3. `:agent-framework-engine`, `:agent-framework-testkit`, `:integrations:agent-framework-mcp`, and
   `:providers:agent-framework-openai` depend on `:agent-framework-api` only. An integration or
   provider additionally depends on the protocol or provider SDK it adapts, which never reaches the
   API or engine modules. `:providers:agent-framework-openai` also compiles its tests against
   `:agent-framework-engine`, which rule 4 governs: it ships to no consumer.
4. A production project dependency is what a consumer inherits, so it is what the dependency
   direction rules constrain. A test-only project dependency reaches no consumer and is allowlisted
   separately in `ModuleCompositionPolicyTest`. Every configuration whose name starts with `test` is
   test-only, including `testFixturesApi` and `testFixturesImplementation`: those carry the
   dependencies of the `testFixtures` source set, not of the published artifact, and a consumer
   reaches them only by asking for `testFixtures(project(":path"))` from a test configuration of its
   own. Publishing test fixtures from a library would add a `-test-fixtures` variant whose
   dependencies do reach a consumer, and must revisit this rule.
5. Declare every project dependency as `configuration(project(":path"))`, alone on its line, with an
   unqualified call and a literal path. The policy reads the configuration from the same line and
   refuses anything else it cannot classify — a declaration split across lines, a second project
   dependency on the same line, a named or extra argument, or a type-safe `projects.` accessor.
   Refusing is deliberate: a form the policy silently skipped would report a module as depending on
   nothing while it shipped against the project. Project references inside `//`, `/* */`, and KDoc
   comments declare nothing and are ignored.
6. No project depends on `:agent-framework-bom`, and the BOM lists every published library.
7. No product project depends on `:build-tools:harness-policy`.
8. Every project registered in `settings.gradle.kts` exists on disk with a build file.
9. Dependency versions come from `gradle/libs.versions.toml`. Build files declare no inline version.
10. The group is `io.github.hellices.agentframework` and the version is repository wide.
11. Java packages start with `io.github.hellices.agentframework`. Harness build code uses
    `io.github.hellices.agentframework.build.harness`.

## Why the graph points this way

The API module is the only contract a provider adapter or a host integration needs to compile
against. If the engine were reachable from the API, every adapter would drag the execution machinery
onto its classpath and a change to run semantics would ripple into unrelated modules.

The testkit depends on the API rather than the engine for the same reason. Fixtures describe
contracts, not internals, so a test written against the testkit keeps compiling when the engine is
restructured.

## Publishing

Every library publishes a main jar, a sources jar, and a javadoc jar, because Maven Central requires
all three and because a consumer cannot step into the code from an IDE without them.

```bash
./gradlew publishAllPublicationsToBuildDirectoryRepository
```

This writes to `build/maven-repository` so publication stays verifiable in CI and in a fork without
credentials. Release repositories are configured by the release workflow, never by a convention
plugin.

Central also rejects unsigned artifacts, so the publishing convention applies PGP signing. Signing
stays optional by default, which keeps the local and fork publish path credential free. A release
build passes `-Pagentframework.release` (or `=true`), and a missing signing key then fails the
publish task rather than the upload, where the failure would surface after the release is already in
motion. `=false` means what it says, and any other value is rejected: a flag that fails open here
would publish unsigned artifacts silently.

Signing only misbehaves once a key exists, which no ordinary pull request provides. CI therefore
generates a throwaway key, publishes through the release path, and `SigningContractTest` asserts
that every artifact carries a detached signature; the workflow then runs `gpg --verify` so a
signature that exists but does not validate cannot pass. A text assertion is not enough here: an
earlier version of that guard was defeated by a single extra space.

The key reaches the build through `SIGNING_KEY_FILE`, a path, rather than `SIGNING_KEY`, a value.
GitHub Actions prints each step's `env` block, so an environment variable holding a private key
ends up in the run log. `SIGNING_KEY` still works for a release that has no way to stage a file.

The BOM must manage versions without forcing dependencies. `PublishedBomContractTest` parses the
published POM and fails when any `<dependencies>` block appears outside `<dependencyManagement>`,
because reading the build file alone cannot tell a constraint from a plain declaration sitting
beside it. The POM is located by the version the build declares rather than by sorting filenames,
since the publish directory accumulates across versions and `0.9.0` sorts above `0.10.0`.

Locally the test skips when nothing has been published, so it never reports a contract it did not
check. CI publishes first and passes `-Pagentframework.requirePublishedBom=true`, which turns a
missing artifact into a failure. `-Pagentframework.requireSignatures` does the same for signing.

Every one of these flags follows one rule: bare or `=true` enables it, `=false` disables it, and any
other value is rejected. A flag that decides whether a check runs must not fail open, because asking
for enforcement and silently getting a skip looks identical to a passing build.

## Adding a project

1. Decide placement. Core contract or execution goes to the root. Anything in a growing family goes
   under its grouping directory.
2. Add the row to the product table above.
3. Add the assertion to `ModuleCompositionPolicyTest` and watch it fail.
4. Register the project in `settings.gradle.kts` and add its build file.
5. For a grouped module, set the archive name from the leaf directory.
6. Run `./gradlew :<project>:resolveAndLockAll --write-locks` and commit the lockfile.
7. Run `./gradlew policyCheck quality testJava17`.

## Related documents

- [Foundation design](foundation-design.md)
- [Requirements](../requirements/README.md)
- [Getting started](../operations/getting-started.md)
