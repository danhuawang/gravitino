---
title: "Flink SQL Server JDBC catalog"
slug: /flink-connector-jdbc-sqlserver
keywords:
- flink
- jdbc
- SQL Server
license: "Copyright 2026 Datastrato Pvt Ltd. This software is licensed under the Apache License version 2."
---

## Introduction

Apache Gravitino Flink connector supports SQL Server as a JDBC catalog. This document describes how to configure and use the SQL Server JDBC catalog with Flink.

:::note
- SQL Server supports schema, table, primary key, comment, default value, and column operations through the Flink connector.
- SQL Server does not support schema comments/options, table properties, partitioning, distribution, purge, drop schema cascade, or column position changes.
:::

:::caution
Place the SQL Server JDBC driver (`mssql-jdbc-12.x.x.jreX.jar`) in the Flink `lib` directory. The driver is available from the [Microsoft Maven repository](https://mvnrepository.com/artifact/com.microsoft.sqlserver/mssql-jdbc).
:::

## Catalog properties

Gravitino Flink connector transforms the Flink `CREATE CATALOG WITH (...)` option names below to the Gravitino catalog property names used to persist the catalog.

| Gravitino catalog property name | Flink WITH option | Description                                                                                     | Since Version    |
|---------------------------------|-------------------|-------------------------------------------------------------------------------------------------|------------------|
| `jdbc-url`                      | `base-url`        | SQL Server JDBC URL, e.g. `jdbc:sqlserver://host:1433;encrypt=true;trustServerCertificate=true` | 0.9.0-incubating |
| `jdbc-database`                 | `jdbc-database`   | SQL Server database name. Must be consistent with `databaseName` in `jdbc-url` if both are set  | 0.9.0-incubating |
| `jdbc-user`                     | `username`        | SQL Server user name                                                                            | 0.9.0-incubating |
| `jdbc-password`                 | `password`        | Password of the SQL Server user                                                                 | 0.9.0-incubating |

:::note
When creating the SQL Server catalog in Gravitino, add the `flink.bypass.default-database` property set to the SQL Server schema name to use (e.g. `DBO`).

```text
jdbc-url=jdbc:sqlserver://localhost:1433;databaseName=db;encrypt=true;trustServerCertificate=true
jdbc-database=db
flink.bypass.default-database=DBO
```
:::

## SQL example

```sql
-- Suppose sqlserver_catalog is the SQL Server catalog name managed by Gravitino,
-- or create it directly from Flink SQL:

CREATE CATALOG sqlserver_catalog WITH (
  'type' = 'gravitino-jdbc-sqlserver',
  'base-url' = 'jdbc:sqlserver://localhost:1433;encrypt=true;trustServerCertificate=true',
  'jdbc-database' = 'db',
  'username' = 'sa',
  'password' = 'YourStrong!Passw0rd',
  'default-database' = 'DBO'
);

USE CATALOG sqlserver_catalog;

SHOW DATABASES;
-- +------------------+
-- |    database name |
-- +------------------+
-- |              DBO |
-- +------------------+

USE DBO;
-- [INFO] Execute statement succeed.

SET 'execution.runtime-mode' = 'batch';
-- [INFO] Execute statement succeed.

SET 'sql-client.execution.result-mode' = 'tableau';
-- [INFO] Execute statement succeed.

SHOW TABLES;
-- Empty set

-- SQL Server's default collation is case-insensitive, so identifiers are
-- shown here in uppercase.
CREATE TABLE ORDERS (
   ORDER_ID BIGINT NOT NULL PRIMARY KEY NOT ENFORCED,
   AMOUNT   BIGINT
);
-- [INFO] Execute statement succeed.

SHOW TABLES;
-- +--------+
-- |  table |
-- +--------+
-- | ORDERS |
-- +--------+

INSERT INTO ORDERS VALUES (1, 100);

SELECT * FROM ORDERS ORDER BY ORDER_ID;
-- +----------+--------+
-- | ORDER_ID | AMOUNT |
-- +----------+--------+
-- |        1 |    100 |
-- +----------+--------+
```
