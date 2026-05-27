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
#   hive       – Hive Metastore (NodePort 30083)
#   keycloak   – Keycloak v26.0.7 (NodePort 30080)
#
# NodePorts exposed:
#   30090  – Gravitino API (always)
#   30001  – Iceberg REST aux service (env2 oauth2-auth; otherwise unbound)
#   30083  – Hive Metastore (if enabled)
#   30080  – Keycloak HTTP (if enabled)
#
# Extra environment variables for the keycloak component:
#   KEYCLOAK_ADMIN_PASSWORD  – admin password (default: admin)
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
if component_enabled "keycloak"; then
  EXTRA_PORT_MAPPINGS="${EXTRA_PORT_MAPPINGS}
  - containerPort: 30080
    hostPort: 30080
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
    protocol: TCP
  - containerPort: 30001
    hostPort: 30001
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

# ── Per-environment Helm overrides ──────────────────────────────────────────
# env2-oauth2-auth: Gravitino validates Keycloak JWTs via JWKS. The values
# file leaves serverUri/authority/jwksUri empty so they can be wired here
# against the in-cluster Keycloak service deployed via the `keycloak`
# component. The IRC dynamic-config-provider metalake is also overridden so
# tests can target a different metalake than the values-file default.
case "${ENV_NAME}" in
  env2-oauth2-auth)
    if ! component_enabled "keycloak"; then
      echo "ERROR: ENV_NAME=${ENV_NAME} requires the 'keycloak' component (use --component keycloak)" >&2
      exit 1
    fi
    NODE_IP_FOR_KEYCLOAK="$(kubectl get nodes -o jsonpath='{.items[0].status.addresses[?(@.type=="InternalIP")].address}')"
    KEYCLOAK_PUBLIC_BASE="http://${NODE_IP_FOR_KEYCLOAK}:30080"
    OAUTH2_SERVER_URI="${OAUTH2_SERVER_URI:-${KEYCLOAK_PUBLIC_BASE}}"
    OAUTH2_AUTHORITY="${OAUTH2_AUTHORITY:-${KEYCLOAK_PUBLIC_BASE}/realms/myrealm}"
    OAUTH2_JWKS_URI="${OAUTH2_JWKS_URI:-${KEYCLOAK_PUBLIC_BASE}/realms/myrealm/protocol/openid-connect/certs}"
    METALAKE_NAME="${GRAVITINO_E2E_METALAKE:-test}"

    HELM_SET_ARGS+=(
      --set "authenticator.oauth.serverUri=${OAUTH2_SERVER_URI}"
      --set "authenticator.oauth.authority=${OAUTH2_AUTHORITY}"
      --set "authenticator.oauth.jwksUri=${OAUTH2_JWKS_URI}"
      --set "icebergRest.dynamicConfigProvider.metalake=${METALAKE_NAME}"
    )
    ;;
esac

helm upgrade --install "gravitino-${ENV_NAME}" \
  "${REPO_ROOT}/dev/charts/gravitino" \
  --namespace "${NAMESPACE}" \
  --values "${VALUES_DIR}/${ENV_NAME}-values.yaml" \
  "${HELM_SET_ARGS[@]}" \
  --wait --timeout 5m

# ── Patch Gravitino Service to NodePort ──────────────────────────────────────
# Port 0 (9090): Gravitino REST API → host NodePort 30090
# Port 1 (9001): Iceberg REST aux service (env2 only; auxService.names=iceberg-rest)
#   → host NodePort 30001. Always pinned for layout consistency; if a chart
#   variant doesn't expose port 1 the patch silently no-ops on the missing index.
echo "=== Patching Gravitino service to NodePort ==="
CURRENT_TYPE=$(kubectl get svc "gravitino" -n "${NAMESPACE}" -o jsonpath='{.spec.type}')
PORT_COUNT=$(kubectl get svc "gravitino" -n "${NAMESPACE}" -o jsonpath='{.spec.ports[*].port}' | wc -w | tr -d ' ')

if [[ "${CURRENT_TYPE}" == "NodePort" ]]; then
  echo "Service already NodePort, updating nodePort value..."
  PATCH_OPS='[
    {"op":"replace","path":"/spec/ports/0/nodePort","value":30090}'
  if [[ "${PORT_COUNT}" -ge 2 ]]; then
    PATCH_OPS+=',
    {"op":"replace","path":"/spec/ports/1/nodePort","value":30001}'
  fi
  PATCH_OPS+='
  ]'
  kubectl patch svc "gravitino" -n "${NAMESPACE}" --type='json' -p="${PATCH_OPS}"
else
  PATCH_OPS='[
    {"op":"replace","path":"/spec/type","value":"NodePort"},
    {"op":"add","path":"/spec/ports/0/nodePort","value":30090}'
  if [[ "${PORT_COUNT}" -ge 2 ]]; then
    PATCH_OPS+=',
    {"op":"add","path":"/spec/ports/1/nodePort","value":30001}'
  fi
  PATCH_OPS+='
  ]'
  kubectl patch svc "gravitino" -n "${NAMESPACE}" --type='json' -p="${PATCH_OPS}"
fi

# ── Seed metalake + IRC default catalog (env2-oauth2-auth only) ─────────────
# IRC's dynamic-config-provider in env2-oauth2-auth-values.yaml is wired to
# `metalake=test, defaultCatalogName=catalog_1`, so any IRC-backed test
# (Trino/Spark via IRC, T12–T19) needs both entities to exist before the
# test JVM connects. We seed them here because:
#   * lakehouse-iceberg requires `catalog-backend` + `uri` properties whose
#     values point at in-cluster services (Postgres, warehouse PVC) — values
#     the test JVM running on the host cannot reasonably guess.
#   * the helm chart's initScript only starts gravitino.sh; it does not seed.
#   * keeping seed logic in the same script that runs `helm upgrade` lets us
#     use the kind cluster's NodePort as the API endpoint without an extra
#     port-forward.
# Idempotent: 409 (already exists) is treated as success so reruns work.
seed_env2_metalake_and_catalog() {
  local node_ip="$1"
  local namespace="$2"
  local release_name="gravitino-${ENV_NAME}"
  local grav_url="http://${node_ip}:30090"
  local pg_host="${release_name}-postgresql.${namespace}"

  echo "=== Seeding metalake '${METALAKE_NAME}' + catalog 'catalog_1' for ${ENV_NAME} ==="

  # Wait for Gravitino's HTTP layer. helm --wait covers pod readiness, but the
  # Jetty server inside the pod may take a few extra seconds to bind the port.
  # Accept any HTTP response (including 401) — a connection error means not ready.
  local ready=false
  for _ in $(seq 1 60); do
    local http_code
    http_code=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 2 \
      "${grav_url}/configs" 2>/dev/null || echo "000")
    if [[ "${http_code}" != "000" ]]; then
      ready=true
      break
    fi
    sleep 2
  done
  if [[ "${ready}" != "true" ]]; then
    echo "ERROR: Gravitino API at ${grav_url}/configs did not become ready in 120s" >&2
    return 1
  fi

  # Mint a service-account token for the bootstrap admin (postman-client is in
  # serviceAdmins per env2-oauth2-auth-values.yaml).
  local kc_client_secret="${OAUTH2_CLIENT_SECRET:-JKaXEyxp1TiTeVf5ggDSjdTiVixCxj37}"
  local kc_token_url="${OAUTH2_SERVER_URI}/realms/myrealm/protocol/openid-connect/token"
  local admin_token
  admin_token=$(
    curl -fsS -X POST "${kc_token_url}" \
      -d 'grant_type=client_credentials' \
      -d 'client_id=postman-client' \
      -d "client_secret=${kc_client_secret}" \
      -d 'scope=openid profile email' |
      jq -r '.access_token'
  )
  if [[ -z "${admin_token}" || "${admin_token}" == "null" ]]; then
    echo "ERROR: Failed to mint Keycloak service-account token from ${kc_token_url}" >&2
    return 1
  fi

  # Create metalake (treat 409 as success — re-run safe).
  local mlk_payload
  mlk_payload=$(jq -nc \
    --arg name "${METALAKE_NAME}" \
    '{name:$name, comment:"seeded by setup-kind-env.sh", properties:{}}')
  local mlk_status
  mlk_status=$(
    curl -sS -o /tmp/grav-seed-mlk.out -w '%{http_code}' \
      -X POST "${grav_url}/api/metalakes" \
      -H "Authorization: Bearer ${admin_token}" \
      -H 'Content-Type: application/json' \
      --data "${mlk_payload}"
  )
  case "${mlk_status}" in
    200|201|409)
      echo "  metalake '${METALAKE_NAME}': HTTP ${mlk_status} (ok)"
      ;;
    *)
      echo "ERROR: createMetalake returned HTTP ${mlk_status}" >&2
      cat /tmp/grav-seed-mlk.out >&2
      return 1
      ;;
  esac

  # Create catalog catalog_1 (lakehouse-iceberg, jdbc backend → in-cluster Postgres,
  # warehouse on the pod's local /tmp). Treat 409 as success.
  local cat_payload
  cat_payload=$(jq -nc \
    --arg uri "jdbc:postgresql://${pg_host}:5432/gravitino" \
    --arg pgUser "gravitino" \
    --arg pgPass "gravitino" \
    --arg warehouse "file:///tmp/iceberg-warehouse" \
    '{
       name:"catalog_1",
       type:"RELATIONAL",
       provider:"lakehouse-iceberg",
       comment:"IRC default catalog (seeded by setup-kind-env.sh)",
       properties:{
         "catalog-backend":"jdbc",
         "uri":$uri,
         "jdbc-user":$pgUser,
         "jdbc-password":$pgPass,
         "jdbc-driver":"org.postgresql.Driver",
         "jdbc-initialize":"true",
         "jdbc-schema-version":"V1",
         "warehouse":$warehouse
       }
     }')
  local cat_status
  cat_status=$(
    curl -sS -o /tmp/grav-seed-cat.out -w '%{http_code}' \
      -X POST "${grav_url}/api/metalakes/${METALAKE_NAME}/catalogs" \
      -H "Authorization: Bearer ${admin_token}" \
      -H 'Content-Type: application/json' \
      --data "${cat_payload}"
  )
  case "${cat_status}" in
    200|201|409)
      echo "  catalog 'catalog_1': HTTP ${cat_status} (ok)"
      ;;
    *)
      echo "ERROR: createCatalog returned HTTP ${cat_status}" >&2
      cat /tmp/grav-seed-cat.out >&2
      return 1
      ;;
  esac
}

# ══════════════════════════════════════════════════════════════════════════════
# Optional Components
# ══════════════════════════════════════════════════════════════════════════════

# ── Hive Metastore ────────────────────────────────────────────────────────────
if component_enabled "hive"; then
  echo "=== Deploying Hive Metastore ==="
  sed "s|\${NAMESPACE}|${NAMESPACE}|g" "${K8S_DIR}/hive-deployment.yaml" | kubectl apply --validate=false -f -
  kubectl rollout status deployment/hive-metastore -n "${NAMESPACE}" --timeout=3m
fi

if component_enabled "keycloak"; then
  echo "=== Deploying Keycloak (ephemeral, namespace=${NAMESPACE}) ==="
  KEYCLOAK_ADMIN_PASSWORD="${KEYCLOAK_ADMIN_PASSWORD:-admin}"

  if [[ "${RESET}" == "true" ]]; then
    echo "Reset requested: deleting any existing Keycloak deployment in ${NAMESPACE}"
    kubectl delete deployment/keycloak -n "${NAMESPACE}" --ignore-not-found=true
    kubectl delete service/keycloak -n "${NAMESPACE}" --ignore-not-found=true
  fi

  sed -e "s|\${NAMESPACE}|${NAMESPACE}|g" \
      -e "s|\${KEYCLOAK_ADMIN_PASSWORD}|${KEYCLOAK_ADMIN_PASSWORD}|g" \
      "${K8S_DIR}/keycloak-deployment.yaml" | kubectl apply --validate=false -f -
  kubectl rollout status deployment/keycloak -n "${NAMESPACE}" --timeout=5m
fi

case "${ENV_NAME}" in
  env2-oauth2-auth)
    NODE_IP_FOR_SEED="$(kubectl get nodes -o jsonpath='{.items[0].status.addresses[?(@.type=="InternalIP")].address}')"
    seed_env2_metalake_and_catalog "${NODE_IP_FOR_SEED}" "${NAMESPACE}"
    ;;
esac

# ══════════════════════════════════════════════════════════════════════════════
# Export connection info
# ══════════════════════════════════════════════════════════════════════════════
NODE_IP=$(kubectl get nodes -o jsonpath='{.items[0].status.addresses[?(@.type=="InternalIP")].address}')

export GRAVITINO_E2E_URI="http://${NODE_IP}:30090"
export GRAVITINO_E2E_METALAKE="test"
export GRAVITINO_E2E_ENV_NAME="${ENV_NAME}"

export GRAVITINO_E2E_IRC_URI="http://${NODE_IP}:30001/iceberg/"
export GRAVITINO_E2E_IRC_CATALOG="${GRAVITINO_E2E_IRC_CATALOG:-catalog_1}"

if component_enabled "hive"; then
  export GRAVITINO_E2E_HIVE_URI="thrift://${NODE_IP}:30083"
fi

if component_enabled "keycloak"; then
  export GRAVITINO_E2E_KEYCLOAK_URI="http://${NODE_IP}:30080"
  export GRAVITINO_E2E_KEYCLOAK_TOKEN_URI="http://${NODE_IP}:30080/realms/myrealm/protocol/openid-connect/token"

  export OAUTH2_SERVER_URI="http://${NODE_IP}:30080"
  export OAUTH2_REALM="${OAUTH2_REALM:-myrealm}"
  export OAUTH2_CLIENT_ID="${OAUTH2_CLIENT_ID:-postman-client}"
  export OAUTH2_CLIENT_SECRET="${OAUTH2_CLIENT_SECRET:-JKaXEyxp1TiTeVf5ggDSjdTiVixCxj37}"
  export OAUTH2_SCOPE="${OAUTH2_SCOPE:-openid profile email}"
  export OAUTH2_TOKEN_PATH="${OAUTH2_TOKEN_PATH:-realms/${OAUTH2_REALM}/protocol/openid-connect/token}"

  # Master-realm bootstrap admin used by tests that need to call the Keycloak Admin REST API
  # (group/user lifecycle). The realm-level `postman-client` service account has no
  # realm-management roles in the seeded image, so admin operations require master/admin-cli.
  export OAUTH2_ADMIN_USER="${OAUTH2_ADMIN_USER:-admin}"
  export OAUTH2_ADMIN_PASSWORD="${OAUTH2_ADMIN_PASSWORD:-${KEYCLOAK_ADMIN_PASSWORD:-admin}}"
fi

echo ""
echo "=== Connection info ==="
env | grep -E '^(GRAVITINO_E2E_|OAUTH2_)' | sort

if [[ -n "${GITHUB_ENV:-}" ]]; then
  env | grep -E '^(GRAVITINO_E2E_|OAUTH2_)' >> "${GITHUB_ENV}"
fi
