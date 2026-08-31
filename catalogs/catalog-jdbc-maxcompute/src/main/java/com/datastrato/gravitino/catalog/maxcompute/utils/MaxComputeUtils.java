/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.catalog.maxcompute.utils;

/** Utility class for MaxCompute catalog operations. */
public final class MaxComputeUtils {

  private static final String BACK_QUOTE = "`";

  private MaxComputeUtils() {
    // Utility class, prevent instantiation
  }

  /**
   * Quotes an identifier with backticks.
   *
   * @param identifier the identifier to quote
   * @return the quoted identifier
   */
  public static String quoteIdentifier(String identifier) {
    return BACK_QUOTE + identifier + BACK_QUOTE;
  }

  /**
   * Quotes a table name properly for MaxCompute SQL.
   *
   * <p>Handles different formats:
   *
   * <ul>
   *   <li>"table" becomes "`table`"
   *   <li>"schema.table" becomes "schema.`table`"
   *   <li>"project.schema.table" becomes "project.schema.`table`"
   * </ul>
   *
   * <p>Note: MaxCompute JDBC driver has issues parsing fully quoted three-layer names, so we only
   * quote the table name part. Schema and project names are typically system-controlled and don't
   * contain special characters.
   *
   * @param tableName the table name, possibly qualified with schema or project. schema
   * @return the properly quoted table name
   */
  public static String quoteTableName(String tableName) {
    if (tableName == null) {
      return "";
    }
    if (tableName.contains(".")) {
      // Only quote the last part (table name) to avoid JDBC driver parsing issues
      String[] parts = tableName.split("\\.");
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < parts.length - 1; i++) {
        if (i > 0) {
          sb.append(".");
        }
        sb.append(parts[i]);
      }
      sb.append(".").append(quoteIdentifier(parts[parts.length - 1]));
      return sb.toString();
    }
    return quoteIdentifier(tableName);
  }

  /**
   * Escapes special characters in a string for SQL.
   *
   * <p>Uses SQL-standard escaping: single quotes are escaped by doubling them. This method can be
   * used for both comments and values.
   *
   * @param str the string to escape (comment or value)
   * @return the escaped string
   */
  public static String escapeSql(String str) {
    if (str == null) {
      return "";
    }
    return str.replace("'", "''");
  }
}
