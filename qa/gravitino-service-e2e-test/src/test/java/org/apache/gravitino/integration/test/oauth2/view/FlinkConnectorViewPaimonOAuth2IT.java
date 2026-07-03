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
import org.apache.gravitino.catalog.lakehouse.paimon.PaimonConstants;
import org.apache.gravitino.client.DefaultOAuth2TokenProvider;
import org.apache.gravitino.client.GravitinoAdminClient;
import org.apache.gravitino.client.GravitinoMetalake;
import org.apache.gravitino.client.OAuth2TokenProvider;
import org.apache.gravitino.rel.SQLRepresentation;
import org.apache.gravitino.rel.View;
import org.apache.gravitino.rel.ViewCatalog;
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
 * E2E integration test for the Flink connector Paimon view support (PR #11349) against a deployed
 * Gravitino service authenticated with OAuth2.
 *
 * <p>Implements the Paimon-applicable test cases from {@code PR11349-view-test-plan.md}, driving
 * each scenario end to end through Flink SQL against a real (Hive-backed) Paimon catalog:
 *
 * <ul>
 *   <li><b>TC-P0-01</b> — {@code DROP VIEW} succeeds (core bug fix). {@code GravitinoPaimonCatalog}
 *       overrides {@code dropTable} to call {@code purgeTable()}, which historically lacked the
 *       table&rarr;view fallback present in {@code BaseCatalog.dropTable} and threw {@code
 *       TableNotExistException} when dropping a view.
 *   <li><b>TC-P0-02</b> — {@code DROP TABLE} purges data and does not hit the view path; a
 *       subsequent {@code getTable} throws {@code TableNotExistException}.
 *   <li><b>TC-P0-03</b> — {@code DROP TABLE}/{@code DROP VIEW} on a non-existent object honours the
 *       {@code IF EXISTS} branch logic.
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
@DisplayName("Flink Connector Paimon View OAuth2 E2E (PR #11349)")
public class FlinkConnectorViewPaimonOAuth2IT {

  private static final Logger LOG = LoggerFactory.getLogger(FlinkConnectorViewPaimonOAuth2IT.class);

  private static final String PAIMON_CATALOG_NAME = "paimon_view_oauth2";

  private static GravitinoAdminClient adminClient;
  private static GravitinoMetalake metalake;
  private static Catalog paimonCatalog;
  private static TableEnvironment tableEnv;

  private static String gravitinoUri;
  private static String metalakeName;
  private static String schemaName;
  private static String warehouse;
  private static String hiveMetastoreUri;

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
    hiveMetastoreUri = System.getProperty("hive.metastore.uri", "thrift://localhost:30083");
    warehouse =
        System.getProperty("paimon.warehouse", "s3a://gravitino-glue-test/paimon/warehouse");

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

    // --- 1. Connect to Gravitino over OAuth2 and provision a metalake + Paimon (Hive) catalog ---
    adminClient =
        GravitinoAdminClient.builder(gravitinoUri)
            .withOAuth(buildAdminTokenProvider())
            .withVersionCheckDisabled()
            .build();

    metalakeName = RandomNameUtils.genRandomName("paimon_view_oauth2_e2e");
    metalake =
        adminClient.createMetalake(
            metalakeName, "Metalake for Flink Paimon view OAuth2 E2E", Collections.emptyMap());

    Map<String, String> paimonProps = Maps.newHashMap();
    paimonProps.put(PaimonConstants.CATALOG_BACKEND, "hive");
    paimonProps.put(PaimonConstants.WAREHOUSE, warehouse);
    paimonProps.put(PaimonConstants.URI, hiveMetastoreUri);
    // Hadoop S3A filesystem configuration — "gravitino.bypass." is stripped by Gravitino, then
    // Paimon's CatalogContext strips "hadoop." and puts the remainder into Hadoop Configuration.
    paimonProps.put("gravitino.bypass.hadoop.fs.s3a.access.key", s3AccessKey);
    paimonProps.put("gravitino.bypass.hadoop.fs.s3a.secret.key", s3SecretKey);
    paimonProps.put("gravitino.bypass.hadoop.fs.s3a.endpoint", s3Endpoint);
    paimonCatalog =
        metalake.createCatalog(
            PAIMON_CATALOG_NAME,
            Catalog.Type.RELATIONAL,
            "lakehouse-paimon",
            "Paimon catalog for Flink view OAuth2 E2E",
            paimonProps);

    schemaName = RandomNameUtils.genRandomName("pv").toLowerCase() + "_db";
    paimonCatalog
        .asSchemas()
        .createSchema(schemaName, "Flink Paimon view OAuth2 e2e db", Collections.emptyMap());

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

    // Hadoop S3A configuration for the local Flink/Paimon client to read/write data files on S3.
    // Without these, INSERT INTO and SELECT FROM Paimon tables fail with "No FileSystem for s3a".
    configuration.setString("fs.s3a.access.key", s3AccessKey);
    configuration.setString("fs.s3a.secret.key", s3SecretKey);
    configuration.setString("fs.s3a.endpoint", s3Endpoint);

    EnvironmentSettings settings =
        EnvironmentSettings.newInstance().withConfiguration(configuration).inBatchMode().build();
    tableEnv = TableEnvironment.create(settings);

    LOG.info(
        "FlinkConnectorViewPaimonOAuth2IT setup complete: metalake={}, catalog={}, schema={}",
        metalakeName,
        PAIMON_CATALOG_NAME,
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

  /**
   * TC-P0-01: a Paimon {@code DROP VIEW} issued through Flink succeeds and removes the view,
   * without triggering the {@code TableNotExistException} that the {@code purgeTable()} override
   * used to throw before the {@code BaseCatalog} table&rarr;view fallback was added.
   */
  @Test
  @DisplayName("TC-P0-01 Paimon DROP VIEW succeeds via Flink (core bug fix)")
  public void testPaimonDropViewSucceeds() {
    String tableName = "tc_p0_01_base";
    String viewName = "tc_p0_01_view";
    useCatalogAndSchema();

    try {
      assertTableResult(createBaseTable(tableName, "id INT"), ResultKind.SUCCESS);
      assertTableResult(
          sql("CREATE VIEW %s AS SELECT id FROM %s", viewName, tableName), ResultKind.SUCCESS);

      ViewCatalog viewCatalog = paimonCatalog.asViewCatalog();
      Assertions.assertTrue(
          viewCatalog.viewExists(NameIdentifier.of(schemaName, viewName)),
          "view should exist before drop");

      // The core assertion: DROP VIEW must return SUCCESS and must NOT throw
      // TableNotExistException through the Paimon purgeTable() override.
      assertTableResult(sql("DROP VIEW %s", viewName), ResultKind.SUCCESS);

      Assertions.assertFalse(
          viewCatalog.viewExists(NameIdentifier.of(schemaName, viewName)),
          "view should not exist after DROP VIEW");
    } finally {
      dropQuietly(viewName, tableName);
    }
  }

  /**
   * TC-P0-02: a Paimon {@code DROP TABLE} purges the table through {@code purgeTable()} without
   * touching the view fallback. After the drop a subsequent {@code getTable} via the Flink catalog
   * must raise {@code TableNotExistException}, proving the native cache was invalidated.
   */
  @Test
  @DisplayName("TC-P0-02 Paimon DROP TABLE purges data and does not hit view path")
  public void testPaimonDropTablePurges() {
    String tableName = "tc_p0_02_base";
    useCatalogAndSchema();

    try {
      assertTableResult(createBaseTable(tableName, "id INT"), ResultKind.SUCCESS);
      // INSERT returns a job result; only assert it does not fail.
      sql("INSERT INTO %s VALUES (1)", tableName).await();

      assertTableResult(sql("DROP TABLE %s", tableName), ResultKind.SUCCESS);

      Assertions.assertFalse(
          paimonCatalog.asTableCatalog().tableExists(NameIdentifier.of(schemaName, tableName)),
          "table should not exist in Gravitino after DROP TABLE");
      Assertions.assertThrows(
          TableNotExistException.class,
          () -> flinkCatalogTable(tableName),
          "getTable on a dropped Paimon table must throw TableNotExistException");
    } catch (Exception e) {
      Assertions.fail("unexpected failure during DROP TABLE purge test: " + e.getMessage());
    } finally {
      dropQuietly(null, tableName);
    }
  }

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
   * TC-P0-07: {@code tableExists} falls back from the table catalog to the view catalog. Flink's
   * {@code getTable}/{@code tableExists} contract must report a view as existing through the
   * table&rarr;view probe, while a missing object reports as absent.
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
   * fresh name succeeds, while renaming a view onto an existing table name is rejected (the
   * connector maps {@code ViewAlreadyExistsException} to {@code TableAlreadyExistException}).
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

      ViewCatalog viewCatalog = paimonCatalog.asViewCatalog();
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
              "CREATE VIEW %s COMMENT 'view comment' AS SELECT id, name FROM %s",
              viewName, tableName),
          ResultKind.SUCCESS);

      ViewCatalog viewCatalog = paimonCatalog.asViewCatalog();
      View view = viewCatalog.loadView(NameIdentifier.of(schemaName, viewName));
      Assertions.assertEquals(viewName, view.name());
      Assertions.assertEquals("view comment", view.comment());
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
          paimonCatalog.asViewCatalog().viewExists(NameIdentifier.of(schemaName, viewName)),
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

      View view = paimonCatalog.asViewCatalog().loadView(NameIdentifier.of(schemaName, viewName));
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
      // Propagate the full exception chain so the root cause is visible in the test report.
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
          paimonCatalog.asViewCatalog().listViews(Namespace.of(schemaName));
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

  // ---------------------------------------------------------------------------------------------
  // ALTER TABLE impact on views
  // ---------------------------------------------------------------------------------------------

  /**
   * Adding a column to the base table does not break an existing view that references a subset of
   * columns. The view metadata remains valid and loadable.
   */
  @Test
  @DisplayName("ALTER TABLE ADD COLUMN does not break existing view")
  public void testAlterTableAddColumnDoesNotBreakView() {
    String tableName = "alter_add_col_base";
    String viewName = "alter_add_col_view";
    useCatalogAndSchema();

    try {
      assertTableResult(createBaseTable(tableName, "id INT, name STRING"), ResultKind.SUCCESS);
      assertTableResult(
          sql("CREATE VIEW %s AS SELECT id, name FROM %s", viewName, tableName),
          ResultKind.SUCCESS);

      assertTableResult(sql("ALTER TABLE %s ADD (age INT)", tableName), ResultKind.SUCCESS);

      ViewCatalog viewCatalog = paimonCatalog.asViewCatalog();
      Assertions.assertTrue(
          viewCatalog.viewExists(NameIdentifier.of(schemaName, viewName)),
          "view should still exist after ALTER TABLE ADD COLUMN");

      org.apache.gravitino.rel.View loadedView =
          viewCatalog.loadView(NameIdentifier.of(schemaName, viewName));
      Assertions.assertEquals(2, loadedView.columns().length, "view should still have 2 columns");
    } finally {
      dropQuietly(viewName, tableName);
    }
  }

  /**
   * Dropping a column that a view references does NOT automatically invalidate the view metadata.
   * The view still exists in Gravitino, but querying it at runtime would fail. This test verifies
   * the metadata layer remains consistent.
   */
  @Test
  @DisplayName("ALTER TABLE DROP COLUMN leaves view metadata intact (dangling reference)")
  public void testAlterTableDropColumnLeavesViewIntact() {
    String tableName = "alter_drop_col_base";
    String viewName = "alter_drop_col_view";
    useCatalogAndSchema();

    try {
      assertTableResult(
          createBaseTable(tableName, "id INT, name STRING, city STRING"), ResultKind.SUCCESS);
      assertTableResult(
          sql("CREATE VIEW %s AS SELECT id, name FROM %s", viewName, tableName),
          ResultKind.SUCCESS);

      // Drop a column NOT referenced by the view.
      assertTableResult(sql("ALTER TABLE %s DROP (city)", tableName), ResultKind.SUCCESS);

      ViewCatalog viewCatalog = paimonCatalog.asViewCatalog();
      Assertions.assertTrue(
          viewCatalog.viewExists(NameIdentifier.of(schemaName, viewName)),
          "view should still exist after dropping an unreferenced column");

      // Drop a column that IS referenced by the view.
      assertTableResult(sql("ALTER TABLE %s DROP (name)", tableName), ResultKind.SUCCESS);

      // The view metadata should still exist — Gravitino does not cascade-invalidate views.
      Assertions.assertTrue(
          viewCatalog.viewExists(NameIdentifier.of(schemaName, viewName)),
          "view metadata should persist even after referenced column is dropped");
    } finally {
      dropQuietly(viewName, tableName);
    }
  }

  /**
   * Renaming the base table leaves the view referencing the old table name. The view metadata is
   * still loadable, demonstrating that views store a snapshot of the SQL at creation time.
   */
  @Test
  @DisplayName("ALTER TABLE RENAME leaves view with stale table reference")
  public void testAlterTableRenameLeavesViewStale() {
    String tableName = "alter_rename_base";
    String renamedTable = "alter_rename_base_new";
    String viewName = "alter_rename_view";
    useCatalogAndSchema();

    try {
      assertTableResult(createBaseTable(tableName, "id INT"), ResultKind.SUCCESS);
      assertTableResult(
          sql("CREATE VIEW %s AS SELECT id FROM %s", viewName, tableName), ResultKind.SUCCESS);

      assertTableResult(
          sql("ALTER TABLE %s RENAME TO %s", tableName, renamedTable), ResultKind.SUCCESS);

      ViewCatalog viewCatalog = paimonCatalog.asViewCatalog();
      Assertions.assertTrue(
          viewCatalog.viewExists(NameIdentifier.of(schemaName, viewName)),
          "view should still exist after base table rename");
    } finally {
      dropQuietly(viewName, null);
      sql("DROP TABLE IF EXISTS %s", renamedTable);
      sql("DROP TABLE IF EXISTS %s", tableName);
    }
  }

  /**
   * Dropping the base table entirely while a dependent view exists. The view metadata remains — no
   * cascade delete. This validates that the view layer and table layer are decoupled.
   */
  @Test
  @DisplayName("DROP TABLE does not cascade-delete dependent views")
  public void testDropTableDoesNotCascadeDeleteView() {
    String tableName = "cascade_del_base";
    String viewName = "cascade_del_view";
    useCatalogAndSchema();

    try {
      assertTableResult(createBaseTable(tableName, "id INT, val STRING"), ResultKind.SUCCESS);
      assertTableResult(
          sql("CREATE VIEW %s AS SELECT id, val FROM %s", viewName, tableName), ResultKind.SUCCESS);

      assertTableResult(sql("DROP TABLE %s", tableName), ResultKind.SUCCESS);

      ViewCatalog viewCatalog = paimonCatalog.asViewCatalog();
      Assertions.assertTrue(
          viewCatalog.viewExists(NameIdentifier.of(schemaName, viewName)),
          "view should survive base table deletion");

      assertTableResult(sql("DROP VIEW %s", viewName), ResultKind.SUCCESS);
    } finally {
      dropQuietly(viewName, tableName);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Views over a partitioned base table
  // ---------------------------------------------------------------------------------------------

  /**
   * A view over a partitioned Paimon table is created and loadable. Verifies the view metadata
   * captures all projected columns including the partition column.
   */
  @Test
  @DisplayName("CREATE VIEW over a partitioned base table captures partition column")
  public void testViewOverPartitionedTable() {
    String tableName = "part_base";
    String viewName = "part_view";
    useCatalogAndSchema();

    try {
      assertTableResult(
          sql(
              "CREATE TABLE %s (id INT, ts TIMESTAMP(3), dt STRING) PARTITIONED BY (dt)"
                  + " WITH ('connector'='paimon')",
              tableName),
          ResultKind.SUCCESS);
      assertTableResult(
          sql("CREATE VIEW %s AS SELECT id, ts, dt FROM %s", viewName, tableName),
          ResultKind.SUCCESS);

      ViewCatalog viewCatalog = paimonCatalog.asViewCatalog();
      org.apache.gravitino.rel.View loadedView =
          viewCatalog.loadView(NameIdentifier.of(schemaName, viewName));
      Assertions.assertEquals(
          3, loadedView.columns().length, "view should project id, ts and the dt partition column");
    } finally {
      dropQuietly(viewName, tableName);
    }
  }

  /**
   * Inserting into a partitioned base table and querying through a view that filters on the
   * partition column returns only the matching partition rows.
   */
  @Test
  @DisplayName("Query through view with partition filter returns matching partition rows")
  public void testQueryViewWithPartitionFilter() {
    String tableName = "part_query_base";
    String viewName = "part_query_view";
    useCatalogAndSchema();

    try {
      assertTableResult(
          sql(
              "CREATE TABLE %s (id INT, ts TIMESTAMP(3), dt STRING) PARTITIONED BY (dt)"
                  + " WITH ('connector'='paimon')",
              tableName),
          ResultKind.SUCCESS);
      assertTableResult(
          sql(
              "CREATE VIEW %s AS SELECT id, dt FROM %s WHERE dt = '2024-01-01'",
              viewName, tableName),
          ResultKind.SUCCESS);

      sql(
              "INSERT INTO %s VALUES "
                  + "(1, TIMESTAMP '2024-01-01 12:00:00', '2024-01-01'), "
                  + "(2, TIMESTAMP '2024-01-02 09:30:00', '2024-01-02'), "
                  + "(3, TIMESTAMP '2024-01-01 18:45:00', '2024-01-01')",
              tableName)
          .await();

      List<Row> rows = collectRows(sql("SELECT * FROM %s ORDER BY id", viewName));
      Assertions.assertEquals(
          Arrays.asList(Row.of(1, "2024-01-01"), Row.of(3, "2024-01-01")),
          rows,
          "view should return only rows from the 2024-01-01 partition, ordered by id");
    } catch (Exception e) {
      throw new AssertionError("unexpected failure querying partitioned view", e);
    } finally {
      dropQuietly(viewName, tableName);
    }
  }

  /**
   * Adding a column to a partitioned base table does not break a view over it; the view metadata
   * and its partition-aware projection remain valid.
   */
  @Test
  @DisplayName("ALTER TABLE ADD COLUMN on partitioned table does not break view")
  public void testAlterPartitionedTableAddColumnKeepsView() {
    String tableName = "part_alter_base";
    String viewName = "part_alter_view";
    useCatalogAndSchema();

    try {
      assertTableResult(
          sql(
              "CREATE TABLE %s (id INT, ts TIMESTAMP(3), dt STRING) PARTITIONED BY (dt)"
                  + " WITH ('connector'='paimon')",
              tableName),
          ResultKind.SUCCESS);
      assertTableResult(
          sql("CREATE VIEW %s AS SELECT id, dt FROM %s", viewName, tableName), ResultKind.SUCCESS);

      assertTableResult(
          sql("ALTER TABLE %s ADD (amount DECIMAL(10, 2))", tableName), ResultKind.SUCCESS);

      ViewCatalog viewCatalog = paimonCatalog.asViewCatalog();
      Assertions.assertTrue(
          viewCatalog.viewExists(NameIdentifier.of(schemaName, viewName)),
          "view over partitioned table should survive ALTER TABLE ADD COLUMN");
      org.apache.gravitino.rel.View loadedView =
          viewCatalog.loadView(NameIdentifier.of(schemaName, viewName));
      Assertions.assertEquals(
          2, loadedView.columns().length, "view projection should be unchanged (id, dt)");
    } finally {
      dropQuietly(viewName, tableName);
    }
  }

  @FormatMethod
  private static TableResult sql(String sql, Object... args) {
    return tableEnv.executeSql(String.format(sql, args));
  }

  private static void assertTableResult(TableResult tableResult, ResultKind expected) {
    Assertions.assertEquals(expected, tableResult.getResultKind());
  }

  /** Selects the Paimon catalog and the test schema as the current Flink session context. */
  private static void useCatalogAndSchema() {
    tableEnv.useCatalog(PAIMON_CATALOG_NAME);
    tableEnv.useDatabase(schemaName);
  }

  /** Creates a Paimon base table with the given column definition list. */
  private static TableResult createBaseTable(String tableName, String columns) {
    return sql("CREATE TABLE %s (%s) WITH ('connector'='paimon')", tableName, columns);
  }

  /** Resolves a table/view in the Flink-side Gravitino catalog, exercising the table→view probe. */
  private static CatalogBaseTable flinkCatalogTable(String name) throws TableNotExistException {
    return tableEnv
        .getCatalog(PAIMON_CATALOG_NAME)
        .orElseThrow(() -> new IllegalStateException("Flink catalog not registered"))
        .getTable(new ObjectPath(schemaName, name));
  }

  /** Returns whether the Flink-side catalog reports the given table/view as existing. */
  private static boolean flinkCatalogTableExists(String name) {
    return tableEnv
        .getCatalog(PAIMON_CATALOG_NAME)
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
