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
import org.apache.flink.configuration.Configuration;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.ResultKind;
import org.apache.flink.table.api.TableEnvironment;
import org.apache.flink.table.api.TableResult;
import org.apache.flink.table.api.internal.TableEnvironmentImpl;
import org.apache.flink.types.Row;
import org.apache.flink.util.CloseableIterator;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.catalog.lakehouse.paimon.PaimonConstants;
import org.apache.gravitino.client.DefaultOAuth2TokenProvider;
import org.apache.gravitino.client.GravitinoAdminClient;
import org.apache.gravitino.client.GravitinoMetalake;
import org.apache.gravitino.client.OAuth2TokenProvider;
import org.apache.gravitino.rel.ViewCatalog;
import org.apache.gravitino.utils.RandomNameUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * E2E integration test for the Flink connector Paimon <b>JDBC backend</b> view support (PR #11349)
 * against a deployed Gravitino service authenticated with OAuth2.
 *
 * <p>Focus areas unique to this class (not covered by {@link FlinkConnectorViewPaimonOAuth2IT}):
 *
 * <ul>
 *   <li>Paimon JDBC metastore backend with S3 warehouse — verifies view DDL works when metadata
 *       lives in a JDBC database instead of HMS.
 *   <li>{@code ALTER TABLE} impact on views — validates that adding/dropping columns on a base
 *       table does not corrupt or invalidate the view, and that querying the view after schema
 *       evolution behaves correctly (or fails with an understandable error).
 *   <li>{@code DROP TABLE} while a dependent view exists — verifies the view becomes a "dangling"
 *       reference but its metadata remains intact.
 * </ul>
 *
 * <p>Gated on environment variables: {@code OAUTH2_SERVER_URI}, {@code OAUTH2_CLIENT_ID}, {@code
 * OAUTH2_CLIENT_SECRET}, {@code PAIMON_JDBC_URI}.
 */
@DisplayName("Flink Paimon JDBC Backend View OAuth2 E2E")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class FlinkConnectorViewPaimonJdbcOAuth2IT_unsupport {

  private static final Logger LOG =
      LoggerFactory.getLogger(FlinkConnectorViewPaimonJdbcOAuth2IT_unsupport.class);

  private static final String CATALOG_NAME = "paimon_jdbc_view_oauth2";

  private static GravitinoAdminClient adminClient;
  private static GravitinoMetalake metalake;
  private static Catalog paimonCatalog;
  private static TableEnvironment tableEnv;

  private static String gravitinoUri;
  private static String metalakeName;
  private static String schemaName;
  private static String warehouse;

  // JDBC backend settings.
  private static String jdbcUri;
  private static String jdbcUser;
  private static String jdbcPassword;
  private static String jdbcDriver;

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
    // --- Gate on required env vars ---
    oauth2ServerUri = System.getenv("OAUTH2_SERVER_URI");
    oauth2ClientId = System.getenv("OAUTH2_CLIENT_ID");
    oauth2ClientSecret = System.getenv("OAUTH2_CLIENT_SECRET");
    jdbcUri = System.getenv("PAIMON_JDBC_URI");
    Assumptions.assumeTrue(
        oauth2ServerUri != null && !oauth2ServerUri.isEmpty(),
        "Skipping: OAUTH2_SERVER_URI not set");
    Assumptions.assumeTrue(
        oauth2ClientId != null && !oauth2ClientId.isEmpty(), "Skipping: OAUTH2_CLIENT_ID not set");
    Assumptions.assumeTrue(
        oauth2ClientSecret != null && !oauth2ClientSecret.isEmpty(),
        "Skipping: OAUTH2_CLIENT_SECRET not set");

    gravitinoUri = System.getProperty("gravitino.uri", "http://localhost:30090");
    warehouse =
        System.getProperty("paimon.warehouse", "s3a://gravitino-glue-test/paimon-jdbc/warehouse");

    // Reuse the existing PostgreSQL instance deployed alongside Gravitino for the JDBC backend.
    jdbcUri =
        System.getenv()
            .getOrDefault(
                "PAIMON_JDBC_URI",
                "jdbc:postgresql://gravitino-env2-oauth2-auth-postgresql:31544/paimon");
    jdbcUser = System.getenv().getOrDefault("PAIMON_JDBC_USER", "gravitino");
    jdbcPassword = System.getenv().getOrDefault("PAIMON_JDBC_PASSWORD", "gravitino");
    jdbcDriver = System.getenv().getOrDefault("PAIMON_JDBC_DRIVER", "org.postgresql.Driver");

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

    // --- 1. Connect to Gravitino and provision metalake + Paimon JDBC catalog ---
    adminClient =
        GravitinoAdminClient.builder(gravitinoUri)
            .withOAuth(buildTokenProvider())
            .withVersionCheckDisabled()
            .build();

    metalakeName = RandomNameUtils.genRandomName("paimon_jdbc_view_e2e");
    metalake =
        adminClient.createMetalake(
            metalakeName, "Paimon JDBC view OAuth2 E2E", Collections.emptyMap());

    Map<String, String> props = Maps.newHashMap();
    props.put(PaimonConstants.CATALOG_BACKEND, "jdbc");
    props.put(PaimonConstants.WAREHOUSE, warehouse);
    props.put(PaimonConstants.URI, jdbcUri);
    props.put(PaimonConstants.GRAVITINO_JDBC_USER, jdbcUser);
    props.put(PaimonConstants.GRAVITINO_JDBC_PASSWORD, jdbcPassword);
    props.put(PaimonConstants.GRAVITINO_JDBC_DRIVER, jdbcDriver);
    // Hadoop S3A config via bypass for the server-side Paimon FileIO.
    props.put("gravitino.bypass.hadoop.fs.s3a.access.key", s3AccessKey);
    props.put("gravitino.bypass.hadoop.fs.s3a.secret.key", s3SecretKey);
    props.put("gravitino.bypass.hadoop.fs.s3a.endpoint", s3Endpoint);

    paimonCatalog =
        metalake.createCatalog(
            CATALOG_NAME,
            Catalog.Type.RELATIONAL,
            "lakehouse-paimon",
            "Paimon JDBC catalog for view E2E",
            props);

    schemaName = RandomNameUtils.genRandomName("pj").toLowerCase() + "_db";
    paimonCatalog
        .asSchemas()
        .createSchema(schemaName, "Paimon JDBC view e2e schema", Collections.emptyMap());

    // --- 2. Build Flink TableEnvironment ---
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

    // Local Hadoop S3A for Paimon data I/O in the embedded Flink runtime.
    configuration.setString("fs.s3a.access.key", s3AccessKey);
    configuration.setString("fs.s3a.secret.key", s3SecretKey);
    configuration.setString("fs.s3a.endpoint", s3Endpoint);

    EnvironmentSettings settings =
        EnvironmentSettings.newInstance().withConfiguration(configuration).inBatchMode().build();
    tableEnv = TableEnvironment.create(settings);

    tableEnv.useCatalog(CATALOG_NAME);
    tableEnv.useDatabase(schemaName);

    LOG.info(
        "Setup complete: metalake={}, catalog={}, schema={}",
        metalakeName,
        CATALOG_NAME,
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

  // ---------------------------------------------------------------------------
  // Test: DROP VIEW on JDBC backend (TC-P0-01 equivalent)
  // ---------------------------------------------------------------------------

  /**
   * Verifies that DROP VIEW succeeds on the Paimon JDBC backend without throwing
   * TableNotExistException through the purgeTable() override.
   */
  @Test
  @Order(1)
  @DisplayName("DROP VIEW succeeds on Paimon JDBC backend")
  public void testDropViewOnJdbcBackend() {
    String table = "jdbc_drop_view_base";
    String view = "jdbc_drop_view_v";

    try {
      assertSuccess(
          sql("CREATE TABLE %s (id INT, name STRING) WITH ('connector'='paimon')", table));
      assertSuccess(sql("CREATE VIEW %s AS SELECT id, name FROM %s", view, table));

      ViewCatalog viewCatalog = paimonCatalog.asViewCatalog();
      Assertions.assertTrue(
          viewCatalog.viewExists(NameIdentifier.of(schemaName, view)), "view should exist");

      assertSuccess(sql("DROP VIEW %s", view));

      Assertions.assertFalse(
          viewCatalog.viewExists(NameIdentifier.of(schemaName, view)),
          "view should not exist after DROP");
    } finally {
      quietDrop(view, table);
    }
  }

  // ---------------------------------------------------------------------------
  // Tests: ALTER TABLE impact on dependent views
  // ---------------------------------------------------------------------------

  /**
   * Adding a column to the base table does not break an existing view that references a subset of
   * columns. The view metadata remains valid and loadable.
   */
  @Test
  @Order(2)
  @DisplayName("ALTER TABLE ADD COLUMN does not break existing view")
  public void testAlterTableAddColumnDoesNotBreakView() {
    String table = "add_col_base";
    String view = "add_col_view";

    try {
      assertSuccess(
          sql("CREATE TABLE %s (id INT, name STRING) WITH ('connector'='paimon')", table));
      assertSuccess(sql("CREATE VIEW %s AS SELECT id, name FROM %s", view, table));

      // Add a new column to the base table.
      assertSuccess(sql("ALTER TABLE %s ADD (age INT)", table));

      // The view should still be loadable and its metadata intact.
      ViewCatalog viewCatalog = paimonCatalog.asViewCatalog();
      Assertions.assertTrue(
          viewCatalog.viewExists(NameIdentifier.of(schemaName, view)),
          "view should still exist after ALTER TABLE ADD COLUMN");

      // The view's SQL still references the original columns — verify via Gravitino API.
      org.apache.gravitino.rel.View loadedView =
          viewCatalog.loadView(NameIdentifier.of(schemaName, view));
      Assertions.assertEquals(2, loadedView.columns().length, "view should still have 2 columns");
    } finally {
      quietDrop(view, table);
    }
  }

  /**
   * Dropping a column that a view references does NOT automatically invalidate the view metadata.
   * The view still exists in Gravitino, but querying it at runtime would fail (the referenced
   * column is gone). This test verifies the metadata layer remains consistent.
   */
  @Test
  @Order(3)
  @DisplayName("ALTER TABLE DROP COLUMN leaves view metadata intact (dangling reference)")
  public void testAlterTableDropColumnLeavesViewIntact() {
    String table = "drop_col_base";
    String view = "drop_col_view";

    try {
      assertSuccess(
          sql(
              "CREATE TABLE %s (id INT, name STRING, city STRING) WITH ('connector'='paimon')",
              table));
      assertSuccess(sql("CREATE VIEW %s AS SELECT id, name FROM %s", view, table));

      // Drop a column NOT referenced by the view — view should be unaffected.
      assertSuccess(sql("ALTER TABLE %s DROP (city)", table));

      ViewCatalog viewCatalog = paimonCatalog.asViewCatalog();
      Assertions.assertTrue(
          viewCatalog.viewExists(NameIdentifier.of(schemaName, view)),
          "view should still exist after dropping an unreferenced column");

      // Now drop a column that IS referenced by the view.
      assertSuccess(sql("ALTER TABLE %s DROP (name)", table));

      // The view metadata should still exist — Gravitino does not cascade-invalidate views.
      Assertions.assertTrue(
          viewCatalog.viewExists(NameIdentifier.of(schemaName, view)),
          "view metadata should persist even after referenced column is dropped");
    } finally {
      quietDrop(view, table);
    }
  }

  /**
   * Renaming the base table leaves the view referencing the old table name. The view metadata is
   * still loadable, demonstrating that views store a snapshot of the SQL at creation time.
   */
  @Test
  @Order(4)
  @DisplayName("ALTER TABLE RENAME leaves view with stale table reference")
  public void testAlterTableRenameLeavesViewStale() {
    String table = "rename_base";
    String renamedTable = "rename_base_new";
    String view = "rename_view";

    try {
      assertSuccess(sql("CREATE TABLE %s (id INT) WITH ('connector'='paimon')", table));
      assertSuccess(sql("CREATE VIEW %s AS SELECT id FROM %s", view, table));

      assertSuccess(sql("ALTER TABLE %s RENAME TO %s", table, renamedTable));

      // View metadata should still exist.
      ViewCatalog viewCatalog = paimonCatalog.asViewCatalog();
      Assertions.assertTrue(
          viewCatalog.viewExists(NameIdentifier.of(schemaName, view)),
          "view should still exist after base table rename");
    } finally {
      quietDrop(view, null);
      sql("DROP TABLE IF EXISTS %s", renamedTable);
      sql("DROP TABLE IF EXISTS %s", table);
    }
  }

  /**
   * Dropping the base table entirely while a dependent view exists. The view metadata remains — no
   * cascade delete. This validates that the view layer and table layer are decoupled in Gravitino.
   */
  @Test
  @Order(5)
  @DisplayName("DROP TABLE does not cascade-delete dependent views")
  public void testDropTableDoesNotCascadeDeleteView() {
    String table = "cascade_base";
    String view = "cascade_view";

    try {
      assertSuccess(sql("CREATE TABLE %s (id INT, val STRING) WITH ('connector'='paimon')", table));
      assertSuccess(sql("CREATE VIEW %s AS SELECT id, val FROM %s", view, table));

      assertSuccess(sql("DROP TABLE %s", table));

      // View should still be present in the metadata catalog.
      ViewCatalog viewCatalog = paimonCatalog.asViewCatalog();
      Assertions.assertTrue(
          viewCatalog.viewExists(NameIdentifier.of(schemaName, view)),
          "view should survive base table deletion");

      // Clean up the orphaned view.
      assertSuccess(sql("DROP VIEW %s", view));
    } finally {
      quietDrop(view, table);
    }
  }

  // ---------------------------------------------------------------------------
  // Tests: views over a partitioned base table
  // ---------------------------------------------------------------------------

  /**
   * A view over a partitioned Paimon table is created and loadable. Paimon partitions by actual
   * column values (it has no Iceberg-style {@code days(ts)} transform), so we partition by a
   * dedicated {@code dt} day-string column. Verifies the view metadata captures all projected
   * columns including the partition column.
   */
  @Test
  @Order(6)
  @DisplayName("CREATE VIEW over a partitioned base table captures partition column")
  public void testViewOverPartitionedTable() {
    String table = "part_base";
    String view = "part_view";

    try {
      assertSuccess(
          sql(
              "CREATE TABLE %s (id INT, ts TIMESTAMP(3), dt STRING) PARTITIONED BY (dt)"
                  + " WITH ('connector'='paimon')",
              table));
      assertSuccess(sql("CREATE VIEW %s AS SELECT id, ts, dt FROM %s", view, table));

      ViewCatalog viewCatalog = paimonCatalog.asViewCatalog();
      org.apache.gravitino.rel.View loadedView =
          viewCatalog.loadView(NameIdentifier.of(schemaName, view));
      Assertions.assertEquals(
          3, loadedView.columns().length, "view should project id, ts and the dt partition column");
    } finally {
      quietDrop(view, table);
    }
  }

  /**
   * Inserting into a partitioned base table and querying through a view that filters on the
   * partition column returns only the matching partition rows, exercising partition pruning end to
   * end through S3.
   */
  @Test
  @Order(7)
  @DisplayName("Query through view with partition filter returns matching partition rows")
  public void testQueryViewWithPartitionFilter() {
    String table = "part_query_base";
    String view = "part_query_view";

    try {
      assertSuccess(
          sql(
              "CREATE TABLE %s (id INT, ts TIMESTAMP(3), dt STRING) PARTITIONED BY (dt)"
                  + " WITH ('connector'='paimon')",
              table));
      // The view restricts to a single partition.
      assertSuccess(
          sql("CREATE VIEW %s AS SELECT id, dt FROM %s WHERE dt = '2024-01-01'", view, table));

      sql(
              "INSERT INTO %s VALUES "
                  + "(1, TIMESTAMP '2024-01-01 12:00:00', '2024-01-01'), "
                  + "(2, TIMESTAMP '2024-01-02 09:30:00', '2024-01-02'), "
                  + "(3, TIMESTAMP '2024-01-01 18:45:00', '2024-01-01')",
              table)
          .await();

      java.util.List<Row> rows = collectRows(sql("SELECT * FROM %s ORDER BY id", view));
      Assertions.assertEquals(
          Arrays.asList(Row.of(1, "2024-01-01"), Row.of(3, "2024-01-01")),
          rows,
          "view should return only rows from the 2024-01-01 partition, ordered by id");
    } catch (Exception e) {
      throw new AssertionError("unexpected failure querying partitioned view", e);
    } finally {
      quietDrop(view, table);
    }
  }

  /**
   * Adding a new partition column-independent field to a partitioned base table does not break a
   * view over it; the view metadata and its partition-aware projection remain valid.
   */
  @Test
  @Order(8)
  @DisplayName("ALTER TABLE ADD COLUMN on partitioned table does not break view")
  public void testAlterPartitionedTableAddColumnKeepsView() {
    String table = "part_alter_base";
    String view = "part_alter_view";

    try {
      assertSuccess(
          sql(
              "CREATE TABLE %s (id INT, ts TIMESTAMP(3), dt STRING) PARTITIONED BY (dt)"
                  + " WITH ('connector'='paimon')",
              table));
      assertSuccess(sql("CREATE VIEW %s AS SELECT id, dt FROM %s", view, table));

      assertSuccess(sql("ALTER TABLE %s ADD (amount DECIMAL(10, 2))", table));

      ViewCatalog viewCatalog = paimonCatalog.asViewCatalog();
      Assertions.assertTrue(
          viewCatalog.viewExists(NameIdentifier.of(schemaName, view)),
          "view over partitioned table should survive ALTER TABLE ADD COLUMN");
      org.apache.gravitino.rel.View loadedView =
          viewCatalog.loadView(NameIdentifier.of(schemaName, view));
      Assertions.assertEquals(
          2, loadedView.columns().length, "view projection should be unchanged (id, dt)");
    } finally {
      quietDrop(view, table);
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  @FormatMethod
  private static TableResult sql(String sqlFmt, Object... args) {
    return tableEnv.executeSql(String.format(sqlFmt, args));
  }

  private static void assertSuccess(TableResult result) {
    Assertions.assertEquals(ResultKind.SUCCESS, result.getResultKind());
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

  private static void quietDrop(String view, String table) {
    if (view != null) {
      try {
        sql("DROP VIEW IF EXISTS %s", view);
      } catch (Exception e) {
        LOG.warn("cleanup: failed to drop view '{}'", view, e);
      }
    }
    if (table != null) {
      try {
        sql("DROP TABLE IF EXISTS %s", table);
      } catch (Exception e) {
        LOG.warn("cleanup: failed to drop table '{}'", table, e);
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
