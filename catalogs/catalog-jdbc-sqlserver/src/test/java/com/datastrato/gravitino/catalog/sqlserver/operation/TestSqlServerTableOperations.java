/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.catalog.sqlserver.operation;

import com.datastrato.gravitino.catalog.sqlserver.converter.SqlServerColumnDefaultValueConverter;
import com.datastrato.gravitino.catalog.sqlserver.converter.SqlServerExceptionConverter;
import com.datastrato.gravitino.catalog.sqlserver.converter.SqlServerTypeConverter;
import java.util.Collections;
import org.apache.gravitino.catalog.jdbc.JdbcColumn;
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

/** Unit tests for SqlServerTableOperations SQL generation. */
public class TestSqlServerTableOperations {

  private TestableSqlServerTableOperations ops;

  /** Testable subclass that exposes protected methods for unit testing. */
  static class TestableSqlServerTableOperations extends SqlServerTableOperations {
    TestableSqlServerTableOperations(String schemaName) {
      super.exceptionMapper = new SqlServerExceptionConverter();
      super.typeConverter = new SqlServerTypeConverter();
      super.columnDefaultValueConverter = new SqlServerColumnDefaultValueConverter();
      // Set the current schema name for SQL generation
      this.currentSchemaName = schemaName;
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
}
