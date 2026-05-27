/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package org.apache.gravitino.integration.test.trino;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.client.DefaultOAuth2TokenProvider;
import org.apache.gravitino.client.GravitinoAdminClient;
import org.apache.gravitino.client.GravitinoMetalake;
import org.apache.gravitino.exceptions.CatalogAlreadyExistsException;
import org.apache.gravitino.rel.Column;
import org.apache.gravitino.rel.SQLRepresentation;
import org.apache.gravitino.rel.View;
import org.apache.gravitino.rel.ViewCatalog;
import org.apache.gravitino.rel.types.Types;
import org.apache.gravitino.utils.RandomNameUtils;
import org.apache.spark.SparkConf;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * E2E tests for the generic view support feature (Iceberg catalog backed by S3) verified through
 * Trino JDBC → Iceberg REST Catalog (IRC) path.
 *
 * <p>Tests run against a real Gravitino + Trino deployment in Kubernetes (env2-oauth2-auth). The
 * test creates {@code catalog_iceberg_s3} idempotently, then exercises Trino DDL (CREATE VIEW,
 * CREATE OR REPLACE VIEW, DROP VIEW) and asserts the resulting state via the Gravitino client API
 * (loadView / viewExists).
 *
 * <ul>
 *   <li>T1 — {@code testTrinoCreateFilterViewIsLoadableViaGravitino}
 *   <li>T2 — {@code testTrinoCreateAggregationViewIsLoadableViaGravitino}
 *   <li>T3 — {@code testTrinoCreateJoinViewIsLoadableViaGravitino}
 *   <li>T4 — {@code testTrinoCreateNestedViewIsLoadableViaGravitino}
 *   <li>T5 — {@code testTrinoCreateOrReplaceViewIsReflectedInGravitino}
 *   <li>T6 — {@code testTrinoDropViewIsReflectedInGravitino}
 * </ul>
 */
@DisplayName("Trino Iceberg View E2E Tests (via IRC, S3 warehouse)")
public class TrinoIcebergRestGenericViewIT {

  private static final Logger LOG = LoggerFactory.getLogger(TrinoIcebergRestGenericViewIT.class);

  // ── Constants ─────────────────────────────────────────────────────────────

  private static final String METALAKE_NAME = "test";
  private static final String CATALOG_NAME = "catalog_iceberg_s3";

  /**
   * Trino catalog name — matches the properties file name {@code gravitino_irc_s3.properties}
   * mounted in the Trino container (without the {@code .properties} suffix).
   */
  private static final String TRINO_CATALOG = "gravitino_irc_s3";

  /**
   * Spark catalog name — registered in the SparkSession to point at the same IRC endpoint. Must
   * match the catalog name used in the spark-dialect SQL representations.
   */
  private static final String SPARK_CATALOG = "gravitino_irc_s3";

  private static final String DEFAULT_OAUTH2_REALM = "myrealm";
  private static final String DEFAULT_OAUTH2_SCOPE = "openid profile email";

  // ── Shared state ──────────────────────────────────────────────────────────

  private static GravitinoAdminClient adminClient;
  private static GravitinoMetalake metalake;
  private static Catalog catalog;
  private static ViewCatalog viewCatalog;
  private static Connection trinoConnection;

  /** Tracks whether this test run created the catalog (so we only drop what we created). */
  private static boolean catalogCreatedByTest = false;

  /** Per-test schema name (randomized to avoid collisions across parallel runs). */
  private static String schemaName;

  // ── Lifecycle ─────────────────────────────────────────────────────────────

  @BeforeAll
  public static void setup() throws Exception {
    // 1. Validate required AWS env vars.
    String awsAccessKeyId = requireEnv("AWS_ACCESS_KEY_ID");
    String awsSecretAccessKey = requireEnv("AWS_SECRET_ACCESS_KEY");
    String awsS3RoleArn = requireEnv("AWS_S3_ROLE_ARN");
    String awsS3Bucket = requireEnv("AWS_S3_BUCKET");
    String awsS3Region = System.getenv().getOrDefault("AWS_S3_REGION", "us-east-1");

    // 2. Validate required OAuth2 env vars.
    String oauth2ServerUri = requireEnv("OAUTH2_SERVER_URI");
    String oauth2ClientId = requireEnv("OAUTH2_CLIENT_ID");
    String oauth2ClientSecret = requireEnv("OAUTH2_CLIENT_SECRET");
    String oauth2Realm = System.getenv().getOrDefault("OAUTH2_REALM", DEFAULT_OAUTH2_REALM);
    String oauth2Scope = System.getenv().getOrDefault("OAUTH2_SCOPE", DEFAULT_OAUTH2_SCOPE);
    String oauth2TokenPath =
        System.getenv()
            .getOrDefault(
                "OAUTH2_TOKEN_PATH",
                String.format("realms/%s/protocol/openid-connect/token", oauth2Realm));
    if (oauth2TokenPath.startsWith("/")) {
      oauth2TokenPath = oauth2TokenPath.substring(1);
    }

    // 3. Build OAuth2 admin client (postman-client service-account).
    String gravitinoUri = System.getProperty("gravitino.uri", "http://localhost:30090");
    String credential = oauth2ClientId + ":" + oauth2ClientSecret;
    DefaultOAuth2TokenProvider tokenProvider =
        DefaultOAuth2TokenProvider.builder()
            .withUri(oauth2ServerUri)
            .withCredential(credential)
            .withScope(oauth2Scope)
            .withPath(oauth2TokenPath)
            .build();
    adminClient = GravitinoAdminClient.builder(gravitinoUri).withOAuth(tokenProvider).build();
    metalake = adminClient.loadMetalake(METALAKE_NAME);

    // 4. Idempotent catalog creation — treat AlreadyExists as success.
    Map<String, String> catalogProps = new HashMap<>();
    catalogProps.put("catalog-backend", "jdbc");
    catalogProps.put(
        "uri",
        "jdbc:postgresql://gravitino-env2-oauth2-auth-postgresql"
            + ".gravitino-e2e-env2-oauth2-auth:5432/gravitinoirc");
    catalogProps.put("jdbc-driver", "org.postgresql.Driver");
    catalogProps.put("jdbc-user", "gravitino");
    catalogProps.put("jdbc-password", "gravitino");
    catalogProps.put("jdbc-schema-version", "V1");
    catalogProps.put("jdbc-initialize", "true");
    catalogProps.put("io-impl", "org.apache.iceberg.aws.s3.S3FileIO");
    catalogProps.put(
        "warehouse",
        String.format(
            "s3://%s/gravitino-e2e-env2-oauth2-auth/test/catalog_iceberg_s3", awsS3Bucket));
    catalogProps.put("credential-providers", "s3-token");
    catalogProps.put("s3-token-expire-in-secs", "900");
    catalogProps.put("s3-access-key-id", awsAccessKeyId);
    catalogProps.put("s3-secret-access-key", awsSecretAccessKey);
    catalogProps.put("s3-region", awsS3Region);
    catalogProps.put("s3-role-arn", awsS3RoleArn);

    try {
      metalake.createCatalog(
          CATALOG_NAME,
          Catalog.Type.RELATIONAL,
          "lakehouse-iceberg",
          "S3-backed Iceberg catalog for Trino view E2E tests",
          catalogProps);
      catalogCreatedByTest = true;
      LOG.info("Created catalog '{}'", CATALOG_NAME);
    } catch (CatalogAlreadyExistsException e) {
      LOG.info("Catalog '{}' already exists — reusing", CATALOG_NAME);
    }

    catalog = metalake.loadCatalog(CATALOG_NAME);
    viewCatalog = catalog.asViewCatalog();

    // 5. Build Trino JDBC connection.
    String trinoUri = System.getProperty("gravitino.trino.uri", "http://localhost:30880");
    // Convert http://host:port → jdbc:trino://host:port
    String trinoJdbcUrl =
        "jdbc:trino://" + trinoUri.replaceFirst("^https?://", "") + "/" + TRINO_CATALOG;
    trinoConnection = DriverManager.getConnection(trinoJdbcUrl, "admin", null);
    LOG.info(
        "TrinoIcebergRestGenericViewIT setup complete: gravitinoUri={}, trinoJdbc={}",
        gravitinoUri,
        trinoJdbcUrl);

    // 6. Create a random schema for this test run.
    schemaName = RandomNameUtils.genRandomName("trino_view_e2e");
    executeTrinoDdl(String.format("CREATE SCHEMA %s.\"%s\"", TRINO_CATALOG, schemaName));
    LOG.info("Created test schema: {}", schemaName);
  }

  @AfterEach
  public void cleanSchema() {
    if (viewCatalog == null || schemaName == null) {
      return;
    }
    try {
      for (NameIdentifier v : viewCatalog.listViews(Namespace.of(schemaName))) {
        try {
          executeTrinoDdl(
              String.format(
                  "DROP VIEW IF EXISTS %s.\"%s\".\"%s\"", TRINO_CATALOG, schemaName, v.name()));
        } catch (Exception ex) {
          LOG.warn("Failed to drop view '{}' via Trino", v.name(), ex);
        }
      }
      for (NameIdentifier t : catalog.asTableCatalog().listTables(Namespace.of(schemaName))) {
        try {
          executeTrinoDdl(
              String.format(
                  "DROP TABLE IF EXISTS %s.\"%s\".\"%s\"", TRINO_CATALOG, schemaName, t.name()));
        } catch (Exception ex) {
          LOG.warn("Failed to drop table '{}' via Trino", t.name(), ex);
        }
      }
    } catch (Exception e) {
      LOG.warn("Per-test cleanup failed, proceeding anyway", e);
    }
  }

  @AfterAll
  public static void teardown() {
    // Drop schema.
    if (trinoConnection != null && schemaName != null) {
      try {
        executeTrinoDdl(
            String.format("DROP SCHEMA IF EXISTS %s.\"%s\"", TRINO_CATALOG, schemaName));
      } catch (Exception e) {
        LOG.warn("Failed to drop test schema '{}'", schemaName, e);
      }
    }

    // Drop catalog only if this test created it.
    if (catalogCreatedByTest && metalake != null) {
      try {
        metalake.dropCatalog(CATALOG_NAME, true);
        LOG.info("Dropped catalog '{}' (created by this test run)", CATALOG_NAME);
      } catch (Exception e) {
        LOG.warn("Failed to drop catalog '{}'", CATALOG_NAME, e);
      }
    }

    // Close JDBC.
    if (trinoConnection != null) {
      try {
        trinoConnection.close();
      } catch (Exception e) {
        LOG.warn("Failed to close Trino JDBC connection", e);
      }
    }

    // Close admin client.
    if (adminClient != null) {
      adminClient.close();
    }
  }

  // ── Test cases (T1–T6) — bodies to be filled in later ─────────────────────

  @Test
  @DisplayName("T1: Trino CREATE VIEW (filter) is loadable via Gravitino, with data round-trip")
  public void testTrinoCreateFilterViewIsLoadableViaGravitino() {
    String tableName = RandomNameUtils.genRandomName("products");
    String viewName = RandomNameUtils.genRandomName("electronics_view");

    // 1. Create base table and insert sample data.
    createProductsTable(tableName);
    executeTrinoDdl(
        String.format(
            "INSERT INTO %s.\"%s\".\"%s\" VALUES "
                + "(1, 'Laptop', 'Electronics', DECIMAL '999.99', 50), "
                + "(2, 'Headphones', 'Electronics', DECIMAL '149.99', 200), "
                + "(3, 'Desk Chair', 'Furniture', DECIMAL '299.99', 30), "
                + "(4, 'Notebook', 'Stationery', DECIMAL '4.99', 500), "
                + "(5, 'Monitor', 'Electronics', DECIMAL '399.99', 80)",
            TRINO_CATALOG, schemaName, tableName));

    // 2. Create a filter view via Trino.
    executeTrinoDdl(
        String.format(
            "CREATE VIEW %s.\"%s\".\"%s\" AS "
                + "SELECT product_id, product_name, price, stock "
                + "FROM %s.\"%s\".\"%s\" WHERE category = 'Electronics'",
            TRINO_CATALOG, schemaName, viewName, TRINO_CATALOG, schemaName, tableName));

    // 3. Gravitino-side assertions: view is loadable with expected columns.
    View loaded = viewCatalog.loadView(NameIdentifier.of(schemaName, viewName));
    Assertions.assertEquals(viewName, loaded.name(), "view name must match");
    assertViewHasColumns(loaded, "product_id", "product_name", "price", "stock");

    // 4. Data round-trip: SELECT through the view and verify filtered rows.
    java.util.List<String> productNames =
        queryFirstColumn(
            String.format(
                "SELECT product_name FROM %s.\"%s\".\"%s\" ORDER BY product_id",
                TRINO_CATALOG, schemaName, viewName));
    Assertions.assertEquals(3, productNames.size(), "filter view must return 3 Electronics rows");
    Assertions.assertEquals("Laptop", productNames.get(0));
    Assertions.assertEquals("Headphones", productNames.get(1));
    Assertions.assertEquals("Monitor", productNames.get(2));
  }

  @Test
  @DisplayName("T2: Trino CREATE VIEW (aggregation) is loadable via Gravitino")
  public void testTrinoCreateAggregationViewIsLoadableViaGravitino() {
    String tableName = RandomNameUtils.genRandomName("products");
    String viewName = RandomNameUtils.genRandomName("category_stats_view");

    // 1. Create base table and insert sample data.
    createProductsTable(tableName);
    executeTrinoDdl(
        String.format(
            "INSERT INTO %s.\"%s\".\"%s\" VALUES "
                + "(1, 'Laptop', 'Electronics', DECIMAL '999.99', 50), "
                + "(2, 'Headphones', 'Electronics', DECIMAL '149.99', 200), "
                + "(3, 'Desk Chair', 'Furniture', DECIMAL '299.99', 30), "
                + "(4, 'Notebook', 'Stationery', DECIMAL '4.99', 500), "
                + "(5, 'Monitor', 'Electronics', DECIMAL '399.99', 80)",
            TRINO_CATALOG, schemaName, tableName));

    // 2. Create an aggregation view via Trino (GROUP BY with aggregate functions).
    executeTrinoDdl(
        String.format(
            "CREATE VIEW %s.\"%s\".\"%s\" AS "
                + "SELECT category, COUNT(*) AS product_count, SUM(stock) AS total_stock "
                + "FROM %s.\"%s\".\"%s\" GROUP BY category",
            TRINO_CATALOG, schemaName, viewName, TRINO_CATALOG, schemaName, tableName));

    // 3. Gravitino-side assertions: view is loadable with expected columns.
    View loaded = viewCatalog.loadView(NameIdentifier.of(schemaName, viewName));
    Assertions.assertEquals(viewName, loaded.name(), "view name must match");
    assertViewHasColumns(loaded, "category", "product_count", "total_stock");

    // 4. Data round-trip: SELECT through the view and verify aggregated rows.
    java.util.List<String> categories =
        queryFirstColumn(
            String.format(
                "SELECT category FROM %s.\"%s\".\"%s\" ORDER BY category",
                TRINO_CATALOG, schemaName, viewName));
    Assertions.assertEquals(3, categories.size(), "aggregation view must return 3 category rows");
    Assertions.assertEquals("Electronics", categories.get(0));
    Assertions.assertEquals("Furniture", categories.get(1));
    Assertions.assertEquals("Stationery", categories.get(2));
  }

  @Test
  @DisplayName("T3: Trino CREATE VIEW (JOIN) is loadable via Gravitino")
  public void testTrinoCreateJoinViewIsLoadableViaGravitino() {
    String productsTable = RandomNameUtils.genRandomName("products");
    String ordersTable = RandomNameUtils.genRandomName("orders");
    String viewName = RandomNameUtils.genRandomName("order_details_view");

    // 1. Create base tables and insert sample data.
    createProductsTable(productsTable);
    createOrdersTable(ordersTable);
    executeTrinoDdl(
        String.format(
            "INSERT INTO %s.\"%s\".\"%s\" VALUES "
                + "(1, 'Laptop', 'Electronics', DECIMAL '999.99', 50), "
                + "(2, 'Headphones', 'Electronics', DECIMAL '149.99', 200), "
                + "(3, 'Desk Chair', 'Furniture', DECIMAL '299.99', 30)",
            TRINO_CATALOG, schemaName, productsTable));
    executeTrinoDdl(
        String.format(
            "INSERT INTO %s.\"%s\".\"%s\" VALUES "
                + "(101, 1, 2, DATE '2025-01-15'), "
                + "(102, 2, 5, DATE '2025-01-16'), "
                + "(103, 1, 1, DATE '2025-01-17')",
            TRINO_CATALOG, schemaName, ordersTable));

    // 2. Create a JOIN view via Trino.
    executeTrinoDdl(
        String.format(
            "CREATE VIEW %s.\"%s\".\"%s\" AS "
                + "SELECT o.order_id, p.product_name, o.quantity, o.order_date "
                + "FROM %s.\"%s\".\"%s\" o "
                + "JOIN %s.\"%s\".\"%s\" p ON o.product_id = p.product_id",
            TRINO_CATALOG,
            schemaName,
            viewName,
            TRINO_CATALOG,
            schemaName,
            ordersTable,
            TRINO_CATALOG,
            schemaName,
            productsTable));

    // 3. Gravitino-side assertions: view is loadable with expected columns.
    View loaded = viewCatalog.loadView(NameIdentifier.of(schemaName, viewName));
    Assertions.assertEquals(viewName, loaded.name(), "view name must match");
    assertViewHasColumns(loaded, "order_id", "product_name", "quantity", "order_date");

    // 4. Data round-trip: SELECT through the view and verify joined rows.
    java.util.List<String> productNames =
        queryFirstColumn(
            String.format(
                "SELECT product_name FROM %s.\"%s\".\"%s\" ORDER BY order_id",
                TRINO_CATALOG, schemaName, viewName));
    Assertions.assertEquals(3, productNames.size(), "join view must return 3 rows");
    Assertions.assertEquals("Laptop", productNames.get(0));
    Assertions.assertEquals("Headphones", productNames.get(1));
    Assertions.assertEquals("Laptop", productNames.get(2));
  }

  @Test
  @DisplayName("T4: Trino CREATE VIEW (nested, view-on-view) is loadable via Gravitino")
  public void testTrinoCreateNestedViewIsLoadableViaGravitino() {
    String tableName = RandomNameUtils.genRandomName("products");
    String baseViewName = RandomNameUtils.genRandomName("electronics_view");
    String nestedViewName = RandomNameUtils.genRandomName("expensive_electronics_view");

    // 1. Create base table and insert sample data.
    createProductsTable(tableName);
    executeTrinoDdl(
        String.format(
            "INSERT INTO %s.\"%s\".\"%s\" VALUES "
                + "(1, 'Laptop', 'Electronics', DECIMAL '999.99', 50), "
                + "(2, 'Headphones', 'Electronics', DECIMAL '149.99', 200), "
                + "(3, 'Desk Chair', 'Furniture', DECIMAL '299.99', 30), "
                + "(4, 'Monitor', 'Electronics', DECIMAL '399.99', 80), "
                + "(5, 'Keyboard', 'Electronics', DECIMAL '79.99', 300)",
            TRINO_CATALOG, schemaName, tableName));

    // 2. Create a base view (filter on Electronics).
    executeTrinoDdl(
        String.format(
            "CREATE VIEW %s.\"%s\".\"%s\" AS "
                + "SELECT product_id, product_name, price "
                + "FROM %s.\"%s\".\"%s\" WHERE category = 'Electronics'",
            TRINO_CATALOG, schemaName, baseViewName, TRINO_CATALOG, schemaName, tableName));

    // 3. Create a nested view (view-on-view: filter on price > 100).
    executeTrinoDdl(
        String.format(
            "CREATE VIEW %s.\"%s\".\"%s\" AS "
                + "SELECT product_id, product_name, price "
                + "FROM %s.\"%s\".\"%s\" WHERE price > DECIMAL '100.00'",
            TRINO_CATALOG, schemaName, nestedViewName, TRINO_CATALOG, schemaName, baseViewName));

    // 4. Gravitino-side assertions: nested view is loadable with expected columns.
    View loaded = viewCatalog.loadView(NameIdentifier.of(schemaName, nestedViewName));
    Assertions.assertEquals(nestedViewName, loaded.name(), "nested view name must match");
    assertViewHasColumns(loaded, "product_id", "product_name", "price");

    // 5. Data round-trip: SELECT through the nested view and verify doubly-filtered rows.
    java.util.List<String> productNames =
        queryFirstColumn(
            String.format(
                "SELECT product_name FROM %s.\"%s\".\"%s\" ORDER BY product_id",
                TRINO_CATALOG, schemaName, nestedViewName));
    Assertions.assertEquals(
        3, productNames.size(), "nested view must return 3 Electronics with price > 100");
    Assertions.assertEquals("Laptop", productNames.get(0));
    Assertions.assertEquals("Headphones", productNames.get(1));
    Assertions.assertEquals("Monitor", productNames.get(2));
  }

  @Test
  @DisplayName("T5: Trino CREATE OR REPLACE VIEW is reflected in Gravitino")
  public void testTrinoCreateOrReplaceViewIsReflectedInGravitino() {
    String tableName = RandomNameUtils.genRandomName("products");
    String viewName = RandomNameUtils.genRandomName("replaceable_view");

    // 1. Create base table and insert sample data.
    createProductsTable(tableName);
    executeTrinoDdl(
        String.format(
            "INSERT INTO %s.\"%s\".\"%s\" VALUES "
                + "(1, 'Laptop', 'Electronics', DECIMAL '999.99', 50), "
                + "(2, 'Headphones', 'Electronics', DECIMAL '149.99', 200), "
                + "(3, 'Desk Chair', 'Furniture', DECIMAL '299.99', 30)",
            TRINO_CATALOG, schemaName, tableName));

    // 2. Create the initial view with two columns.
    executeTrinoDdl(
        String.format(
            "CREATE VIEW %s.\"%s\".\"%s\" AS "
                + "SELECT product_id, product_name FROM %s.\"%s\".\"%s\"",
            TRINO_CATALOG, schemaName, viewName, TRINO_CATALOG, schemaName, tableName));

    // 3. Verify initial view via Gravitino.
    View initial = viewCatalog.loadView(NameIdentifier.of(schemaName, viewName));
    assertViewHasColumns(initial, "product_id", "product_name");

    // 4. Replace the view with a different column set.
    executeTrinoDdl(
        String.format(
            "CREATE OR REPLACE VIEW %s.\"%s\".\"%s\" AS "
                + "SELECT product_name, category, price "
                + "FROM %s.\"%s\".\"%s\" WHERE price > DECIMAL '100.00'",
            TRINO_CATALOG, schemaName, viewName, TRINO_CATALOG, schemaName, tableName));

    // 5. Gravitino-side assertions: replaced view has the new columns.
    View replaced = viewCatalog.loadView(NameIdentifier.of(schemaName, viewName));
    Assertions.assertEquals(viewName, replaced.name(), "view name must match after replace");
    assertViewHasColumns(replaced, "product_name", "category", "price");

    // 6. Data round-trip: verify the replaced view returns filtered data.
    java.util.List<String> productNames =
        queryFirstColumn(
            String.format(
                "SELECT product_name FROM %s.\"%s\".\"%s\" ORDER BY product_name",
                TRINO_CATALOG, schemaName, viewName));
    Assertions.assertEquals(
        3, productNames.size(), "replaced view must return rows with price > 100");
    Assertions.assertEquals("Desk Chair", productNames.get(0));
    Assertions.assertEquals("Headphones", productNames.get(1));
    Assertions.assertEquals("Laptop", productNames.get(2));
  }

  @Test
  @DisplayName("T6: Trino DROP VIEW is reflected in Gravitino")
  public void testTrinoDropViewIsReflectedInGravitino() {
    String tableName = RandomNameUtils.genRandomName("products");
    String viewName = RandomNameUtils.genRandomName("drop_me_view");

    // 1. Create base table and a view.
    createProductsTable(tableName);
    executeTrinoDdl(
        String.format(
            "CREATE VIEW %s.\"%s\".\"%s\" AS "
                + "SELECT product_id, product_name FROM %s.\"%s\".\"%s\"",
            TRINO_CATALOG, schemaName, viewName, TRINO_CATALOG, schemaName, tableName));

    // 2. Verify the view exists in Gravitino.
    Assertions.assertTrue(
        viewCatalog.viewExists(NameIdentifier.of(schemaName, viewName)),
        "view must exist before drop");

    // 3. Drop the view via Trino.
    executeTrinoDdl(
        String.format("DROP VIEW %s.\"%s\".\"%s\"", TRINO_CATALOG, schemaName, viewName));

    // 4. Verify the view no longer exists in Gravitino.
    Assertions.assertFalse(
        viewCatalog.viewExists(NameIdentifier.of(schemaName, viewName)),
        "view must not exist after drop");
  }

  // ── Cross-engine tests (T7–T8) ───────────────────────────────────────────

  @Test
  @DisplayName(
      "T7: View created via Gravitino API with trino+spark dialects is queryable through"
          + " both Trino and Spark")
  public void testGravitinoCreatedMultiDialectViewIsQueryableViaBothEngines() {
    String tableName = RandomNameUtils.genRandomName("products");
    String viewName = RandomNameUtils.genRandomName("multi_dialect_view");
    String sparkCatalogName = SPARK_CATALOG;

    // 1. Create base table and insert data via Trino.
    createProductsTable(tableName);
    executeTrinoDdl(
        String.format(
            "INSERT INTO %s.\"%s\".\"%s\" VALUES "
                + "(1, 'Laptop', 'Electronics', DECIMAL '999.99', 50), "
                + "(2, 'Headphones', 'Electronics', DECIMAL '149.99', 200), "
                + "(3, 'Desk Chair', 'Furniture', DECIMAL '299.99', 30)",
            TRINO_CATALOG, schemaName, tableName));

    // 2. Create a view via Gravitino API with DIFFERENT filter conditions per dialect.
    //    Trino dialect filters on 'Electronics' (2 rows), Spark dialect filters on 'Furniture'
    //    (1 row). This proves each engine picks the correct dialect representation.
    String trinoSql =
        String.format(
            "SELECT product_id, product_name, price FROM \"%s\".\"%s\".\"%s\" WHERE category = 'Electronics'",
            TRINO_CATALOG, schemaName, tableName);
    String sparkSql =
        String.format(
            "SELECT product_id, product_name, price FROM %s.%s.%s WHERE category = 'Furniture'",
            sparkCatalogName, schemaName, tableName);

    viewCatalog.createView(
        NameIdentifier.of(schemaName, viewName),
        "Multi-dialect view with different filters per dialect",
        new Column[] {
          Column.of("product_id", Types.LongType.get(), null),
          Column.of("product_name", Types.StringType.get(), null),
          Column.of("price", Types.DecimalType.of(10, 2), null)
        },
        new SQLRepresentation[] {
          SQLRepresentation.builder().withDialect("trino").withSql(trinoSql).build(),
          SQLRepresentation.builder().withDialect("spark").withSql(sparkSql).build()
        },
        sparkCatalogName,
        schemaName,
        Collections.emptyMap());

    // 3. Verify the view is loadable via Gravitino with both representations.
    View loaded = viewCatalog.loadView(NameIdentifier.of(schemaName, viewName));
    Assertions.assertEquals(viewName, loaded.name(), "view name must match");
    assertViewHasColumns(loaded, "product_id", "product_name", "price");

    // 4. Query the view through Trino — must use trino dialect (Electronics, 2 rows).
    List<String> trinoResults =
        queryFirstColumn(
            String.format(
                "SELECT product_name FROM %s.\"%s\".\"%s\" ORDER BY product_id",
                TRINO_CATALOG, schemaName, viewName));
    Assertions.assertEquals(
        2,
        trinoResults.size(),
        "Trino must return 2 rows (Electronics) proving it used the trino dialect");
    Assertions.assertEquals("Laptop", trinoResults.get(0));
    Assertions.assertEquals("Headphones", trinoResults.get(1));

    // 5. Query the view through Spark — must use spark dialect (Furniture, 1 row).
    SparkSession spark = newSparkSessionWithOAuth2(sparkCatalogName, "t7-cross-engine-spark");
    try {
      String viewFqn = String.format("%s.%s.%s", sparkCatalogName, schemaName, viewName);
      List<Row> sparkResults =
          spark
              .sql(String.format("SELECT product_name FROM %s ORDER BY product_id", viewFqn))
              .collectAsList();
      Assertions.assertEquals(
          1,
          sparkResults.size(),
          "Spark must return 1 row (Furniture) proving it used the spark dialect");
      Assertions.assertEquals("Desk Chair", sparkResults.get(0).getString(0));
    } finally {
      closeSparkQuietly(spark);
    }
  }

  @Test
  @DisplayName(
      "T8: View created via Gravitino API with aggregation (trino+spark dialects) is queryable"
          + " through both engines with correct dialect selection")
  public void testGravitinoCreatedAggregationMultiDialectViewIsQueryableViaBothEngines() {
    String tableName = RandomNameUtils.genRandomName("products");
    String viewName = RandomNameUtils.genRandomName("agg_multi_dialect_view");
    String sparkCatalogName = SPARK_CATALOG;

    // 1. Create base table and insert data via Trino.
    createProductsTable(tableName);
    executeTrinoDdl(
        String.format(
            "INSERT INTO %s.\"%s\".\"%s\" VALUES "
                + "(1, 'Laptop', 'Electronics', DECIMAL '999.99', 50), "
                + "(2, 'Headphones', 'Electronics', DECIMAL '149.99', 200), "
                + "(3, 'Desk Chair', 'Furniture', DECIMAL '299.99', 30), "
                + "(4, 'Notebook', 'Stationery', DECIMAL '4.99', 500)",
            TRINO_CATALOG, schemaName, tableName));

    // 2. Create an aggregation view with DIFFERENT aggregation per dialect.
    //    Trino dialect: aggregates ALL categories (3 rows).
    //    Spark dialect: aggregates only Electronics (1 row with count=2).
    //    This proves each engine picks the correct dialect.
    String trinoSql =
        String.format(
            "SELECT category, COUNT(*) AS product_count, SUM(stock) AS total_stock "
                + "FROM \"%s\".\"%s\".\"%s\" GROUP BY category",
            TRINO_CATALOG, schemaName, tableName);
    String sparkSql =
        String.format(
            "SELECT category, COUNT(*) AS product_count, SUM(stock) AS total_stock "
                + "FROM %s.%s.%s WHERE category = 'Electronics' GROUP BY category",
            sparkCatalogName, schemaName, tableName);

    viewCatalog.createView(
        NameIdentifier.of(schemaName, viewName),
        "Multi-dialect aggregation view with different logic per dialect",
        new Column[] {
          Column.of("category", Types.StringType.get(), null),
          Column.of("product_count", Types.LongType.get(), null),
          Column.of("total_stock", Types.LongType.get(), null)
        },
        new SQLRepresentation[] {
          SQLRepresentation.builder().withDialect("trino").withSql(trinoSql).build(),
          SQLRepresentation.builder().withDialect("spark").withSql(sparkSql).build()
        },
        sparkCatalogName,
        schemaName,
        Collections.emptyMap());

    // 3. Query through Trino — must use trino dialect (all categories, 3 rows).
    List<String> trinoCategories =
        queryFirstColumn(
            String.format(
                "SELECT category FROM %s.\"%s\".\"%s\" ORDER BY category",
                TRINO_CATALOG, schemaName, viewName));
    Assertions.assertEquals(
        3,
        trinoCategories.size(),
        "Trino must return 3 category rows proving it used the trino dialect");
    Assertions.assertEquals("Electronics", trinoCategories.get(0));
    Assertions.assertEquals("Furniture", trinoCategories.get(1));
    Assertions.assertEquals("Stationery", trinoCategories.get(2));

    // 4. Query through Spark — must use spark dialect (only Electronics, 1 row).
    SparkSession spark = newSparkSessionWithOAuth2(sparkCatalogName, "t8-cross-engine-spark");
    try {
      String viewFqn = String.format("%s.%s.%s", sparkCatalogName, schemaName, viewName);
      List<Row> sparkResults =
          spark
              .sql(String.format("SELECT category FROM %s ORDER BY category", viewFqn))
              .collectAsList();
      Assertions.assertEquals(
          1,
          sparkResults.size(),
          "Spark must return 1 row (Electronics only) proving it used the spark dialect");
      Assertions.assertEquals("Electronics", sparkResults.get(0).getString(0));
    } finally {
      closeSparkQuietly(spark);
    }
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  /** Executes a Trino DDL/DML statement (no result set expected). */
  private static void executeTrinoDdl(String sql) {
    try (Statement stmt = trinoConnection.createStatement()) {
      stmt.execute(sql);
    } catch (SQLException e) {
      throw new AssertionError("Trino SQL failed: " + sql, e);
    }
  }

  /** Executes a Trino query and returns the result set rows as a simple string list (col1). */
  private static java.util.List<String> queryFirstColumn(String sql) {
    java.util.List<String> results = new java.util.ArrayList<>();
    try (Statement stmt = trinoConnection.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      while (rs.next()) {
        results.add(rs.getString(1));
      }
    } catch (SQLException e) {
      throw new AssertionError("Trino query failed: " + sql, e);
    }
    return results;
  }

  /**
   * Asserts the loaded Gravitino {@link View} contains every expected column name (order-agnostic).
   */
  private static void assertViewHasColumns(View view, String... expected) {
    Set<String> actual =
        Arrays.stream(view.columns()).map(Column::name).collect(Collectors.toSet());
    for (String name : expected) {
      Assertions.assertTrue(
          actual.contains(name),
          "view must contain column '" + name + "'; actual columns=" + actual);
    }
  }

  /** Creates the products fixture table via Trino DDL. */
  private static void createProductsTable(String tableName) {
    executeTrinoDdl(
        String.format(
            "CREATE TABLE %s.\"%s\".\"%s\" ("
                + "product_id bigint, "
                + "product_name varchar, "
                + "category varchar, "
                + "price decimal(10,2), "
                + "stock integer"
                + ")",
            TRINO_CATALOG, schemaName, tableName));
  }

  /** Creates the orders fixture table via Trino DDL. */
  private static void createOrdersTable(String tableName) {
    executeTrinoDdl(
        String.format(
            "CREATE TABLE %s.\"%s\".\"%s\" ("
                + "order_id bigint, "
                + "product_id bigint, "
                + "quantity integer, "
                + "order_date date"
                + ")",
            TRINO_CATALOG, schemaName, tableName));
  }

  /** Required env var; fails fast at @BeforeAll if missing. */
  private static String requireEnv(String name) {
    String value = System.getenv(name);
    if (value == null || value.isEmpty()) {
      throw new IllegalStateException(
          "Required environment variable not set: "
              + name
              + ". Set GRAVITINO_TEST_AWS_IT=true and provide AWS/OAuth2 env vars.");
    }
    return value;
  }

  /**
   * Builds a SparkSession with the Iceberg REST catalog registered under the given catalog name,
   * authenticated via OAuth2 client_credentials grant against the same Keycloak used by the test.
   * Uses the {@code credential} + {@code oauth2-server-uri} pattern (Spark/Iceberg fetches the
   * token itself) instead of a pre-minted static bearer token.
   */
  private static SparkSession newSparkSessionWithOAuth2(String catalogName, String appName) {
    String oauth2ServerUri = requireEnv("OAUTH2_SERVER_URI");
    String oauth2ClientId = System.getenv().getOrDefault("OAUTH2_CLIENT_ID", "postman-client");
    String oauth2ClientSecret = requireEnv("OAUTH2_CLIENT_SECRET");
    String oauth2Realm = System.getenv().getOrDefault("OAUTH2_REALM", "myrealm");
    String oauth2Scope = System.getenv().getOrDefault("OAUTH2_SCOPE", "openid");
    String oauth2TokenUri =
        String.format("%s/realms/%s/protocol/openid-connect/token", oauth2ServerUri, oauth2Realm);

    String ircBaseUri = System.getProperty("gravitino.irc.uri", "http://localhost:30001/iceberg/");

    String base = "spark.sql.catalog." + catalogName;
    SparkConf conf =
        new SparkConf()
            .set(
                "spark.sql.extensions",
                "org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions")
            .set("spark.sql.defaultCatalog", catalogName)
            .set(base, "org.apache.iceberg.spark.SparkCatalog")
            .set(base + ".type", "rest")
            .set(base + ".uri", ircBaseUri)
            .set(base + ".prefix", CATALOG_NAME)
            .set(base + ".warehouse", CATALOG_NAME)
            .set(base + ".header.X-Iceberg-Access-Delegation", "vended-credentials")
            .set(base + ".rest.auth.type", "oauth2")
            .set(base + ".credential", oauth2ClientId + ":" + oauth2ClientSecret)
            .set(base + ".oauth2-server-uri", oauth2TokenUri)
            .set(base + ".scope", oauth2Scope)
            .set(base + ".token-exchange-enabled", "false")
            .set("spark.locality.wait.node", "0");
    return SparkSession.builder().master("local[2]").appName(appName).config(conf).getOrCreate();
  }

  /** Closes a SparkSession, swallowing any exception (test-cleanup helper). */
  private static void closeSparkQuietly(SparkSession spark) {
    if (spark != null) {
      try {
        spark.close();
      } catch (Exception e) {
        LOG.warn("Failed to close SparkSession", e);
      }
    }
  }
}
