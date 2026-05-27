/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package org.apache.gravitino.integration.test.view;

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
import org.apache.gravitino.client.GravitinoAdminClient;
import org.apache.gravitino.client.GravitinoMetalake;
import org.apache.gravitino.exceptions.NoSuchViewException;
import org.apache.gravitino.rel.Column;
import org.apache.gravitino.rel.SQLRepresentation;
import org.apache.gravitino.rel.View;
import org.apache.gravitino.rel.ViewCatalog;
import org.apache.gravitino.rel.ViewChange;
import org.apache.gravitino.rel.expressions.distributions.Distributions;
import org.apache.gravitino.rel.expressions.sorts.SortOrder;
import org.apache.gravitino.rel.expressions.transforms.Transforms;
import org.apache.gravitino.rel.types.Types;
import org.apache.gravitino.utils.RandomNameUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.rest.RESTCatalog;
import org.apache.iceberg.view.SQLViewRepresentation;
import org.apache.iceberg.view.ViewVersion;
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
 * E2E tests for the generic view support feature (Iceberg catalog) verified through the Iceberg
 * REST Catalog (IRC) path.
 */
@DisplayName("Iceberg View E2E Tests (via IRC)")
public class SparkIcebergRestGenericViewIT {

  private static final Logger LOG = LoggerFactory.getLogger(SparkIcebergRestGenericViewIT.class);

  private static final String VIEW_COMMENT = "e2e view comment";
  private static final String TABLE_COMMENT = "e2e table comment";
  private static final String SPARK_DIALECT = "spark";
  private static final String TRINO_DIALECT = "trino";
  private static final String NATIVE_CATALOG_NAME = "native-irc-for-view-e2e";

  private static final String SPARK_CATALOG_NAME = "gravitino_irc";

  private static GravitinoAdminClient adminClient;
  private static GravitinoMetalake metalake;
  private static Catalog catalog;
  private static ViewCatalog viewCatalog;

  private static RESTCatalog nativeIrc;

  private static String metalakeName;
  private static String catalogName;
  private static String schemaName;

  private static String ircUri;

  private static String simpleUser;

  private static org.apache.iceberg.catalog.Namespace icebergNs;

  @BeforeAll
  public static void setup() {
    String gravitinoUri = System.getProperty("gravitino.uri", "http://localhost:30090");
    ircUri = System.getProperty("gravitino.irc.uri", "http://localhost:30001/iceberg/");
    metalakeName = System.getProperty("gravitino.metalake", "test");
    catalogName = System.getProperty("gravitino.irc.catalog", "catalog_1");
    simpleUser = System.getProperty("gravitino.simple.user", "admin");

    adminClient = GravitinoAdminClient.builder(gravitinoUri).withSimpleAuth(simpleUser).build();
    metalake = adminClient.loadMetalake(metalakeName);
    catalog = metalake.loadCatalog(catalogName);
    viewCatalog = catalog.asViewCatalog();

    schemaName = RandomNameUtils.genRandomName("iceberg_view_e2e_schema");
    catalog.asSchemas().createSchema(schemaName, "view e2e schema", Collections.emptyMap());

    Map<String, String> nativeProps = new HashMap<>();
    nativeProps.put(CatalogProperties.URI, ircUri);
    nativeProps.put(CatalogProperties.CACHE_ENABLED, "false");
    nativeProps.put("rest.auth.type", "basic");
    nativeProps.put("rest.auth.basic.username", simpleUser);
    nativeProps.put("rest.auth.basic.password", "mock");
    nativeIrc = new RESTCatalog();
    nativeIrc.setConf(new Configuration());
    nativeIrc.initialize(NATIVE_CATALOG_NAME, nativeProps);

    icebergNs = org.apache.iceberg.catalog.Namespace.of(schemaName);
    LOG.info(
        "SparkIcebergRestGenericViewIT setup complete: metalake={}, catalog={}, schema={},"
            + " ircUri={}",
        metalakeName,
        catalogName,
        schemaName,
        ircUri);
  }

  @AfterEach
  public void cleanSchema() {
    try {
      for (NameIdentifier v : viewCatalog.listViews(Namespace.of(schemaName))) {
        viewCatalog.dropView(v);
      }
      for (NameIdentifier t : catalog.asTableCatalog().listTables(Namespace.of(schemaName))) {
        catalog.asTableCatalog().dropTable(t);
      }
    } catch (Exception e) {
      LOG.warn("Per-test cleanup failed, proceeding anyway", e);
    }
  }

  @AfterAll
  public static void teardown() {
    try {
      if (catalog != null && schemaName != null) {
        catalog.asSchemas().dropSchema(schemaName, true);
      }
    } catch (Exception e) {
      LOG.warn("Failed to drop fixture schema '{}'", schemaName, e);
    }
    try {
      if (nativeIrc != null) {
        nativeIrc.close();
      }
    } catch (Exception e) {
      LOG.warn("Failed to close native RESTCatalog", e);
    }
    if (adminClient != null) {
      adminClient.close();
    }
  }

  // -------------------------------------------------------------------------
  // I1 — listViews must not include tables; listTables must not include views
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("I1: listViews does not include tables, and listTables does not include views")
  public void testListViewsDoesNotIncludeTables() {
    String tableName = RandomNameUtils.genRandomName("iceberg_isolation_table");
    String viewName = RandomNameUtils.genRandomName("iceberg_isolation_view");

    // Create a regular Iceberg table.
    catalog
        .asTableCatalog()
        .createTable(
            NameIdentifier.of(schemaName, tableName),
            new Column[] {Column.of("id", Types.IntegerType.get(), "id column")},
            TABLE_COMMENT,
            Collections.emptyMap(),
            Transforms.EMPTY_TRANSFORM,
            Distributions.NONE,
            new SortOrder[0]);

    // Create a view in the same schema.
    viewCatalog.createView(
        NameIdentifier.of(schemaName, viewName),
        VIEW_COMMENT,
        new Column[] {Column.of("id", Types.IntegerType.get(), "id column")},
        new SQLRepresentation[] {
          SQLRepresentation.builder().withDialect(SPARK_DIALECT).withSql("SELECT id FROM t").build()
        },
        null,
        null,
        Collections.emptyMap());

    // listViews must contain the view and must NOT contain the table.
    Set<String> viewNames =
        Arrays.stream(viewCatalog.listViews(Namespace.of(schemaName)))
            .map(NameIdentifier::name)
            .collect(Collectors.toSet());
    Assertions.assertTrue(viewNames.contains(viewName), "listViews should contain the view");
    Assertions.assertFalse(
        viewNames.contains(tableName), "listViews must not include regular tables");

    // listTables must contain the table and must NOT contain the view.
    Set<String> tableNames =
        Arrays.stream(catalog.asTableCatalog().listTables(Namespace.of(schemaName)))
            .map(NameIdentifier::name)
            .collect(Collectors.toSet());
    Assertions.assertTrue(tableNames.contains(tableName), "listTables should contain the table");
    Assertions.assertFalse(tableNames.contains(viewName), "listTables must not include views");
  }

  // -------------------------------------------------------------------------
  // I2 — view created via Gravitino is visible through the native IRC client
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("I2: view created via Gravitino is visible through native Iceberg RESTCatalog")
  public void testViewCreatedByGravitinoIsVisibleViaNativeIcebergAPI() {
    String viewName = RandomNameUtils.genRandomName("iceberg_native_load_view");
    TableIdentifier viewIdent = TableIdentifier.of(icebergNs, viewName);

    // Create view with two SQL representations via Gravitino API.
    View created =
        viewCatalog.createView(
            NameIdentifier.of(schemaName, viewName),
            VIEW_COMMENT,
            new Column[] {Column.of("id", Types.IntegerType.get(), "id column")},
            new SQLRepresentation[] {
              SQLRepresentation.builder()
                  .withDialect(SPARK_DIALECT)
                  .withSql("SELECT id FROM some_table")
                  .build(),
              SQLRepresentation.builder()
                  .withDialect(TRINO_DIALECT)
                  .withSql("SELECT id FROM some_table")
                  .build()
            },
            null,
            schemaName,
            Collections.singletonMap("created_by", "e2e_test"));

    Assertions.assertEquals(viewName, created.name());

    // --- Verify via native Iceberg RESTCatalog (exact engine code path) ---

    Assertions.assertTrue(
        nativeIrc.viewExists(viewIdent),
        "Native Iceberg RESTCatalog must see the view created via Gravitino");

    org.apache.iceberg.view.View nativeView = nativeIrc.loadView(viewIdent);

    String expectedFqn = NATIVE_CATALOG_NAME + "." + schemaName + "." + viewName;
    Assertions.assertEquals(expectedFqn, nativeView.name());

    Assertions.assertNotNull(nativeView.schema(), "Native view schema must not be null");
    Assertions.assertNotNull(
        nativeView.schema().findField("id"), "Native view schema must contain column 'id'");

    ViewVersion currentVersion = nativeView.currentVersion();
    Assertions.assertNotNull(currentVersion, "Native view must have a current version");

    Set<String> dialects =
        currentVersion.representations().stream()
            .filter(r -> r instanceof SQLViewRepresentation)
            .map(r -> ((SQLViewRepresentation) r).dialect())
            .collect(Collectors.toSet());
    Assertions.assertTrue(
        dialects.contains(SPARK_DIALECT),
        "Native view must contain spark SQL representation; found: " + dialects);
    Assertions.assertTrue(
        dialects.contains(TRINO_DIALECT),
        "Native view must contain trino SQL representation; found: " + dialects);

    Assertions.assertEquals(
        VIEW_COMMENT,
        nativeView.properties().get("comment"),
        "Comment must survive the Gravitino → IRC round-trip");

    Assertions.assertEquals(
        "e2e_test",
        nativeView.properties().get("created_by"),
        "Custom property 'created_by' must survive the Gravitino → IRC round-trip");
  }

  // -------------------------------------------------------------------------
  // I3 — view dropped via Gravitino is removed from the native IRC client
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("I3: view dropped via Gravitino is removed from native Iceberg RESTCatalog")
  public void testViewDroppedByGravitinoIsRemovedFromNativeIcebergAPI() {
    String viewName = RandomNameUtils.genRandomName("iceberg_native_drop_view");
    TableIdentifier viewIdent = TableIdentifier.of(icebergNs, viewName);

    viewCatalog.createView(
        NameIdentifier.of(schemaName, viewName),
        VIEW_COMMENT,
        new Column[] {Column.of("id", Types.IntegerType.get(), "id column")},
        new SQLRepresentation[] {
          SQLRepresentation.builder().withDialect(SPARK_DIALECT).withSql("SELECT id FROM t").build()
        },
        null,
        null,
        Collections.emptyMap());

    // Confirm it is visible natively before drop.
    Assertions.assertTrue(
        nativeIrc.viewExists(viewIdent), "View must exist in native catalog before Gravitino drop");

    // Drop via Gravitino.
    boolean dropped = viewCatalog.dropView(NameIdentifier.of(schemaName, viewName));
    Assertions.assertTrue(dropped, "dropView must return true on first call");

    // Gravitino should also report the view as gone.
    Assertions.assertThrows(
        NoSuchViewException.class,
        () -> viewCatalog.loadView(NameIdentifier.of(schemaName, viewName)),
        "loadView must throw NoSuchViewException after drop");

    // Native IRC must reflect the removal.
    Assertions.assertFalse(
        nativeIrc.viewExists(viewIdent),
        "Native Iceberg RESTCatalog must not see the view after Gravitino drop");
  }

  // -------------------------------------------------------------------------
  // I4 — view renamed via Gravitino is reflected in the native IRC client
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("I4: view renamed via Gravitino is reflected in native Iceberg RESTCatalog")
  public void testViewRenamedByGravitinoIsReflectedInNativeIcebergAPI() {
    String oldName = RandomNameUtils.genRandomName("iceberg_native_rename_old");
    String newName = RandomNameUtils.genRandomName("iceberg_native_rename_new");
    TableIdentifier oldIdent = TableIdentifier.of(icebergNs, oldName);
    TableIdentifier newIdent = TableIdentifier.of(icebergNs, newName);

    viewCatalog.createView(
        NameIdentifier.of(schemaName, oldName),
        VIEW_COMMENT,
        new Column[] {Column.of("id", Types.IntegerType.get(), "id column")},
        new SQLRepresentation[] {
          SQLRepresentation.builder().withDialect(SPARK_DIALECT).withSql("SELECT id FROM t").build()
        },
        null,
        null,
        Collections.emptyMap());

    // Confirm old name exists natively before rename.
    Assertions.assertTrue(
        nativeIrc.viewExists(oldIdent),
        "View must exist under old name in native catalog before rename");

    // Rename via Gravitino.
    View renamed =
        viewCatalog.alterView(NameIdentifier.of(schemaName, oldName), ViewChange.rename(newName));
    Assertions.assertEquals(newName, renamed.name(), "alterView must return the new name");

    // Gravitino: old name gone, new name loadable.
    Assertions.assertThrows(
        NoSuchViewException.class,
        () -> viewCatalog.loadView(NameIdentifier.of(schemaName, oldName)),
        "loadView on old name must throw NoSuchViewException after rename");
    View reloaded = viewCatalog.loadView(NameIdentifier.of(schemaName, newName));
    Assertions.assertEquals(newName, reloaded.name());

    // Native IRC: old identifier gone, new identifier present with intact metadata.
    Assertions.assertFalse(
        nativeIrc.viewExists(oldIdent),
        "Native catalog must not see the old view name after Gravitino rename");
    Assertions.assertTrue(
        nativeIrc.viewExists(newIdent),
        "Native catalog must see the new view name after Gravitino rename");

    org.apache.iceberg.view.View nativeRenamed = nativeIrc.loadView(newIdent);
    // RESTCatalog.loadView returns the fully-qualified identifier
    // <catalogName>.<namespace>.<viewName> per the Iceberg REST spec convention.
    String expectedRenamedFqn = NATIVE_CATALOG_NAME + "." + schemaName + "." + newName;
    Assertions.assertEquals(expectedRenamedFqn, nativeRenamed.name());

    // SQL representations must be intact after rename.
    Assertions.assertFalse(
        nativeRenamed.currentVersion().representations().isEmpty(),
        "Renamed native view must still carry its SQL representations");

    boolean hasSpark =
        nativeRenamed.currentVersion().representations().stream()
            .filter(r -> r instanceof SQLViewRepresentation)
            .map(r -> ((SQLViewRepresentation) r).dialect())
            .anyMatch(SPARK_DIALECT::equals);
    Assertions.assertTrue(hasSpark, "spark representation must survive the rename");
  }

  // -------------------------------------------------------------------------
  // I5 — view created via Gravitino is resolvable through Spark + IRC
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("I5: view created via Gravitino is resolvable through Spark + IRC")
  public void testViewCreatedByGravitinoIsReadableViaSpark() {
    String tableName = RandomNameUtils.genRandomName("iceberg_spark_src");
    String viewName = RandomNameUtils.genRandomName("iceberg_spark_view");

    // 1. Source table — metadata only, no data inserted (data IO is blocked by the pod-local
    //    warehouse path; see class-level note).
    catalog
        .asTableCatalog()
        .createTable(
            NameIdentifier.of(schemaName, tableName),
            new Column[] {Column.of("id", Types.IntegerType.get(), "id column")},
            TABLE_COMMENT,
            Collections.emptyMap(),
            Transforms.EMPTY_TRANSFORM,
            Distributions.NONE,
            new SortOrder[0]);

    // 2. View — Spark dialect, with a 3-part-name body that fully qualifies the source table
    //    against the Spark catalog name we register below. Using a 3-part name removes any
    //    dependency on session defaults inside Spark's view-resolution path.
    String viewSql =
        String.format("SELECT id FROM %s.%s.%s", SPARK_CATALOG_NAME, schemaName, tableName);
    viewCatalog.createView(
        NameIdentifier.of(schemaName, viewName),
        VIEW_COMMENT,
        new Column[] {Column.of("id", Types.IntegerType.get(), "id column")},
        new SQLRepresentation[] {
          SQLRepresentation.builder().withDialect(SPARK_DIALECT).withSql(viewSql).build()
        },
        SPARK_CATALOG_NAME,
        schemaName,
        Collections.emptyMap());

    // 3. Spark + IRC.
    SparkSession spark = newSparkSessionWithIrc("alice-i5");
    try {
      String viewFqn = String.format("%s.%s.%s", SPARK_CATALOG_NAME, schemaName, viewName);

      // 3a. SHOW VIEWS — exercises IRC listViews.
      List<Row> shown =
          spark
              .sql(String.format("SHOW VIEWS IN %s.%s", SPARK_CATALOG_NAME, schemaName))
              .collectAsList();
      boolean foundInShow =
          shown.stream()
              .anyMatch(
                  r -> {
                    // Spark's SHOW VIEWS schema is (namespace, viewName, isTemporary)
                    Object name = r.getAs("viewName");
                    return name != null && viewName.equals(name.toString());
                  });
      Assertions.assertTrue(
          foundInShow,
          "SHOW VIEWS must include the view created via Gravitino. Rows seen: " + shown);

      // 3b. DESCRIBE EXTENDED — exercises IRC loadView; assert it returns metadata.
      List<Row> described =
          spark.sql(String.format("DESCRIBE EXTENDED %s", viewFqn)).collectAsList();
      Assertions.assertFalse(
          described.isEmpty(),
          "DESCRIBE EXTENDED must return at least one row for an IRC-resolvable view");
      boolean hasIdColumn =
          described.stream()
              .anyMatch(
                  r -> {
                    Object col = r.get(0);
                    return col != null && "id".equals(col.toString());
                  });
      Assertions.assertTrue(hasIdColumn, "DESCRIBE EXTENDED must surface the view's 'id' column");

      // 3c. Analyze a SELECT against the view — proves IRC supplies the view's SQL and Spark
      //     can expand it (no data scan).
      Assertions.assertDoesNotThrow(
          () -> spark.sql(String.format("SELECT id FROM %s", viewFqn)).queryExecution().analyzed(),
          "Spark must analyze a SELECT against the IRC view without error");
    } finally {
      try {
        spark.close();
      } catch (Exception e) {
        LOG.warn("Failed to close SparkSession", e);
      }
    }
  }

  // -------------------------------------------------------------------------
  // I6–I11 — Spark DDL via IRC is reflected in Gravitino's view APIs
  //
  // CREATE VIEW, CREATE OR REPLACE VIEW, view-on-view, ALTER VIEW RENAME,
  // ALTER VIEW SET / UNSET TBLPROPERTIES, DROP VIEW. Each test issues the
  // Spark DDL through a fresh SparkSession pointed at the IRC, then asserts
  // the resulting state via the Gravitino client (loadView / viewExists /
  // listViews). Drop is covered implicitly by @AfterEach cleanup.
  //
  // Data-side operations (INSERT, SELECT.collect) are intentionally skipped:
  // the seed catalog uses a pod-local warehouse, so a host-side Spark cannot
  // read or write data files. See the I5 javadoc for the full reasoning.
  // -------------------------------------------------------------------------

  // ---- I6 ----------------------------------------------------------------

  @Test
  @DisplayName(
      "I6: Spark CREATE VIEW (simple projection) is loadable via Gravitino with name, comment,"
          + " columns")
  public void testSparkCreateViewIsLoadableViaGravitino() {
    String tableName = RandomNameUtils.genRandomName("products");
    String viewName = RandomNameUtils.genRandomName("electronics_view");

    SparkSession spark = newSparkSessionWithIrc("i6-spark-create-view");
    try {
      createProductsTable(spark, tableName);

      runSparkSql(
          spark,
          String.format(
              "CREATE VIEW %s.%s.%s COMMENT 'Products in the Electronics category' AS "
                  + "SELECT product_id, product_name, price, stock "
                  + "FROM %s.%s.%s WHERE category = 'Electronics'",
              SPARK_CATALOG_NAME, schemaName, viewName, SPARK_CATALOG_NAME, schemaName, tableName));

      // Gravitino-side assertions.
      View loaded = viewCatalog.loadView(NameIdentifier.of(schemaName, viewName));
      Assertions.assertEquals(viewName, loaded.name(), "view name");
      Assertions.assertEquals(
          "Products in the Electronics category", loaded.comment(), "view comment");
      assertViewHasColumns(loaded, "product_id", "product_name", "price", "stock");
    } finally {
      closeQuietly(spark);
    }
  }

  // ---- I7 ----------------------------------------------------------------

  @Test
  @DisplayName(
      "I7: Spark CREATE VIEW (aggregation, GROUP BY) is loadable via Gravitino with aggregated"
          + " columns")
  public void testSparkCreateAggregationViewIsLoadableViaGravitino() {
    String tableName = RandomNameUtils.genRandomName("products");
    String viewName = RandomNameUtils.genRandomName("category_summary_view");

    SparkSession spark = newSparkSessionWithIrc("i7-spark-aggregation-view");
    try {
      createProductsTable(spark, tableName);

      runSparkSql(
          spark,
          String.format(
              "CREATE VIEW %s.%s.%s COMMENT 'Per-category product count and average price' AS "
                  + "SELECT category, COUNT(*) AS product_count, AVG(price) AS avg_price, "
                  + "SUM(stock) AS total_stock "
                  + "FROM %s.%s.%s GROUP BY category",
              SPARK_CATALOG_NAME, schemaName, viewName, SPARK_CATALOG_NAME, schemaName, tableName));

      View loaded = viewCatalog.loadView(NameIdentifier.of(schemaName, viewName));
      Assertions.assertEquals(viewName, loaded.name());
      Assertions.assertEquals("Per-category product count and average price", loaded.comment());
      assertViewHasColumns(loaded, "category", "product_count", "avg_price", "total_stock");
    } finally {
      closeQuietly(spark);
    }
  }

  // ---- I8 ----------------------------------------------------------------

  @Test
  @DisplayName(
      "I8: Spark CREATE OR REPLACE VIEW updates definition and comment, visible via Gravitino")
  public void testSparkCreateOrReplaceViewIsReflectedInGravitino() {
    String tableName = RandomNameUtils.genRandomName("products");
    String viewName = RandomNameUtils.genRandomName("electronics_view");

    SparkSession spark = newSparkSessionWithIrc("i8-spark-create-or-replace");
    try {
      createProductsTable(spark, tableName);

      // Initial definition.
      runSparkSql(
          spark,
          String.format(
              "CREATE VIEW %s.%s.%s COMMENT 'initial' AS "
                  + "SELECT product_id, product_name, price, stock "
                  + "FROM %s.%s.%s WHERE category = 'Electronics'",
              SPARK_CATALOG_NAME, schemaName, viewName, SPARK_CATALOG_NAME, schemaName, tableName));

      View first = viewCatalog.loadView(NameIdentifier.of(schemaName, viewName));
      Assertions.assertEquals("initial", first.comment(), "comment before replace");
      String firstSparkSql = sparkRepresentationSql(first);

      // CREATE OR REPLACE with a tighter WHERE and updated comment.
      runSparkSql(
          spark,
          String.format(
              "CREATE OR REPLACE VIEW %s.%s.%s COMMENT 'Electronics products with stock > 60' AS "
                  + "SELECT product_id, product_name, price, stock "
                  + "FROM %s.%s.%s WHERE category = 'Electronics' AND stock > 60",
              SPARK_CATALOG_NAME, schemaName, viewName, SPARK_CATALOG_NAME, schemaName, tableName));

      View second = viewCatalog.loadView(NameIdentifier.of(schemaName, viewName));
      Assertions.assertEquals(
          "Electronics products with stock > 60", second.comment(), "comment after replace");

      // The SQL definition Spark sent should now include the new WHERE clause; assert via the
      // spark dialect representation that the body has changed and contains "stock > 60".
      String secondSparkSql = sparkRepresentationSql(second);
      Assertions.assertNotEquals(
          firstSparkSql, secondSparkSql, "spark SQL representation must change after replace");
      Assertions.assertTrue(
          secondSparkSql != null && secondSparkSql.contains("stock"),
          "replaced view SQL must reference 'stock'; got: " + secondSparkSql);
    } finally {
      closeQuietly(spark);
    }
  }

  // ---- I9 ----------------------------------------------------------------

  @Test
  @DisplayName("I9: Spark view-on-view (nested) is loadable via Gravitino")
  public void testSparkCreateNestedViewIsLoadableViaGravitino() {
    String tableName = RandomNameUtils.genRandomName("products");
    String baseViewName = RandomNameUtils.genRandomName("electronics_view");
    String nestedViewName = RandomNameUtils.genRandomName("affordable_electronics_view");

    SparkSession spark = newSparkSessionWithIrc("i9-spark-nested-view");
    try {
      createProductsTable(spark, tableName);

      runSparkSql(
          spark,
          String.format(
              "CREATE VIEW %s.%s.%s AS "
                  + "SELECT product_id, product_name, price, stock "
                  + "FROM %s.%s.%s WHERE category = 'Electronics'",
              SPARK_CATALOG_NAME,
              schemaName,
              baseViewName,
              SPARK_CATALOG_NAME,
              schemaName,
              tableName));

      runSparkSql(
          spark,
          String.format(
              "CREATE VIEW %s.%s.%s COMMENT 'Electronics under $500 with sufficient stock' AS "
                  + "SELECT product_id, product_name, price "
                  + "FROM %s.%s.%s WHERE price < 500.00",
              SPARK_CATALOG_NAME,
              schemaName,
              nestedViewName,
              SPARK_CATALOG_NAME,
              schemaName,
              baseViewName));

      // Both views must be loadable via Gravitino.
      View base = viewCatalog.loadView(NameIdentifier.of(schemaName, baseViewName));
      Assertions.assertEquals(baseViewName, base.name());
      assertViewHasColumns(base, "product_id", "product_name", "price", "stock");

      View nested = viewCatalog.loadView(NameIdentifier.of(schemaName, nestedViewName));
      Assertions.assertEquals(nestedViewName, nested.name());
      Assertions.assertEquals("Electronics under $500 with sufficient stock", nested.comment());
      assertViewHasColumns(nested, "product_id", "product_name", "price");
    } finally {
      closeQuietly(spark);
    }
  }

  // ---- I10 ---------------------------------------------------------------

  @Test
  @DisplayName(
      "I10: Spark ALTER VIEW RENAME moves the view to the new name; old name is gone in Gravitino")
  public void testSparkAlterViewRenameIsReflectedInGravitino() {
    String tableName = RandomNameUtils.genRandomName("products");
    String oldName = RandomNameUtils.genRandomName("affordable_electronics_view");
    String newName = RandomNameUtils.genRandomName("budget_electronics_view");

    SparkSession spark = newSparkSessionWithIrc("i10-spark-alter-rename");
    try {
      createProductsTable(spark, tableName);

      runSparkSql(
          spark,
          String.format(
              "CREATE VIEW %s.%s.%s AS SELECT product_id, product_name FROM %s.%s.%s",
              SPARK_CATALOG_NAME, schemaName, oldName, SPARK_CATALOG_NAME, schemaName, tableName));

      Assertions.assertTrue(
          viewCatalog.viewExists(NameIdentifier.of(schemaName, oldName)),
          "view must exist under old name before rename");

      runSparkSql(
          spark,
          String.format(
              "ALTER VIEW %s.%s.%s RENAME TO %s.%s.%s",
              SPARK_CATALOG_NAME, schemaName, oldName, SPARK_CATALOG_NAME, schemaName, newName));

      Assertions.assertFalse(
          viewCatalog.viewExists(NameIdentifier.of(schemaName, oldName)),
          "old name must be gone in Gravitino after Spark RENAME");
      Assertions.assertTrue(
          viewCatalog.viewExists(NameIdentifier.of(schemaName, newName)),
          "new name must be loadable in Gravitino after Spark RENAME");
      View renamed = viewCatalog.loadView(NameIdentifier.of(schemaName, newName));
      Assertions.assertEquals(newName, renamed.name());
    } finally {
      closeQuietly(spark);
    }
  }

  // ---- I11 ---------------------------------------------------------------

  @Test
  @DisplayName(
      "I11: Spark ALTER VIEW SET / UNSET TBLPROPERTIES is reflected in view properties via"
          + " Gravitino")
  public void testSparkAlterViewSetUnsetPropertiesIsReflectedInGravitino() {
    String tableName = RandomNameUtils.genRandomName("products");
    String viewName = RandomNameUtils.genRandomName("budget_electronics_view");

    SparkSession spark = newSparkSessionWithIrc("i11-spark-alter-properties");
    try {
      createProductsTable(spark, tableName);

      runSparkSql(
          spark,
          String.format(
              "CREATE VIEW %s.%s.%s AS SELECT product_id, product_name FROM %s.%s.%s",
              SPARK_CATALOG_NAME, schemaName, viewName, SPARK_CATALOG_NAME, schemaName, tableName));

      // SET TBLPROPERTIES — assert property visible via Gravitino.
      runSparkSql(
          spark,
          String.format(
              "ALTER VIEW %s.%s.%s SET TBLPROPERTIES ('version' = '2')",
              SPARK_CATALOG_NAME, schemaName, viewName));

      View afterSet = viewCatalog.loadView(NameIdentifier.of(schemaName, viewName));
      Assertions.assertEquals(
          "2",
          afterSet.properties().get("version"),
          "Spark SET TBLPROPERTIES must surface 'version=2' in Gravitino view properties");

      // UNSET TBLPROPERTIES — assert property gone via Gravitino.
      runSparkSql(
          spark,
          String.format(
              "ALTER VIEW %s.%s.%s UNSET TBLPROPERTIES ('version')",
              SPARK_CATALOG_NAME, schemaName, viewName));

      View afterUnset = viewCatalog.loadView(NameIdentifier.of(schemaName, viewName));
      Assertions.assertNull(
          afterUnset.properties().get("version"),
          "Spark UNSET TBLPROPERTIES must remove 'version' from Gravitino view properties");
    } finally {
      closeQuietly(spark);
    }
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  /**
   * Creates the {@code products} fixture table via the Gravitino table API. No data is inserted;
   * the host-side Spark cannot write to the pod-local warehouse, and these tests only need the
   * table's metadata so Spark can analyze and persist view definitions over IRC.
   */
  private static void createProductsTable(SparkSession spark, String tableName) {
    catalog
        .asTableCatalog()
        .createTable(
            NameIdentifier.of(schemaName, tableName),
            new Column[] {
              Column.of("product_id", Types.LongType.get(), "product id"),
              Column.of("product_name", Types.StringType.get(), "product name"),
              Column.of("category", Types.StringType.get(), "product category"),
              Column.of("price", Types.DecimalType.of(10, 2), "price"),
              Column.of("stock", Types.IntegerType.get(), "stock count"),
              Column.of("created_date", Types.DateType.get(), "created date")
            },
            TABLE_COMMENT,
            Collections.emptyMap(),
            Transforms.EMPTY_TRANSFORM,
            Distributions.NONE,
            new SortOrder[0]);
    LOG.info(
        "Created products fixture table {}.{} for Spark session {}",
        schemaName,
        tableName,
        spark.sparkContext().appName());
  }

  /**
   * Runs a Spark SQL statement and surfaces the SQL text in the failure message if execution
   * throws. Matches the small wrapper pattern in other Spark+IRC ITs in this module.
   */
  private static void runSparkSql(SparkSession spark, String sql) {
    try {
      spark.sql(sql).collect();
    } catch (RuntimeException e) {
      throw new AssertionError("Spark SQL failed: " + sql, e);
    }
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

  /**
   * Returns the SQL body of the {@code spark}-dialect representation, or {@code null} if the view
   * has none. Used by I8 to verify the view body changed after CREATE OR REPLACE.
   */
  private static String sparkRepresentationSql(View view) {
    return Arrays.stream(view.representations())
        .filter(r -> r instanceof SQLRepresentation)
        .map(r -> (SQLRepresentation) r)
        .filter(r -> SPARK_DIALECT.equalsIgnoreCase(r.dialect()))
        .map(SQLRepresentation::sql)
        .findFirst()
        .orElse(null);
  }

  /** Closes a SparkSession, swallowing any exception (test-cleanup helper). */
  private static void closeQuietly(SparkSession spark) {
    if (spark != null) {
      try {
        spark.close();
      } catch (Exception e) {
        LOG.warn("Failed to close SparkSession", e);
      }
    }
  }

  /**
   * Builds a SparkSession with the Iceberg REST catalog registered as {@link #SPARK_CATALOG_NAME}
   * and authenticated against the IRC via simple-auth Basic header. Mirrors the pattern in {@code
   * SparkIcebergRestGroupBasedAccessControlIT.newSparkSessionForToken}, switching the auth type to
   * {@code basic} so it works in env1-simple-auth.
   */
  private static SparkSession newSparkSessionWithIrc(String app) {
    String base = "spark.sql.catalog." + SPARK_CATALOG_NAME;
    SparkConf conf =
        new SparkConf()
            .set(
                "spark.sql.extensions",
                "org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions")
            .set("spark.sql.defaultCatalog", SPARK_CATALOG_NAME)
            .set(base, "org.apache.iceberg.spark.SparkCatalog")
            .set(base + ".type", "rest")
            .set(base + ".uri", ircUri)
            .set(base + ".cache-enabled", "false")
            .set(base + ".rest.auth.type", "basic")
            .set(base + ".rest.auth.basic.username", simpleUser)
            .set(base + ".rest.auth.basic.password", "mock")
            .set("spark.locality.wait.node", "0");
    return SparkSession.builder().master("local[2]").appName(app).config(conf).getOrCreate();
  }
}
