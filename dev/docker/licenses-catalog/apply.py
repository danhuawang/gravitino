#!/usr/bin/env python3
#
# Copyright 2026 Datastrato Inc.
#

"""Generate LICENSE.bin, NOTICE.bin, and per-image /licenses catalogs from the verified CSV."""

from __future__ import annotations

import argparse
import csv
import re
from collections import defaultdict
from pathlib import Path

CATALOG_DIR = Path(__file__).resolve().parent
REPO_ROOT = CATALOG_DIR.parents[2]
DATA_DIR = CATALOG_DIR / "data"
CSV_COLUMNS = (
    "section",
    "component",
    "versions",
    "license",
    "license_source",
    "images",
)
GENERATED_MARK = (
    "========================================================================\n"
    "Datastrato Enterprise third-party catalog (generated)\n"
    "========================================================================\n"
)
NOTICE_PREAMBLE = """\
Datastrato Enterprise
Copyright 2024-2026 Datastrato Inc.

This product includes software developed at Datastrato Inc.
(https://datastrato.ai/).

This file reproduces NOTICE text required by the Apache License,
Version 2.0, section 4(d), for Apache-licensed components in this image
or binary package. The component list is the verified Datastrato
Enterprise 1.3.0 scan (dev/docker/licenses-catalog/data).
"""
SHORT_GRAVITINO = """\
Apache Gravitino
Copyright 2026 The Apache Software Foundation

This product includes software developed at
The Apache Software Foundation (http://www.apache.org/).

The initial code for the Gravitino project was donated
to the ASF by Datastrato (https://datastrato.ai/) copyright 2023-2024.
"""
# Dockerfile directories that COPY licenses /licenses for a CSV image label.
IMAGE_LICENSE_DIRS: tuple[tuple[str, str], ...] = (
    ("Server", "dev/docker/datastrato-gravitino/licenses"),
    ("MCP server", "dev/docker/mcp-server/licenses"),
    ("Spark connector", "dev/docker/spark-connectors/licenses"),
    ("Spark connector", "dev/docker/spark-connectors-enterprise/licenses"),
    ("Flink connector", "dev/docker/flink-connectors/licenses"),
    ("Flink connector", "dev/docker/flink-connectors-enterprise/licenses"),
    ("Trino connector", "dev/docker/trino-connectors/licenses"),
    ("Trino connector", "dev/docker/trino-connectors-enterprise/licenses"),
)
MCP_KEEP_ALWAYS = {
    "APACHE_GRAVITINO_LICENSE.txt",
    "APACHE_GRAVITINO_NOTICE.txt",
    "NOTICE.txt",
    "THIRD_PARTY_LICENSES.txt",
}


def image_labels(images: str) -> list[str]:
    """Split the CSV images cell on commas and semicolons."""
    return [part.strip() for part in re.split(r"[;,]", images) if part.strip()]


def load_components(csv_path: Path) -> list[dict[str, str]]:
    """Return CSV rows, validating columns."""
    with csv_path.open(newline="", encoding="utf-8") as handle:
        reader = csv.DictReader(handle)
        if reader.fieldnames != list(CSV_COLUMNS):
            raise SystemExit(f"{csv_path}: unexpected columns {reader.fieldnames}")
        rows = list(reader)
    if not rows:
        raise SystemExit(f"{csv_path}: no rows")
    return rows


def rows_for_image(rows: list[dict[str, str]], label: str) -> list[dict[str, str]]:
    """Rows whose images cell includes the CSV image label."""
    return [row for row in rows if label in image_labels(row["images"])]


def apache_rows(rows: list[dict[str, str]]) -> list[dict[str, str]]:
    """Rows whose license includes Apache-2.0."""
    return [row for row in rows if "Apache-2.0" in row["license"]]


def format_catalog(rows: list[dict[str, str]]) -> str:
    """Render components grouped by license."""
    grouped: dict[str, list[str]] = defaultdict(list)
    for row in rows:
        versions = row["versions"].strip() or "(version not listed)"
        grouped[row["license"]].append(f"{row['component']} {versions}")
    blocks = []
    for license_id in sorted(grouped):
        blocks.append(f"License: {license_id}")
        blocks.extend(grouped[license_id])
        blocks.append("")
    return "\n".join(blocks)


def format_apache_lines(rows: list[dict[str, str]]) -> str:
    """One line per Apache-licensed component."""
    lines = []
    for row in apache_rows(rows):
        versions = row["versions"].strip() or "(version not listed)"
        lines.append(f"{row['component']} {versions} [{row['images']}]")
    return "\n".join(lines) + ("\n" if lines else "")


def build_notice(image_rows: list[dict[str, str]], inherited: str, include_inherited: bool) -> str:
    """Assemble NOTICE text for one image or the server binary."""
    parts = [NOTICE_PREAMBLE.rstrip(), "", SHORT_GRAVITINO.rstrip(), ""]
    if include_inherited:
        parts.extend(
            [
                "========================================================================",
                "Inherited NOTICE text of bundled Apache works",
                "========================================================================",
                inherited.rstrip(),
                "",
            ]
        )
    parts.extend(
        [
            "========================================================================",
            "Apache-licensed components in this package",
            "========================================================================",
            "",
            format_apache_lines(image_rows),
        ]
    )
    return "\n".join(parts)


def write_image_catalogs(
    dest: Path, image_rows: list[dict[str, str]], inherited: str, include_inherited: bool
) -> None:
    """Write NOTICE.txt and THIRD_PARTY_LICENSES.txt into a licenses directory."""
    dest.mkdir(parents=True, exist_ok=True)
    (dest / "NOTICE.txt").write_text(
        build_notice(image_rows, inherited, include_inherited), encoding="utf-8"
    )
    header = (
        f"Third-party components in this image ({len(image_rows)} entries).\n"
        f"{GENERATED_MARK}\n"
    )
    (dest / "THIRD_PARTY_LICENSES.txt").write_text(
        header + format_catalog(image_rows), encoding="utf-8"
    )


def prune_mcp_license_texts(licenses_dir: Path, mcp_rows: list[dict[str, str]]) -> None:
    """Drop per-package LICENSE files for packages not on the MCP scan list."""
    slugs = tuple(
        row["component"].lower().replace("_", "-") for row in mcp_rows
    )
    for path in licenses_dir.glob("*.LICENSE.txt"):
        if path.name in MCP_KEEP_ALWAYS:
            continue
        stem = path.name[: -len(".LICENSE.txt")].lower()
        if any(stem.startswith(slug) for slug in slugs):
            continue
        path.unlink()


def apply_generated_suffix(path: Path, prefix: str, catalog: str) -> None:
    """Write prefix plus generated catalog, replacing a previous generated suffix."""
    body = prefix
    mark_at = prefix.find(GENERATED_MARK)
    if mark_at != -1:
        body = prefix[:mark_at].rstrip() + "\n"
    path.write_text(body + "\n" + GENERATED_MARK + "\n" + catalog, encoding="utf-8")


def apply_catalog(
    repo_root: Path,
    csv_path: Path,
    inherited_path: Path,
    preamble_path: Path,
) -> None:
    """Regenerate binary and image license catalogs."""
    rows = load_components(csv_path)
    inherited = inherited_path.read_text(encoding="utf-8")
    preamble = preamble_path.read_text(encoding="utf-8").rstrip() + "\n"
    server_rows = rows_for_image(rows, "Server")
    (repo_root / "NOTICE.bin").write_text(
        build_notice(server_rows, inherited, include_inherited=True), encoding="utf-8"
    )
    apply_generated_suffix(
        repo_root / "LICENSE.bin",
        preamble,
        format_catalog(server_rows),
    )
    for label, rel in IMAGE_LICENSE_DIRS:
        include_inherited = label == "Server"
        write_image_catalogs(
            repo_root / rel,
            rows_for_image(rows, label),
            inherited,
            include_inherited,
        )
    mcp_rows = rows_for_image(rows, "MCP server")
    prune_mcp_license_texts(repo_root / "dev/docker/mcp-server/licenses", mcp_rows)
    gravitino_notice = SHORT_GRAVITINO.rstrip() + "\n"
    mcp_notice = repo_root / "dev/docker/mcp-server/licenses/APACHE_GRAVITINO_NOTICE.txt"
    if mcp_notice.exists():
        mcp_notice.write_text(gravitino_notice, encoding="utf-8")
    web_rows = rows_for_image(rows, "Web UI")
    web_notice = build_notice(web_rows, inherited, include_inherited=False)
    for web in ("web/web", "web-v2/web"):
        (repo_root / web / "NOTICE.bin").write_text(web_notice, encoding="utf-8")
        license_path = repo_root / web / "LICENSE.bin"
        apply_generated_suffix(
            license_path,
            license_path.read_text(encoding="utf-8"),
            format_catalog(web_rows),
        )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=REPO_ROOT)
    parser.add_argument(
        "--csv", type=Path, default=DATA_DIR / "third-party-components-1.3.0.csv"
    )
    parser.add_argument(
        "--inherited", type=Path, default=DATA_DIR / "inherited-apache-notices.txt"
    )
    parser.add_argument(
        "--preamble", type=Path, default=DATA_DIR / "LICENSE.bin.preamble"
    )
    args = parser.parse_args()
    apply_catalog(args.root, args.csv, args.inherited, args.preamble)
    print(f"Applied third-party catalog under {args.root}")


if __name__ == "__main__":
    main()
