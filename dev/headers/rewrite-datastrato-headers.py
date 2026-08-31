#!/usr/bin/env python3
#
# Copyright 2026 Datastrato Inc.
#
# Rewrite existing Datastrato headers to copyright-only Inc. Keep the year.
# Drop the Apache grant line. Do not stamp headerless files — that is
# apply-datastrato-license-headers.sh. Never rewrite Apache ASF or SPDX headers.
#
# Usage:
#   rewrite-datastrato-headers.py FILE...
#   rewrite-datastrato-headers.py --scan
#   rewrite-datastrato-headers.py --scan --root DIR
#
# Critical paths this script must cover (see README and test/run.sh):
#   1. Keep YYYY; Pvt Ltd → Inc.; drop the Apache grant line, including already-Inc.
#   2. Do not bump the year on a current-year Pvt Ltd header.
#   3. Rewrite comment styles: Java, shell (after shebang), SQL, Markdown, XML.
#   4. Leave quoted copyright / Apache strings in the body alone.
#   5. Leave copyright text after the first 20 lines alone.
#   6. Skip ASF, SPDX, already-Inc., and headerless files.
#   7. --scan skips docs-enterprise, stamper fixtures, and classifier tests.
#   8. Refuse to run with no files unless --scan is set. Do not mix --scan with files.
#
# Only the leading HEADER_LINE_COUNT lines are considered. --scan walks the
# repo for "Datastrato Pvt Ltd" and skips docs-enterprise, stamper fixtures,
# and classifier test strings.

"""Rewrite existing Datastrato headers to ``Copyright YEAR Datastrato Inc.``."""

from __future__ import annotations

import argparse
import os
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
HEADER_LINE_COUNT = 20
COPYRIGHT_RE = re.compile(r"(Copyright\s+\d{4}\s+Datastrato )Pvt Ltd\.")
APACHE_GRANT = "This software is licensed under the Apache License version 2."
ASF_MARKERS = (
    "Licensed to the Apache Software Foundation",
    "SPDX-License-Identifier:",
    "Licensed under the Apache License, Version 2.0",
)
SCAN_SKIP_DIRECTORIES = (
    Path(".git"),
    Path("docs-enterprise"),
    Path("dev/headers/test"),
)
SKIP_FILES = {
    "buildSrc/src/test/java/org/apache/gravitino/license/TestLicenseHeaderClassifier.java",
    "dev/ci/test_new_file_license_headers.sh",
}


def header_lines(text: str) -> tuple[list[str], list[str]]:
    """Split a file into the leading header window and the remainder."""
    lines = text.splitlines(keepends=True)
    n = min(HEADER_LINE_COUNT, len(lines))
    return lines[:n], lines[n:]


def header_has_asf_or_spdx(header: list[str]) -> bool:
    """Return True if the header window looks like an ASF or SPDX block."""
    blob = "".join(header)
    return any(marker in blob for marker in ASF_MARKERS)


def is_quoted_literal(line: str) -> bool:
    """Return True if the license text sits inside a string or char literal."""
    return bool(re.search(r"""['"].*(Copyright|This software is licensed)""", line))


def rewrite_header(header: list[str]) -> tuple[list[str], bool]:
    """Rewrite Pvt Ltd and drop the Apache grant line. Keep the year."""
    rewritten: list[str] = []
    mutated = False
    for line in header:
        if is_quoted_literal(line):
            rewritten.append(line)
            continue
        if APACHE_GRANT in line:
            mutated = True
            continue
        repl = COPYRIGHT_RE.sub(r"\1Inc.", line)
        if repl != line:
            mutated = True
        rewritten.append(repl)
    return rewritten, mutated


def rewrite_file(path: Path) -> bool:
    """Rewrite one file in place. Return True if the file changed."""
    text = path.read_text(encoding="utf-8")
    header, rest = header_lines(text)
    if header_has_asf_or_spdx(header):
        return False
    new_header, mutated = rewrite_header(header)
    if not mutated:
        return False
    new_text = "".join(new_header + rest)
    if text.endswith("\n") and not new_text.endswith("\n"):
        new_text += "\n"
    path.write_text(new_text, encoding="utf-8")
    return True


def should_skip_scan_path(relative_path: Path) -> bool:
    """Return whether the path is excluded from the repository-wide rewrite scan."""
    if relative_path.as_posix() in SKIP_FILES:
        return True
    return any(
        relative_path == skip_directory or skip_directory in relative_path.parents
        for skip_directory in SCAN_SKIP_DIRECTORIES
    )


def scan_candidates(root: Path) -> list[Path]:
    """List eligible paths that still contain ``Datastrato Pvt Ltd``."""
    paths = []
    for directory, child_directories, filenames in os.walk(root):
        directory_path = Path(directory)
        relative_directory = directory_path.relative_to(root)
        child_directories[:] = [
            child
            for child in child_directories
            if not should_skip_scan_path(relative_directory / child)
        ]
        for filename in filenames:
            path = directory_path / filename
            relative_path = path.relative_to(root)
            if should_skip_scan_path(relative_path):
                continue
            try:
                text = path.read_text(encoding="utf-8")
            except (OSError, UnicodeDecodeError):
                continue
            if "Datastrato Pvt Ltd" in text:
                paths.append(path)
    return paths


def main(argv: list[str] | None = None) -> int:
    """Rewrite the files passed on the command line, or scan the repo."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "files",
        nargs="*",
        help="Files to rewrite. Required unless --scan is set.",
    )
    parser.add_argument(
        "--scan",
        action="store_true",
        help="Find Datastrato Pvt Ltd headers in the repo. Skips docs-enterprise, "
        "stamper fixtures, and classifier test strings.",
    )
    parser.add_argument(
        "--root",
        default=str(ROOT),
        help="Repo root for --scan (default: the gravitino-enterprise root).",
    )
    args = parser.parse_args(argv)
    if args.scan:
        if args.files:
            parser.error("pass files or --scan, not both")
        targets = scan_candidates(Path(args.root))
    else:
        if not args.files:
            parser.error("pass the files to rewrite, or use --scan")
        targets = [Path(p) for p in args.files]

    changed = 0
    skipped = 0
    for path in targets:
        if not path.is_file():
            skipped += 1
            continue
        if rewrite_file(path):
            print(f"Rewrote {path}")
            changed += 1
        else:
            skipped += 1
    print(
        f"Rewrote {changed} file(s); skipped {skipped} "
        "(already Inc., ASF/SPDX, missing file, or no Datastrato header)."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
