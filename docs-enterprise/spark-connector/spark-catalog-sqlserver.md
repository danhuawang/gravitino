---
title: "Spark Connector: SQL Server Catalog"
slug: /spark-connector/spark-catalog-sqlserver
keywords:
- spark connector
- jdbc
- SQL Server
license: "Copyright 2026 Datastrato Pvt Ltd. This software is licensed under the Apache License version 2."
---

## Introduction

The Apache Gravitino Spark connector offers the capability to read SQL Server tables, with the metadata managed by the Gravitino server.

## Preparation

Download the [Microsoft JDBC Driver for SQL Server](https://learn.microsoft.com/en-us/sql/connect/jdbc/download-microsoft-jdbc-driver-for-sql-server) (`mssql-jdbc`) jar to the Spark classpath.

## Capabilities

Use the `jdbc-sqlserver` provider for SQL Server catalogs.

SQL Server catalogs do not support the following operations through the Spark connector:

- `CLUSTERED BY`
- Partitioned tables
- `DELETE`
- Schema evolution (adding/dropping/renaming/updating columns)
- Replacing columns
- Schema and table properties
- Complex types (array, map, struct)
- Updating column position
- Creating a table with a comment
- Functions (UDFs)

## SQL Example

```sql
-- Suppose jdbc_sqlserver is the SQL Server catalog name managed by Gravitino,
-- and mydatabase is an existing SQL Server schema.
USE jdbc_sqlserver.mydatabase;

CREATE TABLE IF NOT EXISTS employee (
  id INT,
  name STRING,
  age INT
);

SHOW TABLES;
SELECT * FROM employee;
```

## Catalog Properties

The Gravitino Spark connector converts the following Gravitino catalog properties to Spark JDBC connector configuration.

| Gravitino catalog property name | Spark JDBC connector configuration | Description                                                                                               | Since Version |
|---------------------------------|------------------------------------|-----------------------------------------------------------------------------------------------------------|---------------|
| `jdbc-url`                      | `url`                              | JDBC URL for connecting to the database. For example, `jdbc:sqlserver://localhost:1433;databaseName=mydb` | 0.3.0         |
| `jdbc-user`                     | `jdbc.user`                        | JDBC user name                                                                                            | 0.3.0         |
| `jdbc-password`                 | `jdbc.password`                    | JDBC password                                                                                             | 0.3.0         |
| `jdbc-driver`                   | `driver`                           | The driver of the JDBC connection. For example, `com.microsoft.sqlserver.jdbc.SQLServerDriver`            | 0.3.0         |

Gravitino catalog property names with the prefix `spark.bypass.` are passed to Spark JDBC connector.
