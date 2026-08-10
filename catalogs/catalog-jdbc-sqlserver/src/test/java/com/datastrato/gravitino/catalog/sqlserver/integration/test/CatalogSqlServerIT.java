/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.catalog.sqlserver.integration.test;

import static org.apache.gravitino.rel.Column.DEFAULT_VALUE_OF_CURRENT_TIMESTAMP;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.Schema;
import org.apache.gravitino.SupportsSchemas;
import org.apache.gravitino.catalog.jdbc.config.JdbcConfig;
import org.apache.gravitino.client.GravitinoMetalake;
import org.apache.gravitino.exceptions.NoSuchSchemaException;
import org.apache.gravitino.exceptions.SchemaAlreadyExistsException;
import org.apache.gravitino.exceptions.TableAlreadyExistsException;
import org.apache.gravitino.integration.test.container.ContainerSuite;
import org.apache.gravitino.integration.test.container.SqlServerContainer;
import org.apache.gravitino.integration.test.util.BaseIT;
import org.apache.gravitino.integration.test.util.GravitinoITUtils;
import org.apache.gravitino.integration.test.util.TestDatabaseName;
import org.apache.gravitino.rel.Column;
import org.apache.gravitino.rel.Table;
import org.apache.gravitino.rel.TableCatalog;
import org.apache.gravitino.rel.TableChange;
import org.apache.gravitino.rel.expressions.distributions.Distributions;
import org.apache.gravitino.rel.expressions.literals.Literals;
import org.apache.gravitino.rel.expressions.sorts.SortOrder;
import org.apache.gravitino.rel.expressions.transforms.Transforms;
import org.apache.gravitino.rel.indexes.Index;
import org.apache.gravitino.rel.indexes.Indexes;
import org.apache.gravitino.rel.types.Types;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

/** Integration tests for the SQL Server JDBC catalog. */
@Tag("gravitino-docker-test")
@TestInstance(Lifecycle.PER_CLASS)
public class CatalogSqlServerIT extends BaseIT {

  private static final ContainerSuite containerSuite = ContainerSuite.getInstance();
  private static final TestDatabaseName TEST_DB_NAME =
      TestDatabaseName.SQLSERVER_CATALOG_JDBC_SQLSERVER_IT;
  private static final String PROVIDER = "jdbc-sqlserver";

  private String metalakeName = GravitinoITUtils.genRandomName("sqlserver_it_metalake");
  private String catalogName = GravitinoITUtils.genRandomName("sqlserver_it_catalog");
  private String schemaName = GravitinoITUtils.genRandomName("sqlserver_it_schema");
  private String tableName = GravitinoITUtils.genRandomName("sqlserver_it_table");
  private String alertTableName = "alert_table_name";
  private String tableComment = "table_comment";

  private static final String SQLSERVER_COL_NAME1 = "sqlserver_col_name1";
  private static final String SQLSERVER_COL_NAME2 = "sqlserver_col_name2";
  private static final String SQLSERVER_COL_NAME3 = "sqlserver_col_name3";

  private String databaseName;

  private GravitinoMetalake metalake;
  private Catalog catalog;
  private SqlServerContainer sqlServerContainer;

  private String jdbcUrl;

  @BeforeAll
  @Override
  public void startIntegrationTest() throws Exception {
    super.startIntegrationTest();
    databaseName = TEST_DB_NAME.toString();

    containerSuite.startSqlServerContainer(TEST_DB_NAME);
    sqlServerContainer = containerSuite.getSqlServerContainer();
    jdbcUrl = sqlServerContainer.getJdbcUrl(TEST_DB_NAME);

    createMetalake();
    catalog = createCatalog(catalogName);
    createSchema(schemaName);
  }

  @AfterAll
  public void stop() throws Exception {
    clearTableAndSchema();
    metalake.dropCatalog(catalogName, true);
    client.dropMetalake(metalakeName, true);
    super.stopIntegrationTest();
  }

  @AfterEach
  public void resetSchema() {
    clearTableAndSchema();
    createSchema(schemaName);
  }

  private void clearTableAndSchema() {
    NameIdentifier[] tables = catalog.asTableCatalog().listTables(Namespace.of(schemaName));
    for (NameIdentifier table : tables) {
      catalog.asTableCatalog().dropTable(table);
    }
    catalog.asSchemas().dropSchema(schemaName, false);
  }

  private void createMetalake() {
    GravitinoMetalake[] metalakes = client.listMetalakes();
    Assertions.assertEquals(0, metalakes.length);

    client.createMetalake(metalakeName, "comment", Collections.emptyMap());
    GravitinoMetalake loaded = client.loadMetalake(metalakeName);
    Assertions.assertEquals(metalakeName, loaded.name());
    metalake = loaded;
  }

  private Catalog createCatalog(String catalogName) {
    Map<String, String> properties = Maps.newHashMap();
    properties.put(JdbcConfig.JDBC_URL.getKey(), jdbcUrl);
    properties.put(
        JdbcConfig.JDBC_DRIVER.getKey(), sqlServerContainer.getDriverClassName(TEST_DB_NAME));
    properties.put(JdbcConfig.JDBC_DATABASE.getKey(), databaseName);
    properties.put(JdbcConfig.USERNAME.getKey(), sqlServerContainer.getUsername());
    properties.put(JdbcConfig.PASSWORD.getKey(), sqlServerContainer.getPassword());

    Catalog created =
        metalake.createCatalog(
            catalogName, Catalog.Type.RELATIONAL, PROVIDER, "comment", properties);
    Catalog loaded = metalake.loadCatalog(catalogName);
    Assertions.assertEquals(created, loaded);
    return loaded;
  }

  private void createSchema(String schemaName) {
    Schema created = catalog.asSchemas().createSchema(schemaName, null, Collections.emptyMap());
    Schema loaded = catalog.asSchemas().loadSchema(schemaName);
    Assertions.assertEquals(created.name(), loaded.name());
  }

  private Column[] createColumns() {
    Column col1 = Column.of(SQLSERVER_COL_NAME1, Types.IntegerType.get(), "col_1_comment");
    Column col2 = Column.of(SQLSERVER_COL_NAME2, Types.DateType.get(), "col_2_comment");
    Column col3 = Column.of(SQLSERVER_COL_NAME3, Types.StringType.get(), "col_3_comment");
    return new Column[] {col1, col2, col3};
  }

  private void assertColumnExactly(Column expected, Column actual) {
    Assertions.assertEquals(expected.name(), actual.name());
    Assertions.assertEquals(expected.dataType(), actual.dataType());
    Assertions.assertEquals(expected.nullable(), actual.nullable());
    Assertions.assertEquals(expected.comment(), actual.comment());
    Assertions.assertEquals(expected.autoIncrement(), actual.autoIncrement());
    Assertions.assertEquals(expected.defaultValue(), actual.defaultValue());
  }

  // ==================== Schema CRUD Tests ====================

  @Test
  void testCreateAndLoadSchema() {
    String testSchemaName = GravitinoITUtils.genRandomName("test_schema");
    Schema created = catalog.asSchemas().createSchema(testSchemaName, null, Collections.emptyMap());
    Assertions.assertEquals(testSchemaName, created.name());

    Schema loaded = catalog.asSchemas().loadSchema(testSchemaName);
    Assertions.assertEquals(testSchemaName, loaded.name());

    // Cleanup
    catalog.asSchemas().dropSchema(testSchemaName, false);
  }

  @Test
  void testListSchemas() {
    String[] schemas = catalog.asSchemas().listSchemas();
    Set<String> schemaSet = Sets.newHashSet(schemas);

    // dbo should be visible
    Assertions.assertTrue(schemaSet.contains("dbo"));
    // Our test schema should be visible
    Assertions.assertTrue(schemaSet.contains(schemaName));

    // System schemas should be filtered
    Assertions.assertFalse(schemaSet.contains("guest"));
    Assertions.assertFalse(schemaSet.contains("INFORMATION_SCHEMA"));
    Assertions.assertFalse(schemaSet.contains("sys"));
    Assertions.assertFalse(schemaSet.contains("db_owner"));
    Assertions.assertFalse(schemaSet.contains("db_accessadmin"));
  }

  @Test
  void testDropSchema() {
    String testSchemaName = GravitinoITUtils.genRandomName("drop_schema");
    catalog.asSchemas().createSchema(testSchemaName, null, Collections.emptyMap());

    // Drop empty schema should succeed
    boolean dropped = catalog.asSchemas().dropSchema(testSchemaName, false);
    Assertions.assertTrue(dropped);

    // Load after drop should throw
    Assertions.assertThrows(
        NoSuchSchemaException.class, () -> catalog.asSchemas().loadSchema(testSchemaName));

    // Drop non-existent schema should return false
    Assertions.assertFalse(catalog.asSchemas().dropSchema("non_existent_schema", false));
  }

  @Test
  void testDropNonEmptySchema() {
    String testSchemaName = GravitinoITUtils.genRandomName("nonempty_schema");
    catalog.asSchemas().createSchema(testSchemaName, null, Collections.emptyMap());

    // Create a table in the schema
    Column[] columns = createColumns();
    NameIdentifier tableIdent = NameIdentifier.of(testSchemaName, "test_table");
    catalog.asTableCatalog().createTable(tableIdent, columns, null, ImmutableMap.of());

    // Drop non-empty schema without cascade should fail
    // SQL Server does not support CASCADE, so the native error is surfaced
    Assertions.assertThrows(
        Exception.class, () -> catalog.asSchemas().dropSchema(testSchemaName, false));

    // Cleanup: drop table first, then schema
    catalog.asTableCatalog().dropTable(tableIdent);
    catalog.asSchemas().dropSchema(testSchemaName, false);
  }

  @Test
  void testSchemaCommentRejection() {
    String testSchemaName = GravitinoITUtils.genRandomName("comment_schema");
    // SQL Server does not support schema comments; creating with a non-empty comment
    // should throw UnsupportedOperationException
    Assertions.assertThrows(
        UnsupportedOperationException.class,
        () ->
            catalog
                .asSchemas()
                .createSchema(testSchemaName, "some comment", Collections.emptyMap()));
  }

  @Test
  void testDuplicateSchemaCreation() {
    String testSchemaName = GravitinoITUtils.genRandomName("dup_schema");
    catalog.asSchemas().createSchema(testSchemaName, null, Collections.emptyMap());

    Assertions.assertThrows(
        SchemaAlreadyExistsException.class,
        () -> catalog.asSchemas().createSchema(testSchemaName, null, Collections.emptyMap()));

    // Cleanup
    catalog.asSchemas().dropSchema(testSchemaName, false);
  }

  // ==================== Table CRUD Tests ====================

  @Test
  void testCreateAndLoadTable() {
    Column[] columns = createColumns();
    NameIdentifier tableIdent = NameIdentifier.of(schemaName, tableName);

    TableCatalog tableCatalog = catalog.asTableCatalog();
    tableCatalog.createTable(
        tableIdent,
        columns,
        tableComment,
        ImmutableMap.of(),
        Transforms.EMPTY_TRANSFORM,
        Distributions.NONE,
        new SortOrder[0]);

    Table loaded = tableCatalog.loadTable(tableIdent);
    Assertions.assertEquals(tableName, loaded.name());
    Assertions.assertEquals(tableComment, loaded.comment());
    Assertions.assertEquals(columns.length, loaded.columns().length);
    for (int i = 0; i < columns.length; i++) {
      assertColumnExactly(columns[i], loaded.columns()[i]);
    }
  }

  @Test
  void testCreateTableWithIdentityAndConstraints() {
    Column col1 = Column.of("id", Types.IntegerType.get(), "primary key", false, true, null);
    Column col2 = Column.of("name", Types.VarCharType.of(100), "name col", false, false, null);
    Column col3 = Column.of("email", Types.VarCharType.of(200), "email col", true, false, null);

    Index[] indexes =
        new Index[] {
          Indexes.primary("PK_test_table", new String[][] {{"id"}}),
          Indexes.unique("UQ_test_email", new String[][] {{"email"}}),
        };

    NameIdentifier tableIdent =
        NameIdentifier.of(schemaName, GravitinoITUtils.genRandomName("identity_table"));
    TableCatalog tableCatalog = catalog.asTableCatalog();
    tableCatalog.createTable(
        tableIdent,
        new Column[] {col1, col2, col3},
        "table with identity",
        ImmutableMap.of(),
        Transforms.EMPTY_TRANSFORM,
        Distributions.NONE,
        new SortOrder[0],
        indexes);

    Table loaded = tableCatalog.loadTable(tableIdent);
    Assertions.assertEquals("table with identity", loaded.comment());

    // Verify IDENTITY column
    Column loadedCol1 = loaded.columns()[0];
    Assertions.assertEquals("id", loadedCol1.name());
    Assertions.assertTrue(loadedCol1.autoIncrement());
    Assertions.assertFalse(loadedCol1.nullable());

    // Verify indexes
    Assertions.assertEquals(2, loaded.index().length);
    Set<String> indexNames =
        Arrays.stream(loaded.index()).map(Index::name).collect(Collectors.toSet());
    Assertions.assertTrue(indexNames.contains("PK_test_table"));
    Assertions.assertTrue(indexNames.contains("UQ_test_email"));
  }

  @Test
  void testCreateTableWithDefaults() {
    Column col1 =
        Column.of(
            "col_int",
            Types.IntegerType.get(),
            "int with default",
            true,
            false,
            Literals.integerLiteral(42));
    Column col2 =
        Column.of(
            "col_str",
            Types.VarCharType.of(100),
            "str with default",
            true,
            false,
            Literals.varcharLiteral(100, "hello"));
    Column col3 =
        Column.of(
            "col_ts",
            Types.TimestampType.withoutTimeZone(),
            "ts with default",
            true,
            false,
            DEFAULT_VALUE_OF_CURRENT_TIMESTAMP);
    Column col4 =
        Column.of("col_null", Types.StringType.get(), "nullable col", true, false, Literals.NULL);

    NameIdentifier tableIdent =
        NameIdentifier.of(schemaName, GravitinoITUtils.genRandomName("default_table"));
    TableCatalog tableCatalog = catalog.asTableCatalog();
    tableCatalog.createTable(
        tableIdent, new Column[] {col1, col2, col3, col4}, null, ImmutableMap.of());

    Table loaded = tableCatalog.loadTable(tableIdent);
    Assertions.assertEquals(Literals.integerLiteral(42), loaded.columns()[0].defaultValue());
    Assertions.assertEquals(
        Literals.varcharLiteral(100, "hello"), loaded.columns()[1].defaultValue());
    Assertions.assertEquals(DEFAULT_VALUE_OF_CURRENT_TIMESTAMP, loaded.columns()[2].defaultValue());
    Assertions.assertEquals(Literals.NULL, loaded.columns()[3].defaultValue());
  }

  @Test
  void testListTables() {
    Column[] columns = createColumns();
    String table1 = GravitinoITUtils.genRandomName("list_t1");
    String table2 = GravitinoITUtils.genRandomName("list_t2");

    TableCatalog tableCatalog = catalog.asTableCatalog();
    tableCatalog.createTable(
        NameIdentifier.of(schemaName, table1), columns, null, ImmutableMap.of());
    tableCatalog.createTable(
        NameIdentifier.of(schemaName, table2), columns, null, ImmutableMap.of());

    NameIdentifier[] tables = tableCatalog.listTables(Namespace.of(schemaName));
    Set<String> tableNames =
        Arrays.stream(tables).map(NameIdentifier::name).collect(Collectors.toSet());
    Assertions.assertTrue(tableNames.contains(table1));
    Assertions.assertTrue(tableNames.contains(table2));
  }

  @Test
  void testDropTable() {
    Column[] columns = createColumns();
    String dropTableName = GravitinoITUtils.genRandomName("drop_table");
    NameIdentifier tableIdent = NameIdentifier.of(schemaName, dropTableName);

    TableCatalog tableCatalog = catalog.asTableCatalog();
    tableCatalog.createTable(tableIdent, columns, null, ImmutableMap.of());

    boolean dropped = tableCatalog.dropTable(tableIdent);
    Assertions.assertTrue(dropped);

    // Drop non-existent table should return false
    Assertions.assertFalse(tableCatalog.dropTable(tableIdent));
  }

  // ==================== ALTER TABLE Tests ====================

  @Test
  void testAlterTableRename() {
    Column[] columns = createColumns();
    NameIdentifier tableIdent = NameIdentifier.of(schemaName, tableName);
    TableCatalog tableCatalog = catalog.asTableCatalog();
    tableCatalog.createTable(tableIdent, columns, tableComment, ImmutableMap.of());

    // Rename table
    Table renamed = tableCatalog.alterTable(tableIdent, TableChange.rename(alertTableName));
    Assertions.assertEquals(alertTableName, renamed.name());

    Table loaded = tableCatalog.loadTable(NameIdentifier.of(schemaName, alertTableName));
    Assertions.assertEquals(alertTableName, loaded.name());
  }

  @Test
  void testAlterTableAddAndDropColumn() {
    Column[] columns = createColumns();
    NameIdentifier tableIdent = NameIdentifier.of(schemaName, tableName);
    TableCatalog tableCatalog = catalog.asTableCatalog();
    tableCatalog.createTable(tableIdent, columns, tableComment, ImmutableMap.of());

    // Add column
    tableCatalog.alterTable(
        tableIdent,
        TableChange.addColumn(
            new String[] {"col_4"}, Types.VarCharType.of(50), "new column", true));

    Table loaded = tableCatalog.loadTable(tableIdent);
    Assertions.assertEquals(4, loaded.columns().length);
    Assertions.assertEquals("col_4", loaded.columns()[3].name());
    Assertions.assertEquals(Types.VarCharType.of(50), loaded.columns()[3].dataType());

    // Drop column
    tableCatalog.alterTable(tableIdent, TableChange.deleteColumn(new String[] {"col_4"}, true));

    loaded = tableCatalog.loadTable(tableIdent);
    Assertions.assertEquals(3, loaded.columns().length);
  }

  @Test
  void testAlterTableRenameColumn() {
    Column[] columns = createColumns();
    NameIdentifier tableIdent = NameIdentifier.of(schemaName, tableName);
    TableCatalog tableCatalog = catalog.asTableCatalog();
    tableCatalog.createTable(tableIdent, columns, tableComment, ImmutableMap.of());

    tableCatalog.alterTable(
        tableIdent, TableChange.renameColumn(new String[] {SQLSERVER_COL_NAME2}, "col_2_renamed"));

    Table loaded = tableCatalog.loadTable(tableIdent);
    Assertions.assertEquals("col_2_renamed", loaded.columns()[1].name());
    Assertions.assertEquals(Types.DateType.get(), loaded.columns()[1].dataType());
  }

  @Test
  void testAlterTableModifyColumnType() {
    Column col1 = Column.of("col_a", Types.IntegerType.get(), "col a", true, false, null);
    Column col2 = Column.of("col_b", Types.VarCharType.of(50), "col b", true, false, null);

    NameIdentifier tableIdent = NameIdentifier.of(schemaName, tableName);
    TableCatalog tableCatalog = catalog.asTableCatalog();
    tableCatalog.createTable(
        tableIdent, new Column[] {col1, col2}, tableComment, ImmutableMap.of());

    // Change col_b from varchar(50) to varchar(200)
    tableCatalog.alterTable(
        tableIdent,
        TableChange.updateColumnType(new String[] {"col_b"}, Types.VarCharType.of(200)));

    Table loaded = tableCatalog.loadTable(tableIdent);
    Assertions.assertEquals(Types.VarCharType.of(200), loaded.columns()[1].dataType());
  }

  @Test
  void testAlterTableUpdateColumnNullability() {
    Column col1 =
        Column.of(
            "col_a", Types.IntegerType.get(), "col a", true, false, Literals.integerLiteral(0));

    NameIdentifier tableIdent = NameIdentifier.of(schemaName, tableName);
    TableCatalog tableCatalog = catalog.asTableCatalog();
    tableCatalog.createTable(tableIdent, new Column[] {col1}, tableComment, ImmutableMap.of());

    // Change from nullable to not null (column has a non-null default value)
    tableCatalog.alterTable(
        tableIdent, TableChange.updateColumnNullability(new String[] {"col_a"}, false));

    Table loaded = tableCatalog.loadTable(tableIdent);
    Assertions.assertFalse(loaded.columns()[0].nullable());

    // Change back to nullable
    tableCatalog.alterTable(
        tableIdent, TableChange.updateColumnNullability(new String[] {"col_a"}, true));

    loaded = tableCatalog.loadTable(tableIdent);
    Assertions.assertTrue(loaded.columns()[0].nullable());
  }

  @Test
  void testAlterTableUpdateComment() {
    Column[] columns = createColumns();
    NameIdentifier tableIdent = NameIdentifier.of(schemaName, tableName);
    TableCatalog tableCatalog = catalog.asTableCatalog();
    tableCatalog.createTable(tableIdent, columns, tableComment, ImmutableMap.of());

    String newComment = "updated_comment";
    tableCatalog.alterTable(tableIdent, TableChange.updateComment(newComment));

    Table loaded = tableCatalog.loadTable(tableIdent);
    Assertions.assertEquals(newComment, loaded.comment());
  }

  @Test
  void testAlterTableUpdateColumnComment() {
    Column[] columns = createColumns();
    NameIdentifier tableIdent = NameIdentifier.of(schemaName, tableName);
    TableCatalog tableCatalog = catalog.asTableCatalog();
    tableCatalog.createTable(tableIdent, columns, tableComment, ImmutableMap.of());

    tableCatalog.alterTable(
        tableIdent,
        TableChange.updateColumnComment(new String[] {SQLSERVER_COL_NAME1}, "new_col_comment"));

    Table loaded = tableCatalog.loadTable(tableIdent);
    Assertions.assertEquals("new_col_comment", loaded.columns()[0].comment());
  }

  @Test
  void testUpdateColumnDefaultToNull() {
    Column col1 =
        Column.of(
            "col_int", Types.IntegerType.get(), null, true, false, Literals.integerLiteral(10));

    NameIdentifier tableIdent = NameIdentifier.of(schemaName, tableName);
    TableCatalog tableCatalog = catalog.asTableCatalog();
    tableCatalog.createTable(tableIdent, new Column[] {col1}, null, ImmutableMap.of());

    // Verify initial default is persisted before updating
    Table beforeUpdate = tableCatalog.loadTable(tableIdent);
    Assertions.assertEquals(Literals.integerLiteral(10), beforeUpdate.columns()[0].defaultValue());

    tableCatalog.alterTable(
        tableIdent, TableChange.updateColumnDefaultValue(new String[] {"col_int"}, Literals.NULL));

    Table loaded = tableCatalog.loadTable(tableIdent);
    Assertions.assertEquals(
        Literals.NULL,
        loaded.columns()[0].defaultValue(),
        "Default must be Literals.NULL after update");
  }

  @Test
  void testAlterTableUpdateColumnDefaultValue() {
    Column col1 =
        Column.of(
            "col_def",
            Types.IntegerType.get(),
            "col with default",
            true,
            false,
            Literals.integerLiteral(10));

    NameIdentifier tableIdent = NameIdentifier.of(schemaName, tableName);
    TableCatalog tableCatalog = catalog.asTableCatalog();
    tableCatalog.createTable(tableIdent, new Column[] {col1}, tableComment, ImmutableMap.of());

    tableCatalog.alterTable(
        tableIdent,
        TableChange.updateColumnDefaultValue(
            new String[] {"col_def"}, Literals.integerLiteral(99)));

    Table loaded = tableCatalog.loadTable(tableIdent);
    Assertions.assertEquals(Literals.integerLiteral(99), loaded.columns()[0].defaultValue());
  }

  @Test
  void testAlterTableUpdateColumnPositionRejected() {
    Column[] columns = createColumns();
    NameIdentifier tableIdent = NameIdentifier.of(schemaName, tableName);
    TableCatalog tableCatalog = catalog.asTableCatalog();
    tableCatalog.createTable(tableIdent, columns, tableComment, ImmutableMap.of());

    // UpdateColumnPosition is not supported by SQL Server
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () ->
            tableCatalog.alterTable(
                tableIdent,
                TableChange.updateColumnPosition(
                    new String[] {SQLSERVER_COL_NAME3}, TableChange.ColumnPosition.first())));
  }

  // ==================== Type Mapping Tests ====================

  @Test
  void testTypeMappingRoundTrip() {
    Column[] columns =
        new Column[] {
          Column.of("col_tinyint", Types.ByteType.get(), null, true, false, null),
          Column.of("col_smallint", Types.ShortType.get(), null, true, false, null),
          Column.of("col_int", Types.IntegerType.get(), null, true, false, null),
          Column.of("col_bigint", Types.LongType.get(), null, true, false, null),
          Column.of("col_bit", Types.BooleanType.get(), null, true, false, null),
          Column.of("col_decimal", Types.DecimalType.of(18, 4), null, true, false, null),
          Column.of("col_real", Types.FloatType.get(), null, true, false, null),
          Column.of("col_float", Types.DoubleType.get(), null, true, false, null),
          Column.of("col_date", Types.DateType.get(), null, true, false, null),
          Column.of("col_time", Types.TimeType.of(3), null, true, false, null),
          Column.of(
              "col_datetime2", Types.TimestampType.withoutTimeZone(3), null, true, false, null),
          Column.of("col_char", Types.FixedCharType.of(10), null, true, false, null),
          Column.of("col_varchar", Types.VarCharType.of(255), null, true, false, null),
          Column.of("col_nvarchar", Types.StringType.get(), null, true, false, null),
          Column.of("col_binary", Types.FixedType.of(16), null, true, false, null),
          Column.of("col_varbinary", Types.BinaryType.get(), null, true, false, null),
          Column.of("col_uuid", Types.UUIDType.get(), null, true, false, null),
        };

    NameIdentifier tableIdent =
        NameIdentifier.of(schemaName, GravitinoITUtils.genRandomName("type_table"));
    TableCatalog tableCatalog = catalog.asTableCatalog();
    tableCatalog.createTable(tableIdent, columns, "type mapping test", ImmutableMap.of());

    Table loaded = tableCatalog.loadTable(tableIdent);
    Assertions.assertEquals(columns.length, loaded.columns().length);

    for (int i = 0; i < columns.length; i++) {
      Assertions.assertEquals(
          columns[i].dataType(),
          loaded.columns()[i].dataType(),
          "Type mismatch for column: " + columns[i].name());
    }
  }

  @Test
  void testNvarcharStringTypeMapping() {
    // Verify nvarchar -> StringType and StringType -> nvarchar one-to-one mapping
    Column col = Column.of("nvarchar_col", Types.StringType.get(), null, true, false, null);

    NameIdentifier tableIdent =
        NameIdentifier.of(schemaName, GravitinoITUtils.genRandomName("nvarchar_table"));
    TableCatalog tableCatalog = catalog.asTableCatalog();
    tableCatalog.createTable(tableIdent, new Column[] {col}, null, ImmutableMap.of());

    Table loaded = tableCatalog.loadTable(tableIdent);
    Assertions.assertEquals(Types.StringType.get(), loaded.columns()[0].dataType());

    // Verify via direct SQL that the underlying type is nvarchar
    try (Connection conn =
            DriverManager.getConnection(
                jdbcUrl, sqlServerContainer.getUsername(), sqlServerContainer.getPassword());
        Statement stmt = conn.createStatement();
        ResultSet rs =
            stmt.executeQuery(
                "SELECT t.name FROM sys.columns c "
                    + "JOIN sys.types t ON c.user_type_id = t.user_type_id "
                    + "WHERE c.object_id = OBJECT_ID('"
                    + schemaName
                    + "."
                    + loaded.name()
                    + "') AND c.name = 'nvarchar_col'")) {
      Assertions.assertTrue(rs.next());
      Assertions.assertEquals("nvarchar", rs.getString(1));
    } catch (SQLException e) {
      Assertions.fail("Failed to verify nvarchar type: " + e.getMessage());
    }
  }

  @Test
  void testExternalTypeMapping() {
    // Create a table with ExternalType columns via direct SQL, then load via Gravitino
    String extTableName = GravitinoITUtils.genRandomName("ext_type_table");
    try (Connection conn =
            DriverManager.getConnection(
                jdbcUrl, sqlServerContainer.getUsername(), sqlServerContainer.getPassword());
        Statement stmt = conn.createStatement()) {
      stmt.execute(
          "CREATE TABLE ["
              + schemaName
              + "].["
              + extTableName
              + "] ("
              + "[col_money] money NULL, "
              + "[col_datetime] datetime NULL, "
              + "[col_nvarchar] nvarchar(50) NULL, "
              + "[col_nvarchar_max] nvarchar(max) NULL, "
              + "[col_xml] xml NULL"
              + ")");
    } catch (SQLException e) {
      Assertions.fail("Failed to create external type table: " + e.getMessage());
    }

    Table loaded = catalog.asTableCatalog().loadTable(NameIdentifier.of(schemaName, extTableName));
    Assertions.assertEquals(5, loaded.columns().length);
    Assertions.assertEquals(Types.ExternalType.of("money"), loaded.columns()[0].dataType());
    Assertions.assertEquals(Types.TimestampType.withoutTimeZone(3), loaded.columns()[1].dataType());
    Assertions.assertEquals(Types.VarCharType.of(50), loaded.columns()[2].dataType());
    Assertions.assertEquals(Types.StringType.get(), loaded.columns()[3].dataType());
    Assertions.assertEquals(Types.ExternalType.of("xml"), loaded.columns()[4].dataType());
    Arrays.stream(loaded.columns())
        .forEach(
            column ->
                Assertions.assertEquals(
                    Column.DEFAULT_VALUE_NOT_SET,
                    column.defaultValue(),
                    "Unexpected default for " + column.name()));
  }

  // ==================== Exception Mapping Tests ====================

  @Test
  void testDuplicateTableCreation() {
    Column[] columns = createColumns();
    String dupTableName = GravitinoITUtils.genRandomName("dup_table");
    NameIdentifier tableIdent = NameIdentifier.of(schemaName, dupTableName);

    TableCatalog tableCatalog = catalog.asTableCatalog();
    tableCatalog.createTable(tableIdent, columns, null, ImmutableMap.of());

    // Creating the same table again should throw TableAlreadyExistsException
    Assertions.assertThrows(
        TableAlreadyExistsException.class,
        () -> tableCatalog.createTable(tableIdent, columns, null, ImmutableMap.of()));
  }

  @Test
  void testOperationOnNonExistentSchema() {
    NameIdentifier tableIdent = NameIdentifier.of("non_existent_schema", "some_table");
    TableCatalog tableCatalog = catalog.asTableCatalog();

    Assertions.assertThrows(
        NoSuchSchemaException.class,
        () -> tableCatalog.createTable(tableIdent, createColumns(), null, ImmutableMap.of()));
  }

  @Test
  void testLoadNonExistentSchema() {
    Assertions.assertThrows(
        NoSuchSchemaException.class,
        () -> catalog.asSchemas().loadSchema("non_existent_schema_xyz"));
  }

  // ==================== IDENTITY and Comments Tests ====================

  @Test
  void testIdentityColumn() {
    Column col1 = Column.of("id", Types.IntegerType.get(), "identity col", false, true, null);
    Column col2 = Column.of("data", Types.VarCharType.of(100), "data col", true, false, null);

    Index[] indexes = new Index[] {Indexes.primary("PK_identity", new String[][] {{"id"}})};

    NameIdentifier tableIdent =
        NameIdentifier.of(schemaName, GravitinoITUtils.genRandomName("identity_test"));
    TableCatalog tableCatalog = catalog.asTableCatalog();
    tableCatalog.createTable(
        tableIdent,
        new Column[] {col1, col2},
        "identity test",
        ImmutableMap.of(),
        Transforms.EMPTY_TRANSFORM,
        Distributions.NONE,
        new SortOrder[0],
        indexes);

    Table loaded = tableCatalog.loadTable(tableIdent);
    Assertions.assertTrue(loaded.columns()[0].autoIncrement());
    Assertions.assertFalse(loaded.columns()[0].nullable());
    Assertions.assertFalse(loaded.columns()[1].autoIncrement());
  }

  @Test
  void testTableAndColumnComments() {
    Column col1 = Column.of("col_a", Types.IntegerType.get(), "comment for col_a");
    Column col2 = Column.of("col_b", Types.StringType.get(), "comment for col_b");

    NameIdentifier tableIdent =
        NameIdentifier.of(schemaName, GravitinoITUtils.genRandomName("comment_table"));
    TableCatalog tableCatalog = catalog.asTableCatalog();
    tableCatalog.createTable(
        tableIdent, new Column[] {col1, col2}, "table level comment", ImmutableMap.of());

    Table loaded = tableCatalog.loadTable(tableIdent);
    Assertions.assertEquals("table level comment", loaded.comment());
    Assertions.assertEquals("comment for col_a", loaded.columns()[0].comment());
    Assertions.assertEquals("comment for col_b", loaded.columns()[1].comment());
  }

  @Test
  void testIdentifierNameLimit() {
    // SQL Server supports up to 128 characters for identifiers
    String longName = StringUtils.repeat("a", 128);

    // 128-char name should succeed
    Schema created = catalog.asSchemas().createSchema(longName, null, Collections.emptyMap());
    Assertions.assertEquals(longName, created.name());
    catalog.asSchemas().dropSchema(longName, false);

    // 129-char name should be rejected by capability validation
    String tooLongName = StringUtils.repeat("b", 129);
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> catalog.asSchemas().createSchema(tooLongName, null, Collections.emptyMap()));
  }

  @Test
  void testTableIndexes() {
    Column col1 = Column.of("col_1", Types.LongType.get(), "id", false, false, null);
    Column col2 = Column.of("col_2", Types.VarCharType.of(100), "name", false, false, null);
    Column col3 = Column.of("col_3", Types.IntegerType.get(), "age", true, false, null);

    Index[] indexes =
        new Index[] {
          Indexes.primary("pk_idx", new String[][] {{"col_1"}}),
          Indexes.unique("uq_idx", new String[][] {{"col_2"}}),
        };

    NameIdentifier tableIdent =
        NameIdentifier.of(schemaName, GravitinoITUtils.genRandomName("index_table"));
    TableCatalog tableCatalog = catalog.asTableCatalog();
    tableCatalog.createTable(
        tableIdent,
        new Column[] {col1, col2, col3},
        "index test",
        ImmutableMap.of(),
        Transforms.EMPTY_TRANSFORM,
        Distributions.NONE,
        new SortOrder[0],
        indexes);

    Table loaded = tableCatalog.loadTable(tableIdent);
    Assertions.assertEquals(2, loaded.index().length);

    Map<String, Index> indexMap =
        Arrays.stream(loaded.index()).collect(Collectors.toMap(Index::name, idx -> idx));

    Assertions.assertTrue(indexMap.containsKey("pk_idx"));
    Assertions.assertEquals(Index.IndexType.PRIMARY_KEY, indexMap.get("pk_idx").type());

    Assertions.assertTrue(indexMap.containsKey("uq_idx"));
    Assertions.assertEquals(Index.IndexType.UNIQUE_KEY, indexMap.get("uq_idx").type());
  }

  @Test
  void testCompositeIndexes() {
    Column col1 = Column.of("col_1", Types.IntegerType.get(), null, false, false, null);
    Column col2 = Column.of("col_2", Types.IntegerType.get(), null, false, false, null);
    Column col3 = Column.of("col_3", Types.VarCharType.of(50), null, true, false, null);

    Index[] indexes =
        new Index[] {
          Indexes.primary("pk_composite", new String[][] {{"col_1"}, {"col_2"}}),
          Indexes.unique("uq_composite", new String[][] {{"col_2"}, {"col_3"}}),
        };

    NameIdentifier tableIdent =
        NameIdentifier.of(schemaName, GravitinoITUtils.genRandomName("composite_idx"));
    TableCatalog tableCatalog = catalog.asTableCatalog();
    tableCatalog.createTable(
        tableIdent,
        new Column[] {col1, col2, col3},
        null,
        ImmutableMap.of(),
        Transforms.EMPTY_TRANSFORM,
        Distributions.NONE,
        new SortOrder[0],
        indexes);

    Table loaded = tableCatalog.loadTable(tableIdent);
    Assertions.assertEquals(2, loaded.index().length);

    for (Index idx : loaded.index()) {
      if ("pk_composite".equals(idx.name())) {
        Assertions.assertEquals(2, idx.fieldNames().length);
      } else if ("uq_composite".equals(idx.name())) {
        Assertions.assertEquals(2, idx.fieldNames().length);
      }
    }
  }

  @Test
  void testDeleteIndex() {
    Column col1 = Column.of("id", Types.IntegerType.get(), null, false, false, null);
    Column col2 = Column.of("email", Types.VarCharType.of(200), null, false, false, null);

    String deleteIndexTable = GravitinoITUtils.genRandomName("del_idx_table");
    String uqName = "UQ_" + deleteIndexTable;
    Index[] indexes =
        new Index[] {
          Indexes.primary("PK_" + deleteIndexTable, new String[][] {{"id"}}),
          Indexes.unique(uqName, new String[][] {{"email"}})
        };

    NameIdentifier tableIdent = NameIdentifier.of(schemaName, deleteIndexTable);
    TableCatalog tableCatalog = catalog.asTableCatalog();
    tableCatalog.createTable(
        tableIdent,
        new Column[] {col1, col2},
        null,
        ImmutableMap.of(),
        Transforms.EMPTY_TRANSFORM,
        Distributions.NONE,
        new SortOrder[0],
        indexes);

    // Delete the UNIQUE index
    tableCatalog.alterTable(tableIdent, TableChange.deleteIndex(uqName, false));

    Table loaded = tableCatalog.loadTable(tableIdent);
    Set<String> indexNames =
        Arrays.stream(loaded.index()).map(Index::name).collect(Collectors.toSet());
    Assertions.assertFalse(
        indexNames.contains(uqName), "Deleted index should not appear after deleteIndex");
    Assertions.assertTrue(indexNames.contains("PK_" + deleteIndexTable), "PK should still exist");
  }

  // ==================== Additional Tests ====================

  @Test
  void testSchemaWithIllegalName() {
    SupportsSchemas schemas = catalog.asSchemas();

    // SQL injection attempt should be rejected by capability validation
    String sqlInjection = "test; DROP TABLE important_table; --";
    Assertions.assertThrows(
        IllegalArgumentException.class, () -> schemas.createSchema(sqlInjection, null, null));

    // Name starting with invalid character
    String invalidStart = "1invalid_schema";
    Assertions.assertThrows(
        IllegalArgumentException.class, () -> schemas.createSchema(invalidStart, null, null));
  }

  @Test
  void testDefaultValueRoundTrip() {
    // Test various default value types round-trip through SQL Server
    Column colBool =
        Column.of(
            "col_bool",
            Types.BooleanType.get(),
            "bool default",
            true,
            false,
            Literals.booleanLiteral(true));
    Column colDouble =
        Column.of(
            "col_double",
            Types.DoubleType.get(),
            "double default",
            true,
            false,
            Literals.doubleLiteral(3.14));
    Column colTimestamp =
        Column.of(
            "col_ts",
            Types.TimestampType.withoutTimeZone(),
            "ts default",
            true,
            false,
            DEFAULT_VALUE_OF_CURRENT_TIMESTAMP);

    NameIdentifier tableIdent =
        NameIdentifier.of(schemaName, GravitinoITUtils.genRandomName("default_rt"));
    TableCatalog tableCatalog = catalog.asTableCatalog();
    tableCatalog.createTable(
        tableIdent, new Column[] {colBool, colDouble, colTimestamp}, null, ImmutableMap.of());

    Table loaded = tableCatalog.loadTable(tableIdent);
    Assertions.assertEquals(Literals.booleanLiteral(true), loaded.columns()[0].defaultValue());
    Assertions.assertEquals(Literals.doubleLiteral(3.14), loaded.columns()[1].defaultValue());
    Assertions.assertEquals(DEFAULT_VALUE_OF_CURRENT_TIMESTAMP, loaded.columns()[2].defaultValue());
  }

  @Test
  void testAlterTableMultipleChanges() {
    Column col1 = Column.of("col_a", Types.IntegerType.get(), "comment_a", true, false, null);
    Column col2 = Column.of("col_b", Types.VarCharType.of(50), "comment_b", true, false, null);

    NameIdentifier tableIdent =
        NameIdentifier.of(schemaName, GravitinoITUtils.genRandomName("multi_alter"));
    TableCatalog tableCatalog = catalog.asTableCatalog();
    tableCatalog.createTable(
        tableIdent, new Column[] {col1, col2}, tableComment, ImmutableMap.of());

    // Apply multiple non-rename changes in a single alterTable call
    tableCatalog.alterTable(
        tableIdent,
        TableChange.updateComment("new_table_comment"),
        TableChange.addColumn(new String[] {"col_c"}, Types.LongType.get(), "new col", true),
        TableChange.updateColumnComment(new String[] {"col_a"}, "updated_comment_a"));

    Table loaded = tableCatalog.loadTable(tableIdent);
    Assertions.assertEquals("new_table_comment", loaded.comment());
    Assertions.assertEquals(3, loaded.columns().length);
    Assertions.assertEquals("col_c", loaded.columns()[2].name());
    Assertions.assertEquals(Types.LongType.get(), loaded.columns()[2].dataType());
    Assertions.assertEquals("updated_comment_a", loaded.columns()[0].comment());
  }

  @Test
  void testListTablesExcludesSystemShippedTables() {
    // Verify that listTables() in the dbo schema does not include system-shipped tables
    // such as msreplication_options, spt_fallback_db, spt_fallback_dev, spt_fallback_usg,
    // spt_monitor. These tables have is_ms_shipped = 1 in sys.tables.
    TableCatalog tableCatalog = catalog.asTableCatalog();
    NameIdentifier[] tables = tableCatalog.listTables(Namespace.of("dbo"));
    Set<String> tableNames =
        Arrays.stream(tables).map(NameIdentifier::name).collect(Collectors.toSet());

    // These are well-known system-shipped tables that exist in dbo on most SQL Server instances
    Set<String> systemShippedTables =
        Sets.newHashSet(
            "msreplication_options",
            "spt_fallback_db",
            "spt_fallback_dev",
            "spt_fallback_usg",
            "spt_monitor");
    for (String systemTable : systemShippedTables) {
      Assertions.assertFalse(
          tableNames.contains(systemTable),
          "System-shipped table '" + systemTable + "' should not appear in listTables()");
    }
  }

  @Test
  void testCreateTableInDboSchema() {
    // Verify that tables can be created in the default dbo schema
    Column[] columns = createColumns();
    String dboTableName = GravitinoITUtils.genRandomName("dbo_table");
    NameIdentifier tableIdent = NameIdentifier.of("dbo", dboTableName);

    TableCatalog tableCatalog = catalog.asTableCatalog();
    tableCatalog.createTable(tableIdent, columns, "dbo table", ImmutableMap.of());

    Table loaded = tableCatalog.loadTable(tableIdent);
    Assertions.assertEquals(dboTableName, loaded.name());
    Assertions.assertEquals("dbo table", loaded.comment());

    // Cleanup
    tableCatalog.dropTable(tableIdent);
  }
}
