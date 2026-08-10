---
title: "Manage Relational Metadata"
slug: "/manage-relational-metadata-using-gravitino"
keyword: "table management, table, column, relational metadata, Gravitino"
license: "This software is licensed under the Apache License version 2."
---

import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

## Introduction

This page covers the Gravitino API for tables. For what a table is, how columns and properties work,
the drop versus purge distinction, and how to work with tables in the UI, see [Tables and
Views](./tables-and-views.md). For creating the catalog and schema a table lives in, see [Manage
Catalogs and Schemas](./manage-catalogs-and-schemas.md). Views have their own page, [Manage View
Metadata](./manage-view-metadata-using-gravitino.md).

The examples below use a Hive catalog. Column types, table properties, and supported operations vary
by provider, and each catalog type documents its own: [Apache Hive](./apache-hive-catalog.md),
[MySQL](./jdbc-mysql-catalog.md), [PostgreSQL](./jdbc-postgresql-catalog.md), [Apache
Doris](./jdbc-doris-catalog.md), [StarRocks](./jdbc-starrocks-catalog.md),
[OceanBase](./jdbc-oceanbase-catalog.md), [Hologres](./jdbc-hologres-catalog.md),
[ClickHouse](./jdbc-clickhouse-catalog.md), [Apache Iceberg](./lakehouse-iceberg-catalog.md),
[Apache Paimon](./lakehouse-paimon-catalog.md), [Apache Hudi](./lakehouse-hudi-catalog.md), and
[Lakehouse generic](./lakehouse-generic-catalog.md).

## Table Operations

### Create a Table

A table needs a name and its columns. Partitioning, distribution, sort order, indexes, and
properties are all optional, and which of them a catalog accepts depends on the provider.

<Tabs groupId='language' queryString>
<TabItem value="shell" label="REST">

```shell
curl -X POST -H "Accept: application/vnd.gravitino.v1+json" \
  -H "Content-Type: application/json" -d '{
  "name": "customers",
  "comment": "Customer records",
  "columns": [
    {
      "name": "id",
      "type": "integer",
      "comment": "Primary key",
      "nullable": false,
      "autoIncrement": true
    },
    {
      "name": "name",
      "type": "varchar(500)",
      "comment": "Customer name",
      "nullable": true
    },
    {
      "name": "created_at",
      "type": "timestamp",
      "nullable": false,
      "defaultValue": {
        "type": "function",
        "funcName": "current_timestamp",
        "funcArgs": []
      }
    }
  ],
  "properties": {"format": "ORC"}
}' http://localhost:8090/api/metalakes/example/catalogs/sales/schemas/public/tables
```

</TabItem>
<TabItem value="java" label="Java">

```java
Catalog catalog = client.loadCatalog("sales");

Column[] columns = new Column[] {
    Column.of("id", Types.IntegerType.get(), "Primary key", false, true, null),
    Column.of("name", Types.VarCharType.of(500), "Customer name"),
    Column.of("created_at", Types.TimestampType.withoutTimeZone(), null, false, false,
        FunctionExpression.of("current_timestamp"))
};

Table table = catalog.asTableCatalog().createTable(
    NameIdentifier.of("public", "customers"),
    columns,
    "Customer records",
    ImmutableMap.of("format", "ORC"));
```

</TabItem>
<TabItem value="python" label="Python">

```python
catalog = client.load_catalog("sales")

columns = [
    Column.of("id", Types.IntegerType.get(), "Primary key", False, True, None),
    Column.of("name", Types.VarCharType.of(500), "Customer name"),
    Column.of("created_at", Types.TimestampType.without_time_zone(), None, False),
]

table = catalog.as_table_catalog().create_table(
    ident=NameIdentifier.of("public", "customers"),
    columns=columns,
    comment="Customer records",
    properties={"format": "ORC"})
```

</TabItem>
</Tabs>

<<<<<<< HEAD
:::caution
The provided example demonstrates table creation but isn't directly executable in Gravitino, since not all catalogs fully support these capabilities.
:::

To create a table, you need to provide the following information:

- Table column name and type
- Table column default value (optional)
- Table column auto-increment (optional)
- Table property (optional)

#### Table Column Type

The following types that Gravitino supports:

| Type                      | Java / Python                                                            | JSON                                                                                                                                 | Description                                                                                                                                                                |
|---------------------------|--------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Boolean                   | `Types.BooleanType.get()`                                                | `boolean`                                                                                                                            | Boolean type                                                                                                                                                               |
| Byte                      | `Types.ByteType.get()`                                                   | `byte`                                                                                                                               | Byte type, indicates a numerical value of 1 byte                                                                                                                           |
| Unsigned Byte             | `Types.ByteType.unsigned()`                                              | `byte unsigned`                                                                                                                      | Unsigned Byte type, indicates a unsigned numerical value of 1 byte                                                                                                         |
| Short                     | `Types.ShortType.get()`                                                  | `short`                                                                                                                              | Short type, indicates a numerical value of 2 bytes                                                                                                                         |
| Unsigned Short            | `Types.ShortType.unsigned()`                                             | `short unsigned`                                                                                                                     | Unsigned Short type, indicates a unsigned numerical value of 2 bytes                                                                                                       |
| Integer                   | `Types.IntegerType.get()`                                                | `integer`                                                                                                                            | Integer type, indicates a numerical value of 4 bytes                                                                                                                       |
| Unsigned Integer          | `Types.IntegerType.unsigned()`                                           | `integer unsigned`                                                                                                                   | Unsigned Integer type, indicates a unsigned numerical value of 4 bytes                                                                                                     |
| Long                      | `Types.LongType.get()`                                                   | `long`                                                                                                                               | Long type, indicates a numerical value of 8 bytes                                                                                                                          |
| Unsigned Long             | `Types.LongType.unsigned()`                                              | `long unsigned`                                                                                                                      | Unsigned Long type, indicates a unsigned numerical value of 8 bytes                                                                                                        |
| Float                     | `Types.FloatType.get()`                                                  | `float`                                                                                                                              | Float type, indicates a single-precision floating point number                                                                                                             |
| Double                    | `Types.DoubleType.get()`                                                 | `double`                                                                                                                             | Double type, indicates a double-precision floating point number                                                                                                            |
| Decimal(precision, scale) | `Types.DecimalType.of(precision, scale)`                                 | `decimal(p, s)`                                                                                                                      | Decimal type, indicates a fixed-precision decimal number with the constraint that the precision must be in range `[1, 38]` and the scala must be in range `[0, precision]` |
| String                    | `Types.StringType.get()`                                                 | `string`                                                                                                                             | String type                                                                                                                                                                |
| FixedChar(length)         | `Types.FixedCharType.of(length)`                                         | `char(l)`                                                                                                                            | Char type, indicates a fixed-length string                                                                                                                                 |
| VarChar(length)           | `Types.VarCharType.of(length)`                                           | `varchar(l)`                                                                                                                         | Varchar type, indicates a variable-length string, the length is the maximum length of the string                                                                           |
| Timestamp(p)              | `Types.TimestampType.withoutTimeZone(p)`                                 | `timestamp(p)`                                                                                                                       | Timestamp type, indicates a timestamp without timezone, where p is the precision of fractional seconds (0-12)                                                              |
| TimestampWithTimezone(p)  | `Types.TimestampType.withTimeZone(p)`                                    | `timestamp_tz(p)`                                                                                                                    | Timestamp with timezone type, indicates a timestamp with timezone, where p is the precision of fractional seconds (0-12)                                                   |
| Date                      | `Types.DateType.get()`                                                   | `date`                                                                                                                               | Date type                                                                                                                                                                  |
| Time                      | `Types.TimeType.withoutTimeZone()`                                       | `time`                                                                                                                               | Time type                                                                                                                                                                  |
| IntervalToYearMonth       | `Types.IntervalYearType.get()`                                           | `interval_year`                                                                                                                      | Interval type, indicates an interval of year and month                                                                                                                     |
| IntervalToDayTime         | `Types.IntervalDayType.get()`                                            | `interval_day`                                                                                                                       | Interval type, indicates an interval of day and time                                                                                                                       |
| Fixed(length)             | `Types.FixedType.of(length)`                                             | `fixed(l)`                                                                                                                           | Fixed type, indicates a fixed-length binary array                                                                                                                          |
| Binary                    | `Types.BinaryType.get()`                                                 | `binary`                                                                                                                             | Binary type, indicates a arbitrary-length binary array                                                                                                                     |
| List                      | `Types.ListType.of(elementType, elementNullable)`                        | `{"type": "list", "containsNull": JSON Boolean, "elementType": type JSON}`                                                           | List type, indicate a list of elements with the same type                                                                                                                  |
| Map                       | `Types.MapType.of(keyType, valueType)`                                   | `{"type": "map", "keyType": type JSON, "valueType": type JSON, "valueContainsNull": JSON Boolean}`                                   | Map type, indicate a map of key-value pairs                                                                                                                                |
| Struct                    | `Types.StructType.of([Types.StructType.Field.of(name, type, nullable)])` | `{"type": "struct", "fields": [JSON StructField, {"name": string, "type": type JSON, "nullable": JSON Boolean, "comment": string}]}` | Struct type, indicate a struct of fields                                                                                                                                   |
| Union                     | `Types.UnionType.of([type1, type2, ...])`                                | `{"type": "union", "types": [type JSON, ...]}`                                                                                       | Union type, indicates a union of types                                                                                                                                     |
| UUID                      | `Types.UUIDType.get()`                                                   | `uuid`                                                                                                                               | UUID type, indicates a universally unique identifier                                                                                                                       |

The related java doc is [here](pathname:///docs/1.3.0-SNAPSHOT/api/java/org/apache/gravitino/rel/types/Type.html).

##### External type

External type is a special type of column type, when you need to use a data type that is not in the Gravitino type
system, and you explicitly know its string representation in an external catalog (usually used in JDBC catalogs), then
you can use the ExternalType to represent the type. Similarly, if the original type is unsolvable, it will be
represented by ExternalType.
The following shows the data structure of an external type in JSON and Java, enabling easy retrieval of its string value.

<Tabs groupId='language' queryString>
  <TabItem value="Json" label="JSON">

```json
{
  "type": "external",
  "catalogString": "user-defined"
}
```

  </TabItem>
  <TabItem value="java" label="Java">

```java
// The result of the following type is a string "user-defined"
String typeString = ((ExternalType) type).catalogString();
```

  </TabItem>
</Tabs>

##### Unparsed type

Unparsed type is a special type of column type, it used to address compatibility issues in type serialization and
deserialization between the server and client. For instance, if a new column type is introduced on the Gravitino server
that the client does not recognize, it will be treated as an unparsed type on the client side.
The following shows the data structure of an unparsed type in JSON and Java, enabling easy retrieval of its value.

<Tabs groupId='language' queryString>
  <TabItem value="Json" label="JSON">

```json
{
  "type": "unparsed",
  "unparsedType": "unknown-type"
}
```

  </TabItem>
  <TabItem value="java" label="Java">

```java
// The result of the following type is a string "unknown-type"
String unparsedValue = ((UnparsedType) type).unparsedType();
```

  </TabItem>
</Tabs>

#### Table Column Default Value

When defining a table column, you can specify a [literal](./expression.md#literal) or an [expression](./expression.md) as the default value. The default value typically applies to new rows that are inserted into the table by the underlying catalog.

The following is a table of the column default value that Gravitino supports for different catalogs:

| Catalog provider     | Supported default value |
|----------------------|-------------------------|
| `hive`               | &#10008;                |
| `lakehouse-iceberg`  | &#10008;                |
| `lakehouse-paimon`   | &#10008;                |
| `lakehouse-hudi`     | &#10008;                |
| `jdbc-mysql`         | &#10004;                |
| `jdbc-postgresql`    | &#10004;                |
| `jdbc-doris`         | &#10004;                |
| `jdbc-oceanbase`     | &#10004;                |
| `jdbc-hologres`      | &#10004;                |
| `jdbc-starrocks`     | &#10004;                |
| `jdbc-clickhouse`    | &#10004;                |
| `jdbc-hologres`      | &#10004;                |
| `lakehouse-generic`  | &#10008;                |

#### Table Column Auto-increment

Auto-increment provides a convenient way to ensure that each row in a table has a unique identifier without the need for manually managing identifier allocation.
The following table shows the column auto-increment that Gravitino supports for different catalogs:

| Catalog provider    | Supported auto-increment                                                         |
|---------------------|----------------------------------------------------------------------------------|
| `hive`              | &#10008;                                                                         |
| `lakehouse-iceberg` | &#10008;                                                                         |
| `lakehouse-paimon`  | &#10008;                                                                         |
| `lakehouse-hudi`    | &#10008;                                                                         |
| `jdbc-mysql`        | &#10004;([limitations](./jdbc-mysql-catalog.md#table-column-auto-increment))     |
| `jdbc-postgresql`   | &#10004;                                                                         |
| `jdbc-doris`        | &#10008;                                                                         |
| `jdbc-oceanbase`    | &#10004;([limitations](./jdbc-oceanbase-catalog.md#table-column-auto-increment)) |
| `jdbc-hologres`     | &#10008;                                                                         |
| `jdbc-starrocks`    | &#10004;                                                                         |
| `jdbc-clickhouse`   | &#10008;                                                                         |
| `jdbc-hologres`     | &#10008;                                                                         |
| `lakehouse-generic` | &#10008;                                                                         |

#### Table Property and Type Mapping

The following is the table property that Gravitino supports:

| Catalog provider    | Table property                                                                                                                                                                                                             | Type mapping                                                                                                                                                |
|---------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `hive`              | [Hive table property](./apache-hive-catalog.md#table-properties)                                                                                                                                                           | [Hive type mapping](./apache-hive-catalog.md#table-column-types)                                                                                            |
| `lakehouse-iceberg` | [Iceberg table property](./lakehouse-iceberg-catalog.md#table-properties)                                                                                                                                                  | [Iceberg type mapping](./lakehouse-iceberg-catalog.md#table-column-types)                                                                                   |
| `lakehouse-paimon`  | [Paimon table property](./lakehouse-paimon-catalog.md#table-properties)                                                                                                                                                    | [Paimon type mapping](./lakehouse-paimon-catalog.md#table-column-types)                                                                                     |
| `lakehouse-hudi`    | [Hudi table property](./lakehouse-hudi-catalog.md#table-properties)                                                                                                                                                        | [Hudi type mapping](./lakehouse-hudi-catalog.md#table-column-types)                                                                                         |
| `jdbc-mysql`        | [MySQL table property](./jdbc-mysql-catalog.md#table-properties)                                                                                                                                                           | [MySQL type mapping](./jdbc-mysql-catalog.md#table-column-types)                                                                                            |
| `jdbc-postgresql`   | [PostgreSQL table property](./jdbc-postgresql-catalog.md#table-properties)                                                                                                                                                 | [PostgreSQL type mapping](./jdbc-postgresql-catalog.md#table-column-types)                                                                                  |
| `jdbc-doris`        | [Doris table property](./jdbc-doris-catalog.md#table-properties)                                                                                                                                                           | [Doris type mapping](./jdbc-doris-catalog.md#table-column-types)                                                                                            |
| `jdbc-oceanbase`    | [OceanBase table property](./jdbc-oceanbase-catalog.md#table-properties)                                                                                                                                                   | [OceanBase type mapping](./jdbc-oceanbase-catalog.md#table-column-types)                                                                                    |
| `jdbc-hologres`     | [Hologres table property](./jdbc-hologres-catalog.md#table-properties)                                                                                                                                                     | [Hologres type mapping](./jdbc-hologres-catalog.md#table-column-types)                                                                                      |
| `jdbc-starrocks`    | [StarRocks table property](./jdbc-starrocks-catalog.md#table-properties)                                                                                                                                                   | [StarRocks type mapping](./jdbc-starrocks-catalog.md#table-column-types)                                                                                    |
| `jdbc-clickhouse`   | [ClickHouse table property](./jdbc-clickhouse-catalog.md#table-properties)                                                                                                                                                 | [ClickHouse type mapping](./jdbc-clickhouse-catalog.md#table-column-types)                                                                                  |
| `jdbc-hologres`     | [Hologres table property](./jdbc-hologres-catalog.md#table-properties)                                                                                                                                                     | [Hologres type mapping](./jdbc-hologres-catalog.md#table-column-types)                                                                                      |
| `lakehouse-generic` | Lakehouse generic table property depends on specific table implementation, for Lance table, refer to [doc](./lakehouse-generic-lance-table.md#table-properties), other table format, refer to related docs.                | Lakehouse generic type mapping. Similar to table properties, for Lance table, refer to [docs](./lakehouse-generic-lance-table.md#data-type-mappings)        |

#### Table Partitioning, Distribution, Sort Ordering and Indexes

In addition to the basic settings, Gravitino supports the following features:

| Feature             | Description                                                                                                                                                                                                                                                                                    | Java doc                                                                                                                                  |
|---------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------|
| Table partitioning  | Equal to `PARTITION BY` in Apache Hive, It is a partitioning strategy that is used to split a table into parts based on partition keys. Some table engine may not support this feature                                                                                                         | [Partition](pathname:///docs/1.3.0-SNAPSHOT/api/java/org/apache/gravitino/dto/rel/partitioning/Partitioning.html)             |
| Table distribution  | Equal to `CLUSTERED BY` in Apache Hive, distribution a.k.a (Clustering) is a technique to split the data into more manageable files/parts, (By specifying the number of buckets to create). The value of the distribution column will be hashed by a user-defined number into buckets.         | [Distribution](pathname:///docs/1.3.0-SNAPSHOT/api/java/org/apache/gravitino/rel/expressions/distributions/Distribution.html) |
| Table sort ordering | Equal to `SORTED BY` in Apache Hive, sort ordering is a method to sort the data in specific ways such as by a column or a function, and then store table data. it will highly improve the query performance under certain scenarios.                                                           | [SortOrder](pathname:///docs/1.3.0-SNAPSHOT/api/java/org/apache/gravitino/rel/expressions/sorts/SortOrder.html)               |
| Table indexes       | Equal to `KEY/INDEX` in MySQL , unique key enforces uniqueness of values in one or more columns within a table. It ensures that no two rows have identical values in specified columns, thereby facilitating data integrity and enabling efficient data retrieval and manipulation operations. | [Index](pathname:///docs/1.3.0-SNAPSHOT/api/java/org/apache/gravitino/rel/indexes/Index.html)                                 |

For more information, see the related document on [partitioning, bucketing, sorting, and indexes](table-partitioning-distribution-sort-order-indexes.md).

:::note
The code above is an example of creating a Hive table. For other catalogs, the code is similar, but the supported column type, and table properties may be different. For more details, refer to the related doc.
:::
=======
For partitioning, distribution, sort order, and indexes, see [Table partitioning, distribution, sort
order, and indexes](./table-partitioning-distribution-sort-order-indexes.md).
>>>>>>> upstream/branch-1.3

### Load a Table

<Tabs groupId='language' queryString>
<TabItem value="shell" label="REST">

```shell
curl -X GET -H "Accept: application/vnd.gravitino.v1+json" \
  http://localhost:8090/api/metalakes/example/catalogs/sales/schemas/public/tables/customers
```

</TabItem>
<TabItem value="java" label="Java">

```java
Table table = catalog.asTableCatalog().loadTable(
    NameIdentifier.of("public", "customers"));
```

</TabItem>
<TabItem value="python" label="Python">

```python
table = catalog.as_table_catalog().load_table(
    NameIdentifier.of("public", "customers"))
```

</TabItem>
</Tabs>

### Alter a Table

Changes are applied as a list in one request, and cover the table itself, its properties, and its
columns.

| Change                        | JSON                                                                                            | Java                                          |
|-------------------------------|-------------------------------------------------------------------------------------------------|-----------------------------------------------|
| Rename the table              | `{"@type":"rename","newName":"table_renamed"}`                                                  | `TableChange.rename(...)`                     |
| Move to another schema        | `{"@type":"rename","newName":"table_renamed","newSchemaName":"new_schema"}`                     | `TableChange.rename(...)`                     |
| Update the comment            | `{"@type":"updateComment","newComment":"new_comment"}`                                          | `TableChange.updateComment(...)`              |
| Set a property                | `{"@type":"setProperty","property":"key1","value":"value1"}`                                    | `TableChange.setProperty(...)`                |
| Remove a property             | `{"@type":"removeProperty","property":"key1"}`                                                  | `TableChange.removeProperty(...)`             |
| Add a column                  | `{"@type":"addColumn","fieldName":["position"],"type":"varchar(20)","position":"FIRST"}`        | `TableChange.addColumn(...)`                  |
| Delete a column               | `{"@type":"deleteColumn","fieldName":["name"],"ifExists":true}`                                 | `TableChange.deleteColumn(...)`               |
| Rename a column               | `{"@type":"renameColumn","oldFieldName":["name_old"],"newFieldName":"name_new"}`                | `TableChange.renameColumn(...)`               |
| Update a column comment       | `{"@type":"updateColumnComment","fieldName":["name"],"newComment":"new comment"}`               | `TableChange.updateColumnComment(...)`        |
| Update a column type          | `{"@type":"updateColumnType","fieldName":["name"],"newType":"varchar(100)"}`                    | `TableChange.updateColumnType(...)`           |
| Update a column's nullability | `{"@type":"updateColumnNullability","fieldName":["name"],"nullable":true}`                      | `TableChange.updateColumnNullability(...)`    |
| Update a column position      | `{"@type":"updateColumnPosition","fieldName":["name"],"newPosition":"default"}`                 | `TableChange.updateColumnPosition(...)`       |
| Update a column default value | `{"@type":"updateColumnDefaultValue","fieldName":["name"],"newDefaultValue":{...}}`             | `TableChange.updateColumnDefaultValue(...)`   |

Not every provider accepts every change. Where one does not, the request is rejected rather than
silently ignored.

<Tabs groupId='language' queryString>
<TabItem value="shell" label="REST">

```shell
curl -X PUT -H "Accept: application/vnd.gravitino.v1+json" \
  -H "Content-Type: application/json" -d '{
  "updates": [
    {"@type": "updateComment", "newComment": "Customer records, curated"},
    {"@type": "addColumn", "fieldName": ["email"], "type": "varchar(320)", "nullable": true}
  ]
}' http://localhost:8090/api/metalakes/example/catalogs/sales/schemas/public/tables/customers
```

</TabItem>
<TabItem value="java" label="Java">

```java
Table table = catalog.asTableCatalog().alterTable(
    NameIdentifier.of("public", "customers"),
    TableChange.updateComment("Customer records, curated"),
    TableChange.addColumn(new String[] {"email"}, Types.VarCharType.of(320)));
```

</TabItem>
<TabItem value="python" label="Python">

```python
table = catalog.as_table_catalog().alter_table(
    NameIdentifier.of("public", "customers"),
    TableChange.update_comment("Customer records, curated"),
    TableChange.add_column(["email"], Types.VarCharType.of(320)))
```

</TabItem>
</Tabs>

### Drop or Purge a Table

Dropping removes the metadata, and for a managed table the underlying directory as well. For an
external table only the metadata goes. Purging removes the data completely and skips trash, is
rejected on external tables, and is not supported by every catalog.

<Tabs groupId='language' queryString>
<TabItem value="shell" label="REST">

```shell
curl -X DELETE -H "Accept: application/vnd.gravitino.v1+json" \
  "http://localhost:8090/api/metalakes/example/catalogs/sales/schemas/public/tables/customers?purge=false"
```

</TabItem>
<TabItem value="java" label="Java">

```java
boolean dropped = catalog.asTableCatalog().dropTable(
    NameIdentifier.of("public", "customers"));

boolean purged = catalog.asTableCatalog().purgeTable(
    NameIdentifier.of("public", "customers"));
```

</TabItem>
<TabItem value="python" label="Python">

```python
dropped = catalog.as_table_catalog().drop_table(
    NameIdentifier.of("public", "customers"))

purged = catalog.as_table_catalog().purge_table(
    NameIdentifier.of("public", "customers"))
```

</TabItem>
</Tabs>

### List Tables

<Tabs groupId='language' queryString>
<TabItem value="shell" label="REST">

```shell
curl -X GET -H "Accept: application/vnd.gravitino.v1+json" \
  http://localhost:8090/api/metalakes/example/catalogs/sales/schemas/public/tables
```

</TabItem>
<TabItem value="java" label="Java">

```java
NameIdentifier[] identifiers = catalog.asTableCatalog().listTables(
    Namespace.of("public"));
```

</TabItem>
<TabItem value="python" label="Python">

```python
identifiers = catalog.as_table_catalog().list_tables(Namespace.of("public"))
```

</TabItem>
</Tabs>
