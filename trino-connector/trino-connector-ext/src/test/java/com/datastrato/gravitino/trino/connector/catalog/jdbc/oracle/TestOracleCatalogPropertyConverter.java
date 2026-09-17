/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.trino.connector.catalog.jdbc.oracle;

import com.google.common.collect.ImmutableMap;
import java.util.Map;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.credential.Credential;
import org.apache.gravitino.trino.connector.metadata.GravitinoCatalog;
import org.apache.gravitino.trino.connector.metadata.TestGravitinoCatalog;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestOracleCatalogPropertyConverter {

  @Test
  public void testBuildOracleConnectorProperties() throws Exception {
    String name = "test_catalog";
    Map<String, String> properties =
        ImmutableMap.<String, String>builder()
            .put("jdbc-url", "jdbc:oracle:thin:@localhost:1521/FREEPDB1")
            .put("jdbc-user", "test")
            .put("jdbc-password", "test")
            .put("trino.bypass.join-pushdown.strategy", "EAGER")
            .put("unknown-key", "1")
            .put("trino.bypass.oracle.unknown-key", "1")
            .build();
    Catalog mockCatalog =
        TestGravitinoCatalog.mockCatalog(
            name, "jdbc-oracle", "test catalog", Catalog.Type.RELATIONAL, properties);
    OracleConnectorAdapter adapter = new OracleConnectorAdapter();

    Map<String, String> config =
        adapter.buildInternalConnectorConfig(
            new GravitinoCatalog("test", mockCatalog), new Credential[0]);

    // test converted properties
    Assertions.assertEquals(
        "jdbc:oracle:thin:@localhost:1521/FREEPDB1", config.get("connection-url"));
    Assertions.assertEquals("test", config.get("connection-user"));
    Assertions.assertEquals("test", config.get("connection-password"));

    // test trino.bypass passthrough properties
    Assertions.assertEquals("EAGER", config.get("join-pushdown.strategy"));

    // test unknown properties are filtered
    Assertions.assertNull(config.get("unknown-key"));
    Assertions.assertEquals("1", config.get("oracle.unknown-key"));
  }
}
