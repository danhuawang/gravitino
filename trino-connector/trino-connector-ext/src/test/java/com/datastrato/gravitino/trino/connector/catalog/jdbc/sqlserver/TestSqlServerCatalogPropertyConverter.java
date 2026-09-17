/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.trino.connector.catalog.jdbc.sqlserver;

import com.google.common.collect.ImmutableMap;
import java.util.Map;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.credential.Credential;
import org.apache.gravitino.trino.connector.metadata.GravitinoCatalog;
import org.apache.gravitino.trino.connector.metadata.TestGravitinoCatalog;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestSqlServerCatalogPropertyConverter {

  @Test
  public void testBuildSqlServerConnectorProperties() throws Exception {
    String name = "test_catalog";
    Map<String, String> properties =
        ImmutableMap.<String, String>builder()
            .put("jdbc-url", "jdbc:sqlserver://localhost:1433;databaseName=test")
            .put("jdbc-user", "test")
            .put("jdbc-password", "test")
            .put("trino.bypass.join-pushdown.strategy", "EAGER")
            .put("unknown-key", "1")
            .put("trino.bypass.sqlserver.unknown-key", "1")
            .build();
    Catalog mockCatalog =
        TestGravitinoCatalog.mockCatalog(
            name, "jdbc-sqlserver", "test catalog", Catalog.Type.RELATIONAL, properties);
    SqlServerConnectorAdapter adapter = new SqlServerConnectorAdapter();

    Map<String, String> config =
        adapter.buildInternalConnectorConfig(
            new GravitinoCatalog("test", mockCatalog), new Credential[0]);

    // test converted properties
    Assertions.assertEquals(
        config.get("connection-url"), "jdbc:sqlserver://localhost:1433;databaseName=test");
    Assertions.assertEquals(config.get("connection-user"), "test");
    Assertions.assertEquals(config.get("connection-password"), "test");

    // test trino passing properties
    Assertions.assertEquals(config.get("join-pushdown.strategy"), "EAGER");

    // test unknown properties
    Assertions.assertNull(config.get("unknown-key"));
    Assertions.assertEquals(config.get("sqlserver.unknown-key"), "1");
  }
}
