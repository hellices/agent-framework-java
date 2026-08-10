# GitHub Actions Runner Contract

## Merge gate: do not merge before `arc-java-build` exists

**This branch must not be merged into `main` until the `arc-java-build` scale set is live.**

`.github/workflows/ci.yml` schedules `trusted-quality` and `trusted-compatibility` on
`runs-on: arc-java-build`. That label is created by the platform plan
`docs/superpowers/plans/2026-08-10-java-arc-platform.md` in the sibling repository
`agent-framework-java-platform`. Until the scale set is registered and accepting jobs:

- every trusted job stays queued and eventually times out;
- `verify-result` sees `trusted-quality=failure`/`cancelled` and fails, because a skipped or
  timed-out trusted path is never reported as green;
- `main` pushes therefore fail, not silently pass.

The workflow is intentionally not softened to `ubuntu-latest` to make the branch mergeable earlier.
Doing that would delete the only executable evidence that the runner contract below is honoured, and
the runner label allow list in `WorkflowPolicyTest` would then permit a trusted path on a hosted
runner. Merge order is fixed:

1. `agent-framework-java-platform` deploys `arc-java-build` and its smoke workflow passes.
2. A trusted run of this repository's `ci.yml` completes on `arc-java-build`.
3. Only then is this branch merged and `verify-result` made a required status check.

## Application repository contract

Trusted Java verification runs on `runs-on: arc-java-build`. Fork pull requests run the minimum
verification on `runs-on: ubuntu-latest`. No other runner label is permitted.

Jobs on `arc-java-build` must not assume Docker, privileged mode, Azure credentials, Kubernetes API
access, internal service access, persistent workspace storage, or a pre-warmed runner. They may
assume:

- Eclipse Temurin 17, 21, and 25 in the GitHub tool cache under `/opt/hostedtoolcache`;
- `JAVA_HOME_17_X64`, `JAVA_HOME_21_X64`, and `JAVA_HOME_25_X64`;
- outbound HTTPS to GitHub Actions, the GitHub API, Maven Central, the Gradle Plugin Portal, the
  Gradle distribution service, and the approved container registry;
- a non-root `runner` user and `/home/runner/run.sh`.

The repository owns workflow triggers, pinned action SHAs, token permissions, Gradle commands,
caches, artifacts, and timeouts. Gradle itself is owned by the committed wrapper, never by the image.

## Platform contract

The platform repository `agent-framework-java-platform` owns the runner image, the Azure Container
Registry repository, the `arc-java-build` Helm release, namespace `arc-runners-java`, the service
account, Pod Security Admission labels, the Cilium network policy, and log export.

`arc-java-build` is expected to:

- live in namespace `arc-runners-java`;
- be ephemeral and non-privileged with `allowPrivilegeEscalation: false` and all capabilities dropped;
- use a dedicated service account with no application permissions;
- scale from `minRunners: 0` to a finite `maxRunners`;
- reference the runner image by immutable digest;
- never enable Docker-in-Docker or Kubernetes container mode.

The existing general-purpose `aks-runners` scale set is not modified by this repository or by the
`arc-java-build` rollout.

## Tool cache regression

`actions/setup-java` must select the preinstalled toolchains without downloading. The trusted CI jobs
record each `JAVA_HOME_*_X64` value and whether it came from the tool cache in the job summary, so a
regression is visible without failing unrelated work. The hard block lives in the image tests: the
in-image verifier that runs during the registry build, and the manual `runner-smoke.yml` workflow,
which fails when any toolchain is not served from
`/opt/hostedtoolcache/Java_Temurin-Hotspot_jdk/`.

## Trust boundary

Fork pull requests never reach `arc-java-build`. The trusted and fork jobs use mutually exclusive
conditions, and the required `verify-result` job fails unless exactly one complete path succeeded, so
a skipped job can never be reported as green.

Never combine `pull_request_target`, checkout of pull-request head code, and execution of repository
scripts. Docker or Testcontainers work requires a separately reviewed scale set, namespace, runner
group, and network policy; this repository does not create one.

## Executable enforcement

Every clause above that this repository can enforce is a test, not prose. `./gradlew check` runs
`WorkflowPolicyTest` and `WorkflowPolicyBypassProbeTest`, which parse every workflow as YAML and
enforce the runner allow list in all `runs-on` forms, the ban on job-level `uses:`, full commit-SHA
pinning, `read`/`none` permissions, `persist-credentials: false`, pull-request-only cancellation,
mutually exclusive trusted and fork conditions, and the `verify-result` truth table. Conditions are
compared after whitespace and `${{ }}` normalization only, so reformatting a condition is allowed and
weakening one is not.
