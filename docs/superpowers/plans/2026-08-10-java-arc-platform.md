# Java ARC Platform Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build, verify, and deploy a dedicated non-privileged Java runner image and the `arc-java-build` Actions Runner Controller scale set in a separate platform repository, without modifying any existing scale set and without ever printing secret content.

**Architecture:** All platform assets live in a standalone sibling Git repository at `/Users/hwang-inhwan/workspace/agent-framework-java-platform`, so they can move to a private infrastructure repository later. The runner image extends the official digest-pinned GitHub Actions runner image with Eclipse Temurin 17, 21, and 25 laid out as a GitHub tool cache; Gradle itself is never baked in because the application repository owns it through its wrapper. Because no local Docker daemon exists, every image build and every in-image verification runs server-side in Azure Container Registry through `az acr build` and `az acr run`. Kubernetes exposure is minimized with a dedicated namespace, a dedicated service account, Pod Security Admission labels, and a Cilium default-deny plus FQDN-allow egress policy. Deployment is gated by an explicit GitHub authorization preflight that blocks rather than falling back, and every mutating script is proven namespace-, release-, or registry-scoped by an executable safety check.

**Tech Stack:** Git, POSIX shell, Python 3, Docker BuildKit syntax (built by ACR, not locally), Azure CLI, Azure Container Registry (Basic), Azure Kubernetes Service 1.35, Kubernetes Pod Security Admission, Cilium `CiliumNetworkPolicy`, Helm, Actions Runner Controller `gha-runner-scale-set` chart 0.14.2, GitHub CLI, GitHub Actions.

## Global Constraints

- The platform repository root is `/Users/hwang-inhwan/workspace/agent-framework-java-platform`. It is an independent Git repository and is never nested inside the application repository.
- Every path in this plan that is not explicitly marked "application repository" is relative to the platform repository root.
- Observed Azure environment, used verbatim:
  - subscription `f752aff6-b20c-4973-b32b-0a60ba2c6764`
  - resource group `rg-korvid-contract-test`
  - AKS cluster `aks-korvid-contract-test`
  - location `koreacentral`
  - Kubernetes `1.35`
  - Actions Runner Controller chart and controller version `0.14.2`
  - Cilium is the active CNI daemonset, so network policy uses `cilium.io/v2` `CiliumNetworkPolicy`
- No Azure Container Registry exists yet. The registry to create is `acrafjavaf752aff6`, SKU `Basic`, admin user disabled, in `rg-korvid-contract-test` / `koreacentral`.
- The image repository is `gha-runners/agent-framework-java`. Tags are convenience only; Helm always references an immutable `@sha256:` digest.
- There is no local Docker daemon. Every image build uses `az acr build` and every in-image test uses `az acr run`. No step may invoke `docker`.
- Base image is exactly `ghcr.io/actions/actions-runner:2.336.0@sha256:0cfdcc701ce933c6d243c6b0b2da767366dc9f2e99961d4c3754b0b78084cdda`.
- Eclipse Temurin archives and checksums, used verbatim:
  - `17.0.20+8` — `https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20%2B8/OpenJDK17U-jdk_x64_linux_hotspot_17.0.20_8.tar.gz` — `be7668bc030d578b83d6d5ef9221d6d6729bbbca8cf94a7d52e16ac68b5a5a35`
  - `21.0.12+8` — `https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12%2B8/OpenJDK21U-jdk_x64_linux_hotspot_21.0.12_8.tar.gz` — `e4446ff06a276155697597cc0f1b15da004ff083f4964a35271ecee567177370`
  - `25.0.4+7` — `https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.4%2B7/OpenJDK25U-jdk_x64_linux_hotspot_25.0.4_7.tar.gz` — `e58fcdcd637b25c03ca84cbbcefc70d11efb8f4b4cbd05decc9f661769d77f94`
- Tool cache layout is exactly `/opt/hostedtoolcache/Java_Temurin-Hotspot_jdk/<normalized-version>/x64` with the sibling marker file `/opt/hostedtoolcache/Java_Temurin-Hotspot_jdk/<normalized-version>/x64.complete`. Normalized versions are `17.0.20-8`, `21.0.12-8`, and `25.0.4-7`.
- The new scale set is `arc-java-build` in namespace `arc-runners-java` with `minRunners: 0` and `maxRunners: 5`. It is non-privileged, drops all capabilities, sets `allowPrivilegeEscalation: false`, uses a dedicated service account, and never enables `containerMode` (no Docker-in-Docker, no Kubernetes mode).
- The existing ARC installation targets `https://github.com/open-play-ground/grown-up` with the pre-defined secret reference `gha-token`. The new scale set targets `https://github.com/open-play-ground/agent-framework-java` and reuses the pre-defined secret reference name `gha-token` in its own namespace. No secret value is ever created, read, decoded, logged, or committed by this plan.
- Existing scale sets must be byte-for-byte unchanged. Every deployment and rollback compares a committed baseline snapshot before and after and fails on any difference.
- GitHub repository and credential authorization is an explicit preflight that blocks deployment. There is no fallback to a different repository, a different secret, or an unauthenticated path.
- Read-only scripts contain no mutating `kubectl`, `helm`, or `az` verb. Cluster-mutating scripts scope every mutation to namespace `arc-runners-java` and Helm release `arc-java-build`. Azure-mutating scripts scope every mutation to resource group `rg-korvid-contract-test`, registry `acrafjavaf752aff6`, or the registry resource id. `scripts/check-script-safety.sh` proves this and is the first gate in every task.
- Application-repository changes are permitted only in Task 9, only after the scale set is verified healthy, and only as an additive smoke workflow.
- Every commit created by an agent includes:

```text
Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
```

---

## File Map

### Platform repository governance

- `.gitignore`: excludes generated build output and any credential-shaped file.
- `README.md`: platform ownership, bring-up order, and rollback entry points.
- `AGENTS.md`: platform-specific agent rules, including the secret-handling prohibition.
- `scripts/lib/common.sh`: the single source of truth for environment identifiers and shared helpers.
- `scripts/check-script-safety.sh`: executable proof that each script stays inside its declared blast radius.

### Runner image

- `runner-images/java/Dockerfile`: digest-pinned base plus three checksum-verified Temurin toolchains.
- `runner-images/java/install-temurin.sh`: checksum-verified tool cache installer used during the build.
- `runner-images/java/verify-image.sh`: in-image contract verifier, also shipped to `/usr/local/bin`.
- `runner-images/java/toolcache-manifest.json`: machine-readable toolchain contract.
- `runner-images/java/check-manifest.sh`: local consistency gate between the manifest and the Dockerfile.
- `runner-images/java/README.md`: image contents, exclusions, and rebuild instructions.

### Azure and image lifecycle scripts

- `scripts/create-acr.sh`: idempotent registry creation and AcrPull grant to the AKS kubelet identity.
- `scripts/build-java-runner.sh`: `az acr build` with a reproducible date-and-revision tag.
- `scripts/verify-java-runner-image.sh`: resolves the digest and runs the in-image verifier through `az acr run`.
- `scripts/update-image-digest.sh`: writes the verified digest into the Helm values file.

### Kubernetes and Helm assets

- `arc/arc-java-build/namespace.yaml`: namespace, Pod Security Admission labels, and the dedicated service account.
- `arc/arc-java-build/network-policy.yaml`: namespace default-deny plus the FQDN egress allow-list.
- `arc/arc-java-build/values.yaml`: the complete `gha-runner-scale-set` values for `arc-java-build`.
- `arc/arc-java-build/README.md`: scale-set contract, blast radius, and recovery steps.
- `arc/arc-java-build/baseline/existing-runner-sets.json`: committed snapshot of every pre-existing scale set.

### Deployment scripts

- `scripts/snapshot-existing-runners.sh`: read-only baseline capture.
- `scripts/check-helm-template.sh`: server dry-run and rendered-manifest assertions.
- `scripts/preflight-github-authorization.sh`: blocking repository and credential authorization gate.
- `scripts/deploy-arc-java-build.sh`: namespace, policy, and Helm release rollout with baseline diffing.
- `scripts/verify-arc-java-build.sh`: read-only post-deployment contract verification.
- `scripts/rollback-arc-java-build.sh`: release-scoped uninstall with baseline diffing.

### Application repository (Task 9 only)

- `.github/workflows/runner-smoke.yml`: manual tool-cache smoke workflow on `arc-java-build`.
- `docs/operations/github-actions-runner-contract.md`: readiness note appended after the smoke run.

---

### Task 1: Bootstrap the platform repository and the script safety gate

**Files:**
- Create: `/Users/hwang-inhwan/workspace/agent-framework-java-platform/.gitignore`
- Create: `/Users/hwang-inhwan/workspace/agent-framework-java-platform/README.md`
- Create: `/Users/hwang-inhwan/workspace/agent-framework-java-platform/AGENTS.md`
- Create: `scripts/check-script-safety.sh`
- Create: `scripts/lib/common.sh`
- Create: `scripts/snapshot-existing-runners.sh`

**Interfaces:**
- Consumes: the observed Azure and Kubernetes environment listed in Global Constraints.
- Produces: `scripts/lib/common.sh` exporting `AZURE_SUBSCRIPTION_ID`, `AZURE_RESOURCE_GROUP`, `AZURE_LOCATION`, `AKS_CLUSTER_NAME`, `KUBE_CONTEXT`, `ACR_NAME`, `IMAGE_REPOSITORY`, `ARC_NAMESPACE`, `ARC_RELEASE`, `ARC_CHART`, `ARC_CHART_VERSION`, `GITHUB_CONFIG_URL`, `GITHUB_CONFIG_SECRET`, `EGRESS_PROFILE_LABEL`, and the helpers `fail`, `info`, `require_command`, `require_azure_subscription`, `require_kube_context`, `platform_repository_root`; `arc/arc-java-build/baseline/existing-runner-sets.json`.

- [ ] **Step 1: Create the standalone platform repository**

Run:

```bash
mkdir -p /Users/hwang-inhwan/workspace/agent-framework-java-platform
cd /Users/hwang-inhwan/workspace/agent-framework-java-platform
git init -b main
git status --short
```

Expected: `Initialized empty Git repository`, then no output from `git status --short`.

All remaining commands in this plan run from `/Users/hwang-inhwan/workspace/agent-framework-java-platform` unless a step says otherwise.

- [ ] **Step 2: Create the repository hygiene and ownership files**

Create `.gitignore`:

```text
build/
*.log

*.pem
*.key
*.pfx
kubeconfig
.env
*.tfstate
*.tfstate.backup
```

Create `README.md`:

````markdown
# Agent Framework for Java Platform

This repository owns the Java runner image and the `arc-java-build` Actions Runner Controller scale
set for `https://github.com/open-play-ground/agent-framework-java`. It is intentionally separate from
the application repository so it can move to a private infrastructure repository later.

## Blast radius

- Azure resource group: `rg-korvid-contract-test`
- Azure Container Registry: `acrafjavaf752aff6`
- AKS cluster: `aks-korvid-contract-test`
- Kubernetes namespace: `arc-runners-java`
- Helm release: `arc-java-build`

Nothing in this repository modifies an existing runner scale set. Every deployment compares the
committed baseline in `arc/arc-java-build/baseline/existing-runner-sets.json` before and after.

## Bring-up order

```bash
sh scripts/check-script-safety.sh
sh scripts/snapshot-existing-runners.sh
sh runner-images/java/check-manifest.sh
sh scripts/create-acr.sh
sh scripts/build-java-runner.sh
sh scripts/verify-java-runner-image.sh
sh scripts/update-image-digest.sh
sh scripts/check-helm-template.sh
sh scripts/preflight-github-authorization.sh
sh scripts/deploy-arc-java-build.sh
sh scripts/verify-arc-java-build.sh
```

## Rollback

```bash
sh scripts/rollback-arc-java-build.sh
sh scripts/verify-arc-java-build.sh
```

## Secrets

No secret value is created, read, decoded, printed, or committed here. The `gha-token` secret is
provisioned out of band by a cluster operator and only referenced by name.
````

Create `AGENTS.md`:

````markdown
# Agent Framework for Java Platform Instructions

## Repository purpose

This repository owns the Java runner image and the `arc-java-build` ARC scale set. The application
repository owns Gradle, workflows, and product code. Never move ownership across that line.

## Blast radius

Every mutation must stay inside resource group `rg-korvid-contract-test`, registry
`acrafjavaf752aff6`, namespace `arc-runners-java`, or Helm release `arc-java-build`.

Run the executable proof before and after any script change:

```bash
sh scripts/check-script-safety.sh
```

## Sensitive data

- Never create, read, decode, print, log, or commit a secret value.
- Read Kubernetes secrets only through a key-name projection, never `-o yaml` or `-o json`.
- Never use `base64 --decode`, `base64 -d`, or `--from-literal` in a committed script.
- Never commit a kubeconfig, private key, GitHub App key, or registry credential.

## Verification contract

- No local Docker daemon exists. Build with `az acr build` and test images with `az acr run`.
- Image builds verify the base image digest and every JDK archive checksum.
- Helm values must reference an immutable `@sha256:` image digest.
- Deployment is blocked unless `scripts/preflight-github-authorization.sh` succeeded.
- Deployment and rollback fail if any pre-existing scale set changed.

## Prohibited changes

- Do not modify, upgrade, or delete an existing runner scale set or its namespace.
- Do not enable `containerMode`, Docker-in-Docker, privileged containers, or host mounts.
- Do not grant the runner service account any Kubernetes RBAC permission.
- Do not bake Gradle, dependency caches, cloud CLIs, or credentials into the runner image.
- Do not replace a blocked preflight with a fallback path.
````

- [ ] **Step 3: Write the failing script safety gate**

Create `scripts/check-script-safety.sh`:

```sh
#!/bin/sh
set -eu

repository_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
work_dir="$repository_root/build/script-safety"
failures=0

library_scripts="scripts/lib/common.sh"
read_only_scripts="scripts/snapshot-existing-runners.sh scripts/verify-arc-java-build.sh"
dry_run_scripts="scripts/check-helm-template.sh"
cluster_scoped_scripts="scripts/preflight-github-authorization.sh scripts/deploy-arc-java-build.sh scripts/rollback-arc-java-build.sh"
azure_scoped_scripts="scripts/create-acr.sh scripts/build-java-runner.sh scripts/verify-java-runner-image.sh"
plain_scripts="scripts/check-script-safety.sh scripts/update-image-digest.sh runner-images/java/check-manifest.sh runner-images/java/install-temurin.sh runner-images/java/verify-image.sh"

all_scripts="$library_scripts $read_only_scripts $dry_run_scripts $cluster_scoped_scripts $azure_scoped_scripts $plain_scripts"

mutation_pattern='kubectl[[:space:]]+(apply|create|delete|patch|replace|edit|scale|annotate|label|rollout|cordon|drain|exec)|helm[[:space:]]+(install|upgrade|uninstall|rollback)|az[[:space:]]+[a-z-]+[[:space:]]+[a-z-]*[[:space:]]*(create|update|delete|set|add|remove|build|run|purge|import)|(^|[^A-Za-z0-9_-])docker[[:space:]]'
inline_secret_pattern='base64[[:space:]]+(-d|--decode)|--from-literal'
secret_command_pattern='kubectl[^|]*secret'

report() {
  printf '%s\n' "$1" >&2
  failures=$((failures + 1))
}

normalize() {
  awk '
    {
      line = $0
      sub(/^[[:space:]]+/, "", line)
      if (line ~ /\\$/) {
        sub(/\\$/, " ", line)
        buffer = buffer line
        next
      }
      print buffer line
      buffer = ""
    }
    END { if (buffer != "") print buffer }
  ' "$1" | grep -v '^#' || true
}

rm -rf "$work_dir"
mkdir -p "$work_dir"

for script in $all_scripts; do
  if [ ! -f "$repository_root/$script" ]; then
    report "missing required script: $script"
    continue
  fi
  if [ "$(head -n 1 "$repository_root/$script")" != "#!/bin/sh" ]; then
    report "$script must start with #!/bin/sh"
  fi
  if ! grep -q '^set -eu$' "$repository_root/$script"; then
    report "$script must declare 'set -eu'"
  fi
  case "$script" in
    scripts/check-script-safety.sh)
      : # this file necessarily contains the forbidden literals as detection patterns
      ;;
    *)
      if grep -qE "$inline_secret_pattern" "$repository_root/$script"; then
        report "$script must not decode or inline secret material"
      fi
      ;;
  esac
  normalize "$repository_root/$script" > "$work_dir/$(basename "$script").normalized"
done

if [ "$failures" -ne 0 ]; then
  printf 'Script safety check failed with %s problem(s).\n' "$failures" >&2
  exit 1
fi

for script in $all_scripts; do
  normalized="$work_dir/$(basename "$script").normalized"
  grep -E "$secret_command_pattern" "$normalized" > "$work_dir/secret-lines.txt" || true
  while IFS= read -r command_line; do
    case "$command_line" in
      *"-o json"*|*"-o yaml"*|*jsonpath*|*--from-literal*)
        report "$script must not project secret values: $command_line"
        ;;
      *) ;;
    esac
  done < "$work_dir/secret-lines.txt"
done

for script in $read_only_scripts; do
  normalized="$work_dir/$(basename "$script").normalized"
  if grep -qE "$mutation_pattern" "$normalized"; then
    grep -nE "$mutation_pattern" "$normalized" >&2
    report "$script is declared read-only but contains a mutating command"
  fi
done

for script in $dry_run_scripts; do
  normalized="$work_dir/$(basename "$script").normalized"
  grep -E 'kubectl[[:space:]]+apply' "$normalized" > "$work_dir/apply-lines.txt" || true
  while IFS= read -r command_line; do
    case "$command_line" in
      *--dry-run=server*) ;;
      *) report "$script must only run 'kubectl apply --dry-run=server': $command_line" ;;
    esac
  done < "$work_dir/apply-lines.txt"
  if grep -qE 'helm[[:space:]]+(install|upgrade|uninstall|rollback)' "$normalized"; then
    report "$script must not install, upgrade, uninstall, or roll back a Helm release"
  fi
done

for script in $cluster_scoped_scripts; do
  normalized="$work_dir/$(basename "$script").normalized"
  grep -E 'kubectl[[:space:]]+(apply|create|delete|patch|replace|scale|annotate|label|rollout)' \
    "$normalized" > "$work_dir/kubectl-lines.txt" || true
  while IFS= read -r command_line; do
    case "$command_line" in
      *'--namespace "$ARC_NAMESPACE"'*) ;;
      *) report "$script has an unscoped kubectl mutation: $command_line" ;;
    esac
  done < "$work_dir/kubectl-lines.txt"

  grep -E 'helm[[:space:]]+(install|upgrade|uninstall|rollback)' \
    "$normalized" > "$work_dir/helm-lines.txt" || true
  while IFS= read -r command_line; do
    case "$command_line" in
      *'--namespace "$ARC_NAMESPACE"'*)
        case "$command_line" in
          *'"$ARC_RELEASE"'*) ;;
          *) report "$script has a Helm command outside release arc-java-build: $command_line" ;;
        esac
        ;;
      *) report "$script has an unscoped Helm command: $command_line" ;;
    esac
  done < "$work_dir/helm-lines.txt"
done

for script in $azure_scoped_scripts; do
  normalized="$work_dir/$(basename "$script").normalized"
  grep -E 'az[[:space:]]+[a-z-]+[[:space:]]+[a-z-]*[[:space:]]*(create|update|delete|set|add|remove|build|run|purge|import)' \
    "$normalized" > "$work_dir/az-lines.txt" || true
  while IFS= read -r command_line; do
    case "$command_line" in
      *'--resource-group "$AZURE_RESOURCE_GROUP"'*) ;;
      *'--registry "$ACR_NAME"'*) ;;
      *'--scope "$registry_id"'*) ;;
      *) report "$script has an unscoped Azure mutation: $command_line" ;;
    esac
  done < "$work_dir/az-lines.txt"
done

if [ "$failures" -ne 0 ]; then
  printf 'Script safety check failed with %s problem(s).\n' "$failures" >&2
  exit 1
fi

printf 'Script safety check passed for %s scripts.\n' "$(printf '%s\n' $all_scripts | wc -l | tr -d ' ')"
```

Make it executable:

```bash
chmod +x scripts/check-script-safety.sh
```

- [ ] **Step 4: Run the safety gate and verify it fails**

Run:

```bash
sh scripts/check-script-safety.sh
```

Expected: exit 1 listing `missing required script:` for every script in the declared inventory except `scripts/check-script-safety.sh`.

- [ ] **Step 5: Create the shared environment library**

Create `scripts/lib/common.sh`:

```sh
#!/bin/sh
set -eu

AZURE_SUBSCRIPTION_ID=f752aff6-b20c-4973-b32b-0a60ba2c6764
AZURE_RESOURCE_GROUP=rg-korvid-contract-test
AZURE_LOCATION=koreacentral
AKS_CLUSTER_NAME=aks-korvid-contract-test
KUBE_CONTEXT=${ARC_KUBE_CONTEXT:-aks-korvid-contract-test}
ACR_NAME=acrafjavaf752aff6
ACR_LOGIN_SERVER=acrafjavaf752aff6.azurecr.io
IMAGE_REPOSITORY=gha-runners/agent-framework-java
ARC_NAMESPACE=arc-runners-java
ARC_RELEASE=arc-java-build
ARC_CHART=oci://ghcr.io/actions/actions-runner-controller-charts/gha-runner-scale-set
ARC_CHART_VERSION=0.14.2
GITHUB_CONFIG_URL=https://github.com/open-play-ground/agent-framework-java
GITHUB_CONFIG_SECRET=gha-token
GITHUB_TARGET_REPOSITORY=open-play-ground/agent-framework-java
EGRESS_PROFILE_LABEL=agentframework.io/egress-profile=java-build

export AZURE_SUBSCRIPTION_ID AZURE_RESOURCE_GROUP AZURE_LOCATION AKS_CLUSTER_NAME KUBE_CONTEXT
export ACR_NAME ACR_LOGIN_SERVER IMAGE_REPOSITORY ARC_NAMESPACE ARC_RELEASE ARC_CHART
export ARC_CHART_VERSION GITHUB_CONFIG_URL GITHUB_CONFIG_SECRET GITHUB_TARGET_REPOSITORY
export EGRESS_PROFILE_LABEL

platform_repository_root() {
  CDPATH= cd -- "$(dirname -- "$0")/.." && pwd
}

info() {
  printf 'INFO %s\n' "$1"
}

fail() {
  printf 'BLOCKED %s\n' "$1" >&2
  exit 1
}

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    fail "required command not found: $1"
  fi
}

require_azure_subscription() {
  require_command az
  actual_subscription=$(az account show --query id -o tsv)
  if [ "$actual_subscription" != "$AZURE_SUBSCRIPTION_ID" ]; then
    fail "expected subscription $AZURE_SUBSCRIPTION_ID but the CLI is signed in to $actual_subscription"
  fi
  info "azure subscription $AZURE_SUBSCRIPTION_ID"
}

require_kube_context() {
  require_command kubectl
  actual_context=$(kubectl config current-context)
  if [ "$actual_context" != "$KUBE_CONTEXT" ]; then
    fail "expected kubectl context $KUBE_CONTEXT but found $actual_context"
  fi
  info "kubectl context $KUBE_CONTEXT"
}
```

- [ ] **Step 6: Create the read-only baseline snapshot script**

Create `scripts/snapshot-existing-runners.sh`:

```sh
#!/bin/sh
set -eu

. "$(dirname -- "$0")/lib/common.sh"

repository_root=$(platform_repository_root)
baseline_dir="$repository_root/arc/arc-java-build/baseline"
baseline_file="$baseline_dir/existing-runner-sets.json"

require_kube_context
require_command python3

mkdir -p "$baseline_dir"

kubectl get autoscalingrunnersets.actions.github.com --all-namespaces -o json |
  python3 -c '
import json
import sys

document = json.load(sys.stdin)
records = []
for item in document.get("items", []):
    metadata = item.get("metadata", {})
    if metadata.get("name") == "arc-java-build":
        continue
    records.append(
        {
            "namespace": metadata.get("namespace"),
            "name": metadata.get("name"),
            "spec": item.get("spec", {}),
        }
    )
records.sort(key=lambda record: (record["namespace"] or "", record["name"] or ""))
print(json.dumps(records, indent=2, sort_keys=True))
' > "$baseline_file"

info "captured $(python3 -c 'import json,sys; print(len(json.load(open(sys.argv[1]))))' "$baseline_file") existing scale set(s)"
info "baseline written to arc/arc-java-build/baseline/existing-runner-sets.json"
```

- [ ] **Step 7: Run the safety gate and verify the read-only classification**

Run:

```bash
chmod +x scripts/lib/common.sh scripts/snapshot-existing-runners.sh
sh scripts/check-script-safety.sh
```

Expected: exit 1, and the remaining `missing required script:` lines no longer include `scripts/lib/common.sh` or `scripts/snapshot-existing-runners.sh`. The safety gate stays red until Task 7 creates the last script; that is the intended sequencing.

- [ ] **Step 8: Capture the existing scale-set baseline**

Run:

```bash
kubectl config current-context
sh scripts/snapshot-existing-runners.sh
python3 -c "import json;print([(r['namespace'], r['name']) for r in json.load(open('arc/arc-java-build/baseline/existing-runner-sets.json'))])"
```

Expected: the context prints `aks-korvid-contract-test`; the snapshot script reports at least one existing scale set; the final command prints the namespace and name of every pre-existing scale set, and `arc-java-build` is absent.

- [ ] **Step 9: Commit the platform bootstrap**

Run:

```bash
git add .gitignore README.md AGENTS.md scripts arc
git commit -m "chore: bootstrap the Java ARC platform repository

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 2: Author the Java runner image and its in-image verifier

**Files:**
- Create: `runner-images/java/toolcache-manifest.json`
- Create: `runner-images/java/check-manifest.sh`
- Create: `runner-images/java/install-temurin.sh`
- Create: `runner-images/java/verify-image.sh`
- Create: `runner-images/java/Dockerfile`
- Create: `runner-images/java/README.md`

**Interfaces:**
- Consumes: the base image digest and Temurin archive URLs/checksums from Global Constraints.
- Produces: a build context at `runner-images/java` whose `Dockerfile` installs the tool cache and runs `/usr/local/bin/verify-java-runner-image.sh` both as root and as `runner`; `runner-images/java/check-manifest.sh` proving the Dockerfile and the manifest agree.

- [ ] **Step 1: Create the toolchain manifest**

Create `runner-images/java/toolcache-manifest.json`:

```json
{
  "baseImage": "ghcr.io/actions/actions-runner:2.336.0@sha256:0cfdcc701ce933c6d243c6b0b2da767366dc9f2e99961d4c3754b0b78084cdda",
  "toolCacheRoot": "/opt/hostedtoolcache/Java_Temurin-Hotspot_jdk",
  "architecture": "x64",
  "toolchains": [
    {
      "majorVersion": 17,
      "release": "17.0.20+8",
      "normalizedVersion": "17.0.20-8",
      "javaHomeVariable": "JAVA_HOME_17_X64",
      "url": "https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20%2B8/OpenJDK17U-jdk_x64_linux_hotspot_17.0.20_8.tar.gz",
      "sha256": "be7668bc030d578b83d6d5ef9221d6d6729bbbca8cf94a7d52e16ac68b5a5a35"
    },
    {
      "majorVersion": 21,
      "release": "21.0.12+8",
      "normalizedVersion": "21.0.12-8",
      "javaHomeVariable": "JAVA_HOME_21_X64",
      "url": "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12%2B8/OpenJDK21U-jdk_x64_linux_hotspot_21.0.12_8.tar.gz",
      "sha256": "e4446ff06a276155697597cc0f1b15da004ff083f4964a35271ecee567177370"
    },
    {
      "majorVersion": 25,
      "release": "25.0.4+7",
      "normalizedVersion": "25.0.4-7",
      "javaHomeVariable": "JAVA_HOME_25_X64",
      "url": "https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.4%2B7/OpenJDK25U-jdk_x64_linux_hotspot_25.0.4_7.tar.gz",
      "sha256": "e58fcdcd637b25c03ca84cbbcefc70d11efb8f4b4cbd05decc9f661769d77f94"
    }
  ]
}
```

- [ ] **Step 2: Write the failing manifest consistency gate**

Create `runner-images/java/check-manifest.sh`:

```sh
#!/bin/sh
set -eu

image_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

if ! command -v python3 >/dev/null 2>&1; then
  printf 'BLOCKED required command not found: python3\n' >&2
  exit 1
fi

python3 - "$image_dir/toolcache-manifest.json" "$image_dir/Dockerfile" <<'PY'
import json
import pathlib
import sys

manifest_path, dockerfile_path = sys.argv[1], sys.argv[2]
manifest = json.loads(pathlib.Path(manifest_path).read_text())
dockerfile = pathlib.Path(dockerfile_path).read_text()

problems = []

expected_base = "FROM " + manifest["baseImage"]
if expected_base not in dockerfile:
    problems.append("Dockerfile must pin the manifest base image: " + expected_base)

root = manifest["toolCacheRoot"]
if "ARG TOOLCACHE_ROOT=" + root not in dockerfile:
    problems.append("Dockerfile must declare ARG TOOLCACHE_ROOT=" + root)

for toolchain in manifest["toolchains"]:
    major = toolchain["majorVersion"]
    for suffix, key in (
        ("VERSION", "normalizedVersion"),
        ("URL", "url"),
        ("SHA256", "sha256"),
    ):
        expected = "ARG JDK{}_{}={}".format(major, suffix, toolchain[key])
        if expected not in dockerfile:
            problems.append("Dockerfile must declare " + expected)
    home = "{}/{}/x64".format(root, toolchain["normalizedVersion"])
    expected_env = "{}={}".format(toolchain["javaHomeVariable"], home)
    if expected_env not in dockerfile:
        problems.append("Dockerfile must export " + expected_env)

if problems:
    for problem in problems:
        print("FAIL " + problem, file=sys.stderr)
    raise SystemExit(1)

print("PASS Dockerfile matches toolcache-manifest.json for {} toolchains".format(len(manifest["toolchains"])))
PY
```

- [ ] **Step 3: Run the manifest gate and verify it fails**

Run:

```bash
chmod +x runner-images/java/check-manifest.sh
sh runner-images/java/check-manifest.sh
```

Expected: FAIL with a Python `FileNotFoundError` for `runner-images/java/Dockerfile`.

- [ ] **Step 4: Create the checksum-verified tool cache installer**

Create `runner-images/java/install-temurin.sh`:

```sh
#!/bin/sh
set -eu

normalized_version=$1
archive_url=$2
archive_sha256=$3

toolcache_root=/opt/hostedtoolcache/Java_Temurin-Hotspot_jdk
target_dir="$toolcache_root/$normalized_version/x64"
download_dir=/var/cache/temurin-download
archive="$download_dir/temurin-$normalized_version.tar.gz"

mkdir -p "$target_dir" "$download_dir"
curl --fail --silent --show-error --location --output "$archive" "$archive_url"
printf '%s  %s\n' "$archive_sha256" "$archive" | sha256sum -c -
tar -xzf "$archive" -C "$target_dir" --strip-components=1
rm -rf "$download_dir"
touch "$toolcache_root/$normalized_version/x64.complete"

"$target_dir/bin/java" -version
"$target_dir/bin/javac" -version
```

- [ ] **Step 5: Create the in-image contract verifier**

Create `runner-images/java/verify-image.sh`:

```sh
#!/bin/sh
set -eu

skip_user_check=0
if [ "${1:-}" = "--skip-user-check" ]; then
  skip_user_check=1
fi

toolcache_root=/opt/hostedtoolcache/Java_Temurin-Hotspot_jdk
failures=0

pass() {
  printf 'PASS %s\n' "$1"
}

report() {
  printf 'FAIL %s\n' "$1" >&2
  failures=$((failures + 1))
}

check_toolchain() {
  normalized_version=$1
  expected_release=$2
  home="$toolcache_root/$normalized_version/x64"

  if [ ! -x "$home/bin/java" ]; then
    report "missing executable $home/bin/java"
    return
  fi
  if [ ! -x "$home/bin/javac" ]; then
    report "missing executable $home/bin/javac"
    return
  fi
  if [ ! -f "$toolcache_root/$normalized_version/x64.complete" ]; then
    report "missing tool cache marker $toolcache_root/$normalized_version/x64.complete"
    return
  fi

  reported=$("$home/bin/java" -version 2>&1 | head -n 1)
  case "$reported" in
    *"\"$expected_release\""*) pass "toolchain $normalized_version reports $reported" ;;
    *) report "toolchain $normalized_version reported unexpected version: $reported" ;;
  esac
}

check_environment() {
  variable=$1
  expected=$2
  actual=$(printenv "$variable" 2>/dev/null || printf '')
  if [ "$actual" = "$expected" ]; then
    pass "$variable=$actual"
  else
    report "$variable must be $expected but was '$actual'"
  fi
}

check_toolchain 17.0.20-8 17.0.20
check_toolchain 21.0.12-8 21.0.12
check_toolchain 25.0.4-7 25.0.4

check_environment JAVA_HOME_17_X64 "$toolcache_root/17.0.20-8/x64"
check_environment JAVA_HOME_21_X64 "$toolcache_root/21.0.12-8/x64"
check_environment JAVA_HOME_25_X64 "$toolcache_root/25.0.4-7/x64"
check_environment JAVA_HOME "$toolcache_root/17.0.20-8/x64"

for required in bash git curl unzip zip jq tar; do
  if command -v "$required" >/dev/null 2>&1; then
    pass "required command present: $required"
  else
    report "required command missing: $required"
  fi
done

for forbidden in docker kubectl helm az gradle mvn; do
  if command -v "$forbidden" >/dev/null 2>&1; then
    report "forbidden command present in image: $forbidden"
  else
    pass "forbidden command absent: $forbidden"
  fi
done

if [ -x /home/runner/run.sh ]; then
  pass "runner entrypoint /home/runner/run.sh is executable"
else
  report "missing executable /home/runner/run.sh"
fi

if [ -d /home/runner/.gradle ]; then
  report "image must not preseed /home/runner/.gradle"
else
  pass "no preseeded Gradle user home"
fi

if [ "$skip_user_check" -eq 0 ]; then
  if [ "$(id -u)" = "0" ]; then
    report "image must not run as root"
  else
    pass "running as uid $(id -u)"
  fi
  if [ "$(id -un)" = "runner" ]; then
    pass "running as user runner"
  else
    report "expected user runner but found $(id -un)"
  fi
else
  pass "user check skipped for the build stage"
fi

if [ "$failures" -ne 0 ]; then
  printf 'Runner image verification failed with %s problem(s).\n' "$failures" >&2
  exit 1
fi

printf 'Runner image verification passed.\n'
```

- [ ] **Step 6: Create the Dockerfile**

Create `runner-images/java/Dockerfile`:

```dockerfile
# syntax=docker/dockerfile:1
FROM ghcr.io/actions/actions-runner:2.336.0@sha256:0cfdcc701ce933c6d243c6b0b2da767366dc9f2e99961d4c3754b0b78084cdda

ARG TOOLCACHE_ROOT=/opt/hostedtoolcache/Java_Temurin-Hotspot_jdk
ARG JDK17_VERSION=17.0.20-8
ARG JDK17_URL=https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20%2B8/OpenJDK17U-jdk_x64_linux_hotspot_17.0.20_8.tar.gz
ARG JDK17_SHA256=be7668bc030d578b83d6d5ef9221d6d6729bbbca8cf94a7d52e16ac68b5a5a35
ARG JDK21_VERSION=21.0.12-8
ARG JDK21_URL=https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12%2B8/OpenJDK21U-jdk_x64_linux_hotspot_21.0.12_8.tar.gz
ARG JDK21_SHA256=e4446ff06a276155697597cc0f1b15da004ff083f4964a35271ecee567177370
ARG JDK25_VERSION=25.0.4-7
ARG JDK25_URL=https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.4%2B7/OpenJDK25U-jdk_x64_linux_hotspot_25.0.4_7.tar.gz
ARG JDK25_SHA256=e58fcdcd637b25c03ca84cbbcefc70d11efb8f4b4cbd05decc9f661769d77f94

USER root

RUN set -eux; \
    apt-get update; \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
      bash \
      ca-certificates \
      curl \
      git \
      jq \
      tar \
      unzip \
      zip; \
    rm -rf /var/lib/apt/lists/*

COPY install-temurin.sh /usr/local/bin/install-temurin.sh
COPY verify-image.sh /usr/local/bin/verify-java-runner-image.sh

RUN set -eux; \
    chmod 0755 /usr/local/bin/install-temurin.sh /usr/local/bin/verify-java-runner-image.sh; \
    /usr/local/bin/install-temurin.sh "${JDK17_VERSION}" "${JDK17_URL}" "${JDK17_SHA256}"; \
    /usr/local/bin/install-temurin.sh "${JDK21_VERSION}" "${JDK21_URL}" "${JDK21_SHA256}"; \
    /usr/local/bin/install-temurin.sh "${JDK25_VERSION}" "${JDK25_URL}" "${JDK25_SHA256}"; \
    rm -f /usr/local/bin/install-temurin.sh; \
    chown -R runner:runner /opt/hostedtoolcache

ENV JAVA_HOME_17_X64=/opt/hostedtoolcache/Java_Temurin-Hotspot_jdk/17.0.20-8/x64
ENV JAVA_HOME_21_X64=/opt/hostedtoolcache/Java_Temurin-Hotspot_jdk/21.0.12-8/x64
ENV JAVA_HOME_25_X64=/opt/hostedtoolcache/Java_Temurin-Hotspot_jdk/25.0.4-7/x64
ENV JAVA_HOME=/opt/hostedtoolcache/Java_Temurin-Hotspot_jdk/17.0.20-8/x64
ENV PATH=/opt/hostedtoolcache/Java_Temurin-Hotspot_jdk/17.0.20-8/x64/bin:${PATH}

RUN /usr/local/bin/verify-java-runner-image.sh --skip-user-check

USER runner
WORKDIR /home/runner

RUN /usr/local/bin/verify-java-runner-image.sh
```

- [ ] **Step 7: Run the manifest gate and verify it passes**

Run:

```bash
sh runner-images/java/check-manifest.sh
```

Expected: `PASS Dockerfile matches toolcache-manifest.json for 3 toolchains`.

- [ ] **Step 8: Document the image contract**

Create `runner-images/java/README.md`:

````markdown
# Java Runner Image

## Contents

- Official GitHub Actions runner base image pinned by digest.
- Eclipse Temurin 17, 21, and 25 installed as a GitHub tool cache under
  `/opt/hostedtoolcache/Java_Temurin-Hotspot_jdk/<normalized-version>/x64` with `x64.complete`
  markers, so `actions/setup-java` selects them without downloading.
- `JAVA_HOME_17_X64`, `JAVA_HOME_21_X64`, `JAVA_HOME_25_X64`, and a default `JAVA_HOME` of 17.
- `bash`, `ca-certificates`, `curl`, `git`, `jq`, `tar`, `unzip`, `zip`.
- Non-root `runner` user and the base image's `/home/runner/run.sh` entrypoint.

## Deliberate exclusions

Gradle, dependency caches, Docker, `kubectl`, `helm`, the Azure CLI, and any credential are excluded.
Gradle is owned by the application repository's committed wrapper, and dependency caching is provided
by the GitHub Actions cache service.

## Build and verify

There is no local Docker daemon. Both operations run server-side in Azure Container Registry:

```bash
sh ../../scripts/build-java-runner.sh
sh ../../scripts/verify-java-runner-image.sh
```

The build fails when the base image digest or any JDK archive checksum does not match, and it runs
`verify-image.sh` twice: once as root during installation and once as the `runner` user.

## Contract regression

```bash
sh check-manifest.sh
```

`toolcache-manifest.json` is the machine-readable source of truth. Any Dockerfile change that drifts
from it fails this gate.
````

- [ ] **Step 9: Commit the runner image**

Run:

```bash
chmod +x runner-images/java/install-temurin.sh runner-images/java/verify-image.sh
git add runner-images
git commit -m "feat: add the Temurin 17/21/25 Java runner image

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 3: Create the Azure Container Registry and grant AcrPull

**Files:**
- Create: `scripts/create-acr.sh`

**Interfaces:**
- Consumes: `scripts/lib/common.sh`.
- Produces: registry `acrafjavaf752aff6` (Basic, admin disabled) in `rg-korvid-contract-test`, and an `AcrPull` role assignment for the AKS kubelet managed identity scoped to that registry only.

- [ ] **Step 1: Confirm the registry does not exist yet**

Run:

```bash
az account set --subscription f752aff6-b20c-4973-b32b-0a60ba2c6764
az acr list --resource-group rg-korvid-contract-test --query "[].name" -o tsv
```

Expected: no output, confirming the observed state that no registry exists.

- [ ] **Step 2: Create the idempotent registry and role assignment script**

Create `scripts/create-acr.sh`:

```sh
#!/bin/sh
set -eu

. "$(dirname -- "$0")/lib/common.sh"

require_azure_subscription

if az acr show --name "$ACR_NAME" --resource-group "$AZURE_RESOURCE_GROUP" >/dev/null 2>&1; then
  info "registry $ACR_NAME already exists"
else
  info "creating registry $ACR_NAME"
  az acr create \
    --name "$ACR_NAME" \
    --resource-group "$AZURE_RESOURCE_GROUP" \
    --location "$AZURE_LOCATION" \
    --sku Basic \
    --admin-enabled false \
    --output none
fi

admin_enabled=$(az acr show --name "$ACR_NAME" --resource-group "$AZURE_RESOURCE_GROUP" --query adminUserEnabled -o tsv)
if [ "$admin_enabled" != "false" ]; then
  fail "registry $ACR_NAME must keep the admin user disabled but reported $admin_enabled"
fi

registry_id=$(az acr show --name "$ACR_NAME" --resource-group "$AZURE_RESOURCE_GROUP" --query id -o tsv)
kubelet_object_id=$(
  az aks show \
    --name "$AKS_CLUSTER_NAME" \
    --resource-group "$AZURE_RESOURCE_GROUP" \
    --query identityProfile.kubeletidentity.objectId -o tsv
)

if [ -z "$kubelet_object_id" ]; then
  fail "could not resolve the kubelet identity of $AKS_CLUSTER_NAME"
fi

existing_assignment=$(
  az role assignment list \
    --assignee "$kubelet_object_id" \
    --role AcrPull \
    --scope "$registry_id" \
    --query "[0].id" -o tsv
)

if [ -n "$existing_assignment" ]; then
  info "AcrPull is already granted to the kubelet identity"
else
  info "granting AcrPull to the kubelet identity"
  az role assignment create \
    --assignee-object-id "$kubelet_object_id" \
    --assignee-principal-type ServicePrincipal \
    --role AcrPull \
    --scope "$registry_id" \
    --output none
fi

info "registry login server $(az acr show --name "$ACR_NAME" --resource-group "$AZURE_RESOURCE_GROUP" --query loginServer -o tsv)"
```

- [ ] **Step 3: Verify the script is classified as Azure-scoped**

Run:

```bash
chmod +x scripts/create-acr.sh
sh scripts/check-script-safety.sh
```

Expected: exit 1 only because scripts from Tasks 4 through 7 are still missing. No line mentions `scripts/create-acr.sh`, which proves every mutating `az` command in it carries `--resource-group "$AZURE_RESOURCE_GROUP"` or `--scope "$registry_id"`.

- [ ] **Step 4: Commit the registry provisioning script**

Run:

```bash
git add scripts/create-acr.sh
git commit -m "feat: add scoped Azure Container Registry provisioning

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 4: Build the image in ACR and verify it inside the registry

**Files:**
- Create: `scripts/build-java-runner.sh`
- Create: `scripts/verify-java-runner-image.sh`

**Interfaces:**
- Consumes: `scripts/lib/common.sh`, the build context `runner-images/java`.
- Produces: `build/last-image-tag.txt` containing `<YYYYMMDD>-<12-char-revision>`; `build/last-image-digest.txt` containing `sha256:<64 hex>`; a registry-side verification run of `/usr/local/bin/verify-java-runner-image.sh`.

- [ ] **Step 1: Create the ACR build script**

Create `scripts/build-java-runner.sh`:

```sh
#!/bin/sh
set -eu

. "$(dirname -- "$0")/lib/common.sh"

repository_root=$(platform_repository_root)

require_azure_subscription
require_command git

if [ -n "$(git -C "$repository_root" status --porcelain)" ]; then
  fail "commit or stash local changes before building so the image tag identifies a real revision"
fi

sh "$repository_root/runner-images/java/check-manifest.sh"

revision=$(git -C "$repository_root" rev-parse --short=12 HEAD)
image_tag="$(date -u +%Y%m%d)-$revision"

mkdir -p "$repository_root/build"

info "building $ACR_LOGIN_SERVER/$IMAGE_REPOSITORY:$image_tag"
az acr build \
  --registry "$ACR_NAME" \
  --resource-group "$AZURE_RESOURCE_GROUP" \
  --image "$IMAGE_REPOSITORY:$image_tag" \
  --platform linux/amd64 \
  --file "$repository_root/runner-images/java/Dockerfile" \
  "$repository_root/runner-images/java"

printf '%s\n' "$image_tag" > "$repository_root/build/last-image-tag.txt"
info "wrote build/last-image-tag.txt with $image_tag"
```

- [ ] **Step 2: Create the registry-side image verification script**

Create `scripts/verify-java-runner-image.sh`:

```sh
#!/bin/sh
set -eu

. "$(dirname -- "$0")/lib/common.sh"

repository_root=$(platform_repository_root)
tag_file="$repository_root/build/last-image-tag.txt"

require_azure_subscription

if [ -n "${1:-}" ]; then
  image_tag=$1
elif [ -f "$tag_file" ]; then
  image_tag=$(cat "$tag_file")
else
  fail "no image tag supplied and $tag_file does not exist"
fi

image_digest=$(
  az acr repository show \
    --name "$ACR_NAME" \
    --image "$IMAGE_REPOSITORY:$image_tag" \
    --query digest -o tsv
)

case "$image_digest" in
  sha256:*) info "resolved $IMAGE_REPOSITORY:$image_tag to $image_digest" ;;
  *) fail "expected a sha256 digest but resolved '$image_digest'" ;;
esac

info "running the in-image verifier through Azure Container Registry"
az acr run \
  --registry "$ACR_NAME" \
  --resource-group "$AZURE_RESOURCE_GROUP" \
  --platform linux/amd64 \
  --cmd "$ACR_LOGIN_SERVER/$IMAGE_REPOSITORY@$image_digest /usr/local/bin/verify-java-runner-image.sh" \
  /dev/null

printf '%s\n' "$image_digest" > "$repository_root/build/last-image-digest.txt"
info "wrote build/last-image-digest.txt with $image_digest"
```

- [ ] **Step 3: Verify both scripts stay Azure-scoped**

Run:

```bash
chmod +x scripts/build-java-runner.sh scripts/verify-java-runner-image.sh
sh scripts/check-script-safety.sh
```

Expected: exit 1 only because scripts from Tasks 5 through 7 are still missing. Neither `scripts/build-java-runner.sh` nor `scripts/verify-java-runner-image.sh` is reported as unscoped, and neither contains a `docker` invocation.

- [ ] **Step 4: Prove no step depends on a local Docker daemon**

Run:

```bash
grep -rn '^[[:space:]]*docker[[:space:]]' scripts runner-images --include='*.sh' || echo 'no docker invocation found'
```

Expected: `no docker invocation found`. The word `docker` appears only in the
`# syntax=docker/dockerfile:1` directive of `runner-images/java/Dockerfile`, in the forbidden-command
loop of `runner-images/java/verify-image.sh`, and inside the pattern string of
`scripts/check-script-safety.sh`. None of those is a `docker` invocation.

- [ ] **Step 5: Commit the image lifecycle scripts**

Run:

```bash
git add scripts/build-java-runner.sh scripts/verify-java-runner-image.sh
git commit -m "feat: build and verify the Java runner image inside ACR

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 5: Author the namespace, egress policy, Helm values, and digest wiring

**Files:**
- Create: `arc/arc-java-build/namespace.yaml`
- Create: `arc/arc-java-build/network-policy.yaml`
- Create: `arc/arc-java-build/values.yaml`
- Create: `arc/arc-java-build/README.md`
- Create: `scripts/update-image-digest.sh`
- Create: `scripts/check-helm-template.sh`

**Interfaces:**
- Consumes: `build/last-image-digest.txt`, chart `oci://ghcr.io/actions/actions-runner-controller-charts/gha-runner-scale-set` version `0.14.2`.
- Produces: `arc/arc-java-build/values.yaml` whose runner container carries an `image:` line with an immutable `@sha256:` digest; `scripts/update-image-digest.sh` that inserts or replaces exactly that line; `scripts/check-helm-template.sh` that renders the chart and asserts the security contract without mutating the cluster.

- [ ] **Step 1: Create the namespace and dedicated service account**

Create `arc/arc-java-build/namespace.yaml`:

```yaml
apiVersion: v1
kind: Namespace
metadata:
  name: arc-runners-java
  labels:
    app.kubernetes.io/part-of: arc-java-build
    pod-security.kubernetes.io/enforce: baseline
    pod-security.kubernetes.io/enforce-version: latest
    pod-security.kubernetes.io/audit: restricted
    pod-security.kubernetes.io/audit-version: latest
    pod-security.kubernetes.io/warn: restricted
    pod-security.kubernetes.io/warn-version: latest
---
apiVersion: v1
kind: ServiceAccount
metadata:
  name: arc-java-build-runner
  namespace: arc-runners-java
  labels:
    app.kubernetes.io/part-of: arc-java-build
automountServiceAccountToken: false
```

- [ ] **Step 2: Create the Cilium default-deny and FQDN egress policy**

Create `arc/arc-java-build/network-policy.yaml`:

```yaml
apiVersion: cilium.io/v2
kind: CiliumNetworkPolicy
metadata:
  name: arc-runners-java-default-deny
  namespace: arc-runners-java
spec:
  description: Denies all ingress and all egress except cluster DNS for every pod in the namespace.
  endpointSelector: {}
  ingress: []
  egress:
    - toEndpoints:
        - matchLabels:
            io.kubernetes.pod.namespace: kube-system
            k8s-app: kube-dns
      toPorts:
        - ports:
            - port: "53"
              protocol: UDP
            - port: "53"
              protocol: TCP
          rules:
            dns:
              - matchPattern: "*"
---
apiVersion: cilium.io/v2
kind: CiliumNetworkPolicy
metadata:
  name: arc-java-build-egress
  namespace: arc-runners-java
spec:
  description: Allows the java-build egress profile to reach GitHub, Gradle, Maven Central, and acrafjavaf752aff6.
  endpointSelector:
    matchLabels:
      agentframework.io/egress-profile: java-build
  egress:
    - toFQDNs:
        - matchName: github.com
        - matchName: api.github.com
        - matchName: codeload.github.com
        - matchName: objects.githubusercontent.com
        - matchName: raw.githubusercontent.com
        - matchName: results-receiver.actions.githubusercontent.com
        - matchPattern: "*.actions.githubusercontent.com"
        - matchName: repo.maven.apache.org
        - matchName: plugins.gradle.org
        - matchName: plugins-artifacts.gradle.org
        - matchName: services.gradle.org
        - matchName: downloads.gradle.org
        - matchName: acrafjavaf752aff6.azurecr.io
        - matchPattern: "*.blob.core.windows.net"
      toPorts:
        - ports:
            - port: "443"
              protocol: TCP
  egressDeny:
    - toCIDR:
        - 169.254.169.254/32
    - toEntities:
        - kube-apiserver
```

Cilium policies are additive, so runner pods obtain DNS from the namespace default-deny policy and
outbound HTTPS from the egress profile policy. The DNS rule is what makes `toFQDNs` enforceable.

- [ ] **Step 3: Create the scale-set values**

Create `arc/arc-java-build/values.yaml`:

```yaml
githubConfigUrl: https://github.com/open-play-ground/agent-framework-java
githubConfigSecret: gha-token
runnerScaleSetName: arc-java-build
minRunners: 0
maxRunners: 5
template:
  metadata:
    labels:
      agentframework.io/egress-profile: java-build
  spec:
    serviceAccountName: arc-java-build-runner
    automountServiceAccountToken: false
    restartPolicy: Never
    securityContext:
      runAsNonRoot: true
      runAsUser: 1001
      runAsGroup: 1001
      fsGroup: 1001
      seccompProfile:
        type: RuntimeDefault
    containers:
      - name: runner
        command:
          - /home/runner/run.sh
        env:
          - name: ACTIONS_RUNNER_REQUIRE_JOB_CONTAINER
            value: "false"
          - name: GRADLE_USER_HOME
            value: /home/runner/.gradle
        securityContext:
          privileged: false
          allowPrivilegeEscalation: false
          runAsNonRoot: true
          runAsUser: 1001
          readOnlyRootFilesystem: false
          capabilities:
            drop:
              - ALL
          seccompProfile:
            type: RuntimeDefault
        resources:
          requests:
            cpu: "1"
            memory: 2Gi
          limits:
            cpu: "2"
            memory: 4Gi
```

`containerMode` is intentionally absent, which keeps Docker-in-Docker and Kubernetes mode disabled.
The runner container has no `image` key yet; `scripts/update-image-digest.sh` writes the verified
immutable digest into this file, and every downstream gate fails while that line is missing.

- [ ] **Step 4: Create the digest writer**

Create `scripts/update-image-digest.sh`:

```sh
#!/bin/sh
set -eu

. "$(dirname -- "$0")/lib/common.sh"

repository_root=$(platform_repository_root)
digest_file="$repository_root/build/last-image-digest.txt"
values_file="$repository_root/arc/arc-java-build/values.yaml"

require_command python3

if [ -n "${1:-}" ]; then
  image_digest=$1
elif [ -f "$digest_file" ]; then
  image_digest=$(cat "$digest_file")
else
  fail "no digest supplied and $digest_file does not exist; run scripts/verify-java-runner-image.sh first"
fi

case "$image_digest" in
  sha256:????????????????????????????????????????????????????????????????) ;;
  *) fail "expected a sha256 digest with 64 hexadecimal characters but received '$image_digest'" ;;
esac

image_reference="$ACR_LOGIN_SERVER/$IMAGE_REPOSITORY@$image_digest"

python3 - "$values_file" "$image_reference" <<'PY'
import pathlib
import sys

values_path, image_reference = sys.argv[1], sys.argv[2]
path = pathlib.Path(values_path)
lines = path.read_text().splitlines()

anchor = "      - name: runner"
if anchor not in lines:
    raise SystemExit("runner container entry not found in " + values_path)

index = lines.index(anchor)
image_line = "        image: " + image_reference
if index + 1 < len(lines) and lines[index + 1].startswith("        image: "):
    lines[index + 1] = image_line
else:
    lines.insert(index + 1, image_line)

path.write_text("\n".join(lines) + "\n")
print("set the runner image to " + image_reference)
PY

grep -n 'image: ' "$values_file"
```

- [ ] **Step 5: Create the render and server dry-run gate**

Create `scripts/check-helm-template.sh`:

```sh
#!/bin/sh
set -eu

. "$(dirname -- "$0")/lib/common.sh"

repository_root=$(platform_repository_root)
render_dir="$repository_root/build/render"
rendered="$render_dir/arc-java-build.yaml"
values_file="$repository_root/arc/arc-java-build/values.yaml"
failures=0

require_command helm

report() {
  printf 'FAIL %s\n' "$1" >&2
  failures=$((failures + 1))
}

expect_contains() {
  if grep -q -- "$1" "$rendered"; then
    printf 'PASS rendered manifest contains %s\n' "$1"
  else
    report "rendered manifest is missing: $1"
  fi
}

expect_absent() {
  if grep -q -- "$1" "$rendered"; then
    report "rendered manifest must not contain: $1"
  else
    printf 'PASS rendered manifest omits %s\n' "$1"
  fi
}

if ! grep -q '^        image: .*@sha256:' "$values_file"; then
  fail "values.yaml has no immutable runner image digest; run scripts/update-image-digest.sh first"
fi

mkdir -p "$render_dir"

helm template "$ARC_RELEASE" "$ARC_CHART" \
  --version "$ARC_CHART_VERSION" \
  --namespace "$ARC_NAMESPACE" \
  --values "$values_file" > "$rendered"

expect_contains "runnerScaleSetName: arc-java-build"
expect_contains "githubConfigUrl: https://github.com/open-play-ground/agent-framework-java"
expect_contains "@sha256:"
expect_contains "allowPrivilegeEscalation: false"
expect_contains "runAsNonRoot: true"
expect_contains "serviceAccountName: arc-java-build-runner"
expect_contains "agentframework.io/egress-profile: java-build"
expect_contains "maxRunners: 5"
expect_contains "minRunners: 0"
expect_absent "privileged: true"
expect_absent "dockerd"
expect_absent "containerMode"

require_kube_context

kubectl apply --dry-run=server --namespace "$ARC_NAMESPACE" \
  -f "$repository_root/arc/arc-java-build/namespace.yaml"
kubectl apply --dry-run=server --namespace "$ARC_NAMESPACE" \
  -f "$repository_root/arc/arc-java-build/network-policy.yaml"

if [ "$failures" -ne 0 ]; then
  printf 'Helm template check failed with %s problem(s).\n' "$failures" >&2
  exit 1
fi

printf 'Helm template and manifest dry-run check passed.\n'
```

- [ ] **Step 6: Run the render gate and verify it blocks on the missing digest**

Run:

```bash
chmod +x scripts/update-image-digest.sh scripts/check-helm-template.sh
sh scripts/check-helm-template.sh
```

Expected: `BLOCKED values.yaml has no immutable runner image digest; run scripts/update-image-digest.sh first` and exit 1. This proves the deployment path cannot proceed with a mutable tag or a missing image.

- [ ] **Step 7: Verify the dry-run classification**

Run:

```bash
sh scripts/check-script-safety.sh
```

Expected: exit 1 only because the Task 6 and Task 7 scripts are still missing. `scripts/check-helm-template.sh` is not reported, proving that every `kubectl apply` in it carries `--dry-run=server` and that it never installs, upgrades, uninstalls, or rolls back a Helm release.

- [ ] **Step 8: Document the scale-set contract**

Create `arc/arc-java-build/README.md`:

````markdown
# arc-java-build Scale Set

## Identity

- Helm release: `arc-java-build`
- Namespace: `arc-runners-java`
- Chart: `oci://ghcr.io/actions/actions-runner-controller-charts/gha-runner-scale-set` version `0.14.2`
- GitHub target: `https://github.com/open-play-ground/agent-framework-java`
- Credential: the pre-defined Kubernetes secret named `gha-token` in `arc-runners-java`

## Security contract

- Non-privileged, `allowPrivilegeEscalation: false`, all Linux capabilities dropped.
- `runAsNonRoot: true` with UID and GID 1001 and the `RuntimeDefault` seccomp profile.
- Dedicated service account `arc-java-build-runner` with no RBAC and no mounted API token.
- `containerMode` is never set, so Docker-in-Docker and Kubernetes mode stay disabled.
- The runner image is referenced only by immutable `@sha256:` digest.
- Namespace default-deny egress with a Cilium FQDN allow-list; AKS IMDS (`169.254.169.254/32`) and
  the Kubernetes API server are explicitly denied.

## Blast radius

This release never touches an existing scale set. `scripts/deploy-arc-java-build.sh` and
`scripts/rollback-arc-java-build.sh` re-snapshot every other `AutoscalingRunnerSet` and fail on any
difference from `baseline/existing-runner-sets.json`.

## Secret handling

The `gha-token` secret is created out of band by a cluster operator:

```bash
kubectl create secret generic gha-token --namespace arc-runners-java --from-literal=github_token=<redacted>
```

That command is intentionally not scripted here. No script in this repository creates, reads,
decodes, or prints a secret value.

## Recovery

```bash
sh ../../scripts/rollback-arc-java-build.sh
sh ../../scripts/verify-arc-java-build.sh
```
````

- [ ] **Step 9: Commit the Kubernetes and Helm assets**

Run:

```bash
git add arc scripts/update-image-digest.sh scripts/check-helm-template.sh
git commit -m "feat: add arc-java-build namespace, egress policy, and Helm values

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 6: Author the blocking GitHub authorization preflight

**Files:**
- Create: `scripts/preflight-github-authorization.sh`

**Interfaces:**
- Consumes: `scripts/lib/common.sh`, `arc/arc-java-build/namespace.yaml`, `arc/arc-java-build/network-policy.yaml`, `arc/arc-java-build/values.yaml`, the operator's authenticated `gh` session, the out-of-band `gha-token` secret.
- Produces: namespace `arc-runners-java` with its service account and network policies; `build/preflight-github-authorization.ok`, which `scripts/deploy-arc-java-build.sh` requires. Every failure exits non-zero with a `BLOCKED` message and no fallback.

- [ ] **Step 1: Create the preflight script**

Create `scripts/preflight-github-authorization.sh`:

```sh
#!/bin/sh
set -eu

. "$(dirname -- "$0")/lib/common.sh"

repository_root=$(platform_repository_root)
values_file="$repository_root/arc/arc-java-build/values.yaml"
marker_file="$repository_root/build/preflight-github-authorization.ok"
probe_manifest="$repository_root/build/authorization-probe.yaml"
probe_name=arc-java-build-authorization-probe

require_kube_context
require_command gh
require_command python3

mkdir -p "$repository_root/build"
rm -f "$marker_file"

if ! gh repo view "$GITHUB_TARGET_REPOSITORY" --json nameWithOwner -q .nameWithOwner >/dev/null 2>&1; then
  fail "target repository $GITHUB_TARGET_REPOSITORY is not visible to the signed-in gh session; create it and grant access, then re-run"
fi
info "target repository $GITHUB_TARGET_REPOSITORY is visible"

if ! grep -q "githubConfigUrl: $GITHUB_CONFIG_URL" "$values_file"; then
  fail "values.yaml must set githubConfigUrl to $GITHUB_CONFIG_URL"
fi

if ! grep -q "githubConfigSecret: $GITHUB_CONFIG_SECRET" "$values_file"; then
  fail "values.yaml must reference the pre-defined secret $GITHUB_CONFIG_SECRET"
fi

image_reference=$(grep '^        image: ' "$values_file" | head -n 1 | sed 's/^        image: //')
case "$image_reference" in
  *@sha256:*) info "runner image $image_reference" ;;
  *) fail "values.yaml has no immutable runner image digest; run scripts/update-image-digest.sh first" ;;
esac

info "applying the namespace, service account, and network policies"
kubectl apply --namespace "$ARC_NAMESPACE" -f "$repository_root/arc/arc-java-build/namespace.yaml"
kubectl apply --namespace "$ARC_NAMESPACE" -f "$repository_root/arc/arc-java-build/network-policy.yaml"

if ! kubectl get secret "$GITHUB_CONFIG_SECRET" --namespace "$ARC_NAMESPACE" >/dev/null 2>&1; then
  fail "secret $GITHUB_CONFIG_SECRET is missing in namespace $ARC_NAMESPACE; a cluster operator must create it out of band before deployment"
fi

secret_keys=$(
  kubectl get secret "$GITHUB_CONFIG_SECRET" --namespace "$ARC_NAMESPACE" \
    -o go-template='{{range $key, $unused := .data}}{{$key}}{{"\n"}}{{end}}'
)
info "credential key names: $(printf '%s' "$secret_keys" | tr '\n' ' ')"

credential_kind=unknown
if printf '%s\n' "$secret_keys" | grep -Fxq github_token; then
  credential_kind=pat
elif printf '%s\n' "$secret_keys" | grep -Fxq github_app_id &&
  printf '%s\n' "$secret_keys" | grep -Fxq github_app_installation_id &&
  printf '%s\n' "$secret_keys" | grep -Fxq github_app_private_key; then
  credential_kind=app
fi
info "credential kind: $credential_kind"

if [ "$credential_kind" = unknown ]; then
  fail "secret $GITHUB_CONFIG_SECRET has neither a github_token key nor the three GitHub App keys"
fi

if [ "$credential_kind" = app ]; then
  if [ "${ARC_GITHUB_APP_AUTHORIZATION_CONFIRMED:-0}" != "1" ]; then
    fail "GitHub App credentials cannot be probed with a bearer token; confirm in GitHub that the app installation covers $GITHUB_TARGET_REPOSITORY, then re-run with ARC_GITHUB_APP_AUTHORIZATION_CONFIRMED=1"
  fi
  info "operator confirmed the GitHub App installation covers $GITHUB_TARGET_REPOSITORY"
  printf 'kind=app repository=%s confirmed-by-operator\n' "$GITHUB_TARGET_REPOSITORY" > "$marker_file"
  info "wrote build/preflight-github-authorization.ok"
  exit 0
fi

kubectl delete job "$probe_name" --namespace "$ARC_NAMESPACE" --ignore-not-found

cat > "$probe_manifest" <<YAML
apiVersion: batch/v1
kind: Job
metadata:
  name: $probe_name
  namespace: $ARC_NAMESPACE
spec:
  backoffLimit: 0
  ttlSecondsAfterFinished: 300
  template:
    metadata:
      labels:
        agentframework.io/egress-profile: java-build
    spec:
      restartPolicy: Never
      serviceAccountName: arc-java-build-runner
      automountServiceAccountToken: false
      securityContext:
        runAsNonRoot: true
        runAsUser: 1001
        runAsGroup: 1001
        seccompProfile:
          type: RuntimeDefault
      containers:
        - name: probe
          image: $image_reference
          command:
            - /bin/sh
            - -c
            - >-
              status=\$(curl --silent --show-error --output /dev/null
              --write-out '%{http_code}'
              --header "Authorization: Bearer \$GITHUB_TOKEN"
              https://api.github.com/repos/$GITHUB_TARGET_REPOSITORY);
              echo "authorization_probe_status=\$status"
          env:
            - name: GITHUB_TOKEN
              valueFrom:
                secretKeyRef:
                  name: $GITHUB_CONFIG_SECRET
                  key: github_token
          securityContext:
            privileged: false
            allowPrivilegeEscalation: false
            capabilities:
              drop:
                - ALL
YAML

info "running the authorization probe; only the HTTP status code is printed"
kubectl apply --namespace "$ARC_NAMESPACE" -f "$probe_manifest"
kubectl wait --namespace "$ARC_NAMESPACE" --for=condition=complete "job/$probe_name" --timeout=180s || true
probe_output=$(kubectl logs --namespace "$ARC_NAMESPACE" "job/$probe_name" --tail=5 2>/dev/null || printf '')
kubectl delete job "$probe_name" --namespace "$ARC_NAMESPACE" --ignore-not-found

info "probe output: $probe_output"
case "$probe_output" in
  *authorization_probe_status=200*)
    info "the credential is authorized for $GITHUB_TARGET_REPOSITORY"
    ;;
  *)
    fail "the credential is not authorized for $GITHUB_TARGET_REPOSITORY; grant access to the token or replace the secret, then re-run"
    ;;
esac

printf 'kind=pat repository=%s status=200\n' "$GITHUB_TARGET_REPOSITORY" > "$marker_file"
info "wrote build/preflight-github-authorization.ok"
```

- [ ] **Step 2: Verify the preflight stays namespace-scoped and secret-safe**

Run:

```bash
chmod +x scripts/preflight-github-authorization.sh
sh scripts/check-script-safety.sh
```

Expected: exit 1 only because `scripts/deploy-arc-java-build.sh`, `scripts/verify-arc-java-build.sh`, and `scripts/rollback-arc-java-build.sh` are still missing. `scripts/preflight-github-authorization.sh` is not reported, proving that every `kubectl apply` and `kubectl delete` in it carries `--namespace "$ARC_NAMESPACE"` and that it never projects secret values.

- [ ] **Step 3: Prove the preflight blocks instead of falling back**

Run:

```bash
grep -n 'fail "' scripts/preflight-github-authorization.sh | wc -l | tr -d ' '
grep -c 'exit 0' scripts/preflight-github-authorization.sh
```

Expected: `8` blocking conditions and exactly `1` early success exit, which is the operator-confirmed GitHub App path. There is no branch that continues after a failed check.

- [ ] **Step 4: Commit the preflight**

Run:

```bash
git add scripts/preflight-github-authorization.sh
git commit -m "feat: block deployment on GitHub repository and credential authorization

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 7: Author deploy, verify, and rollback with existing-scale-set proofs

**Files:**
- Create: `scripts/deploy-arc-java-build.sh`
- Create: `scripts/verify-arc-java-build.sh`
- Create: `scripts/rollback-arc-java-build.sh`
- Modify: `scripts/snapshot-existing-runners.sh`

**Interfaces:**
- Consumes: `build/preflight-github-authorization.ok`, `arc/arc-java-build/baseline/existing-runner-sets.json`, `arc/arc-java-build/values.yaml`.
- Produces: the `arc-java-build` Helm release in `arc-runners-java`; `build/existing-runner-sets-after.json` and `build/existing-runner-sets.diff` as the executable proof that no pre-existing scale set changed; a read-only contract verification of the deployed scale set.

- [ ] **Step 1: Let the snapshot script write to an explicit destination**

Replace the assignment block near the top of `scripts/snapshot-existing-runners.sh` so it accepts an output path:

```sh
repository_root=$(platform_repository_root)
baseline_file=${1:-$repository_root/arc/arc-java-build/baseline/existing-runner-sets.json}
```

Delete the now-unused `baseline_dir` variable and replace `mkdir -p "$baseline_dir"` with:

```sh
mkdir -p "$(dirname -- "$baseline_file")"
```

Replace the final `info` line with:

```sh
info "snapshot written to $baseline_file"
```

Verify the baseline is still reproducible:

```bash
sh scripts/snapshot-existing-runners.sh
git diff --stat arc/arc-java-build/baseline/existing-runner-sets.json
```

Expected: `snapshot written to .../existing-runner-sets.json` and no diff, proving the refactor did not change the captured content.

- [ ] **Step 2: Create the deployment script**

Create `scripts/deploy-arc-java-build.sh`:

```sh
#!/bin/sh
set -eu

. "$(dirname -- "$0")/lib/common.sh"

repository_root=$(platform_repository_root)
values_file="$repository_root/arc/arc-java-build/values.yaml"
baseline_file="$repository_root/arc/arc-java-build/baseline/existing-runner-sets.json"
marker_file="$repository_root/build/preflight-github-authorization.ok"
after_file="$repository_root/build/existing-runner-sets-after.json"
diff_file="$repository_root/build/existing-runner-sets.diff"

require_kube_context
require_command helm
require_command python3

if [ ! -f "$marker_file" ]; then
  fail "deployment is blocked; run scripts/preflight-github-authorization.sh first"
fi

if [ ! -f "$baseline_file" ]; then
  fail "no baseline snapshot; run scripts/snapshot-existing-runners.sh first"
fi

if ! grep -q '^        image: .*@sha256:' "$values_file"; then
  fail "values.yaml has no immutable runner image digest; run scripts/update-image-digest.sh first"
fi

sh "$repository_root/scripts/check-helm-template.sh"

conflicting_namespaces=$(
  helm list --all-namespaces --filter '^arc-java-build$' --output json |
    python3 -c '
import json
import sys

releases = json.load(sys.stdin)
print(" ".join(sorted({release["namespace"] for release in releases if release["namespace"] != "arc-runners-java"})))
'
)

if [ -n "$conflicting_namespaces" ]; then
  fail "a release named arc-java-build already exists in: $conflicting_namespaces"
fi

info "applying the namespace, service account, and network policies"
kubectl apply --namespace "$ARC_NAMESPACE" -f "$repository_root/arc/arc-java-build/namespace.yaml"
kubectl apply --namespace "$ARC_NAMESPACE" -f "$repository_root/arc/arc-java-build/network-policy.yaml"

info "installing or upgrading the $ARC_RELEASE release"
helm upgrade --install "$ARC_RELEASE" "$ARC_CHART" \
  --version "$ARC_CHART_VERSION" \
  --namespace "$ARC_NAMESPACE" \
  --values "$values_file" \
  --wait \
  --timeout 10m

sh "$repository_root/scripts/snapshot-existing-runners.sh" "$after_file"

if diff -u "$baseline_file" "$after_file" > "$diff_file"; then
  info "every pre-existing scale set is unchanged"
else
  cat "$diff_file" >&2
  fail "a pre-existing scale set changed during deployment; see build/existing-runner-sets.diff"
fi

info "deployment complete; run scripts/verify-arc-java-build.sh next"
```

- [ ] **Step 3: Create the read-only verification script**

Create `scripts/verify-arc-java-build.sh`:

```sh
#!/bin/sh
set -eu

. "$(dirname -- "$0")/lib/common.sh"

repository_root=$(platform_repository_root)
baseline_file="$repository_root/arc/arc-java-build/baseline/existing-runner-sets.json"
after_file="$repository_root/build/existing-runner-sets-verify.json"
resource=autoscalingrunnersets.actions.github.com
failures=0

require_kube_context

pass() {
  printf 'PASS %s\n' "$1"
}

report() {
  printf 'FAIL %s\n' "$1" >&2
  failures=$((failures + 1))
}

expect() {
  description=$1
  expected=$2
  actual=$3
  if [ "$actual" = "$expected" ]; then
    pass "$description is $actual"
  else
    report "$description must be $expected but was '$actual'"
  fi
}

scale_set() {
  kubectl get "$resource" "$ARC_RELEASE" --namespace "$ARC_NAMESPACE" -o jsonpath="$1"
}

if ! kubectl get "$resource" "$ARC_RELEASE" --namespace "$ARC_NAMESPACE" >/dev/null 2>&1; then
  fail "scale set $ARC_RELEASE was not found in namespace $ARC_NAMESPACE"
fi

expect "minRunners" "0" "$(scale_set '{.spec.minRunners}')"
expect "maxRunners" "5" "$(scale_set '{.spec.maxRunners}')"
expect "githubConfigUrl" "$GITHUB_CONFIG_URL" "$(scale_set '{.spec.githubConfigUrl}')"
expect "runner container name" "runner" "$(scale_set '{.spec.template.spec.containers[0].name}')"
expect "service account" "arc-java-build-runner" "$(scale_set '{.spec.template.spec.serviceAccountName}')"
expect "allowPrivilegeEscalation" "false" "$(scale_set '{.spec.template.spec.containers[0].securityContext.allowPrivilegeEscalation}')"
expect "privileged" "false" "$(scale_set '{.spec.template.spec.containers[0].securityContext.privileged}')"
expect "runAsNonRoot" "true" "$(scale_set '{.spec.template.spec.securityContext.runAsNonRoot}')"
expect "dropped capabilities" "ALL" "$(scale_set '{.spec.template.spec.containers[0].securityContext.capabilities.drop[0]}')"

runner_image=$(scale_set '{.spec.template.spec.containers[0].image}')
case "$runner_image" in
  "$ACR_LOGIN_SERVER/$IMAGE_REPOSITORY"@sha256:*) pass "runner image is digest-pinned: $runner_image" ;;
  *) report "runner image must be a digest-pinned $ACR_LOGIN_SERVER/$IMAGE_REPOSITORY reference but was '$runner_image'" ;;
esac

container_names=$(scale_set '{range .spec.template.spec.containers[*]}{.name}{" "}{end}')
case "$container_names" in
  "runner ") pass "only the runner container is defined" ;;
  *) report "expected only a runner container but found: $container_names" ;;
esac

listener_phase=$(
  kubectl get pods --namespace arc-systems \
    -l actions.github.com/scale-set-name=arc-java-build \
    -o jsonpath='{.items[0].status.phase}' 2>/dev/null || printf ''
)
if [ "$listener_phase" = "Running" ]; then
  pass "listener pod is Running"
else
  report "listener pod for arc-java-build is not Running (phase '$listener_phase')"
fi

for policy in arc-runners-java-default-deny arc-java-build-egress; do
  if kubectl get ciliumnetworkpolicies.cilium.io "$policy" --namespace "$ARC_NAMESPACE" >/dev/null 2>&1; then
    pass "network policy $policy exists"
  else
    report "missing network policy $policy in $ARC_NAMESPACE"
  fi
done

enforce_label=$(kubectl get namespace "$ARC_NAMESPACE" -o jsonpath='{.metadata.labels.pod-security\.kubernetes\.io/enforce}')
expect "Pod Security Admission enforce label" "baseline" "$enforce_label"

log_agent_ready=$(
  kubectl get daemonset ama-logs --namespace kube-system \
    -o jsonpath='{.status.numberReady}' 2>/dev/null || printf '0'
)
if [ "${log_agent_ready:-0}" -gt 0 ]; then
  pass "cluster log agent is ready on $log_agent_ready node(s), so listener and ephemeral runner logs are exported before pod deletion"
else
  report "no ready ama-logs daemonset in kube-system; enable Container Insights on aks-korvid-contract-test so runner logs survive pod deletion"
fi

sh "$repository_root/scripts/snapshot-existing-runners.sh" "$after_file"
if diff -u "$baseline_file" "$after_file" >/dev/null; then
  pass "every pre-existing scale set is unchanged"
else
  diff -u "$baseline_file" "$after_file" >&2 || true
  report "a pre-existing scale set differs from the committed baseline"
fi

if [ "$failures" -ne 0 ]; then
  printf 'arc-java-build verification failed with %s problem(s).\n' "$failures" >&2
  exit 1
fi

printf 'arc-java-build verification passed.\n'
```

- [ ] **Step 4: Create the release-scoped rollback script**

Create `scripts/rollback-arc-java-build.sh`:

```sh
#!/bin/sh
set -eu

. "$(dirname -- "$0")/lib/common.sh"

repository_root=$(platform_repository_root)
baseline_file="$repository_root/arc/arc-java-build/baseline/existing-runner-sets.json"
after_file="$repository_root/build/existing-runner-sets-rollback.json"

require_kube_context
require_command helm

if [ ! -f "$baseline_file" ]; then
  fail "no baseline snapshot; refusing to roll back without a comparison point"
fi

if helm status "$ARC_RELEASE" --namespace "$ARC_NAMESPACE" >/dev/null 2>&1; then
  info "uninstalling release $ARC_RELEASE from namespace $ARC_NAMESPACE"
  helm uninstall "$ARC_RELEASE" --namespace "$ARC_NAMESPACE" --wait --timeout 10m
else
  info "release $ARC_RELEASE is not installed in namespace $ARC_NAMESPACE"
fi

sh "$repository_root/scripts/snapshot-existing-runners.sh" "$after_file"

if diff -u "$baseline_file" "$after_file" >/dev/null; then
  info "every pre-existing scale set is unchanged"
else
  diff -u "$baseline_file" "$after_file" >&2 || true
  fail "a pre-existing scale set changed during rollback"
fi

info "namespace $ARC_NAMESPACE, its service account, and its network policies were intentionally left in place"
```

- [ ] **Step 5: Run the safety gate and verify it finally passes**

Run:

```bash
chmod +x scripts/deploy-arc-java-build.sh scripts/verify-arc-java-build.sh scripts/rollback-arc-java-build.sh
sh scripts/check-script-safety.sh
```

Expected: `Script safety check passed for 15 scripts.` and exit 0. This is the first green run of the gate and proves:

- `scripts/snapshot-existing-runners.sh` and `scripts/verify-arc-java-build.sh` contain no mutating command;
- `scripts/check-helm-template.sh` only ever runs `kubectl apply --dry-run=server`;
- `scripts/preflight-github-authorization.sh`, `scripts/deploy-arc-java-build.sh`, and `scripts/rollback-arc-java-build.sh` scope every mutation to `--namespace "$ARC_NAMESPACE"` and release `"$ARC_RELEASE"`;
- `scripts/create-acr.sh`, `scripts/build-java-runner.sh`, and `scripts/verify-java-runner-image.sh` scope every Azure mutation to `--resource-group "$AZURE_RESOURCE_GROUP"`, `--registry "$ACR_NAME"`, or `--scope "$registry_id"`;
- no script decodes, inlines, or projects a secret value.

- [ ] **Step 6: Commit the deployment scripts**

Run:

```bash
git add scripts/snapshot-existing-runners.sh scripts/deploy-arc-java-build.sh \
  scripts/verify-arc-java-build.sh scripts/rollback-arc-java-build.sh
git commit -m "feat: deploy, verify, and roll back arc-java-build with baseline proofs

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 8: Execute the platform bring-up

**Files:**
- Modify: `arc/arc-java-build/values.yaml`
- Modify: `README.md`

**Interfaces:**
- Consumes: every script from Tasks 1 through 7.
- Produces: registry `acrafjavaf752aff6` with a verified image; the committed runner image digest in `arc/arc-java-build/values.yaml`; the running `arc-java-build` scale set in `arc-runners-java`.

- [ ] **Step 1: Confirm the environment before any mutation**

Run:

```bash
az account show --query id -o tsv
kubectl config current-context
kubectl get nodes -o name | head -n 1
kubectl -n kube-system get daemonset cilium -o jsonpath='{.status.numberReady}'
helm list --all-namespaces --output table
sh scripts/check-script-safety.sh
sh scripts/snapshot-existing-runners.sh
```

Expected: subscription `f752aff6-b20c-4973-b32b-0a60ba2c6764`; context `aks-korvid-contract-test`; at least one node; a non-zero Cilium ready count; the Helm table lists the existing ARC releases and no `arc-java-build`; the safety gate passes; the baseline snapshot is unchanged.

- [ ] **Step 2: Create the registry and grant AcrPull**

Run:

```bash
sh scripts/create-acr.sh
az acr show --name acrafjavaf752aff6 --resource-group rg-korvid-contract-test --query "{sku:sku.name,admin:adminUserEnabled,login:loginServer}" -o json
```

Expected: the script reports registry creation and the AcrPull grant; the query prints `"sku": "Basic"`, `"admin": false`, and `"login": "acrafjavaf752aff6.azurecr.io"`.

- [ ] **Step 3: Build the runner image in ACR**

Run:

```bash
sh scripts/build-java-runner.sh
cat build/last-image-tag.txt
```

Expected: `az acr build` streams the build, the three `install-temurin.sh` invocations print `OK` from `sha256sum -c -`, both `verify-java-runner-image.sh` invocations print `Runner image verification passed.`, the build reports `Run ID ... was successful after ...`, and `build/last-image-tag.txt` holds a `<YYYYMMDD>-<12-hex>` tag.

- [ ] **Step 4: Verify the pushed image from inside the registry**

Run:

```bash
sh scripts/verify-java-runner-image.sh
cat build/last-image-digest.txt
```

Expected: the `az acr run` output ends with `Runner image verification passed.` and `Run ID ... was successful`; `build/last-image-digest.txt` holds a `sha256:` digest with 64 hexadecimal characters.

- [ ] **Step 5: Pin the verified digest into the Helm values**

Run:

```bash
sh scripts/update-image-digest.sh
git diff arc/arc-java-build/values.yaml
sh scripts/check-helm-template.sh
```

Expected: `set the runner image to acrafjavaf752aff6.azurecr.io/gha-runners/agent-framework-java@sha256:...`; the diff adds exactly one `image:` line under `- name: runner`; the template check prints every `PASS` line and `Helm template and manifest dry-run check passed.`

- [ ] **Step 6: Run the blocking authorization preflight**

Run:

```bash
sh scripts/preflight-github-authorization.sh
cat build/preflight-github-authorization.ok
```

Expected on success: `INFO the credential is authorized for open-play-ground/agent-framework-java` and a marker file containing `kind=pat repository=open-play-ground/agent-framework-java status=200`.

If the run stops with `BLOCKED`, stop here and resolve the named condition. The three most likely blocking conditions and their resolutions are:

- `target repository ... is not visible` — create `https://github.com/open-play-ground/agent-framework-java` and grant the operator access.
- `secret gha-token is missing in namespace arc-runners-java` — ask a cluster operator to create the pre-defined secret in the new namespace with a credential authorized for the target repository.
- `the credential is not authorized ...` — grant the existing credential access to the target repository or provision a new one.

Do not proceed to Step 7 until the preflight exits zero. There is no alternative path.

- [ ] **Step 7: Deploy the new scale set**

Run:

```bash
sh scripts/deploy-arc-java-build.sh
```

Expected: the template check passes, no conflicting release is found, `namespace/arc-runners-java` and both `ciliumnetworkpolicy` resources are configured, Helm reports `STATUS: deployed` for `arc-java-build`, and the script prints `every pre-existing scale set is unchanged`.

- [ ] **Step 8: Verify the deployed contract**

Run:

```bash
sh scripts/verify-arc-java-build.sh
kubectl get autoscalingrunnersets.actions.github.com --all-namespaces
```

Expected: every check prints `PASS` and the script ends with `arc-java-build verification passed.`; the final listing shows the pre-existing scale sets plus `arc-runners-java/arc-java-build`.

- [ ] **Step 9: Confirm the GitHub side registered the scale set**

Run:

```bash
gh api repos/open-play-ground/agent-framework-java/actions/runners --jq '.runners[] | {name: .name, status: .status, labels: [.labels[].name]}'
```

Expected: with `minRunners: 0` there may be no runner listed while idle, which is correct. The scale set itself is registered; confirm it in the repository's Actions settings, or trigger Task 9's smoke workflow and re-run this command while the job is queued to see an ephemeral runner labelled `arc-java-build`.

- [ ] **Step 10: Commit the pinned digest and bring-up record**

Add this section to `README.md` immediately after `## Bring-up order`:

````markdown
## Current deployment

- Registry: `acrafjavaf752aff6.azurecr.io`
- Image repository: `gha-runners/agent-framework-java`
- Deployed digest: the `image:` value in `arc/arc-java-build/values.yaml`
- Scale set: `arc-java-build` in `arc-runners-java`, `minRunners: 0`, `maxRunners: 5`

Reproduce the deployed state with:

```bash
sh scripts/check-helm-template.sh
sh scripts/verify-arc-java-build.sh
```
````

Run:

```bash
git add arc/arc-java-build/values.yaml README.md
git commit -m "feat: deploy arc-java-build with a verified runner image digest

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 9: Add the application-repository smoke workflow

**Files:**
- Create: `/Users/hwang-inhwan/workspace/agent-framework-java/.github/workflows/runner-smoke.yml` (application repository)
- Modify: `/Users/hwang-inhwan/workspace/agent-framework-java/docs/operations/github-actions-runner-contract.md` (application repository)

**Interfaces:**
- Consumes: a verified `arc-java-build` scale set from Task 8 and the Gradle tasks `testJava17`, `testJava21`, `testJava25` created by `2026-08-10-gradle-repository-foundation.md`.
- Produces: a manual smoke workflow that proves an ephemeral runner starts, selects all three preinstalled toolchains without downloading, and runs the compatibility matrix.

This task changes the application repository, not the platform repository. Run it only after
`scripts/verify-arc-java-build.sh` has passed.

- [ ] **Step 1: Confirm platform readiness before touching the application repository**

Run:

```bash
cd /Users/hwang-inhwan/workspace/agent-framework-java-platform
sh scripts/verify-arc-java-build.sh
```

Expected: `arc-java-build verification passed.` Stop and return to Task 8 if any check fails.

- [ ] **Step 2: Create the smoke workflow**

In `/Users/hwang-inhwan/workspace/agent-framework-java`, create `.github/workflows/runner-smoke.yml`:

```yaml
name: Runner smoke

on:
  workflow_dispatch:

permissions:
  contents: read

concurrency:
  group: runner-smoke-${{ github.event.pull_request.number || github.ref }}
  cancel-in-progress: ${{ github.event_name == 'pull_request' }}

jobs:
  toolcache-smoke:
    name: Runner tool cache smoke
    if: github.event_name != 'pull_request' || github.event.pull_request.head.repo.full_name == github.repository
    runs-on: arc-java-build
    timeout-minutes: 20
    permissions:
      contents: read
    steps:
      - name: Check out source
        uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
        with:
          persist-credentials: false

      - name: Select preinstalled Eclipse Temurin toolchains
        uses: actions/setup-java@b6effb05e454b25005698d916606bdc6ffcbf961 # v5.7.0
        with:
          distribution: temurin
          java-version: |
            17
            21
            25

      - name: Assert the runner is non-root and serves JDKs from the tool cache
        run: |
          set -eu
          if [ "$(id -u)" = "0" ]; then
            printf 'runner must not be root\n' >&2
            exit 1
          fi
          printf 'uid=%s user=%s\n' "$(id -u)" "$(id -un)"
          for version in 17 21 25; do
            home_variable="JAVA_HOME_${version}_X64"
            home_path=$(printenv "$home_variable")
            case "$home_path" in
              /opt/hostedtoolcache/Java_Temurin-Hotspot_jdk/*)
                printf '%s=%s\n' "$home_variable" "$home_path"
                "$home_path/bin/java" -version
                ;;
              *)
                printf '%s is not served from the tool cache: %s\n' "$home_variable" "$home_path" >&2
                exit 1
                ;;
            esac
          done
          if command -v docker >/dev/null 2>&1; then
            printf 'the runner image must not ship docker\n' >&2
            exit 1
          fi

      - name: Set up Gradle
        uses: gradle/actions/setup-gradle@9c971963bec38e04b3d30dcc455b5382be2fdbfb # v6.3.0
        with:
          validate-wrappers: true

      - name: Run the Java compatibility matrix
        env:
          JAVA_HOME: ${{ env.JAVA_HOME_17_X64 }}
        run: ./gradlew --no-daemon testJava17 testJava21 testJava25

      - name: Upload verification reports
        if: always()
        uses: actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a # v7.0.1
        with:
          name: runner-smoke-reports
          path: |
            **/build/reports/
            **/build/test-results/
          if-no-files-found: ignore
          retention-days: 7
```

- [ ] **Step 3: Verify the new workflow satisfies the repository workflow policy**

Run:

```bash
cd /Users/hwang-inhwan/workspace/agent-framework-java
./gradlew :build-tools:harness-policy:test --tests '*WorkflowPolicyTest*'
```

Expected: `BUILD SUCCESSFUL`. The parameterized cases now run for both `ci.yml` and `runner-smoke.yml`. The fork and fan-in cases report as skipped for `runner-smoke.yml` because it has no `pull_request` trigger, while the pinning, runner-label, permission, credential-persistence, cancellation, and trusted-condition cases pass for both files.

- [ ] **Step 4: Run the smoke workflow on the new scale set**

Run:

```bash
cd /Users/hwang-inhwan/workspace/agent-framework-java
git add .github/workflows/runner-smoke.yml
git commit -m "ci: add an arc-java-build runner smoke workflow

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
git push
gh workflow run runner-smoke.yml
gh run watch
```

Expected: the run is picked up by an ephemeral `arc-java-build` runner; the assertion step prints a non-zero uid, three `JAVA_HOME_*_X64` values under `/opt/hostedtoolcache/Java_Temurin-Hotspot_jdk/`, and three `openjdk version` lines; `setup-java` logs a cache hit rather than a download; the Gradle step passes `testJava17`, `testJava21`, and `testJava25`.

- [ ] **Step 5: Record platform readiness in the runner contract**

Append this section to `/Users/hwang-inhwan/workspace/agent-framework-java/docs/operations/github-actions-runner-contract.md`:

````markdown
## Readiness

`arc-java-build` is verified by the platform repository and by the application-side smoke workflow:

```bash
gh workflow run runner-smoke.yml
```

The smoke workflow fails when the runner runs as root, when any `JAVA_HOME_*_X64` value is not served
from `/opt/hostedtoolcache/Java_Temurin-Hotspot_jdk/`, when `docker` is present, or when the Java 17,
21, and 25 compatibility tasks do not pass.
````

- [ ] **Step 6: Commit the readiness record**

Run:

```bash
cd /Users/hwang-inhwan/workspace/agent-framework-java
git add docs/operations/github-actions-runner-contract.md
git commit -m "docs: record arc-java-build readiness

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

## Error and Recovery Contract

- **Image checksum or in-image verification fails:** `az acr build` aborts, no tag is written,
  `build/last-image-tag.txt` is not updated, and no Helm operation runs.
- **Registry verification fails:** `scripts/verify-java-runner-image.sh` exits non-zero and never
  writes `build/last-image-digest.txt`, so `scripts/update-image-digest.sh` cannot pin a bad image.
- **Values still lack a digest:** `scripts/check-helm-template.sh`,
  `scripts/preflight-github-authorization.sh`, and `scripts/deploy-arc-java-build.sh` all block.
- **GitHub repository or credential is not authorized:** the preflight exits `BLOCKED`, no marker file
  is written, and deployment refuses to start. There is no fallback repository, secret, or path.
- **New listener or runner fails:** run `sh scripts/rollback-arc-java-build.sh`. It uninstalls only the
  `arc-java-build` release in `arc-runners-java` and then proves every pre-existing scale set is
  unchanged. Existing scale sets are never modified or rolled back.
- **A pre-existing scale set differs from the baseline:** deployment, rollback, and verification all
  fail with the diff in `build/existing-runner-sets.diff`. Investigate before any further change.
- **Tool cache miss on a runner:** trusted CI records the observed `JAVA_HOME_*_X64` values in the job
  summary without failing, and the hard block lives in the image tests: `runner-images/java/verify-image.sh`
  during the ACR build and `runner-smoke.yml` on a live runner.
- **Cluster log agent is not ready:** `scripts/verify-arc-java-build.sh` reports the missing `ama-logs`
  daemonset. Enable Container Insights on `aks-korvid-contract-test` so listener and ephemeral runner
  logs are exported before pods are deleted, then re-run the verification.

## Plan Verification Checklist

- Sections 5, 6, and 10 of `docs/superpowers/specs/2026-08-10-gradle-kotlin-arc-foundation-design.md`
  are implemented by Tasks 1 through 9; section 11's platform half is covered by
  `runner-images/java/check-manifest.sh`, `runner-images/java/verify-image.sh`,
  `scripts/check-helm-template.sh`, and `scripts/verify-arc-java-build.sh`.
- Every observed environment value is used verbatim: subscription, resource group, cluster, location,
  chart version, registry name, namespace, scale-set name, runner bounds, base image digest, Temurin
  URLs and checksums, and the tool cache layout.
- No step invokes a local Docker daemon; all image work runs through `az acr build` and `az acr run`.
- The new scale set is non-privileged, drops all capabilities, uses a dedicated service account, never
  sets `containerMode`, and references the image only by digest.
- Existing scale sets are protected by a committed baseline and a before/after diff in deploy,
  rollback, and verify.
- Listener and ephemeral runner log export is verified by the cluster log agent check in
  `scripts/verify-arc-java-build.sh`.
- GitHub repository and credential authorization is a blocking preflight with seven explicit block
  conditions and no fallback.
- No script creates, reads, decodes, prints, or commits a secret value, and
  `scripts/check-script-safety.sh` proves it.
- Application-repository changes occur only in Task 9 and only after platform verification passes.
- No step contains TBD, TODO, placeholder text, an undefined variable, or an unnamed file.
