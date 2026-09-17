/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.spark.connector.jdbc;

/** Registers the SQL Server catalog with the Gravitino Spark connector. */
public class SqlServerSparkCatalogExtension extends VersionedSparkCatalogExtension {

  /** Creates the extension; instantiated by {@code ServiceLoader}. */
  public SqlServerSparkCatalogExtension() {
    super(
        "jdbc-sqlserver",
        "com.datastrato.gravitino.spark.connector.jdbc.sqlserver.GravitinoSqlServerCatalogSpark");
  }
}
