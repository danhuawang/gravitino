/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.flink.connector.jdbc;

import com.datastrato.gravitino.flink.connector.jdbc.oracle.GravitinoOracleJdbcCatalogFactoryFlink119;
import com.datastrato.gravitino.flink.connector.jdbc.sqlserver.GravitinoSqlServerJdbcCatalogFactoryFlink119;
import org.apache.flink.table.factories.CatalogFactory;
import org.apache.flink.table.factories.FactoryUtil;
import org.apache.gravitino.flink.connector.catalog.BaseCatalogFactory;
import org.apache.gravitino.flink.connector.utils.FactoryUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestExtCatalogFactoryRegistration {

  @Test
  void testCatalogTypeIdentifiers() {
    Assertions.assertEquals(
        "gravitino-jdbc-oracle", ExtJdbcCatalogFactoryOptions.ORACLE_IDENTIFIER);
    Assertions.assertEquals(
        "gravitino-jdbc-sqlserver", ExtJdbcCatalogFactoryOptions.SQLSERVER_IDENTIFIER);
  }

  @Test
  void testExtCatalogTypesAreGravitinoManaged() {
    Assertions.assertTrue(
        FactoryUtils.isGravitinoManagedCatalogType(ExtJdbcCatalogFactoryOptions.ORACLE_IDENTIFIER));
    Assertions.assertTrue(
        FactoryUtils.isGravitinoManagedCatalogType(
            ExtJdbcCatalogFactoryOptions.SQLSERVER_IDENTIFIER));
  }

  @Test
  void testEachCatalogTypeResolvesToOneVersionedFactory() {
    // FactoryUtil.discoverFactory throws when zero or several factories match the identifier.
    CatalogFactory oracle =
        FactoryUtil.discoverFactory(
            getClass().getClassLoader(),
            CatalogFactory.class,
            ExtJdbcCatalogFactoryOptions.ORACLE_IDENTIFIER);
    Assertions.assertInstanceOf(GravitinoOracleJdbcCatalogFactoryFlink119.class, oracle);
    Assertions.assertEquals(
        "jdbc-oracle", ((BaseCatalogFactory) oracle).gravitinoCatalogProvider());

    CatalogFactory sqlServer =
        FactoryUtil.discoverFactory(
            getClass().getClassLoader(),
            CatalogFactory.class,
            ExtJdbcCatalogFactoryOptions.SQLSERVER_IDENTIFIER);
    Assertions.assertInstanceOf(GravitinoSqlServerJdbcCatalogFactoryFlink119.class, sqlServer);
    Assertions.assertEquals(
        "jdbc-sqlserver", ((BaseCatalogFactory) sqlServer).gravitinoCatalogProvider());
  }
}
