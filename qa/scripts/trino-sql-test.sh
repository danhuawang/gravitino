#!/usr/bin/env python3
#
# Copyright 2024 Datastrato Pvt Ltd.
# This software is licensed under the Apache License version 2.
#
"""Trino SQL test runner.

Usage:
  ./trino-sql-test.sh --trino_uri=http://34.138.225.171:8080 \
      --test_sets_dir qa/testsets/trino-iceberg-rest

Options:
  --trino_uri=URL        Trino server URL (default: http://localhost:8080)
  --test_sets_dir=DIR    Directory containing *.sql / *.txt test pairs
  --tester_id=PREFIX     Only run files whose name starts with PREFIX (e.g. 00008)
  --ignore_failed        Continue on failure instead of stopping
  --gen_output           Generate / overwrite *.txt files from actual query output
  --params=k1,v1;k2,v2  Replace ${key} placeholders in SQL files
  --user=NAME            Trino user (default: admin)
  --help                 Print this help message
"""

import argparse
import base64
import csv
import io
import os
import re
import sys
import time
import urllib.request
import urllib.error
import json
from pathlib import Path

# ──────────────────────────────────────────────────────────────────────────────
# ANSI colours
# ──────────────────────────────────────────────────────────────────────────────
GREEN  = "\033[32m"
RED    = "\033[31m"
YELLOW = "\033[33m"
CYAN   = "\033[36m"
RESET  = "\033[0m"
BOLD   = "\033[1m"


def green(s):  return f"{GREEN}{s}{RESET}"
def red(s):    return f"{RED}{s}{RESET}"
def yellow(s): return f"{YELLOW}{s}{RESET}"
def cyan(s):   return f"{CYAN}{s}{RESET}"
def bold(s):   return f"{BOLD}{s}{RESET}"


# ──────────────────────────────────────────────────────────────────────────────
# Trino HTTP client
# ──────────────────────────────────────────────────────────────────────────────
class TrinoClient:
    """Minimal Trino REST client — no external dependencies required."""

    def __init__(self, trino_uri: str, user: str = "admin"):
        self.base_url = trino_uri.rstrip("/")
        self.user = user
        # Session state — updated from Trino response headers
        self._catalog: str | None = None
        self._schema: str | None = None

    def _request(self, url: str, body: bytes | None = None,
                 retries: int = 5, backoff: float = 1.0) -> dict:
        headers = {
            "X-Trino-User": self.user,
            "X-Trino-Source": "trino-sql-test",
            "Content-Type": "application/json",
        }
        # Send current session catalog/schema if set
        if self._catalog:
            headers["X-Trino-Catalog"] = self._catalog
        if self._schema:
            headers["X-Trino-Schema"] = self._schema

        method = "POST" if body is not None else "GET"
        for attempt in range(retries):
            try:
                req = urllib.request.Request(url, data=body, headers=headers, method=method)
                with urllib.request.urlopen(req, timeout=120) as resp:
                    # Track session state from response headers
                    set_catalog = resp.headers.get("X-Trino-Set-Catalog")
                    set_schema = resp.headers.get("X-Trino-Set-Schema")
                    if set_catalog:
                        self._catalog = set_catalog
                    if set_schema:
                        self._schema = set_schema
                    return json.loads(resp.read().decode())
            except (urllib.error.URLError, ConnectionResetError, OSError) as e:
                if attempt < retries - 1:
                    wait = backoff * (2 ** attempt)
                    time.sleep(wait)
                else:
                    raise

    def run_query(self, sql: str) -> str:
        """Execute *sql* and return a formatted result string."""
        url = f"{self.base_url}/v1/statement"
        body = sql.strip().encode()
        data = self._request(url, body)

        columns = []
        col_types = []
        rows = []
        error = None
        update_type = None
        update_count = None

        while True:
            if "error" in data:
                error = data["error"].get("message", str(data["error"]))
                break

            if "columns" in data and not columns:
                columns = [c["name"] for c in data["columns"]]
                col_types = [c.get("type", "") for c in data["columns"]]

            if "data" in data:
                rows.extend(data["data"])

            # Trino signals DML via updateType + stats.processedRows
            if "updateType" in data and update_type is None:
                update_type = data["updateType"]
            if "stats" in data:
                pr = data["stats"].get("processedRows")
                if pr is not None:
                    update_count = pr

            next_uri = data.get("nextUri")
            if not next_uri:
                break

            time.sleep(0.05)
            data = self._request(next_uri)

        if error:
            return f"<QUERY_FAILED> {error}"

        return self._format_result(sql, columns, col_types, rows,
                                   update_type, update_count)

    @staticmethod
    def _format_result(sql: str, columns: list, col_types: list, rows: list,
                       update_type: str | None, update_count: int | None) -> str:
        """Produce output matching the Trino CLI format.

        Trino always sets updateType for every DDL/DML operation, and the value
        matches exactly what the CLI prints (RENAME TABLE, ADD COLUMN, INSERT, …).

        Special case: INSERT/UPDATE/DELETE/MERGE return a single column named
        'rows' whose value is the affected row count, rather than using
        stats.processedRows. We detect this pattern and format it correctly.
        """
        DML_TYPES = {"INSERT", "UPDATE", "DELETE", "MERGE"}

        # ── DML special response: single 'rows' column with the count ─────────
        # INSERT/UPDATE/DELETE/MERGE return columns=[{'name':'rows'}], data=[[n]]
        if (update_type is not None
                and update_type.upper() in DML_TYPES
                and len(columns) == 1
                and columns[0].lower() == "rows"
                and rows):
            n = int(rows[0][0]) if rows[0] else 0
            unit = "row" if n == 1 else "rows"
            return f"{update_type}: {n} {unit}"

        # ── DDL / DML with updateType and no result columns ───────────────────
        if update_type is not None and not columns:
            if update_type.upper() in DML_TYPES:
                n = int(update_count) if update_count is not None else 0
                unit = "row" if n == 1 else "rows"
                return f"{update_type}: {n} {unit}"
            # DDL — return label only (matches Trino CLI)
            return update_type

        # ── No updateType and no columns: unknown DDL fallback ────────────────
        if not columns:
            return ""

        # ── SELECT / SHOW: format as CSV rows ─────────────────────────────────
        # Identify VARBINARY columns — Trino REST API returns them as base64,
        # but the CLI displays hex. Convert to match CLI output.
        varbinary_indices = {i for i, t in enumerate(col_types)
                            if t.lower() == "varbinary"}

        buf = io.StringIO()
        writer = csv.writer(buf, quoting=csv.QUOTE_ALL, lineterminator="\n")
        for row in rows:
            formatted = []
            for i, v in enumerate(row):
                if v is None:
                    formatted.append("")
                elif i in varbinary_indices:
                    # Decode base64 → raw bytes → hex string (matches Trino CLI)
                    formatted.append(base64.b64decode(v).hex().upper()
                                     if v else "")
                elif isinstance(v, bool):
                    formatted.append(str(v).lower())
                elif isinstance(v, (list, dict)):
                    # Complex types (ARRAY, MAP, ROW): serialize to match
                    # Trino CLI format — Python None → NULL
                    formatted.append(TrinoClient._format_complex_value(v))
                else:
                    formatted.append(str(v))
            writer.writerow(formatted)
        return buf.getvalue().rstrip("\n")

    @staticmethod
    def _format_complex_value(v):
        """Format a single value within a complex type to match Trino CLI."""
        if v is None:
            return "NULL"
        elif isinstance(v, bool):
            return str(v).lower()
        elif isinstance(v, list):
            inner = ", ".join(TrinoClient._format_complex_value(x) for x in v)
            return f"[{inner}]"
        elif isinstance(v, dict):
            # MAP — sort by key for deterministic output since MAP is unordered
            sorted_items = sorted(v.items(), key=lambda kv: str(kv[0]))
            entries = ", ".join(
                f"{TrinoClient._format_complex_value(k)}={TrinoClient._format_complex_value(val)}"
                for k, val in sorted_items
            )
            return f"{{{entries}}}"
        else:
            return str(v)


# ──────────────────────────────────────────────────────────────────────────────
# SQL file parsing
# ──────────────────────────────────────────────────────────────────────────────
_SQL_COMMENT_RE = re.compile(r"--[^\n]*", re.MULTILINE)
_SQL_STMT_RE    = re.compile(r"([<\w].*?);", re.DOTALL)


def parse_sql_file(path: str, params: dict) -> list[str]:
    text = Path(path).read_text(encoding="utf-8")
    text = _SQL_COMMENT_RE.sub("", text)
    for k, v in params.items():
        text = text.replace(f"${{{k}}}", v)
    stmts = [m.group(1).strip() for m in _SQL_STMT_RE.finditer(text)]
    return [s for s in stmts if s]


# ──────────────────────────────────────────────────────────────────────────────
# Expected-output file parsing
# ──────────────────────────────────────────────────────────────────────────────
_BLOCK_RE = re.compile(
    r'((".*?")\n{2,})|((\S.*?)\n{2,})',
    re.DOTALL,
)


def parse_txt_file(path: str, params: dict = None) -> list[str]:
    text = Path(path).read_text(encoding="utf-8") + "\n\n"
    # Apply the same ${key} substitution as SQL files
    if params:
        for k, v in params.items():
            text = text.replace(f"${{{k}}}", v)
    blocks = []
    for m in _BLOCK_RE.finditer(text):
        if m.group(2) is not None:
            blocks.append(m.group(2).strip())
        else:
            blocks.append(m.group(4).strip())
    return blocks


# ──────────────────────────────────────────────────────────────────────────────
# Output normalisation
# ──────────────────────────────────────────────────────────────────────────────
# Properties that are environment-specific or implementation-defined and should
# not be required to appear in the expected output.  When the expected pattern
# does NOT mention a property, we strip it from the actual output so that its
# presence (or absence) does not affect the comparison.
_OPTIONAL_WITH_PROPS = re.compile(
    r"^\s*compression_codec\s*=\s*'[^']*',?\s*$",
    re.IGNORECASE | re.MULTILINE,
)


def normalize_actual(expected: str, actual: str) -> str:
    """Remove optional WITH-clause properties from *actual* when the expected
    pattern does not reference them.  This lets tests pass regardless of
    whether the server returns e.g. ``compression_codec = 'ZSTD'``."""
    # Only bother when the actual output looks like a CREATE TABLE statement.
    if "WITH (" not in actual:
        return actual

    # Strip each optional property whose key does not appear in the expected
    # pattern (case-insensitive check on the key name only).
    for prop_re in [_OPTIONAL_WITH_PROPS]:
        # Extract the property key from the pattern source for the check.
        key_match = re.search(r"\\s\*(\w+)\\s\*=", prop_re.pattern)
        key = key_match.group(1) if key_match else None
        if key and key.lower() not in expected.lower():
            actual = prop_re.sub("", actual)

    # Clean up any trailing comma left on the preceding property line after
    # removal, e.g. "   format = 'PARQUET',\n\n   format_version" should stay
    # valid — but more importantly remove blank lines introduced by the strip.
    actual = re.sub(r"\n{2,}", "\n", actual)
    return actual


# ──────────────────────────────────────────────────────────────────────────────
# Wildcard matching  (mirrors TrinoQueryITBase.match)
# ──────────────────────────────────────────────────────────────────────────────
def match_result(expected: str, actual: str) -> bool:
    if not expected:
        return True
    actual = normalize_actual(expected, actual)
    exp_flat = expected.replace("\n", "")
    act_flat = actual.replace("\n", "")
    parts = exp_flat.split("%")
    # Use greedy .* so multi-segment wildcards span long content correctly
    regex = "^" + ".*".join(re.escape(p) for p in parts) + "$"
    return bool(re.match(regex, act_flat, re.DOTALL))


# ──────────────────────────────────────────────────────────────────────────────
# Output generation
# ──────────────────────────────────────────────────────────────────────────────
def generate_txt(sql_file: str, client: TrinoClient, params: dict) -> None:
    stmts = parse_sql_file(sql_file, params)
    txt_file = sql_file.replace(".sql", ".txt")
    lines = []
    for stmt in stmts:
        result = client.run_query(stmt).strip()
        lines.append(result)
        lines.append("")
    Path(txt_file).write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"  {cyan('Generated')} {txt_file}")


# ──────────────────────────────────────────────────────────────────────────────
# Lifecycle: prepare / cleanup
# ──────────────────────────────────────────────────────────────────────────────
def run_lifecycle_file(label: str, sql_file: str, client: TrinoClient,
                       params: dict) -> bool:
    """Execute all statements in a lifecycle SQL file (prepare.sql / cleanup.sql).

    Returns True if all statements succeeded, False otherwise.
    Failures are reported but never abort the process.
    """
    if not os.path.exists(sql_file):
        return True

    stmts = parse_sql_file(sql_file, params)
    if not stmts:
        return True

    print(f"\n{bold(cyan(f'--- {label}: {os.path.basename(sql_file)} ---'))}")
    ok = True
    for i, stmt in enumerate(stmts):
        short_stmt = stmt[:80].replace("\n", " ")
        try:
            result = client.run_query(stmt).strip()
        except Exception as e:
            result = f"<RUNNER_ERROR> {e}"

        if result.startswith("<QUERY_FAILED>") or result.startswith("<RUNNER_ERROR>"):
            ok = False
            print(f"  {red('FAIL')} [{i+1}/{len(stmts)}] {short_stmt}")
            print(f"    {result[:200]}")
        else:
            print(f"  {green('OK')}   [{i+1}/{len(stmts)}] {short_stmt}")

    return ok


# ──────────────────────────────────────────────────────────────────────────────
# Single-file test runner
# ──────────────────────────────────────────────────────────────────────────────
def run_test_file(sql_file: str, client: TrinoClient, params: dict,
                  ignore_failed: bool) -> tuple[int, int]:
    txt_file = sql_file.replace(".sql", ".txt")
    if not os.path.exists(txt_file):
        print(f"  {yellow('SKIP')} no .txt file: {txt_file}")
        return 0, 0

    stmts    = parse_sql_file(sql_file, params)
    expected = parse_txt_file(txt_file, params)

    passed = 0
    failed = 0

    for i, stmt in enumerate(stmts):
        exp = expected[i] if i < len(expected) else ""
        try:
            actual = client.run_query(stmt).strip()
        except Exception as e:
            actual = f"<RUNNER_ERROR> {e}"

        ok = match_result(exp, actual)
        short_stmt = stmt[:80].replace("\n", " ")
        if ok:
            passed += 1
            print(f"    {green('PASS')} [{i+1}/{len(stmts)}] {short_stmt}")
        else:
            failed += 1
            print(f"    {red('FAIL')} [{i+1}/{len(stmts)}] {short_stmt}")
            print(f"      {bold('Expected:')} {exp[:300]}")
            print(f"      {bold('Actual  :')} {actual[:300]}")
            if not ignore_failed:
                print(red(f"\nAborting: first failure in {os.path.basename(sql_file)}"))
                sys.exit(1)

    return passed, failed


# ──────────────────────────────────────────────────────────────────────────────
# Directory runner
# ──────────────────────────────────────────────────────────────────────────────
def run_test_dir(test_sets_dir: str, client: TrinoClient, params: dict,
                 tester_id: str, ignore_failed: bool,
                 gen_output: bool) -> tuple[int, int]:
    test_dir = Path(test_sets_dir)

    # Lifecycle files
    prepare_file = str(test_dir / "prepare.sql")
    cleanup_file = str(test_dir / "cleanup.sql")

    # Exclude lifecycle and catalog/iceberg_rest prefixed files from test set
    lifecycle_names = {"prepare.sql", "cleanup.sql"}
    sql_files = sorted(
        f for f in test_dir.iterdir()
        if f.suffix == ".sql"
        and f.name not in lifecycle_names
        and not f.name.startswith("catalog_")
        and not f.name.startswith("iceberg_rest")
        and (not tester_id or f.name.startswith(tester_id))
    )

    if not sql_files:
        print(yellow(f"No .sql test files found in {test_sets_dir}"))
        return 0, 0

    # ── Prepare phase ─────────────────────────────────────────────────────────
    if not gen_output:
        prepare_ok = run_lifecycle_file("PREPARE", prepare_file, client, params)
        if not prepare_ok and not ignore_failed:
            print(red("\nAborting: prepare phase failed"))
            # Still attempt cleanup before exiting
            run_lifecycle_file("CLEANUP", cleanup_file, client, params)
            sys.exit(1)

    # ── Test phase ────────────────────────────────────────────────────────────
    total_pass = total_fail = 0
    try:
        for sql_file in sql_files:
            print(f"\n{bold(cyan(f'=== {sql_file.name} ==='))}")
            if gen_output:
                generate_txt(str(sql_file), client, params)
            else:
                p, f = run_test_file(str(sql_file), client, params, ignore_failed)
                total_pass += p
                total_fail += f
                status = green("PASS") if f == 0 else red("FAIL")
                print(f"  {status}  pass={p}  fail={f}")
    finally:
        # ── Cleanup phase (always runs) ───────────────────────────────────────
        if not gen_output:
            run_lifecycle_file("CLEANUP", cleanup_file, client, params)

    return total_pass, total_fail


# ──────────────────────────────────────────────────────────────────────────────
# Argument parsing
# ──────────────────────────────────────────────────────────────────────────────
def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(
        description="Trino SQL test runner",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    p.add_argument("--trino_uri",      default="http://localhost:8080")
    p.add_argument("--test_sets_dir",  required=True)
    p.add_argument("--tester_id",      default="")
    p.add_argument("--ignore_failed",  action="store_true")
    p.add_argument("--gen_output",     action="store_true")
    p.add_argument("--params",         default="")
    p.add_argument("--user",           default="admin")
    return p.parse_args()


# ──────────────────────────────────────────────────────────────────────────────
# Entry point
# ──────────────────────────────────────────────────────────────────────────────
def main() -> None:
    args = parse_args()

    params: dict[str, str] = {}
    if args.params:
        for pair in args.params.split(";"):
            parts = pair.split(",", 1)
            if len(parts) == 2:
                params[parts[0].strip()] = parts[1].strip()

    test_sets_dir = os.path.abspath(args.test_sets_dir)
    if not os.path.isdir(test_sets_dir):
        print(red(f"ERROR: test_sets_dir '{test_sets_dir}' does not exist"))
        sys.exit(1)

    print(bold(f"\nTrino SQL Test Runner"))
    print(f"  Trino URI    : {args.trino_uri}")
    print(f"  Test sets dir: {test_sets_dir}")
    if args.tester_id:
        print(f"  Filter       : {args.tester_id}*")
    if args.gen_output:
        print(yellow("  Mode         : GENERATE OUTPUT"))
    print()

    client = TrinoClient(args.trino_uri, user=args.user)

    try:
        info = client._request(f"{args.trino_uri}/v1/info")
        print(f"  Trino version: {info.get('nodeVersion', {}).get('version', 'unknown')}\n")
    except Exception as e:
        print(red(f"ERROR: Cannot connect to Trino at {args.trino_uri}: {e}"))
        sys.exit(1)

    start = time.time()
    total_pass, total_fail = run_test_dir(
        test_sets_dir, client, params,
        tester_id=args.tester_id,
        ignore_failed=args.ignore_failed,
        gen_output=args.gen_output,
    )
    elapsed = time.time() - start

    if not args.gen_output:
        total = total_pass + total_fail
        bar = green("█" * total_pass) + red("█" * total_fail)
        print(f"\n{'─'*60}")
        print(bold(f"Results: {total} queries  |  "
                   f"{green(str(total_pass))} passed  |  "
                   f"{red(str(total_fail))} failed  |  "
                   f"{elapsed:.1f}s"))
        print(f"[{bar}]")
        if total_fail > 0:
            sys.exit(1)


if __name__ == "__main__":
    main()
