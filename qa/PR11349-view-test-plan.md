<!--
  Licensed to the Apache Software Foundation (ASF) under one
  or more contributor license agreements.  See the NOTICE file
  distributed with this work for additional information
  regarding copyright ownership.  The ASF licenses this file
  to you under the Apache License, Version 2.0 (the
  "License"); you may not use this file except in compliance
  with the License.  You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing,
  software distributed under the License is distributed on an
  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
  KIND, either express or implied.  See the License for the
  specific language governing permissions and limitations
  under the License.
-->

# Test Plan: PR #11349 — Flink Connector View Support for Iceberg and Paimon

## Overview

PR [#11349](https://github.com/apache/gravitino/pull/11349) extends the Flink connector so that
`CREATE / DROP / ALTER / SHOW VIEW` work for Iceberg (Hive + REST) and Paimon (Hive + JDBC)
catalogs, not just Hive. Key changes under test:

1. **Bug fix**: `GravitinoPaimonCatalog.dropTable` now uses `purgeTable()` and the `DROP VIEW`
   path no longer throws `TableNotExistException`.
2. **View SQL dialect resolution**: `BaseCatalog.toFlinkView` resolves the view body using a
   `FLINK -> HIVE` fallback order.
3. **Table/View probing**: `getTable`, `tableExists`, `renameTable`, and `dropTable` all fall
   back from the table catalog to the view catalog.
4. **Schema cleanup**: `doWithSchema` now accepts a `cascade` flag so views inside a schema can
   be cleaned up across backends (Iceberg does not support `CASCADE`).

Test backends in scope:

| Backend | Class |
| --- | --- |
| Hive | `FlinkHiveCatalogIT` |
| Iceberg Hive | `FlinkIcebergHiveCatalogIT` |
| Iceberg REST | `FlinkIcebergRestCatalogIT` |
| Paimon Hive | `FlinkPaimonHiveCatalogIT` |
| Paimon JDBC | `FlinkPaimonJdbcBackendIT` |

Priority legend: **P0** = highest, bug-prone hot spots · **P1** = important · **P2** = good coverage.

---

## P0 — Bug-Prone Hot Spots (Highest Priority)

These areas have backend-specific branches, complex exception chains, or were repeatedly patched
during PR review. They are the most likely to regress.

### TC-P0-01 — Paimon `DROP VIEW` succeeds (core bug fix)

- **Why bug-prone**: `GravitinoPaimonCatalog` overrides `dropTable` to call `purgeTable()`, which
  does NOT have the table→view fallback that `BaseCatalog.dropTable` has. The original bug threw
  `TableNotExistException` on `DROP VIEW`.
- **Preconditions**: Paimon Hive and Paimon JDBC catalogs, schema with a base table and a view.
- **Steps**:
  1. `CREATE TABLE base (id INT) ...`
  2. `CREATE VIEW v AS SELECT id FROM base`
  3. `DROP VIEW v`
- **Expected**: `DROP VIEW` returns `SUCCESS`; `viewExists(v)` is `false`; no
  `TableNotExistException` is thrown.
- **Backends**: Paimon Hive, Paimon JDBC (run on both — JDBC view support was toggled multiple
  times in the PR).

### TC-P0-02 — Paimon `DROP TABLE` purges data and does not hit view path

- **Why bug-prone**: `purgeTable()` must return `true` for a real table and clean up data files;
  the view fallback must not be triggered for tables.
- **Steps**:
  1. `CREATE TABLE base (id INT) WITH (...)`
  2. `INSERT INTO base VALUES (1)`
  3. `DROP TABLE base`
- **Expected**: Table dropped, native cache invalidated, underlying data files removed; a
  subsequent `getTable(base)` throws `TableNotExistException`.
- **Backends**: Paimon Hive, Paimon JDBC.

### TC-P0-03 — `DROP TABLE`/`DROP VIEW` on a non-existent object

- **Why bug-prone**: Both `purgeTable()` and `dropView()` return `false`; the `ignoreIfNotExists`
  branch logic differs between `BaseCatalog` and the Paimon override.
- **Steps / Expected**:
  - `DROP TABLE missing` (ignoreIfNotExists=false) → `TableNotExistException`.
  - `DROP TABLE IF EXISTS missing` → `SUCCESS`, no exception.
  - `DROP VIEW IF EXISTS missing` → `SUCCESS`, no exception.
- **Backends**: All five.

### TC-P0-04 — View SQL dialect fallback (`FLINK -> HIVE`)

- **Why bug-prone**: `toFlinkView` hardcodes the `FLINK -> HIVE` lookup. Different backends tag
  the stored representation with different dialects; this was patched several times in the PR.
- **Scenarios**:
  1. View stored with `flink` dialect only → loads using flink SQL.
  2. View stored with `hive` dialect only (e.g. created via Hive) → falls back to hive SQL.
  3. View with neither `flink` nor `hive` dialect → `CatalogException` with a clear message
     naming the view, catalog, and both dialects.
- **Expected**: Correct SQL chosen per scenario; failure case throws the descriptive exception,
  not an NPE.
- **Backends**: Paimon Hive (Hive dialect path), Iceberg REST (flink dialect path).

### TC-P0-05 — `getTable` table→view fallback and exception precision

- **Why bug-prone**: `getTable` first calls `loadTable` (expects `NoSuchTableException`), then
  `loadViewOrThrow`. The catch order (`NoSuchTableException` → `ForbiddenException` →
  `CatalogException` → generic) is fragile.
- **Scenarios**:
  1. `getTable(view)` → returns a `CatalogBaseTable` with `TableKind.VIEW`.
  2. `getTable(table)` → returns `TableKind.TABLE`, view path not touched.
  3. `getTable(missing)` → `TableNotExistException`.
  4. `ForbiddenException` during probe → mapped to `TableNotExistException` (no crash).
- **Backends**: All five for 1–3; auth-enabled env for 4.

### TC-P0-06 — Schema cleanup with views present (`cascade` flag)

- **Why bug-prone**: `clearTableInSchema()` only runs `SHOW TABLES` + `DROP TABLE`; views are not
  listed by `SHOW TABLES`. Iceberg has `supportDropCascade() == false`, so leftover views can
  block `DROP DATABASE` and destabilize later tests.
- **Scenarios**:
  1. Iceberg: schema with table + view, `cascade=false` → cleanup must drop the view explicitly
     before `DROP DATABASE`; database is removed.
  2. Paimon: schema with table + view, `cascade=true` → `DROP DATABASE` removes everything.
- **Expected**: No `DatabaseNotEmptyException` leaks; schema fully removed; no cross-test pollution.
- **Backends**: Iceberg Hive/REST (cascade=false), Paimon Hive/JDBC (cascade=true).

### TC-P0-07 — `tableExists` table→view fallback

- **Why bug-prone**: Sequential probe (`tableCatalog.tableExists` → `viewCatalog.viewExists`) with
  multiple swallowed exceptions (`UnsupportedOperationException`, `NoSuchSchemaException`,
  `ForbiddenException`).
- **Scenarios**:
  1. `tableExists(view)` → `true`.
  2. `tableExists(table)` → `true`.
  3. `tableExists(missing)` → `false`.
  4. Catalog without view support → `false`, no exception.
- **Backends**: All five.

### TC-P0-08 — `ALTER VIEW ... RENAME` table↔view branch and name conflicts

- **Why bug-prone**: `renameTable` tries table rename first (`NoSuchTableException` → fall to view),
  then maps `ViewAlreadyExistsException` to `TableAlreadyExistException`.
- **Scenarios**:
  1. `ALTER VIEW v RENAME TO v2` → success; old name gone, new name present.
  2. Rename a view to the name of an existing table → `TableAlreadyExistException`.
  3. Rename a table to the name of an existing view → `TableAlreadyExistException`.
- **Backends**: Hive, Iceberg, Paimon (where ALTER VIEW RENAME is supported).

---

## P1 — Important Functional Coverage

### TC-P1-01 — `CREATE VIEW` across all backends

- Simple projection view, with `COMMENT`.
- Verify via Gravitino `ViewCatalog.loadView` (name, comment, 1 representation) and via Flink
  `getTable` (`TableKind.VIEW`).
- **Backends**: All five.

### TC-P1-02 — `CREATE VIEW IF NOT EXISTS`

- Create view, then re-issue `CREATE VIEW IF NOT EXISTS` → `SUCCESS`, view unchanged.
- **Backends**: All five.

### TC-P1-03 — `ALTER VIEW AS` (replace body)

- Replace `SELECT id` with `SELECT id, name`.
- Expected: stored representation updated; new columns reflected in schema.
- **Backends**: All five.

### TC-P1-04 — `ALTER VIEW` set / reset properties

- `SET ('k'='v')` then `RESET ('k')`.
- Expected: property added then removed; view body untouched (no full ReplaceView triggered
  unless an unrecognized change type appears).
- **Backends**: All five.

### TC-P1-05 — Query through a view

- Insert rows into base table, `SELECT * FROM view ORDER BY ...` with a `WHERE` filter.
- Expected: filtered, ordered result rows match.
- **Backends**: All five.

### TC-P1-06 — `listViews` / `listTables` isolation

- Schema contains both a table and views.
- Expected: `listViews` returns only views; `listTables` returns only tables; cross-check against
  Gravitino `ViewCatalog.listViews`.
- **Backends**: All five.

### TC-P1-07 — `DROP VIEW IF EXISTS` idempotency

- `DROP VIEW v`, then `DROP VIEW IF EXISTS v` again → second call `SUCCESS`, no exception.
- **Backends**: All five.

---

## P2 — Additional / Edge Coverage

### TC-P2-01 — Paimon native cache invalidation

- After `DROP TABLE`, immediate `getTable` → `TableNotExistException`; `tableExists` → `false`
  (no stale cache hit). Also verify `invalidateNativeTableCache` is a no-op when the underlying
  catalog is not a `FlinkCatalog`.
- **Backends**: Paimon Hive, Paimon JDBC.

### TC-P2-02 — View on view (nested view)

- `CREATE VIEW v2 AS SELECT ... FROM v1`; query `v2`.
- Expected: nested resolution works or fails with a clear error per backend capability.
- **Backends**: Hive, Iceberg.

### TC-P2-03 — Aggregation / JOIN views

- Views containing `GROUP BY`, aggregates, and `JOIN`.
- Expected: created, listed, and queried successfully.
- **Backends**: All five.

### TC-P2-04 — Cross-dialect views appear but fail to load

- A view created by another engine (e.g. Spark/Trino dialect) appears in `listViews` but
  `getTable` raises a descriptive `CatalogException` (matches the known TODO in `listViews`).
- **Backends**: Iceberg REST.

### TC-P2-05 — Boundary inputs

- Long view SQL body; view referencing a base table that is later dropped (query-time error, not
  metadata corruption).
- **Backends**: Representative single backend (Hive).

---

## Test Execution

```bash
# Unit tests
./gradlew :flink-connector:flink-common:test -PskipITs --tests "*TestBaseCatalog*"
./gradlew :flink-connector:flink-common:test -PskipITs --tests "*TestGravitinoPaimonCatalog*"

# Integration tests (require Docker)
./gradlew :flink-connector:flink-common:test -PskipTests -PskipDockerTests=false --tests "*FlinkHiveCatalogIT*"
./gradlew :flink-connector:flink-common:test -PskipTests -PskipDockerTests=false --tests "*FlinkIcebergHiveCatalogIT*"
./gradlew :flink-connector:flink-common:test -PskipTests -PskipDockerTests=false --tests "*FlinkIcebergRestCatalogIT*"
./gradlew :flink-connector:flink-common:test -PskipTests -PskipDockerTests=false --tests "*FlinkPaimon*"
```

## Top 3 Highest-Risk Areas (Summary)

1. **Paimon `DROP VIEW` path** (TC-P0-01): the `purgeTable()` override lacks the view fallback
   present in `BaseCatalog`.
2. **View SQL dialect matching** (TC-P0-04): hardcoded `FLINK -> HIVE` fallback vs. backend-specific
   dialect tags — repeatedly patched in the PR.
3. **Iceberg schema cleanup with views** (TC-P0-06): `supportDropCascade() == false` plus
   `clearTableInSchema()` ignoring views risks leftover state and flaky tests.
