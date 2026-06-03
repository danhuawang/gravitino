/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.gravitino.integration.test.glue;

import com.google.common.collect.Maps;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.client.GravitinoAdminClient;
import org.apache.gravitino.client.GravitinoMetalake;
import org.apache.gravitino.exceptions.NoSuchSchemaException;
import org.apache.gravitino.exceptions.NoSuchTableException;
import org.apache.gravitino.exceptions.SchemaAlreadyExistsException;
import org.apache.gravitino.exceptions.TableAlreadyExistsException;
import org.apache.gravitino.rel.Column;
import org.apache.gravitino.rel.types.Types;
import org.apache.gravitino.utils.RandomNameUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * E2E integration tests for Glue catalog boundary conditions and error handling.
 *
 * <p>Test plan section 6: Boundary Conditions and Error Handling
 *
 * <ul>
 *   <li>6.1 Create an already existing schema — should throw SchemaAlreadyExistsException
 *   <li>6.2 Load a non-existent schema — should throw NoSuchSchemaException
 *   <li>6.3 Create an already existing table — should throw TableAlreadyExistsException
 *   <li>6.4 Load a non-existent table — should throw NoSuchTableException
 *   <li>6.5 Schema name exceeds length limit (>255 chars) — should return a clear error
 *   <li>6.6 Table name exceeds length limit (>255 chars) — should return a clear error
 *   <li>6.7 Large number of tables pagination — verify listTables paginates correctly with >100
 *       tables
 *   <li>6.8 Large number of schemas pagination — verify listSchemas paginates correctly with >100
 *       databases
 *   <li>6.9 Concurrent creation of same-name schema — verify idempotency or correct conflict error
 * </ul>
 *
 * <p>Tests run against a real Gravitino server with a Glue catalog configured.
 */
@DisplayName("Glue Catalog Boundary Conditions Integration Tests")
public class GlueBoundaryConditionIT {

  private static final Logger LOG = LoggerFactory.getLogger(GlueBoundaryConditionIT.class);

  private static GravitinoAdminClient adminClient;
  private static GravitinoMetalake metalake;
  private static Catalog glueCatalog;
  private static String metalakeName;
  private static String glueCatalogName;

  /** Unique prefix for this test run to avoid collisions across parallel runs. */
  private static String testRunPrefix;

  @BeforeAll
  public static void setup() {
    String gravitinoUri = System.getProperty("gravitino.uri", "http://localhost:30090");
    String simpleUser = System.getProperty("gravitino.simple.user", "admin");

    adminClient = GravitinoAdminClient.builder(gravitinoUri).withSimpleAuth(simpleUser).build();

    metalakeName = RandomNameUtils.genRandomName("glue_boundary_metalake");
    metalake =
        adminClient.createMetalake(
            metalakeName, "Metalake for Glue boundary tests", Collections.emptyMap());

    glueCatalogName = RandomNameUtils.genRandomName("glue_boundary");
    Map<String, String> glueProps = Maps.newHashMap();
    glueProps.put("aws-region", System.getProperty("glue.aws.region", "us-east-1"));
    glueProps.put("aws-glue-catalog-id", System.getProperty("glue.aws.catalog.id", "730335553010"));
    glueProps.put(
        "warehouse",
        System.getProperty("glue.aws.warehouse", "s3://gravitino-glue-test/warehouse"));

    String accessKey = System.getProperty("glue.aws.access.key.id");
    String secretKey = System.getProperty("glue.aws.secret.access.key");
    if (accessKey != null && secretKey != null) {
      glueProps.put("aws-access-key-id", accessKey);
      glueProps.put("aws-secret-access-key", secretKey);
    }

    String glueEndpoint = System.getProperty("glue.aws.endpoint");
    if (glueEndpoint != null) {
      glueProps.put("aws-glue-endpoint", glueEndpoint);
    }

    glueCatalog =
        metalake.createCatalog(
            glueCatalogName,
            Catalog.Type.RELATIONAL,
            "glue",
            "Glue catalog for boundary condition tests",
            glueProps);

    testRunPrefix = RandomNameUtils.genRandomName("gb");
    LOG.info(
        "GlueBoundaryConditionIT setup complete: metalake={}, glueCatalog={}, prefix={}",
        metalakeName,
        glueCatalogName,
        testRunPrefix);
  }

  @AfterAll
  public static void teardown() {
    try {
      if (metalake != null && glueCatalogName != null) {
        metalake.dropCatalog(glueCatalogName, true);
      }
      if (adminClient != null && metalakeName != null) {
        adminClient.dropMetalake(metalakeName, true);
      }
    } catch (Exception e) {
      LOG.warn("Teardown failed, proceeding anyway", e);
    } finally {
      if (adminClient != null) {
        adminClient.close();
      }
    }
  }

  @AfterEach
  public void cleanupSchemas() {
    try {
      String[] schemas = glueCatalog.asSchemas().listSchemas();
      for (String schema : schemas) {
        if (schema.startsWith(testRunPrefix)) {
          try {
            glueCatalog.asSchemas().dropSchema(schema, true);
          } catch (Exception e) {
            LOG.warn("Failed to cleanup schema: {}", schema, e);
          }
        }
      }
    } catch (Exception e) {
      LOG.warn("Failed to list schemas during cleanup", e);
    }
  }

  // ── 6.1 Create an already existing schema ────────────────────────────────

  @Test
  @DisplayName("6.1 Create an already existing schema should throw SchemaAlreadyExistsException")
  public void testCreateAlreadyExistingSchema() {
    String schemaName = testRunPrefix + "_duplicate";

    // Create schema first time — should succeed
    glueCatalog.asSchemas().createSchema(schemaName, "first creation", Collections.emptyMap());

    // Verify schema exists
    Assertions.assertTrue(
        glueCatalog.asSchemas().schemaExists(schemaName),
        "Schema should exist after first creation");

    // Create schema second time with the same name — should throw SchemaAlreadyExistsException
    Assertions.assertThrows(
        SchemaAlreadyExistsException.class,
        () ->
            glueCatalog
                .asSchemas()
                .createSchema(schemaName, "second creation", Collections.emptyMap()),
        "Creating an already existing schema should throw SchemaAlreadyExistsException");

    LOG.info(
        "6.1 verified: duplicate schema creation correctly throws SchemaAlreadyExistsException");
  }

  // ── 6.2 Load a non-existent schema ───────────────────────────────────────

  @Test
  @DisplayName("6.2 Load a non-existent schema should throw NoSuchSchemaException")
  public void testLoadNonExistentSchema() {
    String schemaName = testRunPrefix + "_nonexistent_schema";

    // Verify schema does not exist
    Assertions.assertFalse(
        glueCatalog.asSchemas().schemaExists(schemaName),
        "Schema should not exist before the test");

    // Load non-existent schema — should throw NoSuchSchemaException
    Assertions.assertThrows(
        NoSuchSchemaException.class,
        () -> glueCatalog.asSchemas().loadSchema(schemaName),
        "Loading a non-existent schema should throw NoSuchSchemaException");

    LOG.info("6.2 verified: loading non-existent schema correctly throws NoSuchSchemaException");
  }

  // ── 6.3 Create an already existing table ─────────────────────────────────

  @Test
  @DisplayName("6.3 Create an already existing table should throw TableAlreadyExistsException")
  public void testCreateAlreadyExistingTable() {
    String schemaName = testRunPrefix + "_dup_table";

    // Create schema
    glueCatalog.asSchemas().createSchema(schemaName, "table dup test", Collections.emptyMap());

    // Create table first time
    String tableName = "existing_table";
    Column[] columns = {Column.of("id", Types.IntegerType.get(), "id column")};
    Map<String, String> tableProps = Maps.newHashMap();
    tableProps.put("input-format", "org.apache.hadoop.mapred.TextInputFormat");
    tableProps.put("output-format", "org.apache.hadoop.hive.ql.io.HiveIgnoreKeyTextOutputFormat");
    tableProps.put("serde-lib", "org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe");

    glueCatalog
        .asTableCatalog()
        .createTable(
            NameIdentifier.of(schemaName, tableName), columns, "first creation", tableProps);

    // Create table second time with the same name — should throw TableAlreadyExistsException
    Assertions.assertThrows(
        TableAlreadyExistsException.class,
        () ->
            glueCatalog
                .asTableCatalog()
                .createTable(
                    NameIdentifier.of(schemaName, tableName),
                    columns,
                    "second creation",
                    tableProps),
        "Creating an already existing table should throw TableAlreadyExistsException");

    LOG.info("6.3 verified: duplicate table creation correctly throws TableAlreadyExistsException");
  }

  // ── 6.4 Load a non-existent table ────────────────────────────────────────

  @Test
  @DisplayName("6.4 Load a non-existent table should throw NoSuchTableException")
  public void testLoadNonExistentTable() {
    String schemaName = testRunPrefix + "_no_table";

    // Create schema so the namespace is valid
    glueCatalog.asSchemas().createSchema(schemaName, "no table test", Collections.emptyMap());

    // Load non-existent table — should throw NoSuchTableException
    Assertions.assertThrows(
        NoSuchTableException.class,
        () ->
            glueCatalog
                .asTableCatalog()
                .loadTable(NameIdentifier.of(schemaName, "nonexistent_table")),
        "Loading a non-existent table should throw NoSuchTableException");

    LOG.info("6.4 verified: loading non-existent table correctly throws NoSuchTableException");
  }

  // ── 6.5 Schema name exceeds length limit ─────────────────────────────────

  @Test
  @DisplayName("6.5 Schema name exceeds 255 chars should return a clear error")
  public void testSchemaNameExceedsLengthLimit() {
    // Glue database names are limited to 255 characters
    StringBuilder sb = new StringBuilder(testRunPrefix + "_");
    while (sb.length() <= 255) {
      sb.append("a");
    }
    String longSchemaName = sb.toString();

    Assertions.assertTrue(
        longSchemaName.length() > 255,
        "Schema name should exceed 255 chars, got: " + longSchemaName.length());

    // Creating a schema with a name exceeding 255 chars should throw an exception
    Exception exception =
        Assertions.assertThrows(
            Exception.class,
            () ->
                glueCatalog
                    .asSchemas()
                    .createSchema(longSchemaName, "too long name", Collections.emptyMap()),
            "Creating schema with name > 255 chars should fail with a clear error");

    Assertions.assertNotNull(exception.getMessage(), "Exception message should not be null");
    LOG.info(
        "6.5 verified: schema name exceeding 255 chars rejected with error: {}",
        exception.getMessage());
  }

  // ── 6.6 Table name exceeds length limit ──────────────────────────────────

  @Test
  @DisplayName("6.6 Table name exceeds 255 chars should return a clear error")
  public void testTableNameExceedsLengthLimit() {
    String schemaName = testRunPrefix + "_long_tbl";

    // Create a valid schema first
    glueCatalog
        .asSchemas()
        .createSchema(schemaName, "long table name test", Collections.emptyMap());

    // Build a table name exceeding 255 characters
    StringBuilder sb = new StringBuilder("tbl_");
    while (sb.length() <= 255) {
      sb.append("a");
    }
    String longTableName = sb.toString();

    Assertions.assertTrue(
        longTableName.length() > 255,
        "Table name should exceed 255 chars, got: " + longTableName.length());

    Column[] columns = {Column.of("id", Types.IntegerType.get(), "id column")};
    Map<String, String> tableProps = Maps.newHashMap();
    tableProps.put("input-format", "org.apache.hadoop.mapred.TextInputFormat");
    tableProps.put("output-format", "org.apache.hadoop.hive.ql.io.HiveIgnoreKeyTextOutputFormat");
    tableProps.put("serde-lib", "org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe");

    // Creating a table with a name exceeding 255 chars should throw an exception
    Exception exception =
        Assertions.assertThrows(
            Exception.class,
            () ->
                glueCatalog
                    .asTableCatalog()
                    .createTable(
                        NameIdentifier.of(schemaName, longTableName),
                        columns,
                        "too long name",
                        tableProps),
            "Creating table with name > 255 chars should fail with a clear error");

    Assertions.assertNotNull(exception.getMessage(), "Exception message should not be null");
    LOG.info(
        "6.6 verified: table name exceeding 255 chars rejected with error: {}",
        exception.getMessage());
  }

  // ── 6.7 Large number of tables pagination ────────────────────────────────

  @Test
  @DisplayName("6.7 listTables paginates correctly with >100 tables")
  public void testListTablesPagination() {
    String schemaName = testRunPrefix + "_pagination";

    // Create schema
    glueCatalog.asSchemas().createSchema(schemaName, "pagination test", Collections.emptyMap());

    // Create more than 100 tables (Glue default page size is 100)
    int tableCount = 105;
    Column[] columns = {Column.of("id", Types.IntegerType.get(), "id column")};
    Map<String, String> tableProps = Maps.newHashMap();
    tableProps.put("input-format", "org.apache.hadoop.mapred.TextInputFormat");
    tableProps.put("output-format", "org.apache.hadoop.hive.ql.io.HiveIgnoreKeyTextOutputFormat");
    tableProps.put("serde-lib", "org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe");

    for (int i = 0; i < tableCount; i++) {
      String tableName = String.format("tbl_%03d", i);
      glueCatalog
          .asTableCatalog()
          .createTable(NameIdentifier.of(schemaName, tableName), columns, "table " + i, tableProps);
    }

    LOG.info("Created {} tables in schema '{}'", tableCount, schemaName);

    // List tables — should return all tables despite Glue's 100-per-page limit
    NameIdentifier[] tables = glueCatalog.asTableCatalog().listTables(Namespace.of(schemaName));

    Assertions.assertNotNull(tables, "listTables should not return null");
    Assertions.assertEquals(
        tableCount,
        tables.length,
        "listTables should return all " + tableCount + " tables (pagination handled internally)");

    LOG.info(
        "6.7 verified: listTables returned {} tables (expected {})", tables.length, tableCount);
  }

  // ── 6.8 Large number of schemas pagination ───────────────────────────────

  @Test
  @DisplayName("6.8 listSchemas paginates correctly with >100 schemas")
  public void testListSchemasPagination() {
    // Create more than 100 schemas (Glue default page size is 100)
    int schemaCount = 105;

    for (int i = 0; i < schemaCount; i++) {
      String schemaName = String.format("%s_pg_%03d", testRunPrefix, i);
      glueCatalog
          .asSchemas()
          .createSchema(schemaName, "pagination schema " + i, Collections.emptyMap());
    }

    LOG.info("Created {} schemas with prefix '{}'", schemaCount, testRunPrefix);

    // List schemas — should return all schemas despite Glue's 100-per-page limit
    String[] schemas = glueCatalog.asSchemas().listSchemas();
    Assertions.assertNotNull(schemas, "listSchemas should not return null");

    // Count schemas matching our test prefix
    long matchingCount = 0;
    for (String schema : schemas) {
      if (schema.startsWith(testRunPrefix + "_pg_")) {
        matchingCount++;
      }
    }

    Assertions.assertEquals(
        schemaCount,
        matchingCount,
        "listSchemas should return all "
            + schemaCount
            + " test schemas (pagination handled internally), found: "
            + matchingCount);

    LOG.info(
        "6.8 verified: listSchemas returned {} matching schemas (expected {})",
        matchingCount,
        schemaCount);
  }

  // ── 6.9 Concurrent creation of same-name schema ──────────────────────────

  @Test
  @DisplayName("6.9 Concurrent creation of same-name schema - idempotency or correct conflict")
  public void testConcurrentSchemaCreation() throws InterruptedException {
    String schemaName = testRunPrefix + "_concurrent";
    int threadCount = 5;

    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger failureCount = new AtomicInteger(0);

    // Launch multiple threads that all try to create the same schema simultaneously
    for (int i = 0; i < threadCount; i++) {
      executor.submit(
          () -> {
            try {
              startLatch.await(); // Wait for all threads to be ready
              glueCatalog
                  .asSchemas()
                  .createSchema(schemaName, "concurrent test", Collections.emptyMap());
              successCount.incrementAndGet();
            } catch (SchemaAlreadyExistsException e) {
              failureCount.incrementAndGet();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            } catch (Exception e) {
              // Other exceptions also count as conflict failures
              failureCount.incrementAndGet();
              LOG.info("Concurrent creation got exception: {}", e.getMessage());
            } finally {
              doneLatch.countDown();
            }
          });
    }

    // Release all threads at once
    startLatch.countDown();
    doneLatch.await();
    executor.shutdown();

    LOG.info(
        "Concurrent schema creation results: successes={}, failures={}",
        successCount.get(),
        failureCount.get());

    // Exactly one thread should succeed, the rest should get conflict errors
    Assertions.assertEquals(
        1,
        successCount.get(),
        "Exactly one concurrent creation should succeed, got: " + successCount.get());
    Assertions.assertEquals(
        threadCount - 1,
        failureCount.get(),
        "Remaining threads should fail with conflict, got failures: " + failureCount.get());

    // Verify the schema actually exists
    Assertions.assertTrue(
        glueCatalog.asSchemas().schemaExists(schemaName),
        "Schema should exist after concurrent creation");

    LOG.info("6.9 verified: concurrent schema creation correctly handles conflicts");
  }
}
