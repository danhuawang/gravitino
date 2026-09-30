<!--
  Copyright 2026 Datastrato Inc.
-->

# Image and binary third-party catalogs

Generates `LICENSE.bin`, `NOTICE.bin`, and per-image `NOTICE.txt` /
`THIRD_PARTY_LICENSES.txt` from the verified 1.3.0 component CSV (enterprise-
deployment #305).

## Replace with the RC9 scan

Overwrite only
`dev/docker/licenses-catalog/data/third-party-components-1.3.0.csv`
(and `inherited-apache-notices.txt` if gravitino NOTICE text changed). Then:

```bash
dev/docker/licenses-catalog/apply.sh
bash dev/docker/licenses-catalog/test/run.sh
```

Do not hand-edit `LICENSE.bin`, `NOTICE.bin`, or the generated files under
`dev/docker/*/licenses/`.

## What is updated

- Server binary: `LICENSE.bin`, `NOTICE.bin`
- Web UI wars: `web/web/LICENSE.bin`, `web/web/NOTICE.bin`, and web-v2
- Images that `COPY licenses /licenses`: server, MCP, Spark/Flink/Trino
  connectors (OSS and enterprise)
- MCP: drops per-package license files for packages not on the scan
  (pylint, pytest, and other removed dev tools)

OpenSearch and the full Trino engine image keep their existing license
files; they are not in the CSV image column.

Marquez images are built outside this repository.
