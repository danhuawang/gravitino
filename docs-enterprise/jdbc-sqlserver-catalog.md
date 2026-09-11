---
title: "SQL Server catalog"
slug: /jdbc-sqlserver-catalog
keywords:
  - jdbc
  - SQL Server
  - metadata
license: "Copyright 2026 Datastrato Inc."
---

import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

## Introduction

Apache Gravitino provides the ability to manage Microsoft SQL Server metadata.

:::caution
Gravitino saves some system information in table comments, like `(From Gravitino, DO NOT EDIT: gravitino.v1.uid1078334182909406185)`, please don't change or remove this message.
:::

## Catalog

### Catalog capabilities

- Gravitino catalog corresponds to the SQL Server database.
- Supports metadata management of SQL Server (2008, 2016, 2017, 2019, 2022).
- Supports DDL operation for SQL Server schemas and tables.
- Supports table index.
- Supports column default value and auto-increment (`IDENTITY`).
- Supports table and column comments via SQL Server extended properties (`MS_Description`).

### Catalog properties

Any property that isn't defined by Gravitino can pass to SQL Server data source by adding `gravitino.bypass.` prefix as a catalog property. For example, catalog property `gravitino.bypass.maxWaitMillis` will pass `maxWaitMillis` to the data source property.
You can check the relevant data source configuration in [data source properties](https://commons.apache.org/proper/commons-dbcp/configuration.html).

If you use a JDBC catalog, you must provide `jdbc-url`, `jdbc-driver`, `jdbc-user` and `jdbc-password` to catalog properties.
Besides the [common catalog properties](./gravitino-server-config.md#apache-gravitino-catalog-properties-configuration), the SQL Server catalog has the following properties:

| Configuration item        | Description                                                                                                                                           | Default value   | Required                         | Since Version   |
| ------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------- | --------------- | -------------------------------- | --------------- |
| `jdbc-url`                | JDBC URL for connecting to the database. For example, `jdbc:sqlserver://localhost:1433;databaseName=mydb;encrypt=true;trustServerCertificate=true`.   | (none)          | Yes                              | 1.3.0           |
| `jdbc-driver`             | The driver of the JDBC connection. For example, `com.microsoft.sqlserver.jdbc.SQLServerDriver`.                                                       | (none)          | Yes                              | 1.3.0           |
| `jdbc-database`           | The database of the JDBC connection. Derived from `jdbc-url` when omitted. An explicit value must be nonblank and match the URL database.             | (none)          | Only if absent from `jdbc-url`   | 1.3.0           |
| `jdbc-user`               | The JDBC user name.                                                                                                                                   | (none)          | Yes                              | 1.3.0           |
| `jdbc-password`           | The JDBC password.                                                                                                                                    | (none)          | Yes                              | 1.3.0           |
| `jdbc.pool.min-size`      | The minimum number of connections in the pool. `2` by default.                                                                                        | `2`             | No                               | 1.3.0           |
| `jdbc.pool.max-size`      | The maximum number of connections in the pool. `10` by default.                                                                                       | `10`            | No                               | 1.3.0           |
| `jdbc.pool.max-wait-ms`   | The maximum duration (ms) that the pool will wait for a connection to be returned. `30000` by default.                                                | `30000`         | No                               | 1.3.0           |

:::info
The Microsoft JDBC Driver for SQL Server (`mssql-jdbc:12.8.1.jre11`) is MIT-licensed and is bundled with the catalog module. You do **not** need to manually download the driver.

In SQL Server, the database corresponds to the Gravitino catalog, and the schema (e.g., `dbo`) corresponds to the Gravitino schema. This is the same mapping pattern as the PostgreSQL catalog.
:::

:::caution
When `jdbc-database` is omitted, the catalog derives it from `jdbc-url`. Catalog creation fails if neither provides a nonblank database name. When both `jdbc-database` and the URL specify a database, their names must match; catalog creation rejects conflicting values.
:::

The connector uses case-insensitive names and retains Gravitino's lowercase internal identifiers. For SQL Server databases with case-insensitive identifier collation, source names returned by JDBC can be loaded using different letter casing. Listing retains the source table spelling, while loaded table names use the canonical lowercase form. Case-sensitive identifier collations are not supported by this capability.

### JDBC URL and TLS

The MSSQL JDBC Driver 10.x+ enables `encrypt=true` by default. Example JDBC URLs:

```properties
# Production (recommended)
jdbc:sqlserver://host:1433;databaseName=mydb;encrypt=true;trustServerCertificate=false

# Local development / testing only
jdbc:sqlserver://localhost:1433;databaseName=mydb;encrypt=true;trustServerCertificate=true
```

:::note
SQL Server 2008 only supports TLS 1.0, while modern MSSQL JDBC drivers (10.x+) require TLS 1.2 by default. For SQL Server 2008, use `encrypt=false` or configure TLS certificates on the server side:

```properties
jdbc:sqlserver://host:1433;databaseName=mydb;encrypt=false
```
:::

### Catalog operations

Please refer to [Manage Catalogs and Schemas](./manage-catalogs-and-schemas.md#catalog-operations) for more details.

## Schema

### Schema capabilities

- Gravitino schema corresponds to the SQL Server schema (e.g., `dbo`).
- Supports creating schema.
- Supports dropping schema.
- Schema comments are unsupported. Creating a schema with a non-empty comment throws `UnsupportedOperationException`.
- Doesn't support cascade dropping schema (SQL Server does not support `DROP SCHEMA ... CASCADE`). If the schema contains objects, the drop will fail with a native SQL Server error.

:::info
The built-in `dbo` schema is always visible in `listSchemas`. System schemas (`guest`, `INFORMATION_SCHEMA`, `sys`, `db_owner`, `db_accessadmin`, `db_securityadmin`, `db_ddladmin`, `db_backupoperator`, `db_datareader`, `db_datawriter`, `db_denydatareader`, `db_denydatawriter`) are filtered out.
:::

### Schema properties

- Doesn't support any schema property settings.

### Schema operations

Please refer to [Manage Catalogs and Schemas](./manage-catalogs-and-schemas.md#schema-operations) for more details.

## Table

### Table capabilities

- The Gravitino table corresponds to the SQL Server table.
- Supports DDL operation for SQL Server tables.
- Supports index.
- Supports column default value and auto-increment (`IDENTITY(1,1)`).
- Supports table and column comments via SQL Server extended properties (`sp_addextendedproperty` / `sp_updateextendedproperty`).
- Doesn't support table property settings.

### Table column types

The catalog preserves canonical Gravitino write mappings and normalizes compatible source types when loading existing SQL Server tables.

#### Supported one-to-one mappings

| Gravitino Type   | SQL Server Type      | Notes                                   |
| ---------------- | -------------------- | --------------------------------------- |
| `Byte`           | `tinyint`            | SQL Server tinyint is unsigned 0–255.   |
| `Short`          | `smallint`           |                                         |
| `Integer`        | `int`                |                                         |
| `Long`           | `bigint`             |                                         |
| `Boolean`        | `bit`                |                                         |
| `Decimal(p,s)`   | `decimal(p,s)`       | p ≤ 38.                                 |
| `Float`          | `real`               | 32-bit IEEE 754.                        |
| `Double`         | `float`              | 64-bit IEEE 754 (`float(53)`).          |
| `Date`           | `date`               |                                         |
| `Time(p)`        | `time(p)`            | Precision p (0–7).                      |
| `Timestamp(p)`   | `datetime2(p)`       | Without time zone. p (0–7).             |
| `FixedChar(n)`   | `char(n)`            |                                         |
| `VarChar(n)`     | `varchar(n)`         | n ≤ 8000.                               |
| `String`         | `nvarchar(max)`      | Unicode string type.                    |
| `Fixed(n)`       | `binary(n)`          |                                         |
| `Binary`         | `varbinary(max)`     |                                         |
| `UUID`           | `uniqueidentifier`   |                                         |

When loading source tables, SQL Server `numeric(p,s)` maps to `Decimal(p,s)` just like `decimal(p,s)`, including a zero scale. SQL Server `datetime` maps to `Timestamp(3)`. `char(n)` and `nchar(n)` map to `FixedChar(n)`; `varchar(n)` and `nvarchar(n)` map to `VarChar(n)`; both `varchar(max)` and `nvarchar(max)` map to `String`.

Gravitino character types do not retain source encoding or collation. The connector logs a warning once per source type (`nchar` or `nvarchar`) per converter instance. Existing creation mappings remain unchanged: `FixedChar(n)` creates `char(n)`, `VarChar(n)` creates `varchar(n)`, and `String` creates `nvarchar(max)`. Recreating columns from these logical types does not guarantee the original encoding or byte width.

Changing only column nullability uses the native SQL Server type, length, precision, scale, and collation rather than recreating the type from Gravitino metadata. String default literals use the SQL Server `N'...'` prefix to retain Unicode values while parsing the DDL; the target column's type and collation still determine which characters can be stored.

#### Types mapped to ExternalType

SQL Server types not in the table above are mapped to Gravitino **External Type** on read. Users can use `ExternalType("typename")` when creating tables through Gravitino, and the converter emits the type name as-is.

| SQL Server Type              | Gravitino Type                        |
| ---------------------------- | ------------------------------------- |
| `smalldatetime`              | `ExternalType("smalldatetime")`       |
| `datetimeoffset(p)`          | `ExternalType("datetimeoffset(p)")`   |
| `text`                       | `ExternalType("text")`                |
| `ntext`                      | `ExternalType("ntext")`               |
| `image`                      | `ExternalType("image")`               |
| `money`                      | `ExternalType("money")`               |
| `smallmoney`                 | `ExternalType("smallmoney")`          |
| `xml`                        | `ExternalType("xml")`                 |
| `geography`                  | `ExternalType("geography")`           |
| `geometry`                   | `ExternalType("geometry")`            |
| `sql_variant`                | `ExternalType("sql_variant")`         |
| `timestamp` / `rowversion`   | `ExternalType("rowversion")`          |

:::info
SQL Server doesn't support Gravitino `Timestamp_tz` (timestamp with time zone). Use `ExternalType("datetimeoffset(p)")` instead.

If character-type length metadata is missing, the connector logs a warning and uses the unbounded logical `String` type rather than inventing a length. This does not imply that the source column was declared as MAX.
:::

### Table column auto-increment

- Supports setting auto-increment via SQL Server `IDENTITY(1,1)`.
- Only one `IDENTITY` column is allowed per table (enforced by SQL Server).
- The `IDENTITY` column must be `NOT NULL`.
- SQL Server does not support adding or removing `IDENTITY` via `ALTER COLUMN`. If attempted, the native SQL Server error is surfaced.
- Seed and increment are always `(1,1)`.

### Table column default value

- Supports `GETDATE()` / `CURRENT_TIMESTAMP` for timestamp columns.
- Supports numeric, string, and boolean literals.
- SQL Server `bit` type uses `1`/`0` for boolean defaults (not `'true'`/`'false'`).
- SQL Server wraps default values in parentheses (e.g., `((0))`, `('hello')`). The converter strips these automatically.
- A nullable column without a default constraint has no Gravitino default value. Only an explicit `DEFAULT NULL` constraint maps to a null literal default.

### Table properties

- Doesn't support table properties.

### Table indexes

- Supports `PRIMARY_KEY` and `UNIQUE_KEY`.

<Tabs groupId='language' queryString>
<TabItem value="json" label="Json">

```json
{
  "indexes": [
    {
      "indexType": "primary_key",
      "name": "PK_mytable",
      "fieldNames": [["id"]]
    },
    {
      "indexType": "unique_key",
      "name": "UQ_email",
      "fieldNames": [["email"]]
    }
  ]
}
```

</TabItem>
<TabItem value="java" label="Java">

```java
Index[] indexes = new Index[] {
    Indexes.of(IndexType.PRIMARY_KEY, "PK_mytable", new String[][]{{"id"}}, Map.of()),
    Indexes.of(IndexType.UNIQUE_KEY, "UQ_email", new String[][]{{"email"}}, Map.of()),
};
```

</TabItem>
</Tabs>

### Table operations

Please refer to [Manage Relational Metadata Using Gravitino](./manage-relational-metadata-using-gravitino.md#table-operations) for more details.

#### Alter table operations

Supports operations:

- `RenameTable`
- `UpdateComment`
- `AddColumn`
- `DeleteColumn`
- `RenameColumn`
- `UpdateColumnType`
- `UpdateColumnNullability`
- `UpdateColumnComment`
- `UpdateColumnDefaultValue`

:::info
You can't submit the `RenameTable` operation at the same time as other operations.
:::

:::caution
SQL Server doesn't support the `UpdateColumnPosition` operation, so you can only use `ColumnPosition.defaultPosition()` when `AddColumn`.
If you update a nullable column to non-nullable, there may be compatibility issues.
:::

## Limitations

The following features are not supported in the current version:

| Feature                                      | Reason                                                                                                                                                                           |
| -------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| View support                                 | Gravitino `ViewCatalog` interface is `@Unstable`, no JDBC catalog implements it yet.                                                                                             |
| Schema comments                              | Non-empty comments are rejected with `UnsupportedOperationException`.                                                                                                    |
| Cascade drop schema                          | SQL Server does not support `DROP SCHEMA ... CASCADE`. Native error is surfaced.                                                                                                 |
| `datetimeoffset` native mapping              | No Gravitino equivalent. Use `ExternalType`.                                                                                                                                     |
| `TimestampType.withTimeZone`                 | SQL Server uses `datetimeoffset` which is offset-based, not timezone-based. Use `ExternalType`.                                                                                  |
| Partitioned table creation                   | SQL Server partitioning requires a multi-step process (PARTITION FUNCTION → PARTITION SCHEME → CREATE TABLE ON) that doesn't fit Gravitino's single-statement Transform model.   |
| Non-unique indexes                           | Only `PRIMARY_KEY` and `UNIQUE_KEY` are exposed.                                                                                                                                 |
| Auto-increment seed/increment                | Always `(1,1)`.                                                                                                                                                                  |
| ALTER TABLE for IDENTITY columns             | SQL Server limitation: cannot add/remove IDENTITY via ALTER.                                                                                                                     |
| Windows Auth / Entra ID / Managed Identity   | Future candidates.                                                                                                                                                               |
| `UpdateColumnPosition`                       | SQL Server does not support column reordering.                                                                                                                                   |
