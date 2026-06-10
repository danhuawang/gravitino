/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package org.apache.gravitino.integration.test.oauth2.catalogs;

import com.google.common.collect.Maps;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Schema;
import org.apache.gravitino.client.DefaultOAuth2TokenProvider;
import org.apache.gravitino.client.GravitinoAdminClient;
import org.apache.gravitino.client.GravitinoMetalake;
import org.apache.gravitino.client.OAuth2TokenProvider;
import org.apache.gravitino.rel.Column;
import org.apache.gravitino.rel.Table;
import org.apache.gravitino.spark.connector.GravitinoSparkConfig;
import org.apache.gravitino.spark.connector.glue.GluePropertiesConverter;
import org.apache.gravitino.spark.connector.plugin.GravitinoSparkPlugin;
import org.apache.gravitino.utils.RandomNameUtils;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.aws.glue.GlueCatalog;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.types.Types;
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

/** E2E integration test for the Spark Glue connector (PR #11186) with OAuth2 authentication. */
@DisplayName("Glue Spark Connector OAuth2 E2E (4B.1)")
public class GlueSparkConnectorOAuth2IT {

  private static final Logger LOG = LoggerFactory.getLogger(GlueSparkConnectorOAuth2IT.class);

  private static final String GLUE_CATALOG_NAME = "glue_spark_oauth2";

  private static GravitinoAdminClient adminClient;
  private static GravitinoMetalake metalake;
  private static Catalog glueCatalog;
  private static SparkSession spark;
  private static String gravitinoUri;

  private static String metalakeName;
  private static String schemaName;
  private static String testRunPrefix;

  private static String glueAwsRegion;
  private static String glueAwsCatalogId;
  private static String glueWarehouse;
  private static String glueAwsAccessKey;
  private static String glueAwsSecretKey;
  private static String glueAwsEndpoint;

  private static String oauth2ServerUri;
  private static String oauth2ClientId;
  private static String oauth2ClientSecret;
  private static String oauth2TokenPath;
  private static String oauth2Scope;
  private static String oauth2Realm;

  @BeforeAll
  public static void setup() {
    oauth2ServerUri = System.getenv("OAUTH2_SERVER_URI");
    oauth2ClientId = System.getenv("OAUTH2_CLIENT_ID");
    oauth2ClientSecret = System.getenv("OAUTH2_CLIENT_SECRET");
    Assumptions.assumeTrue(
        oauth2ServerUri != null && !oauth2ServerUri.isEmpty(),
        "Skipping: OAUTH2_SERVER_URI not set");

    glueAwsAccessKey = System.getProperty("glue.aws.access.key.id");
    glueAwsSecretKey = System.getProperty("glue.aws.secret.access.key");
    Assumptions.assumeTrue(
        glueAwsAccessKey != null && !glueAwsAccessKey.isEmpty(),
        "Skipping: glue.aws.access.key.id not set");

    gravitinoUri = System.getProperty("gravitino.uri", "http://localhost:30090");
    glueAwsRegion = System.getProperty("glue.aws.region", "us-east-1");
    glueAwsCatalogId = System.getProperty("glue.aws.catalog.id", "730335553010");
    glueWarehouse = System.getProperty("glue.aws.warehouse", "s3://gravitino-glue-test/warehouse");

    oauth2Realm = System.getenv().getOrDefault("OAUTH2_REALM", "myrealm");
    oauth2Scope = System.getenv().getOrDefault("OAUTH2_SCOPE", "openid profile email");
    oauth2TokenPath =
        System.getenv()
            .getOrDefault(
                "OAUTH2_TOKEN_PATH",
                String.format("realms/%s/protocol/openid-connect/token", oauth2Realm));
    if (oauth2TokenPath.startsWith("/")) {
      oauth2TokenPath = oauth2TokenPath.substring(1);
    }

    adminClient =
        GravitinoAdminClient.builder(gravitinoUri)
            .withOAuth(buildAdminTokenProvider())
            .withVersionCheckDisabled()
            .build();

    metalakeName = RandomNameUtils.genRandomName("glue_oauth2_e2e");
    metalake =
        adminClient.createMetalake(
            metalakeName, "Metalake for Glue Spark OAuth2 E2E", Collections.emptyMap());

    Map<String, String> glueProps = Maps.newHashMap();
    glueProps.put("aws-region", glueAwsRegion);
    glueProps.put("aws-glue-catalog-id", glueAwsCatalogId);
    glueProps.put("warehouse", glueWarehouse);
    glueProps.put("aws-access-key-id", glueAwsAccessKey);
    glueProps.put("aws-secret-access-key", glueAwsSecretKey);

    glueAwsEndpoint = System.getProperty("glue.aws.endpoint");
    if (glueAwsEndpoint != null) {
      glueProps.put("aws-glue-endpoint", glueAwsEndpoint);
    }

    glueCatalog =
        metalake.createCatalog(
            GLUE_CATALOG_NAME,
            Catalog.Type.RELATIONAL,
            "glue",
            "Glue catalog for Spark OAuth2 E2E",
            glueProps);

    testRunPrefix = RandomNameUtils.genRandomName("sp");
    schemaName = testRunPrefix + "_db";
    Map<String, String> schemaProps = Maps.newHashMap();
    schemaProps.put("location", glueWarehouse + "/" + schemaName);
    glueCatalog.asSchemas().createSchema(schemaName, "Spark OAuth2 e2e test db", schemaProps);

    setEnv("GRAVITINO_VERSION_CHECK_DISABLED", "true");

    System.setProperty("aws.accessKeyId", glueAwsAccessKey);
    System.setProperty("aws.secretKey", glueAwsSecretKey);
    System.setProperty("aws.region", glueAwsRegion);

    String oauth2Credential = oauth2ClientId + ":" + oauth2ClientSecret;

    SparkConf sparkConf =
        new SparkConf()
            .set("spark.plugins", GravitinoSparkPlugin.class.getName())
            .set(GravitinoSparkConfig.GRAVITINO_URI, gravitinoUri)
            .set(GravitinoSparkConfig.GRAVITINO_METALAKE, metalakeName)
            .set(GravitinoSparkConfig.GRAVITINO_ENABLE_ICEBERG_SUPPORT, "true")
            .set(GravitinoSparkConfig.GRAVITINO_AUTH_TYPE, "oauth2")
            .set(GravitinoSparkConfig.GRAVITINO_OAUTH2_URI, oauth2ServerUri)
            .set(GravitinoSparkConfig.GRAVITINO_OAUTH2_PATH, oauth2TokenPath)
            .set(GravitinoSparkConfig.GRAVITINO_OAUTH2_CREDENTIAL, oauth2Credential)
            .set(GravitinoSparkConfig.GRAVITINO_OAUTH2_SCOPE, oauth2Scope)
            .set("spark.sql.session.timeZone", "UTC")
            .set("spark.hadoop.fs.s3a.access.key", glueAwsAccessKey)
            .set("spark.hadoop.fs.s3a.secret.key", glueAwsSecretKey)
            .set("spark.hadoop.fs.s3a.endpoint.region", glueAwsRegion)
            .set("spark.hadoop.fs.s3.impl", "org.apache.hadoop.fs.s3a.S3AFileSystem")
            .set("spark.hadoop.fs.s3.access.key", glueAwsAccessKey)
            .set("spark.hadoop.fs.s3.secret.key", glueAwsSecretKey)
            .set("spark.hadoop.fs.s3.endpoint.region", glueAwsRegion);

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
            .appName("GlueSparkConnectorOAuth2IT")
            .config(sparkConf)
            .enableHiveSupport()
            .getOrCreate();

    LOG.info(
        "GlueSparkConnectorOAuth2IT setup complete: metalake={}, catalog={}, schema={}",
        metalakeName,
        GLUE_CATALOG_NAME,
        schemaName);
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

  @Test
  @DisplayName(
      "4B.1 USING iceberg stores table-format=ICEBERG and round-trips (OAuth2 authenticated)")
  public void testUsingIcebergStoresTableFormatAndRoundTrips() {
    String tableName = testRunPrefix + "_iceberg_rt";
    String fqTable = String.format("%s.%s.%s", GLUE_CATALOG_NAME, schemaName, tableName);

    try {
      spark.sql(
          String.format("CREATE TABLE %s (id INT, name STRING, age INT) USING iceberg", fqTable));

      Table gravitinoTable =
          glueCatalog.asTableCatalog().loadTable(NameIdentifier.of(schemaName, tableName));
      Assertions.assertNotNull(
          gravitinoTable, "Table should exist in Gravitino after Spark CREATE");

      Map<String, String> props = gravitinoTable.properties();
      LOG.info("Gravitino table properties for {}: {}", tableName, props);

      String tableFormat = props.get("table-format");
      String tableType = props.get("table_type");

      boolean isIcebergFormat =
          "ICEBERG".equalsIgnoreCase(tableFormat) || "ICEBERG".equalsIgnoreCase(tableType);
      Assertions.assertTrue(
          isIcebergFormat,
          String.format(
              "Expected table-format=ICEBERG or table_type=ICEBERG in Gravitino properties, "
                  + "but got table-format='%s', table_type='%s'. Full props: %s",
              tableFormat, tableType, props));

      String metadataLocation = props.get("metadata_location");
      Assertions.assertNotNull(
          metadataLocation,
          "metadata_location must be present for an Iceberg table; full props: " + props);
      Assertions.assertFalse(metadataLocation.isEmpty(), "metadata_location must not be empty");

      spark.sql(String.format("INSERT INTO %s VALUES (1, 'Alice', 30)", fqTable));

      List<Row> rows = spark.sql(String.format("SELECT * FROM %s", fqTable)).collectAsList();
      Assertions.assertEquals(1, rows.size(), "Expected 1 row after insert");
      Assertions.assertEquals(1, rows.get(0).getInt(0), "id should be 1");
      Assertions.assertEquals("Alice", rows.get(0).getString(1), "name should be Alice");
      Assertions.assertEquals(30, rows.get(0).getInt(2), "age should be 30");

      LOG.info(
          "4B.1 PASSED: USING iceberg correctly stores Iceberg format and round-trips (OAuth2)");
    } finally {
      try {
        spark.sql(String.format("DROP TABLE IF EXISTS %s", fqTable));
      } catch (Exception e) {
        LOG.warn("Failed to drop table {}", fqTable, e);
      }
    }
  }

  @Test
  @DisplayName("4B.2 USING ICEBERG/Iceberg (mixed case) still routes to Iceberg backend")
  public void testMixedCaseProviderRoutesToIceberg() {
    String tableUpper = testRunPrefix + "_ice_upper";
    String tableMixed = testRunPrefix + "_ice_mixed";
    String fqUpper = String.format("%s.%s.%s", GLUE_CATALOG_NAME, schemaName, tableUpper);
    String fqMixed = String.format("%s.%s.%s", GLUE_CATALOG_NAME, schemaName, tableMixed);

    try {
      // Create with uppercase ICEBERG
      spark.sql(String.format("CREATE TABLE %s (id INT, val STRING) USING ICEBERG", fqUpper));

      Table upper =
          glueCatalog.asTableCatalog().loadTable(NameIdentifier.of(schemaName, tableUpper));
      assertIsIcebergTable(upper, tableUpper);

      // Create with mixed-case Iceberg
      spark.sql(String.format("CREATE TABLE %s (id INT, val STRING) USING Iceberg", fqMixed));

      Table mixed =
          glueCatalog.asTableCatalog().loadTable(NameIdentifier.of(schemaName, tableMixed));
      assertIsIcebergTable(mixed, tableMixed);

      // Verify both are readable through the Iceberg backend
      spark.sql(String.format("INSERT INTO %s VALUES (1, 'upper')", fqUpper));
      spark.sql(String.format("INSERT INTO %s VALUES (2, 'mixed')", fqMixed));

      List<Row> upperRows = spark.sql(String.format("SELECT * FROM %s", fqUpper)).collectAsList();
      Assertions.assertEquals(1, upperRows.size());
      Assertions.assertEquals("upper", upperRows.get(0).getString(1));

      List<Row> mixedRows = spark.sql(String.format("SELECT * FROM %s", fqMixed)).collectAsList();
      Assertions.assertEquals(1, mixedRows.size());
      Assertions.assertEquals("mixed", mixedRows.get(0).getString(1));

      LOG.info("4B.2 PASSED: mixed-case USING iceberg routes correctly");
    } finally {
      dropTableQuietly(fqUpper);
      dropTableQuietly(fqMixed);
    }
  }

  @Test
  @DisplayName("4B.3 USING parquet does NOT set table-format=ICEBERG (stays non-Iceberg)")
  public void testUsingParquetDoesNotRouteToIceberg() {
    Assumptions.assumeTrue(
        System.getProperty("glue.hive-jars-dir") != null,
        "Skipping 4B.3: glue.hive-jars-dir not set (patched Hive jars required for non-Iceberg)");

    String tableName = testRunPrefix + "_parquet_nrt";
    String fqTable = String.format("%s.%s.%s", GLUE_CATALOG_NAME, schemaName, tableName);

    try {
      spark.sql(String.format("CREATE TABLE %s (id INT, val STRING) USING PARQUET", fqTable));

      Table gravitinoTable =
          glueCatalog.asTableCatalog().loadTable(NameIdentifier.of(schemaName, tableName));
      Assertions.assertNotNull(gravitinoTable, "Table should exist after CREATE");

      Map<String, String> props = gravitinoTable.properties();
      LOG.info("Parquet table properties for {}: {}", tableName, props);

      String tableFormat = props.get("table-format");
      String tableType = props.get("table_type");

      boolean isIceberg =
          "ICEBERG".equalsIgnoreCase(tableFormat) || "ICEBERG".equalsIgnoreCase(tableType);
      Assertions.assertFalse(
          isIceberg,
          String.format(
              "Parquet table must NOT be routed as Iceberg, but got table-format='%s', "
                  + "table_type='%s'. Props: %s",
              tableFormat, tableType, props));

      Assertions.assertNull(
          props.get("metadata_location"),
          "metadata_location must not be present for a non-Iceberg table; props: " + props);

      spark.sql(String.format("INSERT INTO %s VALUES (1, 'parquet_val')", fqTable));

      List<Row> rows = spark.sql(String.format("SELECT * FROM %s", fqTable)).collectAsList();
      Assertions.assertEquals(1, rows.size(), "Expected 1 row after insert");
      Assertions.assertEquals(1, rows.get(0).getInt(0), "id should be 1");
      Assertions.assertEquals("parquet_val", rows.get(0).getString(1), "val should match");

      LOG.info("4B.3 PASSED: USING parquet stays non-Iceberg");
    } finally {
      dropTableQuietly(fqTable);
    }
  }

  @Test
  @DisplayName("4B.4 Insert then select on an Iceberg table returns the written rows")
  public void testInsertThenSelectIcebergTable() {
    String tableName = testRunPrefix + "_iceberg_dml";
    String fqTable = String.format("%s.%s.%s", GLUE_CATALOG_NAME, schemaName, tableName);

    try {
      spark.sql(
          String.format("CREATE TABLE %s (id INT, name STRING, age INT) USING iceberg", fqTable));

      spark.sql(String.format("INSERT INTO %s VALUES (1, 'Alice', 25)", fqTable));
      spark.sql(String.format("INSERT INTO %s VALUES (2, 'Bob', 30), (3, 'Charlie', 35)", fqTable));

      List<Row> rows =
          spark.sql(String.format("SELECT * FROM %s ORDER BY id", fqTable)).collectAsList();
      Assertions.assertEquals(3, rows.size(), "Expected 3 rows after two inserts");
      Assertions.assertEquals("Alice", rows.get(0).getString(1));
      Assertions.assertEquals(25, rows.get(0).getInt(2));
      Assertions.assertEquals("Bob", rows.get(1).getString(1));
      Assertions.assertEquals("Charlie", rows.get(2).getString(1));

      List<Row> filtered =
          spark
              .sql(String.format("SELECT name FROM %s WHERE age >= 30 ORDER BY age", fqTable))
              .collectAsList();
      Assertions.assertEquals(2, filtered.size(), "Expected 2 rows with age >= 30");
      Assertions.assertEquals("Bob", filtered.get(0).getString(0));
      Assertions.assertEquals("Charlie", filtered.get(1).getString(0));

      List<Row> agg =
          spark.sql(String.format("SELECT COUNT(*), SUM(age) FROM %s", fqTable)).collectAsList();
      Assertions.assertEquals(1, agg.size());
      Assertions.assertEquals(3L, agg.get(0).getLong(0), "COUNT(*) should be 3");
      Assertions.assertEquals(90L, agg.get(0).getLong(1), "SUM(age) should be 25+30+35=90");

      LOG.info("4B.4 PASSED: Iceberg insert+select round-trips correctly");
    } finally {
      dropTableQuietly(fqTable);
    }
  }

  @Test
  @DisplayName("4B.5 Native Iceberg table (only table_type=ICEBERG) loads via Iceberg backend")
  public void testNativeIcebergTableRoutesViaTableType() {
    String tableName = testRunPrefix + "_native_iceberg";
    String fqTable = String.format("%s.%s.%s", GLUE_CATALOG_NAME, schemaName, tableName);

    GlueCatalog nativeGlue = buildNativeIcebergGlueCatalog();
    try {
      org.apache.iceberg.Schema icebergSchema =
          new org.apache.iceberg.Schema(
              Types.NestedField.required(1, "id", Types.IntegerType.get()),
              Types.NestedField.optional(2, "name", Types.StringType.get()));
      TableIdentifier identifier = TableIdentifier.of(schemaName, tableName);
      nativeGlue.createTable(identifier, icebergSchema);

      Table gravitinoTable =
          glueCatalog.asTableCatalog().loadTable(NameIdentifier.of(schemaName, tableName));
      Assertions.assertNotNull(
          gravitinoTable, "Native Iceberg table should be visible through Gravitino");

      Map<String, String> props = gravitinoTable.properties();
      LOG.info("Native Iceberg table properties for {}: {}", tableName, props);

      String tableFormat = props.get("table-format");
      String tableType = props.get("table_type");

      Assertions.assertEquals(
          "ICEBERG",
          tableType == null ? null : tableType.toUpperCase(Locale.ROOT),
          "Native Iceberg table must carry table_type=ICEBERG; props: " + props);
      Assertions.assertFalse(
          "ICEBERG".equalsIgnoreCase(tableFormat),
          "Native Iceberg table must NOT carry table-format=ICEBERG (it is the connector hint); "
              + "props: "
              + props);
      Assertions.assertNotNull(
          props.get("metadata_location"),
          "Native Iceberg table must expose metadata_location; props: " + props);

      spark.sql(String.format("INSERT INTO %s VALUES (1, 'native')", fqTable));

      List<Row> rows =
          spark.sql(String.format("SELECT * FROM %s ORDER BY id", fqTable)).collectAsList();
      Assertions.assertEquals(1, rows.size(), "Expected 1 row from the native Iceberg table");
      Assertions.assertEquals(1, rows.get(0).getInt(0), "id should be 1");
      Assertions.assertEquals("native", rows.get(0).getString(1), "name should be 'native'");

      LOG.info("4B.5 PASSED: native Iceberg table routed via table_type=ICEBERG");
    } finally {
      dropTableQuietly(fqTable);
      try {
        nativeGlue.close();
      } catch (Exception e) {
        LOG.warn("Failed to close native Iceberg GlueCatalog", e);
      }
    }
  }

  @Test
  @DisplayName("4B.6 Mixed Hive + Iceberg in one database: SHOW TABLES lists both, each loads")
  public void testMixedHiveAndIcebergInSameDatabase() {
    Assumptions.assumeTrue(
        System.getProperty("glue.hive-jars-dir") != null,
        "Skipping 4B.6: glue.hive-jars-dir not set (patched Hive jars required for non-Iceberg)");

    String hiveTable = testRunPrefix + "_mixed_hive";
    String icebergTable = testRunPrefix + "_mixed_iceberg";
    String fqHive = String.format("%s.%s.%s", GLUE_CATALOG_NAME, schemaName, hiveTable);
    String fqIceberg = String.format("%s.%s.%s", GLUE_CATALOG_NAME, schemaName, icebergTable);

    try {
      // Create one Hive-format and one Iceberg-format table in the same database
      spark.sql(String.format("CREATE TABLE %s (id INT, val STRING) USING PARQUET", fqHive));
      spark.sql(String.format("CREATE TABLE %s (id INT, val STRING) USING iceberg", fqIceberg));

      // SHOW TABLES must list both
      List<Row> shown =
          spark
              .sql(String.format("SHOW TABLES IN %s.%s", GLUE_CATALOG_NAME, schemaName))
              .collectAsList();
      Set<String> listed =
          shown.stream()
              // SHOW TABLES returns (namespace, tableName, isTemporary); tableName is column 1
              .map(r -> r.getString(1))
              .collect(Collectors.toSet());
      Assertions.assertTrue(
          listed.contains(hiveTable), "SHOW TABLES should list the Hive table, got: " + listed);
      Assertions.assertTrue(
          listed.contains(icebergTable),
          "SHOW TABLES should list the Iceberg table, got: " + listed);

      // Each table loads through its matching backend (verified via Gravitino properties)
      Table hiveGt =
          glueCatalog.asTableCatalog().loadTable(NameIdentifier.of(schemaName, hiveTable));
      Map<String, String> hiveProps = hiveGt.properties();
      boolean hiveIsIceberg =
          "ICEBERG".equalsIgnoreCase(hiveProps.get("table-format"))
              || "ICEBERG".equalsIgnoreCase(hiveProps.get("table_type"));
      Assertions.assertFalse(
          hiveIsIceberg, "Hive table must not be Iceberg-formatted; props: " + hiveProps);

      Table icebergGt =
          glueCatalog.asTableCatalog().loadTable(NameIdentifier.of(schemaName, icebergTable));
      assertIsIcebergTable(icebergGt, icebergTable);

      // Each table is independently writable/readable through its correct backend
      spark.sql(String.format("INSERT INTO %s VALUES (1, 'hive_row')", fqHive));
      spark.sql(String.format("INSERT INTO %s VALUES (2, 'iceberg_row')", fqIceberg));

      List<Row> hiveRows = spark.sql(String.format("SELECT * FROM %s", fqHive)).collectAsList();
      Assertions.assertEquals(1, hiveRows.size());
      Assertions.assertEquals("hive_row", hiveRows.get(0).getString(1));

      List<Row> icebergRows =
          spark.sql(String.format("SELECT * FROM %s", fqIceberg)).collectAsList();
      Assertions.assertEquals(1, icebergRows.size());
      Assertions.assertEquals("iceberg_row", icebergRows.get(0).getString(1));

      LOG.info("4B.6 PASSED: mixed Hive + Iceberg listed and routed correctly");
    } finally {
      dropTableQuietly(fqHive);
      dropTableQuietly(fqIceberg);
    }
  }

  @Test
  @DisplayName("4B.8 Iceberg ALTER TABLE ... RENAME TO succeeds")
  public void testIcebergRenameTable() {
    String oldName = testRunPrefix + "_ice_rename_old";
    String newName = testRunPrefix + "_ice_rename_new";
    String fqOld = String.format("%s.%s.%s", GLUE_CATALOG_NAME, schemaName, oldName);
    String fqNew = String.format("%s.%s.%s", GLUE_CATALOG_NAME, schemaName, newName);

    try {
      spark.sql(String.format("CREATE TABLE %s (id INT, val STRING) USING iceberg", fqOld));
      spark.sql(String.format("INSERT INTO %s VALUES (1, 'before_rename')", fqOld));

      spark.sql(String.format("ALTER TABLE %s RENAME TO %s.%s", fqOld, schemaName, newName));

      // New name resolves and data is preserved
      List<Row> rows = spark.sql(String.format("SELECT * FROM %s", fqNew)).collectAsList();
      Assertions.assertEquals(1, rows.size(), "Renamed table should retain its single row");
      Assertions.assertEquals(1, rows.get(0).getInt(0));
      Assertions.assertEquals("before_rename", rows.get(0).getString(1));

      // New name exists in Gravitino, still Iceberg-formatted
      Table renamed =
          glueCatalog.asTableCatalog().loadTable(NameIdentifier.of(schemaName, newName));
      assertIsIcebergTable(renamed, newName);

      // Old name no longer exists
      Assertions.assertFalse(
          glueCatalog.asTableCatalog().tableExists(NameIdentifier.of(schemaName, oldName)),
          "Old table name should no longer exist after rename");

      LOG.info("4B.8 PASSED: Iceberg rename succeeded and preserved data");
    } finally {
      dropTableQuietly(fqOld);
      dropTableQuietly(fqNew);
    }
  }

  @Test
  @DisplayName("4B.9 Non-Iceberg ALTER TABLE ... RENAME TO fails")
  public void testHiveRenameTableUnsupported() {
    Assumptions.assumeTrue(
        System.getProperty("glue.hive-jars-dir") != null,
        "Skipping 4B.9: glue.hive-jars-dir not set (patched Hive jars required for non-Iceberg)");

    String oldName = testRunPrefix + "_hive_rename_old";
    String newName = testRunPrefix + "_hive_rename_new";
    String fqOld = String.format("%s.%s.%s", GLUE_CATALOG_NAME, schemaName, oldName);
    String fqNew = String.format("%s.%s.%s", GLUE_CATALOG_NAME, schemaName, newName);

    try {
      spark.sql(String.format("CREATE TABLE %s (id INT, val STRING) USING PARQUET", fqOld));
      spark.sql(String.format("INSERT INTO %s VALUES (1, 'hive_data')", fqOld));

      Assertions.assertThrows(
          Exception.class,
          () ->
              spark.sql(
                  String.format("ALTER TABLE %s RENAME TO %s.%s", fqOld, schemaName, newName)),
          "Renaming a non-Iceberg Glue table must fail");

      // Original table must still exist and be readable
      Assertions.assertTrue(
          glueCatalog.asTableCatalog().tableExists(NameIdentifier.of(schemaName, oldName)),
          "Original Hive table must still exist after a failed rename");
      List<Row> rows = spark.sql(String.format("SELECT * FROM %s", fqOld)).collectAsList();
      Assertions.assertEquals(1, rows.size(), "Original Hive table data must be intact");
      Assertions.assertEquals("hive_data", rows.get(0).getString(1));

      // The new name must NOT have been created
      Assertions.assertFalse(
          glueCatalog.asTableCatalog().tableExists(NameIdentifier.of(schemaName, newName)),
          "Rename target must not exist after a failed rename");

      LOG.info("4B.9 PASSED: non-Iceberg rename correctly rejected");
    } finally {
      dropTableQuietly(fqOld);
      dropTableQuietly(fqNew);
    }
  }

  @Test
  @DisplayName("4B.11 SHOW DATABASES IN glue works although catalog is absent from SHOW CATALOGS")
  public void testShowDatabasesOnLazilyRegisteredCatalog() {
    // The Glue catalog is registered lazily; it need not appear in SHOW CATALOGS.
    List<Row> catalogs = spark.sql("SHOW CATALOGS").collectAsList();
    Set<String> catalogNames =
        catalogs.stream().map(r -> r.getString(0)).collect(Collectors.toSet());
    LOG.info("SHOW CATALOGS returned: {}", catalogNames);

    // Explicitly listing databases in the Glue catalog must work (triggers lazy registration).
    List<Row> databases =
        spark.sql(String.format("SHOW DATABASES IN %s", GLUE_CATALOG_NAME)).collectAsList();
    Assertions.assertFalse(
        databases.isEmpty(), "SHOW DATABASES IN " + GLUE_CATALOG_NAME + " should not be empty");

    // SHOW DATABASES returns a single 'namespace' column; the test schema must be present.
    Set<String> dbNames = databases.stream().map(r -> r.getString(0)).collect(Collectors.toSet());
    Assertions.assertTrue(
        dbNames.contains(schemaName),
        "SHOW DATABASES should include the test schema '" + schemaName + "', got: " + dbNames);

    LOG.info("4B.11 PASSED: SHOW DATABASES works on lazily-registered Glue catalog");
  }

  @Test
  @DisplayName("4B.7 Iceberg ALTER TABLE ADD COLUMNS / rename column succeeds")
  public void testIcebergSchemaEvolution() {
    String tableName = testRunPrefix + "_iceberg_evolve";
    String fqTable = String.format("%s.%s.%s", GLUE_CATALOG_NAME, schemaName, tableName);

    try {
      // 1. Create an Iceberg table and confirm it routes to the Iceberg backend
      spark.sql(String.format("CREATE TABLE %s (id INT, name STRING) USING iceberg", fqTable));

      Table created =
          glueCatalog.asTableCatalog().loadTable(NameIdentifier.of(schemaName, tableName));
      assertIsIcebergTable(created, tableName);

      // 2. Insert a row under the original schema
      spark.sql(String.format("INSERT INTO %s VALUES (1, 'Alice')", fqTable));

      // 3. ADD COLUMNS — Iceberg schema evolution should succeed
      spark.sql(String.format("ALTER TABLE %s ADD COLUMNS (age INT)", fqTable));

      // The new column must be visible via the Gravitino client round-trip
      Table afterAdd =
          glueCatalog.asTableCatalog().loadTable(NameIdentifier.of(schemaName, tableName));
      Set<String> colsAfterAdd =
          Arrays.stream(afterAdd.columns()).map(Column::name).collect(Collectors.toSet());
      Assertions.assertTrue(
          colsAfterAdd.contains("age"),
          "Column 'age' should be present after ADD COLUMNS, got: " + colsAfterAdd);

      // The pre-existing row must read NULL for the newly added column
      List<Row> afterAddRows =
          spark.sql(String.format("SELECT id, name, age FROM %s", fqTable)).collectAsList();
      Assertions.assertEquals(1, afterAddRows.size(), "Expected the original row to remain");
      Assertions.assertEquals(1, afterAddRows.get(0).getInt(0), "id should be 1");
      Assertions.assertEquals("Alice", afterAddRows.get(0).getString(1), "name should be Alice");
      Assertions.assertTrue(
          afterAddRows.get(0).isNullAt(2),
          "pre-existing row should read NULL for new 'age' column");

      // Inserting a row that populates the new column must work
      spark.sql(String.format("INSERT INTO %s VALUES (2, 'Bob', 30)", fqTable));

      // 4. RENAME COLUMN — Iceberg supports column rename; data must be preserved
      spark.sql(String.format("ALTER TABLE %s RENAME COLUMN name TO full_name", fqTable));

      Table afterRename =
          glueCatalog.asTableCatalog().loadTable(NameIdentifier.of(schemaName, tableName));
      Set<String> colsAfterRename =
          Arrays.stream(afterRename.columns()).map(Column::name).collect(Collectors.toSet());
      Assertions.assertTrue(
          colsAfterRename.contains("full_name"),
          "Column should be renamed to 'full_name', got: " + colsAfterRename);
      Assertions.assertFalse(
          colsAfterRename.contains("name"),
          "Old column name 'name' should no longer exist, got: " + colsAfterRename);

      // Data must remain readable under the renamed column
      List<Row> renamedRows =
          spark
              .sql(String.format("SELECT id, full_name, age FROM %s ORDER BY id", fqTable))
              .collectAsList();
      Assertions.assertEquals(2, renamedRows.size(), "Expected 2 rows after second insert");
      Assertions.assertEquals("Alice", renamedRows.get(0).getString(1), "row 1 full_name");
      Assertions.assertTrue(renamedRows.get(0).isNullAt(2), "row 1 age should still be NULL");
      Assertions.assertEquals("Bob", renamedRows.get(1).getString(1), "row 2 full_name");
      Assertions.assertEquals(30, renamedRows.get(1).getInt(2), "row 2 age should be 30");

      LOG.info("4B.7 PASSED: Iceberg ADD COLUMNS and RENAME COLUMN succeed and preserve data");
    } finally {
      dropTableQuietly(fqTable);
    }
  }

  @Test
  @DisplayName("4B.10 Drop + recreate + reinsert Iceberg table yields no duplicate rows")
  public void testDropRecreateIcebergNoDuplicateRows() {
    String tableName = testRunPrefix + "_iceberg_recreate";
    String fqTable = String.format("%s.%s.%s", GLUE_CATALOG_NAME, schemaName, tableName);

    try {
      // 1. Create and populate the first incarnation of the table
      spark.sql(String.format("CREATE TABLE %s (id INT, name STRING) USING iceberg", fqTable));

      Table created =
          glueCatalog.asTableCatalog().loadTable(NameIdentifier.of(schemaName, tableName));
      assertIsIcebergTable(created, tableName);

      spark.sql(String.format("INSERT INTO %s VALUES (1, 'Alice'), (2, 'Bob')", fqTable));

      List<Row> firstRows =
          spark.sql(String.format("SELECT * FROM %s ORDER BY id", fqTable)).collectAsList();
      Assertions.assertEquals(2, firstRows.size(), "Expected 2 rows in the first incarnation");

      // 2. Drop the table (must purge underlying S3 data/metadata)
      spark.sql(String.format("DROP TABLE %s", fqTable));

      // 3. Recreate the table with the same identifier and reinsert the same rows
      spark.sql(String.format("CREATE TABLE %s (id INT, name STRING) USING iceberg", fqTable));

      Table recreated =
          glueCatalog.asTableCatalog().loadTable(NameIdentifier.of(schemaName, tableName));
      assertIsIcebergTable(recreated, tableName);

      spark.sql(String.format("INSERT INTO %s VALUES (1, 'Alice'), (2, 'Bob')", fqTable));

      // 4. The recreated table must contain ONLY the freshly inserted rows. If stale S3
      //    data from the dropped table were not cleaned, the count would be > 2 (duplicates).
      List<Row> recreatedRows =
          spark.sql(String.format("SELECT * FROM %s ORDER BY id", fqTable)).collectAsList();
      Assertions.assertEquals(
          2,
          recreatedRows.size(),
          "Recreated table must contain exactly 2 rows (no stale/duplicate data from the "
              + "previous incarnation); got: "
              + recreatedRows.size());
      Assertions.assertEquals(1, recreatedRows.get(0).getInt(0), "row 1 id should be 1");
      Assertions.assertEquals("Alice", recreatedRows.get(0).getString(1), "row 1 name");
      Assertions.assertEquals(2, recreatedRows.get(1).getInt(0), "row 2 id should be 2");
      Assertions.assertEquals("Bob", recreatedRows.get(1).getString(1), "row 2 name");

      // A direct count aggregate provides a second, scan-independent check.
      List<Row> countRows =
          spark.sql(String.format("SELECT COUNT(*) FROM %s", fqTable)).collectAsList();
      Assertions.assertEquals(
          2L, countRows.get(0).getLong(0), "COUNT(*) must be 2 after drop+recreate+reinsert");

      LOG.info("4B.10 PASSED: drop + recreate + reinsert Iceberg table yields no duplicate rows");
    } finally {
      dropTableQuietly(fqTable);
    }
  }

  @Test
  @DisplayName("4B.12 CREATE/ALTER/DROP DATABASE on Glue catalog round-trips properties")
  public void testCreateAlterDropDatabaseRoundTripsProperties() {
    String dbName = testRunPrefix + "_db_ddl";
    String warehouse =
        System.getProperty("glue.aws.warehouse", "s3://gravitino-glue-test/warehouse");
    String dbLocation = warehouse + "/" + dbName;

    boolean created = false;
    try {
      // 1. CREATE DATABASE with a custom location and DBPROPERTIES
      spark.sql(
          String.format(
              "CREATE DATABASE %s.%s LOCATION '%s' WITH DBPROPERTIES (k1='v1', k2='v2')",
              GLUE_CATALOG_NAME, dbName, dbLocation));
      created = true;

      // Verify via the Gravitino client that properties round-tripped through the server
      Schema afterCreate = glueCatalog.asSchemas().loadSchema(dbName);
      Assertions.assertNotNull(afterCreate, "Schema should exist after CREATE DATABASE");
      Map<String, String> createProps = afterCreate.properties();
      LOG.info("Gravitino schema properties after CREATE for {}: {}", dbName, createProps);
      Assertions.assertEquals(
          "v1",
          createProps.get("k1"),
          "Custom property k1 should round-trip; props: " + createProps);
      Assertions.assertEquals(
          "v2",
          createProps.get("k2"),
          "Custom property k2 should round-trip; props: " + createProps);

      // 2. ALTER DATABASE SET DBPROPERTIES — update an existing key and add a new one
      spark.sql(
          String.format(
              "ALTER DATABASE %s.%s SET DBPROPERTIES (k1='v1-updated', k3='v3')",
              GLUE_CATALOG_NAME, dbName));

      Schema afterAlter = glueCatalog.asSchemas().loadSchema(dbName);
      Map<String, String> alterProps = afterAlter.properties();
      LOG.info("Gravitino schema properties after ALTER for {}: {}", dbName, alterProps);
      Assertions.assertEquals(
          "v1-updated",
          alterProps.get("k1"),
          "Property k1 should be updated; props: " + alterProps);
      Assertions.assertEquals(
          "v3", alterProps.get("k3"), "New property k3 should be added; props: " + alterProps);
      Assertions.assertEquals(
          "v2", alterProps.get("k2"), "Untouched property k2 should remain; props: " + alterProps);

      // 3. DROP DATABASE — the schema must be removed from the server
      spark.sql(String.format("DROP DATABASE %s.%s", GLUE_CATALOG_NAME, dbName));
      created = false;
      Assertions.assertFalse(
          glueCatalog.asSchemas().schemaExists(dbName),
          "Schema should no longer exist after DROP DATABASE");

      LOG.info("4B.12 PASSED: CREATE/ALTER/DROP DATABASE round-trips properties correctly");
    } finally {
      if (created) {
        try {
          spark.sql(
              String.format("DROP DATABASE IF EXISTS %s.%s CASCADE", GLUE_CATALOG_NAME, dbName));
        } catch (Exception e) {
          LOG.warn("Failed to drop database {}.{}", GLUE_CATALOG_NAME, dbName, e);
        }
      }
    }
  }

  // ==================== Helper methods ====================

  /** Builds an OAuth2TokenProvider for the admin client using client_credentials grant. */
  private static OAuth2TokenProvider buildAdminTokenProvider() {
    String credential = oauth2ClientId + ":" + oauth2ClientSecret;
    return DefaultOAuth2TokenProvider.builder()
        .withUri(oauth2ServerUri)
        .withCredential(credential)
        .withScope(oauth2Scope)
        .withPath(oauth2TokenPath)
        .build();
  }

  /**
   * Set environment variable for the current process. Used to set GRAVITINO_VERSION_CHECK_DISABLED
   * so the Spark plugin's internal GravitinoClient skips version validation when the local client
   * version is newer than the deployed server.
   */
  @SuppressWarnings("unchecked")
  private static void setEnv(String key, String value) {
    try {
      Map<String, String> env = System.getenv();
      Class<?> cl = env.getClass();
      Field field = cl.getDeclaredField("m");
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

  /** Asserts that the Gravitino table is recognized as an Iceberg table with metadata_location. */
  private static void assertIsIcebergTable(Table table, String context) {
    Map<String, String> props = table.properties();
    String tableFormat = props.get("table-format");
    String tableType = props.get("table_type");
    boolean isIceberg =
        "ICEBERG".equalsIgnoreCase(tableFormat) || "ICEBERG".equalsIgnoreCase(tableType);
    Assertions.assertTrue(
        isIceberg,
        String.format(
            "[%s] Expected Iceberg format but got table-format='%s', table_type='%s'. Props: %s",
            context, tableFormat, tableType, props));
    Assertions.assertNotNull(
        props.get("metadata_location"),
        String.format("[%s] metadata_location must be present for Iceberg table", context));
  }

  /** Drops a table via Spark SQL, swallowing any exceptions. */
  private static void dropTableQuietly(String fqTable) {
    try {
      spark.sql(String.format("DROP TABLE IF EXISTS %s", fqTable));
    } catch (Exception e) {
      LOG.warn("Failed to drop table {}", fqTable, e);
    }
  }

  /**
   * Builds a native Iceberg {@link GlueCatalog} wired with the same AWS credentials and region as
   * the Gravitino Glue catalog. Used by 4B.5 to create an Iceberg table that bypasses the Gravitino
   * Spark connector, so the table carries only {@code table_type=ICEBERG} (no {@code
   * table-format}).
   */
  private static GlueCatalog buildNativeIcebergGlueCatalog() {
    GlueCatalog catalog = new GlueCatalog();
    Map<String, String> props = new HashMap<>();
    props.put(CatalogProperties.WAREHOUSE_LOCATION, glueWarehouse);
    props.put(GluePropertiesConverter.CLIENT_REGION, glueAwsRegion);
    if (glueAwsCatalogId != null && !glueAwsCatalogId.isEmpty()) {
      props.put(GluePropertiesConverter.GLUE_ID, glueAwsCatalogId);
    }
    if (glueAwsEndpoint != null && !glueAwsEndpoint.isEmpty()) {
      props.put(GluePropertiesConverter.GLUE_ENDPOINT, glueAwsEndpoint);
    }
    // Reuse the connector's credentials provider so static keys flow into the native catalog.
    props.put(
        GluePropertiesConverter.CLIENT_CREDENTIALS_PROVIDER,
        GluePropertiesConverter.GRAVITINO_GLUE_CREDENTIALS_PROVIDER);
    props.put("client.credentials-provider.access-key-id", glueAwsAccessKey);
    props.put("client.credentials-provider.secret-access-key", glueAwsSecretKey);
    catalog.initialize("native_glue_4b5", props);
    return catalog;
  }
}
