/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.catalog.sqlserver.utils;

/** Utility class for SQL Server catalog operations. */
public class SqlServerUtils {

  private SqlServerUtils() {
    // Utility class, no instantiation
  }

  /**
   * Recursively strips outer parentheses from a string. SQL Server wraps default values in
   * parentheses, e.g., ((0)) -> 0, ('hello') -> 'hello', (getdate()) -> getdate().
   *
   * @param value The string to strip parentheses from
   * @return The string with outer parentheses removed
   */
  public static String stripOuterParentheses(String value) {
    if (value == null) {
      return null;
    }
    String result = value.trim();
    while (result.startsWith("(") && result.endsWith(")")) {
      result = result.substring(1, result.length() - 1).trim();
    }
    return result;
  }

  /**
   * Escapes single quotes in a string for use in SQL Server string literals. This method is shared
   * across SqlServerUtils, SqlServerColumnDefaultValueConverter, and SqlServerTableOperations.
   *
   * @param value The string to escape
   * @return The escaped string, or empty string if null
   */
  public static String escapeQuote(String value) {
    if (value == null) {
      return "";
    }
    return value.replace("'", "''");
  }

  /**
   * Generates SQL to add an extended property (comment) on a table.
   *
   * @param schema The schema name
   * @param table The table name
   * @param comment The comment value
   * @return The sp_addextendedproperty SQL statement
   */
  public static String generateAddTableCommentSql(String schema, String table, String comment) {
    return String.format(
        "EXEC sp_addextendedproperty @name=N'MS_Description', @value=N'%s', "
            + "@level0type=N'SCHEMA', @level0name=N'%s', "
            + "@level1type=N'TABLE', @level1name=N'%s'",
        escapeQuote(comment), escapeQuote(schema), escapeQuote(table));
  }

  /**
   * Generates SQL to add an extended property (comment) on a column.
   *
   * @param schema The schema name
   * @param table The table name
   * @param column The column name
   * @param comment The comment value
   * @return The sp_addextendedproperty SQL statement
   */
  public static String generateAddColumnCommentSql(
      String schema, String table, String column, String comment) {
    return String.format(
        "EXEC sp_addextendedproperty @name=N'MS_Description', @value=N'%s', "
            + "@level0type=N'SCHEMA', @level0name=N'%s', "
            + "@level1type=N'TABLE', @level1name=N'%s', "
            + "@level2type=N'COLUMN', @level2name=N'%s'",
        escapeQuote(comment), escapeQuote(schema), escapeQuote(table), escapeQuote(column));
  }

  /**
   * Generates SQL to update an extended property (comment) on a table.
   *
   * @param schema The schema name
   * @param table The table name
   * @param comment The new comment value
   * @return The sp_updateextendedproperty SQL statement
   */
  public static String generateUpdateTableCommentSql(String schema, String table, String comment) {
    return String.format(
        "EXEC sp_updateextendedproperty @name=N'MS_Description', @value=N'%s', "
            + "@level0type=N'SCHEMA', @level0name=N'%s', "
            + "@level1type=N'TABLE', @level1name=N'%s'",
        escapeQuote(comment), escapeQuote(schema), escapeQuote(table));
  }

  /**
   * Generates SQL to update an extended property (comment) on a column.
   *
   * @param schema The schema name
   * @param table The table name
   * @param column The column name
   * @param comment The new comment value
   * @return The sp_updateextendedproperty SQL statement
   */
  public static String generateUpdateColumnCommentSql(
      String schema, String table, String column, String comment) {
    return String.format(
        "EXEC sp_updateextendedproperty @name=N'MS_Description', @value=N'%s', "
            + "@level0type=N'SCHEMA', @level0name=N'%s', "
            + "@level1type=N'TABLE', @level1name=N'%s', "
            + "@level2type=N'COLUMN', @level2name=N'%s'",
        escapeQuote(comment), escapeQuote(schema), escapeQuote(table), escapeQuote(column));
  }

  /**
   * Generates SQL to drop an extended property (comment) from a table.
   *
   * @param schema The schema name
   * @param table The table name
   * @return The sp_dropextendedproperty SQL statement
   */
  public static String generateDropTableCommentSql(String schema, String table) {
    return String.format(
        "EXEC sp_dropextendedproperty @name=N'MS_Description', "
            + "@level0type=N'SCHEMA', @level0name=N'%s', "
            + "@level1type=N'TABLE', @level1name=N'%s'",
        escapeQuote(schema), escapeQuote(table));
  }

  /**
   * Generates SQL to drop an extended property (comment) from a column.
   *
   * @param schema The schema name
   * @param table The table name
   * @param column The column name
   * @return The sp_dropextendedproperty SQL statement
   */
  public static String generateDropColumnCommentSql(String schema, String table, String column) {
    return String.format(
        "EXEC sp_dropextendedproperty @name=N'MS_Description', "
            + "@level0type=N'SCHEMA', @level0name=N'%s', "
            + "@level1type=N'TABLE', @level1name=N'%s', "
            + "@level2type=N'COLUMN', @level2name=N'%s'",
        escapeQuote(schema), escapeQuote(table), escapeQuote(column));
  }

  /**
   * Generates SQL to check if an extended property exists and then add, update, or drop it. Uses
   * sys.fn_listextendedproperty to check existence instead of BEGIN TRY/CATCH, so real errors
   * (permission denied, connection issues) are not swallowed.
   *
   * @param schema The schema name
   * @param table The table name
   * @param column The column name (null for table-level comment)
   * @param comment The new comment value (null/empty to drop the comment)
   * @return The T-SQL batch statement
   */
  public static String generateCheckAndSetCommentSql(
      String schema, String table, String column, String comment) {
    String escapedSchema = escapeQuote(schema);
    String escapedTable = escapeQuote(table);
    boolean isColumnLevel = column != null;
    String escapedColumn = isColumnLevel ? escapeQuote(column) : null;

    // Build the EXISTS check using sys.fn_listextendedproperty
    String existsCheck;
    if (isColumnLevel) {
      existsCheck =
          String.format(
              "SELECT 1 FROM sys.fn_listextendedproperty("
                  + "N'MS_Description', N'SCHEMA', N'%s', N'TABLE', N'%s', N'COLUMN', N'%s')",
              escapedSchema, escapedTable, escapedColumn);
    } else {
      existsCheck =
          String.format(
              "SELECT 1 FROM sys.fn_listextendedproperty("
                  + "N'MS_Description', N'SCHEMA', N'%s', N'TABLE', N'%s', NULL, NULL)",
              escapedSchema, escapedTable);
    }

    StringBuilder sb = new StringBuilder();
    boolean hasNewComment = comment != null && !comment.isEmpty();

    if (hasNewComment) {
      // If comment exists -> update, else -> add
      sb.append(String.format("IF EXISTS (%s)\n", existsCheck));
      if (isColumnLevel) {
        sb.append(generateUpdateColumnCommentSql(schema, table, column, comment));
      } else {
        sb.append(generateUpdateTableCommentSql(schema, table, comment));
      }
      sb.append("\nELSE\n");
      if (isColumnLevel) {
        sb.append(generateAddColumnCommentSql(schema, table, column, comment));
      } else {
        sb.append(generateAddTableCommentSql(schema, table, comment));
      }
    } else {
      // Drop only if exists
      sb.append(String.format("IF EXISTS (%s)\n", existsCheck));
      if (isColumnLevel) {
        sb.append(generateDropColumnCommentSql(schema, table, column));
      } else {
        sb.append(generateDropTableCommentSql(schema, table));
      }
    }
    return sb.toString();
  }
}
