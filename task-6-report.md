# Task 6 Report

## Status
- Result: complete
- Branch: feat/core-convergence
- Commit: see current HEAD (`git rev-parse --short HEAD`)

## Summary
- Added executable typed-public-contract policy in `build-tools/harness-policy` using parsed public API/SPI declarations instead of blanket substring bans.
- Restricted public records to the reviewed fixed set (`Usage`, `MessageAttribution`) and converted remaining session/tool contract records to final classes.
- Replaced MCP call metadata's primary public raw map surface with `JsonObject` and updated runtime/test consumers.
- Updated canonical architecture and audit docs plus README current-state wording.

## RED evidence
- `./gradlew :build-tools:harness-policy:test --tests 'io.github.hellices.agentframework.build.harness.TypedPublicContractPolicyTest'`
  - failed before migration on remaining public records and `McpCallMetadataProvider.metadata(ToolContext)` returning `Map<String, Object>`.
- `./gradlew :build-tools:harness-policy:test --tests 'io.github.hellices.agentframework.build.harness.PublicContractSurfaceTest'`
  - failed before parser hardening on nested interface types and fully qualified raw-map spellings.

## Key changes
- Added `PublicContractSurface` parser-backed policy helper and policy tests.
- Converted these public contract records to final classes with preserved invariants/equality:
  - `MessageHistory`
  - `SessionStateEntry`
  - `SessionSnapshotMetadata`
  - `ContextMessageContribution`
  - `SessionSnapshot`
  - `ToolResult`
  - `HistoryPolicy`
- Changed `McpCallMetadataProvider.metadata(ToolContext)` to return `JsonObject`.
- Updated MCP option defaults, request metadata mapping, and MCP tests for typed metadata.
- Updated:
  - `docs/requirements/java-api-audit.md`
  - `docs/design/requirements-design/00-clean-architecture.md`
  - `docs/design/requirements-design/01-core-execution.md`
  - `docs/design/requirements-design/requirements-traceability-matrix.md`
  - `README.md`

## Verification
- Affected-project tests:
  - `./gradlew :agent-framework-api:test :agent-framework-engine:test :agent-framework-testkit:test :integrations:agent-framework-mcp:test :providers:agent-framework-openai:test :samples:sample-standalone:test`
- Repository gates:
  - `./gradlew policyCheck`
  - `./gradlew quality`
  - `./gradlew testJava17`
- Compatibility toolchains:
  - `./gradlew testJava21` -> failed because no Java 21 installation was available and toolchain auto-provisioning is disabled.
  - `./gradlew testJava25` -> passed.

## Review
- Ran automated code review on the working tree.
- Addressed review findings in the new policy parser by adding coverage for:
  - nested public types inside public interfaces
  - fully qualified `java.util.Map<java.lang.String, java.lang.Object>` signatures

## Concerns
- Java 21 is not installed locally, so that compatibility lane could not be verified in this workspace.
