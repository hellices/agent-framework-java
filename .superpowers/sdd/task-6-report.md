# Task 6 Report: Document and Validate the Local ARC Runner Contract

## Commit SHA

`90761d9` — `docs: define ARC runner contract`

## Files Created / Modified

| File | Action |
|---|---|
| `scripts/check-arc-runner.sh` | Created (executable) |
| `docs/operations/github-actions-runner-contract.md` | Created |
| `README.md` | Modified — added runner contract link under 기여와 하네스 |

## Commands and Results

### 1. Repository policy

```
$ sh build/check-repository-policy.sh
Repository policy check passed.
```

### 2. Spotless apply

```
$ ./mvnw -B spotless:apply
BUILD SUCCESS
```

### 3. Maven clean verify

```
$ ./mvnw -B clean verify
Tests run: 7, Failures: 0, Errors: 0, Skipped: 0  (ArtifactContractTest)
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0  (WorkflowPolicyTest)
BUILD SUCCESS  (total time: ~5 s)
```

### 4. ARC preflight

```
$ sh scripts/check-arc-runner.sh
ARC runner contract passed: context=evalollama namespace=arc-runners scaleSet=aks-runners max=5 serviceAccount=aks-runners-gha-rs-no-permission
```

All five contract assertions passed:
- `minRunners = 0` ✓
- `maxRunners = 5` (positive) ✓
- `serviceAccountName = aks-runners-gha-rs-no-permission` (not `default`) ✓
- No privileged container ✓
- CRD `autoscalingrunnersets.actions.github.com` present ✓

## One-line Test Summary

`10 tests passed (7 artifact-contract + 3 workflow-policy), 0 failures, 0 errors — BUILD SUCCESS`

## Self-Review

- Script is strictly read-only: only `kubectl get` / `kubectl config current-context` calls.
- No cluster mutation, no tool installation, no infrastructure created.
- Overridable via `ARC_RUNNER_NAMESPACE` and `ARC_RUNNER_SCALE_SET` env vars for future approved scale sets.
- Trust boundary section in the contract doc explicitly prohibits `pull_request_target` + head-checkout + privileged execution.

## Concerns

None. All contract checks pass against the observed cluster state. The script will fail fast if the cluster context changes or the scale set configuration drifts from the documented contract.
