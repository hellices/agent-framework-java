# 30. Packaging & Compatibility

## State

- Document status: upstream snapshot analysis document
- Reference snapshot: `d0a4165f170193ba1d026a259af40d35bb7eaefe`
- Analysis scope: package/project layout, release metadata, stable/alpha/beta/experimental determination, versioning, dependency bounds, CI release validation, cross-language compatibility, snapshot update policy
- Out of scope:
  - observability/telemetry detailed behavior is owned by the observability document
  - error taxonomy/timeout/security boundary is owned by the errors-resilience-security document
  - detailed behavior of the evaluation algorithm and conformance corpus is owned by the evaluation-testing document
  - the object format contract for serialization/versioning is owned by the session document; this document covers only versioning from the deployment/release/compatibility perspective

## Snapshot summary

This snapshot uses a **multilingual monorepo** structure that places `.NET`, Python, and declarative assets together in a single repository. The repository's sparse checkout and Python workspace settings show that the repository operational units are separated into per-language subtrees; Python is organized as a `packages/*` workspace, and `.NET` is grouped around central package version management and multiple SDK-style projects. On the Python side, package-level lifecycle documentation is very explicit; on the `.NET` side, the release stage is expressed through build metadata such as `IsReleased`, `IsReleaseCandidate`, and `VersionSuffix` in each csproj.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/dotnet-build-and-test.yml#L106-L114  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/pyproject.toml#L73-L77  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CODING_STANDARD.md#L309-L355  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L5-L18  

Release metadata and compatibility gates also differ by language. `.NET` manages the central `VersionPrefix`, preview/RC/stable package version rules, SourceLink, symbols, package validation baseline, and NuGet audit in `dotnet/nuget/nuget-package.props`, and CI also performs an install check that packs the actual packages and then installs and builds them in a new console application. Python exposes the stable classifier and release URLs in the root `pyproject.toml`, declares alpha/beta/rc/released/deprecated in `PACKAGE_STATUS.md`, and manages dependency bounds through coding standards and validation tasks.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/nuget/nuget-package.props#L3-L10  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/nuget/nuget-package.props#L16-L33  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/nuget/nuget-package.props#L35-L60  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/dotnet-build-and-test.yml#L130-L164  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/pyproject.toml#L1-L27  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L5-L12  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CODING_STANDARD.md#L413-L431  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/DEV_SETUP.md#L386-L401  

Cross-language compatibility does not mean “sharing the same package manager semantics.” In practice, Python uses a PyPI-style package lifecycle and bounded dependency ranges, while `.NET` uses multi-TFM binary compatibility and a package validation baseline. However, the repository states that it performs conformance and engineering testing across `.NET` and Python, and the Python changelog shows a compatibility strategy of maintaining re-exports and the install surface even when code is moved to an external extension repo, so that existing imports are not broken. That is, cross-language compatibility is achieved not through an identical distribution mechanism but through **maintaining public surfaces, test-based behavioral alignment, and staged promotion**.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/TRANSPARENCY_FAQ.md#L26-L28  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CHANGELOG.md#L10-L11  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CHANGELOG.md#L29-L29  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CHANGELOG.md#L109-L122  

## Purpose and boundary of the original functionality

### 1. Purpose of package / project layout

The layout purpose of this repository is to simultaneously satisfy per-feature package separation, per-language build system independence, and selective installation. Python is decomposed into an `agent-framework` meta package and sub-packages under `packages/*`, with the core serving as the lazy-loading root surface. `.NET` maintains multiple `dotnet/src/*` projects as individual NuGet packages while applying central version management and common build props.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CODING_STANDARD.md#L309-L355  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/README.md#L17-L37  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/Directory.Packages.props#L3-L6  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests.csproj#L19-L23  

### 2. Purpose of release metadata

The purpose of release metadata is to calculate artifact versions, expose package feed/portal/debugging metadata consistently, and apply a validation policy per release stage. `.NET` centralizes `VersionPrefix`, `PackageVersion`, `GitTag`, package URL, SourceLink, symbols, docs, and package validation baseline. Python defines the installation and release surface through project version, homepage/source/release-notes URL, and PyPI classifiers.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/nuget/nuget-package.props#L3-L11  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/nuget/nuget-package.props#L35-L60  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/pyproject.toml#L1-L27  

### 3. Purpose of stable / alpha / beta / experimental determination

Release stage labeling exists to align user expectations with CI policy. Python explicitly designates the package lifecycle as `alpha`, `beta`, `rc`, `released`, and `deprecated`, and separately manages a feature-level `experimental` stage. For `.NET`, based on the evidence collected in this snapshot, `released`, `release candidate`, and `preview` are directly confirmed at the package metadata level, and it cannot be confirmed whether the same `alpha`/`beta` package taxonomy as Python is directly reflected in csproj metadata. Instead, `.NET` has an API-level `[Experimental]` usage and an `ExperimentalAttribute` injection path for legacy TFMs.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L5-L12  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L60-L66  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L87-L153  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Microsoft.Agents.AI.csproj#L3-L6  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Purview/Microsoft.Agents.AI.Purview.csproj#L3-L5  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/Microsoft.Agents.AI.Hosting.csproj#L3-L5  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/OpenTelemetryAgent.cs#L97-L98  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/eng/MSBuild/LegacySupport.props#L14-L16  

### 4. Purpose of versioning

The purpose of versioning is to manage the release train, automate the distinction between preview/RC/stable artifacts, and make explicit what compatibility promises consuming projects can expect. `.NET` layers a preview/RC/stable suffix policy on top of a central `VersionPrefix`, and Python uses a semver-based root version, package-specific version tags, and changelog-driven promotion.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/nuget/nuget-package.props#L3-L10  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/pyproject.toml#L1-L8  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CHANGELOG.md#L13-L29  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CHANGELOG.md#L60-L74  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CHANGELOG.md#L113-L122  

### 5. Purpose of dependency bounds

Dependency bounds exist to widen the supported range of external dependencies while promising only validated ranges. Python coding standards require a lower bound + explicit upper major cap for stable dependencies, and a stricter bounded range for prerelease/`<1.0` dependencies. `.NET` manages dependency version bounds through central package version pinning and transitive pinning rather than per-package specs.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CODING_STANDARD.md#L420-L431  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/DEV_SETUP.md#L386-L401  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/pyproject.toml#L60-L67  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/Directory.Packages.props#L3-L6  

### 6. Purpose of CI release validation

The purpose of CI release validation is to guarantee, more strongly than “the build succeeds,” that “the package is actually installed, is compatible with the baseline, and passes the audit criteria.” `.NET` does this through package validation and install check, and Python through dependency-bound validation and release-tag build workflow.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/CONTRIBUTING.md#L77-L97  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/nuget/nuget-package.props#L16-L33  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/dotnet-build-and-test.yml#L130-L164  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/DEV_SETUP.md#L386-L401  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/python-release.yml#L14-L63  

### 7. Purpose of cross-language compatibility

The purpose of cross-language compatibility lies not in providing identical functionality through identical distribution methods, but in **stably maintaining the public contract and expected behavior** while using each language's own package system. The fact that the Python changelog states that re-exports and the install surface are maintained even when moving durable/azurefunctions integration to an external extension repo is a good example of this strategy.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/TRANSPARENCY_FAQ.md#L26-L28  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CHANGELOG.md#L10-L11  

### 8. Purpose of the snapshot update policy

The purpose of the snapshot document update policy is to regenerate downstream documents without missing upstream stage promotions, repository splits, dependency compatibility adjustments, and release metadata changes. Because this repository's changelog and package status documents very actively record lifecycle changes and compatibility adjustments, they are suitable as the primary triggers for snapshot updates.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CHANGELOG.md#L10-L11  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CHANGELOG.md#L25-L29  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CHANGELOG.md#L66-L74  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CHANGELOG.md#L109-L122  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L13-L58  

## Public APIs

The “public APIs” in this document refer not to runtime method APIs but to the **deployment/metadata/compatibility surface** on which external consumers and downstream maintainers depend.

### .NET public surface

1. `IsReleased`, `IsReleaseCandidate`, `VersionSuffix`  
   - csproj metadata that determines the package stage  
   Source:  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Microsoft.Agents.AI.csproj#L3-L6  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Purview/Microsoft.Agents.AI.Purview.csproj#L3-L5  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/Microsoft.Agents.AI.Hosting.csproj#L3-L5  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.A2A/Microsoft.Agents.AI.A2A.csproj#L3-L6  

2. `VersionPrefix`, `PackageVersion`, `GitTag`, `PackageValidationBaselineVersion`, `EnablePackageValidation`, package URLs and symbols  
   - central package metadata and compatibility policy  
   Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/nuget/nuget-package.props#L3-L60

3. `ManagePackageVersionsCentrally`, `CentralPackageTransitivePinningEnabled`, `PackageVersion Include=...`  
   - central dependency version surface  
   Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/Directory.Packages.props#L3-L24

4. `CompatibilitySuppressions.xml`  
   - approved break/suppression artifact  
   Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/CompatibilitySuppressions.xml#L1-L120

5. `LegacySupport.props`  
   - compatibility build surface that injects experimental/trim/init/diagnostic shims for legacy TFMs  
   Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/eng/MSBuild/LegacySupport.props#L2-L32

### Python public surface

1. root `pyproject.toml`  
   - project name/version, stable classifier, release note/source URLs, Python requirement  
   Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/pyproject.toml#L1-L27

2. `PACKAGE_STATUS.md`  
   - alpha/beta/rc/released/deprecated package lifecycle and feature-level staged API status  
   Source:  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L5-L12  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L13-L58  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L60-L153  

3. `tool.uv.workspace`, `tool.uv.sources`, dependency/constraint override settings  
   - Python monorepo build/install surface and dependency bound override surface  
   Source:  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/pyproject.toml#L60-L77  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/pyproject.toml#L76-L112  

4. Python release tag format  
   - `python-<package>-<version>`  
   Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/python-release.yml#L36-L49

5. Dependency bounds policy in coding standard / validation tasks  
   - stable/pre-release/<1.0 dependency bound rules  
   Source:  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CODING_STANDARD.md#L413-L431  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/DEV_SETUP.md#L386-L401  

## Detailed execution flow

### 1. .NET release version calculation flow

1. The central `VersionPrefix`, `DateSuffix`, and `RCNumber` are defined.
2. If `IsReleaseCandidate=true`, then `PackageVersion = <VersionPrefix>-rc<RCNumber>`.
3. If `IsReleaseCandidate!=true` and `VersionSuffix != ''`, then `<VersionPrefix>-<VersionSuffix>.<DateSuffix>.1` is used.
4. If `IsReleaseCandidate!=true` and `VersionSuffix == ''`, then `<VersionPrefix>-preview.<DateSuffix>.1` is used.
5. If `IsReleased=true`, the stable `<VersionPrefix>` without a suffix is used.  
Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/nuget/nuget-package.props#L3-L10

### 2. .NET package validation / compatibility flow

1. The shared props set `PackageValidationBaselineVersion=1.0.0`.
2. Package validation is automatically enabled only for packages with `IsReleased=true`.
3. If CP diagnostics occur during a release build/pack, the CONTRIBUTING document specifies:
   - if the break is unintentional, refactor
   - if the break is intentional, create a suppression file  
4. Approved breaks are recorded in `CompatibilitySuppressions.xml`.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/nuget/nuget-package.props#L16-L23  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/CONTRIBUTING.md#L77-L97  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/CompatibilitySuppressions.xml#L1-L120  

### 3. .NET install validation flow

CI does not only pack.
1. Pack the solution in the release configuration.
2. Create a new temporary console app.
3. Add local artifacts as a source and add NuGet.org.
4. Actually add the `Microsoft.Agents.AI` and `Microsoft.Agents.AI.LocalCodeAct` packages and then build.  
This step verifies “whether the NuGet metadata is actually consumable.”  
Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/dotnet-build-and-test.yml#L130-L164

### 4. Python install surface and selective installation flow

Python provides two install stories.
1. Development mode that receives the entire framework and all sub-packages via `pip install agent-framework`
2. Selective install of only the packages required for the needed integration  
In addition, released packages no longer require `--pre`, while preview connectors still require `--pre`. This difference reflects package maturity in the actual installation UX.  
Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/README.md#L7-L37

### 5. Python lifecycle determination flow

The `PACKAGE_STATUS.md` operates as the single source of truth for Python package status.
- meta package/root package
- each integration package
- deprecated package  
Feature-level experimental APIs are managed separately from package status. That is, even if a package is released, a feature within it may be experimental.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L13-L58  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L60-L153  

### 6. Python dependency bounds / validation flow

1. The coding standard defines the bound rules for stable/prerelease/`<1.0` dependencies.
2. `validate-dependency-bounds-test` performs a workspace-wide lower/upper resolution gate.
3. `validate-dependency-bounds-project` explores and validates the actual lower/upper range for a specific package/dependency.
4. `add-dependency-and-validate-bounds` combines dependency addition and bound validation.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CODING_STANDARD.md#L420-L431  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/DEV_SETUP.md#L386-L401  

### 7. Python release asset build flow

The release workflow runs only when a published GitHub release starts with a `python-` tag. It extracts the package name from the tag, verifies the existence of `packages/<package>`, performs `uv run poe --directory packages/<package> build`, and uploads the output as a GitHub release asset.  
Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/python-release.yml#L14-L63

### 8. Cross-language compatibility maintenance flow

The Python changelog explains that although the durabletask/azurefunctions integration has moved to a separate repository, the core re-exports public symbols and the `all` extra installation surface is also maintained. This approach, in that it preserves the consumer-facing import/install contract even when the implementation location changes, well illustrates the operating principles of cross-language/extension compatibility.  
Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CHANGELOG.md#L10-L11

### 9. Snapshot update policy flow

It is reasonable for downstream document updates for this snapshot to be triggered by changes in changelog and status files.
- package promotion (`rc -> stable`, `preview/beta` transition)
- dependency compatibility adjustment
- repo split / package move
- changes to the main installation surface  
This is because all of these are directly reflected in changelog and status documents.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CHANGELOG.md#L10-L11  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CHANGELOG.md#L66-L74  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CHANGELOG.md#L113-L122  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L13-L58  

## State and configuration

### Repository / monorepo layout

- The repository operational units are separated into at least `dotnet/`, `python/`, and `declarative-agents/`.
- Python has a workspace `packages/*` structure, and the root meta project is responsible for the entire install surface.
- `.NET` separates `src` package projects and `tests` projects, and the test csproj references the source project via an explicit `ProjectReference`.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/dotnet-build-and-test.yml#L106-L114  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/pyproject.toml#L73-L77  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CODING_STANDARD.md#L309-L355  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests.csproj#L19-L23  

### `.NET` current stage examples

- released: `Microsoft.Agents.AI`
- release candidate: `Microsoft.Agents.AI.Purview`
- preview: `Microsoft.Agents.AI.Hosting`, `Microsoft.Agents.AI.A2A`  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Microsoft.Agents.AI.csproj#L3-L6  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Purview/Microsoft.Agents.AI.Purview.csproj#L3-L5  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/Microsoft.Agents.AI.Hosting.csproj#L3-L5  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.A2A/Microsoft.Agents.AI.A2A.csproj#L3-L6  

### Python current stage examples

- released: `agent-framework`, `agent-framework-core`, `agent-framework-foundry`, `agent-framework-openai`, `agent-framework-orchestrations`
- beta: `agent-framework-anthropic`, `agent-framework-bedrock`, `agent-framework-tools`, etc.
- alpha: `agent-framework-hosting`, `agent-framework-hosting-a2a`, `agent-framework-hosting-mcp`, `agent-framework-azure-cosmos-memory`
- deprecated: `agent-framework-azure-ai`  
Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L15-L58

### Experimental determination configuration

- Python explicitly separates feature-level experimental in the package status document.
- For `.NET`, based on the evidence collected in this snapshot, API-level `[Experimental]` and `ExperimentalAttribute` support for legacy TFMs are visible rather than a package-level experimental taxonomy.
- The alpha/beta package taxonomy of `.NET` cannot be confirmed from the evidence collected in this snapshot alone.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L60-L153  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/OpenTelemetryAgent.cs#L97-L98  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/eng/MSBuild/LegacySupport.props#L14-L16  

## Errors and security

### 1. Supply-chain security

`.NET` enables transitive pinning under central package management and activates NuGet audit. Python enforces security floors through `constraint-dependencies` and `override-dependencies`. That is, both treat dependency compatibility not merely as “build convenience” but as supply chain risk management.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/Directory.Packages.props#L3-L6  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/nuget/nuget-package.props#L25-L33  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/pyproject.toml#L63-L66  

### 2. Patched dependency pinning

The `.NET` central package file leaves a comment noting that a transitive dependency has been pinned to a higher version due to vulnerability remediation. Python likewise records security-motivated adjustments via override dependency comments. Such comments are important evidence in downstream compatibility documentation.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/Directory.Packages.props#L23-L24  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/Directory.Packages.props#L77-L80  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/pyproject.toml#L63-L66  

### 3. Handling of compatibility validation failures

`.NET` interprets CP diagnostics as unintentional breaking changes and permits suppression file creation only when approved. Python guides re-validation of the actual support range before widening bounds when lower/upper dependency bounds validation fails. That is, both treat a compatibility failure not as “a warning to be ignored” but as **a failure that requires explicit design judgment**.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/CONTRIBUTING.md#L90-L97  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/DEV_SETUP.md#L386-L401  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CODING_STANDARD.md#L420-L431  

### 4. Release build failure surface

- The Python release workflow extracts the package from the tag, verifies whether the actual directory exists, and fails if it is not found.
- For `.NET`, if any of release build + package validation + install check fails, the consumability of the artifact is not guaranteed.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/python-release.yml#L36-L47  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/dotnet-build-and-test.yml#L123-L164  

## .NET implementation

### 1. Central version/package metadata

`.NET` centrally manages version calculation, package validation baseline, audit, package URLs, docs file, SourceLink, and symbols in `dotnet/nuget/nuget-package.props`. Individual csproj files differ only in their stage flags, and common release metadata imports from the central props.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/nuget/nuget-package.props#L3-L60  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Microsoft.Agents.AI.csproj#L3-L19  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Purview/Microsoft.Agents.AI.Purview.csproj#L3-L13  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/Microsoft.Agents.AI.Hosting.csproj#L3-L13  

### 2. Central dependency version management

`.NET` uses `ManagePackageVersionsCentrally` and `CentralPackageTransitivePinningEnabled`. This causes individual csproj files to use a central version registry instead of redundantly declaring version strings directly. A broad dependency set such as OpenTelemetry, Azure, System.*, and Aspire.* is also pinned in the central file.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/Directory.Packages.props#L3-L6  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/Directory.Packages.props#L14-L24  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/Directory.Packages.props#L26-L33  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/Directory.Packages.props#L66-L80  

### 3. Binary/API compatibility strategy

`.NET` applies a package validation baseline to release packages and records intentional breaks in a suppression file. In addition, `LegacySupport.props` injects compiler/runtime support shims for older TFMs to preserve multi-targeting compatibility.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/nuget/nuget-package.props#L16-L23  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/CompatibilitySuppressions.xml#L1-L120  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/eng/MSBuild/LegacySupport.props#L2-L32  

### 4. Characteristics of the stage vocabulary

The vocabulary directly visible in `.NET` build metadata is released / release-candidate / preview. A form in which a package-level alpha/beta table is organized in a single document file, as in Python, is not directly confirmed in the evidence collected in this snapshot.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Microsoft.Agents.AI.csproj#L3-L6  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Purview/Microsoft.Agents.AI.Purview.csproj#L3-L5  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/Microsoft.Agents.AI.Hosting.csproj#L3-L5  

## Python implementation

### 1. Meta package + workspace package structure

Python's root `agent-framework` meta package serves as the overall framework umbrella, and the actual implementation resides in sub-packages under `packages/*`. The selective install guidance, workspace declaration, and package structure description in the coding standard together illustrate this structure.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/pyproject.toml#L1-L27  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/pyproject.toml#L73-L77  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/README.md#L17-L37  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CODING_STANDARD.md#L309-L355  

### 2. Lifecycle and feature stage are documented

Python tracks package lifecycle status directly in documentation and identifies experimental features separately from package-level status. Therefore, even if a package is `released`, internal features may be experimental.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L13-L58  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L60-L153  

### 3. Dependency bounds and compatibility support range

Python explicitly states a philosophy of maintaining a wide range within validated bounds rather than fixing bounds narrowly. It allows version-conditional imports or branches to widen the support range, but widens bounds only after actually running lower/upper validation.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CODING_STANDARD.md#L420-L431  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/DEV_SETUP.md#L386-L401  

### 4. Promotion / deprecation / repo split signals are well reflected in the changelog

The Python changelog records all of: package promotions, compatibility adjustments, dependency validation tooling, and repo splits. For example, GitHub Copilot package stable promotion, declarative stable promotion, foundry-hosting beta promotion, and extension repo split are all strong signals for downstream snapshot updates.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CHANGELOG.md#L10-L11  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CHANGELOG.md#L66-L74  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CHANGELOG.md#L113-L122  

## Test evidence

### 1. `.NET` release/install validation evidence

CI does not merely stop at a simple pack after the build; it actually adds the package to a new console app and builds it. This verifies restore metadata, dependency resolution, and installability.  
Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/dotnet-build-and-test.yml#L130-L164

### 2. `.NET` compatibility validation evidence

The CONTRIBUTING document describes that package validation is compared against the baseline API surface during build/pack and documents the procedure for creating suppression files. An actual suppression file also exists in the repository.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/CONTRIBUTING.md#L77-L97  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/CompatibilitySuppressions.xml#L1-L120  

### 3. Python bounds/coverage validation evidence

The Python DEV_SETUP document specifies the dependency bound validation task as part of the actual workflow and also connects the coverage enforcement policy to package maturity. A separate coverage workflow verifies threshold 85 in CI.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/DEV_SETUP.md#L176-L190  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/DEV_SETUP.md#L386-L401  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/python-test-coverage.yml#L15-L46  

### 4. Python release packaging evidence

The Python release workflow checks the release tag prefix, extracts the package name from the tag, verifies the existence of the actual package directory, and then attaches the build artifact to the release. It is notable that the release asset build is directly tied to the package directory naming convention.  
Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/python-release.yml#L14-L63

## Differences between documentation and code

### 1. Discrepancy between `.NET` package validation description and actual condition name

The CONTRIBUTING document uses the expression “GA packages (`IsGenerallyAvailable=true`)”, but the actual central packaging props enable validation based on `IsReleased`. Therefore, the authoritative condition name in the current snapshot is `IsReleased`.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/CONTRIBUTING.md#L84-L88  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/nuget/nuget-package.props#L16-L20  

### 2. Difference between Python package stability and feature stability

The Python root package has a stable classifier and released status, but `PACKAGE_STATUS.md` separately tracks the feature-level experimental surface. Therefore, it must not be concluded that internal features are also stable based solely on reading the package-level release statement.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/pyproject.toml#L13-L24  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L60-L153  

### 3. Insufficient direct evidence for `.NET` alpha/beta taxonomy

While Python has explicit package-level alpha/beta/rc/released/deprecated, for `.NET` only released/RC/preview are explicitly confirmed in the evidence collected in this snapshot. Therefore, using `.NET beta` or `.NET alpha` in a 1:1 correspondence with Python package status is an overstatement based solely on the evidence of this snapshot.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L5-L12  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Microsoft.Agents.AI.csproj#L3-L6  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Purview/Microsoft.Agents.AI.Purview.csproj#L3-L5  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/Microsoft.Agents.AI.Hosting.csproj#L3-L5  

## Java design decisions

### Decision 1. Java monorepo is designed as a combination of root BOM + module-local artifacts

Java must combine the advantages of Python workspace and `.NET` central package management. Specifically:
- a root `platform` or BOM module declares common dependency versions
- actual functionality is separated into individual artifacts such as `core`, `foundry`, `hosting`, `tools`, and `orchestrations`
- a root “all-in-one” aggregator artifact is optional  
This structure provides both selective install and centrally managed compatibility at the same time.  
Evidence:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/Directory.Packages.props#L3-L6  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/README.md#L17-L37  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CODING_STANDARD.md#L309-L355  

### Decision 2. Package stage maintains both artifact metadata and a separate lifecycle registry

Java should not rely solely on build metadata as `.NET` does, and it is better to also maintain a lifecycle registry document such as Python's `PACKAGE_STATUS.md`. Artifact POM/Gradle metadata alone does not adequately expose operational information such as “beta vs preview vs deprecated”.  
Evidence:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L5-L58  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Microsoft.Agents.AI.csproj#L3-L6  

### Decision 3. Java dependency management combines BOM + validated bounds

Java should not be limited to `.NET`-style central pinning alone, and it is better to also adopt the Python-style validated bounds strategy. That is:
- the BOM pins the currently recommended tested version
- the library POM documents the supported lower/upper range
- CI has a lower/upper resolution smoke lane  
This is necessary to address both consumers' dependency convergence issues and upstream extensibility.  
Evidence:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/Directory.Packages.props#L3-L6  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CODING_STANDARD.md#L420-L431  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/DEV_SETUP.md#L386-L401  

### Decision 4. Binary/API compatibility gate is linked to the release stage

Java, like `.NET`, can enable the binary/API compatibility gate by default for stable/released artifacts, and may have a reporting-oriented or relaxed gate for preview/experimental artifacts. Intentional breaks must be recorded as suppression/waiver artifacts.  
Evidence:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/nuget/nuget-package.props#L16-L23  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/CONTRIBUTING.md#L77-L97  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/CompatibilitySuppressions.xml#L1-L120  

### Decision 5. Installability smoke test is included in the release pipeline

The Java release pipeline, before artifact publish:
- create an example consumer project
- declare BOM import or direct dependency
- verify actual build/resolve success  
must include these steps. `.NET`'s install check demonstrates that this strategy is effective.  
Evidence:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/dotnet-build-and-test.yml#L130-L164  

### Decision 6. Cross-language compatibility is assessed based on import surface and behavior

Compatibility documentation between Java and `.NET`/Python must be assessed based on the following rather than “the same version number scheme”.
- correspondence of public API names/concepts
- correspondence of stable vs experimental stage
- alignment of conformance/behavior tests
- whether a facade is maintained upon package split/repo split  
The re-export case in the Python changelog shows that maintaining the facade is essential.  
Evidence:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CHANGELOG.md#L10-L11  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/TRANSPARENCY_FAQ.md#L26-L28  

### Decision 7. The snapshot update policy is operated in a changelog-driven manner

Java upstream snapshot documents must be regenerated when the following changes occur.
1. package stage promotion/demotion
2. dependency compatibility adjustment
3. extension repo split or re-export change
4. change to the main installation surface
5. change to the central version/BOM  
The 1st-priority evidence for trigger determination must be changelog, package status, and central metadata files.  
Evidence:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CHANGELOG.md#L10-L11  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CHANGELOG.md#L25-L29  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CHANGELOG.md#L66-L74  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CHANGELOG.md#L109-L122  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L13-L58  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/nuget/nuget-package.props#L3-L23  

## Concrete acceptance scenarios

### Scenario 1. A `.NET` released package must have a stable version without a suffix

- Given: `IsReleased=true` in csproj
- When: the package version is calculated
- Then: `PackageVersion` must be `VersionPrefix` without a suffix.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/nuget/nuget-package.props#L7-L10  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Microsoft.Agents.AI.csproj#L3-L6  

### Scenario 2. A `.NET` preview package uses the preview version path

- Given: `VersionSuffix=preview` in csproj and not `IsReleased`
- When: the package version is calculated
- Then: the package version must include a preview suffix rather than being a stable version.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/nuget/nuget-package.props#L7-L10  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/Microsoft.Agents.AI.Hosting.csproj#L3-L5  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.A2A/Microsoft.Agents.AI.A2A.csproj#L3-L6  

### Scenario 3. A `.NET` released package must be subject to the package validation baseline

- Given: released `.NET` package
- When: a release build or pack is performed
- Then: package validation must operate comparing the baseline version against the current API surface.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/nuget/nuget-package.props#L16-L20  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/CONTRIBUTING.md#L77-L88  

### Scenario 4. A `.NET` intentional breaking change must leave a suppression artifact

- Given: approved breaking change
- When: package validation produces a CP error
- Then: a suppression file must be created, committed, and managed with a justification.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/CONTRIBUTING.md#L90-L97  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/CompatibilitySuppressions.xml#L1-L120  

### Scenario 5. Python selective install must distinguish between stable packages and preview packages

- Given: `agent-framework-foundry` and `agent-framework-copilotstudio`
- When: a user performs a selective install
- Then: a released package must be installable without `--pre`, and a preview connector must require `--pre`.  
Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/README.md#L17-L37

### Scenario 6. The Python lifecycle registry must explicitly state the package stage

- Given: `PACKAGE_STATUS.md`
- When: a downstream snapshot document reads the package maturity
- Then: the `alpha`/`beta`/`rc`/`released`/`deprecated` status of each package must be directly verifiable.  
Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L13-L58

### Scenario 7. A Python dependency range change must go through the validated bounds task

- Given: widening an external dependency bound or adding a new dependency
- When: a maintainer prepares the change
- Then: a workspace-wide or package-scoped bound validation task must be run first.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CODING_STANDARD.md#L420-L431  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/DEV_SETUP.md#L386-L401  

### Scenario 8. The Python release workflow must resolve the package directory from the tag

- Given: published release tag `python-<package>-<version>`
- When: the Python release workflow is run
- Then: the package must be extracted from the tag, the existence of `packages/<package>` must be verified, and the build artifact must be uploaded.  
Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/python-release.yml#L36-L63

### Scenario 9. Snapshot update must be re-run when there are promotion/compatibility signals

- Given: stable promotion, beta promotion, compatibility adjustment, and repo split are recorded in the changelog
- When: a downstream snapshot document is maintained
- Then: the packaging/compatibility document must be regenerated or at minimum reviewed.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CHANGELOG.md#L10-L11  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CHANGELOG.md#L25-L29  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CHANGELOG.md#L66-L74  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CHANGELOG.md#L109-L122  

## Source inventory

### Repository layout and metadata

- `.github/workflows/dotnet-build-and-test.yml`  
  - multi-language sparse checkout, `.NET` build/test flow, install check, coverage threshold  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/dotnet-build-and-test.yml#L106-L114  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/dotnet-build-and-test.yml#L130-L164  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/dotnet-build-and-test.yml#L317-L336  

- `dotnet/nuget/nuget-package.props`  
  - version prefix/suffix logic, validation baseline, audit, URLs, SourceLink, symbols  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/nuget/nuget-package.props#L3-L60

- `dotnet/Directory.Packages.props`  
  - central package version management and transitive pinning  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/Directory.Packages.props#L3-L24  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/Directory.Packages.props#L26-L80  

- `dotnet/eng/MSBuild/LegacySupport.props`  
  - compatibility shims for older TFMs  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/eng/MSBuild/LegacySupport.props#L2-L32

- `dotnet/src/Microsoft.Agents.AI/Microsoft.Agents.AI.csproj`  
  - released stage, eval limited to net8.0+  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Microsoft.Agents.AI.csproj#L3-L6  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Microsoft.Agents.AI.csproj#L36-L42  

- `dotnet/src/Microsoft.Agents.AI.Purview/Microsoft.Agents.AI.Purview.csproj`  
  - RC stage example  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Purview/Microsoft.Agents.AI.Purview.csproj#L3-L5  

- `dotnet/src/Microsoft.Agents.AI.Hosting/Microsoft.Agents.AI.Hosting.csproj`  
  - preview stage example  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/Microsoft.Agents.AI.Hosting.csproj#L3-L5  

- `dotnet/src/Microsoft.Agents.AI.A2A/Microsoft.Agents.AI.A2A.csproj`  
  - preview stage example  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.A2A/Microsoft.Agents.AI.A2A.csproj#L3-L6  

- `dotnet/src/Microsoft.Agents.AI/CompatibilitySuppressions.xml`  
  - approved compatibility suppressions  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/CompatibilitySuppressions.xml#L1-L120

- `CONTRIBUTING.md`  
  - automated API compatibility validation policy  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/CONTRIBUTING.md#L71-L97

### Python package/lifecycle/version sources

- `python/pyproject.toml`  
  - project metadata, stable classifiers, workspace, sources, security override dependencies  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/pyproject.toml#L1-L27  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/pyproject.toml#L60-L77  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/pyproject.toml#L76-L112  

- `python/README.md`  
  - development vs selective install, released vs preview `--pre` guidance  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/README.md#L7-L37

- `python/PACKAGE_STATUS.md`  
  - lifecycle buckets, package states, feature-level stage table  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L5-L58  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L60-L153  

- `python/CODING_STANDARD.md`  
  - monorepo/package structure, versioning and dependency bounds rules  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CODING_STANDARD.md#L309-L355  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CODING_STANDARD.md#L413-L431  

- `python/DEV_SETUP.md`  
  - coverage enforcement, dependency bound validation tasks  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/DEV_SETUP.md#L176-L190  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/DEV_SETUP.md#L386-L401  

- `python/CHANGELOG.md`  
  - extension repo split, validation tooling changes, stage promotions, compatibility adjustments  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CHANGELOG.md#L10-L11  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CHANGELOG.md#L25-L29  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CHANGELOG.md#L66-L74  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CHANGELOG.md#L109-L122  

### Release workflows and cross-language references

- `.github/workflows/python-release.yml`  
  - release tag parsing and package asset build  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/python-release.yml#L14-L63  

- `TRANSPARENCY_FAQ.md`  
  - engineering/integration/conformance testing across `.NET` and Python  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/TRANSPARENCY_FAQ.md#L26-L28