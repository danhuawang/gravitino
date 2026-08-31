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
dev/headers/test/run.sh
```

Pass **only the new files** you want stamped. The script does **not** scan
the repo. `apply` inserts the current calendar year on headerless paths. If
you pass a file that already has a Datastrato header, `apply` leaves the year
and entity alone — including 2024/2025 headers and `Pvt Ltd`. Detection
inspects only the leading comment after an optional shebang or XML
declaration (license-maven-plugin / HawkEye `skipLine`), and never looks past
the first 20 lines (`LicenseHeaderClassifier`, same idea as Apache RAT).
Copyright or ASF text in the file body is not a header. An ASF file you pass
through is left unchanged. `check-year` fails only for the files you pass if
they have a stale year.
