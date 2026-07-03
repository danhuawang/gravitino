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
package org.apache.gravitino.integration.test.oauth2.view;

import com.google.common.collect.Maps;
import com.google.errorprone.annotations.FormatMethod;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.ResultKind;
import org.apache.flink.table.api.TableEnvironment;
import org.apache.flink.table.api.TableResult;
import org.apache.flink.table.api.internal.TableEnvironmentImpl;
import org.apache.flink.table.catalog.CatalogBaseTable;
import org.apache.flink.table.catalog.ObjectPath;
import org.apache.flink.table.catalog.exceptions.TableNotExistException;
import org.apache.flink.types.Row;
import org.apache.flink.util.CloseableIterator;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.catalog.lakehouse.iceberg.IcebergConstants;
import org.apache.gravitino.client.DefaultOAuth2TokenProvider;
import org.apache.gravitino.client.GravitinoAdminClient;
import org.apache.gravitino.client.GravitinoMetalake;
import org.apache.gravitino.client.OAuth2TokenProvider;
import org.apache.gravitino.rel.Column;
import org.apache.gravitino.rel.Representation;
import org.apache.gravitino.rel.SQLRepresentation;
import org.apache.gravitino.rel.View;
import org.apache.gravitino.rel.ViewCatalog;
import org.apache.gravitino.rel.ViewChange;
import org.apache.gravitino.rel.types.Types;
import org.apache.gravitino.storage.S3Properties;
import org.apache.gravitino.utils.RandomNameUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * E2E integration test for the Flink connector Iceberg <b>Hive backend</b> view support against a
 * deployed Gravitino service authenticated with OAuth2.
 *
 * <p>This test creates its own metalake and Iceberg catalog with {@code catalog-backend=hive}
 * pointing to a Hive Metastore, exercising the same test cases as {@link
 * FlinkConnectorViewIcebergRestOAuth2IT}.
 *
 * <p>Test cases covered:
 *
 * <ul>
 *   <li><b>TC-P0-01</b> — {@code DROP VIEW} succeeds via Flink.
 *   <li><b>TC-P0-03</b> — {@code DROP TABLE}/{@code DROP VIEW} on a non-existent object honours
 *       {@code IF EXISTS}.
 *   <li><b>TC-P0-04</b> — View SQL dialect fallback.
 *   <li><b>TC-P0-05</b> — {@code getTable} table&rarr;view fallback and exception precision.
 *   <li><b>TC-P0-06</b> — Schema cleanup with views present.
 *   <li><b>TC-P0-07</b> — {@code tableExists} falls back from table to view catalog.
 *   <li><b>TC-P0-08</b> — {@code ALTER VIEW ... RENAME} uses the table&rarr;view branch.
 *   <li><b>TC-P1-01..07</b> — functional coverage.
 *   <li>Cross-client consistency.
 * </ul>
 */
@DisplayName("Flink Connector Iceberg Hive Backend View OAuth2 E2E")
public class FlinkConnectorViewIcebergHiveOAuth2IT {

  private static final Logger LOG =
      LoggerFactory.getLogger(FlinkConnectorViewIcebergHiveOAuth2IT.class);

  private static final String ICEBERG_CATALOG_NAME = "iceberg_hive_view_oauth2";

  private static GravitinoAdminClient adminClient;
  private static GravitinoMetalake metalake;
  private static Catalog icebergCatalog;
  private static TableEnvironment tableEnv;

  private static String gravitinoUri;
  private static String metalakeName;
  private static String schemaName;
  private static String hiveMetastoreUri;
  private static String warehouse;

  // S3 settings.
  private static String s3Endpoint;
  private static String s3AccessKey;
  private static String s3SecretKey;
  private static String s3Region;

  // OAuth2 settings.
  private static String oauth2ServerUri;
  private static String oauth2ClientId;
  private static String oauth2ClientSecret;
  private static String oauth2TokenPath;
  private static String oauth2Scope;

  @BeforeAll
  public static void setup() {
    oauth2ServerUri = System.getenv("OAUTH2_SERVER_URI");
    oauth2ClientId = System.getenv("OAUTH2_CLIENT_ID");
    oauth2ClientSecret = System.getenv("OAUTH2_CLIENT_SECRET");
    Assumptions.assumeTrue(
        oauth2ServerUri != null && !oauth2ServerUri.isEmpty(),
        "Skipping: OAUTH2_SERVER_URI not set");
    Assumptions.assumeTrue(
        oauth2ClientId != null && !oauth2ClientId.isEmpty(), "Skipping: OAUTH2_CLIENT_ID not set");
    Assumptions.assumeTrue(
        oauth2ClientSecret != null && !oauth2ClientSecret.isEmpty(),
        "Skipping: OAUTH2_CLIENT_SECRET not set");

    gravitinoUri = System.getProperty("gravitino.uri", "http://localhost:30090");
    hiveMetastoreUri = System.getProperty("hive.metastore.uri", "thrift://localhost:30083");
    warehouse =
        System.getProperty("iceberg.warehouse", "s3a://gravitino-glue-test/iceberg/warehouse");

    // S3 filesystem credentials.
    s3Endpoint = System.getProperty("s3.endpoint", "http://s3.us-east-1.amazonaws.com");
    s3AccessKey = System.getProperty("s3.access.key", "minioadmin");
    s3SecretKey = System.getProperty("s3.secret.key", "minioadmin");
    s3Region = System.getProperty("s3.region", "us-east-1");

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

    // --- 1. Connect to Gravitino and create a new metalake + Iceberg (Hive backend) catalog ---
    adminClient =
        GravitinoAdminClient.builder(gravitinoUri)
            .withOAuth(buildTokenProvider())
            .withVersionCheckDisabled()
            .build();

    metalakeName = RandomNameUtils.genRandomName("iceberg_hive_view_e2e");
    metalake =
        adminClient.createMetalake(
            metalakeName, "Iceberg Hive backend view OAuth2 E2E", Collections.emptyMap());

    Map<String, String> icebergProps = Maps.newHashMap();
    icebergProps.put(IcebergConstants.CATALOG_BACKEND, "hive");
    icebergProps.put(IcebergConstants.URI, hiveMetastoreUri);
    icebergProps.put(IcebergConstants.WAREHOUSE, warehouse);
    // S3 IO configuration for Iceberg to read/write data files.
    icebergProps.put(IcebergConstants.IO_IMPL, "org.apache.iceberg.aws.s3.S3FileIO");
    icebergProps.put(S3Properties.GRAVITINO_S3_ENDPOINT, s3Endpoint);
    icebergProps.put(S3Properties.GRAVITINO_S3_ACCESS_KEY_ID, s3AccessKey);
    icebergProps.put(S3Properties.GRAVITINO_S3_SECRET_ACCESS_KEY, s3SecretKey);
    icebergProps.put(S3Properties.GRAVITINO_S3_REGION, s3Region);
    icebergCatalog =
        metalake.createCatalog(
            ICEBERG_CATALOG_NAME,
            Catalog.Type.RELATIONAL,
            "lakehouse-iceberg",
            "Iceberg Hive backend catalog for Flink view E2E",
            icebergProps);

    schemaName = RandomNameUtils.genRandomName("iv").toLowerCase() + "_db";
    icebergCatalog
        .asSchemas()
        .createSchema(schemaName, "Flink Iceberg Hive backend view e2e db", Collections.emptyMap());

    // --- 2. Build Flink TableEnvironment with Gravitino catalog store ---
    Configuration configuration = new Configuration();
    configuration.setString("table.catalog-store.kind", "gravitino");
    configuration.setString("table.catalog-store.gravitino.gravitino.metalake", metalakeName);
    configuration.setString("table.catalog-store.gravitino.gravitino.uri", gravitinoUri);
    configuration.setString("table.catalog-store.gravitino.gravitino.client.auth.type", "oauth2");
    configuration.setString(
        "table.catalog-store.gravitino.gravitino.client.oauth2.serverUri", oauth2ServerUri);
    configuration.setString(
        "table.catalog-store.gravitino.gravitino.client.oauth2.tokenPath", oauth2TokenPath);
    configuration.setString(
        "table.catalog-store.gravitino.gravitino.client.oauth2.credential",
        oauth2ClientId + ":" + oauth2ClientSecret);
    configuration.setString(
        "table.catalog-store.gravitino.gravitino.client.oauth2.scope", oauth2Scope);

    // Hadoop S3A for Flink client-side data IO.
    configuration.setString("fs.s3a.access.key", s3AccessKey);
    configuration.setString("fs.s3a.secret.key", s3SecretKey);
    configuration.setString("fs.s3a.endpoint", s3Endpoint);

    EnvironmentSettings settings =
        EnvironmentSettings.newInstance().withConfiguration(configuration).inBatchMode().build();
    tableEnv = TableEnvironment.create(settings);

    LOG.info(
        "Setup complete: metalake={}, catalog={}, schema={}",
        metalakeName,
        ICEBERG_CATALOG_NAME,
        schemaName);
  }

  @AfterAll
  public static void teardown() {
    if (tableEnv != null) {
      try {
        ((TableEnvironmentImpl) tableEnv).getCatalogManager().close();
      } catch (Exception e) {
        LOG.warn("Failed to close Flink catalog manager", e);
      }
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

  // ---------------------------------------------------------------------------------------------
  // P0 — bug-prone hot spots
  // ---------------------------------------------------------------------------------------------

  /** TC-P0-01: {@code DROP VIEW} issued through Flink succeeds and removes the view. */
  @Test
  @DisplayName("TC-P0-01 Iceberg Hive DROP VIEW succeeds via Flink")
  public void testIcebergDropViewSucceeds() {
    String tableName = "tc_p0_01_base";
    String viewName = "tc_p0_01_view";
    useCatalogAndSchema();

    try {
      assertSuccess(createBaseTable(tableName, "id INT"));
      assertSuccess(sql("CREATE VIEW %s AS SELECT id FROM %s", viewName, tableName));

      ViewCatalog viewCatalog = icebergCatalog.asViewCatalog();
      Assertions.assertTrue(viewCatalog.viewExists(NameIdentifier.of(schemaName, viewName)));

      assertSuccess(sql("DROP VIEW %s", viewName));

      Assertions.assertFalse(viewCatalog.viewExists(NameIdentifier.of(schemaName, viewName)));
    } finally {
      dropQuietly(viewName, tableName);
    }
  }

  /** TC-P0-03: {@code DROP} on a non-existent object honours the {@code IF EXISTS} branch logic. */
  @Test
  @DisplayName("TC-P0-03 DROP on a non-existent object honours IF EXISTS")
  public void testDropNonExistentObject() {
    useCatalogAndSchema();
    String missing = "tc_p0_03_missing";

    Assertions.assertThrows(Exception.class, () -> sql("DROP TABLE %s", missing));
    assertSuccess(sql("DROP TABLE IF EXISTS %s", missing));
    assertSuccess(sql("DROP VIEW IF EXISTS %s", missing));
  }

  /**
   * TC-P0-05: {@code getTable} table&rarr;view fallback. Flink's {@code getTable} must return a
   * view as {@code TableKind.VIEW} and a table as {@code TableKind.TABLE}. A missing object must
   * raise {@code TableNotExistException}.
   */
  @Test
  @DisplayName("TC-P0-05 getTable table->view fallback and exception precision")
  public void testGetTableFallback() {
    String tableName = "tc_p0_05_base";
    String viewName = "tc_p0_05_view";
    useCatalogAndSchema();

    try {
      assertSuccess(createBaseTable(tableName, "id INT, name STRING"));
      assertSuccess(sql("CREATE VIEW %s AS SELECT id, name FROM %s", viewName, tableName));

      CatalogBaseTable viewResult = flinkCatalogTable(viewName);
      Assertions.assertEquals(CatalogBaseTable.TableKind.VIEW, viewResult.getTableKind());

      CatalogBaseTable tableResult = flinkCatalogTable(tableName);
      Assertions.assertEquals(CatalogBaseTable.TableKind.TABLE, tableResult.getTableKind());

      Assertions.assertThrows(
          TableNotExistException.class, () -> flinkCatalogTable("tc_p0_05_missing"));
    } catch (TableNotExistException e) {
      Assertions.fail("unexpected: " + e.getMessage());
    } finally {
      dropQuietly(viewName, tableName);
    }
  }

  /**
   * TC-P0-06: Schema cleanup with views present. Iceberg does not support {@code DROP CASCADE}, so
   * views must be dropped explicitly before the database can be removed.
   */
  @Test
  @DisplayName("TC-P0-06 Schema cleanup with views present (Iceberg no cascade)")
  public void testSchemaCleanupWithViews() {
    String subSchema = RandomNameUtils.genRandomName("tc_p0_06").toLowerCase() + "_db";
    useCatalogAndSchema();

    try {
      assertSuccess(sql("CREATE DATABASE %s", subSchema));
      tableEnv.useDatabase(subSchema);

      assertSuccess(createBaseTable("cleanup_base", "id INT"));
      assertSuccess(sql("CREATE VIEW cleanup_view AS SELECT id FROM cleanup_base"));

      assertSuccess(sql("DROP VIEW cleanup_view"));
      assertSuccess(sql("DROP TABLE cleanup_base"));

      tableEnv.useDatabase(schemaName);
      assertSuccess(sql("DROP DATABASE %s", subSchema));

      Assertions.assertFalse(icebergCatalog.asSchemas().schemaExists(subSchema));
    } finally {
      tableEnv.useDatabase(schemaName);
      try {
        sql("DROP DATABASE IF EXISTS %s", subSchema);
      } catch (Exception ignored) {
      }
    }
  }

  /** TC-P0-07: {@code tableExists} falls back from the table catalog to the view catalog. */
  @Test
  @DisplayName("TC-P0-07 tableExists falls back from table to view catalog")
  public void testTableExistsFallsBackToView() {
    String tableName = "tc_p0_07_base";
    String viewName = "tc_p0_07_view";
    useCatalogAndSchema();

    try {
      assertSuccess(createBaseTable(tableName, "id INT"));
      assertSuccess(sql("CREATE VIEW %s AS SELECT id FROM %s", viewName, tableName));

      Assertions.assertTrue(flinkCatalogTableExists(viewName));
      Assertions.assertTrue(flinkCatalogTableExists(tableName));
      Assertions.assertFalse(flinkCatalogTableExists("tc_p0_07_missing"));
    } finally {
      dropQuietly(viewName, tableName);
    }
  }

  /**
   * TC-P0-08: {@code ALTER VIEW ... RENAME} uses the table&rarr;view branch. Renaming a view to a
   * fresh name succeeds, while renaming a view onto an existing table name is rejected.
   */
  @Test
  @DisplayName("TC-P0-08 ALTER VIEW RENAME uses table->view branch and rejects conflicts")
  public void testAlterViewRename() {
    String tableName = "tc_p0_08_base";
    String viewName = "tc_p0_08_view_src";
    String renamed = "tc_p0_08_view_dst";
    useCatalogAndSchema();

    try {
      assertSuccess(createBaseTable(tableName, "id INT"));
      assertSuccess(sql("CREATE VIEW %s AS SELECT id FROM %s", viewName, tableName));

      assertSuccess(sql("ALTER VIEW %s RENAME TO %s", viewName, renamed));

      ViewCatalog viewCatalog = icebergCatalog.asViewCatalog();
      Assertions.assertFalse(viewCatalog.viewExists(NameIdentifier.of(schemaName, viewName)));
      Assertions.assertTrue(viewCatalog.viewExists(NameIdentifier.of(schemaName, renamed)));

      Assertions.assertThrows(
          Exception.class, () -> sql("ALTER VIEW %s RENAME TO %s", renamed, tableName));
    } finally {
      dropQuietly(viewName, null);
      dropQuietly(renamed, tableName);
    }
  }

  /**
   * TC-P0-04: View SQL dialect fallback. A view created via Flink should be stored with the {@code
   * flink} or {@code hive} dialect and loaded back successfully through Flink.
   */
  @Test
  @DisplayName("TC-P0-04 View SQL dialect fallback (flink dialect path)")
  public void testViewSqlDialectFlink() {
    String tableName = "tc_p0_04_base";
    String viewName = "tc_p0_04_view";
    useCatalogAndSchema();

    try {
      assertSuccess(createBaseTable(tableName, "id INT, name STRING"));
      assertSuccess(sql("CREATE VIEW %s AS SELECT id, name FROM %s", viewName, tableName));

      View view = icebergCatalog.asViewCatalog().loadView(NameIdentifier.of(schemaName, viewName));
      Assertions.assertTrue(view.representations().length >= 1);
      SQLRepresentation rep = (SQLRepresentation) view.representations()[0];
      Assertions.assertTrue(
          "flink".equalsIgnoreCase(rep.dialect()) || "hive".equalsIgnoreCase(rep.dialect()),
          "expected flink or hive dialect, got: " + rep.dialect());

      CatalogBaseTable loaded = flinkCatalogTable(viewName);
      Assertions.assertEquals(CatalogBaseTable.TableKind.VIEW, loaded.getTableKind());
    } catch (TableNotExistException e) {
      Assertions.fail("view should be loadable: " + e.getMessage());
    } finally {
      dropQuietly(viewName, tableName);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // P1 — functional coverage
  // ---------------------------------------------------------------------------------------------

  /**
   * TC-P1-01: {@code CREATE VIEW} with a comment is reflected both in Gravitino's {@code
   * ViewCatalog.loadView} and in the Flink catalog as a {@code TableKind.VIEW}.
   */
  @Test
  @DisplayName("TC-P1-01 CREATE VIEW with comment is visible via Gravitino and Flink")
  public void testCreateViewWithComment() {
    String tableName = "tc_p1_01_base";
    String viewName = "tc_p1_01_view";
    useCatalogAndSchema();

    try {
      assertSuccess(createBaseTable(tableName, "id INT, name STRING"));
      assertSuccess(
          sql(
              "CREATE VIEW %s COMMENT 'iceberg hive view comment' AS SELECT id, name FROM %s",
              viewName, tableName));

      ViewCatalog viewCatalog = icebergCatalog.asViewCatalog();
      View view = viewCatalog.loadView(NameIdentifier.of(schemaName, viewName));
      Assertions.assertEquals(viewName, view.name());
      Assertions.assertEquals("iceberg hive view comment", view.comment());
      Assertions.assertTrue(view.representations().length >= 1);
      Assertions.assertInstanceOf(SQLRepresentation.class, view.representations()[0]);

      Assertions.assertEquals(
          CatalogBaseTable.TableKind.VIEW, flinkCatalogTable(viewName).getTableKind());
    } catch (TableNotExistException e) {
      Assertions.fail("view should exist: " + e.getMessage());
    } finally {
      dropQuietly(viewName, tableName);
    }
  }

  /** TC-P1-02: re-issuing {@code CREATE VIEW IF NOT EXISTS} succeeds and leaves the view intact. */
  @Test
  @DisplayName("TC-P1-02 CREATE VIEW IF NOT EXISTS is idempotent")
  public void testCreateViewIfNotExists() {
    String tableName = "tc_p1_02_base";
    String viewName = "tc_p1_02_view";
    useCatalogAndSchema();

    try {
      assertSuccess(createBaseTable(tableName, "id INT"));
      assertSuccess(sql("CREATE VIEW %s AS SELECT id FROM %s", viewName, tableName));
      assertSuccess(sql("CREATE VIEW IF NOT EXISTS %s AS SELECT id FROM %s", viewName, tableName));

      Assertions.assertTrue(
          icebergCatalog.asViewCatalog().viewExists(NameIdentifier.of(schemaName, viewName)));
    } finally {
      dropQuietly(viewName, tableName);
    }
  }

  /**
   * TC-P1-03: {@code ALTER VIEW ... AS} replaces the view body; the stored SQL representation must
   * reflect the new projection.
   */
  @Test
  @DisplayName("TC-P1-03 ALTER VIEW AS replaces the view body")
  public void testAlterViewReplaceBody() {
    String tableName = "tc_p1_03_base";
    String viewName = "tc_p1_03_view";
    useCatalogAndSchema();

    try {
      assertSuccess(createBaseTable(tableName, "id INT, name STRING"));
      assertSuccess(sql("CREATE VIEW %s AS SELECT id FROM %s", viewName, tableName));
      assertSuccess(sql("ALTER VIEW %s AS SELECT id, name FROM %s", viewName, tableName));

      View view = icebergCatalog.asViewCatalog().loadView(NameIdentifier.of(schemaName, viewName));
      SQLRepresentation rep = (SQLRepresentation) view.representations()[0];
      Assertions.assertTrue(rep.sql().contains("id") && rep.sql().contains("name"));
    } finally {
      dropQuietly(viewName, tableName);
    }
  }

  /** TC-P1-04: {@code ALTER VIEW} set / reset properties. */
  @Test
  @org.junit.jupiter.api.Disabled(
      "Flink SQL does not support ALTER VIEW SET/RESET properties syntax")
  @DisplayName("TC-P1-04 ALTER VIEW set/reset properties")
  public void testAlterViewSetResetProperties() {
    String tableName = "tc_p1_04_base";
    String viewName = "tc_p1_04_view";
    useCatalogAndSchema();

    try {
      assertSuccess(createBaseTable(tableName, "id INT"));
      assertSuccess(sql("CREATE VIEW %s AS SELECT id FROM %s", viewName, tableName));

      assertSuccess(sql("ALTER VIEW %s SET ('k1'='v1')", viewName));
      View view = icebergCatalog.asViewCatalog().loadView(NameIdentifier.of(schemaName, viewName));
      Assertions.assertEquals("v1", view.properties().get("k1"));

      assertSuccess(sql("ALTER VIEW %s RESET ('k1')", viewName));
      view = icebergCatalog.asViewCatalog().loadView(NameIdentifier.of(schemaName, viewName));
      Assertions.assertFalse(view.properties().containsKey("k1"));
    } finally {
      dropQuietly(viewName, tableName);
    }
  }

  /** TC-P1-05: querying through a view applies the view's projection and filter. */
  @Test
  @DisplayName("TC-P1-05 Query through a view returns filtered, ordered rows")
  public void testQueryThroughView() {
    String tableName = "tc_p1_05_base";
    String viewName = "tc_p1_05_view";
    useCatalogAndSchema();

    try {
      assertSuccess(createBaseTable(tableName, "id INT, name STRING"));
      assertSuccess(
          sql("CREATE VIEW %s AS SELECT id, name FROM %s WHERE id > 1", viewName, tableName));
      sql("INSERT INTO %s VALUES (1, 'alice'), (2, 'bob'), (3, 'carol')", tableName).await();

      List<Row> rows = collectRows(sql("SELECT * FROM %s ORDER BY id", viewName));
      Assertions.assertEquals(Arrays.asList(Row.of(2, "bob"), Row.of(3, "carol")), rows);
    } catch (Exception e) {
      throw new AssertionError("query through view failed", e);
    } finally {
      dropQuietly(viewName, tableName);
    }
  }

  /** TC-P1-06: {@code listViews}/{@code listTables} isolation. */
  @Test
  @DisplayName("TC-P1-06 listViews / listTables isolation")
  public void testListViewsAndTablesIsolation() {
    String tableName = "tc_p1_06_base";
    String view1 = "tc_p1_06_view_1";
    String view2 = "tc_p1_06_view_2";
    useCatalogAndSchema();

    try {
      assertSuccess(createBaseTable(tableName, "id INT"));
      assertSuccess(sql("CREATE VIEW %s AS SELECT id FROM %s", view1, tableName));
      assertSuccess(sql("CREATE VIEW %s AS SELECT id FROM %s", view2, tableName));

      List<String> views = Arrays.asList(tableEnv.listViews());
      Assertions.assertTrue(views.contains(view1));
      Assertions.assertTrue(views.contains(view2));
      Assertions.assertFalse(views.contains(tableName));

      List<String> tables = Arrays.asList(tableEnv.listTables());
      Assertions.assertTrue(tables.contains(tableName));
      Assertions.assertFalse(tables.contains(view1));

      NameIdentifier[] gravitinoViews =
          icebergCatalog.asViewCatalog().listViews(Namespace.of(schemaName));
      List<String> names =
          Arrays.stream(gravitinoViews).map(NameIdentifier::name).collect(Collectors.toList());
      Assertions.assertTrue(names.contains(view1));
      Assertions.assertTrue(names.contains(view2));
    } finally {
      dropQuietly(view1, null);
      dropQuietly(view2, tableName);
    }
  }

  /** TC-P1-07: {@code DROP VIEW IF EXISTS} is idempotent; a second drop succeeds without error. */
  @Test
  @DisplayName("TC-P1-07 DROP VIEW IF EXISTS is idempotent")
  public void testDropViewIfExistsIdempotent() {
    String tableName = "tc_p1_07_base";
    String viewName = "tc_p1_07_view";
    useCatalogAndSchema();

    try {
      assertSuccess(createBaseTable(tableName, "id INT"));
      assertSuccess(sql("CREATE VIEW %s AS SELECT id FROM %s", viewName, tableName));
      assertSuccess(sql("DROP VIEW %s", viewName));
      assertSuccess(sql("DROP VIEW IF EXISTS %s", viewName));
    } finally {
      dropQuietly(viewName, tableName);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Partitioned table + view scenarios
  // ---------------------------------------------------------------------------------------------

  /**
   * TC-PART-01: Create a view on a partitioned Iceberg table. The view should be queryable and
   * return correct results filtered by the view predicate.
   */
  @Test
  @DisplayName("TC-PART-01 View on partitioned table returns correct filtered results")
  public void testViewOnPartitionedTable() {
    String tableName = "tc_part_01_orders";
    String viewName = "tc_part_01_view";
    useCatalogAndSchema();

    try {
      assertSuccess(
          sql(
              "CREATE TABLE %s (id INT, ts TIMESTAMP(6), dt STRING) PARTITIONED BY (dt)",
              tableName));
      sql(
              "INSERT INTO %s VALUES "
                  + "(1, TIMESTAMP '2024-01-01 12:00:00', '2024-01-01'), "
                  + "(2, TIMESTAMP '2024-01-02 08:00:00', '2024-01-02'), "
                  + "(3, TIMESTAMP '2024-01-01 18:00:00', '2024-01-01')",
              tableName)
          .await();

      assertSuccess(
          sql("CREATE VIEW %s AS SELECT id, ts, dt FROM %s WHERE id > 1", viewName, tableName));

      List<Row> rows = collectRows(sql("SELECT id FROM %s ORDER BY id", viewName));
      Assertions.assertEquals(
          Arrays.asList(Row.of(2), Row.of(3)),
          rows,
          "view on partitioned table should return filtered rows");
    } catch (Exception e) {
      throw new AssertionError("view on partitioned table failed", e);
    } finally {
      dropQuietly(viewName, tableName);
    }
  }

  /**
   * TC-PART-02: A view over a partitioned table correctly reflects new data inserted after the view
   * is created. This verifies the view is not a snapshot but a live reference.
   */
  @Test
  @DisplayName("TC-PART-02 View on partitioned table reflects newly inserted data")
  public void testViewOnPartitionedTableReflectsNewData() {
    String tableName = "tc_part_02_orders";
    String viewName = "tc_part_02_view";
    useCatalogAndSchema();

    try {
      assertSuccess(
          sql(
              "CREATE TABLE %s (id INT, ts TIMESTAMP(6), dt STRING) PARTITIONED BY (dt)",
              tableName));
      sql("INSERT INTO %s VALUES (1, TIMESTAMP '2024-01-01 12:00:00', '2024-01-01')", tableName)
          .await();

      assertSuccess(sql("CREATE VIEW %s AS SELECT id, ts, dt FROM %s", viewName, tableName));

      // Initial query: 1 row.
      List<Row> rows1 = collectRows(sql("SELECT id FROM %s ORDER BY id", viewName));
      Assertions.assertEquals(1, rows1.size());

      // Insert more data into different partitions.
      sql(
              "INSERT INTO %s VALUES "
                  + "(2, TIMESTAMP '2024-01-02 09:00:00', '2024-01-02'), "
                  + "(3, TIMESTAMP '2024-01-03 10:00:00', '2024-01-03')",
              tableName)
          .await();

      // The view should now reflect all 3 rows.
      List<Row> rows2 = collectRows(sql("SELECT id FROM %s ORDER BY id", viewName));
      Assertions.assertEquals(
          Arrays.asList(Row.of(1), Row.of(2), Row.of(3)),
          rows2,
          "view should reflect newly inserted data across partitions");
    } catch (Exception e) {
      throw new AssertionError("view on partitioned table new data test failed", e);
    } finally {
      dropQuietly(viewName, tableName);
    }
  }

  /**
   * TC-PART-03: {@code DROP VIEW} on a view referencing a partitioned table does not affect the
   * underlying partitioned table data.
   */
  @Test
  @DisplayName("TC-PART-03 DROP VIEW does not affect underlying partitioned table")
  public void testDropViewDoesNotAffectPartitionedTable() {
    String tableName = "tc_part_03_orders";
    String viewName = "tc_part_03_view";
    useCatalogAndSchema();

    try {
      assertSuccess(
          sql(
              "CREATE TABLE %s (id INT, ts TIMESTAMP(6), dt STRING) PARTITIONED BY (dt)",
              tableName));
      sql(
              "INSERT INTO %s VALUES "
                  + "(1, TIMESTAMP '2024-01-01 12:00:00', '2024-01-01'), "
                  + "(2, TIMESTAMP '2024-01-02 08:00:00', '2024-01-02')",
              tableName)
          .await();

      assertSuccess(sql("CREATE VIEW %s AS SELECT id, ts, dt FROM %s", viewName, tableName));
      assertSuccess(sql("DROP VIEW %s", viewName));

      // The base table data should still be intact.
      List<Row> rows = collectRows(sql("SELECT id FROM %s ORDER BY id", tableName));
      Assertions.assertEquals(
          Arrays.asList(Row.of(1), Row.of(2)),
          rows,
          "partitioned table data should be intact after DROP VIEW");
    } catch (Exception e) {
      throw new AssertionError("drop view partitioned table test failed", e);
    } finally {
      dropQuietly(viewName, tableName);
    }
  }

  /**
   * TC-PART-04: {@code ALTER VIEW AS} can update a view's body to add a partition-column filter on
   * a partitioned table.
   */
  @Test
  @DisplayName("TC-PART-04 ALTER VIEW AS on partitioned table updates filter")
  public void testAlterViewOnPartitionedTable() {
    String tableName = "tc_part_04_orders";
    String viewName = "tc_part_04_view";
    useCatalogAndSchema();

    try {
      assertSuccess(
          sql(
              "CREATE TABLE %s (id INT, ts TIMESTAMP(6), dt STRING) PARTITIONED BY (dt)",
              tableName));
      sql(
              "INSERT INTO %s VALUES "
                  + "(1, TIMESTAMP '2024-01-01 12:00:00', '2024-01-01'), "
                  + "(2, TIMESTAMP '2024-01-02 08:00:00', '2024-01-02'), "
                  + "(3, TIMESTAMP '2024-01-03 10:00:00', '2024-01-03')",
              tableName)
          .await();

      // Create view selecting all rows.
      assertSuccess(sql("CREATE VIEW %s AS SELECT id, ts, dt FROM %s", viewName, tableName));
      List<Row> allRows = collectRows(sql("SELECT id FROM %s ORDER BY id", viewName));
      Assertions.assertEquals(3, allRows.size());

      // Alter view to filter by partition column.
      assertSuccess(
          sql(
              "ALTER VIEW %s AS SELECT id, ts, dt FROM %s WHERE dt >= '2024-01-02'",
              viewName, tableName));

      List<Row> filteredRows = collectRows(sql("SELECT id FROM %s ORDER BY id", viewName));
      Assertions.assertEquals(
          Arrays.asList(Row.of(2), Row.of(3)),
          filteredRows,
          "altered view should only return rows matching the updated partition filter");
    } catch (Exception e) {
      throw new AssertionError("alter view on partitioned table failed", e);
    } finally {
      dropQuietly(viewName, tableName);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Cross-client consistency
  // ---------------------------------------------------------------------------------------------

  /**
   * Verifies that a view modified through the Gravitino Java API (bypassing the Flink connector) is
   * immediately visible to the Flink connector with the updated metadata.
   */
  @Test
  @DisplayName("Cross-client: Gravitino API modified view is visible in Flink")
  public void testGravitinoApiModifiedViewVisibleInFlink() {
    String tableName = "tc_cross_client_base";
    String viewName = "tc_cross_client_view";
    useCatalogAndSchema();

    try {
      assertSuccess(createBaseTable(tableName, "id INT, name STRING, age INT"));
      sql("INSERT INTO %s VALUES (1, 'alice', 30), (2, 'bob', 25), (3, 'carol', 35)", tableName)
          .await();
      assertSuccess(
          sql(
              "CREATE VIEW %s COMMENT 'original comment' AS SELECT id, name FROM %s",
              viewName, tableName));

      List<Row> originalRows = collectRows(sql("SELECT * FROM %s ORDER BY id", viewName));
      Assertions.assertEquals(3, originalRows.size());

      // Modify via Gravitino API.
      ViewCatalog viewCatalog = icebergCatalog.asViewCatalog();
      NameIdentifier viewIdent = NameIdentifier.of(schemaName, viewName);

      SQLRepresentation newRep =
          SQLRepresentation.builder()
              .withDialect("flink")
              .withSql(String.format("SELECT id, name, age FROM %s WHERE age > 26", tableName))
              .build();

      Column[] newColumns =
          new Column[] {
            Column.of("id", Types.IntegerType.get(), null),
            Column.of("name", Types.StringType.get(), null),
            Column.of("age", Types.IntegerType.get(), null)
          };

      viewCatalog.alterView(
          viewIdent,
          ViewChange.setProperty("modified_by", "gravitino_api"),
          ViewChange.replaceView(
              newColumns,
              new Representation[] {newRep},
              null,
              schemaName,
              "modified via gravitino api"));

      // Verify via Flink.
      CatalogBaseTable flinkView = flinkCatalogTable(viewName);
      Assertions.assertEquals(CatalogBaseTable.TableKind.VIEW, flinkView.getTableKind());
      Assertions.assertEquals("modified via gravitino api", flinkView.getComment());

      View reloadedView = viewCatalog.loadView(viewIdent);
      Assertions.assertEquals("gravitino_api", reloadedView.properties().get("modified_by"));

      List<Row> modifiedRows = collectRows(sql("SELECT * FROM %s ORDER BY id", viewName));
      Assertions.assertEquals(
          Arrays.asList(Row.of(1, "alice", 30), Row.of(3, "carol", 35)), modifiedRows);
    } catch (Exception e) {
      throw new AssertionError("cross-client consistency test failed: " + e.getMessage(), e);
    } finally {
      dropQuietly(viewName, tableName);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------------------------

  @FormatMethod
  private static TableResult sql(String sqlFmt, Object... args) {
    return tableEnv.executeSql(String.format(sqlFmt, args));
  }

  private static void assertSuccess(TableResult result) {
    Assertions.assertEquals(ResultKind.SUCCESS, result.getResultKind());
  }

  private static void useCatalogAndSchema() {
    tableEnv.useCatalog(ICEBERG_CATALOG_NAME);
    tableEnv.useDatabase(schemaName);
  }

  private static TableResult createBaseTable(String tableName, String columns) {
    return sql("CREATE TABLE %s (%s)", tableName, columns);
  }

  private static CatalogBaseTable flinkCatalogTable(String name) throws TableNotExistException {
    return tableEnv
        .getCatalog(ICEBERG_CATALOG_NAME)
        .orElseThrow(() -> new IllegalStateException("Flink catalog not registered"))
        .getTable(new ObjectPath(schemaName, name));
  }

  private static boolean flinkCatalogTableExists(String name) {
    return tableEnv
        .getCatalog(ICEBERG_CATALOG_NAME)
        .orElseThrow(() -> new IllegalStateException("Flink catalog not registered"))
        .tableExists(new ObjectPath(schemaName, name));
  }

  private static List<Row> collectRows(TableResult tableResult) {
    List<Row> rows = new ArrayList<>();
    try (CloseableIterator<Row> it = tableResult.collect()) {
      while (it.hasNext()) {
        rows.add(it.next());
      }
    } catch (Exception e) {
      throw new RuntimeException("Failed to collect query result rows", e);
    }
    return rows;
  }

  private static void dropQuietly(String viewName, String tableName) {
    if (viewName != null) {
      try {
        sql("DROP VIEW IF EXISTS %s", viewName);
      } catch (Exception e) {
        LOG.warn("cleanup: failed to drop view '{}'", viewName, e);
      }
    }
    if (tableName != null) {
      try {
        sql("DROP TABLE IF EXISTS %s", tableName);
      } catch (Exception e) {
        LOG.warn("cleanup: failed to drop table '{}'", tableName, e);
      }
    }
  }

  private static OAuth2TokenProvider buildTokenProvider() {
    return DefaultOAuth2TokenProvider.builder()
        .withUri(oauth2ServerUri)
        .withCredential(oauth2ClientId + ":" + oauth2ClientSecret)
        .withScope(oauth2Scope)
        .withPath(oauth2TokenPath)
        .build();
  }
}
