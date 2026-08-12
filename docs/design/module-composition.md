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
integrations/                   MCP, Spring AI, memory, storage adapters
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

Planned grouping directories are `providers/`, `integrations/`, `starters/`, `protocols/`,
`workflow/`, `compatibility-tests/`, and `samples/`. `build-tools/` already exists for harness code.
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

Deeper nesting is rejected. Quarkus splits each extension into `runtime/` and `deployment/`, which
earns its complexity because a Quarkus extension genuinely executes in two build phases. This
project has no such split, so a second level would add path depth without adding meaning.

## Product projects

| Gradle path | Artifact | Responsibility | Allowed project dependencies |
| --- | --- | --- | --- |
| `:agent-framework-api` | `agent-framework-api` | Public contracts and value types. | none |
| `:agent-framework-engine` | `agent-framework-engine` | Embedded execution state machine. | `:agent-framework-api` |
| `:agent-framework-testkit` | `agent-framework-testkit` | Deterministic fixtures and contract-test bases. | `:agent-framework-api` |
| `:agent-framework-bom` | `agent-framework-bom` | `java-platform` listing every published artifact. | none |

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

## Rules

1. Every library project applies `agentframework.java-library-conventions`,
   `agentframework.test-conventions`, `agentframework.quality-conventions`, and
   `agentframework.library-publishing-conventions`.
2. `:agent-framework-api` declares no project dependency. It is the root of the graph.
3. `:agent-framework-engine` and `:agent-framework-testkit` depend on `:agent-framework-api` only.
4. No project depends on `:agent-framework-bom`, and the BOM lists every published library.
5. No product project depends on `:build-tools:harness-policy`.
6. Every project registered in `settings.gradle.kts` exists on disk with a build file.
7. Dependency versions come from `gradle/libs.versions.toml`. Build files declare no inline version.
8. The group is `com.microsoft.agentframework` and the version is repository wide.
9. Java packages start with `com.microsoft.agentframework`. Harness build code uses
   `com.microsoft.agentframework.build.harness`.

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
build passes `-Pagentframework.release=true`, and a missing `SIGNING_KEY` then fails the publish
task rather than the upload, where the failure would surface after the release is already in
motion. The flag is read by value, so `-Pagentframework.release=false` means what it says.

Signing only misbehaves once a key exists, which no ordinary pull request provides. CI therefore
generates a throwaway key, publishes through the release path, and `SigningContractTest` asserts
that every artifact carries a detached signature; the workflow then runs `gpg --verify` so a
signature that exists but does not validate cannot pass. A text assertion is not enough here: an
earlier version of that guard was defeated by a single extra space.

The BOM must manage versions without forcing dependencies. `PublishedBomContractTest` parses the
published POM and fails when any `<dependencies>` block appears outside `<dependencyManagement>`,
because reading the build file alone cannot tell a constraint from a plain declaration sitting
beside it. The POM is located by the version the build declares rather than by sorting filenames,
since the publish directory accumulates across versions and `0.9.0` sorts above `0.10.0`.

Locally the test skips when nothing has been published, so it never reports a contract it did not
check. CI publishes first and passes `-Pagentframework.requirePublishedBom=true`, which turns a
missing artifact into a failure.

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
