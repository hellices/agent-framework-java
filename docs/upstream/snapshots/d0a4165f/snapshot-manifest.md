# Microsoft Agent Framework Upstream Snapshot

## Identification

| Item | Value |
| --- | --- |
| Repository | `https://github.com/microsoft/agent-framework.git` |
| Branch at capture | `main` |
| Full commit | `d0a4165f170193ba1d026a259af40d35bb7eaefe` |
| Git tree | `0dbd7a60d70ad3b588b5b2ad77131b3a0879c3cf` |
| Author date | `2026-08-09T22:51:59-07:00` |
| Commit date | `2026-08-10T05:51:59Z` |
| Subject | `[BREAKING] Python: Migrate FHA to responses==2.0.0b1 and add Foundry state store (#7533)` |
| Nearest tag description | `dotnet-1.17.0-23-gd0a4165f` |
| Git archive SHA-256 | `73bf466a3df507a7af15355c32c37526fd4aad8a30de569fe34664c11237d45e` |
| Tracked files | 4,876 |

The Git archive checksum is taken over the tar byte stream of the following command.

```bash
git archive --format=tar d0a4165f170193ba1d026a259af40d35bb7eaefe \
  | shasum -a 256
```

The source is reproduced with the following commands.

```bash
git clone --no-checkout --filter=blob:none \
  https://github.com/microsoft/agent-framework.git agent-framework-upstream
git -C agent-framework-upstream checkout --detach \
  d0a4165f170193ba1d026a259af40d35bb7eaefe
```

## Repository composition

| Area | Tracked files | Role |
| --- | ---: | --- |
| `dotnet/` | 2,941 | .NET core, workflow, harness, hosting, protocol, and provider implementations |
| `python/` | 1,778 | Python core, orchestration, hosting, and provider implementations |
| `docs/` | 68 | Repository specification, decision, and feature documents |
| `declarative-agents/` | 19 | Declarative agent schema and related assets |
| `.github/` | 58 | CI, release, and repository automation |
| Other root files | 12 | License, contribution, security, and project metadata |

## Top-level versions

### .NET

The common `VersionPrefix` in `dotnet/nuget/nuget-package.props` is `1.17.0`. The snapshot is 23
commits past the `dotnet-1.17.0` tag, so it is not treated as identical to the `1.17.0` release.

### Python

| Package | Version | Version status |
| --- | --- | --- |
| `agent-framework-core` | `1.13.0` | stable version |
| `agent-framework-openai` | `1.12.0` | stable version |
| `agent-framework-foundry` | `1.10.4` | stable version |
| `agent-framework-ag-ui` | `1.0.1` | stable version |
| `agent-framework-orchestrations` | `1.0.2` | stable version |
| `agent-framework-declarative` | `1.0.1` | stable version |
| `agent-framework-github-copilot` | `1.0.1` | stable version |
| `agent-framework-hosting` | `1.0.0a260730` | alpha version |
| `agent-framework-hosting-a2a` | `1.0.0a260730` | alpha version |
| `agent-framework-hosting-mcp` | `1.0.0a260730` | alpha version |
| `agent-framework-hosting-responses` | `1.0.0a260730` | alpha version |
| `agent-framework-hosting-telegram` | `1.0.0a260730` | alpha version |
| `agent-framework-azure-cosmos-memory` | `1.0.0a260730` | alpha version |
| `agent-framework-a2a` | `1.0.0b260730` | beta version |
| `agent-framework-anthropic` | `1.0.0b260730` | beta version |
| `agent-framework-azure-ai-search` | `1.0.0b260730` | beta version |
| `agent-framework-azure-contentunderstanding` | `1.0.0b260730` | beta version |
| `agent-framework-azure-cosmos` | `1.0.0b260730` | beta version |
| `agent-framework-bedrock` | `1.0.0b260730` | beta version |
| `agent-framework-chatkit` | `1.0.0b260730` | beta version |
| `agent-framework-claude` | `1.0.0b260730` | beta version |
| `agent-framework-copilotstudio` | `1.0.0b260730` | beta version |
| `agent-framework-devui` | `1.0.0b260730` | beta version |
| `agent-framework-foundry-hosting` | `1.0.0b260730` | beta version |
| `agent-framework-foundry-local` | `1.0.0b260730` | beta version |
| `agent-framework-gemini` | `1.0.0b260730` | beta version |
| `agent-framework-hyperlight` | `1.0.0b260730` | beta version |
| `agent-framework-lab` | `1.0.0b260730` | beta version |
| `agent-framework-mem0` | `1.0.0b260730` | beta version |
| `agent-framework-mistral` | `1.0.0b260730` | beta version |
| `agent-framework-monty` | `1.0.0b260730` | beta version |
| `agent-framework-ollama` | `1.0.0b260730` | beta version |
| `agent-framework-purview` | `1.0.0b260730` | beta version |
| `agent-framework-redis` | `1.0.0b260730` | beta version |
| `agent-framework-tools` | `1.0.0b260730` | beta version |

Status here is based on the PEP 440 prerelease marker of the package version string. Package
metadata classifiers and the stability of individual features are judged separately in the feature
matrix.

## Analysis criteria

- Features added after this commit are not part of the current Java design baseline.
- A feature that was removed at the current commit but still appears in external documentation is
  recorded as not implemented or as removed.
- Feature completeness is not judged from the existence of a public API alone; the actual execution
  path and its tests are checked.
- Samples are used for feature discovery but are not used on their own as evidence of a production
  contract.
- Provider-only features are not generalized into common core features.
- The experimental, alpha, beta, and stable states are not merged into a single support level.
- Code citations use GitHub permalinks at this full commit.

## Snapshot change procedure

1. Record the new `main` commit and tree hash.
2. Compute the Git archive SHA-256 and the file counts.
3. Record package version and stability changes.
4. Derive the source tree and public API differences against the existing snapshot.
5. Reflect the added, changed, and removed states in the feature matrix.
6. Approve the Java support target and the implementation impact in a separate delta document.
