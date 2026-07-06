---
title: "Flink Oracle JDBC catalog"
slug: /flink-connector-jdbc-oracle
keywords:
- flink
- jdbc
- Oracle
license: "This software is licensed under the Apache License version 2."
---

## Introduction

Apache Gravitino Flink connector supports Oracle as a JDBC catalog. This document describes how to configure and use the Oracle JDBC catalog with Flink.

:::note
- Oracle schemas map directly to Oracle database users. Schema creation and deletion are not supported because they require DBA privileges. Schemas must be created in advance by a DBA.
- Table properties and schema comments are not supported.
- Column reordering in `ALTER TABLE` is not supported.
:::

:::caution
Place the Oracle JDBC driver (`ojdbc8-23.x.x.x.jar`) in the Flink `lib` directory. The driver is available from the [Oracle Maven repository](https://mvnrepository.com/artifact/com.oracle.database.jdbc/ojdbc8).
:::

## Catalog properties

| Gravitino catalog property name | Description                                                    | Since Version    |
|:--------------------------------|----------------------------------------------------------------|------------------|
| `jdbc-url`                      | Oracle JDBC URL, e.g. `jdbc:oracle:thin:@//host:1521/FREEPDB1` | 0.9.0-incubating |
| `username`                      | Oracle user name (serves as the schema name)                   | 0.9.0-incubating |
| `password`                      | Password of the Oracle user                                    | 0.9.0-incubating |

:::note
When creating the Oracle catalog in Gravitino, add the `flink.bypass.default-database` property set to the Oracle username in **uppercase**. Oracle schemas are users, so the default database must match the username exactly.

```text
flink.bypass.default-database=MYSCHEMA
```
:::

## SQL example

```sql
-- Suppose oracle_cat is the Oracle catalog name managed by Gravitino

USE CATALOG oracle_cat;

-- SHOW DATABASES returns Oracle usernames, which are always uppercase.
SHOW DATABASES;
-- +------------------+
-- |    database name |
-- +------------------+
-- |         MYSCHEMA |
-- +------------------+

USE MYSCHEMA;
-- [INFO] Execute statement succeed.

SET 'execution.runtime-mode' = 'batch';
-- [INFO] Execute statement succeed.

SET 'sql-client.execution.result-mode' = 'tableau';
-- [INFO] Execute statement succeed.

SHOW TABLES;
-- Empty set

-- Table and column names are case-insensitive; the connector normalizes them to uppercase.
CREATE TABLE orders (
   order_id BIGINT NOT NULL PRIMARY KEY NOT ENFORCED,
   amount   BIGINT
);
-- [INFO] Execute statement succeed.

SHOW TABLES;
-- +--------+
-- |  table |
-- +--------+
-- | ORDERS |
-- +--------+

INSERT INTO orders VALUES (1, 100);

SELECT * FROM orders ORDER BY ORDER_ID;
-- +----------+--------+
-- | ORDER_ID | AMOUNT |
-- +----------+--------+
-- |        1 |    100 |
-- +----------+--------+
```
