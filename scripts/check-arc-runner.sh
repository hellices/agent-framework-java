#!/bin/sh
set -eu

namespace=${ARC_RUNNER_NAMESPACE:-arc-runners}
scale_set=${ARC_RUNNER_SCALE_SET:-aks-runners}
resource="autoscalingrunnersets.actions.github.com"

if ! command -v kubectl >/dev/null 2>&1; then
  printf 'kubectl is required for ARC preflight.\n' >&2
  exit 1
fi

context=$(kubectl config current-context)
if [ -z "$context" ]; then
  printf 'No kubectl context is selected.\n' >&2
  exit 1
fi

kubectl get crd autoscalingrunnersets.actions.github.com >/dev/null
kubectl get "$resource" "$scale_set" -n "$namespace" >/dev/null

minimum=$(kubectl get "$resource" "$scale_set" -n "$namespace" -o jsonpath='{.spec.minRunners}')
maximum=$(kubectl get "$resource" "$scale_set" -n "$namespace" -o jsonpath='{.spec.maxRunners}')
service_account=$(
  kubectl get "$resource" "$scale_set" -n "$namespace" \
    -o jsonpath='{.spec.template.spec.serviceAccountName}'
)
privileged=$(
  kubectl get "$resource" "$scale_set" -n "$namespace" \
    -o jsonpath='{range .spec.template.spec.containers[*]}{.securityContext.privileged}{"\n"}{end}'
)

if [ "${minimum:-0}" -ne 0 ]; then
  printf 'Expected %s/%s minRunners=0, found %s.\n' "$namespace" "$scale_set" "$minimum" >&2
  exit 1
fi

if [ -z "$maximum" ] || [ "$maximum" -le 0 ]; then
  printf 'Expected %s/%s to have a positive maxRunners value.\n' "$namespace" "$scale_set" >&2
  exit 1
fi

if [ -z "$service_account" ] || [ "$service_account" = "default" ]; then
  printf 'Expected %s/%s to use a dedicated service account.\n' "$namespace" "$scale_set" >&2
  exit 1
fi

if printf '%s\n' "$privileged" | grep -Fxq true; then
  printf 'Expected %s/%s runner containers to be non-privileged.\n' "$namespace" "$scale_set" >&2
  exit 1
fi

printf 'ARC runner contract passed: context=%s namespace=%s scaleSet=%s max=%s serviceAccount=%s\n' \
  "$context" "$namespace" "$scale_set" "$maximum" "$service_account"
