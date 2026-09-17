/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.trino.connector.catalog.jdbc.oracle;

import java.util.Collections;
import java.util.Map;
import org.apache.gravitino.credential.Credential;
import org.apache.gravitino.credential.JdbcCredential;
import org.apache.gravitino.trino.connector.catalog.jdbc.JDBCCatalogPropertyConverter;
import org.apache.gravitino.trino.connector.metadata.GravitinoCatalog;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link OracleConnectorAdapter#buildInternalConnectorConfig}. */
public class TestOracleConnectorAdapter {

  private static final String JDBC_URL = "jdbc:oracle:thin:@localhost:1521/FREEPDB1";

  private GravitinoCatalog catalogWithProps(Map<String, String> props) {
    return new GravitinoCatalog("metalake", "jdbc-oracle", "test", props, 0L);
  }

  @Test
  void testCredentialVendingOverridesProperties() throws Exception {
    GravitinoCatalog catalog = catalogWithProps(Collections.singletonMap("jdbc-url", JDBC_URL));
    Credential[] credentials = {new JdbcCredential("vended-user", "vended-password")};

    Map<String, String> config =
        new OracleConnectorAdapter().buildInternalConnectorConfig(catalog, credentials);

    Assertions.assertEquals(
        "vended-user", config.get(JDBCCatalogPropertyConverter.JDBC_CONNECTION_USER_KEY));
    Assertions.assertEquals(
        "vended-password", config.get(JDBCCatalogPropertyConverter.JDBC_CONNECTION_PASSWORD_KEY));
  }

  @Test
  void testFallsBackToPropertiesWhenNoCredentials() throws Exception {
    Map<String, String> props =
        Map.of("jdbc-url", JDBC_URL, "jdbc-user", "prop-user", "jdbc-password", "prop-password");
    GravitinoCatalog catalog = catalogWithProps(props);

    Map<String, String> config =
        new OracleConnectorAdapter().buildInternalConnectorConfig(catalog, new Credential[0]);

    Assertions.assertEquals(
        "prop-user", config.get(JDBCCatalogPropertyConverter.JDBC_CONNECTION_USER_KEY));
    Assertions.assertEquals(
        "prop-password", config.get(JDBCCatalogPropertyConverter.JDBC_CONNECTION_PASSWORD_KEY));
  }
}
