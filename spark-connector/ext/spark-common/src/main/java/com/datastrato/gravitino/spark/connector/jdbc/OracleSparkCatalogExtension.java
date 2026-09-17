/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.spark.connector.jdbc;

/** Registers the Oracle catalog with the Gravitino Spark connector. */
public class OracleSparkCatalogExtension extends VersionedSparkCatalogExtension {

  /** Creates the extension; instantiated by {@code ServiceLoader}. */
  public OracleSparkCatalogExtension() {
    super(
        "jdbc-oracle",
        "com.datastrato.gravitino.spark.connector.jdbc.oracle.GravitinoOracleCatalogSpark");
  }
}
