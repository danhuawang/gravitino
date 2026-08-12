---
title: "Oracle catalog"
slug: /jdbc-oracle-catalog
keywords:
- jdbc
- Oracle
- metadata
license: "This software is licensed under the Apache License version 2."
---

import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

## Introduction

Apache Gravitino provides the ability to manage Oracle metadata.

:::caution
Gravitino saves some system information in table and column comments, like `(From Gravitino, DO NOT EDIT: gravitino.v1.uid1078334182909406185)`. Please don't change or remove this message.
:::

:::caution
The Oracle catalog targets **Oracle 11g Release 2 (11.2.x)** and is also tested with Oracle Database Free 23c. Because Oracle 11g has no Identity Columns, the catalog does **not** support auto-increment.
:::

## Case sensitivity

The Oracle catalog follows Oracle's own native case-folding rule, applied per identifier: an **unquoted** identifier folds to uppercase and is compared case-insensitively; a **quoted** identifier (e.g. `"MyTable"`) preserves its exact case and is compared case-sensitively. You express this by including literal double-quote characters in the schema/table/column name string when you specify it (create, rename, or look up an object) — no separate API parameter is needed.

- **Unquoted**: `app_user`, `APP_USER`, and `App_User` all resolve to the same Gravitino schema `APP_USER`, backed by the Oracle user `APP_USER`. This makes Oracle objects reachable via unquoted SQL from any client, even ones that never go through Gravitino.
- **Quoted**: passing the literal string `"MyTable"` (including the two `"` characters) as the table name creates a case-sensitive Oracle object whose physical name is exactly `MyTable`. The quoting is evaluated once, at the point you specify the name — Gravitino never stores or returns a name containing literal quote characters — so you must re-supply the quoted form (e.g. `NameIdentifier.of(schema, "\"MyTable\"")`) every time you reference this object again (load, alter, drop, rename). A quoted name lets you use characters, spaces, or reserved words that would otherwise be illegal or ambiguous unquoted (e.g. `"comment"` as a table name).

:::info
The name Gravitino reports back (from `createTable`, `loadTable`, `listTables`, etc.) always matches Oracle's physical name exactly, letter case included, and never contains a literal quote character — whether the object was created quoted or not.
:::

:::caution
Breaking changes from earlier versions of this catalog:
- Unquoted logical names are now uppercase (e.g. `APP_USER`), not lowercase (`app_user`) as in previous releases.
- Quoted mixed-case tables — created outside Gravitino, by older Gravitino versions, or now directly through this catalog — are visible again via `listTables`/`loadTable` (reported using their exact physical case), instead of being skipped with a warning. Referencing one of these tables again for `alterTable`/`dropTable`/etc. still requires supplying the quoted form.
:::

Index names are the one exception: Gravitino does not fold them and does not apply the quoted/unquoted convention to them, so an index/constraint name is always quoted case-preserving, exactly as given, and must be referenced with that same exact case thereafter (e.g. an index created as `PK_USERS` cannot be dropped by referencing `pk_users`).

:::note
Tag and role/privilege APIs resolve names from a caller-supplied `MetadataObject` string, which is not folded the same way `createTable`/`loadTable` names are. Always reference a table/schema by the exact same casing it resolved to (e.g. the name returned by `loadTable`), rather than relying on case-insensitive matching, when calling those APIs.
:::

See [Identifier naming rules](#identifier-naming-rules) for the separate character/length restrictions the catalog enforces on every name.

## Catalog

### Catalog capabilities

- Gravitino catalog corresponds to one Oracle database instance (identified by SID or service name).
- Supports loading and listing Oracle schemas (users). Creating or dropping a schema is **not** supported in the current version.
- Supports DDL operations for Oracle tables.
- Supports table index (`PRIMARY_KEY` and `UNIQUE_KEY`).
- Supports column default value.
- Does **not** support auto-increment (Oracle 11g has no Identity Columns).
- Supports Oracle single-level partitioning (`RANGE`, `LIST`, `HASH`).

### Catalog properties

Any property that isn't defined by Gravitino can be passed to the Oracle data source by adding the `gravitino.bypass.` prefix as a catalog property. For example, the catalog property `gravitino.bypass.maxWaitMillis` will pass `maxWaitMillis` to the data source property.
You can check the relevant data source configuration in [data source properties](https://commons.apache.org/proper/commons-dbcp/configuration.html).

If you use a JDBC catalog, you must provide `jdbc-url`, `jdbc-driver`, `jdbc-user` and `jdbc-password` in the catalog properties.
Besides the [common catalog properties](../docs/gravitino-server-config.md#apache-gravitino-catalog-properties-configuration), the Oracle catalog has the following properties:

| Configuration item      | Description                                                                                                                                                      | Default value | Required | Since Version |
|-------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------|----------|---------------|
| `jdbc-url`              | JDBC URL for connecting to Oracle. For example `jdbc:oracle:thin:@host:1521:SID` or `jdbc:oracle:thin:@//host:1521/service`.                                     | (none)        | Yes      | 1.3.0         |
| `jdbc-driver`           | The driver of the JDBC connection. For example `oracle.jdbc.OracleDriver` or `oracle.jdbc.driver.OracleDriver`.                                                  | (none)        | Yes      | 1.3.0         |
| `jdbc-user`             | The JDBC user name. The user needs `SELECT` privileges on `ALL_USERS`, `ALL_TABLES`, `ALL_TAB_COLUMNS`, `ALL_CONSTRAINTS` and `ALL_CONS_COLUMNS` at minimum.     | (none)        | Yes      | 1.3.0         |
| `jdbc-password`         | The JDBC password.                                                                                                                                               | (none)        | Yes      | 1.3.0         |
| `jdbc.pool.min-size`    | The minimum number of connections in the pool.                                                                                                                   | `2`           | No       | 1.3.0         |
| `jdbc.pool.max-size`    | The maximum number of connections in the pool.                                                                                                                   | `10`          | No       | 1.3.0         |
| `jdbc.pool.max-wait-ms` | The maximum duration that the pool will wait for a connection to be returned.                                                                                    | `30000`       | No       | 1.3.0         |

:::info
The distribution includes one Java 17-compatible `ojdbc11` driver in `catalogs/jdbc-oracle/libs`. Do not add a second Oracle JDBC driver to that directory because class loading would become order-dependent.
:::

:::info
In Oracle, **a schema is a user**. One Gravitino schema corresponds to one Oracle user. See the [Schema](#schema) section below.
:::

### Catalog operations

Please refer to [Manage Catalogs and Schemas](../docs/manage-catalogs-and-schemas.md#catalog-operations) for more details.

## Schema

### Schema capabilities

- A Gravitino schema corresponds to one Oracle user / schema.
- Supports loading and listing schemas.
- **Does not support** creating a schema. Oracle's `CREATE USER` requires a password and extra privileges, so the current version does not expose this operation. Please create the user in Oracle first, then use it from Gravitino.
- **Does not support** dropping a schema.
- **Does not support** schema comment. Oracle has no `COMMENT ON SCHEMA` syntax, so the comment is always empty.
- System schemas (such as `SYS`, `SYSTEM`, `CTXSYS`, `XDB`, etc.) are filtered out from the list results automatically.


### Schema properties

- Doesn't support any schema property settings.

### Schema operations

Please refer to [Manage Catalogs and Schemas](../docs/manage-catalogs-and-schemas.md#schema-operations) for more details.

## Table

### Table capabilities

- A Gravitino table corresponds to one Oracle table.
- Supports creating, loading, listing, and dropping Oracle tables.
- Supports `PRIMARY_KEY` and `UNIQUE_KEY` indexes.
- Supports column default value, including common Oracle functions like `SYSDATE`, `SYSTIMESTAMP`, `CURRENT_TIMESTAMP`, `CURRENT_DATE`, `SYS_GUID()`.
- Supports Oracle partitioning: single-level `RANGE`, `LIST`, `HASH` (composite partitions are not supported).
- **Does not support** auto-increment (Oracle 11g has no Identity Columns).
- **Does not support** `UpdateColumnPosition` (Oracle cannot reorder columns).

See [Case sensitivity](#case-sensitivity) for how table/column names are folded, and how quoted (mixed-case) table names are created and reached.

### Table column types

| Gravitino Type     | Oracle Type              |
|--------------------|--------------------------|
| `Byte`             | `NUMBER(3)`              |
| `Short`            | `NUMBER(5)`              |
| `Integer`          | `NUMBER(10)`             |
| `Long`             | `NUMBER(19)`             |
| `Decimal(p,s)`     | `NUMBER(p,s)`            |
| `Float`            | `BINARY_FLOAT`           |
| `Double`           | `BINARY_DOUBLE`          |
| `Boolean`          | `BOOLEAN` on Oracle 23+, otherwise marked `NUMBER(1)` |
| `String`           | `CLOB`                   |
| `VarChar(n)`       | `VARCHAR2(n)`            |
| `FixedChar(n)`     | `CHAR(n)`                |
| `Timestamp(p)`     | `TIMESTAMP(p)`           |
| `Timestamp_tz(p)`  | `TIMESTAMP(p) WITH TIME ZONE` |
| `Binary`           | `BLOB`                   |

When loading an Oracle table, Gravitino converts Oracle types back as follows:

| Oracle Type                 | Gravitino Type                                                                                      |
|-----------------------------|-----------------------------------------------------------------------------------------------------|
| `NUMBER(p)` (scale = 0)     | `Byte` (p ≤ 3), `Short` (p ≤ 5), `Integer` (p ≤ 10), `Long` (p ≤ 19), otherwise `Decimal(p,0)`      |
| `NUMBER(p,s)` (scale > 0)   | `Decimal(p,s)`                                                                                      |
| `NUMBER(p,s)` (scale < 0)   | `ExternalType("NUMBER(p,s)")`                                                                       |
| `NUMBER` (no p, no s)       | `Decimal(38,0)`; Oracle JDBC reports this as the sentinel precision `0`, scale `-127`                |
| `BOOLEAN`                   | `Boolean`                                                                                             |
| `VARCHAR2(n)` / `VARCHAR(n)`| `VarChar(n)` (or `String` when length is unknown)                                                   |
| `CHAR(n)`                   | `FixedChar(n)`                                                                                      |
| `CLOB` / `NCLOB`            | `String`                                                                                            |
| `BLOB` / `RAW` / `LONG RAW` | `Binary`                                                                                            |
| `BINARY_FLOAT`              | `Float`                                                                                             |
| `BINARY_DOUBLE` / `FLOAT`   | `Double`                                                                                            |
| `DATE`                      | `Timestamp` (Oracle's `DATE` includes time to seconds, so it maps to `Timestamp`, not `Date`)       |
| `TIMESTAMP(p)`              | `Timestamp(p)`                                                                                      |
| `NCHAR` / `NVARCHAR2`       | `ExternalType("NCHAR(...)")` / `ExternalType("NVARCHAR2(...)")`                                     |
| Other Oracle types          | `ExternalType(<original type name>)`                                                                |

:::info
Oracle doesn't support Gravitino `Date`, `Time`, `Fixed`, `Struct`, `List`, `Map`, `IntervalDay`, `IntervalYear`, `Union`, `UUID` types.
Types not listed above are mapped to Gravitino **External Type** to keep the original Oracle type name.
Oracle `FLOAT` is a subtype of `NUMBER`, not the same as Oracle `BINARY_DOUBLE`. The Oracle catalog maps it to Gravitino `Double` for compatibility, but the Oracle and Gravitino types are not strictly equivalent.

A source `NUMBER(1)` is an integer and maps to `Byte`; it is never inferred to be boolean. On Oracle releases before 23, Gravitino records boolean intent in internal column-comment metadata when it emulates `Boolean` with `NUMBER(1)`, and removes that marker from the comment returned through the API.
:::

:::caution
Gravitino `Date` cannot be converted to Oracle because Oracle's `DATE` has a time part. If you need the Oracle `DATE` type, use `ExternalType("DATE")` instead.
:::

### Table column default values

The Oracle catalog recognizes these built-in functions when reading or writing default values:

| Oracle function     | Gravitino representation                    |
|---------------------|---------------------------------------------|
| `SYSDATE`           | `FunctionExpression("SYSDATE")`             |
| `SYSTIMESTAMP`      | `FunctionExpression("SYSTIMESTAMP")`        |
| `CURRENT_TIMESTAMP` | `FunctionExpression("CURRENT_TIMESTAMP")`   |
| `CURRENT_DATE`      | `FunctionExpression("CURRENT_DATE")`        |
| `SYS_GUID()`        | `FunctionExpression("SYS_GUID")`            |

String, number, and `NULL` literals are also supported. Any other default value is kept as an `UnparsedExpression` so it round-trips unchanged.

:::info
Oracle requires `DEFAULT` to come before `NOT NULL` in a column definition. The generated DDL follows this order automatically.
:::

### Table column auto-increment

- **Not supported.** Oracle 11g has no Identity Column. If you set `autoIncrement=true` on a column, Gravitino throws `UnsupportedOperationException`.

### Table properties

The Oracle catalog exposes a few Oracle-specific metadata fields as **read-only** table properties. They are populated from `ALL_TABLES` when you load a table, but you cannot set or change them through Gravitino.

:::note
**Immutable**: Fields that cannot be modified once set.
:::

| Property Name  | Description                                                                          | Default Value | Required | Immutable | Since version |
|----------------|--------------------------------------------------------------------------------------|---------------|----------|-----------|---------------|
| `tablespace`   | Oracle tablespace, read from `ALL_TABLES.TABLESPACE_NAME`.                           | (none)        | No       | Yes       | 1.3.0         |
| `partitioned`  | Whether the table is partitioned, read from `ALL_TABLES.PARTITIONED` (`YES` / `NO`). | (none)        | No       | Yes       | 1.3.0         |
| `row_movement` | Row movement status, read from `ALL_TABLES.ROW_MOVEMENT`.                            | (none)        | No       | Yes       | 1.3.0         |
| `compression`  | Compression status, read from `ALL_TABLES.COMPRESSION`.                              | (none)        | No       | Yes       | 1.3.0         |

:::caution
Only `tablespace` can be specified at `CREATE TABLE` time (it becomes the `TABLESPACE <name>` clause). The other three are informational: they show the current Oracle state, but you cannot change them through `SetProperty` / `RemoveProperty` — those operations are rejected.
:::

### Table indexes

- Supports `PRIMARY_KEY` and `UNIQUE_KEY`.
- `UNIQUE_KEY` may be declared with or without an explicit name when creating a table. If the name is omitted, Oracle creates an unnamed `UNIQUE (...)` constraint.
- Each index field must be a single column. Composite field expressions (function-based indexes) are not supported.

<Tabs groupId='language' queryString>
<TabItem value="json" label="Json">

```json
{
  "indexes": [
    {
      "indexType": "primary_key",
      "name": "PK_USERS",
      "fieldNames": [["ID"]]
    },
    {
      "indexType": "unique_key",
      "name": "UK_USERS_EMAIL",
      "fieldNames": [["EMAIL"]]
    }
  ]
}
```

</TabItem>
<TabItem value="java" label="Java">

```java
Index[] indexes = new Index[] {
    Indexes.of(IndexType.PRIMARY_KEY, "PK_USERS", new String[][]{{"ID"}}, Map.of()),
    Indexes.of(IndexType.UNIQUE_KEY, "UK_USERS_EMAIL", new String[][]{{"EMAIL"}}, Map.of()),
};
```

</TabItem>
</Tabs>

### Table partitioning

The Oracle catalog supports creating a partitioned table. Only **single-level** partitioning is supported; composite partitions (Range-Hash, Range-List, etc.) are not supported in the current version.

Supported partition strategies:

- `RANGE` — maps to Oracle `PARTITION BY RANGE (col)`. Single column only.
- `LIST` — maps to Oracle `PARTITION BY LIST (cols)`.
- `HASH` (`Transforms.bucket`) — maps to Oracle `PARTITION BY HASH (cols) PARTITIONS n`. The bucket count becomes the Oracle partition count.

When you load a partitioned table, Gravitino reads `ALL_PART_TABLES.PARTITIONING_TYPE` and `ALL_PART_KEY_COLUMNS` and reconstructs the matching transform.

:::caution
- Only one top-level partitioning transform is allowed.
- For `RANGE`, only a single partition column is supported.
- `Transforms.range` / `Transforms.list` partition assignments are translated to `PARTITION <name> VALUES LESS THAN (...)` / `PARTITION <name> VALUES (...)`; `NULL` upper bound is emitted as `MAXVALUE`.
:::

### Table operations

Please refer to [Manage Relational Metadata Using Gravitino](../docs/manage-relational-metadata-using-gravitino.md#table-operations) for more details.

#### Alter table operations

Gravitino supports these table alteration operations on the Oracle catalog:

- `RenameTable`
- `UpdateComment`
- `AddColumn`
- `DeleteColumn`
- `RenameColumn`
- `UpdateColumnType`
- `UpdateColumnNullability`
- `UpdateColumnComment`
- `UpdateColumnDefaultValue`
- `AddIndex`
- `DeleteIndex`

The following operations are **not** supported and will throw `UnsupportedOperationException`:

- `UpdateColumnPosition` — Oracle cannot reorder columns.
- `UpdateColumnAutoIncrement` — Oracle 11g has no Identity Columns.
- `SetProperty` / `RemoveProperty` — Oracle table properties exposed by this catalog are read-only.

:::caution
- Updating a nullable column to non-nullable may fail if the column contains `NULL` values.
- Changing a column type is translated to `ALTER TABLE ... MODIFY (col newType)`, which follows Oracle's own compatibility rules. Incompatible type changes will be rejected by Oracle.
:::

### Identifier naming rules

Oracle 11g has strict identifier rules, and the catalog enforces them on every schema, table, column, and index name:

- Must start with a letter (A-Z, a-z).
- May contain letters, digits, underscores (`_`), dollar signs (`$`), and hash signs (`#`).
- Maximum length is **30 characters** (Oracle 11g hard limit).

Regex: `^[A-Za-z][A-Za-z0-9_$#]{0,29}$`

Names that do not match this pattern are rejected up front, before any SQL is sent to Oracle.
