# Gradle Kotlin DSL and Java ARC Foundation Design

- Status: approved
- Date: 2026-08-10
- Revised: 2026-08-11 — the 5.3 registry decision and the new 5.4 target environment settled with measured values
- Supersedes: the Maven build and the general-purpose `aks-runners` usage decision of the existing engineering harness design
- Retains: the repository, local, and platform ownership separation, MAF conformance, the agent DAG, and the three-layer regression strategy

## 1. Decision

The build harness of Agent Framework for Java uses Gradle Kotlin DSL. The trusted Java jobs of
GitHub Actions run on a dedicated Java-only ARC scale set `arc-java-build` rather than on the
existing general-purpose `aks-runners`.

The two assets are separated as follows.

- Application repository: the Gradle Wrapper, the Kotlin DSL build logic, the quality rules, the tests, and the workflows
- Separate local platform repository: the Java runner image, image verification, the ARC Helm values, and the Kubernetes policy
- Azure, AKS, and GitHub configuration: the registry credential, the GitHub App key, the federated identity, and the runner group

The Maven implementation on the existing `harness-foundation` branch is not merged. Its design
rationale is preserved, but the implementation is done independently on the new Gradle branch.

## 2. Considered approaches

### 2.1 A general-purpose runner with CI installation

Install the JDK and Gradle on every job on the existing `aks-runners`. The initial change is small,
but the download cost repeats on an ephemeral runner and the Java toolchain is not pinned as a
platform contract.

### 2.2 A per-JDK image and scale set

Keep an image and an ARC scale set for each of JDK 17, 21, and 25. The isolation is clear, but the
images, Helm releases, security policies, and autoscaling targets to operate triple in number.

### 2.3 A single Java image with the Gradle Wrapper

Include JDK 17, 21, and 25 in the runner image in the GitHub tool-cache format and pin the Gradle
version with the repository's Gradle Wrapper. `actions/setup-java` then selects the preinstalled
toolchain without a download.

**Decision:** adopt 2.3. It secures both the reproducibility of the JDK matrix and the simplicity of
runner operations, and it leaves ownership of the Gradle version in the repository.

## 3. The application repository build

### 3.1 Baseline

- Gradle Wrapper: 9.7.0
- Gradle runtime: JDK 17 or later
- Java source and bytecode baseline: release 17
- CI toolchain matrix: Eclipse Temurin 17, 21, 25
- Build scripts: Kotlin DSL only
- Dependency versions: `gradle/libs.versions.toml`
- Shared plugin configuration: the included build `build-logic`

The Gradle distribution and the wrapper JAR checksum are verified. A system Gradle is not used for
anything other than wrapper generation and bootstrap.

### 3.2 File structure

```text
agent-framework-java/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradle/
│   ├── libs.versions.toml
│   └── wrapper/
├── gradlew
├── gradlew.bat
├── build-logic/
│   ├── settings.gradle.kts
│   └── src/main/kotlin/
│       ├── agentframework.java-library-conventions.gradle.kts
│       ├── agentframework.quality-conventions.gradle.kts
│       └── agentframework.test-conventions.gradle.kts
└── build-tools/
    └── harness-policy/
```

The root project contains no product code. A product module is added only when an actual vertical
slice is implemented.

### 3.3 Convention plugin boundaries

`java-library-conventions` owns the Java toolchain 17, `options.release=17`, UTF-8, the compiler
lint, reproducible archives, and dependency locking.

`test-conventions` owns JUnit 5, AssertJ, the deterministic test defaults, and the test reports.
Each per-JDK compatibility task uses Gradle Java Toolchains to run the tests with the corresponding
launcher.

`quality-conventions` owns the Spotless, Checkstyle, PMD, SpotBugs, and JaCoCo configuration. The
formatter prefers an engine that does not depend on javac internal APIs.

## 4. Verification graph

The formatter and static analysis run only once, and JDK compatibility concentrates on compilation
and tests.

```text
policy
  |
  +--> quality (Gradle runtime JDK 17)
  |
  +--> testJava17
  +--> testJava21
  +--> testJava25
  |
  +--> fan-in --> required CI
```

`quality` runs format, Checkstyle, PMD, SpotBugs, the dependency policy, architecture, and the
coverage report. `testJava17/21/25` does not run the quality plugins again. This separation prevents
a tool coupled to JDK compiler internals, such as google-java-format, from breaking the entire
matrix.

CI and local commands use the same Gradle tasks.

- `./gradlew policyCheck`
- `./gradlew quality`
- `./gradlew testJava17 testJava21 testJava25`
- `./gradlew check`

## 5. The Java-only runner image

### 5.1 A separate platform repository

The default path of the local sibling repository is as follows.

```text
/Users/hwang-inhwan/workspace/agent-framework-java-platform/
```

This repository is managed as an independent Git repository so that it can later move to a private
infrastructure repository.

```text
agent-framework-java-platform/
├── runner-images/java/
│   ├── Dockerfile
│   ├── toolcache-manifest.json
│   ├── verify-image.sh
│   └── README.md
├── arc/arc-java-build/
│   ├── values.yaml
│   ├── namespace.yaml
│   ├── network-policy.yaml
│   └── README.md
└── scripts/
    ├── build-java-runner.sh
    ├── publish-java-runner.sh
    ├── deploy-arc-java-build.sh
    └── verify-arc-java-build.sh
```

Real secrets and credentials are not committed to this repository either.

### 5.2 Image contents

The image is based on the official GitHub Actions runner image at a pinned digest and adds only the
following.

- Eclipse Temurin JDK 17, 21, 25
- `JAVA_HOME_17_X64`, `JAVA_HOME_21_X64`, `JAVA_HOME_25_X64`
- the GitHub tool-cache metadata and the `.complete` marker
- Git, curl, unzip, zip, jq, bash, and CA certificates
- the non-root `runner` user and `/home/runner/run.sh`

The Gradle distribution, the dependency cache, Azure credentials, kubectl, Helm, and the Docker
daemon are not put into the image. Gradle is owned by the repository wrapper, and dependencies use
the GitHub cache service.

The image build verifies the JDK archive checksums and the base image digest. `verify-image.sh`
checks the three JDK versions, `javac`, the tool-cache layout, the non-root user, and the runner
entrypoint.

### 5.3 Registry

**Decision (settled 2026-08-11):** reuse the existing Azure Container Registry `acrpensionguard` and
create only a new dedicated repository `gha-runners/agent-framework-java`. **Do not create a new
registry.**

`acrpensionguard` is a shared asset of `rg-pension-guard`, so this work neither creates the registry
nor changes any property such as its SKU, admin user, or network. The `AcrPull` role assignment for
the AKS kubelet managed identity already exists at registry scope, so the platform work does not
assume it and only re-confirms it idempotently.

Tags are for convenience, and the Helm values use the immutable digest.

```text
acrpensionguard.azurecr.io/gha-runners/agent-framework-java:<date>-<source-sha>
acrpensionguard.azurecr.io/gha-runners/agent-framework-java@sha256:<digest>
```

### 5.4 Target environment

The following values are the authoritative values confirmed on 2026-08-11 against the actual cluster
and subscription. The `rg-korvid-contract-test` / `aks-korvid-contract-test` / new-registry proposal
that appeared in earlier design discussion is discarded.

| Item | Value | Ownership |
| --- | --- | --- |
| kubectl context | `evalollama` | Existing |
| Azure subscription | `95933ae5-0201-4a21-a1fc-8051a7437982` (`ME-MngEnvMCAP310512-inhwanhwang-3`) | Existing |
| Resource group / location | `rg-pension-guard` / `koreacentral` | Existing |
| AKS cluster | `aks-shared-runners` (Kubernetes 1.35, Cilium dataplane) | Existing |
| Container registry | `acrpensionguard` / `acrpensionguard.azurecr.io` (Basic, admin disabled) | Existing, reused |
| Image repository | `gha-runners/agent-framework-java` | New |
| ARC chart | `gha-runner-scale-set` `0.14.2` | Existing |
| Existing scale sets | `aks-runners`, `aks-runners-flutter`, and `korvid-runners` in `arc-runners` | Existing, must not change |
| New scale set | `arc-java-build` in `arc-runners-java` | New |
| GitHub credential | secret `gha-token` | Existing, copied |

The existing `aks-runners` and `aks-runners-flutter` target
`https://github.com/open-play-ground/grown-up`, and `korvid-runners` targets
`https://github.com/hellices/korvid`; all three reference the `gha-token` of the `arc-runners`
namespace. The new scale set targets
`https://github.com/open-play-ground/agent-framework-java` and references a secret of the same name
in its own namespace.

## 6. The ARC scale set

`arc-java-build` is added as a new Helm release without modifying the existing scale sets.

- runner scale set name: `arc-java-build`
- namespace: `arc-runners-java`
- container name: `runner`
- image: the Java runner image digest
- `minRunners: 0`
- an initial `maxRunners: 5`
- non-privileged
- `allowPrivilegeEscalation: false`
- every Linux capability dropped
- a dedicated service account
- the GitHub credential used only as a secret reference
- Docker-in-Docker and the Kubernetes container mode not used

A Kubernetes Secret is namespace-scoped, so the `gha-token` of `arc-runners` cannot be referenced as
is from `arc-runners-java`. The cluster operator copies it with
`scripts/copy-github-config-secret.sh`, and that script reads the secret as JSON but handles it only
inside a single pipeline that ends with `kubectl apply --namespace "$ARC_NAMESPACE" -f -`. The whole
document is never printed to a terminal, a log, or a file; only the key names are shown. The values
are never created, decoded, or committed.

The NetworkPolicy denies by default and then permits only DNS, the GitHub Actions endpoints, the
GitHub API, Maven Central, the Gradle Plugin Portal, the Gradle distribution service, and approved
artifact registries over HTTPS. The AKS IMDS and the Kubernetes API are blocked from the runner pod.

The ARC controller, listener, and ephemeral runner logs are collected into Azure Monitor or the
existing cluster telemetry before the pod is deleted.

## 7. GitHub Actions

### 7.1 The trusted path

A same-repository PR, a `main` push, and a manual dispatch run on `arc-java-build`.

- `quality`: JDK 17
- `testJava17`: JDK 17
- `testJava21`: JDK 21
- `testJava25`: JDK 25

`actions/setup-java` selects the image's tool cache, and a network download that occurs is recorded
as a runner image regression.

Only PR concurrency is cancelled. A `main` push is not cancelled.

### 7.2 The fork path

A fork PR runs a minimal `policyCheck`, `quality`, and `testJava17` on `ubuntu-latest` with a
read-only token and no secrets. The trusted ARC jobs are skipped explicitly.

Branch protection fans the fork and trusted paths into a single required result job so that they
cannot become skipped-green without real verification.

### 7.3 Workflow policy

A single workflow is not checked with one or two regular expressions. The repository policy test
parses every `.github/workflows/*.yml` and `*.yaml` and verifies the following.

- external actions and reusable workflows are pinned to a full SHA
- a local composite action is allowed only through a `./` path
- `pull_request_target` is forbidden
- the runner label allow-list
- the workflow and job permission allow-list
- `persist-credentials: false` on every checkout
- the mutually exclusive conditions of the fork and trusted runners
- no cancellation of a `main` run

## 8. Repository policy

A shell policy script is not left as a dead gate that can only be run standalone. A Gradle test or a
custom task in `build-tools` verifies the following and is wired into `check`.

- the size of and the links between `AGENTS.md` and the vendor adapters
- the Gradle wrapper URL and checksum
- the quality tool versions
- the artifact schemas and examples
- the workflow policy

When a shell script is needed, a Gradle task runs it or it is made a thin wrapper around a Java or
Kotlin policy test.

## 9. Artifact contracts

The foundation adds two contracts to the existing six so that they match the design DAG.

- `TaskIntent`
- `ChangeContext`
- `ImpactSet`
- `TestPlan`
- `ChangeSummary`
- `VerificationResult`
- `ReviewResult`
- `RunScore`

A JSON Schema 2020-12 validator verifies every example against the real schema. Checking only the
presence of top-level keys is not enough. Whether `additionalProperties` is explicitly `false` is
verified as well.

## 10. Errors and recovery

- An image checksum or image test failure: stop the publish and the Helm upgrade.
- A new scale set listener or runner failure: leave the existing `aks-runners` unchanged and roll back only the new release.
- A tool-cache miss: record it as a metric first rather than failing CI, and block it in the image test.
- A fork path failure: do not re-run it automatically on a trusted runner.
- A quality failure: do not cover it with a passing compatibility test.
- Missing ARC credential or registry information: complete the app build and mark only the platform deploy as explicitly blocked.
- A missing registry, a renamed registry, or an enabled admin user: `verify-acr.sh` stops as BLOCKED and changes
  nothing. Because the registry is shared, a fix is an out-of-band operator task.
- A missing `gha-token` in `arc-runners-java`: preflight blocks, the operator runs the copy script, and then it is
  re-run. There is no path that creates a credential.

## 11. Regression

### The application repository

- verifying convention plugin application and the task graph with Gradle TestKit
- the JDK 17, 21, and 25 toolchain tests
- the security policy based on a workflow YAML parser
- JSON Schema example validation
- the wrapper distribution and checksum regression

### The platform repository

- Dockerfile lint and base digest confirmation
- verification of the JDK, the tool cache, and the non-root user inside the image
- Helm template snapshots and schema validation
- verification with `kubectl --dry-run=server` or a separate test cluster
- an ephemeral runner smoke workflow after deployment

## 12. Stages

1. Exclude the Maven branch from the merge targets and settle the Gradle design and plan.
2. Create the Gradle Wrapper, the Kotlin DSL, build-logic, and the policy tests in the application repository.
3. Create the Java runner image and its verification in the sibling platform repository.
4. Confirm the existing ACR `acrpensionguard` and the current ARC authentication and namespace policy. Do not create a new registry.
5. Publish the image and deploy `arc-java-build` as a separate release.
6. After the smoke workflow, switch the application CI label to `arc-java-build`.
7. Verify the fork and trusted result fan-in and branch protection.

## 13. Success criteria

1. `./gradlew check` passes on JDK 17.
2. The Java 17, 21, and 25 compatibility tests verify the same source baseline.
3. The `arc-java-build` runner selects all three JDKs without an image download.
4. The runner pod is non-privileged and does not change the three existing scale sets (`aks-runners`, `aks-runners-flutter`, `korvid-runners`).
5. A fork PR does not turn green without GitHub-hosted verification.
6. Every repository policy is included in the Gradle `check`.
7. The runner image and the ARC configuration are reproducible outside the application repository.

## 14. References

- [Gradle 9.7.0 release metadata](https://services.gradle.org/versions/current)
- [Gradle Java compatibility](https://docs.gradle.org/9.7.0/userguide/compatibility.html)
- [Gradle Java Toolchains](https://docs.gradle.org/9.7.0/userguide/toolchains.html)
- [ARC custom runner image](https://docs.github.com/en/actions/how-tos/manage-runners/use-actions-runner-controller/deploy-runner-scale-sets#using-a-custom-runner-image)
- [setup-java](https://github.com/actions/setup-java)

