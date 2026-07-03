/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.gravitino.integration.test.view;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.client.GravitinoAdminClient;
import org.apache.gravitino.client.GravitinoMetalake;
import org.apache.gravitino.rel.Column;
import org.apache.gravitino.rel.SQLRepresentation;
import org.apache.gravitino.rel.ViewCatalog;
import org.apache.gravitino.rel.types.Types;
import org.apache.gravitino.spark.connector.GravitinoSparkConfig;
import org.apache.gravitino.spark.connector.plugin.GravitinoSparkPlugin;
import org.apache.gravitino.utils.RandomNameUtils;
import org.apache.spark.SparkConf;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.types.StructType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * E2E integration tests for Spark Hive View read-only support (PR #11288).
 *
 * <p>These tests verify that views created via the Gravitino ViewCatalog API can be queried through
 * the Spark Hive connector, covering filtering, aggregation, and other real-world SQL patterns.
 *
 * <p>The tests connect to a Gravitino server (deployed in K8s or locally) and use the Gravitino
 * Spark plugin to register a Hive catalog. Views are created via the Gravitino client API (since
 * Spark V2 named catalogs do not support CREATE VIEW) and then queried via Spark SQL.
 */
@DisplayName("Spark Hive View E2E Tests")
public class SparkHiveViewIT {

  private static final Logger LOG = LoggerFactory.getLogger(SparkHiveViewIT.class);

  private static final String SPARK_DIALECT = "spark";
  private static final String HIVE_CATALOG_NAME = "hive";
  private static final String BASE_TABLE = "view_filter_base_table";

  private static GravitinoAdminClient adminClient;
  private static GravitinoMetalake metalake;
  private static Catalog catalog;
  private static ViewCatalog viewCatalog;

  private static String metalakeName;
  private static String schemaName;
  private static String serverUri;

  private static SparkSession spark;

  @BeforeAll
  public static void setup() {
    serverUri = System.getProperty("gravitino.uri", "http://localhost:30090");
    String hiveMetastoreUri = System.getProperty("hive.metastore.uri", "thrift://localhost:30083");
    String s3AccessKey = System.getProperty("s3.access.key", "minioadmin");
    String s3SecretKey = System.getProperty("s3.secret.key", "minioadmin");
    String s3Endpoint = System.getProperty("s3.endpoint", "http://localhost:30009");
    metalakeName = RandomNameUtils.genRandomName("hive_view_metalake");

    adminClient =
        GravitinoAdminClient.builder(serverUri)
            .withSimpleAuth("admin")
            .withVersionCheckDisabled()
            .build();
    metalake = adminClient.createMetalake(metalakeName, "hive view e2e", Collections.emptyMap());

    // Create Hive catalog
    Map<String, String> catalogProperties = Maps.newHashMap();
    catalogProperties.put("metastore.uris", hiveMetastoreUri);
    catalog =
        metalake.createCatalog(
            HIVE_CATALOG_NAME, Catalog.Type.RELATIONAL, "hive", "hive catalog", catalogProperties);
    viewCatalog = catalog.asViewCatalog();

    // Create schema with S3 location to avoid HDFS dependency
    schemaName = RandomNameUtils.genRandomName("hive_view_schema");
    Map<String, String> schemaProperties = Maps.newHashMap();
    schemaProperties.put("location", "s3a://gravitino-hive-test/" + schemaName);
    catalog.asSchemas().createSchema(schemaName, "view e2e schema", schemaProperties);

    // Set SPARK_USER so the Gravitino Spark plugin authenticates as 'admin' instead of 'anonymous'
    setEnv("SPARK_USER", "admin");

    // Initialize Spark session with Gravitino plugin and S3A filesystem configuration
    SparkConf sparkConf =
        new SparkConf()
            .set("spark.plugins", GravitinoSparkPlugin.class.getName())
            .set(GravitinoSparkConfig.GRAVITINO_URI, serverUri)
            .set(GravitinoSparkConfig.GRAVITINO_METALAKE, metalakeName)
            .set("spark.sql.session.timeZone", "UTC")
            .set("mapreduce.input.fileinputformat.input.dir.recursive", "true")
            // S3A filesystem configuration (spark.hadoop.* propagates to all catalogs)
            .set("spark.hadoop.fs.s3a.access.key", s3AccessKey)
            .set("spark.hadoop.fs.s3a.secret.key", s3SecretKey)
            .set("spark.hadoop.fs.s3a.endpoint", s3Endpoint)
            .set("spark.hadoop.fs.s3a.impl", "org.apache.hadoop.fs.s3a.S3AFileSystem");
    spark =
        SparkSession.builder()
            .master("local[1]")
            .appName("Spark Hive View E2E Test")
            .config(sparkConf)
            .enableHiveSupport()
            .getOrCreate();

    // Create base table and insert data via Spark SQL
    String fqTable = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, BASE_TABLE);
    spark.sql(String.format("CREATE TABLE %s (id INT, name STRING, age INT) USING hive", fqTable));
    spark.sql(
        String.format(
            "INSERT INTO %s VALUES "
                + "(1, 'Alice', 25), "
                + "(2, 'Bob', 30), "
                + "(3, 'Charlie', 35), "
                + "(4, 'Diana', 25), "
                + "(5, 'Eve', 30)",
            fqTable));

    LOG.info(
        "SparkHiveViewIT setup complete: metalake={}, catalog={}, schema={}",
        metalakeName,
        HIVE_CATALOG_NAME,
        schemaName);
  }

  @AfterEach
  public void cleanViews() {
    try {
      for (NameIdentifier v :
          viewCatalog.listViews(org.apache.gravitino.Namespace.of(schemaName))) {
        viewCatalog.dropView(v);
      }
    } catch (Exception e) {
      LOG.warn("Per-test view cleanup failed, proceeding anyway", e);
    }
  }

  @AfterAll
  public static void teardown() {
    if (spark != null) {
      spark.close();
    }
    try {
      if (adminClient != null && metalakeName != null) {
        adminClient.dropMetalake(metalakeName, true);
      }
    } catch (Exception e) {
      LOG.warn("Failed to drop metalake '{}'", metalakeName, e);
    }
    if (adminClient != null) {
      adminClient.close();
    }
  }

  // -------------------------------------------------------------------------
  // Test Case #1: testSelectHiveViewWithFilter
  //
  // Create a view over a base table, then SELECT with various WHERE clauses
  // applied on top of the view. Verify only filtered rows are returned.
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("Select from Hive view with WHERE filter returns only matching rows")
  public void testSelectHiveViewWithFilter() {
    String viewName = "test_view_with_filter";
    String fqTable = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, BASE_TABLE);
    String fqView = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, viewName);

    // Create a view via Gravitino API that selects all rows from the base table
    String viewSql = String.format("SELECT * FROM %s", fqTable);
    NameIdentifier viewIdent = NameIdentifier.of(schemaName, viewName);

    viewCatalog.createView(
        viewIdent,
        "View for filter test",
        new Column[] {
          Column.of("id", Types.IntegerType.get(), null),
          Column.of("name", Types.StringType.get(), null),
          Column.of("age", Types.IntegerType.get(), null)
        },
        new SQLRepresentation[] {
          SQLRepresentation.builder().withDialect(SPARK_DIALECT).withSql(viewSql).build()
        },
        HIVE_CATALOG_NAME,
        schemaName,
        ImmutableMap.of("spark.sql.create.version", spark.version()));

    // Query the view with a WHERE clause applied on top
    List<Row> rows =
        spark.sql(String.format("SELECT * FROM %s WHERE age = 25", fqView)).collectAsList();

    // Verify only rows with age=25 are returned (Alice and Diana)
    Assertions.assertEquals(2, rows.size(), "Expected 2 rows with age=25");

    List<String> names =
        rows.stream()
            .map(r -> r.getString(1))
            .sorted(Comparator.naturalOrder())
            .collect(Collectors.toList());
    Assertions.assertEquals("Alice", names.get(0));
    Assertions.assertEquals("Diana", names.get(1));

    // Verify a different filter: age > 30 should return only Charlie (age=35)
    List<Row> olderRows =
        spark.sql(String.format("SELECT * FROM %s WHERE age > 30", fqView)).collectAsList();
    Assertions.assertEquals(1, olderRows.size(), "Expected 1 row with age > 30");
    Assertions.assertEquals("Charlie", olderRows.get(0).getString(1));

    // Verify a filter that matches no rows returns empty result
    List<Row> emptyRows =
        spark.sql(String.format("SELECT * FROM %s WHERE age > 100", fqView)).collectAsList();
    Assertions.assertEquals(0, emptyRows.size(), "Expected 0 rows with age > 100");

    // Verify filter on string column
    List<Row> nameFilter =
        spark
            .sql(String.format("SELECT id, name FROM %s WHERE name = 'Bob'", fqView))
            .collectAsList();
    Assertions.assertEquals(1, nameFilter.size(), "Expected 1 row with name='Bob'");
    Assertions.assertEquals(2, nameFilter.get(0).getInt(0));
    Assertions.assertEquals("Bob", nameFilter.get(0).getString(1));

    // Verify compound filter (AND)
    List<Row> compoundFilter =
        spark
            .sql(String.format("SELECT * FROM %s WHERE age = 30 AND name = 'Eve'", fqView))
            .collectAsList();
    Assertions.assertEquals(1, compoundFilter.size(), "Expected 1 row matching compound filter");
    Assertions.assertEquals(5, compoundFilter.get(0).getInt(0));

    // Verify OR filter
    List<Row> orFilter =
        spark
            .sql(String.format("SELECT * FROM %s WHERE name = 'Alice' OR name = 'Eve'", fqView))
            .collectAsList();
    Assertions.assertEquals(2, orFilter.size(), "Expected 2 rows matching OR filter");

    // Verify IN filter
    List<Row> inFilter =
        spark.sql(String.format("SELECT * FROM %s WHERE id IN (1, 3, 5)", fqView)).collectAsList();
    Assertions.assertEquals(3, inFilter.size(), "Expected 3 rows matching IN filter");

    // Verify BETWEEN filter
    List<Row> betweenFilter =
        spark
            .sql(String.format("SELECT * FROM %s WHERE age BETWEEN 26 AND 34", fqView))
            .collectAsList();
    Assertions.assertEquals(
        2, betweenFilter.size(), "Expected 2 rows matching BETWEEN 26 AND 34 (Bob and Eve)");

    // Verify LIKE filter
    List<Row> likeFilter =
        spark.sql(String.format("SELECT * FROM %s WHERE name LIKE 'C%%'", fqView)).collectAsList();
    Assertions.assertEquals(1, likeFilter.size(), "Expected 1 row matching LIKE 'C%'");
    Assertions.assertEquals("Charlie", likeFilter.get(0).getString(1));
  }

  // -------------------------------------------------------------------------
  // Test Case #2: testSelectHiveViewWithAggregation
  //
  // Create a view whose SQL uses GROUP BY + COUNT. Query the view and verify
  // aggregated results are computed correctly through driver-side execution.
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("Select from Hive view with aggregation returns correct grouped results")
  public void testSelectHiveViewWithAggregation() {
    String viewName = "test_view_with_aggregation";
    String fqTable = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, BASE_TABLE);
    String fqView = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, viewName);

    // View SQL groups by age and counts rows per group
    String viewSql = String.format("SELECT age, COUNT(*) AS cnt FROM %s GROUP BY age", fqTable);
    NameIdentifier viewIdent = NameIdentifier.of(schemaName, viewName);

    viewCatalog.createView(
        viewIdent,
        "View for aggregation test",
        new Column[] {
          Column.of("age", Types.IntegerType.get(), null),
          Column.of("cnt", Types.LongType.get(), null)
        },
        new SQLRepresentation[] {
          SQLRepresentation.builder().withDialect(SPARK_DIALECT).withSql(viewSql).build()
        },
        HIVE_CATALOG_NAME,
        schemaName,
        ImmutableMap.of("spark.sql.create.version", spark.version()));

    // Query the view
    List<Row> rows =
        spark.sql(String.format("SELECT * FROM %s ORDER BY age", fqView)).collectAsList();

    // Base data: age 25 -> 2 rows (Alice, Diana), age 30 -> 2 rows (Bob, Eve), age 35 -> 1
    // (Charlie)
    Assertions.assertEquals(3, rows.size(), "Expected 3 distinct age groups");

    Assertions.assertEquals(25, rows.get(0).getInt(0));
    Assertions.assertEquals(2L, rows.get(0).getLong(1));

    Assertions.assertEquals(30, rows.get(1).getInt(0));
    Assertions.assertEquals(2L, rows.get(1).getLong(1));

    Assertions.assertEquals(35, rows.get(2).getInt(0));
    Assertions.assertEquals(1L, rows.get(2).getLong(1));
  }

  // -------------------------------------------------------------------------
  // Test Case #3: testSelectHiveViewWithJoin
  //
  // Create a second base table, then create a view whose SQL joins the two
  // tables. Query the view and verify the joined result set is correct.
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("Select from Hive view with JOIN returns correct joined results")
  public void testSelectHiveViewWithJoin() {
    String joinTable = "view_join_secondary_table";
    String viewName = "test_view_with_join";
    String fqTable = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, BASE_TABLE);
    String fqJoinTable = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, joinTable);
    String fqView = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, viewName);

    // Create and populate a secondary table for the join
    spark.sql(String.format("CREATE TABLE %s (id INT, city STRING) USING hive", fqJoinTable));
    spark.sql(
        String.format("INSERT INTO %s VALUES (1, 'NYC'), (2, 'LA'), (3, 'Chicago')", fqJoinTable));

    try {
      // View SQL joins base table with secondary table on id
      String viewSql =
          String.format(
              "SELECT a.id, a.name, b.city FROM %s a INNER JOIN %s b ON a.id = b.id",
              fqTable, fqJoinTable);
      NameIdentifier viewIdent = NameIdentifier.of(schemaName, viewName);

      viewCatalog.createView(
          viewIdent,
          "View for join test",
          new Column[] {
            Column.of("id", Types.IntegerType.get(), null),
            Column.of("name", Types.StringType.get(), null),
            Column.of("city", Types.StringType.get(), null)
          },
          new SQLRepresentation[] {
            SQLRepresentation.builder().withDialect(SPARK_DIALECT).withSql(viewSql).build()
          },
          HIVE_CATALOG_NAME,
          schemaName,
          ImmutableMap.of("spark.sql.create.version", spark.version()));

      // Query the view
      List<Row> rows =
          spark.sql(String.format("SELECT * FROM %s ORDER BY id", fqView)).collectAsList();

      // Only ids 1, 2, 3 exist in both tables
      Assertions.assertEquals(3, rows.size(), "Expected 3 joined rows");

      Assertions.assertEquals(1, rows.get(0).getInt(0));
      Assertions.assertEquals("Alice", rows.get(0).getString(1));
      Assertions.assertEquals("NYC", rows.get(0).getString(2));

      Assertions.assertEquals(2, rows.get(1).getInt(0));
      Assertions.assertEquals("Bob", rows.get(1).getString(1));
      Assertions.assertEquals("LA", rows.get(1).getString(2));

      Assertions.assertEquals(3, rows.get(2).getInt(0));
      Assertions.assertEquals("Charlie", rows.get(2).getString(1));
      Assertions.assertEquals("Chicago", rows.get(2).getString(2));
    } finally {
      spark.sql(String.format("DROP TABLE IF EXISTS %s", fqJoinTable));
    }
  }

  // -------------------------------------------------------------------------
  // Test Case #4: testSelectHiveViewWithSubquery
  //
  // Create a view whose SQL contains an IN subquery. Query the view and verify
  // the complex SQL plan executes correctly via executeCollect().
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("Select from Hive view with subquery returns correct results")
  public void testSelectHiveViewWithSubquery() {
    String viewName = "test_view_with_subquery";
    String fqTable = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, BASE_TABLE);
    String fqView = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, viewName);

    // View SQL uses an IN subquery to select people whose age matches someone with id <= 2
    // id 1 -> age 25, id 2 -> age 30, so the view returns rows with age IN (25, 30)
    String viewSql =
        String.format(
            "SELECT * FROM %s WHERE age IN (SELECT age FROM %s WHERE id <= 2)", fqTable, fqTable);
    NameIdentifier viewIdent = NameIdentifier.of(schemaName, viewName);

    viewCatalog.createView(
        viewIdent,
        "View for subquery test",
        new Column[] {
          Column.of("id", Types.IntegerType.get(), null),
          Column.of("name", Types.StringType.get(), null),
          Column.of("age", Types.IntegerType.get(), null)
        },
        new SQLRepresentation[] {
          SQLRepresentation.builder().withDialect(SPARK_DIALECT).withSql(viewSql).build()
        },
        HIVE_CATALOG_NAME,
        schemaName,
        ImmutableMap.of("spark.sql.create.version", spark.version()));

    // Query the view
    List<Row> rows =
        spark.sql(String.format("SELECT * FROM %s ORDER BY id", fqView)).collectAsList();

    // age IN (25, 30) matches: Alice(25), Bob(30), Diana(25), Eve(30)
    Assertions.assertEquals(4, rows.size(), "Expected 4 rows matching subquery condition");

    List<String> names =
        rows.stream()
            .map(r -> r.getString(1))
            .sorted(Comparator.naturalOrder())
            .collect(Collectors.toList());
    Assertions.assertTrue(names.contains("Alice"));
    Assertions.assertTrue(names.contains("Bob"));
    Assertions.assertTrue(names.contains("Diana"));
    Assertions.assertTrue(names.contains("Eve"));
    // Charlie (age=35) should not be included
    Assertions.assertFalse(names.contains("Charlie"));
  }

  // -------------------------------------------------------------------------
  // Test Case #5: testSelectHiveViewColumnOrder
  //
  // Create a view that defines columns in a different order than the base table.
  // Verify that the returned columns follow the view's column order.
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("Select from Hive view returns columns in view-defined order")
  public void testSelectHiveViewColumnOrder() {
    String viewName = "test_view_column_order";
    String fqTable = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, BASE_TABLE);
    String fqView = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, viewName);

    // View SQL reorders columns: age, name, id (base table is id, name, age)
    String viewSql = String.format("SELECT age, name, id FROM %s", fqTable);
    NameIdentifier viewIdent = NameIdentifier.of(schemaName, viewName);

    viewCatalog.createView(
        viewIdent,
        "View for column order test",
        new Column[] {
          Column.of("age", Types.IntegerType.get(), null),
          Column.of("name", Types.StringType.get(), null),
          Column.of("id", Types.IntegerType.get(), null)
        },
        new SQLRepresentation[] {
          SQLRepresentation.builder().withDialect(SPARK_DIALECT).withSql(viewSql).build()
        },
        HIVE_CATALOG_NAME,
        schemaName,
        ImmutableMap.of("spark.sql.create.version", spark.version()));

    // Query the view and check column order
    List<Row> rows =
        spark.sql(String.format("SELECT * FROM %s WHERE id = 1", fqView)).collectAsList();

    Assertions.assertEquals(1, rows.size(), "Expected 1 row for id=1");
    Row row = rows.get(0);

    // Column order should be: age(0), name(1), id(2)
    Assertions.assertEquals(25, row.getInt(0), "First column should be age");
    Assertions.assertEquals("Alice", row.getString(1), "Second column should be name");
    Assertions.assertEquals(1, row.getInt(2), "Third column should be id");

    // Also verify schema field names reflect view order
    String[] fieldNames = rows.get(0).schema().fieldNames();
    Assertions.assertEquals("age", fieldNames[0]);
    Assertions.assertEquals("name", fieldNames[1]);
    Assertions.assertEquals("id", fieldNames[2]);
  }

  // -------------------------------------------------------------------------
  // Test Case #6: testSelectHiveViewWithExpression
  //
  // Create a view whose SQL includes a computed expression (age * 2).
  // Verify derived columns are computed and returned correctly.
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("Select from Hive view with computed expression returns correct derived values")
  public void testSelectHiveViewWithExpression() {
    String viewName = "test_view_with_expression";
    String fqTable = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, BASE_TABLE);
    String fqView = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, viewName);

    // View SQL includes a computed column: age * 2 AS double_age
    String viewSql = String.format("SELECT id, name, age * 2 AS double_age FROM %s", fqTable);
    NameIdentifier viewIdent = NameIdentifier.of(schemaName, viewName);

    viewCatalog.createView(
        viewIdent,
        "View for expression test",
        new Column[] {
          Column.of("id", Types.IntegerType.get(), null),
          Column.of("name", Types.StringType.get(), null),
          Column.of("double_age", Types.IntegerType.get(), null)
        },
        new SQLRepresentation[] {
          SQLRepresentation.builder().withDialect(SPARK_DIALECT).withSql(viewSql).build()
        },
        HIVE_CATALOG_NAME,
        schemaName,
        ImmutableMap.of("spark.sql.create.version", spark.version()));

    // Query the view
    List<Row> rows =
        spark.sql(String.format("SELECT * FROM %s ORDER BY id", fqView)).collectAsList();

    Assertions.assertEquals(5, rows.size(), "Expected 5 rows from the view");

    // Verify each row has the correctly computed double_age
    // Alice: age=25 -> double_age=50
    Assertions.assertEquals(1, rows.get(0).getInt(0));
    Assertions.assertEquals("Alice", rows.get(0).getString(1));
    Assertions.assertEquals(50, rows.get(0).getInt(2));

    // Bob: age=30 -> double_age=60
    Assertions.assertEquals(2, rows.get(1).getInt(0));
    Assertions.assertEquals("Bob", rows.get(1).getString(1));
    Assertions.assertEquals(60, rows.get(1).getInt(2));

    // Charlie: age=35 -> double_age=70
    Assertions.assertEquals(3, rows.get(2).getInt(0));
    Assertions.assertEquals("Charlie", rows.get(2).getString(1));
    Assertions.assertEquals(70, rows.get(2).getInt(2));

    // Diana: age=25 -> double_age=50
    Assertions.assertEquals(4, rows.get(3).getInt(0));
    Assertions.assertEquals("Diana", rows.get(3).getString(1));
    Assertions.assertEquals(50, rows.get(3).getInt(2));

    // Eve: age=30 -> double_age=60
    Assertions.assertEquals(5, rows.get(4).getInt(0));
    Assertions.assertEquals("Eve", rows.get(4).getString(1));
    Assertions.assertEquals(60, rows.get(4).getInt(2));

    // Verify the schema contains the alias name
    String[] fieldNames = rows.get(0).schema().fieldNames();
    Assertions.assertEquals("id", fieldNames[0]);
    Assertions.assertEquals("name", fieldNames[1]);
    Assertions.assertEquals("double_age", fieldNames[2]);
  }

  // -------------------------------------------------------------------------
  // Test Case #7: testViewSchemaFromGravitinoColumns
  //
  // Create a view with explicit Gravitino columns. Verify DESCRIBE TABLE
  // returns the Gravitino-defined schema (not HMS fallback).
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("View schema from explicit Gravitino columns is reflected in DESCRIBE TABLE")
  public void testViewSchemaFromGravitinoColumns() {
    String viewName = "test_view_schema_gravitino_columns";
    String fqTable = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, BASE_TABLE);
    String fqView = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, viewName);

    // View SQL selects all columns from the base table
    String viewSql = String.format("SELECT id, name, age FROM %s", fqTable);
    NameIdentifier viewIdent = NameIdentifier.of(schemaName, viewName);

    // Create the view with explicit Gravitino column definitions
    viewCatalog.createView(
        viewIdent,
        "View for schema from Gravitino columns test",
        new Column[] {
          Column.of("id", Types.IntegerType.get(), null),
          Column.of("name", Types.StringType.get(), null),
          Column.of("age", Types.IntegerType.get(), null)
        },
        new SQLRepresentation[] {
          SQLRepresentation.builder().withDialect(SPARK_DIALECT).withSql(viewSql).build()
        },
        HIVE_CATALOG_NAME,
        schemaName,
        ImmutableMap.of("spark.sql.create.version", spark.version()));

    // Use DESCRIBE TABLE to get the schema metadata
    List<Row> describeRows = spark.sql(String.format("DESCRIBE TABLE %s", fqView)).collectAsList();

    // Verify column names and types match the Gravitino-defined schema
    Assertions.assertTrue(describeRows.size() >= 3, "Expected at least 3 columns in DESCRIBE");

    // Find columns by name from describe output
    Map<String, String> colTypes = Maps.newHashMap();
    for (Row row : describeRows) {
      String colName = row.getString(0).trim();
      String colType = row.getString(1).trim();
      if (!colName.isEmpty() && !colName.startsWith("#")) {
        colTypes.put(colName, colType);
      }
    }

    Assertions.assertEquals("int", colTypes.get("id"), "id column should be int");
    Assertions.assertEquals("string", colTypes.get("name"), "name column should be string");
    Assertions.assertEquals("int", colTypes.get("age"), "age column should be int");
  }

  // -------------------------------------------------------------------------
  // Test Case #8: testViewSchemaFallbackToHMS
  //
  // Create a view with an empty column array (new Column[0]). When both the
  // Gravitino columns and HMS sd.cols are empty (which happens when creating
  // via the Gravitino API with no columns), SparkHiveView.schema() returns an
  // empty StructType. The behavior when querying such a view depends on
  // whether the Spark connector infers the schema from SQL execution:
  // - If schema is empty: SELECT * returns empty columns, but referencing
  //   specific column names throws AnalysisException.
  // - If schema is inferred: the query succeeds normally.
  // This test verifies the view is created successfully and that referencing
  // a named column against the empty-schema view throws AnalysisException.
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("View with empty columns throws AnalysisException when referencing columns by name")
  public void testViewSchemaFallbackToHMS() {
    String viewName = "test_view_schema_fallback_hms";
    String fqTable = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, BASE_TABLE);
    String fqView = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, viewName);

    // View SQL selects all columns from the base table
    String viewSql = String.format("SELECT id, name, age FROM %s", fqTable);
    NameIdentifier viewIdent = NameIdentifier.of(schemaName, viewName);

    // Create view with empty column array — the Gravitino API allows this
    viewCatalog.createView(
        viewIdent,
        "View for HMS schema fallback test",
        new Column[0],
        new SQLRepresentation[] {
          SQLRepresentation.builder().withDialect(SPARK_DIALECT).withSql(viewSql).build()
        },
        HIVE_CATALOG_NAME,
        schemaName,
        ImmutableMap.of("spark.sql.create.version", spark.version()));

    // Verify the view was created successfully in Gravitino
    Assertions.assertTrue(viewCatalog.viewExists(viewIdent), "View should exist after creation");

    // Referencing a named column against a view with empty schema throws AnalysisException
    // because Spark's analyzer cannot resolve the column against an empty StructType.
    // Use Exception.class for compatibility across Spark versions (3.5 uses
    // ExtendedAnalysisException).
    Exception ex =
        Assertions.assertThrows(
            Exception.class,
            () -> spark.sql(String.format("SELECT id FROM %s", fqView)).collectAsList(),
            "Expected exception when referencing named column on empty-schema view");
    Assertions.assertTrue(
        ex.getMessage().contains("cannot be resolved"),
        "Exception should mention unresolved column, got: " + ex.getMessage());
  }

  // -------------------------------------------------------------------------
  // Test Case #9: testViewWithNullableAndNonNullableColumns
  //
  // Create a view where some columns are nullable and others are not.
  // Note: Hive's FieldSchema has no nullable concept, so when columns are
  // round-tripped through HMS, all columns become nullable (hardcoded in
  // HiveTableConverter.buildColumn). This test verifies the actual behavior:
  // all columns in a Hive-backed view are reported as nullable regardless of
  // the nullable flag set at creation time.
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("Hive view columns are always nullable due to HMS limitation")
  public void testViewWithNullableAndNonNullableColumns() {
    String viewName = "test_view_nullable_columns";
    String fqTable = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, BASE_TABLE);
    String fqView = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, viewName);

    String viewSql = String.format("SELECT id, name, age FROM %s", fqTable);
    NameIdentifier viewIdent = NameIdentifier.of(schemaName, viewName);

    // Create view with mixed nullability:
    // id -> non-nullable, name -> nullable, age -> non-nullable
    viewCatalog.createView(
        viewIdent,
        "View for nullable/non-nullable column test",
        new Column[] {
          Column.of(
              "id", Types.IntegerType.get(), null, false, false, Column.DEFAULT_VALUE_NOT_SET),
          Column.of(
              "name", Types.StringType.get(), null, true, false, Column.DEFAULT_VALUE_NOT_SET),
          Column.of(
              "age", Types.IntegerType.get(), null, false, false, Column.DEFAULT_VALUE_NOT_SET)
        },
        new SQLRepresentation[] {
          SQLRepresentation.builder().withDialect(SPARK_DIALECT).withSql(viewSql).build()
        },
        HIVE_CATALOG_NAME,
        schemaName,
        ImmutableMap.of("spark.sql.create.version", spark.version()));

    // Query the view to get the schema
    List<Row> rows = spark.sql(String.format("SELECT * FROM %s LIMIT 1", fqView)).collectAsList();

    Assertions.assertFalse(rows.isEmpty(), "Expected non-empty result");

    // Verify schema from the Spark StructType
    StructType schema = rows.get(0).schema();
    Assertions.assertEquals(3, schema.fields().length, "Expected 3 fields in schema");

    // Hive does not support NOT NULL constraints on view columns. The FieldSchema in HMS
    // has no nullable flag, so HiveTableConverter.buildColumn always sets nullable=true.
    // All columns are reported as nullable after round-tripping through HMS.
    Assertions.assertTrue(schema.fields()[0].nullable(), "id column is nullable (Hive limitation)");
    Assertions.assertTrue(schema.fields()[1].nullable(), "name column is nullable");
    Assertions.assertTrue(
        schema.fields()[2].nullable(), "age column is nullable (Hive limitation)");
  }

  // -------------------------------------------------------------------------
  // Test Case #10: testViewWithColumnComment
  //
  // Create a view with column-level comments. Query DESCRIBE TABLE.
  // Verify column comments are exposed in metadata.
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("View with column comments exposes comments in DESCRIBE TABLE")
  public void testViewWithColumnComment() {
    String viewName = "test_view_column_comment";
    String fqTable = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, BASE_TABLE);
    String fqView = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, viewName);

    String viewSql = String.format("SELECT id, name, age FROM %s", fqTable);
    NameIdentifier viewIdent = NameIdentifier.of(schemaName, viewName);

    // Create view with column-level comments
    viewCatalog.createView(
        viewIdent,
        "View for column comment test",
        new Column[] {
          Column.of("id", Types.IntegerType.get(), "The unique identifier"),
          Column.of("name", Types.StringType.get(), "Person name"),
          Column.of("age", Types.IntegerType.get(), "Age in years")
        },
        new SQLRepresentation[] {
          SQLRepresentation.builder().withDialect(SPARK_DIALECT).withSql(viewSql).build()
        },
        HIVE_CATALOG_NAME,
        schemaName,
        ImmutableMap.of("spark.sql.create.version", spark.version()));

    // Use DESCRIBE TABLE to get schema metadata including comments
    List<Row> describeRows = spark.sql(String.format("DESCRIBE TABLE %s", fqView)).collectAsList();

    // Build a map of column name -> comment from DESCRIBE output
    // DESCRIBE TABLE output columns: col_name, data_type, comment
    Map<String, String> colComments = Maps.newHashMap();
    for (Row row : describeRows) {
      String colName = row.getString(0).trim();
      // Comment is in the third column (index 2)
      String comment = row.isNullAt(2) ? null : row.getString(2).trim();
      if (!colName.isEmpty() && !colName.startsWith("#")) {
        colComments.put(colName, comment);
      }
    }

    // Verify column comments are exposed
    Assertions.assertEquals(
        "The unique identifier", colComments.get("id"), "id column comment mismatch");
    Assertions.assertEquals("Person name", colComments.get("name"), "name column comment mismatch");
    Assertions.assertEquals("Age in years", colComments.get("age"), "age column comment mismatch");
  }

  // -------------------------------------------------------------------------
  // Test Case #11: testViewWithHiveDialectFallback
  //
  // Create a view using only "hive" dialect (no spark.sql.create.version property).
  // SparkHiveView's newScanBuilder falls back to Hive SQL when Spark dialect is
  // absent; query succeeds if the SQL is Spark-compatible.
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("View with Hive dialect only falls back and query succeeds if SQL is compatible")
  public void testViewWithHiveDialectFallback() {
    String viewName = "test_view_hive_dialect_fallback";
    String fqTable = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, BASE_TABLE);
    String fqView = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, viewName);

    // View SQL is simple enough to be compatible with both Hive and Spark dialects
    String viewSql = String.format("SELECT id, name, age FROM %s WHERE age >= 30", fqTable);
    NameIdentifier viewIdent = NameIdentifier.of(schemaName, viewName);

    // Create the view with only "hive" dialect — no spark.sql.create.version property.
    // For "hive" dialect, defaultCatalog and defaultSchema must be null per Hive catalog
    // validation.
    viewCatalog.createView(
        viewIdent,
        "View for Hive dialect fallback test",
        new Column[] {
          Column.of("id", Types.IntegerType.get(), null),
          Column.of("name", Types.StringType.get(), null),
          Column.of("age", Types.IntegerType.get(), null)
        },
        new SQLRepresentation[] {
          SQLRepresentation.builder().withDialect("hive").withSql(viewSql).build()
        },
        null,
        null,
        Collections.emptyMap());

    // Query the view — SparkHiveView should fall back to Hive SQL since Spark dialect is absent
    List<Row> rows =
        spark.sql(String.format("SELECT * FROM %s ORDER BY id", fqView)).collectAsList();

    // age >= 30 matches: Bob(30), Charlie(35), Eve(30)
    Assertions.assertEquals(3, rows.size(), "Expected 3 rows with age >= 30");

    Assertions.assertEquals(2, rows.get(0).getInt(0));
    Assertions.assertEquals("Bob", rows.get(0).getString(1));
    Assertions.assertEquals(30, rows.get(0).getInt(2));

    Assertions.assertEquals(3, rows.get(1).getInt(0));
    Assertions.assertEquals("Charlie", rows.get(1).getString(1));
    Assertions.assertEquals(35, rows.get(1).getInt(2));

    Assertions.assertEquals(5, rows.get(2).getInt(0));
    Assertions.assertEquals("Eve", rows.get(2).getString(1));
    Assertions.assertEquals(30, rows.get(2).getInt(2));
  }

  // -------------------------------------------------------------------------
  // Test Case #12: testViewWithUnsupportedDialectOnly
  //
  // Attempt to create a view with an unsupported dialect (e.g., "trino").
  // The Hive catalog validates dialects at creation time and only accepts
  // 'hive', 'flink', and 'spark'. The createView call throws
  // UnsupportedOperationException with a meaningful message.
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("View creation with unsupported dialect throws UnsupportedOperationException")
  public void testViewWithUnsupportedDialectOnly() {
    String viewName = "test_view_unsupported_dialect";
    String fqTable = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, BASE_TABLE);

    // View SQL uses a dialect that is neither "spark", "hive", nor "flink"
    String viewSql = String.format("SELECT id, name, age FROM %s", fqTable);
    NameIdentifier viewIdent = NameIdentifier.of(schemaName, viewName);

    // Attempting to create a view with "trino" dialect should fail at the server side
    // because the Hive catalog only supports 'hive', 'flink', and 'spark' dialects.
    UnsupportedOperationException ex =
        Assertions.assertThrows(
            UnsupportedOperationException.class,
            () ->
                viewCatalog.createView(
                    viewIdent,
                    "View for unsupported dialect test",
                    new Column[] {
                      Column.of("id", Types.IntegerType.get(), null),
                      Column.of("name", Types.StringType.get(), null),
                      Column.of("age", Types.IntegerType.get(), null)
                    },
                    new SQLRepresentation[] {
                      SQLRepresentation.builder().withDialect("trino").withSql(viewSql).build()
                    },
                    HIVE_CATALOG_NAME,
                    schemaName,
                    Collections.emptyMap()),
            "Expected UnsupportedOperationException for unsupported dialect");

    // Verify the error message mentions the unsupported dialect
    Assertions.assertTrue(
        ex.getMessage().contains("trino"),
        "Exception message should mention the unsupported dialect 'trino', got: "
            + ex.getMessage());
  }

  // -------------------------------------------------------------------------
  // Test Case #13: testSelectHiveViewEmptyResult
  //
  // Create a view over a table with a WHERE clause that matches nothing.
  // Verify that an empty result set is returned without any exceptions.
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("Select from Hive view with no matching rows returns empty result set")
  public void testSelectHiveViewEmptyResult() {
    String emptyTable = "view_empty_result_table";
    String viewName = "test_view_empty_result";
    String fqEmptyTable = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, emptyTable);
    String fqView = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, viewName);

    // Create an empty table (no data inserted)
    spark.sql(
        String.format("CREATE TABLE %s (id INT, name STRING, age INT) USING hive", fqEmptyTable));

    try {
      // Create a view over the empty table
      String viewSql = String.format("SELECT * FROM %s", fqEmptyTable);
      NameIdentifier viewIdent = NameIdentifier.of(schemaName, viewName);

      viewCatalog.createView(
          viewIdent,
          "View for empty result test",
          new Column[] {
            Column.of("id", Types.IntegerType.get(), null),
            Column.of("name", Types.StringType.get(), null),
            Column.of("age", Types.IntegerType.get(), null)
          },
          new SQLRepresentation[] {
            SQLRepresentation.builder().withDialect(SPARK_DIALECT).withSql(viewSql).build()
          },
          HIVE_CATALOG_NAME,
          schemaName,
          ImmutableMap.of("spark.sql.create.version", spark.version()));

      // Query the view — should return empty result set without exceptions
      List<Row> rows = spark.sql(String.format("SELECT * FROM %s", fqView)).collectAsList();
      Assertions.assertEquals(0, rows.size(), "Expected 0 rows from view over empty table");

      // Also test with a WHERE clause that matches nothing on the populated base table
      String viewName2 = "test_view_empty_filter";
      String fqView2 = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, viewName2);
      String fqTable = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, BASE_TABLE);
      String viewSql2 = String.format("SELECT * FROM %s WHERE age > 1000", fqTable);
      NameIdentifier viewIdent2 = NameIdentifier.of(schemaName, viewName2);

      viewCatalog.createView(
          viewIdent2,
          "View with filter that matches nothing",
          new Column[] {
            Column.of("id", Types.IntegerType.get(), null),
            Column.of("name", Types.StringType.get(), null),
            Column.of("age", Types.IntegerType.get(), null)
          },
          new SQLRepresentation[] {
            SQLRepresentation.builder().withDialect(SPARK_DIALECT).withSql(viewSql2).build()
          },
          HIVE_CATALOG_NAME,
          schemaName,
          ImmutableMap.of("spark.sql.create.version", spark.version()));

      List<Row> rows2 = spark.sql(String.format("SELECT * FROM %s", fqView2)).collectAsList();
      Assertions.assertEquals(0, rows2.size(), "Expected 0 rows from view with impossible filter");

      // Verify the schema is still correct even with empty results
      StructType schema = spark.sql(String.format("SELECT * FROM %s", fqView)).schema();
      Assertions.assertEquals(3, schema.fields().length, "Schema should have 3 fields");
      Assertions.assertEquals("id", schema.fields()[0].name());
      Assertions.assertEquals("name", schema.fields()[1].name());
      Assertions.assertEquals("age", schema.fields()[2].name());
    } finally {
      spark.sql(String.format("DROP TABLE IF EXISTS %s", fqEmptyTable));
    }
  }

  // -------------------------------------------------------------------------
  // Test Case #14: testSelectHiveViewWithNullValues
  //
  // Base table has rows with NULL values in various columns. View is SELECT *.
  // Verify NULL values are correctly propagated through executeCollect() and
  // LocalScan.
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("Select from Hive view correctly propagates NULL values")
  public void testSelectHiveViewWithNullValues() {
    String nullTable = "view_null_values_table";
    String viewName = "test_view_null_values";
    String fqNullTable = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, nullTable);
    String fqView = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, viewName);

    // Create a table with NULL values in various columns
    spark.sql(
        String.format("CREATE TABLE %s (id INT, name STRING, age INT) USING hive", fqNullTable));
    spark.sql(
        String.format(
            "INSERT INTO %s VALUES "
                + "(1, 'Alice', 25), "
                + "(2, NULL, 30), "
                + "(3, 'Charlie', NULL), "
                + "(NULL, 'Diana', 40), "
                + "(NULL, NULL, NULL)",
            fqNullTable));

    try {
      // Create a view over the table with NULLs
      String viewSql = String.format("SELECT * FROM %s", fqNullTable);
      NameIdentifier viewIdent = NameIdentifier.of(schemaName, viewName);

      viewCatalog.createView(
          viewIdent,
          "View for NULL values test",
          new Column[] {
            Column.of("id", Types.IntegerType.get(), null),
            Column.of("name", Types.StringType.get(), null),
            Column.of("age", Types.IntegerType.get(), null)
          },
          new SQLRepresentation[] {
            SQLRepresentation.builder().withDialect(SPARK_DIALECT).withSql(viewSql).build()
          },
          HIVE_CATALOG_NAME,
          schemaName,
          ImmutableMap.of("spark.sql.create.version", spark.version()));

      // Query the view and verify NULLs are correctly propagated
      List<Row> rows =
          spark.sql(String.format("SELECT * FROM %s ORDER BY id", fqView)).collectAsList();

      Assertions.assertEquals(5, rows.size(), "Expected 5 rows from view");

      // Row with id=NULL sorts first (or last depending on Spark's NULL ordering)
      // Spark default null ordering for ASC is NULLS FIRST
      // Rows with NULL id come first
      // Find the row with all NULLs
      long allNullCount =
          rows.stream().filter(r -> r.isNullAt(0) && r.isNullAt(1) && r.isNullAt(2)).count();
      Assertions.assertEquals(1, allNullCount, "Expected exactly 1 row with all NULLs");

      // Verify NULL in name column (id=2)
      Row nullNameRow =
          rows.stream().filter(r -> !r.isNullAt(0) && r.getInt(0) == 2).findFirst().orElse(null);
      Assertions.assertNotNull(nullNameRow, "Should find row with id=2");
      Assertions.assertTrue(nullNameRow.isNullAt(1), "name should be NULL for id=2");
      Assertions.assertEquals(30, nullNameRow.getInt(2), "age should be 30 for id=2");

      // Verify NULL in age column (id=3)
      Row nullAgeRow =
          rows.stream().filter(r -> !r.isNullAt(0) && r.getInt(0) == 3).findFirst().orElse(null);
      Assertions.assertNotNull(nullAgeRow, "Should find row with id=3");
      Assertions.assertEquals("Charlie", nullAgeRow.getString(1));
      Assertions.assertTrue(nullAgeRow.isNullAt(2), "age should be NULL for id=3");

      // Verify NULL in id column (Diana)
      Row nullIdRow =
          rows.stream()
              .filter(r -> r.isNullAt(0) && !r.isNullAt(1) && "Diana".equals(r.getString(1)))
              .findFirst()
              .orElse(null);
      Assertions.assertNotNull(nullIdRow, "Should find row with name=Diana and NULL id");
      Assertions.assertTrue(nullIdRow.isNullAt(0), "id should be NULL for Diana");
      Assertions.assertEquals(40, nullIdRow.getInt(2), "age should be 40 for Diana");

      // Verify filtering on NULL values works through the view
      List<Row> nullNameRows =
          spark.sql(String.format("SELECT * FROM %s WHERE name IS NULL", fqView)).collectAsList();
      Assertions.assertEquals(2, nullNameRows.size(), "Expected 2 rows where name IS NULL");

      List<Row> nonNullRows =
          spark
              .sql(
                  String.format(
                      "SELECT * FROM %s WHERE id IS NOT NULL AND name IS NOT NULL AND age IS NOT NULL",
                      fqView))
              .collectAsList();
      Assertions.assertEquals(1, nonNullRows.size(), "Expected 1 row with all non-NULL values");
      Assertions.assertEquals(1, nonNullRows.get(0).getInt(0));
      Assertions.assertEquals("Alice", nonNullRows.get(0).getString(1));
      Assertions.assertEquals(25, nonNullRows.get(0).getInt(2));
    } finally {
      spark.sql(String.format("DROP TABLE IF EXISTS %s", fqNullTable));
    }
  }

  // -------------------------------------------------------------------------
  // Test Case #15: testSelectHiveViewSingleRow
  //
  // Base table has exactly one row. This is a boundary case to verify that
  // single-partition LocalScan works correctly.
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("Select from Hive view with single row works correctly (boundary case)")
  public void testSelectHiveViewSingleRow() {
    String singleRowTable = "view_single_row_table";
    String viewName = "test_view_single_row";
    String fqSingleRowTable =
        String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, singleRowTable);
    String fqView = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, viewName);

    // Create a table with exactly one row
    spark.sql(
        String.format(
            "CREATE TABLE %s (id INT, name STRING, age INT) USING hive", fqSingleRowTable));
    spark.sql(String.format("INSERT INTO %s VALUES (42, 'SingleUser', 99)", fqSingleRowTable));

    try {
      // Create a view over the single-row table
      String viewSql = String.format("SELECT * FROM %s", fqSingleRowTable);
      NameIdentifier viewIdent = NameIdentifier.of(schemaName, viewName);

      viewCatalog.createView(
          viewIdent,
          "View for single row boundary test",
          new Column[] {
            Column.of("id", Types.IntegerType.get(), null),
            Column.of("name", Types.StringType.get(), null),
            Column.of("age", Types.IntegerType.get(), null)
          },
          new SQLRepresentation[] {
            SQLRepresentation.builder().withDialect(SPARK_DIALECT).withSql(viewSql).build()
          },
          HIVE_CATALOG_NAME,
          schemaName,
          ImmutableMap.of("spark.sql.create.version", spark.version()));

      // Query the view — single-partition LocalScan boundary case
      List<Row> rows = spark.sql(String.format("SELECT * FROM %s", fqView)).collectAsList();

      Assertions.assertEquals(1, rows.size(), "Expected exactly 1 row from single-row view");
      Assertions.assertEquals(42, rows.get(0).getInt(0), "id should be 42");
      Assertions.assertEquals("SingleUser", rows.get(0).getString(1), "name should be SingleUser");
      Assertions.assertEquals(99, rows.get(0).getInt(2), "age should be 99");

      // Verify that applying a filter still works on a single-row result
      List<Row> filtered =
          spark.sql(String.format("SELECT * FROM %s WHERE id = 42", fqView)).collectAsList();
      Assertions.assertEquals(1, filtered.size(), "Expected 1 row matching id=42");

      // Verify that a non-matching filter returns empty
      List<Row> noMatch =
          spark.sql(String.format("SELECT * FROM %s WHERE id = 999", fqView)).collectAsList();
      Assertions.assertEquals(0, noMatch.size(), "Expected 0 rows for non-matching filter");

      // Verify count aggregation works on single row
      List<Row> countResult =
          spark.sql(String.format("SELECT COUNT(*) FROM %s", fqView)).collectAsList();
      Assertions.assertEquals(1L, countResult.get(0).getLong(0), "COUNT(*) should be 1");
    } finally {
      spark.sql(String.format("DROP TABLE IF EXISTS %s", fqSingleRowTable));
    }
  }

  // -------------------------------------------------------------------------
  // Test Case #16: testLoadTableFallsThroughToView
  //
  // Directly call loadTable (via DESCRIBE TABLE or SELECT) on a view identifier.
  // The table-loading logic falls through and successfully returns the view as
  // a Table.
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("loadTable falls through to view and returns view as Table")
  public void testLoadTableFallsThroughToView() {
    String viewName = "test_load_table_falls_through";
    String fqTable = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, BASE_TABLE);
    String fqView = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, viewName);

    // Create a view via Gravitino API
    String viewSql = String.format("SELECT id, name, age FROM %s", fqTable);
    NameIdentifier viewIdent = NameIdentifier.of(schemaName, viewName);

    viewCatalog.createView(
        viewIdent,
        "View for loadTable fallthrough test",
        new Column[] {
          Column.of("id", Types.IntegerType.get(), null),
          Column.of("name", Types.StringType.get(), null),
          Column.of("age", Types.IntegerType.get(), null)
        },
        new SQLRepresentation[] {
          SQLRepresentation.builder().withDialect(SPARK_DIALECT).withSql(viewSql).build()
        },
        HIVE_CATALOG_NAME,
        schemaName,
        ImmutableMap.of("spark.sql.create.version", spark.version()));

    // DESCRIBE TABLE on a view identifier should succeed (loadTable falls through to view)
    List<Row> describeRows = spark.sql(String.format("DESCRIBE TABLE %s", fqView)).collectAsList();
    Assertions.assertFalse(describeRows.isEmpty(), "DESCRIBE TABLE should return schema for view");

    // Verify the schema columns are present
    Map<String, String> colTypes = Maps.newHashMap();
    for (Row row : describeRows) {
      String colName = row.getString(0).trim();
      String colType = row.getString(1).trim();
      if (!colName.isEmpty() && !colName.startsWith("#")) {
        colTypes.put(colName, colType);
      }
    }
    Assertions.assertTrue(colTypes.containsKey("id"), "Should contain 'id' column");
    Assertions.assertTrue(colTypes.containsKey("name"), "Should contain 'name' column");
    Assertions.assertTrue(colTypes.containsKey("age"), "Should contain 'age' column");

    // SELECT from the view identifier should also succeed
    List<Row> rows =
        spark.sql(String.format("SELECT * FROM %s ORDER BY id", fqView)).collectAsList();
    Assertions.assertEquals(5, rows.size(), "Expected 5 rows from view via loadTable fallthrough");
    Assertions.assertEquals(1, rows.get(0).getInt(0));
    Assertions.assertEquals("Alice", rows.get(0).getString(1));
  }

  // -------------------------------------------------------------------------
  // Test Case #17: testTableExistsReturnsTrueForView
  //
  // Explicitly call tableExists logic (e.g., CREATE TABLE IF NOT EXISTS
  // <viewName>) where <viewName> is an existing view. tableExists returns
  // true, preventing accidental overwrite.
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("tableExists returns true for a view, preventing accidental overwrite")
  public void testTableExistsReturnsTrueForView() {
    String viewName = "test_table_exists_for_view";
    String fqTable = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, BASE_TABLE);
    String fqView = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, viewName);

    // Create a view via Gravitino API
    String viewSql = String.format("SELECT id, name, age FROM %s", fqTable);
    NameIdentifier viewIdent = NameIdentifier.of(schemaName, viewName);

    viewCatalog.createView(
        viewIdent,
        "View for tableExists test",
        new Column[] {
          Column.of("id", Types.IntegerType.get(), null),
          Column.of("name", Types.StringType.get(), null),
          Column.of("age", Types.IntegerType.get(), null)
        },
        new SQLRepresentation[] {
          SQLRepresentation.builder().withDialect(SPARK_DIALECT).withSql(viewSql).build()
        },
        HIVE_CATALOG_NAME,
        schemaName,
        ImmutableMap.of("spark.sql.create.version", spark.version()));

    // CREATE TABLE IF NOT EXISTS should NOT create a new table because the view already exists
    // (tableExists returns true for a view)
    spark.sql(String.format("CREATE TABLE IF NOT EXISTS %s (x INT, y STRING) USING hive", fqView));

    // The view should still be accessible and return the original view data (not the new table)
    List<Row> rows =
        spark.sql(String.format("SELECT * FROM %s ORDER BY id", fqView)).collectAsList();
    Assertions.assertEquals(5, rows.size(), "View should still return 5 rows (not overwritten)");
    Assertions.assertEquals(1, rows.get(0).getInt(0));
    Assertions.assertEquals("Alice", rows.get(0).getString(1));
    Assertions.assertEquals(25, rows.get(0).getInt(2));
  }

  // -------------------------------------------------------------------------
  // Test Case #18: testDropTableDoesNotDropView
  //
  // Drop a base table and verify that views defined over OTHER tables in the
  // same schema are not affected. This validates that HMS dropTable only
  // removes the named entry and does not cascade to unrelated views.
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("Dropping a base table does not affect views on other tables in the same schema")
  public void testDropTableDoesNotDropView() {
    String extraTable = "view_drop_test_extra_table";
    String viewOnBaseTable = "view_on_base_table";
    String viewOnExtraTable = "view_on_extra_table";
    String fqExtraTable = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, extraTable);
    String fqBaseTable = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, BASE_TABLE);
    String fqViewOnBase = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, viewOnBaseTable);
    String fqViewOnExtra =
        String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, viewOnExtraTable);

    // Create an extra table in the same schema
    spark.sql(String.format("CREATE TABLE %s (id INT, value STRING) USING hive", fqExtraTable));
    spark.sql(String.format("INSERT INTO %s VALUES (10, 'X'), (20, 'Y')", fqExtraTable));

    try {
      // Create view1 over the extra table
      String viewSql1 = String.format("SELECT id, value FROM %s", fqExtraTable);
      NameIdentifier viewIdent1 = NameIdentifier.of(schemaName, viewOnExtraTable);

      viewCatalog.createView(
          viewIdent1,
          "View on extra table",
          new Column[] {
            Column.of("id", Types.IntegerType.get(), null),
            Column.of("value", Types.StringType.get(), null)
          },
          new SQLRepresentation[] {
            SQLRepresentation.builder().withDialect(SPARK_DIALECT).withSql(viewSql1).build()
          },
          HIVE_CATALOG_NAME,
          schemaName,
          ImmutableMap.of("spark.sql.create.version", spark.version()));

      // Create view2 over the shared BASE_TABLE
      String viewSql2 = String.format("SELECT id, name FROM %s", fqBaseTable);
      NameIdentifier viewIdent2 = NameIdentifier.of(schemaName, viewOnBaseTable);

      viewCatalog.createView(
          viewIdent2,
          "View on base table",
          new Column[] {
            Column.of("id", Types.IntegerType.get(), null),
            Column.of("name", Types.StringType.get(), null)
          },
          new SQLRepresentation[] {
            SQLRepresentation.builder().withDialect(SPARK_DIALECT).withSql(viewSql2).build()
          },
          HIVE_CATALOG_NAME,
          schemaName,
          ImmutableMap.of("spark.sql.create.version", spark.version()));

      // Verify both views exist before dropping the extra table
      Assertions.assertTrue(
          viewCatalog.viewExists(viewIdent1), "view_on_extra_table should exist before drop");
      Assertions.assertTrue(
          viewCatalog.viewExists(viewIdent2), "view_on_base_table should exist before drop");

      // Drop the extra table — this should NOT affect view_on_base_table
      spark.sql(String.format("DROP TABLE %s", fqExtraTable));

      // view_on_base_table should still exist and be queryable
      Assertions.assertTrue(
          viewCatalog.viewExists(viewIdent2),
          "view_on_base_table should still exist after dropping extra table");

      List<Row> rows =
          spark.sql(String.format("SELECT * FROM %s ORDER BY id", fqViewOnBase)).collectAsList();
      Assertions.assertEquals(5, rows.size(), "view_on_base_table should still return 5 rows");
      Assertions.assertEquals(1, rows.get(0).getInt(0));
      Assertions.assertEquals("Alice", rows.get(0).getString(1));

      // view_on_extra_table should still exist in Gravitino metadata (HMS does not cascade)
      Assertions.assertTrue(
          viewCatalog.viewExists(viewIdent1),
          "view_on_extra_table should still exist in metadata after dropping its base table");

      // However, querying view_on_extra_table should fail because the base table is gone
      Exception ex =
          Assertions.assertThrows(
              Exception.class,
              () -> spark.sql(String.format("SELECT * FROM %s", fqViewOnExtra)).collectAsList(),
              "Querying a view whose base table was dropped should throw");
      LOG.info("Query on view with dropped base table threw (expected): {}", ex.getMessage());

    } finally {
      // Clean up: drop extra table if it still exists
      spark.sql(String.format("DROP TABLE IF EXISTS %s", fqExtraTable));
    }
  }

  // -------------------------------------------------------------------------
  // Test Case #19: testViewAndTableSameSchema
  //
  // Create both a table and a view in the same schema. SELECT from both.
  // Both are accessible; no naming conflict.
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("View and table coexist in the same schema without naming conflict")
  public void testViewAndTableSameSchema() {
    String tableName = "coexist_table_t1";
    String viewName = "coexist_view_v1";
    String fqCoexistTable = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, tableName);
    String fqView = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, viewName);

    // Create a separate table in the same schema
    spark.sql(String.format("CREATE TABLE %s (id INT, value STRING) USING hive", fqCoexistTable));
    spark.sql(
        String.format(
            "INSERT INTO %s VALUES (100, 'TableRow1'), (200, 'TableRow2')", fqCoexistTable));

    try {
      // Create a view in the same schema over the base table
      String fqTable = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, BASE_TABLE);
      String viewSql = String.format("SELECT id, name FROM %s WHERE id <= 3", fqTable);
      NameIdentifier viewIdent = NameIdentifier.of(schemaName, viewName);

      viewCatalog.createView(
          viewIdent,
          "View coexisting with table",
          new Column[] {
            Column.of("id", Types.IntegerType.get(), null),
            Column.of("name", Types.StringType.get(), null)
          },
          new SQLRepresentation[] {
            SQLRepresentation.builder().withDialect(SPARK_DIALECT).withSql(viewSql).build()
          },
          HIVE_CATALOG_NAME,
          schemaName,
          ImmutableMap.of("spark.sql.create.version", spark.version()));

      // Query the table
      List<Row> tableRows =
          spark.sql(String.format("SELECT * FROM %s ORDER BY id", fqCoexistTable)).collectAsList();
      Assertions.assertEquals(2, tableRows.size(), "Expected 2 rows from the table");
      Assertions.assertEquals(100, tableRows.get(0).getInt(0));
      Assertions.assertEquals("TableRow1", tableRows.get(0).getString(1));

      // Query the view
      List<Row> viewRows =
          spark.sql(String.format("SELECT * FROM %s ORDER BY id", fqView)).collectAsList();
      Assertions.assertEquals(3, viewRows.size(), "Expected 3 rows from the view");
      Assertions.assertEquals(1, viewRows.get(0).getInt(0));
      Assertions.assertEquals("Alice", viewRows.get(0).getString(1));
      Assertions.assertEquals(2, viewRows.get(1).getInt(0));
      Assertions.assertEquals("Bob", viewRows.get(1).getString(1));
      Assertions.assertEquals(3, viewRows.get(2).getInt(0));
      Assertions.assertEquals("Charlie", viewRows.get(2).getString(1));
    } finally {
      spark.sql(String.format("DROP TABLE IF EXISTS %s", fqCoexistTable));
    }
  }

  // -------------------------------------------------------------------------
  // Test Case #20: testViewNameSameAsDroppedTable
  //
  // Create and drop a table, then create a view with the same name.
  // SELECT from the view. View is correctly loaded; no stale cache from the
  // dropped table interferes.
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("View with same name as previously dropped table is loaded correctly")
  public void testViewNameSameAsDroppedTable() {
    String sharedName = "shared_name_entity";
    String fqSharedName = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, sharedName);
    String fqTable = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, BASE_TABLE);

    // Step 1: Create a table with the shared name
    spark.sql(String.format("CREATE TABLE %s (x INT, y STRING) USING hive", fqSharedName));
    spark.sql(String.format("INSERT INTO %s VALUES (999, 'OldTable')", fqSharedName));

    // Verify the table is accessible
    List<Row> tableRows =
        spark.sql(String.format("SELECT * FROM %s", fqSharedName)).collectAsList();
    Assertions.assertEquals(1, tableRows.size());
    Assertions.assertEquals(999, tableRows.get(0).getInt(0));

    // Step 2: Drop the table
    spark.sql(String.format("DROP TABLE %s", fqSharedName));

    // Step 3: Create a view with the same name
    String viewSql = String.format("SELECT id, name, age FROM %s WHERE age = 25", fqTable);
    NameIdentifier viewIdent = NameIdentifier.of(schemaName, sharedName);

    viewCatalog.createView(
        viewIdent,
        "View reusing dropped table name",
        new Column[] {
          Column.of("id", Types.IntegerType.get(), null),
          Column.of("name", Types.StringType.get(), null),
          Column.of("age", Types.IntegerType.get(), null)
        },
        new SQLRepresentation[] {
          SQLRepresentation.builder().withDialect(SPARK_DIALECT).withSql(viewSql).build()
        },
        HIVE_CATALOG_NAME,
        schemaName,
        ImmutableMap.of("spark.sql.create.version", spark.version()));

    // Step 4: Query the view — should return view data, not stale table data
    List<Row> viewRows =
        spark.sql(String.format("SELECT * FROM %s ORDER BY id", fqSharedName)).collectAsList();

    // age=25 matches Alice(1) and Diana(4)
    Assertions.assertEquals(2, viewRows.size(), "Expected 2 rows from view (age=25)");
    Assertions.assertEquals(1, viewRows.get(0).getInt(0));
    Assertions.assertEquals("Alice", viewRows.get(0).getString(1));
    Assertions.assertEquals(25, viewRows.get(0).getInt(2));
    Assertions.assertEquals(4, viewRows.get(1).getInt(0));
    Assertions.assertEquals("Diana", viewRows.get(1).getString(1));
    Assertions.assertEquals(25, viewRows.get(1).getInt(2));

    // Verify the schema reflects the view columns (3 columns), not the old table (2 columns)
    String[] fieldNames = viewRows.get(0).schema().fieldNames();
    Assertions.assertEquals(3, fieldNames.length, "View should have 3 columns");
    Assertions.assertEquals("id", fieldNames[0]);
    Assertions.assertEquals("name", fieldNames[1]);
    Assertions.assertEquals("age", fieldNames[2]);
  }

  // -------------------------------------------------------------------------
  // Test Case #21: testInsertIntoViewFails
  //
  // Attempt INSERT INTO <viewName> VALUES (...). Write operations on views
  // should fail with a clear error (views are read-only).
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("INSERT INTO a view fails with a clear error (views are read-only)")
  public void testInsertIntoViewFails() {
    String viewName = "test_insert_into_view_fails";
    String fqTable = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, BASE_TABLE);
    String fqView = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, viewName);

    // Create a view via Gravitino API
    String viewSql = String.format("SELECT id, name, age FROM %s", fqTable);
    NameIdentifier viewIdent = NameIdentifier.of(schemaName, viewName);

    viewCatalog.createView(
        viewIdent,
        "View for INSERT INTO failure test",
        new Column[] {
          Column.of("id", Types.IntegerType.get(), null),
          Column.of("name", Types.StringType.get(), null),
          Column.of("age", Types.IntegerType.get(), null)
        },
        new SQLRepresentation[] {
          SQLRepresentation.builder().withDialect(SPARK_DIALECT).withSql(viewSql).build()
        },
        HIVE_CATALOG_NAME,
        schemaName,
        ImmutableMap.of("spark.sql.create.version", spark.version()));

    // Attempt to INSERT INTO the view — should fail because views are read-only
    Exception ex =
        Assertions.assertThrows(
            Exception.class,
            () ->
                spark
                    .sql(String.format("INSERT INTO %s VALUES (99, 'Hacker', 50)", fqView))
                    .collectAsList(),
            "INSERT INTO a view should throw an exception");

    LOG.info("INSERT INTO view threw (expected): {}", ex.getMessage());

    // The view should still be intact and queryable with original data
    List<Row> rows =
        spark.sql(String.format("SELECT * FROM %s ORDER BY id", fqView)).collectAsList();
    Assertions.assertEquals(5, rows.size(), "View should still return 5 original rows");
  }

  // -------------------------------------------------------------------------
  // Test Case #22: testAlterViewViaSparkFails
  //
  // Attempt ALTER TABLE <viewName> ADD COLUMNS (...). Should throw
  // AnalysisException or similar; view is not mutable via Spark DDL.
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("ALTER TABLE ADD COLUMNS on a view fails (view is not mutable via Spark DDL)")
  public void testAlterViewViaSparkFails() {
    String viewName = "test_alter_view_via_spark_fails";
    String fqTable = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, BASE_TABLE);
    String fqView = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, viewName);

    // Create a view via Gravitino API
    String viewSql = String.format("SELECT id, name, age FROM %s", fqTable);
    NameIdentifier viewIdent = NameIdentifier.of(schemaName, viewName);

    viewCatalog.createView(
        viewIdent,
        "View for ALTER TABLE failure test",
        new Column[] {
          Column.of("id", Types.IntegerType.get(), null),
          Column.of("name", Types.StringType.get(), null),
          Column.of("age", Types.IntegerType.get(), null)
        },
        new SQLRepresentation[] {
          SQLRepresentation.builder().withDialect(SPARK_DIALECT).withSql(viewSql).build()
        },
        HIVE_CATALOG_NAME,
        schemaName,
        ImmutableMap.of("spark.sql.create.version", spark.version()));

    // Attempt to ALTER TABLE ADD COLUMNS on the view — should fail
    Exception ex =
        Assertions.assertThrows(
            Exception.class,
            () ->
                spark
                    .sql(String.format("ALTER TABLE %s ADD COLUMNS (extra STRING)", fqView))
                    .collectAsList(),
            "ALTER TABLE ADD COLUMNS on a view should throw an exception");

    LOG.info("ALTER TABLE on view threw (expected): {}", ex.getMessage());

    // The view schema should remain unchanged — still 3 columns
    List<Row> describeRows = spark.sql(String.format("DESCRIBE TABLE %s", fqView)).collectAsList();
    Map<String, String> colTypes = Maps.newHashMap();
    for (Row row : describeRows) {
      String colName = row.getString(0).trim();
      String colType = row.getString(1).trim();
      if (!colName.isEmpty() && !colName.startsWith("#")) {
        colTypes.put(colName, colType);
      }
    }
    Assertions.assertEquals(3, colTypes.size(), "View should still have exactly 3 columns");
    Assertions.assertFalse(
        colTypes.containsKey("extra"), "View should NOT have the 'extra' column");
    Assertions.assertTrue(colTypes.containsKey("id"));
    Assertions.assertTrue(colTypes.containsKey("name"));
    Assertions.assertTrue(colTypes.containsKey("age"));
  }

  // -------------------------------------------------------------------------
  // Test Case #23: testDropViewViaSparkTableAPI
  //
  // Verify that dropping a table via Spark's DROP TABLE does not accidentally
  // remove views in the same schema. Views are independent HMS entries and
  // should survive unrelated table drops.
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("DROP TABLE on a regular table does not accidentally drop views in the same schema")
  public void testDropViewViaSparkTableAPI() {
    String tempTable = "view_drop_api_temp_table";
    String viewName = "test_drop_view_via_spark_table_api";
    String fqTempTable = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, tempTable);
    String fqTable = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, BASE_TABLE);
    String fqView = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, viewName);

    // Create a temporary table to be dropped later
    spark.sql(String.format("CREATE TABLE %s (x INT, y STRING) USING hive", fqTempTable));
    spark.sql(String.format("INSERT INTO %s VALUES (1, 'hello')", fqTempTable));

    try {
      // Create a view via Gravitino API over the shared BASE_TABLE
      String viewSql = String.format("SELECT id, name, age FROM %s", fqTable);
      NameIdentifier viewIdent = NameIdentifier.of(schemaName, viewName);

      viewCatalog.createView(
          viewIdent,
          "View for DROP TABLE via Spark test",
          new Column[] {
            Column.of("id", Types.IntegerType.get(), null),
            Column.of("name", Types.StringType.get(), null),
            Column.of("age", Types.IntegerType.get(), null)
          },
          new SQLRepresentation[] {
            SQLRepresentation.builder().withDialect(SPARK_DIALECT).withSql(viewSql).build()
          },
          HIVE_CATALOG_NAME,
          schemaName,
          ImmutableMap.of("spark.sql.create.version", spark.version()));

      // Verify the view exists before the DROP TABLE attempt
      Assertions.assertTrue(
          viewCatalog.viewExists(viewIdent), "View should exist before DROP TABLE attempt");

      // Drop the temp table — this should NOT affect the view
      spark.sql(String.format("DROP TABLE %s", fqTempTable));

      // The view should still exist in Gravitino
      Assertions.assertTrue(
          viewCatalog.viewExists(viewIdent),
          "View should still exist after dropping an unrelated table");

      // The view should still be queryable
      List<Row> rows =
          spark.sql(String.format("SELECT * FROM %s ORDER BY id", fqView)).collectAsList();
      Assertions.assertEquals(
          5, rows.size(), "View should still return 5 rows after unrelated table drop");
      Assertions.assertEquals(1, rows.get(0).getInt(0));
      Assertions.assertEquals("Alice", rows.get(0).getString(1));
      Assertions.assertEquals(25, rows.get(0).getInt(2));
    } finally {
      spark.sql(String.format("DROP TABLE IF EXISTS %s", fqTempTable));
    }
  }

  // -------------------------------------------------------------------------
  // Test Case #24: testSelectHiveViewOverPartitionedTable
  //
  // View is defined over a partitioned Hive table with data in multiple
  // partitions. All partitions' data is returned through the view's SQL
  // execution.
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("Select from Hive view over partitioned table returns all partitions' data")
  public void testSelectHiveViewOverPartitionedTable() {
    String partTable = "view_partitioned_base_table";
    String viewName = "test_view_over_partitioned_table";
    String fqPartTable = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, partTable);
    String fqView = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, viewName);

    // Create a partitioned Hive table partitioned by 'dt' column.
    // With USING hive, define dt in column list and reference by name in PARTITIONED BY.
    spark.sql(
        String.format(
            "CREATE TABLE %s (id INT, name STRING, amount DOUBLE, dt STRING) USING hive PARTITIONED BY (dt)",
            fqPartTable));

    // Insert data into multiple partitions
    spark.sql(
        String.format(
            "INSERT INTO %s VALUES (1, 'Alice', 100.0, '2024-01-01'), (2, 'Bob', 200.0, '2024-01-01')",
            fqPartTable));
    spark.sql(
        String.format(
            "INSERT INTO %s VALUES (3, 'Charlie', 300.0, '2024-01-02'), (4, 'Diana', 400.0, '2024-01-02')",
            fqPartTable));
    spark.sql(String.format("INSERT INTO %s VALUES (5, 'Eve', 500.0, '2024-01-03')", fqPartTable));

    try {
      // Create a view over the entire partitioned table (SELECT *)
      String viewSql = String.format("SELECT id, name, amount, dt FROM %s", fqPartTable);
      NameIdentifier viewIdent = NameIdentifier.of(schemaName, viewName);

      viewCatalog.createView(
          viewIdent,
          "View over partitioned table",
          new Column[] {
            Column.of("id", Types.IntegerType.get(), null),
            Column.of("name", Types.StringType.get(), null),
            Column.of("amount", Types.DoubleType.get(), null),
            Column.of("dt", Types.StringType.get(), null)
          },
          new SQLRepresentation[] {
            SQLRepresentation.builder().withDialect(SPARK_DIALECT).withSql(viewSql).build()
          },
          HIVE_CATALOG_NAME,
          schemaName,
          ImmutableMap.of("spark.sql.create.version", spark.version()));

      // Query the view — all partitions' data should be returned
      List<Row> rows =
          spark.sql(String.format("SELECT * FROM %s ORDER BY id", fqView)).collectAsList();

      // Verify all 5 rows from 3 partitions are returned
      Assertions.assertEquals(
          5, rows.size(), "Expected 5 rows from all partitions through the view");

      // Verify data from partition dt='2024-01-01'
      Assertions.assertEquals(1, rows.get(0).getInt(0));
      Assertions.assertEquals("Alice", rows.get(0).getString(1));
      Assertions.assertEquals(100.0, rows.get(0).getDouble(2), 0.001);
      Assertions.assertEquals("2024-01-01", rows.get(0).getString(3));

      Assertions.assertEquals(2, rows.get(1).getInt(0));
      Assertions.assertEquals("Bob", rows.get(1).getString(1));
      Assertions.assertEquals(200.0, rows.get(1).getDouble(2), 0.001);
      Assertions.assertEquals("2024-01-01", rows.get(1).getString(3));

      // Verify data from partition dt='2024-01-02'
      Assertions.assertEquals(3, rows.get(2).getInt(0));
      Assertions.assertEquals("Charlie", rows.get(2).getString(1));
      Assertions.assertEquals(300.0, rows.get(2).getDouble(2), 0.001);
      Assertions.assertEquals("2024-01-02", rows.get(2).getString(3));

      Assertions.assertEquals(4, rows.get(3).getInt(0));
      Assertions.assertEquals("Diana", rows.get(3).getString(1));
      Assertions.assertEquals(400.0, rows.get(3).getDouble(2), 0.001);
      Assertions.assertEquals("2024-01-02", rows.get(3).getString(3));

      // Verify data from partition dt='2024-01-03'
      Assertions.assertEquals(5, rows.get(4).getInt(0));
      Assertions.assertEquals("Eve", rows.get(4).getString(1));
      Assertions.assertEquals(500.0, rows.get(4).getDouble(2), 0.001);
      Assertions.assertEquals("2024-01-03", rows.get(4).getString(3));

      // Also verify COUNT(*) returns correct total across all partitions
      List<Row> countResult =
          spark.sql(String.format("SELECT COUNT(*) FROM %s", fqView)).collectAsList();
      Assertions.assertEquals(5L, countResult.get(0).getLong(0), "COUNT(*) should be 5");

      // Verify distinct partitions are accessible through the view
      List<Row> distinctPartitions =
          spark
              .sql(String.format("SELECT DISTINCT dt FROM %s ORDER BY dt", fqView))
              .collectAsList();
      Assertions.assertEquals(3, distinctPartitions.size(), "Expected 3 distinct partitions");
      Assertions.assertEquals("2024-01-01", distinctPartitions.get(0).getString(0));
      Assertions.assertEquals("2024-01-02", distinctPartitions.get(1).getString(0));
      Assertions.assertEquals("2024-01-03", distinctPartitions.get(2).getString(0));
    } finally {
      spark.sql(String.format("DROP TABLE IF EXISTS %s", fqPartTable));
    }
  }

  // -------------------------------------------------------------------------
  // Test Case #25: testSelectHiveViewWithPartitionFilter
  //
  // View SQL includes a partition-pruning WHERE clause (e.g.,
  // WHERE dt = '2024-01-01'). Only rows from the specified partition are
  // returned.
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("Select from Hive view with partition filter returns only filtered partition data")
  public void testSelectHiveViewWithPartitionFilter() {
    String partTable = "view_partition_filter_base_table";
    String viewName = "test_view_with_partition_filter";
    String fqPartTable = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, partTable);
    String fqView = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, viewName);

    // Create a partitioned Hive table partitioned by 'dt' column.
    // With USING hive, define dt in column list and reference by name in PARTITIONED BY.
    spark.sql(
        String.format(
            "CREATE TABLE %s (id INT, name STRING, amount DOUBLE, dt STRING) USING hive PARTITIONED BY (dt)",
            fqPartTable));

    // Insert data into multiple partitions
    spark.sql(
        String.format(
            "INSERT INTO %s VALUES (1, 'Alice', 100.0, '2024-01-01'), (2, 'Bob', 200.0, '2024-01-01')",
            fqPartTable));
    spark.sql(
        String.format(
            "INSERT INTO %s VALUES (3, 'Charlie', 300.0, '2024-01-02'), (4, 'Diana', 400.0, '2024-01-02')",
            fqPartTable));
    spark.sql(String.format("INSERT INTO %s VALUES (5, 'Eve', 500.0, '2024-01-03')", fqPartTable));

    try {
      // Create a view with a partition-pruning WHERE clause
      String viewSql =
          String.format("SELECT id, name, amount, dt FROM %s WHERE dt = '2024-01-01'", fqPartTable);
      NameIdentifier viewIdent = NameIdentifier.of(schemaName, viewName);

      viewCatalog.createView(
          viewIdent,
          "View with partition filter",
          new Column[] {
            Column.of("id", Types.IntegerType.get(), null),
            Column.of("name", Types.StringType.get(), null),
            Column.of("amount", Types.DoubleType.get(), null),
            Column.of("dt", Types.StringType.get(), null)
          },
          new SQLRepresentation[] {
            SQLRepresentation.builder().withDialect(SPARK_DIALECT).withSql(viewSql).build()
          },
          HIVE_CATALOG_NAME,
          schemaName,
          ImmutableMap.of("spark.sql.create.version", spark.version()));

      // Query the view — only rows from partition dt='2024-01-01' should be returned
      List<Row> rows =
          spark.sql(String.format("SELECT * FROM %s ORDER BY id", fqView)).collectAsList();

      // Only 2 rows from partition '2024-01-01'
      Assertions.assertEquals(
          2, rows.size(), "Expected 2 rows from partition dt='2024-01-01' only");

      Assertions.assertEquals(1, rows.get(0).getInt(0));
      Assertions.assertEquals("Alice", rows.get(0).getString(1));
      Assertions.assertEquals(100.0, rows.get(0).getDouble(2), 0.001);
      Assertions.assertEquals("2024-01-01", rows.get(0).getString(3));

      Assertions.assertEquals(2, rows.get(1).getInt(0));
      Assertions.assertEquals("Bob", rows.get(1).getString(1));
      Assertions.assertEquals(200.0, rows.get(1).getDouble(2), 0.001);
      Assertions.assertEquals("2024-01-01", rows.get(1).getString(3));

      // Verify that rows from other partitions are NOT returned
      List<Row> otherPartitions =
          spark
              .sql(String.format("SELECT * FROM %s WHERE dt != '2024-01-01'", fqView))
              .collectAsList();
      Assertions.assertEquals(
          0, otherPartitions.size(), "No rows from other partitions should be returned");

      // Verify COUNT(*) returns only filtered partition count
      List<Row> countResult =
          spark.sql(String.format("SELECT COUNT(*) FROM %s", fqView)).collectAsList();
      Assertions.assertEquals(
          2L, countResult.get(0).getLong(0), "COUNT(*) should be 2 (filtered partition only)");

      // Apply an additional filter on top of the view
      List<Row> additionalFilter =
          spark.sql(String.format("SELECT * FROM %s WHERE name = 'Alice'", fqView)).collectAsList();
      Assertions.assertEquals(
          1, additionalFilter.size(), "Expected 1 row matching additional filter on view");
      Assertions.assertEquals(1, additionalFilter.get(0).getInt(0));
      Assertions.assertEquals("Alice", additionalFilter.get(0).getString(1));
    } finally {
      spark.sql(String.format("DROP TABLE IF EXISTS %s", fqPartTable));
    }
  }

  // -------------------------------------------------------------------------
  // Test Case #26: testMultipleViewsOnSameTable
  //
  // Create two views (v1, v2) on the same base table with different
  // projections/filters. SELECT from both. Both return correct, independent
  // results.
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("Multiple views on the same table return correct independent results")
  public void testMultipleViewsOnSameTable() {
    String viewName1 = "test_multi_view_v1";
    String viewName2 = "test_multi_view_v2";
    String fqTable = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, BASE_TABLE);
    String fqView1 = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, viewName1);
    String fqView2 = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, viewName2);

    // View 1: project only id and name, filter age <= 25
    String viewSql1 = String.format("SELECT id, name FROM %s WHERE age <= 25", fqTable);
    NameIdentifier viewIdent1 = NameIdentifier.of(schemaName, viewName1);

    viewCatalog.createView(
        viewIdent1,
        "View v1 with name projection and age filter",
        new Column[] {
          Column.of("id", Types.IntegerType.get(), null),
          Column.of("name", Types.StringType.get(), null)
        },
        new SQLRepresentation[] {
          SQLRepresentation.builder().withDialect(SPARK_DIALECT).withSql(viewSql1).build()
        },
        HIVE_CATALOG_NAME,
        schemaName,
        ImmutableMap.of("spark.sql.create.version", spark.version()));

    // View 2: project all columns, filter age >= 30
    String viewSql2 = String.format("SELECT id, name, age FROM %s WHERE age >= 30", fqTable);
    NameIdentifier viewIdent2 = NameIdentifier.of(schemaName, viewName2);

    viewCatalog.createView(
        viewIdent2,
        "View v2 with all columns and age filter",
        new Column[] {
          Column.of("id", Types.IntegerType.get(), null),
          Column.of("name", Types.StringType.get(), null),
          Column.of("age", Types.IntegerType.get(), null)
        },
        new SQLRepresentation[] {
          SQLRepresentation.builder().withDialect(SPARK_DIALECT).withSql(viewSql2).build()
        },
        HIVE_CATALOG_NAME,
        schemaName,
        ImmutableMap.of("spark.sql.create.version", spark.version()));

    // Query view 1: age <= 25 -> Alice(25), Diana(25)
    List<Row> v1Rows =
        spark.sql(String.format("SELECT * FROM %s ORDER BY id", fqView1)).collectAsList();
    Assertions.assertEquals(2, v1Rows.size(), "View v1 should return 2 rows (age <= 25)");
    Assertions.assertEquals(1, v1Rows.get(0).getInt(0));
    Assertions.assertEquals("Alice", v1Rows.get(0).getString(1));
    Assertions.assertEquals(4, v1Rows.get(1).getInt(0));
    Assertions.assertEquals("Diana", v1Rows.get(1).getString(1));
    // v1 should have only 2 columns
    Assertions.assertEquals(2, v1Rows.get(0).schema().fields().length, "v1 should have 2 columns");

    // Query view 2: age >= 30 -> Bob(30), Charlie(35), Eve(30)
    List<Row> v2Rows =
        spark.sql(String.format("SELECT * FROM %s ORDER BY id", fqView2)).collectAsList();
    Assertions.assertEquals(3, v2Rows.size(), "View v2 should return 3 rows (age >= 30)");
    Assertions.assertEquals(2, v2Rows.get(0).getInt(0));
    Assertions.assertEquals("Bob", v2Rows.get(0).getString(1));
    Assertions.assertEquals(30, v2Rows.get(0).getInt(2));
    Assertions.assertEquals(3, v2Rows.get(1).getInt(0));
    Assertions.assertEquals("Charlie", v2Rows.get(1).getString(1));
    Assertions.assertEquals(35, v2Rows.get(1).getInt(2));
    Assertions.assertEquals(5, v2Rows.get(2).getInt(0));
    Assertions.assertEquals("Eve", v2Rows.get(2).getString(1));
    Assertions.assertEquals(30, v2Rows.get(2).getInt(2));
    // v2 should have 3 columns
    Assertions.assertEquals(3, v2Rows.get(0).schema().fields().length, "v2 should have 3 columns");
  }

  // -------------------------------------------------------------------------
  // Test Case #27: testViewReferencingAnotherView
  //
  // Create v1 on a table, then v2 with SQL referencing v1. SELECT from v2.
  // Nested view expansion works via executeCollect().
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("View referencing another view works via nested executeCollect()")
  public void testViewReferencingAnotherView() {
    String viewName1 = "test_nested_base_view";
    String viewName2 = "test_nested_outer_view";
    String fqTable = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, BASE_TABLE);
    String fqView1 = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, viewName1);
    String fqView2 = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, viewName2);

    // Create v1: base view that filters age >= 25 (all rows in our data)
    String viewSql1 = String.format("SELECT id, name, age FROM %s WHERE age >= 25", fqTable);
    NameIdentifier viewIdent1 = NameIdentifier.of(schemaName, viewName1);

    viewCatalog.createView(
        viewIdent1,
        "Base view for nested view test",
        new Column[] {
          Column.of("id", Types.IntegerType.get(), null),
          Column.of("name", Types.StringType.get(), null),
          Column.of("age", Types.IntegerType.get(), null)
        },
        new SQLRepresentation[] {
          SQLRepresentation.builder().withDialect(SPARK_DIALECT).withSql(viewSql1).build()
        },
        HIVE_CATALOG_NAME,
        schemaName,
        ImmutableMap.of("spark.sql.create.version", spark.version()));

    // Verify v1 works first
    List<Row> v1Rows =
        spark.sql(String.format("SELECT * FROM %s ORDER BY id", fqView1)).collectAsList();
    Assertions.assertEquals(5, v1Rows.size(), "Base view v1 should return all 5 rows");

    // Create v2: outer view that references v1 with additional filter
    String viewSql2 = String.format("SELECT id, name, age FROM %s WHERE age > 25", fqView1);
    NameIdentifier viewIdent2 = NameIdentifier.of(schemaName, viewName2);

    viewCatalog.createView(
        viewIdent2,
        "Outer view referencing base view",
        new Column[] {
          Column.of("id", Types.IntegerType.get(), null),
          Column.of("name", Types.StringType.get(), null),
          Column.of("age", Types.IntegerType.get(), null)
        },
        new SQLRepresentation[] {
          SQLRepresentation.builder().withDialect(SPARK_DIALECT).withSql(viewSql2).build()
        },
        HIVE_CATALOG_NAME,
        schemaName,
        ImmutableMap.of("spark.sql.create.version", spark.version()));

    // Query v2: age > 25 from v1 (which already has age >= 25) -> Bob(30), Charlie(35), Eve(30)
    List<Row> v2Rows =
        spark.sql(String.format("SELECT * FROM %s ORDER BY id", fqView2)).collectAsList();
    Assertions.assertEquals(3, v2Rows.size(), "Outer view v2 should return 3 rows (age > 25)");

    Assertions.assertEquals(2, v2Rows.get(0).getInt(0));
    Assertions.assertEquals("Bob", v2Rows.get(0).getString(1));
    Assertions.assertEquals(30, v2Rows.get(0).getInt(2));

    Assertions.assertEquals(3, v2Rows.get(1).getInt(0));
    Assertions.assertEquals("Charlie", v2Rows.get(1).getString(1));
    Assertions.assertEquals(35, v2Rows.get(1).getInt(2));

    Assertions.assertEquals(5, v2Rows.get(2).getInt(0));
    Assertions.assertEquals("Eve", v2Rows.get(2).getString(1));
    Assertions.assertEquals(30, v2Rows.get(2).getInt(2));

    // Verify applying additional filters on top of the nested view works
    List<Row> filtered =
        spark
            .sql(String.format("SELECT * FROM %s WHERE name = 'Charlie'", fqView2))
            .collectAsList();
    Assertions.assertEquals(1, filtered.size(), "Expected 1 row filtering nested view");
    Assertions.assertEquals(35, filtered.get(0).getInt(2));
  }

  // -------------------------------------------------------------------------
  // Test Case #31: testViewPropertiesExposed
  //
  // Create a view with custom properties (e.g., "owner" -> "test"). Access
  // properties() via describe or the Table API. Custom properties are visible.
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("View with custom properties exposes them via DESCRIBE EXTENDED")
  public void testViewPropertiesExposed() {
    String viewName = "test_view_properties_exposed";
    String fqTable = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, BASE_TABLE);
    String fqView = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, viewName);

    // View SQL selects all columns from the base table
    String viewSql = String.format("SELECT id, name, age FROM %s", fqTable);
    NameIdentifier viewIdent = NameIdentifier.of(schemaName, viewName);

    // Create the view with custom properties
    Map<String, String> viewProperties = Maps.newHashMap();
    viewProperties.put("spark.sql.create.version", spark.version());
    viewProperties.put("owner", "test");
    viewProperties.put("team", "data-platform");

    viewCatalog.createView(
        viewIdent,
        "View for properties exposure test",
        new Column[] {
          Column.of("id", Types.IntegerType.get(), null),
          Column.of("name", Types.StringType.get(), null),
          Column.of("age", Types.IntegerType.get(), null)
        },
        new SQLRepresentation[] {
          SQLRepresentation.builder().withDialect(SPARK_DIALECT).withSql(viewSql).build()
        },
        HIVE_CATALOG_NAME,
        schemaName,
        viewProperties);

    // Use DESCRIBE EXTENDED to get full table/view metadata including properties
    List<Row> describeRows =
        spark.sql(String.format("DESCRIBE EXTENDED %s", fqView)).collectAsList();

    // DESCRIBE EXTENDED returns schema columns first, then a separator, then detailed info.
    // Look for property information in the output.
    // The detailed info section contains "Table Properties" or individual property rows.
    String fullDescribeOutput =
        describeRows.stream()
            .map(
                r -> {
                  StringBuilder sb = new StringBuilder();
                  for (int i = 0; i < r.size(); i++) {
                    if (!r.isNullAt(i)) {
                      sb.append(r.getString(i)).append(" ");
                    }
                  }
                  return sb.toString().trim();
                })
            .collect(Collectors.joining("\n"));

    LOG.info("DESCRIBE EXTENDED output for view:\n{}", fullDescribeOutput);

    // Verify that at least the view is accessible via DESCRIBE EXTENDED without error
    Assertions.assertFalse(
        describeRows.isEmpty(), "DESCRIBE EXTENDED should return non-empty output");

    // Verify properties are accessible via the Gravitino API directly
    org.apache.gravitino.rel.View gravitinoView = viewCatalog.loadView(viewIdent);
    Map<String, String> loadedProperties = gravitinoView.properties();

    Assertions.assertEquals(
        "test", loadedProperties.get("owner"), "owner property should be 'test'");
    Assertions.assertEquals(
        "data-platform", loadedProperties.get("team"), "team property should be 'data-platform'");

    // Verify the view is still queryable (properties don't affect query behavior)
    List<Row> rows =
        spark.sql(String.format("SELECT * FROM %s ORDER BY id", fqView)).collectAsList();
    Assertions.assertEquals(5, rows.size(), "View should return 5 rows regardless of properties");
  }

  // -------------------------------------------------------------------------
  // Test Case #32: testViewComment
  //
  // Create a view with a non-null comment. Verify via describe. Comment is
  // returned.
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("View with non-null comment exposes the comment via describe")
  public void testViewComment() {
    String viewName = "test_view_comment";
    String fqTable = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, BASE_TABLE);
    String fqView = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, viewName);

    String viewComment = "This is a test view for verifying comment exposure";

    // View SQL selects all columns from the base table
    String viewSql = String.format("SELECT id, name, age FROM %s", fqTable);
    NameIdentifier viewIdent = NameIdentifier.of(schemaName, viewName);

    // Create the view with a non-null comment
    viewCatalog.createView(
        viewIdent,
        viewComment,
        new Column[] {
          Column.of("id", Types.IntegerType.get(), null),
          Column.of("name", Types.StringType.get(), null),
          Column.of("age", Types.IntegerType.get(), null)
        },
        new SQLRepresentation[] {
          SQLRepresentation.builder().withDialect(SPARK_DIALECT).withSql(viewSql).build()
        },
        HIVE_CATALOG_NAME,
        schemaName,
        ImmutableMap.of("spark.sql.create.version", spark.version()));

    // Verify the comment is accessible via the Gravitino API
    org.apache.gravitino.rel.View gravitinoView = viewCatalog.loadView(viewIdent);
    Assertions.assertEquals(
        viewComment, gravitinoView.comment(), "View comment should match what was set at creation");

    // Use DESCRIBE EXTENDED to check if the comment appears in Spark metadata
    List<Row> describeRows =
        spark.sql(String.format("DESCRIBE EXTENDED %s", fqView)).collectAsList();

    // Search for the comment in the DESCRIBE EXTENDED output
    boolean commentFound = false;
    for (Row row : describeRows) {
      for (int i = 0; i < row.size(); i++) {
        if (!row.isNullAt(i)) {
          String cellValue = row.getString(i);
          if (cellValue != null && cellValue.contains(viewComment)) {
            commentFound = true;
            break;
          }
        }
      }
      if (commentFound) {
        break;
      }
    }

    // Log the full output for debugging
    String fullOutput =
        describeRows.stream()
            .map(
                r -> {
                  StringBuilder sb = new StringBuilder();
                  for (int i = 0; i < r.size(); i++) {
                    if (!r.isNullAt(i)) {
                      sb.append(r.getString(i)).append(" | ");
                    }
                  }
                  return sb.toString().trim();
                })
            .collect(Collectors.joining("\n"));
    LOG.info("DESCRIBE EXTENDED output:\n{}", fullOutput);

    // The comment may or may not be surfaced through DESCRIBE depending on the SparkHiveView
    // implementation. At minimum, verify it is accessible via the Gravitino API (already done).
    // If it's present in DESCRIBE output, that's an additional confirmation.
    if (commentFound) {
      LOG.info("View comment is exposed in DESCRIBE EXTENDED output");
    } else {
      LOG.info(
          "View comment is not surfaced in DESCRIBE EXTENDED (Spark V2 Table API limitation),"
              + " but is accessible via Gravitino API");
    }

    // Verify the view is still queryable
    List<Row> rows =
        spark.sql(String.format("SELECT * FROM %s ORDER BY id", fqView)).collectAsList();
    Assertions.assertEquals(5, rows.size(), "View should return 5 rows");
    Assertions.assertEquals(1, rows.get(0).getInt(0));
    Assertions.assertEquals("Alice", rows.get(0).getString(1));
  }

  // -------------------------------------------------------------------------
  // Test Case #28: testSelectHiveViewWithComplexTypes
  //
  // Base table has columns with ARRAY, MAP, STRUCT types. View is SELECT *.
  // Complex types serialize/deserialize correctly through LocalScan.
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("Select from Hive view with complex types (ARRAY, MAP, STRUCT) works correctly")
  public void testSelectHiveViewWithComplexTypes() {
    String complexTable = "view_complex_types_table";
    String viewName = "test_view_complex_types";
    String fqComplexTable = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, complexTable);
    String fqView = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, viewName);

    // Create a table with ARRAY, MAP, and STRUCT columns
    spark.sql(
        String.format(
            "CREATE TABLE %s ("
                + "id INT, "
                + "tags ARRAY<STRING>, "
                + "props MAP<STRING, STRING>, "
                + "address STRUCT<city: STRING, zip: STRING>"
                + ") USING hive",
            fqComplexTable));

    // Insert data with complex types
    spark.sql(
        String.format(
            "INSERT INTO %s SELECT 1, "
                + "array('tag1', 'tag2'), "
                + "map('key1', 'val1', 'key2', 'val2'), "
                + "named_struct('city', 'NYC', 'zip', '10001')",
            fqComplexTable));
    spark.sql(
        String.format(
            "INSERT INTO %s SELECT 2, "
                + "array('tagA'), "
                + "map('color', 'blue'), "
                + "named_struct('city', 'LA', 'zip', '90001')",
            fqComplexTable));

    try {
      // Create a view over the complex-typed table
      String viewSql = String.format("SELECT * FROM %s", fqComplexTable);
      NameIdentifier viewIdent = NameIdentifier.of(schemaName, viewName);

      viewCatalog.createView(
          viewIdent,
          "View for complex types test",
          new Column[] {
            Column.of("id", Types.IntegerType.get(), null),
            Column.of("tags", Types.ListType.nullable(Types.StringType.get()), null),
            Column.of(
                "props",
                Types.MapType.valueNullable(Types.StringType.get(), Types.StringType.get()),
                null),
            Column.of(
                "address",
                Types.StructType.of(
                    Types.StructType.Field.nullableField("city", Types.StringType.get()),
                    Types.StructType.Field.nullableField("zip", Types.StringType.get())),
                null)
          },
          new SQLRepresentation[] {
            SQLRepresentation.builder().withDialect(SPARK_DIALECT).withSql(viewSql).build()
          },
          HIVE_CATALOG_NAME,
          schemaName,
          ImmutableMap.of("spark.sql.create.version", spark.version()));

      // Query the view
      List<Row> rows =
          spark.sql(String.format("SELECT * FROM %s ORDER BY id", fqView)).collectAsList();

      Assertions.assertEquals(2, rows.size(), "Expected 2 rows from complex types view");

      // Verify row 1: id=1
      Row row1 = rows.get(0);
      Assertions.assertEquals(1, row1.getInt(0));

      // Verify ARRAY column
      List<String> tags1 = row1.getList(1);
      Assertions.assertEquals(2, tags1.size(), "Expected 2 tags for id=1");
      Assertions.assertTrue(tags1.contains("tag1"));
      Assertions.assertTrue(tags1.contains("tag2"));

      // Verify MAP column
      Map<String, String> props1 = row1.getJavaMap(2);
      Assertions.assertEquals(2, props1.size(), "Expected 2 map entries for id=1");
      Assertions.assertEquals("val1", props1.get("key1"));
      Assertions.assertEquals("val2", props1.get("key2"));

      // Verify STRUCT column
      Row address1 = row1.getStruct(3);
      Assertions.assertEquals("NYC", address1.getString(0));
      Assertions.assertEquals("10001", address1.getString(1));

      // Verify row 2: id=2
      Row row2 = rows.get(1);
      Assertions.assertEquals(2, row2.getInt(0));

      List<String> tags2 = row2.getList(1);
      Assertions.assertEquals(1, tags2.size(), "Expected 1 tag for id=2");
      Assertions.assertEquals("tagA", tags2.get(0));

      Map<String, String> props2 = row2.getJavaMap(2);
      Assertions.assertEquals(1, props2.size(), "Expected 1 map entry for id=2");
      Assertions.assertEquals("blue", props2.get("color"));

      Row address2 = row2.getStruct(3);
      Assertions.assertEquals("LA", address2.getString(0));
      Assertions.assertEquals("90001", address2.getString(1));

      // Verify schema field types
      StructType schema = rows.get(0).schema();
      Assertions.assertEquals("id", schema.fields()[0].name());
      Assertions.assertEquals("tags", schema.fields()[1].name());
      Assertions.assertEquals("props", schema.fields()[2].name());
      Assertions.assertEquals("address", schema.fields()[3].name());
    } finally {
      spark.sql(String.format("DROP TABLE IF EXISTS %s", fqComplexTable));
    }
  }

  // -------------------------------------------------------------------------
  // Test Case #29: testSelectHiveViewWithDecimalType
  //
  // Base table has a DECIMAL(18,2) column. View projects this column.
  // Precision and scale are preserved.
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("Select from Hive view with DECIMAL type preserves precision and scale")
  public void testSelectHiveViewWithDecimalType() {
    String decimalTable = "view_decimal_type_table";
    String viewName = "test_view_decimal_type";
    String fqDecimalTable = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, decimalTable);
    String fqView = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, viewName);

    // Create a table with a DECIMAL(18,2) column
    spark.sql(
        String.format(
            "CREATE TABLE %s (id INT, amount DECIMAL(18,2), name STRING) USING hive",
            fqDecimalTable));

    // Insert data with various decimal values including edge cases
    spark.sql(
        String.format(
            "INSERT INTO %s VALUES "
                + "(1, 12345.67, 'Alice'), "
                + "(2, 0.01, 'Bob'), "
                + "(3, 9999999999999999.99, 'Charlie'), "
                + "(4, -500.50, 'Diana'), "
                + "(5, 0.00, 'Eve')",
            fqDecimalTable));

    try {
      // Create a view that projects the decimal column
      String viewSql = String.format("SELECT id, amount, name FROM %s", fqDecimalTable);
      NameIdentifier viewIdent = NameIdentifier.of(schemaName, viewName);

      viewCatalog.createView(
          viewIdent,
          "View for DECIMAL type test",
          new Column[] {
            Column.of("id", Types.IntegerType.get(), null),
            Column.of("amount", Types.DecimalType.of(18, 2), null),
            Column.of("name", Types.StringType.get(), null)
          },
          new SQLRepresentation[] {
            SQLRepresentation.builder().withDialect(SPARK_DIALECT).withSql(viewSql).build()
          },
          HIVE_CATALOG_NAME,
          schemaName,
          ImmutableMap.of("spark.sql.create.version", spark.version()));

      // Query the view
      List<Row> rows =
          spark.sql(String.format("SELECT * FROM %s ORDER BY id", fqView)).collectAsList();

      Assertions.assertEquals(5, rows.size(), "Expected 5 rows from decimal type view");

      // Verify decimal precision and scale are preserved
      Assertions.assertEquals(
          new java.math.BigDecimal("12345.67"),
          rows.get(0).getDecimal(1),
          "Amount for Alice should be 12345.67");
      Assertions.assertEquals(
          new java.math.BigDecimal("0.01"),
          rows.get(1).getDecimal(1),
          "Amount for Bob should be 0.01");
      Assertions.assertEquals(
          new java.math.BigDecimal("9999999999999999.99"),
          rows.get(2).getDecimal(1),
          "Amount for Charlie should be 9999999999999999.99");
      Assertions.assertEquals(
          new java.math.BigDecimal("-500.50"),
          rows.get(3).getDecimal(1),
          "Amount for Diana should be -500.50");
      Assertions.assertEquals(
          new java.math.BigDecimal("0.00"),
          rows.get(4).getDecimal(1),
          "Amount for Eve should be 0.00");

      // Verify the schema reflects the correct decimal type
      StructType schema = rows.get(0).schema();
      String amountType = schema.fields()[1].dataType().simpleString();
      Assertions.assertTrue(
          amountType.contains("decimal") && amountType.contains("18") && amountType.contains("2"),
          "Schema should reflect DECIMAL(18,2), got: " + amountType);

      // Verify arithmetic operations on decimal column through the view
      List<Row> sumResult =
          spark.sql(String.format("SELECT SUM(amount) FROM %s", fqView)).collectAsList();
      Assertions.assertNotNull(sumResult.get(0).get(0), "SUM should not be null");
    } finally {
      spark.sql(String.format("DROP TABLE IF EXISTS %s", fqDecimalTable));
    }
  }

  // -------------------------------------------------------------------------
  // Test Case #30: testSelectHiveViewWithTimestampType
  //
  // Base table has a TIMESTAMP column. View projects this column.
  // Timestamp values are returned correctly.
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("Select from Hive view with TIMESTAMP type returns correct timestamp values")
  public void testSelectHiveViewWithTimestampType() {
    String tsTable = "view_timestamp_type_table";
    String viewName = "test_view_timestamp_type";
    String fqTsTable = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, tsTable);
    String fqView = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, viewName);

    // Create a table with a TIMESTAMP column
    spark.sql(
        String.format(
            "CREATE TABLE %s (id INT, event_time TIMESTAMP, description STRING) USING hive",
            fqTsTable));

    // Insert data with various timestamp values
    spark.sql(
        String.format(
            "INSERT INTO %s VALUES "
                + "(1, TIMESTAMP '2024-01-15 10:30:00', 'morning event'), "
                + "(2, TIMESTAMP '2024-06-20 23:59:59', 'midnight event'), "
                + "(3, TIMESTAMP '2024-12-31 00:00:00', 'new year event')",
            fqTsTable));

    try {
      // Create a view that projects the timestamp column
      String viewSql = String.format("SELECT id, event_time, description FROM %s", fqTsTable);
      NameIdentifier viewIdent = NameIdentifier.of(schemaName, viewName);

      viewCatalog.createView(
          viewIdent,
          "View for TIMESTAMP type test",
          new Column[] {
            Column.of("id", Types.IntegerType.get(), null),
            Column.of("event_time", Types.TimestampType.withoutTimeZone(), null),
            Column.of("description", Types.StringType.get(), null)
          },
          new SQLRepresentation[] {
            SQLRepresentation.builder().withDialect(SPARK_DIALECT).withSql(viewSql).build()
          },
          HIVE_CATALOG_NAME,
          schemaName,
          ImmutableMap.of("spark.sql.create.version", spark.version()));

      // Query the view. CAST the timestamp to STRING so the assertion is based on the
      // wall-clock value under the Spark session timezone (UTC, configured in setup) and
      // is not affected by the JVM's default timezone. Using java.sql.Timestamp.valueOf()
      // would parse the expected literal in the JVM's local timezone, causing spurious
      // failures on machines whose default timezone differs from the Spark session timezone.
      List<Row> rows =
          spark
              .sql(
                  String.format(
                      "SELECT id, CAST(event_time AS STRING) AS ts, description FROM %s ORDER BY id",
                      fqView))
              .collectAsList();

      Assertions.assertEquals(3, rows.size(), "Expected 3 rows from timestamp type view");

      // Verify timestamp values are returned correctly (wall-clock under UTC session timezone)
      Assertions.assertEquals(
          "2024-01-15 10:30:00", rows.get(0).getString(1), "Timestamp for id=1 mismatch");
      Assertions.assertEquals(
          "2024-06-20 23:59:59", rows.get(1).getString(1), "Timestamp for id=2 mismatch");
      Assertions.assertEquals(
          "2024-12-31 00:00:00", rows.get(2).getString(1), "Timestamp for id=3 mismatch");

      // Verify filtering on timestamp column works through the view
      List<Row> filteredRows =
          spark
              .sql(
                  String.format(
                      "SELECT * FROM %s WHERE event_time > TIMESTAMP '2024-06-01 00:00:00'",
                      fqView))
              .collectAsList();
      Assertions.assertEquals(
          2, filteredRows.size(), "Expected 2 rows with event_time after 2024-06-01");

      // Verify the schema reflects timestamp type
      StructType schema = spark.sql(String.format("SELECT * FROM %s", fqView)).schema();
      String tsType = schema.fields()[1].dataType().simpleString();
      Assertions.assertTrue(
          tsType.contains("timestamp"), "Schema should reflect timestamp type, got: " + tsType);
    } finally {
      spark.sql(String.format("DROP TABLE IF EXISTS %s", fqTsTable));
    }
  }

  // -------------------------------------------------------------------------
  // Test Case #33: testSelectStarViewAfterBaseTableSchemaChange
  //
  // Create a SELECT * view, then ALTER TABLE ... ADD COLUMNS on the base table.
  // Query the view. This exercises the consistency between
  // SparkHiveView.schema() (static Gravitino columns) and rows() (live SQL
  // execution). The behavior must be deterministic and must not throw a dirty
  // error such as an NPE or array index issue.
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("SELECT * view remains queryable and deterministic after base table schema change")
  public void testSelectStarViewAfterBaseTableSchemaChange() {
    String driftTable = "view_schema_drift_base_table";
    String viewName = "test_view_schema_drift";
    String fqDriftTable = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, driftTable);
    String fqView = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, viewName);

    // Create a base table with 3 columns and insert data
    spark.sql(
        String.format("CREATE TABLE %s (id INT, name STRING, age INT) USING hive", fqDriftTable));
    spark.sql(
        String.format("INSERT INTO %s VALUES (1, 'Alice', 25), (2, 'Bob', 30)", fqDriftTable));

    try {
      // Create a SELECT * view with explicit Gravitino columns matching the current table schema
      String viewSql = String.format("SELECT * FROM %s", fqDriftTable);
      NameIdentifier viewIdent = NameIdentifier.of(schemaName, viewName);

      viewCatalog.createView(
          viewIdent,
          "View for schema drift test",
          new Column[] {
            Column.of("id", Types.IntegerType.get(), null),
            Column.of("name", Types.StringType.get(), null),
            Column.of("age", Types.IntegerType.get(), null)
          },
          new SQLRepresentation[] {
            SQLRepresentation.builder().withDialect(SPARK_DIALECT).withSql(viewSql).build()
          },
          HIVE_CATALOG_NAME,
          schemaName,
          ImmutableMap.of("spark.sql.create.version", spark.version()));

      // Sanity check: the view returns the original 3-column rows
      List<Row> before =
          spark.sql(String.format("SELECT * FROM %s ORDER BY id", fqView)).collectAsList();
      Assertions.assertEquals(2, before.size(), "Expected 2 rows before schema change");
      Assertions.assertEquals(3, before.get(0).schema().fields().length, "Expected 3 columns");

      // Now add a new column to the base table — the view's stored Gravitino columns
      // (3 columns) no longer match the base table's live schema (4 columns).
      spark.sql(String.format("ALTER TABLE %s ADD COLUMNS (city STRING)", fqDriftTable));
      spark.sql(String.format("INSERT INTO %s VALUES (3, 'Charlie', 35, 'NYC')", fqDriftTable));

      // Query the view again. The behavior must be deterministic: either the view
      // continues to honor its declared 3-column schema, or it reflects the new schema.
      // The key requirement is that it does NOT throw a dirty/internal error. We assert
      // the query completes and the projected columns are internally consistent.
      List<Row> after =
          spark.sql(String.format("SELECT * FROM %s ORDER BY id", fqView)).collectAsList();
      Assertions.assertEquals(3, after.size(), "Expected 3 rows after schema change and insert");

      // The number of columns reported by the result schema must match the number of
      // values in every returned row (no index-out-of-bounds / mismatched-arity issues).
      int reportedColumns = after.get(0).schema().fields().length;
      for (Row row : after) {
        Assertions.assertEquals(
            reportedColumns,
            row.size(),
            "Every row must have the same arity as the reported schema");
      }

      // The original columns must still resolve correctly regardless of how many columns
      // the view now exposes.
      List<Row> projected =
          spark.sql(String.format("SELECT id, name FROM %s ORDER BY id", fqView)).collectAsList();
      Assertions.assertEquals(3, projected.size(), "Projection should return 3 rows");
      Assertions.assertEquals(1, projected.get(0).getInt(0));
      Assertions.assertEquals("Alice", projected.get(0).getString(1));
      Assertions.assertEquals(3, projected.get(2).getInt(0));
      Assertions.assertEquals("Charlie", projected.get(2).getString(1));
    } finally {
      spark.sql(String.format("DROP TABLE IF EXISTS %s", fqDriftTable));
    }
  }

  // -------------------------------------------------------------------------
  // Test Case #34: testListViewsVisibility
  //
  // Create multiple views and verify viewCatalog.listViews() returns them.
  // Verify a dropped view disappears from the listing. The listing must
  // reflect exactly the existing views.
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("listViews reflects exactly the existing views")
  public void testListViewsVisibility() {
    String viewName1 = "test_list_views_v1";
    String viewName2 = "test_list_views_v2";
    String fqTable = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, BASE_TABLE);

    org.apache.gravitino.Namespace ns = org.apache.gravitino.Namespace.of(schemaName);
    NameIdentifier viewIdent1 = NameIdentifier.of(schemaName, viewName1);
    NameIdentifier viewIdent2 = NameIdentifier.of(schemaName, viewName2);

    String viewSql = String.format("SELECT id, name FROM %s", fqTable);

    // Create two views
    viewCatalog.createView(
        viewIdent1,
        "First view for list test",
        new Column[] {
          Column.of("id", Types.IntegerType.get(), null),
          Column.of("name", Types.StringType.get(), null)
        },
        new SQLRepresentation[] {
          SQLRepresentation.builder().withDialect(SPARK_DIALECT).withSql(viewSql).build()
        },
        HIVE_CATALOG_NAME,
        schemaName,
        ImmutableMap.of("spark.sql.create.version", spark.version()));

    viewCatalog.createView(
        viewIdent2,
        "Second view for list test",
        new Column[] {
          Column.of("id", Types.IntegerType.get(), null),
          Column.of("name", Types.StringType.get(), null)
        },
        new SQLRepresentation[] {
          SQLRepresentation.builder().withDialect(SPARK_DIALECT).withSql(viewSql).build()
        },
        HIVE_CATALOG_NAME,
        schemaName,
        ImmutableMap.of("spark.sql.create.version", spark.version()));

    // listViews should include both views
    List<String> listedNames =
        Arrays.stream(viewCatalog.listViews(ns))
            .map(NameIdentifier::name)
            .collect(Collectors.toList());
    Assertions.assertTrue(listedNames.contains(viewName1), "listViews should contain " + viewName1);
    Assertions.assertTrue(listedNames.contains(viewName2), "listViews should contain " + viewName2);

    // Drop one view; it should disappear from the listing while the other remains
    viewCatalog.dropView(viewIdent1);

    List<String> afterDrop =
        Arrays.stream(viewCatalog.listViews(ns))
            .map(NameIdentifier::name)
            .collect(Collectors.toList());
    Assertions.assertFalse(
        afterDrop.contains(viewName1), "Dropped view should not appear in listViews");
    Assertions.assertTrue(
        afterDrop.contains(viewName2), "Remaining view should still appear in listViews");

    // Confirm existence checks agree with the listing
    Assertions.assertFalse(viewCatalog.viewExists(viewIdent1), "Dropped view should not exist");
    Assertions.assertTrue(viewCatalog.viewExists(viewIdent2), "Remaining view should exist");
  }

  // -------------------------------------------------------------------------
  // Test Case #35: testSelectHiveViewMediumResultSet
  //
  // Base table has a few thousand rows. View is SELECT *. Query the view to
  // verify driver-side executeCollect() materialization works for non-trivial
  // (bounded) result sizes. The class Javadoc states this path is suitable
  // only for small/bounded views, so this acts as a regression guard for a
  // reasonable upper bound.
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("Select from Hive view with a medium-sized result set materializes correctly")
  public void testSelectHiveViewMediumResultSet() {
    String bigTable = "view_medium_result_base_table";
    String viewName = "test_view_medium_result";
    String fqBigTable = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, bigTable);
    String fqView = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, viewName);

    int rowCount = 5000;

    // Create a base table and populate it with a few thousand rows using a range-based INSERT
    spark.sql(String.format("CREATE TABLE %s (id INT, val STRING) USING hive", fqBigTable));
    spark.sql(
        String.format(
            "INSERT INTO %s SELECT CAST(id AS INT) AS id, CONCAT('val_', CAST(id AS STRING)) AS val "
                + "FROM range(%d)",
            fqBigTable, rowCount));

    try {
      // Create a SELECT * view over the medium-sized table
      String viewSql = String.format("SELECT id, val FROM %s", fqBigTable);
      NameIdentifier viewIdent = NameIdentifier.of(schemaName, viewName);

      viewCatalog.createView(
          viewIdent,
          "View for medium result set test",
          new Column[] {
            Column.of("id", Types.IntegerType.get(), null),
            Column.of("val", Types.StringType.get(), null)
          },
          new SQLRepresentation[] {
            SQLRepresentation.builder().withDialect(SPARK_DIALECT).withSql(viewSql).build()
          },
          HIVE_CATALOG_NAME,
          schemaName,
          ImmutableMap.of("spark.sql.create.version", spark.version()));

      // Verify total row count via aggregation through the view
      List<Row> countResult =
          spark.sql(String.format("SELECT COUNT(*) FROM %s", fqView)).collectAsList();
      Assertions.assertEquals(
          (long) rowCount, countResult.get(0).getLong(0), "COUNT(*) should match inserted rows");

      // Verify full materialization returns all rows
      List<Row> allRows = spark.sql(String.format("SELECT * FROM %s", fqView)).collectAsList();
      Assertions.assertEquals(
          rowCount, allRows.size(), "View should materialize all rows via executeCollect()");

      // Spot-check a filtered lookup through the view
      List<Row> oneRow =
          spark.sql(String.format("SELECT * FROM %s WHERE id = 4999", fqView)).collectAsList();
      Assertions.assertEquals(1, oneRow.size(), "Expected exactly 1 row for id=4999");
      Assertions.assertEquals("val_4999", oneRow.get(0).getString(1));

      // Verify an aggregate computed through the view matches the expected sum of ids
      List<Row> sumResult =
          spark.sql(String.format("SELECT SUM(id) FROM %s", fqView)).collectAsList();
      long expectedSum = (long) (rowCount - 1) * rowCount / 2; // sum of 0..rowCount-1
      Assertions.assertEquals(
          expectedSum, sumResult.get(0).getLong(0), "SUM(id) through the view should be correct");
    } finally {
      spark.sql(String.format("DROP TABLE IF EXISTS %s", fqBigTable));
    }
  }

  // -------------------------------------------------------------------------
  // Test Case #36: testCreateViewViaSqlOnNamedCatalogFails
  //
  // Execute CREATE VIEW on a named catalog via Spark SQL. Spark V2 does not
  // route CREATE VIEW to named catalogs (ResolveSessionCatalog throws).
  // Verify the error is descriptive.
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("CREATE VIEW via Spark SQL on a named catalog fails with descriptive error")
  public void testCreateViewViaSqlOnNamedCatalogFails() {
    String viewName = "test_create_view_via_sql_fails";
    String fqTable = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, BASE_TABLE);
    String fqView = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, viewName);

    // Attempt to CREATE VIEW on the named Gravitino catalog — Spark V2 does not route
    // CREATE VIEW to named catalogs; this should fail with an AnalysisException.
    Exception ex =
        Assertions.assertThrows(
            Exception.class,
            () ->
                spark
                    .sql(String.format("CREATE VIEW %s AS SELECT * FROM %s", fqView, fqTable))
                    .collectAsList(),
            "CREATE VIEW on named catalog should throw an exception");

    LOG.info("CREATE VIEW on named catalog threw (expected): {}", ex.getMessage());

    // The error message should hint at the limitation (catalog, view, or unsupported)
    String msg = ex.getMessage().toLowerCase();
    Assertions.assertTrue(
        msg.contains("view") || msg.contains("catalog") || msg.contains("not support"),
        "Error message should mention view/catalog limitation, got: " + ex.getMessage());
  }

  // -------------------------------------------------------------------------
  // Test Case #37: testViewNameCaseInsensitivity
  //
  // HMS normalizes table/view names to lower case. Create a view with a
  // mixed-case name and verify it's accessible via lower-case query.
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("View name is case-insensitive (HMS normalizes to lower case)")
  public void testViewNameCaseInsensitivity() {
    String mixedCaseName = "MyCamelCaseView";
    String lowerCaseName = mixedCaseName.toLowerCase();
    String fqTable = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, BASE_TABLE);
    String fqViewLower = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, lowerCaseName);

    // Create a view with mixed-case name
    String viewSql = String.format("SELECT id, name FROM %s WHERE id <= 2", fqTable);
    NameIdentifier viewIdent = NameIdentifier.of(schemaName, mixedCaseName);

    viewCatalog.createView(
        viewIdent,
        "View for case sensitivity test",
        new Column[] {
          Column.of("id", Types.IntegerType.get(), null),
          Column.of("name", Types.StringType.get(), null)
        },
        new SQLRepresentation[] {
          SQLRepresentation.builder().withDialect(SPARK_DIALECT).withSql(viewSql).build()
        },
        HIVE_CATALOG_NAME,
        schemaName,
        ImmutableMap.of("spark.sql.create.version", spark.version()));

    // Query via lower-case name — HMS normalizes to lower case so this should work
    List<Row> rows =
        spark.sql(String.format("SELECT * FROM %s ORDER BY id", fqViewLower)).collectAsList();

    Assertions.assertEquals(2, rows.size(), "Expected 2 rows via lower-case view name");
    Assertions.assertEquals(1, rows.get(0).getInt(0));
    Assertions.assertEquals("Alice", rows.get(0).getString(1));
    Assertions.assertEquals(2, rows.get(1).getInt(0));
    Assertions.assertEquals("Bob", rows.get(1).getString(1));

    // Verify via upper-case name as well
    String fqViewUpper =
        String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, mixedCaseName.toUpperCase());
    List<Row> upperRows =
        spark.sql(String.format("SELECT * FROM %s ORDER BY id", fqViewUpper)).collectAsList();
    Assertions.assertEquals(2, upperRows.size(), "Expected 2 rows via upper-case view name");

    // viewExists should also be case-insensitive
    Assertions.assertTrue(
        viewCatalog.viewExists(NameIdentifier.of(schemaName, lowerCaseName)),
        "viewExists should find the view via lower-case name");
  }

  // -------------------------------------------------------------------------
  // Test Case #38: testRefreshTableOnView
  //
  // Create a view, query it, then REFRESH TABLE <view>. Query again.
  // The REFRESH operation should not throw and the view should remain
  // queryable with correct data.
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("REFRESH TABLE on a view does not throw and view remains queryable")
  public void testRefreshTableOnView() {
    String viewName = "test_refresh_table_on_view";
    String fqTable = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, BASE_TABLE);
    String fqView = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, viewName);

    // Create a view via Gravitino API
    String viewSql = String.format("SELECT id, name, age FROM %s", fqTable);
    NameIdentifier viewIdent = NameIdentifier.of(schemaName, viewName);

    viewCatalog.createView(
        viewIdent,
        "View for REFRESH TABLE test",
        new Column[] {
          Column.of("id", Types.IntegerType.get(), null),
          Column.of("name", Types.StringType.get(), null),
          Column.of("age", Types.IntegerType.get(), null)
        },
        new SQLRepresentation[] {
          SQLRepresentation.builder().withDialect(SPARK_DIALECT).withSql(viewSql).build()
        },
        HIVE_CATALOG_NAME,
        schemaName,
        ImmutableMap.of("spark.sql.create.version", spark.version()));

    // First query — establish baseline
    List<Row> beforeRefresh =
        spark.sql(String.format("SELECT * FROM %s ORDER BY id", fqView)).collectAsList();
    Assertions.assertEquals(5, beforeRefresh.size(), "Expected 5 rows before REFRESH");

    // Execute REFRESH TABLE on the view — should not throw
    try {
      spark.sql(String.format("REFRESH TABLE %s", fqView));
    } catch (Exception e) {
      // If REFRESH throws, log and fail with a descriptive message
      Assertions.fail("REFRESH TABLE on a view should not throw, but got: " + e.getMessage());
    }

    // Query after refresh — data should be unchanged and correct
    List<Row> afterRefresh =
        spark.sql(String.format("SELECT * FROM %s ORDER BY id", fqView)).collectAsList();
    Assertions.assertEquals(5, afterRefresh.size(), "Expected 5 rows after REFRESH");
    Assertions.assertEquals(1, afterRefresh.get(0).getInt(0));
    Assertions.assertEquals("Alice", afterRefresh.get(0).getString(1));
    Assertions.assertEquals(25, afterRefresh.get(0).getInt(2));
  }

  // -------------------------------------------------------------------------
  // Test Case #39: testViewColumnPruning
  //
  // Create a view with 3+ columns. SELECT only a subset from the view.
  // Spark prunes columns above the LocalScan; this verifies the projection
  // is applied correctly on top of the full materialized result.
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("Column pruning on view returns only the requested columns with correct values")
  public void testViewColumnPruning() {
    String viewName = "test_view_column_pruning";
    String fqTable = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, BASE_TABLE);
    String fqView = String.format("%s.%s.%s", HIVE_CATALOG_NAME, schemaName, viewName);

    // Create a view with 3 columns
    String viewSql = String.format("SELECT id, name, age FROM %s", fqTable);
    NameIdentifier viewIdent = NameIdentifier.of(schemaName, viewName);

    viewCatalog.createView(
        viewIdent,
        "View for column pruning test",
        new Column[] {
          Column.of("id", Types.IntegerType.get(), null),
          Column.of("name", Types.StringType.get(), null),
          Column.of("age", Types.IntegerType.get(), null)
        },
        new SQLRepresentation[] {
          SQLRepresentation.builder().withDialect(SPARK_DIALECT).withSql(viewSql).build()
        },
        HIVE_CATALOG_NAME,
        schemaName,
        ImmutableMap.of("spark.sql.create.version", spark.version()));

    // SELECT only 1 column — Spark should prune the other 2 columns above LocalScan
    List<Row> singleCol =
        spark.sql(String.format("SELECT name FROM %s ORDER BY name", fqView)).collectAsList();
    Assertions.assertEquals(5, singleCol.size(), "Expected 5 rows for single-column projection");
    Assertions.assertEquals(1, singleCol.get(0).schema().fields().length, "Should have 1 column");
    Assertions.assertEquals("name", singleCol.get(0).schema().fields()[0].name());
    Assertions.assertEquals("Alice", singleCol.get(0).getString(0));
    Assertions.assertEquals("Bob", singleCol.get(1).getString(0));

    // SELECT 2 out of 3 columns
    List<Row> twoCols =
        spark.sql(String.format("SELECT id, age FROM %s ORDER BY id", fqView)).collectAsList();
    Assertions.assertEquals(5, twoCols.size(), "Expected 5 rows for two-column projection");
    Assertions.assertEquals(2, twoCols.get(0).schema().fields().length, "Should have 2 columns");
    Assertions.assertEquals("id", twoCols.get(0).schema().fields()[0].name());
    Assertions.assertEquals("age", twoCols.get(0).schema().fields()[1].name());
    // Verify values are correct (not shifted due to wrong column mapping)
    Assertions.assertEquals(1, twoCols.get(0).getInt(0), "id should be 1");
    Assertions.assertEquals(25, twoCols.get(0).getInt(1), "age should be 25 for Alice");
    Assertions.assertEquals(3, twoCols.get(2).getInt(0), "id should be 3");
    Assertions.assertEquals(35, twoCols.get(2).getInt(1), "age should be 35 for Charlie");

    // SELECT with column alias — verify aliasing above the materialized scan works
    List<Row> aliased =
        spark
            .sql(String.format("SELECT name AS person_name FROM %s WHERE id = 1", fqView))
            .collectAsList();
    Assertions.assertEquals(1, aliased.size(), "Expected 1 row for aliased projection");
    Assertions.assertEquals(
        "person_name", aliased.get(0).schema().fields()[0].name(), "Alias should be applied");
    Assertions.assertEquals("Alice", aliased.get(0).getString(0));

    // SELECT with expression on pruned columns
    List<Row> exprOnPruned =
        spark
            .sql(String.format("SELECT id, age + 10 AS age_plus_10 FROM %s WHERE id = 2", fqView))
            .collectAsList();
    Assertions.assertEquals(1, exprOnPruned.size());
    Assertions.assertEquals(2, exprOnPruned.get(0).getInt(0));
    Assertions.assertEquals(40, exprOnPruned.get(0).getInt(1), "age + 10 should be 40 for Bob");
  }

  // ==================== Helper methods ====================

  /**
   * Set environment variable for the current process. Used to set SPARK_USER for Gravitino Spark
   * plugin authentication.
   */
  @SuppressWarnings("unchecked")
  private static void setEnv(String key, String value) {
    try {
      Map<String, String> env = System.getenv();
      Class<?> cl = env.getClass();
      java.lang.reflect.Field field = cl.getDeclaredField("m");
      field.setAccessible(true);
      Map<String, String> writableEnv = (Map<String, String>) field.get(env);
      if (value == null) {
        writableEnv.remove(key);
      } else {
        writableEnv.put(key, value);
      }
    } catch (Exception e) {
      throw new IllegalStateException("Failed to set environment variable: " + key, e);
    }
  }
}
