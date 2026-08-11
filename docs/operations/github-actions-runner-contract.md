# GitHub Actions Runner Contract

## Runner availability: `arc-java-build` is scoped to a repository

`.github/workflows/ci.yml` schedules `trusted-quality` and `trusted-compatibility` on
`runs-on: arc-java-build`. That label comes from an Actions Runner Controller scale set deployed by
the sibling repository `agent-framework-java-platform`. A scale set is registered against a specific
GitHub owner and repository, so the label does not follow the code.

**When this repository moves to a different owner, the scale set must be re-registered against the
new location before trusted CI can run.** Verify with:

```bash
gh api repos/<owner>/<repo>/actions/runners --jq '.total_count'
```

A count of zero means every trusted job queues until it times out. The observable symptoms are:

- trusted jobs stay `queued` indefinitely rather than failing fast;
- `verify-result` fails once they time out, because a path that is neither wholly successful nor
  wholly skipped is never reported as green;
- `main` pushes therefore fail rather than silently pass.

The workflow is intentionally not softened to `ubuntu-latest` to make a branch mergeable earlier.
Doing that would delete the only executable evidence that the runner contract below is honoured, and
the runner label allow list in `WorkflowPolicyTest` would then permit a trusted path on a hosted
runner.

While the scale set is unavailable, verify locally and record the result in the pull request:

```bash
./gradlew policyCheck quality testJava17 buildLogicTest
./gradlew publishAllPublicationsToBuildDirectoryRepository
```

This covers everything except the Java 21 and 25 compatibility matrix, which needs those toolchains
installed locally or a working runner.

Order of operations when standing up or moving the runner:

1. `agent-framework-java-platform` deploys `arc-java-build` for the target repository and its smoke
   workflow passes.
2. A trusted run of this repository's `ci.yml` completes on `arc-java-build`.
3. Only then is `verify-result` made a required status check.

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
Registry *repository* `gha-runners/agent-framework-java`, the `arc-java-build` Helm release,
namespace `arc-runners-java`, the service account, Pod Security Admission labels, the Cilium network
policy, and log export.

The registry itself is **not** owned here. `acrpensionguard` / `acrpensionguard.azurecr.io` in
resource group `rg-pension-guard` already exists and is shared; the platform repository reuses it,
never creates a registry, and never changes a registry property. The target cluster is
`aks-shared-runners` (kubectl context `evalollama`) in subscription
`95933ae5-0201-4a21-a1fc-8051a7437982`.

`arc-java-build` is expected to:

- live in namespace `arc-runners-java`;
- be ephemeral and non-privileged with `allowPrivilegeEscalation: false` and all capabilities dropped;
- use a dedicated service account with no application permissions;
- scale from `minRunners: 0` to a finite `maxRunners`;
- reference the runner image by immutable digest;
- never enable Docker-in-Docker or Kubernetes container mode.

The pre-existing scale sets `aks-runners`, `aks-runners-flutter`, and `korvid-runners`, all in
namespace `arc-runners`, are not modified by this repository or by the `arc-java-build` rollout. That
namespace is read-only to the platform repository apart from one operator action: `gha-token` is
namespace-scoped, so it is copied — never re-created and never rendered — into `arc-runners-java`.

## Tool cache regression

`actions/setup-java` must select the preinstalled toolchains without downloading. The trusted CI jobs
record each `JAVA_HOME_*_X64` value and whether it came from the tool cache in the job summary, so a
regression is visible without failing unrelated work. The hard block lives in the image tests: the
in-image verifier that runs during the registry build, and the manual `runner-smoke.yml` workflow,
which fails when any toolchain is not served from
`/opt/hostedtoolcache/Java_Temurin-Hotspot_jdk/`.

## Trigger allow list

A workflow in this repository may declare only these events:

- `pull_request`
- `push`
- `workflow_dispatch`

The list is part of the trust boundary, not a convenience. The trusted condition asks a single
question — is this event a pull request from a fork? — so **every event that is not a pull request is
trusted by construction**. A job carrying the verbatim trusted condition under `workflow_run`,
`issue_comment`, `repository_dispatch`, or `schedule` therefore runs on `arc-java-build`, and
`workflow_run` in particular can be started by a workflow run that a fork pull request triggered.
Nothing in the condition catches that; only the allow list does.

Adding an event is a reviewed change to `WorkflowPolicy.ALLOWED_TRIGGERS`, and it must be paired
with a trust decision for that event, not merely with a workflow edit.
`WorkflowPolicyBypassProbeTest.workflowRunTrustedJobIsCaughtOnlyByTheTriggerAllowList` pins the
bypass: it asserts that the condition rules accept such a job and that the allow list rejects it.

## Local composite actions

A step may use a local composite action through a `./` path. That path is not a weaker form of
pinning, it is an unreviewed indirection: the pinning rule accepts any `./` reference on sight, so
without a further rule a local action is free to pull `attacker/action@main`, and a `./` reference
that resolves to nothing is a step whose behaviour no rule in this repository has ever read.

Every action definition under `.github/actions` is therefore scanned recursively, at any depth, in
both `action.yml` and `action.yaml` form, and the action graph reachable from every workflow step is
walked through local composite actions. At every depth:

- a referenced local action must exist, as `action.yml` or `action.yaml` in the directory the
  reference names;
- a local reference must stay inside this repository, so `./../…` is rejected before it is resolved;
- a local action must declare `runs.using: composite`. Any other form — `docker` above all, which
  pulls an image this repository never reviewed, but equally a Node action or a form this policy
  does not know — declares no `steps`, so the walk would resolve it, find no edge, and report it
  clean. Rejecting the form is what keeps the walk fail-closed;
- every external `uses:` a composite action declares must be pinned to a full 40-character commit
  SHA, exactly as in a workflow file;
- a definition that no workflow references yet is held to the same rules, so an unpinned action
  cannot be committed now and wired up in a later change.

Cycles terminate rather than hang the scan. The repository currently ships no local composite
action; the rules are pinned by `WorkflowPolicyBypassProbeTest`, which drives them with synthetic
definitions carrying `attacker/action@main`, a nonexistent `./` path, an escaping `./../` path, a
two-composite-deep nesting, and every non-composite `runs.using` form, and which runs the real
recursive scan and the real resolver against a fixture tree so the discovery, the `action.yml` over
`action.yaml` resolution order, and the root containment check are executed rather than assumed.

## Trust boundary

Fork pull requests never reach `arc-java-build`. The trusted and fork jobs use mutually exclusive
conditions, only allow-listed events can start a workflow, and the required `verify-result` job
fails unless exactly one complete path succeeded, so a skipped job can never be reported as green.

`verify-result` reads the whole `needs` context as `NEEDS_JSON` (`${{ toJSON(needs) }}`) and
evaluates it against the declarative `VERIFICATION_PATHS` map, `name=job[,job]` per line. It fails
when any job reports anything other than `success` or `skipped`, when a path is neither wholly
successful nor wholly skipped, when a needed job belongs to no path, when a path names a job that is
not in `needs`, and unless exactly one path completed. A verification job added to `needs` but not
classified into a path therefore fails the gate instead of passing unexamined: the gate can never go
green over a job it does not know about.

These rules bind **every workflow a pull request can start**, not the first one that was written.
Every such workflow fans in to a `verify-result` that is a merge-blocking check, so a second
pull-request workflow whose gate classifies nothing — or whose script is replaced by `exit 0` — is a
required check that verifies nothing. The gate tests are therefore parameterized over every
pull-request workflow, and each workflow's truth table is derived from its own `needs` list and its
own `VERIFICATION_PATHS` map rather than from a table hand-written for `ci.yml`.

Never combine `pull_request_target`, checkout of pull-request head code, and execution of repository
scripts. Docker or Testcontainers work requires a separately reviewed scale set, namespace, runner
group, and network policy; this repository does not create one.

## Executable enforcement

Every clause above that this repository can enforce is a test, not prose. `./gradlew check` runs
`WorkflowPolicyTest` and `WorkflowPolicyBypassProbeTest`, which parse every workflow as YAML and
enforce the trigger allow list, the runner allow list in all `runs-on` forms, the ban on job-level
`uses:`, full commit-SHA pinning, the recursive local composite action rules above, `read`/`none`
permissions, `persist-credentials: false`, pull-request-only cancellation, mutually exclusive
trusted and fork conditions, the requirement that the `verify-result` job of every pull-request
workflow consume the whole `needs` context and classify every needed job into exactly one
verification path, and each of those workflows' derived `verify-result` truth table, which is
executed as a real shell process against a synthesized `needs` context rather than pattern matched.
Conditions are compared after whitespace and `${{ }}` normalization only, so reformatting a
condition is allowed and weakening one is not.

## Readiness

`arc-java-build` is verified by the platform repository and by the application-side smoke workflow:

```bash
gh workflow run runner-smoke.yml
```

The smoke workflow fails when the runner runs as root, when any `JAVA_HOME_*_X64` value is not served
from `/opt/hostedtoolcache/Java_Temurin-Hotspot_jdk/`, when `docker` is present, or when the Java 17,
21, and 25 compatibility tasks do not pass.
