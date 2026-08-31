/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.catalog.oracle.integration.test;

import static com.datastrato.gravitino.catalog.oracle.OracleTablePropertiesMetadata.COMPRESSION;
import static com.datastrato.gravitino.catalog.oracle.OracleTablePropertiesMetadata.PARTITIONED;
import static com.datastrato.gravitino.catalog.oracle.OracleTablePropertiesMetadata.ROW_MOVEMENT;
import static org.apache.gravitino.catalog.jdbc.JdbcTablePropertiesMetadata.COMMENT_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datastrato.gravitino.catalog.oracle.integration.test.service.OracleService;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import java.io.IOException;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.Schema;
import org.apache.gravitino.SupportsSchemas;
import org.apache.gravitino.catalog.jdbc.config.JdbcConfig;
import org.apache.gravitino.client.GravitinoMetalake;
import org.apache.gravitino.connector.HiddenPropertyMaskUtils;
import org.apache.gravitino.exceptions.NoSuchSchemaException;
import org.apache.gravitino.integration.test.container.ContainerSuite;
import org.apache.gravitino.integration.test.container.OracleContainer;
import org.apache.gravitino.integration.test.util.BaseIT;
import org.apache.gravitino.integration.test.util.GravitinoITUtils;
import org.apache.gravitino.integration.test.util.ITUtils;
import org.apache.gravitino.integration.test.util.TestDatabaseName;
import org.apache.gravitino.rel.Column;
import org.apache.gravitino.rel.Table;
import org.apache.gravitino.rel.TableCatalog;
import org.apache.gravitino.rel.TableChange;
import org.apache.gravitino.rel.expressions.FunctionExpression;
import org.apache.gravitino.rel.expressions.distributions.Distributions;
import org.apache.gravitino.rel.expressions.literals.Literal;
import org.apache.gravitino.rel.expressions.literals.Literals;
import org.apache.gravitino.rel.expressions.sorts.SortOrder;
import org.apache.gravitino.rel.expressions.transforms.Transform;
import org.apache.gravitino.rel.expressions.transforms.Transforms;
import org.apache.gravitino.rel.indexes.Index;
import org.apache.gravitino.rel.indexes.Indexes;
import org.apache.gravitino.rel.partitions.ListPartition;
import org.apache.gravitino.rel.partitions.Partitions;
import org.apache.gravitino.rel.partitions.RangePartition;
import org.apache.gravitino.rel.types.Types;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

@Tag("gravitino-docker-test")
@TestInstance(Lifecycle.PER_CLASS)
public class CatalogOracleIT extends BaseIT {
  private static final ContainerSuite containerSuite = ContainerSuite.getInstance();
  private static final String provider = "jdbc-oracle";
  private static final String ORACLE_JDBC_DRIVER_URL =
      "https://repo1.maven.org/maven2/com/oracle/database/jdbc/ojdbc11/23.26.2.0.0/ojdbc11-23.26.2.0.0.jar";
  private static final TestDatabaseName TEST_DB_NAME = TestDatabaseName.ORACLE_CATALOG_ORACLE_IT;

  // Oracle identifiers are upper-cased by default; keep these ASCII-safe to satisfy
  // OracleCatalogCapability's name pattern and Oracle's 30-char limit on pre-12.2 syntax.
  private final String metalakeName = GravitinoITUtils.genRandomName("oracle_it_metalake");
  private final String catalogName = GravitinoITUtils.genRandomName("oracle_it_catalog");

  // In Oracle a schema is the APP_USER that owns its objects — it is created by the container
  // image at boot and cannot be created or dropped via the Gravitino catalog.
  private final String schemaName = OracleContainer.APP_USER;

  private GravitinoMetalake metalake;
  private Catalog catalog;
  private OracleService oracleService;
  private OracleContainer oracleContainer;

  @Override
  protected void setupJdbcDrivers() throws IOException {
    super.setupJdbcDrivers();
    if (!ITUtils.DEPLOY_TEST_MODE.equals(testMode)) {
      return;
    }

    String gravitinoHome = System.getenv("GRAVITINO_HOME");
    String[] oracleDriverDirs = {
      ITUtils.joinPath(gravitinoHome, "catalogs", "jdbc-oracle", "libs")
    };
    String[] oracleDriverUrls = {ORACLE_JDBC_DRIVER_URL};
    downloadJdbcDrivers(oracleDriverUrls, oracleDriverDirs);
    cleanJdbcDriverConflicts(oracleDriverUrls, oracleDriverDirs);
  }

  @BeforeAll
  @Override
  public void startIntegrationTest() throws Exception {
    super.startIntegrationTest();
    containerSuite.startOracleContainer(TEST_DB_NAME);
    oracleContainer = containerSuite.getOracleContainer();

    oracleService = new OracleService(oracleContainer, TEST_DB_NAME);
    createMetalake();
    catalog = createCatalog(catalogName);
  }

  @AfterAll
  public void stop() {
    clearTables();
    if (metalake != null) {
      // In Oracle a schema is the APP_USER and cannot be dropped; force-drop removes the catalog
      // despite the non-empty GRAVITINO schema.
      metalake.disableCatalog(catalogName);
      metalake.dropCatalog(catalogName, true);
      client.disableMetalake(metalakeName);
      client.dropMetalake(metalakeName);
    }
    if (oracleService != null) {
      oracleService.close();
    }
  }

  @AfterEach
  public void resetTables() {
    clearTables();
  }

  private void clearTables() {
    if (catalog == null) {
      return;
    }
    TableCatalog tableCatalog = catalog.asTableCatalog();
    NameIdentifier[] tables = tableCatalog.listTables(Namespace.of(schemaName));
    for (NameIdentifier ident : tables) {
      tableCatalog.purgeTable(ident);
    }
  }

  private void createMetalake() {
    GravitinoMetalake[] existing = client.listMetalakes();
    Assertions.assertEquals(0, existing.length);

    client.createMetalake(metalakeName, "comment", Collections.emptyMap());
    GravitinoMetalake loaded = client.loadMetalake(metalakeName);
    Assertions.assertEquals(metalakeName, loaded.name());
    metalake = loaded;
  }

  private Catalog createCatalog(String catalogName) throws SQLException {
    Map<String, String> catalogProperties = Maps.newHashMap();
    catalogProperties.put(JdbcConfig.JDBC_URL.getKey(), oracleContainer.getJdbcUrl(TEST_DB_NAME));
    catalogProperties.put(
        JdbcConfig.JDBC_DRIVER.getKey(), oracleContainer.getDriverClassName(TEST_DB_NAME));
    catalogProperties.put(JdbcConfig.USERNAME.getKey(), oracleContainer.getUsername());
    catalogProperties.put(JdbcConfig.PASSWORD.getKey(), oracleContainer.getPassword());

    Catalog created =
        metalake.createCatalog(
            catalogName, Catalog.Type.RELATIONAL, provider, "comment", catalogProperties);
    Catalog loaded = metalake.loadCatalog(catalogName);
    Assertions.assertEquals(created, loaded);
    return loaded;
  }

  // Column comments are left null in most tests just to keep unrelated assertions simple. Column
  // comments themselves round-trip correctly through loadTable via ALL_COL_COMMENTS (see #855 and
  // testColumnCommentsRoundTripThroughLoad); the Oracle driver leaves REMARKS empty, so the catalog
  // reads ALL_COL_COMMENTS explicitly rather than relying on DatabaseMetaData.
  private Column[] simpleColumns() {
    return new Column[] {
      Column.of("id", Types.IntegerType.get(), null, false, false, null),
      Column.of("name", Types.VarCharType.of(64), null, true, false, null),
      Column.of("score", Types.DecimalType.of(10, 2), null, true, false, null),
      Column.of("created_at", Types.TimestampType.withoutTimeZone(), null, true, false, null)
    };
  }

  private Table createTable(String name, Column[] columns) {
    return createTable(name, columns, null, ImmutableMap.of(), Transforms.EMPTY_TRANSFORM);
  }

  private Table createTable(
      String name,
      Column[] columns,
      String comment,
      Map<String, String> properties,
      Transform[] partitioning) {
    return catalog
        .asTableCatalog()
        .createTable(
            NameIdentifier.of(schemaName, name),
            columns,
            comment,
            properties,
            partitioning,
            Distributions.NONE,
            new SortOrder[0]);
  }

  // ----------------------------------------------------------------------
  // Connection & schema
  // ----------------------------------------------------------------------

  @Test
  void testTestConnectionFailure() throws SQLException {
    Map<String, String> catalogProperties = Maps.newHashMap();
    catalogProperties.put(JdbcConfig.JDBC_URL.getKey(), oracleContainer.getJdbcUrl(TEST_DB_NAME));
    catalogProperties.put(
        JdbcConfig.JDBC_DRIVER.getKey(), oracleContainer.getDriverClassName(TEST_DB_NAME));
    catalogProperties.put(JdbcConfig.USERNAME.getKey(), oracleContainer.getUsername());
    catalogProperties.put(JdbcConfig.PASSWORD.getKey(), "wrong_password");

    // testConnection wraps Oracle driver failures into RuntimeException in some code paths, so we
    // accept either the specific ConnectionFailedException or a generic RuntimeException.
    assertThrows(
        RuntimeException.class,
        () ->
            metalake.testConnection(
                GravitinoITUtils.genRandomName("oracle_it_catalog"),
                Catalog.Type.RELATIONAL,
                provider,
                "comment",
                catalogProperties));
  }

  @Test
  void testListAndLoadSchemas() {
    SupportsSchemas schemas = catalog.asSchemas();
    String[] names = schemas.listSchemas();
    Set<String> schemaSet = Sets.newHashSet(names);
    // Unquoted schema (Oracle user) names are exposed as bare uppercase logical names.
    String expectedSchemaName = schemaName.toUpperCase(Locale.ROOT);
    assertTrue(
        schemaSet.contains(expectedSchemaName),
        "Expected APP_USER schema " + expectedSchemaName + " to appear in " + schemaSet);

    Schema loaded = schemas.loadSchema(schemaName);
    assertEquals(expectedSchemaName, loaded.name());
  }

  @Test
  void testSystemSchemasAreFiltered() {
    // Every account maintained by Oracle must be filtered, including accounts installed by optional
    // components that are not present in a fixed list. See issue #839.
    Set<String> listed = Sets.newHashSet(catalog.asSchemas().listSchemas());
    Set<String> oracleMaintainedUsers = oracleService.listOracleMaintainedUsers();

    assertTrue(
        oracleMaintainedUsers.contains("SYS"),
        "Expected SYS to be maintained by Oracle: " + oracleMaintainedUsers);

    for (String account : oracleMaintainedUsers) {
      assertFalse(
          listed.contains(account),
          account + " is maintained by Oracle but was returned by listSchemas: " + listed);
    }

    // The application schema must still be listed.
    assertTrue(listed.contains(schemaName), "Expected " + schemaName + " in " + listed);
    assertFalse(
        oracleMaintainedUsers.contains(schemaName),
        "Application schema must not be marked as maintained by Oracle");
  }

  @Test
  void testCreateAndDropSchemaUnsupported() {
    SupportsSchemas schemas = catalog.asSchemas();
    // Oracle does not expose CREATE SCHEMA — the catalog refuses the request with a clear error.
    RuntimeException createException =
        assertThrows(
            RuntimeException.class,
            () ->
                schemas.createSchema(
                    GravitinoITUtils.genRandomName("unsupported"), null, Collections.emptyMap()));
    assertTrue(createException.getMessage().contains("does not support creating schemas"));

    // And the same holds for DROP SCHEMA — even targeting the existing APP_USER schema.
    assertThrows(RuntimeException.class, () -> schemas.dropSchema(schemaName, false));

    assertThrows(NoSuchSchemaException.class, () -> schemas.loadSchema("does_not_exist"));
  }

  // ----------------------------------------------------------------------
  // Table basics
  // ----------------------------------------------------------------------

  @Test
  void testCreateAndLoadTable() {
    TableCatalog tableCatalog = catalog.asTableCatalog();
    String name = "IT_CREATE_TBL";
    NameIdentifier tableIdent = NameIdentifier.of(schemaName, name);
    Column[] columns = simpleColumns();

    Table created =
        createTable(name, columns, "table_comment", ImmutableMap.of(), Transforms.EMPTY_TRANSFORM);
    // Unquoted table name input folds to the bare uppercase logical name.
    assertEquals(name.toUpperCase(Locale.ROOT), created.name());

    Table loaded = tableCatalog.loadTable(tableIdent);
    assertEquals(name.toUpperCase(Locale.ROOT), loaded.name());
    assertEquals("table_comment", loaded.comment());
    assertEquals(HiddenPropertyMaskUtils.MASKED_VALUE, loaded.properties().get(COMMENT_KEY));
    assertEquals(columns.length, loaded.columns().length);
    for (int i = 0; i < columns.length; i++) {
      // Unquoted column name input folds to the bare uppercase logical name.
      assertEquals(columns[i].name().toUpperCase(Locale.ROOT), loaded.columns()[i].name());
      assertEquals(columns[i].nullable(), loaded.columns()[i].nullable());
    }

    // Tablespace is not asserted: Oracle Free 23c's default tablespace for APP_USER may come back
    // as null under ALL_TABLES.TABLESPACE_NAME, and the property metadata drops null values.
    assertTrue(oracleService.tableExists(name));
  }

  @Test
  void testColumnCommentsRoundTripThroughLoad() {
    // Issue #855: Oracle leaves REMARKS empty, so column comments must be read from
    // ALL_COL_COMMENTS. Verify both the create+load and the alter+load paths.
    TableCatalog tableCatalog = catalog.asTableCatalog();
    String name = "IT_COL_COMMENTS";
    NameIdentifier tableIdent = NameIdentifier.of(schemaName, name);
    Column[] columns =
        new Column[] {
          Column.of("id", Types.IntegerType.get(), null, false, false, null),
          Column.of("name", Types.VarCharType.of(64), "person name", true, false, null)
        };
    createTable(name, columns);

    Table loaded = tableCatalog.loadTable(tableIdent);
    Map<String, Column> byName =
        Arrays.stream(loaded.columns())
            .collect(Collectors.toMap(c -> c.name().toLowerCase(), c -> c));
    assertEquals("person name", byName.get("name").comment());
    // A column created without a comment stays null.
    assertNull(byName.get("id").comment());

    // Update the comment via alterTable and confirm the new value is returned by loadTable.
    tableCatalog.alterTable(
        tableIdent, TableChange.updateColumnComment(new String[] {"name"}, "updated name comment"));
    Table reloaded = tableCatalog.loadTable(tableIdent);
    Map<String, Column> reloadedByName =
        Arrays.stream(reloaded.columns())
            .collect(Collectors.toMap(c -> c.name().toLowerCase(), c -> c));
    assertEquals("updated name comment", reloadedByName.get("name").comment());
    // Cross-check that the write path actually updated ALL_COL_COMMENTS. getColumnComment queries
    // ALL_COL_COMMENTS.COLUMN_NAME with an exact, case-sensitive match, so the physical (uppercase,
    // unquoted-folded) column name is required here, not the lowercase logical name used above.
    assertEquals("updated name comment", oracleService.getColumnComment(name, "NAME"));
  }

  @Test
  void testListAndDropTable() {
    TableCatalog tableCatalog = catalog.asTableCatalog();
    NameIdentifier t1 = NameIdentifier.of(schemaName, "IT_LIST_TBL_1");
    NameIdentifier t2 = NameIdentifier.of(schemaName, "IT_LIST_TBL_2");

    createTable("IT_LIST_TBL_1", simpleColumns());
    createTable("IT_LIST_TBL_2", simpleColumns());

    // Sanity-check that the two tables actually exist in Oracle via ALL_TABLES before we ask the
    // catalog to list them — listTables goes through DatabaseMetaData.getTables, and on Oracle
    // Free 23c the driver can return an empty set when the schema pattern does not exactly match
    // the stored OWNER (see GRAVITINO-600 follow-up).
    assertTrue(oracleService.tableExists("IT_LIST_TBL_1"), "IT_LIST_TBL_1 missing from ALL_TABLES");
    assertTrue(oracleService.tableExists("IT_LIST_TBL_2"), "IT_LIST_TBL_2 missing from ALL_TABLES");

    // listTables may return the Oracle-stored form of the identifier (either upper- or
    // lowercase depending on JDBC driver/version), so normalize for comparison.
    Set<String> listed =
        Arrays.stream(tableCatalog.listTables(Namespace.of(schemaName)))
            .map(ident -> ident.name().toUpperCase())
            .collect(Collectors.toSet());
    assertTrue(listed.contains("IT_LIST_TBL_1"), "Listed tables: " + listed);
    assertTrue(listed.contains("IT_LIST_TBL_2"), "Listed tables: " + listed);

    assertTrue(tableCatalog.dropTable(t1));
    assertFalse(oracleService.tableExists("IT_LIST_TBL_1"));

    // Drop + purge both translate to "DROP TABLE ... PURGE" for Oracle.
    assertTrue(tableCatalog.purgeTable(t2));
    assertFalse(oracleService.tableExists("IT_LIST_TBL_2"));
  }

  // ----------------------------------------------------------------------
  // Column types
  // ----------------------------------------------------------------------

  @Test
  void testColumnTypeCoverage() {
    TableCatalog tableCatalog = catalog.asTableCatalog();
    String name = "IT_TYPES_TBL";
    NameIdentifier tableIdent = NameIdentifier.of(schemaName, name);

    Column[] columns =
        new Column[] {
          // NUMBER(p, s) precision-driven integral round-trips:
          // Byte (3), Short (5), Integer (10), Long (19).
          Column.of("c_byte", Types.ByteType.get(), null, true, false, null),
          Column.of("c_short", Types.ShortType.get(), null, true, false, null),
          Column.of("c_int", Types.IntegerType.get(), null, true, false, null),
          // Oracle NUMBER(19, 0) can hold values outside Java signed long, but Gravitino writes
          // LongType as NUMBER(19), so OracleTypeConverter maps precision <= 19 back to LongType
          // to preserve Gravitino LongType round trips.
          Column.of("c_long", Types.LongType.get(), null, true, false, null),
          Column.of("c_decimal", Types.DecimalType.of(12, 4), null, true, false, null),
          // BINARY_FLOAT / BINARY_DOUBLE are the Oracle-native IEEE-754 types.
          Column.of("c_float", Types.FloatType.get(), null, true, false, null),
          Column.of("c_double", Types.DoubleType.get(), null, true, false, null),
          Column.of("c_boolean", Types.BooleanType.get(), null, true, false, null),
          Column.of("c_varchar", Types.VarCharType.of(200), null, true, false, null),
          Column.of("c_char", Types.FixedCharType.of(10), null, true, false, null),
          Column.of("c_string", Types.StringType.get(), null, true, false, null),
          Column.of("c_binary", Types.BinaryType.get(), null, true, false, null),
          Column.of("c_ts", Types.TimestampType.withoutTimeZone(), null, true, false, null)
        };

    createTable(name, columns);
    Table loaded = tableCatalog.loadTable(tableIdent);

    Map<String, Column> loadedByName =
        Arrays.stream(loaded.columns())
            .collect(Collectors.toMap(c -> c.name().toLowerCase(), c -> c));

    assertEquals(Types.ByteType.get(), loadedByName.get("c_byte").dataType());
    assertEquals(Types.ShortType.get(), loadedByName.get("c_short").dataType());
    assertEquals(Types.IntegerType.get(), loadedByName.get("c_int").dataType());
    // LongType is NUMBER(19) -> LongType on read under the precision <= 19 integral mapping.
    assertEquals(Types.LongType.get(), loadedByName.get("c_long").dataType());
    assertEquals(Types.DecimalType.of(12, 4), loadedByName.get("c_decimal").dataType());
    assertEquals(Types.FloatType.get(), loadedByName.get("c_float").dataType());
    assertEquals(Types.DoubleType.get(), loadedByName.get("c_double").dataType());
    assertEquals(Types.BooleanType.get(), loadedByName.get("c_boolean").dataType());
    assertEquals(Types.VarCharType.of(200), loadedByName.get("c_varchar").dataType());
    assertEquals(Types.FixedCharType.of(10), loadedByName.get("c_char").dataType());
    assertEquals(Types.StringType.get(), loadedByName.get("c_string").dataType());
    assertEquals(Types.BinaryType.get(), loadedByName.get("c_binary").dataType());
    assertEquals(Types.TimestampType.withoutTimeZone(6), loadedByName.get("c_ts").dataType());
  }

  @Test
  void testSourceNumberAndBooleanMetadataMapping() {
    String name = "IT_SOURCE_NUMBER_TYPES";
    oracleService.executeQuery(
        "CREATE TABLE " + name + " (FLAG NUMBER(1), AMOUNT NUMBER, ENABLED BOOLEAN)");

    Table loaded = catalog.asTableCatalog().loadTable(NameIdentifier.of(schemaName, name));
    Map<String, Column> loadedByName =
        Arrays.stream(loaded.columns())
            .collect(Collectors.toMap(c -> c.name().toLowerCase(), c -> c));

    assertEquals(Types.ByteType.get(), loadedByName.get("flag").dataType());
    assertEquals(Types.DecimalType.of(38, 0), loadedByName.get("amount").dataType());
    assertEquals(Types.BooleanType.get(), loadedByName.get("enabled").dataType());
  }

  // ----------------------------------------------------------------------
  // Identifier case folding
  // ----------------------------------------------------------------------
  //
  // Oracle's native rule is per-identifier: an unquoted identifier folds to uppercase and compares
  // case-insensitively; a quoted identifier preserves its exact case and compares case-sensitively.
  // Gravitino encodes "this identifier was quoted" directly in the logical name string using
  // literal double-quote characters (e.g. the Gravitino name `"MyTable"` means "case-sensitive,
  // exact case MyTable"). The cases below verify that:
  //   1. Any unquoted input casing (schema/table/column) folds to the same bare uppercase logical
  //      name, and the physical Oracle object is reachable via unquoted SQL,
  //   2. A quoted logical name preserves its exact case end-to-end: the physical Oracle name and
  //      the Gravitino logical name have identical letter casing,
  //   3. Reserved-word column names still work unquoted because the physical name is quoted in the
  //      generated DDL,
  //   4. ALTER TABLE ADD/RENAME/DELETE COLUMN and index operations fold mixed-case input too.

  @Test
  void testNamesFoldToUppercase() {
    TableCatalog tableCatalog = catalog.asTableCatalog();

    Column[] columns =
        new Column[] {
          Column.of("MixedCol", Types.IntegerType.get(), null, true, false, null),
          Column.of("UPPERCOL", Types.VarCharType.of(16), null, true, false, null),
          Column.of("lowercol", Types.IntegerType.get(), null, true, false, null)
        };

    createTable("FooBar", columns);

    // The physical Oracle table/columns are uppercase and reachable via unquoted SQL.
    assertTrue(oracleService.tableExists("FOOBAR"));

    // loadTable with any input casing resolves to the same bare uppercase logical table.
    for (String variant : new String[] {"foobar", "FOOBAR", "FooBar"}) {
      Table loaded = tableCatalog.loadTable(NameIdentifier.of(schemaName, variant));
      assertEquals("FOOBAR", loaded.name());
      Set<String> columnNames =
          Arrays.stream(loaded.columns()).map(Column::name).collect(Collectors.toSet());
      assertTrue(columnNames.contains("MIXEDCOL"), "columns: " + columnNames);
      assertTrue(columnNames.contains("UPPERCOL"), "columns: " + columnNames);
      assertTrue(columnNames.contains("LOWERCOL"), "columns: " + columnNames);
    }
  }

  @Test
  void testQuotedNamePreservesExactCase() {
    TableCatalog tableCatalog = catalog.asTableCatalog();

    Column[] columns =
        new Column[] {Column.of("id", Types.IntegerType.get(), null, false, false, null)};

    // A quoted logical name creates a case-sensitive Oracle object with its exact case preserved.
    // The quoting is a one-time signal evaluated at specification time; the name Gravitino reports
    // back is a plain string that never itself contains a literal quote character.
    Table created = createTable("\"MyQuotedTable\"", columns);
    assertEquals("MyQuotedTable", created.name());

    // The physical Oracle table is stored with the exact case, and is NOT reachable via the
    // all-uppercase unquoted form.
    assertTrue(oracleService.tableExistsExact("MyQuotedTable"));
    assertFalse(oracleService.tableExistsExact("MYQUOTEDTABLE"));

    // A later reference must re-supply the quoted form to be treated as case-sensitive again.
    Table loaded = tableCatalog.loadTable(NameIdentifier.of(schemaName, "\"MyQuotedTable\""));
    assertEquals("MyQuotedTable", loaded.name());

    Set<String> listed =
        Arrays.stream(tableCatalog.listTables(Namespace.of(schemaName)))
            .map(NameIdentifier::name)
            .collect(Collectors.toSet());
    assertTrue(listed.contains("MyQuotedTable"), "Listed tables: " + listed);
  }

  @Test
  void testQuotedNameStartingWithDigitSucceeds() {
    TableCatalog tableCatalog = catalog.asTableCatalog();
    Column[] columns =
        new Column[] {Column.of("id", Types.IntegerType.get(), null, false, false, null)};

    // A quoted name starting with a digit is invalid unquoted-identifier grammar (which requires
    // starting with a letter), but must be validated against its original (still-quoted) form, not
    // its already-normalized (unquoted) form, or it would be wrongly rejected as illegal even
    // though it is valid as supplied.
    Table created = createTable("\"1Table\"", columns);
    assertEquals("1Table", created.name());
    assertTrue(oracleService.tableExistsExact("1Table"));

    Table loaded = tableCatalog.loadTable(NameIdentifier.of(schemaName, "\"1Table\""));
    assertEquals("1Table", loaded.name());

    // The canonical name "1Table" itself fails Oracle's unquoted-identifier grammar (must start
    // with a letter), so it must be purged here using the re-quoted form: the shared per-test
    // cleanup helper purges by bare name and cannot reach a table whose canonical name is
    // grammar-invalid unquoted.
    assertTrue(tableCatalog.purgeTable(NameIdentifier.of(schemaName, "\"1Table\"")));
  }

  @Test
  void testQuotedReservedWordNameSucceedsWhereUnquotedWouldFail() {
    TableCatalog tableCatalog = catalog.asTableCatalog();
    Column[] columns =
        new Column[] {Column.of("id", Types.IntegerType.get(), null, false, false, null)};

    // "comment" (lowercase, quoted) is a case-sensitive, reserved-word table name that only
    // succeeds because it is quoted in the generated DDL.
    Table created = createTable("\"comment\"", columns);
    assertEquals("comment", created.name());
    assertTrue(oracleService.tableExistsExact("comment"));

    Table loaded = tableCatalog.loadTable(NameIdentifier.of(schemaName, "\"comment\""));
    assertEquals("comment", loaded.name());
  }

  @Test
  void testLegacyQuotedTableCreatedOutsideGravitinoIsVisible() {
    TableCatalog tableCatalog = catalog.asTableCatalog();

    // Bypass Gravitino entirely and create a mixed-case quoted table directly via JDBC, simulating
    // a table created by an older Gravitino version or external tooling.
    oracleService.executeQuery("CREATE TABLE \"LegacyFoo\" (\"ID\" NUMBER(10))");

    Set<String> listed =
        Arrays.stream(tableCatalog.listTables(Namespace.of(schemaName)))
            .map(NameIdentifier::name)
            .collect(Collectors.toSet());
    assertTrue(listed.contains("LegacyFoo"), "Listed tables: " + listed);

    // The name Gravitino reports never itself contains a literal quote character; a later
    // reference must re-supply the quoted form to reach this case-sensitive physical table.
    Table loaded = tableCatalog.loadTable(NameIdentifier.of(schemaName, "\"LegacyFoo\""));
    assertEquals("LegacyFoo", loaded.name());
  }

  @Test
  void testQuotedAndUnquotedNameCollisionSurfacesAsTableAlreadyExists() {
    TableCatalog tableCatalog = catalog.asTableCatalog();
    Column[] columns =
        new Column[] {Column.of("id", Types.IntegerType.get(), null, false, false, null)};

    createTable("IT_COLLISION_TBL", columns);

    // The quoted form "IT_COLLISION_TBL" targets the identical physical object as the unquoted
    // table just created, so Oracle rejects it as an existing-name conflict.
    assertThrows(RuntimeException.class, () -> createTable("\"IT_COLLISION_TBL\"", columns));

    assertNotNull(tableCatalog.loadTable(NameIdentifier.of(schemaName, "IT_COLLISION_TBL")));
  }

  @Test
  void testReservedWordColumnNames() {
    TableCatalog tableCatalog = catalog.asTableCatalog();
    String name = "it_reserved_cols";
    NameIdentifier tableIdent = NameIdentifier.of(schemaName, name);

    // "comment" and "number" are Oracle reserved words; OracleTableOperations quotes the
    // uppercased physical name, which protects them from failing as unquoted identifiers.
    Column[] columns =
        new Column[] {
          Column.of("id", Types.IntegerType.get(), null, false, false, null),
          Column.of("comment", Types.VarCharType.of(64), null, true, false, null),
          Column.of("number", Types.IntegerType.get(), null, true, false, null)
        };

    createTable(name, columns);
    Table loaded = tableCatalog.loadTable(tableIdent);

    Set<String> columnNames =
        Arrays.stream(loaded.columns()).map(Column::name).collect(Collectors.toSet());
    assertTrue(columnNames.contains("COMMENT"), "columns: " + columnNames);
    assertTrue(columnNames.contains("NUMBER"), "columns: " + columnNames);
  }

  @Test
  void testAlterTableColumnCaseFolding() {
    TableCatalog tableCatalog = catalog.asTableCatalog();
    String name = "it_col_case_alter";
    NameIdentifier tableIdent = NameIdentifier.of(schemaName, name);

    Column[] columns =
        new Column[] {
          Column.of("id", Types.IntegerType.get(), null, false, false, null),
          Column.of("keepme", Types.VarCharType.of(16), null, true, false, null)
        };
    createTable(name, columns);

    // Mixed-case field name folds to uppercase before the ADD COLUMN path applies it to Oracle.
    tableCatalog.alterTable(
        tableIdent,
        TableChange.addColumn(
            new String[] {"NewCol"},
            Types.IntegerType.get(),
            null,
            TableChange.ColumnPosition.defaultPos(),
            true,
            false,
            null));

    Table afterAdd = tableCatalog.loadTable(tableIdent);
    Set<String> namesAfterAdd =
        Arrays.stream(afterAdd.columns()).map(Column::name).collect(Collectors.toSet());
    assertTrue(namesAfterAdd.contains("NEWCOL"), "NEWCOL should be added: " + namesAfterAdd);
    assertTrue(
        namesAfterAdd.contains("KEEPME"), "KEEPME should still be present: " + namesAfterAdd);

    // Drop it using yet another casing — folding makes it the same logical column.
    tableCatalog.alterTable(tableIdent, TableChange.deleteColumn(new String[] {"NEWCOL"}, false));

    Table afterDrop = tableCatalog.loadTable(tableIdent);
    Set<String> namesAfterDrop =
        Arrays.stream(afterDrop.columns()).map(Column::name).collect(Collectors.toSet());
    assertFalse(namesAfterDrop.contains("NEWCOL"), "NEWCOL should be dropped: " + namesAfterDrop);
    assertTrue(
        namesAfterDrop.contains("KEEPME"), "KEEPME should still be present: " + namesAfterDrop);
  }

  // ----------------------------------------------------------------------
  // Column default values
  // ----------------------------------------------------------------------

  @Test
  void testColumnDefaultValues() {
    TableCatalog tableCatalog = catalog.asTableCatalog();
    String name = "IT_DEFAULTS_TBL";
    NameIdentifier tableIdent = NameIdentifier.of(schemaName, name);

    Column[] columns =
        new Column[] {
          Column.of("id", Types.IntegerType.get(), null, false, false, null),
          // Integer literal default.
          Column.of(
              "qty",
              Types.IntegerType.get(),
              null,
              true,
              false,
              Literals.of("5", Types.IntegerType.get())),
          // String literal default — stored as 'hello' in DDL.
          Column.of(
              "tag",
              Types.VarCharType.of(32),
              null,
              true,
              false,
              Literals.of("hello", Types.VarCharType.of(32))),
          // NULL default on a nullable column.
          Column.of("memo", Types.VarCharType.of(64), null, true, false, Literals.NULL),
          // Oracle function defaults commonly used for audit columns.
          Column.of(
              "created_at",
              Types.TimestampType.withoutTimeZone(),
              null,
              true,
              false,
              FunctionExpression.of("SYSTIMESTAMP")),
          Column.of(
              "created_date",
              Types.TimestampType.withoutTimeZone(),
              null,
              true,
              false,
              FunctionExpression.of("SYSDATE"))
        };

    createTable(name, columns);
    Table loaded = tableCatalog.loadTable(tableIdent);

    Map<String, Column> byName =
        Arrays.stream(loaded.columns())
            .collect(Collectors.toMap(c -> c.name().toLowerCase(), c -> c));

    // Literals.of("5", IntegerType) holds a String value, so the Oracle catalog emits DEFAULT '5'
    // and reads it back as a StringType literal (the surrounding quotes are stripped by the
    // default-value converter). This is the same fate as the "hello" VARCHAR2 default below.
    assertEquals(Literals.stringLiteral("5"), byName.get("qty").defaultValue());

    assertEquals(Literals.stringLiteral("hello"), byName.get("tag").defaultValue());

    // DEFAULT NULL on a nullable column comes back as Literals.NULL.
    assertEquals(Literals.NULL, byName.get("memo").defaultValue());

    assertEquals(FunctionExpression.of("SYSTIMESTAMP"), byName.get("created_at").defaultValue());
    assertEquals(FunctionExpression.of("SYSDATE"), byName.get("created_date").defaultValue());
  }

  // ----------------------------------------------------------------------
  // Indexes
  // ----------------------------------------------------------------------

  @Test
  void testTableWithIndexes() {
    TableCatalog tableCatalog = catalog.asTableCatalog();
    String name = "IT_INDEX_TBL";
    NameIdentifier tableIdent = NameIdentifier.of(schemaName, name);

    Column[] columns =
        new Column[] {
          Column.of("id", Types.IntegerType.get(), null, false, false, null),
          Column.of("code", Types.VarCharType.of(32), null, false, false, null),
          Column.of(
              "qty",
              Types.IntegerType.get(),
              null,
              false,
              false,
              Literals.of("1", Types.IntegerType.get()))
        };

    Index[] indexes =
        new Index[] {
          Indexes.primary("PK_IT_INDEX_TBL", new String[][] {new String[] {"id"}}),
          Indexes.unique("UK_IT_INDEX_TBL_CODE", new String[][] {new String[] {"code"}})
        };

    catalog
        .asTableCatalog()
        .createTable(
            tableIdent,
            columns,
            null,
            ImmutableMap.of(),
            Transforms.EMPTY_TRANSFORM,
            Distributions.NONE,
            new SortOrder[0],
            indexes);

    Table loaded = tableCatalog.loadTable(tableIdent);
    Set<Index.IndexType> types =
        Arrays.stream(loaded.index()).map(Index::type).collect(Collectors.toSet());
    assertTrue(types.contains(Index.IndexType.PRIMARY_KEY));
    assertTrue(types.contains(Index.IndexType.UNIQUE_KEY));

    Column qty =
        Arrays.stream(loaded.columns())
            .filter(c -> "qty".equalsIgnoreCase(c.name()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("qty column missing"));
    assertEquals(Literals.stringLiteral("1"), qty.defaultValue());
  }

  // ----------------------------------------------------------------------
  // Alter table
  // ----------------------------------------------------------------------

  @Test
  void testAlterTableRename() {
    TableCatalog tableCatalog = catalog.asTableCatalog();
    String oldName = "IT_RENAME_OLD";
    String newName = "IT_RENAME_NEW";
    NameIdentifier oldIdent = NameIdentifier.of(schemaName, oldName);
    NameIdentifier newIdent = NameIdentifier.of(schemaName, newName);

    createTable(oldName, simpleColumns());

    tableCatalog.alterTable(oldIdent, TableChange.rename(newName));

    Table renamed = tableCatalog.loadTable(newIdent);
    assertEquals(newName.toUpperCase(Locale.ROOT), renamed.name());
    assertFalse(oracleService.tableExists(oldName));
    assertTrue(oracleService.tableExists(newName));
  }

  @Test
  void testAlterTableUpdateTableComment() {
    TableCatalog tableCatalog = catalog.asTableCatalog();
    String name = "IT_COMMENT_TBL";
    NameIdentifier tableIdent = NameIdentifier.of(schemaName, name);
    createTable(name, simpleColumns(), "initial", ImmutableMap.of(), Transforms.EMPTY_TRANSFORM);

    tableCatalog.alterTable(tableIdent, TableChange.updateComment("updated comment"));

    Table loaded = tableCatalog.loadTable(tableIdent);
    assertEquals("updated comment", loaded.comment());
  }

  @Test
  void testAlterTableColumnOperations() {
    TableCatalog tableCatalog = catalog.asTableCatalog();
    String name = "IT_ALTER_COLS";
    NameIdentifier tableIdent = NameIdentifier.of(schemaName, name);

    Column[] columns =
        new Column[] {
          Column.of("id", Types.IntegerType.get(), null, false, false, null),
          Column.of("name", Types.VarCharType.of(32), null, false, false, null),
          Column.of("to_drop", Types.VarCharType.of(16), null, true, false, null)
        };
    createTable(name, columns);

    // Add a new nullable column with a default value and a comment.
    tableCatalog.alterTable(
        tableIdent,
        TableChange.addColumn(
            new String[] {"qty"},
            Types.IntegerType.get(),
            "qty_comment",
            TableChange.ColumnPosition.defaultPos(),
            true,
            false,
            Literals.of("0", Types.IntegerType.get())));

    // Widen VARCHAR2(32) to VARCHAR2(64).
    tableCatalog.alterTable(
        tableIdent, TableChange.updateColumnType(new String[] {"name"}, Types.VarCharType.of(64)));

    // Rename `id` to `pk_id`.
    tableCatalog.alterTable(tableIdent, TableChange.renameColumn(new String[] {"id"}, "pk_id"));

    // Switch `pk_id` from NOT NULL to nullable so later nullability/default writes have room.
    tableCatalog.alterTable(
        tableIdent, TableChange.updateColumnNullability(new String[] {"pk_id"}, true));

    // Set a new default for `pk_id`.
    tableCatalog.alterTable(
        tableIdent,
        TableChange.updateColumnDefaultValue(
            new String[] {"pk_id"}, Literals.of("42", Types.IntegerType.get())));

    // Drop the no-longer-needed column.
    tableCatalog.alterTable(tableIdent, TableChange.deleteColumn(new String[] {"to_drop"}, false));

    Table loaded = tableCatalog.loadTable(tableIdent);

    Map<String, Column> byName =
        Arrays.stream(loaded.columns())
            .collect(Collectors.toMap(c -> c.name().toLowerCase(), c -> c));

    assertTrue(byName.containsKey("pk_id"));
    assertFalse(byName.containsKey("id"));
    assertTrue(byName.get("pk_id").nullable());
    // NUMBER default values round-trip as StringType literals; see testColumnDefaultValues.
    assertEquals(Literals.stringLiteral("42"), byName.get("pk_id").defaultValue());

    assertEquals(Types.VarCharType.of(64), byName.get("name").dataType());

    assertTrue(byName.containsKey("qty"));
    assertEquals(Types.IntegerType.get(), byName.get("qty").dataType());
    assertEquals(Literals.stringLiteral("0"), byName.get("qty").defaultValue());

    assertFalse(byName.containsKey("to_drop"));
  }

  @Test
  void testAlterTableIndexOperations() {
    TableCatalog tableCatalog = catalog.asTableCatalog();
    String name = "IT_ALTER_IDX";
    NameIdentifier tableIdent = NameIdentifier.of(schemaName, name);

    Column[] columns =
        new Column[] {
          Column.of("id", Types.IntegerType.get(), null, false, false, null),
          Column.of("code", Types.VarCharType.of(32), null, false, false, null)
        };
    createTable(name, columns);

    // Add a primary key via AddIndex.
    tableCatalog.alterTable(
        tableIdent,
        TableChange.addIndex(
            Index.IndexType.PRIMARY_KEY, "PK_IT_ALTER_IDX", new String[][] {new String[] {"id"}}));

    // Add a unique constraint.
    tableCatalog.alterTable(
        tableIdent,
        TableChange.addIndex(
            Index.IndexType.UNIQUE_KEY,
            "UK_IT_ALTER_IDX_CODE",
            new String[][] {new String[] {"code"}}));

    Table afterAdd = tableCatalog.loadTable(tableIdent);
    Set<Index.IndexType> typesAfterAdd =
        Arrays.stream(afterAdd.index()).map(Index::type).collect(Collectors.toSet());
    assertTrue(typesAfterAdd.contains(Index.IndexType.PRIMARY_KEY));
    assertTrue(typesAfterAdd.contains(Index.IndexType.UNIQUE_KEY));

    // Drop the unique constraint. Index names are not folded by Gravitino core (unlike
    // table/column names) and are quoted case-preserving, exactly like Oracle's own case-sensitive
    // quoted-identifier semantics, so this must reference the exact case it was created with.
    tableCatalog.alterTable(tableIdent, TableChange.deleteIndex("UK_IT_ALTER_IDX_CODE", false));

    Table afterDrop = tableCatalog.loadTable(tableIdent);
    Set<Index.IndexType> typesAfterDrop =
        Arrays.stream(afterDrop.index()).map(Index::type).collect(Collectors.toSet());
    assertTrue(typesAfterDrop.contains(Index.IndexType.PRIMARY_KEY));
    assertFalse(typesAfterDrop.contains(Index.IndexType.UNIQUE_KEY));
  }

  @Test
  void testAlterTableUnsupportedChanges() {
    TableCatalog tableCatalog = catalog.asTableCatalog();
    String name = "IT_UNSUPPORTED_ALTER";
    NameIdentifier tableIdent = NameIdentifier.of(schemaName, name);
    createTable(name, simpleColumns());

    // Column reordering is not supported by the Oracle catalog.
    assertThrows(
        UnsupportedOperationException.class,
        () ->
            tableCatalog.alterTable(
                tableIdent,
                TableChange.updateColumnPosition(
                    new String[] {"name"}, TableChange.ColumnPosition.first())));

    // Table properties are derived from Oracle system views, not settable through Gravitino.
    IllegalArgumentException setPropertyException =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                tableCatalog.alterTable(
                    tableIdent, TableChange.setProperty("tablespace", "USERS")));
    assertTrue(
        setPropertyException
            .getMessage()
            .contains("Property tablespace is immutable or reserved, cannot be set"));

    IllegalArgumentException removePropertyException =
        assertThrows(
            IllegalArgumentException.class,
            () -> tableCatalog.alterTable(tableIdent, TableChange.removeProperty("tablespace")));
    assertTrue(
        removePropertyException
            .getMessage()
            .contains("Property tablespace is immutable or reserved, cannot be deleted"));
  }

  // ----------------------------------------------------------------------
  // Partitioning
  // ----------------------------------------------------------------------

  @Test
  void testCreateRangePartitionedTable() {
    TableCatalog tableCatalog = catalog.asTableCatalog();
    String name = "IT_RANGE_PART";
    NameIdentifier tableIdent = NameIdentifier.of(schemaName, name);

    Column[] columns =
        new Column[] {
          Column.of("id", Types.IntegerType.get(), null, false, false, null),
          Column.of("bucket", Types.IntegerType.get(), null, false, false, null)
        };

    RangePartition p1 =
        Partitions.range(
            "p_low",
            Literals.of("100", Types.IntegerType.get()),
            Literals.NULL,
            Collections.emptyMap());
    RangePartition p2 =
        Partitions.range("p_rest", Literals.NULL, Literals.NULL, Collections.emptyMap());

    Transform[] partitioning = {
      Transforms.range(new String[] {"bucket"}, new RangePartition[] {p1, p2})
    };

    createTable(name, columns, null, ImmutableMap.of(), partitioning);

    Table loaded = tableCatalog.loadTable(tableIdent);
    assertTrue(loaded.partitioning().length > 0, "Expected partitioning to be reported");
    Transforms.RangeTransform loadedRange =
        assertInstanceOf(Transforms.RangeTransform.class, loaded.partitioning()[0]);
    assertEquals(2, loadedRange.assignments().length);
    assertEquals("p_low", loadedRange.assignments()[0].name().toLowerCase());
    assertEquals("100", loadedRange.assignments()[0].upper().value());
    assertEquals(Literals.NULL, loadedRange.assignments()[0].lower());
    assertEquals("p_rest", loadedRange.assignments()[1].name().toLowerCase());
    assertEquals(Literals.NULL, loadedRange.assignments()[1].upper());
    // The "partitioned" property is derived from ALL_TABLES.PARTITIONED and is now visible on load
    // (see issue #854); a partitioned table reports "YES".
    assertEquals("YES", loaded.properties().get(PARTITIONED));
  }

  @Test
  void testCreateTimestampRangePartitionedTable() {
    TableCatalog tableCatalog = catalog.asTableCatalog();
    String name = "IT_TIMESTAMP_RANGE_PART";
    NameIdentifier tableIdent = NameIdentifier.of(schemaName, name);

    Column[] columns =
        new Column[] {
          Column.of("id", Types.IntegerType.get(), null, false, false, null),
          Column.of("event_time", Types.TimestampType.withoutTimeZone(), null, false, false, null)
        };
    RangePartition p2024 =
        Partitions.range(
            "p_2024",
            Literals.timestampLiteral("2025-01-01T00:00:00"),
            Literals.NULL,
            Collections.emptyMap());
    RangePartition pMax =
        Partitions.range("p_max", Literals.NULL, Literals.NULL, Collections.emptyMap());

    createTable(
        name,
        columns,
        null,
        ImmutableMap.of(),
        new Transform[] {
          Transforms.range(new String[] {"event_time"}, new RangePartition[] {p2024, pMax})
        });

    Table loaded = tableCatalog.loadTable(tableIdent);
    Transforms.RangeTransform loadedRange =
        assertInstanceOf(Transforms.RangeTransform.class, loaded.partitioning()[0]);
    assertEquals(2, loadedRange.assignments().length);
    assertEquals("p_2024", loadedRange.assignments()[0].name().toLowerCase());
    assertEquals(
        LocalDateTime.parse("2025-01-01T00:00:00"),
        LocalDateTime.parse(loadedRange.assignments()[0].upper().value().toString()));
    assertEquals("p_max", loadedRange.assignments()[1].name().toLowerCase());
    assertEquals(Literals.NULL, loadedRange.assignments()[1].upper());
  }

  @Test
  void testCreateListPartitionedTable() {
    TableCatalog tableCatalog = catalog.asTableCatalog();
    String name = "IT_LIST_PART";
    NameIdentifier tableIdent = NameIdentifier.of(schemaName, name);

    Column[] columns =
        new Column[] {
          Column.of("id", Types.IntegerType.get(), null, false, false, null),
          Column.of("region", Types.VarCharType.of(16), null, false, false, null)
        };

    Literal<?>[][] asiaValues = {{Literals.of("CN", Types.VarCharType.of(16))}};
    Literal<?>[][] euValues = {{Literals.of("DE", Types.VarCharType.of(16))}};
    ListPartition pAsia = Partitions.list("p_asia", asiaValues, Collections.emptyMap());
    ListPartition pEu = Partitions.list("p_eu", euValues, Collections.emptyMap());

    Transform[] partitioning = {
      Transforms.list(new String[][] {{"region"}}, new ListPartition[] {pAsia, pEu})
    };

    createTable(name, columns, null, ImmutableMap.of(), partitioning);

    Table loaded = tableCatalog.loadTable(tableIdent);
    assertNotNull(loaded.partitioning());
    assertTrue(loaded.partitioning().length > 0);
    assertInstanceOf(Transforms.ListTransform.class, loaded.partitioning()[0]);
  }

  @Test
  void testCreateHashPartitionedTable() {
    TableCatalog tableCatalog = catalog.asTableCatalog();
    String name = "IT_HASH_PART";
    NameIdentifier tableIdent = NameIdentifier.of(schemaName, name);

    Column[] columns =
        new Column[] {
          Column.of("id", Types.IntegerType.get(), null, false, false, null),
          Column.of("bucket", Types.IntegerType.get(), null, false, false, null)
        };

    Transform[] partitioning = {Transforms.bucket(4, new String[] {"bucket"})};

    createTable(name, columns, null, ImmutableMap.of(), partitioning);

    Table loaded = tableCatalog.loadTable(tableIdent);
    assertTrue(loaded.partitioning().length > 0);
    assertInstanceOf(Transforms.BucketTransform.class, loaded.partitioning()[0]);
    // Issue #854: the "partitioned" property must be returned by loadTable — "YES" for a
    // HASH-partitioned table.
    assertEquals("YES", loaded.properties().get(PARTITIONED));
  }

  @Test
  void testOracleTablePropertiesVisibleOnLoad() {
    // Issue #854: partitioned, row_movement and compression are read-only ALL_TABLES metadata and
    // must be returned by loadTable. They were previously registered hidden=true and stripped from
    // the response, so loaded.properties().get(...) always returned null.
    TableCatalog tableCatalog = catalog.asTableCatalog();

    // Non-partitioned table: partitioned = NO, and row_movement/compression are present.
    String plain = "IT_PROPS_PLAIN";
    createTable(plain, simpleColumns());
    Map<String, String> plainProps =
        tableCatalog.loadTable(NameIdentifier.of(schemaName, plain)).properties();
    assertEquals("NO", plainProps.get(PARTITIONED), "partitioned should be visible: " + plainProps);
    assertTrue(
        plainProps.containsKey(ROW_MOVEMENT), "row_movement should be visible: " + plainProps);
    assertTrue(plainProps.containsKey(COMPRESSION), "compression should be visible: " + plainProps);

    // HASH-partitioned table: partitioned = YES (the exact scenario reported in issue #854).
    String parted = "IT_PROPS_PART";
    Column[] columns =
        new Column[] {
          Column.of("id", Types.IntegerType.get(), null, false, false, null),
          Column.of("bucket", Types.IntegerType.get(), null, false, false, null)
        };
    createTable(
        parted,
        columns,
        null,
        ImmutableMap.of(),
        new Transform[] {Transforms.bucket(4, new String[] {"bucket"})});
    Map<String, String> partedProps =
        tableCatalog.loadTable(NameIdentifier.of(schemaName, parted)).properties();
    assertEquals("YES", partedProps.get(PARTITIONED));
  }
}
