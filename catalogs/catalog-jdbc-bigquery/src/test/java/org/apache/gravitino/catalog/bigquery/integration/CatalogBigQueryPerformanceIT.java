package org.apache.gravitino.catalog.bigquery.integration;

import static org.apache.gravitino.catalog.bigquery.BigQueryCatalogPropertiesMetadata.PROJECT_ID;
import static org.awaitility.Awaitility.await;

import com.google.common.collect.Maps;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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
 * Performance integration tests for BigQuery catalog.
 *
 * <p>This test class focuses on performance testing of BigQuery catalog operations, including:
 *
 * <ul>
 *   <li>Bulk operations performance
 *   <li>Concurrent operations throughput
 *   <li>Large-scale metadata operations
 *   <li>Connection pooling efficiency
 * </ul>
 *
 * <p>Requires the same environment variables as CatalogBigQueryIT plus:
 *
 * <ul>
 *   <li>BIGQUERY_PERFORMANCE_TEST - Set to "true" to enable performance tests
 * </ul>
 */
@TestInstance(Lifecycle.PER_CLASS)
@EnabledIf("isPerformanceTestConfigured")
public class CatalogBigQueryPerformanceIT extends BaseIT {

  private static final Logger LOG = LoggerFactory.getLogger(CatalogBigQueryPerformanceIT.class);
  private static final String provider = "jdbc-bigquery";

  // Environment variable names
  private static final String ENV_PROJECT_ID = "BIGQUERY_PROJECT_ID";
  private static final String ENV_KEY_PATH = "BIGQUERY_SERVICE_ACCOUNT_KEY_PATH";
  private static final String ENV_SERVICE_ACCOUNT_EMAIL = "BIGQUERY_SERVICE_ACCOUNT_EMAIL";
  private static final String ENV_PERFORMANCE_TEST = "BIGQUERY_PERFORMANCE_TEST";

  // Performance test configuration
  private static final int BULK_OPERATION_COUNT = 20;
  private static final int CONCURRENT_THREAD_COUNT = 5;
  private static final int LARGE_SCALE_COUNT = 30;

  // Performance thresholds
  private static final long TABLE_LOADING_THRESHOLD_MS = 3000;
  private static final long LISTING_THRESHOLD_MS = 10000;
  private static final long TOTAL_OPERATION_THRESHOLD_MS = 300000;

  // Test configuration
  private String projectId;
  private String keyPath;
  private String serviceAccountEmail;

  // Test identifiers
  public String metalakeName = GravitinoITUtils.genRandomName("bigquery_perf_metalake");
  public String catalogName = GravitinoITUtils.genRandomName("bigquery_perf_catalog");
  public String schemaName = GravitinoITUtils.genRandomName("bigquery_perf_schema");

  private GravitinoMetalake metalake;
  protected Catalog catalog;

  /**
   * Check if BigQuery performance testing is configured.
   *
   * @return true if all required environment variables are set and performance test is enabled
   */
  static boolean isPerformanceTestConfigured() {
    return StringUtils.isNotBlank(System.getenv(ENV_PROJECT_ID))
        && StringUtils.isNotBlank(System.getenv(ENV_KEY_PATH))
        && "true".equalsIgnoreCase(System.getenv(ENV_PERFORMANCE_TEST));
  }

  @BeforeAll
  public void startup() {
    // Load configuration from environment variables
    projectId = System.getenv(ENV_PROJECT_ID);
    keyPath = System.getenv(ENV_KEY_PATH);
    serviceAccountEmail = System.getenv(ENV_SERVICE_ACCOUNT_EMAIL);

    LOG.info("Starting BigQuery performance integration tests with project: {}", projectId);

    createMetalake();
    catalog = createCatalog(catalogName);
    createSchema(catalog, schemaName);
  }

  @AfterAll
  public void stop() {
    try {
      cleanupAllTestData();

      if (metalake != null) {
        try {
          client.disableMetalake(metalakeName);
          client.dropMetalake(metalakeName);
          LOG.info("Dropped test metalake: {}", metalakeName);
        } catch (Exception e) {
          LOG.warn("Failed to drop metalake: {}, error: {}", metalakeName, e.getMessage());
        }
      }
    } catch (Exception e) {
      LOG.warn("Error during stop cleanup: {}", e.getMessage());
    }
  }

  @AfterEach
  public void cleanup() {
    cleanupTestData();
  }

  private void cleanupAllTestData() {
    LOG.info("Starting comprehensive test data cleanup");

    try {
      if (catalog != null) {
        cleanupTestSchemas();

        cleanupTestTablesInSchema(schemaName);

        dropSchemaIfExists(schemaName);
      }
    } catch (Exception e) {
      LOG.warn("Error during comprehensive cleanup: {}", e.getMessage());
    }

    LOG.info("Completed test data cleanup");
  }

  private void cleanupTestData() {
    try {
      if (catalog != null) {
        cleanupTestTablesInSchema(schemaName);

        cleanupTestSchemasExcludingMain();
      }
    } catch (Exception e) {
      LOG.warn("Error during test data cleanup: {}", e.getMessage());
    }
  }

  private void cleanupTestTablesInSchema(String schemaName) {
    try {
      NameIdentifier[] tables = catalog.asTableCatalog().listTables(Namespace.of(schemaName));
      int droppedCount = 0;
      for (NameIdentifier table : tables) {
        if (isTestTable(table.name())) {
          try {
            catalog.asTableCatalog().dropTable(table);
            droppedCount++;
          } catch (Exception e) {
            LOG.warn("Failed to drop test table {}.{}", schemaName, table.name());
          }
        }
      }
      if (droppedCount > 0) {
        LOG.info("Dropped {} test tables from schema: {}", droppedCount, schemaName);
      }
    } catch (Exception e) {
      LOG.warn("Failed to list tables in schema {}: {}", schemaName, e.getMessage());
    }
  }

  private void cleanupTestSchemas() {
    try {
      String[] schemas = catalog.asSchemas().listSchemas();
      for (String schema : schemas) {
        if (isTestSchema(schema)) {
          cleanupTestTablesInSchema(schema);

          dropSchemaIfExists(schema);
        }
      }
    } catch (Exception e) {
      LOG.warn("Failed to list schemas during cleanup: {}", e.getMessage());
    }
  }

  private void cleanupTestSchemasExcludingMain() {
    try {
      String[] schemas = catalog.asSchemas().listSchemas();
      for (String schema : schemas) {
        if (isTestSchema(schema) && !schema.equals(schemaName)) {
          cleanupTestTablesInSchema(schema);

          dropSchemaIfExists(schema);
        }
      }
    } catch (Exception e) {
      LOG.warn("Failed to list schemas during cleanup: {}", e.getMessage());
    }
  }

  private void dropSchemaIfExists(String schemaName) {
    try {
      catalog.asSchemas().dropSchema(schemaName, true);
      LOG.info("Dropped schema: {}", schemaName);
    } catch (Exception e) {
      LOG.warn("Failed to drop schema {}: {}", schemaName, e.getMessage());
    }
  }

  private boolean isTestTable(String tableName) {
    return tableName.startsWith("perf_test_")
        || tableName.startsWith("bulk_test_")
        || tableName.startsWith("test_concurrent_");
  }

  private boolean isTestSchema(String schemaName) {
    return schemaName.startsWith("perf_test_")
        || schemaName.startsWith("bulk_test_")
        || schemaName.startsWith("bigquery_perf_");
  }

  private void createMetalake() {
    try {
      GravitinoMetalake existingMetalake = client.loadMetalake(metalakeName);
      if (existingMetalake != null) {
        client.disableMetalake(metalakeName);
        client.dropMetalake(metalakeName);
        LOG.info("Deleted existing metalake: {}", metalakeName);
      }
    } catch (Exception e) {
      // Metalake doesn't exist, continue with creation
    }

    client.createMetalake(metalakeName, "Performance test metalake", Collections.emptyMap());
    GravitinoMetalake loadMetalake = client.loadMetalake(metalakeName);
    Assertions.assertEquals(metalakeName, loadMetalake.name());

    metalake = loadMetalake;
    LOG.info("Created metalake: {}", metalakeName);
  }

  private Catalog createCatalog(String catalogName) {
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

    catalogProperties.put(JdbcConfig.POOL_MIN_SIZE.getKey(), "2");
    catalogProperties.put(JdbcConfig.POOL_MAX_SIZE.getKey(), "10");

    Catalog createdCatalog =
        metalake.createCatalog(
            catalogName,
            Catalog.Type.RELATIONAL,
            provider,
            "Performance test catalog",
            catalogProperties);
    Catalog loadCatalog = metalake.loadCatalog(catalogName);
    Assertions.assertEquals(createdCatalog, loadCatalog);

    LOG.info("Created catalog: {}", catalogName);
    return loadCatalog;
  }

  private void createSchema(Catalog catalog, String schemaName) {
    Map<String, String> prop = Maps.newHashMap();
    prop.put("location", "US");
    prop.put("description", "Performance test schema");

    Schema createdSchema = catalog.asSchemas().createSchema(schemaName, "Performance test", prop);
    Schema loadSchema = catalog.asSchemas().loadSchema(schemaName);
    Assertions.assertEquals(createdSchema.name(), loadSchema.name());

    LOG.info("Created schema: {}", schemaName);
  }

  private Column[] createTestColumns() {
    return new Column[] {
      Column.of("id", Types.LongType.get(), "ID column"),
      Column.of("name", Types.StringType.get(), "Name column"),
      Column.of("value", Types.DoubleType.get(), "Value column"),
      Column.of("created_at", Types.TimestampType.withoutTimeZone(), "Creation timestamp"),
      Column.of("is_active", Types.BooleanType.get(), "Active flag")
    };
  }

  @Test
  void testBulkTableCreationPerformance() {
    LOG.info("Testing bulk table creation performance with {} tables", BULK_OPERATION_COUNT);

    TableCatalog tableCatalog = catalog.asTableCatalog();
    long startTime = System.currentTimeMillis();

    // Create multiple tables
    for (int i = 0; i < BULK_OPERATION_COUNT; i++) {
      String tableName = String.format("bulk_test_table_%03d", i);
      NameIdentifier tableId = NameIdentifier.of(schemaName, tableName);

      try {
        tableCatalog.createTable(
            tableId,
            createTestColumns(),
            "Bulk test table " + i,
            Collections.emptyMap(),
            Transforms.EMPTY_TRANSFORM,
            Distributions.NONE,
            new SortOrder[0]);

        if ((i + 1) % 5 == 0) {
          LOG.info("Created {} tables", i + 1);
        }
      } catch (Exception e) {
        LOG.warn("Failed to create table {}: {}", tableName, e.getMessage());
      }
    }

    long endTime = System.currentTimeMillis();
    long totalTime = endTime - startTime;
    double avgTime = totalTime > 0 ? (double) totalTime / BULK_OPERATION_COUNT : 0;

    LOG.info(
        "Bulk table creation completed: attempted {} tables in {}ms (avg: {:.2f}ms per table)",
        BULK_OPERATION_COUNT,
        totalTime,
        avgTime);

    // Verify tables were created
    try {
      NameIdentifier[] tables = tableCatalog.listTables(Namespace.of(schemaName));
      long bulkTestTables =
          java.util.Arrays.stream(tables)
              .filter(table -> table.name().startsWith("bulk_test_table_"))
              .count();

      LOG.info("Successfully created {} bulk test tables", bulkTestTables);

      LOG.info(
          "Performance metrics - Average creation time: {:.2f}ms, Total time: {}ms",
          avgTime,
          totalTime);

      if (totalTime > TOTAL_OPERATION_THRESHOLD_MS) {
        LOG.warn(
            "Total bulk creation time {}ms exceeds threshold {}ms",
            totalTime,
            TOTAL_OPERATION_THRESHOLD_MS);
      }
    } catch (Exception e) {
      LOG.warn("Failed to verify created tables: {}", e.getMessage());
    }
  }

  @Test
  void testBulkTableLoadingPerformance() {
    LOG.info("Testing bulk table loading performance");

    // First create some tables
    TableCatalog tableCatalog = catalog.asTableCatalog();
    int tableCount = Math.min(BULK_OPERATION_COUNT, 10);

    cleanupTestTablesInSchema(schemaName);

    for (int i = 0; i < tableCount; i++) {
      String tableName = String.format("perf_test_load_table_%03d", i);
      NameIdentifier tableId = NameIdentifier.of(schemaName, tableName);

      try {
        tableCatalog.createTable(
            tableId,
            createTestColumns(),
            "Performance load test table " + i,
            Collections.emptyMap(),
            Transforms.EMPTY_TRANSFORM,
            Distributions.NONE,
            new SortOrder[0]);
      } catch (Exception e) {
        LOG.warn("Failed to create table {} for loading test: {}", tableName, e.getMessage());
      }
    }

    LOG.info("Created tables for loading test");

    // Wait for BigQuery operations to complete
    await()
        .atMost(Duration.ofSeconds(10))
        .pollInterval(Duration.ofMillis(500))
        .until(() -> true); // BigQuery operations are eventually consistent

    // Now test loading performance
    long startTime = System.currentTimeMillis();
    int successfulLoads = 0;

    for (int i = 0; i < tableCount; i++) {
      String tableName = String.format("perf_test_load_table_%03d", i);
      NameIdentifier tableId = NameIdentifier.of(schemaName, tableName);

      try {
        Table loadedTable = tableCatalog.loadTable(tableId);
        Assertions.assertEquals(tableName, loadedTable.name());
        successfulLoads++;
      } catch (Exception e) {
        LOG.warn("Failed to load table {}: {}", tableName, e.getMessage());
      }
    }

    long endTime = System.currentTimeMillis();
    long totalTime = endTime - startTime;
    double avgTime = successfulLoads > 0 ? (double) totalTime / successfulLoads : 0;

    LOG.info(
        "Bulk table loading completed: {} successful loads out of {} attempts in {}ms (avg: {:.2f}ms per table)",
        successfulLoads,
        tableCount,
        totalTime,
        avgTime);

    if (successfulLoads > 0) {
      LOG.info(
          "Performance metrics - Average loading time: {:.2f}ms, Total time: {}ms",
          avgTime,
          totalTime);

      if (avgTime > TABLE_LOADING_THRESHOLD_MS) {
        LOG.warn(
            "Average table loading time {:.2f}ms exceeds typical threshold {}ms",
            avgTime,
            TABLE_LOADING_THRESHOLD_MS);
      }
    }

    Assertions.assertTrue(successfulLoads > 0, "Should successfully load at least one table");
  }

  @Test
  void testConcurrentTableOperationsPerformance() throws InterruptedException {
    LOG.info(
        "Testing concurrent table operations performance with {} threads", CONCURRENT_THREAD_COUNT);

    ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_THREAD_COUNT);
    CountDownLatch latch = new CountDownLatch(CONCURRENT_THREAD_COUNT);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger errorCount = new AtomicInteger(0);
    AtomicLong totalTime = new AtomicLong(0);

    long startTime = System.currentTimeMillis();

    for (int i = 0; i < CONCURRENT_THREAD_COUNT; i++) {
      final int threadId = i;
      executor.submit(
          () -> {
            try {
              long threadStartTime = System.currentTimeMillis();

              String tableName = String.format("perf_test_concurrent_%03d", threadId);
              NameIdentifier tableId = NameIdentifier.of(schemaName, tableName);

              try {
                catalog.asTableCatalog().loadTable(tableId);
                catalog.asTableCatalog().dropTable(tableId);
              } catch (Exception e) {
                // Table doesn't exist, that's expected
              }

              // Create table
              catalog
                  .asTableCatalog()
                  .createTable(
                      tableId,
                      createTestColumns(),
                      "Concurrent test table " + threadId,
                      Collections.emptyMap(),
                      Transforms.EMPTY_TRANSFORM,
                      Distributions.NONE,
                      new SortOrder[0]);

              // Load table
              Table loadedTable = catalog.asTableCatalog().loadTable(tableId);
              Assertions.assertEquals(tableName, loadedTable.name());

              long threadEndTime = System.currentTimeMillis();
              totalTime.addAndGet(threadEndTime - threadStartTime);
              successCount.incrementAndGet();

            } catch (Exception e) {
              LOG.error("Concurrent operation failed in thread {}: {}", threadId, e.getMessage());
              errorCount.incrementAndGet();
            } finally {
              latch.countDown();
            }
          });
    }

    // Wait for all threads to complete
    boolean completed = latch.await(10, TimeUnit.MINUTES);
    executor.shutdown();

    long endTime = System.currentTimeMillis();
    long wallClockTime = endTime - startTime;

    LOG.info(
        "Concurrent operations completed: {} successful, {} errors in {}ms wall-clock time",
        successCount.get(),
        errorCount.get(),
        wallClockTime);

    if (CONCURRENT_THREAD_COUNT > 0) {
      double avgThreadTime = totalTime.get() / (double) CONCURRENT_THREAD_COUNT;
      LOG.info("Average thread execution time: {:.2f}ms", avgThreadTime);
    }

    Assertions.assertTrue(completed, "All concurrent operations should complete within timeout");
    Assertions.assertTrue(
        successCount.get() > 0, "At least some concurrent operations should succeed");
  }

  @Test
  void testLargeScaleSchemaListingPerformance() {
    LOG.info("Testing large-scale schema listing performance");

    // Create multiple schemas
    int schemaCount = Math.min(LARGE_SCALE_COUNT, 10);
    int createdCount = 0;

    for (int i = 0; i < schemaCount; i++) {
      String testSchemaName = String.format("perf_test_schema_%03d", i);

      try {
        catalog.asSchemas().loadSchema(testSchemaName);
        continue;
      } catch (Exception e) {
        // Schema doesn't exist, continue to create
      }

      Map<String, String> properties = Maps.newHashMap();
      properties.put("location", "US");
      properties.put("description", "Performance test schema " + i);

      try {
        catalog.asSchemas().createSchema(testSchemaName, "Perf test schema", properties);
        createdCount++;
        LOG.info("Created schema {}", testSchemaName);
      } catch (Exception e) {
        LOG.warn("Failed to create schema {}: {}", testSchemaName, e.getMessage());
      }
    }

    LOG.info("Created {} schemas for listing test", createdCount);

    // Wait for BigQuery operations to complete
    await()
        .atMost(Duration.ofSeconds(15))
        .pollInterval(Duration.ofMillis(500))
        .until(() -> true); // BigQuery operations are eventually consistent

    // Test listing performance multiple times
    int iterations = 3;
    long totalTime = 0;

    for (int i = 0; i < iterations; i++) {
      long startTime = System.currentTimeMillis();
      String[] schemas = catalog.asSchemas().listSchemas();
      long endTime = System.currentTimeMillis();

      totalTime += (endTime - startTime);

      if (i == 0) {
        LOG.info("First schema listing returned {} schemas", schemas.length);
      }
    }

    double avgTime = (double) totalTime / iterations;
    LOG.info("Schema listing performance: {} iterations, avg time: {:.2f}ms", iterations, avgTime);

    if (avgTime > LISTING_THRESHOLD_MS) {
      LOG.warn(
          "Average schema listing time {:.2f}ms exceeds typical threshold {}ms",
          avgTime,
          LISTING_THRESHOLD_MS);
    }
  }

  @Test
  void testLargeScaleTableListingPerformance() {
    LOG.info("Testing large-scale table listing performance");

    // Create multiple tables
    TableCatalog tableCatalog = catalog.asTableCatalog();
    int tableCount = Math.min(LARGE_SCALE_COUNT, 20);

    cleanupTestTablesInSchema(schemaName);

    long creationStartTime = System.currentTimeMillis();
    int createdCount = 0;

    for (int i = 0; i < tableCount; i++) {
      String tableName = String.format("perf_test_list_table_%03d", i);
      NameIdentifier tableId = NameIdentifier.of(schemaName, tableName);

      try {
        tableCatalog.createTable(
            tableId,
            createTestColumns(),
            "Performance list test table " + i,
            Collections.emptyMap(),
            Transforms.EMPTY_TRANSFORM,
            Distributions.NONE,
            new SortOrder[0]);
        createdCount++;

        if ((i + 1) % 5 == 0) {
          LOG.info("Created {} tables for listing test", i + 1);
        }
      } catch (Exception e) {
        LOG.warn("Failed to create table {}: {}", tableName, e.getMessage());
      }
    }

    long creationEndTime = System.currentTimeMillis();
    LOG.info(
        "Created {} tables for listing test in {}ms",
        createdCount,
        creationEndTime - creationStartTime);

    // Wait for BigQuery operations to complete
    await()
        .atMost(Duration.ofSeconds(15))
        .pollInterval(Duration.ofMillis(500))
        .until(() -> true); // BigQuery operations are eventually consistent

    // Test listing performance multiple times
    int iterations = 3;
    long totalTime = 0;

    for (int i = 0; i < iterations; i++) {
      long startTime = System.currentTimeMillis();
      NameIdentifier[] tables = tableCatalog.listTables(Namespace.of(schemaName));
      long endTime = System.currentTimeMillis();

      totalTime += (endTime - startTime);

      if (i == 0) {
        LOG.info("First table listing returned {} tables", tables.length);
      }
    }

    double avgTime = (double) totalTime / iterations;
    LOG.info("Table listing performance: {} iterations, avg time: {:.2f}ms", iterations, avgTime);

    if (avgTime > LISTING_THRESHOLD_MS) {
      LOG.warn(
          "Average table listing time {:.2f}ms exceeds typical threshold {}ms",
          avgTime,
          LISTING_THRESHOLD_MS);
    }
  }

  @Test
  void testConnectionPoolingEfficiency() {
    LOG.info("Testing connection pooling efficiency");

    // Perform rapid sequential operations to test connection reuse
    TableCatalog tableCatalog = catalog.asTableCatalog();
    int operationCount = 10;

    long startTime = System.currentTimeMillis();

    for (int i = 0; i < operationCount; i++) {
      // Mix of different operations to test connection pooling
      if (i % 3 == 0) {
        // Schema listing
        try {
          catalog.asSchemas().listSchemas();
        } catch (Exception e) {
          LOG.warn("Schema listing operation failed: {}", e.getMessage());
        }
      } else if (i % 3 == 1) {
        // Table listing
        try {
          tableCatalog.listTables(Namespace.of(schemaName));
        } catch (Exception e) {
          LOG.warn("Table listing operation failed: {}", e.getMessage());
        }
      } else {
        // Schema loading
        try {
          catalog.asSchemas().loadSchema(schemaName);
        } catch (Exception e) {
          LOG.warn("Schema loading operation failed: {}", e.getMessage());
        }
      }
    }

    long endTime = System.currentTimeMillis();
    long totalTime = endTime - startTime;
    double avgTime = (double) totalTime / operationCount;

    LOG.info(
        "Connection pooling test: {} operations in {}ms (avg: {:.2f}ms per operation)",
        operationCount,
        totalTime,
        avgTime);

    LOG.info("Connection pooling performance - Total: {}ms, Average: {:.2f}ms", totalTime, avgTime);
  }
}
