---
title: "Trino Connector: SQL Server Catalog"
slug: "/trino-connector/catalog-sqlserver"
keywords:
- Gravitino
- connector
- Trino
- SQLServer
license: "Copyright 2026 Datastrato Inc."
---

## Introduction

The SQL Server catalog allows querying and creating tables in an external Microsoft SQL Server database.
This can be used to join data between different systems like SQL Server and Hive, or between different SQL Server instances.

## Requirements

To connect to SQL Server, you need:
- SQL Server 2008, 2016, 2017, 2019, or 2022.
- Network access from the Trino coordinator and workers to SQL Server. Port 1433 is the default port.

## Create Table

At present, the Apache Gravitino Trino connector only supports basic SQL Server table creation statements, which involve fields, null allowances, and comments. It does not support advanced features like primary keys, indexes, default values, and auto-increment.
The Gravitino Trino connector supports `CREATE TABLE AS SELECT`.

The following Gravitino data types have special handling when mapped to Trino types for SQL Server:
- A Gravitino `FIXED(n)` column (mapped from SQL Server `binary(n)`) is exposed in Trino as `VARBINARY`, because Trino has no fixed-length binary type.
- A Gravitino `EXTERNAL` column (used for SQL Server types without a direct Gravitino equivalent, such as `money`, `xml`, or `datetimeoffset`) is exposed in Trino as an unbounded `VARCHAR` for read-only display purposes.
- A Gravitino `TIMESTAMP` column with a time zone is not supported; SQL Server has no timestamp-with-time-zone type, and attempting to read or create such a column raises an error.

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
- Set a table property

## Select

The Gravitino Trino connector supports most SELECT statements, allowing the execution of queries successfully.
It doesn't support certain query optimizations, such as indexes and pushdowns.

## Table and Schema Properties

Only the `comment` property is supported on SQL Server tables. SQL Server schemas do not support properties.

## Examples

Complete the following steps before you can use the SQL Server catalog in Trino through Gravitino:

- Create a metalake and catalog in Gravitino. Assuming that the metalake name is `test` and the catalog name is `sqlserver_test`, then you can use the following code to create them in Gravitino:

```bash
curl -X POST -H "Content-Type: application/json" \
-d '{
  "name": "test",
  "comment": "comment",
  "properties": {}
}' http://gravitino-host:8090/api/metalakes

curl -X POST -H "Content-Type: application/json" \
-d '{
  "name": "sqlserver_test",
  "type": "RELATIONAL",
  "comment": "comment",
  "provider": "jdbc-sqlserver",
  "properties": {
    "jdbc-url": "jdbc:sqlserver://sqlserver-host:1433;databaseName=mydb",
    "jdbc-user": "<user>",
    "jdbc-password": "<password>",
    "jdbc-database": "mydb",
    "jdbc-driver": "com.microsoft.sqlserver.jdbc.SQLServerDriver"
  }
}' http://gravitino-host:8090/api/metalakes/test/catalogs
```
For more information about the SQL Server catalog, refer to [SQL Server catalog](../jdbc-sqlserver-catalog.md).

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
 sqlserver_test
(4 rows)
```

The `gravitino` catalog is a catalog defined by Trino catalog configuration.
The `sqlserver_test` catalog is the catalog created by you in Gravitino.
Other catalogs are regular user-configured Trino catalogs.

### Create Tables and Schemas

Create a new schema named `database_01` in `sqlserver_test` catalog.

```sql
CREATE SCHEMA sqlserver_test.database_01;
```

Create a new table named `table_01` in schema `sqlserver_test.database_01`.

```sql
CREATE TABLE sqlserver_test.database_01.table_01
(
name varchar(200),
salary int
);
```

### Write Data

Insert data into the table `table_01`:

```sql
INSERT INTO sqlserver_test.database_01.table_01 (name, salary) VALUES ('ice', 12);
```

Insert data into the table `table_01` from select:

```sql
INSERT INTO sqlserver_test.database_01.table_01 (name, salary) SELECT * FROM sqlserver_test.database_01.table_01;
```

### Query Data

Query the `table_01` table:

```sql
SELECT * FROM sqlserver_test.database_01.table_01;
```

### Modify a Table

Add a new column `age` to the `table_01` table:

```sql
ALTER TABLE sqlserver_test.database_01.table_01 ADD COLUMN age int;
```

Drop a column `age` from the `table_01` table:

```sql
ALTER TABLE sqlserver_test.database_01.table_01 DROP COLUMN age;
```

Rename the `table_01` table to `table_02`:

```sql
ALTER TABLE sqlserver_test.database_01.table_01 RENAME TO sqlserver_test.database_01.table_02;
```

### Drop

Drop a schema:

```sql
DROP SCHEMA sqlserver_test.database_01;
```

Drop a table:

```sql
DROP TABLE sqlserver_test.database_01.table_01;
```
