#!/bin/bash
#
# Copyright 2024 Datastrato Pvt Ltd.
# This software is licensed under the Apache License version 2.
#
# Cleanup script for Gravitino e2e test environment
#
# Usage:
#   bash cleanup.sh              # Clean namespace only
#   bash cleanup.sh --full       # Clean namespace + kind cluster
#   bash cleanup.sh --registry   # Also clean registry storage
#
set -euo pipefail

FULL_CLEANUP=false
CLEAN_REGISTRY=false

for arg in "$@"; do
  case "${arg}" in
    --full) FULL_CLEANUP=true ;;
    --registry) CLEAN_REGISTRY=true ;;
    *) echo "Unknown argument: ${arg}" >&2; exit 1 ;;
  esac
done

ENV_NAME="${ENV_NAME:-env1-simple-auth}"
NAMESPACE="${NAMESPACE:-gravitino-e2e-${ENV_NAME}}"
CLUSTER_NAME="${CLUSTER_NAME:-gravitino-e2e}"

echo "=== Cleaning up Gravitino e2e test environment ==="

# 1. Delete namespace
if kubectl get namespace "${NAMESPACE}" > /dev/null 2>&1; then
  echo "=== Deleting namespace: ${NAMESPACE} ==="
  kubectl delete namespace "${NAMESPACE}" --timeout=60s || true
else
  echo "Namespace ${NAMESPACE} does not exist, skipping"
fi

# 2. Delete kind cluster(s) (if --full)
#
# We delete every cluster whose name starts with "${CLUSTER_NAME}" so that
# leftover clusters from older script versions (e.g. gravitino-e2e-env1-simple-auth)
# or forked environments don't keep host ports (30080/30083/30090) bound and
# block the next `setup-kind-env.sh` run.
if [[ "${FULL_CLEANUP}" == "true" ]]; then
  MATCHING_CLUSTERS=$(kind get clusters 2>/dev/null | grep -E "^${CLUSTER_NAME}(\$|-)" || true)
  if [[ -n "${MATCHING_CLUSTERS}" ]]; then
    while IFS= read -r cluster; do
      echo "=== Deleting kind cluster: ${cluster} ==="
      kind delete cluster --name "${cluster}"
    done <<< "${MATCHING_CLUSTERS}"
  else
    echo "No kind clusters matching '${CLUSTER_NAME}*' found, skipping"
  fi
fi

# 3. Clean registry storage (if --registry)
if [[ "${CLEAN_REGISTRY}" == "true" ]]; then
  if docker ps | grep -q kind-registry; then
    echo "=== Running registry garbage collection ==="
    docker exec kind-registry bin/registry garbage-collect /etc/docker/registry/config.yml --delete-untagged
    
    echo "=== Registry storage usage ==="
    docker exec kind-registry du -sh /var/lib/registry
  else
    echo "kind-registry container not found, skipping registry cleanup"
  fi
fi

echo ""
echo "=== Cleanup complete ==="
if [[ "${FULL_CLEANUP}" == "true" ]]; then
  echo "✅ Namespace and matching kind cluster(s) deleted"
else
  echo "✅ Namespace deleted (kind cluster preserved)"
  echo "To delete the cluster, run: bash cleanup.sh --full"
fi
