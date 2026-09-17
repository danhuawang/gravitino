/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.flink.connector.jdbc;

/** Flink catalog type identifiers of the JDBC catalogs this extension contributes. */
public class ExtJdbcCatalogFactoryOptions {

  /** Identifier for the Oracle catalog. */
  public static final String ORACLE_IDENTIFIER = "gravitino-jdbc-oracle";

  /** Identifier for the SQL Server catalog. */
  public static final String SQLSERVER_IDENTIFIER = "gravitino-jdbc-sqlserver";

  private ExtJdbcCatalogFactoryOptions() {}
}
