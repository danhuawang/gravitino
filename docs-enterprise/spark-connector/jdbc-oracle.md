---
title: "Spark Oracle JDBC catalog"
slug: /spark-connector-jdbc-oracle
keywords:
- spark
- jdbc
- Oracle
license: "Copyright 2026 Datastrato Inc."
---

## Introduction

Apache Gravitino Spark connector supports Oracle as a JDBC catalog. This document describes how to configure and use the Oracle JDBC catalog with Spark.

:::note
- Oracle schemas map directly to Oracle database users. Schema creation and deletion are not supported through Spark SQL `CREATE DATABASE` / `DROP DATABASE` because they require DBA privileges. Schemas must be created in advance by a DBA.
- Table properties and schema comments are not supported.
- Column reordering in `ALTER TABLE` is not supported.
:::

:::note
Gravitino folds unquoted Oracle schema, table, and column names to uppercase logical names, regardless of the case used to create them — `app_user`, `APP_USER`, and `App_User` all resolve to the same schema `APP_USER`. `SHOW DATABASES`, `SHOW TABLES`, and `DESC`/`SHOW COLUMNS` all report the uppercase form, even for objects created with lowercase names. Referencing schemas, tables, or columns in SQL or a `DataFrame` works with any case, since Spark resolves identifiers case-insensitively by default.

Quoted, case-sensitive identifiers (such as `"MyTable"`) are not supported through the Spark connector.
:::

:::caution
Place the Oracle JDBC driver (`ojdbc8-23.x.x.x.jar`) on the Spark classpath. The driver is available from the [Oracle Maven repository](https://mvnrepository.com/artifact/com.oracle.database.jdbc/ojdbc8).
:::

## Catalog properties

Gravitino Spark connector transforms the following catalog property names to Spark JDBC connector configuration:

| Gravitino catalog property name | Spark JDBC connector configuration | Description                                                                   | Since Version |
|---------------------------------|------------------------------------|-------------------------------------------------------------------------------|---------------|
| `jdbc-url`                      | `url`                              | JDBC URL for connecting to Oracle, e.g. `jdbc:oracle:thin:@host:1521/service` | 0.3.0         |
| `jdbc-user`                     | `jdbc.user`                        | JDBC user name                                                                | 0.3.0         |
| `jdbc-password`                 | `jdbc.password`                    | JDBC password                                                                 | 0.3.0         |
| `jdbc-driver`                   | `driver`                           | The driver of the JDBC connection, e.g. `oracle.jdbc.OracleDriver`            | 0.3.0         |

Gravitino catalog property names with the prefix `spark.bypass.` are passed to the Spark JDBC connector.

## SQL example

```sql
-- Suppose jdbc_oracle is the Oracle catalog name managed by Gravitino,
-- and APP_USER is an existing Oracle user/schema.
USE jdbc_oracle;

-- SHOW DATABASES returns Gravitino's uppercase logical schema name.
SHOW DATABASES;
-- +----------+
-- | APP_USER |
-- +----------+

USE APP_USER;

CREATE TABLE IF NOT EXISTS employee (
  id INT,
  name STRING,
  age INT
);

SHOW TABLES;
-- +----------+
-- | EMPLOYEE |
-- +----------+

-- DESC reports Oracle's physical (uppercase) table and column names.
DESC TABLE employee;
-- +--------+---------+
-- |   name |    type |
-- +--------+---------+
-- |     ID |     int |
-- |   NAME |  string |
-- |    AGE |     int |
-- +--------+---------+

INSERT INTO employee VALUES (1, 'Alice', 30);

SELECT * FROM employee;
-- +----+-------+-----+
-- | id |  name | age |
-- +----+-------+-----+
-- |  1 | Alice |  30 |
-- +----+-------+-----+
```
