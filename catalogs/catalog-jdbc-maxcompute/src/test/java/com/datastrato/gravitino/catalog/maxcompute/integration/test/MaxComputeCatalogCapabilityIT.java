/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.catalog.maxcompute.integration.test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.collect.Maps;
import java.util.Collections;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.catalog.jdbc.config.JdbcConfig;
import org.apache.gravitino.client.GravitinoMetalake;
import org.apache.gravitino.integration.test.util.BaseIT;
import org.apache.gravitino.integration.test.util.GravitinoITUtils;
import org.apache.gravitino.rel.Column;
import org.apache.gravitino.rel.TableCatalog;
import org.apache.gravitino.rel.expressions.distributions.Distributions;
import org.apache.gravitino.rel.expressions.sorts.SortOrder;
import org.apache.gravitino.rel.expressions.transforms.Transforms;
import org.apache.gravitino.rel.indexes.Index;
import org.apache.gravitino.rel.types.Types;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * Integration tests for MaxCompute catalog capability.
 *
 * <p>Tests case sensitivity, name validation, and other capability-related features.
 */
@TestInstance(Lifecycle.PER_CLASS)
@EnabledIf("isMaxComputeConfigured")
public class MaxComputeCatalogCapabilityIT extends BaseIT {

  private static final String PROVIDER = "jdbc-maxcompute";
  private static final String DRIVER_CLASS_NAME = "com.aliyun.odps.jdbc.OdpsDriver";

  private static final String ENV_ACCESS_KEY_ID = "MAXCOMPUTE_ACCESS_KEY_ID";
  private static final String ENV_ACCESS_KEY_SECRET = "MAXCOMPUTE_ACCESS_KEY_SECRET";
  private static final String ENV_PROJECT = "MAXCOMPUTE_PROJECT";
  private static final String ENV_ENDPOINT = "MAXCOMPUTE_ENDPOINT";

  private final String metalakeName = GravitinoITUtils.genRandomName("mc_cap_metalake");
  private final String catalogName = GravitinoITUtils.genRandomName("mc_cap_catalog");
  private final String schemaName = GravitinoITUtils.genRandomName("mc_cap_schema");

  private GravitinoMetalake metalake;
  private Catalog catalog;

  private String accessKeyId;
  private String accessKeySecret;
  private String jdbcUrl;

  // This method is used by @EnabledIf annotation via reflection
  @SuppressWarnings("unused")
  static boolean isMaxComputeConfigured() {
    return StringUtils.isNotBlank(System.getenv(ENV_ACCESS_KEY_ID))
        && StringUtils.isNotBlank(System.getenv(ENV_ACCESS_KEY_SECRET))
        && StringUtils.isNotBlank(System.getenv(ENV_PROJECT))
        && StringUtils.isNotBlank(System.getenv(ENV_ENDPOINT));
  }

  @BeforeAll
  public void startup() {
    accessKeyId = System.getenv(ENV_ACCESS_KEY_ID);
    accessKeySecret = System.getenv(ENV_ACCESS_KEY_SECRET);
    String project = System.getenv(ENV_PROJECT);
    String endpoint = System.getenv(ENV_ENDPOINT);

    jdbcUrl = String.format("jdbc:odps:%s?project=%s&odpsNamespaceSchema=true", endpoint, project);

    createMetalake();
    createCatalog();
    createSchema();
  }

  @AfterAll
  public void stop() {
    if (catalog != null) {
      clearTableAndSchema();
      // Note: We don't drop the catalog because it corresponds to the MaxCompute project
      // which contains other schemas that we don't want to affect
      try {
        metalake.disableCatalog(catalogName);
        metalake.dropCatalog(catalogName);
      } catch (Exception e) {
        // Ignore if catalog cannot be dropped (e.g., has other schemas)
      }
    }
    if (metalake != null) {
      try {
        client.disableMetalake(metalakeName);
        client.dropMetalake(metalakeName);
      } catch (Exception e) {
        // Ignore cleanup errors
      }
    }
  }

  @AfterEach
  public void resetSchema() {
    clearTableAndSchema();
    createSchema();
  }

  private void clearTableAndSchema() {
    try {
      // Only clean up schemas created by this test (with prefix "mc_cap_" or "upper_schema_")
      String[] schemas = catalog.asSchemas().listSchemas();
      for (String schema : schemas) {
        String lowerSchema = schema.toLowerCase();
        // Only clean up test-created schemas
        if (!lowerSchema.startsWith("mc_cap_") && !lowerSchema.startsWith("upper_schema_")) {
          continue;
        }
        try {
          // Drop all tables in the schema first
          NameIdentifier[] tables = catalog.asTableCatalog().listTables(Namespace.of(schema));
          for (NameIdentifier table : tables) {
            catalog.asTableCatalog().dropTable(table);
          }
          // Then drop the schema
          catalog.asSchemas().dropSchema(schema, false);
        } catch (Exception e) {
          // Ignore individual schema cleanup errors
        }
      }
    } catch (Exception e) {
      // Ignore cleanup errors
    }
  }

  private void createMetalake() {
    client.createMetalake(
        metalakeName, "MaxCompute capability IT metalake", Collections.emptyMap());
    metalake = client.loadMetalake(metalakeName);
  }

  private void createCatalog() {
    Map<String, String> properties = Maps.newHashMap();
    properties.put(JdbcConfig.JDBC_URL.getKey(), jdbcUrl);
    properties.put(JdbcConfig.JDBC_DRIVER.getKey(), DRIVER_CLASS_NAME);
    properties.put(JdbcConfig.USERNAME.getKey(), accessKeyId);
    properties.put(JdbcConfig.PASSWORD.getKey(), accessKeySecret);

    catalog =
        metalake.createCatalog(
            catalogName,
            Catalog.Type.RELATIONAL,
            PROVIDER,
            "MaxCompute catalog for capability test",
            properties);
  }

  private void createSchema() {
    catalog.asSchemas().createSchema(schemaName, null, Collections.emptyMap());
  }

  private Column[] createBasicColumns() {
    return new Column[] {
      Column.of("id", Types.LongType.get(), "ID column", false, false, null),
      Column.of("name", Types.StringType.get(), "Name column", true, false, null)
    };
  }

  // ==================== Case Sensitivity Tests ====================

  @Test
  void testCaseInsensitiveSchemaName() {
    // MaxCompute schema names are case-insensitive
    String upperSchemaName = "UPPER_SCHEMA_" + System.currentTimeMillis();
    String lowerSchemaName = upperSchemaName.toLowerCase();

    catalog.asSchemas().createSchema(upperSchemaName, null, Collections.emptyMap());

    // Should be able to load with different case
    assertTrue(catalog.asSchemas().schemaExists(lowerSchemaName));

    // Cleanup
    catalog.asSchemas().dropSchema(upperSchemaName, false);
  }

  @Test
  void testCaseInsensitiveTableName() {
    TableCatalog tableCatalog = catalog.asTableCatalog();
    String upperTableName = "UPPER_TABLE_" + System.currentTimeMillis();
    String lowerTableName = upperTableName.toLowerCase();

    NameIdentifier tableIdent = NameIdentifier.of(schemaName, upperTableName);
    Column[] columns = createBasicColumns();

    tableCatalog.createTable(
        tableIdent,
        columns,
        "Test table",
        Collections.emptyMap(),
        Transforms.EMPTY_TRANSFORM,
        Distributions.NONE,
        new SortOrder[0],
        new Index[0]);

    // Should be able to load with different case
    NameIdentifier lowerIdent = NameIdentifier.of(schemaName, lowerTableName);
    assertTrue(tableCatalog.tableExists(lowerIdent));

    // Cleanup
    tableCatalog.dropTable(tableIdent);
  }

  // ==================== Name Validation Tests ====================

  @Test
  void testSchemaNameWithSqlInjection() {
    String maliciousName = "test`; DROP TABLE important; --";

    assertThrows(
        IllegalArgumentException.class,
        () -> catalog.asSchemas().createSchema(maliciousName, null, Collections.emptyMap()));
  }

  @Test
  void testTableNameWithSqlInjection() {
    TableCatalog tableCatalog = catalog.asTableCatalog();
    String maliciousName = "test`; DROP TABLE important; --";
    NameIdentifier tableIdent = NameIdentifier.of(schemaName, maliciousName);
    Column[] columns = createBasicColumns();

    assertThrows(
        IllegalArgumentException.class,
        () ->
            tableCatalog.createTable(
                tableIdent,
                columns,
                "Test table",
                Collections.emptyMap(),
                Transforms.EMPTY_TRANSFORM,
                Distributions.NONE,
                new SortOrder[0],
                new Index[0]));
  }

  @Test
  void testSchemaNameTooLong() {
    // MaxCompute has a limit on schema name length (128 characters)
    String longName = StringUtils.repeat("a", 129);

    assertThrows(
        IllegalArgumentException.class,
        () -> catalog.asSchemas().createSchema(longName, null, Collections.emptyMap()));
  }

  @Test
  void testTableNameTooLong() {
    TableCatalog tableCatalog = catalog.asTableCatalog();
    String longName = StringUtils.repeat("a", 129);
    NameIdentifier tableIdent = NameIdentifier.of(schemaName, longName);
    Column[] columns = createBasicColumns();

    assertThrows(
        IllegalArgumentException.class,
        () ->
            tableCatalog.createTable(
                tableIdent,
                columns,
                "Test table",
                Collections.emptyMap(),
                Transforms.EMPTY_TRANSFORM,
                Distributions.NONE,
                new SortOrder[0],
                new Index[0]));
  }

  @Test
  void testSchemaNameWithReservedWord() {
    // Test with MaxCompute reserved words
    String[] reservedWords = {"select", "from", "where", "table", "database"};

    for (String word : reservedWords) {
      // Reserved words should be rejected or properly escaped
      // The behavior depends on the capability implementation
      try {
        catalog.asSchemas().createSchema(word, null, Collections.emptyMap());
        // If creation succeeds, clean up
        catalog.asSchemas().dropSchema(word, false);
      } catch (IllegalArgumentException e) {
        // Expected for reserved words
        assertTrue(e.getMessage().contains("reserved") || e.getMessage().contains("invalid"));
      }
    }
  }

  // ==================== Column Default Value Tests ====================

  @Test
  void testColumnWithDefaultValue() {
    TableCatalog tableCatalog = catalog.asTableCatalog();
    String testTableName = GravitinoITUtils.genRandomName("mc_default_val");
    NameIdentifier tableIdent = NameIdentifier.of(schemaName, testTableName);

    Column[] columns =
        new Column[] {
          Column.of("id", Types.LongType.get(), "ID column", false, false, null),
          Column.of(
              "status",
              Types.IntegerType.get(),
              "Status with default",
              true,
              false,
              org.apache.gravitino.rel.expressions.literals.Literals.integerLiteral(0)),
          Column.of(
              "name",
              Types.StringType.get(),
              "Name with default",
              true,
              false,
              org.apache.gravitino.rel.expressions.literals.Literals.stringLiteral("unknown"))
        };

    tableCatalog.createTable(
        tableIdent,
        columns,
        "Table with default values",
        Collections.emptyMap(),
        Transforms.EMPTY_TRANSFORM,
        Distributions.NONE,
        new SortOrder[0],
        new Index[0]);

    // Load and verify
    assertTrue(tableCatalog.tableExists(tableIdent));

    // Cleanup
    tableCatalog.dropTable(tableIdent);
  }

  // ==================== Property Validation Tests ====================

  @Test
  void testInvalidLifecycleValue() {
    TableCatalog tableCatalog = catalog.asTableCatalog();
    String testTableName = GravitinoITUtils.genRandomName("mc_invalid_lc");
    NameIdentifier tableIdent = NameIdentifier.of(schemaName, testTableName);
    Column[] columns = createBasicColumns();

    // Test non-numeric lifecycle
    Map<String, String> invalidProps = Maps.newHashMap();
    invalidProps.put("lifecycle", "invalid");

    assertThrows(
        IllegalArgumentException.class,
        () ->
            tableCatalog.createTable(
                tableIdent,
                columns,
                "Test table",
                invalidProps,
                Transforms.EMPTY_TRANSFORM,
                Distributions.NONE,
                new SortOrder[0],
                new Index[0]));

    // Test negative lifecycle
    Map<String, String> negativeProps = Maps.newHashMap();
    negativeProps.put("lifecycle", "-1");

    assertThrows(
        IllegalArgumentException.class,
        () ->
            tableCatalog.createTable(
                tableIdent,
                columns,
                "Test table",
                negativeProps,
                Transforms.EMPTY_TRANSFORM,
                Distributions.NONE,
                new SortOrder[0],
                new Index[0]));
  }

  @Test
  void testInvalidWriteBucketNum() {
    TableCatalog tableCatalog = catalog.asTableCatalog();
    String testTableName = GravitinoITUtils.genRandomName("mc_invalid_bucket");
    NameIdentifier tableIdent = NameIdentifier.of(schemaName, testTableName);
    Column[] columns = createBasicColumns();

    // Test bucket num out of range (> 4096)
    Map<String, String> invalidProps = Maps.newHashMap();
    invalidProps.put("transactional", "true");
    invalidProps.put("write.bucket.num", "5000");

    assertThrows(
        IllegalArgumentException.class,
        () ->
            tableCatalog.createTable(
                tableIdent,
                columns,
                "Test table",
                invalidProps,
                Transforms.EMPTY_TRANSFORM,
                Distributions.NONE,
                new SortOrder[0],
                new Index[0]));

    // Test bucket num < 1
    Map<String, String> zeroProps = Maps.newHashMap();
    zeroProps.put("transactional", "true");
    zeroProps.put("write.bucket.num", "0");

    assertThrows(
        IllegalArgumentException.class,
        () ->
            tableCatalog.createTable(
                tableIdent,
                columns,
                "Test table",
                zeroProps,
                Transforms.EMPTY_TRANSFORM,
                Distributions.NONE,
                new SortOrder[0],
                new Index[0]));
  }

  @Test
  void testInvalidAcidDataRetainHours() {
    TableCatalog tableCatalog = catalog.asTableCatalog();
    String testTableName = GravitinoITUtils.genRandomName("mc_invalid_retain");
    NameIdentifier tableIdent = NameIdentifier.of(schemaName, testTableName);
    Column[] columns = createBasicColumns();

    // Test retain hours < 24
    Map<String, String> lowProps = Maps.newHashMap();
    lowProps.put("transactional", "true");
    lowProps.put("acid.data.retain.hours", "12");

    assertThrows(
        IllegalArgumentException.class,
        () ->
            tableCatalog.createTable(
                tableIdent,
                columns,
                "Test table",
                lowProps,
                Transforms.EMPTY_TRANSFORM,
                Distributions.NONE,
                new SortOrder[0],
                new Index[0]));

    // Test retain hours > 168
    Map<String, String> highProps = Maps.newHashMap();
    highProps.put("transactional", "true");
    highProps.put("acid.data.retain.hours", "200");

    assertThrows(
        IllegalArgumentException.class,
        () ->
            tableCatalog.createTable(
                tableIdent,
                columns,
                "Test table",
                highProps,
                Transforms.EMPTY_TRANSFORM,
                Distributions.NONE,
                new SortOrder[0],
                new Index[0]));
  }
}
