# 12 Provider integrations

**Prefix** `PRV` · **Upstream features** [31 provider-integrations](../upstream/snapshots/d0a4165f/features/31-provider-integrations.md)

Organizes the model providers, the managed agent runtimes, storage, memory, governance, and the
hosting adapter inventory from the perspective of Java modules. The individual protocol wire
contracts are owned by [10 Hosting and protocols](10-hosting.md) and the operational quality and
packaging rules by [11 Operational quality](11-operations.md). This document settles which adapter
is moved with which boundary and at which priority.

## Adoption scope

The `Grade` column in this document is, as [README](README.md#requirement-grades) defines it, how binding a requirement is once the decision to build the feature has been made; whether the feature is adopted at all follows the [compatibility matrix](../upstream/snapshots/d0a4165f/compatibility-matrix.md).

- The first OpenAI-compatible vertical slice and the shared provider boundary have adoption `Required`.
- The hosting and protocol separation and the provider-owned continuation boundary have adoption `Optional`, in line with the optional adapter category.
- Storage, memory, and governance and the one-sided long-tail adapter family have adoption `Deferred`.

## Summary

| ID | Requirement | Adoption | Grade | Phase |
| --- | --- | --- | --- | --- |
| PRV-001 | The core does not depend directly on a provider SDK | Required | Required | Core+ |
| PRV-002 | Providers and integrations are separated per artifact | Required | Required | Core+ |
| PRV-003 | An all-providers bundle is not provided by default | Optional | Recommended | Optional |
| PRV-004 | The adapter porting priority is fixed from P0 to P4 | Required | Required | Core+ |
| PRV-005 | Hosting and protocol adapters are separated from the model providers | Optional | Required | Hosting |
| PRV-006 | Storage, memory, and governance are separated from the model providers | Deferred | Required | Optional |
| PRV-007 | Provider-specific features are exposed as optional capabilities | Required | Required | Core+ |
| PRV-008 | Provider-specific continuation and hosted state are owned by the adapter | Optional | Required | Hosting |
| PRV-009 | An integration that exists in only one language is kept in an optional tier | Deferred | Recommended | Optional |
| PRV-010 | An adapter provides maturity, README, and test evidence together and preserves its facade | Required | Required | Optional |

---

### Shared boundaries

## PRV-001 The core does not depend directly on a provider SDK

**Requirement.** The core and engine modules must not depend directly on a provider SDK and must know
only the neutral ports.

**Upstream comparison**

- .NET: Separates transports such as OpenAI, Foundry, and Anthropic into individual projects.
- Python: Keeps provider packages such as openai, foundry, gemini, and bedrock as packages separate from the core.

**Decision.** This is the structure the inventory already shows. Once an SDK dependency enters the
core, adding long-tail providers and writing test doubles becomes sharply harder.

**Acceptance criteria**

- No specific provider SDK enters the core build classpath directly.
- A provider adapter connects to the core by implementing the neutral ChatClient or an equivalent SPI.

**Evidence** [31 provider-integrations](../upstream/snapshots/d0a4165f/features/31-provider-integrations.md)

---

## PRV-002 Providers and integrations are separated per artifact

**Requirement.** Model providers and integrations must be separated into a distinct artifact per
provider or integration family.

**Upstream comparison**

- .NET: Splits OpenAI, Foundry, GitHub Copilot, Purview, Cosmos, Hosting.OpenAI, and others per project.
- Python: Splits provider, storage, hosting, and channel adapters per package under packages/*.

**Decision.** Mixing several providers into one artifact ties their versions, dependencies, and
maturity together. Java also fixes the provider-per-artifact principle.

**Acceptance criteria**

- Adding a new provider does not grow the transitive dependency set of the existing provider artifacts.
- Provider artifacts are identified by independent names in the documentation and the build outputs.

**Evidence** [31 provider-integrations](../upstream/snapshots/d0a4165f/features/31-provider-integrations.md)

---

## PRV-003 An all-providers bundle is not provided by default

**Requirement.** The default distribution must not build an all-providers bundle and must prefer
selective installation.

**Upstream comparison**

- .NET: The NUGET metadata and the project split assume individual installation.
- Python: Even with a meta package, the packages/* selective install surface is maintained separately.

**Decision.** Selective installation reduces the dependency blast radius. Even if an aggregator
artifact exists, it must be an optional convenience.

**Acceptance criteria**

- The core can be used with only an individual provider installed.
- Even if an aggregator artifact exists, it does not replace the per-provider artifacts.

**Evidence** [31 provider-integrations](../upstream/snapshots/d0a4165f/features/31-provider-integrations.md)

---

---

### Priority

## PRV-004 The adapter porting priority is fixed from P0 to P4

**Requirement.** The Java adapter porting priority must be fixed as P0 core parity, P1 shared
providers and hosting, P2 persistence and governance, P3 managed runtimes, and P4 language-specific
extras.

**Upstream comparison**

- .NET: .NET-only extras such as AzureAI Persistent, LocalCodeAct, AspNetCore, and Aspire DevUI form their own tier.
- Python: Python-only extras such as Bedrock, Claude, Gemini, ChatKit, and Telegram form their own tier.

**Decision.** The inventory is wide, so without priorities the core parity is delayed. The shared
providers and hosting are fixed first and the one-sided extras are pushed back.

**Acceptance criteria**

- Before the P0 and P1 scope is finished, a P4-only adapter is not treated as a core milestone.
- The priority table is reused identically in the documentation and the plans.

**Evidence** [31 provider-integrations](../upstream/snapshots/d0a4165f/features/31-provider-integrations.md)

---

---

### Shared boundaries

## PRV-005 Hosting and protocol adapters are separated from the model providers

**Requirement.** Hosting and protocol adapters such as OpenAI Responses, A2A, AG-UI, and MCP must be
separated into artifacts distinct from the model provider adapters.

**Upstream comparison**

- .NET: Hosting.OpenAI, Hosting.A2A, Hosting.AGUI, and Hosting.AspNetCore are packages separate from the core providers.
- Python: hosting-responses, hosting-a2a, ag-ui, and hosting-mcp are separated from the provider packages.

**Decision.** The host surface and the model transport have different lifecycles and security
boundaries. Merging the two makes a wire protocol change force a model provider upgrade.

**Acceptance criteria**

- Removing the model provider artifact keeps the test doubles of the hosting adapters intact.
- A hosting adapter connects to the core only through the provider-neutral hosting core or a protocol SPI.

**Evidence** [31 provider-integrations](../upstream/snapshots/d0a4165f/features/31-provider-integrations.md),
[20 hosting](../upstream/snapshots/d0a4165f/features/20-hosting.md)

---

## PRV-006 Storage, memory, and governance are separated from the model providers

**Requirement.** Storage, memory, and governance integrations such as Cosmos, Redis or Valkey, Mem0,
and Purview must be separated into artifacts distinct from the model providers.

**Upstream comparison**

- .NET: CosmosNoSql, Valkey, and Purview exist as projects independent of the model providers.
- Python: azure-cosmos-memory, redis, mem0, and purview exist as separate packages.

**Decision.** Stores and governance are operational dependencies that outlive the models. Mixing them
with the model adapters grows an unnecessary transitive surface.

**Acceptance criteria**

- Removing a storage or governance adapter does not change the API of the model provider artifacts.
- History or checkpoints and semantic memory are identified as separate artifacts.

**Evidence** [31 provider-integrations](../upstream/snapshots/d0a4165f/features/31-provider-integrations.md)

---

## PRV-007 Provider-specific features are exposed as optional capabilities

**Requirement.** Provider-specific features must be exposed as an adapter-owned capability or option
surface rather than by growing the core methods.

**Upstream comparison**

- .NET: Persistent Agents, Foundry hosted notes, and provider-specific extensions are separated into individual surfaces.
- Python: Keeps dedicated features such as ThinkingConfig, BedrockGuardrailConfig, and Foundry generated evaluators on a per-package public surface.

**Decision.** The user's requirement about "how provider-specific features are exposed" is reflected
directly. Not contaminating the common interfaces is what keeps portability and Java conventions
together.

**Acceptance criteria**

- The common ChatClient or Agent SPI does not directly require option fields specific to a particular provider.
- Whether a dedicated feature is used is stated through a capability query or an adapter-specific type.

**Evidence** [31 provider-integrations](../upstream/snapshots/d0a4165f/features/31-provider-integrations.md)

---

## PRV-008 Provider-specific continuation and hosted state are owned by the adapter

**Requirement.** A provider-specific continuation handle or managed runtime state must be held as an
adapter-owned structured type and must not be parsed generically by the core.

**Upstream comparison**

- .NET: Provider- or runtime-specific state surfaces such as A2AAgentSession and the Foundry hosting context exist separately.
- Python: A2AServiceSessionId and the Foundry state store provider have provider-owned continuation and storage shapes.

**Decision.** This is a shared principle of the hosting and provider documents. If the core tries to
understand a provider handle, the core shakes every time a new adapter arrives.

**Acceptance criteria**

- Provider-owned continuation is interpreted only inside the adapter module.
- Core telemetry and session helpers do not parse provider-specific structured state directly.

**Evidence** [31 provider-integrations](../upstream/snapshots/d0a4165f/features/31-provider-integrations.md),
[26 identity-session-routing](../upstream/snapshots/d0a4165f/features/26-identity-session-routing.md)

---

---

### Priority

## PRV-009 An integration that exists in only one language is kept in an optional tier

**Requirement.** An integration that exists in only one language or on one specific runtime must be
kept in an optional adapter tier rather than as a first-class core dependency.

**Upstream comparison**

- .NET: There are .NET-only surfaces such as Azure AI Persistent Agents, LocalCodeAct, and Aspire DevUI.
- Python: There are Python-only surfaces such as Bedrock, Claude, ChatKit, Telegram, and Foundry Local.

**Decision.** The inventory is asymmetric. Managing this difference as an optional tier instead of
treating it as a core parity failure is what keeps the overall plan stable.

**Acceptance criteria**

- The absence of a one-sided adapter is not classified as a core compatibility failure.
- An optional tier adapter is not forcibly included in the core BOM.

**Evidence** [31 provider-integrations](../upstream/snapshots/d0a4165f/features/31-provider-integrations.md)

---

## PRV-010 An adapter provides maturity, README, and test evidence together and preserves its facade

**Requirement.** Each adapter artifact must provide its maturity, a README or equivalent
documentation, and test evidence together, and must preserve its facade surface even through a repo
split or a move of the implementation.

**Upstream comparison**

- .NET: A central NUGET README fallback, stage metadata, and per-project tests exist.
- Python: PACKAGE_STATUS, package READMEs, tests, and changelog re-export cases all exist.

**Decision.** A provider inventory is not sufficient as a mere existence check. Downstream consumers
must be able to read installation, maturity, and reliability, and the import surface must hold even
when the backend moves.

**Acceptance criteria**

- Each adapter has a maturity marker and a README or a central README fallback.
- For each adapter, at least one piece of test evidence can be found in the documentation.
- On a repo split or a move of the implementation, whether the facade or the re-exports are preserved is recorded in the changelog or equivalent documentation.

**Evidence** [31 provider-integrations](../upstream/snapshots/d0a4165f/features/31-provider-integrations.md)

---

## What this document does not cover

| Topic | Owning document |
| --- | --- |
| The core contracts of agent execution and the message model | [01 Agent execution and model calls](01-agent-execution.md) |
| The hosting model and the identity boundary | [10 Hosting and protocols](10-hosting.md) |
| Evaluation, packaging, compatibility gates | [11 Operational quality](11-operations.md) |
| The detailed wire payloads and sample code of an individual provider | ../upstream/snapshots/d0a4165f/features/31-provider-integrations.md |
