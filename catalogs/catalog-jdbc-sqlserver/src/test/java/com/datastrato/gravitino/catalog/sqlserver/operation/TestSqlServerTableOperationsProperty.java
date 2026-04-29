/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.catalog.sqlserver.operation;

import com.datastrato.gravitino.catalog.sqlserver.utils.SqlServerUtils;
import java.util.stream.Stream;
import org.apache.gravitino.catalog.jdbc.JdbcColumn;
import org.apache.gravitino.rel.indexes.Index;
import org.apache.gravitino.rel.indexes.Indexes;
import org.apache.gravitino.rel.types.Type;
import org.apache.gravitino.rel.types.Types;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Property-style tests for SqlServerTableOperations SQL generation. Validates Properties 4, 5, 9,
 * and 12 from the task list.
 */
public class TestSqlServerTableOperationsProperty {

  // --- Property 12: Identifier quoting wraps in square brackets ---

  @ParameterizedTest
  @ValueSource(strings = {"simple", "with space", "special#chars", "CamelCase", "123numeric"})
  void testIdentifierQuotingWrapsInSquareBrackets(String identifier) {
    TestSqlServerTableOperations.TestableSqlServerTableOperations ops =
        new TestSqlServerTableOperations.TestableSqlServerTableOperations("dbo");
    // The quoting is embedded in SQL generation; verify via CREATE TABLE
    JdbcColumn col =
        JdbcColumn.builder()
            .withName(identifier)
            .withType(Types.IntegerType.get())
            .withNullable(true)
            .build();
    String sql =
        ops.testCreateTableSql("test", new JdbcColumn[] {col}, null, Indexes.EMPTY_INDEXES);
    Assertions.assertTrue(
        sql.contains("[" + identifier + "]"),
        "Column name should be wrapped in square brackets: " + sql);
  }

  // --- Property 4: CREATE TABLE SQL generation includes all specified elements ---

  /** Provides various column configurations for CREATE TABLE testing. */
  static Stream<Arguments> createTableConfigurations() {
    return Stream.of(
        Arguments.of("int_col", Types.IntegerType.get(), false, false, "int NOT NULL"),
        Arguments.of("varchar_col", Types.VarCharType.of(100), true, false, "varchar(100) NULL"),
        Arguments.of("nvarchar_col", Types.StringType.get(), true, false, "nvarchar(max) NULL"),
        Arguments.of(
            "decimal_col", Types.DecimalType.of(10, 2), false, false, "decimal(10,2) NOT NULL"),
        Arguments.of("id_col", Types.IntegerType.get(), false, true, "IDENTITY(1,1)"));
  }

  @ParameterizedTest
  @MethodSource("createTableConfigurations")
  void testCreateTableContainsColumnDefinition(
      String colName,
      Type colType,
      boolean nullable,
      boolean autoIncrement,
      String expectedFragment) {
    TestSqlServerTableOperations.TestableSqlServerTableOperations ops =
        new TestSqlServerTableOperations.TestableSqlServerTableOperations("dbo");

    JdbcColumn.Builder builder =
        JdbcColumn.builder()
            .withName(colName)
            .withType(colType)
            .withNullable(nullable)
            .withAutoIncrement(autoIncrement);
    JdbcColumn col = builder.build();

    Index[] indexes = Indexes.EMPTY_INDEXES;
    if (autoIncrement) {
      indexes = new Index[] {Indexes.primary("PK_test", new String[][] {{colName}})};
    }

    String sql = ops.testCreateTableSql("test_table", new JdbcColumn[] {col}, null, indexes);
    Assertions.assertTrue(
        sql.contains(expectedFragment),
        String.format("SQL should contain '%s' but was: %s", expectedFragment, sql));
  }

  @Test
  void testCreateTableWithPrimaryKeyContainsNamedConstraint() {
    TestSqlServerTableOperations.TestableSqlServerTableOperations ops =
        new TestSqlServerTableOperations.TestableSqlServerTableOperations("dbo");
    JdbcColumn col =
        JdbcColumn.builder()
            .withName("id")
            .withType(Types.IntegerType.get())
            .withNullable(false)
            .build();
    Index pk = Indexes.primary("PK_my_table", new String[][] {{"id"}});
    String sql = ops.testCreateTableSql("my_table", new JdbcColumn[] {col}, null, new Index[] {pk});
    Assertions.assertTrue(sql.contains("CONSTRAINT [PK_my_table] PRIMARY KEY ([id])"));
  }

  @Test
  void testCreateTableWithCommentContainsExtendedProperty() {
    TestSqlServerTableOperations.TestableSqlServerTableOperations ops =
        new TestSqlServerTableOperations.TestableSqlServerTableOperations("dbo");
    JdbcColumn col =
        JdbcColumn.builder()
            .withName("id")
            .withType(Types.IntegerType.get())
            .withNullable(false)
            .withComment("ID column")
            .build();
    String sql =
        ops.testCreateTableSql(
            "my_table", new JdbcColumn[] {col}, "My table", Indexes.EMPTY_INDEXES);
    Assertions.assertTrue(sql.contains("sp_addextendedproperty"));
    Assertions.assertTrue(sql.contains("@value=N'My table'"));
    Assertions.assertTrue(sql.contains("@value=N'ID column'"));
  }

  // --- Property 9: Extended property SQL generation produces valid sp_ calls ---

  static Stream<Arguments> extendedPropertyCases() {
    return Stream.of(
        Arguments.of("dbo", "users", null, "User table"),
        Arguments.of("sales", "orders", null, "Order table"),
        Arguments.of("dbo", "users", "name", "User's name"),
        Arguments.of("hr", "employees", "email", "Employee email"));
  }

  @ParameterizedTest
  @MethodSource("extendedPropertyCases")
  void testAddExtendedPropertySqlContainsRequiredElements(
      String schema, String table, String column, String comment) {
    String sql;
    if (column == null) {
      sql = SqlServerUtils.generateAddTableCommentSql(schema, table, comment);
    } else {
      sql = SqlServerUtils.generateAddColumnCommentSql(schema, table, column, comment);
    }
    Assertions.assertTrue(sql.contains("sp_addextendedproperty"));
    Assertions.assertTrue(sql.contains("@name=N'MS_Description'"));
    Assertions.assertTrue(sql.contains("@level0type=N'SCHEMA'"));
    Assertions.assertTrue(sql.contains("@level0name=N'" + schema + "'"));
    Assertions.assertTrue(sql.contains("@level1type=N'TABLE'"));
    Assertions.assertTrue(sql.contains("@level1name=N'" + table + "'"));
    if (column != null) {
      Assertions.assertTrue(sql.contains("@level2type=N'COLUMN'"));
      Assertions.assertTrue(sql.contains("@level2name=N'" + column + "'"));
    }
  }

  @ParameterizedTest
  @MethodSource("extendedPropertyCases")
  void testUpdateExtendedPropertySqlContainsRequiredElements(
      String schema, String table, String column, String comment) {
    String sql;
    if (column == null) {
      sql = SqlServerUtils.generateUpdateTableCommentSql(schema, table, comment);
    } else {
      sql = SqlServerUtils.generateUpdateColumnCommentSql(schema, table, column, comment);
    }
    Assertions.assertTrue(sql.contains("sp_updateextendedproperty"));
    Assertions.assertTrue(sql.contains("@name=N'MS_Description'"));
  }

  @ParameterizedTest
  @MethodSource("extendedPropertyCases")
  void testDropExtendedPropertySqlContainsRequiredElements(
      String schema, String table, String column, String comment) {
    String sql;
    if (column == null) {
      sql = SqlServerUtils.generateDropTableCommentSql(schema, table);
    } else {
      sql = SqlServerUtils.generateDropColumnCommentSql(schema, table, column);
    }
    Assertions.assertTrue(sql.contains("sp_dropextendedproperty"));
    Assertions.assertTrue(sql.contains("@name=N'MS_Description'"));
  }

  // --- Property 5: ALTER TABLE SQL generation ---

  @Test
  void testDropTableSqlContainsSchemaAndTable() {
    TestSqlServerTableOperations.TestableSqlServerTableOperations ops =
        new TestSqlServerTableOperations.TestableSqlServerTableOperations("sales");
    String sql = ops.testDropTableSql("orders");
    Assertions.assertEquals("DROP TABLE [sales].[orders]", sql);
  }

  @Test
  void testRenameTableSqlUsesSpRename() {
    TestSqlServerTableOperations.TestableSqlServerTableOperations ops =
        new TestSqlServerTableOperations.TestableSqlServerTableOperations("dbo");
    String sql = ops.testRenameTableSql("old_name", "new_name");
    Assertions.assertTrue(sql.contains("sp_rename"));
    Assertions.assertTrue(sql.contains("dbo"));
    Assertions.assertTrue(sql.contains("old_name"));
    Assertions.assertTrue(sql.contains("new_name"));
  }

  /** Verify extended property SQL escapes single quotes in comments. */
  @Test
  void testExtendedPropertyEscapesSingleQuotes() {
    String sql = SqlServerUtils.generateAddTableCommentSql("dbo", "test", "It's a table");
    Assertions.assertTrue(sql.contains("It''s a table"));
  }
}
