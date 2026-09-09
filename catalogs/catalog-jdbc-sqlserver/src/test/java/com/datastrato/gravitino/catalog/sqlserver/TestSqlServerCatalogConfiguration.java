/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.catalog.sqlserver;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.connector.CatalogOperations;
import org.apache.gravitino.meta.AuditInfo;
import org.apache.gravitino.meta.CatalogEntity;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** Tests database resolution before catalog operations are initialized. */
class TestSqlServerCatalogConfiguration {

  @ParameterizedTest
  @CsvSource(
      value = {
        "jdbc:sqlserver://localhost:1433;databaseName=demo|demo",
        "jdbc:sqlserver://localhost;DATABASENAME=demo;encrypt=true|demo",
        "jdbc:sqlserver://localhost;databaseName={my;db}|my;db",
        "jdbc:sqlserver://localhost;databaseName={my}}db}|my}db",
        "jdbc:sqlserver://localhost;password={secret;databaseName=fake};databaseName=demo|demo"
      },
      delimiter = '|')
  void testDatabaseFromUrl(String url, String database) {
    Map<String, String> config = Map.of("jdbc-url", url);
    CapturingCatalog catalog = new CapturingCatalog();
    catalog.withCatalogConf(config);
    catalog.withCatalogEntity(
        CatalogEntity.builder()
            .withId(1L)
            .withName("test")
            .withNamespace(Namespace.of("metalake"))
            .withType(Catalog.Type.RELATIONAL)
            .withProvider(catalog.shortName())
            .withAuditInfo(
                AuditInfo.builder().withCreator("test").withCreateTime(Instant.EPOCH).build())
            .build());
    Assertions.assertThrows(UnsupportedOperationException.class, catalog::ops);
    Assertions.assertEquals(database, catalog.config.get("jdbc-database"));
    Assertions.assertEquals(url, catalog.config.get("jdbc-url"));
    Assertions.assertFalse(config.containsKey("jdbc-database"));
  }

  @ParameterizedTest
  @CsvSource(
      value = {
        "jdbc:sqlserver://localhost;databaseName=demo|demo",
        "jdbc:sqlserver://localhost;databaseName={my;db}|my;db",
        "jdbc:sqlserver://localhost|explicit"
      },
      delimiter = '|')
  void testExplicitDatabase(String url, String database) {
    CapturingCatalog catalog = new CapturingCatalog();
    catalog.withCatalogConf(Map.of("jdbc-url", url, "jdbc-database", database));
    catalog.withCatalogEntity(
        CatalogEntity.builder()
            .withId(1L)
            .withName("test")
            .withNamespace(Namespace.of("metalake"))
            .withType(Catalog.Type.RELATIONAL)
            .withProvider(catalog.shortName())
            .withAuditInfo(
                AuditInfo.builder().withCreator("test").withCreateTime(Instant.EPOCH).build())
            .build());
    Assertions.assertThrows(UnsupportedOperationException.class, catalog::ops);
    Assertions.assertEquals(database, catalog.config.get("jdbc-database"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "jdbc:sqlserver://localhost;databaseName=demo",
        "jdbc:sqlserver://localhost;databaseName={my;db}"
      })
  void testConflictingDatabaseRejected(String url) {
    IllegalArgumentException error =
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () ->
                new SqlServerCatalog()
                    .withCatalogConf(Map.of("jdbc-url", url, "jdbc-database", "conflicting")));
    Assertions.assertEquals(
        "jdbc-database must match the database specified in jdbc-url", error.getMessage());
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", "\t"})
  void testBlankExplicitDatabaseRejected(String database) {
    Map<String, String> config = new HashMap<>();
    config.put("jdbc-url", "jdbc:sqlserver://localhost:1433;databaseName=demo");
    config.put("jdbc-database", database);
    IllegalArgumentException error =
        Assertions.assertThrows(
            IllegalArgumentException.class, () -> new SqlServerCatalog().withCatalogConf(config));
    Assertions.assertTrue(error.getMessage().contains("jdbc-database"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "jdbc:sqlserver://localhost",
        "jdbc:sqlserver://localhost;databaseName=",
        "jdbc:sqlserver://localhost;databaseName={}",
        "jdbc:sqlserver://localhost;databaseName={broken"
      })
  void testMissingDatabaseRejectedBeforeFirstOperation(String url) {
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> new SqlServerCatalog().withCatalogConf(Map.of("jdbc-url", url)));
  }

  @Test
  void testInvalidUrlRejected() {
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> new SqlServerCatalog().withCatalogConf(Map.of("jdbc-url", "invalid")));
    Assertions.assertThrows(
        IllegalArgumentException.class, () -> new SqlServerCatalog().withCatalogConf(Map.of()));
  }

  private static class CapturingCatalog extends SqlServerCatalog {
    private Map<String, String> config;

    /** {@inheritDoc} */
    @Override
    protected CatalogOperations newOps(Map<String, String> conf) {
      config = conf;
      throw new UnsupportedOperationException("Configuration captured without connecting");
    }
  }
}
