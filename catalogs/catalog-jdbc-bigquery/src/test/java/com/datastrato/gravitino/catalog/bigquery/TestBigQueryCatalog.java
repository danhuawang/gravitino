/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.catalog.bigquery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
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
}
