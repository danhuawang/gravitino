---
title: "BigQuery catalog"
slug: /jdbc-bigquery-catalog
keywords:
- jdbc
- BigQuery
- metadata
license: "Copyright 2026 Datastrato Pvt Ltd. This software is licensed under the Apache License version 2."
---

import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

## Introduction

Apache Gravitino provides the ability to manage Google BigQuery metadata through JDBC connection.

:::caution
Gravitino saves some system information in schema and table comment, like `(From Gravitino, DO NOT EDIT: gravitino.v1.uid1078334182909406185)`, please don't change or remove this message.
:::

### JDBC Driver Installation

The BigQuery JDBC driver is not included in the Gravitino distribution. You must download and install it manually:

1. Download the Simba JDBC Driver for Google BigQuery from the [official website](https://cloud.google.com/bigquery/docs/reference/odbc-jdbc-drivers)
2. Extract the downloaded ZIP file
3. Copy all JAR files (except Jackson JARs to avoid conflicts) to `${GRAVITINO_HOME}/catalogs/jdbc-bigquery/libs/`
4. Restart the Gravitino server

:::note
The recommended driver version is 1.6.5.1001 or later. Make sure to exclude Jackson-related JARs from the driver package to avoid dependency conflicts with Gravitino.
:::

## Catalog

### Catalog capabilities

- Gravitino catalog corresponds to the BigQuery project.
- Supports metadata management of Google BigQuery.
- Supports DDL operations for BigQuery datasets and tables.
- Supports table partitioning and clustering.
- Supports [column default value](./manage-relational-metadata-using-gravitino.md#table-column-default-value).

### Catalog properties

You can pass to a BigQuery data source any property that isn't defined by Gravitino by adding `gravitino.bypass.` prefix as a catalog property. For example, catalog property `gravitino.bypass.maxWaitMillis` will pass `maxWaitMillis` to the data source property.

Check the relevant data source configuration in [data source properties](https://commons.apache.org/proper/commons-dbcp/configuration.html)

If you use a JDBC catalog, you must provide `jdbc-url`, `jdbc-driver`, `jdbc-user` and `jdbc-password` to catalog properties.
Besides the [common catalog properties](./gravitino-server-config.md#apache-gravitino-catalog-properties-configuration), the BigQuery catalog has the following properties:

| Configuration item      | Description                                                                                       | Default value                                                | Required | Since version |
|-------------------------|---------------------------------------------------------------------------------------------------|--------------------------------------------------------------|----------|---------------|
| `project-id`            | Google Cloud Project ID                                                                           | (none)                                                       | Yes      | 1.2.0         |
| `jdbc-url`              | JDBC connection URL, can be auto-generated or manually specified                                  | `jdbc:bigquery://https://www.googleapis.com/bigquery/v2:443` | Yes      | 1.2.0         |
| `jdbc-driver`           | JDBC driver class name                                                                            | `com.simba.googlebigquery.jdbc42.Driver`                     | Yes      | 1.2.0         |
| `jdbc-user`             | Service account email                                                                             | (none)                                                       | Yes      | 1.2.0         |
| `jdbc-password`         | Service account key file path                                                                     | (none)                                                       | Yes      | 1.2.0         |
| `jdbc.pool.min-size`    | The minimum number of connections in the pool. `2` by default.                                    | `2`                                                          | No       | 1.2.0         |
| `jdbc.pool.max-size`    | The maximum number of connections in the pool. `10` by default.                                   | `10`                                                         | No       | 1.2.0         |
| `jdbc.pool.max-wait-ms` | The maximum Duration that the pool will wait for a connection to be returned. `30000` by default. | `30000`                                                      | No       | 1.2.0         |
| `proxy-host`            | Proxy server hostname or IP address                                                               | (none)                                                       | No       | 1.2.0         |
| `proxy-port`            | Proxy server port number                                                                          | (none)                                                       | No       | 1.2.0         |
| `proxy-username`        | Proxy authentication username                                                                     | (none)                                                       | No       | 1.2.0         |
| `proxy-password`        | Proxy authentication password                                                                     | (none)                                                       | No       | 1.2.0         |

:::note
When using a proxy that requires credentials (`proxy-username` and `proxy-password`), the following JVM arguments must be used:
```
-Djdk.http.auth.tunneling.disabledSchemes=
-Djdk.http.auth.proxying.disabledSchemes=
```
These arguments disable the default security restrictions on HTTP authentication schemes for tunneling and proxying, allowing authentication to work properly through the proxy.
:::



### Catalog operations

Refer to [Manage Relational Metadata Using Gravitino](./manage-relational-metadata-using-gravitino.md#catalog-operations) for more details.

## Schema

### Schema capabilities

- Gravitino's schema concept corresponds to the BigQuery dataset.
- Supports creating schema with properties like location, description, labels, etc.
- Supports dropping schema.
- Supports cascade dropping schema.

### Schema properties

| Property Name                        | Type                          | Description                                                             | Supported |
|--------------------------------------|-------------------------------|-------------------------------------------------------------------------|-----------|
| `default_kms_key_name`               | STRING                        | Default Cloud KMS encryption key (not verified)                         | Yes       |
| `default_partition_expiration_days`  | FLOAT64                       | Default partition expiration days                                       | Yes       |
| `default_rounding_mode`              | STRING                        | Default rounding mode: `ROUND_HALF_AWAY_FROM_ZERO` or `ROUND_HALF_EVEN` | Yes       |
| `default_table_expiration_days`      | FLOAT64                       | Default table expiration days                                           | Yes       |
| `failover_reservation`               | STRING                        | Failover reservation                                                    | No        |
| `friendly_name`                      | STRING                        | Friendly name                                                           | Yes       |
| `is_case_insensitive`                | BOOL                          | Case insensitive                                                        | Yes       |
| `is_primary`                         | BOOLEAN                       | Whether it's the primary replica                                        | No        |
| `primary_replica`                    | STRING                        | Primary replica name                                                    | No        |
| `storage_billing_model`              | STRING                        | Storage billing model: `PHYSICAL` or `LOGICAL`                          | Yes       |
| `tags`                               | ARRAY<STRUCT<STRING, STRING>> | IAM tags array                                                          | Yes       |
| `collate`                            | STRING                        | Collation schema                                                        | No        |

### Schema operations

Refer to [Manage Relational Metadata Using Gravitino](./manage-relational-metadata-using-gravitino.md#schema-operations) for more details.

## Table

### Table capabilities

- Gravitino's table concept corresponds to the BigQuery table.
- Supports DDL operations for BigQuery tables.
- Supports table partitioning and clustering.
- Supports [column default value](./manage-relational-metadata-using-gravitino.md#table-column-default-value).

### Table column types

**Basic Types Supported by Web UI**:

| Gravitino Type | BigQuery Type |
|----------------|---------------|
| `Binary`       | `BYTES`       |
| `Boolean`      | `BOOL`        |
| `Char`         | `STRING`      |
| `Date`         | `DATE`        |
| `Decimal`      | `NUMERIC`     |
| `Double`       | `FLOAT64`     |
| `Long`         | `INT64`       |
| `String`       | `STRING`      |
| `Time`         | `TIME`        |
| `Timestamp`    | `DATETIME`    |
| `Timestamp_tz` | `TIMESTAMP`   |
| `VarChar`      | `STRING`      |

**Complex Types Supported by API** (Not supported in Web UI):

| Gravitino Type | BigQuery Type |
|----------------|---------------|
| `ARRAY<T>`     | `ARRAY<T>`    |
| `STRUCT<...>`  | `STRUCT<...>` |
| `Geography`    | `GEOGRAPHY`   |
| `Json`         | `JSON`        |
| `Range<T>`     | `RANGE<T>`    |
| `BigNumeric`   | `BIGNUMERIC`  |

:::info
BigQuery doesn't support Gravitino `Fixed` `IntervalDay` `IntervalYear` `Union` `UUID` type.
Meanwhile, the data types other than listed above are mapped to Gravitino **[External Type](./manage-relational-metadata-using-gravitino.md#external-type)** that represents an unresolvable data type.

**Note on BIGNUMERIC**: BigQuery's BIGNUMERIC type supports precision of approximately 76.8 digits (the 77th digit is partial), which exceeds Gravitino's DecimalType maximum precision of 38. To avoid precision loss, BIGNUMERIC is mapped to ExternalType and preserved as-is. Use the API with ExternalType or UnparsedType to work with BIGNUMERIC columns.

Unsupported types will be optimized in future versions. The following types are recommended to use `string` type as a workaround:
- INTERVAL
:::

### Table properties

| Property Name                   | Description                               | Type    | Example Value                                      | Supported |
|---------------------------------|-------------------------------------------|---------|----------------------------------------------------|-----------|
| `description`                   | Table description                         | String  | `"Customer data table"`                            | Yes       |
| `friendly_name`                 | Friendly name                             | String  | `"Customer Data"`                                  | Yes       |
| `expiration_timestamp`          | Table expiration timestamp                | String  | `"2025-12-31T23:59:59Z"`                           | Yes       |
| `partition_expiration_days`     | Partition expiration days                 | Float64 | `"365.5"`                                          | Yes       |
| `require_partition_filter`      | Whether partition filter is required      | Boolean | `"true"`                                           | Yes       |
| `clustering_fields`             | Clustering fields, comma-separated, max 4 | String  | `"country,city,category"`                          | Yes       |
| `labels`                        | Table labels, JSON array format           | String  | `"[{\"env\":\"prod\"}, {\"team\":\"data\"}]"`      | Yes       |
| `kms_key_name`                  | Cloud KMS encryption key                  | String  | `"projects/p/locations/l/keyRings/r/cryptoKeys/k"` | Yes       |
| `default_rounding_mode`         | Default rounding mode                     | String  | `"ROUND_HALF_EVEN"`                                | Yes       |
| `enable_change_history`         | Enable change history                     | Boolean | `"true"`                                           | Yes       |
| `enable_fine_grained_mutations` | Enable fine-grained mutations             | Boolean | `"true"`                                           | Yes       |
| `tags`                          | IAM tags                                  | String  | `"[{\"key\":\"value\"}]"`                          | Yes       |
| `max_staleness`                 | Maximum staleness                         | String  | `"INTERVAL 4 HOUR"`                                | No        |
| `storage_uri`                   | Storage URI for managed tables            | String  | `"gs://my-bucket/tables/"`                         | No        |
| `file_format`                   | File format for managed tables            | String  | `"PARQUET"`                                        | No        |
| `table_format`                  | Table format for managed tables           | String  | `"ICEBERG"`                                        | No        |

### Table partitioning

The BigQuery catalog supports partitioned tables. Users can create partitioned tables with specific partitioning attributes through the API.

| Gravitino Partition Transform   | BigQuery Partition Strategy                                                                        |
|---------------------------------|----------------------------------------------------------------------------------------------------|
| `Transforms.day("column")`      | Daily partitioning based on DATE/TIMESTAMP/DATETIME column                                         |
| `Transforms.hour("column")`     | Hourly partitioning based on TIMESTAMP/DATETIME column                                             |
| `Transforms.month("column")`    | Monthly partitioning based on DATE/TIMESTAMP/DATETIME column                                       |
| `Transforms.year("column")`     | Yearly partitioning based on DATE/TIMESTAMP/DATETIME column                                        |
| `Transforms.identity("column")` | Direct partitioning (only DATE/TIMESTAMP/DATETIME, integer range partitioning not yet implemented) |

:::note
Integer range partitioning is not implemented in the current version.
:::

### Table operations

Refer to [Manage Relational Metadata Using Gravitino](./manage-relational-metadata-using-gravitino.md#table-operations) for more details.

#### Create table examples

<Tabs groupId='language' queryString>
<TabItem value="json" label="Json">

```json
{
  "name": "user_events",
  "comment": "User events table with partitioning and clustering",
  "columns": [
    {
      "name": "user_id",
      "type": "string",
      "nullable": false,
      "comment": "User ID"
    },
    {
      "name": "event_timestamp",
      "type": "timestamp_tz",
      "nullable": false,
      "comment": "Event timestamp"
    },
    {
      "name": "event_type",
      "type": "string",
      "nullable": true,
      "comment": "Event type"
    },
    {
      "name": "event_data",
      "type": "json",
      "nullable": true,
      "comment": "Event data in JSON format"
    }
  ],
  "partitioning": [
    {
      "strategy": "day",
      "fieldName": ["event_timestamp"]
    }
  ],
  "properties": {
    "clustering_fields": "user_id,event_type",
    "partition_expiration_days": "365",
    "require_partition_filter": "true"
  }
}
```

</TabItem>
<TabItem value="java" label="Java">

```java
Column[] columns = new Column[] {
    Column.of("user_id", Types.StringType.get(), "User ID", false, false, null),
    Column.of("event_timestamp", Types.TimestampType.withTimeZone(), "Event timestamp", false, false, null),
    Column.of("event_type", Types.StringType.get(), "Event type", true, false, null),
    Column.of("event_data", Types.ExternalType.of("JSON"), "Event data in JSON format", true, false, null)
};

Transform[] partitioning = new Transform[] {
    Transforms.day("event_timestamp")
};

Map<String, String> properties = ImmutableMap.of(
    "clustering_fields", "user_id,event_type",
    "partition_expiration_days", "365",
    "require_partition_filter", "true"
);

Table table = catalog.asTableCatalog()
    .createTable(
        NameIdentifier.of("schema_name", "user_events"),
        columns,
        "User events table with partitioning and clustering",
        properties,
        partitioning
    );
```

</TabItem>
</Tabs>

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

## Limitations and Considerations

### Current Limitations

1. **Feature Limitations**
   - Integer range partitioning not implemented
   - External tables, views, table clones not supported
   - Dataset properties like `failover_reservation`, `is_primary`, `primary_replica`, `collate` not supported
   - Web UI does not support table partitioning and clustering (API only)
   - Web UI does not support complex data types (ARRAY, STRUCT, GEOGRAPHY, JSON, RANGE, BIGNUMERIC) (API only)
   - INTERVAL type currently not supported
   - Unsupported data types display as "external" in Web UI
   - JDBC driver must be manually downloaded and installed (not included in distribution)

2. **Performance Considerations**
   - Table metadata loading uses JDBC which may be slower than native API calls

### Recommended Usage

- **Simple Scenarios**: Use Web UI to create basic tables and schemas
- **Advanced Scenarios**: Use API for complex configurations (supports BigQuery native data types for table creation)
- **Production Environment**: Recommend using API for batch operations