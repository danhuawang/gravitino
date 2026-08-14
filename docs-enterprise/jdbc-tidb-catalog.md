---
title: "TiDB catalog"
slug: /jdbc-tidb-catalog
keywords:
  - JDBC
  - TiDB
  - MySQL
  - metadata
license: "Copyright 2026 Datastrato Pvt Ltd. This software is licensed under the Apache License version 2."
---

import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

## Introduction

Apache Gravitino provides the ability to manage TiDB metadata by reusing the existing `jdbc-mysql` catalog provider. Since TiDB is fully compatible with the MySQL protocol, no dedicated TiDB catalog provider is needed. You can connect Gravitino to a TiDB instance by simply using the `jdbc-mysql` provider with the TiDB JDBC URL.

:::caution
Gravitino saves some system information in schema and table comment, like `(From Gravitino, DO NOT EDIT: gravitino.v1.uid1078334182909406185)`, please don't change or remove this message.
:::

## Catalog

### Catalog capabilities

- Gravitino catalog corresponds to the TiDB instance.
- Supports metadata management of TiDB (6.5.x and above) via the `jdbc-mysql` provider.
- Supports DDL operation for TiDB databases and tables.
- Supports table index.
- Supports column default value and auto-increment.
- Supports managing TiDB table features through table properties, like using `engine` to set the storage engine (accepted by TiDB syntax but ignored at the storage layer).

### Catalog properties

Since TiDB uses the `jdbc-mysql` provider, the catalog properties are identical to the [MySQL catalog properties](./jdbc-mysql-catalog.md#catalog-properties). The only difference is the `jdbc-url`, which should point to the TiDB instance (default port `4000`).

You can pass to a TiDB data source any property that isn't defined by Gravitino by adding `gravitino.bypass.` prefix as a catalog property. For example, catalog property `gravitino.bypass.maxWaitMillis` will pass `maxWaitMillis` to the data source property.

Check the relevant data source configuration in [data source properties](https://commons.apache.org/proper/commons-dbcp/configuration.html)

If you use a JDBC catalog, you must provide `jdbc-url`, `jdbc-driver`, `jdbc-user` and `jdbc-password` to catalog properties.
Besides the [common catalog properties](./gravitino-server-config.md#apache-gravitino-catalog-properties-configuration), the TiDB catalog has the following properties:

| Configuration item      | Description                                                                                            | Default value | Required | Since Version |
|-------------------------|--------------------------------------------------------------------------------------------------------|---------------|----------|---------------|
| `jdbc-url`              | JDBC URL for connecting to the database. For example, `jdbc:mysql://localhost:4000`                    | (none)        | Yes      | 1.3.0         |
| `jdbc-driver`           | The driver of the JDBC connection. For example, `com.mysql.jdbc.Driver` or `com.mysql.cj.jdbc.Driver`. | (none)        | Yes      | 1.3.0         |
| `jdbc-user`             | The JDBC user name.                                                                                    | (none)        | Yes      | 1.3.0         |
| `jdbc-password`         | The JDBC password.                                                                                     | (none)        | Yes      | 1.3.0         |
| `jdbc.pool.min-size`    | The minimum number of connections in the pool. `2` by default.                                         | `2`           | No       | 1.3.0         |
| `jdbc.pool.max-size`    | The maximum number of connections in the pool. `10` by default.                                        | `10`          | No       | 1.3.0         |
| `jdbc.pool.max-wait-ms` | The maximum Duration that the pool will wait for a connection to be returned. `30000` by default.      | `30000`       | No       | 1.3.0         |

:::caution
You must download the corresponding MySQL JDBC driver to the `catalogs/jdbc-mysql/libs` directory. TiDB uses the standard MySQL Connector/J driver.
:::

### Catalog creation example

<Tabs groupId='language' queryString>
<TabItem value="shell" label="Shell">

```shell
curl -X POST -H "Accept: application/vnd.gravitino.v1+json" \
-H "Content-Type: application/json" -d '{
  "name": "tidb_catalog",
  "type": "RELATIONAL",
  "comment": "TiDB catalog via jdbc-mysql provider",
  "provider": "jdbc-mysql",
  "properties": {
    "jdbc-url": "jdbc:mysql://localhost:4000?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
    "jdbc-driver": "com.mysql.cj.jdbc.Driver",
    "jdbc-user": "root",
    "jdbc-password": ""
  }
}' http://localhost:8090/api/metalakes/metalake/catalogs
```

</TabItem>
<TabItem value="java" label="Java">

```java
Map<String, String> tidbProperties = ImmutableMap.<String, String>builder()
    .put("jdbc-url", "jdbc:mysql://localhost:4000?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC")
    .put("jdbc-driver", "com.mysql.cj.jdbc.Driver")
    .put("jdbc-user", "root")
    .put("jdbc-password", "")
    .build();

Catalog catalog = gravitinoClient.createCatalog("tidb_catalog",
    Type.RELATIONAL,
    "jdbc-mysql",
    "TiDB catalog via jdbc-mysql provider",
    tidbProperties);
```

</TabItem>
<TabItem value="python" label="Python">

```python
gravitino_client.create_catalog(
    name="tidb_catalog",
    catalog_type=Catalog.Type.RELATIONAL,
    provider="jdbc-mysql",
    comment="TiDB catalog via jdbc-mysql provider",
    properties={
        "jdbc-url": "jdbc:mysql://localhost:4000?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
        "jdbc-driver": "com.mysql.cj.jdbc.Driver",
        "jdbc-user": "root",
        "jdbc-password": "",
    })
```

</TabItem>
</Tabs>

### Catalog operations

Refer to [Manage Catalogs and Schemas](./manage-catalogs-and-schemas.md#catalog-operations) for more details.

## Schema

### Schema capabilities

- Gravitino's schema concept corresponds to the TiDB database.
- Supports creating schema, but does not support setting comment.
- Supports dropping schema.
- Supports cascade dropping schema.

### Schema properties

- Doesn't support any schema property settings.

### Schema operations

Refer to [Manage Catalogs and Schemas](./manage-catalogs-and-schemas.md#schema-operations) for more details.

:::info
When listing schemas, TiDB system databases such as `METRICS_SCHEMA` and `test` will appear alongside user-created schemas. These are TiDB built-in databases and can be safely ignored.
:::

## Table

### Table capabilities

- Gravitino's table concept corresponds to the TiDB table.
- Supports DDL operation for TiDB tables.
- Supports index.
- Supports column default value and auto-increment.
- Supports managing TiDB table features through table properties, like using `engine` to set the storage engine property.

### Table column types

Since TiDB is compatible with the MySQL protocol, the type mapping is the same as the [MySQL catalog type mapping](./jdbc-mysql-catalog.md#table-column-types):

| Gravitino Type      | TiDB Type           |
|---------------------|---------------------|
| `Byte`              | `Tinyint`           |
| `Unsigned Byte`     | `Tinyint Unsigned`  |
| `Short`             | `Smallint`          |
| `Unsigned Short`    | `Smallint Unsigned` |
| `Integer`           | `Int`               |
| `Unsigned Integer`  | `Int Unsigned`      |
| `Long`              | `Bigint`            |
| `Unsigned Long`     | `Bigint Unsigned`   |
| `Float`             | `Float`             |
| `Double`            | `Double`            |
| `String`            | `Text`              |
| `Date`              | `Date`              |
| `Time[(p)]`         | `Time[(p)]`         |
| `Timestamp_tz[(p)]` | `Timestamp(p)`      |
| `Timestamp[(p)]`    | `Datetime[(p)]`     |
| `Decimal`           | `Decimal`           |
| `VarChar`           | `VarChar`           |
| `FixedChar`         | `FixedChar`         |
| `Binary`            | `Binary`            |
| `BOOLEAN`           | `BIT`               |

:::info
TiDB doesn't support Gravitino `Fixed` `Struct` `List` `Map` `IntervalDay` `IntervalYear` `Union` `UUID` type.
Meanwhile, the data types other than listed above are mapped to Gravitino **External Type** that represents an unresolvable data type since 0.6.0-incubating.
:::

### Table column auto-increment

:::note
TiDB setting an auto-increment column requires simultaneously setting a unique index; otherwise, an error will occur.
:::

:::info
TiDB's auto-increment ID allocation strategy differs from MySQL. By default, TiDB uses a distributed allocation strategy where auto-increment IDs may not be strictly sequential. This does not affect Gravitino metadata management but should be noted at the data layer.
:::

<Tabs groupId='language' queryString>
<TabItem value="json" label="Json">

```json
{
  "columns": [
    {
      "name": "id",
      "type": "integer",
      "comment": "id column comment",
      "nullable": false,
      "autoIncrement": true
    },
    {
      "name": "name",
      "type": "varchar(500)",
      "comment": "name column comment",
      "nullable": true,
      "autoIncrement": false
    }
  ],
  "indexes": [
    {
      "indexType": "primary_key",
      "name": "PRIMARY",
      "fieldNames": [["id"]]
    }
  ]
}
```

</TabItem>
<TabItem value="java" label="Java">

```java
Column[] cols = new Column[] {
    Column.of("id", Types.IntegerType.get(), "id column comment", false, true, null),
    Column.of("name", Types.VarCharType.of(500), "Name of the user", true, false, null)
};
Index[] indexes = new Index[] {
    Indexes.of(IndexType.PRIMARY_KEY, "PRIMARY", new String[][]{{"id"}}, Map.of())
};
```

</TabItem>
</Tabs>

### Table properties

Although TiDB itself does not fully support MySQL storage engines, Gravitino offers table property management for TiDB tables through the `jdbc-mysql` catalog. The supported properties are listed as follows:

:::note
**Reserved**: Fields that cannot be passed to the Gravitino server.

**Immutable**: Fields that cannot be modified once set.
:::

:::caution
- Doesn't support remove table properties. You can only add or modify properties, not delete properties.
:::

| Property Name           | Description                                                                                                                                                     | Default Value | Required | Reserved | Immutable |
|-------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------|----------|----------|-----------|
| `engine`                | The engine used by the table. TiDB accepts this property in DDL syntax but ignores it at the storage layer (TiDB uses its own distributed storage engine TiKV). | `InnoDB`      | No       | No       | Yes       |
| `auto-increment-offset` | Used to specify the starting value of the auto-increment field.                                                                                                 | (none)        | No       | No       | Yes       |

### Table indexes

- Supports PRIMARY_KEY and UNIQUE_KEY.

:::note
The index name of the PRIMARY_KEY must be PRIMARY
[Create table index](https://docs.pingcap.com/tidb/v6.5/sql-statement-create-table)
:::

<Tabs groupId='language' queryString>
<TabItem value="json" label="Json">

```json
{
  "indexes": [
    {
      "indexType": "primary_key",
      "name": "PRIMARY",
      "fieldNames": [["id"]]
    },
    {
      "indexType": "unique_key",
      "name": "id_name_uk",
      "fieldNames": [["id"] ,["name"]]
    }
  ]
}
```

</TabItem>
<TabItem value="java" label="Java">

```java
Index[] indexes = new Index[] {
    Indexes.of(IndexType.PRIMARY_KEY, "PRIMARY", new String[][]{{"id"}}, Map.of()),
    Indexes.of(IndexType.UNIQUE_KEY, "id_name_uk", new String[][]{{"id"} , {"name"}}, Map.of()),
};
```

</TabItem>
</Tabs>

### Table operations

Refer to [Manage Relational Metadata Using Gravitino](./manage-relational-metadata-using-gravitino.md#table-operations) for more details.

#### Alter table operations

Gravitino supports these table alteration operations:

- `RenameTable`
- `UpdateComment`
- `AddColumn`
- `DeleteColumn`
- `RenameColumn`
- `UpdateColumnType`
- `UpdateColumnPosition`
- `UpdateColumnNullability`
- `UpdateColumnComment`
- `UpdateColumnDefaultValue`
- `SetProperty`

:::info
 - You cannot submit the `RenameTable` operation at the same time as other operations.
 - If you update a nullability column to non-nullability, there may be compatibility issues.
:::

## Differences from MySQL

While TiDB is highly compatible with MySQL, there are some behavioral differences when using the Gravitino `jdbc-mysql` catalog:

| Item                                | MySQL                                                          | TiDB                                                              |
|-------------------------------------|----------------------------------------------------------------|-------------------------------------------------------------------|
| Default port                        | `3306`                                                         | `4000`                                                            |
| System databases                    | `information_schema`, `mysql`, `performance_schema`, `sys`     | Additionally includes `METRICS_SCHEMA`, `test`                    |
| Storage engine (`engine` property)  | Takes effect (InnoDB, MyISAM, etc.)                            | Accepted in DDL syntax but ignored (TiDB uses TiKV)               |
| Auto-increment ID                   | Strictly sequential                                            | Non-sequential by default (distributed allocation)                |
| `lower_case_table_names`            | `0` (case-sensitive) on Linux, `1` (case-insensitive) on macOS | `2` (case-insensitive, preserves original case)                   |
| Transaction isolation level         | Supports all 4 standard levels                                 | Only `REPEATABLE-READ` and `READ-COMMITTED`                       |
| `SET SESSION TRANSACTION READ ONLY` | Natively supported                                             | Noop implementation, disabled by default                          |
| `CREATE TABLE ... SELECT`           | Supported                                                      | Not supported in 6.5.x (supported since 7.x)                      |

:::info
TiDB's system databases (`METRICS_SCHEMA`, `test`) will appear when listing schemas but do not affect catalog drop operations. Gravitino only tracks schemas created through its own API (stored in the entity store), so externally existing databases are not considered during catalog drop checks.
:::

## TiDB prerequisite configuration

When using TiDB with Gravitino through query engines (Spark, Trino, Flink), the following TiDB global variables must be set to ensure compatibility:

```sql
-- Required for Spark: JDBC writer uses READ-UNCOMMITTED isolation level
SET GLOBAL tidb_skip_isolation_level_check = 1;

-- Required for Trino: MySQL connector uses SET SESSION TRANSACTION READ ONLY
SET GLOBAL tidb_enable_noop_functions = 1;
```

These settings only need to be applied once and persist across TiDB restarts.

## Trino integration notes

When using TiDB with the Gravitino Trino connector, add the following catalog property to avoid the `CREATE TABLE ... SELECT` limitation in TiDB 6.5.x:

```json
{
  "trino.bypass.insert.non-transactional-insert.enabled": "true"
}
```

This makes Trino write directly to the target table instead of using a temporary table. Note that with this property enabled, data can be corrupted in rare cases where exceptions occur during the insert operation.
