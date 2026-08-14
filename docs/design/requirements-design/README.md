# Requirements-driven design

[Back to the documentation index](../../README.md)

This directory translates the 244 requirements in `docs/requirements/` into a Java implementation
structure. The requirements define what to build; this design defines the boundaries and types used
to build it.

## Document structure

| Document | Canonical requirement owner |
| --- | --- |
| [00 Clean architecture](00-clean-architecture.md) | Common layers, dependencies, Java API, and framework assembly |
| [01 Core execution](01-core-execution.md) | `AGT`, `MSG`, `OUT`, `TOOL` |
| [02 State, extension, and MCP](02-state-extension-mcp.md) | `SES`, `INT`, `MCP` |
| [03 Workflow and harness](03-workflow-harness.md) | `WF`, `HAR` |
| [04 Hosting, operations, and providers](04-hosting-operations-providers.md) | `HOST`, `OPS`, `PRV` |
| [05 Framework adapters](05-framework-adapters.md) | Assembly patterns by framework; owns no canonical requirements |
| [06 Developer experience](06-developer-experience.md) | Progressive disclosure and the `agent.run()`-centered facade |
| [Requirements traceability matrix](requirements-traceability-matrix.md) | Design, target code, current code, and verification mappings for all 244 IDs |

Each requirement has exactly one document as its canonical owner. When a requirement affects
multiple bounded contexts, the other documents only cross-reference it; they do not duplicate its
ownership.

## Current code status

The current product code is at the module bootstrap stage.

- `agent-framework-api`: only the `ApiContract` marker exists
- `agent-framework-engine`: only the `EngineContract` marker and dependency test exist
- `agent-framework-testkit`: only the `DeterministicClock` fixture exists
- `agent-framework-bom`: only artifact alignment exists

Consequently, most entries in the mapping's `Current` column are `absent`. A `planned` symbol must
not be described as a current implementation. Only areas with actual code, such as build,
publishing, and repository policy, are marked `implemented` or `partial`.

## Mapping rules

Each requirement is traced across the following five dimensions.

1. Requirement ID and source text
2. Canonical design section
3. Target Gradle module, package, and Java symbol
4. Current implementation status and actual path
5. Unit, contract, golden, and wire verification

Status values are limited to the following.

| Status | Meaning |
| --- | --- |
| `absent` | The target module or production type does not exist |
| `bootstrap` | Only the module or marker exists; the required behavior does not |
| `partial` | Some required behavior is verified by production code and tests |
| `implemented` | All acceptance criteria are connected to code and tests |

## Evidence priority

Design decisions follow the repository contract's evidence priority.

1. Pinned upstream production source
2. Pinned upstream conformance/integration tests
3. Requirements and approved design
4. Official Java framework documentation
5. Samples and README files

The current upstream pin is `d0a4165f170193ba1d026a259af40d35bb7eaefe`. Do not mix new behavior
from the current upstream `main` into this design.

## Java and framework principles

All designs follow [Java API and extension principles](../../requirements/java-api-principles.md)
and the [Java idiom audit](../../requirements/java-api-audit.md).

- The core API does not expose types from Spring, Quarkus, Jakarta EE, provider SDKs, JSON mappers,
  Reactor, or Mutiny.
- Values are immutable, and extension points are typed open SPIs.
- Contracts identify the owners of asynchronous work, streaming, cancellation, and close.
- Adapters implement only public ports and do not reference engine internals.
- Framework adapters are valid only if the same assembly is possible in plain Java first.

## Design change process

1. For a requirement change, update `docs/requirements/` and the traceability matrix first.
2. For a public symbol or module ownership change, update the canonical design and module
   composition together.
3. Verify that the mapping script and policy find every ID exactly once.
4. Run `./gradlew policyCheck` because this changes instructions and contracts.
5. Complete the Copilot review loop after pushing.
