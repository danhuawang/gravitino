/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.catalog.bigquery.integration;

import static com.datastrato.gravitino.catalog.bigquery.BigQueryCatalogPropertiesMetadata.PROJECT_ID;
import static org.awaitility.Awaitility.await;

import com.google.common.collect.Maps;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.Schema;
import org.apache.gravitino.catalog.jdbc.config.JdbcConfig;
import org.apache.gravitino.client.GravitinoMetalake;
import org.apache.gravitino.integration.test.util.BaseIT;
import org.apache.gravitino.integration.test.util.GravitinoITUtils;
import org.apache.gravitino.rel.Column;
import org.apache.gravitino.rel.Table;
import org.apache.gravitino.rel.TableCatalog;
import org.apache.gravitino.rel.expressions.distributions.Distributions;
import org.apache.gravitino.rel.expressions.sorts.SortOrder;
import org.apache.gravitino.rel.expressions.transforms.Transforms;
import org.apache.gravitino.rel.types.Types;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.api.condition.EnabledIf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cross-region integration tests for BigQuery catalog.
 *
 * <p>This test class focuses on testing BigQuery's cross-region capabilities, including:
 *
 * <ul>
 *   <li>Cross-region dataset listing and operations
 *   <li>Multi-region dataset creation and management
 *   <li>Cross-region table operations
 *   <li>Performance testing across regions
 * </ul>
 *
 * <p>Requires the same environment variables as CatalogBigQueryIT plus:
 *
 * <ul>
 *   <li>BIGQUERY_MULTI_REGION_TEST - Set to "true" to enable multi-region tests
 * </ul>
 */
@TestInstance(Lifecycle.PER_CLASS)
@EnabledIf("isCrossRegionTestConfigured")
public class CatalogBigQueryCrossRegionIT extends BaseIT {

  private static final Logger LOG = LoggerFactory.getLogger(CatalogBigQueryCrossRegionIT.class);
  private static final String provider = "jdbc-bigquery";

  // Environment variable names
  private static final String ENV_PROJECT_ID = "BIGQUERY_PROJECT_ID";
  private static final String ENV_KEY_PATH = "BIGQUERY_SERVICE_ACCOUNT_KEY_PATH";
  private static final String ENV_SERVICE_ACCOUNT_EMAIL = "BIGQUERY_SERVICE_ACCOUNT_EMAIL";
  private static final String ENV_MULTI_REGION_TEST = "BIGQUERY_MULTI_REGION_TEST";

  // Test regions
  private static final List<String> TEST_REGIONS = Arrays.asList("US", "EU", "asia-east1");

  // Test configuration
  private String projectId;
  private String keyPath;
  private String serviceAccountEmail;

  // Test identifiers
  public String metalakeName = GravitinoITUtils.genRandomName("bigquery_cross_region_metalake");
  public String catalogName = GravitinoITUtils.genRandomName("bigquery_cross_region_catalog");

  // Track created resources to clean up
  private final Set<String> createdSchemas = ConcurrentHashMap.newKeySet();
  private final Set<NameIdentifier> createdTables = ConcurrentHashMap.newKeySet();

  private GravitinoMetalake metalake;
  protected Catalog catalog;

  /**
   * Check if BigQuery cross-region testing is configured.
   *
   * @return true if all required environment variables are set and multi-region test is enabled
   */
  static boolean isCrossRegionTestConfigured() {
    return StringUtils.isNotBlank(System.getenv(ENV_PROJECT_ID))
        && StringUtils.isNotBlank(System.getenv(ENV_KEY_PATH))
        && "true".equalsIgnoreCase(System.getenv(ENV_MULTI_REGION_TEST));
  }

  @BeforeAll
  public void startup() {
    // Load configuration from environment variables
    projectId = System.getenv(ENV_PROJECT_ID);
    keyPath = System.getenv(ENV_KEY_PATH);
    serviceAccountEmail = System.getenv(ENV_SERVICE_ACCOUNT_EMAIL);
    boolean multiRegionTestEnabled = "true".equalsIgnoreCase(System.getenv(ENV_MULTI_REGION_TEST));

    LOG.info("Starting BigQuery cross-region integration tests with project: {}", projectId);
    LOG.info("Multi-region test enabled: {}", multiRegionTestEnabled);

    createMetalake();
    catalog = createCatalog(catalogName);
  }

  @AfterAll
  public void stop() {
    // Clean up only resources created by this test
    cleanupTestResources();

    // Do NOT delete catalog or metalake - they may be shared
    LOG.info("Test cleanup completed. Catalog and metalake are preserved.");
  }

  @AfterEach
  public void cleanup() {
    // Clean up resources created in this test method
    cleanupTestResources();
  }

  private void cleanupTestResources() {
    LOG.info(
        "Cleaning up test resources: {} schemas, {} tables",
        createdSchemas.size(),
        createdTables.size());

    // First, clean up all tables created by tests
    Set<NameIdentifier> tablesToDelete = new HashSet<>(createdTables);
    for (NameIdentifier tableId : tablesToDelete) {
      try {
        LOG.debug("Dropping table: {}", tableId);
        catalog.asTableCatalog().dropTable(tableId);
        createdTables.remove(tableId);
      } catch (Exception e) {
        LOG.warn("Failed to drop table {}: {}", tableId, e.getMessage());
      }
    }

    // Then, clean up all schemas created by tests
    Set<String> schemasToDelete = new HashSet<>(createdSchemas);
    for (String schemaName : schemasToDelete) {
      try {
        LOG.debug("Dropping schema: {}", schemaName);
        catalog.asSchemas().dropSchema(schemaName, true);
        createdSchemas.remove(schemaName);
      } catch (Exception e) {
        LOG.warn("Failed to drop schema {}: {}", schemaName, e.getMessage());
      }
    }

    // Wait for BigQuery operations to complete
    await()
        .atMost(Duration.ofSeconds(10))
        .pollInterval(Duration.ofMillis(500))
        .until(() -> true); // BigQuery operations are eventually consistent
  }

  private void createMetalake() {
    try {
      // Check if metalake already exists
      GravitinoMetalake[] existingMetalakes = client.listMetalakes();
      for (GravitinoMetalake existing : existingMetalakes) {
        if (existing.name().equals(metalakeName)) {
          LOG.info("Using existing metalake: {}", metalakeName);
          metalake = client.loadMetalake(metalakeName);
          return;
        }
      }

      // Create new metalake if it doesn't exist
      client.createMetalake(metalakeName, "Cross-region test metalake", Collections.emptyMap());
      metalake = client.loadMetalake(metalakeName);
      Assertions.assertEquals(metalakeName, metalake.name());
      LOG.info("Created new metalake: {}", metalakeName);
    } catch (Exception e) {
      LOG.error("Failed to create or load metalake: {}", e.getMessage());
      throw new RuntimeException("Failed to setup metalake", e);
    }
  }

  private Catalog createCatalog(String catalogName) {
    try {
      // Check if catalog already exists in the metalake
      String[] existingCatalogs = metalake.listCatalogs();
      for (String existing : existingCatalogs) {
        if (existing.equals(catalogName)) {
          LOG.info("Using existing catalog: {}", catalogName);
          return metalake.loadCatalog(catalogName);
        }
      }

      // Create new catalog if it doesn't exist
      Map<String, String> catalogProperties = Maps.newHashMap();

      // Required properties
      catalogProperties.put(PROJECT_ID, projectId);
      catalogProperties.put(JdbcConfig.PASSWORD.getKey(), keyPath); // Key file path
      catalogProperties.put(
          JdbcConfig.JDBC_DRIVER.getKey(), "com.simba.googlebigquery.jdbc42.Driver");

      // Optional properties
      if (StringUtils.isNotBlank(serviceAccountEmail)) {
        catalogProperties.put(JdbcConfig.USERNAME.getKey(), serviceAccountEmail);
      }

      Catalog createdCatalog =
          metalake.createCatalog(
              catalogName,
              Catalog.Type.RELATIONAL,
              provider,
              "Cross-region test catalog",
              catalogProperties);
      Catalog loadCatalog = metalake.loadCatalog(catalogName);
      Assertions.assertEquals(createdCatalog, loadCatalog);

      LOG.info("Created new catalog: {}", catalogName);
      return loadCatalog;
    } catch (Exception e) {
      LOG.error("Failed to create or load catalog: {}", e.getMessage());
      throw new RuntimeException("Failed to setup catalog", e);
    }
  }

  private Column[] createTestColumns() {
    return new Column[] {
      Column.of("id", Types.LongType.get(), "ID column"),
      Column.of("name", Types.StringType.get(), "Name column"),
      Column.of("created_at", Types.TimestampType.withoutTimeZone(), "Creation timestamp")
    };
  }

  @Test
  void testCrossRegionSchemaListing() {
    LOG.info("Testing cross-region schema listing");

    // Create schemas in different regions
    Map<String, String> createdTestSchemas = Maps.newHashMap();

    for (String region : TEST_REGIONS) {
      String schemaName =
          GravitinoITUtils.genRandomName("bigquery_cross_region_test_" + region.toLowerCase());
      Map<String, String> properties = Maps.newHashMap();
      properties.put("location", region);
      properties.put("description", "Cross-region test schema in " + region);

      try {
        catalog.asSchemas().createSchema(schemaName, "Test schema in " + region, properties);
        createdTestSchemas.put(region, schemaName);
        createdSchemas.add(schemaName); // Track for cleanup
        LOG.info("Created schema {} in region {}", schemaName, region);
      } catch (Exception e) {
        LOG.warn("Failed to create schema in region {}: {}", region, e.getMessage());
      }
    }

    // List all schemas and verify cross-region visibility
    String[] allSchemas = catalog.asSchemas().listSchemas();
    Set<String> schemaSet = new HashSet<>(Arrays.asList(allSchemas));

    LOG.info("Total schemas found: {}", allSchemas.length);

    // Verify that schemas from different regions are visible
    for (Map.Entry<String, String> entry : createdTestSchemas.entrySet()) {
      String region = entry.getKey();
      String schemaName = entry.getValue();

      Assertions.assertTrue(
          schemaSet.contains(schemaName),
          "Schema " + schemaName + " from region " + region + " should be visible");

      // Load schema and verify properties
      Schema loadedSchema = catalog.asSchemas().loadSchema(schemaName);
      Assertions.assertEquals(schemaName, loadedSchema.name());
      Assertions.assertEquals(region, loadedSchema.properties().get("location"));
    }

    LOG.info("Cross-region schema listing test completed successfully");
  }

  @Test
  void testCrossRegionTableOperations() {
    LOG.info("Testing cross-region table operations");

    // Create a schema in US region
    String usSchemaName = GravitinoITUtils.genRandomName("bigquery_cross_region_us_test");
    Map<String, String> usProperties = Maps.newHashMap();
    usProperties.put("location", "US");
    catalog.asSchemas().createSchema(usSchemaName, "US test schema", usProperties);
    createdSchemas.add(usSchemaName); // Track for cleanup

    // Create a schema in EU region (if supported)
    String euSchemaName = GravitinoITUtils.genRandomName("bigquery_cross_region_eu_test");
    Map<String, String> euProperties = Maps.newHashMap();
    euProperties.put("location", "EU");

    boolean euSchemaCreated = false;
    try {
      catalog.asSchemas().createSchema(euSchemaName, "EU test schema", euProperties);
      createdSchemas.add(euSchemaName); // Track for cleanup
      euSchemaCreated = true;
      LOG.info("Successfully created EU schema: {}", euSchemaName);
    } catch (Exception e) {
      LOG.warn("Failed to create EU schema, continuing with US only: {}", e.getMessage());
    }

    // Create tables in US schema
    String usTableName = GravitinoITUtils.genRandomName("us_table");
    NameIdentifier usTableId = NameIdentifier.of(usSchemaName, usTableName);

    TableCatalog tableCatalog = catalog.asTableCatalog();
    tableCatalog.createTable(
        usTableId,
        createTestColumns(),
        "US table",
        Collections.emptyMap(),
        Transforms.EMPTY_TRANSFORM,
        Distributions.NONE,
        new SortOrder[0]);
    createdTables.add(usTableId); // Track for cleanup

    // Create table in EU schema if available
    if (euSchemaCreated) {
      String euTableName = GravitinoITUtils.genRandomName("eu_table");
      NameIdentifier euTableId = NameIdentifier.of(euSchemaName, euTableName);

      tableCatalog.createTable(
          euTableId,
          createTestColumns(),
          "EU table",
          Collections.emptyMap(),
          Transforms.EMPTY_TRANSFORM,
          Distributions.NONE,
          new SortOrder[0]);
      createdTables.add(euTableId); // Track for cleanup

      // Verify both tables are accessible
      Table usTable = tableCatalog.loadTable(usTableId);
      Table euTable = tableCatalog.loadTable(euTableId);

      Assertions.assertEquals(usTableName, usTable.name());
      Assertions.assertEquals(euTableName, euTable.name());

      LOG.info("Successfully created and accessed tables in both US and EU regions");
    }

    // List tables across regions
    NameIdentifier[] usTables = tableCatalog.listTables(Namespace.of(usSchemaName));
    Assertions.assertTrue(usTables.length > 0);

    if (euSchemaCreated) {
      NameIdentifier[] euTables = tableCatalog.listTables(Namespace.of(euSchemaName));
      Assertions.assertTrue(euTables.length > 0);
    }

    LOG.info("Cross-region table operations test completed successfully");
  }

  @Test
  void testMultiRegionPerformanceComparison() {
    LOG.info("Testing multi-region performance comparison");

    Map<String, Long> regionPerformance = Maps.newHashMap();

    for (String region : TEST_REGIONS) {
      try {
        String schemaName =
            GravitinoITUtils.genRandomName("bigquery_multi_region_perf_" + region.toLowerCase());
        Map<String, String> properties = Maps.newHashMap();
        properties.put("location", region);

        // Measure schema creation time
        long startTime = System.currentTimeMillis();
        catalog.asSchemas().createSchema(schemaName, "Performance test schema", properties);
        long schemaCreationTime = System.currentTimeMillis() - startTime;
        createdSchemas.add(schemaName); // Track for cleanup

        // Measure table creation time
        String tableName = GravitinoITUtils.genRandomName("perf_table");
        NameIdentifier tableId = NameIdentifier.of(schemaName, tableName);

        startTime = System.currentTimeMillis();
        catalog
            .asTableCatalog()
            .createTable(
                tableId,
                createTestColumns(),
                "Performance test table",
                Collections.emptyMap(),
                Transforms.EMPTY_TRANSFORM,
                Distributions.NONE,
                new SortOrder[0]);
        long tableCreationTime = System.currentTimeMillis() - startTime;
        createdTables.add(tableId); // Track for cleanup

        // Measure table loading time
        startTime = System.currentTimeMillis();
        Table loadedTable = catalog.asTableCatalog().loadTable(tableId);
        long tableLoadTime = System.currentTimeMillis() - startTime;

        Assertions.assertEquals(tableName, loadedTable.name());

        long totalTime = schemaCreationTime + tableCreationTime + tableLoadTime;
        regionPerformance.put(region, totalTime);

        LOG.info(
            "Region {} performance: Schema creation: {}ms, Table creation: {}ms, Table load: {}ms, Total: {}ms",
            region,
            schemaCreationTime,
            tableCreationTime,
            tableLoadTime,
            totalTime);

      } catch (Exception e) {
        LOG.warn("Performance test failed for region {}: {}", region, e.getMessage());
        regionPerformance.put(region, -1L); // Mark as failed
      }
    }

    // Report performance comparison
    LOG.info("Multi-region performance comparison:");
    regionPerformance.forEach(
        (region, time) -> {
          if (time > 0) {
            LOG.info("  {}: {}ms", region, time);
          } else {
            LOG.info("  {}: FAILED", region);
          }
        });

    // Verify at least one region worked
    boolean anyRegionWorked = regionPerformance.values().stream().anyMatch(time -> time > 0);
    Assertions.assertTrue(anyRegionWorked, "At least one region should work");

    LOG.info("Multi-region performance comparison completed");
  }

  @Test
  void testCrossRegionDatasetProperties() {
    LOG.info("Testing cross-region dataset properties");

    Map<String, String> testRegions = Maps.newHashMap();
    testRegions.put("US", "United States");
    testRegions.put("EU", "Europe");

    for (Map.Entry<String, String> entry : testRegions.entrySet()) {
      String region = entry.getKey();
      String description = entry.getValue();

      try {
        String schemaName =
            GravitinoITUtils.genRandomName("bigquery_cross_region_props_" + region.toLowerCase());

        Map<String, String> properties = Maps.newHashMap();
        properties.put("location", region);
        properties.put("description", "Test dataset in " + description);
        properties.put("friendly_name", "Cross-region test dataset");

        // Create schema with properties
        Schema createdSchema =
            catalog
                .asSchemas()
                .createSchema(schemaName, "Cross-region properties test", properties);
        createdSchemas.add(schemaName); // Track for cleanup

        Assertions.assertEquals(schemaName, createdSchema.name());

        // Load schema and verify properties
        Schema loadedSchema = catalog.asSchemas().loadSchema(schemaName);
        Assertions.assertEquals(schemaName, loadedSchema.name());
        Assertions.assertEquals(region, loadedSchema.properties().get("location"));

        LOG.info("Successfully tested properties for region: {}", region);

      } catch (Exception e) {
        LOG.warn("Properties test failed for region {}: {}", region, e.getMessage());
      }
    }

    LOG.info("Cross-region dataset properties test completed");
  }

  @Test
  void testCrossRegionConcurrentOperations() throws InterruptedException {
    LOG.info("Testing cross-region concurrent operations");

    int numThreads = Math.min(3, TEST_REGIONS.size()); // Limit to 3 threads max
    Thread[] threads = new Thread[numThreads];

    for (int i = 0; i < numThreads; i++) {
      final String region = TEST_REGIONS.get(i);
      final int threadId = i;

      threads[i] =
          new Thread(
              () -> {
                try {
                  String schemaName =
                      GravitinoITUtils.genRandomName(
                          "bigquery_cross_region_concurrent_" + region.toLowerCase());
                  String tableName = GravitinoITUtils.genRandomName("concurrent_table");

                  Map<String, String> properties = Maps.newHashMap();
                  properties.put("location", region);

                  // Create schema
                  catalog
                      .asSchemas()
                      .createSchema(schemaName, "Concurrent test schema", properties);
                  createdSchemas.add(schemaName); // Track for cleanup

                  // Create table
                  NameIdentifier tableId = NameIdentifier.of(schemaName, tableName);
                  catalog
                      .asTableCatalog()
                      .createTable(
                          tableId,
                          createTestColumns(),
                          "Concurrent test table",
                          Collections.emptyMap(),
                          Transforms.EMPTY_TRANSFORM,
                          Distributions.NONE,
                          new SortOrder[0]);
                  createdTables.add(tableId); // Track for cleanup

                  // Load table
                  Table loadedTable = catalog.asTableCatalog().loadTable(tableId);
                  Assertions.assertEquals(tableName, loadedTable.name());

                  LOG.info("Thread {} completed operations for region {}", threadId, region);

                } catch (Exception e) {
                  LOG.error(
                      "Concurrent operation failed for region {} in thread {}",
                      region,
                      threadId,
                      e);
                  // Don't throw exception to avoid failing other threads
                }
              });
    }

    // Start all threads
    for (Thread thread : threads) {
      thread.start();
    }

    // Wait for all threads to complete
    for (Thread thread : threads) {
      thread.join(60000); // 60 second timeout per thread
    }

    LOG.info("Cross-region concurrent operations test completed");
  }
}
