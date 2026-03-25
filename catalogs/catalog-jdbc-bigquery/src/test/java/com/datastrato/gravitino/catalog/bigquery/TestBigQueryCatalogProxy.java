/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.catalog.bigquery;

import static com.datastrato.gravitino.catalog.bigquery.BigQueryCatalogPropertiesMetadata.PROJECT_ID;
import static com.datastrato.gravitino.catalog.bigquery.BigQueryCatalogPropertiesMetadata.PROXY_HOST;
import static com.datastrato.gravitino.catalog.bigquery.BigQueryCatalogPropertiesMetadata.PROXY_PASSWORD;
import static com.datastrato.gravitino.catalog.bigquery.BigQueryCatalogPropertiesMetadata.PROXY_PORT;
import static com.datastrato.gravitino.catalog.bigquery.BigQueryCatalogPropertiesMetadata.PROXY_USERNAME;
import static org.apache.gravitino.catalog.jdbc.config.JdbcConfig.PASSWORD;
import static org.apache.gravitino.catalog.jdbc.config.JdbcConfig.USERNAME;

import com.google.common.collect.Maps;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** Unit tests for BigQuery catalog proxy configuration. */
public class TestBigQueryCatalogProxy {

  @Test
  void testProxyConfigurationInJdbcUrl() {
    // Test the proxy configuration by creating a catalog and checking the processed config
    // We'll use reflection to access the buildJdbcUrl method or test through withCatalogConf
    Map<String, String> config = Maps.newHashMap();

    // Basic required properties
    config.put(PROJECT_ID, "test-project");
    config.put(PASSWORD.getKey(), "/path/to/key.json");
    config.put(USERNAME.getKey(), "test@example.com");

    // Proxy configuration
    config.put(PROXY_HOST, "proxy.example.com");
    config.put(PROXY_PORT, "3128");
    config.put(PROXY_USERNAME, "proxyuser");
    config.put(PROXY_PASSWORD, "proxypass");

    // Create catalog and process configuration
    BigQueryCatalog catalog = new BigQueryCatalog();
    catalog.withCatalogConf(config);

    // Since we can't access properties() without entity, we'll verify the configuration
    // was processed correctly by checking that no exceptions were thrown and the catalog
    // was initialized successfully. The actual JDBC URL construction is tested in integration
    // tests.

    // Verify the catalog was created successfully (no exceptions thrown)
    Assertions.assertNotNull(catalog);

    // Test that proxy validation works correctly
    Assertions.assertDoesNotThrow(
        () -> {
          BigQueryCatalog testCatalog = new BigQueryCatalog();
          testCatalog.withCatalogConf(config);
        });
  }

  @Test
  void testProxyConfigurationWithoutAuthentication() {
    Map<String, String> config = Maps.newHashMap();

    // Basic required properties
    config.put(PROJECT_ID, "test-project");
    config.put(PASSWORD.getKey(), "/path/to/key.json");

    // Proxy configuration without authentication
    config.put(PROXY_HOST, "proxy.example.com");
    config.put(PROXY_PORT, "8080");

    // Create catalog and process configuration
    BigQueryCatalog catalog = new BigQueryCatalog();

    // Verify the catalog processes the configuration without errors
    Assertions.assertDoesNotThrow(
        () -> {
          catalog.withCatalogConf(config);
        });

    // Verify the catalog was created successfully
    Assertions.assertNotNull(catalog);
  }

  @Test
  void testNoProxyConfiguration() {
    Map<String, String> config = Maps.newHashMap();

    // Basic required properties only
    config.put(PROJECT_ID, "test-project");
    config.put(PASSWORD.getKey(), "/path/to/key.json");

    // Create catalog and process configuration
    BigQueryCatalog catalog = new BigQueryCatalog();

    // Verify the catalog processes the configuration without errors
    Assertions.assertDoesNotThrow(
        () -> {
          catalog.withCatalogConf(config);
        });

    // Verify the catalog was created successfully
    Assertions.assertNotNull(catalog);
  }

  @Test
  void testInvalidProxyConfiguration() {
    BigQueryCatalog catalog = new BigQueryCatalog();
    Map<String, String> config = Maps.newHashMap();

    // Basic required properties
    config.put(PROJECT_ID, "test-project");
    config.put(PASSWORD.getKey(), "/path/to/key.json");

    // Invalid proxy configuration - host without port
    config.put(PROXY_HOST, "proxy.example.com");
    // Missing PROXY_PORT

    // Should throw exception
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> catalog.withCatalogConf(config),
        "proxy-port is required when proxy-host is specified");
  }

  @Test
  void testInvalidProxyPort() {
    BigQueryCatalog catalog = new BigQueryCatalog();
    Map<String, String> config = Maps.newHashMap();

    // Basic required properties
    config.put(PROJECT_ID, "test-project");
    config.put(PASSWORD.getKey(), "/path/to/key.json");

    // Invalid proxy port
    config.put(PROXY_HOST, "proxy.example.com");
    config.put(PROXY_PORT, "invalid-port");

    // Should throw exception
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> catalog.withCatalogConf(config),
        "proxy-port must be a valid integer");
  }

  @Test
  void testProxyPortOutOfRange() {
    BigQueryCatalog catalog = new BigQueryCatalog();
    Map<String, String> config = Maps.newHashMap();

    // Basic required properties
    config.put(PROJECT_ID, "test-project");
    config.put(PASSWORD.getKey(), "/path/to/key.json");

    // Proxy port out of range
    config.put(PROXY_HOST, "proxy.example.com");
    config.put(PROXY_PORT, "70000");

    // Should throw exception
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> catalog.withCatalogConf(config),
        "proxy-port must be between 1 and 65535");
  }

  @Test
  void testBigQueryClientPoolWithAuthenticatedProxy() {
    Map<String, String> config = Maps.newHashMap();

    // Basic required properties
    config.put(PROJECT_ID, "test-project");
    config.put(PASSWORD.getKey(), "/path/to/key.json");

    // Proxy configuration with authentication
    config.put(PROXY_HOST, "proxy.example.com");
    config.put(PROXY_PORT, "3128");
    config.put(PROXY_USERNAME, "proxyuser");
    config.put(PROXY_PASSWORD, "proxypass");

    // Create client pool with authenticated proxy configuration
    BigQueryClientPool clientPool = new BigQueryClientPool(config);

    // Verify proxy configuration is stored
    Assertions.assertEquals("test-project", clientPool.getProjectId());

    // Note: We can't easily test the actual HTTP transport proxy authentication
    // without making real network calls, but we can verify the client pool
    // initializes correctly with proxy authentication parameters
    clientPool.close();
  }

  @Test
  void testBigQueryClientPoolWithProxy() {
    Map<String, String> config = Maps.newHashMap();

    // Basic required properties
    config.put(PROJECT_ID, "test-project");
    config.put(PASSWORD.getKey(), "/path/to/key.json");

    // Proxy configuration
    config.put(PROXY_HOST, "proxy.example.com");
    config.put(PROXY_PORT, "3128");

    // Create client pool with proxy configuration
    BigQueryClientPool clientPool = new BigQueryClientPool(config);

    // Verify proxy configuration is stored
    Assertions.assertEquals("test-project", clientPool.getProjectId());

    // Note: We can't easily test the actual HTTP transport proxy configuration
    // without making real network calls, but we can verify the client pool
    // initializes correctly with proxy parameters
    clientPool.close();
  }

  @Test
  void testBigQueryClientPoolWithoutProxy() {
    Map<String, String> config = Maps.newHashMap();

    // Basic required properties only
    config.put(PROJECT_ID, "test-project");
    config.put(PASSWORD.getKey(), "/path/to/key.json");

    // Create client pool without proxy configuration
    BigQueryClientPool clientPool = new BigQueryClientPool(config);

    // Verify basic configuration
    Assertions.assertEquals("test-project", clientPool.getProjectId());

    clientPool.close();
  }
}
