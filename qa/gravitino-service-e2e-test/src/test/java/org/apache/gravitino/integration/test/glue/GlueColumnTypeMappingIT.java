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
import org.apache.gravitino.Catalog;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.client.GravitinoAdminClient;
import org.apache.gravitino.client.GravitinoMetalake;
import org.apache.gravitino.rel.Column;
import org.apache.gravitino.rel.Table;
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
 * E2E integration tests for Glue catalog column and type mapping.
 *
 * <p>Test plan section 3: Table Column and Type Mapping
 *
 * <ul>
 *   <li>3.1 All basic type mappings
 * </ul>
 */
@DisplayName("Glue Catalog Column Type Mapping Integration Tests")
public class GlueColumnTypeMappingIT {

  private static final Logger LOG = LoggerFactory.getLogger(GlueColumnTypeMappingIT.class);

  private static GravitinoAdminClient adminClient;
  private static GravitinoMetalake metalake;
  private static Catalog glueCatalog;
  private static String metalakeName;
  private static String glueCatalogName;
  private static String testRunPrefix;

  @BeforeAll
  public static void setup() {
    String gravitinoUri = System.getProperty("gravitino.uri", "http://localhost:30090");
    String simpleUser = System.getProperty("gravitino.simple.user", "admin");

    adminClient = GravitinoAdminClient.builder(gravitinoUri).withSimpleAuth(simpleUser).build();

    metalakeName = RandomNameUtils.genRandomName("glue_type_metalake");
    metalake =
        adminClient.createMetalake(
            metalakeName, "Metalake for Glue type mapping tests", Collections.emptyMap());

    glueCatalogName = RandomNameUtils.genRandomName("glue_type_mapping");
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
            "Glue catalog for type mapping tests",
            glueProps);

    testRunPrefix = RandomNameUtils.genRandomName("gtype");
    LOG.info(
        "GlueColumnTypeMappingIT setup complete: metalake={}, catalog={}, prefix={}",
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

  // ── 3.1 All basic type mappings ──────────────────────────────────────────

  @Test
  @DisplayName(
      "3.1 Basic type mappings: int, long, string, double, float, boolean, binary, date, timestamp")
  public void testAllBasicTypeMappings() {
    String schemaName = testRunPrefix + "_basic_types";

    // Create schema
    glueCatalog.asSchemas().createSchema(schemaName, "basic type test", Collections.emptyMap());

    // Create a Hive table with all basic types
    String tableName = "all_basic_types";
    Column[] columns = {
      Column.of("col_int", Types.IntegerType.get(), "integer column"),
      Column.of("col_long", Types.LongType.get(), "long column"),
      Column.of("col_string", Types.StringType.get(), "string column"),
      Column.of("col_double", Types.DoubleType.get(), "double column"),
      Column.of("col_float", Types.FloatType.get(), "float column"),
      Column.of("col_boolean", Types.BooleanType.get(), "boolean column"),
      Column.of("col_binary", Types.BinaryType.get(), "binary column"),
      Column.of("col_date", Types.DateType.get(), "date column"),
      Column.of("col_timestamp", Types.TimestampType.withoutTimeZone(), "timestamp column")
    };

    Map<String, String> hiveProps = Maps.newHashMap();
    hiveProps.put("input-format", "org.apache.hadoop.mapred.TextInputFormat");
    hiveProps.put("output-format", "org.apache.hadoop.hive.ql.io.HiveIgnoreKeyTextOutputFormat");
    hiveProps.put("serde-lib", "org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe");

    glueCatalog
        .asTableCatalog()
        .createTable(
            NameIdentifier.of(schemaName, tableName),
            columns,
            "Table with all basic types",
            hiveProps);

    // Load the table and verify all column types
    Table loadedTable =
        glueCatalog.asTableCatalog().loadTable(NameIdentifier.of(schemaName, tableName));

    Column[] loadedColumns = loadedTable.columns();
    Assertions.assertEquals(9, loadedColumns.length, "Should have 9 columns");

    // Verify each column name and type
    assertColumn(loadedColumns[0], "col_int", Types.IntegerType.get());
    assertColumn(loadedColumns[1], "col_long", Types.LongType.get());
    assertColumn(loadedColumns[2], "col_string", Types.StringType.get());
    assertColumn(loadedColumns[3], "col_double", Types.DoubleType.get());
    assertColumn(loadedColumns[4], "col_float", Types.FloatType.get());
    assertColumn(loadedColumns[5], "col_boolean", Types.BooleanType.get());
    assertColumn(loadedColumns[6], "col_binary", Types.BinaryType.get());
    assertColumn(loadedColumns[7], "col_date", Types.DateType.get());
    assertColumn(loadedColumns[8], "col_timestamp", Types.TimestampType.withoutTimeZone());

    LOG.info("All basic type mappings verified successfully for {} columns", loadedColumns.length);
  }

  // ── 3.1 (Iceberg) All basic type mappings for Iceberg table ──────────────

  @Test
  @DisplayName(
      "3.1 Iceberg basic type mappings: int, long, string, double, float, boolean, binary, date, timestamp")
  public void testAllBasicTypeMappingsIceberg() {
    String schemaName = testRunPrefix + "_ice_types";

    // Create schema
    glueCatalog.asSchemas().createSchema(schemaName, "iceberg type test", Collections.emptyMap());

    // Create an Iceberg table with all basic types
    String tableName = "iceberg_basic_types";
    Column[] columns = {
      Column.of("col_int", Types.IntegerType.get(), "integer column"),
      Column.of("col_long", Types.LongType.get(), "long column"),
      Column.of("col_string", Types.StringType.get(), "string column"),
      Column.of("col_double", Types.DoubleType.get(), "double column"),
      Column.of("col_float", Types.FloatType.get(), "float column"),
      Column.of("col_boolean", Types.BooleanType.get(), "boolean column"),
      Column.of("col_binary", Types.BinaryType.get(), "binary column"),
      Column.of("col_date", Types.DateType.get(), "date column"),
      Column.of("col_timestamp", Types.TimestampType.withoutTimeZone(), "timestamp column")
    };

    Map<String, String> icebergProps = Maps.newHashMap();
    icebergProps.put("table-format", "ICEBERG");

    glueCatalog
        .asTableCatalog()
        .createTable(
            NameIdentifier.of(schemaName, tableName),
            columns,
            "Iceberg table with all basic types",
            icebergProps);

    // Load the table and verify all column types
    Table loadedTable =
        glueCatalog.asTableCatalog().loadTable(NameIdentifier.of(schemaName, tableName));

    Column[] loadedColumns = loadedTable.columns();
    Assertions.assertEquals(9, loadedColumns.length, "Should have 9 columns for Iceberg table");

    // Verify each column name and type
    assertColumn(loadedColumns[0], "col_int", Types.IntegerType.get());
    assertColumn(loadedColumns[1], "col_long", Types.LongType.get());
    assertColumn(loadedColumns[2], "col_string", Types.StringType.get());
    assertColumn(loadedColumns[3], "col_double", Types.DoubleType.get());
    assertColumn(loadedColumns[4], "col_float", Types.FloatType.get());
    assertColumn(loadedColumns[5], "col_boolean", Types.BooleanType.get());
    assertColumn(loadedColumns[6], "col_binary", Types.BinaryType.get());
    assertColumn(loadedColumns[7], "col_date", Types.DateType.get());
    assertColumn(loadedColumns[8], "col_timestamp", Types.TimestampType.withoutTimeZone(6));

    // Verify it's actually an Iceberg table
    Assertions.assertEquals(
        "ICEBERG",
        loadedTable.properties().getOrDefault("table_type", "").toUpperCase(),
        "Table should be Iceberg format");

    LOG.info(
        "Iceberg basic type mappings verified successfully for {} columns", loadedColumns.length);
  }

  // ── 3.2 Complex type mappings ────────────────────────────────────────────

  @Test
  @DisplayName("3.2 Complex type mappings for Hive: array, map, struct")
  public void testComplexTypeMappingsHive() {
    String schemaName = testRunPrefix + "_complex_hive";

    // Create schema
    glueCatalog
        .asSchemas()
        .createSchema(schemaName, "complex type hive test", Collections.emptyMap());

    // Define complex types
    Types.ListType arrayType = Types.ListType.nullable(Types.StringType.get());
    Types.MapType mapType =
        Types.MapType.valueNullable(Types.StringType.get(), Types.IntegerType.get());
    Types.StructType structType =
        Types.StructType.of(
            Types.StructType.Field.nullableField("field_name", Types.StringType.get()),
            Types.StructType.Field.nullableField("field_age", Types.IntegerType.get()));

    String tableName = "hive_complex_types";
    Column[] columns = {
      Column.of("col_array", arrayType, "array column"),
      Column.of("col_map", mapType, "map column"),
      Column.of("col_struct", structType, "struct column")
    };

    Map<String, String> hiveProps = Maps.newHashMap();
    hiveProps.put("input-format", "org.apache.hadoop.mapred.TextInputFormat");
    hiveProps.put("output-format", "org.apache.hadoop.hive.ql.io.HiveIgnoreKeyTextOutputFormat");
    hiveProps.put("serde-lib", "org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe");

    glueCatalog
        .asTableCatalog()
        .createTable(
            NameIdentifier.of(schemaName, tableName),
            columns,
            "Hive table with complex types",
            hiveProps);

    // Load and verify
    Table loadedTable =
        glueCatalog.asTableCatalog().loadTable(NameIdentifier.of(schemaName, tableName));
    Column[] loadedColumns = loadedTable.columns();
    Assertions.assertEquals(3, loadedColumns.length, "Should have 3 columns");

    // Verify array type (ListType)
    Assertions.assertEquals("col_array", loadedColumns[0].name());
    Assertions.assertInstanceOf(
        Types.ListType.class,
        loadedColumns[0].dataType(),
        "col_array should be ListType, got: " + loadedColumns[0].dataType());

    // Verify map type (MapType)
    Assertions.assertEquals("col_map", loadedColumns[1].name());
    Assertions.assertInstanceOf(
        Types.MapType.class,
        loadedColumns[1].dataType(),
        "col_map should be MapType, got: " + loadedColumns[1].dataType());

    // Verify struct type (StructType)
    Assertions.assertEquals("col_struct", loadedColumns[2].name());
    Assertions.assertInstanceOf(
        Types.StructType.class,
        loadedColumns[2].dataType(),
        "col_struct should be StructType, got: " + loadedColumns[2].dataType());

    LOG.info(
        "Hive complex type mappings verified: array={}, map={}, struct={}",
        loadedColumns[0].dataType(),
        loadedColumns[1].dataType(),
        loadedColumns[2].dataType());
  }

  @Test
  @DisplayName("3.2 Complex type mappings for Iceberg: array, map, struct")
  public void testComplexTypeMappingsIceberg() {
    String schemaName = testRunPrefix + "_complex_ice";

    // Create schema
    glueCatalog
        .asSchemas()
        .createSchema(schemaName, "complex type iceberg test", Collections.emptyMap());

    // Define complex types
    Types.ListType arrayType = Types.ListType.nullable(Types.StringType.get());
    Types.MapType mapType =
        Types.MapType.valueNullable(Types.StringType.get(), Types.IntegerType.get());
    Types.StructType structType =
        Types.StructType.of(
            Types.StructType.Field.nullableField("field_name", Types.StringType.get()),
            Types.StructType.Field.nullableField("field_age", Types.IntegerType.get()));

    String tableName = "iceberg_complex_types";
    Column[] columns = {
      Column.of("col_array", arrayType, "array column"),
      Column.of("col_map", mapType, "map column"),
      Column.of("col_struct", structType, "struct column")
    };

    Map<String, String> icebergProps = Maps.newHashMap();
    icebergProps.put("table-format", "ICEBERG");

    glueCatalog
        .asTableCatalog()
        .createTable(
            NameIdentifier.of(schemaName, tableName),
            columns,
            "Iceberg table with complex types",
            icebergProps);

    // Load and verify
    Table loadedTable =
        glueCatalog.asTableCatalog().loadTable(NameIdentifier.of(schemaName, tableName));
    Column[] loadedColumns = loadedTable.columns();
    Assertions.assertEquals(3, loadedColumns.length, "Should have 3 columns");

    // Verify array type (ListType)
    Assertions.assertEquals("col_array", loadedColumns[0].name());
    Assertions.assertInstanceOf(
        Types.ListType.class,
        loadedColumns[0].dataType(),
        "col_array should be ListType, got: " + loadedColumns[0].dataType());

    // Verify map type (MapType)
    Assertions.assertEquals("col_map", loadedColumns[1].name());
    Assertions.assertInstanceOf(
        Types.MapType.class,
        loadedColumns[1].dataType(),
        "col_map should be MapType, got: " + loadedColumns[1].dataType());

    // Verify struct type (StructType)
    Assertions.assertEquals("col_struct", loadedColumns[2].name());
    Assertions.assertInstanceOf(
        Types.StructType.class,
        loadedColumns[2].dataType(),
        "col_struct should be StructType, got: " + loadedColumns[2].dataType());

    // Verify it's an Iceberg table
    Assertions.assertEquals(
        "ICEBERG",
        loadedTable.properties().getOrDefault("table_type", "").toUpperCase(),
        "Table should be Iceberg format");

    LOG.info(
        "Iceberg complex type mappings verified: array={}, map={}, struct={}",
        loadedColumns[0].dataType(),
        loadedColumns[1].dataType(),
        loadedColumns[2].dataType());
  }

  // ── 3.3 NOT NULL constraint is rejected by Glue catalog ────────────────

  @Test
  @DisplayName("3.3 NOT NULL constraint: creation should fail because Glue does not support it")
  public void testNotNullConstraintRejectedForHive() {
    String schemaName = testRunPrefix + "_not_null";

    // Create schema
    glueCatalog.asSchemas().createSchema(schemaName, "not null test", Collections.emptyMap());

    // Create a Hive table with NOT NULL columns (nullable=false)
    String tableName = "hive_not_null";
    Column[] columns = {
      Column.of("id", Types.LongType.get(), "primary key", false, false, null),
      Column.of("name", Types.StringType.get(), "required name", false, false, null),
      Column.of("optional_field", Types.StringType.get(), "optional field", true, false, null)
    };

    Map<String, String> hiveProps = Maps.newHashMap();
    hiveProps.put("input-format", "org.apache.hadoop.mapred.TextInputFormat");
    hiveProps.put("output-format", "org.apache.hadoop.hive.ql.io.HiveIgnoreKeyTextOutputFormat");
    hiveProps.put("serde-lib", "org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe");

    // Creation should fail because Glue does not support NOT NULL constraints
    IllegalArgumentException exception =
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () ->
                glueCatalog
                    .asTableCatalog()
                    .createTable(
                        NameIdentifier.of(schemaName, tableName),
                        columns,
                        "Table with NOT NULL columns",
                        hiveProps));
    Assertions.assertTrue(
        exception.getMessage().contains("NOT NULL"),
        "Exception should mention NOT NULL constraint: " + exception.getMessage());

    LOG.info(
        "NOT NULL constraint correctly rejected for Hive table on Glue: {}",
        exception.getMessage());
  }

  @Test
  @DisplayName(
      "3.3 NOT NULL constraint for Iceberg: creation should fail because Glue does not support it")
  public void testNotNullConstraintRejectedForIceberg() {
    String schemaName = testRunPrefix + "_ice_not_null";

    // Create schema
    glueCatalog
        .asSchemas()
        .createSchema(schemaName, "iceberg not null test", Collections.emptyMap());

    // Create an Iceberg table with NOT NULL columns (nullable=false)
    String tableName = "iceberg_not_null";
    Column[] columns = {
      Column.of("id", Types.LongType.get(), "primary key", false, false, null),
      Column.of("name", Types.StringType.get(), "required name", false, false, null),
      Column.of("optional_field", Types.StringType.get(), "optional field", true, false, null)
    };

    Map<String, String> icebergProps = Maps.newHashMap();
    icebergProps.put("table-format", "ICEBERG");

    // Creation should fail because Glue catalog does not support NOT NULL constraints
    IllegalArgumentException exception =
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () ->
                glueCatalog
                    .asTableCatalog()
                    .createTable(
                        NameIdentifier.of(schemaName, tableName),
                        columns,
                        "Iceberg table with NOT NULL columns",
                        icebergProps));
    Assertions.assertTrue(
        exception.getMessage().contains("NOT NULL"),
        "Exception should mention NOT NULL constraint: " + exception.getMessage());

    LOG.info(
        "NOT NULL constraint correctly rejected for Iceberg table on Glue: {}",
        exception.getMessage());
  }

  // ── 3.4 DEFAULT value is rejected by Glue ─────────────────────────────────

  @Test
  @DisplayName("3.4 DEFAULT value: creation should fail because Glue does not support it")
  public void testDefaultValueRejectedForGlue() {
    String schemaName = testRunPrefix + "_default_val";

    // Create schema
    glueCatalog.asSchemas().createSchema(schemaName, "default value test", Collections.emptyMap());

    // Attempt to create a Hive table with DEFAULT values on columns
    String tableName = "hive_with_defaults";
    Column[] columns = {
      Column.of(
          "id",
          Types.LongType.get(),
          "primary key",
          true,
          false,
          org.apache.gravitino.rel.expressions.literals.Literals.longLiteral(0L)),
      Column.of(
          "name",
          Types.StringType.get(),
          "user name",
          true,
          false,
          org.apache.gravitino.rel.expressions.literals.Literals.stringLiteral("unknown")),
      Column.of(
          "status",
          Types.IntegerType.get(),
          "status code",
          true,
          false,
          org.apache.gravitino.rel.expressions.literals.Literals.integerLiteral(1)),
      Column.of("no_default", Types.StringType.get(), "no default value")
    };

    Map<String, String> hiveProps = Maps.newHashMap();
    hiveProps.put("input-format", "org.apache.hadoop.mapred.TextInputFormat");
    hiveProps.put("output-format", "org.apache.hadoop.hive.ql.io.HiveIgnoreKeyTextOutputFormat");
    hiveProps.put("serde-lib", "org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe");

    // Glue does not support DEFAULT values — creation must be rejected
    Exception exception =
        Assertions.assertThrows(
            Exception.class,
            () ->
                glueCatalog
                    .asTableCatalog()
                    .createTable(
                        NameIdentifier.of(schemaName, tableName),
                        columns,
                        "Table with DEFAULT values",
                        hiveProps),
            "Creating a table with DEFAULT values should fail on Glue");

    Assertions.assertTrue(
        exception.getMessage().contains("does not support DEFAULT values"),
        "Error message should indicate DEFAULT values are unsupported, got: "
            + exception.getMessage());

    LOG.info("DEFAULT value correctly rejected for Hive table on Glue: {}", exception.getMessage());
  }

  // ── 3.5 Partition columns ────────────────────────────────────────────────

  @Test
  @DisplayName("3.5 Partition columns: verify partitionKeys are correctly mapped")
  public void testPartitionColumnsHive() {
    String schemaName = testRunPrefix + "_partitions";

    // Create schema
    glueCatalog.asSchemas().createSchema(schemaName, "partition test", Collections.emptyMap());

    // Create a Hive table with partition columns
    String tableName = "hive_partitioned";
    Column[] columns = {
      Column.of("id", Types.LongType.get(), "primary key"),
      Column.of("name", Types.StringType.get(), "user name"),
      Column.of("dt", Types.DateType.get(), "partition date"),
      Column.of("region", Types.StringType.get(), "partition region")
    };

    Map<String, String> hiveProps = Maps.newHashMap();
    hiveProps.put("input-format", "org.apache.hadoop.mapred.TextInputFormat");
    hiveProps.put("output-format", "org.apache.hadoop.hive.ql.io.HiveIgnoreKeyTextOutputFormat");
    hiveProps.put("serde-lib", "org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe");

    // Create table with identity partitioning on 'dt' and 'region'
    org.apache.gravitino.rel.expressions.transforms.Transform[] partitions = {
      org.apache.gravitino.rel.expressions.transforms.Transforms.identity("dt"),
      org.apache.gravitino.rel.expressions.transforms.Transforms.identity("region")
    };

    glueCatalog
        .asTableCatalog()
        .createTable(
            NameIdentifier.of(schemaName, tableName),
            columns,
            "Hive partitioned table",
            hiveProps,
            partitions);

    // Load the table and verify partitioning
    Table loadedTable =
        glueCatalog.asTableCatalog().loadTable(NameIdentifier.of(schemaName, tableName));

    Assertions.assertNotNull(loadedTable, "Loaded table should not be null");

    // Verify partition transforms are preserved
    org.apache.gravitino.rel.expressions.transforms.Transform[] loadedPartitions =
        loadedTable.partitioning();
    Assertions.assertNotNull(loadedPartitions, "Partitioning should not be null");
    Assertions.assertEquals(2, loadedPartitions.length, "Should have 2 partition transforms");

    // Verify partition column names
    Assertions.assertInstanceOf(
        org.apache.gravitino.rel.expressions.transforms.Transforms.IdentityTransform.class,
        loadedPartitions[0],
        "First partition should be identity transform");
    Assertions.assertInstanceOf(
        org.apache.gravitino.rel.expressions.transforms.Transforms.IdentityTransform.class,
        loadedPartitions[1],
        "Second partition should be identity transform");

    LOG.info("Partition columns verified: {} partitions found", loadedPartitions.length);
  }

  /** Helper to assert column name and type. */
  private void assertColumn(
      Column actual, String expectedName, org.apache.gravitino.rel.types.Type expectedType) {
    Assertions.assertEquals(expectedName, actual.name(), "Column name mismatch");
    Assertions.assertEquals(
        expectedType,
        actual.dataType(),
        "Column type mismatch for '"
            + expectedName
            + "': expected "
            + expectedType
            + " but got "
            + actual.dataType());
  }
}
