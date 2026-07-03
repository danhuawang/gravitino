#!/bin/bash
#
# Copyright 2024 Datastrato Pvt Ltd.
# This software is licensed under the Apache License version 2.
#
# Deploys Gravitino (IRC auxiliary service) + Trino into both GKE clusters
# created by the env1 Terraform configuration (Cross-Metalake Federation).
#
# Catalog config (warehouse, credentials) is NOT passed to helm install.
# Instead, it uses dynamic-config-provider: metalake and catalogs are created
# via the Gravitino REST API after helm install.
#
# Run this script from the repo root:
#   bash qa/iceberg-rest-service-e2e-test/scripts/setup-env1-gke.sh
#
# Required environment variables:
#   GCP_PROJECT          – GCP project ID
#   GCP_ZONE             – GCP zone for GKE clusters (e.g. us-east1-b)
#
#   IRC_E2E_OAUTH2_TOKEN – (optional) OAuth2 bearer token for Gravitino API calls.
#                          If not set, the token is fetched automatically from Keycloak
#                          using TRINO_OAUTH2_CLIENT_ID / TRINO_OAUTH2_CLIENT_SECRET
#                          via the client_credentials grant.
#
#   # Metalake-A (GCS)
#   GRAVITINO_GSA_EMAIL  – GSA email (from Terraform output metalake_a_gsa_email)
#   GCS_BUCKET           – GCS bucket name (from Terraform output metalake_a_gcs_bucket_name)
#
#   # Metalake-B (S3)
#   S3_BUCKET            – AWS S3 bucket name (from Terraform output metalake_b_s3_warehouse_path)
#   S3_REGION            – AWS S3 region
#   S3_ACCESS_KEY        – AWS access key ID (from Terraform output metalake_b_s3_access_key_id)
#   S3_SECRET_KEY        – AWS secret access key (from Terraform output metalake_b_s3_secret_access_key)
#   S3_ROLE_ARN          – AWS IAM role ARN for credential vending (optional)
#
#   # Trino OAuth2 client credentials (Keycloak client with client_credentials grant)
#   TRINO_OAUTH2_CLIENT_ID      – Keycloak client ID for Trino service account
#   TRINO_OAUTH2_CLIENT_SECRET  – Keycloak client secret
#   TRINO_OAUTH2_SCOPE          – OAuth2 scope (default: "profile email")
#
set -euo pipefail

: "${GCP_PROJECT:?GCP_PROJECT must be set}"
: "${GCP_ZONE:?GCP_ZONE must be set (e.g. us-east1-b)}"
: "${KEYCLOAK_IP:?KEYCLOAK_IP must be set (Keycloak public IP from Terraform output)}"
: "${GRAVITINO_GSA_EMAIL:?GRAVITINO_GSA_EMAIL must be set}"
: "${GCS_BUCKET:?GCS_BUCKET must be set}"
: "${S3_BUCKET:?S3_BUCKET must be set}"
: "${S3_REGION:?S3_REGION must be set}"
: "${S3_ACCESS_KEY:?S3_ACCESS_KEY must be set}"
: "${S3_SECRET_KEY:?S3_SECRET_KEY must be set}"
: "${TRINO_OAUTH2_CLIENT_ID:?TRINO_OAUTH2_CLIENT_ID must be set}"
: "${TRINO_OAUTH2_CLIENT_SECRET:?TRINO_OAUTH2_CLIENT_SECRET must be set}"
: "${GRAVITINO_IMAGE_TAG:?GRAVITINO_IMAGE_TAG must be set}"

TRINO_OAUTH2_SCOPE="${TRINO_OAUTH2_SCOPE:-openid profile email}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
VALUES_FILE="${SCRIPT_DIR}/../k8s/helm-values/env1-cross-metalake-values.yaml"
K8S_DIR="${SCRIPT_DIR}/../k8s"
NAMESPACE="irc-e2e-env1"

CLUSTER_A="irc-e2e-env1-metalake-a"
CLUSTER_B="irc-e2e-env1-metalake-b"
METALAKE_A="metalake_a"
METALAKE_B="metalake_b"

GRAVITINO_IMAGE_TAG="${GRAVITINO_IMAGE_TAG:-$(yq '.image.tag' "${REPO_ROOT}/dev/charts/gravitino/values.yaml" | tr -d '"')}"
echo "Using Gravitino image tag: ${GRAVITINO_IMAGE_TAG}"

# Derive Keycloak OAuth2 URIs from the single KEYCLOAK_IP variable
KEYCLOAK_BASE="http://${KEYCLOAK_IP}:8080"
OAUTH2_SERVER_URI="${KEYCLOAK_BASE}"
OAUTH2_AUTHORITY="${KEYCLOAK_BASE}/realms/myrealm"
OAUTH2_JWKS_URI="${KEYCLOAK_BASE}/realms/myrealm/protocol/openid-connect/certs"
KEYCLOAK_TOKEN_ENDPOINT="${KEYCLOAK_BASE}/realms/myrealm/protocol/openid-connect/token"

# ── Helper: fetch OAuth2 token from Keycloak via client_credentials grant ────
# Mirrors KeycloakTokenHelper.getAccessTokenWithClientCredentials()
fetch_keycloak_token() {
  local client_id="$1"
  local client_secret="$2"
  local scope="${3:-profile}"
  local token
  token=$(curl -sf \
    --insecure \
    -X POST "${KEYCLOAK_TOKEN_ENDPOINT}" \
    -H "Content-Type: application/x-www-form-urlencoded" \
    -d "grant_type=client_credentials" \
    -d "client_id=${client_id}" \
    -d "client_secret=${client_secret}" \
    -d "scope=${scope}" \
    | jq -r '.access_token')
  if [[ -z "${token}" || "${token}" == "null" ]]; then
    echo "ERROR: Failed to obtain access token from Keycloak (client_id=${client_id})" >&2
    return 1
  fi
  echo "${token}"
}

IRC_E2E_OAUTH2_TOKEN_AUTO_FETCHED="false"
IRC_E2E_OAUTH2_TOKEN_OBTAINED_AT=""

refresh_oauth2_token() {
  echo "Fetching OAuth2 token from Keycloak (client_credentials)..."
  IRC_E2E_OAUTH2_TOKEN=$(fetch_keycloak_token \
    "${TRINO_OAUTH2_CLIENT_ID}" \
    "${TRINO_OAUTH2_CLIENT_SECRET}" \
    "${TRINO_OAUTH2_SCOPE}")
  IRC_E2E_OAUTH2_TOKEN_AUTO_FETCHED="true"
  IRC_E2E_OAUTH2_TOKEN_OBTAINED_AT="$(date +%s)"
  echo "OAuth2 token obtained successfully."
}

ensure_oauth2_token() {
  if [[ -z "${IRC_E2E_OAUTH2_TOKEN:-}" ]]; then
    refresh_oauth2_token
    return 0
  fi

  if [[ "${IRC_E2E_OAUTH2_TOKEN_AUTO_FETCHED}" == "true" ]]; then
    local now
    now="$(date +%s)"
    if [[ -z "${IRC_E2E_OAUTH2_TOKEN_OBTAINED_AT}" \
      || $((now - IRC_E2E_OAUTH2_TOKEN_OBTAINED_AT)) -gt 240 ]]; then
      echo "Refreshing OAuth2 token (previous token may be expired)..."
      refresh_oauth2_token
    fi
  fi
}

# Auto-fetch IRC_E2E_OAUTH2_TOKEN if not already set
if [[ -z "${IRC_E2E_OAUTH2_TOKEN:-}" ]]; then
  refresh_oauth2_token
else
  echo "Using IRC_E2E_OAUTH2_TOKEN from environment; if you see 'Expired JWT', unset it to allow auto-refresh." >&2
fi

# ── Helper: wait for LoadBalancer IP ─────────────────────────────────────────
wait_for_lb_ip() {
  local svc="$1" ns="$2" ip=""
  echo "Waiting for LoadBalancer IP on ${svc}..." >&2
  for i in $(seq 1 24); do
    ip=$(kubectl get svc "${svc}" -n "${ns}" \
      -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null || true)
    [[ -n "${ip}" ]] && { echo "${ip}"; return 0; }
    sleep 5
  done
  echo "ERROR: LoadBalancer IP not assigned for ${svc} after 2 minutes" >&2
  return 1
}

# ── Helper: create metalake via Gravitino API ─────────────────────────────────
create_metalake() {
  local gravitino_url="$1" metalake_name="$2"
  ensure_oauth2_token
  echo "Creating metalake: ${metalake_name}"
  curl -sf -X POST "${gravitino_url}/api/metalakes" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer ${IRC_E2E_OAUTH2_TOKEN}" \
    -d "{\"name\":\"${metalake_name}\",\"comment\":\"env1 cross-metalake federation\",\"properties\":{}}" \
    | jq -r '.metalake.name'
}

# ── Helper: create Iceberg catalog via Gravitino API ─────────────────────────
create_catalog() {
  local gravitino_url="$1" metalake_name="$2" catalog_name="$3"
  shift 3
  local properties_json="$1"   # JSON object string, e.g. '{"key":"val",...}'

  ensure_oauth2_token
  echo "Creating catalog: ${metalake_name}/${catalog_name}"
  curl -sf -X POST "${gravitino_url}/api/metalakes/${metalake_name}/catalogs" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer ${IRC_E2E_OAUTH2_TOKEN}" \
    -d "{
      \"name\": \"${catalog_name}\",
      \"type\": \"RELATIONAL\",
      \"provider\": \"lakehouse-iceberg\",
      \"comment\": \"env1 catalog\",
      \"properties\": ${properties_json}
    }" | jq -r '.catalog.name'
}

# ── Helper: deploy one cluster ────────────────────────────────────────────────
deploy_cluster() {
  local cluster_name="$1"
  local metalake_name="$2"
  local -a extra_set_args=("${@:3}")

  echo ""
  echo "=== Deploying to cluster: ${cluster_name} (${metalake_name}) ==="

  gcloud container clusters get-credentials "${cluster_name}" \
    --zone "${GCP_ZONE}" --project "${GCP_PROJECT}"

  kubectl create namespace "${NAMESPACE}" --dry-run=client -o yaml | kubectl apply --validate=false -f -

  # Mount GCS service account JSON for credential vending (needed by both clusters:
  # Metalake-A for catalog_1, Metalake-B for catalog_2 proxy to Metalake-A's GCS IRC)
  if [[ -n "${GCS_SERVICE_ACCOUNT_JSON_CONTENT:-}" ]]; then
    kubectl create secret generic gcs-service-account \
      --namespace="${NAMESPACE}" \
      --from-literal=key.json="${GCS_SERVICE_ACCOUNT_JSON_CONTENT}" \
      --dry-run=client -o yaml | kubectl apply --validate=false -f -
  fi

  # Helm release names must be lowercase alphanumeric + hyphens only (no underscores)
  local helm_release_name="gravitino-env1-${metalake_name//_/-}"
  helm dependency build "${REPO_ROOT}/dev/charts/gravitino"
  helm upgrade --install "${helm_release_name}" \
    "${REPO_ROOT}/dev/charts/gravitino" \
    --namespace "${NAMESPACE}" \
    --values "${VALUES_FILE}" \
    --set "image.tag=${GRAVITINO_IMAGE_TAG}" \
    --set "authenticator.oauth.serverUri=${OAUTH2_SERVER_URI}" \
    --set "authenticator.oauth.authority=${OAUTH2_AUTHORITY}" \
    --set "authenticator.oauth.jwksUri=${OAUTH2_JWKS_URI}" \
    --set "icebergRest.dynamicConfigProvider.metalake=${metalake_name}" \
    ${extra_set_args[@]+"${extra_set_args[@]}"} \
    --wait --timeout 5m

  # Expose Gravitino via public LoadBalancer
  kubectl patch svc gravitino -n "${NAMESPACE}" \
    --type='json' \
    -p='[{"op":"replace","path":"/spec/type","value":"LoadBalancer"}]' || true

  # Deploy Trino
  IRC_SERVICE_URL="http://gravitino.${NAMESPACE}:9001/iceberg/"
  KEYCLOAK_TOKEN_URI="${KEYCLOAK_BASE}/realms/myrealm/protocol/openid-connect/token"

  local fs_native_gcs_enabled="false"
  local fs_native_s3_enabled="false"
  local gcs_project_id="${GCP_PROJECT}"
  if [[ "${metalake_name}" == "${METALAKE_A}" ]]; then
    fs_native_gcs_enabled="true"
  else
    fs_native_s3_enabled="true"
  fi

  sed \
    -e "s|\${TRINO_NAMESPACE}|${NAMESPACE}|g" \
    -e "s|\${IRC_SERVICE_URL}|${IRC_SERVICE_URL}|g" \
    -e "s|\${KEYCLOAK_TOKEN_URI}|${KEYCLOAK_TOKEN_URI}|g" \
    -e "s|\${TRINO_OAUTH2_CREDENTIAL}|${TRINO_OAUTH2_CLIENT_ID}:${TRINO_OAUTH2_CLIENT_SECRET}|g" \
    -e "s|\${TRINO_OAUTH2_SCOPE}|${TRINO_OAUTH2_SCOPE:-profile email}|g" \
    -e "s|\${FS_NATIVE_GCS_ENABLED}|${fs_native_gcs_enabled}|g" \
    -e "s|\${GCS_PROJECT_ID}|${gcs_project_id}|g" \
    -e "s|\${FS_NATIVE_S3_ENABLED}|${fs_native_s3_enabled}|g" \
    -e "s|\${S3_REGION}|${S3_REGION}|g" \
    "${K8S_DIR}/trino-deployment.yaml" | kubectl apply --validate=false -f -

  kubectl rollout status deployment/trino -n "${NAMESPACE}" --timeout=3m


  echo "=== Cluster ${cluster_name} ready ==="
}

# ── Helper: create metalake + 2 catalogs via API ──────────────────────────────
# (catalog_1 = native, catalog_2 = REST proxy to the other Metalake's IRC)
# Catalogs are created inline below after both clusters are deployed.

resolve_gravitino_lb_ip() {
  local cluster_name="$1"
  local ip=""

  gcloud container clusters get-credentials "${cluster_name}" \
    --zone "${GCP_ZONE}" --project "${GCP_PROJECT}" --quiet

  echo "Resolving LoadBalancer IP for cluster ${cluster_name}..." >&2
  for i in $(seq 1 24); do
    ip=$(kubectl get svc gravitino -n "${NAMESPACE}" \
      -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null || true)
    [[ -n "${ip}" ]] && { echo "${ip}"; return 0; }
    sleep 5
  done
  echo "ERROR: LoadBalancer IP not assigned for gravitino after 2 minutes (cluster=${cluster_name})" >&2
  return 1
}

# ── Deploy Metalake-A (GCS) ───────────────────────────────────────────────────
# deploy_cluster "${CLUSTER_A}" "${METALAKE_A}" \
#   "--set" "serviceAccount.annotations.iam\\.gke\\.io/gcp-service-account=${GRAVITINO_GSA_EMAIL}"

LB_IP_A=$(resolve_gravitino_lb_ip "${CLUSTER_A}")
GRAVITINO_URL_A="http://${LB_IP_A}:8090"
IRC_URL_A="http://${LB_IP_A}:9001/iceberg"

# ── Deploy Metalake-B (S3) ────────────────────────────────────────────────────
# deploy_cluster "${CLUSTER_B}" "${METALAKE_B}"

LB_IP_B=$(resolve_gravitino_lb_ip "${CLUSTER_B}")
GRAVITINO_URL_B="http://${LB_IP_B}:8090"
IRC_URL_B="http://${LB_IP_B}:9001/iceberg"

# ── Create catalogs for Metalake-A ───────────────────────────────────────────
# gcloud container clusters get-credentials "${CLUSTER_A}" \
#   --zone "${GCP_ZONE}" --project "${GCP_PROJECT}" --quiet
#
# create_metalake "${GRAVITINO_URL_A}" "${METALAKE_A}"
#
# # catalog_1: native GCS-backed Iceberg catalog (default catalog)
# create_catalog "${GRAVITINO_URL_A}" "${METALAKE_A}" "catalog_1" "$(cat <<EOF
# {
#   "catalog-backend": "jdbc",
#   "uri": "jdbc:postgresql://gravitino-env1-${METALAKE_A//_/-}-postgresql.${NAMESPACE}:5432/gravitinoIRC",
#   "jdbc-user": "gravitino",
#   "jdbc-password": "gravitino",
#   "jdbc-driver": "org.postgresql.Driver",
#   "jdbc-initialize": "true",
#   "jdbc-schema-version": "V1",
#   "warehouse": "gs://${GCS_BUCKET}/env1/${METALAKE_A}/catalog_1",
#   "io-impl": "org.apache.iceberg.gcp.gcs.GCSFileIO",
#   "credential-providers": "gcs-token",
#   "gcs-service-account-file": "/etc/gcs/key.json"
# }
# EOF
# )"
#
# # catalog_2: REST proxy → Metalake-B's IRC (S3-backed)
# create_catalog "${GRAVITINO_URL_A}" "${METALAKE_A}" "catalog_2" "$(cat <<EOF
# {
#   "catalog-backend": "rest",
#   "uri": "${IRC_URL_B}",
#   "s3-access-key-id": "${S3_ACCESS_KEY}",
#   "s3-secret-access-key": "${S3_SECRET_KEY}",
#   "s3-region": "${S3_REGION}",
#   "credential-providers": "s3-secret-key",
#   "s3-token-expire-in-secs": "900",
#   "data-access": "vended-credentials"
# }
# EOF
# )"

# ── Create catalogs for Metalake-B ───────────────────────────────────────────
gcloud container clusters get-credentials "${CLUSTER_B}" \
  --zone "${GCP_ZONE}" --project "${GCP_PROJECT}" --quiet

create_metalake "${GRAVITINO_URL_B}" "${METALAKE_B}"

# catalog_1: native S3-backed Iceberg catalog (default catalog)
create_catalog "${GRAVITINO_URL_B}" "${METALAKE_B}" "catalog_1" "$(cat <<EOF
{
  "catalog-backend": "jdbc",
  "uri": "jdbc:postgresql://gravitino-env1-${METALAKE_B//_/-}-postgresql.${NAMESPACE}:5432/gravitinoIRC",
  "jdbc-user": "gravitino",
  "jdbc-password": "gravitino",
  "jdbc-driver": "org.postgresql.Driver",
  "jdbc-initialize": "true",
  "jdbc-schema-version": "V1",
  "warehouse": "s3://${S3_BUCKET}/env1/${METALAKE_B}/catalog_1",
  "io-impl": "org.apache.iceberg.aws.s3.S3FileIO",
  "credential-providers": "s3-token",
  "s3-token-expire-in-secs": "900",
  "s3-access-key-id": "${S3_ACCESS_KEY}",
  "s3-secret-access-key": "${S3_SECRET_KEY}",
  "s3-region": "${S3_REGION}",
  "s3-role-arn": "${S3_ROLE_ARN:-}"
}
EOF
)"

# catalog_2: REST proxy → Metalake-A's IRC (GCS-backed)
create_catalog "${GRAVITINO_URL_B}" "${METALAKE_B}" "catalog_2" "$(cat <<EOF
{
  "catalog-backend": "rest",
  "uri": "${IRC_URL_A}",
  "credential-providers": "gcs-token",
  "gcs-service-account-file": "/etc/gcs/key.json",
  "data-access": "vended-credentials"
}
EOF
)"

# ── Print connection info ─────────────────────────────────────────────────────
echo ""
echo "=== Environment 1 ready ==="
echo ""
echo "Metalake-A (GCS):"
echo "  Gravitino : ${GRAVITINO_URL_A}"
echo "  IRC       : http://${LB_IP_A}:9001/iceberg/"
echo "  Metalake  : ${METALAKE_A}  (catalogs: catalog_1, catalog_2)"
echo ""
echo "Metalake-B (S3):"
echo "  Gravitino : ${GRAVITINO_URL_B}"
echo "  IRC       : http://${LB_IP_B}:9001/iceberg/"
echo "  Metalake  : ${METALAKE_B}  (catalogs: catalog_1, catalog_2)"
echo ""
echo "Federation: Metalake-B can access Metalake-A's IRC at http://${LB_IP_A}:9001/iceberg/"

# Write to GITHUB_ENV if running in CI
if [[ -n "${GITHUB_ENV:-}" ]]; then
  {
    echo "IRC_E2E_METALAKE_A_GRAVITINO_URI=${GRAVITINO_URL_A}"
    echo "IRC_E2E_METALAKE_A_IRC_URI=http://${LB_IP_A}:9001/iceberg/"
    echo "IRC_E2E_METALAKE_B_GRAVITINO_URI=${GRAVITINO_URL_B}"
    echo "IRC_E2E_METALAKE_B_IRC_URI=http://${LB_IP_B}:9001/iceberg/"
  } >> "${GITHUB_ENV}"
fi
