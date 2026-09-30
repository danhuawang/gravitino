#!/usr/bin/env bash
#
# Copyright 2026 Datastrato Inc.
#
# Rebuild LICENSE.bin, NOTICE.bin, and image /licenses catalogs from data/.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
python3 "${ROOT}/dev/docker/licenses-catalog/apply.py" --root "${ROOT}"
echo "Run: bash dev/docker/licenses-catalog/test/run.sh"
