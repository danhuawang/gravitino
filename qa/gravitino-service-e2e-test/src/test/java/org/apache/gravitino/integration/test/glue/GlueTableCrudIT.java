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
import org.apache.gravitino.Namespace;
import org.apache.gravitino.client.GravitinoAdminClient;
import org.apache.gravitino.client.GravitinoMetalake;
import org.apache.gravitino.rel.Column;
import org.apache.gravitino.rel.Table;
import org.apache.gravitino.rel.TableChange;
import org.apache.gravitino.rel.types.Types;
import org.apache.gravitino.utils.RandomNameUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * E2E integration tests for Glue catalog mixed-format table operations.
 *
 * <p>Test plan section 2: Mixed-Format Table Operations (Core Feature)
 *
 * <ul>
 *   <li>2.1 Create Iceberg format table (default format)
 *   <li>2.2 Create Hive format table
 *   <li>2.3 Mixed Hive + Iceberg tables in the same schema
 *   <li>2.4–2.9 (to be added)
 * </ul>
 */
@DisplayName("Glue Catalog Table CRUD Integration Tests")
public class GlueTableCrudIT {

  private static final Logger LOG = LoggerFactory.getLogger(GlueTableCrudIT.class);

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

    metalakeName = RandomNameUtils.genRandomName("glue_tbl_metalake");
    metalake =
        adminClient.createMetalake(
            metalakeName, "Metalake for Glue table tests", Collections.emptyMap());

    // Create a Glue catalog for testing
    glueCatalogName = RandomNameUtils.genRandomName("glue_table_crud");
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
            "Glue catalog for table CRUD tests",
            glueProps);

    testRunPrefix = RandomNameUtils.genRandomName("gt");
    LOG.info(
        "GlueTableCrudIT setup complete: metalake={}, glueCatalog={}, prefix={}",
        metalakeName,
        glueCatalogName,
        testRunPrefix);
  }

  //  @AfterAll
  //  public static void teardown() {
  //    try {
  //      if (metalake != null && glueCatalogName != null) {
  //        metalake.dropCatalog(glueCatalogName, true);
  //      }
  //      if (adminClient != null && metalakeName != null) {
  //        adminClient.dropMetalake(metalakeName, true);
  //      }
  //    } catch (Exception e) {
  //      LOG.warn("Teardown failed, proceeding anyway", e);
  //    } finally {
  //      if (adminClient != null) {
  //        adminClient.close();
  //      }
  //    }
  //  }
  //
  //  @AfterEach
  //  public void cleanupSchemas() {
  //    // Best-effort cleanup of schemas created during each test
  //    try {
  //      String[] schemas = glueCatalog.asSchemas().listSchemas();
  //      for (String schema : schemas) {
  //        if (schema.startsWith(testRunPrefix)) {
  //          try {
  //            glueCatalog.asSchemas().dropSchema(schema, true);
  //          } catch (Exception e) {
  //            LOG.warn("Failed to cleanup schema: {}", schema, e);
  //          }
  //        }
  //      }
  //    } catch (Exception e) {
  //      LOG.warn("Failed to list schemas during cleanup", e);
  //    }
  //  }

  // ── 2.1 Create Iceberg format table (default format) ─────────────────────

  @Test
  @DisplayName("2.1 Create Iceberg format table and verify table_type and metadata_location")
  public void testCreateIcebergTableDefaultFormat() {
    String schemaName = testRunPrefix + "_iceberg_default";

    // Create schema
    glueCatalog.asSchemas().createSchema(schemaName, "iceberg table test", Collections.emptyMap());

    // Create table with Iceberg format
    String tableName = "iceberg_table_1";
    Column[] columns = {
      Column.of("id", Types.LongType.get(), "primary key"),
      Column.of("name", Types.StringType.get(), "user name"),
      Column.of("created_at", Types.TimestampType.withoutTimeZone(), "creation time")
    };

    Map<String, String> tableProps = Maps.newHashMap();
    tableProps.put("table-format", "ICEBERG");

    glueCatalog
        .asTableCatalog()
        .createTable(
            NameIdentifier.of(schemaName, tableName), columns, "Iceberg test table", tableProps);

    // Load the table and verify properties
    Table loadedTable =
        glueCatalog.asTableCatalog().loadTable(NameIdentifier.of(schemaName, tableName));

    Assertions.assertNotNull(loadedTable, "Loaded table should not be null");
    Assertions.assertEquals(tableName, loadedTable.name(), "Table name should match");

    // Verify table_type=ICEBERG is present in properties
    Map<String, String> loadedProps = loadedTable.properties();
    String tableType = loadedProps.get("table_type");
    Assertions.assertNotNull(tableType, "table_type should be present in properties");
    Assertions.assertEquals("ICEBERG", tableType.toUpperCase(), "table_type should be ICEBERG");

    // Verify metadata_location is present in properties
    String metadataLocation = loadedProps.get("metadata_location");
    Assertions.assertNotNull(
        metadataLocation, "metadata_location should be present in properties for Iceberg tables");
    Assertions.assertTrue(
        metadataLocation.contains("metadata"),
        "metadata_location should contain 'metadata' path segment, got: " + metadataLocation);

    LOG.info(
        "Iceberg table created successfully: table_type={}, metadata_location={}",
        tableType,
        metadataLocation);
  }

  // ── 2.2 Create Hive format table ─────────────────────────────────────────

  @Test
  @DisplayName("2.2 Create Hive format table and verify StorageDescriptor mapping")
  public void testCreateHiveFormatTable() {
    String schemaName = testRunPrefix + "_hive_table";

    // Create schema
    glueCatalog.asSchemas().createSchema(schemaName, "hive table test", Collections.emptyMap());

    // Create table with Hive format, providing StorageDescriptor properties
    String tableName = "hive_table_1";
    Column[] columns = {
      Column.of("id", Types.LongType.get(), "primary key"),
      Column.of("name", Types.StringType.get(), "user name"),
      Column.of("age", Types.IntegerType.get(), "user age")
    };

    Map<String, String> tableProps = Maps.newHashMap();
    tableProps.put("input-format", "org.apache.hadoop.mapred.TextInputFormat");
    tableProps.put("output-format", "org.apache.hadoop.hive.ql.io.HiveIgnoreKeyTextOutputFormat");
    tableProps.put("serde-lib", "org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe");
    tableProps.put(
        "location", "s3://gravitino-glue-test/warehouse/" + schemaName + "/" + tableName);

    glueCatalog
        .asTableCatalog()
        .createTable(
            NameIdentifier.of(schemaName, tableName),
            columns,
            "Hive format test table",
            tableProps);

    // Load the table and verify
    Table loadedTable =
        glueCatalog.asTableCatalog().loadTable(NameIdentifier.of(schemaName, tableName));

    Assertions.assertNotNull(loadedTable, "Loaded table should not be null");
    Assertions.assertEquals(tableName, loadedTable.name(), "Table name should match");
    Assertions.assertEquals(
        "Hive format test table", loadedTable.comment(), "Comment should match");

    // Verify columns are correctly mapped from StorageDescriptor
    Column[] loadedColumns = loadedTable.columns();
    Assertions.assertEquals(3, loadedColumns.length, "Should have 3 columns");
    Assertions.assertEquals("id", loadedColumns[0].name());
    Assertions.assertEquals(Types.LongType.get(), loadedColumns[0].dataType());
    Assertions.assertEquals("name", loadedColumns[1].name());
    Assertions.assertEquals(Types.StringType.get(), loadedColumns[1].dataType());
    Assertions.assertEquals("age", loadedColumns[2].name());
    Assertions.assertEquals(Types.IntegerType.get(), loadedColumns[2].dataType());

    // Verify StorageDescriptor properties are correctly mapped
    Map<String, String> loadedProps = loadedTable.properties();
    Assertions.assertEquals(
        "org.apache.hadoop.mapred.TextInputFormat",
        loadedProps.get("input-format"),
        "input-format should be correctly mapped");
    Assertions.assertEquals(
        "org.apache.hadoop.hive.ql.io.HiveIgnoreKeyTextOutputFormat",
        loadedProps.get("output-format"),
        "output-format should be correctly mapped");
    Assertions.assertEquals(
        "org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe",
        loadedProps.get("serde-lib"),
        "serde-lib should be correctly mapped");

    // Verify location is set
    String location = loadedProps.get("location");
    Assertions.assertNotNull(location, "location should be present");
    Assertions.assertTrue(
        location.contains(schemaName), "location should contain schema name, got: " + location);

    LOG.info(
        "Hive table created successfully: input-format={}, output-format={}, serde-lib={}",
        loadedProps.get("input-format"),
        loadedProps.get("output-format"),
        loadedProps.get("serde-lib"));
  }

  // ── 2.3 Mixed Hive + Iceberg tables in the same schema ───────────────────

  @Test
  @DisplayName("2.3 Mixed Hive + Iceberg tables in same schema - listTables returns all types")
  public void testMixedHiveAndIcebergTablesInSameSchema() {
    String schemaName = testRunPrefix + "_mixed_tables";

    // Create schema
    glueCatalog.asSchemas().createSchema(schemaName, "mixed format test", Collections.emptyMap());

    // Create a Hive format table
    String hiveTableName = "hive_orders";
    Column[] hiveColumns = {
      Column.of("order_id", Types.LongType.get(), "order id"),
      Column.of("customer", Types.StringType.get(), "customer name"),
      Column.of("amount", Types.DoubleType.get(), "order amount")
    };
    Map<String, String> hiveProps = Maps.newHashMap();
    hiveProps.put("input-format", "org.apache.hadoop.mapred.TextInputFormat");
    hiveProps.put("output-format", "org.apache.hadoop.hive.ql.io.HiveIgnoreKeyTextOutputFormat");
    hiveProps.put("serde-lib", "org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe");

    glueCatalog
        .asTableCatalog()
        .createTable(
            NameIdentifier.of(schemaName, hiveTableName),
            hiveColumns,
            "Hive orders table",
            hiveProps);

    // Create an Iceberg format table
    String icebergTableName = "iceberg_events";
    Column[] icebergColumns = {
      Column.of("event_id", Types.LongType.get(), "event id"),
      Column.of("event_type", Types.StringType.get(), "event type"),
      Column.of("timestamp", Types.TimestampType.withoutTimeZone(), "event time")
    };
    Map<String, String> icebergProps = Maps.newHashMap();
    icebergProps.put("table-format", "ICEBERG");

    glueCatalog
        .asTableCatalog()
        .createTable(
            NameIdentifier.of(schemaName, icebergTableName),
            icebergColumns,
            "Iceberg events table",
            icebergProps);

    // List tables — should return both Hive and Iceberg tables
    NameIdentifier[] tables = glueCatalog.asTableCatalog().listTables(Namespace.of(schemaName));
    Set<String> tableNames =
        Arrays.stream(tables).map(NameIdentifier::name).collect(Collectors.toSet());

    Assertions.assertEquals(2, tables.length, "Should have 2 tables in the schema");
    Assertions.assertTrue(
        tableNames.contains(hiveTableName),
        "listTables should include Hive table, got: " + tableNames);
    Assertions.assertTrue(
        tableNames.contains(icebergTableName),
        "listTables should include Iceberg table, got: " + tableNames);

    // Verify each table can be loaded with correct format
    Table loadedHive =
        glueCatalog.asTableCatalog().loadTable(NameIdentifier.of(schemaName, hiveTableName));
    Assertions.assertNotNull(
        loadedHive.properties().get("input-format"),
        "Hive table should have input-format property");

    Table loadedIceberg =
        glueCatalog.asTableCatalog().loadTable(NameIdentifier.of(schemaName, icebergTableName));
    String icebergType = loadedIceberg.properties().get("table_type");
    Assertions.assertNotNull(icebergType, "Iceberg table should have table_type property");
    Assertions.assertEquals(
        "ICEBERG", icebergType.toUpperCase(), "Iceberg table table_type should be ICEBERG");

    LOG.info("Mixed-format listing verified: {} tables found ({})", tables.length, tableNames);
  }

  // ── 2.4 table-type-filter=hive shows only Hive tables ────────────────────

  @Test
  @DisplayName("2.4 table-format-filter=hive - only Hive tables are visible")
  public void testTableFormatFilterHiveOnly() {
    // Create a separate catalog with table-format-filter=hive
    String filteredCatalogName = RandomNameUtils.genRandomName("glue_filter_hive");
    Map<String, String> filteredProps = Maps.newHashMap();
    filteredProps.put("aws-region", System.getProperty("glue.aws.region", "us-east-1"));
    filteredProps.put(
        "aws-glue-catalog-id", System.getProperty("glue.aws.catalog.id", "730335553010"));
    filteredProps.put(
        "warehouse",
        System.getProperty("glue.aws.warehouse", "s3://gravitino-glue-test/warehouse"));
    filteredProps.put("table-format-filter", "hive");

    String accessKey = System.getProperty("glue.aws.access.key.id");
    String secretKey = System.getProperty("glue.aws.secret.access.key");
    if (accessKey != null && secretKey != null) {
      filteredProps.put("aws-access-key-id", accessKey);
      filteredProps.put("aws-secret-access-key", secretKey);
    }
    String glueEndpoint = System.getProperty("glue.aws.endpoint");
    if (glueEndpoint != null) {
      filteredProps.put("aws-glue-endpoint", glueEndpoint);
    }

    Catalog filteredCatalog =
        metalake.createCatalog(
            filteredCatalogName,
            Catalog.Type.RELATIONAL,
            "glue",
            "Glue catalog with hive filter",
            filteredProps);

    try {
      String schemaName = testRunPrefix + "_filter_hive";

      // Create schema via the filtered catalog
      filteredCatalog.asSchemas().createSchema(schemaName, "filter test", Collections.emptyMap());

      // Create a Hive table
      String hiveTableName = "hive_visible";
      Column[] hiveColumns = {Column.of("id", Types.LongType.get(), "id")};
      Map<String, String> hiveProps = Maps.newHashMap();
      hiveProps.put("input-format", "org.apache.hadoop.mapred.TextInputFormat");
      hiveProps.put("output-format", "org.apache.hadoop.hive.ql.io.HiveIgnoreKeyTextOutputFormat");
      hiveProps.put("serde-lib", "org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe");

      filteredCatalog
          .asTableCatalog()
          .createTable(
              NameIdentifier.of(schemaName, hiveTableName), hiveColumns, "Hive table", hiveProps);

      // Create an Iceberg table
      String icebergTableName = "iceberg_hidden";
      Column[] icebergColumns = {Column.of("id", Types.LongType.get(), "id")};
      Map<String, String> icebergProps = Maps.newHashMap();
      icebergProps.put("table-format", "ICEBERG");

      filteredCatalog
          .asTableCatalog()
          .createTable(
              NameIdentifier.of(schemaName, icebergTableName),
              icebergColumns,
              "Iceberg table",
              icebergProps);

      // List tables with filter=hive — only Hive table should be visible
      NameIdentifier[] tables =
          filteredCatalog.asTableCatalog().listTables(Namespace.of(schemaName));
      Set<String> tableNames =
          Arrays.stream(tables).map(NameIdentifier::name).collect(Collectors.toSet());

      Assertions.assertTrue(
          tableNames.contains(hiveTableName),
          "Hive table should be visible with filter=hive, got: " + tableNames);
      Assertions.assertFalse(
          tableNames.contains(icebergTableName),
          "Iceberg table should NOT be visible with filter=hive, got: " + tableNames);

      // Second verification: confirm the Iceberg table actually exists in Glue
      // by loading it via the unfiltered main catalog (which sees all formats)
      NameIdentifier[] allTables =
          glueCatalog.asTableCatalog().listTables(Namespace.of(schemaName));
      Set<String> allTableNames =
          Arrays.stream(allTables).map(NameIdentifier::name).collect(Collectors.toSet());
      Assertions.assertTrue(
          allTableNames.contains(icebergTableName),
          "Iceberg table should exist in Glue (visible via unfiltered catalog), got: "
              + allTableNames);
      Assertions.assertTrue(
          allTableNames.contains(hiveTableName),
          "Hive table should also exist in unfiltered catalog, got: " + allTableNames);

      LOG.info("table-format-filter=hive verified: visible tables = {}", tableNames);
    } finally {
      metalake.dropCatalog(filteredCatalogName, true);
    }
  }

  // ── 2.5 table-type-filter=iceberg shows only Iceberg tables ──────────────

  @Test
  @DisplayName("2.5 table-format-filter=iceberg - only Iceberg tables are visible")
  public void testTableFormatFilterIcebergOnly() {
    // Create a separate catalog with table-format-filter=iceberg
    String filteredCatalogName = RandomNameUtils.genRandomName("glue_filter_ice");
    Map<String, String> filteredProps = Maps.newHashMap();
    filteredProps.put("aws-region", System.getProperty("glue.aws.region", "us-east-1"));
    filteredProps.put(
        "aws-glue-catalog-id", System.getProperty("glue.aws.catalog.id", "730335553010"));
    filteredProps.put(
        "warehouse",
        System.getProperty("glue.aws.warehouse", "s3://gravitino-glue-test/warehouse"));
    filteredProps.put("table-format-filter", "iceberg");

    String accessKey = System.getProperty("glue.aws.access.key.id");
    String secretKey = System.getProperty("glue.aws.secret.access.key");
    if (accessKey != null && secretKey != null) {
      filteredProps.put("aws-access-key-id", accessKey);
      filteredProps.put("aws-secret-access-key", secretKey);
    }
    String glueEndpoint = System.getProperty("glue.aws.endpoint");
    if (glueEndpoint != null) {
      filteredProps.put("aws-glue-endpoint", glueEndpoint);
    }

    Catalog filteredCatalog =
        metalake.createCatalog(
            filteredCatalogName,
            Catalog.Type.RELATIONAL,
            "glue",
            "Glue catalog with iceberg filter",
            filteredProps);

    try {
      String schemaName = testRunPrefix + "_filter_ice";

      // Create schema via the filtered catalog
      filteredCatalog.asSchemas().createSchema(schemaName, "filter test", Collections.emptyMap());

      // Create a Hive table
      String hiveTableName = "hive_hidden";
      Column[] hiveColumns = {Column.of("id", Types.LongType.get(), "id")};
      Map<String, String> hiveProps = Maps.newHashMap();
      hiveProps.put("input-format", "org.apache.hadoop.mapred.TextInputFormat");
      hiveProps.put("output-format", "org.apache.hadoop.hive.ql.io.HiveIgnoreKeyTextOutputFormat");
      hiveProps.put("serde-lib", "org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe");

      filteredCatalog
          .asTableCatalog()
          .createTable(
              NameIdentifier.of(schemaName, hiveTableName), hiveColumns, "Hive table", hiveProps);

      // Create an Iceberg table
      String icebergTableName = "iceberg_visible";
      Column[] icebergColumns = {Column.of("id", Types.LongType.get(), "id")};
      Map<String, String> icebergProps = Maps.newHashMap();
      icebergProps.put("table-format", "ICEBERG");

      filteredCatalog
          .asTableCatalog()
          .createTable(
              NameIdentifier.of(schemaName, icebergTableName),
              icebergColumns,
              "Iceberg table",
              icebergProps);

      // List tables with filter=iceberg — only Iceberg table should be visible
      NameIdentifier[] tables =
          filteredCatalog.asTableCatalog().listTables(Namespace.of(schemaName));
      Set<String> tableNames =
          Arrays.stream(tables).map(NameIdentifier::name).collect(Collectors.toSet());

      Assertions.assertTrue(
          tableNames.contains(icebergTableName),
          "Iceberg table should be visible with filter=iceberg, got: " + tableNames);
      Assertions.assertFalse(
          tableNames.contains(hiveTableName),
          "Hive table should NOT be visible with filter=iceberg, got: " + tableNames);

      // Second verification: confirm the Hive table actually exists in Glue
      // by loading it via the unfiltered main catalog (which sees all formats)
      NameIdentifier[] allTables =
          glueCatalog.asTableCatalog().listTables(Namespace.of(schemaName));
      Set<String> allTableNames =
          Arrays.stream(allTables).map(NameIdentifier::name).collect(Collectors.toSet());
      Assertions.assertTrue(
          allTableNames.contains(hiveTableName),
          "Hive table should exist in Glue (visible via unfiltered catalog), got: "
              + allTableNames);
      Assertions.assertTrue(
          allTableNames.contains(icebergTableName),
          "Iceberg table should also exist in unfiltered catalog, got: " + allTableNames);

      LOG.info("table-format-filter=iceberg verified: visible tables = {}", tableNames);
    } finally {
      metalake.dropCatalog(filteredCatalogName, true);
    }
  }

  // ── 2.6 Metadata passthrough verification ────────────────────────────────

  @Test
  @DisplayName("2.6 Metadata passthrough - table_type, metadata_location, custom params preserved")
  public void testMetadataPassthrough() {
    String schemaName = testRunPrefix + "_passthrough";

    // Create schema
    glueCatalog.asSchemas().createSchema(schemaName, "passthrough test", Collections.emptyMap());

    // Create an Iceberg table with various metadata parameters
    String icebergTableName = "iceberg_with_metadata";
    Column[] columns = {
      Column.of("id", Types.LongType.get(), "id"), Column.of("data", Types.StringType.get(), "data")
    };
    Map<String, String> icebergProps = Maps.newHashMap();
    icebergProps.put("table-format", "ICEBERG");

    glueCatalog
        .asTableCatalog()
        .createTable(
            NameIdentifier.of(schemaName, icebergTableName),
            columns,
            "Iceberg metadata test",
            icebergProps);

    // Load and verify Iceberg metadata passthrough
    Table loadedIceberg =
        glueCatalog.asTableCatalog().loadTable(NameIdentifier.of(schemaName, icebergTableName));
    Map<String, String> icebergLoadedProps = loadedIceberg.properties();

    // table_type must survive
    Assertions.assertEquals(
        "ICEBERG",
        icebergLoadedProps.getOrDefault("table_type", "").toUpperCase(),
        "table_type=ICEBERG must be preserved in properties");

    // metadata_location must survive
    String metadataLoc = icebergLoadedProps.get("metadata_location");
    Assertions.assertNotNull(metadataLoc, "metadata_location must be present for Iceberg tables");
    Assertions.assertFalse(metadataLoc.isEmpty(), "metadata_location must not be empty");

    // Create a Hive table with custom parameters
    String hiveTableName = "hive_with_params";
    Column[] hiveColumns = {Column.of("id", Types.LongType.get(), "id")};
    Map<String, String> hiveProps = Maps.newHashMap();
    hiveProps.put("input-format", "org.apache.hadoop.mapred.TextInputFormat");
    hiveProps.put("output-format", "org.apache.hadoop.hive.ql.io.HiveIgnoreKeyTextOutputFormat");
    hiveProps.put("serde-lib", "org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe");
    hiveProps.put("spark.sql.sources.provider", "hive");
    hiveProps.put("custom-param-1", "custom-value-1");
    hiveProps.put("custom-param-2", "custom-value-2");

    glueCatalog
        .asTableCatalog()
        .createTable(
            NameIdentifier.of(schemaName, hiveTableName),
            hiveColumns,
            "Hive params test",
            hiveProps);

    // Load and verify Hive custom parameters passthrough
    Table loadedHive =
        glueCatalog.asTableCatalog().loadTable(NameIdentifier.of(schemaName, hiveTableName));
    Map<String, String> hiveLoadedProps = loadedHive.properties();

    Assertions.assertEquals(
        "hive",
        hiveLoadedProps.get("spark.sql.sources.provider"),
        "spark.sql.sources.provider must be preserved");
    Assertions.assertEquals(
        "custom-value-1",
        hiveLoadedProps.get("custom-param-1"),
        "custom-param-1 must be preserved");
    Assertions.assertEquals(
        "custom-value-2",
        hiveLoadedProps.get("custom-param-2"),
        "custom-param-2 must be preserved");

    LOG.info(
        "Metadata passthrough verified: Iceberg metadata_location={}, "
            + "Hive spark.sql.sources.provider={}",
        metadataLoc,
        hiveLoadedProps.get("spark.sql.sources.provider"));
  }

  // ── 2.7 Alter Iceberg table (update metadata_location) ───────────────────

  @Test
  @DisplayName("2.7 Alter Iceberg table - update metadata_location via setProperty")
  public void testAlterIcebergTableUpdateMetadataLocation() {
    String schemaName = testRunPrefix + "_alter_iceberg";

    // Create schema
    glueCatalog.asSchemas().createSchema(schemaName, "alter iceberg test", Collections.emptyMap());

    // Create an Iceberg table
    String tableName = "iceberg_alter_test";
    Column[] columns = {
      Column.of("id", Types.LongType.get(), "id"),
      Column.of("value", Types.StringType.get(), "value")
    };
    Map<String, String> tableProps = Maps.newHashMap();
    tableProps.put("table-format", "ICEBERG");

    glueCatalog
        .asTableCatalog()
        .createTable(
            NameIdentifier.of(schemaName, tableName), columns, "Iceberg alter test", tableProps);

    // Load and record original metadata_location
    Table original =
        glueCatalog.asTableCatalog().loadTable(NameIdentifier.of(schemaName, tableName));
    String originalMetadataLocation = original.properties().get("metadata_location");
    Assertions.assertNotNull(
        originalMetadataLocation, "Original metadata_location should be present");

    LOG.info("Original metadata_location: {}", originalMetadataLocation);

    // Alter the table: update metadata_location to a new path
    String newMetadataLocation =
        originalMetadataLocation.replace(".metadata.json", "-updated.metadata.json");
    Table altered =
        glueCatalog
            .asTableCatalog()
            .alterTable(
                NameIdentifier.of(schemaName, tableName),
                TableChange.setProperty("metadata_location", newMetadataLocation));

    // Verify the alter returned the updated table
    Assertions.assertNotNull(altered, "Altered table should not be null");

    // Reload and verify the metadata_location was updated
    Table reloaded =
        glueCatalog.asTableCatalog().loadTable(NameIdentifier.of(schemaName, tableName));
    String reloadedMetadataLocation = reloaded.properties().get("metadata_location");
    Assertions.assertNotNull(reloadedMetadataLocation, "Reloaded metadata_location should exist");
    Assertions.assertEquals(
        newMetadataLocation,
        reloadedMetadataLocation,
        "metadata_location should be updated to the new value");
    Assertions.assertNotEquals(
        originalMetadataLocation,
        reloadedMetadataLocation,
        "metadata_location should differ from original");

    // Verify table_type is still ICEBERG after alter
    Assertions.assertEquals(
        "ICEBERG",
        reloaded.properties().getOrDefault("table_type", "").toUpperCase(),
        "table_type should still be ICEBERG after alter");

    LOG.info(
        "Iceberg table alter verified: metadata_location changed from {} to {}",
        originalMetadataLocation,
        reloadedMetadataLocation);
  }

  // ── 2.8 Alter Hive table (add/remove columns) ────────────────────────────

  @Test
  @DisplayName("2.8 Alter Hive table - add and remove columns mapped to Glue")
  public void testAlterHiveTableAddRemoveColumns() {
    String schemaName = testRunPrefix + "_alter_hive_col";

    // Create schema
    glueCatalog
        .asSchemas()
        .createSchema(schemaName, "alter hive columns test", Collections.emptyMap());

    // Create a Hive table with initial columns
    String tableName = "hive_alter_columns";
    Column[] columns = {
      Column.of("id", Types.LongType.get(), "primary key"),
      Column.of("name", Types.StringType.get(), "user name")
    };
    Map<String, String> hiveProps = Maps.newHashMap();
    hiveProps.put("input-format", "org.apache.hadoop.mapred.TextInputFormat");
    hiveProps.put("output-format", "org.apache.hadoop.hive.ql.io.HiveIgnoreKeyTextOutputFormat");
    hiveProps.put("serde-lib", "org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe");

    glueCatalog
        .asTableCatalog()
        .createTable(
            NameIdentifier.of(schemaName, tableName), columns, "Hive alter test", hiveProps);

    // Verify initial state: 2 columns
    Table original =
        glueCatalog.asTableCatalog().loadTable(NameIdentifier.of(schemaName, tableName));
    Assertions.assertEquals(2, original.columns().length, "Should start with 2 columns");

    // Add a new column
    glueCatalog
        .asTableCatalog()
        .alterTable(
            NameIdentifier.of(schemaName, tableName),
            TableChange.addColumn(new String[] {"email"}, Types.StringType.get(), "email address"));

    // Verify column was added
    Table afterAdd =
        glueCatalog.asTableCatalog().loadTable(NameIdentifier.of(schemaName, tableName));
    Assertions.assertEquals(3, afterAdd.columns().length, "Should have 3 columns after add");
    Assertions.assertEquals("email", afterAdd.columns()[2].name(), "New column should be 'email'");
    Assertions.assertEquals(
        Types.StringType.get(),
        afterAdd.columns()[2].dataType(),
        "New column type should be string");

    // Add another column with a specific type
    glueCatalog
        .asTableCatalog()
        .alterTable(
            NameIdentifier.of(schemaName, tableName),
            TableChange.addColumn(new String[] {"age"}, Types.IntegerType.get(), "user age"));

    // Verify second column was added
    Table afterSecondAdd =
        glueCatalog.asTableCatalog().loadTable(NameIdentifier.of(schemaName, tableName));
    Assertions.assertEquals(
        4, afterSecondAdd.columns().length, "Should have 4 columns after second add");
    Assertions.assertEquals("age", afterSecondAdd.columns()[3].name());
    Assertions.assertEquals(Types.IntegerType.get(), afterSecondAdd.columns()[3].dataType());

    // Delete a column
    glueCatalog
        .asTableCatalog()
        .alterTable(
            NameIdentifier.of(schemaName, tableName),
            TableChange.deleteColumn(new String[] {"email"}, true));

    // Verify column was removed
    Table afterDelete =
        glueCatalog.asTableCatalog().loadTable(NameIdentifier.of(schemaName, tableName));
    Assertions.assertEquals(3, afterDelete.columns().length, "Should have 3 columns after delete");

    // Verify remaining columns are correct
    Set<String> remainingColumnNames =
        Arrays.stream(afterDelete.columns()).map(Column::name).collect(Collectors.toSet());
    Assertions.assertTrue(remainingColumnNames.contains("id"), "id column should remain");
    Assertions.assertTrue(remainingColumnNames.contains("name"), "name column should remain");
    Assertions.assertTrue(remainingColumnNames.contains("age"), "age column should remain");
    Assertions.assertFalse(
        remainingColumnNames.contains("email"), "email column should be removed");

    LOG.info("Hive table alter columns verified: final columns = {}", remainingColumnNames);
  }

  // ── 2.9 Drop table ───────────────────────────────────────────────────────

  @Test
  @DisplayName("2.9 Drop table - both Hive and Iceberg tables can be deleted correctly")
  public void testDropHiveAndIcebergTables() {
    String schemaName = testRunPrefix + "_drop_tables";

    // Create schema
    glueCatalog.asSchemas().createSchema(schemaName, "drop table test", Collections.emptyMap());

    // Create a Hive table
    String hiveTableName = "hive_to_drop";
    Column[] hiveColumns = {Column.of("id", Types.LongType.get(), "id")};
    Map<String, String> hiveProps = Maps.newHashMap();
    hiveProps.put("input-format", "org.apache.hadoop.mapred.TextInputFormat");
    hiveProps.put("output-format", "org.apache.hadoop.hive.ql.io.HiveIgnoreKeyTextOutputFormat");
    hiveProps.put("serde-lib", "org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe");

    glueCatalog
        .asTableCatalog()
        .createTable(
            NameIdentifier.of(schemaName, hiveTableName), hiveColumns, "Hive to drop", hiveProps);

    // Create an Iceberg table
    String icebergTableName = "iceberg_to_drop";
    Column[] icebergColumns = {Column.of("id", Types.LongType.get(), "id")};
    Map<String, String> icebergProps = Maps.newHashMap();
    icebergProps.put("table-format", "ICEBERG");

    glueCatalog
        .asTableCatalog()
        .createTable(
            NameIdentifier.of(schemaName, icebergTableName),
            icebergColumns,
            "Iceberg to drop",
            icebergProps);

    // Verify both tables exist
    NameIdentifier[] tablesBefore =
        glueCatalog.asTableCatalog().listTables(Namespace.of(schemaName));
    Assertions.assertEquals(2, tablesBefore.length, "Should have 2 tables before drop");

    // Drop the Hive table
    boolean hiveDropped =
        glueCatalog.asTableCatalog().dropTable(NameIdentifier.of(schemaName, hiveTableName));
    Assertions.assertTrue(hiveDropped, "Hive table drop should return true");

    // Verify Hive table is gone
    boolean hiveDropAgain =
        glueCatalog.asTableCatalog().dropTable(NameIdentifier.of(schemaName, hiveTableName));
    Assertions.assertFalse(
        hiveDropAgain, "Dropping already-dropped Hive table should return false");

    // Verify only Iceberg table remains
    NameIdentifier[] tablesAfterHiveDrop =
        glueCatalog.asTableCatalog().listTables(Namespace.of(schemaName));
    Assertions.assertEquals(
        1, tablesAfterHiveDrop.length, "Should have 1 table after dropping Hive table");
    Assertions.assertEquals(
        icebergTableName, tablesAfterHiveDrop[0].name(), "Remaining table should be Iceberg");

    // Drop the Iceberg table
    boolean icebergDropped =
        glueCatalog.asTableCatalog().dropTable(NameIdentifier.of(schemaName, icebergTableName));
    Assertions.assertTrue(icebergDropped, "Iceberg table drop should return true");

    // Verify Iceberg table is gone
    boolean icebergDropAgain =
        glueCatalog.asTableCatalog().dropTable(NameIdentifier.of(schemaName, icebergTableName));
    Assertions.assertFalse(
        icebergDropAgain, "Dropping already-dropped Iceberg table should return false");

    // Verify schema is now empty
    NameIdentifier[] tablesAfterAll =
        glueCatalog.asTableCatalog().listTables(Namespace.of(schemaName));
    Assertions.assertEquals(0, tablesAfterAll.length, "Schema should be empty after dropping all");

    LOG.info("Drop table verified: both Hive and Iceberg tables deleted successfully");
  }
}
