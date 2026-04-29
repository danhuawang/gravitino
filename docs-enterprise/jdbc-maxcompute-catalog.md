---
title: "MaxCompute catalog"
slug: /jdbc-maxcompute-catalog
keywords:
- jdbc
- MaxCompute
- ODPS
- Alibaba Cloud
- metadata
license: "Copyright 2026 Datastrato Pvt Ltd. This software is licensed under the Apache License version 2."
---

import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

## Introduction

Apache Gravitino provides the ability to manage [Alibaba Cloud MaxCompute](https://www.alibabacloud.com/product/maxcompute) (formerly known as ODPS) metadata through JDBC connection.

:::caution
Before using the MaxCompute catalog, you must:

1. **Enable Tenant-level Schema Syntax** in MaxCompute Console:
    - Go to MaxCompute Console → Project Settings → Advanced Settings
    - Enable "Tenant-level Schema Syntax"
    - This enables the three-layer namespace: Project → Schema → Table

2. **Download the JDBC Driver**:
    - Download `odps-jdbc-3.8.8-jar-with-dependencies.jar` or higher from [Maven Repository](https://search.maven.org/search?q=a:odps-jdbc) or [GitHub Releases](https://github.com/aliyun/aliyun-odps-jdbc/releases)
    - Place it in `catalogs/jdbc-maxcompute/libs` directory
      :::

## Catalog

### Catalog capabilities

- Gravitino catalog corresponds to the MaxCompute Project.
- Supports metadata management of MaxCompute schemas and tables.
- Supports DDL operations for MaxCompute schemas and tables.
- Supports partitioned tables (up to 6 partition levels).
- Supports [column default value](./manage-relational-metadata-using-gravitino.md#table-column-default-value).

### Catalog properties

You can pass to a MaxCompute data source any property that isn't defined by Gravitino by adding
`gravitino.bypass.` prefix as a catalog property. For example, catalog property
`gravitino.bypass.tunnelEndpoint` will pass `tunnelEndpoint` to the data source property.

Besides the [common catalog properties](./gravitino-server-config.md#apache-gravitino-catalog-properties-configuration), the MaxCompute catalog has the following properties:

| Configuration item   | Description                                                                                                  | Default value | Required | Since Version |
|----------------------|--------------------------------------------------------------------------------------------------------------|---------------|----------|---------------|
| `jdbc-url`           | JDBC URL for connecting to MaxCompute. Must include `odpsNamespaceSchema=true` parameter. See example below. | (none)        | Yes      | 1.2.0         |
| `jdbc-driver`        | The driver of the JDBC connection. Should be `com.aliyun.odps.jdbc.OdpsDriver`.                              | (none)        | Yes      | 1.2.0         |
| `jdbc-user`          | The Alibaba Cloud Access Key ID.                                                                             | (none)        | Yes      | 1.2.0         |
| `jdbc-password`      | The Alibaba Cloud Access Key Secret.                                                                         | (none)        | Yes      | 1.2.0         |
| `jdbc.pool.min-size` | The minimum number of connections in the pool. `2` by default.                                               | `2`           | No       | 1.2.0         |
| `jdbc.pool.max-size` | The maximum number of connections in the pool. `10` by default.                                              | `10`          | No       | 1.2.0         |

#### JDBC URL format

The JDBC URL must follow this format and include the `odpsNamespaceSchema=true` parameter:

```
jdbc:odps:<endpoint>?project=<project_name>&odpsNamespaceSchema=true
```

Example:
```
jdbc:odps:https://service.cn-hangzhou.maxcompute.aliyun.com/api?project=my_project&odpsNamespaceSchema=true
```

Common MaxCompute endpoints:
- China (Hangzhou): `https://service.cn-hangzhou.maxcompute.aliyun.com/api`
- China (Shanghai): `https://service.cn-shanghai.maxcompute.aliyun.com/api`
- China (Beijing): `https://service.cn-beijing.maxcompute.aliyun.com/api`
- Singapore: `https://service.ap-southeast-1.maxcompute.aliyun.com/api`

:::caution
The `odpsNamespaceSchema=true` parameter is **required** for the three-layer namespace (Project → Schema → Table) to work properly. Without this parameter, schema and table operations will fail.
:::

### Catalog operations

Please refer to [Manage Relational Metadata Using Gravitino](./manage-relational-metadata-using-gravitino.md#catalog-operations) for more details.

## Schema

### Schema capabilities

- Gravitino's schema concept corresponds to the MaxCompute Schema (within a Project).
- Supports creating schema.
- Supports dropping schema.
- Supports listing schemas.

### Schema properties

- Doesn't support any schema property settings.

:::caution
MaxCompute does not support schema comments or custom properties. Attempting to create a schema with a comment will throw an `UnsupportedOperationException`.
:::

### Schema operations

Please refer to [Manage Relational Metadata Using Gravitino](./manage-relational-metadata-using-gravitino.md#schema-operations) for more details.

## Table

### Table capabilities

- Gravitino's table concept corresponds to the MaxCompute table.
- Supports internal tables (managed tables).
- Supports partitioned tables (up to 6 partition levels).
- Supports [column default value](./manage-relational-metadata-using-gravitino.md#table-column-default-value).

### Table column types

| Gravitino Type | MaxCompute Type       | Notes                                      |
|----------------|-----------------------|--------------------------------------------|
| `Boolean`      | `BOOLEAN`             | true/false                                 |
| `Byte`         | `TINYINT`             | 8-bit signed integer (-128 to 127)         |
| `Short`        | `SMALLINT`            | 16-bit signed integer (-32768 to 32767)    |
| `Integer`      | `INT`                 | 32-bit signed integer                      |
| `Long`         | `BIGINT`              | 64-bit signed integer                      |
| `Float`        | `FLOAT`               | 32-bit floating point                      |
| `Double`       | `DOUBLE`              | 64-bit floating point                      |
| `Decimal(p,s)` | `DECIMAL(p,s)`        | precision: 1-38, scale: 0-18 (default)     |
| `String`       | `STRING`              | Variable-length string (up to 8MB)         |
| `VarChar(n)`   | `VARCHAR(n)`          | Variable-length string (n: 1-65535)        |
| `FixedChar(n)` | `CHAR(n)`             | Fixed-length string (n: 1-255)             |
| `Binary`       | `BINARY`              | Binary data (up to 8MB)                    |
| `Date`         | `DATE`                | Date (yyyy-MM-dd)                          |
| `Timestamp`    | `TIMESTAMP`           | Timestamp with nanosecond precision        |
| `Timestamp`    | `DATETIME`            | When reading from MaxCompute (millisecond) |
| `IntervalYear` | `INTERVAL_YEAR_MONTH` | Year-month interval                        |
| `IntervalDay`  | `INTERVAL_DAY_TIME`   | Day-time interval                          |

:::info
MaxCompute doesn't support Gravitino `Time` `UUID` `Fixed` `Timestamp_tz` `ListType` `MapType` `StructType` `UnionType` type.
Complex types (`ARRAY`, `MAP`, `STRUCT`, `JSON`) are mapped to Gravitino's **[External Type](./manage-relational-metadata-using-gravitino.md#external-type)** that represents an unresolvable data type since 1.2.0.
:::

:::note
- **TIMESTAMP vs DATETIME**: MaxCompute has both `DATETIME` (millisecond precision) and `TIMESTAMP` (nanosecond precision). Gravitino's `TimestampType` maps to `TIMESTAMP` when writing, but both map to `TimestampType` when reading.
- **Timezone**: MaxCompute `TIMESTAMP` is timezone-independent. If you use Gravitino's `TimestampType.withTimeZone()`, the timezone information will be lost when stored in MaxCompute.
- **DECIMAL Scale**: By default, MaxCompute DECIMAL scale is limited to 18. To use scale up to 38, you need to enable the extended scale flag in MaxCompute project settings.
  :::

### Table column auto-increment

- MaxCompute does not support auto-increment columns.

### Table properties

| Property name | Description                                         | Default value | Required |
|---------------|-----------------------------------------------------|---------------|----------|
| `lifecycle`   | Table lifecycle in days (auto-delete after expiry). | (none)        | No       |

### Table indexes

- MaxCompute does not support traditional indexes (PRIMARY_KEY, UNIQUE_KEY, or secondary indexes).

:::info
MaxCompute does not have a traditional index concept. If you need data organization for performance, consider using partitioned tables.
:::

### Table partitioning

MaxCompute supports partitioned tables with up to 6 partition levels. Partition columns are typically of type `STRING`, but also support `TINYINT`, `SMALLINT`, `INT`, `BIGINT`.

<Tabs groupId='language' queryString>
<TabItem value="json" label="Json">

```json
{
  "partitioning": [
    {
      "strategy": "identity",
      "fieldName": ["dt"]
    },
    {
      "strategy": "identity",
      "fieldName": ["region"]
    }
  ]
}
```

</TabItem>
<TabItem value="java" label="Java">

```java
Transform[] partitioning = new Transform[] {
    Transforms.identity("dt"),
    Transforms.identity("region")
};
```

</TabItem>
</Tabs>

:::caution
The `fieldName` specified in the partitioning attributes must be the name of columns defined in the table.
:::

### Table operations

Please refer to [Manage Relational Metadata Using Gravitino](./manage-relational-metadata-using-gravitino.md#table-operations) for more details.

#### Alter table operations

Gravitino supports these table alteration operations:

- `RenameTable`
- `UpdateComment`
- `AddColumn`
- `RenameColumn`
- `UpdateColumnComment`
- `SetProperty`

:::info
- `DeleteColumn` is **NOT supported** by MaxCompute. You need to recreate the table without the column.
- `RemoveProperty` is **NOT supported** by MaxCompute.
- `UpdateColumnType` is **NOT supported** by MaxCompute. Column types cannot be changed after table creation.
- `UpdateColumnNullability` is **NOT supported** by MaxCompute. The nullability of a column cannot be changed after table creation.
- When performing multiple ALTER operations in a single request, Gravitino executes each operation separately to avoid MaxCompute JDBC driver limitations.
  :::

### Partition operations

Partition-level operations are supported for partitioned tables:

| Operation            | Supported | Notes                                        |
|----------------------|-----------|----------------------------------------------|
| List partition names | Yes       | Returns partition specs like `pt=2024-01-01` |
| List partitions      | Yes       | Returns full partition objects               |
| Get partition        | Yes       | Get a specific partition by name             |
| Add partition        | Yes       | Add a new partition to the table             |
| Drop partition       | Yes       | Remove a partition from the table            |
| Partition exists     | Yes       | Check if a partition exists                  |
| Purge partition      | No        | MaxCompute does not support partition purge  |

<Tabs groupId='language' queryString>
<TabItem value="shell" label="Shell">

```shell
# List all partition names
curl -X GET "${BASE_URL}/schemas/test_schema/tables/partitioned_table/partitions" | jq

# Add a new partition
curl -X POST "${BASE_URL}/schemas/test_schema/tables/partitioned_table/partitions" \
-H "Content-Type: application/json" \
-d '{
  "partitions": [
    {
      "name": "dt=2024-01-01",
      "type": "identity",
      "fieldNames": [["dt"]],
      "values": [
        {"type": "literal", "dataType": "string", "value": "2024-01-01"}
      ],
      "properties": {}
    }
  ]
}' | jq

# Get a specific partition (URL encode '=' as '%3D')
curl -X GET "${BASE_URL}/schemas/test_schema/tables/partitioned_table/partitions/dt%3D2024-01-01" | jq

# Check if partition exists
curl -X HEAD "${BASE_URL}/schemas/test_schema/tables/partitioned_table/partitions/dt%3D2024-01-01" -w "%{http_code}\n"

# Drop a partition
curl -X DELETE "${BASE_URL}/schemas/test_schema/tables/partitioned_table/partitions/dt%3D2024-01-01" | jq
```

</TabItem>
<TabItem value="java" label="Java">

```java
// Get partition operations from a partitioned table
Table table = tableCatalog.loadTable(NameIdentifier.of("my_schema", "partitioned_table"));
SupportsPartitions partitionOps = table.supportPartitions();

// List all partition names
String[] partitionNames = partitionOps.listPartitionNames();
// Returns: ["pt=2024-01-01", "pt=2024-01-02"]

// List all partitions with details
Partition[] partitions = partitionOps.listPartitions();

// Get a specific partition
Partition partition = partitionOps.getPartition("pt=2024-01-01");

// Add a new partition
IdentityPartition newPartition = Partitions.identity(
    "pt=2024-01-03",
    new String[][] {{"pt"}},
    new Literal<?>[] {Literals.stringLiteral("2024-01-03")},
    Collections.emptyMap()
);
Partition added = partitionOps.addPartition(newPartition);

// Check if partition exists
boolean exists = partitionOps.partitionExists("pt=2024-01-01");

// Drop a partition
boolean dropped = partitionOps.dropPartition("pt=2024-01-01");
```

</TabItem>
</Tabs>

:::info
For tables with multiple partition columns, partition names use `/` as separator:
- Single level: `pt=2024-01-01`
- Multi-level: `pt=2024-01-01/region=us`
  :::

## Known limitations

| Feature                   | Status         | Notes                                                                |
|---------------------------|----------------|----------------------------------------------------------------------|
| Delta Tables              |  Not supported | Transactional tables are not supported.                              |
| External tables           |  Not supported | OSS external tables are not supported.                               |
| Column deletion           |  Not supported | MaxCompute does not support DROP COLUMN. Recreate the table instead. |
| Column type change        |  Not supported | MaxCompute does not support ALTER COLUMN TYPE. Recreate the table.   |
| Column nullability change |  Not supported | MaxCompute does not support ALTER COLUMN SET/DROP NOT NULL.          |
| Property removal          |  Not supported | MaxCompute does not support removing table properties.               |
| Auto-increment columns    |  Not supported | MaxCompute has no auto-increment feature.                            |
| TIME data type            |  Not supported | Use DATETIME or TIMESTAMP instead.                                   |
| Primary keys              |  Not supported | MaxCompute has no primary key concept.                               |
| Secondary indexes         |  Not supported | MaxCompute has no index concept.                                     |
| Foreign keys              |  Not supported | MaxCompute has no foreign key concept.                               |
| Unique constraints        |  Not supported | MaxCompute has no unique constraint concept.                         |
| Transactions              |  Not supported | MaxCompute does not support ACID transactions.                       |
| Stored procedures         |  Not supported |                                                                      |
| Schema comments           |  Not supported | MaxCompute does not support schema-level comments.                   |



## Example

### Create a MaxCompute catalog

<Tabs groupId='language' queryString>
<TabItem value="shell" label="Shell">

```shell
curl -X POST http://localhost:8090/api/metalakes/metalake/catalogs \
  -H "Content-Type: application/json" \
  -d '{
    "name": "maxcompute_catalog",
    "type": "RELATIONAL",
    "provider": "jdbc-maxcompute",
    "properties": {
      "jdbc-url": "jdbc:odps:https://service.cn-hangzhou.maxcompute.aliyun.com/api?project=your_project&odpsNamespaceSchema=true",
      "jdbc-driver": "com.aliyun.odps.jdbc.OdpsDriver",
      "jdbc-user": "your_access_key_id",
      "jdbc-password": "your_access_key_secret"
    }
  }'
```

</TabItem>
<TabItem value="java" label="Java">

```java
GravitinoClient gravitinoClient = GravitinoClient
    .builder("http://localhost:8090")
    .withMetalake("metalake")
    .build();

Map<String, String> properties = ImmutableMap.<String, String>builder()
    .put("jdbc-url", "jdbc:odps:https://service.cn-hangzhou.maxcompute.aliyun.com/api?project=your_project&odpsNamespaceSchema=true")
    .put("jdbc-driver", "com.aliyun.odps.jdbc.OdpsDriver")
    .put("jdbc-user", "your_access_key_id")
    .put("jdbc-password", "your_access_key_secret")
    .build();

Catalog catalog = gravitinoClient.createCatalog(
    "maxcompute_catalog",
    Catalog.Type.RELATIONAL,
    "jdbc-maxcompute",
    "MaxCompute catalog",
    properties
);
```

</TabItem>
</Tabs>

### Create a schema

<Tabs groupId='language' queryString>
<TabItem value="shell" label="Shell">

```shell
curl -X POST http://localhost:8090/api/metalakes/metalake/catalogs/maxcompute_catalog/schemas \
  -H "Content-Type: application/json" \
  -d '{
    "name": "my_schema",
    "properties": {}
  }'
```

</TabItem>
<TabItem value="java" label="Java">

```java
SupportsSchemas schemas = catalog.asSchemas();
Schema schema = schemas.createSchema(
    "my_schema",
    null,  // MaxCompute does not support schema comments
    ImmutableMap.of()
);
```

</TabItem>
</Tabs>

### Create a table

<Tabs groupId='language' queryString>
<TabItem value="shell" label="Shell">

```shell
curl -X POST http://localhost:8090/api/metalakes/metalake/catalogs/maxcompute_catalog/schemas/my_schema/tables \
  -H "Content-Type: application/json" \
  -d '{
    "name": "my_table",
    "comment": "My MaxCompute table",
    "columns": [
      {"name": "id", "type": "bigint", "nullable": false, "comment": "Primary key"},
      {"name": "name", "type": "string", "nullable": true, "comment": "User name"},
      {"name": "dt", "type": "string", "nullable": true, "comment": "Partition column"}
    ],
    "partitioning": [
      {"strategy": "identity", "fieldName": ["dt"]}
    ],
    "properties": {
      "lifecycle": "30"
    }
  }'
```

</TabItem>
<TabItem value="java" label="Java">

```java
TableCatalog tableCatalog = catalog.asTableCatalog();

Column[] columns = new Column[] {
    Column.of("id", Types.LongType.get(), "Primary key", false, false, null),
    Column.of("name", Types.StringType.get(), "User name", true, false, null),
    Column.of("dt", Types.StringType.get(), "Partition column", true, false, null)
};

Transform[] partitioning = new Transform[] {
    Transforms.identity("dt")
};

Map<String, String> properties = ImmutableMap.of("lifecycle", "30");

Table table = tableCatalog.createTable(
    NameIdentifier.of("my_schema", "my_table"),
    columns,
    "My MaxCompute table",
    properties,
    partitioning
);
```

</TabItem>
</Tabs>
