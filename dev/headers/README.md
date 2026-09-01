<!--
  Copyright 2026 Datastrato Inc.
-->

# Datastrato source license headers

New Datastrato-owned files use the copyright-only header with the **calendar
year the file is generated**. Copy the body from
[`datastrato-apache-2.txt`](datastrato-apache-2.txt) and replace `YEAR`.

```text
Copyright 2026 Datastrato Inc.
```

Do not add `This software is licensed under the Apache License version 2.`
Do not copy a 2024 or 2025 header from an older file. Apache ASF files keep
the long yearless ASF license block. Spotless does not write license text.

## Helper

```bash
dev/headers/apply-datastrato-license-headers.sh apply path/to/NewFile.java
dev/headers/apply-datastrato-license-headers.sh check-year path/to/NewFile.java
dev/headers/rewrite-datastrato-headers.py path/to/OldFile.java
dev/headers/rewrite-datastrato-headers.py --scan
dev/headers/rewrite-datastrato-headers.py --scan --root DIR
dev/headers/test/run.sh
```

Pass **only the new files** you want stamped. `apply` does **not** scan the
repo. It inserts the current calendar year on headerless paths. If you pass a
file that already has a Datastrato header, `apply` leaves the year and entity
alone — including 2024/2025 headers and `Pvt Ltd`. Detection inspects only
the leading comment after an optional shebang or XML declaration
(license-maven-plugin / HawkEye `skipLine`), and never looks past the first
20 lines (`LicenseHeaderClassifier`, same idea as Apache RAT). Copyright or
ASF text in the file body is not a header. An ASF file you pass through is
left unchanged. `check-year` fails only for the files you pass if they have a
stale year.

`rewrite-datastrato-headers.py` is the one-off M3 pass for **existing**
Datastrato headers. Do not use `apply` for that rewrite.

`./gradlew checkDatastratoLicenseHeaders` then requires listed enterprise
source to be `Copyright YYYY Datastrato Inc.` with no Apache grant line.
`checkNewFileLicenseHeaders` only rejects a newly added Datastrato file that
still has the grant line.

Critical paths (locked by `dev/headers/test/run.sh`):

1. Keep `YYYY`; `Pvt Ltd` → `Inc.`; drop the Apache grant line, including on an already-`Inc.` header.
2. Do not bump the year on a current-year `Pvt Ltd` header.
3. Rewrite Java, shell (after shebang), SQL, Markdown, and XML comment styles.
4. Leave quoted copyright / Apache strings in the body alone.
5. Leave copyright text after the first 20 lines alone.
6. Skip ASF, SPDX, already-`Inc.`, and headerless files. Do not stamp.
7. `--scan` skips `docs-enterprise`, stamper fixtures, and classifier tests.
8. Refuse to run with no files unless `--scan` is set. Do not mix `--scan` with file arguments.
