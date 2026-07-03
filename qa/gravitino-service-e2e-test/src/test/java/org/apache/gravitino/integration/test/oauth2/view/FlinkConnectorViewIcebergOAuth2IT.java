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

import com.google.errorprone.annotations.FormatMethod;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
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
 * E2E integration test for the Flink connector Iceberg view support (PR #11349) against a deployed
 * Gravitino service authenticated with OAuth2.
 *
 * <p>Implements the Iceberg-applicable test cases from {@code PR11349-view-test-plan.md}, driving
 * each scenario end to end through Flink SQL against a real Iceberg REST catalog:
 *
 * <ul>
 *   <li><b>TC-P0-03</b> — {@code DROP TABLE}/{@code DROP VIEW} on a non-existent object honours the
 *       {@code IF EXISTS} branch logic.
 *   <li><b>TC-P0-05</b> — {@code getTable} table&rarr;view fallback and exception precision.
 *   <li><b>TC-P0-06</b> — Schema cleanup with views present: Iceberg does not support {@code DROP
 *       CASCADE}, so views must be dropped explicitly before the database can be removed.
 *   <li><b>TC-P0-07</b> — {@code tableExists} falls back from the table catalog to the view
 *       catalog.
 *   <li><b>TC-P0-08</b> — {@code ALTER VIEW ... RENAME} uses the table&rarr;view branch and maps
 *       name conflicts to {@code TableAlreadyExistException}.
 *   <li><b>TC-P1-01..07</b> — functional coverage: {@code CREATE VIEW} (with comment), {@code
 *       CREATE VIEW IF NOT EXISTS}, {@code ALTER VIEW AS}, querying through a view, {@code
 *       listViews}/{@code listTables} isolation, and {@code DROP VIEW IF EXISTS} idempotency.
 * </ul>
 *
 * <p>The test is gated on OAuth2 configuration ({@code OAUTH2_SERVER_URI}, {@code
 * OAUTH2_CLIENT_ID}, {@code OAUTH2_CLIENT_SECRET}); it self-skips when those are absent so it does
 * not run in environments that are not configured for the deployed Gravitino service.
 */
@DisplayName("Flink Connector Iceberg View OAuth2 E2E (PR #11349)")
public class FlinkConnectorViewIcebergOAuth2IT {

  private static final Logger LOG =
      LoggerFactory.getLogger(FlinkConnectorViewIcebergOAuth2IT.class);

  private static GravitinoAdminClient adminClient;
  private static GravitinoMetalake metalake;
  private static Catalog icebergCatalog;
  private static TableEnvironment tableEnv;

  private static String gravitinoUri;
  private static String metalakeName;
  private static String icebergCatalogName;
  private static String schemaName;

  // S3 settings.
  private static String s3Endpoint;
  private static String s3AccessKey;
  private static String s3SecretKey;

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
    metalakeName = System.getProperty("gravitino.metalake", "test");
    icebergCatalogName = System.getProperty("gravitino.irc.catalog", "catalog_iceberg_s3_3");

    // S3 filesystem credentials (passed as system properties from the build).
    s3Endpoint = System.getProperty("s3.endpoint", "http://s3.us-east-1.amazonaws.com");
    s3AccessKey = System.getProperty("s3.access.key", "minioadmin");
    s3SecretKey = System.getProperty("s3.secret.key", "minioadmin");

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

    // --- 1. Connect to Gravitino over OAuth2 and load the pre-existing metalake + catalog ---
    adminClient =
        GravitinoAdminClient.builder(gravitinoUri)
            .withOAuth(buildAdminTokenProvider())
            .withVersionCheckDisabled()
            .build();

    metalake = adminClient.loadMetalake(metalakeName);
    icebergCatalog = metalake.loadCatalog(icebergCatalogName);

    schemaName = RandomNameUtils.genRandomName("iv").toLowerCase() + "_db";
    icebergCatalog
        .asSchemas()
        .createSchema(schemaName, "Flink Iceberg view OAuth2 e2e db", Collections.emptyMap());

    // --- 2. Build a Flink TableEnvironment backed by the Gravitino catalog store over OAuth2 ---
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

    // Hadoop S3A configuration for the local Flink/Iceberg client to read/write data files on S3.
    configuration.setString("fs.s3a.access.key", s3AccessKey);
    configuration.setString("fs.s3a.secret.key", s3SecretKey);
    configuration.setString("fs.s3a.endpoint", s3Endpoint);

    EnvironmentSettings settings =
        EnvironmentSettings.newInstance().withConfiguration(configuration).inBatchMode().build();
    tableEnv = TableEnvironment.create(settings);

    LOG.info(
        "FlinkConnectorViewIcebergOAuth2IT setup complete: metalake={}, catalog={}, schema={}",
        metalakeName,
        icebergCatalogName,
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
      if (icebergCatalog != null && schemaName != null) {
        icebergCatalog.asSchemas().dropSchema(schemaName, true);
      }
    } catch (Exception e) {
      LOG.warn("Failed to drop schema '{}'", schemaName, e);
    }
    if (adminClient != null) {
      adminClient.close();
    }
  }

  // ---------------------------------------------------------------------------------------------
  // P0 — bug-prone hot spots
  // ---------------------------------------------------------------------------------------------

  /**
   * TC-P0-03: {@code DROP} on a non-existent object honours the {@code IF EXISTS} branch logic. A
   * plain {@code DROP TABLE} on a missing object fails, while {@code DROP TABLE IF EXISTS} and
   * {@code DROP VIEW IF EXISTS} succeed without throwing.
   */
  @Test
  @DisplayName("TC-P0-03 DROP on a non-existent object honours IF EXISTS")
  public void testDropNonExistentObject() {
    useCatalogAndSchema();
    String missing = "tc_p0_03_missing";

    Assertions.assertThrows(
        Exception.class,
        () -> sql("DROP TABLE %s", missing),
        "DROP TABLE on a missing object should fail without IF EXISTS");

    assertTableResult(sql("DROP TABLE IF EXISTS %s", missing), ResultKind.SUCCESS);
    assertTableResult(sql("DROP VIEW IF EXISTS %s", missing), ResultKind.SUCCESS);
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
      assertTableResult(createBaseTable(tableName, "id INT, name STRING"), ResultKind.SUCCESS);
      assertTableResult(
          sql("CREATE VIEW %s AS SELECT id, name FROM %s", viewName, tableName),
          ResultKind.SUCCESS);

      CatalogBaseTable viewResult = flinkCatalogTable(viewName);
      Assertions.assertEquals(
          CatalogBaseTable.TableKind.VIEW,
          viewResult.getTableKind(),
          "getTable(view) should return TableKind.VIEW");

      CatalogBaseTable tableResult = flinkCatalogTable(tableName);
      Assertions.assertEquals(
          CatalogBaseTable.TableKind.TABLE,
          tableResult.getTableKind(),
          "getTable(table) should return TableKind.TABLE");

      Assertions.assertThrows(
          TableNotExistException.class,
          () -> flinkCatalogTable("tc_p0_05_missing"),
          "getTable(missing) should throw TableNotExistException");
    } catch (TableNotExistException e) {
      Assertions.fail("unexpected TableNotExistException: " + e.getMessage());
    } finally {
      dropQuietly(viewName, tableName);
    }
  }

  /**
   * TC-P0-06: Iceberg does not support {@code DROP CASCADE}. Schema cleanup must explicitly drop
   * views before dropping the database. This test creates a table and a view in a sub-schema, drops
   * both explicitly, then drops the database.
   */
  @Test
  @DisplayName("TC-P0-06 Schema cleanup with views present (Iceberg no cascade)")
  public void testSchemaCleanupWithViews() {
    String subSchema = RandomNameUtils.genRandomName("tc_p0_06").toLowerCase() + "_db";
    useCatalogAndSchema();

    try {
      // Create a sub-schema, table, and view inside it.
      assertTableResult(sql("CREATE DATABASE %s", subSchema), ResultKind.SUCCESS);
      tableEnv.useDatabase(subSchema);

      assertTableResult(createBaseTable("cleanup_base", "id INT"), ResultKind.SUCCESS);
      assertTableResult(
          sql("CREATE VIEW cleanup_view AS SELECT id FROM cleanup_base"), ResultKind.SUCCESS);

      // Iceberg does not support CASCADE; DROP DATABASE must fail when views are still present.
      tableEnv.useDatabase(schemaName);
      Assertions.assertThrows(
          Exception.class,
          () -> sql("DROP DATABASE %s CASCADE", subSchema),
          "DROP DATABASE should fail when views still exist (Iceberg does not support CASCADE)");

      // Must drop view and table explicitly first.
      tableEnv.useDatabase(subSchema);
      assertTableResult(sql("DROP VIEW cleanup_view"), ResultKind.SUCCESS);
      assertTableResult(sql("DROP TABLE cleanup_base"), ResultKind.SUCCESS);

      // Now dropping the empty database should succeed.
      tableEnv.useDatabase(schemaName);
      assertTableResult(sql("DROP DATABASE %s", subSchema), ResultKind.SUCCESS);

      Assertions.assertFalse(
          icebergCatalog.asSchemas().schemaExists(subSchema),
          "sub-schema should not exist after explicit cleanup and drop");
    } catch (Exception e) {
      Assertions.fail("schema cleanup test failed: " + e.getMessage());
    } finally {
      // Best-effort fallback cleanup.
      tableEnv.useDatabase(schemaName);
      try {
        sql("DROP VIEW IF EXISTS %s.cleanup_view", subSchema);
      } catch (Exception ignored) {
      }
      try {
        sql("DROP TABLE IF EXISTS %s.cleanup_base", subSchema);
      } catch (Exception ignored) {
      }
      try {
        sql("DROP DATABASE IF EXISTS %s", subSchema);
      } catch (Exception ignored) {
      }
    }
  }

  /**
   * TC-P0-07: {@code tableExists} falls back from the table catalog to the view catalog. Flink's
   * {@code tableExists} contract must report a view as existing through the table&rarr;view probe,
   * while a missing object reports as absent.
   */
  @Test
  @DisplayName("TC-P0-07 tableExists falls back from table to view catalog")
  public void testTableExistsFallsBackToView() {
    String tableName = "tc_p0_07_base";
    String viewName = "tc_p0_07_view";
    useCatalogAndSchema();

    try {
      assertTableResult(createBaseTable(tableName, "id INT"), ResultKind.SUCCESS);
      assertTableResult(
          sql("CREATE VIEW %s AS SELECT id FROM %s", viewName, tableName), ResultKind.SUCCESS);

      Assertions.assertTrue(
          flinkCatalogTableExists(viewName), "tableExists(view) should be true via fallback");
      Assertions.assertTrue(
          flinkCatalogTableExists(tableName), "tableExists(table) should be true");
      Assertions.assertFalse(
          flinkCatalogTableExists("tc_p0_07_missing"), "tableExists(missing) should be false");
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
      assertTableResult(createBaseTable(tableName, "id INT"), ResultKind.SUCCESS);
      assertTableResult(
          sql("CREATE VIEW %s AS SELECT id FROM %s", viewName, tableName), ResultKind.SUCCESS);

      assertTableResult(sql("ALTER VIEW %s RENAME TO %s", viewName, renamed), ResultKind.SUCCESS);

      ViewCatalog viewCatalog = icebergCatalog.asViewCatalog();
      Assertions.assertFalse(
          viewCatalog.viewExists(NameIdentifier.of(schemaName, viewName)),
          "old view name should be gone after rename");
      Assertions.assertTrue(
          viewCatalog.viewExists(NameIdentifier.of(schemaName, renamed)),
          "new view name should exist after rename");

      // Renaming the view onto the existing base table name must be rejected.
      Assertions.assertThrows(
          Exception.class,
          () -> sql("ALTER VIEW %s RENAME TO %s", renamed, tableName),
          "renaming a view onto an existing table name should fail");
    } finally {
      dropQuietly(viewName, null);
      dropQuietly(renamed, tableName);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // P1 — functional coverage
  // ---------------------------------------------------------------------------------------------

  /**
   * TC-P1-01: {@code CREATE VIEW} with a comment is reflected both in Gravitino's {@code
   * ViewCatalog.loadView} (name, comment, at least one SQL representation) and in the Flink catalog
   * as a {@code TableKind.VIEW}.
   */
  @Test
  @DisplayName("TC-P1-01 CREATE VIEW with comment is visible via Gravitino and Flink")
  public void testCreateViewWithComment() {
    String tableName = "tc_p1_01_base";
    String viewName = "tc_p1_01_view";
    useCatalogAndSchema();

    try {
      assertTableResult(createBaseTable(tableName, "id INT, name STRING"), ResultKind.SUCCESS);
      assertTableResult(
          sql(
              "CREATE VIEW %s COMMENT 'iceberg view comment' AS SELECT id, name FROM %s",
              viewName, tableName),
          ResultKind.SUCCESS);

      ViewCatalog viewCatalog = icebergCatalog.asViewCatalog();
      View view = viewCatalog.loadView(NameIdentifier.of(schemaName, viewName));
      Assertions.assertEquals(viewName, view.name());
      Assertions.assertEquals("iceberg view comment", view.comment());
      Assertions.assertTrue(view.representations().length >= 1);
      Assertions.assertInstanceOf(SQLRepresentation.class, view.representations()[0]);

      Assertions.assertEquals(
          CatalogBaseTable.TableKind.VIEW,
          flinkCatalogTable(viewName).getTableKind(),
          "Flink getTable should report the view as TableKind.VIEW");
    } catch (TableNotExistException e) {
      Assertions.fail("view should exist in Flink catalog: " + e.getMessage());
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
      assertTableResult(createBaseTable(tableName, "id INT"), ResultKind.SUCCESS);
      assertTableResult(
          sql("CREATE VIEW %s AS SELECT id FROM %s", viewName, tableName), ResultKind.SUCCESS);
      assertTableResult(
          sql("CREATE VIEW IF NOT EXISTS %s AS SELECT id FROM %s", viewName, tableName),
          ResultKind.SUCCESS);

      Assertions.assertTrue(
          icebergCatalog.asViewCatalog().viewExists(NameIdentifier.of(schemaName, viewName)),
          "view should still exist after CREATE VIEW IF NOT EXISTS");
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
      assertTableResult(createBaseTable(tableName, "id INT, name STRING"), ResultKind.SUCCESS);
      assertTableResult(
          sql("CREATE VIEW %s AS SELECT id FROM %s", viewName, tableName), ResultKind.SUCCESS);

      assertTableResult(
          sql("ALTER VIEW %s AS SELECT id, name FROM %s", viewName, tableName), ResultKind.SUCCESS);

      View view = icebergCatalog.asViewCatalog().loadView(NameIdentifier.of(schemaName, viewName));
      Assertions.assertTrue(view.representations().length >= 1);
      SQLRepresentation rep = (SQLRepresentation) view.representations()[0];
      Assertions.assertTrue(
          rep.sql().contains("id") && rep.sql().contains("name"),
          "updated view SQL should select both id and name columns");
    } finally {
      dropQuietly(viewName, tableName);
    }
  }

  /**
   * TC-P1-04: {@code ALTER VIEW} set / reset properties. Set a property then reset it; the view
   * body should remain untouched.
   */
  @Test
  @DisplayName("TC-P1-04 ALTER VIEW set/reset properties")
  public void testAlterViewSetResetProperties() {
    String tableName = "tc_p1_04_base";
    String viewName = "tc_p1_04_view";
    useCatalogAndSchema();

    try {
      assertTableResult(createBaseTable(tableName, "id INT"), ResultKind.SUCCESS);
      assertTableResult(
          sql("CREATE VIEW %s AS SELECT id FROM %s", viewName, tableName), ResultKind.SUCCESS);

      assertTableResult(sql("ALTER VIEW %s SET ('k1'='v1')", viewName), ResultKind.SUCCESS);

      View view = icebergCatalog.asViewCatalog().loadView(NameIdentifier.of(schemaName, viewName));
      Assertions.assertEquals("v1", view.properties().get("k1"), "property k1 should be set");

      assertTableResult(sql("ALTER VIEW %s RESET ('k1')", viewName), ResultKind.SUCCESS);

      view = icebergCatalog.asViewCatalog().loadView(NameIdentifier.of(schemaName, viewName));
      Assertions.assertFalse(
          view.properties().containsKey("k1"), "property k1 should be removed after RESET");
    } finally {
      dropQuietly(viewName, tableName);
    }
  }

  /**
   * TC-P1-05: querying through a view applies the view's projection and filter. Rows inserted into
   * the base table are returned filtered and ordered when selected from the view.
   */
  @Test
  @DisplayName("TC-P1-05 Query through a view returns filtered, ordered rows")
  public void testQueryThroughView() {
    String tableName = "tc_p1_05_base";
    String viewName = "tc_p1_05_view";
    useCatalogAndSchema();

    try {
      assertTableResult(createBaseTable(tableName, "id INT, name STRING"), ResultKind.SUCCESS);
      assertTableResult(
          sql("CREATE VIEW %s AS SELECT id, name FROM %s WHERE id > 1", viewName, tableName),
          ResultKind.SUCCESS);
      sql("INSERT INTO %s VALUES (1, 'alice'), (2, 'bob'), (3, 'carol')", tableName).await();

      List<Row> rows = collectRows(sql("SELECT * FROM %s ORDER BY id", viewName));
      Assertions.assertEquals(
          Arrays.asList(Row.of(2, "bob"), Row.of(3, "carol")),
          rows,
          "view query should return only rows matching the view filter, ordered by id");
    } catch (Exception e) {
      throw new AssertionError("unexpected failure querying through view", e);
    } finally {
      dropQuietly(viewName, tableName);
    }
  }

  /**
   * TC-P1-06: {@code listViews}/{@code listTables} isolation. {@code SHOW VIEWS} returns only views
   * and {@code SHOW TABLES} returns only tables, cross-checked against Gravitino's {@code
   * ViewCatalog.listViews}.
   */
  @Test
  @DisplayName("TC-P1-06 listViews / listTables isolation")
  public void testListViewsAndTablesIsolation() {
    String tableName = "tc_p1_06_base";
    String view1 = "tc_p1_06_view_1";
    String view2 = "tc_p1_06_view_2";
    useCatalogAndSchema();

    try {
      assertTableResult(createBaseTable(tableName, "id INT"), ResultKind.SUCCESS);
      assertTableResult(
          sql("CREATE VIEW %s AS SELECT id FROM %s", view1, tableName), ResultKind.SUCCESS);
      assertTableResult(
          sql("CREATE VIEW %s AS SELECT id FROM %s", view2, tableName), ResultKind.SUCCESS);

      List<String> views = Arrays.asList(tableEnv.listViews());
      Assertions.assertTrue(views.contains(view1), "view1 should appear in SHOW VIEWS");
      Assertions.assertTrue(views.contains(view2), "view2 should appear in SHOW VIEWS");
      Assertions.assertFalse(views.contains(tableName), "table should not appear in SHOW VIEWS");

      List<String> tables = Arrays.asList(tableEnv.listTables());
      Assertions.assertTrue(tables.contains(tableName), "table should appear in SHOW TABLES");
      Assertions.assertFalse(tables.contains(view1), "view should not appear in SHOW TABLES");

      NameIdentifier[] gravitinoViews =
          icebergCatalog.asViewCatalog().listViews(Namespace.of(schemaName));
      List<String> gravitinoViewNames =
          Arrays.stream(gravitinoViews).map(NameIdentifier::name).collect(Collectors.toList());
      Assertions.assertTrue(gravitinoViewNames.contains(view1));
      Assertions.assertTrue(gravitinoViewNames.contains(view2));
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
      assertTableResult(createBaseTable(tableName, "id INT"), ResultKind.SUCCESS);
      assertTableResult(
          sql("CREATE VIEW %s AS SELECT id FROM %s", viewName, tableName), ResultKind.SUCCESS);

      assertTableResult(sql("DROP VIEW %s", viewName), ResultKind.SUCCESS);
      assertTableResult(sql("DROP VIEW IF EXISTS %s", viewName), ResultKind.SUCCESS);
    } finally {
      dropQuietly(viewName, tableName);
    }
  }

  /**
   * TC-P0-04 (Iceberg REST path): View SQL dialect fallback. A view created via Flink should be
   * stored with the {@code flink} dialect and loaded back successfully through Flink.
   */
  @Test
  @DisplayName("TC-P0-04 View SQL dialect fallback (flink dialect path)")
  public void testViewSqlDialectFlink() {
    String tableName = "tc_p0_04_base";
    String viewName = "tc_p0_04_view";
    useCatalogAndSchema();

    try {
      assertTableResult(createBaseTable(tableName, "id INT, name STRING"), ResultKind.SUCCESS);
      assertTableResult(
          sql("CREATE VIEW %s AS SELECT id, name FROM %s", viewName, tableName),
          ResultKind.SUCCESS);

      // Verify the view was stored with a flink dialect representation.
      View view = icebergCatalog.asViewCatalog().loadView(NameIdentifier.of(schemaName, viewName));
      Assertions.assertTrue(view.representations().length >= 1);
      SQLRepresentation rep = (SQLRepresentation) view.representations()[0];
      // The dialect should be "flink" for views created through the Flink connector.
      Assertions.assertTrue(
          "flink".equalsIgnoreCase(rep.dialect()) || "hive".equalsIgnoreCase(rep.dialect()),
          "view should be stored with flink or hive dialect, got: " + rep.dialect());

      // Verify the view can be loaded back through Flink without errors.
      CatalogBaseTable loaded = flinkCatalogTable(viewName);
      Assertions.assertEquals(CatalogBaseTable.TableKind.VIEW, loaded.getTableKind());
    } catch (TableNotExistException e) {
      Assertions.fail("view should be loadable via Flink: " + e.getMessage());
    } finally {
      dropQuietly(viewName, tableName);
    }
  }

  /**
   * TC-P0-01 (Iceberg variant): {@code DROP VIEW} issued through Flink succeeds and removes the
   * view from the Iceberg catalog.
   */
  @Test
  @DisplayName("TC-P0-01 Iceberg DROP VIEW succeeds via Flink")
  public void testIcebergDropViewSucceeds() {
    String tableName = "tc_p0_01_base";
    String viewName = "tc_p0_01_view";
    useCatalogAndSchema();

    try {
      assertTableResult(createBaseTable(tableName, "id INT"), ResultKind.SUCCESS);
      assertTableResult(
          sql("CREATE VIEW %s AS SELECT id FROM %s", viewName, tableName), ResultKind.SUCCESS);

      ViewCatalog viewCatalog = icebergCatalog.asViewCatalog();
      Assertions.assertTrue(
          viewCatalog.viewExists(NameIdentifier.of(schemaName, viewName)),
          "view should exist before drop");

      assertTableResult(sql("DROP VIEW %s", viewName), ResultKind.SUCCESS);

      Assertions.assertFalse(
          viewCatalog.viewExists(NameIdentifier.of(schemaName, viewName)),
          "view should not exist after DROP VIEW");
    } finally {
      dropQuietly(viewName, tableName);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Cross-client consistency
  // ---------------------------------------------------------------------------------------------

  /**
   * Verifies that a view modified through the Gravitino Java API (bypassing the Flink connector) is
   * immediately visible to the Flink connector with the updated metadata. This tests the
   * cross-client consistency guarantee: changes made via the REST API or another engine must be
   * reflected when querying through Flink without caching issues.
   *
   * <p>Scenario:
   *
   * <ol>
   *   <li>Create a base table and a view via Flink SQL.
   *   <li>Modify the view via Gravitino {@code ViewCatalog.alterView}: update comment, add a
   *       property, and replace the view body with a new SQL projection.
   *   <li>Verify via Flink that the view metadata (comment, columns, properties) and query results
   *       reflect the Gravitino-side modification.
   * </ol>
   */
  @Test
  @DisplayName("Cross-client: Gravitino API modified view is visible in Flink")
  public void testGravitinoApiModifiedViewVisibleInFlink() {
    String tableName = "tc_cross_client_base";
    String viewName = "tc_cross_client_view";
    useCatalogAndSchema();

    try {
      // Step 1: Create base table and view via Flink.
      assertTableResult(
          createBaseTable(tableName, "id INT, name STRING, age INT"), ResultKind.SUCCESS);
      sql("INSERT INTO %s VALUES (1, 'alice', 30), (2, 'bob', 25), (3, 'carol', 35)", tableName)
          .await();
      assertTableResult(
          sql(
              "CREATE VIEW %s COMMENT 'original comment' AS SELECT id, name FROM %s",
              viewName, tableName),
          ResultKind.SUCCESS);

      // Sanity check: Flink can query the original view.
      List<Row> originalRows = collectRows(sql("SELECT * FROM %s ORDER BY id", viewName));
      Assertions.assertEquals(3, originalRows.size(), "original view should return 3 rows");

      // Step 2: Modify the view via Gravitino API directly (simulating another client).
      ViewCatalog viewCatalog = icebergCatalog.asViewCatalog();
      NameIdentifier viewIdent = NameIdentifier.of(schemaName, viewName);

      // Build a new SQL representation with an updated projection including the age column
      // and a WHERE filter.
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

      // Step 3: Verify via Flink that the modification is visible.

      // 3a: Check metadata through Flink catalog API.
      CatalogBaseTable flinkView = flinkCatalogTable(viewName);
      Assertions.assertEquals(
          CatalogBaseTable.TableKind.VIEW,
          flinkView.getTableKind(),
          "modified view should still be reported as VIEW");
      Assertions.assertEquals(
          "modified via gravitino api",
          flinkView.getComment(),
          "Flink should see the updated comment from Gravitino API");

      // 3b: Check that the Gravitino-side property is visible.
      View reloadedView = viewCatalog.loadView(viewIdent);
      Assertions.assertEquals(
          "gravitino_api",
          reloadedView.properties().get("modified_by"),
          "property set via Gravitino API should be present");
      Assertions.assertEquals("modified via gravitino api", reloadedView.comment());

      // 3c: Query through the view — should now reflect the new SQL body (3 columns, filtered).
      List<Row> modifiedRows = collectRows(sql("SELECT * FROM %s ORDER BY id", viewName));
      // Only alice (age=30) and carol (age=35) match WHERE age > 26.
      Assertions.assertEquals(
          Arrays.asList(Row.of(1, "alice", 30), Row.of(3, "carol", 35)),
          modifiedRows,
          "view query should return rows matching the updated filter from Gravitino API");
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
  private static TableResult sql(String sql, Object... args) {
    return tableEnv.executeSql(String.format(sql, args));
  }

  private static void assertTableResult(TableResult tableResult, ResultKind expected) {
    Assertions.assertEquals(expected, tableResult.getResultKind());
  }

  /** Selects the Iceberg catalog and the test schema as the current Flink session context. */
  private static void useCatalogAndSchema() {
    tableEnv.useCatalog(icebergCatalogName);
    tableEnv.useDatabase(schemaName);
  }

  /** Creates an Iceberg base table with the given column definition list. */
  private static TableResult createBaseTable(String tableName, String columns) {
    return sql("CREATE TABLE %s (%s)", tableName, columns);
  }

  /** Resolves a table/view in the Flink-side Gravitino catalog, exercising the table→view probe. */
  private static CatalogBaseTable flinkCatalogTable(String name) throws TableNotExistException {
    return tableEnv
        .getCatalog(icebergCatalogName)
        .orElseThrow(() -> new IllegalStateException("Flink catalog not registered"))
        .getTable(new ObjectPath(schemaName, name));
  }

  /** Returns whether the Flink-side catalog reports the given table/view as existing. */
  private static boolean flinkCatalogTableExists(String name) {
    return tableEnv
        .getCatalog(icebergCatalogName)
        .orElseThrow(() -> new IllegalStateException("Flink catalog not registered"))
        .tableExists(new ObjectPath(schemaName, name));
  }

  /** Materializes the rows of a query result into a list for assertions. */
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

  /**
   * Best-effort cleanup of a view and/or table created by a test. Either argument may be {@code
   * null} to skip that object.
   */
  private static void dropQuietly(String viewName, String tableName) {
    if (viewName != null) {
      try {
        sql("DROP VIEW IF EXISTS %s", viewName);
      } catch (Exception e) {
        LOG.warn("Failed to drop view '{}' during cleanup", viewName, e);
      }
    }
    if (tableName != null) {
      try {
        sql("DROP TABLE IF EXISTS %s", tableName);
      } catch (Exception e) {
        LOG.warn("Failed to drop table '{}' during cleanup", tableName, e);
      }
    }
  }

  /** Builds an OAuth2TokenProvider for the admin client using the client_credentials grant. */
  private static OAuth2TokenProvider buildAdminTokenProvider() {
    String credential = oauth2ClientId + ":" + oauth2ClientSecret;
    return DefaultOAuth2TokenProvider.builder()
        .withUri(oauth2ServerUri)
        .withCredential(credential)
        .withScope(oauth2Scope)
        .withPath(oauth2TokenPath)
        .build();
  }
}
