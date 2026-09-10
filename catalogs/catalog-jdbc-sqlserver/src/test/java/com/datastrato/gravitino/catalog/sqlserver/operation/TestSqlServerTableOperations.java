/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.catalog.sqlserver.operation;

import com.datastrato.gravitino.catalog.sqlserver.converter.SqlServerColumnDefaultValueConverter;
import com.datastrato.gravitino.catalog.sqlserver.converter.SqlServerExceptionConverter;
import com.datastrato.gravitino.catalog.sqlserver.converter.SqlServerTypeConverter;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import org.apache.gravitino.catalog.jdbc.JdbcColumn;
import org.apache.gravitino.catalog.jdbc.JdbcTable;
import org.apache.gravitino.exceptions.NoSuchColumnException;
import org.apache.gravitino.exceptions.NoSuchTableException;
import org.apache.gravitino.rel.TableChange;
import org.apache.gravitino.rel.expressions.distributions.Distributions;
import org.apache.gravitino.rel.expressions.literals.Literals;
import org.apache.gravitino.rel.expressions.transforms.Transforms;
import org.apache.gravitino.rel.indexes.Index;
import org.apache.gravitino.rel.indexes.Indexes;
import org.apache.gravitino.rel.types.Types;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mockito;

/** Unit tests for SqlServerTableOperations SQL generation. */
public class TestSqlServerTableOperations {

  private TestableSqlServerTableOperations ops;

  /** Testable subclass that exposes protected methods for unit testing. */
  static class TestableSqlServerTableOperations extends SqlServerTableOperations {
    Connection connection;
    JdbcTable loadedTable;

    TestableSqlServerTableOperations(String schemaName) {
      super.exceptionMapper = new SqlServerExceptionConverter();
      super.typeConverter = new SqlServerTypeConverter();
      super.columnDefaultValueConverter = new SqlServerColumnDefaultValueConverter();
      // Set the current schema name for SQL generation
      this.currentSchemaName = schemaName;
    }

    /** {@inheritDoc} */
    @Override
    protected Connection getConnection(String schema) {
      return connection;
    }

    /** {@inheritDoc} */
    @Override
    protected JdbcTable getOrCreateTable(String schema, String table, JdbcTable previous) {
      return loadedTable;
    }

    String alter(TableChange change) {
      return generateAlterTableSql("dbo", "MixedTable", change);
    }

    public String testCreateTableSql(
        String tableName, JdbcColumn[] columns, String comment, Index[] indexes) {
      return generateCreateTableSql(
          tableName,
          columns,
          comment,
          Collections.emptyMap(),
          Transforms.EMPTY_TRANSFORM,
          Distributions.NONE,
          indexes);
    }

    public String testRenameTableSql(String oldName, String newName) {
      return generateRenameTableSql(oldName, newName);
    }

    public String testDropTableSql(String tableName) {
      return generateDropTableSql(tableName);
    }

    public String testPurgeTableSql(String tableName) {
      return generatePurgeTableSql(tableName);
    }
  }

  @BeforeEach
  void setUp() {
    ops = new TestableSqlServerTableOperations("dbo");
  }

  @Test
  void testCreateTableBasic() {
    JdbcColumn col1 =
        JdbcColumn.builder()
            .withName("id")
            .withType(Types.IntegerType.get())
            .withNullable(false)
            .build();
    JdbcColumn col2 =
        JdbcColumn.builder()
            .withName("name")
            .withType(Types.VarCharType.of(100))
            .withNullable(true)
            .build();

    String sql =
        ops.testCreateTableSql(
            "test_table", new JdbcColumn[] {col1, col2}, null, Indexes.EMPTY_INDEXES);
    Assertions.assertTrue(sql.contains("[dbo].[test_table]"));
    Assertions.assertTrue(sql.contains("[id] int NOT NULL"));
    Assertions.assertTrue(sql.contains("[name] varchar(100) NULL"));
  }

  @Test
  void testCreateTableWithIdentity() {
    JdbcColumn col1 =
        JdbcColumn.builder()
            .withName("id")
            .withType(Types.IntegerType.get())
            .withNullable(false)
            .withAutoIncrement(true)
            .build();

    Index pk = Indexes.primary("PK_test", new String[][] {{"id"}});
    String sql =
        ops.testCreateTableSql("test_table", new JdbcColumn[] {col1}, null, new Index[] {pk});
    Assertions.assertTrue(sql.contains("IDENTITY(1,1)"));
    Assertions.assertTrue(sql.contains("CONSTRAINT [PK_test] PRIMARY KEY ([id])"));
  }

  @Test
  void testCreateTableWithPrimaryKeyAndUnique() {
    JdbcColumn col1 =
        JdbcColumn.builder()
            .withName("id")
            .withType(Types.IntegerType.get())
            .withNullable(false)
            .withAutoIncrement(true)
            .build();
    JdbcColumn col2 =
        JdbcColumn.builder()
            .withName("email")
            .withType(Types.VarCharType.of(255))
            .withNullable(false)
            .build();

    Index pk = Indexes.primary("PK_users", new String[][] {{"id"}});
    Index uq = Indexes.unique("UQ_email", new String[][] {{"email"}});
    String sql =
        ops.testCreateTableSql("users", new JdbcColumn[] {col1, col2}, null, new Index[] {pk, uq});
    Assertions.assertTrue(sql.contains("CONSTRAINT [PK_users] PRIMARY KEY ([id])"));
    Assertions.assertTrue(sql.contains("CONSTRAINT [UQ_email] UNIQUE ([email])"));
  }

  @Test
  void testCreateTableWithComments() {
    JdbcColumn col1 =
        JdbcColumn.builder()
            .withName("id")
            .withType(Types.IntegerType.get())
            .withNullable(false)
            .withComment("Primary key column")
            .build();

    String sql =
        ops.testCreateTableSql(
            "test_table", new JdbcColumn[] {col1}, "Table comment", Indexes.EMPTY_INDEXES);
    Assertions.assertTrue(sql.contains("sp_addextendedproperty"));
    Assertions.assertTrue(sql.contains("@value=N'Table comment'"));
    Assertions.assertTrue(sql.contains("@value=N'Primary key column'"));
  }

  @Test
  void testCreateTableWithDefaultValue() {
    JdbcColumn col1 =
        JdbcColumn.builder()
            .withName("status")
            .withType(Types.IntegerType.get())
            .withNullable(false)
            .withDefaultValue(Literals.integerLiteral(0))
            .build();

    String sql =
        ops.testCreateTableSql("test_table", new JdbcColumn[] {col1}, null, Indexes.EMPTY_INDEXES);
    Assertions.assertTrue(sql.contains("DEFAULT 0"));
  }

  @Test
  void testRenameTableSql() {
    String sql = ops.testRenameTableSql("old_table", "new_table");
    Assertions.assertTrue(sql.contains("sp_rename"));
    Assertions.assertTrue(sql.contains("dbo"));
    Assertions.assertTrue(sql.contains("old_table"));
    Assertions.assertTrue(sql.contains("new_table"));
  }

  @Test
  void testDropTableSql() {
    String sql = ops.testDropTableSql("test_table");
    Assertions.assertEquals("DROP TABLE [dbo].[test_table]", sql);
  }

  @Test
  void testPurgeTableSqlThrows() {
    Assertions.assertThrows(
        UnsupportedOperationException.class, () -> ops.testPurgeTableSql("test_table"));
  }

  @Test
  void testColumnPositionRejected() {
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () ->
            ops.generateAlterTableSql(
                "dbo",
                "test_table",
                TableChange.updateColumnPosition(
                    new String[] {"col1"}, TableChange.ColumnPosition.first())));
  }

  @Test
  void testCreateTableWithNvarcharColumn() {
    JdbcColumn col1 =
        JdbcColumn.builder()
            .withName("description")
            .withType(Types.StringType.get())
            .withNullable(true)
            .build();

    String sql =
        ops.testCreateTableSql("test_table", new JdbcColumn[] {col1}, null, Indexes.EMPTY_INDEXES);
    Assertions.assertTrue(sql.contains("nvarchar"));
  }

  @Test
  void testCreateTableWithAutoIncrementDefaultPkName() {
    JdbcColumn col1 =
        JdbcColumn.builder()
            .withName("id")
            .withType(Types.IntegerType.get())
            .withNullable(false)
            .withAutoIncrement(true)
            .build();

    // Primary key without explicit name should get default PK_tableName
    Index pk = Indexes.primary(null, new String[][] {{"id"}});
    String sql =
        ops.testCreateTableSql("my_table", new JdbcColumn[] {col1}, null, new Index[] {pk});
    Assertions.assertTrue(sql.contains("CONSTRAINT [PK_my_table] PRIMARY KEY"));
  }

  @ParameterizedTest
  @CsvSource({
    "nchar,20,0,0,[nchar](10)",
    "nvarchar,200,0,0,[nvarchar](100)",
    "nvarchar,-1,0,0,[nvarchar](max)",
    "varchar,-1,0,0,[varchar](max)",
    "char,10,0,0,[char](10)",
    "varbinary,-1,0,0,[varbinary](max)",
    "numeric,17,38,0,'[numeric](38,0)'",
    "datetimeoffset,10,0,7,[datetimeoffset](7)",
    "datetime2,8,0,0,[datetime2](0)",
    "float,8,53,0,[float](53)"
  })
  void testNullabilityPreservesNativeType(
      String name, int length, int precision, int scale, String expected) throws Exception {
    ResultSet rs = nativeColumn(name, length, precision, scale);
    String collation = name.contains("char") ? "Chinese_PRC_CI_AS" : null;
    Mockito.when(rs.getString("collation_name")).thenReturn(collation);
    String suffix = collation == null ? "" : " COLLATE " + collation;
    for (boolean nullable : new boolean[] {true, false}) {
      Assertions.assertEquals(
          "ALTER TABLE [dbo].[MixedTable] ALTER COLUMN [col] "
              + expected
              + suffix
              + (nullable ? " NULL;" : " NOT NULL;"),
          ops.alter(TableChange.updateColumnNullability(new String[] {"col"}, nullable)));
    }
  }

  @Test
  void testNullabilityPreservesAliasType() throws Exception {
    ResultSet rs = nativeColumn("AliasType", 20, 0, 0);
    Mockito.when(rs.getBoolean("is_user_defined")).thenReturn(true);
    Mockito.when(rs.getString("TYPE_SCHEMA")).thenReturn("CustomSchema");
    Assertions.assertTrue(
        ops.alter(TableChange.updateColumnNullability(new String[] {"col"}, false))
            .contains("[CustomSchema].[AliasType] NOT NULL"));
  }

  @Test
  void testNullabilityDoesNotGuessWhenNativeMetadataIsMissing() throws Exception {
    ResultSet rs = nativeColumn("nvarchar", 20, 0, 0);
    Mockito.when(rs.next()).thenReturn(false);
    Assertions.assertThrows(
        NoSuchColumnException.class,
        () -> ops.alter(TableChange.updateColumnNullability(new String[] {"col"}, false)));
  }

  @Test
  void testColumnAliasesMatchWithoutAcceptingMissingColumns() throws Exception {
    nativeColumn("nvarchar", 20, 0, 0);
    Assertions.assertTrue(ops.columnExists(ops.loadedTable, "col"));
    Assertions.assertTrue(ops.columnExists(ops.loadedTable, "COL"));
    Assertions.assertFalse(ops.columnExists(ops.loadedTable, "missing"));
    Assertions.assertEquals("Col", ops.getJdbcColumnFromTable(ops.loadedTable, "COL").name());
    Assertions.assertThrows(
        NoSuchColumnException.class, () -> ops.getJdbcColumnFromTable(ops.loadedTable, "missing"));
  }

  @Test
  void testMixedCaseUniqueIndexMetadata() throws SQLException {
    Connection connection = Mockito.mock(Connection.class);
    DatabaseMetaData metadata = Mockito.mock(DatabaseMetaData.class);
    ResultSet primary = Mockito.mock(ResultSet.class);
    ResultSet unique = Mockito.mock(ResultSet.class);
    Mockito.when(connection.getMetaData()).thenReturn(metadata);
    Mockito.when(metadata.getPrimaryKeys(null, "dbo", "mixedtable")).thenReturn(primary);
    Mockito.when(metadata.getIndexInfo(null, "dbo", "mixedtable", true, false)).thenReturn(unique);
    Mockito.when(unique.next()).thenReturn(true, false);
    Mockito.when(unique.getShort("TYPE")).thenReturn(DatabaseMetaData.tableIndexOther);
    Mockito.when(unique.getString("TABLE_NAME")).thenReturn("MixedTable");
    Mockito.when(unique.getString("INDEX_NAME")).thenReturn("UniqueCol");
    Mockito.when(unique.getString("COLUMN_NAME")).thenReturn("Col");
    Mockito.when(unique.getInt("ORDINAL_POSITION")).thenReturn(1);
    List<Index> indexes = ops.getIndexes(connection, "dbo", "mixedtable");
    Assertions.assertEquals(1, indexes.size());
    Assertions.assertEquals(Index.IndexType.UNIQUE_KEY, indexes.get(0).type());
    Assertions.assertEquals("UniqueCol", indexes.get(0).name());
    Assertions.assertArrayEquals(new String[] {"Col"}, indexes.get(0).fieldNames()[0]);
  }

  @Test
  void testMixedCaseMetadataMatchingAndPatternFiltering() throws SQLException {
    ResultSet rs = Mockito.mock(ResultSet.class);
    Mockito.when(rs.next()).thenReturn(true, true, false);
    Mockito.when(rs.getString("TABLE_NAME")).thenReturn("mixedXtable", "Mixed_Table");
    Mockito.when(rs.getString("TABLE_SCHEM")).thenReturn("DBO");
    Assertions.assertNotNull(ops.getTableBuilder(rs, "dbo", "mixed_table"));
    Assertions.assertThrows(
        NoSuchTableException.class, () -> ops.getTableBuilder(rs, "dbo", "missing"));
    Mockito.when(rs.getString("TABLE_NAME")).thenReturn("Mixed_Table");
    Mockito.when(rs.getString("TYPE_NAME")).thenReturn("int");
    Assertions.assertNotNull(ops.getColumnBuilder(rs, "dbo", "mixed_table"));
    Assertions.assertNull(ops.getColumnBuilder(rs, "dbo", "mixedXtable"));
  }

  private ResultSet nativeColumn(String type, int length, int precision, int scale)
      throws Exception {
    ops.connection = Mockito.mock(Connection.class);
    PreparedStatement statement = Mockito.mock(PreparedStatement.class);
    ResultSet rs = Mockito.mock(ResultSet.class);
    Mockito.when(ops.connection.prepareStatement(Mockito.anyString())).thenReturn(statement);
    Mockito.when(statement.executeQuery()).thenReturn(rs);
    Mockito.when(rs.next()).thenReturn(true);
    Mockito.when(rs.getString("TYPE_NAME")).thenReturn(type);
    Mockito.when(rs.getInt("max_length")).thenReturn(length);
    Mockito.when(rs.getInt("precision")).thenReturn(precision);
    Mockito.when(rs.getInt("scale")).thenReturn(scale);
    ops.loadedTable = Mockito.mock(JdbcTable.class);
    Mockito.when(ops.loadedTable.name()).thenReturn("MixedTable");
    Mockito.when(ops.loadedTable.columns())
        .thenReturn(
            new JdbcColumn[] {
              JdbcColumn.builder().withName("Col").withType(Types.StringType.get()).build()
            });
    return rs;
  }
}
