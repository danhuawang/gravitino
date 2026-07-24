---
title: "Trino Connector: Oracle Catalog"
slug: "/trino-connector/catalog-oracle"
keywords:
- Gravitino
- connector
- Trino
- Oracle
license: "Copyright 2026 Datastrato Pvt Ltd. This software is licensed under the Apache License version 2."
---

## Introduction

The Oracle catalog allows querying and creating tables in an external Oracle database.
This can be used to join data between different systems like Oracle and Hive, or between different Oracle instances.

## Requirements

To connect to Oracle, you need:
- Oracle 11g Release 2 (11.2.x). Oracle 12c+ may also work but is not tested.
- Network access from the Trino coordinator and workers to Oracle. Port 1521 is the default port.

## Case sensitivity

The Oracle catalog follows Oracle's own native case-folding rule: an unquoted identifier folds to
uppercase, while a quoted identifier (e.g. `"MyTable"`) preserves its exact case. This folding is
applied automatically on the Gravitino server side, so no case conversion is needed in the Trino
connector itself. See [Oracle catalog: Case sensitivity](../jdbc-oracle-catalog.md#case-sensitivity)
for details.

## Create Table

At present, the Apache Gravitino Trino connector only supports basic Oracle table creation statements, which involve fields, null allowances, and comments. It does not support advanced features like indexes, default values, and auto-increment (Oracle 11g has no Identity Columns).
The Gravitino Trino connector supports `CREATE TABLE AS SELECT`.

The following Gravitino data types have special handling when mapped to Trino types for Oracle:
- Oracle-specific types with no clean Trino equivalent (e.g. `NUMBER` with no precision/scale, `NCHAR`, `NVARCHAR2`, `ROWID`) are exposed in Trino as an unbounded `VARCHAR`.
- A Gravitino `DATE` column is exposed in Trino as `TIMESTAMP(3)` on read, because Oracle `DATE` is a datetime (date + time to second precision), not a pure date.
- `TIME` columns are not supported; Oracle has no native `TIME` type.

:::note
`CREATE OR REPLACE TABLE AS SELECT` is not supported. Use `DROP TABLE` followed by `CREATE TABLE AS SELECT` as an alternative.
:::

## Alter Table

Gravitino Trino connector supports the following alter table operations:
- Rename table
- Add a column
- Drop a column
- Rename a column
- Change a column type
- Set a table comment
- Set a column comment

## Select

The Gravitino Trino connector supports most SELECT statements, allowing the execution of queries successfully.
It doesn't support certain query optimizations, such as indexes and pushdowns.

## Table and Schema Properties

Only the `comment` property is supported on Oracle tables. Oracle schemas do not support properties.

## Examples

Complete the following steps before you can use the Oracle catalog in Trino through Gravitino:

- Create a metalake and catalog in Gravitino. Assuming that the metalake name is `test` and the catalog name is `oracle_test`, then you can use the following code to create them in Gravitino:

```bash
curl -X POST -H "Content-Type: application/json" \
-d '{
  "name": "test",
  "comment": "comment",
  "properties": {}
}' http://gravitino-host:8090/api/metalakes

curl -X POST -H "Content-Type: application/json" \
-d '{
  "name": "oracle_test",
  "type": "RELATIONAL",
  "comment": "comment",
  "provider": "jdbc-oracle",
  "properties": {
    "jdbc-url": "jdbc:oracle:thin:@//oracle-host:1521/mydb",
    "jdbc-user": "<user>",
    "jdbc-password": "<password>",
    "jdbc-driver": "oracle.jdbc.OracleDriver"
  }
}' http://gravitino-host:8090/api/metalakes/test/catalogs
```
For more information about the Oracle catalog, refer to [Oracle catalog](../jdbc-oracle-catalog.md).

- Set the value of configuration `gravitino.metalake` to the metalake you have created, named 'test', and start the Trino container.

Use the Trino CLI to connect to the Trino container and run a query.

Listing all Gravitino managed catalogs:

```sql
SHOW CATALOGS;
```

The results are similar to:

```text
    Catalog
----------------
 gravitino
 jmx
 system
 oracle_test
(4 rows)
```

The `gravitino` catalog is a catalog defined by Trino catalog configuration.
The `oracle_test` catalog is the catalog created by you in Gravitino.
Other catalogs are regular user-configured Trino catalogs.

### Create Tables and Schemas

Oracle schemas map to Oracle users, which must already exist in the target Oracle database; the Oracle catalog does not support creating or dropping schemas.

Create a new table named `table_01` in schema `oracle_test.gravitino`.

```sql
CREATE TABLE oracle_test.gravitino.table_01
(
name varchar(200),
salary decimal(10, 2)
);
```

### Write Data

Insert data into the table `table_01`:

```sql
INSERT INTO oracle_test.gravitino.table_01 (name, salary) VALUES ('ice', 12.00);
```

Insert data into the table `table_01` from select:

```sql
INSERT INTO oracle_test.gravitino.table_01 (name, salary) SELECT * FROM oracle_test.gravitino.table_01;
```

### Query Data

Query the `table_01` table:

```sql
SELECT * FROM oracle_test.gravitino.table_01;
```

### Modify a Table

Add a new column `age` to the `table_01` table:

```sql
ALTER TABLE oracle_test.gravitino.table_01 ADD COLUMN age int;
```

Drop a column `age` from the `table_01` table:

```sql
ALTER TABLE oracle_test.gravitino.table_01 DROP COLUMN age;
```

Rename the `table_01` table to `table_02`:

```sql
ALTER TABLE oracle_test.gravitino.table_01 RENAME TO oracle_test.gravitino.table_02;
```

### Drop

Drop a table:

```sql
DROP TABLE oracle_test.gravitino.table_01;
```
