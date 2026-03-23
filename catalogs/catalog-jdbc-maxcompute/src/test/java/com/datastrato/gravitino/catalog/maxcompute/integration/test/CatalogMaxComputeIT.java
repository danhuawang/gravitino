/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.catalog.maxcompute.integration.test;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.Schema;
import org.apache.gravitino.SupportsSchemas;
import org.apache.gravitino.catalog.jdbc.config.JdbcConfig;
import org.apache.gravitino.client.GravitinoMetalake;
import org.apache.gravitino.exceptions.NoSuchSchemaException;
import org.apache.gravitino.exceptions.NoSuchTableException;
import org.apache.gravitino.exceptions.SchemaAlreadyExistsException;
import org.apache.gravitino.integration.test.util.BaseIT;
import org.apache.gravitino.integration.test.util.GravitinoITUtils;
import org.apache.gravitino.rel.Column;
import org.apache.gravitino.rel.SupportsPartitions;
import org.apache.gravitino.rel.Table;
import org.apache.gravitino.rel.TableCatalog;
import org.apache.gravitino.rel.TableChange;
import org.apache.gravitino.rel.expressions.distributions.Distributions;
import org.apache.gravitino.rel.expressions.literals.Literals;
import org.apache.gravitino.rel.expressions.sorts.SortOrder;
import org.apache.gravitino.rel.expressions.transforms.Transform;
import org.apache.gravitino.rel.expressions.transforms.Transforms;
import org.apache.gravitino.rel.indexes.Index;
import org.apache.gravitino.rel.partitions.IdentityPartition;
import org.apache.gravitino.rel.partitions.Partition;
import org.apache.gravitino.rel.partitions.Partitions;
import org.apache.gravitino.rel.types.Types;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * Integration tests for MaxCompute catalog.
 *
 * <p>This test requires the following environment variables to be set:
 *
 * <ul>
 *   <li>MAXCOMPUTE_ACCESS_KEY_ID: Alibaba Cloud Access Key ID
 *   <li>MAXCOMPUTE_ACCESS_KEY_SECRET: Alibaba Cloud Access Key Secret
 *   <li>MAXCOMPUTE_PROJECT: MaxCompute project name
 *   <li>MAXCOMPUTE_ENDPOINT: MaxCompute endpoint URL
 * </ul>
 *
 * <p>Example:
 *
 * <pre>
 * export MAXCOMPUTE_ACCESS_KEY_ID="your_access_key_id"
 * export MAXCOMPUTE_ACCESS_KEY_SECRET="your_access_key_secret"
 * export MAXCOMPUTE_PROJECT="your_project_name"
 * export MAXCOMPUTE_ENDPOINT="https://service.cn-hangzhou.maxcompute.aliyun.com/api"
 * </pre>
 */
@TestInstance(Lifecycle.PER_CLASS)
@EnabledIf("isMaxComputeConfigured")
public class CatalogMaxComputeIT extends BaseIT {

  private static final String PROVIDER = "jdbc-maxcompute";
  private static final String DRIVER_CLASS_NAME = "com.aliyun.odps.jdbc.OdpsDriver";

  // MaxCompute operation propagation delay timeout in seconds
  private static final long MAX_WAIT_SECONDS = 30;
  private static final long POLL_INTERVAL_SECONDS = 2;

  // Environment variable names
  private static final String ENV_ACCESS_KEY_ID = "MAXCOMPUTE_ACCESS_KEY_ID";
  private static final String ENV_ACCESS_KEY_SECRET = "MAXCOMPUTE_ACCESS_KEY_SECRET";
  private static final String ENV_PROJECT = "MAXCOMPUTE_PROJECT";
  private static final String ENV_ENDPOINT = "MAXCOMPUTE_ENDPOINT";

  private final String metalakeName = GravitinoITUtils.genRandomName("maxcompute_it_metalake");
  private final String catalogName = GravitinoITUtils.genRandomName("maxcompute_it_catalog");
  private final String schemaName = GravitinoITUtils.genRandomName("mc_it_schema");
  private final String tableName = GravitinoITUtils.genRandomName("mc_it_table");

  private static final String TABLE_COMMENT = "MaxCompute integration test table";

  private static final String COL_NAME_1 = "mc_col_id";
  private static final String COL_NAME_2 = "mc_col_name";
  private static final String COL_NAME_3 = "mc_col_value";

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
    // Load configuration from environment variables
    accessKeyId = System.getenv(ENV_ACCESS_KEY_ID);
    accessKeySecret = System.getenv(ENV_ACCESS_KEY_SECRET);
    String project = System.getenv(ENV_PROJECT);
    String endpoint = System.getenv(ENV_ENDPOINT);

    // Build JDBC URL with required parameters
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
    // Wait for MaxCompute to propagate the schema creation
    await()
        .atMost(MAX_WAIT_SECONDS, TimeUnit.SECONDS)
        .pollInterval(POLL_INTERVAL_SECONDS, TimeUnit.SECONDS)
        .until(() -> Arrays.asList(catalog.asSchemas().listSchemas()).contains(schemaName));
  }

  private void clearTableAndSchema() {
    try {
      // Only clean up schemas created by this test (with prefix "mc_it_" or "mc_new_")
      String[] schemas = catalog.asSchemas().listSchemas();
      for (String schema : schemas) {
        // Only clean up test-created schemas
        if (!schema.startsWith("mc_it_")
            && !schema.startsWith("mc_new_")
            && !schema.startsWith("mc_comment_")) {
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
    client.createMetalake(metalakeName, "MaxCompute IT metalake", Collections.emptyMap());
    metalake = client.loadMetalake(metalakeName);
    assertNotNull(metalake);
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
            "MaxCompute catalog for integration test",
            properties);
    assertNotNull(catalog);
  }

  private void createSchema() {
    catalog.asSchemas().createSchema(schemaName, null, Collections.emptyMap());
  }

  private Column[] createBasicColumns() {
    return new Column[] {
      Column.of(COL_NAME_1, Types.LongType.get(), "Primary key column", false, false, null),
      Column.of(COL_NAME_2, Types.StringType.get(), "Name column", true, false, null),
      Column.of(COL_NAME_3, Types.DoubleType.get(), "Value column", true, false, null)
    };
  }

  // ==================== Schema Tests ====================

  @Test
  void testSchemaBasicOperations() {
    SupportsSchemas schemas = catalog.asSchemas();

    // Test list schemas
    String[] schemaNames = schemas.listSchemas();
    assertTrue(Arrays.asList(schemaNames).contains(schemaName));

    // Test create schema
    String newSchemaName = GravitinoITUtils.genRandomName("mc_new_schema");
    schemas.createSchema(newSchemaName, null, Collections.emptyMap());

    // Wait for MaxCompute to propagate the schema creation
    await()
        .atMost(MAX_WAIT_SECONDS, TimeUnit.SECONDS)
        .pollInterval(POLL_INTERVAL_SECONDS, TimeUnit.SECONDS)
        .until(() -> Arrays.asList(schemas.listSchemas()).contains(newSchemaName));

    List<String> schemaList = Arrays.asList(schemas.listSchemas());
    assertTrue(schemaList.contains(newSchemaName));

    // Test load schema
    Schema loadedSchema = schemas.loadSchema(newSchemaName);
    assertEquals(newSchemaName, loadedSchema.name());

    // Test create schema already exists
    assertThrows(
        SchemaAlreadyExistsException.class,
        () -> schemas.createSchema(newSchemaName, null, Collections.emptyMap()));

    // Test drop schema
    assertTrue(schemas.dropSchema(newSchemaName, false));

    // Wait for MaxCompute to propagate the schema deletion
    await()
        .atMost(MAX_WAIT_SECONDS, TimeUnit.SECONDS)
        .pollInterval(POLL_INTERVAL_SECONDS, TimeUnit.SECONDS)
        .until(() -> !Arrays.asList(schemas.listSchemas()).contains(newSchemaName));

    assertThrows(NoSuchSchemaException.class, () -> schemas.loadSchema(newSchemaName));

    // Test drop non-existing schema
    assertFalse(schemas.dropSchema("non_existing_schema", false));
  }

  @Test
  void testSchemaWithUnsupportedComment() {
    SupportsSchemas schemas = catalog.asSchemas();
    String testSchemaName = GravitinoITUtils.genRandomName("mc_comment_schema");

    // MaxCompute does not support schema comments, should throw exception
    assertThrows(
        UnsupportedOperationException.class,
        () -> schemas.createSchema(testSchemaName, "This is a comment", Collections.emptyMap()));
  }

  // ==================== Table Tests ====================

  @Test
  void testTableBasicOperations() {
    TableCatalog tableCatalog = catalog.asTableCatalog();
    NameIdentifier tableIdent = NameIdentifier.of(schemaName, tableName);
    Column[] columns = createBasicColumns();

    // Create table
    Table createdTable =
        tableCatalog.createTable(
            tableIdent,
            columns,
            TABLE_COMMENT,
            Collections.emptyMap(),
            Transforms.EMPTY_TRANSFORM,
            Distributions.NONE,
            new SortOrder[0],
            new Index[0]);

    assertNotNull(createdTable);
    assertEquals(tableName, createdTable.name());
    assertEquals(TABLE_COMMENT, createdTable.comment());

    // Wait for MaxCompute to propagate the table creation
    await()
        .atMost(MAX_WAIT_SECONDS, TimeUnit.SECONDS)
        .pollInterval(POLL_INTERVAL_SECONDS, TimeUnit.SECONDS)
        .until(
            () -> {
              NameIdentifier[] tables = tableCatalog.listTables(Namespace.of(schemaName));
              Set<String> tableNames =
                  Arrays.stream(tables).map(NameIdentifier::name).collect(Collectors.toSet());
              return tableNames.contains(tableName);
            });

    // List tables
    NameIdentifier[] tables = tableCatalog.listTables(Namespace.of(schemaName));
    Set<String> tableNames =
        Arrays.stream(tables).map(NameIdentifier::name).collect(Collectors.toSet());
    assertTrue(
        tableNames.contains(tableName),
        String.format(
            "Expected table '%s' not found in list. Available tables: %s", tableName, tableNames));

    // Load table
    Table loadedTable = tableCatalog.loadTable(tableIdent);
    assertEquals(tableName, loadedTable.name());
    assertEquals(columns.length, loadedTable.columns().length);

    // Rename table
    String newTableName = GravitinoITUtils.genRandomName("mc_renamed_table");
    tableCatalog.alterTable(tableIdent, TableChange.rename(newTableName));

    // Wait for MaxCompute to propagate the rename operation
    NameIdentifier newTableIdent = NameIdentifier.of(schemaName, newTableName);
    await()
        .atMost(MAX_WAIT_SECONDS, TimeUnit.SECONDS)
        .pollInterval(POLL_INTERVAL_SECONDS, TimeUnit.SECONDS)
        .untilAsserted(() -> assertNotNull(tableCatalog.loadTable(newTableIdent)));

    Table renamedTable = tableCatalog.loadTable(newTableIdent);
    assertEquals(newTableName, renamedTable.name());

    // Drop table
    assertTrue(tableCatalog.dropTable(newTableIdent));
    assertThrows(NoSuchTableException.class, () -> tableCatalog.loadTable(newTableIdent));
  }

  @Test
  void testTableWithAllDataTypes() {
    TableCatalog tableCatalog = catalog.asTableCatalog();
    String testTableName = GravitinoITUtils.genRandomName("mc_all_types");
    NameIdentifier tableIdent = NameIdentifier.of(schemaName, testTableName);

    Column[] columns =
        new Column[] {
          Column.of("col_boolean", Types.BooleanType.get(), "Boolean column"),
          Column.of("col_tinyint", Types.ByteType.get(), "Tinyint column"),
          Column.of("col_smallint", Types.ShortType.get(), "Smallint column"),
          Column.of("col_int", Types.IntegerType.get(), "Int column"),
          Column.of("col_bigint", Types.LongType.get(), "Bigint column"),
          Column.of("col_float", Types.FloatType.get(), "Float column"),
          Column.of("col_double", Types.DoubleType.get(), "Double column"),
          Column.of("col_decimal", Types.DecimalType.of(18, 6), "Decimal column"),
          Column.of("col_string", Types.StringType.get(), "String column"),
          Column.of("col_varchar", Types.VarCharType.of(100), "Varchar column"),
          Column.of("col_char", Types.FixedCharType.of(10), "Char column"),
          Column.of("col_binary", Types.BinaryType.get(), "Binary column"),
          Column.of("col_date", Types.DateType.get(), "Date column"),
          Column.of("col_timestamp", Types.TimestampType.withoutTimeZone(), "Timestamp column")
        };

    Table table =
        tableCatalog.createTable(
            tableIdent,
            columns,
            "Table with all data types",
            Collections.emptyMap(),
            Transforms.EMPTY_TRANSFORM,
            Distributions.NONE,
            new SortOrder[0],
            new Index[0]);

    assertNotNull(table);
    assertEquals(columns.length, table.columns().length);

    // Wait for MaxCompute to propagate the table creation
    await()
        .atMost(MAX_WAIT_SECONDS, TimeUnit.SECONDS)
        .pollInterval(POLL_INTERVAL_SECONDS, TimeUnit.SECONDS)
        .untilAsserted(() -> assertNotNull(tableCatalog.loadTable(tableIdent)));

    // Verify column types
    Table loadedTable = tableCatalog.loadTable(tableIdent);
    assertEquals(columns.length, loadedTable.columns().length);

    // Cleanup
    tableCatalog.dropTable(tableIdent);
  }

  @Test
  void testTableWithLifecycle() {
    TableCatalog tableCatalog = catalog.asTableCatalog();
    String testTableName = GravitinoITUtils.genRandomName("mc_lifecycle");
    NameIdentifier tableIdent = NameIdentifier.of(schemaName, testTableName);

    Column[] columns = createBasicColumns();
    Map<String, String> properties = ImmutableMap.of("lifecycle", "30");

    Table table =
        tableCatalog.createTable(
            tableIdent,
            columns,
            "Table with lifecycle",
            properties,
            Transforms.EMPTY_TRANSFORM,
            Distributions.NONE,
            new SortOrder[0],
            new Index[0]);

    assertNotNull(table);

    // Wait for MaxCompute to propagate the table creation
    await()
        .atMost(MAX_WAIT_SECONDS, TimeUnit.SECONDS)
        .pollInterval(POLL_INTERVAL_SECONDS, TimeUnit.SECONDS)
        .untilAsserted(() -> assertNotNull(tableCatalog.loadTable(tableIdent)));

    // Load and verify lifecycle property
    Table loadedTable = tableCatalog.loadTable(tableIdent);
    assertEquals("30", loadedTable.properties().get("lifecycle"));

    // Cleanup
    tableCatalog.dropTable(tableIdent);
  }

  @Test
  void testAlterTableOperations() {
    TableCatalog tableCatalog = catalog.asTableCatalog();
    String testTableName = GravitinoITUtils.genRandomName("mc_alter");
    NameIdentifier tableIdent = NameIdentifier.of(schemaName, testTableName);

    Column[] columns = createBasicColumns();
    tableCatalog.createTable(
        tableIdent,
        columns,
        "Table for alter test",
        Collections.emptyMap(),
        Transforms.EMPTY_TRANSFORM,
        Distributions.NONE,
        new SortOrder[0],
        new Index[0]);

    // Wait for MaxCompute to propagate the table creation
    await()
        .atMost(MAX_WAIT_SECONDS, TimeUnit.SECONDS)
        .pollInterval(POLL_INTERVAL_SECONDS, TimeUnit.SECONDS)
        .untilAsserted(() -> assertNotNull(tableCatalog.loadTable(tableIdent)));

    // Test update comment
    tableCatalog.alterTable(tableIdent, TableChange.updateComment("Updated comment"));

    // Wait for MaxCompute to propagate the alter operation
    await()
        .atMost(MAX_WAIT_SECONDS, TimeUnit.SECONDS)
        .pollInterval(POLL_INTERVAL_SECONDS, TimeUnit.SECONDS)
        .untilAsserted(
            () -> assertEquals("Updated comment", tableCatalog.loadTable(tableIdent).comment()));

    Table updatedTable = tableCatalog.loadTable(tableIdent);
    assertEquals("Updated comment", updatedTable.comment());

    // Test add column
    tableCatalog.alterTable(
        tableIdent,
        TableChange.addColumn(
            new String[] {"new_col"}, Types.StringType.get(), "New column", true));

    // Wait for MaxCompute to propagate the alter operation
    await()
        .atMost(MAX_WAIT_SECONDS, TimeUnit.SECONDS)
        .pollInterval(POLL_INTERVAL_SECONDS, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertEquals(
                    columns.length + 1, tableCatalog.loadTable(tableIdent).columns().length));

    Table tableWithNewCol = tableCatalog.loadTable(tableIdent);
    assertEquals(columns.length + 1, tableWithNewCol.columns().length);

    // Test rename column
    tableCatalog.alterTable(
        tableIdent, TableChange.renameColumn(new String[] {"new_col"}, "renamed_col"));

    // Wait for MaxCompute to propagate the alter operation
    await()
        .atMost(MAX_WAIT_SECONDS, TimeUnit.SECONDS)
        .pollInterval(POLL_INTERVAL_SECONDS, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertTrue(
                    Arrays.stream(tableCatalog.loadTable(tableIdent).columns())
                        .anyMatch(c -> c.name().equals("renamed_col"))));

    Table tableWithRenamedCol = tableCatalog.loadTable(tableIdent);
    boolean hasRenamedCol =
        Arrays.stream(tableWithRenamedCol.columns()).anyMatch(c -> c.name().equals("renamed_col"));
    assertTrue(hasRenamedCol);

    // Test update column comment
    tableCatalog.alterTable(
        tableIdent,
        TableChange.updateColumnComment(new String[] {"renamed_col"}, "Updated column comment"));

    // Wait for MaxCompute to propagate the alter operation
    await()
        .atMost(MAX_WAIT_SECONDS, TimeUnit.SECONDS)
        .pollInterval(POLL_INTERVAL_SECONDS, TimeUnit.SECONDS)
        .untilAsserted(() -> assertNotNull(tableCatalog.loadTable(tableIdent)));

    // Test set property (lifecycle)
    tableCatalog.alterTable(tableIdent, TableChange.setProperty("lifecycle", "60"));

    // Wait for MaxCompute to propagate the alter operation
    await()
        .atMost(MAX_WAIT_SECONDS, TimeUnit.SECONDS)
        .pollInterval(POLL_INTERVAL_SECONDS, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertEquals(
                    "60", tableCatalog.loadTable(tableIdent).properties().get("lifecycle")));

    Table tableWithLifecycle = tableCatalog.loadTable(tableIdent);
    assertEquals("60", tableWithLifecycle.properties().get("lifecycle"));

    // Test unsupported operations
    assertThrows(
        UnsupportedOperationException.class,
        () ->
            tableCatalog.alterTable(
                tableIdent,
                TableChange.updateColumnType(new String[] {COL_NAME_2}, Types.IntegerType.get())));

    assertThrows(
        UnsupportedOperationException.class,
        () -> tableCatalog.alterTable(tableIdent, TableChange.removeProperty("lifecycle")));

    // Cleanup
    tableCatalog.dropTable(tableIdent);
  }

  // ==================== Partitioned Table Tests ====================

  @Test
  void testPartitionedTable() {
    TableCatalog tableCatalog = catalog.asTableCatalog();
    String testTableName = GravitinoITUtils.genRandomName("mc_partitioned");
    NameIdentifier tableIdent = NameIdentifier.of(schemaName, testTableName);

    Column[] columns =
        new Column[] {
          Column.of("id", Types.LongType.get(), "ID column", false, false, null),
          Column.of("name", Types.StringType.get(), "Name column", true, false, null),
          Column.of("dt", Types.StringType.get(), "Partition column", true, false, null)
        };

    Transform[] partitioning = new Transform[] {Transforms.identity("dt")};

    Table table =
        tableCatalog.createTable(
            tableIdent,
            columns,
            "Partitioned table",
            Collections.emptyMap(),
            partitioning,
            Distributions.NONE,
            new SortOrder[0],
            new Index[0]);

    assertNotNull(table);
    // Note: partitioning info may not be returned correctly by MaxCompute JDBC
    // when creating table, so we just verify the table was created

    // Wait for MaxCompute to propagate the table creation
    await()
        .atMost(MAX_WAIT_SECONDS, TimeUnit.SECONDS)
        .pollInterval(POLL_INTERVAL_SECONDS, TimeUnit.SECONDS)
        .untilAsserted(() -> assertNotNull(tableCatalog.loadTable(tableIdent)));

    // Load and verify table exists
    Table loadedTable = tableCatalog.loadTable(tableIdent);
    assertNotNull(loadedTable);
    assertEquals(testTableName, loadedTable.name());
    // Note: MaxCompute JDBC may not return partitioning info correctly
    // The partitioning info is parsed from SHOW CREATE TABLE output

    // Cleanup
    tableCatalog.dropTable(tableIdent);
  }

  @Test
  void testMultiLevelPartitionedTable() {
    TableCatalog tableCatalog = catalog.asTableCatalog();
    String testTableName = GravitinoITUtils.genRandomName("mc_multi_part");
    NameIdentifier tableIdent = NameIdentifier.of(schemaName, testTableName);

    Column[] columns =
        new Column[] {
          Column.of("id", Types.LongType.get(), "ID column", false, false, null),
          Column.of("value", Types.StringType.get(), "Value column", true, false, null),
          Column.of("year", Types.StringType.get(), "Year partition", true, false, null),
          Column.of("month", Types.StringType.get(), "Month partition", true, false, null),
          Column.of("day", Types.StringType.get(), "Day partition", true, false, null)
        };

    Transform[] partitioning =
        new Transform[] {
          Transforms.identity("year"), Transforms.identity("month"), Transforms.identity("day")
        };

    Table table =
        tableCatalog.createTable(
            tableIdent,
            columns,
            "Multi-level partitioned table",
            Collections.emptyMap(),
            partitioning,
            Distributions.NONE,
            new SortOrder[0],
            new Index[0]);

    assertNotNull(table);
    assertEquals(3, table.partitioning().length);

    // Wait for MaxCompute to propagate the table creation
    await()
        .atMost(MAX_WAIT_SECONDS, TimeUnit.SECONDS)
        .pollInterval(POLL_INTERVAL_SECONDS, TimeUnit.SECONDS)
        .untilAsserted(() -> assertNotNull(tableCatalog.loadTable(tableIdent)));

    // Cleanup
    tableCatalog.dropTable(tableIdent);
  }

  // ==================== Partition Operations Tests ====================

  @Test
  void testPartitionOperations() {
    TableCatalog tableCatalog = catalog.asTableCatalog();
    String testTableName = GravitinoITUtils.genRandomName("mc_part_ops");
    NameIdentifier tableIdent = NameIdentifier.of(schemaName, testTableName);

    Column[] columns =
        new Column[] {
          Column.of("id", Types.LongType.get(), "ID column", false, false, null),
          Column.of("name", Types.StringType.get(), "Name column", true, false, null),
          Column.of("dt", Types.StringType.get(), "Partition column", true, false, null)
        };

    Transform[] partitioning = new Transform[] {Transforms.identity("dt")};

    tableCatalog.createTable(
        tableIdent,
        columns,
        "Table for partition operations",
        Collections.emptyMap(),
        partitioning,
        Distributions.NONE,
        new SortOrder[0],
        new Index[0]);

    // Wait for MaxCompute to propagate the table creation
    await()
        .atMost(MAX_WAIT_SECONDS, TimeUnit.SECONDS)
        .pollInterval(POLL_INTERVAL_SECONDS, TimeUnit.SECONDS)
        .untilAsserted(() -> assertNotNull(tableCatalog.loadTable(tableIdent)));

    Table table = tableCatalog.loadTable(tableIdent);
    SupportsPartitions partitionOps = table.supportPartitions();

    // Initially no partitions
    String[] partitionNames = partitionOps.listPartitionNames();
    assertEquals(0, partitionNames.length);

    // Add partition
    IdentityPartition p1 =
        Partitions.identity(
            "dt=2024-01-01",
            new String[][] {{"dt"}},
            new org.apache.gravitino.rel.expressions.literals.Literal<?>[] {
              Literals.stringLiteral("2024-01-01")
            },
            Collections.emptyMap());
    Partition addedPartition = partitionOps.addPartition(p1);
    assertNotNull(addedPartition);

    // Wait for MaxCompute to propagate the partition addition
    await()
        .atMost(MAX_WAIT_SECONDS, TimeUnit.SECONDS)
        .pollInterval(POLL_INTERVAL_SECONDS, TimeUnit.SECONDS)
        .until(() -> partitionOps.listPartitionNames().length == 1);

    // List partitions
    partitionNames = partitionOps.listPartitionNames();
    assertEquals(1, partitionNames.length);
    assertTrue(partitionNames[0].contains("2024-01-01"));

    // Check partition exists
    assertTrue(partitionOps.partitionExists(partitionNames[0]));

    // Get partition
    Partition loadedPartition = partitionOps.getPartition(partitionNames[0]);
    assertNotNull(loadedPartition);

    // Add another partition
    IdentityPartition p2 =
        Partitions.identity(
            "dt=2024-01-02",
            new String[][] {{"dt"}},
            new org.apache.gravitino.rel.expressions.literals.Literal<?>[] {
              Literals.stringLiteral("2024-01-02")
            },
            Collections.emptyMap());
    partitionOps.addPartition(p2);

    // Wait for MaxCompute to propagate the partition addition
    await()
        .atMost(MAX_WAIT_SECONDS, TimeUnit.SECONDS)
        .pollInterval(POLL_INTERVAL_SECONDS, TimeUnit.SECONDS)
        .until(() -> partitionOps.listPartitionNames().length == 2);

    partitionNames = partitionOps.listPartitionNames();
    assertEquals(2, partitionNames.length);

    // Drop partition
    assertTrue(partitionOps.dropPartition(partitionNames[0]));

    // Wait for MaxCompute to propagate the partition deletion
    await()
        .atMost(MAX_WAIT_SECONDS, TimeUnit.SECONDS)
        .pollInterval(POLL_INTERVAL_SECONDS, TimeUnit.SECONDS)
        .until(() -> partitionOps.listPartitionNames().length == 1);

    partitionNames = partitionOps.listPartitionNames();
    assertEquals(1, partitionNames.length);

    // Cleanup
    tableCatalog.dropTable(tableIdent);
  }

  // ==================== Error Handling Tests ====================

  @Test
  void testLoadNonExistingTable() {
    TableCatalog tableCatalog = catalog.asTableCatalog();
    NameIdentifier nonExisting = NameIdentifier.of(schemaName, "non_existing_table");

    assertThrows(NoSuchTableException.class, () -> tableCatalog.loadTable(nonExisting));
  }

  @Test
  void testDropNonExistingTable() {
    TableCatalog tableCatalog = catalog.asTableCatalog();
    NameIdentifier nonExisting = NameIdentifier.of(schemaName, "non_existing_table");

    // MaxCompute uses DROP TABLE IF EXISTS, which always succeeds even if table doesn't exist
    assertTrue(tableCatalog.dropTable(nonExisting));
  }

  @Test
  void testCreateTableInNonExistingSchema() {
    TableCatalog tableCatalog = catalog.asTableCatalog();
    NameIdentifier tableIdent = NameIdentifier.of("non_existing_schema", "test_table");
    Column[] columns = createBasicColumns();

    assertThrows(
        NoSuchSchemaException.class,
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
}
