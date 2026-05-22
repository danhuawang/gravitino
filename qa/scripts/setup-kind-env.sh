#!/bin/bash
#
# Copyright 2024 Datastrato Pvt Ltd.
# This software is licensed under the Apache License version 2.
#
# Deploys Gravitino and optional components into a kind cluster for e2e tests.
#
# Usage:
#   bash setup-kind-env.sh                              # deploy Gravitino only
#   bash setup-kind-env.sh --component hive             # deploy Gravitino + Hive Metastore
#   bash setup-kind-env.sh --reset                      # full rebuild
#
# Environment variables:
#   ENV_NAME          – e.g. env1-simple-auth (required)
#   NAMESPACE         – k8s namespace (default: gravitino-e2e-<ENV_NAME>)
#   KIND_IMAGE_TAG    – Gravitino docker image tag (default: from values.yaml)
#   KIND_REGISTRY     – local kind registry address (default: kind-registry:5000)
#   CLUSTER_NAME      – kind cluster name (default: gravitino-e2e)
#   COMPONENTS        – comma-separated list of optional components (alternative to --component flags)
#
# Supported optional components:
#   hive   – Hive Metastore (NodePort 30083)
#
# NodePorts exposed:
#   30090  – Gravitino API (always)
#   30083  – Hive Metastore (if enabled)
#
set -euo pipefail

# ── Default variables ─────────────────────────────────────────────────────────
RESET=${RESET:-false}
COMPONENTS="${COMPONENTS:-}"
declare -a COMPONENT_LIST=()

# ── Parse options ─────────────────────────────────────────────────────────────
while [[ $# -gt 0 ]]; do
  case "$1" in
    --reset)
      RESET=true
      shift
      ;;
    --component)
      if [[ -z "${2:-}" ]]; then
        echo "ERROR: --component requires a value" >&2
        exit 1
      fi
      COMPONENT_LIST+=("$2")
      shift 2
      ;;
    *)
      echo "Unknown argument: $1" >&2
      exit 1
      ;;
  esac
done

# Merge COMPONENTS env var (comma-separated) into COMPONENT_LIST
if [[ -n "${COMPONENTS}" ]]; then
  IFS=',' read -ra ENV_COMPONENTS <<< "${COMPONENTS}"
  for c in "${ENV_COMPONENTS[@]}"; do
    COMPONENT_LIST+=("$(echo "${c}" | xargs)")
  done
fi

# ── Helper: check if a component is enabled ──────────────────────────────────
component_enabled() {
  local target="$1"
  for c in "${COMPONENT_LIST[@]:-}"; do
    if [[ "${c}" == "${target}" ]]; then
      return 0
    fi
  done
  return 1
}

ENV_NAME="${ENV_NAME:?ENV_NAME must be set}"
NAMESPACE="${NAMESPACE:-gravitino-e2e-${ENV_NAME}}"
PUSH_REGISTRY="${PUSH_REGISTRY:-localhost:5001}"
KIND_REGISTRY="${KIND_REGISTRY:-kind-registry:5000}"
CLUSTER_NAME="${CLUSTER_NAME:-gravitino-e2e}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
VALUES_DIR="${SCRIPT_DIR}/../k8s/helm-values"
K8S_DIR="${SCRIPT_DIR}/../k8s"

echo "=== Components to deploy: Gravitino (core)${COMPONENT_LIST[*]:+, ${COMPONENT_LIST[*]}} ==="

# ── Image tag ────────────────────────────────────────────────────────────────
KIND_IMAGE_TAG="${KIND_IMAGE_TAG:-e2e-$(date +%s)-${RANDOM}}"
echo "Using Gravitino image tag: ${KIND_IMAGE_TAG}"

# ── Build extra port mappings based on enabled components ─────────────────────
EXTRA_PORT_MAPPINGS=""
if component_enabled "hive"; then
  EXTRA_PORT_MAPPINGS="${EXTRA_PORT_MAPPINGS}
  - containerPort: 30083
    hostPort: 30083
    protocol: TCP"
fi

# ── Ensure kind cluster exists with proper registry config ───────────────────
if ! kind get clusters | grep -q "^${CLUSTER_NAME}$"; then
  echo "=== Creating kind cluster with registry support ==="
  cat <<EOF | kind create cluster --name "${CLUSTER_NAME}" --config=-
kind: Cluster
apiVersion: kind.x-k8s.io/v1alpha4
nodes:
- role: control-plane
  extraPortMappings:
  - containerPort: 30090
    hostPort: 30090
    protocol: TCP${EXTRA_PORT_MAPPINGS}
containerdConfigPatches:
- |-
  [plugins."io.containerd.grpc.v1.cri".registry.mirrors."${KIND_REGISTRY}"]
    endpoint = ["http://${KIND_REGISTRY}"]
  [plugins."io.containerd.grpc.v1.cri".registry.configs."${KIND_REGISTRY}".tls]
    insecure_skip_verify = true
  [plugins."io.containerd.grpc.v1.cri".registry.configs."${KIND_REGISTRY}"]
    plain_http = true
EOF

  # Connect registry to kind network
  echo "=== Connecting registry to kind network ==="
  docker network connect kind kind-registry 2>/dev/null || true

  # Verify registry connectivity
  echo "=== Verifying registry connectivity ==="
  docker exec "${CLUSTER_NAME}-control-plane" sh -c "curl -f http://${KIND_REGISTRY}/v2/ || echo 'Warning: Registry not accessible'"
else
  echo "=== Using existing kind cluster: ${CLUSTER_NAME} ==="
fi

# ── Verify cluster is ready ──────────────────────────────────────────────────
echo "=== Verifying cluster is ready ==="
kubectl cluster-info --context "kind-${CLUSTER_NAME}" || {
  echo "ERROR: Cluster is not accessible. Please delete and recreate:"
  echo "  kind delete cluster --name ${CLUSTER_NAME}"
  echo "  Then run this script again"
  exit 1
}

# Wait for cluster to be fully ready
kubectl wait --for=condition=Ready nodes --all --timeout=60s

# ── Namespace ────────────────────────────────────────────────────────────────
kubectl create namespace "${NAMESPACE}" --dry-run=client -o yaml | kubectl apply --validate=false -f -

# ── Build & push Gravitino image ──────────────────────────────────────────────
if [[ "${RESET}" == "true" ]]; then
  echo "=== Reset: removing old image ==="
  docker rmi "${PUSH_REGISTRY}/gravitino:${KIND_IMAGE_TAG}" 2>/dev/null || true
fi

# Check if image already exists in local registry
if docker pull "${PUSH_REGISTRY}/gravitino:${KIND_IMAGE_TAG}" 2>/dev/null; then
  echo "=== Image already exists in registry, skipping build ==="
else
  echo "=== Building Gravitino image ==="
  CONTEXT_DIR="${REPO_ROOT}/dev/docker/datastrato-gravitino"
  pushd "${REPO_ROOT}" > /dev/null
  # shellcheck source=dev/docker/datastrato-gravitino/datastrato-gravitino-dependency.sh
  . "${CONTEXT_DIR}/datastrato-gravitino-dependency.sh"
  popd > /dev/null

  docker build \
    --build-arg MYSQL_JDBC_DRIVER_NAME="${MYSQL_JDBC_DRIVER_NAME}" \
    --build-arg POSTGRESQL_JDBC_DRIVER_NAME="${POSTGRESQL_JDBC_DRIVER_NAME}" \
    -t "${PUSH_REGISTRY}/gravitino:${KIND_IMAGE_TAG}" "${CONTEXT_DIR}"

  echo "=== Pushing image to local registry ==="
  docker push "${PUSH_REGISTRY}/gravitino:${KIND_IMAGE_TAG}"
fi

# ── Deploy Gravitino via Helm ─────────────────────────────────────────────────
echo "=== Deploying Gravitino (${ENV_NAME}) ==="
helm dependency build "${REPO_ROOT}/dev/charts/gravitino"

HELM_SET_ARGS=(
  --set "image.registry="
  --set "image.repository=${KIND_REGISTRY}/gravitino"
  --set "image.tag=${KIND_IMAGE_TAG}"
)

# Pass enterprise license key to the Gravitino pod if available
if [[ -n "${GRAVITINO_LICENSE_KEY:-}" ]]; then
  HELM_SET_ARGS+=(
    --set "env[1].name=GRAVITINO_LICENSE_KEY"
    --set-string "env[1].value=${GRAVITINO_LICENSE_KEY}"
  )
fi

helm upgrade --install "gravitino-${ENV_NAME}" \
  "${REPO_ROOT}/dev/charts/gravitino" \
  --namespace "${NAMESPACE}" \
  --values "${VALUES_DIR}/${ENV_NAME}-values.yaml" \
  "${HELM_SET_ARGS[@]}" \
  --wait --timeout 5m

# ── Patch Gravitino Service to NodePort ──────────────────────────────────────
echo "=== Patching Gravitino service to NodePort ==="
CURRENT_TYPE=$(kubectl get svc "gravitino" -n "${NAMESPACE}" -o jsonpath='{.spec.type}')
if [[ "${CURRENT_TYPE}" == "NodePort" ]]; then
  echo "Service already NodePort, updating nodePort value..."
  kubectl patch svc "gravitino" -n "${NAMESPACE}" \
    --type='json' \
    -p='[
      {"op":"replace","path":"/spec/ports/0/nodePort","value":30090}
    ]'
else
  kubectl patch svc "gravitino" -n "${NAMESPACE}" \
    --type='json' \
    -p='[
      {"op":"replace","path":"/spec/type","value":"NodePort"},
      {"op":"add","path":"/spec/ports/0/nodePort","value":30090}
    ]'
fi

# ══════════════════════════════════════════════════════════════════════════════
# Optional Components
# ══════════════════════════════════════════════════════════════════════════════

# ── Hive Metastore ────────────────────────────────────────────────────────────
if component_enabled "hive"; then
  echo "=== Deploying Hive Metastore ==="
  sed "s|\${NAMESPACE}|${NAMESPACE}|g" "${K8S_DIR}/hive-deployment.yaml" | kubectl apply --validate=false -f -
  kubectl rollout status deployment/hive-metastore -n "${NAMESPACE}" --timeout=3m
fi

# ══════════════════════════════════════════════════════════════════════════════
# Export connection info
# ══════════════════════════════════════════════════════════════════════════════
NODE_IP=$(kubectl get nodes -o jsonpath='{.items[0].status.addresses[?(@.type=="InternalIP")].address}')

export GRAVITINO_E2E_URI="http://${NODE_IP}:30090"
export GRAVITINO_E2E_METALAKE="test"
export GRAVITINO_E2E_ENV_NAME="${ENV_NAME}"

if component_enabled "hive"; then
  export GRAVITINO_E2E_HIVE_URI="thrift://${NODE_IP}:30083"
fi

echo ""
echo "=== Connection info ==="
env | grep '^GRAVITINO_E2E_' | sort

if [[ -n "${GITHUB_ENV:-}" ]]; then
  env | grep '^GRAVITINO_E2E_' >> "${GITHUB_ENV}"
fi
