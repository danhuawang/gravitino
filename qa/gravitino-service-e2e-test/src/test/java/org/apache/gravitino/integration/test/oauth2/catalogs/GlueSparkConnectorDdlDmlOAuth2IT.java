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
package org.apache.gravitino.integration.test.oauth2.catalogs;

import com.google.common.collect.Maps;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.client.GravitinoAdminClient;
import org.apache.gravitino.client.GravitinoMetalake;
import org.apache.gravitino.client.OAuth2TokenProvider;
import org.apache.gravitino.spark.connector.GravitinoSparkConfig;
import org.apache.gravitino.spark.connector.plugin.GravitinoSparkPlugin;
import org.apache.gravitino.utils.RandomNameUtils;
import org.apache.spark.SparkConf;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * E2E integration tests for Iceberg-format DDL/DML operations via the Spark Glue connector with
 * OAuth2 authentication.
 *
 * <p>Test plan section 4A.2: Iceberg-format Table DDL/DML.
 *
 * <p>Only cases NOT already covered by {@link GlueSparkConnectorOAuth2IT} are implemented here:
 *
 * <ul>
 *   <li>4A.2.3 — Iceberg partitioned table (PARTITIONED BY) round-trips data
 * </ul>
 *
 * <p>Already covered by GlueSparkConnectorOAuth2IT:
 *
 * <ul>
 *   <li>4A.2.1 — covered by 4B.1 (USING iceberg + table_type verification)
 *   <li>4A.2.2 — covered by 4B.1 and 4B.4 (insert + select)
 *   <li>4A.2.4 — covered by 4B.7 (ALTER TABLE ADD/RENAME COLUMNS)
 *   <li>4A.2.5 — covered by 4B.8 (ALTER TABLE RENAME TO)
 * </ul>
 */
@DisplayName("Glue Spark Connector DDL/DML OAuth2 E2E (4A.2)")
public class GlueSparkConnectorDdlDmlOAuth2IT {

  private static final Logger LOG = LoggerFactory.getLogger(GlueSparkConnectorDdlDmlOAuth2IT.class);

  private static final String GLUE_CATALOG_NAME = "glue_ddl_dml";

  private static GravitinoAdminClient adminClient;
  private static GravitinoMetalake metalake;
  private static Catalog glueCatalog;
  private static SparkSession spark;

  /** Use the pre-deployed metalake so Trino Gravitino connector can discover our catalog. */
  private static final String METALAKE_NAME = "trino_connector_metalake";

  private static String schemaName;
  private static String testRunPrefix;
  private static boolean catalogCreatedByUs = false;

  // OAuth2 settings
  private static String oauth2ServerUri;
  private static String oauth2ClientId;
  private static String oauth2ClientSecret;
  private static String oauth2TokenPath;
  private static String oauth2Scope;

  @BeforeAll
  public static void setup() {
    // --- Gate: skip if OAuth2 or Glue credentials are not configured ---
    oauth2ServerUri = System.getenv("OAUTH2_SERVER_URI");
    oauth2ClientId = System.getenv("OAUTH2_CLIENT_ID");
    oauth2ClientSecret = System.getenv("OAUTH2_CLIENT_SECRET");
    Assumptions.assumeTrue(
        oauth2ServerUri != null && !oauth2ServerUri.isEmpty(),
        "Skipping: OAUTH2_SERVER_URI not set");

    String glueAccessKey = System.getProperty("glue.aws.access.key.id");
    String glueSecretKey = System.getProperty("glue.aws.secret.access.key");
    Assumptions.assumeTrue(
        glueAccessKey != null && !glueAccessKey.isEmpty(),
        "Skipping: glue.aws.access.key.id not set");

    String gravitinoUri = System.getProperty("gravitino.uri", "http://localhost:30090");
    String awsRegion = System.getProperty("glue.aws.region", "us-east-1");
    String glueCatalogId = System.getProperty("glue.aws.catalog.id", "730335553010");
    String warehouse =
        System.getProperty("glue.aws.warehouse", "s3://gravitino-glue-test/warehouse");

    String oauth2Realm = System.getenv().getOrDefault("OAUTH2_REALM", "myrealm");
    oauth2Scope = System.getenv().getOrDefault("OAUTH2_SCOPE", "openid profile email");
    oauth2TokenPath =
        System.getenv()
            .getOrDefault(
                "OAUTH2_TOKEN_PATH",
                String.format("realms/%s/protocol/openid-connect/token", oauth2Realm));
    if (oauth2TokenPath.startsWith("/")) {
      oauth2TokenPath = oauth2TokenPath.substring(1);
    }

    // --- 1. Connect to Gravitino via OAuth2 and load the pre-deployed metalake ---
    adminClient =
        GravitinoAdminClient.builder(gravitinoUri)
            .withOAuth(buildAdminTokenProvider())
            .withVersionCheckDisabled()
            .build();

    metalake = adminClient.loadMetalake(METALAKE_NAME);

    // Create or load the Glue catalog in the shared metalake.
    // The catalog may already exist from a prior interrupted run.
    if (metalake.catalogExists(GLUE_CATALOG_NAME)) {
      glueCatalog = metalake.loadCatalog(GLUE_CATALOG_NAME);
      catalogCreatedByUs = false;
    } else {
      Map<String, String> glueProps = Maps.newHashMap();
      glueProps.put("aws-region", awsRegion);
      glueProps.put("aws-glue-catalog-id", glueCatalogId);
      glueProps.put("warehouse", warehouse);
      glueProps.put("aws-access-key-id", glueAccessKey);
      glueProps.put("aws-secret-access-key", glueSecretKey);

      String glueEndpoint = System.getProperty("glue.aws.endpoint");
      if (glueEndpoint != null) {
        glueProps.put("aws-glue-endpoint", glueEndpoint);
      }

      glueCatalog =
          metalake.createCatalog(
              GLUE_CATALOG_NAME,
              Catalog.Type.RELATIONAL,
              "glue",
              "Glue catalog for DDL/DML E2E",
              glueProps);
      catalogCreatedByUs = true;
    }

    // --- 2. Create a test schema ---
    testRunPrefix = RandomNameUtils.genRandomName("dd");
    schemaName = testRunPrefix + "_db";
    Map<String, String> schemaProps = Maps.newHashMap();
    schemaProps.put("location", warehouse + "/" + schemaName);
    glueCatalog.asSchemas().createSchema(schemaName, "DDL/DML e2e test db", schemaProps);

    // --- 3. Initialize Spark session with Gravitino plugin + OAuth2 ---
    setEnv("GRAVITINO_VERSION_CHECK_DISABLED", "true");
    System.setProperty("aws.accessKeyId", glueAccessKey);
    System.setProperty("aws.secretKey", glueSecretKey);
    System.setProperty("aws.region", awsRegion);

    String oauth2Credential = oauth2ClientId + ":" + oauth2ClientSecret;

    SparkConf sparkConf =
        new SparkConf()
            .set("spark.plugins", GravitinoSparkPlugin.class.getName())
            .set(GravitinoSparkConfig.GRAVITINO_URI, gravitinoUri)
            .set(GravitinoSparkConfig.GRAVITINO_METALAKE, METALAKE_NAME)
            .set(GravitinoSparkConfig.GRAVITINO_ENABLE_ICEBERG_SUPPORT, "true")
            .set(GravitinoSparkConfig.GRAVITINO_AUTH_TYPE, "oauth2")
            .set(GravitinoSparkConfig.GRAVITINO_OAUTH2_URI, oauth2ServerUri)
            .set(GravitinoSparkConfig.GRAVITINO_OAUTH2_PATH, oauth2TokenPath)
            .set(GravitinoSparkConfig.GRAVITINO_OAUTH2_CREDENTIAL, oauth2Credential)
            .set(GravitinoSparkConfig.GRAVITINO_OAUTH2_SCOPE, oauth2Scope)
            .set("spark.sql.session.timeZone", "UTC")
            .set("spark.hadoop.fs.s3a.access.key", glueAccessKey)
            .set("spark.hadoop.fs.s3a.secret.key", glueSecretKey)
            .set("spark.hadoop.fs.s3a.endpoint.region", awsRegion)
            .set("spark.hadoop.fs.s3.impl", "org.apache.hadoop.fs.s3a.S3AFileSystem")
            .set("spark.hadoop.fs.s3.access.key", glueAccessKey)
            .set("spark.hadoop.fs.s3.secret.key", glueSecretKey)
            .set("spark.hadoop.fs.s3.endpoint.region", awsRegion);

    // Patched Hive jars for non-Iceberg (Hive/Parquet) tables via
    // AWSGlueDataCatalogHiveClientFactory
    String glueHiveJarsDir = System.getProperty("glue.hive-jars-dir");
    if (glueHiveJarsDir != null && !glueHiveJarsDir.isEmpty()) {
      String sharedPrefixes =
          String.join(
              ",",
              "com.mysql.jdbc",
              "org.postgresql",
              "com.microsoft.sqlserver.jdbc",
              "org.apache.thrift",
              "org.slf4j",
              "org.apache.log4j",
              "com.google.protobuf",
              "com.google.common",
              "javax.jdo",
              "org.apache.derby",
              "org.antlr");
      sparkConf
          .set("spark.sql.hive.metastore.version", "2.3.10")
          .set("spark.sql.hive.metastore.jars", "path")
          .set("spark.sql.hive.metastore.jars.path", glueHiveJarsDir + "/*")
          .set("spark.sql.hive.metastore.sharedPrefixes", sharedPrefixes);
    }

    spark =
        SparkSession.builder()
            .master("local[1]")
            .appName("GlueSparkConnectorDdlDmlOAuth2IT")
            .config(sparkConf)
            .enableHiveSupport()
            .getOrCreate();

    LOG.info(
        "GlueSparkConnectorDdlDmlOAuth2IT setup complete: metalake={}, catalog={}, schema={}",
        METALAKE_NAME,
        GLUE_CATALOG_NAME,
        schemaName);
  }

  @AfterAll
  public static void teardown() {
    if (spark != null) {
      spark.close();
    }
    try {
      if (catalogCreatedByUs && metalake != null) {
        metalake.dropCatalog(GLUE_CATALOG_NAME, true);
      }
    } catch (Exception e) {
      LOG.warn("Failed to drop catalog '{}'", GLUE_CATALOG_NAME, e);
    }
    if (adminClient != null) {
      adminClient.close();
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // 4A.2.3: Iceberg partitioned table.
  //
  // Creates an Iceberg table with PARTITIONED BY (id), inserts rows spanning
  // multiple partitions, and verifies all rows read back correctly.
  // Iceberg stores the partition spec in metadata (not in the schema columns),
  // so the schema itself should remain (id, name, age) — partition columns are
  // NOT moved to the end like in Hive.
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("4A.2.3 Iceberg partitioned table round-trips data correctly")
  public void testIcebergPartitionedTable() {
    String tableName = testRunPrefix + "_ice_part";
    String fqTable = String.format("%s.%s.%s", GLUE_CATALOG_NAME, schemaName, tableName);

    try {
      // Create an Iceberg table partitioned by id
      spark.sql(
          String.format(
              "CREATE TABLE %s (id INT, name STRING, age INT) USING iceberg PARTITIONED BY (id)",
              fqTable));

      // Insert rows spanning multiple partition values
      spark.sql(
          String.format(
              "INSERT INTO %s VALUES (1, 'Alice', 25), (2, 'Bob', 30), (1, 'Charlie', 35)",
              fqTable));

      // Full scan: all 3 rows should be returned
      List<Row> allRows =
          spark.sql(String.format("SELECT * FROM %s ORDER BY name", fqTable)).collectAsList();
      Assertions.assertEquals(3, allRows.size(), "Expected 3 rows across partitions");
      Assertions.assertEquals("Alice", allRows.get(0).getString(1));
      Assertions.assertEquals("Bob", allRows.get(1).getString(1));
      Assertions.assertEquals("Charlie", allRows.get(2).getString(1));

      // Partition-pruned read: only partition id=1 should return Alice and Charlie
      List<Row> partition1 =
          spark
              .sql(String.format("SELECT name, age FROM %s WHERE id = 1 ORDER BY name", fqTable))
              .collectAsList();
      Assertions.assertEquals(2, partition1.size(), "Expected 2 rows in partition id=1");
      Assertions.assertEquals("Alice", partition1.get(0).getString(0));
      Assertions.assertEquals(25, partition1.get(0).getInt(1));
      Assertions.assertEquals("Charlie", partition1.get(1).getString(0));
      Assertions.assertEquals(35, partition1.get(1).getInt(1));

      // Partition id=2: only Bob
      List<Row> partition2 =
          spark
              .sql(String.format("SELECT name, age FROM %s WHERE id = 2", fqTable))
              .collectAsList();
      Assertions.assertEquals(1, partition2.size(), "Expected 1 row in partition id=2");
      Assertions.assertEquals("Bob", partition2.get(0).getString(0));
      Assertions.assertEquals(30, partition2.get(0).getInt(1));

      // Verify the schema has all 3 columns (Iceberg does not move partition cols to the end)
      String[] columns = spark.sql(String.format("SELECT * FROM %s LIMIT 0", fqTable)).columns();
      Assertions.assertEquals(3, columns.length, "Schema should have 3 columns");
      Assertions.assertEquals("id", columns[0]);
      Assertions.assertEquals("name", columns[1]);
      Assertions.assertEquals("age", columns[2]);

      LOG.info("4A.2.3 PASSED: Iceberg partitioned table round-trips data correctly");
    } finally {
      dropTableQuietly(fqTable);
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // 4A.4.1: Basic type mapping round-trip.
  //
  // Creates an Iceberg table with int, long, string, double, float, boolean,
  // date, and timestamp columns. Inserts a row and verifies all values read back
  // correctly through the Gravitino Spark Glue connector.
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("4A.4.1 Basic type mapping round-trip (Iceberg)")
  public void testBasicTypeMappingRoundTrip() {
    String tableName = testRunPrefix + "_types";
    String fqTable = String.format("%s.%s.%s", GLUE_CATALOG_NAME, schemaName, tableName);

    try {
      spark.sql(
          String.format(
              "CREATE TABLE %s ("
                  + "c_int INT, "
                  + "c_long LONG, "
                  + "c_string STRING, "
                  + "c_double DOUBLE, "
                  + "c_float FLOAT, "
                  + "c_boolean BOOLEAN, "
                  + "c_date DATE, "
                  + "c_timestamp TIMESTAMP"
                  + ") USING iceberg",
              fqTable));

      spark.sql(
          String.format(
              "INSERT INTO %s VALUES ("
                  + "42, 9999999999, 'hello', 3.14, 2.71, true, "
                  + "DATE '2024-06-15', TIMESTAMP '2024-06-15 10:30:00'"
                  + ")",
              fqTable));

      List<Row> rows = spark.sql(String.format("SELECT * FROM %s", fqTable)).collectAsList();
      Assertions.assertEquals(1, rows.size(), "Expected 1 row");

      Row r = rows.get(0);
      Assertions.assertEquals(42, r.getInt(0), "c_int");
      Assertions.assertEquals(9999999999L, r.getLong(1), "c_long");
      Assertions.assertEquals("hello", r.getString(2), "c_string");
      Assertions.assertEquals(3.14, r.getDouble(3), 0.001, "c_double");
      Assertions.assertEquals(2.71f, r.getFloat(4), 0.01f, "c_float");
      Assertions.assertTrue(r.getBoolean(5), "c_boolean");
      Assertions.assertEquals(Date.valueOf("2024-06-15"), r.getDate(6), "c_date");
      Assertions.assertEquals(
          Timestamp.from(
              LocalDateTime.of(2024, 6, 15, 10, 30, 0).atZone(ZoneOffset.UTC).toInstant()),
          r.getTimestamp(7),
          "c_timestamp");

      LOG.info("4A.4.1 PASSED: basic types round-trip correctly");
    } finally {
      dropTableQuietly(fqTable);
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // 4A.4.2: Complex types supported.
  //
  // The Spark Glue connector supports complex types (ARRAY, MAP, STRUCT) for
  // Iceberg tables. This test verifies they can be created, inserted into, and
  // read back correctly.
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("4A.4.2 Complex types (ARRAY/MAP/STRUCT) are supported via Spark connector")
  public void testComplexTypesSupported() {
    String tableName = testRunPrefix + "_cplx";
    String fqTable = String.format("%s.%s.%s", GLUE_CATALOG_NAME, schemaName, tableName);

    try {
      spark.sql(
          String.format(
              "CREATE TABLE %s ("
                  + "id INT, "
                  + "tags ARRAY<STRING>, "
                  + "meta MAP<STRING, STRING>, "
                  + "info STRUCT<name: STRING, age: INT>"
                  + ") USING iceberg",
              fqTable));

      spark.sql(
          String.format(
              "INSERT INTO %s VALUES ("
                  + "1, "
                  + "array('a', 'b'), "
                  + "map('k1', 'v1'), "
                  + "named_struct('name', 'Alice', 'age', 30)"
                  + ")",
              fqTable));

      List<Row> rows = spark.sql(String.format("SELECT * FROM %s", fqTable)).collectAsList();
      Assertions.assertEquals(1, rows.size(), "Expected 1 row");

      Row r = rows.get(0);
      Assertions.assertEquals(1, r.getInt(0), "id");

      // ARRAY
      List<String> tags = r.getList(1);
      Assertions.assertEquals(2, tags.size());
      Assertions.assertEquals("a", tags.get(0));
      Assertions.assertEquals("b", tags.get(1));

      // MAP
      Map<String, String> meta = r.getJavaMap(2);
      Assertions.assertEquals("v1", meta.get("k1"));

      // STRUCT
      Row info = r.getStruct(3);
      Assertions.assertEquals("Alice", info.getString(0));
      Assertions.assertEquals(30, info.getInt(1));

      LOG.info("4A.4.2 PASSED: complex types round-trip correctly");
    } finally {
      dropTableQuietly(fqTable);
    }
  }

  @Test
  @DisplayName("4A.4.2b Complex types (ARRAY/MAP/STRUCT) are supported for Hive/Parquet tables")
  public void testComplexTypesSupportedHive() {
    Assumptions.assumeTrue(
        System.getProperty("glue.hive-jars-dir") != null,
        "Skipping 4A.4.2b: glue.hive-jars-dir not set (patched Hive jars required)");

    String tableName = testRunPrefix + "_cplx_hive";
    String fqTable = String.format("%s.%s.%s", GLUE_CATALOG_NAME, schemaName, tableName);

    try {
      spark.sql(
          String.format(
              "CREATE TABLE %s ("
                  + "id INT, "
                  + "tags ARRAY<STRING>, "
                  + "meta MAP<STRING, STRING>, "
                  + "info STRUCT<name: STRING, age: INT>"
                  + ") USING PARQUET",
              fqTable));

      spark.sql(
          String.format(
              "INSERT INTO %s VALUES ("
                  + "1, "
                  + "array('x', 'y', 'z'), "
                  + "map('key1', 'val1', 'key2', 'val2'), "
                  + "named_struct('name', 'Bob', 'age', 25)"
                  + ")",
              fqTable));

      List<Row> rows = spark.sql(String.format("SELECT * FROM %s", fqTable)).collectAsList();
      Assertions.assertEquals(1, rows.size(), "Expected 1 row");

      Row r = rows.get(0);
      Assertions.assertEquals(1, r.getInt(0), "id");

      // ARRAY
      List<String> tags = r.getList(1);
      Assertions.assertEquals(3, tags.size());
      Assertions.assertEquals("x", tags.get(0));
      Assertions.assertEquals("z", tags.get(2));

      // MAP
      Map<String, String> meta = r.getJavaMap(2);
      Assertions.assertEquals("val1", meta.get("key1"));
      Assertions.assertEquals("val2", meta.get("key2"));

      // STRUCT
      Row info = r.getStruct(3);
      Assertions.assertEquals("Bob", info.getString(0));
      Assertions.assertEquals(25, info.getInt(1));

      LOG.info("4A.4.2b PASSED: complex types round-trip correctly for Hive/Parquet tables");
    } finally {
      dropTableQuietly(fqTable);
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // 4A.4.4: List tables in nonexistent schema throws exception.
  //
  // SHOW TABLES IN a database that does not exist should throw an
  // AnalysisException or similar runtime exception, not silently return empty.
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("4A.4.4 SHOW TABLES in nonexistent schema throws exception")
  public void testListTablesInNonexistentSchema() {
    String nonexistent = testRunPrefix + "_no_such_db";
    Assertions.assertThrows(
        Exception.class,
        () ->
            spark
                .sql(String.format("SHOW TABLES IN %s.%s", GLUE_CATALOG_NAME, nonexistent))
                .collectAsList(),
        "SHOW TABLES in a nonexistent schema should throw");

    LOG.info("4A.4.4 PASSED: listing tables in nonexistent schema throws");
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // 4A.4.5: Alter / drop nonexistent database throws exception.
  //
  // Attempting to ALTER or DROP a database that does not exist must fail with
  // an exception rather than silently succeeding.
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("4A.4.5 ALTER/DROP nonexistent database throws exception")
  public void testAlterDropNonexistentDatabase() {
    String nonexistent = testRunPrefix + "_no_such_db2";

    // ALTER nonexistent database should throw
    Assertions.assertThrows(
        Exception.class,
        () ->
            spark.sql(
                String.format(
                    "ALTER DATABASE %s.%s SET DBPROPERTIES ('k'='v')",
                    GLUE_CATALOG_NAME, nonexistent)),
        "ALTER DATABASE on nonexistent schema should throw");

    // DROP nonexistent database (without IF EXISTS) should throw
    Assertions.assertThrows(
        Exception.class,
        () -> spark.sql(String.format("DROP DATABASE %s.%s", GLUE_CATALOG_NAME, nonexistent)),
        "DROP DATABASE on nonexistent schema should throw");

    LOG.info("4A.4.5 PASSED: ALTER/DROP nonexistent database throws");
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // 4A.5: Cross-engine Interoperability (Spark + Trino).
  //
  // These tests verify that Iceberg tables written by one engine can be read by
  // the other, proving the Glue catalog acts as a shared metadata source.
  //
  // Trino connects via JDBC to the deployed Trino instance that has the Gravitino
  // connector configured to the same metalake. The Trino catalog name mirrors the
  // Gravitino catalog name (auto-discovered by the Gravitino Trino connector).
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("4A.5.1 Spark write Iceberg → Trino read returns identical rows")
  public void testSparkWriteIcebergTrinoRead() throws Exception {
    Connection trino = getTrinoConnectorConnection();
    Assumptions.assumeTrue(trino != null, "Skipping: Trino connector JDBC not available");
    ensureTrinoGlueCatalog(trino);

    String tableName = testRunPrefix + "_xeng_s2t_ice";
    String fqSpark = String.format("%s.%s.%s", GLUE_CATALOG_NAME, schemaName, tableName);
    String fqTrino =
        String.format("\"%s\".\"%s\".\"%s\"", GLUE_CATALOG_NAME, schemaName, tableName);

    try {
      // Spark creates and inserts
      spark.sql(String.format("CREATE TABLE %s (id INT, val STRING) USING iceberg", fqSpark));
      spark.sql(String.format("INSERT INTO %s VALUES (1, 'spark_row')", fqSpark));

      // Trino reads
      List<String> rows =
          queryTrinoFirstColumn(trino, String.format("SELECT val FROM %s", fqTrino));
      Assertions.assertEquals(1, rows.size(), "Trino should see 1 row written by Spark");
      Assertions.assertEquals("spark_row", rows.get(0));

      LOG.info("4A.5.1 PASSED: Spark write Iceberg → Trino read");
    } finally {
      dropTableQuietly(fqSpark);
      trino.close();
    }
  }

  @Test
  @DisplayName("4A.5.4 Trino write Iceberg → Spark read returns identical rows")
  public void testTrinoWriteIcebergSparkRead() throws Exception {
    Connection trino = getTrinoConnectorConnection();
    Assumptions.assumeTrue(trino != null, "Skipping: Trino connector JDBC not available");
    ensureTrinoGlueCatalog(trino);

    String tableName = testRunPrefix + "_xeng_t2s_ice";
    String fqSpark = String.format("%s.%s.%s", GLUE_CATALOG_NAME, schemaName, tableName);
    String fqTrino =
        String.format("\"%s\".\"%s\".\"%s\"", GLUE_CATALOG_NAME, schemaName, tableName);

    try {
      // Trino creates and inserts an Iceberg table
      executeTrinoDdl(
          trino,
          String.format(
              "CREATE TABLE %s (id INTEGER, val VARCHAR) WITH (format = 'PARQUET')", fqTrino));
      executeTrinoDdl(trino, String.format("INSERT INTO %s VALUES (1, 'trino_row')", fqTrino));

      // Spark reads
      List<Row> rows = spark.sql(String.format("SELECT val FROM %s", fqSpark)).collectAsList();
      Assertions.assertEquals(1, rows.size(), "Spark should see 1 row written by Trino");
      Assertions.assertEquals("trino_row", rows.get(0).getString(0));

      LOG.info("4A.5.4 PASSED: Trino write Iceberg → Spark read");
    } finally {
      dropTableQuietly(fqSpark);
      trino.close();
    }
  }

  @Test
  @DisplayName("4A.5.2 Trino write Hive → Spark read returns identical rows")
  public void testTrinoWriteHiveSparkRead() throws Exception {
    Assumptions.assumeTrue(
        System.getProperty("glue.hive-jars-dir") != null,
        "Skipping 4A.5.2: glue.hive-jars-dir not set (patched Hive jars required)");

    Connection trino = getTrinoConnectorConnection();
    Assumptions.assumeTrue(trino != null, "Skipping: Trino connector JDBC not available");
    ensureTrinoGlueCatalog(trino);

    String tableName = testRunPrefix + "_xeng_t2s_hive";
    String fqSpark = String.format("%s.%s.%s", GLUE_CATALOG_NAME, schemaName, tableName);
    String fqTrino =
        String.format("\"%s\".\"%s\".\"%s\"", GLUE_CATALOG_NAME, schemaName, tableName);

    try {
      // Trino creates a Hive-format table (non-Iceberg) via the Gravitino connector
      executeTrinoDdl(
          trino,
          String.format(
              "CREATE TABLE %s (id INTEGER, val VARCHAR) WITH (format = 'PARQUET', type = 'hive')",
              fqTrino));
      executeTrinoDdl(trino, String.format("INSERT INTO %s VALUES (1, 'trino_hive')", fqTrino));

      // Spark reads via the Glue connector (Hive backend path)
      List<Row> rows = spark.sql(String.format("SELECT val FROM %s", fqSpark)).collectAsList();
      Assertions.assertEquals(1, rows.size(), "Spark should see 1 row written by Trino (Hive)");
      Assertions.assertEquals("trino_hive", rows.get(0).getString(0));

      LOG.info("4A.5.2 PASSED: Trino write Hive → Spark read");
    } finally {
      dropTableQuietly(fqSpark);
      trino.close();
    }
  }

  @Test
  @DisplayName("4A.5.3 Spark write Hive → Trino read returns identical rows")
  public void testSparkWriteHiveTrinoRead() throws Exception {
    Assumptions.assumeTrue(
        System.getProperty("glue.hive-jars-dir") != null,
        "Skipping 4A.5.3: glue.hive-jars-dir not set (patched Hive jars required)");

    Connection trino = getTrinoConnectorConnection();
    Assumptions.assumeTrue(trino != null, "Skipping: Trino connector JDBC not available");
    ensureTrinoGlueCatalog(trino);

    String tableName = testRunPrefix + "_xeng_s2t_hive";
    String fqSpark = String.format("%s.%s.%s", GLUE_CATALOG_NAME, schemaName, tableName);
    String fqTrino =
        String.format("\"%s\".\"%s\".\"%s\"", GLUE_CATALOG_NAME, schemaName, tableName);

    try {
      // Spark creates a Hive/Parquet table and inserts
      spark.sql(String.format("CREATE TABLE %s (id INT, val STRING) USING PARQUET", fqSpark));
      spark.sql(String.format("INSERT INTO %s VALUES (1, 'spark_hive')", fqSpark));

      // Trino reads via the Gravitino connector
      List<String> rows =
          queryTrinoFirstColumn(trino, String.format("SELECT val FROM %s", fqTrino));
      Assertions.assertEquals(1, rows.size(), "Trino should see 1 row written by Spark (Hive)");
      Assertions.assertEquals("spark_hive", rows.get(0));

      LOG.info("4A.5.3 PASSED: Spark write Hive → Trino read");
    } finally {
      dropTableQuietly(fqSpark);
      trino.close();
    }
  }

  @Test
  @DisplayName("4A.5.5 Cross-engine mixed schema visibility (Spark + Trino)")
  public void testCrossEngineMixedSchemaVisibility() throws Exception {
    Assumptions.assumeTrue(
        System.getProperty("glue.hive-jars-dir") != null,
        "Skipping 4A.5.5: glue.hive-jars-dir not set (patched Hive jars required)");

    Connection trino = getTrinoConnectorConnection();
    Assumptions.assumeTrue(trino != null, "Skipping: Trino connector JDBC not available");
    ensureTrinoGlueCatalog(trino);

    String hiveTable = testRunPrefix + "_xeng_mixed_hive";
    String icebergTable = testRunPrefix + "_xeng_mixed_ice";
    String fqHiveSpark = String.format("%s.%s.%s", GLUE_CATALOG_NAME, schemaName, hiveTable);
    String fqIcebergSpark = String.format("%s.%s.%s", GLUE_CATALOG_NAME, schemaName, icebergTable);
    String fqHiveTrino =
        String.format("\"%s\".\"%s\".\"%s\"", GLUE_CATALOG_NAME, schemaName, hiveTable);
    String fqIcebergTrino =
        String.format("\"%s\".\"%s\".\"%s\"", GLUE_CATALOG_NAME, schemaName, icebergTable);

    try {
      // Spark creates one Hive and one Iceberg table
      spark.sql(String.format("CREATE TABLE %s (id INT, val STRING) USING PARQUET", fqHiveSpark));
      spark.sql(String.format("INSERT INTO %s VALUES (1, 'hive_val')", fqHiveSpark));
      spark.sql(
          String.format("CREATE TABLE %s (id INT, val STRING) USING iceberg", fqIcebergSpark));
      spark.sql(String.format("INSERT INTO %s VALUES (2, 'ice_val')", fqIcebergSpark));

      // Trino can list both tables via SHOW TABLES
      List<String> trinoTables =
          queryTrinoFirstColumn(
              trino,
              String.format("SHOW TABLES FROM \"%s\".\"%s\"", GLUE_CATALOG_NAME, schemaName));
      Assertions.assertTrue(
          trinoTables.contains(hiveTable),
          "Trino SHOW TABLES should list Hive table, got: " + trinoTables);
      Assertions.assertTrue(
          trinoTables.contains(icebergTable),
          "Trino SHOW TABLES should list Iceberg table, got: " + trinoTables);

      // Trino can query both
      List<String> hiveRows =
          queryTrinoFirstColumn(trino, String.format("SELECT val FROM %s", fqHiveTrino));
      Assertions.assertEquals(1, hiveRows.size());
      Assertions.assertEquals("hive_val", hiveRows.get(0));

      List<String> iceRows =
          queryTrinoFirstColumn(trino, String.format("SELECT val FROM %s", fqIcebergTrino));
      Assertions.assertEquals(1, iceRows.size());
      Assertions.assertEquals("ice_val", iceRows.get(0));

      LOG.info("4A.5.5 PASSED: cross-engine mixed schema fully visible from both engines");
    } finally {
      dropTableQuietly(fqHiveSpark);
      dropTableQuietly(fqIcebergSpark);
      trino.close();
    }
  }

  // ==================== Helper methods ====================

  /**
   * Builds an OAuth2TokenProvider that authenticates as service-account-postman-client via Keycloak
   * client_credentials grant. This is the metalake owner for trino_connector_metalake.
   */
  private static OAuth2TokenProvider buildAdminTokenProvider() {
    String credential = oauth2ClientId + ":" + oauth2ClientSecret;
    return org.apache.gravitino.client.DefaultOAuth2TokenProvider.builder()
        .withUri(oauth2ServerUri)
        .withCredential(credential)
        .withScope(oauth2Scope)
        .withPath(oauth2TokenPath)
        .build();
  }

  /** Drops a table via Spark SQL, swallowing any exceptions. */
  private static void dropTableQuietly(String fqTable) {
    try {
      spark.sql(String.format("DROP TABLE IF EXISTS %s", fqTable));
    } catch (Exception e) {
      LOG.warn("Failed to drop table {}", fqTable, e);
    }
  }

  /** Set environment variable for the current process. */
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

  /**
   * Creates a Trino JDBC connection to the Gravitino-connector Trino instance (port 30881). This
   * Trino has the Gravitino connector plugin. Connects to the 'gravitino' catalog so we can call
   * system procedures and then query the registered Glue catalog. Returns null if the URI is not
   * configured.
   */
  private static Connection getTrinoConnectorConnection() {
    String trinoUri = System.getProperty("gravitino.trino.connector.uri");
    if (trinoUri == null || trinoUri.isEmpty()) {
      return null;
    }
    try {
      // Connect to the 'gravitino' catalog (the Gravitino Trino connector itself)
      String trinoJdbcUrl = "jdbc:trino://" + trinoUri.replaceFirst("^https?://", "");
      return DriverManager.getConnection(trinoJdbcUrl, "admin", null);
    } catch (Exception e) {
      LOG.warn("Failed to connect to Trino connector at {}: {}", trinoUri, e.getMessage());
      return null;
    }
  }

  /**
   * Ensures the Glue catalog is usable in Trino by polling until a simple metadata query succeeds.
   * The catalog is created in Gravitino by {@code @BeforeAll} and auto-discovered by the Trino
   * Gravitino connector's periodic metadata refresh.
   */
  private static void ensureTrinoGlueCatalog(Connection trino) {
    int maxAttempts = 30;
    int intervalMs = 2000;
    String probeSql = String.format("SHOW SCHEMAS FROM \"%s\"", GLUE_CATALOG_NAME);

    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
      try {
        queryTrinoFirstColumn(trino, probeSql);
        LOG.info(
            "Catalog '{}' is available in Trino after {} attempt(s)", GLUE_CATALOG_NAME, attempt);
        return;
      } catch (SQLException e) {
        LOG.info(
            "Waiting for catalog '{}' in Trino (attempt {}/{}): {}",
            GLUE_CATALOG_NAME,
            attempt,
            maxAttempts,
            e.getMessage());
      }

      try {
        Thread.sleep(intervalMs);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException("Interrupted while waiting for Trino catalog", e);
      }
    }

    throw new RuntimeException(
        String.format(
            "Catalog '%s' did not become available in Trino within %d seconds",
            GLUE_CATALOG_NAME, maxAttempts * intervalMs / 1000));
  }

  /** Executes a Trino DDL/DML statement (no result set expected). */
  private static void executeTrinoDdl(Connection conn, String sql) throws SQLException {
    try (Statement stmt = conn.createStatement()) {
      stmt.execute(sql);
    }
  }

  /** Executes a Trino query and returns the first column of all rows as strings. */
  private static List<String> queryTrinoFirstColumn(Connection conn, String sql)
      throws SQLException {
    List<String> results = new ArrayList<>();
    try (Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      while (rs.next()) {
        results.add(rs.getString(1));
      }
    }
    return results;
  }
}
