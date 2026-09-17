/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.spark.connector.jdbc;

import com.datastrato.gravitino.spark.connector.jdbc.oracle.GravitinoOracleCatalogSpark34;
import com.datastrato.gravitino.spark.connector.jdbc.sqlserver.GravitinoSqlServerCatalogSpark34;
import org.apache.gravitino.spark.connector.version.CatalogNameAdaptor;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestSparkCatalogExtensionRegistration {

  @Test
  void testOracleAndSqlServerResolveThroughExtension() {
    Assertions.assertEquals(
        GravitinoOracleCatalogSpark34.class.getName(),
        CatalogNameAdaptor.getCatalogName("jdbc-oracle"));
    Assertions.assertEquals(
        GravitinoSqlServerCatalogSpark34.class.getName(),
        CatalogNameAdaptor.getCatalogName("jdbc-sqlserver"));
  }
}
