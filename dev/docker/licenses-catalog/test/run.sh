#!/usr/bin/env bash
#
# Copyright 2026 Datastrato Inc.
#
# Checks the image/binary catalog generator.

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CATALOG="$(cd "${HERE}/.." && pwd)"
ROOT="$(cd "${CATALOG}/../../.." && pwd)"
WORKDIR="$(mktemp -d)"
trap 'rm -rf "${WORKDIR}"' EXIT

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

pass() {
  echo "PASS: $*"
}

mkdir -p "${WORKDIR}/dev/docker/datastrato-gravitino/licenses" \
  "${WORKDIR}/dev/docker/mcp-server/licenses" \
  "${WORKDIR}/dev/docker/spark-connectors/licenses" \
  "${WORKDIR}/dev/docker/spark-connectors-enterprise/licenses" \
  "${WORKDIR}/dev/docker/flink-connectors/licenses" \
  "${WORKDIR}/dev/docker/flink-connectors-enterprise/licenses" \
  "${WORKDIR}/dev/docker/trino-connectors/licenses" \
  "${WORKDIR}/dev/docker/trino-connectors-enterprise/licenses" \
  "${WORKDIR}/web/web" \
  "${WORKDIR}/web-v2/web"
echo 'Apache License' > "${WORKDIR}/web/web/LICENSE.bin"
echo 'Apache License' > "${WORKDIR}/web-v2/web/LICENSE.bin"
touch "${WORKDIR}/dev/docker/mcp-server/licenses/pylint-3.3.8.LICENSE.txt"
touch "${WORKDIR}/dev/docker/mcp-server/licenses/mcp-only-9.9.9.LICENSE.txt"
touch "${WORKDIR}/dev/docker/mcp-server/licenses/APACHE_GRAVITINO_NOTICE.txt"

python3 "${CATALOG}/apply.py" \
  --root "${WORKDIR}" \
  --csv "${HERE}/fixtures/components.csv" \
  --inherited "${HERE}/fixtures/inherited.txt" \
  --preamble "${HERE}/fixtures/preamble.txt"

grep -q 'example-lib 1.2.3' "${WORKDIR}/NOTICE.bin" || fail "NOTICE.bin missing server Apache row"
grep -q 'Apache Example' "${WORKDIR}/NOTICE.bin" || fail "NOTICE.bin missing inherited notice"
grep -q 'mcp-only' "${WORKDIR}/LICENSE.bin" && fail "LICENSE.bin listed an MCP-only component"
grep -q 'example-lib 1.2.3' "${WORKDIR}/LICENSE.bin" || fail "LICENSE.bin missing server row"
grep -q 'ui-lib 0.1.0' "${WORKDIR}/web/web/LICENSE.bin" || fail "web LICENSE.bin missing UI row"
[[ -f "${WORKDIR}/dev/docker/mcp-server/licenses/pylint-3.3.8.LICENSE.txt" ]] \
  && fail "MCP pylint license text was not pruned"
[[ -f "${WORKDIR}/dev/docker/mcp-server/licenses/mcp-only-9.9.9.LICENSE.txt" ]] \
  || fail "MCP kept license text was removed"
grep -q 'mcp-only 9.9.9' \
  "${WORKDIR}/dev/docker/mcp-server/licenses/THIRD_PARTY_LICENSES.txt" \
  || fail "MCP catalog missing mcp-only"
grep -q 'spark-lib 2.0.0' \
  "${WORKDIR}/dev/docker/spark-connectors-enterprise/licenses/THIRD_PARTY_LICENSES.txt" \
  || fail "Spark enterprise catalog missing spark-lib"
pass "generator fixture"

python3 - "${CATALOG}/apply.py" "${ROOT}" <<'PY'
import importlib.util
import sys
from pathlib import Path

mod_path = Path(sys.argv[1])
root = Path(sys.argv[2])
spec = importlib.util.spec_from_file_location("catalog_apply", mod_path)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)

rows = mod.load_components(mod.DATA_DIR / "third-party-components-1.3.0.csv")
inherited = (mod.DATA_DIR / "inherited-apache-notices.txt").read_text(encoding="utf-8")
server = mod.rows_for_image(rows, "Server")
expected = mod.build_notice(server, inherited, include_inherited=True)
actual = (root / "NOTICE.bin").read_text(encoding="utf-8")
if actual != expected:
    raise SystemExit("NOTICE.bin is stale; run dev/docker/licenses-catalog/apply.sh")
print(f"NOTICE.bin OK ({len(server)} server components)")
PY
pass "NOTICE.bin matches generator"

grep -q 'Datastrato Enterprise third-party catalog (generated)' "${ROOT}/LICENSE.bin" \
  || fail "LICENSE.bin missing generated catalog"
count="$(grep -c 'Datastrato Enterprise third-party catalog (generated)' "${ROOT}/web/web/LICENSE.bin" || true)"
[[ "${count}" == 1 ]] || fail "web LICENSE.bin catalog marker not unique (${count})"
[[ ! -f "${ROOT}/dev/docker/mcp-server/licenses/pylint-3.3.8.LICENSE.txt" ]] \
  || fail "MCP image still ships pylint license text"
[[ -f "${ROOT}/dev/docker/datastrato-gravitino/licenses/NOTICE.txt" ]] \
  || fail "server image missing NOTICE.txt"
pass "applied catalog files"

echo "OK"
