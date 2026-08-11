# Module composition contract

This repository is a Gradle multi-project monorepo. Module identity, dependency direction,
and artifact coordinates are enforced by `build-tools/harness-policy`. Update this document
and `ModuleCompositionPolicyTest` together.

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

## Rules

1. Every library project applies `agentframework.java-library-conventions`,
   `agentframework.test-conventions`, and `agentframework.quality-conventions`.
2. `:agent-framework-api` declares no project dependency. It is the root of the graph.
3. `:agent-framework-engine` and `:agent-framework-testkit` depend on `:agent-framework-api` only.
4. No project depends on `:agent-framework-bom`, and the BOM depends on no harness project.
5. No product project depends on `:build-tools:harness-policy`.
6. Every project registered in `settings.gradle.kts` exists on disk with a build file.
7. Dependency versions come from `gradle/libs.versions.toml`. Build files declare no inline version.
8. The group is `com.microsoft.agentframework` and the version is repository wide.
9. Java packages start with `com.microsoft.agentframework`. Harness build code uses
   `com.microsoft.agentframework.build.harness`.

## Why the graph points this way

The API module is the only contract a provider adapter or a host integration needs to compile
against. If the engine could be reached from the API, every adapter would drag the execution
machinery into its classpath and a change to run semantics would ripple into unrelated modules.

The testkit depends on the API rather than the engine for the same reason. Fixtures describe
contracts, not internals, so a test written against the testkit keeps compiling when the engine
is restructured.

## Adding a project

1. Add the row to the product table above.
2. Add the assertion to `ModuleCompositionPolicyTest` and watch it fail.
3. Register the project in `settings.gradle.kts` and add its build file.
4. Run `./gradlew policyCheck quality testJava17`.

## Related documents

- [Foundation design](foundation-design.md)
- [Requirements](../requirements/README.md)
