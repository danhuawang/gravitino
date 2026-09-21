/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.catalog.bigquery;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.apache.gravitino.connector.PropertyEntry;
import org.junit.jupiter.api.Test;

/** Unit tests for BigQueryCatalog. */
public class TestBigQueryCatalog {

  @Test
  void testShortName() {
    BigQueryCatalog catalog = new BigQueryCatalog();
    assertEquals("jdbc-bigquery", catalog.shortName());
  }

  @Test
  void testNewCapability() {
    BigQueryCatalog catalog = new BigQueryCatalog();
    assertNotNull(catalog.newCapability());
    assertTrue(catalog.newCapability() instanceof BigQueryCatalogCapability);
  }

  @Test
  void testPropertiesMetadata() {
    BigQueryCatalog catalog = new BigQueryCatalog();

    assertNotNull(catalog.catalogPropertiesMetadata());
    assertTrue(catalog.catalogPropertiesMetadata() instanceof BigQueryCatalogPropertiesMetadata);

    assertNotNull(catalog.schemaPropertiesMetadata());
    assertTrue(catalog.schemaPropertiesMetadata() instanceof BigQuerySchemaPropertiesMetadata);

    assertNotNull(catalog.tablePropertiesMetadata());
    assertTrue(catalog.tablePropertiesMetadata() instanceof BigQueryTablePropertiesMetadata);
  }

  @Test
  void testWithCatalogConf() {
    Map<String, String> config = new HashMap<>();
    config.put("project-id", "test-project");
    config.put("jdbc-user", "test@example.com");
    config.put("jdbc-password", "/path/to/key.json");
    config.put("jdbc-url", "jdbc:bigquery://https://www.googleapis.com/bigquery/v2:443;");

    BigQueryCatalog catalog = new BigQueryCatalog();
    BigQueryCatalog configuredCatalog = catalog.withCatalogConf(config);

    assertNotNull(configuredCatalog);
    assertNotNull(configuredCatalog.getClientPool());
    assertEquals("test-project", configuredCatalog.getClientPool().getProjectId());
  }

  @Test
  void testBuildJdbcUrlWithExistingAuth() {
    Map<String, String> config = new HashMap<>();
    config.put("project-id", "test-project");
    config.put("jdbc-user", "test@example.com");
    config.put("jdbc-password", "/path/to/key.json");
    config.put(
        "jdbc-url",
        "jdbc:bigquery://https://www.googleapis.com/bigquery/v2:443;ProjectId=test-project;OAuthType=0;");

    BigQueryCatalog catalog = new BigQueryCatalog();
    BigQueryCatalog configuredCatalog = catalog.withCatalogConf(config);

    assertNotNull(configuredCatalog);
    assertNotNull(configuredCatalog.getClientPool());
  }

  @Test
  void testBuildJdbcUrlWithoutAuth() {
    Map<String, String> config = new HashMap<>();
    config.put("project-id", "test-project");
    config.put("jdbc-password", "/path/to/key.json");
    config.put("jdbc-user", "test@example.com");

    BigQueryCatalog catalog = new BigQueryCatalog();
    BigQueryCatalog configuredCatalog = catalog.withCatalogConf(config);

    assertNotNull(configuredCatalog);
    assertNotNull(configuredCatalog.getClientPool());
  }

  /**
   * Regression test for #2165: the catalog must declare the inherited JDBC properties so the API
   * and UI can discover them. Previously only 5 BigQuery-specific entries were exposed.
   */
  @Test
  void testCatalogPropertiesDeclareJdbcEntries() {
    BigQueryCatalog catalog = new BigQueryCatalog();
    Map<String, PropertyEntry<?>> entries = catalog.catalogPropertiesMetadata().propertyEntries();

    // BigQuery-specific
    assertTrue(entries.containsKey("project-id"), "project-id must be declared");
    assertTrue(entries.get("project-id").isRequired(), "project-id must be required");

    // Inherited JDBC properties must be present after the super merge.
    assertTrue(entries.containsKey("jdbc-url"), "jdbc-url must be declared");
    assertTrue(entries.containsKey("jdbc-driver"), "jdbc-driver must be declared");
    assertTrue(entries.containsKey("jdbc-user"), "jdbc-user must be declared");
    assertTrue(entries.containsKey("jdbc-password"), "jdbc-password must be declared");

    // jdbc-user and jdbc-password are genuinely required for BigQuery service-account auth.
    assertTrue(entries.get("jdbc-user").isRequired(), "jdbc-user must be required");
    assertTrue(entries.get("jdbc-password").isRequired(), "jdbc-password must be required");

    // jdbc-url and jdbc-driver are optional with BigQuery defaults (built / well-known).
    assertEquals(false, entries.get("jdbc-url").isRequired(), "jdbc-url must be optional");
    assertEquals(false, entries.get("jdbc-driver").isRequired(), "jdbc-driver must be optional");
    assertEquals(
        BigQueryCatalogPropertiesMetadata.DEFAULT_JDBC_DRIVER,
        entries.get("jdbc-driver").getDefaultValue(),
        "jdbc-driver default must be the Simba driver class");
    assertEquals(
        BigQueryCatalogPropertiesMetadata.DEFAULT_JDBC_URL,
        entries.get("jdbc-url").getDefaultValue(),
        "jdbc-url default must be the BigQuery base URL");

    // jdbc-password must remain hidden (credential locator).
    assertTrue(entries.get("jdbc-password").isHidden(), "jdbc-password must be hidden");
  }

  /**
   * Regression test for #2165: a create missing a required credential property must be refused at
   * create time (fail fast in withCatalogConf), not accepted and then failing on first use.
   */
  @Test
  void testWithCatalogConfRejectsMissingProjectId() {
    Map<String, String> config = new HashMap<>();
    config.put("jdbc-user", "test@example.com");
    config.put("jdbc-password", "/path/to/key.json");

    BigQueryCatalog catalog = new BigQueryCatalog();
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> catalog.withCatalogConf(config));
    assertTrue(e.getMessage().contains("project-id"));
  }

  @Test
  void testWithCatalogConfRejectsMissingUser() {
    Map<String, String> config = new HashMap<>();
    config.put("project-id", "test-project");
    config.put("jdbc-password", "/path/to/key.json");

    BigQueryCatalog catalog = new BigQueryCatalog();
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> catalog.withCatalogConf(config));
    assertTrue(e.getMessage().contains("jdbc-user"));
  }

  @Test
  void testWithCatalogConfRejectsMissingPassword() {
    Map<String, String> config = new HashMap<>();
    config.put("project-id", "test-project");
    config.put("jdbc-user", "test@example.com");

    BigQueryCatalog catalog = new BigQueryCatalog();
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> catalog.withCatalogConf(config));
    assertTrue(e.getMessage().contains("jdbc-password"));
  }

  /**
   * Regression test: a catalog whose jdbc-url already carries the project and authentication
   * parameters must keep working with only project-id and jdbc-password (no separate jdbc-user), as
   * it did before the create-time validation was added. The service account email is embedded in
   * the URL, so jdbc-user is not required on this path.
   */
  @Test
  void testWithCatalogConfPreAuthenticatedUrlWithoutJdbcUser() {
    Map<String, String> config = new HashMap<>();
    config.put("project-id", "test-project");
    config.put("jdbc-password", "/path/to/key.json");
    config.put(
        "jdbc-url",
        "jdbc:bigquery://https://www.googleapis.com/bigquery/v2:443;ProjectId=test-project;"
            + "OAuthType=0;OAuthServiceAcctEmail=test@example.com;OAuthPvtKeyPath=/path/to/key.json;");
    // Intentionally no jdbc-user.

    BigQueryCatalog catalog = new BigQueryCatalog();
    BigQueryCatalog configuredCatalog = assertDoesNotThrow(() -> catalog.withCatalogConf(config));
    assertNotNull(configuredCatalog);
    assertNotNull(configuredCatalog.getClientPool());
  }

  @Test
  void testWithCatalogConfRejectsProxyPortWithoutHost() {
    Map<String, String> config = new HashMap<>();
    config.put("project-id", "test-project");
    config.put("jdbc-user", "test@example.com");
    config.put("jdbc-password", "/path/to/key.json");
    config.put("proxy-host", "proxy.example.com");
    // proxy-port intentionally omitted

    BigQueryCatalog catalog = new BigQueryCatalog();
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> catalog.withCatalogConf(config));
    assertTrue(e.getMessage().contains("proxy-port"));
  }
}
