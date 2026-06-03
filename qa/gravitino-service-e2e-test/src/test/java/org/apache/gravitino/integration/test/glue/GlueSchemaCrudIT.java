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
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Schema;
import org.apache.gravitino.SchemaChange;
import org.apache.gravitino.client.GravitinoAdminClient;
import org.apache.gravitino.client.GravitinoMetalake;
import org.apache.gravitino.exceptions.NonEmptySchemaException;
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
 * E2E integration tests for Glue catalog schema CRUD operations.
 *
 * <p>Test plan section 1: Schema CRUD Basic Operations
 *
 * <ul>
 *   <li>1.1 Create schema and verify properties passthrough
 *   <li>1.2 Alter schema (set/remove property)
 *   <li>1.3 Drop non-empty schema (cascade=false) — should throw NonEmptySchemaException
 *   <li>1.4 Drop non-empty schema (cascade=true) — should succeed
 *   <li>1.5 Case-insensitive verification (Glue folds to lowercase)
 * </ul>
 *
 * <p>Tests run against a real Gravitino server with a Glue catalog configured. The server is
 * expected to be deployed with the Glue catalog plugin available.
 */
@DisplayName("Glue Catalog Schema CRUD Integration Tests")
public class GlueSchemaCrudIT {

  private static final Logger LOG = LoggerFactory.getLogger(GlueSchemaCrudIT.class);

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

    metalakeName = RandomNameUtils.genRandomName("glue_test_metalake");
    metalake =
        adminClient.createMetalake(metalakeName, "Metalake for Glue tests", Collections.emptyMap());

    // Create a Glue catalog for testing. Properties are sourced from system properties
    // so that different environments (LocalStack, real AWS) can be configured externally.
    glueCatalogName = RandomNameUtils.genRandomName("glue_schema_crud");
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
            "Glue catalog for schema CRUD tests",
            glueProps);

    testRunPrefix = RandomNameUtils.genRandomName("gs");
    LOG.info(
        "GlueSchemaCrudIT setup complete: metalake={}, glueCatalog={}, prefix={}",
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
    // Best-effort cleanup of schemas created during each test
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

  // ── 1.1 Create schema and verify properties passthrough ───────────────────

  @Test
  @DisplayName("1.1 Create schema and verify description, locationUri, and custom parameters")
  public void testCreateSchemaWithPropertiesPassthrough() {
    String schemaName = testRunPrefix + "_create_props";
    String comment = "Test schema with properties";

    Map<String, String> properties = Maps.newHashMap();
    properties.put("location", "s3://test-bucket/test-location/");
    properties.put("custom-key-1", "custom-value-1");
    properties.put("custom-key-2", "custom-value-2");

    // Create schema
    Schema createdSchema = glueCatalog.asSchemas().createSchema(schemaName, comment, properties);

    // Verify created schema
    Assertions.assertNotNull(createdSchema, "Created schema should not be null");
    Assertions.assertEquals(schemaName, createdSchema.name(), "Schema name should match");
    Assertions.assertEquals(comment, createdSchema.comment(), "Schema comment should match");

    // Load schema and verify properties are persisted
    Schema loadedSchema = glueCatalog.asSchemas().loadSchema(schemaName);
    Assertions.assertNotNull(loadedSchema, "Loaded schema should not be null");
    Assertions.assertEquals(schemaName, loadedSchema.name(), "Loaded schema name should match");
    Assertions.assertEquals(comment, loadedSchema.comment(), "Loaded schema comment should match");

    // Verify properties passthrough
    Map<String, String> loadedProps = loadedSchema.properties();
    Assertions.assertEquals(
        "s3://test-bucket/test-location/",
        loadedProps.get("location"),
        "locationUri should be correctly mapped");
    Assertions.assertEquals(
        "custom-value-1", loadedProps.get("custom-key-1"), "Custom property 1 should be preserved");
    Assertions.assertEquals(
        "custom-value-2", loadedProps.get("custom-key-2"), "Custom property 2 should be preserved");

    // Verify schema appears in list
    String[] schemas = glueCatalog.asSchemas().listSchemas();
    Set<String> schemaSet = Arrays.stream(schemas).collect(Collectors.toSet());
    Assertions.assertTrue(
        schemaSet.contains(schemaName),
        "Created schema should appear in listSchemas, got: " + schemaSet);
  }

  // ── 1.2 Alter schema (set/remove property) ───────────────────────────────

  @Test
  @DisplayName("1.2 Alter schema - set and remove properties")
  public void testAlterSchemaSetAndRemoveProperty() {
    String schemaName = testRunPrefix + "_alter_props";

    // Create schema with initial properties
    Map<String, String> initialProps = Maps.newHashMap();
    initialProps.put("key1", "value1");
    initialProps.put("key2", "value2");
    glueCatalog.asSchemas().createSchema(schemaName, "alter test schema", initialProps);

    // Verify initial state
    Schema loaded = glueCatalog.asSchemas().loadSchema(schemaName);
    Assertions.assertEquals("value1", loaded.properties().get("key1"));
    Assertions.assertEquals("value2", loaded.properties().get("key2"));

    // Set a new property
    Schema alteredSet =
        glueCatalog.asSchemas().alterSchema(schemaName, SchemaChange.setProperty("key3", "value3"));
    Assertions.assertEquals(
        "value3", alteredSet.properties().get("key3"), "New property should be set");

    // Verify via reload
    Schema reloaded = glueCatalog.asSchemas().loadSchema(schemaName);
    Assertions.assertEquals("value1", reloaded.properties().get("key1"), "key1 should remain");
    Assertions.assertEquals("value2", reloaded.properties().get("key2"), "key2 should remain");
    Assertions.assertEquals("value3", reloaded.properties().get("key3"), "key3 should be added");

    // Update an existing property
    glueCatalog
        .asSchemas()
        .alterSchema(schemaName, SchemaChange.setProperty("key1", "updated-value1"));
    Schema afterUpdate = glueCatalog.asSchemas().loadSchema(schemaName);
    Assertions.assertEquals(
        "updated-value1", afterUpdate.properties().get("key1"), "key1 should be updated");

    // Remove a property
    glueCatalog.asSchemas().alterSchema(schemaName, SchemaChange.removeProperty("key2"));
    Schema afterRemove = glueCatalog.asSchemas().loadSchema(schemaName);
    Assertions.assertNull(
        afterRemove.properties().get("key2"), "key2 should be removed after removeProperty");
    Assertions.assertEquals(
        "updated-value1",
        afterRemove.properties().get("key1"),
        "key1 should still exist after removing key2");
    Assertions.assertEquals(
        "value3",
        afterRemove.properties().get("key3"),
        "key3 should still exist after removing key2");

    // Multiple changes in one call
    glueCatalog
        .asSchemas()
        .alterSchema(
            schemaName,
            SchemaChange.setProperty("batch-key", "batch-value"),
            SchemaChange.removeProperty("key3"));
    Schema afterBatch = glueCatalog.asSchemas().loadSchema(schemaName);
    Assertions.assertEquals(
        "batch-value", afterBatch.properties().get("batch-key"), "batch-key should be set");
    Assertions.assertNull(afterBatch.properties().get("key3"), "key3 should be removed in batch");
  }

  // ── 1.3 Drop non-empty schema (cascade=false) ────────────────────────────

  @Test
  @DisplayName("1.3 Drop non-empty schema with cascade=false should throw NonEmptySchemaException")
  public void testDropNonEmptySchemaWithoutCascadeShouldFail() {
    String schemaName = testRunPrefix + "_nonempty_nocascade";

    // Create schema
    glueCatalog
        .asSchemas()
        .createSchema(schemaName, "non-empty schema test", Collections.emptyMap());

    // Create a table in the schema to make it non-empty
    Column[] columns = {Column.of("id", Types.IntegerType.get(), "id column")};
    glueCatalog
        .asTableCatalog()
        .createTable(
            NameIdentifier.of(schemaName, "test_table"),
            columns,
            "table to make schema non-empty",
            Collections.emptyMap());

    // Attempt to drop non-empty schema without cascade — should throw
    Assertions.assertThrows(
        NonEmptySchemaException.class,
        () -> glueCatalog.asSchemas().dropSchema(schemaName, false),
        "Dropping a non-empty schema with cascade=false should throw NonEmptySchemaException");

    // Verify schema still exists
    Assertions.assertTrue(
        glueCatalog.asSchemas().schemaExists(schemaName),
        "Schema should still exist after failed drop");
  }

  // ── 1.4 Drop non-empty schema (cascade=true) ─────────────────────────────

  @Test
  @DisplayName("1.4 Drop non-empty schema with cascade=true should succeed")
  public void testDropNonEmptySchemaWithCascadeShouldSucceed() {
    String schemaName = testRunPrefix + "_nonempty_cascade";

    // Create schema
    glueCatalog.asSchemas().createSchema(schemaName, "cascade drop test", Collections.emptyMap());

    // Create multiple tables in the schema
    Column[] columns = {
      Column.of("id", Types.IntegerType.get(), "id column"),
      Column.of("name", Types.StringType.get(), "name column")
    };
    glueCatalog
        .asTableCatalog()
        .createTable(
            NameIdentifier.of(schemaName, "table_one"),
            columns,
            "first table",
            Collections.emptyMap());
    glueCatalog
        .asTableCatalog()
        .createTable(
            NameIdentifier.of(schemaName, "table_two"),
            columns,
            "second table",
            Collections.emptyMap());

    // Verify tables exist
    NameIdentifier[] tables =
        glueCatalog.asTableCatalog().listTables(org.apache.gravitino.Namespace.of(schemaName));
    Assertions.assertEquals(2, tables.length, "Schema should have 2 tables before cascade drop");

    // Drop with cascade=true — should succeed
    boolean dropped = glueCatalog.asSchemas().dropSchema(schemaName, true);
    Assertions.assertTrue(dropped, "dropSchema with cascade=true should return true");

    // Verify schema no longer exists
    Assertions.assertFalse(
        glueCatalog.asSchemas().schemaExists(schemaName),
        "Schema should not exist after cascade drop");
  }

  // ── 1.5 Case-insensitive verification ────────────────────────────────────

  @Test
  @DisplayName(
      "1.5 Case-insensitive: create MySchema, load with myschema (Glue folds to lowercase)")
  public void testCaseInsensitiveSchemaName() {
    // Glue database names are case-insensitive and folded to lowercase.
    // Creating "MySchema" should be loadable as "myschema".
    String mixedCaseName = testRunPrefix + "_MySchema";
    String lowerCaseName = mixedCaseName.toLowerCase();

    Map<String, String> properties = Maps.newHashMap();
    properties.put("test-key", "test-value");

    // Create schema with mixed-case name
    Schema created = glueCatalog.asSchemas().createSchema(mixedCaseName, "case test", properties);
    Assertions.assertNotNull(created, "Schema creation should succeed");

    // Load schema using lowercase name — should succeed because Glue folds to lowercase
    Schema loadedLower = glueCatalog.asSchemas().loadSchema(lowerCaseName);
    Assertions.assertNotNull(
        loadedLower, "Loading schema with lowercase name should succeed (Glue folds to lowercase)");
    Assertions.assertEquals(
        "test-value",
        loadedLower.properties().get("test-key"),
        "Properties should be accessible via lowercase name");

    // Verify the schema name stored in Glue is lowercase
    Assertions.assertEquals(
        lowerCaseName, loadedLower.name(), "Glue should fold schema name to lowercase");

    // Verify schema appears in list (as lowercase)
    String[] schemas = glueCatalog.asSchemas().listSchemas();
    Set<String> schemaSet = Arrays.stream(schemas).collect(Collectors.toSet());
    Assertions.assertTrue(
        schemaSet.contains(lowerCaseName),
        "Schema should appear in list as lowercase, got: " + schemaSet);

    // Cleanup using lowercase name
    glueCatalog.asSchemas().dropSchema(lowerCaseName, false);
  }
}
