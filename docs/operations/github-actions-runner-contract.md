# GitHub Actions Runner Contract

## Application repository contract

The repository references `runs-on: aks-runners` for ordinary Maven verification. Jobs must not
assume Docker, privileged mode, Azure credentials, Kubernetes API permissions, internal service
access, persistent workspace storage, or a pre-warmed runner.

The repository owns workflow triggers, pinned actions, token permissions, Maven commands, caches,
artifacts, and timeouts.

## Platform contract

The platform owns ARC Helm releases, GitHub App credentials, runner images, service accounts,
namespaces, Pod Security Admission, network policy, node pools, autoscaling, log export, runner
groups, and Entra federated identities.

The `aks-runners` scale set is expected to:

- live in namespace `arc-runners`;
- be ephemeral and non-privileged;
- use a dedicated service account with no application permissions;
- scale from zero with a finite positive maximum;
- reach GitHub Actions and Maven Central over HTTPS;
- export controller, listener, and runner logs before pod deletion.

Run the read-only local preflight with:

```bash
sh scripts/check-arc-runner.sh
```

Override only for inspection of another approved scale set:

```bash
ARC_RUNNER_NAMESPACE=arc-runners \
ARC_RUNNER_SCALE_SET=another-scale-set \
sh scripts/check-arc-runner.sh
```

## Trust boundary

Do not run untrusted fork code on a runner that can reach internal services or obtain repository,
Azure, or Kubernetes credentials. Never combine `pull_request_target`, checkout of pull-request
head code, and execution of repository scripts.

Docker/Testcontainers jobs require a separately reviewed scale set, namespace, runner group, and
network policy. This repository does not create that privileged runner.
